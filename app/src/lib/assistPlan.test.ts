// Run with `npm test`. Covers turning requests into balanced, grouped mod plans, with and without Flint AI.
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  GROUP,
  PERFORMANCE_CAP,
  aiGoals,
  applyExclusions,
  buildRequests,
  groupPlan,
  groupReasons,
  dropAlternatives,
  isGenericSearch,
  groupTitle,
  markMissing,
  mergeGoals,
  parseIntents,
  planRequests,
  sanitizeAiGoals,
  selectPlan,
  smartGoals,
  tagItems,
  type AiIntents,
  type Category,
} from "./assistPlan.ts";

/** The evaluation set: each request and the goal categories it must produce (alternatives joined with "|"). */
const EVALUATION: { prompt: string; expect: string[] }[] = [
  { prompt: "I want the best FPS mods and some good PvP mods.", expect: ["performance", "pvp"] },
  { prompt: "I have a low-end laptop with 4 GB RAM. Give me a lightweight survival setup.", expect: ["lowEnd", "survival"] },
  { prompt: "I want a minimap, zoom, toggle sprint, armor HUD, inventory tweaks and smooth FPS.", expect: ["minimap", "hud|pvp", "inventory", "performance"] },
  { prompt: "I want beautiful shaders but I still want good performance.", expect: ["shaders", "performance"] },
  { prompt: "I want a clean vanilla experience that feels better without changing the gameplay.", expect: ["survival|qol"] },
  { prompt: "I want mods for building, replay recording and cinematic Minecraft content.", expect: ["building", "replay"] },
  { prompt: "I want PvP practice utilities, stable FPS and fast inventory management.", expect: ["pvp", "performance", "inventory"] },
];

const categories = (prompt: string) => [...new Set(smartGoals(prompt).map((g) => g.category))];

test("evaluation set: the smart search covers every goal in each request", () => {
  const rows = EVALUATION.map(({ prompt, expect }) => {
    const found = categories(prompt);
    const missing = expect.filter((alt) => !alt.split("|").some((c) => found.includes(c as Category)));
    return { request: prompt.length > 48 ? `${prompt.slice(0, 47)}…` : prompt, expected: expect.join(", "), found: found.join(", "), ok: missing.length === 0 ? "yes" : `missing ${missing.join(", ")}` };
  });
  console.log("\nAssistant evaluation (deterministic path)");
  console.table(rows);
  for (const row of rows) assert.equal(row.ok, "yes", `${row.request}: ${row.ok}`);
});

test("evaluation set: every request yields picks, and no single goal crowds out the rest", () => {
  for (const { prompt } of EVALUATION) {
    const { goals, requests } = planRequests(prompt, null);
    assert.ok(requests.length > 0 && requests.length <= 16, prompt);
    assert.ok(requests.every((r) => r.source === "user"), "smart search picks are not labelled as AI");
    if (new Set(goals.map(groupTitle)).size > 1) {
      const groups = new Set(requests.map((r) => r.group));
      assert.ok(groups.size > 1, `${prompt} → ${[...groups]}`);
    }
  }
});

test("FPS + PvP stays balanced: performance picks are capped when other goals exist", () => {
  const requests = buildRequests(smartGoals("I want the best FPS mods and some good PvP mods."));
  const perf = requests.filter((r) => r.group === GROUP.performance);
  const pvp = requests.filter((r) => r.group === GROUP.pvp);
  assert.ok(perf.length <= PERFORMANCE_CAP, `performance picks: ${perf.length}`);
  assert.ok(perf.length >= 1 && pvp.length >= 3, `pvp picks: ${pvp.length}`);
  assert.ok(perf.some((r) => r.project === "sodium"));
  assert.ok(pvp.some((r) => r.project === "zoomify") && pvp.some((r) => r.query === "toggle sprint"));
});

test("performance alone keeps the full performance set", () => {
  const requests = buildRequests(smartGoals("better FPS please"));
  assert.ok(requests.length >= 5, JSON.stringify(requests));
});

test("projects come before searches, and shared picks credit every goal once", () => {
  const requests = buildRequests(smartGoals("smooth fps on my laptop"));
  const firstSearch = requests.findIndex((r) => r.query);
  assert.ok(firstSearch === -1 || requests.slice(firstSearch).every((r) => !r.project));
  const sodium = requests.filter((r) => r.project === "sodium");
  assert.equal(sodium.length, 1);
  assert.equal(sodium[0].reason, "For Performance + Low-end PC");
});

