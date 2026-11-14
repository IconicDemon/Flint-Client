/*
 * Where Library content comes from: Modrinth (the default) or CurseForge. CurseForge projects and files carry Flint ids
 * `cf:<number>` (src-tauri/src/curseforge.rs), so an installed item's source is read from its project id.
 */

export type ContentSource = "modrinth" | "curseforge";

export const SOURCES: readonly ContentSource[] = ["modrinth", "curseforge"];

export const SOURCE_NAME: Record<ContentSource, string> = { modrinth: "Modrinth", curseforge: "CurseForge" };

/** The source of a project or version id: `cf:<number>` is CurseForge, anything else Modrinth. */
export function sourceOf(id: string | null | undefined): ContentSource {
  return id && /^cf:[1-9]\d{0,11}$/.test(id) ? "curseforge" : "modrinth";
}

export const isCurseForge = (id: string | null | undefined) => sourceOf(id) === "curseforge";

const KEY = "flint-library-source";

/** The source picked earlier in this run of the app (Modrinth when none was). */
export function sessionSource(storage: Pick<Storage, "getItem"> | null = safeSession()): ContentSource {
  try {
    return storage?.getItem(KEY) === "curseforge" ? "curseforge" : "modrinth";
  } catch {
    return "modrinth";
  }
}

export function rememberSource(source: ContentSource, storage: Pick<Storage, "setItem"> | null = safeSession()) {
  try {
    storage?.setItem(KEY, source);
  } catch {
    // Private storage off: the choice lasts until the page closes.
  }
}

function safeSession(): Storage | null {
  try {
    return typeof sessionStorage === "undefined" ? null : sessionStorage;
  } catch {
    return null;
  }
}

/** The project's own page on its site. */
export function projectPage(hit: { projectId: string; slug: string; projectType: string; websiteUrl?: string | null }, kind: string | null): string {
  if (isCurseForge(hit.projectId)) {
    return hit.websiteUrl && hit.websiteUrl.startsWith("https://") ? hit.websiteUrl : `https://www.curseforge.com/minecraft/search?search=${encodeURIComponent(hit.slug)}`;
  }
  const type = hit.projectType === "datapack" || kind === "datapack" ? "datapack" : hit.projectType;
  return `https://modrinth.com/${type}/${hit.slug}`;
}

/** A CurseForge file's page (where an author who keeps downloads on CurseForge lets players get it). */
export function filePage(websiteUrl: string | null | undefined, versionId: string | null | undefined): string | null {
  if (!websiteUrl || !websiteUrl.startsWith("https://")) return null;
  const file = versionId && isCurseForge(versionId) ? versionId.slice(3) : null;
  return file ? `${websiteUrl.replace(/\/+$/, "")}/files/${file}` : websiteUrl;
}

/** Whether the page after this one is worth fetching ahead (it exists and the results aren't a calm "unavailable"). */
export function prefetchNext(total: number, page: number, pageSize: number): boolean {
  return total > (page + 1) * pageSize && (page + 1) * pageSize < 10_000;
}

/** Why CurseForge can't be searched right now, from a search answer, or null when it can. */
/** `ornithe`: CurseForge lists no mods for Minecraft 1.8.9 (it doesn't mark Ornithe builds; see the launcher's `ornithe.rs`). */
export type Unavailable = "unavailable" | "offline" | "busy" | "ornithe";
export function unavailableOf(results: { unavailable?: string | null } | null | undefined): Unavailable | null {
  const u = results?.unavailable;
  return u === "unavailable" || u === "offline" || u === "busy" || u === "ornithe" ? u : null;
}
