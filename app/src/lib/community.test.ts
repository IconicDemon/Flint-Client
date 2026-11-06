// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  attemptProblem,
  ATTEMPT_MAX_MS,
  canRecheck,
  cardAction,
  cardState,
  discordNotice,
  forAccount,
  isConditional,
  joinRewards,
  linkStatus,
  obtainedLabel,
  offerSignedOut,
  ownItems,
  pollAgain,
  recheckWait,
  rewardItem,
  showConnections,
  type CommunityCatalog,
  type CommunityReward,
  type ConnectionsView,
  type DiscordLink,
  type RewardStatus,
  type RewardsView,
  type Standing,
} from "./community.ts";
import type { StoreInventory, StoreItem } from "./storeSync.ts";

const STEVE = "8667ba71b85a4004af54457a9734eed7";
const ALEX = "ec561538f3fd461daff5086b22154bce";
const MEMBER = "00000000-0000-4000-8000-000000000101";
const BOOSTER = "00000000-0000-4000-8000-000000000102";
const FIRST = "00000000-0000-4000-8000-000000000103";
const BOUGHT = "00000000-0000-4000-8000-000000000201";

const reward = (cosmeticId: string, type: string, permanence: "permanent" | "conditional"): CommunityReward => ({
  cosmeticId,
  slug: type,
  kind: "cape",
  name: `${type} cape`,
  requirement: { type, label: `label for ${type}` },
  permanence,
  sort: 100,
  asset: { id: `a-${cosmeticId}`, version: 1 },
  animated: false,
  hd: false,
});

const catalog = (over: Partial<CommunityCatalog> = {}): CommunityCatalog => ({
  state: "ok",
  enabled: true,
  discordEnabled: true,
  rewards: [reward(MEMBER, "discord_member", "conditional"), reward(BOOSTER, "discord_booster", "conditional"), reward(FIRST, "first_launch", "permanent")],
  ...over,
});

const standing = (over: Partial<Standing> = {}): Standing => ({ linked: true, member: true, booster: false, pending: false, state: "verified", ...over });
const status = (cosmeticId: string, s: RewardStatus["status"], kind: RewardStatus["action"]["kind"] = "none", url: string | null = null): RewardStatus => ({
  cosmeticId,
  requirement: "",
  permanence: "conditional",
  status: s,
  action: { kind, url },
});
const rewards = (list: RewardStatus[], over: Partial<RewardsView> = {}): RewardsView => ({ uuid: STEVE, state: "ok", discord: standing(), rewards: list, ...over });

const owned = (cosmeticId: string, over: Partial<StoreItem> = {}): StoreItem => ({
  cosmeticId,
  slug: cosmeticId,
  kind: "cape",
  name: cosmeticId,
  test: false,
  gift: false,
  asset: { id: `a-${cosmeticId}`, version: 1 },
  ...over,
});
const inventory = (items: StoreItem[], equipped: StoreInventory["equipped"] = {}): StoreInventory => ({ version: 3, name: "Steve", balance: 0, owned: items, equipped });

test("an answer for another account is never used", () => {
  const view: ConnectionsView = { uuid: STEVE, state: "ok", canLink: true, discord: null };
  assert.equal(forAccount(STEVE, view), view);
  assert.equal(forAccount("8667BA71-B85A-4004-AF54-457A9734EED7", view), view, "any spelling of the same account");
  assert.equal(forAccount(ALEX, view), null);
  assert.equal(forAccount(STEVE, null), null);
});

test("Connected Accounts shows only when the Store has the feature and there is a link or linking is on", () => {
  const link: DiscordLink = { username: "flintfan", guild: { state: "verified", member: true, booster: false } };
  assert.equal(showConnections(null), false);
  assert.equal(showConnections({ uuid: STEVE, state: "off", canLink: false, discord: null }), false, "a Store without the routes: nothing");
  assert.equal(showConnections({ uuid: STEVE, state: "unavailable", canLink: false, discord: null }), false, "no error row");
  assert.equal(showConnections({ uuid: STEVE, state: "ok", canLink: false, discord: null }), false, "linking off, nothing linked: nothing");
  assert.equal(showConnections({ uuid: STEVE, state: "ok", canLink: false, discord: link }), true, "linking off: an existing link stays to unlink");
  assert.equal(showConnections({ uuid: STEVE, state: "ok", canLink: true, discord: null }), true);
});

