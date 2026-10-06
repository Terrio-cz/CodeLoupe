// Read view over a base index, optionally with a worktree overlay attached as `ov`. Every query goes
// through here so overlay masking (a worktree's copy of a file hides the base copy) lives in one place.
import { openStore } from '../index/store.mjs';

const COLS = `d.id, d.kind, d.name, d.container, d.fqn, d.receiver, d.params, d.param_count, d.returns, d.modifiers,
  d.supertypes, d.start_line, d.decl_line, d.end_line, d.sig, d.hash, d.local, d.parent_id, f.path, f.module, f.source_set`;

export class View {
  constructor(baseFile, overlayFile = null) {
    this.db = openStore(baseFile, { readOnly: true });
    this.overlay = !!overlayFile;
    if (overlayFile) this.db.exec(`ATTACH DATABASE '${overlayFile.replace(/'/g, "''")}' AS ov`);
    this.stmts = new Map();
  }
  close() { this.db.close(); }

  prep(sql) { if (!this.stmts.has(sql)) this.stmts.set(sql, this.db.prepare(sql)); return this.stmts.get(sql); }

  // Runs `select` (written against aliases d/f, with :params) over base and overlay and tags each row's source.
  decls(where, params = {}, tail = '') {
    const base = `SELECT ${COLS}, 'base' AS src FROM main.decls d JOIN main.files f ON f.id = d.file_id WHERE (${where})`;
    const sql = this.overlay
      ? `SELECT * FROM (SELECT ${COLS}, 'ov' AS src FROM ov.decls d JOIN ov.files f ON f.id = d.file_id WHERE (${where})
           UNION ALL ${base} AND f.path NOT IN (SELECT path FROM ov.files)) ${tail}`
      : `SELECT * FROM (${base}) ${tail}`;
    return this.prep(sql).all(params);
  }

  file(path) {
    if (this.overlay) {
      const o = this.prep('SELECT *, \'ov\' AS src FROM ov.files WHERE path = ?').get(path);
      if (o) return o.deleted ? null : o;
    }
    return this.prep('SELECT *, \'base\' AS src FROM main.files WHERE path = ?').get(path) ?? null;
  }

  // Paths ending with the given suffix ("OrderService.kt", "shop/OrderService.kt").
  filesBySuffix(suffix) {
    const like = '%' + suffix.replace(/[%_\\]/g, c => '\\' + c);
    const rows = this.prep(`SELECT path FROM main.files WHERE deleted = 0 AND path LIKE ? ESCAPE '\\'`).all(like).map(r => r.path);
    if (!this.overlay) return rows;
    const ov = this.prep(`SELECT path, deleted FROM ov.files WHERE path LIKE ? ESCAPE '\\'`).all(like);
    const gone = new Set(ov.filter(r => r.deleted).map(r => r.path));
    return [...new Set([...rows.filter(p => !gone.has(p)), ...ov.filter(r => !r.deleted).map(r => r.path)])];
  }
}
