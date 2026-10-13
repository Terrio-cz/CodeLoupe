#!/usr/bin/env node
// Query profile of the daemon (CL-96): client latency of a warm query, the first query in a worktree and an overlay
// refresh, split by the daemon's own timings (/status `timings`, `gitSpawns`) into git, worktree walk, SQL, the rest of
// the tool (resolving and formatting) and HTTP (client time minus tool time). Parts can overlap: a query reads while
// its worktree is checked. Starts its own daemon in --home on --port and stops it at the end.
//
//   node tools/profile.mjs --cli build/install/codeloupe/bin/codeloupe.bat --home <tmp> --port 47471 \
//     --root <repo> --worktree <linked worktree> [--clone <scratch clone to edit>] [--n 60] [--edits 5] [--warmup 2] [--only edit] [--out profile.json]
//
// --only edit runs just the overlay-refresh rows (needs --clone). --root and --worktree are only read. --clone gets a line appended to one of its .kt files and restored afterwards.
//
//   node tools/profile.mjs --only tools --cli ... --home <tmp> --port 47471 --clone <scratch clone> --tasks TER-193,TER-638 [--budget 1000] [--settle 20000]
//
// --only tools (CL-246) profiles the calls that start a plan and a review: `task_context` as the first call in a new worktree of
// --clone for each --tasks id (branch profile-<id> at the default branch, made --settle ms earlier, as `git start` does before an
// agent starts), then `changes` there after the worktree moved to the task's branch origin/<id> (the coder's commits) and once
// more unchanged. Exits 2 when the p95 of task_context or of the unchanged `changes` is over --budget ms. The worktrees and
// branches are removed at the end.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync, execFileSync } from 'node:child_process';
import { daemonTokenHeader } from './daemon-token.mjs';

const args = {}; for (let i = 2; i < process.argv.length; i++) if (process.argv[i].startsWith('--')) args[process.argv[i].slice(2)] = process.argv[i + 1]?.startsWith('--') ? true : process.argv[++i] ?? true;
const port = Number(args.port || 47471), base = `http://127.0.0.1:${port}`;
const N = Number(args.n || 60), PAUSED = Number(args.paused || 10), RESTARTS = Number(args.restarts || 5), EDITS = Number(args.edits || 5), WARMUP = Number(args.warmup ?? 2);
const env = { ...process.env, CODELOUPE_HOME: args.home, CODELOUPE_PORT: String(port) };
const QUERIES = [
  ['find', { q: '*Routes', limit: 10 }], ['outline', { target: 'PasswordHasher' }],
  ['symbol', { name: 'OrganizationRole.atLeast' }], ['find', { q: 'LoginFailures' }],
];
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function status() { return (await fetch(`${base}/status`)).json(); }

async function call(tool, body) {
  const t = performance.now();
  const res = await fetch(`${base}/api/${tool}`, { method: 'POST', headers: { 'content-type': 'application/json', 'x-codeloupe': '1', ...daemonTokenHeader(args.home) }, body: JSON.stringify(body) });
  const json = await res.json();
  return { ms: performance.now() - t, ok: json.ok, text: json.text ?? json.error };
}

/** One query with the daemon's timings diffed around it; retries while the daemon is busy building. */
async function measured(tool, body) {
  for (let i = 0; i < 120; i++) {
    const before = await status();
    const r = await call(tool, body);
    const after = await status();
    if (!r.ok && /^busy/.test(r.text)) { await sleep(1000); continue; }
    if (!r.ok) throw new Error(`${tool} ${JSON.stringify(body)}: ${r.text}`);
    const parts = {};
    for (const [k, v] of Object.entries(after.timings)) parts[k] = { count: v.count - before.timings[k].count, ms: (v.us - before.timings[k].us) / 1000 };
    return { ms: r.ms, spawns: after.gitSpawns - before.gitSpawns, parts, rss: after.rssMb };
  }
  throw new Error('daemon stayed busy');
}

