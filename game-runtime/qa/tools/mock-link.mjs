// Dev-only: a stand-in for the Flint app's game link (app/src-tauri/src/gamelink.rs) on this computer, for checking Flint's main
// menu features in a real game without the launcher, a Store session or an account: Discover (partners, partnerArt, partnerJoin),
// the showcase (showcase, showcaseArt), the "Join our Discord" card and Friends settings (community, rewardArt, discordLink,
// friends, friendsAction). Every answer has exactly the shape the launcher's Rust code sends (its game views: partners/game.rs,
// store/community_game.rs, showcase.rs, social/game.rs); the pictures are drawn here (blocky partner scenes, 16:9 slides with
// block letters, a Discord-member cape), as PNGs.
//
// usage: node qa/tools/mock-link.mjs [--port 18791] [--joined] [--no-showcase] [--no-partners] [--no-community] [--old-friends]
//        [--join ADDRESS] [--log FILE] [--theme NAME] [--theme-file FILE]
//
// - Listens on 127.0.0.1 only and prints the two variables a game needs (FLINT_LAUNCHER_LINK, FLINT_LAUNCHER_LINK_SECRET); the
//   QA runner passes its environment to the game, so: set both, then `node qa/run_qa_windows.mjs ...`.
// - --joined: the player's Discord account is linked and in Flint's server (the card's calm state, Friends' Linked row).
// - --no-showcase / --no-partners / --no-community: that feature answers nothing (an empty feed): its parts mustn't show.
// - --old-friends: Friends' "me" without lastSeen and gifts (a Friends service before they existed): those two settings hide.
// - --join ADDRESS: what partnerJoin answers (default 127.0.0.1:25599, ~/FlintQA/server's test server).
// - --wardrobe: the Store has items of every kind for the player (cosmetics, equip, emotes, cosmeticArt: store/game.rs's shape plus
//   cosmetics v2's kinds, shop, emote wheel and prices) and the skin changer answers (skinsList, skinAdd, skinArt, skinApply,
//   skinRemove: skin_library.rs's shape). Skins are made here: a username of Notch, jeb_, Dinnerbone, Alex or Steve resolves, any
//   other is "No Minecraft player is called …"; an https address ending in .png gives a skin (one with "big" in it a 128×128
//   picture, refused as Mojang's rules say). skinApply only records the ask: nothing is ever sent to Minecraft.
//   --wardrobe-empty: the Store answers, but the player owns nothing (every category's empty state).
//   --wardrobe-many: as --wardrobe, with 500 capes (a big collection, for the grid's speed).
//   Badges (gamelink's badges and badgeSet, the website's /me badges): with --wardrobe the player owns ["default","staff"] and
//   wears "staff"; badgeSet takes one of them (refusals worded as the launcher words invalid_tag and not_owned). Without a
//   wardrobe flag, badges answers {badges: null, badge: null} (a Store without badges: the game's own marks).
// - --typing-file FILE: while FILE exists, Friends says Alex is typing to the player (social/game.rs's `typing`); a tour writes
//   and deletes it. A chat the game opens has a few messages (social/game.rs's `chat`).
// - --theme NAME: the app's theme `theme` answers (default ember); --theme-file FILE: read from FILE at each request instead (a
//   tour changes "the app's" theme mid-game by writing it), and what `setTheme` writes.
// - --emote-room NAME --emote-accounts FILE [--emote-store URL]: the emote room (launcher social/emote_room.rs) for real, as
//   account NAME of FILE ([{username, uuid, friends_token, …}], the website's local demo accounts): emoteRoom opens the
//   Store's room (default ws://[::1]:3200) for the server the game joined, with that account's Friends token; emoteSend and
//   emoteEvents go through it as the launcher's do. cosmetics lists the account's Demo Wave on the wheel, with its model;
//   modelFile gives its animation (--emote-file FILE, checked against --emote-sha256 when given).
// Logs one line per request (op and its fields, never the secret).
import crypto from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import zlib from "node:zlib";

const args = process.argv.slice(2);
const option = (name, fallback) => {
	const i = args.indexOf(name);
	return i >= 0 && i + 1 < args.length ? args[i + 1] : fallback;
};
const flag = (name) => args.includes(name);
const port = Number(option("--port", "18791"));
const joined = flag("--joined");
const joinAddress = option("--join", "127.0.0.1:25599");
const logFile = option("--log", null);
const themeFile = option("--theme-file", null);
let theme = option("--theme", "ember");
const THEMES = ["ember", "icecream", "moss", "bloodstone", "dusk", "aurora"];
const appTheme = () => {
	if (themeFile) {
		try {
			const read = fs.readFileSync(themeFile, "utf8").trim();
			if (THEMES.includes(read)) return read;
		} catch {
			// No file yet: the --theme default.
		}
	}
	return theme;
};
const secret = crypto.randomBytes(32).toString("hex");

function log(line) {
	const text = `${new Date().toISOString()} ${line}`;
	console.log(text);
	if (logFile) fs.appendFileSync(logFile, text + "\n");
}

// ---------- PNG ----------

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
function png(w, h, px) {
	const raw = Buffer.alloc((w * 4 + 1) * h);
	for (let y = 0; y < h; y++) Buffer.from(px.buffer, y * w * 4, w * 4).copy(raw, y * (w * 4 + 1) + 1);
	const ihdr = Buffer.alloc(13);
	ihdr.writeUInt32BE(w, 0);
	ihdr.writeUInt32BE(h, 4);
	ihdr[8] = 8;
	ihdr[9] = 6;
	return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}

// ---------- drawing ----------

