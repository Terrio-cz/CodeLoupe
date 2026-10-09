import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { mcpTools } from './context-audit-mcp.mjs';

const fakeServer = `
let buf = '';
process.stdin.on('data', d => { buf += d; let i;
  while ((i = buf.indexOf('\\n')) >= 0) { const m = JSON.parse(buf.slice(0, i)); buf = buf.slice(i + 1);
    if (m.id === 1) console.log(JSON.stringify({ jsonrpc: '2.0', id: 1, result: {} }));
    if (m.id === 2) console.log(JSON.stringify({ jsonrpc: '2.0', id: 2, result: { tools: [{ name: 'ping', description: 'x' }] } })); } });`;

function workspace(servers) {
  const ws = fs.mkdtempSync(path.join(os.tmpdir(), 'ctx-audit-'));
  fs.writeFileSync(path.join(ws, 'fake.mjs'), fakeServer);
  fs.writeFileSync(path.join(ws, '.mcp.json'), JSON.stringify({ mcpServers: servers }));
  return ws;
}

test('an http entry without a command is skipped and the stdio server is still measured', async () => {
  const ws = workspace({ remote: { type: 'http', url: 'http://127.0.0.1:1/mcp' }, local: { command: process.execPath, args: ['fake.mjs'] } });
  const schema = await mcpTools(ws, { timeoutMs: 5000 });
  assert.deepEqual(Object.keys(schema), ['mcp__local__ping']);
  assert.equal(schema.mcp__local__ping, JSON.stringify({ name: 'ping', description: 'x' }).length);
});

test('a stdio server that cannot start counts as 0 instead of crashing', async () => {
  const ws = workspace({ missing: { command: 'codeloupe-no-such-binary' } });
  assert.deepEqual(await mcpTools(ws, { timeoutMs: 5000 }), {});
});
