/*
 * Home's sponsored card, the part that doesn't depend on where slides come from: which slides show now, which comes
 * next (in the order the source gives), and what a picture is. The slides themselves come from a `SlideSource` (lib/showcaseFeed.ts: Flint
 * Admin's showcase feed, through the launcher); the card (features/home/ShowcaseCard.tsx) only ever sees this shape, so
 * another source of slides plugs in without touching it.
 */
import type { ShowcaseSlide } from "./api";

export type Slide = ShowcaseSlide;
export type SlideSet = { slides: Slide[]; rotateMs: number };

/** Where slides come from. Every call may fail; a failure means no slides (or no picture), never an error on screen. */
export interface SlideSource {
  /** The slides to show now, in order. */
  load(): Promise<SlideSet>;
  /** A slide's picture (PNG, JPEG or WebP bytes). */
  image(slide: Slide): Promise<ArrayBuffer>;
  /** Opens the slide's link in the system browser. */
  open(slide: Slide): Promise<void>;
}

export const EMPTY: SlideSet = { slides: [], rotateMs: 6000 };
/** The card asks for slides this often while the window is shown (and when it's focused again). */
export const REFRESH_MS = 5 * 60_000;
/** A window focus asks again only when the last ask is at least this old (the Store caches for 60 s anyway). */
export const FOCUS_MIN_MS = 60_000;
const ROTATE = { min: 3000, max: 30_000, default: 6000 };

/** A slide set as the card may trust it: slides with what they need, the rotation within 3 to 30 s. */
export function sane(set: unknown): SlideSet {
  if (!set || typeof set !== "object") return EMPTY;
  const { slides, rotateMs } = set as Partial<SlideSet>;
  const list = Array.isArray(slides) ? slides.filter((s): s is Slide => !!s && typeof s.id === "string" && typeof s.imageKey === "string" && typeof s.title === "string") : [];
  const ms = typeof rotateMs === "number" && Number.isFinite(rotateMs) ? Math.min(ROTATE.max, Math.max(ROTATE.min, rotateMs)) : ROTATE.default;
  return { slides: list, rotateMs: ms };
}

/** The slides still inside their schedule at `now` (ms): one whose end passed between refreshes is gone. */
export function liveSlides(slides: readonly Slide[], now: number): Slide[] {
  return slides.filter((s) => s.endsAt == null || s.endsAt > now);
}

/** The index after `index` among `count` slides, round and round. */
export const nextIndex = (index: number, count: number) => (count > 0 ? (index + 1) % count : 0);

/** A picture's type by its first bytes: only PNG, JPEG and WebP are shown. */
export function imageType(bytes: ArrayBuffer | Uint8Array): "image/png" | "image/jpeg" | "image/webp" | null {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const at = (i: number, ...values: number[]) => values.every((v, k) => b[i + k] === v);
  if (at(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return "image/png";
  if (at(0, 0xff, 0xd8, 0xff)) return "image/jpeg";
  if (b.length > 12 && at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) return "image/webp";
  return null;
}

/** Two answers that show the same thing (the card doesn't re-render for a refresh that changed nothing). */
export function sameSet(a: SlideSet, b: SlideSet): boolean {
  return a.rotateMs === b.rotateMs && a.slides.length === b.slides.length && a.slides.every((s, i) => JSON.stringify(s) === JSON.stringify(b.slides[i]));
}

/**
 * A rotation that can pause: how long the current slide has shown, counting only the time it wasn't paused. `left()` is
 * what remains of `duration`; the card sets its timer from it and restarts it on every slide change.
 */
export function rotation(duration: number, clock: () => number = () => performance.now()) {
  let shown = 0;
  let since: number | null = clock();
  return {
    pause() {
      if (since != null) shown += clock() - since;
      since = null;
    },
    resume() {
      if (since == null) since = clock();
    },
    paused: () => since == null,
    left: () => Math.max(0, duration - shown - (since != null ? clock() - since : 0)),
  };
}
