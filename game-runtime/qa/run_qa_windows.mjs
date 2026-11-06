// Dev-only (Windows): runs one scripted QA tour of Flint inside a real Minecraft (26.3, or --mc VERSION), in an isolated game folder. The
// Windows counterpart of run_qa.py, launching the way the Flint app does (app/src-tauri/src/game.rs): the launcher's own
// libraries, assets and Java, read only; Fabric's libraries win over vanilla's of the same key; the client jar last.
//
// usage: node qa/run_qa_windows.mjs SCRIPT RUN [--game NAME] [--reuse] [--size WxH] [--gui N] [--timeout S] [--front] [--mods DIR]
//        [--no-runtime] [--mc VERSION]
//
// - Game folders and results live in %USERPROFILE%\FlintQA (games\<game>, runs\<run>), never in the owner's profiles.
// - Mods: exactly Flint's runtime jar (game-runtime/build/libs, the app's version) and the QA tour jar (qa/build/libs); with
//   --mods, every jar in DIR too (the performance set the app installs, say); with --no-runtime, no Flint runtime (a baseline).
// - --mc VERSION (default 26.3): the Minecraft the launcher has installed (versions\VERSION and fabric-loader-0.19.5-VERSION),
//   with that line's jars: game-runtime/build/mc-<line>/libs/flint-game-runtime-<app>+<line>.jar and
//   qa/build/mc-<line>/libs/flint-qa-1.0.0+<line>.jar (./gradlew build -Pminecraft_version=VERSION, and -p qa likewise).
//   Every 26.1 release uses the 26.1 line. A version whose client jar the launcher hasn't downloaded runs from
//   %USERPROFILE%\FlintQA\mc-cache instead, filled on first use with files checked against Mojang's and Fabric's sha1s
//   (qa/mc_cache.mjs); the launcher's own folder is only ever read.
// - Offline QA identity ("FlintQA", access token 0): never a real account.
// - Only one game runs at a time on this machine: as run_qa_189.mjs, the runner creates %USERPROFILE%\FlintQA\game.lock
//   exclusively before launching and deletes it when the game exits (a lock under 15 minutes old: wait; older: stale).
// - The game window is kept behind the owner's windows (qa/tools/background.ps1) unless --front: it never takes the
//   mouse or the keyboard. It isn't minimised, because Minecraft can't draw (or screenshot) a minimised window.
// - A tour step "await-key NAME KEY" (rshift, lshift or a letter) makes this runner post a real KEY press (qa/tools/keypost.ps1), then
//   answer by writing runs\<run>\key-NAME.done.
// Prints every "QA PASS/FAIL" line and the summary; exit code 0 when the tour finished with no failures.
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { cachedGame, cachedJavaRuntime } from "./mc_cache.mjs";
import { aotPlan, buildCache } from "./aot_cache.mjs";

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
const MC = (() => {
	const i = process.argv.indexOf("--mc");
	return i > 0 ? process.argv[i + 1] : "26.3";
})();
// The runtime's build line for MC (one build serves every 26.1 release); 26.3's jars keep their plain names. --line LINE
// runs another line's jars on MC instead (the newest line on a newer Minecraft, as the launcher does before Flint has a build
// for it: --mc 26.4-snapshot-6 --line 26.3).
const LINE = (() => {
	const i = process.argv.indexOf("--line");
	return i > 0 ? process.argv[i + 1] : MC.startsWith("26.1") ? "26.1" : MC;
})();
const RUNTIME_JAR = LINE === "26.3"
	? path.join(REPO, "game-runtime", "build", "libs", `flint-game-runtime-${VERSION}.jar`)
	: path.join(REPO, "game-runtime", "build", `mc-${LINE}`, "libs", `flint-game-runtime-${VERSION}+${LINE}.jar`);
const QA_JAR = LINE === "26.3"
	? path.join(HERE, "build", "libs", "flint-qa-1.0.0.jar")
	: path.join(HERE, "build", `mc-${LINE}`, "libs", `flint-qa-1.0.0+${LINE}.jar`);
const KEYPOST = path.join(HERE, "tools", "keypost.ps1");
const BACKGROUND = path.join(HERE, "tools", "background.ps1");
const LOADER = "0.19.5";
const FABRIC = `fabric-loader-${LOADER}-${MC}`;
const FRESH_OPTIONS = ["fullscreen:false", "pauseOnLostFocus:false", "onboardAccessibility:false", "tutorialStep:none",
	"soundCategory_master:0.0", "skipMultiplayerWarning:true", "joinedFirstServer:true", "narrator:0", "renderDistance:6", "simulationDistance:6"];

