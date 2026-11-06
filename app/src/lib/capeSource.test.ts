// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import { isPlayable, type CapeAnimation } from "./capeAnimation.ts";
import { chooseCape, createCapeSource, sameCape, settleCape, wornCape, type CapeSourceDeps, type FlintCapeState, type ShownCape } from "./capeSource.ts";
import type { StoreChanges, StoreItem, StoreView } from "./storeSync.ts";

const STEVE = "8667ba71b85a4004af54457a9734eed7";
const ALEX = "ec561538f3fd461daff5086b22154bce";
const MC_CAPE = "data:image/png;base64,bWluZWNyYWZ0";
const EMBER = "00000000-0000-4000-8000-0000000000c1";
const FROST = "00000000-0000-4000-8000-0000000000c2";
const HD = "00000000-0000-4000-8000-0000000000c3";
const MOVING = "00000000-0000-4000-8000-0000000000c4";

const animation = (scale: number, durations: number[]): CapeAnimation => {
  const height = 17 * scale;
  const per = Math.max(1, Math.floor(2048 / height));
  const strips = Array.from({ length: Math.ceil(durations.length / per) }, (_, s) => ({ frames: Math.min(per, durations.length - s * per) }));
  return { scale, width: 22 * scale, height, frameCount: durations.length, durationsMs: durations, durationMs: durations.reduce((a, b) => a + b, 0), framesPerStrip: per, strips };
};
const item = (cosmeticId: string, version = 1, anim: CapeAnimation | null = null): StoreItem => ({
  cosmeticId,
  slug: cosmeticId.slice(-2),
  kind: "cape",
  name: `TEST · ${cosmeticId.slice(-2)}`,
  test: true,
  gift: false,
  asset: { id: `a-${cosmeticId.slice(-2)}`, version, width: anim ? 64 * Math.min(16, anim.scale) : 64, height: anim ? 32 * Math.min(16, anim.scale) : 32, animation: anim },
});
const OWNED = [item(EMBER), item(FROST), item(HD), item(MOVING, 1, animation(16, [40, 80, 120, 60, 260, 50, 90, 150, 70, 110]))];

function view(uuid: string, worn: string | null, extra: Partial<StoreView> = {}, owned = OWNED): StoreView {
  return {
    uuid,
    storeUrl: "https://store.flintclient.com",
    signedIn: true,
    locked: false,
    stale: false,
    name: uuid === STEVE ? "Steve" : "Alex",
    inventory: { version: 3, name: "x", balance: 0, owned, equipped: worn ? { cape: worn } : {} },
    ...extra,
  };
}
const signedOutView = (uuid: string): StoreView => ({ uuid, storeUrl: "https://store.flintclient.com", signedIn: false, locked: false, stale: false });

/** A promise resolved by hand, to hold an answer "on its way". */
function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((a, b) => ((resolve = a), (reject = b)));
  return { promise, resolve, reject };
}
const flush = () => new Promise((r) => setTimeout(r, 0));

type Strip = { name: string; closed: boolean };

/** The source with a fake Store: answers queued per account, pictures by cosmetic id, strips decoded as plain objects. */
function rig() {
  const views = new Map<string, StoreView>();
  const posters = new Map<string, ReturnType<typeof deferred<string>>>();
  const stripCalls: string[] = [];
  const failStrips = new Set<string>();
  const calls: string[] = [];
  let changes: StoreChanges = { state: "ok", changed: false, version: 3, pollAfterSeconds: 25 };
  let now = 1_000_000;
  const timers: { at: number; cb: () => void }[] = [];
  const deps: CapeSourceDeps<Strip> = {
    inventory: async (uuid) => {
      calls.push(`inventory ${uuid}`);
      const v = views.get(uuid);
      if (!v) throw new Error("no answer");
      return v;
    },
    changes: async (uuid, since) => {
      calls.push(`changes ${uuid} ${since}`);
      return changes;
    },
    poster: (uuid, id) => {
      calls.push(`poster ${uuid} ${id}`);
      const key = `${uuid}:${id}`;
      if (!posters.has(key)) posters.set(key, deferred<string>());
      return posters.get(key)!.promise;
    },
    strips: async (uuid, id, manifest) => {
      stripCalls.push(`${uuid}:${id}`);
      if (failStrips.has(id)) throw new Error("a strip failed its checks");
      return manifest.strips.map((_, i) => ({ name: `${id}#${i}`, closed: false }));
    },
    release: (strips) => strips.forEach((s) => (s.closed = true)),
    isPlayable,
    now: () => now,
    later: (cb, ms) => {
      const t = { at: now + ms, cb };
      timers.push(t);
      return t;
    },
    cancel: (t) => {
      const i = timers.indexOf(t as (typeof timers)[number]);
      if (i >= 0) timers.splice(i, 1);
    },
  };
  const source = createCapeSource(deps);
  const art = (uuid: string, id: string) => `data:image/png;base64,${uuid.slice(0, 4)}-${id.slice(-2)}`;
  return {
    source,
    views,
    calls,
    stripCalls,
    failStrips,
    setChanges: (c: StoreChanges) => (changes = c),
    /** The picture for `id` arrives (or fails). */
    poster: (uuid: string, id: string, ok = true) => {
      const key = `${uuid}:${id}`;
      if (!posters.has(key)) posters.set(key, deferred<string>());
      const d = posters.get(key)!;
      if (ok) d.resolve(art(uuid, id));
      else d.reject(new Error("hash mismatch"));
    },
    art,
    advance: (ms: number) => {
      now += ms;
      for (const t of timers.filter((t) => t.at <= now)) {
        timers.splice(timers.indexOf(t), 1);
        t.cb();
      }
    },
    /** What a preview of `uuid` would show now, with `minecraft` as its Minecraft cape. */
    shown: (uuid: string, minecraft: string | null | undefined = MC_CAPE) => chooseCape(source.state(uuid), minecraft),
  };
}

