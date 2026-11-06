// Development only (`vite` in a browser, src/lib/devMockPartners.ts): stand-in pictures for the partner fixtures
// (src/lib/partnerFixtures.json `media`), served at /__dev-partner-media/<sha256> the way the launcher serves real ones
// at flint-partner://localhost/<sha256>. Each is drawn here as an SVG scene (blocky ridges, a sky, a glow) from the
// listing's hue and motif, so the directory and Home look like they will with real artwork. Never part of a build.
import { readFileSync } from "node:fs";

const PREFIX = "/__dev-partner-media/";

function random(seed) {
  let h = 2166136261;
  for (const c of seed) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
  return () => {
    h = Math.imul(h ^ (h >>> 15), 2246822507);
    h = Math.imul(h ^ (h >>> 13), 3266489909);
    return ((h ^= h >>> 16) >>> 0) / 4294967296;
  };
}

/** A stepped ridge line (Minecraft terraces) across the picture, closed to the bottom. */
function ridge(rand, w, h, base, amp, step) {
  const pts = [];
  let y = base + (rand() - 0.5) * amp;
  for (let x = 0; x <= w + step; x += step) {
    y += (rand() - 0.5) * amp * 0.55;
    y = Math.max(base - amp, Math.min(base + amp, y));
    const q = Math.round(y / (step / 2)) * (step / 2);
    pts.push(`${x},${q}`, `${x + step},${q}`);
  }
  return `M0,${h} L${pts.join(" L")} L${w},${h} Z`;
}

const hsl = (h, s, l, a = 1) => `hsla(${((h % 360) + 360) % 360},${s}%,${l}%,${a})`;

