// Dev-only (Windows QA): packs added by the Flint app while Minecraft runs, checked in a real game. Runs a tour with the QA
// runner (run_qa_windows.mjs, or run_qa_189.mjs for 1.8.9) and, whenever the tour waits on a signal ("await-signal NAME"),
// does what the launcher does at that point through the launcher's own Rust code (app/src-tauri content.rs
// `qa_live_pack_change`: `commit_staged` / `remove_items`, the profile counting as running), then answers the signal.
//
// usage: node qa/tools/live-packs.mjs TOUR --mc VERSION [--mods DIR] [--run NAME] [--size WxH]
//
// - The game folder is %USERPROFILE%\FlintQA\live-packs\root\profiles\lp-<mc> (laid out as Flint's data folder, so the
//   launcher code finds it as profile lp-<mc>), made fresh for each run; results go to FlintQA\runs\live-packs\<mc>.
// - Test packs are generated into FlintQA\live-packs\fixtures\<mc> (no licence questions): resource packs that recolour dirt,
//   grass and stone in Flint's orange, one of another pack format, shader packs that tint the screen (a "final" pass), a data
//   pack with one function.
// - The game files come from the QA cache only (APPDATA points at FlintQA\no-appdata for the runner): the installed app's
//   folder is never read. One game at a time: FlintQA\game.lock is held while the game runs (the 1.8.9 runner holds it itself).
//
// Signals the tours use: rp-on, rp-listed, rp-while-open, rp-incompatible, rp-remove, shader-on, shader-listed, mod-blocked, datapack.
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import zlib from "node:zlib";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const QA = path.resolve(HERE, "..");
const REPO = path.resolve(QA, "..", "..");
const QA_ROOT = path.join(os.homedir(), "FlintQA");
const LOCK = path.join(QA_ROOT, "game.lock");
const LOCK_STALE_MS = 15 * 60 * 1000;
const TAR = path.join(process.env.SystemRoot, "System32", "tar.exe");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (message) => console.log("[live-packs] " + message);

function parseArgs(argv) {
	const args = { positional: [] };
	for (let i = 0; i < argv.length; i++) {
		if (["--mc", "--mods", "--run", "--size"].includes(argv[i])) args[argv[i].slice(2)] = argv[++i];
		else args.positional.push(argv[i]);
	}
	if (args.positional.length !== 1 || !args.mc) throw new Error("usage: live-packs.mjs TOUR --mc VERSION [--mods DIR] [--run NAME] [--size WxH]");
	return args;
}

// ---------- fixtures ----------

/** A PNG of w×h RGBA pixels, `pixel(x, y)` → [r, g, b, a]. */
function png(w, h, pixel) {
	const raw = Buffer.alloc((w * 4 + 1) * h);
	for (let y = 0; y < h; y++) {
		for (let x = 0; x < w; x++) Buffer.from(pixel(x, y)).copy(raw, y * (w * 4 + 1) + 1 + x * 4);
	}
	const chunk = (type, data) => {
		const head = Buffer.alloc(8);
		head.writeUInt32BE(data.length, 0);
		head.write(type, 4, "latin1");
		const crc = Buffer.alloc(4);
		crc.writeUInt32BE(zlib.crc32(Buffer.concat([head.subarray(4), data])) >>> 0, 0);
		return Buffer.concat([head, data, crc]);
	};
	const ihdr = Buffer.alloc(13);
	ihdr.writeUInt32BE(w, 0);
	ihdr.writeUInt32BE(h, 4);
	ihdr.set([8, 6, 0, 0, 0], 8);
	return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}

const checker = (a, b) => (x, y) => ((x >> 2) + (y >> 2)) % 2 ? a : b;
const ORANGE = [0xe0, 0x7a, 0x1f, 255];
const BLACK = [0x16, 0x12, 0x10, 255];
const WHITE = [0xff, 0xff, 0xff, 255];
const GREY = [0x55, 0x55, 0x55, 255];

/** The pack format Minecraft `mc` reads, in its own pack.mcmeta form ("data" for a data pack). */
function packMeta(mc, description, data = false) {
	const formats = {
		"26.3": [97, 121], "26.2": [88, 107], "26.1.2": [84, 101], "26.1.1": [84, 101], "26.1": [84, 101],
		"1.21.11": [75, 94], "1.21.10": [69, 88], "1.21.1": [34, 48], "1.8.9": [1, null],
	};
	const format = formats[mc]?.[data ? 1 : 0];
	if (format == null) throw new Error(`no pack format known for ${mc}`);
	// 1.21.9 and later read min_format/max_format; older ones pack_format.
	const pack = format >= 65 ? { description, min_format: format, max_format: format } : { description, pack_format: format };
	return JSON.stringify({ pack }, null, 2);
}

