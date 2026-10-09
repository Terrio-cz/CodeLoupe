import fs from 'node:fs';
import path from 'node:path';

/** The command the app uses for `start`/`stop` when it ships the daemon (the Electron installers do). */
export interface BundledDaemon {
  command: string;
  args: string[];
  /** Version of the bundle, from the name of its jar: what `/status` of a daemon started from it reports. */
  version: string;
}

// The launcher's flags for a short-lived CLI (gradle/start/codeloupe) without its AOT cache.
const JVM_FLAGS = ['-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1', '-Xshare:auto', '-Xss512k', '-Xmx128m', '-XX:-UsePerfData', '-Xlog:disable'];

export interface BundleOptions {
  platform?: NodeJS.Platform;
  /** Set inside an AppImage: its mount disappears when the app quits, but the daemon must outlive the app. */
  appImage?: string;
  /** Where a bundle copy for an AppImage goes (userData), and the app version that names the copy. */
  stageDir?: string;
  version?: string;
}

/**
 * The bundled runtime and jar of the daemon in `<resources>/codeloupe` (CL-103's bundle layout: bin/, lib/, runtime/),
 * run directly with `java -jar` because a .bat launcher cannot run without a shell. null when the app ships none.
 */
export function findBundledDaemon(resources: string, opts: BundleOptions = {}): BundledDaemon | null {
  const platform = opts.platform ?? process.platform;
  let root = path.join(resources, 'codeloupe');
  if (!fs.existsSync(path.join(root, 'lib'))) return null;
  if (opts.appImage && opts.stageDir && opts.version) {
    try { root = stageBundle(root, opts.stageDir, opts.version); } catch { /* run from the mount: works until the app quits */ }
  }
  const java = path.join(root, 'runtime', 'bin', platform === 'win32' ? 'java.exe' : 'java');
  const jar = fs.readdirSync(path.join(root, 'lib')).filter(f => /^codeloupe-.+\.jar$/.test(f)).sort().pop();
  if (!jar || !isFile(java)) return null;
  const version = /^codeloupe-(.+)\.jar$/.exec(jar)?.[1] ?? '';
  return { command: java, args: [...JVM_FLAGS, '-jar', path.join(root, 'lib', jar)], version };
}

/**
 * Copies the bundle to `<stageDir>/<version>` once (a marker file says it is complete) and drops copies of other
 * versions, so the daemon runs from a path that stays when the AppImage is unmounted.
 */
export function stageBundle(source: string, stageDir: string, version: string): string {
  const target = path.join(stageDir, version);
  if (!isFile(path.join(target, '.complete'))) {
    const tmp = `${target}.tmp`;
    fs.rmSync(tmp, { recursive: true, force: true });
    fs.mkdirSync(stageDir, { recursive: true });
    fs.cpSync(source, tmp, { recursive: true, verbatimSymlinks: true });
    fs.writeFileSync(path.join(tmp, '.complete'), version);
    fs.rmSync(target, { recursive: true, force: true });
    fs.renameSync(tmp, target);
  }
  for (const entry of fs.readdirSync(stageDir)) {
    if (entry !== version) fs.rmSync(path.join(stageDir, entry), { recursive: true, force: true });
  }
  return target;
}

function isFile(p: string): boolean {
  try { return fs.statSync(p).isFile(); } catch { return false; }
}
