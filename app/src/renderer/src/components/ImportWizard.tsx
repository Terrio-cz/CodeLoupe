import { useCallback, useEffect, useState } from 'react';
import type { EnvImportResult, EnvInventory } from '../../../shared/envActions';
import { bridge } from '../api';
import { choose, defaultPicks, groupKey, needsChoice, outcomeLabel, scopeLabel, selectedCount, selectionsOf, togglePick, type Picks } from '../envImport';
import { Drawer } from './Drawer';
import { ErrorState } from './Parts';
import { StatusBadge } from './StatusBadge';

interface Props {
  onClose(): void;
  /** Something was imported or rolled back; the screen reloads. */
  onChanged(): void;
}

const plural = (n: number, noun: string) => `${n} ${noun}${n === 1 ? '' : 's'}`;

type Phase =
  | { step: 'scan' }
  | { step: 'failed'; message: string }
  | { step: 'choose'; inventory: EnvInventory }
  | { step: 'importing' }
  | { step: 'done'; result: EnvImportResult };

/**
 * Inventory → confirm → import → optional replacement of the sources. Everything the page sees is names, places and
 * counts: main runs the CLI, which reads the files and copies the values into the store in its own process.
 */
export function ImportWizard({ onClose, onChanged }: Props) {
  const [phase, setPhase] = useState<Phase>({ step: 'scan' });
  const [picks, setPicks] = useState<Picks>({});
  const [onlySensitive, setOnlySensitive] = useState(true);
  const [includeExcluded, setIncludeExcluded] = useState(false);
  const [replaceSources, setReplaceSources] = useState(false);
  const [overwrite, setOverwrite] = useState(false);
  const [rolled, setRolled] = useState<string | null>(null);

  const scan = useCallback(async (withExcluded: boolean) => {
    setPhase({ step: 'scan' });
    const r = await bridge().env.scan(withExcluded);
    if (!r.ok) return setPhase({ step: 'failed', message: r.message });
    setPicks(defaultPicks(r.inventory));
    setPhase({ step: 'choose', inventory: r.inventory });
  }, []);

  useEffect(() => { void scan(false); }, [scan]);

  const run = async (inventory: EnvInventory) => {
    setPhase({ step: 'importing' });
    const r = await bridge().env.importRun({ selections: selectionsOf(inventory, picks), replaceSources, overwrite, includeExcluded });
    if (!r.ok) return setPhase({ step: 'failed', message: r.message });
    setPhase({ step: 'done', result: r.result });
    onChanged();
  };

  const rollback = async (id: string) => {
    const r = await bridge().env.rollback(id);
    setRolled(r.message);
    if (r.ok) onChanged();
  };

  return (
    <Drawer title="Import variables" subtitle="Finds variables in Claude files and in repositories and moves the selected ones into the encrypted store. The window never sees the values." onClose={onClose} wide>
      {phase.step === 'scan' && (
        <div className="state" aria-busy="true" aria-label="Scanning">Scanning Claude folders and repositories (only files with variables are read; the window keeps just the names)…</div>
      )}
      {phase.step === 'importing' && <div className="state" aria-busy="true" aria-label="Importing">Importing into the store…</div>}
      {phase.step === 'failed' && <ErrorState message={phase.message} onRetry={() => void scan(includeExcluded)} />}
      {phase.step === 'choose' && <Choose inventory={phase.inventory} picks={picks} setPicks={setPicks} onlySensitive={onlySensitive} setOnlySensitive={setOnlySensitive}
        includeExcluded={includeExcluded} setIncludeExcluded={v => { setIncludeExcluded(v); void scan(v); }} replaceSources={replaceSources} setReplaceSources={setReplaceSources}
        overwrite={overwrite} setOverwrite={setOverwrite} onRun={() => void run(phase.inventory)} />}
      {phase.step === 'done' && <Done result={phase.result} rolled={rolled} onRollback={id => void rollback(id)} onClose={onClose} />}
    </Drawer>
  );
}

interface ChooseProps {
  inventory: EnvInventory;
  picks: Picks;
  setPicks(p: Picks): void;
  onlySensitive: boolean;
  setOnlySensitive(v: boolean): void;
  includeExcluded: boolean;
  setIncludeExcluded(v: boolean): void;
  replaceSources: boolean;
  setReplaceSources(v: boolean): void;
  overwrite: boolean;
  setOverwrite(v: boolean): void;
  onRun(): void;
}

