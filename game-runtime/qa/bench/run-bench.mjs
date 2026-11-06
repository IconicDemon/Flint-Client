// Dev only: runs the benchmark matrix with the QA runners (run_qa_windows.mjs, run_qa_189.mjs), one game at a time, each in a
// fresh game folder under %USERPROFILE%\FlintQA (never the owner's profiles), and writes one JSON line per run to
// %USERPROFILE%\FlintQA\bench\results\<tag>.jsonl plus the machine's details to <tag>-machine.json.
//
// usage: node game-runtime/qa/bench/run-bench.mjs --tag NAME [--scenario main|packs|shaders|long] [--versions 26.3,1.8.9]
//        [--configs vanilla,flint] [--reps 2] [--no-wait]
//
// Configurations (the same heap, window size and world for both):
// - vanilla: Minecraft + Fabric Loader + the QA tour mod only, on the Java the version asks for (Java 8 for 1.8.9), with
//   Java's default collector settings: what a plain launcher runs.
// - flint: Flint's runtime jar and Flint's performance set for that version (fetch-assets.mjs; none on 1.8.9, where Flint
//   offers no Modrinth mods), on Java 25 as the app runs every line (runtime.rs JAVA_FOR_LINE), with the collector
//   settings Automatic Optimization passes (perfcore.rs: G1 set + compact object headers).
// - shaders (scenario shaders): flint plus Iris and the shader pack.
// Game windows stay behind the owner's windows (the runners' default); Dynamic FPS's unfocused limits are switched off above.
// Runs are interleaved (vanilla, flint, vanilla, flint...) and each waits for an idle machine first: no Rust or Gradle
// build running and CPU load under 20% for 30 s, so a background build never skews a result.
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { BENCH, packName } from "./names.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const QA = path.resolve(HERE, "..");
const QA_ROOT = path.join(os.homedir(), "FlintQA");
const FLINT_JVM = "-XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M -XX:+UseCompactObjectHeaders";
const SIZE = "1600x900";

