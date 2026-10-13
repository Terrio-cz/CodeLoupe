import type { BrainEntry, BrainHit, BrainSearchView, BrainTask, BrainTasksView, BrainTopicView, BrainView, BrainWorkspace, TaskBrain, TaskBrainEntry, TaskBrainNotes } from '../../shared/contract';
import type { Query } from '../../shared/request';
import { HttpError } from '../daemon/DaemonClient';

const TOPICS: Record<string, { about: string; entries: BrainEntry[] }> = {
  db: {
    about: 'database facts: schemas, tables, PostGIS, queries',
    entries: [
      { mark: '+', text: 'shared DB is PostgreSQL 17 with PostGIS; task databases only through the task stack', stamp: '2026-09-23' },
      { mark: '!', text: 'an in-query SET can lift read-only; the db tool refuses SET, DML and DDL lexically', stamp: '2026-09-25' },
      { mark: '=', text: 'ANALYZE inside the importing transaction keeps planner statistics fresh', stamp: null },
    ],
  },
  docker: {
    about: 'task and shared Docker stacks: ports, build, seed, teardown',
    entries: [
      { mark: '+', text: 'the shared stack belongs to the user; build, restart and stop need a yes', stamp: '2026-09-23' },
      { mark: '!', text: 'never run compose down -v on the shared stack', stamp: '2026-09-24' },
    ],
  },
  'review-patterns': { about: 'mistakes reviews found repeatedly, by kind', entries: [{ mark: '+', text: 'split into subtopics; the planner copies the matching ones into the plan', stamp: '2026-10-11' }] },
  'review-patterns/sql-scale': {
    about: 'SQL at scale: EXPLAIN, correlated subqueries, planner statistics',
    entries: [
      { mark: '+', text: 'fence a correlated spatial subquery with OFFSET 0 so it is evaluated once', stamp: '2026-09-27' },
      { mark: '+', text: 'prefer NOT EXISTS over GROUP BY / HAVING for uniqueness checks on large sets', stamp: '2026-09-28' },
    ],
  },
  'review-patterns/tests': { about: 'test design: fixtures, boundaries, ordering, evidence', entries: [{ mark: '!', text: 'a fixture id range must not collide with ids other suites insert', stamp: '2026-10-02' }] },
};

const WORKSPACES: BrainWorkspace[] = [
  workspace('c0ffee000001', 'terrio', 'C:/work/terrio', Object.keys(TOPICS)),
  { id: 'c0ffee000002', name: 'shop', root: 'C:/work/shop', topics: [], entries: 0, bytes: 0, errorCode: 'node_missing', error: 'node could not be started (Cannot run program "node"); install Node.js or add it to the daemon\'s PATH' },
];

/** Task files of the first mock brain: oldest section first, as the file reads; the answers list them newest first. */
const TASK_FILES: Record<string, { at: string; who: string; lines: string[] }[]> = {
  'SHOP-671': [
    { at: '2026-10-05T09:00:00.000Z', who: 'planner', lines: ['~ recompute totals inside the checkout transaction; no new table', '-> coder: plan steps 1-6', '% plan=ok'] },
    { at: '2026-10-05T11:30:00.000Z', who: 'coder', lines: ['> changed OrderTotals.kt, CheckoutService.kt', '% code=ok round=1'] },
    { at: '2026-10-05T13:10:00.000Z', who: 'reviewer', lines: ['!P2 r1 a coupon that expires between price and payment is charged in full: CheckoutService.kt:88', '? does the coupon table keep expired rows', '% rev=fail@3f9c1e2'] },
    { at: '2026-10-05T13:40:00.000Z', who: 'coder', lines: ['x P3 rename OrderTotals -- declined: used once'] },
    { at: '2026-10-05T14:20:00.000Z', who: 'tester', lines: ['= PASS 212 tests @3f9c1e2', '% test=ok@3f9c1e2 round=2'] },
  ],
  'SHOP-672': [{ at: '2026-10-06T08:00:00.000Z', who: 'planner', lines: ['+ the product list reads stock one row at a time', '% plan=wip'] }],
  'SHOP-9': [{ at: '2026-09-30T16:00:00.000Z', who: 'orchestrator', lines: ['= LANDED on origin/master', '% land=ok merge=9a17b7e close=ok'] }],
  'CL-43': [{ at: '2026-10-04T10:00:00.000Z', who: 'orchestrator', lines: ['@ claimed as orchestrator', '% plan=ok code=wip hold=-'] }],
};

const STATE_ORDER = ['plan', 'code', 'rev', 'test', 'round', 'cl', 'land', 'merge', 'close', 'hold'];

function stateOf(sections: { lines: string[] }[]): TaskBrainNotes['state'] {
  const state = new Map<string, string>();
  for (const { lines } of sections) {
    for (const line of lines.filter(l => l.startsWith('% '))) {
      for (const pair of line.slice(2).split(' ')) state.set(pair.slice(0, pair.indexOf('=')), pair.slice(pair.indexOf('=') + 1));
    }
  }
  const rank = (key: string) => { const i = STATE_ORDER.indexOf(key); return i < 0 ? STATE_ORDER.length : i; };
  return [...state].sort((a, b) => rank(a[0]) - rank(b[0])).map(([key, value]) => ({ key, value }));
}

