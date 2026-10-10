// Mock of the workspace registry, its Docker inventory and the cleanup plan (src/shared/workspaces.ts):
// every registry state, owned and adopted and unowned resources, every verdict, a release that is still retrying.
import type {
  DockerResource,
  PlanEntry,
  PortReport,
  ReconcilePlan,
  ReleaseStatus,
  ResourceReport,
  Verdict,
  Workspace,
  WorkspaceList,
  WorkspaceState,
} from '../../shared/workspaces';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;

interface Seed {
  repo: 'shop-api' | 'CodeLoupe';
  name: string;
  role: Workspace['role'];
  state: WorkspaceState;
  ageDays: number;
  ahead?: number;
  merged?: boolean;
  task?: [state: string, resolved: boolean, summary: string];
  note?: string;
  sizeMb?: number;
}

const SEEDS: Seed[] = [
  { repo: 'shop-api', name: 'shop-api', role: 'main', state: 'active', ageDays: 0.1, merged: true, sizeMb: 1_900 },
  { repo: 'shop-api', name: 'SHOP-671', role: 'worktree', state: 'active', ageDays: 0.05, ahead: 3, task: ['In Progress', false, 'Fix order totals when a coupon expires during checkout'], sizeMb: 640 },
  { repo: 'shop-api', name: 'SHOP-672', role: 'worktree', state: 'active', ageDays: 0.3, ahead: 1, task: ['In Progress', false, 'Batch stock lookups for the product list endpoint'], sizeMb: 580 },
  { repo: 'shop-api', name: 'SHOP-664', role: 'worktree', state: 'landed', ageDays: 1.2, ahead: 4, merged: true, task: ['Done', true, 'Add pagination links to every list response'], sizeMb: 710 },
  { repo: 'shop-api', name: 'SHOP-591', role: 'worktree', state: 'abandoned', ageDays: 21, ahead: 2, merged: false, task: ['Ready for testing', false, 'Send rate-limit headers on every API response'], note: 'untouched for 21 days, 2 commits are not on origin/master', sizeMb: 530 },
  { repo: 'shop-api', name: 'SHOP-92', role: 'worktree', state: 'landed', ageDays: 3, ahead: 1, merged: true, task: ['Done', true, 'Retry partial downloads in the catalog importer'], sizeMb: 420 },
  { repo: 'shop-api', name: 'SHOP-420-old', role: 'directory', state: 'orphan', ageDays: 40, note: 'no .git: git has no worktree here', sizeMb: 310 },
  { repo: 'CodeLoupe', name: 'CodeLoupe', role: 'main', state: 'active', ageDays: 0.2, merged: true, sizeMb: 260 },
  { repo: 'CodeLoupe', name: 'CL-43', role: 'worktree', state: 'active', ageDays: 0.1, ahead: 5, task: ['In Progress', false, 'Electron app: window, tray, notifications, daemon management'], sizeMb: 190 },
  { repo: 'CodeLoupe', name: 'CL-56', role: 'worktree', state: 'landed', ageDays: 2, ahead: 9, merged: true, task: ['Done', true, 'Port CodeLoupe to Kotlin/JVM with parity to phase 1'], sizeMb: 240 },
];

const REPO_PATH: Record<Seed['repo'], string> = {
  'shop-api': 'C:/Users/dev/IdeaProjects/shop-api',
  CodeLoupe: 'C:/Users/dev/IdeaProjects/CodeLoupe',
};
const ROOT: Record<Seed['repo'], string> = {
  'shop-api': 'C:/Users/dev/IdeaProjects/shop-api-worktrees',
  CodeLoupe: 'C:/Users/dev/IdeaProjects/codeloupe-worktrees',
};

const iso = (ms: number) => new Date(ms).toISOString();

/** 12 hex digits from a name (FNV-1a twice), so every mock resource has its own id like Docker's short ids. */
function shortId(s: string): string {
  let a = 2166136261;
  let b = 5381;
  for (let i = 0; i < s.length; i++) {
    a = Math.imul(a ^ s.charCodeAt(i), 16777619);
    b = Math.imul(b ^ s.charCodeAt(i), 33);
  }
  return ((a >>> 0).toString(16).padStart(8, '0') + (b >>> 0).toString(16).padStart(8, '0')).slice(0, 12);
}

export class MockWorkspaces {
  constructor(private readonly now: number) {}

