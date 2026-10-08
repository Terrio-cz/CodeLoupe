import type { OnboardingQuery, RepositoriesAdded } from '../../shared/onboardingActions';
import type { RunCli } from '../env/EnvManager';

export interface OnboardingDeps {
  run: RunCli;
  /** The native folder dialog (several folders); empty when the user cancelled. */
  pickFolders(): Promise<string[]>;
  /** The repositories the daemon knows, from its settings. */
  repos(): Promise<{ id: string; path: string }[]>;
  /** Why nothing may be changed right now (the screen shows mock data), or null. */
  blocked?: () => string | null;
}

const MAX_FOLDERS = 50;
const MAX_TEXT = 20_000;
const REPO_ID = /^[A-Za-z0-9._:-]{1,100}$/;
const ADD_TIMEOUT_MS = 5 * 60_000;
const QUERY_TIMEOUT_MS = 2 * 60_000;

const hasControl = (s: string): boolean => [...s].some(c => c.charCodeAt(0) < 32);

const none = (message: string): RepositoriesAdded => ({ ok: false, message, added: [], already: [], rejected: [] });

/** What the first-run flow does through the CLI: put repositories into the daemon's configuration, ask the daemon something. */
export class OnboardingManager {
  constructor(private readonly deps: OnboardingDeps) {}

  async addRepositories(): Promise<RepositoriesAdded | 'cancelled'> {
    const blocked = this.deps.blocked?.();
    if (blocked) return none(blocked);
    const picked = (await this.deps.pickFolders()).filter(p => typeof p === 'string' && p.length > 0 && p.length <= 500 && !hasControl(p));
    if (picked.length === 0) return 'cancelled';
    if (picked.length > MAX_FOLDERS) return none(`Vyberte nejvýše ${MAX_FOLDERS} složek najednou.`);
    try {
      const r = await this.deps.run(['repos', 'add', '--json', ...picked], null, ADD_TIMEOUT_MS);
      const report = parse(r.stdout);
      if (!report) return none(`Repozitáře se nepodařilo přidat: ${lastLine(r.stderr || r.stdout)}`);
      const done = report.added.length + report.already.length;
      return {
        ok: done > 0,
        message: done > 0
          ? `Přidáno ${report.added.length}, už bylo ${report.already.length}${report.rejected.length ? `, odmítnuto ${report.rejected.length}` : ''}. Index se staví na pozadí.`
          : 'Žádná z vybraných složek není git repozitář.',
        ...report,
      };
    } catch (e) {
      return none(`Repozitáře se nepodařilo přidat: ${lastLine((e as Error).message)}`);
    }
  }

  async query(repoId: string): Promise<OnboardingQuery> {
    const blocked = this.deps.blocked?.();
    if (blocked) return { ok: false, text: blocked };
    if (typeof repoId !== 'string' || !REPO_ID.test(repoId)) return { ok: false, text: 'Neplatné ID repozitáře.' };
    const repo = (await this.deps.repos().catch(() => [])).find(r => r.id === repoId);
    if (!repo) return { ok: false, text: 'Daemon tento repozitář nezná; přidejte ho v prvním kroku.' };
    try {
      const r = await this.deps.run(['outline', '--root', repo.path, '--budget', '600'], null, QUERY_TIMEOUT_MS);
      return { ok: r.code === 0, text: (r.stdout.trim() || r.stderr.trim() || 'Prázdná odpověď.').slice(0, MAX_TEXT) };
    } catch (e) {
      return { ok: false, text: `Dotaz selhal: ${lastLine((e as Error).message)}` };
    }
  }
}

function parse(text: string): { added: string[]; already: string[]; rejected: { path: string; reason: string }[] } | null {
  try {
    const o = JSON.parse(text) as { added?: unknown; already?: unknown; rejected?: unknown };
    const strings = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);
    const rejected = Array.isArray(o.rejected) ? o.rejected.filter((x): x is { path: string; reason: string } => !!x && typeof (x as { path?: unknown }).path === 'string') : [];
    return { added: strings(o.added), already: strings(o.already), rejected };
  } catch {
    return null;
  }
}

function lastLine(text: string): string {
  return text.trim().split(/\r?\n/).filter(Boolean).slice(-1)[0] ?? 'bez zprávy';
}
