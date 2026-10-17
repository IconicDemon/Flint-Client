// Run with `npm test`. The fallback must cover every goal in a request, not just the first.
import assert from "node:assert/strict";
import { test } from "node:test";
import { CATEGORIES, GOALS, lookupRequest, understand } from "./intents.ts";

const ids = (prompt: string) => understand(prompt).goals.map((g) => g.id);
const projects = (prompt: string) => understand(prompt).requests.filter((r) => r.project).map((r) => r.project);
const queries = (prompt: string) => understand(prompt).requests.filter((r) => r.query).map((r) => r.query);

test("FPS and PvP are both recognised and both get picks", () => {
  const prompt = "I want the best FPS mods and good PvP mods for Minecraft 26.3 on Fabric.";
  assert.deepEqual(ids(prompt), ["performance", "pvp"]);
  assert.ok(projects(prompt).includes("sodium"), "performance pick");
  assert.ok(projects(prompt).includes("zoomify"), "PvP pick");
  assert.ok(queries(prompt).includes("toggle sprint") && queries(prompt).includes("keystrokes"), "PvP searches");
  const reasons = understand(prompt).requests.map((r) => r.reason).join(" | ");
  assert.match(reasons, /For Performance/);
  assert.match(reasons, /For PvP/);
});

test("version and loader words are never searched as mod names", () => {
  const q = queries("I want the best FPS mods and good PvP mods for Minecraft 26.3 on Fabric.");
  assert.ok(!q.some((x) => /26\.3|minecraft|fabric/i.test(x ?? "")), JSON.stringify(q));
});

test("a smooth vanilla survival setup on a laptop", () => {
  const g = ids("I want a vanilla-looking survival setup that runs smoothly on a laptop.");
  for (const id of ["performance", "lowEnd", "survival"]) assert.ok(g.includes(id), `${id} in ${g}`);
});

test("beautiful shaders that don't destroy FPS", () => {
  const p = "I want shaders that look beautiful but don't destroy my FPS.";
  assert.ok(ids(p).includes("shaders") && ids(p).includes("performance"));
  // Shader packs, not the Iris loader (the resolver adds Iris as their dependency).
  assert.ok(projects(p).includes("complementary-reimagined") && !projects(p).includes("iris"));
  assert.equal(GOALS.find((g) => g.id === "shaders")?.kind, "shader");
});

test("minimap, inventory tweaks and quality of life together", () => {
  const p = "I want a minimap, inventory tweaks and quality-of-life mods that work together.";
  const g = ids(p);
  for (const id of ["minimap", "inventory", "qol"]) assert.ok(g.includes(id), `${id} in ${g}`);
  assert.ok(projects(p).includes("xaeros-minimap") && projects(p).includes("mouse-tweaks") && projects(p).includes("appleskin"));
});

test("lightweight mods for 4 GB of RAM", () => {
  assert.ok(ids("I want lightweight mods for a computer with only 4 GB of RAM.").includes("lowEnd"));
});

test("shared picks are listed once, crediting every goal", () => {
  const r = understand("better fps on my laptop").requests.filter((x) => x.project === "sodium");
  assert.equal(r.length, 1);
  assert.equal(r[0].reason, "For Performance + Low-end PC");
});

test("unrecognised wishes still get their own search", () => {
  const q = queries("better fps and a backpack mod");
  assert.ok(q.includes("backpack"), JSON.stringify(q));
});

test("plain chatter produces nothing", () => {
  assert.deepEqual(understand("hi").requests, []);
});

test("HUD wishes (armor HUD, toggle sprint) are their own goal", () => {
  const p = "I want a minimap, zoom, toggle sprint, armor HUD, inventory tweaks and smooth FPS.";
  for (const id of ["minimap", "zoom", "hud", "inventory", "performance"]) assert.ok(ids(p).includes(id), `${id} in ${ids(p)}`);
  assert.ok(queries(p).includes("armor hud") && queries(p).includes("toggle sprint"));
});

