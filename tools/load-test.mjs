#!/usr/bin/env node
// Load test of the daemon (CL-25): many clients on many worktrees while the base index syncs and worktrees change.
// Each client waits --think ms (default 250: four queries a second, a window hard at work) between queries.
// Starts its own daemon (own --home and --port), clones --source into a scratch directory (the source is only read),
// adds worktrees, and runs --clients concurrent query loops over them. Midway it commits a change to many files of the
// default branch (a sync in a build worker) and edits files in some worktrees (overlay refreshes). Samples /status
// every 500 ms. Prints a table and checks the budgets of docs/plan.md § 2; exit code 1 when one is exceeded.
//
//   node tools/load-test.mjs --install build/install/codeloupe --source <repo> [--home <tmp>] [--port 47481]
//     [--worktrees 8] [--clients 10] [--seconds 90] [--sync-files 250] [--think 250] [--config '{"maxParallelQueries":2}'] [--out load-test.json] [--external]
//   --external: the daemon on --port is already running (started with other JVM flags, say); it is neither started nor stopped.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { daemonTokenHeader } from './daemon-token.mjs';

const args = {};
for (let i = 2; i < process.argv.length; i++) if (process.argv[i].startsWith('--')) args[process.argv[i].slice(2)] = process.argv[i + 1]?.startsWith('--') ? true : process.argv[++i] ?? true;
if (!args.install || !args.source) { console.error('usage: load-test.mjs --install <install dir> --source <repo> [--home dir] [--port n] [--worktrees 8] [--clients 10] [--seconds 90] [--sync-files 250] [--out file]'); process.exit(2); }

const WORKTREES = Number(args.worktrees || 8), CLIENTS = Number(args.clients || 10), SECONDS = Number(args.seconds || 90), SYNC_FILES = Number(args['sync-files'] || 250), THINK = Number(args.think ?? 250);
const port = Number(args.port || 47481), base = `http://127.0.0.1:${port}`;
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'codeloupe-load-'));
const home = path.resolve(args.home || path.join(scratch, 'home'));
const env = { ...process.env, CODELOUPE_HOME: home, CODELOUPE_PORT: String(port) };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const windows = process.platform === 'win32';
const install = path.resolve(args.install);
const jar = fs.readdirSync(path.join(install, 'lib')).find(f => /^codeloupe-.*\.jar$/.test(f));
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', windows ? 'java.exe' : 'java') : 'java';
if (!jar) throw new Error(`no codeloupe jar in ${install}/lib`);

function codeloupe(...a) {
  const run = spawnSync(java, ['-Xshare:auto', '-Xmx128m', '-jar', path.join(install, 'lib', jar), ...a], { env, encoding: 'utf8' });
  if (run.status !== 0) throw new Error(`codeloupe ${a.join(' ')}: ${run.stderr || run.stdout}`);
  return run.stdout;
}
function git(cwd, ...a) {
  const run = spawnSync('git', ['-c', 'user.email=load@example.com', '-c', 'user.name=load', ...a], { cwd, encoding: 'utf8', maxBuffer: 64 << 20 });
  if (run.status !== 0) throw new Error(`git ${a.join(' ')}: ${run.stderr}`);
  return run.stdout;
}
const status = async () => (await fetch(`${base}/status`, { headers: { 'x-codeloupe': '1' } })).json();
let retried = 0;
async function call(tool, body) {
  const t = performance.now();
  // Like an MCP client, retry once when the connection broke before an answer came (a socket the server just closed).
  for (let attempt = 0; ; attempt++) {
    try {
      const res = await fetch(`${base}/api/${tool}`, { method: 'POST', headers: { 'content-type': 'application/json', 'x-codeloupe': '1', ...daemonTokenHeader(home) }, body: JSON.stringify(body) });
      const json = await res.json();
      return { ms: performance.now() - t, ok: !!json.ok, text: json.text ?? json.error ?? '' };
    } catch (e) {
      if (attempt === 0) { retried++; await sleep(50); continue; }
      return { ms: performance.now() - t, ok: false, text: `error: ${e.message}` };
    }
  }
}
/** Time per timing part (summed over threads, so it can exceed the wall time) between the samples around [from] and [to]. */
function timingsDiff(rss, from, to) {
  const at = t => rss.filter(s => s.t <= t).pop()?.timings;
  const a = at(from), b = at(Number.isFinite(to) ? to : Infinity);
  if (!a || !b) return null;
  return Object.fromEntries(Object.keys(b).map(k => [k, Math.round(((b[k].us ?? 0) - (a[k]?.us ?? 0)) / 1000)]));
}
const pct = (xs, p) => { if (!xs.length) return 0; const s = [...xs].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p / 100 * s.length))]; };

