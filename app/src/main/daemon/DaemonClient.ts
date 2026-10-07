import http from 'node:http';
import type { DaemonStatus } from '../../shared/contract';

const HOST = '127.0.0.1';
const MAX_BODY = 8 * 1024 * 1024;

export class HttpError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

/**
 * GET-only client of the local daemon. Always 127.0.0.1, never an Origin header, a fresh connection per
 * call (the daemon closes every socket so a restart cannot break a pooled one).
 */
export class DaemonClient {
  constructor(private readonly port: () => number) {}

  status(): Promise<DaemonStatus> {
    return this.get<DaemonStatus>('/status', 3000);
  }

  get<T>(path: string, timeoutMs = 5000): Promise<T> {
    if (!path.startsWith('/')) return Promise.reject(new Error('path must be absolute'));
    const port = this.port();
    return new Promise<T>((resolve, reject) => {
      // Settle exactly once: a daemon that restarts mid-response fails the response, not the request.
      let done = false;
      const ok = (v: T) => { if (!done) { done = true; resolve(v); } };
      const fail = (e: Error) => { if (!done) { done = true; reject(e); } };
      const req = http.request(
        { host: HOST, port, path, method: 'GET', agent: false, timeout: timeoutMs,
          headers: { host: `${HOST}:${port}`, 'x-codeloupe': '1', accept: 'application/json' } },
        res => {
          const chunks: Buffer[] = [];
          let size = 0;
          res.on('error', fail);
          res.on('aborted', () => fail(new Error('daemon closed the connection')));
          res.on('close', () => { if (!res.complete) fail(new Error('daemon closed the connection')); });
          res.on('data', (c: Buffer) => {
            size += c.length;
            if (size > MAX_BODY) { req.destroy(new Error('response too large')); return; }
            chunks.push(c);
          });
          res.on('end', () => {
            const text = Buffer.concat(chunks).toString('utf8');
            let body: unknown;
            try { body = text ? JSON.parse(text) : null; } catch { return fail(new HttpError(res.statusCode ?? 0, 'bad_response', 'daemon returned invalid JSON')); }
            const status = res.statusCode ?? 0;
            if (status >= 200 && status < 300) return ok(body as T);
            const err = (body as { error?: unknown })?.error;
            const code = typeof err === 'object' && err && 'code' in err ? String((err as { code: unknown }).code) : status === 404 ? 'not_found' : 'http_error';
            const message = typeof err === 'object' && err && 'message' in err ? String((err as { message: unknown }).message) : typeof err === 'string' ? err : `HTTP ${status}`;
            fail(new HttpError(status, code, message));
          });
        });
      req.on('timeout', () => req.destroy(new Error('daemon did not answer in time')));
      req.on('error', fail);
      req.end();
    });
  }
}
