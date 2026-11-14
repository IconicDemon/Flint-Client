//! The partner listings in the game: what the game link (`gamelink.rs`) gives the in-game Discover page and the main
//! menu's partner count. The feed is the launcher's own (fetched, cached and capped in `mod.rs`); the game gets a trimmed
//! copy read with the rules the launcher's page applies (src/lib/partners.ts `parsePartnerFeed` and `directoryPartners`),
//! so a listing the Library doesn't show never shows in the game either:
//!
//! - Only listings in the directory (`in_library`) whose schedule runs now; a schedule Flint can't read hides the listing.
//! - Required parts (id, slug, name, kind, label, summary, sort, update time, primary action, a 16:9 featured picture from
//!   Flint's partner-media bucket, a public server for a Minecraft server, an invite for a Discord listing) checked, or the
//!   listing is left out; optional parts (the wide picture, the logo, links, statistics) dropped on their own.
//! - Text is plain: control, invisible and reordering characters and Minecraft's formatting sign removed, spaces collapsed.
//! - Statistics only when the Store checked them in the last ten minutes (and not from the future beyond clock skew).
//! - Links only https to a public DNS name of the form each kind allows (`safe_link`, as the page's `safeLink`).
//!
//! Pictures are named by checksum; the game asks for one (`partnerArt`), and gets it from the launcher's checked cache
//! (`media.rs`). A WebP picture (which Minecraft can't read) is converted to a PNG no larger than the game draws it
//! ([`webp_to_png`]).
use super::rules::{self, Versions};
use crate::store::{is_uuid, rfc3339_secs};
use serde::Serialize;
use serde_json::{json, Value};
use std::collections::HashSet;
use url::Url;