function parseArgs(argv) {
	const args = { positional: [], timeout: 900 };
	for (let i = 0; i < argv.length; i++) {
		const a = argv[i];
		if (a === "--reuse") args.reuse = true;
		else if (a === "--front") args.front = true;
		else if (a === "--no-runtime") args.noRuntime = true;
		else if (["--game", "--size", "--gui", "--timeout", "--mods", "--mc", "--line"].includes(a)) args[a.slice(2)] = argv[++i];
		else args.positional.push(a);
	}
	if (args.positional.length !== 2) throw new Error("usage: run_qa_windows.mjs SCRIPT RUN [--game NAME] [--reuse] [--size WxH] [--gui N] [--timeout S] [--front] [--mods DIR] [--no-runtime] [--mc VERSION]");
	return args;
}

const ruleMatches = (rule) => !rule.features && (!rule.os || ((!rule.os.name || rule.os.name === "windows") && (!rule.os.arch || rule.os.arch === "x86_64")));
function rulesAllow(rules) {
	if (!rules) return true;
	let allowed = false;
	for (const rule of rules) if (ruleMatches(rule)) allowed = rule.action === "allow";
	return allowed;
}
function mavenPath(name) {
	const [coords, ext = "jar"] = name.split("@");
	const [group, artifact, version, classifier] = coords.split(":");
	return `${group.replaceAll(".", "/")}/${artifact}/${version}/${artifact}-${version}${classifier ? "-" + classifier : ""}.${ext}`;
}
function libraryKey(name) {
	const p = name.split(":");
	return p.length >= 4 ? `${p[0]}:${p[1]}:${p[3]}` : `${p[0]}:${p[1]}`;
}

function classpath(vanilla, fabric) {
	const libRoot = path.join(GAME_ROOT, "libraries");
	const out = [];
	const keys = new Set();
	for (const lib of fabric.libraries) {
		out.push(path.join(libRoot, mavenPath(lib.name)));
		keys.add(libraryKey(lib.name));
	}
	for (const lib of vanilla.libraries) {
		const artifact = lib.downloads?.artifact;
		if (!rulesAllow(lib.rules) || !artifact?.path || keys.has(libraryKey(lib.name))) continue;
		out.push(path.join(libRoot, artifact.path));
	}
	out.push(path.join(GAME_ROOT, "versions", MC, `${MC}.jar`));
	for (const jar of out) if (!fs.existsSync(jar)) throw new Error(`missing library (launch ${MC} once from Flint first): ` + jar);
	return out;
}

// The game's files: the launcher's own (read only) when it has this version's client, else the QA cache's.
async function gameFiles() {
	const clientJar = path.join(GAME_ROOT, "versions", MC, `${MC}.jar`);
	const profile = path.join(GAME_ROOT, "versions", FABRIC, "profile.json");
	if (fs.existsSync(clientJar) && fs.existsSync(profile)) {
		const vanilla = JSON.parse(fs.readFileSync(path.join(GAME_ROOT, "versions", MC, `${MC}.json`), "utf8"));
		const fabric = JSON.parse(fs.readFileSync(profile, "utf8"));
		return { vanilla, fabric, jars: classpath(vanilla, fabric), clientJar, assetsDir: path.join(GAME_ROOT, "assets") };
	}
	console.log(`[qa] the launcher has no Minecraft ${MC} client: using ${CACHE_ROOT}`);
	return cachedGame(MC, LOADER, { launcher: GAME_ROOT, cache: CACHE_ROOT }, (message) => console.log("[qa] " + message));
}

// The client jar's data version, which a fresh options.txt is written at (so Minecraft doesn't upgrade it as an old one).
function dataVersion(jar) {
	const r = spawnSync(path.join(process.env.SystemRoot, "System32", "tar.exe"), ["-xOf", jar, "version.json"], { encoding: "utf8" });
	const version = r.stdout?.match(/"world_version":\s*(\d+)/)?.[1];
	if (!version) throw new Error(`couldn't read the data version of ${jar} (launch ${MC} once from Flint first)`);
	return version;
}

