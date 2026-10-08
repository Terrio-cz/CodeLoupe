import fs from 'node:fs';
import path from 'node:path';
import type { JobChain, JobLogText } from '../../shared/jobs';
import { redactLine } from './redact';

const ID = /^[A-Za-z0-9_-]{1,64}$/;
const MAX_BYTES = 96 * 1024;
const MAX_LINES = 400;

export interface JobDaemon {
  get<T>(path: string): Promise<T>;
}

/**
 * The tail of a finished job's log. The page names a job id; main asks the daemon for the job, accepts only a finished
 * one (the daemon masks the stored secrets in a log when the job ends, not while it runs), and reads the file the daemon
 * keeps for that id under its own home, never a path from the page or from the record.
 */
export class JobLogReader {
  constructor(private readonly daemon: JobDaemon, private readonly homeDir: () => string) {}

  async read(input: unknown): Promise<JobLogText | null> {
    if (typeof input !== 'string' || !ID.test(input)) return null;
    const chain = await this.daemon.get<JobChain>(`/jobs/${encodeURIComponent(input)}`).catch(() => null);
    const job = chain?.chain.find(j => j.id === input);
    if (!job || job.status === 'queued' || job.status === 'running') return null;
    const file = path.join(this.homeDir(), 'jobs', `${input}.log`);
    // The record's own path must be that file: a record that points elsewhere is not read.
    if (path.resolve(job.log).toLowerCase() !== path.resolve(file).toLowerCase()) return null;
    return tail(file);
  }
}

function tail(file: string): JobLogText | null {
  let fd: number | null = null;
  try {
    fd = fs.openSync(file, 'r');
    const { size } = fs.fstatSync(fd);
    const start = Math.max(0, size - MAX_BYTES);
    const buf = Buffer.alloc(size - start);
    fs.readSync(fd, buf, 0, buf.length, start);
    let lines = buf.toString('utf8').split(/\r?\n/);
    // A tail that begins inside a line drops that line.
    if (start > 0) lines = lines.slice(1);
    if (lines.at(-1) === '') lines.pop();
    const cut = lines.length > MAX_LINES;
    return { text: lines.slice(-MAX_LINES).map(redactLine).join('\n'), truncated: start > 0 || cut, bytes: size };
  } catch {
    return null;
  } finally {
    if (fd !== null) fs.closeSync(fd);
  }
}
