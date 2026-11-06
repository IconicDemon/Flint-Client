// Run with `npm test`. The partner feed parser and the rules the interface applies (src/lib/partners.ts), against the
// fixtures the Rust tests read too (partnerFixtures.json).
import assert from "node:assert/strict";
import { test } from "node:test";
import fixtures from "./partnerFixtures.json" with { type: "json" };
import {
  canJoin,
  checkHost,
  checkImage,
  compareVersions,
  directoryPartners,
  featuredPartner,
  freshDiscordStats,
  freshStats,
  inviteOf,
  isActive,
  menuEntries,
  parsePartnerFeed,
  parseTime,
  plainText,
  playChoice,
  safeLink,
  serverAddress,
  versionFits,
  versionsText,
  websiteOf,
  type PartnerFeed,
} from "./partners.ts";

const NOW = Date.parse(fixtures.now);
const MIN = 60_000;
const OPTIONS = { mediaProjects: fixtures.mediaProjects };
type SetName = keyof typeof fixtures.sets;
const parse = (set: SetName) => parsePartnerFeed(fixtures.sets[set], OPTIONS);
const feed = (set: SetName): PartnerFeed => {
  const { feed } = parse(set);
  assert.ok(feed, set);
  return feed;
};
const byName = (f: PartnerFeed, name: string) => {
  const p = f.partners.find((x) => x.name === name);
  assert.ok(p, name);
  return p;
};

test("a feed with one featured partner reads whole, in the backend's order", () => {
  const { feed: f, dropped } = parse("featured");
  assert.ok(f);
  assert.deepEqual(dropped, []);
  assert.deepEqual(
    f.partners.map((p) => p.name),
    ["Emberfall Network", "Skyhollow SMP", "Forgelight Collective", "Cinderbloc Hosting"],
  );
  const ember = f.partners[0];
  assert.equal(featuredPartner(f, NOW)?.id, ember.id);
  assert.equal(ember.kind, "minecraft_server");
  assert.equal(ember.label, "Partner");
  assert.deepEqual(ember.server, { host: "play.emberfall.example", port: 25565, edition: "java", versions: { min: "1.8.9", max: "26.3", list: null }, recommendedVersion: "1.21.11" });
  assert.equal(serverAddress(ember.server!), "play.emberfall.example");
  assert.equal(serverAddress(byName(f, "Skyhollow SMP").server!), "mc.skyhollow.example:25570");
  assert.deepEqual(Object.keys(ember.links), ["website", "discord", "store", "youtube"]);
  assert.equal(ember.featuredImage?.width, 1600);
  assert.match(ember.featuredImage!.sha256, /^[0-9a-f]{64}$/);
  assert.ok(!("url" in ember.featuredImage!), "the remote address stays with the launcher");
  assert.ok(ember.description.includes("\n\n"), "paragraphs are kept");
  assert.equal(byName(f, "Skyhollow SMP").label, "Sponsored");
  assert.equal(inviteOf(byName(f, "Forgelight Collective")), "https://discord.gg/forgelight");
  assert.equal(websiteOf(byName(f, "Cinderbloc Hosting")), "https://cinderbloc.example/flint");
});

test("no partners: an empty directory and nothing featured", () => {
  const f = feed("none");
  assert.deepEqual(f.partners, []);
  assert.equal(f.featuredId, null);
  assert.equal(featuredPartner(f, NOW), null);
  assert.deepEqual(directoryPartners(f, NOW), []);
  assert.equal(featuredPartner(null, NOW), null, "no feed yet: Home shows nothing in its place");
});

test("a featured-only listing is on Home but not in the directory", () => {
  const f = feed("many");
  const featured = featuredPartner(f, NOW);
  assert.equal(featured?.name, "Frostfire Cup");
  assert.equal(featured?.inLibrary, false);
  const directory = directoryPartners(f, NOW);
  assert.equal(directory.length, 12);
  assert.ok(!directory.some((p) => p.id === featured?.id));
  assert.equal(featuredPartner(f, Date.parse(fixtures.sets.many.partners[0].ends_at!)), null, "it collapses the moment its window closes");
});

