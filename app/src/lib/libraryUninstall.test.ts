import assert from "node:assert/strict";
import { test } from "node:test";
import type { InstalledItem } from "./api.ts";
import { dependentsOf, installedByProject, uninstallItems, withoutItems } from "./libraryUninstall.ts";

const item = (over: Partial<InstalledItem>): InstalledItem => ({
  kind: "mod",
  fileName: "x.jar",
  title: "X",
  source: "modrinth",
  size: 1,
  enabled: true,
  missing: false,
  tracked: true,
  requires: [],
  ...over,
} as InstalledItem);

const sodium = item({ fileName: "sodium.jar", projectId: "AANobbMI", title: "Sodium" });
const reeses = item({ fileName: "reeses.jar", projectId: "reeses", title: "Reese's Sodium Options", requires: ["AANobbMI"] });
const offAddon = item({ fileName: "off.jar.disabled", projectId: "off", enabled: false, requires: ["AANobbMI"] });
const pack = item({ kind: "resourcepack", fileName: "faithful.zip", projectId: "faithful" });
const shader = item({ kind: "shader", fileName: "complementary.zip", projectId: "comp" });
const handAdded = item({ fileName: "local.jar", projectId: null });
const dpOne = item({ kind: "datapack", fileName: "terralith.zip", projectId: "terra", world: "New World" });
const dpA = item({ kind: "datapack", fileName: "vt.zip", projectId: "vt", world: "A" });
const dpB = item({ kind: "datapack", fileName: "vt.zip", projectId: "vt", world: "B" });
const dpNoWorld = item({ kind: "datapack", fileName: "g.zip", projectId: "g", world: null });
const all = [sodium, reeses, offAddon, pack, shader, handAdded, dpOne, dpA, dpB, dpNoWorld];
const map = installedByProject(all);

test("installed items are grouped by project; hand-added files without one are left out", () => {
  assert.deepEqual([...map.keys()].sort(), ["AANobbMI", "comp", "faithful", "g", "off", "reeses", "terra", "vt"]);
  assert.deepEqual(map.get("vt"), [dpA, dpB]);
});

test("mods, resource packs and shader packs uninstall their files; the card's kind must match", () => {
  assert.deepEqual(uninstallItems(map, "AANobbMI", "mod"), [sodium]);
  assert.deepEqual(uninstallItems(map, "faithful", "resourcepack"), [pack]);
  assert.deepEqual(uninstallItems(map, "comp", "shader"), [shader]);
  assert.equal(uninstallItems(map, "faithful", "mod"), null, "installed as another kind: no button");
  assert.equal(uninstallItems(map, "unknown", "mod"), null);
  assert.equal(uninstallItems(map, "AANobbMI", null), null);
});

test("modpacks never; data packs only in exactly one known world", () => {
  assert.equal(uninstallItems(map, "AANobbMI", "modpack"), null);
  assert.deepEqual(uninstallItems(map, "terra", "datapack"), [dpOne]);
  assert.equal(uninstallItems(map, "vt", "datapack"), null, "in two worlds");
  assert.equal(uninstallItems(map, "g", "datapack"), null, "world unknown");
});

test("enabled mods that need the removed one are its dependents, as on the profile page", () => {
  assert.deepEqual(dependentsOf(all, [sodium]), [reeses]);
  assert.deepEqual(dependentsOf(all, [sodium, reeses]), []);
  assert.deepEqual(dependentsOf(all, [pack]), []);
});

test("removed files leave the list at once, matched by kind, world and name", () => {
  assert.deepEqual(withoutItems(all, [sodium]).includes(sodium), false);
  const left = withoutItems(all, [dpA]);
  assert.equal(left.length, all.length - 1);
  assert.ok(left.includes(dpB), "the same file in another world stays");
});