// The Java runtime the version asks for (its javaVersion component: java-runtime-epsilon, Java 25, for 26.x;
// java-runtime-delta, Java 21, for 1.21.x): the launcher's (read only), else the QA cache's (see mc_cache.mjs).
function javaBinary(vanilla) {
	// FLINTQA_JAVA_BIN: one exact java.exe instead (comparing Java builds, e.g. Mojang's 25.0.1 against a newer 25).
	if (process.env.FLINTQA_JAVA_BIN) return process.env.FLINTQA_JAVA_BIN;
	// FLINTQA_JAVA_COMPONENT: run on another of Mojang's Java runtimes than the version's own (as the launcher does for some
	// lines, app/src-tauri/src/runtime.rs JAVA_FOR_LINE), e.g. java-runtime-epsilon (Java 25).
	const component = process.env.FLINTQA_JAVA_COMPONENT || (vanilla.javaVersion?.component ?? "java-runtime-epsilon");
	return cachedJavaRuntime(component, { launcherRuntimes: path.join(APP_DATA, "runtimes"), cache: CACHE_ROOT }, (message) => console.log("[qa] " + message));
}

function prepare(game, args, files) {
	const fresh = !fs.existsSync(game);
	if (!fresh && !args.reuse) throw new Error(`game folder exists: ${game} (use --reuse)`);
	const mods = path.join(game, "mods");
	fs.mkdirSync(mods, { recursive: true });
	for (const f of fs.readdirSync(mods)) fs.rmSync(path.join(mods, f));
	const jars = args.noRuntime ? [[QA_JAR, "flint-qa.jar"]] : [[RUNTIME_JAR, "flint-runtime.jar"], [QA_JAR, "flint-qa.jar"]];
	if (args.mods) {
		for (const f of fs.readdirSync(args.mods)) {
			if (f.endsWith(".jar")) jars.push([path.join(args.mods, f), f]);
		}
	}
	for (const [src, name] of jars) {
		if (!fs.existsSync(src)) throw new Error("missing jar: " + src);
		fs.copyFileSync(src, path.join(mods, name));
	}
	const options = path.join(game, "options.txt");
	let lines = fs.existsSync(options) ? fs.readFileSync(options, "utf8").split(/\r?\n/).filter(Boolean) : [`version:${dataVersion(files.clientJar)}`, ...FRESH_OPTIONS];
	if (args.gui !== undefined) lines = lines.filter((l) => !l.startsWith("guiScale:")).concat(`guiScale:${args.gui}`);
	fs.writeFileSync(options, lines.join("\n") + "\n");
}

function argv(game, out, script, args, files) {
	const { vanilla, fabric } = files;
	const natives = path.join(game, "natives");
	for (const sub of ["java", "jna", "lwjgl", "netty"]) fs.mkdirSync(path.join(natives, sub), { recursive: true });
	const jvm = ["-Xmx3G", "-XX:StackShadowPages=32", "--enable-native-access=ALL-UNNAMED", "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
		`-Djava.library.path=${path.join(natives, "java")}`, `-Djna.tmpdir=${path.join(natives, "jna")}`,
		`-Dorg.lwjgl.system.SharedLibraryExtractPath=${path.join(natives, "lwjgl")}`, `-Dio.netty.native.workdir=${path.join(natives, "netty")}`,
		"-Dminecraft.launcher.brand=flint-qa", ...fabric.arguments.jvm,
		// FLINTQA_JVM: extra JVM flags for this run (a profiler recording, say), split on spaces.
		...(process.env.FLINTQA_JVM ? process.env.FLINTQA_JVM.split(" ").filter(Boolean) : []), `-Dflintqa.script=${script}`, `-Dflintqa.out=${out}`,
		"-cp", files.jars.join(";")];
	const game_ = ["--username", "FlintQA", "--version", FABRIC, "--gameDir", game, "--assetsDir", files.assetsDir,
		"--assetIndex", vanilla.assetIndex.id, "--uuid", "f1e7a000-0000-4000-8000-00000000f1a7", "--accessToken", "0", "--clientId", "0",
		"--xuid", "0", "--versionType", "release"];
	if (args.size) {
		const [w, h] = args.size.split("x");
		game_.push("--width", w, "--height", h);
	}
	return [files.java, [...jvm, fabric.mainClass, ...game_]];
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- the one-game-at-a-time lock (as run_qa_189.mjs) ----------

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
			console.log(`[qa] taking over a stale game lock (${Math.round(age / 60000)} min old)`);
			fs.writeFileSync(LOCK, mine);
			lockHeld = true;
			return;
		}
		console.log(`[qa] another game holds ${LOCK} (${Math.round(age / 1000)} s old): waiting`);
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

