// Browser-only development stand-in for the Rust backend (vite dev without Tauri).
// Never bundled into production builds; see initBridge() in api.ts.
import type { Account, Profile, ProfileState } from "./api";
import { RELEASE_NOTES } from "../data/releaseNotes";
import { createPartnersMock } from "./devMockPartners";
import { createSocialMock, demoSkin } from "./devMockSocial";
import { communityAccounts, communitySkin, createCommunityMock } from "./devMockCommunity";
import { createStoreMock } from "./devMockStore";

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
// `?demo`: a signed-in player with a 26.2 profile that has logs and a crash report (to look at pages without clicking
// through onboarding: `?demo&page=profiles:demo262:logs`), and one profile already imported from the Modrinth App.
if (params.has("demo")) {
  const player = { uuid: "069a79f444e94726a5befca90e38aaf5", username: "IconicDemon", addedAt: Date.now() / 1000 };
  db = { ...blank(), onboarding: { completed: true }, accounts: [player as Account], active: player.uuid, selected: "demo262",
    profiles: [
      { id: "demo262", name: "Minecraft 26.2", gameVersion: "26.2", loader: "fabric", loaderVersion: "0.20.1", createdAt: Date.now() / 1000, installed: true } as Profile,
      { id: "demoiconic", name: "Iconic", gameVersion: "26.3", loader: "fabric", loaderVersion: "0.19.5", createdAt: Date.now() / 1000 - 86_400 * 3, installed: true, origin: { launcher: "modrinth", key: importKey("modrinth", "Iconic"), importedAt: Date.now() / 1000 - 86_400 * 3 } } as Profile,
    ],
    mods: { demo262: demoContent() } };
  // `?demo&rail`: six profiles (three pinned) and the sidebar's profile list open, to look at the rail.
  if (params.has("rail")) {
    const now = Date.now() / 1000;
    const more = ["Bedwars", "Skyblock", "Survival", "PVP 26.2"].map(
      (name, i) => ({ id: `rail${i}`, name, gameVersion: i % 2 ? "26.2" : "1.21.11", loader: "fabric", loaderVersion: "0.19.5", createdAt: now - 86_400 * (i + 4), installed: true, ...(i < 3 ? { pinnedAt: now - i } : {}) }) as Profile,
    );
    db.profiles = [...db.profiles, ...more];
    db.settings = { ...db.settings, sidebarProfilesOpen: true };
  }
  // `?demo&alt`: a second account (Kestrel) to switch to, with no Minecraft cape and no Store sign-in.
  if (params.has("alt")) db.accounts = [...db.accounts, { uuid: "1a2b3c4d5e6f40718293a4b5c6d7e8f1", username: "Kestrel", addedAt: Date.now() / 1000 } as Account];
  // `?demo&signedout`: past onboarding with no account signed in (Friends' signed-out page).
  if (params.has("signedout")) {
    db.accounts = [];
    db.active = null;
  }
}
// `?ob=<step>`: onboarding opened at that step (2 is Import), already signed in. `flow` is Onboarding.tsx's FLOW_VERSION.
if (params.has("ob")) {
  const player = { uuid: "069a79f444e94726a5befca90e38aaf5", username: "IconicDemon", addedAt: Date.now() / 1000 };
  db = { ...blank(), onboarding: { completed: false, flow: 4, step: Number(params.get("ob")) || 2, answers: { accountUuid: player.uuid } }, accounts: [player as Account], active: player.uuid };
}

// `?demo&friends`: the Friends button is shown in the sidebar (it's off by default, Settings → General).
if (params.has("demo") && params.has("friends")) {
  db.settings = { ...db.settings, friendsInSidebar: true };
}
// `?demo&community=fan|booster|newface|notlinked|outage|grace|screening|checking|off`: five fictional players signed in
// (devMockCommunity.ts), for Settings → Connected Accounts and the Store's Community Rewards. Add `&store=in`.
const community = communityAccounts(params);
if (params.has("demo") && community) {
  db.accounts = community.accounts;
  db.active = community.active;
}
// `&lang=hi`: the preview in that language (Settings → Language), to look at a translation.
if (params.get("lang")) {
  db.settings = { ...db.settings, language: params.get("lang") };
}

// Friends (devMockSocial.ts): for the active account, unless it is the offline test identity.
const socialMock = createSocialMock(
  params,
  () => {
    const account = db.accounts.find((a) => a.uuid === db.active);
    return account && !account.qa ? { uuid: account.uuid, name: account.username } : null;
  },
  emit,
);

// The Flint Store (devMockStore.ts): `?store=in,many,anim,orders…` and `&cape=…`.
const communityMock = createCommunityMock(params, () => db.active);
const storeMock = createStoreMock(params, (uuid) => db.accounts.find((a) => a.uuid === uuid)?.username ?? null, () => db.active, communityMock.extension);
communityMock.attach(storeMock);

/**
 * The demo profile's content: Flint's mods, a pinned and switched-off one, a beta, one added by hand that Modrinth knows
 * and one it doesn't, a resource pack and a shader pack. `DEMO_UPDATES` has what the update check finds for them.
 */
function demoContent() {
  const item = (kind: string, title: string, projectId: string, fileName: string, versionNumber: string, extra: Record<string, unknown> = {}) => ({
    kind, title, projectId, fileName, versionNumber, versionId: `v-${projectId}`, iconUrl: null, source: "user", size: 1_400_000, enabled: true, missing: false, tracked: true, requires: [], installedAt: Date.now() / 1000 - 86400 * 9, ...extra,
  });
  return [
    item("mod", "Sodium", "AANobbMI", "sodium-fabric-0.6.12+mc26.2.jar", "0.6.12+mc26.2", { size: 1_180_000 }),
    item("mod", "Lithium", "gvQqBUqZ", "lithium-fabric-0.15.1+mc26.2.jar", "0.15.1+mc26.2", { enabled: false, pinned: true, size: 720_000 }),
    item("mod", "Iris Shaders", "YL57xq9U", "iris-fabric-1.8.0-beta.3+mc26.2.jar", "1.8.0-beta.3+mc26.2", { size: 2_900_000 }),
    item("mod", "Zoomify", "w7ThoJFB", "zoomify-2.14.2+26.2.jar", "2.14.2+26.2", { source: "manual", tracked: false, identified: true, size: 380_000 }),
    item("mod", "Fabric API", "P7dR8mSH", "fabric-api-0.130.0+26.2.jar", "0.130.0+26.2", { source: "dependency", size: 2_400_000 }),
    item("mod", "Xaero's Minimap", "1bokaNcj", "Xaeros_Minimap_25.2.10_Fabric_26.2.jar", "25.2.10", { size: 1_900_000 }),
    item("mod", "Mod Menu", "mOgUt4GM", "modmenu-15.0.0.jar", "15.0.0", { source: "optional", size: 650_000 }),
    item("mod", "old-zoom-tweak", "", "old-zoom-tweak.jar", "", { source: "manual", tracked: false, identified: false, projectId: null, versionId: null, size: 42_000 }),
    item("resourcepack", "Faithful 32x", "Ma5BV2xc", "Faithful 32x - 26.2.zip", "26.2.1", { size: 31_000_000 }),
    item("shader", "Complementary Shaders - Reimagined", "HVnmMxH1", "ComplementaryReimagined_r5.4.zip", "r5.4", { size: 610_000 }),
  ];
}
const DEMO_CRASH = [
  "---- Minecraft Crash Report ----",
  "// Who set us up the TNT?",
  "",
  "Time: 2026-10-30 17:48:13",
  "Description: Initializing game",
  "",
  "java.lang.RuntimeException: Mixin transformation of net.minecraft.client.gui.components.PlayerTabOverlay failed",
  "\tat net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.getPostMixinClassByteArray(KnotClassDelegate.java:440)",
  "\tat net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.tryLoadClass(KnotClassDelegate.java:340)",
  "Caused by: org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error was encountered",
  "\tat org.spongepowered.asm.mixin.transformer.MixinProcessor.applyMixins(MixinProcessor.java:380)",
  "",
  "-- System Details --",
  "Details:",
  "\tMinecraft Version: 26.2",
  "\tMinecraft Version ID: 26.2",
  "\tOperating System: Windows 11 (amd64) version 10.0",
  "\tJava Version: 25.0.1, Microsoft",
  "\tMemory: 312 MB / 1024 MB up to 4096 MB",
  "\tLaunched Version: fabric-loader-0.20.1-26.2",
  "\tLauncher name: flint",
  "\tBackend library: LWJGL version 3.4.1-snapshot",
  "\tGraphics Device: NVIDIA GeForce RTX 5060/PCIe/SSE2",
  "\tGraphics Backend: OpenGL",
  "\tIs Modded: Definitely; Client brand changed to 'fabric'",
  "\tType: Client",
  "\tLocale: en_US",
].join("\n");
const DEMO_LOG = Array.from({ length: 120 }, (_, i) => {
  const t = `[17:55:${String(10 + Math.floor(i / 4)).padStart(2, "0")}]`;
  if (i === 40) return `${t} [main/WARN]: Reference map 'iris.refmap.json' for mixins.iris.json could not be read.`;
  if (i === 70) return `${t} [Render thread/ERROR]: Failed to load gamemodes!\njava.net.ConnectException: Connection refused\n\tat java.base/sun.nio.ch.Net.pollConnect(Native Method)`;
  return `${t} [Render thread/INFO]: Loading line ${i} of the game's start-up`;
}).join("\n");

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const fail = params.get("fail") ?? "";
const states: Record<string, ProfileState> = {};
// `?running=<profile id>`: that profile's game is running (packs then go into it live, mods wait for a restart).
if (params.get("running")) states[params.get("running")!] = { profileId: params.get("running")!, state: "running" } as ProfileState;
const setState = (s: ProfileState) => {
  states[s.profileId] = s;
  emit("profile-state", s);
};

