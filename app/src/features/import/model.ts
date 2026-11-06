/**
 * Importing profiles from other launchers: what the picker, the profile table, the preview and the finished
 * screen show, and what a selection means. No Tauri or React (its text comes from lib/i18n), so `npm test` covers
 * it. The same model serves the onboarding step and the Import Profile page.
 */
import type { ImportLauncherId, ImportLoader, ImportPart, ImportPartPreview, ImportProfilePreview, ImportResult, ImportSourceProfile } from "../../lib/api.ts";
import { list, number } from "../../lib/format.ts";
import { t, type MessageKey } from "../../lib/i18n.ts";

/** The loaders' own names; only "Vanilla" and "Unknown" are words of the interface language. */
const LOADER_NAME: Record<Exclude<ImportLoader, "vanilla" | "unknown">, string> = {
  fabric: "Fabric",
  forge: "Forge",
  neoforge: "NeoForge",
  quilt: "Quilt",
};

export function loaderLabel(loader: ImportLoader): string {
  if (loader === "vanilla") return t("import.loader.vanilla");
  if (loader === "unknown") return t("import.loader.unknown");
  return LOADER_NAME[loader];
}

/** The order the loader filter lists them in: what Flint runs first. */
const LOADER_ORDER: ImportLoader[] = ["fabric", "vanilla", "forge", "neoforge", "quilt", "unknown"];

/**
 * Each launcher as a monogram on a faint tint of its own hue: what shows if its icon (Launchers.tsx) can't load. Hues are
 * spread so neighbours in the picker never look alike.
 */
export const LAUNCHER_MARK: Record<ImportLauncherId, { mark: string; hue: number | null }> = {
  modrinth: { mark: "M", hue: 145 },
  curseforge: { mark: "CF", hue: 20 },
  prism: { mark: "P", hue: 265 },
  multimc: { mark: "MM", hue: 38 },
  lunar: { mark: "L", hue: 222 },
  feather: { mark: "F", hue: 195 },
  dawn: { mark: "D", hue: 335 },
  official: { mark: "MC", hue: 95 },
  gdlauncher: { mark: "GD", hue: 172 },
  atlauncher: { mark: "AT", hue: 55 },
  folder: { mark: "", hue: null },
};

export type LoaderFilter = "all" | ImportLoader;
export type SortKey = "played" | "name" | "version";

export const SORTS: { value: SortKey; label: MessageKey }[] = [
  { value: "played", label: "import.sort.played" },
  { value: "name", label: "import.sort.name" },
  { value: "version", label: "import.sort.version" },
];

/** Flint can bring it over, fully or settings only. */
export const selectable = (p: ImportSourceProfile) => p.support !== "unsupported";

/**
 * Picked by Select all and by a Shift-click range. A profile already in Flint is left out of both: a second
 * copy is only made when the player ticks that row itself.
 */
export const bulkSelectable = (p: ImportSourceProfile) => selectable(p) && !p.imported;

/** "All 34", then each loader present with its count, in Flint's order. */
export function loaderCounts(profiles: ImportSourceProfile[]): { value: LoaderFilter; label: string; count: number }[] {
  const counts = new Map<ImportLoader, number>();
  for (const p of profiles) counts.set(p.loader, (counts.get(p.loader) ?? 0) + 1);
  return [
    { value: "all", label: t("import.table.loaderAll"), count: profiles.length },
    ...LOADER_ORDER.filter((l) => counts.has(l)).map((l) => ({ value: l as LoaderFilter, label: loaderLabel(l), count: counts.get(l)! })),
  ];
}

/** Compares Minecraft versions by their numbers ("1.21.10" after "1.21.9"); unknown versions sort first. */
export function compareVersions(a: string | null, b: string | null): number {
  if (a === b) return 0;
  if (!a) return -1;
  if (!b) return 1;
  const pa = a.split(/[.-]/);
  const pb = b.split(/[.-]/);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] ?? "0";
    const y = pb[i] ?? "0";
    const nx = Number(x);
    const ny = Number(y);
    const diff = Number.isFinite(nx) && Number.isFinite(ny) ? nx - ny : x.localeCompare(y);
    if (diff !== 0) return diff < 0 ? -1 : 1;
  }
  return 0;
}

