//! Flint's partnership showcase, from the Store's Partners API v1 (`GET <Store>/api/v1/partners/`, the Store's
//! docs/partners-api-v1.md): the listings in Library → Partnerships and the one featured partner on Home.
//!
//! - **Fetching**: the Store this build talks to (store::Endpoints: store.flintclient.com, or a staging build's staging
//!   Store). No sign-in and nothing about the player is sent. `If-None-Match` with the last ETag, so an unchanged feed
//!   costs a 304; its `Cache-Control: max-age` (120 s) is respected; `429` waits for `Retry-After`; one request at a time.
//! - **Cache**: the last good answer, on disk per Store, for a short offline use (6 hours). The page checks each
//!   listing's dates again on every render, so a cached promotion that ended never shows.
//! - **Untrusted**: the body is at most 256 KB and must be a schema 1 feed; the page parses it strictly
//!   (src/lib/partners.ts). The launcher checks again what it acts on: pictures (media.rs) and the server it adds to a
//!   profile and joins (play.rs, rules.rs).
//! - **Favourites**: kept in Flint's data folder (`partners/favorites.json`), for this launcher.

pub mod media;
pub mod play;
pub mod rules;
#[cfg(test)]
mod tests;

use crate::core::{now_secs, read_json_opt, write_json, AppState, CmdResult};
use reqwest::header::{ACCEPT, CACHE_CONTROL, ETAG, IF_NONE_MATCH, RETRY_AFTER};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::State;
use url::Url;

pub const FEED_PATH: &str = "/api/v1/partners/";
const MAX_FEED: usize = 256 * 1024;
const TIMEOUT: Duration = Duration::from_secs(15);
const DEFAULT_MAX_AGE: Duration = Duration::from_secs(120);
/// After a failed request, the next automatic one waits at least this long.
const FAILURE_PAUSE: Duration = Duration::from_secs(30);
/// How long a cached answer may stand in while the Store can't be reached.
pub const OFFLINE_KEEP_MS: u64 = 6 * 3600 * 1000;
const MAX_FAVORITES: usize = 200;

/// Flint's Supabase projects (build.rs checks a build never mixes them up): partner pictures come from one of these.
const PRODUCTION_PROJECT: &str = "vkqzlqjbpvxrskwpvijt";
const STAGING_PROJECT: &str = "mxgeolovubvnjjhwihsi";

/// This run's partner state: one request at a time, when the last one was, and the current pictures by checksum.
#[derive(Default)]
pub struct PartnersState {
    fetch: tokio::sync::Mutex<()>,
    last: Mutex<Option<Attempt>>,
    index: Mutex<Option<HashMap<String, Url>>>,
}

#[derive(Clone, Copy)]
struct Attempt {
    at: Instant,
    /// The automatic refresh doesn't ask again before this much time has passed.
    wait: Duration,
}

/// The Supabase projects partner pictures may come from: this build's channel's project, and the project the build was
/// given (a development build may also name one with `FLINT_PARTNER_MEDIA_PROJECT`).
pub fn media_projects() -> Vec<String> {
    let mut out = vec![if crate::channel::STAGING { STAGING_PROJECT } else { PRODUCTION_PROJECT }.to_owned()];
    let built = option_env!("FLINT_SUPABASE_URL").and_then(project_of);
    let dev = cfg!(debug_assertions).then(|| std::env::var("FLINT_PARTNER_MEDIA_PROJECT").ok().or_else(|| std::env::var("FLINT_SUPABASE_URL").ok().and_then(|u| project_of(&u)))).flatten();
    for p in [built, dev].into_iter().flatten() {
        if p.len() == 20 && p.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit()) && !out.contains(&p) {
            out.push(p);
        }
    }
    out
}

fn project_of(url: &str) -> Option<String> {
    Url::parse(url.trim()).ok()?.host_str()?.strip_suffix(".supabase.co").map(str::to_owned)
}

// ---------- the cached copy ----------

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CacheFile {
    etag: Option<String>,
    /// When the Store last sent or confirmed this body (ms).
    fetched_at: u64,
    body: Value,
}

fn cache_path(state: &AppState) -> PathBuf {
    state.paths.cache().join("partners").join(state.store.endpoints.origin_key()).join("feed.json")
}

fn load_cache(state: &AppState) -> Option<CacheFile> {
    read_json_opt::<CacheFile>(&cache_path(state)).filter(|c| is_feed(&c.body))
}

