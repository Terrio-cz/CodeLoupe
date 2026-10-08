// Changing the environment store (docs/ui-spec.md § 3.7.1). The page only asks; main validates, confirms in a native
// dialog where something is removed or rewritten, and writes through the CodeLoupe CLI (value on its stdin). A value
// goes into the store and never comes back: no answer below carries one.

export const ENV_CH = {
  capabilities: 'cl:env:capabilities',
  set: 'cl:env:set',
  remove: 'cl:env:remove',
  scan: 'cl:env:scan',
  importRun: 'cl:env:import-run',
  rollback: 'cl:env:rollback',
  reveal: 'cl:env:reveal',
} as const;

export type EnvScopeKind = 'global' | 'workspace' | 'repo';

export interface EnvScopeInput {
  kind: EnvScopeKind;
  /** Workspace folder or repository path; absent for `global`. */
  ref?: string;
}

export interface EnvSetInput {
  name: string;
  scope: EnvScopeInput;
  /** Sent once, cleared by the page at once, never returned. */
  value: string;
}

export interface EnvKeyRef {
  name: string;
  scope: EnvScopeInput;
}

/** One line for the page; `ok: false` carries the reason, never the value. */
export interface EnvOutcome {
  ok: boolean;
  message: string;
}

/** What the platform can do; the page hides what is false. */
export interface EnvCapabilities {
  /** The OS can ask the user to authenticate again (Touch ID on macOS); without it a value cannot be copied out at all. */
  reveal: boolean;
}

/** The inventory of `codeloupe env import scan --json`: names, places, comparisons. */
export interface EnvInventory {
  roots: string[];
  excluded: string[];
  counts: {
    files: number; occurrences: number; names: number; groups: number; sensitive: number; duplicates: number; conflicts: number;
    inStore: number; unreadable: number; invalidNames: number; empty: number; references: number; excludedFolders: number;
  };
  variables: {
    name: string;
    scope: string;
    sensitive: boolean;
    store: 'new' | 'same' | 'differs';
    duplicate: boolean;
    conflict: boolean;
    sources: { id: string; file: string; kind: string; locator: string; hash: string }[];
  }[];
}

export type EnvScanResult = { ok: true; inventory: EnvInventory } | { ok: false; message: string };

export interface EnvImportInput {
  /** Occurrence ids from the inventory, each with an optional scope (`global`, `workspace:<id>`, `repo:<id>`) instead of the suggested one. */
  selections: { id: string; scope?: string }[];
  /** Replace the imported values in their source files with references (main asks first and keeps a backup). */
  replaceSources: boolean;
  /** Let a file's value win over a different value the store already holds. */
  overwrite: boolean;
  includeExcluded: boolean;
}

export interface EnvImportResult {
  created: number;
  updated: number;
  skipped: number;
  items: { id: string; name: string; scope: string; file: string; outcome: string; replaced: boolean }[];
  replacedFiles: number;
  backupId: string | null;
  notReplaced: { file: string; reason: string }[];
}

export type EnvImportOutcome = { ok: true; result: EnvImportResult } | { ok: false; message: string };

export interface EnvBridge {
  capabilities(): Promise<EnvCapabilities>;
  /** Stores or replaces (rotates) a value. */
  set(input: EnvSetInput): Promise<EnvOutcome>;
  /** Main asks in a native dialog first. */
  remove(key: EnvKeyRef): Promise<EnvOutcome>;
  scan(includeExcluded: boolean): Promise<EnvScanResult>;
  /** Main asks in a native dialog before it replaces sources. */
  importRun(input: EnvImportInput): Promise<EnvImportOutcome>;
  /** Main asks in a native dialog first. */
  rollback(backupId: string): Promise<EnvOutcome>;
  /** Copies the value to the clipboard after the OS asked the user to authenticate; the page never gets it. */
  reveal(key: EnvKeyRef): Promise<EnvOutcome>;
}
