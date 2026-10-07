import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { MarkdownView } from '../src/renderer/src/components/MarkdownView';

describe('MarkdownView', () => {
  it('terminates on a lone carriage return and other odd line breaks', () => {
    const html = renderToStaticMarkup(<MarkdownView source={'# a\rb c\n\n- [x] done\n```\ncode'} />);
    expect(html).toContain('<h2>a</h2>');
    expect(html).toContain('done');
  });

  it('never renders raw HTML', () => {
    const html = renderToStaticMarkup(<MarkdownView source={'<img src=x onerror=alert(1)> **b** [x](javascript:alert(1))'} />);
    expect(html).not.toContain('<img');
    expect(html).toContain('&lt;img');
    expect(html).not.toContain('<button');
  });
});
