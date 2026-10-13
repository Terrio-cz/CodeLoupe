import type { JobMoveRequest } from '../../shared/actions';
import type { Delivery, JobRecord, SlotEntry, SlotSnapshot } from '../../shared/jobs';
import type { Tone } from './components/StatusBadge';

export type JobFilter = '' | 'active' | 'finished' | 'failed';

export const isActive = (j: JobRecord) => j.status === 'queued' || j.status === 'running';
/** Ended and not well: a non-zero exit, a program that did not start, a job the daemon lost. */
export const isFailed = (j: JobRecord) => (j.status === 'done' && j.exit !== 0) || j.status === 'error' || j.status === 'lost';
export const isPassed = (j: JobRecord) => j.status === 'done' && j.exit === 0;

export function matches(j: JobRecord, f: { filter: JobFilter; q: string }): boolean {
  if (f.filter === 'active' && !isActive(j)) return false;
  if (f.filter === 'finished' && isActive(j)) return false;
  if (f.filter === 'failed' && !isFailed(j)) return false;
  const q = f.q.trim().toLowerCase();
  return !q || `${j.id} ${j.command} ${j.tag ?? ''} ${j.slot ?? ''} ${j.cwd}`.toLowerCase().includes(q);
}

export interface JobCounts {
  running: number;
  queued: number;
  passed: number;
  failed: number;
  stopped: number;
}

export function jobCounts(jobs: JobRecord[]): JobCounts {
  const c: JobCounts = { running: 0, queued: 0, passed: 0, failed: 0, stopped: 0 };
  for (const j of jobs) {
    if (j.status === 'running') c.running++;
    else if (j.status === 'queued') c.queued++;
    else if (isPassed(j)) c.passed++;
    else if (isFailed(j)) c.failed++;
    else c.stopped++;
  }
  return c;
}

export function jobStatus(j: JobRecord): { tone: Tone; label: string } {
  switch (j.status) {
    case 'queued': return { tone: 'neutral', label: j.slot ? `waiting for slot ${j.slot}` : 'starting' };
    case 'running': return { tone: 'running', label: 'running' };
    case 'done': return j.exit === 0 ? { tone: 'ok', label: 'done' } : { tone: 'critical', label: `failed (exit ${j.exit})` };
    case 'denied': return { tone: 'serious', label: 'denied' };
    case 'cancelled': return { tone: 'neutral', label: 'cancelled' };
    case 'lost': return { tone: 'warning', label: 'lost' };
    case 'error': return { tone: 'critical', label: 'error' };
  }
}

/** A URL as the page shows it: no credentials, and a query (where tokens go) only as a mark that there is one. */
export function maskUrl(url: string): string {
  try {
    const u = new URL(url);
    return `${u.protocol}//${u.host}${u.pathname === '/' ? '' : u.pathname}${u.search ? '?…' : ''}`;
  } catch {
    return url.replace(/\?.*$/, '?…');
  }
}

/** A declared follow-up step (`job:<command>`, `notify:<message>`, `webhook:<url>`) with the query of a URL hidden. */
export function stepLabel(spec: string): string {
  return spec.replace(/(webhook:\s*)(\S+)/i, (_m, head: string, url: string) => `${head}${maskUrl(url)}`);
}

/** Who holds a slot and who waits, by job id, with the capacity left. */
export function slotLoad(s: SlotSnapshot): { used: number; free: number } {
  return { used: s.running.length, free: Math.max(0, s.capacity - s.running.length) };
}

/** Seconds a job has held or waited for a slot: counted from `since` as the clock moves, else as the daemon counted them. */
export function entrySeconds(e: SlotEntry, now: number): number {
  const since = Date.parse(e.since);
  return Number.isNaN(since) ? e.seconds : Math.max(0, Math.round((now - since) / 1000));
}

/** `45 s`, `12 min`, `2 h 5 min`: a wait is read in the unit it is felt in. */
export function waitLabel(seconds: number): string {
  if (seconds < 60) return `${seconds} s`;
  const min = Math.floor(seconds / 60);
  if (min < 60) return `${min} min`;
  return min % 60 === 0 ? `${Math.floor(min / 60)} h` : `${Math.floor(min / 60)} h ${min % 60} min`;
}

/** Holders and queue of a slot as entries; an older daemon that sends only ids gives entries without a time. */
export function slotEntries(s: SlotSnapshot): { holders: SlotEntry[]; queue: SlotEntry[] } {
  const fill = (ids: string[], entries: SlotEntry[] | undefined) => entries ?? ids.map(id => ({ id, since: '', seconds: 0 }));
  return { holders: fill(s.running, s.holders), queue: fill(s.waiting, s.queue) };
}

/** True when setting `value` would change nothing: it is the capacity in force and what config.json says (an older daemon does not say). */
export const sameCapacity = (s: SlotSnapshot, value: number): boolean => value === s.capacity && (s.configured === undefined || s.configured === value);

/** Where the capacity in force comes from: the value in `config.json`, the default, or a value the daemon ignored. */
export function capacitySource(s: SlotSnapshot): { text: string; warn: boolean } {
  if (s.invalid) return { text: `config.json has ${s.invalid}, not a positive whole number: ignored, 1 in force`, warn: true };
  if (s.configured === undefined) return { text: '', warn: false };
  if (s.configured === null) return { text: 'not in config.json: default 1', warn: false };
  return { text: s.configured === s.capacity ? `configured ${s.configured}` : `configured ${s.configured}, ${s.capacity} in force`, warn: s.configured !== s.capacity };
}

/** The request that moves the waiting job at `index` one place up (-1) or down (+1); null at the end of the queue. */
export function moveStep(queue: SlotEntry[], index: number, step: -1 | 1): JobMoveRequest | null {
  const id = queue[index]?.id;
  const next = queue[index + step];
  if (!id || !next) return null;
  if (step === -1) return index === 1 ? { id, to: 'first' } : { id, to: 'before', target: next.id };
  return { id, to: 'after', target: next.id };
}

export const deliveryTone = (d: Delivery): Tone => (d.state === 'delivered' ? 'ok' : d.state === 'failed' ? 'critical' : d.attempts > 1 ? 'warning' : 'running');
export const deliveryLabel = (d: Delivery): string =>
  d.state === 'delivered' ? 'delivered' : d.state === 'failed' ? 'failed' : d.attempts > 1 ? `retrying (#${d.attempts})` : 'pending';

export function shorten(text: string, max = 90): string {
  const one = text.replace(/\s+/g, ' ').trim();
  return one.length > max ? `${one.slice(0, max - 1)}…` : one;
}
