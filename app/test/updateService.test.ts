import { describe, expect, it, vi } from 'vitest';
import { HttpStatusError } from '../src/main/update/ReleaseFeed';
import { UpdateService, type UpdateDeps, type UpdateEngine } from '../src/main/update/UpdateService';

const atom = (...tags: string[]) => tags.map(t => `href="https://github.com/Terrio-cz/CodeLoupe/releases/tag/${t}"`).join('\n');

function setup(over: Partial<UpdateDeps> = {}, offered: string | null = '0.9.0-rc.2') {
  const asked: string[] = [];
  const engine: UpdateEngine = {
    download: vi.fn(async (_url, onProgress) => { onProgress(50); return offered ? { version: offered } : null; }),
    quitAndInstall: vi.fn(),
  };
  const deps: UpdateDeps = {
    current: '0.9.0-rc.1',
    mode: { kind: 'install', engine: 'nsis' },
    enabled: () => true,
    fetchText: async url => { asked.push(url); return atom('v0.9.0-rc.2', 'v0.9.0-rc.1'); },
    engine,
    beforeInstall: vi.fn(async () => undefined),
    now: () => new Date('2026-10-08T12:00:00Z'),
    ...over,
  };
  return { service: new UpdateService(deps), deps, engine, asked };
}

describe('UpdateService', () => {
  it('downloads a newer release from its GitHub directory, keeps the old daemon bundle and waits for the restart', async () => {
    const { service, engine, deps, asked } = setup();
    const seen: string[] = [];
    service.on('state', s => seen.push(s.phase));
    const state = await service.check();
    expect(asked).toEqual(['https://github.com/Terrio-cz/CodeLoupe/releases.atom', 'https://github.com/Terrio-cz/CodeLoupe/releases/download/v0.9.0-rc.2/latest.yml']);
    expect(engine.download).toHaveBeenCalledWith('https://github.com/Terrio-cz/CodeLoupe/releases/download/v0.9.0-rc.2/', expect.any(Function));
    expect(deps.beforeInstall).toHaveBeenCalledWith('0.9.0-rc.2');
    expect(seen).toEqual(['checking', 'downloading', 'downloading', 'ready']);
    expect(state).toMatchObject({ phase: 'ready', latest: '0.9.0-rc.2', percent: 100, mode: 'install' });
    expect(engine.quitAndInstall).not.toHaveBeenCalled();
    service.install();
    expect(engine.quitAndInstall).toHaveBeenCalledWith(true);
  });

  it('only reports the release where the installation cannot update itself', async () => {
    const { service, engine } = setup({ mode: { kind: 'notify', reason: 'macOS' }, engine: null });
    const state = await service.check();
    expect(state).toMatchObject({ phase: 'available', latest: '0.9.0-rc.2', mode: 'notify', releaseUrl: 'https://github.com/Terrio-cz/CodeLoupe/releases/tag/v0.9.0-rc.2' });
    expect(engine.download).not.toHaveBeenCalled();
    service.install();
    expect(engine.quitAndInstall).not.toHaveBeenCalled();
  });

  it('reads the release list of a local feed where the installation only notifies', async () => {
    const asked: string[] = [];
    const { service, engine } = setup({
      mode: { kind: 'notify', reason: 'macOS' }, engine: null, feedOverride: 'http://127.0.0.1:47551/',
      fetchText: async url => { asked.push(url); return atom('v99.0.0'); },
    });
    expect(await service.check()).toMatchObject({ phase: 'available', latest: '99.0.0' });
    expect(asked).toEqual(['http://127.0.0.1:47551/releases.atom']);
    expect(engine.download).not.toHaveBeenCalled();
  });

  it('says it is up to date when the feed has nothing newer', async () => {
    const { service, engine } = setup({ fetchText: async () => atom('v0.9.0-rc.1') });
    expect(await service.check()).toMatchObject({ phase: 'idle', latest: null, checkedAt: '2026-10-08T12:00:00.000Z' });
    expect(engine.download).not.toHaveBeenCalled();
  });

  it('a failed download is an error, not an install; a failed backup of the daemon does not stop the update', async () => {
    const failing = setup();
    vi.mocked(failing.engine.download).mockRejectedValueOnce(new Error('sha512 checksum mismatch\nstack'));
    expect(await failing.service.check()).toMatchObject({ phase: 'error', message: 'sha512 checksum mismatch' });
    failing.service.install();
    expect(failing.engine.quitAndInstall).not.toHaveBeenCalled();

    const noBackup = setup({ beforeInstall: async () => { throw new Error('disk full'); } });
    const s = await noBackup.service.check();
    expect(s.phase).toBe('ready');
    expect(s.message).toContain('disk full');
  });

  it('uses a local feed instead of GitHub when one is given, and can install by itself', async () => {
    const { service, engine, asked } = setup({ feedOverride: 'http://127.0.0.1:47551/', installWhenReady: true });
    await service.check();
    expect(asked).toEqual([]);
    expect(engine.download).toHaveBeenCalledWith('http://127.0.0.1:47551/', expect.any(Function));
    expect(engine.quitAndInstall).toHaveBeenCalledOnce();
  });

  it('is off while the setting is off: no timer check, but a check the user asks for works', async () => {
    vi.useFakeTimers();
    try {
      let on = false;
      const { service, asked } = setup({ enabled: () => on });
      expect(service.current.phase).toBe('off');
      service.start(1000, 1000);
      await vi.advanceTimersByTimeAsync(5000);
      expect(asked).toEqual([]);
      on = true;
      service.settingsChanged();
      expect(service.current.phase).toBe('idle');
      await vi.advanceTimersByTimeAsync(1000);
      expect(asked.length).toBeGreaterThan(0);
      service.dispose();
    } finally {
      vi.useRealTimers();
    }
    const manual = setup({ enabled: () => false });
    expect((await manual.service.check()).phase).toBe('ready');
  });

  it('never checks a development run', async () => {
    const { service, asked } = setup({ mode: { kind: 'unavailable', reason: 'dev' }, engine: null });
    expect(service.current).toMatchObject({ phase: 'off', mode: 'unavailable' });
    await service.check();
    expect(asked).toEqual([]);
  });

  it('does not run two checks at once', async () => {
    const { service, asked } = setup();
    await Promise.all([service.check(), service.check()]);
    expect(asked.length).toBe(2);
  });

  // CL-254: GitHub's atom feed lists the tag of a release that is still a draft; its latest.yml is a 404.
  describe('with a draft or a bare tag newer than the newest published release', () => {
    const draftFeed = (published: string[]) => async (url: string) => {
      if (url.endsWith('.atom')) return atom('v0.1.8', 'v0.1.7', 'v0.1.6', 'v0.1.4');
      const tag = /\/download\/(v[^/]+)\//.exec(url)?.[1] ?? '';
      if (!published.includes(tag)) throw new HttpStatusError(404, 'github.com');
      return 'version: x';
    };

    it('downloads the newest published release and reports no error', async () => {
      const { service, engine } = setup({ current: '0.1.4', fetchText: draftFeed(['v0.1.6', 'v0.1.4']) }, '0.1.6');
      expect(await service.check()).toMatchObject({ phase: 'ready', latest: '0.1.6', message: null });
      expect(engine.download).toHaveBeenCalledWith('https://github.com/Terrio-cz/CodeLoupe/releases/download/v0.1.6/', expect.any(Function));
    });

    it('is up to date when only the drafts are newer', async () => {
      const { service, engine } = setup({ current: '0.1.6', fetchText: draftFeed(['v0.1.6']) }, null);
      expect(await service.check()).toMatchObject({ phase: 'idle', latest: null, message: null });
      expect(engine.download).not.toHaveBeenCalled();
    });

    it('names the newest published release where the installation only notifies', async () => {
      const { service } = setup({ current: '0.1.4', mode: { kind: 'notify', reason: 'macOS' }, engine: null, fetchText: draftFeed(['v0.1.6']) });
      expect(await service.check()).toMatchObject({ phase: 'available', latest: '0.1.6', releaseUrl: 'https://github.com/Terrio-cz/CodeLoupe/releases/tag/v0.1.6' });
    });

    it('still fails the check on a broken connection or a server error, not only a 404', async () => {
      const down = async (url: string) => { if (url.endsWith('.atom')) return atom('v0.1.8'); throw new HttpStatusError(503, 'github.com'); };
      const { service } = setup({ current: '0.1.4', fetchText: down });
      expect(await service.check()).toMatchObject({ phase: 'error', message: 'github.com: HTTP 503' });
    });
  });
});
