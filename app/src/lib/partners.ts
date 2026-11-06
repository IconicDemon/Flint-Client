/*
 * Flint's partnership showcase: the Store's partner feed (`GET <Store>/api/v1/partners/`, schema 1, agreed with the
 * website) read strictly, and the rules the interface applies on every render. Everything in the feed is untrusted:
 * names and text are plain text (never HTML or markup), links must be https and of an allowed form or they are dropped,
 * images come only from Flint's partner-media bucket and are fetched and checked by the launcher (src-tauri/src/partners),
 * and a server address must be a public hostname or IP before anything can join it.
 *
 * The launcher's Rust side fetches the feed (ETag, cache, size limit) and checks again what it acts on (images, the
 * server it adds and joins); this module decides what is shown. No I/O here, so it is tested on its own
 * (partners.test.ts, fixtures in partnerFixtures.json, shared with the Rust tests).
 */

export const PARTNER_SCHEMA = 1;
/** Statistics older than this are not shown (the backend checks them; Flint never invents a count). */
export const STATS_FRESH_MS = 10 * 60_000;
/** A checked time this far ahead of the computer's clock is still believed (clock skew), any further is not. */
const CLOCK_SKEW_MS = 2 * 60_000;
export const MAX_PARTNERS = 200;
export const DEFAULT_PORT = 25565;

export type PartnerKind = "minecraft_server" | "discord" | "hosting";
export type PartnerLabel = "Partner" | "Sponsored";
export type PartnerImage = { sha256: string; width: number; height: number; alt: string };
export type ServerVersions = { min: string | null; max: string | null; list: string[] | null };
export type PartnerServer = {
  host: string;
  port: number;
  /** "java", or another edition the backend named (shown, never joined). */
  edition: string;
  versions: ServerVersions | null;
  recommendedVersion: string | null;
};
export type ServerStats = { online: boolean; players: number | null; max: number | null; checkedAt: number };
export type DiscordStats = { members: number | null; online: number | null; checkedAt: number };
export type LinkKind = "website" | "discord" | "store" | "x" | "youtube";
export type PartnerLinks = Partial<Record<LinkKind, string>>;
export type PrimaryAction = { kind: "join_server" | "open_url"; label: string | null; url: string | null };

export type Partner = {
  id: string;
  slug: string;
  name: string;
  kind: PartnerKind;
  label: PartnerLabel;
  inLibrary: boolean;
  summary: string;
  description: string;
  primary: PrimaryAction;
  /** Always present (a listing without a valid one is left out). */
  featuredImage: PartnerImage;
  /** The wide crop for the directory; absent: the directory uses featuredImage. */
  libraryImage: PartnerImage | null;
  logo: PartnerImage | null;
  server: PartnerServer | null;
  stats: ServerStats | null;
  discordStats: DiscordStats | null;
  links: PartnerLinks;
  startsAt: number | null;
  endsAt: number | null;
  updatedAt: number;
  sort: number;
};

export type PartnerFeed = { schema: 1; generatedAt: number; featuredId: string | null; partners: Partner[] };
/** A listing (or a part of one) the parser left out, and why: for logs and tests, never shown to players. */
export type Dropped = { id: string | null; field: string; reason: string };
export type ParseResult = { feed: PartnerFeed | null; dropped: Dropped[] };
export type ParseOptions = {
  /** Supabase project refs whose partner-media bucket images may come from; empty accepts any project's. */
  mediaProjects: readonly string[];
  /** Development builds only (the launcher says so): a listing may name a server on this computer. */
  allowLoopback?: boolean;
};

// ---------- small checks ----------

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const isUuid = (v: unknown): v is string => typeof v === "string" && UUID.test(v);
const SLUG = /^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$/;
const SHA256 = /^[0-9a-f]{64}$/;
const VERSION = /^\d{1,3}(?:\.\d{1,3}){1,3}$/;
const RFC3339 = /^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(\.\d{1,9})?([Zz]|[+-]\d{2}(?::?\d{2})?)$/;

