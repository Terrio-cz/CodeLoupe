/** Release versions: `v1.2.3` or `v1.2.3-rc.1` (the release workflow refuses anything else). */
export interface ParsedVersion {
  numbers: [number, number, number];
  /** Pre-release identifiers; empty for a final release. */
  pre: (string | number)[];
}

const VERSION = /^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$/;

export function parseVersion(text: string): ParsedVersion | null {
  const m = VERSION.exec(text);
  if (!m) return null;
  return {
    numbers: [Number(m[1]), Number(m[2]), Number(m[3])],
    pre: m[4] ? m[4].split('.').map(p => (/^\d+$/.test(p) ? Number(p) : p)) : [],
  };
}

/** Semantic-versioning order: a pre-release sorts before its release, numeric identifiers before text ones. */
export function compareVersions(a: ParsedVersion, b: ParsedVersion): number {
  for (let i = 0; i < 3; i++) {
    if (a.numbers[i] !== b.numbers[i]) return a.numbers[i] < b.numbers[i] ? -1 : 1;
  }
  if (a.pre.length === 0 || b.pre.length === 0) return a.pre.length === b.pre.length ? 0 : a.pre.length === 0 ? 1 : -1;
  for (let i = 0; i < Math.max(a.pre.length, b.pre.length); i++) {
    const x = a.pre[i];
    const y = b.pre[i];
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    if (x === y) continue;
    if (typeof x === 'number' && typeof y === 'number') return x < y ? -1 : 1;
    if (typeof x === 'number') return -1;
    if (typeof y === 'number') return 1;
    return x < y ? -1 : 1;
  }
  return 0;
}
