// Run with `npm test`.
import assert from "node:assert/strict";
import { test } from "node:test";
import { formatDuration, quotaFromError, quotaLabel } from "./aiQuota.ts";

test("the quota pill says what the server counted", () => {
  assert.equal(quotaLabel({ limit: 3, remaining: 3, resetInMs: null }), "3 searches remaining");
  assert.equal(quotaLabel({ limit: 3, remaining: 1, resetInMs: null }), "1 search left");
  assert.equal(quotaLabel({ limit: 3, remaining: 0, resetInMs: (11 * 60 + 24) * 60_000 }), "Your searches reset in 11h 24m");
  assert.equal(quotaLabel({ limit: 3, remaining: 0, resetInMs: null }), "Your searches reset soon");
});

test("durations round up to the minute", () => {
  assert.equal(formatDuration(12 * 3600_000), "12h");
  assert.equal(formatDuration(59 * 60_000 + 1), "1h");
  assert.equal(formatDuration(5 * 60_000), "5m");
  assert.equal(formatDuration(30_000), "under a minute");
});

test("quota errors carry the server's countdown", () => {
  assert.deepEqual(quotaFromError('AI_QUOTA:{"limit":3,"remaining":0,"resetInMs":43200000}'), { limit: 3, remaining: 0, resetInMs: 43_200_000 });
  assert.deepEqual(quotaFromError("AI_QUOTA:not json"), {
    limit: 3,
    remaining: 0,
    resetInMs: null,
  });
  assert.equal(quotaFromError("AI_RATE_LIMIT"), null);
});