const opt = (name, fallback) => {
	const i = process.argv.indexOf(`--${name}`);
	return i > 0 ? process.argv[i + 1] : fallback;
};
const TAG = opt("tag", null);
if (!TAG) throw new Error("--tag NAME is required");
const SCENARIO = opt("scenario", "main");
const VERSIONS = opt("versions", "26.3,26.2,26.1.2,26.1.1,26.1,1.21.11,1.21.10,1.21.1,1.8.9").split(",");
const CONFIGS = opt("configs", SCENARIO === "shaders" ? "shaders" : "vanilla,flint").split(",");
const REPS = Number(opt("reps", "1"));
// Overrides for a one-off comparison: another tour script, another mods folder (a player's own set) for the non-vanilla
// configs, and more resource packs (from bench/<version>/resourcepacks) to put in the game folder.
const SCRIPT = opt("script", null);
const MODS_DIR = opt("mods-dir", null);
const EXTRA_PACKS = opt("packs", "").split(",").filter(Boolean);
// The CPU load a run waits to drop under (an idle machine: 20; with the owner's everyday apps open, more).
const MAX_CPU = Number(opt("max-cpu", "20"));
const WAIT = !process.argv.includes("--no-wait");
const assets = JSON.parse(fs.readFileSync(path.join(BENCH, "assets.json"), "utf8"));
const RESULTS = path.join(BENCH, "results");
fs.mkdirSync(RESULTS, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ps = (command) => spawnSync("powershell", ["-NoProfile", "-NonInteractive", "-Command", command], { encoding: "utf8" }).stdout.trim();

function machine() {
	const json = ps(
		"$c=Get-CimInstance Win32_Processor|Select-Object -First 1; $g=Get-CimInstance Win32_VideoController|Where-Object {$_.AdapterRAM -ne $null}|Select-Object Name,DriverVersion,CurrentHorizontalResolution,CurrentVerticalResolution,CurrentRefreshRate; " +
			"$o=Get-CimInstance Win32_OperatingSystem; $m=(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory; " +
			"[pscustomobject]@{cpu=$c.Name.Trim();cores=$c.NumberOfCores;threads=$c.NumberOfLogicalProcessors;gpus=@($g);ramGB=[math]::Round($m/1GB,1);os=$o.Caption+' '+$o.Version;power=(powercfg /getactivescheme)}|ConvertTo-Json -Depth 4",
	);
	return JSON.parse(json);
}

function buildsRunning() {
	const list = spawnSync("tasklist", ["/FO", "CSV", "/NH"], { encoding: "utf8" }).stdout.toLowerCase();
	const names = ["rustc.exe", "cargo.exe", "link.exe", "rust-lld.exe"].filter((n) => list.includes(`"${n}"`));
	const gradle = ps("@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*GradleWorkerMain*' -or $_.CommandLine -like '*GradleWrapperMain*' }).Count");
	if (Number(gradle) > 0) names.push("gradle");
	return names;
}

/** Minecraft windows open now (while a run plays, its own game is one of them). */
function minecraftWindows() {
	return Number(ps("@(Get-Process java,javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'Minecraft*' }).Count"));
}

/** Waits until no build or other Minecraft game runs and the CPU has been under 20% for three samples in a row. */
async function waitIdle() {
	if (!WAIT) return;
	let calm = 0;
	let said = "";
	while (calm < 3) {
		const builds = buildsRunning();
		const games = minecraftWindows();
		// The processor counter averaged over 6 s. Win32_Processor's LoadPercentage read 60%+ here while processes used under
		// 10% (the query itself and boost states skew it), which held runs back for good.
		const load = Number(ps("[math]::Round(((Get-Counter '\\Processor(_Total)\\% Processor Time' -SampleInterval 2 -MaxSamples 3).CounterSamples | Measure-Object CookedValue -Average).Average)"));
		const why = builds.length ? `builds running (${builds.join(", ")})` : games ? "another Minecraft game is open" : load >= MAX_CPU ? `CPU at ${load}%` : "";
		calm = why ? 0 : calm + 1;
		if (why && why !== said) console.log(`[bench] waiting: ${why}`);
		said = why;
		await sleep(10_000);
	}
}

function prepareGame(game, version, config) {
	fs.rmSync(game, { recursive: true, force: true });
	const entry = assets[version] ?? {};
	const packs = path.join(game, "resourcepacks");
	fs.mkdirSync(packs, { recursive: true });
	for (const name of [entry.normalPack, entry.demandingPack, ...EXTRA_PACKS].filter(Boolean)) {
		fs.copyFileSync(path.join(BENCH, version, "resourcepacks", name), path.join(packs, packName(name)));
	}
	// Dynamic FPS (in Flint's performance set) holds a window the mouse is over but that isn't focused at 60 FPS, an unfocused
	// one at 1 and one with no input for 5 minutes at 10. A tour has no input and Windows may not give its window focus, while
	// a player is focused and pressing keys: these states are switched off so the runs measure what a player sees. Merged over
	// the mod's defaults (it saves only what differs). No idle condition: timeout 0 already switches idling off, and the
	// conditions differ between its builds (1.21.1's 3.11.4 has no "vanilla" and won't start with it).
	if (config !== "vanilla") {
		fs.mkdirSync(path.join(game, "config"), { recursive: true });
		const unlimited = { frame_rate_target: -1, volume_multipliers: {} };
		fs.writeFileSync(path.join(game, "config", "dynamic_fps.json"), JSON.stringify({ idle: { timeout: 0 }, states: { hovered: unlimited, unfocused: unlimited, abandoned: unlimited } }, null, 2));
	}
	if (config === "shaders" && entry.shader) {
		fs.mkdirSync(path.join(game, "shaderpacks"), { recursive: true });
		fs.copyFileSync(path.join(BENCH, version, "shaderpacks", entry.shader), path.join(game, "shaderpacks", entry.shader));
		fs.mkdirSync(path.join(game, "config"), { recursive: true });
		fs.writeFileSync(path.join(game, "config", "iris.properties"), `shaderPack=${entry.shader}\nenableShaders=true\n`);
	}
}

function parse(runDir) {
	const log = path.join(runDir, "tour.log");
	const text = fs.existsSync(log) ? fs.readFileSync(log, "utf8") : "";
	const out = { titleMs: null, worldMs: null, frames: {}, mem: [], failures: [] };
	for (const line of text.split(/\r?\n/)) {
		let m;
		if ((m = line.match(/title screen .*uptimeMs=(\d+)/))) out.titleMs = Number(m[1]);
		else if ((m = line.match(/world joined (\d+) ms/))) out.worldMs = Number(m[1]);
		else if ((m = line.match(/frames (\S+) n=(\d+) avgFps=([\d.]+) low1=([\d.]+) low01=([\d.]+) median=([\d.]+)ms p99=([\d.]+)ms max=([\d.]+)ms jitter=([\d.]+)ms hitches=(\d+) pauses50=(\d+) gc=(\d+) gcMs=(\d+) allocMBps=([\d.]+)/))) {
			const [, name, n, avg, low1, low01, median, p99, max, jitter, hitches, pauses, gc, gcMs, alloc] = m;
			out.frames[name] = { n: +n, avgFps: +avg, low1: +low1, low01: +low01, medianMs: +median, p99Ms: +p99, maxMs: +max, jitterMs: +jitter, hitches: +hitches, pauses50: +pauses, gc: +gc, gcMs: +gcMs, allocMBps: +alloc };
		} else if ((m = line.match(/mem used=(\d+)MB committed=(\d+)MB max=(\d+)MB/))) out.mem.push({ usedMB: +m[1], committedMB: +m[2], maxMB: +m[3] });
		else if (/\bFAIL\b/.test(line)) out.failures.push(line.slice(0, 200));
	}
	return out;
}

/** CPU (100 = every core busy), peak working set and average GPU 3D load from the sampler's CSV, after the title screen. */
function processStats(csv, cores) {
	if (!fs.existsSync(csv)) return null;
	// The last sample can catch the process as it exits (its CPU time reads 0): only samples whose CPU time keeps rising count.
	const rows = fs
		.readFileSync(csv, "utf8")
		.trim()
		.split(/\r?\n/)
		.slice(1)
		.map((l) => l.replace(/^﻿/, "").split(",").map((v) => (v === "" ? null : Number(v))))
		.filter((r, i, all) => r[1] > 0 && (i === 0 || r[1] >= all[i - 1][1]));
	if (rows.length < 3) return null;
	const cpu = [];
	for (let i = 1; i < rows.length; i++) {
		const dt = rows[i][0] - rows[i - 1][0];
		if (dt > 0) cpu.push(((rows[i][1] - rows[i - 1][1]) / dt / cores) * 100);
	}
	const gpu = rows.map((r) => r[4]).filter((v) => v != null);
	const avg = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null);
	const round = (v) => (v == null ? null : Math.round(v * 10) / 10);
	return {
		cpuAvgPct: round(avg(cpu)),
		cpuPeakPct: round(Math.max(...cpu)),
		coresUsedAvg: round((avg(cpu) * cores) / 100),
		workingSetPeakMB: Math.max(...rows.map((r) => r[2])),
		privatePeakMB: Math.max(...rows.map((r) => r[3])),
		gpu3dAvgPct: round(avg(gpu)),
		samples: rows.length,
	};
}

