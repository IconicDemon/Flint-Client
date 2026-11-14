// Browser-only development stand-ins for the wardrobe (devMock.ts, devMockStore.ts): PLACEHOLDER cosmetics of every v2 kind
// with simple Bedrock geometry drawn here (made for Flint's tests, no one else's art), and the skin changer's list. Never
// bundled into production builds; nothing here is a real product. `?store=v2` adds the cosmetics and the emote wheel;
// `?store=v2,future` also a kind Flint doesn't know yet. The skin changer never touches a real account: Apply only
// records the pick (`window.__flintAppliedSkins`).

type Cube = { origin: number[]; size: number[]; uv: number[]; rotation?: number[]; pivot?: number[]; inflate?: number };
type Bone = { name: string; parent?: string; pivot: number[]; rotation?: number[]; cubes?: Cube[] };

const geometry = (id: string, bones: Bone[], tw = 32, th = 32) =>
  JSON.stringify({ format_version: "1.12.0", "minecraft:geometry": [{ description: { identifier: `geometry.flint.${id}`, texture_width: tw, texture_height: th }, bones }] });

/** Placeholder models in player-model coordinates (feet at y 0, neck at 24, back at +z). */
export const MODELS: Record<string, { geometry: string; anchor: string; frames?: number; side?: "left" | "right"; hidesCape?: boolean }> = {
  wings: {
    anchor: "anchor_body",
    geometry: geometry("wings", [
      { name: "anchor_body", pivot: [0, 24, 0] },
      { name: "left_wing", parent: "anchor_body", pivot: [1, 21, 2], rotation: [0, -25, 0], cubes: [{ origin: [1, 9, 2], size: [14, 15, 1], uv: [0, 0] }, { origin: [13, 14, 2], size: [5, 12, 1], uv: [0, 16] }] },
      { name: "right_wing", parent: "anchor_body", pivot: [-1, 21, 2], rotation: [0, 25, 0], cubes: [{ origin: [-15, 9, 2], size: [14, 15, 1], uv: [0, 0] }, { origin: [-18, 14, 2], size: [5, 12, 1], uv: [0, 16] }] },
    ]),
    hidesCape: true,
  },
  headwear: {
    anchor: "anchor_head",
    geometry: geometry("crown", [
      {
        name: "anchor_head",
        pivot: [0, 24, 0],
        cubes: [
          { origin: [-4.5, 32, -4.5], size: [9, 2, 9], uv: [0, 0] },
          { origin: [-4.5, 34, -4.5], size: [1, 2, 1], uv: [0, 12] },
          { origin: [3.5, 34, -4.5], size: [1, 2, 1], uv: [0, 12] },
          { origin: [-0.5, 34, -4.5], size: [1, 3, 1], uv: [0, 12] },
          { origin: [-4.5, 34, 3.5], size: [1, 2, 1], uv: [0, 12] },
          { origin: [3.5, 34, 3.5], size: [1, 2, 1], uv: [0, 12] },
        ],
      },
    ]),
  },
  bodywear: {
    anchor: "anchor_body",
    geometry: geometry("scarf", [
      { name: "anchor_body", pivot: [0, 24, 0], cubes: [{ origin: [-4.5, 21, -2.5], size: [9, 3, 5], uv: [0, 0], inflate: 0.2 }, { origin: [1, 13, -3], size: [3, 8, 1], uv: [0, 10] }] },
    ]),
  },
  aura: {
    anchor: "anchor_body",
    frames: 4,
    geometry: geometry(
      "ring",
      [
        {
          name: "anchor_body",
          pivot: [0, 0, 0],
          cubes: Array.from({ length: 12 }, (_, i) => {
            const a = (i / 12) * Math.PI * 2;
            return { origin: [Math.cos(a) * 11 - 1, 1 + (i % 3) * 0.5, Math.sin(a) * 11 - 1], size: [2, 2, 2], uv: [0, 0], rotation: [0, (-a * 180) / Math.PI, 0], pivot: [Math.cos(a) * 11, 2, Math.sin(a) * 11] };
          }),
        },
      ],
      16,
      16,
    ),
  },
  pet: {
    anchor: "anchor_body",
    side: "right",
    geometry: geometry("fox", [
      {
        name: "anchor_body",
        pivot: [0, 24, 0],
        cubes: [
          { origin: [-8, 24, -2], size: [4, 3, 5], uv: [0, 0] },
          { origin: [-7.5, 27, -3], size: [3, 3, 3], uv: [0, 8] },
          { origin: [-7.5, 30, -2], size: [1, 1, 1], uv: [12, 8] },
          { origin: [-5.5, 30, -2], size: [1, 1, 1], uv: [12, 8] },
          { origin: [-7, 24.5, 3], size: [2, 2, 3], uv: [12, 0] },
        ],
      },
    ]),
  },
  shield: {
    anchor: "anchor_left_arm",
    geometry: geometry("shield", [{ name: "anchor_left_arm", pivot: [5, 22, 0], cubes: [{ origin: [8, 10, -5], size: [1, 12, 10], uv: [0, 0] }] }]),
  },
  gloves: {
    anchor: "anchor_right_arm",
    geometry: geometry("gloves", [
      { name: "anchor_right_arm", pivot: [-5, 22, 0], cubes: [{ origin: [-8, 12, -2], size: [4, 3, 4], uv: [0, 0], inflate: 0.3 }] },
      { name: "anchor_left_arm", pivot: [5, 22, 0], cubes: [{ origin: [4, 12, -2], size: [4, 3, 4], uv: [0, 0], inflate: 0.3 }] },
    ]),
  },
};

