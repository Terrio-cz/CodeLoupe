// Mock of the runs the daemon ingests from Claude Code transcripts (src/shared/runs.ts), shaped after the measured baseline
// (plan.md § 1): cache reads carry most of the cost, a few runs go over the run budget.
import type { Page } from '../../shared/contract';
import type { Query } from '../../shared/request';
import type { IngestStatus, RunDetail, RunItem, RunPage, StepItem } from '../../shared/runs';
import { rng } from './mockData';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const RUN_BUDGET = 4_000_000;

const ROLES: { role: string; cost: number; turns: number; share: number }[] = [
  { role: 'main', cost: 1_100_000, turns: 55, share: 0.58 },
  { role: 'terrio-coder', cost: 920_000, turns: 55, share: 0.54 },
  { role: 'terrio-reviewer', cost: 1_000_000, turns: 52, share: 0.62 },
  { role: 'terrio-tester', cost: 390_000, turns: 14, share: 0.4 },
  { role: 'terrio-planner', cost: 260_000, turns: 24, share: 0.5 },
  { role: 'terrio-context', cost: 60_000, turns: 9, share: 0.35 },
  { role: 'codeloupe-coder', cost: 2_300_000, turns: 74, share: 0.66 },
];

const TITLES = [
  'Implement TER-{n} from the verified plan in the task worktree',
  'Review the whole flow of TER-{n} against the acceptance criteria',
  'Run the full test and the isolated stack for TER-{n}',
  'Plan TER-{n}: read the issue, map the flow, list the risks',
  'Find where the statistics anti-join is built',
  'Fix review findings of TER-{n} in the same worktree',
];

const TOOLS: [tool: string, category: string, summary: string][] = [
  ['Read', 'code_read', 'src/main/kotlin/OrderStatistics.kt'],
  ['Grep', 'code_search', 'pattern "anti.?join" in domain/'],
  ['Bash', 'build_test', 'gradlew :domain:test --tests *Statistics*'],
  ['Bash', 'git', 'git diff --stat origin/master'],
  ['Edit', 'code_edit', 'src/main/kotlin/RevisionRepository.kt'],
  ['mcp__codeloupe__find', 'codeloupe', 'find RevisionRepository*'],
  ['mcp__codeloupe__symbol', 'codeloupe', 'symbol OrderStatistics.handle'],
  ['Bash', 'shell_other', 'node run/terrio.mjs docker plan'],
  ['mcp__youtrack__yt_get_issue', 'tracker', 'issue TER-671'],
];

export class MockRuns {
  readonly runs: RunItem[];

  constructor(private readonly now: number) {
    const r = rng(77);
    this.runs = Array.from({ length: 90 }, (_, i): RunItem => {
      const shape = ROLES[Math.floor(r() ** 1.3 * ROLES.length)];
      const weighted = Math.round(shape.cost * (0.3 + r() * 1.9));
      const turns = Math.max(3, Math.round(shape.turns * (0.4 + r() * 1.3)));
      const started = now - Math.floor(r() ** 0.9 * 30 * DAY) - 5 * 60_000;
      const durationSec = Math.round(turns * (25 + r() * 40));
      const n = 600 + Math.floor(r() * 90);
      const calls = Math.max(2, Math.round(turns * (1.2 + r() * 1.6)));
      return {
        id: String(1000 + i), file: `agent-${(i * 7919 + 13).toString(16).padStart(8, '0')}`, session: `s-${(i * 104729 + 7).toString(36)}`,
        project: shape.role.startsWith('codeloupe') ? 'codeloupe' : 'terrio', kind: shape.role === 'main' ? 'session' : 'subagent', role: shape.role,
        ter: shape.role.startsWith('codeloupe') ? null : `TER-${n}`, model: i % 5 === 0 ? 'claude-opus' : 'claude-sonnet',
        title: TITLES[i % TITLES.length].replace('{n}', String(n)),
        startedAt: new Date(started).toISOString(), endedAt: new Date(started + durationSec * 1000).toISOString(), durationSec, turns,
        weighted, peakContext: Math.round(60_000 + r() * 140_000), toolResultShare: Math.min(0.9, shape.share + (r() - 0.5) * 0.2),
        toolCalls: calls, toolErrors: Math.floor(r() ** 3 * 6), overBudget: weighted > RUN_BUDGET,
      };
    }).sort((a, b) => b.startedAt.localeCompare(a.startedAt));
  }

