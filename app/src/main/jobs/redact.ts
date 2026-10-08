// The escape character is built rather than written in the literal, which the linter rejects as a control character.
const ANSI = new RegExp(`${String.fromCharCode(27)}\\[[0-9;?]*[ -/]*[@-~]`, 'g');
const MASK = '***';

// Values the daemon masks only when it knows them; these are the shapes of the common ones, as a second line of defence.
const TOKENS = [
  /\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|perm:[A-Za-z0-9._=-]{10,})/g,
  /\bBearer\s+[A-Za-z0-9._~+/=-]{12,}/gi,
];
const ASSIGNED = /\b([A-Za-z0-9_.-]*(?:token|secret|password|passwd|api[_-]?key|credential)[A-Za-z0-9_.-]*)(\s*[=:]\s*)("[^"]*"|'[^']*'|\S+)/gi;
const URL_USER = /\b([a-z][a-z0-9+.-]*:\/\/)[^\s/@:]+:[^\s/@]+@/gi;

/** One log line without terminal codes and with anything that looks like a credential masked. */
export function redactLine(line: string): string {
  let out = line.replace(ANSI, '');
  for (const t of TOKENS) out = out.replace(t, MASK);
  out = out.replace(ASSIGNED, (_m, name: string, sep: string) => `${name}${sep}${MASK}`);
  return out.replace(URL_USER, `$1${MASK}@`);
}