  workspaces(size: boolean): WorkspaceList {
    const repos = (['shop-api', 'CodeLoupe'] as const).map(name => {
      const workspaces = SEEDS.filter(s => s.repo === name).map((s): Workspace => ({
        path: s.role === 'main' ? REPO_PATH[name] : `${ROOT[name]}/${s.name}`,
        name: s.name, role: s.role, state: s.state, note: s.note ?? null,
        branch: s.role === 'directory' ? null : s.role === 'main' ? (name === 'CodeLoupe' ? 'main' : 'master') : s.name,
        head: s.role === 'directory' ? null : (s.name.length * 7919 + 4_101_233).toString(16).padStart(40, '0').slice(0, 40),
        taskId: s.task ? s.name : null,
        merge: s.role === 'directory' ? null : { defaultRef: name === 'CodeLoupe' ? 'origin/main' : 'origin/master', ahead: s.ahead ?? 0, merged: s.merged ?? false, subject: s.task ? `${s.name} ${s.task[2].slice(0, 40).toLowerCase()}` : 'merge origin/master' },
        tracker: s.task ? { state: s.task[0], resolved: s.task[1], summary: s.task[2] } : null,
        lastActivity: iso(this.now - s.ageDays * DAY),
        sizeBytes: size && s.sizeMb ? s.sizeMb * 1024 * 1024 : null,
      }));
      const counts: Record<string, number> = {};
      for (const w of workspaces) counts[w.state] = (counts[w.state] ?? 0) + 1;
      return { repo: REPO_PATH[name], name, commonDir: `${REPO_PATH[name]}/.git`, defaultRef: name === 'CodeLoupe' ? 'origin/main' : 'origin/master', roots: [ROOT[name]], counts, workspaces };
    });
    return { generatedAt: iso(this.now), repos, problems: [] };
  }

  private stateOf(repo: string, workspace: string): WorkspaceState | null {
    return SEEDS.find(s => s.repo === repo && s.name === workspace)?.state ?? null;
  }

  resources(stats = false): ResourceReport {
    const out: DockerResource[] = [];
    const add = (kind: DockerResource['kind'], name: string, repo: string | null, workspace: string | null, extra: Partial<DockerResource> = {}) => {
      const owned = repo !== null && workspace !== null;
      out.push({
        kind, id: shortId(`${kind}:${name}`), names: [name],
        ownership: owned ? 'owned' : 'unowned', repo, workspace, task: owned && /^[A-Z]+-\d+$/.test(workspace!) ? workspace : null,
        via: owned ? 'labels' : null, workspaceState: owned ? this.stateOf(repo!, workspace!) : null,
        state: kind === 'container' ? 'running' : null, created: iso(this.now - 2 * DAY), project: null, publishedPorts: [], memoryBytes: null, ...extra,
      });
    };
    add('container', 'shop-671-app-1', 'shop-api', 'SHOP-671', { publishedPorts: [19001] });
    add('container', 'shop-671-postgres-1', 'shop-api', 'SHOP-671', { publishedPorts: [19002] });
    add('volume', 'shop-671_pgdata', 'shop-api', 'SHOP-671');
    add('network', 'shop-671_default', 'shop-api', 'SHOP-671');
    add('container', 'shop-672-app-1', 'shop-api', 'SHOP-672', { ownership: 'adopted', via: 'adoption rule 1 (shop-672)', publishedPorts: [19010] });
    add('container', 'shop-664-app-1', 'shop-api', 'SHOP-664', { state: 'exited', created: iso(this.now - 3 * DAY) });
    add('volume', 'shop-664_pgdata', 'shop-api', 'SHOP-664', { created: iso(this.now - 3 * DAY) });
    add('network', 'shop-664_default', 'shop-api', 'SHOP-664', { created: iso(this.now - 3 * DAY) });
    add('image', 'shop-664-app:latest', 'shop-api', 'SHOP-664', { created: iso(this.now - 3 * DAY) });
    add('container', 'shop-591-postgres-1', 'shop-api', 'SHOP-591', { state: 'exited', created: iso(this.now - 22 * DAY) });
    add('volume', 'shop-591_pgdata', 'shop-api', 'SHOP-591', { created: iso(this.now - 22 * DAY) });
    add('volume', 'shop-92_pgdata', 'shop-api', 'SHOP-92', { created: iso(this.now - 4 * DAY) });
    add('volume', 'shop-420_pgdata', 'shop-api', 'SHOP-420-old', { created: iso(this.now - 41 * DAY) });
    add('container', 'cl-43-daemon-1', 'CodeLoupe', 'CL-43', { publishedPorts: [19020] });
    add('volume', 'cl-56_cache', 'CodeLoupe', 'CL-56', { created: iso(this.now - 3 * DAY) });
    for (const n of ['sample-db-1', 'other-postgres', 'other-redis']) add('container', n, null, null, { publishedPorts: [25460] });
    for (let i = 0; i < 6; i++) add('volume', `unrelated_data_${i}`, null, null);
    for (let i = 0; i < 9; i++) add('image', `unrelated/image-${i}:latest`, null, null);
    if (stats) {
      for (const r of out) if (r.kind === 'container' && r.state === 'running' && r.ownership !== 'unowned') r.memoryBytes = (60 + (r.names[0].length * 37) % 400) * 1024 * 1024;
    }
    const counts: Record<string, number> = {};
    for (const r of out) counts[`${r.ownership}/${r.kind}`] = (counts[`${r.ownership}/${r.kind}`] ?? 0) + 1;
    return { generatedAt: iso(this.now), engine: 'npipe:////./pipe/dockerDesktopLinuxEngine (Docker 29.8.0)', counts, resources: out, problems: [] };
  }

