import type {
  Nav,
  Overview,
  Page,
  Range,
  RunStep,
  RunSummary,
  TaskSummary,
  WorktreeSummary,
} from '../../shared/contract';
import type { ApiRequest, Query } from '../../shared/request';
import { HttpError } from '../daemon/DaemonClient';
import type { ApiSource } from './ApiSource';
import { MockData } from './mockData';

const DAY = 86_400_000;

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
      case 'runs': return this.runs(q);
      case 'runs/:id': return found(d.runDetail(req.id!));
      case 'runs/:id/steps': return this.steps(req.id!, q);
      case 'tasks': return { ...this.tasks(q), mirrorSyncedAt: new Date(d.now - 120_000).toISOString() };
      case 'tasks/:id': return found(d.taskDetail(req.id!));
      case 'index': return d.index();
      case 'gaps': {
        const g = d.gaps(range(q));
        const items = g.items.filter(x => (!q.tool || x.tool === q.tool) && (!q.reason || x.reason === q.reason));
        return { summary: g.summary.filter(x => !q.tool || x.tool === q.tool), items };
      }
      case 'environment': return d.environment();
      case 'settings': return d.settings();
      case 'events': return d.events(q.since === undefined ? null : Number(q.since));
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
    const runs = d.runsIn(r);
    const sum = (xs: RunSummary[]) => xs.reduce((a, x) => a + x.weighted, 0);
    const dayStart = startOfDay(d.now);
    const today = d.runs.filter(x => Date.parse(x.startedAt) >= dayStart);
    const yesterday = d.runs.filter(x => {
      const t = Date.parse(x.startedAt);
      return t >= dayStart - DAY && t < d.now - DAY;
    });
    const bucket = r === '24h' ? 3_600_000 : DAY;
    const from = Math.floor((d.now - span) / bucket) * bucket;
    const series: Overview['costSeries'] = [];
    for (let t = from; t <= d.now; t += bucket) {
      const w = sum(d.runs.filter(x => { const s = Date.parse(x.startedAt); return s >= t && s < t + bucket; }));
      series.push({ t: new Date(t).toISOString(), weighted: w, baseline: Math.round(w * 1.17 + (r === '24h' ? 20_000 : 300_000)) });
    }
    const weightedRange = sum(runs);
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
        runs: runs.length, activeWindows: 6, runningRuns: d.runs.filter(x => x.status === 'running').length,
        codeloupeCalls: Math.round(3412 * scale), callP50Ms: 9,
        gaps: gaps.items.length, newGaps: gaps.items.filter(g => Date.parse(g.at) > d.now - DAY).length,
      },
      budget: { dailyWeighted: 25_000_000, usedToday: sum(today) },
      costSeries: series,
      savingsByTool: savings.map(s => ({ tool: s.tool, calls: Math.round(s.calls * scale), savedTokens: Math.round(savedTokens * s.share) })),
      recentRuns: d.runs.slice(0, 10),
    };
  }

  private worktrees(q: Query): WorktreeSummary[] {
    const text = typeof q.q === 'string' ? q.q.toLowerCase() : '';
    return this.data.worktrees.filter(w =>
      (!q.repo || w.repoId === q.repo) && (!q.layer || w.layer === q.layer)
      && (!text || `${w.branch} ${w.taskId} ${w.path}`.toLowerCase().includes(text)));
  }

  private runs(q: Query): Page<RunSummary> {
    let xs = this.data.runsIn(range(q));
    if (q.role) xs = xs.filter(x => x.role === q.role);
    if (q.task) xs = xs.filter(x => x.taskId === q.task);
    if (q.worktree) xs = xs.filter(x => x.worktreeId === q.worktree);
    if (q.gapsOnly === true || q.gapsOnly === 'true') xs = xs.filter(x => x.gaps > 0);
    const key = String(q.sort ?? 'started');
    const val = (x: RunSummary): number => {
      switch (key) {
        case 'duration': return (x.endedAt ? Date.parse(x.endedAt) : this.data.now) - Date.parse(x.startedAt);
        case 'turns': return x.turns;
        case 'weighted': return x.weighted;
        case 'peakContext': return x.peakContext;
        case 'toolResultShare': return x.toolResultShare;
        case 'gaps': return x.gaps;
        default: return Date.parse(x.startedAt);
      }
    };
    return page(sortBy(xs, val, q.order), q);
  }

  private steps(id: string, q: Query): Page<RunStep> {
    let xs = found(this.data.runSteps(id));
    if (typeof q.kind === 'string') xs = xs.filter(s => s.kind === q.kind);
    if (typeof q.flags === 'string' && q.flags) {
      const want = q.flags.split(',');
      xs = xs.filter(s => want.every(f => s.flags.includes(f as RunStep['flags'][number])));
    }
    const key = String(q.sort ?? 'seq');
    const val = (s: RunStep): number =>
      key === 'weighted' ? s.weighted : key === 'carried' ? s.carriedWeighted : key === 'resultChars' ? s.resultChars
        : key === 'latency' ? s.latencyMs ?? -1 : s.seq;
    const sorted = sortBy(xs, val, q.order ?? (key === 'seq' ? 'asc' : 'desc'));
    if (q.around !== undefined) {
      // Start the page at the slot that contains the requested step (deep link from Gaps).
      const limit = pageLimit(q);
      const at = sorted.findIndex(s => s.seq === Number(q.around));
      if (at >= 0) return page(sorted, { ...q, cursor: String(Math.floor(at / limit) * limit) });
    }
    return page(sorted, q);
  }

  private tasks(q: Query): Page<TaskSummary> {
    const text = typeof q.q === 'string' ? q.q.toLowerCase() : '';
    const xs = this.data.tasks.filter(t =>
      (!q.project || t.project === q.project) && (!q.state || t.state === q.state)
      && (!text || `${t.id} ${t.summary}`.toLowerCase().includes(text)));
    return page(xs, q);
  }
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

function sortBy<T>(xs: T[], val: (x: T) => number, order: Query[string]): T[] {
  const dir = order === 'asc' ? 1 : -1;
  return [...xs].sort((a, b) => (val(a) - val(b)) * dir);
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
