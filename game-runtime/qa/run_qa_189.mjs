// Dev-only (Windows): runs one scripted QA tour of Flint inside a real Minecraft 1.8.9, in an isolated game folder: the
// 1.8.9 counterpart of run_qa_windows.mjs. It launches the way the Flint app does for 1.8.9: Mojang's own 1.8.9 (its
// libraries, LWJGL 2 natives, assets and Java 8, jre-legacy) with the official Fabric Loader from Ornithe's meta (its
// launcher profile for 1.8.9: Calamus intermediary gen 2 and Fabric's libraries, which win over vanilla's of the same key).
// Like the app, it runs Flint's 1.8.9 on Mojang's Java 21 (java-runtime-delta); --java 8 runs Mojang's Java 8 (jre-legacy),
// as --no-runtime baselines do by default.
//
// usage: node qa/run_qa_189.mjs SCRIPT RUN [--game NAME] [--reuse] [--size WxH] [--gui N] [--timeout S] [--front]
//        [--no-runtime] [--mods DIR] [--options K:V,K:V] [--java 8|21]
//
// - Build first (from game-runtime): ./gradlew -p legacy189 build && ./gradlew -p qa/legacy189 build
// - Game folders and results live in %USERPROFILE%\FlintQA (games\<game>, runs\<run>), never in the owner's profiles; the
//   game's files come from %USERPROFILE%\FlintQA\mc-cache, every one checked against its publisher's sha1 (Mojang's version
//   file and manifest; Fabric's and Ornithe's mavens publish a .sha1 beside each jar the profile lists without one).
// - Mods: Flint's 1.8.9 runtime (build/mc-1.8.9/libs) and the QA tour (qa/build/mc-1.8.9/libs); --no-runtime leaves Flint
//   out (the baseline for FPS comparisons); --mods adds every jar in DIR.
// - Only one game runs at a time on this machine: the runner creates %USERPROFILE%\FlintQA\game.lock exclusively before
//   launching and deletes it when the game exits. A lock younger than 15 minutes means another game is running: wait and
//   retry. An older one is stale and is taken over.
// - Offline QA identity ("FlintQA", access token 0). Muted. The window is kept behind the owner's windows unless --front.
// Prints every "QA PASS/FAIL/FPS" line and the summary; exit code 0 when the tour finished with no failures.
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { aotPlan, buildCache } from "./aot_cache.mjs";
import { cachedJavaRuntime, fetchBytes, download, resolve, rulesAllow, mavenPath, libraryKey, sha1Matches, sha1Of } from "./mc_cache.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, "..", "..");
// The installed app's data ("Flint Client"; older versions of Flint kept it in "com.flintclient.desktop").
const APP_DATA = ["Flint Client", "com.flintclient.desktop"].map((name) => path.join(process.env.APPDATA, name)).find((dir) => fs.existsSync(dir))
	?? path.join(process.env.APPDATA, "Flint Client");
const GAME_ROOT = path.join(APP_DATA, "minecraft");
const QA_ROOT = path.join(os.homedir(), "FlintQA");
const CACHE_ROOT = path.join(QA_ROOT, "mc-cache");
const LOCK = path.join(QA_ROOT, "game.lock");
const LOCK_STALE_MS = 15 * 60 * 1000;
const VERSION = JSON.parse(fs.readFileSync(path.join(REPO, "app", "package.json"), "utf8")).version;
const MC = "1.8.9";
const LOADER = "0.20.1";
const MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const ORNITHE_PROFILE = `https://meta.ornithemc.net/v3/versions/fabric-loader/${MC}/${LOADER}/profile/json`;
const ASSETS = "https://resources.download.minecraft.net";
const RUNTIME_JAR = path.join(REPO, "game-runtime", "build", `mc-${MC}`, "libs", `flint-game-runtime-${VERSION}+${MC}.jar`);
const QA_JAR = path.join(HERE, "build", `mc-${MC}`, "libs", `flint-qa-1.0.0+${MC}.jar`);
const BACKGROUND = path.join(HERE, "tools", "background.ps1");
const TAR = path.join(process.env.SystemRoot, "System32", "tar.exe");
// 1.8.9's options.txt keys. 260 frames is "Unlimited"; the master volume 0 mutes the game.
const FRESH_OPTIONS = ["fullscreen:false", "pauseOnLostFocus:false", "soundCategory_master:0.0", "renderDistance:8", "enableVsync:false",
	"maxFps:260", "snooperEnabled:false", "lang:en_US", "guiScale:2", "fboEnable:true", "useVbo:true"];

