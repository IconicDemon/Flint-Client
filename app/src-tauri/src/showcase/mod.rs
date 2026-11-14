//! The small sponsored card on Home: slides staff publish in Flint Admin (Store → Showcase, Backend 7780), from the Store's
//! showcase feed (`GET <Store>/api/v1/showcase/?placement=launcher`, the Store's src/lib/server/showcase.ts). Flint's own
//! slides only (sponsors, partners, promotions): no ad network and no third-party script, ever. Another source of slides
//! would be another module answering the same `Answer`; the card (src/features/home/ShowcaseCard.tsx) doesn't change.
//!
//! - **Fetching**: the Store this build talks to (store::Endpoints: store.flintclient.com, a staging build's staging Store,
//!   or `FLINT_STORE_URL` in development and isolated runs, with the same Access token), so it works wherever the Store
//!   and Partners do. No sign-in and nothing about the player is sent. A 6 s timeout; `If-None-Match` with the last ETag;
//!   the answer's `max-age` (60 s) is respected, so asking on every window focus costs nothing; `429` waits for
//!   `Retry-After`; one request at a time.
//! - **Session only**: the last good answer is kept in memory, nothing on disk. Every answer is filtered by the schedule
//!   again, so a slide whose end has passed never shows, even between refreshes.
//! - **Failures**: 400 (`invalid_placement`), 404, 503 (`unavailable`) or any other error answer, no connection, or an
//!   unreadable answer means no slides, so no card (the Store's showcase contract). Only a 429 keeps what is shown (it
//!   just means "ask later"). After a failure the Store is asked again at least 30 s later.
//! - **Untrusted**: at most 128 KB, each slide checked (slide.rs). Pictures (image.rs) and links are asked for by slide id
//!   only: the window never gets an address from the feed to load or open.

/// The in-game main menu's slideshow (placement=game_menu), handed to the game through the game link.
pub mod game;
pub mod image;
pub mod slide;
#[cfg(test)]
mod tests;

use crate::core::{AppState, CmdResult};
use crate::store::Endpoints;
use reqwest::header::{ACCEPT, CACHE_CONTROL, ETAG, IF_NONE_MATCH, RETRY_AFTER};
use serde::Serialize;
use slide::{Feed, Slide};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;

pub const FEED_PATH: &str = "/api/v1/showcase/";
pub const PLACEMENT: &str = "launcher";
const MAX_FEED: usize = 128 * 1024;
const TIMEOUT: Duration = Duration::from_secs(6);
/// The Store's `Cache-Control: public, max-age=60`; used when an answer gives none.
const DEFAULT_MAX_AGE: Duration = Duration::from_secs(60);
/// After a failed request, the next one waits at least this long.
const FAILURE_PAUSE: Duration = Duration::from_secs(30);

/// This run's showcase: one request at a time, the last good feed and when the Store was last asked.
pub struct ShowcaseState {
    /// The Supabase projects pictures may come from (partners::media_projects: this build's channel, and the project a
    /// development build names).
    projects: Vec<String>,
    fetch: tokio::sync::Mutex<()>,
    inner: Mutex<Inner>,
}

#[derive(Default)]
struct Inner {
    kept: Option<Kept>,
    /// When the Store was last asked, and how long until it is asked again.
    last: Option<(Instant, Duration)>,
}

struct Kept {
    feed: Feed,
    etag: Option<String>,
}

impl Default for ShowcaseState {
    fn default() -> Self {
        Self::new(crate::partners::media_projects())
    }
}

/// A slide as the card shows it. Its picture comes from `showcase_image`, its link opens with `showcase_open`.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SlideView {
    pub id: String,
    pub title: String,
    pub alt: String,
    pub width: u32,
    pub height: u32,
    /// The picture's SHA-256: a new picture is a new key.
    pub image_key: String,
    /// The host a click opens (`example.com`), or null when the slide isn't clickable.
    pub link_host: Option<String>,
    /// When the slide stops showing (ms since the epoch), or null.
    pub ends_at: Option<u64>,
    pub weight: u32,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Answer {
    /// In the order to show them; empty: the card isn't shown.
    pub slides: Vec<SlideView>,
    pub rotate_ms: u64,
}

fn view(slide: &Slide) -> SlideView {
    SlideView {
        id: slide.id.clone(),
        title: slide.title.clone(),
        alt: slide.alt.clone(),
        width: slide.width,
        height: slide.height,
        image_key: slide.sha256.clone(),
        link_host: slide.link.as_ref().and_then(|l| l.host_str()).map(|h| h.trim_start_matches("www.").to_owned()),
        ends_at: slide.ends_at.and_then(|s| u64::try_from(s).ok()).map(|s| s * 1000),
        weight: slide.weight,
    }
}

/// `max-age` from a Cache-Control header, within 30 s to 10 min.
fn max_age(headers: &reqwest::header::HeaderMap) -> Duration {
    headers
        .get(CACHE_CONTROL)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(',').find_map(|d| d.trim().strip_prefix("max-age=")?.parse::<u64>().ok()))
        .map(|s| Duration::from_secs(s.clamp(30, 600)))
        .unwrap_or(DEFAULT_MAX_AGE)
}

fn now() -> i64 {
    crate::core::now_secs() as i64
}

impl ShowcaseState {
    pub fn new(projects: Vec<String>) -> Self {
        Self { projects, fetch: tokio::sync::Mutex::new(()), inner: Mutex::default() }
    }

    fn recently_asked(&self) -> bool {
        self.inner.lock().unwrap().last.is_some_and(|(at, wait)| at.elapsed() < wait)
    }

