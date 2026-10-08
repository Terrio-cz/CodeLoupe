import { app, BrowserWindow, Menu, nativeTheme, session } from 'electron';
import path from 'node:path';
import { CH } from '../shared/ipc';
import type { Events } from '../shared/contract';
import type { AppSettings } from '../shared/settings';
import { validateRequest } from '../shared/request';
import type { ApiSource } from './api/ApiSource';
import { DaemonApi } from './api/DaemonApi';
import { MockApi } from './api/MockApi';
import { APP_ORIGIN, handleAppScheme, registerAppScheme } from './appProtocol';
import { ClaudeConnector, execClaude, findMarketplace } from './claude/ClaudeConnector';
import { DaemonClient } from './daemon/DaemonClient';
import { DaemonHome } from './daemon/DaemonHome';
import { DaemonManager } from './daemon/DaemonManager';
import { glyphImage } from './icons';
import { registerIpc } from './ipcHandlers';
import { Notifier } from './notifier';
import { captureScreens, tour } from './screenshots';
import { SettingsStore } from './settingsStore';
import { AppTray } from './tray';

const DEV_URL = !app.isPackaged ? process.env.ELECTRON_RENDERER_URL : undefined;
// Verification modes write files and switch themes: development builds only.
const SCREENSHOTS = !app.isPackaged ? process.env.CODELOUPE_APP_SCREENSHOTS : undefined;
const TOUR = !app.isPackaged ? process.env.CODELOUPE_APP_TOUR : undefined;
const EVENTS_MS = 15_000;

// RAM budget ≤ 300 MB (docs/ui-spec.md § 11): tables and SVG need no GPU, and the GPU and network
// services run inside the main process instead of two extra processes (~120 MB working set).
app.disableHardwareAcceleration();
app.commandLine.appendSwitch('in-process-gpu');
app.commandLine.appendSwitch('enable-features', 'NetworkServiceInProcess,NetworkServiceInProcess2');
registerAppScheme();

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  void main();
}

