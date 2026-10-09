// What the daemon answers for the workspace registry, its Docker inventory and the cleanup plan
// (`GET /workspaces`, `/resources`, `/reconcile`, `/workspaces/releases`, `/ports`; src/main/kotlin/codeloupe/{workspace,docker,reconcile,ports}).
// Read-only here; what changes something goes through the actions of actions.ts.

import type { Iso } from './contract';

export type WorkspaceState = 'active' | 'landed' | 'abandoned' | 'orphan';
export const WORKSPACE_STATES: readonly WorkspaceState[] = ['active', 'landed', 'abandoned', 'orphan'];

/** One directory of the registry: a worktree git registered, or a directory under a worktree root it did not. */
export interface Workspace {
  path: string;
  name: string;
  role: 'main' | 'worktree' | 'directory';
  state: WorkspaceState;
  /** Why the state is not plain: what an orphan lacks, a resolved task whose branch is not merged. */
  note: string | null;
  branch: string | null;
  head: string | null;
  taskId: string | null;
  merge: { defaultRef: string; ahead: number; merged: boolean; subject: string | null } | null;
  tracker: { state: string | null; resolved: boolean; summary: string } | null;
  lastActivity: Iso | null;
  /** Only with `size=1`. */
  sizeBytes: number | null;
}

export interface RepoWorkspaces {
  /** The main worktree. */
  repo: string;
  name: string;
  commonDir: string;
  defaultRef: string;
  roots: string[];
  counts: Record<string, number>;
  workspaces: Workspace[];
}

export interface WorkspaceList {
  generatedAt: Iso;
  repos: RepoWorkspaces[];
  problems: string[];
}

export type OwnershipClass = 'owned' | 'adopted' | 'unowned';
export type ResourceKind = 'container' | 'image' | 'volume' | 'network';

export interface DockerResource {
  kind: ResourceKind;
  id: string;
  names: string[];
  ownership: OwnershipClass;
  repo: string | null;
  workspace: string | null;
  task: string | null;
  via: string | null;
  workspaceState: WorkspaceState | null;
  state: string | null;
  created: string | null;
  project: string | null;
  publishedPorts: number[];
  /** Running containers of a workspace, only with `stats=1`. */
  memoryBytes: number | null;
}

export interface ResourceReport {
  generatedAt: Iso;
  /** The Engine endpoint and version; null when Docker could not be reached. */
  engine: string | null;
  counts: Record<string, number>;
  resources: DockerResource[];
  problems: string[];
}

export type Verdict = 'auto' | 'confirm' | 'keep' | 'protected';
export type TargetKind = 'container' | 'network' | 'volume' | 'image' | 'directory';

export interface PlanEntry {
  /** `<kind>:<id>` (volumes by name, directories by path): what a confirmation names. */
  key: string;
  kind: TargetKind;
  name: string;
  repo: string | null;
  workspace: string | null;
  ownership: OwnershipClass | null;
  workspaceState: WorkspaceState | null;
  verdict: Verdict;
  reason: string;
  /** The workspace was released: this entry goes without asking. */
  released: boolean;
  attempts: number;
  nextAttempt: Iso | null;
  lastError: string | null;
}

export interface ReconcilePlan {
  generatedAt: Iso;
  /** Whether the reconciler cleans on its own (`workspaces.reconcile.auto`). */
  auto: boolean;
  counts: Record<string, number>;
  entries: PlanEntry[];
  problems: string[];
  /** Fingerprint of the entries; a confirm sends the one of the plan the person saw (`POST /reconcile/run`). */
  planHash: string;
}

export interface ReleaseStatus {
  repo: string;
  workspace: string;
  at: Iso;
  /** Resources of it still found at the last look; null before the reconciler has looked. */
  pending: number | null;
  /** Of those, the ones whose removal failed or is blocked and waits for a retry. */
  retrying: number | null;
}

export type ActionOutcomeKind = 'removed' | 'gone' | 'blocked' | 'failed' | 'skipped';
export interface ReconcileAction {
  key: string;
  kind: TargetKind;
  name: string;
  workspace: string | null;
  outcome: ActionOutcomeKind;
  detail: string;
}

export interface PortStatus {
  allocation: { port: number; repo: string; workspace: string; name: string; at: Iso };
  state: 'free' | 'in-use' | 'conflict';
  usedBy: string | null;
}
export interface PortReport {
  generatedAt: Iso;
  range: string | null;
  allocations: PortStatus[];
  foreign: { port: number; usedBy: string }[];
  problems: string[];
}
