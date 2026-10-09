#!/usr/bin/env node
// Update test between two builds of the installer (CL-107): installs version N, runs it, lets it update itself to N+1
// from a LOCAL feed (a throwaway HTTP server on 127.0.0.1 serving latest.yml and the installer the build produced;
// never GitHub, never a release), and checks what the card promises:
//   - the app downloaded the installer, verified its SHA-512 and installed it by itself;
//   - the settings file and the stored secret are as they were, the daemon runs the NEW bundle and the index of a
//     repository with an older format is rebuilt;
//   - the updater asked only the feed host (every request is logged), without cookies or an identifying header;
//   - a tampered installer is refused; with automatic updates off the app asks for nothing;
//   - with --bad (an installer whose daemon cannot start) the app falls back to the previous daemon bundle.
//
//   node tools/update-test.mjs --old <installer N> --new <installer N+1> [--bad <installer N+2 with a broken daemon>]
//        [--port 47550] [--out report.json]
// Uses ports <port> (feed) and <port>+1 (daemon), its own CODELOUPE_HOME, app data and updater cache, on a PATH
// without Java. Windows: NSIS installers (silent, into a temporary directory). Linux: AppImages.
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { noJavaEnv } from './no-java-env.mjs';
import { daemonTokenHeader } from './daemon-token.mjs';

const args = process.argv.slice(2);
const opt = name => (args.includes(`--${name}`) ? args[args.indexOf(`--${name}`) + 1] : undefined);
const oldInstaller = opt('old') && path.resolve(opt('old'));
const newInstaller = opt('new') && path.resolve(opt('new'));
const badInstaller = opt('bad') && path.resolve(opt('bad'));
if (!oldInstaller || !newInstaller || ![oldInstaller, newInstaller].every(f => fs.existsSync(f))) {
  console.error('usage: update-test.mjs --old <installer N> --new <installer N+1> [--bad <installer>] [--port 47550] [--out file]');
  process.exit(2);
}
const platform = process.platform;
if (platform !== 'win32' && platform !== 'linux') { console.error(`no update test for ${platform}: macOS only notifies`); process.exit(2); }
const feedPort = Number(opt('port') ?? 47550);
const daemonPort = feedPort + 1;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = msg => console.log(`[update-test] ${msg}`);
const versionOf = file => /CodeLoupe-(.+?)-(?:win|linux)-/.exec(path.basename(file))?.[1];
const [vOld, vNew, vBad] = [oldInstaller, newInstaller, badInstaller].map(f => f && versionOf(f));
if (!vOld || !vNew) { console.error('cannot read the versions from the installer names'); process.exit(2); }

const { env: baseEnv, cleanup: cleanupEnv } = noJavaEnv();
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codeloupe-update-'));
const dirs = { home: path.join(tmp, 'home'), userData: path.join(tmp, 'userdata'), local: path.join(tmp, 'local'), app: path.join(tmp, 'app'), feed: path.join(tmp, 'feed') };
for (const d of [dirs.local, dirs.feed]) fs.mkdirSync(d, { recursive: true });
const feedUrl = `http://127.0.0.1:${feedPort}/`;
const report = { os: platform, from: vOld, to: vNew, bad: vBad ?? null, installerMb: Math.round(fs.statSync(newInstaller).size / 104857.6) / 10, timingsSec: {}, checks: {}, requests: [] };
let clock = performance.now();
/** Seconds since the previous lap, recorded under `name`. */
const lap = name => { const now = performance.now(); report.timingsSec[name] = Math.round((now - clock) / 100) / 10; clock = now; };
const check = (name, ok, detail = '') => {
  report.checks[name] = ok ? 'PASS' : `FAIL ${detail}`;
  log(`${ok ? 'PASS' : 'FAIL'} ${name} ${ok ? '' : detail}`);
  if (!ok) failures.push(name);
};
const failures = [];

