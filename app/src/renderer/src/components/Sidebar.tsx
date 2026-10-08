import type { ReactNode } from 'react';
import type { Nav } from '../../../shared/contract';
import type { DaemonState } from '../../../shared/ipc';
import type { AppSettings } from '../../../shared/settings';
import { GROUP_TITLES, screensIn, type ScreenDef, type ScreenGroup } from '../screenList';
import { href, type Screen } from '../router';
import { PhaseBadge, RepoBadge } from './StatusBadge';

/** What each sidebar entry shows on its right; an entry without a count is left out. */
export function sidebarCounts(nav: Nav | null): Partial<Record<Screen, ReactNode>> {
  if (!nav) return {};
  return {
    branches: nav.activeWorktrees,
    tasks: nav.openTasks,
    gaps: nav.newGaps ? <span title="nové od poslední návštěvy">{nav.newGaps} nové</span> : undefined,
    index: <RepoBadge state={nav.indexState} />,
  };
}

const GROUPS: Exclude<ScreenGroup, 'top'>[] = ['work', 'index', 'system'];

export function Sidebar({ current, counts, daemon, settings, version }: {
  current: Screen;
  counts: Partial<Record<Screen, ReactNode>>;
  daemon: DaemonState | null;
  settings: AppSettings | null;
  version: string;
}) {
  const link = (d: ScreenDef) => (
    <a key={d.id} className="nav-link" href={href(d.id as Screen)} aria-current={current === d.id ? 'page' : undefined} title={d.title}>
      <span className="nav-icon" aria-hidden="true">{d.icon}</span>
      <span className="label-text">{d.title}</span>
      {counts[d.id as Screen] !== undefined && <span className="count">{counts[d.id as Screen]}</span>}
    </a>
  );
  const st = daemon?.status;
  return (
    <aside className="sidebar">
      <div className="brand"><span aria-hidden="true">◎</span><span className="label-text">CodeLoupe</span><small>{version && `v${version}`}</small></div>
      <nav aria-label="Hlavní navigace" style={{ display: 'contents' }}>
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
            {st ? `${st.rssMb} MB · fronta ${st.queue.fast.waiting.length + st.queue.heavy.waiting.length} · :${daemon?.port}` : daemon?.message ?? `port ${daemon?.port ?? '—'}`}
          </div>
          {settings?.apiSource === 'mock' && <div className="detail muted">Data: mock</div>}
        </div>
      </div>
    </aside>
  );
}
