import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';
import { ACTION_CH } from '../shared/actions';
import { ENV_CH } from '../shared/envActions';
import { JOB_CH, type LiveEvent } from '../shared/jobs';
import { CH, type CodeLoupeBridge, type DaemonState } from '../shared/ipc';

// The renderer's only access to anything outside the page; every call is validated again in main.
const bridge: CodeLoupeBridge = {
  api: req => ipcRenderer.invoke(CH.api, req),
  daemon: {
    state: () => ipcRenderer.invoke(CH.daemonState),
    start: () => ipcRenderer.invoke(CH.daemonStart),
    stop: () => ipcRenderer.invoke(CH.daemonStop),
    restart: () => ipcRenderer.invoke(CH.daemonRestart),
    onState: cb => {
      const listener = (_e: IpcRendererEvent, s: DaemonState) => cb(s);
      ipcRenderer.on(CH.daemonPush, listener);
      return () => ipcRenderer.removeListener(CH.daemonPush, listener);
    },
  },
  settings: {
    get: () => ipcRenderer.invoke(CH.settingsGet),
    set: s => ipcRenderer.invoke(CH.settingsSet, s),
    proposeCli: (command, args) => ipcRenderer.invoke(CH.settingsProposeCli, command, args),
  },
  claude: {
    status: () => ipcRenderer.invoke(CH.claudeStatus),
    connect: kind => ipcRenderer.invoke(CH.claudeConnect, kind),
    manual: kind => ipcRenderer.invoke(CH.claudeManual, kind),
  },
  actions: {
    gapsRefresh: () => ipcRenderer.invoke(ACTION_CH.gapsRefresh),
    workspaceRelease: req => ipcRenderer.invoke(ACTION_CH.workspaceRelease, req),
    reconcileRun: req => ipcRenderer.invoke(ACTION_CH.reconcileRun, req),
  },
  jobs: {
    log: id => ipcRenderer.invoke(JOB_CH.log, id),
  },
  live: {
    subscribe: cb => {
      const listener = (_e: IpcRendererEvent, event: LiveEvent) => cb(event);
      ipcRenderer.on(JOB_CH.livePush, listener);
      void ipcRenderer.invoke(JOB_CH.liveStart);
      return () => {
        ipcRenderer.removeListener(JOB_CH.livePush, listener);
        void ipcRenderer.invoke(JOB_CH.liveStop);
      };
    },
  },
  env: {
    capabilities: () => ipcRenderer.invoke(ENV_CH.capabilities),
    set: input => ipcRenderer.invoke(ENV_CH.set, input),
    remove: key => ipcRenderer.invoke(ENV_CH.remove, key),
    scan: includeExcluded => ipcRenderer.invoke(ENV_CH.scan, includeExcluded),
    importRun: input => ipcRenderer.invoke(ENV_CH.importRun, input),
    rollback: id => ipcRenderer.invoke(ENV_CH.rollback, id),
    reveal: key => ipcRenderer.invoke(ENV_CH.reveal, key),
  },
  metrics: () => ipcRenderer.invoke(CH.metrics),
  open: {
    worktree: id => ipcRenderer.invoke(CH.openWorktree, id),
    config: () => ipcRenderer.invoke(CH.openConfig),
    external: url => ipcRenderer.invoke(CH.openExternal, url),
  },
  onNavigate: cb => {
    const listener = (_e: IpcRendererEvent, hash: string) => cb(hash);
    ipcRenderer.on(CH.navigate, listener);
    return () => ipcRenderer.removeListener(CH.navigate, listener);
  },
};

contextBridge.exposeInMainWorld('codeloupe', bridge);