const flint = (url: string, key: string) => ({ source: "flint", url, key });
const pick = (c: ShownCape<unknown> | null) => (c === null ? "pending" : c.source === "flint" ? flint(c.url, c.key) : c);

test("the rule: the Flint cape, else the Minecraft cape, else none; undecided while either is on its way", () => {
  const ready: FlintCapeState<never> = { state: "ready", cape: { cosmeticId: EMBER, key: "k", poster: "flint.png", animation: null } };
  const cases: [FlintCapeState<never>, string | null | undefined, unknown][] = [
    // Flint cape + Minecraft cape
    [ready, MC_CAPE, flint("flint.png", "k")],
    // Flint cape only
    [ready, null, flint("flint.png", "k")],
    // ... whatever Mojang's answer is, even before it arrives.
    [ready, undefined, flint("flint.png", "k")],
    // Minecraft cape only
    [{ state: "none" }, MC_CAPE, { source: "minecraft", url: MC_CAPE }],
    // neither
    [{ state: "none" }, null, { source: "none" }],
    // A Flint picture that failed its checks: Minecraft's.
    [{ state: "failed", key: "k" }, MC_CAPE, { source: "minecraft", url: MC_CAPE }],
    // Not decided yet: never the Minecraft cape first.
    [{ state: "unknown" }, MC_CAPE, "pending"],
    [{ state: "loading", key: "k" }, MC_CAPE, "pending"],
    [{ state: "none" }, undefined, "pending"],
  ];
  for (const [state, minecraft, expected] of cases) assert.deepEqual(pick(chooseCape(state, minecraft)), expected, JSON.stringify([state.state, minecraft]));
});

test("while undecided a preview keeps its account's last cape, and never another account's", () => {
  const steveFlint: ShownCape<never> = { source: "flint", url: "steve.png", cosmeticId: EMBER, key: "k", animation: null };
  const kept = settleCape({ uuid: STEVE, cape: steveFlint }, STEVE, null);
  assert.equal(kept.pending, true);
  assert.equal(kept.source, "flint");
  // An account switch: nothing of Steve's, not even for a frame.
  const switched = settleCape({ uuid: STEVE, cape: steveFlint }, ALEX, null);
  assert.deepEqual(switched, { source: "none", pending: true });
  assert.deepEqual(settleCape(null, ALEX, null), { source: "none", pending: true });
  assert.deepEqual(settleCape({ uuid: STEVE, cape: steveFlint }, ALEX, { source: "minecraft", url: MC_CAPE }), { source: "minecraft", url: MC_CAPE, pending: false });
});

test("the worn cape is read from the Store's answer only", () => {
  assert.equal(wornCape(view(STEVE, EMBER))?.key, `${EMBER}:a-c1:1`);
  assert.equal(wornCape(view(STEVE, null)), null);
  assert.equal(wornCape(signedOutView(STEVE)), null);
  // Worn but not (any more) owned, or without a picture: none.
  assert.equal(wornCape(view(STEVE, EMBER, {}, [item(FROST)])), null);
  assert.equal(wornCape(view(STEVE, EMBER, {}, [{ ...item(EMBER), asset: null }])), null);
  assert.equal(wornCape(view(STEVE, EMBER, {}, [{ ...item(EMBER), kind: "back" }])), null);
  assert.equal(sameCape({ source: "none" }, { source: "none" }), true);
  assert.equal(sameCape({ source: "minecraft", url: "a" }, { source: "minecraft", url: "b" }), false);
});