/** A folder's files zipped as `zip` (bsdtar picks the format from the name), entries relative to the folder. */
function zipDir(dir, zip) {
	fs.rmSync(zip, { force: true });
	const r = spawnSync(TAR, ["-a", "-c", "-f", zip, "-C", dir, ...fs.readdirSync(dir)], { encoding: "utf8" });
	if (r.status !== 0) throw new Error("tar: " + r.stderr);
}

function writeTree(dir, files) {
	fs.rmSync(dir, { recursive: true, force: true });
	for (const [rel, data] of Object.entries(files)) {
		fs.mkdirSync(path.dirname(path.join(dir, rel)), { recursive: true });
		fs.writeFileSync(path.join(dir, rel), data);
	}
}

function fixtures(mc) {
	const out = path.join(QA_ROOT, "live-packs", "fixtures", mc);
	const work = path.join(out, "work");
	fs.mkdirSync(out, { recursive: true });
	const legacy = mc === "1.8.9";
	const blocks = legacy ? "assets/minecraft/textures/blocks/" : "assets/minecraft/textures/block/";
	const names = legacy
		? { dirt: "dirt", side: "grass_side", top: "grass_top", stone: "stone", sand: "sand", gravel: "gravel" }
		: { dirt: "dirt", side: "grass_block_side", top: "grass_block_top", stone: "stone", sand: "sand", gravel: "gravel" };
	const icon = (color) => png(64, 64, (x, y) => (x < 4 || y < 4 || x > 59 || y > 59 ? BLACK : color));
	const tile = (fn) => png(16, 16, fn);

	const packs = {
		"Flint Dirt Tint.zip": {
			"pack.mcmeta": packMeta(mc, "Flint QA: dirt, grass and stone in Flint orange"),
			"pack.png": icon(ORANGE),
			[blocks + names.dirt + ".png"]: tile(checker(ORANGE, BLACK)),
			[blocks + names.side + ".png"]: tile(checker(ORANGE, BLACK)),
			[blocks + names.stone + ".png"]: tile(checker(ORANGE, BLACK)),
			[blocks + names.sand + ".png"]: tile(checker(ORANGE, BLACK)),
			[blocks + names.gravel + ".png"]: tile(checker(ORANGE, BLACK)),
			// Tinted by the biome's grass colour: green and black squares.
			[blocks + names.top + ".png"]: tile(checker(WHITE, GREY)),
		},
		"Flint Blue Stone.zip": {
			"pack.mcmeta": packMeta(mc, "Flint QA: blue stone"),
			"pack.png": icon([0x2f, 0x6f, 0xe0, 255]),
			[blocks + names.stone + ".png"]: tile(checker([0x2f, 0x6f, 0xe0, 255], BLACK)),
		},
		"Flint Gold Gravel.zip": {
			"pack.mcmeta": packMeta(mc, "Flint QA: gold gravel"),
			"pack.png": icon([0xf0, 0xc0, 0x30, 255]),
			[blocks + names.gravel + ".png"]: tile(checker([0xf0, 0xc0, 0x30, 255], BLACK)),
		},
		"Flint Old Format.zip": {
			// Another version's format: Minecraft lists it as incompatible.
			"pack.mcmeta": JSON.stringify({ pack: { description: "Flint QA: another version's pack", pack_format: legacy ? 3 : mc === "1.21.1" ? 99 : 34 } }, null, 2),
			"pack.png": icon([0xd0, 0x30, 0x30, 255]),
			[blocks + names.dirt + ".png"]: tile(checker([0xd0, 0x30, 0x30, 255], BLACK)),
		},
	};
	// The smallest complete pack: the world drawn as textured × vertex colour × light (gbuffers_textured, which terrain, entities
	// and the hand fall back to; gbuffers_basic for untextured lines and sky), then a final pass that tints it and darkens the edges.
	const gbuffer = (textured) => ({
		vsh: `#version 120\n\nvarying vec2 texcoord;\nvarying vec2 lmcoord;\nvarying vec4 glcolor;\n\nvoid main() {\n\tgl_Position = ftransform();\n\ttexcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;\n\tlmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;\n\tglcolor = gl_Color;\n}\n`,
		fsh: `#version 120\n\nuniform sampler2D texture;\nuniform sampler2D lightmap;\nvarying vec2 texcoord;\nvarying vec2 lmcoord;\nvarying vec4 glcolor;\n\nvoid main() {\n\tvec4 color = ${textured ? "texture2D(texture, texcoord) * " : ""}glcolor * texture2D(lightmap, lmcoord);\n\tif (color.a < 0.1) discard;\n\tgl_FragData[0] = color;\n}\n`,
	});
	const shader = (tint) => ({
		"shaders/gbuffers_textured.vsh": gbuffer(true).vsh,
		"shaders/gbuffers_textured.fsh": gbuffer(true).fsh,
		"shaders/gbuffers_basic.vsh": gbuffer(false).vsh,
		"shaders/gbuffers_basic.fsh": gbuffer(false).fsh,
		"shaders/final.vsh": "#version 120\n\nvarying vec2 texcoord;\n\nvoid main() {\n\tgl_Position = ftransform();\n\ttexcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;\n}\n",
		"shaders/final.fsh": `#version 120\n\nuniform sampler2D colortex0;\nvarying vec2 texcoord;\n\nvoid main() {\n\tvec3 color = texture2D(colortex0, texcoord).rgb;\n\tfloat edge = smoothstep(0.35, 0.95, distance(texcoord, vec2(0.5)));\n\tgl_FragColor = vec4(mix(color * vec3(${tint}), vec3(0.05), edge * 0.8), 1.0);\n}\n`,
	});
	if (!legacy) {
		packs["Flint Tint Shader.zip"] = shader("1.0, 0.55, 0.2");
		packs["Flint Green Shader.zip"] = shader("0.4, 1.0, 0.4");
		packs["Flint QA Data.zip"] = {
			"pack.mcmeta": packMeta(mc, "Flint QA: one function", true),
			"data/flint_qa/function/hello.mcfunction": "say Flint live data pack loaded\n",
		};
	}
	for (const [zip, files] of Object.entries(packs)) {
		writeTree(work, files);
		zipDir(work, path.join(out, zip));
	}
	fs.rmSync(work, { recursive: true, force: true });
	// A mod: the launcher refuses it while the game runs, before it is moved anywhere.
	fs.writeFileSync(path.join(out, "flint-qa-mod.jar"), "not a real mod");
	log(`fixtures for ${mc} in ${out}`);
	return out;
}