/** Milliseconds since the epoch for an RFC 3339 time as Postgres and JavaScript write it; null for anything else. */
export function parseTime(v: unknown): number | null {
  if (typeof v !== "string" || v.length > 40) return null;
  const m = RFC3339.exec(v.trim());
  if (!m) return null;
  const [, y, mo, d, h, mi, s, frac = "", zone] = m;
  if (+mo < 1 || +mo > 12 || +d < 1 || +d > 31 || +h > 23 || +mi > 59 || +s > 60) return null;
  let offset = "Z";
  if (zone !== "Z" && zone !== "z") {
    const digits = zone.slice(1).replace(":", "");
    offset = `${zone[0]}${digits.slice(0, 2)}:${(digits.slice(2) || "00").padEnd(2, "0")}`;
  }
  const ms = Date.parse(`${y}-${mo}-${d}T${h}:${mi}:${s}${frac.slice(0, 4)}${offset}`);
  return Number.isFinite(ms) ? ms : null;
}

// Characters that hide or reorder text (bidi overrides, zero-width marks) and control characters: never shown.
const INVISIBLE = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F­؜ᅟᅠ᠎​-‏‪-‮⁠-⁯ㅤ﻿ﾠ￹-￻]/g;

/**
 * Plain text as the interface shows it: invisible and control characters removed, Minecraft's formatting sign (§)
 * removed, runs of spaces collapsed; one line unless `multiline` (then at most one empty line in a row). Null when it
 * isn't text or is longer than `max` characters.
 */
export function plainText(v: unknown, max: number, multiline = false): string | null {
  if (typeof v !== "string") return null;
  let text = v.replace(/\r\n?/g, "\n").replace(INVISIBLE, (c) => (c === "\t" ? " " : "")).replace(/§/g, "");
  text = multiline
    ? text
        .split("\n")
        .map((line) => line.replace(/[ \t]+/g, " ").trim())
        .join("\n")
        .replace(/\n{3,}/g, "\n\n")
        .trim()
    : text.replace(/\s+/g, " ").trim();
  return [...text].length <= max ? text : null;
}

const intIn = (v: unknown, min: number, max: number): number | null => (typeof v === "number" && Number.isInteger(v) && v >= min && v <= max ? v : null);

// ---------- hosts and links ----------

/** Names that only mean something on a local network (or nowhere): never a partner's server or website. */
const LOCAL_SUFFIXES = ["localhost", "local", "localdomain", "internal", "intranet", "lan", "home", "corp", "private", "arpa", "onion", "invalid"];

function ipv4(host: string): number[] | null {
  const parts = host.split(".");
  if (parts.length !== 4 || !parts.every((p) => /^(0|[1-9]\d{0,2})$/.test(p))) return null;
  const n = parts.map(Number);
  return n.every((x) => x <= 255) ? n : null;
}

/** Whether an IPv4 address is on the public internet (not private, loopback, link-local, shared, reserved…). */
export function publicIpv4(a: number[]): boolean {
  const [x, y, z] = a;
  if (x === 0 || x === 10 || x === 127 || x >= 224) return false; // this network, private, loopback, multicast and reserved
  if (x === 100 && y >= 64 && y <= 127) return false; // shared address space (carrier-grade NAT)
  if (x === 169 && y === 254) return false; // link-local
  if (x === 172 && y >= 16 && y <= 31) return false; // private
  if (x === 192 && y === 168) return false; // private
  if (x === 192 && y === 0 && (z === 0 || z === 2)) return false; // protocol assignments, documentation
  if (x === 198 && (y === 18 || y === 19)) return false; // benchmarking
  if (x === 198 && y === 51 && z === 100) return false; // documentation
  if (x === 203 && y === 0 && z === 113) return false; // documentation
  return true;
}

