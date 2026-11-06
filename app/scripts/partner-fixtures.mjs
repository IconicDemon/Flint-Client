#!/usr/bin/env node
// Writes src/lib/partnerFixtures.json: Partners API v1 feed bodies (the Store's docs/partners-api-v1.md, schema 1) for the
// launcher's tests (src/lib/partners.test.ts, src-tauri/src/partners) and the development mock (src/lib/devMockPartners.ts).
// Every name, host and link is fictional (.example hosts, made-up Discord codes), except `example`, the Store's own
// example copied from its contract with the "…" placeholders written out.
//
//   node scripts/partner-fixtures.mjs [out]
import { createHash } from "node:crypto";
import { writeFileSync } from "node:fs";

const out = process.argv[2] ?? new URL("../src/lib/partnerFixtures.json", import.meta.url);
const NOW = Date.parse("2026-11-08T12:00:00.000Z");
const iso = (ms) => new Date(ms).toISOString();
const min = 60_000;
const PROJECT = "vkqzlqjbpvxrskwpvijt";
const media = {};
const sha = (text) => createHash("sha256").update(text).digest("hex");
const STAGING = "mxgeolovubvnjjhwihsi";
/** Partners API v1's image address: partner-media/partners/<listing id>/<sha256>.<png|jpg|webp>. */
const mediaUrl = (id, hash, ext = "webp", project = PROJECT) => `https://${project}.supabase.co/storage/v1/object/public/partner-media/partners/${id}/${hash}.${ext}`;
function img(id, slug, shape, hue, extra = {}) {
  const [w, h] = shape === "featured" ? [1600, 900] : shape === "library" ? [1600, 400] : [256, 256];
  const hash = sha(`${slug}:${shape}`);
  media[hash] = { slug, shape, hue, motif: extra.motif ?? "hills" };
  const out = { url: mediaUrl(id, hash, shape === "logo" ? "png" : "webp"), width: w, height: h, sha256: hash };
  if (extra.alt) out.alt = extra.alt;
  return out;
}
const uuid = (n) => `7a1e${String(n).padStart(4, "0")}-5c2d-4b8e-9f10-${String(n).padStart(12, "0")}`;

function partner(n, slug, fields) {
  const hue = fields.hue ?? (n * 47) % 360;
  const motif = fields.motif ?? "hills";
  const p = {
    id: uuid(n),
    slug,
    name: fields.name,
    kind: fields.kind,
    label: fields.label ?? "Partner",
    in_library: fields.in_library ?? true,
    summary: fields.summary,
    description: fields.description ?? fields.summary,
    primary: fields.primary,
    featured_image: img(uuid(n), slug, "featured", hue, { motif, alt: `${fields.name} artwork` }),
    library_image: img(uuid(n), slug, "library", hue, { motif }),
    logo: img(uuid(n), slug, "logo", hue, { motif }),
    updated_at: iso(NOW - 3 * 86_400_000),
    sort: fields.sort ?? n * 10,
  };
  for (const key of ["server", "stats", "discord_stats", "links", "starts_at", "ends_at"]) if (fields[key] !== undefined) p[key] = fields[key];
  for (const key of ["featured_image", "library_image", "logo"]) if (fields[key] !== undefined) p[key] = fields[key];
  if (fields.omit) for (const key of fields.omit) delete p[key];
  return p;
}