/// The most listings read from a feed (the page reads no more).
pub const MAX_PARTNERS: usize = 200;
/// Statistics older than this aren't shown (ms).
pub const STATS_FRESH_MS: i64 = 10 * 60_000;
/// A check this far ahead of the computer's clock is still believed (ms).
const CLOCK_SKEW_MS: i64 = 2 * 60_000;
/// A WebP picture is converted to a PNG that fits this box (the Discover page draws a banner at most this large).
pub const ART_MAX_W: u32 = 960;
pub const ART_MAX_H: u32 = 540;
/// The most pixels a WebP picture may have before it is decoded (16 megapixels).
const MAX_WEBP_PIXELS: u64 = 16 * 1024 * 1024;

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameStats {
    pub online: bool,
    pub players: Option<i64>,
    pub max: Option<i64>,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Picture {
    pub sha: String,
    pub width: i64,
    pub height: i64,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct Logo {
    pub sha: String,
}

#[derive(Serialize, Clone, Debug, PartialEq, Default)]
pub struct Links {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub website: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub discord: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub store: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub x: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub youtube: Option<String>,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Primary {
    /// `join_server` or `open_url`.
    pub kind: String,
    pub label: Option<String>,
    pub url: Option<String>,
}

/// One listing as the game shows it.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GamePartner {
    pub id: String,
    pub name: String,
    /// `minecraft_server`, `discord` or `hosting`.
    pub kind: String,
    /// `Partner` or `Sponsored`.
    pub label: String,
    pub summary: String,
    /// The feed's featured listing (Home's).
    pub featured: bool,
    /// A server's `host` or `host:port`, as the server list writes it.
    pub address: Option<String>,
    pub edition: Option<String>,
    /// A Java Edition server that takes the game's version (any version when the game named none).
    pub joinable: bool,
    /// The versions a server takes, in the page's words ("1.8.9 – 26.3").
    pub versions: Option<String>,
    pub recommended: Option<String>,
    pub stats: Option<GameStats>,
    pub members: Option<i64>,
    pub members_online: Option<i64>,
    /// The wide picture when it checks out, else the 16:9 featured one.
    pub banner: Option<Picture>,
    pub logo: Option<Logo>,
    pub links: Links,
    pub primary: Primary,
}

// ---------- text ----------

fn hidden(c: char) -> bool {
    c.is_control()
        || matches!(c, '\u{00ad}' | '\u{061c}' | '\u{115f}' | '\u{1160}' | '\u{180e}' | '\u{200b}'..='\u{200f}' | '\u{202a}'..='\u{202e}' | '\u{2060}'..='\u{206f}' | '\u{3164}' | '\u{feff}' | '\u{ffa0}' | '\u{fff9}'..='\u{fffb}' | '§')
}

/// Plain one-line text as the page shows it (its `plainText`): hidden characters and `§` removed, spaces collapsed. None
/// when it isn't a string or is longer than `max` characters; may be empty.
pub fn plain(raw: &Value, max: usize) -> Option<String> {
    let text: String = raw.as_str()?.chars().map(|c| if matches!(c, '\n' | '\r' | '\t') { ' ' } else { c }).filter(|c| !hidden(*c)).collect();
    let text = text.split_whitespace().collect::<Vec<_>>().join(" ");
    (text.chars().count() <= max).then_some(text)
}

/// Plain text that keeps its lines (the page's `plainText(…, multiline)`): each line's spaces collapsed, at most one empty
/// line in a row. None when it isn't a string or is longer than `max` characters.
fn plain_lines(raw: &Value, max: usize) -> Option<String> {
    let text = raw.as_str()?.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<String> = text
        .split('\n')
        .map(|line| line.chars().map(|c| if c == '\t' { ' ' } else { c }).filter(|c| !hidden(*c)).collect::<String>().split(' ').filter(|w| !w.is_empty()).collect::<Vec<_>>().join(" "))
        .collect();
    let mut joined = lines.join("\n");
    while joined.contains("\n\n\n") {
        joined = joined.replace("\n\n\n", "\n\n");
    }
    let joined = joined.trim().to_owned();
    (joined.chars().count() <= max).then_some(joined)
}

fn required_text(raw: &Value, max: usize) -> Option<String> {
    plain(raw, max).filter(|t| !t.is_empty())
}

fn int_in(raw: &Value, min: i64, max: i64) -> Option<i64> {
    // A whole number only (1.5 and "3" are not).
    let n = raw.as_i64().or_else(|| raw.as_u64().and_then(|u| i64::try_from(u).ok()))?;
    (min..=max).contains(&n).then_some(n)
}

/// An RFC 3339 time in ms, or None.
fn time_ms(raw: &Value) -> Option<i64> {
    raw.as_str().filter(|s| s.len() <= 40).and_then(rfc3339_secs).map(|s| s * 1000)
}

// ---------- links ----------

const X_HOSTS: &[&str] = &["x.com", "www.x.com", "twitter.com", "www.twitter.com"];
const YOUTUBE_HOSTS: &[&str] = &["youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"];

/// What a link is for: a plain website, or a network whose form is checked.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum LinkKind {
    Url,
    Discord,
    X,
    Youtube,
}

/// A link the game may open (the page's `safeLink`): https, a public DNS name (no IP, user, password or port), at most
/// 2048 characters with no spaces or control characters, and the form its kind allows (Discord: `discord.gg/<code>` or
/// `discord.com/invite/<code>` only, with no query or fragment). None otherwise.
pub fn safe_link(raw: &str, kind: LinkKind) -> Option<String> {
    if raw.len() > 2048 || raw.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return None;
    }
    let url = Url::parse(raw).ok()?;
    if url.scheme() != "https" || !url.username().is_empty() || url.password().is_some() || url.port().is_some() {
        return None;
    }
    let host = match url.host()? {
        url::Host::Domain(domain) => rules::check_host(domain, false).ok()?,
        _ => return None,
    };
    match kind {
        LinkKind::Discord => {
            let path = url.path().strip_suffix('/').unwrap_or(url.path());
            let code = match host.as_str() {
                "discord.gg" | "www.discord.gg" => path.strip_prefix('/'),
                "discord.com" | "www.discord.com" => path.strip_prefix("/invite/"),
                _ => None,
            }?;
            let code_ok = (2..=32).contains(&code.len()) && code.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-');
            if !code_ok || url.query().is_some() || url.fragment().is_some() {
                return None;
            }
        }
        LinkKind::X if !X_HOSTS.contains(&host.as_str()) => return None,
        LinkKind::Youtube if !YOUTUBE_HOSTS.contains(&host.as_str()) => return None,
        _ => {}
    }
    Some(url.to_string())
}

// ---------- pictures ----------

#[derive(Clone, Copy)]
enum Shape {
    Featured,
    Library,
    Logo,
}

/// A picture as Partners API v1 serves it for this listing (`media::media_url`), at a size and shape the page accepts.
fn picture(raw: &Value, shape: Shape, id: &str, projects: &[String]) -> Option<Picture> {
    let url = raw["url"].as_str().filter(|u| u.len() <= 512)?;
    let sha = raw["sha256"].as_str()?;
    super::media::media_url(url, id, sha, projects)?;
    let width = int_in(&raw["width"], 16, 8192)?;
    let height = int_in(&raw["height"], 16, 8192)?;
    let ratio = width as f64 / height as f64;
    let (low, high) = match shape {
        Shape::Featured => (16.0 / 9.0 / 1.04, 16.0 / 9.0 * 1.04),
        Shape::Library => (2.0 / 1.02, 5.0 * 1.02),
        Shape::Logo => (1.0 / 1.04, 1.04),
    };
    (low..=high).contains(&ratio).then(|| Picture { sha: sha.to_owned(), width, height })
}

// ---------- servers and versions ----------

