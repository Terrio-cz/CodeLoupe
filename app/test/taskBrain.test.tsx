import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { MockApi } from '../src/main/api/MockApi';
import { BrainTaskTable } from '../src/renderer/src/components/BrainTaskTable';
import { TaskBrainSection } from '../src/renderer/src/components/TaskBrainSection';
import { stateText, stateTone, taskMarkLabel } from '../src/renderer/src/brainModel';
import type { BrainTasksView, BrainView, TaskBrain, TaskDetail } from '../src/shared/contract';
import { validateRequest, timeoutMs, type ApiRequest } from '../src/shared/request';

const api = new MockApi(Date.parse('2026-10-07T12:00:00Z'));
const get = async <T,>(req: ApiRequest): Promise<T> => {
  const v = validateRequest(req);
  if (!v.ok) throw new Error(v.error);
  return (await api.get(v.request)) as T;
};

describe('task notes of the brain through the mock API', () => {
  it('lists the task files newest first with their state and says which the tracker holds', async () => {
    const id = (await get<BrainView>({ resource: 'brain' })).workspaces[0].id;
    const view = await get<BrainTasksView>({ resource: 'brain/:id/tasks', id });
    expect(view.tasks.map(t => t.id)).toEqual(['SHOP-672', 'SHOP-671', 'CL-43', 'SHOP-9']);
    const t = view.tasks.find(x => x.id === 'SHOP-671')!;
    expect(stateText(t.state)).toBe('plan=ok code=ok rev=fail@3f9c1e2 test=ok@3f9c1e2 round=2');
    expect(t.entries).toBe(11);
    expect(view.tasks.find(x => x.id === 'SHOP-9')?.tracked).toBe(false);
    expect(t.tracked).toBe(true);
    await expect(get({ resource: 'brain/:id/tasks', id: 'nope' })).rejects.toThrow();
    const none = await get<BrainTasksView>({ resource: 'brain/:id/tasks', id: (await get<BrainView>({ resource: 'brain' })).workspaces[1].id });
    expect(none.tasks).toEqual([]);
  });

  it('puts the notes into the task detail: entries newest first, a cold index, an unreadable file, no file', async () => {
    const full = await get<TaskDetail>({ resource: 'tasks/:id', id: 'SHOP-671' });
    const notes = full.brain.brains[0];
    expect(notes.entries.slice(0, 2).map(e => e.marker)).toEqual(['%', '=']);
    expect(notes.entries[0].who).toBe('tester');
    expect(notes.entries.at(-1)?.at).toBe('2026-10-05T09:00:00.000Z');
    expect(notes.entries.filter(e => e.rejected).map(e => e.marker)).toEqual(['x']);
    expect(notes.entries.filter(e => e.superseded).map(e => e.marker)).toEqual(['!P2']);
    expect(notes.indexing).toBe(false);
    expect((await get<TaskDetail>({ resource: 'tasks/:id', id: 'SHOP-672' })).brain.brains[0].indexing).toBe(true);
    expect((await get<TaskDetail>({ resource: 'tasks/:id', id: 'CL-43' })).brain.brains[0].error).toMatch(/could not be read/);
    expect((await get<TaskDetail>({ resource: 'tasks/:id', id: 'SHOP-660' })).brain.brains).toEqual([]);
  });

  it('allows the tasks view without a query and reads it quickly', () => {
    expect(validateRequest({ resource: 'brain/:id/tasks', id: 'abc' }).ok).toBe(true);
    expect(validateRequest({ resource: 'brain/:id/tasks', id: 'abc', query: { x: 1 } }).ok).toBe(false);
    expect(validateRequest({ resource: 'brain/:id/tasks' }).ok).toBe(false);
    expect(timeoutMs({ resource: 'brain/:id/tasks', id: 'abc' })).toBeLessThanOrEqual(10_000);
  });
});

describe('task note words', () => {
  it('names the marker and reads the state keys', () => {
    expect(taskMarkLabel('->')).toBe('handoff');
    expect(taskMarkLabel('!P2')).toBe('blocker P2');
    expect(taskMarkLabel('')).toBe('note');
    expect([stateTone('rev', 'ok@3f9'), stateTone('test', 'fail'), stateTone('code', 'wip'), stateTone('plan', '-'), stateTone('round', '3'), stateTone('hold', 'review'), stateTone('hold', '-')])
      .toEqual(['ok', 'critical', 'running', 'neutral', 'neutral', 'warning', 'neutral']);
  });
});

describe('Brain section of the task detail', () => {
  const brain = async (id: string) => (await get<TaskDetail>({ resource: 'tasks/:id', id })).brain;

  it('shows the state keys and the entries newest first, with rejected and superseded ones marked', async () => {
    const html = renderToStaticMarkup(<TaskBrainSection brain={await brain('SHOP-671')} />);
    expect(html).toContain('plan=ok');
    expect(html).toContain('rev=fail@3f9c1e2');
    expect(html.indexOf('PASS 212 tests')).toBeLessThan(html.indexOf('recompute totals'));
    expect(html).toContain('rejected');
    expect(html).toContain('superseded by CL-check-7');
    expect(html).toContain('blocker P2');
    expect(html).not.toContain('still building');
  });

  it('says when the index is cold, when the file is unreadable and when no brain has the task', async () => {
    expect(renderToStaticMarkup(<TaskBrainSection brain={await brain('SHOP-672')} />)).toContain('still building');
    const failed = renderToStaticMarkup(<TaskBrainSection brain={await brain('CL-43')} />);
    expect(failed).toContain('could not be read');
    expect(failed).toContain('role="alert"');
    expect(renderToStaticMarkup(<TaskBrainSection brain={{ brains: [] } satisfies TaskBrain} />)).toContain('No brain holds notes for this task');
  });
});

describe('Tasks view of the Brain screen', () => {
  it('lists id, state, entry count and last entry, and opens only tasks the tracker holds', async () => {
    const id = (await get<BrainView>({ resource: 'brain' })).workspaces[0].id;
    const view = await get<BrainTasksView>({ resource: 'brain/:id/tasks', id });
    const html = renderToStaticMarkup(<BrainTaskTable tasks={view.tasks} onOpen={() => undefined} />);
    expect(html).toContain('SHOP-671');
    expect(html).toContain('code=ok');
    expect(html).toContain('>11<');
    expect(html).toContain('not in the tracker mirror');
    expect(renderToStaticMarkup(<BrainTaskTable tasks={[]} onOpen={() => undefined} />)).toContain('no task files');
  });
});
