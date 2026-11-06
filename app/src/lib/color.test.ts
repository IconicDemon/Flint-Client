import assert from "node:assert/strict";
import { test } from "node:test";
import { hexToHsv, hsvToHex, normalizeHex, tileColor, tileShades } from "./color.ts";

test("hex colours are read in any of the usual spellings", () => {
  assert.equal(normalizeHex("#ABCDEF"), "#abcdef");
  assert.equal(normalizeHex("abc"), "#aabbcc");
  assert.equal(normalizeHex(" #f80 "), "#ff8800");
  assert.equal(normalizeHex("#abcd"), null);
  assert.equal(normalizeHex("orange"), null);
});

test("a colour survives the picker's hue, saturation and value", () => {
  for (const hex of ["#ff0000", "#00ff00", "#0000ff", "#ec7834", "#272a33", "#ffffff", "#000000", "#7f7f7f"]) {
    assert.equal(hsvToHex(hexToHsv(hex)!), hex);
  }
  assert.deepEqual(hexToHsv("#ff0000"), { h: 0, s: 1, v: 1 });
  assert.equal(hsvToHex({ h: 360, s: 1, v: 1 }), "#ff0000", "a full turn is red again");
});

test("a tile is lighter at the top and deeper at the bottom", () => {
  const [top, bottom] = tileShades("#808080");
  assert.ok(hexToHsv(top)!.v > 0.5 && hexToHsv(bottom)!.v < 0.5);
  assert.equal(tileColor("ember"), "#ec7834", "a named tile starts the picker at its colour");
  assert.equal(tileColor("#123456"), "#123456");
  assert.equal(tileColor("unknown"), "#ec7834");
});
