// An environment of a machine without Java, for the bundle and installer smoke tests (CL-103, CL-108): no JAVA_HOME-style
// variables and no PATH directory with a java executable, proven by trying to run java.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

/** Returns { env, cleanup }; `env` is a copy of process.env that cannot reach a JDK. */
export function noJavaEnv() {
  const windows = process.platform === 'win32';
  const javaName = windows ? 'java.exe' : 'java';
  const pathKey = Object.keys(process.env).find(k => k.toLowerCase() === 'path') ?? 'PATH';
  // Unix keeps the other tools of such a directory (/usr/bin also holds git and tr) through a directory of symlinks
  // without java; on Windows the directory is dropped.
  const shims = fs.mkdtempSync(path.join(os.tmpdir(), 'codeloupe-nojava-'));
  const keptPath = (process.env[pathKey] ?? '').split(path.delimiter).filter(Boolean).flatMap((d, i) => {
    if (!fs.existsSync(path.join(d, javaName))) return [d];
    if (windows) return [];
    const shim = path.join(shims, String(i));
    fs.mkdirSync(shim);
    for (const entry of fs.readdirSync(d)) {
      if (entry === javaName) continue;
      try { fs.symlinkSync(path.join(d, entry), path.join(shim, entry)); } catch { /* an entry that cannot be linked is not needed */ }
    }
    return [shim];
  });
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(JAVA_HOME|JAVA_HOME_.*|JDK_HOME|JRE_HOME|CLASSPATH|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS)$/i.test(k) && k.toLowerCase() !== 'path'));
  env[pathKey] = keptPath.join(path.delimiter);
  if (keptPath.some(d => fs.existsSync(path.join(d, javaName)))) throw new Error('a java executable is still on PATH');
  const probe = spawnSync(javaName, ['-version'], { env, encoding: 'utf8' });
  if (!probe.error) throw new Error('java still runs from PATH, so this would not prove a machine without Java');
  return { env, cleanup: () => fs.rmSync(shims, { recursive: true, force: true }) };
}
