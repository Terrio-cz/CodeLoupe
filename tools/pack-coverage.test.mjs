import test from 'node:test';
import assert from 'node:assert/strict';
import { coverage, parseExpected } from './pack-coverage.mjs';

test('the expected list ignores comments and blank lines', () => {
  assert.deepEqual(parseExpected('AccountService\n\n# docs\nlocal-stack # the setup guide\n  User.kt  \n'), ['AccountService', 'local-stack', 'User.kt']);
});

test('coverage counts the expected names the pack mentions and lists the rest', () => {
  const pack = '## touch\n= accounts/service/AccountService.kt:151-167\n## cochange\ndocs/local-stack.md  ‹3 of 11›';
  const result = coverage(pack, ['AccountService', 'local-stack', 'PhoneNumber']);
  assert.deepEqual(result.named, ['AccountService', 'local-stack']);
  assert.deepEqual(result.missing, ['PhoneNumber']);
  assert.equal(result.share.toFixed(2), '0.67');
  assert.equal(coverage('', []).share, 1);
});
