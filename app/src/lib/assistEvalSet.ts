/**
 * Flint AI's evaluation set: real requests and the rules that judge each recommendation. Used
 * offline by `assistEval.test.ts` (recorded answers) and live by `scripts/live-assist.mjs --eval`
 * (staging gateway, Modrinth and Flint's resolver). A pick is relevant when it is a kind the
 * request allows and its title, slug or Modrinth summary matches a rule for that kind.
 */
import type { Category, Kind } from "./assistPlan.ts";

export type EvalCase = {
  id: string;
  prompt: string;
  /** Goal categories the plan must contain (alternatives joined with "|"). */
  goals: string[];
  /** For each kind a pick may be, what makes it relevant. A kind not listed is a wrong recommendation. */
  relevant: Partial<Record<Kind, RegExp>>;
  /** Picks that would be noise however they were found (the real failures). */
  noise?: RegExp;
  /** A single forgotten project, answered by targeted discovery: a candidate must mention at least two of these features. */
  lookup?: string[];
};

const PERFORMANCE = /sodium|lithium|ferrite|immediately ?fast|entity ?cull|more ?cull|dynamic ?fps|c2me|optimi[sz]|performance|fps|frame ?rate|render|lag|stutter|memory|faster|lightweight|cull/i;
const PVP = /pvp|zoom|hud|armou?r|keystroke|cps|crosshair|sprint|status effect|potion|effect (bar|timer)|hit ?(box|colou?r)|combat|damage|totem|low fire|shield|fire overlay|reach display|durability|cooldown/i;
const QOL = /inventory|sort|tooltip|appleskin|food|saturation|jade|what am i looking at|mod ?menu|recipe|emi|map|waypoint|zoom|hud|mouse|tweak|quality of life|qol|controls|key ?bind/i;
const VISUAL = /visual|cloud|light|textur|leaves|grass|sky|particle|animation|connected|shader/i;
const NOISE = /usefulfood|let'?s parkour|elytra|fireworks boosting|useful hats|bow boost/i;

export const EVAL_SET: EvalCase[] = [
  { id: "fps", prompt: "best fps mods for my pc", goals: ["performance"], relevant: { mod: PERFORMANCE }, noise: NOISE },
  { id: "pvp", prompt: "good pvp mods for 1v1 duels", goals: ["pvp|hud"], relevant: { mod: PVP, resourcepack: /fire|shield|totem|sword|pvp|hit|particle|crosshair/i }, noise: NOISE },
  {
    id: "fps+pvp",
    // The owner's production prompt, verbatim.
    prompt: "I want a best minecraft fps boosting mods that boost my fps and I also want best pvp mods",
    goals: ["performance", "pvp|hud"],
    relevant: { mod: new RegExp(`${PERFORMANCE.source}|${PVP.source}`, "i"), resourcepack: /fire|shield|totem|sword|pvp/i },
    noise: NOISE,
  },
  {
    id: "forgotten",
    prompt: "a mod starting with L that lowers fire, makes the shield smaller, removes pumpkin blur and reduces fog",
    goals: ["other"],
    relevant: { mod: /fire|shield|pumpkin|fog/i },
    lookup: ["fire", "shield", "pumpkin", "fog"],
  },
  { id: "named", prompt: "install sodium and iris", goals: [], relevant: { mod: /^(sodium|iris)\b|\b(sodium|iris)$/i } },
  {
    id: "shaders",
    prompt: "good looking shaders that keep FPS",
    goals: ["shaders", "performance"],
    // Every shader pick is a real shader pack (the resolver only accepts project_type "shader"); mods only for FPS.
    relevant: { shader: /./, mod: PERFORMANCE },
  },
  { id: "packs", prompt: "lower fire and a smaller shield", goals: ["visual|pvp"], relevant: { resourcepack: /fire|shield/i } },
  { id: "low-end", prompt: "my old laptop has 4gb ram and minecraft lags a lot, make it run better", goals: ["lowEnd|performance"], relevant: { mod: PERFORMANCE }, noise: NOISE },
  { id: "noise", prompt: "I want some useful mods and lets make my game feel cool", goals: [], relevant: { mod: new RegExp(`${PERFORMANCE.source}|${QOL.source}|${PVP.source}|${VISUAL.source}`, "i"), resourcepack: /./ }, noise: NOISE },
];

export type JudgedItem = { title: string; slug: string; description?: string | null; kind: string; source: string; status: string; group?: string | null; role?: string | null };

/**
 * The verdict on one recommendation: relevant, or why it is wrong. Only picks are judged (what
 * Flint AI or the smart search chose), not the dependencies the resolver adds for them.
 */
export function judge(c: EvalCase, item: JudgedItem): { ok: boolean; why: string } {
  const text = `${item.title} ${item.slug} ${item.description ?? ""}`;
  if (c.noise?.test(text)) return { ok: false, why: "noise" };
  // Iris and Sodium are mods: shown as a shader pack they would be wrong whatever the request.
  if (/^(iris|sodium)$/.test(item.slug) && (item.kind === "shader" || item.group === "Shader packs")) return { ok: false, why: "loader shown as a shader pack" };
  const rule = c.relevant[item.kind as Kind];
  if (!rule) return { ok: false, why: `wrong kind (${item.kind})` };
  if (c.lookup) {
    const found = c.lookup.filter((f) => new RegExp(`\\b${f}`, "i").test(text));
    return found.length >= 2 ? { ok: true, why: "" } : { ok: false, why: `mentions ${found.length ? found.join(", ") : "none"} of the described features` };
  }
  return rule.test(text) ? { ok: true, why: "" } : { ok: false, why: "not what was asked" };
}

/** Picks a plan recommends: the player's or Flint AI's, checked and installable (or already installed). */
export const isPick = (item: { source: string; status: string }) => (item.source === "user" || item.source === "assistant") && (item.status === "ok" || item.status === "installed");

export const categoriesOf = (goals: { category: Category }[]) => [...new Set(goals.map((g) => g.category))];
