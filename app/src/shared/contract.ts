// Read-only UI API of the CodeLoupe daemon (docs/ui-spec.md § 9, YouTrack CL-39).
// This file is the app's copy of the contract: a change to the spec changes both.

export type Iso = string;
export type Range = '24h' | '7d' | '30d';
export const RANGES: readonly Range[] = ['24h', '7d', '30d'];

export interface Page<T> {
  items: T[];
  total: number;
  nextCursor: string | null;
}

export interface Tokens {
  input: number;
  cacheWrite5m: number;
  cacheWrite1h: number;
  cacheRead: number;
  output: number;
}

/** Weighted cost of a token breakdown (analysis.md, plan.md § 1). */
export function weighted(t: Tokens): number {
  return Math.round(t.input + 1.25 * t.cacheWrite5m + 2 * t.cacheWrite1h + 0.1 * t.cacheRead + 5 * t.output);
}

/** State of a worktree layer (plan.md § 5.3). */
export type LayerState = 'fresh' | 'stale' | 'building' | 'error' | 'none';
/** State of a repository's base index. */
export type RepoIndexState = 'ready' | 'building' | 'stale' | 'error' | 'none';
export interface WorktreeSummary {
  id: string;
  repoId: string;
  repoName: string;
  path: string;
  branch: string | null;
  head: string;
  isMain: boolean;
  taskId: string | null;
  ahead: number;
  behind: number;
  changedFiles: number;
  changedDecls: number;
  layer: LayerState;
  lastActivityAt: Iso | null;
  /** CodeLoupe queries with this worktree as root in the last 24 h (call telemetry, CL-24). */
  queries24h: number;
}

export interface TaskSummary {
  id: string;
  project: string;
  summary: string;
  state: string;
  priority: string | null;
  type: string | null;
  assignee: string | null;
  updatedAt: Iso;
  /** Issue reads served by the CodeLoupe mirror. */
  reads: number;
  worktreeIds: string[];
}

// § 9.3 GET /status — exists in the daemon today.
export interface Lane {
  running: string | null;
  waiting: string[];
}
export interface DaemonStatus {
  name: 'codeloupe';
  version: string;
  pid: number;
  port: number;
  home: string;
  uptimeSec: number;
  rssMb: number;
  heapMb: number;
  cpuSec: number;
  calls: { total: number; errors: number; busy: number };
  /** A build runs when heavy.running is set. */
  queue: { fast: Lane; heavy: Lane; [stat: string]: unknown };
  repos: { id: string; commonDir: string; defaultRef: string; baseCommit: string | null; lastBuild: LastBuild | null }[];
}
/** Last successful build; failures are recorded by CL-62. */
export interface LastBuild {
  at: Iso;
  ok: true;
  files: number;
  errors: number;
  ms: number;
  peakRssMb?: number;
}

// § 9.4a
export interface Nav {
  activeWorktrees: number;
  openTasks: number;
  newGaps: number;
  indexState: RepoIndexState;
}

// § 9.4
export interface Overview {
  range: Range;
  generatedAt: Iso;
  kpis: {
    weightedToday: number;
    weightedYesterdaySameTime: number;
    weightedRange: number;
    baselineRange: number;
    savedTokens: number;
    savedPct: number;
    /** Distinct MCP clients that called CodeLoupe in the last 15 minutes. */
    activeWindows: number;
    queriedWorktrees: number;
    codeloupeCalls: number;
    callP50Ms: number;
    gaps: number;
    newGaps: number;
  };
  budget: { dailyWeighted: number | null; usedToday: number };
  costSeries: { t: Iso; weighted: number; baseline: number }[];
  savingsByTool: { tool: string; calls: number; savedTokens: number }[];
  /** CodeLoupe's own call telemetry per tool (calls.jsonl, CL-24). */
  toolCalls: ToolCalls[];
}
export interface ToolCalls {
  tool: string;
  calls: number;
  p50Ms: number;
  p95Ms: number;
  avgResultChars: number;
  /** 0..1 */
  emptyShare: number;
  busy: number;
  errors: number;
}

