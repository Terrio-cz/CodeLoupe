import { useCallback, useEffect, useState } from 'react';
import type { ClaudeConnectKind, ClaudeStatus } from '../../../shared/ipc';
import { bridge } from '../api';
import { Card, Toast } from './Parts';

const yes = (ok: boolean, on: string, off: string) => (ok ? `${on} ✓` : off);
// The claude CLI answers in a second or two; until then a quiet word, not an ellipsis that looks like a value.
const Checking = () => <span className="muted">checking…</span>;

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
        <dt>claude command</dt><dd>{status ? (status.cli ? 'found ✓' : 'not found — run the commands below by hand') : <Checking />}</dd>
        <dt>MCP server</dt><dd>{status ? yes(status.mcp, 'added', 'not added') : <Checking />}</dd>
        <dt>Plugin</dt><dd>{status ? yes(status.plugin, 'installed', 'not installed') : <Checking />}</dd>
      </dl>
      <p className="t2">
        The plugin adds the MCP server, a skill, and starts the daemon when a session starts. The MCP server alone is the simpler option,
        without the skill or auto-start. Both ask for confirmation first; the claude command makes the change, the app never edits its configuration.
      </p>
      <div className="actions">
        <button className="btn primary" disabled={busy || status?.plugin} onClick={() => void connect('plugin')}>Connect plugin…</button>
        <button className="btn" disabled={busy || status?.mcp} onClick={() => void connect('mcp')}>Add MCP server only…</button>
      </div>
      {message && <Toast>{message}</Toast>}
      {manual.length > 0 && <pre className="mono">{manual.join('\n')}</pre>}
    </Card>
  );
}
