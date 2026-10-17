// Run with `npm test`. Flint AI's offline evaluation: every request in the evaluation set
// (assistEvalSet.ts) is planned from recorded AI answers and with the smart search alone, then
// "resolved" against recorded Modrinth data with the same relevance gate the Rust resolver uses
// (shared cases in relevanceCases.json). Every pick is judged; the precision table is printed.
// The live version of this is `scripts/live-assist.mjs --eval` (staging).
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { EVAL_SET, categoriesOf, judge, type EvalCase, type JudgedItem } from "./assistEvalSet.ts";
import {
  GROUP,
  LEFTOVER_LABEL,
  discoverLookup,
  lookupRequests,
  matchesQuery,
  parseIntents,
  planRequests,
  queryTerms,
  type AiIntents,
  type PlannedRequest,
  type SearchHit,
} from "./assistPlan.ts";

type Recorded = { project_id: string; slug: string; title: string; description: string; project_type?: string; categories?: string[] };
const fixtures = JSON.parse(readFileSync(new URL("./assistEvalFixtures.json", import.meta.url), "utf8")) as {
  recorded: Record<string, unknown>;
  contract: Record<string, unknown>;
  projects: Record<string, Recorded>;
  searches: Record<string, Recorded[]>;
};
const relevance = JSON.parse(readFileSync(new URL("./relevanceCases.json", import.meta.url), "utf8")) as { cases: { query: string; match: boolean; hit: SearchHit }[] };

const hits = (kind: string, query: string, limit: number): Recorded[] => {
  const found = fixtures.searches[`${kind}|${limit}|${query}`];
  assert.ok(found, `no recorded Modrinth search for ${kind} "${query}" (re-record the fixtures)`);
  return found;
};

/**
 * What Flint's resolver would pick for each request, on recorded data: a named project as itself
 * (it must be the kind asked for), a search as its first hit that matches it (`matches_query`).
 */
function resolveOffline(requests: PlannedRequest[]): { picks: (JudgedItem & { reason: string })[]; unmatched: string[] } {
  const picks: (JudgedItem & { reason: string })[] = [];
  const unmatched: string[] = [];
  for (const r of requests) {
    let found: Recorded | undefined;
    if (r.project) found = fixtures.projects[r.project] ?? Object.values(fixtures.searches).flat().find((h) => h.project_id === r.project);
    else found = hits(r.kind, r.query!, 5).find((h) => matchesQuery(r.query!, h) && !picks.some((p) => p.slug === h.slug));
    if (!found || (found.project_type && found.project_type !== r.kind)) {
      unmatched.push(r.project ?? r.query!);
      continue;
    }
    if (picks.some((p) => p.slug === found.slug)) continue;
    picks.push({ title: found.title, slug: found.slug, description: found.description, kind: r.kind, source: r.source, status: "ok", group: r.group, reason: r.reason });
  }
  return { picks, unmatched };
}

async function evaluate(c: EvalCase, intents: AiIntents | null) {
  const planned = planRequests(c.prompt, intents);
  const found = planned.lookup ? await discoverLookup(planned.lookup, async (q) => fixtures.searches[`${planned.lookup!.kind}|10|${q}`] ?? []) : null;
  const requests = [...(found && planned.lookup ? lookupRequests(found, planned.lookup) : []), ...planned.requests];
  const { picks, unmatched } = resolveOffline(requests);
  const verdicts = picks.map((p) => ({ pick: p, ...judge(c, p) }));
  return { planned, found, requests, picks, unmatched, verdicts };
}

const answer = (raw: unknown) => {
  const parsed = parseIntents(raw);
  assert.ok(parsed, "recorded answer parses");
  return parsed;
};

test("evaluation set: recorded Flint AI answers and the smart search give only relevant, correctly typed picks", async () => {
  const rows: Record<string, string | number>[] = [];
  const wrong: string[] = [];
  for (const c of EVAL_SET) {
    const runs: [string, AiIntents | null][] = [["smart", null]];
    if (fixtures.recorded[c.id]) runs.push(["ai (recorded)", answer(fixtures.recorded[c.id])]);
    if (fixtures.contract[c.id]) runs.push(["ai (new contract)", answer(fixtures.contract[c.id])]);
    for (const [mode, intents] of runs) {
      const r = await evaluate(c, intents);
      const cats = categoriesOf(r.planned.goals);
      const missing = c.goals.filter((alt) => !alt.split("|").some((x) => cats.includes(x as never)));
      const ok = r.verdicts.filter((v) => v.ok).length;
      for (const v of r.verdicts.filter((x) => !x.ok)) wrong.push(`[${c.id} · ${mode}] ${v.pick.title} (${v.pick.kind}, ${v.pick.group}): ${v.why}`);
      rows.push({ case: c.id, mode, goals: cats.join(","), picks: r.picks.length, relevant: ok, precision: r.picks.length ? `${Math.round((100 * ok) / r.picks.length)}%` : "-", unmatched: r.unmatched.length, missing: missing.join(",") });
      assert.deepEqual(missing, [], `${c.id} · ${mode}: goals ${cats}`);
      if (intents) assert.ok(!r.planned.goals.some((g) => g.role === "leftover" || g.label === LEFTOVER_LABEL), `${c.id}: no leftover-word searches on top of an AI answer`);
      if (c.id !== "noise") assert.ok(r.picks.length > 0, `${c.id} · ${mode}: has picks`);
    }
  }
  console.log("\nFlint AI evaluation (offline, recorded data)");
  console.table(rows);
  if (wrong.length) console.log(`Wrong recommendations:\n  ${wrong.join("\n  ")}`);
  assert.deepEqual(wrong, []);
});

