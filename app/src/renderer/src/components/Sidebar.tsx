import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import type { Nav } from '../../../shared/contract';
import type { DaemonState } from '../../../shared/ipc';
import type { AppSettings } from '../../../shared/settings';
import { GROUP_TITLES, screensIn, type ScreenDef, type ScreenGroup } from '../screenList';
import { href, type Screen } from '../router';
import { Icon } from './Icon';
import { PhaseBadge, RepoBadge } from './StatusBadge';

/** What each sidebar entry shows on its right; an entry without a count is left out. */
export function sidebarCounts(nav: Nav | null): Partial<Record<Screen, ReactNode>> {
  if (!nav) return {};
  return {
    branches: nav.activeWorktrees,
    tasks: nav.openTasks,
    gaps: nav.newGaps ? <span title="new since your last visit">{nav.newGaps} new</span> : undefined,
    index: <RepoBadge state={nav.indexState} />,
  };
}

const GROUPS: Exclude<ScreenGroup, 'top'>[] = ['work', 'index', 'system'];

/** The highlight behind the current entry slides to the next one instead of jumping. */
function useIndicator(current: Screen) {
  const nav = useRef<HTMLElement>(null);
  const [box, setBox] = useState<{ top: number; height: number; animate: boolean } | null>(null);
  useLayoutEffect(() => {
    const el = nav.current;
    if (!el) return;
    const place = (animate: boolean) => {
      const link = el.querySelector<HTMLElement>('[aria-current="page"]');
      setBox(link ? { top: link.offsetTop, height: link.offsetHeight, animate } : null);
    };
    place(box !== null);
    const ro = new ResizeObserver(() => place(false));
    ro.observe(el);
    return () => ro.disconnect();
  }, [current]);
  return { nav, box };
}

export function Sidebar({ current, counts, daemon, settings }: {
  current: Screen;
  counts: Partial<Record<Screen, ReactNode>>;
  daemon: DaemonState | null;
  settings: AppSettings | null;
}) {
  const { nav, box } = useIndicator(current);
  const link = (d: ScreenDef) => (
    <a key={d.id} className="nav-link" href={href(d.id as Screen)} aria-current={current === d.id ? 'page' : undefined} title={d.title}>
      <span className="nav-icon" aria-hidden="true"><Icon name={d.icon} /></span>
      <span className="label-text">{d.title}</span>
      {counts[d.id as Screen] !== undefined && <span className="count">{counts[d.id as Screen]}</span>}
    </a>
  );
  const st = daemon?.status;
  return (
    <aside className="sidebar">
      <nav ref={nav} className="nav" aria-label="Main navigation">
        {box && (
          <span
            className="nav-indicator"
            aria-hidden="true"
            style={{ transform: `translateY(${box.top}px)`, height: box.height, transition: box.animate ? undefined : 'none' }}
          />
        )}
        {screensIn('top').map(link)}
        {GROUPS.map(g => (
          <div key={g} style={{ display: 'contents' }}>
            <div className="nav-group">{GROUP_TITLES[g]}</div>
            {screensIn(g).map(link)}
          </div>
        ))}
      </nav>
      <div className="sidebar-foot">
        <div className="daemon-pill">
          <div className="line" aria-live="polite">{daemon ? <PhaseBadge phase={daemon.phase} /> : 'Daemon …'}</div>
          <div className="detail muted">
            {st ? `${st.rssMb} MB · queue ${st.queue.fast.waiting.length + st.queue.heavy.waiting.length} · :${daemon?.port}` : daemon?.message ?? `port ${daemon?.port ?? '—'}`}
          </div>
          {settings?.apiSource === 'mock' && <span className="chip tag">Data: mock</span>}
        </div>
      </div>
    </aside>
  );
}
