import type { ReactNode } from 'react';
import { bridge, useApi } from '../api';

/**
 * Renders untrusted markdown from the YouTrack mirror as React elements: headings, paragraphs, lists,
 * checkboxes, inline code, code blocks and links. Never raw HTML. A link is clickable only when its origin
 * is a configured YouTrack instance (main checks the same again); any other link stays text.
 */
export function MarkdownView({ source }: { source: string }) {
  const settings = useApi('settings');
  const allowed = new Set((settings.data?.youtrack ?? []).map(y => originOf(y.url)).filter((o): o is string => !!o));
  const blocks: ReactNode[] = [];
  // Every line break form becomes \n: a lone \r would otherwise match no block rule.
  const lines = source.replace(/\r\n?|\u2028|\u2029/g, '\n').split('\n');
  const md = (text: string) => inline(text, allowed);
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.startsWith('```')) {
      const code: string[] = [];
      for (i++; i < lines.length && !lines[i].startsWith('```'); i++) code.push(lines[i]);
      i++;
      blocks.push(<pre key={blocks.length}><code>{code.join('\n')}</code></pre>);
      continue;
    }
    const h = /^(#{1,6})\s+(.*)$/.exec(line);
    if (h) {
      blocks.push(h[1].length <= 2 ? <h2 key={blocks.length}>{md(h[2])}</h2> : <h3 key={blocks.length}>{md(h[2])}</h3>);
      i++;
      continue;
    }
    if (/^\s*[-*]\s+/.test(line)) {
      const items: ReactNode[] = [];
      for (; i < lines.length && /^\s*[-*]\s+/.test(lines[i]); i++) {
        const text = lines[i].replace(/^\s*[-*]\s+/, '');
        const box = /^\[( |x|X)\]\s+(.*)$/.exec(text);
        items.push(
          <li key={items.length}>
            {box ? <><span aria-label={box[1] === ' ' ? 'nesplněno' : 'splněno'} role="img">{box[1] === ' ' ? '☐' : '☑'}</span> {md(box[2])}</> : md(text)}
          </li>,
        );
      }
      blocks.push(<ul key={blocks.length}>{items}</ul>);
      continue;
    }
    if (!line.trim()) { i++; continue; }
    const para: string[] = [];
    for (; i < lines.length && lines[i].trim() && !/^(#{1,6}\s|```|\s*[-*]\s)/.test(lines[i]); i++) para.push(lines[i]);
    // Always consume a line, so text that no rule matches can never stall the loop.
    if (para.length === 0) para.push(lines[i++]);
    blocks.push(<p key={blocks.length}>{md(para.join(' '))}</p>);
  }
  return <div className="md">{blocks}</div>;
}

function inline(text: string, allowed: Set<string>): ReactNode[] {
  const out: ReactNode[] = [];
  const re = /`([^`]+)`|\[([^\]]+)\]\(([^)\s]+)\)|\*\*([^*]+)\*\*/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(text.slice(last, m.index));
    if (m[1] !== undefined) out.push(<code key={out.length}>{m[1]}</code>);
    else if (m[2] !== undefined) {
      const url = m[3];
      const origin = originOf(url);
      out.push(
        url.startsWith('https://') && origin && allowed.has(origin)
          ? <button key={out.length} className="link" onClick={() => void bridge().open.external(url)} title={url}>{m[2]}</button>
          : <span key={out.length} title={url}>{m[2]}</span>,
      );
    } else if (m[4] !== undefined) out.push(<strong key={out.length}>{m[4]}</strong>);
    last = re.lastIndex;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

export function originOf(url: string): string | null {
  try {
    return new URL(url).origin;
  } catch {
    return null;
  }
}
