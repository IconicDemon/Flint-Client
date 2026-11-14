/*
 * The slides Home's sponsored card shows, kept in step with their source. Today that's Flint Admin's showcase feed
 * through the launcher (src-tauri/src/showcase: fetched from the Store, every slide checked, pictures checked against
 * their checksum); `source` below is the one place another source of slides would plug in.
 *
 * Asked once when the card first shows, then every 5 minutes while the window is shown and when it's focused again
 * (the launcher answers from its copy within the Store's 60 s max-age). A refresh that brings the same slides changes
 * nothing on screen; a failure means no slides, so no card. Pictures are fetched once each per run and kept as blob
 * URLs; one that can't be had leaves its slide out.
 */
import { useEffect, useSyncExternalStore } from "react";
import { api } from "./api";
import { EMPTY, FOCUS_MIN_MS, imageType, REFRESH_MS, sameSet, sane, type Slide, type SlideSet, type SlideSource } from "./showcase";
import { onWindowFocus } from "./windowFocus";

/** Flint Admin's launcher slides (Store → Showcase), as the launcher checked them. */
const flintAdmin: SlideSource = {
  load: () => api.showcaseSlides(),
  image: (slide) => api.showcaseImage(slide.id),
  open: (slide) => api.showcaseOpen(slide.id),
};

const source: SlideSource = flintAdmin;

// ---------- the slides ----------

let set: SlideSet = EMPTY;
const listeners = new Set<() => void>();
let started = false;
let inflight: Promise<void> | null = null;
let lastAsked = 0;

function emit() {
  listeners.forEach((l) => l());
}

export function refreshShowcase(): Promise<void> {
  if (inflight) return inflight;
  lastAsked = Date.now();
  inflight = source
    .load()
    .then((answer) => {
      const next = sane(answer);
      if (sameSet(next, set)) return;
      set = next;
      emit();
    })
    .catch(() => {
      // The source failed: no card (as the Store's contract asks).
      if (set.slides.length) {
        set = EMPTY;
        emit();
      }
    })
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

function start() {
  if (started) return;
  started = true;
  void refreshShowcase();
  window.setInterval(() => {
    if (document.visibilityState === "visible") void refreshShowcase();
  }, REFRESH_MS);
  const again = () => {
    if (Date.now() - lastAsked >= FOCUS_MIN_MS) void refreshShowcase();
  };
  onWindowFocus((focused) => focused && again());
  window.addEventListener("focus", again);
  document.addEventListener("visibilitychange", () => document.visibilityState === "visible" && again());
}

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

/** The slides to show (starts asking on first use). */
export function useShowcase(): SlideSet {
  useEffect(start, []);
  return useSyncExternalStore(subscribe, () => set);
}

// ---------- pictures ----------

/** A picture by its checksum: its blob URL, null when it can't be shown, or the request still on its way. */
const pictures = new Map<string, string | null | Promise<string | null>>();

/** The slide's picture as a blob URL, or null when it can't be had (that slide is then left out). Fetched once per run. */
export function slidePicture(slide: Slide): Promise<string | null> {
  const known = pictures.get(slide.imageKey);
  if (known !== undefined) return Promise.resolve(known);
  const loading = source
    .image(slide)
    .then((bytes) => {
      const type = imageType(bytes);
      return type ? URL.createObjectURL(new Blob([bytes], { type })) : null;
    })
    .catch(() => null)
    .then((url) => {
      pictures.set(slide.imageKey, url);
      return url;
    });
  pictures.set(slide.imageKey, loading);
  return loading;
}

/** The picture already fetched for this slide (no waiting), else undefined. */
export function knownPicture(slide: Slide): string | null | undefined {
  const known = pictures.get(slide.imageKey);
  return known instanceof Promise ? undefined : known;
}

/** Opens the slide's link in the system browser. */
export function openSlide(slide: Slide): Promise<void> {
  return source.open(slide).catch(() => undefined);
}