// ---------- the launcher's side ----------

function plan(fx) {
	const f = (name) => path.join(fx, name);
	return {
		"rp-on": [{ action: "install", kind: "resourcepack", file: f("Flint Dirt Tint.zip"), title: "Flint Dirt Tint", enable: true }],
		"rp-listed": [{ action: "install", kind: "resourcepack", file: f("Flint Blue Stone.zip"), title: "Flint Blue Stone", enable: false }],
		"rp-while-open": [{ action: "install", kind: "resourcepack", file: f("Flint Gold Gravel.zip"), title: "Flint Gold Gravel", enable: true }],
		"rp-incompatible": [{ action: "install", kind: "resourcepack", file: f("Flint Old Format.zip"), title: "Flint Old Format", enable: true }],
		"rp-remove": [{ action: "remove", kind: "resourcepack", file: "Flint Blue Stone.zip" }],
		"shader-on": [{ action: "install", kind: "shader", file: f("Flint Tint Shader.zip"), title: "Flint Tint Shader", enable: true }],
		"shader-listed": [{ action: "install", kind: "shader", file: f("Flint Green Shader.zip"), title: "Flint Green Shader", enable: false }],
		"mod-blocked": [{ action: "install", kind: "mod", file: f("flint-qa-mod.jar"), title: "QA Mod", enable: true }],
		datapack: [{ action: "install", kind: "datapack", file: f("Flint QA Data.zip"), title: "Flint QA Data", world: "LivePacks", enable: true }],
	};
}

/** One launcher change, through content.rs's QA driver. Returns its QA-DRIVER line. */
function launcherChange(root, profile, step) {
	const env = {
		...process.env,
		FLINT_QA_ROOT: root,
		FLINT_QA_PROFILE: profile,
		FLINT_QA_ACTION: step.action,
		FLINT_QA_KIND: step.kind,
		FLINT_QA_FILE: step.file,
		FLINT_QA_TITLE: step.title ?? "",
		FLINT_QA_ENABLE: String(step.enable ?? true),
		FLINT_QA_WORLD: step.world ?? "",
	};
	const r = spawnSync("cargo", ["test", "--lib", "-q", "content::tests::qa_live_pack_change", "--", "--ignored", "--exact", "--nocapture"], {
		cwd: path.join(REPO, "app", "src-tauri"),
		env,
		encoding: "utf8",
	});
	const line = (r.stdout ?? "").split(/\r?\n/).find((l) => l.startsWith("QA-DRIVER ")) ?? `QA-DRIVER {"ok":false,"error":"no answer (exit ${r.status}): ${(r.stderr ?? "").slice(-300)}"}`;
	return line;
}

