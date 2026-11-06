/**
 * Discord connected accounts and community rewards (src-tauri/src/store/community.rs; the Store's contract
 * docs/connections-rewards-api-v1.md): the shapes Rust answers with, and the rules Settings → Connected Accounts and the
 * Store page's Community Rewards follow, apart from React so they can be tested.
 *
 * Everything here is display. The Store decides what an account has earned; Flint never sends anything about Discord
 * (linking is Discord's own page in the system browser) and never presents a conditional reward as owned for good.
 * A Store without these routes answers `off`, and then nothing here shows at all.
 */
import type { MessageKey } from "./i18n.ts";
import { canonicalUuid, type StoreAsset, type StoreInventory, type StoreItem, type StoreSlot } from "./storeSync.ts";

/** `ok`: the Store answered. `off`: this Store doesn't have the feature (show nothing). `signedOut`: no Store session.
 * `unavailable`: the Store couldn't answer now (keep what was shown). */
export type Avail = "ok" | "off" | "signedOut" | "unavailable";

// ---------- Settings → Connected Accounts ----------

export type GuildState = "checking" | "verified" | "pending" | "unavailable";

export type DiscordLink = {
  username: string;
  displayName?: string | null;
  /** Only ever a `cdn.discordapp.com` address (checked in Rust); none shows a default avatar. */
  avatarUrl?: string | null;
  linkedAt?: string | null;
  guild: { state: GuildState; member?: boolean | null; booster?: boolean | null; checkedAt?: string | null; verifiedAt?: string | null };
};

export type ConnectionsView = { uuid: string; state: Avail; canLink: boolean; discord: DiscordLink | null; problem?: string | null };

export type LinkStart = { state: "started"; attemptId: string; expiresAt?: string | null; opened: boolean } | { state: "off" };

export type AttemptState = "waiting" | "linked" | "cancelled" | "denied" | "expired" | "already_linked_elsewhere" | "error";
export type AttemptView = { state: AttemptState; expiresAt?: string | null };

/** An answer counts only for the account it was asked for (an account switch can overtake a slow answer). */
export function forAccount<T extends { uuid: string }>(uuid: string, answer: T | null | undefined): T | null {
  return answer && canonicalUuid(answer.uuid) === canonicalUuid(uuid) ? answer : null;
}

/**
 * Whether Settings shows Connected Accounts at all: only when the Store has the feature and there is something to do
 * (linking is on) or to see (a link, which stays so it can be removed even with linking switched off).
 */
export function showConnections(view: ConnectionsView | null): boolean {
  return !!view && view.state === "ok" && (view.canLink || !!view.discord);
}

/**
 * Signed out of the Store, Settings offers "Sign in to the Flint Store" on the Connected accounts row only when the Store
 * has the feature on. Without a session the public catalog is the only signal: community rewards on (`enabled`) or Discord
 * rewards on (`discord_enabled`). A Store without the catalog (a 404, `off`), with both off, or not answering: no row.
 */
export function offerSignedOut(catalog: Pick<CommunityCatalog, "state" | "enabled" | "discordEnabled"> | null | undefined): boolean {
  return !!catalog && catalog.state === "ok" && (catalog.enabled || catalog.discordEnabled);
}

export type Tone = "neutral" | "accent" | "success" | "danger" | "warning";

/** How Flint's server sees the linked account, in words. */
export function linkStatus(link: DiscordLink): { label: MessageKey; tone: Tone; checking: boolean } {
  switch (link.guild.state) {
    case "checking":
      return { label: "connections.discord.checking", tone: "neutral", checking: true };
    case "unavailable":
      return { label: "connections.discord.cantCheck", tone: "warning", checking: false };
    case "pending":
      return { label: "connections.discord.screening", tone: "warning", checking: false };
    default:
      if (link.guild.booster) return { label: "connections.discord.booster", tone: "success", checking: false };
      if (link.guild.member) return { label: "connections.discord.member", tone: "success", checking: false };
      if (link.guild.member === false) return { label: "connections.discord.notMember", tone: "neutral", checking: false };
      return { label: "connections.discord.checking", tone: "neutral", checking: true };
  }
}

// ---------- the link attempt ----------

/** The Store asks for a poll every 2 s while the dialog is open, for at most 10 minutes. */
export const ATTEMPT_POLL_MS = 2_000;
export const ATTEMPT_MAX_MS = 10 * 60_000;

/** Whether to ask about the attempt again: only while it is waiting, and within its 10 minutes. */
export function pollAgain(state: AttemptState | null, startedAt: number, now: number): boolean {
  return (state === null || state === "waiting") && now - startedAt < ATTEMPT_MAX_MS;
}

