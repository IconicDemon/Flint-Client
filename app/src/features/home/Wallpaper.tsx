import { useEffect, useRef, useState } from "react";
import fallback from "../../assets/wallpapers/26_3.jpg";

const ready = new Map<string, Promise<boolean>>();

/** Fetches and fully decodes an image once; later requests for the same URL reuse the result. */
function decodeOnce(src: string): Promise<boolean> {
  let pending = ready.get(src);
  if (!pending) {
    pending = new Promise<boolean>((resolve) => {
      const img = new Image();
      img.decoding = "async";
      img.onload = () => {
        (img.decode ? img.decode() : Promise.resolve()).then(
          () => resolve(true),
          () => resolve(img.naturalWidth > 0),
        );
      };
      img.onerror = () => resolve(false);
      img.src = src;
    });
    ready.set(src, pending);
  }
  return pending;
}

/** Decodes wallpapers ahead of time when the app is idle, so switching profiles is instant. */
export function preloadWallpapers(srcs: string[]) {
  const pending = [...new Set(srcs)].filter((s) => !ready.has(s));
  if (!pending.length) return;
  const idle = window.requestIdleCallback ?? ((cb: () => void) => window.setTimeout(cb, 400));
  const next = () => {
    const src = pending.shift();
    if (!src) return;
    void decodeOnce(src).then(() => idle(next));
  };
  idle(next);
}

type Layer = { src: string; key: number };

/**
 * Cover-scaled version wallpaper. The next image is decoded before it is mounted and fades in over
 * the previous one, which stays visible underneath, so switching profiles never shows an empty
 * frame. If an image can't be decoded, the generic Java Edition art is used and the failure logged.
 */
export function Wallpaper({ src, parallax, crossfade }: { src: string; parallax: boolean; crossfade: boolean }) {
  const [layers, setLayers] = useState<Layer[]>([]);
  const wrap = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let live = true;
    void (async () => {
      let next = src;
      if (!(await decodeOnce(src))) {
        console.error(`[flint] wallpaper failed to load: ${src}`);
        next = fallback;
        await decodeOnce(fallback);
      }
      if (!live) return;
      setLayers((prev) => {
        if (prev[prev.length - 1]?.src === next) return prev;
        const key = (prev[prev.length - 1]?.key ?? 0) + 1;
        return crossfade ? [...prev.slice(-1), { src: next, key }] : [{ src: next, key }];
      });
    })();
    return () => {
      live = false;
    };
  }, [src, crossfade]);

  useEffect(() => {
    if (layers.length < 2) return;
    const t = window.setTimeout(() => setLayers((prev) => prev.slice(-1)), 480);
    return () => window.clearTimeout(t);
  }, [layers]);

  useEffect(() => {
    const el = wrap.current;
    if (!el) return;
    if (!parallax) {
      el.style.transform = "";
      return;
    }
    const target = { x: 0, y: 0 };
    const cur = { x: 0, y: 0 };
    let frame = 0;
    const tick = () => {
      cur.x += (target.x - cur.x) * 0.08;
      cur.y += (target.y - cur.y) * 0.08;
      el.style.transform = `translate3d(${cur.x.toFixed(2)}px, ${cur.y.toFixed(2)}px, 0)`;
      frame = Math.abs(target.x - cur.x) + Math.abs(target.y - cur.y) > 0.05 ? requestAnimationFrame(tick) : 0;
    };
    const kick = () => {
      if (!frame) frame = requestAnimationFrame(tick);
    };
    const onMove = (e: PointerEvent) => {
      target.x = -((e.clientX / window.innerWidth) * 2 - 1) * 8;
      target.y = -((e.clientY / window.innerHeight) * 2 - 1) * 5;
      kick();
    };
    const settle = () => {
      target.x = 0;
      target.y = 0;
      kick();
    };
    window.addEventListener("pointermove", onMove, { passive: true });
    window.addEventListener("blur", settle);
    document.documentElement.addEventListener("pointerleave", settle);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("blur", settle);
      document.documentElement.removeEventListener("pointerleave", settle);
    };
  }, [parallax]);

  return (
    <div className="wallpaper" aria-hidden>
      <div ref={wrap} className={`wallpaper-move ${parallax ? "has-parallax" : ""}`}>
        {layers.map((layer, i) => (
          <img
            key={layer.key}
            src={layer.src}
            alt=""
            decoding="async"
            draggable={false}
            className={`wallpaper-img ${i === layers.length - 1 && (layers.length > 1 || layer.key === 1) ? "is-entering" : ""}`}
          />
        ))}
      </div>
      <div className="wallpaper-shade" />
    </div>
  );
}
