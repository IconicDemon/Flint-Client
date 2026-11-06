// Settings → Syncing: what its rows say (pure, so it is tested with `npm test`). Auto Sync itself lives in
// src-tauri/src/gamesettings/autosync; nothing here decides what is synced.
import type { AutoSyncOverview, AutoSyncStatus, AutoSyncSwitchId } from "./api";
import { currentLocale, formatLocale, t, translator, type MessageKey } from "./i18n.ts";

/** The "waiting" line in English, as en.json has it (the tests compare with it); the page shows it in the interface language. */
export const WAITING_TEXT = translator("en", {})("settings.sync.waiting");

export type AutoSyncLine = { text: string; dismissId?: string } | null;

/** A notice (with its Dismiss) wins over "waiting"; nothing when Auto Sync is off or there's nothing to say. */
export function autoSyncLine(on: boolean, status: AutoSyncStatus | null): AutoSyncLine {
  if (!on || !status) return null;
  if (status.notice) return { text: status.notice.text, dismissId: status.notice.id };
  return status.waiting ? { text: t("settings.sync.waiting") } : null;
}

/**
 * One of Settings → Syncing's switches. Its words (settings.sync.<id>.*) are read when they're shown, so they
 * follow the interface language.
 */
export type SyncSwitch = {
  id: AutoSyncSwitchId;
  /** The row's title and the switch's label: "Game options". */
  readonly title: string;
  /** What it shares, and where it doesn't apply. */
  readonly desc: string;
  /** The row's search words. */
  keywords: MessageKey;
  /** What it shares, as the confirmation says it: "New profiles start with the game options from …". */
  readonly noun: string;
  /** The confirmation's title: "Sync game options?". */
  readonly confirmTitle: string;
};

type SwitchKeys = Record<"title" | "desc" | "keywords" | "noun" | "confirmTitle", MessageKey>;

const syncSwitch = (id: AutoSyncSwitchId, keys: SwitchKeys): SyncSwitch => ({
  id,
  get title() {
    return t(keys.title);
  },
  get desc() {
    return t(keys.desc);
  },
  keywords: keys.keywords,
  get noun() {
    return t(keys.noun);
  },
  get confirmTitle() {
    return t(keys.confirmTitle);
  },
});

/** Settings → Syncing's switches, in the order they're shown. */
export const SYNC_SWITCHES: SyncSwitch[] = [
  syncSwitch("gameOptions", {
    title: "settings.sync.gameOptions.title",
    desc: "settings.sync.gameOptions.desc",
    keywords: "settings.sync.gameOptions.keywords",
    noun: "settings.sync.gameOptions.noun",
    confirmTitle: "settings.sync.gameOptions.confirmTitle",
  }),
  syncSwitch("keybinds", {
    title: "settings.sync.keybinds.title",
    desc: "settings.sync.keybinds.desc",
    keywords: "settings.sync.keybinds.keywords",
    noun: "settings.sync.keybinds.noun",
    confirmTitle: "settings.sync.keybinds.confirmTitle",
  }),
  syncSwitch("servers", {
    title: "settings.sync.servers.title",
    desc: "settings.sync.servers.desc",
    keywords: "settings.sync.servers.keywords",
    noun: "settings.sync.servers.noun",
    confirmTitle: "settings.sync.servers.confirmTitle",
  }),
  syncSwitch("resourcePacks", {
    title: "settings.sync.resourcePacks.title",
    desc: "settings.sync.resourcePacks.desc",
    keywords: "settings.sync.resourcePacks.keywords",
    noun: "settings.sync.resourcePacks.noun",
    confirmTitle: "settings.sync.resourcePacks.confirmTitle",
  }),
  syncSwitch("shaderPacks", {
    title: "settings.sync.shaderPacks.title",
    desc: "settings.sync.shaderPacks.desc",
    keywords: "settings.sync.shaderPacks.keywords",
    noun: "settings.sync.shaderPacks.noun",
    confirmTitle: "settings.sync.shaderPacks.confirmTitle",
  }),
  syncSwitch("hotbars", {
    title: "settings.sync.hotbars.title",
    desc: "settings.sync.hotbars.desc",
    keywords: "settings.sync.hotbars.keywords",
    noun: "settings.sync.hotbars.noun",
    confirmTitle: "settings.sync.hotbars.confirmTitle",
  }),
  syncSwitch("commandHistory", {
    title: "settings.sync.commandHistory.title",
    desc: "settings.sync.commandHistory.desc",
    keywords: "settings.sync.commandHistory.keywords",
    noun: "settings.sync.commandHistory.noun",
    confirmTitle: "settings.sync.commandHistory.confirmTitle",
  }),
  syncSwitch("modSettings", {
    title: "settings.sync.modSettings.title",
    desc: "settings.sync.modSettings.desc",
    keywords: "settings.sync.modSettings.keywords",
    noun: "settings.sync.modSettings.noun",
    confirmTitle: "settings.sync.modSettings.confirmTitle",
  }),
];

