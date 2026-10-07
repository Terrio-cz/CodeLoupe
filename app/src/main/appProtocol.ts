import { net, protocol } from 'electron';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const APP_ORIGIN = 'app://codeloupe';

export const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data:",
  "font-src 'self'",
  "connect-src 'none'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-ancestors 'none'",
].join('; ');

/** Must run before `app.ready`: a standard, secure scheme gives the bundle a fixed origin for CSP and IPC checks. */
export function registerAppScheme(): void {
  protocol.registerSchemesAsPrivileged([{ scheme: 'app', privileges: { standard: true, secure: true } }]);
}

/** Serves only files inside the renderer bundle directory, with the CSP header on every response. */
export function handleAppScheme(rendererDir: string): void {
  const root = path.resolve(rendererDir);
  protocol.handle('app', async request => {
    const url = new URL(request.url);
    if (url.host !== 'codeloupe') return new Response('not found', { status: 404 });
    const rel = decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname);
    const file = path.resolve(root, `.${rel}`);
    if (file !== root && !file.startsWith(root + path.sep)) return new Response('forbidden', { status: 403 });
    const res = await net.fetch(pathToFileURL(file).toString()).catch(() => null);
    if (!res || !res.ok) return new Response('not found', { status: 404 });
    const headers = new Headers(res.headers);
    headers.set('Content-Security-Policy', CSP);
    headers.set('X-Content-Type-Options', 'nosniff');
    return new Response(res.body, { status: 200, headers });
  });
}
