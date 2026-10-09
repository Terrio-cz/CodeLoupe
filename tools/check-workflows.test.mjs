import assert from 'node:assert/strict';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { check, checkDir } from './check-workflows.mjs';

const SHA = '3d3c42e5aac5ba805825da76410c181273ba90b1';

const good = `name: X
on: push
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@${SHA} # v7
        with:
          persist-credentials: false
      - uses: ./.github/actions/local
      - env:
          NAME: \${{ github.event.pull_request.title }}
        run: |
          echo "$NAME"
`;

test('a workflow that follows the rules passes', () => {
  assert.deepEqual(check('x.yml', good), []);
});

test('the workflows of this repository follow the rules', () => {
  const dir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '.github', 'workflows');
  assert.deepEqual(checkDir(dir), []);
});

test('a missing permissions block, write-all and pull_request_target are reported', () => {
  assert.match(check('x.yml', good.replace('permissions:\n  contents: read\n', '')).join('\n'), /no top-level permissions/);
  assert.match(check('x.yml', good.replace('contents: read', 'write-all').replace('permissions:\n  write-all', 'permissions: write-all')).join('\n'), /write-all/);
  assert.match(check('x.yml', good.replace('on: push', 'on: pull_request_target')).join('\n'), /pull_request_target/);
});

test('an action named by a tag or branch is reported, one named by a commit is not', () => {
  assert.match(check('x.yml', good.replace(`@${SHA}`, '@v4')).join('\n'), /actions\/checkout@v4 is not pinned/);
  assert.match(check('x.yml', good.replace(`@${SHA}`, '')).join('\n'), /not pinned/);
  assert.match(check('x.yml', good.replace(`@${SHA}`, '@main')).join('\n'), /not pinned/);
});

test('a checkout that keeps the token is reported', () => {
  const bad = good.replace('        with:\n          persist-credentials: false\n', '');
  assert.match(check('x.yml', bad).join('\n'), /persist-credentials/);
  const second = good + `      - uses: actions/checkout@${SHA}\n        with:\n          fetch-depth: 0\n`;
  assert.match(check('x.yml', second).join('\n'), /persist-credentials/);
});

test('text an outsider controls inside a run block is reported, through env it is not', () => {
  const bad = good.replace('echo "$NAME"', 'echo "${{ github.event.pull_request.title }}"');
  assert.match(check('x.yml', bad).join('\n'), /untrusted expression/);
  assert.match(check('x.yml', good.replace('run: |\n          echo "$NAME"', 'run: echo ${{ github.head_ref }}')).join('\n'), /untrusted expression/);
  assert.deepEqual(check('x.yml', good.replace('echo "$NAME"', 'echo "${{ github.sha }}"')), []);
});