  reconcile(): ReconcilePlan {
    const released = new Set(this.releases().items.map(r => `${r.repo}/${r.workspace}`));
    const entries: PlanEntry[] = this.resources().resources.filter(r => r.ownership !== 'unowned').map(r => {
      const kind = r.kind;
      const name = r.names[0];
      const isReleased = released.has(`${r.repo}/${r.workspace}`);
      let verdict: Verdict = 'keep';
      let reason = 'the workspace is active';
      if (isReleased) { verdict = 'auto'; reason = 'the workspace was released'; }
      else if (r.workspaceState === 'landed') { [verdict, reason] = r.ownership === 'adopted' ? ['confirm', 'the workspace landed; the resource is adopted by a rule, not labelled'] : ['auto', 'the workspace landed']; }
      else if (r.workspaceState === 'abandoned') { verdict = 'confirm'; reason = 'the workspace is abandoned'; }
      else if (r.workspaceState === 'orphan') { verdict = 'confirm'; reason = 'the workspace is an orphan'; }
      return {
        key: `${kind}:${kind === 'volume' ? name : r.id}`, kind, name, repo: r.repo, workspace: r.workspace, ownership: r.ownership, workspaceState: r.workspaceState,
        verdict, reason, released: isReleased, attempts: isReleased ? 2 : 0, nextAttempt: isReleased ? iso(this.now + 4 * 60_000) : null, lastError: isReleased ? 'volume is in use' : null,
      };
    });
    entries.push({
      key: `directory:${ROOT['shop-api']}/SHOP-420-old`, kind: 'directory', name: `${ROOT['shop-api']}/SHOP-420-old`, repo: 'shop-api', workspace: 'SHOP-420-old', ownership: null,
      workspaceState: 'orphan', verdict: 'confirm', reason: 'a directory under a worktree root that git has no worktree for', released: false, attempts: 0, nextAttempt: null, lastError: null,
    });
    const counts: Record<string, number> = {};
    for (const e of entries) counts[e.verdict] = (counts[e.verdict] ?? 0) + 1;
    return { generatedAt: iso(this.now), auto: false, counts, entries, problems: [], planHash: 'mockplanhash0001' };
  }

  releases(): { items: ReleaseStatus[] } {
    return { items: [{ repo: 'shop-api', workspace: 'SHOP-92', at: iso(this.now - 25 * 60_000), pending: 1, retrying: 1 }] };
  }

  ports(): PortReport {
    const a = (port: number, workspace: string, name: string, state: 'free' | 'in-use' | 'conflict', usedBy: string | null, repo = 'shop-api') =>
      ({ allocation: { port, repo, workspace, name, at: iso(this.now - 2 * DAY) }, state, usedBy });
    return {
      generatedAt: iso(this.now), range: '19000-19999',
      allocations: [
        a(19001, 'SHOP-671', 'app', 'in-use', 'container shop-671-app-1'), a(19002, 'SHOP-671', 'postgres', 'in-use', 'container shop-671-postgres-1'),
        a(19005, 'SHOP-664', 'app', 'free', null), a(19010, 'SHOP-672', 'app', 'conflict', 'process 4321 (java)'), a(19020, 'CL-43', 'daemon', 'in-use', 'container cl-43-daemon-1', 'CodeLoupe'),
      ],
      foreign: [{ port: 19003, usedBy: 'container other-db-1' }], problems: [],
    };
  }
}
