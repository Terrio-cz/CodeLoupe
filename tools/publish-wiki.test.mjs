import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { gitEnv, listFiles, publishWiki, PublishError, redact } from './publish-wiki.mjs';

const tmp = prefix => fs.mkdtempSync(path.join(os.tmpdir(), prefix));
const git = (cwd, ...args) => execFileSync('git', args, {
  cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
  env: { ...process.env, GIT_AUTHOR_NAME: 't', GIT_AUTHOR_EMAIL: 't@example.com', GIT_COMMITTER_NAME: 't', GIT_COMMITTER_EMAIL: 't@example.com' },
}).trim();

/** A bare repository standing in for the wiki; `initial` pages are committed on master like GitHub's first page. */
function wiki(initial = { 'Home.md': 'Welcome to the wiki!\n' }) {
  const bare = path.join(tmp('wiki-remote-'), 'wiki.git');
  fs.mkdirSync(bare);
  git(bare, 'init', '--bare', '--quiet', '--initial-branch=master');
  if (initial) {
    const seed = tmp('wiki-seed-');
    git(seed, 'init', '--quiet', '--initial-branch=master');
    for (const [name, text] of Object.entries(initial)) fs.writeFileSync(path.join(seed, name), text);
    git(seed, 'add', '--all');
    git(seed, 'commit', '--quiet', '-m', 'Initial Home page');
    git(seed, 'push', '--quiet', bare, 'master');
  }
  return bare;
}

function source(files) {
  const dir = tmp('wiki-src-');
  for (const [name, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true });
    fs.writeFileSync(path.join(dir, name), text);
  }
  return dir;
}

const pagesOf = bare => {
  const check = tmp('wiki-check-');
  git(check, '-c', 'core.autocrlf=false', 'clone', '--quiet', bare, '.');
  return Object.fromEntries(listFiles(check).filter(f => !f.startsWith('.git/')).map(f => [f, fs.readFileSync(path.join(check, f), 'utf8')]));
};
const headOf = bare => git(bare, 'rev-parse', 'master');
const run = (src, bare, extra = {}) => publishWiki({ source: src, remote: bare, env: { PATH: process.env.PATH, HOME: process.env.HOME, USERPROFILE: process.env.USERPROFILE, SystemRoot: process.env.SystemRoot }, ...extra });

test('publishes the sources over what the wiki held', () => {
  const bare = wiki();
  const src = source({ 'Home.md': 'Home\n', 'Guide.md': '# Guide\n', '_Sidebar.md': '* [Home](Home)\n' });
  assert.equal(run(src, bare).status, 'published');
  assert.deepEqual(pagesOf(bare), { 'Home.md': 'Home\n', 'Guide.md': '# Guide\n', '_Sidebar.md': '* [Home](Home)\n' });
  assert.equal(git(bare, 'log', '-1', '--format=%an', 'master'), 'github-actions[bot]');
});

test('running it again changes nothing', () => {
  const bare = wiki();
  const src = source({ 'Home.md': 'Home\n', 'Guide.md': '# Guide\n' });
  run(src, bare);
  const head = headOf(bare);
  assert.equal(run(src, bare).status, 'unchanged');
  assert.equal(headOf(bare), head);
});

test('a page removed from the sources is removed from the wiki', () => {
  const bare = wiki();
  const src = source({ 'Home.md': 'Home\n', 'Old.md': 'old\n' });
  run(src, bare);
  fs.rmSync(path.join(src, 'Old.md'));
  assert.equal(run(src, bare).status, 'published');
  assert.deepEqual(Object.keys(pagesOf(bare)), ['Home.md']);
});

test('a page added in the web UI is not kept: the wiki mirrors the sources', () => {
  const bare = wiki({ 'Home.md': 'x\n', 'Typed-in-browser.md': 'y\n' });
  run(source({ 'Home.md': 'Home\n' }), bare);
  assert.deepEqual(Object.keys(pagesOf(bare)), ['Home.md']);
});

test('keeps subfolders and file contents byte for byte', () => {
  const bare = wiki();
  const src = source({ 'Home.md': 'a\r\nb\n', 'images/x.txt': 'x' });
  run(src, bare);
  assert.deepEqual(pagesOf(bare), { 'Home.md': 'a\r\nb\n', 'images/x.txt': 'x' });
});

test('an empty wiki repository gets its first commit on master', () => {
  const bare = wiki(null);
  assert.equal(run(source({ 'Home.md': 'Home\n' }), bare).status, 'published');
  assert.deepEqual(Object.keys(pagesOf(bare)), ['Home.md']);
  assert.equal(git(bare, 'branch', '--list').replace('*', '').trim(), 'master');
});

test('refuses sources without Home.md and leaves the wiki alone', () => {
  const bare = wiki();
  const head = headOf(bare);
  assert.throws(() => run(source({ 'Guide.md': 'x\n' }), bare), error => error instanceof PublishError && /no Home\.md/.test(error.message));
  assert.equal(headOf(bare), head);
});

test('a dry run reports the change and pushes nothing', () => {
  const bare = wiki();
  const head = headOf(bare);
  const lines = [];
  const result = run(source({ 'Home.md': 'Home\n' }), bare, { dryRun: true, log: m => lines.push(m) });
  assert.equal(result.status, 'dry-run');
  assert.match(lines.join('\n'), /Would publish 1 changed files/);
  assert.equal(headOf(bare), head);
});

test('a wiki that does not exist is reported, not an error', () => {
  const lines = [];
  const missing = path.join(tmp('wiki-none-'), 'nothing.git');
  const result = run(source({ 'Home.md': 'Home\n' }), missing, { log: m => lines.push(m) });
  assert.equal(result.status, 'missing-wiki');
  assert.match(lines.join('\n'), /create its first page/);
});

test('the token reaches git only as an environment header and is redacted from messages', () => {
  const env = gitEnv({ GITHUB_TOKEN: 'ghs_secret123' }, 'https://github.com/o/r.wiki.git');
  assert.match(env.GIT_CONFIG_VALUE_2, /^AUTHORIZATION: basic /);
  assert.equal(env.GIT_TERMINAL_PROMPT, '0');
  const basic = Buffer.from('x-access-token:ghs_secret123').toString('base64');
  assert.equal(redact(`fail ghs_secret123 and ${basic}`, { GITHUB_TOKEN: 'ghs_secret123' }), 'fail *** and ***');
  assert.equal(gitEnv({ GITHUB_TOKEN: 'x' }, '/some/local/path').GIT_CONFIG_COUNT, '2');
});
