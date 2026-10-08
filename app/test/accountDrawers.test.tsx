import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ClaudeAccountDrawer, RenameDrawer, TokenDrawer, YoutrackAccountDrawer } from '../src/renderer/src/components/AccountDrawers';

const noop = () => undefined;
const tokenField = (html: string) => /<input[^>]*id="acct-token"[^>]*>/.exec(html)![0];

describe('account drawers', () => {
  it('ask for a token in a password field that the markup leaves empty, with the storage note', () => {
    for (const html of [renderToStaticMarkup(<YoutrackAccountDrawer onClose={noop} onDone={noop} />), renderToStaticMarkup(<TokenDrawer id="terrio" label="Terrio" onClose={noop} onDone={noop} />)]) {
      const field = tokenField(html);
      expect(field).toContain('type="password"');
      expect(field).toContain('autoComplete="new-password"');
      expect(field).not.toMatch(/\svalue=/);
      expect(html).toContain('po uložení se už nikde nezobrazí');
    }
  });

  it('say that adding a YouTrack account restarts the daemon, and that a Claude account is only a folder', () => {
    expect(renderToStaticMarkup(<YoutrackAccountDrawer onClose={noop} onDone={noop} />)).toContain('restartuje daemon');
    const claude = renderToStaticMarkup(<ClaudeAccountDrawer onClose={noop} onDone={noop} />);
    expect(claude).toContain('CLAUDE_CONFIG_DIR');
    expect(claude).toContain('Vytvořit složku');
    expect(claude).not.toContain('type="password"');
  });

  it('renames with the current name filled in', () => {
    expect(renderToStaticMarkup(<RenameDrawer id="b" label="Účet B" onClose={noop} onDone={noop} />)).toContain('value="Účet B"');
  });
});
