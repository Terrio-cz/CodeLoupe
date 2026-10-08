import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { LatencyBars } from '../src/renderer/src/components/LatencyBars';
import { TimeChart } from '../src/renderer/src/components/TimeChart';
import type { CallLatency } from '../src/shared/contract';

const latency: CallLatency = {
  window: 400, p50Ms: 9, p95Ms: 1400, p95Chars: 5000, emptyRate: 0.03, busyRate: 0.02,
  byTool: { find: { calls: 200, p50Ms: 7, p95Ms: 41 }, changes: { calls: 20, p50Ms: 120, p95Ms: 1400 } },
};

describe('LatencyBars', () => {
  it('names a tool over the p95 budget in words, slowest first', () => {
    const html = renderToStaticMarkup(<LatencyBars latency={latency} budgetMs={1000} />);
    expect(html.indexOf('changes')).toBeLessThan(html.indexOf('find'));
    expect(html).toContain('nad budget');
    expect(html).toContain('budget 1');
  });

  it('says so when no call has been made', () => {
    expect(renderToStaticMarkup(<LatencyBars latency={{ ...latency, window: 0, byTool: {} }} budgetMs={1000} />)).toContain('Zatím žádná volání');
  });
});

describe('TimeChart', () => {
  const points = [{ t: '2026-10-08T10:00:00Z', v: 120 }, { t: '2026-10-08T10:01:00Z', v: 260 }];

  it('summarises the series and the exceeded budget for assistive technology', () => {
    const html = renderToStaticMarkup(<TimeChart label="RSS" points={points} format={v => `${v} MB`} limit={{ value: 250, label: 'budget' }} />);
    expect(html).toContain('role="img"');
    expect(html).toContain('RSS: poslední 260 MB, maximum 260 MB, budget 250 MB, překročeno.');
    expect(html).toContain('překročeno</strong>');
  });

  it('says why there is nothing to draw', () => {
    expect(renderToStaticMarkup(<TimeChart label="RSS" points={[]} format={String} />)).toContain('zatím nezaznamenal');
  });
});