const join = { kind: "join_server", label: "Play" };
const P = {
  emberfall: partner(1, "emberfall", {
    name: "Emberfall Network",
    kind: "minecraft_server",
    motif: "volcano",
    hue: 18,
    summary: "Survival, Bedwars and seasonal events on one network, with a friendly community and weekly updates.",
    description:
      "Emberfall is a community-run network with three game modes: a long-term Survival world with land claims, fast Bedwars with ranked seasons, and a monthly build event.\n\nEveryone starts equal: ranks are cosmetic only, and the staff team is online around the clock.",
    primary: join,
    server: { host: "play.emberfall.example", port: 25565, edition: "java", versions: { min: "1.8.9", max: "26.3" }, recommended_version: "1.21.11" },
    stats: { online: true, players: 1284, max: 5000, checked_at: iso(NOW - 2 * min) },
    links: { website: "https://emberfall.example/", discord: "https://discord.gg/emberfall", store: "https://store.emberfall.example/", youtube: "https://www.youtube.com/@emberfall" },
  }),
  skyhollow: partner(2, "skyhollow", {
    name: "Skyhollow SMP",
    kind: "minecraft_server",
    label: "Sponsored",
    motif: "islands",
    hue: 196,
    summary: "A calm whitelist-free SMP on floating islands. Build a home in the clouds and trade at the sky market.",
    primary: join,
    server: { host: "mc.skyhollow.example", port: 25570, edition: "java", versions: { min: "1.21.10" }, recommended_version: "26.3" },
    stats: { online: true, players: 342, max: 500, checked_at: iso(NOW - 4 * min) },
    links: { website: "https://skyhollow.example/", discord: "https://discord.com/invite/skyhollow" },
  }),
  forgelight: partner(3, "forgelight", {
    name: "Forgelight Collective",
    kind: "discord",
    motif: "forge",
    hue: 262,
    summary: "Builders, redstoners and texture artists sharing their work. Weekly build prompts and feedback nights.",
    primary: { kind: "open_url", label: "Join Discord", url: "https://discord.gg/forgelight" },
    discord_stats: { members: 18240, online: 2210, checked_at: iso(NOW - 3 * min) },
  }),
  cinderbloc: partner(4, "cinderbloc", {
    name: "Cinderbloc Hosting",
    kind: "hosting",
    label: "Sponsored",
    motif: "grid",
    hue: 28,
    summary: "Minecraft server hosting with instant setup, daily backups and Fabric in one click. Plans from small to huge.",
    primary: { kind: "open_url", label: "Visit website", url: "https://cinderbloc.example/flint" },
    links: { website: "https://cinderbloc.example/" },
  }),
  northwind: partner(5, "northwind", {
    name: "Northwind Arena",
    kind: "minecraft_server",
    motif: "peaks",
    hue: 210,
    summary: "Classic 1.8.9 PvP: duels, practice and tournaments every weekend, tuned for the old combat.",
    primary: join,
    server: { host: "pvp.northwind.example", edition: "java", versions: { list: ["1.8.9"] }, recommended_version: "1.8.9" },
    stats: { online: true, players: 610, max: 2000, checked_at: iso(NOW - 25 * min) },
  }),
  lumenvale: partner(6, "lumenvale", {
    name: "Lumenvale Realms",
    kind: "minecraft_server",
    motif: "forest",
    hue: 142,
    summary: "A fantasy RPG server with custom quests, classes and dungeons built by hand over five years.",
    primary: join,
    server: { host: "play.lumenvale.example", port: 25565, edition: "java", versions: { min: "1.21.1", max: "26.3" }, recommended_version: "26.2" },
    stats: { online: true, players: 97, max: 250, checked_at: iso(NOW - 1 * min) },
    links: { website: "https://lumenvale.example/", x: "https://x.com/lumenvale" },
  }),
  moonmere: partner(7, "moonmere", {
    name: "Moonmere Survival",
    kind: "minecraft_server",
    label: "Sponsored",
    motif: "lake",
    hue: 232,
    summary: "Vanilla survival with a twist: the moon changes every night, and so do the mobs.",
    primary: join,
    server: { host: "moonmere.example", edition: "java", versions: { min: "1.21.1", max: "1.21.11" } },
  }),
  driftreef: partner(8, "driftreef", {
    name: "Driftreef Islands",
    kind: "minecraft_server",
    motif: "islands",
    hue: 176,
    summary: "Start on a raft, end with an archipelago. Co-op island survival for small groups of friends.",
    primary: join,
    server: { host: "play.driftreef.example", edition: "java", versions: { list: ["1.21.11", "26.1.2", "26.2", "26.3"] } },
    stats: { online: true, checked_at: iso(NOW - 3 * min) },
  }),
  quarrystone: partner(9, "quarrystone", {
    name: "Quarrystone Builders",
    kind: "discord",
    label: "Sponsored",
    motif: "grid",
    hue: 40,
    summary: "A server-agnostic community for megabuilds: schematics, tutorials and a monthly showcase.",
    primary: { kind: "open_url", label: "Join Discord", url: "https://discord.com/invite/quarrystone" },
    links: { discord: "https://discord.com/invite/quarrystone" },
  }),
  glasswing: partner(10, "glasswing", {
    name: "Glasswing Games",
    kind: "minecraft_server",
    motif: "peaks",
    hue: 300,
    summary: "Minigames that change every week: parkour, build battles and a hide-and-seek mode with disguises.",
    primary: join,
    server: { host: "play.glasswing.example", port: 25566, edition: "java", versions: { min: "1.21.10", max: "26.3" }, recommended_version: "26.3" },
    stats: { online: false, checked_at: iso(NOW - 1 * min) },
    links: { website: "https://glasswing.example/" },
  }),
  ironpine: partner(11, "ironpine", {
    name: "Ironpine Hosting",
    kind: "hosting",
    motif: "forest",
    hue: 160,
    summary: "Low-latency hosting in Europe and North America, with a control panel made for modded servers.",
    primary: { kind: "open_url", label: "See plans", url: "https://ironpine.example/minecraft" },
  }),
  basalt: partner(12, "basalt", {
    name: "Basalt Brawl",
    kind: "minecraft_server",
    label: "Sponsored",
    motif: "volcano",
    hue: 4,
    summary: "Team PvP on volcanic maps: capture the core, hold the bridge and survive the lava rising.",
    primary: join,
    server: { host: "basalt-brawl.example", edition: "java", versions: { list: ["1.8.9", "1.21.11"] }, recommended_version: "1.8.9" },
    stats: { online: true, players: 2140, max: 2500, checked_at: iso(NOW - 6 * min) },
  }),
  frostfire: partner(13, "frostfire", {
    name: "Frostfire Cup",
    kind: "minecraft_server",
    in_library: false,
    motif: "peaks",
    hue: 200,
    summary: "This weekend only: a 128-team Bedwars tournament with live commentary. Sign up on the server.",
    primary: { kind: "join_server", label: "Join the cup" },
    server: { host: "cup.frostfire.example", edition: "java", versions: { min: "1.8.9", max: "1.21.11" }, recommended_version: "1.8.9" },
    stats: { online: true, players: 3011, max: 6000, checked_at: iso(NOW - 2 * min) },
    starts_at: iso(NOW - 86_400_000),
    ends_at: iso(NOW + 2 * 86_400_000),
    sort: 0,
  }),
};

