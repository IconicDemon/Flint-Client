import { api, errorText, subscribe, type AiQuota, type ModPlan, type ModTarget, type PlanItem, type ResolveRequest } from "./api";
import { quotaFromError } from "./aiQuota";
import { LEFTOVER_LABEL, discoverLookup, goalLabel, groupPlan, groupReasons, lookupRequests, parseIntents, planRequests, tagItems, type AiIntents, type DisplayGroup, type Kind, type LookupPlan } from "./assistPlan";
import { t, type MessageKey } from "./i18n";

/**
 * Request chips. `text` is what a chip adds to the request, and stays English: Flint's smart search
 * (the answer whenever Flint AI isn't available) only understands English requests.
 */
const CHIPS: { label: MessageKey; text: string }[] = [
  { label: "ai.examples.performance", text: "better performance and smoother FPS" },
  { label: "ai.examples.fpsPvp", text: "the best FPS mods and good PvP mods" },
  { label: "ai.examples.minimap", text: "a minimap" },
  { label: "ai.examples.shaders", text: "shader support" },
  { label: "ai.examples.zoom", text: "a zoom key" },
  { label: "ai.examples.armorHud", text: "an armor HUD and toggle sprint" },
  { label: "ai.examples.inventory", text: "easier inventory management" },
  { label: "ai.examples.foodHealth", text: "show food saturation and health info" },
  { label: "ai.examples.building", text: "building tools and replay recording" },
];

/** The chips with their `label` in the interface language, read when shown. */
export const EXAMPLES = CHIPS.map(({ label, text }) => ({
  get label() {
    return t(label);
  },
  text,
}));

/** Deterministic matching used when the assistant is unavailable. Never invents projects. */
export function fallbackRequests(prompt: string): ResolveRequest[] {
  return planRequests(prompt, null).requests.map(({ project, query, source, reason, kind }) => ({ project, query, source, reason, kind }));
}

/** The player has used their Flint AI searches; `quota.resetInMs` is the server's own countdown. */
export class QuotaExhausted extends Error {
  quota: AiQuota;
  constructor(quota: AiQuota) {
    super("AI_QUOTA");
    this.quota = quota;
  }
}

/** `goal`: the display group's title (English, see `goalLabel`); `match`: what a lookup candidate matched. */
export type AssistItem = PlanItem & { goal?: string | null; match?: string | null };

export type AssistResult = {
  plan: ModPlan & { items: AssistItem[] };
  groups: DisplayGroup<AssistItem>[];
  /** "ai": Flint AI understood the request; "fallback": Flint's smart search (no AI) did. */
  mode: "ai" | "fallback";
  note?: string | null;
  /** Why the smart search answered instead of Flint AI. */
  reason?: string;
  /** The goals that were recognised, as player-facing labels (in the interface language). */
  goals: string[];
  model?: string | null;
  /** The server's count after this search (AI answers only). */
  quota?: AiQuota | null;
  /** Wishes that need mods on a vanilla profile, which can only take resource packs (player-facing labels). */
  needsFabric: string[];
  /** A forgotten-project lookup: whether a possible match turned up (two or more described features), and what was looked for. */
  lookup?: { identified: boolean; features: string[]; nameHint: string | null } | null;
  /** The kind of content the request was mostly about, for plain Library alternatives when nothing fit. */
  searchKind: Kind;
};

export type AssistStage = "connecting" | "thinking" | "matching";

/** Why the smart search answered, by the backend's error code. */
const REASONS: Record<string, MessageKey> = {
  AI_UNAVAILABLE: "ai.unavailable.unreachable",
  AI_RATE_LIMIT: "ai.unavailable.busy",
  AI_CAPACITY: "ai.unavailable.capacity",
  AI_TIMEOUT: "ai.unavailable.timeout",
  AI_INVALID: "ai.unavailable.invalid",
  AI_NOT_CONFIGURED: "ai.unavailable.notConfigured",
};

/**
 * Turns a request into a checked mod plan. Flint AI (when set up) interprets the request into
 * goals; Flint then searches, verifies and ranks real Modrinth projects for the target. Any AI
 * error falls back to the smart search with a reason. Never installs anything.
 */
