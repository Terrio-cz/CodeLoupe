#!/usr/bin/env node
// Scripted check of what `task_context` names of the files a task really changed (CL-194), without a model run.
//
//   node tools/pack-coverage.mjs --cli build/install/codeloupe/bin/codeloupe --root <clone before the task landed> \
//        --task TER-5 --expect expected.txt [--section cochange --section touch ...]
//
// <expect> lists, one per line, a substring per file of the real solution (a file name, or a path part); `#` starts a comment.
// The CLI runs with the caller's CODELOUPE_HOME / CODELOUPE_PORT (a throwaway daemon, never the default one). Prints how many of the
// expected files the pack mentions, which it does not, and the size of the pack. Exit code 1 when `--min <fraction>` is not reached.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

export function coverage(pack, expected) {
  const named = expected.filter((e) => pack.includes(e));
  return { named, missing: expected.filter((e) => !pack.includes(e)), share: expected.length ? named.length / expected.length : 1 };
}

export function parseExpected(text) {
  return text.split(/\r?\n/).map((l) => l.replace(/#.*/, '').trim()).filter(Boolean);
}

function main(argv) {
  const opts = { section: [] };
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i]?.replace(/^--/, '');
    if (key === 'section') opts.section.push(argv[i + 1]);
    else opts[key] = argv[i + 1];
  }
  if (!opts.cli || !opts.root || !opts.task || !opts.expect) {
    console.error('usage: pack-coverage --cli <codeloupe> --root <dir> --task <id> --expect <file> [--section <name>]... [--min <0..1>]');
    return 2;
  }
  const args = ['task_context', opts.task, '--root', opts.root, ...opts.section.flatMap((s) => ['--section', s]), '--since', 'none'];
  // The Windows launcher is a .bat, which only a shell starts.
  const windows = process.platform === 'win32';
  const cli = path.resolve(opts.cli) + (windows && !/\.(bat|cmd)$/i.test(opts.cli) ? '.bat' : '');
  const run = windows
    ? spawnSync(`"${cli}" ${args.map((a) => `"${a}"`).join(' ')}`, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, shell: true })
    : spawnSync(cli, args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (run.status !== 0) {
    console.error(run.stderr || run.stdout);
    return 2;
  }
  const result = coverage(run.stdout, parseExpected(fs.readFileSync(opts.expect, 'utf8')));
  console.log(JSON.stringify({ task: opts.task, chars: run.stdout.length, named: result.named.length, of: result.named.length + result.missing.length, share: Number(result.share.toFixed(2)), missing: result.missing }));
  return opts.min && result.share < Number(opts.min) ? 1 : 0;
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) process.exit(main(process.argv.slice(2)));
