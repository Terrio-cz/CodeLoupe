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

const SORTS = Object.entries(SORT_LABELS).map(([value, label]) => ({ value, label: `Řadit: ${label.toLowerCase()}` }));

function columns(): Column<RunItem>[] {
  return [
    { key: 'start', header: 'Začátek', sortKey: 'start', render: r => dateTime(r.startedAt) },
    { key: 'role', header: 'Role', render: r => <span className="mono">{r.role}</span> },
    { key: 'title', header: 'Zadání', render: r => <span title={r.title}>{r.title}</span>, className: 'ellipsis' },
    { key: 'ter', header: 'Úkol', render: r => r.ter ?? '—' },
    { key: 'duration', header: 'Délka', sortKey: 'duration', render: r => span(r.durationSec), numeric: true },
    { key: 'turns', header: 'Tahy', sortKey: 'turns', render: r => num(r.turns), numeric: true },
    { key: 'weighted', header: 'Cena', sortKey: 'weighted', render: r => tokens(r.weighted), numeric: true },
    { key: 'peak', header: 'Peak kontext', sortKey: 'peak', render: r => tokens(r.peakContext), numeric: true },
    { key: 'share', header: 'Podíl výsledků', sortKey: 'share', render: r => pct(r.toolResultShare * 100), numeric: true },
    { key: 'calls', header: 'Volání', render: r => `${num(r.toolCalls)}${r.toolErrors ? ` · ${r.toolErrors} chyb` : ''}`, numeric: true },
    { key: 'budget', header: 'Rozpočet', render: r => (r.overBudget ? <StatusBadge tone="warning">nad rozpočet</StatusBadge> : <span className="muted">—</span>) },
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

  if (!page.data) return <Card bodyClass="">{page.loading ? <Loading variant="table" /> : <ErrorState message={page.error?.message ?? 'Nelze načíst běhy.'} onRetry={page.reload} />}</Card>;
  const data: RunPage = page.data;
  const shownCost = rows.items.reduce((a, r) => a + r.weighted, 0);
  const over = rows.items.filter(r => r.overBudget).length;

  return (
    <>
      <div className="filterbar">
        <Select label="Role" value={role} onChange={setRole} options={[{ value: '', label: 'Všechny role' }, ...data.roles.map(r => ({ value: r, label: r }))]} />
        <Select label="Řazení" value={sort} onChange={v => setSort(v as RunSortKey)} options={SORTS} />
        <Search label="Hledat zadání, úkol, roli" value={q} onChange={setQ} />
        <span className="muted">Běhy agentů z transkriptů Claude Code: kolik stály a kde; aplikace je nesleduje za běhu.</span>
      </div>
      {reading && <Banner tone="info">Daemon čte transkripty ({num(data.ingest.filesDone)} z {num(data.ingest.filesTotal)} souborů), seznam se doplňuje.</Banner>}

      <section aria-label="Souhrn běhů">
        <div className="kpis">
          <KpiTile label="Běhy v rozsahu" value={<CountUp value={data.total} format={num} />} ctx={`${num(rows.items.length)} zobrazeno`} />
          <KpiTile label="Cena zobrazených" value={<CountUp value={shownCost} format={tokens} />} ctx="vážené tokeny" />
          <KpiTile label="Nad rozpočet běhu" value={<CountUp value={over} format={num} />} ctx="mezi zobrazenými" />
        </div>
      </section>

      <Card bodyClass="">
        <DataTable label="Běhy agentů" rows={rows.items} columns={cols} rowKey={r => r.id} selected={route.id} shortcuts={settings?.shortcuts}
          sort={{ key: sort, order: 'desc' }} onSort={s => setSort(s.key as RunSortKey)} onOpen={r => go('runs', r.id)}
          empty="Žádný běh v tomto rozsahu a filtru." />
        {rows.hasMore && (
          <div className="table-foot">
            <button className="btn" disabled={rows.loading} onClick={() => void rows.load()}>{rows.loading ? 'Načítám…' : 'Načíst další'}</button>
            <span>{num(rows.items.length)} z {num(data.total)}</span>
          </div>
        )}
      </Card>
      {route.id && <RunDrawer id={route.id} onClose={() => go('runs')} />}
    </>
  );
}

const STEP_SORTS: { value: StepSortKey; label: string }[] = [
  { value: 'seq', label: 'Pořadí volání' }, { value: 'weighted', label: 'Cena držení výsledku' }, { value: 'chars', label: 'Velikost výsledku' },
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
            <span className="chip">{run.kind === 'session' ? 'sezení' : 'subagent'}</span>
            {run.model && <span className="chip">{run.model}</span>}
            {run.overBudget && <StatusBadge tone="warning">nad rozpočet běhu</StatusBadge>}
          </div>
          <dl className="dl">
            <dt>Začátek</dt><dd>{dateTime(run.startedAt)} · trvalo {span(run.durationSec)}</dd>
            <dt>Cena</dt><dd>{tokens(run.weighted)} vážených tokenů za {num(run.turns)} tahů</dd>
            <dt>Peak kontext</dt><dd>{tokens(run.peakContext)} tokenů</dd>
            <dt>Podíl výsledků nástrojů</dt><dd>{pct(run.toolResultShare * 100)} ceny je držení výsledků v kontextu</dd>
            <dt>Volání nástrojů</dt><dd>{num(run.toolCalls)}{run.toolErrors ? `, z toho ${num(run.toolErrors)} s chybou` : ''}</dd>
            {run.ter && <><dt>Úkol</dt><dd><button className="link" onClick={() => go('tasks', run.ter)}>{run.ter} →</button></dd></>}
            <dt>Sezení · soubor</dt><dd className="mono">{run.session} · {run.file}</dd>
          </dl>

          <Section title="Z čeho se cena skládá">
            <BarList label="Vážená cena podle druhu tokenů" noteWidth={130} items={usageParts(data.usage).map(p => ({ name: p.name, value: p.value, note: `${tokens(p.tokens)} × ${String(p.factor).replace('.', ',')}` }))} />
          </Section>

          <Section title="Podle kategorie nástroje" count={data.categories.length}>
            <div className="table-wrap" style={{ maxHeight: 260 }}>
              <table className="data" aria-label="Kategorie nástrojů">
                <thead><tr><th scope="col">Kategorie</th><th scope="col" className="num">Volání</th><th scope="col" className="num">Výsledky</th><th scope="col" className="num">Držení × tahy</th><th scope="col" className="num">Cena</th><th scope="col" className="num">Chyby</th></tr></thead>
                <tbody>
                  {data.categories.map(c => (
                    <tr key={c.category}><td className="mono">{c.category}</td><td className="num">{num(c.calls)}</td><td className="num">{chars(c.chars)}</td><td className="num">{chars(c.carried)}</td><td className="num">{tokens(c.weighted)}</td><td className="num">{num(c.errors)}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Section>

          <Section title="Kroky" count={steps.data?.total}>
            <div className="filterbar" style={{ marginBottom: 8 }}>
              <Select label="Řazení kroků" value={sort} onChange={v => setSort(v as StepSortKey)} options={STEP_SORTS} />
              <span className="muted">Popis volání je zkrácený a maskovaný daemonem, nikdy ne obsah výsledku.</span>
            </div>
            {!steps.data ? (steps.error ? <ErrorState message={steps.error.message} onRetry={steps.reload} /> : <Loading />) : (
              <>
                <div className="table-wrap" style={{ maxHeight: 420 }}>
                  <table className="data" aria-label="Kroky běhu">
                    <thead><tr><th scope="col" className="num">#</th><th scope="col" className="num">Tah</th><th scope="col">Nástroj</th><th scope="col">Kategorie</th><th scope="col">O čem</th><th scope="col" className="num">Výsledek</th><th scope="col" className="num">Doba</th><th scope="col" className="num">Cena</th><th scope="col">Stav</th></tr></thead>
                    <tbody>
                      {more.items.map(s => (
                        <tr key={s.seq}>
                          <td className="num">{s.seq}</td><td className="num">{s.turn}</td><td className="mono">{s.tool.replace('mcp__', '')}</td><td>{s.category}</td>
                          <td className="mono ellipsis" title={s.summary}>{s.summary}</td><td className="num">{chars(s.chars)}</td><td className="num">{ms(s.durationMs)}</td><td className="num">{tokens(s.weighted)}</td>
                          <td>{s.error ? <span title={s.errorText ?? undefined}><StatusBadge tone="critical">chyba</StatusBadge></span> : s.gap ? <StatusBadge tone="warning">{GAP_LABELS[s.gap]}</StatusBadge> : <span className="muted">ok</span>}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {more.hasMore && <div className="table-foot"><button className="btn" disabled={more.loading} onClick={() => void more.load()}>{more.loading ? 'Načítám…' : 'Načíst další kroky'}</button><span>{num(more.items.length)} z {num(steps.data.total)}</span></div>}
              </>
            )}
          </Section>
        </>
      )}
    </Drawer>
  );
}
