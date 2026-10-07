import { execFile } from 'node:child_process';
import { EventEmitter } from 'node:events';
import fs from 'node:fs';
import path from 'node:path';
import type { DaemonStatus } from '../../shared/contract';
import type { DaemonPhase, DaemonState } from '../../shared/ipc';
import type { AppSettings } from '../../shared/settings';
import type { DaemonClient } from './DaemonClient';
import type { DaemonHome } from './DaemonHome';

const CLI_TIMEOUT_MS = 30_000;
const WAIT_MS = 10_000;
const MAX_ATTEMPTS = 5;
const ATTEMPT_WINDOW_MS = 10 * 60_000;

/**
 * Watches the daemon through GET /status and starts or stops it through the CodeLoupe CLI.
 * Keeps the daemon up unless the user stopped it (here or via `codeloupe stop`); gives up after
 * 5 failed starts in 10 minutes.
 */
export class DaemonManager extends EventEmitter {
  private state: DaemonState;
  private failures = 0;
  private everRunning = false;
  private attempts: number[] = [];
  private nextStartAt = 0;
  private busy: Promise<unknown> | null = null;
  private timer: NodeJS.Timeout | null = null;
  private disposed = false;
  private mismatchTicks = 0;
  private gaveUp = false;

  constructor(
    private readonly client: DaemonClient,
    private readonly home: DaemonHome,
    private readonly settings: () => AppSettings,
    private readonly periodMs: () => number,
    /** Runs `<cli> start|stop`; injectable for tests. */
    private readonly runCli: (verb: 'start' | 'stop') => Promise<string> = verb => execCli(settings(), verb, this.port()),
  ) {
    super();
    this.state = { phase: 'unknown', status: null, port: this.port(), message: null, manualStop: home.stoppedByUser(), checkedAt: null };
  }

  get current(): DaemonState {
    return this.state;
  }

  /** True when /status answered and its pid is the one the daemon wrote to daemon.json. */
  get trusted(): boolean {
    return this.state.phase === 'running' && this.state.status !== null;
  }

  port(): number {
    return this.home.port(this.settings().portOverride);
  }

  run(): void {
    const tick = async () => {
      if (this.disposed) return;
      await this.check(true).catch(() => undefined);
      if (!this.disposed) this.timer = setTimeout(tick, this.periodMs());
    };
    void tick();
  }

  dispose(): void {
    this.disposed = true;
    if (this.timer) clearTimeout(this.timer);
  }

  /**
   * Reads /status. Only the periodic tick (`tick`) counts misses towards 'down'; checks asked for by the
   * window or the page refresh the status but never trigger an outage or a start on their own.
   */
  async check(tick = false): Promise<DaemonState> {
    const port = this.port();
    const status = await this.client.status().catch(() => null);
    // A CLI start/stop in flight owns the state until it finishes.
    if (this.busy) {
      this.set({ port });
      return this.state;
    }
    if (status) {
      this.failures = 0;
      this.everRunning = true;
      const foreign = this.foreign(status);
      if (!foreign) {
        this.mismatchTicks = 0;
        // A daemon started after the stop marker means someone started it again: the stop no longer applies.
        // One that started before it is still shutting down, so the stop stays.
        let manualStop = this.home.stoppedByUser();
        if (manualStop && this.home.startedAfterStop()) {
          this.home.setStoppedByUser(false);
          manualStop = false;
        }
        this.set({ phase: 'running', status, port, message: null, manualStop });
      } else if (foreign.hard || (tick && ++this.mismatchTicks >= 3)) {
        this.set({ phase: 'error', status: null, port, message: foreign.message });
      } else {
        // daemon.json is written after the daemon listens and removed on stop: give it a moment.
        this.set({ phase: 'starting', status: null, port, message: null });
      }
      return this.state;
    }
    if (!tick) return this.state;
    this.failures++;
    const threshold = this.everRunning ? 3 : 1;
    if (this.failures < threshold) return this.state;
    const manualStop = this.state.manualStop || this.home.stoppedByUser();
    const phase: DaemonPhase = manualStop ? 'stopped' : this.state.phase === 'error' ? 'error' : 'down';
    this.set({ phase, status: null, port, manualStop });
    if (!manualStop && this.settings().autoStartDaemon) void this.autoStart();
    return this.state;
  }

  start(): Promise<DaemonState> {
    this.attempts = [];
    this.home.setStoppedByUser(false);
    this.set({ manualStop: false });
    return this.serial(() => this.doStart());
  }

  stop(): Promise<DaemonState> {
    return this.serial(async () => {
      this.home.setStoppedByUser(true);
      this.set({ phase: 'stopping', manualStop: true, message: null });
      try {
        await this.cli('stop');
      } catch (e) {
        this.set({ message: `Zastavení selhalo: ${(e as Error).message}` });
      }
      if (!(await this.waitFor(false))) this.set({ phase: 'error', message: 'Daemon po zastavení stále odpovídá.' });
      return this.state;
    });
  }

  restart(): Promise<DaemonState> {
    return this.serial(async () => {
      this.home.setStoppedByUser(false);
      this.set({ phase: 'stopping', manualStop: false, message: null });
      try { await this.cli('stop'); } catch { /* not running is fine */ }
      // Stay in 'stopping' between stop and start: a restart is not an outage.
      await this.waitFor(false, 'stopping');
      this.attempts = [];
      return this.doStart();
    });
  }

