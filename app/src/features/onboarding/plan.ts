/**
 * The onboarding mod plan: merging search results, what is ticked, what actually installs and how
 * it is grouped on the Mods, Review and finished screens. Pure (no Tauri or React), so `npm test`
 * covers it.
 */
import type { InstalledItem, PlanItem, ResolveRequest } from "../../lib/api.ts";
import { selectPlan } from "../../lib/assistPlan.ts";

/** `goal` is the display group of the goal a pick came from (Flint AI or smart search). */
export type PlanEntry = PlanItem & { selected: boolean; goal?: string | null };

/**
 * Flint's automatic performance set for new Fabric profiles (`PERFORMANCE_SET` in content.rs, by
 * project title). Each is added only when Modrinth has a build for the profile's version.
 */
export const PERFORMANCE_SET = ["Sodium", "Lithium", "FerriteCore", "ImmediatelyFast", "Entity Culling", "Dynamic FPS"] as const;

/** Most requests the resolver takes in one call (`mods_resolve` drops the rest). */
export const RESOLVE_LIMIT = 16;

export const isPick = (i: { source: string }) => i.source === "user" || i.source === "assistant";
const usable = (i: { status: string }) => i.status === "ok";

/** The plan items that need (or, for an optional extra, extend) this one. */
const linked = (i: PlanItem) => [...new Set([...(i.requiredByAll ?? []), ...(i.requiredBy ? [i.requiredBy] : [])])];

/** Removes a pick with the dependencies and extras only it brought in; one another mod still needs stays. */
export function withoutPick(plan: PlanEntry[], projectId: string): PlanEntry[] {
  const title = plan.find((i) => i.projectId === projectId)?.title;
  const rest = plan.filter((i) => i.projectId !== projectId);
  if (!title) return rest;
  const titles = new Set(rest.map((i) => i.title));
  return rest.filter((i) => {
    if (i.source !== "dependency" && i.source !== "optional") return true;
    const by = linked(i);
    return !by.includes(title) || by.some((t) => t !== title && titles.has(t));
  });
}

/**
 * Adds a checked search result to the plan. New picks start ticked; optional extras always start
 * off (they install only when the player ticks them). A project already in the plan keeps the
 * player's choice, except that an optional extra another pick now requires becomes a dependency,
 * and one the player searched for themselves becomes their pick.
 */
export function mergePlan(current: PlanEntry[], incoming: (PlanItem & { goal?: string | null })[], replacing?: string): PlanEntry[] {
  const out = replacing ? withoutPick(current, replacing) : [...current];
  for (const item of incoming) {
    const existing = out.findIndex((i) => i.projectId === item.projectId);
    if (existing >= 0) {
      const was = out[existing];
      if (item.source === "dependency") {
        out[existing] = { ...was, ...(was.source === "optional" ? { source: "dependency" as const } : {}), requiredByAll: [...new Set([...linked(was), ...linked(item)])] };
      } else if (isPick(item) && was.source === "optional") {
        out[existing] = { ...item, selected: usable(item) };
      }
      continue;
    }
    if (item.status === "not-needed") continue;
    out.push({ ...item, selected: usable(item) && item.source !== "optional" });
  }
  return out;
}

/** The player's ticks: picks and optional extras. Dependencies have no tick of their own. */
const ticks = (plan: PlanEntry[]) => Object.fromEntries(plan.map((i) => [i.projectId, i.selected && (isPick(i) || i.source === "optional")]));

/**
 * What installs: ticked picks, every dependency something included needs, and ticked optional
 * extras whose mod is included. `forced` are dependencies held in by an included mod.
 */
export function planSelection(plan: PlanEntry[]): { on: Set<string>; forced: Set<string> } {
  const { on, forced } = selectPlan(plan, ticks(plan));
  return { on, forced };
}

export type ReviewGroups = { picks: PlanEntry[]; dependencies: PlanEntry[]; optional: PlanEntry[]; optionalOff: PlanEntry[]; blocked: PlanEntry[] };

/** The plan as the Review shows it: only what will install, in the four kinds the player knows. */
export function reviewGroups(plan: PlanEntry[]): ReviewGroups {
  const { on } = planSelection(plan);
  const has = (i: PlanEntry) => on.has(i.projectId);
  return {
    picks: plan.filter((i) => isPick(i) && has(i)),
    dependencies: plan.filter((i) => i.source === "dependency" && has(i)),
    optional: plan.filter((i) => i.source === "optional" && has(i)),
    optionalOff: plan.filter((i) => i.source === "optional" && usable(i) && !has(i)),
    blocked: plan.filter((i) => i.status === "conflict" || i.status === "unavailable"),
  };
}

