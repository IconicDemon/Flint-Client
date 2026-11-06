// Dev-only: a stand-in for the Flint Store's public cosmetics lookup, on this computer, for checking Store capes in a real game
// without the Store, its database or any account. It answers exactly as the Store does (Store src/lib/server/api.ts
// publicCosmetics: {"players": {"<uuid>": {"cape": {cosmetic_id, kind, asset: {id, version, path, sha256, width, height, url,
// animation}}}}}, Cache-Control: public, max-age=60) and serves the cape texture itself.
//
// usage: node qa/tools/mock-store.mjs [--port 8787] [--cape FILE.png] [--only UUID] [--revoke-after SECONDS] [--bad-hash]
//        [--handoff GAME_DIR] [--equip-after SECONDS] [--animated standard|hd|long|region|region-fast] [--bad-strip]
//        [--region-cape SCALE] [--asset FILE.json]
//
// - Listens on 127.0.0.1 only. Point the game at it with -Dflint.store.baseUrl=http://127.0.0.1:8787 (for QA tours:
//   FLINTQA_JVM="-Dflint.store.baseUrl=http://127.0.0.1:8787" node qa/run_qa_windows.mjs ...). A Store on loopback may be
//   asked about offline-mode ids too, so QA's offline identity gets its cape.
// - --cape: the texture (64x32, or a whole multiple); without it, a bright test cape is made here.
// - --only: only that player wears it (default: everyone asked about).
// - --revoke-after: from that many seconds after the first lookup, nobody wears anything (an unequip or a refund); with
//   --handoff, the file is rewritten with a new equipRevision then, as the launcher does when the player takes a cape off.
// - --bad-hash: the answer names a hash that isn't the file's (the game must refuse the texture).
// - --handoff: plays the launcher's part: writes GAME_DIR/.flint-store.json (launcher docs/STORE-INTEGRATION.md, v1) naming this
//   Store, so the game needs no -D flag; with --equip-after, nobody wears anything until that many seconds after the first
//   lookup, then the cape is "equipped" and the file is rewritten with a new equipRevision, as the launcher does.
// - --animated: an animated cape (Store docs/LAUNCHER-API.md "Animated capes"), made here exactly as the Store's own e2e fixtures
//   (Store scripts/e2e-animated-capes.mjs): every frame its own colours and duration, a transparent and a half-transparent unit
//   on the front, PNG strips of floor(2048 / height) frames, the poster frame 0 at the top left of a 64x32-per-unit texture.
//   standard: 10 frames at 16 px per unit (352x272); hd: 8 frames at 32 (704x544, poster halved); long: 120 frames of 100 ms at
//   16 (more than one cape's frame budget: the game keeps them at 8). --bad-strip serves a strip that isn't its hash (the game
//   must keep the poster).
// - --animated region / region-fast, --region-cape SCALE: the websites' region-coloured cape (Store scripts/e2e-cape-elytra.mjs,
//   regionPixel): front quadrants red, yellow, green, blue with a transparent 2x2 hole at front columns 2-3, rows 5-6; the
//   lining magenta, cyan, orange, purple; every frame turns the hues 45 degrees. region: its 8 frames and durations at 16 px per
//   unit; region-fast: 120 frames in a 4 s loop (about 30 per second, as the Snowfall Cape plays); --region-cape: static.
// - --asset FILE.json: answers with that asset as it is (a real cape's, from the Store's public lookup: its own Supabase
//   addresses), for checking a production cape in game.
// Logs one line per request.
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import zlib from "node:zlib";

const args = process.argv.slice(2);
const option = (name, fallback) => {
	const i = args.indexOf(name);
	return i >= 0 && i + 1 < args.length ? args[i + 1] : fallback;
};
const port = Number(option("--port", "8787"));
const only = option("--only", null)?.toLowerCase();
const revokeAfter = Number(option("--revoke-after", "0"));
const badHash = args.includes("--bad-hash");
const handoffDir = option("--handoff", null);
const equipAfter = Number(option("--equip-after", "0"));
let equipped = equipAfter <= 0;

