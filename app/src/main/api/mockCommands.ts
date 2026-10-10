// Mock of the Commands tab (src/shared/commands.ts): the same grouping and sums as the daemon's report, over the mock runs and their steps.
import type { CommandCategory, CommandGroup, CommandKind, CommandReport, CommandRunRef, CommandSlice, CommandSortKey, CommandsView } from '../../shared/commands';
import type { Query } from '../../shared/request';
import type { RunItem, StepItem } from '../../shared/runs';
import type { MockRuns } from './mockRuns';

const DAY = 86_400_000;
const TOP_RUNS = 3;
const SLICE_ROWS = 5;

// What the daemon's normalizer makes of each call the mock runs contain, by tool and summary.
const LABELS: Record<string, [label: string, kind: CommandKind]> = {
  'Read|src/main/kotlin/OrderTotals.kt': ['Read', 'file'],
  'Grep|pattern "anti.?join" in domain/': ['Grep', 'search'],
  'Bash|gradlew :domain:test --tests *OrderTotals*': ['gradlew :domain:test --tests <path>', 'shell'],
  'Bash|git diff --stat origin/master': ['git diff --stat <path>', 'shell'],
  'Edit|src/main/kotlin/CouponRepository.kt': ['Edit', 'file'],
  'mcp__codeloupe__find|find CouponRepository*': ['codeloupe/find', 'mcp'],
  'mcp__codeloupe__symbol|symbol OrderTotals.handle': ['codeloupe/symbol', 'mcp'],
  'Bash|node scripts/dev.mjs docker plan': ['node dev.mjs docker plan', 'shell'],
  'mcp__youtrack__yt_get_issue|issue SHOP-671': ['youtrack/yt_get_issue', 'mcp'],
};

interface Totals {
  kind: CommandKind;
  calls: number;
  runs: Set<string>;
  chars: number;
  args: number;
  repeats: number;
  repeatChars: number;
  errors: number;
  weighted: number;
  perRun: Map<string, { calls: number; weighted: number }>;
}

const tokens = (chars: number) => Math.round(chars / 4);
const tenth = (x: number) => Math.round(x * 10) / 10;

/** ISO week of a day, `2026-W41`: the week belongs to the year of its Thursday. */
function week(ms: number): string {
  const d = new Date(ms);
  const thursday = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() - ((d.getUTCDay() + 6) % 7) + 3);
  const year = new Date(thursday).getUTCFullYear();
  const n = 1 + Math.floor((thursday - Date.UTC(year, 0, 1)) / DAY / 7);
  return `${year}-W${String(n).padStart(2, '0')}`;
}

export class MockCommands {
  constructor(private readonly runs: MockRuns, private readonly now: number) {}

