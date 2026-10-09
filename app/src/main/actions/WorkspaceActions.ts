import type { ActionOutcome, ReconcileOutcome, ReconcileRequest, ReleaseRequest } from '../../shared/actions';
import type { PlanEntry, ReconcilePlan, ReconcileAction, WorkspaceList } from '../../shared/workspaces';

/** The daemon calls the actions need. */
export interface WorkspaceDaemon {
  get<T>(path: string): Promise<T>;
  post<T>(path: string, body: unknown): Promise<T>;
}

/** Asks the user in a native dialog the page cannot click; true when they accepted. */
export type Confirm = (question: { message: string; detail: string; accept: string }) => Promise<boolean>;

const KEY = /^(container|network|volume|image|directory):.{1,500}$/;
const MAX_KEYS = 200;
const PLAN_HASH = /^[A-Za-z0-9]{8,64}$/;
const SHOWN = 12;

/** `3 resources`: the noun agrees with the number. */
const resources = (n: number) => `${n} ${n === 1 ? 'resource' : 'resources'}`;

const text = (v: unknown, max = 600): string | null => (typeof v === 'string' && v.length > 0 && v.length <= max ? v : null);

/**
 * The two actions of the Workspaces screen. The page names what it wants; main reads the daemon's own registry and plan,
 * checks the request against them, shows the user exactly what would be removed, and only then asks the daemon.
 */
export class WorkspaceActions {
  constructor(private readonly daemon: WorkspaceDaemon, private readonly confirm: Confirm) {}

  /** `POST /workspaces/release`: the cleanup of everything a worktree's Docker stack left, started in the background. */
  async release(input: unknown): Promise<ActionOutcome> {
    const req = input as Partial<ReleaseRequest> | null;
    const repo = text(req?.repo);
    const path = text(req?.path);
    if (!repo || !path) return refused('Incomplete request.');

    const registry = await this.daemon.get<WorkspaceList>('/workspaces');
    const found = registry.repos.find(r => r.repo === repo)?.workspaces.find(w => w.path === path);
    if (!found || found.role !== 'worktree') return refused('Only a worktree the daemon knows can be released (not the main worktree or foreign directories).');

    const plan = await this.daemon.get<ReconcilePlan>('/reconcile');
    const own = plan.entries.filter(e => e.workspace?.toLowerCase() === found.name.toLowerCase() && sameRepo(e, registry, repo));
    const stack = own.filter(e => e.verdict !== 'protected' && e.kind !== 'directory');
    const ok = await this.confirm({
      message: `Release workspace ${found.name}?`,
      detail: [
        stack.length ? `The daemon removes its Docker resources (${stack.length}):` : 'The workspace has no Docker resources; it is only marked as released.',
        ...listing(stack),
        '',
        'The worktree directory, branch and task do not change. Removal is retried while a resource is in use.',
      ].join('\n'),
      accept: 'Release',
    });
    if (!ok) return { ok: false, message: 'Cancelled.' };
    await this.daemon.post('/workspaces/release', { target: found.path, repo });
    return { ok: true, message: stack.length ? `Workspace ${found.name} released, cleanup (${resources(stack.length)}) runs in the background.` : `Workspace ${found.name} released.` };
  }

  /** `POST /reconcile/run` with the entries the user confirmed; the daemon re-reads its plan and refuses what is no longer removable. */
  async reconcile(input: unknown): Promise<ReconcileOutcome> {
    const req = input as Partial<ReconcileRequest> | null;
    const keys = Array.isArray(req?.keys) ? req.keys : null;
    if (!keys || keys.length === 0 || keys.length > MAX_KEYS || !keys.every(k => typeof k === 'string' && KEY.test(k))) return { ...refused('Invalid selection.'), results: [] };

    const plan = await this.daemon.get<ReconcilePlan>('/reconcile');
    // The hash of the plan the screen showed must still be the daemon's: the person clicked on a list that is no longer the plan otherwise.
    if (req?.planHash !== undefined && (typeof req.planHash !== 'string' || !PLAN_HASH.test(req.planHash))) return { ...refused('Invalid selection.'), results: [] };
    if (req?.planHash !== undefined && req.planHash !== plan.planHash) return { ...refused('The cleanup plan has changed in the meantime; refresh the screen.'), results: [] };
    const wanted = [...new Set(keys)].map(k => plan.entries.find(e => e.key === k));
    if (wanted.some(e => !e)) return { ...refused('The cleanup plan has changed in the meantime; refresh the screen.'), results: [] };
    const entries = wanted as PlanEntry[];
    const notConfirmable = entries.find(e => e.verdict !== 'confirm');
    if (notConfirmable) return { ...refused(`“${notConfirmable.name}” cannot be removed by confirmation (${notConfirmable.verdict}).`), results: [] };

    const ok = await this.confirm({
      message: entries.length === 1 ? 'Remove this resource?' : `Remove ${resources(entries.length)}?`,
      detail: [...listing(entries), '', 'Irreversible: containers, volumes and directories are deleted with their data. Anything in use right now is not deleted and is retried later.'].join('\n'),
      accept: 'Remove',
    });
    if (!ok) return { ok: false, message: 'Cancelled.', results: [] };

    // The dialog listed this plan: the daemon removes nothing if its plan is another one by the time the call arrives.
    let run: { actions: ReconcileAction[] };
    try {
      run = await this.daemon.post<{ actions: ReconcileAction[] }>('/reconcile/run', { confirm: entries.map(e => e.key), auto: false, planHash: plan.planHash });
    } catch (e) {
      if ((e as { status?: number }).status === 409) return { ...refused('The cleanup plan changed while the dialog was open; nothing was removed. Refresh the screen.'), results: [] };
      throw e;
    }
    const named = new Set(entries.map(e => e.key));
    const results = run.actions.filter(a => named.has(a.key));
    const count = (o: ReconcileAction['outcome']) => results.filter(a => a.outcome === o).length;
    const gone = count('removed') + count('gone');
    const left = results.length - gone;
    return {
      ok: left === 0,
      message: left === 0 ? `Removed ${gone}.` : `Removed ${gone}, ${left} left (in use or failed; the daemon will retry).`,
      results,
    };
  }
}

function refused(message: string): ActionOutcome {
  return { ok: false, message };
}

function sameRepo(entry: PlanEntry, registry: WorkspaceList, repoPath: string): boolean {
  const name = registry.repos.find(r => r.repo === repoPath)?.name;
  return !!name && entry.repo?.toLowerCase() === name.toLowerCase();
}

function listing(entries: PlanEntry[]): string[] {
  const lines = entries.slice(0, SHOWN).map(e => `  ${e.kind}  ${e.name}${e.workspace ? `  (${e.workspace})` : ''}`);
  return entries.length > SHOWN ? [...lines, `  … and ${entries.length - SHOWN} more`] : lines;
}
