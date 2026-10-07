#!/usr/bin/env node
// Starting-context audit of Claude Code agent runs (CL-31): what every run carries from its first turn,
// broken down by source and role, what each role actually calls, and the savings of removing a source.
// Read-only. Run set and cost weights match the Terrio workspace collector run/codemetrics.mjs.
//
//   node tools/context-audit.mjs --since 2026-09-23 --until 2026-10-07 [--workspace <dir>] [--out audit.json]
//
// Method: starting context S = input + cache read + cache write of the first assistant turn. Its cost =
// what turn 1 paid for those tokens + one cache read (0.1) per later turn. Sources come from the transcript
// (prompt_snapshot = agent body or harness prompt, instructions = CLAUDE.md + MEMORY.md, skill/agent/deferred
// listings, MCP instructions, first user message); MCP tool schemas = JSON of tools/list for the tools in the
// agent's frontmatter (measured live from the workspace .mcp.json). Chars -> tokens at CPT chars per token,
// calibrated by least squares of S on the measured chars over subagent runs. The remainder is the harness
// system prompt plus the built-in tool schemas.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import readline from 'node:readline';
import { spawn } from 'node:child_process';

const args = {}; for (let i = 2; i < process.argv.length; i++) if (process.argv[i].startsWith('--')) args[process.argv[i].slice(2)] = process.argv[i + 1]?.startsWith('--') ? true : process.argv[++i] ?? true;
const WS = args.workspace || 'C:/Users/tadea/Documents/Claude/terrio';
const T = args.transcripts || path.join(os.homedir(), '.claude', 'projects', WS.replace(/[:\\/]/g, '-'));
const W = { input: 1, cw5m: 1.25, cw1h: 2, cacheRead: 0.1, output: 5 };
const since = Date.parse(args.since || '2026-09-23'), until = args.until ? Date.parse(args.until) : Infinity;

async function mcpTools() {
  const cfg = JSON.parse(fs.readFileSync(path.join(WS, '.mcp.json'), 'utf8')).mcpServers; const schema = {};
  for (const [name, c] of Object.entries(cfg)) {
    const tools = await new Promise(res => {
      const p = spawn(c.command, c.args || [], { cwd: WS, env: { ...process.env, ...(c.env || {}) } }); let buf = '';
      const t = setTimeout(() => { p.kill(); res([]); }, 30000); const send = m => p.stdin.write(JSON.stringify(m) + '\n');
      p.stdout.on('data', d => { buf += d; let i; while ((i = buf.indexOf('\n')) >= 0) { let m; try { m = JSON.parse(buf.slice(0, i)); } catch { m = {}; } buf = buf.slice(i + 1);
        if (m.id === 1) { send({ jsonrpc: '2.0', method: 'notifications/initialized' }); send({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }); }
        if (m.id === 2) { clearTimeout(t); p.kill(); res(m.result?.tools || []); } } });
      send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'context-audit', version: '1' } } });
    });
    if (!tools.length) console.error(`! ${name}: no tools/list answer, its schemas count as 0`);
    for (const t of tools) schema[`mcp__${name}__${t.name}`] = JSON.stringify(t).length;
  }
  return schema;
}

function agentDefs() {
  const dir = path.join(WS, '.claude', 'agents'); const out = {};
  for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.md'))) { const t = fs.readFileSync(path.join(dir, f), 'utf8');
    out[f.replace(/\.md$/, '')] = { tools: (t.match(/^tools:\s*(.*)$/m)?.[1] || '').split(',').map(s => s.trim()).filter(Boolean), omitClaudeMd: /^omitClaudeMd:\s*true/m.test(t) }; }
  return out;
}

function listRuns() {
  const list = [];
  for (const e of fs.readdirSync(T, { withFileTypes: true })) {
    const p = path.join(T, e.name);
    if (e.isFile() && e.name.endsWith('.jsonl')) list.push({ file: p, kind: 'session', role: 'main' });
    const sub = path.join(p, 'subagents');
    if (e.isDirectory() && fs.existsSync(sub)) for (const f of fs.readdirSync(sub).filter(f => f.endsWith('.jsonl'))) {
      let meta = {}; try { meta = JSON.parse(fs.readFileSync(path.join(sub, f.replace(/\.jsonl$/, '.meta.json')), 'utf8')); } catch {}
      list.push({ file: path.join(sub, f), kind: 'subagent', role: meta.agentType || 'unknown' });
    }
  }
  return list;
}

const textLen = c => typeof c === 'string' ? c.length : Array.isArray(c) ? c.reduce((n, b) => n + (b.type === 'text' ? (b.text || '').length : 0), 0) : 0;

