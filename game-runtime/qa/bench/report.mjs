// Dev only: turns the benchmark results (results/<tag>.jsonl from run-bench.mjs, results/<tag>-launch.jsonl from
// launch-chain.mjs) into Markdown tables. Runs a build overlapped (disturbedBy) are left out and counted.
// usage: node game-runtime/qa/bench/report.mjs --fps base055,base055r2 --launch chain055,chain056 > report.md
import fs from "node:fs";
import path from "node:path";
import { BENCH } from "./names.mjs";

const opt = (name) => {
	const i = process.argv.indexOf(`--${name}`);
	return i > 0 ? process.argv[i + 1].split(",").filter(Boolean) : [];
};
const read = (file) => (fs.existsSync(file) ? fs.readFileSync(file, "utf8").trim().split(/\r?\n/).filter(Boolean).map((l) => JSON.parse(l)) : []);
const RESULTS = path.join(BENCH, "results");
const median = (xs) => {
	const v = xs.filter((x) => typeof x === "number").sort((a, b) => a - b);
	if (!v.length) return null;
	return v.length % 2 ? v[(v.length - 1) / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
};
const f1 = (x) => (x == null ? "n/a" : (Math.round(x * 10) / 10).toLocaleString("en-US"));
const s1 = (ms) => (ms == null ? "n/a" : `${(ms / 1000).toFixed(1)} s`);
const ORDER = ["26.3", "26.2", "26.1.2", "26.1.1", "26.1", "1.21.11", "1.21.10", "1.21.1", "1.8.9"];
const byVersion = (a, b) => ORDER.indexOf(a) - ORDER.indexOf(b);
const out = [];

// ---------- FPS ----------
const fps = opt("fps").flatMap((tag) => read(path.join(RESULTS, `${tag}.jsonl`)));
const disturbed = fps.filter((r) => r.disturbedBy?.length).length;
// 1.21.1 has no inactivityFpsLimit option (it came in 1.21.2): setting it fails that one step, and the run is still sound.
// 1.8.9's tour has no `option` step at all; its runner writes the same settings into options.txt before the start.
const harmless = (r) =>
	r.failures?.length &&
	r.failures.every((f) => /step option inactivityFpsLimit/.test(f) || (r.version === "1.8.9" && /unknown step option/.test(f))) &&
	Object.keys(r.frames ?? {}).length;
const clean = fps.filter((r) => !r.disturbedBy?.length && (r.exit === 0 || harmless(r)));
if (fps.length) {
	const machine = JSON.parse(fs.readFileSync(path.join(RESULTS, `${opt("fps")[0]}-machine.json`), "utf8"));
	out.push(`### Machine\n\n${machine.cpu} (${machine.cores} cores, ${machine.threads} threads), ${machine.ramGB} GB RAM, ${machine.gpus.map((g) => `${g.Name} (driver ${g.DriverVersion})`).join(", ")}, ${machine.os}, display ${machine.gpus[0]?.CurrentHorizontalResolution}×${machine.gpus[0]?.CurrentVerticalResolution} at ${machine.gpus[0]?.CurrentRefreshRate} Hz, ${String(machine.power).replace(/.*\(|\).*/g, "")} power plan.\n`);
	const reasons = [...new Set(fps.flatMap((r) => r.disturbedBy ?? []))].join(", ");
	out.push(`Runs: ${clean.length} counted, ${disturbed} left out (something else ran alongside: ${reasons || "none"}), ${fps.length - clean.length - disturbed} failed.\n`);
	const scenarios = [...new Set(clean.flatMap((r) => Object.keys(r.frames)))];
	const group = (scenario, config) =>
		ORDER.filter((v) => clean.some((r) => r.version === v && r.config === config && r.scenario === scenario));
	for (const scenario of ["main", "packs", "shaders", "mp", "long"]) {
		const rows = clean.filter((r) => r.scenario === scenario);
		if (!rows.length) continue;
		out.push(`\n### Scenario: ${scenario}\n`);
		const segs = [...new Set(rows.flatMap((r) => Object.keys(r.frames)))];
		const configs = [...new Set(rows.map((r) => r.config))];
		out.push(`| Version | Segment | ${configs.map((c) => `${c} avg FPS | ${c} 1% low | ${c} 0.1% low | ${c} p99 ms`).join(" | ")} |`);
		out.push(`|---|---|${configs.map(() => "---|---|---|---").join("|")}|`);
		for (const v of [...new Set(rows.map((r) => r.version))].sort(byVersion)) {
			for (const seg of segs) {
				const cells = configs.map((c) => {
					const hits = rows.filter((r) => r.version === v && r.config === c && r.frames[seg]).map((r) => r.frames[seg]);
					if (!hits.length) return "n/a | n/a | n/a | n/a";
					return `${f1(median(hits.map((h) => h.avgFps)))} | ${f1(median(hits.map((h) => h.low1)))} | ${f1(median(hits.map((h) => h.low01)))} | ${f1(median(hits.map((h) => h.p99Ms)))}`;
				});
				if (cells.every((c) => c.startsWith("n/a"))) continue;
				out.push(`| ${v} | ${seg} | ${cells.join(" | ")} |`);
			}
		}
		out.push("");
		out.push(`| Version | ${configs.map((c) => `${c} Java start→title | ${c} world load | ${c} heap after GC (MB) | ${c} peak RAM (MB) | ${c} CPU avg % | ${c} GPU 3D avg %`).join(" | ")} |`);
		out.push(`|---|${configs.map(() => "---|---|---|---|---|---").join("|")}|`);
		for (const v of [...new Set(rows.map((r) => r.version))].sort(byVersion)) {
			const cells = configs.map((c) => {
				const rs = rows.filter((r) => r.version === v && r.config === c);
				if (!rs.length) return "n/a | n/a | n/a | n/a | n/a | n/a";
				return [s1(median(rs.map((r) => r.titleMs))), s1(median(rs.map((r) => r.worldMs))), f1(median(rs.map((r) => r.mem.at(-1)?.usedMB))), f1(median(rs.map((r) => r.process?.workingSetPeakMB))), f1(median(rs.map((r) => r.process?.cpuAvgPct))), f1(median(rs.map((r) => r.process?.gpu3dAvgPct)))].join(" | ");
			});
			out.push(`| ${v} | ${cells.join(" | ")} |`);
		}
		if (scenario === "long") {
			out.push("\nHeap after a collection, minute by minute (memory growth):\n");
			for (const r of rows) out.push(`- ${r.version} ${r.config}: ${r.mem.map((m) => m.usedMB).join(" → ")} MB`);
		}
	}
	void scenarios;
	void group;
}

// ---------- launch chain ----------
const everyLaunch = opt("launch").flatMap((tag) => read(path.join(RESULTS, `${tag}-launch.jsonl`)));
const launch = everyLaunch.filter((r) => !r.disturbedBy?.length);
if (everyLaunch.length) {
	const tags = opt("launch");
	out.push(`\n### Play to playable, through the launcher\n`);
	out.push(`Medians per version. "first" is the launch right after installing (performance mods added, Java's start-up cache recorded); cold is the next one; warm the ones after. ${everyLaunch.length - launch.length} launch(es) left out: something else ran alongside (${[...new Set(everyLaunch.flatMap((r) => r.disturbedBy ?? []))].join(", ") || "none"}).\n`);
	out.push(`| Version | Launch | ${tags.map((t) => `${t} Play→Java | ${t} Play→window | ${t} Play→title | ${t} Play→world`).join(" | ")} |`);
	out.push(`|---|---|${tags.map(() => "---|---|---|---").join("|")}|`);
	for (const v of [...new Set(launch.map((r) => r.version))].sort(byVersion)) {
		for (const kind of ["first", "cold", "warm"]) {
			const cells = tags.map((t) => {
				const rs = launch.filter((r) => r.tag === t && r.version === v && r.kind === kind && !r.failures.length);
				if (!rs.length) return "n/a | n/a | n/a | n/a";
				const ms = (k) => median(rs.map((r) => r[k]));
				return `${f1(ms("playToJavaMs"))} ms | ${s1(ms("clickToWindowMs"))} | ${s1(ms("clickToTitleMs"))} | ${s1(ms("clickToWorldMs"))}`;
			});
			out.push(`| ${v} | ${kind} | ${cells.join(" | ")} |`);
		}
	}
}
console.log(out.join("\n"));
