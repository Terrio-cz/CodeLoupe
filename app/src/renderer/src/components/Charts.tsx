import { useEffect, useId, useMemo, useRef, useState, type CSSProperties } from 'react';
import { tokens } from '../format';
import { Icon } from './Icon';

export interface CostPoint {
  t: string;
  weighted: number;
  baseline: number;
}

const H = 220, PAD = { l: 52, r: 12, t: 12, b: 26 };

/**
 * Cost over time: actual (area + 2px line) against the baseline (dashed). One y-axis, crosshair tooltip,
 * a table view for screen readers and for exact values.
 */
export function CostChart({ points, hourly }: { points: CostPoint[]; hourly: boolean }) {
  const [hover, setHover] = useState<number | null>(null);
  const [asTable, setAsTable] = useState(false);
  const fill = `cost-fill-${useId().replace(/:/g, '')}`;
  const svg = useRef<SVGSVGElement>(null);
  const box = useRef<HTMLDivElement>(null);
  // The viewBox follows the real width so text and strokes are never stretched.
  const [W, setW] = useState(720);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(320, Math.round(e.contentRect.width))));
    ro.observe(el);
    return () => ro.disconnect();
  }, [asTable]);

  const geo = useMemo(() => {
    const max = Math.max(1, ...points.map(p => Math.max(p.weighted, p.baseline)));
    const step = niceStep(max / 4);
    const top = Math.ceil(max / step) * step;
    const x = (i: number) => PAD.l + (points.length <= 1 ? 0 : (i / (points.length - 1)) * (W - PAD.l - PAD.r));
    const y = (v: number) => PAD.t + (1 - v / top) * (H - PAD.t - PAD.b);
    const line = (key: 'weighted' | 'baseline') => points.map((p, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(p[key]).toFixed(1)}`).join('');
    const area = `${line('weighted')}L${x(points.length - 1).toFixed(1)},${y(0)}L${x(0).toFixed(1)},${y(0)}Z`;
    const ticks = Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step);
    return { x, y, top, ticks, actual: line('weighted'), baseline: line('baseline'), area };
  }, [points, W]);

  const label = (iso: string) => {
    const d = new Date(iso);
    return hourly ? `${String(d.getHours()).padStart(2, '0')}:00` : `${d.getDate()}. ${d.getMonth() + 1}.`;
  };
  const sumActual = points.reduce((a, p) => a + p.weighted, 0);
  const sumBase = points.reduce((a, p) => a + p.baseline, 0);

  const onMove = (e: React.MouseEvent<SVGSVGElement>) => {
    const r = svg.current!.getBoundingClientRect();
    const px = ((e.clientX - r.left) / r.width) * W;
    const i = Math.round(((px - PAD.l) / (W - PAD.l - PAD.r)) * (points.length - 1));
    setHover(i >= 0 && i < points.length ? i : null);
  };

  const every = Math.max(1, Math.ceil(points.length / 8));
  const h = hover !== null ? points[hover] : null;

  return (
    <div>
      <div className="legend" style={{ marginBottom: 8 }}>
        <span><span className="sw" aria-hidden="true" />Skutečnost <strong>{tokens(sumActual)}</strong></span>
        <span><span className="sw base" aria-hidden="true" />Baseline <strong>{tokens(sumBase)}</strong></span>
        <span style={{ flex: 1 }} />
        <button className="btn ghost" aria-pressed={asTable} onClick={() => setAsTable(v => !v)}><Icon name={asTable ? 'chart' : 'table'} size={14} />{asTable ? 'Graf' : 'Tabulka'}</button>
      </div>
      {asTable ? (
        <div className="table-wrap" style={{ maxHeight: 220 }}>
          <table className="data" aria-label="Cena v čase">
            <thead><tr><th scope="col">Období</th><th scope="col" className="num">Skutečnost</th><th scope="col" className="num">Baseline</th></tr></thead>
            <tbody>{points.map(p => <tr key={p.t}><td>{label(p.t)}</td><td className="num">{tokens(p.weighted)}</td><td className="num">{tokens(p.baseline)}</td></tr>)}</tbody>
          </table>
        </div>
      ) : (
        <div className="chart" ref={box}>
          <svg
            ref={svg}
            viewBox={`0 0 ${W} ${H}`}
            role="img"
            aria-label={`Cena v čase: skutečnost ${tokens(sumActual)} vážených tokenů, baseline ${tokens(sumBase)}.`}
            onMouseMove={onMove}
            onMouseLeave={() => setHover(null)}
          >
            <defs>
              <linearGradient id={fill} x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="var(--series-1)" stopOpacity={0.28} />
                <stop offset="100%" stopColor="var(--series-1)" stopOpacity={0} />
              </linearGradient>
            </defs>
            {geo.ticks.map(t => (
              <g key={t}>
                <line x1={PAD.l} x2={W - PAD.r} y1={geo.y(t)} y2={geo.y(t)} stroke={t === 0 ? 'var(--axis)' : 'var(--grid)'} strokeWidth={1} strokeDasharray={t === 0 ? undefined : '2 4'} />
                <text x={PAD.l - 8} y={geo.y(t) + 4} textAnchor="end" fontSize="11" fill="var(--text-muted)">{tokens(t)}</text>
              </g>
            ))}
            {points.map((p, i) => (i % every === 0 ? (
              <text key={p.t} x={geo.x(i)} y={H - 6} textAnchor="middle" fontSize="11" fill="var(--text-muted)">{label(p.t)}</text>
            ) : null))}
            <path className="reveal" d={geo.area} fill={`url(#${fill})`} />
            <path className="reveal" d={geo.baseline} fill="none" stroke="var(--series-baseline)" strokeWidth={1.5} strokeDasharray="5 4" />
            <path className="draw" pathLength={1} d={geo.actual} fill="none" stroke="var(--series-1)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
            {hover !== null && h && (
              <g>
                <line className="crosshair" x1={geo.x(hover)} x2={geo.x(hover)} y1={PAD.t} y2={H - PAD.b} />
                <circle cx={geo.x(hover)} cy={geo.y(h.baseline)} r={3} fill="var(--surface)" stroke="var(--series-baseline)" strokeWidth={1.5} />
                <circle className="hover-dot" cx={geo.x(hover)} cy={geo.y(h.weighted)} r={4.5} fill="var(--series-1)" stroke="var(--surface)" strokeWidth={2} />
              </g>
            )}
          </svg>
          {hover !== null && h && (
            <div className="tooltip" style={{ left: `${(geo.x(hover) / W) * 100}%`, top: `${(geo.y(Math.max(h.weighted, h.baseline)) / H) * 100}%` }}>
              <strong>{label(h.t)}</strong>
              <div className="row"><span className="sw" aria-hidden="true" />Skutečnost {tokens(h.weighted)}</div>
              <div className="row"><span className="sw base" aria-hidden="true" />Baseline {tokens(h.baseline)}</div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export function niceStep(raw: number): number {
  const p = 10 ** Math.floor(Math.log10(raw || 1));
  const f = raw / p;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * p;
}

/** Horizontal bars of one series, sorted, value printed next to the bar. */
export function BarList({ label, items }: { label: string; items: { name: string; value: number; note?: string }[] }) {
  const max = Math.max(1, ...items.map(i => i.value));
  return (
    <ul className="bar-list" aria-label={label}>
      {items.map((i, n) => (
        <li key={i.name}>
          <span className="mono">{i.name}</span>
          <span className="bar-track" aria-hidden="true"><span style={{ width: `${(i.value / max) * 100}%`, '--i': n } as CSSProperties} /></span>
          <span className="num">{tokens(i.value)}</span>
          <span className="num muted">{i.note}</span>
        </li>
      ))}
    </ul>
  );
}
