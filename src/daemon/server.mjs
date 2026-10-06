// The single CodeLoupe daemon: one process for every client on the machine. Serves MCP (stateless
// Streamable HTTP) on /mcp, the same tools as JSON on /api/<tool> for the CLI, and /status.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { loadConfig, VERSION, HEADER } from '../config.mjs';
import { Queue } from './queue.mjs';
import { Registry, BusyError } from '../repo/registry.mjs';
import { TOOLS } from '../tools.mjs';

const MAX_LOG = 10 * 1024 * 1024;

export async function startDaemon(overrides = {}) {
  const cfg = { ...loadConfig(), ...overrides };
  fs.mkdirSync(cfg.home, { recursive: true });
  const logFile = path.join(cfg.home, 'daemon.log');
  const callsFile = path.join(cfg.home, 'calls.jsonl');
  const append = (file, line) => {
    try { if (fs.statSync(file).size > MAX_LOG) fs.renameSync(file, file + '.1'); } catch {}
    fs.appendFileSync(file, line + '\n');
  };
  const log = msg => append(logFile, `${new Date().toISOString()} ${msg}`);
  const queue = new Queue();
  const registry = new Registry(cfg, queue, log);
  const started = Date.now();
  const calls = { total: 0, errors: 0, busy: 0 };

  async function runTool(tool, args, via) {
    const t0 = Date.now(); let text, ok = true, busy = false;
    try {
      const rootArg = args.root || cfg.defaultRoot;
      if (!rootArg) throw new Error('pass root: the absolute path of the repository or worktree to answer for');
      const { view, note } = await registry.view(rootArg);
      try { text = tool.run(view, args); } finally { view.close(); }
      if (note) text = `${note}\n${text}`;
    } catch (e) {
      ok = false; busy = e instanceof BusyError;
      text = busy ? `busy: ${e.message}` : `error: ${e.message}`;
    }
    calls.total++; if (!ok) calls.errors++; if (busy) calls.busy++;
    append(callsFile, JSON.stringify({ t: new Date(t0).toISOString(), tool: tool.name, via, ms: Date.now() - t0, chars: text.length, ok, busy,
      empty: /^no (declaration|type|indexed file)/.test(text) }));
    return { ok, text };
  }

  function mcpServer() {
    const s = new McpServer({ name: 'codeloupe', version: VERSION });
    for (const tool of TOOLS) {
      s.registerTool(tool.name, { description: tool.description, inputSchema: tool.shape }, async args => {
        const r = await runTool(tool, args, 'mcp');
        return { content: [{ type: 'text', text: r.text }], isError: !r.ok };
      });
    }
    return s;
  }

  const status = () => {
    const m = process.memoryUsage(); const cpu = process.cpuUsage();
    return { name: 'codeloupe', version: VERSION, pid: process.pid, port: cfg.port, home: cfg.home, uptimeSec: Math.round((Date.now() - started) / 1000),
      rssMb: Math.round(m.rss / 1048576), heapMb: Math.round(m.heapUsed / 1048576), cpuSec: Math.round((cpu.user + cpu.system) / 1e6),
      calls, queue: queue.snapshot(), repos: registry.snapshot() };
  };

  const readBody = req => new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', c => { size += c.length; if (size > 4 * 1024 * 1024) { reject(new Error('body too large')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => { try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : undefined); } catch (e) { reject(e); } });
    req.on('error', reject);
  });
  const send = (res, code, body) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(body)); };

  const server = http.createServer(async (req, res) => {
    // No keep-alive: a pooled socket would outlive a daemon restart and fail the client's next call.
    res.setHeader('connection', 'close');
    try {
      // Local only, never from a browser page: exact Host, no Origin, and a custom header on writes of any kind.
      const host = req.headers.host || '';
      if (host !== `127.0.0.1:${cfg.port}` && host !== `localhost:${cfg.port}`) return send(res, 403, { error: 'bad host' });
      if (req.headers.origin) return send(res, 403, { error: 'browser requests are not accepted' });
      const url = new URL(req.url, `http://${host}`);
      if (req.method === 'GET' && url.pathname === '/status') return send(res, 200, status());
      if (req.headers[HEADER] === undefined) return send(res, 403, { error: `missing ${HEADER} header` });
      if (url.pathname === '/mcp') {
        if (req.method !== 'POST') return send(res, 405, { jsonrpc: '2.0', error: { code: -32000, message: 'stateless server: POST only' }, id: null });
        const body = await readBody(req);
        const s = mcpServer();
        const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
        res.on('close', () => { transport.close(); s.close(); });
        await s.connect(transport);
        await transport.handleRequest(req, res, body);
        return;
      }
      if (req.method === 'POST' && url.pathname.startsWith('/api/')) {
        const tool = TOOLS.find(t => t.name === url.pathname.slice(5));
        if (!tool) return send(res, 404, { error: 'unknown tool' });
        return send(res, 200, await runTool(tool, (await readBody(req)) || {}, 'api'));
      }
      if (req.method === 'POST' && url.pathname === '/shutdown') { send(res, 200, { ok: true }); log('shutdown requested'); setImmediate(() => stop().then(() => { if (cfg.exitOnShutdown) process.exit(0); })); return; }
      send(res, 404, { error: 'not found' });
    } catch (e) {
      log(`request ${req.method} ${req.url} failed: ${e.stack || e.message}`);
      if (!res.headersSent) send(res, 500, { error: e.message });
    }
  });

  const infoFile = path.join(cfg.home, 'daemon.json');
  const stop = () => new Promise(resolve => {
    server.close(() => resolve());
    server.closeAllConnections?.();
    try { if (JSON.parse(fs.readFileSync(infoFile, 'utf8')).pid === process.pid) fs.rmSync(infoFile, { force: true }); } catch {}
  });

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(cfg.port, '127.0.0.1', () => { server.off('error', reject); resolve(); });
  });
  fs.writeFileSync(infoFile, JSON.stringify({ pid: process.pid, port: cfg.port, version: VERSION, startedAt: new Date(started).toISOString() }));
  log(`daemon ${VERSION} pid ${process.pid} listening on 127.0.0.1:${cfg.port}`);
  return { cfg, server, stop, status, registry };
}
