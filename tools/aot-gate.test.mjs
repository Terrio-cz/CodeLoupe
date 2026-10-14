import assert from 'node:assert/strict';
import { test } from 'node:test';
import { aotGateFailure } from './aot-gate.mjs';

test('runner noise between the AOT cache and the dynamic archive passes', () => {
  // ci / bundle (macos-15-intel) of the v0.1.7 release run 38462933951
  assert.equal(aotGateFailure({ none: 911, dynamic: 456, aot: 510 }), null);
  assert.equal(aotGateFailure({ none: 136 * 2, dynamic: 136, aot: 112 }), null);
});

test('an AOT cache well behind the dynamic archive fails', () => {
  assert.match(aotGateFailure({ none: 911, dynamic: 456, aot: 530 }), /more than 15 % slower than the dynamic archive/);
});

test('an unused cache, as slow as no archive, fails', () => {
  assert.match(aotGateFailure({ none: 911, dynamic: 456, aot: 905 }), /slower than the dynamic archive/);
  assert.match(aotGateFailure({ none: 500, dynamic: 480, aot: 520 }), /not faster than no archive/);
});

test('a missing timing fails', () => {
  assert.match(aotGateFailure({ none: 911, dynamic: 456 }), /incomplete/);
});