/// A cached copy still recent enough to show while the Store can't be reached.
fn usable(cache: Option<CacheFile>) -> Option<CacheFile> {
    cache.filter(|c| crate::store::now_millis().saturating_sub(c.fetched_at) <= OFFLINE_KEEP_MS)
}

fn save_cache(state: &AppState, file: &CacheFile) {
    if let Err(error) = write_json(&cache_path(state), file) {
        eprintln!("[flint] couldn't keep a copy of the partner listings: {error}");
    }
}

/// The shape every Partners API v1 answer has; the page checks the rest.
pub fn is_feed(body: &Value) -> bool {
    body["schema"] == 1 && body["partners"].is_array() && body.get("error").is_none()
}

/// The pictures the current feed lists (read from the cached copy the first time).
pub fn current_index(state: &AppState) -> HashMap<String, Url> {
    if let Some(index) = state.store.partners.index.lock().unwrap().as_ref() {
        return index.clone();
    }
    let index = load_cache(state).map(|c| media::index(&c.body, &media_projects())).unwrap_or_default();
    *state.store.partners.index.lock().unwrap() = Some(index.clone());
    index
}

fn set_index(state: &AppState, body: &Value) {
    let index = media::index(body, &media_projects());
    let keep: HashSet<String> = index.keys().cloned().collect();
    *state.store.partners.index.lock().unwrap() = Some(index);
    media::prune(&media::dir(state), &keep);
}

// ---------- fetching ----------

#[derive(Serialize, Clone, Copy, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub enum Source {
    /// A new answer from the Store.
    Network,
    /// The Store confirmed the cached copy (304).
    Confirmed,
    /// The cached copy, without asking (asked recently, or `mode: "cache"`), or standing in after a failure.
    Cache,
    /// Nothing to show.
    None,
}

