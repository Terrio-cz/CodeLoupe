// Mock of the daemon's jobs, slots and webhooks (src/shared/jobs.ts): every status, a chain that ended in a follow-up,
// a failed run with test counts, a queue behind a busy slot, a webhook with delivered, retried and failed deliveries.
import type { DaemonStatus } from '../../shared/contract';
import type { Delivery, JobChain, JobRecord, JobStatus, JobSummary, Webhook } from '../../shared/jobs';

const MIN = 60_000;
const iso = (ms: number) => new Date(ms).toISOString();

interface Seed {
  id: string;
  status: JobStatus;
  command: string;
  cwd?: string;
  slot?: string | null;
  tag?: string | null;
  exit?: number | null;
  reason?: string | null;
  startedAgoMin?: number;
  durationMin?: number;
  summary?: JobSummary | null;
  then?: string[];
  onFailure?: string[];
  root?: string;
  parent?: string;
  next?: string;
  failureBranch?: boolean;
  wake?: JobRecord['wakeOn'];
}

const SHOP_WT = 'C:/Users/dev/IdeaProjects/shop-api-worktrees/SHOP-671';
const CL = 'C:/Users/dev/IdeaProjects/codeloupe-worktrees/CL-43';

const GRADLE_FAIL: JobSummary = {
  tests: 412, passed: 409, failed: 2, skipped: 1,
  failures: ['FAILED OrderTotalsTest > coupon expiring mid checkout keeps the old total', 'FAILED ProductRoutesTest > returns page links on every response'],
  tail: ['> Task :app:test FAILED', '412 tests completed, 2 failed, 1 skipped', 'FAILURE: Build failed with an exception.', '* What went wrong:', 'Execution failed for task \':app:test\'.', 'BUILD FAILED in 3m 12s'],
};
const GRADLE_OK: JobSummary = { tests: 412, passed: 411, failed: 0, skipped: 1, failures: [], tail: ['> Task :app:test', '412 tests completed, 0 failed, 1 skipped', 'BUILD SUCCESSFUL in 2m 58s'] };

const SEEDS: Seed[] = [
  { id: 'J20261008-K2QF', status: 'running', command: 'gradlew test --console=plain', cwd: SHOP_WT, slot: 'gradle-test', tag: 'SHOP-671', startedAgoMin: 2.4, then: ['notify:tests of SHOP-671 done'], wake: 'failure' },
  { id: 'J20261008-M9ZD', status: 'running', command: 'npm run build', cwd: `${CL}/app`, slot: null, tag: 'CL-43', startedAgoMin: 0.6, wake: 'always' },
  { id: 'J20261008-P4TX', status: 'queued', command: 'gradlew test --console=plain', cwd: 'C:/Users/dev/IdeaProjects/shop-api-worktrees/SHOP-672', slot: 'gradle-test', tag: 'SHOP-672', wake: 'failure' },
  { id: 'J20261008-R7HB', status: 'queued', command: 'gradlew test --console=plain', cwd: 'C:/Users/dev/IdeaProjects/shop-api-worktrees/SHOP-664', slot: 'gradle-test', tag: 'SHOP-664', wake: 'failure' },
  { id: 'J20261008-A1B2', status: 'done', command: 'gradlew test --console=plain', cwd: SHOP_WT, slot: 'gradle-test', tag: 'SHOP-671', exit: 1, startedAgoMin: 31, durationMin: 3.2, summary: GRADLE_FAIL, then: ['notify:tests of SHOP-671 done'], onFailure: ['notify:tests of SHOP-671 failed'], next: 'J20261008-A1B3', wake: 'failure' },
  { id: 'J20261008-A1B3', status: 'done', command: 'notify: tests of SHOP-671 failed', cwd: SHOP_WT, slot: null, tag: 'SHOP-671', exit: 0, startedAgoMin: 27.8, durationMin: 0.0, root: 'J20261008-A1B2', parent: 'J20261008-A1B2', failureBranch: true, wake: 'failure' },
  { id: 'J20261008-C3D4', status: 'done', command: 'gradlew test --console=plain', cwd: 'C:/Users/dev/IdeaProjects/shop-api-worktrees/SHOP-664', slot: 'gradle-test', tag: 'SHOP-664', exit: 0, startedAgoMin: 75, durationMin: 2.9, summary: GRADLE_OK, then: ['webhook:http://127.0.0.1:9000/hooks/ci'], next: 'J20261008-C3D5', wake: 'failure' },
  { id: 'J20261008-C3D5', status: 'done', command: 'webhook: http://127.0.0.1:9000/hooks/ci', cwd: 'C:/Users/dev/IdeaProjects/shop-api-worktrees/SHOP-664', slot: null, tag: 'SHOP-664', exit: 0, startedAgoMin: 72, durationMin: 0.0, root: 'J20261008-C3D4', parent: 'J20261008-C3D4', wake: 'failure' },
  { id: 'J20261008-E5F6', status: 'denied', command: 'rm -rf build', cwd: SHOP_WT, slot: null, tag: 'SHOP-671', reason: 'policy hook: deletes outside the worktree', wake: 'always' },
  { id: 'J20261008-G7H8', status: 'cancelled', command: 'gradlew integrationTest', cwd: 'C:/Users/dev/IdeaProjects/shop-api-worktrees/SHOP-591', slot: 'vps-test', tag: 'SHOP-591', startedAgoMin: 190, durationMin: 4.1, wake: 'always' },
  { id: 'J20261008-J9K1', status: 'lost', command: 'gradlew test --console=plain', cwd: 'C:/Users/dev/IdeaProjects/shop-api-worktrees/SHOP-114', slot: 'gradle-test', tag: 'SHOP-114', reason: 'the daemon stopped while it ran', startedAgoMin: 400, durationMin: 1.4, wake: 'failure' },
  { id: 'J20261008-L2M3', status: 'error', command: 'jq .items out.json', cwd: SHOP_WT, slot: null, tag: null, reason: 'no such program: jq', wake: 'always' },
];

