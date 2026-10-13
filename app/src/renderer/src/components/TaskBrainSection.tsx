import { useState } from 'react';
import type { TaskBrain, TaskBrainNotes } from '../../../shared/contract';
import { stateTone, taskMarkLabel } from '../brainModel';
import { ago, dateTime, num } from '../format';
import { Banner, Card, Empty } from './Parts';
import { StatusBadge } from './StatusBadge';

const FIRST = 40;

/** What the configured brains recorded on a task: its state keys, then the entries newest first (task detail, CL-248). */
export function TaskBrainSection({ brain }: { brain: TaskBrain }) {
  if (brain.brains.length === 0) {
    return <Card title="Brain"><Empty icon="info">No brain holds notes for this task.</Empty></Card>;
  }
  return <>{brain.brains.map(b => <Notes key={b.workspaceId} notes={b} />)}</>;
}

function Notes({ notes }: { notes: TaskBrainNotes }) {
  const [all, setAll] = useState(false);
  const shown = all ? notes.entries : notes.entries.slice(0, FIRST);
  const title = <>Brain · <span className="mono">{notes.workspace}</span></>;
  if (notes.error) {
    return <Card title={title}><Banner tone="warning" role="alert">The task notes could not be read: {notes.error}</Banner></Card>;
  }
  return (
    <Card title={title} actions={<span className="muted">{num(notes.entryCount)} entries · last {ago(notes.lastAt)}</span>} bodyClass="">
      {notes.indexing && <Banner tone="info" role="note">The brain index is still building; entries superseded by a check are not marked yet.</Banner>}
      {notes.state.length > 0 && (
        <ul className="task-state" aria-label="State">
          {notes.state.map(s => <li key={s.key}><StatusBadge tone={stateTone(s.key, s.value)}>{s.key}={s.value}</StatusBadge></li>)}
        </ul>
      )}
      <ol className="brain-entries task-notes" aria-label={`Entries from ${notes.workspace}`}>
        {shown.map(e => {
          const label = taskMarkLabel(e.marker);
          const done = e.rejected || e.superseded;
          return (
            <li key={e.line} className={done ? 'muted' : undefined}>
              <span className={`mark mark-${e.marker.startsWith('!') ? 'warn' : 'plain'}`} title={label}><span aria-hidden="true">{e.marker || '·'}</span><span className="sr-only">{label}</span></span>
              <span className="text">
                {e.text}
                {e.rejected && <> <StatusBadge tone="neutral">rejected</StatusBadge></>}
                {e.superseded && <> <StatusBadge tone="neutral">{e.coveredBy ? `superseded by ${e.coveredBy}` : 'superseded'}</StatusBadge></>}
              </span>
              <span className="stamp muted" title={e.at}>{e.who} · {dateTime(e.at)}</span>
            </li>
          );
        })}
      </ol>
      {shown.length < notes.entryCount && (
        <div className="table-foot">
          <span>{num(shown.length)} of {num(notes.entryCount)} entries{notes.entries.length < notes.entryCount ? '; older ones are in the file' : ''}</span>
          <span style={{ flex: 1 }} />
          {!all && notes.entries.length > FIRST && <button className="btn" onClick={() => setAll(true)}>Show {num(notes.entries.length)}</button>}
        </div>
      )}
    </Card>
  );
}
