import { useLayoutEffect, useRef } from 'react';

export const reducedMotion = (): boolean =>
  typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

const easeOut = (t: number) => 1 - (1 - t) ** 4;

/**
 * A number that counts up to its value when it appears or changes. The markup always holds the final text
 * (server render, assistive technology, reduced motion); only the visible frames in between are written directly.
 */
export function CountUp({ value, format, ms = 650 }: { value: number; format: (n: number) => string; ms?: number }) {
  const el = useRef<HTMLSpanElement>(null);
  const from = useRef(0);

  useLayoutEffect(() => {
    const node = el.current;
    const start = from.current;
    from.current = value;
    if (!node || start === value || reducedMotion()) return;
    const final = format(value);
    const t0 = performance.now();
    let done = false;
    let raf = requestAnimationFrame(function frame(now) {
      const t = Math.min(1, (now - t0) / ms);
      const x = start + (value - start) * easeOut(t);
      node.textContent = t < 1 ? format(Number.isInteger(value) ? Math.round(x) : x) : final;
      if (t < 1) raf = requestAnimationFrame(frame);
      else done = true;
    });
    node.textContent = format(start);
    return () => {
      cancelAnimationFrame(raf);
      // StrictMode re-runs the effect at once: an interrupted run starts over instead of being skipped.
      if (!done && from.current === value) from.current = start;
      node.textContent = final;
    };
  }, [value]);

  return <span ref={el}>{format(value)}</span>;
}
