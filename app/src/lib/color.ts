/**
 * Colours as the profile icon picker works with them: `#rrggbb` strings, hue / saturation / value (the picker's square and
 * hue bar), and the two shades a tile is drawn with.
 */

/** Hue in degrees (0 to 360), saturation and value from 0 to 1. */
export type Hsv = { h: number; s: number; v: number };

/** A named tile's colour, for the picker to start from (the middle of its two shades in profiles.css). */
export const NAMED_TILES: Record<string, string> = {
  ember: "#ec7834",
  amber: "#f0b744",
  crimson: "#dc4652",
  rose: "#ec71af",
  violet: "#9169ec",
  ocean: "#528dea",
  teal: "#47beb1",
  emerald: "#54c078",
  slate: "#737e8f",
  night: "#272a33",
};

const clamp01 = (n: number) => Math.min(1, Math.max(0, n));
const byte = (n: number) => Math.round(Math.min(255, Math.max(0, n)));

/** `#abc`, `abc`, `#aabbcc` or `AABBCC` as `#aabbcc`; null for anything else. */
export function normalizeHex(text: string): string | null {
  const t = text.trim().replace(/^#/, "").toLowerCase();
  if (/^[0-9a-f]{3}$/.test(t)) return `#${[...t].map((c) => c + c).join("")}`;
  if (/^[0-9a-f]{6}$/.test(t)) return `#${t}`;
  return null;
}

export function hexToRgb(hex: string): [number, number, number] | null {
  const n = normalizeHex(hex);
  if (!n) return null;
  return [parseInt(n.slice(1, 3), 16), parseInt(n.slice(3, 5), 16), parseInt(n.slice(5, 7), 16)];
}

export function rgbToHex(r: number, g: number, b: number): string {
  return `#${[r, g, b].map((c) => byte(c).toString(16).padStart(2, "0")).join("")}`;
}

export function hexToHsv(hex: string): Hsv | null {
  const rgb = hexToRgb(hex);
  if (!rgb) return null;
  const [r, g, b] = rgb.map((c) => c / 255);
  const max = Math.max(r, g, b);
  const d = max - Math.min(r, g, b);
  let h = 0;
  if (d > 0) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60;
    if (h < 0) h += 360;
  }
  return { h, s: max === 0 ? 0 : d / max, v: max };
}

export function hsvToHex({ h, s, v }: Hsv): string {
  const hue = (((h % 360) + 360) % 360) / 60;
  const sat = clamp01(s);
  const val = clamp01(v);
  const c = val * sat;
  const x = c * (1 - Math.abs((hue % 2) - 1));
  const [r, g, b] = hue < 1 ? [c, x, 0] : hue < 2 ? [x, c, 0] : hue < 3 ? [0, c, x] : hue < 4 ? [0, x, c] : hue < 5 ? [x, 0, c] : [c, 0, x];
  const m = val - c;
  return rgbToHex((r + m) * 255, (g + m) * 255, (b + m) * 255);
}

function mix(hex: string, toward: [number, number, number], amount: number): string {
  const rgb = hexToRgb(hex) ?? [0, 0, 0];
  return rgbToHex(...(rgb.map((c, i) => c + (toward[i] - c) * amount) as [number, number, number]));
}

/** A tile's two shades from one colour: lighter at its top left, deeper at its bottom right. */
export function tileShades(hex: string): [string, string] {
  return [mix(hex, [255, 255, 255], 0.18), mix(hex, [0, 0, 0], 0.24)];
}

/** The colour the picker shows for a tile: a picked `#rrggbb`, or a named tile's colour. */
export function tileColor(color: string): string {
  return normalizeHex(color) ?? NAMED_TILES[color] ?? NAMED_TILES.ember;
}
