// Dev-only (Windows QA): the game files for a Minecraft version the Flint launcher hasn't downloaded (say 26.1.2, when the
// launcher only has 26.1.1's version file). Everything missing is fetched once into %USERPROFILE%\FlintQA\mc-cache, never
// into the launcher's own folder, which is only read. Every file is checked against the sha1 its publisher lists before it
// is used: the version file against Mojang's version manifest, the client jar, libraries and asset index against the
// version file, each asset object against its own name (its sha1), and Fabric's libraries against Fabric's profile.
// Files the launcher already has (most libraries, most assets) are used from there when their sha1 matches; assets are
// copied into the cache, because Minecraft reads them from one folder.
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const ASSETS = "https://resources.download.minecraft.net";
const FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader";

export const sha1Of = (file) => crypto.createHash("sha1").update(fs.readFileSync(file)).digest("hex");
export const sha1Matches = (file, sha1) => fs.existsSync(file) && sha1Of(file) === sha1;

export async function fetchBytes(url) {
	for (let attempt = 1; ; attempt++) {
		try {
			const r = await fetch(url);
			if (!r.ok) throw new Error(`HTTP ${r.status}`);
			return Buffer.from(await r.arrayBuffer());
		} catch (e) {
			if (attempt >= 3) throw new Error(`couldn't download ${url}: ${e.message}`);
		}
	}
}

/** Writes url's bytes to dest once their sha1 is the expected one (atomically, so a half file never stays behind). */
export async function download(url, dest, sha1) {
	const bytes = await fetchBytes(url);
	const got = crypto.createHash("sha1").update(bytes).digest("hex");
	if (sha1 && got !== sha1) throw new Error(`sha1 mismatch for ${url}: got ${got}, expected ${sha1}`);
	fs.mkdirSync(path.dirname(dest), { recursive: true });
	const tmp = dest + ".part";
	fs.writeFileSync(tmp, bytes);
	fs.renameSync(tmp, dest);
	return dest;
}

/**
 * The file at rel with this sha1: the cache's copy, else the launcher's (used in place, read only), else downloaded into
 * the cache from url.
 */
export async function resolve(rel, sha1, url, roots, log) {
	const cached = path.join(roots.cache, rel);
	if (sha1Matches(cached, sha1)) return cached;
	const launcher = path.join(roots.launcher, rel);
	if (sha1Matches(launcher, sha1)) return launcher;
	log(`downloading ${rel}`);
	return download(url, cached, sha1);
}

const ruleMatches = (rule) => !rule.features && (!rule.os || ((!rule.os.name || rule.os.name === "windows") && (!rule.os.arch || rule.os.arch === "x86_64")));
export function rulesAllow(rules) {
	if (!rules) return true;
	let allowed = false;
	for (const rule of rules) if (ruleMatches(rule)) allowed = rule.action === "allow";
	return allowed;
}
export function mavenPath(name) {
	const [coords, ext = "jar"] = name.split("@");
	const [group, artifact, version, classifier] = coords.split(":");
	return `${group.replaceAll(".", "/")}/${artifact}/${version}/${artifact}-${version}${classifier ? "-" + classifier : ""}.${ext}`;
}
export function libraryKey(name) {
	const p = name.split(":");
	return p.length >= 4 ? `${p[0]}:${p[1]}:${p[3]}` : `${p[0]}:${p[1]}`;
}

/**
 * Minecraft mc with Fabric Loader loader, from the cache (filled as needed). Returns the parsed version and Fabric
 * profile, the classpath in launch order (Fabric's libraries win over vanilla's of the same key; the client jar last),
 * the client jar, and the assets folder.
 */