// ---- the feed -----------------------------------------------------------------------------------------------------------
let tamper = false;
let served = [];
const server = http.createServer((req, res) => {
  const entry = { method: req.method, path: req.url, headers: { ...req.headers } };
  report.requests.push(entry);
  const file = path.join(dirs.feed, decodeURIComponent(new URL(req.url, feedUrl).pathname).replace(/^\/+/, ''));
  if (!file.startsWith(dirs.feed) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { res.writeHead(404).end(); entry.status = 404; return; }
  let data = fs.readFileSync(file);
  if (tamper && /\.(exe|AppImage)$/.test(file)) { data = Buffer.from(data); data[Math.floor(data.length / 2)] ^= 0xff; }
  const range = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range ?? '');
  if (range) {
    const start = Number(range[1]);
    const end = range[2] ? Math.min(Number(range[2]), data.length - 1) : data.length - 1;
    res.writeHead(206, { 'content-range': `bytes ${start}-${end}/${data.length}`, 'content-length': end - start + 1, 'accept-ranges': 'bytes' });
    res.end(data.subarray(start, end + 1));
    entry.status = 206;
  } else {
    res.writeHead(200, { 'content-length': data.length, 'accept-ranges': 'bytes' });
    res.end(data);
    entry.status = 200;
  }
  served.push(entry.path);
});
await new Promise((resolve, reject) => { server.once('error', reject); server.listen(feedPort, '127.0.0.1', resolve); });

const ymlName = platform === 'win32' ? 'latest.yml' : 'latest-linux.yml';
/** Publishes one installer as the release: its file and the feed file (electron-builder's own, or one made here). */
function publish(installer) {
  for (const f of fs.readdirSync(dirs.feed)) fs.rmSync(path.join(dirs.feed, f), { force: true });
  fs.copyFileSync(installer, path.join(dirs.feed, path.basename(installer)));
  const blockmap = `${installer}.blockmap`;
  if (fs.existsSync(blockmap)) fs.copyFileSync(blockmap, path.join(dirs.feed, path.basename(blockmap)));
  const builderYml = path.join(path.dirname(installer), ymlName);
  const wanted = path.basename(installer);
  if (fs.existsSync(builderYml) && fs.readFileSync(builderYml, 'utf8').includes(wanted)) {
    fs.copyFileSync(builderYml, path.join(dirs.feed, ymlName));
    return 'electron-builder';
  }
  const bytes = fs.readFileSync(installer);
  const yml = `version: ${versionOf(installer)}\nfiles:\n  - url: ${wanted}\n    sha512: ${crypto.createHash('sha512').update(bytes).digest('base64')}\n    size: ${bytes.length}\npath: ${wanted}\nsha512: ${crypto.createHash('sha512').update(bytes).digest('base64')}\nreleaseDate: '${new Date().toISOString()}'\n`;
  fs.writeFileSync(path.join(dirs.feed, ymlName), yml);
  return 'generated';
}

