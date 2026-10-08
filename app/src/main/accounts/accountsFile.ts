import fs from 'node:fs';
import path from 'node:path';

// `<home>/accounts.json`: the app writes it, the daemon reads it (codeloupe.accounts.AccountsFile). No secret in it: a
// YouTrack token is the name `token` in the encrypted store.

export interface ClaudeEntry {
  id: string;
  label: string;
  configDir: string;
  default: boolean;
}

export interface YoutrackEntry {
  id: string;
  label: string;
  url: string;
  projects: string[];
  token: string;
}

export interface AccountsData {
  claude: ClaudeEntry[];
  youtrack: YoutrackEntry[];
}

export const accountsPath = (home: string): string => path.join(home, 'accounts.json');

const isObject = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);
const text = (v: unknown): string | null => (typeof v === 'string' && v.length > 0 ? v : null);

/** What the file says; a missing or damaged file is no accounts. */
export function readAccounts(home: string): AccountsData {
  let raw: unknown;
  try { raw = JSON.parse(fs.readFileSync(accountsPath(home), 'utf8')); } catch { return { claude: [], youtrack: [] }; }
  const o = isObject(raw) ? raw : {};
  const claude: ClaudeEntry[] = [];
  for (const c of Array.isArray(o.claude) ? o.claude : []) {
    if (isObject(c) && text(c.id) && text(c.label) && text(c.configDir)) claude.push({ id: c.id as string, label: c.label as string, configDir: c.configDir as string, default: c.default === true });
  }
  const youtrack: YoutrackEntry[] = [];
  for (const y of Array.isArray(o.youtrack) ? o.youtrack : []) {
    if (isObject(y) && text(y.id) && text(y.url) && text(y.token)) {
      youtrack.push({
        id: y.id as string, label: text(y.label) ?? (y.id as string), url: y.url as string, token: y.token as string,
        projects: Array.isArray(y.projects) ? y.projects.filter((p): p is string => typeof p === 'string') : [],
      });
    }
  }
  return { claude, youtrack };
}

/** Writes through a temp file beside it, so a crash leaves the old or the new file, never half. */
export function writeAccounts(home: string, data: AccountsData): void {
  const file = accountsPath(home);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const temp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(temp, `${JSON.stringify({ version: 1, ...data }, null, 2)}\n`);
  fs.renameSync(temp, file);
}