const VALID: AiIntents = {
  goals: [{ category: "performance", kind: "mod", label: "Performance essentials", why: "Raises and steadies frame rates.", searches: ["fps boost"], projects: ["sodium", "lithium"] }],
  constraints: { lowEndHardware: false, ramGb: null, keepVanilla: false },
  exclude: [],
  note: null,
  model: "@cf/openai/gpt-oss-20b",
  quota: null,
};

test("a malformed AI payload is rejected", () => {
  for (const bad of [null, undefined, "sodium", 42, [], {}, { goals: "performance" }, { goals: [] }, { mods: [{ name: "Sodium" }] }, { goals: [{ category: "hacks", label: "x", searches: ["a b"], projects: ["wurst"] }] }, { goals: [{ category: "pvp", label: "PvP", searches: [], projects: ["../../etc", "UPPER CASE", ".."] }] }]) {
    assert.equal(parseIntents(bad), null, JSON.stringify(bad));
  }
});

test("an AI payload with some bad fields keeps only the safe parts", () => {
  const parsed = parseIntents({
    goals: [
      { category: "performance", label: "Speed\u0007".padEnd(80, "!"), searches: ["<b>fps</b>", "x", 7], projects: ["sodium", "../evil", "Lithium"] },
      { category: "made-up", label: "Nope", searches: ["anything"], projects: [] },
    ],
    constraints: { lowEndHardware: "true", ramGb: -3, keepVanilla: true },
    exclude: ["optifine", 5, "optifine"],
    note: "n".repeat(500),
    model: "bad model\n",
  });
  assert.ok(parsed);
  assert.equal(parsed.goals.length, 1);
  assert.deepEqual(parsed.goals[0].projects, ["sodium", "lithium"]);
  assert.deepEqual(parsed.goals[0].searches, ["b fps b"]);
  assert.ok(parsed.goals[0].label.length <= 40 && !parsed.goals[0].label.includes("\u0007"));
  assert.deepEqual(parsed.constraints, { lowEndHardware: false, ramGb: null, keepVanilla: true });
  assert.deepEqual(parsed.exclude, ["optifine"]);
  assert.equal(parsed.note?.length, 240);
  assert.equal(parsed.model, null);
  assert.deepEqual(parseIntents(VALID), VALID);
});

test("recognised goals the AI missed are added from the smart search, leftover-word searches never", () => {
  const prompt = "I want better FPS, good PvP mods, a minimap and a backpack.";
  const merged = mergeGoals(aiGoals(VALID), smartGoals(prompt));
  const cats = merged.map((g) => g.category);
  assert.equal(cats.filter((c) => c === "performance").length, 1, "the AI's performance goal isn't duplicated");
  for (const c of ["pvp", "minimap"]) assert.ok(cats.includes(c as Category), `${c} in ${cats}`);
  assert.ok(!cats.includes("other"), "the AI read the whole request; leftover words are not searched on top of its answer");
  assert.ok(smartGoals(prompt).some((g) => g.role === "leftover" && g.searches.includes("backpack")), "the smart search alone still searches them");
  const { requests } = planRequests(prompt, VALID);
  assert.ok(requests.some((r) => r.project === "sodium" && r.source === "assistant"), "AI picks are labelled as AI");
  assert.ok(requests.some((r) => r.project === "xaeros-minimap" && r.source === "user"), "smart search picks are not");
});

test("a specific wish the AI covered under another category isn't added twice", () => {
  const ai = parseIntents({ goals: [{ category: "pvp", label: "PvP & HUD", searches: ["toggle sprint"], projects: ["zoomify"] }] })!;
  const merged = mergeGoals(aiGoals(ai), smartGoals("pvp and zoom"));
  assert.deepEqual(merged.map((g) => g.category), ["pvp"]);
});

test("exclusions remove picks and empty goals", () => {
  const goals = applyExclusions(smartGoals("better fps with shaders"), ["sodium", "shaders"]);
  assert.ok(!goals.some((g) => g.category === "shaders"));
  assert.ok(!goals.some((g) => g.projects.includes("sodium")));
  assert.ok(goals.some((g) => g.projects.includes("lithium")));
});