test("ended, scheduled and paused listings never show, even from a cached copy", () => {
  const f = feed("expired");
  assert.equal(f.partners.length, 4, "all four read");
  assert.deepEqual(directoryPartners(f, NOW).map((p) => p.name), ["Lumenvale Realms"]);
  assert.equal(featuredPartner(f, NOW), null, "the featured one ended a minute ago");
  const ended = byName(f, "Last Season Cup");
  assert.ok(isActive(ended, NOW - 2 * MIN), "it showed while it ran");
  assert.equal(featuredPartner(f, NOW - 2 * MIN)?.name, "Last Season Cup");
  const later = byName(f, "Next Month Launch");
  assert.ok(!isActive(later, NOW) && isActive(later, later.startsAt!));
});

test("invalid listings are left out and invalid parts dropped, each on its own", () => {
  const { feed: f, dropped } = parse("invalid");
  assert.ok(f);
  assert.equal(f.featuredId, null, "a featured id that isn't a uuid");
  assert.deepEqual(f.partners.map((p) => p.name).sort(), ["Bad Extra Pictures", "Bad Links Server", "Bedrock Only Realm", "Emberfall Network"]);
  const leftOut = dropped.filter((d) => d.reason === "listing left out").map((d) => d.field).sort();
  const expected = [
    ...Array(6).fill("featured_image"), // another host, a query string, square, another listing's folder, a name that isn't its checksum, none
    ...Array(4).fill("server"), // 192.168.x, localhost, port 70000, 169.254.x
    "name",
    "kind",
    "label",
    "primary.url", // an http link
    "primary.url", // a Discord listing whose link isn't an invite
    "schedule",
    "id",
  ];
  assert.deepEqual(leftOut, expected.sort());
  const extras = byName(f, "Bad Extra Pictures");
  assert.ok(extras.featuredImage);
  assert.deepEqual([extras.libraryImage, extras.logo], [null, null], "another bucket and a .gif: dropped, the card uses the featured image");
  assert.deepEqual(byName(f, "Bad Links Server").links, {}, "http, a fake Discord, javascript:, the wrong network and a user name are all dropped");
  const bedrock = byName(f, "Bedrock Only Realm");
  assert.equal(bedrock.server?.edition, "bedrock");
  assert.ok(!canJoin(bedrock), "shown, never joined");
  assert.ok(!menuEntries(bedrock, NOW, 3).includes("play"));
});

test("the website's own example (docs/partners-api-v1.md) reads whole", () => {
  const { feed: f, dropped } = parse("example");
  const at = Date.parse(fixtures.exampleNow);
  assert.ok(f);
  assert.deepEqual(dropped, []);
  assert.deepEqual(f.partners.map((p) => [p.name, p.kind, p.label]), [
    ["Ember SMP", "minecraft_server", "Sponsored"],
    ["Flint Builders", "discord", "Partner"],
    ["Blaze Hosting", "hosting", "Sponsored"],
  ]);
  const ember = featuredPartner(f, at)!;
  assert.equal(ember.name, "Ember SMP");
  assert.equal(ember.primary.label, "Join server");
  assert.equal(ember.description, ember.summary, "no description: the summary stands in");
  assert.equal(ember.libraryImage?.height, 400);
  assert.equal(ember.logo, null);
  assert.equal(ember.featuredImage.alt, "Ember SMP");
  assert.equal(f.partners[1].featuredImage.alt, "", "alt is optional");
  assert.deepEqual(freshStats(ember, at), { online: true, players: 128, max: 500, checkedAt: Date.parse("2026-11-05T04:29:12.000Z") });
  assert.equal(freshDiscordStats(f.partners[1], at)?.members, 1520);
  assert.deepEqual(menuEntries(f.partners[2], at, 1), ["visitWebsite"]);
});

test("a body that isn't a schema 1 feed is refused whole", () => {
  for (const body of [null, [], "text", { schema: 2, generated_at: fixtures.now, partners: [] }, { schema: 1, generated_at: fixtures.now }, { schema: 1, generated_at: "yesterday", partners: [] }]) {
    assert.equal(parsePartnerFeed(body, OPTIONS).feed, null, JSON.stringify(body));
  }
});

test("a listing sent twice shows once", () => {
  const body = { ...fixtures.sets.featured, partners: [fixtures.sets.featured.partners[0], fixtures.sets.featured.partners[0]] };
  const { feed: f, dropped } = parsePartnerFeed(body, OPTIONS);
  assert.equal(f?.partners.length, 1);
  assert.equal(dropped[0].reason, "listed twice");
});

