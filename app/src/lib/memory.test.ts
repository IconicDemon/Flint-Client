import assert from "node:assert/strict";
import { test } from "node:test";
import { UNKNOWN_LIMITS, effectiveMemory, gbLabel, limitsFrom, machineLabel, memoryDescription, type MemoryLimits } from "./memory.ts";

// An 8 GB PC as the launcher reports it (data.rs memory_limits).
const EIGHT: MemoryLimits = { totalMb: 8192, maxMb: 5632, recommendedMb: 3072, warnMb: 4608 };

test("an 8 GB computer says 8 GB and never offers or shows more than it can spare", () => {
  assert.equal(machineLabel(8192), "8 GB");
  assert.equal(machineLabel(7990), "8 GB", "usable memory still reads as installed");
  const text = memoryDescription(EIGHT);
  assert.match(text, /This computer has 8 GB/);
  assert.match(text, /up to 5\.5 GB/);
  assert.doesNotMatch(text, /16/);
  // A 16 GB allocation saved earlier shows (and launches) as this computer's limit.
  assert.equal(effectiveMemory(16384, EIGHT), 5632);
  assert.equal(gbLabel(effectiveMemory(16384, EIGHT)), "5.5 GB");
  assert.equal(effectiveMemory(4096, EIGHT), 4096);
  assert.equal(effectiveMemory(256, EIGHT), 1024, "never under 1 GB");
});

test("without a reading the limits are the launcher's safe ones, not a 16 GB guess", () => {
  assert.deepEqual(limitsFrom(null), UNKNOWN_LIMITS);
  assert.deepEqual(limitsFrom({ memory: null }), UNKNOWN_LIMITS);
  assert.deepEqual(limitsFrom({ memory: { ...EIGHT, maxMb: Number.NaN } }), UNKNOWN_LIMITS);
  assert.deepEqual(limitsFrom({ memory: EIGHT }), EIGHT);
  assert.equal(effectiveMemory(16384, UNKNOWN_LIMITS), 4096);
  assert.doesNotMatch(memoryDescription(UNKNOWN_LIMITS), /This computer has/);
});

import { memoryAdvice, overridesLine } from "./memory.ts";

