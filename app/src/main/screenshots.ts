import { nativeTheme, type BrowserWindow } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { metrics } from './ipcHandlers';

const MOCK_ROUTES: [string, string][] = [
  ['overview', '#/overview'],
  ['branches', '#/branches'],
  ['branch-detail', '#/branches/a1f3c09e4b21'],
  ['workspaces', '#/workspaces'],
  ['workspace-detail', '#/workspaces/TerrioImporter%2FTER-591'],
  ['tasks', '#/tasks'],
  ['task-detail', '#/tasks/TER-671'],
  ['jobs', '#/jobs'],
  ['job-detail', '#/jobs/J20261008-A1B2'],
  ['runs', '#/runs'],
  ['run-detail', '#/runs/1004'],
  ['index', '#/index'],
  ['gaps', '#/gaps'],
  ['environment', '#/environment'],
  ['settings', '#/settings'],
];

// CODELOUPE_APP_SCREENSHOT_ROUTES='[["name","#/hash"], …]' replaces the list, e.g. with ids of a real daemon.
const ROUTES: [string, string][] = (() => {
  try {
    const custom = JSON.parse(process.env.CODELOUPE_APP_SCREENSHOT_ROUTES ?? 'null') as unknown;
    if (Array.isArray(custom) && custom.every(r => Array.isArray(r) && typeof r[0] === 'string' && /^#\/[\w/?=&.:-]*$/.test(String(r[1])))) return custom as [string, string][];
  } catch { /* not set or not JSON: use the mock routes */ }
  return MOCK_ROUTES;
})();

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));
const go = (win: BrowserWindow, hash: string) => win.webContents.executeJavaScript(`location.hash = ${JSON.stringify(hash)}`);
/**
 * Opens a screen and resolves with the milliseconds until its data was shown: from the route change to the first moment
 * no skeleton (`aria-busy`) is left, or -1 after 20 s. Two frames pass first so the loading state has appeared.
 */
const visit = (win: BrowserWindow, hash: string): Promise<number> =>
  win.webContents.executeJavaScript(`new Promise(resolve => {
    const t0 = performance.now();
    location.hash = ${JSON.stringify(hash)};
    const poll = () => {
      if (!document.querySelector('[aria-busy="true"]')) resolve(Math.round(performance.now() - t0));
      else if (performance.now() - t0 > 20000) resolve(-1);
      else setTimeout(poll, 10);
    };
    requestAnimationFrame(() => requestAnimationFrame(poll));
  })`);

// Resolves after the page painted twice, so a capture never returns the previous frame.
const painted = (win: BrowserWindow) =>
  win.webContents.executeJavaScript('new Promise(r => requestAnimationFrame(() => requestAnimationFrame(() => r(true))))');

/**
 * RAM check (CODELOUPE_APP_TOUR=1): visits every screen in both themes like the screenshot run, without
 * capturing, and stays on the branch detail drawer so the process memory can be measured from the OS.
 */
export async function tour(win: BrowserWindow): Promise<void> {
  for (const theme of ['light', 'dark'] as const) {
    nativeTheme.themeSource = theme;
    for (const [, route] of ROUTES) {
      await go(win, route);
      await sleep(800);
    }
  }
  nativeTheme.themeSource = 'system';
  await go(win, '#/branches/a1f3c09e4b21');
}

/**
 * Verification run (CODELOUPE_APP_SCREENSHOTS=<dir>): captures every screen in light and dark mode,
 * writes the app's memory after each pass to metrics.json, then resolves.
 */
export async function captureScreens(win: BrowserWindow, dir: string): Promise<void> {
  fs.mkdirSync(dir, { recursive: true });
  win.webContents.setBackgroundThrottling(false);
  const report: Record<string, unknown> = {};
  const loadMs: Record<string, number> = {};
  for (const theme of ['light', 'dark'] as const) {
    nativeTheme.themeSource = theme;
    for (const [name, route] of ROUTES) {
      loadMs[`${theme}-${name}`] = await visit(win, route);
      await sleep(900);
      win.webContents.invalidate();
      await painted(win);
      const img = await win.webContents.capturePage();
      fs.writeFileSync(path.join(dir, `${theme}-${name}.png`), img.toPNG());
    }
    report[theme] = metrics();
  }
  await sleep(2000);
  report.settled = metrics();
  report.loadMs = loadMs;
  fs.writeFileSync(path.join(dir, 'metrics.json'), JSON.stringify(report, null, 2));
}
