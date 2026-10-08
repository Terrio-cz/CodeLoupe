import { app, BrowserWindow, dialog, ipcMain, shell, type IpcMainInvokeEvent } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import type { DaemonSettings, WorktreeDetail } from '../shared/contract';
import { CH, type ApiResult, type AppMetrics, type ClaudeConnectKind } from '../shared/ipc';
import { validateRequest, type ApiRequest } from '../shared/request';
import { applyRendererUpdate, isValidCli, type AppSettings } from '../shared/settings';
import type { ApiSource } from './api/ApiSource';
import { HttpError, type DaemonClient } from './daemon/DaemonClient';
import { commandLines, type ClaudeConnector } from './claude/ClaudeConnector';
import type { DaemonHome } from './daemon/DaemonHome';
import type { DaemonManager } from './daemon/DaemonManager';
import type { SettingsStore } from './settingsStore';
import { registerActions } from './actions/registerActions';
import { JOB_CH } from '../shared/jobs';
import { JobLogReader } from './jobs/JobLogReader';
import { EventStream } from './live/EventStream';
import { registerLive } from './live/registerLive';
import { GapReportRefresh } from './gaps/GapReportRefresh';
import { registerEnv } from './env/registerEnv';
import { registerAccounts } from './accounts/registerAccounts';

export interface IpcContext {
  store: SettingsStore;
  manager: DaemonManager;
  client: DaemonClient;
  home: DaemonHome;
  claude: ClaudeConnector;
  source(): ApiSource;
  /** Origins the renderer may be loaded from: app://codeloupe, plus the Vite dev server in development. */
  trustedOrigins: string[];
  applySettings(prev: AppSettings, next: AppSettings): void;
}

/** Registers every IPC channel; each handler checks that the call comes from the app's own page. */
export function registerIpc(ctx: IpcContext): { onWindowClosed(): void } {
  const handle = <A extends unknown[], R>(channel: string, fn: (...args: A) => Promise<R> | R) => {
    ipcMain.handle(channel, (event: IpcMainInvokeEvent, ...args: unknown[]) => {
      if (!trusted(event, ctx.trustedOrigins)) throw new Error('untrusted sender');
      return fn(...(args as A));
    });
  };

  registerActions({
    gapsRefresh: new GapReportRefresh(() => ctx.store.get(), () => ctx.home.dir),
    client: ctx.client, daemonTrusted: () => ctx.manager.trusted, mock: () => ctx.source().kind === 'mock',
  }, handle);
  const stream = new EventStream(() => ctx.manager.port(), e => {
    for (const w of BrowserWindow.getAllWindows()) if (!w.isDestroyed()) w.webContents.send(JOB_CH.livePush, e);
  });
  const live = registerLive(stream, new JobLogReader(ctx.client, () => ctx.home.dir), handle, () => ctx.source().kind === 'daemon' && ctx.manager.trusted);
  const envContext = { settings: () => ctx.store.get(), homeDir: () => ctx.home.dir, port: () => ctx.manager.port(), source: ctx.source };
  registerEnv(envContext, handle);
  registerAccounts({ ...envContext, restartDaemon: () => ctx.manager.restart() }, handle);
  handle(CH.api, (req: unknown) => callApi(ctx, req));
  handle(CH.daemonState, () => ctx.manager.check());
  handle(CH.daemonStart, () => ctx.manager.start());
  handle(CH.daemonStop, () => ctx.manager.stop());
  handle(CH.daemonRestart, () => ctx.manager.restart());
  handle(CH.settingsGet, () => ctx.store.get());
  handle(CH.settingsSet, (patch: unknown) => {
    const prev = ctx.store.get();
    const next = ctx.store.save(applyRendererUpdate(prev, patch));
    ctx.applySettings(prev, next);
    return next;
  });
  handle(CH.settingsProposeCli, async (command: unknown, args: unknown) => {
    if (!isValidCli(command, args)) throw new Error('invalid command');
    // The renderer only proposes; the user confirms in a native dialog the page cannot click.
    const win = BrowserWindow.getFocusedWindow();
    const opts = {
      type: 'question' as const,
      buttons: ['Uložit', 'Zrušit'],
      defaultId: 1,
      cancelId: 1,
      title: 'CodeLoupe',
      message: 'Změnit příkaz pro spouštění daemonu?',
      detail: [command as string, ...args].map(a => (/\s/.test(a) ? `"${a}"` : a)).join(' '),
    };
    const { response } = win ? await dialog.showMessageBox(win, opts) : await dialog.showMessageBox(opts);
    if (response !== 0) return ctx.store.get();
    return ctx.store.save({ ...ctx.store.get(), cliCommand: (command as string).trim(), cliArgs: args });
  });
  handle(CH.claudeStatus, () => ctx.claude.status());
  handle(CH.claudeManual, (kind: unknown) => commandLines(claudeKind(kind), ctx.manager.port(), ctx.claude.marketplaceDir()));
  handle(CH.claudeConnect, async (kind: unknown) => {
    const k = claudeKind(kind);
    const port = ctx.manager.port();
    // The renderer only asks; the user sees the exact commands in a native dialog the page cannot click.
    const win = BrowserWindow.getFocusedWindow();
    const opts = {
      type: 'question' as const,
      buttons: ['Připojit', 'Zrušit'],
      defaultId: 1,
      cancelId: 1,
      title: 'CodeLoupe',
      message: k === 'mcp' ? 'Přidat CodeLoupe do Claude Code jako MCP server (uživatelská úroveň)?' : 'Nainstalovat plugin CodeLoupe do Claude Code?',
      detail: commandLines(k, port, ctx.claude.marketplaceDir()).join('\n'),
    };
    const { response } = win ? await dialog.showMessageBox(win, opts) : await dialog.showMessageBox(opts);
    if (response !== 0) return 'cancelled' as const;
    return ctx.claude.connect(k, port);
  });
  handle(CH.metrics, () => metrics());
  handle(CH.openWorktree, async (id: unknown) => {
    if (!daemonTrusted(ctx)) return false;
    const req = validateRequest({ resource: 'worktrees/:id', id });
    if (!req.ok) return false;
    const wt = (await ctx.source().get(req.request, req.path).catch(() => null)) as WorktreeDetail | null;
    if (!wt?.path) return false;
    const dir = path.resolve(wt.path);
    // Only an existing git worktree directory; never a file that the shell would execute.
    if (!isDir(dir) || !fs.existsSync(path.join(dir, '.git'))) return false;
    return (await shell.openPath(dir)) === '';
  });
  handle(CH.openConfig, () => {
    const file = ctx.home.configFile;
    if (fs.existsSync(file)) shell.showItemInFolder(file);
    else if (isDir(ctx.home.dir)) void shell.openPath(ctx.home.dir);
    else return false;
    return true;
  });
  handle(CH.openExternal, async (url: unknown) => {
    if (typeof url !== 'string' || url.length > 2000 || !daemonTrusted(ctx)) return false;
    let target: URL;
    try { target = new URL(url); } catch { return false; }
    if (target.protocol !== 'https:') return false;
    const req = validateRequest({ resource: 'settings' });
    if (!req.ok) return false;
    const settings = (await ctx.source().get(req.request, req.path).catch(() => null)) as DaemonSettings | null;
    const allowed = new Set((settings?.youtrack ?? []).map(y => { try { return new URL(y.url).origin; } catch { return null; } }));
    if (!allowed.has(target.origin)) return false;
    await shell.openExternal(target.toString());
    return true;
  });
  return { onWindowClosed: live.reset };
}

