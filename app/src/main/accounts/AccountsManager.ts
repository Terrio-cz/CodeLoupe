import fs from 'node:fs';
import path from 'node:path';
import type { AccountOutcome, ClaudeAccountInput, YoutrackAccountInput } from '../../shared/accountActions';
import type { RunCli } from '../env/EnvManager';
import { scrub } from '../env/envValidation';
import { readAccounts, writeAccounts, type AccountsData, type ClaudeEntry, type YoutrackEntry } from './accountsFile';

export interface AccountsDeps {
  homeDir(): string;
  /** The user's home directory, where Claude Code keeps `~/.claude` when no config directory is named. */
  userHome(): string;
  run: RunCli;
  /** A native dialog the page cannot click; true = the user agreed. */
  confirm(message: string, detail: string, okLabel: string): Promise<boolean>;
  /** The stored value of a global key, read from the daemon on behalf of the app (an audited read); null when it is not there. */
  fetchStored(name: string): Promise<string | null>;
  /** One GET with headers; the body only as far as the caller needs it. */
  http(url: string, headers: Record<string, string>): Promise<{ status: number; body: string }>;
  restartDaemon(): Promise<void>;
  /** Why nothing may be changed right now (the screen shows mock data), or null. */
  blocked?: () => string | null;
}

const LABEL_MAX = 60;
const PROJECT = /^[A-Za-z][A-Za-z0-9]{0,19}$/;

const hasControl = (s: string): boolean => [...s].some(c => c.charCodeAt(0) < 32);
const fail = (message: string): AccountOutcome => ({ ok: false, message });
const ok = (message: string): AccountOutcome => ({ ok: true, message });

/** `YOUTRACK_TOKEN_TERRIO`: where the token of an account lives in the encrypted store. */
export const tokenName = (id: string): string => `YOUTRACK_TOKEN_${id.toUpperCase().replace(/[^A-Z0-9]/g, '_')}`;

function slug(text: string, fallback: string, taken: Set<string>): string {
  const base = text.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 30).replace(/-+$/g, '') || fallback;
  let id = base;
  for (let n = 2; taken.has(id); n++) id = `${base}-${n}`;
  return id;
}

/** A plain https URL (or http to this machine, for a test instance) without credentials, as the origin only. */
export function checkUrl(input: unknown): { ok: true; url: string } | { ok: false; error: string } {
  if (typeof input !== 'string' || input.length > 300 || hasControl(input)) return { ok: false, error: 'adresa není platná' };
  let u: URL;
  try { u = new URL(input.trim()); } catch { return { ok: false, error: 'adresa není platná' }; }
  const local = u.hostname === 'localhost' || u.hostname === '127.0.0.1';
  if (u.protocol !== 'https:' && !(u.protocol === 'http:' && local)) return { ok: false, error: 'adresa musí začínat https://' };
  if (u.username || u.password) return { ok: false, error: 'adresa nesmí obsahovat přihlašovací údaje' };
  return { ok: true, url: `${u.protocol}//${u.host}${u.pathname.replace(/\/+$/, '')}` };
}

/**
 * The accounts of the Accounts screen. Claude accounts and YouTrack instances are written to `accounts.json` (no secret); a
 * YouTrack token goes to the encrypted store through the CLI on its stdin and is read back only for the connection test, in
 * this process, to send it to that instance.
 */
export class AccountsManager {
  constructor(private readonly deps: AccountsDeps) {}

