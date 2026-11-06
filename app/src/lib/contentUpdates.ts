/*
 * Content updates without React: the profile's update check (cached per profile), and the Update projects
 * dialog's choices: which updates are ticked, which version each goes to, and what the batch adds. Tested
 * with Node's runner (`contentUpdates.test.ts`).
 */
import type { ContentKind, ContentUpdate, InstalledItem, UpdateCheck, UpdateDependency, UpdateRequest, UpdateResult, UpdateVersion } from "./api";
import { list } from "./format.ts";
import { t, type MessageKey, type PluralKey } from "./i18n.ts";

export type Choices = Record<string, string>;

/** One installed file, the same way the content list keys its rows. */
export const updateKey = (i: { kind: string; fileName: string; world?: string | null }) => `${i.kind}/${i.world ?? "-"}/${i.fileName}`;

/** Every version an update offers: the proposed one, then the other newer builds, newest first. */
export const versionsOf = (update: ContentUpdate): UpdateVersion[] => [update, ...update.alternatives];

/** The version an update goes to: the proposed one unless another was picked. */
export function chosen(update: ContentUpdate, choices: Choices): UpdateVersion {
  const id = choices[updateKey(update)];
  return (id && update.alternatives.find((v) => v.versionId === id)) || update;
}

/** Whether an update can go in as it is set: its version isn't blocked. */
export const selectable = (update: ContentUpdate, choices: Choices) => !chosen(update, choices).blocked;

/**
 * Updates whose file is still in the profile (a removed or replaced file's update is stale), each with the pin as the
 * list has it now.
 */
export function currentUpdates(updates: ContentUpdate[], items: InstalledItem[]): ContentUpdate[] {
  const rows = new Map(items.map((i) => [updateKey(i), i]));
  return updates.flatMap((u) => {
    const row = rows.get(updateKey(u));
    return row ? [{ ...u, pinned: !!row.pinned }] : [];
  });
}

/** What Update all counts: updates that can go in and aren't pinned. */
export const eligible = (updates: ContentUpdate[]) => updates.filter((u) => !u.pinned && !u.blocked);

/** Ticked when the dialog opens: every update that can go in and isn't pinned, or only the ones asked for (`only`). */
export function initialSelection(updates: ContentUpdate[], only?: string[] | null): Set<string> {
  const keys = only ? new Set(only) : null;
  return new Set(updates.filter((u) => !u.blocked && (keys ? keys.has(updateKey(u)) : !u.pinned)).map(updateKey));
}

/** What the header checkbox ticks: everything that can go in except pinned items, unless only pinned ones are left. */
function bulk(updates: ContentUpdate[], choices: Choices): ContentUpdate[] {
  const open = updates.filter((u) => selectable(u, choices));
  const unpinned = open.filter((u) => !u.pinned);
  return unpinned.length ? unpinned : open;
}

export function headerState(updates: ContentUpdate[], selected: Set<string>, choices: Choices): "all" | "some" | "none" {
  if (!updates.some((u) => selected.has(updateKey(u)))) return "none";
  const targets = bulk(updates, choices);
  return targets.length > 0 && targets.every((u) => selected.has(updateKey(u))) ? "all" : "some";
}

/** The header checkbox: on ticks everything `bulk` covers (pinned items already ticked stay ticked); off clears all. */
export function toggleAll(updates: ContentUpdate[], selected: Set<string>, choices: Choices, on: boolean): Set<string> {
  if (!on) return new Set();
  const next = new Set(selected);
  for (const u of bulk(updates, choices)) next.add(updateKey(u));
  return next;
}

/** One row's checkbox. An update that can't go in can't be ticked. */
export function toggle(update: ContentUpdate, on: boolean, selected: Set<string>, choices: Choices): Set<string> {
  const next = new Set(selected);
  if (on && selectable(update, choices)) next.add(updateKey(update));
  else next.delete(updateKey(update));
  return next;
}

/** Picks a version for an update. A version that can't go in unticks the row; picking one that can ticks it. */
export function choose(update: ContentUpdate, versionId: string, selected: Set<string>, choices: Choices): { choices: Choices; selected: Set<string> } {
  const key = updateKey(update);
  const next: Choices = { ...choices };
  if (versionId === update.versionId) delete next[key];
  else next[key] = versionId;
  return { choices: next, selected: toggle(update, !chosen(update, next).blocked, selected, next) };
}

/** The ticked updates as the backend takes them. */
export function requests(updates: ContentUpdate[], selected: Set<string>, choices: Choices): UpdateRequest[] {
  return updates
    .filter((u) => selected.has(updateKey(u)) && selectable(u, choices))
    .map((u) => ({ kind: u.kind, fileName: u.fileName, versionId: chosen(u, choices).versionId, world: u.world ?? null }));
}

/** The projects the ticked updates add, each once. A project the batch updates anyway isn't added. */
export function addedDependencies(updates: ContentUpdate[], selected: Set<string>, choices: Choices): UpdateDependency[] {
  const picked = updates.filter((u) => selected.has(updateKey(u)));
  const updating = new Set(picked.map((u) => u.projectId));
  const out = new Map<string, UpdateDependency>();
  for (const u of picked) {
    for (const dep of chosen(u, choices).adds) {
      if (!updating.has(dep.projectId) && !out.has(dep.projectId)) out.set(dep.projectId, dep);
    }
  }
  return [...out.values()];
}

/** "Update 1 project", "Update 3 projects". */
export const updateLabel = (n: number) => t("updates.dialog.submit", { count: n });

type VersionType = UpdateVersion["versionType"];

const TYPE_KEY: Record<VersionType, MessageKey> = { release: "updates.type.release", beta: "updates.type.beta", alpha: "updates.type.alpha" };

