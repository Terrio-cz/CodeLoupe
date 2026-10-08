import { useCallback, useEffect, useState } from 'react';
import type { EnvImportResult, EnvInventory } from '../../../shared/envActions';
import { bridge } from '../api';
import { choose, defaultPicks, groupKey, needsChoice, outcomeLabel, scopeLabel, selectedCount, selectionsOf, togglePick, type Picks } from '../envImport';
import { Drawer } from './Drawer';
import { StatusBadge } from './StatusBadge';

interface Props {
  onClose(): void;
  /** Something was imported or rolled back; the screen reloads. */
  onChanged(): void;
}

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
    <Drawer title="Import proměnných" subtitle="Najde proměnné v souborech Claude a v repozitářích a přesune vybrané do šifrovaného úložiště. Hodnoty okno nikdy nevidí." onClose={onClose} wide>
      {phase.step === 'scan' && (
        <div className="state" aria-busy="true" aria-label="Prohledávám">Prohledávám složky Claude a repozitáře (čtou se jen soubory s proměnnými, v okně zůstanou jména)…</div>
      )}
      {phase.step === 'importing' && <div className="state" aria-busy="true" aria-label="Importuji">Importuji do úložiště…</div>}
      {phase.step === 'failed' && (
        <div className="state" role="alert">
          <div>{phase.message}</div>
          <button className="btn" onClick={() => void scan(includeExcluded)}>Zkusit znovu</button>
        </div>
      )}
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
        {inv.counts.groups} proměnných ({inv.counts.names} jmen) v {inv.counts.files} souborech: {inv.counts.sensitive} vypadá jako přihlašovací údaj, {inv.counts.duplicates} se opakuje,
        {' '}{inv.counts.conflicts} má zdroje s různou hodnotou, {inv.counts.inStore} už je ve storu.
      </p>
      {inv.excluded.length > 0 && !p.includeExcluded && (
        <div className="banner info" role="note">Vynecháno {inv.excluded.length} složek cizích systémů (TNT/FoodRetailor): {inv.excluded.slice(0, 3).join(', ')}{inv.excluded.length > 3 ? ', …' : ''}.</div>
      )}
      <div className="filterbar">
        <label className="check"><input type="checkbox" checked={p.onlySensitive} onChange={e => p.setOnlySensitive(e.target.checked)} /> Jen údaje, které vypadají citlivě</label>
        <label className="check"><input type="checkbox" checked={p.includeExcluded} onChange={e => p.setIncludeExcluded(e.target.checked)} /> Zahrnout vyloučené složky</label>
      </div>
      <table className="data import-table choose">
        <caption className="sr-only">Nalezené proměnné</caption>
        <thead>
          <tr><th scope="col">Importovat</th><th scope="col">Klíč</th><th scope="col">Rozsah</th><th scope="col">Zdroje</th><th scope="col">Stav</th></tr>
        </thead>
        <tbody>
          {shown.length === 0 && <tr><td colSpan={5}>Nic k importu.</td></tr>}
          {shown.map(g => <GroupRow key={groupKey(g)} g={g} picks={picks} setPicks={p.setPicks} />)}
        </tbody>
      </table>
      <fieldset className="import-options">
        <legend className="sr-only">Možnosti importu</legend>
        <label className="check"><input type="checkbox" checked={p.replaceSources} onChange={e => p.setReplaceSources(e.target.checked)} /> Po importu nahradit hodnoty ve zdrojích odkazem (záloha zůstane, jde vrátit)</label>
        <label className="check"><input type="checkbox" checked={p.overwrite} onChange={e => p.setOverwrite(e.target.checked)} /> Přepsat odlišnou hodnotu, kterou už store drží</label>
      </fieldset>
      {unresolved > 0 && <div className="banner" role="note">{unresolved} proměnných má zdroje s různou hodnotou: vyberte u nich zdroj, jinak se neimportují.</div>}
      <div className="drawer-actions">
        <button className="btn primary" disabled={count === 0} onClick={p.onRun}>Importovat ({count})</button>
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
          ? <span className="muted">vyberte zdroj</span>
          : <input type="checkbox" aria-label={`Importovat ${g.name}`} checked={ticked} onChange={() => setPicks(togglePick(picks, g))} />}
      </td>
      <td className="mono">{g.name}</td>
      <td>{scopeLabel(g.scope)}</td>
      <td>
        <ul className="plain sources">
          {g.sources.map(s => (
            <li key={s.id}>
              {g.conflict && (
                <input type="radio" name={idBase} aria-label={`Použít zdroj ${s.file}`} checked={picks[key]?.[0] === s.id} onChange={() => setPicks(choose(picks, g, s.id))} />
              )}{' '}
              <span title={`${s.kind}: ${s.locator}`}>{s.file}</span> <span className="muted">({s.locator})</span>
            </li>
          ))}
        </ul>
      </td>
      <td>
        <span className="flags">
          {g.sensitive && <StatusBadge tone="neutral">citlivé</StatusBadge>}
          {g.conflict && <StatusBadge tone="serious">různé hodnoty</StatusBadge>}
          {g.duplicate && <StatusBadge tone="neutral">opakuje se</StatusBadge>}
          {g.store === 'same' && <StatusBadge tone="ok">ve storu</StatusBadge>}
          {g.store === 'differs' && <StatusBadge tone="warning">store má jinou</StatusBadge>}
        </span>
      </td>
    </tr>
  );
}

export function Done({ result, rolled, onRollback, onClose }: { result: EnvImportResult; rolled: string | null; onRollback(id: string): void; onClose(): void }) {
  return (
    <>
      <div className="banner info" role="status">Vytvořeno {result.created}, aktualizováno {result.updated}, přeskočeno {result.skipped}.{result.replacedFiles > 0 ? ` Zdroje nahrazeny v ${result.replacedFiles} souborech.` : ''}</div>
      <table className="data import-table">
        <caption className="sr-only">Výsledek importu</caption>
        <thead><tr><th scope="col">Klíč</th><th scope="col">Rozsah</th><th scope="col">Výsledek</th><th scope="col">Zdroj</th></tr></thead>
        <tbody>
          {result.items.slice(0, 200).map(i => (
            <tr key={i.id}>
              <td className="mono">{i.name}</td>
              <td>{scopeLabel(i.scope)}</td>
              <td>{outcomeLabel(i.outcome)}{i.replaced ? ', zdroj nahrazen odkazem' : ''}</td>
              <td>{i.file}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {result.items.length > 200 && <p className="muted">… a {result.items.length - 200} dalších.</p>}
      {result.notReplaced.length > 0 && (
        <div className="banner" role="note">
          Nenahrazeno:
          <ul className="plain">{result.notReplaced.map(n => <li key={n.file}>{n.file}: {n.reason}</li>)}</ul>
        </div>
      )}
      {result.backupId && (
        <div className="banner info" role="note">
          Původní soubory jsou v šifrované záloze {result.backupId}.
          {rolled ? <> {rolled}</> : <> <button className="btn" onClick={() => onRollback(result.backupId!)}>Vrátit zdroje do původního stavu</button></>}
        </div>
      )}
      <div className="drawer-actions"><button className="btn primary" onClick={onClose}>Hotovo</button></div>
    </>
  );
}
