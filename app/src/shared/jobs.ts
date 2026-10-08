// Jobs, slots, webhooks and the live event stream of the daemon
// (`GET /jobs`, `/jobs/{id}`, `/webhooks`, `/webhooks/deliveries`, `/events/stream`; src/main/kotlin/codeloupe/{jobs,events}).
// Read-only here. The commands, summaries and logs the daemon holds are scrubbed of secrets before they reach the app.

import type { Iso } from './contract';

export type JobStatus = 'queued' | 'running' | 'done' | 'denied' | 'cancelled' | 'lost' | 'error';

/** What an agent needs from a log: test counts when the output has them, failure lines, the last lines. */
export interface JobSummary {
  tests: number | null;
  passed: number | null;
  failed: number | null;
  skipped: number | null;
  failures: string[];
  tail: string[];
}

export interface JobRecord {
  id: string;
  /** First job of the chain this one belongs to; its own id when it started the chain. */
  rootId: string;
  parentId: string | null;
  /** The follow-up job a completion action started, once this one finished. */
  nextId: string | null;
  tag: string | null;
  command: string;
  cwd: string;
  /** Names of the environment variables the job got, never their values. */
  envNames: string[];
  slot: string | null;
  status: JobStatus;
  exit: number | null;
  reason: string | null;
  createdAt: Iso;
  startedAt: Iso | null;
  endedAt: Iso | null;
  durationMs: number | null;
  log: string;
  summary: JobSummary | null;
  wakeOn: 'always' | 'failure' | 'never';
  /** Declared follow-up steps, as written: `job:<command>`, `notify:<message>`, `webhook:<url>`. */
  then: string[];
  onFailure: string[];
  /** Started by an `onFailure` step: the chain failed even when this job passes. */
  failureBranch: boolean;
}

/** `GET /jobs/{id}`: the chain of the job and how it ended. */
export interface JobChain {
  done: boolean;
  /** What `job wait` exits with. */
  exit: number;
  /** The compact report an agent reads. */
  text: string;
  chain: JobRecord[];
}

export interface SlotSnapshot {
  name: string;
  capacity: number;
  /** Ids of the jobs holding the slot. */
  running: string[];
  /** Ids of the jobs waiting for it, first in line first. */
  waiting: string[];
}

/** `jobs` of `GET /status`. */
export interface JobsSnapshot {
  running: number;
  queued: number;
  policyHook: boolean;
  slots: SlotSnapshot[];
}

export interface Webhook {
  id: string;
  url: string;
  events: string[];
  createdAt: Iso;
}

export interface Delivery {
  id: string;
  /** Null for a job's own `webhook:` step. */
  webhookId: string | null;
  url: string;
  seq: number;
  type: string;
  state: 'pending' | 'delivered' | 'failed';
  attempts: number;
  lastStatus: number | null;
  lastError: string | null;
  createdAt: Iso;
  updatedAt: Iso;
}

/** What the page learns from the live stream: that something happened, not the event's data. */
export interface LiveEvent {
  seq: number;
  at: Iso;
  type: string;
  /** The job the event is about, when it is one. */
  job: string | null;
}

export interface JobLogText {
  /** Last lines of the log, ANSI codes removed, secret-looking values masked. */
  text: string;
  /** The log has more before these lines. */
  truncated: boolean;
  /** Size of the log file. */
  bytes: number;
}

export const JOB_CH = {
  log: 'cl:jobs:log',
  liveStart: 'cl:live:start',
  liveStop: 'cl:live:stop',
  livePush: 'cl:live:push',
} as const;

export interface JobsBridge {
  /** The tail of a finished job's log; null when it cannot be shown (running, unknown, file gone). */
  log(id: string): Promise<JobLogText | null>;
}

export interface LiveBridge {
  /** Calls back for every event of the daemon while subscribed; the stream is open only while someone listens. */
  subscribe(cb: (e: LiveEvent) => void): () => void;
}
