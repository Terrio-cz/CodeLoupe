import { describe, expect, it } from 'vitest';
import { JobActions } from '../src/main/actions/JobActions';
import { HttpError } from '../src/main/daemon/DaemonClient';
import type { SlotSnapshot } from '../src/shared/jobs';

const entry = (id: string, seconds: number) => ({ id, since: new Date(Date.parse('2026-10-13T12:00:00Z') - seconds * 1000).toISOString(), seconds });
const slots: SlotSnapshot[] = [
  { name: 'gradle-test', capacity: 1, configured: null, invalid: null, running: ['J-RUN'], waiting: ['J-A', 'J-B', 'J-C'], holders: [entry('J-RUN', 90)], queue: [entry('J-A', 300), entry('J-B', 200), entry('J-C', 100)] },
  { name: 'vps-test', capacity: 2, configured: 2, invalid: null, running: [], waiting: ['J-V'], holders: [], queue: [entry('J-V', 5)] },
];

function setup(failWith?: Error) {
  const posts: { path: string; body: unknown }[] = [];
  const actions = new JobActions({
    async get<T>(path: string) {
      expect(path).toBe('/jobs/slots');
      return { slots } as unknown as T;
    },
    async post<T>(path: string, body: unknown) {
      if (failWith) throw failWith;
      posts.push({ path, body });
      return {} as T;
    },
  });
  return { actions, posts };
}

describe('slot capacity', () => {
  it('sets the capacity of a slot the daemon holds and says that waiting jobs start on a raise', async () => {
    const { actions, posts } = setup();
    const r = await actions.setCapacity({ name: 'gradle-test', capacity: 3 });
    expect(r).toEqual({ ok: true, message: 'Slot gradle-test: capacity 1 → 3; waiting jobs start now.' });
    expect(posts).toEqual([{ path: '/jobs/slots/gradle-test', body: { capacity: 3 } }]);
  });

  it('lowers a capacity without promising a start', async () => {
    const { actions } = setup();
    expect((await actions.setCapacity({ name: 'vps-test', capacity: 1 })).message).toBe('Slot vps-test: capacity 2 → 1.');
  });

  it('writes the value again when the slot has it only by default (nothing in config.json)', async () => {
    const { actions, posts } = setup();
    expect((await actions.setCapacity({ name: 'gradle-test', capacity: 1 })).message).toBe('Slot gradle-test: capacity 1 is now written in config.json.');
    expect(posts).toHaveLength(1);
  });

  it('does not call the daemon when the slot already has that capacity from config.json', async () => {
    const { actions, posts } = setup();
    expect((await actions.setCapacity({ name: 'vps-test', capacity: 2 })).ok).toBe(true);
    expect(posts).toEqual([]);
  });

  it.each([
    ['zero', { name: 'gradle-test', capacity: 0 }],
    ['above the daemon limit', { name: 'gradle-test', capacity: 65 }],
    ['a fraction', { name: 'gradle-test', capacity: 1.5 }],
    ['a string', { name: 'gradle-test', capacity: '2' }],
    ['a slot the daemon does not hold', { name: 'nope', capacity: 2 }],
    ['a name that is no slot name', { name: '../config', capacity: 2 }],
    ['no request at all', null],
  ])('refuses %s without calling the daemon', async (_what, input) => {
    const { actions, posts } = setup();
    expect((await actions.setCapacity(input)).ok).toBe(false);
    expect(posts).toEqual([]);
  });

  it('lets the daemon refusal through for the guard to word', async () => {
    const { actions } = setup(new HttpError(400, 'bad_request', 'capacity must be 1 to 64'));
    await expect(actions.setCapacity({ name: 'gradle-test', capacity: 4 })).rejects.toThrow('capacity must be 1 to 64');
  });
});

describe('moving a waiting job', () => {
  it('moves it to the front, or before or after another waiting job of its slot', async () => {
    const { actions, posts } = setup();
    expect((await actions.move({ id: 'J-C', to: 'first' })).message).toBe('J-C moved to the front of slot gradle-test.');
    expect((await actions.move({ id: 'J-A', to: 'after', target: 'J-B' })).message).toBe('J-A moved after J-B in slot gradle-test.');
    expect((await actions.move({ id: 'J-C', to: 'before', target: 'J-B' })).ok).toBe(true);
    expect(posts).toEqual([
      { path: '/jobs/J-C/move', body: { first: true } },
      { path: '/jobs/J-A/move', body: { after: 'J-B' } },
      { path: '/jobs/J-C/move', body: { before: 'J-B' } },
    ]);
  });

  it.each([
    ['a running job', { id: 'J-RUN', to: 'first' }],
    ['a job no slot knows', { id: 'J-X', to: 'first' }],
    ['a target in another slot', { id: 'J-A', to: 'before', target: 'J-V' }],
    ['a target that is not waiting', { id: 'J-A', to: 'after', target: 'J-RUN' }],
    ['itself as the target', { id: 'J-A', to: 'after', target: 'J-A' }],
    ['before / after without a target', { id: 'J-A', to: 'before' }],
    ['first with a target', { id: 'J-A', to: 'first', target: 'J-B' }],
    ['an unknown position', { id: 'J-A', to: 'last' }],
    ['a job id that is a path', { id: '../cancel', to: 'first' }],
    ['no request at all', null],
  ])('refuses %s without calling the daemon', async (_what, input) => {
    const { actions, posts } = setup();
    expect((await actions.move(input)).ok).toBe(false);
    expect(posts).toEqual([]);
  });
});
