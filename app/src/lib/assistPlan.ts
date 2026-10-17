/**
 * Turns what the player asked for into Modrinth requests and groups the checked result for display.
 * Goals come from Flint AI (validated again here) or from the deterministic smart search
 * (`understand()`); AI goals are merged with any smart-search goal the AI missed, so nothing the
 * player asked for is dropped. Projects named here are hints only: Flint's resolver checks every
 * one against Modrinth, the Minecraft version and the loader. Pure: no Tauri or API imports.
 */
import { CATEGORIES, GOALS, hasLookupCue, isPackFeature, lookupRequest, understand, type Category } from "./intents.ts";

export type { Category };

export type Kind = "mod" | "resourcepack" | "shader";

/** One project the player described but couldn't name: its features (searched one by one) and what they remember of the name. */
export type LookupGoal = { features: string[]; nameHint: string | null; projects: string[] };

export type PlanGoal = {
  category: Category;
  label: string;
  projects: string[];
  searches: string[];
  from: "ai" | "smart";
  why?: string | null;
  kind?: Kind;
  /** Set for a forgotten-project lookup, which is answered by targeted discovery instead of requests. */
  lookup?: LookupGoal;
  /** Smart-search goals that aren't recognised wishes: projects the player named, or a search for leftover words. */
  role?: "named" | "leftover";
};

export type AiIntents = {
  goals: { category: Category; kind: Kind; label: string; why: string | null; searches: string[]; projects: string[]; lookup?: boolean; nameHint?: string | null }[];
  constraints: { lowEndHardware: boolean; ramGb: number | null; keepVanilla: boolean };
  exclude: string[];
  note: string | null;
  model: string | null;
  /** The server's count after this search, when the gateway sent one. */
  quota?: { limit: number; remaining: number; resetInMs: number | null } | null;
};

export type PlannedRequest = { project?: string; query?: string; source: "user" | "assistant"; reason: string; group: string; category: Category; kind: Kind };

export const GROUP = {
  performance: "Performance essentials",
  pvp: "PvP & HUD",
  qol: "Quality of life",
  visual: "Visual enhancements",
  maps: "Maps",
  packs: "Resource packs",
  shaders: "Shader packs",
  shaderSupport: "Shader support",
  lookup: "Possible matches",
  closest: "Closest matches",
  named: "Projects you named",
  picks: "Your picks",
  dependencies: "Required dependencies",
  optional: "Optional extras",
} as const;

const GROUP_OF: Partial<Record<Category, string>> = {
  performance: GROUP.performance,
  lowEnd: GROUP.performance,
  pvp: GROUP.pvp,
  hud: GROUP.pvp,
  qol: GROUP.qol,
  inventory: GROUP.qol,
  survival: GROUP.qol,
  visual: GROUP.visual,
  shaders: GROUP.shaderSupport,
  minimap: GROUP.maps,
};

const DEFAULT_LABEL: Record<Category, string> = {
  performance: "Performance",
  lowEnd: "Low-end PC",
  pvp: "PvP",
  hud: "HUD",
  inventory: "Inventory",
  qol: "Quality of life",
  visual: "Visuals",
  shaders: "Shaders",
  minimap: "Maps",
  building: "Building",
  replay: "Replay & cinematics",
  survival: "Vanilla survival",
  exploration: "Exploration",
  ui: "Interface",
  other: "More from your request",
};

/** The label of the search goal for words no goal explained; not a goal the player would recognise. */
export const LEFTOVER_LABEL = DEFAULT_LABEL.other;

const PERFORMANCE: ReadonlySet<Category> = new Set(["performance", "lowEnd"]);
/** Goals that change how the game looks, left out of AI plans that should keep vanilla unless the player asked for them. */
const LOOKS: ReadonlySet<Category> = new Set(["visual", "shaders"]);
/** Flint's own performance picks, shown under performance wherever they were suggested from. */
const PERFORMANCE_PROJECTS: ReadonlySet<string> = new Set(GOALS.filter((g) => PERFORMANCE.has(g.category)).flatMap((g) => g.projects ?? []));
/** Most requests Flint resolves at once (the resolver's own limit). */
export const MAX_REQUESTS = 16;
/** Performance picks per goal when the player also asked for something else, so "FPS + PvP" isn't all FPS mods. */
export const PERFORMANCE_CAP = 3;
const GOAL_CAP = 6;
/** An AI goal naming at least this many projects needs no extra searches (they mostly add noise). */
const NAMED_ENOUGH = 2;

