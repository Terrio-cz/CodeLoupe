import { AppImageUpdater, NsisUpdater, type AppUpdater } from 'electron-updater';
import { net } from 'electron';
import { signedFeedProvider } from './SignedFeedProvider';
import type { UpdateEngine } from './UpdateService';

/** Sent instead of the per-installation id electron-updater makes up for staged roll-outs, which CodeLoupe never uses. */
const NO_STAGING_ID = '00000000-0000-4000-8000-000000000000';
/** Chromium would add the system's language and its own version; the feed has no use for either. */
const HEADERS = { 'User-Agent': 'CodeLoupe', 'Accept-Language': 'en' };

/** Plain text of a URL, redirects followed, no cookies; throws unless the answer is 200. */
export async function fetchText(url: string): Promise<string> {
  const res = await net.fetch(url, { credentials: 'omit', headers: { ...HEADERS, accept: 'application/atom+xml, text/plain' } });
  if (!res.ok) throw new Error(`${new URL(url).host}: HTTP ${res.status}`);
  return res.text();
}

/**
 * electron-updater around a generic feed (the directory of a release's files): it reads `latest.yml`, downloads the
 * installer of the platform and compares its SHA-512 with the one in the feed, and installs it on quit or on request.
 * No telemetry: the only requests are for `latest.yml`, its blockmap and the installer; the User-Agent is a bare
 * "CodeLoupe", the language English and the staging id header a constant. With `keys`, a feed file is read only if the
 * signature next to it (`latest.yml.sig`) verifies against one of them, before anything of the installer is looked at;
 * without keys the SHA-512 of the feed is all that is checked, and the log says so.
 */
export function createEngine(kind: 'nsis' | 'appimage', log: (line: string) => void, keys: readonly string[] = []): UpdateEngine {
  const updater: AppUpdater = kind === 'nsis' ? new NsisUpdater() : new AppImageUpdater();
  (updater as unknown as { getOrCreateStagingUserId(): Promise<string> }).getOrCreateStagingUserId = async () => NO_STAGING_ID;
  updater.autoDownload = false;
  updater.autoInstallOnAppQuit = true;
  updater.allowDowngrade = false;
  updater.disableWebInstaller = true;
  updater.requestHeaders = HEADERS;
  const say = (level: string) => (message?: unknown) => log(`${level} ${String(message)}`);
  updater.logger = { info: say('info'), warn: say('warn'), error: say('error'), debug: say('debug') };
  // electron-updater emits 'error' besides rejecting; an emitter without a listener would throw it.
  updater.on('error', e => log(`error ${e.message}`));

  if (keys.length === 0) log('info update feeds are not signature-checked: this build embeds no update key');
  return {
    async download(feedUrl, onProgress) {
      updater.setFeedURL(keys.length > 0
        ? { provider: 'custom', updateProvider: signedFeedProvider(keys), url: feedUrl }
        : { provider: 'generic', url: feedUrl });
      const onDownload = (p: { percent: number }) => onProgress(Math.round(p.percent));
      updater.on('download-progress', onDownload);
      try {
        const found = await updater.checkForUpdates();
        if (!found?.isUpdateAvailable) return null;
        await updater.downloadUpdate(found.cancellationToken);
        return { version: found.updateInfo.version };
      } finally {
        updater.off('download-progress', onDownload);
      }
    },
    quitAndInstall(relaunch) {
      updater.quitAndInstall(true, relaunch);
    },
  };
}
