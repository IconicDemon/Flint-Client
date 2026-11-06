// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  formatStoreMerit,
  isWorn,
  meritText,
  moneyText,
  needsRefresh,
  nextPollDelay,
  normalizeStoreCode,
  POLL_DEFAULT_MS,
  POLL_MAX_MS,
  reasonText,
  slotsWithItems,
  storeHost,
  updatedText,
  viewFor,
  type StoreInventory,
  type StoreView,
} from "./storeSync.ts";

const item = (cosmeticId: string, kind: "cape" | "head" = "cape") => ({ cosmeticId, slug: cosmeticId, kind, name: cosmeticId, test: true, gift: false });

test("the poll follows the Store's interval, backs off while offline and stops when signed out", () => {
  assert.equal(nextPollDelay(POLL_DEFAULT_MS, { state: "ok", changed: false, version: 3, pollAfterSeconds: 25 }), 25_000);
  assert.equal(nextPollDelay(POLL_DEFAULT_MS, { state: "ok", changed: false, version: 3, pollAfterSeconds: 1 }), 10_000, "never faster than 10 s");
  assert.equal(nextPollDelay(POLL_DEFAULT_MS, { state: "ok", changed: true, version: 3, pollAfterSeconds: 9999 }), POLL_MAX_MS);
  let delay = POLL_DEFAULT_MS;
  const seen: number[] = [];
  for (let i = 0; i < 6; i++) seen.push((delay = nextPollDelay(delay, { state: "offline" })!));
  assert.deepEqual(seen, [50_000, 100_000, 200_000, 300_000, 300_000, 300_000]);
  assert.equal(nextPollDelay(POLL_MAX_MS, { state: "ok", changed: false, version: 3, pollAfterSeconds: 25 }), 25_000, "back to normal once online");
  assert.equal(nextPollDelay(POLL_DEFAULT_MS, { state: "signedOut" }), null);
});

test("the inventory is fetched again only when something changed or the connection did", () => {
  const ok = (changed: boolean) => ({ state: "ok" as const, changed, version: 1, pollAfterSeconds: 25 });
  assert.equal(needsRefresh(ok(false), false), false, "unchanged: nothing to fetch");
  assert.equal(needsRefresh(ok(true), false), true);
  assert.equal(needsRefresh(ok(false), true), true, "the Store answers again: replace the stale copy");
  assert.equal(needsRefresh({ state: "offline" }, false), true, "offline: show the copy as stale");
  assert.equal(needsRefresh({ state: "offline" }, true), false, "already shown as stale");
  assert.equal(needsRefresh({ state: "signedOut" }, false), true);
});

test("browser codes are read the way the Store writes them", () => {
  assert.equal(normalizeStoreCode("GXV9-MC1G"), "GXV9-MC1G");
  assert.equal(normalizeStoreCode(" gxv9 mc1g "), "GXV9-MC1G");
  assert.equal(normalizeStoreCode("gxv9-mclg"), "GXV9-MC1G", "l reads as 1");
  assert.equal(normalizeStoreCode("O0I1-ABCD"), "0011-ABCD");
  assert.equal(normalizeStoreCode("GXV9-MC1U"), null, "U isn't in the Store's alphabet");
  assert.equal(normalizeStoreCode("GXV9MC1"), null);
  assert.equal(normalizeStoreCode("GXV9-MC1GG"), null);
  assert.equal(normalizeStoreCode(""), null);
});

test("an answer counts only for the account it was asked for", () => {
  const view: StoreView = { uuid: "8667ba71b85a4004af54457a9734eed7", storeUrl: "https://store.flintclient.com", signedIn: true, locked: false, stale: false };
  assert.equal(viewFor("8667ba71-b85a-4004-af54-457a9734eed7", view), view);
  assert.equal(viewFor("ec561538f3fd461daff5086b22154bce", view), null, "Alex never sees Steve's page");
  assert.equal(viewFor("8667ba71b85a4004af54457a9734eed7", null), null);
});

test("what is worn comes from the Store's equipped list", () => {
  const inventory: StoreInventory = { version: 2, name: "Steve", balance: 1250, owned: [item("a"), item("b"), item("h", "head")], equipped: { cape: "b" } };
  assert.equal(isWorn(inventory, inventory.owned[0]), false);
  assert.equal(isWorn(inventory, inventory.owned[1]), true);
  assert.deepEqual(slotsWithItems(inventory), ["cape", "head"]);
});

test("Merit, money, reasons and the age of the copy read as words", () => {
  assert.equal(formatStoreMerit(1250), "1,250");
  assert.equal(formatStoreMerit(null), "—", "unknown is never shown as zero");
  assert.equal(meritText(500), "500 Merits");
  assert.equal(meritText(1), "1 Merit");
  assert.equal(meritText(0), "0 Merits");
  assert.equal(meritText(null), null, "unknown is never shown as zero");
  assert.equal(reasonText("daily_reward"), "Daily reward");
  assert.equal(moneyText(499, "USD"), "$4.99");
  assert.equal(moneyText(null, "USD"), "");
  assert.equal(reasonText("test_grant"), "Test grant");
  assert.equal(storeHost("https://store.flintclient.com"), "store.flintclient.com");
  assert.equal(storeHost("http://localhost:3214"), "localhost:3214");
  const now = 1_000_000_000;
  assert.equal(updatedText(now - 5_000, now), "Updated just now");
  assert.equal(updatedText(now - 4 * 60_000, now), "Updated 4 min ago");
  assert.equal(updatedText(null, now), "Not updated yet");
});
