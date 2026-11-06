/*
 * How much of the interface each language translates, as the build counted it from the catalogs
 * (vite.config.ts). Kept apart from lib/i18n.ts, which the Node tests load without the bundler.
 */
import coverage from "virtual:i18n-coverage";
import type { Locale } from "./i18n";

export type LanguageCoverage = { translated: number; total: number; /** Whole percent, rounded down: 99.6 % shows as 99. */ percent: number; complete: boolean };

export function languageCoverage(locale: Locale): LanguageCoverage {
  const { translated, total } = coverage[locale] ?? { translated: 0, total: 0 };
  return { translated, total, percent: total ? Math.floor((translated / total) * 100) : 0, complete: total > 0 && translated === total };
}