const hsl = (h, s, l) => {
	h = ((h % 360) + 360) % 360;
	const k = (n) => (n + h / 30) % 12, a = s * Math.min(l, 1 - l);
	return [0, 8, 4].map((n) => Math.round(255 * (l - a * Math.max(-1, Math.min(k(n) - 3, 9 - k(n), 1)))));
};
function random(seed) {
	let h = 2166136261;
	for (const c of seed) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
	return () => {
		h = Math.imul(h ^ (h >>> 15), 2246822507);
		h = Math.imul(h ^ (h >>> 13), 3266489909);
		return ((h ^= h >>> 16) >>> 0) / 4294967296;
	};
}
function canvas(w, h) {
	const px = new Uint8Array(w * h * 4);
	const put = (x, y, [r, g, b], a = 1) => {
		if (x < 0 || y < 0 || x >= w || y >= h) return;
		const o = (y * w + x) * 4;
		px[o] = Math.round(px[o] * (1 - a) + r * a);
		px[o + 1] = Math.round(px[o + 1] * (1 - a) + g * a);
		px[o + 2] = Math.round(px[o + 2] * (1 - a) + b * a);
		px[o + 3] = 255;
	};
	const rect = (x0, y0, rw, rh, c, a = 1) => {
		for (let y = Math.max(0, y0); y < Math.min(h, y0 + rh); y++) for (let x = Math.max(0, x0); x < Math.min(w, x0 + rw); x++) put(x, y, c, a);
	};
	return { w, h, px, put, rect };
}
/** A blocky Minecraft-ish scene: a sky in the listing's hue, a square sun with a glow, stepped ridges, stars at night. */
function scene(seed, hue, w, h, night) {
	const c = canvas(w, h);
	const rand = random(seed);
	for (let y = 0; y < h; y++) {
		const t = y / h;
		const col = hsl(hue + 200 * (1 - t), 0.45 + t * 0.15, night ? 0.05 + t * 0.2 : 0.1 + t * 0.32);
		c.rect(0, y, w, 1, col);
	}
	if (night) for (let i = 0; i < 90; i++) c.rect(Math.floor(rand() * w), Math.floor(rand() * h * 0.5), 3, 3, [255, 255, 255], 0.3 + rand() * 0.6);
	const gx = Math.floor(w * (0.6 + rand() * 0.25)), gy = Math.floor(h * 0.3);
	const glowR = h * 0.55, glow = hsl(hue + 25, 0.9, 0.62);
	for (let y = Math.max(0, Math.floor(gy - glowR)); y < Math.min(h, gy + glowR); y++) for (let x = Math.max(0, Math.floor(gx - glowR)); x < Math.min(w, gx + glowR); x++) {
		const d = Math.hypot(x - gx, y - gy) / glowR;
		if (d < 1) c.put(x, y, glow, 0.55 * (1 - d) * (1 - d));
	}
	const sun = Math.floor(h * 0.09);
	c.rect(gx - sun, gy - sun, 2 * sun, 2 * sun, hsl(hue + 35, 1, night ? 0.9 : 0.8));
	for (let layer = 0; layer < 3; layer++) {
		const cell = Math.floor(h / 22) * (layer + 1);
		let y = h * (0.52 + layer * 0.13);
		const amp = h * 0.1 * (1 - layer * 0.2);
		const col = hsl(hue + 200 - layer * 10, 0.3 - layer * 0.05, Math.max(0.04, 0.16 - layer * 0.05));
		for (let x = 0; x < w; x += cell) {
			y += (rand() - 0.5) * amp * 0.6;
			y = Math.max(h * (0.52 + layer * 0.13) - amp, Math.min(h * (0.52 + layer * 0.13) + amp, y));
			const top = Math.round(y / (cell / 2)) * (cell / 2);
			c.rect(x, top, cell, h - top, col, 0.85 + layer * 0.05);
		}
	}
	for (let y = Math.floor(h * 0.55); y < h; y++) c.rect(0, y, w, 1, [0, 0, 0], ((y - h * 0.55) / (h * 0.45)) * 0.35);
	return c;
}
// A 5×7 block font for the slides' headlines.
const FONT = {
	A: "01110 10001 10001 11111 10001 10001 10001", B: "11110 10001 11110 10001 10001 10001 11110", C: "01111 10000 10000 10000 10000 10000 01111",
	D: "11110 10001 10001 10001 10001 10001 11110", E: "11111 10000 11110 10000 10000 10000 11111", F: "11111 10000 11110 10000 10000 10000 10000",
	G: "01111 10000 10000 10111 10001 10001 01111", H: "10001 10001 11111 10001 10001 10001 10001", I: "11111 00100 00100 00100 00100 00100 11111",
	J: "00111 00010 00010 00010 00010 10010 01100", K: "10001 10010 11100 10010 10001 10001 10001", L: "10000 10000 10000 10000 10000 10000 11111",
	M: "10001 11011 10101 10001 10001 10001 10001", N: "10001 11001 10101 10011 10001 10001 10001", O: "01110 10001 10001 10001 10001 10001 01110",
	P: "11110 10001 10001 11110 10000 10000 10000", R: "11110 10001 10001 11110 10010 10001 10001", S: "01111 10000 01110 00001 00001 00001 11110",
	T: "11111 00100 00100 00100 00100 00100 00100", U: "10001 10001 10001 10001 10001 10001 01110", V: "10001 10001 10001 10001 01010 01010 00100",
	W: "10001 10001 10001 10101 10101 11011 10001", X: "10001 01010 00100 00100 00100 01010 10001", Y: "10001 01010 00100 00100 00100 00100 00100",
	Z: "11111 00010 00100 01000 10000 10000 11111", 0: "01110 10011 10101 10101 11001 10001 01110", 1: "00100 01100 00100 00100 00100 00100 01110",
	2: "01110 10001 00001 00110 01000 10000 11111", 3: "11110 00001 00110 00001 00001 00001 11110", 4: "10010 10010 10010 11111 00010 00010 00010",
	5: "11111 10000 11110 00001 00001 00001 11110", 6: "01110 10000 11110 10001 10001 10001 01110", "%": "11001 11010 00010 00100 01000 01011 10011",
	"!": "00100 00100 00100 00100 00100 00000 00100", "-": "00000 00000 00000 11111 00000 00000 00000", " ": "00000 00000 00000 00000 00000 00000 00000",
};
function text(c, words, x, y, scale, col, a = 1) {
	let cx = x;
	for (const ch of words) {
		const rows = (FONT[ch] ?? FONT[" "]).split(" ");
		rows.forEach((row, ry) => [...row].forEach((bit, rx) => bit === "1" && c.rect(cx + rx * scale, y + ry * scale, scale, scale, col, a)));
		cx += 6 * scale;
	}
}
const textWidth = (words, scale) => words.length * 6 * scale - scale;