export const EMOTE_ANIMATION = JSON.stringify({ format_version: "1.8.0", animations: { "animation.flint.wave": { animation_length: 2, loop: false, bones: { right_arm: { rotation: { "0.0": [0, 0, 0], "1.0": [0, 0, -150], "2.0": [0, 0, 0] } } } } } });

/** The kinds' placeholder items: [kind, name, hue]. */
export const V2_ITEMS: [string, string, number][] = [
  ["wings", "Ember Wings", 18],
  ["wings", "Frost Wings", 200],
  ["headwear", "Copper Crown", 34],
  ["headwear", "Midnight Halo", 260],
  ["aura", "Solar Ring", 45],
  ["pet", "Pocket Fox", 22],
  ["shield", "Obsidian Shield", 280],
  ["gloves", "Velvet Gloves", 330],
  ["emote", "Wave", 190],
  ["emote", "Cheer", 50],
  ["emote", "Dab", 300],
];

const canvas = (w: number, h: number) => {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  return [c, c.getContext("2d")!] as const;
};

/** A placeholder texture: bands of the hue (a vertical strip of `frames` frames, each brighter). */
export function modelTexture(kind: string, hue: number, frames = 1): string {
  const size = MODELS[kind]?.geometry.includes('"texture_width":16') ? 16 : 32;
  const [c, g] = canvas(size, size * frames);
  for (let f = 0; f < frames; f++)
    for (let y = 0; y < size; y++)
      for (let x = 0; x < size; x += 2) {
        const wave = Math.sin((x + y + f * 6) / 5);
        g.fillStyle = `hsl(${hue} ${10 + wave * 4}% ${44 + wave * 8 + f * 6}%)`;
        g.fillRect(x, f * size + y, 2, 1);
      }
  return c.toDataURL("image/png");
}

