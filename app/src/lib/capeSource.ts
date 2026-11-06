/**
 * Which cape a launcher preview shows for an account, everywhere (Home, Skins, the Store):
 *
 *   1. the cape the account wears in the Flint Store;
 *   2. otherwise its Minecraft cape (the one Mojang has active);
 *   3. otherwise no cape.
 *
 * The same rule the game follows (game-runtime `Mc263StoreCapes`, `StoreCapes189`). The Store decides what is worn: this only
 * reads its answers (`store_inventory`, and every answer the Store page gets, through `storeViewFeed`). Never changes the
 * Minecraft cape at Mojang.
 *
 * - **Per account**: everything is kept by canonical UUID. A preview asking for another account never sees this one's
 *   Store answer, art or animation, not even for a frame; signing out of the Store (or removing the account) forgets them.
 * - **No flicker**: while the Store's answer or the Flint cape's picture is on its way, the choice is *pending* and a preview
 *   keeps what it shows ({@link settleCape}), instead of showing the Minecraft cape and then swapping it out.
 * - Animated capes show their poster (frame 0, the Store's static texture) first, then play once their strips are decoded.
 *
 * Pure (dependencies injected), so the Node tests drive it; `displayedCape.ts` wires it to the app.
 */
import type { CapeAnimation } from "./capeAnimation.ts";
import { canonicalUuid, needsRefresh, type StoreChanges, type StoreItem, type StoreView } from "./storeSync.ts";

/** A Flint cape ready to show: its texture (a data URL; the poster when animated) and, once decoded, its frames. */
export type FlintCape<S> = {
  cosmeticId: string;
  /** `cosmeticId:assetId:version`: a new approved texture is a new key. */
  key: string;
  poster: string;
  animation: PlayableAnimation<S> | null;
};

/** An animation with its strips decoded (`S`: an `ImageBitmap` in the app). */
export type PlayableAnimation<S> = { manifest: CapeAnimation; strips: S[] };

export type FlintCapeState<S> =
  /** The Store's answer for this account hasn't arrived yet. */
  | { state: "unknown" }
  /** Not signed in to the Store, nothing worn in the cape slot, or the worn item has no picture. */
  | { state: "none" }
  | { state: "loading"; key: string }
  /** Its picture couldn't be loaded or checked: the Minecraft cape shows. */
  | { state: "failed"; key: string }
  | { state: "ready"; cape: FlintCape<S> };

export type ShownCape<S> =
  | { source: "none" }
  | { source: "minecraft"; url: string }
  | { source: "flint"; url: string; cosmeticId: string; key: string; animation: PlayableAnimation<S> | null };

/** What a preview shows; `pending` while part of the answer is still on its way (what's shown is then the last choice). */
export type DisplayedCape<S = ImageBitmap> = ShownCape<S> & { pending: boolean };

export const NO_CAPE: ShownCape<never> = Object.freeze({ source: "none" });

/**
 * The rule. `minecraft`: the account's Minecraft cape (a data URL), null for none, undefined while its skin is loading.
 * Null when it can't be decided yet.
 */
export function chooseCape<S>(flint: FlintCapeState<S>, minecraft: string | null | undefined): ShownCape<S> | null {
  if (flint.state === "ready") {
    const { cape } = flint;
    return { source: "flint", url: cape.poster, cosmeticId: cape.cosmeticId, key: cape.key, animation: cape.animation };
  }
  if (flint.state === "unknown" || flint.state === "loading" || minecraft === undefined) return null;
  return minecraft ? { source: "minecraft", url: minecraft } : NO_CAPE;
}

/** The last decided cape and the account it was for. */
export type Settled<S> = { uuid: string; cape: ShownCape<S> };

/**
 * A pending choice keeps the last decided cape **of the same account**; for any other account (a switch) it is no cape.
 * Returns what to show and whether it is pending.
 */
export function settleCape<S>(previous: Settled<S> | null, uuid: string, choice: ShownCape<S> | null): DisplayedCape<S> {
  if (choice) return { ...choice, pending: false };
  const kept = previous && previous.uuid === uuid ? previous.cape : NO_CAPE;
  return { ...kept, pending: true };
}

/** Whether two shown capes draw the same thing (so a preview leaves its texture alone). */
export function sameCape<S>(a: ShownCape<S> | null | undefined, b: ShownCape<S> | null | undefined): boolean {
  if (!a || !b || a.source !== b.source) return false;
  if (a.source === "none") return true;
  if (a.source === "minecraft") return a.url === (b as typeof a).url;
  const other = b as typeof a;
  return a.key === other.key && a.url === other.url && a.animation === other.animation;
}

