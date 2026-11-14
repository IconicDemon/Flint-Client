// Dev-only: a stand-in for the Flint app's game link for the in-game Library and Instant Swap (the launcher's
// content/ingame.rs and swap.rs), to check them in real 26.3 games without the launcher: the Library's ops against real
// Modrinth (search, a project's 26.3 Fabric build and its required libraries downloaded into the QA game's folder, removals
// held until the game that loaded them ends) and CurseForge search through the local relay; and the swap: a mod change
// starts the standby game (the QA runner again, same game folder, FLINT_SWAP_STANDBY, idle priority, a mods folder copy
// without what was taken out), which takes over when the first game hands over. The answers have the launcher's shapes.
//
// usage: node qa/tools/swap-link.mjs --game DIR --standby-script FILE --standby-run NAME [--port 18795] [--log FILE]
//        [--size WxH] [--curseforge http://127.0.0.1:4810] [--mc VERSION]
// Prints FLINT_LAUNCHER_LINK and FLINT_LAUNCHER_LINK_SECRET for the first game (and writes them to LOG.env).
import crypto from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const args = process.argv.slice(2);
const option = (name, fallback) => {
	const i = args.indexOf(name);
	return i >= 0 && i + 1 < args.length ? args[i + 1] : fallback;
};
const port = Number(option("--port", "18795"));
const game = option("--game", null);
const standbyScript = option("--standby-script", null);
const standbyRun = option("--standby-run", "swap-standby");
const size = option("--size", null);
const logFile = option("--log", null);
const curseforge = option("--curseforge", "http://127.0.0.1:4810");
// As the launcher (swap.rs): a standby game starts early only when free memory holds its heap (the QA runner's -Xmx3G) and
// 1.5 GB more; otherwise the first game hands over at once and the new one starts after it ends. --prewarm: always early.
const forcePrewarm = args.includes("--prewarm");
const HEAP_MB = 3072;
// FLINT_FFMPEG: an ffmpeg.exe for the frozen-frame video (optional).
const FFMPEG = [process.env.FLINT_FFMPEG].find((p) => p && fs.existsSync(p)) ?? null;
const MARGIN_MB = 1536;
if (!game || !standbyScript) throw new Error("--game and --standby-script are required");
const HERE = path.dirname(fileURLToPath(import.meta.url));
// --mc VERSION: the Minecraft the games run (default 26.3): the Library's searches and downloads are for it, and the standby
// game runs it too (the QA runner's --mc, that line's jars). 1.8.9 games run through run_qa_189.mjs.
const MC = option("--mc", "26.3");
const RUNNER = path.join(HERE, "..", MC === "1.8.9" ? "run_qa_189.mjs" : "run_qa_windows.mjs");
// 1.8.9 runs Fabric Loader through Ornithe: its mods are Modrinth's "ornithe" builds (as the launcher's ornithe.rs takes them).
const MOD_LOADER = MC === "1.8.9" ? "ornithe" : "fabric";
const MODRINTH = "https://api.modrinth.com/v2";
const UA = "flint-dev-qa";
const first = crypto.randomBytes(32).toString("hex");
const MANIFEST = path.join(game, ".qa-library.json");
// Every game this link started keeps its secret (as the launcher's registry does until the game ends): a standby game that
// took over is the profile's game, and asks as the first one did.
const known = new Set([first]);

function log(line) {
	const text = `${new Date().toISOString()} ${line}`;
	console.log(text);
	if (logFile) fs.appendFileSync(logFile, text + "\n");
}

const manifest = () => (fs.existsSync(MANIFEST) ? JSON.parse(fs.readFileSync(MANIFEST, "utf8")) : {});
const saveManifest = (m) => fs.writeFileSync(MANIFEST, JSON.stringify(m, null, 1));
const DIRS = { mod: "mods", resourcepack: "resourcepacks", shader: "shaderpacks" };
const key = (t) => String(t ?? "").toLowerCase().replace(/[^a-z0-9]/g, "");

async function get(url) {
	const r = await fetch(url, { headers: { "User-Agent": UA } });
	if (!r.ok) throw new Error(`${r.status} from ${new URL(url).host}`);
	return r.json();
}

// ---------- the Library ----------

let pending = new Set();

