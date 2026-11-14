// Dev-only: a stand-in for Flint's CurseForge proxy (the Store's /api/v1/curseforge, which mirrors api.curseforge.com/v1
// and adds the API key), on this computer, for developing and testing the Library's CurseForge source without the
// Store or a key. It answers in CurseForge's documented shapes (docs.curseforge.com/rest-api: Mod, File,
// FileDependency, Category, and `{ data, pagination }`) and the Store's own rules:
//   - every route ends in "/" (others get a 308 to it, as the Store does); gameId=432 on search and categories, else
//     400 {"error":"minecraft_only"}; POST bodies are lists of whole numbers (modIds and fileIds at most 100,
//     fingerprints at most 1000), else 400 {"error":"invalid_body"}; anything off the allowlist is 404.
//   - --mode unavailable: 503 {"error":"curseforge_unavailable"} (no key); busy: 429 {"error":"rate-limited"} with
//     Retry-After: 60; unreachable: 502 {"error":"curseforge_unreachable"}; missing: 404 (the route isn't deployed).
// Files download from this server (http://127.0.0.1:PORT/files/...): real jars with a fabric.mod.json and real pack
// zips, each with its SHA-1 and MD5 as CurseForge publishes them. One mod (Simple Voice Chat) has downloadUrl null, as
// when its author allows downloads on CurseForge only.
//
// usage: node app/scripts/mock-curseforge.mjs [--port 4810] [--mode ok|unavailable|busy|unreachable|missing] [--slow-ms N]
//        [--real]
// Point a development or isolated build at it: FLINT_CURSEFORGE_URL=http://127.0.0.1:4810
//
// --real: forwards every allowed request to the real api.curseforge.com with the key read from
// %USERPROFILE%\.flint-secrets\curseforge.key (only if that file exists; it is never printed or logged), for checking
// the app against live data before the Store's proxy is deployed. Downloads then come from CurseForge's CDN.
// Logs one line per request (method, path, status). Listens on 127.0.0.1 only.
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import zlib from "node:zlib";

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : fallback;
};
const port = Number(option("--port", "4810"));
let mode = option("--mode", "ok");
const slowMs = Number(option("--slow-ms", "0"));
const real = args.includes("--real");
const origin = `http://127.0.0.1:${port}`;
const GAME = 432;

let realKey = null;
if (real) {
  const keyFile = path.join(os.homedir(), ".flint-secrets", "curseforge.key");
  if (!fs.existsSync(keyFile)) {
    console.error("--real needs the key file in .flint-secrets (not found); serving fixtures instead");
  } else {
    realKey = fs.readFileSync(keyFile, "utf8").trim();
    console.log("--real: forwarding to api.curseforge.com (key loaded from .flint-secrets, not shown)");
  }
}

// ---------- archives ----------

/** A zip of stored (uncompressed) entries: enough for a jar Fabric and Flint can read. */
function zip(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, text] of entries) {
    const data = Buffer.from(text);
    const nameBytes = Buffer.from(name);
    const crc = zlib.crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    locals.push(local, nameBytes, data);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBytes);
    offset += 30 + nameBytes.length + data.length;
  }
  const dir = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(dir.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, dir, end]);
}

const fabricJar = (modId, name, version, depends = {}) =>
  zip([["fabric.mod.json", JSON.stringify({ schemaVersion: 1, id: modId, version, name, environment: "*", depends: { fabricloader: "*", ...depends } })]]);

// ---------- fixtures ----------

