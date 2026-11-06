/**
 * Animated capes on the Store page beyond the cape a player wears (which lib/displayedCape.ts keeps): the frames of an item
 * pointed at for the stage, and the inventory's moving thumbnails, with one shared clock for those.
 *
 * - Strips come checked from `api.storeCapeStrip` and are decoded by lib/capeTexture.ts (HD at half size).
 * - An item pointed at keeps its decoded strips for the stage's driver (lib/capeTexture.ts `createCapeDriver`); the last
 *   few stay, older ones are freed a little later (the stage may still be drawing one of their frames).
 * - Thumbnails keep only each frame's front (10 × 16 units) at most 8 px per unit and 6 MB a cape, 64 MB together.
 * - Strips load two at a time per cape; nothing moves while the window is hidden or behind another, or nobody has used it
 *   for a minute (lib/renderLoop.ts `motionWanted`), and each thumbnail only while it is on screen.
 */
import { frameAt, isPlayable, type CapeAnimation } from "../../lib/capeAnimation";
import type { PlayableAnimation } from "../../lib/capeSource";
import { CAPE_FRONT, thumbnailScale } from "../../lib/capeThumbnail";
import { decodeStrip, loadStrips, releaseStrips } from "../../lib/capeTexture";
import { motionWanted } from "../../lib/renderLoop";
import { onWindowFocus } from "../../lib/windowFocus";

/** Where an animated cape's strips come from: PNG bytes, one strip at a time. */
export type AnimationSource = { key: string; manifest: CapeAnimation; strip: (index: number) => Promise<ArrayBuffer> };

// ---------- an item pointed at, for the stage ----------

const PREVIEWS_KEPT = 3;
const RELEASE_AFTER_MS = 10_000;
const previews = new Map<string, Promise<PlayableAnimation<ImageBitmap>>>();

/** The item's decoded strips for the stage's cape driver; the same object while it is kept. Rejects if any strip fails. */
export function previewFrames(source: AnimationSource): Promise<PlayableAnimation<ImageBitmap>> {
  const known = previews.get(source.key);
  if (known) {
    previews.delete(source.key);
    previews.set(source.key, known);
    return known;
  }
  const loading = loadStrips(source.manifest, source.strip).then((strips) => ({ manifest: source.manifest, strips }));
  previews.set(source.key, loading);
  loading.catch(() => previews.delete(source.key));
  while (previews.size > PREVIEWS_KEPT) {
    const [oldest, frames] = previews.entries().next().value!;
    previews.delete(oldest);
    frames.then((f) => window.setTimeout(() => releaseStrips(f.strips), RELEASE_AFTER_MS), () => {});
  }
  return loading;
}

// ---------- thumbnails ----------

export type Thumbnail = { manifest: CapeAnimation; width: number; height: number; bytes: number; draw(ctx: CanvasRenderingContext2D, index: number): void };

const THUMB = { max: 8, budget: 6 * 1024 * 1024 };
const THUMBS_BUDGET = 64 * 1024 * 1024;
const thumbs = new Map<string, { frames: Promise<Thumbnail>; bytes: number }>();

async function cutThumbnail(source: AnimationSource): Promise<Thumbnail> {
  const anim = source.manifest;
  if (!isPlayable(anim)) throw new Error("not playable");
  const scale = thumbnailScale(anim, THUMB.max, THUMB.budget);
  const width = CAPE_FRONT.width * scale;
  const height = CAPE_FRONT.height * scale;
  const frames: HTMLCanvasElement[] = new Array(anim.frameCount);
  let next = 0;
  const worker = async () => {
    while (next < anim.strips.length) {
      const i = next++;
      const strip = await decodeStrip(await source.strip(i), anim, i);
      try {
        // 1, or ½ for an HD strip decoded at half size.
        const k = strip.width / anim.width;
        const unit = anim.scale * k;
        for (let f = 0; f < anim.strips[i].frames; f++) {
          const c = document.createElement("canvas");
          c.width = width;
          c.height = height;
          const g = c.getContext("2d")!;
          g.imageSmoothingEnabled = false;
          g.drawImage(strip, CAPE_FRONT.x * unit, f * anim.height * k + CAPE_FRONT.y * unit, CAPE_FRONT.width * unit, CAPE_FRONT.height * unit, 0, 0, width, height);
          frames[i * anim.framesPerStrip + f] = c;
        }
      } finally {
        strip.close();
      }
    }
  };
  await Promise.all([worker(), worker()]);
  return {
    manifest: anim,
    width,
    height,
    bytes: width * height * 4 * anim.frameCount,
    draw(ctx, index) {
      ctx.imageSmoothingEnabled = false;
      ctx.clearRect(0, 0, width, height);
      ctx.drawImage(frames[index] ?? frames[0], 0, 0);
    },
  };
}

