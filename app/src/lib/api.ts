import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { t } from "./i18n";
import { PERF, recordCall } from "./perf";
import type { MemoryLimits } from "./memory";
import { storeFeed, type StoreChanges, type StoreInfo, type StoreOrder, type StoreSlot, type StoreView, type StoreWallet } from "./storeSync";
import type { ChatMessage, Me, MyStatus, SearchPlayer, SocialSnapshot } from "./social";
import type { AttemptView, CommunityCatalog, ConnectionsView, LinkStart, Recheck, RewardsView } from "./community";
import type { CapeConflict } from "./capeConflict";

export type Account = {
  uuid: string;
  username: string;
  skinUrl?: string | null;
  skinModel?: "classic" | "slim" | null;
  addedAt: number;
  qa?: boolean;
};

export type Profile = {
  id: string;
  name: string;
  gameVersion: string;
  loader: "fabric" | "vanilla";
  loaderVersion?: string | null;
  createdAt: number;
  lastPlayed?: number | null;
  installed: boolean;
  modpack?: { projectId: string; versionId: string; title: string; versionNumber?: string; iconUrl?: string | null } | null;
  /** This profile's own heap in MB; absent means Memory in Settings → Java and memory applies. */
  memoryMb?: number | null;
  /** Its icon; absent: the version's picture. */
  icon?: ProfileIcon | null;
  /** When it was pinned to the sidebar (Unix seconds); absent when it isn't. */
  pinnedAt?: number | null;
  /** Time played through Flint, in seconds. */
  playSeconds?: number;
  /** The launcher profile it was imported from (src-tauri/src/import), so a later scan knows it's already here. */
  origin?: { launcher: ImportLauncherId; key: string; importedAt: number } | null;
};

// ---------- importing profiles from other launchers (src-tauri/src/import, docs/PROFILE_IMPORT.md) ----------

/** The launchers Flint reads profiles from. "folder" is a folder the player chose (portable or custom installs). */
export type ImportLauncherId = "modrinth" | "curseforge" | "prism" | "multimc" | "lunar" | "feather" | "dawn" | "official" | "gdlauncher" | "atlauncher" | "folder";

export type ImportLauncher = {
  id: ImportLauncherId;
  name: string;
  /** found: it has profiles Flint can read. empty: installed, but no profiles. unreadable: its files couldn't be read (`message` says why). */
  status: "found" | "empty" | "unreadable";
  /** Profiles listed, importable or not. */
  profiles: number;
  /** Of those, how many Flint can bring over (fully or settings only). */
  importable: number;
  message?: string | null;
};

export type ImportLoader = "vanilla" | "fabric" | "quilt" | "forge" | "neoforge" | "unknown";

/**
 * full: Flint runs its version and loader, so the mods come too. settings: Flint runs its version but not its loader
 * (Forge, NeoForge, Quilt), so settings, keybinds, packs, servers and worlds come without the mods. unsupported: Flint
 * doesn't offer its Minecraft version (or it couldn't be read); nothing is imported.
 */
export type ImportSupport = "full" | "settings" | "unsupported";

export type ImportSourceProfile = {
  /** Stable, non-sensitive id of this source profile (a hash); kept on the Flint profile it becomes. */
  key: string;
  name: string;
  gameVersion: string | null;
  loader: ImportLoader;
  loaderVersion: string | null;
  /** Unix seconds. */
  lastPlayed: number | null;
  /** A small data: URL, when the launcher keeps an icon Flint can read. */
  icon: string | null;
  /** Mod files in its mods folder (enabled and disabled). */
  mods: number;
  worlds: number;
  support: ImportSupport;
  /** Why it's settings only or unsupported, in one sentence. */
  supportNote: string | null;
  /** Already in Flint from an earlier import of this same source profile. */
  imported: { profileId: string; name: string } | null;
  /** It shares its game folder with other profiles of the same launcher (the official launcher, Lunar). */
  sharedFolder: boolean;
};

export type ImportListing = { launcher: ImportLauncher; profiles: ImportSourceProfile[] };

export type ImportPart = "mods" | "config" | "options" | "keybinds" | "resourcepacks" | "shaderpacks" | "servers" | "worlds" | "other";

export type ImportPartPreview = {
  id: ImportPart;
  status: "compatible" | "attention" | "unsupported";
  files: number;
  bytes: number;
  /** Mods: how many come over. Worlds: how many worlds. Otherwise the file count. */
  count: number;
  note: string | null;
};

export type ImportProfilePreview = {
  key: string;
  name: string;
  /** The Flint profile it becomes (its unique name, version and loader), or null when it's blocked. */
  target: { name: string; gameVersion: string; loader: "fabric" | "vanilla" } | null;
  parts: ImportPartPreview[];
  /** Bytes copied with the current choices (worlds only when chosen). */
  bytes: number;
  worldBytes: number;
  warnings: string[];
  /** Why it can't be imported; it's left out of the import. */
  blocker: string | null;
  /** Files that stay behind, each with its reason (a link, sign-in data, too large, not a Fabric mod...). */
  skipped?: ImportSkip[];
};

export type ImportPreview = { profiles: ImportProfilePreview[]; bytes: number; freeBytes: number | null; enoughSpace: boolean };

export type ImportRequest = { launcher: ImportLauncherId; folder?: string | null; keys: string[]; worlds: boolean };

/** One profile of a running import ("import-progress" event, throttled; also `importProgress`). */
export type ImportProfileProgress = {
  key: string;
  name: string;
  state: "waiting" | "copying" | "verifying" | "done" | "failed" | "skipped" | "cancelled";
  bytesDone: number;
  bytesTotal: number;
  filesDone: number;
  filesTotal: number;
  /** What it's copying now ("Mods", "World: Survival"). */
  current: string | null;
  profileId: string | null;
  error: string | null;
};

export type ImportProgress = { job: string; profiles: ImportProfileProgress[] };