/** An IPv6 address as eight 16-bit groups, or null. Accepts `::` shortening and a trailing IPv4 part. */
function ipv6(host: string): number[] | null {
  if (!/^[0-9a-fA-F:.]+$/.test(host) || host.length > 45) return null;
  const lastColon = host.lastIndexOf(":");
  if (lastColon < 0) return null;
  let text = host;
  let tail: number[] = [];
  if (host.slice(lastColon + 1).includes(".")) {
    // A trailing IPv4 part (::ffff:1.2.3.4) is the last two groups.
    const v4 = ipv4(host.slice(lastColon + 1));
    if (!v4) return null;
    tail = [(v4[0] << 8) | v4[1], (v4[2] << 8) | v4[3]];
    text = host.slice(0, lastColon + 1);
    if (!text.endsWith("::")) text = text.slice(0, -1);
  }
  const halves = text.split("::");
  if (halves.length > 2) return null;
  const group = (s: string) => (s === "" ? [] : s.split(":"));
  const head = group(halves[0]);
  const rest = halves.length === 2 ? group(halves[1]) : [];
  if (![...head, ...rest].every((g) => /^[0-9a-fA-F]{1,4}$/.test(g))) return null;
  const known = head.length + rest.length + tail.length;
  if (halves.length === 1 ? known !== 8 : known > 7) return null;
  const zeros = Array<number>(8 - known).fill(0);
  return [...head.map((g) => parseInt(g, 16)), ...zeros, ...rest.map((g) => parseInt(g, 16)), ...tail];
}

/** Whether an IPv6 address is a public, global unicast one (2000::/3 minus documentation, Teredo and 6to4 of private addresses). */
export function publicIpv6(g: number[]): boolean {
  if ((g[0] & 0xe000) !== 0x2000) return false; // not global unicast: ::1, ::, fc00::/7, fe80::/10, ff00::/8, mapped, NAT64…
  if (g[0] === 0x2001 && g[1] === 0x0db8) return false; // documentation
  if (g[0] === 0x2001 && g[1] === 0) return false; // Teredo
  if (g[0] === 0x2002) return publicIpv4([g[1] >> 8, g[1] & 0xff, g[2] >> 8, g[2] & 0xff]); // 6to4: its IPv4 decides
  return true;
}

export type HostCheck = { ok: true; host: string; ip: boolean } | { ok: false; reason: "invalid" | "private" };

/**
 * A server or website host: a public DNS name (at least two labels, an alphabetic top-level domain, nothing that only
 * works on a local network) or a public IPv4/IPv6 address. Private, loopback, link-local and other non-public addresses
 * are refused. The host comes back lowercased, without a trailing dot or IPv6 brackets.
 */
export function checkHost(raw: unknown, allowIp = true): HostCheck {
  if (typeof raw !== "string") return { ok: false, reason: "invalid" };
  let host = raw.trim().toLowerCase();
  if (host.startsWith("[") && host.endsWith("]")) host = host.slice(1, -1);
  if (host.endsWith(".")) host = host.slice(0, -1);
  if (!host || host.length > 253) return { ok: false, reason: "invalid" };
  const v4 = ipv4(host);
  if (v4) return !allowIp ? { ok: false, reason: "invalid" } : publicIpv4(v4) ? { ok: true, host, ip: true } : { ok: false, reason: "private" };
  if (host.includes(":")) {
    const v6 = ipv6(host);
    if (!v6 || !allowIp) return { ok: false, reason: "invalid" };
    return publicIpv6(v6) ? { ok: true, host, ip: true } : { ok: false, reason: "private" };
  }
  const labels = host.split(".");
  if (labels.length < 2 || !labels.every((l) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(l))) return { ok: false, reason: "invalid" };
  const tld = labels[labels.length - 1];
  if (!/^[a-z]{2,63}$/.test(tld) && !/^xn--[a-z0-9-]{1,59}$/.test(tld)) return { ok: false, reason: "invalid" };
  if (LOCAL_SUFFIXES.includes(tld) || host.endsWith(".home.arpa")) return { ok: false, reason: "private" };
  return { ok: true, host, ip: false };
}

