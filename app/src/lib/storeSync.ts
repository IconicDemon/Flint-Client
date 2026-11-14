/**
 * The Flint Store from the launcher (src-tauri/src/store): the shapes the Store page gets from Rust, and the small rules it
 * follows to stay current. The Store decides what an account owns; everything here is display only, never changed locally.
 *
 * Merit is Flint's one balance: the Store's wallet, which the game's Rewards page shows too (Backend migration 5100).
 */
import type { CapeAnimation } from "./capeAnimation.ts";
import { STORE_SLOTS, slotOf, type StoreSlot } from "./cosmeticTypes.ts";
import { number } from "./format.ts";
import { formatLocale, t } from "./i18n.ts";

// The kinds, slots and their names live in lib/cosmeticTypes.ts (one place, so a Store rename is a one-line change).
export { SLOT_LABELS, STORE_SLOTS, type StoreSlot } from "./cosmeticTypes.ts";

/** One file of a 3D cosmetic, as Rust shows it (its address and hash stay in Rust; `store_model_file` gets it, checked). */
export type ModelFileInfo = { bytes?: number | null; width?: number | null; height?: number | null };

/** A 3D cosmetic's files (cosmetics v2: wings, headwear… emotes): which exist, its texture's frames and where it attaches. */
export type StoreModel = {
  geometry?: ModelFileInfo | null;
  texture?: ModelFileInfo | null;
  animation?: ModelFileInfo | null;
  thumbnail?: ModelFileInfo | null;
  textureFrames?: number | null;
  frameMs?: number[];
  attach?: { anchor: string; side?: "left" | "right" | null } | null;
  hidesCape?: boolean;
};

/**
 * `animation`: an animated cape's frames and timing (lib/capeAnimation.ts); none for a static cape. `model`: a 3D cosmetic's
 * files. `hasTexture`: false when the item has only its model's files (no flat texture for `store_asset`).
 */
export type StoreAsset = {
  id: string;
  version: number;
  width?: number | null;
  height?: number | null;
  animation?: CapeAnimation | null;
  model?: StoreModel | null;
  hasTexture?: boolean;
};

export type StoreItem = {
  cosmeticId: string;
  slug: string;
  /** The Store's kind (lib/cosmeticTypes.ts `CosmeticKind`), or one a newer Store sends that Flint doesn't know yet. */
  kind: string;
  /** The slot it is worn in, as Rust read it; null for an emote or an unknown kind. Use `slotOf(item)`. */
  slot?: StoreSlot | null;
  name: string;
  source?: string | null;
  test: boolean;
  gift: boolean;
  giftFrom?: string | null;
  giftMessage?: string | null;
  grantedAt?: string | null;
  asset?: StoreAsset | null;
  /**
   * How the account got it (Store contract v1 additions; none from an older Store): `purchase`, `admin_gift`, `giveaway`,
   * `event`, `first_launch`, `creator`, `test`, `discord_member`, `discord_booster`. Never the catalog's `origin`.
   */
  obtainedVia?: string | null;
  /** `conditional`: counts only while its requirement holds (a Discord reward). Rust reads every reward as conditional. */
  permanence?: "permanent" | "conditional" | null;
  /** `unavailable`: conditional, and Discord can't be checked right now (still usable during the grace period). */
  state?: "owned" | "eligible" | "unavailable" | null;
  /** Launcher only: a reward listed in the community catalog, not owned; its art comes from the catalog (lib/community.ts). */
  listed?: boolean;
  /** Cosmetics v2 catalog fields, when the Store sends them. `rarity` isn't in the v2 contract yet: sorted by only if sent. */
  priceCoins?: number | null;
  directPriceMinor?: number | null;
  directCurrency?: string | null;
  rarity?: string | null;
};

export type StoreInventory = {
  version: number;
  name: string;
  /** Merit. */
  balance: number | null;
  owned: StoreItem[];
  equipped: Partial<Record<StoreSlot, string>>;
  /** The emote wheel: 8 places, each an owned emote's id or null. Absent from a Store without emotes. */
  emotes?: (string | null)[] | null;
};