const byName = (a: ImportSourceProfile, b: ImportSourceProfile) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: "base" });

export type TableView = { query: string; loader: LoaderFilter; sort: SortKey; hideImported: boolean };

/** The rows the table shows: searched (name, version or loader), filtered by loader, then sorted. */
export function visibleProfiles(profiles: ImportSourceProfile[], view: TableView): ImportSourceProfile[] {
  const q = view.query.trim().toLowerCase();
  const rows = profiles.filter(
    (p) => (view.loader === "all" || p.loader === view.loader) && (!view.hideImported || !p.imported) && (!q || `${p.name} ${p.gameVersion ?? ""} ${loaderLabel(p.loader)}`.toLowerCase().includes(q)),
  );
  const sorted = rows.slice();
  if (view.sort === "name") sorted.sort(byName);
  else if (view.sort === "version") sorted.sort((a, b) => compareVersions(b.gameVersion, a.gameVersion) || byName(a, b));
  // Newest played first; never played last, by name.
  else sorted.sort((a, b) => (b.lastPlayed ?? -1) - (a.lastPlayed ?? -1) || byName(a, b));
  return sorted;
}

/** "Today", "Yesterday", "3 d ago", "2 wk ago", "5 mo ago", by calendar day in the player's time zone. */
export function lastPlayedLabel(seconds: number | null, now: number = Date.now()): string {
  if (!seconds) return t("import.lastPlayed.never");
  const day = (ms: number) => {
    const d = new Date(ms);
    return Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86_400_000;
  };
  const days = Math.max(0, Math.round(day(now) - day(seconds * 1000)));
  if (days === 0) return t("import.lastPlayed.today");
  if (days === 1) return t("import.lastPlayed.yesterday");
  if (days < 14) return t("import.lastPlayed.days", { count: days });
  if (days < 60) return t("import.lastPlayed.weeks", { count: Math.floor(days / 7) });
  if (days < 365) return t("import.lastPlayed.months", { count: Math.floor(days / 30) });
  return t("import.lastPlayed.years", { count: Math.floor(days / 365) });
}

/** Adds or removes one key, keeping the order keys were picked in. */
export function toggleKey(selected: string[], key: string, on: boolean): string[] {
  const has = selected.includes(key);
  if (on === has) return selected;
  return on ? [...selected, key] : selected.filter((k) => k !== key);
}

/**
 * A Shift-click: every row between the anchor (the last row clicked) and this one, in the order shown, takes
 * the clicked row's new state. Rows already in Flint stay as they are, unless it's the clicked row itself.
 */
export function selectRange(selected: string[], visible: ImportSourceProfile[], anchor: string | null, key: string, on: boolean): string[] {
  const to = visible.findIndex((p) => p.key === key);
  const from = anchor ? visible.findIndex((p) => p.key === anchor) : -1;
  if (to < 0) return selected;
  if (from < 0) return selectable(visible[to]) ? toggleKey(selected, key, on) : selected;
  const [lo, hi] = from < to ? [from, to] : [to, from];
  let next = selected;
  for (let i = lo; i <= hi; i++) {
    const p = visible[i];
    if (p.key === key ? selectable(p) : bulkSelectable(p)) next = toggleKey(next, p.key, on);
  }
  return next;
}

/** The header checkbox over the rows shown: ticked when every row it would pick is picked, a dash when some are. */
export function headerState(visible: ImportSourceProfile[], selected: string[]): { checked: boolean; indeterminate: boolean; disabled: boolean } {
  const picked = new Set(selected);
  const eligible = visible.filter(bulkSelectable);
  const all = eligible.length > 0 && eligible.every((p) => picked.has(p.key));
  const some = visible.some((p) => picked.has(p.key));
  return { checked: all, indeterminate: !all && some, disabled: eligible.length === 0 && !some };
}

