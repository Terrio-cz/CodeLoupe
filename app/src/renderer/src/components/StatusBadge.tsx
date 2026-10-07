import type { DeclChangeKind, LayerState, RepoIndexState, RunStatus, StepFlag } from '../../../shared/contract';
import type { DaemonPhase } from '../../../shared/ipc';

export type Tone = 'ok' | 'warning' | 'serious' | 'critical' | 'neutral' | 'running';

/** State is always a dot plus a word; colour never carries it alone. */
export function StatusBadge({ tone, children }: { tone: Tone; children: React.ReactNode }) {
  return (
    <span className={`badge ${tone}`}>
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
export const LayerBadge = ({ state }: { state: LayerState }) => <StatusBadge tone={LAYER[state][0]}>{LAYER[state][1]}</StatusBadge>;

const REPO: Record<RepoIndexState, [Tone, string]> = {
  ready: ['ok', 'připraven'],
  building: ['running', 'build běží'],
  stale: ['warning', 'zastaralý'],
  error: ['critical', 'chyba'],
  none: ['neutral', 'bez indexu'],
};
export const RepoBadge = ({ state }: { state: RepoIndexState }) => <StatusBadge tone={REPO[state][0]}>{REPO[state][1]}</StatusBadge>;

const RUN: Record<RunStatus, [Tone, string]> = {
  running: ['running', 'běží'],
  done: ['ok', 'hotovo'],
  error: ['critical', 'chyba'],
};
export const RunBadge = ({ status }: { status: RunStatus }) => <StatusBadge tone={RUN[status][0]}>{RUN[status][1]}</StatusBadge>;

const PHASE: Record<DaemonPhase, [Tone, string]> = {
  unknown: ['neutral', 'zjišťuji'],
  starting: ['running', 'spouští se'],
  running: ['ok', 'běží'],
  stopping: ['running', 'zastavuje se'],
  stopped: ['neutral', 'zastaven'],
  down: ['critical', 'neodpovídá'],
  error: ['critical', 'chyba'],
};
export const PhaseBadge = ({ phase }: { phase: DaemonPhase }) => <StatusBadge tone={PHASE[phase][0]}>Daemon {PHASE[phase][1]}</StatusBadge>;

export function taskTone(state: string): Tone {
  if (state === 'Done') return 'ok';
  if (state === 'In Progress' || state === 'Review' || state === 'Ready for testing') return 'running';
  if (state === "Won't do") return 'neutral';
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

const FLAG: Record<StepFlag, string> = {
  large_result: '⚠ velký výsledek',
  gap: '⚑ mezera',
  error: '✕ chyba',
  codeloupe: '◆ CodeLoupe',
};
export const FlagChip = ({ flag }: { flag: StepFlag }) => <span className={`chip flag-${flag}`}>{FLAG[flag]}</span>;
