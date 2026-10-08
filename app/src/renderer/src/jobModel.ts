import type { Delivery, JobRecord, SlotSnapshot } from '../../shared/jobs';
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
    case 'queued': return { tone: 'neutral', label: j.slot ? `čeká na slot ${j.slot}` : 'spouští se' };
    case 'running': return { tone: 'running', label: 'běží' };
    case 'done': return j.exit === 0 ? { tone: 'ok', label: 'hotovo' } : { tone: 'critical', label: `selhalo (exit ${j.exit})` };
    case 'denied': return { tone: 'serious', label: 'zamítnuto' };
    case 'cancelled': return { tone: 'neutral', label: 'zrušeno' };
    case 'lost': return { tone: 'warning', label: 'ztraceno' };
    case 'error': return { tone: 'critical', label: 'chyba' };
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

export const deliveryTone = (d: Delivery): Tone => (d.state === 'delivered' ? 'ok' : d.state === 'failed' ? 'critical' : d.attempts > 1 ? 'warning' : 'running');
export const deliveryLabel = (d: Delivery): string =>
  d.state === 'delivered' ? 'doručeno' : d.state === 'failed' ? 'selhalo' : d.attempts > 1 ? `opakuje (${d.attempts}.)` : 'čeká';

export function shorten(text: string, max = 90): string {
  const one = text.replace(/\s+/g, ' ').trim();
  return one.length > max ? `${one.slice(0, max - 1)}…` : one;
}