const ICONS: Record<string, (g: CanvasRenderingContext2D) => void> = {
  wings: (g) => {
    g.beginPath();
    g.moveTo(256, 260);
    g.quadraticCurveTo(120, 120, 60, 170);
    g.quadraticCurveTo(110, 330, 256, 300);
    g.quadraticCurveTo(402, 330, 452, 170);
    g.quadraticCurveTo(392, 120, 256, 260);
    g.fill();
  },
  headwear: (g) => {
    g.beginPath();
    g.moveTo(120, 340);
    g.lineTo(110, 180);
    g.lineTo(190, 250);
    g.lineTo(256, 150);
    g.lineTo(322, 250);
    g.lineTo(402, 180);
    g.lineTo(392, 340);
    g.closePath();
    g.fill();
  },
  bodywear: (g) => {
    g.fillRect(140, 170, 232, 70);
    g.fillRect(290, 230, 60, 150);
  },
  aura: (g) => {
    for (let i = 0; i < 12; i++) {
      const a = (i / 12) * Math.PI * 2;
      g.fillRect(256 + Math.cos(a) * 150 - 18, 256 + Math.sin(a) * 90 - 18, 36, 36);
    }
  },
  pet: (g) => {
    g.fillRect(170, 250, 170, 110);
    g.fillRect(200, 160, 110, 100);
    g.fillRect(205, 130, 26, 34);
    g.fillRect(280, 130, 26, 34);
    g.fillRect(335, 280, 80, 40);
  },
  shield: (g) => {
    g.beginPath();
    g.moveTo(256, 110);
    g.lineTo(380, 160);
    g.lineTo(370, 300);
    g.quadraticCurveTo(330, 380, 256, 410);
    g.quadraticCurveTo(182, 380, 142, 300);
    g.lineTo(132, 160);
    g.closePath();
    g.fill();
  },
  gloves: (g) => {
    g.fillRect(120, 200, 110, 140);
    g.fillRect(282, 200, 110, 140);
    g.fillRect(110, 170, 30, 70);
    g.fillRect(372, 170, 30, 70);
  },
  emote: (g) => {
    // A blocky figure with one arm raised.
    g.fillRect(226, 120, 60, 60);
    g.fillRect(222, 190, 68, 110);
    g.fillRect(186, 196, 30, 96);
    g.save();
    g.translate(296, 200);
    g.rotate(-2.4);
    g.fillRect(0, 0, 30, 96);
    g.restore();
    g.fillRect(224, 306, 30, 100);
    g.fillRect(258, 306, 30, 100);
  },
};

/** A placeholder 512×512 thumbnail: the kind's shape in light grey on a dark tile. */
const thumbnails = new Map<string, string>();
export function thumbnail(kind: string, hue: number): string {
  const key = `${kind}:${hue % 36}`;
  const known = thumbnails.get(key);
  if (known) return known;
  const url = drawThumbnail(kind, hue);
  thumbnails.set(key, url);
  return url;
}

function drawThumbnail(kind: string, hue: number): string {
  const [c, g] = canvas(512, 512);
  // Flint's greys only: a flat dark tile and the shape in a light warm grey (the hue only tints it faintly).
  g.fillStyle = "#1b1a19";
  g.fillRect(0, 0, 512, 512);
  g.fillStyle = `hsl(${hue} 8% ${74 + (hue % 3) * 4}%)`;
  (ICONS[kind] ?? ICONS.pet)(g);
  return c.toDataURL("image/png");
}

// ---------- the skin changer ----------

type Saved = { id: string; name: string; source: "username" | "url"; origin: string; variant: "classic" | "slim"; legacy: boolean; addedAt: number };
const library = new Map<string, Saved[]>();
const pngs = new Map<string, string>();
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** A recognisable placeholder skin for a name: a coloured body, a face. */
function skinFor(seed: string): string {
  let hue = 0;
  for (const ch of seed) hue = (hue * 31 + ch.charCodeAt(0)) % 360;
  const [c, g] = canvas(64, 64);
  const fill = (x: number, y: number, w: number, h: number, color: string) => {
    g.fillStyle = color;
    g.fillRect(x, y, w, h);
  };
  const skin = `hsl(${(hue + 20) % 360} 35% 62%)`;
  fill(0, 0, 32, 16, skin); // head
  fill(8, 0, 16, 8, `hsl(${hue} 40% 25%)`); // hair top
  fill(8, 8, 8, 2, `hsl(${hue} 40% 25%)`); // fringe
  fill(9, 12, 2, 1, "#fff");
  fill(13, 12, 2, 1, "#fff");
  fill(10, 12, 1, 1, "#203050");
  fill(14, 12, 1, 1, "#203050");
  fill(11, 14, 2, 1, `hsl(${hue} 30% 35%)`);
  fill(16, 16, 24, 16, `hsl(${hue} 55% 45%)`); // body
  fill(40, 16, 16, 16, `hsl(${hue} 55% 40%)`); // right arm
  fill(32, 48, 16, 16, `hsl(${hue} 55% 40%)`); // left arm
  fill(0, 16, 16, 16, `hsl(${(hue + 200) % 360} 30% 28%)`); // right leg
  fill(16, 48, 16, 16, `hsl(${(hue + 200) % 360} 30% 28%)`); // left leg
  fill(44, 20, 4, 4, skin);
  fill(36, 52, 4, 4, skin);
  return c.toDataURL("image/png");
}