const jobs: unknown[] = [];
// Auto Sync's status line: `?autosync=waiting` shows "Waiting for your first game", `?autosync=notice` a notice.
let autoSyncDismissed = false;
// Settings → Syncing: the switches as the backend keeps them (servers, hotbars and command history start off), the
// profiles left out, and a few invented profiles when the mock has fewer than three.
const syncMock = {
  off: new Set<string>(["servers", "hotbars", "commandHistory"]),
  started: {} as Record<string, string>,
  excluded: new Set<string>(),
};
const SYNC_DEMO_PROFILES = [
  { id: "survival", name: "Survival", gameVersion: "26.3", lastPlayed: Date.now() / 1000 - 1800, backups: 3 },
  { id: "pvp", name: "PvP 1.8.9", gameVersion: "1.8.9", lastPlayed: Date.now() / 1000 - 86400, backups: 1 },
  { id: "modded", name: "Create Modded", gameVersion: "1.21.11", lastPlayed: Date.now() / 1000 - 3 * 86400, backups: 0 },
  { id: "snapshot", name: "Old Snapshot", gameVersion: "1.20.1", lastPlayed: Date.now() / 1000 - 30 * 86400, backups: 0, note: "Shares its changes; Flint doesn't change Minecraft 1.20.1 files" },
];
function syncOverview() {
  const enabled = db.settings?.autoSync !== false;
  const base = db.profiles.length >= 3 ? db.profiles.map((p) => ({ id: p.id, name: p.name, gameVersion: p.gameVersion, lastPlayed: p.lastPlayed ?? undefined, backups: 0, note: undefined as string | undefined })) : SYNC_DEMO_PROFILES;
  const profiles = base.map((p) => ({ ...p, excluded: syncMock.excluded.has(p.id), canStart: !p.note && !!p.lastPlayed && !syncMock.excluded.has(p.id), lastBackup: p.backups ? Date.now() / 1000 - 3600 : undefined }));
  const mode = params.get("autosync");
  const notices = enabled && mode === "notice" && !autoSyncDismissed ? [{ id: "unreadable:demo", text: "A settings file in ‘Survival’ couldn't be read; Auto Sync is leaving that profile's settings alone." }] : [];
  const ids = ["gameOptions", "keybinds", "servers", "resourcePacks", "shaderPacks", "hotbars", "commandHistory", "modSettings"];
  return {
    enabled,
    waiting: enabled && mode === "waiting",
    notices,
    switches: ids.map((id) => ({ id, on: !syncMock.off.has(id), startedFrom: syncMock.started[id], startedAt: syncMock.started[id] ? Date.now() / 1000 : undefined })),
    profiles,
    startedFrom: profiles[0]?.id,
    defaultSource: profiles.find((p) => p.canStart)?.id,
    lastSync: enabled && mode !== "waiting" && profiles[0] ? { at: Date.now() / 1000 - 600, profile: profiles[0].id } : undefined,
  };
}
const JAVA_PLAN = ["26.3", "26.2", "26.1.2", "26.1.1", "26.1", "1.21.11", "1.21.10", "1.21.1", "1.8.9"].map((version) => {
  const own = version.startsWith("26.") ? { component: "java-runtime-epsilon", major: 25, minimum: 25 } : version === "1.8.9" ? { component: "jre-legacy", major: 8, minimum: 8 } : { component: "java-runtime-delta", major: 21, minimum: 21 };
  const withMenu = version.startsWith("26.") ? own : { component: "java-runtime-epsilon", major: 25, minimum: 21, fallback: 21 };
  return { version, withMenu, withoutMenu: own };
});
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
// `&mccape`: the demo account wears a Minecraft cape (Migrator) at start.
let capeActive: string | null = params.has("mccape") ? "c1" : null;
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

/** What the update check finds for the demo profile's content (see `demoContent`), by project. */
const version = (versionId: string, versionNumber: string, versionType: string, datePublished: string, changelog: string, extra: Record<string, unknown> = {}) => ({ versionId, versionNumber, versionType, datePublished, changelog, size: 1_300_000, adds: [] as any[], blocked: null as string | null, ...extra });
const DEMO_UPDATES = [
  {
    projectId: "AANobbMI",
    iconUrl: null,
    currentVersion: "0.6.12+mc26.2",
    currentVersionId: "v-AANobbMI",
    ...version("sod614", "0.6.14+mc26.2", "release", "2026-10-21T15:02:11Z", "## Fixes\n- Fixed flickering chunks after teleporting across dimensions\n- Fixed a crash with some AMD drivers when the window is resized\n\n## Performance\n- Faster chunk meshing on CPUs with many cores\n- Lower memory use for large render distances"),
    alternatives: [
      version("sod70b2", "0.7.0-beta.2+mc26.2", "beta", "2026-10-28T09:40:00Z", "## 0.7.0 beta 2\n\nThe new renderer, still in testing. Expect rough edges.\n\n- Rewritten translucency sorting\n- New video settings page"),
      version("sod613", "0.6.13+mc26.2", "release", "2026-10-02T12:00:00Z", "- Fixed fog colour at the edge of the world border"),
    ],
  },
  { projectId: "gvQqBUqZ", iconUrl: null, currentVersion: "0.15.1+mc26.2", currentVersionId: "v-gvQqBUqZ", ...version("lit160", "0.16.0+mc26.2", "release", "2026-10-25T10:00:00Z", "- Faster hopper and redstone ticking\n- Fixed rare desync of block entities"), alternatives: [] },
  {
    projectId: "YL57xq9U",
    iconUrl: null,
    currentVersion: "1.8.0-beta.3+mc26.2",
    currentVersionId: "v-YL57xq9U",
    ...version("iris80b5", "1.8.0-beta.5+mc26.2", "beta", "2026-10-26T18:30:00Z", "### Iris 1.8.0 beta 5\n- Shadows render correctly with Sodium 0.6.14\n- Fixed shader packs failing to load from folders with spaces"),
    alternatives: [version("iris80b4", "1.8.0-beta.4+mc26.2", "beta", "2026-10-12T08:00:00Z", "- Fixed hand rendering with some packs")],
  },
  {
    projectId: "w7ThoJFB",
    iconUrl: null,
    currentVersion: "2.14.2+26.2",
    currentVersionId: "v-w7ThoJFB",
    ...version("zoom215", "2.15.0+26.2", "release", "2026-10-19T11:11:00Z", "- Smooth zoom curve options\n- Settings moved to YetAnotherConfigLib", { adds: [{ projectId: "1eAoo2KR", title: "YetAnotherConfigLib", iconUrl: null, kind: "mod", versionId: "yacl", versionNumber: "3.8.0+26.2", size: 870_000 }] }),
    alternatives: [],
  },
  {
    projectId: "1bokaNcj",
    iconUrl: null,
    currentVersion: "25.2.10",
    currentVersionId: "v-1bokaNcj",
    ...version("xae253", "25.3.0", "release", "2026-10-27T13:00:00Z", "- New waypoint menu", { blocked: "Needs Forge Config API Port: Not available for Minecraft 26.2" }),
    alternatives: [],
  },
  { projectId: "Ma5BV2xc", iconUrl: null, currentVersion: "26.2.1", currentVersionId: "v-Ma5BV2xc", ...version("faith2622", "26.2.2", "release", "2026-10-22T16:00:00Z", "- New copper golem textures\n- Sharper glass panes"), alternatives: [] },
  { projectId: "HVnmMxH1", iconUrl: null, currentVersion: "r5.4", currentVersionId: "v-HVnmMxH1", ...version("comp55", "r5.5", "release", "2026-10-15T20:00:00Z", "## r5.5\n- Improved water reflections\n- Fixed clouds disappearing at high render distances"), alternatives: [] },
];

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
    fabric: true,
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

