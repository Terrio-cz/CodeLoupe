import { describe, expect, it, vi } from 'vitest';
import type { DaemonStatus } from '../src/shared/contract';
import { DEFAULT_SETTINGS } from '../src/shared/settings';
import { DaemonManager, resolveCommand } from '../src/main/daemon/DaemonManager';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { DaemonClient } from '../src/main/daemon/DaemonClient';
import type { DaemonHome, DaemonInfo } from '../src/main/daemon/DaemonHome';

const status = (pid: number, name = 'codeloupe'): DaemonStatus => ({
  name: name as 'codeloupe', version: '0.1.0', pid, port: 47391, home: 'h', uptimeSec: 1, rssMb: 70, heapMb: 10, cpuSec: 0,
  calls: { total: 0, errors: 0, busy: 0 }, queue: { fast: { running: null, waiting: [] }, heavy: { running: null, waiting: [] } }, repos: [],
});

/** A fake daemon: `up` decides whether /status answers, `pid` what it reports, daemon.json holds `filePid`. */
function setup(opts: { up?: boolean; pid?: number; filePid?: number | null; stoppedMarker?: boolean; startedAfterStop?: boolean } = {}) {
  const world = { up: opts.up ?? false, pid: opts.pid ?? 100, filePid: opts.filePid === undefined ? 100 : opts.filePid, name: 'codeloupe', marker: !!opts.stoppedMarker, startedAfterStop: opts.startedAfterStop ?? true };
  const client = { status: vi.fn(async () => { if (!world.up) throw new Error('down'); return status(world.pid, world.name); }) } as unknown as DaemonClient;
  const home = {
    port: () => 47391,
    info: (): DaemonInfo | null => (world.filePid === null ? null : { pid: world.filePid, port: 47391 }),
    stoppedByUser: () => world.marker,
    setStoppedByUser: (v: boolean) => { world.marker = v; },
    startedAfterStop: () => world.startedAfterStop,
  } as unknown as DaemonHome;
  const cli = vi.fn(async (verb: 'start' | 'stop') => { world.up = verb === 'start'; return ''; });
  const m = new DaemonManager(client, home, () => DEFAULT_SETTINGS, () => 1000, cli);
  return { m, world, cli };
}

