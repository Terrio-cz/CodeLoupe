import { describe, expect, it } from 'vitest';
import type { BrainSearchView, BrainTopicView, BrainView } from '../src/shared/contract';
import { validateRequest, timeoutMs, type ApiRequest } from '../src/shared/request';
import { MockApi } from '../src/main/api/MockApi';
import { errorTitle, pickTopic, searchTerms, treeRows } from '../src/renderer/src/brainModel';

const api = new MockApi(Date.parse('2026-10-07T12:00:00Z'));
const get = async <T>(req: ApiRequest): Promise<T> => {
  const v = validateRequest(req);
  if (!v.ok) throw new Error(v.error);
  return (await api.get(v.request)) as T;
};
const topic = (name: string, entries: number) => ({ topic: name, entries, bytes: entries * 10, about: '' });

describe('topic tree', () => {
  it('nests by slash, puts a parent before its children and sums the subtree', () => {
    const rows = treeRows([topic('review-patterns/tests', 3), topic('db', 5), topic('review-patterns/sql', 2), topic('review-patterns-x', 1), topic('review-patterns', 1)]);
    expect(rows.map(r => `${r.depth}:${r.path}:${r.total}`)).toEqual(['0:db:5', '0:review-patterns:6', '1:review-patterns/sql:2', '1:review-patterns/tests:3', '0:review-patterns-x:1']);
  });

  it('shows a group that exists only as a parent, without a topic', () => {
    const rows = treeRows([topic('a/b', 2), topic('a/c', 1)]);
    expect(rows[0]).toMatchObject({ path: 'a', topic: null, total: 3 });
    expect(rows.slice(1).every(r => r.topic !== null)).toBe(true);
  });

  it('picks the asked topic, else the first one with entries', () => {
    const topics = [topic('a', 0), topic('b/c', 4)];
    expect(pickTopic(topics, 'a')).toBe('a');
    expect(pickTopic(topics, 'gone')).toBe('b/c');
    expect(pickTopic([], null)).toBeNull();
  });

  it('splits search terms on blanks and drops leading dashes', () => {
    expect(searchTerms('  anti  --join ')).toEqual(['anti', 'join']);
    expect(searchTerms('   ')).toEqual([]);
    expect(errorTitle('node_missing')).toMatch(/Node/);
  });
});

describe('Brain through the mock API', () => {
  it('lists workspaces with totals, one of them with an error and no topics', async () => {
    const view = await get<BrainView>({ resource: 'brain' });
    const [ok, broken] = view.workspaces;
    expect(ok.entries).toBe(ok.topics.reduce((a, t) => a + t.entries, 0));
    expect(broken).toMatchObject({ errorCode: 'node_missing', topics: [] });
  });

  it('reads a topic and searches, with the task findings only on request', async () => {
    const id = (await get<BrainView>({ resource: 'brain' })).workspaces[0].id;
    const t = await get<BrainTopicView>({ resource: 'brain/:id/topic', id, query: { name: 'db' } });
    expect(t.entries).toHaveLength(3);
    const plain = await get<BrainSearchView>({ resource: 'brain/:id/search', id, query: { q: 'statistics' } });
    expect(plain.hits.every(h => h.topic)).toBe(true);
    const withTasks = await get<BrainSearchView>({ resource: 'brain/:id/search', id, query: { q: 'statistics', tasks: 1 } });
    expect(withTasks.hits.some(h => h.task === 'SHOP-5')).toBe(true);
    const scoped = await get<BrainSearchView>({ resource: 'brain/:id/search', id, query: { q: 'statistics', topic: 'docker' } });
    expect(scoped.hits).toHaveLength(0);
    await expect(get({ resource: 'brain/:id/topic', id, query: { name: 'nope' } })).rejects.toThrow();
  });

  it('allows only the brain query keys and waits long enough for the CLI', () => {
    expect(validateRequest({ resource: 'brain/:id/search', id: 'abc', query: { q: 'x', topic: 'db', tasks: 1, limit: 5 } }).ok).toBe(true);
    expect(validateRequest({ resource: 'brain/:id/topic', id: 'abc', query: { path: '../x' } }).ok).toBe(false);
    expect(validateRequest({ resource: 'brain', query: { name: 'x' } }).ok).toBe(false);
    expect(validateRequest({ resource: 'brain/:id/topic', id: '../x', query: { name: 'db' } }).ok).toBe(false);
    expect(timeoutMs({ resource: 'brain' })).toBeGreaterThan(20_000);
  });
});
