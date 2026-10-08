import { nativeImage, type NativeImage } from 'electron';

/** Tray state: each one has its own shape, not only a colour (docs/ui-spec.md § 4). */
export type Glyph = 'running' | 'building' | 'stopped' | 'down' | 'app';

const COLORS: Record<Glyph, [number, number, number]> = {
  running: [12, 163, 12],
  building: [42, 120, 214],
  stopped: [137, 135, 129],
  down: [208, 59, 59],
  app: [42, 120, 214],
};

/** Whether a point lies in the axis-aligned box [x0,x1] x [y0,y1]. */
function box(x: number, y: number, x0: number, x1: number, y0: number, y1: number): boolean {
  return x >= x0 && x <= x1 && y >= y0 && y <= y1;
}

/** Whether a point of the unit square centred on 0,0 (radius 1) belongs to the glyph. */
function inside(glyph: Glyph, x: number, y: number): boolean {
  const r = Math.hypot(x, y);
  const ring = r <= 0.92 && r >= 0.62;
  switch (glyph) {
    case 'running':
      return r <= 0.92;
    case 'building': {
      // Ring with a filled three-quarter sector: "work in progress".
      const a = Math.atan2(x, -y);
      return ring || (r <= 0.62 && a >= -Math.PI / 2 && a <= Math.PI);
    }
    case 'stopped':
      return ring;
    case 'down':
      return ring || (r < 0.62 && (Math.abs(x - y) < 0.18 || Math.abs(x + y) < 0.18) && Math.abs(x) < 0.4 && Math.abs(y) < 0.4);
    case 'app': {
      // Brand mark (docs/brand): two brackets around a solid block.
      return (
        box(x, y, -0.781, -0.594, -0.781, 0.781) ||
        box(x, y, -0.781, -0.344, -0.781, -0.594) ||
        box(x, y, -0.781, -0.344, 0.594, 0.781) ||
        box(x, y, 0.594, 0.781, -0.781, 0.781) ||
        box(x, y, 0.344, 0.781, -0.781, -0.594) ||
        box(x, y, 0.344, 0.781, 0.594, 0.781) ||
        box(x, y, -0.219, 0.219, -0.219, 0.219)
      );
    }
  }
}

/** Rasterises a glyph with 4×4 supersampling into a BGRA bitmap. */
export function glyphImage(glyph: Glyph, size = 32, scaleFactor = 2): NativeImage {
  const [r, g, b] = COLORS[glyph];
  const buf = Buffer.alloc(size * size * 4);
  const ss = 4;
  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      let hit = 0;
      for (let sy = 0; sy < ss; sy++) {
        for (let sx = 0; sx < ss; sx++) {
          const x = ((px + (sx + 0.5) / ss) / size) * 2 - 1;
          const y = ((py + (sy + 0.5) / ss) / size) * 2 - 1;
          if (inside(glyph, x, y)) hit++;
        }
      }
      const a = hit / (ss * ss);
      const i = (py * size + px) * 4;
      // Premultiplied BGRA.
      buf[i] = Math.round(b * a);
      buf[i + 1] = Math.round(g * a);
      buf[i + 2] = Math.round(r * a);
      buf[i + 3] = Math.round(255 * a);
    }
  }
  return nativeImage.createFromBitmap(buf, { width: size, height: size, scaleFactor });
}