test("Flint cape + Minecraft cape: nothing new until the Flint picture arrives, then the Flint cape; taking it off brings Minecraft's back at once", async () => {
  const r = rig();
  r.views.set(STEVE, view(STEVE, EMBER));
  let heard = 0;
  r.source.subscribe(STEVE, () => heard++, { store: true });
  assert.equal(r.shown(STEVE), null, "the Store's answer is on its way");
  await flush();
  assert.equal(r.shown(STEVE), null, "the Flint picture is on its way: never the Minecraft cape first");
  r.poster(STEVE, EMBER);
  await flush();
  assert.deepEqual(pick(r.shown(STEVE)), flint(r.art(STEVE, EMBER), `${EMBER}:a-c1:1`));
  assert.ok(heard >= 2);

  // Taken off on the Store page: the Store's answer arrives through the feed.
  r.source.noteView(view(STEVE, null));
  assert.deepEqual(r.shown(STEVE), { source: "minecraft", url: MC_CAPE });
  // Without a Minecraft cape: none.
  assert.deepEqual(r.shown(STEVE, null), { source: "none" });

  // Worn again: its picture is fetched again only if it isn't the one already here.
  r.source.noteView(view(STEVE, FROST));
  assert.equal(r.shown(STEVE), null);
  r.poster(STEVE, FROST);
  await flush();
  assert.equal((r.shown(STEVE) as { url: string }).url, r.art(STEVE, FROST));
  const posterCalls = r.calls.filter((c) => c.startsWith("poster")).length;
  r.source.noteView(view(STEVE, FROST, { fetchedAt: 2 }));
  assert.equal(r.calls.filter((c) => c.startsWith("poster")).length, posterCalls, "the same answer again changes nothing");
  // A new approved texture (a new asset version) is fetched.
  r.source.noteView(view(STEVE, FROST, {}, [item(EMBER), item(FROST, 2)]));
  assert.equal(r.shown(STEVE), null);
});

test("Flint cape only, static and HD: shown whether or not there is a Minecraft cape", async () => {
  const r = rig();
  r.views.set(STEVE, view(STEVE, HD));
  r.source.subscribe(STEVE, () => {}, { store: true });
  await flush();
  r.poster(STEVE, HD);
  await flush();
  assert.equal(r.shown(STEVE, null)?.source, "flint");
  assert.equal(r.shown(STEVE, MC_CAPE)?.source, "flint");
  assert.equal(r.stripCalls.length, 0, "a static cape has no strips");
});

test("an animated cape shows its poster, then plays; a strip that fails keeps the poster", async () => {
  const r = rig();
  r.views.set(STEVE, view(STEVE, MOVING));
  r.source.subscribe(STEVE, () => {}, { store: true });
  await flush();
  r.poster(STEVE, MOVING);
  await flush();
  const shown = r.shown(STEVE);
  assert.equal(shown?.source, "flint");
  if (shown?.source !== "flint") return;
  assert.ok(shown.animation, "decoded strips");
  assert.equal(shown.animation.strips.length, 2);
  assert.equal(shown.animation.manifest.frameCount, 10);

  // Taken off: its frames are freed a little later (a preview may be drawing one), never while still worn.
  const strips = shown.animation.strips as Strip[];
  r.source.noteView(view(STEVE, EMBER));
  assert.equal(strips[0].closed, false);
  r.advance(10_000);
  assert.equal(strips[0].closed, true);

  const failing = rig();
  failing.failStrips.add(MOVING);
  failing.views.set(STEVE, view(STEVE, MOVING));
  failing.source.subscribe(STEVE, () => {}, { store: true });
  await flush();
  failing.poster(STEVE, MOVING);
  await flush();
  const poster = failing.shown(STEVE);
  assert.equal(poster?.source, "flint");
  assert.equal(poster?.source === "flint" && poster.animation, null, "the poster, as a static cape");
});

test("a Flint picture that can't be loaded shows the Minecraft cape", async () => {
  const r = rig();
  r.views.set(STEVE, view(STEVE, EMBER));
  r.source.subscribe(STEVE, () => {}, { store: true });
  await flush();
  r.poster(STEVE, EMBER, false);
  await flush();
  assert.deepEqual(r.shown(STEVE), { source: "minecraft", url: MC_CAPE });
});

