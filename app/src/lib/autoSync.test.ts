// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import { autoSyncLine, WAITING_TEXT } from "./autoSync.ts";

const notice = { id: "unreadable:p1", text: "A settings file in ‘Survival’ couldn't be read; Auto Sync is leaving that profile's settings alone." };

test("the line says Auto Sync is waiting only while the store is empty", () => {
  assert.deepEqual(autoSyncLine(true, { enabled: true, waiting: true }), { text: WAITING_TEXT });
  assert.equal(autoSyncLine(true, { enabled: true, waiting: false }), null, "nothing to say");
});

test("a notice is shown with its Dismiss and wins over waiting", () => {
  assert.deepEqual(autoSyncLine(true, { enabled: true, waiting: true, notice }), { text: notice.text, dismissId: "unreadable:p1" });
});

test("nothing shows while Auto Sync is off or its status isn't known yet", () => {
  assert.equal(autoSyncLine(false, { enabled: true, waiting: true, notice }), null);
  assert.equal(autoSyncLine(true, null), null);
});

// ---------- Settings → Syncing ----------

import { SYNC_SWITCHES, since, sourceChoices, startExplainer, startedLine, syncStatusLine } from "./autoSync.ts";
import type { AutoSyncOverview } from "./api";

const NOW = 1_800_000_000;
const overview = (patch: Partial<AutoSyncOverview> = {}): AutoSyncOverview => ({
  enabled: true,
  waiting: false,
  notices: [],
  switches: SYNC_SWITCHES.map((s) => ({ id: s.id, on: !["servers", "hotbars", "commandHistory"].includes(s.id) })),
  profiles: [
    { id: "a", name: "Survival", gameVersion: "26.3", excluded: false, canStart: true, backups: 2, lastPlayed: NOW - 60 },
    { id: "b", name: "PvP", gameVersion: "1.8.9", excluded: false, canStart: true, backups: 0, lastPlayed: NOW - 3600 },
    { id: "c", name: "Fresh", gameVersion: "26.2", excluded: false, canStart: false, backups: 0 },
    { id: "d", name: "Old", gameVersion: "1.20.1", excluded: true, canStart: false, backups: 0, note: "Shares its changes" },
  ],
  defaultSource: "b",
  ...patch,
});

test("every switch has one line saying what it shares, and command history promises login commands stay put", () => {
  assert.equal(new Set(SYNC_SWITCHES.map((s) => s.id)).size, 8);
  for (const s of SYNC_SWITCHES) assert.ok(s.desc.endsWith(".") && s.desc.length < 140, s.id);
  assert.match(SYNC_SWITCHES.find((s) => s.id === "commandHistory")!.desc, /Login commands like \/login are never copied/);
  assert.match(SYNC_SWITCHES.find((s) => s.id === "hotbars")!.desc, /never 1\.8\.9/);
});

test("the status line shows a notice first, then waiting, then when it last synced", () => {
  assert.equal(syncStatusLine(null), null);
  assert.equal(syncStatusLine(overview({ enabled: false, notices: [notice] })), null, "nothing while off");
  assert.deepEqual(syncStatusLine(overview({ notices: [notice], waiting: true })), { text: notice.text, dismissId: notice.id });
  assert.deepEqual(syncStatusLine(overview({ waiting: true })), { text: WAITING_TEXT });
  assert.deepEqual(syncStatusLine(overview({ lastSync: { at: NOW - 300, profile: "a" } }), NOW), { text: "Last synced 5 min ago, with ‘Survival’." });
  assert.equal(syncStatusLine(overview({ lastSync: { at: NOW, profile: "gone" } }), NOW), null, "a profile that's gone isn't named");
  assert.equal(since(NOW - 30, NOW), "just now");
  assert.equal(since(NOW - 7200, NOW), "2 h ago");
});

test("the start-from list holds only profiles Auto Sync can read now, the one it would pick first", () => {
  const choices = sourceChoices(overview());
  assert.deepEqual(choices.map((c) => c.value), ["b", "a"]);
  assert.equal(choices[0].hint, "1.8.9 · played last");
  assert.deepEqual(sourceChoices(overview({ defaultSource: undefined, profiles: [] })), []);
});

test("turning something on says where it starts and that nothing is overwritten", () => {
  const text = startExplainer("server list", "Survival");
  assert.match(text, /New profiles start with the server list from ‘Survival’/);
  assert.match(text, /keep their own until you change it/);
  assert.match(startExplainer("settings", null), /starts empty/);
  const o = overview({ switches: [{ id: "servers", on: true, startedFrom: "a" }, { id: "hotbars", on: false, startedFrom: "a" }] });
  assert.equal(startedLine(o, "servers"), "Started from ‘Survival’.");
  assert.equal(startedLine(o, "hotbars"), null, "only while it's on");
});
