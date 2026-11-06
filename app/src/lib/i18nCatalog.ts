/*
 * Catalog checks without React or the bundler: plural keys, placeholders and how much of the English
 * catalog a language translates. Shared by the i18n module, the build (vite.config.ts computes every
 * language's coverage from the catalog files) and the tests.
 */

export type Messages = Record<string, string>;

/** CLDR plural categories, the last segment of a plural message's keys (`profiles.count.few`). */
export const PLURAL_CATEGORIES = ["zero", "one", "two", "few", "many", "other"] as const;
export type PluralCategory = (typeof PLURAL_CATEGORIES)[number];

/** `profiles.count` for `profiles.count.one`; null for a key that isn't a plural form. */
export function pluralBase(key: string): string | null {
  const dot = key.lastIndexOf(".");
  return dot > 0 && (PLURAL_CATEGORIES as readonly string[]).includes(key.slice(dot + 1)) ? key.slice(0, dot) : null;
}

/** Every message of a catalog once: plain keys, and each plural message by its base. */
export function messageIds(catalog: Messages): string[] {
  const ids = new Set<string>();
  for (const key of Object.keys(catalog)) ids.add(pluralBase(key) ?? key);
  return [...ids];
}

/** The placeholder names in a message: `{name}` → name. */
export function placeholders(text: string): string[] {
  return [...new Set(Array.from(text.matchAll(/\{(\w+)\}/g), (m) => m[1]))].sort();
}

/**
 * The plural forms a language must give: every category whole numbers up to a thousand fall into
 * (Russian and Polish: one, few, many), plus `other`, the fallback. Categories only fractions or
 * millions reach (Spanish `many`) are optional and fall back to `other`.
 */
export function requiredForms(locale: string): PluralCategory[] {
  const rules = new Intl.PluralRules(locale);
  const forms = new Set<PluralCategory>(["other"]);
  for (let n = 0; n <= 1000; n++) forms.add(rules.select(n) as PluralCategory);
  return PLURAL_CATEGORIES.filter((c) => forms.has(c));
}

/** The forms a language may give at all. */
export const allowedForms = (locale: string) => new Intl.PluralRules(locale).resolvedOptions().pluralCategories as PluralCategory[];

/**
 * How much of English a catalog translates: messages with a translation over all English messages. A
 * plural message counts once it has every form its language needs.
 */
export function coverageOf(english: Messages, catalog: Messages, locale: string): { translated: number; total: number } {
  const ids = messageIds(english);
  const forms = requiredForms(locale);
  let translated = 0;
  for (const id of ids) {
    const plural = `${id}.other` in english;
    const done = plural ? forms.every((f) => !!catalog[`${id}.${f}`]) : !!catalog[id];
    if (done) translated++;
  }
  return { translated, total: ids.length };
}
