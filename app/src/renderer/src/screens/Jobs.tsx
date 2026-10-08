import { useEffect, useMemo, useRef, useState } from 'react';
import type { Delivery, JobLogText, JobRecord, SlotSnapshot, Webhook } from '../../../shared/jobs';
import { bridge, useApi } from '../api';
import { DataTable, type Column } from '../components/DataTable';
import { Drawer } from '../components/Drawer';
import { Card, ErrorState, KpiTile, Loading, Search, Section, Select } from '../components/Parts';
import { StatusBadge } from '../components/StatusBadge';
import { ago, dateTime, ms, num } from '../format';
import { useSettings } from '../hooks';
import { deliveryLabel, deliveryTone, isActive, jobCounts, jobStatus, matches, maskUrl, shorten, slotLoad, stepLabel, type JobFilter } from '../jobModel';
import { go, type Route } from '../router';
import { useLive, useNow } from '../useLive';

const FILTERS: { value: JobFilter; label: string }[] = [
  { value: '', label: 'Všechny joby' }, { value: 'active', label: 'Běží a čekají' }, { value: 'finished', label: 'Skončené' }, { value: 'failed', label: 'Selhané' },
];

const elapsed = (j: JobRecord, now: number): number | null =>
  j.durationMs ?? (j.status === 'running' && j.startedAt ? Math.max(0, now - Date.parse(j.startedAt)) : null);

