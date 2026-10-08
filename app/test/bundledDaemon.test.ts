import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { findBundledDaemon, stageBundle } from '../src/main/daemon/BundledDaemon';
import { DEFAULT_SETTINGS } from '../src/shared/settings';
import { SettingsStore } from '../src/main/settingsStore';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'cl-bundled-'));

/** A bundle laid out like gradle/bundle.gradle.kts builds it. */
function makeBundle(resources: string, platform: NodeJS.Platform = process.platform): string {
  const root = path.join(resources, 'codeloupe');
  fs.mkdirSync(path.join(root, 'lib'), { recursive: true });
  fs.mkdirSync(path.join(root, 'runtime', 'bin'), { recursive: true });
  fs.writeFileSync(path.join(root, 'lib', 'codeloupe-0.1.0.jar'), 'jar');
  fs.writeFileSync(path.join(root, 'lib', 'clikt-jvm-5.0.jar'), 'jar');
  fs.writeFileSync(path.join(root, 'runtime', 'bin', platform === 'win32' ? 'java.exe' : 'java'), 'java');
  return root;
}

describe('bundled daemon', () => {
  it('runs the bundled java on the codeloupe jar, not on a launcher script', () => {
    const resources = tmp();
    const root = makeBundle(resources, 'win32');
    const found = findBundledDaemon(resources, { platform: 'win32' });
    expect(found?.command).toBe(path.join(root, 'runtime', 'bin', 'java.exe'));
    expect(found?.args.slice(-2)).toEqual(['-jar', path.join(root, 'lib', 'codeloupe-0.1.0.jar')]);
  });

  it('finds nothing when the app ships no bundle or it is incomplete', () => {
    const resources = tmp();
    expect(findBundledDaemon(resources)).toBeNull();
    fs.mkdirSync(path.join(resources, 'codeloupe', 'lib'), { recursive: true });
    expect(findBundledDaemon(resources)).toBeNull();
  });

  it('copies the bundle out of an AppImage once and drops other versions', () => {
    const resources = tmp();
    makeBundle(resources);
    const stage = path.join(tmp(), 'daemon');
    fs.mkdirSync(path.join(stage, '0.0.1'), { recursive: true });
    const found = findBundledDaemon(resources, { appImage: '/x/CodeLoupe.AppImage', stageDir: stage, version: '0.4.0' });
    expect(found?.command.startsWith(path.join(stage, '0.4.0'))).toBe(true);
    expect(fs.readdirSync(stage)).toEqual(['0.4.0']);
    const marker = path.join(stage, '0.4.0', '.complete');
    const before = fs.statSync(marker).mtimeMs;
    expect(stageBundle(path.join(resources, 'codeloupe'), stage, '0.4.0')).toBe(path.join(stage, '0.4.0'));
    expect(fs.statSync(marker).mtimeMs).toBe(before);
  });

  it('makes the settings default to the bundle and real data, without writing the bundle path', () => {
    const dir = tmp();
    const bundled = { command: '/app/resources/codeloupe/runtime/bin/java', args: ['-jar', '/app/resources/codeloupe/lib/codeloupe-0.1.0.jar'] };
    const store = new SettingsStore(dir, {}, bundled);
    expect(store.get()).toMatchObject({ apiSource: 'daemon', cliCommand: bundled.command, cliArgs: bundled.args });
    store.save({ ...store.get(), theme: 'dark' });
    const file = JSON.parse(fs.readFileSync(path.join(dir, 'settings.json'), 'utf8'));
    expect(file).toMatchObject({ cliCommand: DEFAULT_SETTINGS.cliCommand, cliArgs: [], theme: 'dark' });
  });

  it('leaves a CLI the user chose alone', () => {
    const dir = tmp();
    fs.writeFileSync(path.join(dir, 'settings.json'), JSON.stringify({ cliCommand: 'node', cliArgs: ['cli.mjs'], apiSource: 'mock' }));
    const store = new SettingsStore(dir, {}, { command: '/bundled/java', args: [] });
    expect(store.get()).toMatchObject({ cliCommand: 'node', cliArgs: ['cli.mjs'], apiSource: 'mock' });
  });
});
