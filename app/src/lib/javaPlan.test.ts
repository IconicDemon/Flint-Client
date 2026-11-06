// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import { andList, customJavaWarnings, groupVersions, rowDetail, rowJava, runtimeLabel } from "./javaPlan.ts";
import type { JavaOverview } from "./api";

// What the launcher reports (src-tauri/src/javaplan.rs, version_plans): Java 25 for 26.x; Java 25 with Flint's
// in-game menu for 1.21.x and 1.8.9 (Java 21 at least, Java 21 where Mojang has no Java 25), their own Java otherwise.
const VERSIONS: JavaOverview["versions"] = ["26.3", "26.2", "26.1.2", "26.1.1", "26.1", "1.21.11", "1.21.10", "1.21.1", "1.8.9"].map((version) => {
  const own =
    version.startsWith("26.") ? { component: "java-runtime-epsilon", major: 25, minimum: 25 }
    : version === "1.8.9" ? { component: "jre-legacy", major: 8, minimum: 8 }
    : { component: "java-runtime-delta", major: 21, minimum: 21 };
  const withMenu = version.startsWith("26.") ? own : { component: "java-runtime-epsilon", major: 25, minimum: 21, fallback: 21 };
  return { version, withMenu, withoutMenu: own };
});

test("versions that run the same way share a row, in Flint's order", () => {
  const rows = groupVersions(VERSIONS);
  assert.deepEqual(rows.map((r) => r.versions), [["26.3", "26.2", "26.1.2", "26.1.1", "26.1"], ["1.21.11", "1.21.10", "1.21.1"], ["1.8.9"]]);
  assert.equal(rowJava(rows[0], true), "Java 25");
  assert.equal(rowJava(rows[1], true), "Java 25 with Flint's in-game menu, otherwise Java 21");
  assert.equal(rowJava(rows[2], true), "Java 25 with Flint's in-game menu, otherwise Java 8");
  assert.equal(rowJava(rows[2], false), "Java 8", "with the menu turned off every profile runs on its version's own Java");
  assert.match(rowDetail(rows[1], true)!, /Java 21 instead on computers Mojang has no Java 25 for/);
  assert.equal(rowDetail(rows[0], true), null);
  assert.equal(rowDetail(rows[1], false), null);
  assert.equal(andList(["a", "b", "c"]), "a, b and c");
});

test("a custom Java says which versions it can't run, and that 1.8.9 without the menu is made for Java 8", () => {
  assert.deepEqual(customJavaWarnings(25, VERSIONS, true), ["Minecraft 1.8.9 without Flint's in-game menu is made for Java 8 and may not start on Java 25."]);
  const java21 = customJavaWarnings(21, VERSIONS, true);
  assert.equal(java21[0], "Java 21 can't run Minecraft 26.3, 26.2, 26.1.2, 26.1.1 and 26.1, which need Java 25 or newer.");
  assert.match(java21[1], /1\.8\.9 without Flint's in-game menu is made for Java 8/);
  const java17 = customJavaWarnings(17, VERSIONS, true);
  assert.equal(java17[1], "Java 17 can't run Minecraft 1.21.11, 1.21.10, 1.21.1 and 1.8.9 with Flint's in-game menu, which need Java 21 or newer.");
  assert.equal(java17.length, 3);
  // Java 8 runs only 1.8.9 without the menu; with the menu off, 1.8.9 is fine on it.
  const java8 = customJavaWarnings(8, VERSIONS, false);
  assert.equal(java8.length, 2);
  assert.ok(java8.every((w) => !w.includes("1.8.9")), java8.join(" / "));
  assert.deepEqual(customJavaWarnings(null, VERSIONS, true), [], "a Java whose version isn't known gets no guesses");
});

test("runtimes read as the Java they are", () => {
  assert.equal(runtimeLabel({ component: "java-runtime-epsilon", major: 25, size: 1 }), "Java 25");
  assert.equal(runtimeLabel({ component: "jre-legacy", size: 1 }), "Java 8");
  assert.equal(runtimeLabel({ component: "something-new", size: 1 }), "something-new");
});