  page(q: Query): RunPage {
    const from = this.now - ({ '24h': 1, '7d': 7, '30d': 30 }[String(q.range)] ?? 7) * DAY;
    const text = typeof q.q === 'string' ? q.q.toLowerCase() : '';
    const ter = typeof q.ter === 'string' ? q.ter.toLowerCase() : '';
    const key = ({ weighted: 'weighted', turns: 'turns', peak: 'peakContext', share: 'toolResultShare', duration: 'durationSec' } as const)[String(q.sort) as 'weighted'];
    const rows = this.runs
      .filter(r => Date.parse(r.startedAt) >= from && (!q.role || r.role === q.role) && (!ter || r.ter?.toLowerCase() === ter) && (!text || `${r.title} ${r.ter ?? ''} ${r.role} ${r.file}`.toLowerCase().includes(text)))
      .sort((a, b) => (key ? (b[key] as number) - (a[key] as number) : b.startedAt.localeCompare(a.startedAt)));
    const ingest: IngestStatus = { running: false, filesDone: 2_651, filesTotal: 2_651, at: new Date(this.now - 90_000).toISOString() };
    return { ...paged(rows, q, 50, 200), roles: ROLES.map(x => x.role), ingest };
  }

  detail(id: string): RunDetail | null {
    const run = this.runs.find(r => r.id === id);
    if (!run) return null;
    // Cache reads carry 55 % of the weighted cost, writes 20 %, output 8 %, the rest is fresh input.
    const w = run.weighted;
    const usage = { cacheRead: Math.round((w * 0.55) / 0.1), cacheWrite1h: Math.round((w * 0.2) / 2), cacheWrite5m: Math.round((w * 0.04) / 1.25), output: Math.round((w * 0.08) / 5), input: Math.round(w * 0.13) };
    const steps = this.steps(id, {});
    const byCategory = new Map<string, RunDetail['categories'][number]>();
    for (const s of steps?.items ?? []) {
      const c = byCategory.get(s.category) ?? { category: s.category, calls: 0, chars: 0, carried: 0, weighted: 0, errors: 0 };
      c.calls++; c.chars += s.chars; c.carried += s.carried; c.weighted += s.weighted; c.errors += s.error ? 1 : 0;
      byCategory.set(s.category, c);
    }
    return { run, usage, categories: [...byCategory.values()].sort((a, b) => b.carried - a.carried) };
  }

  steps(id: string, q: Query): Page<StepItem> | null {
    const run = this.runs.find(r => r.id === id);
    if (!run) return null;
    const r = rng(Number(id));
    const t0 = Date.parse(run.startedAt);
    const all = Array.from({ length: run.toolCalls }, (_, i): StepItem => {
      const [tool, category, summary] = TOOLS[Math.floor(r() * TOOLS.length)];
      const turn = 1 + Math.floor((i / run.toolCalls) * run.turns);
      const chars = Math.round(300 + r() ** 2 * 40_000);
      const carried = chars * Math.max(0, run.turns - turn);
      const error = r() < 0.04;
      const codeloupe = category === 'codeloupe';
      return {
        seq: i + 1, turn, at: new Date(t0 + ((i + 1) / run.toolCalls) * run.durationSec * 1000).toISOString(), tool, category, summary,
        chars, durationMs: Math.round(20 + r() ** 3 * 9_000), error, errorText: error ? 'exit code 1' : null, carried, weighted: Math.round(carried * 0.1 / 4),
        gap: codeloupe && r() < 0.25 ? (['fallback', 'empty', 'candidates'] as const)[Math.floor(r() * 3)] : null,
      };
    });
    const key = ({ weighted: 'weighted', chars: 'chars' } as const)[String(q.sort) as 'weighted'];
    const rows = key ? [...all].sort((a, b) => b[key] - a[key]) : all;
    return paged(rows, q, 200, 500);
  }
}

function paged<T>(rows: T[], q: Query, def: number, max: number): Page<T> {
  const limit = Math.min(max, Math.max(1, Number(q.limit ?? def) || def));
  const offset = Math.max(0, Number(q.cursor ?? 0) || 0);
  return { items: rows.slice(offset, offset + limit), total: rows.length, nextCursor: offset + limit < rows.length ? String(offset + limit) : null };
}