/** Words that say what kind of mod is wanted without naming a feature ("survival mods", "qol"). */
const GENERIC = new Set(
  "mod mods modpack modpacks best good top great nice popular essential essentials pvp survival qol quality of life performance fps boost booster lightweight light hud minimap map maps improvement improvements tweak client fabric minecraft vanilla low end lowend low-end optimization optimisation optimizations optimisations setup pack packs utility utilities tools and for the a shader shaders shaderpack shaderpacks resource resourcepack resourcepacks texture textures looking beautiful pretty".split(" "),
);
/** Mods that load or speed up shaders: never shader packs. Flint adds Iris itself whenever a shader pack is picked. */
const SHADER_SUPPORT: ReadonlySet<string> = new Set(["iris", "sodium", "oculus", "optifine", "optifabric", "sodium-extra", "reeses-sodium-options"]);
/** A search made only of generic words would return whatever is popular, not what was asked for. */
export const isGenericSearch = (q: string) => q.toLowerCase().split(/[^a-z0-9-]+/).filter(Boolean).every((w) => GENERIC.has(w));

/** Mods that do the same job: only the first one named in a plan is kept. */
const ALTERNATIVES: string[][] = [
  ["xaeros-minimap", "journeymap"],
  ["emi", "jei", "roughly-enough-items"],
];

/** Keeps the first of each set of interchangeable mods across all goals (two minimaps at once help nobody). */
export function dropAlternatives(goals: PlanGoal[]): PlanGoal[] {
  const chosen = new Map<number, string>();
  return goals
    .map((g) => ({
      ...g,
      projects: g.projects.filter((p) => {
        const set = ALTERNATIVES.findIndex((a) => a.includes(p));
        if (set < 0) return true;
        if (!chosen.has(set)) chosen.set(set, p);
        return chosen.get(set) === p;
      }),
    }))
    .filter((g) => g.projects.length || g.searches.length || g.lookup);
}

/** The display group a goal's picks are shown under. */
export function groupTitle(goal: { category: Category; label: string; kind?: Kind; lookup?: LookupGoal; role?: PlanGoal["role"] }): string {
  if (goal.lookup) return GROUP.lookup;
  if (goal.kind === "resourcepack") return GROUP.packs;
  if (goal.kind === "shader") return GROUP.shaders;
  if (goal.role === "named") return GROUP.named;
  return GROUP_OF[goal.category] ?? (goal.label || DEFAULT_LABEL[goal.category]);
}

const SLUG = /^[a-z0-9_.-]{1,64}$/;
const isCategory = (c: unknown): c is Category => typeof c === "string" && (CATEGORIES as readonly string[]).includes(c);
const text = (v: unknown, max: number) => (typeof v === "string" ? v.replace(/[\u0000-\u001f\u007f]/g, "").slice(0, max).trim() : "");
const list = (v: unknown) => (Array.isArray(v) ? v.slice(0, 32).filter((x): x is string => typeof x === "string") : []);

const DAY_MS = 48 * 3600_000;
function parseQuota(raw: unknown): AiIntents["quota"] {
  if (!raw || typeof raw !== "object") return null;
  const q = raw as Record<string, unknown>;
  const limit = typeof q.limit === "number" && Number.isInteger(q.limit) && q.limit > 0 && q.limit <= 100 ? q.limit : null;
  const remaining = typeof q.remaining === "number" && Number.isInteger(q.remaining) && q.remaining >= 0 ? q.remaining : null;
  if (limit === null || remaining === null || remaining > limit) return null;
  const reset = typeof q.resetInMs === "number" && q.resetInMs >= 0 && q.resetInMs <= DAY_MS ? q.resetInMs : null;
  return { limit, remaining, resetInMs: reset };
}