/** The cape item the account wears, from a Store answer: null when none (or it has no picture). */
export function wornCape(view: StoreView | null | undefined): { item: StoreItem; key: string } | null {
  const inventory = view?.signedIn ? view.inventory : null;
  const id = inventory?.equipped.cape;
  if (!inventory || !id) return null;
  const item = inventory.owned.find((o) => o.cosmeticId === id && o.kind === "cape");
  if (!item?.asset) return null;
  return { item, key: `${item.cosmeticId}:${item.asset.id}:${item.asset.version}` };
}

export type CapeSourceDeps<S> = {
  /** `store_inventory` (non-interactive: never asks the system for a saved sign-in). */
  inventory(uuid: string): Promise<StoreView>;
  changes(uuid: string, since: number | null): Promise<StoreChanges>;
  /** The item's checked texture, as a data URL (`store_asset`). */
  poster(uuid: string, cosmeticId: string): Promise<string>;
  /** Every strip of the item's animation, checked and decoded; rejects if any fails (the poster then stays). */
  strips(uuid: string, cosmeticId: string, manifest: CapeAnimation): Promise<S[]>;
  /** Frees decoded strips no longer shown. */
  release(strips: S[]): void;
  isPlayable(manifest: CapeAnimation | null | undefined): manifest is CapeAnimation;
  now(): number;
  later(callback: () => void, ms: number): unknown;
  cancel(handle: unknown): void;
};

type Art<S> = {
  key: string;
  cosmeticId: string;
  poster: string | null;
  failed: boolean;
  animation: PlayableAnimation<S> | null;
};

type Entry<S> = {
  uuid: string;
  /** undefined: not heard yet; null: no answer (the test identity, which has no Store, or the Store's answer couldn't be had). */
  view: StoreView | null | undefined;
  /** False for the test identity: never asked about. */
  store: boolean;
  art: Art<S> | null;
  /** Art no longer worn, freed a little later (a preview may still be drawing a frame of it). */
  retired: { art: Art<S>; timer: unknown }[];
  listeners: Set<() => void>;
  snapshot: FlintCapeState<S>;
  loading: Promise<void> | null;
  lastCheck: number;
};

/** Decoded frames of a cape no longer worn are freed after this long. */
export const RELEASE_AFTER_MS = 10_000;
/** A window coming to the front checks the Store for changes (an equip in the game or on the website), at most this often. */
export const CHECK_GAP_MS = 5_000;

export type CapeSource<S> = ReturnType<typeof createCapeSource<S>>;

