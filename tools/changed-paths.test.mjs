import assert from 'node:assert/strict';
import { test } from 'node:test';
import { changedFiles, classify, render } from './changed-paths.mjs';

test('documentation alone is not code and does not touch the update test', () => {
  assert.deepEqual(classify(['docs/ci.md', 'README.md', 'docs/screenshots/cli-dark.png', 'app/README.md', 'LICENSE']), { code: false, update: false });
});

test('Kotlin, tests and the plugin are code, but the update test is skipped for them', () => {
  assert.deepEqual(classify(['docs/ci.md', 'src/test/kotlin/A.kt', 'app/test/x.test.ts', 'plugin/hooks/h.sh']), { code: true, update: false });
  assert.deepEqual(classify(['src/main/kotlin/codeloupe/A.kt', 'src/main/resources/x.json', 'tools/release-notes.mjs', 'tools/feed-check.test.mjs', 'gradle/test-shards.gradle.kts']), { code: true, update: false });
});

test('the app, the build, the workflows and the updater scripts change both', () => {
  const files = ['app/src/main/index.ts', 'app/package.json', 'app/electron-builder.yml', 'build.gradle.kts', 'gradle/bundle.gradle.kts', '.github/workflows/ci.yml',
    ...['update-test', 'feed-check', 'break-bundle', 'no-java-env', 'daemon-token'].map(name => `tools/${name}.mjs`)];
  for (const file of files) {
    assert.deepEqual(classify(['docs/ci.md', 'src/main/kotlin/codeloupe/A.kt', file]), { code: true, update: true }, file);
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