export type ImportSkip = { path: string; reason: string };

export type ImportResultProfile = {
  key: string;
  name: string;
  profileId: string | null;
  state: "done" | "failed" | "cancelled" | "skipped";
  error: string | null;
  warnings: string[];
  skipped: ImportSkip[];
  /** What really came over, part by part, after skips (empty when nothing was imported). */
  parts?: ImportPartPreview[];
};

/** An import job's `result`. */
export type ImportResult = { profiles: ImportResultProfile[] };

/** A Minecraft item (`item/diamond`, `block/tnt_side`) on a coloured tile, or the player's own picture. */
export type ProfileIcon = { kind: "item"; name: string; color: string } | { kind: "image"; file: string };

export type RunState =
  | "uninstalled"
  | "checking"
  | "downloading"
  | "installing"
  | "ready"
  | "launching"
  | "running"
  | "error";

export type ProfileState = {
  profileId: string;
  state: RunState;
  stage?: "validate" | "check" | "download" | "install" | "finalize";
  label?: string;
  progress?: number | null;
  bytesDone?: number;
  bytesTotal?: number;
  filesDone?: number;
  filesTotal?: number;
  missingBytes?: number;
  message?: string;
  /** Redacted technical details for a failed launch. */
  detail?: string;
  exitCode?: number | null;
  logFile?: { folder: "logs" | "crash-reports"; name: string };
  demo?: boolean;
};

export type AppInfo = {
  version: string;
  /** Release build number (`YYYY.MMDD.HHMM`); null for development builds. */
  build?: string | null;
  commit?: string | null;
  platform: "macos" | "windows" | "linux";
  arch: string;
  dataDir: string;
  qa: boolean;
  forceOnboarding: boolean;
  gatewayConfigured: boolean;
  discordUrl: string | null;
  /** QA builds only: the page to open first. */
  qaStartPage?: string | null;
  /** Measurement builds only: the scenario suite to run after start-up. */
  perfRun?: string | null;
};

export type CatalogVersion = { id: string; available: boolean; releaseTime?: string | null; fabric: boolean };
export type Catalog = { versions: CatalogVersion[]; fabricLoader?: string | null; offline: boolean; latestRelease?: string };

export type ModSource = "user" | "assistant" | "dependency" | "optional" | "manual";
export type ContentKind = "mod" | "resourcepack" | "shader" | "datapack";
export type LibraryKind = ContentKind | "modpack";

export type PlanItem = {
  projectId: string;
  slug: string;
  title: string;
  description?: string;
  iconUrl?: string | null;
  clientSide?: string | null;
  kind: ContentKind;
  versionId?: string | null;
  versionNumber?: string | null;
  versionType?: string | null;
  datePublished?: string | null;
  fileName?: string | null;
  size?: number | null;
  /** The Modrinth author (best effort; resolver plans only). */
  author?: string | null;
  source: ModSource;
  reason?: string | null;
  requiredBy?: string | null;
  /** Every project in the plan that requires this one (`requiredBy` names only the first). */
  requiredByAll?: string[] | null;
  required?: boolean;
  status: "ok" | "unavailable" | "conflict" | "installed" | "not-needed";
  message?: string | null;
  /** mods_resolve only: "shaderLoader" marks Iris, the mod that loads shader packs. */
  role?: "shaderLoader" | null;
};

export type ModPlan = {
  items: PlanItem[];
  blockers: string[];
  gameVersion: string;
  loader: string;
  unmatched?: { query: string; source: ModSource; message: string }[];
  /** mods_resolve only: the project each request resolved to, in request order (null when none). */
  matches?: (string | null)[];
  /** content_plan only: whether the profile's game, while it runs, turns new packs on by itself (Flint's runtime is in it). */
  gameApplies?: boolean;
};

export type InstalledItem = {
  kind: ContentKind;
  fileName: string;
  title: string;
  projectId?: string | null;
  versionId?: string | null;
  versionNumber?: string | null;
  iconUrl?: string | null;
  /** Mods: the jar's own logo (src-tauri/src/modicons.rs), for when there's no Modrinth icon or it can't load. */
  localIcon?: string | null;
  slug?: string | null;
  source: ModSource;
  reason?: string | null;
  size: number;
  modified?: number | null;
  enabled: boolean;
  missing: boolean;
  tracked: boolean;
  folder?: boolean;
  world?: string | null;
  requires: string[];
  installedAt?: number;
  canRollback?: boolean;
  previousVersion?: string | null;
  identified?: boolean;
  /** The player keeps this version: Update all leaves it unticked. */
  pinned?: boolean;
};

export type World = { id: string; name: string; lastPlayed?: number | null; icon?: string | null; datapacks: number };

export type ProfileContent = {
  profileId: string;
  mods: InstalledItem[];
  resourcepacks: InstalledItem[];
  shaders: InstalledItem[];
  datapacks: InstalledItem[];
  worlds: World[];
  running: boolean;
  irisInstalled: boolean;
};

/** A project an update adds because its new version needs it (content/updates.rs `version_option`). */
export type UpdateDependency = { projectId: string; title: string; iconUrl?: string | null; kind: ContentKind; versionId: string; versionNumber?: string | null; size?: number | null };

/** One version an item can be updated to: what it adds to the profile, or why it can't go in. */
export type UpdateVersion = {
  versionId: string;
  versionNumber: string;
  versionType: "release" | "beta" | "alpha";
  datePublished: string;
  /** Trimmed; Modrinth has the whole text. */
  changelog: string;
  size?: number | null;
  adds: UpdateDependency[];
  blocked: string | null;
};

/** An update for one installed file. Its own fields are the proposed version; `alternatives` are other newer builds for this profile, newest first. */
export type ContentUpdate = UpdateVersion & {
  kind: ContentKind;
  fileName: string;
  title: string;
  projectId: string;
  iconUrl?: string | null;
  world?: string | null;
  tracked: boolean;
  pinned: boolean;
  currentVersion?: string | null;
  currentVersionId?: string | null;
  alternatives: UpdateVersion[];
};