/** The header checkbox clicked: when everything it covers is picked, clears the rows shown; otherwise picks them. */
export function toggleAll(selected: string[], visible: ImportSourceProfile[]): string[] {
  const { checked } = headerState(visible, selected);
  if (checked) {
    const shown = new Set(visible.map((p) => p.key));
    return selected.filter((k) => !shown.has(k));
  }
  let next = selected;
  for (const p of visible) if (bulkSelectable(p)) next = toggleKey(next, p.key, true);
  return next;
}

/** Keys still in the listing and still importable, in the order picked (a rescan can drop some). */
export function keepSelectable(selected: string[], profiles: ImportSourceProfile[]): string[] {
  const ok = new Set(profiles.filter(selectable).map((p) => p.key));
  return selected.filter((k) => ok.has(k));
}

/** Chips for the selection bar: every name when they fit, otherwise the first few and "+N more". */
export function chips<T>(items: T[], max = 3): { shown: T[]; more: number } {
  if (items.length <= max) return { shown: items, more: 0 };
  return { shown: items.slice(0, max - 1), more: items.length - (max - 1) };
}

/** "34 profiles, 2 already in Flint", or "12 of 34 profiles" while a search or filter hides some. */
export function countLine(total: number, shown: number, imported: number): string {
  const vars = { count: total, shown: number(shown), imported: number(imported) };
  if (shown === total) return t(imported ? "import.table.countImported" : "import.table.count", vars);
  return t(imported ? "import.table.countShownImported" : "import.table.countShown", vars);
}

export const importLabel = (n: number) => (n === 0 ? t("import.action.importNone") : t("import.action.import", { count: n }));

/** "Demon", "Demon and Iconic", "Demon, Iconic and Flash", "Demon, Iconic and 3 more". */
export function nameList(names: string[]): string {
  if (names.length <= 3) return list(names);
  return list([...names.slice(0, 2), t("import.list.more", { count: names.length - 2 })]);
}

const PART_LABEL: Record<ImportPart, MessageKey> = {
  mods: "import.part.mods",
  config: "import.part.config",
  options: "import.part.options",
  keybinds: "import.part.keybinds",
  resourcepacks: "import.part.resourcepacks",
  shaderpacks: "import.part.shaderpacks",
  servers: "import.part.servers",
  worlds: "import.part.worlds",
  other: "import.part.otherData",
};

/** The same, inside a sentence: "mods, game settings and worlds". */
const PART_INLINE: Record<ImportPart, MessageKey> = {
  mods: "import.part.inline.mods",
  config: "import.part.inline.config",
  options: "import.part.inline.options",
  keybinds: "import.part.inline.keybinds",
  resourcepacks: "import.part.inline.resourcepacks",
  shaderpacks: "import.part.inline.shaderpacks",
  servers: "import.part.inline.servers",
  worlds: "import.part.inline.worlds",
  other: "import.part.inline.otherData",
};

/** A part's name, "Game settings"; `inline` for one inside a sentence, "game settings". */
export const partLabel = (id: ImportPart, inline = false) => t((inline ? PART_INLINE : PART_LABEL)[id]);

const PART_ORDER: ImportPart[] = ["mods", "config", "options", "keybinds", "resourcepacks", "shaderpacks", "servers", "worlds", "other"];

/** One part as the preview lists it: "Mods · 36", "Worlds · 2", or just "Keybinds". */
export function partLine(part: ImportPartPreview): string {
  const label = partLabel(part.id);
  return part.count > 0 && ["mods", "resourcepacks", "shaderpacks", "worlds"].includes(part.id) ? t("import.part.withCount", { part: label, amount: number(part.count) }) : label;
}

