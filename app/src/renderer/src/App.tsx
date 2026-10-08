import { useEffect, useRef, useState } from 'react';
import { bridge, refreshAll, useApi } from './api';
import { Icon } from './components/Icon';
import { RANGE_OPTIONS, Segmented } from './components/Parts';
import { Sidebar, sidebarCounts } from './components/Sidebar';
import { useDaemon, useRange, useSettings } from './hooks';
import { href, useRoute } from './router';
import { SCREEN_DEFS, screenDef } from './screenList';
import { VIEWS } from './views';

export function App() {
  const route = useRoute();
  const daemon = useDaemon();
  const [settings] = useSettings();
  const [range, setRange] = useRange();
  const [gapsSince] = useState(() => lastVisit());
  const [appVersion, setAppVersion] = useState('');
  const nav = useApi('nav', undefined, { gapsSince });
  const main = useRef<HTMLElement>(null);
  const [spin, setSpin] = useState(0);

  // Theme mirror for CSS; main also sets nativeTheme so the OS chrome follows.
  useEffect(() => {
    const t = settings?.theme;
    if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t;
    else delete document.documentElement.dataset.theme;
  }, [settings?.theme]);

  useEffect(() => bridge().onNavigate(h => { location.hash = h; }), []);
  useEffect(() => { void bridge().metrics().then(m => setAppVersion(m.version)); }, []);
  useEffect(() => { document.title = `${screenDef(route.screen).title} · CodeLoupe`; }, [route.screen]);
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

  // Back from a restart or an outage: reload what the screens failed to get meanwhile.
  const lastPhase = useRef(daemon?.phase);
  useEffect(() => {
    const prev = lastPhase.current;
    lastPhase.current = daemon?.phase;
    if (daemon?.phase === 'running' && prev && prev !== 'running') refreshAll();
  }, [daemon?.phase]);

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
      // The second key wins over a new prefix, so `g g` goes to Mezery.
      const target = SCREEN_DEFS.find(d => d.key === e.key);
      if (pendingG && Date.now() - pendingG < 1200 && target) {
        e.preventDefault();
        location.hash = href(target.id);
        pendingG = 0;
        return;
      }
      pendingG = e.key === 'g' ? Date.now() : 0;
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [settings?.shortcuts]);

  const def = screenDef(route.screen);
  return (
    <div className="shell">
      <Sidebar current={route.screen} counts={sidebarCounts(nav.data)} daemon={daemon} settings={settings} version={appVersion} />
      <main className="main" ref={main}>
        <header className="topbar">
          <div className="title" key={route.screen}>
            <span className="title-icon" aria-hidden="true"><Icon name={def.icon} /></span>
            <h1>{def.title}</h1>
          </div>
          <span className="spacer" />
          {def.range && <Segmented label="Časový rozsah" value={range} onChange={setRange} options={RANGE_OPTIONS} />}
          <button className="btn ghost icon-only" onClick={() => { setSpin(n => n + 1); refreshAll(); }} aria-label="Obnovit data (Ctrl+R)" title="Obnovit (Ctrl+R)">
            <Icon name="refresh" key={spin} className={spin ? 'spin' : undefined} />
          </button>
        </header>
        {/* Re-keyed per screen (and per task detail), so the new screen's blocks enter instead of swapping in place. */}
        <div className="content" key={route.screen === 'tasks' ? `tasks/${route.id ?? ''}` : route.screen}>
          {VIEWS[route.screen](route)}
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
