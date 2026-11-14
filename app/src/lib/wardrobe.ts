/**
 * The wardrobe's rules, apart from React so they can be tested: what each category holds and wears, the grid's search,
 * order and filters, the emote wheel, and outfits (saved combinations, kept on this computer per account). Everything here
 * is display: the Store decides what an account owns and wears (lib/storeSync.ts, src-tauri/src/store). The kinds, slots
 * and categories are named in lib/cosmeticTypes.ts.
 */
import { isPlayable } from "./capeAnimation.ts";
import { CATEGORIES, EMOTE_SLOTS, STORE_SLOTS, categoryOf, kindOf, slotOf, type CategoryId, type StoreSlot } from "./cosmeticTypes.ts";
import { searchable, type MessageKey } from "./i18n.ts";
import type { StoreInventory, StoreItem } from "./storeSync.ts";

// ---------- categories ----------

export type CategoryInfo = { count: number; equipped: boolean };

/** Each store category's item count and whether something in it is worn (emotes: on the wheel). */
export function categoryInfo(inventory: StoreInventory | null | undefined, listed: readonly StoreItem[] = []): Record<CategoryId, CategoryInfo> {
  const info = Object.fromEntries(CATEGORIES.map((c) => [c.id, { count: 0, equipped: false }])) as Record<CategoryId, CategoryInfo>;
  const owned = inventory?.owned ?? [];
  const seen = new Set<string>();
  for (const item of owned) {
    seen.add(item.cosmeticId);
    const at = info[categoryOf(item)];
    at.count++;
    const slot = slotOf(item);
    if (slot && inventory?.equipped[slot] === item.cosmeticId) at.equipped = true;
  }
  for (const item of listed) if (!seen.has(item.cosmeticId)) info[categoryOf(item)].count++;
  // The wheel counts once it holds an emote the account owns.
  if (inventory?.emotes?.some((id) => !!id && owned.some((o) => o.cosmeticId === id && kindOf(o.kind) === "emote"))) info.emotes.equipped = true;
  return info;
}

/** The categories the sidebar shows: every one but "More", which shows only when a newer Store sent a kind Flint doesn't know. */
export function shownCategories(info: Record<CategoryId, CategoryInfo>): CategoryId[] {
  return CATEGORIES.map((c) => c.id).filter((id) => id !== "more" || info.more.count > 0);
}

// ---------- the grid ----------

export type WardrobeSort = "newest" | "name" | "equipped";
export const WARDROBE_SORTS: { value: WardrobeSort; label: MessageKey }[] = [
  { value: "newest", label: "store.inventory.sort.newest" },
  { value: "name", label: "store.inventory.sort.name" },
  { value: "equipped", label: "wardrobe.sort.equipped" },
];

/** Which items show: all, the account's own, or the locked ones (Community Rewards not earned yet); and only animated. */
export type WardrobeFilter = { show: "all" | "owned" | "locked"; animated: boolean };
export const NO_FILTER: WardrobeFilter = { show: "all", animated: false };
export const filterCount = (f: WardrobeFilter) => (f.show !== "all" ? 1 : 0) + (f.animated ? 1 : 0);

/** Animated: an animated cape, a texture strip of frames, or a model with its own animation. */
export function isAnimated(item: StoreItem): boolean {
  const model = item.asset?.model;
  return isPlayable(item.asset?.animation) || (model?.textureFrames ?? 0) > 1 || (!!model?.animation && kindOf(item.kind) !== "emote");
}

const grantedTime = (item: StoreItem) => {
  const time = item.grantedAt ? Date.parse(item.grantedAt) : NaN;
  return Number.isNaN(time) ? 0 : time;
};
const byName = (a: StoreItem, b: StoreItem) => a.name.localeCompare(b.name, undefined, { sensitivity: "base", numeric: true }) || a.cosmeticId.localeCompare(b.cosmeticId);

/**
 * A category's items (owned, then any listed but not owned: `listed`) matching `query` (name, case and accents ignored)
 * and `filter`, in `sort` order. Ties fall back to the name.
 */
