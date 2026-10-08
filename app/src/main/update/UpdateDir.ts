import fs from 'node:fs';
import path from 'node:path';
import { findBundledDaemon, type BundledDaemon } from '../daemon/BundledDaemon';

/** Written when an update is ready: the running version will be replaced by `to`. */
export interface PendingUpdate {
  from: string;
  to: string;
  at: string;
}

/** Written when the daemon of `failedVersion` did not start and the previous bundle runs instead. */
export interface RollbackRecord {
  failedVersion: string;
  usingVersion: string;
  reason: string;
  at: string;
}

/**
 * `<userData>/update`: the daemon bundle of the version before an update (`previous/codeloupe`) and two small records.
 * Settings, secrets and indexes are elsewhere (userData/settings.json, the daemon's home) and no update touches them.
 */
export class UpdateDir {
  constructor(readonly dir: string) {}

  /** Holds `codeloupe/`, laid out like `<resources>/codeloupe`, so findBundledDaemon reads it. */
  get previousRoot(): string {
    return path.join(this.dir, 'previous');
  }

  readPending(): PendingUpdate | null {
    return this.read<PendingUpdate>('pending.json', v => typeof v.from === 'string' && typeof v.to === 'string');
  }

  writePending(p: PendingUpdate): void {
    this.write('pending.json', p);
  }

  clearPending(): void {
    fs.rmSync(path.join(this.dir, 'pending.json'), { force: true });
  }

  readRollback(): RollbackRecord | null {
    return this.read<RollbackRecord>('rollback.json', v => typeof v.failedVersion === 'string' && typeof v.usingVersion === 'string');
  }

  writeRollback(r: RollbackRecord): void {
    this.write('rollback.json', r);
  }

  clearRollback(): void {
    fs.rmSync(path.join(this.dir, 'rollback.json'), { force: true });
  }

  /** The kept bundle of the previous version, when it was copied completely. */
  previousBundle(): BundledDaemon | null {
    if (!fs.existsSync(path.join(this.previousRoot, 'codeloupe', '.complete'))) return null;
    return findBundledDaemon(this.previousRoot);
  }

  /**
   * Copies the running bundle to `previous/`, replacing an older copy. Done while the new version is still only
   * downloaded: afterwards the installer overwrites the bundle in place.
   */
  async snapshot(bundle: string, version: string): Promise<void> {
    // The app was restarted before it installed: the copy of this version is already there.
    try { if (fs.readFileSync(path.join(this.previousRoot, 'codeloupe', '.complete'), 'utf8') === version) return; } catch { /* no copy yet */ }
    const tmp = path.join(this.dir, 'previous.tmp');
    fs.rmSync(tmp, { recursive: true, force: true });
    await fs.promises.cp(bundle, path.join(tmp, 'codeloupe'), { recursive: true, verbatimSymlinks: true });
    fs.writeFileSync(path.join(tmp, 'codeloupe', '.complete'), version);
    fs.rmSync(this.previousRoot, { recursive: true, force: true });
    fs.renameSync(tmp, this.previousRoot);
  }

  removePrevious(): void {
    fs.rmSync(this.previousRoot, { recursive: true, force: true });
  }

  private read<T extends object>(name: string, valid: (v: Record<string, unknown>) => boolean): T | null {
    try {
      const v = JSON.parse(fs.readFileSync(path.join(this.dir, name), 'utf8'));
      return v && typeof v === 'object' && valid(v) ? (v as T) : null;
    } catch {
      return null;
    }
  }

  private write(name: string, value: object): void {
    fs.mkdirSync(this.dir, { recursive: true });
    const file = path.join(this.dir, name);
    fs.writeFileSync(`${file}.tmp`, JSON.stringify(value, null, 2));
    fs.renameSync(`${file}.tmp`, file);
  }
}
