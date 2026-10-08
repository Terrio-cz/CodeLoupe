import { useEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { reducedMotion } from './CountUp';
import { Icon } from './Icon';

interface Props {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  wide?: boolean;
  onClose(): void;
  children: ReactNode;
}

const FOCUSABLE = 'a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"]), summary';
const EXIT_MS = 180;

/**
 * Modal side panel: focus moves in, stays in (Tab wraps), Esc closes, focus returns to the opener.
 * Rendered into <body> so the content's entrance transforms never become its containing block; it slides out before closing.
 */
export function Drawer({ title, subtitle, actions, wide, onClose, children }: Props) {
  const panel = useRef<HTMLDivElement>(null);
  const [closing, setClosing] = useState(false);
  const close = useRef(onClose);
  close.current = onClose;
  const requestClose = useRef(() => {});
  requestClose.current = () => {
    if (closing) return;
    if (reducedMotion()) { close.current(); return; }
    setClosing(true);
    setTimeout(() => close.current(), EXIT_MS);
  };

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    panel.current?.querySelector<HTMLElement>('.drawer-close')?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); requestClose.current(); return; }
      if (e.key !== 'Tab' || !panel.current) return;
      const items = [...panel.current.querySelectorAll<HTMLElement>(FOCUSABLE)].filter(el => el.offsetParent !== null);
      if (items.length === 0) return;
      const first = items[0], last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      opener?.focus?.();
    };
  }, []);

  const state = closing ? ' closing' : '';
  return createPortal(
    <>
      <div className={`drawer-backdrop${state}`} onClick={() => requestClose.current()} aria-hidden="true" />
      <div ref={panel} className={`drawer${wide ? ' xl' : ''}${state}`} role="dialog" aria-modal="true" aria-labelledby="drawer-title">
        <div className="drawer-head">
          <div className="titles">
            <h1 id="drawer-title">{title}</h1>
            {subtitle && <div className="muted">{subtitle}</div>}
          </div>
          {actions}
          <button className="btn ghost icon-only drawer-close" onClick={() => requestClose.current()} aria-label="Zavřít detail"><Icon name="close" /></button>
        </div>
        <div className="drawer-body">{children}</div>
      </div>
    </>,
    document.body,
  );
}
