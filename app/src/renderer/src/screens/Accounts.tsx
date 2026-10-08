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
  synced: ['ok', 'synced'],
  syncing: ['running', 'syncing'],
  error: ['critical', 'error'],
  off: ['neutral', 'off'],
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
      key: 'account', header: 'Account',
      render: a => (
        <span>
          {a.label}{' '}
          {a.isDefault && <StatusBadge tone="ok">default</StatusBadge>}
          {a.email && <span className="muted" style={{ display: 'block' }}>{a.email}</span>}
        </span>
      ),
      className: 'two-line',
    },
    {
      key: 'dir', header: 'Folder (CLAUDE_CONFIG_DIR)',
      render: a => <span className="mono">{a.configDir}{!a.exists && <> <StatusBadge tone="warning">folder missing</StatusBadge></>}{a.implicit && <span className="muted"> (implicit)</span>}</span>,
      className: 'ellipsis',
    },
    { key: 'windows', header: 'Windows', render: a => a.windows, numeric: true },
    { key: 'cost', header: 'Cost 7d', render: a => tokens(a.weighted7d), numeric: true },
    { key: 'saved', header: 'Saved', render: a => (a.savedPct7d > 0 ? pct(a.savedPct7d) : '—'), numeric: true },
    { key: 'used', header: 'Last used', render: a => ago(a.lastUsedAt) },
    {
      key: 'actions', header: 'Actions',
      render: a => (
        <span className="row-actions">
          <button className="btn" aria-label={`Rename ${a.label}`} disabled={a.implicit} title={a.implicit ? 'Add an account first; the list is then saved' : undefined} onClick={() => setPanel({ kind: 'claude-rename', id: a.id, label: a.label })}>Rename</button>
          {!a.isDefault && <button className="btn" aria-label={`Set ${a.label} as default`} onClick={() => void claude.claudeSetDefault(a.id).then(outcome)}>Make default</button>}
          <button className="btn" aria-label={`Remove ${a.label}`} disabled={a.implicit} onClick={() => void claude.claudeRemove(a.id).then(outcome)}>Remove</button>
        </span>
      ),
    },
  ];

  const youtrackColumns: Column<YoutrackRow>[] = [
    { key: 'instance', header: 'Instance', render: a => <span>{a.label}<span className="muted mono" style={{ display: 'block' }}>{a.url}</span></span>, className: 'two-line' },
    { key: 'projects', header: 'Projects', render: a => a.projects.join(', ') },
    { key: 'token', header: 'Token', render: a => (a.tokenConfigured ? <StatusBadge tone="ok">set</StatusBadge> : <StatusBadge tone="warning">missing</StatusBadge>) },
    {
      key: 'mirror', header: 'Mirror',
      render: a => <><StatusBadge tone={MIRROR[a.mirror.state][0]}>{MIRROR[a.mirror.state][1]}</StatusBadge>{a.mirror.syncedAt && <span className="muted"> {ago(a.mirror.syncedAt)}</span>}</>,
    },
    {
      key: 'actions', header: 'Actions',
      render: a => a.editable ? (
        <span className="row-actions">
          <button className="btn" aria-label={`Test connection ${a.label}`} onClick={() => void claude.youtrackTest(a.id).then(setNotice)}>Test</button>
          <button className="btn" aria-label={`Rotate token ${a.label}`} onClick={() => setPanel({ kind: 'youtrack-token', id: a.id, label: a.label })}>Rotate token</button>
          <button className="btn" aria-label={`Remove ${a.label}`} onClick={() => void claude.youtrackRemove(a.id).then(outcome)}>Remove</button>
        </span>
      ) : <span className="muted" title="The tracker is in the daemon's config.json; the app does not edit it.">from config.json</span>,
    },
  ];

  return (
    <>
      {notice && <Banner tone={notice.ok ? 'info' : 'warning'} role={notice.ok ? 'status' : 'alert'}>{notice.message}</Banner>}
      <Card title="Claude accounts" actions={<button className="btn primary" onClick={() => setPanel({ kind: 'claude-add' })}><Icon name="plus" size={14} />Add account</button>} bodyClass="">
        {data ? <DataTable label="Claude accounts" rows={data.claude} columns={claudeColumns} rowKey={a => a.id} empty="No accounts." />
          : loading ? <Loading variant="table" /> : <ErrorState message={error?.message ?? 'Could not load accounts.'} onRetry={reload} />}
      </Card>
      <Card title="YouTrack accounts" actions={<button className="btn" onClick={() => setPanel({ kind: 'youtrack-add' })}><Icon name="plus" size={14} />Add account</button>} bodyClass="">
        {data ? <DataTable label="YouTrack accounts" rows={data.youtrack} columns={youtrackColumns} rowKey={a => a.id} empty="No YouTrack accounts. Add an instance with a token." />
          : loading ? <Loading variant="table" /> : <ErrorState message={error?.message ?? 'Could not load accounts.'} onRetry={reload} />}
      </Card>
      <p className="footnote muted">Cost and windows are assigned to an account by the folder the transcript lives in. Tokens are kept only in the encrypted store; this screen never shows them.</p>
      {panel?.kind === 'claude-add' && <ClaudeAccountDrawer onClose={() => setPanel(null)} onDone={done} />}
      {panel?.kind === 'claude-rename' && <RenameDrawer id={panel.id} label={panel.label} onClose={() => setPanel(null)} onDone={done} />}
      {panel?.kind === 'youtrack-add' && <YoutrackAccountDrawer onClose={() => setPanel(null)} onDone={done} />}
      {panel?.kind === 'youtrack-token' && <TokenDrawer id={panel.id} label={panel.label} onClose={() => setPanel(null)} onDone={done} />}
    </>
  );
}
