// Settings of the desktop app itself (userData/settings.json). The daemon's own config is read-only here.

export type ApiSource = 'mock' | 'daemon';
export type Theme = 'system' | 'light' | 'dark';

export interface AppSettings {
  /** Where screen data comes from; daemon status and management are always real. */
  apiSource: ApiSource;
  /** CodeLoupe CLI used for start/stop; the Node prototype today, the Kotlin CLI after CL-56. */
  cliCommand: string;
  cliArgs: string[];
  /** Explicit port; null = follow <home>/daemon.json, CODELOUPE_PORT, config.json, 47391. */
  portOverride: number | null;
  autoStartDaemon: boolean;
  openAtLogin: boolean;
  /** Look for a newer release on GitHub now and then (CL-107); off = the app never contacts the release feed by itself. */
  autoUpdate: boolean;
  theme: Theme;
  /** Single-key shortcuts can be turned off (WCAG 2.1.4). */
  shortcuts: boolean;
  notify: { budget: boolean; builds: boolean; gaps: boolean; daemon: boolean };
}

export const DEFAULT_SETTINGS: AppSettings = {
  apiSource: 'mock',
  cliCommand: 'codeloupe',
  cliArgs: [],
  portOverride: null,
  autoStartDaemon: true,
  openAtLogin: false,
  autoUpdate: true,
  theme: 'system',
  shortcuts: true,
  notify: { budget: true, builds: true, gaps: true, daemon: true },
};

const bool = (v: unknown, d: boolean) => (typeof v === 'boolean' ? v : d);

/** Fields the renderer may change; the CLI command changes only through a native confirmation in main. */
export type RendererSettings = Omit<AppSettings, 'cliCommand' | 'cliArgs'>;

const validPort = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v) && v >= 1024 && v <= 65535;

/** Merges untrusted input over a base, keeping only valid fields. */
export function sanitizeSettings(input: unknown, base: AppSettings = DEFAULT_SETTINGS): AppSettings {
  const s = (input && typeof input === 'object' ? input : {}) as Record<string, unknown>;
  const n = (s.notify && typeof s.notify === 'object' ? s.notify : {}) as Record<string, unknown>;
  const portOverride = s.portOverride === null ? null : validPort(s.portOverride) ? s.portOverride : base.portOverride;
  const cli = isValidCli(s.cliCommand, s.cliArgs);
  return {
    apiSource: s.apiSource === 'mock' || s.apiSource === 'daemon' ? s.apiSource : base.apiSource,
    cliCommand: cli ? (s.cliCommand as string).trim() : base.cliCommand,
    cliArgs: cli ? (s.cliArgs as string[]) : base.cliArgs,
    portOverride,
    autoStartDaemon: bool(s.autoStartDaemon, base.autoStartDaemon),
    openAtLogin: bool(s.openAtLogin, base.openAtLogin),
    autoUpdate: bool(s.autoUpdate, base.autoUpdate),
    theme: s.theme === 'system' || s.theme === 'light' || s.theme === 'dark' ? s.theme : base.theme,
    shortcuts: bool(s.shortcuts, base.shortcuts),
    notify: {
      budget: bool(n.budget, base.notify.budget),
      builds: bool(n.builds, base.notify.builds),
      gaps: bool(n.gaps, base.notify.gaps),
      daemon: bool(n.daemon, base.notify.daemon),
    },
  };
}

/** Splits an argument line; double quotes group, no shell expansion. */
export function splitArgs(line: string): string[] {
  const out: string[] = [];
  const re = /"([^"]*)"|(\S+)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(line))) out.push(m[1] ?? m[2]);
  return out;
}

/** Applies a renderer update: everything except the CLI command. */
export function applyRendererUpdate(current: AppSettings, input: unknown): AppSettings {
  const patch = { ...((input && typeof input === 'object' ? input : {}) as Record<string, unknown>) };
  delete patch.cliCommand;
  delete patch.cliArgs;
  return sanitizeSettings({ ...current, ...patch, notify: { ...current.notify, ...(patch.notify as object | undefined) } }, current);
}

const BAD_CHARS = /[\r\n\0]/;

export function isValidCli(command: unknown, args: unknown): args is string[] {
  return typeof command === 'string' && command.trim().length > 0 && command.length <= 500 && !BAD_CHARS.test(command)
    && Array.isArray(args) && args.length <= 20 && args.every(a => typeof a === 'string' && a.length <= 500 && !BAD_CHARS.test(a));
}