test("low-end hardware from the AI adds the low-end set", () => {
  const { goals } = planRequests("a survival setup", { ...VALID, goals: [{ category: "survival", label: "Survival", searches: [], projects: ["appleskin"] }], constraints: { lowEndHardware: false, ramGb: 4, keepVanilla: true } });
  assert.ok(goals.some((g) => g.category === "lowEnd"));
});

test("resolved items are grouped by goal, then dependencies and optional extras", () => {
  const requests = buildRequests(smartGoals("best fps and good pvp mods and replay recording"));
  const item = (projectId: string, slug: string, source: string, reason?: string) => ({ projectId, slug, source, reason });
  const items = tagItems(
    [
      item("ID-SODIUM", "sodium", "user", "For Performance"),
      item("ID-ZOOM", "zoomify", "user"),
      item("ID-SEARCHED", "some-sprint-mod", "user"),
      item("ID-REPLAY", "replaymod", "user"),
      item("ID-API", "fabric-api", "dependency"),
      item("ID-MENU", "modmenu", "optional"),
      item("ID-MINE", "my-own-pick", "user", null as unknown as string),
    ],
    requests,
    requests.map((r) => (r.query === "toggle sprint" ? "ID-SEARCHED" : null)),
  );
  const groups = groupPlan(items);
  assert.deepEqual(
    groups.map((g) => g.title),
    [GROUP.performance, GROUP.pvp, "Replay & cinematics", GROUP.picks, GROUP.dependencies, GROUP.optional],
  );
  assert.deepEqual(groups.find((g) => g.title === GROUP.pvp)?.items.map((i) => i.projectId), ["ID-ZOOM", "ID-SEARCHED"]);
  assert.equal(groups.find((g) => g.kind === "dependency")?.items[0].projectId, "ID-API");
});

test("a gaming PC with plenty of memory gets no low-end picks, with or without the AI", () => {
  const prompt = "I have a gaming PC with 32 GB RAM and want shaders.";
  assert.ok(!planRequests(prompt, null).goals.some((g) => g.category === "lowEnd"));
  const ai: AiIntents = { ...VALID, goals: [{ category: "shaders", label: "Shaders", searches: [], projects: ["iris"] }], constraints: { lowEndHardware: false, ramGb: 32, keepVanilla: false } };
  assert.ok(!planRequests(prompt, ai).goals.some((g) => g.category === "lowEnd"));
  // A smart-search low-end goal still joins when the AI agrees the computer is low-end.
  const low: AiIntents = { ...ai, constraints: { lowEndHardware: true, ramGb: 4, keepVanilla: false } };
  assert.ok(planRequests("shaders on a potato laptop", low).goals.some((g) => g.category === "lowEnd"));
});

test("performance mods are shown under performance, whichever goal suggested them", () => {
  for (const prompt of [
    "I have a low-end laptop with 4 GB RAM. Give me a lightweight survival setup.",
    "I want a clean vanilla experience that feels better without changing the gameplay.",
    "I want beautiful shaders and smooth fps.",
  ]) {
    const { requests } = planRequests(prompt, null);
    for (const slug of ["sodium", "lithium", "ferrite-core"]) {
      const r = requests.find((x) => x.project === slug);
      if (r) assert.equal(r.group, GROUP.performance, `${slug} in ${prompt}`);
    }
    assert.ok(requests.some((r) => r.project === "sodium"), prompt);
  }
  assert.equal(planRequests("a survival setup", null).requests.find((r) => r.project === "appleskin")?.group, GROUP.qol);
});

test("the smart search honours exclusions", () => {
  const { requests } = planRequests("better fps but not sodium", null);
  assert.ok(!requests.some((r) => r.project === "sodium"));
  assert.ok(requests.some((r) => r.project === "lithium"));
  const second = planRequests("a minimap and a backpack but no optifine", null).requests;
  assert.ok(second.some((r) => r.query === "backpack") && !second.some((r) => /optifine/.test(r.query ?? r.project ?? "")));
});

test("keepVanilla drops visual goals the player didn't ask for", () => {
  const ai: AiIntents = {
    ...VALID,
    goals: [VALID.goals[0], { category: "visual", label: "Visuals", searches: ["better clouds"], projects: ["continuity"] }],
    constraints: { lowEndHardware: false, ramGb: null, keepVanilla: true },
  };
  assert.ok(!planRequests("a clean vanilla experience with better fps", ai).goals.some((g) => g.category === "visual"));
  assert.ok(planRequests("vanilla but with nicer visuals", ai).goals.some((g) => g.category === "visual"));
});

