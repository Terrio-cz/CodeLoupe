#!/usr/bin/env node
// The rules the workflows in .github/workflows follow, checked on every push (CL-159), so a change cannot loosen them unnoticed:
//
//   - a top-level `permissions:` block, never `write-all` (jobs that need more ask for it on the job);
//   - every action is pinned to a full commit SHA (a tag can be moved), except local ones (`./...`);
//   - `actions/checkout` does not leave the token on disk (`persist-credentials: false`);
//   - no `pull_request_target` trigger (it runs with a write token on code from a fork);
//   - no `${{ github.event.* }}` / `github.head_ref` inside a `run:` block (text an outsider controls must go through `env:`).
//
//   node tools/check-workflows.mjs [dir]      default .github/workflows; exit 1 and the list when a rule is broken
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const SHA = /^[0-9a-f]{40}$/;
const UNTRUSTED = /\$\{\{\s*(github\.event\.|github\.head_ref|inputs\.)/;

const indent = line => line.length - line.trimStart().length;

/** Problems of one workflow file, as `file:line message`. */
export function check(name, text) {
  const lines = text.split(/\r?\n/);
  const problems = [];
  const report = (n, message) => problems.push(`${name}:${n + 1} ${message}`);

  if (!lines.some(l => /^permissions:/.test(l))) report(0, 'no top-level permissions: block');
  lines.forEach((line, n) => {
    const code = line.replace(/#.*$/, '');
    if (/^\s*permissions:\s*write-all/.test(code)) report(n, 'permissions: write-all');
    if (/pull_request_target/.test(code)) report(n, 'pull_request_target is not allowed');
    const use = /^\s*-?\s*uses:\s*(\S+)/.exec(code);
    if (use) {
      const ref = use[1];
      if (!ref.startsWith('./') && !ref.startsWith('docker://')) {
        const [, version] = ref.split('@');
        if (!version || !SHA.test(version)) report(n, `${ref} is not pinned to a full commit SHA`);
      }
      if (ref.startsWith('actions/checkout@')) {
        const base = indent(line.replace(/^(\s*)-/, '$1 '));
        let kept = false;
        for (let i = n + 1; i < lines.length; i++) {
          const next = lines[i];
          if (next.trim() === '') continue;
          if (indent(next) < base || /^\s*-\s/.test(next) && indent(next) <= base) break;
          if (/persist-credentials:\s*false/.test(next)) kept = true;
        }
        if (!kept) report(n, 'actions/checkout without persist-credentials: false');
      }
    }
  });

  // Inside a `run: |` block (or a one-line run:) no expression that an outsider's text can reach.
  for (let n = 0; n < lines.length; n++) {
    const m = /^(\s*)(?:-\s*)?run:\s*(.*)$/.exec(lines[n]);
    if (!m) continue;
    if (UNTRUSTED.test(m[2])) report(n, 'untrusted expression in run:');
    if (/^[|>]/.test(m[2])) {
      for (let i = n + 1; i < lines.length && (lines[i].trim() === '' || indent(lines[i]) > m[1].length); i++) {
        if (UNTRUSTED.test(lines[i])) report(i, 'untrusted expression in run: (pass it through env:)');
      }
    }
  }
  return problems;
}

export function checkDir(dir) {
  return fs.readdirSync(dir).filter(f => /\.ya?ml$/.test(f)).sort().flatMap(f => check(f, fs.readFileSync(path.join(dir, f), 'utf8')));
}

function main(argv) {
  const dir = argv[0] ?? path.join('.github', 'workflows');
  const problems = checkDir(dir);
  if (problems.length) {
    console.error(problems.join('\n'));
    process.exit(1);
  }
  console.log(`workflows in ${dir}: permissions, pinned actions, checkout credentials, triggers and run: blocks are in order`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main(process.argv.slice(2));
