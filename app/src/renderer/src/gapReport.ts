import type { GapKind, GapReport, GapReportRow, Range } from '../../shared/contract';

const DAY = 86_400_000;
const RANGE_DAYS: Record<Range, number> = { '24h': 1, '7d': 7, '30d': 30 };

export const KIND_LABELS: Record<GapKind, string> = {
  fallback: 'agent sáhl po rg/cat/Read',
  empty: 'prázdný výsledek',
  busy: 'busy',
  candidates: 'jen kandidáti',
};

/** First day (UTC) of an ISO week label like `2026-W41`; null for `unknown` or anything else. */
export function weekStart(label: string): number | null {
  const m = /^(\d{4})-W(\d{2})$/.exec(label);
  if (!m) return null;
  const jan4 = Date.UTC(Number(m[1]), 0, 4);
  const mondayOfWeek1 = jan4 - ((new Date(jan4).getUTCDay() + 6) % 7) * DAY;
  return mondayOfWeek1 + (Number(m[2]) - 1) * 7 * DAY;
}

export interface GapFilter {
  range: Range;
  tool: string;
  kind: string;
  now?: number;
}

/** Rows of weeks that end inside the range (a week that is partly in counts whole: the report has no finer grain), by tool and kind. */
export function filterRows(report: GapReport, f: GapFilter): GapReportRow[] {
  const from = (f.now ?? Date.now()) - RANGE_DAYS[f.range] * DAY;
  return report.rows.filter(r => {
    const start = weekStart(r.week);
    // A row of an unknown week stays visible rather than vanishing.
    const inRange = start === null || start + 7 * DAY > from;
    return inRange && (!f.tool || r.tool === f.tool) && (!f.kind || r.kind === f.kind);
  });
}

/** Rows per week, newest week first, the order of the rows inside a week kept. */
export function byWeek(rows: GapReportRow[]): [string, GapReportRow[]][] {
  const weeks = new Map<string, GapReportRow[]>();
  for (const r of rows) weeks.set(r.week, [...(weeks.get(r.week) ?? []), r]);
  return [...weeks.entries()].sort((a, b) => b[0].localeCompare(a[0]));
}

export const totalOf = (rows: GapReportRow[]) => rows.reduce((a, r) => a + r.count, 0);
