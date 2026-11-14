//! The showcase feed against a small fake Store (200, ETag and 304, max-age, 400, 404, 429, 5xx, offline, oversized and
//! wrong answers), every slide check, slides whose end passes between refreshes, and the picture checks, with the
//! fixtures the browser preview shows too (src/lib/showcaseFixtures.json).
use super::image::{check, dimensions, download, Rejected};
use super::slide::{self, Format};
use super::*;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use url::Url;

const FIXTURES: &str = include_str!("../../../src/lib/showcaseFixtures.json");
const PROJECT: &str = "vkqzlqjbpvxrskwpvijt";
const ID: &str = "6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b";

fn fixture(name: &str) -> Value {
    serde_json::from_str::<Value>(FIXTURES).unwrap()["sets"][name].clone()
}

fn projects() -> Vec<String> {
    vec![PROJECT.to_owned()]
}

/// The fixtures' "now" (2026-11-10T12:00:00Z).
fn fixture_now() -> i64 {
    crate::store::rfc3339_secs(serde_json::from_str::<Value>(FIXTURES).unwrap()["now"].as_str().unwrap()).unwrap()
}

/// A slide that passes every check; tests change one field at a time.
fn good() -> Value {
    fixture("slides")["slides"][0].clone()
}

fn with(field: &str, value: Value) -> Value {
    let mut raw = good();
    raw[field] = value;
    raw
}

fn accepted(raw: &Value) -> Option<slide::Slide> {
    slide::slide(raw, &projects(), fixture_now())
}

// ---------- slides ----------

#[test]
fn the_fixture_feed_is_read_in_order_with_every_field() {
    let feed = slide::parse(&fixture("slides"), &projects(), fixture_now()).unwrap();
    assert_eq!(feed.rotate_ms, 6000);
    assert_eq!(feed.slides.len(), 3);
    let first = &feed.slides[0];
    assert_eq!(first.id, ID);
    assert_eq!(first.title, "Emberfall SMP: season 4 opens Friday");
    assert_eq!((first.width, first.height, first.weight, first.format), (2400, 800, 3, Format::Webp));
    assert_eq!(first.link.as_ref().map(Url::as_str), Some("https://emberfall.example.com/season-4"));
    assert_eq!(first.sha256.len(), 64);
    // An empty alt is the title (as the Store answers it); no link means not clickable.
    assert_eq!(feed.slides[1].alt, "New in the Flint Store: Aurora capes");
    assert_eq!(feed.slides[1].format, Format::Png);
    assert!(feed.slides[2].link.is_none());
    assert_eq!(feed.slides[2].format, Format::Jpeg);
    let view = view(first);
    assert_eq!(view.link_host.as_deref(), Some("emberfall.example.com"));
    assert_eq!(view.ends_at, Some(crate::store::rfc3339_secs("2026-11-24T00:00:00.000Z").unwrap() as u64 * 1000));
    assert_eq!(view.image_key, first.sha256);
}

#[test]
fn an_empty_feed_has_no_slides_and_a_non_feed_is_refused() {
    assert_eq!(slide::parse(&fixture("none"), &projects(), fixture_now()).unwrap().slides, vec![]);
    for body in [json!({}), json!({"slides": {}}), json!({"error": "invalid_placement"}), json!({"slides": [], "error": "x"}), json!([]), json!("slides")] {
        assert!(slide::parse(&body, &projects(), fixture_now()).is_none(), "{body}");
    }
}

