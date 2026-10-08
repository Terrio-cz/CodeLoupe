import fs from 'node:fs';
import http from 'node:http';
import type { AddressInfo } from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { MockJobs } from '../src/main/api/mockJobs';
import { JobLogReader } from '../src/main/jobs/JobLogReader';
import { redactLine } from '../src/main/jobs/redact';
import { EventStream } from '../src/main/live/EventStream';
import { deliveryLabel, isActive, jobCounts, jobStatus, maskUrl, matches, stepLabel } from '../src/renderer/src/jobModel';
import type { JobRecord, LiveEvent } from '../src/shared/jobs';

const mock = new MockJobs(Date.parse('2026-10-08T12:00:00Z'));
const job = (id: string) => mock.jobs.find(j => j.id === id)!;

describe('job model', () => {
  it('counts every state once: running, queued, passed, failed (also lost and error) and stopped (denied, cancelled)', () => {
    expect(jobCounts(mock.jobs)).toEqual({ running: 2, queued: 2, passed: 3, failed: 3, stopped: 2 });
    expect(mock.jobs.filter(isActive)).toHaveLength(4);
  });

  it('names a status in words with a tone, and the exit of a failed run', () => {
    expect(jobStatus(job('J20261008-A1B2'))).toEqual({ tone: 'critical', label: 'selhalo (exit 1)' });
    expect(jobStatus(job('J20261008-P4TX'))).toEqual({ tone: 'neutral', label: 'čeká na slot gradle-test' });
    expect(jobStatus(job('J20261008-C3D4'))).toEqual({ tone: 'ok', label: 'hotovo' });
    expect(jobStatus(job('J20261008-J9K1')).label).toBe('ztraceno');
  });

  it('filters by what is going on and by text', () => {
    const f = (filter: '' | 'active' | 'finished' | 'failed', q = '') => mock.jobs.filter(j => matches(j, { filter, q })).map(j => j.id);
    expect(f('active')).toHaveLength(4);
    expect(f('failed')).toEqual(['J20261008-A1B2', 'J20261008-J9K1', 'J20261008-L2M3']);
    expect(f('finished')).toHaveLength(8);
    expect(f('', 'ter-672')).toEqual(['J20261008-P4TX']);
  });

  it('never shows the query or the credentials of a webhook address', () => {
    expect(maskUrl('http://127.0.0.1:9001/hooks/all?token=hidden')).toBe('http://127.0.0.1:9001/hooks/all?…');
    expect(maskUrl('https://user:pw@hooks.example.com/a')).toBe('https://hooks.example.com/a');
    expect(stepLabel('webhook:http://127.0.0.1:9000/x?key=secret')).toBe('webhook:http://127.0.0.1:9000/x?…');
    expect(stepLabel('notify:tests done')).toBe('notify:tests done');
  });

  it('describes a delivery: delivered, waiting, retrying and failed', () => {
    const [pending, delivered, failed] = mock.deliveries().items;
    expect(deliveryLabel(pending)).toBe('opakuje (2.)');
    expect(deliveryLabel(delivered)).toBe('doručeno');
    expect(deliveryLabel(failed)).toBe('selhalo');
  });

  it('follows a chain from its first job to the follow-up it started', () => {
    const chain = mock.chain('J20261008-A1B3')!;
    expect(chain.chain.map(j => j.id)).toEqual(['J20261008-A1B2', 'J20261008-A1B3']);
    expect(chain.done).toBe(true);
    expect(chain.exit).toBe(1);
    expect(mock.chain('nope')).toBeNull();
  });
});

describe('redactLine', () => {
  it.each([
    ['token ghp_abcdefghijklmnopqrstuvwxyz0123456789 end', 'token *** end'],
    ['Authorization: Bearer abcdef1234567890abcdef', 'Authorization: ***'],
    ['export YOUTRACK_TOKEN=perm:abcdefghijklmnop', 'export YOUTRACK_TOKEN=***'],
    ['password: "hunter2 hunter2"', 'password: ***'],
    ['clone https://user:secret@github.com/a/b.git', 'clone https://***@github.com/a/b.git'],
    ['\u001B[31mFAILED\u001B[0m OrderTest', 'FAILED OrderTest'],
    ['412 tests completed, 2 failed', '412 tests completed, 2 failed'],
  ])('%s', (input, expected) => {
    expect(redactLine(input)).toBe(expected);
  });
});

