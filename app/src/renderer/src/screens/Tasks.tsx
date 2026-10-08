import { Fragment, useState } from 'react';
import type { TaskSummary } from '../../../shared/contract';
import { bridge, useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { allowedOrigins, MarkdownView } from '../components/MarkdownView';
import { Icon } from '../components/Icon';
import { Card, ErrorState, Loading, Search, Section, Select } from '../components/Parts';
import { LayerBadge, StatusBadge, taskTone } from '../components/StatusBadge';
import { ago, dateTime, num } from '../format';
import { useDebounced, useSettings } from '../hooks';
import { go, type Route } from '../router';

const PAGE = 50;

const columns: Column<TaskSummary>[] = [
  { key: 'id', header: 'ID', render: t => <span className="mono">{t.id}</span> },
  { key: 'summary', header: 'Title', render: t => t.summary, className: 'ellipsis' },
  { key: 'state', header: 'Status', render: t => <StatusBadge tone={taskTone(t.state)}>{t.state}</StatusBadge> },
  { key: 'priority', header: 'Priority', render: t => t.priority ?? '—' },
  { key: 'branches', header: 'Branches', render: t => num(t.worktreeIds.length), numeric: true },
  { key: 'reads', header: 'Reads', render: t => num(t.reads), numeric: true },
  { key: 'updated', header: 'Updated', render: t => ago(t.updatedAt) },
];

export function Tasks({ route }: { route: Route }) {
  if (route.id) return <TaskDetailView id={route.id} />;
  return <TaskList />;
}

function TaskList() {
  const [project, setProject] = useState('');
  const [state, setState] = useState('');
  const [q, setQ] = useState('');
  const [settings] = useSettings();
  const query = useDebounced(q);
  // Cursors are opaque (spec § 9.1): keep the ones seen so far, reset together with the filter.
  const filterKey = JSON.stringify([project, state, query]);
  const [pages, setPages] = useState<{ key: string; stack: string[] }>({ key: filterKey, stack: [] });
  const stack = pages.key === filterKey ? pages.stack : [];
  const cursor = stack[stack.length - 1];
  const { data, error, loading, reload } = useApi('tasks', undefined, { project, state, q: query, limit: PAGE, cursor });
  const first = stack.length * PAGE;
  return (
    <>
      <div className="filterbar">
        <Select label="Project" value={project} onChange={setProject} options={[{ value: '', label: 'All projects' }, { value: 'TER', label: 'TER' }, { value: 'CL', label: 'CL' }]} />
        <Select label="Status" value={state} onChange={setState} options={['', 'To do', 'In Progress', 'Ready for testing', 'Done'].map(s => ({ value: s, label: s || 'All statuses' }))} />
        <Search label="Search tasks" value={q} onChange={setQ} />
        <span style={{ flex: 1 }} />
        {data && <span className="chip">mirror synced {ago(data.mirrorSyncedAt)}</span>}
      </div>
      <Card bodyClass="">
        {data ? (
          <>
            <DataTable label="Tasks" rows={data.items} columns={columns} rowKey={t => t.id} onOpen={t => go('tasks', t.id)} shortcuts={settings?.shortcuts} empty="No task matches the filter." />
            <div className="table-foot">
              <span>{num(data.items.length ? first + 1 : 0)}–{num(first + data.items.length)} of {num(data.total)}</span>
              <span style={{ flex: 1 }} />
              <button className="btn" disabled={stack.length === 0} onClick={() => setPages({ key: filterKey, stack: stack.slice(0, -1) })}><Icon name="arrowLeft" size={14} />Previous</button>
              <button className="btn" disabled={!data.nextCursor} onClick={() => data.nextCursor && setPages({ key: filterKey, stack: [...stack, data.nextCursor] })}>Next<Icon name="arrowRight" size={14} /></button>
            </div>
          </>
        ) : loading ? <Loading variant="table" /> : <ErrorState message={error?.message ?? 'Could not load tasks.'} onRetry={reload} />}
      </Card>
    </>
  );
}

function TaskDetailView({ id }: { id: string }) {
  const { data: t, error, reload } = useApi('tasks/:id', id);
  const settings = useApi('settings');
  const allowed = allowedOrigins((settings.data?.youtrack ?? []).map(y => y.url));
  const [openFailed, setOpenFailed] = useState(false);
  if (!t) return <Card>{error ? <ErrorState title={`Could not load task ${id}`} message={error.message} onRetry={reload} action={<button className="btn ghost" onClick={() => go('tasks')}>Back to tasks</button>} /> : <Loading />}</Card>;
  const done = t.criteria.filter(c => c.checked).length;
  return (
    <>
      <nav aria-label="Breadcrumb" className="crumbs muted">
        <button className="link" onClick={() => go('tasks')}>Tasks</button>
        <Icon name="chevron" size={12} />
        <span className="mono">{t.id}</span>
      </nav>
      <div className="task-layout">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16, minWidth: 0 }}>
          <Card>
            <h1 className="task-title">{t.summary}</h1>
            <button className="link" onClick={() => void bridge().open.external(t.url).then(ok => setOpenFailed(!ok))} style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>Open in YouTrack<Icon name="external" size={13} /></button>
            {openFailed && <span className="t2" role="status" style={{ marginLeft: 8 }}>The link does not point to a configured YouTrack instance, so it was not opened.</span>}
            <div style={{ marginTop: 12 }}><MarkdownView source={t.description} allowed={allowed} /></div>
          </Card>
          <Card title={`Acceptance criteria ${done}/${t.criteria.length}`}>
            {t.criteria.length > 0 && (
              <div className="progress" aria-hidden="true"><span style={{ width: `${(done / t.criteria.length) * 100}%` }} /></div>
            )}
            <ul className="rows">
              {t.criteria.map((c, i) => (
                <li key={i}>
                  <span role="img" aria-label={c.checked ? 'met' : 'not met'} className={`criterion${c.checked ? ' done' : ''}`}>{c.checked && <Icon name="check" size={12} />}</span>
                  <span className="grow">{c.text}</span>
                </li>
              ))}
            </ul>
          </Card>
          <Card title="Links">
            <ul className="rows">
              {t.links.map(l => <li key={l.id}><span className="muted">{l.type}</span><span className="mono">{l.id}</span><span className="grow">{l.summary}</span></li>)}
            </ul>
          </Card>
          <Card title="Activity">
            <ol className="activity">
              {t.activity.map((a, i) => (
                <li key={i}>
                  <span className="tick" aria-hidden="true" />
                  <div>
                    <div className="muted">{dateTime(a.at)} · {a.author}</div>
                    {a.kind === 'comment' ? <MarkdownView source={a.text} allowed={allowed} /> : <div>{a.text}</div>}
                  </div>
                </li>
              ))}
            </ol>
          </Card>
        </div>
        <aside className="card" aria-label="Properties">
          <div className="card-head"><h2>Properties</h2></div>
          <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <dl className="dl">
              {t.fields.map(f => <Fragment key={f.name}><dt>{f.name}</dt><dd>{f.name === 'State' ? <StatusBadge tone={taskTone(f.value)}>{f.value}</StatusBadge> : f.value}</dd></Fragment>)}
              <dt>Updated</dt><dd>{ago(t.updatedAt)}</dd>
            </dl>
            <Section title="Branches" count={t.worktrees.length}>
              <ul className="rows">
                {t.worktrees.map(w => (
                  <li key={w.id}><button className="link mono" onClick={() => go('branches', w.id)}>{w.branch}</button><span className="grow" /><LayerBadge state={w.layer} /></li>
                ))}
              </ul>
            </Section>
            <Section title="Mirror">
              <dl className="dl">
                <dt>Synced</dt><dd>{ago(t.mirror.syncedAt)}</dd>
                <dt>Mirror reads</dt><dd>{num(t.reads)} · last {ago(t.mirror.lastReadAt)}</dd>
              </dl>
            </Section>
          </div>
        </aside>
      </div>
    </>
  );
}
