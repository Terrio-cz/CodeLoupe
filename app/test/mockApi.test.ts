import { describe, expect, it } from 'vitest';
import type { Gaps, Overview, Page, TaskSummary, WorktreeDetail, WorktreeSummary } from '../src/shared/contract';
import { validateRequest, type ApiRequest } from '../src/shared/request';
import { MockApi } from '../src/main/api/MockApi';

const api = new MockApi(Date.parse('2026-10-07T12:00:00Z'));
const get = async <T>(req: ApiRequest): Promise<T> => {
  const v = validateRequest(req);
  if (!v.ok) throw new Error(v.error);
  return (await api.get(v.request)) as T;
};

describe('MockApi follows the read-only contract', () => {
  it('answers every resource the renderer may request', async () => {
    const wt = await get<{ items: WorktreeSummary[] }>({ resource: 'worktrees' });
    const task = await get<Page<TaskSummary>>({ resource: 'tasks' });
    const all: ApiRequest[] = [
      { resource: 'nav' }, { resource: 'overview' }, { resource: 'worktrees' },
      { resource: 'worktrees/:id', id: wt.items[0].id }, { resource: 'tasks' }, { resource: 'tasks/:id', id: task.items[0].id },
      { resource: 'index' }, { resource: 'gaps' }, { resource: 'environment' }, { resource: 'settings' }, { resource: 'events' }, { resource: 'status/history' },
    ];
    for (const r of all) expect(await get(r)).toBeTruthy();
  });

  it('overview series and KPIs are consistent', async () => {
    const o = await get<Overview>({ resource: 'overview', query: { range: '7d' } });
    expect(o.costSeries.length).toBeGreaterThanOrEqual(7);
    // The first bucket may start before the range, so the series can only be larger.
    expect(o.kpis.weightedRange).toBeGreaterThan(0);
    expect(o.kpis.weightedRange).toBeLessThanOrEqual(o.costSeries.reduce((a, x) => a + x.weighted, 0));
    expect(o.toolCalls.every(t => t.p95Ms >= t.p50Ms)).toBe(true);
    expect(o).not.toHaveProperty('recentRuns');
  });

  it('filters worktrees and returns 404 for unknown ids', async () => {
    const fresh = await get<{ items: WorktreeSummary[] }>({ resource: 'worktrees', query: { layer: 'fresh' } });
    expect(fresh.items.every(w => w.layer === 'fresh')).toBe(true);
    const d = await get<WorktreeDetail>({ resource: 'worktrees/:id', id: fresh.items[0].id });
    expect(d.changes.length).toBe(d.changedDecls);
    await expect(get({ resource: 'worktrees/:id', id: 'nope' })).rejects.toMatchObject({ status: 404 });
  });

  it('pages tasks', async () => {
    const p1 = await get<Page<TaskSummary>>({ resource: 'tasks', query: { limit: 5 } });
    expect(p1.items).toHaveLength(5);
    const p2 = await get<Page<TaskSummary>>({ resource: 'tasks', query: { limit: 5, cursor: p1.nextCursor! } });
    expect(p2.items[0].id).not.toBe(p1.items[0].id);
  });

  it('gaps carry session text, never a link to an agent run', async () => {
    const g = await get<Gaps>({ resource: 'gaps', query: { range: '30d' } });
    expect(g.items.length).toBeGreaterThan(0);
    for (const i of g.items) {
      expect(i).toHaveProperty('session');
      expect(i).not.toHaveProperty('runId');
    }
  });

  it('events start at the current cursor and do not replay history', async () => {
    const first = await get<{ epoch: string; lastSeq: number; items: unknown[] }>({ resource: 'events' });
    expect(first.items).toHaveLength(0);
    const next = await get<{ lastSeq: number; items: unknown[] }>({ resource: 'events', query: { since: first.lastSeq } });
    expect(next.items).toHaveLength(1);
    const again = await get<{ items: unknown[] }>({ resource: 'events', query: { since: next.lastSeq } });
    expect(again.items).toHaveLength(0);
  });
});
