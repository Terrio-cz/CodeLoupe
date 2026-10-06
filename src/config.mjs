// Where CodeLoupe keeps its state and how the daemon is reached. Override with CODELOUPE_HOME /
// CODELOUPE_PORT or <home>/config.json { "port": 47391 }.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

export const VERSION = createRequire(import.meta.url)('../package.json').version;
export const HEADER = 'x-codeloupe';

function defaultHome() {
  if (process.platform === 'win32') return path.join(process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'), 'codeloupe');
  if (process.platform === 'darwin') return path.join(os.homedir(), 'Library', 'Caches', 'codeloupe');
  return path.join(process.env.XDG_CACHE_HOME || path.join(os.homedir(), '.cache'), 'codeloupe');
}

export function loadConfig() {
  const home = process.env.CODELOUPE_HOME || defaultHome();
  let file = {};
  try { file = JSON.parse(fs.readFileSync(path.join(home, 'config.json'), 'utf8')); } catch {}
  return {
    home,
    port: Number(process.env.CODELOUPE_PORT || file.port || 47391),
    queryTimeoutMs: Number(file.queryTimeoutMs || 10_000),
    buildTimeoutMs: Number(file.buildTimeoutMs || 10 * 60_000),
    buildHeapMb: Number(file.buildHeapMb || 512),
    defaultRoot: process.env.CODELOUPE_ROOT || file.defaultRoot || null,
  };
}