/** What the player reads when an attempt ends without a link; null for waiting and linked. Every one offers Try again. */
export function attemptProblem(state: AttemptState): { title: MessageKey; body: MessageKey } | null {
  switch (state) {
    case "denied":
      return { title: "connections.link.denied.title", body: "connections.link.denied.body" };
    case "cancelled":
      return { title: "connections.link.cancelled.title", body: "connections.link.cancelled.body" };
    case "expired":
      return { title: "connections.link.expired.title", body: "connections.link.expired.body" };
    case "already_linked_elsewhere":
      return { title: "connections.link.elsewhere.title", body: "connections.link.elsewhere.body" };
    case "error":
      return { title: "connections.link.error.title", body: "connections.link.error.body" };
    default:
      return null;
  }
}

// ---------- rewards ----------

export type StandingState = "disconnected" | "checking" | "verified" | "grace" | "unavailable";
export type Standing = { linked: boolean; member: boolean; booster: boolean; pending: boolean; state: StandingState };
export type RewardStatusKind = "owned" | "eligible" | "checking" | "unavailable" | "locked";
export type ActionKind = "connect_discord" | "join_discord" | "complete_screening" | "boost_discord" | "launch" | "none";
export type RewardStatus = {
  cosmeticId: string;
  requirement: string;
  permanence: "permanent" | "conditional";
  status: RewardStatusKind;
  /** `url`: a Discord invite Rust accepted, for join, screening and boost. */
  action: { kind: ActionKind; url?: string | null };
};
export type RewardsView = { uuid: string; state: Avail; discord: Standing | null; rewards: RewardStatus[]; problem?: string | null };
export type Recheck = { state: "ok"; connections: ConnectionsView; rewards: RewardsView } | { state: "limited"; nextAllowedAt: number } | { state: "off" };

export type CommunityReward = {
  cosmeticId: string;
  slug: string;
  kind: StoreSlot;
  name: string;
  description?: string | null;
  /** `label`: the Store's own words for what earns it, shown as they are. */
  requirement: { type: string; label: string; description?: string | null };
  permanence: "permanent" | "conditional";
  startsAt?: string | null;
  endsAt?: string | null;
  sort: number;
  asset: StoreAsset;
  animated: boolean;
  hd: boolean;
};
export type CommunityCatalog = { state: Avail; enabled: boolean; discordEnabled: boolean; rewards: CommunityReward[]; fetchedAt?: number | null };

/** A conditional item counts only while its condition holds (a Discord reward); never presented as owned for good. */
export function isConditional(item: Pick<StoreItem, "permanence" | "source">): boolean {
  return item.permanence === "conditional" || item.source === "reward";
}

const OBTAINED: Record<string, MessageKey> = {
  purchase: "store.obtained.purchase",
  admin_gift: "store.obtained.adminGift",
  giveaway: "store.obtained.giveaway",
  event: "store.obtained.event",
  first_launch: "store.obtained.firstLaunch",
  discord_member: "store.obtained.discordMember",
  discord_booster: "store.obtained.discordBooster",
  creator: "store.obtained.creator",
};

/**
 * Where an owned item came from, in words, or null: nothing known (an older Store), or a test grant (the tile's Test badge
 * already says so). The catalog's `origin` (who made the cape) is never this.
 */
export function obtainedLabel(item: Pick<StoreItem, "obtainedVia">): MessageKey | null {
  return (item.obtainedVia && OBTAINED[item.obtainedVia]) || null;
}

/** A listed reward as an item the Store page can picture (its art comes from the community catalog, checked in Rust). */
export function rewardItem(reward: CommunityReward): StoreItem {
  return { cosmeticId: reward.cosmeticId, slug: reward.slug, kind: reward.kind, name: reward.name, test: false, gift: false, asset: reward.asset, listed: true };
}

export type RewardCard = {
  reward: CommunityReward;
  status: RewardStatusKind;
  action: { kind: ActionKind; url: string | null };
  /** The account's inventory item for it (owned, or earned now): what the Store lets it wear. */
  item: StoreItem | null;
  worn: boolean;
  /** Counts only while its requirement holds. False once it is owned for good. */
  conditional: boolean;
};

/**
 * The Community Rewards group: the catalog's rewards in its order, each joined by `cosmeticId` with the account's standing
 * (`/me/rewards`) and inventory.
 * - Without the catalog, or with community rewards off at the Store: nothing (no group at all).
 * - With Discord rewards off, Discord rewards are left out even if a stale answer lists them.
 * - Without a standing for a reward (not answered yet, or failed): locked with nothing to do, unless the inventory lists it.
 * - Wearable only when the inventory lists the item: the inventory is what the Store equips.
 */