// ---------- what the app answers ----------

const art = new Map();
function keep(bytes) {
	const sha = crypto.createHash("sha256").update(bytes).digest("hex");
	art.set(sha, bytes);
	return sha;
}
function banner(slug, hue, night) {
	const c = scene(`${slug}:banner`, hue, 1600, 600, night);
	return keep(png(c.w, c.h, c.px));
}
function logo(letter, hue) {
	const c = canvas(256, 256);
	c.rect(0, 0, 256, 256, hsl(hue, 0.55, 0.32));
	c.rect(16, 16, 224, 224, hsl(hue, 0.6, 0.4));
	text(c, letter, 128 - 30 * 1 + 2, 128 - 35, 12, [255, 255, 255]);
	return keep(png(c.w, c.h, c.px));
}
const UUID = (n) => `7a1e0001-5c2d-4b8e-9f10-${String(n).padStart(12, "0")}`;
const partner = (n, name, kind, label, summary, hue, night, extra = {}) => ({
	id: UUID(n), name, kind, label, summary, featured: n === 1, address: null, edition: null, joinable: false, versions: null, recommended: null,
	stats: null, members: null, membersOnline: null, banner: { sha: banner(name, hue, night), width: 1600, height: 600 },
	logo: { sha: logo(name[0], hue) }, links: {}, primary: { kind: "open_url", label: null, url: null }, ...extra,
});
const fresh = (players, max) => ({ online: true, players, max });
const PARTNERS = flag("--no-partners") ? [] : [
	partner(1, "Emberfall Network", "minecraft_server", "Partner", "Survival, Bedwars and seasonal events on one network, with a friendly community and weekly updates.", 22, false, {
		address: "play.emberfall.example", edition: "java", joinable: true, versions: "1.8.9 – 26.3", recommended: "1.21.11", stats: fresh(1284, 5000),
		links: { website: "https://emberfall.example/", discord: "https://discord.gg/emberfall", store: "https://store.emberfall.example/" },
		primary: { kind: "join_server", label: "Play", url: null } }),
	partner(2, "Skyreach Isles", "minecraft_server", "Sponsored", "Skyblock with custom islands, a player market and co-op challenges every weekend.", 200, true, {
		address: "mc.skyreach.example", edition: "java", joinable: true, versions: "1.21.11+", stats: fresh(612, 2000),
		links: { website: "https://skyreach.example/", youtube: "https://www.youtube.com/@skyreach" }, primary: { kind: "join_server", label: null, url: null } }),
	partner(3, "Glasswing Games", "minecraft_server", "Partner", "Minigames for every mood: parkour, build battles and a weekly tournament.", 280, true, {
		address: "glasswing.example:25570", edition: "java", joinable: false, versions: "1.8.9", stats: { online: false, players: null, max: null },
		links: { website: "https://glasswing.example/" }, primary: { kind: "join_server", label: null, url: null } }),
	partner(4, "Redstone Guild", "discord", "Partner", "Builders and redstone engineers sharing farms, contraptions and world downloads.", 350, false, {
		members: 18240, membersOnline: 2210, links: { discord: "https://discord.gg/redstoneguild" },
		primary: { kind: "open_url", label: "Join Discord", url: "https://discord.gg/redstoneguild" } }),
	partner(5, "BlockHost", "hosting", "Sponsored", "Minecraft servers that start in seconds, with backups and mod installs built in.", 140, true, {
		links: { website: "https://blockhost.example/" }, primary: { kind: "open_url", label: "Visit website", url: "https://blockhost.example/" } }),
];
function slide(id, title, top, bottom, hue, night) {
	const c = scene(`slide:${id}`, hue, 960, 540, night);
	for (let x = 0; x < 640; x++) c.rect(x, 0, 1, 540, [0, 0, 0], 0.5 * Math.pow(1 - x / 640, 1.5));
	text(c, top, 48, 150, 9, [255, 255, 255]);
	text(c, bottom, 48, 150 + 9 * 10, 6, hsl(hue + 30, 0.95, 0.68));
	c.rect(48, 360, 210, 54, hsl(hue + 25, 0.95, 0.55));
	text(c, "PLAY NOW", 48 + (210 - textWidth("PLAY NOW", 4)) / 2, 360 + 13, 4, [24, 14, 4]);
	return { id, title, alt: title, link: `https://flintclient.com/showcase/${id}`, width: 960, height: 540, weight: 10, endsAt: null, png: png(c.w, c.h, c.px) };
}
const SLIDES = flag("--no-showcase") ? [] : [
	slide("season-4", "Emberfall Season 4 is live", "SEASON 4", "EMBERFALL", 22, false),
	slide("skyreach-x-flint", "Skyreach Isles x Flint", "SKYREACH", "FREE FLINT CAPE", 200, true),
	slide("build-battle", "Build Battle weekend", "BUILD BATTLE", "THIS WEEKEND", 280, true),
];
SLIDES[1] && (SLIDES[1].link = null);
/** The Discord member cape: Discord's blurple with a white emblem on its front (Minecraft's 64×32 cape layout). */
function memberCape() {
	const c = canvas(64, 32);
	c.px.fill(0);
	for (let y = 0; y <= 16; y++) for (let x = 0; x <= 21; x++) {
		const front = x >= 1 && x <= 10 && y >= 1;
		const base = front ? hsl(235, 0.82, 0.62 - y * 0.012) : hsl(235, 0.6, 0.38);
		c.put(x, y, base);
	}
	// A white emblem on the front: two eyes and a smile, Discord's own shape in blocks.
	for (const [x, y] of [[3, 4], [4, 4], [7, 4], [8, 4], [3, 5], [8, 5], [3, 6], [4, 6], [5, 6], [6, 6], [7, 6], [8, 6], [3, 7], [8, 7]]) c.put(x, y, [255, 255, 255]);
	for (const [x, y] of [[4, 5], [7, 5]]) c.put(x, y, hsl(235, 0.82, 0.55));
	for (let x = 1; x <= 10; x++) c.put(x, 14, hsl(32, 0.95, 0.55));
	return png(64, 32, c.px);
}
const CAPE_ID = "b2d1c0de-4c0d-4e5a-9b1e-00000000d15c";
const CAPE = memberCape();
const community = () => flag("--no-community") ? { state: "off", enabled: false, discordEnabled: false, canLink: false, invite: "https://discord.gg/flintclient", discord: null, standing: null, rewards: [] } : {
	state: "ok", enabled: true, discordEnabled: true, canLink: true, invite: "https://discord.gg/flintclient",
	discord: joined ? { username: "flintqa", displayName: "Flint QA", member: true, booster: false, guild: "verified", linkedAt: "2026-11-01T12:00:00Z" } : null,
	standing: { linked: joined, member: joined, booster: false, pending: false, state: joined ? "verified" : "disconnected" },
	rewards: [{ id: CAPE_ID, kind: "cape", name: "Discord Member Cape", requirement: "discord_member", label: "Member of the Flint Discord server",
		permanence: "conditional", status: joined ? "eligible" : "locked", animated: false, art: 1 }],
};
const me = (uuid) => flag("--old-friends") ? { uuid, name: "FlintQA", status: "online", allowRequests: true }
	: { uuid, name: "FlintQA", status: "online", allowRequests: true, lastSeen: settings.lastSeen, gifts: settings.gifts };
