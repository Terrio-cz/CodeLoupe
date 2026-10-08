import type { ResourceSample } from '../../shared/contract';
import type { TimePoint } from './components/TimeChart';

/** RSS readings in MB; a reading the OS could not give (null) is left out. */
export function rssPoints(samples: ResourceSample[]): TimePoint[] {
  return samples.flatMap(s => (s.rssMb === null ? [] : [{ t: s.t, v: s.rssMb }]));
}

/**
 * CPU use between two readings as a share of one core: the CPU seconds only grow, so the load is their difference over the
 * wall time. Readings further apart than `maxGapMs` are idle stretches and give no load.
 */
export function cpuPoints(samples: ResourceSample[], maxGapMs = 5 * 60_000): TimePoint[] {
  const out: TimePoint[] = [];
  for (let i = 1; i < samples.length; i++) {
    const dt = Date.parse(samples[i].t) - Date.parse(samples[i - 1].t);
    const dcpu = samples[i].cpuSec - samples[i - 1].cpuSec;
    if (dt <= 0 || dt > maxGapMs || dcpu < 0) continue;
    out.push({ t: samples[i].t, v: Math.round((dcpu / (dt / 1000)) * 1000) / 10 });
  }
  return out;
}
