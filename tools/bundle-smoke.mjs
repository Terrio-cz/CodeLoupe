#!/usr/bin/env node
// Smoke test and measurement of a bundle (CL-103): runs a query with the bundle's own launcher (on Windows: its runtime) on a PATH without any
// Java (JAVA_HOME and friends removed, every PATH directory holding a java executable dropped), then records the bundle
// size and the daemon's RSS. Uses its own CODELOUPE_HOME and port and stops its daemon at the end.
//
//   node tools/bundle-smoke.mjs <bundle dir> [--zip <bundle zip>] [--out bundle-report.json]
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { noJavaEnv } from './no-java-env.mjs';

const [bundle, ...rest] = process.argv.slice(2);
const opt = name => rest[rest.indexOf(`--${name}`) + 1];
if (!bundle || !fs.existsSync(path.join(bundle, 'bin'))) { console.error('usage: bundle-smoke.mjs <bundle dir> [--zip file] [--out file]'); process.exit(2); }
const windows = process.platform === 'win32';
const sleep = ms => new Promise(r => setTimeout(r, ms));

function dirSize(dir) {
  let total = 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    total += entry.isDirectory() ? dirSize(p) : entry.isFile() ? fs.statSync(p).size : 0;
  }
  return total;
}
const mb = bytes => Math.round(bytes / 1048576 * 10) / 10;

// A machine without Java: no JAVA_HOME-style variables, no PATH directory with a java executable.
const javaName = windows ? 'java.exe' : 'java';
const { env, cleanup: cleanupEnv } = noJavaEnv();

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codeloupe-smoke-'));
const port = await new Promise(resolve => { const s = net.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); }); });
Object.assign(env, { CODELOUPE_HOME: path.join(tmp, 'home'), CODELOUPE_PORT: String(port) });

// Node refuses to start a .bat without a shell (CVE-2024-27980) and a shell command built from a path is what we avoid,
// so on Windows the bundled java runs the jar with the launcher's own flags (minus the class-data archive, which only
// speeds up repeated starts). The unix launcher is a plain executable and runs as shipped.
const launcher = path.resolve(bundle, 'bin', 'codeloupe');
const bundledJava = path.resolve(bundle, 'runtime', 'bin', javaName);
const jar = windows ? fs.readdirSync(path.join(bundle, 'lib')).find(f => /^codeloupe-.*\.jar$/.test(f)) : null;
if (windows && !jar) throw new Error('no codeloupe jar in the bundle lib directory');
const windowsJvmFlags = ['-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1', '-Xshare:auto', '-Xss512k', '-Xmx128m', '-XX:-UsePerfData', '-Xlog:disable'];
function cli(args, cwd) {
  const started = performance.now();
  const run = windows
    ? spawnSync(bundledJava, [...windowsJvmFlags, '-jar', path.resolve(bundle, 'lib', jar), ...args], { env, cwd, encoding: 'utf8' })
    : spawnSync(launcher, args, { env, cwd, encoding: 'utf8' });
  return { code: run.status, out: `${run.stdout ?? ''}${run.stderr ?? ''}`, ms: Math.round(performance.now() - started) };
}
function git(cwd, ...args) {
  const run = spawnSync('git', ['-c', 'user.email=t@example.com', '-c', 'user.name=t', ...args], { cwd, encoding: 'utf8' });
  if (run.status !== 0) throw new Error(`git ${args.join(' ')}: ${run.stderr}`);
}

const repo = path.join(tmp, 'repo');
fs.mkdirSync(path.join(repo, 'src'), { recursive: true });
fs.writeFileSync(path.join(repo, 'src', 'Greeter.kt'), 'package demo\n\nclass Greeter {\n    fun greet(name: String): String = "Hello, $name"\n}\n');
git(repo, 'init', '-q', '-b', 'main');
git(repo, 'add', '-A');
git(repo, 'commit', '-q', '-m', 'init');

const report = { os: process.platform, arch: process.arch, port };
let failure = null;
try {
  const first = cli(['outline', 'Greeter'], repo);
  report.firstQueryMs = first.ms;
  if (first.code !== 0 || !first.out.includes('greet')) throw new Error(`outline failed (exit ${first.code}): ${first.out}`);
  const second = cli(['find', 'greet'], repo);
  report.warmQueryMs = second.ms;
  if (second.code !== 0 || !second.out.includes('Greeter')) throw new Error(`find failed (exit ${second.code}): ${second.out}`);
  // Let the daemon settle after the index build before reading its memory.
  await sleep(2000);
  const status = await (await fetch(`http://127.0.0.1:${port}/status`)).json();
  report.daemonRssMb = status.rssMb;
  report.daemonHeapMb = status.heapMb;
  report.version = status.version;
  const runtimeJava = bundledJava;
  report.runtime = (spawnSync(runtimeJava, ['-version'], { encoding: 'utf8' }).stderr ?? '').split('\n')[0];
} catch (e) {
  failure = e;
} finally {
  cli(['stop'], repo);
  await sleep(500);
}

report.bundleMb = mb(dirSize(bundle));
report.runtimeMb = mb(dirSize(path.join(bundle, 'runtime')));
const zip = opt('zip');
if (zip) report.zipMb = mb(fs.statSync(zip).size);
console.log(JSON.stringify(report, null, 2));
if (opt('out')) fs.writeFileSync(opt('out'), JSON.stringify(report, null, 2) + '\n');
try { fs.rmSync(tmp, { recursive: true, force: true }); cleanupEnv(); } catch { /* the stopped daemon may still hold a file on Windows */ }
if (failure) { console.error(failure.message); process.exit(1); }
