import { Notification } from 'electron';
import type { DaemonEvent, Events } from '../shared/contract';
import type { DaemonPhase } from '../shared/ipc';
import type { AppSettings } from '../shared/settings';

const THROTTLE_MS = 60_000;
const PAGE = 50;
const MAX_PAGES = 10;

type Kind = DaemonEvent['kind'] | 'daemon';

interface Pending {
  count: number;
  last: DaemonEvent;
}

/**
 * Turns daemon events (GET events) and daemon outages into OS notifications. Events of one kind are merged:
 * at most one notification per kind per minute, the rest are summed into the next one. Each kind can be
 * switched off in Settings. History is never replayed: the first poll only sets the cursor.
 */
export class Notifier {
  private epoch: string | null = null;
  private lastSeq: number | null = null;
  private readonly lastShown = new Map<Kind, number>();
  private readonly pending = new Map<DaemonEvent['kind'], Pending>();
  // Windows drops the click handler of a notification that was garbage-collected.
  private readonly live = new Set<Notification>();
  private wasRunning = false;
  private outageShown = false;

  constructor(
    private readonly fetchEvents: (since: number | null, limit: number) => Promise<Events>,
    private readonly settings: () => AppSettings,
    private readonly onClick: (hash: string) => void,
  ) {}

  /** Forget the cursor (API source switched). */
  reset(): void {
    this.epoch = null;
    this.lastSeq = null;
    this.pending.clear();
  }

  async poll(): Promise<void> {
    if (this.lastSeq === null) {
      const res = await this.fetchEvents(null, PAGE);
      this.epoch = res.epoch;
      this.lastSeq = res.lastSeq;
      return;
    }
    for (let page = 0; page < MAX_PAGES; page++) {
      const res = await this.fetchEvents(this.lastSeq, PAGE);
      if (res.epoch !== this.epoch) {
        // The daemon numbers anew: re-baseline without notifying.
        this.epoch = res.epoch;
        this.lastSeq = res.lastSeq;
        return;
      }
      for (const e of res.items) {
        this.lastSeq = Math.max(this.lastSeq, e.seq);
        if (!this.enabled(e.kind)) continue;
        const p = this.pending.get(e.kind);
        this.pending.set(e.kind, { count: (p?.count ?? 0) + 1, last: e });
      }
      if (res.items.length < PAGE) break;
    }
    this.flush();
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

  /** A start that failed before the daemon ever ran (wrong CLI command, for example). */
  startFailed(message: string | null): void {
    if (this.wasRunning || this.outageShown || !this.settings().notify.daemon) return;
    this.outageShown = true;
    this.show('daemon', 'Daemon se nepodařilo spustit', message ?? 'Zkontrolujte příkaz CLI v Nastavení.', '#/settings', true);
  }

  /** Auto-start stopped trying (5 failures in 10 minutes). */
  gaveUp(message: string | null): void {
    if (!this.settings().notify.daemon) return;
    this.outageShown = true;
    this.show('daemon', 'Daemon se nedaří spustit', message ?? 'Zkontrolujte příkaz CLI v Nastavení.', '#/settings', true);
  }

  private flush(): void {
    const now = Date.now();
    for (const [kind, p] of this.pending) {
      if (now - (this.lastShown.get(kind) ?? 0) < THROTTLE_MS) continue;
      this.pending.delete(kind);
      const title = p.count > 1 ? `${p.last.title} (+${p.count - 1} další)` : p.last.title;
      this.show(kind, title, p.last.body, `#/${p.last.ref.screen}`);
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
    this.live.add(n);
    n.on('click', () => { this.live.delete(n); this.onClick(hash); });
    n.on('close', () => this.live.delete(n));
    n.show();
  }
}
