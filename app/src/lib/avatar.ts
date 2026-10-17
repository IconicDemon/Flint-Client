const cache = new Map<string, Promise<string | null>>();

/** Minecraft ignores a fully opaque hat region on legacy 64×32 skins. */
function hatIsUsable(image: HTMLImageElement): boolean {
  if (image.height < 32) return false;
  if (image.height >= 64) return true;
  const probe = document.createElement("canvas");
  probe.width = 32;
  probe.height = 16;
  const ctx = probe.getContext("2d");
  if (!ctx) return false;
  ctx.drawImage(image, 32, 0, 32, 16, 0, 0, 32, 16);
  const data = ctx.getImageData(0, 0, 32, 16).data;
  for (let i = 3; i < data.length; i += 4) if (data[i] < 128) return true;
  return false;
}

type Part = [sx: number, sy: number, w: number, h: number, dx: number, dy: number, mirror?: boolean];

/** Flat front view of the whole player, used when 3D is turned off. */
export function bodyFromSkin(dataUrl: string, slim: boolean, scale = 14): Promise<string | null> {
  const key = `body:${slim}:${scale}:${dataUrl.length}:${dataUrl.slice(-48)}`;
  const hit = cache.get(key);
  if (hit) return hit;
  const job = new Promise<string | null>((resolve) => {
    const image = new Image();
    image.onload = () => {
      const modern = image.height >= 64;
      const arm = slim && modern ? 3 : 4;
      const base: Part[] = [
        [8, 8, 8, 8, 4, 0],
        [20, 20, 8, 12, 4, 8],
        [44, 20, arm, 12, 4 - arm, 8],
        modern ? [36, 52, arm, 12, 12, 8] : [44, 20, arm, 12, 12, 8, true],
        [4, 20, 4, 12, 4, 20],
        modern ? [20, 52, 4, 12, 8, 20] : [4, 20, 4, 12, 8, 20, true],
      ];
      const overlay: Part[] = modern
        ? [
            [20, 36, 8, 12, 4, 8],
            [44, 36, arm, 12, 4 - arm, 8],
            [52, 52, arm, 12, 12, 8],
            [4, 36, 4, 12, 4, 20],
            [4, 52, 4, 12, 8, 20],
          ]
        : [];
      if (hatIsUsable(image)) overlay.unshift([40, 8, 8, 8, 4, 0]);
      const canvas = document.createElement("canvas");
      canvas.width = 16 * scale;
      canvas.height = 32 * scale;
      const ctx = canvas.getContext("2d");
      if (!ctx) return resolve(null);
      ctx.imageSmoothingEnabled = false;
      for (const [sx, sy, w, h, dx, dy, mirror] of [...base, ...overlay]) {
        if (mirror) {
          ctx.save();
          ctx.translate((dx + w) * scale, 0);
          ctx.scale(-1, 1);
          ctx.drawImage(image, sx, sy, w, h, 0, dy * scale, w * scale, h * scale);
          ctx.restore();
        } else {
          ctx.drawImage(image, sx, sy, w, h, dx * scale, dy * scale, w * scale, h * scale);
        }
      }
      resolve(canvas.toDataURL("image/png"));
    };
    image.onerror = () => resolve(null);
    image.src = dataUrl;
  });
  cache.set(key, job);
  return job;
}

/** Renders the face and hat layer of a skin texture into a crisp square avatar. */
export function headFromSkin(dataUrl: string, size = 64): Promise<string | null> {
  const key = `${size}:${dataUrl.length}:${dataUrl.slice(-48)}`;
  const hit = cache.get(key);
  if (hit) return hit;
  const job = new Promise<string | null>((resolve) => {
    const image = new Image();
    image.onload = () => {
      const canvas = document.createElement("canvas");
      canvas.width = canvas.height = size;
      const ctx = canvas.getContext("2d");
      if (!ctx) return resolve(null);
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(image, 8, 8, 8, 8, 0, 0, size, size);
      if (hatIsUsable(image)) ctx.drawImage(image, 40, 8, 8, 8, 0, 0, size, size);
      resolve(canvas.toDataURL("image/png"));
    };
    image.onerror = () => resolve(null);
    image.src = dataUrl;
  });
  cache.set(key, job);
  return job;
}
