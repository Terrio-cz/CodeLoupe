import { useEffect, useMemo, useState } from 'react';
import type { ReconcileOutcome } from '../../../shared/actions';
import type { PlanEntry } from '../../../shared/workspaces';
import { bridge, useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { Drawer } from '../components/Drawer';
import { Card, ErrorState, KpiTile, Loading, Search, Section, Select } from '../components/Parts';
import { StatusBadge, VerdictBadge, WorkspaceBadge } from '../components/StatusBadge';
import { ago, bytes, num } from '../format';
import { useSettings } from '../hooks';
import { go, type Route } from '../router';
import { useAction } from '../useAction';
import { confirmable, dockerCounts, joinWorkspaces, matches, stateCounts, type StateFilter, type WorkspaceRow } from '../workspaceModel';

const STATES: { value: StateFilter; label: string }[] = [
  { value: '', label: 'Všechny stavy' }, { value: 'active', label: 'Aktivní' }, { value: 'landed', label: 'Dokončené' },
  { value: 'abandoned', label: 'Opuštěné' }, { value: 'orphan', label: 'Sirotci' }, { value: 'gone', label: 'Chybí v registru' }, { value: 'released', label: 'Uvolněné' },
];

const OUTCOME: Record<string, string> = { removed: 'odstraněno', gone: 'už neexistovalo', blocked: 'používané, zkusí se znovu', failed: 'selhalo, zkusí se znovu', skipped: 'přeskočeno' };

/** Docker resources of a workspace in one cell: `2 kont. · 1 vol. · 1 síť`. */
function dockerCell(row: WorkspaceRow): string {
  const c = dockerCounts(row);
  const parts = [c.containers && `${c.containers} kont.${c.running ? ` (${c.running} běží)` : ''}`, c.volumes && `${c.volumes} vol.`, c.networks && `${c.networks} síť`, c.images && `${c.images} img`].filter(Boolean);
  return parts.length ? parts.join(' · ') : '—';
}

const columns: Column<WorkspaceRow>[] = [
  { key: 'name', header: 'Workspace', render: r => <span className="mono">{r.name}{r.ws?.role === 'main' ? ' · hlavní' : ''}</span> },
  { key: 'repo', header: 'Repo', render: r => r.repoName },
  { key: 'state', header: 'Stav', render: r => <><WorkspaceBadge state={r.state} />{r.release && <span className="chip" style={{ marginLeft: 6 }}>uvolněný</span>}</> },
  { key: 'task', header: 'Úkol', render: r => (r.ws?.taskId ? `${r.ws.taskId}${r.ws.tracker?.state ? ` · ${r.ws.tracker.state}` : ''}` : '—'), className: 'ellipsis' },
  { key: 'merge', header: 'Větev', render: r => (r.ws?.merge ? (r.ws.merge.merged ? 'sloučená' : `↑${r.ws.merge.ahead} nesloučeno`) : '—') },
  { key: 'docker', header: 'Docker', render: dockerCell },
  { key: 'ports', header: 'Porty', render: r => (r.ports.length ? r.ports.map(p => p.allocation.port).join(', ') : '—'), className: 'mono' },
  { key: 'disk', header: 'Disk', render: r => (r.ws?.sizeBytes != null ? bytes(r.ws.sizeBytes) : '—'), numeric: true },
  { key: 'ram', header: 'RAM', render: r => { const m = dockerCounts(r).memoryBytes; return m === null ? '—' : bytes(m); }, numeric: true },
  { key: 'activity', header: 'Aktivita', render: r => ago(r.ws?.lastActivity) },
  { key: 'cleanup', header: 'Úklid', render: r => <CleanupCell row={r} /> },
];

function CleanupCell({ row }: { row: WorkspaceRow }) {
  const auto = row.plan.filter(e => e.verdict === 'auto').length;
  const confirm = confirmable(row).length;
  if (!auto && !confirm && !row.release) return <span className="muted">—</span>;
  return (
    <span>
      {confirm > 0 && <StatusBadge tone="warning">{confirm} čeká na potvrzení</StatusBadge>}
      {auto > 0 && <span className="muted">{confirm > 0 ? ' · ' : ''}{auto} sám</span>}
      {row.release && <span className="muted">{auto || confirm ? ' · ' : ''}uvolněn, zbývá {row.release.pending ?? '?'}{row.release.retrying ? `, opakuje ${row.release.retrying}` : ''}</span>}
    </span>
  );
}

export function Workspaces({ route }: { route: Route }) {
  const [repo, setRepo] = useState('');
  const [state, setState] = useState<StateFilter>('');
  const [q, setQ] = useState('');
  const [sizes, setSizes] = useState(false);
  const [settings] = useSettings();
  const list = useApi('workspaces', undefined, { size: sizes ? 1 : undefined });
  const resources = useApi('resources', undefined, { stats: sizes ? 1 : undefined });
  const plan = useApi('reconcile');
  const releases = useApi('releases');
  const ports = useApi('ports');

  const reloadAll = () => { list.reload(); resources.reload(); plan.reload(); releases.reload(); ports.reload(); };
  const rows = useMemo(
    () => (list.data ? joinWorkspaces(list.data, resources.data, plan.data, releases.data?.items ?? [], ports.data) : []),
    [list.data, resources.data, plan.data, releases.data, ports.data],
  );

  // A release cleans in the background: look again while something of it is left.
  const pendingRelease = (releases.data?.items ?? []).some(r => (r.pending ?? 1) > 0);
  useEffect(() => {
    if (!pendingRelease) return;
    const t = setInterval(reloadAll, 5000);
    return () => clearInterval(t);
  }, [pendingRelease]);

  if (!list.data) return <Card>{list.loading ? <Loading /> : <ErrorState message={list.error?.message ?? 'Nelze načíst workspaces.'} onRetry={list.reload} />}</Card>;

  const repos = list.data.repos.map(r => r.name);
  const shown = rows.filter(r => matches(r, { repo, state, q }));
  // The tiles follow the repository filter, not the state or text ones: they are the breakdown those filters pick from.
  const ofRepo = rows.filter(r => !repo || r.repoName === repo);
  const counts = stateCounts(ofRepo);
  const toConfirm = ofRepo.flatMap(confirmable);
  const selected = route.id ? rows.find(r => r.id === route.id) ?? null : null;
  const released = (releases.data?.items ?? []).filter(r => !repo || r.repo === repo);
  const problems = [...list.data.problems, ...(resources.data?.problems ?? []), ...(plan.data?.problems ?? [])];

  return (
    <>
      <div className="filterbar">
        <Select label="Repozitář" value={repo} onChange={setRepo} options={[{ value: '', label: 'Všechna repa' }, ...repos.map(r => ({ value: r, label: r }))]} />
        <Select label="Stav workspace" value={state} onChange={v => setState(v as StateFilter)} options={STATES} />
        <Search label="Hledat workspace, úkol" value={q} onChange={setQ} />
        <label className="check"><input type="checkbox" checked={sizes} onChange={e => setSizes(e.target.checked)} />Zjistit disk a paměť <span className="muted">(projde soubory a ptá se Dockeru, trvá vteřiny)</span></label>
        {sizes && (list.loading || resources.loading) && <span className="muted" role="status">Zjišťuji…</span>}
      </div>
      {problems.length > 0 && <div className="banner" role="status"><strong>⚠ Něco se nepodařilo přečíst</strong><ul className="plain">{problems.map(p => <li key={p}>{p}</li>)}</ul></div>}

      <section className="card" aria-label="Počty podle stavu">
        <div className="kpis">
          <KpiTile label="Aktivní" value={num(counts.active)} ctx="práce běží" />
          <KpiTile label="Dokončené" value={num(counts.landed)} ctx="práce je na hlavní větvi" />
          <KpiTile label="Opuštěné" value={num(counts.abandoned)} ctx="bez aktivity, nesloučené" />
          <KpiTile label="Sirotci" value={num(counts.orphan)} ctx="adresář bez worktree" />
          <KpiTile label="Čeká na potvrzení" value={num(toConfirm.length)} ctx="prostředky k úklidu" />
          <KpiTile label="Uvolněné" value={num(released.length)} ctx={`zbývá ${num(released.reduce((a, r) => a + (r.pending ?? 0), 0))} prostředků`} />
        </div>
      </section>

      {toConfirm.length > 0 && <ConfirmCard entries={toConfirm} onDone={reloadAll} />}

      <Card bodyClass="">
        <DataTable label="Workspaces" rows={shown} columns={columns} rowKey={r => r.id} selected={route.id}
          onOpen={r => go('workspaces', r.id)} shortcuts={settings?.shortcuts} empty="Žádný workspace neodpovídá filtru." />
      </Card>
      {route.id && (selected
        ? <WorkspaceDrawer row={selected} onClose={() => go('workspaces')} onChanged={reloadAll} />
        : <Drawer title={route.id} onClose={() => go('workspaces')}><ErrorState message="Tento workspace v registru není." /></Drawer>)}
    </>
  );
}

/** Everything waiting for a yes, across workspaces; the yes itself is given in a native dialog that lists what goes. */
function ConfirmCard({ entries, onDone }: { entries: PlanEntry[]; onDone(): void }) {
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const action = useAction<ReconcileOutcome>();
  const keys = entries.map(e => e.key).join('|');
  useEffect(() => setPicked(prev => new Set([...prev].filter(k => entries.some(e => e.key === k)))), [keys]);

  const toggle = (k: string) => setPicked(prev => { const n = new Set(prev); if (!n.delete(k)) n.add(k); return n; });
  const remove = async () => {
    const result = await action.run(() => bridge().actions.reconcileRun({ keys: [...picked] }));
    if (result?.results.length) { setPicked(new Set()); onDone(); }
  };

  return (
    <Card title={`Čeká na potvrzení úklidu (${entries.length})`} bodyClass="" actions={
      <>
        <button className="btn ghost" onClick={() => setPicked(picked.size === entries.length ? new Set() : new Set(entries.map(e => e.key)))}>{picked.size === entries.length ? 'Zrušit výběr' : 'Vybrat vše'}</button>
        <button className="btn primary" disabled={picked.size === 0 || action.busy} onClick={() => void remove()}>{action.busy ? 'Odstraňuji…' : `Potvrdit úklid vybraných (${picked.size})`}</button>
      </>
    }>
      <div className="table-wrap" style={{ maxHeight: 280 }}>
        <table className="data" aria-label="Prostředky čekající na potvrzení">
          <thead><tr><th scope="col"><span className="sr-only">Vybrat</span></th><th scope="col">Druh</th><th scope="col">Prostředek</th><th scope="col">Workspace</th><th scope="col">Proč</th></tr></thead>
          <tbody>
            {entries.map(e => (
              <tr key={e.key}>
                <td><input type="checkbox" checked={picked.has(e.key)} onChange={() => toggle(e.key)} aria-label={`Vybrat ${e.name}`} /></td>
                <td>{e.kind}</td><td className="mono ellipsis" title={e.name}>{e.name}</td><td className="mono">{e.workspace ?? '—'}</td><td className="ellipsis" title={e.reason}>{e.reason}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Outcome outcome={action.outcome} />
    </Card>
  );
}

function Outcome({ outcome }: { outcome: ReconcileOutcome | { ok: boolean; message: string } | null }) {
  if (!outcome) return null;
  const results = 'results' in outcome ? outcome.results : [];
  return (
    <div className={`banner${outcome.ok ? ' info' : ''}`} role="status" style={{ margin: 12 }}>
      {outcome.message}
      {results.length > 0 && <ul className="plain">{results.map(r => <li key={r.key}><span className="mono">{r.name}</span>: {OUTCOME[r.outcome] ?? r.outcome}{r.detail ? ` (${r.detail})` : ''}</li>)}</ul>}
    </div>
  );
}

function WorkspaceDrawer({ row, onClose, onChanged }: { row: WorkspaceRow; onClose(): void; onChanged(): void }) {
  const release = useAction();
  const cleanup = useAction<ReconcileOutcome>();
  const ws = row.ws;
  const waiting = confirmable(row);
  const canRelease = ws?.role === 'worktree' && !row.release;

  const doRelease = async () => {
    const r = await release.run(() => bridge().actions.workspaceRelease({ repo: row.repoPath, path: ws!.path }));
    if (r?.ok) onChanged();
  };
  const doCleanup = async () => {
    const r = await cleanup.run(() => bridge().actions.reconcileRun({ keys: waiting.map(e => e.key) }));
    if (r?.results.length) onChanged();
  };

  return (
    <Drawer title={<span className="mono">{row.name}</span>} subtitle={ws ? <span className="mono">{ws.path}</span> : `${row.repoName}: workspace chybí v registru`} onClose={onClose}>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <WorkspaceBadge state={row.state} />
        {row.release && <span className="chip">uvolněný {ago(row.release.at)}</span>}
        <span className="muted">{row.repoName}</span>
      </div>
      {ws?.note && <div className="banner info" role="note">{ws.note}</div>}

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {canRelease && <button className="btn" disabled={release.busy} onClick={() => void doRelease()}>{release.busy ? 'Uvolňuji…' : 'Uvolnit workspace…'}</button>}
        {waiting.length > 0 && <button className="btn primary" disabled={cleanup.busy} onClick={() => void doCleanup()}>{cleanup.busy ? 'Odstraňuji…' : `Potvrdit úklid (${waiting.length})…`}</button>}
        {ws?.taskId && <button className="btn" onClick={() => go('tasks', ws.taskId)}>Úkol {ws.taskId} →</button>}
      </div>
      <Outcome outcome={release.outcome} />
      <Outcome outcome={cleanup.outcome} />

      <Section title="Souhrn">
        <dl className="dl">
          <dt>Role</dt><dd>{ws ? ({ main: 'hlavní worktree', worktree: 'worktree', directory: 'adresář bez worktree' } as const)[ws.role] : '—'}</dd>
          <dt>Větev</dt><dd className="mono">{ws?.branch ?? '—'}{ws?.head ? ` · ${ws.head.slice(0, 7)}` : ''}</dd>
          <dt>Vůči {ws?.merge?.defaultRef ?? 'hlavní větvi'}</dt>
          <dd>{ws?.merge ? (ws.merge.merged ? 'vše je sloučeno' : `${ws.merge.ahead} ${ws.merge.ahead === 1 ? 'commit' : ws.merge.ahead < 5 ? 'commity' : 'commitů'} nesloučeno`) : '—'}{ws?.merge?.subject ? <div className="muted">{ws.merge.subject}</div> : null}</dd>
          <dt>Úkol</dt><dd>{ws?.taskId ? `${ws.taskId} · ${ws.tracker?.state ?? 'v mirroru není'}${ws.tracker?.resolved ? ' (vyřešený)' : ''}` : '—'}{ws?.tracker?.summary ? <div className="muted">{ws.tracker.summary}</div> : null}</dd>
          <dt>Poslední aktivita</dt><dd>{ago(ws?.lastActivity)}</dd>
          <dt>Disk</dt><dd>{ws?.sizeBytes != null ? bytes(ws.sizeBytes) : 'nezjištěno (zapněte „Zjistit disk a paměť“)'}</dd>
          <dt>Paměť kontejnerů</dt><dd>{dockerCounts(row).memoryBytes !== null ? bytes(dockerCounts(row).memoryBytes!) : dockerCounts(row).running ? 'nezjištěno (zapněte „Zjistit disk a paměť“)' : 'žádný kontejner neběží'}</dd>
        </dl>
      </Section>

      {row.release && (
        <Section title="Uvolnění">
          <dl className="dl">
            <dt>Uvolněn</dt><dd>{ago(row.release.at)}</dd>
            <dt>Zbývá</dt><dd>{row.release.pending === null ? 'daemon se ještě nepodíval' : `${num(row.release.pending)} prostředků`}</dd>
            <dt>Čeká na opakování</dt><dd>{row.release.retrying === null ? '—' : num(row.release.retrying)}</dd>
          </dl>
        </Section>
      )}

      <Section title="Docker prostředky" count={Math.max(row.resources.length, row.plan.length)}>
        {row.plan.length === 0 && row.resources.length === 0 ? <div className="muted">Žádné prostředky, které by CodeLoupe vlastnil nebo adoptoval.</div> : (
          <ul className="rows">
            {(row.plan.length ? row.plan : []).map(e => (
              <li key={e.key} style={{ flexWrap: 'wrap' }}>
                <span className="muted" style={{ width: 70 }}>{e.kind}</span>
                <span className="grow mono" title={e.name}>{e.name}</span>
                <VerdictBadge verdict={e.verdict} />
                <div className="muted" style={{ flexBasis: '100%', paddingLeft: 78 }}>
                  {e.reason}{e.attempts > 0 ? ` · pokusů ${e.attempts}${e.lastError ? `, naposledy: ${e.lastError}` : ''}${e.nextAttempt ? `, další ${ago(e.nextAttempt)}` : ''}` : ''}
                </div>
              </li>
            ))}
            {row.resources.filter(r => !row.plan.some(e => e.name === r.names[0])).map(r => (
              <li key={`${r.kind}:${r.id}`}><span className="muted" style={{ width: 70 }}>{r.kind}</span><span className="grow mono">{r.names[0]}</span><span className="muted">{r.state ?? ''}</span></li>
            ))}
          </ul>
        )}
      </Section>

      <Section title="Porty" count={row.ports.length}>
        {row.ports.length === 0 ? <div className="muted">Žádný zapsaný port.</div> : (
          <ul className="rows">
            {row.ports.map(p => (
              <li key={p.allocation.port}>
                <span className="mono">{p.allocation.port}</span><span className="grow">{p.allocation.name}</span>
                <StatusBadge tone={p.state === 'conflict' ? 'critical' : p.state === 'in-use' ? 'running' : 'neutral'}>{p.state === 'free' ? 'volný' : p.state === 'in-use' ? 'používá workspace' : 'koliduje'}</StatusBadge>
                {p.usedBy && <span className="muted">{p.usedBy}</span>}
              </li>
            ))}
          </ul>
        )}
      </Section>
    </Drawer>
  );
}
