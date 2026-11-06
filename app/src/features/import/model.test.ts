// Run with `npm test`. Covers what the import table shows and picks, and what the finished screen says.
import assert from "node:assert/strict";
import { test } from "node:test";
import type { ImportProfilePreview, ImportResultProfile, ImportSourceProfile } from "../../lib/api.ts";
import {
  cameOver,
  chips,
  compareVersions,
  countLine,
  headerState,
  importLabel,
  keepSelectable,
  lastPlayedLabel,
  loaderCounts,
  nameList,
  outcome,
  partsByStatus,
  selectRange,
  toggleAll,
  toggleKey,
  visibleProfiles,
  type TableView,
} from "./model.ts";

const NOW = new Date(2026, 10, 2, 15, 0).getTime();
const daysAgo = (d: number) => Math.floor((NOW - d * 86_400_000) / 1000);

const source = (name: string, extra: Partial<ImportSourceProfile> = {}): ImportSourceProfile => ({
  key: name.toLowerCase().replace(/\W+/g, "-"),
  name,
  gameVersion: "26.3",
  loader: "fabric",
  loaderVersion: "0.19.5",
  lastPlayed: null,
  icon: null,
  mods: 10,
  worlds: 1,
  support: "full",
  supportNote: null,
  imported: null,
  sharedFolder: false,
  ...extra,
});

const LIST = [
  source("Iconic", { lastPlayed: daysAgo(0), imported: { profileId: "p1", name: "Iconic" } }),
  source("SMP main", { lastPlayed: daysAgo(1) }),
  source("Demon", { lastPlayed: daysAgo(2) }),
  source("PvP 1.8.9", { gameVersion: "1.8.9", loader: "forge", support: "settings", lastPlayed: daysAgo(5) }),
  source("Create", { gameVersion: "1.20.1", loader: "forge", support: "unsupported", supportNote: "Flint doesn't offer Minecraft 1.20.1.", lastPlayed: daysAgo(9) }),
  source("Vanilla", { loader: "vanilla", gameVersion: "1.21.11", mods: 0 }),
  source("Older SMP", { gameVersion: "1.21.10", lastPlayed: daysAgo(40) }),
];
const VIEW: TableView = { query: "", loader: "all", sort: "played", hideImported: false };
const keys = (rows: ImportSourceProfile[]) => rows.map((r) => r.key);

test("loader filter: All, then only the loaders present, in Flint's order, with counts", () => {
  assert.deepEqual(
    loaderCounts(LIST).map((o) => `${o.label} ${o.count}`),
    ["All 7", "Fabric 4", "Vanilla 1", "Forge 2"],
  );
});

test("search matches name, version and loader; the filter narrows by loader", () => {
  assert.deepEqual(keys(visibleProfiles(LIST, { ...VIEW, query: "smp" })), ["smp-main", "older-smp"]);
  assert.deepEqual(keys(visibleProfiles(LIST, { ...VIEW, query: "1.8" })), ["pvp-1-8-9"]);
  assert.deepEqual(keys(visibleProfiles(LIST, { ...VIEW, query: "FORGE" })), ["pvp-1-8-9", "create"]);
  assert.deepEqual(keys(visibleProfiles(LIST, { ...VIEW, loader: "vanilla" })), ["vanilla"]);
  assert.equal(
    visibleProfiles(LIST, { ...VIEW, hideImported: true }).some((p) => p.imported),
    false,
  );
});

test("sorts: newest played first (never played last), by name, by version", () => {
  assert.deepEqual(keys(visibleProfiles(LIST, VIEW)), ["iconic", "smp-main", "demon", "pvp-1-8-9", "create", "older-smp", "vanilla"]);
  assert.deepEqual(keys(visibleProfiles(LIST, { ...VIEW, sort: "name" })), ["create", "demon", "iconic", "older-smp", "pvp-1-8-9", "smp-main", "vanilla"]);
  const byVersion = visibleProfiles(LIST, { ...VIEW, sort: "version" }).map((p) => p.gameVersion);
  assert.deepEqual(byVersion, ["26.3", "26.3", "26.3", "1.21.11", "1.21.10", "1.20.1", "1.8.9"]);
});

