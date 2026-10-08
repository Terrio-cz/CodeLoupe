#!/usr/bin/env node
// Precision at 5 of `find mode=search` on a repository (CL-137).
//
//   node tools/search-eval.mjs --repo <git checkout> --questions <file> [--cli build/install/codeloupe/bin/codeloupe] [--k 5]
//
// <file> holds one question per line: `expected<TAB>question`. A question passes when one of the first k lines of the answer
// contains `expected` (a file name, a path part or a signature part). The daemon is the one the CLI finds: set CODELOUPE_HOME
// and CODELOUPE_PORT to a throwaway home first.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const args = {};
for (let i = 2; i < process.argv.length; i += 2) args[process.argv[i].replace(/^--/, '')] = process.argv[i + 1];
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const cli = path.resolve(args.cli ?? path.join(root, 'build', 'install', 'codeloupe', 'bin', process.platform === 'win32' ? 'codeloupe.bat' : 'codeloupe'));
const k = Number(args.k ?? 5);
if (!args.repo || !args.questions) {
  console.error('usage: node tools/search-eval.mjs --repo <dir> --questions <file> [--cli <codeloupe>] [--k 5]');
  process.exit(2);
}

const rows = fs.readFileSync(args.questions, 'utf8').split(/\r?\n/).filter(l => l.trim() && !l.startsWith('#')).map(l => l.split('\t'));
let passed = 0;
for (const [expected, question] of rows) {
  const argv = ['find', question, '--mode', 'search', '--limit', String(k)];
  // A .bat launcher needs a shell, which splits unquoted arguments at spaces.
  const r = process.platform === 'win32'
    ? spawnSync(`"${cli}" ${argv.map(a => `"${a.replace(/"/g, '')}"`).join(' ')}`, { cwd: args.repo, encoding: 'utf8', shell: true })
    : spawnSync(cli, argv, { cwd: args.repo, encoding: 'utf8' });
  const lines = (r.stdout ?? '').split('\n').filter(Boolean);
  const rank = lines.findIndex(l => l.includes(expected)) + 1;
  if (rank > 0) passed++;
  console.log(`${rank > 0 ? 'PASS' : 'FAIL'} ${rank > 0 ? `#${rank}` : '--'}  ${question}  [${expected}]`);
}
console.log(`precision@${k}: ${passed}/${rows.length} = ${Math.round((100 * passed) / rows.length)} %`);
process.exit(passed === rows.length ? 0 : 1);