/** One account's Store page, as `store_inventory` answers it. */
export type StoreView = {
  uuid: string;
  storeUrl: string;
  signedIn: boolean;
  /** The system refused to read the saved Store sign-in: an explicit retry may ask again. */
  locked: boolean;
  name?: string | null;
  sessionExpiresAt?: string | null;
  inventory?: StoreInventory | null;
  /** The Store couldn't be reached: the inventory is its last answer, for display only. */
  stale: boolean;
  fetchedAt?: number | null;
  problem?: string | null;
};

export type StoreChanges = { state: "ok"; changed: boolean; version: number; pollAfterSeconds: number } | { state: "offline" } | { state: "signedOut" };

export type StoreWallet = { balance: number | null; ledger: { delta: number | null; balanceAfter: number | null; reason?: string | null; createdAt?: string | null }[] };

export type StoreOrder = {
  id: string;
  kind?: string | null;
  status?: string | null;
  paymentMethod?: string | null;
  testMode: boolean;
  item?: string | null;
  coins?: number | null;
  amountMinor?: number | null;
  currency?: string | null;
  createdAt?: string | null;
  gift: boolean;
  recipientName?: string | null;
  /** The checkout: the lines of one cart share it. */
  reference?: string | null;
};

export type StoreInfo = { storeUrl: string; contractVersion: number; production: boolean };

/** Every Store answer the launcher gets, wherever it was asked for (lib/api.ts passes them here). */
export type StoreFeedEvent = { kind: "view"; view: StoreView } | { kind: "signedOut"; uuid: string };

/**
 * The Store's answers, for whoever shows them outside the page that asked: the previews' cape (lib/displayedCape.ts) hears of
 * an equip, an unequip or a sign-out on the Store page the moment the Store answers it.
 */
