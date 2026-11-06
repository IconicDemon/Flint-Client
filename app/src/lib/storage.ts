// Settings → Storage: the bar, its legend, the cleanups and the download settings' ranges (pure, so `npm test` covers it).
import type { CleanupKind, StorageCategoryId, StorageOverview } from "./api";
import { t, type MessageKey } from "./i18n.ts";

/** The download settings' ranges and defaults (src-tauri/src/storage.rs checks the same). The defaults are what Flint did
 *  before they were settings. */
export const DOWNLOAD_LIMITS = {
  maxDownloads: { min: 1, max: 32, default: 32 },
  maxInstalls: { min: 1, max: 4, default: 2 },
} as const;

/** A whole number in a setting's range, or its default when it isn't a number. */
export function limitSetting(value: unknown, range: { min: number; max: number; default: number }): number {
  const n = typeof value === "number" ? value : typeof value === "string" && value.trim() ? Number(value) : Number.NaN;
  if (!Number.isFinite(n)) return range.default;
  return Math.min(range.max, Math.max(range.min, Math.round(n)));
}

/**
 * Settings → Storage's categories, in the order the bar and its legend show them (src-tauri/src/storage.rs puts every byte
 * of Flint's folder in exactly one). Each has its colour in pages.css (`--storage-<id>`).
 */
export const STORAGE_CATEGORIES: { id: StorageCategoryId; label: MessageKey; note: MessageKey }[] = [
  { id: "game", label: "settings.storage.category.game.name", note: "settings.storage.category.game.note" },
  { id: "profiles", label: "settings.storage.category.profiles.name", note: "settings.storage.category.profiles.note" },
  { id: "runtimes", label: "settings.storage.category.runtimes.name", note: "settings.storage.category.runtimes.note" },
  { id: "cache", label: "settings.storage.category.cache.name", note: "settings.storage.category.cache.note" },
  { id: "rollback", label: "settings.storage.category.rollback.name", note: "settings.storage.category.rollback.note" },
  { id: "sync", label: "settings.storage.category.sync.name", note: "settings.storage.category.sync.note" },
  { id: "temporary", label: "settings.storage.category.temporary.name", note: "settings.storage.category.temporary.note" },
  { id: "other", label: "settings.storage.category.appData.name", note: "settings.storage.category.appData.note" },
];

export const categoryLabel = (id: StorageCategoryId) => {
  const category = STORAGE_CATEGORIES.find((c) => c.id === id);
  return category ? t(category.label) : id;
};

/** The smallest share of the bar a non-empty category gets, so it can still be seen and pointed at. */
export const MIN_SEGMENT = 0.8;

export type Segment = { id: StorageCategoryId; bytes: number; percent: number };

/**
 * Each non-empty category's width on the bar, in percent, in the categories' own order, adding up to exactly 100. A
 * category too small to see gets {@link MIN_SEGMENT}, taken from the largest. Nothing when the folder is empty.
 */
export function barSegments(categories: StorageOverview["categories"], min = MIN_SEGMENT): Segment[] {
  const order = STORAGE_CATEGORIES.map((c) => c.id);
  const present = categories
    .filter((c) => c.bytes > 0 && Number.isFinite(c.bytes))
    .sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
  const total = present.reduce((sum, c) => sum + c.bytes, 0);
  if (total <= 0) return [];
  const round = (n: number) => Math.round(n * 100) / 100;
  const segments = present.map((c) => ({ id: c.id, bytes: c.bytes, percent: round(Math.max((c.bytes / total) * 100, min)) }));
  const largest = segments.reduce((a, b) => (b.percent > a.percent ? b : a));
  largest.percent = round(Math.max(0, 100 - segments.filter((s) => s !== largest).reduce((sum, s) => sum + s.percent, 0)));
  return segments;
}

/** A category's share of the whole, for the legend and screen readers: "42%", "under 1%". */
export function shareLabel(bytes: number, total: number): string {
  if (total <= 0 || bytes <= 0) return t("settings.storage.share.percent", { percent: 0 });
  const percent = (bytes / total) * 100;
  return percent < 1 ? t("settings.storage.share.underOne") : t("settings.storage.share.percent", { percent: Math.round(percent) });
}

/**
 * What each cleanup's buttons say: `action` while its size isn't known, `button` with the size it frees (it opens the
 * confirmation), `confirm` in the confirmation, and `done` once it worked.
 */
export const CLEANUPS: Record<CleanupKind, { action: MessageKey; button: MessageKey; confirm: MessageKey; done: MessageKey }> = {
  cache: {
    action: "settings.storage.cleanup.clear",
    button: "settings.storage.cleanup.clearSize",
    confirm: "settings.storage.cleanup.clearConfirm",
    done: "settings.storage.cleanup.cache.done",
  },
  temporary: {
    action: "common.remove",
    button: "settings.storage.cleanup.removeSize",
    confirm: "settings.storage.cleanup.removeConfirm",
    done: "settings.storage.cleanup.temporary.done",
  },
  unusedGame: {
    action: "common.remove",
    button: "settings.storage.cleanup.removeSize",
    confirm: "settings.storage.cleanup.removeConfirm",
    done: "settings.storage.cleanup.unusedGame.done",
  },
  rollback: {
    action: "common.remove",
    button: "settings.storage.cleanup.removeSize",
    confirm: "settings.storage.cleanup.removeConfirm",
    done: "settings.storage.cleanup.rollback.done",
  },
};

/** "3 files", "1 file". */
export const files = (n: number) => t("settings.storage.files", { count: n });
