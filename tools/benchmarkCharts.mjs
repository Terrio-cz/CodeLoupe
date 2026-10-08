// Branded SVG charts for tools/benchmark.mjs (CL-128): colours, type and mark from docs/brand/README.md.
// Each chart comes in a light and a dark variant; README and docs/benchmarks.md pick one by colour scheme.
// The text is JetBrains Mono, embedded as a subset (tools/fonts, SIL OFL 1.1) so an SVG shown through <img>
// looks the same on every machine.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FONT_B64 = fs.readFileSync(path.join(HERE, 'fonts', 'JetBrainsMono-subset.woff2')).toString('base64');

export const THEMES = {
  light: { bg: '#F3F5EF', grid: '#D3D8CF', text: '#0C0F14', sub: '#4A5260', accent: '#3A6600', read: '#7A8478', minFill: '#D3D8CF', minStroke: '#6B7366', hollow: '#0C0F14', mark: '#0C0F14', markBlock: '#3A6600', edge: '#D3D8CF' },
  dark: { bg: '#0C0F14', grid: '#2A313C', text: '#E6EAE3', sub: '#8E98A6', accent: '#B6F04A', read: '#8E98A6', minFill: '#2A313C', minStroke: '#5F6B7C', hollow: '#E6EAE3', mark: '#B6F04A', markBlock: '#B6F04A', edge: '#2A313C' },
};

export const CHARTS = ['tokens', 'share'];
const FONT = "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";
// Geometry shared by both charts, so they read as one set.
const G = { W: 880, left: 196, top: 128, barH: 14, radius: 2, gap: 4 };
const e = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;');