#[test]
fn picture_addresses_must_be_this_slides_showcase_media_file_in_an_allowed_project() {
    assert!(accepted(&good()).is_some());
    let base = format!("https://{PROJECT}.supabase.co/storage/v1/object/public/showcase-media/slides/{ID}/");
    let sha = "1d3c5e7f9a0b2c4d6e8f0a1b3c5d7e9f1a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d";
    for ext in ["png", "jpg", "webp"] {
        assert!(accepted(&with("image_url", json!(format!("{base}{sha}.{ext}")))).is_some(), "{ext}");
    }
    let bad = [
        format!("http://{PROJECT}.supabase.co/storage/v1/object/public/showcase-media/slides/{ID}/{sha}.png"),
        format!("https://mxgeolovubvnjjhwihsi.supabase.co/storage/v1/object/public/showcase-media/slides/{ID}/{sha}.png"),
        format!("https://{PROJECT}.supabase.co.evil.example/storage/v1/object/public/showcase-media/slides/{ID}/{sha}.png"),
        format!("https://evil.example/storage/v1/object/public/showcase-media/slides/{ID}/{sha}.png"),
        format!("https://{PROJECT}.supabase.co/storage/v1/object/public/partner-media/slides/{ID}/{sha}.png"),
        format!("https://{PROJECT}.supabase.co/storage/v1/object/public/showcase-media/slides/0a9b8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d/{sha}.png"),
        format!("https://{PROJECT}.supabase.co/storage/v1/object/public/showcase-media/slides/{ID}/../{ID}/{sha}.png"),
        format!("{base}{sha}.gif"),
        format!("{base}{sha}.svg"),
        format!("{base}{sha}.png?x=1"),
        format!("{base}{sha}.png#x"),
        format!("{base}{}.png", sha.to_uppercase()),
        format!("{base}{}.png", &sha[..63]),
        format!("https://{PROJECT}.supabase.co:8443/storage/v1/object/public/showcase-media/slides/{ID}/{sha}.png"),
        format!("https://user@{PROJECT}.supabase.co/storage/v1/object/public/showcase-media/slides/{ID}/{sha}.png"),
        "javascript:alert(1)".to_owned(),
        String::new(),
    ];
    for url in bad {
        assert!(accepted(&with("image_url", json!(url))).is_none(), "{url}");
    }
    assert!(accepted(&with("image_url", Value::Null)).is_none());
    // A staging or development build's own project, when the build names it.
    let staging = vec!["mxgeolovubvnjjhwihsi".to_owned()];
    let raw = with("image_url", json!(format!("https://mxgeolovubvnjjhwihsi.supabase.co/storage/v1/object/public/showcase-media/slides/{ID}/{sha}.png")));
    assert!(slide::slide(&raw, &staging, fixture_now()).is_some());
    assert!(slide::slide(&good(), &staging, fixture_now()).is_none());
}

#[test]
fn sizes_must_be_the_launchers_3_to_1_shape() {
    let size = |w: Value, h: Value| {
        let mut raw = good();
        raw["width"] = w;
        raw["height"] = h;
        accepted(&raw).is_some()
    };
    assert!(size(json!(1200), json!(400)));
    assert!(size(json!(2400), json!(800)));
    assert!(size(json!(3000), json!(1000)));
    // Within 2 %: 1224×400 (2 % wide) passes, 1225×400 doesn't.
    assert!(size(json!(1224), json!(400)));
    assert!(!size(json!(1225), json!(400)));
    assert!(!size(json!(1920), json!(1080)), "16:9 is the game menu's");
    assert!(!size(json!(900), json!(300)), "too small");
    assert!(!size(json!(4200), json!(1400)), "over 4096 a side");
    assert!(!size(json!(0), json!(0)));
    assert!(!size(json!(-1200), json!(400)));
    assert!(!size(json!(1200.5), json!(400)));
    assert!(!size(json!("1200"), json!("400")));
    assert!(!size(Value::Null, json!(400)));
}

