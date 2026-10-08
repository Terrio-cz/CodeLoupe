// Actions the page may ask main to do: the page only asks, main validates against the daemon's own data and does it
// (confirming first in a native dialog where something is changed or removed). Reads go through `api`, never through here.
import type { ReconcileAction } from './workspaces';

export const ACTION_CH = {
  gapsRefresh: 'cl:action:gaps-refresh',
  workspaceRelease: 'cl:action:workspace-release',
  reconcileRun: 'cl:action:reconcile-run',
} as const;

/** The result of an action in one line the page shows as it is. */
export interface ActionOutcome {
  ok: boolean;
  message: string;
}

/** A worktree of the registry: the main worktree of its repository, and its own directory, as `GET /workspaces` lists them. */
export interface ReleaseRequest {
  repo: string;
  path: string;
}

/** Plan entries (`PlanEntry.key`) the user confirmed for removal. */
export interface ReconcileRequest {
  keys: string[];
}

export interface ReconcileOutcome extends ActionOutcome {
  results: ReconcileAction[];
}

export interface ActionsBridge {
  /** Runs `codeloupe metrics gaps` over the transcripts of the last 30 days and stores the report for the Gaps screen. */
  gapsRefresh(): Promise<ActionOutcome>;
  /** Releases a worktree: after a native confirmation that lists them, the daemon removes its Docker resources. */
  workspaceRelease(req: ReleaseRequest): Promise<ActionOutcome>;
  /** Removes the plan entries after a native confirmation that lists them. */
  reconcileRun(req: ReconcileRequest): Promise<ReconcileOutcome>;
}