#[derive(Serialize, Clone, Copy, Debug, PartialEq)]
#[serde(rename_all = "kebab-case")]
pub enum Problem {
    /// The Store couldn't be reached.
    Offline,
    /// 503 (or another error answer).
    Unavailable,
    /// 429: asked too often.
    RateLimited,
    /// An answer that isn't a schema 1 feed (too large, not JSON).
    Invalid,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct FeedAnswer {
    pub source: Source,
    pub problem: Option<Problem>,
    /// The feed as the Store sent it (the page parses it), or null.
    pub body: Option<Value>,
    /// When the Store last sent or confirmed it (ms).
    pub fetched_at: Option<u64>,
    /// Where the page loads pictures from (`<base><sha256>`).
    pub media_base: String,
    /// The Supabase projects pictures may come from.
    pub media_projects: Vec<String>,
    /// Development only: listings may name a server on this computer (rules::allow_loopback).
    pub allow_loopback: bool,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Mode {
    /// The cached copy only, no request: the page's first paint.
    Cache,
    /// Ask the Store unless it was asked within its max-age (or must be waited for).
    Auto,
    /// Ask now (still conditional on the ETag).
    Force,
}

fn answer(source: Source, problem: Option<Problem>, cache: Option<CacheFile>) -> FeedAnswer {
    let (body, fetched_at) = match cache {
        Some(c) => (Some(c.body), Some(c.fetched_at)),
        None => (None, None),
    };
    let source = if body.is_none() { Source::None } else { source };
    FeedAnswer { source, problem, body, fetched_at, media_base: media::base(), media_projects: media_projects(), allow_loopback: rules::allow_loopback() }
}

fn recently_asked(state: &AppState) -> bool {
    state.store.partners.last.lock().unwrap().is_some_and(|a| a.at.elapsed() < a.wait)
}

fn record(state: &AppState, wait: Duration) {
    *state.store.partners.last.lock().unwrap() = Some(Attempt { at: Instant::now(), wait });
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

/// The partner listings: see `Mode`. A failure keeps showing the cached copy while it is recent enough.
pub async fn feed(state: &AppState, mode: Mode) -> FeedAnswer {
    if mode == Mode::Cache || (mode == Mode::Auto && recently_asked(state)) {
        let cache = usable(load_cache(state));
        if let Some(c) = &cache {
            if state.store.partners.index.lock().unwrap().is_none() {
                set_index(state, &c.body);
            }
        }
        return answer(Source::Cache, None, cache);
    }
    // One request at a time; whoever waited finds the answer the first one got.
    let _one = state.store.partners.fetch.lock().await;
    if mode == Mode::Auto && recently_asked(state) {
        return answer(Source::Cache, None, usable(load_cache(state)));
    }
    let cached = load_cache(state);
    let etag = cached.as_ref().and_then(|c| c.etag.clone());
    let endpoints = &state.store.endpoints;
    let mut request = crate::store::with_access(state.http.get(endpoints.api(FEED_PATH)), endpoints).timeout(TIMEOUT).header(ACCEPT, "application/json");
    if let Some(etag) = &etag {
        request = request.header(IF_NONE_MATCH, etag);
    }
    let response = match request.send().await {
        Ok(r) => r,
        Err(_) => {
            record(state, FAILURE_PAUSE);
            return answer(Source::Cache, Some(Problem::Offline), usable(cached));
        }
    };
    let status = response.status().as_u16();
    let headers = response.headers().clone();
    let new_etag = headers.get(ETAG).and_then(|v| v.to_str().ok()).filter(|v| v.len() <= 200 && v.is_ascii()).map(str::to_owned);
    match status {
        304 => match cached {
            Some(mut c) => {
                record(state, max_age(&headers));
                c.fetched_at = crate::store::now_millis();
                c.etag = new_etag.or(c.etag);
                save_cache(state, &c);
                set_index(state, &c.body);
                answer(Source::Confirmed, None, Some(c))
            }
            // A 304 for a copy Flint doesn't have: ask again plainly next time.
            None => {
                record(state, FAILURE_PAUSE);
                answer(Source::None, Some(Problem::Invalid), None)
            }
        },
        200 => {
            let body = crate::store::read_capped(response, MAX_FEED).await.ok().and_then(|b| serde_json::from_slice::<Value>(&b).ok()).filter(is_feed);
            match body {
                Some(body) => {
                    record(state, max_age(&headers));
                    let file = CacheFile { etag: new_etag, fetched_at: crate::store::now_millis(), body };
                    save_cache(state, &file);
                    set_index(state, &file.body);
                    answer(Source::Network, None, Some(file))
                }
                None => {
                    record(state, FAILURE_PAUSE);
                    answer(Source::Cache, Some(Problem::Invalid), usable(cached))
                }
            }
        }
        429 => {
            let wait = headers.get(RETRY_AFTER).and_then(|v| v.to_str().ok()).and_then(|v| v.trim().parse::<u64>().ok()).unwrap_or(60).clamp(1, 600);
            record(state, Duration::from_secs(wait));
            answer(Source::Cache, Some(Problem::RateLimited), usable(cached))
        }
        _ => {
            record(state, FAILURE_PAUSE);
            answer(Source::Cache, Some(Problem::Unavailable), usable(cached))
        }
    }
}

/// The cached feed's body, while it is recent enough to act on (play.rs looks a listing up in it).
pub(crate) fn cached_body(state: &AppState) -> Option<Value> {
    usable(load_cache(state)).map(|c| c.body)
}

// ---------- favourites ----------

#[derive(Serialize, Deserialize, Default)]
struct Favorites {
    ids: Vec<String>,
}

fn favorites_path(state: &AppState) -> PathBuf {
    state.paths.root.join("partners").join("favorites.json")
}

pub fn favorites(state: &AppState) -> Vec<String> {
    read_json_opt::<Favorites>(&favorites_path(state)).map(|f| f.ids.into_iter().filter(|id| crate::store::is_uuid(id)).collect()).unwrap_or_default()
}

pub fn set_favorite(state: &AppState, id: &str, on: bool) -> CmdResult<Vec<String>> {
    if !crate::store::is_uuid(id) {
        return Err("That isn't a partner listing.".into());
    }
    let mut ids = favorites(state);
    ids.retain(|x| x != id);
    if on {
        ids.insert(0, id.to_owned());
        ids.truncate(MAX_FAVORITES);
    }
    write_json(&favorites_path(state), &Favorites { ids: ids.clone() })?;
    Ok(ids)
}

// ---------- commands ----------

#[tauri::command]
pub async fn partners_feed(state: State<'_, AppState>, mode: Option<String>) -> CmdResult<FeedAnswer> {
    let mode = match mode.as_deref() {
        Some("cache") => Mode::Cache,
        Some("force") => Mode::Force,
        _ => Mode::Auto,
    };
    Ok(feed(&state, mode).await)
}

#[tauri::command]
pub fn partners_favorites(state: State<'_, AppState>) -> Vec<String> {
    favorites(&state)
}

#[tauri::command]
pub fn partners_set_favorite(state: State<'_, AppState>, id: String, on: bool) -> CmdResult<Vec<String>> {
    set_favorite(&state, &id, on)
}

/// Seconds since the epoch, for the listing schedules play.rs checks.
pub(crate) fn now() -> i64 {
    now_secs() as i64
}