/** The launcher's handoff, written the way it writes it: a new file renamed over the old. */
function writeHandoff(equipRevision) {
	if (!handoffDir) return;
	const base = `http://127.0.0.1:${port}`;
	const json = JSON.stringify({ schema: 1, contractVersion: 1, storeBaseUrl: base, publicCosmeticsUrl: `${base}/api/v1/players/cosmetics/`,
		equipRevision }, null, 2);
	const file = path.join(handoffDir, ".flint-store.json");
	fs.writeFileSync(file + ".tmp", json);
	fs.renameSync(file + ".tmp", file);
	console.log(`${new Date().toISOString()} handoff written: equipRevision ${equipRevision}`);
}
writeHandoff(0);

function crc32(buf) {
	let c = ~0;
	for (const b of buf) {
		c ^= b;
		for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
	}
	return ~c >>> 0;
}
function chunk(type, data) {
	const len = Buffer.alloc(4);
	len.writeUInt32BE(data.length);
	const td = Buffer.concat([Buffer.from(type), data]);
	const crc = Buffer.alloc(4);
	crc.writeUInt32BE(crc32(td));
	return Buffer.concat([len, td, crc]);
}
/** Minecraft's cape layout (front x1..10, back x12..21, rows 1..16), in loud stripes so it can't be mistaken for another. */
function testCape() {
	const w = 64, h = 32, raw = Buffer.alloc((w * 4 + 1) * h);
	for (let y = 0; y < h; y++) {
		for (let x = 0; x < w; x++) {
			const o = y * (w * 4 + 1) + 1 + x * 4;
			const onCape = x <= 21 && y <= 16;
			const stripe = Math.floor(y / 3) % 2 === 0;
			const rgba = !onCape ? [0, 0, 0, 0] : stripe ? [255, 140, 0, 255] : [20, 20, 160, 255];
			rgba.forEach((v, i) => (raw[o + i] = v));
		}
	}
	const ihdr = Buffer.alloc(13);
	ihdr.writeUInt32BE(w, 0);
	ihdr.writeUInt32BE(h, 4);
	ihdr[8] = 8;
	ihdr[9] = 6;
	return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw)),
		chunk("IEND", Buffer.alloc(0))]);
}

