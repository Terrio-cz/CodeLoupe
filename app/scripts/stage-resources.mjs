#!/usr/bin/env node
// Collects what the installer ships next to the app (extraResources of electron-builder.yml) in app/stage/:
//   stage/codeloupe/       the daemon bundle for this OS (`./gradlew bundle`: bin/, lib/, runtime/)
//   stage/claude-plugin/   the Claude Code marketplace and plugin the app's "Connect" button installs from
//   stage/icon.png         the brand icon (docs/brand), converted by electron-builder to .ico and .icns
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const app = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repo = path.resolve(app, '..');
const stage = path.join(app, 'stage');

const bundles = path.join(repo, 'build', 'bundle');
const found = fs.existsSync(bundles) ? fs.readdirSync(bundles).filter(n => /^codeloupe-.+-(windows|macos|linux)-/.test(n)) : [];
if (found.length !== 1) {
  console.error(`expected exactly one bundle in ${bundles}, found ${found.length}; run ./gradlew bundle first`);
  process.exit(1);
}

fs.rmSync(stage, { recursive: true, force: true });
// verbatimSymlinks: a link inside the runtime stays a link, and file modes (the java launcher) are kept.
fs.cpSync(path.join(bundles, found[0]), path.join(stage, 'codeloupe'), { recursive: true, verbatimSymlinks: true });
fs.cpSync(path.join(repo, '.claude-plugin'), path.join(stage, 'claude-plugin', '.claude-plugin'), { recursive: true });
fs.cpSync(path.join(repo, 'plugin'), path.join(stage, 'claude-plugin', 'plugin'), { recursive: true });
fs.copyFileSync(path.join(repo, 'docs', 'brand', 'icon.png'), path.join(stage, 'icon.png'));
console.log(`staged ${found[0]} and the Claude Code plugin in ${stage}`);
