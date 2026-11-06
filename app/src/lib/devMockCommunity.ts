// Browser-only development stand-in for Discord connected accounts and community rewards (src-tauri/src/store/community.rs),
// used by devMock.ts and devMockStore.ts. Never bundled into production builds. Every name, id and cape here is made up;
// the reward capes are MOCK art drawn in the browser and never ship anywhere.
//
// `?demo&community=<who>` signs in five fictional players (the Store's local demo has the first three) and starts as one:
// - fan: FlintFan, a member of the Flint server (member cape unlocked; booster cape locked: Boost the server).
// - booster: BoostBuddy, a member who is boosting (both unlocked).
// - newface: NewFace, linked but not a member (Join the server; after joining, Check again unlocks it).
// - notlinked: Wanderer, no Discord linked (Connect Discord).
// - outage: StormyDay, linked, Discord unreachable past the grace period (Can't check Discord right now).
// - grace: StormyDay within the grace period (still usable). screening: NewFace still on the server's rules.
// - checking: FlintFan linked a moment ago, not checked yet. off: a Store without any of it (today's production Store).
// Extra flags: `&linkoff` (linking switched off in Admin), `&rewardsoff` (community rewards off), `&discordoff` (Discord
// rewards off), `&attempt=denied|elsewhere|expired|error|cancelled|slow` (how linking ends), `&recheck=limited`.
import type { Account } from "./api";
import type { AttemptState, ConnectionsView, GuildState, RewardStatusKind, Standing } from "./community";
import { animatedArt, manifest, strip, texture, type Item, type StoreExtension } from "./devMockStore";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

type Discord = { username: string; displayName: string; avatarUrl: string; member: boolean | null; booster: boolean | null; pending: boolean; state: GuildState };
type Person = { uuid: string; name: string; colors: [string, string, string]; discord: Discord | null; outage: "none" | "grace" | "unavailable"; joining: boolean; owns: string[] };

const uuid = (n: number) => `5a1c0f3e2b7d4e8f9a6b1c2d3e4f${n.toString(16).padStart(4, "0")}`;
/** Discord's own default avatars (its logo on a colour): no real person's picture. */
const avatar = (n: number) => `https://cdn.discordapp.com/embed/avatars/${n % 6}.png`;
const discord = (username: string, displayName: string, n: number, over: Partial<Discord> = {}): Discord => ({
  username,
  displayName,
  avatarUrl: avatar(n),
  member: true,
  booster: false,
  pending: false,
  state: "verified",
  ...over,
});

const id = (n: number) => `00000000-0000-4000-8000-${n.toString(16).padStart(12, "0")}`;
export const MOCK_REWARD_IDS = { member: id(0xc001), booster: id(0xc002), first: id(0xc003), event: id(0xc004) };

type Reward = { cosmeticId: string; type: string; label: string; name: string; hue: number; animated?: boolean };
/** MOCK reward capes: drawn here, clearly named, for the development preview only. */
const REWARDS: Reward[] = [
  { cosmeticId: MOCK_REWARD_IDS.member, type: "discord_member", label: "Member of the Flint Discord server", name: "MOCK · Discord Member Cape", hue: 234, animated: true },
  { cosmeticId: MOCK_REWARD_IDS.booster, type: "discord_booster", label: "Boosting the Flint Discord server", name: "MOCK · Discord Booster Cape", hue: 318 },
  { cosmeticId: MOCK_REWARD_IDS.first, type: "first_launch", label: "Sign in with the Flint launcher", name: "MOCK · First Launch Cape", hue: 28 },
  { cosmeticId: MOCK_REWARD_IDS.event, type: "event", label: "Winter community event, 2026", name: "MOCK · Winter Event Cape", hue: 196 },
];
const GIFTS: (Reward & { via: string })[] = [
  { cosmeticId: id(0xc101), type: "", label: "", name: "MOCK · Founders Gift Cape", hue: 48, via: "admin_gift" },
  { cosmeticId: id(0xc102), type: "", label: "", name: "MOCK · Giveaway Cape", hue: 140, via: "giveaway" },
];
const ART = new Map<string, Item["art"]>([...REWARDS, ...GIFTS].map((r) => [r.cosmeticId, r.animated ? { hue: r.hue, animated: animatedArt(12, 8) } : { hue: r.hue }]));
const INVITE = "https://discord.gg/flint";

