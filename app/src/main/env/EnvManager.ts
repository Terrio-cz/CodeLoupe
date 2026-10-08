import { createHash } from 'node:crypto';
import type { EnvCapabilities, EnvImportInput, EnvImportOutcome, EnvImportResult, EnvInventory, EnvKeyRef, EnvOutcome, EnvScanResult, EnvSetInput } from '../../shared/envActions';
import { checkBackupId, checkImport, checkKeyRef, checkSet, scrub } from './envValidation';

export interface CliResult {
  code: number;
  stdout: string;
  stderr: string;
}

/** Runs the CodeLoupe CLI with these arguments (after the configured prefix) and [stdin]; never through a shell. */
export type RunCli = (args: string[], stdin: string | null, timeoutMs: number) => Promise<CliResult>;

export interface EnvDeps {
  run: RunCli;
  /** Why nothing may be changed right now (the screen shows mock data), or null. */
  blocked?: () => string | null;
  /** A native dialog the page cannot click; true = the user agreed. */
  confirm(message: string, detail: string, okLabel: string): Promise<boolean>;
  /** Who reads the key (audit consumers), for the removal dialog. */
  consumers(key: { name: string; scope: string }): Promise<string[]>;
  /** The OS asks the user to authenticate again; absent where the platform has no such prompt. */
  reauth?: () => Promise<boolean>;
  /** The stored value of one key, read from the daemon on behalf of the app (an audited read); null when it is not there. */
  fetchValue(key: { name: string; scope: string }): Promise<string | null>;
  clipboard: { write(text: string): void; read(): string | Promise<string> };
  schedule(fn: () => void, ms: number): void;
}

const CLIPBOARD_MS = 60_000;
const SHORT_MS = 60_000;
const LONG_MS = 5 * 60_000;

/**
 * The environment store's writes, from the main process. A value arrives once from the page, goes to the CLI on its
 * stdin (never an argument, never a log line) and is gone; every answer is a line of text or metadata. The CLI is the
 * only writer of the vault, so its format, its key protection and its audit stay in one place.
 */
export class EnvManager {
  constructor(private readonly deps: EnvDeps) {}

  capabilities(): EnvCapabilities {
    return { reveal: this.deps.reauth !== undefined };
  }

