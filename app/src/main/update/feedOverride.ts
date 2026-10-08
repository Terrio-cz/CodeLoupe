/**
 * `CODELOUPE_UPDATE_FEED`: a directory with `latest.yml` and the installer on this machine, served by a throwaway HTTP
 * server (tools/update-test.mjs). Only a loopback address is accepted, so the variable can point the updater nowhere
 * but at this computer: the updater still talks to the release feed host and no one else.
 */
export function feedOverride(value: string | undefined): string | null {
  if (!value) return null;
  let url: URL;
  try { url = new URL(value); } catch { return null; }
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
  if (url.protocol !== 'http:' || !loopback || url.username || url.password || url.search || url.hash) return null;
  return url.href.endsWith('/') ? url.href : `${url.href}/`;
}
