// The screens of the app, one entry each: route id, title, sidebar icon (components/Icon.tsx) and group, `g <key>` shortcut.
// Adding a screen = an entry here + its component in views.tsx (+ a sidebar count in components/Sidebar.tsx).

import type { IconName } from './components/Icon';

export type ScreenGroup = 'top' | 'work' | 'index' | 'system';

export interface ScreenDef {
  id: string;
  title: string;
  icon: IconName;
  group: ScreenGroup;
  /** Second key of the `g <key>` navigation shortcut; unique across screens. */
  key: string;
  /** Shows the 24h/7d/30d range switch in the topbar. */
  range?: boolean;
}

export const SCREEN_DEFS = [
  { id: 'overview', title: 'Přehled', icon: 'overview', group: 'top', key: 'o', range: true },
  { id: 'branches', title: 'Větve', icon: 'branches', group: 'work', key: 'b' },
  { id: 'workspaces', title: 'Workspaces', icon: 'workspaces', group: 'work', key: 'w' },
  { id: 'tasks', title: 'Úkoly', icon: 'tasks', group: 'work', key: 't' },
  { id: 'jobs', title: 'Joby', icon: 'jobs', group: 'work', key: 'j' },
  { id: 'runs', title: 'Běhy', icon: 'runs', group: 'work', key: 'r', range: true },
  { id: 'index', title: 'Index', icon: 'index', group: 'index', key: 'i' },
  { id: 'gaps', title: 'Mezery', icon: 'gaps', group: 'index', key: 'g', range: true },
  { id: 'environment', title: 'Prostředí', icon: 'environment', group: 'system', key: 'e' },
  { id: 'accounts', title: 'Účty', icon: 'accounts', group: 'system', key: 'u' },
  { id: 'settings', title: 'Nastavení', icon: 'settings', group: 'system', key: 's' },
] as const satisfies readonly ScreenDef[];

export type Screen = (typeof SCREEN_DEFS)[number]['id'];

export const GROUP_TITLES: Record<Exclude<ScreenGroup, 'top'>, string> = { work: 'Práce', index: 'Index', system: 'Systém' };

const BY_ID: ReadonlyMap<string, ScreenDef> = new Map(SCREEN_DEFS.map(d => [d.id, d]));

export const isScreen = (id: string): id is Screen => BY_ID.has(id);
export const screenDef = (id: Screen): ScreenDef => BY_ID.get(id)!;
export const screensIn = (group: ScreenGroup): ScreenDef[] => SCREEN_DEFS.filter(d => d.group === group);
