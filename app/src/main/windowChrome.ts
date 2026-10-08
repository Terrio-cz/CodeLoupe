import type { TitleBarOverlay } from 'electron';

/**
 * The window's own title bar is drawn by the page (`.titlebar` in styles.css); the OS keeps only its caption buttons
 * (Windows: minimise, maximise with snap layouts, close; macOS: the traffic lights) as an overlay in the page's corner.
 * Its colours are the design tokens `--sidebar` and `--text-2` and its height is `--titlebar` (test/windowChrome.test.ts).
 */
export const TITLEBAR_HEIGHT = 36;

const TOKENS = {
  light: { sidebar: '#f1f1f4', text2: '#464651' },
  dark: { sidebar: '#09090b', text2: '#b8b8c2' },
} as const;

/** Shown before the page paints, so a new window never flashes another colour. */
export const windowBackground = (dark: boolean): string => TOKENS[dark ? 'dark' : 'light'].sidebar;

export function titleBarOverlay(dark: boolean): Required<Pick<TitleBarOverlay, 'color' | 'symbolColor' | 'height'>> {
  const t = TOKENS[dark ? 'dark' : 'light'];
  return { color: t.sidebar, symbolColor: t.text2, height: TITLEBAR_HEIGHT };
}

/** BrowserWindow options: macOS takes the overlay only as a switch, Windows and Linux take its colours and height. */
export function titleBarOptions(platform: NodeJS.Platform, dark: boolean): { titleBarStyle: 'hidden'; titleBarOverlay: TitleBarOverlay | boolean } {
  return { titleBarStyle: 'hidden', titleBarOverlay: platform === 'darwin' ? true : titleBarOverlay(dark) };
}

/** Only Windows and Linux can recolour the overlay after the window exists. */
export const canRecolourOverlay = (platform: NodeJS.Platform): boolean => platform === 'win32' || platform === 'linux';
