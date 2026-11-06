// Browser-only development stand-in: the Flint Store test capes the dev mock's Store page owns (devMock.ts), drawn here so
// the character previews can be looked at with every kind of cape. Never bundled into production builds; nothing here is a
// real product. Static capes are 64x32 per unit textures; animated ones follow the Store's manifest exactly (docs/LAUNCHER-
// API.md "Animated capes": 22x17 frames at `scale` px per unit, strips of floor(2048 / height) frames, the poster frame 0
// at the top left of a 64x32-per-unit texture at most 16 px per unit).
import { textureSize, type CapeAnimation } from "./capeAnimation";

export const CAPE_IDS = {
  ember: "00000000-0000-4000-8000-0000000000c1",
  frost: "00000000-0000-4000-8000-0000000000c2",
  emberHd: "00000000-0000-4000-8000-0000000000c3",
  flame: "00000000-0000-4000-8000-0000000000c4",
  flameHd: "00000000-0000-4000-8000-0000000000c5",
} as const;

/** `&cape=` in the dev URL → the item worn at start. */
export const CAPE_PARAM: Record<string, string> = { static: CAPE_IDS.ember, frost: CAPE_IDS.frost, hd: CAPE_IDS.emberHd, animated: CAPE_IDS.flame, animatedhd: CAPE_IDS.flameHd };

const manifest = (scale: number, durations: number[]): CapeAnimation => {
  const height = 17 * scale;
  const per = Math.max(1, Math.floor(2048 / height));
  const strips = Array.from({ length: Math.ceil(durations.length / per) }, (_, s) => ({ frames: Math.min(per, durations.length - s * per) }));
  return { scale, width: 22 * scale, height, frameCount: durations.length, durationsMs: durations, durationMs: durations.reduce((a, b) => a + b, 0), framesPerStrip: per, strips };
};

const ANIMATIONS: Record<string, CapeAnimation> = {
  // 12 frames of their own durations (no fixed frame rate), 16 px per unit.
  [CAPE_IDS.flame]: manifest(16, [90, 70, 60, 80, 110, 70, 60, 90, 120, 80, 70, 100]),
  // HD: 32 px per unit (704x544 frames, 3 to a strip), halved by the previews.
  [CAPE_IDS.flameHd]: manifest(32, [80, 60, 100, 70, 90, 60, 110, 80]),
};

type Item = { cosmeticId: string; slug: string; name: string; scale: number; animation: CapeAnimation | null; gift?: string };
export const CAPE_ITEMS: Item[] = [
  { cosmeticId: CAPE_IDS.ember, slug: "test-ember-cape", name: "TEST · Ember Cape", scale: 1, animation: null },
  { cosmeticId: CAPE_IDS.frost, slug: "test-frost-cape", name: "TEST · Frost Cape", scale: 1, animation: null, gift: "Alex" },
  { cosmeticId: CAPE_IDS.emberHd, slug: "test-ember-hd", name: "TEST · Ember Cape HD", scale: 16, animation: null },
  { cosmeticId: CAPE_IDS.flame, slug: "test-living-flame", name: "TEST · Living Flame", scale: 16, animation: ANIMATIONS[CAPE_IDS.flame] },
  { cosmeticId: CAPE_IDS.flameHd, slug: "test-living-flame-hd", name: "TEST · Living Flame HD", scale: 32, animation: ANIMATIONS[CAPE_IDS.flameHd] },
];

type Palette = { deep: string; mid: string; hot: string; edge: string };
const EMBER: Palette = { deep: "#2a0800", mid: "#d9480f", hot: "#ffc24a", edge: "#1b1b1f" };
const FROST: Palette = { deep: "#071a33", mid: "#2f7fd1", hot: "#c8ecff", edge: "#1b1f27" };

const mix = (a: string, b: string, t: number) => {
  const pa = [1, 3, 5].map((i) => parseInt(a.slice(i, i + 2), 16));
  const pb = [1, 3, 5].map((i) => parseInt(b.slice(i, i + 2), 16));
  return `rgb(${pa.map((v, i) => Math.round(v + (pb[i] - v) * t)).join(",")})`;
};

/**
 * One cape (the 22x17 atlas) at `s` px per unit at (ox, oy): grey edges, a glowing band rising up the outside (x 1..10)
 * with `phase` (0..1) moving it, sparks, and the Flint mark; the inside (x 12..21) darker.
 */