// ---------- Profile import (docs/PROFILE_IMPORT.md) ----------
// `?import=none` finds no launchers, `?import=many` lists 1,000 profiles in the Modrinth App, `?import=full` leaves too
// little disk space, `?import=fail` makes the second profile fail while copying.
const importMode = params.get("import") ?? "";
const IMPORT_VERSIONS = new Set(catalog.versions.map((v) => v.id));
const MB = 1024 * 1024;

/** A stable, meaningless key (the backend hashes the launcher and its own profile id). */
function importKey(launcher: string, name: string) {
  let h = 2166136261;
  for (const c of `${launcher}/${name}`) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
  return `k${(h >>> 0).toString(16)}`;
}

/** Small block-like pictures, as launchers keep them for their profiles. */
const iconCache = new Map<string, string>();
function mockIcon(top: string, side: string) {
  const id = `${top}${side}`;
  if (!iconCache.has(id)) {
    const c = document.createElement("canvas");
    c.width = c.height = 16;
    const g = c.getContext("2d")!;
    g.fillStyle = side;
    g.fillRect(0, 0, 16, 16);
    g.fillStyle = top;
    g.fillRect(0, 0, 16, 5);
    g.fillStyle = "rgba(0,0,0,0.18)";
    for (let i = 0; i < 9; i++) g.fillRect((i * 7) % 15, 5 + ((i * 5) % 10), 2, 2);
    iconCache.set(id, c.toDataURL("image/png"));
  }
  return iconCache.get(id)!;
}
const ICONS: Record<string, [string, string]> = { grass: ["#5d9b3c", "#7a5534"], ember: ["#e0913f", "#5a3417"], crimson: ["#b43a3a", "#3c1414"], ocean: ["#3f8fbf", "#1d3550"], prism: ["#a07ad6", "#3a2a55"], gold: ["#e8c25a", "#6b5220"] };

type MockSource = [name: string, version: string | null, loader: string, mods: number, daysAgo: number | null, icon?: string, worlds?: number];
const MODRINTH: MockSource[] = [
  ["Iconic", "26.3", "fabric", 36, 0, "ember", 3],
  ["SMP main", "26.3", "fabric", 27, 1, undefined, 1],
  ["Demon", "26.3", "fabric", 42, 2, "crimson", 2],
  ["Creator SMP", "26.3", "fabric", 33, 2],
  ["Recording", "1.21.11", "fabric", 19, 3],
  ["SMP test", "26.2", "fabric", 29, 3],
  ["Optimized", "26.3", "fabric", 18, 4, "grass"],
  ["PvP 1.8.9", "1.8.9", "forge", 23, 5, undefined, 1],
  ["Fabulously Optimized", "1.21.11", "fabric", 74, 6],
  ["Hypixel", "1.8.9", "vanilla", 0, 6],
  ["Bedwars practice", "1.21.11", "fabric", 12, 8],
  ["All the Mods 10", "1.21.1", "neoforge", 412, 9, "prism", 2],
  ["Create: Above and Beyond", "1.20.1", "forge", 183, 11, undefined, 4],
  ["Skyblock", "1.21.10", "fabric", 21, 12, "ocean", 1],
  ["Survival with Sam", "26.2", "fabric", 15, 13, undefined, 1],
  ["Building", "26.3", "vanilla", 0, 15, undefined, 6],
  ["Shader tests", "26.1.2", "fabric", 9, 17],
  ["Cobblemon", "1.21.1", "fabric", 58, 19, "gold", 1],
  ["Redstone lab", "26.3", "fabric", 6, 21, undefined, 2],
  ["Quilted", "1.21.1", "quilt", 31, 24],
  ["Hardcore", "26.2", "vanilla", 0, 26, undefined, 1],
  ["Parkour maps", "1.21.11", "vanilla", 0, 30, undefined, 8],
  ["Speedrun 1.16", "1.16.1", "fabric", 14, 34],
  ["Minigames", "1.21.10", "fabric", 8, 37],
  ["Better MC", "1.21.1", "forge", 260, 41, undefined, 1],
  ["Vault Hunters", "1.18.2", "forge", 220, 45, undefined, 1],
  ["Legacy PvP", "1.8.9", "fabric", 11, 52],
  ["Datapack testing", "26.3", "vanilla", 0, 58, undefined, 3],
  ["Friends realm", "26.1", "fabric", 22, 63, undefined, 1],
  ["Simply Optimized", "1.21.10", "fabric", 31, 70],
  ["Origins", "1.21.1", "fabric", 27, 81, undefined, 1],
  ["Tech world", "1.21.1", "neoforge", 140, 96, undefined, 1],
  ["RLCraft", "1.12.2", "forge", 172, 120, undefined, 2],
  ["Old survival", "1.21.10", "vanilla", 0, 140, undefined, 2],
  ["Snapshot testing", "26.3", "fabric", 3, 160],
  ["Hub practice", "1.21.11", "fabric", 16, 200],
  ["Modfest", "26.2", "fabric", 45, 260],
  ["SkyFactory 4", "1.16.5", "forge", 190, 400, undefined, 1],
  ["Testing", "26.3", "fabric", 2, null],
  ["Empty", "26.3", "vanilla", 0, null],
];
const IMPORT_SOURCES: Record<string, { name: string; profiles: MockSource[]; shared?: boolean }> = {
  modrinth: {
    name: "Modrinth App",
    profiles:
      importMode === "many"
        ? Array.from({ length: 1000 }, (_, i): MockSource => {
            const [name, version, loader, mods, days, icon, worlds] = MODRINTH[i % MODRINTH.length];
            return [i < MODRINTH.length ? name : `${name} ${Math.floor(i / MODRINTH.length) + 1}`, version, loader, mods, days === null ? null : days + Math.floor(i / MODRINTH.length) * 3, icon, worlds];
          })
        : MODRINTH,
  },
  prism: {
    name: "Prism Launcher",
    profiles: [
      ["Fabric 26.3", "26.3", "fabric", 24, 1, "prism", 1],
      ["Vanilla+ 1.21.11", "1.21.11", "fabric", 11, 4],
      ["Forge 1.21.1", "1.21.1", "forge", 64, 10, undefined, 1],
      ["Speedrun", "1.16.1", "fabric", 9, 33],
      ["Testing instance", "26.2", "vanilla", 0, 47],
      ["GTNH", "1.7.10", "forge", 230, 300, undefined, 1],
    ],
  },
  lunar: {
    name: "Lunar Client",
    shared: true,
    profiles: [
      ["Lunar 1.8.9", "1.8.9", "vanilla", 0, 0, undefined, 2],
      ["Lunar 1.21.11", "1.21.11", "vanilla", 0, 7, undefined, 2],
      ["Lunar 26.3", "26.3", "vanilla", 0, 16, undefined, 2],
    ],
  },
  feather: {
    name: "Feather Client",
    profiles: [
      ["Feather 26.3", "26.3", "fabric", 14, 2, undefined, 1],
      ["Feather 1.21.11", "1.21.11", "fabric", 9, 20],
    ],
  },
  official: {
    name: "Minecraft Launcher",
    shared: true,
    profiles: [
      ["Latest release", "26.3", "vanilla", 0, 5, "grass", 4],
      ["fabric-loader-26.3", "26.3", "fabric", 8, 12, undefined, 4],
    ],
  },
  folder: {
    name: "PvP Pack",
    profiles: [
      ["PvP Pack", "1.21.11", "fabric", 17, 9, "gold", 1],
      ["PvP Pack (vanilla)", "1.21.11", "vanilla", 0, 40],
    ],
  },
};

