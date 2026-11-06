/*
 * The launcher's interface language. English (locales/en.json) is bundled and backs every key; the other
 * catalogs load on demand, one chunk per language, and whatever a catalog doesn't translate shows in
 * English. Only Flint's own interface changes: Minecraft's language and mods' translations are never
 * touched, and text the launcher backend sends (job stages, error details) stays English.
 *
 * Keys are dotted and grouped by area (`import.launchers.title`). A plural message has one key per
 * CLDR category (`profiles.count.one`, `.other`, plus `.few` / `.many` where the language has them) and
 * is asked for by its base with a numeric `count`. `{name}` placeholders are filled from `vars`.
 *
 * Components call `useT()` so a language change re-renders them; code outside React (toasts built in
 * lib/, labels for jobs) calls `t` at the moment it needs the text. Never call either at module top
 * level: that text would stay in the language the app started with.
 */
import { createElement, Fragment, useSyncExternalStore, type ReactNode } from "react";
import en from "../locales/en.json" with { type: "json" };

type English = typeof en;
export type MessageKey = keyof English;
/** A plural message's base: `profiles.count` for `profiles.count.one` / `.other`. */
export type PluralKey = { [K in MessageKey]: K extends `${infer Base}.other` ? Base : never }[MessageKey];
export type Key = MessageKey | PluralKey;
export type Vars = Record<string, string | number>;
export type Catalog = Record<string, string>;

export const LOCALES = ["en", "es", "pt-BR", "de", "fr", "it", "nl", "pl", "ru", "uk", "tr", "hi", "id", "vi", "ja", "ko", "zh-CN"] as const;
export type Locale = (typeof LOCALES)[number];
/** Settings → Language: "system" follows the computer's language when Flint has it, else English. */
export type LanguageSetting = "system" | Locale;

/** Each language as it names itself and in English; `region` only where the variant matters. */
export const LANGUAGES: { id: Locale; native: string; english: string; region?: { native: string; english: string } }[] = [
  { id: "en", native: "English", english: "English" },
  { id: "es", native: "Español", english: "Spanish" },
  { id: "pt-BR", native: "Português", english: "Portuguese", region: { native: "Brasil", english: "Brazil" } },
  { id: "de", native: "Deutsch", english: "German" },
  { id: "fr", native: "Français", english: "French" },
  { id: "it", native: "Italiano", english: "Italian" },
  { id: "nl", native: "Nederlands", english: "Dutch" },
  { id: "pl", native: "Polski", english: "Polish" },
  { id: "ru", native: "Русский", english: "Russian" },
  { id: "uk", native: "Українська", english: "Ukrainian" },
  { id: "tr", native: "Türkçe", english: "Turkish" },
  { id: "hi", native: "हिन्दी", english: "Hindi" },
  { id: "id", native: "Bahasa Indonesia", english: "Indonesian" },
  { id: "vi", native: "Tiếng Việt", english: "Vietnamese" },
  { id: "ja", native: "日本語", english: "Japanese" },
  { id: "ko", native: "한국어", english: "Korean" },
  { id: "zh-CN", native: "中文", english: "Chinese", region: { native: "简体", english: "Simplified" } },
];

/**
 * One chunk per language, fetched only when it's chosen. (No `with { type: "json" }` here: the dev server
 * serves these as JavaScript modules, which a JSON import attribute would make the web view reject.)
 */
const CATALOGS: Record<Exclude<Locale, "en">, () => Promise<{ default: Catalog }>> = {
  es: () => import("../locales/es.json"),
  "pt-BR": () => import("../locales/pt-BR.json"),
  de: () => import("../locales/de.json"),
  fr: () => import("../locales/fr.json"),
  it: () => import("../locales/it.json"),
  nl: () => import("../locales/nl.json"),
  pl: () => import("../locales/pl.json"),
  ru: () => import("../locales/ru.json"),
  uk: () => import("../locales/uk.json"),
  tr: () => import("../locales/tr.json"),
  hi: () => import("../locales/hi.json"),
  id: () => import("../locales/id.json"),
  vi: () => import("../locales/vi.json"),
  ja: () => import("../locales/ja.json"),
  ko: () => import("../locales/ko.json"),
  "zh-CN": () => import("../locales/zh-CN.json"),
};

const ENGLISH: Catalog = en;

export const isLanguageSetting = (value: unknown): value is LanguageSetting =>
  value === "system" || (LOCALES as readonly unknown[]).includes(value);

const systemLanguages = (): readonly string[] =>
  typeof navigator === "undefined" ? [] : navigator.languages?.length ? navigator.languages : [navigator.language];

/**
 * The language a setting means. "system" takes the first of the computer's languages Flint has,
 * matching the region when it can (any Portuguese gets Brazilian Portuguese, the only one there is).
 */
export function resolveLanguage(setting: LanguageSetting, preferred: readonly string[] = systemLanguages()): Locale {
  if (setting !== "system") return setting;
  for (const tag of preferred) {
    const exact = LOCALES.find((l) => l.toLowerCase() === tag.toLowerCase());
    if (exact) return exact;
    const base = tag.split("-")[0].toLowerCase();
    const near = LOCALES.find((l) => l.split("-")[0] === base);
    if (near) return near;
  }
  return "en";
}