/** A file the check can't ask Modrinth about: a folder, or a file Modrinth doesn't know by its hash. */
export type UnmanagedFile = { kind: ContentKind; fileName: string; title: string; world?: string | null; folder: boolean };

export type UpdateCheck = { updates: ContentUpdate[]; unmanaged: UnmanagedFile[]; checkedAt: number; gameVersion: string; loader: string };

/** What happened to one item of an update job. */
export type UpdateOutcome = { kind: ContentKind; fileName: string; world?: string | null; title: string; reason?: string; newFileName?: string; projectId?: string; from?: string; to?: string };

/** An update job's `result`. */
export type UpdateResult = {
  updated: UpdateOutcome[];
  added: { title: string; versionNumber: string; projectId: string; kind: ContentKind }[];
  failed: UpdateOutcome[];
  skipped: UpdateOutcome[];
  installed: string[];
};

export type RateLimit = { limit?: number | null; remaining?: number | null; resetIn?: number | null };

export type LibraryHit = {
  projectId: string;
  slug: string;
  title: string;
  description: string;
  iconUrl?: string | null;
  author: string;
  downloads: number;
  follows: number;
  categories?: string[] | null;
  versions?: string[] | null;
  latestVersion?: string | null;
  projectType: string;
  clientSide?: string;
  serverSide?: string;
  dateModified?: string;
  gallery?: string | null;
  color?: number | null;
};

export type LibrarySearchParams = {
  kind: LibraryKind;
  query: string;
  index: "relevance" | "downloads" | "follows" | "updated" | "newest";
  offset: number;
  limit: number;
  gameVersion?: string | null;
  loader?: string | null;
  categories: string[];
  clientOnly?: boolean;
};

export type LibraryResults = { hits: LibraryHit[]; offset: number; limit: number; total: number; stale: boolean; staging: boolean; rateLimit: RateLimit };

export type LibraryProject = {
  id: string;
  slug: string;
  title: string;
  description: string;
  body: string;
  iconUrl?: string | null;
  projectType: string;
  categories: string[];
  additionalCategories?: string[];
  loaders: string[];
  gameVersions: string[];
  clientSide: string;
  serverSide: string;
  downloads: number;
  followers: number;
  license?: { id: string; name: string; url?: string | null } | null;
  published: string;
  updated: string;
  gallery?: { url: string; title?: string | null; featured: boolean }[] | null;
  sourceUrl?: string | null;
  issuesUrl?: string | null;
  wikiUrl?: string | null;
  color?: number | null;
  author?: string | null;
  members: { username: string; role: string; avatarUrl?: string | null; owner: boolean }[];
  stale: boolean;
};

export type LibraryVersion = {
  id: string;
  projectId: string;
  name: string;
  versionNumber: string;
  versionType: "release" | "beta" | "alpha";
  gameVersions: string[];
  loaders: string[];
  datePublished: string;
  downloads: number;
  changelog: string;
  dependencies: { project_id?: string | null; version_id?: string | null; dependency_type: string }[];
  files: { filename: string; size: number; primary: boolean }[];
};

export type Category = { name: string; projectType: string; header: string };

export type Job = {
  id: string;
  kind: "install" | "update" | "modpack" | "export" | "import";
  profileId?: string | null;
  title: string;
  state: "queued" | "running" | "done" | "failed" | "cancelled";
  stage: string;
  file?: string | null;
  bytesDone: number;
  bytesTotal?: number | null;
  itemsDone: number;
  itemsTotal: number;
  message?: string | null;
  result?: unknown;
  createdAt: number;
};

export type LogFile = { name: string; folder: "logs" | "crash-reports"; kind: "log" | "crash"; size: number; modified?: number | null };
export type ProfileFiles = { path: string; total: number; folders: { id: string; count: number; size: number; exists: boolean }[] };
export type SkinProfile = { name: string; variant: "classic" | "slim"; capes: { id: string; alias: string; active: boolean; dataUrl: string | null }[]; saved?: boolean };
/** `skin_profile` without asking macOS: the profile, or `locked` when that needs the saved sign-in. */
export type SkinProfileResult = SkinProfile | { locked: true };
export type InstallItem = { versionId: string; kind?: ContentKind; source: ModSource; reason?: string | null };
export type UpdateRequest = { kind: ContentKind; fileName: string; versionId: string; world?: string | null };
export type FolderKind = "data" | "game" | "profiles" | "profile" | "mods" | "resourcepacks" | "shaderpacks" | "saves" | "screenshots" | "config" | "logs" | "backups";

/** Settings → Storage (src-tauri/src/storage.rs): every byte of Flint's folder in one of these. */
export type StorageCategoryId = "game" | "profiles" | "runtimes" | "cache" | "rollback" | "sync" | "temporary" | "other";
export type CleanupKind = "cache" | "temporary" | "unusedGame" | "rollback";
export type StorageProfile = {
  /** None for a folder no profile uses any more (a removed profile's kept folder). */
  id: string | null;
  name: string;
  folder: string;
  gameVersion: string | null;
  loader: string | null;
  /** The profile's own files: worlds, mods, packs, configs (not its backups). */
  bytes: number;
  rollbackBytes: number;
  rollbackFiles: number;
  syncBackupBytes: number;
  syncBackups: number;
};
export type StorageOverview = {
  root: string;
  total: number;
  categories: { id: StorageCategoryId; bytes: number; files: number }[];
  /** Biggest first. */
  profiles: StorageProfile[];
  /** Hard links to files already counted (macOS, Linux), not counted again. */
  linkedBytes: number;
  reclaimable: Record<CleanupKind, number>;
  scannedAt: number;
  /** Why nothing may be removed right now (a game runs, an install is going on). */
  busy: string | null;
};
export type CleanupPlan = {
  kind: CleanupKind;
  /** Names this exact plan; a cleanup runs only while its files are still the ones shown. */
  id: string;
  bytes: number;
  files: number;
  groups: { label: string; bytes: number; files: number }[];
  note: string | null;
  busy: string | null;
};
export type CleanupResult = { removedBytes: number; removedFiles: number; failed: { name: string; error: string }[] };
/** Settings → Troubleshooting → Error history (src-tauri/src/troubleshoot.rs). */
export type ErrorEntry = { at: number; kind: "job" | "profile" | "game"; title: string; message: string; gameVersion?: string | null };
export type EnvironmentInfo = { os: string; arch: string; cpu: string | null; threads: number; memoryMb: number | null };
export type SavedDiagnostics = { path: string; size: number; files: number };