async function readRun(m) {
  const r = { role: m.role, kind: m.kind, start: null, turns: 0, S: 0, first: null, cost: 0, src: {}, tools: {} };
  const seen = new Set(); let before = true;
  const add = (k, n) => { r.src[k] = (r.src[k] || 0) + n; };
  const rl = readline.createInterface({ input: fs.createReadStream(m.file, 'utf8'), crlfDelay: Infinity });
  for await (const line of rl) {
    if (!line) continue; let o; try { o = JSON.parse(line); } catch { continue; }
    if (o.type === 'agent-setting' && o.agentSetting) { r.role = o.agentSetting; r.kind = 'phase'; }
    if (o.timestamp) r.start ??= o.timestamp;
    const a = o.attachment;
    if (a && before) {
      if (a.type === 'prompt_snapshot') { if (!r.src.body) add('body', a.systemPrompt.reduce((n, s) => n + s.length, 0)); }
      else add(a.type, JSON.stringify(a.content ?? a.addedLines ?? a.addedBlocks ?? a.addedNames ?? a.context ?? a.snapshot ?? a).length);
    }
    if (o.type === 'user' && before && o.message) add('prompt', textLen(o.message.content));
    if (o.type === 'assistant' && o.message) {
      const msg = o.message;
      if (msg.id && !seen.has(msg.id)) { seen.add(msg.id); r.turns++; const u = msg.usage || {};
        const cw1h = u.cache_creation?.ephemeral_1h_input_tokens || 0, cw5m = u.cache_creation?.ephemeral_5m_input_tokens ?? Math.max(0, (u.cache_creation_input_tokens || 0) - cw1h);
        const c = { input: u.input_tokens || 0, cw5m, cw1h, cacheRead: u.cache_read_input_tokens || 0, output: u.output_tokens || 0 };
        r.cost += Object.entries(W).reduce((n, [k, w]) => n + c[k] * w, 0);
        if (before) { r.S = c.input + c.cacheRead + c.cw5m + c.cw1h; r.first = c; before = false; } }
      for (const b of Array.isArray(msg.content) ? msg.content : []) if (b.type === 'tool_use') r.tools[b.name] = (r.tools[b.name] || 0) + 1;
    }
  }
  if (!r.turns || !r.S || !r.start || Date.parse(r.start) < since || Date.parse(r.start) >= until) return null;
  const f = r.first; r.startCost = f.input * W.input + f.cw5m * W.cw5m + f.cw1h * W.cw1h + f.cacheRead * W.cacheRead + r.S * W.cacheRead * (r.turns - 1);
  return r;
}