const DAY = 86_400_000;
const date = (daysAgo) => new Date(Date.parse("2026-11-08T12:00:00Z") - daysAgo * DAY).toISOString();
const CATEGORIES = [
  { id: 6, name: "Mods", slug: "mc-mods", isClass: true, classId: null },
  { id: 4471, name: "Modpacks", slug: "modpacks", isClass: true, classId: null },
  { id: 12, name: "Resource Packs", slug: "texture-packs", isClass: true, classId: null },
  { id: 6552, name: "Shaders", slug: "shaders", isClass: true, classId: null },
  { id: 6945, name: "Data Packs", slug: "data-packs", isClass: true, classId: null },
  { id: 423, name: "Map and Information", slug: "map-information", classId: 6, parentCategoryId: 6 },
  { id: 421, name: "API and Library", slug: "library-api", classId: 6, parentCategoryId: 6 },
  { id: 436, name: "Food", slug: "mc-food", classId: 6, parentCategoryId: 6 },
  { id: 435, name: "Server Utility", slug: "server-utility", classId: 6, parentCategoryId: 6 },
  { id: 5191, name: "Utility & QoL", slug: "utility-qol", classId: 6, parentCategoryId: 6 },
  { id: 4484, name: "Multiplayer", slug: "multiplayer", classId: 4471, parentCategoryId: 4471 },
  { id: 393, name: "16x", slug: "16x", classId: 12, parentCategoryId: 12 },
  { id: 6554, name: "Fantasy", slug: "fantasy", classId: 6552, parentCategoryId: 6552 },
  { id: 6946, name: "Worldgen", slug: "worldgen", classId: 6945, parentCategoryId: 6945 },
].map((c) => ({ gameId: GAME, url: `https://www.curseforge.com/minecraft/${c.slug}`, iconUrl: "https://media.forgecdn.net/avatars/thumbnails/6/6/64/64/0.png", dateModified: date(400), isClass: false, parentCategoryId: c.classId ?? undefined, ...c }));
const CLASS_SLUG = { 6: "mc-mods", 4471: "modpacks", 12: "texture-packs", 6552: "shaders", 6945: "data-packs" };

/** [id, classId, slug, name, summary, author, downloads, categoryIds, allowDistribution] */
const MODS = [
  [248787, 6, "appleskin", "AppleSkin", "Adds various food-related HUD improvements.", "squeek502", 168_204_113, [436, 423], true],
  [306612, 6, "fabric-api", "Fabric API", "Lightweight and modular API providing common hooks and intercompatibility measures utilized by mods using the Fabric toolchain.", "modmuss50", 98_311_026, [421], true],
  [324717, 6, "jade", "Jade 🔍", "Shows information about what you are looking at.", "Snownee", 190_514_002, [423], true],
  [238222, 6, "jei", "Just Enough Items (JEI)", "View Items and Recipes.", "mezz", 412_000_000, [423], true],
  [451136, 6, "simple-voice-chat", "Simple Voice Chat", "A working voice chat in Minecraft!", "henkelmax", 60_100_400, [435], false],
  [60089, 6, "mouse-tweaks", "Mouse Tweaks", "Enhances inventory management by adding various functions to the mouse buttons.", "YaLTeR", 230_002_118, [5191], true],
  [396246, 4471, "fabulously-optimized", "Fabulously Optimized", "Improved performance, graphics and familiar features.", "robotkoer", 21_000_000, [4484], true],
  [999001, 4471, "forge-adventure", "Forge Adventure", "A Forge pack (Flint refuses it).", "someone", 1_200_000, [4484], true],
  [457458, 12, "stay-true", "Stay True", "A vanilla-faithful resource pack.", "haaayden", 4_800_000, [393], true],
  [385587, 6552, "complementary-reimagined", "Complementary Reimagined", "Shaders with a vanilla-faithful look.", "EminGTR", 9_300_000, [6554], true],
  [667899, 6945, "terralith", "Terralith", "Over 95 new biomes, using only vanilla blocks.", "Starmute", 12_000_000, [6946], true],
];

