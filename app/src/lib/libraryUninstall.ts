// The Library's Uninstall button: which installed files a result card stands for in the chosen profile.
import type { InstalledItem, LibraryKind } from "./api";

/** One installed file: its kind, world (data packs) and name, the same as content_remove matches it. */
const fileKey = (i: Pick<InstalledItem, "kind" | "fileName" | "world">) => `${i.kind}\n${i.world ?? ""}\n${i.fileName}`;

/** A profile's installed items that came from a Modrinth project, by project id. */
export function installedByProject(items: readonly InstalledItem[]): Map<string, InstalledItem[]> {
  const map = new Map<string, InstalledItem[]>();
  for (const item of items) {
    if (!item.projectId) continue;
    const list = map.get(item.projectId);
    if (list) list.push(item);
    else map.set(item.projectId, [item]);
  }
  return map;
}

/**
 * What Uninstall removes for a project shown as `kind`, or null when its card offers none. Modpacks are whole profiles, so
 * never; a data pack only when it sits in exactly one known world (in several, removing one would leave it installed).
 */
export function uninstallItems(byProject: ReadonlyMap<string, InstalledItem[]>, projectId: string, kind: LibraryKind | null): InstalledItem[] | null {
  if (!kind || kind === "modpack") return null;
  const items = (byProject.get(projectId) ?? []).filter((i) => i.kind === kind);
  if (!items.length) return null;
  if (kind === "datapack") return items.length === 1 && items[0].world ? items : null;
  return items;
}

/** Enabled mods that require any of these items and aren't being removed themselves (the profile page's warning). */
export function dependentsOf(all: readonly InstalledItem[], list: readonly InstalledItem[]): InstalledItem[] {
  const ids = new Set(list.map((i) => i.projectId).filter((x): x is string => !!x));
  const names = new Set(list.map((i) => i.fileName));
  return all.filter((m) => m.kind === "mod" && m.enabled && !names.has(m.fileName) && m.requires.some((r) => ids.has(r)));
}

/** The items left once these were removed. */
export function withoutItems(all: readonly InstalledItem[], removed: readonly InstalledItem[]): InstalledItem[] {
  const gone = new Set(removed.map(fileKey));
  return all.filter((i) => !gone.has(fileKey(i)));
}