export function wardrobeList(
  items: readonly StoreItem[],
  { category, query, sort, filter, equipped = {} }: { category: CategoryId; query: string; sort: WardrobeSort; filter: WardrobeFilter; equipped?: StoreInventory["equipped"] },
): StoreItem[] {
  const needle = searchable(query.trim());
  const list = items.filter(
    (item) =>
      categoryOf(item) === category &&
      (!needle || searchable(item.name).includes(needle)) &&
      (filter.show === "all" || (filter.show === "locked") === !!item.listed) &&
      (!filter.animated || isAnimated(item)),
  );
  if (sort === "name") return list.sort(byName);
  const newest = (a: StoreItem, b: StoreItem) => grantedTime(b) - grantedTime(a) || byName(a, b);
  if (sort === "equipped") {
    const worn = new Set(Object.values(equipped));
    return list.sort((a, b) => Number(worn.has(b.cosmeticId)) - Number(worn.has(a.cosmeticId)) || newest(a, b));
  }
  return list.sort(newest);
}

/** What an item's slot wears in `inventory` (null for an emote or an unknown kind). */
export function isEquipped(inventory: StoreInventory | null | undefined, item: StoreItem): boolean {
  const slot = slotOf(item);
  if (slot) return inventory?.equipped[slot] === item.cosmeticId;
  return kindOf(item.kind) === "emote" && !!inventory?.emotes?.includes(item.cosmeticId);
}

/** How an item came to the account, as an icon on its card: a Discord reward, a gift, or nothing to say. */
export type ItemSource = "discord" | "gift" | "creator" | "event" | null;
export function itemSource(item: StoreItem): ItemSource {
  const via = item.obtainedVia ?? "";
  if (via.startsWith("discord_") || item.source === "reward") return "discord";
  if (item.gift || via === "admin_gift") return "gift";
  if (via === "creator") return "creator";
  if (via === "event" || via === "giveaway" || via === "first_launch") return "event";
  return null;
}

// ---------- the emote wheel ----------

/** The wheel as eight places (a Store without emotes: eight empty ones). */
export function wheelOf(inventory: StoreInventory | null | undefined): (string | null)[] {
  const wheel = [...(inventory?.emotes ?? [])].slice(0, EMOTE_SLOTS);
  while (wheel.length < EMOTE_SLOTS) wheel.push(null);
  return wheel;
}

/** The wheel with `id` in `place` (null empties it). */
export function wheelWith(wheel: readonly (string | null)[], place: number, id: string | null): (string | null)[] {
  const next = wheelOf({ emotes: [...wheel] } as StoreInventory);
  if (place >= 0 && place < EMOTE_SLOTS) next[place] = id;
  return next;
}

/** The first empty place, else the selected one. */
export const freePlace = (wheel: readonly (string | null)[], selected: number) => {
  const free = wheel.indexOf(null);
  return free >= 0 ? free : selected;
};

// ---------- outfits ----------

/** A saved combination: what each slot wears (null: nothing) and, optionally, the emote wheel. */
export type Outfit = { id: string; name: string; createdAt: number; slots: Partial<Record<StoreSlot, string | null>>; emotes?: (string | null)[] };

export const MAX_OUTFITS = 24;
export const MAX_OUTFIT_NAME = 32;

/** What the account wears now, as an outfit's slots (every slot named, null when empty). */
export function wornSlots(inventory: StoreInventory): Record<StoreSlot, string | null> {
  return Object.fromEntries(STORE_SLOTS.map((slot) => [slot, inventory.equipped[slot] ?? null])) as Record<StoreSlot, string | null>;
}

/**
 * Putting an outfit on: the slot changes to ask the Store for (nothing for a slot that already matches), and the outfit's
 * items the account no longer owns (their slots are left as they are).
 */
export function outfitPlan(outfit: Outfit, inventory: StoreInventory): { changes: { slot: StoreSlot; cosmeticId: string | null }[]; missing: number } {
  const owned = new Map(inventory.owned.map((o) => [o.cosmeticId, o]));
  const changes: { slot: StoreSlot; cosmeticId: string | null }[] = [];
  let missing = 0;
  for (const slot of STORE_SLOTS) {
    if (!(slot in outfit.slots)) continue;
    const id = outfit.slots[slot] ?? null;
    if (id && (!owned.has(id) || slotOf(owned.get(id)!) !== slot)) {
      missing++;
      continue;
    }
    if ((inventory.equipped[slot] ?? null) !== id) changes.push({ slot, cosmeticId: id });
  }
  return { changes, missing };
}

