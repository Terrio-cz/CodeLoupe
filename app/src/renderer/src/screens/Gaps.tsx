import { Fragment, useState } from 'react';
import type { GapKind } from '../../../shared/contract';
import { bridge, useApi } from '../api';
import { CountUp } from '../components/CountUp';
import { Icon } from '../components/Icon';
import { Banner, Card, Empty, ErrorState, KpiTile, Loading, Select } from '../components/Parts';
import { ago, dateTime, num, pct } from '../format';
import { byWeek, filterRows, KIND_LABELS, totalOf } from '../gapReport';
import { useRange } from '../hooks';

const REASONS: Record<string, string> = {
  followup_read: 'agent dočítal ručně',
  empty: 'prázdný výsledek',
  candidate_manual: 'candidate rozhodnut ručně',
  rollback: 'zápis vrácen',
};

export function Gaps() {
  const [range] = useRange();
  const [tool, setTool] = useState('');
  const [kind, setKind] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [outcome, setOutcome] = useState<{ ok: boolean; message: string } | null>(null);
  const { data, error, loading, reload } = useApi('gaps', undefined, { range });

  if (!data) return loading ? <><Loading variant="kpis" /><Card bodyClass=""><Loading variant="table" /></Card></> : <Card><ErrorState message={error?.message ?? 'Nelze načíst mezery.'} onRetry={reload} /></Card>;

  const report = data.report;
  const rows = report ? filterRows(report, { range, tool, kind }) : [];
  const tools = [...new Set([...(report?.rows ?? []).map(r => r.tool), ...data.summary.map(s => s.tool)])].sort();
  const total = totalOf(rows);
  const seenCalls = report?.calls ?? 0;

  const refresh = async () => {
    setRefreshing(true);
    setOutcome(null);
    try {
      setOutcome(await bridge().actions.gapsRefresh());
      reload();
    } finally {
      setRefreshing(false);
    }
  };

  return (
    <>
      <div className="filterbar">
        <Select label="Nástroj" value={tool} onChange={setTool} options={[{ value: '', label: 'Všechny nástroje' }, ...tools.map(t => ({ value: t, label: t }))]} />
        <Select label="Druh" value={kind} onChange={setKind} options={[{ value: '', label: 'Všechny druhy' }, ...(Object.keys(KIND_LABELS) as GapKind[]).map(k => ({ value: k, label: KIND_LABELS[k] }))]} />
        <span className="muted hint">Mezera = volání CodeLoupe, které nestačilo: agent sáhl po rg/sed/cat/Read na stejný cíl, nebo odpověď byla prázdná, busy či jen kandidáti.</span>
        <button className="btn" onClick={() => void refresh()} disabled={refreshing} aria-describedby="gaps-refresh-hint">
          <Icon name="refresh" size={14} className={refreshing ? 'spinning' : undefined} />{refreshing ? 'Počítám report…' : 'Přepočítat report'}
        </button>
      </div>
      {outcome && <Banner tone={outcome.ok ? 'info' : 'warning'}>{outcome.message}</Banner>}

      {!report ? (
        <Card title="Týdenní report">
          <Empty icon="gaps" action={<button className="btn primary" onClick={() => void refresh()} disabled={refreshing}>{refreshing ? 'Počítám report…' : 'Spočítat report'}</button>}>
            Report ještě nebyl spočítán. Projde transkripty Claude Code z posledních 30 dnů (<span className="mono">codeloupe metrics gaps</span>), trvá asi půl minuty.
          </Empty>
        </Card>
      ) : (
        <>
          <section aria-label="Souhrn reportu">
            <div className="kpis">
              <KpiTile label="Mezery v rozsahu" value={<CountUp value={total} format={num} />} ctx={`${num(rows.length)} řádků reportu`} />
              <KpiTile label="Volání CodeLoupe v reportu" value={<CountUp value={seenCalls} format={num} />} ctx={`${num(report.runs)} běhů agentů`} />
              <KpiTile label="Podíl mezer" value={seenCalls ? <CountUp value={(totalOf(report.rows) / seenCalls) * 100} format={v => pct(v, 1)} /> : '—'} ctx="mezery / volání, celý report" />
              <KpiTile label="Report" value={ago(report.generatedAt)} ctx={report.since ? `od ${report.since}` : undefined} />
            </div>
          </section>
          <Card title="Týdenní report podle nástroje a tvaru dotazu" bodyClass="">
            <div className="table-wrap">
              <table className="data" aria-label="Týdenní report mezer">
                <thead>
                  <tr>
                    <th scope="col">Nástroj</th><th scope="col">Tvar dotazu</th><th scope="col">Druh</th>
                    <th scope="col" className="num">Počet</th><th scope="col">Co se hledalo</th>
                  </tr>
                </thead>
                {rows.length === 0 ? (
                  <tbody><tr><td colSpan={5} className="empty-cell"><Empty icon="check">Žádné mezery v tomto rozsahu a filtru.</Empty></td></tr></tbody>
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
      <p id="gaps-refresh-hint" className="muted footnote">
        Report se počítá z transkriptů na tomto počítači jen na vyžádání; daemon ho pak servíruje ze souboru <span className="mono">gaps-report.json</span> ve svém home.
      </p>

      {data.summary.length > 0 && (
        <Card title="Výskyty podle nástroje a tvaru dotazu (ingest transkriptů)" bodyClass="">
          <div className="table-wrap">
            <table className="data" aria-label="Mezery podle nástroje">
              <thead>
                <tr>
                  <th scope="col">Nástroj</th><th scope="col">Tvar dotazu</th><th scope="col">Náhrada</th>
                  <th scope="col" className="num">Počet</th><th scope="col">Poslední</th><th scope="col"><span className="sr-only">Výskyty</span></th>
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
                            <Icon name="chevron" size={14} className={expanded ? 'open' : undefined} />{expanded ? 'Skrýt' : 'Výskyty'}
                          </button>
                        </td>
                      </tr>
                      {expanded && items.map(g => (
                        <tr key={g.id}>
                          <td />
                          <td className="t2">{dateTime(g.at)} · {REASONS[g.reason]}</td>
                          <td className="mono">{g.fallback}</td>
                          <td className="mono" colSpan={2}>{g.target}</td>
                          <td className="mono muted" title="Claude Code session a tah, kde agent sáhl po náhradě">{g.session}{g.turn !== null ? ` · tah ${g.turn}` : ''}</td>
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
