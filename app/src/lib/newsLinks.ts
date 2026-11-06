/*
 * Home's news: Flint's own releases only (owner, 1.0.1; Minecraft's patch notes aren't shown). The notes this build ships
 * with (releaseNotes.json) always show. Flint's public release feed (src-tauri/src/data.rs `news_feed`, the same file
 * flintclient.com builds its changelog from) adds the releases this build doesn't know, so news of an update shows before
 * it installs. A release opens its own page on flintclient.com and only that version's page (`page_url`, which must be
 * exactly https://flintclient.com/releases/<version>/); anything else isn't clickable.
 */

/** One release in Home's news. */
export type NewsRelease = { version: string; date: string; title: string; summary: string; page_url?: string | null };

const VERSION = /^\d{1,3}\.\d{1,3}\.\d{1,3}$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

/** The one address flintclient.com serves a version's page at. */
export const flintReleasePage = (version: string) => `https://flintclient.com/releases/${version}/`;

/** A Flint release item's page: its `page_url` when that is exactly its own version's page, else null. */
export function releasePage(note: { version: string; page_url?: unknown }): string | null {
  if (!VERSION.test(note.version)) return null;
  const page = flintReleasePage(note.version);
  return note.page_url === page ? page : null;
}

/** Newest first by version number (1.0.10 comes before 1.0.9). */
function newestFirst(a: NewsRelease, b: NewsRelease): number {
  const x = a.version.split(".").map(Number);
  const y = b.version.split(".").map(Number);
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return y[i] - x[i];
  return 0;
}

/**
 * The releases Home's news shows, newest first, `count` at most: this build's own notes, plus each release from Flint's
 * feed that they don't have, checked again here (a plain x.y.z version, a YYYY-MM-DD date, a title of at most 120
 * characters, a summary of at most 400; anything else is left out). For a version both have, this build's notes win.
 */
export function newsReleases(bundled: readonly NewsRelease[], feed: readonly unknown[] | null | undefined, count = 3): NewsRelease[] {
  const known = new Set(bundled.map((n) => n.version));
  const extra: NewsRelease[] = [];
  for (const raw of feed ?? []) {
    if (!raw || typeof raw !== "object") continue;
    const r = raw as Record<string, unknown>;
    if (typeof r.version !== "string" || !VERSION.test(r.version) || known.has(r.version)) continue;
    if (typeof r.date !== "string" || !DAY.test(r.date)) continue;
    if (typeof r.title !== "string" || !r.title.trim() || r.title.length > 120) continue;
    if (r.summary != null && (typeof r.summary !== "string" || r.summary.length > 400)) continue;
    known.add(r.version);
    extra.push({ version: r.version, date: r.date, title: r.title, summary: r.summary ?? "", page_url: typeof r.page_url === "string" ? r.page_url : null });
  }
  return [...bundled.filter((n) => VERSION.test(n.version)), ...extra].sort(newestFirst).slice(0, count);
}
