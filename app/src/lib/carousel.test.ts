// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  computeLayout,
  dotWindow,
  DOTS_MAX,
  DOTS_WINDOW,
  EDGE_GUTTER,
  HoverIntent,
  KEY_REPEAT_MS,
  MEDIUM_MIN,
  neighbourAfterRemoval,
  RENDER_RADIUS,
  RepeatThrottle,
  sidesForWidth,
  swipeSteps,
  WHEEL_GESTURE_GAP_MS,
  WHEEL_RESTEP_MS,
  WheelStepper,
  WIDE_MIN,
  type Timers,
} from "./carousel.ts";

const WIDTHS = [320, 480, MEDIUM_MIN - 1, MEDIUM_MIN, 980, WIDE_MIN - 1, WIDE_MIN, 1600, 2560];
const COUNTS = [1, 2, 3, 5, 12];
const BASE = 300;

test("breakpoints: centre only when narrow, one neighbour per side at medium, two when wide", () => {
  assert.equal(sidesForWidth(MEDIUM_MIN - 1), 0);
  assert.equal(sidesForWidth(MEDIUM_MIN), 1);
  assert.equal(sidesForWidth(WIDE_MIN - 1), 1);
  assert.equal(sidesForWidth(WIDE_MIN), 2);
  assert.equal(computeLayout(12, 6, 1600, BASE).sides, 2);
  assert.equal(computeLayout(12, 6, 1000, BASE).sides, 1);
  assert.equal(computeLayout(12, 6, 500, BASE).sides, 0);
});

test("no card, shown or hidden, extends past the carousel's edges", () => {
  for (const width of WIDTHS) {
    for (const count of COUNTS) {
      for (let focus = 0; focus < count; focus++) {
        const layout = computeLayout(count, focus, width, BASE);
        assert.ok(layout.cardWidth > 0, `width ${width}: cards have a size`);
        for (const slot of layout.slots) {
          const edge = Math.abs(slot.x) + (layout.cardWidth * slot.scale) / 2;
          assert.ok(edge <= width / 2 - EDGE_GUTTER + 0.5, `width ${width}, ${count} profiles, focus ${focus}, index ${slot.index}: edge ${edge}`);
        }
      }
    }
  }
});

test("the layout is symmetric around the focused card", () => {
  for (const width of [900, 1600]) {
    const { slots } = computeLayout(12, 6, width, BASE);
    for (const slot of slots) {
      const mirror = slots.find((s) => s.offset === -slot.offset)!;
      assert.equal(mirror.x + slot.x, 0);
      assert.equal(mirror.scale, slot.scale);
      assert.equal(mirror.visible, slot.visible);
      assert.equal(mirror.z, slot.z);
    }
  }
});

test("the focused card is centred, largest and on top; neighbours shrink and sit behind", () => {
  const { slots, focus } = computeLayout(12, 5, 1600, BASE);
  const centre = slots.find((s) => s.index === focus)!;
  assert.equal(centre.x, 0);
  assert.equal(centre.scale, 1);
  assert.equal(centre.dim, 0);
  const right = slots.filter((s) => s.offset > 0 && s.visible).sort((a, b) => a.offset - b.offset);
  let previous = centre;
  for (const slot of right) {
    assert.ok(slot.x > previous.x, "farther cards sit farther out");
    assert.ok(slot.scale < previous.scale, "farther cards are smaller");
    assert.ok(slot.z < previous.z, "farther cards sit behind");
    assert.ok(slot.dim > previous.dim, "farther cards are darker");
    previous = slot;
  }
});

test("one profile is a single centred card", () => {
  const layout = computeLayout(1, 0, 1600, BASE);
  assert.equal(layout.sides, 0);
  assert.equal(layout.slots.length, 1);
  assert.deepEqual([layout.slots[0].x, layout.slots[0].visible], [0, true]);
});

test("short lists never wrap around or invent filler cards", () => {
  for (const count of [2, 3]) {
    for (let focus = 0; focus < count; focus++) {
      const { slots } = computeLayout(count, focus, 1600, BASE);
      assert.equal(slots.length, count);
      assert.deepEqual(
        slots.map((s) => s.index),
        Array.from({ length: count }, (_, i) => i),
      );
      for (const slot of slots) assert.equal(Math.sign(slot.x), Math.sign(slot.index - focus));
    }
  }
});

