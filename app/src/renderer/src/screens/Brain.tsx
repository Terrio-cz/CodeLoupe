import { useState } from 'react';
import type { BrainHit, BrainWorkspace } from '../../../shared/contract';
import { useApi } from '../api';
import { Banner, Card, Empty, ErrorState, Loading, Search, Select } from '../components/Parts';
import { errorTitle, pickTopic, searchTerms, treeRows } from '../brainModel';
import { bytes, num } from '../format';
import { useDebounced } from '../hooks';
import { go, href, type Route } from '../router';

const MARK_LABEL: Record<string, string> = { '+': 'fact', '!': 'warning', '=': 'measured', x: 'removed', '?': 'open', '~': 'approximate', '-': 'note' };

/** What each workspace's shared memory knows: topics with entry counts, one topic's entries, and a search over all of them. */
export function Brain({ route }: { route: Route }) {
  const list = useApi('brain');
  const [q, setQ] = useState('');
  const [tasks, setTasks] = useState(false);
  const [onlyTopic, setOnlyTopic] = useState(false);
  const shown = useDebounced(q);

  if (!list.data) {
    return <Card bodyClass="">{list.loading ? <Loading variant="table" /> : <ErrorState message={list.error?.message ?? 'Could not load the brains.'} onRetry={list.reload} />}</Card>;
  }
  const workspaces = list.data.workspaces;
  if (workspaces.length === 0) {
    return (
      <Card>
        <Empty icon="info">
          No workspace has a brain. A workspace is listed when <span className="mono">brain/brain.mjs</span> exists in one of the repositories CodeLoupe knows
          or in a directory named in <span className="mono">config.json</span> under <span className="mono">workspaces.brains</span>.
        </Empty>
      </Card>
    );
  }
  const ws = workspaces.find(w => w.id === route.id) ?? workspaces[0];
  const wanted = route.params.get('topic');
  const topic = pickTopic(ws.topics, wanted);
  const terms = searchTerms(shown);

  return (
    <>
      <div className="filterbar">
        <Select label="Workspace" value={ws.id} onChange={id => go('brain', id)} options={workspaces.map(w => ({ value: w.id, label: w.name }))} />
        <Search label="Search the brain" value={q} onChange={setQ} />
        <label className="check"><input type="checkbox" checked={tasks} onChange={e => setTasks(e.target.checked)} />Include past task findings</label>
        {topic && <label className="check"><input type="checkbox" checked={onlyTopic} onChange={e => setOnlyTopic(e.target.checked)} />Only in <span className="mono">{topic}</span></label>}
      </div>
      {ws.errorCode ? (
        <Card><ErrorState title={errorTitle(ws.errorCode)} message={ws.error ?? ''} onRetry={list.reload} /></Card>
      ) : (
        <div className="brain-layout">
          <TopicTree ws={ws} current={topic} />
          {terms.length > 0
            ? <Results ws={ws} q={shown} tasks={tasks} topic={onlyTopic ? topic : null} />
            : <TopicEntries ws={ws} topic={topic} />}
        </div>
      )}
    </>
  );
}

function TopicTree({ ws, current }: { ws: BrainWorkspace; current: string | null }) {
  const rows = treeRows(ws.topics);
  return (
    <Card title="Topics" actions={<span className="muted">{num(ws.topics.length)} topics · {num(ws.entries)} entries · {bytes(ws.bytes)}</span>} bodyClass="">
      {rows.length === 0 ? <Empty>This brain has no topics yet.</Empty> : (
        <nav aria-label="Brain topics">
          <ul className="brain-tree">
            {rows.map(r => (
              <li key={r.path} style={{ paddingLeft: r.depth * 14 }}>
                {r.topic ? (
                  <a href={href('brain', ws.id, { topic: r.path })} aria-current={current === r.path ? 'true' : undefined} title={r.topic.about}>
                    <span className="label">{r.label}</span>
                    <span className="count" title={r.total !== r.topic.entries ? `${r.topic.entries} here, ${r.total} with subtopics` : undefined}>{r.total}</span>
                  </a>
                ) : (
                  <span className="group"><span className="label">{r.label}</span><span className="count" title="with subtopics">{r.total}</span></span>
                )}
              </li>
            ))}
          </ul>
        </nav>
      )}
    </Card>
  );
}