const body = (featured, partners) => ({ schema: 1, generated_at: iso(NOW - 30_000), featured_id: featured ? P[featured]?.id ?? featured : null, partners: partners.map((p) => (typeof p === "string" ? P[p] : p)) });

const bad = (n, slug, fields) => partner(n, slug, { name: "Broken listing", kind: "minecraft_server", summary: "Should never show.", primary: join, server: { host: "play.broken.example", edition: "java" }, ...fields });
const okServer = (n, slug, name, fields) => partner(n, slug, { name, kind: "minecraft_server", summary: `${name}: a listing whose pictures or links are wrong.`, primary: join, server: { host: `play.${slug}.example`, edition: "java" }, ...fields });

/** docs/partners-api-v1.md's example (the website's worktree, cb62497), with its "…" placeholders written out in full. */
function websiteExample() {
  const ember = "6f1c9a52-3e1b-4a7e-9d55-1c2b3a4d5e6f";
  const builders = "0b2d7c41-8e3a-4f1b-a2c9-5d6e7f809a1b";
  const blaze = "9e47b2c3-1d4e-4a5f-8b6c-7d8e9f0a1b2c";
  const [a, b, c, d] = ["a", "b", "c", "d"].map((x) => x.repeat(64));
  media[a] = { slug: "ember-smp", shape: "featured", hue: 14, motif: "volcano" };
  media[b] = { slug: "ember-smp", shape: "library", hue: 14, motif: "volcano" };
  media[c] = { slug: "flint-builders", shape: "featured", hue: 250, motif: "forge" };
  media[d] = { slug: "blaze-hosting", shape: "featured", hue: 30, motif: "grid" };
  return {
    schema: 1,
    generated_at: "2026-11-05T04:30:00.000Z",
    featured_id: ember,
    partners: [
      {
        id: ember, slug: "ember-smp", name: "Ember SMP", kind: "minecraft_server",
        label: "Sponsored", summary: "Survival with friends, on every version from 1.8.9 to 26.3.", in_library: true, sort: 10,
        primary: { kind: "join_server", label: "Join server" },
        server: { host: "play.embersmp.net", port: 25565, edition: "java", versions: { min: "1.8.9", max: "26.3" }, recommended_version: "1.21.11" },
        links: { website: "https://embersmp.net/", discord: "https://discord.gg/embersmp" },
        stats: { online: true, players: 128, max: 500, checked_at: "2026-11-05T04:29:12.000Z" },
        featured_image: { url: mediaUrl(ember, a, "png", STAGING), width: 1600, height: 900, sha256: a, alt: "Ember SMP" },
        library_image: { url: mediaUrl(ember, b, "png", STAGING), width: 1600, height: 400, sha256: b },
        updated_at: "2026-11-05T04:00:00.000Z",
      },
      {
        id: builders, slug: "flint-builders", name: "Flint Builders", kind: "discord", label: "Partner",
        summary: "A community of builders who play on Flint.", in_library: true, sort: 20,
        primary: { kind: "open_url", label: "Join Discord", url: "https://discord.gg/flintbuild" },
        links: { discord: "https://discord.gg/flintbuild" },
        discord_stats: { members: 1520, online: 300, checked_at: "2026-11-05T04:28:40.000Z" },
        featured_image: { url: mediaUrl(builders, c, "png", STAGING), width: 1600, height: 900, sha256: c },
        updated_at: "2026-11-05T03:10:00.000Z",
      },
      {
        id: blaze, slug: "blaze-hosting", name: "Blaze Hosting", kind: "hosting", label: "Sponsored",
        summary: "Minecraft server hosting with Flint-ready setups.", in_library: true, sort: 30,
        primary: { kind: "open_url", label: "Visit website", url: "https://blazehost.net/flint" },
        links: { website: "https://blazehost.net/" },
        featured_image: { url: mediaUrl(blaze, d, "png", STAGING), width: 1600, height: 900, sha256: d },
        updated_at: "2026-11-05T02:00:00.000Z",
      },
    ],
  };
}

