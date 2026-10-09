#!/usr/bin/env node
// Smoke test of an Electron installer (CL-108): installs it silently, starts the app, waits for the daemon the app
// starts from the bundled runtime, runs `find` on a fixture repository through the CLI and through the MCP endpoint,
// takes a screenshot of the screen where a CI runner has one, stops everything and uninstalls. Everything runs on a PATH
// without Java and with its own CODELOUPE_HOME, port and app data, so a developer's own daemon is never touched.
//
//   node tools/installer-smoke.mjs <installer file or the directory holding it> [--shots <dir>] [--out report.json]
//                                  [--expect-version <version>]   (a release build: installer name, daemon, /status and CLI must say so)
//
// Windows: NSIS installer, silent, into a temporary directory. Linux: the .deb through apt (needs sudo), the app on a
// virtual display. macOS: the .dmg, the app copied to a temporary directory. Both of those only notify about a newer
// release (CL-107): a release list on 127.0.0.1 offers v99.0.0 and the app must say so (Windows updates itself, and
// tools/update-test.mjs covers that).
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { noJavaEnv } from './no-java-env.mjs';
import { daemonTokenHeader } from './daemon-token.mjs';

const [target, ...rest] = process.argv.slice(2);
const opt = name => (rest.includes(`--${name}`) ? rest[rest.indexOf(`--${name}`) + 1] : undefined);
if (!target || !fs.existsSync(target)) { console.error('usage: installer-smoke.mjs <installer or directory> [--shots dir] [--out file]'); process.exit(2); }
const platform = process.platform;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ext = { win32: '.exe', linux: '.deb', darwin: '.dmg' }[platform];
const installer = path.resolve(fs.statSync(target).isDirectory()
  ? path.join(target, fs.readdirSync(target).find(f => f.endsWith(ext)) ?? (() => { throw new Error(`no ${ext} in ${target}`); })())
  : target);

const { env: baseEnv, cleanup: cleanupEnv } = noJavaEnv();
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codeloupe-installer-'));
const port = await new Promise(resolve => { const s = net.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); }); });
const home = path.join(tmp, 'home');
const userData = path.join(tmp, 'userdata');
const env = { ...baseEnv, CODELOUPE_HOME: home, CODELOUPE_PORT: String(port) };
const shotsDir = opt('shots') ? path.resolve(opt('shots')) : null;
if (shotsDir) fs.mkdirSync(shotsDir, { recursive: true });

const report = { os: platform, arch: process.arch, installer: path.basename(installer), installerMb: Math.round(fs.statSync(installer).size / 104857.6) / 10, port, steps: {} };
const log = msg => console.log(`[installer-smoke] ${msg}`);
async function step(name, fn) {
  const started = performance.now();
  log(`${name} ...`);
  const result = await fn();
  report.steps[name] = Math.round(performance.now() - started);
  return result;
}
function run(command, args, options = {}) {
  const r = spawnSync(command, args, { encoding: 'utf8', env, ...options });
  if (r.status !== 0 && !options.allowFail) throw new Error(`${command} ${args.join(' ')} failed (${r.status ?? r.error?.message}): ${r.stdout ?? ''}${r.stderr ?? ''}`);
  return r;
}
const status = () => fetch(`http://127.0.0.1:${port}/status`, { signal: AbortSignal.timeout(2000) }).then(r => r.json()).catch(() => null);

// ---- install ----------------------------------------------------------------------------------------------------------
let appDir;       // directory that holds the executable
let appBin;       // the executable
let resources;    // <install>/resources (macOS: Contents/Resources)
let uninstall;
const debPackage = platform === 'linux' ? run('dpkg-deb', ['-f', installer, 'Package']).stdout.trim() : null;

