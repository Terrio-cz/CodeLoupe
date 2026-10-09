#!/usr/bin/env node
// The tests a Gradle run skipped, with the reason each gave (CL-166): a test that does nothing on this OS has to say so
// in the report, not pass. Reads the JUnit XML of a run and prints Markdown; CI appends it to the job summary.
//
//   node tools/skipped-tests.mjs [build/test-results/test] [--title "Skipped on ubuntu-latest"]
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const PREFIX = /^org\.opentest4j\.TestAbortedException:\s*(Assumption failed:\s*)?/;

function unescape(text) {
  return text.replace(/&(lt|gt|quot|apos|amp|#(\d+)|#x([0-9a-f]+));/gi, (all, name, dec, hex) => {
    if (dec) return String.fromCodePoint(Number(dec));
    if (hex) return String.fromCodePoint(parseInt(hex, 16));
    return { lt: '<', gt: '>', quot: '"', apos: "'", amp: '&' }[name.toLowerCase()];
  });
}

/** The skipped test cases of one JUnit XML document: class, test name, reason. */
export function skippedIn(xml) {
  const out = [];
  const cases = /<testcase\s([^>]*?)(\/>|>([\s\S]*?)<\/testcase>)/g;
  for (const m of xml.matchAll(cases)) {
    const body = m[3] ?? '';
    const skipped = /<skipped(\s[^>]*?)?(\/>|>)/.exec(body);
    if (!skipped) continue;
    const attr = (name, text) => new RegExp(`\\b${name}="([^"]*)"`).exec(text)?.[1];
    const reason = unescape(attr('message', skipped[1] ?? '') ?? '').replace(PREFIX, '').trim();
    out.push({
      cls: unescape(attr('classname', m[1]) ?? '').split('.').pop(),
      name: unescape(attr('name', m[1]) ?? '').replace(/\(\)$/, ''),
      reason: reason || 'no reason given',
    });
  }
  return out;
}

export function render(skipped, title) {
  if (skipped.length === 0) return `### ${title}\n\nNothing was skipped.\n`;
  const rows = skipped.map((s) => `| ${s.cls} | ${s.name.replace(/\|/g, '\\|')} | ${s.reason.replace(/\|/g, '\\|')} |`);
  return `### ${title}\n\n${skipped.length} skipped.\n\n| Class | Test | Reason |\n| --- | --- | --- |\n${rows.join('\n')}\n`;
}

function main(argv) {
  const titleAt = argv.indexOf('--title');
  const title = titleAt >= 0 ? argv[titleAt + 1] : 'Skipped tests';
  const dir = argv.find((a, i) => !a.startsWith('--') && argv[i - 1] !== '--title') ?? 'build/test-results/test';
  const files = fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => f.endsWith('.xml')).sort() : [];
  const skipped = files.flatMap((f) => skippedIn(fs.readFileSync(path.join(dir, f), 'utf8')));
  process.stdout.write(render(skipped, title));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main(process.argv.slice(2));
