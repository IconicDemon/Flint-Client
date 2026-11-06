// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  inventoryList,
  ledgerReason,
  meritShown,
  needsFinder,
  orderRows,
  orderStatus,
  pageButtons,
  paginate,
  pickSlot,
  shownName,
  slotCounts,
  storePhase,
  tilePage,
  withEquipped,
  wornIn,
} from "./storePage.ts";
import type { StoreInventory, StoreItem, StoreOrder, StoreSlot, StoreView } from "./storeSync.ts";

const UUID = "8667ba71b85a4004af54457a9734eed7";
const asset = { id: "a", version: 1 };
const item = (id: string, kind: StoreSlot, name: string, grantedAt: string | null = null, withAsset = true): StoreItem => ({
  cosmeticId: id,
  slug: id,
  kind,
  name,
  test: false,
  gift: false,
  grantedAt,
  asset: withAsset ? asset : null,
});
const inventory = (owned: StoreItem[], equipped: StoreInventory["equipped"] = {}): StoreInventory => ({ version: 1, name: "Steve", balance: 250, owned, equipped });
const view = (over: Partial<StoreView> = {}): StoreView => ({ uuid: UUID, storeUrl: "https://store.flintclient.com", signedIn: true, locked: false, stale: false, inventory: inventory([]), fetchedAt: 1000, ...over });

test("the page's state follows the account and the Store's answer, never guessing", () => {
  const account = { qa: false };
  assert.equal(storePhase({ account: null, view: null, loadError: null }), "noAccount");
  assert.equal(storePhase({ account: { qa: true }, view: null, loadError: null }), "testIdentity");
  assert.equal(storePhase({ account, view: null, loadError: null }), "loading", "a new account shows nothing of the last one");
  assert.equal(storePhase({ account, view: null, loadError: "boom" }), "error");
  assert.equal(storePhase({ account, view: view({ signedIn: false, inventory: null }), loadError: null }), "signedOut");
  assert.equal(storePhase({ account, view: view({ signedIn: false, locked: true, inventory: null }), loadError: null }), "locked");
  assert.equal(storePhase({ account, view: view({ stale: true }), loadError: null }), "offline", "the last copy, read only");
  assert.equal(storePhase({ account, view: view({ stale: true, inventory: null }), loadError: null }), "unreachable", "no copy to show");
  assert.equal(storePhase({ account, view: view(), loadError: "an older failure" }), "ready", "an answer replaces an earlier error");
});

test("the inventory shows one slot, searched and sorted, ties broken by name", () => {
  const owned = [
    item("c1", "cape", "Ember Cape", "2026-10-02T10:00:00Z"),
    item("c2", "cape", "aurora cape", "2026-11-01T10:00:00Z"),
    item("c3", "cape", "Émeraude", "2026-10-02T10:00:00Z"),
    item("c4", "cape", "Cape 10", null),
    item("c5", "cape", "Cape 9", null),
    item("h1", "head", "Crown", "2026-11-03T10:00:00Z"),
  ];
  const names = (list: StoreItem[]) => list.map((i) => i.name);
  assert.deepEqual(names(inventoryList(owned, { slot: "cape", query: "", sort: "newest" })), ["aurora cape", "Ember Cape", "Émeraude", "Cape 9", "Cape 10"]);
  assert.deepEqual(names(inventoryList(owned, { slot: "cape", query: "", sort: "oldest" })), ["Cape 9", "Cape 10", "Ember Cape", "Émeraude", "aurora cape"], "undated items count as oldest");
  assert.deepEqual(names(inventoryList(owned, { slot: "cape", query: "", sort: "name" })), ["aurora cape", "Cape 9", "Cape 10", "Ember Cape", "Émeraude"], "case and accents ignored, numbers in order");
  assert.deepEqual(names(inventoryList(owned, { slot: "cape", query: " EME ", sort: "name" })), ["Émeraude"], "search ignores case, accents and spaces around it");
  assert.deepEqual(names(inventoryList(owned, { slot: "head", query: "", sort: "newest" })), ["Crown"]);
  assert.deepEqual(inventoryList(owned, { slot: "aura", query: "", sort: "newest" }), []);
  assert.equal(owned[0].name, "Ember Cape", "the Store's list is never reordered in place");
});

