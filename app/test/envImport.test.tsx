import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Choose, Done } from '../src/renderer/src/components/ImportWizard';
import { choose, defaultPicks, groupKey, needsChoice, outcomeLabel, scopeLabel, selectedCount, selectionsOf, togglePick } from '../src/renderer/src/envImport';
import type { EnvImportResult, EnvInventory } from '../src/shared/envActions';

const source = (id: string, file: string, hash: string) => ({ id, file, kind: '.env file', locator: 'line 1', hash });
const inventory: EnvInventory = {
  roots: ['C:/Users/dev'],
  excluded: ['C:/Users/dev/Documents/Claude/tnt'],
  counts: { files: 5, occurrences: 7, names: 5, groups: 5, sensitive: 4, duplicates: 1, conflicts: 1, inStore: 1, unreadable: 0, invalidNames: 0, empty: 0, references: 0, excludedFolders: 1 },
  variables: [
    { name: 'API_KEY', scope: 'repo:c:/work/app', sensitive: true, store: 'new', duplicate: false, conflict: true, sources: [source('aaaaaaaaaaaa', 'C:/work/app/.env', 'h1'), source('bbbbbbbbbbbb', 'C:/work/app/api/.env', 'h2')] },
    { name: 'BASH_TIMEOUT_MS', scope: 'global', sensitive: false, store: 'new', duplicate: false, conflict: false, sources: [source('cccccccccccc', 'C:/Users/dev/.claude/settings.json', 'h3')] },
    { name: 'DB_PASSWORD', scope: 'repo:c:/work/app', sensitive: true, store: 'new', duplicate: false, conflict: false, sources: [source('dddddddddddd', 'C:/work/app/.env', 'h4')] },
    { name: 'GITHUB_TOKEN', scope: 'global', sensitive: true, store: 'same', duplicate: false, conflict: false, sources: [source('eeeeeeeeeeee', 'C:/Users/dev/.claude.json', 'h5')] },
    { name: 'YOUTRACK_TOKEN', scope: 'workspace:c:/work/terrio', sensitive: true, store: 'new', duplicate: true, conflict: false, sources: [source('111111111111', 'C:/work/terrio/.mcp.json', 'h6'), source('222222222222', 'C:/work/terrio/.claude/settings.local.json', 'h6')] },
  ],
};
const [api, , db, , yt] = inventory.variables;

describe('import selection', () => {
  it('ticks credentials that nothing contradicts and the store does not hold yet', () => {
    const picks = defaultPicks(inventory);
    expect(Object.keys(picks).sort()).toEqual([groupKey(db), groupKey(yt)].sort());
    expect(picks[groupKey(yt)]).toEqual(['111111111111', '222222222222']);
    expect(selectedCount(inventory, picks)).toBe(2);
  });

  it('needs an explicit source for a conflict and imports exactly that one', () => {
    let picks = defaultPicks(inventory);
    expect(needsChoice(api, picks)).toBe(true);
    expect(togglePick(picks, api)[groupKey(api)]).toBeUndefined();
    picks = choose(picks, api, 'bbbbbbbbbbbb');
    expect(needsChoice(api, picks)).toBe(false);
    expect(selectionsOf(inventory, picks).map(s => s.id).sort()).toEqual(['111111111111', '222222222222', 'bbbbbbbbbbbb', 'dddddddddddd']);
    picks = togglePick(picks, api);
    expect(picks[groupKey(api)]).toBeUndefined();
    expect(selectionsOf(inventory, togglePick(picks, db)).map(s => s.id)).not.toContain('dddddddddddd');
  });

  it('labels scopes and outcomes in words', () => {
    expect(scopeLabel('workspace:c:/work/terrio')).toBe('workspace · c:/work/terrio');
    expect(scopeLabel('global')).toBe('global');
    expect(outcomeLabel('SKIPPED_CONFLICT')).toContain('sources differ');
    expect(outcomeLabel('SOMETHING_NEW')).toBe('something_new');
  });
});

const noop = () => undefined;
const props = { inventory, picks: defaultPicks(inventory), setPicks: noop, onlySensitive: true, setOnlySensitive: noop, includeExcluded: false, setIncludeExcluded: noop, replaceSources: false, setReplaceSources: noop, overwrite: false, setOverwrite: noop, onRun: noop };

describe('import wizard markup', () => {
  it('lists names, scopes and sources, hides what is not sensitive, and never renders a value', () => {
    const html = renderToStaticMarkup(<Choose {...props} />);
    expect(html).toContain('DB_PASSWORD');
    expect(html).toContain('C:/work/terrio/.mcp.json');
    expect(html).not.toContain('BASH_TIMEOUT_MS');
    expect(html).toContain('different values');
    expect(html).toContain('pick a source');
    expect(html).toContain('Import (2)');
    expect(html).toContain('Skipped 1 folder of');
    expect(html).toContain('1 variable has sources with different values');
    expect(html).not.toContain('type="password"');
    expect(renderToStaticMarkup(<Choose {...props} onlySensitive={false} />)).toContain('BASH_TIMEOUT_MS');
  });

  it('shows the result and a way back when sources were replaced', () => {
    const result: EnvImportResult = {
      created: 2, updated: 0, skipped: 1, replacedFiles: 1, backupId: '20261008123456-aabbcc',
      items: [{ id: 'dddddddddddd', name: 'DB_PASSWORD', scope: 'repo:c:/work/app', file: 'C:/work/app/.env', outcome: 'CREATED', replaced: true }],
      notReplaced: [{ file: 'C:/work/app/api/.env', reason: 'changed since the scan' }],
    };
    const html = renderToStaticMarkup(<Done result={result} rolled={null} onRollback={noop} onClose={noop} />);
    expect(html).toContain('Created 2, updated 0, skipped 1.');
    expect(html).toContain('source replaced with a reference');
    expect(html).toContain('20261008123456-aabbcc');
    expect(html).toContain('Restore sources to original state');
    expect(html).toContain('changed since the scan');
    expect(renderToStaticMarkup(<Done result={result} rolled="Restored 1 file." onRollback={noop} onClose={noop} />)).not.toContain('Restore sources to original state');
  });
});
