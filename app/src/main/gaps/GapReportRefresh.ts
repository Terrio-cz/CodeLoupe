import { execFile } from 'node:child_process';
import path from 'node:path';
import type { ActionOutcome } from '../../shared/actions';
import type { AppSettings } from '../../shared/settings';
import { resolveCommand } from '../daemon/DaemonManager';

const DAYS = 30;
const TIMEOUT_MS = 10 * 60_000;

export type Exec = (command: string, args: string[], env: NodeJS.ProcessEnv) => Promise<void>;

/** `<cli> metrics gaps --since <day> --out <file>`: a fixed argument list, nothing of it comes from the page. */
export function gapsArgs(cliArgs: string[], file: string, now = new Date()): string[] {
  const since = new Date(now.getTime() - DAYS * 86_400_000).toISOString().slice(0, 10);
  return [...cliArgs, 'metrics', 'gaps', '--since', since, '--out', file];
}

/**
 * Recomputes the weekly gap report (CL-22) with the CodeLoupe CLI, which reads the Claude Code transcripts in its own
 * process (about 20 s for a month of them) and writes `<home>/gaps-report.json`, which the daemon serves to the Gaps
 * screen. One run at a time; a second request waits for the first.
 */
export class GapReportRefresh {
  private running: Promise<ActionOutcome> | null = null;

  constructor(
    private readonly settings: () => AppSettings,
    private readonly homeDir: () => string,
    private readonly exec: Exec = execCli,
  ) {}

  run(): Promise<ActionOutcome> {
    this.running ??= this.refresh().finally(() => { this.running = null; });
    return this.running;
  }

  private async refresh(): Promise<ActionOutcome> {
    const s = this.settings();
    const home = this.homeDir();
    try {
      const command = resolveCommand(s.cliCommand);
      await this.exec(command, gapsArgs(s.cliArgs, path.join(home, 'gaps-report.json')), { ...process.env, CODELOUPE_HOME: home });
      return { ok: true, message: 'Report přepočítán.' };
    } catch (e) {
      return { ok: false, message: `Report se nepodařilo přepočítat: ${(e as Error).message}` };
    }
  }
}

function execCli(command: string, args: string[], env: NodeJS.ProcessEnv): Promise<void> {
  return new Promise((resolve, reject) => {
    // No shell: the command and its arguments go to the OS as an argv array.
    execFile(command, args, { timeout: TIMEOUT_MS, windowsHide: true, shell: false, env, maxBuffer: 1024 * 1024 }, (err, _stdout, stderr) => {
      if (!err) return resolve();
      const code = (err as NodeJS.ErrnoException).code;
      if (code === 'ENOENT') return reject(new Error(`příkaz „${command}“ nebyl nalezen`));
      reject(new Error(String(stderr || err.message).trim().split(/\r?\n/).slice(-1)[0]));
    });
  });
}
