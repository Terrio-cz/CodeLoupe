import { useEffect, useState } from 'react';
import type { AppMetrics } from '../../../shared/ipc';
import { splitArgs, type AppSettings } from '../../../shared/settings';
import { bridge, useApi } from '../api';
import { ClaudeCodeCard } from '../components/ClaudeCodeCard';
import { Card, Loading, Segmented, Toast } from '../components/Parts';
import { UpdateCard } from '../components/UpdateCard';
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

  if (!settings) return <Card title="Application"><Loading /></Card>;
  const set = (patch: Partial<AppSettings>) => void update(patch).then(() => flash('Saved.'));
  const flash = (m: string) => { setSaved(m); setTimeout(() => setSaved(null), 4000); };
  const d = daemonSettings.data;

  return (
    <>
      <Card title="Application">
        <div className="form-row">
          <span className="label" id="src-l">Data source</span>
          <Segmented label="Data source" value={settings.apiSource} onChange={v => set({ apiSource: v })}
            options={[{ value: 'daemon', label: 'Daemon' }, { value: 'mock', label: 'Mock data' }]} />
        </div>
        <div className="form-row">
          <label className="label" htmlFor="cli-cmd">CLI command</label>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <input id="cli-cmd" className="input mono" style={{ flex: 2 }} value={cmd} onChange={e => setCmd(e.target.value)} />
            <input aria-label="CLI arguments" className="input mono" style={{ flex: 3 }} value={args} onChange={e => setArgs(e.target.value)} placeholder="arguments, e.g. -cp C:/…/codeloupe/lib/* codeloupe.MainKt" />
            <button className="btn" onClick={() => void bridge().settings.proposeCli(cmd.trim(), splitArgs(args)).then(s => { publishSettings(s); flash(s.cliCommand === cmd.trim() ? 'Command saved.' : 'Command unchanged.'); })}>
              Change…
            </button>
          </div>
        </div>
        <div className="form-row">
          <span className="label">Daemon port</span>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <span>{daemon ? daemon.port : '—'} {settings.portOverride ? '(overridden)' : '(from daemon.json / config.json)'}</span>
            <input aria-label="Override port" className="input" style={{ minWidth: 0, width: 110 }} inputMode="numeric" placeholder="override" value={port} onChange={e => setPort(e.target.value.replace(/\D/g, ''))} />
            <button className="btn" onClick={() => set({ portOverride: port ? Number(port) : null })}>Apply</button>
          </div>
        </div>
        <div className="form-row">
          <span className="label">Daemon</span>
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            <label className="check"><input type="checkbox" checked={settings.autoStartDaemon} onChange={e => set({ autoStartDaemon: e.target.checked })} /> Start the daemon when it is not running</label>
            <label className="check"><input type="checkbox" checked={settings.openAtLogin} onChange={e => set({ openAtLogin: e.target.checked })} /> Open the app at login</label>
          </div>
        </div>
        <div className="form-row">
          <span className="label">Appearance</span>
          <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
            <Segmented label="Appearance" value={settings.theme} onChange={v => set({ theme: v })}
              options={[{ value: 'system', label: 'System' }, { value: 'light', label: 'Light' }, { value: 'dark', label: 'Dark' }]} />
            <label className="check"><input type="checkbox" checked={settings.shortcuts} onChange={e => set({ shortcuts: e.target.checked })} /> Keyboard shortcuts</label>
          </div>
        </div>
        <div className="form-row">
          <span className="label">Notifications</span>
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            {([['budget', 'budgets'], ['builds', 'finished builds'], ['gaps', 'new gaps'], ['daemon', 'daemon crashed']] as const).map(([k, l]) => (
              <label key={k} className="check"><input type="checkbox" checked={settings.notify[k]} onChange={e => set({ notify: { ...settings.notify, [k]: e.target.checked } })} /> {l}</label>
            ))}
          </div>
        </div>
        {saved && <Toast>{saved}</Toast>}
      </Card>

      <ClaudeCodeCard />

      <Card title="Setup guide" actions={<button className="btn" onClick={() => { location.hash = '#/settings?welcome=1'; }}>Open guide</button>}>
        <p className="t2">Repositories, a YouTrack account, the Claude Code connection and a test query in one pass. Every step can be skipped.</p>
      </Card>

      <UpdateCard />

      <Card title="Daemon (read only)" actions={<button className="btn" onClick={() => void bridge().open.config()}>Show config.json</button>}>
        {d ? (
          <dl className="dl">
            <dt>Home</dt><dd className="mono">{d.home}</dd>
            <dt>Port</dt><dd>{d.port}</dd>
            <dt>Repositories</dt><dd>{d.repos.map(r => <div key={r.id} className="mono">{r.path} ({r.baseRef})</div>)}</dd>
            <dt>YouTrack</dt><dd>{d.youtrack.map(y => <div key={y.url}>{y.url} · {y.projects.join(', ')} · token {y.tokenConfigured ? 'set ✓' : 'missing ✕'} · every {y.pollSec} s</div>)}</dd>
            <dt>Budgets</dt><dd>daily {tokens(d.budgets.dailyWeighted)} · daemon {d.budgets.daemonRssMb} MB · build {d.budgets.buildPeakRssMb} MB</dd>
          </dl>
        ) : <span className="t2">{daemonSettings.error?.message ?? 'Loading…'}</span>}
      </Card>

      <Card title="About">
        {metrics && (
          <dl className="dl">
            <dt>Version</dt><dd>{metrics.version} · Electron {metrics.electron}</dd>
            <dt>App memory</dt><dd>working set {num(metrics.totalMb)} MB ({metrics.processes.map(p => `${p.type} ${p.mb}`).join(', ')}) · private {num(metrics.privateMb)} MB · limit 300 MB</dd>
          </dl>
        )}
      </Card>
    </>
  );
}
