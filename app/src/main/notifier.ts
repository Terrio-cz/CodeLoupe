import { Notification } from 'electron';
import type { DaemonEvent, Events } from '../shared/contract';
import type { DaemonPhase } from '../shared/ipc';
import type { AppSettings } from '../shared/settings';

const THROTTLE_MS = 60_000;

type Kind = DaemonEvent['kind'] | 'daemon';

/**
 * Turns daemon events (GET events) and daemon outages into OS notifications: at most one per kind per
 * minute, each kind switchable in Settings. History is never replayed: the first poll only sets the cursor.
 */
export class Notifier {
  private epoch: string | null = null;
  private lastSeq: number | null = null;
  private readonly lastShown = new Map<Kind, number>();
  private wasRunning = false;
  private outageShown = false;

  constructor(
    private readonly fetchEvents: (since: number | null) => Promise<Events>,
    private readonly settings: () => AppSettings,
    private readonly onClick: (hash: string) => void,
  ) {}

  /** Forget the cursor (API source switched). */
  reset(): void {
    this.epoch = null;
    this.lastSeq = null;
  }

  async poll(): Promise<void> {
    const res = await this.fetchEvents(this.lastSeq);
    if (this.epoch !== res.epoch || this.lastSeq === null) {
      this.epoch = res.epoch;
      this.lastSeq = res.lastSeq;
      return;
    }
    this.lastSeq = Math.max(this.lastSeq, res.lastSeq);
    for (const e of res.items) {
      if (!this.enabled(e.kind)) continue;
      const hash = `#/${e.ref.screen}${e.ref.id && e.ref.screen === 'runs' ? `/${encodeURIComponent(e.ref.id)}` : ''}`;
      this.show(e.kind, e.title, e.body, hash);
    }
  }

  daemonPhase(phase: DaemonPhase): void {
    if (phase === 'running') {
      if (this.outageShown) this.show('daemon', 'Daemon znovu běží', 'CodeLoupe daemon odpovídá.', '#/overview', true);
      this.outageShown = false;
      this.wasRunning = true;
      return;
    }
    // Only an outage the user did not cause: stops from the app or CLI end in 'stopped'.
    if (this.wasRunning && !this.outageShown && (phase === 'down' || phase === 'error') && this.settings().notify.daemon) {
      this.outageShown = true;
      this.show('daemon', 'Daemon neodpovídá', this.settings().autoStartDaemon ? 'Spouštím ho znovu.' : 'Spusťte ho z tray menu.', '#/overview', true);
    }
  }

  private enabled(kind: DaemonEvent['kind']): boolean {
    const n = this.settings().notify;
    if (kind === 'budget_breach') return n.budget;
    if (kind === 'build_finished' || kind === 'build_failed') return n.builds;
    return n.gaps;
  }

  private show(kind: Kind, title: string, body: string, hash: string, force = false): void {
    if (!Notification.isSupported()) return;
    const now = Date.now();
    if (!force && now - (this.lastShown.get(kind) ?? 0) < THROTTLE_MS) return;
    this.lastShown.set(kind, now);
    const n = new Notification({ title, body, silent: kind !== 'budget_breach' });
    n.on('click', () => this.onClick(hash));
    n.show();
  }
}
