import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import {
  ClaudeConnector, commandLines, execClaude, findClaude, findMarketplace, mcpUrl, type ClaudeRunner, type RunResult,
} from '../src/main/claude/ClaudeConnector';

const ok = (stdout = ''): RunResult => ({ code: 0, stdout, stderr: '' });

/** Records every call; `answers` maps the first argv words to a result (default: success). */
function fake(answers: Record<string, RunResult> = {}) {
  const calls: string[][] = [];
  const run: ClaudeRunner = async args => {
    calls.push(args);
    const key = Object.keys(answers).find(k => args.join(' ').startsWith(k));
    return key ? answers[key] : ok();
  };
  return { calls, run };
}

describe('commandLines', () => {
  it('writes the user-level MCP command with the port and the header, no secret', () => {
    expect(commandLines('mcp', 47391, null)).toEqual([
      'claude mcp add --transport http --scope user codeloupe http://127.0.0.1:47391/mcp --header "x-codeloupe: 1"',
    ]);
  });

  it('quotes a marketplace folder with spaces', () => {
    expect(commandLines('plugin', 47391, 'C:\\Program Files\\CodeLoupe\\claude-plugin')[0])
      .toBe('claude plugin marketplace add "C:\\Program Files\\CodeLoupe\\claude-plugin"');
  });
});

describe('ClaudeConnector', () => {
  it('adds the MCP server for the user scope as one argv', async () => {
    const f = fake({ 'mcp get': { code: 1, stdout: '', stderr: 'No MCP server found' } });
    const r = await new ClaudeConnector(f.run, () => null).connect('mcp', 48000);
    expect(r.ok).toBe(true);
    expect(f.calls.at(-1)).toEqual(['mcp', 'add', '--transport', 'http', '--scope', 'user', 'codeloupe', mcpUrl(48000), '--header', 'x-codeloupe: 1']);
  });

  it('adds the entry with a headers helper when the CLI can be named, so the token never lands in the Claude Code configuration', async () => {
    const f = fake({ 'mcp get': { code: 1, stdout: '', stderr: 'No MCP server found' } });
    await new ClaudeConnector(f.run, () => null, () => 'codeloupe mcp-headers').connect('mcp', 48000);
    const call = f.calls.at(-1)!;
    expect(call.slice(0, 4)).toEqual(['mcp', 'add-json', '--scope', 'user']);
    expect(JSON.parse(call[5])).toEqual({ type: 'http', url: mcpUrl(48000), headers: { 'x-codeloupe': '1' }, headersHelper: 'codeloupe mcp-headers' });
    expect(commandLines('mcp', 48000, null, 'codeloupe mcp-headers')[0]).toContain('"headersHelper":"codeloupe mcp-headers"');
    expect(JSON.stringify(call)).not.toMatch(/[0-9a-f]{32}/);
  });

  it('replaces an existing entry so a changed port is picked up', async () => {
    const f = fake();
    await new ClaudeConnector(f.run, () => null).connect('mcp', 47391);
    expect(f.calls.map(c => c.slice(0, 2).join(' '))).toEqual(['--version', 'mcp get', 'mcp remove', 'mcp add']);
  });

  it('adds the marketplace folder, then installs the plugin at user scope', async () => {
    const f = fake();
    const r = await new ClaudeConnector(f.run, () => '/m').connect('plugin', 47391);
    expect(r.ok).toBe(true);
    expect(f.calls.slice(1)).toEqual([['plugin', 'marketplace', 'add', '/m'], ['plugin', 'install', 'codeloupe@codeloupe', '--scope', 'user']]);
  });

  it('stops at the first failing step and returns the manual commands', async () => {
    const f = fake({ 'plugin marketplace add': { code: 1, stdout: '', stderr: 'boom\nbad source' } });
    const r = await new ClaudeConnector(f.run, () => '/m').connect('plugin', 47391);
    expect(r.ok).toBe(false);
    expect(r.message).toContain('bad source');
    expect(r.manual).toHaveLength(2);
    expect(f.calls.some(c => c[1] === 'install')).toBe(false);
  });

  it('reports a missing claude CLI and a missing plugin folder without running anything else', async () => {
    const missing: ClaudeRunner = async () => { throw new Error('claude not found'); };
    expect((await new ClaudeConnector(missing, () => '/m').connect('mcp', 47391)).message).toContain('claude');
    const f = fake();
    const r = await new ClaudeConnector(f.run, () => null).connect('plugin', 47391);
    expect(r.ok).toBe(false);
    expect(f.calls).toEqual([]);
  });

  it('reads the status from mcp get and plugin list', async () => {
    const f = fake({ 'plugin list': ok(JSON.stringify([{ id: 'codeloupe@codeloupe' }])), 'mcp get': { code: 1, stdout: '', stderr: '' } });
    expect(await new ClaudeConnector(f.run, () => null).status()).toEqual({ cli: true, mcp: false, plugin: true });
    const none = fake({ 'plugin list': ok('[]') });
    expect(await new ClaudeConnector(none.run, () => null).status()).toEqual({ cli: true, mcp: true, plugin: false });
  });
});

