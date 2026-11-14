import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

/*
 * No theme may have hard-to-read text: every theme's tokens (styles/tokens.css) are read and checked against WCAG AA.
 * Text and secondary text 4.5:1 on the page, a panel and a raised panel; hints (--text-3) 3:1 (large text and UI); the accent
 * as text 4.5:1 on the page and panels; the text on a primary fill 4.5:1 on its solid accent and on both ends of a gradient.
 */

const css = readFileSync(new URL("../styles/tokens.css", import.meta.url), "utf8");
/** The themes the app offers (settings.tsx THEMES, read as text: node can't load .tsx). */
const THEMES = [...readFileSync(new URL("./settings.tsx", import.meta.url), "utf8").split("export const THEMES")[1].split("];")[0].matchAll(/\{ id: "([a-z]+)", name: "[^"]+", note:/g)].map((m) => ({ id: m[1] }));

type RGB = [number, number, number];

function block(theme: string): string {
  const at = css.indexOf(`[data-theme="${theme}"]`);
  assert.ok(at >= 0, `${theme} has a block in tokens.css`);
  return css.slice(at, css.indexOf("}", at));
}

const rootBlock = (() => {
  const at = css.indexOf(":root {\n  --scrim");
  return css.slice(at, css.indexOf("}", at));
})();

function token(theme: string, name: string): string | null {
  const m = block(theme).match(new RegExp(`--${name}:\\s*([^;]+);`));
  return m ? m[1].trim() : null;
}

function hex(value: string): RGB {
  const h = value.replace("#", "");
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16)) as RGB;
}

const channels = (value: string): RGB => value.split(",").map((n) => Number(n.trim())) as RGB;

/** An ink alpha (`rgba(var(--ink-rgb), A)`) from the theme's block, else the shared one. */
function alpha(theme: string, name: string): number {
  const own = token(theme, name);
  const shared = rootBlock.match(new RegExp(`--${name}:\\s*([^;]+);`))?.[1];
  const value = own ?? shared ?? "";
  const a = value.match(/rgba\(var\(--ink-rgb\),\s*([\d.]+)\)/)?.[1];
  assert.ok(a, `${theme}: --${name} is the ink at an alpha`);
  return Number(a);
}

const over = (top: RGB, a: number, bottom: RGB): RGB => top.map((c, i) => c * a + bottom[i] * (1 - a)) as RGB;

function luminance(c: RGB): number {
  const [r, g, b] = c.map((v) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function contrast(a: RGB, b: RGB): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** The solid colours a primary fill shows: the accent, or each stop of its gradient. */
function fills(theme: string): RGB[] {
  const fill = token(theme, "accent-fill");
  const stops = fill?.match(/#[0-9a-f]{6}/gi);
  return stops?.length ? stops.map(hex) : [channels(token(theme, "accent-rgb")!)];
}

for (const { id } of THEMES) {
  test(`${id}: every text is readable (WCAG AA)`, () => {
    const bg = channels(token(id, "bg-rgb")!);
    const ink = channels(token(id, "ink-rgb")!);
    const panels: [string, RGB][] = [
      ["page", bg],
      ["panel", hex(token(id, "surface-1")!)],
      ["raised", hex(token(id, "surface-3")!)],
    ];
    const accentText = hex(token(id, "accent-text")!);
    const failures: string[] = [];
    const need = (what: string, ratio: number, min: number) => {
      if (ratio < min) failures.push(`${what}: ${ratio.toFixed(2)}:1 < ${min}:1`);
    };
    for (const [where, surface] of panels) {
      need(`text on ${where}`, contrast(over(ink, alpha(id, "text"), surface), surface), 4.5);
      need(`secondary text on ${where}`, contrast(over(ink, alpha(id, "text-2"), surface), surface), 4.5);
      need(`hints on ${where}`, contrast(over(ink, alpha(id, "text-3"), surface), surface), 3);
      need(`accent text on ${where}`, contrast(accentText, surface), 4.5);
    }
    const accentInk = hex(token(id, "accent-ink")!);
    for (const fill of fills(id)) need(`primary-button text on rgb(${fill})`, contrast(accentInk, fill), 4.5);
    assert.deepEqual(failures, []);
  });
}

test("the check itself", () => {
  assert.equal(THEMES.length, 6, "Orange, Ice Cream, Mint, Cherry, Passion Fruit, Blueberry");
  assert.equal(contrast([0, 0, 0], [255, 255, 255]).toFixed(0), "21");
  assert.equal(contrast([119, 119, 119], [255, 255, 255]) >= 4.47, true);
});