const hash = (text: string) => {
  let h = 0n;
  for (const ch of text) h = (h * 131n + BigInt(ch.charCodeAt(0))) % (1n << 250n);
  return h.toString(16).padStart(64, "0").slice(-64);
};

export async function skinLibraryCall(cmd: string, args: Record<string, any>): Promise<unknown> {
  const uuid = String(args.uuid);
  const list = library.get(uuid) ?? [];
  library.set(uuid, list);
  switch (cmd) {
    case "skin_library_list":
      return [...list];
    case "skin_library_add": {
      await sleep(500);
      const { from, value } = args.source as { from: "username" | "url"; value: string };
      if (from === "username") {
        if (/^nobody/i.test(value)) throw `No Minecraft player is called ${value}.`;
        const png = skinFor(value.toLowerCase());
        const id = hash(png);
        pngs.set(id, png);
        const skin: Saved = { id, name: value, source: "username", origin: value, variant: /alex|slim/i.test(value) ? "slim" : "classic", legacy: false, addedAt: Date.now() };
        library.set(uuid, [skin, ...list.filter((s) => s.id !== id)]);
        return skin;
      }
      if (!/\.png($|\?)/i.test(value) || /bad|wrong/i.test(value)) throw "That address isn't a Minecraft skin: Flint needs a 64×64 or 64×32 PNG.";
      const png = skinFor(value);
      const id = hash(png);
      pngs.set(id, png);
      const name = decodeURIComponent(value.split("/").pop()!.replace(/\.png.*$/i, "")) || new URL(value).host;
      const skin: Saved = { id, name, source: "url", origin: value, variant: "classic", legacy: false, addedAt: Date.now() };
      library.set(uuid, [skin, ...list.filter((s) => s.id !== id)]);
      return skin;
    }
    case "skin_library_remove":
      library.set(
        uuid,
        list.filter((s) => s.id !== args.id),
      );
      return library.get(uuid);
    case "skin_library_png": {
      const png = pngs.get(String(args.id));
      if (!png) throw "Flint doesn't have that skin any more.";
      return png;
    }
    case "skin_library_apply": {
      await sleep(700);
      const skin = list.find((s) => s.id === args.id);
      if (!skin) throw "Flint doesn't have that skin any more.";
      // A mock of Minecraft's skin API: nothing leaves the browser.
      const w = window as unknown as { __flintAppliedSkins?: unknown[] };
      (w.__flintAppliedSkins ??= []).push({ uuid, id: skin.id, variant: args.variant });
      return { name: "IconicDemon", variant: args.variant, capes: [] };
    }
  }
  throw new Error(`devMock: unknown command ${cmd}`);
}

/** The skin the active account wears after an Apply in the mock (devMock.ts `skin_texture`), or null. */
export function appliedSkin(uuid: string): { dataUrl: string; model: "classic" | "slim" } | null {
  const w = window as unknown as { __flintAppliedSkins?: { uuid: string; id: string; variant: "classic" | "slim" }[] };
  const last = [...(w.__flintAppliedSkins ?? [])].reverse().find((a) => a.uuid === uuid);
  const png = last ? pngs.get(last.id) : null;
  return last && png ? { dataUrl: png, model: last.variant } : null;
}
