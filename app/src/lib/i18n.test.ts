import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { test } from "node:test";
import en from "../locales/en.json" with { type: "json" };
import { bytes, list, relativeTime, shortDate } from "./format.ts";
import { LANGUAGES, LOCALES, resolveLanguage, searchable, setLanguage, t, translator, type Key } from "./i18n.ts";
import { allowedForms, coverageOf, messageIds, placeholders, pluralBase, requiredForms, type Messages } from "./i18nCatalog.ts";

const SRC = new URL("../", import.meta.url);
const ENGLISH: Messages = en;
const catalog = (locale: string): Messages => JSON.parse(readFileSync(new URL(`locales/${locale}.json`, SRC), "utf8"));
const OTHERS = LOCALES.filter((l) => l !== "en");
/** setLanguage's loader for Node: the catalog file itself (the app fetches its bundled chunk). */
const readCatalog = async (locale: string) => catalog(locale);

/** Every source file of the app (tests excluded) without its comments, as [path, code]. */
const sources = (readdirSync(SRC, { recursive: true }) as string[])
  .map((f) => f.replace(/\\/g, "/"))
  .filter((f) => /\.(ts|tsx)$/.test(f) && !f.endsWith(".test.ts"))
  .map((f) => [f, readFileSync(new URL(f, SRC), "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|\s)\/\/.*$/gm, "$1")] as const);

const isMessage = (id: string) => id in ENGLISH || `${id}.other` in ENGLISH;
const NAMESPACES = new Set(Object.keys(ENGLISH).map((k) => k.split(".")[0]));
/** String literals in code that are message keys: what t() is called with, and keys kept in tables. Host names aren't keys. */
function keysInCode(): { file: string; key: string }[] {
  const found: { file: string; key: string }[] = [];
  for (const [file, code] of sources) {
    for (const m of code.matchAll(/["'`]([a-z][a-zA-Z0-9]*(?:\.[a-zA-Z0-9]+)+)["'`]/g)) {
      if (NAMESPACES.has(m[1].split(".")[0]) && !/\.(com|net|org|io|gg)$/.test(m[1])) found.push({ file, key: m[1] });
    }
  }
  return found;
}

test("every key the code uses is in en.json, and t() is never given a built-up key", () => {
  const missing = keysInCode().filter(({ key }) => !isMessage(key));
  assert.deepEqual(missing, [], "keys used in code but missing from locales/en.json");
  for (const [file, text] of sources) {
    for (const m of text.matchAll(/\bt(?:\.rich)?\(\s*`[^`]*\$\{/g)) assert.fail(`${file}: t() with a template-literal key (${m[0]}…); keep keys whole so they can be checked`);
  }
});

test("every English message is used somewhere (no stale keys)", () => {
  const used = new Set(keysInCode().map(({ key }) => key));
  const unused = messageIds(ENGLISH).filter((id) => !used.has(id));
  assert.deepEqual(unused, [], "en.json messages no code refers to");
});

test("English is well formed: plain keys or one/other plurals with the same placeholders", () => {
  for (const [key, text] of Object.entries(ENGLISH)) {
    assert.ok(text.trim() && text === text.trim(), `${key}: empty or padded`);
    const base = pluralBase(key);
    if (!base) continue;
    assert.ok(!(base in ENGLISH), `${base}: both a message and a plural`);
    assert.ok(`${base}.one` in ENGLISH && `${base}.other` in ENGLISH, `${base}: English plurals need .one and .other`);
    assert.deepEqual(placeholders(text), placeholders(ENGLISH[`${base}.other`]), `${key}: placeholders differ from .other`);
  }
});

for (const locale of OTHERS) {
  test(`${locale}: only English keys, the same placeholders and the plural forms ${locale} needs`, () => {
    const messages = catalog(locale);
    const allowed = new Set(allowedForms(locale));
    for (const [key, text] of Object.entries(messages)) {
      assert.ok(typeof text === "string" && text.trim() && text === text.trim(), `${locale} ${key}: empty or padded`);
      if (key in ENGLISH) {
        assert.deepEqual(placeholders(text), placeholders(ENGLISH[key]), `${locale} ${key}: placeholders`);
        continue;
      }
      const base = pluralBase(key);
      assert.ok(base && `${base}.other` in ENGLISH, `${locale} ${key}: not an English key`);
      assert.ok(allowed.has(key.slice(base.length + 1) as never), `${locale} ${key}: ${locale} has no such plural form`);
      assert.deepEqual(placeholders(text), placeholders(ENGLISH[`${base}.other`]), `${locale} ${key}: placeholders`);
    }
    for (const id of messageIds(ENGLISH).filter((id) => `${id}.other` in ENGLISH)) {
      const given = requiredForms(locale).filter((f) => `${id}.${f}` in messages);
      // A plural is either untranslated (English shows) or has every form the language needs.
      if (given.length) assert.deepEqual(given, requiredForms(locale), `${locale} ${id}: plural forms`);
    }
  });
}

test("coverage counts messages, with a plural counted once it has every form", () => {
  const english = { "a.title": "Title", "a.count.one": "{count} item", "a.count.other": "{count} items", "a.body": "Body" };
  assert.deepEqual(coverageOf(english, english, "en"), { translated: 3, total: 3 });
  assert.deepEqual(coverageOf(english, { "a.title": "Titel", "a.count.one": "{count} Element" }, "de"), { translated: 1, total: 3 });
  const polish = { "a.count.one": "{count} element", "a.count.few": "{count} elementy", "a.count.many": "{count} elementów", "a.count.other": "{count} elementu" };
  assert.deepEqual(coverageOf(english, polish, "pl"), { translated: 1, total: 3 });
  assert.deepEqual(requiredForms("ru"), ["one", "few", "many", "other"]);
  assert.deepEqual(requiredForms("fr"), ["one", "other"], "French many is only for millions and falls back to other");
  for (const locale of LOCALES) {
    const { translated, total } = coverageOf(ENGLISH, locale === "en" ? ENGLISH : catalog(locale), locale);
    console.log(`  ${locale}: ${translated} of ${total} messages (${((translated / total) * 100).toFixed(1)}%)`);
  }
});

test("t fills placeholders, picks plural forms by the language's rules and falls back to English", () => {
  const ru = translator("ru", { "updates.dialog.submit.one": "Обновить {count} проект", "updates.dialog.submit.few": "Обновить {count} проекта", "updates.dialog.submit.many": "Обновить {count} проектов" });
  assert.equal(ru("updates.dialog.submit", { count: 1 }), "Обновить 1 проект");
  assert.equal(ru("updates.dialog.submit", { count: 3 }), "Обновить 3 проекта");
  assert.equal(ru("updates.dialog.submit", { count: 25 }), "Обновить 25 проектов");
  assert.equal(ru("updates.dialog.submit", { count: 21 }), "Обновить 21 проект");
  // Missing in the catalog: English, with English plural rules.
  assert.equal(ru("common.cancel"), "Cancel");
  const english = translator("en", {});
  assert.equal(english("updates.dialog.submit", { count: 1 }), ENGLISH["updates.dialog.submit.one"].replace("{count}", "1"));
  assert.equal(english("updates.dialog.submit", { count: 12480 }), ENGLISH["updates.dialog.submit.other"].replace("{count}", "12,480"));
  assert.equal(english("no.such.key" as Key), "no.such.key");
});

test("search ignores case and accents", () => {
  assert.equal(searchable("Versión Français Ёлка"), "version francais елка");
  assert.ok(searchable("Mémoire Java").includes(searchable("memoire")));
});

test("the system language picks the closest catalog, else English", () => {
  assert.equal(resolveLanguage("system", ["de-AT", "en-US"]), "de");
  assert.equal(resolveLanguage("system", ["pt-PT"]), "pt-BR");
  assert.equal(resolveLanguage("system", ["sv-SE", "fr-CA"]), "fr");
  assert.equal(resolveLanguage("system", ["th-TH"]), "en");
  assert.equal(resolveLanguage("system", ["ja-JP"]), "ja");
  assert.equal(resolveLanguage("system", ["hi-IN", "en-IN"]), "hi");
  assert.equal(resolveLanguage("system", ["zh-CN"]), "zh-CN");
  assert.equal(resolveLanguage("system", ["zh-Hans"]), "zh-CN");
  assert.equal(resolveLanguage("system", []), "en");
  assert.equal(resolveLanguage("pl", ["de"]), "pl");
  assert.deepEqual(LANGUAGES.map((l) => l.id), [...LOCALES]);
});

test("English formatting is unchanged; other languages format their own way", async () => {
  const now = Date.now() / 1000;
  assert.equal(bytes(0), "0 MB");
  assert.equal(bytes(500 * 1024), "500 KB");
  assert.equal(bytes(4.2 * 1024 ** 2), "4.2 MB");
  assert.equal(bytes(123 * 1024 ** 2), "123 MB");
  assert.equal(bytes(1.234 * 1024 ** 3), "1.23 GB");
  assert.equal(relativeTime(null), "Never played");
  assert.equal(relativeTime(now - 30), "Just now");
  assert.equal(relativeTime(now - 300), "5 min ago");
  assert.equal(relativeTime(now - 3 * 3600), "3 h ago");
  assert.equal(relativeTime(now - 2 * 86400), "2 d ago");
  assert.equal(list(["A", "B", "C"]), "A, B and C");
  try {
    await setLanguage("de", readCatalog);
    assert.equal(t.locale, "de");
    assert.equal(bytes(4.2 * 1024 ** 2), "4,2 MB");
    assert.equal(list(["A", "B", "C"]), "A, B und C");
    assert.match(shortDate("2026-11-02T12:00:00Z"), /2\.? Nov\.? 2026/);
    await setLanguage("ru", readCatalog);
    assert.equal(bytes(1.234 * 1024 ** 3), "1,23 ГБ");
    assert.equal(relativeTime(now - 300), "5 мин. назад");
  } finally {
    await setLanguage("en");
  }
});
