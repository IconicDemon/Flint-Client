import assert from "node:assert/strict";
import { test } from "node:test";
import type { StorageOverview } from "./api.ts";
import { DOWNLOAD_LIMITS, MIN_SEGMENT, STORAGE_CATEGORIES, barSegments, files, limitSetting, shareLabel } from "./storage.ts";

test("download settings stay in range and default to 32 files at once", () => {
  const { maxDownloads, maxInstalls } = DOWNLOAD_LIMITS;
  assert.equal(limitSetting(undefined, maxDownloads), 32);
  assert.equal(limitSetting(null, maxDownloads), 32);
  assert.equal(limitSetting("lots", maxDownloads), 32);
  assert.equal(limitSetting("", maxDownloads), 32);
  assert.equal(limitSetting(8, maxDownloads), 8);
  assert.equal(limitSetting("12", maxDownloads), 12);
  assert.equal(limitSetting(500, maxDownloads), 32);
  assert.equal(limitSetting(0, maxDownloads), 1);
  assert.equal(limitSetting(2.6, maxInstalls), 3);
  assert.equal(limitSetting(undefined, maxInstalls), 2);
  assert.equal(limitSetting(9, maxInstalls), 4);
});

const MB = 1024 * 1024;
type Categories = StorageOverview["categories"];
const cats = (sizes: Partial<Record<Categories[number]["id"], number>>): Categories =>
  Object.entries(sizes).map(([id, bytes]) => ({ id: id as Categories[number]["id"], bytes: bytes ?? 0, files: 1 }));
const sum = (values: number[]) => values.reduce((a, b) => a + b, 0);

test("the bar's segments follow the legend's order and add up to exactly 100", () => {
  const segments = barSegments(cats({ cache: 689 * MB, game: 685 * MB, profiles: 342 * MB, runtimes: 101 * MB, other: 106 * MB }));
  assert.deepEqual(
    segments.map((s) => s.id),
    ["game", "profiles", "runtimes", "cache", "other"],
    "the categories' own order, whatever order they arrive in",
  );
  assert.equal(Math.round(sum(segments.map((s) => s.percent)) * 100) / 100, 100);
  assert.ok(Math.abs(segments[0].percent - (685 / 1923) * 100) < 0.01);
});

test("a tiny category still shows as a sliver, taken from the largest", () => {
  const segments = barSegments(cats({ game: 900 * MB, temporary: 5_000, rollback: 280 }));
  const temporary = segments.find((s) => s.id === "temporary")!;
  const rollback = segments.find((s) => s.id === "rollback")!;
  assert.equal(temporary.percent, MIN_SEGMENT);
  assert.equal(rollback.percent, MIN_SEGMENT);
  assert.equal(segments.find((s) => s.id === "game")!.percent, 100 - 2 * MIN_SEGMENT);
  assert.equal(Math.round(sum(segments.map((s) => s.percent)) * 100) / 100, 100);
});

test("empty and unreadable sizes leave nothing on the bar", () => {
  assert.deepEqual(barSegments([]), []);
  assert.deepEqual(barSegments(cats({ game: 0, cache: 0 })), []);
  assert.deepEqual(
    barSegments(cats({ game: Number.NaN, cache: 10 })).map((s) => [s.id, s.percent]),
    [["cache", 100]],
  );
});

test("one category fills the bar", () => {
  assert.deepEqual(barSegments(cats({ profiles: 42 })), [{ id: "profiles", bytes: 42, percent: 100 }]);
});

test("shares read plainly", () => {
  assert.equal(shareLabel(50, 200), "25%");
  assert.equal(shareLabel(1, 1000), "under 1%");
  assert.equal(shareLabel(0, 1000), "0%");
  assert.equal(shareLabel(5, 0), "0%");
  assert.equal(files(1), "1 file");
  assert.equal(files(2400), `${(2400).toLocaleString()} files`);
});

test("every category the launcher reports has a label and a note", () => {
  for (const id of ["game", "profiles", "runtimes", "cache", "rollback", "sync", "temporary", "other"]) {
    const category = STORAGE_CATEGORIES.find((c) => c.id === id);
    assert.ok(category && category.label && category.note, id);
  }
});