fn parse_versions(raw: &Value) -> Option<Versions> {
    let one = |v: &Value| v.as_str().filter(|s| rules::is_version(s)).map(str::to_owned);
    let mut list: Vec<String> = Vec::new();
    for v in raw["list"].as_array().into_iter().flatten().filter_map(one).take(64) {
        if !list.contains(&v) {
            list.push(v);
        }
    }
    let versions = Versions { min: one(&raw["min"]), max: one(&raw["max"]), list: (!list.is_empty()).then_some(list) };
    (versions.min.is_some() || versions.max.is_some() || versions.list.is_some()).then_some(versions)
}

/// The page's `versionsText`: "1.8.9, 1.21.11", "1.8.9 – 26.3", "1.21.10+", "≤ 26.3".
pub fn versions_text(v: &Versions) -> Option<String> {
    if let Some(list) = &v.list {
        let mut sorted = list.clone();
        sorted.sort_by(|a, b| rules::compare_versions(a, b));
        return Some(sorted.join(", "));
    }
    match (&v.min, &v.max) {
        (Some(min), Some(max)) if min == max => Some(min.clone()),
        (Some(min), Some(max)) => Some(format!("{min} – {max}")),
        (Some(min), None) => Some(format!("{min}+")),
        (None, Some(max)) => Some(format!("≤ {max}")),
        (None, None) => None,
    }
}

struct Server {
    host: String,
    port: u16,
    edition: String,
    versions: Option<Versions>,
    recommended: Option<String>,
}

fn parse_server(raw: &Value, loopback: bool) -> Option<Server> {
    if !raw.is_object() {
        return None;
    }
    let host = rules::check_host(raw["host"].as_str()?, loopback).ok()?;
    let port = match &raw["port"] {
        Value::Null => rules::DEFAULT_PORT,
        v => u16::try_from(int_in(v, 1, 65535)?).ok()?,
    };
    let edition = raw["edition"].as_str().filter(|e| (1..=20).contains(&e.len()) && e.bytes().all(|b| b.is_ascii_lowercase() || b == b'_')).unwrap_or("java").to_owned();
    let recommended = raw["recommended_version"].as_str().filter(|v| rules::is_version(v)).map(str::to_owned);
    Some(Server { host, port, edition, versions: parse_versions(&raw["versions"]), recommended })
}

fn fresh(checked_at: Option<i64>, now_ms: i64) -> bool {
    checked_at.is_some_and(|at| now_ms - at < STATS_FRESH_MS && at - now_ms <= CLOCK_SKEW_MS)
}

// ---------- listings ----------

fn slug_ok(slug: &str) -> bool {
    let b = slug.as_bytes();
    (1..=64).contains(&b.len())
        && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
        && b[0] != b'-'
        && b[b.len() - 1] != b'-'
}

