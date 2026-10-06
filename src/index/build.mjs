// Full build of a base index from git objects of one commit. Runs as a short-lived child process of the
// daemon: parsing a whole repository grows the WASM heap (~0.5 GB for 2k Kotlin files) and only process
// exit returns that memory.
import fs from 'node:fs';
import os from 'node:os';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { lsTree, readBlobs } from '../git.mjs';
import { languageOf, extract } from '../lang/index.mjs';
import { openStore, makeWriter, setMeta, SCHEMA_VERSION } from './store.mjs';

export async function buildBase({ repoDir, commit, outFile }) {
  const started = Date.now();
  const entries = lsTree(repoDir, commit).filter(e => languageOf(e.path));
  for (const f of [outFile, outFile + '-wal', outFile + '-shm']) fs.rmSync(f, { force: true });
  const db = openStore(outFile);
  const w = makeWriter(db);
  const bySha = new Map(); for (const e of entries) (bySha.get(e.sha) ?? bySha.set(e.sha, []).get(e.sha)).push(e);
  let files = 0, errors = 0;
  db.exec('BEGIN');
  await readBlobs(repoDir, [...bySha.keys()], async (sha, text) => {
    const facts = await extract(bySha.get(sha)[0].path, text);
    for (const e of bySha.get(sha)) {
      w.put({ path: e.path, lang: languageOf(e.path), hash: crypto.createHash('sha1').update(text).digest('hex'), size: e.size, content: text }, facts);
      files++; errors += facts.errors ? 1 : 0;
    }
  });
  setMeta(db, 'schema', SCHEMA_VERSION);
  setMeta(db, 'commit', commit);
  setMeta(db, 'built_at', new Date().toISOString());
  setMeta(db, 'files', files);
  setMeta(db, 'files_with_errors', errors);
  db.exec('COMMIT');
  db.exec('PRAGMA wal_checkpoint(TRUNCATE)');
  db.close();
  return { files, errors, ms: Date.now() - started };
}

// Worker entry: node build.mjs <repoDir> <commit> <outFile>  -> one JSON line on stdout.
if (process.argv[1] && fileURLToPath(import.meta.url) === fs.realpathSync(process.argv[1])) {
  try { os.setPriority(os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {}
  const [repoDir, commit, outFile] = process.argv.slice(2);
  buildBase({ repoDir, commit, outFile })
    .then(r => { process.stdout.write(JSON.stringify({ ok: true, ...r, peakRssMb: Math.round(process.resourceUsage().maxRSS / 1024) }) + '\n'); })
    .catch(e => { process.stdout.write(JSON.stringify({ ok: false, error: e.message }) + '\n'); process.exitCode = 1; });
}
