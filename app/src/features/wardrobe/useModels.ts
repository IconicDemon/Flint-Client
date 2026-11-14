/**
 * 3D cosmetics for the wardrobe: an item's thumbnail for its card, and the worn items' geometry and texture for the
 * preview. Every file comes from Rust (`store_model_file`), checked against the Store's SHA-256 and size; each is asked for
 * once per (account, item, asset version) and kept for the visit.
 */
import { useEffect, useMemo, useState } from "react";
import { api, errorText } from "../../lib/api";
import { parseGeometry } from "../../lib/bedrockModel";
import { ANCHORS, kindOf, type Anchor } from "../../lib/cosmeticTypes";
import type { StoreItem } from "../../lib/storeSync";
import type { PreviewModel } from "../store/StorePreview";

type Part = "geometry" | "texture" | "animation" | "thumbnail";
const files = new Map<string, Promise<string>>();
const KEPT = 240;

const fileKey = (uuid: string, item: StoreItem, part: Part) => `${uuid}:${item.cosmeticId}:${item.asset?.id}:${item.asset?.version}:${part}`;

function file(uuid: string, item: StoreItem, part: Part): Promise<string> {
  const key = fileKey(uuid, item, part);
  let known = files.get(key);
  if (!known) {
    known = api.storeModelFile(uuid, item.cosmeticId, part);
    files.set(key, known);
    known.catch(() => files.delete(key));
    if (files.size > KEPT) files.delete(files.keys().next().value!);
  }
  return known;
}

/** Whether the item is a 3D cosmetic with a picture of its own (its 512×512 thumbnail). */
export const hasThumbnail = (item: StoreItem | null | undefined) => !!item?.asset?.model?.thumbnail;

/** An item's thumbnail (a data URL), or null while it loads or when it has none. */
export function useThumbnail(uuid: string, item: StoreItem | null): { url: string | null; error: string | null } {
  const key = item && hasThumbnail(item) ? fileKey(uuid, item, "thumbnail") : null;
  const [state, setState] = useState<{ key: string | null; url: string | null; error: string | null }>({ key: null, url: null, error: null });
  useEffect(() => {
    if (!key || !item) return;
    let cancelled = false;
    file(uuid, item, "thumbnail").then(
      (url) => !cancelled && setState({ key, url, error: null }),
      (e) => !cancelled && setState({ key, url: null, error: errorText(e) }),
    );
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [uuid, key]);
  return state.key === key ? { url: state.url, error: state.error } : { url: null, error: null };
}

function decode(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = reject;
    img.src = url;
  });
}

const models = new Map<string, Promise<PreviewModel | null>>();

/** An item as a model the preview draws: null for one without geometry (a cape, an emote) or that can't be read. */
function previewModel(uuid: string, item: StoreItem): Promise<PreviewModel | null> {
  const m = item.asset?.model;
  if (!m?.geometry || kindOf(item.kind) === "emote") return Promise.resolve(null);
  const key = fileKey(uuid, item, "geometry");
  let known = models.get(key);
  if (!known) {
    known = (async () => {
      const geometry = parseGeometry(await file(uuid, item, "geometry"));
      if (!geometry) return null;
      const image = m.texture ? await decode(await file(uuid, item, "texture")).catch(() => null) : null;
      const anchor: Anchor = (ANCHORS as string[]).includes(m.attach?.anchor ?? "") ? (m.attach!.anchor as Anchor) : "anchor_body";
      return {
        key,
        geometry,
        image,
        frames: Math.max(1, Math.min(12, m.textureFrames ?? 1)),
        frameMs: m.frameMs ?? [],
        anchor,
        mirror: m.attach?.side === "left",
        hidesCape: !!m.hidesCape,
      };
    })().catch(() => null);
    models.set(key, known);
    if (models.size > 60) models.delete(models.keys().next().value!);
  }
  return known;
}

/** The models of `items` (worn, or worn plus the one being looked at), in order, once each is ready. */
export function useLoadoutModels(uuid: string, items: readonly (StoreItem | null)[]): PreviewModel[] {
  const keys = items.map((i) => (i?.asset?.model?.geometry ? fileKey(uuid, i, "geometry") : "")).join("|");
  const [ready, setReady] = useState<{ keys: string; models: PreviewModel[] }>({ keys: "", models: [] });
  useEffect(() => {
    let cancelled = false;
    const wanted = items.filter((i): i is StoreItem => !!i?.asset?.model?.geometry);
    if (!wanted.length) {
      setReady({ keys, models: [] });
      return;
    }
    void Promise.all(wanted.map((i) => previewModel(uuid, i))).then((list) => {
      if (!cancelled) setReady({ keys, models: list.filter((m): m is PreviewModel => !!m) });
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [uuid, keys]);
  // Until the new set is ready the preview keeps the last one, so nothing blinks off and on between picks.
  return useMemo(() => ready.models, [ready]);
}
