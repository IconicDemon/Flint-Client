// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import { createEquipQueue, type Pick } from "./equipQueue.ts";
import type { StoreSlot } from "./storeSync.ts";

const A = "00000000-0000-4000-8000-0000000000aa";
const B = "00000000-0000-4000-8000-0000000000bb";
const C = "00000000-0000-4000-8000-0000000000cc";

const flush = () => new Promise((r) => setTimeout(r, 0));

/** A Store whose answers are given by hand, in order; `worn` is what it says each slot wears. */
function rig(initial: string | null = null) {
  const sent: string[] = [];
  const replies: { resolve: (v: { cape: string | null; version: number }) => void; reject: (e: unknown) => void }[] = [];
  const answered: { cape: string | null; version: number }[] = [];
  const settled: { pick: Pick; error: unknown | null }[] = [];
  const shown: (Pick | null)[] = [];
  let worn: string | null = initial;
  let version = 1;
  const queue = createEquipQueue<{ cape: string | null; version: number }>({
    send: (slot: StoreSlot, id) => {
      sent.push(`${slot}:${id ?? "none"}`);
      return new Promise((resolve, reject) => replies.push({ resolve, reject }));
    },
    worn: () => worn,
    answered: (view) => {
      answered.push(view);
      worn = view.cape;
    },
    changed: (pending) => shown.push(pending),
    settled: (pick, error) => settled.push({ pick, error }),
  });
  return {
    queue,
    sent,
    answered,
    settled,
    shown,
    /** The Store takes the request on its way: it now wears what was asked. */
    accept: async () => {
      const id = sent[sent.length - 1].split(":")[1];
      replies.shift()!.resolve({ cape: id === "none" ? null : id, version: ++version });
      await flush();
    },
    refuse: async (why = "not_owned") => {
      replies.shift()!.reject(new Error(why));
      await flush();
    },
    waiting: () => replies.length,
  };
}

test("a pick is shown at once and sent once; the Store's answer settles it", async () => {
  const r = rig();
  r.queue.pick("cape", A);
  assert.deepEqual(r.queue.pending(), { slot: "cape", cosmeticId: A }, "shown before the Store answers");
  assert.deepEqual(r.sent, ["cape:" + A]);
  await r.accept();
  assert.equal(r.queue.pending(), null);
  assert.deepEqual(r.settled, [{ pick: { slot: "cape", cosmeticId: A }, error: null }]);
  assert.equal(r.answered.length, 1);
});

test("the latest pick wins: picks made while one is on its way replace each other, and only the last is sent", async () => {
  const r = rig();
  r.queue.pick("cape", A);
  r.queue.pick("cape", B);
  r.queue.pick("cape", C);
  assert.deepEqual(r.sent, ["cape:" + A], "one request per slot at a time");
  assert.deepEqual(r.queue.pending(), { slot: "cape", cosmeticId: C }, "the last pick is what shows");
  await r.accept();
  assert.deepEqual(r.sent, ["cape:" + A, "cape:" + C], "B was overtaken: never sent");
  assert.deepEqual(r.queue.pending(), { slot: "cape", cosmeticId: C }, "A's answer doesn't take C off the screen");
  assert.equal(r.settled.length, 0, "nothing settles until the last pick is answered");
  await r.accept();
  assert.equal(r.queue.pending(), null);
  assert.deepEqual(r.settled, [{ pick: { slot: "cape", cosmeticId: C }, error: null }]);
  assert.equal(r.waiting(), 0);
});

test("A → B → A while A is on its way: A's answer is already the last pick, so nothing more is sent", async () => {
  const r = rig();
  r.queue.pick("cape", A);
  r.queue.pick("cape", B);
  r.queue.pick("cape", A);
  await r.accept();
  assert.deepEqual(r.sent, ["cape:" + A]);
  assert.deepEqual(r.settled, [{ pick: { slot: "cape", cosmeticId: A }, error: null }]);
});

test("back to what the Store already wears: nothing is sent", async () => {
  const r = rig(A);
  r.queue.pick("cape", B);
  r.queue.pick("cape", A);
  await r.accept();
  // B was worn by the Store's answer, so A (the last pick) is sent after it.
  assert.deepEqual(r.sent, ["cape:" + B, "cape:" + A]);
  await r.accept();
  const s = rig(A);
  s.queue.pick("cape", A);
  assert.deepEqual(s.sent, [], "the pick is what the Store says is worn");
  assert.deepEqual(s.settled, [{ pick: { slot: "cape", cosmeticId: A }, error: null }]);
});

test("a refusal of the last pick settles with its reason; a refusal overtaken by a later pick is never shown", async () => {
  const r = rig(A);
  r.queue.pick("cape", B);
  await r.refuse();
  assert.equal(r.queue.pending(), null, "the previous state comes back");
  assert.equal(r.settled.length, 1);
  assert.equal((r.settled[0].error as Error).message, "not_owned");
  assert.deepEqual(r.settled[0].pick, { slot: "cape", cosmeticId: B });

  const s = rig(A);
  s.queue.pick("cape", B);
  s.queue.pick("cape", C);
  await s.refuse();
  assert.deepEqual(s.sent, ["cape:" + B, "cape:" + C], "the later pick goes anyway");
  assert.equal(s.settled.length, 0);
  await s.accept();
  assert.deepEqual(s.settled, [{ pick: { slot: "cape", cosmeticId: C }, error: null }]);
});

test("slots are independent: taking a cape off while a head item is on its way", async () => {
  const r = rig(A);
  r.queue.pick("head", B);
  r.queue.pick("cape", null);
  assert.deepEqual(r.sent, ["head:" + B, "cape:none"]);
  assert.deepEqual(r.queue.pending(), { slot: "cape", cosmeticId: null }, "the most recent pick shows");
  assert.ok(r.queue.sending("head") && r.queue.sending("cape"));
});