// ---- the installation ---------------------------------------------------------------------------------------------------
const appBin = platform === 'win32' ? path.join(dirs.app, 'CodeLoupe.exe') : path.join(dirs.app, 'CodeLoupe.AppImage');
const resources = platform === 'win32' ? path.join(dirs.app, 'resources') : null;
function run(command, cmdArgs, options = {}) {
  const r = spawnSync(command, cmdArgs, { encoding: 'utf8', env: appEnv(), ...options });
  if (r.status !== 0 && !options.allowFail) throw new Error(`${command} ${cmdArgs.join(' ')} failed (${r.status ?? r.error?.message}): ${r.stdout ?? ''}${r.stderr ?? ''}`);
  return r;
}
function appEnv(extra = {}) {
  const env = { ...baseEnv, CODELOUPE_HOME: dirs.home, CODELOUPE_PORT: String(daemonPort), ...extra };
  if (platform === 'win32') {
    env.LOCALAPPDATA = dirs.local;
  } else {
    env.XDG_CACHE_HOME = dirs.local;
    // A CI runner has no libsecret: the vault is protected by a passphrase, which the CLI and the daemon both get.
    env.CODELOUPE_PASSPHRASE ??= 'update-test-passphrase';
  }
  return env;
}
function install(installer) {
  if (platform === 'win32') {
    run(installer, ['/S', `/D=${dirs.app}`], { env: baseEnv });
  } else {
    fs.mkdirSync(dirs.app, { recursive: true });
    fs.copyFileSync(installer, appBin);
    fs.chmodSync(appBin, 0o755);
  }
  if (!fs.existsSync(appBin)) throw new Error(`installed, but ${appBin} is missing`);
}
/** Where the bundle the daemon runs from lives: the install directory, or (an AppImage) a copy in userData. */
function bundleRoots() {
  const roots = [];
  if (resources) roots.push(path.join(resources, 'codeloupe'));
  const staged = path.join(dirs.userData, 'daemon');
  if (fs.existsSync(staged)) for (const v of fs.readdirSync(staged)) roots.push(path.join(staged, v));
  return roots;
}
const jarVersion = root => { try { return /^codeloupe-(.+)\.jar$/.exec(fs.readdirSync(path.join(root, 'lib')).find(f => /^codeloupe-.+\.jar$/.test(f)))?.[1]; } catch { return undefined; } };
const sha256 = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const installerHash = new Map([oldInstaller, newInstaller, badInstaller].filter(Boolean).map(f => [f, sha256(f)]));
/** Whether the installation on disk is this installer's build: the daemon bundle's version, or (an AppImage) the file itself. */
const isInstalled = (installer, version) => (resources ? jarVersion(path.join(resources, 'codeloupe')) === version : sha256(appBin) === installerHash.get(installer));
/** Windows: the installer the app started is still working, in the updater's cache directory. */
const installerRunning = () => platform === 'win32' && processLines(dirs.local).length > 0;

let app = null;
function startApp(extra = {}) {
  const out = fs.openSync(path.join(tmp, 'app.log'), 'a');
  const appArgs = [`--user-data-dir=${dirs.userData}`];
  const env = appEnv({
    CODELOUPE_UPDATE_FEED: feedUrl, CODELOUPE_UPDATE_INSTALL: '1', CODELOUPE_UPDATE_DELAY_MS: '4000',
    // The installer would start the new app through the shell with the user's own environment, an AppImage without the
    // arguments of this test: the harness starts the new version itself.
    CODELOUPE_UPDATE_RELAUNCH: '0',
    ...extra,
  });
  if (platform === 'linux') {
    appArgs.push('--no-sandbox');
    env.DISPLAY ??= ':99';
    env.APPIMAGE_EXTRACT_AND_RUN = '1';
  }
  app = spawn(appBin, appArgs, { env, stdio: ['ignore', out, out], detached: platform !== 'win32' });
  return app;
}
const appAlive = () => app && app.exitCode === null && app.signalCode === null;
function killApp() {
  if (!app?.pid) return;
  if (platform === 'win32') spawnSync('taskkill', ['/PID', String(app.pid), '/T', '/F']);
  else try { process.kill(-app.pid, 'SIGKILL'); } catch { /* gone */ }
}
const status = () => fetch(`http://127.0.0.1:${daemonPort}/status`, { signal: AbortSignal.timeout(2000) }).then(r => r.json()).catch(() => null);
async function waitFor(what, fn, seconds = 120) {
  for (let i = 0; i < seconds; i++) {
    const v = await fn();
    if (v) return v;
    await sleep(1000);
  }
  throw new Error(`timed out waiting for ${what}\n${tail(path.join(tmp, 'app.log'))}\n${tail(path.join(dirs.userData, 'update', 'update.log'))}`);
}
function tail(file, lines = 20) {
  try { return fs.readFileSync(file, 'utf8').split(/\r?\n/).slice(-lines).join('\n'); } catch { return '(no log)'; }
}
const updateFile = name => path.join(dirs.userData, 'update', name);
const updateLog = () => { try { return fs.readFileSync(updateFile('update.log'), 'utf8'); } catch { return ''; } };
function processLines(like) {
  if (platform === 'win32') {
    const script = `Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -like '${like.replace(/'/g, "''")}*' } | ForEach-Object { "$($_.ProcessId)|$($_.ExecutablePath)|$($_.CommandLine)" }`;
    return run('powershell.exe', ['-NoProfile', '-Command', script], { env: process.env }).stdout.split(/\r?\n/).filter(Boolean);
  }
  return run('pgrep', ['-af', like], { allowFail: true, env: process.env }).stdout.split(/\r?\n/).filter(Boolean);
}
const daemonCommandLine = pid => platform === 'win32'
  ? run('powershell.exe', ['-NoProfile', '-Command', `(Get-CimInstance Win32_Process -Filter "ProcessId=${pid}").CommandLine`], { env: process.env }).stdout.trim()
  : run('ps', ['-o', 'command=', '-p', String(pid)], { env: process.env }).stdout.trim();