/// One listing as the game shows it, with whether the directory lists it now (in the directory, its schedule running); None
/// when the page would leave it out.
fn listing(raw: &Value, featured_id: Option<&str>, now_ms: i64, version: Option<&str>, projects: &[String], loopback: bool) -> Option<(GamePartner, bool)> {
    let id = raw["id"].as_str().filter(|id| is_uuid(id) && id.bytes().all(|b| !b.is_ascii_uppercase()))?.to_owned();
    raw["slug"].as_str().filter(|s| slug_ok(s))?;
    let name = required_text(&raw["name"], 60)?;
    let kind = raw["kind"].as_str().filter(|k| matches!(*k, "minecraft_server" | "discord" | "hosting"))?.to_owned();
    let label = raw["label"].as_str().filter(|l| matches!(*l, "Partner" | "Sponsored"))?.to_owned();
    let in_library = raw["in_library"].as_bool()?;
    let summary = required_text(&raw["summary"], 160)?;
    if !raw["description"].is_null() {
        // Not shown in the game, but a listing the page leaves out for it isn't shown here either.
        plain_lines(&raw["description"], 1000)?;
    }
    time_ms(&raw["updated_at"])?;
    int_in(&raw["sort"], -1_000_000, 1_000_000)?;
    let when = |key: &str| -> Option<Option<i64>> {
        match &raw[key] {
            Value::Null => Some(None),
            v => time_ms(v).map(Some),
        }
    };
    // A schedule Flint can't read could show a promotion outside its time: the listing isn't shown.
    let (starts, ends) = (when("starts_at")?, when("ends_at")?);
    let listed = in_library && starts.is_none_or(|s| s <= now_ms) && ends.is_none_or(|e| now_ms < e);

    let server = if kind == "minecraft_server" { Some(parse_server(&raw["server"], loopback)?) } else { None };

    let mut links = Links::default();
    if let Some(given) = raw["links"].as_object() {
        let link = |key: &str, kind: LinkKind| given.get(key).and_then(Value::as_str).and_then(|u| safe_link(u, kind));
        links = Links {
            website: link("website", LinkKind::Url),
            discord: link("discord", LinkKind::Discord),
            store: link("store", LinkKind::Url),
            x: link("x", LinkKind::X),
            youtube: link("youtube", LinkKind::Youtube),
        };
    }

    let primary_raw = raw["primary"].as_object()?;
    let primary_label = primary_raw.get("label").filter(|l| !l.is_null()).map_or(Some(String::new()), |l| plain(l, 32)).filter(|l| !l.is_empty());
    let primary = match primary_raw.get("kind").and_then(Value::as_str)? {
        "join_server" if kind == "minecraft_server" => Primary { kind: "join_server".into(), label: primary_label, url: None },
        "open_url" => {
            let raw_url = primary_raw.get("url").and_then(Value::as_str)?;
            let url = safe_link(raw_url, if kind == "discord" { LinkKind::Discord } else { LinkKind::Url })?;
            Primary { kind: "open_url".into(), label: primary_label, url: Some(url) }
        }
        _ => return None,
    };
    // A Discord listing must lead to an invite.
    if kind == "discord" && links.discord.is_none() && primary.url.is_none() {
        return None;
    }

    let featured_image = picture(&raw["featured_image"], Shape::Featured, &id, projects)?;
    let banner = (!raw["library_image"].is_null()).then(|| picture(&raw["library_image"], Shape::Library, &id, projects)).flatten().unwrap_or(featured_image);
    let logo = (!raw["logo"].is_null()).then(|| picture(&raw["logo"], Shape::Logo, &id, projects)).flatten().map(|p| Logo { sha: p.sha });

    let stats = (kind == "minecraft_server" && raw["stats"].is_object())
        .then(|| {
            let s = &raw["stats"];
            let online = s["online"].as_bool()?;
            fresh(time_ms(&s["checked_at"]), now_ms).then(|| GameStats { online, players: int_in(&s["players"], 0, 10_000_000), max: int_in(&s["max"], 0, 10_000_000) })
        })
        .flatten();
    let (members, members_online) = if kind == "discord" && raw["discord_stats"].is_object() {
        let d = &raw["discord_stats"];
        let (m, o) = (int_in(&d["members"], 0, 100_000_000), int_in(&d["online"], 0, 100_000_000));
        if fresh(time_ms(&d["checked_at"]), now_ms) && (m.is_some() || o.is_some()) {
            (m, o)
        } else {
            (None, None)
        }
    } else {
        (None, None)
    };

    let (address, edition, joinable, versions, recommended) = match &server {
        Some(s) => {
            let java = s.edition == "java";
            let fits = version.is_none_or(|v| rules::version_fits(v, s.versions.as_ref()));
            (Some(rules::address(&s.host, s.port)), Some(s.edition.clone()), java && fits, s.versions.as_ref().and_then(versions_text), s.recommended.clone())
        }
        None => (None, None, false, None, None),
    };

    let partner = GamePartner {
        featured: featured_id == Some(id.as_str()),
        id,
        name,
        kind,
        label,
        summary,
        address,
        edition,
        joinable,
        versions,
        recommended,
        stats,
        members,
        members_online,
        banner: Some(banner),
        logo,
        links,
        primary,
    };
    Some((partner, listed))
}

/// The directory as the game shows it, from a Partners API v1 body: the listings the Library shows at `now_ms`, by
/// `sort` then name, read as described in the module notes. `version`: the game's Minecraft version, for `joinable`.
pub fn view(body: &Value, now_ms: i64, version: Option<&str>, projects: &[String], loopback: bool) -> Vec<GamePartner> {
    if !super::is_feed(body) || time_ms(&body["generated_at"]).is_none() {
        return Vec::new();
    }
    let featured_id = body["featured_id"].as_str().filter(|id| is_uuid(id));
    let mut seen = HashSet::new();
    let mut out: Vec<(i64, GamePartner)> = Vec::new();
    for raw in body["partners"].as_array().into_iter().flatten().take(MAX_PARTNERS) {
        let Some((partner, listed)) = listing(raw, featured_id, now_ms, version, projects, loopback) else { continue };
        // A listing named twice: only its first readable entry counts (the page does the same).
        if seen.insert(partner.id.clone()) && listed {
            out.push((raw["sort"].as_i64().unwrap_or(0), partner));
        }
    }
    out.sort_by(|(a, x), (b, y)| a.cmp(b).then_with(|| x.name.to_lowercase().cmp(&y.name.to_lowercase())).then_with(|| x.name.cmp(&y.name)));
    out.into_iter().map(|(_, p)| p).collect()
}

/// The `partners` answer for a game: where the feed came from, why it may be old, the server count and the listings.
pub fn reply(answer: &super::FeedAnswer, now_ms: i64, version: Option<&str>) -> Value {
    let partners = answer.body.as_ref().map(|b| view(b, now_ms, version, &answer.media_projects, answer.allow_loopback)).unwrap_or_default();
    let servers = partners.iter().filter(|p| p.kind == "minecraft_server").count();
    json!({ "source": answer.source, "problem": answer.problem, "servers": servers, "partners": partners })
}

// ---------- in the app ----------

