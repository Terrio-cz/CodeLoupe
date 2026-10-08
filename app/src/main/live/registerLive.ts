import { JOB_CH } from '../../shared/jobs';
import type { JobLogReader } from '../jobs/JobLogReader';
import type { EventStream } from './EventStream';

/** The live stream is open while at least one page listens: each listener is one `liveStart`, closed by one `liveStop`. */
export function registerLive(
  stream: EventStream,
  logs: JobLogReader,
  handle: <A extends unknown[], R>(channel: string, fn: (...args: A) => Promise<R> | R) => void,
  allowed: () => boolean,
): { reset(): void } {
  let listeners = 0;
  handle(JOB_CH.liveStart, () => { if (allowed() && ++listeners === 1) stream.start(); });
  handle(JOB_CH.liveStop, () => { if (listeners > 0 && --listeners === 0) stream.stop(); });
  handle(JOB_CH.log, (id: unknown) => (allowed() ? logs.read(id) : null));
  // The window that listened is gone: its unsubscribe never arrives.
  return { reset: () => { listeners = 0; stream.stop(); } };
}