async function callApi(ctx: IpcContext, input: unknown): Promise<ApiResult<unknown>> {
  const v = validateRequest(input);
  if (!v.ok) return { ok: false, code: 'bad_request', message: v.error };
  const source = ctx.source();
  if (source.kind === 'daemon' && !daemonTrusted(ctx)) {
    return { ok: false, code: 'unavailable', message: ctx.manager.current.message ?? 'Daemon neběží.' };
  }
  try {
    return { ok: true, data: await source.get(v.request as ApiRequest, v.path) };
  } catch (e) {
    if (e instanceof HttpError) {
      const message = e.status === 404 && source.kind === 'daemon' ? 'Daemon tento endpoint zatím neposkytuje (CL-39).' : e.message;
      return { ok: false, code: e.code, message };
    }
    return { ok: false, code: 'unavailable', message: (e as Error).message };
  }
}

/** Mock data does not need the daemon; real data and anything opened from it need the verified daemon. */
function daemonTrusted(ctx: IpcContext): boolean {
  return ctx.source().kind === 'mock' || ctx.manager.trusted;
}

function trusted(event: IpcMainInvokeEvent, origins: string[]): boolean {
  const url = event.senderFrame?.url;
  if (!url) return false;
  try {
    // URL.origin is "null" for app:// in Node, so compare scheme and host explicitly.
    const u = new URL(url);
    return origins.includes(`${u.protocol}//${u.host}`);
  } catch {
    return false;
  }
}

function claudeKind(kind: unknown): ClaudeConnectKind {
  if (kind !== 'mcp' && kind !== 'plugin') throw new Error('invalid kind');
  return kind;
}

function isDir(p: string): boolean {
  try { return fs.statSync(p).isDirectory(); } catch { return false; }
}

export function metrics(): AppMetrics {
  const all = app.getAppMetrics();
  const processes = all.map(m => ({ type: m.type, mb: Math.round(m.memory.workingSetSize / 1024) }));
  return {
    totalMb: processes.reduce((a, p) => a + p.mb, 0),
    privateMb: Math.round(all.reduce((a, m) => a + (m.memory.privateBytes ?? 0), 0) / 1024),
    processes,
    version: app.getVersion(),
    electron: process.versions.electron,
  };
}