test("versions compare by their numbers", () => {
  assert.ok(compareVersions("1.21.10", "1.21.9") > 0);
  assert.ok(compareVersions("26.1", "1.21.11") > 0);
  assert.ok(compareVersions("26.1.2", "26.1") > 0);
  assert.ok(compareVersions(null, "1.8.9") < 0);
  assert.equal(compareVersions("1.21", "1.21.0"), 0);
});

test("last played reads by calendar day", () => {
  assert.equal(lastPlayedLabel(daysAgo(0), NOW), "Today");
  assert.equal(lastPlayedLabel(Math.floor(new Date(2026, 10, 1, 23, 50).getTime() / 1000), NOW), "Yesterday");
  assert.equal(lastPlayedLabel(daysAgo(3), NOW), "3 d ago");
  assert.equal(lastPlayedLabel(daysAgo(20), NOW), "2 wk ago");
  assert.equal(lastPlayedLabel(daysAgo(100), NOW), "3 mo ago");
  assert.equal(lastPlayedLabel(daysAgo(800), NOW), "2 yr ago");
  assert.equal(lastPlayedLabel(null, NOW), "Never");
});

test("Select all picks what can come over, never a profile already in Flint or one that can't", () => {
  const rows = visibleProfiles(LIST, VIEW);
  const all = toggleAll([], rows);
  assert.deepEqual(all, ["smp-main", "demon", "pvp-1-8-9", "older-smp", "vanilla"]);
  assert.deepEqual(headerState(rows, all), { checked: true, indeterminate: false, disabled: false });
  assert.deepEqual(toggleAll(all, rows), []);
  assert.deepEqual(headerState(rows, ["demon"]), { checked: false, indeterminate: true, disabled: false });
});

test("Select all works on the rows shown and keeps picks hidden by a search", () => {
  const smp = visibleProfiles(LIST, { ...VIEW, query: "smp" });
  const picked = toggleAll(["demon"], smp);
  assert.deepEqual(picked, ["demon", "smp-main", "older-smp"]);
  assert.deepEqual(toggleAll(picked, smp), ["demon"]);
});

test("a profile already in Flint is picked only deliberately, and then counts as a pick", () => {
  const rows = visibleProfiles(LIST, VIEW);
  const deliberate = toggleKey([], "iconic", true);
  assert.deepEqual(deliberate, ["iconic"]);
  assert.equal(headerState(rows, deliberate).indeterminate, true);
});

test("Shift-click picks the range in the order shown, skipping rows that can't come over and ones in Flint", () => {
  const rows = visibleProfiles(LIST, VIEW);
  const range = selectRange(["smp-main"], rows, "smp-main", "older-smp", true);
  assert.deepEqual(range, ["smp-main", "demon", "pvp-1-8-9", "older-smp"]);
  // Across a profile already in Flint: it stays as it was.
  const byName = visibleProfiles(LIST, { ...VIEW, sort: "name" });
  assert.deepEqual(selectRange([], byName, "demon", "older-smp", true), ["demon", "older-smp"]);
  // Upwards, ending on the profile already in Flint: that one was clicked, so it's picked too.
  assert.deepEqual(selectRange([], rows, "demon", "iconic", true), ["iconic", "smp-main", "demon"]);
  // Clearing a range.
  assert.deepEqual(selectRange(range, rows, "older-smp", "demon", false), ["smp-main"]);
  // No anchor: just the row.
  assert.deepEqual(selectRange([], rows, null, "demon", true), ["demon"]);
  assert.deepEqual(selectRange([], rows, null, "create", true), []);
});

test("picks that leave the listing or can't come over any more are dropped", () => {
  assert.deepEqual(keepSelectable(["demon", "gone", "create"], LIST), ["demon"]);
});

test("chips collapse to the first few and +N more", () => {
  assert.deepEqual(chips(["a", "b", "c"]), { shown: ["a", "b", "c"], more: 0 });
  assert.deepEqual(chips(["a", "b", "c", "d", "e"]), { shown: ["a", "b"], more: 3 });
});

