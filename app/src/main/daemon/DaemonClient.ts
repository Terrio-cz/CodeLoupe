import http from 'node:http';
import type { DaemonStatus } from '../../shared/contract';
import { NONCE_HEADER, PROOF_HEADER, TOKEN_HEADER, newNonce, proofOf, readToken } from './DaemonToken';

const HOST = '127.0.0.1';
const MAX_BODY = 8 * 1024 * 1024;
/** How long an answer to /status counts as proof that the daemon on the port holds the token. */
const TRUST_MS = 3000;

export class HttpError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

/**
 * Client of the local daemon: GET for everything the screens read, POST only for the few actions main confirms
 * with the user first (`actions/`). Always 127.0.0.1, never an Origin header, a fresh connection per call
 * (the daemon closes every socket so a restart cannot break a pooled one).
 *
 * Everything but /status carries the token of `<home>/daemon.token` once the daemon has answered a nonce with the proof only a holder
 * of it can give: the token is never sent to a process that did not. Without a home or a token file (a daemon from before the token)
 * only `x-codeloupe` goes, which that daemon accepts.
 */
export class DaemonClient {
  private trusted: { token: string; port: number; at: number } | null = null;

  constructor(private readonly port: () => number, private readonly homeDir: () => string | null = () => null) {}

  status(): Promise<DaemonStatus> {
    return this.get<DaemonStatus>('/status', 3000);
  }

  /** The header that presents the token, or nothing when the daemon on the port has not proved it holds one. For streams the client does not open itself. */
  async authHeaders(): Promise<Record<string, string>> {
    const port = this.port();
    const dir = this.homeDir();
    const token = dir ? readToken(dir) : null;
    if (!token) return {};
    const known = () => this.trusted !== null && this.trusted.port === port && this.trusted.token === token;
    if (!(known() && Date.now() - this.trusted!.at < TRUST_MS)) await this.send<DaemonStatus>('GET', '/status', undefined, 3000).catch(() => undefined);
    return known() ? { [TOKEN_HEADER]: token } : {};
  }

  get<T>(path: string, timeoutMs = 5000): Promise<T> {
    return this.send<T>('GET', path, undefined, timeoutMs);
  }

  /** POST of a JSON body; the reply is parsed like a GET's. */
  post<T>(path: string, body: unknown, timeoutMs = 30_000): Promise<T> {
    return this.send<T>('POST', path, JSON.stringify(body), timeoutMs);
  }

  private async send<T>(method: 'GET' | 'POST', path: string, body: string | undefined, timeoutMs: number): Promise<T> {
    if (!path.startsWith('/')) throw new Error('path must be absolute');
    const nonce = path === '/status' ? newNonce() : null;
    const auth = nonce === null ? await this.authHeaders() : {};
    return this.request<T>(method, path, body, timeoutMs, nonce, auth);
  }

  private request<T>(method: 'GET' | 'POST', path: string, body: string | undefined, timeoutMs: number, nonce: string | null, auth: Record<string, string>): Promise<T> {
    const port = this.port();
    const payload = body === undefined ? undefined : Buffer.from(body, 'utf8');
    return new Promise<T>((resolve, reject) => {
      // Settle exactly once: a daemon that restarts mid-response fails the response, not the request.
      let done = false;
      const ok = (v: T) => { if (!done) { done = true; resolve(v); } };
      const fail = (e: Error) => { if (!done) { done = true; reject(e); } };
      const req = http.request(
        { host: HOST, port, path, method, agent: false, timeout: timeoutMs,
          headers: {
            host: `${HOST}:${port}`, 'x-codeloupe': '1', accept: 'application/json', ...auth,
            ...(nonce === null ? {} : { [NONCE_HEADER]: nonce }),
            ...(payload ? { 'content-type': 'application/json', 'content-length': String(payload.length) } : {}),
          } },
        res => {
          if (nonce !== null) this.judge(port, nonce, res.headers[PROOF_HEADER]);
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
      req.end(payload);
    });
  }

  /** Remembers the token as the daemon's when the proof matches the nonce asked, and forgets it otherwise. */
  private judge(port: number, nonce: string, proof: string | string[] | undefined): void {
    const dir = this.homeDir();
    const token = dir ? readToken(dir) : null;
    this.trusted = token && proof === proofOf(token, nonce) ? { token, port, at: Date.now() } : null;
  }
}
