import { useCallback, useState } from 'react';
import type { ActionOutcome } from '../../shared/actions';

/** Runs one action at a time and keeps its last outcome for the page to show; a rejected call becomes a failed outcome. */
export function useAction<T extends ActionOutcome>(): { busy: boolean; outcome: T | null; run(fn: () => Promise<T>): Promise<T | null>; clear(): void } {
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<T | null>(null);
  const run = useCallback(async (fn: () => Promise<T>) => {
    setBusy(true);
    setOutcome(null);
    try {
      const result = await fn();
      setOutcome(result);
      return result;
    } catch (e) {
      setOutcome({ ok: false, message: (e as Error).message } as T);
      return null;
    } finally {
      setBusy(false);
    }
  }, []);
  return { busy, outcome, run, clear: () => setOutcome(null) };
}
