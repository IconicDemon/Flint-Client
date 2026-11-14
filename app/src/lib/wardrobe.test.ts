// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import { buildModel, frameDurations, parseGeometry } from "./bedrockModel.ts";
import { CATEGORIES, KIND_SLOT, STORE_SLOTS, categoryOf, categorySlot, kindOf, slotOf } from "./cosmeticTypes.ts";
import type { StoreInventory, StoreItem } from "./storeSync.ts";
import {
  NO_FILTER,
  categoryInfo,
  isAnimated,
  isEquipped,
  itemSource,
  nextOutfitName,
  outfitPlan,
  readOutfits,
  shownCategories,
  skinQuery,
  sortedSkins,
  wardrobeList,
  wheelOf,
  wheelWith,
  wornOutfit,
  wornSlots,
  type Outfit,
} from "./wardrobe.ts";

const item = (id: string, kind: string, name: string, extra: Partial<StoreItem> = {}): StoreItem => ({ cosmeticId: id, slug: id, kind, name, test: false, gift: false, ...extra });
const inventory = (owned: StoreItem[], equipped: StoreInventory["equipped"] = {}, emotes?: (string | null)[]): StoreInventory => ({ version: 1, name: "Steve", balance: 0, owned, equipped, emotes });

test("kinds: every slot has one kind, older names read as today's, unknown kinds go under More", () => {
  for (const slot of STORE_SLOTS) assert.equal(Object.values(KIND_SLOT).filter((s) => s === slot).length, 1, slot);
  assert.equal(kindOf("back"), "wings");
  assert.equal(kindOf("head"), "headwear");
  assert.equal(kindOf("jetpack"), null);
  assert.equal(slotOf({ kind: "gloves" }), "hands");
  assert.equal(slotOf({ kind: "emote" }), null);
  assert.equal(slotOf({ kind: "jetpack", slot: "pet" }), "pet", "Rust's slot wins when it gives one");
  assert.equal(slotOf({ kind: "jetpack", slot: "boots" }), null);
  assert.equal(categoryOf({ kind: "back" }), "wings");
  assert.equal(categoryOf({ kind: "jetpack" }), "more");
  assert.equal(categoryOf({ kind: "bodywear" }), "more", "bodywear is out of this release");
  assert.equal(categorySlot("pets"), "pet");
  assert.equal(categorySlot("emotes"), null);
  // The owner's order: the sidebar ends with outfits, the skin changer and badges (More only when needed).
  assert.deepEqual(
    CATEGORIES.map((c) => c.id),
    ["capes", "wings", "headwear", "auras", "pets", "shields", "gloves", "emotes", "bodywear", "outfits", "skins", "badges", "more"],
  );
});

test("categories count items and mark the ones with something worn; More shows only with an unknown kind", () => {
  const owned = [item("c1", "cape", "Ember"), item("w1", "wings", "Frost"), item("w2", "back", "Old wings"), item("e1", "emote", "Wave"), item("x1", "jetpack", "Jet")];
  const info = categoryInfo(inventory(owned, { back: "w2" }, ["e1", null, null, null, null, null, null, null]), [item("r1", "cape", "Reward", { listed: true })]);
  assert.deepEqual(info.capes, { count: 2, equipped: false }, "a listed reward counts in its category");
  assert.deepEqual(info.wings, { count: 2, equipped: true });
  assert.deepEqual(info.emotes, { count: 1, equipped: true });
  assert.equal(shownCategories(info).includes("more"), true);
  const plain = categoryInfo(inventory([item("c1", "cape", "Ember")], {}, ["gone", null, null, null, null, null, null, null]));
  assert.equal(plain.emotes.equipped, false, "an emote the account doesn't own doesn't count");
  assert.equal(shownCategories(plain).includes("more"), false);
});

test("the grid: one category, searched, filtered and sorted", () => {
  const items = [
    item("a", "wings", "Ángel", { grantedAt: "2026-11-01T00:00:00Z" }),
    item("b", "wings", "Bat", { grantedAt: "2026-11-05T00:00:00Z", asset: { id: "x", version: 1, model: { textureFrames: 4 } } }),
    item("c", "wings", "Crow", { listed: true }),
    item("d", "cape", "Dawn"),
  ];
  const list = (over: Partial<Parameters<typeof wardrobeList>[1]>) => wardrobeList(items, { category: "wings", query: "", sort: "newest", filter: NO_FILTER, ...over }).map((i) => i.cosmeticId);
  assert.deepEqual(list({}), ["b", "a", "c"]);
  assert.deepEqual(list({ sort: "name" }), ["a", "b", "c"]);
  assert.deepEqual(list({ sort: "equipped", equipped: { back: "a" } }), ["a", "b", "c"], "what is worn first, then newest");
  assert.deepEqual(list({ query: "angel" }), ["a"], "accents and case ignored");
  assert.deepEqual(list({ filter: { show: "owned", animated: false } }), ["b", "a"]);
  assert.deepEqual(list({ filter: { show: "locked", animated: false } }), ["c"]);
  assert.deepEqual(list({ filter: { show: "all", animated: true } }), ["b"]);
  assert.equal(isAnimated(item("e", "emote", "Wave", { asset: { id: "x", version: 1, model: { animation: { bytes: 10 } } } })), false, "an emote's animation is the emote");
});