/// The picture with checksum `sha` for the game, from the launcher's checked cache: a PNG or JPEG as it is, a WebP as a
/// PNG. `{"type": "png"|"jpeg", "data": base64}`. Only a picture the current feed lists.
pub async fn art(state: &crate::core::AppState, sha: &str) -> crate::core::CmdResult<Value> {
    use base64::Engine;
    let url = super::current_index(state).get(sha).cloned().ok_or("not found")?;
    let (bytes, kind) = super::media::fetch(&state.http, &super::media::dir(state), sha, &url).await.ok_or("Flint couldn't get this picture.")?;
    let (kind, bytes) = match kind {
        "image/png" => ("png", bytes),
        "image/jpeg" => ("jpeg", bytes),
        _ => (
            "png",
            tokio::task::spawn_blocking(move || webp_to_png(&bytes, ART_MAX_W, ART_MAX_H))
                .await
                .ok()
                .flatten()
                .ok_or("unsupported picture")?,
        ),
    };
    Ok(json!({ "type": kind, "data": base64::engine::general_purpose::STANDARD.encode(bytes) }))
}

/// The server of listing `id` for a game on Minecraft `version`, checked as the launcher's Play checks it (play.rs: in the
/// current feed, running now, Java Edition, a public host and port, a version it takes, a public address after DNS).
/// `{"name", "address"}`: the game joins it itself.
pub async fn join(state: &crate::core::AppState, id: &str, version: &str) -> crate::core::CmdResult<Value> {
    let target = super::play::target(state, id)?;
    super::play::compatible(&target, version)?;
    rules::resolves_publicly(&target.join.host, target.join.port, rules::allow_loopback()).await?;
    Ok(json!({ "name": target.join.name, "address": target.join.address }))
}

// ---------- pictures for the game ----------

/// A WebP picture as a PNG fitting `max_w × max_h` (shape kept; never enlarged), for a game that reads only PNG and JPEG.
/// None when it isn't a WebP Flint can read (an animation shows its first frame).
pub fn webp_to_png(bytes: &[u8], max_w: u32, max_h: u32) -> Option<Vec<u8>> {
    let mut decoder = image_webp::WebPDecoder::new(std::io::Cursor::new(bytes)).ok()?;
    let (w, h) = decoder.dimensions();
    if w == 0 || h == 0 || u64::from(w) * u64::from(h) > MAX_WEBP_PIXELS {
        return None;
    }
    let alpha = decoder.has_alpha();
    let mut pixels = vec![0u8; decoder.output_buffer_size()?];
    decoder.read_image(&mut pixels).ok()?;
    let rgba: Vec<u8> = if alpha {
        pixels
    } else {
        pixels.as_chunks::<3>().0.iter().flat_map(|p| [p[0], p[1], p[2], 255]).collect()
    };
    if rgba.len() != (w as usize) * (h as usize) * 4 {
        return None;
    }
    let scale = (max_w as f64 / w as f64).min(max_h as f64 / h as f64).min(1.0);
    let tw = ((w as f64 * scale).round() as u32).clamp(1, w);
    let th = ((h as f64 * scale).round() as u32).clamp(1, h);
    let out = if (tw, th) == (w, h) { rgba } else { shrink(&rgba, w, h, tw, th) };
    encode_png(&out, tw, th)
}

/// Area-averaged downscale of RGBA pixels (premultiplied while averaging, so transparent edges don't darken).
fn shrink(src: &[u8], w: u32, h: u32, tw: u32, th: u32) -> Vec<u8> {
    let (w, h, tw, th) = (w as usize, h as usize, tw as usize, th as usize);
    let mut out = vec![0u8; tw * th * 4];
    for ty in 0..th {
        let y0 = ty * h / th;
        let y1 = ((ty + 1) * h / th).max(y0 + 1).min(h);
        for tx in 0..tw {
            let x0 = tx * w / tw;
            let x1 = ((tx + 1) * w / tw).max(x0 + 1).min(w);
            let mut sum = [0u64; 4];
            for y in y0..y1 {
                for x in x0..x1 {
                    let p = &src[(y * w + x) * 4..(y * w + x) * 4 + 4];
                    let a = u64::from(p[3]);
                    sum[0] += u64::from(p[0]) * a;
                    sum[1] += u64::from(p[1]) * a;
                    sum[2] += u64::from(p[2]) * a;
                    sum[3] += a;
                }
            }
            let n = ((y1 - y0) * (x1 - x0)) as u64;
            let o = &mut out[(ty * tw + tx) * 4..(ty * tw + tx) * 4 + 4];
            // Colour weighted by alpha (none where the whole area is transparent).
            let weight = sum[3];
            for (channel, total) in o.iter_mut().zip(sum).take(3) {
                *channel = (total + weight / 2).checked_div(weight).unwrap_or(0).min(255) as u8;
            }
            o[3] = ((sum[3] + n / 2) / n).min(255) as u8;
        }
    }
    out
}