test("images come only from partner-media/partners/<listing>/<sha256> in an allowed project", () => {
  const item = fixtures.sets.featured.partners[0];
  const good = item.featured_image;
  const check = (raw: unknown, shape: "featured" | "library" | "logo" = "featured", options = OPTIONS, id = item.id) => checkImage(raw, shape, options, id);
  assert.ok(check(good));
  assert.ok(check(good, "featured", { mediaProjects: [] }), "no project list: any project's partner-media");
  assert.equal(check(good, "featured", { mediaProjects: ["abcdefghijklmnopqrst"] }), null, "another project");
  assert.equal(check(good, "featured", OPTIONS, fixtures.sets.featured.partners[1].id), null, "another listing's folder");
  for (const url of [
    good.url.replace("https:", "http:"),
    good.url.replace(".supabase.co", ".supabase.co.evil.example"),
    good.url.replace("/partners/", "/partners/../secrets/"),
    good.url.replace("/partners/", "/other/"),
    good.url.replace("/public/", "/sign/"),
    good.url.replace(".webp", ".svg"),
    good.url.replace(".webp", ".WEBP"),
    good.url.replace(/[0-9a-f]{64}/, (h) => h.replace(/^./, "%61")),
    `${good.url}#x`,
    `${good.url}?download`,
    good.url.replace("https://", "https://user@"),
    good.url.replace(".co/", ".co:8443/"),
  ]) {
    assert.equal(check({ ...good, url }), null, url);
  }
  assert.equal(check({ ...good, width: 0 }), null);
  assert.equal(check({ ...good, sha256: good.sha256.toUpperCase() }), null);
  assert.equal(check({ ...good, sha256: "f".repeat(64) }), null, "the checksum must be the file's name");
  assert.equal(check(good, "logo"), null, "a 16:9 picture isn't a logo");
  assert.ok(check({ ...good, width: 1600, height: 800 }, "library"), "2:1 is a wide crop");
  assert.equal(check({ ...good, width: 1600, height: 1000 }, "library"), null);
  const { alt: _alt, ...noAlt } = good as typeof good & { alt?: string };
  assert.equal(check(noAlt)?.alt, "", "alt is optional");
});

