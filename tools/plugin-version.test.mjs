import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { resourcesDirOf, shippedPluginVersions, stampPackedPlugin, stampPluginVersion } from '../app/scripts/plugin-version.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function marketplace(entryExtra = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'plugin-version-'));
  fs.mkdirSync(path.join(dir, '.claude-plugin'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'plugin', '.claude-plugin'), { recursive: true });
  fs.writeFileSync(path.join(dir, '.claude-plugin', 'marketplace.json'), JSON.stringify({ name: 'codeloupe', plugins: [{ name: 'codeloupe', source: './plugin', ...entryExtra }] }));
  fs.writeFileSync(path.join(dir, 'plugin', '.claude-plugin', 'plugin.json'), JSON.stringify({ name: 'codeloupe', version: '0.1.1', description: 'd' }));
  return dir;
}
const manifest = dir => JSON.parse(fs.readFileSync(path.join(dir, 'plugin', '.claude-plugin', 'plugin.json'), 'utf8'));

test('stamps the version into plugin.json and keeps the rest of it', () => {
  const dir = marketplace();
  stampPluginVersion(dir, '0.1.9');
  assert.deepEqual(manifest(dir), { name: 'codeloupe', version: '0.1.9', description: 'd' });
  assert.deepEqual(shippedPluginVersions(dir).map(v => v.version), ['0.1.9']);
});

test('stamps a marketplace entry only when it carries a version', () => {
  const plain = marketplace();
  stampPluginVersion(plain, '0.1.9');
  assert.equal('version' in JSON.parse(fs.readFileSync(path.join(plain, '.claude-plugin', 'marketplace.json'), 'utf8')).plugins[0], false);

  const pinned = marketplace({ version: '0.1.1' });
  stampPluginVersion(pinned, '0.1.9');
  assert.deepEqual(shippedPluginVersions(pinned).map(v => v.version), ['0.1.9', '0.1.9']);
});

test('refuses to stamp without a version', () => {
  assert.throws(() => stampPluginVersion(marketplace(), ''), /no version/);
});

test('finds Resources next to the executable, inside the .app on macOS, and stamps the plugin there with the app version', () => {
  const packager = { appInfo: { productFilename: 'CodeLoupe', version: '1.2.3' } };
  assert.equal(resourcesDirOf({ appOutDir: '/o', electronPlatformName: 'linux', packager }), path.join('/o', 'resources'));
  assert.equal(resourcesDirOf({ appOutDir: '/o', electronPlatformName: 'darwin', packager }), path.join('/o', 'CodeLoupe.app', 'Contents', 'Resources'));

  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'plugin-version-out-'));
  const dir = path.join(out, 'resources', 'claude-plugin');
  fs.cpSync(marketplace(), dir, { recursive: true });
  stampPackedPlugin({ appOutDir: out, electronPlatformName: 'win32', packager });
  assert.deepEqual(shippedPluginVersions(dir).map(v => v.version), ['1.2.3']);
});

// The repository's own copy is the one a GitHub marketplace install reads: pinned, it never updates (CL-255).
test('the plugin in the repository is unversioned, so a marketplace install from GitHub follows the commits', () => {
  const versions = shippedPluginVersions(repo);
  assert.ok(versions.length >= 1);
  for (const { file, version } of versions) assert.equal(version, undefined, `${path.relative(repo, file)} pins version ${version}`);
});