test("account switch: one account's answer, picture or ownership never shows for another", async () => {
  const r = rig();
  r.views.set(STEVE, view(STEVE, EMBER));
  r.views.set(ALEX, view(ALEX, null));
  r.source.subscribe(STEVE, () => {}, { store: true });
  await flush();
  r.poster(STEVE, EMBER);
  await flush();
  assert.equal(r.shown(STEVE)?.source, "flint");
  // Alex, before the Store answered for Alex: undecided (a preview shows no cape: settleCape), never Steve's.
  assert.equal(r.shown(ALEX), null);
  r.source.subscribe(ALEX, () => {}, { store: true });
  await flush();
  assert.deepEqual(r.shown(ALEX), { source: "minecraft", url: MC_CAPE });
  // An answer for Steve never changes Alex.
  r.source.noteView(view(STEVE, FROST));
  assert.deepEqual(r.shown(ALEX), { source: "minecraft", url: MC_CAPE });
  // UUIDs in either spelling are one account.
  assert.equal(r.source.state("8667ba71-b85a-4004-af54-457a9734eed7").state, "loading");
  // Steve's picture asked for Steve's account only.
  assert.ok(r.calls.filter((c) => c.startsWith("poster")).every((c) => c.includes(STEVE)));

  // The test identity has no Store: never asked about, its Minecraft cape (none) at once.
  const QA = "00000000000040008000000000f11a7e";
  r.source.subscribe(QA, () => {}, { store: false });
  assert.deepEqual(r.shown(QA, null), { source: "none" });
  assert.ok(!r.calls.some((c) => c.includes(QA)));
});

test("signing out of the Store, or removing the account, forgets its cape at once; a late picture never brings it back", async () => {
  const r = rig();
  r.views.set(STEVE, view(STEVE, MOVING));
  r.source.subscribe(STEVE, () => {}, { store: true });
  await flush();
  r.poster(STEVE, MOVING);
  await flush();
  const shown = r.shown(STEVE);
  const strips = (shown?.source === "flint" ? shown.animation?.strips : []) as Strip[];
  r.source.signedOut(STEVE);
  assert.deepEqual(r.shown(STEVE), { source: "minecraft", url: MC_CAPE });
  assert.ok(strips.every((s) => s.closed), "frames freed at once");

  // Wearing something, then signed out before its picture arrived: the picture is dropped.
  r.source.noteView(view(STEVE, EMBER));
  r.source.noteView(signedOutView(STEVE));
  r.poster(STEVE, EMBER);
  await flush();
  assert.deepEqual(r.shown(STEVE), { source: "minecraft", url: MC_CAPE });

  // Removed from Flint.
  r.source.noteView(view(STEVE, FROST));
  r.poster(STEVE, FROST);
  await flush();
  assert.equal(r.shown(STEVE)?.source, "flint");
  r.source.keepOnly([ALEX]);
  assert.equal(r.source.state(STEVE).state, "unknown");
  assert.equal(r.shown(STEVE), null);
});

test("Store offline: the last copy keeps its cached picture for the same account; reconnecting asks again", async () => {
  const r = rig();
  r.views.set(STEVE, view(STEVE, EMBER, { stale: true, problem: "offline" }));
  r.source.subscribe(STEVE, () => {}, { store: true });
  await flush();
  r.poster(STEVE, EMBER);
  await flush();
  assert.equal(r.shown(STEVE)?.source, "flint", "the cached copy, display only");

  // Still offline when the window comes to the front: nothing asked again but the cheap check.
  r.setChanges({ state: "offline" });
  r.advance(6_000);
  r.source.checkShown();
  await flush();
  assert.equal(r.calls.filter((c) => c.startsWith("inventory")).length, 1);
  // Back online: the change check sees the Store answer again, and the inventory is asked for (the cape was taken off).
  r.views.set(STEVE, view(STEVE, null));
  r.setChanges({ state: "ok", changed: false, version: 3, pollAfterSeconds: 25 });
  r.advance(6_000);
  r.source.checkShown();
  await flush();
  await flush();
  assert.deepEqual(r.shown(STEVE), { source: "minecraft", url: MC_CAPE });
  // Checks on focus are spaced out.
  const before = r.calls.length;
  r.source.checkShown();
  r.source.checkShown();
  assert.equal(r.calls.length, before);

  // No answer at all (not even a cached copy): the Minecraft cape, and the next focus asks again.
  const none = rig();
  none.source.subscribe(ALEX, () => {}, { store: true });
  await flush();
  assert.deepEqual(none.shown(ALEX), { source: "minecraft", url: MC_CAPE });
  none.views.set(ALEX, view(ALEX, EMBER));
  none.advance(6_000);
  none.source.checkShown();
  await flush();
  assert.equal(none.shown(ALEX), null, "the Flint picture is on its way");
});

test("signed out of the Store: a stale copy is never shown as owned", async () => {
  const r = rig();
  r.views.set(STEVE, signedOutView(STEVE));
  r.source.subscribe(STEVE, () => {}, { store: true });
  await flush();
  assert.deepEqual(r.shown(STEVE), { source: "minecraft", url: MC_CAPE });
  assert.ok(!r.calls.some((c) => c.startsWith("poster")));
});
