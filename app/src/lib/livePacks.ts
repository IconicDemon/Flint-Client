// Content added while Minecraft runs (src-tauri/src/content/livepacks.rs): resource packs, shader packs and data packs go
// into the running game at once; mods need a restart.
import type { ContentKind } from "./api";

/** Iris, the shader loader: a mod, so a shader pack that brings it waits for a restart. */
export const IRIS_PROJECT = "YL57xq9U";

/**
 * What an install means for the profile's game: `closed` when it isn't running (as always); `blocked` while it runs and
 * something in the install needs a restart (`iris` when that is the shader loader a shader pack brings); `live` while it runs
 * and everything can go in now (`packs`: resource and shader packs the game can turn on, `datapack`: data packs, which load
 * at the next /reload).
 */
export type LiveInstall = { state: "closed" } | { state: "blocked"; reason: "mods" | "iris" } | { state: "live"; packs: number; datapack: boolean };

export function liveInstall(running: boolean, items: readonly { kind: ContentKind; projectId: string }[]): LiveInstall {
  if (!running) return { state: "closed" };
  const mods = items.filter((i) => i.kind === "mod");
  if (mods.length) {
    const iris = mods.every((m) => m.projectId === IRIS_PROJECT) && items.some((i) => i.kind === "shader");
    return { state: "blocked", reason: iris ? "iris" : "mods" };
  }
  return { state: "live", packs: items.filter((i) => i.kind === "resourcepack" || i.kind === "shader").length, datapack: items.some((i) => i.kind === "datapack") };
}

/** Whether an installed item can be removed while the game runs (packs can; mods need a restart). */
export const removableWhileRunning = (kind: ContentKind) => kind !== "mod";

/**
 * An install job's result when it went into a running game (content.rs `commit_staged`); `applies`: the game turns packs on by
 * itself (Flint's runtime is in it).
 */
export type LiveResult = { installed?: string[]; live?: { enable: boolean; datapack: boolean; applies?: boolean } | null };

/** Which message the finished job's toast gives for a live install, or null for an ordinary one. */
export function liveOutcome(result: unknown): "on" | "listed" | "datapack" | null {
  const live = (result as LiveResult | null | undefined)?.live;
  if (!live || typeof live !== "object") return null;
  if (live.datapack && !live.enable) return "datapack";
  return live.enable && live.applies !== false ? "on" : "listed";
}