test("the advice gives the range most profiles need and says more isn't faster", () => {
  const text = memoryAdvice(EIGHT);
  assert.match(text, /Most profiles run well with 2–3 GB \(this computer has 8 GB, so up to 5\.5 GB\)/);
  assert.match(text, /2 GB is what Minecraft's own launcher gives/);
  assert.match(text, /More rarely helps/);
  assert.match(memoryAdvice(UNKNOWN_LIMITS), /up to 4\.0 GB here/);
});

test("profiles with their own memory are named, since it takes this setting's place", () => {
  assert.equal(overridesLine([{ name: "A" }, { name: "B", memoryMb: null }]), null);
  assert.equal(overridesLine([{ name: "Survival", memoryMb: 6144 }]), "‘Survival’ (6.0 GB) uses its own memory, set in Edit profile.");
  assert.equal(overridesLine([{ name: "A", memoryMb: 2048 }, { name: "B", memoryMb: 4096 }]), "‘A’ (2.0 GB) and ‘B’ (4.0 GB) use their own memory, set in Edit profile.");
  assert.equal(
    overridesLine(["A", "B", "C", "D", "E"].map((name) => ({ name, memoryMb: 2048 }))),
    "‘A’ (2.0 GB), ‘B’ (2.0 GB), ‘C’ (2.0 GB) and 2 more use their own memory, set in Edit profile.",
  );
});

import { readFileSync } from "node:fs";
import { setLanguage } from "./i18n.ts";
import { memoryInputText, memoryUnit, parseMemoryInput } from "./memory.ts";

// The sliders' own range on that 8 GB PC: 1 GB up to its limit, in 0.5 GB steps (Workspace.tsx, SettingsJava.tsx).
const RANGE = { min: 1024, max: EIGHT.maxMb, step: 512 };

test("a typed memory size is read in GB, with either decimal mark and an optional unit", () => {
  assert.deepEqual(parseMemoryInput("4", RANGE), { mb: 4096, adjusted: null });
  assert.deepEqual(parseMemoryInput("4.5", RANGE), { mb: 4608, adjusted: null });
  assert.deepEqual(parseMemoryInput("4,5", RANGE), { mb: 4608, adjusted: null }, "a decimal comma is a decimal mark");
  assert.deepEqual(parseMemoryInput(" 3 GB ", RANGE), { mb: 3072, adjusted: null });
  assert.deepEqual(parseMemoryInput("3gb", RANGE), { mb: 3072, adjusted: null });
  assert.deepEqual(parseMemoryInput("3 G", RANGE), { mb: 3072, adjusted: null });
  assert.deepEqual(parseMemoryInput("2.", RANGE), { mb: 2048, adjusted: null });
  assert.deepEqual(parseMemoryInput(".5", { ...RANGE, min: 512 }), { mb: 512, adjusted: null });
  assert.deepEqual(parseMemoryInput("3 ГБ", RANGE), { mb: 3072, adjusted: null }, "the language's own unit");
});

test("MB is read when it says so, or from a bare number of 256 and up", () => {
  assert.deepEqual(parseMemoryInput("4096", RANGE), { mb: 4096, adjusted: null });
  assert.deepEqual(parseMemoryInput("3072 MB", RANGE), { mb: 3072, adjusted: null });
  assert.deepEqual(parseMemoryInput("2048m", RANGE), { mb: 2048, adjusted: null });
  assert.deepEqual(parseMemoryInput("255", RANGE), { mb: RANGE.max, adjusted: "max" }, "under 256 it is GB: 255 GB is held at the limit");
});

test("a typed size takes the slider's steps and stays between its ends", () => {
  assert.deepEqual(parseMemoryInput("6.3", { ...RANGE, max: 8192 }), { mb: 6656, adjusted: "rounded" }, "6.3 GB is set as 6.5 GB");
  assert.deepEqual(parseMemoryInput("6.2", { ...RANGE, max: 8192 }), { mb: 6144, adjusted: "rounded" });
  assert.deepEqual(parseMemoryInput("4100", RANGE), { mb: 4096, adjusted: "rounded" });
  assert.deepEqual(parseMemoryInput("64", RANGE), { mb: 5632, adjusted: "max" }, "never above what this computer can spare");
  assert.deepEqual(parseMemoryInput("0.5", RANGE), { mb: 1024, adjusted: "min" }, "never under 1 GB");
  assert.deepEqual(parseMemoryInput("5.5", RANGE), { mb: 5632, adjusted: null }, "the limit itself is fine");
  // A limit between two steps is still reachable: nearer the limit than the step below, it is the limit.
  assert.deepEqual(parseMemoryInput("5.7", { ...RANGE, max: 5900 }), { mb: 5900, adjusted: "rounded" });
});

test("anything that isn't a size changes nothing", () => {
  for (const text of ["", "  ", "lots", "abc4", "4 apples", "-2", "0", "0.0", "1e3", "4..5", "4 5", "6.5.1", "∞", "NaN"]) {
    assert.equal(parseMemoryInput(text, RANGE), null, JSON.stringify(text));
  }
});

test("the field shows GB with one decimal as the language writes it", async () => {
  assert.equal(memoryInputText(4096), "4.0");
  assert.equal(memoryInputText(6656), "6.5");
  assert.equal(memoryUnit(), "GB");
  const catalog = (locale: string) => JSON.parse(readFileSync(new URL(`../locales/${locale}.json`, import.meta.url), "utf8"));
  try {
    await setLanguage("de", async (locale) => catalog(locale));
    assert.equal(memoryInputText(6656), "6,5");
    assert.equal(memoryInputText(12288), "12,0", "no thousands grouping in a field");
    await setLanguage("ru", async (locale) => catalog(locale));
    assert.equal(memoryUnit(), "ГБ");
    assert.deepEqual(parseMemoryInput("6,5 ГБ", { ...RANGE, max: 8192 }, memoryUnit()), { mb: 6656, adjusted: null });
  } finally {
    await setLanguage("en");
  }
});
