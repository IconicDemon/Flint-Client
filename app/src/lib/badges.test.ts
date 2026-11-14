// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import { equipBadge, shown, UNKNOWN, type Badges } from "./badges.ts";

test("today's Store (no badges sent) shows the Flint mark only", () => {
  assert.deepEqual(shown(UNKNOWN), { badges: ["default"], badge: "default" });
  assert.deepEqual(shown(null), { badges: ["default"], badge: "default" });
});

test("owned marks: default first and once, unknown names left out, a worn one not owned shows the Flint mark", () => {
  assert.deepEqual(shown({ known: true, badges: ["staff", "default", "emperor", "vip", "staff"], badge: "vip" }), { badges: ["default", "staff", "vip"], badge: "vip" });
  assert.equal(shown({ known: true, badges: ["default", "staff"], badge: "owner" }).badge, "default");
});

test("Equip shows the pick at once and keeps the Store's answer", async () => {
  const seen: Badges[] = [];
  const now: Badges = { known: true, badges: ["default", "staff"], badge: "staff" };
  const error = await equipBadge(now, "default", async (tag) => ({ ...now, badge: tag }), (b) => seen.push(b));
  assert.equal(error, null);
  assert.deepEqual(seen.map((b) => b.badge), ["default", "default"]);
});

test("a refused Equip puts the previous badge back and says why", async () => {
  const seen: Badges[] = [];
  const now: Badges = { known: true, badges: ["default", "staff"], badge: "staff" };
  const error = await equipBadge(now, "default", async () => Promise.reject("You're changing your badge too often. Wait a minute and try again."), (b) => seen.push(b));
  assert.match(error ?? "", /too often/);
  assert.deepEqual(seen.map((b) => b.badge), ["default", "staff"]);
  assert.equal(seen.at(-1), now);
});
