import { useEffect, useRef, type ReactNode } from 'react';

interface Props {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  wide?: boolean;
  onClose(): void;
  children: ReactNode;
}

const FOCUSABLE = 'a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"]), summary';

/** Modal side panel: focus moves in, stays in (Tab wraps), Esc closes, focus returns to the opener. */
export function Drawer({ title, subtitle, actions, wide, onClose, children }: Props) {
  const panel = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    panel.current?.querySelector<HTMLElement>('.drawer-close')?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); close.current(); return; }
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

  return (
    <>
      <div className="drawer-backdrop" onClick={onClose} aria-hidden="true" />
      <div ref={panel} className={`drawer${wide ? ' xl' : ''}`} role="dialog" aria-modal="true" aria-labelledby="drawer-title">
        <div className="drawer-head">
          <div className="titles">
            <h1 id="drawer-title">{title}</h1>
            {subtitle && <div className="muted">{subtitle}</div>}
          </div>
          {actions}
          <button className="btn ghost drawer-close" onClick={onClose} aria-label="Zavřít detail">✕</button>
        </div>
        <div className="drawer-body">{children}</div>
      </div>
    </>
  );
}