/** [fileId, modId, displayName, fileName, releaseType, gameVersions, daysAgo, dependencies [[modId, relationType]], bytes] */
const files = [];
const add = (id, modId, displayName, fileName, releaseType, gameVersions, daysAgo, deps, bytes) => files.push({ id, modId, displayName, fileName, releaseType, gameVersions, daysAgo, deps, bytes });
add(5000, 248787, "AppleSkin 3.0.5", "appleskin-fabric-mc1.21-3.0.5.jar", 1, ["1.21.1", "1.21", "Fabric", "Client", "Server"], 90, [[306612, 3]], fabricJar("appleskin", "AppleSkin", "3.0.5+mc1.21"));
add(5001, 248787, "AppleSkin 3.0.6", "appleskin-fabric-mc1.21-3.0.6.jar", 1, ["1.21.1", "1.21", "Fabric", "Client", "Server"], 12, [[306612, 3]], fabricJar("appleskin", "AppleSkin", "3.0.6+mc1.21"));
add(5002, 248787, "AppleSkin 3.0.6 (NeoForge)", "appleskin-neoforge-mc1.21-3.0.6.jar", 1, ["1.21.1", "NeoForge"], 12, [], fabricJar("appleskin", "AppleSkin", "3.0.6"));
add(6001, 306612, "[1.21.1] Fabric API 0.116.7+1.21.1", "fabric-api-0.116.7+1.21.1.jar", 1, ["1.21.1", "Fabric"], 20, [], fabricJar("fabric-api", "Fabric API", "0.116.7+1.21.1"));
add(8001, 324717, "Jade 15.10.3+fabric", "Jade-1.21.1-Fabric-15.10.3.jar", 1, ["1.21.1", "Fabric"], 30, [[306612, 3], [238222, 2]], fabricJar("jade", "Jade", "15.10.3+fabric"));
add(8501, 238222, "jei-1.21.1-fabric-19.21.0.247", "jei-1.21.1-fabric-19.21.0.247.jar", 1, ["1.21.1", "Fabric"], 40, [[306612, 3]], fabricJar("jei", "Just Enough Items", "19.21.0.247"));
add(7001, 451136, "voicechat-fabric-1.21.1-2.5.26", "voicechat-fabric-1.21.1-2.5.26.jar", 1, ["1.21.1", "Fabric"], 15, [[306612, 3]], fabricJar("voicechat", "Simple Voice Chat", "2.5.26"));
add(7501, 60089, "Mouse Tweaks 2.26.1 (Fabric)", "MouseTweaks-fabric-mc1.21-2.26.1.jar", 2, ["1.21.1", "Fabric"], 50, [], fabricJar("mousetweaks", "Mouse Tweaks", "2.26.1"));
add(9001, 457458, "Stay True 1.21.1", "Stay True 1.21.1.zip", 1, ["1.21.1"], 25, [], zip([["pack.mcmeta", JSON.stringify({ pack: { pack_format: 34, description: "Stay True" } })]]));
add(9101, 385587, "ComplementaryReimagined_r5.5", "ComplementaryReimagined_r5.5.zip", 1, ["1.21.1", "1.21"], 35, [], zip([["shaders/composite.fsh", "void main() {}\n"]]));
add(9201, 667899, "Terralith_1.21.x_v2.5.8", "Terralith_1.21.x_v2.5.8.zip", 1, ["1.21.1"], 60, [], zip([["pack.mcmeta", JSON.stringify({ pack: { pack_format: 48, description: "Terralith" } })]]));
add(9901, 396246, "Fabulously Optimized 6.4.0", "Fabulously Optimized-6.4.0.zip", 1, ["1.21.1"], 10, [], zip([
  ["manifest.json", JSON.stringify({ minecraft: { version: "1.21.1", modLoaders: [{ id: "fabric-0.16.9", primary: true }] }, manifestType: "minecraftModpack", manifestVersion: 1, name: "Fabulously Optimized", version: "6.4.0", author: "robotkoer", files: [{ projectID: 248787, fileID: 5001, required: true }, { projectID: 306612, fileID: 6001, required: true }, { projectID: 457458, fileID: 9001, required: true }, { projectID: 60089, fileID: 7501, required: false }], overrides: "overrides" })],
  ["overrides/config/fabulously-optimized.txt", "fo=1\n"],
  ["overrides/options.txt", "renderDistance:8\n"],
]));
add(9902, 999001, "Forge Adventure 1.0", "Forge Adventure-1.0.zip", 1, ["1.21.1"], 10, [], zip([["manifest.json", JSON.stringify({ minecraft: { version: "1.21.1", modLoaders: [{ id: "forge-52.0.1", primary: true }] }, manifestType: "minecraftModpack", manifestVersion: 1, name: "Forge Adventure", files: [], overrides: "overrides" })]]));

const hash = (algo, bytes) => crypto.createHash(algo).update(bytes).digest("hex");
const modOf = (id) => MODS.find((m) => m[0] === id);
const LOADER_TYPE = { Forge: 1, Fabric: 4, Quilt: 5, NeoForge: 6 };

