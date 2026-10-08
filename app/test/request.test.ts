import { describe, expect, it } from 'vitest';
import { timeoutMs, validateRequest } from '../src/shared/request';

describe('validateRequest', () => {
  it('builds the daemon path from resource, id and allowed query keys', () => {
    const r = validateRequest({ resource: 'worktrees/:id', id: 'a1f3c09e4b21' });
    expect(r).toMatchObject({ ok: true, path: '/ui-api/v1/worktrees/a1f3c09e4b21' });
    const o = validateRequest({ resource: 'overview', query: { range: '30d' } });
    expect(o).toMatchObject({ ok: true, path: '/ui-api/v1/overview?range=30d' });
  });

  it('reaches the daemon routes outside the UI API that a screen needs, and no others', () => {
    expect(validateRequest({ resource: 'status/history' })).toMatchObject({ ok: true, path: '/status/history' });
    expect(validateRequest({ resource: 'shutdown' }).ok).toBe(false);
    expect(validateRequest({ resource: 'status' })).toMatchObject({ ok: true, path: '/status' });
    expect(validateRequest({ resource: 'jobs/:id', id: 'J20261008-K2QF' })).toMatchObject({ ok: true, path: '/jobs/J20261008-K2QF' });
    expect(validateRequest({ resource: 'jobs/:id', id: '../shutdown' }).ok).toBe(false);
    expect(validateRequest({ resource: 'events/stream' }).ok).toBe(false);
  });

  it('drops empty and undefined query values', () => {
    const r = validateRequest({ resource: 'tasks', query: { q: '', state: undefined, limit: 200 } });
    expect(r).toMatchObject({ ok: true, path: '/ui-api/v1/tasks?limit=200' });
  });

  it.each([
    [{ resource: 'agents' }, 'unknown resource'],
    [{ resource: '../status' }, 'unknown resource'],
    [{ resource: 'overview', id: 'x' }, 'id not allowed'],
    [{ resource: 'tasks/:id' }, 'id required'],
    [{ resource: 'tasks/:id', id: '../../shutdown' }, 'bad id'],
    [{ resource: 'tasks/:id', id: 'TER-1?x=1' }, 'bad id'],
    [{ resource: 'overview', query: { range: '7d', evil: '1' } }, 'not allowed'],
    [{ resource: 'overview', query: { range: { a: 1 } } }, 'bad value'],
    [{ resource: 'overview', query: { range: 'x'.repeat(201) } }, 'too long'],
    [null, 'object'],
  ])('refuses %j', (input, error) => {
    const r = validateRequest(input);
    expect(r.ok).toBe(false);
    expect(!r.ok && r.error).toContain(error);
  });

  it('encodes values so they cannot break out of the query', () => {
    const r = validateRequest({ resource: 'worktrees', query: { q: 'a&layer=x#/../' } });
    expect(r).toMatchObject({ ok: true, path: '/ui-api/v1/worktrees?q=a%26layer%3Dx%23%2F..%2F' });
  });
});

it('gives the readings that walk files or ask Docker time to finish', () => {
  expect(timeoutMs({ resource: 'workspaces', query: { size: 1 } })).toBe(120_000);
  expect(timeoutMs({ resource: 'resources', query: { stats: 1 } })).toBe(120_000);
  expect(timeoutMs({ resource: 'workspaces' })).toBe(5_000);
  expect(timeoutMs({ resource: 'overview', query: { range: '7d' } })).toBe(5_000);
});

it('refuses dot-only ids that the daemon would normalise into another path', () => {
  expect(validateRequest({ resource: 'worktrees/:id', id: '..' }).ok).toBe(false);
  expect(validateRequest({ resource: 'worktrees/:id', id: '.' }).ok).toBe(false);
  expect(validateRequest({ resource: 'worktrees/:id', id: 'a..b' }).ok).toBe(true);
});
