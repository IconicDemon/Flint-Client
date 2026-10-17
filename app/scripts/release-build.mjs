#!/usr/bin/env node
// Builds a release app with an identifiable build number.
//
//   npm run release                       (from app/; the .app only)
//   npm run release -- --bundles app,dmg  (also the installer disk image)
//
// Every build gets a build number from the UTC time it was made (YYYY.MMDD.HHMM), which always
// increases and fits macOS's CFBundleVersion format (up to three integers). The marketing version
// stays in tauri.conf.json. The app shows both, plus the git commit, in Settings → About Flint.
// It builds game-runtime/ first (the in-game menu jar the app embeds).
// Set APPLE_SIGNING_IDENTITY to sign with a stable identity instead of the ad-hoc "-" (see
// docs/INSTALL_WORKFLOW.md); Tauri reads it directly.
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";

const now = new Date();
const pad = (n) => String(n).padStart(2, "0");
const build = `${now.getUTCFullYear()}.${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}.${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}`;

const git = (...args) => {
  try {
    return execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
  } catch {
    return "";
  }
};
const head = git("rev-parse", "--short=8", "HEAD");
const commit = head ? `${head}${git("status", "--porcelain") ? "-dirty" : ""}` : "unknown";

console.log(`Flint release build ${build} (${commit})`);

const windows = process.platform === "win32";

// Flint's in-game runtime (game-runtime/) is embedded in the app, so it is built first; the release
// fails rather than ship an app that silently lacks it. (Windows runs the batch wrappers through its shell.)
const runtime = spawnSync(windows ? "gradlew.bat" : "./gradlew", ["build", "--quiet"], { cwd: "../game-runtime", stdio: "inherit", shell: windows });
if (runtime.status !== 0) {
  console.error("The in-game runtime (game-runtime/) failed to build.");
  process.exit(runtime.status ?? 1);
}
// The app embeds the jar of its own version (src-tauri/build.rs); without it the build would quietly ship
// without Flint's in-game menu, so a missing jar stops the release.
const version = JSON.parse(readFileSync("package.json", "utf8")).version;
const jar = `../game-runtime/build/libs/flint-game-runtime-${version}.jar`;
if (!existsSync(jar)) {
  console.error(`The in-game runtime for ${version} is missing (${jar}).`);
  process.exit(1);
}

// Signed update archives (the files installed copies update from) need the updater signing key
// (docs/UPDATE_SYSTEM.md). Without it, for example on a contributor's machine or in CI checks, the build
// still makes a working app and installer, just no update archive.
const signing = Boolean(process.env.TAURI_SIGNING_PRIVATE_KEY);
if (!signing) console.log("No updater signing key (TAURI_SIGNING_PRIVATE_KEY): building without update archives.");

// `npm run release -- --bundles app,dmg` picks the bundle types (default: the .app on macOS, the NSIS
// installer on Windows).
const extra = process.argv.slice(2);
const bundles = extra.includes("--bundles") ? [] : ["--bundles", windows ? "nsis" : "app"];
// The build number goes into the macOS bundle version (Windows installers take the marketing version).
const config = windows ? [] : ["--config", JSON.stringify({ bundle: { macOS: { bundleVersion: build } } })];
if (!signing) {
  // A file (relative, inside the ignored build folder) rather than inline JSON, which Windows' shell would mangle.
  mkdirSync("src-tauri/target.noindex", { recursive: true });
  const unsigned = "src-tauri/target.noindex/flint-unsigned-build.json";
  writeFileSync(unsigned, JSON.stringify({ bundle: { createUpdaterArtifacts: false } }));
  config.push("--config", unsigned);
}
const result = spawnSync(
  windows ? "npx.cmd" : "npx",
  ["tauri", "build", ...bundles, ...config, ...extra],
  {
    shell: windows,
    stdio: "inherit",
    env: {
      ...process.env,
      FLINT_BUILD: build,
      FLINT_COMMIT: commit,
      // Source paths compiled into the binary (panic locations of dependencies) name the build machine's home
      // folder; map it to "~" so a release never carries the builder's user name. Same on macOS and Windows.
      RUSTFLAGS: [process.env.RUSTFLAGS, `--remap-path-prefix=${homedir()}=~`].filter(Boolean).join(" "),
    },
  },
);
if (result.status === 0 && !windows) reportSigning();
process.exit(result.status ?? 1);

// Prints how the built app is signed. macOS ties Keychain access to the designated requirement: an
// ad-hoc build's requirement is its own code hash, so each new build asks for the Keychain once.
function reportSigning() {
  const appPath = "src-tauri/target.noindex/release/bundle/macos/Flint Client.app";
  const run = (...args) => spawnSync("codesign", args, { encoding: "utf8" });
  const details = run("-dvv", appPath).stderr ?? "";
  const requirement = (run("-d", "-r-", appPath).stdout ?? "").replace(/^#\s*/m, "").trim();
  const field = (name) => details.match(new RegExp(`^${name}=(.*)$`, "m"))?.[1] ?? "not set";
  console.log(`\nSigning: ${field("Signature") === "adhoc" ? "ad-hoc" : field("Authority")} · team ${field("TeamIdentifier")}`);
  console.log(`Designated requirement: ${requirement || "unknown"}`);
  if (/cdhash/.test(requirement)) {
    console.log("Note: this build's identity is its code hash, so macOS asks once for Keychain access after installing it.");
    console.log("Set APPLE_SIGNING_IDENTITY to an Apple-issued certificate for an identity that stays the same across builds.");
  }
}