  view(q: Query): CommandsView {
    const days = ({ '24h': 1, '7d': 7, '30d': 30 } as Record<string, number>)[String(q.since)] ?? 7;
    const role = typeof q.role === 'string' && q.role ? q.role : null;
    const top = Math.min(100, Math.max(1, Number(q.top ?? 20) || 20));
    const sort = (['calls', 'tokens'] as const).find(s => s === q.sort) ?? 'cost';
    const from = this.now - days * DAY;
    const picked = this.runs.runs.filter(r => Date.parse(r.startedAt) >= from && (!role || r.role === role));

    const all = new Map<string, Totals>();
    const byRole = new Map<string, { runs: RunItem[]; groups: Map<string, Totals> }>();
    const byWeek = new Map<string, { runs: RunItem[]; groups: Map<string, Totals> }>();
    const categories = new Map<string, CommandCategory>();
    let argChars = 0;
    for (const run of picked) {
      const slices = [all, this.slice(byRole, run.role, run), this.slice(byWeek, week(Date.parse(run.startedAt)), run)];
      const seen = new Set<string>();
      for (const s of this.runs.steps(run.id, { limit: 500 })?.items ?? []) {
        const hit = LABELS[`${s.tool}|${s.summary}`];
        const label = hit ? hit[0] : s.tool;
        const kind: CommandKind = hit ? hit[1] : 'other';
        const sent = 40 + s.summary.length;
        const repeat = seen.has(`${s.tool}|${s.summary}`);
        seen.add(`${s.tool}|${s.summary}`);
        for (const groups of slices) add(groups, label, kind, run.id, s, sent, repeat);
        argChars += sent;
        const c = categories.get(s.category) ?? { category: s.category, calls: 0, resultTokens: 0, errors: 0, weighted: 0, costPct: 0 };
        c.calls++; c.resultTokens += s.chars; c.errors += s.error ? 1 : 0; c.weighted += s.weighted;
        categories.set(s.category, c);
      }
    }

    const cost = picked.reduce((a, r) => a + r.weighted, 0);
    const pctOf = (part: number, whole: number) => tenth((100 * part) / Math.max(whole, 1));
    const groupsOf = (map: Map<string, Totals>, withRuns: boolean, n: number): CommandGroup[] =>
      [...map.entries()].map(([command, t]) => this.group(command, t, cost, picked, withRuns)).sort(ORDER[sort]).slice(0, n);
    const slice = (map: Map<string, { runs: RunItem[]; groups: Map<string, Totals> }>): CommandSlice[] => [...map.entries()].map(([key, s]) => {
      const sliceCost = s.runs.reduce((a, r) => a + r.weighted, 0);
      const groups = [...s.groups.entries()].map(([command, t]) => this.group(command, t, sliceCost, s.runs, false)).sort(ORDER[sort]);
      return { key, runs: s.runs.length, calls: groups.reduce((a, g) => a + g.calls, 0), resultTokens: groups.reduce((a, g) => a + g.resultTokens, 0), cost: sliceCost, costPct: pctOf(groups.reduce((a, g) => a + g.weighted, 0), sliceCost), groups: groups.slice(0, SLICE_ROWS) };
    });
    const weighted = [...categories.values()].reduce((a, c) => a + c.weighted, 0);
    const report: CommandReport = {
      generatedAt: new Date(this.now).toISOString(), since: new Date(from).toISOString(), until: null, role,
      runs: picked.length, calls: [...categories.values()].reduce((a, c) => a + c.calls, 0), resultTokens: tokens([...categories.values()].reduce((a, c) => a + c.resultTokens, 0)),
      argTokens: tokens(argChars), cost, weighted, costPct: pctOf(weighted, cost), money: Math.round(cost * 0.0000032 * 100) / 100, currency: 'USD', unpricedRuns: 0,
      groupCount: all.size, groups: groupsOf(all, true, top),
      categories: [...categories.values()].map(c => ({ ...c, resultTokens: tokens(c.resultTokens), costPct: pctOf(c.weighted, cost) })).sort((a, b) => b.weighted - a.weighted),
      roles: slice(byRole).sort((a, b) => b.cost - a.cost), weeks: slice(byWeek).sort((a, b) => a.key.localeCompare(b.key)),
    };
    return { report, roles: this.runs.page({}).roles, ingest: { running: false, filesDone: 2_651, filesTotal: 2_651, at: new Date(this.now - 90_000).toISOString() } };
  }

  private slice(map: Map<string, { runs: RunItem[]; groups: Map<string, Totals> }>, key: string, run: RunItem): Map<string, Totals> {
    const s = map.get(key) ?? { runs: [], groups: new Map<string, Totals>() };
    s.runs.push(run);
    map.set(key, s);
    return s.groups;
  }

  private group(command: string, t: Totals, cost: number, runs: RunItem[], withRuns: boolean): CommandGroup {
    const topRuns: CommandRunRef[] = !withRuns ? [] : [...t.perRun.entries()].sort((a, b) => b[1].weighted - a[1].weighted).slice(0, TOP_RUNS).flatMap(([id, v]) => {
      const run = runs.find(r => r.id === id);
      return run ? [{ id, role: run.role, ter: run.ter, title: run.title, calls: v.calls, weighted: v.weighted }] : [];
    });
    return {
      command, kind: t.kind, calls: t.calls, runs: t.runs.size, resultTokens: tokens(t.chars), argTokens: tokens(t.args), repeats: t.repeats, repeatTokens: tokens(t.repeatChars),
      errors: t.errors, weighted: t.weighted, costPct: tenth((100 * t.weighted) / Math.max(cost, 1)), money: Math.round(t.weighted * 0.0000032 * 100) / 100, topRuns,
    };
  }
}

const ORDER: Record<CommandSortKey, (a: CommandGroup, b: CommandGroup) => number> = {
  cost: (a, b) => b.weighted - a.weighted || b.resultTokens - a.resultTokens || a.command.localeCompare(b.command),
  calls: (a, b) => b.calls - a.calls || b.weighted - a.weighted || a.command.localeCompare(b.command),
  tokens: (a, b) => b.resultTokens - a.resultTokens || b.weighted - a.weighted || a.command.localeCompare(b.command),
};

function add(groups: Map<string, Totals>, label: string, kind: CommandKind, runId: string, s: StepItem, sent: number, repeat: boolean): void {
  const t = groups.get(label) ?? { kind, calls: 0, runs: new Set<string>(), chars: 0, args: 0, repeats: 0, repeatChars: 0, errors: 0, weighted: 0, perRun: new Map() };
  t.calls++; t.runs.add(runId); t.chars += s.chars; t.args += sent; t.weighted += s.weighted; t.errors += s.error ? 1 : 0;
  if (repeat) { t.repeats++; t.repeatChars += s.chars; }
  const mine = t.perRun.get(runId) ?? { calls: 0, weighted: 0 };
  mine.calls++; mine.weighted += s.weighted;
  t.perRun.set(runId, mine);
  groups.set(label, t);
}
