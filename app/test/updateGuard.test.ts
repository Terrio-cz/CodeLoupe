import { EventEmitter } from 'node:events';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import type { DaemonStatus } from '../src/shared/contract';
import type { DaemonState } from '../src/shared/ipc';
import type { BundledDaemon } from '../src/main/daemon/BundledDaemon';
import { UpdateDir } from '../src/main/update/UpdateDir';
import { UpdateGuard, type GuardedManager } from '../src/main/update/UpdateGuard';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'cl-guard-'));

/** A daemon bundle like gradle/bundle.gradle.kts lays it out, with `version` in its jar name. */
function makeBundle(dir: string, version: string): string {
  const root = path.join(dir, 'codeloupe');
  fs.mkdirSync(path.join(root, 'lib'), { recursive: true });
  fs.mkdirSync(path.join(root, 'runtime', 'bin'), { recursive: true });
  fs.writeFileSync(path.join(root, 'lib', `codeloupe-${version}.jar`), `jar ${version}`);
  fs.writeFileSync(path.join(root, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java'), 'java');
  return root;
}

const state = (phase: DaemonState['phase'], version?: string, over: Partial<DaemonState> = {}): DaemonState => ({
  phase, status: version ? ({ name: 'codeloupe', version } as DaemonStatus) : null, port: 1, message: null, manualStop: false, checkedAt: null, ...over,
});

class FakeManager extends EventEmitter implements GuardedManager {
  current: DaemonState = state('unknown');
  restart = vi.fn(async () => this.current);
  push(s: DaemonState): void { this.current = s; this.emit('state', s); }
}

describe('UpdateDir', () => {
  it('keeps a complete copy of the running bundle that runs like the original', async () => {
    const root = tmp();
    const files = new UpdateDir(path.join(root, 'update'));
    expect(files.previousBundle()).toBeNull();
    await files.snapshot(makeBundle(path.join(root, 'res'), '0.9.0-rc.1'), '0.9.0-rc.1');
    const prev = files.previousBundle();
    expect(prev?.version).toBe('0.9.0-rc.1');
    expect(prev?.command.startsWith(files.previousRoot)).toBe(true);
    // An interrupted copy is never used.
    fs.rmSync(path.join(files.previousRoot, 'codeloupe', '.complete'));
    expect(files.previousBundle()).toBeNull();
  });
});

describe('UpdateGuard', () => {
  async function guard(opts: { pending?: boolean; withPrevious?: boolean; timeoutMs?: number } = {}) {
    const root = tmp();
    const files = new UpdateDir(path.join(root, 'update'));
    if (opts.withPrevious !== false) await files.snapshot(makeBundle(path.join(root, 'old'), '0.9.0-rc.1'), '0.9.0-rc.1');
    if (opts.pending !== false) files.writePending({ from: '0.9.0-rc.1', to: '0.9.0-rc.2', at: 'now' });
    const shipped: BundledDaemon = { command: 'new-java', args: [], version: '0.9.0-rc.2' };
    const manager = new FakeManager();
    const used: BundledDaemon[] = [];
    const told: string[] = [];
    const rolledBack: unknown[] = [];
    const g = new UpdateGuard({
      version: '0.9.0-rc.2', shipped, files, manager,
      useBundle: b => used.push(b), onRollback: r => rolledBack.push(r), tell: m => told.push(m), timeoutMs: opts.timeoutMs ?? 90_000,
    });
    return { g, files, manager, shipped, used, told, rolledBack };
  }

  it('does nothing when the app was not just updated', async () => {
    const { g, manager } = await guard({ pending: false });
    expect(g.watch()).toBe(false);
    manager.push(state('running', '0.9.0-rc.2'));
  });

  it('ends the watch and deletes the old bundle when the new daemon answers', async () => {
    const { g, files, manager } = await guard();
    expect(g.watch()).toBe(true);
    manager.push(state('starting'));
    expect(files.previousBundle()).not.toBeNull();
    manager.push(state('running', '0.9.0-rc.2'));
    expect(files.readPending()).toBeNull();
    expect(files.previousBundle()).toBeNull();
    expect(fs.existsSync(files.previousRoot)).toBe(false);
  });

  it('restarts an older daemon that outlived the update, once, and succeeds when the new one answers', async () => {
    const { g, manager, files } = await guard();
    g.watch();
    manager.push(state('running', '0.9.0-rc.1'));
    manager.push(state('running', '0.9.0-rc.1'));
    expect(manager.restart).toHaveBeenCalledOnce();
    manager.push(state('running', '0.9.0-rc.2'));
    expect(files.readPending()).toBeNull();
  });

  it('leaves a daemon the user stopped alone', async () => {
    const { g, manager } = await guard();
    g.watch();
    manager.push(state('running', '0.9.0-rc.1', { manualStop: true }));
    expect(manager.restart).not.toHaveBeenCalled();
  });

  it('rolls back to the previous bundle when the new daemon cannot be started', async () => {
    const { g, manager, files, used, told, rolledBack } = await guard();
    manager.restart.mockImplementationOnce(async () => { manager.current = state('running', '0.9.0-rc.1'); return manager.current; });
    g.watch();
    manager.emit('failed', 'Spuštění selhalo: Error: Unable to access jarfile');
    await vi.waitFor(() => expect(rolledBack).toHaveLength(1));
    expect(used).toHaveLength(1);
    expect(used[0].version).toBe('0.9.0-rc.1');
    expect(manager.restart).toHaveBeenCalledOnce();
    expect(files.readPending()).toBeNull();
    expect(files.readRollback()).toMatchObject({ failedVersion: '0.9.0-rc.2', usingVersion: '0.9.0-rc.1' });
    expect(told[0]).toContain('běží předchozí verze 0.9.0-rc.1');
    // The previous bundle stays: it is the one that runs while the rollback is in force.
    expect(UpdateGuard.bundleInForce(files, '0.9.0-rc.2')?.bundle.version).toBe('0.9.0-rc.1');
    expect(UpdateGuard.bundleInForce(files, '0.9.0-rc.3')).toBeNull();
    expect(files.readRollback()).toBeNull();
  });

  it('rolls back when the new daemon is silent for too long', async () => {
    vi.useFakeTimers();
    try {
      const { g, manager, rolledBack } = await guard({ timeoutMs: 5_000 });
      manager.restart.mockImplementationOnce(async () => { manager.current = state('running', '0.9.0-rc.1'); return manager.current; });
      g.watch();
      manager.push(state('error', undefined, { message: 'Daemon po spuštění neodpovídá na /status.' }));
      await vi.advanceTimersByTimeAsync(5_001);
      expect(rolledBack).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('goes back to the shipped bundle when the previous one fails too', async () => {
    const { g, manager, used, told, files } = await guard();
    manager.restart.mockImplementationOnce(async () => { manager.current = state('error', undefined, { message: 'port taken' }); return manager.current; });
    g.watch();
    manager.emit('gaveUp', 'x');
    await vi.waitFor(() => expect(told).toHaveLength(1));
    expect(used.map(b => b.version)).toEqual(['0.9.0-rc.1', '0.9.0-rc.2']);
    expect(files.readRollback()).toBeNull();
    expect(told[0]).toContain('ani z předchozí verze');
  });

  it('only reports the failure when no previous bundle was kept', async () => {
    const { g, manager, used, told } = await guard({ withPrevious: false });
    g.watch();
    manager.emit('failed', 'boom');
    await vi.waitFor(() => expect(told).toHaveLength(1));
    expect(used).toEqual([]);
    expect(manager.restart).not.toHaveBeenCalled();
  });
});