// ---- the bundled CLI and MCP ----------------------------------------------------------------------------------------------
function cliPrefix(root) {
  const jar = fs.readdirSync(path.join(root, 'lib')).find(f => /^codeloupe-.+\.jar$/.test(f));
  const java = path.join(root, 'runtime', 'bin', platform === 'win32' ? 'java.exe' : 'java');
  return [java, ['-XX:+UseSerialGC', '-Xmx128m', '-jar', path.join(root, 'lib', jar)]];
}
function cli(root, cliArgs, options = {}) {
  const [java, pre] = cliPrefix(root);
  return spawnSync(java, [...pre, ...cliArgs], { env: appEnv(), encoding: 'utf8', input: options.input, cwd: options.cwd });
}
async function mcpFind(repo) {
  const r = await fetch(`http://127.0.0.1:${daemonPort}/mcp`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'x-codeloupe': '1', ...daemonTokenHeader(dirs.home) },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'find', arguments: { root: repo, q: 'greet' } } }),
    signal: AbortSignal.timeout(90_000),
  });
  const body = await r.json();
  return body.result?.content?.map(c => c.text).join('\n') ?? JSON.stringify(body);
}
/** An AppImage's files without running it: --appimage-extract into a directory of its own. */
function unpackAppImage() {
  const dir = path.join(tmp, 'unpacked');
  fs.mkdirSync(dir, { recursive: true });
  run(appBin, ['--appimage-extract'], { cwd: dir });
  return path.join(dir, 'squashfs-root', 'resources', 'codeloupe');
}
function makeFixture() {
  const repo = path.join(tmp, 'repo');
  fs.mkdirSync(path.join(repo, 'src'), { recursive: true });
  fs.writeFileSync(path.join(repo, 'src', 'Greeter.kt'), 'package demo\n\nclass Greeter {\n    fun greet(name: String): String = "Hello, $name"\n}\n');
  for (const a of [['init', '-q', '-b', 'main'], ['add', '-A'], ['-c', 'user.email=t@example.com', '-c', 'user.name=t', 'commit', '-q', '-m', 'init']]) run('git', a, { cwd: repo, env: process.env });
  return repo;
}
const repoJson = () => {
  const dir = path.join(dirs.home, 'repos');
  const id = fs.readdirSync(dir).find(d => fs.existsSync(path.join(dir, d, 'repo.json')));
  return id ? path.join(dir, id, 'repo.json') : null;
};

