import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CHARTS, THEMES, chartPath, renderChart } from './benchmarkCharts.mjs';

const KINDS = [['source', 'Source of a type'], ['grep', 'Text search, 30 hits']];
const num = n => n == null ? 'n/a' : Math.round(n).toLocaleString('en-US');
const sum = xs => xs.reduce((a, b) => a + b, 0);
const rows = [
  { kind: 'source', minimal: { tokens: 300 }, typical: { tokens: 800 }, codeloupe: { tokens: 320 } },
  { kind: 'grep', minimal: { tokens: 1500 }, typical: null, codeloupe: { tokens: 1300 } },
];
const pooled = (results, kind) => {
  const r = results.filter(x => x.kind === kind);
  return { n: r.length, minimal: r[0].minimal.tokens, typical: r[0].typical?.tokens ?? null, codeloupe: r[0].codeloupe.tokens, gitnexus: null };
};
const d = { results: rows, meta: { date: '2026-10-08', charsPerToken: 3.16, repos: [{ name: 'exposed' }] } };
const h = { KINDS, pooled, num, sum };

test('every chart renders in every theme with the brand colours and the numbers', () => {
  for (const name of CHARTS) for (const theme of Object.keys(THEMES)) {
    const svg = renderChart(name, theme, d, h);
    assert.match(svg, /^<svg xmlns=/);
    assert.ok(svg.includes(THEMES[theme].accent), `${name}/${theme} uses the accent colour`);
    assert.ok(svg.includes('font/woff2;base64,'), 'font is embedded');
    assert.ok(!svg.includes('NaN'), `${name}/${theme} has no NaN`);
  }
  assert.ok(renderChart('tokens', 'light', d, h).includes('>1,300<'));
  assert.ok(renderChart('share', 'light', d, h).includes('40 %'));
});

test('chart file names sit next to the base SVG', () => {
  assert.equal(chartPath('docs/benchmarks.svg', 'tokens', 'light'), 'docs/benchmarks.svg');
  assert.equal(chartPath('docs/benchmarks.svg', 'tokens', 'dark'), 'docs/benchmarks-dark.svg');
  assert.equal(chartPath('docs/benchmarks.svg', 'share', 'dark'), 'docs/benchmarks-share-dark.svg');
});