test("only cards near the focus are rendered, and the ones beyond the shown slots wait transparent", () => {
  const { slots } = computeLayout(40, 20, 1600, BASE);
  assert.equal(slots.length, RENDER_RADIUS * 2 + 1);
  assert.ok(slots.every((s) => Math.abs(s.offset) <= RENDER_RADIUS));
  assert.ok(slots.filter((s) => !s.visible).every((s) => s.opacity === 0));
});

test("focus is clamped into the list", () => {
  assert.equal(computeLayout(5, 99, 1600, BASE).focus, 4);
  assert.equal(computeLayout(5, -3, 1600, BASE).focus, 0);
  assert.deepEqual(computeLayout(0, 0, 1600, BASE).slots, []);
  assert.equal(computeLayout(5, 2, 0, BASE).cardWidth, 0);
});

test("a deleted profile hands focus to its next neighbour, or the previous at the end", () => {
  const ids = ["alpha-test", "bravo-test", "charlie-test"];
  assert.equal(neighbourAfterRemoval(ids, "bravo-test"), "charlie-test");
  assert.equal(neighbourAfterRemoval(ids, "charlie-test"), "bravo-test");
  assert.equal(neighbourAfterRemoval(["solo-test"], "solo-test"), null);
  assert.equal(neighbourAfterRemoval(ids, "missing-test"), null);
});

test("short drags do nothing; longer ones step towards the drag", () => {
  assert.equal(swipeSteps(10, 300), 0);
  assert.equal(swipeSteps(-200, 300), 1);
  assert.equal(swipeSteps(200, 300), -1);
  assert.equal(swipeSteps(-5000, 300), 3);
});

/** Manual clock: timers run only when `advance` passes their due time. */
function fakeTimers() {
  let now = 0;
  let nextId = 1;
  const queue = new Map<number, { at: number; fn: () => void }>();
  const timers: Timers = {
    set: (fn, ms) => {
      const id = nextId++;
      queue.set(id, { at: now + ms, fn });
      return id;
    },
    clear: (handle) => void queue.delete(handle as number),
  };
  const advance = (ms: number) => {
    now += ms;
    for (const [id, t] of [...queue].sort((a, b) => a[1].at - b[1].at)) {
      if (t.at <= now && queue.delete(id)) t.fn();
    }
  };
  return { timers, advance, now: () => now, pending: () => queue.size };
}

function intent() {
  const clock = fakeTimers();
  const fired: string[] = [];
  const hover = new HoverIntent<string>((t) => fired.push(t), { delay: 200, slop: 6, timers: clock.timers, now: clock.now });
  return { hover, fired, ...clock };
}

test("hover intent fires once the pointer has rested for the delay", () => {
  const { hover, fired, advance } = intent();
  hover.move("card-b", 100, 100);
  advance(199);
  assert.deepEqual(fired, []);
  advance(1);
  assert.deepEqual(fired, ["card-b"]);
  assert.equal(hover.pending, null);
});

test("leaving before the delay cancels", () => {
  const { hover, fired, advance, pending } = intent();
  hover.move("card-b", 100, 100);
  advance(120);
  hover.leave("card-b");
  advance(500);
  assert.deepEqual(fired, []);
  assert.equal(pending(), 0);
});

test("leaving a card that isn't pending keeps the pending one", () => {
  const { hover, fired, advance } = intent();
  hover.move("card-c", 100, 100);
  hover.leave("card-b");
  advance(200);
  assert.deepEqual(fired, ["card-c"]);
});

test("rapid movement supersedes: only the latest target can fire, nothing queues", () => {
  const { hover, fired, advance, pending } = intent();
  hover.move("card-a", 10, 100);
  advance(80);
  hover.move("card-b", 60, 100);
  advance(80);
  hover.move("card-c", 120, 100);
  assert.equal(pending(), 1);
  advance(199);
  assert.deepEqual(fired, []);
  advance(1);
  assert.deepEqual(fired, ["card-c"]);
  advance(1000);
  assert.deepEqual(fired, ["card-c"]);
});

test("moving within the pending card neither restarts nor double-fires", () => {
  const { hover, fired, advance, pending } = intent();
  hover.move("card-b", 100, 100);
  advance(100);
  hover.move("card-b", 104, 102);
  hover.move("card-b", 110, 104);
  assert.equal(pending(), 1);
  advance(100);
  assert.deepEqual(fired, ["card-b"]);
  advance(1000);
  assert.deepEqual(fired, ["card-b"]);
});

