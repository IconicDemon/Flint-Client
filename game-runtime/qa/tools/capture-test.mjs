// Dev only (Windows): what a Discord / OBS viewer sees of a Flint game. Runs one QA game (run_qa_windows.mjs, or
// run_qa_189.mjs for 1.8.9) through a list of segments (windowed / borderless / exclusive fullscreen; FPS limit; VSync), and
// for each segment captures the game the way screen sharing does while the camera turns:
//   wgc60 / wgc30: Windows.Graphics.Capture of the game window (Discord "share an application"), 60 or 30 fps, encoded with
//                  NVENC H.264 (as Discord and OBS encode);
//   dda60:         Desktop Duplication of the game's area of the monitor (Discord "share a screen", OBS display capture);
//   none:          no capture (the game's own numbers without a capture running).
// The game stamps every frame it presents with its number (qa QaStamp) and logs when it presented it, so each captured frame
// tells which game frame the viewer got. Per segment: the game's FPS, 1% low and present pacing; the stream's repeated frames
// ("stuck"), skipped content and how unevenly the picture moves from one stream frame to the next (judder); GPU load.
//
// usage: node qa/tools/capture-test.mjs --plan PLAN.json --run NAME [--mc 26.3] [--mods DIR] [--display \\.\DISPLAY2]
//        [--dda-output 1] [--size 1600x900] [--focus-idle-s 120] [--keep]
//        node qa/tools/capture-test.mjs --analyze RUN          (again, from the files of an earlier run)
// PLAN.json: { "setup": [tour steps], "segments": [{ "name", "mode": "windowed|borderless|exclusive", "steps": [tour steps],
//              "capture": "wgc60|wgc30|dda60|none", "seconds": 15 }] }
// Needs ffmpeg with gfxcapture and ddagrab (FFMPEG, default %USERPROFILE%\FlintQA\tools\ffmpeg\...\bin) and nvidia-smi.
// Run it inside heavy-slot.mjs (it runs a game). The game window is put on --display (on top there, never activated, so the
// owner keeps the keyboard and mouse); exclusive fullscreen needs focus, which it takes only if nobody has touched the PC for
// --focus-idle-s seconds (else that segment runs without focus, and says so). Capture files are deleted unless --keep.
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const QA = path.resolve(HERE, "..");
const QA_ROOT = path.join(os.homedir(), "FlintQA");
const FFMPEG_DIR = process.env.FFMPEG ?? path.join(QA_ROOT, "tools", "ffmpeg", "ffmpeg-master-latest-win64-gpl", "bin");
const FFMPEG = path.join(FFMPEG_DIR, "ffmpeg.exe");
const FFPROBE = path.join(FFMPEG_DIR, "ffprobe.exe");
const WINDOW = path.join(HERE, "capture-window.ps1");

