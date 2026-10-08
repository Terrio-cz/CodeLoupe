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
const SHOWN = 12;

/** `3 prostředky`: the noun agrees with the number in Czech. */
const resources = (n: number) => `${n} ${n === 1 ? 'prostředek' : n >= 2 && n <= 4 ? 'prostředky' : 'prostředků'}`;

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
    if (!repo || !path) return refused('Neúplný požadavek.');

    const registry = await this.daemon.get<WorkspaceList>('/workspaces');
    const found = registry.repos.find(r => r.repo === repo)?.workspaces.find(w => w.path === path);
    if (!found || found.role !== 'worktree') return refused('Uvolnit jde jen worktree, který daemon zná (hlavní worktree a cizí adresáře ne).');

    const plan = await this.daemon.get<ReconcilePlan>('/reconcile');
    const own = plan.entries.filter(e => e.workspace?.toLowerCase() === found.name.toLowerCase() && sameRepo(e, registry, repo));
    const stack = own.filter(e => e.verdict !== 'protected' && e.kind !== 'directory');
    const ok = await this.confirm({
      message: `Uvolnit workspace ${found.name}?`,
      detail: [
        stack.length ? `Daemon odstraní jeho Docker prostředky (${stack.length}):` : 'Workspace nemá žádné Docker prostředky; jen se označí jako uvolněný.',
        ...listing(stack),
        '',
        'Adresář worktree, větev ani úkol se nemění. Odstranění se opakuje, dokud je prostředek používaný.',
      ].join('\n'),
      accept: 'Uvolnit',
    });
    if (!ok) return { ok: false, message: 'Zrušeno.' };
    await this.daemon.post('/workspaces/release', { target: found.path, repo });
    return { ok: true, message: stack.length ? `Workspace ${found.name} uvolněn, úklid (${resources(stack.length)}) běží na pozadí.` : `Workspace ${found.name} uvolněn.` };
  }

  /** `POST /reconcile/run` with the entries the user confirmed; the daemon re-reads its plan and refuses what is no longer removable. */
  async reconcile(input: unknown): Promise<ReconcileOutcome> {
    const req = input as Partial<ReconcileRequest> | null;
    const keys = Array.isArray(req?.keys) ? req.keys : null;
    if (!keys || keys.length === 0 || keys.length > MAX_KEYS || !keys.every(k => typeof k === 'string' && KEY.test(k))) return { ...refused('Neplatný výběr.'), results: [] };

    const plan = await this.daemon.get<ReconcilePlan>('/reconcile');
    const wanted = [...new Set(keys)].map(k => plan.entries.find(e => e.key === k));
    if (wanted.some(e => !e)) return { ...refused('Plán úklidu se mezitím změnil; obnovte obrazovku.'), results: [] };
    const entries = wanted as PlanEntry[];
    const notConfirmable = entries.find(e => e.verdict !== 'confirm');
    if (notConfirmable) return { ...refused(`„${notConfirmable.name}“ se potvrzením odstranit nedá (${notConfirmable.verdict}).`), results: [] };

    const ok = await this.confirm({
      message: entries.length === 1 ? 'Odstranit tento prostředek?' : `Odstranit ${resources(entries.length)}?`,
      detail: [...listing(entries), '', 'Nevratné: kontejnery, volumes a adresáře se smažou i s daty. Co je právě používané, se nesmaže a zkusí se znovu později.'].join('\n'),
      accept: 'Odstranit',
    });
    if (!ok) return { ok: false, message: 'Zrušeno.', results: [] };

    const run = await this.daemon.post<{ actions: ReconcileAction[] }>('/reconcile/run', { confirm: entries.map(e => e.key) });
    const named = new Set(entries.map(e => e.key));
    const results = run.actions.filter(a => named.has(a.key));
    const count = (o: ReconcileAction['outcome']) => results.filter(a => a.outcome === o).length;
    const gone = count('removed') + count('gone');
    const left = results.length - gone;
    return {
      ok: left === 0,
      message: left === 0 ? `Odstraněno ${gone}.` : `Odstraněno ${gone}, ${left} zůstává (používané nebo selhalo; daemon to zkusí znovu).`,
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
  return entries.length > SHOWN ? [...lines, `  … a dalších ${entries.length - SHOWN}`] : lines;
}
