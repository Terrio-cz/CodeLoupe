import assert from 'node:assert/strict';
import { test } from 'node:test';
import { changedFiles, classify, render } from './changed-paths.mjs';

test('documentation alone is not code and does not touch the update test', () => {
  assert.deepEqual(classify(['docs/ci.md', 'README.md', 'docs/screenshots/cli-dark.png', 'app/README.md', 'LICENSE']), { code: false, update: false });
});

test('tests and the plugin are code, but the update test cannot notice them', () => {
  assert.deepEqual(classify(['docs/ci.md', 'src/test/kotlin/A.kt', 'app/test/x.test.ts', 'plugin/hooks/h.sh']), { code: true, update: false });
});

test('sources, the build and the workflows change both', () => {
  for (const file of ['src/main/kotlin/codeloupe/A.kt', 'app/src/main/index.ts', 'build.gradle.kts', 'gradle/bundle.gradle.kts', '.github/workflows/ci.yml', 'tools/update-test.mjs']) {
    assert.deepEqual(classify(['docs/ci.md', file]), { code: true, update: true }, file);
  }
});

test('an empty or unreadable change is unknown, so everything runs', () => {
  assert.deepEqual(classify([]), { code: true, update: true });
  assert.deepEqual(classify(['', '  ']), { code: true, update: true });
});

test('a Windows path separator does not hide a documentation file', () => {
  assert.deepEqual(classify(['docs\\wiki\\Home.md']), { code: false, update: false });
});

test('renders one line per answer', () => {
  assert.equal(render({ code: false, update: true }), 'code=false\nupdate=true\n');
});

test('a missing, zero or unknown base gives no file list', () => {
  const git = args => { if (args[0] === 'cat-file') throw new Error('unknown'); return ''; };
  assert.equal(changedFiles('', 'HEAD', git), null);
  assert.equal(changedFiles('0000000000000000000000000000000000000000', 'HEAD', git), null);
  assert.equal(changedFiles('abc123', 'HEAD', git), null);
});

test('a known base gives the changed files', () => {
  const calls = [];
  const git = args => { calls.push(args); return args[0] === 'diff' ? 'docs/a.md\nsrc/main/B.kt\n' : ''; };
  assert.deepEqual(changedFiles('abc123', 'def456', git), ['docs/a.md', 'src/main/B.kt', '']);
  assert.deepEqual(calls.at(-1), ['diff', '--name-only', 'abc123', 'def456']);
});
