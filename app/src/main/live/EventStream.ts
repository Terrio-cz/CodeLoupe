import http from 'node:http';
import type { LiveEvent } from '../../shared/jobs';

const HOST = '127.0.0.1';
const RETRY_MS = [1_000, 2_000, 5_000, 15_000];

/**
 * The daemon's server-sent event stream (`GET /events/stream`), opened only while someone listens. It resumes after the last
 * event it saw, reconnects with a growing wait when the daemon restarts, and hands on what happened (type, number, job id)
 * but not the event's data: the page reads the data it shows through the API.
 */
export class EventStream {
  private req: http.ClientRequest | null = null;
  private lastSeq: number | null = null;
  private failures = 0;
  private timer: NodeJS.Timeout | null = null;
  private running = false;

  constructor(
    private readonly port: () => number,
    private readonly onEvent: (e: LiveEvent) => void,
    /** The token header, once the daemon has proved it holds the token (DaemonClient.authHeaders). */
    private readonly auth: () => Promise<Record<string, string>> = async () => ({}),
  ) {}

  get open(): boolean {
    return this.running;
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.connect();
  }

  stop(): void {
    this.running = false;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.req?.destroy();
    this.req = null;
    this.lastSeq = null;
  }

  private connect(): void {
    void this.auth().catch(() => ({})).then(auth => { if (this.running) this.connectWith(auth); });
  }

  private connectWith(auth: Record<string, string>): void {
    const port = this.port();
    const headers: Record<string, string> = { host: `${HOST}:${port}`, 'x-codeloupe': '1', accept: 'text/event-stream', ...auth };
    if (this.lastSeq !== null) headers['last-event-id'] = String(this.lastSeq);
    const req = http.request({ host: HOST, port, path: '/events/stream', method: 'GET', agent: false, headers }, res => {
      if (res.statusCode !== 200) { res.resume(); this.retry(); return; }
      this.failures = 0;
      res.setEncoding('utf8');
      let buffer = '';
      res.on('data', (chunk: string) => {
        buffer += chunk;
        let at: number;
        while ((at = buffer.indexOf('\n\n')) >= 0) {
          this.frame(buffer.slice(0, at));
          buffer = buffer.slice(at + 2);
        }
        if (buffer.length > 1_000_000) buffer = '';
      });
      res.on('end', () => this.retry());
      res.on('error', () => this.retry());
    });
    req.on('error', () => this.retry());
    req.end();
    this.req = req;
  }

  private retry(): void {
    this.req = null;
    if (!this.running || this.timer) return;
    const wait = RETRY_MS[Math.min(this.failures++, RETRY_MS.length - 1)];
    this.timer = setTimeout(() => { this.timer = null; if (this.running) this.connect(); }, wait);
  }

  private frame(text: string): void {
    let seq: number | null = null;
    let type = '';
    let data = '';
    for (const line of text.split('\n')) {
      if (line.startsWith('id:')) seq = Number(line.slice(3).trim());
      else if (line.startsWith('event:')) type = line.slice(6).trim();
      else if (line.startsWith('data:')) data += line.slice(5).trim();
    }
    if (seq === null || !Number.isFinite(seq) || !type) return;
    this.lastSeq = seq;
    let at = '';
    let job: string | null = null;
    try {
      const parsed = JSON.parse(data) as { at?: unknown; data?: { id?: unknown } };
      at = typeof parsed.at === 'string' ? parsed.at : '';
      const id = parsed.data?.id;
      job = type.startsWith('job.') && typeof id === 'string' ? id : null;
    } catch { /* an event without readable data still tells that something happened */ }
    this.onEvent({ seq, at, type, job });
  }
}
