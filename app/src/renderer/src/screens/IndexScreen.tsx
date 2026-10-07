import type { IndexHealth } from '../../../shared/contract';
import { useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { Card, ErrorState, Loading } from '../components/Parts';
import { RepoBadge, StatusBadge } from '../components/StatusBadge';
import { ago, bytes, dateTime, ms, num } from '../format';

type Repo = IndexHealth['repos'][number];
type Build = IndexHealth['builds'][number];

export function IndexScreen() {
  const { data, error, loading, reload } = useApi('index');
  if (!data) return <Card>{loading ? <Loading /> : <ErrorState message={error?.message ?? 'Nelze načíst index.'} onRetry={reload} />}</Card>;
  const names = new Map(data.repos.map(r => [r.id, r.name]));
  const budget = data.budgets.buildPeakRssMb;

  const repoCols: Column<Repo>[] = [
    { key: 'name', header: 'Repo', render: r => r.name },
    { key: 'base', header: 'Báze', render: r => <span className="mono">{r.baseRef}</span> },
    { key: 'commit', header: 'Commit', render: r => <span className="mono">{r.baseCommit ?? '—'}</span> },
    { key: 'state', header: 'Stav', render: r => <RepoBadge state={r.state} /> },
    { key: 'built', header: 'Build', render: r => `${ago(r.builtAt)} · ${ms(r.buildMs)}` },
    { key: 'files', header: 'Soubory', render: r => num(r.files), numeric: true },
    { key: 'decls', header: 'Deklarace', render: r => num(r.decls), numeric: true },
    { key: 'refs', header: 'Reference', render: r => num(r.refs), numeric: true },
    { key: 'db', header: 'DB', render: r => bytes(r.dbBytes), numeric: true },
    { key: 'layers', header: 'Vrstvy', render: r => num(r.layers), numeric: true },
    { key: 'err', header: 'Chyby parseru', render: r => num(r.errorFiles), numeric: true },
  ];
  const buildCols: Column<Build>[] = [
    { key: 'at', header: 'Začátek', render: b => dateTime(b.startedAt) },
    { key: 'kind', header: 'Druh', render: b => b.kind },
    { key: 'repo', header: 'Repo', render: b => names.get(b.repoId) ?? b.repoId },
    { key: 'dur', header: 'Délka', render: b => ms(b.durationMs), numeric: true },
    { key: 'rss', header: 'Peak RSS', render: b => (b.peakRssMb === null ? '—' : <>{num(b.peakRssMb)} MB{b.peakRssMb > budget && <span className="chip warn" style={{ marginLeft: 6 }}>⚠ nad {budget} MB</span>}</>), numeric: true },
    { key: 'files', header: 'Soubory', render: b => num(b.files), numeric: true },
    { key: 'status', header: 'Stav', render: b => b.status === 'failed' ? <span title={b.error ?? ''}><StatusBadge tone="critical">selhal</StatusBadge></span> : b.status === 'running' ? <StatusBadge tone="running">běží</StatusBadge> : <StatusBadge tone="ok">ok</StatusBadge> },
  ];

  return (
    <>
      <Card title="Repozitáře" bodyClass="">
        <DataTable label="Repozitáře" rows={data.repos} columns={repoCols} rowKey={r => r.id} />
      </Card>
      <div className="grid-2e">
        <Card title={`Historie buildů (budget peak ${budget} MB)`} bodyClass="">
          <DataTable label="Historie buildů" rows={data.builds} columns={buildCols} rowKey={b => b.id} />
        </Card>
        <Card title={`Soubory s chybami parseru (${data.errorFiles.length})`} bodyClass="">
          <DataTable label="Soubory s chybami parseru" rows={data.errorFiles} rowKey={f => f.repoId + f.path}
            columns={[
              { key: 'path', header: 'Soubor', render: f => <span className="mono">{f.path}</span>, className: 'ellipsis' },
              { key: 'errors', header: 'ERROR uzly', render: f => num(f.errors), numeric: true },
              { key: 'line', header: 'Řádek', render: f => num(f.firstLine), numeric: true },
            ]} />
        </Card>
      </div>
    </>
  );
}
