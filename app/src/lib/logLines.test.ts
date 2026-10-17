// Run with `npm test`.
import assert from "node:assert/strict";
import { test } from "node:test";
import { countEntries, crashDescription, filterLines, parseLog } from "./logLines.ts";

const LATEST = `[11:50:49] [main/INFO]: Loading Minecraft 26.3 with Fabric Loader 0.19.5
[11:50:51] [main/WARN]: Unable to parse version 27.0 to a codename.
[11:50:53] [Render thread/ERROR]: Failed to load mod
java.lang.IllegalStateException: boom
	at net.example.Mod.init(Mod.java:10)
	at net.fabricmc.Loader.run(Loader.java:20)
Caused by: java.lang.NullPointerException
	... 4 more
[11:51:15] [Server thread/INFO]: IconicDemon joined the game
`;

test("lines keep their time, level and stack traces", () => {
  const lines = parseLog(LATEST);
  assert.equal(lines.length, 9);
  assert.equal(lines[0].time, "11:50:49");
  assert.equal(lines[0].text, "[main/INFO]: Loading Minecraft 26.3 with Fabric Loader 0.19.5");
  assert.equal(lines[1].level, "warn");
  assert.equal(lines[2].level, "error");
  assert.ok(lines[4].stack && lines[4].level === "error", "a stack frame belongs to its error");
  assert.ok(lines[6].stack, "Caused by is part of the trace");
  assert.equal(lines[8].level, "info", "the next entry starts fresh");
});

test("filtering to errors keeps the whole trace", () => {
  const errors = filterLines(parseLog(LATEST), "error", "");
  assert.deepEqual(errors.map((l) => l.n), [3, 4, 5, 6, 7, 8]);
  assert.equal(filterLines(parseLog(LATEST), "warn", "").length, 7);
  assert.deepEqual(filterLines(parseLog(LATEST), "all", "joined").map((l) => l.n), [9]);
});

test("entries are counted once, not per stack line", () => {
  assert.deepEqual(countEntries(parseLog(LATEST)), { warn: 1, error: 1 });
});

test("Flint's rendered launch log is understood too", () => {
  const lines = parseLog("[main/ERROR] FabricLoader: Incompatible mods found!\n[Render thread/INFO] Minecraft: Setting user: Steve\n");
  assert.equal(lines[0].level, "error");
  assert.equal(lines[1].level, "info");
});

test("a crash report only colours its errors", () => {
  const report = "---- Minecraft Crash Report ----\n// Why did it break?\n\nTime: now\nDescription: Rendering overlay\n\njava.lang.NullPointerException: x\n\tat a.b(C.java:1)\n\nA detailed walkthrough of the error";
  const lines = parseLog(report);
  assert.deepEqual(lines.map((l) => l.level), ["error", "info", "info", "info", "error", "info", "error", "error", "info", "info"]);
});

test("crash reports show their description", () => {
  const report = "---- Minecraft Crash Report ----\nTime: 2026-10-13\nDescription: Rendering overlay\n\njava.lang.NullPointerException";
  assert.equal(crashDescription(report), "Rendering overlay");
  assert.equal(crashDescription("no report here"), null);
  assert.equal(parseLog(report)[0].level, "error");
});
