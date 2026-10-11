#!/usr/bin/env node
// CL-221: classify the failed builds of Terrio coder runs (the build loops CL-214 found) and bound the part a cheaper
// check could have avoided. Counts only: command and output text are matched in memory and never written; commands and
// error lines are compared by hash inside one run and dropped.
//
//   node tools/build-loops.mjs [--since 2026-10-01] [--denominator 3224] [--workspace <dir>] [--transcripts <dir>] [--json out.json]
//
// Sources and weights are those of the CL-214 measurement (plan 8.12): the transcripts of the Terrio workspace
// (default ~/Documents/Claude/terrio, its project dir under ~/.claude/projects), sessions + subagents, role from
// agent-setting / meta.json, USD from the PriceTable.DEFAULT of 2026-10-06, output per message = maximum.
// Two views of a build call are kept apart on purpose:
//   loose  = the CL-214 detector (command mentions gradle/terrio.mjs gradle|ci, failure = is_error or a FAIL word)
//   strict = a build invoked at command position, outcome read from the verdict lines of the Terrio wrappers
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import readline from 'node:readline';

const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d; };
const SINCE = Date.parse(arg('since', '2026-10-01'));
const DEN = +arg('denominator', 3224);
const WORKSPACE = arg('workspace', path.join(os.homedir(), 'Documents', 'Claude', 'terrio'));
const TRANSCRIPTS = arg('transcripts', path.join(os.homedir(), '.claude', 'projects', path.resolve(WORKSPACE).replace(/[^A-Za-z0-9]/g, '-')));

// ------------------------------------------------------------------ cost (CL-206 weights, PriceTable.DEFAULT 2026-10-06)
const W = { input: 1, cw5m: 1.25, cw1h: 2, cacheRead: 0.1, output: 5 };
const std = (i, o, cr = i * 0.1) => ({ input: i, output: o, cacheRead: cr, cw5m: i * 1.25, cw1h: i * 2 });
const PRICES = {
  'claude-fable-5-1': std(10, 50, 0.25), 'claude-fable-5': std(10, 50, 0.25), 'claude-opus-5-5': std(4, 20, 0.2), 'claude-opus-5': std(5, 25),
  'claude-sonnet-5-5': std(2, 10, 0.2), 'claude-sonnet-5': std(2, 10), 'claude-sonnet-4-6': std(3, 15), 'claude-haiku-5-5': std(0.1, 0.5),
  'claude-haiku-4-5': std(1, 5), '<synthetic>': std(0, 0),
};
const priceOf = m => PRICES[m] || PRICES[String(m || '').replace(/-\d{8}$/, '')] || null;
const usdOf = (u, p) => p ? (u.input * p.input + u.output * p.output + u.cacheRead * p.cacheRead + u.cw5m * p.cw5m + u.cw1h * p.cw1h) / 1e6 : 0;
const unitsOf = u => Object.entries(W).reduce((n, [k, w]) => n + u[k] * w, 0);
// a tool result of n chars is written to the cache once and read by every later turn (4 chars per token)
const carryUsd = (chars, turnsAfter, p) => p ? chars / 4 * (p.cw1h + p.cacheRead * turnsAfter) / 1e6 : 0;

// ------------------------------------------------------------------ build detection
const LOOSE_BUILD = /gradlew|terrio\.mjs\s+(gradle|ci)\b|\bgradle\s+\S*(test|compile|build|check|detekt|ktlint)|junit\.mjs|\bnpm\s+(test|run\s+(test|build|lint))\b/;
const LOOSE_FAIL = /gradle exit [1-9]|BUILD FAILED|Compilation failed|^e: file:|FAILED\b.*\btests?\b|\btests? completed, [1-9]\d* failed|\bFAIL\b(?!ED)/m;
const LOOSE_OK = /gradle exit 0|BUILD SUCCESSFUL/;

