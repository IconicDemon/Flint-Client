// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import { decodesHalved, frameAt, framePlace, isPlayable, previewScale, textureSize, untilNextFrame, type CapeAnimation } from "./capeAnimation.ts";

/** The Store's e2e "standard" cape (game-runtime qa/tools/mock-store.mjs): 10 frames of their own durations at 16 px. */
const standard: CapeAnimation = {
  scale: 16,
  width: 352,
  height: 272,
  frameCount: 10,
  durationsMs: [40, 80, 120, 60, 260, 50, 90, 150, 70, 110],
  durationMs: 1030,
  framesPerStrip: 7,
  strips: [{ frames: 7 }, { frames: 3 }],
};
/** The "hd" one: 8 frames at 32 px per unit, 3 to a strip. */
const hd: CapeAnimation = {
  scale: 32,
  width: 704,
  height: 544,
  frameCount: 8,
  durationsMs: [60, 120, 90, 150, 45, 260, 80, 100],
  durationMs: 905,
  framesPerStrip: 3,
  strips: [{ frames: 3 }, { frames: 3 }, { frames: 2 }],
};

test("each frame shows for its own duration on the wall clock, and the loop repeats", () => {
  // Frame boundaries: 40, 120, 240, 300, 560, 610, 700, 850, 920, 1030.
  const at = (t: number) => frameAt(standard, t);
  assert.deepEqual([0, 39, 40, 119, 120, 239, 240, 299, 300, 559, 560, 1029].map(at), [0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 9]);
  assert.equal(at(1030), 0, "the loop starts again");
  assert.equal(at(1030 * 1000 + 300), 4);
  // Every client (the launcher and every game) shows the same frame at the same moment: the loop starts at now % loop.
  const now = 1_762_300_000_123;
  assert.equal(frameAt(standard, now), frameAt({ ...standard }, now));
  assert.equal(frameAt(standard, now), frameAt(standard, now % standard.durationMs));
  // Before 1970 still counts forward.
  assert.equal(at(-1), 9);
});

test("the next frame change is due when the current frame's time is up", () => {
  assert.equal(untilNextFrame(standard, 0), 40);
  assert.equal(untilNextFrame(standard, 39), 1);
  assert.equal(untilNextFrame(standard, 40), 80);
  assert.equal(untilNextFrame(standard, 1029), 1);
  assert.equal(untilNextFrame(standard, 1030 * 7 + 300), 260);
});

test("frames are found in their strips, HD three to a strip", () => {
  assert.deepEqual(framePlace(standard, 0), { strip: 0, y: 0 });
  assert.deepEqual(framePlace(standard, 6), { strip: 0, y: 6 * 272 });
  assert.deepEqual(framePlace(standard, 7), { strip: 1, y: 0 });
  assert.deepEqual(framePlace(hd, 4), { strip: 1, y: 544 });
  assert.deepEqual(framePlace(hd, 7), { strip: 2, y: 544 });
});

test("previews draw a 64x32-per-unit texture of at most 1024x512, HD frames halved", () => {
  assert.deepEqual(textureSize(standard), { width: 1024, height: 512 });
  assert.deepEqual(textureSize(hd), { width: 1024, height: 512 });
  assert.deepEqual(textureSize({ scale: 8 }), { width: 512, height: 256 });
  assert.deepEqual(textureSize({ scale: 4 }), { width: 256, height: 128 });
  assert.equal(previewScale(hd), 16);
  assert.equal(decodesHalved(hd), true);
  assert.equal(decodesHalved(standard), false);
  // The cape's front (x 1..10, y 1..16 of the 64x32 layout) lands where the UVs (normalized to 64x32) look for it at any size.
  for (const scale of [4, 8, 16, 32]) {
    const { width, height } = textureSize({ scale });
    assert.equal(width / 64, height / 32);
  }
});

test("only manifests the code can play are played", () => {
  assert.equal(isPlayable(standard), true);
  assert.equal(isPlayable(hd), true);
  assert.equal(isPlayable(null), false);
  assert.equal(isPlayable({ ...standard, scale: 12, width: 264, height: 204 }), false);
  assert.equal(isPlayable({ ...standard, durationMs: 1 }), false);
  assert.equal(isPlayable({ ...standard, frameCount: 11 }), false);
  assert.equal(isPlayable({ ...standard, strips: [{ frames: 7 }] }), false);
  assert.equal(isPlayable({ ...standard, strips: [{ frames: 7 }, { frames: 2 }] }), false);
  assert.equal(isPlayable({ ...standard, durationsMs: [0, ...standard.durationsMs.slice(1)], durationMs: 990 }), false);
});
