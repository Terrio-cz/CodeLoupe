import type { BrainTopic } from '../../shared/contract';

/** One line of the topic tree. `topic` is null for a group that exists only as the parent of subtopics. */
export interface TreeRow {
  path: string;
  label: string;
  depth: number;
  topic: BrainTopic | null;
  /** Entries of this topic and of every topic below it. */
  total: number;
}

/** The topics, which nest by `/`, as tree rows in reading order (a parent before its children, siblings alphabetical). */
export function treeRows(topics: BrainTopic[]): TreeRow[] {
  const paths = new Set<string>();
  for (const { topic } of topics) {
    const parts = topic.split('/');
    for (let i = 1; i <= parts.length; i++) paths.add(parts.slice(0, i).join('/'));
  }
  const byName = new Map(topics.map(t => [t.topic, t]));
  return [...paths]
    .map(path => path.split('/'))
    .sort(compareSegments)
    .map(parts => {
      const path = parts.join('/');
      const total = topics.filter(t => t.topic === path || t.topic.startsWith(`${path}/`)).reduce((a, t) => a + t.entries, 0);
      return { path, label: parts[parts.length - 1], depth: parts.length - 1, topic: byName.get(path) ?? null, total };
    });
}

function compareSegments(a: string[], b: string[]): number {
  for (let i = 0; i < Math.min(a.length, b.length); i++) {
    if (a[i] !== b[i]) return a[i] < b[i] ? -1 : 1;
  }
  return a.length - b.length;
}

/** The topic to show: the one asked for if it exists, else the first with entries, else the first row that is a topic. */
export function pickTopic(topics: BrainTopic[], wanted: string | null): string | null {
  if (wanted && topics.some(t => t.topic === wanted)) return wanted;
  const rows = treeRows(topics);
  return (rows.find(r => r.topic && r.topic.entries > 0) ?? rows.find(r => r.topic))?.path ?? null;
}

/** Search terms as the user typed them, split on blanks; empty when there is nothing to search for. */
export function searchTerms(text: string): string[] {
  return text.split(/\s+/).map(t => t.replace(/^-+/, '')).filter(Boolean);
}

/** Why the brain of a workspace could not be read, in the app's words. */
export function errorTitle(code: string | null | undefined): string {
  if (code === 'node_missing') return 'Node.js was not found';
  if (code === 'timeout') return 'The brain did not answer in time';
  return 'The brain could not be read';
}
