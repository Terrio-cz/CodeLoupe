import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { newEntries } from './temp-entries.mjs';

const script = fileURLToPath(new URL('./temp-entries.mjs', import.meta.url));

test('lists only the names that were not there before', () => {
  assert.deepEqual(newEntries(['a', 'b'], ['b', 'c', 'a', 'd']), ['c', 'd']);
  assert.deepEqual(newEntries(['a'], ['a']), []);
  const win = (name) => ['C:', 'Temp', name].join('\\');
  const names = ['kotlin-daemon.1.log', win('kotlin-compiler-client-1-is-running'), '/tmp/hsperfdata_x', '/tmp/codeloupe-repo-1', win('cl-test-7')];
  assert.deepEqual(newEntries([], names), ['/tmp/codeloupe-repo-1', win('cl-test-7')]);
});

test('check passes within the limit and fails above it, naming the leftovers', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'temp-entries-'));
  const env = { ...process.env, TMPDIR: dir, TMP: dir, TEMP: dir, TEMP_ENTRIES_DIRS: dir };
  const snapshot = path.join(os.tmpdir(), `temp-entries-snapshot-${process.pid}.json`);
  try {
    execFileSync(process.execPath, [script, 'snapshot', snapshot], { env });
    for (const n of ['one', 'two', 'three']) fs.mkdirSync(path.join(dir, n));
    const within = spawnSync(process.execPath, [script, 'check', snapshot, '--max', '3'], { env, encoding: 'utf8' });
    assert.equal(within.status, 0);
    const above = spawnSync(process.execPath, [script, 'check', snapshot, '--max', '2'], { env, encoding: 'utf8' });
    assert.equal(above.status, 1);
    assert.match(above.stdout, /3 new entries/);
    assert.match(above.stdout, /three/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
    fs.rmSync(snapshot, { force: true });
  }
});
