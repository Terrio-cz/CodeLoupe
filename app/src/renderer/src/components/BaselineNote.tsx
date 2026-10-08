import type { BaselineInfo } from '../../../shared/contract';
import { num, pct } from '../format';

/** What the savings are measured against, or why there are none: the screens never show a figure the baseline does not back. */
export function baselineText(b: BaselineInfo): string {
  if (b.state === 'none') return 'No baseline yet, so there are no savings to show. Store the cost of the period before CodeLoupe with "codeloupe metrics collect --baseline --since <day>"; the daemon reads it from its home.';
  if (b.state === 'unreadable') return `The baseline file in the daemon's home cannot be used: ${b.message ?? 'unknown error'}.`;
  const period = b.since && b.until ? `, ${b.since} to ${b.until}` : '';
  const covered = b.coveredShare === null ? '' : ` ${pct(b.coveredShare * 100)} of the cost here has a baseline for its role.`;
  return `Savings are measured against "${b.label ?? 'baseline'}" (${num(b.runs)} runs${period}): the average run of the same role, on finished runs only.${covered} An estimate, not a benchmark.`;
}

export function BaselineNote({ baseline }: { baseline: BaselineInfo }) {
  return <p className="footnote muted" role="note">{baselineText(baseline)}</p>;
}