function people(scenario: string): Person[] {
  const p = (n: number, name: string, colors: Person["colors"], link: Discord | null, owns: string[] = [MOCK_REWARD_IDS.first], outage: Person["outage"] = "none"): Person => ({
    uuid: uuid(n),
    name,
    colors,
    discord: link,
    outage,
    joining: false,
    owns,
  });
  return [
    p(0x101, "FlintFan", ["#3b2a1e", "#e0b48c", "#3d6fb5"], discord("flintfan", "FlintFan", 1, scenario === "checking" ? { state: "checking", member: null, booster: null } : {}), [
      MOCK_REWARD_IDS.first,
      MOCK_REWARD_IDS.event,
      ...GIFTS.map((g) => g.cosmeticId),
    ]),
    p(0x102, "BoostBuddy", ["#1d1d24", "#c98f6a", "#7a3fb8"], discord("boostbuddy", "BoostBuddy", 2, { booster: true })),
    p(0x103, "NewFace", ["#6b4a2a", "#f1c9a5", "#3a8f5c"], discord("newface", "NewFace", 3, scenario === "screening" ? { member: true, pending: true } : { member: false }), []),
    p(0x104, "Wanderer", ["#2a2a2a", "#d8a983", "#b5483d"], null),
    p(0x105, "StormyDay", ["#4a3626", "#e7bf98", "#4c7fa3"], discord("stormyday", "StormyDay", 4), [MOCK_REWARD_IDS.first], scenario === "grace" ? "grace" : "unavailable"),
  ];
}

const ACTIVE: Record<string, number> = { fan: 0, booster: 1, newface: 2, screening: 2, notlinked: 3, outage: 4, grace: 4, checking: 0, off: 0 };

/** The fictional players for `?demo&community=…`, and which one is active. */
export function communityAccounts(params: URLSearchParams): { accounts: Account[]; active: string } | null {
  const scenario = params.get("community");
  if (scenario === null) return null;
  const list = people(scenario);
  const accounts = list.map((p, i) => ({ uuid: p.uuid, username: p.name, addedAt: Date.now() / 1000 - i * 3600 }) as Account);
  return { accounts, active: list[ACTIVE[scenario] ?? 0].uuid };
}

const skins = new Map<string, string>();
/** A simple skin for each fictional player (hair, skin, shirt). */
export function communitySkin(uuid: string): string | null {
  const person = people("").find((p) => p.uuid === uuid);
  if (!person) return null;
  if (!skins.has(uuid)) {
    const [hair, skin, shirt] = person.colors;
    const c = document.createElement("canvas");
    c.width = c.height = 64;
    const g = c.getContext("2d")!;
    const px = (x: number, y: number, w: number, h: number, color: string) => {
      g.fillStyle = color;
      g.fillRect(x, y, w, h);
    };
    px(0, 8, 32, 8, skin);
    px(8, 0, 16, 8, hair);
    px(0, 8, 32, 2, hair);
    px(9, 12, 2, 1, "#ffffff");
    px(13, 12, 2, 1, "#ffffff");
    px(10, 12, 1, 1, "#2b2b33");
    px(13, 12, 1, 1, "#2b2b33");
    px(11, 14, 2, 1, "#9a6a50");
    px(16, 16, 24, 16, shirt);
    px(40, 16, 16, 16, shirt);
    px(32, 48, 16, 16, shirt);
    px(44, 20, 4, 12, skin);
    px(36, 52, 4, 12, skin);
    px(0, 16, 16, 16, "#2f3b55");
    px(16, 48, 16, 16, "#2f3b55");
    px(0, 28, 16, 4, "#3a3a3a");
    px(16, 60, 16, 4, "#3a3a3a");
    skins.set(uuid, c.toDataURL("image/png"));
  }
  return skins.get(uuid)!;
}

type StoreHooks = { bump(uuid: string): void; takeOff(uuid: string, cosmeticId: string): void; signedIn(uuid: string): boolean };

