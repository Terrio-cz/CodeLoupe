// Kotlin source -> facts of one file: package, imports, declarations, references.
// Facts never reach outside the file; cross-file questions are answered at query time.
import crypto from 'node:crypto';

const TYPE_NODES = new Set(['user_type', 'nullable_type', 'function_type', 'parenthesized_type', 'non_nullable_type', 'dynamic']);
const BODY_NODES = new Set(['function_body', 'class_body', 'enum_class_body', 'block', 'getter', 'setter', 'property_delegate']);
const CODE_KINDS = new Set(['fun', 'property', 'constructor', 'init']);
// The grammar parses these literals as identifiers.
const KEYWORD_IDS = new Set(['null', 'true', 'false']);

const hash = text => crypto.createHash('sha1').update(text).digest('hex').slice(0, 10);
const line = n => n.startPosition.row + 1;
const endLine = n => n.endPosition.row + (n.endPosition.column === 0 && n.endPosition.row > n.startPosition.row ? 0 : 1);
const squash = s => s.replace(/\s+/g, ' ').trim();
export const bare = s => s.startsWith('`') && s.endsWith('`') ? s.slice(1, -1) : s;

function children(n) { const out = []; for (let i = 0; i < n.childCount; i++) out.push(n.child(i)); return out; }
function named(n, type) { for (let i = 0; i < n.namedChildCount; i++) { const c = n.namedChild(i); if (c.type === type) return c; } return null; }

function modifiersOf(n) {
  const m = named(n, 'modifiers'); if (!m) return [];
  const out = [];
  for (let i = 0; i < m.namedChildCount; i++) { const c = m.namedChild(i); out.push(c.type === 'annotation' ? squash(c.text) : c.text); }
  return out;
}

function kdocLine(n) {
  const prev = n.previousSibling;
  if (prev && prev.type === 'block_comment' && prev.text.startsWith('/**') && prev.endPosition.row >= n.startPosition.row - 1) return line(prev);
  return null;
}

// Header text up to the body: what a reader needs to know the declaration without its implementation.
function signature(n, mods, src) {
  let end = n.endIndex;
  for (const c of children(n)) {
    if (BODY_NODES.has(c.type) || c.type === '=') { end = c.startIndex; break; }
  }
  let start = n.startIndex;
  const m = named(n, 'modifiers'); if (m) start = m.endIndex;
  const words = mods.filter(x => !x.startsWith('@'));
  return squash((words.length ? words.join(' ') + ' ' : '') + src.slice(start, end)).slice(0, 300);
}

function typeAround(n, nameNode) {
  let receiver = null, after = null, seenName = false, seenParams = false;
  for (const c of children(n)) {
    if (c === nameNode || (nameNode && c.startIndex === nameNode.startIndex)) { seenName = true; continue; }
    if (c.type === 'function_value_parameters') { seenParams = true; continue; }
    if (!TYPE_NODES.has(c.type)) continue;
    if (!seenName) receiver = squash(c.text);
    else if (seenParams || n.type !== 'function_declaration') { after ??= squash(c.text); }
  }
  return { receiver, returns: after };
}

function params(n) {
  const p = named(n, 'function_value_parameters') || named(n, 'class_parameters');
  if (!p) return [];
  const out = [];
  for (let i = 0; i < p.namedChildCount; i++) {
    const c = p.namedChild(i);
    if (c.type !== 'parameter' && c.type !== 'class_parameter') continue;
    const id = named(c, 'identifier'); const t = children(c).find(x => TYPE_NODES.has(x.type));
    out.push({ name: id ? bare(id.text) : '?', type: t ? squash(t.text) : '' });
  }
  return out;
}

function supertypes(n) {
  const d = named(n, 'delegation_specifiers'); if (!d) return [];
  const out = [];
  for (let i = 0; i < d.namedChildCount; i++) {
    const s = d.namedChild(i); const ci = named(s, 'constructor_invocation'); const ut = named(ci || s, 'user_type');
    const id = ut && named(ut, 'identifier'); if (id) out.push(bare(id.text));
  }
  return out;
}

function classKind(n, mods) {
  if (n.type === 'object_declaration') return 'object';
  if (n.type === 'companion_object') return 'companion';
  if (children(n).some(c => c.type === 'interface')) return 'interface';
  if (mods.includes('enum')) return 'enum';
  if (mods.includes('annotation')) return 'annotation';
  return 'class';
}

