#!/usr/bin/env node
// codeloupe — on-demand code index for AI coding agents.
import { loadConfig } from '../src/config.mjs';
import { startDaemon } from '../src/daemon/server.mjs';
import { status, ensureDaemon, call, shutdown } from '../src/daemon/client.mjs';

const USAGE = `codeloupe <command>
  daemon                      run the daemon in the foreground (normally started on demand)
  start | stop | status       manage the background daemon
  find <q> [--kind k] [--module m] [--test true|false] [--root path]
  outline <file|Type> [--root path]
  symbol <name> [--full] [--all] [--root path]
  mcp-config                  print the .mcp.json entry for Claude Code
Root defaults to the current directory.`;

function parse(argv) {
  const a = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const x = argv[i];
    if (!x.startsWith('--')) { a._.push(x); continue; }
    const k = x.slice(2); const v = argv[i + 1];
    if (v === undefined || v.startsWith('--')) a[k] = true; else { a[k] = v === 'true' ? true : v === 'false' ? false : v; i++; }
  }
  return a;
}

const args = parse(process.argv.slice(2));
const cmd = args._.shift();
const cfg = loadConfig();

async function main() {
  switch (cmd) {
    case 'daemon': {
      try { await startDaemon({ exitOnShutdown: true }); }
      catch (e) {
        if (e.code === 'EADDRINUSE' && (await status(cfg))) { console.log(`already running on 127.0.0.1:${cfg.port}`); return; }
        throw e;
      }
      return;
    }
    case 'start': { const s = await ensureDaemon(cfg); console.log(`running pid ${s.pid} on 127.0.0.1:${s.port}`); return; }
    case 'stop': console.log((await shutdown(cfg)) ? 'stopped' : 'not running'); return;
    case 'status': { const s = await status(cfg); console.log(s ? JSON.stringify(s, null, 1) : 'not running'); process.exitCode = s ? 0 : 3; return; }
    case 'mcp-config':
      console.log(JSON.stringify({ codeloupe: { type: 'http', url: `http://127.0.0.1:${cfg.port}/mcp`, headers: { 'x-codeloupe': '1' } } }, null, 2));
      return;
    case 'find': case 'outline': case 'symbol': {
      const key = { find: 'q', outline: 'target', symbol: 'name' }[cmd];
      const { _, ...rest } = args;
      if (rest.limit) rest.limit = Number(rest.limit);
      const r = await call(cmd, { root: process.cwd(), ...rest, [key]: _[0] }, cfg);
      console.log(r.text ?? r.error);
      process.exitCode = r.ok ? 0 : 1;
      return;
    }
    default: console.log(USAGE); process.exitCode = cmd ? 2 : 0;
  }
}

main().catch(e => { console.error(e.message); process.exitCode = 1; });