async function main() {
  const clone = path.join(scratch, 'clone');
  git(scratch, 'clone', '-q', '--local', path.resolve(args.source), clone);
  const branch = git(clone, 'rev-parse', '--abbrev-ref', 'HEAD').trim();
  const trees = [clone];
  for (let i = 1; i < WORKTREES; i++) { const wt = path.join(scratch, `wt-${i}`); git(clone, 'worktree', 'add', '-q', '--detach', wt); trees.push(wt); }

  fs.mkdirSync(home, { recursive: true });
  if (args.config) fs.writeFileSync(path.join(home, 'config.json'), String(args.config));
  if (!args.external) codeloupe('start');
  const started = Date.now();
  // The first query builds the base; the map names the types the mix then asks about.
  let map = await call('outline', { root: clone, budget: 1500 });
  while (/^busy/.test(map.text)) { await sleep(1000); map = await call('outline', { root: clone, budget: 1500 }); }
  const buildMs = Date.now() - started;
  const types = [...map.text.matchAll(/^ {2}(?:[a-z]+ )*(?:class|interface|object) (\w+)/gm)].map(m => m[1]).slice(0, 12);
  const files = [...map.text.matchAll(/^(\S+\.kt)$/gm)].map(m => m[1]).slice(0, 12);
  if (types.length < 3) throw new Error(`the map of ${args.source} names too few types:\n${map.text.slice(0, 500)}`);

  const mix = i => {
    const t = types[i % types.length], f = files[i % files.length] ?? '';
    return [['find', { q: t }], ['symbol', { name: t }], ['usages', { name: t, limit: 20 }], ['calls', { name: t, depth: 2 }], ['outline', { target: f }],
      ['grep', { pattern: t, limit: 20 }], ['context', { name: t }], ['hierarchy', { name: t }]][i % 8];
  };

  const samples = [], rss = [], events = [], failures = {};
  let stop = false, calls = 0;
  const t0 = performance.now();
  const sampler = (async () => {
    while (!stop) {
      try { const s = await status(); rss.push({ t: performance.now() - t0, rss: s.rssMb ?? 0, heapRunning: s.queue.heavy.running, fastRunning: s.queue.fast.running, waiting: s.queue.heavy.waiting.length, timings: s.timings }); } catch { /* the daemon is busy starting */ }
      await sleep(500);
    }
  })();
  const client = async id => {
    let n = id;
    while (!stop) {
      const tree = (id + n) % trees.length, root = trees[tree];
      const [tool, body] = mix(n++);
      const r = await call(tool, { root, ...body });
      calls++;
      const failed = !r.ok && !/^busy/.test(r.text);
      if (failed) { const key = r.text.slice(0, 120); const f = failures[key] ??= { count: 0, firstAtMs: Math.round(performance.now() - t0) }; f.count++; f.lastAtMs = Math.round(performance.now() - t0); }
      samples.push({ t: performance.now() - t0 - r.ms, tool, tree, ms: r.ms, busy: !r.ok && /^busy/.test(r.text), failed });
      await sleep(THINK);
    }
  };
  const clients = Array.from({ length: CLIENTS }, (_, i) => client(i));

  // Warm for a quarter of the time, then change the world.
  await sleep(SECONDS * 250);
  const syncAt = performance.now() - t0;
  const tracked = git(clone, 'ls-files', '*.kt').split('\n').filter(Boolean).slice(0, SYNC_FILES);
  for (const f of tracked) fs.appendFileSync(path.join(clone, f), '\n// load test change\n');
  git(clone, 'commit', '-q', '-am', 'load test: touch many files');
  events.push({ at: syncAt, what: `commit touching ${tracked.length} files` });
  await sleep(SECONDS * 150);
  const editAt = performance.now() - t0;
  for (let i = 1; i < Math.min(5, trees.length); i++) {
    const f = git(trees[i], 'ls-files', '*.kt').split('\n').filter(Boolean)[i];
    fs.appendFileSync(path.join(trees[i], f), `\nfun loadTestEdit${i}() = ${i}\n`);
  }
  events.push({ at: editAt, what: 'edits in 4 worktrees' });
  await sleep(SECONDS * 1000 - (performance.now() - t0));
  stop = true;
  await Promise.all([...clients, sampler]);
  const finalStatus = await status();
  if (!args.external) codeloupe('stop');

  // The window after the landing: from the commit until the heavy lane was seen idle again after running.
  const afterSync = rss.filter(s => s.t >= syncAt);
  const busyAfter = afterSync.findIndex(s => s.heapRunning);
  const idleAfter = busyAfter < 0 ? -1 : afterSync.slice(busyAfter).findIndex(s => !s.heapRunning);
  const syncEnd = Math.max(syncAt + 5000, busyAfter < 0 ? syncAt : idleAfter < 0 ? Infinity : afterSync[busyAfter + idleAfter].t);
  // Worktree 0 made the commit: its own files changed with the landing, and its next query may wait once. The others did not.
  const inWindow = samples.filter(s => s.t >= syncAt && s.t <= syncEnd);
  const during = inWindow.filter(s => s.tree !== 0).map(s => s.ms);
  const landing = inWindow.filter(s => s.tree === 0).map(s => s.ms);
  const outside = samples.filter(s => s.t < syncAt).map(s => s.ms);
  const lastThird = rss.filter(s => s.t >= SECONDS * 1000 * 2 / 3).map(s => s.rss);
  const result = {
    at: new Date().toISOString(), source: args.source, worktrees: WORKTREES, clients: CLIENTS, seconds: SECONDS, syncFiles: tracked.length, buildMs, calls,
    rssSeries: rss.filter((_, i) => i % 20 === 0).map(s => `${Math.round(s.t / 1000)}s ${s.rss}`),
    rssSteadyMb: pct(lastThird, 50), rssPeakMb: Math.max(...rss.map(s => s.rss)), heavyWaitingMax: Math.max(0, ...rss.map(s => s.waiting)),
    p95Ms: pct(samples.map(s => s.ms), 95), p95BeforeSyncMs: pct(outside, 95), p95DuringSyncMs: pct(during, 95), landingWorktreeMaxMs: Math.round(Math.max(0, ...landing)), duringSyncCalls: during.length,
    slowestDuringSync: samples.filter(s => s.t >= syncAt && s.t <= syncEnd && s.tree !== 0).sort((a, b) => b.ms - a.ms).slice(0, 8).map(s => `${Math.round(s.t - syncAt)}  ms after the commit it started: ${s.tool} on worktree ${s.tree} took ${Math.round(s.ms)} ms`),
    syncSeconds: Number.isFinite(syncEnd) ? Math.round((syncEnd - syncAt) / 100) / 10 : null,
    retried, busy: samples.filter(s => s.busy).length, failed: samples.filter(s => s.failed).length, failures, daemonCalls: finalStatus.calls, events,
    syncTimingsMs: timingsDiff(rss, syncAt, syncEnd), heavyJobs: [...new Set(rss.map(s => s.heapRunning).filter(Boolean))], fastJobs: [...new Set(rss.map(s => s.fastRunning).filter(Boolean))],
    byTool: Object.fromEntries([...new Set(samples.map(s => s.tool))].map(t => [t, { n: samples.filter(s => s.tool === t).length, p95: Math.round(pct(samples.filter(s => s.tool === t).map(s => s.ms), 95)) }])),
  };
  const budgets = [
    ['steady RSS ≤ 200 MB', result.rssSteadyMb <= 200], ['peak RSS ≤ 300 MB', result.rssPeakMb <= 300],
    ['p95 of the other worktrees in the 5 s after the landing ≤ 300 ms', result.duringSyncCalls > 0 && result.p95DuringSyncMs <= 300], ['no busy answers', result.busy === 0], ['no failed calls', result.failed === 0],
  ];
  result.budgets = Object.fromEntries(budgets);
  console.log(JSON.stringify(result, null, 2));
  for (const [name, ok] of budgets) console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}`);
  if (args.out) fs.writeFileSync(args.out, JSON.stringify(result, null, 2) + '\n');
  fs.rmSync(scratch, { recursive: true, force: true, maxRetries: 3 });
  if (budgets.some(([, ok]) => !ok)) process.exit(1);
}

main().catch(e => { try { if (!args.external) codeloupe('stop'); } catch { /* not started */ } console.error(e.message); process.exit(1); });
