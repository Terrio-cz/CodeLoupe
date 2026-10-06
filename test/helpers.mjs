import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export const FIXTURES = fileURLToPath(new URL('./fixtures/', import.meta.url));

export function tmpDir(prefix) { return fs.mkdtempSync(path.join(os.tmpdir(), `codeloupe-${prefix}-`)); }

export const gitIn = (cwd, ...args) => execFileSync('git', ['-C', cwd, '-c', 'user.email=t@example.com', '-c', 'user.name=t', '-c', 'core.autocrlf=false', ...args], { encoding: 'utf8' }).trim();

// A git repository holding a copy of a fixture tree, committed on branch main.
export function fixtureRepo(name, extraFiles = {}) {
  const dir = tmpDir('repo');
  fs.cpSync(path.join(FIXTURES, name), dir, { recursive: true });
  for (const [p, text] of Object.entries(extraFiles)) { fs.mkdirSync(path.dirname(path.join(dir, p)), { recursive: true }); fs.writeFileSync(path.join(dir, p), text); }
  gitIn(dir, 'init', '-q', '-b', 'main');
  gitIn(dir, 'add', '-A');
  gitIn(dir, 'commit', '-q', '-m', 'fixture');
  return dir;
}

// A Kotlin class with `n` small members — big enough to trigger the large-type summary.
export function bigClass(pkg, name, n) {
  const members = Array.from({ length: n }, (_, i) => `    fun m${i}(x: Int): Int {\n        return x + ${i}\n    }\n`).join('\n');
  return `package ${pkg}\n\n/** A big class. */\nclass ${name} {\n${members}}\n`;
}
