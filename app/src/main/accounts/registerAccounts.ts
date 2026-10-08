import os from 'node:os';
import { ACCOUNT_CH } from '../../shared/accountActions';
import { cliRunner } from '../env/execCli';
import { fetchValue, nativeConfirm, type EnvContext } from '../env/registerEnv';
import { AccountsManager } from './AccountsManager';

export interface AccountsContext extends EnvContext {
  restartDaemon(): Promise<unknown>;
}

const HTTP_TIMEOUT_MS = 10_000;
const MAX_BODY = 64 * 1024;

/** Registers the channels of shared/accountActions.ts; `handle` checks the sender, as for every other channel. */
export function registerAccounts(ctx: AccountsContext, handle: <A extends unknown[], R>(channel: string, fn: (...args: A) => Promise<R> | R) => void): AccountsManager {
  const manager = new AccountsManager({
    homeDir: ctx.homeDir,
    userHome: () => os.homedir(),
    run: cliRunner(ctx.settings, ctx.homeDir),
    confirm: nativeConfirm,
    fetchStored: name => fetchValue(ctx, { name, scope: 'global' }, 'CodeLoupe app (test spojení)'),
    // Redirects are not followed: the bearer token goes to the instance the user named and nowhere else.
    http: async (url, headers) => {
      const r = await fetch(url, { headers, redirect: 'manual', signal: AbortSignal.timeout(HTTP_TIMEOUT_MS) });
      return { status: r.status, body: (await r.text()).slice(0, MAX_BODY) };
    },
    restartDaemon: async () => { await ctx.restartDaemon(); },
    blocked: () => (ctx.source().kind === 'mock' ? 'Zdroj dat je Mock: účty se v tomto režimu nemění. Přepněte v Nastavení zdroj dat na Daemon.' : null),
  });
  handle(ACCOUNT_CH.claudeAdd, (input: unknown) => manager.claudeAdd(input as Parameters<AccountsManager['claudeAdd']>[0]));
  handle(ACCOUNT_CH.claudeRename, (id: unknown, label: unknown) => manager.claudeRename(String(id), String(label)));
  handle(ACCOUNT_CH.claudeDefault, (id: unknown) => manager.claudeSetDefault(String(id)));
  handle(ACCOUNT_CH.claudeRemove, (id: unknown) => manager.claudeRemove(String(id)));
  handle(ACCOUNT_CH.youtrackAdd, (input: unknown) => manager.youtrackAdd(input as Parameters<AccountsManager['youtrackAdd']>[0]));
  handle(ACCOUNT_CH.youtrackTest, (id: unknown) => manager.youtrackTest(String(id)));
  handle(ACCOUNT_CH.youtrackRotate, (id: unknown, token: unknown) => manager.youtrackRotate(String(id), typeof token === 'string' ? token : ''));
  handle(ACCOUNT_CH.youtrackRemove, (id: unknown) => manager.youtrackRemove(String(id)));
  return manager;
}
