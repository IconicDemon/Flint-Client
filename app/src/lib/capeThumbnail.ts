/**
 * An animated cape as a flat thumbnail (the Store page's tiles): only the cape's front, at a scale kept within a memory
 * budget. Pure, for the tests; the timing is lib/capeAnimation.ts's, the drawing features/store/capeFrames.ts's.
 */
import type { CapeAnimation } from "./capeAnimation.ts";

/** The cape's front in the 22×17 atlas (and the 64×32 texture), in units: what a flat thumbnail shows. */
export const CAPE_FRONT = { x: 1, y: 1, width: 10, height: 16 } as const;

/**
 * The scale (px per unit) to keep a thumbnail's frames at: the manifest's, at most `max`, halved while all its frames'
 * fronts would take more than `budget` bytes as RGBA.
 */
export function thumbnailScale(anim: Pick<CapeAnimation, "scale" | "frameCount">, max: number, budget: number): number {
  let scale = Math.min(anim.scale, max);
  while (scale > 1 && CAPE_FRONT.width * CAPE_FRONT.height * scale * scale * 4 * anim.frameCount > budget) scale /= 2;
  return Math.max(1, Math.floor(scale));
}
