import { useState } from 'react';
import type { Environment as Env, EnvironmentAudit } from '../../../shared/contract';
import { useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { Card, ErrorState, Loading, Search, Select } from '../components/Parts';
import { StatusBadge } from '../components/StatusBadge';
import { ago, dateTime } from '../format';

type Key = Env['keys'][number];
type AuditEvent = EnvironmentAudit['events'][number];

const scopeLabel = (k: { scope: string; scopeRef: string | null }) => (k.scopeRef ? `${k.scope} · ${k.scopeRef}` : k.scope);

const ACTION: Record<AuditEvent['action'], string> = { read: 'přečteno', created: 'vytvořeno', rotated: 'rotováno', removed: 'smazáno' };

function columns(rotationDays: number): Column<Key>[] {
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
  ];
}

export function Environment() {
  const [scope, setScope] = useState('');
  const [q, setQ] = useState('');
  const { data, error, loading, reload } = useApi('environment');
  const audit = useApi('environment/audit', undefined, { limit: 30 });
  const rows = (data?.keys ?? []).filter(k => (!scope || k.scope === scope) && (!q || k.name.toLowerCase().includes(q.toLowerCase())));
  const due = (data?.keys ?? []).filter(k => k.rotationDue).length;

  return (
    <>
      <div className="filterbar">
        <Select label="Rozsah" value={scope} onChange={setScope} options={[{ value: '', label: 'Všechny rozsahy' }, { value: 'global', label: 'global' }, { value: 'repo', label: 'repo' }, { value: 'workspace', label: 'workspace' }]} />
        <Search label="Hledat klíč" value={q} onChange={setQ} />
        <span style={{ flex: 1 }} />
        <button className="btn" disabled title="Přijde s CL-54">Importovat… (CL-54)</button>
        <button className="btn primary" disabled title="Přijde s CL-54">+ Přidat (CL-54)</button>
      </div>
      {data && !data.storeReady && (
        <div className="banner info" role="note">Šifrované úložiště zatím není připravené (chybí úložiště klíčů systému i heslo). Hodnota se po uložení nikdy nezobrazí.</div>
      )}
      {due > 0 && data && (
        <div className="banner warning" role="status">{due === 1 ? '1 klíč je' : `${due} klíčů je`} starších než {data.rotationDays} dní: čas je rotovat.</div>
      )}
      <Card bodyClass="">
        {data ? <DataTable label="Klíče prostředí" rows={rows} columns={columns(data.rotationDays)} rowKey={k => `${k.scope}:${k.scopeRef}:${k.name}`} empty="Žádné klíče." />
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
