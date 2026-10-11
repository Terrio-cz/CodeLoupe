import type { BrainEntry, BrainHit, BrainSearchView, BrainTopicView, BrainView, BrainWorkspace } from '../../shared/contract';
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

function workspace(id: string, name: string, root: string, names: string[]): BrainWorkspace {
  const topics = names.map(topic => ({ topic, entries: TOPICS[topic].entries.length, bytes: TOPICS[topic].entries.reduce((a, e) => a + e.text.length + 24, 0), about: TOPICS[topic].about }));
  return { id, name, root, topics, entries: topics.reduce((a, t) => a + t.entries, 0), bytes: topics.reduce((a, t) => a + t.bytes, 0) };
}

/** Deterministic brain answers that follow the daemon's contract (a topic by name, a case-insensitive term search). */
export class MockBrain {
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
}
