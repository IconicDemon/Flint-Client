import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

/*
 * One theme everywhere: a colour that should follow the player's theme comes from the tokens in styles/tokens.css
 * (--accent, --accent-rgb, --accent-hover, --accent-text, --logo-*, the surfaces), never from a literal. This test reads every
 * stylesheet and component and fails on a coloured (not grey) literal that isn't listed below with the reason it stays fixed:
 * a brand's own colour, a meaning (success, danger, a status), or a colour the player picks themselves.
 *
 * Adding a colour? Use a token. If it truly must not follow the theme, add it here with its reason.
 */

const SRC = fileURLToPath(new URL("..", import.meta.url));

/** Files whose colours are all data or the theme definitions themselves. */
const SKIP_FILES = new Set(["styles/tokens.css", "lib/settings.tsx", "lib/color.ts"]);

/** file → colours that stay as they are, and why. */
const ALLOWED: Record<string, { values: string[]; why: string }[]> = {
  "features/pages/ConnectedAccounts.tsx": [{ values: ["#5865f2"], why: "Discord's brand blurple" }],
  "styles/components.css": [{ values: ["#5865f2", "#4752c4", "#3c45a5", "#7983f5"], why: "Discord's brand button" }],
  "styles/pages.css": [
    { values: ["#5865f2"], why: "Discord's brand button" },
    {
      values: ["#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300", "#9085e9", "#6e6a64"],
      why: "storage chart: one fixed colour per kind of data",
    },
  ],
  "styles/flintai.css": [{ values: ["#d9a651"], why: "fallback of --warning (a meaning)" }],
  "styles/friends.css": [{ values: ["#3fbf7f", "#e0625a", "#e2a640"], why: "online, busy and away statuses" }],
  "styles/home.css": [{ values: ["rgba(255,140,60,0.35)"], why: "Flint news artwork: the flame's glow on its fire picture" }],
  "styles/library.css": [{ values: ["#1bd96a", "#f16436"], why: "Modrinth green and CurseForge orange (brands)" }],
  "styles/profiles.css": [
    { values: ["rgba(241,100,54,0.12)", "rgba(241,100,54,0.32)", "#f58a62", "#b8461e"], why: "the CurseForge source tag (brand; the deeper orange reads on Ice Cream)" },
    {
      values: [
        "#ff9a4d", "#d9561c", "#ffd36b", "#e09b1d", "#ff6b6b", "#b8203a", "#ff9ccf", "#d9468f", "#b593ff", "#6c3fd9",
        "#74b6ff", "#2f64d6", "#6fe3d3", "#1f9a8f", "#7ee39a", "#2a9c55", "#9aa6b8", "#4b5566", "#3a3f4b", "#14161b",
      ],
      why: "profile tile colours the player picks",
    },
    { values: ["#f00", "#ff0", "#0f0", "#0ff", "#00f", "#f0f"], why: "the colour picker's hue strip" },
  ],
  "styles/shell.css": [{ values: ["#c42b1c"], why: "Windows' own close-button red" }],
  "ui/Icon.tsx": [{ values: ["#f25022", "#7fba00", "#00a4ef", "#ffb900"], why: "Microsoft's logo" }],
};

function files(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) files(path, out);
    else if (/\.(css|tsx?)$/.test(entry.name) && !/\.test\.ts$/.test(entry.name) && !/^devMock/.test(entry.name)) out.push(path);
  }
  return out;
}

const LITERAL = /#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3})\b|rgba?\(\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}\s*(?:,\s*[\d.]+\s*)?\)|hsla?\(\s*\d[^)]*\)/gi;

/** [r, g, b] of a literal; null for one this test doesn't read (hsl: always reported). */
function rgb(literal: string): [number, number, number] | null {
  const hex = literal.match(/^#([0-9a-f]+)$/i)?.[1];
  if (hex) {
    const full = hex.length === 3 ? [...hex].map((c) => c + c).join("") : hex.slice(0, 6);
    return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16)) as [number, number, number];
  }
  const parts = literal.match(/\d+(?:\.\d+)?/g);
  return literal.startsWith("rgb") && parts ? [Number(parts[0]), Number(parts[1]), Number(parts[2])] : null;
}

/** Greys, black and white (and near-greys such as a warm charcoal) follow nothing and are fine anywhere. */
function coloured(literal: string): boolean {
  const c = rgb(literal);
  if (!c) return true;
  return Math.max(...c) - Math.min(...c) >= 24;
}

const normal = (literal: string) => literal.toLowerCase().replace(/\s+/g, "");

/** A literal's place, skipping comments (a colour named in a comment isn't drawn). */
function literalsIn(text: string): { value: string; line: number }[] {
  const stripped = text.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " ")).replace(/(^|[^:"'`])\/\/[^\n]*/g, (m, p) => p + " ".repeat(m.length - p.length));
  const found: { value: string; line: number }[] = [];
  for (const m of stripped.matchAll(LITERAL)) {
    found.push({ value: m[0], line: stripped.slice(0, m.index).split("\n").length });
  }
  return found;
}

test("colours that should follow the theme come from its tokens", () => {
  const problems: string[] = [];
  for (const path of files(SRC)) {
    const file = relative(SRC, path).replace(/\\/g, "/");
    if (SKIP_FILES.has(file)) continue;
    const allowed = new Set((ALLOWED[file] ?? []).flatMap((a) => a.values.map(normal)));
    for (const { value, line } of literalsIn(readFileSync(path, "utf8"))) {
      if (coloured(value) && !allowed.has(normal(value))) problems.push(`${file}:${line} ${value}`);
    }
  }
  assert.deepEqual(problems, [], "hard-coded colours: use the theme tokens (styles/tokens.css), or list a fixed one in themeColors.test.ts with its reason");
});

test("every allowed colour is still in use (the list doesn't go stale)", () => {
  for (const [file, groups] of Object.entries(ALLOWED)) {
    const text = readFileSync(join(SRC, file), "utf8");
    const present = new Set(literalsIn(text).map((l) => normal(l.value)));
    for (const value of groups.flatMap((g) => g.values)) {
      assert.ok(present.has(normal(value)), `${file}: ${value} is no longer used; take it off the list`);
    }
  }
});

test("the check catches an accent written by hand", () => {
  assert.ok(coloured("#cd8b3e"), "Orange's amber");
  assert.ok(coloured("rgba(146, 118, 186, 0.4)"), "Passion Fruit's violet");
  assert.ok(!coloured("#141414") && !coloured("rgba(255, 255, 255, 0.12)") && !coloured("#120a05"), "greys are fine");
  assert.deepEqual(literalsIn("a { color: #cd8b3e; } /* #ff0000 */").map((l) => l.value), ["#cd8b3e"]);
});
