import assert from "node:assert/strict";
import { test } from "node:test";
import type { Profile } from "./api.ts";
import { SIDEBAR_PROFILES, sidebarProfiles } from "./profileOrder.ts";

const profile = (id: string, extra: Partial<Profile> = {}): Profile => ({ id, name: id, gameVersion: "26.3", loader: "fabric", createdAt: 1_800_000_000, installed: true, ...extra });

test("pinned profiles come first, in the order they were pinned", () => {
  const { shown } = sidebarProfiles([profile("a", { playSeconds: 9000 }), profile("b", { pinnedAt: 20 }), profile("c", { pinnedAt: 10 })]);
  assert.deepEqual(shown.map((p) => p.id), ["c", "b", "a"]);
});

test("then the most played: time played, then the latest played, then the newest", () => {
  const { shown } = sidebarProfiles([
    profile("never", { createdAt: 1_900_000_000 }),
    profile("old", { createdAt: 1_700_000_000 }),
    profile("recent", { lastPlayed: 1_850_000_000 }),
    profile("most", { playSeconds: 3600, lastPlayed: 1_810_000_000 }),
    profile("some", { playSeconds: 60 }),
  ]);
  assert.deepEqual(shown.map((p) => p.id), ["most", "some", "recent", "never", "old"]);
});

test("at most five, with View more when there are others", () => {
  const six = ["a", "b", "c", "d", "e", "f"].map((id, i) => profile(id, { playSeconds: 100 - i }));
  const many = sidebarProfiles(six);
  assert.equal(many.shown.length, SIDEBAR_PROFILES);
  assert.equal(many.more, true);
  assert.deepEqual(many.shown.map((p) => p.id), ["a", "b", "c", "d", "e"]);
  const few = sidebarProfiles(six.slice(0, 5));
  assert.equal(few.more, false);
  assert.deepEqual(sidebarProfiles([]), { shown: [], more: false });
});

test("more pinned than fit: the first five pinned", () => {
  const pinned = ["a", "b", "c", "d", "e", "f"].map((id, i) => profile(id, { pinnedAt: i + 1 }));
  const { shown, more } = sidebarProfiles([...pinned, profile("x", { playSeconds: 99999 })]);
  assert.deepEqual(shown.map((p) => p.id), ["a", "b", "c", "d", "e"]);
  assert.equal(more, true);
});
