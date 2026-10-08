import { useRef, useState, type ReactNode } from 'react';
import type { AccountOutcome } from '../../../shared/accountActions';
import { bridge } from '../api';
import { Drawer } from './Drawer';

interface FormProps {
  title: string;
  subtitle?: string;
  submit: string;
  busy: boolean;
  error: string | null;
  disabled?: boolean;
  onSubmit(): void;
  onClose(): void;
  children: ReactNode;
}

function Form({ title, subtitle, submit, busy, error, disabled, onSubmit, onClose, children }: FormProps) {
  return (
    <Drawer title={title} subtitle={subtitle} onClose={onClose}>
      <form className="key-form" autoComplete="off" onSubmit={e => { e.preventDefault(); onSubmit(); }}>
        {children}
        {error && <div className="banner" role="alert">{error}</div>}
        <div className="drawer-actions">
          <button type="submit" className="btn primary" disabled={busy || disabled}>{busy ? 'Working…' : submit}</button>
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
        </div>
      </form>
    </Drawer>
  );
}

/** Runs [action], keeps the drawer open with the reason on failure and reports the one-line result on success. */
function useSubmit(onDone: (message: string) => void) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async (action: () => Promise<AccountOutcome>) => {
    setBusy(true);
    setError(null);
    const outcome = await action();
    setBusy(false);
    if (outcome.ok) onDone(outcome.message);
    else setError(outcome.message);
  };
  return { busy, error, run };
}

const TOKEN_NOTE = 'The token is stored encrypted and never shown anywhere after saving; the field is cleared on submit.';

function TokenField({ field, label }: { field: React.RefObject<HTMLInputElement | null>; label: string }) {
  return (
    <div className="form-row">
      <label className="label" htmlFor="acct-token">{label}</label>
      <input id="acct-token" ref={field} className="input mono" type="password" autoComplete="new-password" spellCheck={false} autoCapitalize="off" autoCorrect="off" required aria-describedby="acct-token-note" />
      <span id="acct-token-note" className="muted" style={{ gridColumn: '2' }}>{TOKEN_NOTE}</span>
    </div>
  );
}

export function ClaudeAccountDrawer({ onClose, onDone }: { onClose(): void; onDone(message: string): void }) {
  const [label, setLabel] = useState('');
  const [configDir, setConfigDir] = useState('');
  const [create, setCreate] = useState(false);
  const { busy, error, run } = useSubmit(onDone);
  return (
    <Form title="Add Claude account" subtitle="An account is a Claude Code settings folder (CLAUDE_CONFIG_DIR). You sign in to it in Claude Code; CodeLoupe never sees the login." submit="Add" busy={busy} error={error}
      disabled={!label.trim() || !configDir.trim()} onSubmit={() => void run(() => bridge().accounts.claudeAdd({ label, configDir, create }))} onClose={onClose}>
      <div className="form-row">
        <label className="label" htmlFor="acct-label">Name</label>
        <input id="acct-label" className="input" value={label} onChange={e => setLabel(e.target.value)} maxLength={60} required placeholder="e.g. Account B (work)" autoComplete="off" />
      </div>
      <div className="form-row">
        <label className="label" htmlFor="acct-dir">Folder</label>
        <input id="acct-dir" className="input mono" value={configDir} onChange={e => setConfigDir(e.target.value)} required placeholder="C:/Users/me/.claude-b" autoComplete="off" spellCheck={false} />
      </div>
      <label className="check"><input type="checkbox" checked={create} onChange={e => setCreate(e.target.checked)} /> Create the folder if it does not exist</label>
    </Form>
  );
}

export function RenameDrawer({ id, label: current, onClose, onDone }: { id: string; label: string; onClose(): void; onDone(message: string): void }) {
  const [label, setLabel] = useState(current);
  const { busy, error, run } = useSubmit(onDone);
  return (
    <Form title={`Rename ${current}`} submit="Save" busy={busy} error={error} disabled={!label.trim()} onSubmit={() => void run(() => bridge().accounts.claudeRename(id, label))} onClose={onClose}>
      <div className="form-row">
        <label className="label" htmlFor="acct-label">Name</label>
        <input id="acct-label" className="input" value={label} onChange={e => setLabel(e.target.value)} maxLength={60} required autoComplete="off" />
      </div>
    </Form>
  );
}

export function YoutrackAccountDrawer({ onClose, onDone }: { onClose(): void; onDone(message: string): void }) {
  const [label, setLabel] = useState('');
  const [url, setUrl] = useState('https://');
  const [projects, setProjects] = useState('');
  const token = useRef<HTMLInputElement>(null);
  const { busy, error, run } = useSubmit(onDone);
  const submit = () => {
    const secret = token.current?.value ?? '';
    if (token.current) token.current.value = '';
    void run(() => bridge().accounts.youtrackAdd({ label, url, projects: projects.split(/[\s,;]+/).filter(Boolean), token: secret }));
  };
  return (
    <Form title="Add YouTrack account" subtitle="Adding restarts the daemon so it can mirror the new instance." submit="Add" busy={busy} error={error} disabled={!label.trim() || !url.trim() || !projects.trim()} onSubmit={submit} onClose={onClose}>
      <div className="form-row">
        <label className="label" htmlFor="acct-label">Name</label>
        <input id="acct-label" className="input" value={label} onChange={e => setLabel(e.target.value)} maxLength={60} required placeholder="e.g. Terrio" autoComplete="off" />
      </div>
      <div className="form-row">
        <label className="label" htmlFor="acct-url">Instance URL</label>
        <input id="acct-url" className="input mono" value={url} onChange={e => setUrl(e.target.value)} required placeholder="https://company.youtrack.cloud" autoComplete="off" spellCheck={false} />
      </div>
      <div className="form-row">
        <label className="label" htmlFor="acct-projects">Projects</label>
        <input id="acct-projects" className="input mono" value={projects} onChange={e => setProjects(e.target.value)} required placeholder="TER, CL" autoComplete="off" spellCheck={false} />
      </div>
      <TokenField field={token} label="Token" />
    </Form>
  );
}

export function TokenDrawer({ id, label, onClose, onDone }: { id: string; label: string; onClose(): void; onDone(message: string): void }) {
  const token = useRef<HTMLInputElement>(null);
  const { busy, error, run } = useSubmit(onDone);
  const submit = () => {
    const secret = token.current?.value ?? '';
    if (token.current) token.current.value = '';
    void run(() => bridge().accounts.youtrackRotate(id, secret));
  };
  return (
    <Form title={`Rotate token: ${label}`} subtitle="The old token is not shown." submit="Rotate" busy={busy} error={error} onSubmit={submit} onClose={onClose}>
      <TokenField field={token} label="New token" />
    </Form>
  );
}
