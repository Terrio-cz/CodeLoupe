import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

// docs/design-revamp.md § Tokeny: text tokens ≥ 4.5:1 and control borders ≥ 3:1 on every background, both modes.
const css = fs.readFileSync(path.join(__dirname, '../src/renderer/src/styles.css'), 'utf8');

function block(selector: string): Record<string, string> {
  const i = css.indexOf(selector);
  const body = css.slice(css.indexOf('{', i) + 1, css.indexOf('}', i));
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*(#[0-9a-f]{6})/gi)].map(m => [m[1], m[2]]));
}

const light = block(':root {');
const dark = { ...light, ...block(":root[data-theme='dark'] {") };

function lum(hex: string): number {
  const c = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255).map(v => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}
const ratio = (a: string, b: string) => {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
};

const BACKGROUNDS = ['bg', 'surface', 'surface-2', 'accent-weak', 'sidebar'];
const TEXT = ['text', 'text-2', 'text-muted', 'accent-text', 'ok-text', 'warning-text', 'serious-text', 'critical-text'];

describe.each([['light', light], ['dark', dark]] as const)('%s tokens', (_mode, t) => {
  it.each(TEXT)('--%s reads at 4.5:1 on every background', name => {
    for (const bg of BACKGROUNDS) expect(ratio(t[name], t[bg]), `${name} on ${bg}`).toBeGreaterThanOrEqual(4.5);
  });

  it('control borders reach 3:1 on every background', () => {
    for (const bg of BACKGROUNDS) expect(ratio(t['border-control'], t[bg]), `border-control on ${bg}`).toBeGreaterThanOrEqual(3);
  });

  it.each(['ok', 'warning', 'serious', 'critical'])('--%s-text reads at 4.5:1 on its badge background', tone => {
    expect(ratio(t[`${tone}-text`], t[`${tone}-weak`])).toBeGreaterThanOrEqual(4.5);
  });

  it('primary button text reads at 4.5:1', () => {
    expect(ratio(t['btn-primary-fg'], t['btn-primary-bg'])).toBeGreaterThanOrEqual(4.5);
  });
});

it('dark mode under the media query matches the explicit dark theme', () => {
  const media = block(":root:not([data-theme='light']) {");
  expect(media).toEqual(block(":root[data-theme='dark'] {"));
});
