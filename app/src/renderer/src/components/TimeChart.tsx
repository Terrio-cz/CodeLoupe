import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { time } from '../format';
import { niceStep } from './Charts';
import { Icon } from './Icon';
import { Empty } from './Parts';

export interface TimePoint {
  /** ISO time of the reading. */
  t: string;
  v: number;
}

interface Props {
  /** Heading used for the accessible name and the table view. */
  label: string;
  points: TimePoint[];
  format: (v: number) => string;
  /** A budget: dashed reference line, and a warning in the summary when a reading is above it. */
  limit?: { value: number; label: string };
  /** A pause longer than this between two readings breaks the line (the daemon takes none while idle). */
  gapMs?: number;
}

const H = 170, PAD = { l: 48, r: 10, t: 10, b: 24 };

/** One series over time on one y-axis: crosshair tooltip, optional budget line, a table view for exact values. */
export function TimeChart({ label, points, format, limit, gapMs = 5 * 60_000 }: Props) {
  const [hover, setHover] = useState<number | null>(null);
  const [asTable, setAsTable] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const svg = useRef<SVGSVGElement>(null);
  const [W, setW] = useState(420);
  const fill = `time-fill-${useId().replace(/:/g, '')}`;
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(260, Math.round(e.contentRect.width))));
    ro.observe(el);
    return () => ro.disconnect();
  }, [asTable]);

  const geo = useMemo(() => {
    const times = points.map(p => Date.parse(p.t));
    const t0 = times[0] ?? 0;
    const span = Math.max(1, (times[times.length - 1] ?? 0) - t0);
    const max = Math.max(1, ...points.map(p => p.v), limit?.value ?? 0);
    const step = niceStep(max / 4);
    const top = Math.ceil((max * 1.05) / step) * step;
    const x = (i: number) => PAD.l + ((times[i] - t0) / span) * (W - PAD.l - PAD.r);
    const y = (v: number) => PAD.t + (1 - v / top) * (H - PAD.t - PAD.b);
    // The line breaks over pauses; the area under it is one closed shape per unbroken run.
    let d = '';
    let area = '';
    let runStart = 0;
    let run = '';
    const closeRun = (end: number) => {
      if (end > runStart) area += `${run}L${x(end).toFixed(1)},${y(0)}L${x(runStart).toFixed(1)},${y(0)}Z`;
    };
    points.forEach((p, i) => {
      const connected = i > 0 && times[i] - times[i - 1] <= gapMs;
      if (i > 0 && !connected) closeRun(i - 1);
      const seg = `${connected ? 'L' : 'M'}${x(i).toFixed(1)},${y(p.v).toFixed(1)}`;
      if (!connected) { runStart = i; run = ''; }
      run += seg;
      d += seg;
    });
    if (points.length) closeRun(points.length - 1);
    const ticks = Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step);
    const xTicks = points.length < 2 ? [] : [0, 1, 2, 3, 4].map(k => Math.round((k / 4) * (points.length - 1)));
    return { x, y, ticks, xTicks, line: d, area, top };
  }, [points, W, limit?.value, gapMs]);

  const last = points[points.length - 1];
  const peak = points.reduce((m, p) => Math.max(m, p.v), 0);
  const over = limit ? points.some(p => p.v > limit.value) : false;
  const summary = last
    ? `${label}: poslední ${format(last.v)}, maximum ${format(peak)}${limit ? `, ${limit.label} ${format(limit.value)}${over ? ', překročeno' : ''}` : ''}.`
    : `${label}: žádná data.`;

  const onMove = (e: React.MouseEvent<SVGSVGElement>) => {
    const r = svg.current!.getBoundingClientRect();
    const px = ((e.clientX - r.left) / r.width) * W;
    let best = 0;
    for (let i = 1; i < points.length; i++) if (Math.abs(geo.x(i) - px) < Math.abs(geo.x(best) - px)) best = i;
    setHover(points.length ? best : null);
  };
  const h = hover !== null ? points[hover] : null;

  if (points.length === 0) return <Empty icon="chart">Daemon zatím nezaznamenal žádné odečty (měří se jen při používání).</Empty>;

  return (
    <div>
      <div className="legend" style={{ marginBottom: 6 }}>
        <span><span className="sw" aria-hidden="true" />{label} <strong>{format(last.v)}</strong></span>
        {limit && <span><span className="sw base" aria-hidden="true" />{limit.label} {format(limit.value)}{over && <> · <span className="badge critical"><strong>překročeno</strong></span></>}</span>}
        <button className="btn ghost" aria-pressed={asTable} onClick={() => setAsTable(v => !v)}><Icon name={asTable ? 'chart' : 'table'} size={14} />{asTable ? 'Graf' : 'Tabulka'}</button>
      </div>
      {asTable ? (
        <div className="table-wrap" style={{ maxHeight: H }}>
          <table className="data" aria-label={label}>
            <thead><tr><th scope="col">Čas</th><th scope="col" className="num">{label}</th></tr></thead>
            <tbody>{[...points].reverse().map(p => <tr key={p.t}><td>{time(p.t)}</td><td className="num">{format(p.v)}</td></tr>)}</tbody>
          </table>
        </div>
      ) : (
        <div className="chart" ref={box}>
          <svg ref={svg} viewBox={`0 0 ${W} ${H}`} style={{ height: H }} role="img" aria-label={summary} onMouseMove={onMove} onMouseLeave={() => setHover(null)}>
            <defs>
              <linearGradient id={fill} x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="var(--series-1)" stopOpacity={0.22} />
                <stop offset="100%" stopColor="var(--series-1)" stopOpacity={0} />
              </linearGradient>
            </defs>
            {geo.ticks.map(t => (
              <g key={t}>
                <line x1={PAD.l} x2={W - PAD.r} y1={geo.y(t)} y2={geo.y(t)} stroke={t === 0 ? 'var(--axis)' : 'var(--grid)'} strokeWidth={1} strokeDasharray={t === 0 ? undefined : '2 4'} />
                <text x={PAD.l - 6} y={geo.y(t) + 4} textAnchor="end" fontSize="11" fill="var(--text-muted)">{format(t)}</text>
              </g>
            ))}
            {geo.xTicks.map(i => (
              <text key={i} x={geo.x(i)} y={H - 6} textAnchor="middle" fontSize="11" fill="var(--text-muted)">{time(points[i].t)}</text>
            ))}
            <path className="reveal" d={geo.area} fill={`url(#${fill})`} />
            {limit && <line className="reveal" x1={PAD.l} x2={W - PAD.r} y1={geo.y(limit.value)} y2={geo.y(limit.value)} stroke="var(--series-baseline)" strokeWidth={1.5} strokeDasharray="5 4" />}
            <path className="draw" pathLength={1} d={geo.line} fill="none" stroke="var(--series-1)" strokeWidth={1.75} strokeLinejoin="round" strokeLinecap="round" />
            {hover !== null && h && (
              <g>
                <line className="crosshair" x1={geo.x(hover)} x2={geo.x(hover)} y1={PAD.t} y2={H - PAD.b} />
                <circle className="hover-dot" cx={geo.x(hover)} cy={geo.y(h.v)} r={4} fill="var(--series-1)" stroke="var(--surface)" strokeWidth={2} />
              </g>
            )}
          </svg>
          {hover !== null && h && (
            <div className="tooltip" style={{ left: `${(geo.x(hover) / W) * 100}%`, top: `${(geo.y(h.v) / H) * 100}%` }}>
              <strong>{time(h.t)}</strong><br />{format(h.v)}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
