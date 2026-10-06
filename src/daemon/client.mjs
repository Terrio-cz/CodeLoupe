// CLI side: reach the daemon, starting it when it is not running.
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { loadConfig, HEADER } from '../config.mjs';

const BIN = fileURLToPath(new URL('../../bin/codeloupe.mjs', import.meta.url));

export async function status(cfg = loadConfig(), timeoutMs = 800) {
  try {
    const r = await fetch(`http://127.0.0.1:${cfg.port}/status`, { signal: AbortSignal.timeout(timeoutMs) });
    const s = await r.json();
    return s.name === 'codeloupe' ? s : null;
  } catch { return null; }
}

export async function ensureDaemon(cfg = loadConfig()) {
  const s = await status(cfg);
  if (s) return s;
  const p = spawn(process.execPath, [BIN, 'daemon'], { detached: true, stdio: 'ignore', windowsHide: true, cwd: path.dirname(BIN) });
  p.unref();
  for (let i = 0; i < 80; i++) {
    await new Promise(r => setTimeout(r, 100));
    const up = await status(cfg, 300);
    if (up) return up;
  }
  throw new Error(`daemon did not start on 127.0.0.1:${cfg.port}; see ${path.join(cfg.home, 'daemon.log')}`);
}

export async function call(tool, args, cfg = loadConfig()) {
  await ensureDaemon(cfg);
  const r = await fetch(`http://127.0.0.1:${cfg.port}/api/${tool}`, {
    method: 'POST', headers: { 'content-type': 'application/json', [HEADER]: '1' }, body: JSON.stringify(args),
  });
  return r.json();
}

export async function shutdown(cfg = loadConfig()) {
  if (!(await status(cfg))) return false;
  await fetch(`http://127.0.0.1:${cfg.port}/shutdown`, { method: 'POST', headers: { [HEADER]: '1' } }).catch(() => {});
  for (let i = 0; i < 50 && (await status(cfg, 200)); i++) await new Promise(r => setTimeout(r, 100));
  return true;
}