export class MockJobs {
  readonly jobs: JobRecord[];

  constructor(private readonly now: number) {
    this.jobs = SEEDS.map((s, i): JobRecord => {
      const started = s.startedAgoMin === undefined ? null : now - s.startedAgoMin * MIN;
      const ended = started !== null && s.durationMin !== undefined ? started + s.durationMin * MIN : null;
      return {
        id: s.id, rootId: s.root ?? s.id, parentId: s.parent ?? null, nextId: s.next ?? null, tag: s.tag ?? null,
        command: s.command, cwd: s.cwd ?? SHOP_WT, envNames: s.slot ? ['JAVA_HOME'] : [], slot: s.slot ?? null,
        status: s.status, exit: s.exit ?? null, reason: s.reason ?? null,
        createdAt: iso((started ?? now - (i + 1) * 40_000) - 4_000), startedAt: started === null ? null : iso(started), endedAt: ended === null ? null : iso(ended),
        durationMs: s.durationMin === undefined ? null : Math.round(s.durationMin * MIN),
        log: `C:/Users/dev/AppData/Local/codeloupe/jobs/${s.id}.log`, summary: s.summary ?? null, wakeOn: s.wake ?? 'failure',
        then: s.then ?? [], onFailure: s.onFailure ?? [], failureBranch: s.failureBranch ?? false,
      };
    });
  }

  list(limit: number): JobRecord[] {
    return this.jobs.slice(0, limit);
  }

  chain(id: string): JobChain | null {
    const first = this.jobs.find(j => j.id === id);
    if (!first) return null;
    const chain: JobRecord[] = [];
    for (let j: JobRecord | undefined = this.jobs.find(x => x.id === first.rootId) ?? first; j; j = j.nextId ? this.jobs.find(x => x.id === j!.nextId) : undefined) chain.push(j);
    const last = chain[chain.length - 1];
    const failed = chain.find(j => j.status !== 'queued' && j.status !== 'running' && !(j.status === 'done' && j.exit === 0));
    const done = last.status !== 'queued' && last.status !== 'running';
    return { done, exit: failed ? failed.exit || 1 : done ? 0 : 1, text: chain.map(j => `${j.id} ${j.status}: ${j.command}`).join('\n'), chain };
  }

  webhooks(): { items: Webhook[] } {
    return {
      items: [
        { id: 'W1', url: 'http://127.0.0.1:9000/hooks/ci', events: ['job.finished'], createdAt: iso(this.now - 5 * 86_400_000) },
        { id: 'W2', url: 'http://127.0.0.1:9001/hooks/all?token=hidden', events: ['job.*', 'build.done'], createdAt: iso(this.now - 2 * 86_400_000) },
      ],
    };
  }

  deliveries(): { items: Delivery[] } {
    const d = (n: number, webhookId: string | null, url: string, type: string, state: Delivery['state'], attempts: number, status: number | null, error: string | null, agoMin: number): Delivery => ({
      id: `D${n}`, webhookId, url, seq: 4200 + n, type, state, attempts, lastStatus: status, lastError: error, createdAt: iso(this.now - agoMin * MIN), updatedAt: iso(this.now - (agoMin - 0.1) * MIN),
    });
    return {
      items: [
        d(5, 'W2', 'http://127.0.0.1:9001/hooks/all?token=hidden', 'job.started', 'pending', 2, null, 'connection refused', 2),
        d(4, 'W1', 'http://127.0.0.1:9000/hooks/ci', 'job.finished', 'delivered', 1, 200, null, 27),
        d(3, 'W2', 'http://127.0.0.1:9001/hooks/all?token=hidden', 'job.finished', 'failed', 5, 502, 'bad gateway', 28),
        d(2, null, 'http://127.0.0.1:9000/hooks/ci', 'job.finished', 'delivered', 2, 204, null, 72),
        d(1, 'W2', 'http://127.0.0.1:9001/hooks/all?token=hidden', 'build.done', 'delivered', 1, 200, null, 120),
      ],
    };
  }

  /** `GET /status` as far as the Jobs screen reads it: running and queued jobs and the slots with their holders. */
  status(base: Partial<DaemonStatus>): DaemonStatus {
    const running = this.jobs.filter(j => j.status === 'running');
    const queued = this.jobs.filter(j => j.status === 'queued');
    const ids = (xs: JobRecord[], slot: string) => xs.filter(j => j.slot === slot).map(j => j.id);
    return {
      name: 'codeloupe', version: '0.4.0', pid: 1234, port: 47391, home: 'C:/Users/dev/AppData/Local/codeloupe', uptimeSec: 7_200, rssMb: 96, heapMb: 41, cpuSec: 53,
      calls: { total: 3412, errors: 6, busy: 3 }, queue: { fast: { running: null, waiting: [] }, heavy: { running: null, waiting: [] } }, repos: [],
      jobs: {
        running: running.length, queued: queued.length, policyHook: true,
        slots: [
          { name: 'gradle-test', capacity: 2, running: ids(running, 'gradle-test'), waiting: ids(queued, 'gradle-test') },
          { name: 'vps-test', capacity: 1, running: [], waiting: [] },
        ],
      },
      ...base,
    };
  }
}