const settings = { lastSeen: "friends", gifts: "everyone" };

// ---------- the wardrobe (--wardrobe): Store items of every kind, the emote wheel, the skin changer ----------

const wardrobe = flag("--wardrobe") || flag("--wardrobe-empty") || flag("--wardrobe-many");
const BADGE_TAGS = ["default", "owner", "co_owner", "developer", "staff", "content_creator", "trial_creator", "vip", "special"];
const ownedBadges = flag("--wardrobe-empty") ? ["default"] : ["default", "staff"];
let badge = ownedBadges[ownedBadges.length - 1];
const ITEM = (n) => `5ca1ab1e-0000-4000-8000-${String(n).padStart(12, "0")}`;
/** A cape (Minecraft's 64×32 layout): its front in a gradient of the hue, a band and a mark. */
function capeArt(hue) {
	const c = canvas(64, 32);
	c.px.fill(0);
	for (let y = 0; y <= 16; y++) for (let x = 0; x <= 21; x++) c.put(x, y, x >= 1 && x <= 10 && y >= 1 ? hsl(hue, 0.75, 0.6 - y * 0.018) : hsl(hue, 0.5, 0.3));
	for (let x = 1; x <= 10; x++) c.put(x, 12, hsl(hue + 40, 0.9, 0.7));
	for (const [x, y] of [[5, 5], [6, 5], [4, 6], [7, 6], [5, 7], [6, 7]]) c.put(x, y, [255, 255, 255]);
	return png(64, 32, c.px);
}
/** A 3D item's thumbnail stand-in (the Store's is 512×512): a soft disc of the hue with a blocky shape of its kind. */
function thumbArt(kind, hue) {
	const S = 128, c = canvas(S, S);
	c.px.fill(0);
	for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
		const d = Math.hypot(x - S / 2, y - S / 2) / (S / 2);
		if (d < 1) c.put(x, y, hsl(hue, 0.5, 0.12 + 0.1 * (1 - d)), 0.9);
	}
	const fg = hsl(hue, 0.85, 0.62), hi = hsl(hue + 30, 0.95, 0.78);
	const shapes = {
		wings: () => { for (let i = 0; i < 6; i++) { c.rect(20 + i * 5, 30 + i * 6, 34 - i * 5, 8, fg); c.rect(74, 30 + i * 6, 34 - i * 5, 8, fg); } c.rect(58, 36, 12, 50, hi); },
		headwear: () => { c.rect(34, 70, 60, 12, fg); c.rect(46, 40, 36, 32, fg); c.rect(46, 60, 36, 6, hi); },
		bodywear: () => { c.rect(40, 34, 48, 60, fg); c.rect(24, 34, 16, 34, fg); c.rect(88, 34, 16, 34, fg); c.rect(58, 34, 12, 60, hi); },
		pet: () => { c.rect(40, 50, 48, 32, fg); c.rect(80, 34, 22, 22, fg); c.rect(44, 82, 8, 14, fg); c.rect(76, 82, 8, 14, fg); c.rect(92, 40, 4, 4, [20, 20, 20]); },
		aura: () => { for (let a = 0; a < 24; a++) { const t = (a / 24) * Math.PI * 2; c.rect(Math.round(64 + Math.cos(t) * 40) - 4, Math.round(64 + Math.sin(t) * 40) - 4, 8, 8, a % 2 ? fg : hi); } },
		shield: () => { c.rect(38, 28, 52, 52, fg); c.rect(46, 80, 36, 12, fg); c.rect(56, 92, 16, 8, fg); c.rect(60, 32, 8, 60, hi); },
		gloves: () => { c.rect(30, 50, 26, 34, fg); c.rect(72, 50, 26, 34, fg); c.rect(30, 40, 6, 12, fg); c.rect(92, 40, 6, 12, fg); },
		emote: () => { c.rect(40, 40, 48, 48, fg); c.rect(52, 54, 6, 6, [20, 20, 20]); c.rect(70, 54, 6, 6, [20, 20, 20]); c.rect(52, 72, 24, 6, [20, 20, 20]); },
	};
	(shapes[kind] ?? (() => c.rect(44, 44, 40, 40, fg)))();
	return png(S, S, c.px);
}
const day = 86_400_000, now0 = Date.parse("2026-11-11T12:00:00Z");
const ITEMS = flag("--wardrobe-empty") ? [] : [
	[1, "cape", "Ember Cape", 22, true], [2, "cape", "Frost Cape", 200, true], [3, "cape", "Verdant Cape", 120, true], [4, "cape", "Midnight Cape", 260, true],
	[10, "wings", "Ember Wings", 22, true], [11, "wings", "Glass Wings", 190, true], [12, "wings", "Moth Wings", 40, false],
	[20, "headwear", "Little Crown", 48, true], [21, "headwear", "Halo", 55, false],
	[30, "bodywear", "Explorer Scarf", 0, true], [31, "bodywear", "Backpack", 30, false],
	[40, "pet", "Shoulder Fox", 25, true], [41, "pet", "Tiny Axolotl", 320, false],
	[50, "aura", "Ember Ring", 15, true], [51, "aura", "Starfall", 230, false],
	[60, "shield", "Oak Guard", 35, true],
	[70, "gloves", "Ember Gloves", 18, true],
	[80, "emote", "Wave", 50, true], [81, "emote", "Dance", 300, true], [82, "emote", "Clap", 140, true], [83, "emote", "Bow", 200, false],
	[90, "jetpack", "Jet Pack", 210, false],
].map(([n, kind, name, hue, art], i) => ({ n, kind, name, hue, art, addedAt: now0 - i * day }));
// --wardrobe-many: 500 capes in all (a big collection: the grid must stay smooth and open at once).
if (flag("--wardrobe-many")) for (let k = 0; ITEMS.filter((i) => i.kind === "cape").length < 500; k++) {
	ITEMS.push({ n: 1000 + k, kind: "cape", name: `Test Cape ${String(k + 1).padStart(3, "0")}`, hue: (k * 37) % 360, art: true, addedAt: now0 - (30 + k) * day });
}
const SHOP = flag("--wardrobe-empty") ? [] : [
	[100, "cape", "Aurora Cape", 160, 500], [101, "cape", "Copper Cape", 30, 350], [110, "wings", "Phoenix Wings", 10, 1200],
	[120, "headwear", "Wizard Hat", 270, 600], [140, "pet", "Baby Dragon", 0, 1500], [150, "aura", "Snow Globe", 200, 800], [180, "emote", "Backflip", 50, 400],
	// The Flint Discord rewards, as the app sends them: earned, not sold (no price), with how they unlock.
	[190, "cape", "Flint Bound", 230, null, "discord_member"], [191, "cape", "Overcharged", 320, null, "discord_booster"],
].map(([n, kind, name, hue, price, unlock]) => ({ n, kind, name, hue, price, unlock }));
const worn = flag("--wardrobe-empty") ? {} : { cape: ITEM(1), back: ITEM(10) };
let wheel = flag("--wardrobe-empty") ? [null, null, null, null, null, null, null, null] : [ITEM(80), null, ITEM(81), null, null, null, null, null];
let invVersion = 40;
const SLOT_OF = { cape: "cape", wings: "back", headwear: "head", bodywear: "body", aura: "aura", pet: "pet", shield: "shield", gloves: "hands" };
function cosmetics() {
	return {
		storeUrl: "https://store.flintclient.com", name: "FlintQA", stale: false, problem: null, fetchedAt: Date.now(), version: invVersion,
		items: ITEMS.map((i) => ({ id: ITEM(i.n), slug: i.name.toLowerCase().replace(/ /g, "-"), kind: i.kind, slot: SLOT_OF[i.kind] ?? null, name: i.name,
			gift: i.n === 3, giftFrom: i.n === 3 ? "Alex" : null, test: false, art: i.art ? 1 : null, addedAt: i.addedAt })),
		shop: SHOP.map((i) => ({ id: ITEM(i.n), kind: i.kind, name: i.name, priceCoins: i.price, art: 1, ...(i.unlock ? { source: "reward", unlock: i.unlock } : { source: "store" }) })),
		equipped: { ...worn }, emotes: [...wheel],
	};
}
function cosmeticArt(id) {
	// The Store's others (the shop) have their pictures too, as the launcher's catalog thumbnails would be.
	const i = ITEMS.find((x) => ITEM(x.n) === id) ?? SHOP.map((x) => ({ ...x, art: true })).find((x) => ITEM(x.n) === id);
	if (!i || !i.art) return { error: "This item has no texture." };
	return { png: (i.kind === "cape" ? capeArt(i.hue) : thumbArt(i.kind, i.hue)).toString("base64") };
}
/** A skin in Minecraft's 64×64 layout, in the colours a name gives (slim: the outer arm columns left empty). */
function skinPng(seed, { size = 64, slim = false } = {}) {
	const c = canvas(size, size);
	c.px.fill(0);
	const r = random(seed), hue = Math.floor(r() * 360);
	const tone = hsl(25 + r() * 15, 0.45, 0.45 + r() * 0.25), shirt = hsl(hue, 0.6, 0.45), pants = hsl(hue + 180, 0.35, 0.3), hair = hsl(hue + 90, 0.4, 0.2);
	c.rect(0, 0, 32, 16, tone);
	c.rect(8, 0, 16, 8, hair); c.rect(0, 8, 8, 3, hair); c.rect(8, 8, 8, 2, hair); c.rect(16, 8, 8, 3, hair); c.rect(24, 8, 8, 3, hair);
	c.rect(9, 12, 2, 1, [255, 255, 255]); c.rect(13, 12, 2, 1, [255, 255, 255]); c.rect(10, 12, 1, 1, hsl(hue, 0.8, 0.4)); c.rect(13, 12, 1, 1, hsl(hue, 0.8, 0.4));
	c.rect(11, 14, 2, 1, [120, 60, 50]);
	c.rect(16, 16, 24, 16, shirt); c.rect(40, 16, 16, 16, shirt); c.rect(32, 48, 16, 16, shirt);
	c.rect(40, 28, 16, 4, tone); c.rect(32, 60, 16, 4, tone);
	c.rect(0, 16, 16, 16, pants); c.rect(16, 48, 16, 16, pants);
	c.rect(0, 28, 16, 4, [40, 40, 40]); c.rect(16, 60, 16, 4, [40, 40, 40]);
	if (slim) for (let y = 20; y < 32; y++) for (const x of [54, 55]) { const o = (y * size + x) * 4; c.px[o + 3] = 0; }
	return png(size, size, c.px);
}
const PLAYERS = { notch: ["Notch", false], jeb_: ["jeb_", false], dinnerbone: ["Dinnerbone", false], alex: ["Alex", true], steve: ["Steve", false] };
const skinFiles = new Map();
let skinList = [];
const applied = [];
function keepSkin(bytes, entry) {
	const id = crypto.createHash("sha256").update(bytes).digest("hex");
	skinFiles.set(id, bytes);
	const skin = { id, legacy: false, addedAt: Date.now(), ...entry };
	skinList = [skin, ...skinList.filter((s) => s.id !== id)].slice(0, 60);
	return skin;
}
if (wardrobe && !flag("--wardrobe-empty") && !flag("--no-skins")) keepSkin(skinPng("Steve"), { name: "Steve", source: "username", origin: "Steve", variant: "classic" });
function skinAdd(request) {
	if (request.username != null) {
		const name = String(request.username).trim();
		if (!/^[A-Za-z0-9_]{1,25}$/.test(name)) return { error: "A Minecraft username has only letters, digits and _ (up to 16 characters)." };
		const known = PLAYERS[name.toLowerCase()];
		if (!known) return { error: `No Minecraft player is called ${name}.` };
		const skin = keepSkin(skinPng(known[0], { slim: known[1] }), { name: known[0], source: "username", origin: known[0], variant: known[1] ? "slim" : "classic" });
		return { skin, skins: skinList };
	}
	if (request.url != null) {
		const url = String(request.url).trim();
		if (!/^https:\/\//i.test(url)) return { error: "Use the picture's https:// address." };
		if (!/\.png$/i.test(url)) return { error: "That address isn't a PNG picture. A Minecraft skin is a 64×64 (or old 64×32) PNG." };
		if (/big/i.test(url)) return { error: "That picture is 128×128. A Minecraft skin is a 64×64 (or old 64×32) PNG." };
		const file = url.split("/").pop().replace(/\.png$/i, "").slice(0, 32) || "Skin";
		const skin = keepSkin(skinPng(url), { name: file, source: "url", origin: url, variant: "classic" });
		return { skin, skins: skinList };
	}
	return { error: "bad request" };
}

/* ---------- the emote room, for real (--emote-room) ---------- */
const emoteName = option("--emote-room", null);
const WAVE = "c6a771fe-05ee-4aa5-a587-592560c12aa6";
const room = { account: null, socket: null, live: false, address: null, seq: 0, events: [], offset: 0, waiters: [] };
if (emoteName) {
	const accounts = JSON.parse(fs.readFileSync(option("--emote-accounts", ""), "utf8"));
	room.account = accounts.find((a) => a.username.toLowerCase() === emoteName.toLowerCase());
	if (!room.account) throw new Error("no account " + emoteName);
	room.file = fs.readFileSync(option("--emote-file", ""));
	const sha = option("--emote-sha256", null);
	if (sha && crypto.createHash("sha256").update(room.file).digest("hex") !== sha) throw new Error("the wave's file isn't the Store's");
}
const roomStore = option("--emote-store", "ws://[::1]:3200");
const dashed = (u) => u.replace(/-/g, "").replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, "$1-$2-$3-$4-$5");
/** As the launcher's normalize: host lowercased without a trailing dot, ":" and the port (25565 when none). */
function normalized(address) {
	const a = String(address).trim();
	const m = a.match(/^(.*?)(?::(\d+))?$/);
	return m[1].toLowerCase().replace(/\.$/, "") + ":" + (m[2] ?? "25565");
}
function pushEvent(e) {
	room.events.push({ seq: ++room.seq, ...e });
	if (room.events.length > 64) room.events.shift();
	for (const w of room.waiters.splice(0)) w();
}
function openRoom(address) {
	if (room.socket) try { room.socket.close(1000); } catch {}
	room.socket = null;
	room.live = false;
	room.address = address;
	if (!address) return;
	const id = crypto.createHash("sha256").update(normalized(address)).digest("hex");
	const url = `${roomStore}/api/v1/social/emotes/?uuid=${dashed(room.account.uuid)}&room=${id}`;
	log(`  (room) opening for ${normalized(address)} as ${room.account.username}`);
	const ws = new WebSocket(url);
	room.socket = ws;
	ws.addEventListener("open", () => ws.send(JSON.stringify({ type: "auth", token: room.account.friends_token })));
	ws.addEventListener("message", (m) => {
		let v;
		try { v = JSON.parse(String(m.data)); } catch { return; }
		const me = room.account.uuid.replace(/-/g, "").toLowerCase();
		const uuid = typeof v.uuid === "string" ? v.uuid.replace(/-/g, "").toLowerCase() : null;
		if (v.type === "ready") {
			room.live = true;
			room.offset = Date.parse(v.server_time) - Date.now();
			log(`  (room) ready as ${uuid}`);
		} else if (v.type === "emote" && uuid && uuid !== me) {
			const ago = Math.max(0, Date.now() + room.offset - Date.parse(v.at));
			log(`  (room) emote ${v.cosmetic_id} from ${uuid} ${ago} ms ago`);
			pushEvent({ kind: "emote", uuid, cosmeticId: v.cosmetic_id, agoMs: ago });
		} else if (v.type === "emote" && uuid === me) {
			log("  (room) ECHO of our own emote");
		} else if (v.type === "emote_stop" && uuid && uuid !== me) {
			log(`  (room) stop from ${uuid}`);
			pushEvent({ kind: "stop", uuid });
		} else if (v.type === "emote_refused") {
			log(`  (room) refused ${v.cosmetic_id}: ${v.reason}`);
			pushEvent({ kind: "refused", cosmeticId: v.cosmetic_id, reason: v.reason });
		}
	});
	ws.addEventListener("close", (e) => {
		log(`  (room) closed ${e.code}`);
		if (room.socket === ws) room.live = false;
	});
	ws.addEventListener("error", () => log("  (room) socket error"));
}
const waveItem = () => ({ id: WAVE, slug: "demo-wave", kind: "emote", slot: null, name: "Demo Wave", gift: false, giftFrom: null, test: true, art: null,
	model: { animation: { bytes: room.file.length } } });