test("signed out of the Store, the row offers signing in only when the public catalog says the feature is on", () => {
  assert.equal(offerSignedOut(null), false, "no answer: no row");
  assert.equal(offerSignedOut({ state: "off", enabled: false, discordEnabled: false }), false, "a Store without the catalog (404): no row");
  assert.equal(offerSignedOut({ state: "unavailable", enabled: false, discordEnabled: false }), false, "not answering: no row");
  assert.equal(offerSignedOut({ state: "ok", enabled: false, discordEnabled: false }), false, "both off (production today): no row");
  assert.equal(offerSignedOut({ state: "ok", enabled: true, discordEnabled: false }), true, "community rewards on");
  assert.equal(offerSignedOut({ state: "ok", enabled: true, discordEnabled: true }), true, "Discord rewards on");
  assert.equal(offerSignedOut({ state: "ok", enabled: false, discordEnabled: true }), true, "either flag is enough");
});

test("a linked account's status says how Flint's server sees it", () => {
  const at = (guild: DiscordLink["guild"]) => linkStatus({ username: "x", guild }).label;
  assert.equal(at({ state: "checking" }), "connections.discord.checking");
  assert.equal(linkStatus({ username: "x", guild: { state: "checking" } }).checking, true);
  assert.equal(at({ state: "unavailable", member: true }), "connections.discord.cantCheck");
  assert.equal(at({ state: "pending", member: false }), "connections.discord.screening");
  assert.equal(at({ state: "verified", member: true, booster: true }), "connections.discord.booster");
  assert.equal(at({ state: "verified", member: true, booster: false }), "connections.discord.member");
  assert.equal(at({ state: "verified", member: false, booster: false }), "connections.discord.notMember");
  assert.equal(at({ state: "verified", member: null, booster: null }), "connections.discord.checking", "not checked yet is never shown as a member");
});

test("a link attempt is polled only while it waits, for at most 10 minutes, and every ending has words and Try again", () => {
  const start = 1_000_000;
  assert.equal(pollAgain("waiting", start, start + 2_000), true);
  assert.equal(pollAgain(null, start, start), true, "before the first answer");
  assert.equal(pollAgain("waiting", start, start + ATTEMPT_MAX_MS), false, "10 minutes: stop");
  for (const state of ["linked", "cancelled", "denied", "expired", "already_linked_elsewhere", "error"] as const) {
    assert.equal(pollAgain(state, start, start + 2_000), false, state);
  }
  assert.equal(attemptProblem("waiting"), null);
  assert.equal(attemptProblem("linked"), null);
  assert.deepEqual(attemptProblem("denied"), { title: "connections.link.denied.title", body: "connections.link.denied.body" });
  assert.deepEqual(attemptProblem("already_linked_elsewhere"), { title: "connections.link.elsewhere.title", body: "connections.link.elsewhere.body" });
  for (const state of ["cancelled", "expired", "error"] as const) assert.ok(attemptProblem(state), state);
});

test("the Community Rewards cards are the catalog joined with the account's standing and inventory", () => {
  const cards = joinRewards(
    catalog(),
    rewards([status(MEMBER, "eligible"), status(BOOSTER, "locked", "boost_discord", "https://discord.gg/flint"), status(FIRST, "owned")]),
    inventory([owned(MEMBER, { source: "reward", obtainedVia: "discord_member", permanence: "conditional", state: "eligible" }), owned(FIRST, { obtainedVia: "first_launch", permanence: "permanent" })], { cape: MEMBER }),
  );
  assert.deepEqual(
    cards.map((c) => [c.reward.cosmeticId, c.status, c.action.kind, !!c.item, c.worn, c.conditional]),
    [
      [MEMBER, "eligible", "none", true, true, true],
      [BOOSTER, "locked", "boost_discord", false, false, true],
      [FIRST, "owned", "none", true, false, false],
    ],
  );
});

