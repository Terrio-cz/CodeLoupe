#!/usr/bin/env node
// Reproducible benchmark of CodeLoupe against a grep-and-read baseline and GitNexus (CL-123).
//
//   ./gradlew installDist
//   node tools/benchmark.mjs --work <scratch dir> [--cli build/install/codeloupe/bin/codeloupe] [--port 47651]
//        [--items 8] [--no-gitnexus] [--out docs/benchmarks.md] [--json docs/benchmarks.json] [--svg docs/benchmarks.svg]
//   node tools/benchmark.mjs --report-only docs/benchmarks.json      # rewrite the markdown and SVG from a saved run
//
// What it does (every number in docs/benchmarks.md comes from this script):
//   1. Clones two public repositories into <work> at pinned commits (REPOS below): a mid-size Kotlin library and CodeLoupe.
//   2. Picks the questions to ask from the checked-out sources by a fixed rule (SHA-1 order of the names, no tool is
//      consulted), so the same commits give the same questions.
//   3. Answers each question three ways and counts what an agent would have to read: `minimal` and `typical` are
//      deterministic simulations of an agent without an index (rg and whole-file reads, see BASELINES), CodeLoupe is a real
//      daemon (throwaway CODELOUPE_HOME, its own port) asked over MCP, GitNexus is its public npm package asked over MCP
//      (stdio) with its home redirected into <work>.
//   4. Measures the tool definitions an MCP client has to keep in context (tools/list), first-index time, peak and idle
//      memory, idle CPU and what each tool needs to run.
// Tokens are characters / 3.16 (docs/context-audit.md: the ratio measured on Claude Code transcripts); only ratios between
// the modes matter, they all use the same constant. A question a tool cannot answer is `n/a`, a failed run `not measured`.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn, spawnSync, execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const IS_WIN = process.platform === 'win32';
const CHARS_PER_TOKEN = 3.16;

/** Public repositories, pinned. `base` is what the index is built from (the default branch), `tip` a later commit of the
 *  same history that plays the part of a feature branch for the `changes` question. */
export const REPOS = [
  { name: 'exposed', url: 'https://github.com/JetBrains/Exposed.git', license: 'Apache-2.0',
    base: '023a6a3a73c5a3717b824a152b506edc28b662f0', tip: 'be0b6ebc08346b270085be65eeb188d494e651f0' },
  { name: 'codeloupe', url: 'https://github.com/Terrio-cz/CodeLoupe.git', license: 'PolyForm-Noncommercial-1.0.0',
    base: 'f370022024d544549cd94e4568745d29b90de5cf', tip: '489b6122f76d33bc1a8b33549d5084a6044815c7' },
];
const GITNEXUS = { package: 'gitnexus', version: '1.6.12', node: '22.23.3', bufferPool: 2 * 1024 ** 3 };
/** Literal strings for the text search; per repository the first ones with at least 30 hits (the answer is cut at 30). */
const GREP_CANDIDATES = ['lateinit var', 'TODO', 'require(', 'throw IllegalStateException', 'error(', '@Deprecated', 'Exception', 'check('];

const BASELINES = {
  minimal: 'rg lines only (rg -n -w, rg -n for source: plus the exact declaration lines, as if the agent knew the range); git diff --stat for the branch',
  typical: 'rg with 3 lines of context per hit (rg -n -w -C3); whole-file read for source and outline questions; full git diff for the branch',
};

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const a = process.argv[i];
  if (!a.startsWith('--')) continue;
  const next = process.argv[i + 1];
  args[a.slice(2)] = next === undefined || next.startsWith('--') ? true : process.argv[++i];
}
const RG = args.rg || process.env.BENCH_RG || 'rg'; // --rg points at a ripgrep binary when none is on PATH
const log = (...m) => console.error('[benchmark]', ...m);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const tok = s => Math.ceil(s.length / CHARS_PER_TOKEN);
const median = xs => { if (!xs.length) return null; const s = [...xs].sort((a, b) => a - b); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
const sum = xs => xs.reduce((a, b) => a + b, 0);
const sha1 = s => crypto.createHash('sha1').update(s).digest('hex');
const mb = kb => Math.round(kb / 1024);

function sh(cmd, argv, opt = {}) {
  // stdin must not be a pipe: rg would search it instead of the directory
  return spawnSync(cmd, argv, { encoding: 'utf8', maxBuffer: 1 << 30, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], ...opt });
}
function git(dir, ...a) {
  const r = sh('git', ['-C', dir, ...a]);
  if (r.status !== 0) throw new Error(`git ${a.join(' ')}: ${r.stderr}`);
  return r.stdout;
}
function rg(dir, ...a) {
  const r = sh(RG, ['--color', 'never', '--no-heading', '--sort', 'path', ...a], { cwd: dir, env: { ...process.env, ARGV0: 'rg' } });
  if (r.error || r.status > 1) throw new Error(`rg ${a.join(' ')}: ${r.error ?? r.stderr}`);
  return r.stdout;
}
function timed(f, n = 3) {
  const ms = []; let out;
  for (let i = 0; i < n; i++) { const t = performance.now(); out = f(); ms.push(performance.now() - t); }
  return { out, ms: median(ms) };
}
function dirSize(dir) {
  let n = 0;
  const walk = d => { for (const e of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, e.name); if (e.isDirectory()) walk(p); else try { n += fs.statSync(p).size; } catch { /* vanished */ } } };
  try { walk(dir); } catch { /* absent */ }
  return n;
}

// ---------------------------------------------------------------- processes

/** One snapshot of every process: pid, parent, working set (KB) and CPU time (ms). */
function snapshot() {
  return new Promise(resolve => {
    const done = (err, out) => {
      const procs = [];
      for (const l of String(out || '').split(/\r?\n/)) {
        const f = l.trim().split(';');
        if (f.length === 4 && /^\d+$/.test(f[0])) procs.push({ pid: +f[0], ppid: +f[1], rssKb: +f[2], cpuMs: +f[3] });
      }
      resolve(procs);
    };
    if (IS_WIN) {
      // integers only and ';' as separator: a decimal comma (Czech locale) would split the fields
      const ps = "Get-CimInstance Win32_Process | ForEach-Object { '{0};{1};{2};{3}' -f $_.ProcessId,$_.ParentProcessId,([int64][math]::Floor($_.WorkingSetSize/1024)),([int64][math]::Floor(([int64]$_.KernelModeTime+[int64]$_.UserModeTime)/10000)) }";
      execFile('powershell.exe', ['-NoProfile', '-Command', ps], { maxBuffer: 1 << 26, windowsHide: true }, done);
    } else {
      execFile('ps', ['-eo', 'pid=,ppid=,rss=,cputimes='], { maxBuffer: 1 << 26 }, (e, out) =>
        done(e, String(out || '').split('\n').map(l => l.trim().split(/\s+/)).filter(f => f.length === 4).map(f => `${f[0]};${f[1]};${f[2]};${+f[3] * 1000}`).join('\n')));
    }
  });
}
function tree(snap, rootPid) {
  const out = []; const todo = [rootPid]; const seen = new Set();
  while (todo.length) {
    const p = todo.pop(); if (seen.has(p)) continue; seen.add(p);
    const me = snap.find(x => x.pid === p); if (me) out.push(me);
    for (const c of snap) if (c.ppid === p && !seen.has(c.pid)) todo.push(c.pid);
  }
  return out;
}
const treeRss = (snap, pid) => sum(tree(snap, pid).map(p => p.rssKb));
const treeCpu = (snap, pid) => sum(tree(snap, pid).map(p => p.cpuMs));

