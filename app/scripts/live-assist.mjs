#!/usr/bin/env node
// Live end-to-end check of Flint AI against a deployed STAGING gateway. Nothing is installed.
//
// One request:
//   FLINT_AI_GATEWAY_URL=https://<staging>.workers.dev FLINT_AI_ACCESS_KEY=<staging key> \
//     node scripts/live-assist.mjs 26.3 "Best FPS and PvP mods for Minecraft 26.3 Fabric."
// The evaluation set (src/lib/assistEvalSet.ts), with a precision table and every wrong pick:
//   FLINT_AI_GATEWAY_URL=… FLINT_AI_ACCESS_KEY=… node scripts/live-assist.mjs --eval 1.21.11 [--smart] [--only fps,shaders] [--record answers.json]
//   (--smart also runs Flint's smart search for each request; --smart-only runs only that, with no gateway and no model calls;
//    --replay answers.json re-runs answers saved by --record through the current planner and resolver, also without model calls.)
//
// 1. Asks the gateway's /v1/intents (staging-direct: the access key stands in for a Mojang session),
//    as a random test player (x-flint-test-player) for every request, so no real player's quota is used.
// 2. Plans Modrinth requests with the app's own planner (src/lib/assistPlan.ts); a forgotten-project
//    lookup searches Modrinth for each described feature, as the app does.
// 3. Runs Flint's real Rust resolver (debug build, FLINT_SELFTEST=resolve) in a temporary data folder
//    (under FLINT_LIVE_TMP when set).
// 4. Re-checks every chosen download on Modrinth: it must list this Minecraft version and loader.
// The access key is read from the environment and never printed. One model call per request (two
// if the model's first answer is malformed); the evaluation set is 9 requests.
import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { discoverLookup, lookupRequests, parseIntents, planRequests, tagItems } from "../src/lib/assistPlan.ts";
import { EVAL_SET, categoriesOf, isPick, judge } from "../src/lib/assistEvalSet.ts";

const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const option = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);
const evalMode = flag("--eval");
// --replay <file>: answers recorded by an earlier --record run instead of the gateway (no model calls).
const replay = evalMode && option("--replay") ? Object.fromEntries(JSON.parse(readFileSync(option("--replay"), "utf8")).cases.map((c) => [c.id, c])) : null;
// --smart-only: Flint's smart search alone (no gateway, no model calls).
const smartOnly = evalMode && (flag("--smart-only") || !!replay);
const positional = args.filter((a, i) => !a.startsWith("--") && !["--only", "--record", "--replay"].includes(args[i - 1]));
const version = positional[0] ?? (evalMode ? "1.21.11" : undefined);
const gateway = (process.env.FLINT_AI_GATEWAY_URL ?? "").replace(/\/+$/, "");
const key = process.env.FLINT_AI_ACCESS_KEY ?? "";
if (!version || (!evalMode && positional.length < 2) || (!smartOnly && (!gateway.startsWith("https://") || key.length < 32))) {
  console.error("usage: FLINT_AI_GATEWAY_URL=https://… FLINT_AI_ACCESS_KEY=… node scripts/live-assist.mjs <version> <request…>\n       … node scripts/live-assist.mjs --eval [version] [--smart] [--only id,…] [--record file.json]");
  process.exit(2);
}
// Staging only: production refuses the access key anyway, but this script must never spend a real player's searches.
if (!smartOnly && !/staging/i.test(new URL(gateway).hostname) && process.env.FLINT_ALLOW_ANY_GATEWAY !== "1") {
  console.error("refusing: FLINT_AI_GATEWAY_URL is not a staging gateway (set FLINT_ALLOW_ANY_GATEWAY=1 to override)");
  process.exit(2);
}
const UA = { "User-Agent": "IconicDemon/FlintClient (flintclient.com)" };
const BIN = fileURLToPath(new URL("../src-tauri/target.noindex/debug/flint-app", import.meta.url));
const FACETS = {
  mod: (v) => [["project_type:mod"], ["categories:fabric"], [`versions:${v}`], ["client_side:required", "client_side:optional"]],
  resourcepack: (v) => [["project_type:resourcepack"], [`versions:${v}`]],
  shader: (v) => [["project_type:shader"], ["categories:iris"], [`versions:${v}`]],
};
let modelRequests = 0;

/** One /v1/intents call as a fresh random test player. */
async function askGateway(prompt) {
  modelRequests++;
  const started = Date.now();
  const response = await fetch(`${gateway}/v1/intents`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-flint-access": key, "x-flint-test-player": randomBytes(16).toString("hex") },
    body: JSON.stringify({ requestId: `eval-${randomBytes(8).toString("hex")}`, prompt, gameVersion: version, loader: "fabric", installed: [] }),
  });
  const raw = await response.json().catch(() => null);
  return { status: response.status, seconds: ((Date.now() - started) / 1000).toFixed(1), raw, intents: response.ok ? parseIntents(raw) : null };
}

