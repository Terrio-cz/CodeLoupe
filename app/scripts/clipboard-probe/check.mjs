#!/usr/bin/env node
// Proves in a real Electron that the app's concealed copy puts the platform's markers on the OS clipboard next to the text, and reads
// them back with the platform's own tools (xclip, NSPasteboard through osascript, the Win32 clipboard API). CL-171.
//
//   node scripts/clipboard-probe/check.mjs          (Linux: under xvfb-run; needs xclip)
//
// Needs the Electron binary (node node_modules/electron/install.js). Exit 0 when every marker is on the clipboard.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import electron from 'electron';

const here = path.dirname(fileURLToPath(import.meta.url));
const ready = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'cl-clip-')), 'ready.json');
const args = [path.join(here, 'main.mjs')];
if (process.platform === 'linux') args.unshift('--no-sandbox');

const child = spawn(electron, args, { env: { ...process.env, PROBE_READY: ready }, stdio: 'inherit' });
const stop = () => { child.kill(); };
process.on('exit', stop);

const sleep = ms => new Promise(r => setTimeout(r, ms));
for (let i = 0; i < 120 && !fs.existsSync(ready); i++) await sleep(500);
if (!fs.existsSync(ready)) { console.error('the probe wrote nothing in 60 s'); process.exit(1); }
await sleep(1000);
const { outcome, formats } = JSON.parse(fs.readFileSync(ready, 'utf8'));
console.log(`probe: ${outcome}, expected formats: ${formats.join(', ')}`);

function present() {
  if (process.platform === 'linux') {
    const r = spawnSync('xclip', ['-selection', 'clipboard', '-o', '-t', 'TARGETS'], { encoding: 'utf8' });
    return { listing: r.stdout, error: r.stderr };
  }
  if (process.platform === 'darwin') {
    const script = "ObjC.import('AppKit'); JSON.stringify(ObjC.deepUnwrap($.NSPasteboard.generalPasteboard.types))";
    const r = spawnSync('osascript', ['-l', 'JavaScript', '-e', script], { encoding: 'utf8' });
    return { listing: r.stdout, error: r.stderr };
  }
  const r = spawnSync('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(here, 'formats.ps1')], { encoding: 'utf8' });
  return { listing: r.stdout, error: r.stderr };
}

const seen = present();
console.log(`clipboard formats:\n${seen.listing}${seen.error ? `\n(stderr) ${seen.error}` : ''}`);

let failed = outcome !== 'concealed';
if (failed) console.error('the module fell back to plain text');
for (const f of formats) {
  if (!seen.listing.includes(f)) { console.error(`missing on the clipboard: ${f}`); failed = true; }
}
if (process.platform === 'win32') {
  for (const [name, value] of [['ExcludeClipboardContentFromMonitorProcessing', 1], ['CanIncludeInClipboardHistory', 0], ['CanUploadToCloudClipboard', 0]]) {
    if (!new RegExp(`${name} = ${value}\\b`).test(seen.listing)) { console.error(`${name} is not ${value}`); failed = true; }
  }
}
if (process.platform === 'linux') {
  const text = spawnSync('xclip', ['-selection', 'clipboard', '-o', '-t', 'x-kde-passwordManagerHint'], { encoding: 'utf8' });
  if (text.stdout.trim() !== 'secret') { console.error(`hint reads "${text.stdout.trim()}", not "secret"`); failed = true; }
}
process.exit(failed ? 1 : 0);
