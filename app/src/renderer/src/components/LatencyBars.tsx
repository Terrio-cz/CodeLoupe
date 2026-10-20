import type { CallLatency } from '../../../shared/contract';
import type { CSSProperties } from 'react';
import { ms, num } from '../format';
import { Empty } from './Parts';

// Tools run from a few ms to minutes: a log axis from 10 ms keeps a 40 ms lookup and a 56 s plan both readable.
const FLOOR_MS = 10;
const TICKS = [10, 100, 1_000, 10_000, 60_000];

/** How many tools are over their own p95 budget, in words; [fallbackMs] for a daemon that sends no per-tool budget. */
export function overBudget(latency: CallLatency, fallbackMs: number): string {
  const tools = Object.values(latency.byTool);
  const over = tools.filter(t => t.p95Ms > (t.budgetMs ?? fallbackMs)).length;
  return over === 0 ? `all ${num(tools.length)} tools within budget` : `${num(over)} of ${num(tools.length)} tools over budget`;
}

/**
 * p95 latency per tool over the last calls (`/status` latency): one bar per tool on a log axis, a mark at the tool's own
 * budget (`budgetMs` of the tool, else [budgetMs] for a daemon that sends none), and a word instead of a colour when a tool is over it.
 */
export function LatencyBars({ latency, budgetMs }: { latency: CallLatency; budgetMs: number | null }) {
  const rows = Object.entries(latency.byTool).sort((a, b) => b[1].p95Ms - a[1].p95Ms);
  if (latency.window === 0) return <Empty icon="chart">No calls yet.</Empty>;
  const budgetOf = (t: CallLatency['byTool'][string]) => t.budgetMs ?? budgetMs;
  const top = Math.max(FLOOR_MS * 10, ...rows.map(([, t]) => Math.max(t.p95Ms, budgetOf(t) ?? 0)));
  const span = Math.log10(top / FLOOR_MS);
  const pos = (v: number) => Math.min(100, Math.max(0, (Math.log10(Math.max(v, FLOOR_MS) / FLOOR_MS) / span) * 100));
  const at = (v: number) => `${pos(v)}%`;
  // An axis label is centred on its tick, except at the ends where it would leave the card.
  const shift = (v: number) => `translateX(${pos(v) < 5 ? 0 : pos(v) > 90 ? -100 : -50}%)`;
  const ticks = TICKS.filter(t => t <= top);
  return (
    <div>
      <dl className="dl" style={{ marginBottom: 10 }}>
        <dt>Last</dt><dd>{num(latency.window)} {latency.window === 1 ? 'call' : 'calls'}</dd>
        <dt>p50 · p95</dt><dd className="num" style={{ textAlign: 'left' }}>{ms(latency.p50Ms)} · {ms(latency.p95Ms)}</dd>
      </dl>
      <ul className="bar-list latency" aria-label="p95 latency by tool">
        {rows.map(([tool, t], n) => {
          const budget = budgetOf(t);
          const over = budget !== null && t.p95Ms > budget;
          return (
            <li key={tool}>
              <span className="mono">{tool}</span>
              <span className="num">
                {over && <span className="badge critical"><strong>over budget</strong></span>} <strong>{ms(t.p95Ms)}</strong>
              </span>
              <span className="bar-track" aria-hidden="true">
                <span style={{ width: at(t.p95Ms), '--i': n } as CSSProperties} />
                {budget !== null && <i className="budget-mark" style={{ left: at(budget) }} />}
              </span>
              <span className="note muted">
                {num(t.calls)}× · p50 {ms(t.p50Ms)}{budget !== null && ` · budget ${ms(budget)}`}
              </span>
            </li>
          );
        })}
      </ul>
      <div className="latency-axis muted" aria-hidden="true">
        {ticks.map(t => <span key={t} style={{ left: at(t), transform: shift(t) }}>{ms(t)}</span>)}
      </div>
    </div>
  );
}