const pluralRules = new Map<string, Intl.PluralRules>();
function plural(locale: string, n: number): string {
  let rules = pluralRules.get(locale);
  if (!rules) pluralRules.set(locale, (rules = new Intl.PluralRules(locale)));
  return rules.select(n);
}

const warned = new Set<string>();

/** The message for a key in a catalog, falling back to English; plural messages pick their form by `count`. */
function lookup(locale: Locale, catalog: Catalog, key: string, count: unknown): string {
  if (`${key}.other` in ENGLISH) {
    const n = typeof count === "number" ? count : Number.NaN;
    const own = catalog[`${key}.${plural(locale, n)}`] ?? catalog[`${key}.other`];
    if (own !== undefined) return own;
    return ENGLISH[`${key}.${plural("en", n)}`] ?? ENGLISH[`${key}.other`];
  }
  const text = catalog[key] ?? ENGLISH[key];
  if (text !== undefined) return text;
  if (!warned.has(key)) {
    warned.add(key);
    console.warn(`Missing text for "${key}"`);
  }
  return key;
}

const counts = new Map<string, Intl.NumberFormat>();
/** `{count}` gets the language's digit grouping (12,480 / 12 480); other numbers go in as they are. */
function countText(n: number): string {
  const locale = formatLocale();
  let format = counts.get(locale);
  if (!format) counts.set(locale, (format = new Intl.NumberFormat(locale)));
  return format.format(n);
}

const fill = (text: string, vars?: Vars) =>
  vars
    ? text.replace(/\{(\w+)\}/g, (whole, name: string) => {
        if (!(name in vars)) return whole;
        const value = vars[name];
        return name === "count" && typeof value === "number" ? countText(value) : String(value);
      })
    : text;

/** `t(key, vars)`: the text in the current language. `t.rich` lets placeholders be React nodes (a name in <strong>). */
export type Translate = ((key: Key, vars?: Vars) => string) & {
  rich: (key: Key, vars: Record<string, ReactNode>) => ReactNode;
  locale: Locale;
};

/** A translator over one catalog, with English behind it (the app uses `t` / `useT`; tests use this directly). */
export function translator(locale: Locale, catalog: Catalog): Translate {
  const translate = (key: Key, vars?: Vars) => fill(lookup(locale, catalog, key, vars?.count), vars);
  const rich = (key: Key, vars: Record<string, ReactNode>): ReactNode => {
    const parts = lookup(locale, catalog, key, vars.count).split(/(\{\w+\})/);
    return parts.map((part, i) => {
      const name = /^\{(\w+)\}$/.exec(part)?.[1];
      const value = name !== undefined && name in vars ? vars[name] : part;
      return createElement(Fragment, { key: i }, name === "count" && typeof value === "number" ? countText(value) : value);
    });
  };
  return Object.assign(translate, { rich, locale });
}

let current = translator("en", {});
/** The loaded catalog behind `current`. */
let loaded: Catalog = {};
const listeners = new Set<() => void>();

/** The text for a key in the current language. Outside components only: components use `useT()`. */
export const t = Object.defineProperties((key: Key, vars?: Vars) => current(key, vars), {
  rich: { value: (key: Key, vars: Record<string, ReactNode>) => current.rich(key, vars) },
  locale: { get: () => current.locale },
}) as Translate;

export const currentLocale = (): Locale => current.locale;

/** Text as search compares it: lowercase, accents dropped, so "version" finds "versión" and "е" finds "ё". */
export const searchable = (text: string) => text.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();

/**
 * The locale for numbers and dates: the computer's own when it speaks the interface language (an
 * English interface on a British system writes 2 Nov 2026), else the interface language.
 */
export function formatLocale(): string {
  const system = systemLanguages()[0];
  return system && system.split("-")[0].toLowerCase() === current.locale.split("-")[0] ? system : current.locale;
}

let requested = 0;

/** A language's catalog as the app loads it: its lazy chunk. (The Node tests pass a loader that reads the file.) */
const loadCatalog = async (locale: Exclude<Locale, "en">): Promise<Catalog> => (await CATALOGS[locale]()).default;

/** Switches the interface language; the catalog loads first, so the old language stays until the new one is ready. */
export async function setLanguage(setting: LanguageSetting, load = loadCatalog): Promise<void> {
  const locale = resolveLanguage(setting);
  const ticket = ++requested;
  let catalog: Catalog = {};
  if (locale !== "en") {
    try {
      catalog = await load(locale);
    } catch (error) {
      console.error(`Couldn't load the ${locale} translation; showing English`, error);
      if (ticket === requested) apply("en", {});
      return;
    }
  }
  if (ticket === requested) apply(locale, catalog);
}

function apply(locale: Locale, catalog: Catalog) {
  if (locale === current.locale && catalog === loaded) return;
  loaded = catalog;
  current = translator(locale, catalog);
  if (typeof document !== "undefined") document.documentElement.lang = locale;
  for (const listener of listeners) listener();
}

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => void listeners.delete(listener);
};

/**
 * The translator for the current language. Its identity changes with the language, so memos and
 * callbacks that list it as a dependency recompute.
 */
export function useT(): Translate {
  return useSyncExternalStore(subscribe, () => current, () => current);
}
