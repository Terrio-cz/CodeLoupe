import type { RunDetail, RunSortKey, StepItem } from '../../shared/runs';

export const SORT_LABELS: Record<RunSortKey, string> = {
  start: 'Začátek', weighted: 'Cena', turns: 'Tahy', peak: 'Peak kontext', share: 'Podíl výsledků', duration: 'Délka',
};

/** Weighted cost by price class (docs/plan.md § 1): input 1, 5 min cache write 1.25, 1 h cache write 2, cache read 0.1, output 5. */
export function usageParts(u: RunDetail['usage']): { name: string; value: number; tokens: number; factor: number }[] {
  const part = (name: string, tokens: number, factor: number) => ({ name, tokens, factor, value: Math.round(tokens * factor) });
  return [
    part('cache read', u.cacheRead, 0.1), part('cache write 1 h', u.cacheWrite1h, 2), part('cache write 5 min', u.cacheWrite5m, 1.25),
    part('output', u.output, 5), part('input', u.input, 1),
  ].sort((a, b) => b.value - a.value);
}

export const GAP_LABELS: Record<NonNullable<StepItem['gap']>, string> = {
  fallback: 'agent sáhl po rg/cat/Read', empty: 'prázdný výsledek', candidates: 'jen kandidáti', busy: 'busy',
};

/** Characters of a tool result: `41 200` → `41 k zn.`. */
export function chars(n: number): string {
  return n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1).replace('.', ',')} M zn.` : n >= 1000 ? `${Math.round(n / 1000)} k zn.` : `${n} zn.`;
}

/** Seconds as `1 h 05 min`, `12 min`, `45 s`. */
export function span(sec: number): string {
  if (sec >= 3600) return `${Math.floor(sec / 3600)} h ${String(Math.floor((sec % 3600) / 60)).padStart(2, '0')} min`;
  if (sec >= 60) return `${Math.round(sec / 60)} min`;
  return `${sec} s`;
}
