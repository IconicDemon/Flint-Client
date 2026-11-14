//! The showcase: the slideshow Flint's team runs from the Store's admin panel for the in-game main menu, from the Store's
//! `GET <Store>/api/v1/showcase/?placement=game_menu` (`{"slides": [{id, image_url, width, height, title, link_url, alt,
//! starts_at, ends_at, weight}], "rotate_ms"}`), handed to the game through the game link (`gamelink.rs`: `showcase`,
//! `showcaseArt`). The launcher does all of it, so the game never fetches a picture from the internet and never has to
//! read a WebP (which Minecraft can't).
//!
//! - **Fetching**: the Store this build talks to; nothing about the player is sent. `If-None-Match` with the last ETag,
//!   `Cache-Control: max-age` respected (30 s to 10 min, 60 s when not said), the body at most 64 KB, one request at a
//!   time. While the Store can't be reached (or is busy), the last good answer stands, its slides checked against their
//!   schedules again on every answer, so one that ended drops between refreshes.
//! - **Off is not an error**: a Store without the route (404, 405, 410, 501), a placement it doesn't know (400), the
//!   showcase switched off for now (503), or an answer that isn't the contract's shape, means no slides; the game then shows
//!   nothing at all.
//! - **Untrusted**: a slide is shown only with a picture from Flint's own `showcase-media` bucket (https, a Supabase project
//!   of Flint's, no query, port or credentials; the Store's local demo on this computer in development builds only), at a
//!   16:9 size, and a schedule Flint can read. Its title and alt text are plain text; its link must be an https link to a
//!   public name, or the slide shows without one.
//! - **Pictures** (`showcaseArt`): only a slide in the last good list; downloaded without following redirects, at most
//!   2 MB, a PNG, JPEG or WebP by its first bytes (a WebP becomes a PNG fitting 960 × 540), kept in memory for the slides
//!   still listed.
use crate::core::{AppState, CmdResult};
use crate::partners::game::{plain, safe_link, webp_to_png, LinkKind, ART_MAX_H, ART_MAX_W};
use crate::partners::{media, rules};
use base64::Engine;
use reqwest::header::{ACCEPT, CACHE_CONTROL, ETAG, IF_NONE_MATCH, RETRY_AFTER};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};
use url::Url;

pub const PATH: &str = "/api/v1/showcase/?placement=game_menu";
const MAX_BODY: usize = 64 * 1024;
const MAX_IMAGE: usize = 2 * 1024 * 1024;
const TIMEOUT: Duration = Duration::from_secs(10);
const IMAGE_TIMEOUT: Duration = Duration::from_secs(20);
const DEFAULT_MAX_AGE: Duration = Duration::from_secs(60);
/// After a failed request, the next one waits at least this long.
const FAILURE_PAUSE: Duration = Duration::from_secs(30);
/// The most slides shown at once (the Store sends at most 12).
pub const MAX_SLIDES: usize = 12;
const MAX_LISTED: usize = 50;
pub const DEFAULT_ROTATE_MS: u64 = 6000;
const BUCKET: &str = "/storage/v1/object/public/showcase-media/";
/// The Store's local demo (Supabase on this computer), development builds only (`rules::allow_loopback`).
const DEMO: &str = "http://127.0.0.1:54321/";
const COULDNT: &str = "Flint couldn't get this picture.";

/// One slide, checked. Its picture's address stays here (the game asks for the picture by id).
#[derive(Clone, Debug, PartialEq)]
pub struct Slide {
    pub id: String,
    pub title: String,
    pub alt: String,
    pub link: Option<String>,
    pub width: i64,
    pub height: i64,
    pub weight: i64,
    /// ms since the epoch.
    pub starts_at: Option<i64>,
    pub ends_at: Option<i64>,
    pub image: Url,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Showcase {
    pub rotate_ms: u64,
    /// In the Store's order.
    pub slides: Vec<Slide>,
}

impl Showcase {
    fn none() -> Self {
        Self { rotate_ms: DEFAULT_ROTATE_MS, slides: Vec::new() }
    }

