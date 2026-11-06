/**
 * Memory for Minecraft as Settings and the profile chip show it. The limits come from the launcher
 * (data.rs memory_limits: this computer's real memory, three quarters of it at most for the game), so
 * the slider, its labels and what a launch really gets are always the same numbers. Pure, so
 * `npm test` covers it.
 */
import { list } from "./format.ts";
import { currentLocale, formatLocale, t } from "./i18n.ts";

/** data.rs `MemoryLimits`, in MB. */
export type MemoryLimits = { totalMb: number | null; maxMb: number; recommendedMb: number; warnMb: number | null };

/** What the launcher allows when it can't read the memory size (data.rs UNKNOWN_MAX_MB). */
export const UNKNOWN_LIMITS: MemoryLimits = { totalMb: null, maxMb: 4096, recommendedMb: 3072, warnMb: null };

const GB = 1024;

const formats = new Map<string, Intl.NumberFormat>();
/** An amount in GB as the interface language writes it: English keeps "5.5 GB", others use their own ("5,5 ГБ"). */
function gigabytes(value: number, digits: number): string {
  if (currentLocale() === "en") return `${value.toFixed(digits)} GB`;
  const locale = formatLocale();
  const id = `${locale} ${digits}`;
  let format = formats.get(id);
  if (!format) formats.set(id, (format = new Intl.NumberFormat(locale, { style: "unit", unit: "gigabyte", minimumFractionDigits: digits, maximumFractionDigits: digits })));
  return format.format(value);
}

/** "5.5 GB": the slider's and the chip's label. */
export const gbLabel = (mb: number) => gigabytes(mb / GB, 1);

/** "8 GB": installed memory as it is sold (7.9 GB usable still reads 8 GB). */
export const machineLabel = (mb: number) => gigabytes(Math.round(mb / GB), 0);

const plain = new Map<string, Intl.NumberFormat>();
/** The number a memory field shows for `mb`, in GB with one decimal as the language writes it: "6.5", German "6,5". */
export function memoryInputText(mb: number): string {
  const value = mb / GB;
  if (currentLocale() === "en") return value.toFixed(1);
  const locale = formatLocale();
  let format = plain.get(locale);
  if (!format) plain.set(locale, (format = new Intl.NumberFormat(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1, useGrouping: false })));
  return format.format(value);
}

/** "GB" as the language writes it beside a number ("ГБ" in Russian), for the field's unit. */
export function memoryUnit(): string {
  if (currentLocale() === "en") return "GB";
  const parts = new Intl.NumberFormat(formatLocale(), { style: "unit", unit: "gigabyte" }).formatToParts(1);
  return parts.find((part) => part.type === "unit")?.value.trim() || "GB";
}

/** Units a player may type after the number. Anything else isn't a size. */
const MB_UNITS = new Set(["m", "mb", "mib", "м", "мб"]);
const GB_UNITS = new Set(["g", "gb", "gib", "г", "гб"]);
/** A bare number from here up is MB ("4096"), as other launchers ask for it; below, GB ("6", "6.5"). */
export const BARE_MB_FROM = 256;

/** `adjusted`: the value the slider takes differs from what was typed: `rounded` to its step, or held at its `min` or `max`. */
export type MemoryEntry = { mb: number; adjusted: "rounded" | "min" | "max" | null };

/**
 * What a player typed in a memory field, as the slider beside it would set it: "6", "6.5" and "6,5" are GB, "6 GB" and
 * "6144 MB" say their unit, a bare number from 256 up is MB. The result is on the slider's steps (from `min`, `step` MB
 * apart) and between its ends. Null when the text isn't a size: then nothing changes.
 */
export function parseMemoryInput(text: string, { min, max, step }: { min: number; max: number; step: number }, unit = "GB"): MemoryEntry | null {
  const match = /^(\d+(?:[.,]\d*)?|[.,]\d+)\s*([^\s\d]*)$/u.exec(text.trim().toLowerCase());
  if (!match) return null;
  const value = Number(match[1].replace(",", "."));
  const suffix = match[2];
  let megabytes: boolean;
  if (!suffix) megabytes = value >= BARE_MB_FROM;
  else if (MB_UNITS.has(suffix)) megabytes = true;
  else if (GB_UNITS.has(suffix) || suffix === unit.toLowerCase().replace(/\s+/g, "")) megabytes = false;
  else return null;
  if (!Number.isFinite(value) || value <= 0) return null;
  const mb = megabytes ? value : value * GB;
  if (mb < min) return { mb: min, adjusted: "min" };
  if (mb > max) return { mb: max, adjusted: "max" };
  // The nearer of the steps on either side; a limit between two steps counts as one.
  const size = step > 0 ? step : 1;
  const lower = min + Math.floor((mb - min) / size) * size;
  const upper = Math.min(max, lower + size);
  const stepped = mb - lower < upper - mb ? lower : upper;
  return { mb: stepped, adjusted: Math.abs(stepped - mb) >= 0.5 ? "rounded" : null };
}

/** A saved allocation as this computer runs it: at least 1 GB and never above its limit. */
export function effectiveMemory(savedMb: number, limits: MemoryLimits): number {
  return Math.min(limits.maxMb, Math.max(1024, Math.round(savedMb)));
}

/** The memory setting's short description (data.rs memory_limits in words). */
export function memoryDescription(limits: MemoryLimits): string {
  if (limits.totalMb == null) return t("settings.java.memory.describeUnknown", { max: gbLabel(limits.maxMb), suggested: machineLabel(3072) });
  return t("settings.java.memory.describe", { total: machineLabel(limits.totalMb), max: gbLabel(limits.maxMb), recommended: machineLabel(limits.recommendedMb) });
}

/** Settings → Java and memory: the range that suits most profiles, in words, and why more isn't faster. */
export function memoryAdvice(limits: MemoryLimits): string {
  const rec = machineLabel(limits.recommendedMb);
  const vars = {
    range: limits.recommendedMb > 2048 ? t("settings.java.memory.range", { from: 2, to: rec }) : rec,
    max: gbLabel(limits.maxMb),
    vanilla: machineLabel(2048),
    recommended: rec,
  };
  if (limits.totalMb == null) return t("settings.java.memory.adviceUnknown", vars);
  return t("settings.java.memory.advice", { ...vars, total: machineLabel(limits.totalMb) });
}

/** The profiles that use their own memory (Edit profile), which takes the place of this setting for them. */
export function overridesLine(profiles: { name: string; memoryMb?: number | null }[]): string | null {
  const own = profiles.filter((p) => p.memoryMb != null);
  if (own.length === 0) return null;
  if (own.length === 1) return t("settings.java.memory.overrides.single", { name: own[0].name, size: gbLabel(own[0].memoryMb!) });
  const shown = own.slice(0, 3).map((p) => t("settings.java.memory.overrides.item", { name: p.name, size: gbLabel(p.memoryMb!) }));
  if (own.length > 3) shown.push(t("settings.java.memory.overrides.more", { count: own.length - 3 }));
  return t("settings.java.memory.overrides.several", { profiles: list(shown) });
}

/** The launcher's answer, or the safe limits when it gave none (an older launcher, or no reading). */
export function limitsFrom(info: { memory?: MemoryLimits | null } | null | undefined): MemoryLimits {
  const m = info?.memory;
  return m && Number.isFinite(m.maxMb) && m.maxMb >= 1024 ? m : UNKNOWN_LIMITS;
}
