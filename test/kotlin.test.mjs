import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { FIXTURES } from './helpers.mjs';
import { extract } from '../src/lang/index.mjs';

const file = path.join(FIXTURES, 'kotlin/sample/src/main/kotlin/com/example/shop/Constructs.kt');
const facts = await extract(file, fs.readFileSync(file, 'utf8'));
const decl = (q, kind) => facts.decls.filter(d => (d.container ? d.container + '.' : '') + d.name === q && (!kind || d.kind === kind));
const one = (q, kind) => { const r = decl(q, kind); assert.equal(r.length, 1, `exactly one ${kind ?? ''} ${q}, got ${r.length}`); return r[0]; };

test('package, imports, aliases and star imports', () => {
  assert.equal(facts.package, 'com.example.shop');
  assert.equal(facts.errors, 0);
  assert.deepEqual(facts.imports.map(i => [i.fqn, i.alias, i.star]), [
    ['com.example.shop.model.Order', null, 0], ['com.example.shop.model', null, 1], ['com.example.util.Money', 'Cash', 0], ['kotlin.math.max', null, 0]]);
});

test('declaration kinds', () => {
  one('OrderId', 'typealias');
  one('Repository', 'interface');
  one('Result', 'interface');
  one('Result.Ok', 'class');
  one('Result.Missing', 'object');
  one('Status', 'enum');
  one('Status.PAID', 'enum_entry');
  one('OrderService.Factory', 'companion');
  one('OrderService.Audit', 'class');
  one('Registry', 'object');
  one('OrderService.init', 'init');
  one('OrderService.OrderService', 'constructor');
  one('weird name', 'class');
  one('weird name.does something with spaces', 'fun');
});

test('functions: overloads, extensions, return types, parameters', () => {
  const handles = decl('OrderService.handle', 'fun');
  assert.equal(handles.length, 2);
  assert.deepEqual(handles.map(h => h.params.length).sort(), [1, 2]);
  const shout = one('shout', 'fun');
  assert.equal(shout.receiver, 'String'); assert.equal(shout.returns, 'String');
  const big = one('isBig', 'property');
  assert.equal(big.receiver, 'Order'); assert.equal(big.returns, 'Boolean');
  assert.equal(one('handle2', 'fun').receiver, 'OrderService');
  assert.deepEqual(one('Repository.find').params, [{ name: 'id', type: 'OrderId' }]);
  assert.equal(one('Repository.find').returns, 'T?');
});

test('constructor properties, modifiers and supertypes', () => {
  assert.equal(one('Result.Ok.value', 'property').returns, 'Order');
  assert.ok(one('OrderService.clock', 'property').modifiers.includes('private'));
  assert.deepEqual(one('OrderService', 'class').supertypes, ['BaseService', 'AutoCloseable']);
  assert.ok(one('Sku', 'class').modifiers.includes('@JvmInline'));
  assert.ok(one('MAX_ITEMS').modifiers.includes('const'));
});

test('KDoc extends the declaration range upwards', () => {
  const total = one('total');
  assert.equal(total.start, 15); assert.equal(total.declStart, 18);
  assert.equal(one('MAX_ITEMS').start, 12);
});

test('locals and object-literal members are local', () => {
  assert.equal(one('OrderService.handle.local').local, 1);
  assert.equal(one('main.svc.<anonymous>.find').local, 1);
  assert.equal(one('OrderService.log').local, 0);
});

test('references: calls with receivers, infix, callable refs, types', () => {
  const calls = facts.refs.filter(r => r.kind === 'call').map(r => `${r.recv ? r.recv + '.' : ''}${r.name}@${facts.decls[r.decl]?.name}`);
  for (const c of ['repo.find@order', 'svc.handle@main', 'svc.handle2@main', 'Registry.register@main', 'OrderService.create@svc', 'log@record', 'handle@handle2'])
    assert.ok(calls.includes(c), `missing call ${c}`);
  assert.ok(facts.refs.some(r => r.kind === 'callable_ref' && r.name === 'total'));
  assert.ok(facts.refs.some(r => r.kind === 'type' && r.name === 'OrderId'));
  assert.ok(!facts.refs.some(r => r.name === 'null'), 'null literal is not a reference');
  assert.ok(!facts.refs.some(r => r.name === 'orders' && r.kind === 'name' && r.line === 18 && r.col < 15), 'parameter names are not references');
});