test("server hosts: public names and addresses only", () => {
  const ok = ["play.example.com", "Play.Example.COM.", "mc.xn--80ak6aa92e.xn--p1ai", "8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "[2606:4700:4700::1111]", "100.63.255.255", "172.32.0.1"];
  for (const host of ok) assert.ok(checkHost(host).ok, host);
  const refused: [string, "invalid" | "private"][] = [
    ["localhost", "invalid"],
    ["minecraft.localhost", "private"],
    ["server.local", "private"],
    ["nas.home.arpa", "private"],
    ["printer.lan", "private"],
    ["10.0.0.5", "private"],
    ["127.0.0.1", "private"],
    ["127.255.0.1", "private"],
    ["0.0.0.0", "private"],
    ["169.254.10.1", "private"],
    ["172.16.0.1", "private"],
    ["172.31.255.255", "private"],
    ["192.168.1.20", "private"],
    ["100.64.0.1", "private"],
    ["192.0.2.10", "private"],
    ["198.18.0.1", "private"],
    ["224.0.0.1", "private"],
    ["255.255.255.255", "private"],
    ["::1", "private"],
    ["::", "private"],
    ["fe80::1", "private"],
    ["fd12:3456::1", "private"],
    ["::ffff:192.168.0.1", "private"],
    ["::ffff:8.8.8.8", "private"],
    ["2001:db8::1", "private"],
    ["2002:c0a8:0101::1", "private"],
    ["127.1", "invalid"],
    ["0x7f.0.0.1", "invalid"],
    ["2130706433", "invalid"],
    ["010.0.0.1", "invalid"],
    ["play_server.example.com", "invalid"],
    ["-bad.example.com", "invalid"],
    ["a b.example.com", "invalid"],
    ["", "invalid"],
    ["x".repeat(254), "invalid"],
  ];
  for (const [host, reason] of refused) assert.deepEqual(checkHost(host), { ok: false, reason }, host);
  assert.deepEqual(checkHost("2002:0808:0808::1"), { ok: true, host: "2002:0808:0808::1", ip: true }, "6to4 of a public address");
  assert.equal(checkHost("8.8.8.8", false).ok, false, "websites need a name");
});

test("links: https, a public name, and the form each kind allows", () => {
  assert.equal(safeLink("https://emberfall.example/"), "https://emberfall.example/");
  assert.equal(safeLink("https://discord.gg/abc-123", "discord"), "https://discord.gg/abc-123");
  assert.equal(safeLink("https://discord.com/invite/abc123/", "discord"), "https://discord.com/invite/abc123/");
  assert.equal(safeLink("https://www.youtube.com/@flint", "youtube"), "https://www.youtube.com/@flint");
  assert.equal(safeLink("https://x.com/flint", "x"), "https://x.com/flint");
  for (const [link, kind] of [
    ["http://emberfall.example/", "url"],
    ["javascript:alert(1)", "url"],
    ["https://user:pw@emberfall.example/", "url"],
    ["https://emberfall.example:8443/", "url"],
    ["https://192.168.0.1/", "url"],
    ["https://8.8.8.8/", "url"],
    ["https://localhost/", "url"],
    ["https://router.lan/", "url"],
    ["https://exa mple.com/", "url"],
    ["https://discord.com/channels/1/2", "discord"],
    ["https://discord.gg/abc?event=1", "discord"],
    ["https://discordapp.evil.example/invite/abc", "discord"],
    ["https://facebook.com/flint", "x"],
    ["https://vimeo.com/flint", "youtube"],
    [`https://emberfall.example/${"a".repeat(2050)}`, "url"],
    [42, "url"],
  ] as const) {
    assert.equal(safeLink(link, kind), null, String(link).slice(0, 80));
  }
});

test("text is plain: no hidden, reordering or control characters, no formatting codes", () => {
  assert.equal(plainText("  Ember‮fall ​network\u0007 ", 60), "Emberfall network");
  assert.equal(plainText("§cRed §lBold", 60), "cRed lBold");
  assert.equal(plainText("<b>tags stay text</b>", 60), "<b>tags stay text</b>", "shown as text, never rendered");
  assert.equal(plainText("one\n\n\n\ntwo\r\nthree", 100, true), "one\n\ntwo\nthree");
  assert.equal(plainText("one\ntwo", 100), "one two");
  assert.equal(plainText("x".repeat(61), 60), null);
  assert.equal(plainText("🙂".repeat(60), 60), "🙂".repeat(60), "counted in characters, not UTF-16 units");
  assert.equal(plainText(7, 60), null);
});

test("statistics show only when checked under ten minutes ago", () => {
  const f = feed("many");
  const ember = byName(f, "Emberfall Network");
  assert.ok(freshStats(ember, NOW));
  assert.ok(freshStats(ember, NOW + 7 * MIN));
  assert.equal(freshStats(ember, NOW + 8 * MIN), null, "ten minutes after its check");
  assert.equal(freshStats(byName(f, "Northwind Arena"), NOW), null, "checked 25 minutes ago");
  assert.equal(freshStats(byName(f, "Moonmere Survival"), NOW), null, "no statistics at all");
  const drift = freshStats(byName(f, "Driftreef Islands"), NOW);
  assert.deepEqual([drift?.online, drift?.players, drift?.max], [true, null, null], "online, with no counts: none are made up");
  assert.ok(freshDiscordStats(byName(f, "Forgelight Collective"), NOW));
  assert.equal(freshDiscordStats(byName(feed("nocounts"), "Stale Count Discord"), NOW), null);
  const future = { ...ember, stats: { ...ember.stats!, checkedAt: NOW + 5 * MIN } };
  assert.equal(freshStats(future, NOW), null, "a check from the future isn't believed");
});

test("each kind's menu has only the entries that work", () => {
  const f = feed("many");
  assert.deepEqual(menuEntries(byName(f, "Emberfall Network"), NOW, 3), ["info", "stats", "play", "playWith", "favorite", "copyAddress", "website", "discord", "store", "youtube"]);
  assert.deepEqual(menuEntries(byName(f, "Emberfall Network"), NOW, 1), ["info", "stats", "play", "favorite", "copyAddress", "website", "discord", "store", "youtube"], "one profile: no other to pick");
  assert.deepEqual(menuEntries(byName(f, "Emberfall Network"), NOW, 0), ["info", "stats", "favorite", "copyAddress", "website", "discord", "store", "youtube"], "no profile to play with");
  assert.deepEqual(menuEntries(byName(f, "Northwind Arena"), NOW, 2), ["info", "play", "playWith", "favorite", "copyAddress"], "stale statistics: no statistics entry");
  assert.deepEqual(menuEntries(byName(f, "Lumenvale Realms"), NOW, 2), ["info", "stats", "play", "playWith", "favorite", "copyAddress", "website", "x"]);
  assert.deepEqual(menuEntries(byName(f, "Forgelight Collective"), NOW, 2), ["joinDiscord", "copyInvite"]);
  assert.deepEqual(menuEntries(byName(f, "Quarrystone Builders"), NOW, 2), ["joinDiscord", "copyInvite"]);
  assert.deepEqual(menuEntries(byName(f, "Cinderbloc Hosting"), NOW, 2), ["visitWebsite"]);
  assert.deepEqual(menuEntries(byName(f, "Ironpine Hosting"), NOW, 2), ["visitWebsite"]);
});

test("versions: compared by their numbers, and a profile is picked that can join", () => {
  assert.deepEqual(["26.1.2", "1.21.10", "1.8.9", "26.3", "1.21.1", "26.1", "1.21.11"].sort(compareVersions), ["1.8.9", "1.21.1", "1.21.10", "1.21.11", "26.1", "26.1.2", "26.3"]);
  const range = { min: "1.21.1", max: "26.2", list: null };
  assert.ok(versionFits("1.21.11", range) && versionFits("26.2", range) && versionFits("1.21.1", range));
  assert.ok(!versionFits("1.8.9", range) && !versionFits("26.3", range));
  assert.ok(versionFits("26.3", { min: "1.21.10", max: null, list: null }));
  assert.ok(versionFits("1.8.9", { min: null, max: null, list: ["1.8.9"] }) && !versionFits("1.21.11", { min: null, max: null, list: ["1.8.9"] }));
  assert.ok(versionFits("1.8.9", null), "no versions named: any");
  assert.equal(versionsText(range), "1.21.1 – 26.2");
  assert.equal(versionsText({ min: "1.21.10", max: null, list: null }), "1.21.10+");
  assert.equal(versionsText({ min: null, max: null, list: ["1.21.11", "1.8.9"] }), "1.8.9, 1.21.11");

  const server = feed("many").partners.find((p) => p.name === "Emberfall Network")!.server!;
  const profiles = [
    { id: "a", gameVersion: "26.3", lastPlayed: 100 },
    { id: "b", gameVersion: "1.21.11", lastPlayed: null },
    { id: "c", gameVersion: "1.8.9", lastPlayed: 300 },
  ];
  assert.equal(playChoice(server, profiles, "a").pick?.id, "a", "the selected profile when it fits");
  assert.equal(playChoice(server, profiles, null).pick?.id, "b", "else the recommended version");
  const ranged = { ...server, recommendedVersion: null };
  assert.equal(playChoice(ranged, profiles, null).pick?.id, "c", "else the most recently played");
  const pvp = { ...server, versions: { min: null, max: null, list: ["1.8.9"] }, recommendedVersion: "1.8.9" };
  const only = playChoice(pvp, profiles, "a");
  assert.deepEqual([only.pick?.id, only.compatible.map((p) => p.id), only.incompatible.map((p) => p.id)], ["c", ["c"], ["a", "b"]]);
  assert.equal(playChoice(pvp, [profiles[0]], "a").pick, null, "nothing fits: the dialog explains instead");
});

test("times: RFC 3339 as Postgres and JavaScript write it", () => {
  assert.equal(parseTime("2026-11-08T12:00:00.000Z"), Date.UTC(2026, 10, 8, 12));
  assert.equal(parseTime("2026-11-08T13:00:00+01:00"), Date.UTC(2026, 10, 8, 12));
  assert.equal(parseTime("2026-11-08 12:00:00.123456+00"), Date.UTC(2026, 10, 8, 12, 0, 0, 123));
  for (const bad of ["2026-11-08", "next tuesday", "2026-13-01T00:00:00Z", "2026-11-08T25:00:00Z", 1730000000, null]) assert.equal(parseTime(bad), null, String(bad));
});
