// Browser-only development stand-in for the Rust backend (vite dev without Tauri).
// Never bundled into production builds; see initBridge() in api.ts.
import type { Account, Profile, ProfileState } from "./api";

type Handler = (payload: unknown) => void;
const handlers = new Map<string, Set<Handler>>();
const emit = (event: string, payload: unknown) => handlers.get(event)?.forEach((h) => h(payload));
export function listen(event: string, handler: Handler) {
  if (!handlers.has(event)) handlers.set(event, new Set());
  handlers.get(event)!.add(handler);
  return () => handlers.get(event)?.delete(handler);
}

const params = new URLSearchParams(location.search);
const KEY = "flint-dev-mock";
type Db = {
  settings: Record<string, unknown>;
  onboarding: Record<string, unknown>;
  accounts: Account[];
  active: string | null;
  profiles: Profile[];
  selected: string | null;
  mods: Record<string, unknown[]>;
};
const blank = (): Db => ({ settings: {}, onboarding: { completed: false }, accounts: [], active: null, profiles: [], selected: null, mods: {} });
let db: Db = params.has("reset") ? blank() : { ...blank(), ...JSON.parse(localStorage.getItem(KEY) ?? "{}") };
const save = () => localStorage.setItem(KEY, JSON.stringify(db));
if (params.has("reset")) save();

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const fail = params.get("fail") ?? "";
const states: Record<string, ProfileState> = {};
const setState = (s: ProfileState) => {
  states[s.profileId] = s;
  emit("profile-state", s);
};

const jobs: unknown[] = [];
// Auto Sync's status line: `?autosync=waiting` shows "Waiting for your first game", `?autosync=notice` a notice.
let autoSyncDismissed = false;
const CAPE_COLORS: [string, string, string][] = [
  ["c1", "Migrator", "#2f6fb5"],
  ["c2", "Vanilla", "#d9d2b8"],
  ["c3", "Cherry Blossom", "#e7a1c4"],
  ["c4", "15th Anniversary", "#3f8f4b"],
  ["c5", "Purple Heart", "#7b4bb3"],
  ["c6", "Follower's", "#c9822f"],
  ["c7", "MCC 15th Year", "#c23b3b"],
  ["c8", "Common", "#5f6b73"],
];
let capeActive: string | null = null;
const capeArt = (color: string) => {
  const c = document.createElement("canvas");
  c.width = 64;
  c.height = 32;
  const g = c.getContext("2d")!;
  g.fillStyle = color;
  g.fillRect(0, 0, 22, 17);
  g.fillStyle = "rgba(255,255,255,0.35)";
  for (let y = 1; y < 17; y += 4) g.fillRect(1, y, 10, 1);
  return c.toDataURL("image/png");
};
const capes = () => CAPE_COLORS.map(([id, alias, color]) => ({ id, alias, active: capeActive === id, dataUrl: capeArt(color) }));
const mockItem = (title: string, id: string, source: string, kind: string) => ({ kind, fileName: `${id}.jar`, title, projectId: id, versionNumber: "1.0.0", source, size: 1_200_000, enabled: true, missing: false, tracked: true, requires: [], installedAt: Date.now() / 1000 });

const NOTCH: Account = {
  uuid: "069a79f444e94726a5befca90e38aaf5",
  username: "Notch",
  skinModel: "classic",
  addedAt: Date.now() / 1000,
};
const SKIN = "https://textures.minecraft.net/texture/292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680";

async function skinData(): Promise<string | null> {
  try {
    const blob = await (await fetch(SKIN)).blob();
    return await new Promise((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as string);
      reader.readAsDataURL(blob);
    });
  } catch {
    return null;
  }
}

const catalog = {
  versions: ["26.3", "26.2", "26.1.2", "26.1.1", "26.1", "1.21.11", "1.21.10", "1.21.1", "1.8.9"].map((id, i) => ({
    id,
    available: true,
    fabric: id !== "1.8.9",
    releaseTime: ["2026-09-15", "2026-06-16", "2026-04-09", "2026-04-01", "2026-03-24", "2025-12-09", "2025-10-07", "2024-08-08", "2015-12-03"][i],
  })),
  fabricLoader: "0.19.5",
  offline: false,
};

const SAMPLE_MODS = [
  ["AANobbMI", "sodium", "Sodium", "Modern rendering engine with big FPS gains."],
  ["gvQqBUqZ", "lithium", "Lithium", "General-purpose game logic optimization."],
  ["1bokaNcj", "xaeros-minimap", "Xaero's Minimap", "A minimap that keeps the vanilla look."],
  ["YL57xq9U", "iris", "Iris Shaders", "Shader pack support built for Sodium."],
  ["w7ThoJFB", "zoomify", "Zoomify", "A configurable zoom key."],
];