function importLaunchers() {
  if (importMode === "none") return [];
  const found = (id: string) => {
    const s = IMPORT_SOURCES[id];
    const importable = s.profiles.filter(([, v]) => v && IMPORT_VERSIONS.has(v)).length;
    return { id, name: s.name, status: "found", profiles: s.profiles.length, importable, message: null };
  };
  return [
    found("modrinth"),
    found("prism"),
    found("lunar"),
    found("feather"),
    found("official"),
    { id: "curseforge", name: "CurseForge", status: "empty", profiles: 0, importable: 0, message: "It's installed, but has no Minecraft profiles yet." },
    { id: "dawn", name: "Dawn", status: "unreadable", profiles: 0, importable: 0, message: "Its profile list is locked by Dawn. Close Dawn, then scan again." },
  ];
}

function importListing(launcher: string, folder: string | null) {
  const s = IMPORT_SOURCES[launcher];
  if (!s) throw `${launcher} has no profiles Flint can read.`;
  const now = Date.now() / 1000;
  const origins = new Map(db.profiles.filter((p) => p.origin).map((p) => [p.origin!.key, p]));
  const name = launcher === "folder" ? (folder?.split(/[\\/]/).pop() ?? s.name) : s.name;
  const profiles = s.profiles.map(([pname, version, loader, mods, days, icon, worlds]) => {
    const key = importKey(launcher, pname);
    const offered = !!version && IMPORT_VERSIONS.has(version);
    const settings = offered && ["forge", "neoforge", "quilt"].includes(loader);
    const flint = origins.get(key);
    return {
      key,
      name: pname,
      gameVersion: version,
      loader,
      loaderVersion: loader === "fabric" ? "0.19.5" : loader === "vanilla" ? null : "47.3.0",
      // Spread over the day so "Today" isn't always the same hour.
      lastPlayed: days === null ? null : Math.floor(now - days * 86_400 - ((pname.length * 977) % 20_000)),
      icon: icon ? mockIcon(...ICONS[icon]) : null,
      mods,
      worlds: worlds ?? 0,
      support: !offered ? "unsupported" : settings ? "settings" : "full",
      supportNote: !offered
        ? `Flint doesn't offer Minecraft ${version}, so this profile can't come over.`
        : settings
          ? `Flint can't run ${loader === "neoforge" ? "NeoForge" : loader === "quilt" ? "Quilt" : "Forge"} mods, so this brings its settings, keybinds, packs, servers and worlds without them.`
          : null,
      imported: flint ? { profileId: flint.id, name: flint.name } : null,
      sharedFolder: !!s.shared,
    };
  });
  const importable = profiles.filter((p) => p.support !== "unsupported").length;
  return { launcher: { id: launcher, name, status: "found", profiles: profiles.length, importable, message: null }, profiles };
}

/** What one source profile brings, as `import_preview` reports it. */
function importPlan(launcher: string, folder: string | null, key: string, worlds: boolean, taken: Set<string>) {
  const p = importListing(launcher, folder).profiles.find((x) => x.key === key);
  if (!p) return null;
  const seed = p.name.length;
  const part = (id: string, status: string, files: number, bytes: number, count: number, note: string | null = null) => ({ id, status, files, bytes, count, note });
  const parts = [];
  const warnings: string[] = [];
  if (p.mods > 0) {
    if (p.support === "settings") parts.push(part("mods", "unsupported", p.mods, 0, 0, `${p.mods} ${p.loader === "neoforge" ? "NeoForge" : p.loader === "quilt" ? "Quilt" : "Forge"} mods stay behind.`));
    else if (p.gameVersion === "1.8.9") parts.push(part("mods", "attention", p.mods, 0, 0, "These are Legacy Fabric mods, which don't run on Flint's 1.8.9, so they stay behind."));
    else if (p.mods > 30) parts.push(part("mods", "attention", p.mods, p.mods * 2.4 * MB, p.mods - 1, "1 file isn't a Fabric mod and stays behind."));
    else parts.push(part("mods", "compatible", p.mods, p.mods * 2.1 * MB, p.mods));
    if (p.support === "full" && p.gameVersion !== "1.8.9") parts.push(part("config", "compatible", p.mods * 3, p.mods * 41_000, p.mods * 3));
  }
  parts.push(part("options", "compatible", 2, 9_400, 2), part("keybinds", "compatible", 1, 0, 1));
  if (seed % 3 !== 0) parts.push(part("resourcepacks", "compatible", (seed % 4) + 1, ((seed % 4) + 1) * 38 * MB, (seed % 4) + 1));
  if (seed % 4 === 1) parts.push(part("shaderpacks", "compatible", 2, 31 * MB, 2));
  parts.push(part("servers", "compatible", 1, 2_100, 1));
  if (p.mods > 20 && p.support === "full") parts.push(part("other", "compatible", 14, 3.2 * MB, 14));
  const worldBytes = p.worlds * (120 + seed * 9) * MB;
  if (worlds && p.worlds) parts.push(part("worlds", "compatible", p.worlds * 420, worldBytes, p.worlds));
  if (p.name === "Fabulously Optimized") warnings.push("Its options.txt was written by a newer Minecraft; Flint keeps it as it is.");
  let name = p.name;
  for (let n = 2; taken.has(name.toLowerCase()); n++) name = `${p.name} (${n})`;
  taken.add(name.toLowerCase());
  // One that looked fine in the list: its folder went away since (a drive that was unplugged).
  const blocker = p.support === "unsupported" ? (p.supportNote ?? "Flint can't import this profile.") : p.name === "Recording" ? "Its game folder on drive D isn't there any more. Connect the drive, then scan again." : null;
  return {
    key,
    name: p.name,
    target: blocker ? null : { name, gameVersion: p.gameVersion!, loader: p.support === "full" && p.loader === "fabric" ? "fabric" : "vanilla" },
    parts: blocker ? [] : parts,
    bytes: blocker ? 0 : parts.reduce((sum, x) => sum + x.bytes, 0),
    worldBytes: blocker ? 0 : worldBytes,
    warnings: blocker ? [] : warnings,
    blocker,
  };
}

function importPreview(request: { launcher: string; folder?: string | null; keys: string[]; worlds: boolean }) {
  const taken = new Set(db.profiles.map((p) => p.name.toLowerCase()));
  const profiles = request.keys.map((k) => importPlan(request.launcher, request.folder ?? null, k, request.worlds, taken)).filter((p) => !!p);
  const bytes = profiles.reduce((sum, p) => sum + p.bytes, 0);
  const freeBytes = importMode === "full" ? Math.round(bytes * 0.6) : 182 * 1024 ** 3;
  return { profiles, bytes, freeBytes, enoughSpace: bytes + 1024 ** 3 <= freeBytes };
}

/** The running import, if any: its progress rows, and whether the player cancelled it. */
const importRun: { job: string | null; profiles: any[]; cancel: boolean } = { job: null, profiles: [], cancel: false };

