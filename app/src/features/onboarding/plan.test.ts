// Run with `npm test`. Covers what the onboarding plan ticks, installs and shows.
import assert from "node:assert/strict";
import { test } from "node:test";
import type { InstalledItem, PlanItem } from "../../lib/api.ts";
import { RESOLVE_LIMIT, compatibility, installBatches, installedGroups, joinResolved, mergePlan, pickRequests, planSelection, rebasePlan, reviewGroups, withoutPick, type PlanEntry } from "./plan.ts";

const item = (title: string, source: PlanItem["source"], extra: Partial<PlanItem> = {}): PlanItem => ({
  projectId: title.toLowerCase().replace(/\W+/g, "-"),
  slug: title.toLowerCase().replace(/\W+/g, "-"),
  title,
  kind: "mod",
  source,
  status: "ok",
  versionId: `v-${title}`,
  ...extra,
});

/** The owner's real first-run plan (1.21.11, "best FPS and PvP mods"), trimmed to what matters. */
const ownerResult = (): PlanItem[] => [
  item("Sodium", "assistant"),
  item("Armor Hud", "assistant"),
  item("Zoomify", "assistant"),
  item("Mod Menu", "optional", { requiredBy: "Armor Hud" }),
  item("Fabric API", "dependency", { requiredBy: "Armor Hud", requiredByAll: ["Armor Hud", "Zoomify", "Mod Menu"] }),
  item("YACL", "dependency", { requiredBy: "Zoomify", requiredByAll: ["Zoomify"] }),
  item("Text Placeholder API", "dependency", { requiredBy: "Mod Menu", requiredByAll: ["Mod Menu"], versionNumber: "2.8.2+1.21.10" }),
];

const ids = (items: { projectId: string }[]) => items.map((i) => i.projectId).sort();
const byTitle = (plan: PlanEntry[], title: string) => plan.find((i) => i.title === title)!;

test("optional extras start off; picks start on", () => {
  const plan = mergePlan([], ownerResult());
  assert.equal(byTitle(plan, "Mod Menu").selected, false);
  assert.equal(byTitle(plan, "Sodium").selected, true);
});

test("an unticked extra installs nothing, including the dependency only it needs", () => {
  const plan = mergePlan([], ownerResult());
  const { on } = planSelection(plan);
  assert.ok(!on.has("mod-menu"));
  assert.ok(!on.has("text-placeholder-api"), "Text Placeholder API is only for Mod Menu");
  assert.ok(on.has("fabric-api") && on.has("yacl"));
  const groups = reviewGroups(plan);
  assert.deepEqual(ids(groups.picks), ["armor-hud", "sodium", "zoomify"]);
  assert.deepEqual(ids(groups.dependencies), ["fabric-api", "yacl"]);
  assert.deepEqual(ids(groups.optional), []);
  assert.deepEqual(ids(groups.optionalOff), ["mod-menu"]);
});

test("ticking an extra brings its dependency; unticking its mod drops both", () => {
  const plan = mergePlan([], ownerResult()).map((i) => (i.title === "Mod Menu" ? { ...i, selected: true } : i));
  assert.ok(planSelection(plan).on.has("text-placeholder-api"));
  assert.deepEqual(ids(reviewGroups(plan).optional), ["mod-menu"]);
  const noHud = plan.map((i) => (i.title === "Armor Hud" ? { ...i, selected: false } : i));
  const { on } = planSelection(noHud);
  assert.ok(!on.has("mod-menu") && !on.has("text-placeholder-api"));
});

test("Setup installs the extra and its library as a separate batch, only when ticked", () => {
  const off = installBatches(ownerResult(), new Set());
  assert.deepEqual(ids(off.required), ["armor-hud", "fabric-api", "sodium", "yacl", "zoomify"]);
  assert.deepEqual(off.optional, []);
  const on = installBatches(ownerResult(), new Set(["mod-menu"]));
  assert.deepEqual(ids(on.required), ids(off.required));
  assert.deepEqual(ids(on.optional), ["mod-menu", "text-placeholder-api"]);
});

test("already-installed projects still hold their dependencies in", () => {
  const resolved = ownerResult().map((i) => (i.title === "Zoomify" ? { ...i, status: "installed" as const } : i));
  const { required } = installBatches(resolved, new Set());
  assert.ok(ids(required).includes("yacl"));
  assert.ok(!ids(required).includes("zoomify"));
});

