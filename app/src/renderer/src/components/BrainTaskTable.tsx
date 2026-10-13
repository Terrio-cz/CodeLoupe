import type { BrainTask } from '../../../shared/contract';
import { stateText, stateTone } from '../brainModel';
import { ago, num } from '../format';
import { DataTable, type Column } from './DataTable';
import { StatusBadge } from './StatusBadge';

const columns: Column<BrainTask>[] = [
  { key: 'id', header: 'Task', render: t => <span className="mono" title={t.tracked ? undefined : 'not in the tracker mirror'}>{t.id}</span> },
  {
    key: 'state', header: 'State',
    render: t => t.state.length === 0 ? '—' : (
      <span className="task-state-inline" title={stateText(t.state)}>
        {t.state.map(s => <StatusBadge key={s.key} tone={stateTone(s.key, s.value)}>{s.key}={s.value}</StatusBadge>)}
      </span>
    ),
  },
  { key: 'entries', header: 'Entries', render: t => num(t.entries), numeric: true },
  { key: 'last', header: 'Last entry', render: t => ago(t.lastAt) },
];

/** The task files of one brain, newest last entry first; a row opens the task detail when the tracker mirror holds the task. */
export function BrainTaskTable({ tasks, onOpen, shortcuts }: { tasks: BrainTask[]; onOpen(id: string): void; shortcuts?: boolean }) {
  return (
    <DataTable
      label="Task notes" rows={tasks} columns={columns} rowKey={t => t.id} shortcuts={shortcuts}
      onOpen={t => { if (t.tracked) onOpen(t.id); }} empty="This brain has no task files."
    />
  );
}
