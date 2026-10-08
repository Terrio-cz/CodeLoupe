#!/usr/bin/env node
// Licence report and check of the npm dependencies (CL-109), read from package-lock.json: no install, no extra
// dependency. Fails (exit 1) on a package whose SPDX expression is not covered by the allowlist, or has none.
//
//   node tools/npm-licenses.mjs [app/package-lock.json] [--out npm-licenses.json]
import fs from 'node:fs';

const args = process.argv.slice(2);
const outIndex = args.indexOf('--out');
const out = outIndex >= 0 ? args[outIndex + 1] : null;
const lockPath = args.find((a, i) => !a.startsWith('--') && i !== outIndex + 1) ?? 'app/package-lock.json';

// Permissive licences only. An "A OR B" expression passes when any alternative is allowed, "A AND B" when all are.
const ALLOWED = new Set(['MIT', 'ISC', 'BSD-2-Clause', 'BSD-3-Clause', 'Apache-2.0', '0BSD', 'BlueOak-1.0.0', 'Python-2.0']);
// Data, not code, and only in the dev toolchain (browserslist data of the build); attribution is kept by the package.
const EXCEPTIONS = { 'node_modules/caniuse-lite': 'CC-BY-4.0' };

function allowed(expression) {
  const tokens = expression.replace(/[()]/g, ' ').trim().split(/\s+/);
  if (tokens.includes('AND')) return expression.split(/\s+AND\s+/).every(allowed);
  return tokens.filter(t => t !== 'OR').some(t => ALLOWED.has(t));
}

const lock = JSON.parse(fs.readFileSync(lockPath, 'utf8'));
const packages = Object.entries(lock.packages).filter(([path]) => path !== '').map(([path, pkg]) => ({
  name: pkg.name ?? path.slice(path.lastIndexOf('node_modules/') + 'node_modules/'.length),
  version: pkg.version,
  license: pkg.license ?? null,
  dev: pkg.dev === true,
  path,
}));

const violations = packages.filter(p => !(p.license && allowed(p.license)) && EXCEPTIONS[p.path] !== p.license);
const byLicense = {};
for (const p of packages) byLicense[p.license ?? 'none'] = (byLicense[p.license ?? 'none'] ?? 0) + 1;

console.log(`${packages.length} packages: ${Object.entries(byLicense).map(([l, n]) => `${l} ${n}`).join(', ')}`);
if (out) fs.writeFileSync(out, JSON.stringify({ byLicense, packages }, null, 2));
for (const v of violations) console.error(`NOT ALLOWED ${v.name}@${v.version} (${v.license ?? 'no licence field'})`);
process.exit(violations.length > 0 ? 1 : 0);
