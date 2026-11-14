//! One showcase slide, checked: the Store's feed is untrusted, so every field the launcher shows or acts on is checked
//! again here against the contract (store src/lib/server/showcase.ts, Backend 7780 `showcase_slides`). A slide that
//! fails a check is left out; a link that isn't a plain https link only makes its slide not clickable (as the Store does).

use serde_json::Value;
use url::{Host, Url};

/// The Store sends at most 12; anything past that is left out.
pub const MAX_SLIDES: usize = 12;
pub const DEFAULT_ROTATE_MS: u64 = 6000;
/// A slide stays at least 3 s and at most 30 s, whatever the feed asks.
pub const ROTATE_RANGE: (u64, u64) = (3000, 30_000);
const MAX_TITLE: usize = 60;
const MAX_ALT: usize = 160;
const MAX_LINK: usize = 300;
/// The launcher's shape (Backend `showcase_image_ok`, placement launcher): 3:1 within 2 %, 1200×400 or more, at most 4096
/// a side.
const MIN_SIZE: (u32, u32) = (1200, 400);
const MAX_SIDE: u32 = 4096;
/// A slide that starts a little ahead of this computer's clock still shows (the Store already applied the schedule).
pub const CLOCK_SLACK_SECS: i64 = 120;
const BUCKET_PATH: &str = "storage/v1/object/public/showcase-media/slides/";

/// A picture's format, from its address (and checked again against its first bytes).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Format {
    Png,
    Jpeg,
    Webp,
}

