import { generateKeyPairSync, sign } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { signedFeedProvider } from '../src/main/update/SignedFeedProvider';

const feed = 'version: 2.0.0\nfiles:\n  - url: CodeLoupe-2.0.0-win-x64.exe\n    sha512: abc\n    size: 10\npath: CodeLoupe-2.0.0-win-x64.exe\nsha512: abc\nreleaseDate: 2026-10-09T00:00:00.000Z\n';

function keys() {
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  return {
    publicKey: publicKey.export({ format: 'der', type: 'spki' }).toString('base64'),
    signed: (text: string) => sign(null, Buffer.from(text, 'utf8'), privateKey).toString('base64'),
  };
}

/** The provider on a fake release directory: `files` maps a path to its text; anything else answers 404. */
function provider(trusted: readonly string[], files: Record<string, string>) {
  const asked: string[] = [];
  const executor = {
    request: async (options: { path?: string }) => {
      asked.push(options.path ?? '');
      const body = files[options.path ?? ''];
      if (body === undefined) throw Object.assign(new Error('HTTP 404'), { statusCode: 404 });
      return body;
    },
  };
  const Provider = signedFeedProvider(trusted) as unknown as new (o: unknown, u: unknown, r: unknown) => { getLatestVersion(): Promise<{ version: string }> };
  const instance = new Provider({ provider: 'custom', url: 'http://127.0.0.1:1/release/v2.0.0/' }, { channel: null, isAddNoCacheQuery: false }, { platform: 'win32', executor, isUseMultipleRangeRequest: true });
  return { instance, asked };
}

describe('signedFeedProvider', () => {
  const key = keys();

  it('reads the feed once its signature verifies, signature requested beside it', async () => {
    const { instance, asked } = provider([key.publicKey], { '/release/v2.0.0/latest.yml': feed, '/release/v2.0.0/latest.yml.sig': key.signed(feed) });
    expect((await instance.getLatestVersion()).version).toBe('2.0.0');
    expect(asked).toEqual(['/release/v2.0.0/latest.yml', '/release/v2.0.0/latest.yml.sig']);
  });

  it('refuses a feed without a signature file', async () => {
    const { instance } = provider([key.publicKey], { '/release/v2.0.0/latest.yml': feed });
    await expect(instance.getLatestVersion()).rejects.toThrow(/no signature.*latest\.yml/);
  });

  it('refuses a feed changed after signing and a signature by another key', async () => {
    const tampered = provider([key.publicKey], { '/release/v2.0.0/latest.yml': feed.replace('size: 10', 'size: 99'), '/release/v2.0.0/latest.yml.sig': key.signed(feed) });
    await expect(tampered.instance.getLatestVersion()).rejects.toThrow(/not signed by a key/);
    const other = keys();
    const wrong = provider([key.publicKey], { '/release/v2.0.0/latest.yml': feed, '/release/v2.0.0/latest.yml.sig': other.signed(feed) });
    await expect(wrong.instance.getLatestVersion()).rejects.toThrow(/not signed by a key/);
  });
});