async function install() {
  if (platform === 'win32') {
    appDir = path.join(tmp, 'app');
    run(installer, ['/S', `/D=${appDir}`]);
    appBin = path.join(appDir, 'CodeLoupe.exe');
    resources = path.join(appDir, 'resources');
    uninstall = async () => {
      spawn(path.join(appDir, 'Uninstall CodeLoupe.exe'), ['/S'], { stdio: 'ignore', detached: true }).unref();
      // The uninstaller runs a copy of itself and returns at once: wait for the files to go.
      for (let i = 0; i < 120 && fs.existsSync(appBin); i++) await sleep(1000);
    };
  } else if (platform === 'linux') {
    run('sudo', ['apt-get', 'install', '-y', installer], { env: process.env });
    appDir = '/opt/CodeLoupe';
    appBin = path.join(appDir, 'codeloupe-desktop');
    resources = path.join(appDir, 'resources');
    uninstall = async () => { run('sudo', ['apt-get', 'remove', '-y', debPackage], { env: process.env }); };
  } else {
    const mount = path.join(tmp, 'dmg');
    fs.mkdirSync(mount);
    run('hdiutil', ['attach', installer, '-nobrowse', '-readonly', '-mountpoint', mount]);
    const apps = path.join(tmp, 'Applications');
    fs.mkdirSync(apps);
    try { run('cp', ['-R', path.join(mount, 'CodeLoupe.app'), apps]); } finally { run('hdiutil', ['detach', mount, '-force'], { allowFail: true }); }
    appDir = path.join(apps, 'CodeLoupe.app');
    appBin = path.join(appDir, 'Contents', 'MacOS', 'CodeLoupe');
    resources = path.join(appDir, 'Contents', 'Resources');
    // Signed ad hoc (CL-130): Apple Silicon does not run an unsigned app, and the copy must still verify.
    run('codesign', ['--verify', '--deep', '--strict', appDir]);
    if (!run('codesign', ['-dv', appDir]).stderr.includes('Signature=adhoc')) throw new Error('the installed app is not signed ad hoc');
    report.signature = 'adhoc';
    uninstall = async () => { fs.rmSync(appDir, { recursive: true, force: true }); };
  }
  if (!fs.existsSync(appBin)) throw new Error(`installed, but ${appBin} is missing`);
  if (!fs.existsSync(path.join(resources, 'claude-plugin', '.claude-plugin', 'marketplace.json'))) throw new Error('the Claude Code plugin is not installed next to the app');
}

// ---- the app ----------------------------------------------------------------------------------------------------------
let app = null;
let xvfb = null;
let releases = null;
/** A release list with one newer release, in GitHub's atom format, on a loopback port. */
async function serveReleases() {
  releases = http.createServer((_req, res) => {
    res.writeHead(200, { 'content-type': 'application/atom+xml' });
    res.end('<feed><entry><link rel="alternate" type="text/html" href="http://127.0.0.1/releases/tag/v99.0.0"/></entry></feed>');
  });
  await new Promise(resolve => releases.listen(0, '127.0.0.1', resolve));
  return releases.address().port;
}
async function startApp() {
  const appEnv = { ...env };
  if (platform !== 'win32') {
    appEnv.CODELOUPE_UPDATE_FEED = `http://127.0.0.1:${await serveReleases()}/`;
    appEnv.CODELOUPE_UPDATE_DELAY_MS = '2000';
  }
  const args = [`--user-data-dir=${userData}`];
  if (platform === 'linux') {
    // A virtual display for the window, and no Chromium sandbox: the runner's user namespaces are restricted.
    xvfb = spawn('Xvfb', [':99', '-screen', '0', '1280x800x24'], { stdio: 'ignore' });
    xvfb.on('error', e => { throw new Error(`Xvfb: ${e.message}`); });
    await sleep(1500);
    appEnv.DISPLAY = ':99';
    args.push('--no-sandbox');
  }
  const out = fs.openSync(path.join(tmp, 'app.log'), 'w');
  app = spawn(appBin, args, { env: appEnv, stdio: ['ignore', out, out], detached: platform !== 'win32' });
  let exited = null;
  app.on('exit', (code, signal) => { exited = `exit ${code ?? signal}`; });
  for (let i = 0; i < 180; i++) {
    const s = await status();
    if (s?.name === 'codeloupe') return s;
    if (exited) throw new Error(`the app ended (${exited}) before the daemon came up:\n${tail(path.join(tmp, 'app.log'))}`);
    await sleep(1000);
  }
  throw new Error(`no daemon on port ${port} 180 s after the app started:\n${tail(path.join(tmp, 'app.log'))}\n${tail(path.join(home, 'daemon.log'))}`);
}
function tail(file, lines = 25) {
  try { return fs.readFileSync(file, 'utf8').split(/\r?\n/).slice(-lines).join('\n'); } catch { return '(no log)'; }
}