test("building, replay and cinematic content", () => {
  const p = "I want mods for building, replay recording and cinematic Minecraft content.";
  assert.deepEqual(ids(p), ["building", "replay"]);
  assert.ok(projects(p).includes("replaymod"));
  assert.deepEqual(understand(p).leftovers, [], "nothing left over to search as noise");
});

test("describing words are not searched as mod names", () => {
  for (const p of [
    "I have a low-end laptop with 4 GB RAM. Give me a lightweight survival setup.",
    "I want beautiful shaders but I still want good performance.",
    "I want a clean vanilla experience that feels better without changing the gameplay.",
    "I want PvP practice utilities, stable FPS and fast inventory management.",
  ]) {
    assert.deepEqual(understand(p).leftovers, [], p);
  }
});

test("every goal has a category from the AI contract", () => {
  for (const g of GOALS) assert.ok((CATEGORIES as readonly string[]).includes(g.category), g.id);
});

test("a powerful computer isn't treated as low-end", () => {
  const p = "I have a gaming PC with 32 GB RAM and want shaders.";
  assert.deepEqual(ids(p), ["shaders"]);
  assert.deepEqual(understand(p).leftovers, [], "neither the RAM nor the word gaming is searched");
  assert.ok(!ids("smooth fps on my gaming laptop").includes("lowEnd"));
  assert.ok(ids("an old laptop with 2GB").includes("lowEnd"));
});

test("things the player doesn't want are excluded, not searched", () => {
  const u = understand("a minimap and a backpack but no optifine");
  assert.deepEqual(u.exclude, ["optifine"]);
  assert.deepEqual(u.leftovers, ["backpack"]);
  assert.ok(!ids("better fps without shaders").includes("shaders"));
  assert.deepEqual(understand("I don't want Sodium, just a minimap").exclude, ["sodium"]);
});

test("no lag is a wish, not an exclusion", () => {
  const u = understand("no lag and no fps drops please");
  assert.deepEqual(u.exclude, []);
  assert.ok(ids("no lag and no fps drops please").includes("performance"));
  assert.deepEqual(understand("feels better without changing the gameplay").exclude, [], "describing words exclude nothing");
});

test("exploration and interface wishes have their own goals", () => {
  const p = "I want to explore and find structures, plus a cleaner mod menu interface.";
  assert.ok(ids(p).includes("exploration") && ids(p).includes("ui"), String(ids(p)));
  assert.ok(projects(p).includes("natures-compass") && projects(p).includes("modmenu"));
});

test("phrasing words around a wish are never searched on their own", () => {
  for (const p of [
    "I need a mod that lets me zoom with a key",
    "I want the best FPS mods and some useful PvP mods, plus lower fire",
    "Something that makes inventory management easier",
  ]) {
    assert.deepEqual(understand(p).leftovers, [], p);
  }
  assert.deepEqual(understand("a minimap and a backpack").leftovers, ["backpack"], "real extra wishes are still searched");
});

test("a forgotten project is recognised as one lookup with its features and name hint", () => {
  assert.deepEqual(lookupRequest("a mod starting with L that lowers fire, makes the shield smaller, removes pumpkin blur and reduces fog"), {
    features: ["low fire", "low shield", "no pumpkin blur", "low fog"],
    nameHint: "L",
    kind: "mod",
  });
  assert.equal(lookupRequest("I forgot the name of a shader that looks like the old bedrock one")?.kind, "shader");
  assert.equal(lookupRequest("there was a resource pack called something like Lumi that makes swords short")?.nameHint, "Lumi");
  for (const p of ["a mod that lets me zoom with a key", "I want a best minecraft fps boosting mods that boost my fps and I also want best pvp mods", "install sodium and iris"]) {
    assert.equal(lookupRequest(p), null, p);
  }
});

test("projects the player names are picked as themselves, with their kind", () => {
  const u = understand("install sodium and iris, plus complementary shaders");
  assert.deepEqual(u.named.map((n) => `${n.project}:${n.kind}`), ["sodium:mod", "iris:mod", "complementary-reimagined:shader"]);
  assert.deepEqual(u.leftovers, [], "named projects aren't searched as words");
  assert.deepEqual(understand("best fps boosting mods").leftovers, [], "'boosting' is part of the performance wish, not a search");
});
