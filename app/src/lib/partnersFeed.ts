/*
 * The partner listings the window shows, kept in step with the Store (src-tauri/src/partners). Read from the cached copy
 * at start (no waiting for the network on the first paint), asked of the Store on launch and whenever Partnerships
 * opens, then about every 10 minutes while the window is open; the launcher makes each request conditional on the
 * last ETag and respects the Store's max-age. A refresh that brings the same feed changes nothing on screen (no
 * flicker); one that fails keeps showing the cached copy, marked as such.
 */
import { useEffect, useState, useSyncExternalStore } from "react";
import { api, type PartnersAnswer } from "./api";
import { applyAnswer, INITIAL, type PartnersSnapshot } from "./partnersSnapshot";

export type { FeedStatus, PartnersSnapshot } from "./partnersSnapshot";

export const REFRESH_MS = 10 * 60_000;

// ---------- the shared store ----------

let snapshot = INITIAL;
let raw: string | null = null;
const listeners = new Set<() => void>();
let started = false;
let inflight: Promise<void> | null = null;
let lastAsked = 0;

function set(next: PartnersSnapshot) {
  if (next === snapshot) return;
  snapshot = next;
  listeners.forEach((l) => l());
}

function apply(answer: PartnersAnswer, final: boolean) {
  const result = applyAnswer(snapshot, raw, answer, final);
  raw = result.raw;
  if (result.dropped.length && import.meta.env?.DEV) console.info("[partners] left out:", result.dropped);
  set(result.next);
}

const OFFLINE: Omit<PartnersAnswer, "mediaBase" | "mediaProjects" | "allowLoopback"> = { source: "none", problem: "offline", body: null, fetchedAt: null };

/** Asks the Store (the launcher decides whether its last answer is still fresh). One request at a time. */
export function refreshPartners(mode: "auto" | "force" = "auto"): Promise<void> {
  if (inflight) return inflight;
  lastAsked = Date.now();
  inflight = api
    .partnersFeed(mode)
    .then((answer) => apply(answer, true))
    .catch(() => apply({ ...OFFLINE, mediaBase: snapshot.mediaBase, mediaProjects: [], allowLoopback: false }, true))
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

/** Once per window: the cached copy, then the Store, then every 10 minutes while the window is shown. */
export function startPartners() {
  if (started) return;
  started = true;
  void api
    .partnersFeed("cache")
    .then((answer) => apply(answer, false))
    .catch(() => {})
    .finally(() => void refreshPartners("auto"));
  void api
    .partnersFavorites()
    .then((favorites) => set({ ...snapshot, favorites }))
    .catch(() => {});
  window.setInterval(() => {
    if (document.visibilityState === "visible") void refreshPartners("auto");
  }, REFRESH_MS);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && Date.now() - lastAsked > REFRESH_MS) void refreshPartners("auto");
  });
}

export async function toggleFavorite(id: string, on: boolean) {
  const before = snapshot.favorites;
  set({ ...snapshot, favorites: on ? [id, ...before.filter((x) => x !== id)] : before.filter((x) => x !== id) });
  try {
    set({ ...snapshot, favorites: await api.partnersSetFavorite(id, on) });
  } catch (e) {
    set({ ...snapshot, favorites: before });
    throw e;
  }
}

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

/** The partner listings (starts the store on first use). */
export function usePartners(): PartnersSnapshot {
  useEffect(startPartners, []);
  return useSyncExternalStore(subscribe, () => snapshot);
}

/** The time now, updated every `ms`: listings are re-checked against their schedule and statistics against their age. */
export function useNow(ms = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), ms);
    return () => window.clearInterval(timer);
  }, [ms]);
  return now;
}

/** `<mediaBase><sha256>`: a picture as the launcher serves it (checked against its checksum first). */
export const imageSrc = (mediaBase: string, sha256: string) => (mediaBase ? `${mediaBase}${sha256}` : "");
