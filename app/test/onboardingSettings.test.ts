import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { SettingsStore } from '../src/main/settingsStore';

const dir = () => fs.mkdtempSync(path.join(os.tmpdir(), 'cl-onb-'));

describe('who sees the onboarding', () => {
  it('a first start does', () => {
    expect(new SettingsStore(dir(), {}).get().onboardingDone).toBe(false);
  });

  it('settings saved before the onboarding existed do not', () => {
    const d = dir();
    fs.writeFileSync(path.join(d, 'settings.json'), JSON.stringify({ theme: 'dark', apiSource: 'daemon' }));
    expect(new SettingsStore(d, {}).get().onboardingDone).toBe(true);
  });

  it('a user who has not finished it keeps seeing it, and finishing is remembered', () => {
    const d = dir();
    const store = new SettingsStore(d, {});
    store.save({ ...store.get(), theme: 'light' });
    expect(new SettingsStore(d, {}).get().onboardingDone).toBe(false);
    const again = new SettingsStore(d, {});
    again.save({ ...again.get(), onboardingDone: true });
    expect(new SettingsStore(d, {}).get().onboardingDone).toBe(true);
  });

  it('a scripted verification run goes straight to the screens without changing the file', () => {
    const d = dir();
    for (const env of [{ CODELOUPE_APP_ONBOARDING: 'skip' }, { CODELOUPE_APP_SCREENSHOTS: 'x' }, { CODELOUPE_APP_TOUR: '1' }]) {
      expect(new SettingsStore(d, env).get().onboardingDone).toBe(true);
    }
    expect(new SettingsStore(d, {}).get().onboardingDone).toBe(false);
  });
});
