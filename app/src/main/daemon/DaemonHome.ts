import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const DEFAULT_PORT = 47391;

export interface DaemonInfo {
  pid: number;
  port: number;
  version?: string;
  startedAt?: string;
}

/** The daemon's home directory and the files the app reads there (same rules as src/config.mjs). */
export class DaemonHome {
  readonly dir: string;

  constructor(private readonly env: NodeJS.ProcessEnv = process.env, platform: NodeJS.Platform = process.platform) {
    this.dir = env.CODELOUPE_HOME || DaemonHome.defaultDir(env, platform);
  }

  static defaultDir(env: NodeJS.ProcessEnv, platform: NodeJS.Platform): string {
    if (platform === 'win32') return path.join(env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'), 'codeloupe');
    if (platform === 'darwin') return path.join(os.homedir(), 'Library', 'Caches', 'codeloupe');
    return path.join(env.XDG_CACHE_HOME || path.join(os.homedir(), '.cache'), 'codeloupe');
  }

  get configFile(): string {
    return path.join(this.dir, 'config.json');
  }

  /** pid/port the running daemon wrote; null when absent or unreadable. */
  info(): DaemonInfo | null {
    const j = readJson(path.join(this.dir, 'daemon.json'));
    return j && Number.isInteger(j.pid) && Number.isInteger(j.port) ? (j as unknown as DaemonInfo) : null;
  }

  /**
   * `<home>/stopped`: a manual stop (here, or `codeloupe stop` once the CLI writes it, CL-62) that keeps the
   * app from starting the daemon against the user's will, also across app restarts.
   */
  stoppedByUser(): boolean {
    return fs.existsSync(this.stopMarker);
  }

  setStoppedByUser(stopped: boolean): void {
    try {
      if (stopped) {
        fs.mkdirSync(this.dir, { recursive: true });
        fs.writeFileSync(this.stopMarker, new Date().toISOString());
      } else {
        fs.rmSync(this.stopMarker, { force: true });
      }
    } catch { /* the in-memory flag still applies */ }
  }

  private get stopMarker(): string {
    return path.join(this.dir, 'stopped');
  }

  /** Port to watch: explicit override, then daemon.json, CODELOUPE_PORT, config.json, 47391. */
  port(override: number | null): number {
    if (override) return override;
    const info = this.info();
    if (info) return info.port;
    const envPort = Number(this.env.CODELOUPE_PORT);
    if (Number.isInteger(envPort) && envPort > 0) return envPort;
    const cfg = readJson(this.configFile);
    if (cfg && Number.isInteger(cfg.port)) return cfg.port as number;
    return DEFAULT_PORT;
  }
}

function readJson(file: string): Record<string, unknown> | null {
  try {
    const v = JSON.parse(fs.readFileSync(file, 'utf8'));
    return v && typeof v === 'object' ? v : null;
  } catch {
    return null;
  }
}
