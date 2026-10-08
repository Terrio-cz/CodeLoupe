import { useState, type ReactNode } from 'react';
import type { Range } from '../../../shared/contract';
import { Icon, type IconName } from './Icon';

/** Collapsible drawer/detail section with a count in its heading. Only a section the user toggles animates its body. */
export function Section({ title, count, children, open = true }: { title: string; count?: number; children: ReactNode; open?: boolean }) {
  const [toggled, setToggled] = useState(false);
  return (
    <details className={toggled ? 'section toggled' : 'section'} open={open}>
      <summary onClick={() => setToggled(true)}>
        <Icon name="chevron" size={14} className="chev" />
        {title}
        {count !== undefined && <span className="count">{count}</span>}
      </summary>
      {children}
    </details>
  );
}

export function KpiTile({ label, value, ctx, children }: { label: string; value: ReactNode; ctx?: ReactNode; children?: ReactNode }) {
  return (
    <div className="kpi">
      <span className="label">{label}</span>
      <span className="value">{value}</span>
      {ctx && <span className="ctx">{ctx}</span>}
      {children}
    </div>
  );
}

/** ▲/▼ with a word, never colour alone. */
export function Delta({ now, before, unit = '' }: { now: number; before: number; unit?: string }) {
  if (!before) return <span>no comparison</span>;
  const d = Math.round(((now - before) / before) * 100);
  if (d === 0) return <span>same{unit}</span>;
  return <span className="delta"><span className="arrow" aria-hidden="true">{d > 0 ? '▲' : '▼'}</span> {Math.abs(d)}% {d > 0 ? 'more' : 'less'}{unit}</span>;
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
  { value: '24h', label: '24h' },
  { value: '7d', label: '7d' },
  { value: '30d', label: '30d' },
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
    <span className="search">
      <Icon name="search" size={14} />
      <input
        className="input"
        type="search"
        placeholder={label}
        aria-label={label}
        value={value}
        onChange={e => onChange(e.target.value)}
        data-search
      />
    </span>
  );
}

/** Skeleton in the shape of what is coming: lines, table rows or KPI tiles. `aria-busy` is what the screenshot run waits for. */
export function Loading({ variant = 'lines' }: { variant?: 'lines' | 'table' | 'kpis' }) {
  if (variant === 'kpis') {
    return (
      <div className="skeleton-kpis" aria-busy="true" aria-label="Loading">
        {[0, 1, 2, 3].map(i => (
          <div key={i} className="kpi">
            <div className="skeleton" style={{ width: '45%', height: 10 }} />
            <div className="skeleton tall" />
            <div className="skeleton" style={{ width: '65%', height: 10 }} />
          </div>
        ))}
      </div>
    );
  }
  if (variant === 'table') {
    return (
      <div className="skeleton-rows" aria-busy="true" aria-label="Loading">
        {[36, 52, 44, 60, 40, 48].map((w, i) => (
          <div key={i} className="row">
            <div className="skeleton" style={{ width: `${w / 2}%` }} />
            <div className="skeleton" style={{ width: `${w}%` }} />
            <div className="skeleton" style={{ width: '12%', marginLeft: 'auto' }} />
          </div>
        ))}
      </div>
    );
  }
  return (
    <div className="card-body" aria-busy="true" aria-label="Loading">
      {[70, 90, 60, 80].map((w, i) => <div key={i} className="skeleton" style={{ width: `${w}%`, margin: '10px 0' }} />)}
    </div>
  );
}

/** A heading in the app's words, then the message as it came (a daemon error can be terse or English), then what to do. */
export function ErrorState({ message, onRetry, action, title = 'Could not load data' }: { message: string; onRetry?: () => void; action?: ReactNode; title?: string }) {
  return (
    <div className="state error" role="alert">
      <span className="state-icon"><Icon name="alert" size={18} /></span>
      <div className="state-title">{title}</div>
      <div className="state-text muted">{message}</div>
      <div className="actions">
        {onRetry && <button className="btn" onClick={onRetry}><Icon name="refresh" size={14} />Retry</button>}
        {action}
      </div>
    </div>
  );
}

export function Empty({ children, icon = 'inbox', action }: { children: ReactNode; icon?: IconName; action?: ReactNode }) {
  return (
    <div className="state">
      <span className="state-icon"><Icon name={icon} size={18} /></span>
      <div className="state-text">{children}</div>
      {action}
    </div>
  );
}

/** Warning or note above the content: an icon, then the text. */
export function Banner({ tone = 'warning', role = 'status', children }: { tone?: 'warning' | 'info'; role?: 'status' | 'note' | 'alert'; children: ReactNode }) {
  return (
    <div className={`banner ${tone}`} role={role}>
      <Icon name={tone === 'info' ? 'info' : 'alert'} />
      <div className="banner-body">{children}</div>
    </div>
  );
}

export function Toast({ children }: { children: ReactNode }) {
  return <div className="toast" role="status"><Icon name="check" />{children}</div>;
}
