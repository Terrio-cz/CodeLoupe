import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { PluginVersionNotice } from '../src/renderer/src/components/ClaudeCodeCard';
import { PLUGIN_ID } from '../src/main/claude/ClaudeConnector';
import type { ClaudeStatus } from '../src/shared/ipc';
import { PLUGIN_REFRESH_COMMAND, pluginVersionState } from '../src/shared/pluginVersion';

describe('pluginVersionState', () => {
  it('flags an installed plugin at another version than the one the app ships', () => {
    expect(pluginVersionState('0.1.1', '0.1.9')).toBe('differs');
    expect(pluginVersionState('0.1.9', '0.1.9')).toBe('same');
  });

  it('compares nothing when a version is unknown, as in a development run', () => {
    expect(pluginVersionState(null, '0.1.9')).toBe('same');
    expect(pluginVersionState('0.1.9', null)).toBe('same');
    expect(pluginVersionState(null, null)).toBe('same');
  });

  it('does not flag a plugin that follows the commits of a GitHub marketplace, which has no version of the app', () => {
    expect(pluginVersionState('ced9a6d21cdc', '0.1.9')).toBe('tracks-repository');
    expect(pluginVersionState('unknown', '0.1.9')).toBe('tracks-repository');
  });

  it('refreshes the plugin this app installs', () => {
    expect(PLUGIN_REFRESH_COMMAND).toContain(`claude plugin update ${PLUGIN_ID}`);
    expect(PLUGIN_REFRESH_COMMAND).toContain(`claude plugin marketplace update ${PLUGIN_ID.split('@')[1]}`);
  });
});

describe('PluginVersionNotice', () => {
  const status = (over: Partial<ClaudeStatus>): ClaudeStatus => ({ cli: true, mcp: true, plugin: true, mcpVia: 'plugin', pluginVersion: '0.1.1', bundledPluginVersion: '0.1.9', ...over });

  it('names both versions and the refresh command when they differ', () => {
    const html = renderToStaticMarkup(<PluginVersionNotice status={status({})} />);
    expect(html).toContain('v0.1.1');
    expect(html).toContain('v0.1.9');
    expect(html.replaceAll('&amp;', '&')).toContain(PLUGIN_REFRESH_COMMAND);
  });

  it('says nothing when they match, the plugin is not installed, or a version is unknown', () => {
    expect(renderToStaticMarkup(<PluginVersionNotice status={status({ pluginVersion: '0.1.9' })} />)).toBe('');
    expect(renderToStaticMarkup(<PluginVersionNotice status={status({ plugin: false, pluginVersion: null })} />)).toBe('');
    expect(renderToStaticMarkup(<PluginVersionNotice status={status({ bundledPluginVersion: null })} />)).toBe('');
  });
});