    fn slide(&self, id: &str) -> Option<&Slide> {
        self.slides.iter().find(|s| s.id == id)
    }
}

#[derive(Clone)]
struct Known {
    etag: Option<String>,
    showcase: Showcase,
}

/// A picture as the game gets it.
#[derive(Clone)]
struct Picture {
    kind: &'static str,
    bytes: Vec<u8>,
}

/// This run's showcase: the last good answer, when the Store was last asked, and the pictures of the slides listed.
#[derive(Default)]
pub struct ShowcaseState {
    fetch: tokio::sync::Mutex<()>,
    known: Mutex<Option<Known>>,
    /// When the Store was last asked, and how long until it may be asked again.
    last: Mutex<Option<(Instant, Duration)>>,
    art: Mutex<HashMap<String, Picture>>,
    /// Tests: a server on this computer standing in for the Store's local demo.
    #[cfg(test)]
    pub(crate) demo: Mutex<Option<Url>>,
}

// ---------- reading an answer ----------

fn safe_path(raw: &str) -> bool {
    let lower = raw.to_ascii_lowercase();
    !(lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || raw.contains('\\') || raw.contains("//"))
        && raw.split('/').skip(1).all(|segment| !segment.is_empty() && segment != "." && segment != "..")
}

/// A slide's picture address, when it is one Flint fetches: https from Flint's own `showcase-media` bucket in a Supabase
/// project of Flint's (`projects`), or the Store's local demo `demo` (development). No credentials, port, query or
/// fragment, and no `.`, `..` or empty path segment. None otherwise.
pub fn image_url(raw: &str, projects: &[String], demo: Option<&Url>) -> Option<Url> {
    if raw.len() > 1024 || raw.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return None;
    }
    // The path as written: the parser would quietly resolve `..`.
    let written = raw.split_once("://").map(|(_, rest)| rest.find('/').map_or("", |i| &rest[i..]))?;
    if !safe_path(written.split(['?', '#']).next().unwrap_or_default()) {
        return None;
    }
    let url = Url::parse(raw).ok()?;
    if !url.username().is_empty() || url.password().is_some() || url.query().is_some() || url.fragment().is_some() {
        return None;
    }
    let path = url.path();
    if !path.starts_with(BUCKET) || path.len() <= BUCKET.len() || !safe_path(path) {
        return None;
    }
    let flints = url.scheme() == "https"
        && url.port().is_none()
        && url.host_str().and_then(|h| h.strip_suffix(".supabase.co")).is_some_and(|project| projects.iter().any(|p| p == project));
    let local = demo.is_some_and(|d| url.scheme() == d.scheme() && url.host_str() == d.host_str() && url.port_or_known_default() == d.port_or_known_default());
    (flints || local).then_some(url)
}

