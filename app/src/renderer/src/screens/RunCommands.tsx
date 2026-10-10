import { useEffect, useState, type ReactNode } from 'react';
import type { Range } from '../../../shared/contract';
import type { CommandGroup, CommandReport, CommandSlice, CommandSortKey } from '../../../shared/commands';
import { useApi } from '../api';
import { CommandTable } from '../components/CommandTable';
import { CountUp } from '../components/CountUp';
import { Drawer } from '../components/Drawer';
import { Banner, Card, ErrorState, KpiTile, Loading, Section, Select } from '../components/Parts';
import { KIND_LABELS, moneyText, SORT_LABELS } from '../commandModel';
import { num, pct, tokens } from '../format';
import { useRange } from '../hooks';

const TOP = 30;
const PERIODS: { value: Range; label: string }[] = [
  { value: '24h', label: 'Last 24 hours' }, { value: '7d', label: 'Last 7 days' }, { value: '30d', label: 'Last 30 days' },
];
const SORTS = (Object.keys(SORT_LABELS) as CommandSortKey[]).map(value => ({ value, label: `Rank by: ${SORT_LABELS[value].toLowerCase()}` }));

/**
 * The Commands tab of the Runs screen: which commands agents use most and what their results cost (docs/ui-spec.md § 3.3a).
 * The period is the one of the topbar switch, shared with the other tabs; the figures are those of `codeloupe metrics commands`.
 */
export function CommandsTab({ tabs, onRun }: { tabs: ReactNode; onRun(id: string): void }) {
  const [range, setRange] = useRange();
  const [role, setRole] = useState('');
  const [sort, setSort] = useState<CommandSortKey>('cost');
  const [open, setOpen] = useState<string | null>(null);
  const view = useApi('commands', undefined, { since: range, role, top: TOP, sort });
  const openRun = (id: string) => {
    setOpen(null);
    onRun(id);
  };

  const reading = view.data?.ingest.running ?? false;
  useEffect(() => {
    if (!reading) return;
    const t = setInterval(view.reload, 3000);
    return () => clearInterval(t);
  }, [reading, view.reload]);

  const filters = (roles: string[]) => (
    <div className="filterbar">
      {tabs}
      <Select label="Role" value={role} onChange={setRole} options={[{ value: '', label: 'All roles' }, ...roles.map(r => ({ value: r, label: r }))]} />
      <Select label="Period" value={range} onChange={v => setRange(v as Range)} options={PERIODS} />
      <Select label="Rank by" value={sort} onChange={v => setSort(v as CommandSortKey)} options={SORTS} />
      <span className="muted">Commands agents ran and what their results cost while they stayed in context.</span>
    </div>
  );

  if (!view.data) {
    return (
      <>
        {filters([])}
        <Card bodyClass="">{view.loading ? <Loading variant="table" /> : <ErrorState message={view.error?.message ?? 'Could not load commands.'} onRetry={view.reload} />}</Card>
      </>
    );
  }
  const { report, ingest } = view.data;
  const priced = report.money !== null;
  const group = open ? report.groups.find(g => g.command === open) ?? null : null;

  return (
    <>
      {filters(view.data.roles)}
      {reading && <Banner tone="info">The daemon is reading transcripts ({num(ingest.filesDone)} of {num(ingest.filesTotal)} files); the numbers are filling in.</Banner>}

      <section aria-label="Command summary">
        <div className="kpis">
          <KpiTile label="Runs counted" value={<CountUp value={report.runs} format={num} />} ctx={`${num(report.groupCount)} command groups`} />
          <KpiTile label="Tool calls" value={<CountUp value={report.calls} format={num} />} ctx={`${tokens(report.argTokens)} argument tokens sent`} />
          <KpiTile label="Result tokens" value={<CountUp value={report.resultTokens} format={tokens} />} ctx="returned to the agents" />
          <KpiTile label="Cost carried by results" value={pct(report.costPct, 1)} ctx={`of ${tokens(report.cost)} weighted tokens`} />
          {priced && <KpiTile label="Money carried" value={moneyText(report.money, report.currency)} ctx={report.unpricedRuns ? `${num(report.unpricedRuns)} runs of unpriced models left out` : 'at the price table of metrics collect'} />}
        </div>
      </section>

      <Card bodyClass="">
        <CommandTable label="Commands" groups={report.groups} currency={report.currency} priced={priced} sort={sort} onSort={setSort} onOpen={g => setOpen(g.command)} onRun={openRun} />
        <div className="table-foot"><span>Top {num(report.groups.length)} of {num(report.groupCount)} groups, ranked by {SORT_LABELS[sort].toLowerCase()}.</span></div>
      </Card>

      <Categories report={report} />
      <Slices title="By role" label="Commands by role" first="Role" slices={report.roles} />
      <Slices title="By week" label="Commands by week" first="Week" slices={report.weeks} />

      {group && <GroupDrawer group={group} report={report} onClose={() => setOpen(null)} onRun={openRun} />}
    </>
  );
}

