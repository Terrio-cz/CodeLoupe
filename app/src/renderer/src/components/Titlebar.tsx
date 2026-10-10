/** The app icon (docs/brand/icon.svg): the span mark on a Carbon tile, the same in both themes. */
function AppMark() {
  return (
    <svg className="brand-mark" viewBox="0 0 64 64" aria-hidden="true">
      <rect x="0.5" y="0.5" width="63" height="63" rx="14" fill="#0c0f14" stroke="#2a313c" />
      <g transform="translate(12 12) scale(0.625)">
        <path d="M18 10H10V54H18M46 10H54V54H46" fill="none" stroke="#b6f04a" strokeWidth="6" strokeLinecap="square" />
        <rect x="25" y="25" width="14" height="14" fill="#b6f04a" />
      </g>
    </svg>
  );
}

/**
 * The window's title bar, drawn by the page (main/windowChrome.ts): a drag region with the app's name, leaving the
 * corner the OS caption buttons overlay (`env(titlebar-area-*)`). The OS handles drag, double-click to maximise and snap.
 */
export function Titlebar({ version }: { version: string }) {
  return (
    <div className="titlebar">
      <AppMark />
      <span className="brand-name">CodeLoupe</span>
      {version && <span className="brand-version">v{version}</span>}
    </div>
  );
}