export async function suggestMods(
  target: ModTarget,
  prompt: string,
  opts: { aiEnabled: boolean; requestId: string; onStage?: (stage: AssistStage) => void; loader?: string; fallbackReason?: string; gameVersion?: string },
): Promise<AssistResult> {
  let intents: AiIntents | null = null;
  let reason: string | undefined;
  if (opts.aiEnabled) {
    opts.onStage?.("connecting");
    // "connected" can arrive before the listener is registered (a cached session connects at once),
    // so the stage also moves on after a moment.
    let thinking = false;
    const toThinking = () => {
      if (thinking) return;
      thinking = true;
      opts.onStage?.("thinking");
    };
    const timer = setTimeout(toThinking, 1500);
    const unsubscribe = subscribe<{ requestId: string; type: string }>("ai-event", (e) => {
      if (e.requestId === opts.requestId && e.type === "connected") toThinking();
    });
    const off = () => {
      clearTimeout(timer);
      unsubscribe();
    };
    try {
      intents = parseIntents(await api.aiSuggest(opts.requestId, target, prompt));
      if (!intents) reason = t(REASONS.AI_INVALID);
    } catch (error) {
      const code = errorText(error);
      if (code === "AI_CANCELLED") throw error;
      // Out of searches is not an outage: the player decides whether to use the smart search instead.
      const quota = quotaFromError(code);
      if (quota) throw new QuotaExhausted(quota);
      reason = t(REASONS[code.split(":")[0]] ?? REASONS.AI_UNAVAILABLE);
    } finally {
      off();
    }
  } else {
    reason = opts.fallbackReason ?? t(REASONS.AI_NOT_CONFIGURED);
  }

  const { goals, requests: planned, needsFabric, lookup } = planRequests(prompt, intents, opts.loader ?? "fabric");
  const mode = intents ? "ai" : "fallback";
  const gameVersion = "gameVersion" in target ? target.gameVersion : (opts.gameVersion ?? "");
  // "More from your request" is a search for leftover words, not something Flint understood.
  const labels = [...new Set(goals.filter((g) => g.label !== LEFTOVER_LABEL).map((g) => g.label))].map(goalLabel);
  const wishes = needsFabric.map(goalLabel);
  const searchKind: Kind = lookup?.kind ?? goals.find((g) => g.role !== "leftover")?.kind ?? "mod";
  if (lookup) opts.onStage?.("matching");
  const found = lookup ? await findForgotten(lookup, gameVersion, opts.loader ?? "fabric") : null;
  const requests = [...(found && lookup ? lookupRequests(found, lookup) : []), ...planned];
  const lookupInfo = found ? { identified: found.identified, features: found.features, nameHint: found.nameHint } : null;
  if (!requests.length) {
    const plan = { items: [], unmatched: [], blockers: [], gameVersion, loader: "fabric" };
    const empty = t(lookup ? "ai.results.lookupNothing" : "ai.results.nothing");
    return { plan, groups: [], mode, reason, goals: labels, quota: intents?.quota, needsFabric: wishes, lookup: lookupInfo, searchKind, note: intents?.note ?? (needsFabric.length ? null : empty) };
  }
  opts.onStage?.("matching");
  const plan = await api.resolveMods(
    target,
    requests.map(({ project, query, source, reason, kind }) => ({ project, query, source, reason, kind })),
  );
  const items: AssistItem[] = tagItems(plan.items, requests, plan.matches);
  const why = groupReasons(goals);
  const groups = groupPlan(items).map((g) => ({ ...g, why: g.kind === "goal" ? (why.get(g.title) ?? null) : null }));
  return { plan: { ...plan, items }, groups, mode, note: intents?.note, reason, goals: labels, model: intents?.model, quota: intents?.quota, needsFabric: wishes, lookup: lookupInfo, searchKind };
}

/** Targeted discovery for a described project: version-filtered Library searches for each feature (free, no AI). */
function findForgotten(lookup: LookupPlan, gameVersion: string, loader: string) {
  return discoverLookup(lookup, async (query) =>
    (
      await api.librarySearch({
        kind: lookup.kind,
        query: query.slice(0, 80),
        index: "relevance",
        offset: 0,
        limit: 10,
        gameVersion: gameVersion || null,
        loader,
        categories: [],
        clientOnly: lookup.kind === "mod",
      })
    ).hits,
  );
}
