import { describe, expect, it } from 'vitest';
import { feedOverride } from '../src/main/update/feedOverride';
import { findRelease, HttpStatusError, isReleasePage, newerReleases, newestRelease, releaseInfo, releaseTags } from '../src/main/update/ReleaseFeed';
import { detectMode } from '../src/main/update/updateMode';
import { compareVersions, parseVersion } from '../src/main/update/versions';

const v = (s: string) => parseVersion(s)!;
const atom = (...tags: string[]) => `<feed>${tags.map(t => `<entry><link rel="alternate" type="text/html" href="https://github.com/Terrio-cz/CodeLoupe/releases/tag/${t}"/><title>${t}</title></entry>`).join('')}</feed>`;

describe('versions', () => {
  it('orders releases and pre-releases by semantic versioning', () => {
    const order = ['0.9.0-rc.1', '0.9.0-rc.2', '0.9.0-rc.10', '0.9.0', '0.9.1', '0.10.0', '1.0.0-rc.1', '1.0.0'];
    for (let i = 0; i < order.length - 1; i++) expect(compareVersions(v(order[i]), v(order[i + 1]))).toBe(-1);
    expect(compareVersions(v('v1.2.3'), v('1.2.3'))).toBe(0);
  });

  it('refuses text that is not a release version', () => {
    expect(parseVersion('1.2')).toBeNull();
    expect(parseVersion('latest')).toBeNull();
    expect(parseVersion('1.2.3-')).toBeNull();
  });
});

describe('release feed', () => {
  it('reads the tags of the atom feed', () => {
    expect(releaseTags(atom('v0.9.0-rc.2', 'v0.9.0-rc.1', 'nightly', 'v0.9.0-rc.2'))).toEqual(['v0.9.0-rc.2', 'v0.9.0-rc.1']);
  });

  it('moves a final release to a newer final release and ignores pre-releases', () => {
    expect(newestRelease('1.0.0', ['v1.1.0-rc.1', 'v1.0.1', 'v1.0.0'])?.version).toBe('1.0.1');
    expect(newestRelease('1.0.0', ['v1.1.0-rc.1', 'v1.0.0'])).toBeNull();
  });

  it('moves an rc to the next rc and to the final release', () => {
    expect(newestRelease('0.9.0-rc.1', ['v0.9.0-rc.2', 'v0.9.0-rc.1'])?.version).toBe('0.9.0-rc.2');
    expect(newestRelease('0.9.0-rc.2', ['v0.9.0', 'v0.9.0-rc.2'])?.version).toBe('0.9.0');
    expect(newestRelease('0.9.0-rc.2', ['v0.9.0-rc.2'])).toBeNull();
  });

  it('names the release page and the directory of its files on GitHub, nowhere else', () => {
    const info = releaseInfo('v0.9.0-rc.2');
    expect(info.pageUrl).toBe('https://github.com/Terrio-cz/CodeLoupe/releases/tag/v0.9.0-rc.2');
    expect(info.feedUrl).toBe('https://github.com/Terrio-cz/CodeLoupe/releases/download/v0.9.0-rc.2/');
    expect(isReleasePage(info.pageUrl)).toBe(true);
    expect(isReleasePage('https://evil.example/Terrio-cz/CodeLoupe/releases/tag/v1.0.0')).toBe(false);
    expect(isReleasePage('https://github.com/other/repo/releases/tag/v1.0.0')).toBe(false);
  });

  it('asks only for the releases atom feed of the repository', async () => {
    const asked: string[] = [];
    const found = await findRelease('0.9.0-rc.1', async url => { asked.push(url); return atom('v0.9.0-rc.2'); });
    expect(asked).toEqual(['https://github.com/Terrio-cz/CodeLoupe/releases.atom', 'https://github.com/Terrio-cz/CodeLoupe/releases/download/v0.9.0-rc.2/latest.yml']);
    expect(found?.tag).toBe('v0.9.0-rc.2');
  });
});

