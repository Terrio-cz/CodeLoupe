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
  metrics: 'cl:metrics',
  openWorktree: 'cl:open:worktree',
  openConfig: 'cl:open:config',
  openExternal: 'cl:open:external',
  navigate: 'cl:navigate',
} as const;
