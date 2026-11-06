// Run with `npm test`. The window's copy of the partner listings (src/lib/partnersSnapshot.ts): no flicker on refresh,
// the cached copy while offline, and the loading, empty and offline states.
import assert from "node:assert/strict";
import { test } from "node:test";
import type { PartnersAnswer } from "./api";
import fixtures from "./partnerFixtures.json" with { type: "json" };
import { applyAnswer, INITIAL, type PartnersSnapshot } from "./partnersSnapshot.ts";

const answer = (extra: Partial<PartnersAnswer>): PartnersAnswer => ({ source: "network", problem: null, body: null, fetchedAt: 1000, mediaBase: "flint-partner://localhost/", mediaProjects: fixtures.mediaProjects, allowLoopback: false, ...extra });

function run(steps: [PartnersAnswer, boolean][]): PartnersSnapshot[] {
  let snapshot = INITIAL;
  let raw: string | null = null;
  return steps.map(([a, final]) => {
    const result = applyAnswer(snapshot, raw, a, final);
    raw = result.raw;
    snapshot = result.next;
    return snapshot;
  });
}

test("first paint: an empty cache keeps loading until the Store answers", () => {
  const [cached, live] = run([
    [answer({ source: "none", fetchedAt: null }), false],
    [answer({ body: fixtures.sets.featured }), true],
  ]);
  assert.equal(cached.status, "loading");
  assert.equal(live.status, "ready");
  assert.equal(live.feed?.partners.length, 4);
});

test("a refresh with the same feed changes nothing, so nothing re-renders", () => {
  const [first, confirmed, again] = run([
    [answer({ source: "cache", body: fixtures.sets.featured }), false],
    [answer({ source: "confirmed", body: fixtures.sets.featured }), true],
    [answer({ source: "cache", body: structuredClone(fixtures.sets.featured) }), true],
  ]);
  assert.equal(confirmed, first, "the very same snapshot");
  assert.equal(again, first);
  const [, changed] = run([
    [answer({ body: fixtures.sets.featured }), true],
    [answer({ body: fixtures.sets.many }), true],
  ]);
  assert.equal(changed.feed?.partners.length, 13);
});

test("offline: the cached copy stays, marked stale; with nothing cached, the offline state", () => {
  const [live, offline, back] = run([
    [answer({ body: fixtures.sets.featured }), true],
    [answer({ source: "cache", problem: "offline", body: fixtures.sets.featured }), true],
    [answer({ source: "confirmed", body: fixtures.sets.featured, fetchedAt: 2000 }), true],
  ]);
  assert.equal(offline.feed, live.feed, "the same listings, not re-parsed");
  assert.deepEqual([offline.status, offline.stale], ["ready", true]);
  assert.deepEqual([back.stale, back.fetchedAt], [false, 2000]);
  assert.equal(run([[answer({ source: "none", problem: "offline" }), true]])[0].status, "offline");
  assert.equal(run([[answer({ source: "none", problem: "unavailable" }), true]])[0].status, "unavailable");
  assert.equal(run([[answer({ source: "none", problem: "rate-limited" }), true]])[0].status, "unavailable");
});

test("no partners is a ready, empty directory; a body Flint can't read is not shown", () => {
  const [empty] = run([[answer({ body: fixtures.sets.none }), true]]);
  assert.deepEqual([empty.status, empty.feed?.partners], ["ready", []]);
  const [odd] = run([[answer({ body: { schema: 2, partners: [] } }), true]]);
  assert.deepEqual([odd.status, odd.feed], ["unavailable", null]);
});