test("flags: no catalog, a Store without it, or community rewards off is no group; Discord rewards off drops them", () => {
  assert.deepEqual(joinRewards(null, null, null), []);
  assert.deepEqual(joinRewards(catalog({ state: "off", rewards: [] }), null, null), []);
  assert.deepEqual(joinRewards(catalog({ state: "unavailable", rewards: [] }), null, null), []);
  assert.deepEqual(joinRewards(catalog({ enabled: false }), null, null), [], "even if a stale list still had rewards");
  const cards = joinRewards(catalog({ discordEnabled: false }), null, null);
  assert.deepEqual(cards.map((c) => c.reward.requirement.type), ["first_launch"]);
});

test("never a false unlock: without a standing a reward is locked with nothing to do, unless the inventory lists it", () => {
  for (const answer of [null, rewards([], { state: "unavailable" }), rewards([], { state: "off" })]) {
    const cards = joinRewards(catalog(), answer, inventory([]));
    assert.ok(cards.every((c) => c.status === "locked" && c.action.kind === "none" && !cardState(c).wearable), JSON.stringify(cards));
  }
  // The inventory is what the Store equips: a conditional item there is usable, and conditional.
  const cards = joinRewards(catalog(), null, inventory([owned(MEMBER, { source: "reward", permanence: "conditional" })]));
  assert.equal(cards[0].status, "eligible");
  assert.equal(cards[0].conditional, true);
  // A status that says usable but no inventory item yet (the inventory is about to change) can't be worn yet.
  const early = joinRewards(catalog(), rewards([status(MEMBER, "eligible")]), inventory([]));
  assert.equal(cardState(early[0]).wearable, false);
});

test("each status has its label, and only owned or eligible can be worn", () => {
  const card = (s: RewardStatus["status"], item: StoreItem | null = null) => joinRewards(catalog(), rewards([status(MEMBER, s)]), inventory(item ? [item] : []))[0];
  const member = owned(MEMBER, { source: "reward", permanence: "conditional" });
  assert.deepEqual(cardState(card("eligible", member)), { label: "store.rewards.status.eligible", tone: "success", busy: false, wearable: true, note: "store.rewards.note.conditional" });
  assert.equal(cardState(card("eligible", { ...member, state: "unavailable" })).note, "store.rewards.note.grace", "within the grace period");
  assert.deepEqual(cardState(card("checking")), { label: "store.rewards.status.checking", tone: "neutral", busy: true, wearable: false, note: null });
  assert.equal(cardState(card("unavailable")).label, "store.rewards.status.unavailable");
  assert.equal(cardState(card("unavailable")).wearable, false, "Can't check Discord: never usable");
  assert.equal(cardState(card("locked")).wearable, false);
  const kept = joinRewards(catalog(), rewards([status(FIRST, "owned")]), inventory([owned(FIRST, { permanence: "permanent" })])).find((c) => c.reward.cosmeticId === FIRST)!;
  assert.deepEqual([cardState(kept).label, cardState(kept).note, kept.conditional], ["store.rewards.status.owned", "store.rewards.note.permanent", false]);
  const launch = joinRewards(catalog(), rewards([status(FIRST, "locked", "launch")]), inventory([])).find((c) => c.reward.cosmeticId === FIRST)!;
  assert.equal(cardState(launch).note, "store.rewards.note.launch");
});

test("the action button follows action.kind, and an invite Rust didn't accept is no button", () => {
  const card = (kind: RewardStatus["action"]["kind"], url: string | null = null, s: RewardStatus["status"] = "locked") => joinRewards(catalog(), rewards([status(MEMBER, s, kind, url)]), inventory([]))[0];
  const invite = "https://discord.gg/flint";
  assert.deepEqual(cardAction(card("connect_discord")), { kind: "connect", label: "store.rewards.action.connect" });
  assert.deepEqual(cardAction(card("join_discord", invite)), { kind: "invite", url: invite, label: "store.rewards.action.join" });
  assert.deepEqual(cardAction(card("complete_screening", invite)), { kind: "invite", url: invite, label: "store.rewards.action.screening" });
  assert.deepEqual(cardAction(card("boost_discord", invite)), { kind: "invite", url: invite, label: "store.rewards.action.boost" });
  assert.equal(cardAction(card("join_discord", null)), null, "no checked invite: no button");
  assert.equal(cardAction(card("launch")), null, "unlocks at the next sign-in: said, not a button");
  assert.equal(cardAction(card("none")), null);
  assert.equal(cardAction(card("join_discord", invite, "checking")), null, "only locked rewards have something to do");
});

