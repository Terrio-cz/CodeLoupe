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

/** Whether a point lies within `r` of the segment (x1,y1)-(x2,y2). */
function capsule(x: number, y: number, x1: number, y1: number, x2: number, y2: number, r: number): boolean {
  const dx = x2 - x1;
  const dy = y2 - y1;
  const t = Math.max(0, Math.min(1, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)));
  return Math.hypot(x - (x1 + t * dx), y - (y1 + t * dy)) <= r;
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
      // Brand mark (docs/brand): lens ring, handle towards the bottom right, three index lines inside.
      const rr = Math.hypot(x + 0.125, y + 0.125);
      const lens = rr <= 0.703 && rr >= 0.547;
      return (
        lens ||
        capsule(x, y, 0.344, 0.344, 0.812, 0.812, 0.094) ||
        capsule(x, y, -0.4375, -0.344, 0.094, -0.344, 0.053) ||
        capsule(x, y, -0.281, -0.125, 0.219, -0.125, 0.053) ||
        capsule(x, y, -0.4375, 0.094, -0.0625, 0.094, 0.053)
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
