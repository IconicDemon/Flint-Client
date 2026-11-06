// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import type { ContentUpdate, InstalledItem, UpdateCheck, UpdateResult, UpdateVersion } from "./api.ts";
import {
  addedDependencies,
  addsLabel,
  cachedCheck,
  CHECK_TTL_MS,
  choose,
  chosen,
  currentUpdates,
  eligible,
  forgetCheck,
  headerState,
  initialSelection,
  requests,
  resultSummary,
  runCheck,
  toggle,
  toggleAll,
  updateHint,
  updateKey,
  updateLabel,
  withoutApplied,
} from "./contentUpdates.ts";

const fabricApi = { projectId: "FABRICAP", title: "Fabric API", kind: "mod" as const, versionId: "F1" };
const yacl = { projectId: "YACL0001", title: "YACL", kind: "mod" as const, versionId: "Y1" };

function version(id: string, number: string, extra: Partial<UpdateVersion> = {}): UpdateVersion {
  return { versionId: id, versionNumber: number, versionType: "release", datePublished: "2026-10-01T00:00:00Z", changelog: "", adds: [], blocked: null, ...extra };
}

function update(title: string, extra: Partial<ContentUpdate> = {}): ContentUpdate {
  return { ...version(`${title}-new`, "2.0"), kind: "mod", fileName: `${title}.jar`, title, projectId: title.toUpperCase(), tracked: true, pinned: false, currentVersion: "1.0", alternatives: [], ...extra };
}

const sodium = update("sodium", { alternatives: [version("sodium-beta", "2.1-beta", { versionType: "beta" }), version("sodium-old", "1.5", { blocked: "Needs Old Library: Not available for Minecraft 26.2" })] });
const lithium = update("lithium", { pinned: true });
const zoomify = update("zoomify", { adds: [fabricApi, yacl] });
const cloth = update("cloth", { blocked: "Needs Old Library: Not available for Minecraft 26.2" });
const iris = update("iris", { adds: [fabricApi] });
const all = [sodium, lithium, zoomify, cloth, iris];
const keys = (set: Set<string>) => [...set].sort();
const k = updateKey;

test("the dialog opens with every update that can go in ticked, except pinned ones", () => {
  assert.deepEqual(keys(initialSelection(all)), [k(iris), k(sodium), k(zoomify)].sort());
  // Opened for chosen rows (the picked items, or one row's menu): only those, pinned or not, never a blocked one.
  assert.deepEqual(keys(initialSelection(all, [k(lithium), k(cloth)])), [k(lithium)]);
  assert.deepEqual(eligible(all).map((u) => u.title), ["sodium", "zoomify", "iris"]);
});

test("the header checkbox ticks everything but pinned and blocked items, and clears everything", () => {
  const none = new Set<string>();
  assert.equal(headerState(all, none, {}), "none");
  const on = toggleAll(all, none, {}, true);
  assert.deepEqual(keys(on), [k(iris), k(sodium), k(zoomify)].sort());
  assert.equal(headerState(all, on, {}), "all");
  // A pinned item ticked by hand stays ticked when the header ticks the rest.
  const withPinned = toggleAll(all, toggle(lithium, true, none, {}), {}, true);
  assert.ok(withPinned.has(k(lithium)) && withPinned.has(k(sodium)));
  assert.equal(headerState(all, toggle(sodium, false, on, {}), {}), "some");
  assert.equal(toggleAll(all, withPinned, {}, false).size, 0);
  // Only pinned items left: the header ticks them.
  assert.deepEqual(keys(toggleAll([lithium, cloth], none, {}, true)), [k(lithium)]);
});

test("a blocked update can't be ticked, by its row or by picking a blocked version", () => {
  assert.equal(toggle(cloth, true, new Set(), {}).size, 0);
  const start = initialSelection(all);
  const blocked = choose(sodium, "sodium-old", start, {});
  assert.equal(blocked.selected.has(k(sodium)), false, "picking a version that can't go in unticks the row");
  assert.equal(chosen(sodium, blocked.choices).versionNumber, "1.5");
  const beta = choose(sodium, "sodium-beta", blocked.selected, blocked.choices);
  assert.equal(beta.selected.has(k(sodium)), true, "a version that can go in ticks it again");
  const back = choose(sodium, sodium.versionId, beta.selected, beta.choices);
  assert.deepEqual(back.choices, {}, "the proposed version needs no choice");
});

test("the request carries each row's chosen version", () => {
  const { selected, choices } = choose(sodium, "sodium-beta", initialSelection(all), {});
  assert.deepEqual(requests(all, selected, choices), [
    { kind: "mod", fileName: "sodium.jar", versionId: "sodium-beta", world: null },
    { kind: "mod", fileName: "zoomify.jar", versionId: "zoomify-new", world: null },
    { kind: "mod", fileName: "iris.jar", versionId: "iris-new", world: null },
  ]);
  // A row ticked earlier whose version later turned out blocked isn't sent.
  assert.deepEqual(requests([cloth], new Set([k(cloth)]), {}), []);
});

