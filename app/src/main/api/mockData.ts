// Deterministic mock data that follows the read-only API contract (docs/ui-spec.md § 9).
// Numbers are shaped after the measured baseline (plan.md § 1) so the screens look like real use.
import {
  weighted,
  type DaemonEvent,
  type DaemonSettings,
  type Environment,
  type Events,
  type Gaps,
  type IndexHealth,
  type RunDetail,
  type RunStep,
  type RunSummary,
  type StepFlag,
  type TaskDetail,
  type TaskSummary,
  type Tokens,
  type WorktreeDetail,
  type WorktreeSummary,
} from '../../shared/contract';

const HOUR = 3_600_000;
const RUN_BUDGET = 2_000_000;
const DAY = 24 * HOUR;

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

interface RoleShape { role: string; model: string; cost: number; turns: number; peak: number; share: number }
const ROLES: RoleShape[] = [
  { role: 'reviewer', model: 'fable-5.1', cost: 392_000, turns: 14, peak: 145_000, share: 0.33 },
  { role: 'planner', model: 'opus-5.5', cost: 1_000_000, turns: 52, peak: 182_000, share: 0.29 },
  { role: 'coder-high', model: 'opus-5.5', cost: 921_000, turns: 55, peak: 132_000, share: 0.24 },
  { role: 'coder', model: 'sonnet-5.5', cost: 187_000, turns: 17, peak: 61_000, share: 0.23 },
  { role: 'tester', model: 'sonnet-5.5', cost: 260_000, turns: 24, peak: 70_000, share: 0.27 },
  { role: 'deep-reviewer', model: 'opus-5.5', cost: 2_100_000, turns: 74, peak: 324_000, share: 0.4 },
  { role: 'context', model: 'haiku-4.5', cost: 60_000, turns: 9, peak: 30_000, share: 0.35 },
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

export class MockData {
  readonly now: number;
  readonly worktrees: WorktreeSummary[];
  readonly runs: RunSummary[];
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
        layer, lastActivityAt: iso(now - Math.floor(r() * 5 * HOUR)), activeRuns: s.isMain ? 0 : Math.floor(r() * 3),
      };
    });

    const runs: RunSummary[] = [];
    const taskIds = WT_SEEDS.filter(w => w.task).map(w => w.task as string);
    for (let i = 0; i < 140; i++) {
      const shape = ROLES[Math.floor(r() ** 1.4 * ROLES.length)];
      const task = taskIds[Math.floor(r() * taskIds.length)];
      const wt = this.worktrees.find(w => w.taskId === task) ?? null;
      const startedAt = now - Math.floor(r() ** 0.8 * 30 * DAY) - 60_000;
      const cost = Math.round(shape.cost * (0.35 + r() * 1.5));
      const turns = Math.max(3, Math.round(shape.turns * (0.4 + r() * 1.3)));
      const running = i < 3;
      const durationMs = turns * (20_000 + r() * 40_000);
      const tokens = tokensFor(cost, r);
      runs.push({
        id: `run-${(hash(`run${i}`) >>> 0).toString(16).padStart(8, '0')}`,
        sessionId: `s-${(hash(`s${i}`) >>> 0).toString(36)}`,
        role: shape.role, model: shape.model, taskId: task, worktreeId: wt?.id ?? null, branch: wt?.branch ?? null,
        startedAt: iso(running ? now - Math.floor(durationMs / 2) : startedAt),
        endedAt: running ? null : iso(startedAt + durationMs),
        status: running ? 'running' : r() < 0.04 ? 'error' : 'done',
        turns, weighted: weighted(tokens), tokens,
        peakContext: Math.round(shape.peak * (0.6 + r() * 0.7)),
        toolResultShare: Math.round((shape.share * (0.7 + r() * 0.6)) * 100) / 100,
        codeloupeCalls: Math.floor(r() * 30), gaps: r() < 0.25 ? 1 + Math.floor(r() * 3) : 0,
        overBudget: weighted(tokens) > RUN_BUDGET,
      });
    }
    runs.sort((a, b) => b.startedAt.localeCompare(a.startedAt));
    this.runs = runs;

    this.tasks = TASK_SEEDS.map((t, i) => ({
      id: t.id, project: t.id.split('-')[0], summary: t.summary, state: t.state, priority: t.priority, type: t.type,
      assignee: 'Tadeáš G.', updatedAt: iso(now - (i + 1) * 3.7 * HOUR),
      reads: 1 + (hash(t.id) % 9),
      worktreeIds: this.worktrees.filter(w => w.taskId === t.id).map(w => w.id),
      runs: runs.filter(x => x.taskId === t.id).length,
    }));
  }

  rangeMs(range: string): number {
    return range === '24h' ? DAY : range === '30d' ? 30 * DAY : 7 * DAY;
  }

  runsIn(range: string): RunSummary[] {
    const from = this.now - this.rangeMs(range);
    return this.runs.filter(x => Date.parse(x.startedAt) >= from);
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
      runs: this.runs.filter(x => x.worktreeId === id).slice(0, 12),
      task,
      index: {
        layerFiles: w.changedFiles, parsedAt: w.layer === 'none' ? null : iso(this.now - 4 * 60_000),
        errorFiles: w.layer === 'error' ? [`src/main/kotlin/${names[0]}.kt`] : [],
      },
    };
  }

  runDetail(id: string): RunDetail | null {
    const run = this.runs.find(x => x.id === id);
    const steps = this.runSteps(id);
    if (!run || !steps) return null;
    const byToolMap = new Map<string, RunDetail['byTool'][number]>();
    for (const s of steps) {
      const key = s.tool ?? (s.kind === 'prompt' ? 'prompt' : 'text');
      const e = byToolMap.get(key) ?? { tool: key, calls: 0, resultChars: 0, weighted: 0, carriedWeighted: 0 };
      e.calls++; e.resultChars += s.resultChars; e.weighted += s.weighted; e.carriedWeighted += s.carriedWeighted;
      byToolMap.set(key, e);
    }
    return { ...run, stepCount: steps.length, maxCarriedWeighted: Math.max(0, ...steps.map(s => s.carriedWeighted)), byTool: [...byToolMap.values()].sort((a, b) => b.carriedWeighted - a.carriedWeighted) };
  }

  runSteps(id: string): RunStep[] | null {
    const run = this.runs.find(x => x.id === id);
    if (!run) return null;
    const r = rng(hash(id));
    const tools = ['Read', 'Bash rg', 'Bash sed -n', 'Grep', 'Edit', 'mcp__codeloupe__symbol', 'mcp__codeloupe__outline', 'mcp__codeloupe__find', 'mcp__youtrack__yt_get_issue', 'Bash git diff', 'Agent'];
    const targets = ['OrderStatistics.kt', 'RevisionRepository.find', '"anti-join"', 'StatisticsRoutes.kt', 'TER-671', 'docs/api.md', 'ParcelRoutes.handle', 'build.gradle.kts'];
    const steps: RunStep[] = [];
    let t = Date.parse(run.startedAt);
    const perTurn = run.weighted / run.turns;
    for (let i = 0; i < run.turns; i++) {
      const kind: RunStep['kind'] = i === 0 ? 'prompt' : r() < 0.15 ? 'text' : 'tool';
      const tool = kind === 'tool' ? tools[Math.floor(r() * tools.length)] : null;
      const resultChars = kind === 'prompt' ? 4_000 + Math.floor(r() * 4_000) : kind === 'text' ? 200 + Math.floor(r() * 1_500) : Math.floor(200 + r() ** 3 * 42_000);
      const flags: StepFlag[] = [];
      if (resultChars > 10_000) flags.push('large_result');
      if (tool?.startsWith('mcp__codeloupe')) flags.push('codeloupe');
      if (tool && /rg|sed|Read|Grep/.test(tool) && r() < 0.18 && run.gaps > 0) flags.push('gap');
      if (r() < 0.02) flags.push('error');
      const tokens = tokensFor(Math.round(perTurn * (0.5 + r())), r);
      const latencyMs = kind === 'tool' ? Math.round(20 + r() ** 2 * 6_000) : null;
      const target = targets[Math.floor(r() * targets.length)];
      const summary = kind === 'prompt' ? `Task packet for ${run.taskId ?? 'session'} (${run.role})`
        : kind === 'text' ? 'Assistant reasoning and plan update'
        : `${tool} ${target}`;
      steps.push({
        seq: i + 1, at: iso(t), kind, tool, summary, resultChars, tokens, weighted: weighted(tokens),
        // A result is cached once and re-read by every later turn (analysis.md § 1).
        carriedWeighted: Math.round((resultChars / 4) * (1.25 + 0.1 * (run.turns - i - 1))),
        latencyMs, flags,
      });
      t += 4_000 + Math.floor(r() * 50_000);
    }
    return steps;
  }

  taskDetail(id: string): TaskDetail | null {
    const t = this.tasks.find(x => x.id === id);
    if (!t) return null;
    const runList = this.runs.filter(x => x.taskId === id);
    const roles = new Map<string, number>();
    for (const x of runList) roles.set(x.role, (roles.get(x.role) ?? 0) + 1);
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
      runList: runList.slice(0, 20),
      mirror: { syncedAt: iso(this.now - 2 * 60_000), readsByRole: [...roles.entries()].map(([role, reads]) => ({ role, reads })) },
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
    const withGaps = this.runs.filter(x => x.gaps > 0);
    const items: Gaps['items'] = [];
    for (let i = 0; i < 40; i++) {
      const s = shapes[Math.floor(r() ** 1.3 * shapes.length)];
      const run = withGaps[Math.floor(r() * withGaps.length)];
      items.push({
        id: `g-${i}`, at: iso(Date.parse(run.startedAt) + Math.floor(r() * HOUR)), tool: s.tool, shape: s.shape, fallback: s.fallback,
        reason: (['followup_read', 'followup_read', 'empty', 'candidate_manual'] as const)[Math.floor(r() * 4)],
        runId: run.id, stepSeq: 2 + Math.floor(r() * Math.max(1, run.turns - 2)),
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
    return { summary: [...groups.values()].sort((a, b) => b.count - a.count), items: inRange };
  }

  environment(): Environment {
    const k = (name: string, scope: Environment['keys'][number]['scope'], scopeRef: string | null, source: Environment['keys'][number]['source'], consumers: string[], usedH: number | null, updatedD: number) =>
      ({ name, scope, scopeRef, source, consumers, lastUsedAt: usedH === null ? null : iso(this.now - usedH * HOUR), updatedAt: iso(this.now - updatedD * DAY) });
    return {
      storeReady: false,
      keys: [
        k('YOUTRACK_TOKEN', 'global', null, 'env', ['youtrack MCP', 'codeloupe mirror'], 0.05, 9),
        k('TERRIO_API_KEY', 'repo', 'TerrioImporter', 'file', ['run/terrio.mjs api'], 20, 14),
        k('GITHUB_TOKEN', 'global', null, 'env', ['gh'], 3, 30),
        k('POSTGRES_PASSWORD', 'repo', 'TerrioImporter', 'file', ['docker compose'], 1, 60),
        k('MOBBIN_API_KEY', 'workspace', 'terrio', 'env', [], null, 2),
      ],
    };
  }

  settings(): DaemonSettings {
    return {
      port: 47391, home: 'C:/Users/dev/AppData/Local/codeloupe', configFile: 'C:/Users/dev/AppData/Local/codeloupe/config.json', defaultRoot: null,
      repos: [
        { id: REPOS.terrio.id, path: REPOS.terrio.main, baseRef: 'origin/master' },
        { id: REPOS.codeloupe.id, path: REPOS.codeloupe.main, baseRef: 'origin/main' },
      ],
      youtrack: [{ url: 'https://terrio.youtrack.cloud', projects: ['TER', 'CL'], tokenConfigured: true, pollSec: 180 }],
      budgets: { dailyWeighted: 25_000_000, runWeighted: RUN_BUDGET, daemonRssMb: 200, buildPeakRssMb: 600 },
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
