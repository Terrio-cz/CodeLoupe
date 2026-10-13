import { compareVersions, parseVersion } from './versions';

/** The only host the updater talks to, apart from the redirect GitHub answers a release download with. */
export const RELEASE_REPO = 'Terrio-cz/CodeLoupe';
const RELEASES = `https://github.com/${RELEASE_REPO}/releases`;

export interface ReleaseInfo {
  tag: string;
  version: string;
  /** Page of the release in the browser. */
  pageUrl: string;
  /** Directory of the release's files, where electron-updater finds `latest.yml`. */
  feedUrl: string;
}

export const atomUrl = `${RELEASES}.atom`;

/** The release tags of GitHub's releases atom feed (GitHub also lists the tag of a draft or a bare tag: findRelease checks the files), newest first. */
export function releaseTags(atom: string): string[] {
  const tags: string[] = [];
  for (const m of atom.matchAll(/\/releases\/tag\/([^"'<>\s]+)/g)) {
    let tag: string;
    try { tag = decodeURIComponent(m[1]); } catch { continue; }
    if (parseVersion(tag) && !tags.includes(tag)) tags.push(tag);
  }
  return tags;
}

/**
 * The releases newer than `current`, newest first. A final release only offers final releases; an rc offers the next
 * rc and the final release (electron-updater's own GitHub provider would not move an rc to the final release).
 */
export function newerReleases(current: string, tags: string[]): ReleaseInfo[] {
  const now = parseVersion(current);
  if (!now) return [];
  const found: { tag: string; v: NonNullable<ReturnType<typeof parseVersion>> }[] = [];
  for (const tag of tags) {
    const v = parseVersion(tag);
    if (!v || (now.pre.length === 0 && v.pre.length > 0)) continue;
    if (compareVersions(v, now) > 0) found.push({ tag, v });
  }
  return found.sort((x, y) => compareVersions(y.v, x.v)).map(f => releaseInfo(f.tag));
}

export function newestRelease(current: string, tags: string[]): ReleaseInfo | null {
  return newerReleases(current, tags)[0] ?? null;
}

export function releaseInfo(tag: string): ReleaseInfo {
  return {
    tag,
    version: tag.replace(/^v/, ''),
    pageUrl: `${RELEASES}/tag/${encodeURIComponent(tag)}`,
    feedUrl: `${RELEASES}/download/${encodeURIComponent(tag)}/`,
  };
}

/** True for the release page of this repository: the only page the app opens for an update. */
export function isReleasePage(url: string): boolean {
  return url.startsWith(`${RELEASES}/tag/`) && !/[\s"'<>]/.test(url);
}

/** A non-200 answer; `status` tells a missing file (404) from a broken connection or a server error. */
export class HttpStatusError extends Error {
  constructor(readonly status: number, readonly host: string) {
    super(`${host}: HTTP ${status}`);
  }
}

/**
 * Reads the release feed (`atom`: the local test feed's, otherwise GitHub's) and returns the newest newer release that is
 * published, or null. `fetchText` throws on a non-200 answer.
 *
 * GitHub's atom feed also lists the tag of a release that is still a draft (the release workflow waits for an approval and
 * a human publishes it) and a tag that never gets a release. Their `latest.yml` is a 404, which would fail every check from
 * the moment a tag is pushed, so such an entry is skipped and the next one is looked at. Any other failure is a failed check.
 * A local test feed (`atom` not GitHub's) is not probed: its entries point at GitHub, not at the files it serves.
 */
export async function findRelease(current: string, fetchText: (url: string) => Promise<string>, atom = atomUrl): Promise<ReleaseInfo | null> {
  const candidates = newerReleases(current, releaseTags(await fetchText(atom)));
  if (atom !== atomUrl) return candidates[0] ?? null;
  for (const release of candidates) {
    try {
      await fetchText(`${release.feedUrl}latest.yml`);
      return release;
    } catch (e) {
      if (!(e instanceof HttpStatusError && e.status === 404)) throw e;
    }
  }
  return null;
}