/** `news_feed`: a release from Flint's public release feed, already checked field by field (src-tauri/src/data.rs
 *  `feed_releases`; lib/newsLinks.ts `newsReleases` checks it again). `page_url` only when it is exactly the version's page. */
export type FeedRelease = { version: string; date: string; title: string; summary: string; page_url: string | null };

/** `partners_feed` (src-tauri/src/partners): the Store's Partners API v1 body as it came (parse with lib/partners.ts), and where it came from. */
export type PartnersAnswer = {
  source: "network" | "confirmed" | "cache" | "none";
  problem: "offline" | "unavailable" | "rate-limited" | "invalid" | null;
  body: unknown;
  fetchedAt: number | null;
  /** Pictures load from `<mediaBase><sha256>`, served by the launcher after it checked them. */
  mediaBase: string;
  mediaProjects: string[];
  /** Development only: listings may name a server on this computer. */
  allowLoopback: boolean;
};
/** What Play would do for a partner server in a profile (partners_play_preview); nothing is changed. */
export type PartnerPlayPreview = { address: string; listed: boolean; listProblem: string | null; blocked: string | null; othersRunning: string[] };
/** What the launch did with the profile's server list. */
export type PartnerListing = { address: string; added: boolean; alreadyListed: boolean; problem: string | null };
/** The launcher's own status ping of a partner server (never the backend's verified numbers). */
export type PartnerServerCheck = { reachable: boolean; pingMs: number | null; version: string | null };

/** A part of a profile a `.flintpack` can carry (src-tauri/src/flintpack.rs `Section`). */
export type PackSection = "mods" | "resourcepacks" | "shaderpacks" | "config" | "options" | "flint" | "worlds";
/** What one section of a profile (export) or a pack (import) holds. `worlds` counts worlds; `downloads`, files fetched from Modrinth. */
export type PackSectionInfo = { id: PackSection; files: number; size: number; worlds?: number | null; downloads?: number };
export type PackPreview = {
  path: string;
  fileName?: string | null;
  /** The name inside the file, and the name the new profile gets (made unique among existing profiles). */
  packName: string;
  name: string;
  gameVersion: string;
  loader: "fabric" | "vanilla";
  loaderVersion?: string | null;
  modpack?: { title: string; iconUrl?: string | null } | null;
  sections: PackSectionInfo[];
  totalSize: number;
  downloads: number;
  createdAt: number;
  generator: string;
  /** Why this Flint can't import it (an unsupported Minecraft version), or null. */
  blocker?: string | null;
};

export type ModTarget = { profileId: string } | { gameVersion: string };

export type ResolveKind = "mod" | "resourcepack" | "shader";

export type ResolveRequest = { project?: string; query?: string; source: ModSource; reason?: string; kind?: ResolveKind };

/** Flint AI's searches for one verified Minecraft account, as the server counts them. */
/** Rich Presence as the backend sees it: off, waiting for a game, showing on Discord, or Discord not running. */
export type DiscordStatus = "off" | "idle" | "connected" | "unavailable";

/** Settings → Auto Sync (auto_sync_* in src-tauri/src/gamesettings/autosync): the one line the row may show
 * under its switch, either "waiting" (no game has given it anything yet) or a notice. */
export type AutoSyncStatus = { enabled: boolean; waiting: boolean; notice?: { id: string; text: string } };

/** Settings → Syncing's switches (autosync/switches.rs). */
export type AutoSyncSwitchId = "gameOptions" | "keybinds" | "servers" | "resourcePacks" | "shaderPacks" | "hotbars" | "commandHistory" | "modSettings";

/** Everything Settings → Syncing shows (auto_sync_overview). Profile references are ids. */
export type AutoSyncOverview = {
  enabled: boolean;
  waiting: boolean;
  notices: { id: string; text: string }[];
  switches: { id: AutoSyncSwitchId; on: boolean; startedFrom?: string; startedAt?: number }[];
  /** Most recently played first. */
  profiles: {
    id: string;
    name: string;
    gameVersion: string;
    excluded: boolean;
    /** Auto Sync can start from it now. */
    canStart: boolean;
    /** Why it only gives its changes, or joins later. */
    note?: string;
    backups: number;
    lastBackup?: number;
    lastPlayed?: number;
  }[];
  startedFrom?: string;
  defaultSource?: string;
  lastSync?: { at: number; profile: string };
};

/** Settings → Java and memory (src-tauri/src/javaplan.rs). */
export type JavaUse = { component: string; major: number; minimum: number; fallback?: number };
export type JavaOverview = {
  versions: { version: string; withMenu: JavaUse; withoutMenu: JavaUse; note?: string }[];
  runtimes: { component: string; version?: string; major?: number; size: number }[];
  custom?: { path: string; version?: string; major?: number; problem?: string };
  inGameMenu: boolean;
  automatic: boolean;
};

export type AiQuota = { limit: number; remaining: number; resetInMs: number | null };

