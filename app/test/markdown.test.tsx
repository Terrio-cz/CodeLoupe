import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { allowedOrigins, MarkdownView } from '../src/renderer/src/components/MarkdownView';

const none = allowedOrigins([]);

describe('MarkdownView', () => {
  it('terminates on a lone carriage return and other odd line breaks', () => {
    const html = renderToStaticMarkup(<MarkdownView source={'# a\rb\u2028c\n\n- [x] done\n```\ncode'} allowed={none} />);
    expect(html).toContain('<h2>a</h2>');
    expect(html).toContain('done');
  });

  it('never renders raw HTML', () => {
    const html = renderToStaticMarkup(<MarkdownView source={'<img src=x onerror=alert(1)> **b** [x](javascript:alert(1))'} allowed={none} />);
    expect(html).not.toContain('<img');
    expect(html).toContain('&lt;img');
    expect(html).not.toContain('<button');
  });

  it('makes only links to a configured YouTrack origin clickable', () => {
    const allowed = allowedOrigins(['https://terrio.youtrack.cloud']);
    const html = renderToStaticMarkup(<MarkdownView source={'[ok](https://terrio.youtrack.cloud/issue/TER-1) [no](https://terrio.youtrack.cloud.evil.com/x)'} allowed={allowed} />);
    expect(html.match(/<button/g)).toHaveLength(1);
    expect(html).toContain('>ok</button>');
    expect(html).toContain('<span title="https://terrio.youtrack.cloud.evil.com/x">no</span>');
  });
});
