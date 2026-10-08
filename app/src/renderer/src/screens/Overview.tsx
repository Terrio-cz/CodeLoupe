import { useEffect } from 'react';
import type { ToolCalls } from '../../../shared/contract';
import { bridge, useApi } from '../api';
import { BarList, CostChart } from '../components/Charts';
import { DataTable, type Column } from '../components/DataTable';
import { LatencyBars } from '../components/LatencyBars';
import { CountUp } from '../components/CountUp';
import { Icon } from '../components/Icon';
import { Banner, Card, Delta, ErrorState, KpiTile, Loading, rangeLabel, Select } from '../components/Parts';
import { PhaseBadge } from '../components/StatusBadge';
import { TimeChart } from '../components/TimeChart';
import { ago, ms, num, pct, time, tokens } from '../format';
import { cpuPoints, rssPoints } from '../history';
import { useAccount, useDaemon, useRange } from '../hooks';

const callColumns: Column<ToolCalls>[] = [
  { key: 'tool', header: 'Tool', render: t => <span className="mono">{t.tool}</span> },
  { key: 'calls', header: 'Calls', render: t => num(t.calls), numeric: true },
  { key: 'p50', header: 'p50', render: t => ms(t.p50Ms), numeric: true },
  { key: 'p95', header: 'p95', render: t => ms(t.p95Ms), numeric: true },
  { key: 'size', header: 'Avg result', render: t => `${tokens(t.avgResultChars)} chars`, numeric: true },
  { key: 'empty', header: 'Empty', render: t => pct(t.emptyShare * 100), numeric: true },
  { key: 'busy', header: 'Busy', render: t => num(t.busy), numeric: true },
  { key: 'errors', header: 'Errors', render: t => num(t.errors), numeric: true },
];

