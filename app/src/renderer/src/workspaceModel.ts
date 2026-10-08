import type { DockerResource, PlanEntry, PortStatus, ReleaseStatus, Workspace, WorkspaceList, WorkspaceState } from '../../shared/workspaces';
import type { PortReport, ReconcilePlan, ResourceReport } from '../../shared/workspaces';

/** A workspace of the registry with everything the daemon knows about it, joined by repository and workspace name. */
export interface WorkspaceRow {
  /** `<repo name>/<workspace name>`: the route id of the detail. */
  id: string;
  repoName: string;
  repoPath: string;
  /** Null for a workspace that left the registry but still has Docker resources. */
  ws: Workspace | null;
  name: string;
  state: WorkspaceState | 'gone';
  resources: DockerResource[];
  plan: PlanEntry[];
  release: ReleaseStatus | null;
  ports: PortStatus[];
}

export type StateFilter = WorkspaceState | 'gone' | 'released' | '';

const norm = (s: string | null | undefined) => (s ?? '').toLowerCase();
const pairKey = (repo: string | null | undefined, workspace: string | null | undefined) => `${norm(repo)}/${norm(workspace)}`;

export function joinWorkspaces(
  list: WorkspaceList,
  resources: ResourceReport | null,
  plan: ReconcilePlan | null,
  releases: ReleaseStatus[],
  ports: PortReport | null,
): WorkspaceRow[] {
  const rows = new Map<string, WorkspaceRow>();
  for (const repo of list.repos) {
    for (const ws of repo.workspaces) {
      const k = pairKey(repo.name, ws.name);
      rows.set(k, { id: `${repo.name}/${ws.name}`, repoName: repo.name, repoPath: repo.repo, ws, name: ws.name, state: ws.state, resources: [], plan: [], release: null, ports: [] });
    }
  }
  const repoPath = new Map(list.repos.map(r => [norm(r.name), r]));
  // What the registry no longer lists still has its resources: a row of its own, so nothing is hidden.
  const rowOf = (repo: string | null, workspace: string | null): WorkspaceRow | null => {
    if (!repo || !workspace) return null;
    const k = pairKey(repo, workspace);
    const known = rows.get(k);
    if (known) return known;
    const r = repoPath.get(norm(repo));
    if (!r) return null;
    const gone: WorkspaceRow = { id: `${r.name}/${workspace}`, repoName: r.name, repoPath: r.repo, ws: null, name: workspace, state: 'gone', resources: [], plan: [], release: null, ports: [] };
    rows.set(k, gone);
    return gone;
  };
  for (const r of resources?.resources ?? []) if (r.ownership !== 'unowned') rowOf(r.repo, r.workspace)?.resources.push(r);
  for (const e of plan?.entries ?? []) rowOf(e.repo, e.workspace)?.plan.push(e);
  for (const rel of releases) {
    const row = rows.get(pairKey(rel.repo, rel.workspace));
    if (row) row.release = rel;
  }
  for (const p of ports?.allocations ?? []) rows.get(pairKey(p.allocation.repo, p.allocation.workspace))?.ports.push(p);
  return [...rows.values()];
}

export const confirmable = (row: WorkspaceRow): PlanEntry[] => row.plan.filter(e => e.verdict === 'confirm');

export function matches(row: WorkspaceRow, f: { repo: string; state: StateFilter; q: string }): boolean {
  if (f.repo && row.repoName !== f.repo) return false;
  if (f.state === 'released') { if (!row.release) return false; }
  else if (f.state && row.state !== f.state) return false;
  const q = f.q.trim().toLowerCase();
  return !q || `${row.name} ${row.ws?.branch ?? ''} ${row.ws?.taskId ?? ''} ${row.ws?.tracker?.summary ?? ''}`.toLowerCase().includes(q);
}

export interface DockerCounts {
  containers: number;
  volumes: number;
  images: number;
  networks: number;
  running: number;
  /** Bytes of memory of the running containers, null while it was not asked for. */
  memoryBytes: number | null;
}

export function dockerCounts(row: WorkspaceRow): DockerCounts {
  const c: DockerCounts = { containers: 0, volumes: 0, images: 0, networks: 0, running: 0, memoryBytes: null };
  for (const r of row.resources) {
    if (r.kind === 'container') {
      c.containers++;
      if (r.state === 'running') c.running++;
      if (r.memoryBytes !== null) c.memoryBytes = (c.memoryBytes ?? 0) + r.memoryBytes;
    }
    else if (r.kind === 'volume') c.volumes++;
    else if (r.kind === 'image') c.images++;
    else c.networks++;
  }
  return c;
}

/** How many workspaces are in each state, for the tiles; the registry's own counts do not know the vanished ones. */
export function stateCounts(rows: WorkspaceRow[]): Record<WorkspaceState | 'gone', number> {
  const out = { active: 0, landed: 0, abandoned: 0, orphan: 0, gone: 0 };
  for (const r of rows) out[r.state]++;
  return out;
}