type Item = { projectId: string; title: string; source: string; status: string; requiredBy?: string | null; requiredByAll?: string[] | null; message?: string | null };
const planItem = (projectId: string, source: string, requiredByAll: string[] = [], status = "ok"): Item => ({ projectId, title: projectId, source, status, requiredBy: requiredByAll[0] ?? null, requiredByAll });

test("a dependency shared by two picks comes along with either of them", () => {
  const items = [planItem("Zoom", "user"), planItem("Map", "user"), planItem("Api", "dependency", ["Zoom", "Map"]), planItem("Menu", "optional", ["Zoom"])];
  const onlyMap = selectPlan(items, { Zoom: false, Map: true, Menu: true });
  assert.deepEqual([...onlyMap.on].sort(), ["Api", "Map"]);
  assert.deepEqual([...selectPlan(items, { Zoom: false, Map: false }).on], []);
  assert.ok(selectPlan(items, { Zoom: true, Map: false, Menu: true }).on.has("Menu"), "a ticked extra follows its mod");
  // A pick another included pick requires stays in, and can't be unticked.
  const picks = [planItem("Lib", "user", ["Map"]), planItem("Map", "user")];
  const s = selectPlan(picks, { Lib: false, Map: true });
  assert.ok(s.on.has("Lib") && s.forced.has("Lib") && !s.forced.has("Map"));
});

test("a missing dependency holds back every project that needs it, down chains", () => {
  const items = markMissing([
    planItem("Zoom", "user"),
    planItem("Map", "user"),
    planItem("Api", "dependency", ["Zoom", "Map"], "unavailable"),
    planItem("Hud", "user"),
    planItem("Lib", "dependency", ["Hud"]),
    planItem("Core", "dependency", ["Lib"], "conflict"),
    planItem("Fine", "user"),
  ]);
  const status = Object.fromEntries(items.map((i) => [i.projectId, i.status]));
  assert.deepEqual(status, { Zoom: "unavailable", Map: "unavailable", Api: "unavailable", Hud: "unavailable", Lib: "unavailable", Core: "conflict", Fine: "ok" });
  assert.match(items.find((i) => i.projectId === "Map")!.message!, /Needs Api/);
  assert.deepEqual(selectPlan(items, { Zoom: true, Map: true, Hud: true, Fine: true }).on, new Set(["Fine"]));
});

test("each display group carries Flint AI's explanation, never the smart search's", () => {
  const intents = parseIntents({
    goals: [
      { category: "pvp", label: "PvP & HUD", why: "Fair zoom and HUD readouts for fights.", searches: ["armor hud"], projects: ["zoomify"] },
      { category: "hud", label: "HUD", why: "Second explanation for the same group.", searches: ["status effects"], projects: [] },
      { category: "exploration", label: "Exploration", why: "Find biomes and structures faster.", searches: [], projects: ["natures-compass"] },
    ],
  })!;
  assert.equal(intents.goals[0].why, "Fair zoom and HUD readouts for fights.");
  const { goals } = planRequests("pvp and exploring", intents);
  const why = groupReasons(goals);
  assert.equal(why.get(GROUP.pvp), "Fair zoom and HUD readouts for fights.", "the first explanation for a group wins");
  assert.equal(why.get("Exploration"), "Find biomes and structures faster.");
  assert.equal(groupReasons(planRequests("pvp and exploring", null).goals).size, 0);
});

// The live answer from @cf/openai/gpt-oss-20b on 2026-10-14 (staging) that produced a noisy plan.
const LIVE_SURVIVAL = {
  goals: [
    { category: "survival", label: "Lightweight Survival Essentials", why: "Adds essential survival features without heavy performance impact.", projects: ["appleskin", "betterf3", "jade", "emi"], searches: ["survival mods", "lightweight survival"] },
    { category: "minimap", label: "MiniMap Setup", why: "Provides a map overlay to help navigate the world.", projects: ["xaeros-minimap", "journeymap", "xaeros-world-map"], searches: ["minimap", "xaeros minimap", "journeymap"] },
    { category: "qol", label: "Quality of Life Tweaks", why: "Adds convenient features to improve gameplay flow.", projects: ["mouse-tweaks", "inventory-profiles-next", "shulkerboxtooltip", "controlling"], searches: ["quality of life", "qol mods", "inventory tweaks"] },
  ],
  constraints: { lowEndHardware: false, ramGb: null, keepVanilla: false },
  exclude: [],
  note: null,
};