/* ---------- animated capes: Store scripts/e2e-animated-capes.mjs, made the same way ---------- */
function encodePng(w, h, rgba) {
	const raw = Buffer.alloc((w * 4 + 1) * h);
	for (let y = 0; y < h; y++) Buffer.from(rgba.buffer, rgba.byteOffset + y * w * 4, w * 4).copy(raw, y * (w * 4 + 1) + 1);
	const ihdr = Buffer.alloc(13);
	ihdr.writeUInt32BE(w, 0);
	ihdr.writeUInt32BE(h, 4);
	ihdr[8] = 8;
	ihdr[9] = 6;
	return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw)),
		chunk("IEND", Buffer.alloc(0))]);
}
const framesPerStrip = (height) => Math.max(1, Math.floor(2048 / height));
const hsl = (h, s, l) => {
	const k = (n) => (n + h / 30) % 12, a = s * Math.min(l, 1 - l);
	return [0, 8, 4].map((n) => Math.round(255 * (l - a * Math.max(-1, Math.min(k(n) - 3, 9 - k(n), 1)))));
};
function unitColor(i, n, ux, uy) {
	if (uy === 0) return ux >= 1 && ux <= 10 ? [180, 180, 180, 255] : ux >= 11 && ux <= 20 ? [90, 90, 90, 255] : [0, 0, 0, 0];
	if (ux === 0) return [120, 120, 120, 255];
	if (ux === 11) return [140, 140, 140, 255];
	if (ux >= 12) return [...hsl((i * 360) / n + 180, 0.6, 0.35), 255];
	if (ux === 5 && uy === 8) return [0, 0, 0, 0];
	if (ux === 6 && uy === 8) return [255, 255, 255, 128];
	return [...hsl((i * 360) / n, 0.85, 0.5), 255];
}
function syntheticFrame(i, n, scale) {
	const w = 22 * scale, h = 17 * scale, px = new Uint8Array(w * h * 4);
	for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) px.set(unitColor(i, n, Math.floor(x / scale), Math.floor(y / scale)), (y * w + x) * 4);
	return px;
}
/* ---------- the websites' region-coloured cape: Store scripts/e2e-cape-elytra.mjs, regionPixel, the same colours ---------- */
const hsv = (h, sat = 0.85, v = 0.95) => { const f = (n) => { const k = (n + h / 60) % 6; return Math.round(255 * v * (1 - sat * Math.max(0, Math.min(k, 4 - k, 1)))); }; return [f(5), f(3), f(1), 255]; };
const REGION_FRONT = { TL: 0, TR: 55, BL: 125, BR: 228 };
const REGION_BACK = { TL: 300, TR: 180, BL: 30, BR: 262 };
const quadrant = (c, r) => (r < 8 ? (c < 5 ? "TL" : "TR") : c < 5 ? "BL" : "BR");
function regionPixel(ax, ay, k = 0) {
	const x = Math.floor(ax), y = Math.floor(ay), turn = (h) => hsv((h + 45 * k) % 360);
	if (x >= 22 || y >= 17) return [0, 0, 0, 0];
	if (y >= 1 && x >= 1 && x < 11) { const c = x - 1, r = y - 1; return c >= 2 && c < 4 && r >= 5 && r < 7 ? [0, 0, 0, 0] : turn(REGION_FRONT[quadrant(c, r)]); }
	if (y >= 1 && x >= 12) return turn(REGION_BACK[quadrant(x - 12, y - 1)]);
	return [245, 245, 245, 255];
}
function regionFrame(i, n, scale) {
	const w = 22 * scale, h = 17 * scale, px = new Uint8Array(w * h * 4);
	for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) px.set(regionPixel(x / scale, y / scale, i), (y * w + x) * 4);
	return px;
}
const ANIMATIONS = {
	region: { scale: 16, durations: [120, 60, 180, 90, 150, 240, 75, 105], frame: regionFrame },
	'region-fast': { scale: 16, durations: Array.from({ length: 120 }, (_, i) => (i % 3 === 0 ? 34 : 33)), frame: regionFrame },
	standard: { scale: 16, durations: [40, 80, 120, 60, 260, 50, 90, 150, 70, 110] },
	hd: { scale: 32, durations: [60, 120, 90, 150, 45, 260, 80, 100] },
	long: { scale: 16, durations: Array(120).fill(100) },
};
const animatedName = option("--animated", null);
const badStrip = args.includes("--bad-strip");
const files = new Map(); // path on this server -> PNG bytes
let animation = null;
let animatedPoster = null;
if (animatedName) {
	const spec = ANIMATIONS[animatedName];
	if (!spec) throw new Error("--animated standard|hd|long|region|region-fast");
	const { scale, durations } = spec, n = durations.length, w = 22 * scale, h = 17 * scale, per = framesPerStrip(h);
	const strips = [];
	for (let first = 0; first < n; first += per) {
		const count = Math.min(per, n - first), px = new Uint8Array(w * h * count * 4);
		for (let k = 0; k < count; k++) px.set((spec.frame ?? syntheticFrame)(first + k, n, scale), k * w * h * 4);
		const strip = encodePng(w, h * count, px), hash = crypto.createHash("sha256").update(strip).digest("hex");
		files.set(`/test-capes/animated/${hash}.png`, badStrip && first > 0 ? Buffer.concat([strip.subarray(0, strip.length - 1), Buffer.from([0])]) : strip);
		strips.push({ path: `animated/${hash}.png`, sha256: hash, bytes: strip.length, frames: count, url: `http://127.0.0.1:${port}/test-capes/animated/${hash}.png` });
	}
	const s = Math.min(scale, 16), step = scale / s, pw = 64 * s, ph = 32 * s, frame0 = (spec.frame ?? syntheticFrame)(0, n, scale), poster = new Uint8Array(pw * ph * 4);
	for (let y = 0; y < 17 * s; y++) for (let x = 0; x < 22 * s; x++) {
		const o = (y * step * w + x * step) * 4;
		poster.set(frame0.subarray(o, o + 4), (y * pw + x) * 4);
	}
	animatedPoster = encodePng(pw, ph, poster);
	animation = { v: 1, layout: "cape-atlas-22x17", width: w, height: h, scale, frame_count: n, durations_ms: durations,
		duration_ms: durations.reduce((a, d) => a + d, 0), frames_per_strip: per, strips,
		source: { format: "webp", sha256: crypto.createHash("sha256").update(animatedName).digest("hex"), bytes: 4096 } };
}

