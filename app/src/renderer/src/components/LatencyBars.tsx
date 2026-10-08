import type { CallLatency } from '../../../shared/contract';
import type { CSSProperties } from 'react';
import { ms, num } from '../format';
import { Empty } from './Parts';

/**
 * p95 latency per tool over the last calls (`/status` latency): one bar per tool, a mark at the budget,
 * and a word instead of a colour when a tool is over it.
 */
export function LatencyBars({ latency, budgetMs }: { latency: CallLatency; budgetMs: number | null }) {
  const rows = Object.entries(latency.byTool).sort((a, b) => b[1].p95Ms - a[1].p95Ms);
  if (latency.window === 0) return <Empty icon="chart">No calls yet.</Empty>;
  const max = Math.max(1, ...rows.map(([, t]) => t.p95Ms), budgetMs ?? 0);
  const at = (v: number) => `${(v / max) * 100}%`;
  return (
    <div>
      <dl className="dl" style={{ marginBottom: 10 }}>
        <dt>Last</dt><dd>{num(latency.window)} {latency.window === 1 ? 'call' : 'calls'}</dd>
        <dt>p50 · p95</dt><dd className="num" style={{ textAlign: 'left' }}>{ms(latency.p50Ms)} · {ms(latency.p95Ms)}{budgetMs !== null && ` (budget ${ms(budgetMs)})`}</dd>
      </dl>
      <ul className="bar-list latency" aria-label="p95 latency by tool">
        {rows.map(([tool, t], n) => {
          const over = budgetMs !== null && t.p95Ms > budgetMs;
          return (
            <li key={tool}>
              <span className="mono">{tool}</span>
              <span className="bar-track" aria-hidden="true">
                <span style={{ width: at(t.p95Ms), '--i': n } as CSSProperties} />
                {budgetMs !== null && <i className="budget-mark" style={{ left: at(budgetMs) }} />}
              </span>
              <span className="num">{ms(t.p95Ms)}</span>
              <span className="num muted">{over ? <span className="badge critical"><strong>over budget</strong></span> : `p50 ${ms(t.p50Ms)}`}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