// ---------- the one-game-at-a-time lock (as run_qa_189.mjs) ----------

let lockHeld = false;
async function acquireLock(run) {
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
			continue;
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

// ---------- the run ----------

async function main() {
	const args = parseArgs(process.argv.slice(2));
	const mc = args.mc;
	const legacy = mc === "1.8.9";
	const tour = path.resolve(args.positional[0]);
	const run = args.run ?? path.join("live-packs", mc);
	const profile = `lp-${mc.replaceAll(".", "")}`;
	const root = path.join(QA_ROOT, "live-packs", "root");
	const game = path.join(root, "profiles", profile);
	const out = path.join(QA_ROOT, "runs", run);
	const fx = fixtures(mc);
	const steps = plan(fx);

	// The launcher's code, built once before the game starts.
	log("building the launcher's QA driver");
	const build = spawnSync("cargo", ["test", "--lib", "--no-run", "-q"], { cwd: path.join(REPO, "app", "src-tauri"), encoding: "utf8" });
	if (build.status !== 0) throw new Error("cargo build failed: " + build.stderr.slice(-2000));

	fs.rmSync(game, { recursive: true, force: true });
	const runner = legacy ? path.join(QA, "run_qa_189.mjs") : path.join(QA, "run_qa_windows.mjs");
	const runnerArgs = [runner, tour, run, "--game", path.relative(path.join(QA_ROOT, "games"), game)];
	if (!legacy) runnerArgs.push("--mc", mc);
	if (args.mods) runnerArgs.push("--mods", args.mods);
	if (args.size) runnerArgs.push("--size", args.size);
	if (!legacy) await acquireLock(run);
	const noAppData = path.join(QA_ROOT, "no-appdata");
	fs.mkdirSync(noAppData, { recursive: true });
	log(`starting: node ${runnerArgs.map((a) => (a.includes(" ") ? `"${a}"` : a)).join(" ")}`);
	const child = spawn(process.execPath, runnerArgs, { cwd: path.resolve(QA, ".."), env: { ...process.env, APPDATA: noAppData }, stdio: ["ignore", "pipe", "pipe"] });
	child.stdout.on("data", (d) => process.stdout.write(d));
	child.stderr.on("data", (d) => process.stderr.write(d));
	let exited = null;
	child.on("exit", (code) => (exited = code ?? 1));
	const answered = new Set();
	const driverLog = [];
	try {
		while (exited === null) {
			await sleep(500);
			const gameLog = path.join(out, "game.log");
			if (!fs.existsSync(gameLog)) continue;
			for (const m of fs.readFileSync(gameLog, "utf8").matchAll(/QA await-signal ([\w-]+)/g)) {
				const name = m[1];
				if (answered.has(name)) continue;
				answered.add(name);
				for (const step of steps[name] ?? []) {
					const line = launcherChange(root, profile, step);
					const entry = `${new Date().toISOString()} ${name}: ${step.action} ${step.kind} ${path.basename(step.file)} enable=${step.enable ?? ""} -> ${line}`;
					log(entry);
					driverLog.push(entry);
					fs.appendFileSync(path.join(out, "launcher.log"), entry + "\n");
				}
				if (!steps[name]) log(`no launcher step for signal ${name}`);
				fs.writeFileSync(path.join(out, `signal-${name}.done`), "1");
			}
		}
	} finally {
		releaseLock();
	}
	// What the launcher wrote for the game, for the record.
	const handoff = path.join(game, ".flint-packs.json");
	if (fs.existsSync(handoff)) fs.copyFileSync(handoff, path.join(out, "flint-packs.json"));
	log(`runner exited ${exited}; launcher steps: ${driverLog.length}`);
	process.exit(exited);
}

process.on("SIGINT", () => {
	releaseLock();
	process.exit(130);
});
main().catch((e) => {
	releaseLock();
	console.error("[live-packs] " + e.message);
	process.exit(2);
});
