import { describe, expect, it } from 'vitest';
import { cpuPoints, rssPoints } from '../src/renderer/src/history';
import type { ResourceSample } from '../src/shared/contract';

const at = (min: number) => new Date(Date.parse('2026-10-08T10:00:00Z') + min * 60_000).toISOString();
const sample = (min: number, rssMb: number | null, cpuSec: number): ResourceSample => ({ t: at(min), rssMb, heapMb: 40, cpuSec });

describe('history charts', () => {
  it('leaves out readings the OS could not give', () => {
    expect(rssPoints([sample(0, 90, 1), sample(1, null, 2), sample(2, 95, 3)]).map(p => p.v)).toEqual([90, 95]);
  });

  it('turns growing CPU seconds into a share of one core', () => {
    // 3 s of CPU in 60 s = 5 %.
    expect(cpuPoints([sample(0, 90, 10), sample(1, 90, 13)])).toEqual([{ t: at(1), v: 5 }]);
  });

  it('gives no load across an idle stretch or after a daemon restart', () => {
    const idle = cpuPoints([sample(0, 90, 10), sample(30, 90, 40), sample(31, 90, 41)]);
    expect(idle.map(p => p.t)).toEqual([at(31)]);
    expect(cpuPoints([sample(0, 90, 500), sample(1, 80, 2)])).toEqual([]);
  });
});
