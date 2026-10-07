import { useCallback, useEffect, useRef, useState } from 'react';
import type { Resource, ResourceMap } from '../../shared/contract';
import type { CodeLoupeBridge } from '../../shared/ipc';
import type { Query } from '../../shared/request';

declare global {
  interface Window {
    codeloupe: CodeLoupeBridge;
  }
}

export const bridge = (): CodeLoupeBridge => window.codeloupe;

export interface Loaded<T> {
  data: T | null;
  error: { code: string; message: string } | null;
  loading: boolean;
  reload(): void;
}

// Bumped by Ctrl+R and the topbar refresh button; every useApi on screen reloads.
const listeners = new Set<() => void>();
export function refreshAll(): void {
  for (const l of listeners) l();
}

/** Loads one API resource through the preload bridge; re-runs when the request changes or on refresh. */
export function useApi<R extends Resource>(resource: R | null, id?: string, query?: Query): Loaded<ResourceMap[R]> {
  const [state, setState] = useState<{ data: ResourceMap[R] | null; error: Loaded<unknown>['error']; loading: boolean }>({ data: null, error: null, loading: !!resource });
  const [tick, setTick] = useState(0);
  const key = JSON.stringify([resource, id, query]);
  const seq = useRef(0);

  useEffect(() => {
    const l = () => setTick(t => t + 1);
    listeners.add(l);
    return () => { listeners.delete(l); };
  }, []);

  useEffect(() => {
    if (!resource) return;
    const mine = ++seq.current;
    setState(s => ({ ...s, loading: true }));
    bridge().api<ResourceMap[R]>({ resource, id, query }).then(res => {
      if (mine !== seq.current) return;
      if (res.ok) setState({ data: res.data, error: null, loading: false });
      else setState(s => ({ data: s.data, error: { code: res.code, message: res.message }, loading: false }));
    }, (e: Error) => {
      if (mine === seq.current) setState(s => ({ data: s.data, error: { code: 'ipc', message: e.message }, loading: false }));
    });
  }, [key, tick]);

  const reload = useCallback(() => setTick(t => t + 1), []);
  return { ...state, reload };
}