test("an extra a later search requires becomes a dependency; one searched for becomes a pick", () => {
  const plan = mergePlan([], ownerResult());
  const needed = mergePlan(plan, [item("Mod Menu", "dependency", { requiredByAll: ["Some HUD"] }), item("Some HUD", "user")]);
  assert.equal(byTitle(needed, "Mod Menu").source, "dependency");
  assert.ok(planSelection(needed).on.has("mod-menu"));
  const searched = mergePlan(plan, [item("Mod Menu", "user")]);
  assert.equal(byTitle(searched, "Mod Menu").source, "user");
  assert.equal(byTitle(searched, "Mod Menu").selected, true);
});

test("removing a pick removes what only it brought in", () => {
  const plan = withoutPick(mergePlan([], ownerResult()), "zoomify");
  assert.ok(!plan.some((i) => i.title === "YACL"));
  assert.ok(plan.some((i) => i.title === "Fabric API"), "Armor Hud still needs Fabric API");
});

test("picks are resolved in batches the resolver takes whole, keeping their kind", () => {
  const picks = Array.from({ length: RESOLVE_LIMIT + 3 }, (_, i) => ({ ...item(`Mod ${i}`, "user"), selected: true }));
  picks[0] = { ...picks[0], kind: "resourcepack" };
  const batches = pickRequests(picks);
  assert.deepEqual(batches.map((b) => b.length), [RESOLVE_LIMIT, 3]);
  assert.equal(batches[0][0].kind, "resourcepack");
  assert.equal(batches[0][1].kind, "mod");
});

test("joined batches keep one row per project, preferring the pick", () => {
  const joined = joinResolved([
    [item("Fabric API", "dependency", { requiredByAll: ["A"] }), item("A", "user")],
    [item("Fabric API", "user", { requiredByAll: ["B"] }), item("B", "user")],
  ]);
  assert.equal(joined.length, 3);
  const api = joined.find((i) => i.title === "Fabric API")!;
  assert.equal(api.source, "user");
  assert.deepEqual(api.requiredByAll, ["A", "B"]);
});

test("a version change keeps ticks and goals", () => {
  const old = mergePlan([], [{ ...item("Sodium", "assistant"), goal: "Performance essentials" }, item("Armor Hud", "assistant", { status: "unavailable" }), item("Zoomify", "user"), item("Mod Menu", "optional")]).map((i) =>
    i.title === "Zoomify" ? { ...i, selected: false } : i,
  );
  const fresh = rebasePlan(old, [item("Sodium", "assistant"), item("Armor Hud", "assistant"), item("Zoomify", "user"), item("Mod Menu", "optional")]);
  assert.equal(byTitle(fresh, "Sodium").goal, "Performance essentials");
  assert.equal(byTitle(fresh, "Armor Hud").selected, true, "now available, so ticked");
  assert.equal(byTitle(fresh, "Zoomify").selected, false, "the player unticked it");
  assert.equal(byTitle(fresh, "Mod Menu").selected, false);
});

test("compatibility reads as the profile's version, with the build name in the tooltip", () => {
  const c = compatibility({ versionNumber: "2.8.2+1.21.10", versionType: "release" }, "1.21.11");
  assert.equal(c.label, "Works with Minecraft 1.21.11");
  assert.match(c.title, /2\.8\.2\+1\.21\.10/);
  assert.match(compatibility({ versionNumber: "1.0", versionType: "beta" }, "1.21.11").title, /beta/);
});

test("the finished screen groups what the profile really has", () => {
  const installed = (title: string, source: string, missing = false) => ({ title, source, missing, kind: "mod", fileName: `${title}.jar`, size: 1, enabled: true, tracked: true, requires: [] }) as unknown as InstalledItem;
  const g = installedGroups([
    installed("Sodium", "assistant"),
    installed("FerriteCore", "flint"),
    installed("Cloth Config API", "dependency"),
    installed("Mod Menu", "optional"),
    installed("Gone", "user", true),
    installed("Dropped in", "manual"),
  ]);
  assert.equal(g.total, 5);
  assert.deepEqual(g.picks.map((i) => i.title), ["Dropped in", "Sodium"]);
  assert.deepEqual(g.performance.map((i) => i.title), ["FerriteCore"]);
  assert.deepEqual(g.dependencies.map((i) => i.title), ["Cloth Config API"]);
  assert.deepEqual(g.optional.map((i) => i.title), ["Mod Menu"]);
});
