import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { adHocSign, isMachO, machOFiles } from '../app/scripts/ad-hoc-sign.mjs';

const thin64 = Buffer.from([0xcf, 0xfa, 0xed, 0xfe, 0x0c, 0x00, 0x00, 0x01]);
const fat = Buffer.from([0xca, 0xfe, 0xba, 0xbe, 0x00, 0x00, 0x00, 0x02]);
const javaClass = Buffer.from([0xca, 0xfe, 0xba, 0xbe, 0x00, 0x00, 0x00, 0x45]);

test('recognises Mach-O files and not Java class files that share the fat magic', () => {
  assert.equal(isMachO(thin64), true);
  assert.equal(isMachO(fat), true);
  assert.equal(isMachO(javaClass), false);
  assert.equal(isMachO(Buffer.from('#!/bin/sh\n')), false);
  assert.equal(isMachO(Buffer.alloc(3)), false);
});

function fixtureApp() {
  const app = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'adhoc-')), 'CodeLoupe.app');
  const res = path.join(app, 'Contents', 'Resources', 'codeloupe');
  fs.mkdirSync(path.join(res, 'runtime', 'bin'), { recursive: true });
  fs.mkdirSync(path.join(res, 'runtime', 'lib'), { recursive: true });
  fs.mkdirSync(path.join(res, 'bin'), { recursive: true });
  fs.writeFileSync(path.join(res, 'runtime', 'bin', 'java'), thin64);
  fs.writeFileSync(path.join(res, 'runtime', 'lib', 'libjvm.dylib'), fat);
  fs.writeFileSync(path.join(res, 'runtime', 'lib', 'Hello.class'), javaClass);
  fs.writeFileSync(path.join(res, 'bin', 'codeloupe'), '#!/bin/sh\n');
  return app;
}

test('finds the loose Mach-O files under Resources in a stable order', () => {
  const app = fixtureApp();
  const found = machOFiles(path.join(app, 'Contents', 'Resources')).map(f => path.relative(app, f).split(path.sep).join('/'));
  assert.deepEqual(found, [
    'Contents/Resources/codeloupe/runtime/bin/java',
    'Contents/Resources/codeloupe/runtime/lib/libjvm.dylib',
  ]);
});

test('signs the loose files first and the app bundle last, all ad hoc', () => {
  const app = fixtureApp();
  const calls = [];
  const count = adHocSign(app, args => calls.push(args));
  assert.equal(count, 2);
  assert.equal(calls.length, 3);
  for (const args of calls) assert.deepEqual(args.slice(0, 1).concat(args.slice(-2, -1)), ['--force', '-']);
  assert.ok(calls[0].at(-1).endsWith(path.join('runtime', 'bin', 'java')));
  assert.deepEqual(calls[2], ['--force', '--deep', '--sign', '-', app]);
});
