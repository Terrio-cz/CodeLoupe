import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { beforeEach, describe, expect, it } from 'vitest';
import { readAccounts } from '../src/main/accounts/accountsFile';
import { AccountsManager, checkUrl, tokenName, type AccountsDeps } from '../src/main/accounts/AccountsManager';
import type { CliResult } from '../src/main/env/EnvManager';

const TOKEN = 'perm:fake-account-token-31415';

interface Call { args: string[]; stdin: string | null }

let tmp: string;
let home: string;
let userHome: string;

beforeEach(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cl-acct-'));
  home = path.join(tmp, 'home');
  userHome = path.join(tmp, 'user');
  fs.mkdirSync(home, { recursive: true });
  fs.mkdirSync(path.join(userHome, '.claude'), { recursive: true });
});

function setup(over: Partial<AccountsDeps> = {}, reply: (c: Call) => CliResult = () => ({ code: 0, stdout: '', stderr: '' })) {
  const calls: Call[] = [];
  const confirms: { message: string; detail: string }[] = [];
  const state = { restarts: 0, http: [] as { url: string; headers: Record<string, string> }[] };
  const deps: AccountsDeps = {
    homeDir: () => home,
    userHome: () => userHome,
    run: async (args, stdin) => { const c = { args, stdin }; calls.push(c); return reply(c); },
    confirm: async (message, detail) => { confirms.push({ message, detail }); return true; },
    fetchStored: async () => TOKEN,
    http: async (url, headers) => { state.http.push({ url, headers }); return { status: 200, body: '{"login":"dev.user"}' }; },
    restartDaemon: async () => { state.restarts++; },
    ...over,
  };
  return { manager: new AccountsManager(deps), calls, confirms, state };
}

const dir = (name: string) => path.join(tmp, name);
const everything = () => fs.readdirSync(home).map(f => fs.readFileSync(path.join(home, f), 'utf8')).join('\n');

describe('Claude accounts', () => {
  it('adds an account, materialising ~/.claude as the default first, and writes the file the daemon reads', async () => {
    const t = setup();
    const out = await t.manager.claudeAdd({ label: 'Účet B', configDir: dir('claude-b'), create: true });
    expect(out).toEqual({ ok: true, message: 'Účet „Účet B“ přidán.' });
    expect(fs.existsSync(dir('claude-b'))).toBe(true);
    const file = JSON.parse(fs.readFileSync(path.join(home, 'accounts.json'), 'utf8')) as Record<string, unknown>;
    expect(Object.keys(file).sort()).toEqual(['claude', 'version', 'youtrack']);
    expect(file.claude).toEqual([
      { id: 'default', label: 'Výchozí účet', configDir: path.join(userHome, '.claude'), default: true },
      { id: 'ucet-b', label: 'Účet B', configDir: path.normalize(dir('claude-b')), default: false },
    ]);
  });

  it('refuses a relative path, a missing directory without create, a file, a duplicate and the implicit default again', async () => {
    const t = setup();
    expect((await t.manager.claudeAdd({ label: 'x', configDir: 'relative/dir', create: true })).ok).toBe(false);
    expect((await t.manager.claudeAdd({ label: 'x', configDir: dir('nope'), create: false })).message).toContain('neexistuje');
    fs.writeFileSync(dir('afile'), 'x');
    expect((await t.manager.claudeAdd({ label: 'x', configDir: dir('afile'), create: false })).message).toContain('není složka');
    expect((await t.manager.claudeAdd({ label: '  ', configDir: dir('ok'), create: true })).ok).toBe(false);
    expect((await t.manager.claudeAdd({ label: 'Default', configDir: path.join(userHome, '.claude'), create: false })).message).toContain('už existuje');
    expect(fs.existsSync(path.join(home, 'accounts.json'))).toBe(false);
    fs.mkdirSync(dir('b'));
    expect((await t.manager.claudeAdd({ label: 'B', configDir: dir('b'), create: false })).ok).toBe(true);
    expect((await t.manager.claudeAdd({ label: 'B again', configDir: dir('b'), create: false })).message).toContain('už existuje');
  });

  it('renames, changes the default and removes with a confirmation that says the directory stays', async () => {
    const t = setup();
    fs.mkdirSync(dir('b'));
    await t.manager.claudeAdd({ label: 'B', configDir: dir('b'), create: false });
    expect((await t.manager.claudeRename('b', 'Práce')).ok).toBe(true);
    expect((await t.manager.claudeRename('zzz', 'x')).ok).toBe(false);
    expect((await t.manager.claudeSetDefault('b')).ok).toBe(true);
    expect(readAccounts(home).claude.map(c => [c.id, c.label, c.default])).toEqual([['default', 'Výchozí účet', false], ['b', 'Práce', true]]);
    expect((await t.manager.claudeRemove('b')).message).toContain('odebrán');
    expect(t.confirms[0].detail).toContain('zůstanou');
    expect(fs.existsSync(dir('b'))).toBe(true);
    // The default went away: the one that is left takes over.
    expect(readAccounts(home).claude.map(c => [c.id, c.default])).toEqual([['default', true]]);
    const declined = setup({ confirm: async () => false });
    expect((await declined.manager.claudeRemove('default')).ok).toBe(false);
    expect(readAccounts(home).claude).toHaveLength(1);
  });
});

