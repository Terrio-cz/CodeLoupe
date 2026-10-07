import react from '@vitejs/plugin-react';
import { defineConfig } from 'electron-vite';
import type { Plugin } from 'vite';

// The page ships with a strict CSP (src/renderer/index.html). Only the dev server relaxes it for
// Vite's inline React refresh preamble and its HMR websocket.
function devCsp(): Plugin {
  return {
    name: 'codeloupe-dev-csp',
    apply: 'serve',
    transformIndexHtml: (html: string) =>
      html
        .replace("script-src 'self'", "script-src 'self' 'unsafe-inline'")
        .replace("style-src 'self'", "style-src 'self' 'unsafe-inline'")
        .replace("connect-src 'none'", "connect-src 'self' ws://localhost:*"),
  };
}

export default defineConfig({
  main: {},
  preload: {},
  renderer: {
    plugins: [react(), devCsp()],
    build: { target: 'chrome140', cssCodeSplit: false, minify: true },
  },
});
