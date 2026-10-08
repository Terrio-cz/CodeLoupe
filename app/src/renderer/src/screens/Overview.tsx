import { useEffect } from 'react';
import type { ToolCalls } from '../../../shared/contract';
import { bridge, useApi } from '../api';
import { BarList, CostChart } from '../components/Charts';
import { DataTable, type Column } from '../components/DataTable';
import { LatencyBars } from '../components/LatencyBars';
import { Card, Delta, ErrorState, KpiTile, Loading, rangeLabel, Select } from '../components/Parts';
import { PhaseBadge } from '../components/StatusBadge';
import { TimeChart } from '../components/TimeChart';
import { ago, ms, num, pct, time, tokens } from '../format';
import { cpuPoints, rssPoints } from '../history';
import { useAccount, useDaemon, useRange } from '../hooks';

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

const mb = (v: number) => `${num(Math.round(v))} MB`;
const cpu = (v: number) => `${num(Math.round(v * 10) / 10)} %`;

export function Overview() {
  const [range] = useRange();
  const [account, setAccount] = useAccount();
  const accounts = useApi('accounts');
  // An account that was removed meanwhile is no filter any more.
  useEffect(() => { if (account && accounts.data && !accounts.data.claude.some(a => a.id === account)) setAccount(''); }, [account, accounts.data]);
  const { data, error, loading, reload } = useApi('overview', undefined, { range, account });
  const daemon = useDaemon();
  const history = useApi('status/history');
  const settings = useApi('settings');

  if (!data) return loading ? <Card><Loading /></Card> : <Card><ErrorState message={error?.message ?? 'Nelze načíst přehled.'} onRetry={reload} /></Card>;
  const k = data.kpis;
  const budget = data.budget.dailyWeighted;
  const used = budget ? Math.min(100, (data.budget.usedToday / budget) * 100) : 0;
  const r = rangeLabel(range);
  const st = daemon?.status;
  const limits = settings.data?.budgets;
  const warnings = [
    ...(budget && data.budget.usedToday > budget ? [`Denní rozpočet tokenů překročen: ${tokens(data.budget.usedToday)} z ${tokens(budget)}`] : []),
    ...(st?.budgets?.warnings ?? []),
  ];
  const samples = history.data ?? [];

  return (
    <>
      {(accounts.data?.claude.length ?? 0) > 1 && (
        <div className="filterbar">
          <Select label="Účet" value={account} onChange={setAccount}
            options={[{ value: '', label: 'Všechny účty' }, ...(accounts.data?.claude ?? []).map(a => ({ value: a.id, label: a.label }))]} />
        </div>
      )}
      {warnings.length > 0 && (
        <div className="banner" role="status">
          <strong>⚠ Rozpočty překročeny</strong>
          <ul className="plain">{warnings.map(w => <li key={w}>{w}</li>)}</ul>
        </div>
      )}
      {k.weightedRange === 0 && data.costSeries.every(p => p.weighted === 0) && (
        <div className="banner info" role="note">Spotřeba a úspora tokenů zatím chybí: daemon je počítá z transkriptů agentů až s jejich ingestem (CL-62). Latence, paměť a volání níže jsou skutečné.</div>
      )}
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

      <div className="grid-3">
        <Card title="Latence volání (p95)">
          {st?.latency ? <LatencyBars latency={st.latency} budgetMs={limits?.p95Ms ?? null} /> : <div className="state">Daemon neběží, latence není k dispozici.</div>}
        </Card>
        <Card title="Paměť daemonu (RSS)">
          <TimeChart label="RSS" points={rssPoints(samples)} format={mb} limit={limits ? { value: limits.daemonRssMb, label: 'budget' } : undefined} />
        </Card>
        <Card title="Zátěž CPU daemonu">
          <TimeChart label="CPU" points={cpuPoints(samples)} format={cpu} />
        </Card>
      </div>

      <div className="grid-2e">
        <Card title={`Úspora podle nástroje (${r})`}>
          <BarList label="Úspora podle nástroje" items={data.savingsByTool.map(s => ({ name: s.tool, value: s.savedTokens, note: `${num(s.calls)}×` }))} />
        </Card>
        <Card title="Rozpočty">
          <dl className="dl">
            <dt>Denní rozpočet</dt><dd>{budget ? `${tokens(data.budget.usedToday)} z ${tokens(budget)}` : 'nenastaven'}</dd>
            {limits && (
              <>
                <dt>Paměť daemonu</dt><dd>{st ? `${st.rssMb} MB z ${limits.daemonRssMb} MB` : `limit ${limits.daemonRssMb} MB`}</dd>
                <dt>p95 latence</dt><dd>{st?.latency ? `${ms(st.latency.p95Ms)} z ${ms(limits.p95Ms)}` : `limit ${ms(limits.p95Ms)}`}</dd>
                <dt>Busy</dt><dd>{st?.latency ? `${pct(st.latency.busyRate * 100, 1)} z ${pct(limits.busyRate * 100)}` : `limit ${pct(limits.busyRate * 100)}`}</dd>
                <dt>Čekání ve frontě</dt><dd>{ms(limits.queueWaitMs)} nejvýš</dd>
              </>
            )}
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