export const storeFeed = (() => {
  const listeners = new Set<(event: StoreFeedEvent) => void>();
  return {
    publish(event: StoreFeedEvent) {
      for (const listener of [...listeners]) {
        try {
          listener(event);
        } catch (e) {
          console.error("[flint] a Store answer's listener failed", e);
        }
      }
    },
    listen(listener: (event: StoreFeedEvent) => void): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
})();

/** The Store asks for 25 s between change checks; offline, Flint backs off to at most 5 minutes. */
export const POLL_DEFAULT_MS = 25_000;
export const POLL_MIN_MS = 10_000;
export const POLL_MAX_MS = 300_000;
/** A window coming to the front checks at once, but not more often than this. */
export const FOCUS_CHECK_GAP_MS = 5_000;

/** How long to wait before the next change check, or null to stop (signed out). */
export function nextPollDelay(previousMs: number, result: StoreChanges): number | null {
  if (result.state === "signedOut") return null;
  if (result.state === "offline") return Math.min(Math.max(previousMs, POLL_DEFAULT_MS) * 2, POLL_MAX_MS);
  return Math.min(Math.max(result.pollAfterSeconds * 1000, POLL_MIN_MS), POLL_MAX_MS);
}

/** Whether a change check calls for the inventory again: a change, or the Store answering again after being unreachable. */
export function needsRefresh(result: StoreChanges, shownStale: boolean): boolean {
  return result.state === "signedOut" || (result.state === "ok" && (result.changed || shownStale)) || (result.state === "offline" && !shownStale);
}

const CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/**
 * A browser sign-in code as the Store writes it (`GXV9-MC1G`), from what the player typed: case, spaces and dashes don't
 * matter, and O, I and L (never in the Store's codes) are read as the digits they look like. Null when it can't be one.
 * The same rule as `normalize_code` in src-tauri/src/store/mod.rs.
 */
export function normalizeStoreCode(input: string): string | null {
  const chars = [...input.toUpperCase()]
    .filter((c) => c !== "-" && !/\s/.test(c))
    .map((c) => (c === "O" ? "0" : c === "I" || c === "L" ? "1" : c));
  if (chars.length !== 8 || !chars.every((c) => CODE_ALPHABET.includes(c))) return null;
  return `${chars.slice(0, 4).join("")}-${chars.slice(4).join("")}`;
}

/** Flint's form of a Minecraft UUID: 32 lowercase hex digits. */
export const canonicalUuid = (uuid: string) => uuid.replace(/-/g, "").toLowerCase();

/**
 * Whether `next` is an older inventory than the one `shown` (the Store's version only grows: purchases, gifts, equips). Such
 * an answer was on its way from before (a change check's reload overtaken by an equip) and is dropped, so a cape never goes
 * back for a moment. A signed-out or offline answer is never older: it says something else.
 */
export function isOlderView(next: StoreView, shown: StoreView | null | undefined): boolean {
  const a = next.signedIn && !next.stale ? next.inventory : null;
  const b = shown?.signedIn ? shown.inventory : null;
  return !!a && !!b && canonicalUuid(next.uuid) === canonicalUuid(shown!.uuid) && a.version < b.version;
}

/** An answer counts only for the account it was asked for (an account switch can overtake a slow answer). */
export function viewFor(uuid: string, view: StoreView | null | undefined): StoreView | null {
  return view && canonicalUuid(view.uuid) === canonicalUuid(uuid) ? view : null;
}

/** The amount alone, grouped as `meritText` writes it (`1,250`); a dash when it isn't known. */
export function formatStoreMerit(balance: number | null | undefined): string {
  return typeof balance === "number" && Number.isFinite(balance) ? number(balance) : "—";
}

/** `1250` → `1,250 Merits`, `1` → `1 Merit`; null when the balance isn't known (never shown as zero). */
export function meritText(balance: number | null | undefined): string | null {
  return typeof balance === "number" && Number.isFinite(balance) ? t("store.merit.amount", { count: balance }) : null;
}

/** `https://store.flintclient.com` → `store.flintclient.com`. */
export function storeHost(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

export function isWorn(inventory: StoreInventory, item: StoreItem): boolean {
  const slot = slotOf(item);
  return !!slot && inventory.equipped[slot] === item.cosmeticId;
}

/** The slots this inventory has something for, in the Store's order. */
export function slotsWithItems(inventory: StoreInventory): StoreSlot[] {
  return STORE_SLOTS.filter((slot) => inventory.owned.some((o) => slotOf(o) === slot));
}

/** "Updated just now", "Updated 4 min ago": how old the Store's last answer is. */
export function updatedText(fetchedAt: number | null | undefined, now = Date.now()): string {
  if (!fetchedAt) return t("store.updated.never");
  const seconds = Math.max(0, (now - fetchedAt) / 1000);
  if (seconds < 60) return t("store.updated.justNow");
  if (seconds < 3600) return t("store.updated.minutes", { count: Math.round(seconds / 60) });
  if (seconds < 86_400) return t("store.updated.hours", { count: Math.round(seconds / 3600) });
  return t("store.updated.days", { count: Math.round(seconds / 86_400) });
}

/** A Store reason or status code (`test_grant`, `refunded`) as words; the Store's codes are English and stay so. */
export function reasonText(code: string | null | undefined): string {
  if (!code) return "";
  const words = code.replace(/[_-]+/g, " ").trim();
  return words ? words[0].toUpperCase() + words.slice(1) : "";
}

/** `499, USD` → `$4.99`; nothing when the Store sent no amount. */
export function moneyText(amountMinor: number | null | undefined, currency: string | null | undefined): string {
  if (typeof amountMinor !== "number" || !currency) return "";
  try {
    return new Intl.NumberFormat(formatLocale(), { style: "currency", currency }).format(amountMinor / 100);
  } catch {
    return `${(amountMinor / 100).toFixed(2)} ${currency}`;
  }
}