function entriesOf(id: string): TaskBrainEntry[] {
  return (TASK_FILES[id] ?? []).flatMap(sec => sec.lines.map((line, i) => {
    const marker = /^(->|!P\d|[@%+!?>=~x-])\s/.exec(line)?.[1] ?? '';
    return {
      marker, text: line.slice(marker.length).trim(), who: sec.who, at: sec.at, line: i, rejected: marker === 'x',
      superseded: id === 'SHOP-671' && marker === '!P2', coveredBy: id === 'SHOP-671' && marker === '!P2' ? 'CL-check-7' : null,
    };
  })).map((e, i) => ({ ...e, line: i + 5 })).reverse();
}

function workspace(id: string, name: string, root: string, names: string[]): BrainWorkspace {
  const topics = names.map(topic => ({ topic, entries: TOPICS[topic].entries.length, bytes: TOPICS[topic].entries.reduce((a, e) => a + e.text.length + 24, 0), about: TOPICS[topic].about }));
  return { id, name, root, topics, entries: topics.reduce((a, t) => a + t.entries, 0), bytes: topics.reduce((a, t) => a + t.bytes, 0) };
}

/** Deterministic brain answers that follow the daemon's contract (a topic by name, a case-insensitive term search, task files). */
export class MockBrain {
  /** [tracked] are the ids the mock tracker mirror holds. */
  constructor(private readonly tracked: ReadonlySet<string> = new Set()) {}

  view(): BrainView {
    return { workspaces: WORKSPACES };
  }

  topic(id: string, name: unknown): BrainTopicView {
    const ws = WORKSPACES.find(w => w.id === id);
    const topic = typeof name === 'string' ? TOPICS[name] : undefined;
    if (!ws || !topic) throw new HttpError(404, 'not_found', `no topic ${String(name)}`);
    return { workspaceId: id, topic: String(name), bytes: ws.topics.find(t => t.topic === name)?.bytes ?? 0, entries: topic.entries };
  }

  search(id: string, q: Query): BrainSearchView {
    if (!WORKSPACES.some(w => w.id === id)) throw new HttpError(404, 'not_found', 'no such workspace');
    const terms = String(q.q ?? '').split(/\s+/).filter(Boolean).map(t => t.toLowerCase());
    if (terms.length === 0) throw new HttpError(400, 'bad_request', 'q needs at least one search term');
    const only = typeof q.topic === 'string' ? q.topic : null;
    const hits: BrainHit[] = [];
    for (const [topic, { entries }] of Object.entries(TOPICS)) {
      if (only && topic !== only) continue;
      entries.forEach((e, i) => {
        const n = terms.filter(t => e.text.toLowerCase().includes(t)).length;
        if (n > 0) hits.push({ where: `facts/${topic}.md:${i + 4}`, topic, line: i + 4, text: e.text, hits: n });
      });
    }
    if (q.tasks === true || q.tasks === 1 || q.tasks === '1') {
      if (terms.includes('statistics')) hits.push({ where: 'tasks/SHOP-5.md:40', task: 'SHOP-5', line: 40, text: 'review finding: statistics were stale after the import', hits: 1 });
    }
    hits.sort((a, b) => b.hits - a.hits);
    return { workspaceId: id, terms, hits, more: 0 };
  }

  tasks(id: string): BrainTasksView {
    if (!WORKSPACES.some(w => w.id === id)) throw new HttpError(404, 'not_found', 'no such workspace');
    if (id !== WORKSPACES[0].id) return { workspaceId: id, tasks: [] };
    const tasks: BrainTask[] = Object.keys(TASK_FILES).map(task => {
      const entries = entriesOf(task);
      return { id: task, lastAt: entries[0].at, state: stateOf(TASK_FILES[task]), entries: entries.length, tracked: this.tracked.has(task) };
    });
    tasks.sort((a, b) => (b.lastAt ?? '').localeCompare(a.lastAt ?? ''));
    return { workspaceId: id, tasks };
  }

  /** The notes of one task in the way the task detail shows them: SHOP-672 with the index still cold, CL-43 with an unreadable file. */
  taskBrain(task: string): TaskBrain {
    if (task === 'CL-43') {
      return { brains: [{ workspaceId: WORKSPACES[0].id, workspace: WORKSPACES[0].name, state: [], entries: [], entryCount: 0, lastAt: null, indexing: false, error: 'CL-43.md could not be read: Access is denied' }] };
    }
    if (!TASK_FILES[task]) return { brains: [] };
    const entries = entriesOf(task);
    return { brains: [{ workspaceId: WORKSPACES[0].id, workspace: WORKSPACES[0].name, state: stateOf(TASK_FILES[task]), entries, entryCount: entries.length, lastAt: entries[0].at, indexing: task === 'SHOP-672', error: null }] };
  }
}
