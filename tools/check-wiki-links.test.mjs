import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { checkWiki, slugs, targets } from './check-wiki-links.mjs';

const W = 'https://github.com/Terrio-cz/CodeLoupe/wiki';
const B = 'https://github.com/Terrio-cz/CodeLoupe/blob/main';

/** A small repository: README, docs/plan.md and a wiki; `edit` can change files before the check. */
function repo(files = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wiki-'));
  const all = {
    'README.md': `# T\n\n[Guide](${W}/Guide#install)  ![x](docs/pic.svg)  [plan](docs/plan.md)\n`,
    'docs/plan.md': '# Plan\n',
    'docs/pic.svg': '<svg/>',
    'docs/wiki/Home.md': '[Guide](Guide)\n',
    'docs/wiki/Guide.md': `## Install\n\nBack to [Home](Home), [plan](${B}/docs/plan.md), [up](#install).\n`,
    'docs/wiki/_Sidebar.md': '* [Home](Home)\n* [Guide](Guide)\n',
    'docs/wiki/_Footer.md': 'footer\n',
    ...files,
  };
  for (const [name, text] of Object.entries(all)) {
    if (text === null) continue;
    fs.mkdirSync(path.dirname(path.join(root, name)), { recursive: true });
    fs.writeFileSync(path.join(root, name), text);
  }
  return root;
}

test('a consistent wiki passes', () => {
  assert.deepEqual(checkWiki(repo()), []);
});

test('this repository passes', () => {
  const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
  assert.deepEqual(checkWiki(root), []);
});

test('refuses a wiki without Home.md', () => {
  assert.deepEqual(checkWiki(repo({ 'docs/wiki/Home.md': null })), ['docs/wiki/Home.md is missing']);
});

test('finds a link to a page that does not exist', () => {
  const problems = checkWiki(repo({ 'docs/wiki/Home.md': '[Guide](Guide) [gone](Missing-page)\n' }));
  assert.equal(problems.length, 1);
  assert.match(problems[0], /Home\.md:1: wiki page "Missing-page" does not exist/);
});

test('finds a link to a heading that does not exist', () => {
  const problems = checkWiki(repo({ 'docs/wiki/Home.md': '[Guide](Guide#nope) [same](#nowhere)\n' }));
  assert.equal(problems.length, 2);
  assert.match(problems[0], /"Guide" has no heading #nope/);
  assert.match(problems[1], /no heading #nowhere on this page/);
});

test('refuses relative paths and .md suffixes inside the wiki', () => {
  const problems = checkWiki(repo({ 'docs/wiki/Home.md': '[a](Guide.md) [b](../plan.md) [c](docs/plan.md)\n' }));
  assert.equal(problems.length, 3);
  assert.ok(problems.every(p => /does not work inside a wiki/.test(p)));
});

test('finds a repository URL whose file is gone', () => {
  const problems = checkWiki(repo({ 'docs/wiki/Home.md': `[Guide](Guide) [x](${B}/docs/gone.md) <img src="https://raw.githubusercontent.com/Terrio-cz/CodeLoupe/main/docs/gone.svg">\n` }));
  assert.equal(problems.length, 2);
  assert.match(problems[0], /docs\/gone\.md does not exist in the repository/);
});

test('finds a README link to a missing wiki page, heading, file or image', () => {
  const problems = checkWiki(repo({
    'README.md': `[a](${W}/Nope) [b](${W}/Guide#nope) [c](docs/missing.md) <img src="docs/missing.svg"> [d](${W})\n`,
  }));
  assert.equal(problems.length, 4);
  assert.match(problems[0], /README\.md:1: wiki page "Nope" does not exist/);
  assert.match(problems[1], /"Guide" has no heading #nope/);
});

test('a page missing from the sidebar is reported', () => {
  const problems = checkWiki(repo({ 'docs/wiki/Extra.md': '# Extra\n' }));
  assert.deepEqual(problems, ['docs/wiki/Extra.md: not linked from _Sidebar.md']);
});

test('ignores links inside code', () => {
  const text = '[Guide](Guide)\n\n```\n[x](Nope)\n```\n\nand `[y](Nope)` inline\n';
  assert.deepEqual(checkWiki(repo({ 'docs/wiki/Home.md': text })), []);
});

test('slugs follow GitHub: lowercase, punctuation dropped, repeats numbered', () => {
  const s = slugs('## Editing by declaration (`edit`)\n\n## 1. Plugin (recommended)\n\n## Same\n\n## Same\n\n```\n## not a heading\n```\n');
  assert.deepEqual([...s], ['editing-by-declaration-edit', '1-plugin-recommended', 'same', 'same-1']);
});

test('targets reads markdown links, images and srcset', () => {
  const t = targets('[a](x) ![b](y.png) <source srcset="s.svg"> <img src="i.svg">\n').map(x => x.target);
  assert.deepEqual(t, ['x', 'y.png', 'i.svg', 's.svg']);
});
