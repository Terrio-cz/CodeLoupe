// Which commands agents use and what they cost (docs/ui-spec.md § 9.7a, CL-220): the report of `codeloupe metrics commands`, read by the
// daemon from the ingested transcripts. Group labels are made of program names, subcommands, flags and placeholders (`<str>`, `<path>`),
// never of the arguments a call carried.

import type { IngestStatus } from './runs';

export type CommandKind = 'shell' | 'heredoc' | 'file' | 'search' | 'mcp' | 'agent' | 'other';
export type CommandSortKey = 'cost' | 'calls' | 'tokens';

/** A run that carried a share of a group: `weighted` is what keeping the results of its `calls` in context cost it. */
export interface CommandRunRef {
  id: string;
  role: string;
  ter: string | null;
  title: string;
  calls: number;
  weighted: number;
}

export interface CommandGroup {
  /** `git diff --stat`, `rg -n <str> <path>`, `heredoc write`, `Write (new file)`, `codeloupe/symbol`. */
  command: string;
  kind: CommandKind;
  calls: number;
  /** Runs that made at least one call of the group. */
  runs: number;
  /** Tokens (a quarter per character) the results hold, and what the calls sent. */
  resultTokens: number;
  argTokens: number;
  /** Calls identical to an earlier call of the same run, and the result tokens they brought again. */
  repeats: number;
  repeatTokens: number;
  errors: number;
  /** What keeping the results in context cost: written once, read on every later turn. */
  weighted: number;
  /** That, as a percentage of the cost of the runs counted. */
  costPct: number;
  /** The part of the money those runs cost; null when no run of the period ran on a priced model. */
  money: number | null;
  /** The runs that carried most of it, only on the overall groups. */
  topRuns: CommandRunRef[];
}

/** A tool category as `metrics collect` sorts the calls, with the same figures, for comparison. */
export interface CommandCategory {
  category: string;
  calls: number;
  resultTokens: number;
  errors: number;
  weighted: number;
  costPct: number;
}

/** The commands of one role (`key` the role) or one ISO week (`key` like `2026-W41`). */
export interface CommandSlice {
  key: string;
  runs: number;
  calls: number;
  resultTokens: number;
  cost: number;
  costPct: number;
  groups: CommandGroup[];
}

export interface CommandReport {
  generatedAt: string;
  since: string | null;
  until: string | null;
  role: string | null;
  runs: number;
  calls: number;
  resultTokens: number;
  argTokens: number;
  /** Weighted cost of the runs counted; `weighted` is the part that results carried, `costPct` the same as a percentage. */
  cost: number;
  weighted: number;
  costPct: number;
  money: number | null;
  currency: string;
  /** Runs of a model without a price: they add nothing to `money`. */
  unpricedRuns: number;
  /** How many groups there are in all; `groups` are the first ones in the order asked for. */
  groupCount: number;
  groups: CommandGroup[];
  categories: CommandCategory[];
  roles: CommandSlice[];
  weeks: CommandSlice[];
}

export interface CommandsView {
  report: CommandReport;
  /** Every role seen in the transcripts, for the filter. */
  roles: string[];
  ingest: IngestStatus;
}
