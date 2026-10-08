import { Menu, Tray } from 'electron';
import type { DaemonState } from '../shared/ipc';
import { glyphImage, type Glyph } from './icons';

export interface TrayActions {
  open(): void;
  start(): void;
  stop(): void;
  restart(): void;
  toggleLogin(): void;
  quit(): void;
  openAtLogin(): boolean;
}

const PHASE_TEXT: Record<DaemonState['phase'], string> = {
  unknown: 'checking',
  starting: 'starting',
  running: 'running',
  stopping: 'stopping',
  stopped: 'stopped',
  down: 'not responding',
  error: 'error',
};

export function glyphFor(s: DaemonState): Glyph {
  if (s.phase === 'running') return s.status?.queue.heavy.running ? 'building' : 'running';
  if (s.phase === 'starting' || s.phase === 'stopping') return 'building';
  if (s.phase === 'stopped' || s.phase === 'unknown') return 'stopped';
  return 'down';
}

/** One line with the daemon state in words: tray tooltip and the first menu row. */
export function stateLine(s: DaemonState): string {
  const parts = [`Daemon ${PHASE_TEXT[s.phase]}`];
  if (s.status) {
    const q = s.status.queue;
    const waiting = q.fast.waiting.length + q.heavy.waiting.length + (q.fast.running ? 1 : 0);
    parts.push(`${s.status.rssMb} MB`, `queue ${waiting}`);
  }
  return parts.join(' · ');
}

export class AppTray {
  private readonly tray: Tray;
  private glyph: Glyph = 'stopped';
  private lastMenuKey = '';

  constructor(private readonly actions: TrayActions) {
    this.tray = new Tray(glyphImage('stopped', 32, 2));
    this.tray.setToolTip('CodeLoupe');
    this.tray.on('click', () => actions.open());
  }

  update(s: DaemonState): void {
    const glyph = glyphFor(s);
    if (glyph !== this.glyph) {
      this.glyph = glyph;
      this.tray.setImage(glyphImage(glyph, 32, 2));
    }
    const line = stateLine(s);
    this.tray.setToolTip(`CodeLoupe — ${line}`);
    const build = s.status?.queue.heavy.running ? `Build: ${s.status.queue.heavy.running}` : 'Build: none';
    const running = s.phase === 'running';
    const key = `${line}|${build}|${running}|${this.actions.openAtLogin()}`;
    if (key === this.lastMenuKey) return;
    this.lastMenuKey = key;
    this.tray.setContextMenu(Menu.buildFromTemplate([
      { label: line, enabled: false },
      { label: build, enabled: false },
      { type: 'separator' },
      { label: 'Open CodeLoupe', click: () => this.actions.open() },
      { label: 'Restart daemon', enabled: running, click: () => this.actions.restart() },
      running
        ? { label: 'Stop daemon', click: () => this.actions.stop() }
        : { label: 'Start daemon', enabled: s.phase !== 'starting', click: () => this.actions.start() },
      { type: 'separator' },
      { label: 'Launch at login', type: 'checkbox', checked: this.actions.openAtLogin(), click: () => this.actions.toggleLogin() },
      { label: 'Quit', click: () => this.actions.quit() },
    ]));
  }

  destroy(): void {
    this.tray.destroy();
  }
}
