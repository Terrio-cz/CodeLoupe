import { useEffect, useState } from 'react';
import type { AppMetrics } from '../../../shared/ipc';
import { splitArgs, type AppSettings } from '../../../shared/settings';
import { bridge, useApi } from '../api';
import { ClaudeCodeCard } from '../components/ClaudeCodeCard';
import { UpdateCard } from '../components/UpdateCard';
import { Card, Segmented } from '../components/Parts';
import { num, tokens } from '../format';
import { publishSettings, useDaemon, useSettings } from '../hooks';

export function Settings() {
  const [settings, update] = useSettings();
  const daemon = useDaemon();
  const daemonSettings = useApi('settings');
  const [metrics, setMetrics] = useState<AppMetrics | null>(null);
  const [cmd, setCmd] = useState('');
  const [args, setArgs] = useState('');
  const [port, setPort] = useState('');
  const [saved, setSaved] = useState<string | null>(null);

  useEffect(() => {
    if (!settings) return;
    setCmd(settings.cliCommand);
    setArgs(settings.cliArgs.map(a => (/\s/.test(a) ? `"${a}"` : a)).join(' '));
    setPort(settings.portOverride ? String(settings.portOverride) : '');
  }, [settings?.cliCommand, settings?.cliArgs.join('\u0000'), settings?.portOverride]);

  useEffect(() => {
    const load = () => void bridge().metrics().then(setMetrics);
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, []);

  if (!settings) return null;
  const set = (patch: Partial<AppSettings>) => void update(patch).then(() => flash('Uloženo.'));
  const flash = (m: string) => { setSaved(m); setTimeout(() => setSaved(null), 4000); };
  const d = daemonSettings.data;

  return (
    <>
      <Card title="Aplikace">
        <div className="form-row">
          <span className="label" id="src-l">Zdroj dat</span>
          <Segmented label="Zdroj dat" value={settings.apiSource} onChange={v => set({ apiSource: v })}
            options={[{ value: 'daemon', label: 'Daemon' }, { value: 'mock', label: 'Mock data' }]} />
        </div>
        <div className="form-row">
          <label className="label" htmlFor="cli-cmd">Příkaz CLI</label>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <input id="cli-cmd" className="input mono" style={{ flex: 2 }} value={cmd} onChange={e => setCmd(e.target.value)} />
            <input aria-label="Argumenty CLI" className="input mono" style={{ flex: 3 }} value={args} onChange={e => setArgs(e.target.value)} placeholder="argumenty, např. -cp C:/…/codeloupe/lib/* codeloupe.MainKt" />
            <button className="btn" onClick={() => void bridge().settings.proposeCli(cmd.trim(), splitArgs(args)).then(s => { publishSettings(s); flash(s.cliCommand === cmd.trim() ? 'Příkaz uložen.' : 'Příkaz nezměněn.'); })}>
              Změnit…
            </button>
          </div>
        </div>
        <div className="form-row">
          <span className="label">Port daemonu</span>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <span>{daemon ? daemon.port : '—'} {settings.portOverride ? '(přepsáno)' : '(z daemon.json / config.json)'}</span>
            <input aria-label="Přepsat port" className="input" style={{ minWidth: 0, width: 110 }} inputMode="numeric" placeholder="přepsat" value={port} onChange={e => setPort(e.target.value.replace(/\D/g, ''))} />
            <button className="btn" onClick={() => set({ portOverride: port ? Number(port) : null })}>Použít</button>
          </div>
        </div>
        <div className="form-row">
          <span className="label">Daemon</span>
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            <label className="check"><input type="checkbox" checked={settings.autoStartDaemon} onChange={e => set({ autoStartDaemon: e.target.checked })} /> Spustit daemon, když neběží</label>
            <label className="check"><input type="checkbox" checked={settings.openAtLogin} onChange={e => set({ openAtLogin: e.target.checked })} /> Spouštět aplikaci po přihlášení</label>
          </div>
        </div>
        <div className="form-row">
          <span className="label">Vzhled</span>
          <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
            <Segmented label="Vzhled" value={settings.theme} onChange={v => set({ theme: v })}
              options={[{ value: 'system', label: 'Systém' }, { value: 'light', label: 'Světlý' }, { value: 'dark', label: 'Tmavý' }]} />
            <label className="check"><input type="checkbox" checked={settings.shortcuts} onChange={e => set({ shortcuts: e.target.checked })} /> Klávesové zkratky</label>
          </div>
        </div>
        <div className="form-row">
          <span className="label">Notifikace</span>
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            {([['budget', 'rozpočty'], ['builds', 'dokončené buildy'], ['gaps', 'nové mezery'], ['daemon', 'daemon spadl']] as const).map(([k, l]) => (
              <label key={k} className="check"><input type="checkbox" checked={settings.notify[k]} onChange={e => set({ notify: { ...settings.notify, [k]: e.target.checked } })} /> {l}</label>
            ))}
          </div>
        </div>
        {saved && <div className="toast" role="status">{saved}</div>}
      </Card>

      <ClaudeCodeCard />

      <UpdateCard />

      <Card title="Daemon (jen čtení)" actions={<button className="btn" onClick={() => void bridge().open.config()}>Ukázat config.json</button>}>
        {d ? (
          <dl className="dl">
            <dt>Home</dt><dd className="mono">{d.home}</dd>
            <dt>Port</dt><dd>{d.port}</dd>
            <dt>Repozitáře</dt><dd>{d.repos.map(r => <div key={r.id} className="mono">{r.path} ({r.baseRef})</div>)}</dd>
            <dt>YouTrack</dt><dd>{d.youtrack.map(y => <div key={y.url}>{y.url} · {y.projects.join(', ')} · token {y.tokenConfigured ? 'nastaven ✓' : 'chybí ✕'} · každých {y.pollSec} s</div>)}</dd>
            <dt>Rozpočty</dt><dd>denní {tokens(d.budgets.dailyWeighted)} · daemon {d.budgets.daemonRssMb} MB · build {d.budgets.buildPeakRssMb} MB</dd>
          </dl>
        ) : <span className="t2">{daemonSettings.error?.message ?? 'Načítám…'}</span>}
      </Card>

      <Card title="O aplikaci">
        {metrics && (
          <dl className="dl">
            <dt>Verze</dt><dd>{metrics.version} · Electron {metrics.electron}</dd>
            <dt>Paměť aplikace</dt><dd>working set {num(metrics.totalMb)} MB ({metrics.processes.map(p => `${p.type} ${p.mb}`).join(', ')}) · private {num(metrics.privateMb)} MB · limit 300 MB</dd>
          </dl>
        )}
      </Card>
    </>
  );
}
