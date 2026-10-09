#!/usr/bin/env node
// Release notes from the commit subjects between two tags (CL-106): one entry per card (`CL-<n> <imperative subject>`),
// merges skipped, commits without a card id under "Other". Prints Markdown.
//
//   node tools/release-notes.mjs <tag> [--from <previous tag>]
//
// Without --from the previous `v*` tag reachable from <tag> is used; with none, the whole history.
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const CARD = /^(CL-\d+)\s+(.+)$/;
const MERGE = /^Merge (remote-tracking )?(branch|pull request)/;

/** Groups commit subjects by card; the order inside a card is the commit order, cards sort by number. */
export function groupSubjects(subjects) {
  const cards = new Map();
  const other = [];
  for (const subject of subjects) {
    if (!subject || MERGE.test(subject)) continue;
    const m = CARD.exec(subject);
    if (!m) { other.push(subject); continue; }
    if (!cards.has(m[1])) cards.set(m[1], []);
    cards.get(m[1]).push(m[2]);
  }
  const sorted = [...cards.entries()].sort((a, b) => Number(a[0].slice(3)) - Number(b[0].slice(3)));
  return { cards: sorted, other };
}

export function render({ version, from, repo, subjects }) {
  const { cards, other } = groupSubjects(subjects);
  const lines = [`## CodeLoupe ${version}`, ''];
  lines.push(from ? `Changes since ${from}.` : 'First release.', '');
  if (cards.length === 0 && other.length === 0) lines.push('No changes recorded.', '');
  for (const [id, entries] of cards) {
    const link = repo ? `[${id}](https://github.com/${repo}/commits?q=${id})` : id;
    lines.push(`- **${link}** ${entries.join('; ')}`);
  }
  if (other.length) lines.push('', '### Other', '', ...other.map(s => `- ${s}`));
  if (repo) {
    lines.push(
      '', '### Verify the files', '',
      '- Checksums: `sha256sum -c SHA256SUMS.txt` in the folder with the downloaded files.',
      `- Provenance (built by this repository's release workflow from this tag): \`gh attestation verify <file> --repo ${repo}\`.`,
    );
  }
  return lines.join('\n') + '\n';
}

function git(...args) {
  return execFileSync('git', args, { encoding: 'utf8' }).trim();
}

function main(argv) {
  const [tag, ...rest] = argv;
  if (!tag) { console.error('usage: release-notes.mjs <tag> [--from <previous tag>]'); process.exit(2); }
  const fromIndex = rest.indexOf('--from');
  let from = fromIndex >= 0 ? rest[fromIndex + 1] : null;
  if (!from) {
    try { from = git('describe', '--tags', '--abbrev=0', '--match', 'v*', `${tag}^`); } catch { from = null; }
  }
  const range = from ? `${from}..${tag}` : tag;
  const subjects = git('log', '--no-merges', '--reverse', '--format=%s', range).split(/\r?\n/);
  process.stdout.write(render({ version: tag.replace(/^v/, ''), from, repo: process.env.GITHUB_REPOSITORY, subjects }));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main(process.argv.slice(2));
