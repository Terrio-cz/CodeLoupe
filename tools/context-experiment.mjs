#!/usr/bin/env node
// Counts of controlled `claude -p` runs (CL-75/76/177): per run the starting context S (input + cache read + cache write of the
// first assistant turn), the characters of every source that reaches the model before that turn, the turns and the tool calls.
// Counts only; it never prints prompts or results. Read-only.
//
//   node tools/context-experiment.mjs --runs <dir of <label>-<variant>-<rep>.json results of `claude -p --output-format json`>
//        [--projects <claude projects dir>] [--cpt 3.16] [--out table.json]
//
// A result file is named <label>-<variant>-<rep>.json (label may contain dashes: the last two dash-separated parts are variant and rep).
// The transcript of a run is found by its session_id under <projects>/*/<session_id>.jsonl; a label starting with sub- measures the first
// subagent that main session spawned (<session_id>/subagents/agent-*.jsonl) instead.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const args = {}; for (let i = 2; i < process.argv.length; i++) if (process.argv[i].startsWith('--')) args[process.argv[i].slice(2)] = process.argv[++i];
const RUNS = args.runs;
const PROJECTS = args.projects || path.join(os.homedir(), '.claude', 'projects');
const CPT = Number(args.cpt || 3.16);
if (!RUNS) { console.error('usage: context-experiment.mjs --runs <dir> [--projects <dir>] [--cpt 3.16] [--out file.json]'); process.exit(2); }

const index = new Map();
for (const d of fs.readdirSync(PROJECTS, { withFileTypes: true })) if (d.isDirectory()) {
  for (const f of fs.readdirSync(path.join(PROJECTS, d.name))) if (f.endsWith('.jsonl')) index.set(f.slice(0, -6), path.join(PROJECTS, d.name, f));
}

const textLen = c => typeof c === 'string' ? c.length : Array.isArray(c) ? c.reduce((n, b) => n + (b.type === 'text' ? b.text.length : 0), 0) : 0;
const median = a => { if (!a.length) return 0; const s = [...a].sort((x, y) => x - y); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };

function readTranscript(file) {
  const r = { src: {}, turns: 0, S: 0, calls: {}, bashGraph: 0, bashTotal: 0 };
  const seen = new Set(); let before = true;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    if (!line) continue; let o; try { o = JSON.parse(line); } catch { continue; }
    const a = o.attachment;
    if (a && before) {
      const add = (k, n) => { r.src[k] = (r.src[k] || 0) + n; };
      if (a.type === 'prompt_snapshot') add('body', (a.systemPrompt || []).reduce((n, s) => n + s.length, 0));
      else add(a.type, JSON.stringify(a.content ?? a.files ?? a.addedLines ?? a.addedBlocks ?? a.addedNames ?? a.context ?? a.snapshot ?? a).length);
      if (a.type === 'instructions') for (const f of a.files || []) add('instructions:' + f.type, (f.content || '').length);
    }
    if (o.type === 'user' && before && o.message) r.src.prompt = (r.src.prompt || 0) + textLen(o.message.content);
    if (o.type === 'assistant' && o.message) {
      const m = o.message;
      if (m.id && !seen.has(m.id)) {
        seen.add(m.id); r.turns++;
        if (before) { const u = m.usage || {}; r.S = (u.input_tokens || 0) + (u.cache_read_input_tokens || 0) + (u.cache_creation_input_tokens || 0); before = false; }
      }
      for (const b of Array.isArray(m.content) ? m.content : []) if (b.type === 'tool_use') {
        const name = b.name.startsWith('mcp__') ? b.name.split('__').slice(0, 2).join('__') : b.name;
        r.calls[name] = (r.calls[name] || 0) + 1;
        if (b.name === 'Bash') { r.bashTotal++; if (/gitnexus/.test(b.input?.command || '')) r.bashGraph++; }
      }
    }
  }
  return r;
}