/** The resolve requests for the plan's picks, in batches the resolver takes whole. */
export function pickRequests(picks: PlanEntry[]): ResolveRequest[][] {
  const requests: ResolveRequest[] = picks.map((i) => ({
    project: i.projectId,
    source: i.source,
    reason: i.reason ?? undefined,
    kind: i.kind === "resourcepack" ? "resourcepack" : "mod",
  }));
  const out: ResolveRequest[][] = [];
  for (let i = 0; i < requests.length; i += RESOLVE_LIMIT) out.push(requests.slice(i, i + RESOLVE_LIMIT));
  return out;
}

const RANK: Record<string, number> = { user: 0, assistant: 0, dependency: 1, optional: 2 };

/** Joins resolver answers for several batches: one row per project, a pick over a dependency over an extra. */
export function joinResolved(batches: PlanItem[][]): PlanItem[] {
  const out = new Map<string, PlanItem>();
  for (const item of batches.flat()) {
    const was = out.get(item.projectId);
    if (!was) {
      out.set(item.projectId, item);
      continue;
    }
    const keep = (RANK[item.source] ?? 3) < (RANK[was.source] ?? 3) ? item : was;
    const by = [...new Set([...(was.requiredByAll ?? []), ...(item.requiredByAll ?? [])])];
    out.set(item.projectId, { ...keep, requiredByAll: by.length ? by : (keep.requiredByAll ?? null) });
  }
  return [...out.values()];
}

/**
 * What Setup installs from the plan resolved against the new profile. `optionalWanted` are the
 * extras the player ticked. Dependencies only an extra needs go with that extra, so a failed or
 * unticked extra never leaves its library behind. Already-installed projects count as included.
 */
export function installBatches(resolved: PlanItem[], optionalWanted: ReadonlySet<string>): { required: PlanItem[]; optional: PlanItem[] } {
  const asOk = resolved.map((i) => (i.status === "installed" ? { ...i, status: "ok" as const } : i));
  const base = selectPlan(asOk, Object.fromEntries(resolved.map((i) => [i.projectId, isPick(i)])));
  const all = selectPlan(asOk, Object.fromEntries(resolved.map((i) => [i.projectId, isPick(i) || (i.source === "optional" && optionalWanted.has(i.projectId))])));
  const installable = resolved.filter((i) => i.status === "ok" && !!i.versionId);
  return {
    required: installable.filter((i) => base.on.has(i.projectId)),
    optional: installable.filter((i) => all.on.has(i.projectId) && !base.on.has(i.projectId)),
  };
}

/**
 * The plan re-checked for another Minecraft version. Picks keep the player's tick (a pick that
 * couldn't be installed before starts ticked once it can), extras stay as chosen, and each pick
 * keeps the goal it was shown under.
 */
export function rebasePlan(old: PlanEntry[], fresh: PlanItem[]): PlanEntry[] {
  const prev = new Map(old.map((i) => [i.projectId, i]));
  return fresh
    .filter((i) => i.status !== "not-needed")
    .map((i) => {
      const was = prev.get(i.projectId);
      const selected = !usable(i) ? false : i.source === "optional" ? was?.source === "optional" && was.selected : isPick(i) ? (was && usable(was) ? was.selected : true) : true;
      return { ...i, selected, goal: was?.goal ?? null };
    });
}

/**
 * Compatibility in words. Modrinth lists each file's supported versions as its author declared
 * them, and build names often carry an older version ("2.8.2+1.21.10" for a file listed for
 * 1.21.11), so the release name stays in the tooltip, not in the row.
 */
export function compatibility(item: Pick<PlanItem, "versionNumber" | "versionType">, gameVersion: string): { label: string; title: string } {
  const pre = item.versionType && item.versionType !== "release" ? ` (${item.versionType})` : "";
  return {
    label: `Works with Minecraft ${gameVersion}`,
    title: item.versionNumber ? `Release ${item.versionNumber}${pre}. Its author lists it for Minecraft ${gameVersion} on Modrinth.` : `Listed for Minecraft ${gameVersion} on Modrinth.`,
  };
}

export type InstalledGroups = { picks: InstalledItem[]; performance: InstalledItem[]; dependencies: InstalledItem[]; optional: InstalledItem[]; total: number };

/** What a profile really has after setup, by why it is there (read back from the profile, not the plan). */
export function installedGroups(items: InstalledItem[]): InstalledGroups {
  const present = items.filter((i) => !i.missing);
  const src = (i: InstalledItem) => i.source as string;
  const byTitle = (a: InstalledItem, b: InstalledItem) => a.title.localeCompare(b.title);
  return {
    performance: present.filter((i) => src(i) === "flint").sort(byTitle),
    dependencies: present.filter((i) => src(i) === "dependency").sort(byTitle),
    optional: present.filter((i) => src(i) === "optional").sort(byTitle),
    picks: present.filter((i) => !["flint", "dependency", "optional"].includes(src(i))).sort(byTitle),
    total: present.length,
  };
}
