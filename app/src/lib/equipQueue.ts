/**
 * Wearing on the Store page: the latest pick wins (lib/storePage.ts shows it at once, the Store decides).
 *
 * - A pick is shown the moment it is made (`pending`), and sent to the Store unless a request for its slot is already on
 *   its way: one request per slot at a time, never two for the same pick.
 * - Picks made while a request is out replace each other; once the Store answers, only the last one is sent (none when it is
 *   what the Store now says is worn).
 * - Every answer goes to `answered` (the page drops one older than what it shows, by the Store's inventory version).
 * - When the slot's last pick is answered, `settled` says whether the Store took it; a refusal of an earlier pick is
 *   overtaken by the later one and never shown.
 *
 * Pure (dependencies injected), so the Node tests drive it.
 */
import type { StoreSlot } from "./storeSync.ts";

export type Pick = { slot: StoreSlot; cosmeticId: string | null };

export type EquipQueueDeps<V> = {
  /** `PUT /me/equipped/` through Rust (`store_equip`): the Store's answer, or a rejection with its reason. */
  send(slot: StoreSlot, cosmeticId: string | null): Promise<V>;
  /** What the Store says the slot wears now (its last answer shown), or null. */
  worn(slot: StoreSlot): string | null;
  answered(view: V): void;
  /** The pick shown while the Store hasn't answered it (the latest one), or null. */
  changed(pending: Pick | null): void;
  /** The slot's last pick was answered: `error` null when the Store took it. */
  settled(pick: Pick, error: unknown | null): void;
};

export type EquipQueue = {
  pick(slot: StoreSlot, cosmeticId: string | null): void;
  /** The pick shown while the Store answers (the most recent slot's), or null. */
  pending(): Pick | null;
  /** Whether a request for `slot` is on its way. */
  sending(slot: StoreSlot): boolean;
};

export function createEquipQueue<V>(deps: EquipQueueDeps<V>): EquipQueue {
  const wanted = new Map<StoreSlot, string | null>();
  const inFlight = new Set<StoreSlot>();
  let last: StoreSlot | null = null;

  const pending = (): Pick | null => {
    if (last && wanted.has(last)) return { slot: last, cosmeticId: wanted.get(last) ?? null };
    for (const [slot, cosmeticId] of wanted) return { slot, cosmeticId };
    return null;
  };

  const pump = async (slot: StoreSlot): Promise<void> => {
    if (!wanted.has(slot)) return;
    const id = wanted.get(slot) ?? null;
    if (id === deps.worn(slot)) {
      // Back to what the Store already says: nothing to ask.
      wanted.delete(slot);
      deps.changed(pending());
      deps.settled({ slot, cosmeticId: id }, null);
      return;
    }
    inFlight.add(slot);
    let error: unknown | null = null;
    try {
      deps.answered(await deps.send(slot, id));
    } catch (e) {
      error = e ?? new Error("refused");
    }
    inFlight.delete(slot);
    if (!wanted.has(slot) || wanted.get(slot) !== id) {
      // A later pick: it is the one that counts (sent now, even after this one failed).
      return pump(slot);
    }
    wanted.delete(slot);
    deps.changed(pending());
    deps.settled({ slot, cosmeticId: id }, error);
  };

  return {
    pick(slot, cosmeticId) {
      wanted.set(slot, cosmeticId);
      last = slot;
      deps.changed(pending());
      if (!inFlight.has(slot)) void pump(slot);
    },
    pending,
    sending: (slot) => inFlight.has(slot),
  };
}