async function roomAnswer(request) {
	switch (request.op) {
		case "cosmetics": return { storeUrl: "https://store.flintclient.com", name: room.account.username, stale: false, problem: null, fetchedAt: Date.now(),
			version: 1, items: [waveItem()], shop: [], equipped: {}, emotes: [WAVE, null, null, null, null, null, null, null] };
		case "modelFile": return request.id === WAVE && request.part === "animation" ? { base64: room.file.toString("base64") } : { error: "This item doesn't have that file." };
		case "emoteRoom": openRoom(request.server ?? null); return { joined: request.server != null, live: room.live };
		case "emoteSend": {
			const frame = request.kind === "emote" ? JSON.stringify({ type: "emote", cosmetic_id: request.cosmeticId }) : JSON.stringify({ type: "emote_stop" });
			const sent = room.live && room.socket?.readyState === 1;
			if (sent) room.socket.send(frame);
			log(`  (room) ${request.kind} ${request.cosmeticId ?? ""} ${sent ? "sent" : "not live: dropped"}`);
			return { sent };
		}
		case "emoteEvents": {
			const since = Number(request.since ?? 0);
			if (!room.events.some((e) => e.seq > since) && request.waitMs > 0) {
				await new Promise((done) => { room.waiters.push(done); setTimeout(done, Math.min(request.waitMs, 25000)); });
			}
			return { events: room.events.filter((e) => e.seq > since), seq: room.seq, live: room.live, items: { [WAVE]: waveItem() } };
		}
	}
	return null;
}

