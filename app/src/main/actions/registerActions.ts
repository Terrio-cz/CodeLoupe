import { app, BrowserWindow, dialog } from 'electron';
import { ACTION_CH, type ActionOutcome, type ReconcileOutcome } from '../../shared/actions';
import type { DaemonClient } from '../daemon/DaemonClient';
import { WorkspaceActions, type Confirm } from './WorkspaceActions';

export interface ActionContext {
  client: DaemonClient;
  /** The daemon answers for real: false in mock mode, or while a foreign process holds the port. */
  daemonTrusted(): boolean;
  /** Whether screens read mock data: actions then change nothing. */
  mock(): boolean;
}

const MOCK: ActionOutcome = { ok: false, message: 'Akce mění skutečný daemon; přepněte zdroj dat na Daemon (Nastavení).' };
const DOWN: ActionOutcome = { ok: false, message: 'Daemon neodpovídá.' };

/** Registers the channels of shared/actions.ts; `handle` checks the sender, as for every other channel. */
export function registerActions(ctx: ActionContext, handle: <A extends unknown[], R>(channel: string, fn: (...args: A) => Promise<R> | R) => void): void {
  const workspaces = new WorkspaceActions(ctx.client, nativeConfirm);
  const guarded = <T extends ActionOutcome>(fn: (input: unknown) => Promise<T>, extra: Omit<T, keyof ActionOutcome>) => async (input: unknown): Promise<T> => {
    if (ctx.mock()) return { ...MOCK, ...extra } as T;
    if (!ctx.daemonTrusted()) return { ...DOWN, ...extra } as T;
    try {
      return await fn(input);
    } catch (e) {
      return { ok: false, message: `Daemon akci odmítl: ${(e as Error).message}`, ...extra } as T;
    }
  };

  handle(ACTION_CH.workspaceRelease, guarded<ActionOutcome>(input => workspaces.release(input), {}));
  handle(ACTION_CH.reconcileRun, guarded<ReconcileOutcome>(input => workspaces.reconcile(input), { results: [] }));
}

// Verification runs (development builds only): CODELOUPE_APP_AUTOCONFIRM=accept|decline answers the dialogs and prints their text.
const AUTOCONFIRM = !app.isPackaged ? process.env.CODELOUPE_APP_AUTOCONFIRM : undefined;

const nativeConfirm: Confirm = async ({ message, detail, accept }) => {
  if (AUTOCONFIRM === 'accept' || AUTOCONFIRM === 'decline') {
    console.log(`[codeloupe] confirm dialog (${AUTOCONFIRM}): ${message}
${detail}`);
    return AUTOCONFIRM === 'accept';
  }
  const win = BrowserWindow.getFocusedWindow();
  const opts = { type: 'warning' as const, buttons: [accept, 'Zrušit'], defaultId: 1, cancelId: 1, title: 'CodeLoupe', message, detail };
  const { response } = win ? await dialog.showMessageBox(win, opts) : await dialog.showMessageBox(opts);
  return response === 0;
};
