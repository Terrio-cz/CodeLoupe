import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { EnvManager, type CliResult, type EnvDeps } from '../src/main/env/EnvManager';
import { cliRunner } from '../src/main/env/execCli';
import { checkImport, checkSet, scopeText } from '../src/main/env/envValidation';
import { DEFAULT_SETTINGS } from '../src/shared/settings';

const flush = () => new Promise<void>(r => setTimeout(r, 0));
const SECRET = 'fake-test-value-do-not-leak-7788';

interface Call { args: string[]; stdin: string | null }

function setup(over: Partial<EnvDeps> = {}, reply: (call: Call) => CliResult = () => ({ code: 0, stdout: '', stderr: '' })) {
  const calls: Call[] = [];
  const confirms: { message: string; detail: string }[] = [];
  let clipboard = '';
  const timers: (() => void)[] = [];
  const deps: EnvDeps = {
    run: async (args, stdin) => { const call = { args, stdin }; calls.push(call); return reply(call); },
    confirm: async (message, detail) => { confirms.push({ message, detail }); return true; },
    consumers: async () => ['youtrack-mcp', 'env run: docker'],
    fetchValue: async () => SECRET,
    clipboard: { write: t => { clipboard = t; }, read: () => clipboard },
    schedule: fn => { timers.push(fn); },
    ...over,
  };
  return { manager: new EnvManager(deps), calls, confirms, timers, clipboard: () => clipboard, setClipboard: (t: string) => { clipboard = t; } };
}

const set = (over: object = {}) => ({ name: 'YOUTRACK_TOKEN', scope: { kind: 'global' as const }, value: SECRET, ...over });

describe('validation', () => {
  it('turns scopes into the CLI form and refuses the rest', () => {
    expect(scopeText({ kind: 'global' })).toEqual({ ok: true, value: 'global' });
    expect(scopeText({ kind: 'repo', ref: ' C:/Work/App ' })).toEqual({ ok: true, value: 'repo:C:/Work/App' });
    for (const bad of [{ kind: 'global', ref: 'x' }, { kind: 'repo' }, { kind: 'repo', ref: '' }, { kind: 'workspace', ref: 'a\nb' }, { kind: 'team', ref: 'x' }, null, 'global']) {
      expect(scopeText(bad).ok).toBe(false);
    }
  });

  it('accepts names the store accepts and values up to 16 KB', () => {
    expect(checkSet(set()).ok).toBe(true);
    expect(checkSet(set({ name: '1BAD' })).ok).toBe(false);
    expect(checkSet(set({ name: 'A-B' })).ok).toBe(false);
    expect(checkSet(set({ value: '' })).ok).toBe(false);
    expect(checkSet(set({ value: 'a\u0000b' })).ok).toBe(false);
    expect(checkSet(set({ value: 'x'.repeat(16 * 1024) })).ok).toBe(true);
    expect(checkSet(set({ value: 'x'.repeat(16 * 1024 + 1) })).ok).toBe(false);
    expect(checkSet(set({ value: 'ß'.repeat(9000) })).ok).toBe(false);
  });

  it('checks the import request: ids, scopes and flags', () => {
    const ok = checkImport({ selections: [{ id: 'aabbccddeeff' }, { id: '001122334455', scope: 'repo:c:/work/app' }], replaceSources: true });
    expect(ok).toEqual({ ok: true, value: { select: ['aabbccddeeff', '001122334455=repo:c:/work/app'], replaceSources: true, overwrite: false, includeExcluded: false } });
    expect(checkImport({ selections: [] }).ok).toBe(false);
    expect(checkImport({ selections: [{ id: 'not-an-id' }] }).ok).toBe(false);
    expect(checkImport({ selections: [{ id: 'aabbccddeeff', scope: 'everywhere' }] }).ok).toBe(false);
    expect(checkImport({ selections: [{ id: 'aabbccddeeff', scope: 'repo:a\nb' }] }).ok).toBe(false);
  });
});

