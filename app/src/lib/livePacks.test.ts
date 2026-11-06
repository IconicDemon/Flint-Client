import assert from "node:assert/strict";
import { test } from "node:test";
import { IRIS_PROJECT, liveInstall, liveOutcome, removableWhileRunning } from "./livePacks.ts";

const item = (kind: "mod" | "resourcepack" | "shader" | "datapack", projectId = "AAAA1111") => ({ kind, projectId });

test("with the game closed an install is as before", () => {
  assert.deepEqual(liveInstall(false, [item("mod"), item("resourcepack")]), { state: "closed" });
  assert.deepEqual(liveInstall(false, []), { state: "closed" });
});

test("packs go into the running game; mods need a restart", () => {
  assert.deepEqual(liveInstall(true, [item("resourcepack")]), { state: "live", packs: 1, datapack: false });
  assert.deepEqual(liveInstall(true, [item("shader"), item("resourcepack")]), { state: "live", packs: 2, datapack: false });
  assert.deepEqual(liveInstall(true, [item("datapack")]), { state: "live", packs: 0, datapack: true });
  assert.deepEqual(liveInstall(true, [item("mod")]), { state: "blocked", reason: "mods" });
  assert.deepEqual(liveInstall(true, [item("resourcepack"), item("mod")]), { state: "blocked", reason: "mods" });
});

test("a shader pack that brings Iris says the shader loader is what needs the restart", () => {
  assert.deepEqual(liveInstall(true, [item("shader"), item("mod", IRIS_PROJECT)]), { state: "blocked", reason: "iris" });
  // Iris with another mod, or Iris alone, is just mods.
  assert.deepEqual(liveInstall(true, [item("shader"), item("mod", IRIS_PROJECT), item("mod")]), { state: "blocked", reason: "mods" });
  assert.deepEqual(liveInstall(true, [item("mod", IRIS_PROJECT)]), { state: "blocked", reason: "mods" });
});

test("only mods are locked while the game runs", () => {
  assert.equal(removableWhileRunning("resourcepack"), true);
  assert.equal(removableWhileRunning("shader"), true);
  assert.equal(removableWhileRunning("datapack"), true);
  assert.equal(removableWhileRunning("mod"), false);
});

test("a finished job's live result picks its toast", () => {
  assert.equal(liveOutcome({ installed: ["A"], failed: [] }), null);
  assert.equal(liveOutcome(null), null);
  assert.equal(liveOutcome(undefined), null);
  assert.equal(liveOutcome({ installed: ["A"], live: { enable: true, datapack: false } }), "on");
  assert.equal(liveOutcome({ installed: ["A"], live: { enable: false, datapack: false } }), "listed");
  assert.equal(liveOutcome({ installed: ["A"], live: { enable: false, datapack: true } }), "datapack");
  assert.equal(liveOutcome({ installed: ["A", "B"], live: { enable: true, datapack: true } }), "on");
  // A game without Flint's runtime lists the pack but doesn't turn it on by itself.
  assert.equal(liveOutcome({ installed: ["A"], live: { enable: true, datapack: false, applies: false } }), "listed");
  assert.equal(liveOutcome({ installed: ["A"], live: { enable: true, datapack: false, applies: true } }), "on");
});
