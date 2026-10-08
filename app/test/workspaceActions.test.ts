import { describe, expect, it } from 'vitest';
import { WorkspaceActions, type Confirm, type WorkspaceDaemon } from '../src/main/actions/WorkspaceActions';
import { MockWorkspaces } from '../src/main/api/mockWorkspaces';
import type { ReconcilePlan, WorkspaceList } from '../src/shared/workspaces';

const mock = new MockWorkspaces(Date.parse('2026-10-08T12:00:00Z'));
const registry: WorkspaceList = mock.workspaces(false);
const plan: ReconcilePlan = mock.reconcile();
const repoPath = registry.repos[0].repo;
const ter664 = registry.repos[0].workspaces.find(w => w.name === 'TER-664')!;
const confirmKeys = plan.entries.filter(e => e.verdict === 'confirm').map(e => e.key);

function setup(answer = true, runActions: { key: string; outcome: string }[] | null = null) {
  const posts: { path: string; body: unknown }[] = [];
  const asked: { message: string; detail: string }[] = [];
  const daemon: WorkspaceDaemon = {
    async get<T>(path: string) { return (path === '/workspaces' ? registry : plan) as unknown as T; },
    async post<T>(path: string, body: unknown) {
      posts.push({ path, body });
      const keys = ((body as { confirm?: string[] }).confirm ?? []);
      return { actions: (runActions ?? keys.map(key => ({ key, outcome: 'removed' }))).map(a => ({ kind: 'volume', name: a.key, workspace: null, detail: '', ...a })) } as unknown as T;
    },
  };
  const confirm: Confirm = async q => { asked.push(q); return answer; };
  return { actions: new WorkspaceActions(daemon, confirm), posts, asked };
}

describe('release', () => {
  it('lists what goes in the dialog and releases the worktree the daemon knows', async () => {
    const { actions, posts, asked } = setup();
    const r = await actions.release({ repo: repoPath, path: ter664.path });
    expect(r.ok).toBe(true);
    expect(asked[0].message).toContain('TER-664');
    expect(asked[0].detail).toContain('ter-664_pgdata');
    expect(posts).toEqual([{ path: '/workspaces/release', body: { target: ter664.path, repo: repoPath } }]);
  });

  it('does nothing when the user declines', async () => {
    const { actions, posts } = setup(false);
    expect(await actions.release({ repo: repoPath, path: ter664.path })).toEqual({ ok: false, message: 'Cancelled.' });
    expect(posts).toEqual([]);
  });

  it.each([
    ['the main worktree', () => ({ repo: repoPath, path: repoPath })],
    ['an orphan directory', () => ({ repo: repoPath, path: registry.repos[0].workspaces.find(w => w.role === 'directory')!.path })],
    ['a path the daemon does not know', () => ({ repo: repoPath, path: 'C:/elsewhere/TER-1' })],
    ['a request without a path', () => ({ repo: repoPath })],
    ['something that is no request', () => null],
  ])('refuses %s without asking or calling the daemon', async (_name, input) => {
    const { actions, posts, asked } = setup();
    const r = await actions.release(input());
    expect(r.ok).toBe(false);
    expect(asked).toEqual([]);
    expect(posts).toEqual([]);
  });
});

describe('reconcile', () => {
  it('asks with the daemon\'s own entries and sends exactly the confirmed keys', async () => {
    const { actions, posts, asked } = setup();
    const keys = confirmKeys.slice(0, 2);
    const r = await actions.reconcile({ keys });
    expect(r.ok).toBe(true);
    expect(r.message).toBe('Removed 2.');
    expect(asked[0].message).toBe('Remove 2 resources?');
    expect(posts).toEqual([{ path: '/reconcile/run', body: { confirm: keys } }]);
  });

  it('says what stays when a resource is in use', async () => {
    const { actions } = setup(true, [{ key: confirmKeys[0], outcome: 'removed' }, { key: confirmKeys[1], outcome: 'blocked' }]);
    const r = await actions.reconcile({ keys: confirmKeys.slice(0, 2) });
    expect(r.ok).toBe(false);
    expect(r.message).toBe('Removed 1, 1 left (in use or failed; the daemon will retry).');
    expect(r.results.map(x => x.outcome)).toEqual(['removed', 'blocked']);
  });

  it('refuses entries that are not waiting for a confirmation, and keys the plan does not have', async () => {
    const keep = plan.entries.find(e => e.verdict === 'keep')!;
    const auto = plan.entries.find(e => e.verdict === 'auto')!;
    for (const keys of [[keep.key], [auto.key], ['volume:not-in-the-plan'], [confirmKeys[0], keep.key]]) {
      const { actions, posts, asked } = setup();
      const r = await actions.reconcile({ keys });
      expect(r.ok).toBe(false);
      expect(asked).toEqual([]);
      expect(posts).toEqual([]);
    }
  });

  it('refuses malformed requests before it reads anything', async () => {
    for (const input of [null, {}, { keys: [] }, { keys: [1] }, { keys: ['rm -rf'] }, { keys: ['volume:' + 'x'.repeat(600)] }, { keys: Array(201).fill('volume:a') }]) {
      const { actions, posts } = setup();
      expect((await actions.reconcile(input)).ok).toBe(false);
      expect(posts).toEqual([]);
    }
  });

  it('does not call the daemon when the user declines', async () => {
    const { actions, posts } = setup(false);
    expect(await actions.reconcile({ keys: [confirmKeys[0]] })).toEqual({ ok: false, message: 'Cancelled.', results: [] });
    expect(posts).toEqual([]);
  });
});
