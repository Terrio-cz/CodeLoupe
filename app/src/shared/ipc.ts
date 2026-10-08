import type { DaemonStatus } from './contract';
import type { AppSettings, RendererSettings } from './settings';
import type { ApiRequest } from './request';

export type DaemonPhase = 'unknown' | 'starting' | 'running' | 'stopping' | 'stopped' | 'down' | 'error';

export interface DaemonState {
  phase: DaemonPhase;
  status: DaemonStatus | null;
  port: number;
  /** Last problem in plain words (CLI missing, start failed, foreign process on the port, …). */
  message: string | null;
  /** Stopped by the user (app or CLI marker): no automatic start. */
  manualStop: boolean;
  checkedAt: string | null;
}

export type ApiResult<T> = { ok: true; data: T } | { ok: false; code: string; message: string };

export interface AppMetrics {
  /** Sum of working sets (shared pages counted per process: an upper bound). */
  totalMb: number;
  /** Sum of private bytes (Windows; 0 elsewhere). */
  privateMb: number;
  processes: { type: string; mb: number }[];
  version: string;
  electron: string;
}

export type ClaudeConnectKind = 'mcp' | 'plugin';

export interface ClaudeStatus {
  /** The claude CLI was found and runs. */
  cli: boolean;
  /** A user-visible MCP server named codeloupe exists. */
  mcp: boolean;
  /** The codeloupe plugin is installed. */
  plugin: boolean;
}

export interface ClaudeConnectResult {
  ok: boolean;
  /** One line for the user: what happened, or why not. */
  message: string;
  /** The same steps as shell commands, for doing it by hand. */
  manual: string[];
}

export type UpdatePhase = 'off' | 'idle' | 'checking' | 'available' | 'downloading' | 'ready' | 'error';

/** What the app knows about updates (CL-107). */
export interface UpdateState {
  phase: UpdatePhase;
  /** `install`: this installation updates itself; `notify`: it only says a release exists; `unavailable`: not a packaged app. */
  mode: 'install' | 'notify' | 'unavailable';
  /** Why the mode is not `install`, in words for the user. */
  reason: string | null;
  current: string;
  /** The newer version found, if any. */
  latest: string | null;
  /** The release page of `latest`. */
  releaseUrl: string | null;
  /** Download progress 0-100 while `downloading`. */
  percent: number | null;
  message: string | null;
  checkedAt: string | null;
  /** Set when the daemon of this version failed to start and the previous one runs instead. */
  rollback: { failedVersion: string; usingVersion: string; reason: string } | null;
}

/** The only surface the renderer gets (preload contextBridge). */
export interface CodeLoupeBridge {
  api<T = unknown>(req: ApiRequest): Promise<ApiResult<T>>;
  daemon: {
    state(): Promise<DaemonState>;
    start(): Promise<DaemonState>;
    stop(): Promise<DaemonState>;
    restart(): Promise<DaemonState>;
    onState(cb: (s: DaemonState) => void): () => void;
  };
  settings: {
    get(): Promise<AppSettings>;
    set(s: Partial<RendererSettings>): Promise<AppSettings>;
    /** Main asks the user in a native dialog; resolves with the settings after the answer. */
    proposeCli(command: string, args: string[]): Promise<AppSettings>;
  };
  claude: {
    status(): Promise<ClaudeStatus>;
    /** Main asks the user in a native dialog first; resolves with 'cancelled' when they decline. */
    connect(kind: ClaudeConnectKind): Promise<ClaudeConnectResult | 'cancelled'>;
    /** The commands for doing it by hand. */
    manual(kind: ClaudeConnectKind): Promise<string[]>;
  };
  update: {
    state(): Promise<UpdateState>;
    /** Looks for a newer release now (also when automatic checks are off). */
    check(): Promise<UpdateState>;
    /** Restarts into the downloaded update. */
    install(): Promise<void>;
    /** Opens the release page of the newer version in the browser. */
    openRelease(): Promise<boolean>;
    onState(cb: (s: UpdateState) => void): () => void;
  };
  metrics(): Promise<AppMetrics>;
  open: {
    worktree(id: string): Promise<boolean>;
    config(): Promise<boolean>;
    /** https only, origin must equal a YouTrack instance from the daemon settings. */
    external(url: string): Promise<boolean>;
  };
  onNavigate(cb: (hash: string) => void): () => void;
}

export const CH = {
  api: 'cl:api',
  daemonState: 'cl:daemon:state',
  daemonStart: 'cl:daemon:start',
  daemonStop: 'cl:daemon:stop',
  daemonRestart: 'cl:daemon:restart',
  daemonPush: 'cl:daemon:push',
  settingsGet: 'cl:settings:get',
  settingsSet: 'cl:settings:set',
  settingsProposeCli: 'cl:settings:propose-cli',
  claudeStatus: 'cl:claude:status',
  claudeConnect: 'cl:claude:connect',
  claudeManual: 'cl:claude:manual',
  updateState: 'cl:update:state',
  updateCheck: 'cl:update:check',
  updateInstall: 'cl:update:install',
  updateRelease: 'cl:update:release',
  updatePush: 'cl:update:push',
  metrics: 'cl:metrics',
  openWorktree: 'cl:open:worktree',
  openConfig: 'cl:open:config',
  openExternal: 'cl:open:external',
  navigate: 'cl:navigate',
} as const;
