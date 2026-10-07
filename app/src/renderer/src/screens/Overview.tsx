import type { ToolCalls } from '../../../shared/contract';
import { bridge, useApi } from '../api';
import { BarList, CostChart } from '../components/Charts';
import { DataTable, type Column } from '../components/DataTable';
import { Card, Delta, ErrorState, KpiTile, Loading, rangeLabel } from '../components/Parts';
import { PhaseBadge } from '../components/StatusBadge';
import { ago, ms, num, pct, time, tokens } from '../format';
import { useDaemon, useRange } from '../hooks';

const callColumns: Column<ToolCalls>[] = [
  { key: 'tool', header: 'Nástroj', render: t => <span className="mono">{t.tool}</span> },
  { key: 'calls', header: 'Volání', render: t => num(t.calls), numeric: true },
  { key: 'p50', header: 'p50', render: t => ms(t.p50Ms), numeric: true },
  { key: 'p95', header: 'p95', render: t => ms(t.p95Ms), numeric: true },
  { key: 'size', header: 'Ø výsledek', render: t => `${tokens(t.avgResultChars)} zn.`, numeric: true },
  { key: 'empty', header: 'Prázdné', render: t => pct(t.emptyShare * 100), numeric: true },
  { key: 'busy', header: 'Busy', render: t => num(t.busy), numeric: true },
  { key: 'errors', header: 'Chyby', render: t => num(t.errors), numeric: true },
];

export function Overview() {
  const [range] = useRange();
  const { data, error, loading, reload } = useApi('overview', undefined, { range });
  const daemon = useDaemon();

  if (!data) return loading ? <Card><Loading /></Card> : <Card><ErrorState message={error?.message ?? 'Nelze načíst přehled.'} onRetry={reload} /></Card>;
  const k = data.kpis;
  const budget = data.budget.dailyWeighted;
  const used = budget ? Math.min(100, (data.budget.usedToday / budget) * 100) : 0;
  const r = rangeLabel(range);
  const st = daemon?.status;

  return (
    <>
      <section className="card" aria-label="Klíčová čísla">
        <div className="kpis">
          <KpiTile label="Cena dnes" value={tokens(k.weightedToday)} ctx={<Delta now={k.weightedToday} before={k.weightedYesterdaySameTime} unit=" než včera" />}>
            {budget && (
              <>
                <div className={`meter${data.budget.usedToday > budget ? ' over' : ''}`} role="meter" aria-valuemin={0} aria-valuemax={budget} aria-valuenow={data.budget.usedToday} aria-label="Čerpání denního rozpočtu">
                  <span style={{ width: `${used}%` }} />
                </div>
                <span className="ctx">{pct((data.budget.usedToday / budget) * 100)} rozpočtu {tokens(budget)}</span>
              </>
            )}
          </KpiTile>
          <KpiTile label={`Cena ${r}`} value={tokens(k.weightedRange)} ctx={`baseline ${tokens(k.baselineRange)}`} />
          <KpiTile label={`Úspora ${r}`} value={pct(k.savedPct, 1)} ctx={`${tokens(k.savedTokens)} tokenů`} />
          <KpiTile label="Aktivní okna" value={num(k.activeWindows)} ctx={`${num(k.queriedWorktrees)} worktree dotazováno`} />
          <KpiTile label={`Volání CodeLoupe ${r}`} value={num(k.codeloupeCalls)} ctx={`${ms(k.callP50Ms)} p50`} />
          <KpiTile label={`Mezery ${r}`} value={num(k.gaps)} ctx={`${num(k.newGaps)} nových za 24 h`} />
        </div>
      </section>

      <div className="grid-2">
        <Card title="Cena v čase (vážené tokeny)">
          <CostChart points={data.costSeries} hourly={range === '24h'} />
        </Card>
        <Card title="Daemon">
          {daemon ? (
            <dl className="dl">
              <dt>Stav</dt><dd><PhaseBadge phase={daemon.phase} /></dd>
              <dt>Verze</dt><dd>{st ? `v${st.version} · pid ${st.pid}` : '—'}</dd>
              <dt>Port</dt><dd className="mono">127.0.0.1:{daemon.port}</dd>
              <dt>RSS</dt><dd>{st ? `${st.rssMb} MB (heap ${st.heapMb} MB)` : '—'}</dd>
              <dt>CPU</dt><dd>{st ? `${st.cpuSec} s · běží ${ms(st.uptimeSec * 1000)}` : '—'}</dd>
              <dt>Fronta</dt><dd>{st ? `fast ${st.queue.fast.waiting.length} · heavy ${st.queue.heavy.waiting.length}` : '—'}</dd>
              <dt>Build</dt><dd>{st?.queue.heavy.running ?? (st?.repos.find(x => x.lastBuild)?.lastBuild ? `poslední ${time(st.repos.find(x => x.lastBuild)!.lastBuild!.at)}, ${ms(st.repos.find(x => x.lastBuild)!.lastBuild!.ms)}` : 'žádný')}</dd>
              <dt>Volání</dt><dd>{st ? `${num(st.calls.total)} (chyby ${num(st.calls.errors)})` : '—'}</dd>
              {daemon.message && <><dt>Problém</dt><dd className="t2">{daemon.message}</dd></>}
            </dl>
          ) : <Loading />}
          <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
            {daemon?.phase === 'running'
              ? <><button className="btn" onClick={() => void bridge().daemon.restart()}>Restartovat</button><button className="btn" onClick={() => void bridge().daemon.stop()}>Zastavit</button></>
              : <button className="btn primary" disabled={daemon?.phase === 'starting'} onClick={() => void bridge().daemon.start()}>Spustit daemon</button>}
          </div>
        </Card>
      </div>

      <div className="grid-2e">
        <Card title={`Úspora podle nástroje (${r})`}>
          <BarList label="Úspora podle nástroje" items={data.savingsByTool.map(s => ({ name: s.tool, value: s.savedTokens, note: `${num(s.calls)}×` }))} />
        </Card>
        <Card title="Rozpočty">
          <dl className="dl">
            <dt>Denní rozpočet</dt><dd>{budget ? `${tokens(data.budget.usedToday)} z ${tokens(budget)}` : 'nenastaven'}</dd>
            <dt>Aktualizováno</dt><dd>{ago(data.generatedAt)}</dd>
          </dl>
        </Card>
      </div>

      <Card title={`Volání CodeLoupe podle nástroje (${r})`} bodyClass="">
        <DataTable label="Volání CodeLoupe podle nástroje" rows={data.toolCalls} columns={callColumns} rowKey={x => x.tool} />
      </Card>
    </>
  );
}
