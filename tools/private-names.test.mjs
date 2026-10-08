// The repository is public: names of the owner's private systems must not appear in tracked files (CL-151). The names
// themselves cannot be listed here, so this file holds the first 16 hex digits of the SHA-256 of each lower-case word;
// every word of every tracked text file, and every pair of neighbouring words joined, is hashed and compared.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FORBIDDEN = new Set([
  '48b2769a272c373b', 'a4f3b8a3796c0653', '43e3a423e7d54019', '69cd1377f1da5475', 'a89c247097677333',
  '40ca8bf51319b386', 'e37fb36c0790a9d2', '2bce5f2197bff9b1', '19a875ec886e906e', 'f68733088698f2fc',
]);
const SKIP = /(^|\/)(package-lock\.json|gradlew\.bat|gradle-wrapper\.jar)$|\.(png|jpg|jpeg|gif|ico|icns|jar|zip|exe|woff2?|svg|db)$/i;
const hash = word => createHash('sha256').update(word).digest('hex').slice(0, 16);

test('no tracked text file carries the name of a private system', () => {
  const listed = spawnSync('git', ['ls-files', '-z'], { cwd: root, encoding: 'utf8', maxBuffer: 1 << 26 });
  assert.equal(listed.status, 0, listed.stderr);
  const found = [];
  for (const file of listed.stdout.split('\0').filter(Boolean)) {
    if (SKIP.test(file) || !fs.existsSync(path.join(root, file))) continue;
    const text = fs.readFileSync(path.join(root, file), 'utf8');
    text.split('\n').forEach((line, i) => {
      const words = line.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
      words.forEach((word, k) => {
        if (FORBIDDEN.has(hash(word)) || (k > 0 && FORBIDDEN.has(hash(words[k - 1] + word)))) found.push(`${file}:${i + 1}`);
      });
    });
  }
  assert.deepEqual(found, [], `private system names in: ${found.join(', ')}`);
});
