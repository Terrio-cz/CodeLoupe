import { useState } from 'react';
import type { RepositoriesAdded } from '../../../shared/onboardingActions';
import { bridge, useApi } from '../api';
import { YoutrackAccountDrawer } from '../components/AccountDrawers';
import { ClaudeCodeCard } from '../components/ClaudeCodeCard';
import { Card, Select } from '../components/Parts';
import { StatusBadge } from '../components/StatusBadge';

const STEPS = ['Repozitáře', 'YouTrack', 'Claude Code', 'Zkouška'] as const;

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
        <h1>Vítejte v CodeLoupe</h1>
        <button className="btn ghost" onClick={onFinish}>Přeskočit vše</button>
      </header>
      <ol className="steps" aria-label="Kroky průvodce">
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
        <button className="btn" disabled={step === 0} onClick={() => setStep(step - 1)}>Zpět</button>
        <span style={{ flex: 1 }} />
        {!last && <button className="btn" onClick={() => setStep(step + 1)}>Přeskočit krok</button>}
        <button className="btn primary" onClick={() => (last ? onFinish() : setStep(step + 1))}>{last ? 'Hotovo' : 'Další'}</button>
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
    <Card title="Které repozitáře má CodeLoupe indexovat?">
      <p className="t2">Vyberte složky s repozitáři (kde je .git). Zapíšou se do konfigurace daemona a hned se začnou indexovat na pozadí; složku vybíráte v okně systému, stránka žádnou cestu nepíše.</p>
      <div className="actions"><button className="btn primary" disabled={busy} onClick={() => void add()}>{busy ? 'Přidávám…' : 'Přidat repozitáře…'}</button></div>
      {result && (
        <div className={`banner${result.ok ? ' info' : ''}`} role={result.ok ? 'status' : 'alert'}>
          {result.message}
          {result.rejected.length > 0 && <ul className="plain">{result.rejected.map(r => <li key={r.path}><span className="mono">{r.path}</span>: {r.reason}</li>)}</ul>}
        </div>
      )}
      <div className="sublabel">Daemon zná</div>
      {repos.length === 0 ? <p className="t2">Zatím žádný repozitář.</p> : <ul className="plain">{repos.map(r => <li key={r.id} className="mono">{r.path}</li>)}</ul>}
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
    <Card title="YouTrack (volitelné)">
      <p className="t2">S tokenem CodeLoupe zrcadlí úkoly a agenti je čtou bez volání YouTrack. Token se uloží šifrovaně (klíč chrání systém: DPAPI, Keychain nebo libsecret) a okno ho po uložení nikdy neukáže. Přidání restartuje daemon.</p>
      <div className="actions"><button className="btn primary" onClick={() => setOpen(true)}>Přidat účet YouTrack…</button></div>
      {message && <div className="banner info" role="status">{message}</div>}
      {list.length > 0 && (
        <ul className="plain">
          {list.map(a => (
            <li key={a.id}>
              {a.label} <span className="mono muted">{a.url}</span> {a.tokenConfigured ? <StatusBadge tone="ok">token nastaven</StatusBadge> : <StatusBadge tone="warning">token chybí</StatusBadge>}{' '}
              {a.editable && <button className="btn" onClick={() => void test(a.id)} aria-label={`Otestovat spojení ${a.label}`}>Test</button>}
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
    <Card title="Zkouška: skutečný dotaz">
      <p className="t2">Pošle daemonu dotaz <span className="mono">outline</span> (mapa nejdůležitějších deklarací) na vybraný repozitář a ukáže jeho odpověď. Když se index ještě staví, odpověď to řekne; zkuste to za chvíli.</p>
      {repos.length === 0 ? <p className="t2">Nejdřív přidejte repozitář v prvním kroku.</p> : (
        <div className="actions">
          <Select label="Repozitář" value={chosen} onChange={setRepoId} options={repos.map(r => ({ value: r.id, label: r.path }))} />
          <button className="btn primary" disabled={busy || !chosen} onClick={() => void run()}>{busy ? 'Ptám se…' : 'Spustit dotaz'}</button>
        </div>
      )}
      {answer && <pre className={`mono${answer.ok ? '' : ' warn'}`} aria-live="polite">{answer.text}</pre>}
    </Card>
  );
}
