import type {
  Nav,
  Overview,
  Page,
  Range,
  TaskSummary,
  ToolCalls,
  WorktreeSummary,
} from '../../shared/contract';
import type { ApiRequest, Query } from '../../shared/request';
import { HttpError } from '../daemon/DaemonClient';
import type { ApiSource } from './ApiSource';
import { MockData } from './mockData';

const DAY = 86_400_000;

// Weekly call telemetry per tool, scaled to the requested range.
const TOOL_CALLS: ToolCalls[] = [
  { tool: 'find', calls: 1210, p50Ms: 7, p95Ms: 41, avgResultChars: 640, emptyShare: 0.06, busy: 0, errors: 2 },
  { tool: 'symbol', calls: 812, p50Ms: 9, p95Ms: 58, avgResultChars: 2310, emptyShare: 0.03, busy: 1, errors: 4 },
  { tool: 'outline', calls: 604, p50Ms: 8, p95Ms: 36, avgResultChars: 1480, emptyShare: 0.01, busy: 0, errors: 0 },
  { tool: 'issue', calls: 410, p50Ms: 4, p95Ms: 19, avgResultChars: 1720, emptyShare: 0, busy: 0, errors: 1 },
  { tool: 'usages', calls: 233, p50Ms: 18, p95Ms: 140, avgResultChars: 1960, emptyShare: 0.09, busy: 2, errors: 0 },
  { tool: 'changes', calls: 96, p50Ms: 120, p95Ms: 910, avgResultChars: 4100, emptyShare: 0.02, busy: 3, errors: 1 },
];

/** Answers the read-only API from deterministic data, with the daemon's filtering, sorting and paging. */
export class MockApi implements ApiSource {
  readonly kind = 'mock';
  private readonly data: MockData;

  constructor(now = Date.now()) {
    this.data = new MockData(now);
  }

  async get(req: ApiRequest): Promise<unknown> {
    const q = req.query ?? {};
    const d = this.data;
    switch (req.resource) {
      case 'nav': return this.nav(q);
      case 'overview': return this.overview(range(q));
      case 'worktrees': return { items: this.worktrees(q) };
      case 'worktrees/:id': return found(d.worktreeDetail(req.id!));
      case 'tasks': return { ...this.tasks(q), mirrorSyncedAt: new Date(d.now - 120_000).toISOString() };
      case 'tasks/:id': return found(d.taskDetail(req.id!));
      case 'index': return d.index();
      case 'gaps': {
        const g = d.gaps(range(q));
        const items = g.items.filter(x => (!q.tool || x.tool === q.tool) && (!q.reason || x.reason === q.reason));
        return { summary: g.summary.filter(x => !q.tool || x.tool === q.tool), items, report: d.gapReport() };
      }
      case 'environment': return d.environment();
      case 'environment/audit': return d.environmentAudit(q.name === undefined ? null : String(q.name), q.limit === undefined ? 100 : Number(q.limit));
      case 'settings': return d.settings();
      case 'events': return d.events(q.since === undefined ? null : Number(q.since));
      case 'status/history': return d.statusHistory();
      default: return unreachable(req.resource);
    }
  }

  private nav(q: Query): Nav {
    const since = typeof q.gapsSince === 'string' ? Date.parse(q.gapsSince) : this.data.now - DAY;
    return {
      activeWorktrees: this.data.worktrees.filter(w => !w.isMain).length,
      openTasks: this.data.tasks.filter(t => t.state !== 'Done').length,
      newGaps: this.data.gaps('30d').items.filter(g => Date.parse(g.at) > since).length,
      indexState: 'ready',
    };
  }