/** The executable of a process, to prove the daemon runs from the installed runtime and not from a JDK. */
function executableOf(pid) {
  if (platform === 'win32') return run('powershell.exe', ['-NoProfile', '-Command', `(Get-Process -Id ${pid}).Path`]).stdout.trim();
  return run('ps', ['-o', 'command=', '-p', String(pid)]).stdout.trim();
}

// ---- queries ----------------------------------------------------------------------------------------------------------
const bundled = () => path.join(resources, 'codeloupe');
const javaName = platform === 'win32' ? 'java.exe' : 'java';
/** The bundled CLI. Node cannot start a .bat without a shell, so Windows runs the runtime's java on the jar (as the app does). */
function cli(args, cwd) {
  if (platform === 'win32') {
    const jar = fs.readdirSync(path.join(bundled(), 'lib')).find(f => /^codeloupe-.*\.jar$/.test(f));
    return spawnSync(path.join(bundled(), 'runtime', 'bin', javaName), ['-XX:+UseSerialGC', '-Xmx128m', '-jar', path.join(bundled(), 'lib', jar), ...args], { env, cwd, encoding: 'utf8' });
  }
  return spawnSync(path.join(bundled(), 'bin', 'codeloupe'), args, { env, cwd, encoding: 'utf8' });
}
async function mcp(tool, args) {
  const r = await fetch(`http://127.0.0.1:${port}/mcp`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'x-codeloupe': '1', ...daemonTokenHeader(home) },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: tool, arguments: args } }),
    signal: AbortSignal.timeout(60_000),
  });
  const body = await r.json();
  if (body.error || body.result?.isError) throw new Error(`MCP ${tool}: ${JSON.stringify(body.error ?? body.result)}`);
  return body.result.content.map(c => c.text).join('\n');
}

function makeFixture() {
  const repo = path.join(tmp, 'repo');
  fs.mkdirSync(path.join(repo, 'src'), { recursive: true });
  fs.writeFileSync(path.join(repo, 'src', 'Greeter.kt'), 'package demo\n\nclass Greeter {\n    fun greet(name: String): String = "Hello, $name"\n}\n');
  for (const args of [['init', '-q', '-b', 'main'], ['add', '-A'], ['-c', 'user.email=t@example.com', '-c', 'user.name=t', 'commit', '-q', '-m', 'init']]) run('git', args, { cwd: repo });
  return repo;
}

// ---- screenshot (best effort: a runner without a screen does not fail the test) -----------------------------------------
// The whole screen is captured, so only where CI says nobody's own desktop is on it.
function screenshot(name) {
  if (!shotsDir || !process.env.CI) return;
  const file = path.join(shotsDir, `${name}-${platform}.png`);
  try {
    if (platform === 'win32') {
      const script = `Add-Type -AssemblyName System.Windows.Forms,System.Drawing; $b=[System.Windows.Forms.SystemInformation]::VirtualScreen; $bmp=New-Object System.Drawing.Bitmap $b.Width,$b.Height; $g=[System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($b.Location,[System.Drawing.Point]::Empty,$b.Size); $bmp.Save('${file}')`;
      run('powershell.exe', ['-NoProfile', '-Command', script]);
    } else if (platform === 'darwin') {
      run('screencapture', ['-x', file]);
    } else {
      run('import', ['-display', ':99', '-window', 'root', file]);
    }
    report.screenshot = path.basename(file);
  } catch (e) {
    log(`no screenshot: ${e.message.split('\n')[0]}`);
  }
}

