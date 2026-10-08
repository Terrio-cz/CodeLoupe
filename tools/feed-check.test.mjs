import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { checkFeed, parseFeed } from './feed-check.mjs';

const sha512 = buf => crypto.createHash('sha512').update(buf).digest('base64');

function release(version, { tamper = false } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'feed-'));
  const exe = Buffer.from('windows installer');
  fs.writeFileSync(path.join(dir, `CodeLoupe-${version}-win-x64.exe`), tamper ? Buffer.from('windows installeR') : exe);
  fs.writeFileSync(path.join(dir, `CodeLoupe-${version}-linux-x64.AppImage`), 'appimage');
  fs.writeFileSync(path.join(dir, 'latest.yml'), `version: ${version}\nfiles:\n  - url: CodeLoupe-${version}-win-x64.exe\n    sha512: ${sha512(exe)}\n    size: ${exe.length}\npath: CodeLoupe-${version}-win-x64.exe\nsha512: ${sha512(exe)}\nreleaseDate: '2026-10-08T12:00:00.000Z'\n`);
  fs.writeFileSync(path.join(dir, 'latest-linux.yml'), `version: ${version}\nfiles:\n  - url: CodeLoupe-${version}-linux-x64.AppImage\n    sha512: ${sha512('appimage')}\n    size: 8\npath: CodeLoupe-${version}-linux-x64.AppImage\nsha512: ${sha512('appimage')}\nreleaseDate: '2026-10-08T12:00:00.000Z'\n`);
  return dir;
}

test('reads the version and the listed files of a feed', () => {
  const feed = parseFeed(fs.readFileSync(path.join(release('0.9.0-rc.1'), 'latest.yml'), 'utf8'));
  assert.equal(feed.version, '0.9.0-rc.1');
  assert.deepEqual(feed.files.map(f => [f.url, f.size]), [['CodeLoupe-0.9.0-rc.1-win-x64.exe', 17]]);
});

test('accepts a feed that matches its files', () => {
  const dir = release('0.9.0-rc.1');
  assert.deepEqual(checkFeed(dir, 'latest.yml', '0.9.0-rc.1'), []);
  assert.deepEqual(checkFeed(dir, 'latest-linux.yml', '0.9.0-rc.1'), []);
});

test('refuses a wrong version, a changed file, a missing file and a missing feed', () => {
  assert.match(checkFeed(release('0.9.0-rc.1'), 'latest.yml', '0.9.0-rc.2')[0], /says version 0\.9\.0-rc\.1, the release is 0\.9\.0-rc\.2/);
  assert.match(checkFeed(release('0.9.0-rc.1', { tamper: true }), 'latest.yml')[0], /SHA-512 of CodeLoupe-0\.9\.0-rc\.1-win-x64\.exe differs/);
  const dir = release('0.9.0-rc.1');
  fs.rmSync(path.join(dir, 'CodeLoupe-0.9.0-rc.1-win-x64.exe'));
  assert.match(checkFeed(dir, 'latest.yml')[0], /is not in the release/);
  fs.rmSync(path.join(dir, 'latest-linux.yml'));
  assert.deepEqual(checkFeed(dir, 'latest-linux.yml'), ['latest-linux.yml is missing']);
});
