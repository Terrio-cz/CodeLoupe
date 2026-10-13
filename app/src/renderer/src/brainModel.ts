import type { BrainStateKey, BrainTopic } from '../../shared/contract';
import type { Tone } from './components/StatusBadge';

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

const TASK_MARK_LABEL: Record<string, string> = {
  '=': 'evidence', '~': 'decision', '!': 'blocker', '?': 'unknown', '+': 'fact', '>': 'action', '->': 'handoff', '%': 'state', '@': 'context', x: 'rejected', '-': 'note',
};

/** The word for a task note's kind marker (`!P2` is a blocker of severity P2); a line without one is a note. */
export function taskMarkLabel(marker: string): string {
  if (/^!P\d$/.test(marker)) return `blocker ${marker.slice(1)}`;
  return TASK_MARK_LABEL[marker] ?? 'note';
}

/** How a state key's value reads: `ok` and `ok@sha` are done, `fail` is broken, `wip` runs, `-` and `skip` are neutral, a hold is a warning. */
export function stateTone(key: string, value: string): Tone {
  if (key === 'hold') return value === '-' ? 'neutral' : 'warning';
  if (key === 'round' || key === 'merge') return 'neutral';
  const base = value.split('@')[0];
  return base === 'ok' ? 'ok' : base === 'fail' || base === 'blocked' ? 'critical' : base === 'wip' ? 'running' : 'neutral';
}

/** The state in one line, `plan=ok code=wip`, for a table cell and for assistive technology. */
export function stateText(state: BrainStateKey[]): string {
  return state.map(s => `${s.key}=${s.value}`).join(' ');
}
