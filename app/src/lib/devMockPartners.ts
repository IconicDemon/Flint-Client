// Browser-only development stand-in for the launcher's partner commands (src-tauri/src/partners), from the shared
// fixtures (partnerFixtures.json, the Partners API v1 contract). Never bundled into production builds (see devMock.ts).
//
// `?partners=<set>` picks the data: featured (the default), none, many, expired, invalid, example, offline (a server that
// is down) or nocounts (missing and stale numbers); `feedoffline` (the Store unreachable, a cached copy shown), `down`
// (unavailable, nothing cached) and `slow` (the featured set after 10 seconds). Fixture times are moved to now, so
// statistics are fresh and schedules run as they were written. Pictures come from scripts/dev-partner-media.mjs.
import type { PartnerListing, PartnerPlayPreview, PartnerServerCheck, PartnersAnswer, Profile } from "./api";
import fixtures from "./partnerFixtures.json";

type SetName = keyof typeof fixtures.sets;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/;

/** A fixture body with every time moved by the time since the fixtures were made. */
function rebased(set: SetName, age = 0): unknown {
  const shift = Date.now() - age - Date.parse(set === "example" ? fixtures.exampleNow : fixtures.now);
  const move = (v: unknown): unknown => {
    if (typeof v === "string" && ISO.test(v)) return new Date(Date.parse(v) + shift).toISOString();
    if (Array.isArray(v)) return v.map(move);
    if (v && typeof v === "object") return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, move(x)]));
    return v;
  };
  return move(fixtures.sets[set]);
}

export function createPartnersMock(params: URLSearchParams, profiles: () => Profile[], running: () => string[], launch: (id: string) => Promise<void>) {
  const choice = params.get("partners") ?? "featured";
  const set: SetName = choice in fixtures.sets ? (choice as SetName) : "featured";
  let favorites: string[] = [];
  let listed = new Set<string>();
  const base = { mediaBase: "/__dev-partner-media/", mediaProjects: fixtures.mediaProjects, allowLoopback: false };
  // `feedoffline` shows a copy cached 25 minutes ago: its numbers are that old too (so they are hidden).
  const body = () => rebased(set, choice === "feedoffline" ? 25 * 60_000 : 0);
  const partner = (id: string) => (fixtures.sets[set].partners as { id: string; name?: string; server?: { host: string; port?: number } }[]).find((p) => p.id === id);
  const address = (id: string) => {
    const s = partner(id)?.server;
    if (!s) throw new Error("This partner isn't a Minecraft server.");
    return s.port && s.port !== 25565 ? `${s.host}:${s.port}` : s.host;
  };

  return async function partners(cmd: string, args: Record<string, any>): Promise<unknown> {
    switch (cmd) {
      case "partners_feed": {
        const mode = args.mode as "cache" | "auto" | "force";
        if (choice === "down") return { ...base, source: "none", problem: "unavailable", body: null, fetchedAt: null } satisfies PartnersAnswer;
        if (choice === "feedoffline") return { ...base, source: "cache", problem: mode === "cache" ? null : "offline", body: body(), fetchedAt: Date.now() - 25 * 60_000 } satisfies PartnersAnswer;
        if (mode === "cache") return { ...base, source: "none", problem: null, body: null, fetchedAt: null } satisfies PartnersAnswer;
        await sleep(choice === "slow" ? 10_000 : 220);
        return { ...base, source: "network", problem: null, body: body(), fetchedAt: Date.now() } satisfies PartnersAnswer;
      }
      case "partners_favorites":
        return favorites;
      case "partners_set_favorite":
        favorites = args.on ? [args.id, ...favorites.filter((x) => x !== args.id)] : favorites.filter((x) => x !== args.id);
        return favorites;
      case "partners_check_server": {
        await sleep(700);
        // `?partners=offline`: Glasswing Games doesn't answer (as its fresh statistics say).
        const down = partner(args.partnerId)?.name === "Glasswing Games";
        return (down ? { reachable: false, pingMs: null, version: null } : { reachable: true, pingMs: 38, version: "Paper 26.3" }) satisfies PartnerServerCheck;
      }
      case "partners_play_preview": {
        await sleep(120);
        const others = running().filter((id) => id !== args.profileId);
        return {
          address: address(args.partnerId),
          listed: listed.has(`${args.profileId}|${address(args.partnerId)}`),
          listProblem: null,
          blocked: running().includes(args.profileId) ? "Minecraft is already running in this profile. Close it first, or join the server from the game's Multiplayer screen." : null,
          othersRunning: profiles().filter((p) => others.includes(p.id)).map((p) => p.name),
        } satisfies PartnerPlayPreview;
      }
      case "partners_play": {
        const key = `${args.profileId}|${address(args.partnerId)}`;
        const already = listed.has(key);
        listed = new Set([...listed, key]);
        await launch(args.profileId);
        return { address: address(args.partnerId), added: !already, alreadyListed: already, problem: null } satisfies PartnerListing;
      }
      default:
        throw new Error(`devMock: unknown command ${cmd}`);
    }
  };
}
