/**
 * Store textures for the Store page, and an owned item as a cape the stage can show.
 *
 * The cape an account wears comes from the launcher-wide `useDisplayedCape(uuid)` (lib/displayedCape.ts: the worn Flint
 * Store cape, else the Minecraft cape, else none). This file adds what only the Store page shows: an item pointed at, or
 * being put on, as a `ShownCape` for the same driver (lib/capeTexture.ts `createCapeDriver`).
 */
import { useEffect, useMemo, useState } from "react";
import { api, errorText } from "../../lib/api";
import { isPlayable } from "../../lib/capeAnimation";
import type { PlayableAnimation, ShownCape } from "../../lib/capeSource";
import type { StoreItem } from "../../lib/storeSync";
import { previewFrames, type AnimationSource } from "./capeFrames";

// ---------- Store textures ----------

/** Verified textures (data URLs), least recently used first; kept within 400 and 48 MB of text (a poster is up to 2 MiB). */
const textures = new Map<string, { url: Promise<string>; size: number }>();
const TEXTURES_KEPT = { count: 400, chars: 48 * 1024 * 1024 };

/**
 * The item's texture cache key: per account, item and approved texture version. A reward listed in the community catalog
 * (`listed`, not owned) has public art, the same for every account.
 */
export const textureKey = (uuid: string, item: StoreItem) =>
  item.asset ? `${item.listed ? "community" : uuid}:${item.cosmeticId}:${item.asset.id}:${item.asset.version}` : null;

function texture(uuid: string, item: StoreItem, key: string): Promise<string> {
  const known = textures.get(key);
  if (known) {
    textures.delete(key);
    textures.set(key, known);
    return known.url;
  }
  const entry = { url: item.listed ? api.storeRewardAsset(item.cosmeticId) : api.storeAsset(uuid, item.cosmeticId), size: 0 };
  textures.set(key, entry);
  entry.url.then(
    (url) => {
      entry.size = url.length;
      let chars = 0;
      textures.forEach((e) => (chars += e.size));
      for (const [k, e] of textures) {
        if (textures.size <= TEXTURES_KEPT.count && chars <= TEXTURES_KEPT.chars) break;
        if (k === key) continue;
        textures.delete(k);
        chars -= e.size;
      }
    },
    () => textures.delete(key),
  );
  return entry.url;
}

/** An owned item's verified texture (a data URL); null while it loads, or for an item without one. Never another item's. */
export function useStoreTexture(uuid: string, item: StoreItem | null): { url: string | null; error: string | null } {
  const key = item ? textureKey(uuid, item) : null;
  const [state, setState] = useState<{ key: string | null; url: string | null; error: string | null }>({ key: null, url: null, error: null });
  useEffect(() => {
    if (!key || !item) return;
    let cancelled = false;
    texture(uuid, item, key).then(
      (url) => !cancelled && setState({ key, url, error: null }),
      (e) => !cancelled && setState({ key, url: null, error: errorText(e) }),
    );
    return () => {
      cancelled = true;
    };
    // `key` names the item and its texture version; the item object itself changes with every answer.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [uuid, key]);
  return state.key === key ? { url: state.url, error: state.error } : { url: null, error: null };
}

/**
 * An animated cape's strips, checked by Flint (`store_cape_strip` for an owned one, `store_reward_strip` for a listed
 * reward); null for an item that isn't one Flint plays.
 */
export function storeAnimation(uuid: string, item: StoreItem | null): AnimationSource | null {
  const anim = item?.asset?.animation;
  const key = item ? textureKey(uuid, item) : null;
  if (!item || !key || !isPlayable(anim)) return null;
  const strip = item.listed ? (index: number) => api.storeRewardStrip(item.cosmeticId, index) : (index: number) => api.storeCapeStrip(uuid, item.cosmeticId, index);
  return { key, manifest: anim, strip };
}

// ---------- an item as a cape to show ----------

/**
 * An owned cape item as the stage shows it: its poster at once (once loaded), then, with `motion`, its frames. Null for
 * no item, an item that isn't a cape, or while its poster loads (the stage keeps what it shows meanwhile).
 */
export function useItemCape(uuid: string, item: StoreItem | null, motion: boolean): ShownCape<ImageBitmap> | null {
  const cape = item?.kind === "cape" ? item : null;
  const key = cape ? textureKey(uuid, cape) : null;
  const { url } = useStoreTexture(uuid, cape);
  const source = useMemo(() => storeAnimation(uuid, cape), [uuid, key]); // eslint-disable-line react-hooks/exhaustive-deps
  const [frames, setFrames] = useState<{ key: string; animation: PlayableAnimation<ImageBitmap> } | null>(null);
  useEffect(() => {
    if (!source || !motion) return;
    let cancelled = false;
    previewFrames(source).then(
      (animation) => !cancelled && setFrames({ key: source.key, animation }),
      () => {},
    );
    return () => {
      cancelled = true;
    };
  }, [source, motion]);
  const animation = frames && frames.key === key ? frames.animation : null;
  return useMemo(
    () => (cape && key && url ? { source: "flint", url, cosmeticId: cape.cosmeticId, key, animation } : null),
    [key, url, animation], // eslint-disable-line react-hooks/exhaustive-deps
  );
}
