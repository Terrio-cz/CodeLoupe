// Agent runs read from the ingested Claude Code transcripts (docs/ui-spec.md § 9.7–9.8, CL-62): what a run cost and where.
// Nothing here follows a running agent: the daemon ingests transcripts on a request, and a run is a finished or still
// growing record of tokens and tool calls. Titles and step summaries are masked and cut by the daemon and never carry content.

import type { Iso, Page } from './contract';

export type RunSortKey = 'start' | 'weighted' | 'turns' | 'peak' | 'share' | 'duration';
export type StepSortKey = 'seq' | 'weighted' | 'chars';

export interface RunItem {
  id: string;
  file: string;
  session: string;
  project: string;
  kind: 'session' | 'subagent';
  role: string;
  ter: string | null;
  model: string | null;
  /** The first line of the prompt, masked and cut. */
  title: string;
  startedAt: Iso;
  endedAt: Iso;
  durationSec: number;
  turns: number;
  /** Weighted tokens: input + 1.25·cacheWrite5m + 2·cacheWrite1h + 0.1·cacheRead + 5·output. */
  weighted: number;
  peakContext: number;
  /** 0..1: the part of the cost that keeping tool results in context carries. */
  toolResultShare: number;
  toolCalls: number;
  toolErrors: number;
  /** More than `budgets.runWeighted`. */
  overBudget: boolean;
}

export interface IngestStatus {
  running: boolean;
  filesDone: number;
  filesTotal: number;
  at: Iso | null;
}

export type RunPage = Page<RunItem> & { roles: string[]; ingest: IngestStatus };

export interface RunDetail {
  run: RunItem;
  usage: { input: number; cacheWrite5m: number; cacheWrite1h: number; cacheRead: number; output: number };
  categories: { category: string; calls: number; chars: number; carried: number; weighted: number; errors: number }[];
}

export interface StepItem {
  seq: number;
  turn: number;
  at: Iso | null;
  tool: string;
  category: string;
  /** What the call was about (command, file, pattern), masked, at most 200 characters, never the content. */
  summary: string;
  chars: number;
  durationMs: number;
  error: boolean;
  errorText: string | null;
  /** Result characters times the turns that followed. */
  carried: number;
  /** What keeping the result in context cost. */
  weighted: number;
  /** The kind of gap a CodeLoupe call ended in. */
  gap: 'fallback' | 'empty' | 'candidates' | 'busy' | null;
}

export type StepPage = Page<StepItem>;
