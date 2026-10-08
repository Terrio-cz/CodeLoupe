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

/** The release tags of GitHub's releases atom feed (published releases only: a draft is not in it), newest first. */
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
 * The newest release that is newer than `current`. A final release only offers final releases; an rc offers the next
 * rc and the final release (electron-updater's own GitHub provider would not move an rc to the final release).
 */
export function newestRelease(current: string, tags: string[]): ReleaseInfo | null {
  const now = parseVersion(current);
  if (!now) return null;
  let best: { tag: string; v: NonNullable<ReturnType<typeof parseVersion>> } | null = null;
  for (const tag of tags) {
    const v = parseVersion(tag);
    if (!v || (now.pre.length === 0 && v.pre.length > 0)) continue;
    if (compareVersions(v, now) <= 0) continue;
    if (!best || compareVersions(v, best.v) > 0) best = { tag, v };
  }
  return best && releaseInfo(best.tag);
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

/** Reads the release feed and returns a newer release, or null. `fetchText` throws on a non-200 answer. */
export async function findRelease(current: string, fetchText: (url: string) => Promise<string>): Promise<ReleaseInfo | null> {
  return newestRelease(current, releaseTags(await fetchText(atomUrl)));
}
