import { app, BrowserWindow, dialog } from 'electron';
import type { DaemonSettings } from '../../shared/contract';
import { ONBOARDING_CH } from '../../shared/onboardingActions';
import { cliRunner } from '../env/execCli';
import type { EnvContext } from '../env/registerEnv';
import { OnboardingManager } from './OnboardingManager';

/** Registers the channels of shared/onboardingActions.ts; `handle` checks the sender, as for every other channel. */
export function registerOnboarding(ctx: EnvContext, handle: <A extends unknown[], R>(channel: string, fn: (...args: A) => Promise<R> | R) => void): OnboardingManager {
  const manager = new OnboardingManager({
    run: cliRunner(ctx.settings, ctx.homeDir),
    pickFolders: async () => {
      // A scripted verification run of a development build names the folders itself; an installed app always asks the user.
      if (!app.isPackaged && process.env.CODELOUPE_APP_PICK_FOLDERS) {
        try {
          const given: unknown = JSON.parse(process.env.CODELOUPE_APP_PICK_FOLDERS);
          if (Array.isArray(given)) return given.filter((p): p is string => typeof p === 'string');
        } catch { /* not JSON: ask */ }
      }
      const win = BrowserWindow.getFocusedWindow();
      const opts = { title: 'Vyberte repozitáře (složky s .git)', properties: ['openDirectory' as const, 'multiSelections' as const] };
      const r = win ? await dialog.showOpenDialog(win, opts) : await dialog.showOpenDialog(opts);
      return r.canceled ? [] : r.filePaths;
    },
    repos: async () => ((await ctx.source().get({ resource: 'settings' }, '/ui-api/v1/settings')) as DaemonSettings).repos,
    blocked: () => (ctx.source().kind === 'mock' ? 'Zdroj dat je Mock: průvodce se v tomto režimu nic nepíše. Přepněte v Nastavení zdroj dat na Daemon.' : null),
  });
  handle(ONBOARDING_CH.addRepositories, () => manager.addRepositories());
  handle(ONBOARDING_CH.query, (repoId: unknown) => manager.query(String(repoId)));
  return manager;
}
