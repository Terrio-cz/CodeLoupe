import { useEffect, useState } from 'react';
import type { RunStep, StepFlag, StepSort } from '../../../shared/contract';
import { useApi } from '../api';
import { BarList, ShareBar } from '../components/Charts';
import { DataTable, type Column, type SortState } from '../components/DataTable';
import { Drawer } from '../components/Drawer';
import { Card, ErrorState, Loading, Segmented, Select } from '../components/Parts';
import { FlagChip, RunBadge } from '../components/StatusBadge';
import { duration, ms, num, offset, pct, tokens } from '../format';
import { useRange, useSettings } from '../hooks';
import { go, type Route } from '../router';
import { runColumns } from './Overview';

const ROLES = ['', 'planner', 'coder', 'coder-high', 'reviewer', 'tester', 'deep-reviewer', 'context'];
const PAGE = 50;

export function Runs({ route }: { route: Route }) {
  const [range] = useRange();
  const [settings] = useSettings();
  const [role, setRole] = useState('');
  const [gapsOnly, setGapsOnly] = useState(false);
  const [sort, setSort] = useState<SortState>({ key: 'started', order: 'desc' });
  const [cursor, setCursor] = useState('0');
  useEffect(() => setCursor('0'), [range, role, gapsOnly, sort.key, sort.order]);
  const { data, error, loading, reload } = useApi('runs', undefined, { range, role, gapsOnly: gapsOnly || undefined, sort: sort.key, order: sort.order, limit: PAGE, cursor });

  return (
    <>
      <div className="filterbar">
        <Select label="Role" value={role} onChange={setRole} options={ROLES.map(r => ({ value: r, label: r || 'Všechny role' }))} />
        <label className="check"><input type="checkbox" checked={gapsOnly} onChange={e => setGapsOnly(e.target.checked)} /> Jen s mezerami</label>
      </div>
      <Card bodyClass="">
        {data ? (
          <>
            <DataTable label="Běhy agentů" rows={data.items} columns={runColumns} rowKey={r => r.id} selected={route.id}
              onOpen={r => go('runs', r.id)} sort={sort} onSort={setSort} shortcuts={settings?.shortcuts} empty="Žádné běhy v tomto rozsahu." />
            <div className="table-foot">
              <span>{num(Number(cursor) + 1)}–{num(Number(cursor) + data.items.length)} z {num(data.total)}</span>
              <span style={{ flex: 1 }} />
              <button className="btn" disabled={cursor === '0'} onClick={() => setCursor(String(Math.max(0, Number(cursor) - PAGE)))}>← Předchozí</button>
              <button className="btn" disabled={!data.nextCursor} onClick={() => data.nextCursor && setCursor(data.nextCursor)}>Další →</button>
            </div>
          </>
        ) : loading ? <Loading /> : <ErrorState message={error?.message ?? 'Nelze načíst běhy.'} onRetry={reload} />}
      </Card>
      {route.id && <RunDrawer id={route.id} step={route.params.get('step')} onClose={() => go('runs')} />}
    </>
  );
}

const STEP_SORTS: { value: StepSort; label: string }[] = [
  { value: 'seq', label: 'Čas' },
  { value: 'carried', label: 'Nesená cena' },
  { value: 'weighted', label: 'Cena tahu' },
  { value: 'resultChars', label: 'Znaky' },
];