test("counts and labels", () => {
  assert.equal(countLine(34, 34, 2), "34 profiles, 2 already in Flint");
  assert.equal(countLine(34, 12, 0), "12 of 34 profiles");
  assert.equal(countLine(1, 1, 0), "1 profile");
  assert.equal(importLabel(0), "Import profiles");
  assert.equal(importLabel(1), "Import 1 profile");
  assert.equal(importLabel(3), "Import 3 profiles");
  assert.equal(nameList(["Demon"]), "Demon");
  assert.equal(nameList(["Demon", "Iconic"]), "Demon and Iconic");
  assert.equal(nameList(["Demon", "Iconic", "Flash"]), "Demon, Iconic and Flash");
  assert.equal(nameList(["Demon", "Iconic", "Flash", "SMP", "Hub"]), "Demon, Iconic and 3 more");
});

const preview = (parts: ImportProfilePreview["parts"]): ImportProfilePreview => ({
  key: "k",
  name: "Iconic",
  target: { name: "Iconic", gameVersion: "26.3", loader: "fabric" },
  parts,
  bytes: 0,
  worldBytes: 0,
  warnings: [],
  blocker: null,
});
const part = (id: ImportProfilePreview["parts"][number]["id"], status: ImportProfilePreview["parts"][number]["status"], count: number, files = count) => ({
  id,
  status,
  files,
  bytes: 1000,
  count,
  note: null,
});

test("parts group by what happens to them, in a fixed order", () => {
  const p = preview([part("servers", "compatible", 1), part("mods", "attention", 35), part("options", "compatible", 2), part("worlds", "unsupported", 2)]);
  const groups = partsByStatus(p);
  assert.deepEqual(
    groups.compatible.map((x) => x.id),
    ["options", "servers"],
  );
  assert.deepEqual(
    groups.attention.map((x) => x.id),
    ["mods"],
  );
  assert.deepEqual(
    groups.unsupported.map((x) => x.id),
    ["worlds"],
  );
});

test("what came over, in words", () => {
  assert.equal(cameOver(preview([part("mods", "compatible", 42), part("options", "compatible", 2)]), 42), "All 42 mods and game settings");
  assert.equal(cameOver(preview([part("mods", "attention", 35), part("resourcepacks", "compatible", 3)]), 36), "35 of 36 mods and 3 resource packs");
  assert.equal(
    cameOver(preview([part("mods", "compatible", 12), part("config", "compatible", 40), part("options", "compatible", 2), part("keybinds", "compatible", 1), part("servers", "compatible", 1)]), 12),
    "All 12 mods, mod settings, game settings and 2 more",
  );
  assert.equal(cameOver(preview([part("mods", "unsupported", 0, 23), part("options", "compatible", 2)]), 23), "Game settings");
  assert.equal(cameOver(preview([]), 0), "Its name and version");
});

const done = (name: string, state: ImportResultProfile["state"] = "done"): ImportResultProfile => ({
  key: name,
  name,
  profileId: state === "done" ? `id-${name}` : null,
  state,
  error: state === "failed" ? "Disk full" : null,
  warnings: [],
  skipped: [],
});

test("the headline never says imported for a profile that wasn't", () => {
  assert.deepEqual(outcome({ profiles: [done("Demon"), done("Iconic")] }, false), {
    tone: "success",
    title: "2 profiles imported",
    body: "Demon and Iconic are now in your Flint profiles.",
    imported: 2,
  });
  assert.equal(outcome({ profiles: [done("Demon")] }, false).title, "1 profile imported");
  const partial = outcome({ profiles: [done("Demon"), done("Fern", "failed"), done("Iconic")] }, false);
  assert.equal(partial.tone, "partial");
  assert.equal(partial.title, "2 of 3 profiles imported");
  assert.equal(partial.body, "Fern couldn't be imported. The others are in your Flint profiles.");
  const none = outcome({ profiles: [done("Fern", "failed")] }, false);
  assert.equal(none.title, "Nothing was imported");
  assert.equal(none.imported, 0);
});

test("a stopped import says so, with what was kept", () => {
  const stopped = outcome({ profiles: [done("Demon"), done("Iconic", "cancelled"), done("Flash", "cancelled")] }, true);
  assert.equal(stopped.title, "Import stopped");
  assert.equal(stopped.body, "Demon was imported before you stopped. The rest weren't copied.");
  assert.equal(outcome({ profiles: [done("Iconic", "cancelled")] }, true).body, "Nothing was imported. Your other launcher is unchanged.");
});
