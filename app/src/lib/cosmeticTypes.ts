/**
 * Flint's cosmetic kinds, the slot each is worn in, and the wardrobe category it is shown under: the one place in the
 * interface that names them. The Store owns the names (cosmetics v2, FlintQA/release-104/cosmetics-contract-website.md,
 * its "Update 2": 8 kinds); a rename there is a one-line change here (and in src-tauri/src/store/kinds.rs,
 * Rust's copy of the same table).
 *
 * A kind Flint doesn't know yet (a newer Store) never breaks the wardrobe: it shows under "More", where it can be seen but
 * not worn from the launcher until Flint knows its slot.
 */
import type { IconName } from "../ui/Icon";
import type { MessageKey } from "./i18n.ts";

/** The Store's slots (`PUT /api/v1/me/equipped/`): one item worn in each. */
export type StoreSlot = "cape" | "back" | "head" | "aura" | "pet" | "shield" | "hands";
export const STORE_SLOTS: StoreSlot[] = ["cape", "back", "head", "aura", "pet", "shield", "hands"];

/** The Store's kinds (`item.kind`). `headwear` is shown as "Hats". */
export type CosmeticKind = "cape" | "wings" | "headwear" | "aura" | "pet" | "shield" | "gloves" | "emote";

/** Each kind's slot; an emote has none (it goes on the emote wheel). */
export const KIND_SLOT: Record<CosmeticKind, StoreSlot | null> = {
  cape: "cape",
  wings: "back",
  headwear: "head",
  aura: "aura",
  pet: "pet",
  shield: "shield",
  gloves: "hands",
  emote: null,
};

/** The emote wheel's places (`inventory.emotes`, `PUT /api/v1/me/emotes/`). */
export const EMOTE_SLOTS = 8;

/** Names an older Store used (the v2 migration renames them); Rust reads them the same way. */
const KIND_ALIASES: Record<string, CosmeticKind> = { back: "wings", head: "headwear" };

/** The kind as Flint names it, or null for one Flint doesn't know. */
export function kindOf(raw: string | null | undefined): CosmeticKind | null {
  if (!raw) return null;
  const kind = KIND_ALIASES[raw] ?? raw;
  return Object.prototype.hasOwnProperty.call(KIND_SLOT, kind) ? (kind as CosmeticKind) : null;
}

/** The slot an item is worn in: Rust's answer (`slot`) when it gave one, else its kind's; null when it isn't worn in one. */
export function slotOf(item: { kind: string; slot?: string | null }): StoreSlot | null {
  if (item.slot && (STORE_SLOTS as string[]).includes(item.slot)) return item.slot as StoreSlot;
  const kind = kindOf(item.kind);
  return kind ? KIND_SLOT[kind] : null;
}

/** Each slot's name (as a message key). */
export const SLOT_LABELS: Record<StoreSlot, MessageKey> = {
  cape: "store.slot.cape",
  back: "store.slot.back",
  head: "store.slot.head",
  aura: "store.slot.aura",
  pet: "wardrobe.slot.pet",
  shield: "wardrobe.slot.shield",
  hands: "wardrobe.slot.hands",
};

// ---------- the wardrobe's categories ----------

/** The wardrobe's left column, in order. `more` (kinds Flint doesn't know) shows only when it has something. */
export type CategoryId = "capes" | "wings" | "headwear" | "auras" | "pets" | "shields" | "gloves" | "emotes" | "bodywear" | "outfits" | "skins" | "badges" | "more";

export type Category = {
  id: CategoryId;
  label: MessageKey;
  icon: IconName;
  /** `main`: the Store's 8 kinds, at the top; `extra`: below a separator. */
  group: "main" | "extra";
  /** The kind it lists (store categories); none for outfits, the skin changer, badges and "More". */
  kind?: CosmeticKind;
  /** The empty grid's title ("No pets yet"). */
  empty?: MessageKey;
  /** Not in the Store yet: the category says "Coming soon" and nothing else (no count, no grid). */
  soon?: boolean;
};

export const CATEGORIES: Category[] = [
  { id: "capes", label: "wardrobe.cat.capes", icon: "flag", group: "main", kind: "cape", empty: "wardrobe.empty.capes" },
  { id: "wings", label: "wardrobe.cat.wings", icon: "wings", group: "main", kind: "wings", empty: "wardrobe.empty.wings" },
  { id: "headwear", label: "wardrobe.cat.headwear", icon: "crown", group: "main", kind: "headwear", empty: "wardrobe.empty.headwear" },
  { id: "auras", label: "wardrobe.cat.auras", icon: "sparkle", group: "main", kind: "aura", empty: "wardrobe.empty.auras" },
  { id: "pets", label: "wardrobe.cat.pets", icon: "paw", group: "main", kind: "pet", empty: "wardrobe.empty.pets" },
  { id: "shields", label: "wardrobe.cat.shields", icon: "shield", group: "main", kind: "shield", empty: "wardrobe.empty.shields" },
  { id: "gloves", label: "wardrobe.cat.gloves", icon: "glove", group: "main", kind: "gloves", empty: "wardrobe.empty.gloves" },
  { id: "emotes", label: "wardrobe.cat.emotes", icon: "smile", group: "main", kind: "emote", empty: "wardrobe.empty.emotes" },
  { id: "bodywear", label: "wardrobe.cat.bodywear", icon: "shirt", group: "main", soon: true },
  { id: "outfits", label: "wardrobe.cat.outfits", icon: "layers", group: "extra" },
  { id: "skins", label: "wardrobe.cat.skins", icon: "user", group: "extra" },
  { id: "badges", label: "wardrobe.cat.badges", icon: "badge", group: "extra" },
  { id: "more", label: "wardrobe.cat.more", icon: "box", group: "extra", empty: "wardrobe.empty.more" },
];

/** The category an item is shown under: its kind's, or "More" for a kind Flint doesn't know. */
export function categoryOf(item: { kind: string }): CategoryId {
  const kind = kindOf(item.kind);
  return CATEGORIES.find((c) => c.kind && c.kind === kind)?.id ?? "more";
}

export const categoryById = (id: CategoryId): Category => CATEGORIES.find((c) => c.id === id) ?? CATEGORIES[0];

/** A category's slot (where its items are worn), or null (emotes, the non-store categories, "More"). */
export function categorySlot(id: CategoryId): StoreSlot | null {
  const kind = categoryById(id).kind;
  return kind ? KIND_SLOT[kind] : null;
}

// ---------- 3D attachment ----------

/** The bones a 3D cosmetic is attached to (its geometry's root bone, or `attach.anchor`). */
export type Anchor = "anchor_body" | "anchor_head" | "anchor_right_arm" | "anchor_left_arm";
export const ANCHORS: Anchor[] = ["anchor_body", "anchor_head", "anchor_right_arm", "anchor_left_arm"];
