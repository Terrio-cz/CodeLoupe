import { useEffect, useMemo, useState } from 'react';
import type { Page } from '../../../shared/contract';
import type { RunItem, RunPage, RunSortKey, StepItem, StepSortKey } from '../../../shared/runs';
import { bridge, useApi } from '../api';
import { BarList } from '../components/Charts';
import { DataTable, type Column } from '../components/DataTable';
import { Drawer } from '../components/Drawer';
import { CountUp } from '../components/CountUp';
import { Banner, Card, ErrorState, KpiTile, Loading, Search, Section, Select } from '../components/Parts';
import { StatusBadge } from '../components/StatusBadge';
import { dateTime, ms, num, pct, tokens } from '../format';
import { useDebounced, useRange, useSettings } from '../hooks';
import { chars, GAP_LABELS, SORT_LABELS, span, usageParts } from '../runModel';
import { go, type Route } from '../router';

const SORTS = Object.entries(SORT_LABELS).map(([value, label]) => ({ value, label: `Sort: ${label.toLowerCase()}` }));

const plural = (n: number, one: string, many: string) => `${num(n)} ${n === 1 ? one : many}`;

function columns(): Column<RunItem>[] {
  return [
    { key: 'start', header: 'Start', sortKey: 'start', render: r => dateTime(r.startedAt) },
    { key: 'role', header: 'Role', render: r => <span className="mono">{r.role}</span> },
    { key: 'title', header: 'Prompt', render: r => <span title={r.title}>{r.title}</span>, className: 'ellipsis narrow' },
    { key: 'ter', header: 'Task', render: r => r.ter ?? '—' },
    { key: 'duration', header: 'Duration', sortKey: 'duration', render: r => span(r.durationSec), numeric: true },
    { key: 'turns', header: 'Turns', sortKey: 'turns', render: r => num(r.turns), numeric: true },
    { key: 'weighted', header: 'Cost', sortKey: 'weighted', render: r => tokens(r.weighted), numeric: true },
    { key: 'peak', header: 'Peak', sortKey: 'peak', render: r => tokens(r.peakContext), numeric: true },
    { key: 'share', header: 'Results', sortKey: 'share', render: r => pct(r.toolResultShare * 100), numeric: true },
    { key: 'calls', header: 'Calls', render: r => `${num(r.toolCalls)}${r.toolErrors ? ` · ${plural(r.toolErrors, 'error', 'errors')}` : ''}`, numeric: true },
    { key: 'budget', header: 'Budget', render: r => (r.overBudget ? <StatusBadge tone="warning">over budget</StatusBadge> : <span className="muted">—</span>) },
  ];
}

/** Pages after the first one, fetched on request and appended; they are dropped whenever the filter changes. */
function usePages<T>(resource: 'runs' | 'runs/:id/steps', id: string | undefined, query: Record<string, string | number>, first: Page<T> | null, signature: string) {
  const [more, setMore] = useState<{ items: T[]; next: string | null; sig: string } | null>(null);
  const [loading, setLoading] = useState(false);
  const live = more && more.sig === signature ? more : null;
  const next = live ? live.next : first?.nextCursor ?? null;
  const load = async () => {
    if (!next) return;
    setLoading(true);
    const res = await bridge().api<Page<T>>({ resource, id, query: { ...query, cursor: next } });
    setLoading(false);
    if (res.ok) setMore({ items: [...(live?.items ?? []), ...res.data.items], next: res.data.nextCursor, sig: signature });
  };
  return { items: [...(first?.items ?? []), ...(live?.items ?? [])], hasMore: next !== null, loading, load };
}

