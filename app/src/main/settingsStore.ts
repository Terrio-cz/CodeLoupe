import fs from 'node:fs';
import path from 'node:path';
import { DEFAULT_SETTINGS, isValidCli, sanitizeSettings, type AppSettings } from '../shared/settings';
import type { BundledDaemon } from './daemon/BundledDaemon';

/** Settings of the app in <userData>/settings.json, plus start-up overrides from the environment. */
export class SettingsStore {
  private value: AppSettings;
  /** Overrides for this run only: CODELOUPE_APP_CLI='["java","-cp","C:/…/codeloupe/lib/*","codeloupe.MainKt"]', CODELOUPE_APP_API=mock|daemon. */
  private overlay: Partial<AppSettings> = {};
  private readonly file: string;

  /**
   * `bundled` is the daemon an installer ships: the app then reads real data by default and runs that daemon unless
   * the user chose another CLI. The bundle's path changes with every update, so it is never written to the file.
   */
  constructor(dir: string, env: NodeJS.ProcessEnv = process.env, bundled: BundledDaemon | null = null) {
    this.file = path.join(dir, 'settings.json');
    let stored: unknown = {};
    try { stored = JSON.parse(fs.readFileSync(this.file, 'utf8')); } catch { /* first start */ }
    this.value = sanitizeSettings(stored, bundled ? { ...DEFAULT_SETTINGS, apiSource: 'daemon' } : DEFAULT_SETTINGS);
    if (bundled && this.value.cliCommand === DEFAULT_SETTINGS.cliCommand && this.value.cliArgs.length === 0) {
      this.overlay.cliCommand = bundled.command;
      this.overlay.cliArgs = bundled.args;
    }
    try {
      const cli: unknown = env.CODELOUPE_APP_CLI ? JSON.parse(env.CODELOUPE_APP_CLI) : null;
      if (Array.isArray(cli) && isValidCli(cli[0], cli.slice(1))) {
        this.overlay.cliCommand = cli[0];
        this.overlay.cliArgs = cli.slice(1);
      }
    } catch { /* invalid JSON: no override */ }
    if (env.CODELOUPE_APP_API === 'mock' || env.CODELOUPE_APP_API === 'daemon') this.overlay.apiSource = env.CODELOUPE_APP_API;
  }

  get(): AppSettings {
    return { ...this.value, ...this.overlay };
  }

  /** Persists `next` (validated by the caller). Fields still equal to their override stay out of the file. */
  save(next: AppSettings): AppSettings {
    const stored: Record<string, unknown> = { ...next };
    for (const k of Object.keys(this.overlay) as (keyof AppSettings)[]) {
      if (JSON.stringify(next[k]) === JSON.stringify(this.overlay[k])) stored[k] = this.value[k];
      else delete this.overlay[k];
    }
    this.value = sanitizeSettings(stored, this.value);
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.value, null, 2));
    fs.renameSync(tmp, this.file);
    return this.get();
  }
}
