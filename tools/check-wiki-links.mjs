#!/usr/bin/env node
// Checks the links of the wiki sources (docs/wiki) and the wiki links of the README, so a renamed page or heading cannot
// leave a dead link behind:
//   - in docs/wiki, a link to another page is its file name without .md (`[x](Page-Name#heading)`); a link to the
//     repository is a full github.com/<repo>/blob|tree/<branch>/<path> or raw.githubusercontent.com URL whose path must
//     exist here; images likewise. Relative paths and `.md` suffixes do not work inside a wiki and are refused.
//   - in README.md, every https://github.com/<repo>/wiki/<Page>[#anchor] must be an existing page and heading, every
//     relative link and image must exist, and `#anchor` must be a heading of the README.
//   - every page except Home, _Sidebar and _Footer must be linked from _Sidebar.md.
// Other external URLs are not fetched. Anchors use GitHub's slug rules.
//
//   node tools/check-wiki-links.mjs [--root <repository root>]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const REPO = 'Terrio-cz/CodeLoupe';
const SPECIAL = new Set(['_Sidebar', '_Footer']);

/** GitHub's heading anchor: lowercase, markup and punctuation dropped, spaces to hyphens, repeats numbered. */
export function slugs(markdown) {
  const seen = new Map();
  const out = new Set();
  let fenced = false;
  for (const line of markdown.split(/\r?\n/)) {
    if (/^\s*(```|~~~)/.test(line)) { fenced = !fenced; continue; }
    if (fenced) continue;
    const m = /^ {0,3}#{1,6}\s+(.*?)\s*#*\s*$/.exec(line);
    if (!m) continue;
    const text = m[1].replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/[`*_~]/g, c => (c === '_' ? c : ''));
    const base = text.toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, '').trim().replace(/\s/g, '-');
    const n = seen.get(base) ?? 0;
    seen.set(base, n + 1);
    out.add(n === 0 ? base : `${base}-${n}`);
  }
  return out;
}

/** The link and image targets of a markdown text, outside code fences and inline code, with their line numbers. */
export function targets(markdown) {
  const found = [];
  let fenced = false;
  markdown.split(/\r?\n/).forEach((raw, i) => {
    if (/^\s*(```|~~~)/.test(raw)) { fenced = !fenced; return; }
    if (fenced) return;
    const line = raw.replace(/`[^`]*`/g, '');
    for (const m of line.matchAll(/\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g)) found.push({ line: i + 1, target: m[1] });
    for (const m of line.matchAll(/\b(?:src|href)="([^"]+)"/g)) found.push({ line: i + 1, target: m[1] });
    for (const m of line.matchAll(/\bsrcset="([^"]+)"/g)) for (const part of m[1].split(',')) found.push({ line: i + 1, target: part.trim().split(/\s+/)[0] });
  });
  return found;
}

const exists = p => fs.existsSync(p);

/** Problems of the wiki sources and the README under `root`; an empty list means every link resolves. */
export function checkWiki(root) {
  const problems = [];
  const wikiDir = path.join(root, 'docs', 'wiki');
  if (!exists(path.join(wikiDir, 'Home.md'))) return ['docs/wiki/Home.md is missing'];

  const pages = new Map();
  for (const f of fs.readdirSync(wikiDir)) {
    if (!f.endsWith('.md')) { problems.push(`docs/wiki/${f}: only .md files belong in the wiki`); continue; }
    if (/\s/.test(f)) problems.push(`docs/wiki/${f}: a page name has no spaces (use hyphens)`);
    pages.set(f.slice(0, -3), fs.readFileSync(path.join(wikiDir, f), 'utf8'));
  }
  const anchorsOf = new Map([...pages].map(([name, text]) => [name, slugs(text)]));
  const readme = fs.readFileSync(path.join(root, 'README.md'), 'utf8');
  const readmeAnchors = slugs(readme);

  const repoUrl = new RegExp(`^https://github\\.com/${REPO}/(?:blob|tree)/[^/]+/(.*)$`);
  const rawUrl = new RegExp(`^https://raw\\.githubusercontent\\.com/${REPO}/[^/]+/(.*)$`);
  const wikiUrl = new RegExp(`^https://github\\.com/${REPO}/wiki(?:/([^#?]*))?(?:#(.*))?$`);

  /** Checks one target; `from` names the file, `inWiki` selects the wiki's rules for relative targets. */
  const check = (from, line, target, inWiki, ownAnchors) => {
    const where = `${from}:${line}`;
    const wiki = wikiUrl.exec(target);
    if (wiki) {
      const page = decodeURIComponent(wiki[1] || 'Home');
      if (!pages.has(page)) problems.push(`${where}: wiki page "${page}" does not exist (${target})`);
      else if (wiki[2] && !anchorsOf.get(page).has(wiki[2])) problems.push(`${where}: "${page}" has no heading #${wiki[2]}`);
      return;
    }
    const repo = repoUrl.exec(target) ?? rawUrl.exec(target);
    if (repo) {
      const [file, anchor] = decodeURIComponent(repo[1]).split('#');
      const local = path.join(root, file.replace(/\/$/, ''));
      if (!exists(local)) problems.push(`${where}: ${file} does not exist in the repository`);
      else if (anchor && file.endsWith('.md') && !slugs(fs.readFileSync(local, 'utf8')).has(anchor)) problems.push(`${where}: ${file} has no heading #${anchor}`);
      return;
    }
    if (/^[a-z][a-z0-9+.-]*:/i.test(target) || target.startsWith('//')) return;   // other external links are not fetched
    const [pathPart, anchor] = target.split('#');
    if (pathPart === '') {
      if (anchor && !ownAnchors.has(anchor)) problems.push(`${where}: no heading #${anchor} on this page`);
      return;
    }
    if (inWiki) {
      if (pathPart.includes('/') || pathPart.endsWith('.md') || /\.[a-z]{2,4}$/i.test(pathPart)) {
        problems.push(`${where}: "${target}" does not work inside a wiki; link a page as Page-Name or a file by its full github.com URL`);
        return;
      }
      const page = decodeURIComponent(pathPart);
      if (!pages.has(page)) problems.push(`${where}: wiki page "${page}" does not exist`);
      else if (anchor && !anchorsOf.get(page).has(anchor)) problems.push(`${where}: "${page}" has no heading #${anchor}`);
      return;
    }
    const local = path.join(root, decodeURIComponent(pathPart));
    if (!exists(local)) problems.push(`${where}: ${pathPart} does not exist`);
  };

  for (const [name, text] of pages) {
    for (const { line, target } of targets(text)) check(`docs/wiki/${name}.md`, line, target, true, anchorsOf.get(name));
  }
  for (const { line, target } of targets(readme)) check('README.md', line, target, false, readmeAnchors);

  const sidebar = pages.get('_Sidebar') ?? '';
  const linked = new Set(targets(sidebar).map(t => decodeURIComponent(t.target.split('#')[0])));
  for (const name of pages.keys()) {
    if (name !== 'Home' && !SPECIAL.has(name) && !linked.has(name)) problems.push(`docs/wiki/${name}.md: not linked from _Sidebar.md`);
  }
  for (const name of ['_Sidebar', '_Footer']) if (!pages.has(name)) problems.push(`docs/wiki/${name}.md is missing`);
  return problems;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const root = path.resolve(args.includes('--root') ? args[args.indexOf('--root') + 1] : path.join(path.dirname(fileURLToPath(import.meta.url)), '..'));
  const problems = checkWiki(root);
  for (const p of problems) console.error(p);
  if (problems.length === 0) console.log('wiki links hold');
  process.exit(problems.length === 0 ? 0 : 1);
}
