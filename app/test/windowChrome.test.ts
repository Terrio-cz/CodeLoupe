import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { canRecolourOverlay, TITLEBAR_HEIGHT, titleBarOptions, titleBarOverlay, windowBackground } from '../src/main/windowChrome';

// The OS caption buttons sit on the page's own title bar: their colours and height must be the design tokens.
const css = fs.readFileSync(path.join(__dirname, '../src/renderer/src/styles.css'), 'utf8');
const token = (selector: string, name: string): string | undefined => {
  const i = css.indexOf(selector);
  const body = css.slice(css.indexOf('{', i) + 1, css.indexOf('}', i));
  return body.match(new RegExp(`--${name}:\\s*([^;]+);`))?.[1].trim();
};
const LIGHT = ':root {';
const DARK = ":root[data-theme='dark'] {";

describe('window chrome', () => {
  it.each([[false, LIGHT], [true, DARK]] as const)('dark=%s overlay uses --sidebar and --text-2', (dark, selector) => {
    const o = titleBarOverlay(dark);
    expect(o.color).toBe(token(selector, 'sidebar'));
    expect(o.symbolColor).toBe(token(selector, 'text-2'));
    expect(windowBackground(dark)).toBe(token(selector, 'sidebar'));
  });

  it('overlay height is the --titlebar token', () => {
    const shape = css.slice(css.indexOf('--titlebar:'));
    expect(shape.match(/--titlebar:\s*(\d+)px/)?.[1]).toBe(String(TITLEBAR_HEIGHT));
    expect(titleBarOverlay(false).height).toBe(TITLEBAR_HEIGHT);
  });

  it('hides the native title bar on every platform; only macOS takes the overlay as a switch', () => {
    expect(titleBarOptions('win32', true)).toEqual({ titleBarStyle: 'hidden', titleBarOverlay: titleBarOverlay(true) });
    expect(titleBarOptions('linux', false)).toEqual({ titleBarStyle: 'hidden', titleBarOverlay: titleBarOverlay(false) });
    expect(titleBarOptions('darwin', true)).toEqual({ titleBarStyle: 'hidden', titleBarOverlay: true });
    expect(canRecolourOverlay('darwin')).toBe(false);
    expect(canRecolourOverlay('win32')).toBe(true);
  });
});
