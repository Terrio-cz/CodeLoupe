import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fixtureRepo, bigClass, tmpDir, gitIn } from './helpers.mjs';
import { buildBase } from '../src/index/build.mjs';
import { View } from '../src/query/view.mjs';
import { find, outline, symbol } from '../src/query/symbols.mjs';

const repo = fixtureRepo('kotlin/sample', { 'big/src/main/kotlin/com/example/big/Big.kt': bigClass('com.example.big', 'Big', 40) });
const dbFile = path.join(tmpDir('db'), 'base.db');
const built = await buildBase({ repoDir: repo, commit: gitIn(repo, 'rev-parse', 'HEAD'), outFile: dbFile });
const view = new View(dbFile);

test('build indexes every Kotlin file of the commit', () => {
  assert.equal(built.files, 2);
  assert.equal(built.errors, 0);
});

test('find: exact, qualified, glob, kind and module filters', () => {
  assert.match(find(view, { q: 'OrderService', kind: 'class' }), /Constructs\.kt:52-94 {2}class OrderService\(/);
  const handles = find(view, { q: 'OrderService.handle' }).split('\n');
  assert.equal(handles.length, 2);
  assert.match(find(view, { q: 'Order*', kind: 'class' }), /OrderService/);
  assert.match(find(view, { q: 'm1', module: 'big' }), /Big\.kt/);
  assert.match(find(view, { q: 'nothing_like_this' }), /^no declaration/);
  assert.doesNotMatch(find(view, { q: 'local' }), /fun local/, 'locals hidden by default');
});

test('outline of a file and of a type', () => {
  const f = outline(view, { target: 'shop/Constructs.kt' });
  assert.match(f, /^src\/main\/kotlin\/com\/example\/shop\/Constructs\.kt {2}\(\d+ lines, package com\.example\.shop\)/);
  assert.match(f, /\n {2}68-75 {2}override fun handle\(id: OrderId\): Result/);
  const t = outline(view, { target: 'OrderService' });
  assert.match(t, /\n {2}85-87 {2}companion object Factory\n {4}86-86 {2}fun create/);
  assert.doesNotMatch(t, /fun local/);
});

test('symbol: body with KDoc, overload selection, file:line, ambiguity', () => {
  const total = symbol(view, { name: 'total' });
  assert.match(total, /hash=[0-9a-f]{10}\n\/\*\*\n \* Computes the total\.\n \*\/\nfun total/);
  assert.match(symbol(view, { name: 'OrderService.handle' }), /^2 declarations match/);
  assert.match(symbol(view, { name: 'OrderService.handle(OrderId, Boolean)' }), /fun handle\(id: OrderId, force: Boolean\)/);
  assert.match(symbol(view, { name: 'OrderService.handle(_)' }), /override fun handle\(id: OrderId\): Result \{/);
  assert.match(symbol(view, { name: 'shop/Constructs.kt:70' }), /\.handle {2}hash=/);
  assert.match(symbol(view, { name: 'String.shout' }), /fun String\.shout\(\)/);
  assert.match(symbol(view, { name: 'com.example.shop.Registry' }), /^src\/main\/kotlin\/com\/example\/shop\/Constructs\.kt:96-99/);
  assert.match(symbol(view, { name: '`weird name`.`does something with spaces`' }), /fun `does something with spaces`\(\) = Unit/);
  assert.match(symbol(view, { name: 'Missing' }), /data object Missing : Result/);
});

test('large types collapse to header + members unless full=true', () => {
  const s = symbol(view, { name: 'Big' });
  assert.match(s, /lines; members \(symbol "Big\.<member>" for one, full=true for all\)/);
  assert.match(s, /\n {2}\d+-\d+ {2}fun m39\(x: Int\): Int/);
  assert.ok(s.length < 2500, `summary stays small (${s.length})`);
  assert.match(symbol(view, { name: 'Big', full: true }), /return x \+ 39/);
});