test("a stale timer that runs after cancel does nothing", () => {
  const callbacks: (() => void)[] = [];
  const fired: string[] = [];
  const hover = new HoverIntent<string>((t) => fired.push(t), {
    timers: { set: (fn) => callbacks.push(fn), clear: () => {} },
  });
  hover.move("card-b", 0, 0);
  hover.cancel();
  callbacks[0]();
  assert.deepEqual(fired, []);
});

test("after focus moves, a resting pointer can't walk focus along the row", () => {
  const { hover, fired, advance } = intent();
  hover.move("card-b", 300, 100);
  advance(200);
  assert.deepEqual(fired, ["card-b"]);
  // The cards slid; the pointer, unmoved, is now over the next one.
  hover.move("card-c", 300, 100);
  hover.move("card-c", 303, 101);
  advance(1000);
  assert.deepEqual(fired, ["card-b"]);
  // A real movement re-arms hover.
  hover.move("card-c", 330, 100);
  advance(200);
  assert.deepEqual(fired, ["card-b", "card-c"]);
});

test("settle after a click or key press also waits for real movement", () => {
  const { hover, fired, advance } = intent();
  hover.move("card-a", 50, 50);
  hover.settle();
  advance(500);
  assert.deepEqual(fired, []);
  hover.move("card-a", 51, 50);
  advance(500);
  assert.deepEqual(fired, []);
  hover.move("card-a", 80, 50);
  advance(200);
  assert.deepEqual(fired, ["card-a"]);
});

test("a drag's release point anchors hover, so the card left under the pointer can't pull focus back", () => {
  const { hover, fired, advance } = intent();
  // Hover last saw the pointer on the centre card's neighbour, before the drag began.
  hover.move("card-b", 400, 100);
  hover.cancel();
  // Released 250px to the left; then the focus change settles again with no position.
  hover.settle(150, 100);
  hover.settle();
  hover.move("card-a", 151, 100);
  hover.move("card-a", 154, 102);
  advance(1000);
  assert.deepEqual(fired, []);
  hover.move("card-a", 180, 100);
  advance(200);
  assert.deepEqual(fired, ["card-a"]);
});

test("positions recorded without hovering a card become the settle anchor", () => {
  const { hover, fired, advance } = intent();
  hover.move("card-b", 400, 100);
  // The pointer then crossed the focused card, which reports no hover, and a key press moved focus.
  hover.point(250, 100);
  hover.settle();
  hover.move("card-c", 252, 101);
  advance(1000);
  assert.deepEqual(fired, []);
  hover.move("card-c", 290, 100);
  advance(200);
  assert.deepEqual(fired, ["card-c"]);
});

test("horizontal wheel gestures step once; vertical scrolling is ignored", () => {
  const wheel = new WheelStepper();
  let t = 0;
  const steps: number[] = [];
  // One swipe right with momentum: many events, one step.
  for (let i = 0; i < 30; i++) steps.push(wheel.push(12, 1, (t += 16)));
  assert.deepEqual(steps.filter(Boolean), [1]);
  // A pause ends the gesture; the next swipe left steps again.
  t += WHEEL_GESTURE_GAP_MS + 1;
  const back: number[] = [];
  for (let i = 0; i < 10; i++) back.push(wheel.push(-15, 0, (t += 16)));
  assert.deepEqual(back.filter(Boolean), [-1]);
  // Mostly vertical scrolling never steps.
  t += WHEEL_GESTURE_GAP_MS + 1;
  for (let i = 0; i < 20; i++) assert.equal(wheel.push(10, 40, (t += 16)), 0);
});

test("hover never selects: it only reports the card to focus, and clicks soon after are aimed at that card", () => {
  const { hover, fired, advance } = intent();
  assert.equal(hover.recentFire(580), null);
  hover.move("card-b", 400, 100);
  advance(200);
  assert.deepEqual(fired, ["card-b"]);
  // The cards are sliding and the pointer hasn't moved: a click now was meant for card-b.
  assert.equal(hover.resting, true);
  advance(300);
  assert.equal(hover.recentFire(580), "card-b");
  // Once the slide is over, a click goes to whatever is under the pointer.
  advance(300);
  assert.equal(hover.recentFire(580), null);
});

