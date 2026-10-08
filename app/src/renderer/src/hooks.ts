import { useEffect, useState } from 'react';
import type { Range } from '../../shared/contract';
import type { DaemonState } from '../../shared/ipc';
import type { AppSettings } from '../../shared/settings';
import { bridge } from './api';

export function useDaemon(): DaemonState | null {
  const [state, setState] = useState<DaemonState | null>(null);
  useEffect(() => {
    void bridge().daemon.state().then(setState);
    return bridge().daemon.onState(setState);
  }, []);
  return state;
}

const settingsListeners = new Set<(s: AppSettings) => void>();

export function useSettings(): [AppSettings | null, (patch: Partial<AppSettings>) => Promise<void>] {
  const [s, setS] = useState<AppSettings | null>(null);
  useEffect(() => {
    void bridge().settings.get().then(setS);
    settingsListeners.add(setS);
    return () => { settingsListeners.delete(setS); };
  }, []);
  const update = async (patch: Partial<AppSettings>) => {
    const next = await bridge().settings.set(patch);
    for (const l of settingsListeners) l(next);
  };
  return [s, update];
}

export function publishSettings(next: AppSettings): void {
  for (const l of settingsListeners) l(next);
}

/** Shared time range, remembered per viewer; works without storage too. */
let currentRange: Range = (() => {
  try {
    const v = localStorage.getItem('codeloupe.range');
    return v === '24h' || v === '30d' ? v : '7d';
  } catch {
    return '7d';
  }
})();
const rangeListeners = new Set<(r: Range) => void>();

export function useRange(): [Range, (r: Range) => void] {
  const [r, setR] = useState<Range>(currentRange);
  useEffect(() => {
    rangeListeners.add(setR);
    return () => { rangeListeners.delete(setR); };
  }, []);
  const set = (v: Range) => {
    currentRange = v;
    try { localStorage.setItem('codeloupe.range', v); } catch { /* storage blocked */ }
    for (const l of rangeListeners) l(v);
  };
  return [r, set];
}

/** The account the Overview is narrowed to ('' = all), remembered per viewer like the range. */
let currentAccount = (() => {
  try { return localStorage.getItem('codeloupe.account') ?? ''; } catch { return ''; }
})();
const accountListeners = new Set<(a: string) => void>();

export function useAccount(): [string, (a: string) => void] {
  const [a, setA] = useState(currentAccount);
  useEffect(() => {
    accountListeners.add(setA);
    return () => { accountListeners.delete(setA); };
  }, []);
  const set = (v: string) => {
    currentAccount = v;
    try { localStorage.setItem('codeloupe.account', v); } catch { /* storage blocked */ }
    for (const l of accountListeners) l(v);
  };
  return [a, set];
}

/** The value after it stopped changing for `ms` (search fields: one request per pause, not per key). */
export function useDebounced<T>(value: T, ms = 250): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}
