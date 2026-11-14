// Browser-only development stand-in for the launcher's showcase commands (src-tauri/src/showcase), from the shared
// fixtures (showcaseFixtures.json, the Store's /api/v1/showcase/ contract). Never bundled into production builds (see
// devMock.ts).
//
// `?showcase=<set>` picks the slides: slides (the default: three fictional sponsors, one ending 20 minutes after the page
// opens), one, none, down (the Store can't be reached), slow (the slides after 5 seconds) or broken (no picture loads).
// Fixture times are moved to now. Each picture is drawn here on a canvas (a PNG, as the launcher would hand over).
import type { ShowcaseAnswer, ShowcaseSlide } from "./api";
import fixtures from "./showcaseFixtures.json";

type SetName = keyof typeof fixtures.sets;
type Raw = (typeof fixtures.sets.slides.slides)[number];
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** The slide as the launcher answers it (showcase/mod.rs `SlideView`), with its times moved to `opened`. */
function view(raw: Raw, shift: number): ShowcaseSlide {
  const time = (v: string | null) => (v ? Date.parse(v) + shift : null);
  const link = raw.link_url ? new URL(raw.link_url).hostname.replace(/^www\./, "") : null;
  return {
    id: raw.id,
    title: raw.title,
    alt: raw.alt || raw.title,
    width: raw.width,
    height: raw.height,
    imageKey: raw.image_url.split("/").pop()!.split(".")[0],
    linkHost: link,
    endsAt: time(raw.ends_at),
    weight: raw.weight,
  };
}

type Art = { hue: number; night: boolean; eyebrow: string; title: string; line: string; cta: string };
const ART: Record<string, Art> = {
  "6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b": { hue: 14, night: false, eyebrow: "PARTNER SERVER", title: "EMBERFALL SMP", line: "Season 4 opens Friday", cta: "play.emberfall.example" },
  "0a9b8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d": { hue: 168, night: true, eyebrow: "FLINT STORE", title: "AURORA CAPES", line: "Six new animated capes", cta: "Shop now" },
  "c4d5e6f7-a8b9-4c0d-9e1f-2a3b4c5d6e7f": { hue: 222, night: true, eyebrow: "GLASSWING GAMES", title: "BUILD WEEK", line: "Nine days, one lake, no rules", cta: "Join in" },
};

function random(seed: string) {
  let h = 2166136261;
  for (const c of seed) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
  return () => {
    h = Math.imul(h ^ (h >>> 15), 2246822507);
    h = Math.imul(h ^ (h >>> 13), 3266489909);
    return ((h ^= h >>> 16) >>> 0) / 4294967296;
  };
}

const hsl = (h: number, s: number, l: number, a = 1) => `hsla(${((h % 360) + 360) % 360},${s}%,${l}%,${a})`;

