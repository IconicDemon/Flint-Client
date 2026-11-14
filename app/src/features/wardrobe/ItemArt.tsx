import { useEffect, useMemo, useRef, useState } from "react";
import { Icon } from "../../ui/Icon";
import { onThumbnailTick, thumbnailFrame, thumbnailFrames, type AnimationSource } from "../store/capeFrames";
import { storeAnimation, textureKey, useStoreTexture } from "../store/storeCape";
import type { StoreItem } from "../../lib/storeSync";
import { hasThumbnail, useThumbnail } from "./useModels";

/**
 * An item's picture. A cape shows its front, pixel for pixel; an animated cape plays there while it is on screen (its
 * poster until the frames are ready). A 3D cosmetic shows its 512×512 thumbnail; other items show their texture whole.
 */
export function ItemArt({ uuid, item, motion, small = false }: { uuid: string; item: StoreItem; motion: boolean; small?: boolean }) {
  const thumbnail = hasThumbnail(item);
  const flat = useStoreTexture(uuid, thumbnail || item.asset?.hasTexture === false ? null : item);
  const thumb = useThumbnail(uuid, thumbnail ? item : null);
  const { url, error } = thumbnail ? thumb : flat;
  const key = textureKey(uuid, item);
  // Keyed on the texture, not the item object (every answer from the Store brings new objects for the same items).
  const source = useMemo(() => (motion && !small ? storeAnimation(uuid, item) : null), [key, motion, small]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!url) return <span className={`store-art ${small ? "is-small" : ""} is-missing`}>{error ? <Icon name="alert" size={16} /> : null}</span>;
  if (thumbnail) return <img className={`store-art store-art-thumb ${small ? "is-small" : ""}`} src={url} alt="" draggable={false} />;
  if (item.kind !== "cape") return <img className={`store-art store-art-whole ${small ? "is-small" : ""}`} src={url} alt="" />;
  return (
    <span className={`store-art store-art-cape ${small ? "is-small" : ""}`} style={{ backgroundImage: `url(${url})` }}>
      {source && <AnimatedFront source={source} />}
    </span>
  );
}

/** An animated cape's front, drawn when its frame changes while on screen. Hidden until its frames are ready. */
function AnimatedFront({ source }: { source: AnimationSource }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const [visible, setVisible] = useState(false);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting), { rootMargin: "120px" });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const el = ref.current;
    if (!visible || !el) return;
    let stop = () => {};
    let cancelled = false;
    thumbnailFrames(source).then(
      (frames) => {
        if (cancelled) return;
        el.width = frames.width;
        el.height = frames.height;
        const ctx = el.getContext("2d")!;
        let shown = -1;
        const draw = (now: number) => {
          const index = thumbnailFrame(frames, now);
          if (index === shown) return;
          frames.draw(ctx, index);
          shown = index;
        };
        // This moment's frame at once (the clock may be paused: window behind, nobody using it), then each change.
        draw(Date.now());
        stop = onThumbnailTick(draw);
        setReady(true);
      },
      () => {},
    );
    return () => {
      cancelled = true;
      stop();
    };
  }, [visible, source]);

  return <canvas ref={ref} className={`store-art-frames ${ready ? "is-ready" : ""}`} aria-hidden />;
}
