// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { capeNotice, capeNoticeKey, LISTED, type CapeConflict, type Message } from "./capeConflict.ts";
import { LOCALES, translator } from "./i18n.ts";
import { requiredForms } from "./i18nCatalog.ts";

const en = translator("en", {});
const text = (m: Message | null) => (m ? en(m.key, m.vars) : null);
const conflict = (profileName: string, mods: string[], profileId = profileName.toLowerCase()): CapeConflict => ({ profileId, profileName, gameVersion: "1.21.11", mods });

test("one profile: one sentence naming the mod and the profile, and what to do", () => {
  const notice = capeNotice([conflict("Survival 1.21.11", ["Capes"])]);
  assert.equal(text(notice.title), "A cape mod is on");
  assert.equal(text(notice.body), "The Capes mod is on in your Survival 1.21.11 profile and can hide your Flint cape there. Turn that mod off to see your cape.");
  assert.deepEqual(notice.body.strong, ["mods", "profile"], "the mod and the profile are in bold");
  assert.deepEqual(notice.rows, []);
  assert.equal(notice.more, null);
  assert.equal(notice.outro, null);

  const two = capeNotice([conflict("Survival", ["Capes", "Essential"])]);
  assert.equal(text(two.title), "Cape mods are on");
  assert.equal(text(two.body), "The Capes and Essential mods are on in your Survival profile and can hide your Flint cape there. Turn those mods off to see your cape.");
});

test("several profiles: a list of each with its mods, then what to do", () => {
  const notice = capeNotice([conflict("Survival", ["Capes"]), conflict("PvP", ["Capes", "MinecraftCapes"])]);
  assert.equal(text(notice.title), "Cape mods are on", "two different mods across the profiles");
  assert.equal(text(notice.body), "Mods that show their own capes are on in these profiles and can hide your Flint cape there:");
  assert.deepEqual(notice.rows.map((r) => [r.profile, r.mods]), [["Survival", "Capes"], ["PvP", "Capes and MinecraftCapes"]]);
  assert.equal(notice.more, null);
  assert.equal(text(notice.outro), "Turn those mods off to see your cape.");

  const same = capeNotice([conflict("Survival", ["Capes"]), conflict("PvP", ["Capes"])]);
  assert.equal(text(same.title), "A cape mod is on", "the same mod in two profiles");
  assert.equal(text(same.outro), "Turn that mod off to see your cape.");
});

test("past four profiles, the rest are counted", () => {
  const many = ["A", "B", "C", "D", "E", "F"].map((name) => conflict(name, ["Essential"]));
  const notice = capeNotice(many);
  assert.equal(notice.rows.length, LISTED);
  assert.deepEqual(notice.rows.map((r) => r.profile), ["A", "B", "C", "D"], "in the order given: the selected profile first");
  assert.equal(text(notice.more), "And 2 more profiles");
  assert.equal(text(capeNotice(many.slice(0, 5)).more), "And 1 more profile");
  assert.equal(capeNotice(many.slice(0, 4)).more, null);
});

test("the same mods in the same profiles are the same notice", () => {
  const a = [conflict("Survival", ["Capes"])];
  assert.equal(capeNoticeKey(a), capeNoticeKey([conflict("Survival", ["Capes"])]));
  assert.notEqual(capeNoticeKey(a), capeNoticeKey([conflict("Survival", ["Capes", "Essential"])]));
  assert.notEqual(capeNoticeKey(a), capeNoticeKey([...a, conflict("PvP", ["Capes"])]));
});

test("every language has the notice, with every plural form it needs", () => {
  const english = JSON.parse(readFileSync(new URL("../locales/en.json", import.meta.url), "utf8")) as Record<string, string>;
  const ids = [...new Set(Object.keys(english).filter((k) => k.startsWith("store.capeConflict.")).map((k) => k.replace(/\.(one|other)$/, "")))];
  assert.deepEqual(ids.sort(), ["store.capeConflict.body", "store.capeConflict.more", "store.capeConflict.ok", "store.capeConflict.several", "store.capeConflict.title", "store.capeConflict.turnOff"]);
  for (const locale of LOCALES.filter((l) => l !== "en")) {
    const catalog = JSON.parse(readFileSync(new URL(`../locales/${locale}.json`, import.meta.url), "utf8")) as Record<string, string>;
    for (const id of ids) {
      const plural = `${id}.other` in english;
      const keys = plural ? requiredForms(locale).map((f) => `${id}.${f}`) : [id];
      for (const key of keys) assert.ok(catalog[key]?.trim(), `${locale}: ${key} is missing`);
    }
    // Translated, not English left in place (OK is OK in several languages).
    assert.notEqual(catalog["store.capeConflict.several"], english["store.capeConflict.several"], `${locale}: store.capeConflict.several is English`);
    const body = translator(locale, catalog)("store.capeConflict.body", { count: 1, mods: "Capes", profile: "Survival" });
    assert.ok(body.includes("Capes") && body.includes("Survival"), `${locale}: the body names the mod and the profile`);
  }
});
