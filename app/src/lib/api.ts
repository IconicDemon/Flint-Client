import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { PERF, recordCall } from "./perf";

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
  /** This profile's own heap in MB; absent means Settings → Memory applies. */
  memoryMb?: number | null;
};

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
};

export type InstalledItem = {
  kind: ContentKind;
  fileName: string;
  title: string;
  projectId?: string | null;
  versionId?: string | null;
  versionNumber?: string | null;
  iconUrl?: string | null;
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

export type ContentUpdate = {
  kind: ContentKind;
  fileName: string;
  title: string;
  projectId: string;
  world?: string | null;
  tracked: boolean;
  currentVersion?: string | null;
  versionId: string;
  versionNumber: string;
  versionType: string;
  datePublished: string;
  changelog: string;
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
  kind: "install" | "update" | "modpack";
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
export type FolderKind = "data" | "profiles" | "profile" | "mods" | "resourcepacks" | "shaderpacks" | "saves" | "screenshots" | "config" | "logs";

export type NewsEntry = { id: string; title: string; version: string; date: string; summary: string; image?: string | null };

export type ModTarget = { profileId: string } | { gameVersion: string };

export type ResolveKind = "mod" | "resourcepack" | "shader";

export type ResolveRequest = { project?: string; query?: string; source: ModSource; reason?: string; kind?: ResolveKind };

/** Flint AI's searches for one verified Minecraft account, as the server counts them. */
/** Rich Presence as the backend sees it: off, waiting for a game, showing on Discord, or Discord not running. */
export type DiscordStatus = "off" | "idle" | "connected" | "unavailable";

/** Settings → Auto Sync (auto_sync_* in src-tauri/src/gamesettings/autosync): the one line the row may show
 * under its switch, either "waiting" (no game has given it anything yet) or a notice. */
export type AutoSyncStatus = { enabled: boolean; waiting: boolean; notice?: { id: string; text: string } };

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
  if (!info.build) return `${info.version} (development)`;
  return `${info.version} (build ${info.build}${info.commit ? `, ${info.commit}` : ""})`;
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
  if (!inTauri) return Promise.reject(new Error("Flint must run as the desktop app."));
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

export function errorText(error: unknown): string {
  if (typeof error === "string") return error;
  if (error instanceof Error) return error.message;
  return "Something went wrong.";
}

export const api = {
  appInfo: () => call<AppInfo>("app_info"),
  /** Measurement builds only (see lib/perf.ts). */
  perfWrite: (name: string, json: string) => call<void>("perf_write", { name, json }),
  perfEmitLoad: (profileId: string, seconds: number) => call<void>("perf_emit_load", { profileId, seconds }),
  settingsGet: () => call<Record<string, unknown>>("settings_get"),
  settingsSet: (settings: Record<string, unknown>) => call<void>("settings_set", { settings }),
  /** Turns Auto Sync on (every existing profile is taken as it is) or off (nothing is removed). */
  autoSyncSetEnabled: (enabled: boolean) => call<AutoSyncStatus>("auto_sync_set_enabled", { enabled }),
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
  selectProfile: (id: string) => call<void>("profiles_select", { id }),
  duplicateProfile: (id: string, name: string) => call<Profile>("profiles_duplicate", { id, name }),
  deleteProfile: (id: string, deleteFiles: boolean) => call<{ keptFolder: string | null }>("profiles_delete", { id, deleteFiles }),
  inspectProfile: (id: string) =>
    call<{ profile: Profile; path: string; mods: number; worlds: number; screenshots: number; resourcePacks: number }>(
      "profiles_inspect",
      { id },
    ),
  catalog: () => call<Catalog>("version_catalog"),
  news: () => call<{ entries: NewsEntry[]; offline: boolean }>("news_feed"),

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
  installContent: (profileId: string, title: string, items: InstallItem[], world?: string | null) =>
    call<string>("content_install", { profileId, title, items, world: world ?? null }),
  installModpack: (projectId: string, versionId: string | null, name: string | null, title: string) =>
    call<string>("modpack_install", { projectId, versionId, name, title }),
  content: (profileId: string) => call<ProfileContent>("content_list", { profileId }),
  identifyContent: (profileId: string) => call<{ identified: number }>("content_identify", { profileId }),
  setEnabled: (profileId: string, fileNames: string[], enabled: boolean) => call<void>("content_set_enabled", { profileId, fileNames, enabled }),
  removeContent: (profileId: string, items: { kind: ContentKind; fileName: string; world?: string | null }[]) =>
    call<void>("content_remove", { profileId, items }),
  rollbackContent: (profileId: string, kind: ContentKind, projectId: string, world?: string | null) =>
    call<void>("content_rollback", { profileId, kind, projectId, world: world ?? null }),
  contentUpdates: (profileId: string) => call<{ updates: ContentUpdate[]; checkedAt: number }>("content_updates", { profileId }),
  updateContent: (profileId: string, items: { kind: ContentKind; fileName: string; versionId: string; world?: string | null }[]) =>
    call<string>("content_update", { profileId, items }),
  worlds: (profileId: string) => call<World[]>("profile_worlds", { profileId }),
  profileFiles: (profileId: string) => call<ProfileFiles>("profile_files", { profileId }),
  profileLogs: (profileId: string) => call<LogFile[]>("profile_logs", { profileId }),
  readLog: (profileId: string, folder: string, name: string) => call<string>("profile_log_read", { profileId, folder, name }),

  jobs: () => call<Job[]>("jobs_list"),
  cancelJob: (id: string) => call<void>("job_cancel", { id }),
  dismissJob: (id: string) => call<void>("job_dismiss", { id }),

  systemInfo: () => call<{ totalMemory: number | null; cpus: number; os: string; arch: string }>("system_info"),
  validateJava: (path: string) => call<{ version: string; major: number | null; summary: string }>("java_validate", { path }),

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
  storageUsage: () => call<{ game: number; runtimes: number; profiles: number; cache: number }>("storage_usage"),
  /** Server-list ping: connect and round-trip time in milliseconds (measurement only). */
  serverPing: (address: string) =>
    call<{ host: string; port: number; connectMs: number; pingMs: number; version: string | null; playersOnline: number | null; playersMax: number | null }>("server_ping", { address }),
  clearCache: () => call<{ removedBytes: number; failed: { name: string; error: string }[] }>("cache_clear"),
};