#[test]
fn text_must_be_plain_and_short() {
    assert!(accepted(&with("title", json!("  Spaced  "))).is_some_and(|s| s.title == "Spaced"));
    for title in [json!(""), json!("   "), json!("<b>Bold</b>"), json!("Line\nbreak"), json!("x".repeat(61)), json!(42), Value::Null] {
        assert!(accepted(&with("title", title.clone())).is_none(), "{title}");
    }
    assert!(accepted(&with("title", json!("é".repeat(60)))).is_some(), "60 characters, not bytes");
    assert!(accepted(&with("alt", json!("a".repeat(161)))).is_none());
    assert!(accepted(&with("alt", json!("<img>"))).is_none());
    assert_eq!(accepted(&with("alt", Value::Null)).unwrap().alt, "Emberfall SMP: season 4 opens Friday");
    assert!(accepted(&with("id", json!("not-a-uuid"))).is_none());
    assert!(accepted(&with("id", json!(ID.to_uppercase()))).is_none());
}

#[test]
fn links_must_be_https_on_a_public_host_or_the_slide_isnt_clickable() {
    let link = |l: Value| accepted(&with("link_url", l)).expect("the slide itself stays").link.map(|u| u.to_string());
    assert_eq!(link(json!("https://example.com/promo?ref=flint")).as_deref(), Some("https://example.com/promo?ref=flint"));
    assert_eq!(link(Value::Null), None);
    for bad in [
        "http://example.com/",
        "javascript:alert(1)",
        "file:///C:/Windows",
        "flint://x",
        "https://user:pass@example.com/",
        "https://127.0.0.1/",
        "https://[::1]/",
        "https://localhost/",
        "https://printer.local/",
        "https://intranet/",
        "https://example.com/a b",
        "https://example.com/\"x",
        "",
    ] {
        assert_eq!(link(json!(bad)), None, "{bad}");
    }
    assert_eq!(link(json!(format!("https://example.com/{}", "a".repeat(300)))), None);
}

#[test]
fn schedules_are_applied_and_unreadable_dates_refused() {
    let now = fixture_now();
    assert!(accepted(&with("ends_at", json!("2026-11-10T11:59:59Z"))).is_none(), "ended");
    assert!(accepted(&with("ends_at", json!("2026-11-10T12:00:00Z"))).is_none(), "ends now");
    assert!(accepted(&with("starts_at", json!("2026-11-11T00:00:00Z"))).is_none(), "starts tomorrow");
    assert!(accepted(&with("starts_at", json!("2026-11-10T12:01:00Z"))).is_some(), "a minute ahead of this clock still shows");
    assert!(accepted(&with("starts_at", json!("2026-11-10T12:00:00+01:00"))).is_some());
    for bad in [json!("tomorrow"), json!(1731240000), json!("2026-13-01T00:00:00Z"), json!({})] {
        assert!(accepted(&with("ends_at", bad.clone())).is_none(), "{bad}");
    }
    let raw = with("ends_at", json!("2026-11-10T12:20:00Z"));
    let slide = slide::slide(&raw, &projects(), now).unwrap();
    assert!(slide.live(now + 1199));
    assert!(!slide.live(now + 1200));
}

#[test]
fn weights_rotation_and_the_slide_cap_are_kept_sane() {
    assert_eq!(accepted(&with("weight", json!(0))).unwrap().weight, 1);
    assert_eq!(accepted(&with("weight", json!(101))).unwrap().weight, 1);
    assert_eq!(accepted(&with("weight", json!(100))).unwrap().weight, 100);
    assert_eq!(accepted(&with("weight", json!("5"))).unwrap().weight, 1);
    for (given, kept) in [(json!(6000), 6000), (json!(100), 3000), (json!(0), 3000), (json!(999_999), 30_000), (json!(-5), 6000), (json!("6000"), 6000), (Value::Null, 6000)] {
        assert_eq!(slide::rotate_ms(&given), kept, "{given}");
    }
    let many: Vec<Value> = (0..20)
        .map(|i| {
            let id = format!("6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a{i:02x}");
            let sha = format!("{:064x}", i + 1);
            json!({"id": id, "image_url": format!("https://{PROJECT}.supabase.co/storage/v1/object/public/showcase-media/slides/{id}/{sha}.png"),
                   "width": 1200, "height": 400, "title": format!("Slide {i}"), "link_url": null, "alt": "", "starts_at": null, "ends_at": null, "weight": 1})
        })
        .collect();
    let feed = slide::parse(&json!({"slides": many, "rotate_ms": 6000}), &projects(), fixture_now()).unwrap();
    assert_eq!(feed.slides.len(), slide::MAX_SLIDES);
    // The same slide twice is shown once.
    let twice = json!({"slides": [good(), good()], "rotate_ms": 6000});
    assert_eq!(slide::parse(&twice, &projects(), fixture_now()).unwrap().slides.len(), 1);
    // Bad slides are left out without losing the good ones.
    let mixed = json!({"slides": [with("width", json!(1920)), good(), "junk", null], "rotate_ms": 6000});
    assert_eq!(slide::parse(&mixed, &projects(), fixture_now()).unwrap().slides.len(), 1);
}