const log = (message) => console.log("[qa] " + message);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function parseArgs(argv) {
	const args = { positional: [], timeout: 600 };
	for (let i = 0; i < argv.length; i++) {
		const a = argv[i];
		if (a === "--reuse") args.reuse = true;
		else if (a === "--front") args.front = true;
		else if (a === "--no-runtime") args.noRuntime = true;
		else if (["--game", "--size", "--gui", "--timeout", "--mods", "--options", "--java"].includes(a)) args[a.slice(2)] = argv[++i];
		else args.positional.push(a);
	}
	if (args.positional.length !== 2) throw new Error("usage: run_qa_189.mjs SCRIPT RUN [--game NAME] [--reuse] [--size WxH] [--gui N] [--timeout S] [--front] [--no-runtime] [--mods DIR] [--options K:V,K:V] [--java 8|21]");
	if (args.java !== undefined && !["8", "21", "25"].includes(args.java)) throw new Error("--java takes 8, 21 or 25");
	return args;
}

// ---------- the one-game-at-a-time lock ----------

let lockHeld = false;

async function acquireLock(run) {
	fs.mkdirSync(QA_ROOT, { recursive: true });
	const mine = JSON.stringify({ pid: process.pid, run, at: new Date().toISOString() }) + "\n";
	for (;;) {
		try {
			const fd = fs.openSync(LOCK, "wx");
			fs.writeSync(fd, mine);
			fs.closeSync(fd);
			lockHeld = true;
			return;
		} catch (e) {
			if (e.code !== "EEXIST") throw e;
		}
		let age;
		try {
			age = Date.now() - fs.statSync(LOCK).mtimeMs;
		} catch {
			continue; // removed between the two calls: try again at once
		}
		if (age > LOCK_STALE_MS) {
			log(`taking over a stale game lock (${Math.round(age / 60000)} min old)`);
			fs.writeFileSync(LOCK, mine);
			lockHeld = true;
			return;
		}
		log(`another game holds ${LOCK} (${Math.round(age / 1000)} s old): waiting`);
		await sleep(20000);
	}
}

function releaseLock() {
	if (!lockHeld) return;
	lockHeld = false;
	try {
		if (fs.readFileSync(LOCK, "utf8").includes(`"pid":${process.pid},`)) fs.rmSync(LOCK);
	} catch {
		// Already gone.
	}
}

// ---------- the game's files ----------

/** Fills in the sha1 of a profile library that lists none, from the .sha1 its maven publishes beside the jar. */
async function librarySha1(lib, url) {
	if (lib.sha1) return lib.sha1;
	const sha1 = (await fetchBytes(url + ".sha1")).toString("utf8").trim().split(/\s+/)[0];
	if (!/^[0-9a-f]{40}$/.test(sha1)) throw new Error(`no sha1 for ${lib.name}`);
	return sha1;
}

// Mojang's Java runtimes by major version: the app gives Flint's 1.8.9 Java 21 (app/src-tauri/src/runtime.rs JAVA_FOR_LINE).
const JAVA_COMPONENTS = { 8: "jre-legacy", 21: "java-runtime-delta", 25: "java-runtime-epsilon" };

