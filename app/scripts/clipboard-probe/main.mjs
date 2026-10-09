// Electron main of the clipboard probe (check.mjs starts it): copies a value that is not a secret through the same module the app
// uses for a revealed secret, reports what it wrote, and keeps the process alive so that the platform's own tools can read the clipboard.
import { app, clipboard, ClipboardItem } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

app.whenReady().then(async () => {
  const module = await import(pathToFileURL(path.join(import.meta.dirname, '..', '..', 'src', 'main', 'env', 'concealedClipboard.ts')).href);
  const outcome = await module.writeConcealed(clipboard, ClipboardItem, 'probe-value-not-a-secret', process.platform);
  const formats = module.concealedFormats(process.platform).map(f => f.format);
  fs.writeFileSync(process.env.PROBE_READY, JSON.stringify({ outcome, formats }));
  setTimeout(() => app.quit(), 60_000);
});
