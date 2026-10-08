import type { DeclChangeKind, LayerState, RepoIndexState } from '../../../shared/contract';
import type { DaemonPhase } from '../../../shared/ipc';
import type { Verdict, WorkspaceState } from '../../../shared/workspaces';

export type Tone = 'ok' | 'warning' | 'serious' | 'critical' | 'neutral' | 'running';

/** State is always a dot plus a word; colour never carries it alone. */
export function StatusBadge({ tone, live, children }: { tone: Tone; live?: boolean; children: React.ReactNode }) {
  return (
    <span className={`badge ${tone}${live ? ' live' : ''}`}>
      <span className="dot" aria-hidden="true" />
      {children}
    </span>
  );
}

const LAYER: Record<LayerState, [Tone, string]> = {
  fresh: ['ok', 'fresh'],
  stale: ['warning', 'stale'],
  building: ['running', 'parsing'],
  error: ['critical', 'error'],
  none: ['neutral', 'no layer'],
};
export const LayerBadge = ({ state }: { state: LayerState }) => <StatusBadge tone={LAYER[state][0]} live={state === 'building'}>{LAYER[state][1]}</StatusBadge>;

const REPO: Record<RepoIndexState, [Tone, string]> = {
  ready: ['ok', 'ready'],
  building: ['running', 'building'],
  stale: ['warning', 'stale'],
  error: ['critical', 'error'],
  none: ['neutral', 'no index'],
};
export const RepoBadge = ({ state }: { state: RepoIndexState }) => <StatusBadge tone={REPO[state][0]} live={state === 'building'}>{REPO[state][1]}</StatusBadge>;

const PHASE: Record<DaemonPhase, [Tone, string]> = {
  unknown: ['neutral', 'state unknown'],
  starting: ['running', 'starting'],
  running: ['ok', 'running'],
  stopping: ['running', 'stopping'],
  stopped: ['neutral', 'stopped'],
  down: ['critical', 'not responding'],
  error: ['critical', 'error'],
};
export const PhaseBadge = ({ phase }: { phase: DaemonPhase }) => <StatusBadge tone={PHASE[phase][0]} live={phase === 'running' || phase === 'starting' || phase === 'stopping'}>Daemon {PHASE[phase][1]}</StatusBadge>;

export function taskTone(state: string): Tone {
  if (state === 'Done') return 'ok';
  if (state === 'In Progress' || state === 'Review' || state === 'Ready for testing') return 'running';
  return 'neutral';
}

const CHANGE: Record<DeclChangeKind, [string, string]> = {
  added: ['+', 'added'],
  body: ['~', 'body changed'],
  signature: ['^', 'signature changed'],
  removed: ['-', 'removed'],
};
export function ChangeMark({ change }: { change: DeclChangeKind }) {
  return (
    <span className={`mark ${change}`} title={CHANGE[change][1]} aria-label={CHANGE[change][1]} role="img">
      {CHANGE[change][0]}
    </span>
  );
}

const WORKSPACE: Record<WorkspaceState | 'gone', [Tone, string]> = {
  active: ['running', 'active'],
  landed: ['ok', 'landed'],
  abandoned: ['warning', 'abandoned'],
  orphan: ['serious', 'orphan'],
  gone: ['neutral', 'missing from registry'],
};
export const WorkspaceBadge = ({ state }: { state: WorkspaceState | 'gone' }) => <StatusBadge tone={WORKSPACE[state][0]}>{WORKSPACE[state][1]}</StatusBadge>;

const VERDICT: Record<Verdict, [Tone, string]> = {
  auto: ['running', 'cleans up automatically'],
  confirm: ['warning', 'awaiting confirmation'],
  keep: ['neutral', 'kept'],
  protected: ['neutral', 'protected'],
};
export const VerdictBadge = ({ verdict }: { verdict: Verdict }) => <StatusBadge tone={VERDICT[verdict][0]}>{VERDICT[verdict][1]}</StatusBadge>;