describe('findRelease with unpublished entries (CL-254)', () => {
  const feed = (missing: string[], failure: Error = new HttpStatusError(404, 'github.com')) => async (url: string) => {
    if (url.endsWith('.atom')) return atom('v0.1.8', 'v0.1.7', 'v0.1.6', 'v0.1.4');
    if (missing.some(t => url.includes(`/${t}/`))) throw failure;
    return 'version: x';
  };

  it('orders the newer releases newest first', () => {
    expect(newerReleases('0.1.4', ['v0.1.6', 'v0.1.8', 'v0.1.7', 'v0.1.4']).map(r => r.tag)).toEqual(['v0.1.8', 'v0.1.7', 'v0.1.6']);
  });

  it('skips a draft and a bare tag and returns the newest published release', async () => {
    expect((await findRelease('0.1.4', feed(['v0.1.8', 'v0.1.7'])))?.tag).toBe('v0.1.6');
  });

  it('returns the newest release when it is published', async () => {
    expect((await findRelease('0.1.4', feed([])))?.tag).toBe('v0.1.8');
  });

  it('finds nothing when every newer entry is unpublished', async () => {
    expect(await findRelease('0.1.6', feed(['v0.1.8', 'v0.1.7']))).toBeNull();
  });

  it('does not hide a server error as an unpublished release', async () => {
    await expect(findRelease('0.1.4', feed(['v0.1.8'], new HttpStatusError(500, 'github.com')))).rejects.toThrow('HTTP 500');
    await expect(findRelease('0.1.4', feed(['v0.1.8'], new Error('net::ERR_INTERNET_DISCONNECTED')))).rejects.toThrow('ERR_INTERNET');
  });

  it('does not probe a local test feed', async () => {
    const asked: string[] = [];
    const found = await findRelease('0.1.4', async url => { asked.push(url); return atom('v99.0.0'); }, 'http://127.0.0.1:1/releases.atom');
    expect(found?.tag).toBe('v99.0.0');
    expect(asked).toEqual(['http://127.0.0.1:1/releases.atom']);
  });
});

describe('local feed override', () => {
  it('accepts a loopback http address only', () => {
    expect(feedOverride('http://127.0.0.1:47551')).toBe('http://127.0.0.1:47551/');
    expect(feedOverride('http://localhost:47551/feed/')).toBe('http://localhost:47551/feed/');
    expect(feedOverride('https://127.0.0.1:47551/')).toBeNull();
    expect(feedOverride('http://example.com/')).toBeNull();
    expect(feedOverride('http://127.0.0.1.example.com/')).toBeNull();
    expect(feedOverride('http://user:pw@127.0.0.1/')).toBeNull();
    expect(feedOverride('')).toBeNull();
    expect(feedOverride(undefined)).toBeNull();
  });
});

describe('update mode', () => {
  const base = { packaged: true, execPath: 'C:\\Apps\\CodeLoupe\\CodeLoupe.exe', exists: () => false };

  it('updates itself on an installed Windows app and on an AppImage', () => {
    expect(detectMode({ ...base, platform: 'win32', exists: f => f.endsWith('Uninstall CodeLoupe.exe') })).toEqual({ kind: 'install', engine: 'nsis' });
    expect(detectMode({ ...base, platform: 'linux', appImage: '/home/u/CodeLoupe.AppImage' })).toEqual({ kind: 'install', engine: 'appimage' });
  });

  it('only notifies on macOS, a .deb and a copy without an uninstaller', () => {
    expect(detectMode({ ...base, platform: 'darwin' }).kind).toBe('notify');
    expect(detectMode({ ...base, platform: 'linux' }).kind).toBe('notify');
    expect(detectMode({ ...base, platform: 'win32' }).kind).toBe('notify');
  });

  it('does nothing in a development run', () => {
    expect(detectMode({ ...base, platform: 'win32', packaged: false }).kind).toBe('unavailable');
  });
});
