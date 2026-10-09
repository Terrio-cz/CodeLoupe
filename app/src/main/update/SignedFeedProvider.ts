import type { CancellationToken, CustomPublishOptions } from 'builder-util-runtime';
import type { OutgoingHttpHeaders } from 'node:http';
import { GenericProvider } from 'electron-updater/out/providers/GenericProvider';
import { verifyFeedSignature } from './FeedSignature';

/**
 * electron-updater's generic provider that also fetches `<feed>.sig` next to every feed file it reads and refuses the feed
 * unless one of `keys` signed exactly the text it is about to parse. The check sits in the request that returns the text, so
 * there is no second download that could differ from the one verified, and it runs before the installer is looked at.
 */
export function signedFeedProvider(keys: readonly string[]): NonNullable<CustomPublishOptions['updateProvider']> {
  return class SignedFeedProvider extends GenericProvider {
    constructor(options: CustomPublishOptions, updater: ConstructorParameters<typeof GenericProvider>[1], runtime: ConstructorParameters<typeof GenericProvider>[2]) {
      super({ provider: 'generic', url: String(options.url) }, updater, runtime);
    }

    protected override async httpRequest(url: URL, headers?: OutgoingHttpHeaders | null, cancellationToken?: CancellationToken): Promise<string | null> {
      const body = await super.httpRequest(url, headers, cancellationToken);
      if (!url.pathname.endsWith('.yml')) return body;
      const signatureUrl = new URL(url);
      signatureUrl.pathname += '.sig';
      const signature = await super.httpRequest(signatureUrl, headers, cancellationToken).catch(() => null);
      const verdict = verifyFeedSignature(body ?? '', signature, keys);
      if (!verdict.ok) throw new Error(`Refusing the update: ${verdict.reason} (${url.pathname.split('/').pop()})`);
      return body;
    }
  };
}