async function gameFiles(javaMajor) {
	const roots = { launcher: GAME_ROOT, cache: CACHE_ROOT };
	const versionRel = path.join("versions", MC, `${MC}.json`);
	const versionFile = path.join(CACHE_ROOT, versionRel);
	if (!fs.existsSync(versionFile)) {
		const manifest = JSON.parse(await fetchBytes(MANIFEST));
		const entry = manifest.versions.find((v) => v.id === MC);
		const found = await resolve(versionRel, entry.sha1, entry.url, roots, log);
		if (found !== versionFile) {
			fs.mkdirSync(path.dirname(versionFile), { recursive: true });
			fs.copyFileSync(found, versionFile);
		}
	}
	const vanilla = JSON.parse(fs.readFileSync(versionFile, "utf8"));

	const profileFile = path.join(CACHE_ROOT, "versions", `fabric-loader-${LOADER}-${MC}-ornithe-gen2`, "profile.json");
	if (!fs.existsSync(profileFile)) {
		log(`downloading Ornithe's Fabric profile for ${MC}`);
		await download(ORNITHE_PROFILE, profileFile, null);
	}
	const fabric = JSON.parse(fs.readFileSync(profileFile, "utf8"));
	if (fabric.inheritsFrom !== `${MC}-vanilla` && fabric.inheritsFrom !== MC) throw new Error(`unexpected inheritsFrom ${fabric.inheritsFrom}`);

	const jars = [];
	const keys = new Set();
	for (const lib of fabric.libraries) {
		const rel = mavenPath(lib.name);
		const url = lib.url.replace(/\/?$/, "/") + rel;
		jars.push(await resolve(path.join("libraries", rel), await librarySha1(lib, url), url, roots, log));
		keys.add(libraryKey(lib.name));
	}
	const natives = [];
	for (const lib of vanilla.libraries) {
		if (!rulesAllow(lib.rules)) continue;
		const artifact = lib.downloads?.artifact;
		if (artifact?.path && !keys.has(libraryKey(lib.name))) {
			jars.push(await resolve(path.join("libraries", artifact.path), artifact.sha1, artifact.url, roots, log));
		}
		const classifier = lib.natives?.windows?.replace("${arch}", "64");
		const native = classifier && lib.downloads?.classifiers?.[classifier];
		if (native) natives.push(await resolve(path.join("libraries", native.path), native.sha1, native.url, roots, log));
	}
	const client = vanilla.downloads.client;
	const clientJar = await resolve(path.join("versions", MC, `${MC}.jar`), client.sha1, client.url, roots, log);
	jars.push(clientJar);

	const assetsDir = path.join(CACHE_ROOT, "assets");
	const indexRel = path.join("assets", "indexes", `${vanilla.assetIndex.id}.json`);
	const indexFile = await resolve(indexRel, vanilla.assetIndex.sha1, vanilla.assetIndex.url, roots, log);
	if (!indexFile.startsWith(CACHE_ROOT)) {
		fs.mkdirSync(path.dirname(path.join(CACHE_ROOT, indexRel)), { recursive: true });
		fs.copyFileSync(indexFile, path.join(CACHE_ROOT, indexRel));
	}
	let fetched = 0;
	for (const { hash } of Object.values(JSON.parse(fs.readFileSync(indexFile, "utf8")).objects)) {
		const rel = path.join("objects", hash.slice(0, 2), hash);
		const dest = path.join(assetsDir, rel);
		if (fs.existsSync(dest)) continue;
		const theirs = path.join(GAME_ROOT, "assets", rel);
		if (sha1Matches(theirs, hash)) {
			fs.mkdirSync(path.dirname(dest), { recursive: true });
			fs.copyFileSync(theirs, dest + ".part");
			if (sha1Of(dest + ".part") !== hash) throw new Error(`asset copy damaged: ${rel}`);
			fs.renameSync(dest + ".part", dest);
		} else {
			await download(`${ASSETS}/${hash.slice(0, 2)}/${hash}`, dest, hash);
		}
		fetched++;
	}
	if (fetched) log(`assets ${vanilla.assetIndex.id}: ${fetched} added to the cache`);
	const java = await cachedJavaRuntime(JAVA_COMPONENTS[javaMajor],
		{ launcherRuntimes: path.join(APP_DATA, "runtimes"), cache: CACHE_ROOT }, log);
	return { vanilla, fabric, jars, natives, clientJar, assetsDir, java };
}

// ---------- the game folder ----------

function prepare(game, args, files) {
	const fresh = !fs.existsSync(game);
	if (!fresh && !args.reuse) throw new Error(`game folder exists: ${game} (use --reuse)`);
	const mods = path.join(game, "mods");
	fs.mkdirSync(mods, { recursive: true });
	for (const f of fs.readdirSync(mods)) fs.rmSync(path.join(mods, f));
	const jars = args.noRuntime ? [[QA_JAR, "flint-qa.jar"]] : [[RUNTIME_JAR, "flint-runtime.jar"], [QA_JAR, "flint-qa.jar"]];
	if (args.mods) {
		for (const f of fs.readdirSync(args.mods)) if (f.endsWith(".jar")) jars.push([path.join(args.mods, f), f]);
	}
	for (const [src, name] of jars) {
		if (!fs.existsSync(src)) throw new Error("missing jar: " + src);
		fs.copyFileSync(src, path.join(mods, name));
	}
	// LWJGL 2's natives, unpacked from their jars (META-INF left out), as the launcher does.
	const natives = path.join(game, "natives");
	fs.rmSync(natives, { recursive: true, force: true });
	fs.mkdirSync(natives, { recursive: true });
	for (const jar of files.natives) {
		const r = spawnSync(TAR, ["-xf", jar, "-C", natives, "--exclude", "META-INF"], { encoding: "utf8" });
		if (r.status !== 0) throw new Error(`couldn't unpack ${jar}: ${r.stderr}`);
	}
	const options = path.join(game, "options.txt");
	let lines = fs.existsSync(options) ? fs.readFileSync(options, "utf8").split(/\r?\n/).filter(Boolean) : [...FRESH_OPTIONS];
	const set = (key, value) => (lines = lines.filter((l) => !l.startsWith(key + ":")).concat(`${key}:${value}`));
	if (args.gui !== undefined) set("guiScale", args.gui);
	for (const kv of (args.options ?? "").split(",").filter(Boolean)) set(kv.slice(0, kv.indexOf(":")), kv.slice(kv.indexOf(":") + 1));
	fs.writeFileSync(options, lines.join("\n") + "\n");
	return natives;
}