describe('EnvManager.set', () => {
  it('sends the value on stdin only and answers without it', async () => {
    const t = setup();
    const out = await t.manager.set(set({ scope: { kind: 'workspace', ref: 'c:/work/terrio' } }));
    expect(out).toEqual({ ok: true, message: 'YOUTRACK_TOKEN saved (workspace:c:/work/terrio).' });
    expect(t.calls).toHaveLength(1);
    expect(t.calls[0].args).toEqual(['env', 'set', 'YOUTRACK_TOKEN', '--scope', 'workspace:c:/work/terrio', '--source', 'app']);
    expect(t.calls[0].stdin).toBe(`${SECRET}\n`);
    expect(JSON.stringify([t.calls[0].args, out])).not.toContain(SECRET);
  });

  it('scrubs the value out of a failure and never calls the CLI for bad input', async () => {
    const t = setup({}, () => ({ code: 1, stdout: '', stderr: `cannot store ${SECRET} here\n` }));
    const out = await t.manager.set(set());
    expect(out.ok).toBe(false);
    expect(out.message).not.toContain(SECRET);
    expect(out.message).toContain('***');
    const thrown = setup({ run: async () => { throw new Error(`spawn failed for ${SECRET}`); } });
    expect((await thrown.manager.set(set())).message).not.toContain(SECRET);
    const none = setup();
    expect((await none.manager.set(set({ name: 'bad name' }))).ok).toBe(false);
    expect(none.calls).toHaveLength(0);
  });

  it('refuses to write while the screen shows mock data', async () => {
    const t = setup({ blocked: () => 'The data source is Mock' });
    expect(await t.manager.set(set())).toEqual({ ok: false, message: 'The data source is Mock' });
    expect((await t.manager.scan(false)).ok).toBe(false);
    expect(t.calls).toHaveLength(0);
  });
});

describe('EnvManager.remove', () => {
  it('names the key and its readers in a native confirmation and deletes only after yes', async () => {
    const t = setup();
    expect(await t.manager.remove({ name: 'GITHUB_TOKEN', scope: { kind: 'repo', ref: 'c:/x' } })).toEqual({ ok: true, message: 'GITHUB_TOKEN deleted.' });
    expect(t.confirms[0].message).toContain('GITHUB_TOKEN');
    expect(t.confirms[0].detail).toContain('youtrack-mcp, env run: docker');
    expect(t.calls[0].args).toEqual(['env', 'unset', 'GITHUB_TOKEN', '--scope', 'repo:c:/x']);
    const no = setup({ confirm: async () => false });
    expect((await no.manager.remove({ name: 'GITHUB_TOKEN', scope: { kind: 'global' } })).ok).toBe(false);
    expect(no.calls).toHaveLength(0);
  });
});

describe('EnvManager import', () => {
  const inventory = { roots: [], excluded: [], counts: { files: 1 }, variables: [] };

  it('scans through the CLI and hands back the inventory', async () => {
    const t = setup({}, () => ({ code: 0, stdout: JSON.stringify(inventory), stderr: '' }));
    expect(await t.manager.scan(true)).toEqual({ ok: true, inventory });
    expect(t.calls[0].args).toEqual(['env', 'import', 'scan', '--json', '--include-excluded']);
    const bad = setup({}, () => ({ code: 0, stdout: '{"nope":1}', stderr: '' }));
    expect((await bad.manager.scan(false)).ok).toBe(false);
    const down = setup({}, () => ({ code: 1, stdout: '', stderr: 'boom\n' }));
    expect(await down.manager.scan(false)).toEqual({ ok: false, message: 'Inventory failed: boom' });
  });

  it('imports the selection, asks before replacing sources and passes the options through', async () => {
    const result = { created: 1, updated: 0, skipped: 0, items: [], replacedFiles: 1, backupId: '20261008123456-aabbcc', notReplaced: [] };
    const t = setup({}, () => ({ code: 0, stdout: JSON.stringify(result), stderr: '' }));
    const out = await t.manager.importRun({ selections: [{ id: 'aabbccddeeff' }, { id: '001122334455', scope: 'global' }], replaceSources: true, overwrite: true, includeExcluded: false });
    expect(out).toEqual({ ok: true, result });
    expect(t.confirms).toHaveLength(1);
    expect(t.calls[0].args).toEqual(['env', 'import', 'run', '--json', '--select', 'aabbccddeeff', '--select', '001122334455=global', '--replace', '--overwrite']);
    const plain = setup({}, () => ({ code: 0, stdout: JSON.stringify(result), stderr: '' }));
    await plain.manager.importRun({ selections: [{ id: 'aabbccddeeff' }], replaceSources: false, overwrite: false, includeExcluded: false });
    expect(plain.confirms).toHaveLength(0);
  });

  it('does nothing when the user declines the replacement', async () => {
    const t = setup({ confirm: async () => false });
    const out = await t.manager.importRun({ selections: [{ id: 'aabbccddeeff' }], replaceSources: true, overwrite: false, includeExcluded: false });
    expect(out).toEqual({ ok: false, message: 'Import cancelled.' });
    expect(t.calls).toHaveLength(0);
  });

  it('rolls a backup back after a confirmation and reports files left alone', async () => {
    const t = setup({}, () => ({ code: 1, stdout: JSON.stringify({ restored: 2, alreadyOriginal: 0, changedSince: ['C:/a/.env'], complete: false }), stderr: '' }));
    const out = await t.manager.rollback('20261008123456-aabbcc');
    expect(out.ok).toBe(false);
    expect(out.message).toContain('Files restored: 2');
    expect(out.message).toContain('1 file was edited');
    expect(t.calls[0].args).toEqual(['env', 'import', 'rollback', '20261008123456-aabbcc', '--json']);
    expect((await t.manager.rollback('../../x')).ok).toBe(false);
    expect(t.calls).toHaveLength(1);
  });
});

