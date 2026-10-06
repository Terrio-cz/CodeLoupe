// Priority job queue of the daemon. Jobs with the same key coalesce (callers share one promise).
// Lane "fast" (overlay refresh, writes) runs one job at a time in-process; lane "heavy" (base builds)
// runs at most one job at a time, normally in a child process. Reads never queue.
export class Queue {
  constructor() {
    this.lanes = { fast: { running: null, waiting: [] }, heavy: { running: null, waiting: [] } };
    this.byKey = new Map();
    this.stats = { done: 0, failed: 0, coalesced: 0, waitMsMax: 0 };
  }

  run(lane, key, fn) {
    if (this.byKey.has(key)) { this.stats.coalesced++; return this.byKey.get(key).promise; }
    let resolve, reject;
    const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
    const job = { key, fn, resolve, reject, promise, queuedAt: Date.now() };
    this.byKey.set(key, job);
    this.lanes[lane].waiting.push(job);
    this.pump(lane);
    return promise;
  }

  has(key) { return this.byKey.has(key); }

  pump(lane) {
    const l = this.lanes[lane];
    if (l.running || !l.waiting.length) return;
    const job = l.waiting.shift(); l.running = job;
    this.stats.waitMsMax = Math.max(this.stats.waitMsMax, Date.now() - job.queuedAt);
    Promise.resolve().then(job.fn).then(v => { this.stats.done++; job.resolve(v); }, e => { this.stats.failed++; job.reject(e); })
      .finally(() => { this.byKey.delete(job.key); l.running = null; this.pump(lane); });
  }

  snapshot() {
    const lane = l => ({ running: l.running?.key ?? null, waiting: l.waiting.map(j => j.key) });
    return { fast: lane(this.lanes.fast), heavy: lane(this.lanes.heavy), ...this.stats };
  }
}
