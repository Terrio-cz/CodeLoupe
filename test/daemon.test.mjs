import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { fixtureRepo, tmpDir } from './helpers.mjs';
import { startDaemon } from '../src/daemon/server.mjs';
import { HEADER } from '../src/config.mjs';

const port = 49152 + Math.floor(Math.random() * 10000);
const home = tmpDir('home');
const repo = fixtureRepo('kotlin/sample');
let d = await startDaemon({ home, port });
after(() => d.stop());

const api = (tool, args, headers = { [HEADER]: '1' }) => fetch(`http://127.0.0.1:${port}/api/${tool}`, {
  method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(args) });

test('concurrent first queries share one build', async () => {
  const [a, b] = await Promise.all([api('find', { root: repo, q: 'OrderService' }), api('outline', { root: repo, target: 'Registry' })]);
  const [ra, rb] = [await a.json(), await b.json()];
  assert.ok(ra.ok && rb.ok, ra.text + rb.text);
  assert.match(ra.text, /class OrderService/);
  const s = d.status();
  assert.equal(s.queue.done, 1, 'one build');
  assert.equal(s.repos.length, 1);
});

test('worktrees of one repository share its index; unknown roots are errors', async () => {
  const sub = await (await api('find', { root: repo + '/src/main', q: 'Registry' })).json();
  assert.ok(sub.ok); assert.equal(d.status().repos.length, 1);
  const bad = await (await api('find', { root: tmpDir('nogit'), q: 'x' })).json();
  assert.equal(bad.ok, false); assert.match(bad.text, /not inside a git repository/);
});

test('rejects browser-shaped and header-less requests', async () => {
  assert.equal((await api('find', { root: repo, q: 'x' }, { [HEADER]: '1', origin: 'https://evil.example' })).status, 403);
  assert.equal((await api('find', { root: repo, q: 'x' }, {})).status, 403);
  // DNS rebinding: a page on evil.example resolving to 127.0.0.1 sends its own Host.
  const status = await new Promise((resolve, reject) => http.get({ host: '127.0.0.1', port, path: '/status', headers: { host: 'evil.example' } },
    res => { res.resume(); resolve(res.statusCode); }).on('error', reject));
  assert.equal(status, 403);
});

test('a second daemon on the same port refuses to start', async () => {
  await assert.rejects(startDaemon({ home, port }), e => e.code === 'EADDRINUSE');
});

test('MCP: tools listed and callable, client survives a daemon restart', async () => {
  const c = new Client({ name: 'test', version: '0' });
  await c.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`), { requestInit: { headers: { [HEADER]: '1' } } }));
  assert.deepEqual((await c.listTools()).tools.map(t => t.name).sort(), ['find', 'outline', 'symbol']);
  const r1 = await c.callTool({ name: 'symbol', arguments: { root: repo, name: 'total' } });
  assert.match(r1.content[0].text, /fun total/);
  await d.stop();
  d = await startDaemon({ home, port });
  const r2 = await c.callTool({ name: 'symbol', arguments: { root: repo, name: 'Registry.register' } });
  assert.match(r2.content[0].text, /fun register/);
  assert.equal(d.status().queue.done, 0, 'restart reused the saved base index');
  await c.close();
});
