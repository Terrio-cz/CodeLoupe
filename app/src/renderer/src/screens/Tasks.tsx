import { Fragment, useState } from 'react';
import type { TaskSummary } from '../../../shared/contract';
import { bridge, useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { MarkdownView } from '../components/MarkdownView';
import { Card, ErrorState, Loading, Search, Section, Select } from '../components/Parts';
import { LayerBadge, RunBadge, StatusBadge, taskTone } from '../components/StatusBadge';
import { ago, dateTime, num, tokens } from '../format';
import { useSettings } from '../hooks';
import { go, type Route } from '../router';

const columns: Column<TaskSummary>[] = [
  { key: 'id', header: 'ID', render: t => <span className="mono">{t.id}</span> },
  { key: 'summary', header: 'Název', render: t => t.summary, className: 'ellipsis' },
  { key: 'state', header: 'Stav', render: t => <StatusBadge tone={taskTone(t.state)}>{t.state}</StatusBadge> },
  { key: 'priority', header: 'Priorita', render: t => t.priority ?? '—' },
  { key: 'branches', header: 'Větve', render: t => num(t.worktreeIds.length), numeric: true },
  { key: 'runs', header: 'Běhy', render: t => num(t.runs), numeric: true },
  { key: 'reads', header: 'Čtení', render: t => num(t.reads), numeric: true },
  { key: 'updated', header: 'Aktualizováno', render: t => ago(t.updatedAt) },
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
  const { data, error, loading, reload } = useApi('tasks', undefined, { project, state, q, limit: 200 });
  return (
    <>
      <div className="filterbar">
        <Select label="Projekt" value={project} onChange={setProject} options={[{ value: '', label: 'Všechny projekty' }, { value: 'TER', label: 'TER' }, { value: 'CL', label: 'CL' }]} />
        <Select label="Stav" value={state} onChange={setState} options={['', 'To do', 'In Progress', 'Ready for testing', 'Done'].map(s => ({ value: s, label: s || 'Všechny stavy' }))} />
        <Search label="Hledat úkol" value={q} onChange={setQ} />
        <span style={{ flex: 1 }} />
        {data && <span className="muted">mirror synchronizován {ago(data.mirrorSyncedAt)}</span>}
      </div>
      <Card bodyClass="">
        {data ? (
          <DataTable label="Úkoly" rows={data.items} columns={columns} rowKey={t => t.id} onOpen={t => go('tasks', t.id)} shortcuts={settings?.shortcuts} empty="Žádný úkol neodpovídá filtru." />
        ) : loading ? <Loading /> : <ErrorState message={error?.message ?? 'Nelze načíst úkoly.'} onRetry={reload} />}
      </Card>
    </>
  );
}

function TaskDetailView({ id }: { id: string }) {
  const { data: t, error, reload } = useApi('tasks/:id', id);
  if (!t) return <Card>{error ? <ErrorState message={error.message} onRetry={reload} /> : <Loading />}</Card>;
  const done = t.criteria.filter(c => c.checked).length;
  return (
    <>
      <nav aria-label="Drobečková navigace" className="muted">
        <button className="link" onClick={() => go('tasks')}>← Úkoly</button> / <span className="mono">{t.id}</span>
      </nav>
      <div className="task-layout">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16, minWidth: 0 }}>
          <Card>
            <h1 style={{ fontSize: 18, marginBottom: 6 }}>{t.summary}</h1>
            <button className="link" onClick={() => void bridge().open.external(t.url)}>Otevřít v YouTracku ↗</button>
            <div style={{ marginTop: 12 }}><MarkdownView source={t.description} /></div>
          </Card>
          <Card title={`Akceptační kritéria ${done}/${t.criteria.length}`}>
            <ul className="rows">
              {t.criteria.map((c, i) => (
                <li key={i}>
                  <span role="img" aria-label={c.checked ? 'splněno' : 'nesplněno'}>{c.checked ? '☑' : '☐'}</span>
                  <span className="grow">{c.text}</span>
                </li>
              ))}
            </ul>
          </Card>
          <Card title="Odkazy">
            <ul className="rows">
              {t.links.map(l => <li key={l.id}><span className="muted">{l.type}</span><span className="mono">{l.id}</span><span className="grow">{l.summary}</span></li>)}
            </ul>
          </Card>
          <Card title="Aktivita">
            <ol className="activity">
              {t.activity.map((a, i) => (
                <li key={i}>
                  <span className="tick" aria-hidden="true" />
                  <div>
                    <div className="muted">{dateTime(a.at)} · {a.author}</div>
                    {a.kind === 'comment' ? <MarkdownView source={a.text} /> : <div>{a.text}</div>}
                  </div>
                </li>
              ))}
            </ol>
          </Card>
        </div>
        <aside className="card" aria-label="Vlastnosti">
          <div className="card-head"><h2>Vlastnosti</h2></div>
          <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <dl className="dl">
              {t.fields.map(f => <Fragment key={f.name}><dt>{f.name}</dt><dd>{f.name === 'State' ? <StatusBadge tone={taskTone(f.value)}>{f.value}</StatusBadge> : f.value}</dd></Fragment>)}
              <dt>Aktualizováno</dt><dd>{ago(t.updatedAt)}</dd>
            </dl>
            <Section title="Větve" count={t.worktrees.length}>
              <ul className="rows">
                {t.worktrees.map(w => (
                  <li key={w.id}><button className="link mono" onClick={() => go('branches', w.id)}>{w.branch}</button><span className="grow" /><LayerBadge state={w.layer} /></li>
                ))}
              </ul>
            </Section>
            <Section title="Běhy" count={t.runList.length}>
              <ul className="rows">
                {t.runList.map(r => (
                  <li key={r.id}><button className="link" onClick={() => go('runs', r.id)}>{r.role}</button><span className="grow muted">{dateTime(r.startedAt)}</span><span className="num">{tokens(r.weighted)}</span><RunBadge status={r.status} /></li>
                ))}
              </ul>
            </Section>
            <Section title="Mirror">
              <dl className="dl">
                <dt>Synchronizováno</dt><dd>{ago(t.mirror.syncedAt)}</dd>
                <dt>Čtení agenty</dt><dd>{t.mirror.readsByRole.map(r => `${r.role} ${r.reads}`).join(', ') || '—'}</dd>
              </dl>
            </Section>
          </div>
        </aside>
      </div>
    </>
  );
}
