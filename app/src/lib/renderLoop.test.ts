// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import { approach, createRenderLoop, DEEP_IDLE_MS, MAX_DT, motionWanted, pacedDelay, smoothstep, type FrameClock, type Next } from "./renderLoop.ts";

/** A hand-driven clock: frames and timers only fire when the test says so. */
function fakeClock() {
  let id = 0;
  let t = 0;
  const frames = new Map<number, (now: number) => void>();
  const timers = new Map<number, { cb: () => void; at: number }>();
  const clock: FrameClock = {
    now: () => t,
    frame: (cb) => (frames.set(++id, cb), id),
    cancelFrame: (i) => void frames.delete(i),
    later: (cb, ms) => (timers.set(++id, { cb, at: t + ms }), id),
    cancelLater: (i) => void timers.delete(i),
  };
  return {
    clock,
    frames,
    timers,
    /** Advances time and runs every frame that was pending. */
    tick(ms = 16) {
      t += ms;
      for (const [i, timer] of [...timers]) if (timer.at <= t) (timers.delete(i), timer.cb());
      const due = [...frames];
      frames.clear();
      for (const [, cb] of due) cb(t);
    },
  };
}

test("renders nothing until asked, then once per request", () => {
  const f = fakeClock();
  let renders = 0;
  const loop = createRenderLoop(() => (renders++, null), f.clock);
  f.tick();
  assert.equal(renders, 0);
  assert.equal(loop.running, false);
  loop.request();
  loop.request(); // coalesced into the same frame
  assert.equal(loop.running, true);
  f.tick();
  assert.equal(renders, 1);
  assert.equal(loop.running, false);
  f.tick();
  assert.equal(renders, 1);
});

test("keeps running while the step asks for display frames, and stops when it doesn't", () => {
  const f = fakeClock();
  let left = 3;
  let renders = 0;
  createRenderLoop(() => (renders++, --left > 0 ? 0 : null), f.clock).request();
  for (let i = 0; i < 10; i++) f.tick();
  assert.equal(renders, 3);
  assert.equal(f.frames.size + f.timers.size, 0);
});

test("a paced step sleeps on a timer between frames, and a request cuts the sleep short", () => {
  const f = fakeClock();
  let renders = 0;
  const loop = createRenderLoop(() => (renders++, 30), f.clock);
  loop.request();
  f.tick();
  assert.equal(renders, 1);
  assert.equal(f.frames.size, 0);
  assert.equal(f.timers.size, 1);
  f.tick(10);
  assert.equal(renders, 1);
  loop.request();
  assert.equal(f.timers.size, 0);
  f.tick(1);
  assert.equal(renders, 2);
  f.tick(31); // timer fires and queues a frame, which runs in the same tick
  assert.equal(renders, 3);
});

test("dt is measured within a run, clamped, and starts fresh after a stop", () => {
  const f = fakeClock();
  const dts: number[] = [];
  let keepGoing = true;
  const loop = createRenderLoop((_, dt) => (dts.push(dt), keepGoing ? 0 : null), f.clock);
  loop.request();
  f.tick(16);
  f.tick(20);
  f.tick(500);
  keepGoing = false;
  f.tick(16);
  f.tick(5000);
  loop.request();
  f.tick(16);
  assert.equal(dts[0], 1 / 60);
  assert.ok(Math.abs(dts[1] - 0.02) < 1e-9);
  assert.equal(dts[2], MAX_DT);
  assert.equal(dts[4], 1 / 60, "a restart doesn't count the time spent stopped");
});

test("a frame requested from inside the step isn't scheduled twice", () => {
  const f = fakeClock();
  let renders = 0;
  let loopRef: ReturnType<typeof createRenderLoop> | null = null;
  const step = (): Next => {
    renders++;
    if (renders === 1) loopRef!.request();
    return 0;
  };
  loopRef = createRenderLoop(step, f.clock);
  loopRef.request();
  f.tick();
  assert.equal(f.frames.size, 1);
});

test("halt and dispose cancel pending work; a disposed loop ignores requests", () => {
  const f = fakeClock();
  let renders = 0;
  const loop = createRenderLoop(() => (renders++, 40), f.clock);
  loop.request();
  f.tick();
  loop.halt();
  assert.equal(loop.running, false);
  f.tick(100);
  assert.equal(renders, 1);
  loop.request();
  loop.dispose();
  f.tick();
  loop.request();
  f.tick();
  assert.equal(renders, 1);
  assert.equal(loop.running, false);
});

test("approach moves linearly and lands exactly on the target", () => {
  assert.equal(approach(0, 1, 0.25, 1), 0.25);
  assert.equal(approach(0.9, 1, 0.25, 1), 1);
  assert.equal(approach(1, 0, 0.5, 0.5), 0);
  assert.equal(approach(0.3, 0.3, 0.1, 1), 0.3);
  assert.equal(approach(0.4, 0, 0.1, 0), 0);
});

test("smoothstep is clamped and eased", () => {
  assert.equal(smoothstep(-1), 0);
  assert.equal(smoothstep(0), 0);
  assert.equal(smoothstep(0.5), 0.5);
  assert.equal(smoothstep(1), 1);
  assert.equal(smoothstep(2), 1);
  assert.ok(smoothstep(0.1) < 0.1);
});

test("pacedDelay waits for the remainder of the frame interval", () => {
  assert.equal(pacedDelay(1000, 1000, 25, 0), 40);
  assert.equal(pacedDelay(1000, 1010, 25, 4), 26);
  assert.equal(pacedDelay(1000, 1100, 25), 0);
});

test("ambient motion needs a visible, focused window and recent input", () => {
  const base = { enabled: true, visible: true, focused: true, now: 100_000, inputAt: 100_000 - 1000 };
  assert.equal(motionWanted(base), true);
  assert.equal(motionWanted({ ...base, enabled: false }), false);
  assert.equal(motionWanted({ ...base, visible: false }), false);
  assert.equal(motionWanted({ ...base, focused: false }), false);
  assert.equal(motionWanted({ ...base, inputAt: base.now - DEEP_IDLE_MS }), false);
  assert.equal(motionWanted({ ...base, inputAt: base.now - DEEP_IDLE_MS + 1 }), true);
});