test("a live survival + minimap + QoL answer plans named mods only, one minimap, no stray searches", () => {
  const prompt = "I want lightweight survival mods, a minimap and quality-of-life improvements.";
  const { requests } = planRequests(prompt, parseIntents(LIVE_SURVIVAL));
  const projects = requests.filter((r) => r.project).map((r) => r.project);
  const queries = requests.filter((r) => r.query).map((r) => r.query);
  assert.deepEqual(queries, [], `no generic or leftover searches: ${queries}`);
  assert.ok(projects.includes("xaeros-minimap") && !projects.includes("journeymap"), "one minimap");
  assert.ok(projects.includes("xaeros-world-map"), "the matching world map stays");
  for (const p of ["appleskin", "jade", "mouse-tweaks", "inventory-profiles-next"]) assert.ok(projects.includes(p), p);
});

test("generic searches are recognised; specific ones are kept", () => {
  for (const q of ["survival mods", "qol mods", "quality of life", "minimap", "pvp", "lightweight survival", "fps boost"]) assert.ok(isGenericSearch(q), q);
  for (const q of ["armor hud", "inventory sorting", "toggle sprint", "xaeros minimap", "zoom", "connected textures"]) assert.ok(!isGenericSearch(q), q);
});

test("interchangeable mods: the first named is kept across goals", () => {
  const goals = dropAlternatives([
    { category: "minimap", label: "Maps", projects: ["journeymap"], searches: [], from: "ai" },
    { category: "minimap", label: "More maps", projects: ["xaeros-minimap"], searches: [], from: "ai" },
    { category: "qol", label: "QoL", projects: ["jei", "emi", "appleskin"], searches: [], from: "ai" },
  ]);
  assert.deepEqual(goals.map((g) => g.projects), [["journeymap"], ["jei", "appleskin"]], "an emptied goal is dropped");
});

test("resource-pack wishes become resource-pack requests in their own group", () => {
  const intents = parseIntents({
    goals: [
      { category: "visual", kind: "resourcepack", label: "Clear view", why: "Smaller fire and shield overlays.", searches: ["low fire", "low shield"], projects: [] },
      { category: "performance", label: "FPS", searches: [], projects: ["sodium", "lithium"] },
      { category: "hud", kind: "weird", label: "Zoom", searches: [], projects: ["zoomify"] },
    ],
  })!;
  assert.deepEqual(intents.goals.map((g) => g.kind), ["resourcepack", "mod", "mod"], "unknown kinds are mods");
  const { requests } = planRequests("lower fire, smaller shield and better fps", intents);
  const packs = requests.filter((r) => r.kind === "resourcepack");
  assert.ok(packs.length >= 2 && packs.every((r) => r.group === GROUP.packs && r.query), JSON.stringify(packs));
  assert.ok(requests.some((r) => r.kind === "mod" && r.project === "sodium"));
});

test("the smart search knows common visual packs, and vanilla profiles get only packs", () => {
  const prompt = "I want lower fire, a smaller shield and no pumpkin blur, plus a zoom key";
  const fabric = planRequests(prompt, null);
  for (const q of ["low fire", "low shield", "pumpkin overlay"]) assert.ok(fabric.requests.some((r) => r.kind === "resourcepack" && r.query === q), q);
  assert.ok(fabric.requests.some((r) => r.project === "zoomify"));
  const vanilla = planRequests(prompt, null, "vanilla");
  assert.ok(vanilla.requests.length >= 3 && vanilla.requests.every((r) => r.kind === "resourcepack"));
  assert.deepEqual(vanilla.needsFabric, ["Zoom"]);
});

