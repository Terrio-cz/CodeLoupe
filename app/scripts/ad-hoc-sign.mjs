// electron-builder `afterPack` hook (CL-130): signs the macOS app ad hoc.
//
// Nothing is paid for (docs/code-signing.md), so there is no Developer ID certificate and `mac.identity` is null, which
// makes electron-builder skip signing and its `afterSign` hook with it. An ad hoc signature (`codesign --sign -`) is
// free and the least Apple Silicon needs to run the app at all; Gatekeeper still asks for a manual allow once.
//
// Order matters: the Mach-O files of the daemon bundle (the jlink runtime's java, its dylibs) are signed one by one
// first, because `codesign --deep` reaches nested code bundles but not loose executables under Resources; the app
// bundle goes last, sealing everything.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

// Mach-O magics (32/64-bit, both byte orders) and the fat header, which shares 0xCAFEBABE with Java class files.
const THIN = new Set([0xfeedface, 0xfeedfacf, 0xcefaedfe, 0xcffaedfe]);
const FAT = 0xcafebabe;
// A fat file has a handful of architectures; a Java class file has its version (45 or more) in the same place.
const MAX_FAT_ARCHS = 20;

/** True when the first bytes of a file are those of a Mach-O binary (thin or universal). */
export function isMachO(head) {
  if (head.length < 8) return false;
  const magic = head.readUInt32BE(0);
  return THIN.has(magic) || (magic === FAT && head.readUInt32BE(4) < MAX_FAT_ARCHS);
}

/** Every Mach-O file under a directory, symbolic links not followed, in a stable order. */
export function machOFiles(dir) {
  const found = [];
  const head = Buffer.alloc(8);
  const walk = current => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) { walk(full); continue; }
      if (!entry.isFile()) continue;
      const fd = fs.openSync(full, 'r');
      try {
        const read = fs.readSync(fd, head, 0, 8, 0);
        if (isMachO(head.subarray(0, read))) found.push(full);
      } finally {
        fs.closeSync(fd);
      }
    }
  };
  walk(dir);
  return found;
}

function codesign(args) {
  const r = spawnSync('codesign', args, { encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`codesign ${args.join(' ')} failed (${r.status ?? r.error?.message}): ${r.stderr}`);
}

/** Signs the loose Mach-O files under `resources`, then the app bundle, all ad hoc. Returns how many loose files. */
export function adHocSign(appPath, run = codesign) {
  const loose = machOFiles(path.join(appPath, 'Contents', 'Resources'));
  for (const file of loose) run(['--force', '--sign', '-', file]);
  run(['--force', '--deep', '--sign', '-', appPath]);
  return loose.length;
}

export async function afterPack(context) {
  if (context.electronPlatformName !== 'darwin') return;
  const app = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`);
  const loose = adHocSign(app);
  console.log(`  • signed ${app} ad hoc (${loose} loose Mach-O files under Resources)`);
}