function TopicEntries({ ws, topic }: { ws: BrainWorkspace; topic: string | null }) {
  const about = ws.topics.find(t => t.topic === topic)?.about;
  const { data, error, loading, reload } = useApi(topic ? 'brain/:id/topic' : null, ws.id, { name: topic ?? undefined });
  if (!topic) return <Card><Empty>Choose a topic.</Empty></Card>;
  const entries = data?.topic === topic ? data.entries : null;
  return (
    <Card title={<span className="mono">{topic}</span>} actions={entries ? <span className="muted">{num(entries.length)} entries</span> : undefined} bodyClass="">
      {about && <p className="brain-about muted">{about}</p>}
      {entries ? (
        entries.length === 0 ? <Empty>No entries in this topic.</Empty> : (
          <ol className="brain-entries" aria-label={`Entries of ${topic}`}>
            {entries.map((e, i) => (
              <li key={i}>
                {e.mark && <span className={`mark mark-${e.mark === '!' ? 'warn' : 'plain'}`} title={MARK_LABEL[e.mark]}><span aria-hidden="true">{e.mark}</span><span className="sr-only">{MARK_LABEL[e.mark] ?? 'entry'}</span></span>}
                <span className="text">{e.text}</span>
                {e.stamp && <span className="stamp muted">{e.stamp}</span>}
              </li>
            ))}
          </ol>
        )
      ) : loading ? <Loading />
        : error?.code === 'not_found'
          ? <Empty>This topic only groups its subtopics; pick one in the list.</Empty>
          : <ErrorState message={error?.message ?? 'Could not load the topic.'} onRetry={reload} />}
    </Card>
  );
}

function Results({ ws, q, tasks, topic }: { ws: BrainWorkspace; q: string; tasks: boolean; topic: string | null }) {
  const { data, error, loading, reload } = useApi('brain/:id/search', ws.id, { q, tasks: tasks ? 1 : undefined, topic: topic ?? undefined });
  const hits = data?.hits ?? null;
  return (
    <Card title="Search results" actions={hits ? <span className="muted">{num(hits.length)}{data && data.more > 0 ? ` of ${num(hits.length + data.more)}` : ''}</span> : undefined} bodyClass="">
      {hits ? (
        hits.length === 0 ? <Empty icon="search">Nothing found for “{q.trim()}”{tasks ? '' : '. Past task findings are not included.'}</Empty> : (
          <ol className="brain-entries" aria-label="Search results">
            {hits.map(h => <HitRow key={h.where} ws={ws} hit={h} />)}
          </ol>
        )
      ) : loading ? <Loading />
        : error?.code === 'bad_request' ? <Empty icon="search">Type at least one search term.</Empty>
          : <ErrorState title="Search failed" message={error?.message ?? 'Could not search the brain.'} onRetry={reload} />}
      {data && data.more > 0 && <Banner tone="info" role="note">{num(data.more)} more matches are not shown; add a term to narrow the search.</Banner>}
    </Card>
  );
}

function HitRow({ ws, hit }: { ws: BrainWorkspace; hit: BrainHit }) {
  return (
    <li>
      <span className="where mono">
        {hit.topic
          ? <a href={href('brain', ws.id, { topic: hit.topic })}>{hit.topic}</a>
          : hit.task ? <a href={href('tasks', hit.task)}>{hit.task}</a> : hit.where}
      </span>
      <span className="text">{hit.text}</span>
      {hit.hits > 1 && <span className="stamp muted" title="search terms matched in this line">{hit.hits} terms</span>}
    </li>
  );
}
