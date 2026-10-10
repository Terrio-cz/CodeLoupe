import type { CommandGroup, CommandKind, CommandSortKey } from '../../shared/commands';
import { num, tokens } from './format';

export const SORT_LABELS: Record<CommandSortKey, string> = { cost: 'Cost carried', calls: 'Calls', tokens: 'Result size' };

export const KIND_LABELS: Record<CommandKind, string> = {
  shell: 'shell', heredoc: 'heredoc', file: 'file tool', search: 'search tool', mcp: 'MCP tool', agent: 'agent', other: 'other',
};

/** `1.24 USD`; a dash when no run of the period ran on a priced model. */
export function moneyText(amount: number | null, currency: string): string {
  return amount === null ? '—' : `${amount.toFixed(2)} ${currency}`;
}

/** Repeated calls with the tokens they brought again: `12 · 3.4k`; nothing to say is a dash. */
export function repeatText(g: Pick<CommandGroup, 'repeats' | 'repeatTokens'>): string {
  return g.repeats === 0 ? '—' : `${num(g.repeats)} · ${tokens(g.repeatTokens)}`;
}

/** The sort key a table column stands for, or null for a column the server does not rank by. */
export function sortOf(columnKey: string): CommandSortKey | null {
  return columnKey === 'calls' || columnKey === 'tokens' || columnKey === 'cost' ? columnKey : null;
}

/** Where a row of a ranked table leads: the runs that carried most of the group, best first, as `#1234` and the number of calls in each. */
export function runLinks(g: CommandGroup): { id: string; text: string; title: string }[] {
  return g.topRuns.map(r => ({
    id: r.id,
    text: `#${r.id}`,
    title: `${r.role}${r.ter ? ` · ${r.ter}` : ''} · ${num(r.calls)} ${r.calls === 1 ? 'call' : 'calls'}, ${tokens(r.weighted)} weighted · ${r.title}`,
  }));
}
