import assert from 'node:assert/strict';
import { test } from 'node:test';
import { groupSubjects, render } from './release-notes.mjs';

test('groups subjects by card, skips merges and keeps the rest', () => {
  const { cards, other } = groupSubjects([
    'CL-104 add electron-builder installers',
    'Merge remote-tracking branch \'origin/main\' into CL-104',
    'CL-9 earlier card',
    'CL-104 use the brand icon',
    'tidy the readme',
    '',
  ]);
  assert.deepEqual(cards, [['CL-9', ['earlier card']], ['CL-104', ['add electron-builder installers', 'use the brand icon']]]);
  assert.deepEqual(other, ['tidy the readme']);
});

test('renders a heading, the base and one line per card', () => {
  const text = render({ version: '0.0.1-rc1', from: 'v0.0.0', repo: 'Terrio-cz/CodeLoupe', subjects: ['CL-1 first thing', 'CL-1 second thing'] });
  assert.match(text, /^## CodeLoupe 0\.0\.1-rc1\n\nChanges since v0\.0\.0\.\n/);
  assert.match(text, /- \*\*\[CL-1\]\(https:\/\/github\.com\/Terrio-cz\/CodeLoupe\/commits\?q=CL-1\)\*\* first thing; second thing/);
});

test('tells how to verify the files when the repository is known', () => {
  const text = render({ version: '1.0.0', from: null, repo: 'Terrio-cz/CodeLoupe', subjects: ['CL-1 first'] });
  assert.match(text, /### Verify the files/);
  assert.match(text, /gh attestation verify <file> --repo Terrio-cz\/CodeLoupe/);
  assert.doesNotMatch(render({ version: '1.0.0', from: null, subjects: [] }), /Verify/);
});

test('says so when there is nothing to list', () => {
  assert.match(render({ version: '1.0.0', from: null, subjects: [] }), /First release\.\n\nNo changes recorded\./);
});
