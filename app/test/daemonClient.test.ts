import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { DaemonClient } from '../src/main/daemon/DaemonClient';
import { proofOf, readToken } from '../src/main/daemon/DaemonToken';

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

  describe('with a token in the daemon home', () => {
    const TOKEN = 'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90';
    const home = () => {
      const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cl-home-'));
      fs.writeFileSync(path.join(dir, 'daemon.token'), `x-codeloupe-token: ${TOKEN}
`);
      return dir;
    };

    /** A fake daemon: answers /status with `proof(nonce)` (or none) and records the headers of every other request. */
    function daemon(proof: ((nonce: string) => string | null) | null) {
      const seen: http.IncomingHttpHeaders[] = [];
      const start = serve((req, res) => {
        if (req.url === '/status') {
          const given = proof ? proof(String(req.headers['x-codeloupe-nonce'])) : null;
          res.writeHead(200, given ? { 'x-codeloupe-proof': given } : {});
          res.end('{"name":"codeloupe"}');
          return;
        }
        seen.push(req.headers);
        res.end('{"ok":true}');
      });
      return { seen, start };
    }

    it('reads the header line and the bare value alike', () => {
      const dir = home();
      expect(readToken(dir)).toBe(TOKEN);
      fs.writeFileSync(path.join(dir, 'daemon.token'), TOKEN);
      expect(readToken(dir)).toBe(TOKEN);
      fs.writeFileSync(path.join(dir, 'daemon.token'), 'short');
      expect(readToken(dir)).toBeNull();
      expect(readToken(path.join(dir, 'missing'))).toBeNull();
    });

    it('sends the token to a daemon that proved it holds it', async () => {
      const dir = home();
      const fake = daemon(nonce => proofOf(TOKEN, nonce));
      const port = await fake.start;
      const client = new DaemonClient(() => port, () => dir);
      await client.get('/jobs');
      expect(fake.seen[0]['x-codeloupe-token']).toBe(TOKEN);
      expect(fake.seen[0]['x-codeloupe']).toBe('1');
    });

    it('does not send the token to a process that answers without the proof or with a wrong one', async () => {
      for (const proof of [null, () => '0'.repeat(64), (nonce: string) => proofOf('f'.repeat(64), nonce)]) {
        const dir = home();
        const fake = daemon(proof);
        const port = await fake.start;
        const client = new DaemonClient(() => port, () => dir);
        await client.get('/jobs');
        expect(fake.seen[0]['x-codeloupe-token']).toBeUndefined();
        expect(await client.authHeaders()).toEqual({});
        await new Promise<void>(r => server!.close(() => r()));
        server = null;
      }
    });

    it('sends only the plain header when there is no token file, as to a daemon from before the token', async () => {
      const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cl-home-'));
      const fake = daemon(null);
      const port = await fake.start;
      await new DaemonClient(() => port, () => dir).get('/jobs');
      expect(fake.seen[0]['x-codeloupe']).toBe('1');
      expect(fake.seen[0]['x-codeloupe-token']).toBeUndefined();
    });
  });
});