function cli(...a) {
  const r = spawnSync(path.resolve(args.cli), a, { env, shell: process.platform === 'win32', encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`codeloupe ${a.join(' ')}: ${r.stderr || r.stdout}`);
}
async function restart() { cli('stop'); cli('start'); }

const pct = (xs, p) => { const s = [...xs].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p / 100 * s.length))]; };
function summary(name, runs) {
  const mean = f => runs.reduce((s, r) => s + f(r), 0) / runs.length;
  const part = k => mean(r => r.parts[k]?.ms ?? 0);
  const tool = part('tool');
  const rest = Math.max(0, tool - Math.max(part('check'), part('git') + part('jgit') + part('scan')) - part('open') - part('sql'));
  return {
    name, n: runs.length, samples: runs.map(r => Math.round(r.ms)), p50: pct(runs.map(r => r.ms), 50), p95: pct(runs.map(r => r.ms), 95), mean: mean(r => r.ms),
    spawns: mean(r => r.spawns), git: part('git'), jgit: part('jgit'), scan: part('scan'), check: part('check'), refresh: part('refresh'),
    open: part('open'), sql: part('sql'), tool, rest, http: mean(r => r.ms) - tool, rss: Math.max(...runs.map(r => r.rss ?? 0)),
  };
}

const f1 = x => x.toFixed(1);
function table(rows) {
  const out = ['| Scenario | n | client p50 | p95 | mean | git spawns | git | jgit | walk | check | refresh | view open | SQL | tool | rest of tool | HTTP | RSS MB |',
    '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|'];
  for (const r of rows) out.push(`| ${r.name} | ${r.n} | ${f1(r.p50)} | ${f1(r.p95)} | ${f1(r.mean)} | ${f1(r.spawns)} | ${f1(r.git)} | ${f1(r.jgit)} | ${f1(r.scan)} | ${f1(r.check)} | ${f1(r.refresh)} | ${f1(r.open)} | ${f1(r.sql)} | ${f1(r.tool)} | ${f1(r.rest)} | ${f1(r.http)} | ${r.rss} |`);
  return out.join('\n');
}

/** The --only tools rows; returns the budget failures. */
async function tools(rows) {
  const clone = path.resolve(args.clone), tasks = String(args.tasks || '').split(',').map(s => s.trim()).filter(Boolean);
  if (!tasks.length) throw new Error('--only tools needs --clone and --tasks');
  const budget = Number(args.budget || 1000), settle = Number(args.settle ?? 20_000);
  const git = (...a) => execFileSync('git', ['-C', clone, ...a], { encoding: 'utf8' });
  const dir = fs.mkdtempSync(path.join(path.dirname(clone), 'profile-wt-'));
  const made = [];
  const first = [], moved = [], unchanged = [];
  try {
    // A daemon in use has its classes loaded and compiled: the first calls after the restart are not counted.
    await measured('find', { root: clone, q: 'LoginFailures' });
    await measured('task_context', { root: clone, id: tasks[0] });
    await measured('changes', { root: clone });
    for (const id of tasks) {
      const wt = path.join(dir, id).replaceAll('\\', '/');
      git('worktree', 'add', '-q', '-b', `profile-${id}`, wt, 'origin/HEAD');
      made.push(wt);
    }
    await sleep(settle);
    for (const [i, id] of tasks.entries()) first.push(await measured('task_context', { root: made[i], id }));
    for (const [i, id] of tasks.entries()) {
      try { git('rev-parse', '-q', '--verify', `origin/${id}^{commit}`); } catch { continue; }
      execFileSync('git', ['-C', made[i], 'reset', '-q', '--hard', `origin/${id}`]);
      await sleep(1100);
      moved.push(await measured('changes', { root: made[i] }));
      await sleep(1100);
      unchanged.push(await measured('changes', { root: made[i] }));
    }
  } finally {
    for (const wt of made) {
      try { git('worktree', 'remove', '--force', wt); git('branch', '-q', '-D', `profile-${path.basename(wt)}`); } catch (e) { console.error(String(e.message).split('\n')[0]); }
    }
    fs.rmSync(dir, { recursive: true, force: true });
  }
  rows.push(summary('task_context, first call in a new worktree', first));
  if (moved.length) rows.push(summary('changes, first call after the task branch was checked out', moved));
  if (unchanged.length) rows.push(summary('changes, unchanged worktree', unchanged));
  const over = [];
  const p95 = runs => pct(runs.map(r => r.ms), 95);
  if (p95(first) > budget) over.push(`task_context p95 ${p95(first).toFixed(0)} ms > ${budget} ms`);
  if (unchanged.length && p95(unchanged) > budget) over.push(`changes p95 ${p95(unchanged).toFixed(0)} ms > ${budget} ms`);
  return over;
}

