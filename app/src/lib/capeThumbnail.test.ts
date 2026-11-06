// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import { CAPE_FRONT, thumbnailScale } from "./capeThumbnail.ts";

const MB = 1024 * 1024;

test("a thumbnail keeps the cape's front, as large as the memory budget allows and never past the cap", () => {
  assert.deepEqual(CAPE_FRONT, { x: 1, y: 1, width: 10, height: 16 }, "the front of the 64 × 32 cape texture");
  assert.equal(thumbnailScale({ scale: 16, frameCount: 45 }, 8, 6 * MB), 8, "a standard cape at the cap (1.8 MB)");
  assert.equal(thumbnailScale({ scale: 16, frameCount: 120 }, 8, 6 * MB), 8, "120 frames still fit (4.9 MB)");
  assert.equal(thumbnailScale({ scale: 32, frameCount: 120 }, 8, 2 * MB), 4, "halved while over the budget");
  assert.equal(thumbnailScale({ scale: 4, frameCount: 12 }, 8, 6 * MB), 4, "never larger than the manifest's own");
  assert.equal(thumbnailScale({ scale: 32, frameCount: 120 }, 8, 1), 1, "at least 1 px per unit");
});
