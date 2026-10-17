// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import { BootTimeout, describeBootFailure, withTimeout } from "./boot.ts";

test("a request that answers in time passes its result through", async () => {
  assert.equal(await withTimeout(Promise.resolve(42), "App info", 50), 42);
});

test("a stalled request becomes a BootTimeout naming the step", async () => {
  const never = new Promise<never>(() => {});
  await assert.rejects(withTimeout(never, "Settings", 20), (error: unknown) => {
    assert.ok(error instanceof BootTimeout);
    assert.equal(error.step, "Settings");
    return true;
  });
});

test("a failing request keeps its own error rather than becoming a timeout", async () => {
  await assert.rejects(withTimeout(Promise.reject(new Error("disk full")), "Settings", 1000), /disk full/);
});

test("the timer is cleared once a request settles", async () => {
  const created: unknown[] = [];
  const cleared: unknown[] = [];
  const realSet = globalThis.setTimeout;
  const realClear = globalThis.clearTimeout;
  globalThis.setTimeout = ((fn: () => void, ms?: number) => {
    const id = realSet(fn, ms);
    created.push(id);
    return id;
  }) as typeof setTimeout;
  globalThis.clearTimeout = ((id: Parameters<typeof clearTimeout>[0]) => {
    cleared.push(id);
    realClear(id);
  }) as typeof clearTimeout;
  try {
    await withTimeout(Promise.resolve("ok"), "App info", 10_000);
    await withTimeout(Promise.reject(new Error("x")), "Settings", 10_000).catch(() => {});
  } finally {
    globalThis.setTimeout = realSet;
    globalThis.clearTimeout = realClear;
  }
  assert.equal(created.length, 2);
  assert.deepEqual(cleared, created, "every timer is cleared, on success and on failure");
});

test("failures are explained without showing technical text up front", () => {
  const timeout = describeBootFailure(new BootTimeout("Onboarding state"));
  assert.match(timeout.title, /taking too long/);
  assert.match(timeout.detail, /Onboarding state/);
  assert.doesNotMatch(timeout.body, /BootTimeout|Onboarding state/);

  const failure = describeBootFailure("Simulated start-up failure");
  assert.equal(failure.title, "Flint couldn't start");
  assert.equal(failure.detail, "Simulated start-up failure");
  assert.doesNotMatch(failure.body, /Simulated/);
});
