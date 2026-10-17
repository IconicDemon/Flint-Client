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
