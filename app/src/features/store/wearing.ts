import type { StoreSlot } from "../../lib/storeSync";

/** A wear the Store hasn't answered yet (already applied to the inventory shown), or null. */
export type Wearing = { slot: StoreSlot; cosmeticId: string | null } | null;
