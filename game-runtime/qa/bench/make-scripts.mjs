// Dev only: writes the benchmark tours (QA scripts, one step per client tick) into %USERPROFILE%\FlintQA\bench\scripts.
// Each tour is one game session, every scenario in the same fixed-seed creative world (QaGame.creativeLevel, seed 20261016),
// so every configuration sees the same terrain, packs, entities and explosions:
//   standing / turning (default settings), the 32x pack, the 128x pack, both packs, render 24 / simulation 16, 300 mobs,
//   100 TNT going off, then the heap. `frames S NAME [DEG]` records every frame (QaFrames: average, 1% and 0.1% lows,
//   median, p99, max, hitches, GC, allocation).
// bench-shaders.txt is the shader run (Iris reads the shader pack from config/iris.properties, written by run-bench.mjs);
// bench-long.txt plays 10 minutes and logs the heap every minute (memory growth, frame-time stability).
import fs from "node:fs";
import path from "node:path";
import { BENCH, packName } from "./names.mjs";

const OUT = path.join(BENCH, "scripts");
fs.mkdirSync(OUT, { recursive: true });

const settle = (s) => [`sleep ${s}`];
const header = () => [
	"wait-title 300",
	"sleep 3",
	"create-world bench",
	"wait-world 300 4",
	"command time set day",
	"command weather clear",
	"option enableVsync false",
	"option framerateLimit 260",
	"option renderDistance 12",
	"option simulationDistance 12",
	"option inactivityFpsLimit minimized",
	"command tp @s 0 ~ 0 0 5",
	"command tp @s ~ ~ ~ 0 5",
	...settle(20),
];

// A grid of N things in front of the player, each its own summon (one step per tick).
function grid(n, spacing, ahead, summon) {
	const side = Math.ceil(Math.sqrt(n));
	const out = [];
	for (let i = 0; i < n; i++) {
		const x = (i % side) * spacing - Math.floor(side / 2) * spacing;
		const z = ahead + Math.floor(i / side) * spacing;
		out.push(`command ${summon(x, z)}`);
	}
	return out;
}

function modern({ normalPack, demandingPack }) {
	const steps = [
		"# Flint benchmark: one session, every scenario in the same world.",
		...header(),
		"mem",
		"frames 20 standing",
		"frames 30 turning 45",
		"mem",
	];
	if (normalPack) steps.push(`pack file/${normalPack} on`, ...settle(10), "frames 30 pack-32x 45");
	if (demandingPack) {
		if (normalPack) steps.push(`pack file/${normalPack} off`);
		steps.push(`pack file/${demandingPack} on`, ...settle(10), "frames 30 pack-128x 45");
		if (normalPack) steps.push(`pack file/${normalPack} on`, ...settle(10), "frames 30 packs-both 45", `pack file/${normalPack} off`);
		steps.push(`pack file/${demandingPack} off`, ...settle(8));
	}
	steps.push(
		"option renderDistance 24",
		"option simulationDistance 16",
		...settle(30),
		"frames 30 far-24-16 45",
		"option renderDistance 12",
		"option simulationDistance 12",
		...settle(10),
		"command tp @s 0 ~ 0 0 5",
		...grid(300, 2, 6, (x, z) => `summon minecraft:cow ~${x} ~ ~${z} {NoAI:1b,Silent:1b}`),
		...settle(5),
		"frames 20 mobs-300",
		"frames 20 mobs-300-turning 45",
		"command kill @e[type=minecraft:cow]",
		"command tp @s 0 ~ 0 0 5",
		...settle(5),
		...grid(100, 3, 14, (x, z) => `summon minecraft:tnt ~${x} ~ ~${z} {fuse:100}`),
		"frames 12 tnt-100",
		"command kill @e[type=minecraft:item]",
		...settle(5),
		"mem",
		"quit",
	);
	return steps;
}

function shaders() {
	return ["# Shader run: Iris with the shader pack from config/iris.properties.", ...header(), ...settle(10), "frames 20 shaders-standing", "frames 30 shaders-turning 45", "mem", "quit"];
}

// Multiplayer (26.3 only: the local test server is 26.3): the same views on a server on this computer (127.0.0.1:25599,
// offline mode, loopback only), so the server's own load is part of it and the network is not (loopback latency is ~0).
function multiplayer() {
	return [
		"# Multiplayer run on the local test server.",
		"wait-title 300",
		"sleep 3",
		"connect 127.0.0.1:25599",
		"wait-world 300 4",
		"option enableVsync false",
		"option framerateLimit 260",
		"option renderDistance 12",
		"option inactivityFpsLimit minimized",
		...settle(20),
		"frames 20 mp-standing",
		"frames 30 mp-turning 45",
		"mem",
		"quit",
	];
}

function long() {
	const steps = ["# Ten minutes in the world: heap every minute, frames throughout.", ...header(), "mem"];
	for (let minute = 1; minute <= 10; minute++) steps.push(`frames 55 minute-${minute} 30`, "mem");
	steps.push("quit");
	return steps;
}

// 1.8.9 (its own QA mod): no pack step, so the pack scenarios are separate sessions with the pack chosen in options.txt
// (run-bench.mjs); render distance 16 is its "far" (the default slider's end), and it has no simulation distance.
function legacy({ packs }) {
	const steps = [
		"# Flint benchmark on 1.8.9: one session, every scenario in the same world.",
		"wait-title 300",
		"sleep 3",
		"create-world bench default 20261016",
		"wait-world 300 4",
		"command time set day",
		"command weather clear",
		"look 0 5",
		...settle(20),
		"mem",
		"frames 20 standing",
		"frames 30 turning 45",
	];
	if (packs) {
		steps.push("mem", "quit");
		return steps;
	}
	steps.push(
		"mem",
		"render-distance 16",
		...settle(30),
		"frames 30 far-16 45",
		"render-distance 12",
		...settle(10),
		"look 0 5",
		...grid(300, 2, 6, (x, z) => `summon Cow ~${x} ~ ~${z} {NoAI:1}`),
		...settle(5),
		"frames 20 mobs-300",
		"frames 20 mobs-300-turning 45",
		"command kill @e[type=Cow]",
		"look 0 5",
		...settle(5),
		...grid(100, 3, 14, (x, z) => `summon PrimedTnt ~${x} ~ ~${z} {Fuse:100}`),
		"frames 12 tnt-100",
		"command kill @e[type=Item]",
		...settle(5),
		"mem",
		"quit",
	);
	return steps;
}

const assets = JSON.parse(fs.readFileSync(path.join(BENCH, "assets.json"), "utf8"));
for (const [version, entry] of Object.entries(assets)) {
	if (version === "1.8.9") {
		fs.writeFileSync(path.join(OUT, "bench-1.8.9.txt"), legacy({ packs: false }).join("\n") + "\n");
		fs.writeFileSync(path.join(OUT, "bench-1.8.9-packs.txt"), legacy({ packs: true }).join("\n") + "\n");
		continue;
	}
	const script = modern({ normalPack: packName(entry.normalPack), demandingPack: packName(entry.demandingPack) });
	fs.writeFileSync(path.join(OUT, `bench-${version}.txt`), script.join("\n") + "\n");
}
fs.writeFileSync(path.join(OUT, "bench-shaders.txt"), shaders().join("\n") + "\n");
fs.writeFileSync(path.join(OUT, "bench-long.txt"), long().join("\n") + "\n");
fs.writeFileSync(path.join(OUT, "bench-mp.txt"), multiplayer().join("\n") + "\n");
console.log(fs.readdirSync(OUT).join("\n"));
