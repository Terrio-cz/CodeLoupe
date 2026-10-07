import { useState } from 'react';
import type { Environment as Env } from '../../../shared/contract';
import { useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { Card, ErrorState, Loading, Search, Select } from '../components/Parts';
import { ago, dateTime } from '../format';

type Key = Env['keys'][number];

const columns: Column<Key>[] = [
  { key: 'name', header: 'Klíč', render: k => <span className="mono">{k.name}</span> },
  { key: 'scope', header: 'Rozsah', render: k => (k.scopeRef ? `${k.scope} · ${k.scopeRef}` : k.scope) },
  { key: 'source', header: 'Zdroj', render: k => k.source },
  // The API never returns a value; the mask is all there is.
  { key: 'value', header: 'Hodnota', render: () => <span className="mono" aria-label="hodnota skrytá">••••••••</span> },
  { key: 'consumers', header: 'Spotřebitelé', render: k => k.consumers.join(', ') || '—', className: 'ellipsis' },
  { key: 'used', header: 'Naposledy', render: k => ago(k.lastUsedAt) },
  { key: 'updated', header: 'Upraveno', render: k => dateTime(k.updatedAt) },
];

export function Environment() {
  const [scope, setScope] = useState('');
  const [q, setQ] = useState('');
  const { data, error, loading, reload } = useApi('environment');
  const rows = (data?.keys ?? []).filter(k => (!scope || k.scope === scope) && (!q || k.name.toLowerCase().includes(q.toLowerCase())));

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
        <div className="banner info" role="note">Šifrované úložiště (CL-50) zatím není připravené: obrazovka jen vypisuje klíče. Přidání, rotace a import přijdou s CL-54; hodnota se po uložení nikdy nezobrazí.</div>
      )}
      <Card bodyClass="">
        {data ? <DataTable label="Klíče prostředí" rows={rows} columns={columns} rowKey={k => `${k.scope}:${k.scopeRef}:${k.name}`} empty="Žádné klíče." />
          : loading ? <Loading /> : <ErrorState message={error?.message ?? 'Nelze načíst prostředí.'} onRetry={reload} />}
      </Card>
    </>
  );
}
