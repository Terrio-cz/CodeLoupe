import { describe, expect, it, vi } from 'vitest';
import type { DaemonStatus } from '../src/shared/contract';
import { DEFAULT_SETTINGS } from '../src/shared/settings';
import { DaemonManager } from '../src/main/daemon/DaemonManager';
import type { DaemonClient } from '../src/main/daemon/DaemonClient';
import type { DaemonHome, DaemonInfo } from '../src/main/daemon/DaemonHome';

const status = (pid: number, name = 'codeloupe'): DaemonStatus => ({
  name: name as 'codeloupe', version: '0.1.0', pid, port: 47391, home: 'h', uptimeSec: 1, rssMb: 70, heapMb: 10, cpuSec: 0,
  calls: { total: 0, errors: 0, busy: 0 }, queue: { fast: { running: null, waiting: [] }, heavy: { running: null, waiting: [] } }, repos: [],
});

/** A fake daemon: `up` decides whether /status answers, `pid` what it reports, daemon.json holds `filePid`. */
function setup(opts: { up?: boolean; pid?: number; filePid?: number | null; stoppedMarker?: boolean } = {}) {
  const world = { up: opts.up ?? false, pid: opts.pid ?? 100, filePid: opts.filePid === undefined ? 100 : opts.filePid, name: 'codeloupe', marker: !!opts.stoppedMarker };
  const client = { status: vi.fn(async () => { if (!world.up) throw new Error('down'); return status(world.pid, world.name); }) } as unknown as DaemonClient;
  const home = {
    port: () => 47391,
    info: (): DaemonInfo | null => (world.filePid === null ? null : { pid: world.filePid, port: 47391 }),
    stoppedByUser: () => world.marker,
  } as unknown as DaemonHome;
  const cli = vi.fn(async (verb: 'start' | 'stop') => { world.up = verb === 'start'; return ''; });
  const m = new DaemonManager(client, home, () => DEFAULT_SETTINGS, () => 1000, cli);
  return { m, world, cli };
}

describe('DaemonManager', () => {
  it('starts the daemon when it is down at app start', async () => {
    const { m, cli } = setup();
    await m.check();
    await vi.waitFor(() => expect(m.current.phase).toBe('running'));
    expect(cli).toHaveBeenCalledWith('start');
  });

  it('starts it again after it dies (three missed checks once it ran)', async () => {
    const { m, world, cli } = setup({ up: true });
    await m.check();
    world.up = false;
    await m.check();
    await m.check();
    expect(cli).not.toHaveBeenCalled();
    await m.check();
    await vi.waitFor(() => expect(m.current.phase).toBe('running'));
    expect(cli).toHaveBeenCalledWith('start');
  });

  it('does not restart a daemon the user stopped', async () => {
    const { m, cli } = setup({ up: true });
    await m.check();
    await m.stop();
    expect(m.current.phase).toBe('stopped');
    for (let i = 0; i < 4; i++) await m.check();
    expect(cli).toHaveBeenCalledTimes(1);
    expect(cli).toHaveBeenCalledWith('stop');
  });

  it('honours the stop marker written by `codeloupe stop`', async () => {
    const { m, cli } = setup({ stoppedMarker: true });
    await m.check();
    expect(m.current.phase).toBe('stopped');
    expect(cli).not.toHaveBeenCalled();
  });

  it('waits three ticks for daemon.json before calling the daemon foreign', async () => {
    const { m, world } = setup({ up: true, pid: 200, filePid: 100 });
    await m.check();
    expect(m.current.phase).toBe('starting');
    expect(m.trusted).toBe(false);
    await m.check();
    await m.check();
    expect(m.current.phase).toBe('error');
    world.filePid = 200;
    await m.check();
    expect(m.current.phase).toBe('running');
    expect(m.trusted).toBe(true);
  });

  it('treats another service on the port as an error at once', async () => {
    const { m, world } = setup({ up: true });
    world.name = 'something-else';
    await m.check();
    expect(m.current.phase).toBe('error');
    expect(m.trusted).toBe(false);
  });

  it('a restart is not reported as an outage', async () => {
    const { m } = setup({ up: true });
    await m.check();
    const phases: string[] = [];
    m.on('phase', p => phases.push(p));
    await m.restart();
    expect(phases).not.toContain('down');
    expect(m.current.phase).toBe('running');
  });
});