// § 9.6
export type DeclChangeKind = 'added' | 'body' | 'signature' | 'removed';
export interface WorktreeDetail extends WorktreeSummary {
  baseRef: string;
  mergeBase: string;
  changes: { change: DeclChangeKind; kind: string; fqn: string; path: string; line: number | null; callers: number }[];
  callers: { fqn: string; path: string; line: number; calls: string; exact: boolean }[];
  tests: { path: string; fqn: string | null; reason: 'touched' | 'calls_changed' }[];
  task: TaskSummary | null;
  index: { layerFiles: number; parsedAt: Iso | null; errorFiles: string[] };
}

// § 9.10
export interface TaskDetail extends TaskSummary {
  url: string;
  description: string;
  fields: { name: string; value: string }[];
  criteria: { text: string; checked: boolean }[];
  links: { type: string; id: string; summary: string }[];
  activity: { at: Iso; author: string; kind: 'created' | 'comment' | 'field' | 'state'; text: string }[];
  worktrees: WorktreeSummary[];
  mirror: { syncedAt: Iso; lastReadAt: Iso | null };
}

// § 9.11
export interface IndexHealth {
  repos: {
    id: string;
    name: string;
    path: string;
    baseRef: string;
    baseCommit: string | null;
    state: RepoIndexState;
    builtAt: Iso | null;
    buildMs: number | null;
    dbBytes: number;
    files: number;
    decls: number;
    refs: number;
    errorFiles: number;
    layers: number;
  }[];
  builds: {
    id: string;
    repoId: string;
    kind: 'full' | 'sync' | 'layer';
    startedAt: Iso;
    durationMs: number | null;
    peakRssMb: number | null;
    files: number;
    status: 'running' | 'ok' | 'failed';
    error: string | null;
  }[];
  errorFiles: { repoId: string; path: string; errors: number; firstLine: number }[];
  budgets: { buildPeakRssMb: number; daemonRssMb: number };
}

// § 9.12
export type GapFallback = 'rg' | 'grep' | 'sed' | 'cat' | 'Read' | 'other';
export type GapReason = 'followup_read' | 'empty' | 'candidate_manual' | 'rollback';
export interface Gaps {
  summary: { tool: string; shape: string; fallback: string; count: number; lastAt: Iso }[];
  items: {
    id: string;
    at: Iso;
    tool: string;
    shape: string;
    fallback: GapFallback;
    reason: GapReason;
    /** Claude Code session and turn of the fallback, as text (the app does not track agent runs). */
    session: string;
    turn: number | null;
    target: string;
  }[];
}

// § 9.13 — metadata only, never values.
export interface Environment {
  keys: {
    name: string;
    scope: 'global' | 'repo' | 'workspace';
    scopeRef: string | null;
    source: 'store' | 'env' | 'file';
    consumers: string[];
    lastUsedAt: Iso | null;
    updatedAt: Iso;
  }[];
  storeReady: boolean;
}

// § 9.14
export interface DaemonSettings {
  port: number;
  home: string;
  configFile: string;
  defaultRoot: string | null;
  repos: { id: string; path: string; baseRef: string }[];
  youtrack: { url: string; projects: string[]; tokenConfigured: boolean; pollSec: number }[];
  budgets: { dailyWeighted: number | null; daemonRssMb: number; buildPeakRssMb: number };
}

// § 9.15
export type EventKind = 'budget_breach' | 'build_finished' | 'build_failed' | 'gap_new';
export interface DaemonEvent {
  seq: number;
  at: Iso;
  kind: EventKind;
  severity: 'info' | 'warning' | 'critical';
  title: string;
  body: string;
  ref: { screen: 'overview' | 'index' | 'gaps'; id: string | null };
}
export interface Events {
  /** Changes when the daemon starts numbering anew; the app re-baselines without notifying. */
  epoch: string;
  lastSeq: number;
  items: DaemonEvent[];
}

/** Resource name → response type, for the typed client in the renderer. */
export interface ResourceMap {
  nav: Nav;
  overview: Overview;
  worktrees: { items: WorktreeSummary[] };
  'worktrees/:id': WorktreeDetail;
  tasks: Page<TaskSummary> & { mirrorSyncedAt: Iso | null };
  'tasks/:id': TaskDetail;
  index: IndexHealth;
  gaps: Gaps;
  environment: Environment;
  settings: DaemonSettings;
  events: Events;
}
export type Resource = keyof ResourceMap;

export interface ApiError {
  error: { code: 'bad_request' | 'not_found' | 'busy' | 'unavailable'; message: string };
}