/** A 1200×400 promotion: a blocky sky and ridges in the slide's hue, its words on the left. */
async function paint(id: string): Promise<ArrayBuffer> {
  const art = ART[id] ?? { hue: 30, night: false, eyebrow: "SPONSOR", title: "FLINT", line: "A fictional promotion", cta: "Learn more" };
  const [w, h] = [1200, 400];
  const canvas = document.createElement("canvas");
  canvas.width = w;
  canvas.height = h;
  const g = canvas.getContext("2d")!;
  const rand = random(id);
  const sky = g.createLinearGradient(0, 0, 0, h);
  sky.addColorStop(0, hsl(art.hue + 200, 35, art.night ? 7 : 14));
  sky.addColorStop(0.6, hsl(art.hue, 45, art.night ? 18 : 30));
  sky.addColorStop(1, hsl(art.hue + 12, 60, art.night ? 26 : 44));
  g.fillStyle = sky;
  g.fillRect(0, 0, w, h);
  if (art.night) {
    for (let i = 0; i < 60; i++) {
      g.fillStyle = `rgba(255,255,255,${(0.25 + rand() * 0.6).toFixed(2)})`;
      g.fillRect(Math.round(rand() * w), Math.round(rand() * h * 0.55), 3, 3);
    }
  }
  const [gx, gy] = [w * (0.68 + rand() * 0.12), h * 0.36];
  const glow = g.createRadialGradient(gx, gy, 0, gx, gy, h * 0.9);
  glow.addColorStop(0, hsl(art.hue + 25, 95, 70, 0.85));
  glow.addColorStop(0.2, hsl(art.hue + 15, 90, 58, 0.35));
  glow.addColorStop(1, hsl(art.hue, 80, 40, 0));
  g.fillStyle = glow;
  g.fillRect(0, 0, w, h);
  const sun = art.night ? 56 : 72;
  g.fillStyle = hsl(art.hue + 35, 100, art.night ? 90 : 80, 0.92);
  g.fillRect(Math.round(gx - sun / 2), Math.round(gy - sun / 2), sun, sun);
  for (let layer = 0; layer < 3; layer++) {
    const step = 24 * (layer + 1);
    const base = h * (0.6 + layer * 0.12);
    const amp = h * 0.1 * (1 - layer * 0.2);
    let y = base;
    g.beginPath();
    g.moveTo(0, h);
    for (let x = 0; x <= w + step; x += step) {
      y = Math.max(base - amp, Math.min(base + amp, y + (rand() - 0.5) * amp * 0.7));
      const q = Math.round(y / (step / 2)) * (step / 2);
      g.lineTo(x, q);
      g.lineTo(x + step, q);
    }
    g.lineTo(w, h);
    g.closePath();
    g.fillStyle = hsl(art.hue + 200 - layer * 10, 30 - layer * 5, Math.max(4, 15 - layer * 5), 0.8 + layer * 0.08);
    g.fill();
  }
  const shade = g.createLinearGradient(0, 0, w * 0.62, 0);
  shade.addColorStop(0, "rgba(6,5,4,0.78)");
  shade.addColorStop(1, "rgba(6,5,4,0)");
  g.fillStyle = shade;
  g.fillRect(0, 0, w, h);
  g.fillStyle = "rgba(255,255,255,0.7)";
  g.font = "600 22px Inter, 'Segoe UI', system-ui, sans-serif";
  g.letterSpacing = "4px";
  g.fillText(art.eyebrow, 64, 118);
  g.letterSpacing = "1px";
  g.fillStyle = "#fff";
  g.font = "800 76px Inter, 'Segoe UI', system-ui, sans-serif";
  g.fillText(art.title, 60, 196);
  g.letterSpacing = "0px";
  g.fillStyle = "rgba(255,255,255,0.82)";
  g.font = "500 30px Inter, 'Segoe UI', system-ui, sans-serif";
  g.fillText(art.line, 64, 246);
  g.font = "650 22px Inter, 'Segoe UI', system-ui, sans-serif";
  const pill = g.measureText(art.cta).width + 44;
  g.fillStyle = "rgba(255,255,255,0.94)";
  g.beginPath();
  g.roundRect(64, 282, pill, 46, 23);
  g.fill();
  g.fillStyle = "#14110d";
  g.fillText(art.cta, 86, 312);
  const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("no PNG"))), "image/png"));
  return blob.arrayBuffer();
}

export function createShowcaseMock(params: URLSearchParams) {
  const choice = params.get("showcase") ?? "slides";
  const set: SetName = choice in fixtures.sets ? (choice as SetName) : "slides";
  // Times move with the page: the fixtures' "now" is when the page opened.
  const shift = Date.now() - Date.parse(fixtures.now);
  const slides = () => (fixtures.sets[set].slides as Raw[]).map((raw) => view(raw, shift)).filter((s) => s.endsAt == null || s.endsAt > Date.now());
  const find = (id: string) => slides().find((s) => s.id === id);

  return async function showcase(cmd: string, args: Record<string, any>): Promise<unknown> {
    switch (cmd) {
      case "showcase_slides":
        if (choice === "down") throw "Flint can't reach the Flint Store right now.";
        await sleep(choice === "slow" ? 5000 : 180);
        return { slides: slides(), rotateMs: fixtures.sets[set].rotate_ms } satisfies ShowcaseAnswer;
      case "showcase_image":
        if (!find(args.id)) throw "This slide isn't showing any more.";
        if (choice === "broken") throw "This slide's picture couldn't be loaded.";
        await sleep(120);
        return paint(args.id);
      case "showcase_open": {
        const slide = find(args.id);
        if (!slide?.linkHost) throw "This slide has no link.";
        console.info("[devMock] would open in the system browser:", (fixtures.sets[set].slides as Raw[]).find((s) => s.id === args.id)?.link_url);
        return;
      }
      default:
        throw new Error(`devMock: unknown command ${cmd}`);
    }
  };
}