// ---------- pictures ----------

fn png(w: u32, h: u32) -> Vec<u8> {
    let mut b = b"\x89PNG\r\n\x1a\n\0\0\0\x0dIHDR".to_vec();
    b.extend_from_slice(&w.to_be_bytes());
    b.extend_from_slice(&h.to_be_bytes());
    b.extend_from_slice(&[8, 6, 0, 0, 0, 0, 0, 0, 0]);
    b
}

fn jpeg(w: u16, h: u16) -> Vec<u8> {
    let mut b = vec![0xff, 0xd8, 0xff, 0xe0, 0, 16];
    b.extend_from_slice(b"JFIF\0\x01\x01\0\0\x01\0\x01\0\0");
    b.extend_from_slice(&[0xff, 0xdb, 0, 3, 0]);
    b.extend_from_slice(&[0xff, 0xc4, 0, 3, 0]);
    b.extend_from_slice(&[0xff, 0xc2, 0, 17, 8]);
    b.extend_from_slice(&h.to_be_bytes());
    b.extend_from_slice(&w.to_be_bytes());
    b.extend_from_slice(&[3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1]);
    b.extend_from_slice(&[0xff, 0xd9]);
    b
}

fn riff(chunk: &[u8; 4], data: &[u8]) -> Vec<u8> {
    let mut b = b"RIFF".to_vec();
    b.extend_from_slice(&((data.len() + 12) as u32).to_le_bytes());
    b.extend_from_slice(b"WEBP");
    b.extend_from_slice(chunk);
    b.extend_from_slice(&(data.len() as u32).to_le_bytes());
    b.extend_from_slice(data);
    b
}

fn webp_lossy(w: u16, h: u16) -> Vec<u8> {
    let mut d = vec![0x10, 0x02, 0x00, 0x9d, 0x01, 0x2a];
    d.extend_from_slice(&w.to_le_bytes());
    d.extend_from_slice(&h.to_le_bytes());
    d.extend_from_slice(&[0; 8]);
    riff(b"VP8 ", &d)
}

fn webp_lossless(w: u32, h: u32) -> Vec<u8> {
    let bits = (w - 1) | (h - 1) << 14;
    let mut d = vec![0x2f];
    d.extend_from_slice(&bits.to_le_bytes());
    d.extend_from_slice(&[0; 4]);
    riff(b"VP8L", &d)
}

fn webp_extended(w: u32, h: u32) -> Vec<u8> {
    let mut d = vec![0x10, 0, 0, 0];
    d.extend_from_slice(&(w - 1).to_le_bytes()[..3]);
    d.extend_from_slice(&(h - 1).to_le_bytes()[..3]);
    riff(b"VP8X", &d)
}