// ---- the test -------------------------------------------------------------------------------------------------------------
const SECRET_NAME = 'CL_UPDATE_TEST_SECRET';
const SECRET_VALUE = `test-secret-${crypto.randomBytes(6).toString('hex')}`;
// The script avoids double quotes: the CLI hands its arguments on to the child process through Windows' quoting rules.
const secretProbe = `process.exit(process.env.${SECRET_NAME}===Buffer.from('${Buffer.from(SECRET_VALUE).toString('hex')}','hex').toString()?0:3)`;
const SETTINGS = { apiSource: 'daemon', theme: 'dark', shortcuts: false, portOverride: daemonPort, notify: { budget: true, builds: false, gaps: false, daemon: true } };
let fatal = null;
try {
  // -- N is installed with data of its own: settings, a secret, an indexed repository.
  const repo = makeFixture();
  install(oldInstaller);
  fs.mkdirSync(dirs.userData, { recursive: true });
  fs.writeFileSync(path.join(dirs.userData, 'settings.json'), JSON.stringify(SETTINGS, null, 2));
  // The CLI of N: from the install directory, or from an AppImage unpacked on the side.
  const bundleN = resources ? path.join(resources, 'codeloupe') : unpackAppImage();
  check('installed version N', jarVersion(bundleN) === vOld, `bundle says ${jarVersion(bundleN)}`);
  const set = cli(bundleN, ['env', 'set', SECRET_NAME, '--scope', 'global'], { input: SECRET_VALUE });
  check('secret stored by N', set.status === 0, `${set.stdout}${set.stderr}`);
  const found = cli(bundleN, ['find', 'greet'], { cwd: repo });
  check('N indexes a repository', found.status === 0 && found.stdout.includes('Greeter'), `${found.stdout}${found.stderr}`);
  const statusN = await status();
  check('daemon of N runs', statusN?.version === vOld, `/status says ${statusN?.version}`);

  // -- N starts, finds the release in the local feed, downloads, verifies and installs it.
  const how = publish(newInstaller);
  report.feedFile = how;
  // A tampered installer must be refused: same size, one byte flipped, the feed's SHA-512 is the original's.
  tamper = true;
  startApp();
  await waitFor('the refusal of the tampered installer', () => /sha512|checksum/i.test(updateLog()) && /state error/.test(updateLog()), 120);
  check('tampered installer refused', true);
  check('nothing installed after the refusal', isInstalled(oldInstaller, vOld) && appAlive() && !fs.existsSync(updateFile('pending.json')));
  killApp();
  await sleep(2000);
  tamper = false;
  fs.rmSync(updateFile('update.log'), { force: true });

  // -- Automatic updates off: the app asks for nothing.
  fs.writeFileSync(path.join(dirs.userData, 'settings.json'), JSON.stringify({ ...SETTINGS, autoUpdate: false }, null, 2));
  const before = report.requests.length;
  startApp();
  await waitFor('the daemon', async () => (await status())?.name === 'codeloupe', 90);
  await sleep(12_000);
  check('no request while automatic updates are off', report.requests.length === before, JSON.stringify(report.requests.slice(before)));
  killApp();
  await sleep(2000);
  fs.writeFileSync(path.join(dirs.userData, 'settings.json'), JSON.stringify(SETTINGS, null, 2));

  // -- The real update.
  const mark = report.requests.length;
  clock = performance.now();
  startApp();
  const pendingSeen = await waitFor('the downloaded update', () => fs.existsSync(updateFile('pending.json')) && JSON.parse(fs.readFileSync(updateFile('pending.json'), 'utf8')), 180);
  lap('appStartToDownloadedAndBackedUp');
  check('previous daemon bundle kept before the install', fs.existsSync(path.join(dirs.userData, 'update', 'previous', 'codeloupe', '.complete')) && jarVersion(path.join(dirs.userData, 'update', 'previous', 'codeloupe')) === vOld);
  check('pending record names both versions', pendingSeen.from === vOld && pendingSeen.to === vNew, JSON.stringify(pendingSeen));
  await waitFor('the installer to replace the app', () => isInstalled(newInstaller, vNew) && !installerRunning(), 180);
  lap('installerRun');
  await waitFor('the app to quit', () => !appAlive(), 60);
  await sleep(1500);
  const updateRequests = report.requests.slice(mark);
  const sums = updateRequests.filter(r => r.status === 200 || r.status === 206).map(r => r.path);
  report.updateRequests = updateRequests.map(r => ({ method: r.method, path: r.path, status: r.status, ua: r.headers['user-agent'], staging: r.headers['x-user-staging-id'], cookie: !!r.headers.cookie, auth: !!r.headers.authorization }));
  check('the updater asked only the feed, for the feed file and the installer', updateRequests.length > 0 && updateRequests.every(r => r.headers.host === `127.0.0.1:${feedPort}` && (new URL(r.path, feedUrl).pathname === `/${ymlName}` || /^\/CodeLoupe-[^/]+\.(exe|AppImage)(\.blockmap)?$/.test(r.path))), JSON.stringify(sums));
  // Standard HTTP headers (host, accept-encoding, sec-fetch-*) aside, nothing is sent but a bare User-Agent, an English
  // Accept-Language (not the system's) and a constant where electron-updater would put a per-installation id.
  const plain = new Set(['host', 'connection', 'accept', 'accept-encoding', 'accept-language', 'cache-control', 'user-agent', 'range', 'if-range', 'x-user-staging-id', 'sec-fetch-site', 'sec-fetch-mode', 'sec-fetch-dest']);
  check('no cookie, no credentials, no identifying header', updateRequests.every(r => Object.keys(r.headers).every(h => plain.has(h)) && r.headers['user-agent'] === 'CodeLoupe' && r.headers['accept-language'] === 'en' && (r.headers['x-user-staging-id'] === undefined || /^0{8}-0{4}-4000-8000-0{12}$/.test(r.headers['x-user-staging-id']))), JSON.stringify(updateRequests.map(r => r.headers)));
  // The NSIS installer stops the daemon of the installation it replaces; an AppImage cannot, so there the new app restarts it.
  const afterSwap = await status();
  check(platform === 'win32' ? 'the old daemon was stopped for the swap' : 'the old daemon still runs until the new app restarts it', platform === 'win32' ? afterSwap === null : afterSwap?.version === vOld, JSON.stringify(afterSwap?.version));

  // -- N+1 starts: the same data, the new daemon.
  const repoFile = repoJson();
  const rec = repoFile ? JSON.parse(fs.readFileSync(repoFile, 'utf8')) : null;
  if (rec?.format) {
    // An index written by an older format: the new daemon has to rebuild it.
    report.formatBefore = rec.format;
    fs.writeFileSync(repoFile, JSON.stringify({ ...rec, format: '1/older-extractor' }));
  }
  clock = performance.now();
  startApp();
  // An AppImage cannot stop the daemon it replaces: the old one answers until the new app has restarted it.
  const statusNew = await waitFor('the daemon of N+1', async () => { const s = await status(); return s?.name === 'codeloupe' && s.version === vNew && s; }, 120);
  lap('newAppStartToDaemonUp');
  check('daemon runs the new version', statusNew.version === vNew, `/status says ${statusNew.version}`);
  const cmdline = daemonCommandLine(statusNew.pid);
  check('daemon runs from the new bundle', cmdline.includes(`codeloupe-${vNew}.jar`) && !cmdline.includes(`codeloupe-${vOld}.jar`), cmdline);
  check('settings survived', (() => { const s = JSON.parse(fs.readFileSync(path.join(dirs.userData, 'settings.json'), 'utf8')); return s.theme === 'dark' && s.shortcuts === false && s.notify?.gaps === false && s.portOverride === daemonPort; })());
  const bundleNew = resources ? path.join(resources, 'codeloupe') : bundleRoots().find(r => jarVersion(r) === vNew);
  const secret = cli(bundleNew, ['env', 'run', '--', process.execPath, '-e', secretProbe]);
  check('secret survived and is readable by N+1', secret.status === 0, `exit ${secret.status} ${secret.stdout}${secret.stderr}`);
  const text = await mcpFind(repo);
  check('queries work and the index was rebuilt', text.includes('Greeter'), text);
  if (rec?.format) {
    await waitFor('the index format to be rewritten', () => JSON.parse(fs.readFileSync(repoFile, 'utf8')).format === rec.format, 60).then(() => check('index of the older format rebuilt', true), e => check('index of the older format rebuilt', false, e.message.split('\n')[0]));
  }
  await waitFor('the update record to be closed', () => !fs.existsSync(updateFile('pending.json')) && !fs.existsSync(path.join(dirs.userData, 'update', 'previous')), 60)
    .then(() => check('first run verified, old bundle deleted', true), e => check('first run verified, old bundle deleted', false, e.message.split('\n')[0]));
  await waitFor('the new app to check the feed', () => /state idle/.test(updateLog()), 60)
    .then(() => check('the new app finds nothing newer', true), e => check('the new app finds nothing newer', false, e.message.split(/\r?\n/)[0]));

  // -- A build whose daemon cannot start: the previous bundle runs.
  if (badInstaller) {
    killApp();
    await sleep(2000);
    publish(badInstaller);
    startApp();
    await waitFor('the update to the bad build', () => isInstalled(badInstaller, vBad) && !installerRunning(), 240);
    await waitFor('the app to quit', () => !appAlive(), 60);
    await sleep(1500);
    clock = performance.now();
    startApp({ CODELOUPE_UPDATE_FEED: '' });
    const record = await waitFor('the rollback record', () => fs.existsSync(updateFile('rollback.json')) && JSON.parse(fs.readFileSync(updateFile('rollback.json'), 'utf8')), 180);
    const rolled = await waitFor('the previous daemon', async () => { const s = await status(); return s?.name === 'codeloupe' && s.version === vNew && s; }, 90);
    lap('brokenBuildStartToPreviousDaemonUp');
    check('failed start rolled back to the previous daemon', rolled.version === vNew, `/status says ${rolled.version}`);
    const cl = daemonCommandLine(rolled.pid);
    check('the previous bundle runs', cl.includes(`codeloupe-${vNew}.jar`) && cl.replaceAll('\\', '/').includes('/update/previous/'), cl);
    check('rollback recorded', record.failedVersion === vBad && record.usingVersion === vNew, JSON.stringify(record));
    check('the secret is still readable after the rollback', cli(path.join(dirs.userData, 'update', 'previous', 'codeloupe'), ['env', 'run', '--', process.execPath, '-e', secretProbe]).status === 0);
  }
} catch (e) {
  fatal = e;
  failures.push('fatal');
  console.error(e.message);
} finally {
  killApp();
  try {
    if (resources && fs.existsSync(path.join(resources, 'codeloupe', 'lib'))) cli(path.join(resources, 'codeloupe'), ['stop']);
    for (const root of fs.existsSync(path.join(dirs.userData, 'update', 'previous', 'codeloupe')) ? [path.join(dirs.userData, 'update', 'previous', 'codeloupe')] : []) cli(root, ['stop']);
    for (const root of bundleRoots()) cli(root, ['stop']);
  } catch { /* nothing left to stop */ }
  await sleep(1500);
  if (platform === 'win32') {
    const un = path.join(dirs.app, 'Uninstall CodeLoupe.exe');
    if (fs.existsSync(un)) spawnSync(un, ['/S'], { env: baseEnv });
    for (let i = 0; i < 60 && fs.existsSync(appBin); i++) await sleep(1000);
  }
  server.close();
  report.ok = failures.length === 0;
  report.failures = failures;
  console.log(JSON.stringify({ ...report, requests: undefined }, null, 2));
  if (opt('out')) fs.writeFileSync(opt('out'), JSON.stringify(report, null, 2) + '\n');
  if (fatal) console.error(fatal.stack);
  try { cleanupEnv(); fs.rmSync(tmp, { recursive: true, force: true }); } catch { /* a stopped process may still hold a file on Windows */ }
  process.exit(failures.length === 0 ? 0 : 1);
}
