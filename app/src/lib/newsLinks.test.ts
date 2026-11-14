// Run with `npm test`. Home's news: Flint's own releases, and where each one leads (src/lib/newsLinks.ts).
import assert from "node:assert/strict";
import { test } from "node:test";
import notes from "../data/releaseNotes.json" with { type: "json" };
import { coverThumbnail, NEWS_SINCE, newsReleases, releasePage } from "./newsLinks.ts";

test("every Flint release opens exactly its own page on flintclient.com", () => {
  for (const note of notes) assert.equal(releasePage(note), "https://flintclient.com/changelog/", note.version);
  assert.equal(releasePage({ version: "1.0.0", page_url: "https://flintclient.com/releases/0.9.1/" }), null, "never another version's page");
  assert.equal(releasePage({ version: "1.0.0", page_url: "https://flintclient.com/releases/1.0.0" }), null, "the exact form only");
  assert.equal(releasePage({ version: "1.0.0", page_url: "http://flintclient.com/releases/1.0.0/" }), null);
  assert.equal(releasePage({ version: "1.0.0" }), null, "no page_url: not clickable");
  assert.equal(releasePage({ version: "../1.0.0", page_url: "https://flintclient.com/releases/../1.0.0/" }), null);
});

const versions = (list: { version: string }[]) => list.map((n) => n.version);
/** Whether a version is the news floor (NEWS_SINCE) or newer. */
const atLeastSince = (version: string) => {
  const x = version.split(".").map(Number);
  const y = NEWS_SINCE.split(".").map(Number);
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] > y[i];
  return true;
};
/** This build's own releases that count as news, newest first (the notes are newest first). */
const ownNews = () => versions(notes.filter((n) => atLeastSince(n.version)));
const feedRelease = (version: string, extra: Record<string, unknown> = {}) => ({ version, date: "2026-11-20", title: "Next", summary: "What's new.", page_url: `https://flintclient.com/releases/${version}/`, ...extra });

test("news is this build's own releases when the feed can't be read", () => {
  const own = ownNews().slice(0, 3);
  assert.deepEqual(versions(newsReleases(notes, null)), own);
  assert.deepEqual(versions(newsReleases(notes, undefined)), own);
  assert.deepEqual(versions(newsReleases(notes, [])), own);
});

test("a release this build doesn't know comes from the feed, newest first, and opens its own page", () => {
  // 9.x: newer than any release this build ships notes for.
  const shown = newsReleases(notes, [feedRelease("9.0.2"), feedRelease("0.9.1", { title: "Changed" })]);
  assert.deepEqual(versions(shown), ["9.0.2", ...ownNews()].slice(0, 3));
  assert.equal(releasePage(shown[0]), "https://flintclient.com/changelog/", "a release opens the changelog directly (no /releases/ redirect)");
  assert.equal(newsReleases(notes, [feedRelease(NEWS_SINCE, { title: "Changed" })], 9).find((n) => n.version === NEWS_SINCE)?.title, notes.find((n) => n.version === NEWS_SINCE)?.title, "this build's own notes win for a version both have");
  assert.deepEqual(versions(newsReleases(notes, [feedRelease("9.0.9"), feedRelease("9.0.10")])).slice(0, 2), ["9.0.10", "9.0.9"], "by number, not text");
  assert.equal(releasePage(newsReleases(notes, [feedRelease("9.0.2", { page_url: null })])[0]), null, "no page: shown, not clickable");
});

test("news starts at ${NEWS_SINCE}: older releases never show, from this build or the feed", () => {
  const shown = versions(newsReleases(notes, [feedRelease("1.0.2"), feedRelease("0.9.9"), feedRelease("9.0.2")], 9));
  assert.ok(shown.every(atLeastSince), shown.join(", "));
  assert.ok(shown.includes("9.0.2") && shown.includes(NEWS_SINCE));
  assert.ok(!shown.includes("1.0.2") && !shown.includes("1.0.1"));
});

test("a feed release that fails any check is left out", () => {
  const own = ownNews().slice(0, 3);
  for (const bad of [
    feedRelease("9.0.2-beta"),
    feedRelease("../9.0.2"),
    feedRelease("1.0"),
    feedRelease("9.0.2", { date: "2026-11-2" }),
    feedRelease("9.0.2", { date: undefined }),
    feedRelease("9.0.2", { title: " " }),
    feedRelease("9.0.2", { title: "x".repeat(121) }),
    feedRelease("9.0.2", { summary: { html: "<b>x</b>" } }),
    feedRelease("9.0.2", { summary: "x".repeat(401) }),
    null,
    "9.0.2",
    7,
  ]) {
    assert.deepEqual(versions(newsReleases(notes, [bad])), own, JSON.stringify(bad));
  }
});

test("a release's cover comes only from its own cover address on flintclient.com, as the 960-wide row picture", () => {
  const base = "https://flintclient.com/releases/covers";
  assert.equal(coverThumbnail("9.0.1", { url: `${base}/9.0.1.webp` }), `${base}/9.0.1-960.webp`);
  assert.equal(coverThumbnail("9.0.1", { url: `${base}/9.0.1.png` }), `${base}/9.0.1-960.webp`);
  for (const image of [null, undefined, "x", { url: `${base}/9.0.0.webp` }, { url: `http://flintclient.com/releases/covers/9.0.1.webp` }, { url: `${base}/9.0.1.svg` }, { url: `https://evil.example/releases/covers/9.0.1.webp` }]) {
    assert.equal(coverThumbnail("9.0.1", image), null, JSON.stringify(image));
  }
  assert.equal(coverThumbnail("../9.0.1", { url: `${base}/../9.0.1.webp` }), null);
  const [newer] = newsReleases(notes, [{ version: "9.0.1", date: "2026-12-01", title: "Next", summary: "", page_url: null, image: { url: `${base}/9.0.1.webp`, width: 1920, height: 1080, alt: "" } }]);
  assert.equal(newer.cover, `${base}/9.0.1-960.webp`, "a newer release from the feed brings its cover");
  for (const note of notes) assert.equal(coverThumbnail(note.version, note.image), `${base}/${note.version}-960.webp`, `${note.version} ships its cover address`);
});