function fileJson(f) {
  const [, , , , , , , , allow] = modOf(f.modId);
  return {
    id: f.id,
    gameId: GAME,
    modId: f.modId,
    isAvailable: true,
    displayName: f.displayName,
    fileName: f.fileName,
    releaseType: f.releaseType,
    fileStatus: 4,
    hashes: [{ value: hash("sha1", f.bytes), algo: 1 }, { value: hash("md5", f.bytes), algo: 2 }],
    fileDate: date(f.daysAgo),
    fileLength: f.bytes.length,
    downloadCount: 10_000 + f.id,
    fileSizeOnDisk: f.bytes.length,
    downloadUrl: allow ? `${origin}/files/${Math.floor(f.id / 1000)}/${f.id % 1000}/${encodeURIComponent(f.fileName)}` : null,
    gameVersions: f.gameVersions,
    sortableGameVersions: f.gameVersions.map((v) => ({ gameVersionName: v, gameVersionPadded: v, gameVersion: /^\d/.test(v) ? v : "", gameVersionReleaseDate: date(500), gameVersionTypeId: /^\d/.test(v) ? 77784 : 68441 })),
    dependencies: f.deps.map(([modId, relationType]) => ({ modId, relationType })),
    alternateFileId: 0,
    isServerPack: false,
    fileFingerprint: zlib.crc32(f.bytes),
    modules: [{ name: "META-INF", fingerprint: 1 }],
  };
}

function modJson(m) {
  const [id, classId, slug, name, summary, author, downloads, categoryIds, allow] = m;
  const own = files.filter((f) => f.modId === id).sort((a, b) => a.daysAgo - b.daysAgo);
  const site = `https://www.curseforge.com/minecraft/${CLASS_SLUG[classId]}/${slug}`;
  return {
    id,
    gameId: GAME,
    name,
    slug,
    links: { websiteUrl: site, wikiUrl: "", issuesUrl: `https://github.com/example/${slug}/issues`, sourceUrl: `https://github.com/example/${slug}` },
    summary,
    status: 4,
    downloadCount: downloads,
    isFeatured: false,
    primaryCategoryId: categoryIds[0],
    categories: categoryIds.map((c) => CATEGORIES.find((x) => x.id === c)),
    classId,
    authors: [{ id: 1000 + id, name: author, url: `https://www.curseforge.com/members/${author}` }],
    logo: { id: id * 10, modId: id, title: name, description: "", thumbnailUrl: `https://media.forgecdn.net/avatars/thumbnails/${id}/256/256/logo.png`, url: `https://media.forgecdn.net/avatars/${id}/logo.png` },
    screenshots: [{ id: id * 10 + 1, modId: id, title: "Screenshot", description: "", thumbnailUrl: `https://media.forgecdn.net/attachments/${id}/thumb.png`, url: `https://media.forgecdn.net/attachments/${id}/full.png` }],
    mainFileId: own[0]?.id ?? 0,
    latestFiles: own.slice(0, 2).map(fileJson),
    latestFilesIndexes: own.flatMap((f) => {
      const loaders = f.gameVersions.filter((v) => v in LOADER_TYPE);
      return f.gameVersions.filter((v) => /^\d/.test(v)).flatMap((v) => (loaders.length ? loaders : [null]).map((l) => ({ gameVersion: v, fileId: f.id, filename: f.fileName, releaseType: f.releaseType, gameVersionTypeId: 77784, modLoader: l ? LOADER_TYPE[l] : undefined })));
    }),
    latestEarlyAccessFilesIndexes: [],
    dateCreated: date(900),
    dateModified: date(own[0]?.daysAgo ?? 100),
    dateReleased: date(own[0]?.daysAgo ?? 100),
    allowModDistribution: allow,
    gamePopularityRank: 100,
    isAvailable: true,
    thumbsUpCount: 0,
  };
}

// ---------- the proxy ----------

const send = (res, status, body, headers = {}) => {
  res.writeHead(status, { "content-type": "application/json", ...headers });
  res.end(typeof body === "string" ? body : JSON.stringify(body));
};
const wholeNumbers = (list, max) => Array.isArray(list) && list.length > 0 && list.length <= max && list.every((n) => Number.isInteger(n) && n > 0);
const readBody = (req) => new Promise((resolve) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    try {
      resolve(JSON.parse(Buffer.concat(chunks).toString("utf8") || "null"));
    } catch {
      resolve(undefined);
    }
  });
});

