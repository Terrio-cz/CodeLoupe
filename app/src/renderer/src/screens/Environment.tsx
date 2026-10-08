import { useEffect, useState } from 'react';
import type { Environment as Env, EnvironmentAudit } from '../../../shared/contract';
import { bridge, refreshAll, useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { Icon } from '../components/Icon';
import { ImportWizard } from '../components/ImportWizard';
import { KeyDrawer, type KeyTarget } from '../components/KeyDrawer';
import { Banner, Card, ErrorState, Loading, Search, Select } from '../components/Parts';
import { StatusBadge } from '../components/StatusBadge';
import { ago, dateTime } from '../format';
import type { Route } from '../router';

type Key = Env['keys'][number];
type AuditEvent = EnvironmentAudit['events'][number];

const scopeLabel = (k: { scope: string; scopeRef: string | null }) => (k.scopeRef ? `${k.scope} · ${k.scopeRef}` : k.scope);
const target = (k: Key): KeyTarget => ({ name: k.name, scope: k.scope, scopeRef: k.scopeRef });

const ACTION: Record<AuditEvent['action'], string> = { read: 'read', created: 'created', rotated: 'rotated', removed: 'deleted' };

interface Actions {
  canReveal: boolean;
  rotate(k: Key): void;
  remove(k: Key): void;
  reveal(k: Key): void;
}

function columns(rotationDays: number, actions: Actions): Column<Key>[] {
  return [
    { key: 'name', header: 'Key', render: k => <span className="mono">{k.name}</span> },
    { key: 'scope', header: 'Scope', render: scopeLabel },
    { key: 'source', header: 'Source', render: k => (k.source === 'file' ? <span title={k.sourceRef ?? undefined}>file</span> : 'store') },
    // The API never returns a value; the mask is all there is.
    { key: 'value', header: 'Value', render: () => <span className="mono" aria-label="value hidden">••••••••</span> },
    { key: 'consumers', header: 'Consumers', render: k => k.consumers.join(', ') || '—', className: 'ellipsis' },
    { key: 'used', header: 'Last used', render: k => ago(k.lastUsedAt) },
    { key: 'updated', header: 'Updated', render: k => dateTime(k.updatedAt) },
    {
      key: 'age',
      header: 'Age',
      render: k => (k.rotationDue
        ? <StatusBadge tone="warning">{k.ageDays} d · rotate</StatusBadge>
        : <span title={rotationDays ? `Reminder after ${rotationDays} days` : 'Reminder off'}>{k.ageDays} d</span>),
    },
    {
      key: 'actions',
      header: 'Actions',
      render: k => (
        <span className="row-actions">
          <button className="btn" onClick={e => { e.stopPropagation(); actions.rotate(k); }} aria-label={`Rotate ${k.name}`}>Rotate</button>
          {actions.canReveal && <button className="btn" onClick={e => { e.stopPropagation(); actions.reveal(k); }} aria-label={`Copy ${k.name}`}>Copy</button>}
          <button className="btn" onClick={e => { e.stopPropagation(); actions.remove(k); }} aria-label={`Delete ${k.name}`}>Delete</button>
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
        <Select label="Scope" value={scope} onChange={setScope} options={[{ value: '', label: 'All scopes' }, { value: 'global', label: 'global' }, { value: 'repo', label: 'repo' }, { value: 'workspace', label: 'workspace' }]} />
        <Search label="Search key" value={q} onChange={setQ} />
        <span style={{ flex: 1 }} />
        <button className="btn" onClick={() => setPanel({ kind: 'import' })} disabled={data?.storeReady === false}>Import…</button>
        <button className="btn primary" onClick={() => setPanel({ kind: 'add' })} disabled={data?.storeReady === false}><Icon name="plus" size={14} />Add</button>
      </div>
      {data && !data.storeReady && (
        <Banner tone="info" role="note">The encrypted store is not ready yet (no system keychain and no passphrase in CODELOUPE_PASSPHRASE), so nothing can be added. A value is never shown after it is saved.</Banner>
      )}
      {notice && <Banner tone={notice.ok ? 'info' : 'warning'} role={notice.ok ? 'status' : 'alert'}>{notice.message}</Banner>}
      {due > 0 && data && (
        <Banner>{due === 1 ? '1 key is' : `${due} keys are`} older than {data.rotationDays} days: time to rotate.</Banner>
      )}
      <Card title={data ? `Keys (${rows.length})` : 'Keys'} bodyClass="">
        {data ? <DataTable label="Environment keys" rows={rows} columns={columns(data.rotationDays, actions)} rowKey={k => `${k.scope}:${k.scopeRef}:${k.name}`} empty="No keys. Add the first one or import existing variables." />
          : loading ? <Loading variant="table" /> : <ErrorState message={error?.message ?? 'Could not load the environment.'} onRetry={reload} />}
      </Card>
      <Card title="Audit" bodyClass="">
        {audit.data ? (
          <DataTable
            label="Key audit"
            rows={audit.data.events}
            columns={auditColumns}
            rowKey={e => `${e.at}:${e.scope}:${e.scopeRef}:${e.name}:${e.action}:${e.consumer}`}
            empty="No key has been read yet."
          />
        ) : audit.loading ? <Loading variant="table" /> : <ErrorState message={audit.error?.message ?? 'Could not load the audit.'} onRetry={audit.reload} />}
      </Card>
      {(panel?.kind === 'add' || panel?.kind === 'rotate') && (
        <KeyDrawer rotate={panel.kind === 'rotate' ? panel.key : undefined} onClose={() => setPanel(null)} onSaved={message => { setPanel(null); outcome({ ok: true, message }); }} />
      )}
      {panel?.kind === 'import' && <ImportWizard onClose={() => setPanel(null)} onChanged={changed} />}
    </>
  );
}

const auditColumns: Column<AuditEvent>[] = [
  { key: 'at', header: 'When', render: e => dateTime(e.at) },
  { key: 'name', header: 'Key', render: e => <span className="mono">{e.name}</span> },
  { key: 'scope', header: 'Scope', render: scopeLabel },
  { key: 'action', header: 'Action', render: e => ACTION[e.action] },
  { key: 'consumer', header: 'Consumer', render: e => e.consumer, className: 'ellipsis' },
];