/** The `ai_suggest` answer (the gateway's /v1/intents contract after Flint's validation). */
export type AiIntentsResponse = {
  goals: { category: string; kind?: ResolveKind; label: string; why?: string | null; searches: string[]; projects: string[]; lookup?: boolean; nameHint?: string | null }[];
  constraints: { lowEndHardware: boolean; ramGb: number | null; keepVanilla: boolean };
  exclude: string[];
  note: string | null;
  model: string | null;
  quota?: AiQuota | null;
  replayed?: boolean;
};

/** "0.4.0 (build 2026.1013.1910, a1b2c3d4)", or "0.4.0 (development)" for builds without a build number. */
export function releaseLabel(info: Pick<AppInfo, "version" | "build" | "commit">): string {
  const { version, build, commit } = info;
  if (!build) return t("app.release.development", { version });
  return commit ? t("app.release.buildCommit", { version, build, commit }) : t("app.release.build", { version, build });
}

const inTauri = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
type MockModule = typeof import("./devMock");
let mock: MockModule | null = null;

export async function initBridge() {
  if (!inTauri && import.meta.env.DEV) {
    mock = await import("./devMock");
  }
}

export const isDesktop = () => inTauri;

function call<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  if (mock) return mock.invoke(command, args) as Promise<T>;
  if (!inTauri) return Promise.reject(new Error(t("app.errors.desktopOnly")));
  if (PERF) {
    // Measurement builds only: how often each command is called and how long it takes to answer.
    const started = performance.now();
    return invoke<T>(command, args).finally(() => recordCall(command, performance.now() - started));
  }
  return invoke<T>(command, args);
}

export function subscribe<T>(event: string, handler: (payload: T) => void): () => void {
  if (mock) return mock.listen(event, handler as (p: unknown) => void);
  if (!inTauri) return () => {};
  let off: (() => void) | null = null;
  let disposed = false;
  void listen<T>(event, (e) => handler(e.payload)).then((unlisten) => {
    if (disposed) unlisten();
    else off = unlisten;
  });
  return () => {
    disposed = true;
    off?.();
  };
}

/** Passes a Store answer on to `storeFeed` and returns it. */
function fed(view: StoreView): StoreView {
  storeFeed.publish({ kind: "view", view });
  return view;
}

export function errorText(error: unknown): string {
  if (typeof error === "string") return error;
  if (error instanceof Error) return error.message;
  return t("app.errors.unknown");
}

