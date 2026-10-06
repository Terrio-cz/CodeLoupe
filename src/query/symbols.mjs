// find / outline / symbol: locate declarations and return exactly the text an agent needs.
const TYPE_KINDS = new Set(['class', 'interface', 'object', 'enum', 'companion', 'annotation']);
const CODE_EXT = /\.(kt|kts|java)$/i;
const BIG_TYPE_LINES = 120;

// "a.b.`c d`.e(Int, String)" -> { parts: ['a','b','c d','e'], params: ['Int','String'] }
export function parseQuery(q) {
  q = String(q || '').trim();
  let params = null;
  const p = q.match(/^(.*?)\((.*)\)$/s);
  if (p) { q = p[1]; params = p[2].split(',').map(s => s.trim()).filter(Boolean); }
  const parts = []; let cur = ''; let tick = false;
  for (const ch of q) {
    if (ch === '`') { tick = !tick; continue; }
    if (ch === '.' && !tick) { parts.push(cur); cur = ''; continue; }
    cur += ch;
  }
  parts.push(cur);
  return { parts: parts.filter(Boolean), params };
}

const baseType = t => String(t || '').replace(/<.*$/s, '').replace(/[?!]+$/, '').trim();

function matchesQualifier(d, qual) {
  if (!qual) return true;
  const prefix = d.fqn.slice(0, Math.max(0, d.fqn.length - d.name.length - 1));
  if (prefix === qual || prefix.endsWith('.' + qual)) return true;
  if (d.receiver && (baseType(d.receiver) === qual || baseType(d.receiver).endsWith('.' + qual))) return true;
  return false;
}

function matchesParams(d, params) {
  if (!params) return true;
  const actual = JSON.parse(d.params || '[]');
  if (actual.length !== params.length) return false;
  return params.every((w, i) => w === '_' || baseType(actual[i].type) === baseType(w) || actual[i].type.replace(/\s/g, '') === w.replace(/\s/g, ''));
}

// Declarations a query names. "file.kt:123" picks the innermost declaration around that line.
export function resolve(view, query, { kinds = null } = {}) {
  const at = String(query).match(/^(.+\.(?:kt|kts|java)):(\d+)$/i);
  if (at) {
    const path = resolvePath(view, at[1]);
    if (!path) return [];
    const rows = view.decls('f.path = :path AND d.start_line <= :line AND d.end_line >= :line', { path, line: Number(at[2]) },
      'ORDER BY (end_line - start_line) ASC LIMIT 1');
    return rows;
  }
  const { parts, params } = parseQuery(query);
  if (!parts.length) return [];
  const name = parts[parts.length - 1]; const qual = parts.slice(0, -1).join('.');
  let rows = view.decls('d.name = :name', { name }).filter(d => matchesQualifier(d, qual) && matchesParams(d, params));
  if (kinds) rows = rows.filter(d => kinds.has(d.kind));
  if (rows.some(d => !d.local)) rows = rows.filter(d => !d.local);
  return rows.sort((a, b) => a.path.localeCompare(b.path) || a.start_line - b.start_line);
}

