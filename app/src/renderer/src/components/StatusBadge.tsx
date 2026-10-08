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
  fresh: ['ok', 'čerstvá'],
  stale: ['warning', 'zastaralá'],
  building: ['running', 'parsuje se'],
  error: ['critical', 'chyba'],
  none: ['neutral', 'bez vrstvy'],
};
export const LayerBadge = ({ state }: { state: LayerState }) => <StatusBadge tone={LAYER[state][0]} live={state === 'building'}>{LAYER[state][1]}</StatusBadge>;

const REPO: Record<RepoIndexState, [Tone, string]> = {
  ready: ['ok', 'připraven'],
  building: ['running', 'build běží'],
  stale: ['warning', 'zastaralý'],
  error: ['critical', 'chyba'],
  none: ['neutral', 'bez indexu'],
};
export const RepoBadge = ({ state }: { state: RepoIndexState }) => <StatusBadge tone={REPO[state][0]} live={state === 'building'}>{REPO[state][1]}</StatusBadge>;

const PHASE: Record<DaemonPhase, [Tone, string]> = {
  unknown: ['neutral', 'zjišťuji'],
  starting: ['running', 'spouští se'],
  running: ['ok', 'běží'],
  stopping: ['running', 'zastavuje se'],
  stopped: ['neutral', 'zastaven'],
  down: ['critical', 'neodpovídá'],
  error: ['critical', 'chyba'],
};
export const PhaseBadge = ({ phase }: { phase: DaemonPhase }) => <StatusBadge tone={PHASE[phase][0]} live={phase === 'running' || phase === 'starting' || phase === 'stopping'}>Daemon {PHASE[phase][1]}</StatusBadge>;

export function taskTone(state: string): Tone {
  if (state === 'Done') return 'ok';
  if (state === 'In Progress' || state === 'Review' || state === 'Ready for testing') return 'running';
  return 'neutral';
}

const CHANGE: Record<DeclChangeKind, [string, string]> = {
  added: ['+', 'přidaná'],
  body: ['~', 'upravené tělo'],
  signature: ['^', 'změněná signatura'],
  removed: ['-', 'odstraněná'],
};
export function ChangeMark({ change }: { change: DeclChangeKind }) {
  return (
    <span className={`mark ${change}`} title={CHANGE[change][1]} aria-label={CHANGE[change][1]} role="img">
      {CHANGE[change][0]}
    </span>
  );
}

const WORKSPACE: Record<WorkspaceState | 'gone', [Tone, string]> = {
  active: ['running', 'aktivní'],
  landed: ['ok', 'dokončený'],
  abandoned: ['warning', 'opuštěný'],
  orphan: ['serious', 'sirotek'],
  gone: ['neutral', 'chybí v registru'],
};
export const WorkspaceBadge = ({ state }: { state: WorkspaceState | 'gone' }) => <StatusBadge tone={WORKSPACE[state][0]}>{WORKSPACE[state][1]}</StatusBadge>;

const VERDICT: Record<Verdict, [Tone, string]> = {
  auto: ['running', 'uklidí se samo'],
  confirm: ['warning', 'čeká na potvrzení'],
  keep: ['neutral', 'zůstane'],
  protected: ['neutral', 'chráněný'],
};
export const VerdictBadge = ({ verdict }: { verdict: Verdict }) => <StatusBadge tone={VERDICT[verdict][0]}>{VERDICT[verdict][1]}</StatusBadge>;
