import { useEffect, useRef, useState } from 'react';
import { bridge, refreshAll, useApi } from './api';
import { RANGE_OPTIONS, Segmented } from './components/Parts';
import { PhaseBadge, RepoBadge } from './components/StatusBadge';
import { useDaemon, useRange, useSettings } from './hooks';
import { href, useRoute, type Screen } from './router';
import { Branches } from './screens/Branches';
import { Environment } from './screens/Environment';
import { Gaps } from './screens/Gaps';
import { IndexScreen } from './screens/IndexScreen';
import { Overview } from './screens/Overview';
import { Settings } from './screens/Settings';
import { Tasks } from './screens/Tasks';

const TITLES: Record<Screen, string> = {
  overview: 'Přehled', branches: 'Větve', tasks: 'Úkoly', index: 'Index', gaps: 'Mezery',
  environment: 'Prostředí', settings: 'Nastavení',
};
const KEYS: Record<string, Screen> = { o: 'overview', b: 'branches', t: 'tasks', i: 'index', g: 'gaps', e: 'environment', s: 'settings' };
const WITH_RANGE: Screen[] = ['overview', 'gaps'];

export function App() {
  const route = useRoute();
  const daemon = useDaemon();
  const [settings] = useSettings();
  const [range, setRange] = useRange();
  const [gapsSince] = useState(() => lastVisit());
  const nav = useApi('nav', undefined, { gapsSince });
  const main = useRef<HTMLElement>(null);

  // Theme mirror for CSS; main also sets nativeTheme so the OS chrome follows.
  useEffect(() => {
    const t = settings?.theme;
    if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t;
    else delete document.documentElement.dataset.theme;
  }, [settings?.theme]);

  useEffect(() => bridge().onNavigate(h => { location.hash = h; }), []);
  useEffect(() => { document.title = `${TITLES[route.screen]} · CodeLoupe`; }, [route.screen]);
  useEffect(() => {
    if (route.screen === 'gaps') try { localStorage.setItem('codeloupe.gapsSeen', new Date().toISOString()); } catch { /* storage blocked */ }
  }, [route.screen]);

  // Sidebar counts follow the daemon status tick, at most every 15 s.
  const lastNav = useRef(0);
  useEffect(() => {
    if (Date.now() - lastNav.current < 15_000) return;
    lastNav.current = Date.now();
    nav.reload();
  }, [daemon?.checkedAt]);

  useEffect(() => {
    let pendingG = 0;
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'r') { e.preventDefault(); refreshAll(); return; }
      if (!settings?.shortcuts || e.ctrlKey || e.metaKey || e.altKey) return;
      const t = e.target as HTMLElement;
      if (t.closest('input, select, textarea, [role="dialog"]')) return;
      if (e.key === '/') {
        const s = document.querySelector<HTMLInputElement>('[data-search]');
        if (s) { e.preventDefault(); s.focus(); }
        return;
      }
      if (e.key === 'g') { pendingG = Date.now(); return; }
      if (pendingG && Date.now() - pendingG < 1200 && KEYS[e.key]) {
        e.preventDefault();
        location.hash = href(KEYS[e.key]);
      }
      pendingG = 0;
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [settings?.shortcuts]);

  const counts: Partial<Record<Screen, React.ReactNode>> = nav.data ? {
    branches: nav.data.activeWorktrees,
    tasks: nav.data.openTasks,
    gaps: nav.data.newGaps ? <span title="nové od poslední návštěvy">{nav.data.newGaps} nové</span> : undefined,
    index: <RepoBadge state={nav.data.indexState} />,
  } : {};

  const link = (s: Screen) => (
    <a key={s} className="nav-link" href={href(s)} aria-current={route.screen === s ? 'page' : undefined}>
      <span className="label-text">{TITLES[s]}</span>
      {counts[s] !== undefined && <span className="count">{counts[s]}</span>}
    </a>
  );

  const st = daemon?.status;
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand"><span aria-hidden="true">◎</span><span className="label-text">CodeLoupe</span><small>v0.4</small></div>
        <nav aria-label="Hlavní navigace" style={{ display: 'contents' }}>
          {link('overview')}
          <div className="nav-group">Práce</div>
          {link('branches')}{link('tasks')}
          <div className="nav-group">Index</div>
          {link('index')}{link('gaps')}
          <div className="nav-group">Systém</div>
          {link('environment')}{link('settings')}
        </nav>
        <div className="sidebar-foot">
          <div className="daemon-pill">
            <div className="line" aria-live="polite">{daemon ? <PhaseBadge phase={daemon.phase} /> : 'Daemon …'}</div>
            <div className="detail muted">
              {st ? `${st.rssMb} MB · fronta ${st.queue.fast.waiting.length + st.queue.heavy.waiting.length} · :${daemon?.port}` : daemon?.message ?? `port ${daemon?.port ?? '—'}`}
            </div>
            {settings?.apiSource === 'mock' && <div className="detail muted">Data: mock</div>}
          </div>
        </div>
      </aside>
      <main className="main" ref={main}>
        <header className="topbar">
          <h1>{TITLES[route.screen]}</h1>
          <span className="spacer" />
          {WITH_RANGE.includes(route.screen) && <Segmented label="Časový rozsah" value={range} onChange={setRange} options={RANGE_OPTIONS} />}
          <button className="btn ghost" onClick={refreshAll} aria-label="Obnovit data (Ctrl+R)" title="Obnovit (Ctrl+R)">⟳</button>
        </header>
        <div className="content">
          {route.screen === 'overview' && <Overview />}
          {route.screen === 'branches' && <Branches route={route} />}
          {route.screen === 'tasks' && <Tasks route={route} />}
          {route.screen === 'index' && <IndexScreen />}
          {route.screen === 'gaps' && <Gaps />}
          {route.screen === 'environment' && <Environment />}
          {route.screen === 'settings' && <Settings />}
        </div>
      </main>
    </div>
  );
}

function lastVisit(): string {
  try {
    return localStorage.getItem('codeloupe.gapsSeen') ?? new Date(Date.now() - 86_400_000).toISOString();
  } catch {
    return new Date(Date.now() - 86_400_000).toISOString();
  }
}