export function createCommunityMock(params: URLSearchParams, active: () => string | null) {
  const scenario = params.get("community") ?? "";
  const on = params.has("community") && scenario !== "off";
  const linkOn = !params.has("linkoff");
  const rewardsOn = !params.has("rewardsoff");
  const discordOn = rewardsOn && linkOn && !params.has("discordoff");
  const crowd = new Map(people(scenario).map((p) => [p.uuid, p]));
  const attempts = new Map<string, { uuid: string; polls: number }>();
  const rechecked = new Map<string, number>();
  let store: StoreHooks | null = null;

  const listed = () => (rewardsOn ? REWARDS.filter((r) => discordOn || !r.type.startsWith("discord_")) : []);
  const person = (uuid: string) => crowd.get(uuid) ?? null;

  function standing(p: Person | null): Standing {
    const d = p?.discord;
    if (!p || !d) return { linked: false, member: false, booster: false, pending: false, state: "disconnected" };
    if (d.state === "checking") return { linked: true, member: false, booster: false, pending: false, state: "checking" };
    const counts = p.outage !== "unavailable";
    return {
      linked: true,
      member: counts && !!d.member && !d.pending,
      booster: counts && !!d.member && !!d.booster,
      pending: !!d.member && d.pending,
      state: p.outage === "grace" ? "grace" : p.outage === "unavailable" ? "unavailable" : "verified",
    };
  }

  /** Discord rewards counting for the player right now. */
  function earned(p: Person | null): Reward[] {
    if (!on || !discordOn || !p) return [];
    const s = standing(p);
    return listed().filter((r) => (r.type === "discord_member" && s.member) || (r.type === "discord_booster" && s.booster));
  }

  const extension: StoreExtension = {
    extra(uuid) {
      const p = person(uuid);
      if (!on || !p) return [];
      const kept = [...REWARDS, ...GIFTS].filter((r) => p.owns.includes(r.cosmeticId));
      const now = earned(p).filter((r) => !p.owns.includes(r.cosmeticId));
      const item = (r: Reward, over: Partial<Item>): Item => ({
        cosmeticId: r.cosmeticId,
        slug: r.name.toLowerCase().replace(/[^a-z0-9]+/g, "-"),
        kind: "cape",
        name: r.name,
        test: false,
        gift: false,
        grantedAt: new Date(Date.now() - 86_400_000).toISOString(),
        asset: { id: `asset-${r.cosmeticId}`, version: 1, width: 64, height: 32, animation: r.animated ? manifest(animatedArt(12, 8)) : null },
        art: ART.get(r.cosmeticId)!,
        ...over,
      });
      return [
        ...kept.map((r) => item(r, { obtainedVia: "via" in r ? (r as { via: string }).via : r.type, permanence: "permanent", state: "owned" })),
        ...now.map((r) =>
          item(r, { source: "reward", grantedAt: null, obtainedVia: r.type, permanence: "conditional", state: p.outage === "grace" ? "unavailable" : "eligible" }),
        ),
      ];
    },
    decorate: () => (on ? { obtainedVia: "purchase", permanence: "permanent", state: "owned" } : {}),
  };

  function connections(uuid: string): ConnectionsView {
    if (!on) return { uuid, state: "off", canLink: false, discord: null };
    if (store && !store.signedIn(uuid)) return { uuid, state: "signedOut", canLink: false, discord: null };
    const p = person(uuid);
    const d = p?.discord;
    const guildState: GuildState = !d ? "verified" : d.state === "checking" ? "checking" : p!.outage !== "none" ? "unavailable" : d.member && d.pending ? "pending" : "verified";
    return {
      uuid,
      state: "ok",
      canLink: linkOn,
      discord: d
        ? {
            username: d.username,
            displayName: d.displayName,
            avatarUrl: d.avatarUrl,
            linkedAt: new Date(Date.now() - 3 * 86_400_000).toISOString(),
            guild: {
              state: guildState,
              member: d.state === "checking" ? null : !!d.member && !d.pending,
              booster: d.state === "checking" ? null : !!d.booster,
              checkedAt: new Date().toISOString(),
            },
          }
        : null,
    };
  }

  function rewards(uuid: string) {
    if (!on) return { uuid, state: "off", discord: null, rewards: [] };
    const p = person(uuid);
    const s = standing(p);
    const now = new Set(earned(p).map((r) => r.cosmeticId));
    return {
      uuid,
      state: "ok",
      discord: s,
      rewards: listed().map((r) => {
        const isDiscord = r.type.startsWith("discord_");
        const status: RewardStatusKind = p?.owns.includes(r.cosmeticId)
          ? "owned"
          : now.has(r.cosmeticId)
            ? "eligible"
            : isDiscord && s.state === "checking"
              ? "checking"
              : isDiscord && s.state === "unavailable"
                ? "unavailable"
                : "locked";
        const action =
          status !== "locked"
            ? { kind: "none" }
            : isDiscord && !s.linked
              ? { kind: "connect_discord" }
              : isDiscord && s.pending
                ? { kind: "complete_screening", url: INVITE }
                : r.type === "discord_member" || (r.type === "discord_booster" && !s.member)
                  ? { kind: "join_discord", url: INVITE }
                  : r.type === "discord_booster"
                    ? { kind: "boost_discord", url: INVITE }
                    : r.type === "first_launch"
                      ? { kind: "launch" }
                      : { kind: "none" };
        return { cosmeticId: r.cosmeticId, requirement: r.type, permanence: isDiscord ? "conditional" : "permanent", status, action };
      }),
    };
  }

  function changed(uuid: string, before: Set<string>) {
    const after = new Set(earned(person(uuid)).map((r) => r.cosmeticId));
    for (const gone of before) if (!after.has(gone)) store?.takeOff(uuid, gone);
    store?.bump(uuid);
  }

  async function call(cmd: string, args: Record<string, any>): Promise<unknown> {
    switch (cmd) {
      case "store_connections":
        await sleep(250);
        return connections(args.uuid);
      case "store_discord_link_start": {
        await sleep(500);
        if (!on || !linkOn) return { state: "off" };
        const attemptId = crypto.randomUUID();
        attempts.set(attemptId, { uuid: args.uuid, polls: 0 });
        return { state: "started", attemptId, expiresAt: new Date(Date.now() + 600_000).toISOString(), opened: true };
      }
      case "store_discord_link_reopen":
        return;
      case "store_discord_link_attempt": {
        const a = attempts.get(args.attemptId);
        if (!a || a.uuid !== args.uuid) throw "That Discord connection isn't open for this account any more. Start again.";
        a.polls += 1;
        const ending = params.get("attempt");
        if (a.polls < 3 || ending === "slow") return { state: "waiting" };
        attempts.delete(args.attemptId);
        if (ending === "elsewhere") return { state: "already_linked_elsewhere" };
        if (ending && ending !== "linked") return { state: ending as AttemptState };
        const p = person(args.uuid);
        if (p) {
          const before = new Set(earned(p).map((r) => r.cosmeticId));
          p.discord = discord(p.name.toLowerCase(), p.name, 5, { member: true });
          p.outage = "none";
          changed(p.uuid, before);
        }
        return { state: "linked" };
      }
      case "store_discord_unlink": {
        await sleep(400);
        const p = person(args.uuid);
        if (p) {
          const before = new Set(earned(p).map((r) => r.cosmeticId));
          p.discord = null;
          changed(p.uuid, before);
        }
        return connections(args.uuid);
      }
      case "store_rewards":
        await sleep(200);
        return rewards(args.uuid);
      case "store_rewards_recheck": {
        await sleep(700);
        if (!on) return { state: "off" };
        const last = rechecked.get(args.uuid) ?? 0;
        if (params.get("recheck") === "limited" || Date.now() - last < 60_000) return { state: "limited", nextAllowedAt: (params.get("recheck") === "limited" ? Date.now() : last) + 42_000 };
        rechecked.set(args.uuid, Date.now());
        const p = person(args.uuid);
        if (p?.discord) {
          const before = new Set(earned(p).map((r) => r.cosmeticId));
          // Joined the server (Join the server, then Check again): a member now.
          if (p.joining) p.discord = { ...p.discord, member: true, pending: false };
          if (p.discord.state === "checking") p.discord = { ...p.discord, state: "verified", member: true, booster: false };
          changed(p.uuid, before);
        }
        return { state: "ok", connections: connections(args.uuid), rewards: rewards(args.uuid) };
      }
      case "store_community_catalog":
        await sleep(150);
        if (!on) return { state: "off", enabled: false, discordEnabled: false, rewards: [] };
        return {
          state: "ok",
          enabled: rewardsOn,
          discordEnabled: discordOn,
          fetchedAt: Date.now(),
          rewards: listed().map((r, i) => ({
            cosmeticId: r.cosmeticId,
            slug: r.name.toLowerCase().replace(/[^a-z0-9]+/g, "-"),
            kind: "cape",
            name: r.name,
            requirement: { type: r.type, label: r.label },
            permanence: r.type.startsWith("discord_") ? "conditional" : "permanent",
            sort: 100 + i,
            asset: { id: `asset-${r.cosmeticId}`, version: 1, width: 64, height: 32, animation: r.animated ? manifest(animatedArt(12, 8)) : null },
            animated: !!r.animated,
            hd: false,
          })),
        };
      case "store_reward_asset": {
        const art = ART.get(args.cosmeticId);
        if (!art) throw "This reward isn't in the Flint Store's community rewards.";
        return texture(art, "cape");
      }
      case "store_reward_strip": {
        const art = ART.get(args.cosmeticId);
        if (!art?.animated) throw "This item isn't animated.";
        await sleep(80);
        return strip(art, args.index);
      }
      case "store_open_discord_invite": {
        // Mock: nothing opens. The player "joins" (Check again then finds them a member).
        const p = person(active() ?? "");
        if (p) p.joining = true;
        console.info("[devMock] would open in the system browser:", args.url);
        return;
      }
    }
    throw new Error(`devMock: unknown command ${cmd}`);
  }

  return Object.assign(call, {
    extension,
    attach(hooks: StoreHooks) {
      store = hooks;
    },
  });
}
