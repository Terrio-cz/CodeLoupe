// Changing the accounts (docs/ui-spec.md § 3.9). The page only asks; main validates, confirms in a native dialog where
// something is removed or the daemon has to restart, writes `<home>/accounts.json` (names and URLs, no secret) and puts
// a YouTrack token into the encrypted store through the CLI. No answer below carries a token.

export const ACCOUNT_CH = {
  claudeAdd: 'cl:accounts:claude-add',
  claudeRename: 'cl:accounts:claude-rename',
  claudeDefault: 'cl:accounts:claude-default',
  claudeRemove: 'cl:accounts:claude-remove',
  youtrackAdd: 'cl:accounts:youtrack-add',
  youtrackTest: 'cl:accounts:youtrack-test',
  youtrackRotate: 'cl:accounts:youtrack-rotate',
  youtrackRemove: 'cl:accounts:youtrack-remove',
} as const;

/** One line for the page; `ok: false` carries the reason, never a token. */
export interface AccountOutcome {
  ok: boolean;
  message: string;
}

export interface ClaudeAccountInput {
  label: string;
  /** Absolute path of the account's Claude Code config directory (`CLAUDE_CONFIG_DIR`). */
  configDir: string;
  /** Create the directory when it does not exist. */
  create: boolean;
}

export interface YoutrackAccountInput {
  label: string;
  url: string;
  /** Project short names (`TER`, `CL`). */
  projects: string[];
  /** Sent once, cleared by the page at once, stored in the encrypted store and never returned. */
  token: string;
}

export interface AccountsBridge {
  claudeAdd(input: ClaudeAccountInput): Promise<AccountOutcome>;
  claudeRename(id: string, label: string): Promise<AccountOutcome>;
  claudeSetDefault(id: string): Promise<AccountOutcome>;
  /** Main asks in a native dialog first; only the entry is removed, never the directory. */
  claudeRemove(id: string): Promise<AccountOutcome>;
  /** Main asks first (the daemon restarts to mirror the new instance). */
  youtrackAdd(input: YoutrackAccountInput): Promise<AccountOutcome>;
  /** Main calls `GET <url>/api/users/me` with the stored token. */
  youtrackTest(id: string): Promise<AccountOutcome>;
  youtrackRotate(id: string, token: string): Promise<AccountOutcome>;
  /** Main asks first (the token is deleted and the daemon restarts). */
  youtrackRemove(id: string): Promise<AccountOutcome>;
}
