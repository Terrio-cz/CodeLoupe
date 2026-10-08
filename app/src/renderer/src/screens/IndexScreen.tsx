import type { IndexHealth } from '../../../shared/contract';
import { useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { CountUp } from '../components/CountUp';
import { Card, ErrorState, KpiTile, Loading } from '../components/Parts';
import { RepoBadge, StatusBadge } from '../components/StatusBadge';
import { ago, bytes, dateTime, ms, num, tokens } from '../format';

type Repo = IndexHealth['repos'][number];
type Build = IndexHealth['builds'][number];

const plural = (n: number, one: string, many: string) => `${num(n)} ${n === 1 ? one : many}`;

export function IndexScreen() {
  const { data, error, loading, reload } = useApi('index');
  if (!data) return <Card title="Repositories" bodyClass="">{loading ? <Loading variant="table" /> : <ErrorState message={error?.message ?? 'Could not load the index.'} onRetry={reload} />}</Card>;
  const names = new Map(data.repos.map(r => [r.id, r.name]));
  const budget = data.budgets.buildPeakRssMb;

  const repoCols: Column<Repo>[] = [
    { key: 'name', header: 'Repo', render: r => r.name },
    { key: 'base', header: 'Base', render: r => <span className="mono">{r.baseRef}</span> },
    { key: 'commit', header: 'Commit', render: r => <span className="mono">{r.baseCommit ?? '—'}</span> },
    { key: 'state', header: 'Status', render: r => <RepoBadge state={r.state} /> },
    { key: 'built', header: 'Build', render: r => `${ago(r.builtAt)} · ${ms(r.buildMs)}` },
    { key: 'files', header: 'Files', render: r => num(r.files), numeric: true },
    { key: 'decls', header: 'Declarations', render: r => num(r.decls), numeric: true },
    { key: 'refs', header: 'References', render: r => num(r.refs), numeric: true },
    { key: 'db', header: 'DB', render: r => bytes(r.dbBytes), numeric: true },
    { key: 'layers', header: 'Layers', render: r => num(r.layers), numeric: true },
    { key: 'err', header: 'Parser errors', render: r => num(r.errorFiles), numeric: true },
  ];
  const buildCols: Column<Build>[] = [
    { key: 'at', header: 'Start', render: b => dateTime(b.startedAt) },
    { key: 'kind', header: 'Kind', render: b => b.kind },
    { key: 'repo', header: 'Repo', render: b => names.get(b.repoId) ?? b.repoId },
    { key: 'dur', header: 'Duration', render: b => ms(b.durationMs), numeric: true },
    { key: 'rss', header: 'Peak RSS', render: b => (b.peakRssMb === null ? '—' : <>{num(b.peakRssMb)} MB{b.peakRssMb > budget && <span className="chip warn" style={{ marginLeft: 6 }}>over {budget} MB</span>}</>), numeric: true },
    { key: 'files', header: 'Files', render: b => num(b.files), numeric: true },
    { key: 'status', header: 'Status', render: b => b.status === 'failed' ? <span title={b.error ?? ''}><StatusBadge tone="critical">failed</StatusBadge></span> : b.status === 'running' ? <StatusBadge tone="running">running</StatusBadge> : <StatusBadge tone="ok">ok</StatusBadge> },
  ];

  const sum = (f: (r: Repo) => number) => data.repos.reduce((a, r) => a + f(r), 0);
  const ready = data.repos.filter(r => r.state === 'ready').length;

  return (
    <>
      <section aria-label="Index summary">
        <div className="kpis">
          <KpiTile label="Repositories" value={<CountUp value={data.repos.length} format={num} />} ctx={`${num(ready)} ready`} />
          <KpiTile label="Declarations" value={<CountUp value={sum(r => r.decls)} format={tokens} />} ctx={plural(sum(r => r.files), 'file', 'files')} />
          <KpiTile label="References" value={<CountUp value={sum(r => r.refs)} format={tokens} />} ctx={plural(sum(r => r.layers), 'layer', 'layers')} />
          <KpiTile label="DB size" value={<CountUp value={sum(r => r.dbBytes)} format={bytes} />} ctx={`${plural(data.errorFiles.length, 'file', 'files')} with parser errors`} />
        </div>
      </section>
      <Card title="Repositories" bodyClass="">
        <DataTable label="Repositories" rows={data.repos} columns={repoCols} rowKey={r => r.id} />
      </Card>
      <div className="grid-2e">
        <Card title={`Build history (peak budget ${budget} MB)`} bodyClass="">
          <DataTable label="Build history" rows={data.builds} columns={buildCols} rowKey={b => b.id} />
        </Card>
        <Card title={`Files with parser errors (${data.errorFiles.length})`} bodyClass="">
          <DataTable label="Files with parser errors" rows={data.errorFiles} rowKey={f => f.repoId + f.path}
            columns={[
              { key: 'path', header: 'File', render: f => <span className="mono">{f.path}</span>, className: 'ellipsis' },
              { key: 'errors', header: 'ERROR nodes', render: f => num(f.errors), numeric: true },
              { key: 'line', header: 'Line', render: f => num(f.firstLine), numeric: true },
            ]} />
        </Card>
      </div>
    </>
  );
}
