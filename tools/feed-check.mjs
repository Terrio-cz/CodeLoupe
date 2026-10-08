#!/usr/bin/env node
// Checks the update feed files of a release (CL-107) against the installers next to them: latest.yml (Windows) and
// latest-linux.yml (AppImage) must name the release's version, every file they list must exist, and the SHA-512 and
// size in the feed must be those of the file. This is what electron-updater verifies on a user's machine, so a release
// whose feed fails here would be refused there.
//
//   node tools/feed-check.mjs <directory with the release files> [--version <v>] [--feed latest.yml]   (default: both feeds)
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const FEEDS = ['latest.yml', 'latest-linux.yml'];

/** The fields of an electron-builder feed file: its version and each listed file with sha512 and size. */
export function parseFeed(text) {
  const version = /^version:\s*(\S+)\s*$/m.exec(text)?.[1] ?? null;
  const files = [];
  for (const block of text.split(/^\s+- url:/m).slice(1)) {
    files.push({
      url: /^\s*(\S+)/.exec(block)?.[1] ?? '',
      sha512: /sha512:\s*(\S+)/.exec(block)?.[1] ?? '',
      size: Number(/size:\s*(\d+)/.exec(block)?.[1] ?? NaN),
    });
  }
  return { version, files };
}

/** Problems found in one feed file of `dir`; an empty list means the feed holds. */
export function checkFeed(dir, name, version) {
  const file = path.join(dir, name);
  if (!fs.existsSync(file)) return [`${name} is missing`];
  const feed = parseFeed(fs.readFileSync(file, 'utf8'));
  const problems = [];
  if (!feed.version) problems.push(`${name}: no version`);
  else if (version && feed.version !== version) problems.push(`${name}: says version ${feed.version}, the release is ${version}`);
  if (feed.files.length === 0) problems.push(`${name}: lists no file`);
  for (const f of feed.files) {
    const target = path.join(dir, f.url);
    if (!fs.existsSync(target)) { problems.push(`${name}: ${f.url} is not in the release`); continue; }
    const bytes = fs.readFileSync(target);
    if (bytes.length !== f.size) problems.push(`${name}: ${f.url} is ${bytes.length} bytes, the feed says ${f.size}`);
    if (crypto.createHash('sha512').update(bytes).digest('base64') !== f.sha512) problems.push(`${name}: SHA-512 of ${f.url} differs from the feed`);
  }
  return problems;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = name => (args.includes(`--${name}`) ? args[args.indexOf(`--${name}`) + 1] : undefined);
  const dir = args.find((a, i) => !a.startsWith('--') && !args[i - 1]?.startsWith('--'));
  const feeds = opt('feed') ? [opt('feed')] : FEEDS;
  if (!dir) { console.error('usage: feed-check.mjs <directory> [--version <v>] [--feed <file>]'); process.exit(2); }
  const problems = feeds.flatMap(name => checkFeed(dir, name, opt('version')));
  for (const p of problems) console.error(p);
  if (problems.length === 0) console.log(`feed files hold: ${feeds.join(', ')}`);
  process.exit(problems.length === 0 ? 0 : 1);
}