describe('EnvManager.reveal', () => {
  it('offers nothing without an OS re-authentication', async () => {
    const t = setup();
    expect(t.manager.capabilities()).toEqual({ reveal: false });
    const out = await t.manager.reveal({ name: 'A', scope: { kind: 'global' } });
    expect(out.ok).toBe(false);
    expect(t.clipboard()).toBe('');
  });

  it('copies after the user authenticated, never returns the value and clears the clipboard later', async () => {
    const t = setup({ reauth: async () => true });
    expect(t.manager.capabilities()).toEqual({ reveal: true });
    const out = await t.manager.reveal({ name: 'A', scope: { kind: 'global' } });
    expect(out.ok).toBe(true);
    expect(JSON.stringify(out)).not.toContain(SECRET);
    expect(t.clipboard()).toBe(SECRET);
    t.timers[0]();
    await flush();
    expect(t.clipboard()).toBe('');
    // Something else was copied meanwhile: it stays.
    await t.manager.reveal({ name: 'A', scope: { kind: 'global' } });
    t.setClipboard('something else');
    t.timers[1]();
    await flush();
    expect(t.clipboard()).toBe('something else');
  });

  it('copies nothing when the authentication fails or the key is gone', async () => {
    const denied = setup({ reauth: async () => false });
    expect((await denied.manager.reveal({ name: 'A', scope: { kind: 'global' } })).ok).toBe(false);
    expect(denied.clipboard()).toBe('');
    const gone = setup({ reauth: async () => true, fetchValue: async () => null });
    expect((await gone.manager.reveal({ name: 'A', scope: { kind: 'global' } })).ok).toBe(false);
  });
});

describe('cliRunner', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cl-env-'));
  afterEach(() => { delete process.env.FAKE_CLI_OUT; delete process.env.FAKE_CLI_FAIL; });

  const settings = () => ({ ...DEFAULT_SETTINGS, cliCommand: process.execPath, cliArgs: [fileURLToPath(new URL('./fixtures/fakeCli.mjs', import.meta.url))] });

  it('starts the CLI without a shell, puts the value on stdin and the daemon home in the environment', async () => {
    const out = path.join(dir, 'a.json');
    process.env.FAKE_CLI_OUT = out;
    const run = cliRunner(settings, () => 'C:/home/codeloupe');
    const result = await run(['env', 'set', 'NAME', '--scope', 'global; echo injected'], `${SECRET}\n`, 20_000);
    expect(result).toEqual({ code: 0, stdout: 'stored\n', stderr: '' });
    const seen = JSON.parse(fs.readFileSync(out, 'utf8')) as { argv: string[]; stdin: string; home: string };
    expect(seen.stdin).toBe(`${SECRET}\n`);
    expect(seen.home).toBe('C:/home/codeloupe');
    expect(seen.argv).toEqual(['env', 'set', 'NAME', '--scope', 'global; echo injected']);
    expect(seen.argv.join(' ')).not.toContain(SECRET);
  });

  it('reports the exit code and stderr of a failing CLI', async () => {
    process.env.FAKE_CLI_OUT = path.join(dir, 'b.json');
    process.env.FAKE_CLI_FAIL = 'oops';
    const result = await cliRunner(settings, () => dir)(['env', 'list'], null, 20_000);
    expect(result.code).toBe(2);
    expect(result.stderr).toContain('failed with oops');
  });

  it('says so when the command does not exist', async () => {
    const run = cliRunner(() => ({ ...DEFAULT_SETTINGS, cliCommand: path.join(dir, 'no-such-codeloupe.exe') }), () => dir);
    await expect(run(['env', 'list'], null, 5_000)).rejects.toThrow(/was not found/);
  });
});