function argv(game, natives, out, script, args, files) {
	const { vanilla, fabric } = files;
	const jvm = ["-Xmx2G", "-Xms512M", `-Djava.library.path=${natives}`, "-Dminecraft.launcher.brand=flint-qa", ...(fabric.arguments?.jvm ?? []),
		...(process.env.FLINTQA_JVM ? process.env.FLINTQA_JVM.split(" ").filter(Boolean) : []),
		`-Dflintqa.script=${script}`, `-Dflintqa.out=${out}`, "-cp", files.jars.join(";")];
	const game_ = ["--username", "FlintQA", "--version", fabric.id, "--gameDir", game, "--assetsDir", files.assetsDir,
		"--assetIndex", vanilla.assetIndex.id, "--uuid", "f1e7a00000004000800000000000f1a7", "--accessToken", "0", "--userProperties", "{}",
		"--userType", "legacy"];
	if (args.size) {
		const [w, h] = args.size.split("x");
		game_.push("--width", w, "--height", h);
	}
	return [files.java, [...jvm, fabric.mainClass, ...game_]];
}

async function main() {
	const args = parseArgs(process.argv.slice(2));
	const [scriptArg, run] = args.positional;
	const game = path.join(QA_ROOT, "games", args.game || run);
	const out = path.join(QA_ROOT, "runs", run);
	fs.rmSync(out, { recursive: true, force: true });
	fs.mkdirSync(out, { recursive: true });
	const javaMajor = Number(args.java ?? (args.noRuntime ? 8 : 21));
	const files = await gameFiles(javaMajor);
	log(`Java ${javaMajor} (${JAVA_COMPONENTS[javaMajor]})`);
	const natives = prepare(game, args, files);
	const script = path.resolve(scriptArg);
	fs.copyFileSync(script, path.join(out, "script.txt"));
	let [java, javaArgs] = argv(game, natives, out, script, args, files);
	// FLINTQA_AOT=DIR (Java 25): the start-up cache, as in run_qa_windows.mjs.
	const aot = process.env.FLINTQA_AOT ? aotPlan(process.env.FLINTQA_AOT, MC, javaArgs, files.clientJar) : null;
	if (aot) {
		javaArgs = aot.args;
		log("start-up cache: " + aot.note);
	}
	const logPath = path.join(out, "game.log");
	await acquireLock(run);
	for (const signal of ["SIGINT", "SIGTERM", "SIGBREAK"]) process.on(signal, () => (releaseLock(), process.exit(130)));
	process.on("exit", releaseLock);
	const logFd = fs.openSync(logPath, "w");
	log(`launching ${run} in ${game}`);
	const proc = spawn(java, javaArgs, { cwd: game, stdio: ["ignore", logFd, logFd], windowsHide: false });
	let exited = null;
	proc.on("exit", (code) => (exited = code));
	if (!args.front) {
		spawn("powershell", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", BACKGROUND, String(proc.pid)], { stdio: "ignore", windowsHide: true });
	}
	const start = Date.now();
	let result = "TIMEOUT";
	for (;;) {
		await sleep(500);
		if (exited !== null) {
			result = `EXITED ${exited}`;
			break;
		}
		if (Date.now() - start > args.timeout * 1000) {
			proc.kill();
			await sleep(1500);
			break;
		}
	}
	fs.closeSync(logFd);
	if (aot?.record && exited === 0) buildCache(java, aot, log);
	releaseLock();
	const lines = [...fs.readFileSync(logPath, "utf8").matchAll(/QA (.*?)(?:\]\]>|$)/gm)].map((m) => m[1]);
	fs.writeFileSync(path.join(out, "tour.log"), lines.join("\n") + "\n");
	const shots = path.join(out, "screenshots");
	log(`result ${result} after ${Math.round((Date.now() - start) / 1000)} s; screenshots: ${fs.existsSync(shots) ? fs.readdirSync(shots).length : 0}`);
	let failed = 0;
	let finished = false;
	for (const body of lines) {
		if (/^(PASS|FAIL|DONE|FPS)/.test(body)) log("  " + body);
		failed += body.startsWith("FAIL") ? 1 : 0;
		finished ||= body.startsWith("DONE");
	}
	process.exit(finished && failed === 0 ? 0 : 1);
}

main().catch((e) => {
	releaseLock();
	console.error("[qa] " + e.message);
	process.exit(2);
});
