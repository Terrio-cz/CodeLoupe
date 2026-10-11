import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { MARKETPLACE_NAME, PLUGIN_ID, SERVER_NAME } from '../src/main/claude/ClaudeConnector';

const root = path.resolve(__dirname, '..', '..');
const json = (...p: string[]) => JSON.parse(fs.readFileSync(path.join(root, ...p), 'utf8'));

// Structure the claude CLI also validates (`claude plugin validate`), checked here where CI has no claude.
describe('Claude Code plugin files', () => {
  const marketplace = json('.claude-plugin', 'marketplace.json');
  const entry = marketplace.plugins[0];
  const pluginDir = path.join(root, entry.source);

  it('has a marketplace entry that the app installs by name', () => {
    expect(marketplace.name).toBe(MARKETPLACE_NAME);
    expect(`${entry.name}@${marketplace.name}`).toBe(PLUGIN_ID);
    expect(fs.existsSync(path.join(pluginDir, '.claude-plugin', 'plugin.json'))).toBe(true);
    expect(JSON.parse(fs.readFileSync(path.join(pluginDir, '.claude-plugin', 'plugin.json'), 'utf8')).name).toBe(entry.name);
  });

  it('declares the daemon as an http MCP server with the guard header and no secret', () => {
    const server = JSON.parse(fs.readFileSync(path.join(pluginDir, '.mcp.json'), 'utf8')).mcpServers[SERVER_NAME];
    expect(server.type).toBe('http');
    expect(server.url).toBe('http://127.0.0.1:${CODELOUPE_PORT:-47391}/mcp');
    expect(server.headers).toEqual({ 'x-codeloupe': '1' });
  });

  it('gets the daemon token for the MCP entry from a helper script that exists, not from the configuration', () => {
    const server = JSON.parse(fs.readFileSync(path.join(pluginDir, '.mcp.json'), 'utf8')).mcpServers[SERVER_NAME];
    expect(server.headersHelper).toContain('${CLAUDE_PLUGIN_ROOT}/hooks/mcp-headers.cmd');
    const script = fs.readFileSync(path.join(pluginDir, 'hooks', 'mcp-headers.sh'), 'utf8');
    expect(script.trimEnd().endsWith('exit 0')).toBe(true);
    expect(script).toContain('codeloupe_prove');
    expect(fs.existsSync(path.join(pluginDir, 'hooks', 'daemon-auth.sh'))).toBe(true);
    expect(JSON.stringify(server)).not.toMatch(/[0-9a-f]{32}/);
  });

  // Claude Code runs a headersHelper through cmd.exe on Windows, where a bare `bash` is the WSL launcher (System32) and exits 127 on a Windows path (CL-228).
  it('starts the headers helper without a bare bash, which is the WSL launcher on Windows', () => {
    const server = JSON.parse(fs.readFileSync(path.join(pluginDir, '.mcp.json'), 'utf8')).mcpServers[SERVER_NAME];
    const [unix, windows] = (server.headersHelper as string).split(' || ');
    expect(unix).toBe('sh "${CLAUDE_PLUGIN_ROOT}/hooks/mcp-headers.cmd"');
    expect(windows).toBe('"${CLAUDE_PLUGIN_ROOT}/hooks/mcp-headers.cmd"');
    expect(server.headersHelper).not.toMatch(/(^|[\s&|;(])bash\b/);
    const launcher = fs.readFileSync(path.join(pluginDir, 'hooks', 'mcp-headers.cmd'), 'utf8');
    // sh reads the first line and execs the bash script; cmd.exe takes that line for a label
    expect(launcher.split('\n')[0]).toMatch(/^:; .*exec "\$b" "\$\(dirname "\$0"\)\/mcp-headers\.sh"$/);
    expect(launcher).not.toContain('\r'); // a CR would end up in the sh line
    // cmd.exe finds Git Bash itself (the one Claude Code needs) and never runs what `bash` resolves to
    expect(launcher).toContain('CLAUDE_CODE_GIT_BASH_PATH');
    expect(launcher).toContain('\\Git\\bin\\bash.exe');
    expect(launcher).not.toMatch(/^\s*(bash|wsl)\b/m);
    expect(launcher).not.toMatch(/^\s*"?bash(\.exe)?"?\s/m);
    // and without Git Bash it still answers: the plain header, exit 0
    expect(launcher).toContain('echo {"x-codeloupe":"1"}');
    expect(launcher.trimEnd().endsWith('exit /b 0')).toBe(true);
  });

  it('starts the daemon from a SessionStart hook whose script exists and never fails the session', () => {
    const hooks = JSON.parse(fs.readFileSync(path.join(pluginDir, 'hooks', 'hooks.json'), 'utf8')).hooks.SessionStart[0].hooks[0];
    expect(hooks.type).toBe('command');
    expect(hooks.command).toContain('${CLAUDE_PLUGIN_ROOT}/hooks/start-daemon.sh');
    const script = fs.readFileSync(path.join(pluginDir, 'hooks', 'start-daemon.sh'), 'utf8');
    expect(script).toContain('"$bin" start');
    expect(script.trimEnd().endsWith('exit 0')).toBe(true);
  });

  it('steers shell searches and whole-file reads through a PreToolUse hook that forwards to the daemon and never fails a call', () => {
    const pre = JSON.parse(fs.readFileSync(path.join(pluginDir, 'hooks', 'hooks.json'), 'utf8')).hooks.PreToolUse[0];
    expect(pre.matcher).toBe('Bash|PowerShell|Read');
    expect(pre.hooks[0].type).toBe('command');
    expect(pre.hooks[0].command).toContain('${CLAUDE_PLUGIN_ROOT}/hooks/hook.sh');
    expect(pre.hooks[0].timeout).toBeLessThanOrEqual(10);
    const script = fs.readFileSync(path.join(pluginDir, 'hooks', 'hook.sh'), 'utf8');
    expect(script).toContain('CODELOUPE_HOOKS');
    expect(script).toContain('/hook');
    expect(script.trimEnd().endsWith('exit 0')).toBe(true);
    expect(script).not.toContain('set -e');
  });

  it('tells the user when a session carries too much from UserPromptSubmit and Stop, through the same forwarding script', () => {
    const hooks = JSON.parse(fs.readFileSync(path.join(pluginDir, 'hooks', 'hooks.json'), 'utf8')).hooks;
    for (const event of ['UserPromptSubmit', 'Stop']) {
      expect(hooks[event][0].hooks[0].command).toContain('${CLAUDE_PLUGIN_ROOT}/hooks/hook.sh');
      expect(hooks[event][0].hooks[0].timeout).toBeLessThanOrEqual(10);
    }
  });

  it('has a skill that names every tool of the daemon', () => {
    const skill = fs.readFileSync(path.join(pluginDir, 'skills', 'codeloupe', 'SKILL.md'), 'utf8');
    expect(skill).toMatch(/^---\nname: codeloupe\ndescription: .+\n---/);
    for (const tool of ['find', 'outline', 'symbol', 'usages', 'calls', 'hierarchy', 'changes', 'task_code', 'issue', 'tasks', 'update', 'job']) {
      expect(skill).toContain(`\`${tool}\``);
    }
  });
});
