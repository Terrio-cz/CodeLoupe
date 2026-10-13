// Actions the page may ask main to do: the page only asks, main validates against the daemon's own data and does it
// (confirming first in a native dialog where something is changed or removed). Reads go through `api`, never through here.
import type { ReconcileAction } from './workspaces';

export const ACTION_CH = {
  workspaceRelease: 'cl:action:workspace-release',
  reconcileRun: 'cl:action:reconcile-run',
  slotCapacity: 'cl:action:slot-capacity',
  jobMove: 'cl:action:job-move',
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
  /** `planHash` of the plan the screen showed; main refuses the request when the daemon's plan has moved on. */
  planHash?: string;
}

export interface ReconcileOutcome extends ActionOutcome {
  results: ReconcileAction[];
}

/** A slot of the daemon (`jobs.slots` of `GET /status`) and the capacity it should have from now on (1 to {@link MAX_SLOT_CAPACITY}). */
export interface SlotCapacityRequest {
  name: string;
  capacity: number;
}

/** The daemon's own limit (`JobRunner.MAX_CAPACITY`). */
export const MAX_SLOT_CAPACITY = 64;

/** Where a waiting job goes in its slot's queue: to the front, or right before / after another waiting job of the same slot. */
export interface JobMoveRequest {
  id: string;
  to: 'first' | 'before' | 'after';
  /** The other waiting job for `before` / `after`. */
  target?: string;
}

export interface ActionsBridge {
  /** Releases a worktree: after a native confirmation that lists them, the daemon removes its Docker resources. */
  workspaceRelease(req: ReleaseRequest): Promise<ActionOutcome>;
  /** Removes the plan entries after a native confirmation that lists them. */
  reconcileRun(req: ReconcileRequest): Promise<ReconcileOutcome>;
  /** Sets a slot's capacity: stored in the daemon's `config.json` and in force at once; a raise starts waiting jobs, a cut ends nobody. */
  slotCapacity(req: SlotCapacityRequest): Promise<ActionOutcome>;
  /** Moves a waiting job within its slot's queue. Running jobs do not move. */
  jobMove(req: JobMoveRequest): Promise<ActionOutcome>;
}