function listed(kind) {
	const dir = path.join(game, DIRS[kind]);
	if (!fs.existsSync(dir)) return [];
	const m = manifest();
	return fs.readdirSync(dir).filter((f) => !f.startsWith(".") && (kind === "mod" ? f.endsWith(".jar") && !f.startsWith("flint-") : f.endsWith(".zip")))
		.filter((f) => !(kind === "mod" && pending.has(f)))
		.map((f) => ({ kind, fileName: f, title: m[f]?.title ?? f.replace(/\.(jar|zip)$/, ""), version: m[f]?.version ?? "", enabled: true, icon: m[f]?.icon ?? null, projectId: m[f]?.projectId ?? null, slug: m[f]?.slug ?? "" }));
}

function state() {
	const mods = listed("mod");
	const packs = listed("resourcepack");
	const shaders = listed("shader");
	return { gameVersion: MC, fabric: true, mods: mods.length, resourcepacks: packs.length, shaders: shaders.length, pendingRemoval: pending.size,
		iris: mods.some((m) => key(m.title).startsWith("iris")), items: [...mods, ...packs, ...shaders] };
}

function haveOf(kind, id, slug, title) {
	return listed(kind).find((i) => i.projectId === id || (i.slug && key(i.slug) === key(slug)) || (kind !== "mod" && key(i.title) === key(title))) ?? null;
}

