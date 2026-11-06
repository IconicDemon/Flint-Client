/**
 * A skinview3d viewer's cape, as a launcher preview shows it ({@link createCapeDriver}): no cape, a Minecraft cape, a Flint
 * cape, or an animated Flint cape played frame by frame at the Store's own timings, on the wall clock as the game plays it
 * (lib/capeAnimation.ts). Browser only.
 *
 * Animated capes: each frame is drawn into the viewer's own offscreen cape canvas (64×32 per unit, at most 1024×512; the
 * 22×17 atlas at its top left, the rest transparent), and the cape texture (shared by the cape and the elytra) is marked for
 * upload; the cape's UVs are normalized to 64×32, so they stay right at every scale. A frame is drawn only when the wall
 * clock moved to another one, and nothing runs while the preview is hidden, the window minimized or motion is off.
 */
import type { SkinViewer } from "skinview3d";
import { ATLAS, decodesHalved, frameAt, framePlace, previewScale, textureSize, untilNextFrame, type CapeAnimation } from "./capeAnimation";
import { sameCape, type PlayableAnimation, type ShownCape } from "./capeSource";
import { onWindowMinimized } from "./windowFocus";

const BITMAP: ImageBitmapOptions = { premultiplyAlpha: "none", colorSpaceConversion: "none" };

/** The strip's frames as an ImageBitmap, checked against the manifest's size; HD strips decoded at half size. */
export async function decodeStrip(bytes: ArrayBuffer, manifest: CapeAnimation, index: number): Promise<ImageBitmap> {
  const width = manifest.width;
  const height = manifest.strips[index].frames * manifest.height;
  const blob = new Blob([bytes], { type: "image/png" });
  if (decodesHalved(manifest)) {
    const half = { resizeWidth: width / 2, resizeHeight: height / 2 };
    try {
      const bitmap = await createImageBitmap(blob, { ...BITMAP, ...half, resizeQuality: "pixelated" });
      if (bitmap.width === half.resizeWidth && bitmap.height === half.resizeHeight) return bitmap;
      bitmap.close();
    } catch {
      // A web view that can't resize while decoding: halve through a canvas below.
    }
    const full = await createImageBitmap(blob, BITMAP);
    try {
      if (full.width !== width || full.height !== height) throw new Error("This cape's animation isn't the size the Flint Store listed.");
      const canvas = document.createElement("canvas");
      canvas.width = half.resizeWidth;
      canvas.height = half.resizeHeight;
      const g = canvas.getContext("2d")!;
      g.imageSmoothingEnabled = false;
      g.drawImage(full, 0, 0, half.resizeWidth, half.resizeHeight);
      return await createImageBitmap(canvas);
    } finally {
      full.close();
    }
  }
  const bitmap = await createImageBitmap(blob, BITMAP);
  if (bitmap.width !== width || bitmap.height !== height) {
    bitmap.close();
    throw new Error("This cape's animation isn't the size the Flint Store listed.");
  }
  return bitmap;
}

/**
 * Every strip of an animation, fetched with `fetchStrip` (a few at a time) and decoded; if one fails, those already decoded
 * are freed and the promise rejects (the cape then keeps its poster).
 */
export async function loadStrips(manifest: CapeAnimation, fetchStrip: (index: number) => Promise<ArrayBuffer>): Promise<ImageBitmap[]> {
  const out: ImageBitmap[] = new Array(manifest.strips.length);
  let next = 0;
  let failed: unknown = null;
  const worker = async () => {
    while (failed === null && next < manifest.strips.length) {
      const i = next++;
      try {
        out[i] = await decodeStrip(await fetchStrip(i), manifest, i);
      } catch (e) {
        failed ??= e;
      }
    }
  };
  await Promise.all([worker(), worker()]);
  if (failed !== null) {
    releaseStrips(out.filter(Boolean));
    throw failed;
  }
  return out;
}

export function releaseStrips(strips: ImageBitmap[]) {
  for (const s of strips) s?.close();
}

/** Draws frame `f` into `canvas` (64×32 per unit at the preview scale): the atlas only; the rest stays transparent. */
export function drawFrame(canvas: HTMLCanvasElement, anim: PlayableAnimation<ImageBitmap>, f: number) {
  const { manifest, strips } = anim;
  const s = previewScale(manifest);
  const { strip, y } = framePlace(manifest, f);
  const source = strips[strip];
  // 1, or ½ for a strip decoded at half size.
  const k = source.width / manifest.width;
  const g = canvas.getContext("2d")!;
  g.imageSmoothingEnabled = false;
  g.clearRect(0, 0, ATLAS.width * s, ATLAS.height * s);
  g.drawImage(source, 0, y * k, manifest.width * k, manifest.height * k, 0, 0, ATLAS.width * s, ATLAS.height * s);
}