fn id_ok(id: &str) -> bool {
    (1..=64).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// Plain text clipped to `max` characters ("" when it isn't text).
fn clipped(raw: &Value, max: usize) -> String {
    plain(raw, 100_000).map(|t| t.chars().take(max).collect::<String>().trim_end().to_owned()).unwrap_or_default()
}

fn time_ms(raw: &Value) -> Option<Option<i64>> {
    match raw {
        Value::Null => Some(None),
        Value::String(s) if s.len() <= 40 => crate::store::rfc3339_secs(s).map(|t| Some(t * 1000)),
        _ => None,
    }
}

fn slide(raw: &Value, projects: &[String], demo: Option<&Url>) -> Option<Slide> {
    let id = raw["id"].as_str().filter(|id| id_ok(id))?.to_owned();
    let image = image_url(raw["image_url"].as_str()?, projects, demo)?;
    let size = |v: &Value| v.as_i64().filter(|n| (16..=8192).contains(n));
    let (width, height) = (size(&raw["width"])?, size(&raw["height"])?);
    if !(1.6..=1.9).contains(&(width as f64 / height as f64)) {
        return None;
    }
    // A schedule Flint can't read could show a slide outside its time: it isn't shown.
    let (starts_at, ends_at) = (time_ms(&raw["starts_at"])?, time_ms(&raw["ends_at"])?);
    let title = clipped(&raw["title"], 80);
    Some(Slide {
        id,
        title: title.clone(),
        // The Store falls back to the title; so does Flint.
        alt: Some(clipped(&raw["alt"], 200)).filter(|a| !a.is_empty()).unwrap_or(title),
        link: raw["link_url"].as_str().and_then(|u| safe_link(u, LinkKind::Url)),
        width,
        height,
        weight: raw["weight"].as_i64().unwrap_or(0),
        starts_at,
        ends_at,
        image,
    })
}

/// The Store's answer, checked (see the module notes); None when it isn't the contract's shape (no `slides` list), which
/// means no slides. Slides keep the Store's order; one named twice counts once.
pub fn parse(body: &Value, projects: &[String], demo: Option<&Url>) -> Option<Showcase> {
    let listed = body["slides"].as_array()?;
    let rotate_ms = body["rotate_ms"].as_u64().map_or(DEFAULT_ROTATE_MS, |ms| ms.clamp(3000, 30_000));
    let mut seen = HashSet::new();
    let slides = listed
        .iter()
        .take(MAX_LISTED)
        .filter_map(|raw| slide(raw, projects, demo))
        .filter(|s| seen.insert(s.id.clone()))
        .collect();
    Some(Showcase { rotate_ms, slides })
}

/// The `showcase` answer at `now_ms`: the slides open now, at most [`MAX_SLIDES`], in the Store's order.
pub fn reply(showcase: &Showcase, now_ms: i64) -> Value {
    let open = |s: &&Slide| s.starts_at.is_none_or(|t| t <= now_ms) && s.ends_at.is_none_or(|t| now_ms < t);
    let slides: Vec<Value> = showcase
        .slides
        .iter()
        .filter(open)
        .take(MAX_SLIDES)
        .map(|s| json!({ "id": s.id, "title": s.title, "alt": s.alt, "link": s.link, "width": s.width, "height": s.height, "weight": s.weight, "endsAt": s.ends_at }))
        .collect();
    json!({ "rotateMs": showcase.rotate_ms, "slides": slides })
}

/// A downloaded picture as the game gets it: a PNG or JPEG as it is, a WebP as a PNG fitting 960 × 540. None for
/// anything else.
pub fn for_game(bytes: Vec<u8>) -> Option<(&'static str, Vec<u8>)> {
    match media::content_type(&bytes)? {
        "image/png" => Some(("png", bytes)),
        "image/jpeg" => Some(("jpeg", bytes)),
        _ => webp_to_png(&bytes, ART_MAX_W, ART_MAX_H).map(|png| ("png", png)),
    }
}

// ---------- asking the Store ----------

fn demo(state: &AppState) -> Option<Url> {
    #[cfg(test)]
    {
        if let Some(url) = state.store.showcase.demo.lock().unwrap().clone() {
            return Some(url);
        }
    }
    let _ = state;
    rules::allow_loopback().then(|| Url::parse(DEMO).expect("valid"))
}

/// `max-age` from a Cache-Control header, within 30 s to 10 min (60 s when not said).
fn max_age(headers: &reqwest::header::HeaderMap) -> Duration {
    headers
        .get(CACHE_CONTROL)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(',').find_map(|d| d.trim().strip_prefix("max-age=")?.parse::<u64>().ok()))
        .map_or(DEFAULT_MAX_AGE, |s| Duration::from_secs(s.clamp(30, 600)))
}

fn known(state: &AppState) -> Showcase {
    state.store.showcase.known.lock().unwrap().as_ref().map_or_else(Showcase::none, |k| k.showcase.clone())
}

fn recently_asked(state: &AppState) -> bool {
    state.store.showcase.last.lock().unwrap().is_some_and(|(at, wait)| at.elapsed() < wait)
}

fn record(state: &AppState, wait: Duration) {
    *state.store.showcase.last.lock().unwrap() = Some((Instant::now(), wait));
}

/// A new good answer: kept, and the pictures of slides no longer listed let go.
fn keep(state: &AppState, etag: Option<String>, showcase: Showcase) -> Showcase {
    let listed: HashSet<String> = showcase.slides.iter().map(|s| s.image.to_string()).collect();
    state.store.showcase.art.lock().unwrap().retain(|url, _| listed.contains(url));
    *state.store.showcase.known.lock().unwrap() = Some(Known { etag, showcase: showcase.clone() });
    showcase
}

/// Tests: the next [`current`] asks the Store (as its max-age running out would).
#[cfg(test)]
pub(crate) fn age(state: &AppState) {
    *state.store.showcase.last.lock().unwrap() = None;
}

