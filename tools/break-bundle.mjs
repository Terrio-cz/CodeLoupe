#!/usr/bin/env node
// Makes a daemon bundle that cannot start, for the rollback case of the update test (CL-107): the jar of the daemon is
// renamed to the given version and cut to its first 4 KB, so `java -jar` refuses it. Everything else stays.
//
//   node tools/break-bundle.mjs <bundle directory, e.g. app/stage/codeloupe> <version>
import fs from 'node:fs';
import path from 'node:path';

const [dir, version] = process.argv.slice(2);
if (!dir || !version) { console.error('usage: break-bundle.mjs <bundle directory> <version>'); process.exit(2); }
const lib = path.join(dir, 'lib');
const jar = fs.readdirSync(lib).find(f => /^codeloupe-.+\.jar$/.test(f));
if (!jar) { console.error(`no codeloupe-<version>.jar in ${lib}`); process.exit(1); }
const source = path.join(lib, jar);
const target = path.join(lib, `codeloupe-${version}.jar`);
const head = Buffer.alloc(4096);
const fd = fs.openSync(source, 'r');
const read = fs.readSync(fd, head, 0, head.length, 0);
fs.closeSync(fd);
fs.rmSync(source);
fs.writeFileSync(target, head.subarray(0, read));
console.log(`${jar} -> ${path.basename(target)} (${read} bytes: a jar that cannot start)`);
