/*
 * Home's news: Flint's own releases only (owner, 1.0.1; Minecraft's patch notes aren't shown). The notes this build ships
 * with (releaseNotes.json) always show. Flint's public release feed (src-tauri/src/data.rs `news_feed`, the same file
 * flintclient.com builds its changelog from) adds the releases this build doesn't know, so news of an update shows before
 * it installs. A release opens its own page on flintclient.com and only that version's page (`page_url`, which must be
 * exactly https://flintclient.com/releases/<version>/) and opens the changelog; anything else isn't clickable.
 */

/** One release in Home's news. `cover`: its row picture from the feed (see {@link coverThumbnail}), when it has one. */
export type NewsRelease = { version: string; date: string; title: string; summary: string; page_url?: string | null; cover?: string | null };

const VERSION = /^\d{1,3}\.\d{1,3}\.\d{1,3}$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

/** The one address flintclient.com serves a version's page at. */
export const flintReleasePage = (version: string) => `https://flintclient.com/releases/${version}/`;

/**
 * Where a release row opens: flintclient.com's changelog (one page, the newest release in full; the site 301s each
 * /releases/<version>/ there, so opening it directly skips that hop, which also matters for link previews). Only a release
 * whose `page_url` is exactly its own version's page is clickable; anything else: null.
 */
export const FLINT_CHANGELOG = "https://flintclient.com/changelog/";

export function releasePage(note: { version: string; page_url?: unknown }): string | null {
  if (!VERSION.test(note.version)) return null;
  return note.page_url === flintReleasePage(note.version) ? FLINT_CHANGELOG : null;
}

/**
 * A feed release's row picture: the 960-wide cover flintclient.com serves next to the release's own cover
 * (`/releases/covers/<version>-960.webp`), only when the feed's `image.url` is exactly that version's cover (.webp or .png).
 * Anything else: null (the row shows Flint's own picture).
 */
export function coverThumbnail(version: string, image: unknown): string | null {
  if (!VERSION.test(version) || !image || typeof image !== "object") return null;
  const url = (image as Record<string, unknown>).url;
  const base = `https://flintclient.com/releases/covers/${version}`;
  return url === `${base}.webp` || url === `${base}.png` ? `${base}-960.webp` : null;
}

/**
 * The oldest release Home's news lists (owner, 1.0.4): from 1.0.3 on, the same releases flintclient.com's changelog shows.
 * Older notes stay in the app (What's new) and on the site's release pages; they just aren't news any more.
 */
export const NEWS_SINCE = "1.0.3";

/** Newest first by version number (1.0.10 comes before 1.0.9). */
function newestFirst(a: Pick<NewsRelease, "version">, b: Pick<NewsRelease, "version">): number {
  const x = a.version.split(".").map(Number);
  const y = b.version.split(".").map(Number);
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return y[i] - x[i];
  return 0;
}

/** Whether a release is {@link NEWS_SINCE} or newer. */
const isNews = (n: Pick<NewsRelease, "version">) => newestFirst(n, { version: NEWS_SINCE }) <= 0;

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
    extra.push({ version: r.version, date: r.date, title: r.title, summary: r.summary ?? "", page_url: typeof r.page_url === "string" ? r.page_url : null, cover: coverThumbnail(r.version, r.image) });
  }
  return [...bundled.filter((n) => VERSION.test(n.version)), ...extra].filter(isNews).sort(newestFirst).slice(0, count);
}
