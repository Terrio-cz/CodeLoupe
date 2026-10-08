#!/usr/bin/env node
// Resident memory of the daemon after a mix of queries (CL-118): starts a throwaway daemon with the JVM flags of
// `DaemonJvm` (and --jvm-opts on top; the parse worker is on, as in the daemon), runs 50 queries in turn — find, outline, symbol, usages, calls callers and callees
// depth 3, context, grep, and `changes bodies` in a worktree with edits — and reports /status rssMb and heapMb after
// the sequence, with the JVM's own accounting (jcmd VM.native_memory) when the JDK has jcmd.
//
//   node tools/rss-mix.mjs --install build/install/codeloupe --source <repo> [--port 47491] [--jvm-opts "-Xmn12m"]
//     [--rounds 5] [--symbol ApiKey.id] [--skip changes,calls]  (tools left out of the mix) [--gc-after true] [--histogram true]  (live heap by class, after a full GC)
//
// --source is cloned into a scratch directory and only read; the daemon has its own home and port and is stopped at the end.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';

const args = {};
for (let i = 2; i < process.argv.length; i++) if (process.argv[i].startsWith('--')) args[process.argv[i].slice(2)] = process.argv[i + 1]?.startsWith('--') ? true : process.argv[++i] ?? true;
if (!args.install || !args.source) { console.error('usage: rss-mix.mjs --install <install dir> --source <repo> [--port n] [--jvm-opts "…"] [--rounds n] [--symbol Type.member]'); process.exit(2); }

const port = Number(args.port || 47491), base = `http://127.0.0.1:${port}`;
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'codeloupe-rss-'));
const home = path.join(scratch, 'home');
const install = path.resolve(args.install);
const windows = process.platform === 'win32';
const javaHome = process.env.JAVA_HOME;
const java = javaHome ? path.join(javaHome, 'bin', windows ? 'java.exe' : 'java') : 'java';
const jcmd = javaHome ? path.join(javaHome, 'bin', windows ? 'jcmd.exe' : 'jcmd') : 'jcmd';
const sleep = ms => new Promise(r => setTimeout(r, ms));
// The flags of DaemonJvm (cli/DaemonJvm.kt); keep in step.
const DAEMON_JVM = ['-Xms16m', '-Xmx64m', '-Xmn10m', '-Xss512k', '-XX:+UseSerialGC', '-XX:MinHeapFreeRatio=10', '-XX:MaxHeapFreeRatio=30', '-XX:+UseCompactObjectHeaders',
  '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=32m', '-XX:MaxMetaspaceSize=96m'];

function git(cwd, ...a) {
  const run = spawnSync('git', ['-c', 'user.email=rss@example.com', '-c', 'user.name=rss', ...a], { cwd, encoding: 'utf8', maxBuffer: 64 << 20 });
  if (run.status !== 0) throw new Error(`git ${a.join(' ')}: ${run.stderr}`);
  return run.stdout;
}
const status = async () => (await fetch(`${base}/status`, { headers: { 'x-codeloupe': '1' } })).json();
async function call(tool, body) {
  for (let i = 0; i < 120; i++) {
    const res = await fetch(`${base}/api/${tool}`, { method: 'POST', headers: { 'content-type': 'application/json', 'x-codeloupe': '1' }, body: JSON.stringify(body) });
    const json = await res.json();
    if (!json.ok && /^busy/.test(json.text ?? '')) { await sleep(1000); continue; }
    return json.text ?? json.error ?? '';
  }
  throw new Error(`${tool} stayed busy`);
}

