import { useRef, useState } from 'react';
import type { EnvScopeKind } from '../../../shared/envActions';
import { bridge } from '../api';
import { Drawer } from './Drawer';

export interface KeyTarget {
  name: string;
  scope: EnvScopeKind;
  scopeRef: string | null;
}

interface Props {
  /** Set when rotating: name and scope are fixed and the old value is not shown. */
  rotate?: KeyTarget;
  onClose(): void;
  /** The store changed; the screen reloads. */
  onSaved(message: string): void;
}

/**
 * Add or rotate one key. The value lives in an uncontrolled password field and is read once on submit; the field is
 * emptied before the request leaves, so no component state, no re-render and no later screen ever holds it.
 */
export function KeyDrawer({ rotate, onClose, onSaved }: Props) {
  const [name, setName] = useState(rotate?.name ?? '');
  const [kind, setKind] = useState<EnvScopeKind>(rotate?.scope ?? 'global');
  const [ref, setRef] = useState(rotate?.scopeRef ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const value = useRef<HTMLInputElement>(null);

  const submit = async () => {
    const field = value.current;
    if (!field || busy) return;
    const secret = field.value;
    field.value = '';
    if (!secret) { setError('Zadejte hodnotu.'); return; }
    setBusy(true);
    setError(null);
    const outcome = await bridge().env.set({ name: name.trim(), scope: kind === 'global' ? { kind } : { kind, ref: ref.trim() }, value: secret });
    setBusy(false);
    if (outcome.ok) onSaved(outcome.message);
    else setError(outcome.message);
  };

  return (
    <Drawer title={rotate ? `Rotovat ${rotate.name}` : 'Přidat klíč'} subtitle="Hodnota se uloží šifrovaně a po uložení se už nikde nezobrazí." onClose={onClose}>
      <form className="key-form" onSubmit={e => { e.preventDefault(); void submit(); }} autoComplete="off">
        <div className="form-row">
          <label className="label" htmlFor="key-name">Jméno</label>
          <input id="key-name" className="input mono" value={name} onChange={e => setName(e.target.value)} disabled={!!rotate} required maxLength={64}
            pattern="[A-Za-z_][A-Za-z0-9_]*" placeholder="např. YOUTRACK_TOKEN" autoComplete="off" spellCheck={false} />
        </div>
        <div className="form-row">
          <label className="label" htmlFor="key-scope">Rozsah</label>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <select id="key-scope" className="select" value={kind} onChange={e => setKind(e.target.value as EnvScopeKind)} disabled={!!rotate}>
              <option value="global">global</option>
              <option value="workspace">workspace</option>
              <option value="repo">repo</option>
            </select>
            {kind !== 'global' && (
              <input aria-label="Cesta nebo identifikátor rozsahu" className="input mono" style={{ flex: 1, minWidth: 220 }} value={ref} onChange={e => setRef(e.target.value)}
                disabled={!!rotate} required placeholder={kind === 'workspace' ? 'složka workspace, např. C:/Users/me/Documents/Claude/terrio' : 'cesta k repozitáři'} autoComplete="off" spellCheck={false} />
            )}
          </div>
        </div>
        <div className="form-row">
          <label className="label" htmlFor="key-value">{rotate ? 'Nová hodnota' : 'Hodnota'}</label>
          <input id="key-value" ref={value} className="input mono" type="password" autoComplete="new-password" spellCheck={false} autoCapitalize="off" autoCorrect="off"
            required={true} aria-describedby="key-value-note" />
        </div>
        <p id="key-value-note" className="muted">
          Pole se vyprázdní při odeslání. Hodnota jde jen do hlavního procesu aplikace a odtud na vstup CLI; do příkazové řádky ani do logu se nedostane.
        </p>
        {error && <div className="banner" role="alert">{error}</div>}
        <div className="drawer-actions">
          <button type="submit" className="btn primary" disabled={busy || !name.trim() || (kind !== 'global' && !ref.trim())}>{busy ? 'Ukládám…' : rotate ? 'Rotovat' : 'Uložit'}</button>
          <button type="button" className="btn" onClick={onClose}>Zrušit</button>
        </div>
      </form>
    </Drawer>
  );
}
