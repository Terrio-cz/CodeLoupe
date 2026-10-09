#!/usr/bin/env node
// A repository with very many files (CL-79): the cost of checking a worktree on every query, the daemon's memory, and its CPU
// while idle. Starts its own daemon (own --home and --port) on --source, a disposable repository such as
// `gen-big.mjs` writes (200k small Kotlin files): it adds a linked worktree there and edits a file in it.
//
//   node tools/large-worktree.mjs --install build/install/codeloupe --source <big repo> [--home <tmp>] [--port 47501]
//     [--pauses 12] [--idle-seconds 20] [--config '{"largeWorktreeFiles":40000}'] [--out large-worktree.json]
//
// Budgets: a query after a pause ≤ 300 ms, RSS ≤ 200 MB, no CPU while idle. Exit code 1 when one is exceeded.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { daemonTokenHeader } from './daemon-token.mjs';

const args = {};
for (let i = 2; i < process.argv.length; i++) if (process.argv[i].startsWith('--')) args[process.argv[i].slice(2)] = process.argv[i + 1]?.startsWith('--') ? true : process.argv[++i] ?? true;
if (!args.install || !args.source) { console.error('usage: large-worktree.mjs --install <install dir> --source <repo> [--home dir] [--port n] [--pauses n] [--idle-seconds n] [--config json] [--out file]'); process.exit(2); }

const port = Number(args.port || 47501), base = `http://127.0.0.1:${port}`;
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'codeloupe-large-'));
const home = path.resolve(args.home || path.join(scratch, 'home'));
const env = { ...process.env, CODELOUPE_HOME: home, CODELOUPE_PORT: String(port) };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const windows = process.platform === 'win32';
const install = path.resolve(args.install);
const jar = fs.readdirSync(path.join(install, 'lib')).find(f => /^codeloupe-.*\.jar$/.test(f));
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', windows ? 'java.exe' : 'java') : 'java';
const source = path.resolve(args.source);
const worktree = path.join(scratch, 'wt');

function codeloupe(...a) {
  const run = spawnSync(java, ['-Xshare:auto', '-Xmx128m', '-jar', path.join(install, 'lib', jar), ...a], { env, encoding: 'utf8' });
  if (run.status !== 0) throw new Error(`codeloupe ${a.join(' ')}: ${run.stderr || run.stdout}`);
}
function git(cwd, ...a) {
  const run = spawnSync('git', ['-c', 'user.email=l@example.com', '-c', 'user.name=l', ...a], { cwd, encoding: 'utf8', maxBuffer: 1 << 28 });
  if (run.status !== 0) throw new Error(`git ${a.join(' ')}: ${run.stderr}`);
  return run.stdout;
}
const status = async () => (await fetch(`${base}/status`, { headers: { 'x-codeloupe': '1' } })).json();
async function call(tool, body) {
  const t = performance.now();
  for (;;) {
    const res = await fetch(`${base}/api/${tool}`, { method: 'POST', headers: { 'content-type': 'application/json', 'x-codeloupe': '1', ...daemonTokenHeader(home) }, body: JSON.stringify(body) });
    const json = await res.json();
    if (!json.ok && /^busy/.test(json.text ?? '')) { await sleep(1000); continue; }
    return { ms: performance.now() - t, ok: !!json.ok, text: json.text ?? json.error ?? '' };
  }
}
const pct = (xs, p) => { const s = [...xs].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p / 100 * s.length))]; };

async function main() {
  const files = Number(git(source, 'ls-files', '*.kt').split('\n').filter(Boolean).length);
  git(source, 'worktree', 'add', '-q', '--detach', worktree);
  fs.mkdirSync(home, { recursive: true });
  if (args.config) fs.writeFileSync(path.join(home, 'config.json'), String(args.config));
  codeloupe('start');
  const t0 = Date.now();
  const first = await call('find', { root: source, q: 'C7' });
  const buildMs = Date.now() - t0;
  const firstWorktree = await call('find', { root: worktree, q: 'C7' });

  const pauses = [];
  for (let i = 0; i < Number(args.pauses || 12); i++) {
    await sleep(1200);
    pauses.push((await call(i % 2 ? 'find' : 'symbol', { root: i % 3 ? worktree : source, ...(i % 2 ? { q: `C${i + 10}` } : { name: `C${i + 10}` }) })).ms);
  }
  // An edit shows in the next query.
  const edited = path.join(worktree, git(worktree, 'ls-files', '*.kt').split('\n').filter(Boolean)[5]);
  fs.appendFileSync(edited, '\nfun largeWorktreeEdit() = 1\n');
  await sleep(1200);
  const edit = await call('find', { root: worktree, q: 'largeWorktreeEdit' });

  const s1 = await status();
  await sleep(Number(args['idle-seconds'] || 20) * 1000);
  const s2 = await status();
  codeloupe('stop');
  git(source, 'worktree', 'remove', '--force', worktree);

  const result = {
    at: new Date().toISOString(), files, config: args.config ?? null, buildMs, firstQueryWorktreeMs: Math.round(firstWorktree.ms),
    afterPauseMs: { n: pauses.length, p50: Math.round(pct(pauses, 50)), p95: Math.round(pct(pauses, 95)), max: Math.round(Math.max(...pauses)) },
    editVisible: edit.text.includes('largeWorktreeEdit'), editMs: Math.round(edit.ms), rssMb: s2.rssMb, heapMb: s2.heapMb,
    idleCpuSec: s2.cpuSec - s1.cpuSec, idleSeconds: Number(args['idle-seconds'] || 20), timings: s2.timings, firstFind: first.text.split('\n')[0],
  };
  const budgets = [['after a pause ≤ 300 ms (p95)', result.afterPauseMs.p95 <= 300], ['RSS ≤ 200 MB', result.rssMb <= 200], ['no CPU while idle', result.idleCpuSec === 0], ['an edit shows in the next query', result.editVisible]];
  result.budgets = Object.fromEntries(budgets);
  console.log(JSON.stringify(result, null, 2));
  for (const [name, ok] of budgets) console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}`);
  if (args.out) fs.writeFileSync(args.out, JSON.stringify(result, null, 2) + '\n');
  fs.rmSync(scratch, { recursive: true, force: true, maxRetries: 3 });
  if (budgets.some(([, ok]) => !ok)) process.exit(1);
}

main().catch(e => { try { codeloupe('stop'); } catch { /* not started */ } console.error(e.message); process.exit(1); });
