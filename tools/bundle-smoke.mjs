#!/usr/bin/env node
// Smoke test and measurement of a bundle (CL-103): runs a query with the bundle's own launcher on a PATH without any
// Java (JAVA_HOME and friends removed, every PATH directory holding a java executable dropped), then records the bundle
// size and the daemon's RSS. Uses its own CODELOUPE_HOME and port and stops its daemon at the end.
//
//   node tools/bundle-smoke.mjs <bundle dir> [--zip <bundle zip>] [--out bundle-report.json] [--aot]
//
// --aot (CL-150) also waits for the AOT caches the daemon makes in the background, checks that they are one pair with nothing left over,
// that a copy of the jars (other path, other modification times) and a damaged cache run without a word, and times a CLI call with no archive,
// with the dynamic class-data archive and with the AOT cache (medians of interleaved calls).
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { noJavaEnv } from './no-java-env.mjs';

const [bundle, ...rest] = process.argv.slice(2);
const opt = name => { const at = rest.indexOf(`--${name}`); return at < 0 ? undefined : rest[at + 1]; };
const flag = name => rest.includes(`--${name}`);
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

// The launcher as a user runs it. Node refuses to start a .bat directly (CVE-2024-27980), so on Windows cmd.exe runs it; the
// command line is built from the bundle path and plain words only, and anything cmd would read as syntax is refused.
const launcher = path.resolve(bundle, 'bin', windows ? 'codeloupe.bat' : 'codeloupe');
const bundledJava = path.resolve(bundle, 'runtime', 'bin', javaName);
const jar = fs.readdirSync(path.join(bundle, 'lib')).find(f => /^codeloupe-.*\.jar$/.test(f));
if (!jar) throw new Error('no codeloupe jar in the bundle lib directory');
const jarPath = path.resolve(bundle, 'lib', jar);
function launch(args, cwd, environment = env) {
  if (!windows) return spawnSync(launcher, args, { env: environment, cwd, encoding: 'utf8' });
  const plain = /^[\w .:\\/-]+$/;
  if (![launcher, ...args].every(a => plain.test(a))) throw new Error(`not a plain command line: ${launcher} ${args.join(' ')}`);
  return spawnSync(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', `""${launcher}" ${args.join(' ')}"`], { env: environment, cwd, encoding: 'utf8', windowsVerbatimArguments: true });
}
/** The same call without waiting: two first calls at once. */
function launchAsync(args, cwd) {
  const started = performance.now();
  const child = windows
    ? spawn(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', `""${launcher}" ${args.join(' ')}"`], { env, cwd, windowsVerbatimArguments: true })
    : spawn(launcher, args, { env, cwd });
  let out = '';
  child.stdout.on('data', d => { out += d; });
  child.stderr.on('data', d => { out += d; });
  return new Promise(resolve => child.on('close', code => resolve({ code, out, ms: Math.round(performance.now() - started) })));
}
function cli(args, cwd) {
  const started = performance.now();
  const run = launch(args, cwd);
  return { code: run.status, out: `${run.stdout ?? ''}${run.stderr ?? ''}`, ms: Math.round(performance.now() - started) };
}
function git(cwd, ...args) {
  const run = spawnSync('git', ['-c', 'user.email=t@example.com', '-c', 'user.name=t', ...args], { cwd, encoding: 'utf8' });
  if (run.status !== 0) throw new Error(`git ${args.join(' ')}: ${run.stderr}`);
}

const repo = path.join(tmp, 'repo');
fs.mkdirSync(path.join(repo, 'src'), { recursive: true });
fs.writeFileSync(path.join(repo, 'src', 'Greeter.kt'), 'package demo\n\nclass Greeter {\n    fun greet(name: String): String = "Hello, $name"\n}\n');
// What the parser meets besides a plain class: generics, lambdas, annotations, string templates, KDoc, a data class, and a Java file.
// A runtime trimmed of a module the Kotlin compiler's parser still loads fails here (CL-143).
fs.writeFileSync(path.join(repo, 'src', 'Orders.kt'), [
  'package demo', '', 'import kotlinx.serialization.Serializable', '',
  '/** An order line. */', '@Serializable', 'data class Line<T : Comparable<T>>(val sku: String, val qty: Int, val tags: List<T> = emptyList())', '',
  'sealed interface Result { object Ok : Result; data class Failed(val why: String) : Result }', '',
  'class Orders(private val lines: MutableList<Line<String>> = mutableListOf()) {',
  '    /** Totals the quantities, `"${lines.size}"` lines. */',
  '    fun total(): Int = lines.sumOf { it.qty } + lines.count { l -> l.tags.any { t -> t.startsWith("x") } }',
  '    suspend fun add(line: Line<String>): Result = if (line.qty > 0) { lines += line; Result.Ok } else Result.Failed("empty $line")',
  '}', '',
].join('\n'));
fs.writeFileSync(path.join(repo, 'src', 'Mailer.java'), [
  'package demo;', '', 'import java.util.List;', '',
  '/** Sends mail. */', 'public class Mailer<T extends CharSequence> {',
  '    public <R> R send(List<? extends T> to, java.util.function.Function<T, R> each) { return each.apply(to.get(0)); }',
  '    public record Sent(String to, int bytes) {}', '}', '',
].join('\n'));
git(repo, 'init', '-q', '-b', 'main');
git(repo, 'add', '-A');
git(repo, 'commit', '-q', '-m', 'init');

// The AOT caches (CL-150): the daemon makes them in the background some seconds after it started.
async function checkAotCaches(report) {
  const aotDir = path.join(tmp, 'home', 'aot');
  const list = () => (fs.existsSync(aotDir) ? fs.readdirSync(aotDir) : []);
  const began = performance.now();
  while (!list().some(f => f.endsWith('.ready'))) {
    if (performance.now() - began > 300_000) {
      const note = list().filter(f => f.endsWith('.failed')).map(f => fs.readFileSync(path.join(aotDir, f), 'utf8')).join(' ');
      throw new Error(`no AOT caches after 5 minutes; aot/ holds: ${list().join(', ') || 'nothing'}; note: ${note}`);
    }
    await sleep(1000);
  }
  report.aotReadySeconds = Math.round((performance.now() - began) / 1000);
  while (list().some(f => f.endsWith('.lock') || f.endsWith('.trainer')) && performance.now() - began < 300_000) await sleep(500);
  const files = list().map(f => f.replace(/^.*-[0-9a-f]{12}/, '*')).sort();
  if (files.join() !== '*.cli.aot,*.daemon.aot,*.ready') throw new Error(`the AOT caches are not one clean pair: ${files.join(', ')}`);
  const archives = path.join(tmp, 'home', 'cds');
  if (fs.existsSync(archives) && fs.readdirSync(archives).some(f => f.endsWith('.jsa'))) throw new Error('the dynamic archive was left behind');
  const cache = path.join(aotDir, list().find(f => f.endsWith('.cli.aot')));
  report.aotCacheMb = { cli: mb(fs.statSync(cache).size), daemon: mb(fs.statSync(cache.replace('.cli.aot', '.daemon.aot')).size) };

  const flags = ['-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1', '-Xss512k', '-Xmx128m', '-XX:-UsePerfData', '-Xlog:disable'];
  const direct = (extra, jarFile, args) => {
    const started = performance.now();
    const run = spawnSync(bundledJava, [...flags, ...extra, '-jar', jarFile, ...args], { env, cwd: path.join(tmp, 'repo'), encoding: 'utf8' });
    return { code: run.status, out: `${run.stdout ?? ''}${run.stderr ?? ''}`.trim(), ms: performance.now() - started };
  };

  // A copy of the jars elsewhere (other path, other modification times) and damaged files: no error, no word on the output.
  const moved = path.join(tmp, 'moved');
  fs.mkdirSync(path.join(moved, 'lib'), { recursive: true });
  for (const f of fs.readdirSync(path.join(bundle, 'lib'))) fs.copyFileSync(path.join(bundle, 'lib', f), path.join(moved, 'lib', f));
  const movedRun = direct([`-XX:AOTCache=${cache}`], path.join(moved, 'lib', jar), ['find', 'greet']);
  if (movedRun.code !== 0 || !movedRun.out.includes('Greeter')) throw new Error(`a copy of the install did not run with the cache (exit ${movedRun.code}): ${movedRun.out}`);
  const original = fs.readFileSync(cache);
  const without = direct([], jarPath, ['find', 'greet']);
  for (const [name, content] of [['cut', original.subarray(0, original.length / 3)], ['empty', Buffer.alloc(0)], ['garbage', Buffer.alloc(2_000_000, 7)]]) {
    const damaged = path.join(tmp, `damaged-${name}.aot`);
    fs.writeFileSync(damaged, content);
    const run = direct([`-XX:AOTCache=${damaged}`], jarPath, ['find', 'greet']);
    if (run.code !== 0 || run.out !== without.out) throw new Error(`a ${name} cache changed the call (exit ${run.code}): ${run.out}`);
  }

  // Interleaved medians: no archive, the dynamic archive the launchers made before, the AOT cache.
  const dynamic = path.join(tmp, 'dynamic.jsa');
  const variants = { none: ['-Xshare:auto'], dynamic: ['-Xshare:auto', '-XX:+AutoCreateSharedArchive', `-XX:SharedArchiveFile=${dynamic}`], aot: [`-XX:AOTCache=${cache}`] };
  direct(variants.dynamic, jarPath, ['find', 'greet']);
  const times = { none: [], dynamic: [], aot: [] };
  for (let i = 0; i < 12; i++) {
    for (const [name, extra] of Object.entries(variants)) {
      const run = direct(extra, jarPath, ['find', 'greet']);
      if (run.code !== 0 || !run.out.includes('Greeter')) throw new Error(`${name}: find failed (exit ${run.code}): ${run.out}`);
      times[name].push(run.ms);
    }
  }
  const median = xs => [...xs].sort((a, b) => a - b)[xs.length >> 1];
  report.cliMs = Object.fromEntries(Object.entries(times).map(([k, v]) => [k, Math.round(median(v))]));
  report.aotVsDynamicPct = Math.round((1 - report.cliMs.aot / report.cliMs.dynamic) * 100);
  if (report.cliMs.aot >= report.cliMs.dynamic) throw new Error(`the AOT cache is not faster than the dynamic archive: ${JSON.stringify(report.cliMs)}`);
}

const report = { os: process.platform, arch: process.arch, port };
let failure = null;
try {
  // Two first calls at once (an agent fires several): both start the daemon and the class-data archive, both must answer.
  const [first, alongside] = await Promise.all([launchAsync(['outline', 'Greeter'], repo), launchAsync(['find', 'greet'], repo)]);
  report.firstQueryMs = first.ms;
  if (first.code !== 0 || !first.out.includes('greet')) throw new Error(`outline failed (exit ${first.code}): ${first.out}`);
  if (alongside.code !== 0 || !alongside.out.includes('Greeter')) throw new Error(`the first call alongside failed (exit ${alongside.code}): ${alongside.out}`);
  const second = cli(['find', 'greet'], repo);
  report.warmQueryMs = second.ms;
  if (second.code !== 0 || !second.out.includes('Greeter')) throw new Error(`find failed (exit ${second.code}): ${second.out}`);
  // The second file of each language went through the same parser worker as the first.
  const mixed = [['find', 'total'], ['find', 'send'], ['find', 'Sent']].map(args => cli(args, repo));
  for (const [i, needle] of ['Orders', 'Mailer', 'record Sent'].entries()) {
    if (mixed[i].code !== 0 || !mixed[i].out.includes(needle)) throw new Error(`find ${['total', 'send', 'Sent'][i]} failed (exit ${mixed[i].code}): ${mixed[i].out}`);
  }
  // Let the daemon settle after the index build before reading its memory.
  await sleep(2000);
  const status = await (await fetch(`http://127.0.0.1:${port}/status`)).json();
  report.daemonRssMb = status.rssMb;
  report.daemonHeapMb = status.heapMb;
  report.version = status.version;
  const runtimeJava = bundledJava;
  report.runtime = (spawnSync(runtimeJava, ['-version'], { encoding: 'utf8' }).stderr ?? '').split('\n')[0];
  if (flag('aot')) await checkAotCaches(report);
} catch (e) {
  failure = e;
} finally {
  const stop = cli(['stop'], repo);
  report.stopMs = stop.ms;
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
