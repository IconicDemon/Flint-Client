#!/usr/bin/env node
// Writes the update manifest (latest.json) that installed copies of Flint read to find a newer version.
//
//   node scripts/updater-manifest.mjs --version 0.5.1 --notes-file notes.md --out latest.json \
//     --platform darwin-aarch64=https://…/Flint_Client_0.5.1_aarch64.app.tar.gz,path/to/Flint\ Client.app.tar.gz.sig
//
// Each --platform names a Tauri target (darwin-aarch64, darwin-x86_64, windows-x86_64), the HTTPS URL its
// update archive will be downloaded from, and the .sig file the build wrote for that archive. Only platforms
// passed here are listed, so a platform that was not built and tested is never offered an update.
// --merge latest.json keeps the other platforms of an existing manifest for the same version (a Windows
// build published after the macOS one). --allow-http is for local update tests only.
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const TARGETS = new Set(["darwin-aarch64", "darwin-x86_64", "windows-x86_64", "windows-aarch64", "linux-x86_64"]);
const SEMVER = /^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$/;

function fail(message) {
  console.error(`updater-manifest: ${message}`);
  process.exit(1);
}

const args = process.argv.slice(2);
const options = { platforms: [] };
for (let i = 0; i < args.length; i++) {
  const flag = args[i];
  const value = () => args[++i] ?? fail(`${flag} needs a value`);
  if (flag === "--version") options.version = value();
  else if (flag === "--notes") options.notes = value();
  else if (flag === "--notes-file") options.notes = readFileSync(value(), "utf8").trim();
  else if (flag === "--pub-date") options.pubDate = value();
  else if (flag === "--out") options.out = value();
  else if (flag === "--merge") options.merge = value();
  else if (flag === "--platform") options.platforms.push(value());
  else if (flag === "--allow-http") options.allowHttp = true;
  else fail(`unknown option ${flag}`);
}

if (!options.version || !SEMVER.test(options.version)) fail("--version must be a semantic version such as 0.5.1");
if (!options.out) fail("--out is required");
if (options.platforms.length === 0) fail("at least one --platform is required");

const pubDate = options.pubDate ?? new Date().toISOString().replace(/\.\d{3}Z$/, "Z");
if (Number.isNaN(Date.parse(pubDate))) fail("--pub-date must be an RFC 3339 date");

let platforms = {};
if (options.merge && existsSync(options.merge)) {
  const existing = JSON.parse(readFileSync(options.merge, "utf8"));
  // Mixing versions would offer some computers a different build than others; start fresh instead.
  if (existing.version === options.version) platforms = existing.platforms ?? {};
}

for (const entry of options.platforms) {
  const match = /^([a-z0-9_-]+)=([^,]+),(.+)$/.exec(entry);
  if (!match) fail(`--platform ${entry}: expected target=url,signature-file`);
  const [, target, url, sigFile] = match;
  if (!TARGETS.has(target)) fail(`unknown target ${target}`);
  if (!url.startsWith("https://") && !(options.allowHttp && url.startsWith("http://"))) fail(`${target}: the URL must use HTTPS`);
  if (!existsSync(sigFile)) fail(`${target}: signature file ${sigFile} not found (was the build signed?)`);
  const signature = readFileSync(sigFile, "utf8").trim();
  if (!signature) fail(`${target}: signature file ${sigFile} is empty`);
  platforms[target] = { signature, url };
}

const manifest = { version: options.version, notes: options.notes ?? "", pub_date: pubDate, platforms };
writeFileSync(options.out, `${JSON.stringify(manifest, null, 2)}\n`);
console.log(`Wrote ${options.out}: Flint ${options.version} for ${Object.keys(platforms).sort().join(", ")}`);
