import fs from 'node:fs';
import path from 'node:path';
import type { BundledDaemon } from '../daemon/BundledDaemon';
import type { DaemonManager } from '../daemon/DaemonManager';
import type { Notifier } from '../notifier';
import type { SettingsStore } from '../settingsStore';
import { createEngine, fetchText } from './ElectronEngine';
import { feedOverride } from './feedOverride';
import { UpdateDir, type RollbackRecord } from './UpdateDir';
import { UpdateGuard } from './UpdateGuard';
import { UPDATE_PUBLIC_KEYS } from './updateKeys';
import { detectMode } from './updateMode';
import { UpdateService } from './UpdateService';

export interface UpdateWiring {
  userData: string;
  version: string;
  packaged: boolean;
  /** The daemon bundle the installation ships. */
  shipped: BundledDaemon | null;
  /** A rollback of this version is in force: the previous bundle is the one the store runs. */
  inForce: { bundle: BundledDaemon; record: RollbackRecord } | null;
  store: SettingsStore;
  manager: DaemonManager;
  notifier: Notifier;
  env: NodeJS.ProcessEnv;
}

export interface Updates {
  service: UpdateService;
  dispose(): void;
}

/** Update checks, the install of a downloaded update and the watch over the daemon of the first run after it. */
export function setupUpdates(w: UpdateWiring): Updates {
  const files = new UpdateDir(path.join(w.userData, 'update'));
  const mode = detectMode({ platform: process.platform, packaged: w.packaged, appImage: w.env.APPIMAGE, execPath: process.execPath, exists: fs.existsSync });
  // Verification only (tools/update-test.mjs, tools/installer-smoke.mjs): a local feed, loopback addresses only, and an
  // update that installs at once.
  const override = mode.kind === 'unavailable' ? null : feedOverride(w.env.CODELOUPE_UPDATE_FEED);
  const log = fileLog(path.join(files.dir, 'update.log'));
  // The local-feed test may add a key of its own (never remove the embedded ones): it can only be set where the feed is already
  // a loopback address, so the variable leads no update from anywhere else.
  const testKey = override ? w.env.CODELOUPE_UPDATE_PUBLIC_KEY?.trim() : undefined;
  const keys = testKey ? [...UPDATE_PUBLIC_KEYS, testKey] : UPDATE_PUBLIC_KEYS;
  const engine = mode.kind === 'install' ? createEngine(mode.engine, log, keys) : null;

  const service = new UpdateService({
    current: w.version,
    mode,
    enabled: () => w.store.get().autoUpdate,
    fetchText,
    engine,
    feedOverride: override,
    installWhenReady: !!override && w.env.CODELOUPE_UPDATE_INSTALL === '1',
    relaunch: !override || w.env.CODELOUPE_UPDATE_RELAUNCH !== '0',
    beforeInstall: async version => {
      // With a rollback in force the bundle that is installed is the one that failed: the kept one stays the way back.
      if (!w.inForce && w.shipped) await files.snapshot(path.join(process.resourcesPath, 'codeloupe'), w.version);
      files.writePending({ from: w.version, to: version, at: new Date().toISOString() });
    },
  });

  if (w.inForce) service.setRollback(rollbackOf(w.inForce.record));
  let announced = '';
  service.on('state', s => {
    log(`state ${s.phase} latest=${s.latest ?? '-'} ${s.message ?? ''}`);
    if (s.phase === 'ready' && announced !== s.latest) {
      announced = s.latest ?? '';
      w.notifier.info(`Update ${s.latest} is ready`, 'Restart CodeLoupe in Settings.', '#/settings');
    } else if (s.phase === 'available' && announced !== s.latest) {
      announced = s.latest ?? '';
      w.notifier.info(`Version ${s.latest} is available`, 'You can download it from the release page (Settings).', '#/settings');
    }
  });

  const usesBundle = !!w.shipped && !w.inForce && w.store.get().cliCommand === w.shipped.command;
  const guard = new UpdateGuard({
    version: w.version,
    shipped: usesBundle ? w.shipped : null,
    files,
    manager: w.manager,
    useBundle: bundle => w.store.setBundled(bundle),
    onRollback: record => service.setRollback(rollbackOf(record)),
    tell: message => w.notifier.info('Daemon update', message, '#/settings'),
  });
  if (w.packaged) guard.watch();

  service.start(override ? Number(w.env.CODELOUPE_UPDATE_DELAY_MS) || 2_000 : undefined);
  return { service, dispose: () => { service.dispose(); guard.dispose(); } };
}

function rollbackOf(r: RollbackRecord) {
  return { failedVersion: r.failedVersion, usingVersion: r.usingVersion, reason: r.reason };
}

/** Appends lines to a small log in userData/update; starts over when it grows past 200 KB. */
function fileLog(file: string): (line: string) => void {
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    if (fs.statSync(file).size > 200_000) fs.rmSync(file);
  } catch { /* no log yet */ }
  return line => {
    try { fs.appendFileSync(file, `${new Date().toISOString()} ${line}\n`); } catch { /* the log is a convenience */ }
  };
}
