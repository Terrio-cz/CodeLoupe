import { nativeTheme, type BrowserWindow } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { metrics } from './ipcHandlers';

const ROUTES: [string, string][] = [
  ['overview', '#/overview'],
  ['branches', '#/branches'],
  ['branch-detail', '#/branches/a1f3c09e4b21'],
  ['tasks', '#/tasks'],
  ['task-detail', '#/tasks/TER-671'],
  ['index', '#/index'],
  ['gaps', '#/gaps'],
  ['environment', '#/environment'],
  ['settings', '#/settings'],
];

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));
const go = (win: BrowserWindow, hash: string) => win.webContents.executeJavaScript(`location.hash = ${JSON.stringify(hash)}`);

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
  const report: Record<string, unknown> = {};
  for (const theme of ['light', 'dark'] as const) {
    nativeTheme.themeSource = theme;
    for (const [name, route] of ROUTES) {
      await go(win, route);
      await sleep(1200);
      const img = await win.webContents.capturePage();
      fs.writeFileSync(path.join(dir, `${theme}-${name}.png`), img.toPNG());
    }
    report[theme] = metrics();
  }
  await sleep(2000);
  report.settled = metrics();
  fs.writeFileSync(path.join(dir, 'metrics.json'), JSON.stringify(report, null, 2));
}