  async set(input: EnvSetInput): Promise<EnvOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return { ok: false, message: blocked };
    const checked = checkSet(input);
    if (!checked.ok) return { ok: false, message: `The value cannot be saved: ${checked.error}.` };
    const { name, scope, value } = checked.value;
    try {
      const r = await this.deps.run(['env', 'set', name, '--scope', scope, '--source', 'app'], `${value}\n`, SHORT_MS);
      if (r.code !== 0) return { ok: false, message: `Save failed: ${lastLine(scrub(r.stderr || r.stdout, value))}` };
      return { ok: true, message: `${name} saved (${scope}).` };
    } catch (e) {
      return { ok: false, message: `Save failed: ${lastLine(scrub((e as Error).message, value))}` };
    }
  }

  async remove(input: EnvKeyRef): Promise<EnvOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return { ok: false, message: blocked };
    const checked = checkKeyRef(input);
    if (!checked.ok) return { ok: false, message: `The key cannot be deleted: ${checked.error}.` };
    const key = checked.value;
    const consumers = await this.deps.consumers(key).catch(() => []);
    const detail = [`Scope: ${key.scope}`, consumers.length ? `Read by: ${consumers.join(', ')}` : 'Nobody has read it yet.', 'The value cannot be restored.'].join('\n');
    if (!(await this.deps.confirm(`Delete key ${key.name}?`, detail, 'Delete'))) return { ok: false, message: 'Deletion cancelled.' };
    return this.simple(['env', 'unset', key.name, '--scope', key.scope], `${key.name} deleted.`, 'Delete failed');
  }

  async scan(includeExcluded: boolean): Promise<EnvScanResult> {
    const blocked = this.deps.blocked?.();
    if (blocked) return { ok: false, message: blocked };
    try {
      const r = await this.deps.run(['env', 'import', 'scan', '--json', ...(includeExcluded === true ? ['--include-excluded'] : [])], null, LONG_MS);
      if (r.code !== 0) return { ok: false, message: `Inventory failed: ${lastLine(r.stderr || r.stdout)}` };
      const inventory = JSON.parse(r.stdout) as EnvInventory;
      if (!Array.isArray(inventory.variables) || !inventory.counts) return { ok: false, message: 'The inventory has an unexpected shape.' };
      return { ok: true, inventory };
    } catch (e) {
      return { ok: false, message: `Inventory failed: ${lastLine((e as Error).message)}` };
    }
  }

  async importRun(input: EnvImportInput): Promise<EnvImportOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return { ok: false, message: blocked };
    const checked = checkImport(input);
    if (!checked.ok) return { ok: false, message: `The import cannot start: ${checked.error}.` };
    const { select, replaceSources, overwrite, includeExcluded } = checked.value;
    if (replaceSources) {
      const ok = await this.deps.confirm(
        'Replace the values in the source files with references?',
        'The values are saved to the store first. Then, in .env files and Claude JSON configs, each value is replaced with a reference (a comment in .env, `${NAME}` in JSON); the rest of the file stays. An encrypted backup of every file is kept until you restore or delete it.',
        'Replace',
      );
      if (!ok) return { ok: false, message: 'Import cancelled.' };
    }
    const args = ['env', 'import', 'run', '--json', ...select.flatMap(s => ['--select', s]),
      ...(replaceSources ? ['--replace'] : []), ...(overwrite ? ['--overwrite'] : []), ...(includeExcluded ? ['--include-excluded'] : [])];
    try {
      const r = await this.deps.run(args, null, LONG_MS);
      if (r.code !== 0) return { ok: false, message: `Import failed: ${lastLine(r.stderr || r.stdout)}` };
      return { ok: true, result: JSON.parse(r.stdout) as EnvImportResult };
    } catch (e) {
      return { ok: false, message: `Import failed: ${lastLine((e as Error).message)}` };
    }
  }

  async rollback(backupId: string): Promise<EnvOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return { ok: false, message: blocked };
    const id = checkBackupId(backupId);
    if (!id.ok) return { ok: false, message: `The backup cannot be restored: ${id.error}.` };
    if (!(await this.deps.confirm('Restore the source files to their state before the import?', 'Every replaced file is restored from its encrypted backup. A file you edited after the import is left alone. The values stay in the store.', 'Restore'))) {
      return { ok: false, message: 'Restore cancelled.' };
    }
    try {
      const r = await this.deps.run(['env', 'import', 'rollback', id.value, '--json'], null, LONG_MS);
      const parsed = safeJson(r.stdout) as { restored?: number; alreadyOriginal?: number; changedSince?: string[]; complete?: boolean } | null;
      if (!parsed) return { ok: false, message: `Restore failed: ${lastLine(r.stderr || r.stdout)}` };
      const left = parsed.changedSince?.length ?? 0;
      return {
        ok: parsed.complete === true,
        message: `Files restored: ${parsed.restored ?? 0}.${left ? ` ${left} ${left === 1 ? 'file was' : 'files were'} edited since the import and left unchanged.` : ''}`,
      };
    } catch (e) {
      return { ok: false, message: `Restore failed: ${lastLine((e as Error).message)}` };
    }
  }

  /** Copies the value to the clipboard after the OS re-authenticated the user; the clipboard is cleared a minute later if it still holds it. */
  async reveal(input: EnvKeyRef): Promise<EnvOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return { ok: false, message: blocked };
    if (!this.deps.reauth) return { ok: false, message: 'This platform cannot re-authenticate the user, so the value cannot be shown or copied.' };
    const checked = checkKeyRef(input);
    if (!checked.ok) return { ok: false, message: `The value cannot be copied: ${checked.error}.` };
    if (!(await this.deps.reauth())) return { ok: false, message: 'Authentication was not confirmed.' };
    const value = await this.deps.fetchValue(checked.value).catch(() => null);
    if (value === null) return { ok: false, message: 'Could not read the key.' };
    this.deps.clipboard.write(value);
    const digest = sha256(value);
    this.deps.schedule(() => { void Promise.resolve(this.deps.clipboard.read()).then(now => { if (sha256(now) === digest) this.deps.clipboard.write(''); }); }, CLIPBOARD_MS);
    return { ok: true, message: 'The value is on the clipboard and will be cleared in a minute.' };
  }

  private async simple(args: string[], done: string, failed: string): Promise<EnvOutcome> {
    try {
      const r = await this.deps.run(args, null, SHORT_MS);
      return r.code === 0 ? { ok: true, message: done } : { ok: false, message: `${failed}: ${lastLine(r.stderr || r.stdout)}` };
    } catch (e) {
      return { ok: false, message: `${failed}: ${lastLine((e as Error).message)}` };
    }
  }
}

const sha256 = (text: string) => createHash('sha256').update(text).digest('hex');

function lastLine(text: string): string {
  return text.trim().split(/\r?\n/).filter(Boolean).slice(-1)[0] ?? 'no message';
}

function safeJson(text: string): unknown {
  try { return JSON.parse(text); } catch { return null; }
}
