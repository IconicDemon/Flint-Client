/**
 * Deterministic understanding of mod requests, used when the AI assistant isn't available. Every
 * goal in a request is recognised independently ("FPS and PvP" yields both), each goal contributes
 * its own picks, and any leftover phrase still gets its own Modrinth search (kept only when a result
 * really matches it; see `matches_query` in content.rs). Nothing here invents a
 * project: slugs are established Modrinth projects, and Flint's resolver checks each one against
 * the profile's Minecraft version and loader (dropping any without a compatible build).
 */

/** The goal categories Flint AI answers with (the gateway contract); the smart search uses them too. */
export const CATEGORIES = ["performance", "lowEnd", "pvp", "hud", "inventory", "qol", "visual", "shaders", "minimap", "building", "replay", "survival", "exploration", "ui", "other"] as const;
export type Category = (typeof CATEGORIES)[number];

export type Goal = {
  id: string;
  label: string;
  category: Category;
  pattern: RegExp;
  /** Established Modrinth project slugs. */
  projects?: string[];
  /** Modrinth searches for goals without one obvious project. */
  searches?: string[];
  /** What the picks are: Fabric mods (default), resource packs (which also work without Fabric) or shader packs (loaded by Iris). */
  kind?: "mod" | "resourcepack" | "shader";
};

export const GOALS: Goal[] = [
  { id: "performance", label: "Performance", category: "performance", pattern: /\b(fps|frames?|frame ?rate|performance|lag\w*|stutter\w*|smooth\w*|faster|optimi[sz]\w*|boost\w*)\b/i, projects: ["sodium", "lithium", "ferrite-core", "immediatelyfast", "entityculling"] },
  { id: "lowEnd", label: "Low-end PC", category: "lowEnd", pattern: /\b([1-6](\.\d)?\s?gb|low[- ]?(end|ram|spec\w*|memory)|old (laptop|pc|computer|mac)|(?<!gaming )laptop|potato|lightweight|light ?weight|weak (pc|computer))\b/i, projects: ["ferrite-core", "lithium", "sodium", "dynamic-fps", "immediatelyfast"] },
  { id: "pvp", label: "PvP", category: "pvp", pattern: /\b(pvp|combat|duels?|bed ?wars|sky ?wars|practice|hypixel|fight\w*)\b/i, projects: ["zoomify"], searches: ["toggle sprint", "keystrokes", "armor hud", "crosshair"] },
  { id: "hud", label: "HUD", category: "hud", pattern: /\b(hud|armou?r (hud|status|durability)|toggle ?sprint|keystrokes|cps|status effects?|potion (timers?|effects?)|coordinates)\b/i, searches: ["armor hud", "toggle sprint", "status effect timer"] },
  { id: "minimap", label: "Minimap", category: "minimap", pattern: /\b(mini ?-?maps?)\b|\bmap\b/i, projects: ["xaeros-minimap"] },
  { id: "worldmap", label: "World map", category: "minimap", pattern: /\b(world ?map|waypoints?)\b/i, projects: ["xaeros-world-map"] },
  // Shader packs, light on FPS first; Flint adds the Iris loader (and Sodium with it) as a dependency.
  { id: "shaders", label: "Shaders", category: "shaders", kind: "shader", pattern: /\bshaders?\b|\bshader ?packs?\b/i, projects: ["complementary-reimagined", "makeup-ultra-fast-shaders", "bsl-shaders"] },
  { id: "visual", label: "Visuals", category: "visual", pattern: /\b(visuals?|eye ?candy|graphics|connected textures?|ctm)\b/i, projects: ["continuity"], searches: ["better clouds"] },
  { id: "inventory", label: "Inventory", category: "inventory", pattern: /\b(inventory|inventories|sort(ing)?|item scroll\w*|tweaks?)\b/i, projects: ["mouse-tweaks"], searches: ["inventory sorting"] },
  { id: "qol", label: "Quality of life", category: "qol", pattern: /\b(quality[- ]of[- ]life|qol|convenien\w*|feels? better)\b/i, projects: ["appleskin", "jade", "mouse-tweaks"] },
  { id: "survival", label: "Vanilla survival", category: "survival", pattern: /\b(vanilla\w*|survival)\b/i, projects: ["sodium", "lithium", "ferrite-core", "appleskin", "jade"] },
  { id: "food", label: "Food & health", category: "qol", pattern: /\b(food|saturation|hunger|health)\b/i, projects: ["appleskin"] },
  { id: "zoom", label: "Zoom", category: "hud", pattern: /\bzoom\w*/i, projects: ["zoomify"] },
  { id: "lights", label: "Dynamic lights", category: "visual", pattern: /\b(dynamic light\w*|torch light\w*|hand ?held light)\b/i, projects: ["lambdynamiclights"] },
  { id: "building", label: "Building", category: "building", pattern: /\b(build\w*|creative|world ?edit|schematics?|litematica)\b/i, projects: ["worldedit", "litematica"], searches: ["building tools"] },
  { id: "replay", label: "Replay & cinematics", category: "replay", pattern: /\b(replays?|record\w*|cinematic\w*|camera|videos?|youtube|content creat\w*|screenshots?)\b/i, projects: ["replaymod"], searches: ["freecam", "cinematic camera"] },
  { id: "recipes", label: "Recipes", category: "qol", pattern: /\b(recipes?|item list|jei|rei|emi)\b/i, projects: ["emi"] },
  { id: "voice", label: "Voice chat", category: "other", pattern: /\b(voice|proximity)\b/i, projects: ["simple-voice-chat"] },
  { id: "exploration", label: "Exploration", category: "exploration", pattern: /\b(explor\w*|adventur\w*|structures?|biomes?|compass\w*)\b/i, projects: ["xaeros-world-map", "natures-compass", "explorers-compass"] },
  { id: "lowFire", label: "Lower fire", category: "visual", kind: "resourcepack", pattern: /\b(low(er)? fire|fire (overlay|on screen)|smaller fire)\b/i, searches: ["low fire"] },
  { id: "smallShield", label: "Smaller shield", category: "visual", kind: "resourcepack", pattern: /\b(small(er)? shield|low(er)? shield|shield (size|smaller|lower))\b/i, searches: ["low shield"] },
  { id: "pumpkin", label: "No pumpkin blur", category: "visual", kind: "resourcepack", pattern: /\bpumpkin (blur|overlay|view|mask)\b/i, searches: ["pumpkin overlay"] },
  { id: "ui", label: "Interface", category: "ui", pattern: /\b(ui|interface|menus?|gui|f3|debug screen|key ?binds?)\b/i, projects: ["modmenu", "betterf3", "controlling"] },
];