/// The showcase now: the last good answer while the Store was asked within its max-age, else the Store's answer (or, when
/// it can't be had, the last good one). Not filtered by time: [`reply`] does that.
pub async fn current(state: &AppState) -> Showcase {
    if recently_asked(state) {
        return known(state);
    }
    // One request at a time; whoever waited finds the answer the first one got.
    let _one = state.store.showcase.fetch.lock().await;
    if recently_asked(state) {
        return known(state);
    }
    let etag = state.store.showcase.known.lock().unwrap().as_ref().and_then(|k| k.etag.clone());
    let endpoints = &state.store.endpoints;
    let mut request = crate::store::with_access(state.http.get(endpoints.api(PATH)), endpoints).timeout(TIMEOUT).header(ACCEPT, "application/json");
    if let Some(etag) = &etag {
        request = request.header(IF_NONE_MATCH, etag);
    }
    let Ok(response) = request.send().await else {
        record(state, FAILURE_PAUSE);
        return known(state);
    };
    let status = response.status().as_u16();
    let headers = response.headers().clone();
    let new_etag = headers.get(ETAG).and_then(|v| v.to_str().ok()).filter(|v| v.len() <= 200 && v.is_ascii()).map(str::to_owned);
    match status {
        304 if etag.is_some() => {
            record(state, max_age(&headers));
            let mut kept = state.store.showcase.known.lock().unwrap();
            if let (Some(k), Some(tag)) = (kept.as_mut(), new_etag) {
                k.etag = Some(tag);
            }
            let shown = kept.as_ref().map_or_else(Showcase::none, |k| k.showcase.clone());
            shown
        }
        200 => {
            record(state, max_age(&headers));
            let body = crate::store::read_capped(response, MAX_BODY).await.ok().and_then(|b| serde_json::from_slice::<Value>(&b).ok());
            match body.and_then(|b| parse(&b, &crate::partners::media_projects(), demo(state).as_ref())) {
                Some(showcase) => keep(state, new_etag, showcase),
                // Not the contract's shape: no slides (and asked plainly next time).
                None => keep(state, None, Showcase::none()),
            }
        }
        // This Store has no showcase (or not for this placement): no slides, never an error.
        400 | 404 | 405 | 410 | 501 => {
            record(state, max_age(&headers));
            keep(state, None, Showcase::none())
        }
        // The showcase is off for now: no slides, asked again soon.
        503 => {
            record(state, FAILURE_PAUSE);
            keep(state, None, Showcase::none())
        }
        429 => {
            let wait = headers.get(RETRY_AFTER).and_then(|v| v.to_str().ok()).and_then(|v| v.trim().parse::<u64>().ok()).unwrap_or(60).clamp(1, 600);
            record(state, Duration::from_secs(wait));
            known(state)
        }
        _ => {
            record(state, FAILURE_PAUSE);
            known(state)
        }
    }
}

/// The picture of slide `id` in the last good list, for the game: `{"type": "png"|"jpeg", "data": base64}`.
pub async fn art(state: &AppState, id: &str) -> CmdResult<Value> {
    let image = known(state).slide(id).map(|s| s.image.clone()).ok_or("not found")?;
    let key = image.to_string();
    let cached = state.store.showcase.art.lock().unwrap().get(&key).cloned();
    let picture = match cached {
        Some(picture) => picture,
        None => {
            let response = image_client().get(image).timeout(IMAGE_TIMEOUT).send().await.map_err(|_| COULDNT.to_owned())?;
            // Redirects aren't followed: a picture that moved elsewhere isn't shown.
            if !response.status().is_success() {
                return Err(COULDNT.into());
            }
            let bytes = crate::store::read_capped(response, MAX_IMAGE).await.map_err(|_| COULDNT.to_owned())?;
            let (kind, bytes) = tokio::task::spawn_blocking(move || for_game(bytes)).await.ok().flatten().ok_or("unsupported picture")?;
            let picture = Picture { kind, bytes };
            // Kept only while its slide is still listed.
            if known(state).slides.iter().any(|s| s.image.as_str() == key) {
                state.store.showcase.art.lock().unwrap().insert(key, picture.clone());
            }
            picture
        }
    };
    Ok(json!({ "type": picture.kind, "data": base64::engine::general_purpose::STANDARD.encode(&picture.bytes) }))
}