/** A profile's parts by what happens to them: comes over, needs a look, or stays behind. */
export function partsByStatus(preview: ImportProfilePreview): Record<ImportPartPreview["status"], ImportPartPreview[]> {
  const sorted = preview.parts.slice().sort((a, b) => PART_ORDER.indexOf(a.id) - PART_ORDER.indexOf(b.id));
  return {
    compatible: sorted.filter((p) => p.status === "compatible"),
    attention: sorted.filter((p) => p.status === "attention"),
    unsupported: sorted.filter((p) => p.status === "unsupported"),
  };
}

/**
 * What came over, in a few words for the finished screen: "All 42 mods, mod settings, game settings and 3
 * resource packs", or "35 of 36 mods, …". `sourceMods` is how many mod files the source profile had.
 */
export function cameOver(preview: ImportProfilePreview, sourceMods: number | null): string {
  const parts = partsByStatus(preview);
  const moved = [...parts.compatible, ...parts.attention].filter((p) => p.count > 0 || p.files > 0);
  const words = moved
    .sort((a, b) => PART_ORDER.indexOf(a.id) - PART_ORDER.indexOf(b.id))
    .map((p) => {
      if (p.id === "mods") {
        if (sourceMods && p.count < sourceMods) return t("import.cameOver.someMods", { moved: number(p.count), count: sourceMods });
        return p.count === 1 ? t("import.cameOver.oneMod") : t("import.cameOver.allMods", { count: p.count });
      }
      if (p.id === "resourcepacks") return t("import.cameOver.resourcePacks", { count: p.count });
      if (p.id === "shaderpacks") return t("import.cameOver.shaderPacks", { count: p.count });
      if (p.id === "worlds") return t("import.cameOver.worlds", { count: p.count });
      return partLabel(p.id, true);
    });
  if (!words.length) return t("import.cameOver.nothing");
  const text = list(words.length > 4 ? [...words.slice(0, 3), t("import.list.more", { count: words.length - 3 })] : words);
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export type Outcome = { tone: "success" | "partial" | "failed" | "stopped"; title: string; body: string; imported: number };

/**
 * The finished screen's headline, honest about what happened: never "imported" for a profile that wasn't.
 * `stopped`: the player cancelled the import.
 */
export function outcome(result: ImportResult, stopped: boolean): Outcome {
  const done = result.profiles.filter((p) => p.state === "done");
  const missed = result.profiles.filter((p) => p.state === "failed" || p.state === "skipped");
  const names = (profiles: { name: string }[]) => nameList(profiles.map((p) => p.name));
  const n = done.length;
  if (stopped) {
    return {
      tone: "stopped",
      title: t("import.outcome.stopped.title"),
      body: !n
        ? t("import.outcome.stopped.bodyNone")
        : n === 1
          ? t("import.outcome.stopped.bodyOne", { name: done[0].name })
          : t("import.outcome.stopped.bodyMany", { names: names(done) }),
      imported: n,
    };
  }
  if (n && !missed.length) {
    return {
      tone: "success",
      title: t("import.outcome.success.title", { count: n }),
      body: n === 1 ? t("import.outcome.success.bodyOne", { name: done[0].name }) : t("import.outcome.success.bodyMany", { names: names(done) }),
      imported: n,
    };
  }
  if (n) {
    // Two sentences: the ones that failed, then the ones that made it.
    const failed = missed.length === 1 ? t("import.outcome.partial.missedOne", { name: missed[0].name }) : t("import.outcome.partial.missedMany", { names: names(missed) });
    const kept = n === 1 ? t("import.outcome.partial.keptOne", { name: done[0].name }) : t("import.outcome.partial.keptMany");
    return {
      tone: "partial",
      title: t("import.outcome.partial.title", { imported: number(n), count: result.profiles.length }),
      body: `${failed} ${kept}`,
      imported: n,
    };
  }
  return {
    tone: "failed",
    title: t("import.outcome.failed.title"),
    body: missed.length === 1 ? t("import.outcome.failed.bodyOne", { name: missed[0].name }) : t("import.outcome.failed.bodyMany"),
    imported: 0,
  };
}