async function search(r) {
	const kind = r.kind;
	let hits = [];
	let total = 0;
	if (r.source === "curseforge") {
		const cls = { mod: 6, resourcepack: 12, shader: 6552 }[kind];
		let u = `${curseforge}/v1/mods/search/?gameId=432&classId=${cls}&sortField=2&sortOrder=desc&index=${r.offset ?? 0}&pageSize=24&gameVersion=${MC}`;
		if (kind === "mod") u += "&modLoaderType=4";
		if (r.query) u += `&searchFilter=${encodeURIComponent(r.query)}`;
		try {
			const a = await get(u);
			total = a.pagination?.totalCount ?? 0;
			hits = (a.data ?? []).map((m) => ({ id: `cf:${m.id}`, slug: m.slug, title: m.name, author: m.authors?.[0]?.name ?? "", description: m.summary ?? "",
				downloads: Math.round(m.downloadCount ?? 0), icon: m.logo?.thumbnailUrl ?? null }));
		} catch (e) {
			return { hits: [], total: 0, offset: r.offset ?? 0, unavailable: "unavailable" };
		}
	} else {
		const facets = kind === "mod" ? [["project_type:mod"], [`categories:${MOD_LOADER}`], [`versions:${MC}`], ["client_side:required", "client_side:optional"]]
			: kind === "shader" ? [["project_type:shader"], ["categories:iris"], [`versions:${MC}`]] : [["project_type:resourcepack"], [`versions:${MC}`]];
		const index = r.sort ?? "relevance";
		const a = await get(`${MODRINTH}/search?query=${encodeURIComponent(r.query ?? "")}&limit=24&offset=${r.offset ?? 0}&index=${index}&facets=${encodeURIComponent(JSON.stringify(facets))}`);
		total = a.total_hits;
		hits = a.hits.map((h) => ({ id: h.project_id, slug: h.slug, title: h.title, author: h.author, description: h.description, downloads: h.downloads, icon: h.icon_url || null }));
	}
	const wanted = key(r.query);
	if (wanted && !r.offset) {
		const place = (h) => (key(h.title) === wanted || key(h.slug) === wanted ? 0 : key(h.title).startsWith(wanted) || key(h.slug).startsWith(wanted) ? 1 : 2);
		hits.sort((a, b) => place(a) - place(b));
	}
	for (const h of hits) {
		const have = haveOf(kind, h.id, h.slug, h.title);
		h.installed = have ? { fileName: have.fileName, pending: false, enabled: true } : null;
		if (h.icon && !/^https:\/\/(cdn\.modrinth\.com|media\.forgecdn\.net)\//.test(h.icon)) h.icon = null;
	}
	return { hits, total, offset: r.offset ?? 0, unavailable: null };
}

async function bestVersion(id, kind) {
	const loaders = { mod: [MOD_LOADER], resourcepack: ["minecraft"], shader: ["iris"] }[kind];
	const list = await get(`${MODRINTH}/project/${id}/version?loaders=${encodeURIComponent(JSON.stringify(loaders))}&game_versions=${encodeURIComponent(JSON.stringify([MC]))}`);
	return list.find((v) => v.version_type === "release") ?? list[0] ?? null;
}

async function install(r, depth = 0, added = []) {
	if (String(r.id).startsWith("cf:")) throw new Error("This QA link installs from Modrinth only (the launcher does both).");
	const project = await get(`${MODRINTH}/project/${r.id}`);
	if (haveOf(r.kind, project.id, project.slug, project.title)) {
		if (depth === 0) throw new Error(`${project.title} is already in this profile.`);
		return added;
	}
	const v = await bestVersion(project.id, r.kind);
	if (!v) throw new Error(`${project.title} isn't available for Minecraft ${MC}.`);
	for (const dep of v.dependencies ?? []) {
		if (dep.dependency_type === "required" && dep.project_id && depth < 4) await install({ kind: "mod", id: dep.project_id }, depth + 1, added);
	}
	const file = v.files.find((f) => f.primary) ?? v.files[0];
	const dir = path.join(game, DIRS[r.kind]);
	fs.mkdirSync(dir, { recursive: true });
	const target = path.join(dir, file.filename);
	if (!fs.existsSync(target)) {
		const bytes = Buffer.from(await (await fetch(file.url, { headers: { "User-Agent": UA } })).arrayBuffer());
		const sha = crypto.createHash("sha512").update(bytes).digest("hex");
		if (sha !== file.hashes.sha512) throw new Error("hash mismatch for " + file.filename);
		fs.writeFileSync(target + ".tmp", bytes);
		fs.renameSync(target + ".tmp", target);
	}
	const m = manifest();
	m[file.filename] = { projectId: project.id, slug: project.slug, title: project.title, version: v.version_number, icon: project.icon_url, kind: r.kind };
	saveManifest(m);
	added.push({ title: project.title, fileName: file.filename, kind: r.kind });
	return added;
}

// ---------- Instant Swap ----------

let swap = null; // { generation, phase, secret, child, handover, titles, waiters }
let generation = 0;

function changed(title) {
	if (swap?.phase === "go") return;
	generation++;
	if (swap?.child) {
		log(`swap: a newer change; stopping standby ${swap.generation}`);
		try { process.kill(swap.child.pid); } catch {}
	}
	swap = { generation, phase: "settling", secret: null, child: null, handover: null, titles: [...new Set([...(swap?.titles ?? []), title])], waiters: [] };
	const mine = generation;
	setTimeout(() => {
		if (swap?.generation !== mine || swap.phase !== "settling") return;
		const free = Math.round(os.freemem() / 1048576);
		if (!forcePrewarm && free < HEAP_MB + MARGIN_MB) {
			swap.phase = "deferred";
			log(`swap: ${free} MB free, not enough for a second game: the new one starts when this one hands over`);
			return;
		}
		startStandby(mine);
	}, 1500);
}

/** Java processes running a game in this folder. */
function gamesRunning() {
	const r = spawnSync("powershell", ["-NoProfile", "-Command", `@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*--gameDir*${path.basename(game)}*' }).Count`], { encoding: "utf8", windowsHide: true });
	return Number(String(r.stdout).trim()) || 0;
}

function startStandby(gen, handedOver = false) {
	swap.phase = handedOver ? "go" : "warming";
	swap.secret = crypto.randomBytes(32).toString("hex");
	known.add(swap.secret);
	const env = { ...process.env, FLINT_SWAP_STANDBY: handedOver ? "urgent" : "1", FLINT_LAUNCHER_LINK: `127.0.0.1:${port}`, FLINT_LAUNCHER_LINK_SECRET: swap.secret,
		FLINTQA_NO_LOCK: "1", FLINTQA_KEEP_MODS: "1", FLINTQA_PRIORITY: handedOver ? "normal" : "idle" };
	if (pending.size) {
		const copy = path.join(game, ".flint-swap-mods");
		fs.rmSync(copy, { recursive: true, force: true });
		fs.mkdirSync(copy);
		for (const f of fs.readdirSync(path.join(game, "mods"))) {
			if (f.endsWith(".jar") && !pending.has(f)) {
				try { fs.linkSync(path.join(game, "mods", f), path.join(copy, f)); } catch { fs.copyFileSync(path.join(game, "mods", f), path.join(copy, f)); }
			}
		}
		env.FLINTQA_JVM = `${env.FLINTQA_JVM ?? ""} -Dfabric.modsFolder=${copy}`.trim();
	}
	const runner = ["--reuse", "--game", path.basename(game)];
	if (size) runner.push("--size", size);
	if (MC !== "26.3") runner.push("--mc", MC);
	// --standby-script A,B: the first swap's standby game runs A, the next B (the last one for any after).
	const scripts = standbyScript.split(",");
	const script = scripts[Math.min(gen, scripts.length) - 1];
	const child = spawn(process.execPath, [RUNNER, script, `${standbyRun}-${gen}`, ...runner], { env, stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
	child.stdout.on("data", (d) => log(`standby ${gen}: ${String(d).trim()}`));
	child.stderr.on("data", (d) => log(`standby ${gen} (err): ${String(d).trim()}`));
	child.on("exit", (code) => {
		log(`swap: standby ${gen} runner exited ${code}`);
		if (swap?.generation === gen && swap.phase !== "go" && swap.phase !== "done") {
			swap.phase = "failed";
			swap.reason = `Minecraft closed before it was ready (exit ${code})`;
		}
	});
	swap.child = child;
	swap.startedAt = Date.now();
	log(`swap: standby ${gen} starting (pending removals: ${[...pending].join(", ") || "none"})`);
}

function wake() {
	for (const w of swap?.waiters ?? []) w();
	if (swap) swap.waiters = [];
}

// ---------- answering ----------

async function answer(request, who) {
	switch (request.op) {
		case "libraryState": return state();
		case "librarySearch": return search(request);
		case "libraryPlan": return { parts: [] };
		case "libraryInstall": {
			const added = await install(request);
			const root = added[added.length - 1];
			const mod = added.some((a) => a.kind === "mod");
			if (mod) changed(root.title);
			// modFiles: the mod jars put in, libraries first (the launcher's answer), which the game loads live where it can.
			return { fileName: root.fileName, added: added.map((a) => a.title), swap: mod, modFiles: added.filter((a) => a.kind === "mod").map((a) => a.fileName) };
		}
		case "libraryRemove": {
			if (request.kind === "mod") {
				if (request.keep) pending.delete(request.file);
				else pending.add(request.file);
				changed(request.file.replace(/\.jar$/, ""));
				return { removed: true, swap: true };
			}
			fs.rmSync(path.join(game, DIRS[request.kind], request.file), { force: true });
			return { removed: true, swap: false };
		}
		case "libraryArt": {
			const r = await fetch(request.url, { headers: { "User-Agent": UA } });
			const bytes = Buffer.from(await r.arrayBuffer());
			const pngSig = bytes[0] === 0x89 && bytes[1] === 0x50;
			const jpg = bytes[0] === 0xff && bytes[1] === 0xd8;
			const webp = bytes.subarray(0, 4).toString() === "RIFF" && bytes.subarray(8, 12).toString() === "WEBP";
			if (webp && FFMPEG) {
				// As the launcher's partners::game::webp_to_png: a WebP as a PNG at most 96 px (QA: through ffmpeg).
				const r = spawnSync(FFMPEG, ["-v", "error", "-i", "pipe:0", "-vf", "scale='min(96,iw)':-1", "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "pipe:1"], { input: bytes, maxBuffer: 8 << 20, windowsHide: true });
				if (r.status === 0 && r.stdout.length > 8) return { type: "png", data: r.stdout.toString("base64") };
			}
			if (!pngSig && !jpg) return { error: "unsupported picture" };
			return { type: pngSig ? "png" : "jpeg", data: bytes.toString("base64") };
		}
		case "swapStatus": {
			if (swap?.joined) return { state: "taken" };
			if (swap?.phase === "done") return { state: "handing-over" };
			if (!swap) return { state: "none" };
			if (swap.phase === "failed") {
				const out = { state: "failed", reason: swap.reason, titles: swap.titles };
				swap = null;
				return out;
			}
			if (swap.phase === "deferred") return { state: "ready", sequential: true, titles: swap.titles };
			return { state: swap.phase === "ready" ? "ready" : swap.phase === "go" ? "handing-over" : "preparing", sequential: false, titles: swap.titles };
		}
		case "swapGo": {
			if (swap?.phase === "deferred") {
				swap.phase = "go";
				swap.goAt = Date.now();
				swap.handover = { world: request.world ?? null, server: request.server ?? null, window: request.window ?? null };
				const gen = swap.generation;
				log(`swap: sequential handover: ${JSON.stringify(swap.handover)}`);
				// The first game quits now; the new one starts once no game of this folder runs (the world is unlocked).
				(async () => {
					for (let i = 0; i < 90 && gamesRunning() > 0; i++) await new Promise((r) => setTimeout(r, 1000));
					startStandby(gen, true);
				})();
				return { sequential: true };
			}
			if (swap?.phase !== "ready") return { error: "not ready" };
			swap.phase = "go";
			swap.goAt = Date.now();
			swap.handover = { world: request.world ?? null, server: request.server ?? null, window: request.window ?? null };
			log(`swap: handover after ${Math.round((Date.now() - swap.readyAt) / 1000)} s ready: ${JSON.stringify(swap.handover)}`);
			if (swap.child) spawn("powershell", ["-NoProfile", "-Command", `Get-CimInstance Win32_Process -Filter "ParentProcessId=${swap.child.pid}" | ForEach-Object { (Get-Process -Id $_.ProcessId).PriorityClass = "Normal" }`], { windowsHide: true });
			wake();
			return { sequential: false };
		}
		case "swapJoined": {
			if (!swap || !swap.handover) return { error: "bad request" };
			swap.joined = true;
			log(`swap: the new game is in front ${Math.round((Date.now() - swap.goAt) / 100) / 10} s after the handover`);
			return { joined: true };
		}
		case "swapReady": {
			if (who !== "standby") return { error: "bad request" };
			if (swap.phase === "warming") swap.phase = "ready";
			swap.readyAt = Date.now();
			log(`swap: standby ready ${Math.round((swap.readyAt - swap.startedAt) / 1000)} s after it started`);
			return { ready: true };
		}
		case "swapWait": {
			if (who !== "standby") return { error: "bad request" };
			if (swap?.handover) {
				swap.phase = "done";
				swap.child = null;
				return { go: swap.handover };
			}
			await new Promise((resolve) => {
				swap.waiters.push(resolve);
				setTimeout(resolve, Math.min(25_000, request.waitMs ?? 20_000));
			});
			if (swap?.handover) {
				swap.phase = "done";
				swap.child = null;
				return { go: swap.handover };
			}
			return { go: null };
		}
		case "partners": return { partners: [], problem: null };
		case "showcase": return { slides: [] };
		default: return { error: "bad request" };
	}
}

const server = net.createServer((socket) => {
	let line = "";
	socket.setEncoding("utf8");
	socket.on("data", async (data) => {
		line += data;
		const end = line.indexOf("\n");
		if (end < 0) return;
		let reply;
		try {
			const request = JSON.parse(line.slice(0, end));
			line = "\0";
			const who = swap?.secret && request.secret === swap.secret && swap.phase !== "done" ? "standby" : known.has(request.secret) ? "first" : null;
			if (!who) reply = { ok: false, error: "unauthorized" };
			else {
				let body;
				try {
					body = await answer(request, who);
				} catch (e) {
					body = { error: e.message };
				}
				reply = body.error ? { ok: false, error: body.error } : { ok: true, ...body };
				if (!["libraryArt", "swapStatus"].includes(request.op) || !reply.ok) {
					const { secret: _, v, op, ...shown } = request;
					log(`${who} ${op} ${JSON.stringify(shown).slice(0, 160)} -> ${reply.ok ? "ok" : reply.error}`);
				}
			}
		} catch {
			reply = { ok: false, error: "bad request" };
		}
		socket.end(JSON.stringify(reply) + "\n");
	});
	socket.on("error", () => {});
});
server.listen(port, "127.0.0.1", () => {
	console.log(`FLINT_LAUNCHER_LINK=127.0.0.1:${port}`);
	console.log(`FLINT_LAUNCHER_LINK_SECRET=${first}`);
	if (logFile) fs.writeFileSync(logFile + ".env", `FLINT_LAUNCHER_LINK=127.0.0.1:${port}\nFLINT_LAUNCHER_LINK_SECRET=${first}\n`);
	log(`swap link up for ${game}`);
});