export const api = {
  appInfo: () => call<AppInfo>("app_info"),
  /** Measurement builds only (see lib/perf.ts). */
  perfWrite: (name: string, json: string) => call<void>("perf_write", { name, json }),
  perfEmitLoad: (profileId: string, seconds: number) => call<void>("perf_emit_load", { profileId, seconds }),
  settingsGet: () => call<Record<string, unknown>>("settings_get"),
  settingsSet: (settings: Record<string, unknown>) => call<void>("settings_set", { settings }),
  /** Turns Auto Sync on (starting from `source`, or the most recently played profile; every existing profile is
   * taken as it is) or off (nothing is removed). */
  autoSyncSetEnabled: (enabled: boolean, source?: string) => call<AutoSyncStatus>("auto_sync_set_enabled", { enabled, source }),
  autoSyncOverview: () => call<AutoSyncOverview>("auto_sync_overview"),
  /** One kind on or off; turned on while Auto Sync is on, it starts from `source`. */
  autoSyncSetSwitch: (id: AutoSyncSwitchId, on: boolean, source?: string) => call<AutoSyncOverview>("auto_sync_set_switch", { id, on, source }),
  /** "Don't sync this profile": its files are never read or written while it is left out. */
  autoSyncSetExcluded: (profileId: string, excluded: boolean) => call<AutoSyncOverview>("auto_sync_set_excluded", { profileId, excluded }),
  autoSyncOpenBackups: (profileId: string) => call<void>("auto_sync_open_backups", { profileId }),
  autoSyncStatus: () => call<AutoSyncStatus>("auto_sync_status"),
  autoSyncDismiss: (id: string) => call<AutoSyncStatus>("auto_sync_dismiss", { id }),
  onboardingGet: () => call<Record<string, unknown>>("onboarding_get"),
  onboardingSet: (value: Record<string, unknown>) => call<void>("onboarding_set", { value }),

  signIn: () => call<Account>("auth_sign_in"),
  cancelSignIn: () => call<void>("auth_cancel"),
  qaAccount: () => call<Account>("auth_qa_account"),
  accounts: () => call<{ active: string | null; accounts: Account[] }>("accounts_list"),
  setActiveAccount: (uuid: string) => call<void>("accounts_set_active", { uuid }),
  refreshAccount: (uuid: string) => call<Account>("accounts_refresh", { uuid }),
  removeAccount: (uuid: string) => call<void>("accounts_remove", { uuid }),
  skin: (uuid: string) => call<{ dataUrl: string | null; capeUrl?: string | null; model: "classic" | "slim" }>("skin_texture", { uuid }),
  /** `interactive: false` never causes a keychain prompt (see skins.rs). */
  skinProfile: (uuid: string, interactive: boolean) => call<SkinProfileResult>("skin_profile", { uuid, interactive }),
  skinUpload: (uuid: string, pngBase64: string, variant: "classic" | "slim") => call<SkinProfile>("skin_upload", { uuid, pngBase64, variant }),
  skinReset: (uuid: string) => call<SkinProfile>("skin_reset", { uuid }),
  capeSet: (uuid: string, capeId: string | null) => call<SkinProfile>("cape_set", { uuid, capeId }),

  profiles: () => call<{ selected: string | null; profiles: Profile[] }>("profiles_list"),
  createProfile: (name: string, gameVersion: string, loader: string, select: boolean) =>
    call<Profile>("profiles_create", { name, gameVersion, loader, select }),
  renameProfile: (id: string, name: string) => call<Profile>("profiles_rename", { id, name }),
  /** `null` goes back to the launcher-wide memory setting. */
  setProfileMemory: (id: string, memoryMb: number | null) => call<Profile>("profiles_set_memory", { id, memoryMb }),
  /** An item on a tile, or `null` for the version's picture again. */
  setProfileIcon: (id: string, icon: ProfileIcon | null) => call<Profile>("profiles_set_icon", { id, icon }),
  /** The system's open dialog, then the picture as the profile's icon; `null` when cancelled. */
  chooseProfileIconPicture: (id: string) => call<Profile | null>("profiles_choose_icon_picture", { id }),
  profileIconPicture: (id: string) => call<string | null>("profiles_icon_picture", { id }),
  setProfilePinned: (id: string, pinned: boolean) => call<Profile>("profiles_set_pinned", { id, pinned }),
  /** The icon picker's items and tiles, with each item's picture from the player's installed Minecraft (empty until one is). */
  profileIconTextures: () =>
    call<{ groups: { name: string; icons: string[] }[]; icons: string[]; colors: string[]; textures: Record<string, string> }>("profile_icon_textures"),
  selectProfile: (id: string) => call<void>("profiles_select", { id }),
  duplicateProfile: (id: string, name: string) => call<Profile>("profiles_duplicate", { id, name }),
  deleteProfile: (id: string, deleteFiles: boolean) => call<{ keptFolder: string | null }>("profiles_delete", { id, deleteFiles }),
  inspectProfile: (id: string) =>
    call<{ profile: Profile; path: string; mods: number; worlds: number; screenshots: number; resourcePacks: number }>(
      "profiles_inspect",
      { id },
    ),
  catalog: () => call<Catalog>("version_catalog"),
  news: () => call<{ entries: FeedRelease[]; offline: boolean }>("news_feed"),

  /** Partners (src-tauri/src/partners). `cache`: the cached copy only; `auto`: asks the Store unless it did within its max-age. */
  partnersFeed: (mode: "cache" | "auto" | "force" = "auto") => call<PartnersAnswer>("partners_feed", { mode }),
  partnersFavorites: () => call<string[]>("partners_favorites"),
  partnersSetFavorite: (id: string, on: boolean) => call<string[]>("partners_set_favorite", { id, on }),
  partnersPlayPreview: (partnerId: string, profileId: string) => call<PartnerPlayPreview>("partners_play_preview", { partnerId, profileId }),
  /** Only after the player confirmed: adds the server to that profile's list, launches it and joins the server. */
  partnersPlay: (partnerId: string, profileId: string) => call<PartnerListing>("partners_play", { partnerId, profileId }),
  partnersCheckServer: (partnerId: string) => call<PartnerServerCheck>("partners_check_server", { partnerId }),

  profileStates: () => call<Record<string, ProfileState>>("profile_states"),
  /** `quiet`: a background probe that shows no "Checking" state and never holds the profile. */
  checkProfile: (id: string, opts?: { quiet?: boolean }) => call<{ ready: boolean; missingBytes: number; missingFiles: number }>("profile_check", { id, quiet: !!opts?.quiet }),
  installProfile: (id: string, repair = false) => call<void>("profile_install", { id, repair }),
  cancelInstall: (id: string) => call<void>("profile_cancel", { id }),
  launch: (id: string) => call<void>("profile_launch", { id }),
  stop: (id: string) => call<void>("profile_stop", { id }),

  librarySearch: (params: LibrarySearchParams) => call<LibraryResults>("library_search", { params }),
  libraryProject: (id: string) => call<LibraryProject>("library_project", { id }),
  libraryVersions: (id: string, kind: LibraryKind, gameVersion?: string | null) =>
    call<{ versions: LibraryVersion[]; stale: boolean }>("library_versions", { id, kind, gameVersion }),
  libraryTags: () => call<{ categories: Category[] }>("library_tags"),
  libraryStatus: () => call<{ base: string; staging: boolean; userAgent: string; rateLimit: RateLimit }>("library_status"),

  resolveMods: (target: ModTarget, requests: ResolveRequest[]) => call<ModPlan>("mods_resolve", { ...target, requests }),
  installMods: (profileId: string, items: InstallItem[]) =>
    call<{ installed: string[]; failed: { versionId: string; message: string }[] }>("mods_install", { profileId, items }),
  planContent: (profileId: string, requests: { kind: ContentKind; project: string; versionId?: string | null; source?: ModSource }[]) =>
    call<ModPlan>("content_plan", { profileId, requests }),
  /** `enableLive`: while the profile's game runs, the packs installed are turned on in it at once (the default). */
  installContent: (profileId: string, title: string, items: InstallItem[], world?: string | null, enableLive?: boolean) =>
    call<string>("content_install", { profileId, title, items, world: world ?? null, enableLive: enableLive ?? true }),
  installModpack: (projectId: string, versionId: string | null, name: string | null, title: string) =>
    call<string>("modpack_install", { projectId, versionId, name, title }),
  content: (profileId: string) => call<ProfileContent>("content_list", { profileId }),
  identifyContent: (profileId: string) => call<{ identified: number }>("content_identify", { profileId }),
  setEnabled: (profileId: string, fileNames: string[], enabled: boolean) => call<void>("content_set_enabled", { profileId, fileNames, enabled }),
  removeContent: (profileId: string, items: { kind: ContentKind; fileName: string; world?: string | null }[]) =>
    call<void>("content_remove", { profileId, items }),
  rollbackContent: (profileId: string, kind: ContentKind, projectId: string, world?: string | null) =>
    call<void>("content_rollback", { profileId, kind, projectId, world: world ?? null }),
  /** Identifies hand-added files first, then checks everything the profile can take. */
  contentUpdates: (profileId: string) => call<UpdateCheck>("content_updates", { profileId }),
  /** One job for all of them; its `result` is an `UpdateResult`. */
  updateContent: (profileId: string, items: UpdateRequest[]) => call<string>("content_update", { profileId, items }),
  pinContent: (profileId: string, items: { kind: ContentKind; fileName: string; world?: string | null }[], pinned: boolean) =>
    call<void>("content_pin", { profileId, items, pinned }),
  /** `.flintpack` export and import (src-tauri/src/flintpack.rs). Export and import run as jobs. */
  packScan: (profileId: string) => call<{ sections: PackSectionInfo[] }>("flintpack_scan", { profileId }),
  packPickSave: (profileId: string) => call<string | null>("flintpack_pick_save", { profileId }),
  packPickOpen: () => call<string | null>("flintpack_pick_open"),
  packExport: (profileId: string, sections: PackSection[], path: string) => call<string>("flintpack_export", { profileId, sections, path }),
  packInspect: (path: string) => call<PackPreview>("flintpack_inspect", { path }),
  packImport: (path: string, name: string) => call<string>("flintpack_import", { path, name }),
  packReveal: (path: string) => call<void>("flintpack_reveal", { path }),
  /** The `.flintpack` Flint was opened with (double-click), handed over once. */
  packTakeOpened: () => call<string | null>("flintpack_take_opened"),
  worlds: (profileId: string) => call<World[]>("profile_worlds", { profileId }),
  profileFiles: (profileId: string) => call<ProfileFiles>("profile_files", { profileId }),
  profileLogs: (profileId: string) => call<LogFile[]>("profile_logs", { profileId }),
  readLog: (profileId: string, folder: string, name: string) => call<string>("profile_log_read", { profileId, folder, name }),

  /** Profile import (src-tauri/src/import). Scanning only reads; the import runs as a job ("import"), cancelled with cancelJob. */
  importLaunchers: () => call<ImportLauncher[]>("import_launchers"),
  importProfiles: (launcher: ImportLauncherId, folder?: string | null) => call<ImportListing>("import_profiles", { launcher, folder: folder ?? null }),
  importPickFolder: () => call<string | null>("import_pick_folder"),
  importPreview: (request: ImportRequest) => call<ImportPreview>("import_preview", { request }),
  importStart: (request: ImportRequest) => call<string>("import_start", { request }),
  importProgress: (job: string) => call<ImportProgress | null>("import_progress", { job }),

  jobs: () => call<Job[]>("jobs_list"),
  cancelJob: (id: string) => call<void>("job_cancel", { id }),
  dismissJob: (id: string) => call<void>("job_dismiss", { id }),

  systemInfo: () => call<{ totalMemory: number | null; memory: MemoryLimits | null; cpus: number; os: string; arch: string }>("system_info"),
  validateJava: (path: string) => call<{ version: string; major: number | null; summary: string }>("java_validate", { path }),
  /** The extra Java arguments as the launcher will use them, or why it won't. */
  checkJavaArgs: (text: string) => call<string[]>("java_args_check", { text }),
  /** Settings → Java and memory: the extra arguments as a launch uses them, what they change and where they won't
   * start, or why they can't be saved. */
  reviewJavaArgs: (text: string) => call<{ args: string[]; notes: string[] }>("java_args_review", { text }),
  /** Settings → Java and memory: each version's Java, the runtimes Flint keeps, and the custom Java. */
  javaOverview: () => call<JavaOverview>("java_overview"),
  /** Settings → Troubleshooting → Copy troubleshooting details: versions, system, memory and Java, redacted. */
  diagnosticsText: () => call<string>("diagnostics_text"),
  /** Asks where to save the diagnostics file, then writes it there; null when cancelled. Nothing is uploaded. */
  exportDiagnostics: () => call<SavedDiagnostics | null>("diagnostics_export"),
  revealDiagnostics: (path: string) => call<void>("diagnostics_reveal", { path }),
  environmentInfo: () => call<EnvironmentInfo>("environment_info"),
  errorHistory: () => call<ErrorEntry[]>("error_history"),
  clearErrorHistory: () => call<void>("error_history_clear"),

  /** Flint AI: goals for a request (validated in Rust; parse again with `parseIntents`). Never installs anything. */
  aiSuggest: (requestId: string, target: ModTarget, prompt: string) => call<AiIntentsResponse>("ai_suggest", { requestId, ...target, prompt }),
  aiCancel: (requestId: string) => call<void>("ai_cancel", { requestId }),
  discordStatus: () => call<DiscordStatus>("discord_status"),
  /** Whether a gateway is configured and answers its health check. Never signs in. */
  aiStatus: () => call<{ configured: boolean; reachable: boolean }>("ai_status"),
  /** The player's remaining Flint AI searches. Signs in to the gateway: call only on a player's action. */
  aiQuota: () => call<AiQuota>("ai_quota"),

  openExternal: (url: string) => call<void>("open_external", { url }),
  revealFolder: (which: FolderKind, profileId?: string) => call<void>("reveal_folder", { which, profileId }),
  launchAtLoginGet: () => call<boolean>("launch_at_login_get"),
  launchAtLoginSet: (enabled: boolean) => call<boolean>("launch_at_login_set", { enabled }),
  /** What Flint's folder holds (measured off the window's thread; reused for a few seconds unless `refresh`). */
  storageOverview: (refresh = false) => call<StorageOverview>("storage_overview", { refresh }),
  /** Exactly what a cleanup would remove, for its confirmation. */
  storagePlan: (kind: CleanupKind) => call<CleanupPlan>("storage_plan", { kind }),
  /** Runs a cleanup as `planId` showed it (refused if Flint's files changed since, or a game or install is running). */
  storageClean: (kind: CleanupKind, planId: string) => call<CleanupResult>("storage_clean", { kind, planId }),
  /** Server-list ping: connect and round-trip time in milliseconds (measurement only). */
  serverPing: (address: string) =>
    call<{ host: string; port: number; connectMs: number; pingMs: number; version: string | null; playersOnline: number | null; playersMax: number | null }>("server_ping", { address }),

  /**
   * The Flint Store (src-tauri/src/store; the Store's launcher API v1). Every call names the Minecraft account; the Store
   * session stays in Rust and the system credential store, never here.
   */
  storeInfo: () => call<StoreInfo>("store_info"),
  /** `interactive: false` never asks the system for the saved Store sign-in again after it refused. */
  // Every answer also goes to `storeFeed`, so the character previews' cape (lib/displayedCape.ts) follows the Store page.
  storeInventory: (uuid: string, interactive = false) => call<StoreView>("store_inventory", { uuid, interactive }).then(fed),
  storeSignIn: (uuid: string) => call<StoreView>("store_sign_in", { uuid }).then(fed),
  storeSignOut: (uuid: string) => call<void>("store_sign_out", { uuid }).then(() => storeFeed.publish({ kind: "signedOut", uuid })),
  storeChanges: (uuid: string, since: number | null) => call<StoreChanges>("store_changes", { uuid, since }),
  storeEquip: (uuid: string, slot: StoreSlot, cosmeticId: string | null) => call<StoreView>("store_equip", { uuid, slot, cosmeticId }).then(fed),
  /** The profiles where a mod that shows its own capes is on (src-tauri/src/capemods.rs): they can hide a Flint cape there. */
  capeModConflicts: () => call<CapeConflict[]>("cape_mod_conflicts"),
  /** The texture of an item this account owns, checked against the Store's SHA-256 (a data URL). */
  storeAsset: (uuid: string, cosmeticId: string) => call<string>("store_asset", { uuid, cosmeticId }),
  /** Strip `index` of an animated cape this account owns (PNG bytes), checked against the Store's SHA-256 and size. */
  storeCapeStrip: (uuid: string, cosmeticId: string, index: number) => call<ArrayBuffer>("store_cape_strip", { uuid, cosmeticId, index }),
  storeWallet: (uuid: string) => call<StoreWallet>("store_wallet", { uuid }),
  storeOrders: (uuid: string) => call<StoreOrder[]>("store_orders", { uuid }),
  /** Signs the browser showing `code` in to the Store as this account. Only after the player confirmed the code. */
  storeApproveLink: (uuid: string, code: string) => call<void>("store_approve_link", { uuid, code }),

  /**
   * Discord connected accounts and community rewards (src-tauri/src/store/community.rs). A Store without them answers
   * `state: "off"`; nothing about Discord is ever sent from the launcher.
   */
  storeConnections: (uuid: string) => call<ConnectionsView>("store_connections", { uuid }),
  /** Starts linking and opens Discord's own page in the system browser (checked in Rust first). */
  storeDiscordLinkStart: (uuid: string) => call<LinkStart>("store_discord_link_start", { uuid }),
  storeDiscordLinkReopen: (uuid: string, attemptId: string) => call<void>("store_discord_link_reopen", { uuid, attemptId }),
  storeDiscordLinkAttempt: (uuid: string, attemptId: string) => call<AttemptView>("store_discord_link_attempt", { uuid, attemptId }),
  storeDiscordUnlink: (uuid: string) => call<ConnectionsView>("store_discord_unlink", { uuid }),
  storeRewards: (uuid: string) => call<RewardsView>("store_rewards", { uuid }),
  /** "Check again": at most once a minute (Rust keeps the limit too). */
  storeRewardsRecheck: (uuid: string) => call<Recheck>("store_rewards_recheck", { uuid }),
  /** The public community catalog; Rust asks the Store at most once a minute. */
  storeCommunityCatalog: () => call<CommunityCatalog>("store_community_catalog"),
  /** A listed reward's texture (owned or not), checked against the catalog's SHA-256 (a data URL). */
  storeRewardAsset: (cosmeticId: string) => call<string>("store_reward_asset", { cosmeticId }),
  storeRewardStrip: (cosmeticId: string, index: number) => call<ArrayBuffer>("store_reward_strip", { cosmeticId, index }),
  /** A reward's Discord invite (join, finish joining, boost), opened in the system browser after Rust checked it. */
  storeOpenDiscordInvite: (url: string) => call<void>("store_open_discord_invite", { url }),

  /**
   * Friends (src-tauri/src/social.rs), for Flint's active account. The backend keeps the state in sync in the background
   * and sends it as "social:update" and "social:event"; errors are short codes (lib/social.ts `errorKey`).
   */
  socialSnapshot: () => call<SocialSnapshot>("social_snapshot"),
  /** The chat open on the Friends page (polled every 4 s while one is), or null. */
  socialFocus: (chat: string | null) => call<void>("social_focus", { chat }),
  /** Signs in now if needed and syncs; `force` (Retry) also cuts a wait short. */
  socialRefresh: (force = false) => call<void>("social_refresh", { force }),
  socialSettings: (settings: { status?: MyStatus; allowRequests?: boolean }) => call<Me>("social_settings", settings),
  socialSearch: (query: string) => call<SearchPlayer[]>("social_search", { query }),
  /** "sent", or "accepted" when they had already asked. */
  socialRequest: (name: string) => call<"sent" | "accepted">("social_request", { name }),
  socialRespond: (requestId: number | string, accept: boolean) => call<void>("social_respond", { requestId, accept }),
  socialCancel: (requestId: number | string) => call<void>("social_cancel", { requestId }),
  socialRemove: (uuid: string) => call<void>("social_remove", { uuid }),
  socialPin: (uuid: string, pinned: boolean) => call<void>("social_pin", { uuid, pinned }),
  socialSend: (to: string, body: string, sensitive: boolean, clientId?: string) => call<ChatMessage>("social_send", { to, body, sensitive, clientId }),
  /** Older messages (before `before`), oldest first; without `before`, the latest page. */
  socialHistory: (withUuid: string, before?: number | null, limit?: number) => call<ChatMessage[]>("social_history", { with: withUuid, before: before ?? null, limit: limit ?? null }),
  socialRead: (withUuid: string, upTo: number) => call<void>("social_read", { with: withUuid, upTo }),
};