async function main() {
  const rows = [];
  await restart();
  const root = args.root, wt = args.worktree;
  if (args.only === 'tools') {
    const over = await tools(rows);
    cli('stop');
    const md = table(rows);
    console.log(md);
    if (args.out) fs.writeFileSync(args.out, JSON.stringify({ at: new Date().toISOString(), clone: args.clone, tasks: args.tasks, rows, table: md, over }, null, 2));
    if (over.length) { console.error(`over budget: ${over.join('; ')}`); process.exit(2); }
    return;
  }
  const edit = args.only === 'edit';
  if (!edit) {
    await measured('find', { root, q: 'LoginFailures' });
    await measured('find', { root: wt, q: 'LoginFailures' });
  }

  if (!edit) for (const target of [['main checkout', root], ['task worktree', wt]]) {
    const warm = [];
    for (let i = 0; i < N; i++) { const [t, b] = QUERIES[i % QUERIES.length]; warm.push(await measured(t, { root: target[1], ...b })); }
    rows.push(summary(`warm, back to back (${target[0]})`, warm));
  }
  const paused = [];
  if (!edit) for (let i = 0; i < PAUSED; i++) { await sleep(1100); const [t, b] = QUERIES[i % QUERIES.length]; paused.push(await measured(t, { root: wt, ...b })); }
  if (!edit) rows.push(summary('warm, after a 1.1 s pause (task worktree)', paused));

  const firstRepo = [], firstWt = [];
  if (!edit) for (let i = 0; i < RESTARTS; i++) {
    await restart();
    firstRepo.push(await measured('find', { root, q: 'LoginFailures' }));
    firstWt.push(await measured('find', { root: wt, q: 'LoginFailures' }));
  }
  if (!edit) {
    rows.push(summary('first query after daemon start (main checkout)', firstRepo));
    rows.push(summary('first query in an unchanged worktree (repo known)', firstWt));
  }

  if (args.clone) {
    const clone = args.clone;
    await measured('find', { root: clone, q: 'LoginFailures' });
    const file = execFileSync('git', ['-C', clone, 'ls-files', '*PasswordHasher.kt'], { encoding: 'utf8' }).split('\n').find(Boolean);
    const full = path.join(clone, file), original = fs.readFileSync(full);
    const edits = [], reverts = [];
    try {
      // The first cycles start the parse worker and warm the JIT: not counted.
      for (let i = 0; i < WARMUP + EDITS; i++) {
        fs.writeFileSync(full, Buffer.concat([original, Buffer.from(`\nfun profileEdit${i}() = ${i}\n`)]));
        await sleep(1100);
        const edited = await measured('find', { root: clone, q: `profileEdit${i}` });
        fs.writeFileSync(full, original);
        await sleep(1100);
        const reverted = await measured('find', { root: clone, q: 'LoginFailures' });
        if (i >= WARMUP) { edits.push(edited); reverts.push(reverted); }
      }
    } finally { fs.writeFileSync(full, original); }
    rows.push(summary('overlay refresh: file edited (clone)', edits));
    rows.push(summary('overlay refresh: edit reverted (clone)', reverts));
  }
  cli('stop');
  const md = table(rows);
  console.log(md);
  if (args.out) fs.writeFileSync(args.out, JSON.stringify({ at: new Date().toISOString(), root, worktree: wt, clone: args.clone, rows, table: md }, null, 2));
}

main().catch(e => { try { cli('stop'); } catch {} console.error(e.message); process.exit(1); });