test("the owner's FPS + PvP prompt: no search for 'boosting', and the gate rejects what it found", async () => {
  const c = EVAL_SET.find((x) => x.id === "fps+pvp")!;
  for (const intents of [answer(fixtures.contract["fps+pvp"]), answer(fixtures.recorded["fps+pvp"]), null]) {
    const { requests } = planRequests(c.prompt, intents);
    assert.ok(!requests.some((r) => /boost/i.test(r.query ?? "")), JSON.stringify(requests.map((r) => r.query ?? r.project)));
    assert.ok(!requests.some((r) => r.group === LEFTOVER_LABEL));
  }
  const { picks } = await evaluate(c, answer(fixtures.contract["fps+pvp"]));
  assert.deepEqual(picks.map((p) => p.slug).sort(), ["armor-hud", "lithium", "sodium", "status-effect-bars", "zoomify"]);
  // The search that produced "Disable Elytra Outside The End" in production: every recorded hit is rejected.
  for (const word of ["boosting", "useful", "lets", "game"]) {
    const found = hits("mod", word, 5);
    assert.ok(found.length > 0);
    assert.ok(found.every((h) => !matchesQuery(word, h)), `${word}: ${found.filter((h) => matchesQuery(word, h)).map((h) => h.title)}`);
  }
});

test("the relevance gate decides the shared cases exactly as the Rust resolver does", () => {
  for (const { query, match, hit } of relevance.cases) assert.equal(matchesQuery(query, hit), match, `${query} vs ${hit.title} (terms ${queryTerms(query)})`);
});

test("shader requests: shader packs only under Shader packs; Iris is the required loader, not a pick", async () => {
  const c = EVAL_SET.find((x) => x.id === "shaders")!;
  for (const intents of [null, answer(fixtures.recorded.shaders), answer(fixtures.contract.shaders)]) {
    const { planned, picks } = await evaluate(c, intents);
    const shaderPicks = picks.filter((p) => p.kind === "shader");
    assert.ok(shaderPicks.length >= 1, "returns shader packs");
    assert.ok(shaderPicks.every((p) => p.group === GROUP.shaders && !/^(iris|sodium)$/.test(p.slug)));
    assert.ok(!planned.requests.some((r) => r.project === "iris"), "Iris comes as the resolver's labelled dependency");
    assert.ok(planned.requests.filter((r) => r.project === "sodium").every((r) => r.kind === "mod" && r.group === GROUP.performance));
  }
  // Named loaders stay mods, shown as shader support rather than packs.
  const named = planRequests("install sodium and iris", answer(fixtures.recorded.named)).requests;
  assert.deepEqual(named.map((r) => `${r.project}:${r.kind}:${r.group}`), [`sodium:mod:${GROUP.performance}`, `iris:mod:${GROUP.shaderSupport}`]);
});

test("resource-pack and mod requests keep their kinds", async () => {
  for (const [id, kind] of [["packs", "resourcepack"], ["pvp", "mod"], ["fps", "mod"], ["low-end", "mod"]] as const) {
    const c = EVAL_SET.find((x) => x.id === id)!;
    for (const intents of [null, answer(fixtures.recorded[id])]) {
      const { picks } = await evaluate(c, intents);
      assert.ok(picks.length && picks.every((p) => p.kind === kind), `${id}: ${picks.map((p) => `${p.title}:${p.kind}`)}`);
    }
  }
});

test("a forgotten mod described by its features is found as a possible match, never claimed", async () => {
  const c = EVAL_SET.find((x) => x.id === "forgotten")!;
  for (const intents of [null, answer(fixtures.contract.forgotten)]) {
    const { planned, found, picks, requests } = await evaluate(c, intents);
    assert.ok(planned.lookup, "recognised as a single-project lookup");
    assert.equal(planned.lookup.nameHint, "L");
    assert.equal(planned.requests.length, 0, "the description isn't split into separate wishes");
    assert.ok(found?.identified);
    assert.equal(picks[0].slug, "bactromod", "matches all four described features");
    assert.ok(picks.length <= 3);
    assert.match(requests[0].reason, /^Possible match — matches .*fire.*shield.*pumpkin blur.*fog.* \(doesn't start with “L”\)$/);
    assert.ok(requests.every((r) => r.group === GROUP.lookup));
  }
});

test("a lookup that matches fewer than two features is reported as not identified", async () => {
  const found = await discoverLookup({ features: ["low fire", "glowing bananas"], nameHint: null, projects: [] }, async (q) =>
    q === "low fire" ? (fixtures.searches["mod|10|low fire"] as SearchHit[]) : [],
  );
  assert.equal(found.identified, false);
  assert.ok(found.candidates.length > 0 && found.candidates.every((x) => x.matched.length === 1));
  const plan = { features: found.features, nameHint: null, projects: [], kind: "mod" as const, from: "smart" as const };
  assert.ok(lookupRequests(found, plan).every((r) => r.group === GROUP.closest && r.reason.startsWith("Closest match — matches low fire")));
});
