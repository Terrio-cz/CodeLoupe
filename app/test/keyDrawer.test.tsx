import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { KeyDrawer } from '../src/renderer/src/components/KeyDrawer';

const noop = () => undefined;
const valueInput = (html: string) => /<input[^>]*id="key-value"[^>]*>/.exec(html)![0];

describe('KeyDrawer', () => {
  it('asks for the value in a password field that autofill, spellcheck and the markup leave empty', () => {
    const field = valueInput(renderToStaticMarkup(<KeyDrawer onClose={noop} onSaved={noop} />));
    expect(field).toContain('type="password"');
    expect(field).toContain('autoComplete="new-password"');
    expect(field).toContain('spellCheck="false"');
    expect(field).not.toMatch(/\svalue=/);
  });

  it('rotates a fixed name and scope, with no old value to show', () => {
    const html = renderToStaticMarkup(<KeyDrawer rotate={{ name: 'GITHUB_TOKEN', scope: 'repo', scopeRef: 'c:/work/app' }} onClose={noop} onSaved={noop} />);
    expect(html).toContain('Rotate GITHUB_TOKEN');
    expect(html).toContain('New value');
    expect(/<input[^>]*id="key-name"[^>]*>/.exec(html)![0]).toContain('disabled');
    expect(valueInput(html)).not.toMatch(/\svalue=/);
    expect(html).toContain('c:/work/app');
  });
});
