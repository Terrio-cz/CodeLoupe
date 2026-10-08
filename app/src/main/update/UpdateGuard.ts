import type { EventEmitter } from 'node:events';
import type { DaemonState } from '../../shared/ipc';
import type { BundledDaemon } from '../daemon/BundledDaemon';
import type { RollbackRecord, UpdateDir } from './UpdateDir';

/** What the guard needs of the daemon manager. */
export interface GuardedManager extends EventEmitter {
  readonly current: DaemonState;
  restart(): Promise<DaemonState>;
}

export interface GuardDeps {
  /** Version of the running app. */
  version: string;
  /** The daemon bundle the installation ships; null when there is none (development run). */
  shipped: BundledDaemon | null;
  files: UpdateDir;
  manager: GuardedManager;
  /** Runs this bundle as the daemon from now on. */
  useBundle(bundle: BundledDaemon): void;
  onRollback(record: RollbackRecord): void;
  /** Something the user should hear about (the new daemon did not start). */
  tell(message: string): void;
  /** How long the new daemon has to come up. */
  timeoutMs?: number;
}

const TIMEOUT_MS = 90_000;

/**
 * After an update the new daemon must come up; when it does not, the previous bundle (kept by the update) runs
 * instead and the user is told. Only the first run of the new version is watched: once the daemon of the new bundle
 * answered, the old bundle is deleted.
 */
export class UpdateGuard {
  private done = false;
  private restarted = false;
  private timer: NodeJS.Timeout | null = null;
  private onState: ((s: DaemonState) => void) | null = null;
  private onFailed: ((message: string | null) => void) | null = null;

  constructor(private readonly deps: GuardDeps) {}

  /**
   * Before the first daemon start: the bundle to run instead of the shipped one (a rollback of this version is in
   * force), or null. A rollback of another version is over: that update was replaced by a newer one.
   */
  static bundleInForce(files: UpdateDir, version: string): { bundle: BundledDaemon; record: RollbackRecord } | null {
    const record = files.readRollback();
    if (!record) return null;
    if (record.failedVersion === version) {
      const bundle = files.previousBundle();
      if (bundle) return { bundle, record };
    }
    files.clearRollback();
    return null;
  }

  /** Starts watching when this is the first run after an update. Returns whether it is. */
  watch(): boolean {
    const { files, version, shipped } = this.deps;
    const pending = files.readPending();
    if (!pending || pending.to !== version || pending.from === version || !shipped) return false;
    this.onState = s => this.observe(s, shipped.version);
    this.onFailed = message => void this.fail(message ?? 'start failed');
    this.deps.manager.on('state', this.onState);
    this.deps.manager.on('failed', this.onFailed);
    this.deps.manager.on('gaveUp', this.onFailed);
    this.timer = setTimeout(() => void this.fail('the daemon did not respond within 90 seconds'), this.deps.timeoutMs ?? TIMEOUT_MS);
    this.onState(this.deps.manager.current);
    return true;
  }

  dispose(): void {
    this.stop();
  }

  /**
   * The daemon answers: with the new version that is the end of the watch. With an older one (it outlived the app,
   * which an AppImage update does) it is restarted from the new bundle, once.
   */
  private observe(s: DaemonState, bundleVersion: string): void {
    if (s.phase !== 'running' || !s.status) return;
    if (s.status.version === bundleVersion) {
      this.succeed();
    } else if (!this.restarted && !s.manualStop) {
      this.restarted = true;
      void this.deps.manager.restart().catch(() => undefined);
    }
  }

  private succeed(): void {
    if (this.done) return;
    this.stop();
    this.deps.files.clearPending();
    this.deps.files.clearRollback();
    // The new daemon runs; its predecessor's files are only disk now.
    this.deps.files.removePrevious();
  }

  private async fail(reason: string): Promise<void> {
    if (this.done) return;
    this.stop();
    const { files, version, manager } = this.deps;
    const pending = files.readPending();
    files.clearPending();
    const previous = files.previousBundle();
    if (!previous || !pending) {
      this.deps.tell(`The daemon of new version ${version} did not start (${reason}) and no previous version is kept.`);
      return;
    }
    this.deps.useBundle(previous);
    const state = await manager.restart().catch(() => manager.current);
    if (state.phase !== 'running') {
      // The old bundle fails too: the cause is not the bundle (a foreign process on the port, say). Leave it be.
      if (this.deps.shipped) this.deps.useBundle(this.deps.shipped);
      this.deps.tell(`The daemon did not start from previous version ${pending.from} either: ${state.message ?? reason}`);
      return;
    }
    const record: RollbackRecord = { failedVersion: version, usingVersion: pending.from, reason, at: new Date().toISOString() };
    files.writeRollback(record);
    this.deps.onRollback(record);
    this.deps.tell(`The daemon of version ${version} did not start (${reason}); previous version ${pending.from} is running.`);
  }

  private stop(): void {
    this.done = true;
    if (this.timer) clearTimeout(this.timer);
    const m = this.deps.manager;
    if (this.onState) m.off('state', this.onState);
    if (this.onFailed) { m.off('failed', this.onFailed); m.off('gaveUp', this.onFailed); }
  }
}
