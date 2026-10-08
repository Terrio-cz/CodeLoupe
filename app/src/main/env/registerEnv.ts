import { app, BrowserWindow, clipboard, dialog, systemPreferences } from 'electron';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import type { Environment } from '../../shared/contract';
import { ENV_CH } from '../../shared/envActions';
import type { AppSettings } from '../../shared/settings';
import type { ApiSource } from '../api/ApiSource';
import { EnvManager } from './EnvManager';
import { cliRunner } from './execCli';

export interface EnvContext {
  settings(): AppSettings;
  homeDir(): string;
  port(): number;
  source(): ApiSource;
}

/** Registers the channels of shared/envActions.ts; `handle` checks the sender, as for every other channel. */
export function registerEnv(ctx: EnvContext, handle: <A extends unknown[], R>(channel: string, fn: (...args: A) => Promise<R> | R) => void): EnvManager {
  const manager = new EnvManager({
    run: cliRunner(ctx.settings, ctx.homeDir),
    blocked: () => (ctx.source().kind === 'mock' ? 'The data source is Mock: the store does not change in this mode. Switch the data source to Daemon in Settings.' : null),
    confirm: nativeConfirm,
    consumers: async key => {
      const env = (await ctx.source().get({ resource: 'environment' }, '/ui-api/v1/environment')) as Environment;
      const [kind, ...rest] = key.scope.split(':');
      const ref = rest.join(':');
      return env.keys.find(k => k.name === key.name && k.scope === kind && (k.scopeRef ?? '').toLowerCase() === ref.toLowerCase())?.consumers ?? [];
    },
    reauth: touchId(),
    fetchValue: key => fetchValue(ctx, key, 'CodeLoupe app (clipboard copy)'),
    clipboard: { write: text => clipboard.writeText(text), read: () => clipboard.readText() },
    schedule: (fn, ms) => { setTimeout(fn, ms).unref(); },
  });
  handle(ENV_CH.capabilities, () => manager.capabilities());
  handle(ENV_CH.set, (input: unknown) => manager.set(input as Parameters<EnvManager['set']>[0]));
  handle(ENV_CH.remove, (input: unknown) => manager.remove(input as Parameters<EnvManager['remove']>[0]));
  handle(ENV_CH.scan, (includeExcluded: unknown) => manager.scan(includeExcluded === true));
  handle(ENV_CH.importRun, (input: unknown) => manager.importRun(input as Parameters<EnvManager['importRun']>[0]));
  handle(ENV_CH.rollback, (id: unknown) => manager.rollback(id as string));
  handle(ENV_CH.reveal, (input: unknown) => manager.reveal(input as Parameters<EnvManager['reveal']>[0]));
  return manager;
}

/** A native dialog the page cannot click. Verification runs of a development build answer it themselves; an installed app never does. */
export async function nativeConfirm(message: string, detail: string, okLabel: string): Promise<boolean> {
  if (!app.isPackaged && process.env.CODELOUPE_APP_CONFIRM === 'accept') return true;
  const win = BrowserWindow.getFocusedWindow();
  const opts = { type: 'warning' as const, buttons: [okLabel, 'Cancel'], defaultId: 1, cancelId: 1, title: 'CodeLoupe', message, detail };
  const { response } = win ? await dialog.showMessageBox(win, opts) : await dialog.showMessageBox(opts);
  return response === 0;
}

/** macOS asks for Touch ID; Windows and Linux have no prompt Electron can raise, so a value cannot be copied out there. */
function touchId(): (() => Promise<boolean>) | undefined {
  if (process.platform !== 'darwin' || !systemPreferences.canPromptTouchID()) return undefined;
  return async () => {
    try {
      await systemPreferences.promptTouchID('show a key value in CodeLoupe');
      return true;
    } catch {
      return false;
    }
  };
}

/** The token a local caller presents to `/env/values`; the daemon makes it on first use, so one unauthenticated request creates it. */
async function readToken(ctx: EnvContext): Promise<string | null> {
  const file = path.join(ctx.homeDir(), 'secrets', 'api-token.env');
  const read = () => { try { return fs.readFileSync(file, 'utf8').trim() || null; } catch { return null; } };
  return read() ?? (await get(ctx, '/env/values', {}), read());
}

/** One value through the daemon's token-guarded route, as the consumer [usedBy] named in the audit. */
export async function fetchValue(ctx: EnvContext, key: { name: string; scope: string }, usedBy: string): Promise<string | null> {
  const token = await readToken(ctx);
  if (!token) return null;
  const [kind, ...rest] = key.scope.split(':');
  const query = new URLSearchParams({ names: key.name });
  if (kind === 'workspace') query.set('workspace', rest.join(':'));
  if (kind === 'repo') query.set('repository', rest.join(':'));
  const body = await get(ctx, `/env/values?${query.toString()}`, { 'x-codeloupe-env-token': token, 'x-codeloupe-used-by': usedBy });
  try {
    const values = (JSON.parse(body ?? '') as { values?: Record<string, string> }).values ?? {};
    return typeof values[key.name] === 'string' ? values[key.name] : null;
  } catch { return null; }
}

function get(ctx: EnvContext, requestPath: string, headers: Record<string, string>): Promise<string | null> {
  return new Promise(resolve => {
    const req = http.get({ host: '127.0.0.1', port: ctx.port(), path: requestPath, timeout: 10_000, headers: { 'x-codeloupe': '1', ...headers } }, res => {
      const chunks: Buffer[] = [];
      res.on('data', c => chunks.push(c as Buffer));
      res.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    });
    req.on('error', () => resolve(null));
    req.on('timeout', () => { req.destroy(); resolve(null); });
  });
}