/** Runs `work` while sampling the process tree under `pid()` (a sample takes ~0.5 s); the peak is a lower bound. */
async function withPeakRss(pid, work) {
  let stop = false; let peak = 0;
  const sampler = (async () => { while (!stop) { const p = pid(); if (p) peak = Math.max(peak, treeRss(await snapshot(), p)); await sleep(500); } })();
  try { return { value: await work(), peakRssKb: peak }; } finally { stop = true; await sampler; }
}

// ---------------------------------------------------------------- repositories

/** What indexing left in the checkout: git's view of the untracked and modified paths (top level, deduplicated). */
function repoWrites(dir) {
  const lines = git(dir, 'status', '--porcelain', '--untracked-files=all').split('\n').filter(Boolean);
  return { files: lines.length, top: [...new Set(lines.map(l => l.slice(3).split('/')[0]))].slice(0, 6) };
}

export function setupRepo(r, work) {
  const dir = path.join(work, 'repos', r.name);
  const feature = path.join(work, 'repos', `${r.name}-feature`);
  const gn = path.join(work, 'repos', `${r.name}-gn`);
  if (!fs.existsSync(path.join(dir, '.git'))) {
    fs.mkdirSync(path.dirname(dir), { recursive: true });
    log('clone', r.url);
    const c = sh('git', ['clone', '--quiet', r.url, dir]); if (c.status !== 0) throw new Error(`clone ${r.url}: ${c.stderr}`);
    git(dir, 'checkout', '-q', '-B', 'main', r.base);
    git(dir, 'remote', 'remove', 'origin'); // the default branch is then `main` = base, whatever the upstream did later
  }
  if (git(dir, 'rev-parse', 'main').trim() !== r.base) throw new Error(`${dir}: main is not ${r.base}`);
  if (!fs.existsSync(feature)) git(dir, 'worktree', 'add', '-q', '-B', 'feature', feature, r.tip);
  if (!fs.existsSync(path.join(gn, '.git'))) {
    const c = sh('git', ['clone', '--quiet', '--shared', dir, gn]); if (c.status !== 0) throw new Error(`clone ${gn}: ${c.stderr}`);
    git(gn, 'checkout', '-q', '--detach', r.base);
  }
  return { ...r, dir, feature, gn };
}

