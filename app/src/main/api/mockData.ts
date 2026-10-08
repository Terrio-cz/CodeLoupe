// Deterministic mock data that follows the read-only API contract (docs/ui-spec.md § 9).
// Numbers are shaped after the measured baseline (plan.md § 1) so the screens look like real use.
import {
  type DaemonEvent,
  type DaemonSettings,
  type Environment,
  type EnvironmentAction,
  type EnvironmentAudit,
  type Events,
  type GapReport,
  type Gaps,
  type IndexHealth,
  type ResourceSample,
  type TaskDetail,
  type TaskSummary,
  type WorktreeDetail,
  type WorktreeSummary,
} from '../../shared/contract';

// Token breakdown behind the mock cost series; the API itself only carries weighted totals.
interface Tokens {
  input: number;
  cacheWrite5m: number;
  cacheWrite1h: number;
  cacheRead: number;
  output: number;
}

/** Weighted cost of a token breakdown (analysis.md, plan.md § 1). */
function weighted(t: Tokens): number {
  return Math.round(t.input + 1.25 * t.cacheWrite5m + 2 * t.cacheWrite1h + 0.1 * t.cacheRead + 5 * t.output);
}

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const ROTATION_DAYS = 90;

/** Small seeded PRNG so every launch shows the same data. */
export function rng(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

const iso = (ms: number) => new Date(ms).toISOString();

// Session cost and length medians by agent role (plan.md § 1); only the token totals are used.
const SHAPES: { cost: number; turns: number }[] = [
  { cost: 392_000, turns: 14 },
  { cost: 1_000_000, turns: 52 },
  { cost: 921_000, turns: 55 },
  { cost: 187_000, turns: 17 },
  { cost: 260_000, turns: 24 },
  { cost: 2_100_000, turns: 74 },
  { cost: 60_000, turns: 9 },
];

interface WtSeed { id: string; repo: 'terrio' | 'codeloupe'; branch: string | null; task: string | null; isMain?: boolean }
const WT_SEEDS: WtSeed[] = [
  { id: 'a1f3c09e4b21', repo: 'terrio', branch: 'TER-671', task: 'TER-671' },
  { id: 'b7e2d4410c9a', repo: 'terrio', branch: 'TER-672', task: 'TER-672' },
  { id: 'c90d1e7f3a55', repo: 'terrio', branch: 'TER-664', task: 'TER-664' },
  { id: 'd2c4b6a80e13', repo: 'terrio', branch: 'TER-591', task: 'TER-591' },
  { id: 'e5a7f9c1b246', repo: 'terrio', branch: 'TER-114', task: 'TER-114' },
  { id: 'f0b1c2d3e4f5', repo: 'codeloupe', branch: 'CL-43', task: 'CL-43' },
  { id: '0a9b8c7d6e5f', repo: 'codeloupe', branch: 'CL-56', task: 'CL-56' },
  { id: '13579bdf0246', repo: 'terrio', branch: 'master', task: null, isMain: true },
  { id: '2468ace13579', repo: 'codeloupe', branch: 'main', task: null, isMain: true },
];

const REPOS = {
  terrio: { id: 'r-5c1e0a', name: 'TerrioImporter', main: 'C:/Users/dev/IdeaProjects/TerrioImporter', wt: 'C:/Users/dev/IdeaProjects/terrio-worktrees', base: 'origin/master' },
  codeloupe: { id: 'r-9d4b27', name: 'CodeLoupe', main: 'C:/Users/dev/IdeaProjects/CodeLoupe', wt: 'C:/Users/dev/IdeaProjects/codeloupe-worktrees', base: 'origin/main' },
};

const TASK_SEEDS: { id: string; summary: string; state: string; priority: string; type: string }[] = [
  { id: 'TER-671', summary: 'Scope the statistics anti-join to complete revisions', state: 'In Progress', priority: 'Major', type: 'Bug' },
  { id: 'TER-672', summary: 'Batch geometry lookups for the parcel endpoint', state: 'In Progress', priority: 'Normal', type: 'Task' },
  { id: 'TER-664', summary: 'Attribution block on every dataset response', state: 'In Progress', priority: 'Major', type: 'Feature' },
  { id: 'TER-591', summary: 'Rate-limit headers for token-priced operations', state: 'Ready for testing', priority: 'Normal', type: 'Feature' },
  { id: 'TER-114', summary: 'Importer retries for partial RÚIAN downloads', state: 'In Progress', priority: 'Minor', type: 'Bug' },
  { id: 'TER-655', summary: 'OpenAPI examples for address search', state: 'To do', priority: 'Minor', type: 'Task' },
  { id: 'TER-660', summary: 'Phase mode for launcher tasks', state: 'Done', priority: 'Normal', type: 'Task' },
  { id: 'CL-43', summary: 'Electron app: window, tray, notifications, daemon management', state: 'In Progress', priority: 'Normal', type: 'Feature' },
  { id: 'CL-56', summary: 'Port CodeLoupe to Kotlin/JVM with parity to phase 1', state: 'In Progress', priority: 'Normal', type: 'Feature' },
  { id: 'CL-39', summary: 'Read-only UI API in the daemon', state: 'To do', priority: 'Normal', type: 'Feature' },
  { id: 'CL-22', summary: 'Gap detector: agent falls back to rg/sed/cat/Read after a CodeLoupe call', state: 'To do', priority: 'Normal', type: 'Feature' },
  { id: 'CL-26', summary: 'Local YouTrack mirror with incremental watcher', state: 'To do', priority: 'Normal', type: 'Feature' },
];

function tokensFor(cost: number, r: () => number): Tokens {
  // Cache reads dominate (48–66 % of cost, plan.md § 1); solve the rest from the weighted formula.
  const cacheRead = Math.round((cost * (0.48 + r() * 0.18)) / 0.1);
  const output = Math.round((cost * (0.05 + r() * 0.04)) / 5);
  const cacheWrite1h = Math.round((cost * (0.18 + r() * 0.08)) / 2);
  const cacheWrite5m = Math.round((cost * 0.04) / 1.25);
  const used = 0.1 * cacheRead + 5 * output + 2 * cacheWrite1h + 1.25 * cacheWrite5m;
  return { input: Math.max(200, Math.round(cost - used)), cacheWrite5m, cacheWrite1h, cacheRead, output };
}

interface Usage {
  session: string;
  at: string;
  weighted: number;
  turns: number;
}

export class MockData {
  readonly now: number;
  readonly worktrees: WorktreeSummary[];
  /** Token usage samples behind the cost series; the app never shows them as agent runs. */
  readonly usage: Usage[];
  readonly tasks: TaskSummary[];
  private seqBase = 4100;

  constructor(now = Date.now()) {
    this.now = now;
    const r = rng(42);
    this.worktrees = WT_SEEDS.map((s, i) => {
      const repo = REPOS[s.repo];
      const layer = s.isMain ? 'fresh' : (['fresh', 'fresh', 'stale', 'fresh', 'building', 'fresh', 'error'] as const)[i % 7];
      const files = s.isMain ? 0 : 2 + Math.floor(r() * 30);
      return {
        id: s.id, repoId: repo.id, repoName: repo.name,
        path: s.isMain ? repo.main : `${repo.wt}/${s.branch}`,
        branch: s.branch, head: (hash(s.id) >>> 0).toString(16).padStart(8, '0').slice(0, 7),
        isMain: !!s.isMain, taskId: s.task,
        ahead: s.isMain ? 0 : 1 + Math.floor(r() * 6), behind: s.isMain ? 0 : Math.floor(r() * 30),
        changedFiles: files, changedDecls: s.isMain ? 0 : Math.round(files * (1.5 + r() * 2)),
        layer, lastActivityAt: iso(now - Math.floor(r() * 5 * HOUR)), queries24h: s.isMain ? Math.floor(r() * 40) : 5 + Math.floor(r() * 120),
      };
    });

    const usage: Usage[] = [];
    for (let i = 0; i < 140; i++) {
      const shape = SHAPES[Math.floor(r() ** 1.4 * SHAPES.length)];
      // A few sessions today so the day KPI and the budget meter have data.
      const at = i < 8 ? now - Math.floor(r() * 5 * HOUR) - 60_000 : now - Math.floor(r() ** 0.8 * 30 * DAY) - 60_000;
      const tokens = tokensFor(Math.round(shape.cost * (0.35 + r() * 1.5)), r);
      usage.push({ session: `s-${(hash(`s${i}`) >>> 0).toString(36)}`, at: iso(at), weighted: weighted(tokens), turns: Math.max(3, Math.round(shape.turns * (0.4 + r() * 1.3))) });
    }
    usage.sort((a, b) => b.at.localeCompare(a.at));
    this.usage = usage;

    this.tasks = TASK_SEEDS.map((t, i) => ({
      id: t.id, project: t.id.split('-')[0], summary: t.summary, state: t.state, priority: t.priority, type: t.type,
      assignee: 'Tadeáš G.', updatedAt: iso(now - (i + 1) * 3.7 * HOUR),
      reads: 1 + (hash(t.id) % 9),
      worktreeIds: this.worktrees.filter(w => w.taskId === t.id).map(w => w.id),
    }));
  }

  rangeMs(range: string): number {
    return range === '24h' ? DAY : range === '30d' ? 30 * DAY : 7 * DAY;
  }

  usageIn(range: string): Usage[] {
    const from = this.now - this.rangeMs(range);
    return this.usage.filter(x => Date.parse(x.at) >= from);
  }

  worktreeDetail(id: string): WorktreeDetail | null {
    const w = this.worktrees.find(x => x.id === id);
    if (!w) return null;
    const r = rng(hash(id));
    const pkg = w.repoName === 'CodeLoupe' ? 'cz.terrio.codeloupe' : 'cz.terrio.importer';
    const names = ['OrderStatistics', 'RevisionRepository', 'ParcelRoutes', 'GeometryBatcher', 'AttributionBlock', 'RateLimitHeaders', 'ImportRetryPolicy', 'TokenMeter'];
    const members = ['handle', 'find', 'load', 'apply', 'toResponse', 'compute', 'validate', 'complete'];
    const changes: WorktreeDetail['changes'] = [];
    for (let i = 0; i < w.changedDecls; i++) {
      const type = names[Math.floor(r() * names.length)];
      const change = (['body', 'body', 'added', 'signature', 'removed'] as const)[Math.floor(r() * 5)];
      const isType = r() < 0.2;
      changes.push({
        change, kind: isType ? 'class' : 'fun',
        fqn: isType ? `${pkg}.${type}` : `${pkg}.${type}.${members[Math.floor(r() * members.length)]}`,
        path: `${w.repoName === 'CodeLoupe' ? 'src/main/kotlin' : 'domain/src/main/kotlin'}/${type}.kt`,
        line: change === 'removed' ? null : 10 + Math.floor(r() * 300), callers: Math.floor(r() * 14),
      });
    }
    const callers = changes.filter(c => c.change !== 'added').slice(0, 24).map((c, i) => ({
      fqn: `${pkg}.${names[(i + 3) % names.length]}.${members[(i + 1) % members.length]}`,
      path: `app/src/main/kotlin/${names[(i + 3) % names.length]}.kt`, line: 20 + i * 7, calls: c.fqn, exact: r() > 0.15,
    }));
    const tests = changes.slice(0, Math.min(8, changes.length)).map((c, i) => ({
      path: c.path.replace('src/main', 'src/test').replace('.kt', 'Test.kt'),
      fqn: `${c.fqn.split('.').slice(0, -1).join('.')}Test`, reason: (i % 3 === 0 ? 'touched' : 'calls_changed') as 'touched' | 'calls_changed',
    }));
    const task = this.tasks.find(t => t.id === w.taskId) ?? null;
    return {
      ...w,
      baseRef: w.repoName === 'CodeLoupe' ? 'origin/main' : 'origin/master',
      mergeBase: (hash(`${id}base`) >>> 0).toString(16).slice(0, 7),
      changes, callers, tests,
      task,
      index: {
        layerFiles: w.changedFiles, parsedAt: w.layer === 'none' ? null : iso(this.now - 4 * 60_000),
        errorFiles: w.layer === 'error' ? [`src/main/kotlin/${names[0]}.kt`] : [],
      },
    };
  }

  taskDetail(id: string): TaskDetail | null {
    const t = this.tasks.find(x => x.id === id);
    if (!t) return null;
    const base = this.now - 3 * DAY;
    const instance = t.project === 'CL' ? 'CL' : 'TER';
    return {
      ...t,
      url: `https://terrio.youtrack.cloud/issue/${t.id}`,
      description: `## Context\n${t.summary}. Mock description from the YouTrack mirror.\n\n## Scope\n- Change the affected flow in one module\n- Keep source attribution on every response\n\n## Verification\nTargeted tests and an isolated stack with real calls.`,
      fields: [
        { name: 'Project', value: instance }, { name: 'Type', value: t.type ?? '—' }, { name: 'Priority', value: t.priority ?? '—' },
        { name: 'State', value: t.state }, { name: 'Assignee', value: t.assignee ?? '—' }, { name: 'Fix versions', value: instance === 'CL' ? '0.4 Desktop' : '—' },
      ],
      criteria: [
        { text: 'Behaviour covered by a targeted test', checked: true },
        { text: 'Isolated stack verified with real calls', checked: true },
        { text: 'Docs updated where behaviour changed', checked: t.state === 'Done' },
        { text: 'Review without open findings', checked: t.state === 'Done' || t.state === 'Ready for testing' },
      ],
      links: [{ type: 'subtask of', id: instance === 'CL' ? 'CL-7' : 'TER-162', summary: instance === 'CL' ? 'Desktop app' : 'Public data API' }],
      activity: [
        { at: iso(base), author: 'Tadeáš G.', kind: 'created', text: 'created the issue' },
        { at: iso(base + 2 * HOUR), author: 'Tadeáš G.', kind: 'state', text: 'To do → In Progress' },
        { at: iso(base + 30 * HOUR), author: 'orchestrator', kind: 'comment', text: 'Plán hotový, kolo 1 běží.' },
        { at: iso(base + 50 * HOUR), author: 'orchestrator', kind: 'field', text: 'Test Evidence: attached' },
      ],
      worktrees: this.worktrees.filter(w => w.taskId === id),
      mirror: { syncedAt: iso(this.now - 2 * 60_000), lastReadAt: iso(this.now - 47 * 60_000) },
    };
  }

  index(): IndexHealth {
    const r = rng(7);
    const builds: IndexHealth['builds'] = [];
    for (let i = 0; i < 14; i++) {
      const repo = i % 4 === 3 ? REPOS.codeloupe : REPOS.terrio;
      const kind = (i % 5 === 0 ? 'full' : i % 2 ? 'layer' : 'sync') as 'full' | 'sync' | 'layer';
      const failed = i === 6;
      builds.push({
        id: `b-${1000 - i}`, repoId: repo.id, kind, startedAt: iso(this.now - (i * 2.3 + 0.2) * HOUR),
        durationMs: kind === 'full' ? 5_400 + Math.round(r() * 900) : 300 + Math.round(r() * 900),
        peakRssMb: kind === 'full' ? 540 + Math.round(r() * 80) : null,
        files: kind === 'full' ? (repo === REPOS.terrio ? 2_211 : 64) : 1 + Math.floor(r() * 40),
        status: failed ? 'failed' : 'ok', error: failed ? 'git cat-file exited with code 128' : null,
      });
    }
    return {
      repos: [
        { id: REPOS.terrio.id, name: 'TerrioImporter', path: REPOS.terrio.main, baseRef: 'origin/master', baseCommit: '6ceb22e', state: 'ready',
          builtAt: iso(this.now - 0.2 * HOUR), buildMs: 5_400, dbBytes: 57 * 1024 * 1024, files: 2_211, decls: 44_012, refs: 361_204, errorFiles: 20, layers: 6 },
        { id: REPOS.codeloupe.id, name: 'CodeLoupe', path: REPOS.codeloupe.main, baseRef: 'origin/main', baseCommit: 'ce01954', state: 'stale',
          builtAt: iso(this.now - 7 * HOUR), buildMs: 610, dbBytes: 3 * 1024 * 1024, files: 64, decls: 1_102, refs: 9_870, errorFiles: 0, layers: 2 },
      ],
      builds,
      errorFiles: Array.from({ length: 20 }, (_, i) => ({
        repoId: REPOS.terrio.id,
        path: `${['app', 'domain', 'public-api', 'importers/ruian/addresses'][i % 4]}/src/main/kotlin/${['Routes', 'OpenRule', 'Mapper', 'Dsl', 'Config'][i % 5]}${i}.kt`,
        errors: 1 + (i % 3), firstLine: 12 + i * 9,
      })),
      budgets: { buildPeakRssMb: 600, daemonRssMb: 200 },
    };
  }

  gaps(range: string): Gaps {
    const from = this.now - this.rangeMs(range);
    const r = rng(11);
    const shapes = [
      { tool: 'symbol', shape: 'Type.member (overload)', fallback: 'Read' as const },
      { tool: 'find', shape: 'glob *Repository', fallback: 'rg' as const },
      { tool: 'outline', shape: 'file > 1 500 lines', fallback: 'sed' as const },
      { tool: 'symbol', shape: 'extension fun', fallback: 'grep' as const },
      { tool: 'find', shape: 'kind=property', fallback: 'rg' as const },
    ];
    const sessions = this.usage;
    const items: Gaps['items'] = [];
    for (let i = 0; i < 40; i++) {
      const s = shapes[Math.floor(r() ** 1.3 * shapes.length)];
      const u = sessions[Math.floor(r() * sessions.length)];
      items.push({
        id: `g-${i}`, at: iso(Date.parse(u.at) + Math.floor(r() * HOUR)), tool: s.tool, shape: s.shape, fallback: s.fallback,
        reason: (['followup_read', 'followup_read', 'empty', 'candidate_manual'] as const)[Math.floor(r() * 4)],
        session: u.session, turn: 2 + Math.floor(r() * Math.max(1, u.turns - 2)),
        target: ['OrderStatistics.handle', 'RevisionRepository', 'ParcelRoutes.kt', 'String.toSlug', 'TokenMeter.limit'][i % 5],
      });
    }
    const inRange = items.filter(g => Date.parse(g.at) >= from).sort((a, b) => b.at.localeCompare(a.at));
    const groups = new Map<string, Gaps['summary'][number]>();
    for (const g of inRange) {
      const key = `${g.tool}|${g.shape}`;
      const e = groups.get(key) ?? { tool: g.tool, shape: g.shape, fallback: g.fallback, count: 0, lastAt: g.at };
      e.count++;
      if (g.at > e.lastAt) e.lastAt = g.at;
      groups.set(key, e);
    }
    return { summary: [...groups.values()].sort((a, b) => b.count - a.count), items: inRange, report: null };
  }

  /** The weekly report of `codeloupe metrics gaps` over four weeks (the shape of its JSON, plus when it was computed). */
  gapReport(): GapReport {
    const r = rng(31);
    const week = (offset: number) => {
      // ISO 8601 week of the Thursday of that week.
      const d = new Date(this.now - offset * 7 * DAY);
      d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7) + 3);
      const first = Date.UTC(d.getUTCFullYear(), 0, 4);
      const n = 1 + Math.round(((d.getTime() - first) / DAY - 3 + ((new Date(first).getUTCDay() + 6) % 7)) / 7);
      return `${d.getUTCFullYear()}-W${String(n).padStart(2, '0')}`;
    };
    const shapes: [string, string, GapReport['rows'][number]['kind'], string[]][] = [
      ['symbol', 'symbol:qualified', 'fallback', ['OrderStatistics.handle', 'RevisionRepository.find']],
      ['find', 'find:glob', 'fallback', ['*Repository', '*Routes']],
      ['symbol', 'symbol:overload', 'candidates', ['render', 'apply']],
      ['outline', 'outline:path', 'fallback', ['ParcelRoutes.kt']],
      ['find', 'find:name', 'empty', ['TokenMeter', 'RateLimit']],
      ['usages', 'usages:name', 'busy', []],
      ['symbol', 'symbol:name', 'fallback', ['PriceRule']],
    ];
    const rows: GapReport['rows'] = [];
    for (let w = 3; w >= 0; w--) {
      for (const [tool, shape, kind, examples] of shapes) {
        if (r() < 0.25) continue;
        rows.push({ week: week(w), tool, shape, kind, count: 1 + Math.floor(r() ** 2 * 14), examples });
      }
    }
    rows.sort((a, b) => a.week.localeCompare(b.week) || b.count - a.count || a.shape.localeCompare(b.shape));
    return { generatedAt: iso(this.now - 2 * HOUR), since: iso(this.now - 30 * DAY).slice(0, 10), runs: 612, calls: 3_412, rows };
  }

  /** Four hours of one reading a minute: RSS climbs while the daemon is used, drops after a build, CPU time only grows. */
  statusHistory(): ResourceSample[] {
    const r = rng(23);
    let cpu = 40;
    return Array.from({ length: 240 }, (_, i) => {
      const wave = 18 * Math.sin(i / 17) + (i % 60 === 45 ? 55 : 0);
      cpu += 0.2 + r() * (i % 40 > 30 ? 3 : 0.8);
      const rss = Math.round(88 + i * 0.22 + wave + r() * 6);
      return { t: iso(this.now - (239 - i) * 60_000), rssMb: rss, heapMb: Math.round(rss * 0.55), cpuSec: Math.round(cpu) };
    });
  }

  environment(): Environment {
    const k = (name: string, scope: Environment['keys'][number]['scope'], scopeRef: string | null, source: Environment['keys'][number]['source'], consumers: string[], usedH: number | null, updatedD: number): Environment['keys'][number] =>
      ({
        name, scope, scopeRef, source, sourceRef: source === 'file' ? 'C:/Users/dev/IdeaProjects/TerrioImporter/.env' : null, consumers, reads: consumers.length * 7,
        lastUsedAt: usedH === null ? null : iso(this.now - usedH * HOUR), createdAt: iso(this.now - (updatedD + 3) * DAY), updatedAt: iso(this.now - updatedD * DAY),
        ageDays: updatedD, rotationDue: updatedD >= ROTATION_DAYS,
      });
    return {
      storeReady: true,
      rotationDays: ROTATION_DAYS,
      keys: [
        k('YOUTRACK_TOKEN', 'global', null, 'store', ['youtrack MCP', 'codeloupe mirror'], 0.05, 9),
        k('TERRIO_API_KEY', 'repo', 'TerrioImporter', 'file', ['run/terrio.mjs api'], 20, 14),
        k('GITHUB_TOKEN', 'global', null, 'store', ['gh'], 3, 120),
        k('POSTGRES_PASSWORD', 'repo', 'TerrioImporter', 'file', ['docker compose'], 1, 95),
        k('MOBBIN_API_KEY', 'workspace', 'terrio', 'store', [], null, 2),
      ],
    };
  }

  environmentAudit(name: string | null, limit: number): EnvironmentAudit {
    const e = (hoursAgo: number, n: string, scope: EnvironmentAudit['events'][number]['scope'], scopeRef: string | null, action: EnvironmentAction, consumer: string) =>
      ({ at: iso(this.now - hoursAgo * HOUR), name: n, scope, scopeRef, action, consumer });
    const all = [
      e(0.05, 'YOUTRACK_TOKEN', 'global', null, 'read', 'youtrack MCP'),
      e(0.4, 'YOUTRACK_TOKEN', 'global', null, 'read', 'codeloupe mirror'),
      e(1, 'POSTGRES_PASSWORD', 'repo', 'TerrioImporter', 'read', 'docker compose'),
      e(3, 'GITHUB_TOKEN', 'global', null, 'read', 'gh'),
      e(20, 'TERRIO_API_KEY', 'repo', 'TerrioImporter', 'read', 'run/terrio.mjs api'),
      e(48, 'MOBBIN_API_KEY', 'workspace', 'terrio', 'created', 'app'),
      e(216, 'YOUTRACK_TOKEN', 'global', null, 'rotated', 'app'),
    ];
    return { events: all.filter(x => name === null || x.name === name).slice(0, limit) };
  }

  settings(): DaemonSettings {
    return {
      port: 47391, home: 'C:/Users/dev/AppData/Local/codeloupe', configFile: 'C:/Users/dev/AppData/Local/codeloupe/config.json', defaultRoot: null,
      repos: [
        { id: REPOS.terrio.id, path: REPOS.terrio.main, baseRef: 'origin/master' },
        { id: REPOS.codeloupe.id, path: REPOS.codeloupe.main, baseRef: 'origin/main' },
      ],
      youtrack: [{ url: 'https://terrio.youtrack.cloud', projects: ['TER', 'CL'], tokenConfigured: true, pollSec: 180 }],
      budgets: { dailyWeighted: 25_000_000, daemonRssMb: 200, buildPeakRssMb: 600, p95Ms: 1000, queueWaitMs: 30_000, busyRate: 0.1 },
    };
  }

  /** One budget breach after the first poll, so notifications can be seen working in mock mode. */
  events(since: number | null): Events {
    const breach: DaemonEvent = {
      seq: this.seqBase + 1, at: iso(this.now), kind: 'budget_breach', severity: 'warning',
      title: 'Denní rozpočet překročen', body: 'Dnes 26,1M vážených tokenů z 25M.', ref: { screen: 'overview', id: null },
    };
    const epoch = 'mock-1';
    if (since === null) return { epoch, lastSeq: this.seqBase, items: [] };
    return since < breach.seq ? { epoch, lastSeq: breach.seq, items: [breach] } : { epoch, lastSeq: since, items: [] };
  }
}