async function answer(request) {
	if (emoteName) {
		const roomReply = await roomAnswer(request);
		if (roomReply) return roomReply;
	}
	const b64 = (bytes) => bytes.toString("base64");
	if (wardrobe) {
		switch (request.op) {
			case "cosmetics": return cosmetics();
			case "badges": return { badges: ownedBadges, badge };
			case "badgeSet": {
				if (!BADGE_TAGS.includes(request.tag)) return { error: "That isn't a Flint badge." };
				if (!ownedBadges.includes(request.tag)) return { error: "This account doesn't have that badge." };
				badge = request.tag;
				return { badges: ownedBadges, badge };
			}
			case "equip": {
				const slot = request.slot ?? "cape";
				if (!Object.values(SLOT_OF).includes(slot)) return { error: "bad request" };
				if (request.id) {
					const item = ITEMS.find((i) => ITEM(i.n) === request.id);
					if (!item) return { error: "This account doesn't own that item (any more). The Flint Store decides what you own." };
					if (SLOT_OF[item.kind] !== slot) return { error: "That item can't be worn there." };
					worn[slot] = request.id;
				} else delete worn[slot];
				invVersion++;
				return cosmetics();
			}
			case "emotes": {
				const slots = request.slots;
				if (!Array.isArray(slots) || slots.length !== 8) return { error: "That emote can't go on your emote wheel." };
				for (const id of slots) if (id != null && !ITEMS.some((i) => ITEM(i.n) === id && i.kind === "emote")) return { error: "That emote can't go on your emote wheel." };
				wheel = slots.map((s) => s ?? null);
				invVersion++;
				return cosmetics();
			}
			case "cosmeticArt": return cosmeticArt(request.id);
			case "skinsList": return { skins: skinList };
			case "skinAdd": return skinAdd(request);
			case "skinArt": {
				const bytes = skinFiles.get(request.id);
				return bytes ? { png: b64(bytes) } : { error: "That skin isn't in your list any more." };
			}
			case "skinApply": {
				if (!skinFiles.has(request.id)) return { error: "That skin isn't in your list any more." };
				applied.push({ id: request.id, variant: request.variant, at: Date.now() });
				log(`  (mock) skinApply recorded only, nothing sent to Minecraft: ${request.id.slice(0, 12)} ${request.variant}`);
				return { applied: request.id, variant: request.variant };
			}
			case "skinRemove": {
				skinList = skinList.filter((s) => s.id !== request.id);
				skinFiles.delete(request.id);
				return { skins: skinList };
			}
		}
	}
	switch (request.op) {
		case "accounts": return { active: null, accounts: [], signIn: { state: "idle" } };
		case "partners": return { source: "network", problem: null, servers: PARTNERS.filter((p) => p.kind === "minecraft_server").length, partners: PARTNERS };
		case "partnerArt": {
			const bytes = art.get(request.sha);
			return bytes ? { type: "png", data: b64(bytes) } : { error: "not found" };
		}
		case "partnerJoin": {
			const p = PARTNERS.find((x) => x.id === request.id);
			if (!p || p.kind !== "minecraft_server") return { error: "This partner isn't listed any more. Open Library → Partnerships to see the current listings." };
			if (!p.joinable) return { error: `${p.name} doesn't take Minecraft ${request.version}. Pick a profile on a version it lists.` };
			return { name: p.name, address: joinAddress };
		}
		case "showcase": return { rotateMs: 6000, slides: SLIDES.map(({ png: _, ...s }) => s) };
		case "showcaseArt": {
			const s = SLIDES.find((x) => x.id === request.id);
			return s ? { type: "png", data: b64(s.png) } : { error: "not found" };
		}
		case "community": return community();
		case "rewardArt": return request.id === CAPE_ID ? { png: b64(CAPE) } : { error: "This reward isn't in the Flint Store's community rewards." };
		case "discordLink": return { state: "off" };
		case "friends": {
			const uuid = String(request.playing ?? "");
			const ALEX = "ec561538f3fd461daff5086b22154bce";
			const typingFile = option("--typing-file", null);
			const typing = typingFile && fs.existsSync(typingFile) ? [ALEX] : [];
			const chat = request.chat ? { with: String(request.chat), loaded: true, more: false, messages: [
				{ id: 1, from: String(request.chat), to: uuid, body: "gg! that bridge was close", sensitive: false, at: Date.now() - 120_000, read: true },
				{ id: 2, from: uuid, to: String(request.chat), body: "haha yeah, rematch?", sensitive: false, at: Date.now() - 60_000, read: true },
			] } : undefined;
			return { configured: true, phase: "ready", me: me(uuid), typing, ...(chat ? { chat } : {}), friends: [
				{ uuid: "ec561538f3fd461daff5086b22154bce", name: "Alex", status: "online", inGame: true, lastSeen: null, pinned: true, unread: 0 },
				{ uuid: "1b0e5c7a2f3d4e6b8c9d0a1b2c3d4e5f", name: "Steve", status: "offline", inGame: false, lastSeen: Date.now() - 3 * 3600_000, pinned: false, unread: 0 },
			], incoming: [], outgoing: [], offline: false, stale: false, error: null, syncedAt: Date.now() };
		}
		case "friendsAction": {
			const action = request.action ?? {};
			if (action.kind === "settings") {
				if (action.lastSeen) settings.lastSeen = action.lastSeen;
				if (action.gifts) settings.gifts = action.gifts;
				return { me: me(String(request.playing ?? "")) };
			}
			return {};
		}
		case "cosmetics": return { error: "Sign in to the Flint Store in the Flint app (its Store page) to wear your cosmetics here." };
		case "badges": return { badges: null, badge: null };
		case "media": return { error: "busy" };
		case "theme": return { theme: appTheme() };
		case "setTheme": {
			if (!THEMES.includes(request.theme)) return { error: "bad request" };
			theme = request.theme;
			if (themeFile) fs.writeFileSync(themeFile, theme);
			return {};
		}
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
			if (request.secret !== secret) reply = { ok: false, error: "unauthorized" };
			else {
				const { secret: _, v, ...shown } = request;
				const body = await answer(request);
				reply = body.error ? { ok: false, error: body.error } : { ok: true, ...body };
				const fields = Object.entries(shown).filter(([k]) => k !== "op").map(([k, v]) => `${k}=${typeof v === "object" ? JSON.stringify(v) : v}`).join(" ");
				log(`${request.op}${fields ? " " + fields : ""} -> ${reply.ok ? "ok" : reply.error}`);
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
	console.log(`FLINT_LAUNCHER_LINK_SECRET=${secret}`);
	if (logFile) fs.writeFileSync(logFile + ".env", `FLINT_LAUNCHER_LINK=127.0.0.1:${port}\nFLINT_LAUNCHER_LINK_SECRET=${secret}\n`);
	log(`mock game link up: ${PARTNERS.length} partners, ${SLIDES.length} slides, community ${joined ? "joined" : "not joined"}`);
});
