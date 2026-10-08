import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { generate, parseChecksums } from './packaging-manifests.mjs';

const VERSION = '1.2.3-rc1';
const REPO = 'Terrio-cz/CodeLoupe';
const NAMES = ['win-x64.exe', 'mac-arm64.dmg', 'mac-x64.dmg', 'linux-x86_64.AppImage'].map(s => `CodeLoupe-${VERSION}-${s}`);
const sha = buf => crypto.createHash('sha256').update(buf).digest('hex');

/** A release directory of small fixture files with a SHA256SUMS.txt in sha256sum's format. */
function release({ withFiles = true, corrupt = null } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'manifests-'));
  const sums = [];
  for (const name of NAMES) {
    const content = Buffer.from(`fixture ${name}`);
    if (withFiles) fs.writeFileSync(path.join(dir, name), name === corrupt ? Buffer.from('tampered') : content);
    sums.push(`${sha(content)}  ${name}`);
  }
  fs.writeFileSync(path.join(dir, 'SHA256SUMS.txt'), sums.join('\n') + '\n');
  return { dir, hashes: Object.fromEntries(NAMES.map(n => [n, sha(Buffer.from(`fixture ${n}`))])) };
}

const read = (out, rel) => fs.readFileSync(path.join(out, rel), 'utf8');
const urlOf = name => `https://github.com/${REPO}/releases/download/v${VERSION}/${name}`;

test('parses sha256sum lines in text and binary mode', () => {
  const h = 'a'.repeat(64);
  assert.deepEqual(parseChecksums(`${h}  one.exe\n${h.toUpperCase()} *two.dmg\nnot a line\n`), { 'one.exe': h, 'two.dmg': h });
});

test('every manifest carries the URL and hash of the release files', () => {
  const { dir, hashes } = release();
  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'manifests-out-'));
  const written = generate({ dir, version: VERSION, out });
  assert.equal(written.length, 5);
  const [win, arm, intel] = NAMES;

  const winget = 'winget/manifests/t/Terrio/CodeLoupe/' + VERSION;
  const installer = read(out, `${winget}/Terrio.CodeLoupe.installer.yaml`);
  assert.match(installer, new RegExp(`^PackageVersion: ${VERSION}$`, 'm'));
  assert.ok(installer.includes(`InstallerUrl: ${urlOf(win)}\n`));
  assert.ok(installer.includes(`InstallerSha256: ${hashes[win].toUpperCase()}\n`));
  assert.match(installer, /^InstallerType: nullsoft$/m);
  assert.match(installer, /^ManifestType: installer$/m);
  assert.match(read(out, `${winget}/Terrio.CodeLoupe.yaml`), /^ManifestType: version$/m);
  const locale = read(out, `${winget}/Terrio.CodeLoupe.locale.en-US.yaml`);
  assert.match(locale, /^License: PolyForm-Noncommercial-1\.0\.0$/m);
  assert.match(locale, /^ManifestType: defaultLocale$/m);
  for (const file of fs.readdirSync(path.join(out, winget))) {
    assert.match(read(out, `${winget}/${file}`), new RegExp(`^PackageIdentifier: Terrio\\.CodeLoupe$`, 'm'));
  }

  const scoop = JSON.parse(read(out, 'scoop/codeloupe.json'));
  assert.equal(scoop.version, VERSION);
  assert.equal(scoop.architecture['64bit'].url, `${urlOf(win)}#/dl.7z`);
  assert.equal(scoop.architecture['64bit'].hash, hashes[win]);
  assert.ok(scoop.autoupdate.architecture['64bit'].url.includes('v$version/CodeLoupe-$version-win-x64.exe'));

  const cask = read(out, 'homebrew/Casks/codeloupe.rb');
  assert.ok(cask.includes(`version "${VERSION}"`));
  assert.ok(cask.includes(`arm:   "${hashes[arm]}"`));
  assert.ok(cask.includes(`intel: "${hashes[intel]}"`));
  assert.ok(cask.includes('releases/download/v#{version}/CodeLoupe-#{version}-mac-#{arch}.dmg'));
  assert.ok(cask.includes('"com.apple.quarantine"'));
  assert.match(cask, /^cask "codeloupe" do\n[\s\S]*\nend\n$/);
});

test('the URL template of the cask expands to the release assets', () => {
  const { dir } = release();
  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'manifests-out-'));
  generate({ dir, version: VERSION, out });
  const template = /url "(.+)"/.exec(read(out, 'homebrew/Casks/codeloupe.rb'))[1];
  const expand = arch => template.replace(/#\{version\}/g, VERSION).replace('#{arch}', arch);
  assert.equal(expand('arm64'), urlOf(NAMES[1]));
  assert.equal(expand('x64'), urlOf(NAMES[2]));
});

test('refuses an installer that does not match its checksum', () => {
  const { dir } = release({ corrupt: NAMES[0] });
  assert.throws(() => generate({ dir, version: VERSION, out: fs.mkdtempSync(path.join(os.tmpdir(), 'manifests-out-')) }), /SHA256SUMS\.txt says/);
});

test('works from the checksums alone and refuses a missing entry or a bad version', () => {
  const { dir } = release({ withFiles: false });
  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'manifests-out-'));
  assert.equal(generate({ dir, version: VERSION, out }).length, 5);
  assert.throws(() => generate({ dir, version: '9.9.9', out }), /no entry for CodeLoupe-9\.9\.9-win-x64\.exe/);
  assert.throws(() => generate({ dir, version: 'v1', out }), /not a release version/);
  assert.throws(() => generate({ dir, version: VERSION, out, repo: 'a b' }), /not a repository/);
});