/** `host` or `host:port` (port only when it isn't Minecraft's default), as Minecraft's server list writes it. */
export function serverAddress(server: Pick<PartnerServer, "host" | "port">): string {
  const host = server.host.includes(":") ? `[${server.host}]` : server.host;
  return server.port === DEFAULT_PORT ? host : `${host}:${server.port}`;
}

const DISCORD_CODE = /^[A-Za-z0-9-]{2,32}$/;
const LINK_HOSTS: Partial<Record<LinkKind, string[]>> = {
  x: ["x.com", "www.x.com", "twitter.com", "www.twitter.com"],
  youtube: ["youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"],
};

/**
 * A link the interface may open: https, a public DNS host (no IP literal, no user, no port), at most 2048 characters,
 * and of the form its kind allows (Discord: discord.gg/<code> or discord.com/invite/<code> only). Null otherwise.
 */
export function safeLink(raw: unknown, kind: LinkKind | "url" = "url"): string | null {
  if (typeof raw !== "string" || raw.length > 2048 || /[\s\u0000-\u001f\u007f]/.test(raw)) return null;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== "https:" || url.username || url.password || url.port) return null;
  const host = checkHost(url.hostname, false);
  if (!host.ok) return null;
  if (kind === "discord") {
    const path = url.pathname.replace(/\/$/, "");
    const code = host.host === "discord.gg" || host.host === "www.discord.gg" ? path.slice(1) : (host.host === "discord.com" || host.host === "www.discord.com") && path.startsWith("/invite/") ? path.slice(8) : null;
    if (!code || !DISCORD_CODE.test(code) || url.search || url.hash) return null;
  }
  const hosts = kind === "url" ? undefined : LINK_HOSTS[kind];
  if (hosts && !hosts.includes(host.host)) return null;
  return url.href;
}

/** A Discord invite link, as safeLink accepts it. */
export const discordInvite = (raw: unknown) => safeLink(raw, "discord");

// ---------- images ----------

type ImageShape = "featured" | "library" | "logo";
/** Width ÷ height each image may have (Partners API v1: 16:9, a 2:1 to 5:1 wide crop, square; a little rounding is fine). */
const RATIOS: Record<ImageShape, [number, number]> = { featured: [16 / 9 / 1.04, (16 / 9) * 1.04], library: [2 / 1.02, 5 * 1.02], logo: [1 / 1.04, 1.04] };
const MEDIA_URL = /^https:\/\/([a-z0-9]{20})\.supabase\.co\/storage\/v1\/object\/public\/partner-media\/partners\/([0-9a-f-]{36})\/([0-9a-f]{64})\.(png|jpg|webp)$/;

/**
 * An image as Partners API v1 serves it: exactly
 * `https://<project>.supabase.co/storage/v1/object/public/partner-media/partners/<listing id>/<sha256>.<png|jpg|webp>`,
 * from an allowed project, named by this listing's id and by its own checksum, with no query, port, user or
 * encoded characters. Null otherwise.
 */
export function checkImage(raw: unknown, shape: ImageShape, options: ParseOptions, partnerId: string): PartnerImage | null {
  if (!isObject(raw) || typeof raw.url !== "string" || raw.url.length > 512) return null;
  const m = MEDIA_URL.exec(raw.url);
  if (!m || m[2] !== partnerId) return null;
  if (options.mediaProjects.length > 0 && !options.mediaProjects.includes(m[1])) return null;
  const width = intIn(raw.width, 16, 8192);
  const height = intIn(raw.height, 16, 8192);
  const sha256 = typeof raw.sha256 === "string" && SHA256.test(raw.sha256) && raw.sha256 === m[3] ? raw.sha256 : null;
  if (!width || !height || !sha256) return null;
  const [low, high] = RATIOS[shape];
  if (width / height < low || width / height > high) return null;
  return { sha256, width, height, alt: plainText(raw.alt ?? "", 200) ?? "" };
}