function RunDrawer({ id, step, onClose }: { id: string; step: string | null; onClose(): void }) {
  const { data: run, error, reload } = useApi('runs/:id', id);
  const [kind, setKind] = useState<'' | 'tool' | 'text'>('');
  const [onlyLarge, setOnlyLarge] = useState(false);
  const [onlyGaps, setOnlyGaps] = useState(false);
  const [sort, setSort] = useState<StepSort>('seq');
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [selected, setSelected] = useState<number | null>(step ? Number(step) : null);
  const flags = [onlyLarge && 'large_result', onlyGaps && 'gap'].filter(Boolean).join(',');
  useEffect(() => setCursor(undefined), [kind, flags, sort]);
  const steps = useApi('runs/:id/steps', id, {
    sort, order: sort === 'seq' ? 'asc' : 'desc', kind: kind || undefined, flags: flags || undefined, limit: 200,
    cursor, around: cursor === undefined && step ? step : undefined,
  });
  const sel = steps.data?.items.find(s => s.seq === selected) ?? null;

  useEffect(() => {
    if (selected === null || !steps.data) return;
    document.getElementById(`step-${selected}`)?.scrollIntoView({ block: 'center' });
  }, [steps.data, selected]);

  const columns: Column<RunStep>[] = [
    { key: 'seq', header: '#', render: s => s.seq, numeric: true },
    { key: 'at', header: 'Čas', render: s => (run ? offset(run.startedAt, s.at) : '') , numeric: true },
    { key: 'step', header: 'Krok', className: 'ellipsis', render: s => (
      <span id={`step-${s.seq}`} style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
        <span className="mono" title={s.summary}>{s.kind === 'tool' ? s.summary : s.kind === 'prompt' ? 'prompt' : 'text'}</span>
        {s.flags.map(f => <FlagChip key={f} flag={f as StepFlag} />)}
      </span>
    ) },
    { key: 'chars', header: 'Znaky', render: s => tokens(s.resultChars), numeric: true },
    { key: 'lat', header: 'Latence', render: s => ms(s.latencyMs), numeric: true },
    { key: 'cost', header: 'Cena tahu', render: s => tokens(s.weighted), numeric: true },
    { key: 'carried', header: 'Nesená', render: s => tokens(s.carriedWeighted), numeric: true },
    { key: 'share', header: 'Podíl', render: s => <ShareBar value={s.carriedWeighted} max={run?.maxCarriedWeighted ?? 1} /> },
  ];

  return (
    <Drawer
      wide
      title={run ? `${run.role} · ${run.taskId ?? 'bez úkolu'}` : 'Běh'}
      subtitle={run ? <span>{tokens(run.weighted)} vážených · {num(run.turns)} tahů · peak {tokens(run.peakContext)} · {duration(run.startedAt, run.endedAt)} · {run.model}</span> : undefined}
      onClose={onClose}
    >
      {!run ? (error ? <ErrorState message={error.message} onRetry={reload} /> : <Loading />) : (
        <>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
            <RunBadge status={run.status} />
            {run.overBudget && <span className="chip flag-error">nad rozpočtem běhu</span>}
            {run.worktreeId && <button className="btn" onClick={() => go('branches', run.worktreeId)}>Větev {run.branch} →</button>}
            {!run.worktreeId && run.branch && <span className="muted">Větev {run.branch} (worktree už neexistuje)</span>}
            {run.taskId && <button className="btn" onClick={() => go('tasks', run.taskId)}>Úkol {run.taskId} →</button>}
            <span className="muted">Výsledky nástrojů {pct(run.toolResultShare * 100)} ceny · {num(run.codeloupeCalls)} volání CodeLoupe · {num(run.gaps)} mezer</span>
          </div>
          <section aria-label="Nesená cena podle nástroje">
            <h2 style={{ marginBottom: 8 }}>Nesená cena podle nástroje</h2>
            <BarList label="Nesená cena podle nástroje" items={run.byTool.slice(0, 8).map(t => ({ name: t.tool, value: t.carriedWeighted, note: `${num(t.calls)}×` }))} />
          </section>
          <div className="filterbar">
            <Segmented<"" | "tool" | "text"> label="Druh kroku" value={kind} onChange={setKind} options={[{ value: '', label: 'Vše' }, { value: 'tool', label: 'Nástroje' }, { value: 'text', label: 'Text' }]} />
            <label className="check"><input type="checkbox" checked={onlyLarge} onChange={e => setOnlyLarge(e.target.checked)} /> Jen velké výsledky</label>
            <label className="check"><input type="checkbox" checked={onlyGaps} onChange={e => setOnlyGaps(e.target.checked)} /> Jen mezery</label>
            <span style={{ flex: 1 }} />
            <span className="muted">Řadit</span>
            <Segmented<StepSort> label="Řazení kroků" value={sort} onChange={setSort} options={STEP_SORTS} />
          </div>
          <div className="card">
            {steps.data ? (
              <>
                <DataTable label="Kroky běhu" rows={steps.data.items} columns={columns} rowKey={s => String(s.seq)} selected={selected === null ? null : String(selected)}
                  onOpen={s => setSelected(s.seq)} empty="Žádný krok neodpovídá filtru." />
                <div className="table-foot">
                  <span>{num(steps.data.items.length)} z {num(steps.data.total)} kroků (běh má {num(run.stepCount)})</span>
                  <span style={{ flex: 1 }} />
                  {steps.data.nextCursor && <button className="btn" onClick={() => setCursor(steps.data!.nextCursor!)}>Další stránka →</button>}
                </div>
              </>
            ) : steps.loading ? <Loading /> : <ErrorState message={steps.error?.message ?? 'Nelze načíst kroky.'} onRetry={steps.reload} />}
          </div>
          {sel && (
            <section className="card" aria-label={`Krok ${sel.seq}`}>
              <div className="card-head"><h2>Krok {sel.seq}</h2><span className="spacer" />{sel.flags.map(f => <FlagChip key={f} flag={f} />)}</div>
              <div className="card-body">
                <dl className="dl">
                  <dt>Vstup</dt><dd className="mono">{sel.summary}</dd>
                  <dt>Nástroj</dt><dd className="mono">{sel.tool ?? sel.kind}</dd>
                  <dt>Výsledek</dt><dd>{num(sel.resultChars)} znaků · latence {ms(sel.latencyMs)}</dd>
                  <dt>Tokeny</dt><dd>input {num(sel.tokens.input)} · cache write {num(sel.tokens.cacheWrite5m + sel.tokens.cacheWrite1h)} · cache read {num(sel.tokens.cacheRead)} · output {num(sel.tokens.output)}</dd>
                  <dt>Cena</dt><dd>{tokens(sel.weighted)} tah · {tokens(sel.carriedWeighted)} nesená do konce běhu</dd>
                </dl>
              </div>
            </section>
          )}
        </>
      )}
    </Drawer>
  );
}
