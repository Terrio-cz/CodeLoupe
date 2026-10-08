import { execFile } from 'node:child_process';
import type { AppSettings } from '../../shared/settings';
import { resolveCommand } from '../daemon/DaemonManager';
import type { CliResult, RunCli } from './EnvManager';

const MAX_BUFFER = 64 * 1024 * 1024;

/**
 * The configured CodeLoupe CLI with [args] after its own arguments, no shell; [stdin] is written to the process and
 * closed. The environment carries the daemon home so the CLI opens the same vault as the daemon.
 */
export function cliRunner(settings: () => AppSettings, homeDir: () => string): RunCli {
  return (args, stdin, timeoutMs) => new Promise<CliResult>((resolve, reject) => {
    const s = settings();
    let command: string;
    try { command = resolveCommand(s.cliCommand); } catch (e) { return reject(e); }
    const child = execFile(command, [...s.cliArgs, ...args], {
      timeout: timeoutMs, windowsHide: true, shell: false, maxBuffer: MAX_BUFFER, encoding: 'utf8',
      env: { ...process.env, CODELOUPE_HOME: homeDir() },
    }, (err, stdout, stderr) => {
      const code = (err as NodeJS.ErrnoException | null)?.code;
      if (code === 'ENOENT') return reject(new Error(`command “${s.cliCommand}” was not found`));
      if (code === 'EINVAL') return reject(new Error(`“${s.cliCommand}” cannot run without a shell; enter java with the arguments -cp <install dir>/lib/* codeloupe.MainKt`));
      const exit = err ? (typeof (err as { code?: unknown }).code === 'number' ? (err as unknown as { code: number }).code : 1) : 0;
      resolve({ code: exit, stdout: String(stdout), stderr: String(stderr) });
    });
    if (stdin !== null) child.stdin?.end(stdin);
    else child.stdin?.end();
  });
}
