#!/usr/bin/env node
// Fails (exit 1) when a SARIF directory holds a finding of high severity (CL-109): a security-severity of 7.0 or more,
// or, for a rule without one, the level "error". Everything else is only listed.
//
//   node tools/sarif-gate.mjs <sarif dir or file> [--min 7.0]
import fs from 'node:fs';
import path from 'node:path';

const [target, ...rest] = process.argv.slice(2);
const min = Number(rest[rest.indexOf('--min') + 1] || 7.0);
if (!target || !fs.existsSync(target)) { console.error('usage: sarif-gate.mjs <sarif dir or file> [--min 7.0]'); process.exit(2); }

const files = fs.statSync(target).isDirectory()
  ? fs.readdirSync(target).filter(f => f.endsWith('.sarif')).map(f => path.join(target, f))
  : [target];
if (files.length === 0) { console.error(`no .sarif file in ${target}`); process.exit(2); }

let high = 0;
let other = 0;
for (const file of files) {
  const sarif = JSON.parse(fs.readFileSync(file, 'utf8'));
  for (const run of sarif.runs ?? []) {
    const rules = new Map();
    for (const rule of [...(run.tool?.driver?.rules ?? []), ...(run.tool?.extensions ?? []).flatMap(e => e.rules ?? [])]) rules.set(rule.id, rule);
    for (const result of run.results ?? []) {
      const rule = rules.get(result.ruleId);
      const score = Number(rule?.properties?.['security-severity']);
      const level = result.level ?? rule?.defaultConfiguration?.level ?? 'warning';
      const isHigh = Number.isFinite(score) ? score >= min : level === 'error';
      const where = result.locations?.[0]?.physicalLocation;
      const line = `${isHigh ? 'HIGH' : level} ${result.ruleId} ${where?.artifactLocation?.uri ?? '?'}:${where?.region?.startLine ?? '?'} ${result.message?.text ?? ''}`;
      if (isHigh) { high++; console.error(line); } else { other++; console.log(line); }
    }
  }
}
console.log(`${files.length} sarif file(s): ${high} high, ${other} other`);
process.exit(high > 0 ? 1 : 0);