impl Format {
    fn from_ext(ext: &str) -> Option<Self> {
        match ext {
            "png" => Some(Self::Png),
            "jpg" => Some(Self::Jpeg),
            "webp" => Some(Self::Webp),
            _ => None,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Slide {
    pub id: String,
    pub title: String,
    pub alt: String,
    /// Where the picture is fetched from: a showcase-media address in an allowed Supabase project.
    pub image: Url,
    /// The picture's SHA-256, from its file name: the bytes must hash to it.
    pub sha256: String,
    pub format: Format,
    pub width: u32,
    pub height: u32,
    /// The page a click opens in the system browser; None: the slide isn't clickable.
    pub link: Option<Url>,
    /// Seconds since the epoch.
    pub starts_at: Option<i64>,
    pub ends_at: Option<i64>,
    pub weight: u32,
}

impl Slide {
    /// Inside its schedule at `now` (seconds).
    pub fn live(&self, now: i64) -> bool {
        self.starts_at.is_none_or(|at| at <= now + CLOCK_SLACK_SECS) && self.ends_at.is_none_or(|at| at > now)
    }
}

/// A checked feed: the slides in the Store's order and how long each one stays.
#[derive(Clone, Debug, PartialEq, Default)]
pub struct Feed {
    pub slides: Vec<Slide>,
    pub rotate_ms: u64,
}

/// The feed from a 200 answer's body, or None when it isn't one at all (not an object with a `slides` list).
pub fn parse(body: &Value, projects: &[String], now: i64) -> Option<Feed> {
    let list = body.get("slides")?.as_array()?;
    if body.get("error").is_some() {
        return None;
    }
    let mut slides: Vec<Slide> = Vec::new();
    for raw in list {
        if slides.len() == MAX_SLIDES {
            break;
        }
        if let Some(slide) = slide(raw, projects, now) {
            if !slides.iter().any(|s| s.id == slide.id) {
                slides.push(slide);
            }
        }
    }
    Some(Feed { slides, rotate_ms: rotate_ms(&body["rotate_ms"]) })
}

/// The time each slide stays: the feed's, within 3 to 30 s; 6 s when it gives none (or nonsense).
pub fn rotate_ms(value: &Value) -> u64 {
    value.as_u64().map_or(DEFAULT_ROTATE_MS, |ms| ms.clamp(ROTATE_RANGE.0, ROTATE_RANGE.1))
}

/// One slide, or None when anything it needs is missing or wrong, or it is outside its schedule at `now`.
pub fn slide(raw: &Value, projects: &[String], now: i64) -> Option<Slide> {
    let id = raw["id"].as_str().filter(|id| crate::store::is_uuid(id) && id.bytes().all(|b| !b.is_ascii_uppercase()))?;
    let (image, sha256, format) = image_url(raw["image_url"].as_str()?, id, projects)?;
    let width = side(&raw["width"])?;
    let height = side(&raw["height"])?;
    if !launcher_shape(width, height) {
        return None;
    }
    let title = plain(&raw["title"], MAX_TITLE).filter(|t| !t.is_empty())?;
    let alt = match &raw["alt"] {
        Value::Null => String::new(),
        value => plain(value, MAX_ALT)?,
    };
    let alt = if alt.is_empty() { title.clone() } else { alt };
    let starts_at = time(&raw["starts_at"])?;
    let ends_at = time(&raw["ends_at"])?;
    let weight = raw["weight"].as_u64().filter(|w| (1..=100).contains(w)).unwrap_or(1) as u32;
    let slide = Slide { id: id.to_owned(), title, alt, image, sha256, format, width, height, link: raw["link_url"].as_str().and_then(link), starts_at, ends_at, weight };
    slide.live(now).then_some(slide)
}

/// 3:1 within 2 %, 1200×400 or more, at most 4096 a side (Backend `showcase_image_ok`: 50·|w − 3h| ≤ 3h).
pub fn launcher_shape(width: u32, height: u32) -> bool {
    let (w, h) = (u64::from(width), u64::from(height));
    width >= MIN_SIZE.0 && height >= MIN_SIZE.1 && width <= MAX_SIDE && height <= MAX_SIDE && 50 * w.abs_diff(3 * h) <= 3 * h
}

fn side(value: &Value) -> Option<u32> {
    value.as_u64().filter(|v| (1..=u64::from(MAX_SIDE)).contains(v)).map(|v| v as u32)
}

fn is_sha(text: &str) -> bool {
    text.len() == 64 && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// A picture address exactly as the Store builds one for slide `id`:
/// `https://<project>.supabase.co/storage/v1/object/public/showcase-media/slides/<id>/<sha256>.<png|jpg|webp>`, from one
/// of `projects` (this build's Supabase projects, partners::media_projects). Anything else is refused.
pub fn image_url(raw: &str, id: &str, projects: &[String]) -> Option<(Url, String, Format)> {
    let rest = raw.strip_prefix("https://")?;
    let (host, path) = rest.split_once('/')?;
    let project = host.strip_suffix(".supabase.co")?;
    if !projects.iter().any(|p| p == project) {
        return None;
    }
    let file = path.strip_prefix(BUCKET_PATH)?.strip_prefix(id)?.strip_prefix('/')?;
    let (name, ext) = file.split_once('.')?;
    let format = Format::from_ext(ext)?;
    if !is_sha(name) {
        return None;
    }
    let url = Url::parse(raw).ok()?;
    (url.query().is_none() && url.fragment().is_none() && url.port().is_none()).then(|| (url, name.to_owned(), format))
}

/// A link a click may open: https on a public host name (no user, password, IP address or bare name), at most 300
/// characters. Anything else: None.
pub fn link(raw: &str) -> Option<Url> {
    if raw.len() > MAX_LINK || raw.chars().any(|c| c.is_whitespace() || c.is_control() || matches!(c, '"' | '\'' | '<' | '>' | '\\')) {
        return None;
    }
    let url = Url::parse(raw).ok()?;
    let host_ok = match url.host() {
        Some(Host::Domain(host)) => host.contains('.') && !host.ends_with('.') && !host.ends_with(".localhost") && !host.ends_with(".local"),
        _ => false,
    };
    (url.scheme() == "https" && url.username().is_empty() && url.password().is_none() && host_ok).then_some(url)
}

/// Plain text, trimmed: no markup brackets or control characters, at most `max` characters. None for anything else.
fn plain(value: &Value, max: usize) -> Option<String> {
    let text = value.as_str()?.trim();
    (!text.chars().any(|c| c.is_control() || c == '<' || c == '>') && text.chars().count() <= max).then(|| text.to_owned())
}

/// A schedule time: None when missing (`Some(None)`), its seconds when readable; None (the slide is left out) otherwise.
fn time(value: &Value) -> Option<Option<i64>> {
    match value {
        Value::Null => Some(None),
        Value::String(text) => crate::store::rfc3339_secs(text).map(Some),
        _ => None,
    }
}