export function Runs({ route }: { route: Route }) {
  const [range] = useRange();
  const [role, setRole] = useState('');
  const [sort, setSort] = useState<RunSortKey>('start');
  // `#/runs?q=TER-1` (from a branch or a task) starts with that search.
  const [q, setQ] = useState(route.params.get('q') ?? '');
  const text = useDebounced(q);
  const [settings] = useSettings();
  const query = { range, sort, role, q: text, limit: 50 };
  const signature = JSON.stringify(query);
  const page = useApi('runs', undefined, query);
  const rows = usePages<RunItem>('runs', undefined, query, page.data, signature);
  const cols = useMemo(columns, []);

  // The first reading of the transcripts takes about a minute: the list fills in while it runs.
  const reading = page.data?.ingest.running ?? false;
  useEffect(() => {
    if (!reading) return;
    const t = setInterval(page.reload, 3000);
    return () => clearInterval(t);
  }, [reading, page.reload]);

  if (!page.data) return <Card bodyClass="">{page.loading ? <Loading variant="table" /> : <ErrorState message={page.error?.message ?? 'Could not load runs.'} onRetry={page.reload} />}</Card>;
  const data: RunPage = page.data;
  const shownCost = rows.items.reduce((a, r) => a + r.weighted, 0);
  const over = rows.items.filter(r => r.overBudget).length;

  return (
    <>
      <div className="filterbar">
        <Select label="Role" value={role} onChange={setRole} options={[{ value: '', label: 'All roles' }, ...data.roles.map(r => ({ value: r, label: r }))]} />
        <Select label="Sort" value={sort} onChange={v => setSort(v as RunSortKey)} options={SORTS} />
        <Search label="Search prompt, task, role" value={q} onChange={setQ} />
        <span className="muted">Agent runs from Claude Code transcripts: what they cost and where. The app does not track them live.</span>
      </div>
      {reading && <Banner tone="info">The daemon is reading transcripts ({num(data.ingest.filesDone)} of {plural(data.ingest.filesTotal, 'file', 'files')}); the list is filling in.</Banner>}

      <section aria-label="Run summary">
        <div className="kpis">
          <KpiTile label="Runs in range" value={<CountUp value={data.total} format={num} />} ctx={`${num(rows.items.length)} shown`} />
          <KpiTile label="Cost of shown" value={<CountUp value={shownCost} format={tokens} />} ctx="weighted tokens" />
          <KpiTile label="Over run budget" value={<CountUp value={over} format={num} />} ctx="among shown" />
        </div>
      </section>

      <Card bodyClass="">
        <DataTable label="Agent runs" rows={rows.items} columns={cols} rowKey={r => r.id} selected={route.id} shortcuts={settings?.shortcuts}
          sort={{ key: sort, order: 'desc' }} onSort={s => setSort(s.key as RunSortKey)} onOpen={r => go('runs', r.id)}
          empty="No runs in this range and filter." />
        {rows.hasMore && (
          <div className="table-foot">
            <button className="btn" disabled={rows.loading} onClick={() => void rows.load()}>{rows.loading ? 'Loading…' : 'Load more'}</button>
            <span>{num(rows.items.length)} of {num(data.total)}</span>
          </div>
        )}
      </Card>
      {route.id && <RunDrawer id={route.id} onClose={() => go('runs')} />}
    </>
  );
}

const STEP_SORTS: { value: StepSortKey; label: string }[] = [
  { value: 'seq', label: 'Call order' }, { value: 'weighted', label: 'Cost of holding result' }, { value: 'chars', label: 'Result size' },
];