async function main() {
	const args = parseArgs(process.argv.slice(2));
	const [scriptArg, run] = args.positional;
	const game = path.join(QA_ROOT, "games", args.game || run);
	const out = path.join(QA_ROOT, "runs", run);
	fs.rmSync(out, { recursive: true, force: true });
	fs.mkdirSync(out, { recursive: true });
	const files = await gameFiles();
	files.java = await javaBinary(files.vanilla);
	prepare(game, args, files);
	const script = path.resolve(scriptArg);
	fs.copyFileSync(script, path.join(out, "script.txt"));
	let [java, javaArgs] = argv(game, out, script, args, files);
	// FLINTQA_AOT=DIR: Java 25's start-up cache as the launcher uses it (app/src-tauri/src/jvmcache.rs), one per Java and
	// Minecraft: the client jar becomes an unsigned copy; without a cache this launch records, and the cache is built after it
	// exits; with one, the launch starts from it.
	const aot = process.env.FLINTQA_AOT ? aotPlan(process.env.FLINTQA_AOT, MC, javaArgs, files.clientJar) : null;
	if (aot) {
		javaArgs = aot.args;
		console.log("[qa] start-up cache: " + aot.note);
	}
	await acquireLock(run);
	for (const signal of ["SIGINT", "SIGTERM", "SIGBREAK"]) process.on(signal, () => (releaseLock(), process.exit(130)));
	process.on("exit", releaseLock);
	const logPath = path.join(out, "game.log");
	const log = fs.openSync(logPath, "w");
	console.log(`[qa] launching ${run} in ${game}`);
	const proc = spawn(java, javaArgs, { cwd: game, stdio: ["ignore", log, log], windowsHide: false });
	let exited = null;
	proc.on("exit", (code) => (exited = code));
	if (!args.front) {
		spawn("powershell", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", BACKGROUND, String(proc.pid)], { stdio: "ignore", windowsHide: true });
	}
	const start = Date.now();
	const handled = new Set();
	let result = "TIMEOUT";
	for (;;) {
		await sleep(500);
		const text = fs.readFileSync(logPath, "utf8");
		for (const m of text.matchAll(/QA await-key ([\w-]+) (rshift|lshift|[a-z])\b/g)) {
			const [, name, key] = m;
			if (handled.has(name)) continue;
			handled.add(name);
			let ok = false;
			for (let attempt = 0; attempt < 6 && !ok; attempt++) {
				const r = spawnSync("powershell", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", KEYPOST, String(proc.pid), key], { encoding: "utf8" });
				console.log(`[qa] keypost ${name} ${key}: ${(r.stdout || r.stderr).trim()}`);
				ok = r.status === 0;
				if (!ok) await sleep(1000);
			}
			if (ok) {
				await sleep(600);
				fs.writeFileSync(path.join(out, `key-${name}.done`), key);
			}
		}
		if (exited !== null) {
			result = `EXITED ${exited}`;
			break;
		}
		if (Date.now() - start > args.timeout * 1000) {
			proc.kill();
			break;
		}
	}
	fs.closeSync(log);
	releaseLock();
	if (aot?.record && exited === 0) buildCache(java, aot, (message) => console.log("[qa] " + message));
	const lines = [...fs.readFileSync(logPath, "utf8").matchAll(/QA (.*?)(?:\]\]>|$)/gm)].map((m) => m[1]);
	fs.writeFileSync(path.join(out, "tour.log"), lines.join("\n") + "\n");
	const shots = path.join(out, "screenshots");
	console.log(`[qa] result ${result} after ${Math.round((Date.now() - start) / 1000)} s; screenshots: ${fs.existsSync(shots) ? fs.readdirSync(shots).length : 0}`);
	let failed = 0;
	let finished = false;
	for (const body of lines) {
		if (/^(PASS|FAIL|DONE)/.test(body)) console.log("[qa]   " + body);
		failed += body.startsWith("FAIL") ? 1 : 0;
		finished ||= body.startsWith("DONE");
	}
	process.exit(finished && failed === 0 ? 0 : 1);
}

main().catch((e) => {
	console.error("[qa] " + e.message);
	process.exit(2);
});