function importStart(request: { launcher: string; folder?: string | null; keys: string[]; worlds: boolean }) {
  const preview = importPreview(request);
  const plans = preview.profiles.filter((p) => !p.blocker);
  if (!plans.length) throw "None of these profiles can be imported.";
  if (!preview.enoughSpace) throw "There isn't enough free space on Flint's drive for this import.";
  const jobId = `job-import-${Date.now().toString(36)}`;
  const source = importListing(request.launcher, request.folder ?? null).launcher.name;
  const total = plans.reduce((sum, p) => sum + p.bytes, 0);
  const job: any = { id: jobId, kind: "import", profileId: null, title: `Importing from ${source}`, state: "running", stage: "Starting", bytesDone: 0, bytesTotal: total, itemsDone: 0, itemsTotal: plans.length, createdAt: Date.now() / 1000 };
  jobs.unshift(job);
  importRun.job = jobId;
  importRun.cancel = false;
  importRun.profiles = plans.map((p) => ({ key: p.key, name: p.name, state: "waiting", bytesDone: 0, bytesTotal: p.bytes, filesDone: 0, filesTotal: p.parts.reduce((s, x) => s + x.files, 0), current: null, profileId: null, error: null }));
  const results: any[] = [];
  const tick = () => {
    emit("import-progress", { job: jobId, profiles: importRun.profiles.map((r) => ({ ...r })) });
    Object.assign(job, { bytesDone: importRun.profiles.reduce((s, r) => s + r.bytesDone, 0) });
    emit("job", { ...job });
  };
  const finish = (state: "done" | "cancelled") => {
    job.state = state;
    job.stage = state === "done" ? "Imported" : "Cancelled";
    job.message = state === "cancelled" ? "Import cancelled." : null;
    job.itemsDone = results.filter((r) => r.state === "done").length;
    job.result = { profiles: results };
    importRun.job = null;
    save();
    tick();
  };
  void (async () => {
    for (const [i, plan] of plans.entries()) {
      const row = importRun.profiles[i];
      if (importRun.cancel) break;
      row.state = "copying";
      job.stage = `Copying ${plan.name}`;
      tick();
      const labels = plan.parts.map((x) => (x.id === "worlds" ? "World: Survival" : { mods: "Mods", config: "Mod settings", options: "Game settings", keybinds: "Keybinds", resourcepacks: "Resource packs", shaderpacks: "Shader packs", servers: "Servers", other: "Other mod data" }[x.id]!));
      const steps = Math.min(22, Math.max(8, Math.round(plan.bytes / (110 * MB))));
      let failed = false;
      for (let s = 1; s <= steps; s++) {
        await sleep(125);
        if (importRun.cancel) break;
        if (importMode === "fail" && i === 1 && s === Math.floor(steps / 2)) {
          failed = true;
          break;
        }
        row.bytesDone = Math.round((plan.bytes * s) / steps);
        row.filesDone = Math.round((row.filesTotal * s) / steps);
        row.current = labels[Math.min(labels.length - 1, Math.floor(((s - 1) / steps) * labels.length))];
        tick();
      }
      if (importRun.cancel) {
        // The profile being copied is removed; nothing of it stays.
        Object.assign(row, { state: "cancelled", current: null, bytesDone: 0, filesDone: 0 });
        results.push({ key: plan.key, name: plan.name, profileId: null, state: "cancelled", error: null, warnings: [], skipped: [] });
        break;
      }
      if (failed) {
        Object.assign(row, { state: "failed", current: null, error: "Couldn't copy config/journeymap/data: the file is in use by another program. Close that launcher and try again." });
        results.push({ key: plan.key, name: plan.name, profileId: null, state: "failed", error: row.error, warnings: [], skipped: [] });
        tick();
        continue;
      }
      row.state = "verifying";
      row.current = null;
      tick();
      await sleep(300);
      const id = `imp${Date.now().toString(36)}${i}`;
      db.profiles.push({ id, name: plan.target!.name, gameVersion: plan.target!.gameVersion, loader: plan.target!.loader as Profile["loader"], loaderVersion: plan.target!.loader === "fabric" ? "0.19.5" : null, createdAt: Date.now() / 1000, installed: false, origin: { launcher: request.launcher as any, key: plan.key, importedAt: Date.now() / 1000 } });
      Object.assign(row, { state: "done", profileId: id });
      const attention = plan.parts.find((x) => x.id === "mods" && x.status === "attention" && x.count > 0);
      results.push({
        key: plan.key,
        name: plan.name,
        profileId: id,
        state: "done",
        error: null,
        warnings: plan.warnings,
        skipped: attention ? [{ path: "mods/OptiFine_HD_U_I7.jar", reason: "Not a Fabric mod: it has no fabric.mod.json, so Flint can't load it." }] : [],
      });
      tick();
    }
    for (const row of importRun.profiles.filter((r) => r.state === "waiting")) {
      row.state = "cancelled";
      results.push({ key: row.key, name: row.name, profileId: null, state: "cancelled", error: null, warnings: [], skipped: [] });
    }
    finish(importRun.cancel ? "cancelled" : "done");
  })();
  return jobId;
}

/** Play: installs first when needed, then "starts" the game for a few seconds. */
async function launchMock(id: string) {
  const p = db.profiles.find((x) => x.id === id);
  if (!p?.installed) await install(id);
  setState({ profileId: id, state: "launching", label: "Starting Minecraft" });
  await sleep(900);
  setState({ profileId: id, state: "running" });
  setTimeout(() => setState({ profileId: id, state: "ready" }), 6000);
}

// Partners (devMockPartners.ts): `?partners=<set>` picks the listings.
const partnersMock = createPartnersMock(params, () => db.profiles, () => Object.values(states).filter((s) => s.state === "running").map((s) => s.profileId), launchMock);