// ---------- the feed ----------

function parseVersions(raw: unknown): ServerVersions | null {
  if (!isObject(raw)) return null;
  const one = (v: unknown) => (typeof v === "string" && VERSION.test(v) ? v : null);
  const list = Array.isArray(raw.list) ? raw.list.filter((v): v is string => typeof v === "string" && VERSION.test(v)).slice(0, 64) : null;
  const versions = { min: one(raw.min), max: one(raw.max), list: list && list.length > 0 ? [...new Set(list)] : null };
  return versions.min || versions.max || versions.list ? versions : null;
}

function parseServer(raw: unknown, allowLoopback = false): PartnerServer | null {
  if (!isObject(raw)) return null;
  const local = allowLoopback && (raw.host === "localhost" || raw.host === "127.0.0.1");
  const host: HostCheck = local ? { ok: true, host: raw.host as string, ip: raw.host !== "localhost" } : checkHost(raw.host);
  const port = raw.port === undefined || raw.port === null ? DEFAULT_PORT : intIn(raw.port, 1, 65535);
  if (!host.ok || port === null) return null;
  const edition = typeof raw.edition === "string" && /^[a-z_]{1,20}$/.test(raw.edition) ? raw.edition : "java";
  const recommended = typeof raw.recommended_version === "string" && VERSION.test(raw.recommended_version) ? raw.recommended_version : null;
  return { host: host.host, port, edition, versions: parseVersions(raw.versions), recommendedVersion: recommended };
}

function parseStats(raw: unknown): ServerStats | null {
  if (!isObject(raw) || typeof raw.online !== "boolean") return null;
  const checkedAt = parseTime(raw.checked_at);
  if (checkedAt === null) return null;
  return { online: raw.online, players: intIn(raw.players, 0, 10_000_000), max: intIn(raw.max, 0, 10_000_000), checkedAt };
}

function parseDiscordStats(raw: unknown): DiscordStats | null {
  if (!isObject(raw)) return null;
  const checkedAt = parseTime(raw.checked_at);
  const members = intIn(raw.members, 0, 100_000_000);
  const online = intIn(raw.online, 0, 100_000_000);
  return checkedAt !== null && (members !== null || online !== null) ? { members, online, checkedAt } : null;
}

const LINK_KINDS: LinkKind[] = ["website", "discord", "store", "x", "youtube"];

