import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { DaemonClient } from '../src/main/daemon/DaemonClient';

let server: http.Server | null = null;
afterEach(() => new Promise<void>(r => (server ? server.close(() => r()) : r())));

function serve(handler: http.RequestListener): Promise<number> {
  server = http.createServer(handler);
  return new Promise(r => server!.listen(0, '127.0.0.1', () => r((server!.address() as AddressInfo).port)));
}

describe('DaemonClient', () => {
  it('sends the local headers and no Origin', async () => {
    let seen: http.IncomingHttpHeaders = {};
    const port = await serve((req, res) => { seen = req.headers; res.end('{"ok":true}'); });
    await expect(new DaemonClient(() => port).get('/x')).resolves.toEqual({ ok: true });
    expect(seen['x-codeloupe']).toBe('1');
    expect(seen.host).toBe(`127.0.0.1:${port}`);
    expect(seen.origin).toBeUndefined();
  });

  it('rejects instead of hanging when the daemon dies mid-response', async () => {
    const port = await serve((_req, res) => {
      res.writeHead(200, { 'content-length': '1000' });
      res.write('{"partial":');
      setTimeout(() => res.socket?.destroy(), 20);
    });
    await expect(new DaemonClient(() => port).get('/x', 2000)).rejects.toThrow();
  });

  it('maps error bodies to codes', async () => {
    const port = await serve((_req, res) => { res.writeHead(503); res.end('{"error":{"code":"busy","message":"later"}}'); });
    await expect(new DaemonClient(() => port).get('/x')).rejects.toMatchObject({ status: 503, code: 'busy' });
  });
});