#[test]
fn picture_sizes_are_read_from_their_own_headers() {
    assert_eq!(dimensions(&png(2400, 800)), Some((2400, 800)));
    assert_eq!(dimensions(&jpeg(1500, 500)), Some((1500, 500)));
    assert_eq!(dimensions(&webp_lossy(1200, 400)), Some((1200, 400)));
    assert_eq!(dimensions(&webp_lossless(3000, 1000)), Some((3000, 1000)));
    assert_eq!(dimensions(&webp_extended(4096, 1365)), Some((4096, 1365)));
    assert_eq!(dimensions(b"GIF89a\x01\0\x01\0"), None);
    assert_eq!(dimensions(&png(2400, 800)[..20]), None, "cut short");
    assert_eq!(dimensions(&[0xff, 0xd8, 0xff, 0xda, 0, 2]), None, "no frame before the scan");
}

fn slide_for(bytes: &[u8], format: Format) -> slide::Slide {
    let mut s = accepted(&good()).unwrap();
    s.sha256 = hex::encode(Sha256::digest(bytes));
    s.format = format;
    s
}

#[test]
fn pictures_must_match_their_checksum_format_and_shape() {
    let picture = png(2400, 800);
    assert_eq!(check(&picture, &slide_for(&picture, Format::Png)), Ok(()));
    let picture = jpeg(1200, 400);
    assert_eq!(check(&picture, &slide_for(&picture, Format::Jpeg)), Ok(()));
    let picture = webp_extended(2400, 800);
    assert_eq!(check(&picture, &slide_for(&picture, Format::Webp)), Ok(()));
    // Another picture than the checksum names.
    let mut other = slide_for(&png(2400, 800), Format::Png);
    other.sha256 = "0".repeat(64);
    assert_eq!(check(&png(2400, 800), &other), Err(Rejected::Checksum));
    // A JPEG named .png.
    let picture = jpeg(1200, 400);
    assert_eq!(check(&picture, &slide_for(&picture, Format::Png)), Err(Rejected::Format));
    // The right bytes, but not the launcher's shape by their own header (whatever the feed said).
    let picture = png(1920, 1080);
    assert_eq!(check(&picture, &slide_for(&picture, Format::Png)), Err(Rejected::Shape));
    let picture = b"<svg xmlns='http://www.w3.org/2000/svg'/>".to_vec();
    assert_eq!(check(&picture, &slide_for(&picture, Format::Png)), Err(Rejected::Format));
}

// ---------- the fake Store ----------

#[derive(Clone)]
struct Reply {
    status: u16,
    headers: Vec<(&'static str, String)>,
    body: Vec<u8>,
}

impl Reply {
    fn json(status: u16, body: &Value) -> Self {
        Self { status, headers: vec![("Content-Type", "application/json".into())], body: serde_json::to_vec(body).unwrap() }
    }
    fn header(mut self, name: &'static str, value: &str) -> Self {
        self.headers.push((name, value.into()));
        self
    }
}

type Seen = Arc<Mutex<Vec<(String, HashMap<String, String>)>>>;
type Handler = Arc<Mutex<Box<dyn FnMut(&str, &HashMap<String, String>) -> Reply + Send>>>;

/// An HTTP server on this computer that records each request's path and headers.
struct FakeStore {
    url: Url,
    seen: Seen,
    handler: Handler,
}

impl FakeStore {
    async fn start(handler: impl FnMut(&str, &HashMap<String, String>) -> Reply + Send + 'static) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = Url::parse(&format!("http://{}/", listener.local_addr().unwrap())).unwrap();
        let seen: Seen = Arc::default();
        let handler: Handler = Arc::new(Mutex::new(Box::new(handler)));
        let (s, h) = (seen.clone(), handler.clone());
        tokio::spawn(async move {
            while let Ok((mut stream, _)) = listener.accept().await {
                let (seen, handler) = (s.clone(), h.clone());
                tokio::spawn(async move {
                    let mut buffer = Vec::new();
                    let mut chunk = [0u8; 4096];
                    while !buffer.windows(4).any(|w| w == b"\r\n\r\n") {
                        match stream.read(&mut chunk).await {
                            Ok(0) | Err(_) => return,
                            Ok(n) => buffer.extend_from_slice(&chunk[..n]),
                        }
                    }
                    let head = String::from_utf8_lossy(&buffer).to_string();
                    let mut lines = head.split("\r\n");
                    let path = lines.next().unwrap_or_default().split(' ').nth(1).unwrap_or_default().to_owned();
                    let headers: HashMap<String, String> = lines.filter_map(|l| l.split_once(':')).map(|(k, v)| (k.trim().to_ascii_lowercase(), v.trim().to_owned())).collect();
                    seen.lock().unwrap().push((path.clone(), headers.clone()));
                    let reply = (handler.lock().unwrap())(&path, &headers);
                    let mut out = format!("HTTP/1.1 {} X\r\nContent-Length: {}\r\nConnection: close\r\n", reply.status, reply.body.len());
                    for (k, v) in &reply.headers {
                        out.push_str(&format!("{k}: {v}\r\n"));
                    }
                    out.push_str("\r\n");
                    let _ = stream.write_all(out.as_bytes()).await;
                    let _ = stream.write_all(&reply.body).await;
                    let _ = stream.shutdown().await;
                });
            }
        });
        Self { url, seen, handler }
    }