function search(q) {
  if (q.get("gameId") !== String(GAME)) return [400, { error: "minecraft_only" }];
  const classId = Number(q.get("classId") ?? 0);
  const text = (q.get("searchFilter") ?? "").toLowerCase();
  const version = q.get("gameVersion");
  const loader = Number(q.get("modLoaderType") ?? 0);
  const categories = JSON.parse(q.get("categoryIds") ?? "[]");
  const index = Number(q.get("index") ?? 0);
  const size = Math.min(50, Number(q.get("pageSize") ?? 50));
  if (index + size > 10_000) return [400, { error: "invalid_query" }];
  let list = MODS.filter((m) => (!classId || m[1] === classId) && (!text || `${m[3]} ${m[4]} ${m[2]}`.toLowerCase().includes(text)))
    .filter((m) => !categories.length || m[7].some((c) => categories.includes(c)))
    .filter((m) => files.some((f) => f.modId === m[0] && (!version || f.gameVersions.includes(version)) && (!loader || f.gameVersions.some((v) => LOADER_TYPE[v] === loader) || m[1] === 4471)));
  const sort = Number(q.get("sortField") ?? 1);
  if (sort === 6 || sort === 2) list = [...list].sort((a, b) => b[6] - a[6]);
  if (sort === 3 || sort === 11) list = [...list].sort((a, b) => Math.min(...files.filter((f) => f.modId === a[0]).map((f) => f.daysAgo)) - Math.min(...files.filter((f) => f.modId === b[0]).map((f) => f.daysAgo)));
  const page = list.slice(index, index + size).map(modJson);
  return [200, { data: page, pagination: { index, pageSize: size, resultCount: page.length, totalCount: list.length } }];
}

async function route(req, res, url) {
  const p = url.pathname;
  if (req.method === "GET" && p === "/v1/mods/search/") return search(url.searchParams);
  if (req.method === "GET" && p === "/v1/categories/") {
    if (url.searchParams.get("gameId") !== String(GAME)) return [400, { error: "minecraft_only" }];
    return [200, { data: CATEGORIES }];
  }
  if (req.method === "POST" && p === "/v1/mods/") {
    const body = await readBody(req);
    if (!body || !wholeNumbers(body.modIds, 100)) return [400, { error: "invalid_body" }];
    return [200, { data: body.modIds.map(modOf).filter(Boolean).map(modJson) }];
  }
  if (req.method === "POST" && p === "/v1/mods/files/") {
    const body = await readBody(req);
    if (!body || !wholeNumbers(body.fileIds, 100)) return [400, { error: "invalid_body" }];
    return [200, { data: body.fileIds.map((id) => files.find((f) => f.id === id)).filter(Boolean).map(fileJson) }];
  }
  if (req.method === "POST" && p === "/v1/fingerprints/432/") {
    const body = await readBody(req);
    if (!body || !wholeNumbers(body.fingerprints, 1000)) return [400, { error: "invalid_body" }];
    const matches = files.filter((f) => body.fingerprints.includes(zlib.crc32(f.bytes))).map((f) => ({ id: f.modId, file: fileJson(f), latestFiles: [] }));
    return [200, { data: { isCacheBuilt: true, exactMatches: matches, exactFingerprints: matches.map((m) => m.file.fileFingerprint), partialMatches: [], partialMatchFingerprints: {}, installedFingerprints: body.fingerprints, unmatchedFingerprints: [] } }];
  }
  let m;
  if (req.method === "GET" && (m = p.match(/^\/v1\/mods\/(\d+)\/$/))) {
    const mod = modOf(Number(m[1]));
    return mod ? [200, { data: modJson(mod) }] : [404, ""];
  }
  if (req.method === "GET" && (m = p.match(/^\/v1\/mods\/(\d+)\/description\/$/))) {
    const mod = modOf(Number(m[1]));
    return mod ? [200, { data: `<p><strong>${mod[3]}</strong></p><p>${mod[4]}</p><ul><li>Served by mock-curseforge.mjs</li></ul>` }] : [404, ""];
  }
  if (req.method === "GET" && (m = p.match(/^\/v1\/mods\/(\d+)\/files\/$/))) {
    const id = Number(m[1]);
    if (!modOf(id)) return [404, ""];
    const version = url.searchParams.get("gameVersion");
    const loader = Number(url.searchParams.get("modLoaderType") ?? 0);
    const list = files
      .filter((f) => f.modId === id && (!version || f.gameVersions.includes(version)) && (!loader || f.gameVersions.some((v) => LOADER_TYPE[v] === loader) || modOf(id)[1] === 4471))
      .sort((a, b) => a.daysAgo - b.daysAgo);
    const index = Number(url.searchParams.get("index") ?? 0);
    const size = Math.min(50, Number(url.searchParams.get("pageSize") ?? 50));
    const page = list.slice(index, index + size).map(fileJson);
    return [200, { data: page, pagination: { index, pageSize: size, resultCount: page.length, totalCount: list.length } }];
  }
  if (req.method === "GET" && (m = p.match(/^\/v1\/mods\/(\d+)\/files\/(\d+)\/$/))) {
    const f = files.find((x) => x.modId === Number(m[1]) && x.id === Number(m[2]));
    return f ? [200, { data: fileJson(f) }] : [404, ""];
  }
  if (req.method === "GET" && (m = p.match(/^\/v1\/mods\/(\d+)\/files\/(\d+)\/download-url\/$/))) {
    const f = files.find((x) => x.modId === Number(m[1]) && x.id === Number(m[2]));
    if (!f) return [404, ""];
    const url = fileJson(f).downloadUrl;
    return url ? [200, { data: url }] : [403, ""];
  }
  if (req.method === "GET" && (m = p.match(/^\/v1\/mods\/(\d+)\/files\/(\d+)\/changelog\/$/))) {
    const f = files.find((x) => x.modId === Number(m[1]) && x.id === Number(m[2]));
    return f ? [200, { data: `<p>${f.displayName}</p><ul><li>Fixed a crash.</li><li>Faster loading.</li></ul>` }] : [404, ""];
  }
  return [404, ""];
}