export function extractKotlin(tree, src) {
  const facts = { package: '', imports: [], decls: [], refs: [], errors: 0 };
  const stack = []; // indices into facts.decls (innermost last); -1 = object literal
  const declNameIds = new Set(); // startIndex of identifiers that name a declaration

  const addDecl = (n, kind, nameNode, name, extra = {}) => {
    const mods = extra.mods ?? modifiersOf(n);
    const parentIdx = [...stack].reverse().find(i => i >= 0) ?? -1;
    const chain = stack.map(i => i >= 0 ? facts.decls[i].name : '<anonymous>');
    const local = stack.some(i => i < 0 || CODE_KINDS.has(facts.decls[i].kind));
    if (nameNode) declNameIds.add(nameNode.startIndex);
    const { receiver, returns } = extra.types ?? typeAround(n, nameNode);
    facts.decls.push({
      kind, name: bare(name), container: chain.join('.'), receiver, params: extra.params ?? params(n), returns,
      modifiers: mods, supertypes: extra.supertypes ?? [], start: kdocLine(n) ?? line(n), declStart: line(n), end: endLine(n),
      sig: extra.sig ?? signature(n, mods, src), hash: hash(n.text), local: local ? 1 : 0, parent: parentIdx,
    });
    return facts.decls.length - 1;
  };

  const walk = n => {
    const t = n.type;
    if (t === 'ERROR' || n.isMissing) facts.errors++;
    if (t === 'package_header') { const q = named(n, 'qualified_identifier') || named(n, 'identifier'); facts.package = q ? squash(q.text) : ''; return; }
    if (t === 'import') {
      const q = named(n, 'qualified_identifier') || named(n, 'identifier'); if (!q) return;
      const ids = children(n).filter(c => c.type === 'identifier');
      const star = children(n).some(c => c.type === '*' || c.type === '.*');
      facts.imports.push({ fqn: squash(q.text), alias: ids.length ? bare(ids[ids.length - 1].text) : null, star: star ? 1 : 0 });
      return;
    }
    let pushed = false;
    if (t === 'class_declaration' || t === 'object_declaration' || t === 'companion_object') {
      const nameNode = n.childForFieldName('name');
      const mods = modifiersOf(n);
      const idx = addDecl(n, classKind(n, mods), nameNode, nameNode ? nameNode.text : 'Companion', { mods, supertypes: supertypes(n), types: { receiver: null, returns: null } });
      stack.push(idx); pushed = true;
      const pc = named(n, 'primary_constructor');
      const cp = pc && named(pc, 'class_parameters');
      if (cp) for (let i = 0; i < cp.namedChildCount; i++) {
        const c = cp.namedChild(i);
        if (c.type !== 'class_parameter' || !children(c).some(x => x.type === 'val' || x.type === 'var')) continue;
        const id = named(c, 'identifier'); const ty = children(c).find(x => TYPE_NODES.has(x.type));
        const pm = modifiersOf(c);
        addDecl(c, 'property', id, id ? id.text : '?', { mods: pm, params: [], types: { receiver: null, returns: ty ? squash(ty.text) : null }, sig: squash(c.text).slice(0, 300) });
      }
    } else if (t === 'object_literal') {
      stack.push(-1); pushed = true;
    } else if (t === 'function_declaration') {
      const nameNode = n.childForFieldName('name');
      stack.push(addDecl(n, 'fun', nameNode, nameNode ? nameNode.text : '?')); pushed = true;
    } else if (t === 'property_declaration') {
      const vd = named(n, 'variable_declaration');
      const id = vd && named(vd, 'identifier');
      const ty = vd && children(vd).find(x => TYPE_NODES.has(x.type));
      const recv = children(n).find(x => TYPE_NODES.has(x.type));
      stack.push(addDecl(n, 'property', id, id ? id.text : '?', { params: [], types: { receiver: recv ? squash(recv.text) : null, returns: ty ? squash(ty.text) : null } })); pushed = true;
    } else if (t === 'secondary_constructor') {
      const owner = stack.length && stack[stack.length - 1] >= 0 ? facts.decls[stack[stack.length - 1]].name : 'constructor';
      stack.push(addDecl(n, 'constructor', null, owner, { types: { receiver: null, returns: null } })); pushed = true;
    } else if (t === 'anonymous_initializer') {
      stack.push(addDecl(n, 'init', null, 'init', { params: [], types: { receiver: null, returns: null } })); pushed = true;
    } else if (t === 'type_alias') {
      const id = named(n, 'identifier'); const target = children(n).find(x => TYPE_NODES.has(x.type));
      addDecl(n, 'typealias', id, id ? id.text : '?', { params: [], types: { receiver: null, returns: target ? squash(target.text) : null } });
    } else if (t === 'enum_entry') {
      const id = named(n, 'identifier');
      stack.push(addDecl(n, 'enum_entry', id, id ? id.text : '?', { params: [], types: { receiver: null, returns: null }, sig: id ? id.text : '?' })); pushed = true;
    } else if (t === 'identifier') {
      ref(n);
      return;
    } else if (t === 'variable_declaration' || t === 'parameter' || t === 'class_parameter' || t === 'type_parameter') {
      // The first identifier names a local/parameter, not a reference; types and defaults still are.
      let skipped = false;
      for (const c of children(n)) { if (!skipped && c.type === 'identifier') { skipped = true; continue; } walk(c); }
      return;
    }
    for (let i = 0; i < n.childCount; i++) walk(n.child(i));
    if (pushed) stack.pop();
  };

  const ref = id => {
    if (declNameIds.has(id.startIndex) || KEYWORD_IDS.has(id.text)) return;
    const p = id.parent; if (!p) return;
    let kind = 'name', recv = null;
    const pt = p.type;
    if (pt === 'user_type') kind = 'type';
    else if (pt === 'callable_reference') kind = 'callable_ref';
    else if (pt === 'navigation_expression') {
      const first = p.namedChild(0);
      if (first && first.startIndex !== id.startIndex) {
        recv = squash(first.text).slice(0, 60);
        const gp = p.parent;
        kind = gp && gp.type === 'call_expression' && gp.namedChild(0)?.startIndex === p.startIndex ? 'call' : 'nav';
      }
    } else if (pt === 'call_expression' && p.namedChild(0)?.startIndex === id.startIndex) kind = 'call';
    else if (pt === 'infix_expression' && p.namedChild(1)?.startIndex === id.startIndex) { kind = 'call'; recv = squash(p.namedChild(0).text).slice(0, 60); }
    else if (pt === 'value_argument' && id.nextSibling?.type === '=') kind = 'named_arg';
    else if (pt === 'label' || pt === 'annotated_label') return;
    const decl = [...stack].reverse().find(i => i >= 0) ?? -1;
    facts.refs.push({ name: bare(id.text), line: line(id), col: id.startPosition.column + 1, kind, recv, decl });
  };

  walk(tree.rootNode);
  return facts;
}