describe('findMarketplace', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cl-market-'));
  afterAll(() => fs.rmSync(dir, { recursive: true, force: true }));

  it('prefers the override, then the packaged folder, and needs the manifest', () => {
    const packaged = path.join(dir, 'res', 'claude-plugin');
    fs.mkdirSync(path.join(packaged, '.claude-plugin'), { recursive: true });
    expect(findMarketplace({ env: {}, resources: path.join(dir, 'res') })).toBeNull();
    fs.writeFileSync(path.join(packaged, '.claude-plugin', 'marketplace.json'), '{}');
    expect(findMarketplace({ env: {}, resources: path.join(dir, 'res') })).toBe(packaged);
    expect(findMarketplace({ env: { CODELOUPE_PLUGIN_DIR: packaged }, resources: null })).toBe(packaged);
  });

  it('finds the repository root of a development run, which holds the real manifest', () => {
    const repo = findMarketplace({ env: {}, appDir: path.resolve(__dirname, '..', 'out', 'main') });
    expect(repo).toBe(path.resolve(__dirname, '..', '..'));
  });
});

// The clean-profile check: the real claude CLI against a throwaway HOME / CLAUDE_CONFIG_DIR, never the user's own.
// Skipped where claude is not installed (CI).
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'cl-profile-'));
const env: NodeJS.ProcessEnv = {
  ...process.env, HOME: profile, USERPROFILE: profile, CLAUDE_CONFIG_DIR: path.join(profile, 'cfg'),
  APPDATA: path.join(profile, 'appdata'), LOCALAPPDATA: path.join(profile, 'local'),
};
afterAll(() => fs.rmSync(profile, { recursive: true, force: true }));
describe.skipIf(!findClaude(process.env))('Connect on a clean profile (real claude CLI)', () => {
  const marketplace = path.resolve(__dirname, '..', '..');
  const connector = new ClaudeConnector(execClaude(env), () => marketplace);

  it('starts with nothing connected', async () => {
    expect(await connector.status()).toEqual({ cli: true, mcp: false, plugin: false });
  });

  it('adds the MCP server, and again with another port', async () => {
    expect((await connector.connect('mcp', 47391)).ok).toBe(true);
    expect((await connector.connect('mcp', 47392)).ok).toBe(true);
    const shown = execFileSync(findClaude(env)!, ['mcp', 'get', 'codeloupe'], { env, encoding: 'utf8' });
    expect(shown).toContain('http://127.0.0.1:47392/mcp');
    expect(shown).toContain('x-codeloupe: 1');
    expect((await connector.status()).mcp).toBe(true);
  }, 60_000);

  it('installs the plugin, also when it is already installed', async () => {
    expect((await connector.connect('plugin', 47391)).ok).toBe(true);
    expect((await connector.connect('plugin', 47391)).ok).toBe(true);
    expect((await connector.status()).plugin).toBe(true);
  }, 60_000);

  it('left the profile folder holding the config, not the real home', () => {
    expect(fs.existsSync(path.join(profile, 'cfg', '.claude.json'))).toBe(true);
    expect(fs.existsSync(path.join(profile, 'cfg', 'plugins', 'installed_plugins.json'))).toBe(true);
  });
});