/** One listing, or the reason it can't be shown. Optional parts that fail their checks are dropped on their own. */
function parsePartner(raw: unknown, options: ParseOptions, dropped: Dropped[]): Partner | string {
  if (!isObject(raw)) return "not an object";
  const id = isUuid(raw.id) ? raw.id : null;
  if (!id) return "id";
  const drop = (field: string, reason: string) => dropped.push({ id, field, reason });
  const slug = typeof raw.slug === "string" && SLUG.test(raw.slug) ? raw.slug : null;
  const name = plainText(raw.name, 60);
  const kind = raw.kind === "minecraft_server" || raw.kind === "discord" || raw.kind === "hosting" ? raw.kind : null;
  const label = raw.label === "Partner" || raw.label === "Sponsored" ? raw.label : null;
  const summary = plainText(raw.summary, 160);
  const description = raw.description === undefined || raw.description === null ? "" : plainText(raw.description, 1000, true);
  const updatedAt = parseTime(raw.updated_at);
  const sort = typeof raw.sort === "number" && Number.isInteger(raw.sort) && Math.abs(raw.sort) <= 1_000_000 ? raw.sort : null;
  if (!slug) return "slug";
  if (!name) return "name";
  if (!kind) return "kind";
  if (!label) return "label";
  if (typeof raw.in_library !== "boolean") return "in_library";
  if (!summary) return "summary";
  if (description === null) return "description";
  if (updatedAt === null) return "updated_at";
  if (sort === null) return "sort";
  const startsAt = raw.starts_at === undefined || raw.starts_at === null ? null : parseTime(raw.starts_at);
  const endsAt = raw.ends_at === undefined || raw.ends_at === null ? null : parseTime(raw.ends_at);
  // A schedule Flint can't read could show a promotion outside its time: the listing isn't shown.
  if ((raw.starts_at != null && startsAt === null) || (raw.ends_at != null && endsAt === null)) return "schedule";

  let server: PartnerServer | null = null;
  if (kind === "minecraft_server") {
    server = parseServer(raw.server, options.allowLoopback);
    if (!server) return "server";
  } else if (raw.server !== undefined && raw.server !== null) {
    drop("server", "only Minecraft servers have one");
  }

  const links: PartnerLinks = {};
  if (raw.links !== undefined && raw.links !== null) {
    if (!isObject(raw.links)) drop("links", "not an object");
    else
      for (const key of LINK_KINDS) {
        if (raw.links[key] === undefined || raw.links[key] === null) continue;
        const link = safeLink(raw.links[key], key === "website" || key === "store" ? "url" : key);
        if (link) links[key] = link;
        else drop(`links.${key}`, "not an allowed https link");
      }
  }

  if (!isObject(raw.primary)) return "primary";
  const primaryLabel = plainText(raw.primary.label ?? "", 32) || null;
  let primary: PrimaryAction;
  if (raw.primary.kind === "join_server") {
    if (kind !== "minecraft_server") return "primary";
    primary = { kind: "join_server", label: primaryLabel, url: null };
  } else if (raw.primary.kind === "open_url") {
    const url = kind === "discord" ? discordInvite(raw.primary.url) : safeLink(raw.primary.url);
    if (!url) return "primary.url";
    primary = { kind: "open_url", label: primaryLabel, url };
  } else return "primary";
  // A Discord listing must lead to an invite.
  if (kind === "discord" && !links.discord && !(primary.url && discordInvite(primary.url))) return "discord invite";

  // Partners API v1: every listing has a 16:9 featured image (the Store leaves out one without); the wide crop and
  // the logo are optional, and one that fails its check is dropped on its own (the card falls back to the featured image).
  const featuredImage = checkImage(raw.featured_image, "featured", options, id);
  if (!featuredImage) return "featured_image";
  const image = (field: string, shape: ImageShape) => {
    const value = raw[field];
    if (value === undefined || value === null) return null;
    const checked = checkImage(value, shape, options, id);
    if (!checked) drop(field, "not an allowed partner-media image");
    return checked;
  };
  const libraryImage = image("library_image", "library");
  const logo = image("logo", "logo");

  let stats: ServerStats | null = null;
  if (raw.stats !== undefined && raw.stats !== null) {
    stats = kind === "minecraft_server" ? parseStats(raw.stats) : null;
    if (!stats) drop("stats", "unreadable");
  }
  let discordStats: DiscordStats | null = null;
  if (raw.discord_stats !== undefined && raw.discord_stats !== null) {
    discordStats = kind === "discord" ? parseDiscordStats(raw.discord_stats) : null;
    if (!discordStats) drop("discord_stats", "unreadable");
  }

  return { id, slug, name, kind, label, inLibrary: raw.in_library, summary, description: description || summary, primary, featuredImage, libraryImage, logo, server, stats, discordStats, links, startsAt, endsAt, updatedAt, sort };
}

/**
 * The partner feed, read strictly. A body that isn't schema 1 is refused whole (null). Each listing is checked on its
 * own: one that breaks the contract is left out (with the reason in `dropped`), and an optional part that fails its
 * check (an image, a link, statistics) is dropped from that listing alone. Listings come back by `sort`, then name.
 */