const mb = (v: number) => `${num(Math.round(v))} MB`;
const cpu = (v: number) => `${num(Math.round(v * 10) / 10)}%`;

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

  if (!data) return loading ? <><Loading variant="kpis" /><Card title="Cost over time (weighted tokens)"><Loading /></Card></> : <Card><ErrorState message={error?.message ?? 'Could not load the overview.'} onRetry={reload} /></Card>;
  const k = data.kpis;
  const budget = data.budget.dailyWeighted;
  const used = budget ? Math.min(100, (data.budget.usedToday / budget) * 100) : 0;
  const r = rangeLabel(range);
  const st = daemon?.status;
  const limits = settings.data?.budgets;
  const warnings = [
    ...(budget && data.budget.usedToday > budget ? [`Daily token budget exceeded: ${tokens(data.budget.usedToday)} of ${tokens(budget)}`] : []),
    ...(st?.budgets?.warnings ?? []),
  ];
  const samples = history.data ?? [];

  return (
    <>
      {(accounts.data?.claude.length ?? 0) > 1 && (
        <div className="filterbar">
          <Select label="Account" value={account} onChange={setAccount}
            options={[{ value: '', label: 'All accounts' }, ...(accounts.data?.claude ?? []).map(a => ({ value: a.id, label: a.label }))]} />
        </div>
      )}
      {warnings.length > 0 && (
        <Banner>
          <strong>Budgets exceeded</strong>
          <ul className="plain">{warnings.map(w => <li key={w}>{w}</li>)}</ul>
        </Banner>
      )}
      {k.weightedRange === 0 && data.costSeries.every(p => p.weighted === 0) && (
        <Banner tone="info" role="note">Token usage and savings are not available yet: the daemon computes them from agent transcripts once it ingests them (CL-62). Latency, memory and calls below are real.</Banner>
      )}
      <section aria-label="Key figures">
        <div className="kpis">
          <KpiTile label="Cost today" value={<CountUp value={k.weightedToday} format={tokens} />} ctx={<Delta now={k.weightedToday} before={k.weightedYesterdaySameTime} unit=" than yesterday" />}>
            {budget && (
              <>
                <div className={`meter${data.budget.usedToday > budget ? ' over' : ''}`} role="meter" aria-valuemin={0} aria-valuemax={budget} aria-valuenow={data.budget.usedToday} aria-label="Daily budget used">
                  <span style={{ width: `${used}%` }} />
                </div>
                <span className="ctx">{pct((data.budget.usedToday / budget) * 100)} of {tokens(budget)} budget</span>
              </>
            )}
          </KpiTile>
          <KpiTile label={`Cost ${r}`} value={<CountUp value={k.weightedRange} format={tokens} />} ctx={`baseline ${tokens(k.baselineRange)}`} />
          <KpiTile label={`Savings ${r}`} value={<CountUp value={k.savedPct} format={v => pct(v, 1)} />} ctx={`${tokens(k.savedTokens)} tokens`} />
          <KpiTile label="Active windows" value={<CountUp value={k.activeWindows} format={num} />} ctx={`${num(k.queriedWorktrees)} worktrees queried`} />
          <KpiTile label={`CodeLoupe calls ${r}`} value={<CountUp value={k.codeloupeCalls} format={num} />} ctx={`${ms(k.callP50Ms)} p50`} />
          <KpiTile label={`Gaps ${r}`} value={<CountUp value={k.gaps} format={num} />} ctx={`${num(k.newGaps)} new in 24h`} />
        </div>
      </section>

      <div className="grid-2">
        <Card title="Cost over time (weighted tokens)">
          <CostChart points={data.costSeries} hourly={range === '24h'} />
        </Card>
        <Card title="Daemon">
          {daemon ? (
            <dl className="dl">
              <dt>Status</dt><dd><PhaseBadge phase={daemon.phase} /></dd>
              <dt>Version</dt><dd>{st ? `v${st.version} · pid ${st.pid}` : '—'}</dd>
              <dt>Port</dt><dd className="mono">127.0.0.1:{daemon.port}</dd>
              <dt>RSS</dt><dd>{st ? `${st.rssMb} MB (heap ${st.heapMb} MB)` : '—'}</dd>
              <dt>CPU</dt><dd>{st ? `${st.cpuSec} s · up ${ms(st.uptimeSec * 1000)}` : '—'}</dd>
              <dt>Queue</dt><dd>{st ? `fast ${st.queue.fast.waiting.length} · heavy ${st.queue.heavy.waiting.length}` : '—'}</dd>
              <dt>Build</dt><dd>{st?.queue.heavy.running ?? (st?.repos.find(x => x.lastBuild)?.lastBuild ? `last ${time(st.repos.find(x => x.lastBuild)!.lastBuild!.at)}, ${ms(st.repos.find(x => x.lastBuild)!.lastBuild!.ms)}` : 'none')}</dd>
              <dt>Calls</dt><dd>{st ? `${num(st.calls.total)} (errors ${num(st.calls.errors)})` : '—'}</dd>
              {daemon.message && <><dt>Problem</dt><dd className="t2">{daemon.message}</dd></>}
            </dl>
          ) : <Loading />}
          <div className="actions" style={{ marginTop: 16 }}>
            {daemon?.phase === 'running'
              ? <><button className="btn" onClick={() => void bridge().daemon.restart()}><Icon name="refresh" size={14} />Restart</button><button className="btn" onClick={() => void bridge().daemon.stop()}>Stop</button></>
              : <button className="btn primary" disabled={daemon?.phase === 'starting'} onClick={() => void bridge().daemon.start()}>Start daemon</button>}
          </div>
        </Card>
      </div>

      <div className="grid-3">
        <Card title="Call latency (p95)">
          {st?.latency ? <LatencyBars latency={st.latency} budgetMs={limits?.p95Ms ?? null} /> : <div className="state"><span className="state-icon"><Icon name="chart" size={18} /></span><div>The daemon is not running, so latency is not available.</div></div>}
        </Card>
        <Card title="Daemon memory (RSS)">
          <TimeChart label="RSS" points={rssPoints(samples)} format={mb} limit={limits ? { value: limits.daemonRssMb, label: 'budget' } : undefined} />
        </Card>
        <Card title="Daemon CPU load">
          <TimeChart label="CPU" points={cpuPoints(samples)} format={cpu} />
        </Card>
      </div>

      <div className="grid-2e">
        <Card title={`Savings by tool (${r})`}>
          <BarList label="Savings by tool" items={data.savingsByTool.map(s => ({ name: s.tool, value: s.savedTokens, note: `${num(s.calls)}×` }))} />
        </Card>
        <Card title="Budgets">
          <dl className="dl">
            <dt>Daily budget</dt><dd>{budget ? `${tokens(data.budget.usedToday)} of ${tokens(budget)}` : 'not set'}</dd>
            {limits && (
              <>
                <dt>Daemon memory</dt><dd>{st ? `${st.rssMb} MB of ${limits.daemonRssMb} MB` : `limit ${limits.daemonRssMb} MB`}</dd>
                <dt>p95 latency</dt><dd>{st?.latency ? `${ms(st.latency.p95Ms)} of ${ms(limits.p95Ms)}` : `limit ${ms(limits.p95Ms)}`}</dd>
                <dt>Busy</dt><dd>{st?.latency ? `${pct(st.latency.busyRate * 100, 1)} of ${pct(limits.busyRate * 100)}` : `limit ${pct(limits.busyRate * 100)}`}</dd>
                <dt>Queue wait</dt><dd>{ms(limits.queueWaitMs)} max</dd>
              </>
            )}
            <dt>Updated</dt><dd>{ago(data.generatedAt)}</dd>
          </dl>
        </Card>
      </div>

      <Card title={`CodeLoupe calls by tool (${r})`} bodyClass="">
        <DataTable label="CodeLoupe calls by tool" rows={data.toolCalls} columns={callColumns} rowKey={x => x.tool} />
      </Card>
    </>
  );
}
