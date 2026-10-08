import { useEffect, useMemo, useRef, useState } from 'react';
import type { Delivery, JobLogText, JobRecord, SlotSnapshot, Webhook } from '../../../shared/jobs';
import { bridge, useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { Drawer } from '../components/Drawer';
import { CountUp } from '../components/CountUp';
import { Card, Empty, ErrorState, KpiTile, Loading, Search, Section, Select } from '../components/Parts';
import { StatusBadge } from '../components/StatusBadge';
import { ago, dateTime, ms, num } from '../format';
import { useSettings } from '../hooks';
import { deliveryLabel, deliveryTone, isActive, jobCounts, jobStatus, matches, maskUrl, shorten, slotLoad, stepLabel, type JobFilter } from '../jobModel';
import { go, type Route } from '../router';
import { useLive, useNow } from '../useLive';

const FILTERS: { value: JobFilter; label: string }[] = [
  { value: '', label: 'All jobs' }, { value: 'active', label: 'Running and waiting' }, { value: 'finished', label: 'Finished' }, { value: 'failed', label: 'Failed' },
];

const elapsed = (j: JobRecord, now: number): number | null =>
  j.durationMs ?? (j.status === 'running' && j.startedAt ? Math.max(0, now - Date.parse(j.startedAt)) : null);

const plural = (n: number, one: string, many: string) => `${num(n)} ${n === 1 ? one : many}`;

function columns(now: number): Column<JobRecord>[] {
  return [
    { key: 'id', header: 'Job', render: j => <span className="mono">{j.id}</span> },
    { key: 'status', header: 'Status', render: j => { const s = jobStatus(j); return <StatusBadge tone={s.tone} live={j.status === 'running'}>{s.label}</StatusBadge>; } },
    { key: 'command', header: 'Command', render: j => <span className="mono" title={j.command}>{shorten(j.command, 70)}</span>, className: 'ellipsis' },
    { key: 'slot', header: 'Slot', render: j => j.slot ?? '—' },
    { key: 'tag', header: 'Tag', render: j => j.tag ?? '—' },
    { key: 'chain', header: 'Chain', render: j => (j.rootId !== j.id ? `step after ${j.parentId ?? j.rootId}` : j.then.length + j.onFailure.length > 0 ? `${plural(j.then.length + j.onFailure.length, 'step', 'steps')} after it ends` : '—') },
    { key: 'when', header: 'Start', render: j => ago(j.startedAt ?? j.createdAt) },
    { key: 'dur', header: 'Duration', render: j => { const d = elapsed(j, now); return d === null ? '—' : ms(d); }, numeric: true },
  ];
}

export function Jobs({ route }: { route: Route }) {
  const [filter, setFilter] = useState<JobFilter>('');
  const [q, setQ] = useState('');
  const [settings] = useSettings();
  const jobs = useApi('jobs', undefined, { limit: 200 });
  const status = useApi('status');
  const hooks = useApi('webhooks');
  const deliveries = useApi('deliveries', undefined, { limit: 40 });
  const items = useMemo(() => jobs.data?.items ?? [], [jobs.data]);
  const active = items.some(isActive);
  const now = useNow(active);

  // The daemon pushes every event: reload what it changed. Several events in a row (a chain ending) are one reload.
  const reloaders = useRef({ jobs: jobs.reload, status: status.reload, deliveries: deliveries.reload, hooks: hooks.reload });
  reloaders.current = { jobs: jobs.reload, status: status.reload, deliveries: deliveries.reload, hooks: hooks.reload };
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useLive(() => {
    if (timer.current) return;
    timer.current = setTimeout(() => {
      timer.current = null;
      const r = reloaders.current;
      r.jobs(); r.status(); r.deliveries(); r.hooks();
    }, 200);
  });
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  // Where no stream exists (mock data, a daemon that restarted) a slow look keeps a running job honest.
  useEffect(() => {
    if (!active) return;
    const t = setInterval(() => { jobs.reload(); status.reload(); }, 10_000);
    return () => clearInterval(t);
  }, [active]);

  if (!jobs.data) return <Card bodyClass="">{jobs.loading ? <Loading variant="table" /> : <ErrorState message={jobs.error?.message ?? 'Could not load jobs.'} onRetry={jobs.reload} />}</Card>;

  const counts = jobCounts(items);
  const shown = items.filter(j => matches(j, { filter, q }));
  const slots = status.data?.jobs?.slots ?? [];
  const byId = new Map(items.map(j => [j.id, j]));

  return (
    <>
      <div className="filterbar">
        <Select label="Job status" value={filter} onChange={v => setFilter(v as JobFilter)} options={FILTERS} />
        <Search label="Search job, command, tag" value={q} onChange={setQ} />
        <span style={{ flex: 1 }} />
        <span className="muted">Last {plural(items.length, 'job', 'jobs')}{settings?.apiSource === 'daemon' ? ' · live from the daemon event stream' : ' · mock data, no live stream'}{status.data?.jobs?.policyHook ? ' · policy guards commands' : ''}</span>
      </div>

      <section aria-label="Job counts">
        <div className="kpis">
          <KpiTile label="Running" value={<CountUp value={counts.running} format={num} />} ctx="right now" />
          <KpiTile label="Queued" value={<CountUp value={counts.queued} format={num} />} ctx="waiting for a slot" />
          <KpiTile label="Done" value={<CountUp value={counts.passed} format={num} />} ctx="exit 0" />
          <KpiTile label="Failed" value={<CountUp value={counts.failed} format={num} />} ctx="exit ≠ 0, error, lost" />
          <KpiTile label="Denied and cancelled" value={<CountUp value={counts.stopped} format={num} />} ctx="not started or stopped" />
        </div>
      </section>

      <Card title="Slots and who holds them" bodyClass="">
        {slots.length === 0 ? <Empty icon="jobs">The daemon has no slots (every job runs right away).</Empty> : (
          <ul className="slots" aria-label="Slots">
            {slots.map(s => <SlotRow key={s.name} slot={s} byId={byId} />)}
          </ul>
        )}
      </Card>

      <Card bodyClass="">
        <DataTable label="Jobs" rows={shown} columns={columns(now)} rowKey={j => j.id} selected={route.id}
          onOpen={j => go('jobs', j.id)} shortcuts={settings?.shortcuts} empty="No job matches the filter." />
      </Card>

      <div className="grid-2e">
        <Card title="Webhooks" bodyClass="">
          <DataTable label="Webhook subscriptions" rows={hooks.data?.items ?? []} rowKey={w => w.id} empty="No subscriptions." columns={hookColumns} />
        </Card>
        <Card title="Delivery log" bodyClass="">
          <DataTable label="Webhook deliveries" rows={deliveries.data?.items ?? []} rowKey={d => d.id} empty="Nothing delivered yet." columns={deliveryColumns} />
        </Card>
      </div>

      {route.id && <JobDrawer id={route.id} now={now} onClose={() => go('jobs')} />}
    </>
  );
}

function SlotRow({ slot, byId }: { slot: SlotSnapshot; byId: Map<string, JobRecord> }) {
  const { used } = slotLoad(slot);
  const link = (id: string) => <button key={id} className="link mono" onClick={() => go('jobs', id)} title={byId.get(id)?.command}>{id}{byId.get(id)?.tag ? ` · ${byId.get(id)!.tag}` : ''}</button>;
  return (
    <li>
      <span className="mono name">{slot.name}</span>
      <span className="num" aria-label={`${used} of ${slot.capacity} taken`}>{used}/{slot.capacity}</span>
      <span className={`meter${used >= slot.capacity && slot.waiting.length > 0 ? ' over' : ''}`} role="meter" aria-valuemin={0} aria-valuemax={slot.capacity} aria-valuenow={used} aria-label={`Slot ${slot.name}`}><span style={{ width: `${(used / slot.capacity) * 100}%` }} /></span>
      <span className="holders">
        {slot.running.length === 0 ? <span className="muted">free</span> : <>held by: {slot.running.map(link)}</>}
        {slot.waiting.length > 0 && <> · {slot.waiting.length} waiting: {slot.waiting.map(link)}</>}
      </span>
    </li>
  );
}

const hookColumns: Column<Webhook>[] = [
  { key: 'url', header: 'URL', render: w => <span className="mono" title="The query string is hidden for security">{maskUrl(w.url)}</span>, className: 'ellipsis' },
  { key: 'events', header: 'Events', render: w => w.events.join(', ') || 'all' },
  { key: 'created', header: 'Since', render: w => dateTime(w.createdAt) },
];

const deliveryColumns: Column<Delivery>[] = [
  { key: 'state', header: 'Status', render: d => <span title={d.lastError ?? undefined}><StatusBadge tone={deliveryTone(d)}>{deliveryLabel(d)}</StatusBadge></span> },
  { key: 'type', header: 'Event', render: d => <span className="mono">{d.type}</span> },
  { key: 'url', header: 'URL', render: d => <span className="mono">{maskUrl(d.url)}</span>, className: 'ellipsis' },
  { key: 'attempts', header: 'Attempts', render: d => num(d.attempts), numeric: true },
  { key: 'http', header: 'HTTP', render: d => (d.lastStatus ?? d.lastError ?? '—'), numeric: true },
  { key: 'when', header: 'When', render: d => ago(d.updatedAt) },
];

function JobDrawer({ id, now, onClose }: { id: string; now: number; onClose(): void }) {
  return (
    <Drawer title={<span className="mono">{id}</span>} onClose={onClose} wide>
      <JobDrawerBody id={id} now={now} />
    </Drawer>
  );
}

function JobDrawerBody({ id, now }: { id: string; now: number }) {
  const { data, error, reload } = useApi('jobs/:id', id);
  useEffect(() => {
    // The drawer follows the job while it runs; the list above reloads on the same events.
    const t = setInterval(reload, 3000);
    return () => clearInterval(t);
  }, [reload]);
  if (!data) return error ? <ErrorState message={error.message} onRetry={reload} /> : <Loading />;
  const job = data.chain.find(j => j.id === id) ?? data.chain[0];
  if (!job) return <ErrorState title="Job not found" message="The daemon no longer knows this job." />;
  const st = jobStatus(job);
  const d = elapsed(job, now);

  return (
    <>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <StatusBadge tone={st.tone} live={job.status === 'running'}>{st.label}</StatusBadge>
        {d !== null && <span>{ms(d)}</span>}
        {job.tag && <span className="chip">{job.tag}</span>}
        {job.failureBranch && <span className="chip warn">failure branch</span>}
      </div>
      <pre className="mono cmd">{job.command}</pre>

      <Section title="Log: summary">
        <LogSummary job={job} />
      </Section>

      <Section title="Chain" count={data.chain.length}>
        <ol className="chain">
          {data.chain.map(j => {
            const s = jobStatus(j);
            return (
              <li key={j.id} aria-current={j.id === id ? 'true' : undefined}>
                <button className="link mono" onClick={() => go('jobs', j.id)}>{j.id}</button>
                <StatusBadge tone={s.tone}>{s.label}</StatusBadge>
                <span className="grow mono" title={j.command}>{shorten(j.command, 60)}</span>
              </li>
            );
          })}
        </ol>
        {(job.then.length > 0 || job.onFailure.length > 0) && (
          <dl className="dl" style={{ marginTop: 8 }}>
            {job.then.length > 0 && <><dt>On success</dt><dd className="mono">{job.then.map(stepLabel).join(' → ')}</dd></>}
            {job.onFailure.length > 0 && <><dt>On failure</dt><dd className="mono">{job.onFailure.map(stepLabel).join(' → ')}</dd></>}
            <dt>Wakes the agent</dt><dd>{{ always: 'always, at the end of the chain', failure: 'only on failure', never: 'never' }[job.wakeOn]}</dd>
          </dl>
        )}
      </Section>

      <Section title="Details">
        <dl className="dl">
          <dt>Directory</dt><dd className="mono">{job.cwd}</dd>
          <dt>Slot</dt><dd>{job.slot ?? '—'}</dd>
          <dt>Environment</dt><dd className="mono">{job.envNames.length ? job.envNames.join(', ') : '—'} <span className="muted">(names only)</span></dd>
          <dt>Created</dt><dd>{dateTime(job.createdAt)}</dd>
          <dt>Started</dt><dd>{job.startedAt ? dateTime(job.startedAt) : '—'}</dd>
          <dt>Ended</dt><dd>{job.endedAt ? dateTime(job.endedAt) : '—'}</dd>
          <dt>Exit</dt><dd>{job.exit ?? '—'}</dd>
          {job.reason && <><dt>Reason</dt><dd>{job.reason}</dd></>}
        </dl>
      </Section>
    </>
  );
}

/** Counts and failures first, the last lines after; the whole log only on request. */
function LogSummary({ job }: { job: JobRecord }) {
  const [log, setLog] = useState<JobLogText | null | 'loading' | 'unavailable'>(null);
  useEffect(() => setLog(null), [job.id]);
  const s = job.summary;
  const counts = s?.tests != null ? [plural(s.tests, 'test', 'tests'), s.passed != null && `${num(s.passed)} passed`, s.failed != null && `${num(s.failed)} failed`, s.skipped ? `${num(s.skipped)} skipped` : null].filter(Boolean).join(' · ') : null;

  const open = async () => {
    setLog('loading');
    setLog((await bridge().jobs.log(job.id)) ?? 'unavailable');
  };

  if (job.status === 'queued') return <div className="muted">The job has not run yet{job.slot ? `; it is waiting for slot ${job.slot}` : ''}.</div>;
  if (job.status === 'running') return <div className="muted">The job is running. The summary and log are available once it ends: the daemon first masks stored secrets in them.</div>;
  if (job.status === 'denied') return <div className="muted">The policy blocked the command: {job.reason ?? 'no reason given'}.</div>;
  return (
    <div>
      {counts && <div style={{ marginBottom: 8 }}><strong>{counts}</strong></div>}
      {s && s.failures.length > 0 && (
        <>
          <div className="sublabel">Failures</div>
          <pre className="mono log failures">{s.failures.join('\n')}</pre>
        </>
      )}
      {s && s.tail.length > 0 && (
        <>
          <div className="sublabel">Last lines</div>
          <pre className="mono log">{s.tail.join('\n')}</pre>
        </>
      )}
      {!s && <div className="muted">The daemon has no summary for this job{job.reason ? `: ${job.reason}` : ''}.</div>}
      <div style={{ marginTop: 8 }}>
        {log === null && <button className="btn" onClick={() => void open()}>Show full log (last 400 lines)</button>}
        {log === 'loading' && <span className="muted" role="status">Reading log…</span>}
        {log === 'unavailable' && <span className="muted" role="status">Could not read the log (the file is missing or the daemon did not return it).</span>}
        {log && typeof log === 'object' && (
          <>
            <div className="muted">{log.truncated ? 'End of log' : 'Full log'} ({num(Math.round(log.bytes / 1024))} kB), without color codes; values that look like secrets are hidden.</div>
            <pre className="mono log full" tabIndex={0} aria-label="Job log">{log.text}</pre>
          </>
        )}
      </div>
    </div>
  );
}
