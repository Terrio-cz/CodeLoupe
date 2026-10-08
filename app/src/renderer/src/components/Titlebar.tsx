import { Icon } from './Icon';

/**
 * The window's title bar, drawn by the page (main/windowChrome.ts): a drag region with the app's name, leaving the
 * corner the OS caption buttons overlay (`env(titlebar-area-*)`). The OS handles drag, double-click to maximise and snap.
 */
export function Titlebar({ version }: { version: string }) {
  return (
    <div className="titlebar">
      <span className="brand-mark" aria-hidden="true"><Icon name="loupe" size={12} /></span>
      <span className="brand-name">CodeLoupe</span>
      {version && <span className="brand-version">v{version}</span>}
    </div>
  );
}