test("moving the pointer, or a drag, ends the click redirect", () => {
  const moved = intent();
  moved.hover.move("card-b", 400, 100);
  moved.advance(200);
  moved.hover.move("card-c", 403, 100);
  assert.equal(moved.hover.recentFire(580), "card-b", "jitter within the slop keeps it");
  moved.hover.move("card-c", 440, 100);
  assert.equal(moved.hover.resting, false);
  assert.equal(moved.hover.recentFire(580), null);

  const dragged = intent();
  dragged.hover.move("card-b", 400, 100);
  dragged.advance(200);
  dragged.hover.settle(150, 100);
  assert.equal(dragged.hover.recentFire(580), null);
});

/** Feeds `deltas` as one stream of horizontal wheel events 16ms apart and returns the steps. */
function wheelSteps(wheel: WheelStepper, deltas: number[], start = 0) {
  let t = start;
  const steps = deltas.map((d) => wheel.push(d, 0, (t += 16))).filter(Boolean);
  return { steps, t };
}

test("a trackpad swipe steps once, however long its momentum runs", () => {
  const wheel = new WheelStepper();
  const swipe = [2, 5, 10, 20, 30, 25, 18, 12, 9, 10, 7, 8, 6, 5, 4, 3, 3, 2, 2, 1, 2, 1, 1, 1];
  assert.deepEqual(wheelSteps(wheel, swipe).steps, [1]);
});

test("a second swipe during the first one's momentum steps again; the newest swipe wins", () => {
  const wheel = new WheelStepper();
  const first = [2, 5, 10, 20, 30, 25, 18, 12, 8, 5, 3, 2, 1];
  const second = [3, 7, 15, 25, 30, 20, 12, 6, 3, 1];
  assert.deepEqual(wheelSteps(wheel, [...first, ...second]).steps, [1, 1]);
});

test("swiping back the other way steps back at once, without waiting for momentum to end", () => {
  const wheel = new WheelStepper();
  const { steps, t } = wheelSteps(wheel, [4, 10, 20, 30, 28, 24]);
  assert.deepEqual(steps, [1]);
  // Momentum from the first swipe and a stray opposite flicker never add up to a step.
  assert.deepEqual(wheelSteps(wheel, [-1, 20, 16, 14], t).steps, []);
  assert.deepEqual(wheelSteps(wheel, [-6, -14, -22, -12, -8], t + 200).steps, [-1]);
});

test("wheel steps are at least the minimum interval apart", () => {
  const wheel = new WheelStepper();
  assert.equal(wheel.push(40, 0, 0), 1);
  assert.equal(wheel.push(-40, 0, WHEEL_RESTEP_MS - 1), 0);
  assert.equal(wheel.push(-1, 0, WHEEL_RESTEP_MS), -1);
});

test("held arrow keys are paced; separate presses always step", () => {
  const keys = new RepeatThrottle();
  assert.equal(keys.allow(false, 0), true);
  assert.equal(keys.allow(false, 10), true);
  // Auto-repeat at 30 events a second moves focus about every KEY_REPEAT_MS.
  let steps = 0;
  for (let t = 40; t <= 1040; t += 33) if (keys.allow(true, t)) steps++;
  assert.ok(steps >= Math.floor(1000 / (KEY_REPEAT_MS + 33)) && steps <= Math.ceil(1000 / KEY_REPEAT_MS), `steps ${steps}`);
  assert.equal(keys.allow(false, 1041), true);
});

test("dots: one per profile up to the limit, then a window that follows the focus", () => {
  assert.deepEqual(dotWindow(5, 2), { start: 0, end: 5, compact: false });
  assert.deepEqual(dotWindow(DOTS_MAX, DOTS_MAX - 1), { start: 0, end: DOTS_MAX, compact: false });
  const count = 40;
  for (let focus = 0; focus < count; focus++) {
    const w = dotWindow(count, focus);
    assert.equal(w.compact, true);
    assert.equal(w.end - w.start, DOTS_WINDOW);
    assert.ok(w.start >= 0 && w.end <= count);
    assert.ok(focus >= w.start && focus < w.end, `focus ${focus} is in its window`);
  }
  assert.equal(dotWindow(count, 20).start, 20 - Math.floor(DOTS_WINDOW / 2), "centred away from the ends");
  assert.deepEqual(dotWindow(0, 0), { start: 0, end: 0, compact: false });
});