function RunDrawer({ id, onClose }: { id: string; onClose(): void }) {
  const { data, error, reload } = useApi('runs/:id', id);
  const [sort, setSort] = useState<StepSortKey>('seq');
  const query = { sort, limit: 200 };
  const steps = useApi('runs/:id/steps', id, query);
  const more = usePages<StepItem>('runs/:id/steps', id, query, steps.data, `${id}|${sort}`);
  const run = data?.run;

  return (
    <Drawer title={run ? <span title={run.title}>{run.role}{run.ter ? ` · ${run.ter}` : ''}</span> : id} subtitle={run?.title} onClose={onClose} wide>
      {!data || !run ? (error ? <ErrorState message={error.message} onRetry={reload} /> : <Loading />) : (
        <>
          <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
            <span className="chip">{run.kind === 'session' ? 'session' : 'subagent'}</span>
            {run.model && <span className="chip">{run.model}</span>}
            {run.overBudget && <StatusBadge tone="warning">over run budget</StatusBadge>}
          </div>
          <dl className="dl">
            <dt>Start</dt><dd>{dateTime(run.startedAt)} · took {span(run.durationSec)}</dd>
            <dt>Cost</dt><dd>{tokens(run.weighted)} weighted tokens over {plural(run.turns, 'turn', 'turns')}</dd>
            <dt>Peak context</dt><dd>{tokens(run.peakContext)} tokens</dd>
            <dt>Tool result share</dt><dd>{pct(run.toolResultShare * 100)} of the cost is holding results in context</dd>
            <dt>Tool calls</dt><dd>{num(run.toolCalls)}{run.toolErrors ? `, ${num(run.toolErrors)} failed` : ''}</dd>
            {run.ter && <><dt>Task</dt><dd><button className="link" onClick={() => go('tasks', run.ter)}>{run.ter} →</button></dd></>}
            <dt>Session · file</dt><dd className="mono">{run.session} · {run.file}</dd>
          </dl>

          <Section title="What the cost is made of">
            <BarList label="Weighted cost by token type" noteWidth={130} items={usageParts(data.usage).map(p => ({ name: p.name, value: p.value, note: `${tokens(p.tokens)} × ${p.factor}` }))} />
          </Section>

          <Section title="By tool category" count={data.categories.length}>
            <div className="table-wrap">
              <table className="data" aria-label="Tool categories">
                <thead><tr><th scope="col">Category</th><th scope="col" className="num">Calls</th><th scope="col" className="num">Results</th><th scope="col" className="num">Held × turns</th><th scope="col" className="num">Cost</th><th scope="col" className="num">Errors</th></tr></thead>
                <tbody>
                  {data.categories.map(c => (
                    <tr key={c.category}><td className="mono">{c.category}</td><td className="num">{num(c.calls)}</td><td className="num">{chars(c.chars)}</td><td className="num">{chars(c.carried)}</td><td className="num">{tokens(c.weighted)}</td><td className="num">{num(c.errors)}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Section>

          <Section title="Steps" count={steps.data?.total}>
            <div className="filterbar" style={{ marginBottom: 8 }}>
              <Select label="Sort steps" value={sort} onChange={v => setSort(v as StepSortKey)} options={STEP_SORTS} />
              <span className="muted">The call description is shortened and masked by the daemon, never the result content.</span>
            </div>
            {!steps.data ? (steps.error ? <ErrorState message={steps.error.message} onRetry={steps.reload} /> : <Loading />) : (
              <>
                <div className="table-wrap">
                  <table className="data" aria-label="Run steps">
                    <thead><tr><th scope="col" className="num">#</th><th scope="col" className="num">Turn</th><th scope="col">Tool</th><th scope="col">Category</th><th scope="col">About</th><th scope="col" className="num">Result</th><th scope="col" className="num">Time</th><th scope="col" className="num">Cost</th><th scope="col">Status</th></tr></thead>
                    <tbody>
                      {more.items.map(s => (
                        <tr key={s.seq}>
                          <td className="num">{s.seq}</td><td className="num">{s.turn}</td><td className="mono">{s.tool.replace('mcp__', '')}</td><td>{s.category}</td>
                          <td className="mono ellipsis" title={s.summary}>{s.summary}</td><td className="num">{chars(s.chars)}</td><td className="num">{ms(s.durationMs)}</td><td className="num">{tokens(s.weighted)}</td>
                          <td>{s.error ? <span title={s.errorText ?? undefined}><StatusBadge tone="critical">error</StatusBadge></span> : s.gap ? <StatusBadge tone="warning">{GAP_LABELS[s.gap]}</StatusBadge> : <span className="muted">ok</span>}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {more.hasMore && <div className="table-foot"><button className="btn" disabled={more.loading} onClick={() => void more.load()}>{more.loading ? 'Loading…' : 'Load more steps'}</button><span>{num(more.items.length)} of {num(steps.data.total)}</span></div>}
              </>
            )}
          </Section>
        </>
      )}
    </Drawer>
  );
}
