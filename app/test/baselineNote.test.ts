import { describe, expect, it } from 'vitest';
import { baselineText } from '../src/renderer/src/components/BaselineNote';
import type { BaselineInfo } from '../src/shared/contract';

const base: BaselineInfo = { state: 'ok', label: 'baseline', since: '2026-09-23', until: '2026-10-06', runs: 2373, coveredShare: 0.86, message: null };

describe('baselineText', () => {
  it('tells where the baseline comes from and how much of the cost it covers', () => {
    const t = baselineText(base);
    expect(t).toContain('"baseline" (2,373 runs, 2026-09-23 to 2026-10-06)');
    expect(t).toContain('86% of the cost here has a baseline for its role');
    expect(t).toContain('An estimate, not a benchmark');
  });

  it('leaves the coverage out where the screen has none (the accounts list)', () => {
    expect(baselineText({ ...base, coveredShare: null })).not.toContain('has a baseline for its role');
  });

  it('says how to create a baseline when there is none, and why one cannot be used', () => {
    expect(baselineText({ ...base, state: 'none', label: null, runs: 0, coveredShare: null })).toContain('codeloupe metrics collect --baseline');
    expect(baselineText({ ...base, state: 'unreadable', message: 'not a metrics report' })).toContain('not a metrics report');
  });
});