test("the server's quota is passed through only when it is well formed", () => {
  const base = { goals: [{ category: "pvp", label: "PvP", searches: [], projects: ["zoomify"] }] };
  assert.deepEqual(parseIntents({ ...base, quota: { limit: 3, remaining: 1, resetInMs: null } })!.quota, { limit: 3, remaining: 1, resetInMs: null });
  assert.deepEqual(parseIntents({ ...base, quota: { limit: 3, remaining: 0, resetInMs: 43_200_000 } })!.quota, { limit: 3, remaining: 0, resetInMs: 43_200_000 });
  for (const bad of [{ limit: 3, remaining: 4 }, { limit: 0, remaining: 0 }, { limit: 3, remaining: -1 }, "3", null]) {
    assert.equal(parseIntents({ ...base, quota: bad })!.quota, null, JSON.stringify(bad));
  }
  assert.equal(parseIntents({ ...base, quota: { limit: 3, remaining: 0, resetInMs: 1e12 } })!.quota!.resetInMs, null, "an absurd countdown is dropped");
});

test("a lookup only the smart search recognised replaces the AI's literal reading of the description", () => {
  const prompt = "I forgot the name of a mod that lowers fire and removes fog";
  const literal = parseIntents({ goals: [{ category: "visual", kind: "resourcepack", label: "Low fire", searches: ["low fire"], projects: [] }, { category: "visual", label: "Fog", searches: ["no fog"], projects: [] }] });
  const { lookup, requests, goals } = planRequests(prompt, literal);
  assert.deepEqual(lookup?.features, ["low fire", "no fog"]);
  assert.equal(lookup?.from, "smart");
  assert.deepEqual(requests, []);
  assert.equal(groupTitle(goals[0]), GROUP.lookup);
  // An AI lookup is used as given, including its name hint.
  const ai = parseIntents({ goals: [{ category: "other", kind: "mod", lookup: true, nameHint: "L", label: "x", searches: ["low fire", "no fog"], projects: [] }] });
  assert.deepEqual(planRequests(prompt, ai).lookup, { features: ["low fire", "no fog"], nameHint: "L", projects: [], kind: "mod", from: "ai" });
});

test("shader goals: loaders split out of shader packs, unknown shader kinds become mods", () => {
  const ai = parseIntents({
    goals: [
      { category: "shaders", kind: "mod", label: "Shaders", searches: [], projects: ["iris", "sodium", "bsl-shaders"] },
      { category: "hud", kind: "shader", label: "Odd", searches: ["armor hud"], projects: [] },
    ],
  })!;
  assert.deepEqual(ai.goals.map((g) => g.kind), ["mod", "mod"], "a shader kind needs a shader or visual goal");
  const goals = aiGoals(ai);
  assert.deepEqual(goals.map((g) => `${g.kind}:${groupTitle(g)}:${g.projects}`), [`shader:${GROUP.shaders}:bsl-shaders`, `mod:${GROUP.shaderSupport}:iris,sodium`, `mod:${GROUP.pvp}:`]);
  const { requests } = planRequests("bsl shaders please", ai);
  assert.ok(!requests.some((r) => r.project === "iris"), "Iris comes as the shader packs' required loader");
  assert.equal(requests.find((r) => r.project === "sodium")?.group, GROUP.performance);
  assert.equal(requests.find((r) => r.project === "bsl-shaders")?.kind, "shader");
  assert.deepEqual(planRequests("shaders", null, "vanilla").needsFabric, ["Shaders"], "vanilla profiles can't load shader packs");
});

test("a lookup needs a forgotten-name cue, and fire/shield tweaks are packs unless a mod was asked for (live staging answer)", () => {
  // The answer @cf/openai/gpt-oss-20b gave on staging for a plain pack request (2026-10-14).
  const live = parseIntents({ goals: [{ category: "other", kind: "mod", lookup: true, label: "Lower Fire & Small Shield", searches: ["low fire", "small shield"], projects: [] }] })!;
  const plain = planRequests("lower fire and a smaller shield", live);
  assert.equal(plain.lookup, null, "no forgotten-name cue: no lookup");
  assert.ok(plain.requests.length > 0 && plain.requests.every((r) => r.kind === "resourcepack"), JSON.stringify(plain.requests));
  const forgot = planRequests("I forgot the name of a mod starting with L that lowers fire and the shield", live);
  assert.ok(forgot.lookup, "a real forgotten-name request stays a lookup");
  const askedMod = sanitizeAiGoals("a mod that lowers fire", { ...live, goals: [{ ...live.goals[0], lookup: false }] });
  assert.equal(askedMod.goals[0].kind, "mod", "the player asked for a mod");
});
