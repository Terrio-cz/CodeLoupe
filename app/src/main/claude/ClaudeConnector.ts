import { execFile } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { ClaudeConnectKind, ClaudeConnectResult, ClaudeStatus } from '../../shared/ipc';

const CLAUDE_TIMEOUT_MS = 60_000;
export const SERVER_NAME = 'codeloupe';
export const MARKETPLACE_NAME = 'codeloupe';
export const PLUGIN_ID = `${SERVER_NAME}@${MARKETPLACE_NAME}`;
export const HEADER = 'x-codeloupe: 1';

export interface RunResult {
  code: number;
  stdout: string;
  stderr: string;
}

/** Runs the `claude` CLI with an argv (no shell); resolves with the exit code, rejects when it cannot start. */
export type ClaudeRunner = (args: string[]) => Promise<RunResult>;

export function mcpUrl(port: number): string {
  return `http://127.0.0.1:${port}/mcp`;
}

/** The commands the Connect buttons run, as the user would type them (shown in the dialog and as a fallback). */
export function commandLines(kind: ClaudeConnectKind, port: number, marketplace: string | null): string[] {
  if (kind === 'mcp') {
    return [`claude mcp add --transport http --scope user ${SERVER_NAME} ${mcpUrl(port)} --header "${HEADER}"`];
  }
  return [
    `claude plugin marketplace add ${quote(marketplace ?? '<CodeLoupe marketplace folder>')}`,
    `claude plugin install ${PLUGIN_ID} --scope user`,
  ];
}

const quote = (s: string) => (/\s/.test(s) ? `"${s}"` : s);

/**
 * Connects Claude Code to the daemon through the `claude` CLI, so Claude Code writes its own configuration:
 * either a user-level MCP entry, or the CodeLoupe plugin (MCP entry, skill and the SessionStart hook that starts
 * the daemon) from a marketplace folder. The caller has the user's confirmation before calling `connect`.
 */
export class ClaudeConnector {
  constructor(
    private readonly run: ClaudeRunner,
    /** The marketplace folder shipped with the app (or the repository root in development); null when absent. */
    private readonly marketplace: () => string | null,
  ) {}

  marketplaceDir(): string | null {
    return this.marketplace();
  }

  async status(): Promise<ClaudeStatus> {
    const version = await this.run(['--version']).catch(() => null);
    if (!version || version.code !== 0) return { cli: false, mcp: false, plugin: false };
    const [mcp, plugins] = await Promise.all([
      this.run(['mcp', 'get', SERVER_NAME]).catch(() => null),
      this.run(['plugin', 'list', '--json']).catch(() => null),
    ]);
    return { cli: true, mcp: mcp?.code === 0, plugin: hasPlugin(plugins?.stdout ?? '') };
  }

  async connect(kind: ClaudeConnectKind, port: number): Promise<ClaudeConnectResult> {
    const marketplace = this.marketplace();
    const manual = commandLines(kind, port, marketplace);
    if (kind === 'plugin' && !marketplace) {
      return { ok: false, message: 'The plugin folder (marketplace) was not found; install the plugin manually as described in the README.', manual };
    }
    const version = await this.run(['--version']).catch(() => null);
    if (!version || version.code !== 0) {
      return { ok: false, message: 'The “claude” command was not found on PATH; run the commands manually.', manual };
    }
    const steps = kind === 'mcp' ? await this.mcpSteps(port) : this.pluginSteps(marketplace!);
    for (const step of steps) {
      const r = await this.run(step).catch((e: Error) => ({ code: -1, stdout: '', stderr: e.message }));
      if (r.code !== 0) {
        return { ok: false, message: `claude ${step.slice(0, 3).join(' ')}: ${lastLine(r.stderr || r.stdout)}`, manual };
      }
    }
    return { ok: true, message: kind === 'mcp' ? 'MCP server added (scope user). New Claude Code sessions will see it.' : 'Plugin installed. New Claude Code sessions will load it.', manual };
  }

  /** An existing user-level entry is replaced, so a changed port ends up in it. */
  private async mcpSteps(port: number): Promise<string[][]> {
    const existing = await this.run(['mcp', 'get', SERVER_NAME]).catch(() => null);
    const steps: string[][] = [];
    if (existing?.code === 0) steps.push(['mcp', 'remove', '--scope', 'user', SERVER_NAME]);
    steps.push(['mcp', 'add', '--transport', 'http', '--scope', 'user', SERVER_NAME, mcpUrl(port), '--header', HEADER]);
    return steps;
  }

  private pluginSteps(marketplace: string): string[][] {
    return [
      ['plugin', 'marketplace', 'add', marketplace],
      ['plugin', 'install', PLUGIN_ID, '--scope', 'user'],
    ];
  }
}

function hasPlugin(json: string): boolean {
  try {
    const list = JSON.parse(json) as unknown;
    return Array.isArray(list) && list.some(p => typeof p === 'object' && p !== null && String((p as { id?: unknown; name?: unknown }).id ?? (p as { name?: unknown }).name ?? '').startsWith(`${SERVER_NAME}@`));
  } catch {
    return false;
  }
}

const lastLine = (s: string) => s.trim().split(/\r?\n/).filter(Boolean).slice(-1)[0] ?? 'failed';

/**
 * `claude` is a real executable on a native install (~/.local/bin/claude[.exe], also on PATH). An npm install is a
 * `.cmd` shim on Windows, which cannot run without a shell; that case reports "not found" and shows the commands.
 */
export function findClaude(env: NodeJS.ProcessEnv = process.env, platform: NodeJS.Platform = process.platform): string | null {
  const exe = platform === 'win32' ? 'claude.exe' : 'claude';
  const dirs = (env.PATH ?? env.Path ?? '').split(platform === 'win32' ? ';' : ':').filter(Boolean);
  dirs.push(path.join(os.homedir(), '.local', 'bin'));
  for (const dir of dirs) {
    const file = path.join(dir, exe);
    if (isFile(file)) return file;
  }
  return null;
}

function isFile(p: string): boolean {
  try { return fs.statSync(p).isFile(); } catch { return false; }
}

/** Runs the claude binary found on PATH; `env` is the environment it gets (tests point it at a temporary profile). */
export function execClaude(env: NodeJS.ProcessEnv = process.env): ClaudeRunner {
  return args => new Promise((resolve, reject) => {
    const bin = findClaude(env);
    if (!bin) return reject(new Error('claude not found'));
    execFile(bin, args, { timeout: CLAUDE_TIMEOUT_MS, windowsHide: true, shell: false, env, maxBuffer: 4 << 20 }, (err, stdout, stderr) => {
      if (err && typeof (err as NodeJS.ErrnoException).code !== 'number') return reject(err);
      resolve({ code: err ? ((err as NodeJS.ErrnoException).code as unknown as number) : 0, stdout: String(stdout), stderr: String(stderr) });
    });
  });
}

/** Marketplace folder: explicit override, the one packaged with the app, or the repository root in a development run. */
export function findMarketplace(opts: { env?: NodeJS.ProcessEnv; resources?: string | null; appDir?: string }): string | null {
  const env = opts.env ?? process.env;
  const candidates = [
    env.CODELOUPE_PLUGIN_DIR,
    opts.resources ? path.join(opts.resources, 'claude-plugin') : null,
    opts.appDir ? path.resolve(opts.appDir, '..', '..', '..') : null,
  ];
  for (const dir of candidates) {
    if (dir && isFile(path.join(dir, '.claude-plugin', 'marketplace.json'))) return path.resolve(dir);
  }
  return null;
}