/** Words that describe the request rather than name something to search for. */
const FILLER = /\b(i|i'?d|i'?m|want|would|like|need|some|the|best|good|great|nice|really|very|mods?|mod ?pack|set ?up|setup|for|on|with|a|an|and|that|which|work|works|together|look(s|ing)?|beautiful|but|don'?t|do|not|destroy\w*|minecraft|java|edition|fabric|my|me|please|also|plus|of|to|in|is|it|runs?|only|has|have|computer|pc|give|get|make|still|clean|experience|feels?|better|without|chang\w*|gameplay|content|stable|fast|utilit\w*|management|managing|ram|memory|stuff|things?|keep|lot|lots|more|less|any|all|can|could|should|just|gaming|rig|improv\w*|enhanc\w*|essentials?|additions?|features?|lightweight|useful|helpful|handy|cool|awesome|interesting|recommended|decent|solid|top|plus|ones?|something|anything|stuff|lets?|allows?|able|enables?|helps?|makes?|making|easier|easy|simple|keys?|buttons?|press\w*|hold\w*|install\w*|add|download\w*|see|games?)\b/gi;
const VERSION = /\b\d{1,2}\.\d{1,2}(\.\d{1,2})?\b/g;
/** Amounts of memory or storage ("32 GB"), which describe the computer rather than a mod. */
const AMOUNT = /\b\d+(\.\d+)?\s?gb\b/gi;
/** "no OptiFine", "without shaders", "I don't want Sodium": the rest of the phrase names something to leave out. */
const NEGATION = /\b(?:no|not|without|except|avoid|don'?t (?:want|need|use|like))\s+([^,;.+!?]*)/gi;
/** Negated phrases that are a wish, not an exclusion ("no lag", "no FPS drops", "no pumpkin blur"). */
const WISH = /\b(lag\w*|stutter\w*|fps|frames?|drops?|crash\w*|freez\w*|blur\w*|overlays?|fog|vignette|bobbing|shake)\b/i;

export type GoalRequest = { project?: string; query?: string; source: "user"; reason: string };

/** Projects players name directly ("install sodium and iris"), with the kind each one is. */
export const NAMED: { pattern: RegExp; project: string; kind: "mod" | "resourcepack" | "shader" }[] = [
  { pattern: /\bsodium\b/i, project: "sodium", kind: "mod" },
  { pattern: /\biris\b/i, project: "iris", kind: "mod" },
  { pattern: /\blithium\b/i, project: "lithium", kind: "mod" },
  { pattern: /\bferrite ?core\b/i, project: "ferrite-core", kind: "mod" },
  { pattern: /\bimmediately ?fast\b/i, project: "immediatelyfast", kind: "mod" },
  { pattern: /\bentity ?culling\b/i, project: "entityculling", kind: "mod" },
  { pattern: /\bzoomify\b/i, project: "zoomify", kind: "mod" },
  { pattern: /\bmod ?menu\b/i, project: "modmenu", kind: "mod" },
  { pattern: /\bxaero'?s? ?mini ?map\b/i, project: "xaeros-minimap", kind: "mod" },
  { pattern: /\bjourney ?map\b/i, project: "journeymap", kind: "mod" },
  { pattern: /\bapple ?skin\b/i, project: "appleskin", kind: "mod" },
  { pattern: /\bcontinuity\b/i, project: "continuity", kind: "mod" },
  { pattern: /\bcomplementary\b/i, project: "complementary-reimagined", kind: "shader" },
  { pattern: /\bbsl\b/i, project: "bsl-shaders", kind: "shader" },
  { pattern: /\bfaithful\b/i, project: "faithful-32x", kind: "resourcepack" },
];

/** A single project the player describes but can't name: what it does, and what they remember of its name. */
export type Lookup = { features: string[]; nameHint: string | null; kind: "mod" | "resourcepack" | "shader" };

/** Phrases that say the player is trying to remember one project rather than asking for a kind of mod. */
const LOOKUP = /\b(forg[eo]t(?:ten)?|can'?t (?:remember|recall|find)|cannot (?:remember|recall)|don'?t (?:remember|recall)|do not remember|what(?:'s| is| was) (?:the )?(?:name|mod|pack|called)|name of (?:a|the|this|that) (?:mod|pack|shader)|(?:start(?:s|ed|ing)?|begin(?:s|ning)?) with|(?:called|named) (?:something )?like|which mod (?:is|was) (?:it|that))\b/i;
const HINT = /\b(?:start(?:s|ed|ing)?|begin(?:s|ning)?) with (?:an? |the )?(?:letter )?["'“‘]?([\p{L}\p{N}]{1,16})["'”’]?|\b(?:called|named) (?:something )?like ["'“‘]?([\p{L}\p{N}]{1,16})/iu;
/** Words that only tell the story of forgetting, or describe the project rather than what it does. */
const STORY = /\b(forg[eo]t(?:ten)?|remember|recall|name[ds]?|called|what'?s|was|is|it|its|there|this|that|which|who|used|years?|ago|back|once|played|saw|found|find|start(?:s|ed|ing)?|begin(?:s|ning)?|letter|something|mod|mods|pack|packs|resource|texture|shader|shaders|one|can'?t|cannot|don'?t|do|did|does)\b/gi;
/** Verbs that describe a feature, mapped onto the words a Modrinth summary would use ("removes fog" → "no fog"). */
const FEATURE_VERBS: [RegExp, string][] = [
  [/\b(remov\w*|disabl\w*|hid(?:e|es|ing)|get rid of|gets rid of|no)\b/gi, "no"],
  [/\b(lower\w*|reduc\w*|shrink\w*|smaller|lowers)\b/gi, "low"],
  [/\b(adds?|shows?|displays?|gives?|lets? (?:you|me))\b/gi, " "],
];

/** True when the player says they're trying to remember one project ("I forgot…", "starts with L"). */
export const hasLookupCue = (prompt: string) => LOOKUP.test(prompt);

/** Visual tweaks a resource pack can do; a goal made only of these is about packs unless the player asked for a mod. */
const PACK_FEATURE = /\b(fire|shield|totem|pumpkin|fog|water|glass|grass|leaves|textures?|dark (mode|ui)|ore borders?)\b/i;
export const isPackFeature = (search: string) => PACK_FEATURE.test(search);

/** Recognises "I forgot the name of a mod that …" and "a mod starting with L that …"; null for an ordinary request. */
export function lookupRequest(prompt: string): Lookup | null {
  if (!LOOKUP.test(prompt)) return null;
  const hint = prompt.match(HINT);
  const nameHint = (hint?.[1] ?? hint?.[2] ?? "").trim() || null;
  const kind = /\bshaders?\b/i.test(prompt) ? "shader" : /\b(resource|texture) ?packs?\b/i.test(prompt) ? "resourcepack" : "mod";
  let rest = prompt;
  if (hint) rest = rest.replace(hint[0], " ");
  const features: string[] = [];
  for (const part of rest.split(/,|;|\.|\band\b|\balso\b|\bplus\b|\bthat\b|\bwhich\b/i)) {
    let words = part;
    for (const [pattern, replacement] of FEATURE_VERBS) words = words.replace(pattern, ` ${replacement} `);
    words = words.replace(STORY, " ").replace(FILLER, " ").replace(/[^\p{L}\p{N}' -]/gu, " ").replace(/\s+/g, " ").trim().toLowerCase();
    // Put the size or absence word first, as summaries say it ("shield low" → "low shield").
    const m = words.match(/^(.*?)\s*\b(no|low)$/);
    if (m && m[1]) words = `${m[2]} ${m[1]}`;
    if (words.replace(/\b(no|low)\b/g, "").trim().length >= 3 && !features.includes(words)) features.push(words);
  }
  return features.length ? { features: features.slice(0, 4), nameHint, kind } : null;
}

/** Pulls "no X" phrases out of a request: what's left, and the terms to leave out. */
function exclusions(prompt: string): { wanted: string; exclude: string[] } {
  let wanted = prompt;
  const exclude: string[] = [];
  for (const m of prompt.matchAll(NEGATION)) {
    const phrase = m[1].split(/\b(?:and|or|but|with|so|because|just|please)\b/i)[0];
    if (WISH.test(phrase)) continue;
    wanted = wanted.replace(m[0].slice(0, m[0].length - m[1].length) + phrase, " ");
    const term = phrase.split(/\s+/).filter(Boolean).slice(0, 3).join(" ").replace(FILLER, " ").replace(/[^\p{L}\p{N}' -]/gu, " ").replace(/\s+/g, " ").trim().toLowerCase();
    if (term.length >= 3 && !exclude.includes(term)) exclude.push(term);
  }
  return { wanted, exclude };
}

export function understand(request: string): { goals: Goal[]; requests: GoalRequest[]; leftovers: string[]; exclude: string[]; named: typeof NAMED } {
  // Goals and leftovers come only from what's wanted; "no shaders" doesn't ask for shaders.
  const { wanted: prompt, exclude } = exclusions(request);
  const goals = GOALS.filter((g) => g.pattern.test(prompt));
  const picked = new Map<string, Set<string>>();
  const searches = new Map<string, Set<string>>();
  for (const goal of goals) {
    for (const p of goal.projects ?? []) (picked.get(p) ?? picked.set(p, new Set()).get(p)!).add(goal.label);
    for (const q of goal.searches ?? []) (searches.get(q) ?? searches.set(q, new Set()).get(q)!).add(goal.label);
  }
  const named = NAMED.filter((n) => n.pattern.test(prompt));
  const requests: GoalRequest[] = [
    ...named.filter((n) => !picked.has(n.project)).map((n) => ({ project: n.project, source: "user" as const, reason: "You named it" })),
    ...[...picked].map(([project, labels]) => ({ project, source: "user" as const, reason: `For ${[...labels].join(" + ")}` })),
    ...[...searches].map(([query, labels]) => ({ query, source: "user" as const, reason: `For ${[...labels].join(" + ")} (Modrinth search)` })),
  ];
  // Whatever no goal explained still gets its own search, so nothing the player asked for is dropped.
  let rest = prompt.replace(VERSION, " ").replace(AMOUNT, " ");
  for (const goal of goals) rest = rest.replace(new RegExp(goal.pattern.source, "gi"), " ");
  for (const n of named) rest = rest.replace(new RegExp(n.pattern.source, "gi"), " ");
  const leftovers: string[] = [];
  for (const part of rest.split(/,|;|\+|\band\b|\bwith\b|\bor\b|\./i)) {
    const words = part.replace(FILLER, " ").replace(/[^\p{L}\p{N}' -]/gu, " ").replace(/\s+/g, " ").trim();
    if (words.length >= 4 && !requests.some((r) => r.query === words)) {
      requests.push({ query: words, source: "user", reason: "Modrinth search for your words" });
      leftovers.push(words);
    }
  }
  return { goals, requests: requests.slice(0, 16), leftovers, exclude, named };
}