function Categories({ report }: { report: CommandReport }) {
  if (report.categories.length === 0) return null;
  return (
    <Section title="By tool category" count={report.categories.length} open={false}>
      <div className="table-wrap">
        <table className="data" aria-label="Tool categories">
          <thead><tr><th scope="col">Category</th><th scope="col" className="num">Calls</th><th scope="col" className="num">Results</th><th scope="col" className="num">Errors</th><th scope="col" className="num">Cost share</th></tr></thead>
          <tbody>
            {report.categories.map(c => (
              <tr key={c.category}><td className="mono">{c.category}</td><td className="num">{num(c.calls)}</td><td className="num">{tokens(c.resultTokens)}</td><td className="num">{num(c.errors)}</td><td className="num">{pct(c.costPct, 1)}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
    </Section>
  );
}

function Slices({ title, label, first, slices }: { title: string; label: string; first: string; slices: CommandSlice[] }) {
  if (slices.length < 2) return null;
  return (
    <Section title={title} count={slices.length} open={false}>
      <div className="table-wrap">
        <table className="data" aria-label={label}>
          <thead><tr><th scope="col">{first}</th><th scope="col" className="num">Runs</th><th scope="col" className="num">Calls</th><th scope="col" className="num">Results</th><th scope="col" className="num">Cost share</th><th scope="col">Top commands</th></tr></thead>
          <tbody>
            {slices.map(s => (
              <tr key={s.key}>
                <td className="mono">{s.key}</td><td className="num">{num(s.runs)}</td><td className="num">{num(s.calls)}</td><td className="num">{tokens(s.resultTokens)}</td><td className="num">{pct(s.costPct, 1)}</td>
                <td className="mono ellipsis narrow" title={s.groups.map(g => g.command).join(', ')}>{s.groups.slice(0, 3).map(g => `${g.command} (${pct(g.costPct, 1)})`).join(', ')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Section>
  );
}

function GroupDrawer({ group: g, report, onClose, onRun }: { group: CommandGroup; report: CommandReport; onClose(): void; onRun(id: string): void }) {
  return (
    <Drawer title={<span className="mono" title={g.command}>{g.command}</span>} subtitle={`${KIND_LABELS[g.kind]} · ${pct(g.costPct, 1)} of the cost of ${num(report.runs)} runs`} onClose={onClose} wide>
      <dl className="dl">
        <dt>Calls</dt><dd>{num(g.calls)} in {num(g.runs)} runs{g.errors ? `, ${num(g.errors)} failed` : ''}</dd>
        <dt>Results</dt><dd>{tokens(g.resultTokens)} tokens returned, {tokens(g.weighted)} weighted tokens while they stayed in context</dd>
        <dt>Arguments</dt><dd>{tokens(g.argTokens)} tokens sent</dd>
        <dt>Repeats</dt><dd>{g.repeats ? `${num(g.repeats)} calls identical to an earlier call of the same run, ${tokens(g.repeatTokens)} result tokens again` : 'none'}</dd>
        {g.money !== null && <><dt>Money</dt><dd>{moneyText(g.money, report.currency)} of what these runs cost</dd></>}
      </dl>
      <Section title="Runs that carried most" count={g.topRuns.length}>
        <div className="table-wrap">
          <table className="data" aria-label="Runs that carried most">
            <thead><tr><th scope="col">Run</th><th scope="col">Role</th><th scope="col">Task</th><th scope="col">Prompt</th><th scope="col" className="num">Calls</th><th scope="col" className="num">Cost</th></tr></thead>
            <tbody>
              {g.topRuns.map(r => (
                <tr key={r.id}>
                  <td><button className="link" onClick={() => onRun(r.id)} aria-label={`Open run ${r.id}`}>#{r.id}</button></td>
                  <td className="mono">{r.role}</td><td>{r.ter ?? '—'}</td><td className="ellipsis narrow" title={r.title}>{r.title}</td><td className="num">{num(r.calls)}</td><td className="num">{tokens(r.weighted)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
    </Drawer>
  );
}