  /** Whether /status came from the daemon that wrote daemon.json; `hard` = not CodeLoupe at all. */
  private foreign(status: DaemonStatus): { hard: boolean; message: string } | null {
    if (status.name !== 'codeloupe') return { hard: true, message: `Na portu ${this.port()} odpovídá jiná služba.` };
    const info = this.home.info();
    if (!info || info.pid !== status.pid) {
      return { hard: false, message: `Na portu ${this.port()} odpovídá proces ${status.pid}, daemon.json uvádí ${info ? info.pid : 'nic'}.` };
    }
    return null;
  }

  private async autoStart(): Promise<void> {
    const now = Date.now();
    this.attempts = this.attempts.filter(t => now - t < ATTEMPT_WINDOW_MS);
    if (this.busy || now < this.nextStartAt) return;
    if (this.attempts.length >= MAX_ATTEMPTS) {
      if (!this.gaveUp) {
        this.gaveUp = true;
        this.set({ phase: 'error', message: 'Daemon se nepodařilo spustit 5× za 10 minut. Zkontrolujte příkaz CLI v Nastavení.' });
        this.emit('gaveUp', this.state.message);
      }
      return;
    }
    await this.serial(() => this.doStart());
  }

  private async doStart(): Promise<DaemonState> {
    this.gaveUp = false;
    const now = Date.now();
    this.attempts.push(now);
    this.nextStartAt = now + Math.min(60_000, 5_000 * 2 ** (this.attempts.length - 1));
    this.set({ phase: 'starting', message: null });
    try {
      await this.cli('start');
    } catch (e) {
      this.set({ phase: 'error', message: `Spuštění selhalo: ${(e as Error).message}` });
      this.emit('failed', this.state.message);
      return this.state;
    }
    if (!(await this.waitFor(true))) this.set({ phase: 'error', message: 'Daemon po spuštění neodpovídá na /status.' });
    return this.state;
  }

  /** Polls /status until the daemon is (or is no longer) reachable. */
  private async waitFor(up: boolean, downPhase?: DaemonPhase): Promise<boolean> {
    const until = Date.now() + WAIT_MS;
    while (Date.now() < until) {
      const status = await this.client.status().catch(() => null);
      if (up && status) {
        const foreign = this.foreign(status);
        if (foreign?.hard) {
          this.set({ phase: 'error', status: null, message: foreign.message });
          return false;
        }
        if (!foreign) {
          this.failures = 0;
          this.everRunning = true;
          this.mismatchTicks = 0;
          this.set({ phase: 'running', status, port: this.port(), message: null });
          return true;
        }
      }
      if (!up && !status) {
        this.set({ phase: downPhase ?? (this.state.manualStop ? 'stopped' : 'down'), status: null });
        return true;
      }
      await new Promise(r => setTimeout(r, 300));
    }
    return false;
  }

  private cli(verb: 'start' | 'stop'): Promise<string> {
    return this.runCli(verb);
  }

  private serial<T>(fn: () => Promise<T>): Promise<T> {
    const run = (this.busy ?? Promise.resolve()).catch(() => undefined).then(fn);
    this.busy = run;
    void run.finally(() => { if (this.busy === run) this.busy = null; }).catch(() => undefined);
    return run;
  }

  private set(patch: Partial<DaemonState>): void {
    const prev = this.state;
    this.state = { ...prev, ...patch, checkedAt: new Date().toISOString() };
    if (prev.phase !== this.state.phase) this.emit('phase', this.state.phase, prev.phase);
    this.emit('state', this.state);
  }
}

/**
 * On Windows a bare command name is looked up on PATH: an .exe runs, a .cmd/.bat cannot run without a
 * shell (the CLI must be an .exe, or node/java plus the script path; docs/ui-spec.md § 8).
 */
export function resolveCommand(command: string, env: NodeJS.ProcessEnv = process.env, platform = process.platform): string {
  if (platform !== 'win32' || /[\\/]/.test(command) || path.extname(command)) return command;
  const dirs = (env.PATH ?? env.Path ?? '').split(';').filter(Boolean);
  // Like the OS: the first real executable anywhere on PATH wins over a script shim.
  for (const dir of dirs) {
    for (const ext of ['.exe', '.com']) {
      if (fs.existsSync(path.join(dir, command + ext))) return path.join(dir, command + ext);
    }
  }
  for (const dir of dirs) {
    for (const ext of ['.cmd', '.bat']) {
      if (fs.existsSync(path.join(dir, command + ext))) {
        throw new Error(`„${command}“ je skript ${ext}, který nejde spustit bez shellu; v Nastavení zadejte node.exe (nebo java) a cestu ke CLI`);
      }
    }
  }
  return command;
}

function execCli(s: AppSettings, verb: 'start' | 'stop', port: number): Promise<string> {
  return new Promise((resolve, reject) => {
    let command: string;
    try { command = resolveCommand(s.cliCommand); } catch (e) { return reject(e); }
    // No shell: the command and its arguments go to the OS as an argv array.
    execFile(command, [...s.cliArgs, verb], {
      timeout: CLI_TIMEOUT_MS, windowsHide: true, shell: false,
      env: { ...process.env, CODELOUPE_PORT: String(port) },
    }, (err, stdout, stderr) => {
      if (err) {
        const code = (err as NodeJS.ErrnoException).code;
        if (code === 'ENOENT') return reject(new Error(`příkaz „${s.cliCommand}“ nebyl nalezen`));
        if (code === 'EINVAL') return reject(new Error(`„${s.cliCommand}“ nejde spustit bez shellu; zadejte node.exe a cestu k bin/codeloupe.mjs`));
        return reject(new Error(String(stderr || err.message).trim().split(/\r?\n/).slice(-1)[0]));
      }
      resolve(String(stdout));
    });
  });
}