async function runOne(scenario, version, config, rep, cores) {
	const legacy = version === "1.8.9";
	const name = `b-${TAG}-${scenario}-${version}-${config}-${rep}`;
	const gameName = `bench-${TAG}-${version}-${config}`;
	const game = path.join(QA_ROOT, "games", gameName);
	const runDir = path.join(QA_ROOT, "runs", name);
	fs.rmSync(runDir, { recursive: true, force: true });
	prepareGame(game, version, config);
	const scripts = path.join(BENCH, "scripts");
	const script = SCRIPT ? path.resolve(SCRIPT) : scenario === "shaders" ? path.join(scripts, "bench-shaders.txt") : scenario === "long" ? path.join(scripts, "bench-long.txt") : scenario === "mp" ? path.join(scripts, "bench-mp.txt") : legacy && scenario === "packs" ? path.join(scripts, "bench-1.8.9-packs.txt") : path.join(scripts, `bench-${version}.txt`);
	const args = [path.join(QA, legacy ? "run_qa_189.mjs" : "run_qa_windows.mjs"), script, name, "--game", gameName, "--reuse", "--size", SIZE, "--timeout", scenario === "long" ? "1200" : "900"];
	const env = { ...process.env };
	delete env.FLINTQA_JVM;
	delete env.FLINTQA_JAVA_COMPONENT;
	if (config === "vanilla") args.push("--no-runtime");
	else env.FLINTQA_JVM = FLINT_JVM;
	if (legacy) {
		args.push("--java", config === "vanilla" ? "8" : "25");
		// 1.8.9 has no pack step: the packs session starts with both packs on (options.txt, which the runner keeps and adds its
		// own keys to); a comma list can't carry the pack list, so it's written here.
		if (scenario === "packs") {
			const packs = [assets[version].normalPack, assets[version].demandingPack].filter(Boolean).map(packName);
			const fresh = ["fullscreen:false", "pauseOnLostFocus:false", "soundCategory_master:0.0", "snooperEnabled:false", "lang:en_US", "guiScale:2", "useVbo:true"];
			fs.writeFileSync(path.join(game, "options.txt"), [...fresh, `resourcePacks:${JSON.stringify(packs)}`].join("\n") + "\n");
		}
		args.push("--options", "enableVsync:false,maxFps:260,renderDistance:12,fboEnable:true");
	} else {
		args.push("--mc", version);
		if (config !== "vanilla") {
			args.push("--mods", MODS_DIR ? path.resolve(MODS_DIR) : path.join(BENCH, version, config === "shaders" ? "iris" : "perf"));
			if (version.startsWith("1.21")) env.FLINTQA_JAVA_COMPONENT = "java-runtime-epsilon";
		}
	}
	fs.mkdirSync(runDir, { recursive: true });
	const csv = path.join(runDir, "proc.csv");
	const sampler = spawn("powershell", ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", path.join(HERE, "sample-proc.ps1"), "-Marker", name, "-Out", csv], { stdio: "ignore" });
	// Listened for from the start: the sampler can end before the runner does, and a missed exit would never settle.
	const samplerDone = new Promise((r) => sampler.once("exit", r).once("error", r));
	const started = Date.now();
	// A build or a second game during the run makes it unfit for comparison: noted, and the run is done again.
	const disturbed = new Set();
	const watch = setInterval(() => {
		buildsRunning().forEach((b) => disturbed.add(b));
		if (minecraftWindows() > 1) disturbed.add("another Minecraft game");
	}, 15_000);
	const code = await new Promise((resolve) => {
		const child = spawn(process.execPath, args, { env, stdio: ["ignore", "pipe", "pipe"] });
		const logFile = fs.createWriteStream(path.join(BENCH, "results", `${TAG}-runner.log`), { flags: "a" });
		logFile.write(`\n=== ${name} ${new Date().toISOString()}\n`);
		child.stdout.pipe(logFile, { end: false });
		child.stderr.pipe(logFile, { end: false });
		child.on("exit", resolve);
	});
	clearInterval(watch);
	await samplerDone;
	const result = { tag: TAG, scenario, version, config, rep, run: name, exit: code, disturbedBy: [...disturbed], wallS: Math.round((Date.now() - started) / 1000), ...parse(runDir), process: processStats(csv, cores), at: new Date().toISOString() };
	fs.appendFileSync(path.join(RESULTS, `${TAG}.jsonl`), JSON.stringify(result) + "\n");
	const fr = result.frames.turning ?? Object.values(result.frames)[0];
	console.log(`[bench] ${name}: exit ${code}, title ${result.titleMs} ms, world ${result.worldMs} ms, turning ${fr ? `${fr.avgFps} fps (1% ${fr.low1})` : "-"}, failures ${result.failures.length}${disturbed.size ? `, DISTURBED by ${[...disturbed].join(", ")}` : ""}`);
	return result;
}

const info = machine();
fs.writeFileSync(path.join(RESULTS, `${TAG}-machine.json`), JSON.stringify(info, null, 2));
console.log(`[bench] ${info.cpu}, ${info.ramGB} GB, ${info.gpus.map((g) => g.Name).join(" + ")}`);
// The multiplayer runs need the local test server (26.3, 127.0.0.1:25599, offline mode, loopback only) running throughout.
let server = null;
if (SCENARIO === "mp") {
	const java = path.join(QA_ROOT, "mc-cache", "runtimes", "java-runtime-epsilon", "windows-x64", "bin", "java.exe");
	server = spawn(java, ["-Xmx2G", "-jar", "minecraft-server.jar", "--nogui"], { cwd: path.join(QA_ROOT, "server"), stdio: ["pipe", "pipe", "pipe"] });
	await new Promise((resolve, reject) => {
		let text = "";
		server.stdout.on("data", (d) => {
			text += d;
			if (/Done \(/.test(text)) resolve();
		});
		server.on("exit", () => reject(new Error("the test server stopped: " + text.slice(-400))));
	});
	console.log("[bench] test server up on 127.0.0.1:25599");
}
try {
	for (let rep = 1; rep <= REPS; rep++) {
		for (const version of VERSIONS) {
			if (SCENARIO === "shaders" && version === "1.8.9") continue;
			for (const config of CONFIGS) {
				for (let attempt = 1; attempt <= 3; attempt++) {
					await waitIdle();
					const result = await runOne(SCENARIO, version, config, rep, info.threads);
					await sleep(20_000);
					if (!result.disturbedBy.length) break;
				}
			}
		}
	}
} finally {
	if (server) {
		server.stdin.write("stop\n");
		await new Promise((r) => setTimeout(r, 15_000));
		server.kill();
	}
}
console.log("[bench] done");
