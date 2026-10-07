import { Fragment, useState } from 'react';
import { useApi } from '../api';
import { Card, ErrorState, Loading, Select } from '../components/Parts';
import { ago, dateTime, num } from '../format';
import { useRange } from '../hooks';
import { go } from '../router';

const REASONS: Record<string, string> = {
  followup_read: 'agent dočítal ručně',
  empty: 'prázdný výsledek',
  candidate_manual: 'candidate rozhodnut ručně',
  rollback: 'zápis vrácen',
};

export function Gaps() {
  const [range] = useRange();
  const [tool, setTool] = useState('');
  const [reason, setReason] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const { data, error, loading, reload } = useApi('gaps', undefined, { range, tool, reason });
  const tools = [...new Set((data?.summary ?? []).map(s => s.tool))];

  return (
    <>
      <div className="filterbar">
        <Select label="Nástroj" value={tool} onChange={setTool} options={[{ value: '', label: 'Všechny nástroje' }, ...tools.map(t => ({ value: t, label: t }))]} />
        <Select label="Důvod" value={reason} onChange={setReason} options={[{ value: '', label: 'Všechny důvody' }, ...Object.entries(REASONS).map(([v, l]) => ({ value: v, label: l }))]} />
        <span className="muted">Mezera = agent po volání CodeLoupe sáhl po rg/sed/cat/Read na stejný cíl.</span>
      </div>
      <Card title="Podle nástroje a tvaru dotazu" bodyClass="">
        {!data ? (loading ? <Loading /> : <ErrorState message={error?.message ?? 'Nelze načíst mezery.'} onRetry={reload} />) : (
          <div className="table-wrap">
            <table className="data" aria-label="Mezery podle nástroje">
              <thead>
                <tr>
                  <th scope="col">Nástroj</th><th scope="col">Tvar dotazu</th><th scope="col">Náhrada</th>
                  <th scope="col" className="num">Počet</th><th scope="col">Poslední</th><th scope="col"><span className="sr-only">Výskyty</span></th>
                </tr>
              </thead>
              <tbody>
                {data.summary.length === 0 && <tr><td colSpan={6} className="muted">Žádné mezery v tomto rozsahu.</td></tr>}
                {data.summary.map(s => {
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
                            {expanded ? '▾ Skrýt' : '▸ Výskyty'}
                          </button>
                        </td>
                      </tr>
                      {expanded && items.map(g => (
                        <tr key={g.id}>
                          <td />
                          <td className="t2">{dateTime(g.at)} · {REASONS[g.reason]}</td>
                          <td className="mono">{g.fallback}</td>
                          <td className="mono" colSpan={2}>{g.target}</td>
                          <td><button className="link" onClick={() => go('runs', g.runId, g.stepSeq ? { step: g.stepSeq } : undefined)}>krok {g.stepSeq ?? '—'} →</button></td>
                        </tr>
                      ))}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
