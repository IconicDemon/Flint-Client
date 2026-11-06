/*
 * Numbers, sizes, dates and ages in the interface language (lib/i18n.ts). English keeps Flint's own
 * compact forms ("4.2 MB", "5 min ago", "3 mo ago"); other languages use the browser's Intl formats for
 * their locale ("4,2 МБ", "vor 5 Min.").
 */
import { currentLocale, formatLocale, t } from "./i18n.ts";

const formats = new Map<string, Intl.NumberFormat>();
function numberFormat(options: Intl.NumberFormatOptions = {}): Intl.NumberFormat {
  const locale = formatLocale();
  const id = `${locale} ${JSON.stringify(options)}`;
  let format = formats.get(id);
  if (!format) formats.set(id, (format = new Intl.NumberFormat(locale, options)));
  return format;
}

const SIZE_UNITS = { kilobyte: "KB", megabyte: "MB", gigabyte: "GB" } as const;

function size(value: number, unit: keyof typeof SIZE_UNITS, digits: number): string {
  if (currentLocale() === "en") return `${value.toFixed(digits)} ${SIZE_UNITS[unit]}`;
  return numberFormat({ style: "unit", unit, minimumFractionDigits: digits, maximumFractionDigits: digits }).format(value);
}

export function bytes(n?: number | null): string {
  if (!n || n <= 0) return size(0, "megabyte", 0);
  if (n < 1024 * 1024) return size(Math.max(1, Math.round(n / 1024)), "kilobyte", 0);
  if (n < 1024 ** 3) return size(n / 1024 ** 2, "megabyte", n < 10 * 1024 ** 2 ? 1 : 0);
  return size(n / 1024 ** 3, "gigabyte", 2);
}

export function dashedUuid(uuid: string): string {
  if (uuid.length !== 32) return uuid;
  return `${uuid.slice(0, 8)}-${uuid.slice(8, 12)}-${uuid.slice(12, 16)}-${uuid.slice(16, 20)}-${uuid.slice(20)}`;
}

/** A whole number with the language's digit grouping: 12,480 / 12.480 / 12 480. */
export const number = (n: number) => numberFormat().format(n);

export const compact = (n: number) => numberFormat({ notation: "compact" }).format(n);

/** "A, B and C" in the interface language (`or` for alternatives). English writes no serial comma. */
export function list(items: string[], type: "and" | "or" = "and"): string {
  const locale = currentLocale() === "en" ? "en-GB" : formatLocale();
  return new Intl.ListFormat(locale, { type: type === "and" ? "conjunction" : "disjunction" }).format(items);
}

/** Accepts an ISO string or Unix seconds. */
export function shortDate(value?: string | number | null): string {
  if (!value) return "";
  const date = new Date(typeof value === "number" ? value * 1000 : value);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleDateString(formatLocale(), { month: "short", day: "numeric", year: "numeric" });
}

function relative(value: number, unit: Intl.RelativeTimeFormatUnit, style: Intl.RelativeTimeFormatStyle): string {
  return new Intl.RelativeTimeFormat(formatLocale(), { numeric: "auto", style }).format(-value, unit);
}

/** When a profile was last played, from Unix seconds: "Just now", "5 min ago", "3 h ago", "2 d ago". */
export function relativeTime(seconds?: number | null): string {
  if (!seconds) return t("format.neverPlayed");
  const diff = Date.now() / 1000 - seconds;
  if (diff < 90) return t("format.justNow");
  const english = currentLocale() === "en";
  if (diff < 3600) return english ? `${Math.round(diff / 60)} min ago` : relative(Math.round(diff / 60), "minute", "short");
  if (diff < 86400) return english ? `${Math.round(diff / 3600)} h ago` : relative(Math.round(diff / 3600), "hour", "short");
  return english ? `${Math.round(diff / 86400)} d ago` : relative(Math.round(diff / 86400), "day", "short");
}

/** Relative age of an ISO date, e.g. "3 days ago". */
export function ago(iso?: string | null): string {
  if (!iso) return "";
  const time = new Date(iso).getTime();
  if (Number.isNaN(time)) return "";
  const days = Math.floor((Date.now() - time) / 86_400_000);
  if (currentLocale() !== "en") {
    if (days < 31) return relative(days, "day", "long");
    return days < 365 ? relative(Math.round(days / 30), "month", "long") : relative(Math.round(days / 365), "year", "long");
  }
  if (days < 1) return "today";
  if (days < 2) return "yesterday";
  if (days < 31) return `${days} days ago`;
  if (days < 365) return `${Math.round(days / 30)} mo ago`;
  return `${Math.round(days / 365)} yr ago`;
}
