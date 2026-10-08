import { useState } from 'react';
import type { RepositoriesAdded } from '../../../shared/onboardingActions';
import { bridge, useApi } from '../api';
import { YoutrackAccountDrawer } from '../components/AccountDrawers';
import { ClaudeCodeCard } from '../components/ClaudeCodeCard';
import { Card, Select } from '../components/Parts';
import { StatusBadge } from '../components/StatusBadge';

const STEPS = ['Repositories', 'YouTrack', 'Claude Code', 'Test'] as const;

/**
 * First-run setup: repositories, a YouTrack account, the Claude Code connector and a real query. Every step can be skipped and
 * the whole flow can be left at any time; Settings opens it again. What it writes goes through main (folder dialog, CLI, the
 * accounts of the Accounts screen): this page holds no path to write and no token.
 */
export function Onboarding({ onFinish }: { onFinish(): void }) {
  const [step, setStep] = useState(0);
  const last = step === STEPS.length - 1;
  return (
    <div className="onboarding">
      <header className="onboarding-head">
        <h1>Welcome to CodeLoupe</h1>
        <button className="btn ghost" onClick={onFinish}>Skip all</button>
      </header>
      <ol className="steps" aria-label="Guide steps">
        {STEPS.map((title, i) => (
          <li key={title} aria-current={i === step ? 'step' : undefined} className={i < step ? 'done' : undefined}>
            <button className="btn ghost" onClick={() => setStep(i)}>{i + 1}. {title}</button>
          </li>
        ))}
      </ol>
      <main className="onboarding-body">
        {step === 0 && <Repositories />}
        {step === 1 && <Youtrack />}
        {step === 2 && <ClaudeCodeCard />}
        {step === 3 && <Trial />}
      </main>
      <footer className="onboarding-foot">
        <button className="btn" disabled={step === 0} onClick={() => setStep(step - 1)}>Back</button>
        <span style={{ flex: 1 }} />
        {!last && <button className="btn" onClick={() => setStep(step + 1)}>Skip step</button>}
        <button className="btn primary" onClick={() => (last ? onFinish() : setStep(step + 1))}>{last ? 'Done' : 'Next'}</button>
      </footer>
    </div>
  );
}

function Repositories() {
  const settings = useApi('settings');
  const [result, setResult] = useState<RepositoriesAdded | null>(null);
  const [busy, setBusy] = useState(false);
  const add = async () => {
    setBusy(true);
    try {
      const r = await bridge().onboarding.addRepositories();
      if (r !== 'cancelled') setResult(r);
      settings.reload();
    } finally {
      setBusy(false);
    }
  };
  const repos = settings.data?.repos ?? [];
  return (
    <Card title="Which repositories should CodeLoupe index?">
      <p className="t2">Pick folders with repositories (where .git is). They are written to the daemon's configuration and start indexing in the background right away; you pick the folder in a system dialog, the page writes no path.</p>
      <div className="actions"><button className="btn primary" disabled={busy} onClick={() => void add()}>{busy ? 'Adding…' : 'Add repositories…'}</button></div>
      {result && (
        <div className={`banner${result.ok ? ' info' : ''}`} role={result.ok ? 'status' : 'alert'}>
          {result.message}
          {result.rejected.length > 0 && <ul className="plain">{result.rejected.map(r => <li key={r.path}><span className="mono">{r.path}</span>: {r.reason}</li>)}</ul>}
        </div>
      )}
      <div className="sublabel">Known to the daemon</div>
      {repos.length === 0 ? <p className="t2">No repositories yet.</p> : <ul className="plain">{repos.map(r => <li key={r.id} className="mono">{r.path}</li>)}</ul>}
    </Card>
  );
}

function Youtrack() {
  const accounts = useApi('accounts');
  const [open, setOpen] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const list = accounts.data?.youtrack ?? [];
  const test = async (id: string) => setMessage((await bridge().accounts.youtrackTest(id)).message);
  return (
    <Card title="YouTrack (optional)">
      <p className="t2">With a token, CodeLoupe mirrors tasks and agents read them without calling YouTrack. The token is stored encrypted (the key is protected by the system: DPAPI, Keychain or libsecret) and the window never shows it after saving. Adding an account restarts the daemon.</p>
      <div className="actions"><button className="btn primary" onClick={() => setOpen(true)}>Add YouTrack account…</button></div>
      {message && <div className="banner info" role="status">{message}</div>}
      {list.length > 0 && (
        <ul className="plain">
          {list.map(a => (
            <li key={a.id}>
              {a.label} <span className="mono muted">{a.url}</span> {a.tokenConfigured ? <StatusBadge tone="ok">token set</StatusBadge> : <StatusBadge tone="warning">token missing</StatusBadge>}{' '}
              {a.editable && <button className="btn" onClick={() => void test(a.id)} aria-label={`Test connection ${a.label}`}>Test</button>}
            </li>
          ))}
        </ul>
      )}
      {open && <YoutrackAccountDrawer onClose={() => setOpen(false)} onDone={m => { setOpen(false); setMessage(m); accounts.reload(); }} />}
    </Card>
  );
}

function Trial() {
  const settings = useApi('settings');
  const repos = settings.data?.repos ?? [];
  const [repoId, setRepoId] = useState('');
  const [answer, setAnswer] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const chosen = repoId || repos[0]?.id || '';
  const run = async () => {
    setBusy(true);
    setAnswer(null);
    try {
      setAnswer(await bridge().onboarding.query(chosen));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card title="Test: a real query">
      <p className="t2">Sends the daemon an <span className="mono">outline</span> query (a map of the most important declarations) for the chosen repository and shows its answer. If the index is still building, the answer says so; try again in a moment.</p>
      {repos.length === 0 ? <p className="t2">Add a repository in the first step first.</p> : (
        <div className="actions">
          <Select label="Repository" value={chosen} onChange={setRepoId} options={repos.map(r => ({ value: r.id, label: r.path }))} />
          <button className="btn primary" disabled={busy || !chosen} onClick={() => void run()}>{busy ? 'Querying…' : 'Run query'}</button>
        </div>
      )}
      {answer && <pre className={`mono${answer.ok ? '' : ' warn'}`} aria-live="polite">{answer.text}</pre>}
    </Card>
  );
}