export function Choose(p: ChooseProps) {
  const { inventory: inv, picks } = p;
  const shown = inv.variables.filter(g => !p.onlySensitive || g.sensitive || picks[groupKey(g)]?.length);
  const count = selectedCount(inv, picks);
  const unresolved = inv.variables.filter(g => g.sensitive && needsChoice(g, picks)).length;
  return (
    <>
      <p className="muted">
        {plural(inv.counts.groups, 'variable')} ({plural(inv.counts.names, 'name')}) in {plural(inv.counts.files, 'file')}: {inv.counts.sensitive} look like credentials, {inv.counts.duplicates} repeat,
        {' '}{inv.counts.conflicts} have sources with different values, {inv.counts.inStore} already in the store.
      </p>
      {inv.excluded.length > 0 && !p.includeExcluded && (
        <div className="banner info" role="note">Skipped {plural(inv.excluded.length, 'folder')} of other systems (left out by your settings): {inv.excluded.slice(0, 3).join(', ')}{inv.excluded.length > 3 ? ', …' : ''}.</div>
      )}
      <div className="filterbar">
        <label className="check"><input type="checkbox" checked={p.onlySensitive} onChange={e => p.setOnlySensitive(e.target.checked)} /> Only values that look sensitive</label>
        <label className="check"><input type="checkbox" checked={p.includeExcluded} onChange={e => p.setIncludeExcluded(e.target.checked)} /> Include excluded folders</label>
      </div>
      <div className="table-wrap">
        <table className="data import-table choose">
          <caption className="sr-only">Variables found</caption>
          <thead>
            <tr><th scope="col">Import</th><th scope="col">Key</th><th scope="col">Scope</th><th scope="col">Sources</th><th scope="col">State</th></tr>
          </thead>
          <tbody>
            {shown.length === 0 && <tr><td colSpan={5}>Nothing to import.</td></tr>}
            {shown.map(g => <GroupRow key={groupKey(g)} g={g} picks={picks} setPicks={p.setPicks} />)}
          </tbody>
        </table>
      </div>
      <fieldset className="import-options">
        <legend className="sr-only">Import options</legend>
        <label className="check"><input type="checkbox" checked={p.replaceSources} onChange={e => p.setReplaceSources(e.target.checked)} /> After import, replace values in the sources with a reference (a backup is kept and can be restored)</label>
        <label className="check"><input type="checkbox" checked={p.overwrite} onChange={e => p.setOverwrite(e.target.checked)} /> Overwrite a different value the store already holds</label>
      </fieldset>
      {unresolved > 0 && <div className="banner" role="note">{unresolved === 1 ? '1 variable has' : `${unresolved} variables have`} sources with different values: pick a source for them, otherwise they are not imported.</div>}
      <div className="drawer-actions">
        <button className="btn primary" disabled={count === 0} onClick={p.onRun}>Import ({count})</button>
      </div>
    </>
  );
}

function GroupRow({ g, picks, setPicks }: { g: EnvInventory['variables'][number]; picks: Picks; setPicks(p: Picks): void }) {
  const key = groupKey(g);
  const ticked = (picks[key]?.length ?? 0) > 0;
  const idBase = `imp-${key.replace(/[^A-Za-z0-9]/g, '-')}`;
  return (
    <tr>
      <td>
        {g.conflict
          ? <span className="muted">pick a source</span>
          : <input type="checkbox" aria-label={`Import ${g.name}`} checked={ticked} onChange={() => setPicks(togglePick(picks, g))} />}
      </td>
      <td className="mono">{g.name}</td>
      <td>{scopeLabel(g.scope)}</td>
      <td>
        <ul className="plain sources">
          {g.sources.map(s => (
            <li key={s.id}>
              {g.conflict && (
                <input type="radio" name={idBase} aria-label={`Use source ${s.file}`} checked={picks[key]?.[0] === s.id} onChange={() => setPicks(choose(picks, g, s.id))} />
              )}{' '}
              <span title={`${s.kind}: ${s.locator}`}>{s.file}</span> <span className="muted">({s.locator})</span>
            </li>
          ))}
        </ul>
      </td>
      <td>
        <span className="flags">
          {g.sensitive && <StatusBadge tone="neutral">sensitive</StatusBadge>}
          {g.conflict && <StatusBadge tone="serious">different values</StatusBadge>}
          {g.duplicate && <StatusBadge tone="neutral">repeated</StatusBadge>}
          {!!g.shadows?.length && <span title={`Would hide the stored ${g.shadows.join(', ')} value of ${g.name} inside this scope`}><StatusBadge tone="warning">hides {g.shadows.map(s => s.split(':')[0]).join(', ')} key</StatusBadge></span>}
          {g.store === 'same' && <StatusBadge tone="ok">in store</StatusBadge>}
          {g.store === 'differs' && <StatusBadge tone="warning">store differs</StatusBadge>}
        </span>
      </td>
    </tr>
  );
}

export function Done({ result, rolled, onRollback, onClose }: { result: EnvImportResult; rolled: string | null; onRollback(id: string): void; onClose(): void }) {
  return (
    <>
      <div className="banner info" role="status">Created {result.created}, updated {result.updated}, skipped {result.skipped}.{result.replacedFiles > 0 ? ` Sources replaced in ${plural(result.replacedFiles, 'file')}.` : ''}</div>
      <div className="table-wrap">
        <table className="data import-table">
          <caption className="sr-only">Import result</caption>
          <thead><tr><th scope="col">Key</th><th scope="col">Scope</th><th scope="col">Result</th><th scope="col">Source</th></tr></thead>
          <tbody>
            {result.items.slice(0, 200).map(i => (
              <tr key={i.id}>
                <td className="mono">{i.name}</td>
                <td>{scopeLabel(i.scope)}</td>
                <td>{outcomeLabel(i.outcome)}{i.replaced ? ', source replaced with a reference' : ''}</td>
                <td>{i.file}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {result.items.length > 200 && <p className="muted">… and {result.items.length - 200} more.</p>}
      {result.notReplaced.length > 0 && (
        <div className="banner" role="note">
          Not replaced:
          <ul className="plain">{result.notReplaced.map(n => <li key={n.file}>{n.file}: {n.reason}</li>)}</ul>
        </div>
      )}
      {result.backupId && (
        <div className="banner info" role="note">
          The original files are in encrypted backup {result.backupId}.
          {rolled ? <> {rolled}</> : <> <button className="btn" onClick={() => onRollback(result.backupId!)}>Restore sources to original state</button></>}
        </div>
      )}
      <div className="drawer-actions"><button className="btn primary" onClick={onClose}>Done</button></div>
    </>
  );
}
