import { useState } from 'react';
import type { AccountOutcome } from '../../../shared/accountActions';
import type { Accounts as AccountsData } from '../../../shared/contract';
import { bridge, refreshAll, useApi } from '../api';
import { ClaudeAccountDrawer, RenameDrawer, TokenDrawer, YoutrackAccountDrawer } from '../components/AccountDrawers';
import { DataTable, type Column } from '../components/DataTable';
import { Icon } from '../components/Icon';
import { Banner, Card, ErrorState, Loading } from '../components/Parts';
import { StatusBadge, type Tone } from '../components/StatusBadge';
import { ago, pct, tokens } from '../format';

type ClaudeRow = AccountsData['claude'][number];
type YoutrackRow = AccountsData['youtrack'][number];

const MIRROR: Record<YoutrackRow['mirror']['state'], [Tone, string]> = {
  synced: ['ok', 'synchronní'],
  syncing: ['running', 'synchronizuje se'],
  error: ['critical', 'chyba'],
  off: ['neutral', 'vypnutý'],
};

type Panel =
  | { kind: 'claude-add' }
  | { kind: 'claude-rename'; id: string; label: string }
  | { kind: 'youtrack-add' }
  | { kind: 'youtrack-token'; id: string; label: string }
  | null;

export function Accounts() {
  const { data, error, loading, reload } = useApi('accounts');
  const [panel, setPanel] = useState<Panel>(null);
  const [notice, setNotice] = useState<AccountOutcome | null>(null);

  const done = (message: string) => { setPanel(null); setNotice({ ok: true, message }); refreshAll(); };
  const outcome = (r: AccountOutcome) => { setNotice(r); if (r.ok) refreshAll(); };
  const claude = bridge().accounts;

  const claudeColumns: Column<ClaudeRow>[] = [
    {
      key: 'account', header: 'Účet',
      render: a => (
        <span>
          {a.label}{' '}
          {a.isDefault && <StatusBadge tone="ok">výchozí</StatusBadge>}
          {a.email && <span className="muted" style={{ display: 'block' }}>{a.email}</span>}
        </span>
      ),
      className: 'two-line',
    },
    {
      key: 'dir', header: 'Složka (CLAUDE_CONFIG_DIR)',
      render: a => <span className="mono">{a.configDir}{!a.exists && <> <StatusBadge tone="warning">složka chybí</StatusBadge></>}{a.implicit && <span className="muted"> (implicitní)</span>}</span>,
      className: 'ellipsis',
    },
    { key: 'windows', header: 'Okna', render: a => a.windows, numeric: true },
    { key: 'cost', header: 'Cena 7 d', render: a => tokens(a.weighted7d), numeric: true },
    { key: 'saved', header: 'Úspora', render: a => (a.savedPct7d > 0 ? pct(a.savedPct7d) : '—'), numeric: true },
    { key: 'used', header: 'Naposledy', render: a => ago(a.lastUsedAt) },
    {
      key: 'actions', header: 'Akce',
      render: a => (
        <span className="row-actions">
          <button className="btn" aria-label={`Přejmenovat ${a.label}`} disabled={a.implicit} title={a.implicit ? 'Nejdřív přidejte účet; seznam se pak uloží' : undefined} onClick={() => setPanel({ kind: 'claude-rename', id: a.id, label: a.label })}>Přejmenovat</button>
          {!a.isDefault && <button className="btn" aria-label={`Nastavit ${a.label} jako výchozí`} onClick={() => void claude.claudeSetDefault(a.id).then(outcome)}>Výchozí</button>}
          <button className="btn" aria-label={`Odebrat ${a.label}`} disabled={a.implicit} onClick={() => void claude.claudeRemove(a.id).then(outcome)}>Odebrat</button>
        </span>
      ),
    },
  ];

  const youtrackColumns: Column<YoutrackRow>[] = [
    { key: 'instance', header: 'Instance', render: a => <span>{a.label}<span className="muted mono" style={{ display: 'block' }}>{a.url}</span></span>, className: 'two-line' },
    { key: 'projects', header: 'Projekty', render: a => a.projects.join(', ') },
    { key: 'token', header: 'Token', render: a => (a.tokenConfigured ? <StatusBadge tone="ok">nastaven</StatusBadge> : <StatusBadge tone="warning">chybí</StatusBadge>) },
    {
      key: 'mirror', header: 'Mirror',
      render: a => <><StatusBadge tone={MIRROR[a.mirror.state][0]}>{MIRROR[a.mirror.state][1]}</StatusBadge>{a.mirror.syncedAt && <span className="muted"> {ago(a.mirror.syncedAt)}</span>}</>,
    },
    {
      key: 'actions', header: 'Akce',
      render: a => a.editable ? (
        <span className="row-actions">
          <button className="btn" aria-label={`Otestovat spojení ${a.label}`} onClick={() => void claude.youtrackTest(a.id).then(setNotice)}>Test</button>
          <button className="btn" aria-label={`Rotovat token ${a.label}`} onClick={() => setPanel({ kind: 'youtrack-token', id: a.id, label: a.label })}>Rotovat token</button>
          <button className="btn" aria-label={`Odebrat ${a.label}`} onClick={() => void claude.youtrackRemove(a.id).then(outcome)}>Odebrat</button>
        </span>
      ) : <span className="muted" title="Tracker je v config.json daemona; aplikace ho needituje.">z config.json</span>,
    },
  ];

  return (
    <>
      {notice && <Banner tone={notice.ok ? 'info' : 'warning'} role={notice.ok ? 'status' : 'alert'}>{notice.message}</Banner>}
      <Card title="Claude účty" actions={<button className="btn primary" onClick={() => setPanel({ kind: 'claude-add' })}><Icon name="plus" size={14} />Přidat účet</button>} bodyClass="">
        {data ? <DataTable label="Claude účty" rows={data.claude} columns={claudeColumns} rowKey={a => a.id} empty="Žádný účet." />
          : loading ? <Loading variant="table" /> : <ErrorState message={error?.message ?? 'Nelze načíst účty.'} onRetry={reload} />}
      </Card>
      <Card title="YouTrack účty" actions={<button className="btn" onClick={() => setPanel({ kind: 'youtrack-add' })}><Icon name="plus" size={14} />Přidat účet</button>} bodyClass="">
        {data ? <DataTable label="YouTrack účty" rows={data.youtrack} columns={youtrackColumns} rowKey={a => a.id} empty="Žádný účet YouTrack. Přidejte instanci s tokenem." />
          : loading ? <Loading variant="table" /> : <ErrorState message={error?.message ?? 'Nelze načíst účty.'} onRetry={reload} />}
      </Card>
      <p className="footnote muted">Cena a okna se přiřazují účtu podle složky, ve které leží transcript. Tokeny jsou jen v šifrovaném úložišti; tato obrazovka je nikdy neukáže.</p>
      {panel?.kind === 'claude-add' && <ClaudeAccountDrawer onClose={() => setPanel(null)} onDone={done} />}
      {panel?.kind === 'claude-rename' && <RenameDrawer id={panel.id} label={panel.label} onClose={() => setPanel(null)} onDone={done} />}
      {panel?.kind === 'youtrack-add' && <YoutrackAccountDrawer onClose={() => setPanel(null)} onDone={done} />}
      {panel?.kind === 'youtrack-token' && <TokenDrawer id={panel.id} label={panel.label} onClose={() => setPanel(null)} onDone={done} />}
    </>
  );
}
