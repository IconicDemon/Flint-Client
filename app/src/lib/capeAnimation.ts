/**
 * Animated Flint Store capes: the timing and layout the launcher's previews play them by. The manifest is the Store's
 * `asset.animation` (Store docs/LAUNCHER-API.md "Animated capes"), checked in Rust (src-tauri/src/store/anim.rs) and handed
 * over without strip addresses or hashes; the strips themselves come checked from `api.storeCapeStrip`.
 *
 * - A frame is the cape region of Minecraft's 64×32 cape texture (the 22×17 atlas) at `scale` pixels per unit. Previews draw
 *   it at the top left of a 64×32-per-unit texture, so the cape's UVs (normalized to 64×32) stay right at any scale.
 * - Timing is the wall clock's, exactly as in the game (game-runtime `CapeAnimation.frameAt`): frame `f` shows for
 *   `durationsMs[f]`, the loop starts at `epochMs % durationMs`, so the launcher and every game show the same frame.
 *
 * Pure: no DOM, so the Node tests run it.
 */

/** As `store_inventory` hands it over (camelCase; no strip addresses or hashes). */
export type CapeAnimation = {
  /** Pixels per atlas unit: 4, 8, 16 or 32 (HD). */
  scale: number;
  /** One frame: 22 × scale by 17 × scale pixels. */
  width: number;
  height: number;
  frameCount: number;
  durationsMs: number[];
  /** One loop: the durations' sum. */
  durationMs: number;
  framesPerStrip: number;
  strips: { frames: number }[];
};

export const ATLAS = { width: 22, height: 17 } as const;
/** Previews never draw more than 16 px per unit (a 1024×512 texture): HD frames are halved, as the Store's pages do. */
export const MAX_PREVIEW_SCALE = 16;

/** Whether `a` is a manifest this player can play: the Rust side checked it; this only guards the shapes the code relies on. */
export function isPlayable(a: CapeAnimation | null | undefined): a is CapeAnimation {
  if (!a || ![4, 8, 16, 32].includes(a.scale) || a.width !== ATLAS.width * a.scale || a.height !== ATLAS.height * a.scale) return false;
  if (!Array.isArray(a.durationsMs) || a.durationsMs.length !== a.frameCount || a.frameCount < 2) return false;
  if (!a.durationsMs.every((d) => Number.isInteger(d) && d > 0) || a.durationsMs.reduce((s, d) => s + d, 0) !== a.durationMs) return false;
  if (!(a.framesPerStrip >= 1) || !Array.isArray(a.strips) || a.strips.length !== Math.ceil(a.frameCount / a.framesPerStrip)) return false;
  return a.strips.every((s, i) => s.frames === Math.min(a.framesPerStrip, a.frameCount - i * a.framesPerStrip));
}

/** `ends[f]`: when frame `f` ends, in ms from the loop's start (exclusive); the last is the loop. Cached per manifest. */
const endsCache = new WeakMap<CapeAnimation, number[]>();
function ends(a: CapeAnimation): number[] {
  let e = endsCache.get(a);
  if (!e) {
    let sum = 0;
    e = a.durationsMs.map((d) => (sum += d));
    endsCache.set(a, e);
  }
  return e;
}

const floorMod = (x: number, m: number) => ((x % m) + m) % m;

/** The frame shown at `epochMs` (wall clock): a binary search over the frames' end times. */
export function frameAt(a: CapeAnimation, epochMs: number): number {
  const e = ends(a);
  const t = floorMod(Math.floor(epochMs), a.durationMs);
  let lo = 0;
  let hi = e.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (e[mid] > t) hi = mid;
    else lo = mid + 1;
  }
  return lo;
}

/** Milliseconds from `epochMs` until the frame changes (at least 1). */
export function untilNextFrame(a: CapeAnimation, epochMs: number): number {
  const e = ends(a);
  const t = floorMod(Math.floor(epochMs), a.durationMs);
  return Math.max(1, e[frameAt(a, epochMs)] - t);
}

/** Where frame `f` is: strip `strip`, rows `y`..`y + height` of it (in the strip's own pixels). */
export function framePlace(a: CapeAnimation, f: number): { strip: number; y: number } {
  return { strip: Math.floor(f / a.framesPerStrip), y: (f % a.framesPerStrip) * a.height };
}

/** Pixels per unit a preview draws at: the animation's, at most 16. */
export const previewScale = (a: Pick<CapeAnimation, "scale">) => Math.min(a.scale, MAX_PREVIEW_SCALE);

/** The cape texture a preview draws frames into: 64×32 per unit (at most 1024×512); the atlas sits at its top left. */
export function textureSize(a: Pick<CapeAnimation, "scale">): { width: number; height: number } {
  const s = previewScale(a);
  return { width: 64 * s, height: 32 * s };
}

/** Whether strips are decoded at half size (HD, 32 px per unit): an HD 120-frame cape is then ~46 MB as RGBA, not 184. */
export const decodesHalved = (a: Pick<CapeAnimation, "scale">) => a.scale > MAX_PREVIEW_SCALE;