async function install(id: string) {
  setState({ profileId: id, state: "checking", stage: "validate", label: "Validating version" });
  await sleep(500);
  setState({ profileId: id, state: "checking", stage: "check", label: "Checking files" });
  await sleep(500);
  if (fail === "install") {
    setState({ profileId: id, state: "error", message: "Download failed for client.jar: could not connect (check your internet connection)" });
    throw new Error("Download failed for client.jar: could not connect (check your internet connection)");
  }
  const total = 728_349_181;
  for (let i = 1; i <= 20; i++) {
    await sleep(110);
    setState({ profileId: id, state: "downloading", stage: "download", label: "Downloading game files", progress: i / 20, bytesDone: (total * i) / 20, bytesTotal: total });
  }
  setState({ profileId: id, state: "installing", stage: "install", label: "Installing" });
  await sleep(400);
  db.profiles = db.profiles.map((p) => (p.id === id ? { ...p, installed: true } : p));
  save();
  setState({ profileId: id, state: "ready" });
}

/** Flint AI searches left in the browser preview (the real count lives on the server). */
const mockQuota = { remaining: 3 };

export async function invoke(cmd: string, args: Record<string, any> = {}): Promise<unknown> {
  await sleep(40);
  switch (cmd) {
    case "app_info":
      return { version: "0.4.0", build: null, commit: null, platform: params.get("platform") ?? "macos", arch: "aarch64", dataDir: "~/Library/Application Support/com.flintclient.desktop", qa: params.has("qa"), forceOnboarding: params.has("onboarding"), gatewayConfigured: fail !== "ai", discordUrl: "https://discord.gg/59rAHZMWb7", qaStartPage: params.get("page") ?? undefined };
    case "settings_get":
      return db.settings;
    case "settings_set":
      db.settings = args.settings;
      return save();
    case "onboarding_get":
      return db.onboarding;
    case "onboarding_set":
      db.onboarding = args.value;
      return save();
    case "auth_sign_in": {
      emit("auth-status", { stage: "browser" });
      await sleep(1400);
      if (fail === "auth") throw "Microsoft sign-in was cancelled.";
      for (const stage of ["exchange", "xbox", "minecraft", "ownership", "profile"]) {
        emit("auth-status", { stage });
        await sleep(350);
      }
      db.accounts = [...db.accounts.filter((a) => a.uuid !== NOTCH.uuid), NOTCH];
      db.active = NOTCH.uuid;
      save();
      return NOTCH;
    }
    case "auth_cancel":
      return;
    case "auth_qa_account": {
      const qa = { uuid: "00000000000040008000000000f11a7e", username: "FlintQA", addedAt: Date.now() / 1000, qa: true };
      db.accounts = [...db.accounts.filter((a) => a.uuid !== qa.uuid), qa];
      db.active = qa.uuid;
      save();
      return qa;
    }
    case "accounts_list":
      return { active: db.active, accounts: db.accounts };
    case "accounts_set_active":
      db.active = args.uuid;
      return save();
    case "accounts_refresh":
      return db.accounts.find((a) => a.uuid === args.uuid);
    case "accounts_remove":
      db.accounts = db.accounts.filter((a) => a.uuid !== args.uuid);
      if (db.active === args.uuid) db.active = db.accounts[0]?.uuid ?? null;
      return save();
    case "skin_texture":
      return args.uuid === NOTCH.uuid ? { dataUrl: await skinData(), model: "classic" } : { dataUrl: null, model: "classic" };
    case "profiles_list":
      return { selected: db.selected, profiles: db.profiles };
    case "profiles_create": {
      const p: Profile = { id: Math.random().toString(16).slice(2, 14), name: args.name, gameVersion: args.gameVersion, loader: args.loader, createdAt: Date.now() / 1000, installed: false };
      db.profiles.push(p);
      if (args.select || !db.selected) db.selected = p.id;
      save();
      return p;
    }
    case "profiles_rename":
      db.profiles = db.profiles.map((p) => (p.id === args.id ? { ...p, name: args.name } : p));
      save();
      return db.profiles.find((p) => p.id === args.id);
    case "profiles_set_memory":
      db.profiles = db.profiles.map((p) => (p.id === args.id ? { ...p, memoryMb: args.memoryMb ?? null } : p));
      save();
      return db.profiles.find((p) => p.id === args.id);
    case "profiles_select":
      db.selected = args.id;
      return save();
    case "profiles_inspect":
      return { profile: db.profiles.find((p) => p.id === args.id), path: `~/Library/Application Support/com.flintclient.desktop/profiles/${args.id}`, mods: (db.mods[args.id] ?? []).length, worlds: 0, screenshots: 0, resourcePacks: 0 };
    case "version_catalog":
      await sleep(300);
      return catalog;
    case "news_feed":
      return { entries: [{ id: "26-3", title: "Minecraft Java Edition 26.3", version: "26.3", date: "2026-09-15T11:23:02Z", summary: "Development mock entry. The desktop app shows Mojang's live patch notes here.", image: null }], offline: false };
    case "profile_states":
      return Object.fromEntries(db.profiles.map((p) => [p.id, states[p.id] ?? { profileId: p.id, state: p.installed ? "ready" : "uninstalled" }]));
    case "profile_check":
      return { ready: db.profiles.find((p) => p.id === args.id)?.installed ?? false, missingBytes: 728_349_181, missingFiles: 5391 };
    case "profile_install":
      return install(args.id);
    case "profile_cancel":
      return;
    case "profile_launch": {
      const p = db.profiles.find((x) => x.id === args.id);
      if (!p?.installed) await install(args.id);
      setState({ profileId: args.id, state: "launching", label: "Starting Minecraft" });
      await sleep(900);
      setState({ profileId: args.id, state: "running" });
      setTimeout(() => setState({ profileId: args.id, state: "ready" }), 6000);
      return;
    }
    case "profile_stop":
      return setState({ profileId: args.id, state: "ready" });
    case "library_search": {
      await sleep(260);
      const p = args.params as { kind: string; query: string; offset: number; limit: number };
      const pool = p.kind === "mod" ? SAMPLE_MODS : SAMPLE_MODS.slice(0, 3).map(([id, slug, title]) => [`${p.kind}-${id}`, `${slug}-${p.kind}`, `${title} ${p.kind === "shader" ? "Shaders" : "Pack"}`, `A sample ${p.kind} for development.`]);
      const q = p.query.toLowerCase();
      const hits = pool
        .filter(([, , title]) => !q || title.toLowerCase().includes(q))
        .map(([projectId, slug, title, description], i) => ({ projectId, slug, title, description, iconUrl: null, author: "dev", downloads: 9_000_000 - i * 1_200_000, follows: 30_000 - i * 3000, categories: ["optimization"], projectType: p.kind, dateModified: "2026-09-20T10:00:00Z", gallery: null }));
      return { hits, offset: p.offset, limit: p.limit, total: hits.length, stale: false, staging: true, rateLimit: { limit: 300, remaining: 297, resetIn: 42 } };
    }
    case "library_tags":
      return { categories: ["optimization", "utility", "decoration", "adventure", "realistic", "simplistic"].map((name) => ({ name, projectType: name === "realistic" ? "shader" : "mod", header: "categories" })) };
    case "library_status":
      return { base: "https://staging-api.modrinth.com/v2", staging: true, userAgent: "IconicDemon/FlintClient/1.0.0 (flintclient.com)", rateLimit: {} };
    case "library_project": {
      const m = SAMPLE_MODS.find(([id]) => id === args.id) ?? SAMPLE_MODS[0];
      return { id: m[0], slug: m[1], title: m[2], description: m[3], body: `## ${m[2]}\n\n${m[3]}\n\n- Development mock description.`, iconUrl: null, projectType: "mod", categories: ["optimization"], loaders: ["fabric"], gameVersions: ["26.3", "26.2"], clientSide: "required", serverSide: "optional", downloads: 9_000_000, followers: 30_000, license: { id: "LGPL-3.0-only", name: "GNU Lesser General Public License v3.0 only" }, published: "2020-01-01T00:00:00Z", updated: "2026-09-20T00:00:00Z", gallery: [], members: [{ username: "dev", role: "Owner", owner: true }], stale: false };
    }
    case "library_versions":
      return { versions: [{ id: `v-${args.id}`, projectId: args.id, name: "1.0.0", versionNumber: "1.0.0", versionType: "release", gameVersions: ["26.3"], loaders: ["fabric"], datePublished: "2026-09-20T00:00:00Z", downloads: 120_000, changelog: "- Mock changelog", dependencies: [], files: [{ filename: "mock.jar", size: 1_200_000, primary: true }] }], stale: false };
    case "content_plan":
    case "mods_resolve": {
      await sleep(700);
      if (fail === "resolve") throw "Modrinth search failed: could not connect (check your internet connection)";
      const requests = args.requests as { query?: string; project?: string; source?: string; reason?: string; kind?: string }[];
      const items: any[] = requests.flatMap((r, i) => {
        const m = SAMPLE_MODS.find(([id, slug, title]) => (r.project && (r.project === slug || r.project === id)) || (r.query && title.toLowerCase().includes(r.query.toLowerCase().split(" ")[0]))) ?? SAMPLE_MODS[i % SAMPLE_MODS.length];
        return [{ projectId: m[0], slug: m[1], title: m[2], description: m[3], kind: r.kind ?? "mod", versionId: `v${m[0]}`, versionNumber: "1.0.0", versionType: "release", fileName: `${m[1]}.jar`, size: 1_200_000, source: r.source ?? "user", reason: r.reason, required: true, status: "ok" }];
      });
      items.push({ projectId: "P7dR8mSH", slug: "fabric-api", title: "Fabric API", description: "Core library for Fabric mods.", kind: "mod", versionId: "vapi", versionNumber: "0.162.0", versionType: "release", fileName: "fabric-api.jar", size: 1_700_000, source: "dependency", requiredBy: items[0]?.title, required: true, status: "ok" });
      items.push({ projectId: "mOgUt4GM", slug: "modmenu", title: "Mod Menu", description: "Lists installed mods.", kind: "mod", versionId: "vmm", versionNumber: "15.0.0", versionType: "release", fileName: "modmenu.jar", size: 700_000, source: "optional", requiredBy: items[0]?.title, required: false, status: "ok" });
      return { items, unmatched: [], blockers: [], gameVersion: "26.3", loader: "fabric" };
    }
    case "content_install":
    case "mods_install": {
      const jobId = `job-${Date.now().toString(36)}`;
      const items = args.items as { versionId: string; source: string; kind?: string }[];
      const job: any = { id: jobId, kind: "install", profileId: args.profileId, title: args.title ?? "Mods for your new profile", state: "running", stage: "Downloading", bytesDone: 0, bytesTotal: null, itemsDone: 0, itemsTotal: items.length, createdAt: Date.now() / 1000 };
      jobs.unshift(job);
      const run = async () => {
        for (const [i, item] of items.entries()) {
          await sleep(220);
          const list = (db.mods[args.profileId] = (db.mods[args.profileId] ?? []).filter((m: any) => m.projectId !== item.versionId));
          const sample = SAMPLE_MODS.find(([id]) => `v${id}` === item.versionId);
          list.push(mockItem(sample?.[2] ?? `Mod ${item.versionId}`, item.versionId, item.source, item.kind ?? "mod"));
          emit("job", { ...job, itemsDone: i + 1 });
        }
        save();
        job.state = "done";
        job.itemsDone = items.length;
        emit("job", { ...job });
      };
      if (cmd === "content_install") {
        void run();
        return jobId;
      }
      await run();
      return { installed: items.map((i) => i.versionId), failed: [] };
    }
    case "modpack_install":
      return "job-modpack";
    case "content_list": {
      const all = (db.mods[args.profileId] ?? []) as any[];
      const by = (k: string) => all.filter((m) => (m.kind ?? "mod") === k);
      return { profileId: args.profileId, mods: by("mod"), resourcepacks: by("resourcepack"), shaders: by("shader"), datapacks: by("datapack"), worlds: [], running: states[args.profileId]?.state === "running", irisInstalled: false };
    }
    case "content_identify":
      return { identified: 0 };
    case "content_set_enabled":
      db.mods[args.profileId] = (db.mods[args.profileId] ?? []).map((m: any) => (args.fileNames.includes(m.fileName) ? { ...m, enabled: args.enabled } : m));
      return save();
    case "content_remove":
      db.mods[args.profileId] = (db.mods[args.profileId] ?? []).filter((m: any) => !args.items.some((i: any) => i.fileName === m.fileName));
      return save();
    case "content_rollback":
      return;
    case "content_updates": {
      const first = (db.mods[args.profileId] ?? [])[0] as any;
      return { updates: first ? [{ kind: "mod", fileName: first.fileName, title: first.title, projectId: first.projectId, tracked: true, currentVersion: first.versionNumber, versionId: "vnew", versionNumber: "1.1.0", versionType: "release", datePublished: "2026-10-01T00:00:00Z", changelog: "Fixes" }] : [], checkedAt: Date.now() / 1000 };
    }
    case "content_update":
      return "job-update";
    case "profile_worlds":
      return [{ id: "New World", name: "New World", lastPlayed: Date.now() / 1000 - 7200, icon: null, datapacks: 0 }];
    case "profile_files":
      return { path: `~/Library/Application Support/com.flintclient.desktop/profiles/${args.profileId}`, total: 15_000_000, folders: ["mods", "resourcepacks", "shaderpacks", "saves", "screenshots", "config", "logs"].map((id, i) => ({ id, count: i, size: i * 1_000_000, exists: i < 5 })) };
    case "profile_logs":
      return [{ name: "latest.log", folder: "logs", kind: "log", size: 42_000, modified: Date.now() / 1000 }];
    case "profile_log_read":
      return "[12:00:00] [main/INFO]: Loading Minecraft 26.3 with Fabric Loader 0.19.5\n[12:00:01] [main/INFO]: Mock log line";
    case "jobs_list":
      return jobs;
    case "job_cancel":
    case "job_dismiss":
      return;
    case "system_info":
      return { totalMemory: 17_179_869_184, cpus: 10, os: "macos", arch: "aarch64" };
    case "java_validate":
      if (!String(args.path).includes("java")) throw "That file isn't a Java executable.";
      return { version: "21.0.4", major: 21, summary: "Java 21.0.4 (Eclipse Temurin)" };
    case "skin_profile":
      return { name: "Notch", variant: "classic", capes: capes() };
    case "skin_upload":
    case "skin_reset":
    case "cape_set":
      await sleep(500);
      if (fail === "skin" && cmd === "skin_upload") throw "Minecraft didn't accept the skin: the image has transparent pixels on the base layer.";
      if (cmd === "cape_set") capeActive = (args.capeId as string | null) ?? null;
      return { name: "Notch", variant: args.variant ?? "classic", capes: capes() };
    case "profiles_duplicate": {
      const src = db.profiles.find((p) => p.id === args.id)!;
      const p: Profile = { ...src, id: Math.random().toString(16).slice(2, 14), name: args.name, createdAt: Date.now() / 1000 };
      db.profiles.push(p);
      save();
      return p;
    }
    case "profiles_delete":
      db.profiles = db.profiles.filter((p) => p.id !== args.id);
      if (db.selected === args.id) db.selected = db.profiles[0]?.id ?? null;
      save();
      return { keptFolder: args.deleteFiles ? null : `profiles/${args.id}` };
    case "ai_suggest": {
      if (fail === "ai") throw "AI_UNAVAILABLE";
      if (mockQuota.remaining <= 0) throw `AI_QUOTA:${JSON.stringify({ limit: 3, remaining: 0, resetInMs: 12 * 3600_000 })}`;
      await sleep(900);
      return {
        goals: [
          { category: "performance", label: "Smooth FPS", why: "Raises and steadies frame rates.", searches: [], projects: ["sodium", "lithium"] },
          { category: "minimap", label: "Minimap", why: "Shows where you are and what's nearby.", searches: [], projects: ["xaeros-minimap"] },
        ],
        constraints: { lowEndHardware: false, ramGb: null, keepVanilla: false },
        exclude: [],
        note: null,
        model: "devmock",
        quota: { limit: 3, remaining: Math.max(0, (mockQuota.remaining -= 1)), resetInMs: mockQuota.remaining <= 0 ? 12 * 3600_000 : null },
      };
    }
    case "ai_cancel":
      return;
    case "discord_status":
      return db.settings?.discordPresence === false ? "off" : "idle";
    case "auto_sync_set_enabled":
    case "auto_sync_status":
    case "auto_sync_dismiss": {
      if (cmd === "auto_sync_dismiss") autoSyncDismissed = true;
      const enabled = cmd === "auto_sync_set_enabled" ? args.enabled === true : db.settings?.autoSync !== false;
      const mode = params.get("autosync");
      const notice = enabled && mode === "notice" && !autoSyncDismissed ? { id: "unreadable:demo", text: "A settings file in ‘Survival’ couldn't be read; Auto Sync is leaving that profile's settings alone." } : undefined;
      return { enabled, waiting: enabled && mode === "waiting", notice };
    }
    case "ai_status":
      return { configured: fail !== "ai", reachable: fail !== "ai" };
    case "ai_quota":
      return { limit: 3, remaining: Math.max(0, mockQuota.remaining), resetInMs: mockQuota.remaining <= 0 ? 12 * 3600_000 : null };
    case "open_external":
      window.open(args.url, "_blank", "noopener");
      return;
    case "reveal_folder":
      return;
    case "launch_at_login_get":
      return !!db.settings.launchAtLogin;
    case "launch_at_login_set":
      return args.enabled;
    case "storage_usage":
      return { game: 690_000_000, runtimes: 110_000_000, profiles: 15_000_000, cache: 120_000 };
    case "cache_clear":
      return { removedBytes: 120_000, failed: [] };
    default:
      throw new Error(`devMock: unknown command ${cmd}`);
  }
}