async function main(): Promise<void> {
  // Windows shows toasts only for an AppUserModelId that a Start-menu shortcut carries: the installer (CL-45)
  // creates one for the packaged app; a development run uses the Electron binary's own id.
  app.setAppUserModelId(app.isPackaged ? 'cz.terrio.codeloupe' : process.execPath);
  await app.whenReady();

  const store = new SettingsStore(app.getPath('userData'));
  const home = new DaemonHome();
  let win: BrowserWindow | null = null;
  let quitting = false;

  const client = new DaemonClient(() => manager.port());
  const manager: DaemonManager = new DaemonManager(client, home, () => store.get(), () => (win && win.isVisible() ? 5_000 : 15_000));
  const daemonApi = new DaemonApi(client);
  let mock: MockApi | null = null;
  const source = (): ApiSource => (store.get().apiSource === 'mock' ? (mock ??= new MockApi()) : daemonApi);

  const showScreen = (hash: string) => { openWindow(hash); };
  const notifier = new Notifier(async (since, limit) => {
    const req = validateRequest({ resource: 'events', query: since === null ? { limit } : { since, limit } });
    if (!req.ok) throw new Error(req.error);
    return (await source().get(req.request, req.path)) as Events;
  }, () => store.get(), showScreen);

  setMenu();
  hardenSessions();
  handleAppScheme(path.join(__dirname, '../renderer'));
  nativeTheme.themeSource = store.get().theme;

  const claude = new ClaudeConnector(execClaude(), () => findMarketplace({ resources: app.isPackaged ? process.resourcesPath : null, appDir: __dirname }));

  registerIpc({
    store, manager, home, claude, source,
    trustedOrigins: [APP_ORIGIN, ...(DEV_URL ? [new URL(DEV_URL).origin] : [])],
    applySettings: (prev, next) => applySettings(prev, next),
  });

  const tray = new AppTray({
    open: () => openWindow(),
    start: () => void manager.start(),
    stop: () => void manager.stop(),
    restart: () => void manager.restart(),
    toggleLogin: () => {
      const prev = store.get();
      const next = store.save({ ...prev, openAtLogin: !prev.openAtLogin });
      applySettings(prev, next);
    },
    quit: () => { quitting = true; app.quit(); },
    openAtLogin: () => store.get().openAtLogin,
  });

  manager.on('state', s => {
    tray.update(s);
    if (win && !win.isDestroyed()) win.webContents.send(CH.daemonPush, s);
  });
  manager.on('phase', phase => notifier.daemonPhase(phase));
  manager.on('failed', message => notifier.startFailed(message));
  manager.on('gaveUp', message => notifier.gaveUp(message));
  manager.run();

  const pollEvents = async () => {
    if (source().kind === 'mock' || manager.trusted) await notifier.poll().catch(() => undefined);
    if (!quitting) setTimeout(pollEvents, EVENTS_MS);
  };
  void pollEvents();

  app.on('second-instance', () => openWindow());
  app.on('activate', () => openWindow());
  // Closing the window destroys it to free the renderer; the app lives on in the tray.
  app.on('window-all-closed', () => { /* keep running in the tray */ });
  app.on('before-quit', () => { quitting = true; manager.dispose(); tray.destroy(); });

  const w = openWindow();
  if (TOUR) {
    // `close` ends in the tray with the window destroyed: the tray-only memory case.
    const thenClose = TOUR === 'close';
    w.webContents.once('did-finish-load', () => setTimeout(() => void tour(w).then(() => { if (thenClose) w.close(); }), 1500));
  }
  if (SCREENSHOTS) {
    w.webContents.once('did-finish-load', () => {
      setTimeout(() => void captureScreens(w, SCREENSHOTS).catch(e => console.error('[codeloupe] screenshots failed', e)).finally(() => { console.log('[codeloupe] screenshots done, quitting'); quitting = true; app.quit(); }), 1500);
    });
  }

  /** Shows the window, creating it when only the tray is left; `hash` selects the screen (notification click). */
  function openWindow(hash?: string): BrowserWindow {
    if (win && !win.isDestroyed()) {
      if (win.isMinimized()) win.restore();
      win.show();
      win.focus();
      if (hash) win.webContents.send(CH.navigate, hash);
      return win;
    }
    win = new BrowserWindow({
      width: SCREENSHOTS ? 1440 : 1360,
      height: SCREENSHOTS ? 900 : 860,
      minWidth: 960,
      minHeight: 600,
      show: false,
      title: 'CodeLoupe',
      icon: glyphImage('app', 64, 1),
      backgroundColor: nativeTheme.shouldUseDarkColors ? '#0d0d0d' : '#f9f9f7',
      autoHideMenuBar: true,
      webPreferences: {
        preload: path.join(__dirname, '../preload/index.js'),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        webSecurity: true,
        spellcheck: false,
        backgroundThrottling: true,
      },
    });
    win.on('ready-to-show', () => win?.show());
    win.on('show', () => void manager.check());
    win.on('closed', () => { win = null; });
    // A new window starts on the requested screen; a message sent before the page loads would be lost.
    const route = hash && /^#\/[\w/?=&.:-]*$/.test(hash) ? hash : '';
    if (DEV_URL) void win.loadURL(`${DEV_URL}${route}`);
    else void win.loadURL(`${APP_ORIGIN}/index.html${route}`);
    return win;
  }

  function applySettings(prev: AppSettings, next: AppSettings): void {
    if (prev.theme !== next.theme) nativeTheme.themeSource = next.theme;
    if (prev.openAtLogin !== next.openAtLogin) app.setLoginItemSettings({ openAtLogin: next.openAtLogin });
    if (prev.apiSource !== next.apiSource) notifier.reset();
    if (prev.portOverride !== next.portOverride || prev.apiSource !== next.apiSource) void manager.check();
    tray.update(manager.current);
  }
}

/** No default menu: no reload or devtools accelerators. macOS keeps the edit roles for copy and paste. */
function setMenu(): void {
  if (process.platform === 'darwin') {
    Menu.setApplicationMenu(Menu.buildFromTemplate([{ role: 'appMenu' }, { role: 'editMenu' }]));
  } else {
    Menu.setApplicationMenu(null);
  }
}

function hardenSessions(): void {
  session.defaultSession.setPermissionRequestHandler((_wc, _permission, callback) => callback(false));
  session.defaultSession.setPermissionCheckHandler(() => false);
  app.on('web-contents-created', (_e, contents) => {
    contents.on('will-navigate', (event, url) => {
      if (!url.startsWith(`${APP_ORIGIN}/`) && !(DEV_URL && url.startsWith(DEV_URL))) event.preventDefault();
    });
    contents.on('will-attach-webview', event => event.preventDefault());
    contents.setWindowOpenHandler(() => ({ action: 'deny' }));
  });
}