function columns(now: number): Column<JobRecord>[] {
  return [
    { key: 'id', header: 'Job', render: j => <span className="mono">{j.id}</span> },
    { key: 'status', header: 'Stav', render: j => { const s = jobStatus(j); return <StatusBadge tone={s.tone}>{s.label}</StatusBadge>; } },
    { key: 'command', header: 'Příkaz', render: j => <span className="mono" title={j.command}>{shorten(j.command, 70)}</span>, className: 'ellipsis' },
    { key: 'slot', header: 'Slot', render: j => j.slot ?? '—' },
    { key: 'tag', header: 'Štítek', render: j => j.tag ?? '—' },
    { key: 'chain', header: 'Řetěz', render: j => (j.rootId !== j.id ? `krok po ${j.parentId ?? j.rootId}` : j.then.length + j.onFailure.length > 0 ? `${j.then.length + j.onFailure.length} kroků po skončení` : '—') },
    { key: 'when', header: 'Začátek', render: j => ago(j.startedAt ?? j.createdAt) },
    { key: 'dur', header: 'Doba', render: j => { const d = elapsed(j, now); return d === null ? '—' : ms(d); }, numeric: true },
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

  if (!jobs.data) return <Card>{jobs.loading ? <Loading /> : <ErrorState message={jobs.error?.message ?? 'Nelze načíst joby.'} onRetry={jobs.reload} />}</Card>;

  const counts = jobCounts(items);
  const shown = items.filter(j => matches(j, { filter, q }));
  const slots = status.data?.jobs?.slots ?? [];
  const byId = new Map(items.map(j => [j.id, j]));

  return (
    <>
      <div className="filterbar">
        <Select label="Stav jobu" value={filter} onChange={v => setFilter(v as JobFilter)} options={FILTERS} />
        <Search label="Hledat job, příkaz, štítek" value={q} onChange={setQ} />
        <span style={{ flex: 1 }} />
        <span className="muted">Posledních {num(items.length)} jobů{settings?.apiSource === 'daemon' ? ' · živě z proudu událostí daemonu' : ' · mock data, bez živého proudu'}{status.data?.jobs?.policyHook ? ' · politika hlídá příkazy' : ''}</span>
      </div>

      <section className="card" aria-label="Počty jobů">
        <div className="kpis">
          <KpiTile label="Běží" value={num(counts.running)} ctx="právě teď" />
          <KpiTile label="Ve frontě" value={num(counts.queued)} ctx="čekají na slot" />
          <KpiTile label="Hotové" value={num(counts.passed)} ctx="exit 0" />
          <KpiTile label="Selhané" value={num(counts.failed)} ctx="exit ≠ 0, chyba, ztracené" />
          <KpiTile label="Zamítnuté a zrušené" value={num(counts.stopped)} ctx="nespuštěné nebo přerušené" />
        </div>
      </section>

      <Card title="Sloty a kdo je drží" bodyClass="">
        {slots.length === 0 ? <div className="state">Daemon nemá žádný slot (každý job běží hned).</div> : (
          <ul className="slots" aria-label="Sloty">
            {slots.map(s => <SlotRow key={s.name} slot={s} byId={byId} />)}
          </ul>
        )}
      </Card>

      <Card bodyClass="">
        <DataTable label="Joby" rows={shown} columns={columns(now)} rowKey={j => j.id} selected={route.id}
          onOpen={j => go('jobs', j.id)} shortcuts={settings?.shortcuts} empty="Žádný job neodpovídá filtru." />
      </Card>

      <div className="grid-2e">
        <Card title="Webhooky" bodyClass="">
          <DataTable label="Odběry webhooků" rows={hooks.data?.items ?? []} rowKey={w => w.id} empty="Žádný odběr." columns={hookColumns} />
        </Card>
        <Card title="Log doručení" bodyClass="">
          <DataTable label="Doručení webhooků" rows={deliveries.data?.items ?? []} rowKey={d => d.id} empty="Zatím nic nebylo doručováno." columns={deliveryColumns} />
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
      <span className="num" aria-label={`obsazeno ${used} z ${slot.capacity}`}>{used}/{slot.capacity}</span>
      <span className={`meter${used >= slot.capacity && slot.waiting.length > 0 ? ' over' : ''}`} role="meter" aria-valuemin={0} aria-valuemax={slot.capacity} aria-valuenow={used} aria-label={`Slot ${slot.name}`}><span style={{ width: `${(used / slot.capacity) * 100}%` }} /></span>
      <span className="holders">
        {slot.running.length === 0 ? <span className="muted">volný</span> : <>drží: {slot.running.map(link)}</>}
        {slot.waiting.length > 0 && <> · čeká {slot.waiting.length}: {slot.waiting.map(link)}</>}
      </span>
    </li>
  );
}

const hookColumns: Column<Webhook>[] = [
  { key: 'url', header: 'Adresa', render: w => <span className="mono" title="Query se z bezpečnosti nezobrazuje">{maskUrl(w.url)}</span>, className: 'ellipsis' },
  { key: 'events', header: 'Události', render: w => w.events.join(', ') || 'všechny' },
  { key: 'created', header: 'Od', render: w => dateTime(w.createdAt) },
];

const deliveryColumns: Column<Delivery>[] = [
  { key: 'state', header: 'Stav', render: d => <span title={d.lastError ?? undefined}><StatusBadge tone={deliveryTone(d)}>{deliveryLabel(d)}</StatusBadge></span> },
  { key: 'type', header: 'Událost', render: d => <span className="mono">{d.type}</span> },
  { key: 'url', header: 'Adresa', render: d => <span className="mono">{maskUrl(d.url)}</span>, className: 'ellipsis' },
  { key: 'attempts', header: 'Pokusů', render: d => num(d.attempts), numeric: true },
  { key: 'http', header: 'HTTP', render: d => (d.lastStatus ?? d.lastError ?? '—'), numeric: true },
  { key: 'when', header: 'Kdy', render: d => ago(d.updatedAt) },
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
  if (!job) return <ErrorState message="Job už daemon nezná." />;
  const st = jobStatus(job);
  const d = elapsed(job, now);

  return (
    <>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <StatusBadge tone={st.tone}>{st.label}</StatusBadge>
        {d !== null && <span>{ms(d)}</span>}
        {job.tag && <span className="chip">{job.tag}</span>}
        {job.failureBranch && <span className="chip warn">větev po selhání</span>}
      </div>
      <pre className="mono cmd">{job.command}</pre>

      <Section title="Log: souhrn">
        <LogSummary job={job} />
      </Section>

      <Section title="Řetěz" count={data.chain.length}>
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
            {job.then.length > 0 && <><dt>Po úspěchu</dt><dd className="mono">{job.then.map(stepLabel).join(' → ')}</dd></>}
            {job.onFailure.length > 0 && <><dt>Po selhání</dt><dd className="mono">{job.onFailure.map(stepLabel).join(' → ')}</dd></>}
            <dt>Probudí agenta</dt><dd>{{ always: 'vždy na konci řetězu', failure: 'jen při selhání', never: 'nikdy' }[job.wakeOn]}</dd>
          </dl>
        )}
      </Section>

      <Section title="Podrobnosti">
        <dl className="dl">
          <dt>Adresář</dt><dd className="mono">{job.cwd}</dd>
          <dt>Slot</dt><dd>{job.slot ?? '—'}</dd>
          <dt>Prostředí</dt><dd className="mono">{job.envNames.length ? job.envNames.join(', ') : '—'} <span className="muted">(jen jména)</span></dd>
          <dt>Vytvořen</dt><dd>{dateTime(job.createdAt)}</dd>
          <dt>Začal</dt><dd>{job.startedAt ? dateTime(job.startedAt) : '—'}</dd>
          <dt>Skončil</dt><dd>{job.endedAt ? dateTime(job.endedAt) : '—'}</dd>
          <dt>Exit</dt><dd>{job.exit ?? '—'}</dd>
          {job.reason && <><dt>Důvod</dt><dd>{job.reason}</dd></>}
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
  const counts = s?.tests != null ? [`${num(s.tests)} testů`, s.passed != null && `${num(s.passed)} prošlo`, s.failed != null && `${num(s.failed)} selhalo`, s.skipped ? `${num(s.skipped)} přeskočeno` : null].filter(Boolean).join(' · ') : null;

  const open = async () => {
    setLog('loading');
    setLog((await bridge().jobs.log(job.id)) ?? 'unavailable');
  };

  if (job.status === 'queued') return <div className="muted">Job ještě neběžel{job.slot ? `, čeká na slot ${job.slot}` : ''}.</div>;
  if (job.status === 'running') return <div className="muted">Job běží. Souhrn a log jsou k dispozici po jeho skončení: daemon z nich nejdřív maskuje uložená tajemství.</div>;
  if (job.status === 'denied') return <div className="muted">Politika příkaz nepustila: {job.reason ?? 'bez uvedeného důvodu'}.</div>;
  return (
    <div>
      {counts && <div style={{ marginBottom: 8 }}><strong>{counts}</strong></div>}
      {s && s.failures.length > 0 && (
        <>
          <div className="muted">Chyby</div>
          <pre className="mono log failures">{s.failures.join('\n')}</pre>
        </>
      )}
      {s && s.tail.length > 0 && (
        <>
          <div className="muted">Poslední řádky</div>
          <pre className="mono log">{s.tail.join('\n')}</pre>
        </>
      )}
      {!s && <div className="muted">Daemon k jobu nemá souhrn{job.reason ? `: ${job.reason}` : ''}.</div>}
      <div style={{ marginTop: 8 }}>
        {log === null && <button className="btn" onClick={() => void open()}>Zobrazit celý log (posledních 400 řádků)</button>}
        {log === 'loading' && <span className="muted" role="status">Čtu log…</span>}
        {log === 'unavailable' && <span className="muted" role="status">Log se nepodařilo přečíst (soubor chybí nebo ho daemon nevydal).</span>}
        {log && typeof log === 'object' && (
          <>
            <div className="muted">{log.truncated ? 'Konec logu' : 'Celý log'} ({num(Math.round(log.bytes / 1024))} kB), bez barevných kódů, hodnoty podobné tajemstvím jsou skryté.</div>
            <pre className="mono log full" tabIndex={0} aria-label="Log jobu">{log.text}</pre>
          </>
        )}
      </div>
    </div>
  );
}