export function createCapeSource<S>(deps: CapeSourceDeps<S>) {
  const entries = new Map<string, Entry<S>>();
  const UNKNOWN: FlintCapeState<S> = { state: "unknown" };
  const NONE: FlintCapeState<S> = { state: "none" };

  const entry = (uuid: string): Entry<S> => {
    const id = canonicalUuid(uuid);
    let e = entries.get(id);
    if (!e) {
      e = { uuid: id, view: undefined, store: true, art: null, retired: [], listeners: new Set(), snapshot: UNKNOWN, loading: null, lastCheck: 0 };
      entries.set(id, e);
    }
    return e;
  };

  const compute = (e: Entry<S>): FlintCapeState<S> => {
    if (e.view === undefined) return UNKNOWN;
    const worn = wornCape(e.view);
    if (!worn || !e.art || e.art.key !== worn.key) return worn ? { state: "loading", key: worn.key } : NONE;
    const art = e.art;
    if (art.failed) return { state: "failed", key: art.key };
    if (!art.poster) return { state: "loading", key: art.key };
    return { state: "ready", cape: { cosmeticId: art.cosmeticId, key: art.key, poster: art.poster, animation: art.animation } };
  };

  /** Recomputes the account's state; listeners hear of it only when it changed. */
  const update = (e: Entry<S>) => {
    const next = compute(e);
    const prev = e.snapshot;
    const same =
      prev.state === next.state &&
      (next.state === "ready"
        ? prev.state === "ready" && prev.cape.key === next.cape.key && prev.cape.poster === next.cape.poster && prev.cape.animation === next.cape.animation
        : next.state === "loading" || next.state === "failed"
          ? (prev as { key: string }).key === next.key
          : true);
    if (same) return;
    e.snapshot = next;
    for (const listener of [...e.listeners]) listener();
  };

  const retire = (e: Entry<S>, art: Art<S> | null, now = false) => {
    if (!art?.animation) return;
    const strips = art.animation.strips;
    if (now) return deps.release(strips);
    const item = { art, timer: null as unknown };
    item.timer = deps.later(() => {
      e.retired = e.retired.filter((r) => r !== item);
      deps.release(strips);
    }, RELEASE_AFTER_MS);
    e.retired.push(item);
  };

  /** Loads the worn cape's picture, then its animation, if they aren't the ones already here. */
  const loadArt = (e: Entry<S>) => {
    const worn = wornCape(e.view);
    if (!worn) {
      retire(e, e.art);
      e.art = null;
      return;
    }
    if (e.art?.key === worn.key) return;
    retire(e, e.art);
    const art: Art<S> = { key: worn.key, cosmeticId: worn.item.cosmeticId, poster: null, failed: false, animation: null };
    e.art = art;
    const current = () => entries.get(e.uuid) === e && e.art === art;
    deps.poster(e.uuid, art.cosmeticId).then(
      (poster) => {
        if (!current()) return;
        art.poster = poster;
        update(e);
        const manifest = worn.item.asset?.animation;
        if (!deps.isPlayable(manifest)) return;
        deps.strips(e.uuid, art.cosmeticId, manifest).then(
          (strips) => {
            if (!current()) return deps.release(strips);
            art.animation = { manifest, strips };
            update(e);
          },
          // A strip that failed its checks: the poster stays, as a static cape (the game does the same).
          () => {},
        );
      },
      () => {
        if (!current()) return;
        art.failed = true;
        update(e);
      },
    );
  };

  const source = {
    /** The account's Flint cape state (stable between changes, for `useSyncExternalStore`). */
    state(uuid: string): FlintCapeState<S> {
      return entries.get(canonicalUuid(uuid))?.snapshot ?? UNKNOWN;
    },

    /** Listens to one account; the first listener asks the Store for its answer if none was heard yet. */
    subscribe(uuid: string, listener: () => void, options: { store: boolean }): () => void {
      const e = entry(uuid);
      e.listeners.add(listener);
      e.store = options.store;
      if (!options.store) {
        // The test identity has no Store: straight to its Minecraft cape.
        if (e.view === undefined) {
          e.view = null;
          update(e);
        }
      } else if (e.view === undefined && !e.loading) void source.refresh(uuid);
      return () => {
        e.listeners.delete(listener);
      };
    },

    /** Asks the Store for the account's answer again (`store_inventory`). */
    refresh(uuid: string): Promise<void> {
      const e = entry(uuid);
      if (e.loading) return e.loading;
      const id = e.uuid;
      e.loading = deps
        .inventory(id)
        .then(
          (view) => source.noteView(view),
          () => {
            // No answer at all (not even the cached copy): no Flint cape until one comes.
            const live = entries.get(id);
            if (live === e && e.view === undefined) {
              e.view = null;
              update(e);
            }
          },
        )
        .finally(() => {
          e.loading = null;
          e.lastCheck = deps.now();
        });
      return e.loading;
    },

    /** A Store answer, from here or from the Store page. Answers for an account nobody showed yet are kept for when one does. */
    noteView(view: StoreView) {
      const e = entry(view.uuid);
      if (!view.signedIn) return source.signedOut(view.uuid, view);
      e.view = view;
      loadArt(e);
      update(e);
    },

    /** The account left the Store on this computer: its answer and art go at once (never shown as worn after sign-out). */
    signedOut(uuid: string, view: StoreView | null = null) {
      const e = entry(uuid);
      retire(e, e.art, true);
      e.art = null;
      e.view = view ?? { uuid: e.uuid, storeUrl: "", signedIn: false, locked: false, stale: false };
      update(e);
    },

    /**
     * The window came to the front: accounts being shown ask the Store whether anything changed (an equip in the game or on
     * the website, a refund, the Store reachable again), at most every few seconds.
     */
    checkShown() {
      const now = deps.now();
      for (const e of entries.values()) {
        if (!e.store || e.listeners.size === 0 || e.loading || now - e.lastCheck < CHECK_GAP_MS) continue;
        e.lastCheck = now;
        if (!e.view?.signedIn) {
          void source.refresh(e.uuid);
          continue;
        }
        const since = e.view.inventory?.version ?? null;
        void deps.changes(e.uuid, since).then(
          (result) => {
            if (entries.get(e.uuid) === e && needsRefresh(result, !!e.view?.stale)) void source.refresh(e.uuid);
          },
          () => {},
        );
      }
    },

    /** Forgets every account not in `uuids` (removed from Flint), with its art. */
    keepOnly(uuids: string[]) {
      const keep = new Set(uuids.map(canonicalUuid));
      for (const [id, e] of entries) {
        if (keep.has(id)) continue;
        retire(e, e.art, true);
        for (const r of e.retired) {
          deps.cancel(r.timer);
          deps.release(r.art.animation?.strips ?? []);
        }
        entries.delete(id);
        e.art = null;
        e.view = undefined;
        e.snapshot = UNKNOWN;
        for (const listener of [...e.listeners]) listener();
      }
    },

    /** For tests and the dev tour: what is kept. */
    describe() {
      return [...entries.values()].map((e) => ({ uuid: e.uuid, state: e.snapshot.state, listeners: e.listeners.size, art: e.art?.key ?? null, animated: !!e.art?.animation }));
    },
  };
  return source;
}