const rows = [];
for (const f of fs.readdirSync(RUNS).filter(f => /\.json$/.test(f) && f !== 'table.json')) {
  const m = f.slice(0, -5).match(/^(.*)-([^-]+)-([^-]+)$/); if (!m) continue;
  let j; try { j = JSON.parse(fs.readFileSync(path.join(RUNS, f), 'utf8')); } catch { continue; }
  const t = index.get(j.session_id); if (!t) { console.error(`! no transcript for ${f}`); continue; }
  let r = readTranscript(t); let mainS;
  if (m[1].startsWith('sub-')) { // subagent-style: the measured run is the spawned agent, found next to the main transcript
    const dir = path.join(path.dirname(t), j.session_id, 'subagents');
    const agent = fs.existsSync(dir) ? fs.readdirSync(dir).find(x => x.endsWith('.jsonl')) : null;
    if (!agent) { console.error(`! no subagent transcript for ${f}`); continue; }
    mainS = r.S; r = readTranscript(path.join(dir, agent));
  }
  rows.push({ mainS, label: m[1], variant: m[2], rep: m[3], session: j.session_id, usd: j.total_cost_usd, turns: r.turns, S: r.S, calls: r.calls, bashGraph: r.bashGraph, bashTotal: r.bashTotal, chars: r.src });
}

const groups = {};
for (const r of rows) (groups[`${r.label}|${r.variant}`] ??= []).push(r);
const table = Object.entries(groups).map(([k, rs]) => {
  const [label, variant] = k.split('|'); const sum = {};
  for (const r of rs) for (const [s, n] of Object.entries(r.chars)) if (!s.includes(':')) (sum[s] ??= []).push(n);
  const claudeMd = rs.map(r => Object.entries(r.chars).filter(([s]) => s.startsWith('instructions:')).reduce((n, [, v]) => n + v, 0));
  const mcpCalls = rs.map(r => Object.entries(r.calls).filter(([n]) => n.startsWith('mcp__')).reduce((n, [, v]) => n + v, 0));
  return { label, variant, n: rs.length, S: rs.map(r => r.S), SMedian: median(rs.map(r => r.S)), turns: rs.map(r => r.turns), turnsMedian: median(rs.map(r => r.turns)),
    claudeMdTokMedian: Math.round(median(claudeMd) / CPT), bodyTokMedian: Math.round(median(sum.body || [0]) / CPT), promptTokMedian: Math.round(median(sum.prompt || [0]) / CPT),
    bashMedian: median(rs.map(r => r.bashTotal)), bashGraphTotal: rs.reduce((n, r) => n + r.bashGraph, 0), mcpCallsMedian: median(mcpCalls),
    usdMedian: +median(rs.map(r => r.usd || 0)).toFixed(2), skillListingChars: Math.round(median(sum.skill_listing || [0])), mcpInstrChars: Math.round(median(sum.mcp_instructions_delta || [0])), deferredChars: Math.round(median(sum.deferred_tools_delta || [0])) };
}).sort((a, b) => a.label.localeCompare(b.label) || a.variant.localeCompare(b.variant));

console.log('label'.padEnd(18), 'var'.padEnd(5), 'n', 'S median', 'S per run'.padEnd(26), 'turns (median)'.padEnd(20), 'CLAUDE.md tok', 'body tok', 'bash', 'graph-bash', 'mcp calls', 'USD');
for (const t of table) console.log(t.label.padEnd(18), t.variant.padEnd(5), t.n, String(Math.round(t.SMedian)).padStart(8), t.S.map(Math.round).join('/').padEnd(26), `${t.turns.join('/')} (${t.turnsMedian})`.padEnd(20), String(t.claudeMdTokMedian).padStart(13), String(t.bodyTokMedian).padStart(8), String(t.bashMedian).padStart(4), String(t.bashGraphTotal).padStart(10), String(t.mcpCallsMedian).padStart(9), t.usdMedian);
if (args.out) fs.writeFileSync(args.out, JSON.stringify({ cpt: CPT, table, rows }, null, 1));