const sets = {
  none: body(null, []),
  featured: body("emberfall", ["emberfall", "skyhollow", "forgelight", "cinderbloc"]),
  many: body("frostfire", ["frostfire", "emberfall", "skyhollow", "forgelight", "cinderbloc", "northwind", "lumenvale", "moonmere", "driftreef", "quarrystone", "glasswing", "ironpine", "basalt"]),
  expired: body(uuid(31), [
    partner(31, "lastseason", { name: "Last Season Cup", kind: "minecraft_server", summary: "A tournament that has ended.", primary: join, server: { host: "cup.lastseason.example", edition: "java" }, starts_at: iso(NOW - 10 * 86_400_000), ends_at: iso(NOW - 60_000) }),
    partner(32, "nextmonth", { name: "Next Month Launch", kind: "minecraft_server", label: "Sponsored", summary: "Scheduled, not started yet.", primary: join, server: { host: "play.nextmonth.example", edition: "java" }, starts_at: iso(NOW + 20 * 86_400_000) }),
    partner(33, "paused", { name: "Paused Promotion", kind: "hosting", label: "Sponsored", summary: "Paused by the team: its window is closed.", primary: { kind: "open_url", label: "Visit", url: "https://paused.example/" }, starts_at: iso(NOW - 86_400_000), ends_at: iso(NOW - 3_600_000) }),
    "lumenvale",
  ]),
  invalid: {
    ...body("not-a-uuid", [
      bad(41, "wronghost", { featured_image: { url: "https://i.imgur.example/banner.png", width: 1600, height: 900, sha256: sha("x") } }),
      bad(42, "querybanner", { featured_image: { url: `${mediaUrl(uuid(42), sha("q"))}?token=1`, width: 1600, height: 900, sha256: sha("q") } }),
      bad(44, "wrongratio", { featured_image: { url: mediaUrl(uuid(44), sha("r")), width: 900, height: 900, sha256: sha("r") } }),
      bad(56, "otherlisting", { featured_image: { url: mediaUrl(uuid(1), sha("o")), width: 1600, height: 900, sha256: sha("o") } }),
      bad(57, "wronghash", { featured_image: { url: mediaUrl(uuid(57), sha("w")), width: 1600, height: 900, sha256: sha("not the file name") } }),
      bad(58, "nofeatured", { omit: ["featured_image"] }),
      okServer(59, "badextras", "Bad Extra Pictures", {
        library_image: { url: mediaUrl(uuid(59), sha("l")).replace("/partner-media/", "/other-bucket/"), width: 1600, height: 400, sha256: sha("l") },
        logo: { url: mediaUrl(uuid(59), sha("g"), "gif"), width: 256, height: 256, sha256: sha("g") },
      }),
      okServer(43, "badlinks", "Bad Links Server", {
        links: { website: "http://badlinks.example/", discord: "https://discord.evil.example/invite/abc", store: "javascript:alert(1)", x: "https://facebook.com/badlinks", youtube: "https://user:pass@www.youtube.com/@bad" },
      }),
      bad(45, "privateip", { server: { host: "192.168.1.20", edition: "java" } }),
      bad(46, "loopback", { server: { host: "localhost", edition: "java" } }),
      bad(47, "badport", { server: { host: "play.badport.example", port: 70000, edition: "java" } }),
      bad(48, "toolong", { name: "A".repeat(61) }),
      bad(49, "unknownkind", { kind: "shop" }),
      bad(50, "badlabel", { label: "Ad" }),
      bad(51, "badprimary", { kind: "hosting", server: undefined, primary: { kind: "open_url", label: "Go", url: "http://insecure.example/" } }),
      bad(52, "linklocal", { server: { host: "169.254.10.1", edition: "java" } }),
      bad(53, "badschedule", { ends_at: "next tuesday" }),
      bad(54, "discordnoinvite", { kind: "discord", server: undefined, primary: { kind: "open_url", label: "Join", url: "https://forgelight.example/discord" } }),
      { id: "not-a-uuid", slug: "noid", name: "No Id" },
      okServer(55, "bedrockonly", "Bedrock Only Realm", { server: { host: "bedrock.example", port: 19132, edition: "bedrock" } }),
      "emberfall",
    ]),
  },
  example: websiteExample(),
  offline: body("glasswing", ["glasswing", "emberfall", "lumenvale", "forgelight"]),
  nocounts: body("moonmere", [
    "moonmere",
    "driftreef",
    "northwind",
    "quarrystone",
    partner(61, "stalediscord", { name: "Stale Count Discord", kind: "discord", summary: "Its member count is an hour old, so it isn't shown.", primary: { kind: "open_url", label: "Join", url: "https://discord.gg/stalecount" }, discord_stats: { members: 900, online: 40, checked_at: iso(NOW - 60 * min) } }),
  ]),
};

writeFileSync(out, JSON.stringify({ $comment: "Partner feed bodies (schema 1) for tests and the dev mock; generated, fictional. `now` is the time they were made for; `media` maps each image's sha256 to the mock's picture.", now: iso(NOW), exampleNow: "2026-11-05T04:30:00.000Z", mediaProjects: [PROJECT, STAGING], media, sets }, null, 1) + "\n");
console.log("wrote", String(out), Object.keys(sets).map((k) => `${k}:${sets[k].partners.length}`).join(" "));