const capeFile = option("--cape", null);
const regionScale = Number(option("--region-cape", 0));
const regionCape = regionScale ? (() => { const w = 64 * regionScale, h = 32 * regionScale, px = new Uint8Array(w * h * 4);
	for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) px.set(regionPixel(x / regionScale, y / regionScale), (y * w + x) * 4);
	return encodePng(w, h, px); })() : null;
const assetFile = option("--asset", null);
const realAsset = assetFile ? JSON.parse(fs.readFileSync(assetFile, "utf8")) : null;
const png = animatedPoster ?? regionCape ?? (capeFile ? fs.readFileSync(capeFile) : testCape());
const width = png.readUInt32BE(16), height = png.readUInt32BE(20);
const sha256 = badHash ? "0".repeat(64) : crypto.createHash("sha256").update(png).digest("hex");
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
let firstLookup = 0;
let requests = 0;

http.createServer((req, res) => {
	const url = new URL(req.url, `http://127.0.0.1:${port}`);
	const send = (status, body, headers = {}) => {
		res.writeHead(status, headers);
		res.end(body);
	};
	if (req.method === "GET" && url.pathname === "/api/v1/players/cosmetics/") {
		requests++;
		if (!firstLookup) {
			firstLookup = Date.now();
			if (revokeAfter > 0) setTimeout(() => writeHandoff(Date.now()), revokeAfter * 1000);
			if (!equipped) {
				setTimeout(() => {
					equipped = true;
					writeHandoff(Date.now());
				}, equipAfter * 1000);
			}
		}
		const revoked = revokeAfter > 0 && Date.now() - firstLookup >= revokeAfter * 1000;
		const uuids = (url.searchParams.get("uuids") ?? "").split(",").map((s) => s.trim().toLowerCase()).filter((s) => UUID.test(s)).slice(0, 100);
		const players = {};
		for (const id of uuids) {
			if (revoked || !equipped || (only && id !== only)) continue;
			if (realAsset) { players[id] = { cape: { cosmetic_id: "11111111-2222-4333-8444-555555555556", kind: "cape", asset: realAsset } }; continue; }
			players[id] = { cape: { cosmetic_id: "11111111-2222-4333-8444-555555555555", kind: "cape", asset: {
				id: "6f9619ff-8b86-4d01-b42d-00cf4fc964ff", version: 1, path: "/test-capes/qa-cape.png", sha256, width, height,
				url: `http://127.0.0.1:${port}/test-capes/qa-cape.png`, animation } } };
		}
		console.log(`${new Date().toISOString()} lookup #${requests}: ${uuids.length} asked, ${Object.keys(players).length} wearing${revoked ? " (revoked)" : ""}`);
		return send(200, JSON.stringify({ players }), { "Content-Type": "application/json", "Cache-Control": "public, max-age=60" });
	}
	if (req.method === "GET" && url.pathname === "/test-capes/qa-cape.png") {
		console.log(`${new Date().toISOString()} texture ${width}x${height} (${png.length} bytes)`);
		return send(200, png, { "Content-Type": "image/png", "Cache-Control": "public, max-age=31536000, immutable" });
	}
	if (req.method === "GET" && files.has(url.pathname)) {
		const strip = files.get(url.pathname);
		console.log(`${new Date().toISOString()} strip ${url.pathname.slice(-20)} (${strip.length} bytes)`);
		return send(200, strip, { "Content-Type": "image/png", "Cache-Control": "public, max-age=31536000, immutable" });
	}
	console.log(`${new Date().toISOString()} 404 ${req.method} ${url.pathname}`);
	send(404, JSON.stringify({ error: "not_found" }), { "Content-Type": "application/json" });
}).listen(port, "127.0.0.1", () => console.log(`mock Store on http://127.0.0.1:${port} (cape ${width}x${height}, sha256 ${sha256.slice(0, 12)}…${animation ? `, animated: ${animation.frame_count} frames in ${animation.strips.length} strips` : ""})`));
