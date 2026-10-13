import type { EventEmitter } from 'node:events';
import type { DaemonState } from '../../shared/ipc';
import type { BundledDaemon } from '../daemon/BundledDaemon';
import type { RollbackRecord, UpdateDir } from './UpdateDir';

/** What the guard needs of the daemon manager. */
export interface GuardedManager extends EventEmitter {
  readonly current: DaemonState;
  restart(): Promise<DaemonState>;
  /** Reads /status now. */
  check(): Promise<DaemonState>;
}

export interface GuardDeps {
  /** Version of the running app. */
  version: string;
  /** The daemon bundle the installation ships; null when there is none (development run) or the user runs a command of their own. */
  shipped: BundledDaemon | null;
  files: UpdateDir;
  manager: GuardedManager;
  /** Runs this bundle as the daemon from now on. */
  useBundle(bundle: BundledDaemon): void;
  /** A rollback is in force (record), or the new daemon answered after all and it is over (null). */
  onRollback(record: RollbackRecord | null): void;
  /** Something the user should hear about (the new daemon did not start). */
  tell(message: string): void;
  /** How long the new daemon has to come up before the guard looks at what the manager is doing. */
  timeoutMs?: number;
  /** The longest the new daemon is waited for while its start is still in progress. */
  maxWaitMs?: number;
  /** Pause between two restarts of an older daemon. */
  restartPauseMs?: number;
}

const TIMEOUT_MS = 90_000;
const MAX_WAIT_MS = 300_000;
const EXTEND_MS = 30_000;
const MAX_RESTARTS = 3;
const RESTART_PAUSE_MS = 5_000;

/**
 * After an update the new daemon must come up; when it does not, the previous bundle (kept by the update) runs
 * instead and the user is told. Only the first run of the new version is watched: once the daemon of the new bundle
 * answered, the old bundle is deleted.
 *
 * The first start after an update is slow and racy (CL-254): the silent installer ends the old daemon and a daemon of
 * another build (the `codeloupe` on PATH that Claude Code's hooks start) can take the port seconds later, so a daemon that
 * outlived the update is restarted up to three times, and the deadline is not final while the manager is still starting
 * one. A rollback is not final either: when a daemon of the version that "failed" answers /status later, the rollback
 * record is cleared and the shipped bundle is used again, so the message in Settings follows the real daemon.
 */
export class UpdateGuard {
  private done = false;
  private restarts = 0;
  private restarting = false;
  private startedAt = 0;
  private timer: NodeJS.Timeout | null = null;
  private retry: NodeJS.Timeout | null = null;
  private onState: ((s: DaemonState) => void) | null = null;
  private onFailed: ((message: string | null) => void) | null = null;
  private onRecovery: ((s: DaemonState) => void) | null = null;

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

  /**
   * Starts watching when this is the first run after an update (returns true), or, when a rollback of this version is in
   * force, waits for its daemon to answer after all (returns false).
   */
  watch(): boolean {
    const { files, version, shipped } = this.deps;
    if (!shipped) return false;
    const pending = files.readPending();
    if (!pending || pending.to !== version || pending.from === version) {
      const record = files.readRollback();
      if (record?.failedVersion === version) this.watchRecovery(record, shipped);
      return false;
    }
    this.startedAt = Date.now();
    this.onState = s => this.observe(s, shipped.version);
    this.onFailed = message => void this.fail(message ?? 'start failed');
    this.deps.manager.on('state', this.onState);
    this.deps.manager.on('failed', this.onFailed);
    this.deps.manager.on('gaveUp', this.onFailed);
    this.timer = setTimeout(() => void this.expire(), this.deps.timeoutMs ?? TIMEOUT_MS);
    this.onState(this.deps.manager.current);
    return true;
  }

  dispose(): void {
    this.stop();
    this.stopRecovery();
  }

  /**
   * The daemon answers: with the new version that is the end of the watch. With an older one (it outlived the app,
   * which an AppImage update does, or another build took the port) it is restarted from the new bundle, a few times.
   */
  private observe(s: DaemonState, bundleVersion: string): void {
    if (this.done || s.phase !== 'running' || !s.status) return;
    if (s.status.version === bundleVersion) {
      this.succeed();
    } else if (!this.restarting && this.restarts < MAX_RESTARTS && !s.manualStop) {
      this.restarts++;
      this.restarting = true;
      void this.deps.manager.restart().catch(() => undefined).finally(() => {
        this.restarting = false;
        if (this.done || !this.deps.shipped) return;
        const shipped = this.deps.shipped;
        // Still the old daemon after the restart: someone started it again meanwhile. Try again after a pause.
        this.retry = setTimeout(() => this.observe(this.deps.manager.current, shipped.version), this.deps.restartPauseMs ?? RESTART_PAUSE_MS);
        this.retry.unref();
      });
    }
  }

  /** The deadline: a daemon that answers now is fine, one that is still being started gets more time, up to a limit. */
  private async expire(): Promise<void> {
    if (this.done) return;
    const state = await this.deps.manager.check().catch(() => this.deps.manager.current);
    if (this.done) return;
    if (this.deps.shipped) this.observe(state, this.deps.shipped.version);
    if (this.done) return;
    const waited = Date.now() - this.startedAt;
    const starting = state.phase === 'starting' || state.phase === 'stopping' || this.restarting;
    if (starting && waited < (this.deps.maxWaitMs ?? MAX_WAIT_MS)) {
      this.timer = setTimeout(() => void this.expire(), EXTEND_MS);
      return;
    }
    void this.fail(`the daemon did not respond within ${Math.round(waited / 1000)} seconds`);
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
    if (this.deps.shipped) this.watchRecovery(record, this.deps.shipped);
  }

  /** The daemon of the "failed" version answers: the rollback was wrong or the start was only late. Back to the shipped bundle. */
  private watchRecovery(record: RollbackRecord, shipped: BundledDaemon): void {
    if (this.onRecovery) return;
    this.onRecovery = s => {
      if (s.phase !== 'running' || s.status?.version !== record.failedVersion) return;
      this.stopRecovery();
      this.deps.files.clearRollback();
      this.deps.files.removePrevious();
      this.deps.useBundle(shipped);
      this.deps.onRollback(null);
    };
    this.deps.manager.on('state', this.onRecovery);
    this.onRecovery(this.deps.manager.current);
  }

  private stopRecovery(): void {
    if (this.onRecovery) this.deps.manager.off('state', this.onRecovery);
    this.onRecovery = null;
  }

  private stop(): void {
    this.done = true;
    if (this.timer) clearTimeout(this.timer);
    if (this.retry) clearTimeout(this.retry);
    const m = this.deps.manager;
    if (this.onState) m.off('state', this.onState);
    if (this.onFailed) { m.off('failed', this.onFailed); m.off('gaveUp', this.onFailed); }
  }
}
