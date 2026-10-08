import { useEffect, useRef, useState } from 'react';
import type { LiveEvent } from '../../shared/jobs';
import { bridge } from './api';

/** Calls `onEvent` for every event of the daemon while the component is on screen; the stream is open only meanwhile. */
export function useLive(onEvent: (e: LiveEvent) => void): void {
  const handler = useRef(onEvent);
  handler.current = onEvent;
  useEffect(() => bridge().live.subscribe(e => handler.current(e)), []);
}

/** `Date.now()` that ticks every `ms` while `active`, for a running job's elapsed time. */
export function useNow(active: boolean, ms = 1000): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!active) return;
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [active, ms]);
  return now;
}