export function joinRewards(catalog: CommunityCatalog | null, rewards: RewardsView | null, inventory: StoreInventory | null): RewardCard[] {
  if (!catalog || catalog.state !== "ok" || !catalog.enabled) return [];
  const standing = new Map((rewards?.state === "ok" ? rewards.rewards : []).map((r) => [r.cosmeticId, r]));
  const items = new Map((inventory?.owned ?? []).map((i) => [i.cosmeticId, i]));
  return catalog.rewards
    .filter((reward) => catalog.discordEnabled || !reward.requirement.type.startsWith("discord_"))
    .map((reward) => {
      const said = standing.get(reward.cosmeticId);
      const item = items.get(reward.cosmeticId) ?? null;
      const status: RewardStatusKind = said?.status ?? (item ? (isConditional(item) ? "eligible" : "owned") : "locked");
      const action = status === "locked" && said ? { kind: said.action.kind, url: said.action.url ?? null } : { kind: "none" as const, url: null };
      const permanent = status === "owned" || (!!item && !isConditional(item));
      return {
        reward,
        status,
        action,
        item,
        worn: !!item && inventory?.equipped[reward.kind] === reward.cosmeticId,
        conditional: !permanent && (reward.permanence !== "permanent" || (!!item && isConditional(item))),
      };
    });
}

/**
 * The account's own items for "My Capes" (and the other slots): everything it owns, less the conditional items the
 * Community Rewards group already shows (they belong there, with their requirement). A conditional item the catalog doesn't
 * list stays here, marked by where it came from, so it is never hidden.
 */
export function ownItems(inventory: StoreInventory, cards: readonly RewardCard[]): StoreItem[] {
  const shown = new Set(cards.filter((c) => c.item && c.conditional).map((c) => c.reward.cosmeticId));
  return inventory.owned.filter((item) => !(isConditional(item) && shown.has(item.cosmeticId)));
}

export type CardState = { label: MessageKey; tone: Tone; busy: boolean; wearable: boolean; note: MessageKey | null };

/** A card's state line, whether it can be worn now, and what to say about how long it lasts. */
export function cardState(card: RewardCard): CardState {
  const grace = card.item?.state === "unavailable";
  switch (card.status) {
    case "owned":
      return { label: "store.rewards.status.owned", tone: "success", busy: false, wearable: !!card.item, note: "store.rewards.note.permanent" };
    case "eligible":
      return {
        label: "store.rewards.status.eligible",
        tone: "success",
        busy: false,
        wearable: !!card.item,
        note: grace ? "store.rewards.note.grace" : card.conditional ? "store.rewards.note.conditional" : "store.rewards.note.permanent",
      };
    case "checking":
      return { label: "store.rewards.status.checking", tone: "neutral", busy: true, wearable: false, note: null };
    case "unavailable":
      return { label: "store.rewards.status.unavailable", tone: "warning", busy: false, wearable: false, note: "store.rewards.note.unavailable" };
    default:
      return { label: "store.rewards.status.locked", tone: "neutral", busy: false, wearable: false, note: card.action.kind === "launch" ? "store.rewards.note.launch" : null };
  }
}

export type CardAction = { kind: "connect"; label: MessageKey } | { kind: "invite"; url: string; label: MessageKey } | null;

/**
 * The button a locked card offers, from the Store's `action.kind`: Connect Discord (opens Connected Accounts), or a
 * Discord invite (join, finish the server's screening, boost) opened in the system browser. None for `launch` (it unlocks
 * at the next sign-in; the card says so) and `none`, and none for an invite Rust didn't accept.
 */
export function cardAction(card: RewardCard): CardAction {
  if (card.status !== "locked") return null;
  const { kind, url } = card.action;
  if (kind === "connect_discord") return { kind: "connect", label: "store.rewards.action.connect" };
  if (!url) return null;
  if (kind === "join_discord") return { kind: "invite", url, label: "store.rewards.action.join" };
  if (kind === "complete_screening") return { kind: "invite", url, label: "store.rewards.action.screening" };
  if (kind === "boost_discord") return { kind: "invite", url, label: "store.rewards.action.boost" };
  return null;
}

/** "Check again" is worth offering once Discord is linked (it checks membership and boosting now). */
export function canRecheck(rewards: RewardsView | null): boolean {
  return rewards?.state === "ok" && !!rewards.discord?.linked;
}

/** Whole seconds until "Check again" may run (0: now). */
export function recheckWait(nextAllowedAt: number | null, now: number): number {
  return nextAllowedAt ? Math.max(0, Math.ceil((nextAllowedAt - now) / 1000)) : 0;
}

/** A notice over the group when Discord can't be checked: past the grace period (rewards paused) or within it. */
export function discordNotice(rewards: RewardsView | null): { title: MessageKey; body: MessageKey; tone: "warn" | "info" } | null {
  const state = rewards?.state === "ok" ? rewards.discord?.state : null;
  if (state === "unavailable") return { title: "store.rewards.outage.title", body: "store.rewards.outage.body", tone: "warn" };
  if (state === "grace") return { title: "store.rewards.grace.title", body: "store.rewards.grace.body", tone: "info" };
  return null;
}
