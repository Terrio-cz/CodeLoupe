import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { ClaudeConnector, execClaude, findClaude } from '../src/main/claude/ClaudeConnector';
import { pluginVersionState } from '../src/shared/pluginVersion';

// CL-255: Claude Code decides an update by the version. Two snapshots of the plugin folder the app ships, one per app version,
// against the real claude CLI in a throwaway profile (never the user's own). Skipped where claude is not installed (CI).
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'cl-update-'));
const env: NodeJS.ProcessEnv = {
  ...process.env, HOME: profile, USERPROFILE: profile, CLAUDE_CONFIG_DIR: path.join(profile, 'cfg'),
  APPDATA: path.join(profile, 'appdata'), LOCALAPPDATA: path.join(profile, 'local'),
};
afterAll(() => fs.rmSync(profile, { recursive: true, force: true }));

/** The plugin folder of one app version: what resources/claude-plugin holds after the afterPack stamp. */
function snapshot(dir: string, version: string, skill: string) {
  fs.mkdirSync(path.join(dir, '.claude-plugin'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'plugin', '.claude-plugin'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'plugin', 'skills', 'codeloupe'), { recursive: true });
  fs.writeFileSync(path.join(dir, '.claude-plugin', 'marketplace.json'), JSON.stringify({ name: 'codeloupe', owner: { name: 'Terrio' }, plugins: [{ name: 'codeloupe', source: './plugin', description: 'd' }] }));
  fs.writeFileSync(path.join(dir, 'plugin', '.claude-plugin', 'plugin.json'), JSON.stringify({ name: 'codeloupe', description: 'd', version }));
  fs.writeFileSync(path.join(dir, 'plugin', 'skills', 'codeloupe', 'SKILL.md'), `---\nname: codeloupe\ndescription: x\n---\n${skill}\n`);
}

describe.skipIf(!findClaude(process.env))('a plugin installed from an older app (real claude CLI)', () => {
  const run = execClaude(env);
  const folder = path.join(profile, 'app-resources', 'claude-plugin');
  const connector = new ClaudeConnector(run, () => folder);

  it('is flagged, then updated by the refresh commands, once the app ships a new version', async () => {
    snapshot(folder, '0.1.8', 'old');
    expect((await connector.connect('plugin', 47391)).ok).toBe(true);
    const installed = await connector.status();
    expect(installed).toMatchObject({ plugin: true, pluginVersion: '0.1.8', bundledPluginVersion: '0.1.8' });
    expect(pluginVersionState(installed.pluginVersion, installed.bundledPluginVersion)).toBe('same');

    // The app is updated: its resources hold the next snapshot.
    snapshot(folder, '0.1.9', 'new');
    const stale = await connector.status();
    expect(stale).toMatchObject({ pluginVersion: '0.1.8', bundledPluginVersion: '0.1.9' });
    expect(pluginVersionState(stale.pluginVersion, stale.bundledPluginVersion)).toBe('differs');

    expect((await run(['plugin', 'marketplace', 'update', 'codeloupe'])).code).toBe(0);
    const update = await run(['plugin', 'update', 'codeloupe@codeloupe', '--scope', 'user']);
    expect(update.code).toBe(0);
    expect(update.stdout).toContain('0.1.8 to 0.1.9');
    const fresh = await connector.status();
    expect(fresh.pluginVersion).toBe('0.1.9');
    expect(pluginVersionState(fresh.pluginVersion, fresh.bundledPluginVersion)).toBe('same');
  }, 120_000);
});
