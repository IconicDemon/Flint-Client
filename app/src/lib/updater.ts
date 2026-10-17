// Flint's self-update, on Tauri's official updater plugin: the release manifest is signed per platform, and
// every download is checked against the public key compiled into the app before anything is installed.
//
// One shared state for the whole launcher (the top-bar Update button and Settings → About read it):
// - A quiet check a few seconds after start-up, then every few hours while Flint is open. A failed quiet
//   check (offline, server down, no build for this platform) leaves the launcher exactly as it was.
// - "Check for updates" (Settings) is the manual check: it reports "You're up to date" or the error.
// - Download shows real progress from the updater's byte events (no estimate).
// - Installing replaces the app and restarts Flint, so it waits while Minecraft is running.
import { check, type DownloadEvent, type Update } from "@tauri-apps/plugin-updater";
import { relaunch } from "@tauri-apps/plugin-process";
import { useSyncExternalStore } from "react";

/** Only the desktop app updates itself (the browser preview has no updater). */
const isTauri = () => typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

export type UpdateState =
  | { phase: "idle" }
  | { phase: "checking"; manual: boolean }
  | { phase: "current"; checkedAt: number }
  | { phase: "available"; version: string; notes: string; date: string | null }
  | { phase: "downloading"; version: string; received: number; total: number | null }
  | { phase: "ready"; version: string }
  | { phase: "installing"; version: string }
  | { phase: "error"; message: string; during: "check" | "download" | "install"; version: string | null };

/** The first quiet check waits this long after start-up, so it never competes with the launcher loading. */
export const FIRST_CHECK_DELAY_MS = 4_000;
/** Quiet checks while Flint stays open. */
export const RECHECK_EVERY_MS = 6 * 60 * 60 * 1000;
/** One check gives up after this long. */
export const CHECK_TIMEOUT_MS = 15_000;

let state: UpdateState = { phase: "idle" };
let pending: Update | null = null;
let busy = false;
const listeners = new Set<() => void>();

function set(next: UpdateState) {
  state = next;
  for (const listener of listeners) listener();
}

export function updateState(): UpdateState {
  return state;
}

export function subscribeUpdates(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useUpdates(): UpdateState {
  return useSyncExternalStore(subscribeUpdates, updateState, updateState);
}

/**
 * The error text people see: short, without stack traces or URLs. The patterns match the updater plugin's
 * own messages (tauri-plugin-updater 2.14; see updater.test.ts for the exact texts).
 */
export function friendlyError(error: unknown, during: "check" | "download" | "install" = "check"): string {
  const text = String(error instanceof Error ? error.message : error ?? "");
  if (/signature|signed for version|different key/i.test(text)) return "The download didn't pass Flint's signature check, so it wasn't installed.";
  if (/platform/i.test(text)) return "There's no update for this computer yet.";
  if (/timed? ?out|timeout/i.test(text)) return "The update server didn't answer in time.";
  if (during === "download" && /decoding response body|status: \d+/i.test(text)) return "The download didn't finish. Try again.";
  if (/decoding response body|valid release JSON/i.test(text)) return "The update server didn't answer properly. Try again later.";
  if (/network|connect|dns|resolve|offline|request/i.test(text)) return "Couldn't reach the update server. Check your connection.";
  return "The update couldn't be completed.";
}

/**
 * Looks for a newer version. Quiet checks (`manual` false) never show an error and never interrupt a
 * download or a ready update; a manual check reports what it found.
 */
export async function checkForUpdates(manual = false): Promise<void> {
  if (!isTauri() || busy) return;
  if (!manual && (state.phase === "downloading" || state.phase === "ready" || state.phase === "installing")) return;
  busy = true;
  const before = state;
  if (manual) set({ phase: "checking", manual });
  try {
    const update = await check({ timeout: CHECK_TIMEOUT_MS });
    if (update) {
      pending = update;
      set({ phase: "available", version: update.version, notes: update.body ?? "", date: update.date ?? null });
    } else {
      pending = null;
      set(manual ? { phase: "current", checkedAt: Date.now() } : { phase: "idle" });
    }
  } catch (error) {
    if (manual) {
      set({ phase: "error", message: friendlyError(error), during: "check", version: null });
    } else {
      // A quiet check that fails changes nothing (offline start-up stays clean).
      console.warn("Flint update check failed", error);
      set(before.phase === "checking" ? { phase: "idle" } : before);
    }
  } finally {
    busy = false;
  }
}

/** Downloads the available update with real progress, verifying its signature; it installs on restart. */
export async function downloadUpdate(): Promise<void> {
  const update = pending;
  if (!update || busy) return;
  busy = true;
  let received = 0;
  let total: number | null = null;
  set({ phase: "downloading", version: update.version, received, total });
  try {
    await update.download((event: DownloadEvent) => {
      if (event.event === "Started") {
        total = event.data.contentLength ?? null;
      } else if (event.event === "Progress") {
        received += event.data.chunkLength;
      }
      set({ phase: "downloading", version: update.version, received, total });
    });
    set({ phase: "ready", version: update.version });
  } catch (error) {
    set({ phase: "error", message: friendlyError(error, "download"), during: "download", version: update.version });
  } finally {
    busy = false;
  }
}

/**
 * Installs the downloaded update and restarts Flint. Refused while Minecraft runs: replacing the app under
 * a running game, or restarting it, would end the player's session.
 */
export async function installAndRestart(gameRunning: boolean): Promise<void> {
  const update = pending;
  if (!update || busy || !canInstallNow(state, gameRunning)) return;
  busy = true;
  set({ phase: "installing", version: update.version });
  try {
    await update.install();
    await relaunch();
  } catch (error) {
    set({ phase: "error", message: friendlyError(error, "install"), during: "install", version: update.version });
  } finally {
    busy = false;
  }
}

/** A downloaded update installs only while no game launched from Flint is starting or running. */
export function canInstallNow(current: UpdateState, gameRunning: boolean): boolean {
  return current.phase === "ready" && !gameRunning;
}

/** After a failed download or install: back to "available" so Retry starts again. */
export function retryUpdate(): void {
  if (state.phase !== "error") return;
  if (state.during === "check" || !pending) {
    void checkForUpdates(true);
  } else {
    set({ phase: "available", version: pending.version, notes: pending.body ?? "", date: pending.date ?? null });
    void downloadUpdate();
  }
}

/** Starts the quiet checks (once, from the shell). Returns a stop function. */
export function startUpdateChecks(): () => void {
  const first = window.setTimeout(() => void checkForUpdates(false), FIRST_CHECK_DELAY_MS);
  const every = window.setInterval(() => void checkForUpdates(false), RECHECK_EVERY_MS);
  return () => {
    window.clearTimeout(first);
    window.clearInterval(every);
  };
}

/** "45%" when the size is known, else the amount received ("12.3 MB"). */
export function progressLabel(received: number, total: number | null): string {
  if (total && total > 0) return `${Math.min(100, Math.floor((received / total) * 100))}%`;
  return `${(received / 1_000_000).toFixed(1)} MB`;
}