/** Images decoded once per URL (Minecraft capes and Flint posters are data URLs), a few kept. */
const images = new Map<string, Promise<HTMLImageElement>>();
function decodeImage(url: string): Promise<HTMLImageElement> {
  let image = images.get(url);
  if (!image) {
    image = new Promise<HTMLImageElement>((resolve, reject) => {
      const img = new Image();
      img.crossOrigin = "anonymous";
      img.onload = () => resolve(img);
      img.onerror = reject;
      img.src = url;
    });
    images.set(url, image);
    image.catch(() => images.delete(url));
    if (images.size > 16) images.delete(images.keys().next().value!);
  }
  return image;
}

export type CapeDriver = {
  /** Shows `cape` (a no-op when it is what's shown). */
  show(cape: ShownCape<ImageBitmap>): void;
  /** Whether an animated cape may play (the preview is on screen and motion is allowed); paused, it holds its frame. */
  setPlaying(playing: boolean): void;
  dispose(): void;
};

/** Frames are never written more often than this (a display frame), whatever their durations; the wall clock picks the frame. */
const MIN_STEP_MS = 16;

/**
 * Drives `viewer`'s cape texture. `requestRender` asks the preview to draw (its own loop decides when); it is called after
 * every texture change. The previews (Home, Skins, Store) each create one per viewer and give it what `useDisplayedCape`
 * returns.
 */
export function createCapeDriver(viewer: SkinViewer, requestRender: () => void): CapeDriver {
  let shown: ShownCape<ImageBitmap> | null = null;
  let token = 0;
  let anim: { data: PlayableAnimation<ImageBitmap>; frame: number } | null = null;
  let timer = 0;
  let wanted = true;
  let minimized = false;
  let disposed = false;
  const playing = () => wanted && !minimized && !document.hidden && !disposed;

  const stop = () => {
    if (timer) window.clearTimeout(timer);
    timer = 0;
  };
  const upload = () => {
    const map = viewer.playerObject.cape.map;
    if (map) map.needsUpdate = true;
    requestRender();
  };
  const tick = () => {
    timer = 0;
    if (!anim || !playing()) return;
    const now = Date.now();
    const f = frameAt(anim.data.manifest, now);
    if (f !== anim.frame) {
      try {
        drawFrame(viewer.capeCanvas, anim.data, f);
      } catch {
        // Its frames were freed (the cape was taken off meanwhile): hold the last frame.
        anim = null;
        return;
      }
      anim.frame = f;
      upload();
    }
    timer = window.setTimeout(tick, Math.max(MIN_STEP_MS, untilNextFrame(anim.data.manifest, Date.now())));
  };
  const sync = () => {
    if (playing() && anim) {
      if (!timer) tick();
    } else stop();
  };

  const startAnimation = (data: PlayableAnimation<ImageBitmap>) => {
    const { width, height } = textureSize(data.manifest);
    const frame = document.createElement("canvas");
    frame.width = width;
    frame.height = height;
    // Paused (motion off, or not on screen yet): the first frame, as the poster shows it.
    const f = playing() ? frameAt(data.manifest, Date.now()) : 0;
    drawFrame(frame, data, f);
    // Copies the frame into the viewer's cape canvas at this size and makes the cape (and elytra) texture from it; frames
    // after this one are drawn straight into that canvas.
    viewer.loadCape(frame);
    anim = { data, frame: f };
    requestRender();
    sync();
  };

  const offMinimized = onWindowMinimized((now) => {
    minimized = now;
    sync();
  });
  const onVisibility = () => sync();
  document.addEventListener("visibilitychange", onVisibility);

  return {
    show(cape) {
      if (disposed || sameCape(shown, cape)) return;
      shown = cape;
      const mine = ++token;
      stop();
      anim = null;
      if (cape.source === "none") {
        viewer.resetCape();
        requestRender();
        return;
      }
      if (cape.source === "flint" && cape.animation) {
        try {
          startAnimation(cape.animation);
          return;
        } catch {
          // Its frames are gone: the poster below.
        }
      }
      decodeImage(cape.url).then(
        (img) => {
          if (mine !== token || disposed || viewer.disposed) return;
          try {
            viewer.loadCape(img);
          } catch {
            // Not a cape texture's size: nothing rather than a broken cape.
            viewer.resetCape();
          }
          requestRender();
        },
        () => {
          if (mine !== token || disposed || viewer.disposed) return;
          viewer.resetCape();
          requestRender();
        },
      );
    },
    setPlaying(next) {
      wanted = next;
      sync();
    },
    dispose() {
      disposed = true;
      stop();
      anim = null;
      offMinimized();
      document.removeEventListener("visibilitychange", onVisibility);
    },
  };
}
