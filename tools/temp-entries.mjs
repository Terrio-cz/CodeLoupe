#!/usr/bin/env node
// Counts what a command leaves in the system temp directory (CL-208). CI takes a snapshot before the Gradle test run and
// checks after it: the tests work in a directory of their own that is removed at the end (gradle/test-scratch.gradle.kts),
// so a handful of new entries is the most a run may leave.
//
//   node tools/temp-entries.mjs snapshot <file>
//   node tools/temp-entries.mjs check <file> [--max 20]
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

// What the toolchain and the operating system keep in the temp folder on their own (the Kotlin compiler daemon and its logs, the SQLite driver's library, hsperfdata,
// PowerShell's probe files, the per-user folders of macOS daemons): not the tests' doing, and it does not grow with the suite.
const TOOLCHAIN = /^(.*[\\/])?(kotlin-|sqlite-.*sqlitejdbc|hsperfdata_|__PSScriptPolicyTest|\.org\.chromium|gradle-|com\.apple\.|betaenrollmentagent$|proactived$)/;

/** Names in [after] that were not in [before] and are not the toolchain's. */
export function newEntries(before, after) {
  const known = new Set(before);
  return after.filter((name) => !known.has(name) && !TOOLCHAIN.test(name)).sort();
}

// The folders a test run may use: the system temp folder and, outside Windows, /tmp (the tests' scratch directory lives there, where a
// Unix domain socket path stays short enough on macOS). TEMP_ENTRIES_DIRS replaces the list (the tests of this script).
function tempDirs() {
  if (process.env.TEMP_ENTRIES_DIRS) return process.env.TEMP_ENTRIES_DIRS.split(path.delimiter);
  return [...new Set([os.tmpdir(), ...(process.platform === 'win32' ? [] : ['/tmp'])])];
}

function listTemp() {
  return tempDirs().flatMap((dir) => fs.readdirSync(dir).map((name) => path.join(dir, name)));
}

function main(argv) {
  const [command, file, ...rest] = argv;
  if (!['snapshot', 'check'].includes(command) || !file) {
    console.error('usage: temp-entries.mjs snapshot <file> | check <file> [--max N]');
    return 2;
  }
  if (command === 'snapshot') {
    fs.writeFileSync(file, JSON.stringify(listTemp()));
    return 0;
  }
  const maxAt = rest.indexOf('--max');
  const max = maxAt >= 0 ? Number(rest[maxAt + 1]) : 20;
  const added = newEntries(JSON.parse(fs.readFileSync(file, 'utf8')), listTemp());
  console.log(`${added.length} new entries in ${tempDirs().join(', ')} (limit ${max})`);
  if (added.length > max) {
    console.log(added.slice(0, 50).join('\n'));
    return 1;
  }
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exit(main(process.argv.slice(2)));