export async function cachedGame(mc, loader, { launcher, cache }, log = console.log) {
	const roots = { launcher, cache };
	// The version file, checked against Mojang's manifest once and then kept in the cache (later runs need no network).
	const versionRel = path.join("versions", mc, `${mc}.json`);
	const versionFile = path.join(cache, versionRel);
	if (!fs.existsSync(versionFile)) {
		const manifest = JSON.parse(await fetchBytes(MANIFEST));
		const entry = manifest.versions.find((v) => v.id === mc);
		if (!entry) throw new Error(`Minecraft ${mc} isn't in Mojang's version manifest`);
		const found = await resolve(versionRel, entry.sha1, entry.url, roots, log);
		if (found !== versionFile) {
			fs.mkdirSync(path.dirname(versionFile), { recursive: true });
			fs.copyFileSync(found, versionFile);
		}
		if (sha1Of(versionFile) !== entry.sha1) throw new Error(`sha1 mismatch for ${versionFile}`);
	}
	const vanilla = JSON.parse(fs.readFileSync(versionFile, "utf8"));

	const fabricId = `fabric-loader-${loader}-${mc}`;
	const fabricRel = path.join("versions", fabricId, "profile.json");
	let fabricFile = [path.join(cache, fabricRel), path.join(launcher, fabricRel)].find((f) => fs.existsSync(f));
	if (!fabricFile) {
		// Fabric's meta publishes no checksum for the profile itself; every library it names is checked below.
		log(`downloading ${fabricRel}`);
		fabricFile = await download(`${FABRIC_META}/${mc}/${loader}/profile/json`, path.join(cache, fabricRel), null);
	}
	const fabric = JSON.parse(fs.readFileSync(fabricFile, "utf8"));

	const jars = [];
	const keys = new Set();
	for (const lib of fabric.libraries) {
		const rel = mavenPath(lib.name);
		const url = lib.url.replace(/\/?$/, "/") + rel;
		// The profile lists no sha1 for Fabric Loader itself: its maven publishes one beside the jar.
		const sha1 = lib.sha1 || (await fetchBytes(url + ".sha1")).toString("utf8").trim().split(/\s+/)[0];
		if (!/^[0-9a-f]{40}$/.test(sha1)) throw new Error(`no sha1 for ${lib.name}`);
		jars.push(await resolve(path.join("libraries", rel), sha1, url, roots, log));
		keys.add(libraryKey(lib.name));
	}
	for (const lib of vanilla.libraries) {
		const artifact = lib.downloads?.artifact;
		if (!rulesAllow(lib.rules) || !artifact?.path || keys.has(libraryKey(lib.name))) continue;
		jars.push(await resolve(path.join("libraries", artifact.path), artifact.sha1, artifact.url, roots, log));
	}
	const client = vanilla.downloads.client;
	const clientJar = await resolve(path.join("versions", mc, `${mc}.jar`), client.sha1, client.url, roots, log);
	jars.push(clientJar);

	const assetsDir = path.join(cache, "assets");
	const indexRel = path.join("assets", "indexes", `${vanilla.assetIndex.id}.json`);
	const indexFile = await resolve(indexRel, vanilla.assetIndex.sha1, vanilla.assetIndex.url, roots, log);
	if (!indexFile.startsWith(cache)) {
		fs.mkdirSync(path.dirname(path.join(cache, indexRel)), { recursive: true });
		fs.copyFileSync(indexFile, path.join(cache, indexRel));
	}
	const objects = Object.values(JSON.parse(fs.readFileSync(indexFile, "utf8")).objects);
	let copied = 0;
	let downloaded = 0;
	for (const { hash } of objects) {
		const rel = path.join("objects", hash.slice(0, 2), hash);
		const dest = path.join(assetsDir, rel);
		if (fs.existsSync(dest)) continue;
		const theirs = path.join(launcher, "assets", rel);
		if (sha1Matches(theirs, hash)) {
			fs.mkdirSync(path.dirname(dest), { recursive: true });
			fs.copyFileSync(theirs, dest + ".part");
			if (sha1Of(dest + ".part") !== hash) throw new Error(`asset copy damaged: ${rel}`);
			fs.renameSync(dest + ".part", dest);
			copied++;
		} else {
			await download(`${ASSETS}/${hash.slice(0, 2)}/${hash}`, dest, hash);
			downloaded++;
		}
	}
	if (copied || downloaded) log(`assets ${vanilla.assetIndex.id}: ${copied} copied from the launcher, ${downloaded} downloaded`);
	return { vanilla, fabric, jars, clientJar, assetsDir };
}

// Mojang's Java runtimes (the launcher's java-runtime-* components), per platform.
const JAVA_RUNTIMES = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

/**
 * javaw.exe of Mojang's Java runtime component (java-runtime-delta, say) for Windows x64: the launcher's (read only) when it
 * has the whole runtime, else the cache's, downloaded once into <cache>/runtimes/<component> with every file checked
 * against the sha1 in the runtime's manifest (itself checked against Mojang's runtime index).
 */
export async function cachedJavaRuntime(component, { launcherRuntimes, cache }, log = console.log) {
	const theirs = path.join(launcherRuntimes, component, "windows-x64", "bin", "javaw.exe");
	if (fs.existsSync(theirs)) return theirs;
	const root = path.join(cache, "runtimes", component, "windows-x64");
	const javaw = path.join(root, "bin", "javaw.exe");
	const done = path.join(cache, "runtimes", component, "complete");
	if (fs.existsSync(done) && fs.existsSync(javaw)) return javaw;
	const entry = JSON.parse(await fetchBytes(JAVA_RUNTIMES))["windows-x64"]?.[component]?.[0];
	if (!entry) throw new Error(`Mojang publishes no ${component} for windows-x64`);
	const manifestFile = await download(entry.manifest.url, path.join(cache, "runtimes", component, "windows-x64.manifest.json"), entry.manifest.sha1);
	const files = JSON.parse(fs.readFileSync(manifestFile, "utf8")).files;
	log(`downloading Java runtime ${component} ${entry.version?.name ?? ""} into ${root}`);
	for (const [rel, file] of Object.entries(files)) {
		const dest = path.join(root, rel);
		if (file.type === "directory") fs.mkdirSync(dest, { recursive: true });
		else if (file.type === "file" && !sha1Matches(dest, file.downloads.raw.sha1)) await download(file.downloads.raw.url, dest, file.downloads.raw.sha1);
	}
	if (!fs.existsSync(javaw)) throw new Error(`${component} has no bin/javaw.exe`);
	fs.writeFileSync(done, entry.manifest.sha1 + "\n");
	return javaw;
}
