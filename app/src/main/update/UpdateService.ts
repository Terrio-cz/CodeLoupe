import { EventEmitter } from 'node:events';
import type { UpdateState } from '../../shared/ipc';
import { findRelease, type ReleaseInfo } from './ReleaseFeed';
import type { UpdateMode } from './updateMode';

/** Downloads and installs a release's installer; the real one wraps electron-updater (ElectronEngine). */
export interface UpdateEngine {
  /** Downloads what the feed at `feedUrl` offers, checking its SHA-512; null when it offers nothing newer than the running version. */
  download(feedUrl: string, onProgress: (percent: number) => void): Promise<{ version: string } | null>;
  /** Closes the app and runs the downloaded installer; `relaunch` starts the new version when it is done. */
  quitAndInstall(relaunch: boolean): void;
}

export interface UpdateDeps {
  current: string;
  mode: UpdateMode;
  /** The "automatic updates" setting. */
  enabled(): boolean;
  fetchText(url: string): Promise<string>;
  engine: UpdateEngine | null;
  /**
   * A feed directory on this machine (verification only, loopback): replaces GitHub. An installation that updates itself
   * takes `latest.yml` and the installer from it; one that only notifies reads `releases.atom` from it.
   */
  feedOverride?: string | null;
  /** Runs before the install: keeps the running daemon bundle for a rollback. A failure does not stop the update. */
  beforeInstall(version: string): Promise<void>;
  /** Install as soon as the download is done, without the user's click (verification only). */
  installWhenReady?: boolean;
  /** Start the new version after the install; false only in the update test, which starts it with its own environment. */
  relaunch?: boolean;
  now?(): Date;
}

const FIRST_CHECK_MS = 30_000;
const CHECK_EVERY_MS = 6 * 3_600_000;

/**
 * Looks for a newer release now and then and, where the installation can update itself, downloads it and waits for the
 * user to restart into it. Everywhere else it only reports that a release exists. The only host it asks is the release
 * feed (GitHub), once per check; nothing else is sent. Switched off in Settings it never checks by itself.
 */
export class UpdateService extends EventEmitter {
  private state: UpdateState;
  private timer: NodeJS.Timeout | null = null;
  private working: Promise<void> | null = null;

  constructor(private readonly deps: UpdateDeps) {
    super();
    const { mode } = deps;
    this.state = {
      phase: mode.kind === 'unavailable' || !deps.enabled() ? 'off' : 'idle',
      mode: mode.kind === 'install' ? 'install' : mode.kind === 'notify' ? 'notify' : 'unavailable',
      reason: mode.kind === 'install' ? null : mode.reason,
      current: deps.current, latest: null, releaseUrl: null, percent: null, message: null, checkedAt: null, rollback: null,
    };
  }

  get current(): UpdateState {
    return this.state;
  }

  /** Checks 30 seconds after the start and every six hours, while automatic updates are on. */
  start(firstCheckMs = FIRST_CHECK_MS, everyMs = CHECK_EVERY_MS): void {
    const tick = () => {
      if (this.deps.enabled() && this.deps.mode.kind !== 'unavailable') void this.check();
      this.timer = setTimeout(tick, everyMs);
    };
    this.timer = setTimeout(tick, firstCheckMs);
  }

  dispose(): void {
    if (this.timer) clearTimeout(this.timer);
  }

  /** The setting changed: an idle service shows it. */
  settingsChanged(): void {
    if (this.deps.mode.kind === 'unavailable' || this.working || this.state.phase === 'ready' || this.state.phase === 'available') return;
    this.set({ phase: this.deps.enabled() ? 'idle' : 'off', message: null });
  }

  setRollback(rollback: UpdateState['rollback']): void {
    this.set({ rollback });
  }

  /** One check; asked for by the user or by the timer. A check or download in progress is not repeated. */
  check(): Promise<UpdateState> {
    if (this.deps.mode.kind === 'unavailable') return Promise.resolve(this.state);
    if (!this.working && this.state.phase !== 'ready') {
      this.working = this.run().finally(() => { this.working = null; });
    }
    return (this.working ?? Promise.resolve()).then(() => this.state);
  }

  /** Restarts into the downloaded update. */
  install(): void {
    if (this.state.phase !== 'ready' || !this.deps.engine) return;
    this.deps.engine.quitAndInstall(this.deps.relaunch ?? true);
  }

  private async run(): Promise<void> {
    const { deps } = this;
    this.set({ phase: 'checking', message: null, percent: null });
    try {
      const override = deps.feedOverride ?? null;
      const engine = deps.mode.kind === 'install' ? deps.engine : null;
      const release = engine && override ? null : await findRelease(deps.current, deps.fetchText, override ? `${override}releases.atom` : undefined);
      const checkedAt = (deps.now?.() ?? new Date()).toISOString();
      if (!engine) {
        this.set(release
          ? { phase: 'available', latest: release.version, releaseUrl: release.pageUrl, checkedAt }
          : { phase: 'idle', latest: null, releaseUrl: null, checkedAt });
        return;
      }
      if (!override && !release) {
        this.set({ phase: 'idle', latest: null, releaseUrl: null, checkedAt });
        return;
      }
      await this.download(engine, override ?? (release as ReleaseInfo).feedUrl, release, checkedAt);
    } catch (e) {
      this.set({ phase: 'error', message: (e as Error).message.split('\n')[0], percent: null });
    }
  }

  private async download(engine: UpdateEngine, feedUrl: string, release: ReleaseInfo | null, checkedAt: string): Promise<void> {
    this.set({ phase: 'downloading', latest: release?.version ?? null, releaseUrl: release?.pageUrl ?? null, percent: 0, checkedAt });
    const got = await engine.download(feedUrl, percent => this.set({ percent }));
    if (!got) {
      this.set({ phase: 'idle', latest: null, releaseUrl: null, percent: null });
      return;
    }
    let message: string | null = null;
    try {
      await this.deps.beforeInstall(got.version);
    } catch (e) {
      message = `Could not keep the previous daemon for a rollback: ${(e as Error).message}`;
    }
    this.set({ phase: 'ready', latest: got.version, percent: 100, message });
    if (this.deps.installWhenReady) this.install();
  }

  private set(patch: Partial<UpdateState>): void {
    this.state = { ...this.state, ...patch };
    this.emit('state', this.state);
  }
}