test("My Capes: bought, given and kept; conditional rewards the group shows are left out, others stay visible", () => {
  const member = owned(MEMBER, { source: "reward", obtainedVia: "discord_member", permanence: "conditional", state: "eligible" });
  const unlisted = owned("00000000-0000-4000-8000-000000000999", { source: "reward", obtainedVia: "discord_booster", permanence: "conditional" });
  const bought = owned(BOUGHT, { obtainedVia: "purchase", permanence: "permanent" });
  const inv = inventory([bought, member, unlisted]);
  const cards = joinRewards(catalog(), rewards([status(MEMBER, "eligible")]), inv);
  assert.deepEqual(
    ownItems(inv, cards).map((i) => i.cosmeticId),
    [BOUGHT, unlisted.cosmeticId],
    "a conditional cape the catalog doesn't list is never hidden",
  );
  assert.deepEqual(ownItems(inv, []).length, 3, "without the group, everything stays");
  // A giveaway of a Discord reward cape is owned for good: in My Capes, and its card says so.
  const won = owned(MEMBER, { obtainedVia: "giveaway", permanence: "permanent" });
  const withWon = joinRewards(catalog(), rewards([status(MEMBER, "owned")]), inventory([won]));
  assert.deepEqual(ownItems(inventory([won]), withWon).map((i) => i.cosmeticId), [MEMBER]);
  assert.equal(withWon[0].conditional, false);
});

test("where an item came from, in words; a reward is never read as owned for good", () => {
  const via = (obtainedVia: string | null | undefined) => obtainedLabel({ obtainedVia });
  assert.equal(via("purchase"), "store.obtained.purchase");
  assert.equal(via("admin_gift"), "store.obtained.adminGift");
  assert.equal(via("giveaway"), "store.obtained.giveaway");
  assert.equal(via("event"), "store.obtained.event");
  assert.equal(via("first_launch"), "store.obtained.firstLaunch");
  assert.equal(via("discord_member"), "store.obtained.discordMember");
  assert.equal(via("discord_booster"), "store.obtained.discordBooster");
  assert.equal(via("creator"), "store.obtained.creator");
  assert.equal(via("test"), null, "the Test badge already says so");
  assert.equal(via(undefined), null, "an older Store");
  assert.equal(via("something_new"), null);
  assert.equal(isConditional({ permanence: "conditional" }), true);
  assert.equal(isConditional({ source: "reward", permanence: null }), true);
  assert.equal(isConditional({ permanence: "permanent", source: "purchase" }), false);
});

test("Check again: offered once Discord is linked, and counts down to the Store's next allowed time", () => {
  assert.equal(canRecheck(null), false);
  assert.equal(canRecheck(rewards([], { discord: standing({ linked: false, state: "disconnected" }) })), false);
  assert.equal(canRecheck(rewards([])), true);
  assert.equal(canRecheck(rewards([], { state: "off" })), false);
  assert.equal(recheckWait(null, 5_000), 0);
  assert.equal(recheckWait(65_000, 5_000), 60);
  assert.equal(recheckWait(5_400, 5_000), 1, "a part second counts as one");
  assert.equal(recheckWait(4_000, 5_000), 0);
});

test("a Discord outage is said once over the group: paused after the grace period, kept within it", () => {
  assert.equal(discordNotice(null), null);
  assert.equal(discordNotice(rewards([])), null);
  assert.equal(discordNotice(rewards([], { discord: standing({ state: "unavailable" }) }))?.title, "store.rewards.outage.title");
  assert.equal(discordNotice(rewards([], { discord: standing({ state: "grace" }) }))?.title, "store.rewards.grace.title");
  assert.equal(discordNotice(rewards([], { state: "unavailable", discord: standing({ state: "unavailable" }) })), null, "not from an answer that didn't come");
});

test("a listed reward's picture comes from the catalog, never as an owned item", () => {
  const item = rewardItem(reward(MEMBER, "discord_member", "conditional"));
  assert.deepEqual([item.listed, item.cosmeticId, item.kind, item.asset?.id], [true, MEMBER, "cape", `a-${MEMBER}`]);
});
