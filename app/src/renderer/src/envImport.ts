import type { EnvImportInput, EnvInventory } from '../../shared/envActions';

export type Group = EnvInventory['variables'][number];

export const groupKey = (g: Group): string => `${g.name}|${g.scope}`;

/** Which source ids are ticked per name and scope. A group in conflict holds at most one: choosing it is the resolution. */
export type Picks = Record<string, string[]>;

/** Credentials that no source contradicts, the store does not hold yet and that hide no wider secret are ticked; the rest waits for the user. */
export function defaultPicks(inventory: EnvInventory): Picks {
  const picks: Picks = {};
  for (const g of inventory.variables) {
    if (g.sensitive && !g.conflict && g.store === 'new' && !g.shadows?.length) picks[groupKey(g)] = g.sources.map(s => s.id);
  }
  return picks;
}

export function togglePick(picks: Picks, g: Group): Picks {
  const next = { ...picks };
  if (next[groupKey(g)]?.length) delete next[groupKey(g)];
  else if (!g.conflict) next[groupKey(g)] = g.sources.map(s => s.id);
  return next;
}

/** Choosing one source of a conflicting group replaces any earlier choice. */
export function choose(picks: Picks, g: Group, sourceId: string): Picks {
  return { ...picks, [groupKey(g)]: [sourceId] };
}

export function selectedCount(inventory: EnvInventory, picks: Picks): number {
  return inventory.variables.filter(g => picks[groupKey(g)]?.length).length;
}

/** The ids the CLI imports, for the groups the user kept ticked. */
export function selectionsOf(inventory: EnvInventory, picks: Picks): EnvImportInput['selections'] {
  return inventory.variables.flatMap(g => (picks[groupKey(g)] ?? []).map(id => ({ id })));
}

/** A group needs a decision before it can be imported. */
export const needsChoice = (g: Group, picks: Picks): boolean => g.conflict && !picks[groupKey(g)]?.length;

/** `workspace:c:/work/terrio` → `workspace · c:/work/terrio`. */
export function scopeLabel(scope: string): string {
  const i = scope.indexOf(':');
  return i < 0 ? scope : `${scope.slice(0, i)} · ${scope.slice(i + 1)}`;
}

const OUTCOME: Record<string, string> = {
  CREATED: 'created',
  UPDATED: 'updated',
  SKIPPED_SAME: 'unchanged (already in store)',
  SKIPPED_DIFFERS: 'skipped (store holds a different value)',
  SKIPPED_CONFLICT: 'skipped (sources differ)',
};

export const outcomeLabel = (outcome: string): string => OUTCOME[outcome] ?? outcome.toLowerCase();