    fn requests(&self) -> usize {
        self.seen.lock().unwrap().len()
    }

    fn last(&self) -> (String, HashMap<String, String>) {
        self.seen.lock().unwrap().last().cloned().unwrap()
    }

    fn answer(&self, handler: impl FnMut(&str, &HashMap<String, String>) -> Reply + Send + 'static) {
        *self.handler.lock().unwrap() = Box::new(handler);
    }

    fn endpoints(&self) -> Endpoints {
        Endpoints { store: self.url.clone(), mojang: self.url.clone(), access: None }
    }
}

fn http() -> reqwest::Client {
    crate::core::test_client()
}

fn ids(answer: &Answer) -> Vec<&str> {
    answer.slides.iter().map(|s| s.id.as_str()).collect()
}

#[tokio::test]
async fn asks_the_launcher_placement_and_shows_the_slides() {
    let store = FakeStore::start(|_, _| Reply::json(200, &fixture("slides")).header("ETag", "\"s1-a\"").header("Cache-Control", "public, max-age=60")).await;
    let state = ShowcaseState::new(projects());
    let answer = state.slides_at(&http(), &store.endpoints(), fixture_now()).await;
    assert_eq!(store.last().0, "/api/v1/showcase/?placement=launcher");
    assert_eq!(store.last().1.get("accept").map(String::as_str), Some("application/json"));
    assert!(!store.last().1.contains_key("authorization"), "nothing about the player is sent");
    assert_eq!(ids(&answer), ["6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b", "0a9b8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d", "c4d5e6f7-a8b9-4c0d-9e1f-2a3b4c5d6e7f"]);
    assert_eq!(answer.rotate_ms, 6000);
    // Within its max-age, the Store isn't asked again (a window focus costs nothing).
    state.slides_at(&http(), &store.endpoints(), fixture_now()).await;
    assert_eq!(store.requests(), 1);
    // Slides are looked up by id only for pictures and links.
    assert!(state.current(ID, fixture_now()).is_some());
    assert!(state.current("0a9b8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d", fixture_now()).unwrap().link.is_some());
    assert!(state.current("6f1c2a8e-3b4d-4e5f-9a0b-000000000000", fixture_now()).is_none());
}

#[tokio::test]
async fn a_staging_store_behind_access_gets_its_token() {
    let store = FakeStore::start(|_, _| Reply::json(200, &fixture("none"))).await;
    let mut endpoints = store.endpoints();
    endpoints.access = Some(crate::store::AccessToken { client_id: "id.access".into(), client_secret: "secret".into() });
    ShowcaseState::new(projects()).slides_at(&http(), &endpoints, fixture_now()).await;
    assert_eq!(store.last().1.get("cf-access-client-id").map(String::as_str), Some("id.access"));
}

#[tokio::test]
async fn slides_whose_end_passes_between_refreshes_drop_out() {
    let store = FakeStore::start(|_, _| Reply::json(200, &fixture("slides")).header("Cache-Control", "public, max-age=600")).await;
    let state = ShowcaseState::new(projects());
    let now = fixture_now();
    assert_eq!(state.slides_at(&http(), &store.endpoints(), now).await.slides.len(), 3);
    // Glasswing's slide ends at 12:20; at 12:30 the kept copy no longer shows it (no request made).
    let later = state.slides_at(&http(), &store.endpoints(), now + 1800).await;
    assert_eq!(store.requests(), 1);
    assert_eq!(later.slides.len(), 2);
    assert!(!ids(&later).contains(&"c4d5e6f7-a8b9-4c0d-9e1f-2a3b4c5d6e7f"));
    assert!(state.current("c4d5e6f7-a8b9-4c0d-9e1f-2a3b4c5d6e7f", now + 1800).is_none(), "nor can it be opened");
}

#[tokio::test]
async fn an_unchanged_feed_costs_a_304() {
    let store = FakeStore::start(|_, headers| {
        if headers.get("if-none-match").map(String::as_str) == Some("\"s1-a\"") {
            return Reply { status: 304, headers: vec![("ETag", "\"s1-a\"".into())], body: Vec::new() };
        }
        Reply::json(200, &fixture("one")).header("ETag", "\"s1-a\"")
    })
    .await;
    let state = ShowcaseState::new(projects());
    assert_eq!(state.slides_at(&http(), &store.endpoints(), fixture_now()).await.slides.len(), 1);
    state.forget_asked();
    let again = state.slides_at(&http(), &store.endpoints(), fixture_now()).await;
    assert_eq!(store.last().1.get("if-none-match").map(String::as_str), Some("\"s1-a\""));
    assert_eq!(again.slides.len(), 1);
}

#[tokio::test]
async fn no_slides_when_the_feed_is_empty_unknown_missing_or_down() {
    for reply in [
        Reply::json(200, &fixture("none")),
        Reply::json(400, &json!({"error": "invalid_placement"})),
        Reply::json(404, &json!({"error": "not_found"})),
        Reply::json(500, &json!({"error": "internal"})),
        Reply::json(502, &json!({})),
        Reply::json(503, &json!({"error": "unavailable"})),
        Reply::json(429, &json!({})).header("Retry-After", "30"),
        Reply { status: 200, headers: vec![], body: b"<html>Cloudflare</html>".to_vec() },
        Reply::json(200, &json!({"error": "invalid_placement"})),
        Reply { status: 304, headers: vec![], body: Vec::new() },
        Reply::json(200, &json!({"slides": vec![good(); 2000], "pad": "x".repeat(200_000)})),
    ] {
        let status = reply.status;
        let store = FakeStore::start(move |_, _| reply.clone()).await;
        let answer = ShowcaseState::new(projects()).slides_at(&http(), &store.endpoints(), fixture_now()).await;
        assert!(answer.slides.is_empty(), "{status}");
    }
    // No Store at all (nothing listening).
    let closed = TcpListener::bind("127.0.0.1:0").await.unwrap().local_addr().unwrap();
    let endpoints = Endpoints { store: Url::parse(&format!("http://{closed}/")).unwrap(), mojang: Url::parse("http://127.0.0.1/").unwrap(), access: None };
    assert!(ShowcaseState::new(projects()).slides_at(&http(), &endpoints, fixture_now()).await.slides.is_empty());
}

#[tokio::test]
async fn a_failure_after_slides_takes_the_card_away_but_a_rate_limit_doesnt() {
    let store = FakeStore::start(|_, _| Reply::json(200, &fixture("slides"))).await;
    let state = ShowcaseState::new(projects());
    let failures: [(&str, Reply); 5] = [
        ("503 unavailable", Reply::json(503, &json!({"error": "unavailable"}))),
        ("400 invalid_placement", Reply::json(400, &json!({"error": "invalid_placement"}))),
        ("404", Reply::json(404, &json!({}))),
        ("500", Reply::json(500, &json!({}))),
        ("unreadable", Reply { status: 200, headers: vec![], body: b"not json".to_vec() }),
    ];
    for (what, reply) in failures {
        store.answer(|_, _| Reply::json(200, &fixture("slides")));
        state.forget_asked();
        assert_eq!(state.slides_at(&http(), &store.endpoints(), fixture_now()).await.slides.len(), 3);
        store.answer(move |_, _| reply.clone());
        state.forget_asked();
        assert!(state.slides_at(&http(), &store.endpoints(), fixture_now()).await.slides.is_empty(), "{what}: no card");
        assert!(state.current(ID, fixture_now()).is_none(), "{what}: nothing to open");
    }
    // The Store unreachable after slides: no card either.
    store.answer(|_, _| Reply::json(200, &fixture("slides")));
    state.forget_asked();
    assert_eq!(state.slides_at(&http(), &store.endpoints(), fixture_now()).await.slides.len(), 3);
    let closed = TcpListener::bind("127.0.0.1:0").await.unwrap().local_addr().unwrap();
    let gone = Endpoints { store: Url::parse(&format!("http://{closed}/")).unwrap(), mojang: Url::parse("http://127.0.0.1/").unwrap(), access: None };
    state.forget_asked();
    assert!(state.slides_at(&http(), &gone, fixture_now()).await.slides.is_empty(), "offline: no card");
    // 429 only means "ask later": what is shown stays.
    state.forget_asked();
    assert_eq!(state.slides_at(&http(), &store.endpoints(), fixture_now()).await.slides.len(), 3);
    store.answer(|_, _| Reply::json(429, &json!({})).header("Retry-After", "30"));
    state.forget_asked();
    assert_eq!(state.slides_at(&http(), &store.endpoints(), fixture_now()).await.slides.len(), 3, "429: kept");
    // And an empty feed after slides empties the card.
    store.answer(|_, _| Reply::json(200, &fixture("none")));
    state.forget_asked();
    assert!(state.slides_at(&http(), &store.endpoints(), fixture_now()).await.slides.is_empty());
}

#[tokio::test]
async fn a_rate_limit_waits_for_retry_after() {
    let store = FakeStore::start(|_, _| Reply::json(429, &json!({})).header("Retry-After", "120")).await;
    let state = ShowcaseState::new(projects());
    state.slides_at(&http(), &store.endpoints(), fixture_now()).await;
    state.slides_at(&http(), &store.endpoints(), fixture_now()).await;
    assert_eq!(store.requests(), 1);
}

#[tokio::test]
async fn pictures_are_downloaded_at_most_2_mb() {
    let picture = png(2400, 800);
    let served = picture.clone();
    let store = FakeStore::start(move |path, _| match path {
        "/ok.png" => Reply { status: 200, headers: vec![("Content-Type", "image/png".into())], body: served.clone() },
        "/big.png" => Reply { status: 200, headers: vec![], body: vec![0; image::MAX_IMAGE + 1] },
        _ => Reply { status: 404, headers: vec![], body: Vec::new() },
    })
    .await;
    let bytes = download(&http(), &store.url.join("ok.png").unwrap()).await.unwrap();
    assert_eq!(check(&bytes, &slide_for(&picture, Format::Png)), Ok(()));
    assert_eq!(download(&http(), &store.url.join("big.png").unwrap()).await, Err(Rejected::Unavailable));
    assert_eq!(download(&http(), &store.url.join("gone.png").unwrap()).await, Err(Rejected::Unavailable));
}