fn encode_png(rgba: &[u8], w: u32, h: u32) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut out, w, h);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().ok()?;
        writer.write_image_data(rgba).ok()?;
        writer.finish().ok()?;
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    const FIXTURES: &str = include_str!("../../../src/lib/partnerFixtures.json");

    fn fixtures() -> Value {
        serde_json::from_str(FIXTURES).unwrap()
    }

    fn projects() -> Vec<String> {
        fixtures()["mediaProjects"].as_array().unwrap().iter().map(|p| p.as_str().unwrap().to_owned()).collect()
    }

    fn now(key: &str) -> i64 {
        rfc3339_secs(fixtures()[key].as_str().unwrap()).unwrap() * 1000
    }

    fn shown(set: &str, version: Option<&str>) -> Vec<GamePartner> {
        let at = now(if set == "example" { "exampleNow" } else { "now" });
        view(&fixtures()["sets"][set], at, version, &projects(), false)
    }

    fn names(list: &[GamePartner]) -> Vec<&str> {
        list.iter().map(|p| p.name.as_str()).collect()
    }

    fn by_name<'a>(list: &'a [GamePartner], name: &str) -> &'a GamePartner {
        list.iter().find(|p| p.name == name).unwrap_or_else(|| panic!("{name} listed"))
    }

    #[test]
    fn the_directory_reads_like_the_launchers_page() {
        let list = shown("featured", Some("26.3"));
        assert_eq!(names(&list), ["Emberfall Network", "Skyhollow SMP", "Forgelight Collective", "Cinderbloc Hosting"]);
        let ember = by_name(&list, "Emberfall Network");
        assert!(ember.featured && ember.joinable);
        assert_eq!(ember.address.as_deref(), Some("play.emberfall.example"));
        assert_eq!(ember.versions.as_deref(), Some("1.8.9 – 26.3"));
        assert_eq!(ember.recommended.as_deref(), Some("1.21.11"));
        assert_eq!(ember.stats, Some(GameStats { online: true, players: Some(1284), max: Some(5000) }));
        assert_eq!(ember.links.discord.as_deref(), Some("https://discord.gg/emberfall"));
        assert_eq!(ember.links.youtube.as_deref(), Some("https://www.youtube.com/@emberfall"));
        assert_eq!(ember.primary, Primary { kind: "join_server".into(), label: Some("Play".into()), url: None });
        // The wide picture for the banner, the logo by its checksum.
        let banner = ember.banner.as_ref().unwrap();
        assert_eq!((banner.width, banner.height), (1600, 400));
        assert_eq!(banner.sha, "9b4b11fb5dd783d98f9c752ca00cd0b923f1e495bf3e6ae03c5b9b8bf6cf6f33");
        assert_eq!(ember.logo.as_ref().unwrap().sha, "dd153fd18b9836b8d5f60693105f894f5d0fc6383204ac1953498998da94150c");
        let sky = by_name(&list, "Skyhollow SMP");
        assert_eq!(sky.address.as_deref(), Some("mc.skyhollow.example:25570"));
        assert_eq!(sky.versions.as_deref(), Some("1.21.10+"));
        assert!(!sky.featured);
        let forge = by_name(&list, "Forgelight Collective");
        assert_eq!((forge.members, forge.members_online), (Some(18240), Some(2210)));
        assert_eq!(forge.primary.url.as_deref(), Some("https://discord.gg/forgelight"));
        assert!(!forge.joinable && forge.address.is_none() && forge.stats.is_none());
        let host = by_name(&list, "Cinderbloc Hosting");
        assert_eq!(host.primary.url.as_deref(), Some("https://cinderbloc.example/flint"));
        assert_eq!(host.links.website.as_deref(), Some("https://cinderbloc.example/"));
    }

    #[test]
    fn only_what_runs_now_and_is_in_the_directory() {
        // The cup is a running promotion but not in the directory.
        let many = shown("many", Some("26.3"));
        assert!(!names(&many).contains(&"Frostfire Cup"));
        assert_eq!(many.len(), 12);
        assert_eq!(many.first().map(|p| p.name.as_str()), Some("Emberfall Network"), "by sort");
        assert_eq!(names(&shown("expired", None)), ["Lumenvale Realms"], "ended, not started yet and paused promotions are hidden");
        assert!(shown("none", None).is_empty());
        // Not a feed: nothing.
        assert!(view(&json!({ "schema": 2, "partners": [] }), 0, None, &[], false).is_empty());
        assert!(view(&json!({ "schema": 1, "partners": [], "generated_at": "soon" }), 0, None, &[], false).is_empty());
    }

    #[test]
    fn a_listing_sent_twice_shows_once() {
        let mut body = fixtures()["sets"]["featured"].clone();
        let mut again = body["partners"][0].clone();
        again["name"] = json!("Emberfall Again");
        body["partners"].as_array_mut().unwrap().push(again);
        let list = view(&body, now("now"), None, &projects(), false);
        assert_eq!(list.len(), 4);
        assert!(!names(&list).contains(&"Emberfall Again"));
    }

    #[test]
    fn joinable_follows_the_games_version() {
        let on_26 = shown("many", Some("26.3"));
        let on_189 = shown("many", Some("1.8.9"));
        let any = shown("many", None);
        assert!(!by_name(&on_26, "Northwind Arena").joinable, "1.8.9 only");
        assert!(by_name(&on_189, "Northwind Arena").joinable);
        assert!(!by_name(&on_189, "Driftreef Islands").joinable);
        assert_eq!(by_name(&on_26, "Driftreef Islands").versions.as_deref(), Some("1.21.11, 26.1.2, 26.2, 26.3"));
        assert_eq!(by_name(&on_26, "Moonmere Survival").versions.as_deref(), Some("1.21.1 – 1.21.11"));
        assert!(!by_name(&on_26, "Moonmere Survival").joinable);
        assert!(any.iter().filter(|p| p.kind == "minecraft_server").all(|p| p.joinable), "no version named: any Java server");
        assert!(by_name(&on_26, "Glasswing Games").address.as_deref() == Some("play.glasswing.example:25566"));
    }

    #[test]
    fn stale_or_missing_numbers_are_never_shown() {
        let list = shown("nocounts", Some("26.3"));
        assert!(by_name(&list, "Northwind Arena").stats.is_none(), "checked 25 minutes ago");
        assert!(by_name(&list, "Moonmere Survival").stats.is_none(), "none given");
        assert!(by_name(&list, "Driftreef Islands").stats.is_some());
        let stale = by_name(&list, "Stale Count Discord");
        assert_eq!((stale.members, stale.members_online), (None, None));
        // A check from the future beyond clock skew isn't believed either.
        let mut body = fixtures()["sets"]["featured"].clone();
        body["partners"][0]["stats"]["checked_at"] = json!("2026-11-08T12:05:00.000Z");
        let list = view(&body, now("now"), None, &projects(), false);
        assert!(by_name(&list, "Emberfall Network").stats.is_none());
        let offline = shown("offline", None);
        assert_eq!(by_name(&offline, "Glasswing Games").stats.as_ref().map(|s| s.online), Some(false));
    }

    #[test]
    fn broken_listings_are_left_out_and_broken_parts_dropped() {
        let list = shown("invalid", Some("26.3"));
        let mut got = names(&list);
        got.sort();
        assert_eq!(got, ["Bad Extra Pictures", "Bad Links Server", "Bedrock Only Realm", "Emberfall Network"]);
        assert!(list.iter().all(|p| !p.featured), "a featured id that isn't a uuid");
        let extras = by_name(&list, "Bad Extra Pictures");
        let banner = extras.banner.as_ref().unwrap();
        assert_eq!((banner.width, banner.height), (1600, 900), "a wide picture from another bucket: the featured one instead");
        assert!(extras.logo.is_none(), "a .gif logo is dropped");
        assert_eq!(by_name(&list, "Bad Links Server").links, Links::default(), "http, a fake Discord, javascript:, the wrong network, a user name");
        let bedrock = by_name(&list, "Bedrock Only Realm");
        assert_eq!(bedrock.edition.as_deref(), Some("bedrock"));
        assert!(!bedrock.joinable, "shown, never joined");
    }

    #[test]
    fn the_websites_example_reads_whole() {
        let list = shown("example", None);
        assert_eq!(names(&list), ["Ember SMP", "Flint Builders", "Blaze Hosting"]);
        assert!(by_name(&list, "Ember SMP").featured);
        assert!(by_name(&list, "Ember SMP").logo.is_none());
        assert_eq!(by_name(&list, "Flint Builders").links.discord.as_deref(), Some("https://discord.gg/flintbuild"));
    }

    #[test]
    fn text_is_plain() {
        assert_eq!(plain(&json!("  §cEmber\u{202e}fall \u{200b}Network\n\tPlus "), 60).as_deref(), Some("cEmberfall Network Plus"));
        assert_eq!(plain(&json!("x".repeat(61)), 60), None);
        assert_eq!(plain(&json!(5), 60), None);
        let mut body = fixtures()["sets"]["featured"].clone();
        body["partners"][0]["name"] = json!("\u{200b}§\u{202e}");
        body["partners"][1]["primary"]["label"] = json!("A label far longer than thirty-two characters");
        body["partners"][2]["summary"] = json!("Line one\r\nline\u{0007} two");
        let list = view(&body, now("now"), None, &projects(), false);
        assert!(!names(&list).contains(&"Emberfall Network"), "nothing left of the name");
        assert_eq!(by_name(&list, "Skyhollow SMP").primary.label, None, "a label too long is dropped, not the listing");
        assert_eq!(by_name(&list, "Forgelight Collective").summary, "Line one line two");
    }

    #[test]
    fn links_are_https_public_names_of_their_kind() {
        assert_eq!(safe_link("https://emberfall.example/", LinkKind::Url).as_deref(), Some("https://emberfall.example/"));
        assert_eq!(safe_link("https://discord.gg/abc-123", LinkKind::Discord).as_deref(), Some("https://discord.gg/abc-123"));
        assert_eq!(safe_link("https://discord.com/invite/abc123/", LinkKind::Discord).as_deref(), Some("https://discord.com/invite/abc123/"));
        assert_eq!(safe_link("https://x.com/flint", LinkKind::X).as_deref(), Some("https://x.com/flint"));
        for (link, kind) in [
            ("http://emberfall.example/", LinkKind::Url),
            ("https://8.8.8.8/", LinkKind::Url),
            ("https://user:pass@site.example/", LinkKind::Url),
            ("https://site.example:8443/", LinkKind::Url),
            ("https://printer.lan/", LinkKind::Url),
            ("javascript:alert(1)", LinkKind::Url),
            ("https://site.example/a b", LinkKind::Url),
            ("https://discord.evil.example/invite/abc", LinkKind::Discord),
            ("https://discord.gg/abc?x=1", LinkKind::Discord),
            ("https://discord.com/channels/1", LinkKind::Discord),
            ("https://facebook.com/x", LinkKind::X),
            ("https://vimeo.com/x", LinkKind::Youtube),
        ] {
            assert_eq!(safe_link(link, kind), None, "{link}");
        }
    }

    #[test]
    fn the_reply_counts_servers_and_says_where_it_came_from() {
        let answer = super::super::FeedAnswer {
            source: super::super::Source::Cache,
            problem: Some(super::super::Problem::Offline),
            body: Some(fixtures()["sets"]["featured"].clone()),
            fetched_at: Some(1),
            media_base: String::new(),
            media_projects: projects(),
            allow_loopback: false,
        };
        let reply = reply(&answer, now("now"), Some("26.3"));
        assert_eq!(reply["source"], "cache");
        assert_eq!(reply["problem"], "offline");
        assert_eq!(reply["servers"], 2);
        let first = &reply["partners"][0];
        assert_eq!(first["membersOnline"], Value::Null);
        assert_eq!(first["banner"]["sha"], "9b4b11fb5dd783d98f9c752ca00cd0b923f1e495bf3e6ae03c5b9b8bf6cf6f33");
        assert!(first["links"].get("x").is_none(), "absent links are left out");
        let none = super::super::FeedAnswer { source: super::super::Source::None, problem: Some(super::super::Problem::Unavailable), body: None, ..answer };
        assert_eq!(reply_of(&none), json!({ "source": "none", "problem": "unavailable", "servers": 0, "partners": [] }));
    }

    fn reply_of(answer: &super::super::FeedAnswer) -> Value {
        reply(answer, 0, None)
    }

    fn webp(w: u32, h: u32, alpha: bool) -> Vec<u8> {
        let mut pixels = Vec::new();
        for y in 0..h {
            for x in 0..w {
                pixels.extend_from_slice(&[(x * 255 / w.max(1)) as u8, (y * 255 / h.max(1)) as u8, 90]);
                if alpha {
                    pixels.push(if x < w / 2 { 255 } else { 0 });
                }
            }
        }
        let mut out = Vec::new();
        let color = if alpha { image_webp::ColorType::Rgba8 } else { image_webp::ColorType::Rgb8 };
        image_webp::WebPEncoder::new(&mut out).encode(&pixels, w, h, color).unwrap();
        out
    }

    fn png_size(bytes: &[u8]) -> (u32, u32) {
        let decoder = png::Decoder::new(std::io::Cursor::new(bytes));
        let reader = decoder.read_info().unwrap();
        (reader.info().width, reader.info().height)
    }

    #[test]
    fn webp_pictures_become_pngs_the_game_can_read() {
        let small = webp_to_png(&webp(64, 36, false), ART_MAX_W, ART_MAX_H).unwrap();
        assert!(small.starts_with(b"\x89PNG\r\n\x1a\n"));
        assert_eq!(png_size(&small), (64, 36), "never enlarged");
        let wide = webp_to_png(&webp(1600, 400, true), ART_MAX_W, ART_MAX_H).unwrap();
        assert_eq!(png_size(&wide), (960, 240), "shrunk to fit, shape kept");
        let tall = webp_to_png(&webp(400, 900, false), ART_MAX_W, ART_MAX_H).unwrap();
        assert_eq!(png_size(&tall), (240, 540));
        assert_eq!(webp_to_png(b"RIFF\x10\x00\x00\x00WEBPnot really", ART_MAX_W, ART_MAX_H), None);
        assert_eq!(webp_to_png(b"\x89PNG\r\n\x1a\n", ART_MAX_W, ART_MAX_H), None);
        // Transparent pixels don't darken what they're averaged with.
        let half = shrink(&[255, 0, 0, 255, 0, 0, 0, 0], 2, 1, 1, 1);
        assert_eq!(half, [255, 0, 0, 128]);
    }
}
