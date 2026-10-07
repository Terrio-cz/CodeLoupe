import type { ReactNode } from 'react';
import type { Range } from '../../../shared/contract';

/** Collapsible drawer/detail section with a count in its heading. */
export function Section({ title, count, children, open = true }: { title: string; count?: number; children: ReactNode; open?: boolean }) {
  return (
    <details className="section" open={open}>
      <summary>
        {title}
        {count !== undefined && <span className="count">({count})</span>}
      </summary>
      {children}
    </details>
  );
}

export function KpiTile({ label, value, ctx, children }: { label: string; value: ReactNode; ctx?: ReactNode; children?: ReactNode }) {
  return (
    <div className="kpi">
      <span className="label">{label}</span>
      <span className="value num" style={{ textAlign: 'left' }}>{value}</span>
      {ctx && <span className="ctx">{ctx}</span>}
      {children}
    </div>
  );
}

/** ▲/▼ with a word, never colour alone. */
export function Delta({ now, before, unit = '' }: { now: number; before: number; unit?: string }) {
  if (!before) return <span>bez srovnání</span>;
  const d = Math.round(((now - before) / before) * 100);
  if (d === 0) return <span>stejně{unit}</span>;
  return <span>{d > 0 ? '▲' : '▼'} {Math.abs(d)} % {d > 0 ? 'víc' : 'méně'}{unit}</span>;
}

export function Card({ title, actions, children, bodyClass = 'card-body' }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; bodyClass?: string }) {
  return (
    <section className="card">
      {title !== undefined && (
        <div className="card-head">
          <h2>{title}</h2>
          <span className="spacer" />
          {actions}
        </div>
      )}
      <div className={bodyClass}>{children}</div>
    </section>
  );
}

export function Segmented<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: { value: T; label: string }[]; onChange(v: T): void }) {
  return (
    <div className="seg" role="group" aria-label={label}>
      {options.map(o => (
        <button key={o.value} aria-pressed={o.value === value} onClick={() => onChange(o.value)}>{o.label}</button>
      ))}
    </div>
  );
}

export const RANGE_OPTIONS: { value: Range; label: string }[] = [
  { value: '24h', label: '24 h' },
  { value: '7d', label: '7 d' },
  { value: '30d', label: '30 d' },
];
export const rangeLabel = (r: Range) => RANGE_OPTIONS.find(o => o.value === r)!.label;

export function Select({ label, value, options, onChange }: { label: string; value: string; options: { value: string; label: string }[]; onChange(v: string): void }) {
  return (
    <label className="check">
      <span className="sr-only">{label}</span>
      <select className="select" value={value} onChange={e => onChange(e.target.value)} aria-label={label}>
        {options.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </label>
  );
}

export function Search({ label, value, onChange }: { label: string; value: string; onChange(v: string): void }) {
  return (
    <input
      className="input"
      type="search"
      placeholder={label}
      aria-label={label}
      value={value}
      onChange={e => onChange(e.target.value)}
      data-search
    />
  );
}

export function Loading() {
  return (
    <div className="card-body" aria-busy="true" aria-label="Načítám">
      {[70, 90, 60, 80].map((w, i) => <div key={i} className="skeleton" style={{ width: `${w}%`, margin: '10px 0' }} />)}
    </div>
  );
}

export function ErrorState({ message, onRetry, action }: { message: string; onRetry?: () => void; action?: ReactNode }) {
  return (
    <div className="state" role="alert">
      <div>{message}</div>
      <div style={{ display: 'flex', gap: 8 }}>
        {onRetry && <button className="btn" onClick={onRetry}>Zkusit znovu</button>}
        {action}
      </div>
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="state">{children}</div>;
}