export function parsePartnerFeed(raw: unknown, options: ParseOptions): ParseResult {
  const dropped: Dropped[] = [];
  if (!isObject(raw) || raw.schema !== PARTNER_SCHEMA || !Array.isArray(raw.partners)) return { feed: null, dropped: [{ id: null, field: "schema", reason: "not a schema 1 partner feed" }] };
  const generatedAt = parseTime(raw.generated_at);
  if (generatedAt === null) return { feed: null, dropped: [{ id: null, field: "generated_at", reason: "unreadable" }] };
  const featuredId = isUuid(raw.featured_id) ? raw.featured_id : null;
  if (raw.featured_id !== null && raw.featured_id !== undefined && !featuredId) dropped.push({ id: null, field: "featured_id", reason: "not a uuid" });
  const partners: Partner[] = [];
  const seen = new Set<string>();
  for (const item of raw.partners.slice(0, MAX_PARTNERS)) {
    const parsed = parsePartner(item, options, dropped);
    const id = isObject(item) && isUuid(item.id) ? item.id : null;
    if (typeof parsed === "string") dropped.push({ id, field: parsed, reason: "listing left out" });
    else if (seen.has(parsed.id)) dropped.push({ id, field: "id", reason: "listed twice" });
    else {
      seen.add(parsed.id);
      partners.push(parsed);
    }
  }
  if (raw.partners.length > MAX_PARTNERS) dropped.push({ id: null, field: "partners", reason: `more than ${MAX_PARTNERS}` });
  partners.sort((a, b) => a.sort - b.sort || a.name.localeCompare(b.name));
  return { feed: { schema: 1, generatedAt, featuredId, partners }, dropped };
}

// ---------- what is shown now ----------

/** Within its schedule now: started (or no start) and not ended. Checked on every render, so a cached promotion that ended never shows. */
export const isActive = (p: Partner, now: number) => (p.startsAt === null || p.startsAt <= now) && (p.endsAt === null || now < p.endsAt);

/** Checked under 10 minutes ago (and not in the future beyond clock skew). */
export const isFresh = (checkedAt: number | null | undefined, now: number) => checkedAt != null && now - checkedAt < STATS_FRESH_MS && checkedAt - now <= CLOCK_SKEW_MS;

export const freshStats = (p: Partner, now: number): ServerStats | null => (p.stats && isFresh(p.stats.checkedAt, now) ? p.stats : null);
export const freshDiscordStats = (p: Partner, now: number): DiscordStats | null => (p.discordStats && isFresh(p.discordStats.checkedAt, now) ? p.discordStats : null);

/** The one featured listing for Home, when there is one and it is running now; otherwise null and Home shows nothing in its place. */
export function featuredPartner(feed: PartnerFeed | null, now: number): Partner | null {
  if (!feed?.featuredId) return null;
  const p = feed.partners.find((x) => x.id === feed.featuredId);
  return p && isActive(p, now) ? p : null;
}

/** Library → Partnerships: the listings in the directory that are running now, in the backend's order. */
export const directoryPartners = (feed: PartnerFeed | null, now: number): Partner[] => (feed ? feed.partners.filter((p) => p.inLibrary && isActive(p, now)) : []);

/** A Minecraft server listing Flint can join: Java Edition. */
export const canJoin = (p: Partner): p is Partner & { server: PartnerServer } => p.kind === "minecraft_server" && !!p.server && p.server.edition === "java";

/** The Discord invite of a Discord listing (its own link first, then its primary link). */
export const inviteOf = (p: Partner): string | null => p.links.discord ?? (p.primary.url && discordInvite(p.primary.url)) ?? null;

/** The website a listing's "Visit website" opens: its primary link for hosting, else its website link. */
export const websiteOf = (p: Partner): string | null => (p.kind === "hosting" ? (p.primary.url ?? p.links.website ?? null) : (p.links.website ?? null));

export type MenuEntry =
  | "info"
  | "stats"
  | "play"
  | "playWith"
  | "favorite"
  | "copyAddress"
  | "website"
  | "discord"
  | "store"
  | "x"
  | "youtube"
  | "joinDiscord"
  | "copyInvite"
  | "visitWebsite";