/** A version's release channel: "Release", "Beta", "Alpha". */
export const typeLabel = (type: VersionType) => t(TYPE_KEY[type]);

/** How what an update adds is worded: on its own ("Adds …"), after a "·" in a line ("adds …"), or as the changelog's sentence. */
const ADDS: Record<"label" | "inline" | "needed", { named: MessageKey; counted: PluralKey }> = {
  label: { named: "updates.adds.label", counted: "updates.adds.labelCount" },
  inline: { named: "updates.adds.inline", counted: "updates.adds.inlineCount" },
  needed: { named: "updates.adds.needed", counted: "updates.adds.neededCount" },
};

/** "Adds Fabric API", "Adds Fabric API and YACL", "Adds 3 dependencies"; `form` words it for its place in a line. */
export function addsLabel(adds: { title: string }[], form: keyof typeof ADDS = "label"): string {
  const { named, counted } = ADDS[form];
  if (adds.length === 1 || adds.length === 2) return t(named, { names: list(adds.map((a) => a.title)) });
  return t(counted, { count: adds.length });
}

/** What a kind of content must be built for in a profile. */
export function madeFor(kind: ContentKind, gameVersion: string, loader: string): string {
  if (kind === "mod" && loader === "fabric") return t("updates.target.fabric", { version: gameVersion });
  if (kind === "shader") return t("updates.target.iris", { version: gameVersion });
  return `Minecraft ${gameVersion}`;
}

const BUILT_FOR: Record<VersionType, MessageKey> = { release: "updates.hint.madeFor", beta: "updates.hint.betaFor", alpha: "updates.hint.alphaFor" };

/** The row button's tooltip: the version, what it's built for, and anything it adds. */
export function updateHint(update: ContentUpdate, gameVersion: string, loader: string): string {
  const parts = [t("updates.hint.updateTo", { version: update.versionNumber }), t(BUILT_FOR[update.versionType], { target: madeFor(update.kind, gameVersion, loader) })];
  if (update.adds.length) parts.push(addsLabel(update.adds, "inline"));
  return parts.join(" · ");
}

/** A finished update job in one line, for its toast and the dialog's result. */
export function resultSummary(result: UpdateResult): { tone: "success" | "neutral" | "danger"; title: string; body?: string } {
  const { updated, failed, added } = result;
  const total = updated.length + failed.length;
  const also = added.length ? t("updates.summary.alsoAdded", { names: added.map((a) => a.title).join(", ") }) : undefined;
  if (total === 0) return { tone: "neutral", title: t("updates.summary.upToDate") };
  if (failed.length === 0) {
    return { tone: "success", title: updated.length === 1 ? t("updates.summary.updatedOne", { name: updated[0].title }) : t("updates.summary.updated", { count: updated.length }), body: also };
  }
  const first = failed[0].reason ? t("updates.summary.reason", { name: failed[0].title, reason: failed[0].reason }) : undefined;
  if (updated.length === 0) {
    const title = failed.length === 1 ? t("updates.summary.failedOne", { name: failed[0].title }) : t("updates.summary.failed", { count: failed.length });
    return { tone: "danger", title, body: failed.length === 1 ? failed[0].reason : first };
  }
  return { tone: "neutral", title: t("updates.summary.partial", { updated: updated.length, count: total }), body: first };
}

/** The updates left once a job has applied some: those for the files it replaced are gone. */
export function withoutApplied(updates: ContentUpdate[], result: Pick<UpdateResult, "updated">): ContentUpdate[] {
  const done = new Set(result.updated.map(updateKey));
  return updates.filter((u) => !done.has(updateKey(u)));
}

/** Update jobs a dialog follows as they run: their outcome is shown there, not repeated in a toast. */
const shownInPlace = new Set<string>();
export function showInPlace(job: string, shown: boolean) {
  if (shown) shownInPlace.add(job);
  else shownInPlace.delete(job);
}
export const isShownInPlace = (job: string) => shownInPlace.has(job);

// ---------- the check, cached per profile ----------

/** How long a profile's check is reused before it's run again when its content tab opens. */
export const CHECK_TTL_MS = 30 * 60_000;

const checks = new Map<string, { check: UpdateCheck; at: number }>();
const running = new Map<string, { generation: number; promise: Promise<UpdateCheck> }>();
/** Bumped whenever a profile's content changes, so a check that started before the change is never reused after it. */
const generations = new Map<string, number>();

/** The last check of a profile while it's fresh, else null. */
export function cachedCheck(profileId: string, now = Date.now()): UpdateCheck | null {
  const hit = checks.get(profileId);
  return hit && now - hit.at < CHECK_TTL_MS ? hit.check : null;
}

/** The profile's content changed: the next look checks again. */
export function forgetCheck(profileId: string) {
  checks.delete(profileId);
  generations.set(profileId, (generations.get(profileId) ?? 0) + 1);
}

/** Checks a profile, or joins the check already running for it since its content last changed. `fresh` skips the cache (the manual refresh). */
export function runCheck(profileId: string, fetch: () => Promise<UpdateCheck>, { fresh = false, now = () => Date.now() } = {}): Promise<UpdateCheck> {
  const cached = fresh ? null : cachedCheck(profileId, now());
  if (cached) return Promise.resolve(cached);
  const generation = generations.get(profileId) ?? 0;
  const pending = running.get(profileId);
  if (pending?.generation === generation) return pending.promise;
  const promise = fetch()
    .then((check) => {
      if ((generations.get(profileId) ?? 0) === generation) checks.set(profileId, { check, at: now() });
      return check;
    })
    .finally(() => {
      if (running.get(profileId)?.promise === promise) running.delete(profileId);
    });
  running.set(profileId, { generation, promise });
  return promise;
}
