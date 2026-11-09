import type { DaemonState } from '../../../shared/ipc';
import { bridge } from '../api';
import { Banner } from './Parts';
import { Icon } from './Icon';

/**
 * A daemon of another version holds the port. Every screen then has no data to show, which reads as a CodeLoupe that
 * lost everything rather than as a daemon that is not ours (CL-260), so this says what it is on every screen and
 * offers the restart that puts it right. The restart ends the jobs the old daemon runs, which is why it is a button
 * and not something the app does by itself.
 */
export function DaemonVersionBanner({ daemon }: { daemon: DaemonState | null }) {
  const mismatch = daemon?.versionMismatch;
  if (!mismatch) return null;
  return (
    <Banner tone="warning" role="alert">
      <strong>Port {daemon?.port} is served by CodeLoupe {mismatch.running}, not the {mismatch.expected} this app ships.</strong>{' '}
      Its answers come from its own index, so the screens stay empty. Restarting the daemon starts the version the app
      ships and ends any job the old one is running.
      {mismatch.jar && <div className="t2">It runs {mismatch.jar}</div>}
      {/* A refused restart says why here (jobs the old daemon runs), where the button that asked for it is. */}
      {daemon?.message && <div className="t2">{daemon.message}</div>}
      <div className="actions">
        <button className="btn primary" onClick={() => void bridge().daemon.restart()}>
          <Icon name="refresh" size={14} />Restart daemon
        </button>
      </div>
    </Banner>
  );
}