  private overview(r: Range): Overview {
    const d = this.data;
    const span = d.rangeMs(r);
    const sum = (xs: { weighted: number }[]) => xs.reduce((a, x) => a + x.weighted, 0);
    const dayStart = startOfDay(d.now);
    const today = d.usage.filter(x => Date.parse(x.at) >= dayStart);
    const yesterday = d.usage.filter(x => {
      const t = Date.parse(x.at);
      return t >= dayStart - DAY && t < d.now - DAY;
    });
    const bucket = r === '24h' ? 3_600_000 : DAY;
    const from = Math.floor((d.now - span) / bucket) * bucket;
    const series: Overview['costSeries'] = [];
    for (let t = from; t <= d.now; t += bucket) {
      const w = sum(d.usage.filter(x => { const s = Date.parse(x.at); return s >= t && s < t + bucket; }));
      series.push({ t: new Date(t).toISOString(), weighted: w, baseline: Math.round(w * 1.17 + (r === '24h' ? 20_000 : 300_000)) });
    }
    const weightedRange = sum(d.usageIn(r));
    const baselineRange = series.reduce((a, x) => a + x.baseline, 0);
    const savedTokens = Math.max(0, baselineRange - weightedRange);
    const savings = [
      { tool: 'symbol', share: 0.38, calls: 812 },
      { tool: 'outline', share: 0.24, calls: 604 },
      { tool: 'find', share: 0.14, calls: 1210 },
      { tool: 'changes', share: 0.12, calls: 96 },
      { tool: 'usages', share: 0.08, calls: 233 },
      { tool: 'issue', share: 0.04, calls: 410 },
    ];
    const scale = span / (7 * DAY);
    const gaps = d.gaps(r);
    return {
      range: r,
      generatedAt: new Date(d.now).toISOString(),
      kpis: {
        weightedToday: sum(today), weightedYesterdaySameTime: sum(yesterday),
        weightedRange, baselineRange, savedTokens,
        savedPct: baselineRange ? Math.round((savedTokens / baselineRange) * 1000) / 10 : 0,
        activeWindows: 6, queriedWorktrees: d.worktrees.filter(w => w.queries24h > 0).length,
        codeloupeCalls: Math.round(3412 * scale), callP50Ms: 9,
        gaps: gaps.items.length, newGaps: gaps.items.filter(g => Date.parse(g.at) > d.now - DAY).length,
      },
      budget: { dailyWeighted: 25_000_000, usedToday: sum(today) },
      costSeries: series,
      savingsByTool: savings.map(s => ({ tool: s.tool, calls: Math.round(s.calls * scale), savedTokens: Math.round(savedTokens * s.share) })),
      toolCalls: TOOL_CALLS.map(t => ({ ...t, calls: Math.round(t.calls * scale), busy: Math.round(t.busy * scale), errors: Math.round(t.errors * scale) })),
    };
  }

  private worktrees(q: Query): WorktreeSummary[] {
    const text = typeof q.q === 'string' ? q.q.toLowerCase() : '';
    return this.data.worktrees.filter(w =>
      (!q.repo || w.repoId === q.repo) && (!q.layer || w.layer === q.layer)
      && (!text || `${w.branch} ${w.taskId} ${w.path}`.toLowerCase().includes(text)));
  }

  private tasks(q: Query): Page<TaskSummary> {
    const text = typeof q.q === 'string' ? q.q.toLowerCase() : '';
    const xs = this.data.tasks.filter(t =>
      (!q.project || t.project === q.project) && (!q.state || t.state === q.state)
      && (!text || `${t.id} ${t.summary}`.toLowerCase().includes(text)));
    return page(xs, q);
  }
}

/** A resource the switch above does not answer is a compile error here. */
function unreachable(resource: never): never {
  throw new HttpError(404, 'not_found', `no mock for ${String(resource)}`);
}

function range(q: Query): Range {
  return q.range === '24h' || q.range === '30d' ? q.range : '7d';
}

function found<T>(x: T | null): T {
  if (x === null) throw new HttpError(404, 'not_found', 'not found');
  return x;
}

function startOfDay(ms: number): number {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

function pageLimit(q: Query): number {
  return Math.min(200, Math.max(1, Number(q.limit ?? 50) || 50));
}

function page<T>(xs: T[], q: Query): Page<T> {
  const limit = pageLimit(q);
  const offset = Math.max(0, Number(q.cursor ?? 0) || 0);
  const items = xs.slice(offset, offset + limit);
  return { items, total: xs.length, nextCursor: offset + limit < xs.length ? String(offset + limit) : null };
}