/**
 * A listing's context menu: only entries that work for it now. A server: its info, statistics only when fresh real ones
 * exist, Play (and Play with another profile when there is another to pick), favourites, its address and its verified
 * links. Discord: join and copy the invite. Hosting: its website.
 */
export function menuEntries(p: Partner, now: number, profiles: number): MenuEntry[] {
  if (p.kind === "discord") return inviteOf(p) ? ["joinDiscord", "copyInvite"] : [];
  if (p.kind === "hosting") return websiteOf(p) ? ["visitWebsite"] : [];
  const out: MenuEntry[] = ["info"];
  if (freshStats(p, now)) out.push("stats");
  if (canJoin(p) && profiles > 0) {
    out.push("play");
    if (profiles > 1) out.push("playWith");
  }
  out.push("favorite");
  if (p.server) out.push("copyAddress");
  for (const key of ["website", "discord", "store", "x", "youtube"] as const) if (p.links[key]) out.push(key);
  return out;
}

// ---------- versions and profiles ----------

/** Compares Minecraft release numbers by their parts (1.8.9 < 1.21.1 < 1.21.10 < 26.1 < 26.1.2). */
export function compareVersions(a: string, b: string): number {
  const x = a.split(".").map((n) => parseInt(n, 10) || 0);
  const y = b.split(".").map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] ?? 0) - (y[i] ?? 0);
    if (d !== 0) return d < 0 ? -1 : 1;
  }
  return 0;
}

/** Whether a Minecraft version can join a server with these versions (no versions listed: any). */
export function versionFits(version: string, versions: ServerVersions | null): boolean {
  if (!versions) return true;
  if (versions.list) return versions.list.includes(version);
  if (versions.min && compareVersions(version, versions.min) < 0) return false;
  if (versions.max && compareVersions(version, versions.max) > 0) return false;
  return true;
}

/** "1.8.9 – 26.3", "1.21.11 or newer", "1.8.9, 1.21.11": null when the server names none. */
export function versionsText(versions: ServerVersions | null, or: (list: string[]) => string = (l) => l.join(", ")): string | null {
  if (!versions) return null;
  if (versions.list) return or([...versions.list].sort(compareVersions));
  if (versions.min && versions.max) return versions.min === versions.max ? versions.min : `${versions.min} – ${versions.max}`;
  if (versions.min) return `${versions.min}+`;
  if (versions.max) return `≤ ${versions.max}`;
  return null;
}

type ProfileLike = { id: string; gameVersion: string; lastPlayed?: number | null };

/**
 * Which profiles can play on a server, and the one to suggest: the selected profile when it fits, else one on the
 * server's recommended version, else the most recently played that fits, else the newest version that fits.
 */
export function playChoice<P extends ProfileLike>(server: PartnerServer, profiles: P[], selectedId: string | null): { compatible: P[]; incompatible: P[]; pick: P | null } {
  const compatible = profiles.filter((p) => versionFits(p.gameVersion, server.versions));
  const incompatible = profiles.filter((p) => !compatible.includes(p));
  const pick =
    compatible.find((p) => p.id === selectedId) ??
    compatible.find((p) => !!server.recommendedVersion && p.gameVersion === server.recommendedVersion) ??
    [...compatible].filter((p) => p.lastPlayed).sort((a, b) => (b.lastPlayed ?? 0) - (a.lastPlayed ?? 0))[0] ??
    [...compatible].sort((a, b) => compareVersions(b.gameVersion, a.gameVersion))[0] ??
    null;
  return { compatible, incompatible, pick };
}

/** A stable hue (0–359) for a listing without pictures, from its id. */
export function hueOf(id: string): number {
  let h = 0;
  for (const c of id) h = (h * 31 + c.charCodeAt(0)) % 360;
  return h;
}