function scene({ shape, hue, motif, slug }) {
  const [w, h] = shape === "library" ? [1600, 400] : [1600, 900];
  const rand = random(`${slug}:${shape}`);
  const night = motif === "lake" || motif === "peaks" || motif === "grid";
  const glowX = 0.62 + rand() * 0.25;
  const glowY = shape === "library" ? 0.35 : 0.32;
  const parts = [];
  parts.push(`<defs>
    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${hsl(hue + 210, 35, night ? 7 : 12)}"/><stop offset="0.55" stop-color="${hsl(hue, 45, night ? 18 : 30)}"/><stop offset="1" stop-color="${hsl(hue + 15, 60, night ? 28 : 46)}"/></linearGradient>
    <radialGradient id="glow" cx="${glowX}" cy="${glowY}" r="0.5"><stop offset="0" stop-color="${hsl(hue + 25, 95, 72, 0.95)}"/><stop offset="0.18" stop-color="${hsl(hue + 15, 90, 60, 0.45)}"/><stop offset="1" stop-color="${hsl(hue, 80, 40, 0)}"/></radialGradient>
    <linearGradient id="fade" x1="0" y1="0" x2="0" y2="1"><stop offset="0.5" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="0.45"/></linearGradient>
  </defs>`);
  parts.push(`<rect width="${w}" height="${h}" fill="url(#sky)"/>`);
  if (night) {
    for (let i = 0; i < 70; i++) parts.push(`<rect x="${Math.round(rand() * w)}" y="${Math.round(rand() * h * 0.5)}" width="4" height="4" fill="#fff" opacity="${(0.25 + rand() * 0.6).toFixed(2)}"/>`);
  }
  parts.push(`<rect width="${w}" height="${h}" fill="url(#glow)"/>`);
  const cell = shape === "library" ? 28 : 40;
  const sun = Math.round(cell * (night ? 2.2 : 3));
  parts.push(`<rect x="${Math.round(glowX * w - sun / 2)}" y="${Math.round(glowY * h - sun / 2)}" width="${sun}" height="${sun}" fill="${hsl(hue + 35, 100, night ? 92 : 82)}" opacity="0.9"/>`);
  const layers = motif === "islands" ? 2 : 3;
  for (let i = 0; i < layers; i++) {
    const base = h * (0.52 + i * 0.13);
    const amp = h * (motif === "peaks" || motif === "volcano" ? 0.2 : 0.09) * (1 - i * 0.2);
    parts.push(`<path d="${ridge(rand, w, h, base, amp, cell * (i + 1))}" fill="${hsl(hue + 200 - i * 10, 30 - i * 5, Math.max(4, 16 - i * 5))}" opacity="${0.75 + i * 0.12}"/>`);
  }
  if (motif === "volcano") {
    parts.push(`<path d="M${w * 0.28},${h * 0.62} L${w * 0.36},${h * 0.3} L${w * 0.4},${h * 0.3} L${w * 0.48},${h * 0.62} Z" fill="${hsl(hue + 190, 20, 8)}"/>`);
    parts.push(`<rect x="${w * 0.362}" y="${h * 0.3}" width="${w * 0.034}" height="${h * 0.32}" fill="${hsl(hue + 18, 100, 55)}" opacity="0.85"/>`);
  }
  if (motif === "islands") {
    for (let i = 0; i < 5; i++) {
      const x = rand() * w * 0.9;
      const y = h * (0.2 + rand() * 0.35);
      const s = cell * (2 + Math.round(rand() * 4));
      parts.push(`<path d="M${x},${y} h${s} v${cell / 2} h-${cell} v${cell / 2} h-${s - cell * 2} v-${cell / 2} h-${cell} Z" fill="${hsl(hue + 190, 25, 12)}"/><rect x="${x}" y="${y - cell / 4}" width="${s}" height="${cell / 4}" fill="${hsl(110, 45, 32)}"/>`);
    }
  }
  if (motif === "forest") {
    for (let i = 0; i < 26; i++) {
      const x = Math.round((rand() * w) / cell) * cell;
      const t = cell * (2 + Math.round(rand() * 3));
      const y = h * 0.62 + rand() * h * 0.2;
      parts.push(`<path d="M${x},${y} h${cell} v-${t} h-${cell} Z M${x - cell},${y - t + cell} h${cell * 3} v-${cell} h-${cell * 3} Z" fill="${hsl(hue + 170, 30, 9)}"/>`);
    }
  }
  if (motif === "grid" || motif === "forge") {
    for (let i = 0; i < 8; i++) {
      const x = w * 0.1 + i * w * 0.1;
      const top = h * (0.35 + rand() * 0.2);
      parts.push(`<rect x="${x}" y="${top}" width="${w * 0.06}" height="${h - top}" fill="${hsl(hue + 200, 20, 10)}" stroke="${hsl(hue, 60, 45, 0.5)}" stroke-width="2"/>`);
      for (let j = 0; j < 6; j++) parts.push(`<rect x="${x + 10}" y="${top + 16 + j * 26}" width="${w * 0.06 - 20}" height="8" fill="${hsl(hue + (j % 2 ? 0 : 40), 85, 60, 0.7)}"/>`);
    }
  }
  if (motif === "lake") parts.push(`<rect x="0" y="${h * 0.74}" width="${w}" height="${h * 0.26}" fill="${hsl(hue + 200, 40, 14, 0.85)}"/><rect x="${glowX * w - sun / 2}" y="${h * 0.76}" width="${sun}" height="${h * 0.2}" fill="${hsl(hue + 35, 100, 85, 0.25)}"/>`);
  parts.push(`<rect width="${w}" height="${h}" fill="url(#fade)"/>`);
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${parts.join("")}</svg>`;
}

function logo({ hue, slug, motif }) {
  const letters = slug.replace(/[^a-z]/g, "").slice(0, 1).toUpperCase() || "?";
  const glyph =
    motif === "volcano" || motif === "forge"
      ? `<path d="M128 46 C150 86 178 98 172 142 C168 176 146 196 128 196 C110 196 88 176 84 142 C80 112 102 96 110 70 C118 92 130 96 128 46Z" fill="#fff" opacity="0.92"/>`
      : `<text x="128" y="160" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif" font-weight="800" font-size="118" fill="#fff">${letters}</text>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${hsl(hue + 20, 75, 55)}"/><stop offset="1" stop-color="${hsl(hue - 20, 70, 28)}"/></linearGradient></defs><rect width="256" height="256" rx="56" fill="url(#g)"/><rect x="10" y="10" width="236" height="236" rx="48" fill="none" stroke="#fff" stroke-opacity="0.18" stroke-width="4"/>${glyph}</svg>`;
}

export function partnerMediaPlugin(fixtures) {
  return {
    name: "flint-dev-partner-media",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (!req.url?.startsWith(PREFIX)) return next();
        const sha = req.url.slice(PREFIX.length).split("?")[0];
        const media = JSON.parse(readFileSync(fixtures, "utf8")).media[sha];
        if (!media) {
          res.statusCode = 404;
          return res.end();
        }
        res.setHeader("Content-Type", "image/svg+xml");
        res.setHeader("Cache-Control", "max-age=3600");
        res.end(media.shape === "logo" ? logo(media) : scene(media));
      });
    },
  };
}