/** Validates an AI answer (already checked in Rust; checked again because it is untrusted). Returns null if unusable. */
export function parseIntents(raw: unknown): AiIntents | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const r = raw as Record<string, unknown>;
  if (!Array.isArray(r.goals)) return null;
  const goals: AiIntents["goals"] = [];
  for (const g of r.goals.slice(0, 16)) {
    if (!g || typeof g !== "object") continue;
    const goal = g as Record<string, unknown>;
    if (!isCategory(goal.category)) continue;
    const searches = [...new Set(list(goal.searches).map((s) => text(s.replace(/[^\p{L}\p{N} '&+.-]/gu, " ").replace(/\s+/g, " "), 40)).filter((s) => s.length >= 2))].slice(0, 4);
    const projects = [...new Set(list(goal.projects).map((p) => p.trim().toLowerCase()).filter((p) => SLUG.test(p) && /[a-z0-9]/.test(p)))].slice(0, 6);
    if (!searches.length && !projects.length) continue;
    const kind: Kind = goal.kind === "resourcepack" ? "resourcepack" : goal.kind === "shader" && (goal.category === "shaders" || goal.category === "visual") ? "shader" : "mod";
    const lookup = goal.lookup === true && searches.length > 0;
    const hint = lookup ? text(typeof goal.nameHint === "string" ? goal.nameHint.replace(/[^\p{L}\p{N} ]/gu, " ").replace(/\s+/g, " ") : "", 24) : "";
    goals.push({ category: goal.category, kind, label: text(goal.label, 40), why: text(goal.why, 140) || null, searches, projects, ...(lookup ? { lookup: true, nameHint: hint || null } : {}) });
    if (goals.length === 8) break;
  }
  if (!goals.length) return null;
  const c = (r.constraints && typeof r.constraints === "object" ? r.constraints : {}) as Record<string, unknown>;
  const ram = typeof c.ramGb === "number" && Number.isFinite(c.ramGb) && c.ramGb >= 0.5 && c.ramGb <= 1024 ? c.ramGb : null;
  return {
    goals,
    constraints: { lowEndHardware: c.lowEndHardware === true, ramGb: ram, keepVanilla: c.keepVanilla === true },
    exclude: [...new Set(list(r.exclude).map((e) => text(e, 40)).filter(Boolean))].slice(0, 8),
    note: text(r.note, 240) || null,
    model: typeof r.model === "string" && /^[\w@/.:-]{1,80}$/.test(r.model) ? r.model : null,
    quota: parseQuota(r.quota),
  };
}

const LOOKUP_LABEL = "The project you described";

/**
 * The smart search's goals for a request: projects the player named, recognised wishes, and one
 * search goal for whatever no goal explained. A forgotten-project description ("a mod starting with
 * L that lowers fire and …") is one lookup goal instead, since it names one project's features.
 */
export function smartGoals(prompt: string): PlanGoal[] {
  const lookup = lookupRequest(prompt);
  if (lookup) return [{ category: "other", label: LOOKUP_LABEL, projects: [], searches: [], from: "smart", kind: lookup.kind, lookup: { features: lookup.features, nameHint: lookup.nameHint, projects: [] } }];
  const understood = understand(prompt);
  const goals: PlanGoal[] = [];
  for (const kind of ["mod", "shader", "resourcepack"] as const) {
    const projects = understood.named.filter((n) => n.kind === kind).map((n) => n.project);
    if (projects.length) goals.push({ category: kind === "shader" ? "shaders" : kind === "resourcepack" ? "visual" : "other", label: GROUP.named, projects, searches: [], from: "smart", kind, role: "named" });
  }
  goals.push(...understood.goals.map((g): PlanGoal => ({ category: g.category, label: g.label, projects: g.projects ?? [], searches: g.searches ?? [], from: "smart", kind: g.kind ?? "mod" })));
  if (understood.leftovers.length) goals.push({ category: "other", label: LEFTOVER_LABEL, projects: [], searches: understood.leftovers, from: "smart", role: "leftover" });
  return goals;
}

/**
 * Flint AI's goals for planning. A shader goal keeps only shader packs: loader mods named in it
 * (Iris, Sodium) become a separate "Shader support" mod goal, so they are never shown as packs.
 */
export function aiGoals(intents: AiIntents): PlanGoal[] {
  const out: PlanGoal[] = [];
  for (const g of intents.goals) {
    const base: PlanGoal = {
      category: g.category,
      label: g.label || DEFAULT_LABEL[g.category],
      projects: g.projects,
      // Named projects say it best; searches fill in only for goals without enough of them.
      searches: g.projects.length >= NAMED_ENOUGH ? [] : g.searches.filter((q) => !isGenericSearch(q)),
      from: "ai",
      why: g.why,
      kind: g.kind,
    };
    if (g.lookup) {
      out.push({ ...base, label: g.label || LOOKUP_LABEL, projects: [], searches: [], lookup: { features: g.searches, nameHint: g.nameHint ?? null, projects: g.projects } });
    } else if (g.kind === "shader" || (g.category === "shaders" && g.kind !== "resourcepack")) {
      const packs = g.projects.filter((p) => !SHADER_SUPPORT.has(p));
      const support = g.projects.filter((p) => SHADER_SUPPORT.has(p));
      const searches = packs.length >= NAMED_ENOUGH ? [] : g.searches.filter((q) => !isGenericSearch(q));
      if (packs.length || searches.length) out.push({ ...base, category: "shaders", kind: "shader", projects: packs, searches });
      if (support.length) out.push({ ...base, category: "shaders", kind: "mod", label: GROUP.shaderSupport, projects: support, searches: [] });
    } else {
      out.push(base);
    }
  }
  return out;
}

/** Words of four or more letters, for "did the AI already cover this?" checks. */
const keywords = (s: string) => s.toLowerCase().split(/[^a-z0-9]+/).filter((w) => w.length >= 4);

/**
 * AI goals first, then every recognised smart-search goal the AI missed: a category (of the same
 * kind: shader packs are not shader mods) the AI didn't use, a specific wish (zoom, food HUD, …)
 * whose projects and name the AI never mentioned, and projects the player named that the AI left
 * out. The smart search's searches for leftover words are never added to an AI answer: the AI read
 * the whole request, and single leftover words ("boosting", "useful") only find noise. When the AI
 * judged the computer not low-end ("a gaming PC with 32 GB"), the low-end goal isn't added back.
 */
export function mergeGoals(ai: PlanGoal[], smart: PlanGoal[], constraints?: AiIntents["constraints"]): PlanGoal[] {
  if (!ai.length) return smart;
  const aiText = ai.flatMap((g) => [g.label, ...g.searches, ...g.projects]).join(" ").toLowerCase();
  const out = [...ai];
  for (const goal of smart) {
    if (goal.category === "lowEnd" && constraints && !isLowEnd(constraints)) continue;
    if (goal.role === "leftover" || goal.lookup) continue;
    if (goal.role === "named") {
      const projects = goal.projects.filter((p) => !ai.some((g) => g.projects.includes(p)));
      if (projects.length) out.push({ ...goal, projects });
      continue;
    }
    const main = GOALS.find((g) => g.label === goal.label)?.id === goal.category;
    const sameCategory = ai.some((g) => g.category === goal.category && (g.kind ?? "mod") === (goal.kind ?? "mod"));
    const mentioned = goal.projects.some((p) => aiText.includes(p)) || keywords(goal.label).some((w) => aiText.includes(w));
    if (sameCategory && (main || mentioned)) continue;
    if (!sameCategory && mentioned && !main) continue;
    out.push(goal);
  }
  return out;
}

/** Drops excluded projects and searches ("no OptiFine", "not Sodium"); a goal left empty is dropped. */
export function applyExclusions(goals: PlanGoal[], exclude: string[]): PlanGoal[] {
  const terms = exclude.map((e) => e.toLowerCase().trim()).filter((e) => e.length >= 3);
  if (!terms.length) return goals;
  const hit = (value: string) => terms.some((t) => value.toLowerCase().includes(t) || value.toLowerCase().includes(t.replace(/\s+/g, "-")));
  return goals
    .filter((g) => !terms.includes(g.category.toLowerCase()) && !terms.includes(g.label.toLowerCase()))
    .map((g) => ({ ...g, projects: g.projects.filter((p) => !hit(p)), searches: g.searches.filter((s) => !hit(s)) }))
    .filter((g) => g.projects.length || g.searches.length || g.lookup);
}

const isLowEnd = (c: AiIntents["constraints"]) => c.lowEndHardware || (c.ramGb !== null && c.ramGb <= 4);

/**
 * AI constraints: low-end hardware (or 4 GB of memory or less) adds the low-end set, and
 * `keepVanilla` drops visual and shader goals unless the player's own words asked for them.
 */
export function applyConstraints(goals: PlanGoal[], constraints: AiIntents["constraints"], asked: Category[] = []): PlanGoal[] {
  const kept = constraints.keepVanilla ? goals.filter((g) => !LOOKS.has(g.category) || asked.includes(g.category)) : goals;
  if (!isLowEnd(constraints) || kept.some((g) => g.category === "lowEnd")) return kept;
  const g = GOALS.find((x) => x.id === "lowEnd")!;
  return [...kept, { category: "lowEnd", label: g.label, projects: g.projects ?? [], searches: [], from: "smart" }];
}

/**
 * One request per pick, taken round-robin across goals so each goal is represented before any goal
 * gets more; a pick shared by several goals is listed once, crediting each. Named projects come
 * before searches, so searches can avoid projects that were already named. AI picks are sent as
 * "assistant" requests, smart-search picks as the player's own ("user"). A shared pick is shown
 * under performance when any goal it serves is about performance, or when it is one of Flint's
 * performance picks, so Sodium never reads as a quality-of-life or visual mod.
 */
export function buildRequests(all: PlanGoal[], max = MAX_REQUESTS): PlannedRequest[] {
  // A lookup is answered by targeted discovery (`discoverLookup`), not by requests of its own.
  const goals = all.filter((g) => !g.lookup);
  const mixed = goals.some((g) => !PERFORMANCE.has(g.category));
  const queues = goals.map((g) => {
    const cap = mixed && PERFORMANCE.has(g.category) ? PERFORMANCE_CAP : GOAL_CAP;
    const kind: Kind = g.kind ?? "mod";
    return [...g.projects.map((project) => ({ project, kind })), ...g.searches.map((query) => ({ query, kind }))].slice(0, cap);
  });
  const picks: { project?: string; query?: string; kind: Kind; goals: PlanGoal[] }[] = [];
  const key = (p: { project?: string; query?: string; kind: Kind }) => `${p.kind}:${p.project ? `p:${p.project}` : `q:${p.query!.toLowerCase()}`}`;
  for (let round = 0; round < GOAL_CAP; round++) {
    goals.forEach((goal, i) => {
      const next = queues[i][round];
      if (!next) return;
      const existing = picks.find((p) => key(p) === key(next));
      if (existing) {
        if (!existing.goals.includes(goal)) existing.goals.push(goal);
      } else if (picks.length < max) {
        picks.push({ ...next, goals: [goal] });
      }
    });
  }
  const ordered = [...picks.filter((p) => p.project), ...picks.filter((p) => !p.project)];
  return ordered.map((p) => {
    const labels = [...new Set(p.goals.filter((g) => g.role !== "named").map((g) => g.label))];
    const first = p.goals[0];
    const shown =
      p.goals.find((g) => PERFORMANCE.has(g.category)) ?? (p.project && PERFORMANCE_PROJECTS.has(p.project) ? { category: "performance" as const, label: GROUP.performance } : first);
    return {
      ...(p.project ? { project: p.project } : { query: p.query }),
      // Picks from Flint AI are labelled as suggestions; the smart search's are the player's own words.
      source: first.from === "ai" ? "assistant" : "user",
      reason: labels.length ? `For ${labels.join(" + ")}${p.query ? " (Modrinth search)" : ""}` : "You named it",
      group: groupTitle({ ...shown, kind: p.kind }),
      category: shown.category,
      kind: p.kind,
    };
  });
}

export type GroupableItem = { projectId: string; slug: string; source: string; reason?: string | null; goal?: string | null };

/** Labels each requested item with the display group of the request it came from. */
export function tagItems<T extends GroupableItem>(items: T[], requests: PlannedRequest[], matches?: (string | null)[] | null): T[] {
  return items.map((item) => {
    if (item.source !== "user" && item.source !== "assistant") return item;
    let index = matches ? matches.indexOf(item.projectId) : -1;
    if (index < 0) index = requests.findIndex((r) => r.project && (r.project === item.slug || r.project === item.projectId));
    if (index < 0) index = requests.findIndex((r) => r.reason === item.reason);
    const group = requests[index]?.group;
    return group ? { ...item, goal: group } : item;
  });
}

export type DisplayGroup<T> = { id: string; title: string; kind: "goal" | "picks" | "dependency" | "optional"; items: T[]; why?: string | null };

/** Flint AI's one-line explanation for each display group (the first goal shown there that has one). */
export function groupReasons(goals: PlanGoal[]): Map<string, string> {
  const out = new Map<string, string>();
  for (const goal of goals) {
    const title = groupTitle(goal);
    if (goal.why && !out.has(title)) out.set(title, goal.why);
  }
  return out;
}

const ORDER: string[] = [GROUP.lookup, GROUP.closest, GROUP.named, GROUP.performance, GROUP.pvp, GROUP.qol, GROUP.visual, GROUP.shaders, GROUP.shaderSupport, GROUP.packs, GROUP.maps];

/** Groups plan items for display: goal groups in a fixed order, then other goals, the player's own picks, required dependencies and optional extras. */
export function groupPlan<T extends GroupableItem>(items: T[]): DisplayGroup<T>[] {
  const goals = new Map<string, T[]>();
  const picks: T[] = [];
  const dependencies: T[] = [];
  const optional: T[] = [];
  for (const item of items) {
    if (item.source === "dependency") dependencies.push(item);
    else if (item.source === "optional") optional.push(item);
    else if (item.goal) (goals.get(item.goal) ?? goals.set(item.goal, []).get(item.goal)!).push(item);
    else picks.push(item);
  }
  const titles = [...ORDER.filter((t) => goals.has(t)), ...[...goals.keys()].filter((t) => !ORDER.includes(t))];
  const slug = (t: string) => t.toLowerCase().replace(/[^a-z0-9]+/g, "-");
  return [
    ...titles.map((title) => ({ id: `goal-${slug(title)}`, title, kind: "goal" as const, items: goals.get(title)! })),
    ...(picks.length ? [{ id: "picks", title: GROUP.picks, kind: "picks" as const, items: picks }] : []),
    ...(dependencies.length ? [{ id: "dependencies", title: GROUP.dependencies, kind: "dependency" as const, items: dependencies }] : []),
    ...(optional.length ? [{ id: "optional", title: GROUP.optional, kind: "optional" as const, items: optional }] : []),
  ];
}

/** A lookup to run before resolving: the goal's features, name hint and kind, and whether Flint AI asked for it. */
export type LookupPlan = LookupGoal & { kind: Kind; from: "ai" | "smart" };

/**
 * Everything between a prompt and the resolver: goals (AI merged with the smart search, or the
 * smart search alone), the requests to resolve, and a forgotten-project lookup when there is one.
 * An AI lookup keeps the AI's own goals; a lookup only the smart search recognised replaces the
 * AI's goals, which then read the description as separate wishes. On a vanilla profile only
 * resource packs can be installed, so other goals are set aside and returned as `needsFabric`
 * (their labels) for the interface to explain.
 */
/**
 * Guards against two model habits seen live (the gateway applies the same rules): a "lookup" for a
 * request that doesn't describe a forgotten project, and visual-pack tweaks (fire, shield, fog…)
 * filed as mods when the player never asked for a mod.
 */
export function sanitizeAiGoals(prompt: string, intents: AiIntents): AiIntents {
  const lookups = hasLookupCue(prompt);
  const wantsMod = /\bmods?\b/i.test(prompt);
  return {
    ...intents,
    goals: intents.goals.map((g) => {
      const lookup = !!g.lookup && lookups;
      const packs = g.kind === "mod" && !lookup && !g.projects.length && g.searches.length > 0 && g.searches.every(isPackFeature) && !wantsMod;
      return { ...g, lookup, ...(packs ? { kind: "resourcepack" as const } : {}) };
    }),
  };
}

export function planRequests(prompt: string, intents: AiIntents | null, loader = "fabric"): { goals: PlanGoal[]; requests: PlannedRequest[]; needsFabric: string[]; lookup: LookupPlan | null } {
  const smart = smartGoals(prompt);
  const exclude = understand(prompt).exclude;
  let goals: PlanGoal[];
  if (!intents) {
    goals = dropAlternatives(applyExclusions(smart, exclude));
  } else {
    const ai = aiGoals(sanitizeAiGoals(prompt, intents));
    if (ai.some((g) => g.lookup)) goals = ai;
    else if (smart.some((g) => g.lookup)) goals = smart;
    else {
      const merged = mergeGoals(ai, smart, intents.constraints);
      goals = dropAlternatives(applyExclusions(applyConstraints(merged, intents.constraints, smart.map((g) => g.category)), [...intents.exclude, ...exclude]));
    }
  }
  // With shader packs planned, the resolver adds Iris as their required loader (labelled as such), so it isn't a pick too.
  if (goals.some((g) => g.kind === "shader")) goals = goals.map((g) => (g.category === "shaders" && g.kind === "mod" ? { ...g, projects: g.projects.filter((p) => p !== "iris") } : g)).filter((g) => g.projects.length || g.searches.length || g.lookup);
  const usable = loader === "fabric" ? goals : goals.filter((g) => g.kind === "resourcepack");
  const needsFabric = loader === "fabric" ? [] : [...new Set(goals.filter((g) => g.kind !== "resourcepack" && g.role !== "leftover").map((g) => g.label))];
  const found = usable.find((g) => g.lookup);
  const lookup: LookupPlan | null = found?.lookup ? { ...found.lookup, kind: found.kind ?? "mod", from: found.from } : null;
  return { goals: usable, requests: buildRequests(usable, lookup ? MAX_REQUESTS - LOOKUP_MAX : MAX_REQUESTS), needsFabric, lookup };
}

// ---------- search relevance (the same rules as `matches_query` in content.rs) ----------

/** Words that say how much, how good or what type, not what a project does (content.rs VAGUE_WORDS). */
const VAGUE_WORDS = [
  "a", "an", "the", "and", "or", "for", "with", "of", "to", "in", "on", "at", "by", "my", "me", "it", "is", "that", "this", "your", "mod", "mods",
  "modpack", "pack", "packs", "resource", "resourcepack", "shader", "shaders", "shaderpack", "minecraft", "fabric", "java", "client", "best", "good",
  "great", "nice", "cool", "awesome", "top", "useful", "helpful", "handy", "better", "boost", "booster", "improve", "enhance", "let", "lets",
  "want", "need", "some", "more", "less", "very", "really", "simple", "easy", "new", "small", "smaller", "big", "bigger", "low", "lower", "high",
  "higher", "no", "not", "without", "make", "makes", "add", "adds", "get", "install", "just", "also", "plus", "any", "all", "stuff", "thing",
  "feature", "lightweight", "fast", "faster", "look", "looking", "game", "feel",
];

/** content.rs `stem`: plurals, "-ing", "-ed", "-er" and a final "e". */
export function stem(word: string): string {
  let s = ({ armour: "armor", colour: "color", grey: "gray" } as Record<string, string>)[word] ?? word;
  const n = s.length;
  if (n > 5 && s.endsWith("ing")) s = s.slice(0, -3);
  else if (n > 4 && s.endsWith("ies")) s = `${s.slice(0, -3)}y`;
  else if (n > 4 && ["ses", "xes", "zes", "ches", "shes"].some((e) => s.endsWith(e))) s = s.slice(0, -2);
  else if (n > 3 && s.endsWith("s") && !s.endsWith("ss") && !s.endsWith("us") && !s.endsWith("is")) s = s.slice(0, -1);
  else if (n > 5 && s.endsWith("ed")) s = s.slice(0, -2);
  if (s.length > 4 && s.endsWith("er")) s = s.slice(0, -2);
  else if (s.length > 4 && s.endsWith("e")) s = s.slice(0, -1);
  return s;
}

const tokens = (text: string) => text.split(/[^\p{L}\p{N}]+/u).filter((w) => [...w].length >= 2).map((w) => w.toLowerCase());
const VAGUE_STEMS = new Set(VAGUE_WORDS.map(stem));
const vague = (word: string) => VAGUE_WORDS.includes(word) || VAGUE_STEMS.has(stem(word));

/** The words of a search that say what is wanted, stemmed, in order and without repeats. */
export function queryTerms(query: string): string[] {
  return [...new Set(tokens(query).filter((w) => !vague(w)).map(stem))];
}

export type SearchHit = { projectId?: string; project_id?: string; slug: string; title: string; description?: string | null; categories?: string[] | null; display_categories?: string[] | null };

function hitWords(hit: SearchHit): { stems: Set<string>; names: string[] } {
  const all = [hit.title, hit.slug, hit.description ?? "", ...(hit.categories ?? []), ...(hit.display_categories ?? [])].join(" ");
  return { stems: new Set(tokens(all).map(stem)), names: tokens(`${hit.title} ${hit.slug}`) };
}

const termFound = (term: string, words: { stems: Set<string>; names: string[] }) => words.stems.has(term) || (term.length >= 4 && words.names.some((n) => n.startsWith(term)));

/** Whether a search hit really is what the search asked for (content.rs `matches_query`). */
export function matchesQuery(query: string, hit: SearchHit): boolean {
  const terms = queryTerms(query);
  if (!terms.length) return false;
  const words = hitWords(hit);
  const found = terms.filter((t) => termFound(t, words)).length;
  return found >= (terms.length <= 2 ? terms.length : Math.floor(terms.length / 2) + 1);
}

// ---------- forgotten-project lookup ----------

/** Most candidates shown for a lookup. */
export const LOOKUP_MAX = 3;

/** A feature counts when at least half of its meaningful words are in the project's name, summary or categories. */
export function featureMatches(feature: string, hit: SearchHit): boolean {
  const terms = queryTerms(feature);
  if (!terms.length) return false;
  const words = hitWords(hit);
  const found = terms.filter((t) => termFound(t, words)).length;
  return found >= 1 && found * 2 >= terms.length;
}

/** Whether a project's name fits what the player remembers: its first letter, or a part of it. */
export function hintMatches(hint: string | null, hit: SearchHit): boolean {
  const h = (hint ?? "").toLowerCase().replace(/\s+/g, "");
  if (!h) return false;
  const title = hit.title.toLowerCase().replace(/^the\s+/, "");
  if (h.length === 1) return title.startsWith(h) || hit.slug.toLowerCase().startsWith(h);
  return title.replace(/[^\p{L}\p{N}]/gu, "").includes(h) || hit.slug.toLowerCase().replace(/-/g, "").includes(h);
}

/** The Modrinth searches for a lookup: all features' key words together, each feature, and a longer name hint. */
export function lookupQueries(lookup: LookupGoal): string[] {
  const combined = [...new Set(lookup.features.flatMap((f) => tokens(f).filter((w) => !vague(w))))].slice(0, 6).join(" ");
  const hint = lookup.nameHint && lookup.nameHint.length >= 3 ? [lookup.nameHint] : [];
  return [...new Set([combined, ...lookup.features, ...hint].filter((q) => q.trim().length >= 2))].slice(0, 6);
}

export type LookupCandidate = { hit: SearchHit; matched: string[]; hintMatch: boolean };
export type LookupFound = { identified: boolean; candidates: LookupCandidate[]; features: string[]; nameHint: string | null };

/**
 * Finds the project a player described: runs each feature search (version-filtered), then ranks
 * every project found by how many of the features its name and summary mention, then the name
 * hint, then how many searches found it. Two or more features make a possible match; otherwise
 * the closest projects are returned with `identified: false`. `search` runs one Modrinth search
 * and may fail; a failed search is skipped. Nothing here claims certainty: callers say "possible".
 */
export async function discoverLookup(lookup: LookupGoal, search: (query: string) => Promise<SearchHit[]>): Promise<LookupFound> {
  const queries = lookupQueries(lookup);
  const results = await Promise.all(queries.map((q) => search(q).catch(() => [] as SearchHit[])));
  // A feature phrase Modrinth found nothing for is retried with its key words only ("low fog" → "fog").
  const retries = queries.map((q, i) => (results[i].length || !lookup.features.includes(q) ? null : tokens(q).filter((w) => !vague(w)).join(" "))).filter((q): q is string => !!q && !queries.includes(q));
  results.push(...(await Promise.all(retries.map((q) => search(q).catch(() => [] as SearchHit[])))));
  const pool = new Map<string, { hit: SearchHit; seen: number; first: number }>();
  let order = 0;
  for (const hits of results) {
    for (const hit of hits) {
      const id = hit.projectId ?? hit.project_id ?? hit.slug;
      const entry = pool.get(id);
      if (entry) entry.seen++;
      else pool.set(id, { hit, seen: 1, first: order++ });
    }
  }
  const named = new Set(lookup.projects);
  const ranked = [...pool.values()]
    .map((e) => ({ ...e, matched: lookup.features.filter((f) => featureMatches(f, e.hit)), hintMatch: hintMatches(lookup.nameHint, e.hit), named: named.has(e.hit.slug) }))
    .filter((e) => e.matched.length > 0)
    .sort((a, b) => b.matched.length - a.matched.length || Number(b.hintMatch) - Number(a.hintMatch) || Number(b.named) - Number(a.named) || b.seen - a.seen || a.first - b.first);
  const enough = Math.min(2, lookup.features.length);
  const identified = ranked.length > 0 && ranked[0].matched.length >= enough;
  const candidates = (identified ? ranked.filter((e) => e.matched.length >= enough) : ranked).slice(0, LOOKUP_MAX);
  return { identified, candidates: candidates.map(({ hit, matched, hintMatch }) => ({ hit, matched, hintMatch })), features: lookup.features, nameHint: lookup.nameHint };
}

/** The resolver requests for a lookup's candidates, each explaining which features it matches. */
export function lookupRequests(found: LookupFound, lookup: LookupPlan): PlannedRequest[] {
  const hint = found.nameHint;
  return found.candidates
    .map((c): PlannedRequest | null => {
      const project = c.hit.projectId ?? c.hit.project_id ?? c.hit.slug;
      if (!project) return null;
      const name = hint && !c.hintMatch ? ` (${hint.length === 1 ? `doesn't start with “${hint.toUpperCase()}”` : `name doesn't contain “${hint}”`})` : "";
      return {
        project,
        source: lookup.from === "ai" ? "assistant" : "user",
        reason: `${found.identified ? "Possible match" : "Closest match"} — matches ${c.matched.join(", ")}${name}`,
        group: found.identified ? GROUP.lookup : GROUP.closest,
        category: "other",
        kind: lookup.kind,
      };
    })
    .filter((r): r is PlannedRequest => r !== null);
}

/** What a checked plan item needs to know to be included or held back. */
export type SelectableItem = { projectId: string; title: string; source: string; status: string; requiredBy?: string | null; requiredByAll?: string[] | null; message?: string | null };

const blockedItem = (i: SelectableItem) => i.status === "unavailable" || i.status === "conflict";
/** The plan items that require `i` (older plans only name the first one a dependency was found for). */
const requirers = (i: SelectableItem) => (i.requiredByAll?.length ? i.requiredByAll : i.source === "dependency" && i.requiredBy ? [i.requiredBy] : []);

/**
 * A project that needs something that can't be installed is shown as unavailable instead of
 * failing at launch, for every project that needs it and down chains (a pick needs a library that
 * needs something missing).
 */
export function markMissing<T extends SelectableItem>(items: T[]): T[] {
  let out = items;
  for (let round = 0; round < 8; round++) {
    const missing = new Map<string, string>();
    for (const i of out) if (blockedItem(i)) for (const by of requirers(i)) if (!missing.has(by)) missing.set(by, i.title);
    let changed = false;
    out = out.map((i) => {
      if (i.status !== "ok" || !missing.has(i.title)) return i;
      changed = true;
      return { ...i, status: "unavailable", message: `Needs ${missing.get(i.title)}, which isn't available for this version` } as T;
    });
    if (!changed) break;
  }
  return out;
}

/**
 * What installs: ticked picks, every project something included requires (whichever of the
 * projects that need it is ticked), and ticked optional extras whose mod is included. `forced`
 * lists projects held in by another included project, whose checkbox can't be cleared.
 */
export function selectPlan(items: SelectableItem[], picked: Record<string, boolean>): { on: Set<string>; titles: Set<string>; forced: Set<string> } {
  const on = new Set<string>();
  const titles = new Set<string>();
  const forced = new Set<string>();
  const add = (i: SelectableItem) => {
    on.add(i.projectId);
    titles.add(i.title);
  };
  for (const i of items) if ((i.source === "user" || i.source === "assistant") && i.status === "ok" && picked[i.projectId]) add(i);
  for (let depth = 0; depth < 8; depth++) {
    let grew = false;
    for (const i of items) {
      if (i.status !== "ok") continue;
      const by = requirers(i);
      const needed = by.length ? by.some((t) => t !== i.title && titles.has(t)) : i.source === "dependency" && titles.size > 0;
      if (needed) forced.add(i.projectId);
      const wanted = needed || (i.source === "optional" && picked[i.projectId] && (i.requiredBy ? titles.has(i.requiredBy) : titles.size > 0));
      if (wanted && !on.has(i.projectId)) {
        add(i);
        grew = true;
      }
    }
    if (!grew) break;
  }
  return { on, titles, forced };
}
