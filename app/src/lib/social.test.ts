// Run with `npm test`. The Friends page's own logic: sections, the badge, the chat's rows and the gift link.
import assert from "node:assert/strict";
import { test } from "node:test";
import { badgeCount, chatItems, EMPTY_SNAPSHOT, errorKey, friendSections, giftUrl, isPlayerName, mergeMessages, statusKey, type ChatMessage, type Friend, type PendingMessage } from "./social.ts";

const friend = (name: string, extra: Partial<Friend> = {}): Friend => ({ uuid: name.toLowerCase().padEnd(32, "0"), name, status: "online", inGame: false, lastSeen: null, pinned: false, since: null, unread: 0, ...extra });
const ME = "a".repeat(32);
const YOU = "b".repeat(32);
const message = (id: number, from: string, at: number, extra: Partial<ChatMessage> = {}): ChatMessage => ({ id, from, to: from === ME ? YOU : ME, body: `m${id}`, sensitive: false, at, read: true, ...extra });

test("friends are listed pinned, then online, then offline", () => {
  const list = [
    friend("Zed", { status: "offline", lastSeen: 1000 }),
    friend("amy", { status: "away" }),
    friend("Bob", { inGame: true }),
    friend("Cat", { pinned: true, status: "offline" }),
    friend("Dan", { pinned: true }),
    friend("Eve", { status: "offline", lastSeen: 5000 }),
    friend("Ann"),
  ];
  const { pinned, online, offline } = friendSections(list);
  assert.deepEqual(pinned.map((f) => f.name), ["Dan", "Cat"]);
  assert.deepEqual(online.map((f) => f.name), ["Bob", "Ann", "amy"], "playing first, then by status and name");
  assert.deepEqual(offline.map((f) => f.name), ["Eve", "Zed"], "most recently seen first");
  assert.equal(statusKey(friend("x", { inGame: true })), "friends.status.inGame");
  assert.equal(statusKey(friend("x", { status: "offline", inGame: true })), "friends.status.offline");
});

test("the badge counts requests and unread messages", () => {
  assert.equal(badgeCount(null), 0);
  const snapshot = { ...EMPTY_SNAPSHOT, phase: "ready" as const, incoming: [{ id: 1, uuid: YOU, name: "B", at: null }], friends: [friend("A", { unread: 3 }), friend("B", { unread: 1 })] };
  assert.equal(badgeCount(snapshot), 5);
  assert.equal(badgeCount({ ...snapshot, phase: "signed_out" }), 0);
});

test("a chat groups messages by sender and day", () => {
  const day = new Date(2026, 10, 3, 12, 0).getTime();
  const pending: PendingMessage = { key: "p1", clientId: "6f1c2e0a-8f4b-4d55-9a7e-2b3c4d5e6f70", to: YOU, body: "on its way", sensitive: false, at: day + 86_400_000 + 60_000, failed: false };
  const items = chatItems(
    [
      message(1, YOU, day),
      message(2, YOU, day + 60_000),
      message(3, ME, day + 120_000),
      message(4, ME, day + 120_000 + 6 * 60_000),
      message(5, ME, day + 86_400_000),
      pending,
    ],
    ME,
  );
  const shape = items.map((i) => (i.type === "day" ? "day" : `${i.mine ? "me" : "you"}${i.first ? "^" : ""}${i.last ? "$" : ""}`));
  assert.deepEqual(shape, ["day", "you^", "you$", "me^$", "me^$", "day", "me^", "me$"]);
  assert.equal(items[items.length - 1].key, "p1");
});

test("messages merge by id, oldest first", () => {
  const merged = mergeMessages([message(3, ME, 3), message(1, YOU, 1)], [message(2, YOU, 2), message(3, ME, 3, { body: "edited" })]);
  assert.deepEqual(merged.map((m) => [m.id, m.body]), [[1, "m1"], [2, "m2"], [3, "edited"]]);
});

test("the gift link names the friend for the Store", () => {
  assert.equal(giftUrl("https://store.flintclient.com/", "8667ba71-b85a-4004-af54-457a9734eed7", "Steve & co"), "https://store.flintclient.com/cosmetics/?for=gift&to=8667ba71b85a4004af54457a9734eed7&toName=Steve%20%26%20co");
});

test("names and error codes", () => {
  assert.ok(isPlayerName("Steve_01") && !isPlayerName("") && !isPlayerName("seventeen_letters") && !isPlayerName("no spaces"));
  assert.equal(errorKey("not_on_flint"), "friends.errors.notOnFlint");
  assert.equal(errorKey("something new"), "friends.errors.generic");
  assert.equal(errorKey(new Error("x")), "friends.errors.generic");
});
