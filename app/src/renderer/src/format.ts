// Czech number and time formatting for dense tables.

const nf = new Intl.NumberFormat('cs-CZ');
const nf1 = new Intl.NumberFormat('cs-CZ', { maximumFractionDigits: 1 });
// One formatter per precision: each Intl.NumberFormat holds native ICU memory the GC does not see, and the
// animated KPI numbers format every frame.
const byDigits = new Map<number, Intl.NumberFormat>();
const nfd = (digits: number) => {
  let f = byDigits.get(digits);
  if (!f) byDigits.set(digits, (f = new Intl.NumberFormat('cs-CZ', { maximumFractionDigits: digits })));
  return f;
};

export function num(n: number | null | undefined): string {
  return n === null || n === undefined ? '—' : nf.format(n);
}

/** 1 240 000 → "1,24M", 392 000 → "392k". */
export function tokens(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—';
  const a = Math.abs(n);
  if (a >= 1e9) return `${nf1.format(n / 1e9)} mld.`;
  if (a >= 1e6) return `${nfd(2).format(n / 1e6)}M`;
  if (a >= 1e4) return `${nf.format(Math.round(n / 1e3))}k`;
  if (a >= 1e3) return `${nf1.format(n / 1e3)}k`;
  return nf.format(n);
}

export function pct(x: number, digits = 0): string {
  return `${nfd(digits).format(x)} %`;
}

export function bytes(b: number): string {
  if (b >= 1024 ** 3) return `${nf1.format(b / 1024 ** 3)} GB`;
  if (b >= 1024 ** 2) return `${nf.format(Math.round(b / 1024 ** 2))} MB`;
  if (b >= 1024) return `${nf.format(Math.round(b / 1024))} kB`;
  return `${b} B`;
}

export function ms(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—';
  if (n >= 60_000) return `${nf1.format(n / 60_000)} min`;
  if (n >= 1000) return `${nf1.format(n / 1000)} s`;
  return `${Math.round(n)} ms`;
}

export function ago(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '—';
  const s = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  if (s < 60) return 'právě teď';
  if (s < 3600) return `před ${Math.round(s / 60)} min`;
  if (s < 86_400) return `před ${Math.round(s / 3600)} h`;
  const d = Math.round(s / 86_400);
  return d === 1 ? 'včera' : `před ${d} dny`;
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

export function time(iso: string): string {
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