test("dependencies the batch adds are counted once, and not when the batch updates them anyway", () => {
  const picked = initialSelection(all);
  assert.deepEqual(addedDependencies(all, picked, {}).map((d) => d.title), ["Fabric API", "YACL"]);
  const fapi = update("fabricap", { projectId: "FABRICAP" });
  assert.deepEqual(addedDependencies([...all, fapi], new Set([...picked, k(fapi)]), {}).map((d) => d.title), ["YACL"]);
  assert.deepEqual(addedDependencies(all, new Set([k(sodium)]), {}), []);
});

test("labels read naturally", () => {
  assert.equal(updateLabel(1), "Update 1 project");
  assert.equal(updateLabel(4), "Update 4 projects");
  assert.equal(addsLabel([fabricApi]), "Adds Fabric API");
  assert.equal(addsLabel([fabricApi, yacl]), "Adds Fabric API and YACL");
  assert.equal(addsLabel([fabricApi, yacl, fabricApi]), "Adds 3 dependencies");
  assert.equal(updateHint(zoomify, "26.2", "fabric"), "Update to 2.0 · Made for Minecraft 26.2 with Fabric · adds Fabric API and YACL");
  assert.equal(updateHint(update("pack", { kind: "resourcepack", versionType: "beta" }), "26.2", "vanilla"), "Update to 2.0 · Beta build for Minecraft 26.2");
});

test("updates follow the content list: removed files drop out and pins come from the list", () => {
  const row = (fileName: string, pinned = false) => ({ kind: "mod", fileName, pinned }) as InstalledItem;
  const current = currentUpdates(all, [row("sodium.jar", true), row("lithium.jar"), row("zoomify.jar")]);
  assert.deepEqual(current.map((u) => [u.title, u.pinned]), [["sodium", true], ["lithium", false], ["zoomify", false]]);
  assert.deepEqual(withoutApplied(all, { updated: [{ kind: "mod", fileName: "sodium.jar", title: "Sodium" }] }).map((u) => u.title), ["lithium", "zoomify", "cloth", "iris"]);
});

test("a finished job is summed up in one line", () => {
  const outcome = (title: string, reason?: string) => ({ kind: "mod" as const, fileName: `${title}.jar`, title, reason });
  const result = (updated: string[], failed: [string, string][], added: string[] = []): UpdateResult => ({
    updated: updated.map((t) => outcome(t)),
    failed: failed.map(([t, r]) => outcome(t, r)),
    skipped: [],
    added: added.map((title) => ({ title, versionNumber: "1", projectId: title, kind: "mod" })),
    installed: [],
  });
  assert.deepEqual(resultSummary(result(["Sodium"], [], ["Fabric API"])), { tone: "success", title: "Updated Sodium", body: "Also added Fabric API." });
  assert.equal(resultSummary(result(["Sodium", "Iris"], [])).title, "Updated 2 projects");
  assert.deepEqual(resultSummary(result(["Sodium"], [["Iris", "Download failed"]])), { tone: "neutral", title: "Updated 1 of 2 projects", body: "Iris: Download failed" });
  assert.deepEqual(resultSummary(result([], [["Iris", "Download failed"]])), { tone: "danger", title: "Couldn't update Iris", body: "Download failed" });
  assert.equal(resultSummary(result([], [])).title, "Already up to date");
});

test("checks are reused for 30 minutes, shared while running, and redone after a change", async () => {
  const check = (n: number): UpdateCheck => ({ updates: [], unmanaged: [], checkedAt: n, gameVersion: "26.2", loader: "fabric" });
  let calls = 0;
  let clock = 1_000_000;
  const now = () => clock;
  const fetch = () => Promise.resolve(check(++calls));

  const [a, b] = await Promise.all([runCheck("p1", fetch, { now }), runCheck("p1", fetch, { now })]);
  assert.equal(calls, 1, "a second look while checking joins the first");
  assert.equal(a, b);
  assert.equal((await runCheck("p1", fetch, { now })).checkedAt, 1, "reused while fresh");
  assert.equal((await runCheck("p1", fetch, { now, fresh: true })).checkedAt, 2, "the manual refresh checks again");
  clock += CHECK_TTL_MS;
  assert.equal(cachedCheck("p1", clock), null);
  assert.equal((await runCheck("p1", fetch, { now })).checkedAt, 3, "checked again once 30 minutes have passed");

  // A check that started before the profile changed isn't joined or kept afterwards.
  let release: (c: UpdateCheck) => void = () => {};
  const slow = runCheck("p2", () => new Promise((resolve) => (release = resolve)), { now });
  forgetCheck("p2");
  const after = runCheck("p2", fetch, { now });
  release(check(99));
  assert.equal((await slow).checkedAt, 99);
  assert.equal((await after).checkedAt, 4);
  assert.equal(cachedCheck("p2", clock)?.checkedAt, 4);
});