  async claudeAdd(input: ClaudeAccountInput): Promise<AccountOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return fail(blocked);
    const label = this.label(input?.label);
    if (typeof label !== 'string') return label;
    const dir = input?.configDir;
    if (typeof dir !== 'string' || !dir.trim() || dir.length > 400 || hasControl(dir) || !path.isAbsolute(dir.trim())) return fail('Složka účtu musí být zadaná plnou cestou.');
    const configDir = path.normalize(dir.trim());
    if (!fs.existsSync(configDir)) {
      if (input.create !== true) return fail('Složka neexistuje; zaškrtněte vytvoření, nebo zadejte existující.');
      try { fs.mkdirSync(configDir, { recursive: true }); } catch (e) { return fail(`Složku nelze vytvořit: ${(e as NodeJS.ErrnoException).code ?? 'chyba'}.`); }
    } else if (!fs.statSync(configDir).isDirectory()) return fail('Zadaná cesta není složka.');
    const data = this.load();
    // With nothing listed, `~/.claude` is the account Claude Code uses; it becomes a real entry before another one joins it.
    if (data.claude.length === 0) data.claude.push({ id: 'default', label: 'Výchozí účet', configDir: path.join(this.deps.userHome(), '.claude'), default: true });
    if (data.claude.some(c => same(c.configDir, configDir))) return fail('Účet s touto složkou už existuje.');
    const id = slug(label, 'ucet', new Set(data.claude.map(c => c.id)));
    data.claude.push({ id, label, configDir, default: false });
    writeAccounts(this.deps.homeDir(), this.withDefault(data));
    return ok(`Účet ${label} přidán.`);
  }

  async claudeRename(id: string, label: string): Promise<AccountOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return fail(blocked);
    const clean = this.label(label);
    if (typeof clean !== 'string') return clean;
    const data = this.load();
    const entry = data.claude.find(c => c.id === id);
    if (!entry) return fail('Účet neexistuje.');
    entry.label = clean;
    writeAccounts(this.deps.homeDir(), data);
    return ok('Účet přejmenován.');
  }

  async claudeSetDefault(id: string): Promise<AccountOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return fail(blocked);
    const data = this.load();
    if (!data.claude.some(c => c.id === id)) return fail('Účet neexistuje.');
    for (const c of data.claude) c.default = c.id === id;
    writeAccounts(this.deps.homeDir(), data);
    return ok('Výchozí účet nastaven.');
  }

  async claudeRemove(id: string): Promise<AccountOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return fail(blocked);
    const data = this.load();
    const entry = data.claude.find(c => c.id === id);
    if (!entry) return fail('Účet neexistuje.');
    if (!(await this.deps.confirm(`Odebrat účet ${entry.label}?`, `Z CodeLoupe zmizí jen záznam o účtu. Složka ${entry.configDir} a její přihlášení zůstanou.`, 'Odebrat'))) return fail('Odebrání zrušeno.');
    data.claude = data.claude.filter(c => c.id !== id);
    writeAccounts(this.deps.homeDir(), this.withDefault(data));
    return ok(`Účet ${entry.label} odebrán.`);
  }

  async youtrackAdd(input: YoutrackAccountInput): Promise<AccountOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return fail(blocked);
    const token = input?.token;
    try {
      const label = this.label(input?.label);
      if (typeof label !== 'string') return label;
      const url = checkUrl(input?.url);
      if (!url.ok) return fail(`Účet nelze přidat: ${url.error}.`);
      const projects = this.projects(input?.projects);
      if (typeof projects === 'string') return fail(projects);
      const bad = this.tokenProblem(token);
      if (bad) return fail(bad);
      const data = this.load();
      if (data.youtrack.some(y => y.url.toLowerCase() === url.url.toLowerCase())) return fail('Účet pro tuto instanci už existuje.');
      const id = slug(label, 'youtrack', new Set(data.youtrack.map(y => y.id)));
      if (!(await this.deps.confirm('Přidat účet YouTrack?', `${url.url}\nProjekty: ${projects.join(', ')}\nDaemon se kvůli novému mirroru restartuje; běžící joby se přeruší.`, 'Přidat a restartovat'))) return fail('Přidání zrušeno.');
      const stored = await this.storeToken(tokenName(id), token as string);
      if (!stored.ok) return stored;
      data.youtrack.push({ id, label, url: url.url, projects, token: tokenName(id) });
      writeAccounts(this.deps.homeDir(), data);
      await this.deps.restartDaemon();
      return ok(`Účet ${label} přidán, daemon restartován.`);
    } catch (e) {
      return fail(`Účet se nepodařilo přidat: ${firstLine(scrub((e as Error).message, String(token ?? '')))}`);
    }
  }

  async youtrackTest(id: string): Promise<AccountOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return fail(blocked);
    const entry = this.load().youtrack.find(y => y.id === id);
    if (!entry) return fail('Účet neexistuje.');
    const token = await this.deps.fetchStored(entry.token).catch(() => null);
    if (!token) return fail('Token účtu není v úložišti; zadejte ho přes Rotovat token.');
    try {
      const r = await this.deps.http(`${entry.url}/api/users/me?fields=login`, { Authorization: `Bearer ${token}`, Accept: 'application/json' });
      if (r.status === 401 || r.status === 403) return fail('Instance token odmítla (neplatný nebo bez oprávnění).');
      if (r.status < 200 || r.status >= 300) return fail(`Instance odpověděla stavem ${r.status}.`);
      const login = (JSON.parse(r.body) as { login?: unknown }).login;
      return ok(typeof login === 'string' && login ? `Připojeno jako ${login}.` : 'Připojeno.');
    } catch (e) {
      return fail(`Spojení selhalo: ${firstLine(scrub((e as Error).message, token))}`);
    }
  }

  async youtrackRotate(id: string, token: string): Promise<AccountOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return fail(blocked);
    const bad = this.tokenProblem(token);
    if (bad) return fail(bad);
    const entry = this.load().youtrack.find(y => y.id === id);
    if (!entry) return fail('Účet neexistuje.');
    const stored = await this.storeToken(entry.token, token);
    return stored.ok ? ok(`Token účtu ${entry.label} rotován.`) : stored;
  }

  async youtrackRemove(id: string): Promise<AccountOutcome> {
    const blocked = this.deps.blocked?.();
    if (blocked) return fail(blocked);
    const data = this.load();
    const entry = data.youtrack.find(y => y.id === id);
    if (!entry) return fail('Účet neexistuje.');
    if (!(await this.deps.confirm(`Odebrat účet ${entry.label}?`, `${entry.url}\nToken se smaže z úložiště, mirror úkolů se přestane aktualizovat a daemon se restartuje.`, 'Odebrat a restartovat'))) return fail('Odebrání zrušeno.');
    data.youtrack = data.youtrack.filter(y => y.id !== id);
    writeAccounts(this.deps.homeDir(), data);
    try {
      await this.deps.run(['env', 'unset', entry.token, '--scope', 'global'], null, 60_000);
      await this.deps.restartDaemon();
    } catch (e) {
      return fail(`Účet je odebrán, ale úklid selhal: ${firstLine((e as Error).message)}`);
    }
    return ok(`Účet ${entry.label} odebrán, daemon restartován.`);
  }

  private async storeToken(name: string, token: string): Promise<AccountOutcome> {
    try {
      const r = await this.deps.run(['env', 'set', name, '--scope', 'global', '--source', 'app'], `${token}\n`, 60_000);
      return r.code === 0 ? ok('uloženo') : fail(`Token se nepodařilo uložit: ${firstLine(scrub(r.stderr || r.stdout, token))}`);
    } catch (e) {
      return fail(`Token se nepodařilo uložit: ${firstLine(scrub((e as Error).message, token))}`);
    }
  }

  private tokenProblem(token: unknown): string | null {
    if (typeof token !== 'string' || token.length === 0) return 'Zadejte token.';
    if (token.length > 4096 || hasControl(token)) return 'Token má neplatný tvar.';
    return null;
  }

  private projects(input: unknown): string[] | string {
    if (!Array.isArray(input) || input.length === 0 || input.length > 50) return 'Zadejte aspoň jeden projekt (zkratku, např. TER).';
    const out: string[] = [];
    for (const p of input) {
      if (typeof p !== 'string' || !PROJECT.test(p.trim())) return `Projekt „${typeof p === 'string' ? p.slice(0, 20) : '?'}“ není platná zkratka.`;
      if (!out.includes(p.trim().toUpperCase())) out.push(p.trim().toUpperCase());
    }
    return out;
  }

  private label(input: unknown): string | AccountOutcome {
    if (typeof input !== 'string' || !input.trim()) return fail('Zadejte název účtu.');
    if (input.trim().length > LABEL_MAX || hasControl(input)) return fail(`Název smí mít nejvýše ${LABEL_MAX} znaků.`);
    return input.trim();
  }

  private load(): AccountsData {
    return readAccounts(this.deps.homeDir());
  }

  /** A non-empty list always has a default account: the first when none says so. */
  private withDefault(data: AccountsData): AccountsData {
    if (data.claude.length > 0 && !data.claude.some(c => c.default)) data.claude[0].default = true;
    return data;
  }
}

const same = (a: string, b: string): boolean => path.normalize(a).toLowerCase() === path.normalize(b).toLowerCase();

function firstLine(text: string): string {
  return text.trim().split(/\r?\n/).filter(Boolean)[0] ?? 'bez zprávy';
}

export type { ClaudeEntry, YoutrackEntry };
