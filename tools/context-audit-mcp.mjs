// MCP tool-schema measurement for tools/context-audit.mjs: JSON size of every tool a stdio server in the
// workspace .mcp.json lists. Entries without a command (http servers) cannot be spawned and count as 0.
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

function listTools(ws, c, timeoutMs) {
  return new Promise(res => {
    const p = spawn(c.command, c.args || [], { cwd: ws, env: { ...process.env, ...(c.env || {}) } }); let buf = '';
    const done = tools => { clearTimeout(t); p.kill(); res(tools); };
    const t = setTimeout(() => done([]), timeoutMs); const send = m => p.stdin.write(JSON.stringify(m) + '\n');
    p.on('error', () => done([]));
    p.stdout.on('data', d => { buf += d; let i; while ((i = buf.indexOf('\n')) >= 0) { let m; try { m = JSON.parse(buf.slice(0, i)); } catch { m = {}; } buf = buf.slice(i + 1);
      if (m.id === 1) { send({ jsonrpc: '2.0', method: 'notifications/initialized' }); send({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }); }
      if (m.id === 2) done(m.result?.tools || []); } });
    send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'context-audit', version: '1' } } });
  });
}

export async function mcpTools(ws, { timeoutMs = 30000 } = {}) {
  const cfg = JSON.parse(fs.readFileSync(path.join(ws, '.mcp.json'), 'utf8')).mcpServers; const schema = {};
  for (const [name, c] of Object.entries(cfg)) {
    if (!c.command) { console.error(`! ${name}: no command (${c.type || 'remote'} server), its schemas count as 0`); continue; }
    const tools = await listTools(ws, c, timeoutMs);
    if (!tools.length) console.error(`! ${name}: no tools/list answer, its schemas count as 0`);
    for (const t of tools) schema[`mcp__${name}__${t.name}`] = JSON.stringify(t).length;
  }
  return schema;
}