/// A client that never follows a redirect (the picture must come from the address the slide names), with the app's TLS
/// (reqwest 0.13 here carries no TLS provider of its own).
fn image_client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .tls_backend_preconfigured(crate::core::tls_config())
            .connect_timeout(Duration::from_secs(10))
            .user_agent(crate::core::USER_AGENT)
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .expect("HTTP client configuration is valid")
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;
    use crate::partners::tests::{state_for, FakeStore, Reply};

    const PROJECT: &str = "vkqzlqjbpvxrskwpvijt";
    const NOW: i64 = 1_800_000_000_000;

    fn projects() -> Vec<String> {
        vec![PROJECT.to_owned()]
    }

    fn picture(name: &str) -> String {
        format!("https://{PROJECT}.supabase.co/storage/v1/object/public/showcase-media/slides/{name}")
    }

    fn slide_json(id: &str, image: &str) -> Value {
        json!({ "id": id, "image_url": image, "width": 1920, "height": 1080, "title": "Season 4 is live", "link_url": "https://emberfall.example/season",
                "alt": "Emberfall's season banner", "starts_at": null, "ends_at": null, "weight": 10 })
    }

    fn ids(showcase: &Showcase) -> Vec<&str> {
        showcase.slides.iter().map(|s| s.id.as_str()).collect()
    }

    #[test]
    fn pictures_come_only_from_flints_showcase_bucket() {
        let p = projects();
        assert!(image_url(&picture("a.png"), &p, None).is_some());
        assert!(image_url(&picture("2026/winter-banner_v2.webp"), &p, None).is_some());
        for bad in [
            "https://mxgeolovubvnjjhwihsi.supabase.co/storage/v1/object/public/showcase-media/a.png".to_owned(),
            format!("https://{PROJECT}.supabase.co/storage/v1/object/public/cosmetic-assets/a.png"),
            format!("https://{PROJECT}.supabase.co/storage/v1/object/public/showcase-media/"),
            format!("https://{PROJECT}.supabase.co/storage/v1/object/public/showcase-media/../cosmetic-assets/a.png"),
            format!("https://{PROJECT}.supabase.co/storage/v1/object/public/showcase-media/%2e%2e/a.png"),
            format!("https://{PROJECT}.supabase.co/storage/v1/object/public/showcase-media//a.png"),
            format!("{}?token=1", picture("a.png")),
            format!("{}#x", picture("a.png")),
            format!("http://{PROJECT}.supabase.co/storage/v1/object/public/showcase-media/a.png"),
            format!("https://{PROJECT}.supabase.co:8443/storage/v1/object/public/showcase-media/a.png"),
            format!("https://user@{PROJECT}.supabase.co/storage/v1/object/public/showcase-media/a.png"),
            "https://imgur.example/storage/v1/object/public/showcase-media/a.png".to_owned(),
            "http://127.0.0.1:54321/storage/v1/object/public/showcase-media/a.png".to_owned(),
            format!("{} ", picture("a.png")),
        ] {
            assert_eq!(image_url(&bad, &p, None), None, "{bad}");
        }
        // The local demo, only when allowed (development builds), and only at its own origin.
        let demo = Url::parse(DEMO).unwrap();
        assert!(image_url("http://127.0.0.1:54321/storage/v1/object/public/showcase-media/a.png", &p, Some(&demo)).is_some());
        assert_eq!(image_url("http://127.0.0.1:54322/storage/v1/object/public/showcase-media/a.png", &p, Some(&demo)), None);
    }

    #[test]
    fn slides_are_checked_one_by_one_and_keep_the_stores_order() {
        let mut long = slide_json("long", &picture("l.png"));
        long["title"] = json!(format!("  §6Big\u{202e} news\n{}", "x".repeat(200)));
        long["link_url"] = json!("http://insecure.example/");
        let mut square = slide_json("square", &picture("s.png"));
        square["height"] = json!(1920);
        let mut tiny = slide_json("tiny", &picture("t.png"));
        tiny["width"] = json!(15);
        let mut odd_time = slide_json("oddtime", &picture("o.png"));
        odd_time["ends_at"] = json!("next tuesday");
        let mut no_title = slide_json("notitle", &picture("n.png"));
        no_title["title"] = Value::Null;
        long["alt"] = json!("");
        no_title["link_url"] = Value::Null;
        let body = json!({
            "rotate_ms": 8000,
            "slides": [
                slide_json("b-second", &picture("b.png")),
                slide_json("a_first", &picture("a.webp")),
                long, square, tiny, odd_time, no_title,
                slide_json("bad id!", &picture("x.png")),
                slide_json("elsewhere", "https://imgur.example/x.png"),
                slide_json("b-second", &picture("again.png")),
            ]
        });
        let shown = parse(&body, &projects(), None).unwrap();
        assert_eq!(ids(&shown), ["b-second", "a_first", "long", "notitle"]);
        assert_eq!(shown.rotate_ms, 8000);
        let long = &shown.slides[2];
        assert_eq!(long.title.chars().count(), 80);
        assert!(long.title.starts_with("6Big news xxx"), "{}", long.title);
        assert_eq!(long.link, None, "an http link: the slide shows without one");
        assert_eq!(long.alt, long.title, "no alt: the title stands in");
        assert_eq!((shown.slides[3].title.as_str(), shown.slides[3].link.as_ref()), ("", None));
        assert_eq!(shown.slides[0].alt, "Emberfall's season banner");
        assert_eq!(shown.slides[0].link.as_deref(), Some("https://emberfall.example/season"));
        assert_eq!(shown.slides[0].image.as_str(), picture("b.png"), "the first entry of an id counts");

        assert_eq!(parse(&json!({ "slides": [], "rotate_ms": 100 }), &projects(), None).unwrap().rotate_ms, 3000);
        assert_eq!(parse(&json!({ "slides": [], "rotate_ms": 999_999 }), &projects(), None).unwrap().rotate_ms, 30_000);
        assert_eq!(parse(&json!({ "slides": [] }), &projects(), None).unwrap().rotate_ms, DEFAULT_ROTATE_MS);
        assert_eq!(parse(&json!({ "slides": [], "rotate_ms": "fast" }), &projects(), None).unwrap().rotate_ms, DEFAULT_ROTATE_MS);
        assert_eq!(parse(&json!({ "error": "unknown placement" }), &projects(), None), None);
        assert_eq!(parse(&json!({ "slides": {} }), &projects(), None), None);
    }

    #[test]
    fn only_slides_open_now_are_answered_at_most_twelve() {
        let mut slides = Vec::new();
        for i in 0..16 {
            slides.push(slide_json(&format!("s{i}"), &picture(&format!("{i}.png"))));
        }
        slides[0]["ends_at"] = json!("2020-01-01T00:00:00Z");
        slides[1]["starts_at"] = json!("2099-01-01T00:00:00Z");
        slides[2]["ends_at"] = json!("2099-01-01T00:00:00.000Z");
        let showcase = parse(&json!({ "slides": slides }), &projects(), None).unwrap();
        let answer = reply(&showcase, NOW);
        let listed: Vec<&str> = answer["slides"].as_array().unwrap().iter().map(|s| s["id"].as_str().unwrap()).collect();
        assert_eq!(listed, ["s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "s12", "s13"]);
        assert_eq!(answer["rotateMs"], DEFAULT_ROTATE_MS);
        let first = &answer["slides"][0];
        assert_eq!(first["endsAt"], 4_070_908_800_000_i64);
        assert_eq!((first["width"].clone(), first["height"].clone(), first["weight"].clone()), (json!(1920), json!(1080), json!(10)));
        assert_eq!(first["alt"], "Emberfall's season banner");
        assert!(first.get("image_url").is_none() && first.get("image").is_none(), "the address stays in the launcher");
        assert_eq!(answer["slides"][1]["endsAt"], Value::Null);
    }

    fn webp_bytes() -> Vec<u8> {
        let pixels: Vec<u8> = (0..32 * 18).flat_map(|i| [(i % 255) as u8, 40, 200, 255]).collect();
        let mut out = Vec::new();
        image_webp::WebPEncoder::new(&mut out).encode(&pixels, 32, 18, image_webp::ColorType::Rgba8).unwrap();
        out
    }

    #[test]
    fn pictures_reach_the_game_as_png_or_jpeg() {
        let png = b"\x89PNG\r\n\x1a\nrest".to_vec();
        assert_eq!(for_game(png.clone()), Some(("png", png)));
        let jpeg = vec![0xff, 0xd8, 0xff, 0xe0, 1, 2];
        assert_eq!(for_game(jpeg.clone()), Some(("jpeg", jpeg)));
        let (kind, converted) = for_game(webp_bytes()).unwrap();
        assert_eq!(kind, "png");
        assert!(converted.starts_with(b"\x89PNG\r\n\x1a\n"));
        assert_eq!(for_game(b"GIF89a....".to_vec()), None);
        assert_eq!(for_game(b"<html>".to_vec()), None);
    }

    #[tokio::test]
    async fn the_store_is_asked_with_its_etag_and_a_store_without_the_route_shows_nothing() {
        let fake = FakeStore::start(|_, _| Reply::json(404, &json!({ "error": "not found" }))).await;
        let dir = TestDir::new();
        let state = state_for(&dir, &fake.url);
        let demo_base = fake.url.clone();
        *state.store.showcase.demo.lock().unwrap() = Some(demo_base.clone());
        let at = |name: &str| format!("{}storage/v1/object/public/showcase-media/{name}", demo_base);

        // Today's Store: no route, no slides, no error; asked again only after its max-age.
        assert_eq!(current(&state).await, Showcase::none());
        assert_eq!(fake.requests(), 1);
        current(&state).await;
        assert_eq!(fake.requests(), 1, "within max-age: nothing sent");

        // The route, with a slide whose picture is the demo's.
        let body = json!({ "slides": [slide_json("winter", &at("winter.png")), slide_json("ended", &at("ended.png"))], "rotate_ms": 7000 });
        let first = body.clone();
        fake.answer(move |path, headers| {
            assert_eq!(path, PATH);
            if headers.get("if-none-match").map(String::as_str) == Some("\"s1\"") {
                return Reply { status: 304, headers: vec![("ETag", "\"s1\"".into()), ("Cache-Control", "max-age=60".into())], body: Vec::new(), delay: Duration::ZERO };
            }
            Reply::json(200, &first).header("ETag", "\"s1\"").header("Cache-Control", "public, max-age=60")
        });
        age(&state);
        let shown = current(&state).await;
        assert_eq!((ids(&shown), shown.rotate_ms), (vec!["winter", "ended"], 7000));
        assert_eq!(fake.last_header("if-none-match"), None);
        assert_eq!(fake.last_header("authorization"), None, "nothing about the player is sent");
        age(&state);
        assert_eq!(current(&state).await, shown, "a 304 keeps the last answer");
        assert_eq!(fake.last_header("if-none-match").as_deref(), Some("\"s1\""));

        // The Store out of reach: the last good answer stands.
        fake.answer(|_, _| Reply::json(500, &json!({ "error": "internal" })));
        age(&state);
        assert_eq!(ids(&current(&state).await), ["winter", "ended"]);
        fake.answer(|_, _| Reply::json(502, &json!({})));
        age(&state);
        assert_eq!(ids(&current(&state).await), ["winter", "ended"]);

        // Pictures: only a listed slide's, fetched once and kept.
        let png = b"\x89PNG\r\n\x1a\nwinter-pixels".to_vec();
        let served = png.clone();
        fake.answer(move |path, _| {
            if path.ends_with("winter.png") {
                return Reply { status: 200, headers: vec![("Content-Type", "image/png".into())], body: served.clone(), delay: Duration::ZERO };
            }
            if path.ends_with("ended.png") {
                return Reply { status: 302, headers: vec![("Location", "https://elsewhere.example/x.png".into())], body: Vec::new(), delay: Duration::ZERO };
            }
            Reply::json(503, &json!({}))
        });
        let art_answer = art(&state, "winter").await.unwrap();
        assert_eq!(art_answer["type"], "png");
        assert_eq!(base64::engine::general_purpose::STANDARD.decode(art_answer["data"].as_str().unwrap()).unwrap(), png);
        let before = fake.requests();
        art(&state, "winter").await.unwrap();
        assert_eq!(fake.requests(), before, "kept in memory");
        assert_eq!(art(&state, "ended").await.unwrap_err(), COULDNT, "a redirect isn't followed");
        assert_eq!(art(&state, "nobody").await.unwrap_err(), "not found");

        // A body that isn't the contract's shape: no slides, and their pictures let go.
        fake.answer(|_, _| Reply { status: 200, headers: Vec::new(), body: b"<html>".to_vec(), delay: Duration::ZERO });
        age(&state);
        assert_eq!(current(&state).await, Showcase::none());
        assert!(state.store.showcase.art.lock().unwrap().is_empty());
        assert_eq!(art(&state, "winter").await.unwrap_err(), "not found");

        // A placement the Store doesn't know, or the showcase switched off for now: no slides, never an error.
        let back = json!({ "slides": [slide_json("back", &at("back.png"))] });
        for (status, error) in [(400, "invalid_placement"), (503, "unavailable")] {
            let listed = back.clone();
            fake.answer(move |_, _| Reply::json(200, &listed));
            age(&state);
            assert_eq!(ids(&current(&state).await), ["back"]);
            fake.answer(move |_, _| Reply::json(status, &json!({ "error": error })));
            age(&state);
            assert_eq!(current(&state).await, Showcase::none(), "{status}");
        }
    }
}