const isTest = f => /(^|\/)(test|tests|androidTest|commonTest|jvmTest|testFixtures|integrationTest|examples?|samples?)\//.test(f);
const TYPE = /^(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|annotation|inner|value|fun|expect|actual|inline|const)\s+)*(class|interface|object)\s+(\w+)/;
const MEMBER = /^ {4}(?:(?:public|internal|protected|override|open|abstract|suspend|inline|operator|infix|final|tailrec)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(/;

/** The exact lines an agent that already knew the range would print: doc comment and annotations, then up to the closing brace. */
function declSlice(lines, idx) {
  let start = idx;
  while (start > 0 && /^\s*(@|\/\*\*|\*|\*\/|\/\/)/.test(lines[start - 1])) start--;
  let depth = 0, paren = 0, seen = false, end = idx;
  for (let i = idx; i < lines.length; i++) {
    const code = lines[i].replace(/"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/g, '""').replace(/\/\/.*$/, '');
    for (const ch of code) {
      if (ch === '{') { depth++; seen = true; } else if (ch === '}') depth--; else if (ch === '(') paren++; else if (ch === ')') paren--;
    }
    end = i;
    if (seen && depth <= 0 && paren <= 0) break;
    if (!seen && paren <= 0 && i > idx - 1 && !/(=|,|\+|&&|\|\||\?:|->)\s*$/.test(code.trimEnd()) && !(lines[i + 1] ?? '').trimStart().startsWith('{')) break;
  }
  return lines.slice(start, end + 1).join('\n');
}

/** Questions for one repository, chosen from the sources by a fixed rule. */
export function pickQuestions(r, n) {
  const files = git(r.dir, 'ls-files', '*.kt').split('\n').filter(f => f && !isTest(f));
  const allFiles = git(r.dir, 'ls-files', '*.kt').split('\n').filter(Boolean);
  const text = new Map(allFiles.map(f => [f, fs.readFileSync(path.join(r.dir, f), 'utf8')]));
  const filesWithWord = new Map();
  for (const [f, t] of text) for (const w of new Set(t.match(/\b[A-Za-z_]\w{2,}\b/g) ?? [])) { let s = filesWithWord.get(w); if (!s) filesWithWord.set(w, s = new Set()); s.add(f); }
  const refFiles = (w, own) => [...(filesWithWord.get(w) ?? [])].filter(f => f !== own).length;

  const types = []; const typeCount = new Map(); const funCount = new Map();
  for (const [f, t] of text) {
    const lines = t.split('\n');
    lines.forEach((l, i) => {
      const m = TYPE.exec(l); if (m && !isTest(f)) { types.push({ name: m[2], kind: m[1], file: f, line: i, modifiers: l.slice(0, l.indexOf(m[1])) }); typeCount.set(m[2], (typeCount.get(m[2]) ?? 0) + 1); }
      const fm = /\bfun\s+(?:<[^>]+>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(/.exec(l); if (fm) funCount.set(fm[1], (funCount.get(fm[1]) ?? 0) + 1);
    });
  }
  const order = (list, key) => [...list].sort((a, b) => sha1(`${r.name}:${key(a)}`).localeCompare(sha1(`${r.name}:${key(b)}`)));
  const uniqueTypes = types.filter(t => typeCount.get(t.name) === 1 && t.name.length >= 4 && refFiles(t.name, t.file) >= 3 && refFiles(t.name, t.file) <= 30);
  const typeItems = order(uniqueTypes, t => t.name).slice(0, n).map(t => {
    const lines = text.get(t.file).split('\n');
    return { ...t, slice: declSlice(lines, t.line), fileLines: lines.length };
  });

  const hierarchyRe = name => `^\\s*(?:\\w+\\s+)*(?:class|object|interface)\\b[^{=]*[:,]\\s*${name}\\b(?:\\s*[<(,{]|\\s*$)`;
  const baseTypes = order(types.filter(t => typeCount.get(t.name) === 1 && t.name.length >= 4)
    .filter(t => t.kind === 'interface' || /\b(abstract|open|sealed)\b/.test(t.modifiers)), t => t.name);
  const hierItems = [];
  for (const t of baseTypes) { // rg only until enough types with a subtype are found
    if (hierItems.length >= n) break;
    if (rg(r.dir, '-n', '-g', '*.kt', '-e', hierarchyRe(t.name)).trim()) hierItems.push({ name: t.name, kind: t.kind, file: t.file });
  }

  const members = [];
  for (const f of files) {
    const lines = text.get(f).split('\n'); let container = null;
    lines.forEach((l, i) => {
      const tm = TYPE.exec(l); if (tm && !/^\s/.test(l)) container = tm[2];
      const m = MEMBER.exec(l);
      if (m && container && !/\bprivate\b/.test(l) && funCount.get(m[1]) === 1 && m[1].length >= 5 && typeCount.get(container) === 1) {
        const rc = refFiles(m[1], f);
        if (rc >= 2 && rc <= 30) members.push({ container, name: m[1], file: f, line: i });
      }
    });
  }
  const memberItems = order(members, m => `${m.container}.${m.name}`).slice(0, n).map(m => {
    const lines = text.get(m.file).split('\n');
    return { ...m, slice: declSlice(lines, m.line), fileLines: lines.length };
  });
  const outlineFiles = [...new Set(typeItems.map(t => t.file))].slice(0, n);
  const ktLines = sum(files.map(f => text.get(f).split('\n').length));
  const grepPatterns = GREP_CANDIDATES.filter(p => rg(r.dir, '-c', '-F', '-g', '*.kt', '-e', p).split('\n').reduce((a, l) => a + (+l.split(':').pop() || 0), 0) >= 30).slice(0, 4);
  return { typeItems, hierItems, memberItems, outlineFiles, grepPatterns, ktFiles: files.length, ktLines, hierarchyRe };
}

// ---------------------------------------------------------------- questions and baselines

const DECL_LINE = '^\\s*(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|override|suspend|inline|operator|const|lateinit|companion)\\s+)*(?:fun|class|interface|object|val|var|typealias)\\b';

/** Every question with the answer of the two baselines (deterministic) and the calls the tools get. */
function buildQuestions(r, q) {
  const out = [];
  const read = f => fs.readFileSync(path.join(r.dir, f), 'utf8');
  const add = (kind, id, minimal, typical, cl, gn) => out.push({ repo: r.name, kind, id, minimal, typical, cl, gn });
  const rgRun = (...a) => timed(() => rg(r.dir, ...a));
  const withRead = (hit, file) => { const t = timed(() => read(file), 3); return { text: hit.out + t.out, ms: hit.ms + t.ms }; };

  for (const t of q.typeItems) {
    const hit = rgRun('-n', '-g', '*.kt', '-w', '-e', `(class|interface|object|typealias) ${t.name}`);
    const whole = withRead(hit, t.file);
    add('declaration', t.name, { text: hit.out + t.slice, calls: 2, ms: hit.ms }, { text: whole.text, calls: 2, ms: whole.ms },
      { tool: 'symbol', args: { name: t.name } }, { tool: 'context', args: { name: t.name, include_content: true }, file: t.file });
  }
  for (const m of q.memberItems) {
    const hit = rgRun('-n', '-g', '*.kt', '-e', `fun\\s+(?:<[^>]+>\\s*)?(?:[\\w.<>?, ]+\\.)?${m.name}\\b`);
    const whole = withRead(hit, m.file);
    add('member', `${m.container}.${m.name}`, { text: hit.out + m.slice, calls: 2, ms: hit.ms }, { text: whole.text, calls: 2, ms: whole.ms },
      { tool: 'symbol', args: { name: `${m.container}.${m.name}` } }, { tool: 'context', args: { name: m.name, include_content: true }, file: m.file });
  }
  for (const f of q.outlineFiles) {
    const hit = timed(() => rg(r.dir, '-n', '-e', DECL_LINE, f));
    const whole = timed(() => read(f), 3);
    add('outline', f.split('/').pop(), { text: hit.out, calls: 1, ms: hit.ms }, { text: whole.out, calls: 1, ms: whole.ms },
      { tool: 'outline', args: { target: f } }, null);
  }
  for (const t of q.typeItems) {
    const lines = rgRun('-n', '-g', '*.kt', '-w', t.name);
    const ctx = rgRun('-n', '-g', '*.kt', '-w', '-C3', t.name);
    add('usages', t.name, { text: lines.out, calls: 1, ms: lines.ms }, { text: ctx.out, calls: 1, ms: ctx.ms },
      { tool: 'usages', args: { name: t.name } }, { tool: 'context', args: { name: t.name }, file: t.file });
  }
  for (const m of q.memberItems) {
    const lines = rgRun('-n', '-g', '*.kt', '-w', m.name);
    const ctx = rgRun('-n', '-g', '*.kt', '-w', '-C3', m.name);
    add('callers', `${m.container}.${m.name}`, { text: lines.out, calls: 1, ms: lines.ms }, { text: ctx.out, calls: 1, ms: ctx.ms },
      { tool: 'calls', args: { name: `${m.container}.${m.name}`, direction: 'callers', depth: 1 } }, { tool: 'context', args: { name: m.name }, file: m.file });
  }
  for (const h of q.hierItems) {
    const lines = rgRun('-n', '-g', '*.kt', '-e', q.hierarchyRe(h.name));
    const ctx = rgRun('-n', '-g', '*.kt', '-C3', '-e', q.hierarchyRe(h.name));
    add('hierarchy', h.name, { text: lines.out, calls: 1, ms: lines.ms }, { text: ctx.out, calls: 1, ms: ctx.ms },
      { tool: 'hierarchy', args: { name: h.name } }, { tool: 'context', args: { name: h.name }, file: h.file });
  }
  for (const p of q.grepPatterns) {
    const hit = timed(() => rg(r.dir, '-n', '-g', '*.kt', '-F', '-e', p).split('\n').slice(0, 30).join('\n'));
    add('grep', p, { text: hit.out, calls: 1, ms: hit.ms }, null, { tool: 'grep', args: { pattern: p, regex: false, limit: 30 } }, null);
  }
  const stat = timed(() => git(r.dir, 'diff', '--stat', r.base, r.tip), 1);
  const full = timed(() => git(r.dir, 'diff', r.base, r.tip), 1);
  add('changes', `${r.base.slice(0, 7)}..${r.tip.slice(0, 7)}`, { text: stat.out, calls: 1, ms: stat.ms }, { text: full.out, calls: 1, ms: full.ms },
    { tool: 'changes', args: {}, feature: true }, { tool: 'detect_changes', args: { scope: 'compare', base_ref: r.base }, tip: true });
  return out;
}

const answerOf = text => ({ chars: text.length, tokens: tok(text), lines: text.split('\n').length });

// ---------------------------------------------------------------- CodeLoupe

class CodeLoupe {
  constructor(cli, home, port) { this.cli = path.resolve(cli); this.home = home; this.port = port; this.base = `http://127.0.0.1:${port}`; this.id = 0; }
  env() { return { ...process.env, CODELOUPE_HOME: this.home, CODELOUPE_PORT: String(this.port) }; }
  run(...a) {
    const t = performance.now();
    const r = sh(this.cli, a, { env: this.env(), shell: IS_WIN, cwd: this.cwd });
    return { ms: performance.now() - t, status: r.status, out: (r.stdout || '') + (r.stderr || '') };
  }
  async status() { return (await fetch(`${this.base}/status`)).json(); }
  async mcp(method, params) {
    const res = await fetch(`${this.base}/mcp`, { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'x-codeloupe': '1' }, body: JSON.stringify({ jsonrpc: '2.0', id: ++this.id, method, params }) });
    const body = await res.text();
    const json = JSON.parse(body.startsWith('{') ? body : body.split('\n').find(l => l.startsWith('data:')).slice(5));
    if (json.error) throw new Error(`${method}: ${JSON.stringify(json.error)}`);
    return json.result;
  }
  /** One tool call as an agent's MCP client sees it; retried while the daemon reports `busy` (index build in progress). */
  async call(name, args, { waitBusy = true } = {}) {
    const t = performance.now();
    for (;;) {
      const res = await this.mcp('tools/call', { name, arguments: args });
      const text = (res.content ?? []).map(c => c.text).join('\n');
      if (waitBusy && /^busy/i.test(text) && performance.now() - t < 20 * 60_000) { await sleep(500); continue; }
      return { ms: performance.now() - t, text, isError: !!res.isError };
    }
  }
  async tools() { return (await this.mcp('tools/list', {})).tools; }
}

// ---------------------------------------------------------------- GitNexus

class GitNexus {
  constructor(work) {
    this.gn = path.join(work, 'gn');
    this.node = path.join(this.gn, 'node22', 'node_modules', 'node', 'bin', IS_WIN ? 'node.exe' : 'node');
    this.cli = path.join(this.gn, 'gitnexus', 'node_modules', GITNEXUS.package, 'dist', 'cli', 'index.js');
    this.home = path.join(this.gn, 'home');
    this.id = 0; this.waiting = new Map(); this.buf = '';
  }
  /** Nothing of the user's own GitNexus is touched: home, registry and caches live in <work>/gn/home. */
  env({ pool = true } = {}) {
    return { ...process.env, USERPROFILE: this.home, HOME: this.home, GITNEXUS_HOME: path.join(this.home, '.gitnexus'),
      ...(pool ? { GITNEXUS_LBUG_BUFFER_POOL_SIZE: String(GITNEXUS.bufferPool) } : {}), SCARF_ANALYTICS: 'false', GITNEXUS_NO_UPDATE_CHECK: '1' };
  }
  install() {
    const npm = IS_WIN ? 'npm.cmd' : 'npm';
    const sel = (dir, pkg) => {
      fs.mkdirSync(dir, { recursive: true });
      if (!fs.existsSync(path.join(dir, 'package.json'))) fs.writeFileSync(path.join(dir, 'package.json'), '{"name":"cl-bench-gn","version":"0.0.0","private":true}');
      const r = sh(npm, ['install', pkg, '--no-audit', '--no-fund', '--loglevel=error'], { cwd: dir, shell: IS_WIN, env: { ...process.env, SCARF_ANALYTICS: 'false' } });
      if (r.status !== 0) throw new Error(`npm install ${pkg}: ${r.stderr || r.stdout}`);
    };
    fs.mkdirSync(this.home, { recursive: true });
    if (!fs.existsSync(this.node)) { log('npm install node@' + GITNEXUS.node); sel(path.join(this.gn, 'node22'), `node@${GITNEXUS.node}`); }
    if (!fs.existsSync(this.cli)) { log(`npm install ${GITNEXUS.package}@${GITNEXUS.version}`); sel(path.join(this.gn, 'gitnexus'), `${GITNEXUS.package}@${GITNEXUS.version}`); }
    const v = JSON.parse(fs.readFileSync(path.join(path.dirname(path.dirname(path.dirname(this.cli))), 'package.json'), 'utf8'));
    if (v.version !== GITNEXUS.version) throw new Error(`gitnexus ${v.version} installed, ${GITNEXUS.version} expected`);
    return { version: v.version, license: v.license };
  }
  analyze(dir) {
    const child = spawn(this.node, [this.cli, 'analyze', dir], { env: this.env(), cwd: dir, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    let out = ''; child.stdout.on('data', d => { out += d; }); child.stderr.on('data', d => { out += d; });
    const done = new Promise(res => child.on('close', code => res({ code, out })));
    return { pid: child.pid, done };
  }
  cliCall(dir, ...a) {
    const t = performance.now();
    const r = sh(this.node, [this.cli, ...a], { cwd: dir, env: this.env() });
    return { ms: performance.now() - t, status: r.status, text: r.stdout || '' };
  }
  async start(cwd) {
    // serving needs no bulk load: the MCP server runs with the database's default buffer pool
    this.proc = spawn(this.node, [this.cli, 'mcp'], { env: this.env({ pool: false }), cwd, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    this.proc.stdout.on('data', d => {
      this.buf += d; let i;
      while ((i = this.buf.indexOf('\n')) >= 0) {
        const l = this.buf.slice(0, i); this.buf = this.buf.slice(i + 1);
        try { const m = JSON.parse(l); this.waiting.get(m.id)?.(m); } catch { /* log line */ }
      }
    });
    this.proc.stderr.on('data', () => {});
    this.pid = this.proc.pid;
    await this.rpc('initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'codeloupe-benchmark', version: '1' } });
    this.proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
  }
  rpc(method, params, timeoutMs = 120_000) {
    return new Promise((res, rej) => {
      const i = ++this.id; const timer = setTimeout(() => { this.waiting.delete(i); rej(new Error(`${method} timed out`)); }, timeoutMs);
      this.waiting.set(i, m => { clearTimeout(timer); this.waiting.delete(i); res(m); });
      this.proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: i, method, params }) + '\n');
    });
  }
  async tools() { return (await this.rpc('tools/list', {})).result.tools; }
  async call(name, args) {
    const t = performance.now();
    const m = await this.rpc('tools/call', { name, arguments: args });
    const text = (m.result?.content ?? []).map(c => c.text).join('\n') || JSON.stringify(m.error ?? '');
    return { ms: performance.now() - t, text, isError: !!(m.result?.isError || m.error || /^Error/.test(text)) };
  }
  stop() { try { this.proc?.kill(); } catch { /* gone */ } }
}

// ---------------------------------------------------------------- run

async function repeat(n, f) {
  const ms = []; let last;
  for (let i = 0; i < n; i++) { last = await f(); ms.push(last.ms); }
  return { ...last, ms: median(ms) };
}
const answered = r => !r.isError && r.text.trim().length > 0 && !/not found|no match|ambiguous/i.test(r.text.slice(0, 200));

async function main() {
  if (args['report-only']) { const d = JSON.parse(fs.readFileSync(args['report-only'], 'utf8')); writeReports(d); return; }
  const work = path.resolve(args.work || path.join(os.tmpdir(), 'codeloupe-bench'));
  const port = Number(args.port || 47651);
  const N = Number(args.items || 8);
  if (port === 47391) throw new Error('refusing the default port: the benchmark starts its own daemon');
  const cliPath = args.cli || path.join(ROOT, 'build', 'install', 'codeloupe', 'bin', IS_WIN ? 'codeloupe.bat' : 'codeloupe');
  if (!fs.existsSync(cliPath)) throw new Error(`no CodeLoupe launcher at ${cliPath}: run ./gradlew installDist or pass --cli`);
  const rgv = sh(RG, ['--version'], { env: { ...process.env, ARGV0: 'rg' } });
  if (rgv.error || rgv.status !== 0) throw new Error('ripgrep not found: install it or pass --rg <binary>');
  fs.mkdirSync(work, { recursive: true });

  const cl = new CodeLoupe(cliPath, path.join(work, 'codeloupe-home'), port);
  if (fs.existsSync(cl.home)) fs.rmSync(cl.home, { recursive: true, force: true });
  try { await fetch(`${cl.base}/status`); throw new Error(`something already listens on port ${port}`); } catch (e) { if (/already listens/.test(e.message)) throw e; }

  const repos = REPOS.map(r => setupRepo(r, work));
  const meta = {
    date: new Date().toISOString().slice(0, 10),
    machine: { os: `${os.type()} ${os.release()}`, cpu: os.cpus()[0].model.trim(), cores: os.cpus().length, ramGb: Math.round(os.totalmem() / 1024 ** 3), node: process.version },
    versions: { ripgrep: rgv.stdout.split('\n')[0], git: sh('git', ['--version']).stdout.trim() },
    codeloupe: { sha: git(ROOT, 'rev-parse', 'HEAD').trim(), dirty: git(ROOT, 'status', '--porcelain').trim().length > 0 },
    baselines: BASELINES, charsPerToken: CHARS_PER_TOKEN, itemsPerQuestion: N,
    repos: [], gitnexusSetup: null,
  };

  let gn = null; const notMeasured = [];
  if (!args['no-gitnexus']) {
    try { gn = new GitNexus(work); meta.gitnexusSetup = { ...gn.install(), nodeForGitNexus: GITNEXUS.node, bufferPoolBytes: GITNEXUS.bufferPool }; }
    catch (e) { notMeasured.push(`GitNexus: not measured, setup failed: ${String(e.message).slice(0, 300)}`); gn = null; }
  } else notMeasured.push('GitNexus: not measured (--no-gitnexus)');

  // questions and baselines
  const all = [];
  for (const r of repos) {
    log('questions', r.name);
    const q = pickQuestions(r, N);
    meta.repos.push({ name: r.name, url: r.url.replace(/\.git$/, ''), license: r.license, base: r.base, tip: r.tip, ktFiles: q.ktFiles, ktLines: q.ktLines, grepPatterns: q.grepPatterns });
    all.push(...buildQuestions(r, q));
  }

  // CodeLoupe
  log('start CodeLoupe on', port);
  const resources = { codeloupe: { perRepo: [] }, gitnexus: { perRepo: [] } };
  const started = cl.run('start');
  if (started.status !== 0) throw new Error(`codeloupe start: ${started.out}`);
  const st0 = await cl.status();
  meta.codeloupe.version = st0.version; meta.codeloupe.home = 'throwaway (--work)'; meta.codeloupe.port = port;
  resources.codeloupe.startMs = Math.round(started.ms); resources.codeloupe.rssAfterStartMb = st0.rssMb;
  const clTools = await cl.tools();
  const results = [];
  try {
    for (const r of repos) {
      const probe = all.find(x => x.repo === r.name && x.kind === 'declaration');
      log('CodeLoupe first index', r.name);
      const { value, peakRssKb } = await withPeakRss(() => st0.pid, () => cl.call('find', { root: r.dir, q: probe.id }));
      const home0 = dirSize(cl.home);
      resources.codeloupe.perRepo.push({ repo: r.name, firstIndexMs: Math.round(value.ms), peakRssMb: mb(peakRssKb), homeBytes: home0, repoWrites: repoWrites(r.dir) });
    }
    for (const r of repos) {
      log('CodeLoupe feature worktree, first changes', r.name);
      const t = await cl.call('changes', { root: r.feature });
      resources.codeloupe.perRepo.find(p => p.repo === r.name).firstChangesMs = Math.round(t.ms);
    }
    for (const x of all) {
      const r = repos.find(y => y.name === x.repo);
      const root = x.cl.feature ? r.feature : r.dir;
      const res = await repeat(x.cl.feature ? 3 : 5, () => cl.call(x.cl.tool, { root, ...x.cl.args }));
      x.clRes = { ...answerOf(res.text), calls: 1, ms: res.ms, answered: answered(res), why: answered(res) ? undefined : res.text.slice(0, 120).replace(/\s+/g, ' '), exact: Number(/(\d+) exact/.exec(res.text)?.[1] ?? NaN), candidate: Number(/(\d+) candidate/.exec(res.text)?.[1] ?? NaN) };
    }
    // a CLI process per call (JVM client start included)
    const cliProbe = [];
    for (const r of repos) {
      cl.cwd = r.dir;
      for (const x of all.filter(y => y.repo === r.name && y.kind === 'member').slice(0, 3)) cliProbe.push(cl.run('symbol', x.cl.args.name).ms);
    }
    cl.cwd = undefined;
    resources.codeloupe.cliCallMs = Math.round(median(cliProbe));
    // idle
    await sleep(5000);
    const idleA = await cl.status(); const snapA = await snapshot();
    await sleep(30_000);
    const idleB = await cl.status(); const snapB = await snapshot();
    resources.codeloupe.idleRssMb = idleB.rssMb;
    resources.codeloupe.idleTreeRssMb = mb(treeRss(snapB, idleB.pid));
    resources.codeloupe.idleCpuMsPer30s = Math.round((idleB.cpuSec - idleA.cpuSec) * 1000);
    resources.codeloupe.idleCpuTreeMsPer30s = Math.round(treeCpu(snapB, idleB.pid) - treeCpu(snapA, idleA.pid));
    resources.codeloupe.homeBytes = dirSize(cl.home);
    resources.codeloupe.heapMb = idleB.heapMb;
  } finally {
    const stop = cl.run('stop');
    if (stop.status !== 0) log('codeloupe stop:', stop.out.trim());
  }

  // GitNexus
  const gnTools = { count: null };
  if (gn) {
    try {
      for (const r of repos) {
        log('GitNexus analyze', r.name);
        const a = gn.analyze(r.gn);
        const t0 = performance.now();
        const { value, peakRssKb } = await withPeakRss(() => a.pid, () => a.done);
        if (value.code !== 0) throw new Error(`analyze ${r.name} exit ${value.code}: ${value.out.slice(-400)}`);
        const nodes = /([\d\s]+) nodes \| ([\d\s]+) edges/.exec(value.out.replace(/ /g, ' '));
        resources.gitnexus.perRepo.push({ repo: r.name, firstIndexMs: Math.round(performance.now() - t0), peakRssMb: mb(peakRssKb), repoWrites: repoWrites(r.gn), toolReportedMs: Math.round(1000 * Number(/indexed successfully \(([\d.]+)s\)/.exec(value.out)?.[1] ?? NaN)), homeBytes: dirSize(path.join(r.gn, '.gitnexus')), graph: nodes ? `${nodes[1].replace(/\s/g, '')} nodes, ${nodes[2].replace(/\s/g, '')} edges` : null });
      }
      await gn.start(repos[0].gn);
      const tl = await gn.tools();
      Object.assign(gnTools, { count: tl.length, list: tl });
      const names = JSON.parse((await gn.call('list_repos', {})).text.split('\n---')[0]).repositories;
      resources.gitnexus.rssAfterStartMb = mb(treeRss(await snapshot(), gn.pid));
      const nameOf = r => names.find(n => path.resolve(n.path).toLowerCase() === path.resolve(r.gn).toLowerCase())?.name;
      for (const x of all) {
        if (!x.gn) { x.gnRes = null; continue; }
        const r = repos.find(y => y.name === x.repo);
        const repoName = nameOf(r);
        if (x.gn.tip) { git(r.gn, 'checkout', '-q', '--detach', r.tip); }
        // an ambiguous name is asked again with the file, as an agent would; both answers and both calls are counted
        const res = await repeat(3, async () => {
          const a = await gn.call(x.gn.tool, { repo: repoName, ...x.gn.args });
          if (!/"status": "ambiguous"/.test(a.text) || !x.gn.file) return { ...a, calls: 1, last: a };
          const b = await gn.call(x.gn.tool, { repo: repoName, ...x.gn.args, file_path: x.gn.file });
          return { ms: a.ms + b.ms, text: `${a.text}\n${b.text}`, isError: b.isError, calls: 2, last: b };
        });
        if (x.gn.tip) { git(r.gn, 'checkout', '-q', '--detach', r.base); }
        const ok = answered(res.last);
        x.gnRes = { ...answerOf(res.text), calls: res.calls, ms: res.ms, answered: ok, why: ok ? undefined : res.last.text.slice(0, 120).replace(/\s+/g, ' ') };
      }
      resources.gitnexus.rssAfterQueriesMb = mb(treeRss(await snapshot(), gn.pid));
      const sA = await snapshot(); await sleep(30_000); const sB = await snapshot();
      resources.gitnexus.idleTreeRssMb = mb(treeRss(sB, gn.pid));
      resources.gitnexus.idleCpuMsPer30s = Math.round(treeCpu(sB, gn.pid) - treeCpu(sA, gn.pid));
      gn.stop();
      const cliProbe = [];
      for (const x of all.filter(y => y.repo === repos[0].name && y.kind === 'member').slice(0, 2)) cliProbe.push(gn.cliCall(repos[0].gn, 'context', x.gn.args.name).ms);
      resources.gitnexus.cliCallMs = Math.round(median(cliProbe));
      resources.gitnexus.homeBytes = dirSize(gn.home);
    } catch (e) {
      gn.stop();
      notMeasured.push(`GitNexus: not measured completely, ${String(e.message).slice(0, 400)}`);
      for (const x of all) x.gnRes ??= null;
    }
  }

  // assemble
  for (const x of all) {
    const m = o => ({ ...answerOf(o.text), calls: o.calls, ms: Math.round(o.ms * 10) / 10 });
    const lineHits = t => t.split('\n').filter(l => /^\S.*:\d+:/.test(l)).length;
    results.push({
      repo: x.repo, kind: x.kind, id: x.id,
      minimal: { ...m(x.minimal), hits: lineHits(x.minimal.text) },
      typical: x.typical ? m(x.typical) : null,
      codeloupe: { ...x.clRes, ms: Math.round(x.clRes.ms * 10) / 10 },
      gitnexus: x.gnRes ? { ...x.gnRes, ms: Math.round(x.gnRes.ms * 10) / 10 } : null,
    });
  }
  const toolInfo = list => ({ count: list.length, chars: JSON.stringify(list).length, tokens: tok(JSON.stringify(list)), perTool: list.map(t => ({ name: t.name, chars: JSON.stringify(t).length })) });
  const data = {
    meta, results, resources, notMeasured,
    tools: { codeloupe: toolInfo(clTools), gitnexus: gnTools.list ? toolInfo(gnTools.list) : null },
  };
  const jsonOut = args.json === true || args.json === undefined ? path.join(ROOT, 'docs', 'benchmarks.json') : path.resolve(args.json);
  fs.writeFileSync(jsonOut, JSON.stringify(data, null, 1) + '\n');
  writeReports(data);
  log('done');
}

// ---------------------------------------------------------------- report

const KINDS = [
  ['declaration', 'Source of a type'], ['member', 'Source of a member'], ['outline', 'Outline of a file'], ['usages', 'Usages of a type'],
  ['callers', 'Callers of a member'], ['hierarchy', 'Subtypes of a type'], ['grep', 'Text search, 30 hits'], ['changes', 'What a branch changed'],
];
const num = n => n == null || Number.isNaN(n) ? 'n/a' : Math.round(n).toLocaleString('en-US');
const pct = (a, b) => a == null || b == null || !b ? 'n/a' : `${Math.round(100 * a / b)} %`;
const secs = ms => ms == null ? 'n/a' : `${(ms / 1000).toFixed(1)} s`;
const mbOf = bytes => bytes == null ? 'n/a' : `${Math.round(bytes / 1024 / 1024)} MB`;

/** Pooled medians for one question kind (optionally one repository). */
function pooled(results, kind, repo) {
  const rows = results.filter(r => r.kind === kind && (!repo || r.repo === repo));
  const med = f => median(rows.map(f).filter(v => v != null));
  const gnRows = rows.filter(r => r.gitnexus);
  return {
    n: rows.length,
    minimal: med(r => r.minimal.tokens), typical: med(r => r.typical?.tokens), codeloupe: med(r => r.codeloupe.tokens),
    gitnexus: gnRows.length ? median(gnRows.map(r => r.gitnexus.tokens)) : null,
    gnAnswered: gnRows.length ? `${gnRows.filter(r => r.gitnexus.answered).length}/${gnRows.length}` : null,
    clAnswered: `${rows.filter(r => r.codeloupe.answered).length}/${rows.length}`,
    msMinimal: med(r => r.minimal.ms), msCl: med(r => r.codeloupe.ms), msGn: gnRows.length ? median(gnRows.map(r => r.gitnexus.ms)) : null,
    clBeatsMinimal: rows.filter(r => r.codeloupe.tokens < r.minimal.tokens).length,
    clBeatsTypical: rows.filter(r => r.typical && r.codeloupe.tokens < r.typical.tokens).length,
    typicalRows: rows.filter(r => r.typical).length,
    callsGitnexus: gnRows.length ? median(gnRows.map(r => r.gitnexus.calls)) : null, callsMinimal: median(rows.map(r => r.minimal.calls)), callsTypical: rows[0]?.typical ? median(rows.map(r => r.typical.calls)) : null,
  };
}

function renderMarkdown(d) {
  const { meta, results, resources, tools } = d;
  const L = [];
  const repoName = n => meta.repos.find(r => r.name === n);
  L.push('# Benchmarks', '',
    '<!-- Generated by tools/benchmark.mjs; do not edit by hand. -->',
    `Run on ${meta.date}. Reproduce with \`./gradlew installDist\` and \`node tools/benchmark.mjs --work <scratch dir>\` (needs git, ripgrep, Node 20+, JDK 25 for the build, network for the clones and for the GitNexus npm package; about 15 minutes).`,
    'Every number below was produced by that script; [benchmarks.json](benchmarks.json) holds the raw rows.', '');
  L.push('## Setup', '',
    `- **Machine**: ${meta.machine.os}, ${meta.machine.cpu} (${meta.machine.cores} threads), ${meta.machine.ramGb} GB RAM, Node ${meta.machine.node}; ${meta.versions.ripgrep}; ${meta.versions.git}.`,
    `- **CodeLoupe**: ${meta.codeloupe.version ?? '?'} built from commit \`${meta.codeloupe.sha.slice(0, 10)}\`${meta.codeloupe.dirty ? ' (working tree had uncommitted changes)' : ''}; a fresh daemon on its own port with a throwaway \`CODELOUPE_HOME\`.`,
    meta.gitnexusSetup ? `- **GitNexus**: npm package \`gitnexus@${meta.gitnexusSetup.version}\` (${meta.gitnexusSetup.license}), run with Node ${meta.gitnexusSetup.nodeForGitNexus} (it requires Node 22 or newer), its home redirected into the scratch directory, MCP over stdio. \`GITNEXUS_LBUG_BUFFER_POOL_SIZE\` set to ${meta.gitnexusSetup.bufferPoolBytes / 1024 ** 3} GiB for \`analyze\` (the MCP server runs with the default): with the default pool (428 MiB here) the first \`analyze\` of the Exposed checkout failed with "Buffer manager exception ... buffer pool is full" and its own message names this setting.` : '- **GitNexus**: not measured, see the end of this page.',
    '', '| Repository | Commit indexed (`main`) | Branch tip for "what changed" | Kotlin files / lines (without tests) | Licence |', '|---|---|---|---:|---|',
    ...meta.repos.map(r => `| [${r.name}](${r.url}) | \`${r.base.slice(0, 10)}\` | \`${r.tip.slice(0, 10)}\` | ${num(r.ktFiles)} / ${num(r.ktLines)} | ${r.license} |`), '');
  L.push('## Method', '',
    `Questions are picked from the sources by a fixed rule, without asking any tool: Kotlin declarations whose name is unique in the repository and referenced from 3 to 30 other files (types) or 2 to 30 (members), ordered by the SHA-1 of \`repository:name\`, the first ${meta.itemsPerQuestion} of each kind. Subtype questions use interfaces and abstract, open or sealed classes with at least one subtype that a regular expression finds. Unique names favour the grep baseline (no name clash to sort out). The text search asks for literal strings with at least 30 hits in the repository, 30 hits each (${meta.repos.map(r => `${r.name}: ${r.grepPatterns.map(p => `\`${p}\``).join(', ')}`).join('; ')}).`, '',
    `An answer is what the agent reads: characters of the tool output, divided by ${meta.charsPerToken} for tokens (the characters-per-token ratio measured on Claude Code transcripts in [context-audit.md](context-audit.md); the constant is the same for every mode, so ratios do not depend on it). Tool-call overhead and line-number prefixes are not counted for any mode.`, '',
    `- **grep, minimal**: ${meta.baselines.minimal}. A lower bound for an agent that greps well; it assumes the agent never reads a line it does not need.`,
    `- **grep + read**: ${meta.baselines.typical}. What an agent without an index does when it wants the surrounding code.`,
    '- **CodeLoupe**: the MCP `tools/call` over HTTP against a running daemon, the answer exactly as the client gets it.',
    '- **GitNexus**: the MCP tool a user would call for the question (`context` for source, usages, callers and subtypes; `detect_changes` for the branch), over stdio. The released `gitnexus@' + (meta.gitnexusSetup?.version ?? GITNEXUS.version) + '` serves no outline, text-search or per-file tool in its `tools/list` (the README on its `main` branch lists `read_file` and `grep` among 19 tools; they are not in the released package, and the 1.6.13 release candidate was not tried), so those rows are `n/a`. Its `context` returns a symbol card (callers, callees, processes) and not always the code; where the answer differs in kind from the others, the token count is not a like-for-like comparison.',
    '- Answers differ in content, not only in size: grep returns every textual match including comments and strings, CodeLoupe returns resolved references grouped by enclosing declaration. Smaller is cheaper to read but not automatically better; the table says how many answers a tool gave at all.', '');

  L.push('## Tokens read per question', '', 'Median over the questions of each kind, both repositories pooled. Lower is better. "answered" counts questions the tool returned a non-empty, non-error answer for.', '',
    '| Question | n | grep, minimal | grep + read | CodeLoupe | GitNexus | CodeLoupe vs minimal | CodeLoupe vs grep + read | calls (minimal / read / CodeLoupe / GitNexus) |', '|---|---:|---:|---:|---:|---:|---:|---:|---|');
  for (const [k, label] of KINDS) {
    const p = pooled(results, k);
    L.push(`| ${label} | ${p.n} | ${num(p.minimal)} | ${p.typical == null ? 'n/a' : num(p.typical)} | ${num(p.codeloupe)}${p.clAnswered === `${p.n}/${p.n}` ? '' : ` (${p.clAnswered} answered)`} | ${p.gitnexus == null ? 'n/a' : `${num(p.gitnexus)} (${p.gnAnswered} answered)`} | ${pct(p.codeloupe, p.minimal)} | ${pct(p.codeloupe, p.typical)} | ${p.callsMinimal} / ${p.callsTypical ?? 'n/a'} / 1 / ${p.callsGitnexus ?? 'n/a'} |`);
  }
  const tot = f => sum(results.filter(f).map(r => r.codeloupe.tokens));
  const totalMin = sum(results.map(r => r.minimal.tokens)), totalTyp = sum(results.filter(r => r.typical).map(r => r.typical.tokens));
  L.push('', `Sum over all ${results.length} questions: grep minimal ${num(totalMin)} tokens, CodeLoupe ${num(tot(() => true))} (${pct(tot(() => true), totalMin)}); over the ${results.filter(r => r.typical).length} questions that have a grep + read variant: grep + read ${num(totalTyp)}, CodeLoupe ${num(tot(r => r.typical))} (${pct(tot(r => r.typical), totalTyp)}).`, '');

  L.push('### By repository', '', '| Question | Repository | n | grep, minimal | grep + read | CodeLoupe | GitNexus |', '|---|---|---:|---:|---:|---:|---:|');
  for (const [k, label] of KINDS) for (const r of meta.repos) {
    const p = pooled(results, k, r.name);
    L.push(`| ${label} | ${r.name} | ${p.n} | ${num(p.minimal)} | ${p.typical == null ? 'n/a' : num(p.typical)} | ${num(p.codeloupe)} | ${p.gitnexus == null ? 'n/a' : num(p.gitnexus)} |`);
  }
  const us = results.filter(r => r.kind === 'usages');
  L.push('', '### Usages: lines against references', '',
    'rg counts every line that holds the word: the declaration, imports, KDoc links, string literals and unrelated symbols of the same name. CodeLoupe counts resolved references (exact plus candidate). Fewer references than lines is expected; the table is here so that a smaller answer is not mistaken for a more complete one.', '',
    '| Repository | rg lines, median | CodeLoupe references, median | CodeLoupe references / rg lines, median |', '|---|---:|---:|---:|');
  for (const r of [...meta.repos.map(x => x.name), null]) {
    const rows = us.filter(x => (!r || x.repo === r) && !Number.isNaN(x.codeloupe.exact));
    L.push(`| ${r ?? 'both'} | ${num(median(rows.map(x => x.minimal.hits)))} | ${num(median(rows.map(x => x.codeloupe.exact + (x.codeloupe.candidate || 0))))} | ${(median(rows.map(x => (x.codeloupe.exact + (x.codeloupe.candidate || 0)) / Math.max(1, x.minimal.hits)))).toFixed(2)} |`);
  }
  L.push('', 'Checked by hand on two items of the Exposed checkout at the commit above (`rg -n -w -g "*.kt" SCryptHasher` and `IntVectorColumnType`): the 5 lines for `SCryptHasher` are one call, the declaration, a string literal and two KDoc links (CodeLoupe: 1 reference); the 6 lines for `IntVectorColumnType` are three calls, one `is` check, the declaration and an import (CodeLoupe: 4).', '', '![Median tokens per question](benchmarks.svg)', '');

  L.push('### CodeLoupe against minimal grep, question by question', '', 'Minimal grep is a best case for grep (the agent never reads a line it does not need). Medians within 10 % of each other count as about the same.', '');
  const verdicts = [];
  for (const [k, label] of KINDS) {
    const p = pooled(results, k); const ratio = p.codeloupe / p.minimal;
    const word = ratio > 1.1 ? '**larger**' : ratio < 0.9 ? '**smaller**' : 'about the same';
    verdicts.push(`- ${label}: CodeLoupe's median answer is ${word} (${num(p.codeloupe)} against ${num(p.minimal)} tokens); smaller than minimal grep in ${p.clBeatsMinimal} of ${p.n} answers${p.typicalRows ? `, smaller than grep + read in ${p.clBeatsTypical} of ${p.typicalRows}` : ''}.`);
  }
  L.push(...verdicts, '');
  L.push('Where the CodeLoupe answer is larger it carries more than the grep lines: usages and callers name the enclosing declaration of every hit and mark exact against candidate references, `changes` lists the changed declarations with their callers and tests where `git diff --stat` lists files, subtypes include supertypes and transitive links.', '');
  const gnWins = KINDS.map(([k, label]) => [label, pooled(results, k)]).filter(([, p]) => p.gitnexus != null && p.gitnexus < p.codeloupe);
  if (gnWins.length) L.push('GitNexus returned a smaller median answer than CodeLoupe for: ' + gnWins.map(([l, p]) => `${l.toLowerCase()} (${num(p.gitnexus)} against ${num(p.codeloupe)} tokens)`).join('; ') + '.', '');

  L.push('## Latency', '', 'Warm calls, median over the questions of each kind (CodeLoupe: median of 5 calls over MCP/HTTP after the index exists; GitNexus: median of 3 calls over MCP/stdio; grep: median of 3 runs of the command, plus the file read where the variant reads files).', '',
    '| Question | grep, minimal | CodeLoupe | GitNexus |', '|---|---:|---:|---:|');
  for (const [k, label] of KINDS) { const p = pooled(results, k); L.push(`| ${label} | ${num(p.msMinimal)} ms | ${num(p.msCl)} ms | ${p.msGn == null ? 'n/a' : `${num(p.msGn)} ms`} |`); }
  L.push('', `A CodeLoupe CLI call (a new process that talks to the running daemon): ${num(resources.codeloupe.cliCallMs)} ms median. A GitNexus CLI call (\`gitnexus context\`, a new Node process that opens the database): ${resources.gitnexus.cliCallMs == null ? 'n/a' : `${num(resources.gitnexus.cliCallMs)} ms median`}. Agents normally use MCP, where the process is already running.`, '');

  L.push('## Tool definitions in the agent\'s context', '', `What an MCP client holds in context for each connected server: the JSON of \`tools/list\` (${meta.charsPerToken} characters per token).`, '',
    '| | Tools | Characters | Tokens | Largest tool |', '|---|---:|---:|---:|---|');
  for (const [name, t] of [['CodeLoupe', tools.codeloupe], ['GitNexus', tools.gitnexus]]) {
    if (!t) { L.push(`| ${name} | not measured | | | |`); continue; }
    const big = [...t.perTool].sort((a, b) => b.chars - a.chars)[0];
    L.push(`| ${name} | ${t.count} | ${num(t.chars)} | ${num(t.tokens)} | \`${big.name}\` (${num(big.chars)} characters) |`);
  }
  L.push('', 'CodeLoupe serves more tools when a tracker is configured (`issue`, `tasks`, `update`, …); the figure is for a daemon without one. IDE-based MCP servers cannot be started by a script (they need a running IDE with the project open): not measured here; [context-audit.md](context-audit.md) has a one-off measurement of one such server (25 tools, about 12.4k tokens).', '',
    '<details><summary>Per tool (characters of the tool definition)</summary>', '');
  for (const [name, t] of [['CodeLoupe', tools.codeloupe], ['GitNexus', tools.gitnexus]]) if (t) L.push(`**${name}**: ${t.perTool.map(x => `${x.name} ${num(x.chars)}`).join(', ')}`, '');
  L.push('</details>', '');

  L.push('## Resources', '', 'One CodeLoupe daemon served both repositories; GitNexus indexed each repository with `gitnexus analyze` and then ran one MCP server process. Peak memory is the working set of the process tree sampled about every 0.5 s (a lower bound of the real peak); idle figures are 5 s after the last query.', '',
    '| | CodeLoupe | GitNexus |', '|---|---|---|');
  const rc = resources.codeloupe, rg_ = resources.gitnexus;
  for (const r of meta.repos) {
    const a = rc.perRepo.find(x => x.repo === r.name), b = rg_.perRepo.find(x => x.repo === r.name);
    L.push(`| First index, ${r.name} | ${a ? `${secs(a.firstIndexMs)} (first query, index built by a child JVM)` : 'n/a'} | ${b ? `${secs(b.firstIndexMs)} (\`analyze\`${b.toolReportedMs ? `, the tool reports ${secs(b.toolReportedMs)}` : ''}${b.graph ? `; ${b.graph}` : ''})` : 'not measured'} |`);
    L.push(`| Peak memory while indexing, ${r.name} | ${a ? `${a.peakRssMb} MB (daemon + child JVM)` : 'n/a'} | ${b ? `${b.peakRssMb} MB` : 'not measured'} |`);
  }
  L.push(`| First \`changes\` in a new worktree of each repository | ${rc.perRepo.map(p => `${p.repo} ${secs(p.firstChangesMs)}`).join(', ')} | not measured (GitNexus's README describes worktrees sharing one store, with a copy updated incrementally for uncommitted changes; this script does not exercise it) |`);
  L.push(`| Resident memory, idle | ${rc.idleRssMb} MB daemon (${rc.idleTreeRssMb} MB with children) | ${rg_.idleTreeRssMb == null ? 'not measured' : `${rg_.idleTreeRssMb} MB MCP server after ${results.filter(r => r.gitnexus).length} queries, default buffer pool (${rg_.rssAfterStartMb ?? 'n/a'} MB right after it started; one Node process, the working set grows with the database pages it touches)`} |`);
  L.push(`| CPU while idle, 30 s | ${rc.idleCpuMsPer30s} ms | ${rg_.idleCpuMsPer30s == null ? 'not measured' : `${rg_.idleCpuMsPer30s} ms`} |`);
  L.push(`| Files written into the repository checkout by indexing (GitNexus: by default, \`--skip-agents-md\` and \`--skip-skills\` turn it off) |${rc.perRepo.map(p => `${p.repoWrites.files} (${p.repo})`).join(', ')} | ${rg_.perRepo.length ? rg_.perRepo.map(p => `${p.repoWrites.files} (${p.repo}: ${p.repoWrites.top.join(', ')})`).join(', ') : 'not measured'} |`);
  L.push(`| Index on disk | ${mbOf(rc.homeBytes)} (both repositories, whole home) | ${rg_.perRepo.length ? rg_.perRepo.map(p => `${p.repo} ${mbOf(p.homeBytes)}`).join(', ') : 'not measured'} |`);
  L.push(`| Daemon / server start | ${num(rc.startMs)} ms (\`codeloupe start\`) | the MCP server starts with the client |`, '');
  const missed = [];
  for (const r of results) for (const t of ['codeloupe', 'gitnexus']) if (r[t] && !r[t].answered) missed.push(`- ${t === 'codeloupe' ? 'CodeLoupe' : 'GitNexus'}, ${r.repo}, ${r.kind} \`${r.id}\`: ${r[t].why || 'empty answer'}`);
  L.push('## Questions a tool did not answer', '', missed.length ? 'An answer counts as missing when it is empty, an error, or says the symbol was not found or is ambiguous. The text is the start of what the tool returned.' : 'Every tool answered every question it has a tool for.', '', ...missed, '');
  L.push('## Not measured', '', ...d.notMeasured.map(x => `- ${x}`), '- IDE-based MCP servers (JetBrains): need a running IDE with the project open, not startable by a script.', '- Worktree handling of GitNexus, and GitNexus 1.6.13 release candidates or newer `main`.', '- Answer quality: the table counts what is read, not whether the agent finishes the task; the controlled agent benchmark of [plan.md](plan.md) § 8.4 has not been run.', '');
  return L.join('\n');
}

function renderSvg(d) {
  const series = [['minimal', 'grep, minimal', '#8b949e'], ['typical', 'grep + read', '#d4a72c'], ['codeloupe', 'CodeLoupe', '#0969da'], ['gitnexus', 'GitNexus', '#8250df']];
  const W = 860, rowH = 74, top = 54, left = 190, right = 70, plotW = W - left - right;
  const H = top + KINDS.length * rowH + 20;
  const maxV = 100000, minV = 10;
  const x = v => left + plotW * (Math.log10(Math.max(v, minV)) - 1) / (Math.log10(maxV) - 1);
  const e = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');
  const out = [`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-family="Segoe UI, Helvetica, Arial, sans-serif" font-size="12">`,
    `<title>Median tokens read per question</title><rect width="${W}" height="${H}" rx="8" fill="#ffffff" stroke="#d0d7de"/>`,
    `<text x="16" y="24" font-size="15" font-weight="600" fill="#1f2328">Median tokens read per question (log scale, lower is better)</text>`];
  series.forEach(([, label, c], i) => out.push(`<rect x="${left + i * 130}" y="34" width="10" height="10" fill="${c}"/><text x="${left + i * 130 + 14}" y="43" fill="#1f2328">${e(label)}</text>`));
  for (const t of [10, 100, 1000, 10000, 100000]) out.push(`<line x1="${x(t)}" y1="${top}" x2="${x(t)}" y2="${H - 20}" stroke="#d8dee4"/><text x="${x(t)}" y="${H - 6}" text-anchor="middle" fill="#57606a">${num(t)}</text>`);
  KINDS.forEach(([k, label], r) => {
    const p = pooled(d.results, k); const y0 = top + r * rowH;
    out.push(`<text x="${left - 10}" y="${y0 + 30}" text-anchor="end" fill="#1f2328" font-weight="600">${e(label)}</text>`);
    series.forEach(([key], i) => {
      const v = p[key]; const y = y0 + 4 + i * 16;
      if (v == null) { out.push(`<text x="${left + 4}" y="${y + 10}" fill="#8c959f" font-size="11">n/a</text>`); return; }
      out.push(`<rect x="${left}" y="${y}" width="${Math.max(2, x(v) - left)}" height="12" fill="${series[i][2]}"/><text x="${x(v) + 5}" y="${y + 10}" fill="#1f2328" font-size="11">${num(v)}</text>`);
    });
  });
  out.push('</svg>');
  return out.join('\n') + '\n';
}

function writeReports(d) {
  const md = path.resolve(args.out && args.out !== true ? args.out : path.join(ROOT, 'docs', 'benchmarks.md'));
  const svg = path.resolve(args.svg && args.svg !== true ? args.svg : path.join(ROOT, 'docs', 'benchmarks.svg'));
  fs.writeFileSync(md, renderMarkdown(d));
  fs.writeFileSync(svg, renderSvg(d));
  log('wrote', md, svg);
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
  main().catch(e => { console.error(e); process.exit(1); });
}