export async function invoke(cmd: string, args: Record<string, any> = {}): Promise<unknown> {
  await sleep(40);
  if (cmd.startsWith("social_")) return socialMock(cmd, args);
  if (cmd.startsWith("partners_")) return partnersMock(cmd, args);
  switch (cmd) {
    case "app_info":
      return { version: "0.4.0", build: null, commit: null, platform: params.get("platform") ?? "macos", arch: "aarch64", dataDir: "~/Library/Application Support/Flint Client", qa: params.has("qa"), forceOnboarding: params.has("onboarding"), gatewayConfigured: fail !== "ai", discordUrl: "https://discord.gg/59rAHZMWb7", qaStartPage: params.get("page") ?? undefined };
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
    case "skin_texture": {
      if (demoSkin(args.uuid)) return { dataUrl: demoSkin(args.uuid), model: "classic", capeUrl: null };
      if (communitySkin(args.uuid)) return { dataUrl: communitySkin(args.uuid), model: "classic", capeUrl: null };
      // The Minecraft cape Mojang has active for the demo account (Skins → Capes), as Flint caches it with the skin.
      const active = CAPE_COLORS.find(([id]) => id === capeActive);
      return args.uuid === NOTCH.uuid ? { dataUrl: await skinData(), model: "classic", capeUrl: active ? capeArt(active[2]) : null } : { dataUrl: null, model: "classic" };
    }
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
    case "profiles_set_icon":
      db.profiles = db.profiles.map((p) => (p.id === args.id ? { ...p, icon: args.icon ?? null } : p));
      save();
      return db.profiles.find((p) => p.id === args.id);
    case "profiles_set_pinned":
      db.profiles = db.profiles.map((p) => (p.id === args.id ? { ...p, pinnedAt: args.pinned ? (p.pinnedAt ?? Math.floor(Date.now() / 1000)) : null } : p));
      save();
      return db.profiles.find((p) => p.id === args.id);
    case "profiles_choose_icon_picture":
    case "profiles_icon_picture":
      return null;
    case "profile_icon_textures":
      // The browser preview has no Minecraft to read pictures from.
      return { groups: [], icons: [], colors: ["ember", "amber", "crimson", "rose", "violet", "ocean", "teal", "emerald", "slate", "night"], textures: {} };
    case "profiles_select":
      db.selected = args.id;
      return save();
    case "profiles_inspect":
      return { profile: db.profiles.find((p) => p.id === args.id), path: `~/Library/Application Support/Flint Client/profiles/${args.id}`, mods: (db.mods[args.id] ?? []).length, worlds: 0, screenshots: 0, resourcePacks: 0 };
    case "version_catalog":
      await sleep(300);
      return catalog;
    case "news_feed": {
      // Flint's public release feed as news_feed passes it on: every release this build knows, plus (?news=next) one it
      // doesn't, as after a newer release is out; ?news=offline fails like a computer with no internet and no saved copy.
      await sleep(400);
      if (params.get("news") === "offline") throw "Flint news: could not connect (check your internet connection)";
      const known = RELEASE_NOTES.map(({ version, date, title, summary, page_url }) => ({ version, date, title, summary, page_url }));
      const next = { version: "9.9.9", date: "2026-12-01", title: "A sample newer release", summary: "Shown in the browser preview only, to check how news of a newer release looks.", page_url: "https://flintclient.com/releases/9.9.9/" };
      return { entries: params.get("news") === "next" ? [next, ...known] : known, offline: false };
    }
    case "profile_states":
      return Object.fromEntries(db.profiles.map((p) => [p.id, states[p.id] ?? { profileId: p.id, state: p.installed ? "ready" : "uninstalled" }]));
    case "profile_check":
      return { ready: db.profiles.find((p) => p.id === args.id)?.installed ?? false, missingBytes: 728_349_181, missingFiles: 5391 };
    case "profile_install":
      return install(args.id);
    case "profile_cancel":
      return;
    case "profile_launch":
      return launchMock(args.id);
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
      // Mods bring a library and an optional extra; packs bring nothing (a shader pack brings Iris with `?noiris`).
      if (requests.some((r) => (r.kind ?? "mod") === "mod")) {
        items.push({ projectId: "P7dR8mSH", slug: "fabric-api", title: "Fabric API", description: "Core library for Fabric mods.", kind: "mod", versionId: "vapi", versionNumber: "0.162.0", versionType: "release", fileName: "fabric-api.jar", size: 1_700_000, source: "dependency", requiredBy: items[0]?.title, required: true, status: "ok" });
        items.push({ projectId: "mOgUt4GM", slug: "modmenu", title: "Mod Menu", description: "Lists installed mods.", kind: "mod", versionId: "vmm", versionNumber: "15.0.0", versionType: "release", fileName: "modmenu.jar", size: 700_000, source: "optional", requiredBy: items[0]?.title, required: false, status: "ok" });
      } else if (params.has("noiris") && requests.some((r) => r.kind === "shader")) {
        items.push({ projectId: "YL57xq9U", slug: "iris", title: "Iris Shaders", description: "Shader pack support built for Sodium.", kind: "mod", versionId: "viris", versionNumber: "1.11.7", versionType: "release", fileName: "iris.jar", size: 2_900_000, source: "dependency", reason: "Shader packs need the Iris shader loader", requiredBy: items[0]?.title, required: true, status: "ok", role: "shaderLoader" });
      }
      // `?noruntime`: the running game has no Flint runtime (vanilla, or the in-game menu off): packs are turned on by hand.
      return { items, unmatched: [], blockers: [], gameVersion: "26.3", loader: "fabric", gameApplies: !params.has("noruntime") };
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
        // A moment apart, as the backend's events are, so the jobs tray sees the job run before it finishes.
        await sleep(150);
        save();
        job.state = "done";
        job.itemsDone = items.length;
        // Into a running game: the packs went in live (content.rs commit_staged).
        const live = states[args.profileId]?.state === "running";
        const titles = items.map((item) => SAMPLE_MODS.find(([id]) => `v${id}` === item.versionId)?.[2] ?? `Pack ${item.versionId}`);
        job.result = { installed: titles, failed: [], ...(live ? { live: { enable: (args.enableLive ?? true) && items.some((i) => i.kind !== "datapack"), datapack: items.some((i) => i.kind === "datapack"), applies: !params.has("noruntime") } } : {}) };
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
    case "content_rollback": {
      const m = (db.mods[args.profileId] ?? []).find((x: any) => x.projectId === args.projectId) as any;
      if (m?.previousVersion) Object.assign(m, { versionNumber: m.previousVersion, previousVersion: null, canRollback: false });
      return save();
    }
    case "content_pin":
      db.mods[args.profileId] = (db.mods[args.profileId] ?? []).map((m: any) => (args.items.some((i: any) => i.fileName === m.fileName) ? { ...m, pinned: args.pinned } : m));
      return save();
    case "content_updates": {
      await sleep(900);
      if (fail === "updates") throw "Couldn't check for updates: could not connect (check your internet connection)";
      const list = (db.mods[args.profileId] ?? []) as any[];
      const updates = DEMO_UPDATES.flatMap((u) => {
        const m = list.find((x) => x.projectId === u.projectId && x.versionNumber === u.currentVersion);
        return m ? [{ ...u, kind: m.kind, fileName: m.fileName, title: m.title, world: null, tracked: m.tracked, pinned: !!m.pinned }] : [];
      });
      const unmanaged = list.filter((m) => m.source === "manual" && !m.identified).map((m) => ({ kind: m.kind, fileName: m.fileName, title: m.title, world: null, folder: false }));
      return { updates, unmanaged, checkedAt: Date.now() / 1000, gameVersion: "26.2", loader: "fabric" };
    }
    case "content_update": {
      // `?fail=update`: Iris's download fails; the rest go in.
      const list = (db.mods[args.profileId] ?? []) as any[];
      const items = args.items as { kind: string; fileName: string; versionId: string; world?: string | null }[];
      const named = list.find((m) => m.fileName === items[0]?.fileName);
      const job: any = { id: `job-${Date.now().toString(36)}`, kind: "update", profileId: args.profileId, title: items.length === 1 ? `Updating ${named?.title ?? "1 project"}` : `Updating ${items.length} projects`, state: "running", stage: "Checking compatibility", bytesDone: 0, bytesTotal: null, itemsDone: 0, itemsTotal: items.length, createdAt: Date.now() / 1000 };
      jobs.unshift(job);
      emit("job", { ...job });
      void (async () => {
        await sleep(600);
        const result: any = { updated: [], added: [], failed: [], skipped: [], installed: [] };
        for (const [i, request] of items.entries()) {
          const m = list.find((x) => x.fileName === request.fileName);
          const u = DEMO_UPDATES.find((x) => x.projectId === m?.projectId);
          const version = u && [u, ...u.alternatives].find((v) => v.versionId === request.versionId);
          Object.assign(job, { stage: "Downloading", file: request.fileName, itemsDone: i, bytesDone: 0, bytesTotal: m?.size ?? 1_000_000 });
          emit("job", { ...job });
          for (let step = 1; step <= 4; step++) {
            await sleep(140);
            emit("job", { ...job, bytesDone: ((m?.size ?? 1_000_000) * step) / 4 });
          }
          const base = { kind: request.kind, fileName: request.fileName, world: request.world ?? null, title: m?.title ?? request.fileName };
          if (!m || !version) {
            result.skipped.push({ ...base, reason: "Already on this version" });
            continue;
          }
          if (fail === "update" && m.projectId === "YL57xq9U") {
            result.failed.push({ ...base, reason: "Download failed: could not connect (check your internet connection)" });
            continue;
          }
          result.updated.push({ ...base, newFileName: m.fileName, projectId: m.projectId, from: m.versionNumber, to: version.versionNumber });
          for (const dep of version.adds) {
            if (list.some((x) => x.projectId === dep.projectId)) continue;
            list.push({ kind: dep.kind, title: dep.title, projectId: dep.projectId, fileName: `${dep.title.toLowerCase().replace(/[^a-z0-9]+/g, "-")}-${dep.versionNumber}.jar`, versionNumber: dep.versionNumber, iconUrl: null, source: "dependency", size: dep.size ?? 900_000, enabled: true, missing: false, tracked: true, requires: [], installedAt: Date.now() / 1000 });
            result.added.push({ title: dep.title, versionNumber: dep.versionNumber, projectId: dep.projectId, kind: dep.kind });
          }
          Object.assign(m, { previousVersion: m.versionNumber, versionNumber: version.versionNumber, canRollback: true, tracked: true });
        }
        Object.assign(job, { stage: "Installing", file: null, itemsDone: items.length });
        emit("job", { ...job });
        await sleep(400);
        result.installed = [...result.updated, ...result.added].map((x: any) => x.title);
        save();
        Object.assign(job, { state: "done", stage: "Done", result });
        emit("job", { ...job });
      })();
      return job.id;
    }
    case "profile_worlds":
      return [{ id: "New World", name: "New World", lastPlayed: Date.now() / 1000 - 7200, icon: null, datapacks: 0 }];
    case "profile_files":
      return { path: `~/Library/Application Support/Flint Client/profiles/${args.profileId}`, total: 15_000_000, folders: ["mods", "resourcepacks", "shaderpacks", "saves", "screenshots", "config", "logs"].map((id, i) => ({ id, count: i, size: i * 1_000_000, exists: i < 5 })) };
    case "profile_logs":
      return [
        { name: "flint-launch.log", folder: "logs", kind: "log", size: 82_000, modified: Date.now() / 1000 - 600 },
        { name: "crash-2026-10-30_17.48.13-client.txt", folder: "crash-reports", kind: "crash", size: 29_000, modified: Date.now() / 1000 - (params.get("demo") === "log" ? 3000 : 300) },
        { name: "latest.log", folder: "logs", kind: "log", size: 29_000, modified: Date.now() / 1000 - 900 },
      ];
    case "profile_log_read":
      return String(args.name).startsWith("crash-") ? DEMO_CRASH : DEMO_LOG;
    case "jobs_list":
      // Copies: the store must never hold the objects this mock keeps changing.
      return jobs.map((j: any) => ({ ...j }));
    case "job_cancel":
      if (args.id === importRun.job) importRun.cancel = true;
      return;
    case "job_dismiss":
      return;
    case "import_launchers":
      // A real scan reads a few small files per launcher.
      await sleep(380);
      return importLaunchers();
    case "import_profiles":
      await sleep(180);
      return importListing(args.launcher, args.folder);
    case "import_pick_folder":
      await sleep(300);
      return importMode === "nofolder" ? null : "C:\\Users\\Steve\\Games\\PvP Pack";
    case "import_preview":
      await sleep(320);
      return importPreview(args.request);
    case "import_start":
      return importStart(args.request);
    case "import_progress":
      return args.job === importRun.job ? { job: args.job, profiles: importRun.profiles.map((r) => ({ ...r })) } : null;
    case "system_info":
      return { totalMemory: 17_179_869_184, memory: { totalMb: 16384, maxMb: 12288, recommendedMb: 4096, warnMb: 9728 }, cpus: 10, os: "macos", arch: "aarch64" };
    case "java_validate":
      if (!String(args.path).includes("java")) throw "That file isn't a Java executable.";
      return { version: "21.0.4", major: 21, summary: "Java 21.0.4 (Eclipse Temurin)" };
    case "java_args_check": {
      const list = String(args.text).split(/\s+/).filter(Boolean);
      const bad = list.find((a) => !a.startsWith("-") || /^-xm[xs]/i.test(a));
      if (bad) throw `“${bad}” can't be used.`;
      return list;
    }
    case "diagnostics_text":
      return "Flint Client 0.5.2\nSystem: macos aarch64, 10 CPU threads\nMemory: 16.0 GB installed, 16.0 GB usable, up to 12.0 GB for Minecraft";
    case "skin_profile":
      return { name: db.accounts.find((a) => a.uuid === args.uuid)?.username ?? "Notch", variant: "classic", capes: capes() };
    case "skin_upload":
    case "skin_reset":
    case "cape_set":
      await sleep(500);
      if (fail === "skin" && cmd === "skin_upload") throw "Minecraft didn't accept the skin: the image has transparent pixels on the base layer.";
      if (cmd === "cape_set") capeActive = (args.capeId as string | null) ?? null;
      return { name: db.accounts.find((a) => a.uuid === args.uuid)?.username ?? "Notch", variant: args.variant ?? "classic", capes: capes() };
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
      if (cmd === "auto_sync_set_enabled") db.settings = { ...db.settings, autoSync: enabled };
      return { enabled, waiting: enabled && mode === "waiting", notice };
    }
    case "auto_sync_overview":
      return syncOverview();
    case "auto_sync_set_switch":
      if (args.on) {
        syncMock.off.delete(args.id);
        syncMock.started[args.id] = args.source ?? syncOverview().defaultSource;
      } else syncMock.off.add(args.id);
      return syncOverview();
    case "auto_sync_set_excluded":
      if (args.excluded) syncMock.excluded.add(args.profileId);
      else syncMock.excluded.delete(args.profileId);
      return syncOverview();
    case "auto_sync_open_backups":
      return;
    case "java_overview": {
      const path = String(db.settings?.javaPath ?? "");
      return {
        versions: JAVA_PLAN,
        runtimes: [
          { component: "java-runtime-epsilon", version: "25.0.1", major: 25, size: 152_000_000 },
          { component: "java-runtime-delta", version: "21.0.7", major: 21, size: 131_000_000 },
        ],
        custom: path ? { path, version: "21.0.4", major: 21 } : undefined,
        inGameMenu: db.settings?.inGameMenu !== false,
        automatic: db.settings?.automaticOptimization !== false,
      };
    }
    case "java_args_review": {
      const list = String(args.text).split(/\s+/).filter(Boolean);
      const bad = list.find((a) => !a.startsWith("-") || /^-xm[xs]/i.test(a));
      if (bad) throw `${bad} sets Java's memory, which Flint takes from Memory in Settings → Java and memory. Change it there.`;
      if (list.includes("-XX:+UseG1GC") && list.includes("-XX:+UseZGC")) throw "-XX:+UseG1GC and -XX:+UseZGC each choose a garbage collector, and Java won't start with two. Keep one.";
      const notes = list.includes("-XX:+UseZGC")
        ? [
            "Minecraft runs with ZGC instead of the collector the official launcher uses (ZGC on 26.x, G1 on 1.21 and 1.8.9), which is left out while this is here.",
            "-XX:+UseZGC needs Java 15 or newer. Minecraft won't start with it on Java 8 (1.8.9 without Flint's in-game menu).",
          ]
        : [];
      return { args: list, notes };
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
    // .flintpack: `?pack=<name>` opens the import preview for a file at start-up, as a double-click would.
    case "flintpack_take_opened": {
      const name = params.get("pack");
      params.delete("pack");
      return name ? `C:\\Users\\Steve\\Downloads\\${name}.flintpack` : null;
    }
    case "flintpack_scan":
      return {
        sections: [
          { id: "mods", files: 14, size: 38_400_000 },
          { id: "resourcepacks", files: 2, size: 96_000_000 },
          { id: "config", files: 31, size: 412_000 },
          { id: "options", files: 2, size: 9_800 },
          { id: "flint", files: 4, size: 22_000 },
          { id: "worlds", files: 640, size: 312_000_000, worlds: 2 },
        ],
      };
    case "flintpack_pick_save":
      return `C:\\Users\\Steve\\Downloads\\${db.profiles.find((p) => p.id === args.profileId)?.name ?? "Profile"}.flintpack`;
    case "flintpack_pick_open":
      return "C:\\Users\\Steve\\Downloads\\PvP Practice.flintpack";
    case "flintpack_inspect": {
      const packName = String(args.path).split("\\").pop()!.replace(/\.flintpack$/i, "");
      const taken = new Set(db.profiles.map((p) => p.name.toLowerCase()));
      let name = packName;
      for (let n = 2; taken.has(name.toLowerCase()); n++) name = `${packName} (${n})`;
      return {
        path: args.path,
        fileName: `${packName}.flintpack`,
        packName,
        name,
        gameVersion: fail === "pack" ? "27.1" : "26.3",
        loader: "fabric",
        loaderVersion: "0.19.5",
        sections: [
          { id: "mods", files: 14, size: 38_400_000, downloads: 12 },
          { id: "config", files: 31, size: 412_000, downloads: 0 },
          { id: "options", files: 2, size: 9_800, downloads: 0 },
          { id: "flint", files: 4, size: 22_000, downloads: 0 },
        ],
        totalSize: 38_843_800,
        downloads: 12,
        createdAt: Date.now() / 1000,
        generator: "Flint Client 0.5.2",
        blocker: fail === "pack" ? "Minecraft 27.1 with Fabric isn't offered in this version of Flint." : null,
      };
    }
    case "flintpack_export":
    case "flintpack_import": {
      const exporting = cmd === "flintpack_export";
      const jobId = `job-${Date.now().toString(36)}`;
      const job: any = { id: jobId, kind: exporting ? "export" : "import", profileId: exporting ? args.profileId : null, title: exporting ? "Exporting profile" : `Importing ${args.name}`, state: "running", stage: exporting ? "Packing files" : "Unpacking files", bytesDone: 0, bytesTotal: 60_000_000, itemsDone: 0, itemsTotal: 0, createdAt: Date.now() / 1000 };
      jobs.unshift(job);
      void (async () => {
        for (let i = 1; i <= 12; i++) {
          await sleep(160);
          Object.assign(job, { bytesDone: (60_000_000 * i) / 12 });
          emit("job", { ...job });
        }
        if (!exporting) {
          const profile: Profile = { id: `p${Date.now().toString(36)}`, name: args.name, gameVersion: "26.3", loader: "fabric", loaderVersion: "0.19.5", createdAt: Date.now() / 1000, installed: false };
          db.profiles.push(profile);
          save();
          job.result = { profileId: profile.id, name: profile.name };
          job.profileId = profile.id;
        } else {
          job.result = { path: args.path, size: 41_000_000, files: 53, downloads: 12 };
        }
        job.state = "done";
        emit("job", { ...job });
      })();
      return jobId;
    }
    case "flintpack_reveal":
      return;
    // Settings → Storage and Troubleshooting. `?storage=busy`: a game is running; `?errors=none`: an empty history.
    case "storage_overview":
      await sleep(args.refresh ? 700 : 350);
      return storageMock.overview();
    case "storage_plan":
      await sleep(250);
      return storageMock.plan(args.kind);
    case "storage_clean": {
      await sleep(600);
      const plan = storageMock.plan(args.kind);
      if (plan.id !== args.planId) throw "Flint's files changed since you looked. Review the cleanup again.";
      storageMock.cleaned.add(args.kind);
      return { removedBytes: plan.bytes, removedFiles: plan.files, failed: [] };
    }
    case "error_history":
      return params.get("errors") === "none" || storageMock.errorsCleared ? [] : storageMock.errors();
    case "error_history_clear":
      storageMock.errorsCleared = true;
      return;
    case "environment_info":
      return { os: params.get("platform") === "windows" ? "Windows 10.0.26300" : "macOS 15.1", arch: "aarch64", cpu: "Apple M3 Pro", threads: 12, memoryMb: 16384 };
    case "diagnostics_export":
      await sleep(500);
      return { path: "C:\\Users\\Steve\\Downloads\\Flint diagnostics 2026-11-02-19-27.zip", size: 1_840_000, files: 14 };
    case "diagnostics_reveal":
      return;
    // The Flint Store: devMockStore.ts (`?store=…` flags pick its state).
    case "store_info":
    case "store_inventory":
    case "store_sign_in":
    case "store_sign_out":
    case "store_equip":
    case "store_changes":
    case "store_asset":
    case "store_cape_strip":
    case "store_wallet":
    case "store_orders":
    case "store_approve_link":
      return storeMock(cmd, args);
    // Discord connected accounts and community rewards: devMockCommunity.ts (`?demo&community=…`).
    case "store_connections":
    case "store_discord_link_start":
    case "store_discord_link_reopen":
    case "store_discord_link_attempt":
    case "store_discord_unlink":
    case "store_rewards":
    case "store_rewards_recheck":
    case "store_community_catalog":
    case "store_reward_asset":
    case "store_reward_strip":
    case "store_open_discord_invite":
      return communityMock(cmd, args);
    default:
      throw new Error(`devMock: unknown command ${cmd}`);
  }
}