    fn record(&self, wait: Duration) {
        self.inner.lock().unwrap().last = Some((Instant::now(), wait));
    }

    /// The slides to show at `now` (seconds): the kept feed's live slides, or none.
    fn shown(&self, now: i64) -> Answer {
        let inner = self.inner.lock().unwrap();
        match &inner.kept {
            Some(kept) => Answer { slides: kept.feed.slides.iter().filter(|s| s.live(now)).map(view).collect(), rotate_ms: kept.feed.rotate_ms },
            None => Answer { slides: Vec::new(), rotate_ms: slide::DEFAULT_ROTATE_MS },
        }
    }

    /// A failed ask: no slides (no card) until the Store answers with some again.
    fn failed(&self, wait: Duration, now: i64) -> Answer {
        self.record(wait);
        self.inner.lock().unwrap().kept = None;
        self.shown(now)
    }

    /// The live slide `id` of the kept feed.
    pub fn current(&self, id: &str, now: i64) -> Option<Slide> {
        self.inner.lock().unwrap().kept.as_ref()?.feed.slides.iter().find(|s| s.id == id && s.live(now)).cloned()
    }

    /// The slides to show, asking the Store unless it was asked within its max-age (or must be waited for).
    pub async fn slides(&self, http: &reqwest::Client, endpoints: &Endpoints) -> Answer {
        self.slides_at(http, endpoints, now()).await
    }

    pub async fn slides_at(&self, http: &reqwest::Client, endpoints: &Endpoints, now: i64) -> Answer {
        if self.recently_asked() {
            return self.shown(now);
        }
        // One request at a time; whoever waited finds the answer the first one got.
        let _one = self.fetch.lock().await;
        if self.recently_asked() {
            return self.shown(now);
        }
        let etag = self.inner.lock().unwrap().kept.as_ref().and_then(|k| k.etag.clone());
        let url = endpoints.api(&format!("{FEED_PATH}?placement={PLACEMENT}"));
        let mut request = crate::store::with_access(http.get(url), endpoints).timeout(TIMEOUT).header(ACCEPT, "application/json");
        if let Some(etag) = &etag {
            request = request.header(IF_NONE_MATCH, etag);
        }
        let Ok(response) = request.send().await else {
            return self.failed(FAILURE_PAUSE, now);
        };
        let status = response.status().as_u16();
        let headers = response.headers().clone();
        let new_etag = headers.get(ETAG).and_then(|v| v.to_str().ok()).filter(|v| v.len() <= 200 && v.is_ascii()).map(str::to_owned);
        match status {
            200 => {
                let body = crate::store::read_capped(response, MAX_FEED).await.ok().and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok());
                let Some(feed) = body.and_then(|b| slide::parse(&b, &self.projects, now)) else {
                    return self.failed(FAILURE_PAUSE, now);
                };
                self.record(max_age(&headers));
                self.inner.lock().unwrap().kept = Some(Kept { feed, etag: new_etag });
                self.shown(now)
            }
            304 => {
                let confirmed = {
                    let mut inner = self.inner.lock().unwrap();
                    let kept = inner.kept.as_mut();
                    let found = kept.is_some();
                    if let (Some(kept), Some(etag)) = (kept, new_etag) {
                        kept.etag = Some(etag);
                    }
                    found
                };
                if confirmed {
                    self.record(max_age(&headers));
                    self.shown(now)
                } else {
                    // A 304 for a copy Flint doesn't have: ask plainly next time.
                    self.failed(FAILURE_PAUSE, now)
                }
            }
            // Asked too often: what is shown stays, and the Store is asked again after its Retry-After.
            429 => {
                let wait = headers.get(RETRY_AFTER).and_then(|v| v.to_str().ok()).and_then(|v| v.trim().parse::<u64>().ok()).unwrap_or(60).clamp(1, 600);
                self.record(Duration::from_secs(wait));
                self.shown(now)
            }
            // 400 invalid_placement, 404, 503 unavailable, anything else: no slides.
            _ => self.failed(FAILURE_PAUSE, now),
        }
    }

    #[cfg(test)]
    fn forget_asked(&self) {
        self.inner.lock().unwrap().last = None;
    }
}

// ---------- commands ----------

/// The slides Home's card shows (asks the Store at most once a minute; the card asks every 5 minutes and on focus).
#[tauri::command]
pub async fn showcase_slides(state: State<'_, AppState>, showcase: State<'_, ShowcaseState>) -> CmdResult<Answer> {
    Ok(showcase.slides(&state.http, &state.store.endpoints).await)
}

/// A showing slide's picture, checked (image.rs), as raw bytes.
#[tauri::command]
pub async fn showcase_image(state: State<'_, AppState>, showcase: State<'_, ShowcaseState>, id: String) -> CmdResult<tauri::ipc::Response> {
    let slide = showcase.current(&id, now()).ok_or("This slide isn't showing any more.")?;
    let bytes = image::load(&state.http, &slide).await.map_err(|_| "This slide's picture couldn't be loaded.".to_string())?;
    Ok(tauri::ipc::Response::new(bytes))
}

/// Opens a showing slide's link in the system browser (never in Flint). Only links the feed gave and slide.rs accepted.
#[tauri::command]
pub fn showcase_open(app: AppHandle, showcase: State<'_, ShowcaseState>, id: String) -> CmdResult<()> {
    let slide = showcase.current(&id, now()).ok_or("This slide isn't showing any more.")?;
    let link = slide.link.ok_or("This slide has no link.")?;
    app.opener().open_url(link.as_str(), None::<&str>).map_err(|_| "Flint couldn't open that link.".to_string())
}