test("slots: every one counted, the chosen one kept, else the first with items", () => {
  const inv = inventory([item("h1", "head", "Crown"), item("b1", "back", "Wings"), item("b2", "back", "Pack")]);
  assert.deepEqual(slotCounts(inv), { cape: 0, back: 2, head: 1, aura: 0 });
  assert.deepEqual(slotCounts(null), { cape: 0, back: 0, head: 0, aura: 0 });
  assert.equal(pickSlot(inv, null), "back", "capes come first, but there are none");
  assert.equal(pickSlot(inv, "aura"), "aura", "an empty slot the player chose stays chosen");
  assert.equal(pickSlot(inventory([]), null), "cape");
});

test("pages: kept in range, counted from 1 for people, with a compact list of page buttons", () => {
  const list = Array.from({ length: 300 }, (_, i) => i);
  assert.deepEqual({ ...paginate(list, 0, 24), items: undefined }, { items: undefined, page: 0, pages: 13, from: 1, to: 24, total: 300 });
  const last = paginate(list, 12, 24);
  assert.deepEqual([last.from, last.to, last.items.length], [289, 300, 12]);
  assert.equal(paginate(list, 99, 24).page, 12, "past the end: the last page");
  assert.equal(paginate(list, -3, 24).page, 0);
  assert.deepEqual({ ...paginate([], 4, 24), items: undefined }, { items: undefined, page: 0, pages: 1, from: 0, to: 0, total: 0 });
  assert.deepEqual(pageButtons(0, 5), [0, 1, 2, 3, 4]);
  assert.deepEqual(pageButtons(0, 50), [0, 1, 2, 3, null, 49]);
  assert.deepEqual(pageButtons(24, 50), [0, null, 23, 24, 25, null, 49]);
  assert.deepEqual(pageButtons(49, 50), [0, null, 46, 47, 48, 49]);
});

test("tiles: None first on the first page, every page full, and the count is of items only", () => {
  const items = ["a", "b", "c", "d", "e", "f", "g"];
  const first = tilePage(items, 0, 4, true);
  assert.deepEqual(first.tiles, [null, "a", "b", "c"]);
  assert.deepEqual([first.paged.from, first.paged.to, first.paged.total, first.paged.pages], [1, 3, 7, 2]);
  const second = tilePage(items, 1, 4, true);
  assert.deepEqual(second.tiles, ["d", "e", "f", "g"], "the next page carries on after None's place");
  assert.deepEqual([second.paged.from, second.paged.to], [4, 7]);
  const searching = tilePage(items, 0, 4, false);
  assert.deepEqual(searching.tiles, ["a", "b", "c", "d"], "no None while searching");
  assert.deepEqual([searching.paged.from, searching.paged.to, searching.paged.pages], [1, 4, 2]);
  assert.deepEqual(tilePage(items, 9, 4, true).paged.page, 1, "past the end: the last page");
  const few = tilePage(["a", "b"], 0, 20, true);
  assert.deepEqual([few.tiles, few.paged.pages, few.paged.from, few.paged.to], [[null, "a", "b"], 1, 1, 2]);
  assert.deepEqual(tilePage([], 0, 20, true).paged.from, 0);
});

test("a test item's tile name drops the Store's TEST prefix (the tile says Test); other names stay as they are", () => {
  assert.equal(shownName({ name: "TEST · Ember Cape", test: true }), "Ember Cape");
  assert.equal(shownName({ name: "test: Frost", test: true }), "Frost");
  assert.equal(shownName({ name: "Flint Test Cape", test: true }), "Flint Test Cape", "only a leading TEST is dropped");
  assert.equal(shownName({ name: "TEST · ", test: true }), "TEST · ", "never an empty name");
  assert.equal(shownName({ name: "TEST · Ember Cape", test: false }), "TEST · Ember Cape", "only for test items");
  assert.equal(shownName({ name: "Testament Cape", test: true }), "Testament Cape");
});

test("search, order and view show only for a slot with more than 8 items", () => {
  assert.equal(needsFinder(0), false);
  assert.equal(needsFinder(8), false);
  assert.equal(needsFinder(9), true);
  assert.equal(needsFinder(240), true);
});

test("wearing shows at once and changes only the copy on screen", () => {
  const inv = inventory([item("c1", "cape", "Ember"), item("c2", "cape", "Frost")], { cape: "c1" });
  const next = withEquipped(inv, "cape", "c2");
  assert.equal(next.equipped.cape, "c2");
  assert.equal(inv.equipped.cape, "c1", "the Store's answer stays as it came");
  assert.deepEqual(withEquipped(inv, "cape", null).equipped, {}, "None takes it off");
  assert.equal(wornIn(next, "cape")?.name, "Frost");
  assert.equal(wornIn(inventory([item("c1", "cape", "Ember")], { cape: "gone" }), "cape"), null, "an item no longer listed isn't shown as worn");
});

