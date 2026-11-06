// Run with `npm test`. Home's news: Flint's own releases, and where each one leads (src/lib/newsLinks.ts).
import assert from "node:assert/strict";
import { test } from "node:test";
import notes from "../data/releaseNotes.json" with { type: "json" };
import { newsReleases, releasePage } from "./newsLinks.ts";

test("every Flint release opens exactly its own page on flintclient.com", () => {
  for (const note of notes) assert.equal(releasePage(note), `https://flintclient.com/releases/${note.version}/`, note.version);
  assert.equal(releasePage({ version: "1.0.0", page_url: "https://flintclient.com/releases/0.9.1/" }), null, "never another version's page");
  assert.equal(releasePage({ version: "1.0.0", page_url: "https://flintclient.com/releases/1.0.0" }), null, "the exact form only");
  assert.equal(releasePage({ version: "1.0.0", page_url: "http://flintclient.com/releases/1.0.0/" }), null);
  assert.equal(releasePage({ version: "1.0.0" }), null, "no page_url: not clickable");
  assert.equal(releasePage({ version: "../1.0.0", page_url: "https://flintclient.com/releases/../1.0.0/" }), null);
});

const versions = (list: { version: string }[]) => list.map((n) => n.version);
const feedRelease = (version: string, extra: Record<string, unknown> = {}) => ({ version, date: "2026-11-20", title: "Next", summary: "What's new.", page_url: `https://flintclient.com/releases/${version}/`, ...extra });

test("news is this build's own releases when the feed can't be read", () => {
  const own = versions(notes.slice(0, 3));
  assert.deepEqual(versions(newsReleases(notes, null)), own);
  assert.deepEqual(versions(newsReleases(notes, undefined)), own);
  assert.deepEqual(versions(newsReleases(notes, [])), own);
});

test("a release this build doesn't know comes from the feed, newest first, and opens its own page", () => {
  const shown = newsReleases(notes, [feedRelease("1.0.2"), feedRelease("0.9.1", { title: "Changed" })]);
  assert.deepEqual(versions(shown), ["1.0.2", notes[0].version, notes[1].version]);
  assert.equal(releasePage(shown[0]), "https://flintclient.com/releases/1.0.2/");
  assert.equal(newsReleases(notes, [feedRelease("0.9.1", { title: "Changed" })], 9).find((n) => n.version === "0.9.1")?.title, notes.find((n) => n.version === "0.9.1")?.title, "this build's own notes win for a version both have");
  assert.deepEqual(versions(newsReleases(notes, [feedRelease("1.0.9"), feedRelease("1.0.10")])).slice(0, 2), ["1.0.10", "1.0.9"], "by number, not text");
  assert.equal(releasePage(newsReleases(notes, [feedRelease("1.0.2", { page_url: null })])[0]), null, "no page: shown, not clickable");
});

test("a feed release that fails any check is left out", () => {
  const own = versions(notes.slice(0, 3));
  for (const bad of [
    feedRelease("1.0.2-beta"),
    feedRelease("../1.0.2"),
    feedRelease("1.0"),
    feedRelease("1.0.2", { date: "2026-11-2" }),
    feedRelease("1.0.2", { date: undefined }),
    feedRelease("1.0.2", { title: " " }),
    feedRelease("1.0.2", { title: "x".repeat(121) }),
    feedRelease("1.0.2", { summary: { html: "<b>x</b>" } }),
    feedRelease("1.0.2", { summary: "x".repeat(401) }),
    null,
    "1.0.2",
    7,
  ]) {
    assert.deepEqual(versions(newsReleases(notes, [bad])), own, JSON.stringify(bad));
  }
});
