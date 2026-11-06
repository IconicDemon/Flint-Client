// Dev only: times Play end to end through the real launcher (a `perf` build, which has the offline QA identity in an
// isolated data folder): Play clicked -> launcher ready ("Play to Java", its own log line) -> Java started -> the game's
// window visible -> title screen usable -> a new world playable. The QA tour mod (in the profile's mods folder, its
// script passed through Settings -> Extra Java arguments) logs the title and world times; a poller sees the window.
//
// usage: node game-runtime/qa/bench/launch-chain.mjs --exe <flint-app.exe> --data <dir> --tag <name>
//        [--versions 26.3,26.2,26.1.2,1.21.11,1.21.10,1.21.1,1.8.9] [--warm 2] [--kinds first,cold,warm]
// Per version: a first launch right after the install (it adds Flint's performance mods and records Java's start-up
// cache, so it is reported apart as "first"), then a "cold" launch (the first with the start-up cache built; Flint has
// launched that profile once this session) and `--warm` more in a row. Results go to FlintQA/bench/results/<tag>-launch.jsonl
// in the user folder.
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { BENCH } from "./names.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const QA = path.resolve(HERE, "..");
const opt = (name, fallback) => {
	const i = process.argv.indexOf(`--${name}`);
	return i > 0 ? process.argv[i + 1] : fallback;
};
const EXE = opt("exe", null);
const DATA = opt("data", null);
const TAG = opt("tag", null);
if (!EXE || !DATA || !TAG) throw new Error("--exe, --data and --tag are required");
const VERSIONS = opt("versions", "26.3,26.2,26.1.2,1.21.11,1.21.10,1.21.1,1.8.9").split(",");
const WARM = Number(opt("warm", "2"));
// Which launches to time (a version's "first" can only be taken once per build, so a re-run can leave it out).
const KINDS = opt("kinds", "first,cold,warm").split(",");
const PORT = 9556;
const OUT = path.join(BENCH, "results");
const RUNS = path.join(os.homedir(), "FlintQA", "launch-runs", TAG);
fs.mkdirSync(RUNS, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const line = (v) => (v.startsWith("26.1") ? "26.1" : v);
const qaJar = (v) => (v === "26.3" ? path.join(QA, "build", "libs", "flint-qa-1.0.0.jar") : path.join(QA, "build", `mc-${line(v)}`, "libs", `flint-qa-1.0.0+${line(v)}.jar`));

// ---------- the app, over CDP ----------
const stderrPath = path.join(RUNS, "launcher-stderr.log");
const app = spawn(EXE, [], {
	env: { ...process.env, FLINT_DATA_DIR: DATA, FLINT_QA: "1", WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--remote-debugging-port=${PORT}` },
	stdio: ["ignore", "ignore", fs.openSync(stderrPath, "w")],
});
let ws;
let seq = 0;
const pending = new Map();
async function connect() {
	for (let i = 0; i < 120; i++) {
		try {
			const pages = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
			const page = pages.find((p) => p.type === "page");
			if (page) {
				ws = new WebSocket(page.webSocketDebuggerUrl);
				await new Promise((r) => ws.addEventListener("open", r));
				ws.addEventListener("close", () => {
					console.log("[chain] the app closed; stopping (results so far are saved)");
					endStrayGames();
					process.exit(2);
				});
				ws.addEventListener("message", (e) => {
					const msg = JSON.parse(e.data);
					if (msg.id && pending.has(msg.id)) pending.get(msg.id)(msg), pending.delete(msg.id);
				});
				return;
			}
		} catch {}
		await sleep(500);
	}
	throw new Error("the app's debugging port never answered");
}
/** Calls a launcher command from the page, as the interface does. */
async function invoke(cmd, args = {}) {
	const id = ++seq;
	const expression = `window.__TAURI_INTERNALS__.invoke(${JSON.stringify(cmd)}, ${JSON.stringify(args)}).then(v => JSON.stringify({ ok: v ?? null }), e => JSON.stringify({ err: String(e) }))`;
	const res = await new Promise((resolve) => {
		pending.set(id, resolve);
		ws.send(JSON.stringify({ id, method: "Runtime.evaluate", params: { expression, awaitPromise: true, returnByValue: true } }));
	});
	const out = JSON.parse(res.result?.result?.value ?? '{"err":"no answer"}');
	if (out.err) throw new Error(`${cmd}: ${out.err}`);
	return out.ok;
}

async function waitState(id, want, timeoutS) {
	const until = Date.now() + timeoutS * 1000;
	let last = "";
	while (Date.now() < until) {
		const states = await invoke("profile_states");
		const s = states[id];
		last = s ? `${s.state} ${s.label ?? ""} ${s.message ?? ""}` : "none";
		if (s && want.includes(s.state)) return s;
		if (s?.state === "error") throw new Error(`profile ${id}: ${last}`);
		await sleep(1000);
	}
	throw new Error(`profile ${id} never reached ${want} (last: ${last})`);
}

/** Watches for the game's window: the moment a Java process first has a visible main window. Then it keeps that window
 *  behind whatever is in front (qa/tools/background.ps1), so a run never takes over the screen. */
function windowPoller(outFile) {
	const background = path.join(QA, "tools", "background.ps1");
	// Only a window that wasn't there when the poller started counts.
	const script = `$before = @(Get-Process java,javaw -ErrorAction SilentlyContinue | ForEach-Object Id); $deadline=(Get-Date).AddMinutes(4); while((Get-Date) -lt $deadline){ $w = Get-Process java,javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 -and $before -notcontains $_.Id } | Select-Object -First 1; if($w){ [DateTimeOffset]::Now.ToUnixTimeMilliseconds() | Out-File -Encoding ascii '${outFile}'; & '${background}' -ProcessId $w.Id -Seconds 90; break }; Start-Sleep -Milliseconds 50 }`;
	return spawn("powershell", ["-NoProfile", "-NonInteractive", "-Command", script], { stdio: "ignore" });
}

const stderrSize = () => fs.statSync(stderrPath).size;
function newStderr(from) {
	const fd = fs.openSync(stderrPath, "r");
	const size = fs.fstatSync(fd).size;
	const buf = Buffer.alloc(Math.max(0, size - from));
	fs.readSync(fd, buf, 0, buf.length, from);
	fs.closeSync(fd);
	return buf.toString("utf8");
}

/** A new profile's first start shows Minecraft's accessibility welcome before the title screen: answered here, as the
 *  QA runners do, so every launch goes straight to the title. Other options are left as Flint wrote them. */
function skipFirstRunScreens(profile) {
	const file = path.join(DATA, "profiles", profile.folder ?? profile.id, "options.txt");
	const lines = fs.existsSync(file) ? fs.readFileSync(file, "utf8").split(/\r?\n/).filter(Boolean) : [];
	const set = { onboardAccessibility: "false", tutorialStep: "none", joinedFirstServer: "true", skipMultiplayerWarning: "true", pauseOnLostFocus: "false", soundCategory_master: "0.0" };
	const kept = lines.filter((l) => !Object.keys(set).includes(l.split(":")[0]));
	fs.writeFileSync(file, [...kept, ...Object.entries(set).map(([k, v]) => `${k}:${v}`)].join("\n") + "\n");
}

/** Ends a game this run started (its command line names this data folder) whose tour didn't finish. Flint writes the
 *  folder with either slash, so both sides are compared with backslashes. */
function endStrayGames() {
	spawnSync("powershell", ["-NoProfile", "-NonInteractive", "-Command", `Get-CimInstance Win32_Process | Where-Object { ($_.Name -like 'java*') -and (($_.CommandLine -replace '/', '\\') -like '*${DATA.replaceAll("/", "\\")}*') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }`], { stdio: "ignore" });
}

/** What else is running that would skew a launch: a build, or a Minecraft window that isn't this run's game. */
function disturbers() {
	const out = spawnSync("powershell", ["-NoProfile", "-NonInteractive", "-Command", `$n = @(Get-Process cargo,rustc,link,gradle -ErrorAction SilentlyContinue | ForEach-Object { $_.ProcessName + '.exe' }); $n += @(Get-Process java,javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | ForEach-Object { 'another Minecraft game' }); $n | Sort-Object -Unique`], { encoding: "utf8" });
	return out.stdout.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
}

async function timedLaunch(profile, version, kind, n) {
	skipFirstRunScreens(profile);
	const runDir = path.join(RUNS, `${version}-${kind}-${n}`);
	fs.rmSync(runDir, { recursive: true, force: true });
	fs.mkdirSync(runDir, { recursive: true });
	const script = path.join(runDir, "script.txt");
	fs.writeFileSync(script, ["wait-title 300", "create-world launchbench" + n, "wait-world 300 1", "quit"].join("\n") + "\n");
	const settings = await invoke("settings_get");
	// Silent and out of the way: no interface sounds, the launcher minimizes as the game starts, a normal-sized game window.
	await invoke("settings_set", { settings: { ...settings, jvmArgs: `-Dflintqa.script=${script} -Dflintqa.out=${runDir}`, gameStartBehavior: "minimize", sounds: false, gameMaximized: false } });
	const windowFile = path.join(runDir, "window.txt");
	endStrayGames();
	const before = disturbers();
	const poller = windowPoller(windowFile);
	await sleep(400);
	const from = stderrSize();
	const t0 = Date.now();
	await invoke("profile_launch", { id: profile.id });
	await waitState(profile.id, ["running"], 240).catch(() => {});
	// The tour quits the game once the world is playable. A game the launcher starts logs the tour's lines ("QA ...") in
	// the profile's own latest.log (the QA runners copy them into tour.log; nothing does here).
	const gameLog = path.join(DATA, "profiles", profile.folder ?? profile.id, "logs", "latest.log");
	const readLog = () => (fs.existsSync(gameLog) && fs.statSync(gameLog).mtimeMs >= t0 ? fs.readFileSync(gameLog, "utf8") : "");
	const until = Date.now() + 300_000;
	while (Date.now() < until && !/QA world joined|QA FAIL/.test(readLog())) await sleep(500);
	// Other builds and games are noted while this one is still up (only its own window is open by now).
	const during = disturbers().filter((d) => d !== "another Minecraft game");
	// The tour quits the game itself; a game that doesn't within half a minute (1.8.9 sometimes stops at its pause menu
	// when it isn't the focused window) is ended. Nothing after "world joined" is timed.
	await waitState(profile.id, ["ready"], 30).catch(() => {});
	poller.kill();
	endStrayGames();
	await waitState(profile.id, ["ready"], 60).catch(() => {});
	await sleep(2000);
	const log = readLog();
	fs.writeFileSync(path.join(runDir, "tour.log"), log.split(/\r?\n/).filter((l) => l.includes("QA ")).join("\n") + "\n");
	const err = newStderr(from);
	const playToJava = err.match(/Play to Java for Minecraft \S+: (\d+) ms(?: \(([^)]*)\))?/);
	const title = log.match(/title screen .*uptimeMs=(\d+)/);
	const world = log.match(/world joined (\d+) ms/);
	const windowAt = fs.existsSync(windowFile) ? Number(fs.readFileSync(windowFile, "utf8").trim()) : null;
	const result = {
		tag: TAG,
		version,
		kind,
		n,
		playToJavaMs: playToJava ? Number(playToJava[1]) : null,
		playToJavaSteps: playToJava?.[2] ?? null,
		clickToWindowMs: windowAt ? windowAt - t0 : null,
		javaToTitleMs: title ? Number(title[1]) : null,
		clickToTitleMs: playToJava && title ? Number(playToJava[1]) + Number(title[1]) : null,
		worldJoinMs: world ? Number(world[1]) : null,
		clickToWorldMs: playToJava && title && world ? Number(playToJava[1]) + Number(title[1]) + Number(world[1]) : null,
		failures: (log.match(/FAIL[^\n]*/g) ?? []).slice(0, 3),
		disturbedBy: [...new Set([...before, ...during])],
		at: new Date().toISOString(),
	};
	fs.appendFileSync(path.join(OUT, `${TAG}-launch.jsonl`), JSON.stringify(result) + "\n");
	console.log(`[chain] ${version} ${kind} ${n}: Play->Java ${result.playToJavaMs} ms, window ${result.clickToWindowMs} ms, title ${result.clickToTitleMs} ms, world ${result.clickToWorldMs} ms`);
	// Back to ready before the next launch.
	await waitState(profile.id, ["ready", "uninstalled"], 120).catch(() => {});
	await minimize();
	await sleep(4000);
	return result;
}

await connect();
await sleep(3000);
// The test launcher stays out of the way: minimized from the start (it is restored and minimized again around each game).
const minimize = () => invoke("plugin:window|minimize", { label: "main" }).catch(() => {});
await minimize();
const account = await invoke("auth_qa_account");
const onboarding = (await invoke("onboarding_get")) ?? {};
await invoke("onboarding_set", { value: { ...onboarding, completed: true } });
console.log(`[chain] ${TAG}: signed in as ${account.username} (offline QA identity)`);
for (const version of VERSIONS) {
	const list = await invoke("profiles_list");
	let profile = list.profiles.find((p) => p.name === `bench ${version}`);
	if (!profile) profile = await invoke("profiles_create", { name: `bench ${version}`, gameVersion: version, loader: "fabric", select: false });
	if (!profile.installed) {
		await invoke("profile_install", { id: profile.id, repair: false });
		await waitState(profile.id, ["ready"], 1800);
	}
	// The QA tour mod goes in the profile's own mods folder (wherever this Flint keeps it).
	const fresh = (await invoke("profiles_list")).profiles.find((p) => p.id === profile.id);
	const mods = path.join(DATA, "profiles", fresh.folder ?? fresh.id, "mods");
	fs.mkdirSync(mods, { recursive: true });
	fs.copyFileSync(qaJar(version), path.join(mods, "flint-qa.jar"));
	// First launch: performance mods and Java's start-up cache recording (not timed as a normal launch).
	if (KINDS.includes("first")) await timedLaunch(fresh, version, "first", 1);
	if (KINDS.includes("cold")) await timedLaunch(fresh, version, "cold", 1);
	if (KINDS.includes("warm")) for (let n = 1; n <= WARM; n++) await timedLaunch(fresh, version, "warm", n);
}
const settings = await invoke("settings_get");
await invoke("settings_set", { settings: { ...settings, jvmArgs: "" } });
ws.close();
app.kill();
spawnSync("taskkill", ["/PID", String(app.pid), "/T", "/F"]);
console.log("[chain] done");