describe('JobLogReader', () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'cl-joblog-'));
  fs.mkdirSync(path.join(home, 'jobs'));
  const file = (id: string) => path.join(home, 'jobs', `${id}.log`);
  const record = (id: string, patch: Partial<JobRecord> = {}): JobRecord => ({ ...job('J20261008-C3D4'), id, rootId: id, nextId: null, log: file(id), ...patch });
  const reader = (records: JobRecord[]) => new JobLogReader({
    get: async <T>(p: string) => {
      const r = records.find(x => p === `/jobs/${x.id}`);
      if (!r) throw new Error('404');
      return { done: true, exit: 0, text: '', chain: [r] } as T;
    },
  }, () => home);

  it('reads the tail of a finished job, without colours and with secret-looking values masked', async () => {
    fs.writeFileSync(file('J1'), ['\u001B[32mBUILD\u001B[0m', 'GITHUB_TOKEN=ghp_abcdefghijklmnopqrstuvwxyz0123456789', 'done'].join('\n'));
    const log = await reader([record('J1')]).read('J1');
    expect(log).toEqual({ text: 'BUILD\nGITHUB_TOKEN=***\ndone', truncated: false, bytes: fs.statSync(file('J1')).size });
  });

  it('keeps only the last lines of a long log and says so', async () => {
    fs.writeFileSync(file('J2'), Array.from({ length: 1000 }, (_, i) => `line ${i}`).join('\n'));
    const log = (await reader([record('J2')]).read('J2'))!;
    expect(log.truncated).toBe(true);
    expect(log.text.split('\n')).toHaveLength(400);
    expect(log.text.endsWith('line 999')).toBe(true);
  });

  it('refuses a running job, an unknown one, a bad id and a record that points to another file', async () => {
    fs.writeFileSync(file('J3'), 'x');
    fs.writeFileSync(path.join(os.tmpdir(), 'cl-elsewhere.log'), 'secret');
    const r = reader([record('J3', { status: 'running' }), record('J4', { log: path.join(os.tmpdir(), 'cl-elsewhere.log') })]);
    expect(await r.read('J3')).toBeNull();
    expect(await r.read('J4')).toBeNull();
    expect(await r.read('J5')).toBeNull();
    expect(await r.read('../x')).toBeNull();
    expect(await r.read(42)).toBeNull();
  });
});

describe('EventStream', () => {
  let server: http.Server | null = null;
  afterEach(() => new Promise<void>(r => (server ? server.close(() => r()) : r())));

  it('hands on the type and job of each event, resumes after the last one and stops when told to', async () => {
    const seen: LiveEvent[] = [];
    const lastIds: (string | undefined)[] = [];
    const sockets: http.ServerResponse[] = [];
    server = http.createServer((req, res) => {
      lastIds.push(req.headers['last-event-id'] as string | undefined);
      expect(req.headers['x-codeloupe']).toBe('1');
      expect(req.headers.origin).toBeUndefined();
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.write('retry: 3000\n: connected\n\n');
      const event = (seq: number, type: string, data: object) => `id: ${seq}\nevent: ${type}\ndata: ${JSON.stringify({ seq, at: '2026-10-08T12:00:00.000Z', type, data })}\n\n`;
      res.write(event(7, 'job.started', { id: 'J1', command: 'secret command' }));
      res.write(event(8, 'build.done', { repo: 'r' }));
      sockets.push(res);
    });
    const port = await new Promise<number>(r => server!.listen(0, '127.0.0.1', () => r((server!.address() as AddressInfo).port)));
    const stream = new EventStream(() => port, e => seen.push(e));
    stream.start();
    await until(() => seen.length === 2);
    expect(seen).toEqual([
      { seq: 7, at: '2026-10-08T12:00:00.000Z', type: 'job.started', job: 'J1' },
      { seq: 8, at: '2026-10-08T12:00:00.000Z', type: 'build.done', job: null },
    ]);
    // The data of an event (a command line) is not passed on.
    expect(JSON.stringify(seen)).not.toContain('secret command');

    // The daemon restarts: the stream reconnects and asks for what came after event 8.
    sockets[0].destroy();
    await until(() => lastIds.length === 2, 5000);
    expect(lastIds).toEqual([undefined, '8']);
    stream.stop();
    expect(stream.open).toBe(false);
  });
});

async function until(check: () => boolean, ms = 3000): Promise<void> {
  const t0 = Date.now();
  while (!check()) {
    if (Date.now() - t0 > ms) throw new Error('timeout');
    await new Promise(r => setTimeout(r, 20));
  }
}
