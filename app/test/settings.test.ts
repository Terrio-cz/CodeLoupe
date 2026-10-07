import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { applyRendererUpdate, DEFAULT_SETTINGS, sanitizeSettings, splitArgs } from '../src/shared/settings';
import { SettingsStore } from '../src/main/settingsStore';

describe('settings', () => {
  it('keeps only valid fields', () => {
    const s = sanitizeSettings({ apiSource: 'remote', portOverride: 80, theme: 'dark', notify: { gaps: false }, extra: 1 });
    expect(s.apiSource).toBe(DEFAULT_SETTINGS.apiSource);
    expect(s.portOverride).toBeNull();
    expect(s.theme).toBe('dark');
    expect(s.notify).toEqual({ ...DEFAULT_SETTINGS.notify, gaps: false });
    expect(s).not.toHaveProperty('extra');
  });

  it('never lets the renderer change the CLI command', () => {
    const next = applyRendererUpdate(DEFAULT_SETTINGS, { cliCommand: 'calc.exe', cliArgs: ['/c'], theme: 'light' });
    expect(next.cliCommand).toBe(DEFAULT_SETTINGS.cliCommand);
    expect(next.cliArgs).toEqual(DEFAULT_SETTINGS.cliArgs);
    expect(next.theme).toBe('light');
  });

  it('refuses commands with control characters', () => {
    const s = sanitizeSettings({ cliCommand: 'node\r\nevil', cliArgs: [] });
    expect(s.cliCommand).toBe(DEFAULT_SETTINGS.cliCommand);
  });

  it('splits arguments without a shell', () => {
    expect(splitArgs('"C:/Program Files/x/bin.mjs" --flag a')).toEqual(['C:/Program Files/x/bin.mjs', '--flag', 'a']);
  });

  it('keeps start-up overrides out of the settings file', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cl-settings-'));
    const store = new SettingsStore(dir, { CODELOUPE_APP_CLI: '["node","bin.mjs"]', CODELOUPE_APP_API: 'daemon' });
    expect(store.get()).toMatchObject({ cliCommand: 'node', cliArgs: ['bin.mjs'], apiSource: 'daemon' });
    store.save({ ...store.get(), theme: 'dark' });
    const file = JSON.parse(fs.readFileSync(path.join(dir, 'settings.json'), 'utf8'));
    expect(file).toMatchObject({ cliCommand: DEFAULT_SETTINGS.cliCommand, apiSource: DEFAULT_SETTINGS.apiSource, theme: 'dark' });
    expect(new SettingsStore(dir, {}).get().theme).toBe('dark');
  });
});