describe('DaemonManager', () => {
  it('starts the daemon when it is down at app start', async () => {
    const { m, cli } = setup();
    await m.check(true);
    await vi.waitFor(() => expect(m.current.phase).toBe('running'));
    expect(cli).toHaveBeenCalledWith('start');
  });

  it('starts it again after it dies (three missed checks once it ran)', async () => {
    const { m, world, cli } = setup({ up: true });
    await m.check(true);
    world.up = false;
    await m.check(true);
    await m.check(true);
    expect(cli).not.toHaveBeenCalled();
    await m.check(true);
    await vi.waitFor(() => expect(m.current.phase).toBe('running'));
    expect(cli).toHaveBeenCalledWith('start');
  });

  it('does not restart a daemon the user stopped', async () => {
    const { m, cli } = setup({ up: true });
    await m.check(true);
    await m.stop();
    expect(m.current.phase).toBe('stopped');
    for (let i = 0; i < 4; i++) await m.check(true);
    expect(cli).toHaveBeenCalledTimes(1);
    expect(cli).toHaveBeenCalledWith('stop');
  });

  it('honours the stop marker written by `codeloupe stop`', async () => {
    const { m, cli } = setup({ stoppedMarker: true });
    await m.check(true);
    expect(m.current.phase).toBe('stopped');
    expect(cli).not.toHaveBeenCalled();
  });

  it('waits three ticks for daemon.json before calling the daemon foreign', async () => {
    const { m, world } = setup({ up: true, pid: 200, filePid: 100 });
    await m.check(true);
    expect(m.current.phase).toBe('starting');
    expect(m.trusted).toBe(false);
    await m.check(true);
    await m.check(true);
    expect(m.current.phase).toBe('error');
    world.filePid = 200;
    await m.check(true);
    expect(m.current.phase).toBe('running');
    expect(m.trusted).toBe(true);
  });

  it('treats another service on the port as an error at once', async () => {
    const { m, world } = setup({ up: true });
    world.name = 'something-else';
    await m.check(true);
    expect(m.current.phase).toBe('error');
    expect(m.trusted).toBe(false);
  });

  it('writes the stop marker on a manual stop and clears it on start', async () => {
    const { m, world } = setup({ up: true });
    await m.check(true);
    await m.stop();
    expect(world.marker).toBe(true);
    await m.start();
    expect(world.marker).toBe(false);
    expect(m.current.phase).toBe('running');
  });

  it('clears a stale marker when someone else started the daemon', async () => {
    const { m, world } = setup({ up: true, stoppedMarker: true });
    await m.check(true);
    expect(m.current.phase).toBe('running');
    expect(world.marker).toBe(false);
  });

  it('keeps the stop while the stopped daemon is still shutting down', async () => {
    const { m, world, cli } = setup({ up: true, stoppedMarker: true, startedAfterStop: false });
    await m.check(true);
    expect(world.marker).toBe(true);
    expect(m.current.manualStop).toBe(true);
    world.up = false;
    for (let i = 0; i < 4; i++) await m.check(true);
    expect(m.current.phase).toBe('stopped');
    expect(cli).not.toHaveBeenCalled();
  });

  it('checks asked for by the page never count as missed ticks', async () => {
    const { m, world, cli } = setup({ up: true });
    await m.check(true);
    world.up = false;
    for (let i = 0; i < 5; i++) await m.check(false);
    expect(m.current.phase).toBe('running');
    expect(cli).not.toHaveBeenCalled();
  });

  it('gives up after five failed starts and says so once', async () => {
    const { m } = setupFailing();
    const gaveUp = vi.fn();
    m.on('gaveUp', gaveUp);
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      for (let i = 0; i < 12; i++) {
        await m.check(true);
        // Let the auto-start that check() kicked off fail before the next tick.
        for (let j = 0; j < 5; j++) await new Promise(r => setTimeout(r, 0));
        vi.setSystemTime(Date.now() + 61_000);
      }
    } finally {
      vi.useRealTimers();
    }
    expect(gaveUp).toHaveBeenCalledTimes(1);
    expect(m.current.phase).toBe('error');
  });

  it('a restart is not reported as an outage', async () => {
    const { m } = setup({ up: true });
    await m.check(true);
    const phases: string[] = [];
    m.on('phase', p => phases.push(p));
    await m.restart();
    expect(phases).not.toContain('down');
    expect(m.current.phase).toBe('running');
  });
});

function setupFailing() {
  const client = { status: vi.fn(async () => { throw new Error('down'); }) } as unknown as DaemonClient;
  const home = { port: () => 47391, info: () => null, stoppedByUser: () => false, setStoppedByUser: () => undefined } as unknown as DaemonHome;
  const cli = vi.fn(async () => { throw new Error('not found'); });
  return { m: new DaemonManager(client, home, () => DEFAULT_SETTINGS, () => 1000, cli), cli };
}

describe('resolveCommand', () => {
  it('refuses a .cmd shim on Windows and resolves an .exe', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cl-path-'));
    fs.writeFileSync(path.join(dir, 'codeloupe.cmd'), '');
    expect(() => resolveCommand('codeloupe', { PATH: dir }, 'win32')).toThrow(/bez shellu/);
    fs.writeFileSync(path.join(dir, 'clx.exe'), '');
    expect(resolveCommand('clx', { PATH: dir }, 'win32')).toBe(path.join(dir, 'clx.exe'));
    expect(resolveCommand('C:/x/node.exe', { PATH: dir }, 'win32')).toBe('C:/x/node.exe');
    expect(resolveCommand('codeloupe', { PATH: dir }, 'linux')).toBe('codeloupe');
    // An .exe later on PATH wins over an earlier .cmd shim.
    const later = fs.mkdtempSync(path.join(os.tmpdir(), 'cl-path-'));
    fs.writeFileSync(path.join(later, 'codeloupe.exe'), '');
    expect(resolveCommand('codeloupe', { PATH: `${dir};${later}` }, 'win32')).toBe(path.join(later, 'codeloupe.exe'));
  });
});
