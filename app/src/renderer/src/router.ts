import { useEffect, useState } from 'react';

export type Screen = 'overview' | 'branches' | 'tasks' | 'index' | 'gaps' | 'environment' | 'settings';
export const SCREENS: Screen[] = ['overview', 'branches', 'tasks', 'index', 'gaps', 'environment', 'settings'];

export interface Route {
  screen: Screen;
  id: string | null;
  params: URLSearchParams;
}

/** `#/tasks/TER-1?x=1` → { screen: 'tasks', id, params }. Unknown routes fall back to the overview. */
export function parseHash(hash: string): Route {
  const raw = hash.replace(/^#\/?/, '');
  const [path, qs = ''] = raw.split('?');
  const [screen, id] = path.split('/');
  const s = (SCREENS as string[]).includes(screen) ? (screen as Screen) : 'overview';
  return { screen: s, id: id ? decodeURIComponent(id) : null, params: new URLSearchParams(qs) };
}

export function href(screen: Screen, id?: string | null, params?: Record<string, string | number>): string {
  const qs = params ? `?${new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)])).toString()}` : '';
  return `#/${screen}${id ? `/${encodeURIComponent(id)}` : ''}${qs}`;
}

export function go(screen: Screen, id?: string | null, params?: Record<string, string | number>): void {
  location.hash = href(screen, id, params);
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseHash(location.hash));
  useEffect(() => {
    const on = () => setRoute(parseHash(location.hash));
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return route;
}
