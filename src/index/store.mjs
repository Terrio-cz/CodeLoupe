// SQLite store of per-file facts. One database per base generation (immutable once built) and one per
// worktree overlay. Only the daemon and its build worker write.
import { DatabaseSync } from 'node:sqlite';

export const SCHEMA_VERSION = 1;

const SCHEMA = `
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE IF NOT EXISTS files (
  id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, lang TEXT, module TEXT, source_set TEXT, package TEXT,
  hash TEXT, eol TEXT, errors INTEGER, size INTEGER, mtime INTEGER, deleted INTEGER NOT NULL DEFAULT 0, content TEXT);
CREATE TABLE IF NOT EXISTS imports (file_id INTEGER NOT NULL, fqn TEXT NOT NULL, alias TEXT, star INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS decls (
  id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, container TEXT NOT NULL,
  fqn TEXT NOT NULL, receiver TEXT, params TEXT, param_count INTEGER, returns TEXT, modifiers TEXT, supertypes TEXT,
  start_line INTEGER, decl_line INTEGER, end_line INTEGER, sig TEXT, hash TEXT, local INTEGER, parent_id INTEGER);
CREATE TABLE IF NOT EXISTS refs (
  file_id INTEGER NOT NULL, name TEXT NOT NULL, line INTEGER, col INTEGER, kind TEXT, recv TEXT, decl_id INTEGER);
CREATE INDEX IF NOT EXISTS decls_name ON decls(name);
CREATE INDEX IF NOT EXISTS decls_file ON decls(file_id);
CREATE INDEX IF NOT EXISTS refs_name ON refs(name);
CREATE INDEX IF NOT EXISTS refs_file ON refs(file_id);
CREATE INDEX IF NOT EXISTS imports_file ON imports(file_id);
CREATE INDEX IF NOT EXISTS imports_fqn ON imports(fqn);
`;

export function openStore(file, { readOnly = false } = {}) {
  const db = new DatabaseSync(file, { readOnly });
  db.exec('PRAGMA busy_timeout = 5000');
  if (!readOnly) { db.exec('PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL'); db.exec(SCHEMA); }
  return db;
}

export function getMeta(db, key) { return db.prepare('SELECT value FROM meta WHERE key = ?').get(key)?.value ?? null; }
export function setMeta(db, key, value) { db.prepare('INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value').run(key, String(value)); }

// path "importers/ruian/addresses/src/main/kotlin/..." -> module "importers/ruian/addresses", source set "main"
export function moduleOf(path) {
  const parts = path.split('/'); const k = parts.indexOf('src');
  if (k < 0) return { module: '', sourceSet: '' };
  return { module: parts.slice(0, k).join('/'), sourceSet: parts[k + 1] || '' };
}

export function makeWriter(db) {
  const s = {
    delFile: db.prepare('DELETE FROM files WHERE path = ?'),
    fileId: db.prepare('SELECT id FROM files WHERE path = ?'),
    delImports: db.prepare('DELETE FROM imports WHERE file_id = ?'),
    delDecls: db.prepare('DELETE FROM decls WHERE file_id = ?'),
    delRefs: db.prepare('DELETE FROM refs WHERE file_id = ?'),
    file: db.prepare(`INSERT INTO files(path, lang, module, source_set, package, hash, eol, errors, size, mtime, deleted, content)
      VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`),
    imp: db.prepare('INSERT INTO imports(file_id, fqn, alias, star) VALUES(?, ?, ?, ?)'),
    decl: db.prepare(`INSERT INTO decls(file_id, kind, name, container, fqn, receiver, params, param_count, returns, modifiers,
      supertypes, start_line, decl_line, end_line, sig, hash, local, parent_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`),
    ref: db.prepare('INSERT INTO refs(file_id, name, line, col, kind, recv, decl_id) VALUES(?, ?, ?, ?, ?, ?, ?)'),
  };
  const remove = path => {
    const row = s.fileId.get(path); if (!row) return;
    s.delImports.run(row.id); s.delDecls.run(row.id); s.delRefs.run(row.id); s.delFile.run(path);
  };
  return {
    remove,
    // A tombstone hides the base copy of a file that the worktree deleted.
    tombstone(path) { remove(path); s.file.run(path, null, null, null, null, null, null, 0, 0, 0, 1, null); },
    put({ path, lang, hash, size, mtime = 0, content }, facts) {
      remove(path);
      const { module, sourceSet } = moduleOf(path);
      const fileId = Number(s.file.run(path, lang, module, sourceSet, facts.package, hash, content.includes('\r\n') ? 'crlf' : 'lf',
        facts.errors, size, mtime, 0, content).lastInsertRowid);
      for (const i of facts.imports) s.imp.run(fileId, i.fqn, i.alias, i.star);
      const ids = [];
      for (const d of facts.decls) {
        const fqn = [facts.package, d.container, d.name].filter(Boolean).join('.');
        ids.push(Number(s.decl.run(fileId, d.kind, d.name, d.container, fqn, d.receiver, JSON.stringify(d.params), d.params.length, d.returns,
          d.modifiers.join(' '), d.supertypes.join(' '), d.start, d.declStart, d.end, d.sig, d.hash, d.local, d.parent >= 0 ? ids[d.parent] : null).lastInsertRowid));
      }
      for (const r of facts.refs) s.ref.run(fileId, r.name, r.line, r.col, r.kind, r.recv, r.decl >= 0 ? ids[r.decl] : null);
      return fileId;
    },
  };
}
