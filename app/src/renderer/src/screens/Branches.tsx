import { useState } from 'react';
import type { LayerState, WorktreeSummary } from '../../../shared/contract';
import { bridge, useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { Drawer } from '../components/Drawer';
import { Icon } from '../components/Icon';
import { Card, ErrorState, Loading, Search, Section, Select } from '../components/Parts';
import { ChangeMark, LayerBadge, StatusBadge, taskTone } from '../components/StatusBadge';
import { ago, num, tokens } from '../format';
import { useDebounced, useSettings } from '../hooks';
import { go, type Route } from '../router';

const columns: Column<WorktreeSummary>[] = [
  { key: 'branch', header: 'Branch', render: w => <span className="mono">{w.branch ?? '(detached)'}{w.isMain ? ' · main' : ''}</span> },
  { key: 'repo', header: 'Repo', render: w => w.repoName },
  { key: 'task', header: 'Task', render: w => w.taskId ?? '—' },
  { key: 'base', header: 'Base', render: w => <span title={`${w.ahead} ${w.ahead === 1 ? 'commit' : 'commits'} ahead, ${w.behind} behind`}>↑{w.ahead} ↓{w.behind}</span>, numeric: true },
  { key: 'files', header: 'Files', render: w => num(w.changedFiles), numeric: true },
  { key: 'decls', header: 'Declarations', render: w => num(w.changedDecls), numeric: true },
  { key: 'layer', header: 'Layer', render: w => <LayerBadge state={w.layer} /> },
  { key: 'activity', header: 'Activity', render: w => ago(w.lastActivityAt) },
  { key: 'queries', header: 'Queries 24h', render: w => num(w.queries24h), numeric: true },
];

const LAYERS: { value: LayerState | ''; label: string }[] = [
  { value: '', label: 'All layers' }, { value: 'fresh', label: 'Fresh' }, { value: 'stale', label: 'Stale' },
  { value: 'building', label: 'Parsing' }, { value: 'error', label: 'Error' },
];

export function Branches({ route }: { route: Route }) {
  const [repo, setRepo] = useState('');
  const [layer, setLayer] = useState('');
  const [q, setQ] = useState('');
  const [settings] = useSettings();
  const query = useDebounced(q);
  const { data, error, loading, reload } = useApi('worktrees', undefined, { repo, layer, q: query });
  const repos = [...new Map((data?.items ?? []).map(w => [w.repoId, w.repoName])).entries()];

  return (
    <>
      <div className="filterbar">
        <Select label="Repository" value={repo} onChange={setRepo} options={[{ value: '', label: 'All repos' }, ...repos.map(([id, name]) => ({ value: id, label: name }))]} />
        <Select label="Layer status" value={layer} onChange={setLayer} options={LAYERS} />
        <Search label="Search branch, task" value={q} onChange={setQ} />
      </div>
      <Card bodyClass="">
        {data ? (
          <DataTable label="Branches and worktrees" rows={data.items} columns={columns} rowKey={w => w.id} selected={route.id}
            onOpen={w => go('branches', w.id)} shortcuts={settings?.shortcuts} empty="No worktree matches the filter." />
        ) : loading ? <Loading variant="table" /> : <ErrorState message={error?.message ?? 'Could not load branches.'} onRetry={reload} />}
      </Card>
      {route.id && <BranchDrawer id={route.id} onClose={() => go('branches')} />}
    </>
  );
}

function BranchDrawer({ id, onClose }: { id: string; onClose(): void }) {
  const { data: w, error, reload } = useApi('worktrees/:id', id);
  // The costliest runs of the branch's task, from the transcripts: where the cost of the work went.
  const runs = useApi(w?.taskId ? 'runs' : null, undefined, { q: w?.taskId ?? undefined, range: '30d', sort: 'weighted', limit: 5 });
  const [opened, setOpened] = useState<boolean | null>(null);
  const LIMIT = 20;
  const [more, setMore] = useState(false);

  return (
    <Drawer
      title={<span className="mono">{w?.branch ?? id}</span>}
      subtitle={w ? <span className="mono">{w.path}</span> : undefined}
      onClose={onClose}
    >
      {!w ? (error ? <ErrorState message={error.message} onRetry={reload} /> : <Loading />) : (
        <>
          <div className="drawer-summary">
            <LayerBadge state={w.layer} />
            <span>↑{w.ahead} ↓{w.behind} from <span className="mono">{w.baseRef}</span> (merge-base <span className="mono">{w.mergeBase}</span>)</span>
          </div>
          <div className="actions">
            {(w.task ?? w.taskId) && <button className="btn" onClick={() => go('tasks', w.task?.id ?? w.taskId)}>Task {w.task?.id ?? w.taskId}<Icon name="arrowRight" size={14} /></button>}
            <button className="btn" onClick={() => void bridge().open.worktree(w.id).then(setOpened)}><Icon name="folder" size={14} />Open folder</button>
            {opened === false && <span className="t2" role="status">Cannot open the folder (it does not exist or is not a git worktree).</span>}
          </div>

          {(w.task || w.taskId) && (
            <Section title="Task">
              <ul className="rows"><li>
                <button className="link" onClick={() => go('tasks', w.task?.id ?? w.taskId)}>{w.task?.id ?? w.taskId}</button>
                {w.task ? (
                  <>
                    <span className="grow">{w.task.summary}</span>
                    <StatusBadge tone={taskTone(w.task.state)}>{w.task.state}</StatusBadge>
                  </>
                ) : <span className="grow muted">not in the YouTrack mirror yet</span>}
              </li></ul>
            </Section>
          )}

          {w.taskId && (
            <Section title="Agent runs on the task" count={runs.data?.total}>
              {!runs.data ? (runs.error ? <div className="muted">{runs.error.message}</div> : <div className="muted">Loading…</div>) : runs.data.items.length === 0 ? <div className="muted">No run in the last 30 days mentions this task.</div> : (
                <ul className="rows">
                  {runs.data.items.map(r => (
                    <li key={r.id}>
                      <button className="link mono" onClick={() => go('runs', r.id)}>{r.role}</button>
                      <span className="grow" title={r.title}>{r.title}</span>
                      <span className="muted num">{tokens(r.weighted)}</span>
                    </li>
                  ))}
                </ul>
              )}
              {runs.data && runs.data.total > runs.data.items.length && <button className="link" onClick={() => go('runs', null, { q: w.taskId! })}>All runs of the task ({runs.data.total}) →</button>}
            </Section>
          )}

          <Section title="Changed declarations" count={w.changes.length}>
            <ul className="rows">
              {(more ? w.changes : w.changes.slice(0, LIMIT)).map((c, i) => (
                <li key={i}>
                  <ChangeMark change={c.change} />
                  <span className="muted">{c.kind}</span>
                  <span className="grow mono" title={`${c.path}${c.line ? `:${c.line}` : ''}`}>{c.fqn}</span>
                  <span className="muted num">{c.callers} {c.callers === 1 ? 'caller' : 'callers'}</span>
                </li>
              ))}
            </ul>
            {!more && w.changes.length > LIMIT && <button className="link" onClick={() => setMore(true)}>Show {w.changes.length - LIMIT} more</button>}
          </Section>

          <Section title="Affected callers" count={w.callers.length}>
            <ul className="rows">
              {w.callers.slice(0, 40).map((c, i) => (
                <li key={i}>
                  <span className="grow mono" title={`${c.path}:${c.line}`}>{c.fqn}</span>
                  <span className="muted mono">→ {c.calls.split('.').slice(-2).join('.')}</span>
                  <span className="chip">{c.exact ? 'exact' : 'candidate'}</span>
                </li>
              ))}
            </ul>
          </Section>

          <Section title="Tests" count={w.tests.length}>
            <ul className="rows">
              {w.tests.map((t, i) => (
                <li key={i}>
                  <span className="grow mono">{t.path}</span>
                  <span className="muted">{t.reason === 'touched' ? 'changed' : 'calls changed code'}</span>
                </li>
              ))}
            </ul>
          </Section>

          <Section title="Layer index">
            <dl className="dl">
              <dt>Files in layer</dt><dd>{num(w.index.layerFiles)}</dd>
              <dt>Parsed</dt><dd>{ago(w.index.parsedAt)}</dd>
              <dt>Files with errors</dt><dd>{w.index.errorFiles.length ? w.index.errorFiles.map(f => <div key={f} className="mono">{f}</div>) : '0'}</dd>
            </dl>
          </Section>
        </>
      )}
    </Drawer>
  );
}