describe('YouTrack accounts', () => {
  const input = { label: 'Terrio', url: 'https://terrio.youtrack.cloud/', projects: ['ter', 'CL', 'TER'], token: TOKEN };

  it('stores the token in the store on stdin, lists no secret in the file, asks first and restarts the daemon', async () => {
    const t = setup();
    const out = await t.manager.youtrackAdd(input);
    expect(out).toEqual({ ok: true, message: 'Účet Terrio přidán, daemon restartován.' });
    expect(t.calls).toHaveLength(1);
    expect(t.calls[0].args).toEqual(['env', 'set', 'YOUTRACK_TOKEN_TERRIO', '--scope', 'global', '--source', 'app']);
    expect(t.calls[0].stdin).toBe(`${TOKEN}\n`);
    expect(t.confirms[0].detail).toContain('restartuje');
    expect(t.state.restarts).toBe(1);
    expect(readAccounts(home).youtrack).toEqual([{ id: 'terrio', label: 'Terrio', url: 'https://terrio.youtrack.cloud', projects: ['TER', 'CL'], token: 'YOUTRACK_TOKEN_TERRIO' }]);
    expect(everything()).not.toContain(TOKEN);
    expect(JSON.stringify([out, t.calls[0].args])).not.toContain(TOKEN);
    expect((await t.manager.youtrackAdd({ ...input, label: 'Again' })).message).toContain('už existuje');
  });

  it('refuses a bad URL, bad projects, an empty token and does nothing before the user agrees', async () => {
    const t = setup();
    for (const bad of [{ url: 'http://evil.example' }, { url: 'https://user:pw@x.example' }, { url: 'ftp://x' }, { url: 'nonsense' }, { projects: [] }, { projects: ['bad name'] }, { token: '' }, { label: '' }]) {
      expect((await t.manager.youtrackAdd({ ...input, ...bad })).ok).toBe(false);
    }
    expect(t.calls).toHaveLength(0);
    expect(t.state.restarts).toBe(0);
    const declined = setup({ confirm: async () => false });
    expect((await declined.manager.youtrackAdd(input)).message).toBe('Přidání zrušeno.');
    expect(declined.calls).toHaveLength(0);
    expect(checkUrl('http://127.0.0.1:47533')).toEqual({ ok: true, url: 'http://127.0.0.1:47533' });
  });

  it('scrubs the token from a failure to store it and does not write the account', async () => {
    const t = setup({}, () => ({ code: 1, stdout: '', stderr: `cannot store ${TOKEN}\n` }));
    const out = await t.manager.youtrackAdd(input);
    expect(out.ok).toBe(false);
    expect(out.message).not.toContain(TOKEN);
    expect(readAccounts(home).youtrack).toEqual([]);
    expect(t.state.restarts).toBe(0);
  });

  it('tests the connection with the stored token and says who it connected as, never the token', async () => {
    const t = setup();
    await t.manager.youtrackAdd(input);
    const out = await t.manager.youtrackTest('terrio');
    expect(out).toEqual({ ok: true, message: 'Připojeno jako dev.user.' });
    expect(t.state.http[0].url).toBe('https://terrio.youtrack.cloud/api/users/me?fields=login');
    expect(t.state.http[0].headers.Authorization).toBe(`Bearer ${TOKEN}`);
    const rejected = setup({ http: async () => ({ status: 401, body: '' }) });
    await rejected.manager.youtrackAdd(input);
    expect((await rejected.manager.youtrackTest('terrio')).message).toContain('odmítla');
    const broken = setup({ http: async () => { throw new Error(`connect failed with ${TOKEN}`); } });
    await broken.manager.youtrackAdd(input);
    const failed = await broken.manager.youtrackTest('terrio');
    expect(failed.ok).toBe(false);
    expect(failed.message).not.toContain(TOKEN);
    const missing = setup({ fetchStored: async () => null });
    await missing.manager.youtrackAdd(input);
    expect((await missing.manager.youtrackTest('terrio')).message).toContain('není v úložišti');
    expect((await t.manager.youtrackTest('nobody')).ok).toBe(false);
  });

  it('rotates a token without restarting and removes an account with its token after a confirmation', async () => {
    const t = setup();
    await t.manager.youtrackAdd(input);
    t.calls.length = 0;
    expect((await t.manager.youtrackRotate('terrio', 'perm:new-fake-token')).ok).toBe(true);
    expect(t.calls[0]).toEqual({ args: ['env', 'set', 'YOUTRACK_TOKEN_TERRIO', '--scope', 'global', '--source', 'app'], stdin: 'perm:new-fake-token\n' });
    expect(t.state.restarts).toBe(1);
    expect((await t.manager.youtrackRotate('terrio', '')).ok).toBe(false);
    expect((await t.manager.youtrackRotate('nobody', 'x')).ok).toBe(false);
    expect((await t.manager.youtrackRemove('terrio')).ok).toBe(true);
    expect(t.calls.at(-1)?.args).toEqual(['env', 'unset', 'YOUTRACK_TOKEN_TERRIO', '--scope', 'global']);
    expect(t.state.restarts).toBe(2);
    expect(readAccounts(home).youtrack).toEqual([]);
  });

  it('turns an id into the name of its token in the store', () => {
    expect(tokenName('terrio')).toBe('YOUTRACK_TOKEN_TERRIO');
    expect(tokenName('my-firma.2')).toBe('YOUTRACK_TOKEN_MY_FIRMA_2');
  });
});

describe('while the screen shows mock data', () => {
  it('changes nothing', async () => {
    const t = setup({ blocked: () => 'Zdroj dat je Mock' });
    expect((await t.manager.claudeAdd({ label: 'B', configDir: dir('b'), create: true })).message).toBe('Zdroj dat je Mock');
    expect((await t.manager.youtrackAdd({ label: 'T', url: 'https://t.example', projects: ['T'], token: TOKEN })).ok).toBe(false);
    expect((await t.manager.youtrackTest('x')).ok).toBe(false);
    expect(t.calls).toHaveLength(0);
    expect(fs.existsSync(path.join(home, 'accounts.json'))).toBe(false);
  });
});
