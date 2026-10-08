import { useEffect, useState } from 'react';
import type { UpdateState } from '../../../shared/ipc';
import { bridge } from '../api';
import { useSettings } from '../hooks';
import { Card } from './Parts';

/** What the state means for the user, in one line. */
export function updateLine(u: UpdateState): string {
  switch (u.phase) {
    case 'off': return u.mode === 'unavailable' ? (u.reason ?? 'Aktualizace nejsou dostupné.') : 'Automatická kontrola je vypnutá.';
    case 'checking': return 'Hledám novou verzi…';
    case 'downloading': return `Stahuji ${u.latest ?? 'novou verzi'}${u.percent !== null ? ` (${u.percent} %)` : ''}…`;
    case 'ready': return `Verze ${u.latest} je stažená a ověřená. Restartujte aplikaci a nainstaluje se.`;
    case 'available': return `Je dostupná verze ${u.latest}. ${u.reason ?? ''}`.trim();
    case 'error': return `Kontrola selhala: ${u.message ?? 'neznámá chyba'}`;
    default: return u.checkedAt ? `Máte nejnovější verzi (kontrola ${new Date(u.checkedAt).toLocaleString('cs-CZ')}).` : 'Zatím nezkontrolováno.';
  }
}

/** Updates (CL-107): the switch for the automatic check, the state of the last one and the restart into a downloaded update. */
export function UpdateCard() {
  const [settings, update] = useSettings();
  const [state, setState] = useState<UpdateState | null>(null);

  useEffect(() => {
    void bridge().update.state().then(setState);
    return bridge().update.onState(setState);
  }, []);

  if (!settings || !state) return null;
  const busy = state.phase === 'checking' || state.phase === 'downloading';
  return (
    <Card title="Aktualizace">
      <dl className="dl">
        <dt>Verze</dt><dd>{state.current}</dd>
        <dt>Stav</dt><dd role="status">{updateLine(state)}</dd>
        {state.mode === 'notify' && state.reason && state.phase !== 'available' && <><dt>Způsob</dt><dd>{state.reason}</dd></>}
        {state.rollback && (
          <><dt>Daemon</dt><dd>Daemon verze {state.rollback.failedVersion} se nespustil ({state.rollback.reason}), běží předchozí verze {state.rollback.usingVersion}.</dd></>
        )}
      </dl>
      <div className="form-row">
        <label className="check">
          <input type="checkbox" checked={settings.autoUpdate} disabled={state.mode === 'unavailable'} onChange={e => void update({ autoUpdate: e.target.checked })} /> Hledat novou verzi automaticky
        </label>
      </div>
      <p className="t2">
        Aplikace se ptá jen na stránce vydání na GitHubu (Terrio-cz/CodeLoupe), nic o vás neposílá a bez zapnutého přepínače se sama neptá vůbec. Stažený instalátor se ověří podle SHA-512 z vydání.
      </p>
      <div className="actions">
        {state.phase === 'ready' && <button className="btn primary" onClick={() => void bridge().update.install()}>Restartovat a aktualizovat</button>}
        {state.phase === 'available' && <button className="btn primary" onClick={() => void bridge().update.openRelease()}>Otevřít stránku vydání</button>}
        <button className="btn" disabled={busy || state.mode === 'unavailable' || state.phase === 'ready'} onClick={() => void bridge().update.check().then(setState)}>Zkontrolovat teď</button>
      </div>
    </Card>
  );
}
