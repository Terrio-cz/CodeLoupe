import { useEffect, useMemo, useState } from 'react';
import type { ReconcileOutcome } from '../../../shared/actions';
import type { PlanEntry } from '../../../shared/workspaces';
import { bridge, useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { Drawer } from '../components/Drawer';
import { CountUp } from '../components/CountUp';
import { Banner, Card, ErrorState, KpiTile, Loading, Search, Section, Select } from '../components/Parts';
import { StatusBadge, VerdictBadge, WorkspaceBadge } from '../components/StatusBadge';
import { ago, bytes, num } from '../format';
import { useSettings } from '../hooks';
import { go, type Route } from '../router';
import { useAction } from '../useAction';
import { confirmable, dockerCounts, joinWorkspaces, matches, stateCounts, type StateFilter, type WorkspaceRow } from '../workspaceModel';

const STATES: { value: StateFilter; label: string }[] = [
  { value: '', label: 'All states' }, { value: 'active', label: 'Active' }, { value: 'landed', label: 'Landed' },
  { value: 'abandoned', label: 'Abandoned' }, { value: 'orphan', label: 'Orphans' }, { value: 'gone', label: 'Missing from registry' }, { value: 'released', label: 'Released' },
];

const OUTCOME: Record<string, string> = { removed: 'removed', gone: 'no longer existed', blocked: 'in use, will retry', failed: 'failed, will retry', skipped: 'skipped' };

/** Docker resources and ports of a workspace in one cell: `2 cont. · 1 vol. · 1 net`, the ports on a second line. */
function DockerCell({ row }: { row: WorkspaceRow }) {
  const c = dockerCounts(row);
  const parts = [c.containers && `${c.containers} cont.${c.running ? ` (${c.running} running)` : ''}`, c.volumes && `${c.volumes} vol.`, c.networks && `${c.networks} net`, c.images && `${c.images} img`].filter(Boolean);
  const ports = row.ports.map(p => p.allocation.port).join(', ');
  if (!parts.length && !ports) return <span className="muted">—</span>;
  return (
    <>
      {parts.length ? parts.join(' · ') : <span className="muted">no resources</span>}
      {ports && <div className="muted mono" title="Registered ports">ports {ports}</div>}
    </>
  );
}

/**
 * The list keeps to what tells workspaces apart; the repository column appears only while more than one is shown.
 * Disk and RAM are known only after "Measure disk and memory"; until then their columns would be all dashes.
 */
function columns(sizes: boolean, repoColumn: boolean): Column<WorkspaceRow>[] {
  return [
    { key: 'name', header: 'Workspace', render: r => <span className="mono">{r.name}{r.ws?.role === 'main' ? ' · main' : ''}</span> },
    ...(repoColumn ? [{ key: 'repo', header: 'Repo', render: (r: WorkspaceRow) => r.repoName }] : []),
    { key: 'state', header: 'State', render: r => <WorkspaceBadge state={r.state} /> },
    { key: 'task', header: 'Task', render: r => (r.ws?.taskId ? <span title={r.ws.tracker?.summary}>{r.ws.taskId}{r.ws.tracker?.state ? ` · ${r.ws.tracker.state}` : ''}</span> : '—'), className: 'ellipsis narrow' },
    { key: 'merge', header: 'Branch', render: r => (r.ws?.merge ? (r.ws.merge.merged ? 'merged' : `↑${r.ws.merge.ahead} unmerged`) : '—') },
    { key: 'docker', header: 'Docker and ports', render: r => <DockerCell row={r} />, className: 'wrap-cell' },
    ...(sizes ? [
      { key: 'disk', header: 'Disk', render: (r: WorkspaceRow) => (r.ws?.sizeBytes != null ? bytes(r.ws.sizeBytes) : '—'), numeric: true },
      { key: 'ram', header: 'RAM', render: (r: WorkspaceRow) => { const m = dockerCounts(r).memoryBytes; return m === null ? '—' : bytes(m); }, numeric: true },
    ] : []),
    { key: 'activity', header: 'Activity', render: r => ago(r.ws?.lastActivity) },
    { key: 'cleanup', header: 'Cleanup', render: r => <CleanupCell row={r} />, className: 'wrap-cell' },
  ];
}

function CleanupCell({ row }: { row: WorkspaceRow }) {
  const auto = row.plan.filter(e => e.verdict === 'auto').length;
  const confirm = confirmable(row).length;
  if (!auto && !confirm && !row.release) return <span className="muted">—</span>;
  return (
    <span>
      {confirm > 0 && <StatusBadge tone="warning">{confirm} awaiting confirmation</StatusBadge>}
      {auto > 0 && <span className="muted">{confirm > 0 ? ' · ' : ''}{auto} automatic</span>}
      {row.release && <span className="muted">{auto || confirm ? ' · ' : ''}released, {row.release.pending ?? '?'} left{row.release.retrying ? `, ${row.release.retrying} retrying` : ''}</span>}
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
  // Each of these makes the daemon read git or Docker again (about a second): the registry goes first and shows the table,
  // the rest follows and fills in what each workspace holds.
  const next = list.data !== null;
  const resources = useApi(next ? 'resources' : null, undefined, { stats: sizes ? 1 : undefined });
  const plan = useApi(next ? 'reconcile' : null);
  const releases = useApi(next ? 'releases' : null);
  const ports = useApi(next ? 'ports' : null);

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

  if (!list.data) return <Card bodyClass="">{list.loading ? <Loading variant="table" /> : <ErrorState message={list.error?.message ?? 'Could not load workspaces.'} onRetry={list.reload} />}</Card>;

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
        <Select label="Repository" value={repo} onChange={setRepo} options={[{ value: '', label: 'All repos' }, ...repos.map(r => ({ value: r, label: r }))]} />
        <Select label="Workspace state" value={state} onChange={v => setState(v as StateFilter)} options={STATES} />
        <Search label="Search workspace, task" value={q} onChange={setQ} />
        <label className="check"><input type="checkbox" checked={sizes} onChange={e => setSizes(e.target.checked)} />Measure disk and memory <span className="muted">(walks files and asks Docker, takes seconds)</span></label>
        {sizes && (list.loading || resources.loading) && <span className="muted" role="status">Measuring…</span>}
      </div>
      {problems.length > 0 && <Banner><strong>Some data could not be read</strong><ul className="plain">{problems.map(p => <li key={p}>{p}</li>)}</ul></Banner>}

      <section aria-label="Counts by state">
        <div className="kpis">
          <KpiTile label="Active" value={<CountUp value={counts.active} format={num} />} ctx="work in progress" />
          <KpiTile label="Landed" value={<CountUp value={counts.landed} format={num} />} ctx="work is on the main branch" />
          <KpiTile label="Abandoned" value={<CountUp value={counts.abandoned} format={num} />} ctx="inactive, unmerged" />
          <KpiTile label="Orphans" value={<CountUp value={counts.orphan} format={num} />} ctx="directory without worktree" />
          <KpiTile label="To confirm" value={<CountUp value={toConfirm.length} format={num} />} ctx="resources to clean up" />
          <KpiTile label="Released" value={<CountUp value={released.length} format={num} />} ctx={(left => `${num(left)} ${left === 1 ? 'resource' : 'resources'} left`)(released.reduce((a, r) => a + (r.pending ?? 0), 0))} />
        </div>
      </section>

      {toConfirm.length > 0 && <ConfirmCard entries={toConfirm} planHash={plan.data?.planHash} onDone={reloadAll} />}

      <Card bodyClass="">
        <DataTable label="Workspaces" rows={shown} columns={columns(sizes, !repo && repos.length > 1)} rowKey={r => r.id} selected={route.id}
          onOpen={r => go('workspaces', r.id)} shortcuts={settings?.shortcuts} empty="No workspace matches the filter." />
      </Card>
      {route.id && (selected
        ? <WorkspaceDrawer row={selected} planHash={plan.data?.planHash} onClose={() => go('workspaces')} onChanged={reloadAll} />
        : <Drawer title={route.id} onClose={() => go('workspaces')}><ErrorState title="Workspace not found" message="This workspace is not in the registry." /></Drawer>)}
    </>
  );
}

/** Everything waiting for a yes, across workspaces; the yes itself is given in a native dialog that lists what goes. */
function ConfirmCard({ entries, planHash, onDone }: { entries: PlanEntry[]; planHash: string | undefined; onDone(): void }) {
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const action = useAction<ReconcileOutcome>();
  const keys = entries.map(e => e.key).join('|');
  useEffect(() => setPicked(prev => new Set([...prev].filter(k => entries.some(e => e.key === k)))), [keys]);

  const toggle = (k: string) => setPicked(prev => { const n = new Set(prev); if (!n.delete(k)) n.add(k); return n; });
  const remove = async () => {
    const result = await action.run(() => bridge().actions.reconcileRun({ keys: [...picked], planHash }));
    if (result?.results.length) { setPicked(new Set()); onDone(); }
  };

  return (
    <Card title={`Cleanup awaiting confirmation (${entries.length})`} bodyClass="" actions={
      <>
        <button className="btn ghost" onClick={() => setPicked(picked.size === entries.length ? new Set() : new Set(entries.map(e => e.key)))}>{picked.size === entries.length ? 'Clear selection' : 'Select all'}</button>
        <button className="btn primary" disabled={picked.size === 0 || action.busy} onClick={() => void remove()}>{action.busy ? 'Removing…' : `Clean up selected (${picked.size})`}</button>
      </>
    }>
      <div className="table-wrap" style={{ maxHeight: 280 }}>
        <table className="data" aria-label="Resources awaiting confirmation">
          <thead><tr><th scope="col"><span className="sr-only">Select</span></th><th scope="col">Kind</th><th scope="col">Resource</th><th scope="col">Workspace</th><th scope="col">Reason</th></tr></thead>
          <tbody>
            {entries.map(e => (
              <tr key={e.key}>
                <td><input type="checkbox" checked={picked.has(e.key)} onChange={() => toggle(e.key)} aria-label={`Select ${e.name}`} /></td>
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

function WorkspaceDrawer({ row, planHash, onClose, onChanged }: { row: WorkspaceRow; planHash: string | undefined; onClose(): void; onChanged(): void }) {
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
    const r = await cleanup.run(() => bridge().actions.reconcileRun({ keys: waiting.map(e => e.key), planHash }));
    if (r?.results.length) onChanged();
  };

  return (
    <Drawer title={<span className="mono">{row.name}</span>} subtitle={ws ? <span className="mono">{ws.path}</span> : `${row.repoName}: workspace missing from registry`} onClose={onClose}>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <WorkspaceBadge state={row.state} />
        {row.release && <span className="chip">released {ago(row.release.at)}</span>}
        <span className="muted">{row.repoName}</span>
      </div>
      {ws?.note && <Banner tone="info" role="note">{ws.note}</Banner>}

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {canRelease && <button className="btn" disabled={release.busy} onClick={() => void doRelease()}>{release.busy ? 'Releasing…' : 'Release workspace…'}</button>}
        {waiting.length > 0 && <button className="btn primary" disabled={cleanup.busy} onClick={() => void doCleanup()}>{cleanup.busy ? 'Removing…' : `Confirm cleanup (${waiting.length})…`}</button>}
        {ws?.taskId && <button className="btn" onClick={() => go('tasks', ws.taskId)}>Task {ws.taskId} →</button>}
      </div>
      <Outcome outcome={release.outcome} />
      <Outcome outcome={cleanup.outcome} />

      <Section title="Summary">
        <dl className="dl">
          <dt>Role</dt><dd>{ws ? ({ main: 'main worktree', worktree: 'worktree', directory: 'directory without worktree' } as const)[ws.role] : '—'}</dd>
          <dt>Branch</dt><dd className="mono">{ws?.branch ?? '—'}{ws?.head ? ` · ${ws.head.slice(0, 7)}` : ''}</dd>
          <dt>Against {ws?.merge?.defaultRef ?? 'main branch'}</dt>
          <dd>{ws?.merge ? (ws.merge.merged ? 'everything is merged' : `${ws.merge.ahead} ${ws.merge.ahead === 1 ? 'commit' : 'commits'} unmerged`) : '—'}{ws?.merge?.subject ? <div className="muted">{ws.merge.subject}</div> : null}</dd>
          <dt>Task</dt><dd>{ws?.taskId ? `${ws.taskId} · ${ws.tracker?.state ?? 'not in mirror'}${ws.tracker?.resolved ? ' (resolved)' : ''}` : '—'}{ws?.tracker?.summary ? <div className="muted">{ws.tracker.summary}</div> : null}</dd>
          <dt>Last activity</dt><dd>{ago(ws?.lastActivity)}</dd>
          <dt>Disk</dt><dd>{ws?.sizeBytes != null ? bytes(ws.sizeBytes) : 'not measured (turn on "Measure disk and memory")'}</dd>
          <dt>Container memory</dt><dd>{dockerCounts(row).memoryBytes !== null ? bytes(dockerCounts(row).memoryBytes!) : dockerCounts(row).running ? 'not measured (turn on "Measure disk and memory")' : 'no container running'}</dd>
        </dl>
      </Section>

      {row.release && (
        <Section title="Release">
          <dl className="dl">
            <dt>Released</dt><dd>{ago(row.release.at)}</dd>
            <dt>Left</dt><dd>{row.release.pending === null ? 'daemon has not checked yet' : `${num(row.release.pending)} ${row.release.pending === 1 ? 'resource' : 'resources'}`}</dd>
            <dt>Awaiting retry</dt><dd>{row.release.retrying === null ? '—' : num(row.release.retrying)}</dd>
          </dl>
        </Section>
      )}

      <Section title="Docker resources" count={Math.max(row.resources.length, row.plan.length)}>
        {row.plan.length === 0 && row.resources.length === 0 ? <div className="muted">No resources owned or adopted by CodeLoupe.</div> : (
          <ul className="rows">
            {row.plan.map(e => (
              <li key={e.key} className="stacked">
                <span className="muted">{e.kind}</span>
                <span className="grow mono" title={e.name}>{e.name}</span>
                <VerdictBadge verdict={e.verdict} />
                <div className="sub muted">
                  {e.reason}{e.attempts > 0 ? ` · ${e.attempts} ${e.attempts === 1 ? 'attempt' : 'attempts'}${e.lastError ? `, last: ${e.lastError}` : ''}${e.nextAttempt ? `, next ${ago(e.nextAttempt)}` : ''}` : ''}
                </div>
              </li>
            ))}
            {row.resources.filter(r => !row.plan.some(e => e.name === r.names[0])).map(r => (
              <li key={`${r.kind}:${r.id}`} className="stacked"><span className="muted">{r.kind}</span><span className="grow mono">{r.names[0]}</span><span className="muted">{r.state ?? ''}</span></li>
            ))}
          </ul>
        )}
      </Section>

      <Section title="Ports" count={row.ports.length}>
        {row.ports.length === 0 ? <div className="muted">No registered port.</div> : (
          <ul className="rows">
            {row.ports.map(p => (
              <li key={p.allocation.port}>
                <span className="mono">{p.allocation.port}</span><span className="grow">{p.allocation.name}</span>
                <StatusBadge tone={p.state === 'conflict' ? 'critical' : p.state === 'in-use' ? 'running' : 'neutral'}>{p.state === 'free' ? 'free' : p.state === 'in-use' ? 'used by workspace' : 'conflict'}</StatusBadge>
                {p.usedBy && <span className="muted">{p.usedBy}</span>}
              </li>
            ))}
          </ul>
        )}
      </Section>
    </Drawer>
  );
}
