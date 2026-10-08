import type { EnvImportInput, EnvKeyRef, EnvScopeInput, EnvSetInput } from '../../shared/envActions';

// The page is untrusted: everything that reaches the CLI is checked here first.

const NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;
const OCCURRENCE_ID = /^[0-9a-f]{12}$/;
const BACKUP_ID = /^[0-9]{14}-[0-9a-f]{6}$/;
const SCOPE_TEXT = /^(global|(workspace|repo):.{1,400})$/;

const hasControl = (text: string): boolean => [...text].some(c => c.charCodeAt(0) < 32);
export const MAX_VALUE_BYTES = 16 * 1024;
const MAX_SELECTIONS = 2000;

export type Checked<T> = { ok: true; value: T } | { ok: false; error: string };

const fail = (error: string): { ok: false; error: string } => ({ ok: false, error });

/** `global`, `workspace:<ref>` or `repo:<ref>` as the CLI's `--scope` takes it. */
export function scopeText(input: unknown): Checked<string> {
  if (!input || typeof input !== 'object') return fail('the scope is missing');
  const { kind, ref } = input as Partial<EnvScopeInput>;
  if (kind === 'global') return ref === undefined || ref === '' ? { ok: true, value: 'global' } : fail('the global scope takes no reference');
  if (kind !== 'workspace' && kind !== 'repo') return fail('unknown scope');
  if (typeof ref !== 'string' || !ref.trim() || ref.length > 400 || hasControl(ref)) return fail('the scope needs a path or an identifier');
  return { ok: true, value: `${kind}:${ref.trim()}` };
}

export function checkName(name: unknown): Checked<string> {
  return typeof name === 'string' && NAME.test(name) ? { ok: true, value: name } : fail('the name may contain letters, digits and _ and must not start with a digit (max. 64 characters)');
}

export function checkKeyRef(input: unknown): Checked<{ name: string; scope: string }> {
  if (!input || typeof input !== 'object') return fail('the key is missing');
  const { name, scope } = input as Partial<EnvKeyRef>;
  const n = checkName(name);
  if (!n.ok) return n;
  const s = scopeText(scope);
  return s.ok ? { ok: true, value: { name: n.value, scope: s.value } } : s;
}

export function checkSet(input: unknown): Checked<{ name: string; scope: string; value: string }> {
  if (!input || typeof input !== 'object') return fail('the request is missing');
  const key = checkKeyRef(input);
  if (!key.ok) return key;
  const value = (input as Partial<EnvSetInput>).value;
  if (typeof value !== 'string' || value.length === 0) return fail('the value is empty');
  if (value.includes('\u0000')) return fail('the value must not contain a NUL character');
  if (Buffer.byteLength(value, 'utf8') > MAX_VALUE_BYTES) return fail('the value is longer than 16 KB');
  return { ok: true, value: { ...key.value, value } };
}

export function checkBackupId(id: unknown): Checked<string> {
  return typeof id === 'string' && BACKUP_ID.test(id) ? { ok: true, value: id } : fail('invalid backup ID');
}

export function checkImport(input: unknown): Checked<{ select: string[]; replaceSources: boolean; overwrite: boolean; includeExcluded: boolean }> {
  if (!input || typeof input !== 'object') return fail('the request is missing');
  const { selections, replaceSources, overwrite, includeExcluded } = input as Partial<EnvImportInput>;
  if (!Array.isArray(selections) || selections.length === 0) return fail('nothing is selected');
  if (selections.length > MAX_SELECTIONS) return fail('too many items selected');
  const select: string[] = [];
  for (const s of selections) {
    if (!s || typeof s !== 'object' || typeof s.id !== 'string' || !OCCURRENCE_ID.test(s.id)) return fail('invalid item ID');
    if (s.scope !== undefined && (typeof s.scope !== 'string' || !SCOPE_TEXT.test(s.scope) || hasControl(s.scope))) return fail('invalid item scope');
    select.push(s.scope ? `${s.id}=${s.scope}` : s.id);
  }
  return {
    ok: true,
    value: { select, replaceSources: replaceSources === true, overwrite: overwrite === true, includeExcluded: includeExcluded === true },
  };
}

/** Text with every occurrence of [secret] replaced; for an error message that might quote what the CLI was given. */
export function scrub(text: string, secret: string): string {
  return secret.length >= 3 ? text.split(secret).join('***') : text;
}
