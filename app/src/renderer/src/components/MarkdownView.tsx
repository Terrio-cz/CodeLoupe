import type { ReactNode } from 'react';
import { bridge } from '../api';

/**
 * Renders untrusted markdown from the YouTrack mirror as React elements: headings, paragraphs, lists,
 * checkboxes, inline code, code blocks and links. Never raw HTML; links open only through main's
 * allow-list (exact origin of a configured YouTrack instance), otherwise they stay text.
 */
export function MarkdownView({ source }: { source: string }) {
  const blocks: ReactNode[] = [];
  const lines = source.replace(/\r\n/g, '\n').split('\n');
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
      blocks.push(h[1].length <= 2 ? <h2 key={blocks.length}>{inline(h[2])}</h2> : <h3 key={blocks.length}>{inline(h[2])}</h3>);
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
            {box ? <><span aria-label={box[1] === ' ' ? 'nesplněno' : 'splněno'} role="img">{box[1] === ' ' ? '☐' : '☑'}</span> {inline(box[2])}</> : inline(text)}
          </li>,
        );
      }
      blocks.push(<ul key={blocks.length}>{items}</ul>);
      continue;
    }
    if (!line.trim()) { i++; continue; }
    const para: string[] = [];
    for (; i < lines.length && lines[i].trim() && !/^(#{1,6}\s|```|\s*[-*]\s)/.test(lines[i]); i++) para.push(lines[i]);
    blocks.push(<p key={blocks.length}>{inline(para.join(' '))}</p>);
  }
  return <div className="md">{blocks}</div>;
}

function inline(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  const re = /`([^`]+)`|\[([^\]]+)\]\(([^)\s]+)\)|\*\*([^*]+)\*\*/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(text.slice(last, m.index));
    if (m[1] !== undefined) out.push(<code key={out.length}>{m[1]}</code>);
    else if (m[2] !== undefined) {
      const url = m[3];
      out.push(
        /^https:\/\//.test(url)
          ? <button key={out.length} className="link" onClick={() => void bridge().open.external(url)} title={url}>{m[2]}</button>
          : <span key={out.length}>{m[2]}</span>,
      );
    } else if (m[4] !== undefined) out.push(<strong key={out.length}>{m[4]}</strong>);
    last = re.lastIndex;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}