/** The saved outfit the account is wearing right now, if any (every slot it names matches). */
export function wornOutfit(outfits: readonly Outfit[], inventory: StoreInventory | null | undefined): Outfit | null {
  if (!inventory) return null;
  return outfits.find((o) => STORE_SLOTS.every((slot) => !(slot in o.slots) || (o.slots[slot] ?? null) === (inventory.equipped[slot] ?? null))) ?? null;
}

/** The outfit's items, in slot order (for its card's pictures). */
export function outfitItems(outfit: Outfit, inventory: StoreInventory | null | undefined): StoreItem[] {
  const owned = new Map((inventory?.owned ?? []).map((o) => [o.cosmeticId, o]));
  return STORE_SLOTS.map((slot) => outfit.slots[slot]).flatMap((id) => (id && owned.has(id) ? [owned.get(id)!] : []));
}

/** A name for a new outfit: "Outfit 3", the first number not taken. */
export function nextOutfitName(outfits: readonly Outfit[], base: string): string {
  const taken = new Set(outfits.map((o) => o.name.trim().toLowerCase()));
  for (let n = outfits.length + 1; ; n++) if (!taken.has(`${base} ${n}`.toLowerCase())) return `${base} ${n}`;
}

/** Outfits as kept: checked, so a hand-edited or older copy can't break the page. */
export function readOutfits(raw: unknown): Outfit[] {
  if (!Array.isArray(raw)) return [];
  const out: Outfit[] = [];
  for (const o of raw) {
    if (!o || typeof o !== "object" || typeof o.id !== "string" || typeof o.name !== "string") continue;
    const slots: Partial<Record<StoreSlot, string | null>> = {};
    for (const slot of STORE_SLOTS) {
      const v = o.slots?.[slot];
      if (v === null || typeof v === "string") slots[slot] = v;
    }
    out.push({ id: o.id, name: o.name.slice(0, MAX_OUTFIT_NAME), createdAt: typeof o.createdAt === "number" ? o.createdAt : 0, slots });
    if (out.length >= MAX_OUTFITS) break;
  }
  return out;
}

const OUTFITS_KEY = (uuid: string) => `flint-wardrobe-outfits:${uuid}`;

export function loadOutfits(uuid: string): Outfit[] {
  try {
    return readOutfits(JSON.parse(localStorage.getItem(OUTFITS_KEY(uuid)) ?? "[]"));
  } catch {
    return [];
  }
}

export function saveOutfits(uuid: string, outfits: readonly Outfit[]): void {
  try {
    localStorage.setItem(OUTFITS_KEY(uuid), JSON.stringify(outfits.slice(0, MAX_OUTFITS)));
  } catch {
    // Storage can be full or unavailable; the outfits stay for this visit.
  }
}

// ---------- skin favourites (the skin changer) ----------

const FAVOURITES_KEY = (uuid: string) => `flint-skin-favourites:${uuid}`;

export function loadFavourites(uuid: string): Set<string> {
  try {
    const list = JSON.parse(localStorage.getItem(FAVOURITES_KEY(uuid)) ?? "[]");
    return new Set(Array.isArray(list) ? list.filter((x): x is string => typeof x === "string").slice(0, 200) : []);
  } catch {
    return new Set();
  }
}

export function saveFavourites(uuid: string, ids: ReadonlySet<string>): void {
  try {
    localStorage.setItem(FAVOURITES_KEY(uuid), JSON.stringify([...ids].slice(0, 200)));
  } catch {
    // As above.
  }
}

/** Saved skins with favourites first, then newest first. */
export function sortedSkins<T extends { id: string; addedAt: number }>(skins: readonly T[], favourites: ReadonlySet<string>): T[] {
  return [...skins].sort((a, b) => Number(favourites.has(b.id)) - Number(favourites.has(a.id)) || b.addedAt - a.addedAt);
}

/** What the skin changer's field holds: a Minecraft username, an https address, or neither (with the reason's key). */
export function skinQuery(text: string): { from: "username" | "url"; value: string } | { error: MessageKey } | null {
  const value = text.trim();
  if (!value) return null;
  if (/^https?:\/\//i.test(value)) {
    try {
      const url = new URL(value);
      if (url.protocol !== "https:") return { error: "wardrobe.skins.error.https" };
      return { from: "url", value: url.toString() };
    } catch {
      return { error: "wardrobe.skins.error.badUrl" };
    }
  }
  if (/^[A-Za-z0-9_]{3,16}$/.test(value)) return { from: "username", value };
  return { error: "wardrobe.skins.error.badName" };
}
