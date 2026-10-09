import { generateKeyPairSync, sign } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { verifyFeedSignature } from '../src/main/update/FeedSignature';

const feed = 'version: 1.2.3\nfiles:\n  - url: CodeLoupe-1.2.3-win-x64.exe\n    sha512: abc\n    size: 10\n';

function keyPair() {
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  return {
    publicKey: publicKey.export({ format: 'der', type: 'spki' }).toString('base64'),
    signed: (text: string) => sign(null, Buffer.from(text, 'utf8'), privateKey).toString('base64'),
  };
}

describe('verifyFeedSignature', () => {
  it('accepts a signature made the way the release workflow makes it, with openssl', () => {
    const dir = path.join(__dirname, 'fixtures', 'signed-feed');
    const read = (name: string) => fs.readFileSync(path.join(dir, name), 'utf8');
    expect(verifyFeedSignature(read('latest.yml'), read('latest.yml.sig'), [read('public-key.b64').trim()])).toEqual({ ok: true });
  });

  const current = keyPair();
  const next = keyPair();
  const old = keyPair();

  it('accepts the feed its key signed, and a second key of the list', () => {
    expect(verifyFeedSignature(feed, current.signed(feed), [current.publicKey])).toEqual({ ok: true });
    expect(verifyFeedSignature(feed, next.signed(feed), [current.publicKey, next.publicKey])).toEqual({ ok: true });
    expect(verifyFeedSignature(feed, `${current.signed(feed)}\n`, [current.publicKey])).toEqual({ ok: true });
  });

  it('refuses a missing signature', () => {
    expect(verifyFeedSignature(feed, null, [current.publicKey])).toMatchObject({ ok: false, reason: expect.stringContaining('no signature') });
    expect(verifyFeedSignature(feed, '  \n', [current.publicKey])).toMatchObject({ ok: false });
  });

  it('refuses a feed that was changed after it was signed, by one byte', () => {
    expect(verifyFeedSignature(feed.replace('size: 10', 'size: 11'), current.signed(feed), [current.publicKey])).toMatchObject({ ok: false });
  });

  it('refuses a signature by a key the build does not list, an old key included', () => {
    expect(verifyFeedSignature(feed, old.signed(feed), [current.publicKey])).toMatchObject({ ok: false, reason: expect.stringContaining('not signed by a key') });
  });

  it('refuses garbage in the place of a signature', () => {
    expect(verifyFeedSignature(feed, 'not base64 at all!', [current.publicKey])).toMatchObject({ ok: false, reason: expect.stringContaining('malformed') });
    expect(verifyFeedSignature(feed, Buffer.alloc(64).toString('base64'), [current.publicKey])).toMatchObject({ ok: false });
  });

  it('accepts nothing when no key can be read', () => {
    expect(verifyFeedSignature(feed, current.signed(feed), ['', 'AAAA'])).toMatchObject({ ok: false });
    expect(verifyFeedSignature(feed, current.signed(feed), [])).toMatchObject({ ok: false });
  });
});
