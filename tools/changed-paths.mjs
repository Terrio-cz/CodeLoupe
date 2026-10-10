#!/usr/bin/env node
// What a push changed, so a workflow can skip the heavy steps when nothing it checks could have changed (CL-195):
//
//   node tools/changed-paths.mjs <base> [head]     prints `code=true|false` and `update=true|false`, one per line, ready for
//                                                  $GITHUB_OUTPUT
//
//   code    false when every changed file is documentation (docs/, markdown, the licence); tests, the build, the workflows
//           and the plugin all count as code;
//   update  false when, besides that, only the plugin and tests changed: the app update test notices neither.
//
// The required checks still report: the jobs run and skip their steps, a job that is skipped as a whole would leave the
// check pending. An unknown range (no base, a new branch or tag, a force push that dropped the base) answers true for both.
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const ZERO = /^0+$/;
const DOCS = [/^docs\//, /\.md$/i, /^LICENSE(\.[a-z]+)?$/, /^\.github\/ISSUE_TEMPLATE\//];
const UPDATE_INERT = [/^plugin\//, /^src\/test\//, /^app\/test\//];

const matches = (file, patterns) => patterns.some(p => p.test(file));

/** `{ code, update }` for a list of changed files; an empty list is an unknown change, so both are true. */
export function classify(files) {
  const changed = files.map(f => f.trim().replace(/\\/g, '/')).filter(Boolean);
  if (changed.length === 0) return { code: true, update: true };
  return {
    code: changed.some(f => !matches(f, DOCS)),
    update: changed.some(f => !matches(f, [...DOCS, ...UPDATE_INERT])),
  };
}

/** The files between two commits, or null when the range cannot be read. */
export function changedFiles(base, head = 'HEAD', git = args => execFileSync('git', args, { encoding: 'utf8' })) {
  if (!base || ZERO.test(base)) return null;
  try {
    git(['cat-file', '-e', `${base}^{commit}`]);
    return git(['diff', '--name-only', base, head]).split('\n');
  } catch {
    return null;
  }
}

export function render({ code, update }) {
  return `code=${code}\nupdate=${update}\n`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [base, head] = process.argv.slice(2);
  process.stdout.write(render(classify(changedFiles(base, head) ?? [])));
}