const HEREDOC = /<<-?\s*(['"]?)(\w+)\1[^\n]*\n[\s\S]*?\n\s*\2\b/g;
const POS = String.raw`(?:^|[;&|(]\s*|\bdo\s+|\bthen\s+|\$\(\s*)(?:timeout\s+\d+\s+)?(?:[A-Za-z_]+=\S+\s+)*`;
const WRAPPER = new RegExp(POS + String.raw`node\s+\S*terrio\.mjs\s+(gradle\s+(compile|precheck|test|lint)|ci\s+test)\b`, 'm');
const DIRECT = new RegExp(POS + String.raw`(?:bash\s+)?(?:\S*[\\/])?gradlew(?:\.bat)?\b([^\n;&|]*)`, 'm');
function strictBuild(raw) {
  const body = raw.replace(HEREDOC, '');
  const w = body.match(WRAPPER);
  if (w) { const k = w[2] || 'ci-test'; return k === 'lint' ? null : k; }
  const d = body.match(DIRECT);
  if (!d) return null;
  return /\btest\b|\bcheck\b|\bbuild\b/.test(d[1]) ? 'test' : /compile|classes|assemble/.test(d[1]) ? 'compile' : 'gradlew-other';
}

// ------------------------------------------------------------------ edits (what changes the code between two builds)
const WRITE_SHELL = /(^|[;&|\s])(tee|sed\s+-i\S*|git\s+apply|patch)\b|(^|[^<>&0-9])>>?\s*[^\s&|>]+|\bwrite_text\b|\bwriteFileSync\b|\bappendFileSync\b|open\([^)]*,\s*['"][wax]b?\+?['"]|\.write\(|Set-Content|Out-File|Add-Content/;
const NULLISH = /\s\d*>\s*(\/dev\/null|NUL|&\d)\b|\s2>&1/g;
function isEdit(name, input) {
  if (name === 'Edit' || name === 'Write' || name === 'MultiEdit' || name === 'NotebookEdit') return true;
  if (/^mcp__(code|codeloupe)__(replace|insert|delete|add_imports|create_file|rename|write)/.test(name)) return true;
  if (name !== 'Bash' && name !== 'PowerShell') return false;
  const raw = String(input.command || '');
  if (/\bbrain\.mjs\b|\.journal[\\/]evidence/.test(raw)) return false;
  const body = raw.replace(NULLISH, ' ').replace(/\d*>>?\s*\S*(\.log|\.out|\.txt|\.output|\.json|\$TEMP|\/tmp|Temp|\.journal)\S*/g, ' ');
  if (/\bgit\b.*\b(add|commit|stash|checkout|restore)\b/.test(body) && !/(tee|sed\s+-i|git\s+apply|patch)\b/.test(body)) return false;
  return WRITE_SHELL.test(body) && !/^\s*(rg|grep)\b[^|]*$/.test(body);
}

// ------------------------------------------------------------------ outcome of one strict build call
const textOf = c => typeof c === 'string' ? c : Array.isArray(c) ? c.map(x => x.type === 'text' ? x.text || '' : '').join('\n') : '';
const H = s => crypto.createHash('sha1').update(s).digest('base64').slice(0, 8);

const RE = {
  blocked: /PreToolUse:\w+ hook error|Nocni rezim/,
  invalid: /unexpected EOF|syntax error near|command not found|unknown command|unknown flag|MODULE_NOT_FOUND|Cannot find module|usage:|tool_use_error/i,
  pending: /call again \(resumes|you are queued|vps-test is not free|… RUNNING|the runner is busy|still busy|is held by|cancelled -- take a local slot|^RESOURCES|^waiting\s*$/m,
  // started or finished without a verdict in the output: the verdict is read from the log or the end notification
  silent: /Output is being written to|You will be notified when it completes|^(started|bg|done|end|gradle-done)\s*$|completed with no output|\(tail it while it runs\)\s*$/m,
  fail: /PRECHECK FAIL|> Task :\S+ FAILED|= FAIL gradlew|BUILD FAILED|gradle exit [1-9]|^FAIL\b|There were failing tests|^e: file:/m,
  pass: /PRECHECK PASS|= PASS gradlew|BUILD SUCCESSFUL|gradle exit 0|^PASS\b/m,
  exitFail: /^(# )?exit[= ][1-9]\d*\b|^rc=[1-9]/m,
  exitPass: /^(# )?exit[= ]0\b|^rc=0/m,
  timeout: /exit[= ]124\b|timed out|Command timed out|TimeoutException/i,
  env: /Could not (resolve|get|download|connect|create service)|UnknownHost|Connection (refused|reset)|Unable to (start|delete|access|establish)|daemon (disappeared|stopped|will be stopped)|OutOfMemory|No space left|Address already in use|Timeout waiting to lock|being used by another process|Could not (delete|move)|Cannot connect to the Docker|JAVA_HOME|Permission denied|ENOENT|no such file|EBUSY|toolchain|Failed to (fetch|connect)|rate limit|ECONN/i,
  compile: /^e: file:|Compilation error|Execution failed for task '[^']*compile\w*(Kotlin|Java)|:compile\w*(Kotlin|Java) FAILED|error: cannot find symbol/m,
  test: /There were failing tests|\bFAILED\b.*\btest|:test FAILED|Test.*> .* FAILED|tests="\d+"[^\n]*failures="[1-9]|AssertionError|AssertionFailedError/m,
};
// compile errors a PostToolUse diagnostics hook (CL-217: syntax errors, unknown names, wrong argument count) can see
const ERR_KIND = [
  ['syntax', /Expecting|Unexpected tokens|Unresolved label|Missing closing|Unclosed|syntax error|Illegal|Expected (a |an )?(name|expression|declaration|member|parameter)/],
  ['unknown-name', /Unresolved reference|Cannot find a parameter with this name|cannot find symbol|No such member|Unresolved class/],
  ['arg-count', /No value passed for parameter|Too many arguments|Not enough information|No parameter with name|None of the following candidates|Cannot infer type for type parameter|overload resolution ambiguity/i],
  ['type', /type mismatch|Type mismatch|Argument type mismatch|Return type mismatch|is not satisfied|Smart cast|cannot be called|Overload resolution|can't be applied|Platform declaration clash|Conflicting|Redeclaration|nullable|non-null|Only safe|Unsafe call|No 'get'|Operator|Condition type|Cannot access|is invisible|protected|private|abstract member|must override|is not abstract|Modifier/],
];
function compileKinds(t) {
  const out = new Map();
  for (const l of t.split('\n')) {
    const m = l.match(/^(?:\s*\|\s*)?e: file:\/\/\S*?([^\/\\\s]+\.kt):\d+:\d+\s+(.*)$/); if (!m) continue;
    const msg = m[2]; const k = (ERR_KIND.find(([, re]) => re.test(msg)) || ['other'])[0];
    out.set(H(m[1] + '|' + msg.replace(/'[^']*'/g, "'_'")), k);
  }
  return out;                      // signature line -> kind
}
function signature(t, cause) {
  if (cause === 'compile') { const s = [...compileKinds(t).keys()].sort(); if (s.length) return 'c:' + H(s.join()); }
  if (cause === 'test') {
    const names = [...new Set((t.match(/[\w.$]+(?:Test|Tests|IT|Spec)\S*\s*>\s*[^\n]*?FAILED|^\s*\|?\s*(?:FAILED|Test) [^\n]*/gm) || []).map(x => x.replace(/\d+/g, 'N')))].sort();
    if (names.length) return 't:' + H(names.join('|'));
    const task = t.match(/Execution failed for task '([^']+)'/); if (task) return 't:' + H(task[1]);
  }
  return null;
}
// what a log read (rg/tail of a gradle log, a report, a background output file) shows about the failure it belongs to
const LOG_READ = /\.journal[\\/]logs|gradle-(compile|precheck|test)-|\.output\b|reports[\\/]tests|\.log\b/;
function logTags(t) {
  if (!RE.compile.test(t) && !RE.test.test(t) && !RE.env.test(t)) return null;
  const cause = RE.compile.test(t) ? 'compile' : RE.test.test(t) ? 'test' : 'env';
  return { cause, sig: signature(t, cause), kinds: cause === 'compile' ? [...compileKinds(t).values()] : [] };
}
// wall time the wrapper or Gradle printed for the run, in seconds (0 when the output is cut before it)
function secondsOf(t) {
  const m = t.match(/# exit \d+ after (?:(\d+)m\s*)?(\d+)s/) || t.match(/BUILD FAILED in (?:(\d+)m\s*)?(\d+)s/);
  return m ? (+m[1] || 0) * 60 + +m[2] : 0;
}
function failure(t) {
  const cause = RE.timeout.test(t) && !/BUILD FAILED/.test(t) ? 'timeout' : RE.compile.test(t) ? 'compile' : RE.env.test(t) && !RE.test.test(t) ? 'env' : RE.test.test(t) ? 'test' : 'other';
  return { o: 'fail', cause, secs: secondsOf(t), sig: signature(t, cause), kinds: cause === 'compile' ? [...compileKinds(t).values()] : [] };
}
// the verdict a text carries, or null (a log read after a background build uses the same rule)
function verdict(isError, t) {
  if (RE.fail.test(t)) return failure(t);
  if (RE.pass.test(t)) return { o: 'pass' };
  if (RE.pending.test(t)) return { o: 'pending' };
  if (RE.silent.test(t)) return { o: 'silent' };
  if (RE.exitFail.test(t)) return failure(t);
  if (RE.exitPass.test(t)) return { o: 'pass' };
  return isError ? failure(t) : null;
}
function outcome(isError, t) {
  if (RE.blocked.test(t)) return { o: 'blocked', why: 'refused by the guard hook' };
  if (RE.invalid.test(t) && !RE.fail.test(t) && !RE.pass.test(t) && (isError || !RE.exitPass.test(t)))
    return { o: 'invalid', why: /MODULE_NOT_FOUND|Cannot find module/.test(t) ? 'wrapper started in the wrong directory' : /unknown (command|flag)|usage:/i.test(t) ? 'unknown wrapper command or flag' : 'shell syntax or tool error' };
  return verdict(isError, t) || { o: 'unknown' };
}

// ------------------------------------------------------------------ one run
async function readRun(file, meta) {
  const turns = []; const seen = new Map(); const pending = new Map(); const calls = []; const byId = new Map();
  let role = meta.role, model = null, start = null, scanned = 0;
  const rl = readline.createInterface({ input: fs.createReadStream(file, 'utf8'), crlfDelay: Infinity });
  for await (const line of rl) {
    if (!line) continue;
    if (meta.kind === 'session' && !role.startsWith('terrio-') && ++scanned > 40) { rl.close(); return null; }
    let o; try { o = JSON.parse(line); } catch { continue; }
    if (o.type === 'agent-setting' && o.agentSetting) role = o.agentSetting;
    if (o.timestamp) start ??= o.timestamp;
    if (o.type === 'assistant' && o.message) {
      const m = o.message; model ??= m.model; const u = m.usage || {};
      if (m.id && seen.has(m.id)) { const t = turns[seen.get(m.id)]; if ((u.output_tokens || 0) > t.usage.output) t.usage.output = u.output_tokens; }
      else if (m.id) {
        seen.set(m.id, turns.length);
        turns.push({ model: m.model, usage: { input: u.input_tokens || 0, output: u.output_tokens || 0, cacheRead: u.cache_read_input_tokens || 0,
          cw1h: u.cache_creation?.ephemeral_1h_input_tokens || 0,
          cw5m: u.cache_creation?.ephemeral_5m_input_tokens ?? Math.max(0, (u.cache_creation_input_tokens || 0) - (u.cache_creation?.ephemeral_1h_input_tokens || 0)) } });
      }
      const ti = m.id != null ? seen.get(m.id) : null; if (ti == null) continue;
      for (const b of Array.isArray(m.content) ? m.content : []) if (b.type === 'tool_use') {
        const input = b.input || {}; const shell = b.name === 'Bash' || b.name === 'PowerShell'; const raw = shell ? String(input.command || '') : '';
        const c = { turn: ti, edit: isEdit(b.name, input), loose: shell && LOOSE_BUILD.test(raw), kind: shell ? strictBuild(raw) : null, key: shell ? H(raw.replace(/\s+/g, ' ').trim()) : null };
        c.kt = c.edit && /\.(kt|kts|java)\b/.test(JSON.stringify(input)); c.shell = shell; c.logRead = LOG_READ.test(JSON.stringify(input)); calls.push(c); pending.set(b.id, c); byId.set(b.id, c);
      }
    }
    // a background command reports its end as a task notification (status + exit code, no output)
    const note = o.type === 'attachment' ? String(o.attachment?.prompt || '') : '';
    if (note.includes('<task-notification>')) {
      const id = note.match(/<tool-use-id>([^<]+)</)?.[1]; const c = id && byId.get(id);
      if (c) { c.bg = note.match(/<status>([^<]+)</)?.[1] || null; c.bgExit = note.match(/exit code (\d+)/)?.[1]; }
    }
    if (o.type === 'user' && Array.isArray(o.message?.content)) for (const b of o.message.content) {
      if (b.type !== 'tool_result' || !pending.has(b.tool_use_id)) continue;
      const c = pending.get(b.tool_use_id); pending.delete(b.tool_use_id);
      const t = textOf(b.content); c.chars = t.length;
      if (c.loose) { c.looseFail = !!b.is_error || (LOOSE_FAIL.test(t) && !LOOSE_OK.test(t)); c.looseOk = !c.looseFail && LOOSE_OK.test(t); }
      if (c.kind) Object.assign(c, outcome(!!b.is_error, t));
      if (!c.kind && (c.logRead || c.shell)) {
        // any shell read may print the log (variables hide the path); without a log path only compiler lines count, source text could mention anything
        c.tags = c.logRead ? logTags(t) : /^(\s*\|)?\s*e: file:/m.test(t) ? logTags(t) : null;
        c.v = c.logRead && (RE.fail.test(t) || RE.pass.test(t)) ? verdict(false, t) : null;
      }
    }
  }
  if (!/^terrio-coder(-high)?$/.test(role) || !turns.length || (start && Date.parse(start) < SINCE)) return null;
  const p = priceOf(model);
  for (const t of turns) { t.usd = usdOf(t.usage, priceOf(t.model) || p); }
  const usage = turns.reduce((a, t) => { for (const k in a) a[k] += t.usage[k]; return a; }, { input: 0, output: 0, cacheRead: 0, cw1h: 0, cw5m: 0 });
  return { file: path.basename(file), role, turns, calls, p, units: unitsOf(usage), usd: usdOf(usage, p) };
}

function listRuns() {
  const out = []; const fresh = f => fs.statSync(f).mtimeMs >= SINCE;
  for (const e of fs.readdirSync(TRANSCRIPTS, { withFileTypes: true })) {
    const p = path.join(TRANSCRIPTS, e.name);
    if (e.isFile() && e.name.endsWith('.jsonl') && fresh(p)) out.push({ file: p, kind: 'session', role: 'main' });
    if (e.isDirectory()) {
      const sub = path.join(p, 'subagents'); if (!fs.existsSync(sub)) continue;
      for (const f of fs.readdirSync(sub)) if (f.endsWith('.jsonl') && fresh(path.join(sub, f))) {
        let m = {}; try { m = JSON.parse(fs.readFileSync(path.join(sub, f.replace(/\.jsonl$/, '.meta.json')), 'utf8')); } catch { /* no meta */ }
        out.push({ file: path.join(sub, f), kind: 'subagent', role: m.agentType || 'unknown' });
      }
    }
  }
  return out;
}

// ------------------------------------------------------------------ per-run analysis
// sequence of strict build attempts that ran (blocked/pending/async are kept for counting, not for the loop logic)
function analyse(r) {
  const n = r.turns.length; const att = [];
  let editSince = false, prevRan = null;
  r.calls.forEach((c, i) => {
    if (c.edit) editSince = true;
    if (!c.kind) return;
    if (c.o === 'silent' && c.bg) { c.o = c.bg === 'completed' ? 'pass' : 'fail'; if (c.o === 'fail') c.cause = /killed|stopped/.test(c.bg) ? 'timeout' : null; }
    else if (c.o === 'silent' || c.o === 'unknown') {
      // started in the background or printed nothing: the verdict is in the log the coder reads before the next build
      for (let j = i + 1; j < r.calls.length && j <= i + 12; j++) {
        const d = r.calls[j]; if (d.kind) break;
        if (d.v && (d.v.o === 'pass' || d.v.o === 'fail')) { Object.assign(c, d.v); c.viaLog = true; break; }
      }
    }
    if (c.o === 'fail' && (!c.cause || c.cause === 'other')) {
      // the wrapper showed only a verdict line: the cause is in the log the coder reads next, before the next edit or build
      for (let j = i + 1; j < r.calls.length && j <= i + 8; j++) {
        const d = r.calls[j]; if (d.kind || d.edit) break;
        if (d.tags) { Object.assign(c, d.tags); c.viaLog = true; break; }
      }
      c.cause ||= 'other';
    }
    const a = { turn: c.turn, kind: c.kind, o: c.o, cause: c.cause, sig: c.sig, kinds: c.kinds || [], secs: c.secs || 0, key: c.key, editedBefore: editSince, viaLog: !!c.viaLog, why: c.why,
      usd: r.turns[c.turn].usd + carryUsd(c.chars || 0, n - 1 - c.turn, priceOf(r.turns[c.turn].model) || r.p) };
    if (c.o === 'fail' || c.o === 'pass') { a.prev = prevRan; prevRan = a; editSince = false; }
    att.push(a);
  });
  // first green, failures before it
  let green = false, failBeforeGreen = 0;
  for (const a of att) { if (a.o === 'pass') { green = true; break; } if (a.o === 'fail') failBeforeGreen++; }
  // repetition class of each failure against the previous ran build of the run
  for (const a of att) {
    if (a.o === 'pass') continue;
    if (a.o !== 'fail') continue;
    const pr = a.prev;
    if (!pr || pr.o === 'pass') a.rep = 'first';
    else if (pr.cause === 'timeout' || pr.cause === 'env') a.rep = 'retry-after-' + pr.cause;
    else if (!a.editedBefore) a.rep = 'rerun-no-edit';
    else if (a.sig && pr.sig && a.sig === pr.sig) a.rep = 'same-error-after-edit';
    else if (a.sig && pr.sig) a.rep = 'new-error';
    else a.rep = 'undecided';
  }
  // span: turns from a failed build to the next build call (the fix cycle), cost only
  for (let i = 0; i < att.length; i++) if (att[i].o === 'fail') {
    const end = i + 1 < att.length ? att[i + 1].turn : n; let s = 0; for (let t = att[i].turn + 1; t < end; t++) s += r.turns[t].usd; att[i].spanUsd = s;
  }
  return { att, green, failBeforeGreen, ran: att.filter(a => a.o === 'fail' || a.o === 'pass').length };
}

// ------------------------------------------------------------------ report
const sum = (a, f) => a.reduce((n, x) => n + f(x), 0);
const pct = (a, q) => { if (!a.length) return 0; const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(s.length * q))]; };
const f1 = n => (+n).toFixed(1); const P = (x, d = 1) => (100 * x).toFixed(d) + ' %';
const out = []; const say = (...a) => out.push(a.join(' '));
const table = (head, rows) => { say('| ' + head.join(' | ') + ' |'); say('|' + head.map(() => '---').join('|') + '|'); for (const r of rows) say('| ' + r.join(' | ') + ' |'); };

const runs = [];
for (const m of listRuns()) { const r = await readRun(m.file, m); if (r) runs.push(r); }
const p75 = pct(runs.map(r => r.units), .75);
const coderUsd = sum(runs, r => r.usd);

// CL-214 loop runs: the loose detector, so the 12 runs / 118 USD can be tied to plan 8.12
for (const r of runs) {
  const b = r.calls.filter(c => c.loose && c.looseFail != null);
  let f = 0, green = false; for (const c of b) { if (c.looseOk) { green = true; break; } if (c.looseFail) f++; }
  r.loose = { builds: b.length, failed: b.filter(c => c.looseFail).length, failBeforeGreen: f, green };
  r.loop214 = r.units > p75 && (f >= 3 || (r.loose.failed >= 4 && r.loose.failed >= 0.5 * b.length));
  r.a = analyse(r);
}
const L = runs.filter(r => r.loop214);
const lUsd = sum(L, r => r.usd);
say(`## 0. Reproduction of CL-214\n`);
say(`coder runs ${runs.length}, ${f1(coderUsd)} USD; p75 ${Math.round(p75 / 1e3)}k units; above p75 ${runs.filter(r => r.units > p75).length}; loose build-loop runs (compile-loop rule of 8.12) **${L.length}**, **${f1(lUsd)} USD** = ${P(lUsd / DEN)} of ${DEN} USD (1 % = ${f1(DEN / 100)} USD).`);

const att = rs => rs.flatMap(r => r.a.att.map(a => ({ ...a, run: r })));
function describe(title, rs) {
  const A = att(rs); const ran = A.filter(a => a.o === 'fail' || a.o === 'pass'); const F = A.filter(a => a.o === 'fail');
  say(`\n### ${title}: ${rs.length} runs, ${f1(sum(rs, r => r.usd))} USD`);
  say(`strict build calls ${A.length}: ran ${ran.length} (pass ${ran.length - F.length}, fail ${F.length}); not a build result: blocked by guard ${A.filter(a => a.o === 'blocked').length}, invalid command ${A.filter(a => a.o === 'invalid').length}, pending/queued ${A.filter(a => a.o === 'pending').length}, started in background or silent, verdict never seen ${A.filter(a => a.o === 'silent').length}, unrecognised ${A.filter(a => a.o === 'unknown').length}.`);
  const kinds = [...new Set(A.map(a => a.kind))];
  say('\nBy build kind (ran / failed):'); table(['kind', 'ran', 'failed', 'fail rate'], kinds.map(k => { const x = ran.filter(a => a.kind === k), y = x.filter(a => a.o === 'fail'); return [k, x.length, y.length, x.length ? P(y.length / x.length, 0) : '-']; }));
  const bad = A.filter(a => a.o === 'blocked' || a.o === 'invalid');
  const causes = ['compile', 'test', 'env', 'timeout', 'other'];
  const whys = [...new Set(bad.map(a => a.why))];
  if (whys.length) say('Commands that never reached Gradle: ' + whys.map(w => `${w} ${bad.filter(a => a.why === w).length}`).join(', ') + '.');
  say('\nFailed builds by cause (cost = the build turn + its output carried by later turns; span = the turns up to the next build, i.e. the fix cycle):');
  table(['cause', 'failed', 'share', 'cost USD', 'span USD', 'build wall min'], [...causes.map(c => { const x = F.filter(a => a.cause === c); return [c, x.length, P(x.length / (F.length + bad.length || 1), 0), f1(sum(x, a => a.usd)), f1(sum(x, a => a.spanUsd)), f1(sum(x, a => a.secs) / 60)]; }),
    ['blocked/invalid command (never reached Gradle)', bad.length, P(bad.length / (F.length + bad.length || 1), 0), f1(sum(bad, a => a.usd)), '-', '-']]);
  const reps = ['first', 'new-error', 'same-error-after-edit', 'rerun-no-edit', 'retry-after-env', 'retry-after-timeout', 'undecided'];
  say('\nFailed builds by repetition (against the previous build that ran in the run):');
  table(['repetition', 'failed', 'cost USD'], reps.map(c => { const x = F.filter(a => a.rep === c); return [c, x.length, f1(sum(x, a => a.usd))]; }));
  const ck = F.filter(a => a.cause === 'compile');
  say('\nCompile failures by what a post-edit diagnostics hook could see (one failure counts for its worst kind; hook = syntax, unknown-name, arg-count):');
  const order = ['syntax', 'unknown-name', 'arg-count', 'type', 'other'];
  const worst = a => order.find(k => a.kinds.includes(k)) || 'no e: line in output';
  table(['kind', 'failed builds', 'cost USD'], [...order, 'no e: line in output'].map(k => { const x = ck.filter(a => worst(a) === k); return [k, x.length, f1(sum(x, a => a.usd))]; }));
  say('\nCompile failures by the build that met them (precheck and compile start with the compile; test and ci-test would stop at the same compile error, so a compile check first saves minutes, not turns):');
  table(['build kind', 'compile failures'], [...new Set(ck.map(a => a.kind))].map(k => [k, ck.filter(a => a.kind === k).length]));
  const hookable = ck.filter(a => ['syntax', 'unknown-name', 'arg-count'].includes(worst(a)));
  // avoidable
  const certain = [...bad, ...F.filter(a => a.rep === 'rerun-no-edit')];
  const hookSaved = hookable.filter(a => !certain.includes(a));
  const sameAfter = F.filter(a => a.rep === 'same-error-after-edit' && !certain.includes(a));
  const lo = sum(certain, a => a.usd), hi = lo + sum(hookSaved, a => a.usd) + sum(sameAfter.filter(a => !hookSaved.includes(a)), a => a.usd);
  say(`\nAvoidable: certain = invalid/blocked commands + re-runs with no edit since the last failure: ${certain.length} builds, ${f1(lo)} USD. Upper bound adds compile failures a hook shows at the edit (${hookSaved.length}) and a repeated error after an edit that did not fix it (${sameAfter.filter(a => !hookSaved.includes(a)).length}): ${f1(hi)} USD.`);
  // ceiling: every failed build and the whole fix cycle after it counted as avoidable (no run avoids all of it)
  const ceil = sum(F, a => a.usd + a.spanUsd) + sum(bad, a => a.usd);
  say(`Ceiling (every failed build with its fix cycle up to the next build): ${f1(ceil)} USD = ${P(ceil / sum(rs, r => r.usd))} of these runs.`);
  return { lo, hi, ceil, F, bad, certain };
}
const resL = describe('CL-214 loop runs (loose rule)', L);
const strictLoop = runs.filter(r => r.a.failBeforeGreen >= 3);
const resS = describe('strict build loops (>= 3 failed builds before the first green)', strictLoop);
const resA = describe('all coder runs', runs);
const never = runs.filter(r => !r.a.green);
const noBuild = never.filter(r => r.a.ran === 0), failedOnly = never.filter(r => r.a.ran > 0);
say(`\n### Runs without a green build (strict): ${never.length} of ${runs.length}, ${f1(sum(never, r => r.usd))} USD; no build ran at all ${noBuild.length} (${f1(sum(noBuild, r => r.usd))} USD, median ${Math.round(pct(noBuild.map(r => r.turns.length), .5))} turns), builds ran but none passed ${failedOnly.length} (${f1(sum(failedOnly, r => r.usd))} USD).`);
const ids = new Set(L.map(r => r.file)); say(`overlap of the CL-214 loop runs with strict loops: ${strictLoop.filter(r => ids.has(r.file)).length} of ${L.length} / ${strictLoop.length}; CL-214 loop runs without any strict build failure: ${L.filter(r => r.a.att.every(a => a.o !== 'fail')).length}`);

const looseNever = runs.filter(r => !r.loose.green);
const kt = noBuild.filter(r => r.calls.some(c => c.kt));
say(`Same with the CL-214 detector: ${looseNever.length} coder runs never reach a green build (${f1(sum(looseNever, r => r.usd))} USD). Of the ${noBuild.length} strict no-build runs, ${kt.length} edited Kotlin/Java files (${f1(sum(kt, r => r.usd))} USD, median ${Math.round(pct(kt.map(r => r.calls.filter(c => c.kt).length), .5))} edits) and ${noBuild.length - kt.length} did not (${f1(sum(noBuild.filter(r => !kt.includes(r)), r => r.usd))} USD).`);

const card = looseNever.filter(r => r.loose.builds > 0);   // the "never reach a green build" set of the card: builds seen, none read as green by CL-214
const cardStrict = { green: card.filter(r => r.a.green), failedOnly: card.filter(r => !r.a.green && r.a.ran > 0), none: card.filter(r => r.a.ran === 0) };
say(`The card's set (a build call seen, none read as green by the CL-214 detector, which knows only BUILD SUCCESSFUL / gradle exit 0): ${card.length} runs, ${f1(sum(card, r => r.usd))} USD. Read strictly: green after all ${cardStrict.green.length} (${f1(sum(cardStrict.green, r => r.usd))} USD), builds ran and none passed ${cardStrict.failedOnly.length} (${f1(sum(cardStrict.failedOnly, r => r.usd))} USD), no build ran (the command only mentioned gradle) ${cardStrict.none.length} (${f1(sum(cardStrict.none, r => r.usd))} USD).`);
const sav = [['CL-214 loop runs', resL], ['strict loops', resS], ['all coder runs', resA]];
say('\n### Rule of 1 %');
table(['base', 'certain USD', 'upper USD', 'cut 30 % of upper', 'ceiling USD', 'cut 30 % of ceiling', 'ceiling of ' + DEN + ' USD'],
  sav.map(([n, r]) => [n, f1(r.lo), f1(r.hi), P(0.3 * r.hi / DEN, 2), f1(r.ceil), P(0.3 * r.ceil / DEN, 2), P(r.ceil / DEN, 2)]));
console.log(out.join('\n'));
if (arg('json')) fs.writeFileSync(arg('json'), JSON.stringify({ runs: runs.length, loop214: L.length, lUsd, sav: sav.map(([n, r]) => ({ n, lo: r.lo, hi: r.hi, ceil: r.ceil })) }));