async function forward(req, res, url) {
  const body = req.method === "POST" ? await readBody(req) : undefined;
  const target = `https://api.curseforge.com${url.pathname.replace(/\/$/, "")}${url.search}`;
  const answer = await fetch(target, { method: req.method, headers: { "x-api-key": realKey, accept: "application/json", ...(body ? { "content-type": "application/json" } : {}) }, body: body ? JSON.stringify(body) : undefined });
  return [answer.status, await answer.text()];
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, origin);
  const done = (status) => console.log(`${req.method} ${url.pathname}${url.search} -> ${status}`);
  try {
    if (slowMs) await new Promise((r) => setTimeout(r, slowMs));
    // Files, as CurseForge's CDN serves them.
    let m;
    if (req.method === "GET" && (m = url.pathname.match(/^\/files\/(\d+)\/(\d+)\/(.+)$/))) {
      const f = files.find((x) => x.id === Number(m[1]) * 1000 + Number(m[2]) && encodeURIComponent(x.fileName) === m[3]);
      if (!f) return send(res, 404, ""), done(404);
      res.writeHead(200, { "content-type": "application/java-archive", "content-length": f.bytes.length });
      res.end(f.bytes);
      return done(200);
    }
    // Test hook: change the mode while running (POST /__mode/<mode>).
    if (req.method === "POST" && (m = url.pathname.match(/^\/__mode\/(\w+)$/))) {
      mode = m[1];
      return send(res, 200, { mode }), done(200);
    }
    if (!url.pathname.startsWith("/v1/")) return send(res, 404, ""), done(404);
    if (!url.pathname.endsWith("/")) {
      res.writeHead(308, { location: `${url.pathname}/${url.search}` });
      res.end();
      return done(308);
    }
    if (mode === "unavailable") return send(res, 503, { error: "curseforge_unavailable" }), done(503);
    if (mode === "busy") return send(res, 429, { error: "rate-limited" }, { "retry-after": "60" }), done(429);
    if (mode === "unreachable") return send(res, 502, { error: "curseforge_unreachable" }), done(502);
    if (mode === "missing") return send(res, 404, ""), done(404);
    const [status, body] = realKey ? await forward(req, res, url) : await route(req, res, url);
    send(res, status, body, status === 200 && req.method === "GET" ? { "cache-control": "public, max-age=90", "x-flint-cache": "MISS" } : {});
    done(status);
  } catch (error) {
    console.error(`${req.method} ${url.pathname}: ${error?.message ?? error}`);
    send(res, 502, { error: "curseforge_unreachable" });
  }
});
server.listen(port, "127.0.0.1", () => console.log(`mock CurseForge proxy on ${origin} (mode ${mode}${realKey ? ", forwarding to the real API" : ""})`));
