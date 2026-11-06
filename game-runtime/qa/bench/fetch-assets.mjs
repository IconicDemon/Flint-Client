// Dev only: fetches what the benchmark runs need into %USERPROFILE%\FlintQA\bench (never the owner's profiles):
// - per Minecraft version, Flint's performance set exactly as the app installs it (content.rs PERFORMANCE_SET, picked
//   like modrinth.rs pick_version: the newest release, else beta, else alpha, plus required dependencies), and the same
//   set with Iris for the shader runs;
// - resource packs (a 32x pack and a demanding 128x pack) and a shader pack, per version where their authors list one.
// Every file is checked against Modrinth's SHA-512 before it's kept.
//
// usage: node game-runtime/qa/bench/fetch-assets.mjs [VERSION...]
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const ROOT = path.join(os.homedir(), "FlintQA", "bench");
const API = "https://api.modrinth.com/v2";
const UA = "flintclient/benchmark (dev tooling)";
const MODERN = ["26.3", "26.2", "26.1.2", "26.1.1", "26.1", "1.21.11", "1.21.10", "1.21.1"];
const VERSIONS = process.argv.length > 2 ? process.argv.slice(2) : [...MODERN, "1.8.9"];
const PERFORMANCE_SET = ["sodium", "lithium", "ferrite-core", "immediatelyfast", "entityculling", "dynamic-fps"];
// Demanding: a complete 128x pack (every texture 64 times the pixels of vanilla); Faithful 64x where it has no build.
const PACKS = { normal: "faithful-32x", demanding: ["default-hd-128x", "faithful-64x"] };
const SHADER = "complementary-reimagined";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function get(url) {
	for (let attempt = 0; ; attempt++) {
		const res = await fetch(url, { headers: { "User-Agent": UA } });
		if (res.status === 429 && attempt < 5) {
			await sleep(2000 * (attempt + 1));
			continue;
		}
		if (!res.ok) throw new Error(`${res.status} ${url}`);
		return res.json();
	}
}

const pick = (list) => list.find((v) => v.version_type === "release") ?? list.find((v) => v.version_type === "beta") ?? list[0] ?? null;

async function versionsOf(project, loaders, gameVersion) {
	const q = new URLSearchParams();
	if (loaders) q.set("loaders", JSON.stringify(loaders));
	q.set("game_versions", JSON.stringify([gameVersion]));
	return get(`${API}/project/${project}/version?${q}`);
}

async function download(version, dir) {
	const file = version.files.find((f) => f.primary) ?? version.files[0];
	const target = path.join(dir, file.filename);
	if (fs.existsSync(target) && crypto.createHash("sha512").update(fs.readFileSync(target)).digest("hex") === file.hashes.sha512) return file.filename;
	const res = await fetch(file.url, { headers: { "User-Agent": UA } });
	if (!res.ok) throw new Error(`${res.status} ${file.url}`);
	const bytes = Buffer.from(await res.arrayBuffer());
	const hash = crypto.createHash("sha512").update(bytes).digest("hex");
	if (hash !== file.hashes.sha512) throw new Error(`hash mismatch: ${file.filename}`);
	fs.mkdirSync(dir, { recursive: true });
	fs.writeFileSync(target, bytes);
	return file.filename;
}

/** A mod set for one version: each project's picked build and the required dependencies they bring, once each. */
async function modSet(projects, gameVersion, dir) {
	const done = new Map();
	const queue = [...projects];
	while (queue.length) {
		const project = queue.shift();
		if (done.has(project)) continue;
		const version = pick(await versionsOf(project, ["fabric"], gameVersion));
		if (!version) {
			done.set(project, null);
			continue;
		}
		done.set(project, version.project_id);
		done.set(version.project_id, version.project_id);
		const name = await download(version, dir);
		console.log(`  ${gameVersion} ${project}: ${version.version_number} (${version.version_type}) -> ${name}`);
		for (const dep of version.dependencies ?? []) if (dep.dependency_type === "required" && dep.project_id && !done.has(dep.project_id)) queue.push(dep.project_id);
	}
	return [...done.entries()].filter(([k, v]) => v === null && projects.includes(k)).map(([k]) => k);
}

async function pack(project, gameVersion, dir) {
	if (!project) return null;
	const version = pick(await versionsOf(project, null, gameVersion));
	if (!version) return null;
	const name = await download(version, dir);
	console.log(`  ${gameVersion} pack ${project}: ${version.version_number} -> ${name}`);
	return name;
}

const manifest = fs.existsSync(path.join(ROOT, "assets.json")) ? JSON.parse(fs.readFileSync(path.join(ROOT, "assets.json"), "utf8")) : {};
for (const v of VERSIONS) {
	const entry = { version: v };
	if (MODERN.includes(v)) {
		entry.perfMissing = await modSet(PERFORMANCE_SET, v, path.join(ROOT, v, "perf"));
		entry.irisMissing = await modSet([...PERFORMANCE_SET, "iris"], v, path.join(ROOT, v, "iris"));
		entry.shader = await pack(SHADER, v, path.join(ROOT, v, "shaderpacks"));
	}
	const packs = path.join(ROOT, v, "resourcepacks");
	entry.normalPack = await pack(PACKS.normal, v, packs);
	for (const project of PACKS.demanding) {
		entry.demandingPack = await pack(project, v, packs);
		entry.demandingProject = project;
		if (entry.demandingPack) break;
	}
	manifest[v] = entry;
	fs.writeFileSync(path.join(ROOT, "assets.json"), JSON.stringify(manifest, null, 2));
}
console.log(JSON.stringify(manifest, null, 2));