/** The Library's version-filtered Modrinth search, as the app's lookup runs it. */
async function search(kind, query) {
  const url = `https://api.modrinth.com/v2/search?query=${encodeURIComponent(query)}&limit=10&index=relevance&facets=${encodeURIComponent(JSON.stringify(FACETS[kind](version)))}`;
  const body = await (await fetch(url, { headers: UA })).json();
  return (body.hits ?? []).map((h) => ({ projectId: h.project_id, slug: h.slug, title: h.title, description: h.description, categories: h.categories }));
}

/** Flint's real resolver on these requests (debug build, isolated data folder). */
function resolve(requests) {
  const dir = mkdtempSync(join(process.env.FLINT_LIVE_TMP ?? tmpdir(), "flint-live-"));
  try {
    const file = join(dir, "requests.json");
    writeFileSync(file, JSON.stringify(requests.map(({ project, query, source, reason, kind }) => ({ project, query, source, reason, kind }))));
    const out = execFileSync(BIN, {
      env: { ...process.env, FLINT_DATA_DIR: join(dir, "data"), FLINT_SELFTEST: `resolve:${version}:${file}`, FLINT_AI_ACCESS_KEY: "" },
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
      timeout: 180_000,
    });
    const line = out.split("\n").find((l) => l.startsWith("[selftest] plan: "));
    const plan = line ? JSON.parse(line.slice("[selftest] plan: ".length)) : { error: "no plan printed" };
    if (plan.error) throw new Error(plan.error);
    return plan;
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** Plans, looks up, resolves and verifies one request; returns the tagged plan items. */
async function run(prompt, intents) {
  const planned = planRequests(prompt, intents);
  const found = planned.lookup ? await discoverLookup(planned.lookup, (q) => search(planned.lookup.kind, q)) : null;
  const requests = [...(found ? lookupRequests(found, planned.lookup) : []), ...planned.requests];
  if (!requests.length) return { planned, found, plan: { items: [], unmatched: [] }, items: [], bad: 0 };
  const plan = resolve(requests);
  const items = tagItems(plan.items, requests, plan.matches);
  const ids = plan.items.filter((i) => i.versionId).map((i) => i.versionId);
  const versions = ids.length ? await (await fetch(`https://api.modrinth.com/v2/versions?ids=${encodeURIComponent(JSON.stringify(ids))}`, { headers: UA })).json() : [];
  const byId = new Map(versions.map((v) => [v.id, v]));
  let bad = 0;
  for (const item of items) {
    const v = byId.get(item.versionId);
    const loaders = item.kind === "mod" ? ["fabric"] : item.kind === "shader" ? ["iris"] : ["minecraft"];
    item.verified = !!v && v.game_versions.includes(version) && v.loaders.some((l) => loaders.includes(l));
    if (item.status === "ok" && !item.verified) bad++;
  }
  return { planned, found, plan, items, bad };
}

function printGoals(intents) {
  for (const g of intents.goals) {
    console.log(`  goal ${g.category.padEnd(11)} ${g.kind.padEnd(12)} "${g.label}"${g.lookup ? ` lookup nameHint=${g.nameHint ?? "-"}` : ""} — ${g.why ?? "(no why)"}\n       projects: ${g.projects.join(", ") || "-"} | searches: ${g.searches.join(", ") || "-"}`);
  }
  console.log(`  constraints ${JSON.stringify(intents.constraints)}${intents.exclude.length ? ` exclude ${intents.exclude}` : ""}${intents.note ? `\n  note: ${intents.note}` : ""}`);
}

function printPlan({ items, plan, found, bad }) {
  if (found) console.log(`  lookup: ${found.identified ? "possible match" : "not identified"} · features ${found.features.join(" / ")}${found.nameHint ? ` · name hint ${found.nameHint}` : ""}`);
  console.log(`  plan: ${items.length} items, ${plan.unmatched.length} unmatched`);
  for (const item of items) {
    const mark = item.verified ? "✓" : item.status === "ok" ? "✗" : "·";
    console.log(`   ${mark} ${item.status.padEnd(11)} ${item.source.padEnd(10)} ${item.kind.padEnd(12)} ${item.title}${item.role ? ` [${item.role}]` : ""} ${item.versionNumber ?? ""}${item.goal ? ` · ${item.goal}` : ""}${item.reason ? ` — ${item.reason}` : ""}`);
  }
  for (const u of plan.unmatched) console.log(`   · unmatched ${u.query ?? JSON.stringify(u)} (${u.kind ?? "mod"})${u.message ? `: ${u.message}` : ""}`);
  if (bad) console.log(`  ✗ ${bad} installable item(s) did not verify for ${version}`);
}

if (!evalMode) {
  const prompt = positional.slice(1).join(" ");
  const answer = await askGateway(prompt);
  console.log(`\n=== ${prompt}\ngateway: HTTP ${answer.status} in ${answer.seconds}s${answer.raw?.model ? ` · ${answer.raw.model}` : ""}`);
  if (!answer.intents) {
    console.log(`no usable AI answer (${JSON.stringify(answer.raw)}); Flint would use its smart search instead`);
    process.exit(1);
  }
  printGoals(answer.intents);
  const result = await run(prompt, answer.intents);
  printPlan(result);
  console.log(result.bad ? "  ✗ some downloads did not verify" : `  ✓ every installable download verified on Modrinth for ${version}`);
  process.exitCode = result.bad ? 1 : 0;
} else {
  const only = option("--only")?.split(",");
  const cases = EVAL_SET.filter((c) => !only || only.includes(c.id));
  const modes = replay ? ["ai", ...(flag("--smart") ? ["smart"] : [])] : smartOnly ? ["smart"] : flag("--smart") ? ["ai", "smart"] : ["ai"];
  const rows = [];
  const wrong = [];
  const recorded = [];
  let unverified = 0;
  for (const c of cases) {
    for (const mode of modes) {
      let intents = null;
      let note = "";
      if (mode === "ai") {
        const answer = replay ? { status: replay[c.id]?.status ?? 0, seconds: "0", raw: replay[c.id]?.answer ?? null, intents: replay[c.id]?.status === 200 ? parseIntents(replay[c.id].answer) : null } : await askGateway(c.prompt);
        recorded.push({ id: c.id, prompt: c.prompt, status: answer.status, answer: answer.raw });
        console.log(`\n=== [${c.id} · Flint AI] ${c.prompt}\ngateway: HTTP ${answer.status} in ${answer.seconds}s${answer.raw?.model ? ` · ${answer.raw.model}` : ""}`);
        if (!answer.intents) {
          console.log(`  no usable AI answer (${JSON.stringify(answer.raw)?.slice(0, 200)}): the app would fall back to its smart search`);
          rows.push({ case: c.id, mode, goals: "-", picks: 0, relevant: 0, precision: "-", unmatched: 0, note: `HTTP ${answer.status}` });
          continue;
        }
        printGoals(answer.intents);
      } else {
        console.log(`\n=== [${c.id} · smart search] ${c.prompt}`);
      }
      intents = mode === "ai" ? parseIntents(recorded.at(-1).answer) : null;
      const result = await run(c.prompt, intents);
      printPlan(result);
      unverified += result.bad;
      const found = categoriesOf(result.planned.goals);
      const missing = c.goals.filter((alt) => !alt.split("|").some((x) => found.includes(x)));
      if (missing.length) note = `missing goal ${missing.join(", ")}`;
      const picks = result.items.filter(isPick);
      let relevant = 0;
      for (const item of picks) {
        const verdict = judge(c, item);
        if (verdict.ok) relevant++;
        else wrong.push({ case: c.id, mode, pick: `${item.title} (${item.kind}${item.goal ? `, ${item.goal}` : ""})`, why: verdict.why, reason: item.reason ?? "" });
      }
      if (c.lookup && result.found && !result.found.identified) note = [note, "lookup not identified"].filter(Boolean).join("; ");
      rows.push({
        case: c.id,
        mode,
        goals: found.join(","),
        picks: picks.length,
        relevant,
        precision: picks.length ? `${Math.round((100 * relevant) / picks.length)}%` : "-",
        unmatched: result.plan.unmatched.length,
        note,
      });
    }
  }
  console.log(`\nFlint AI evaluation · Minecraft ${version} Fabric · ${new Date().toISOString().slice(0, 16)} · ${modelRequests} gateway request(s)`);
  console.table(rows);
  for (const mode of modes) {
    const r = rows.filter((x) => x.mode === mode);
    const picks = r.reduce((n, x) => n + x.picks, 0);
    const ok = r.reduce((n, x) => n + x.relevant, 0);
    console.log(`${mode === "ai" ? "Flint AI" : "Smart search"}: ${ok}/${picks} picks relevant (${picks ? Math.round((100 * ok) / picks) : 0}%)`);
  }
  console.log(wrong.length ? "\nWrong recommendations:" : "\nNo wrong recommendations.");
  for (const w of wrong) console.log(`  ✗ [${w.case} · ${w.mode}] ${w.pick}: ${w.why}${w.reason ? ` — ${w.reason}` : ""}`);
  if (unverified) console.log(`\n✗ ${unverified} installable download(s) did not verify on Modrinth`);
  const record = option("--record");
  if (record) writeFileSync(record, `${JSON.stringify({ recorded: new Date().toISOString(), gameVersion: version, cases: recorded }, null, 2)}\n`);
  process.exitCode = unverified ? 1 : 0;
}
