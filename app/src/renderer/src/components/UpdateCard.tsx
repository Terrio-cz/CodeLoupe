import { useEffect, useState } from 'react';
import type { UpdateState } from '../../../shared/ipc';
import { bridge } from '../api';
import { useSettings } from '../hooks';
import { Card } from './Parts';

/** What the state means for the user, in one line. */
export function updateLine(u: UpdateState): string {
  switch (u.phase) {
    case 'off': return u.mode === 'unavailable' ? (u.reason ?? 'Updates are not available.') : 'Automatic check is off.';
    case 'checking': return 'Checking for a new version…';
    case 'downloading': return `Downloading ${u.latest ?? 'new version'}${u.percent !== null ? ` (${u.percent}%)` : ''}…`;
    case 'ready': return `Version ${u.latest} is downloaded and verified. Restart the app to install it.`;
    case 'available': return `Version ${u.latest} is available. ${u.reason ?? ''}`.trim();
    case 'error': return `Check failed: ${u.message ?? 'unknown error'}`;
    default: return u.checkedAt ? `You have the latest version (checked ${new Date(u.checkedAt).toLocaleString('en-US')}).` : 'Not checked yet.';
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
    <Card title="Updates">
      <dl className="dl">
        <dt>Version</dt><dd>{state.current}</dd>
        <dt>State</dt><dd role="status">{updateLine(state)}</dd>
        {state.mode === 'notify' && state.reason && state.phase !== 'available' && <><dt>Method</dt><dd>{state.reason}</dd></>}
        {state.rollback && (
          <><dt>Daemon</dt><dd>Daemon version {state.rollback.failedVersion} did not start ({state.rollback.reason}); the previous version {state.rollback.usingVersion} is running.</dd></>
        )}
      </dl>
      <div className="actions" style={{ margin: '12px 0 4px' }}>
        <label className="check">
          <input type="checkbox" checked={settings.autoUpdate} disabled={state.mode === 'unavailable'} onChange={e => void update({ autoUpdate: e.target.checked })} /> Check for new versions automatically
        </label>
      </div>
      <p className="t2">
        The app only asks the GitHub releases page (Terrio-cz/CodeLoupe), sends nothing about you, and never asks on its own unless the switch is on. A downloaded installer is verified against the SHA-512 from the release.
      </p>
      <div className="actions">
        {state.phase === 'ready' && <button className="btn primary" onClick={() => void bridge().update.install()}>Restart and update</button>}
        {state.phase === 'available' && <button className="btn primary" onClick={() => void bridge().update.openRelease()}>Open release page</button>}
        <button className="btn" disabled={busy || state.mode === 'unavailable' || state.phase === 'ready'} onClick={() => void bridge().update.check().then(setState)}>Check now</button>
      </div>
    </Card>
  );
}