test("Merit: the newest balance the Store gave, with its time; unknown is never zero", () => {
  assert.deepEqual(meritShown(view({ fetchedAt: 1000 }), null), { balance: 250, at: 1000, stale: false });
  assert.deepEqual(meritShown(view({ fetchedAt: 1000 }), { balance: 300, at: 2000 }), { balance: 300, at: 2000, stale: false }, "the wallet answered later");
  assert.deepEqual(meritShown(view({ fetchedAt: 3000 }), { balance: 300, at: 2000 }), { balance: 250, at: 3000, stale: false }, "the inventory answered later");
  assert.deepEqual(meritShown(view({ inventory: { ...inventory([]), balance: null } }), null), { balance: null, at: null, stale: false });
  assert.deepEqual(meritShown(view({ stale: true, fetchedAt: 500 }), null), { balance: 250, at: 500, stale: true });
  assert.deepEqual(meritShown(null, null), { balance: null, at: null, stale: false });
});

test("orders: one row per checkout, with what was paid in money and in Merit, and gifts", () => {
  const order = (over: Partial<StoreOrder>): StoreOrder => ({ id: Math.random().toString(16), testMode: false, gift: false, status: "paid", createdAt: "2026-11-01T10:00:00Z", ...over });
  const rows = orderRows([
    order({ id: "1", reference: "FL-A", item: "Ember Cape", amountMinor: 499, currency: "USD", paymentMethod: "tebex", coins: 500 }),
    order({ id: "2", reference: "FL-A", item: "Frost Cape", amountMinor: 299, currency: "USD", paymentMethod: "tebex", status: "refunded" }),
    order({ id: "3", item: "Aurora Cape", paymentMethod: "flint_coins", coins: 750 }),
    order({ id: "4", kind: "coins", item: "1,000 Merits", amountMinor: 999, currency: "EUR", paymentMethod: "tebex", coins: 1000 }),
    order({ id: "5", reference: "FL-B", item: "Crown", gift: true, recipientName: "Alex", amountMinor: 199, currency: "USD", paymentMethod: "tebex", testMode: true }),
    order({ id: "6", reference: "FL-C", item: "One", amountMinor: 100, currency: "USD", paymentMethod: "tebex" }),
    order({ id: "7", reference: "FL-C", item: "Two", amountMinor: 100, currency: "GBP", paymentMethod: "tebex" }),
  ]);
  assert.deepEqual(
    rows.map((r) => [r.key, r.items, r.money, r.meritSpent, r.meritAdded, r.statuses]),
    [
      ["ref:FL-A", ["Ember Cape", "Frost Cape"], { minor: 798, currency: "USD" }, null, null, ["paid", "refunded"]],
      ["order:3", ["Aurora Cape"], null, 750, null, ["paid"]],
      ["order:4", ["1,000 Merits"], { minor: 999, currency: "EUR" }, null, 1000, ["paid"]],
      ["ref:FL-B", ["Crown"], { minor: 199, currency: "USD" }, null, null, ["paid"]],
      ["ref:FL-C", ["One", "Two"], null, null, null, ["paid"]],
    ],
  );
  assert.equal(rows[0].meritSpent, null, "a cosmetic's Merit price isn't Merit spent when it was paid by card");
  assert.deepEqual([rows[3].gift, rows[3].recipientName, rows[3].testMode], [true, "Alex", true]);
  assert.deepEqual(orderRows([]), []);
});

test("statuses and Merit reasons are words; unknown ones show as the Store wrote them", () => {
  assert.deepEqual(orderStatus("paid"), { label: "store.orders.status.paid", text: "", tone: "success" });
  assert.deepEqual(orderStatus("charged_back"), { label: "store.orders.status.chargedBack", text: "", tone: "danger" });
  assert.deepEqual(orderStatus("held_for_review"), { label: null, text: "held_for_review", tone: "neutral" });
  assert.equal(ledgerReason("daily_reward"), "store.merit.reason.dailyReward");
  assert.equal(ledgerReason("something_new"), null);
  assert.equal(ledgerReason(null), null);
});
