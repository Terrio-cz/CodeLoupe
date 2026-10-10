import type { CommandGroup, CommandSortKey } from '../../../shared/commands';
import { KIND_LABELS, repeatText, runLinks, sortOf } from '../commandModel';
import { num, pct, tokens } from '../format';
import { DataTable, type Column } from './DataTable';

interface Props {
  label: string;
  groups: CommandGroup[];
  currency: string;
  /** Show the money column: only when some run of the period ran on a priced model. */
  priced: boolean;
  sort?: CommandSortKey;
  onSort?(s: CommandSortKey): void;
  onOpen?(g: CommandGroup): void;
  /** Opens a run; with it each row lists the runs that carried most of the group. */
  onRun?(id: string): void;
}

function columns({ currency, priced, onRun }: Pick<Props, 'currency' | 'priced' | 'onRun'>): Column<CommandGroup>[] {
  const cols: Column<CommandGroup>[] = [
    { key: 'command', header: 'Command', render: g => <span className="mono" title={g.command}>{g.command}</span>, className: 'ellipsis narrow' },
    { key: 'kind', header: 'Kind', render: g => <span className="chip">{KIND_LABELS[g.kind]}</span> },
    { key: 'calls', header: 'Calls', sortKey: 'calls', render: g => num(g.calls), numeric: true },
    { key: 'runs', header: 'Runs', render: g => num(g.runs), numeric: true },
    { key: 'tokens', header: 'Results', sortKey: 'tokens', render: g => tokens(g.resultTokens), numeric: true },
    { key: 'args', header: 'Arguments', render: g => tokens(g.argTokens), numeric: true },
    { key: 'repeats', header: 'Repeats', render: g => repeatText(g), numeric: true },
    { key: 'errors', header: 'Errors', render: g => (g.errors ? num(g.errors) : '—'), numeric: true },
    { key: 'cost', header: 'Cost share', sortKey: 'cost', render: g => pct(g.costPct, 1), numeric: true },
  ];
  if (priced) cols.push({ key: 'money', header: `Money (${currency})`, render: g => (g.money === null ? '—' : g.money.toFixed(2)), numeric: true });
  if (onRun) {
    cols.push({
      key: 'runsOf',
      header: 'Most in',
      render: g => (
        <span className="run-links">
          {runLinks(g).map(l => (
            <button key={l.id} className="link" title={l.title} onClick={e => { e.stopPropagation(); onRun(l.id); }}>{l.text}</button>
          ))}
        </span>
      ),
    });
  }
  return cols;
}

/** The ranked table of command groups: the Commands tab, and the top groups of a role or a week. */
export function CommandTable({ label, groups, currency, priced, sort, onSort, onOpen, onRun }: Props) {
  return (
    <DataTable
      label={label} rows={groups} columns={columns({ currency, priced, onRun })} rowKey={g => g.command} onOpen={onOpen}
      sort={sort ? { key: sort, order: 'desc' } : undefined}
      onSort={onSort ? s => { const k = sortOf(s.key); if (k) onSort(k); } : undefined}
      empty="No tool calls in this period and role."
    />
  );
}