async function main() {
  const clone = path.join(scratch, 'clone'), task = path.join(scratch, 'task');
  git(scratch, 'clone', '-q', '--local', path.resolve(args.source), clone);
  git(clone, 'worktree', 'add', '-q', '-b', 'task', task);
  // Edits in the task worktree: up to six Kotlin and six Java files.
  const sources = ext => git(task, 'ls-files', `*.${ext}`).split('\n').filter(Boolean);
  for (const f of sources('kt').slice(0, 6)) fs.appendFileSync(path.join(task, f), '\nfun rssMixEdit() = 1\n');
  for (const f of sources('java').slice(0, 6)) fs.appendFileSync(path.join(task, f), '\nclass RssMixEdit {}\n');

  fs.mkdirSync(home, { recursive: true });
  const jvm = [...DAEMON_JVM, ...(args['jvm-opts'] ? String(args['jvm-opts']).split(/\s+/).filter(Boolean) : []), '-XX:NativeMemoryTracking=summary'];
  const classpath = path.join(install, 'lib', '*');
  const daemon = spawn(java, [...jvm, '--enable-native-access=ALL-UNNAMED', '-cp', classpath, 'codeloupe.MainKt', 'daemon', '--detached', '--home', home, '--port', String(port)], { stdio: 'ignore' });
  for (let i = 0; i < 100; i++) { try { await status(); break; } catch { await sleep(200); } }

  const map = await call('outline', { root: clone, budget: 1500 });
  const types = [...map.matchAll(/^ {2}(?:[a-z]+ )*(?:class|interface|object) (\w+)/gm)].map(m => m[1]).slice(0, 10);
  const symbol = args.symbol || types[0];
  const mix = [
    ['find', r => ({ root: r, q: types[0] })], ['outline', r => ({ root: r, target: types[1] })], ['symbol', r => ({ root: r, name: symbol })],
    ['usages', r => ({ root: r, name: symbol })], ['calls', r => ({ root: r, name: symbol, depth: 3 })], ['calls', r => ({ root: r, name: types[2], direction: 'callees', depth: 3 })],
    ['context', r => ({ root: r, name: types[3] })], ['grep', r => ({ root: r, pattern: types[4], limit: 30 })], ['hierarchy', r => ({ root: r, name: types[5] })],
    ['changes', () => ({ root: task, bodies: true })],
  ];
  const skip = new Set(String(args.skip || '').split(',').filter(Boolean));
  const rounds = Number(args.rounds || 5);
  for (let round = 0; round < rounds; round++) for (const [tool, body] of mix) if (!skip.has(tool)) await call(tool, body(round % 2 ? task : clone));
  await sleep(1500);
  const s = await status();
  if (args['gc-after']) { spawnSync(jcmd, [String(s.pid), 'GC.run'], { encoding: 'utf8' }); await sleep(1000); }
  const s2 = args['gc-after'] ? await status() : s;
  const out = { rssAfterGcMb: args['gc-after'] ? s2.rssMb : undefined, source: args.source, jvmOpts: args['jvm-opts'] ?? '', queries: rounds * mix.filter(([t]) => !skip.has(t)).length, skipped: [...skip], rssMb: s.rssMb, heapMb: s.heapMb, gitSpawns: s.gitSpawns };
  const nmt = spawnSync(jcmd, [String(s.pid), 'VM.native_memory', 'summary'], { encoding: 'utf8' });
  if (nmt.status === 0) out.nmt = Object.fromEntries([...nmt.stdout.matchAll(/^-\s+([A-Za-z ]+?) \(reserved=\d+KB, committed=(\d+)KB/gm)].map(m => [m[1].trim(), Math.round(m[2] / 1024)]));
  if (args.histogram) {
    const histo = spawnSync(jcmd, [String(s.pid), 'GC.class_histogram'], { encoding: 'utf8', maxBuffer: 64 << 20 });
    out.liveHeapTop = histo.stdout.split(/\r?\n/).filter(l => /^\s*\d+:/.test(l)).slice(0, 14).map(l => l.trim().replace(/\s+/g, ' '));
  }
  out.budget200 = s.rssMb <= 200;
  console.log(JSON.stringify(out, null, 2));
  if (args.out) fs.writeFileSync(args.out, JSON.stringify(out, null, 2) + '\n');
  daemon.kill();
  await sleep(500);
  fs.rmSync(scratch, { recursive: true, force: true, maxRetries: 3 });
  if (!out.budget200) process.exit(1);
}

main().catch(e => { console.error(e.message); process.exit(1); });