function frame(t, W, H, title, desc, body) {
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-labelledby="t d" font-family="${FONT}" font-size="12">`,
    `<title id="t">${e(title)}</title><desc id="d">${e(desc)}</desc>`,
    `<style>@font-face{font-family:'JetBrains Mono';font-weight:100 800;src:url(data:font/woff2;base64,${FONT_B64}) format('woff2')}</style>`,
    `<rect width="${W}" height="${H}" rx="14" fill="${t.bg}"/><rect x="0.5" y="0.5" width="${W - 1}" height="${H - 1}" rx="13.5" fill="none" stroke="${t.edge}"/>`,
    // brand mark, top right (docs/brand: brackets around a block)
    `<g transform="translate(${W - 66} 22) scale(0.6)"><path d="M18 10H10V54H18M46 10H54V54H46" fill="none" stroke="${t.mark}" stroke-width="6" stroke-linecap="square"/><rect x="25" y="25" width="14" height="14" fill="${t.markBlock}"/></g>`,
    body, '</svg>', '',
  ].join('\n');
}

function header(t, title, sub) {
  return `<text x="28" y="40" font-size="19" font-weight="700" letter-spacing="-0.4" fill="${t.text}">${e(title)}</text><text x="28" y="62" fill="${t.sub}">${e(sub)}</text>`;
}

function legend(t, items) {
  let lx = 28;
  const out = [];
  for (const [label, style, w] of items) {
    out.push(`<rect x="${lx}" y="86" width="12" height="12" rx="2" ${style}/><text x="${lx + 18}" y="96" fill="${t.text}">${e(label)}</text>`);
    lx += w;
  }
  return out.join('');
}

function rowLabel(t, label, n, y0) {
  return `<text x="${G.left - 16}" y="${y0 + 12}" text-anchor="end" font-weight="700" fill="${t.text}">${e(label)}</text><text x="${G.left - 16}" y="${y0 + 28}" text-anchor="end" font-size="11" fill="${t.sub}">n = ${n}</text>`;
}

function tokensChart(t, d, h) {
  const { KINDS, pooled, num } = h;
  const series = [['minimal', 'grep, minimal'], ['typical', 'grep + read'], ['codeloupe', 'CodeLoupe'], ['gitnexus', 'GitNexus']];
  const style = key => key === 'minimal' ? `fill="${t.minFill}" stroke="${t.minStroke}"` : key === 'typical' ? `fill="${t.read}"` : key === 'codeloupe' ? `fill="${t.accent}"` : `fill="none" stroke="${t.hollow}" stroke-width="1.25"`;
  const { W, left, top, barH } = G, right = 92, plotW = W - left - right, rowH = 86;
  const H = top + KINDS.length * rowH + 62;
  const minV = 10, maxV = 100000;
  const x = v => left + plotW * (Math.log10(Math.max(v, minV)) - 1) / (Math.log10(maxV) - 1);
  const out = [header(t, 'Tokens read per question', 'median over the questions of each kind, log scale, lower is better')];
  out.push(legend(t, series.map(([key, label]) => [label, style(key), 150])));
  const axisY = top + KINDS.length * rowH;
  for (const g of [10, 100, 1000, 10000, 100000]) out.push(`<line x1="${x(g)}" y1="${top - 8}" x2="${x(g)}" y2="${axisY - 10}" stroke="${t.grid}"/><text x="${x(g)}" y="${axisY + 8}" text-anchor="middle" fill="${t.sub}" font-size="11">${num(g)}</text>`);
  KINDS.forEach(([k, label], r) => {
    const p = pooled(d.results, k); const y0 = top + r * rowH;
    out.push(rowLabel(t, label, p.n, y0 + 14));
    series.forEach(([key], i) => {
      const v = p[key]; const y = y0 + i * 18;
      if (v == null) { out.push(`<text x="${left + 6}" y="${y + 11}" font-size="11" fill="${t.sub}">n/a</text>`); return; }
      const hero = key === 'codeloupe';
      out.push(`<rect x="${left}" y="${y}" width="${Math.max(2, x(v) - left).toFixed(1)}" height="${barH}" rx="${G.radius}" ${style(key)}/><text x="${(x(v) + 7).toFixed(1)}" y="${y + 11}" font-size="11" ${hero ? `font-weight="700" fill="${t.accent}"` : `fill="${t.text}"`}>${num(v)}</text>`);
    });
  });
  const repos = d.meta.repos.map(r => r.name).join(' and ');
  out.push(`<text x="28" y="${H - 18}" font-size="11" fill="${t.sub}">${e(`Run of ${d.meta.date} on ${repos}; tokens are characters / ${d.meta.charsPerToken}`)}</text>`);
  return frame(t, W, H, 'Tokens read per question', 'Grouped bars of median tokens per question kind for grep (minimal), grep plus read, CodeLoupe and GitNexus, log scale.', out.join('\n'));
}

function shareChart(t, d, h) {
  const { KINDS, pooled, num, sum } = h;
  const rows = KINDS.map(([k, label]) => [label, pooled(d.results, k)]).filter(([, p]) => p.typical != null && p.typical > 0);
  const typ = d.results.filter(r => r.typical);
  const total = [sum(typ.map(r => r.codeloupe.tokens)), sum(typ.map(r => r.typical.tokens))];
  const { W, left, top, barH } = G, trackW = 400, rowH = 56;
  const H = top + (rows.length + 1) * rowH + 40;
  const out = [header(t, 'CodeLoupe answer as a share of grep + read', 'median over the questions of each kind, lower is better'),
    legend(t, [['CodeLoupe', `fill="${t.accent}"`, 150], ['grep + read = 100 %', `fill="${t.read}"`, 0]])];
  const bar = (label, n, cl, rd, y, bold) => {
    const ratio = cl / rd;
    out.push(rowLabel(t, label, n, y),
      `<rect x="${left}" y="${y}" width="${trackW}" height="${barH}" rx="${G.radius}" fill="${t.read}"/><rect x="${left}" y="${y}" width="${Math.max(2, Math.min(1, ratio) * trackW).toFixed(1)}" height="${barH}" rx="${G.radius}" fill="${t.accent}"/>`,
      `<text x="${left + trackW + 14}" y="${y + 11}" font-size="11" font-weight="700" fill="${t.accent}">${Math.round(100 * ratio)} %</text>`,
      `<text x="${left + trackW + 60}" y="${y + 11}" font-size="11" fill="${t.text}">${num(cl)} of ${num(rd)}</text>`);
  };
  rows.forEach(([label, p], i) => bar(label, p.n, p.codeloupe, p.typical, top + i * rowH));
  const ly = top + rows.length * rowH;
  out.push(`<line x1="28" y1="${ly - 12}" x2="${W - 28}" y2="${ly - 12}" stroke="${t.grid}"/>`);
  bar('All, summed', typ.length, total[0], total[1], ly + 4, true);
  out.push(`<text x="28" y="${H - 18}" font-size="11" fill="${t.sub}">${e(`Run of ${d.meta.date}; tokens are characters / ${d.meta.charsPerToken}; text search has no grep + read variant and is left out`)}</text>`);
  return frame(t, W, H, 'CodeLoupe answer as a share of grep + read', 'Horizontal bars: tokens of the CodeLoupe answer as a percentage of the grep plus read answer for each question kind, and summed.', out.join('\n'));
}

/** SVG text of one chart in one theme. `h` carries the benchmark's own helpers: KINDS, pooled, num, sum. */
export function renderChart(name, theme, d, h) {
  const t = THEMES[theme];
  if (!t) throw new Error(`unknown theme ${theme}`);
  if (name === 'tokens') return tokensChart(t, d, h);
  if (name === 'share') return shareChart(t, d, h);
  throw new Error(`unknown chart ${name}`);
}

/** docs/benchmarks.svg -> tokens light; benchmarks-dark.svg, benchmarks-share.svg, benchmarks-share-dark.svg. */
export function chartPath(basePath, name, theme) {
  const ext = path.extname(basePath);
  const stem = basePath.slice(0, -ext.length);
  return `${stem}${name === 'tokens' ? '' : `-${name}`}${theme === 'dark' ? '-dark' : ''}${ext}`;
}