test("equipped, sources and the emote wheel", () => {
  const inv = inventory([item("w", "wings", "W"), item("e", "emote", "E")], { back: "w" }, ["e"]);
  assert.equal(isEquipped(inv, inv.owned[0]), true);
  assert.equal(isEquipped(inv, inv.owned[1]), true, "an emote on the wheel counts as equipped");
  assert.deepEqual(wheelOf(inv), ["e", null, null, null, null, null, null, null]);
  assert.deepEqual(wheelOf(null), Array(8).fill(null));
  assert.deepEqual(wheelWith(wheelOf(inv), 3, "e"), ["e", null, null, "e", null, null, null, null]);
  assert.deepEqual(wheelWith(wheelOf(inv), 9, "e"), wheelOf(inv), "a place off the wheel changes nothing");
  assert.equal(itemSource(item("a", "cape", "A", { obtainedVia: "discord_booster" })), "discord");
  assert.equal(itemSource(item("a", "cape", "A", { gift: true })), "gift");
  assert.equal(itemSource(item("a", "cape", "A", { obtainedVia: "purchase" })), null);
});

test("outfits: saved from what is worn, put on slot by slot, missing items skipped", () => {
  const inv = inventory([item("c1", "cape", "C1"), item("c2", "cape", "C2"), item("w1", "wings", "W1")], { cape: "c1" });
  const outfit: Outfit = { id: "o1", name: "Look", createdAt: 1, slots: { ...wornSlots(inv), back: "w1", head: "gone" } };
  assert.equal(wornSlots(inv).cape, "c1");
  assert.equal(wornSlots(inv).pet, null);
  const plan = outfitPlan(outfit, inv);
  assert.deepEqual(plan.changes, [{ slot: "back", cosmeticId: "w1" }]);
  assert.equal(plan.missing, 1);
  assert.equal(wornOutfit([outfit], inv), null);
  assert.equal(wornOutfit([outfit], inventory(inv.owned, { cape: "c1", back: "w1" }))?.id, undefined, "head names a missing item, so it doesn't match");
  const fits: Outfit = { id: "o2", name: "Fits", createdAt: 1, slots: { cape: "c1", back: null } };
  assert.equal(wornOutfit([fits], inv)?.id, "o2");
  assert.equal(nextOutfitName([{ ...fits, name: "Outfit 2" }], "Outfit"), "Outfit 3");
  assert.deepEqual(readOutfits([{ id: "x", name: "A", slots: { cape: "c1", boots: "b", back: 3 } }, null, "junk"]), [{ id: "x", name: "A", createdAt: 0, slots: { cape: "c1" } }]);
  assert.deepEqual(readOutfits("nope"), []);
});

test("the skin changer reads a username or an https address, and lists favourites first", () => {
  assert.deepEqual(skinQuery("Kestrel_1"), { from: "username", value: "Kestrel_1" });
  assert.deepEqual(skinQuery("  https://example.com/s.png "), { from: "url", value: "https://example.com/s.png" });
  assert.deepEqual(skinQuery("http://example.com/s.png"), { error: "wardrobe.skins.error.https" });
  assert.deepEqual(skinQuery("no spaces please"), { error: "wardrobe.skins.error.badName" });
  assert.deepEqual(skinQuery("ab"), { error: "wardrobe.skins.error.badName" });
  assert.equal(skinQuery("   "), null);
  const skins = [
    { id: "a", addedAt: 1 },
    { id: "b", addedAt: 3 },
    { id: "c", addedAt: 2 },
  ];
  assert.deepEqual(
    sortedSkins(skins, new Set(["a"])).map((s) => s.id),
    ["a", "b", "c"],
  );
});

test("Bedrock geometry: parsed defensively, built on the anchors, frames within the budget", () => {
  assert.equal(parseGeometry("not json"), null);
  assert.equal(parseGeometry("{}"), null);
  const geometry = parseGeometry(
    JSON.stringify({
      format_version: "1.12.0",
      "minecraft:geometry": [
        {
          description: { identifier: "geometry.t", texture_width: 32, texture_height: 32 },
          bones: [
            { name: "anchor_right_arm", pivot: [-5, 22, 0], cubes: [{ origin: [-8, 12, -2], size: [4, 3, 4], uv: [0, 0] }] },
            { name: "anchor_left_arm", pivot: [5, 22, 0], cubes: [{ origin: [4, 12, -2], size: [4, 3, 4], uv: { north: { uv: [0, 0], uv_size: [4, 3] } } }] },
            { name: "tip", parent: "anchor_left_arm", pivot: [6, 12, 0], rotation: [0, 0, 20], cubes: [{ origin: [5, 10, -1], size: [-1, 2, 2], uv: [0, 8] }] },
            { name: "loose", pivot: [0, 24, 0], cubes: [{ origin: [0, 0, 0], size: [1, 1, 1], uv: [0, 0], rotation: [10, 0, 0] }] },
          ],
        },
      ],
    }),
  );
  assert.ok(geometry);
  assert.equal(geometry.textureWidth, 32);
  const built = buildModel(geometry, null, "anchor_body");
  assert.deepEqual(
    built.roots.map((r) => r.anchor),
    ["anchor_right_arm", "anchor_left_arm", "anchor_body"],
    "root bones named after an anchor hang there; others on the item's anchor",
  );
  // The right arm's root sits at its pivot relative to skinview3d's arm (x −5, y 22 → −2): no offset.
  const right = built.roots[0].group.children[0];
  assert.deepEqual([right.position.x, right.position.y, right.position.z].map((n) => n + 0), [0, 0, 0]);
  built.dispose();
  assert.deepEqual(frameDurations(3, [20]), [50, 50, 50], "at least 50 ms a frame");
  assert.deepEqual(frameDurations(2, []), [100, 100]);
  assert.deepEqual(frameDurations(3, [80, 120]), [80, 120, 120]);
});
