import { useCallback, useEffect, useState } from 'react';
import type { ClaudeConnectKind, ClaudeStatus } from '../../../shared/ipc';
import { bridge } from '../api';
import { Card, Toast } from './Parts';

const yes = (ok: boolean, on: string, off: string) => (ok ? `${on} ✓` : off);

/** "Connect to Claude Code": adds the MCP server or installs the plugin through the claude CLI after a native confirmation. */
export function ClaudeCodeCard() {
  const [status, setStatus] = useState<ClaudeStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [manual, setManual] = useState<string[]>([]);

  const refresh = useCallback(() => void bridge().claude.status().then(setStatus), []);
  useEffect(refresh, [refresh]);

  const connect = async (kind: ClaudeConnectKind) => {
    setBusy(true);
    setMessage(null);
    try {
      const r = await bridge().claude.connect(kind);
      if (r === 'cancelled') return;
      setMessage(r.message);
      setManual(r.ok ? [] : r.manual);
    } catch (e) {
      setMessage((e as Error).message);
      setManual(await bridge().claude.manual(kind).catch(() => []));
    } finally {
      setBusy(false);
      refresh();
    }
  };

  return (
    <Card title="Claude Code">
      <dl className="dl">
        <dt>Příkaz claude</dt><dd>{status ? (status.cli ? 'nalezen ✓' : 'nenalezen — příkazy níže spusťte ručně') : '…'}</dd>
        <dt>MCP server</dt><dd>{status ? yes(status.mcp, 'přidán', 'nepřidán') : '…'}</dd>
        <dt>Plugin</dt><dd>{status ? yes(status.plugin, 'nainstalován', 'nenainstalován') : '…'}</dd>
      </dl>
      <p className="t2">
        Plugin přidá MCP server, skill a spouštění daemonu při startu relace. Samotný MCP server je jednodušší varianta
        bez skillu a bez automatického startu. Obojí až po potvrzení; zápis provede příkaz claude, aplikace jeho konfiguraci neupravuje.
      </p>
      <div className="actions">
        <button className="btn primary" disabled={busy || status?.plugin} onClick={() => void connect('plugin')}>Připojit plugin…</button>
        <button className="btn" disabled={busy || status?.mcp} onClick={() => void connect('mcp')}>Přidat jen MCP server…</button>
      </div>
      {message && <Toast>{message}</Toast>}
      {manual.length > 0 && <pre className="mono">{manual.join('\n')}</pre>}
    </Card>
  );
}