/** Settings → Storage: a data folder shaped like a real one (two Fabric profiles, a version left from a removed profile). */
const storageMock = {
  cleaned: new Set<string>(),
  errorsCleared: false,
  groups(kind: string): { label: string; bytes: number; files: number }[] {
    if (storageMock.cleaned.has(kind)) return [];
    switch (kind) {
      case "cache":
        return [
          { label: "Java's start-up cache", bytes: 689 * MB, files: 11 },
          { label: "Skins and capes", bytes: 28_000, files: 6 },
          { label: "Minecraft version list", bytes: 1_300, files: 1 },
          { label: "Flint news", bytes: 2_100, files: 1 },
        ];
      case "temporary":
        return [{ label: "Unfinished downloads", bytes: 18.4 * MB, files: 2 }];
      case "unusedGame":
        return [
          { label: "Minecraft 1.21.1", bytes: 27.2 * MB, files: 41 },
          { label: "Fabric Loader 0.20.1 for Minecraft 1.21.1", bytes: 9_800, files: 1 },
          { label: "Libraries only those versions use", bytes: 3.1 * MB, files: 6 },
          { label: "Sounds and textures only those versions use", bytes: 38.6 * MB, files: 412 },
        ];
      case "rollback":
        return [
          { label: "Demon: 2 earlier versions", bytes: 3.4 * MB, files: 2 },
          { label: "PvP: 1 earlier version", bytes: 0.8 * MB, files: 1 },
        ];
    }
    return [];
  },
  plan(kind: string) {
    const groups = storageMock.groups(kind);
    return {
      kind,
      id: `${kind}-${groups.length}`,
      bytes: groups.reduce((s, g) => s + g.bytes, 0),
      files: groups.reduce((s, g) => s + g.files, 0),
      groups,
      note: kind === "temporary" && groups.length ? "1 file changed in the last few minutes, so it is kept in case it is still in use." : null,
      busy: params.get("storage") === "busy" ? "Minecraft is running. Close it first: Flint never removes files a running game may use." : null,
    };
  },
  overview() {
    const size = (kind: string) => storageMock.plan(kind).bytes;
    const categories = [
      { id: "game", bytes: 685 * MB - size("unusedGame") + 69 * MB, files: 5_240 },
      { id: "profiles", bytes: 342 * MB + 61 * MB, files: 1_310 },
      { id: "runtimes", bytes: 101 * MB, files: 412 },
      { id: "cache", bytes: size("cache") + 15_000, files: 19 },
      { id: "rollback", bytes: size("rollback"), files: 3 },
      { id: "sync", bytes: 5.7 * MB, files: 64 },
      { id: "temporary", bytes: size("temporary") + 2.1 * MB, files: 3 },
      { id: "other", bytes: 106 * MB, files: 380 },
    ].map((c) => ({ ...c, bytes: Math.round(c.bytes) }));
    const rollback = !storageMock.cleaned.has("rollback");
    return {
      root: "~/Library/Application Support/Flint Client",
      total: categories.reduce((s, c) => s + c.bytes, 0),
      categories,
      profiles: [
        { id: "demo263", name: "Demon", folder: "Demon-26.3", gameVersion: "26.3", loader: "fabric", bytes: 211 * MB, rollbackBytes: rollback ? 3.4 * MB : 0, rollbackFiles: rollback ? 2 : 0, syncBackupBytes: 120_000, syncBackups: 4 },
        { id: "demo262", name: "PvP", folder: "PvP-26.2", gameVersion: "26.2", loader: "fabric", bytes: 131 * MB, rollbackBytes: rollback ? 0.8 * MB : 0, rollbackFiles: rollback ? 1 : 0, syncBackupBytes: 0, syncBackups: 0 },
        { id: null, name: "Survival-1.21.1", folder: "Survival-1.21.1", gameVersion: null, loader: null, bytes: 61 * MB, rollbackBytes: 0, rollbackFiles: 0, syncBackupBytes: 0, syncBackups: 0 },
      ],
      linkedBytes: 0,
      reclaimable: { cache: size("cache"), temporary: size("temporary"), unusedGame: size("unusedGame"), rollback: size("rollback") },
      scannedAt: Date.now() / 1000,
      busy: params.get("storage") === "busy" ? "Minecraft is running. Close it first: Flint never removes files a running game may use." : null,
    };
  },
  errors() {
    const now = Date.now() / 1000;
    return [
      { at: now - 240, kind: "game", title: "PvP", message: "Minecraft crashed: Initializing game (exit code 1).", gameVersion: "26.2" },
      { at: now - 3 * 3600, kind: "job", title: "Installing Iris Shaders", message: "Couldn't reach Modrinth: the request timed out" },
      { at: now - 2 * 86400, kind: "profile", title: "Demon", message: "Couldn't download the game asset 3f/3f8b… from resources.download.minecraft.net: the file arrived incomplete. Files that finished downloading are kept, so Retry continues from here.", gameVersion: "26.3" },
    ];
  },
};