const median = a => { if (!a.length) return 0; const s = [...a].sort((x, y) => x - y); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
const MISC = ['environment', 'model', 'session_context', 'date', 'credential_org', 'total_tokens_reminder', 'auto_mode'];

const schema = await mcpTools(); const agents = agentDefs();
const runs = (await Promise.all(listRuns().map(readRun))).filter(Boolean);
const mcpOf = r => (agents[r.role]?.tools || []).filter(t => t.startsWith('mcp__'));
for (const r of runs) r.chars = { ...r.src, mcpSchemas: mcpOf(r).reduce((n, t) => n + (schema[t] || 0), 0) };

// chars per token: least squares of S on the measured chars, subagent runs of workspace agents
const pts = runs.filter(r => r.kind === 'subagent' && agents[r.role]).map(r => [(r.src.body || 0) + (r.src.instructions || 0) + (r.src.prompt || 0) + r.chars.mcpSchemas, r.S]);
const mx = pts.reduce((n, p) => n + p[0], 0) / pts.length, my = pts.reduce((n, p) => n + p[1], 0) / pts.length;
const slope = pts.reduce((n, [x, y]) => n + (x - mx) * (y - my), 0) / pts.reduce((n, [x]) => n + (x - mx) ** 2, 0);
const CPT = +(1 / slope).toFixed(2);

const SOURCES = ['body', 'claudeMd', 'prompt', 'mcpSchemas', 'skills', 'agentList', 'deferred', 'mcpInstr', 'hook', 'misc'];
for (const r of runs) {
  const c = r.chars, t = v => (v || 0) / CPT;
  r.tok = { body: t(c.body), claudeMd: t(c.instructions), prompt: t(c.prompt), mcpSchemas: t(c.mcpSchemas), skills: t(c.skill_listing), agentList: t(c.agent_listing_delta),
    deferred: t(c.deferred_tools_delta), mcpInstr: t(c.mcp_instructions_delta), hook: t(c.hook_additional_context), misc: t(MISC.reduce((n, k) => n + (c[k] || 0), 0)) };
  r.tok.rest = Math.max(0, r.S - SOURCES.reduce((n, k) => n + r.tok[k], 0));
  r.f = r.startCost / r.S; // cost of one starting-context token over the run
}
const total = runs.reduce((n, r) => n + r.cost, 0);
const pct = v => +(100 * v / total).toFixed(2);
const saving = fn => pct(runs.reduce((n, r) => n + fn(r) * r.f, 0)); // fn: tokens removed from run r

const roles = {};
for (const r of runs) (roles[r.role + (r.kind === 'phase' ? ' [phase]' : '')] ??= []).push(r);
const byRole = Object.entries(roles).map(([g, rs]) => {
  const start = rs.reduce((n, r) => n + r.startCost, 0), cost = rs.reduce((n, r) => n + r.cost, 0);
  return { role: g, runs: rs.length, turnsMedian: median(rs.map(r => r.turns)), S: Math.round(median(rs.map(r => r.S))),
    median: Object.fromEntries([...SOURCES, 'rest'].map(k => [k, Math.round(median(rs.map(r => r.tok[k])))])),
    costPct: pct(cost), startPct: pct(start), startOfRolePct: +(100 * start / cost).toFixed(1) };
}).filter(x => x.startPct >= 0.05).sort((a, b) => b.startPct - a.startPct);

const nRole = {}, use = {};
for (const r of runs) { nRole[r.role] = (nRole[r.role] || 0) + 1; for (const t of Object.keys(r.tools)) use[r.role + '|' + t] = (use[r.role + '|' + t] || 0) + 1; }
const rate = (role, t) => (use[role + '|' + t] || 0) / (nRole[role] || 1);
const toolUse = Object.fromEntries(Object.entries(agents).filter(([n]) => nRole[n]).map(([n, a]) => [n, a.tools.map(t => ({ tool: t, runsPct: +(100 * rate(n, t)).toFixed(1), schemaTok: Math.round((schema[t] || 0) / CPT) }))]));

const bySource = Object.fromEntries([...SOURCES, 'rest'].map(k => [k, saving(r => r.tok[k])]));
const tk = t => (schema[t] || 0) / CPT;
const scenarios = {
  dropMcpToolsUnder2pct: saving(r => mcpOf(r).filter(t => rate(r.role, t) < 0.02).reduce((n, t) => n + tk(t), 0)),
  retireGitnexusIdea: saving(r => mcpOf(r).filter(t => /^mcp__(gitnexus|idea)__/.test(t)).reduce((n, t) => n + tk(t), 0)),
  gitnexusImpactSchemaOnly: saving(r => mcpOf(r).includes('mcp__gitnexus__impact') ? tk('mcp__gitnexus__impact') : 0),
  capToolJson1500: saving(r => mcpOf(r).reduce((n, t) => n + Math.max(0, (schema[t] || 0) - 1500) / CPT, 0)),
  claudeMdOutOfSubagents: saving(r => r.role !== 'main' ? r.tok.claudeMd : 0),
  phaseModeIgnoresOmitClaudeMd: saving(r => r.kind === 'phase' && agents[r.role]?.omitClaudeMd ? r.tok.claudeMd : 0),
  claudeMdMainOnly: saving(r => r.role === 'main' ? r.tok.claudeMd : 0),
  agentBodiesOver8k: saving(r => r.role !== 'main' && agents[r.role] ? Math.max(0, (r.chars.body || 0) - 8000) / CPT : 0),
  mainSkillsListing: saving(r => r.tok.skills), mainMcpInstructions: saving(r => r.tok.mcpInstr), mainDeferredList: saving(r => r.tok.deferred), mainAgentList: saving(r => r.tok.agentList),
};
const result = { generated: new Date().toISOString(), since: args.since || '2026-09-23', until: args.until || null, runs: runs.length, totalWeighted: Math.round(total), CPT,
  calibration: { slopeTokPerChar: +slope.toFixed(3), interceptTok: Math.round(my - slope * mx), n: pts.length },
  startPct: pct(runs.reduce((n, r) => n + r.startCost, 0)), bySource, byRole, toolUse, scenarios,
  mcpSchemaChars: schema };
if (args.out) fs.writeFileSync(args.out, JSON.stringify(result, null, 1));
console.log(`runs ${runs.length}, ${(total / 1e9).toFixed(2)} bn weighted, starting context ${result.startPct} % of cost, ${CPT} chars/token`);
console.log('by source (% of total cost):', Object.entries(bySource).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k} ${v}`).join(', '));
for (const x of byRole) console.log(`${x.role.padEnd(30)} runs ${String(x.runs).padStart(4)}  S ${String(x.S).padStart(6)}  start ${x.startPct} % of total, ${x.startOfRolePct} % of role  ` + Object.entries(x.median).filter(([, v]) => v).map(([k, v]) => `${k} ${v}`).join(' '));
console.log('scenarios (% of total cost):', JSON.stringify(scenarios));
