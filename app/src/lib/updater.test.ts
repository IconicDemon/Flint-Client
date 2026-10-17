// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { canInstallNow, checkForUpdates, friendlyError, installAndRestart, progressLabel, updateState } from "./updater.ts";

const appRoot = new URL("../../", import.meta.url);
const read = (path: string) => readFileSync(new URL(path, appRoot), "utf8");

test("progress shows a whole percentage when the size is known", () => {
  assert.equal(progressLabel(0, 1000), "0%");
  assert.equal(progressLabel(455, 1000), "45%");
  assert.equal(progressLabel(1000, 1000), "100%");
  assert.equal(progressLabel(1200, 1000), "100%");
});

test("progress shows the amount received when the size is unknown", () => {
  assert.equal(progressLabel(12_345_678, null), "12.3 MB");
  assert.equal(progressLabel(5, 0), "0.0 MB");
});

test("update errors are short and never expose raw details", () => {
  assert.match(friendlyError(new Error("signature verification failed: bad key")), /signature check/);
  assert.match(friendlyError("the platform darwin-x86_64 was not found"), /no update for this computer/);
  assert.match(friendlyError(new Error("operation timed out")), /didn't answer in time/);
  assert.match(friendlyError(new Error("error sending request for url (https://example.invalid)")), /Couldn't reach/);
  assert.equal(friendlyError(undefined), "The update couldn't be completed.");
  assert.doesNotMatch(friendlyError(new Error("error sending request for url (https://example.invalid)")), /https?:/);
});

// The exact texts tauri-plugin-updater 2.14 produced in the windowless update tests (docs/UPDATE_SYSTEM.md).
test("each real updater failure gets the right message", () => {
  const signature = /signature check/;
  assert.match(friendlyError("The signature verification failed", "download"), signature);
  assert.match(friendlyError("The signature was created with a different key than the one provided", "download"), signature);
  assert.match(
    friendlyError("The update was signed for version 0.5.0 but the update endpoint announced version 0.5.1. The endpoint response may have been tampered with", "download"),
    signature,
  );
  assert.equal(friendlyError("error decoding response body", "download"), "The download didn't finish. Try again.");
  assert.equal(friendlyError("`Download request failed with status: 404 Not Found`", "download"), "The download didn't finish. Try again.");
  assert.match(friendlyError("error sending request for url (http://127.0.0.1:9/latest.json)"), /Couldn't reach/);
  assert.match(friendlyError("Could not fetch a valid release JSON from the remote"), /didn't answer properly/);
  assert.match(friendlyError("error decoding response body for url (http://127.0.0.1:8765/m-garbage.json)"), /didn't answer properly/);
  assert.match(friendlyError("the platform `darwin-x86_64` was not found in the response `platforms` object"), /no update for this computer/);
  assert.match(
    friendlyError('None of the fallback platforms `["darwin-aarch64-app", "darwin-aarch64"]` were found in the response `platforms` object'),
    /no update for this computer/,
  );
});

test("a downloaded update waits while Minecraft is starting or running", () => {
  const ready = { phase: "ready", version: "0.5.1" } as const;
  assert.equal(canInstallNow(ready, false), true);
  assert.equal(canInstallNow(ready, true), false);
  assert.equal(canInstallNow({ phase: "downloading", version: "0.5.1", received: 1, total: 2 }, false), false);
  assert.equal(canInstallNow({ phase: "available", version: "0.5.1", notes: "", date: null }, false), false);
});

test("outside the desktop app nothing checks or installs", async () => {
  await checkForUpdates(true);
  await installAndRestart(false);
  assert.deepEqual(updateState(), { phase: "idle" });
});

test("the launcher, its Rust crate and the game runtime share one version", () => {
  const pkg = JSON.parse(read("package.json")).version as string;
  const cargo = /^version\s*=\s*"([^"]+)"/m.exec(read("src-tauri/Cargo.toml"))?.[1];
  const conf = JSON.parse(read("src-tauri/tauri.conf.json"));
  assert.match(pkg, /^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$/, "package.json version is semver");
  assert.equal(cargo, pkg, "src-tauri/Cargo.toml version matches package.json");
  assert.equal(conf.version, "../package.json", "tauri.conf.json takes its version from package.json");
});

test("updates are signed and come only over HTTPS", () => {
  const conf = JSON.parse(read("src-tauri/tauri.conf.json"));
  const updater = conf.plugins?.updater;
  assert.ok(conf.bundle?.createUpdaterArtifacts, "the bundle produces signed updater artifacts");
  assert.ok(typeof updater?.pubkey === "string" && updater.pubkey.length > 40, "a public key is configured");
  assert.ok(updater.endpoints.length > 0);
  for (const endpoint of updater.endpoints) assert.match(endpoint, /^https:\/\//);
  assert.equal(updater.dangerousInsecureTransportProtocol, undefined, "release builds never allow plain HTTP");
  assert.equal(updater.requireSignedVersion, true, "an older signed build can't be replayed as a newer version");
  assert.notEqual(updater.allowDowngrades, true, "no downgrades");
});
