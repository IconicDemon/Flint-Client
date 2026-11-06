// Dev-only: Java 25's start-up cache for a QA launch, as the Flint app uses it (app/src-tauri/src/jvmcache.rs), for both QA
// runners (FLINTQA_AOT=DIR): the client jar becomes an unsigned copy (qa/tools/Unsign.java, as the app's
// unsigned_client_jar); without a cache for this Minecraft the launch records the classes it loads, and the cache is built
// after the game exits cleanly; with one, the launch starts from it. One cache per Minecraft version in DIR (the QA runs
// keep the same Java and options, which the app's key covers).
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const QUIET = ["-Xlog:aot=off", "-Xlog:cds=off"];

/** The launch's options with the cache's: { args, record, createArgs } (createArgs: build the cache after a recording). */
export function aotPlan(dir, mc, javaArgs, clientJar) {
	fs.mkdirSync(dir, { recursive: true });
	const cp = javaArgs.indexOf("-cp");
	const unsigned = path.join(dir, path.basename(clientJar, ".jar") + "-unsigned.jar");
	if (!fs.existsSync(unsigned)) {
		const jdk = process.env.FLINTQA_JDK ?? "java";
		const made = spawnSync(jdk, [path.join(HERE, "tools", "Unsign.java"), clientJar, unsigned], { stdio: "inherit" });
		if (made.status !== 0) throw new Error("couldn't make the unsigned client jar");
	}
	const args = [...javaArgs];
	args[cp + 1] = javaArgs[cp + 1].split(";").map((jar) => (jar === clientJar ? unsigned : jar)).join(";");
	const main = args.findIndex((a, i) => i > cp + 1 && !a.startsWith("-"));
	const jvmOnly = args.slice(0, main).filter((a) => !a.startsWith("-Dflintqa."));
	const name = mc.replace(/[^\w.]/g, "_");
	const cache = path.join(dir, name + ".aot");
	const config = path.join(dir, name + ".aotconf");
	if (fs.existsSync(cache)) {
		args.splice(main, 0, `-XX:AOTCache=${cache}`, ...QUIET);
		return { args, record: false, note: "in use" };
	}
	args.splice(main, 0, "-XX:AOTMode=record", `-XX:AOTConfiguration=${config}`, ...QUIET);
	const createArgs = [...jvmOnly, "-XX:AOTMode=create", `-XX:AOTConfiguration=${config}`, `-XX:AOTCache=${cache}`, ...QUIET];
	return { args, record: true, createArgs, note: "recording" };
}

/** After a recording launch exited cleanly: builds the cache, as the app does in the background. */
export function buildCache(java, plan, log) {
	const t = Date.now();
	const built = spawnSync(java, plan.createArgs, { stdio: ["ignore", "pipe", "pipe"], encoding: "utf8" });
	log(`start-up cache built: exit ${built.status} in ${Math.round((Date.now() - t) / 1000)} s`);
	if (built.status !== 0) log((built.stdout ?? "").slice(-1500) + (built.stderr ?? "").slice(-1500));
}
