import { useEffect, useState } from 'react';
import type { Environment as Env, EnvironmentAudit } from '../../../shared/contract';
import { bridge, refreshAll, useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { ImportWizard } from '../components/ImportWizard';
import { KeyDrawer, type KeyTarget } from '../components/KeyDrawer';
import { Card, ErrorState, Loading, Search, Select } from '../components/Parts';
import { StatusBadge } from '../components/StatusBadge';
import { ago, dateTime } from '../format';
import type { Route } from '../router';

type Key = Env['keys'][number];
type AuditEvent = EnvironmentAudit['events'][number];

const scopeLabel = (k: { scope: string; scopeRef: string | null }) => (k.scopeRef ? `${k.scope} · ${k.scopeRef}` : k.scope);
const target = (k: Key): KeyTarget => ({ name: k.name, scope: k.scope, scopeRef: k.scopeRef });

const ACTION: Record<AuditEvent['action'], string> = { read: 'přečteno', created: 'vytvořeno', rotated: 'rotováno', removed: 'smazáno' };

interface Actions {
  canReveal: boolean;
  rotate(k: Key): void;
  remove(k: Key): void;
  reveal(k: Key): void;
}

function columns(rotationDays: number, actions: Actions): Column<Key>[] {
  return [
    { key: 'name', header: 'Klíč', render: k => <span className="mono">{k.name}</span> },
    { key: 'scope', header: 'Rozsah', render: scopeLabel },
    { key: 'source', header: 'Zdroj', render: k => (k.source === 'file' ? <span title={k.sourceRef ?? undefined}>soubor</span> : 'store') },
    // The API never returns a value; the mask is all there is.
    { key: 'value', header: 'Hodnota', render: () => <span className="mono" aria-label="hodnota skrytá">••••••••</span> },
    { key: 'consumers', header: 'Spotřebitelé', render: k => k.consumers.join(', ') || '—', className: 'ellipsis' },
    { key: 'used', header: 'Naposledy', render: k => ago(k.lastUsedAt) },
    { key: 'updated', header: 'Upraveno', render: k => dateTime(k.updatedAt) },
    {
      key: 'age',
      header: 'Stáří',
      render: k => (k.rotationDue
        ? <StatusBadge tone="warning">{k.ageDays} d · rotovat</StatusBadge>
        : <span title={rotationDays ? `Připomenutí po ${rotationDays} dnech` : 'Připomenutí vypnuto'}>{k.ageDays} d</span>),
    },
    {
      key: 'actions',
      header: 'Akce',
      render: k => (
        <span className="row-actions">
          <button className="btn" onClick={e => { e.stopPropagation(); actions.rotate(k); }} aria-label={`Rotovat ${k.name}`}>Rotovat</button>
          {actions.canReveal && <button className="btn" onClick={e => { e.stopPropagation(); actions.reveal(k); }} aria-label={`Zkopírovat ${k.name}`}>Kopírovat</button>}
          <button className="btn" onClick={e => { e.stopPropagation(); actions.remove(k); }} aria-label={`Smazat ${k.name}`}>Smazat</button>
        </span>
      ),
    },
  ];
}

type Panel = { kind: 'add' } | { kind: 'rotate'; key: KeyTarget } | { kind: 'import' } | null;

export function Environment({ route }: { route?: Route }) {
  const [scope, setScope] = useState('');
  const [q, setQ] = useState('');
  const [panel, setPanel] = useState<Panel>(() => (route?.params.has('import') ? { kind: 'import' } : route?.params.has('add') ? { kind: 'add' } : null));
  const [notice, setNotice] = useState<{ ok: boolean; message: string } | null>(null);
  const [canReveal, setCanReveal] = useState(false);
  const { data, error, loading, reload } = useApi('environment');
  const audit = useApi('environment/audit', undefined, { limit: 30 });
  const rows = (data?.keys ?? []).filter(k => (!scope || k.scope === scope) && (!q || k.name.toLowerCase().includes(q.toLowerCase())));
  const due = (data?.keys ?? []).filter(k => k.rotationDue).length;

  useEffect(() => { void bridge().env.capabilities().then(c => setCanReveal(c.reveal)); }, []);

  const changed = () => { refreshAll(); };
  const outcome = (r: { ok: boolean; message: string }) => { setNotice(r); if (r.ok) changed(); };
  const actions: Actions = {
    canReveal,
    rotate: k => setPanel({ kind: 'rotate', key: target(k) }),
    remove: k => void bridge().env.remove({ name: k.name, scope: k.scopeRef ? { kind: k.scope, ref: k.scopeRef } : { kind: k.scope } }).then(outcome),
    reveal: k => void bridge().env.reveal({ name: k.name, scope: k.scopeRef ? { kind: k.scope, ref: k.scopeRef } : { kind: k.scope } }).then(setNotice),
  };

  return (
    <>
      <div className="filterbar">
        <Select label="Rozsah" value={scope} onChange={setScope} options={[{ value: '', label: 'Všechny rozsahy' }, { value: 'global', label: 'global' }, { value: 'repo', label: 'repo' }, { value: 'workspace', label: 'workspace' }]} />
        <Search label="Hledat klíč" value={q} onChange={setQ} />
        <span style={{ flex: 1 }} />
        <button className="btn" onClick={() => setPanel({ kind: 'import' })} disabled={data?.storeReady === false}>Importovat…</button>
        <button className="btn primary" onClick={() => setPanel({ kind: 'add' })} disabled={data?.storeReady === false}>+ Přidat</button>
      </div>
      {data && !data.storeReady && (
        <div className="banner info" role="note">Šifrované úložiště zatím není připravené (chybí úložiště klíčů systému i heslo v CODELOUPE_PASSPHRASE), proto nejde nic přidat. Hodnota se po uložení nikdy nezobrazí.</div>
      )}
      {notice && <div className={`banner${notice.ok ? ' info' : ''}`} role={notice.ok ? 'status' : 'alert'}>{notice.message}</div>}
      {due > 0 && data && (
        <div className="banner warning" role="status">{due === 1 ? '1 klíč je' : `${due} klíčů je`} starších než {data.rotationDays} dní: čas je rotovat.</div>
      )}
      <Card bodyClass="">
        {data ? <DataTable label="Klíče prostředí" rows={rows} columns={columns(data.rotationDays, actions)} rowKey={k => `${k.scope}:${k.scopeRef}:${k.name}`} empty="Žádné klíče. Přidejte první nebo importujte existující proměnné." />
          : loading ? <Loading /> : <ErrorState message={error?.message ?? 'Nelze načíst prostředí.'} onRetry={reload} />}
      </Card>
      <Card title="Audit">
        {audit.data ? (
          <DataTable
            label="Audit klíčů"
            rows={audit.data.events}
            columns={auditColumns}
            rowKey={e => `${e.at}:${e.scope}:${e.scopeRef}:${e.name}:${e.action}:${e.consumer}`}
            empty="Zatím nikdo žádný klíč nečetl."
          />
        ) : audit.loading ? <Loading /> : <ErrorState message={audit.error?.message ?? 'Nelze načíst audit.'} onRetry={audit.reload} />}
      </Card>
      {(panel?.kind === 'add' || panel?.kind === 'rotate') && (
        <KeyDrawer rotate={panel.kind === 'rotate' ? panel.key : undefined} onClose={() => setPanel(null)} onSaved={message => { setPanel(null); outcome({ ok: true, message }); }} />
      )}
      {panel?.kind === 'import' && <ImportWizard onClose={() => setPanel(null)} onChanged={changed} />}
    </>
  );
}

const auditColumns: Column<AuditEvent>[] = [
  { key: 'at', header: 'Kdy', render: e => dateTime(e.at) },
  { key: 'name', header: 'Klíč', render: e => <span className="mono">{e.name}</span> },
  { key: 'scope', header: 'Rozsah', render: scopeLabel },
  { key: 'action', header: 'Akce', render: e => ACTION[e.action] },
  { key: 'consumer', header: 'Spotřebitel', render: e => e.consumer, className: 'ellipsis' },
];