/** The animation's thumbnail frames, cut once and shared; the least recently used go past 64 MB. */
export function thumbnailFrames(source: AnimationSource): Promise<Thumbnail> {
  const known = thumbs.get(source.key);
  if (known) {
    thumbs.delete(source.key);
    thumbs.set(source.key, known);
    return known.frames;
  }
  const entry = { frames: cutThumbnail(source), bytes: 0 };
  thumbs.set(source.key, entry);
  entry.frames.then(
    (t) => {
      entry.bytes = t.bytes;
      let total = 0;
      thumbs.forEach((e) => (total += e.bytes));
      for (const [key, e] of thumbs) {
        if (total <= THUMBS_BUDGET) break;
        if (key === source.key || !e.bytes) continue;
        thumbs.delete(key);
        total -= e.bytes;
      }
    },
    () => thumbs.delete(source.key),
  );
  return entry.frames;
}

/** The frame of `thumb` at `epochMs`, on the wall clock as everywhere else. */
export const thumbnailFrame = (thumb: Thumbnail, epochMs: number) => frameAt(thumb.manifest, epochMs);

// ---------- the thumbnails' clock ----------

type Tick = (now: number) => void;
const ticks = new Set<Tick>();
let raf = 0;
let detach: (() => void) | null = null;
const activity = { focused: true, visible: true, inputAt: 0 };

const wanted = () => motionWanted({ enabled: true, visible: activity.visible, focused: activity.focused, now: performance.now(), inputAt: activity.inputAt });

function frame() {
  raf = 0;
  if (!ticks.size || !wanted()) return;
  const now = Date.now();
  ticks.forEach((tick) => tick(now));
  raf = requestAnimationFrame(frame);
}

function wake() {
  if (!raf && ticks.size && wanted()) raf = requestAnimationFrame(frame);
}

function attach() {
  activity.focused = document.hasFocus();
  activity.visible = !document.hidden;
  activity.inputAt = performance.now();
  const onInput = () => {
    activity.inputAt = performance.now();
    wake();
  };
  const onFocus = (focused: boolean) => {
    activity.focused = focused;
    if (focused) onInput();
  };
  const onVisibility = () => {
    activity.visible = !document.hidden;
    wake();
  };
  const onWebFocus = () => onFocus(true);
  const onWebBlur = () => onFocus(false);
  const offNative = onWindowFocus(onFocus);
  window.addEventListener("pointermove", onInput, { passive: true });
  window.addEventListener("pointerdown", onInput, { passive: true });
  window.addEventListener("wheel", onInput, { passive: true });
  window.addEventListener("keydown", onInput);
  window.addEventListener("focus", onWebFocus);
  window.addEventListener("blur", onWebBlur);
  document.addEventListener("visibilitychange", onVisibility);
  detach = () => {
    offNative();
    window.removeEventListener("pointermove", onInput);
    window.removeEventListener("pointerdown", onInput);
    window.removeEventListener("wheel", onInput);
    window.removeEventListener("keydown", onInput);
    window.removeEventListener("focus", onWebFocus);
    window.removeEventListener("blur", onWebBlur);
    document.removeEventListener("visibilitychange", onVisibility);
  };
}

/**
 * Calls `tick` with the wall clock (ms) once a display frame while thumbnails should move; returns the way to stop.
 * Nothing runs once no thumbnail listens, the window is hidden or behind another, or nobody has used it for a minute.
 */
export function onThumbnailTick(tick: Tick): () => void {
  if (!ticks.size && !detach) attach();
  ticks.add(tick);
  wake();
  return () => {
    ticks.delete(tick);
    if (!ticks.size) {
      if (raf) cancelAnimationFrame(raf);
      raf = 0;
      detach?.();
      detach = null;
    }
  };
}
