import { useState } from 'react';
import type { LayerState, WorktreeSummary } from '../../../shared/contract';
import { bridge, useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { Drawer } from '../components/Drawer';
import { Card, ErrorState, Loading, Search, Section, Select } from '../components/Parts';
import { ChangeMark, LayerBadge, StatusBadge, taskTone } from '../components/StatusBadge';
import { ago, num } from '../format';
import { useDebounced, useSettings } from '../hooks';
import { go, type Route } from '../router';

const columns: Column<WorktreeSummary>[] = [
  { key: 'branch', header: 'Větev', render: w => <span className="mono">{w.branch ?? '(detached)'}{w.isMain ? ' · hlavní' : ''}</span> },
  { key: 'repo', header: 'Repo', render: w => w.repoName },
  { key: 'task', header: 'Úkol', render: w => w.taskId ?? '—' },
  { key: 'base', header: 'Báze', render: w => <span title={`${w.ahead} commitů napřed, ${w.behind} pozadu`}>↑{w.ahead} ↓{w.behind}</span>, numeric: true },
  { key: 'files', header: 'Soubory', render: w => num(w.changedFiles), numeric: true },
  { key: 'decls', header: 'Deklarace', render: w => num(w.changedDecls), numeric: true },
  { key: 'layer', header: 'Vrstva', render: w => <LayerBadge state={w.layer} /> },
  { key: 'activity', header: 'Aktivita', render: w => ago(w.lastActivityAt) },
  { key: 'queries', header: 'Dotazy 24 h', render: w => num(w.queries24h), numeric: true },
];

const LAYERS: { value: LayerState | ''; label: string }[] = [
  { value: '', label: 'Všechny vrstvy' }, { value: 'fresh', label: 'Čerstvé' }, { value: 'stale', label: 'Zastaralé' },
  { value: 'building', label: 'Parsuje se' }, { value: 'error', label: 'S chybou' },
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
        <Select label="Repozitář" value={repo} onChange={setRepo} options={[{ value: '', label: 'Všechna repa' }, ...repos.map(([id, name]) => ({ value: id, label: name }))]} />
        <Select label="Stav vrstvy" value={layer} onChange={setLayer} options={LAYERS} />
        <Search label="Hledat větev, úkol" value={q} onChange={setQ} />
      </div>
      <Card bodyClass="">
        {data ? (
          <DataTable label="Větve a worktree" rows={data.items} columns={columns} rowKey={w => w.id} selected={route.id}
            onOpen={w => go('branches', w.id)} shortcuts={settings?.shortcuts} empty="Žádný worktree neodpovídá filtru." />
        ) : loading ? <Loading /> : <ErrorState message={error?.message ?? 'Nelze načíst větve.'} onRetry={reload} />}
      </Card>
      {route.id && <BranchDrawer id={route.id} onClose={() => go('branches')} />}
    </>
  );
}

function BranchDrawer({ id, onClose }: { id: string; onClose(): void }) {
  const { data: w, error, reload } = useApi('worktrees/:id', id);
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
          <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
            <LayerBadge state={w.layer} />
            <span>↑{w.ahead} ↓{w.behind} od <span className="mono">{w.baseRef}</span> (merge-base <span className="mono">{w.mergeBase}</span>)</span>
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {w.task && <button className="btn" onClick={() => go('tasks', w.task!.id)}>Úkol {w.task.id} →</button>}
            <button className="btn" onClick={() => void bridge().open.worktree(w.id).then(setOpened)}>Otevřít složku</button>
            {opened === false && <span className="t2" role="status">Složku nejde otevřít (neexistuje nebo to není git worktree).</span>}
          </div>

          {w.task && (
            <Section title="Úkol">
              <ul className="rows"><li>
                <button className="link" onClick={() => go('tasks', w.task!.id)}>{w.task.id}</button>
                <span className="grow">{w.task.summary}</span>
                <StatusBadge tone={taskTone(w.task.state)}>{w.task.state}</StatusBadge>
              </li></ul>
            </Section>
          )}

          <Section title="Změněné deklarace" count={w.changes.length}>
            <ul className="rows">
              {(more ? w.changes : w.changes.slice(0, LIMIT)).map((c, i) => (
                <li key={i}>
                  <ChangeMark change={c.change} />
                  <span className="muted">{c.kind}</span>
                  <span className="grow mono" title={`${c.path}${c.line ? `:${c.line}` : ''}`}>{c.fqn}</span>
                  <span className="muted num">{c.callers} vol.</span>
                </li>
              ))}
            </ul>
            {!more && w.changes.length > LIMIT && <button className="link" onClick={() => setMore(true)}>Zobrazit dalších {w.changes.length - LIMIT}</button>}
          </Section>

          <Section title="Dotčení volající" count={w.callers.length}>
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

          <Section title="Testy" count={w.tests.length}>
            <ul className="rows">
              {w.tests.map((t, i) => (
                <li key={i}>
                  <span className="grow mono">{t.path}</span>
                  <span className="muted">{t.reason === 'touched' ? 'změněný' : 'volá změněné'}</span>
                </li>
              ))}
            </ul>
          </Section>

          <Section title="Index vrstvy">
            <dl className="dl">
              <dt>Soubory ve vrstvě</dt><dd>{num(w.index.layerFiles)}</dd>
              <dt>Parsováno</dt><dd>{ago(w.index.parsedAt)}</dd>
              <dt>Soubory s chybou</dt><dd>{w.index.errorFiles.length ? w.index.errorFiles.map(f => <div key={f} className="mono">{f}</div>) : '0'}</dd>
            </dl>
          </Section>
        </>
      )}
    </Drawer>
  );
}
