import { describe, expect, it } from 'vitest';
import { byWeek, filterRows, totalOf, weekStart } from '../src/renderer/src/gapReport';
import type { GapReport } from '../src/shared/contract';

const row = (week: string, tool: string, kind: GapReport['rows'][number]['kind'], count: number) => ({ week, tool, shape: `${tool}:name`, kind, count, examples: [] });
const report: GapReport = {
  generatedAt: '2026-10-08T10:00:00.000Z', since: '2026-09-10', runs: 5, calls: 100,
  rows: [row('2026-W37', 'find', 'fallback', 2), row('2026-W40', 'find', 'empty', 3), row('2026-W41', 'symbol', 'fallback', 4), row('unknown', 'find', 'busy', 1)],
};
const now = Date.parse('2026-10-08T12:00:00Z');

describe('gap report', () => {
  it('knows the Monday of an ISO week, also across a year boundary', () => {
    expect(new Date(weekStart('2026-W41')!).toISOString()).toBe('2026-10-05T00:00:00.000Z');
    expect(new Date(weekStart('2026-W01')!).toISOString()).toBe('2025-12-29T00:00:00.000Z');
    expect(weekStart('unknown')).toBeNull();
  });

  it('keeps the weeks that touch the range, and rows of an unknown week', () => {
    const weeks = (range: '24h' | '7d' | '30d') => filterRows(report, { range, tool: '', kind: '', now }).map(r => r.week);
    expect(weeks('24h')).toEqual(['2026-W41', 'unknown']);
    // W40 ended on 2026-10-04 at the end of the day: its last day is inside the 7 days before 2026-10-08.
    expect(weeks('7d')).toEqual(['2026-W40', '2026-W41', 'unknown']);
    expect(weeks('30d')).toEqual(['2026-W37', '2026-W40', '2026-W41', 'unknown']);
    // 2026-W36 ended on 2026-09-06, before the 30 days began.
    expect(filterRows({ ...report, rows: [row('2026-W36', 'find', 'fallback', 1)] }, { range: '30d', tool: '', kind: '', now })).toEqual([]);
  });

  it('filters by tool and kind and groups newest week first', () => {
    const rows = filterRows(report, { range: '30d', tool: 'find', kind: '', now });
    expect(rows.map(r => r.week)).toEqual(['2026-W37', '2026-W40', 'unknown']);
    expect(filterRows(report, { range: '30d', tool: '', kind: 'fallback', now }).map(r => r.tool)).toEqual(['find', 'symbol']);
    expect(byWeek(report.rows).map(([w]) => w)).toEqual(['unknown', '2026-W41', '2026-W40', '2026-W37']);
    expect(totalOf(report.rows)).toBe(10);
  });
});