// ---- the test ---------------------------------------------------------------------------------------------------------
let failure = null;
try {
  const repo = makeFixture();
  await step('install', install);
  report.installedMb = Math.round(dirSize(appDir) / 104857.6) / 10;

  const daemon = await step('app starts daemon', startApp);
  report.version = daemon.version;
  const exe = executableOf(daemon.pid);
  report.daemonExecutable = exe;
  if (!exe.replaceAll('\\', '/').toLowerCase().includes('/resources/codeloupe/runtime/bin/java')) throw new Error(`the daemon does not run from the installed runtime: ${exe}`);

  const expected = opt('expect-version');
  if (expected) {
    if (!path.basename(installer).includes(expected)) throw new Error(`the installer ${path.basename(installer)} does not carry version ${expected}`);
    if (daemon.version !== expected) throw new Error(`/status says version ${daemon.version}, expected ${expected}`);
    const v = cli(['--version'], tmp);
    if (!`${v.stdout}`.includes(expected)) throw new Error(`the CLI says "${v.stdout.trim()}", expected version ${expected}`);
    report.expectedVersion = expected;
  }

  await step('cli find', async () => {
    const r = cli(['find', 'greet'], repo);
    if (r.status !== 0 || !r.stdout.includes('Greeter')) throw new Error(`find through the CLI failed (exit ${r.status}): ${r.stdout}${r.stderr}`);
  });
  await step('mcp find', async () => {
    const text = await mcp('find', { root: repo, q: 'greet' });
    if (!text.includes('Greeter')) throw new Error(`find through MCP did not return the fixture: ${text}`);
  });

  if (platform !== 'win32') {
    await step('update notice', async () => {
      const log = path.join(userData, 'update', 'update.log');
      for (let i = 0; i < 60; i++) {
        if (fs.existsSync(log) && /state available latest=99\.0\.0/.test(fs.readFileSync(log, 'utf8'))) return;
        await sleep(1000);
      }
      throw new Error(`the app did not report the newer release:
${tail(log)}`);
    });
  }

  await sleep(3000);
  screenshot('app');
  report.rssMb = (await status())?.rssMb;
} catch (e) {
  failure = e;
  screenshot('failure');
} finally {
  // Stop the app and its daemon, then uninstall: the last part is a test step of its own.
  try {
    if (app?.pid) {
      if (platform === 'win32') spawnSync('taskkill', ['/PID', String(app.pid), '/T', '/F']);
      else try { process.kill(-app.pid, 'SIGKILL'); } catch { /* already gone */ }
    }
    if (xvfb) xvfb.kill();
    releases?.close();
    if (resources) cli(['stop'], tmp);
    await sleep(1000);
    if (uninstall) {
      await step('uninstall', uninstall);
      if (fs.existsSync(appBin)) throw new Error('the uninstaller left the app behind');
      if (await status()) throw new Error('a daemon still answers after the uninstall');
      if (!fs.existsSync(home)) log('note: the data directory is gone');
    }
  } catch (e) {
    failure ??= e;
  }
}

function dirSize(dir) {
  let total = 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    total += entry.isDirectory() ? dirSize(p) : entry.isFile() ? fs.statSync(p).size : 0;
  }
  return total;
}

report.ok = !failure;
console.log(JSON.stringify(report, null, 2));
if (opt('out')) fs.writeFileSync(opt('out'), JSON.stringify(report, null, 2) + '\n');
if (failure) console.error(failure.message);
try { cleanupEnv(); fs.rmSync(tmp, { recursive: true, force: true }); } catch { /* a stopped process may still hold a file on Windows */ }
process.exit(failure ? 1 : 0);