export function resolvePath(view, p) {
  p = String(p).replace(/\\/g, '/').replace(/^\.\//, '');
  if (view.file(p)) return p;
  const hits = view.filesBySuffix(p.startsWith('/') ? p : '/' + p);
  return hits.length === 1 ? hits[0] : null;
}

const head = d => `${d.path}:${d.start_line}-${d.end_line}  ${d.container ? `[${d.container}] ` : ''}${d.sig}`;
const more = (n, shown, hint = '') => n > shown ? `\n… +${n - shown} more${hint}` : '';

export function find(view, { q, kind, module, test, locals = false, limit = 30 }) {
  const conds = []; const params = {};
  if (kind) { conds.push('d.kind = :kind'); params.kind = kind; }
  if (module) { conds.push("(f.module = :module OR f.module LIKE :modulePrefix ESCAPE '\\')"); params.module = module; params.modulePrefix = module.replace(/[%_\\]/g, c => '\\' + c) + '/%'; }
  if (test === true) conds.push("f.source_set LIKE '%test%'");
  if (test === false) conds.push("f.source_set NOT LIKE '%test%'");
  if (!locals) conds.push('d.local = 0');
  const extra = conds.length ? ' AND ' + conds.join(' AND ') : '';
  const { parts } = parseQuery(q); const name = parts[parts.length - 1] || ''; const qual = parts.slice(0, -1).join('.');
  let rows;
  if (/[*?]/.test(name)) rows = view.decls('d.name GLOB :name' + extra, { ...params, name });
  else {
    rows = view.decls('d.name = :name' + extra, { ...params, name });
    if (!rows.length) rows = view.decls("d.name LIKE :like ESCAPE '\\'" + extra, { ...params, like: '%' + name.replace(/[%_\\]/g, c => '\\' + c) + '%' });
  }
  rows = rows.filter(d => matchesQualifier(d, qual)).sort((a, b) => a.path.localeCompare(b.path) || a.start_line - b.start_line);
  if (!rows.length) return `no declaration matches "${q}"`;
  return rows.slice(0, limit).map(head).join('\n') + more(rows.length, limit, ' (narrow with kind/module or a qualified name)');
}

function membersOf(view, type) {
  const prefix = type.container ? `${type.container}.${type.name}` : type.name;
  return view.decls("f.path = :path AND d.local = 0 AND (d.container = :prefix OR d.container LIKE :like ESCAPE '\\')",
    { path: type.path, prefix, like: prefix.replace(/[%_\\]/g, c => '\\' + c) + '.%' }, 'ORDER BY start_line')
    .map(d => ({ d, depth: d.container.split('.').length - prefix.split('.').length }));
}

const memberLine = (d, depth) => `${'  '.repeat(depth + 1)}${d.start_line}-${d.end_line}  ${d.sig}`;

export function outline(view, { target }) {
  const t = String(target || '').trim();
  if (t.includes('/') || CODE_EXT.test(t)) {
    const path = resolvePath(view, t);
    if (!path) { const hits = view.filesBySuffix('/' + t.replace(/^\/+/, '')); return hits.length ? `ambiguous file "${t}":\n${hits.slice(0, 20).join('\n')}` : `no indexed file "${t}"`; }
    const f = view.file(path);
    const rows = view.decls('f.path = :path AND d.local = 0', { path }, 'ORDER BY start_line');
    const lines = rows.map(d => memberLine(d, d.container ? d.container.split('.').length : 0).replace(/^  /, ''));
    return `${path}  (${f.content.split('\n').length} lines${f.package ? `, package ${f.package}` : ''}${f.errors ? `, ${f.errors} parse errors` : ''})\n` + lines.join('\n');
  }
  const types = resolve(view, t, { kinds: TYPE_KINDS });
  if (!types.length) return `no type named "${t}"` + suggest(view, t);
  if (types.length > 1) return `"${t}" is ambiguous:\n` + types.slice(0, 20).map(head).join('\n');
  const type = types[0];
  return `${head(type)}\n` + membersOf(view, type).map(({ d, depth }) => memberLine(d, depth)).join('\n');
}

function suggest(view, q) {
  const { parts } = parseQuery(q); const name = parts[parts.length - 1]; if (!name) return '';
  const rows = view.decls("d.name LIKE :like ESCAPE '\\' AND d.local = 0", { like: '%' + name.replace(/[%_\\]/g, c => '\\' + c) + '%' }, 'LIMIT 8');
  return rows.length ? '\nsimilar:\n' + rows.map(head).join('\n') : '';
}

export function symbol(view, { name, full = false, all = false, limit = 10 }) {
  const rows = resolve(view, name);
  if (!rows.length) return `no declaration "${name}"` + suggest(view, name);
  if (rows.length > 1 && !all) {
    return `${rows.length} declarations match "${name}" — qualify it (Type.member, member(ParamType, …)) or pass all=true:\n`
      + rows.slice(0, 20).map(head).join('\n') + more(rows.length, 20);
  }
  return rows.slice(0, limit).map(d => body(view, d, full)).join('\n\n') + more(rows.length, limit);
}

function body(view, d, full) {
  const lines = view.file(d.path).content.split('\n');
  const slice = (a, b) => lines.slice(a - 1, b).map(l => l.replace(/\r$/, '')).join('\n');
  const header = `${d.path}:${d.start_line}-${d.end_line}  ${d.fqn}  hash=${d.hash}`;
  if (!full && TYPE_KINDS.has(d.kind) && d.end_line - d.start_line > BIG_TYPE_LINES) {
    const members = membersOf(view, d);
    const firstMember = members.length ? Math.min(...members.map(m => m.d.start_line)) : d.end_line;
    const top = slice(d.start_line, Math.min(firstMember - 1, d.decl_line + 15));
    return `${header}\n${top}\n  // ${d.end_line - d.start_line + 1} lines; members (symbol "${d.name}.<member>" for one, full=true for all):\n`
      + members.map(({ d: m, depth }) => memberLine(m, depth)).join('\n') + '\n}';
  }
  return `${header}\n${slice(d.start_line, d.end_line)}`;
}