type Profiles = AutoSyncOverview["profiles"];

/** A profile's name by id, if it still exists. */
export function profileName(profiles: Profiles, id?: string | null): string | null {
  return profiles.find((p) => p.id === id)?.name ?? null;
}

/** "just now", "5 min ago", "3 h ago", "2 d ago" (other languages: their own short form, "vor 5 Min."). */
export function since(seconds: number, now = Date.now() / 1000): string {
  const diff = now - seconds;
  if (diff < 90) return t("settings.sync.justNow");
  const english = currentLocale() === "en";
  const ago = (value: number, unit: Intl.RelativeTimeFormatUnit) =>
    new Intl.RelativeTimeFormat(formatLocale(), { numeric: "auto", style: "short" }).format(-value, unit);
  if (diff < 3600) return english ? `${Math.round(diff / 60)} min ago` : ago(Math.round(diff / 60), "minute");
  if (diff < 86400) return english ? `${Math.round(diff / 3600)} h ago` : ago(Math.round(diff / 3600), "hour");
  return english ? `${Math.round(diff / 86400)} d ago` : ago(Math.round(diff / 86400), "day");
}

/** The line under Auto Sync's switch in Settings → Syncing: a notice to dismiss, "waiting", or when it last synced. */
export function syncStatusLine(o: AutoSyncOverview | null, now = Date.now() / 1000): AutoSyncLine {
  if (!o?.enabled) return null;
  const notice = o.notices[0];
  if (notice) return { text: notice.text, dismissId: notice.id };
  if (o.waiting) return { text: t("settings.sync.waiting") };
  const name = o.lastSync && profileName(o.profiles, o.lastSync.profile);
  if (o.lastSync && name) return { text: t("settings.sync.lastSynced", { when: since(o.lastSync.at, now), name }) };
  return null;
}

/** The profiles Auto Sync can start from now, the one it would pick first. */
export function sourceChoices(o: AutoSyncOverview): { value: string; label: string; hint: string }[] {
  const usable = o.profiles.filter((p) => p.canStart);
  const first = usable.find((p) => p.id === o.defaultSource);
  return (first ? [first, ...usable.filter((p) => p !== first)] : usable).map((p) => ({
    value: p.id,
    label: p.name,
    hint: p.id === o.defaultSource ? t("settings.sync.start.playedLast", { version: p.gameVersion }) : p.gameVersion,
  }));
}

/**
 * What turning something on will do, for its confirmation: where it starts, and that nothing is overwritten.
 * `noun` is what it shares as the sentence names it (a switch's `noun`, or settings.sync.autoSync.noun).
 */
export function startExplainer(noun: string, sourceName: string | null): string {
  if (!sourceName) return t("settings.sync.start.empty");
  return t("settings.sync.start.from", { noun, name: sourceName });
}

/** Shown under a switch that was turned on while Auto Sync was on. */
export function startedLine(o: AutoSyncOverview, id: AutoSyncSwitchId): string | null {
  const s = o.switches.find((x) => x.id === id);
  const name = s?.on ? profileName(o.profiles, s.startedFrom) : null;
  return name ? t("settings.sync.startedFrom", { name }) : null;
}
