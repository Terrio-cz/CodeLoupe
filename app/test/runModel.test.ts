import { describe, expect, it } from 'vitest';
import { MockRuns } from '../src/main/api/mockRuns';
import { chars, span, usageParts } from '../src/renderer/src/runModel';

const mock = new MockRuns(Date.parse('2026-10-08T12:00:00Z'));

describe('runs', () => {
  it('the parts of the cost add up to what the run weighs, biggest first', () => {
    const run = mock.runs[0];
    const parts = usageParts(mock.detail(run.id)!.usage);
    const sum = parts.reduce((a, p) => a + p.value, 0);
    expect(Math.abs(sum - run.weighted) / run.weighted).toBeLessThan(0.01);
    expect(parts.map(p => p.value)).toEqual([...parts.map(p => p.value)].sort((a, b) => b - a));
    expect(parts[0].name).toBe('cache read');
  });

  it('pages, filters and sorts like the daemon: descending, by role and text, 50 a page', () => {
    const first = mock.page({ range: '30d', sort: 'weighted' });
    expect(first.items).toHaveLength(50);
    expect(first.items.map(r => r.weighted)).toEqual([...first.items.map(r => r.weighted)].sort((a, b) => b - a));
    const second = mock.page({ range: '30d', sort: 'weighted', cursor: first.nextCursor! });
    expect(second.items[0].id).not.toBe(first.items[0].id);
    expect(second.items[0].weighted).toBeLessThanOrEqual(first.items[49].weighted);
    expect(mock.page({ range: '30d', role: 'terrio-tester' }).items.every(r => r.role === 'terrio-tester')).toBe(true);
    expect(mock.page({ range: '24h' }).total).toBeLessThan(mock.page({ range: '30d' }).total);
    expect(first.roles).toContain('main');
  });

  it('lists the steps of a run in order, or by what keeping their results cost', () => {
    const run = mock.runs[1];
    const inOrder = mock.steps(run.id, {})!;
    expect(inOrder.items.map(s => s.seq)).toEqual(inOrder.items.map((_, i) => i + 1));
    expect(inOrder.total).toBe(run.toolCalls);
    const byCost = mock.steps(run.id, { sort: 'weighted' })!.items.map(s => s.weighted);
    expect(byCost).toEqual([...byCost].sort((a, b) => b - a));
    expect(mock.steps('nope', {})).toBeNull();
  });

  it('writes lengths and sizes for a dense table', () => {
    expect(span(45)).toBe('45 s');
    expect(span(720)).toBe('12 min');
    expect(span(3900)).toBe('1 h 05 min');
    expect(chars(950)).toBe('950 chars');
    expect(chars(41_200)).toBe('41k chars');
    expect(chars(2_500_000)).toBe('2.5M chars');
  });
});
