import { Fragment, useState } from 'react';
import type { GapKind } from '../../../shared/contract';
import { useApi } from '../api';
import { CountUp } from '../components/CountUp';
import { Icon } from '../components/Icon';
import { Card, Empty, ErrorState, KpiTile, Loading, Select } from '../components/Parts';
import { ago, dateTime, num, pct } from '../format';
import { byWeek, filterRows, KIND_LABELS, totalOf } from '../gapReport';
import { useRange } from '../hooks';

const plural = (n: number, one: string, many: string) => `${num(n)} ${n === 1 ? one : many}`;

const REASONS: Record<string, string> = {
  followup_read: 'agent read on manually',
  empty: 'empty result',
  candidate_manual: 'candidate resolved manually',
  rollback: 'write rolled back',
};

export function Gaps() {
  const [range] = useRange();
  const [tool, setTool] = useState('');
  const [kind, setKind] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const { data, error, loading, reload } = useApi('gaps', undefined, { range });

  if (!data) return loading ? <><Loading variant="kpis" /><Card bodyClass=""><Loading variant="table" /></Card></> : <Card><ErrorState message={error?.message ?? 'Could not load gaps.'} onRetry={reload} /></Card>;

  const report = data.report;
  const rows = report ? filterRows(report, { range, tool, kind }) : [];
  const tools = [...new Set([...(report?.rows ?? []).map(r => r.tool), ...data.summary.map(s => s.tool)])].sort();
  const total = totalOf(rows);
  const seenCalls = report?.calls ?? 0;

  return (
    <>
      <div className="filterbar">
        <Select label="Tool" value={tool} onChange={setTool} options={[{ value: '', label: 'All tools' }, ...tools.map(t => ({ value: t, label: t }))]} />
        <Select label="Kind" value={kind} onChange={setKind} options={[{ value: '', label: 'All kinds' }, ...(Object.keys(KIND_LABELS) as GapKind[]).map(k => ({ value: k, label: KIND_LABELS[k] }))]} />
        <span className="muted hint">Gap = a CodeLoupe call that was not enough: the agent reached for rg/sed/cat/Read on the same target, or the answer was empty, busy or candidates only.</span>
      </div>

      {!report ? (
        <Card title="Weekly report">
          <Empty icon="gaps" action={<button className="btn" onClick={reload}>Retry</button>}>
            The daemon has not found any run in the Claude Code transcripts yet, so there is nothing to build the report from. It reads them on the first query (<span className="mono">~/.claude/projects</span>).
          </Empty>
        </Card>
      ) : (
        <>
          <section aria-label="Report summary">
            <div className="kpis">
              <KpiTile label="Gaps in range" value={<CountUp value={total} format={num} />} ctx={plural(rows.length, 'report row', 'report rows')} />
              <KpiTile label="CodeLoupe calls in report" value={<CountUp value={seenCalls} format={num} />} ctx={plural(report.runs, 'agent run', 'agent runs')} />
              <KpiTile label="Gap share" value={seenCalls ? <CountUp value={(totalOf(report.rows) / seenCalls) * 100} format={v => pct(v, 1)} /> : '—'} ctx="gaps / calls, whole report" />
              <KpiTile label="Report" value={ago(report.generatedAt)} ctx={report.since ? `since ${report.since}` : undefined} />
            </div>
          </section>
          <Card title="Weekly report by tool and query shape" bodyClass="">
            <div className="table-wrap">
              <table className="data" aria-label="Weekly gap report">
                <thead>
                  <tr>
                    <th scope="col">Tool</th><th scope="col">Query shape</th><th scope="col">Kind</th>
                    <th scope="col" className="num">Count</th><th scope="col">What was searched</th>
                  </tr>
                </thead>
                {rows.length === 0 ? (
                  <tbody><tr><td colSpan={5} className="empty-cell"><Empty icon="check">No gaps in this range and filter.</Empty></td></tr></tbody>
                ) : byWeek(rows).map(([week, inWeek]) => (
                  <tbody key={week}>
                    <tr className="group">
                      <th scope="rowgroup" colSpan={3}>{week}</th>
                      <th scope="rowgroup" className="num">{num(totalOf(inWeek))}</th>
                      <td />
                    </tr>
                    {inWeek.map(r => (
                      <tr key={`${r.tool}|${r.shape}|${r.kind}`}>
                        <td className="mono">{r.tool}</td><td className="mono">{r.shape}</td><td>{KIND_LABELS[r.kind]}</td>
                        <td className="num">{num(r.count)}</td><td className="mono ellipsis">{r.examples.join(', ') || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                ))}
              </table>
            </div>
          </Card>
        </>
      )}
      <p className="muted footnote">
        The report is the same as <span className="mono">codeloupe metrics gaps</span> for the last 30 days, only from transcripts the daemon has already read; the range above selects the weeks.
      </p>

      {data.summary.length > 0 && (
        <Card title="Occurrences by tool and query shape (transcript ingest)" bodyClass="">
          <div className="table-wrap">
            <table className="data" aria-label="Gaps by tool">
              <thead>
                <tr>
                  <th scope="col">Tool</th><th scope="col">Query shape</th><th scope="col">Fallback</th>
                  <th scope="col" className="num">Count</th><th scope="col">Last</th><th scope="col"><span className="sr-only">Occurrences</span></th>
                </tr>
              </thead>
              <tbody>
                {data.summary.filter(s => !tool || s.tool === tool).map(s => {
                  const key = `${s.tool}|${s.shape}`;
                  const expanded = open === key;
                  const items = data.items.filter(g => g.tool === s.tool && g.shape === s.shape);
                  return (
                    <Fragment key={key}>
                      <tr>
                        <td className="mono">{s.tool}</td><td>{s.shape}</td><td className="mono">{s.fallback}</td>
                        <td className="num">{num(s.count)}</td><td>{ago(s.lastAt)}</td>
                        <td>
                          <button className="btn ghost" aria-expanded={expanded} onClick={() => setOpen(expanded ? null : key)}>
                            <Icon name="chevron" size={14} className={expanded ? 'open' : undefined} />{expanded ? 'Hide' : 'Occurrences'}
                          </button>
                        </td>
                      </tr>
                      {expanded && items.map(g => (
                        <tr key={g.id}>
                          <td />
                          <td className="t2">{dateTime(g.at)} · {REASONS[g.reason]}</td>
                          <td className="mono">{g.fallback}</td>
                          <td className="mono" colSpan={2}>{g.target}</td>
                          <td className="mono muted" title="Claude Code session and turn where the agent fell back">{g.session}{g.turn !== null ? ` · turn ${g.turn}` : ''}</td>
                        </tr>
                      ))}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </>
  );
}