const opt = (name, fallback) => {
	const i = process.argv.indexOf(`--${name}`);
	return i > 0 ? process.argv[i + 1] : fallback;
};
const flag = (name) => process.argv.includes(`--${name}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- the stamp a captured frame carries (QaStamp: white, black, 20 bits, parity; BLOCK px each) ----------
const BLOCKS = 23;
const BLOCK = 20;

function decodeStrip(buf, offset, width, height) {
	const at = (i) => buf[offset + Math.floor(height / 2) * width + i * BLOCK + Math.floor(BLOCK / 2)];
	if (at(0) < 160 || at(1) > 96) return -1;
	let n = 0;
	let ones = 0;
	for (let b = 0; b < 20; b++) {
		const v = at(2 + b);
		if (v > 96 && v < 160) return -1;
		const bit = v >= 160 ? 1 : 0;
		n = (n << 1) | bit;
		ones += bit;
	}
	const parity = at(22) >= 160 ? 1 : 0;
	return (ones & 1) === parity ? n : -1;
}

/** Every frame of a capture file: its time (s) and the game frame it shows (-1 when no stamp could be read). */
function readCapture(file) {
	const pts = spawnSync(FFPROBE, ["-v", "error", "-select_streams", "v", "-show_entries", "frame=pts_time", "-of", "csv=p=0", file], { encoding: "utf8", maxBuffer: 1 << 28, windowsHide: true })
		.stdout.split(/\r?\n/).filter(Boolean).map(Number);
	const w = BLOCKS * BLOCK;
	const h = BLOCK;
	const strips = {};
	for (const [where, y] of [["top", "0"], ["bottom", `ih-${h}`]]) {
		const r = spawnSync(FFMPEG, ["-v", "error", "-i", file, "-vf", `crop=${w}:${h}:0:${y},format=gray`, "-fps_mode", "passthrough", "-f", "rawvideo", "-"], { maxBuffer: 1 << 30, windowsHide: true });
		strips[where] = r.stdout;
	}
	const frames = Math.min(pts.length, strips.top.length / (w * h));
	// The corner the stamp is in (GL's origin is the bottom left): whichever reads on more frames.
	let best = null;
	for (const where of ["top", "bottom"]) {
		const nums = [];
		for (let i = 0; i < frames; i++) nums.push(decodeStrip(strips[where], i * w * h, w, h));
		const ok = nums.filter((n) => n >= 0).length;
		if (!best || ok > best.ok) best = { where, nums, ok };
	}
	return { pts: pts.slice(0, frames), nums: best.nums, corner: best.where, readable: best.ok };
}

function readStamps(file) {
	const map = new Map();
	const rows = [];
	for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/).slice(1)) {
		if (!line) continue;
		const [frame, before, after] = line.split(",").map(Number);
		rows.push({ frame, before, after });
		map.set(frame & 0xfffff, { before, after });
	}
	return { rows, map };
}

const pct = (sorted, p) => sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))] : NaN;
const mean = (a) => a.reduce((s, x) => s + x, 0) / Math.max(1, a.length);
const stdev = (a) => {
	const m = mean(a);
	return Math.sqrt(mean(a.map((x) => (x - m) ** 2)));
};
const r2 = (x) => Math.round(x * 100) / 100;

/** The game's side: present pacing from the stamps (time between present calls). */
function gameStats(stamps) {
	const rows = stamps.rows;
	const d = [];
	for (let i = 1; i < rows.length; i++) d.push((rows[i].before - rows[i - 1].before) / 1e6);
	const sorted = [...d].sort((a, b) => a - b);
	const total = d.reduce((s, x) => s + x, 0);
	const worst = sorted.slice(Math.floor(sorted.length * 0.99));
	const presentMs = rows.map((r) => (r.after - r.before) / 1e6).sort((a, b) => a - b);
	return {
		fps: r2(d.length / (total / 1000)), low1: r2(worst.length / (worst.reduce((s, x) => s + x, 0) / 1000)),
		intervalMedianMs: r2(pct(sorted, 0.5)), intervalP99Ms: r2(pct(sorted, 0.99)), intervalMaxMs: r2(sorted.at(-1) ?? NaN),
		intervalSdMs: r2(stdev(d)), presentCallMedianMs: r2(pct(presentMs, 0.5)), presentCallP99Ms: r2(pct(presentMs, 0.99)),
	};
}

/**
 * The viewer's side. The capture keeps every frame Windows delivered, with the time it was composed. From those: how evenly
 * new pictures arrived (gaps, the longest wait), how evenly the picture moved between deliveries (content step vs arrival step),
 * and what a stream at a fixed rate (60 and 30 fps, as Discord sends) shows: at each tick, the latest delivered frame.
 */
function streamStats(cap, stamps) {
	const { pts, nums } = cap;
	const del = [];
	let unreadable = 0;
	for (let i = 0; i < nums.length; i++) {
		if (nums[i] < 0 || !stamps.map.has(nums[i])) {
			unreadable++;
			continue;
		}
		const c = stamps.map.get(nums[i]).before / 1e6;
		// A delivery that shows no newer game frame than the last (Windows composed for another reason) is no new picture.
		if (del.length && nums[i] <= del.at(-1).n) continue;
		del.push({ t: pts[i] * 1000, n: nums[i], c });
	}
	if (del.length < 20) return { error: `only ${del.length} readable frames`, frames: nums.length };
	const gaps = [];
	const errs = [];
	for (let i = 1; i < del.length; i++) {
		const dt = del[i].t - del[i - 1].t;
		gaps.push(dt);
		errs.push(Math.abs((del[i].c - del[i - 1].c) - dt));
	}
	const sg = [...gaps].sort((a, b) => a - b);
	const se = [...errs].sort((a, b) => a - b);
	const seconds = (del.at(-1).t - del[0].t) / 1000;
	const out = {
		deliveries: del.length, seconds: r2(seconds), deliveredFps: r2((del.length - 1) / seconds), unreadable,
		gapMedianMs: r2(pct(sg, 0.5)), gapP99Ms: r2(pct(sg, 0.99)), gapMaxMs: r2(sg.at(-1)), gapsOver50: gaps.filter((g) => g > 50).length,
		stepErrMeanMs: r2(mean(errs)), stepErrP95Ms: r2(pct(se, 0.95)), stepErrP99Ms: r2(pct(se, 0.99)),
	};
	// A fixed-rate stream: tick k shows the latest frame delivered by then.
	for (const hz of [60, 30]) {
		const tick = 1000 / hz;
		let j = 0;
		const shown = [];
		for (let t = del[0].t + tick; t <= del.at(-1).t; t += tick) {
			while (j + 1 < del.length && del[j + 1].t <= t) j++;
			shown.push(del[j]);
		}
		let dups = 0;
		let run = 0;
		let stuck = 0;
		let longest = 0;
		const jud = [];
		for (let k = 1; k < shown.length; k++) {
			if (shown[k].n === shown[k - 1].n) {
				dups++;
				run++;
				longest = Math.max(longest, (run + 1) * tick);
			} else {
				if (run >= 1) stuck++;
				run = 0;
			}
			jud.push(Math.abs(shown[k].c - shown[k - 1].c - tick));
		}
		const sj = [...jud].sort((a, b) => a - b);
		out[`s${hz}`] = {
			dupPct: r2(100 * dups / jud.length), repeatsPerMin: r2(stuck / (seconds / 60)), longestSameMs: r2(longest),
			judderMeanMs: r2(mean(jud)), judderP95Ms: r2(pct(sj, 0.95)), judderP99Ms: r2(pct(sj, 0.99)),
			over4Pct: r2(100 * jud.filter((e) => e > 4).length / jud.length), over8Pct: r2(100 * jud.filter((e) => e > 8).length / jud.length),
		};
	}
	return out;
}

function gpuStats(file) {
	if (!fs.existsSync(file)) return null;
	const rows = fs.readFileSync(file, "utf8").split(/\r?\n/).filter((l) => /^\d/.test(l.split(",")[1]?.trim() ?? "")).map((l) => l.split(",").map((x) => x.trim()));
	if (!rows.length) return null;
	const util = rows.map((r) => Number(r[1]));
	const enc = rows.map((r) => Number(r[2]));
	return { gpuUtilPct: r2(mean(util)), gpuUtilMaxPct: Math.max(...util), encoderPct: r2(mean(enc)), samples: rows.length };
}

function analyze(run) {
	const out = path.join(QA_ROOT, "runs", run);
	const plan = JSON.parse(fs.readFileSync(path.join(out, "capture-plan.json"), "utf8"));
	const results = [];
	for (const seg of plan.segments) {
		const stampsFile = path.join(out, `stamps-${seg.name}.csv`);
		if (!fs.existsSync(stampsFile)) {
			results.push({ name: seg.name, error: "no stamps (segment didn't run)" });
			continue;
		}
		const stamps = readStamps(stampsFile);
		const entry = { name: seg.name, mode: seg.mode, capture: seg.capture, game: gameStats(stamps), gpu: gpuStats(path.join(out, `gpu-${seg.name}.csv`)) };
		const meta = path.join(out, `capture-${seg.name}.json`);
		if (fs.existsSync(meta)) entry.window = JSON.parse(fs.readFileSync(meta, "utf8"));
		const capFile = path.join(out, `cap-${seg.name}.nut`);
		if (seg.capture !== "none" && fs.existsSync(capFile)) {
			const cap = readCapture(capFile);
			entry.corner = cap.corner;
			entry.stream = streamStats(cap, stamps);
		}
		const framesLine = fs.existsSync(path.join(out, "tour.log"))
			? fs.readFileSync(path.join(out, "tour.log"), "utf8").split(/\r?\n/).find((l) => l.startsWith(`frames ${seg.name} `)) : null;
		if (framesLine) entry.framesLine = framesLine;
		results.push(entry);
	}
	fs.writeFileSync(path.join(out, "capture-results.json"), JSON.stringify(results, null, 2));
	const rows = [["segment", "mode", "cap", "fps", "low1", "sd", "gpu%", "deliv/s", "gapP99", "gapMax", "gaps>50", "stepErr", "s60dup%", "s60jud", "s60p95", "s60>8%", "s30dup%", "s30p95"]];
	for (const r of results) {
		if (r.error) {
			rows.push([r.name, r.error]);
			continue;
		}
		const s = r.stream ?? {};
		const s60 = s.s60 ?? {};
		const s30 = s.s30 ?? {};
		rows.push([r.name + (r.window?.disturbed ? "*" : ""), r.mode, r.capture, r.game.fps, r.game.low1, r.game.intervalSdMs, r.gpu?.gpuUtilPct ?? "", s.error ?? s.deliveredFps ?? "", s.gapP99Ms ?? "",
			s.gapMaxMs ?? "", s.gapsOver50 ?? "", s.stepErrMeanMs ?? "", s60.dupPct ?? "", s60.judderMeanMs ?? "", s60.judderP95Ms ?? "", s60.over8Pct ?? "", s30.dupPct ?? "",
			s30.judderP95Ms ?? ""]);
	}
	const table = rows.map((r) => r.map(String).join("\t")).join("\n");
	fs.writeFileSync(path.join(out, "capture-results.tsv"), table + "\n");
	console.log(table);
	return results;
}

// ---------- the run ----------

function tourFor(plan, mc) {
	const legacy = mc === "1.8.9";
	const hasExclusiveOption = /^26\./.test(mc);
	// 1.8.9's QA mod has its own steps: the plan's option steps become LegacyStamp's helpers (or are dropped there).
	const STAMP = legacy ? "com.flintclient.qa.legacy.LegacyStamp" : "com.flintclient.qa.QaStamp";
	const step = (line) => {
		if (!legacy) return line;
		const [op, key, value] = line.split(/\s+/);
		if (op !== "option") return line;
		if (key === "framerateLimit") return `call ${STAMP}.maxFps ${value}`;
		if (key === "enableVsync") return `call ${STAMP}.vsync ${value}`;
		if (key === "fullscreen") return `call ${STAMP}.fullscreen ${value}`;
		if (key === "renderDistance") return `render-distance ${value}`;
		return null;
	};
	const header = legacy
		? ["wait-title 300", "sleep 3", "create-world capture default 20261016", "wait-world 300 4", "command time set day", "command weather clear"]
		: ["wait-title 300", "sleep 3", "create-world capture", "wait-world 300 4", "command time set day", "command weather clear", "option inactivityFpsLimit minimized"];
	// Inactivity FPS limit (1.21.2 and newer): AFK would hold a tour without input at 30 FPS after a minute. 1.21.1 has none.
	if (/^1\.21(\.1)?$/.test(mc)) header.pop();
	const steps = ["# capture-test.mjs: generated", ...header, ...(plan.setup ?? []), legacy ? "look 0 5" : "command tp @s ~ ~ ~ 0 5", "sleep 15"];
	let mode = "windowed";
	for (const seg of plan.segments) {
		steps.push(`log seg-begin ${seg.name}`);
		if (seg.mode !== mode) {
			if (mode !== "windowed") steps.push("option fullscreen false", "sleep 3");
			if (seg.mode !== "windowed") {
				if (hasExclusiveOption) steps.push(`option exclusiveFullscreen ${seg.mode === "exclusive"}`);
				steps.push("option fullscreen true", "sleep 4");
			}
			mode = seg.mode;
		}
		steps.push(...(seg.steps ?? []), "sleep 3", `await-signal ${seg.name} 180`, `call ${STAMP}.on`, `frames ${seg.seconds ?? 15} ${seg.name} 45`,
			`call ${STAMP}.dump ${seg.name}`);
	}
	if (mode !== "windowed") steps.push("option fullscreen false", "sleep 2");
	steps.push("quit");
	return steps.map(step).filter((l) => l !== null).join("\n") + "\n";
}

function ps(args) {
	const r = spawnSync("powershell", ["-NoProfile", "-ExecutionPolicy", "Bypass", ...args], { encoding: "utf8", windowsHide: true });
	return (r.stdout || "").trim();
}

function windowAction(pid, action, display) {
	const text = ps(["-File", WINDOW, "-ProcessId", String(pid), "-Action", action, "-Display", display]);
	try {
		return JSON.parse(text.split(/\r?\n/).at(-1));
	} catch {
		return { error: text };
	}
}

function idleSeconds() {
	const text = ps(["-Command", "Add-Type 'using System; using System.Runtime.InteropServices; public static class I { [StructLayout(LayoutKind.Sequential)] struct L { public uint s; public uint t; } [DllImport(\"user32.dll\")] static extern bool GetLastInputInfo(ref L l); public static uint Ms() { var l = new L(); l.s = 8; GetLastInputInfo(ref l); return (uint)Environment.TickCount - l.t; } }'; [I]::Ms()"]);
	return Number(text) / 1000;
}

function gamePid(out) {
	const text = ps(["-Command", `@(Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" | Where-Object { $_.CommandLine -like '*${out.replace(/'/g, "''")}*' } | Select-Object -ExpandProperty ProcessId) -join ','`]);
	return Number(text.split(",")[0]) || null;
}

function startCapture(seg, win, file, ddaOutput) {
	const encode = ["-c:v", "h264_nvenc", "-preset", "p4", "-tune", "ll", "-rc", "cbr", "-b:v", "8M", "-bf", "0", "-g", "240", "-fps_mode", "passthrough", "-f", "nut", file];
	let graph;
	if (seg.capture.startsWith("wgc")) {
		graph = `gfxcapture=hwnd=${win.hwnd}:max_framerate=1000:capture_cursor=0:dup_frames=0:width=-2:height=-2[v]`;
	} else if (seg.capture.startsWith("dda")) {
		const [ml, mt] = win.monitorRect;
		const w = win.width & ~1;
		const h = win.height & ~1;
		graph = `ddagrab=output_idx=${ddaOutput}:framerate=1000:dup_frames=0:draw_mouse=0:offset_x=${win.x - ml}:offset_y=${win.y - mt}:video_size=${w}x${h}[v]`;
	} else {
		return null;
	}
	const proc = spawn(FFMPEG, ["-hide_banner", "-loglevel", "warning", "-y", "-filter_complex", graph, "-map", "[v]", ...encode], { stdio: ["pipe", "ignore", "pipe"], windowsHide: true });
	let err = "";
	proc.stderr.on("data", (d) => (err += d));
	proc.done = new Promise((r) => proc.on("exit", (code) => r({ code, err })));
	return proc;
}

function startGpu(file) {
	const proc = spawn("nvidia-smi", ["--query-gpu=timestamp,utilization.gpu,utilization.encoder,clocks.gr", "--format=csv,noheader,nounits", "-lms", "250"], { stdio: ["ignore", "pipe", "ignore"], windowsHide: true });
	const sink = fs.createWriteStream(file);
	proc.stdout.pipe(sink);
	return proc;
}

async function main() {
	if (opt("analyze", null)) {
		analyze(opt("analyze"));
		return;
	}
	const run = opt("run", null);
	const planFile = opt("plan", null);
	if (!run || !planFile) throw new Error("--plan PLAN.json --run NAME are required");
	const mc = opt("mc", "26.3");
	const display = opt("display", "\\\\.\\DISPLAY2");
	const ddaOutput = opt("dda-output", "1");
	const focusIdle = Number(opt("focus-idle-s", "120"));
	const plan = JSON.parse(fs.readFileSync(planFile, "utf8"));
	// Fullscreen and on-top segments cover the owner's screen: they run only with --allow-on-top, which needs the owner's OK.
	// --borderless-behind: a borderless fullscreen window (26.x's own; Flint's Borderless fullscreen on 1.21.x and 1.8.9, on by
	// default) kept behind the owner's windows.
	const behindOk = (s) => !s.visible && (s.mode === "windowed" || (s.mode === "borderless" && flag("borderless-behind")));
	const onTop = plan.segments.filter((s) => !behindOk(s)).map((s) => s.name);
	if ((onTop.length || flag("visible")) && !flag("allow-on-top")) {
		throw new Error(`segments ${onTop.join(", ") || "(all, --visible)"} would cover the owner's screen: add --allow-on-top only with the owner's OK`);
	}
	const out = path.join(QA_ROOT, "runs", run);
	const game = path.join(QA_ROOT, "games", `cap-${run}`);
	const tour = path.join(QA_ROOT, "runs", `${run}-tour.txt`);
	fs.writeFileSync(tour, tourFor(plan, mc));
	// Dynamic FPS (Flint's performance set) would hold an unfocused window at 1 FPS and a hovered one at 60: off, as the bench does.
	fs.rmSync(game, { recursive: true, force: true });
	fs.mkdirSync(path.join(game, "config"), { recursive: true });
	const unlimited = { frame_rate_target: -1, volume_multipliers: {} };
	fs.writeFileSync(path.join(game, "config", "dynamic_fps.json"), JSON.stringify({ idle: { timeout: 0 }, states: { hovered: unlimited, unfocused: unlimited, abandoned: unlimited } }, null, 2));
	// --shader PACK.zip (with Iris in --mods): a GPU-bound game, as shader players have.
	if (opt("shader", null)) {
		fs.mkdirSync(path.join(game, "shaderpacks"), { recursive: true });
		fs.copyFileSync(opt("shader"), path.join(game, "shaderpacks", path.basename(opt("shader"))));
		fs.writeFileSync(path.join(game, "config", "iris.properties"), `shaderPack=${path.basename(opt("shader"))}\nenableShaders=true\n`);
	}
	// --perf-json JSON: Flint Performance Core's config for this game (as the launcher writes it), e.g. '{"modules":{"framePacing":false}}'.
	if (opt("perf-json", null)) fs.writeFileSync(path.join(game, "config", "flint-performance.json"), opt("perf-json"));
	// --sodium-ahead N: Sodium's CPU render-ahead limit (frames in flight to the GPU; Sodium's default is 3).
	if (opt("sodium-ahead", null)) {
		fs.writeFileSync(path.join(game, "config", "sodium-options.json"), JSON.stringify({ advanced: { cpu_render_ahead_limit: Number(opt("sodium-ahead")) } }, null, 2));
	}
	const runner = mc === "1.8.9" ? "run_qa_189.mjs" : "run_qa_windows.mjs";
	const args = [path.join(QA, runner), tour, run, "--game", `cap-${run}`, "--reuse", "--size", opt("size", "1600x900"), "--timeout", "2400"];
	if (mc !== "1.8.9") args.push("--mc", mc);
	if (opt("mods", null)) args.push("--mods", opt("mods"));
	console.log(`[cap] ${runner} ${run} (${plan.segments.length} segments)`);
	// An earlier run's folder (its game.log) would be taken for this run's before the harness clears it: gone first.
	fs.rmSync(out, { recursive: true, force: true });
	const harness = spawn(process.execPath, args, { stdio: ["ignore", "inherit", "inherit"] });
	let harnessDone = false;
	harness.on("exit", () => (harnessDone = true));
	const logPath = path.join(out, "game.log");
	// The harness clears the run folder first: wait for its fresh game.log, then keep the plan beside the results.
	for (let i = 0; i < 600 && !fs.existsSync(logPath); i++) await sleep(500);
	fs.writeFileSync(path.join(out, "capture-plan.json"), JSON.stringify(plan, null, 2));
	const handled = new Set();
	let pid = null;
	let current = null;
	while (!harnessDone) {
		await sleep(250);
		let text;
		try {
			text = fs.readFileSync(logPath, "utf8");
		} catch {
			continue;
		}
		for (const m of text.matchAll(/QA await-signal (\S+)/g)) {
			const name = m[1];
			if (handled.has(name)) continue;
			handled.add(name);
			const seg = plan.segments.find((s) => s.name === name);
			pid ??= gamePid(out);
			// On top of the owner's windows only when asked (--visible, or a segment's "visible", or exclusive fullscreen) and only
			// when nobody has touched the PC for --focus-idle-s; otherwise behind them (window capture still sees the game).
			const wantsVisible = seg.visible || seg.mode === "exclusive";
			const idleNow = wantsVisible ? idleSeconds() : 0;
			const visible = flag("visible") || (wantsVisible && idleNow >= focusIdle);
			let win = windowAction(pid, seg.mode === "windowed" ? (visible ? "place" : "placeback") : visible ? "top" : "back", display);
			if (seg.mode === "exclusive" && visible) win = windowAction(pid, "front", display);
			if (wantsVisible) win.focusNote = visible ? `visible (PC idle ${Math.round(idleNow)} s)` : `kept behind (PC in use, idle ${Math.round(idleNow)} s)`;
			// gpuPriority (0 idle, 1 below normal, 2 normal): the game's GPU scheduling priority class for this segment.
			const prio = ps(["-File", WINDOW, "-ProcessId", String(pid), "-Action", "gpuprio", "-Priority", String(seg.gpuPriority ?? 2)]);
			await sleep(1500);
			win = { ...windowAction(pid, "info", display), focusNote: win.focusNote, gpuPriority: prio };
			fs.writeFileSync(path.join(out, `capture-${name}.json`), JSON.stringify(win));
			console.log(`[cap] ${name}: ${seg.mode} ${seg.capture} window ${win.width}x${win.height} at ${win.x},${win.y} on ${win.monitor} front=${win.front} ${win.focusNote ?? ""}`);
			const capFile = path.join(out, `cap-${name}.nut`);
			const ff = startCapture(seg, win, capFile, ddaOutput);
			const gpu = startGpu(path.join(out, `gpu-${name}.csv`));
			await sleep(2500);
			current = { name, ff, gpu };
			fs.writeFileSync(path.join(out, `signal-${name}.done`), "go");
		}
		if (current && new RegExp(`stamps ${current.name} frames=`).test(text)) {
			const { name, ff, gpu } = current;
			current = null;
			await sleep(700);
			if (ff) {
				ff.stdin.write("q");
				const r = await Promise.race([ff.done, sleep(15000).then(() => null)]);
				if (!r) ff.kill();
				else if (r.code !== 0) console.log(`[cap] ${name}: ffmpeg exited ${r.code}: ${r.err.trim().slice(-400)}`);
			}
			gpu.kill();
			// Whether the segment ran undisturbed: the window as it started (size, place, mode) and no real click on it meanwhile
			// (the QA mod logs and drops those). A disturbed segment is kept but marked, and the report leaves it out.
			const end = windowAction(pid, "info", display);
			const startInfo = JSON.parse(fs.readFileSync(path.join(out, `capture-${name}.json`), "utf8"));
			const segLog = text.slice(text.lastIndexOf(`QA await-signal ${name}`));
			const clicked = /QA ignored a real mouse/.test(segLog);
			// No window any more: the tour quit after its last segment (not a disturbance).
			const moved = !end.error && (end.width !== startInfo.width || end.height !== startInfo.height || end.x !== startInfo.x || end.y !== startInfo.y || end.style !== startInfo.style);
			startInfo.end = end;
			startInfo.disturbed = clicked || moved ? `${clicked ? "a real click on the game" : ""}${clicked && moved ? "; " : ""}${moved ? "the window changed" : ""}` : null;
			fs.writeFileSync(path.join(out, `capture-${name}.json`), JSON.stringify(startInfo));
			console.log(`[cap] ${name}: captured${startInfo.disturbed ? ` (DISTURBED: ${startInfo.disturbed})` : ""}`);
		}
	}
	if (current?.ff) current.ff.kill();
	current?.gpu.kill();
	analyze(run);
	if (!flag("keep")) for (const f of fs.readdirSync(out)) if (f.startsWith("cap-") && f.endsWith(".nut")) fs.rmSync(path.join(out, f));
	fs.rmSync(game, { recursive: true, force: true });
}

main().catch((e) => {
	console.error("[cap] " + (e.stack ?? e.message));
	process.exit(2);
});