function paintCape(g: CanvasRenderingContext2D, ox: number, oy: number, s: number, p: Palette, phase: number) {
  const unit = (x: number, y: number, w: number, h: number, color: string) => {
    g.fillStyle = color;
    g.fillRect(ox + x * s, oy + y * s, w * s, h * s);
  };
  unit(1, 0, 10, 1, p.edge);
  unit(11, 0, 10, 1, p.edge);
  unit(0, 1, 1, 16, p.edge);
  unit(11, 1, 1, 16, p.edge);
  const rows = 16 * s;
  for (let r = 0; r < rows; r++) {
    const y = r / rows; // 0 top .. 1 bottom
    const wave = 0.5 + 0.5 * Math.sin((y * 1.6 + phase) * Math.PI * 2);
    const heat = Math.min(1, Math.max(0, y * 0.9 + wave * 0.35 - 0.1));
    const color = heat < 0.5 ? mix(p.deep, p.mid, heat * 2) : mix(p.mid, p.hot, (heat - 0.5) * 2);
    g.fillStyle = color;
    g.fillRect(ox + 1 * s, oy + s + r, 10 * s, 1);
    g.fillStyle = mix(p.deep, p.mid, heat * 0.5);
    g.fillRect(ox + 12 * s, oy + s + r, 10 * s, 1);
  }
  // Sparks rising with the phase.
  const spark = Math.max(1, Math.round(s / 2));
  for (let k = 0; k < 14; k++) {
    const x = 1 + ((k * 37) % 97) / 10;
    const y = 1 + (((k * 23) % 160) / 10 - phase * 16 + 32) % 16;
    g.fillStyle = k % 3 ? p.hot : "#ffffff";
    g.fillRect(ox + x * s, oy + y * s, spark, spark);
  }
  // The Flint mark: a diamond in the middle of the outside.
  const cx = ox + 6 * s;
  const cy = oy + 7 * s;
  const r = 2.2 * s;
  g.fillStyle = "rgba(0,0,0,0.55)";
  g.beginPath();
  g.moveTo(cx, cy - r);
  g.lineTo(cx + r * 0.7, cy);
  g.lineTo(cx, cy + r);
  g.lineTo(cx - r * 0.7, cy);
  g.closePath();
  g.fill();
  g.fillStyle = "#ffffff";
  const q = r * 0.55;
  g.beginPath();
  g.moveTo(cx, cy - q);
  g.lineTo(cx + q * 0.7, cy);
  g.lineTo(cx, cy + q);
  g.lineTo(cx - q * 0.7, cy);
  g.closePath();
  g.fill();
}

const canvas = (w: number, h: number) => {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  const g = c.getContext("2d")!;
  g.imageSmoothingEnabled = false;
  return { c, g };
};

const textures = new Map<string, string>();

/** The item's texture as `store_asset` answers it (a data URL): the cape, or an animated cape's poster (its frame 0). */
export function capeAsset(cosmeticId: string): string {
  let url = textures.get(cosmeticId);
  if (!url) {
    const item = CAPE_ITEMS.find((i) => i.cosmeticId === cosmeticId) ?? CAPE_ITEMS[0];
    const s = item.animation ? Math.min(16, item.animation.scale) : item.scale;
    const { width, height } = item.animation ? textureSize(item.animation) : { width: 64 * s, height: 32 * s };
    const { c, g } = canvas(width, height);
    paintCape(g, 0, 0, s, cosmeticId === CAPE_IDS.frost ? FROST : EMBER, 0);
    url = c.toDataURL("image/png");
    textures.set(cosmeticId, url);
  }
  return url;
}

export function capeAnimation(cosmeticId: string): CapeAnimation | null {
  return ANIMATIONS[cosmeticId] ?? null;
}

/** Strip `index` of an animated test cape, as `store_cape_strip` answers it (PNG bytes). */
export async function capeStrip(cosmeticId: string, index: number): Promise<ArrayBuffer> {
  const a = ANIMATIONS[cosmeticId];
  if (!a || index < 0 || index >= a.strips.length) throw new Error("This item isn't animated.");
  const frames = a.strips[index].frames;
  const { c, g } = canvas(a.width, frames * a.height);
  for (let k = 0; k < frames; k++) paintCape(g, 0, k * a.height, a.scale, EMBER, (index * a.framesPerStrip + k) / a.frameCount);
  const blob = await new Promise<Blob>((resolve, reject) => c.toBlob((b) => (b ? resolve(b) : reject(new Error("no PNG"))), "image/png"));
  return blob.arrayBuffer();
}
