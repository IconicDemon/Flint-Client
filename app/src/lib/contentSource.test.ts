import assert from "node:assert/strict";
import { test } from "node:test";
import { filePage, isCurseForge, prefetchNext, projectPage, rememberSource, sessionSource, sourceOf, unavailableOf } from "./contentSource.ts";

test("an id's source: cf:<number> is CurseForge, everything else Modrinth", () => {
  assert.equal(sourceOf("cf:238222"), "curseforge");
  for (const id of ["AANobbMI", "fabric-api", "cf:", "cf:0", "cf:12a", "cf:-1", "CF:1", "cf:1234567890123", null, undefined, ""]) assert.equal(sourceOf(id), "modrinth", String(id));
  assert.ok(isCurseForge("cf:1") && !isCurseForge("P7dR8mSH"));
});

test("the picked source lasts for the session and defaults to Modrinth", () => {
  const box = new Map<string, string>();
  const storage = { getItem: (k: string) => box.get(k) ?? null, setItem: (k: string, v: string) => void box.set(k, v) };
  assert.equal(sessionSource(storage), "modrinth");
  rememberSource("curseforge", storage);
  assert.equal(sessionSource(storage), "curseforge");
  rememberSource("modrinth", storage);
  assert.equal(sessionSource(storage), "modrinth");
  box.set("flint-library-source", "something-else");
  assert.equal(sessionSource(storage), "modrinth");
  const broken = { getItem: () => { throw new Error("denied"); }, setItem: () => { throw new Error("denied"); } };
  assert.equal(sessionSource(broken), "modrinth");
  assert.doesNotThrow(() => rememberSource("curseforge", broken));
  assert.equal(sessionSource(null), "modrinth");
});

test("project pages open on the project's own site", () => {
  assert.equal(projectPage({ projectId: "AANobbMI", slug: "sodium", projectType: "mod" }, "mod"), "https://modrinth.com/mod/sodium");
  assert.equal(projectPage({ projectId: "x", slug: "terralith", projectType: "mod" }, "datapack"), "https://modrinth.com/datapack/terralith");
  assert.equal(projectPage({ projectId: "cf:394468", slug: "sodium", projectType: "mod", websiteUrl: "https://www.curseforge.com/minecraft/mc-mods/sodium" }, "mod"), "https://www.curseforge.com/minecraft/mc-mods/sodium");
  assert.equal(projectPage({ projectId: "cf:1", slug: "a b", projectType: "mod", websiteUrl: "javascript:alert(1)" }, "mod"), "https://www.curseforge.com/minecraft/search?search=a%20b");
});

test("a CurseForge file's page", () => {
  assert.equal(filePage("https://www.curseforge.com/minecraft/mc-mods/x/", "cf:4567"), "https://www.curseforge.com/minecraft/mc-mods/x/files/4567");
  assert.equal(filePage("https://www.curseforge.com/minecraft/mc-mods/x", null), "https://www.curseforge.com/minecraft/mc-mods/x");
  assert.equal(filePage("http://insecure.example", "cf:1"), null);
  assert.equal(filePage(null, "cf:1"), null);
});

test("the next page is fetched ahead only when there is one", () => {
  assert.equal(prefetchNext(100, 0, 24), true);
  assert.equal(prefetchNext(48, 1, 24), false);
  assert.equal(prefetchNext(24, 0, 24), false);
  assert.equal(prefetchNext(50_000, 416, 24), false, "CurseForge and Modrinth stop at 10,000 results");
});

test("CurseForge's calm states are read from the search answer", () => {
  assert.equal(unavailableOf({ unavailable: "busy" }), "busy");
  assert.equal(unavailableOf({ unavailable: "unavailable" }), "unavailable");
  assert.equal(unavailableOf({ unavailable: "offline" }), "offline");
  assert.equal(unavailableOf({ unavailable: "ornithe" }), "ornithe", "no CurseForge mods for 1.8.9");
  assert.equal(unavailableOf({ unavailable: null }), null);
  assert.equal(unavailableOf({ unavailable: "weird" }), null);
  assert.equal(unavailableOf(null), null);
});
