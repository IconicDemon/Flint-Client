#!/usr/bin/env node
// Builds a release app with an identifiable build number.
//
//   npm run release                       (from app/; the .app only)
//   npm run release -- --bundles app,dmg  (also the installer disk image)
//
// Every build gets a build number from the UTC time it was made (YYYY.MMDD.HHMM), which always
// increases and fits macOS's CFBundleVersion format (up to three integers). The marketing version
// stays in tauri.conf.json. The app shows both, plus the git commit, in Settings → About Flint.
// It builds game-runtime/ first (the in-game menu jars the app embeds, one per Minecraft line).
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

// Flint's in-game runtime (game-runtime/) is embedded in the app, so it is built first, once per Minecraft
// line it supports (26.3 is the main build; src-tauri/build.rs's RUNTIME_LINES lists them all); the release
// fails rather than ship an app that silently lacks one. (Windows runs the batch wrappers through its shell.)
const version = JSON.parse(readFileSync("package.json", "utf8")).version;
// Each line with the Minecraft version it compiles against (one 26.1 jar, built against 26.1.2, serves every 26.1 release;
// the obfuscated 1.21.x lines are game-runtime/legacy's build, 1.8.9 is game-runtime/legacy189's own code base). 26.3 comes first: its clean empties game-runtime/build.
for (const [line, minecraft] of [["26.3", "26.3"], ["26.2", "26.2"], ["26.1", "26.1.2"], ["1.21.11", "1.21.11"], ["1.21.10", "1.21.10"], ["1.21.1", "1.21.1"], ["1.8.9", "1.8.9"]]) {
  const main = line === "26.3";
  const project = minecraft === "1.8.9" ? ["-p", "legacy189"] : minecraft.startsWith("1.") ? ["-p", "legacy"] : [];
  // A clean build each time: an incremental one can keep classes of deleted sources in the jar.
  const gradle = [...project, "clean", "build", "--quiet", ...(main || minecraft === "1.8.9" ? [] : [`-Pminecraft_version=${minecraft}`])];
  const runtime = spawnSync(windows ? ".\\gradlew.bat" : "./gradlew", gradle, { cwd: "../game-runtime", stdio: "inherit", shell: windows });
  if (runtime.status !== 0) {
    console.error(`The in-game runtime (game-runtime/) failed to build for Minecraft ${line}.`);
    process.exit(runtime.status ?? 1);
  }
  // The app embeds the jars of its own version (src-tauri/build.rs); without one the build would quietly ship
  // without Flint's in-game menu on that line, so a missing jar stops the release.
  const jar = main
    ? `../game-runtime/build/libs/flint-game-runtime-${version}.jar`
    : `../game-runtime/build/mc-${line}/libs/flint-game-runtime-${version}+${line}.jar`;
  if (!existsSync(jar)) {
    console.error(`The in-game runtime ${version} for Minecraft ${line} is missing (${jar}).`);
    process.exit(1);
  }
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
// A staging test build (FLINT_CHANNEL=staging; src-tauri/src/channel.rs) installs beside the real Flint as "Flint Client
// Staging", with its own data folder, sign-ins and update channel (one nothing is published to), and no .flintpack files.
// build.rs refuses it without every staging address.
if (process.env.FLINT_CHANNEL === "staging") {
  console.log(`STAGING build: Flint's staging project and Store (${process.env.FLINT_STORE_URL ?? "no FLINT_STORE_URL"}), installed as "Flint Client Staging".`);
  config.push("--config", "src-tauri/tauri.staging.conf.json");
}
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
if (result.status === 0 && process.platform === "darwin" && !extra.includes("--target")) reportSigning();
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
