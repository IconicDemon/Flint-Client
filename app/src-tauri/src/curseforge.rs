//! CurseForge as the Library's second source, next to Modrinth.
//!
//! The app never holds a CurseForge API key: every call goes to Flint's Store proxy (`{base}/v1/...` mirrors
//! `api.curseforge.com/v1/...`; the Store adds the key), and files download straight from CurseForge's CDN, which needs
//! none. Development and isolated QA builds may point at another proxy with `FLINT_CURSEFORGE_URL` (an https address, or
//! http on this computer), such as `app/scripts/mock-curseforge.mjs`.
//!
//! CurseForge's answers are turned into the shapes Flint already uses for Modrinth (a project, a version with `files`,
//! `dependencies` and `loaders`), with ids `cf:<modId>` and `cf:<fileId>`, so planning, installing, updating, pinning and
//! removing work the same for both. Files are verified against CurseForge's SHA-1.
//!
//! Only Fabric mods and modpacks are offered (modLoaderType 4). Resource packs, shader packs and data packs take no loader.
//! A file whose author doesn't allow other apps to download it (`downloadUrl` null) is never fetched: Flint links to its
//! page on CurseForge instead.

use crate::core::*;
use crate::modrinth::{encode, valid_game_version, LibrarySearch};
use reqwest::Client;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::State;

pub mod modpack;

pub const PRODUCTION: &str = "https://store.flintclient.com/api/v1/curseforge";
/// Minecraft on CurseForge.
pub const GAME_ID: u32 = 432;
/// Where CurseForge's files are downloaded from (no key needed).
pub const CDN: &[&str] = &["edge.forgecdn.net", "mediafilez.forgecdn.net"];
/// The CDN plus this computer, for a development proxy on loopback that serves its own files.
const CDN_AND_LOOPBACK: &[&str] = &["edge.forgecdn.net", "mediafilez.forgecdn.net", "127.0.0.1", "localhost"];
/// How Flint's ids name a CurseForge mod or file.
pub const PREFIX: &str = "cf:";
/// The proxy's caps for one POST.
pub const MAX_IDS: usize = 100;
const CACHE_LIMIT: usize = 300;
/// A failed request this long after CurseForge asked Flint to slow down fails at once, without asking again.
const RETRY_INLINE: u64 = 2;

/// Short, per-run caches: content is live, so a new upload shows on the next search.
pub const SEARCH_TTL: Duration = Duration::from_secs(20);
pub const PROJECT_TTL: Duration = Duration::from_secs(120);
pub const FILES_TTL: Duration = Duration::from_secs(60);
pub const CATEGORIES_TTL: Duration = Duration::from_secs(3600);

/// CurseForge's class (content type) ids for Minecraft.
pub const CLASS_MOD: u64 = 6;
pub const CLASS_MODPACK: u64 = 4471;
pub const CLASS_RESOURCEPACK: u64 = 12;
pub const CLASS_SHADER: u64 = 6552;
pub const CLASS_DATAPACK: u64 = 6945;
/// CurseForge's ModLoaderType for Fabric.
pub const FABRIC: u32 = 4;

// ---------- ids ----------

/// The CurseForge number in a Flint id (`cf:238222`), or None for anything else (a Modrinth id).
pub fn parse_ref(id: &str) -> Option<u64> {
    let digits = id.strip_prefix(PREFIX)?;
    if digits.is_empty() || digits.len() > 12 || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    digits.parse().ok().filter(|&n| n > 0)
}

pub fn is_ref(id: &str) -> bool {
    parse_ref(id).is_some()
}

pub fn reference(n: u64) -> String {
    format!("{PREFIX}{n}")
}

/// A Modrinth id or slug, or a CurseForge id.
pub fn valid_ref(id: &str) -> bool {
    crate::modrinth::valid_id(id) || is_ref(id)
}

/// The site a project or version id belongs to, as players know it.
pub fn platform_name(id: &str) -> &'static str {
    if is_ref(id) {
        "CurseForge"
    } else {
        "Modrinth"
    }
}

// ---------- the proxy ----------

/// The proxy address from `FLINT_CURSEFORGE_URL`: https, or http on this computer; no credentials, query or fragment.
pub fn proxy_base(address: &str) -> Option<String> {
    let url = url::Url::parse(address.trim()).ok()?;
    let local = matches!(url.host_str(), Some("127.0.0.1" | "localhost" | "[::1]"));
    let scheme_ok = url.scheme() == "https" || (url.scheme() == "http" && local);
    let bare = url.username().is_empty() && url.password().is_none() && url.query().is_none() && url.fragment().is_none();
    (scheme_ok && bare && url.host_str().is_some_and(|h| !h.is_empty())).then(|| url.as_str().trim_end_matches('/').to_owned())
}

/// The Store answers its paths with a trailing slash (and redirects the others), so every path gets one before its query.
pub fn with_slash(path: &str) -> String {
    let (route, query) = match path.split_once('?') {
        Some((route, query)) => (route, Some(query)),
        None => (path, None),
    };
    let route = if route.ends_with('/') { route.to_owned() } else { format!("{route}/") };
    match query {
        Some(q) => format!("{route}?{q}"),
        None => route,
    }
}

/// Why a request didn't give an answer.
#[derive(Debug, Clone, PartialEq)]
pub enum Failure {
    /// The proxy has no key, isn't deployed yet, or CurseForge can't be reached from it (404, 502, 503).
    Unavailable,
    /// This computer couldn't reach the proxy.
    Offline,
    /// CurseForge asked Flint to wait this many seconds (429).
    Busy(u64),
    /// The mod or file isn't on CurseForge (or isn't Minecraft content).
    NotFound,
}

impl Failure {
    /// The state the Library shows for this failure.
    pub fn code(&self) -> &'static str {
        match self {
            Failure::Unavailable | Failure::NotFound => "unavailable",
            Failure::Offline => "offline",
            Failure::Busy(_) => "busy",
        }
    }

    pub fn message(&self, context: &str) -> String {
        match self {
            Failure::Unavailable => format!("{context}: CurseForge isn't available right now. Modrinth still works."),
            Failure::Offline => format!("{context}: could not connect (check your internet connection)"),
            Failure::Busy(wait) => format!("{context}: CurseForge asked Flint to slow down. Try again in {wait} seconds."),
            Failure::NotFound => format!("{context}: not found on CurseForge"),
        }
    }
}

/// Seconds from a Retry-After header (seconds form; CurseForge and the Store send seconds).
pub fn retry_after(value: Option<&str>) -> u64 {
    value.and_then(|v| v.trim().parse::<u64>().ok()).unwrap_or(60).clamp(1, 3600)
}

/// What each kind of answer means. `lookup`: a mod or file asked for by id, where 404 means it isn't there.
pub fn failure_for(status: u16, retry: Option<&str>, lookup: bool) -> Failure {
    match status {
        429 => Failure::Busy(retry_after(retry)),
        404 if lookup => Failure::NotFound,
        _ => Failure::Unavailable,
    }
}

pub struct CurseForge {
    pub base: String,
    /// The proxy runs on this computer (development): its own files may download over http from it too.
    loopback: bool,
    cache: Mutex<HashMap<String, (Instant, Value)>>,
    /// Until when CurseForge asked Flint to wait (429 Retry-After).
    wait_until: Mutex<Option<Instant>>,
}

impl CurseForge {
    pub fn production() -> Self {
        Self::at(PRODUCTION.to_owned())
    }

    /// Release builds always use Flint's proxy; development and isolated builds may name another (`FLINT_CURSEFORGE_URL`).
    pub fn from_env(development: bool) -> Self {
        let custom = development.then(|| std::env::var("FLINT_CURSEFORGE_URL").ok()).flatten().and_then(|a| proxy_base(&a));
        custom.map(Self::at).unwrap_or_else(Self::production)
    }

    fn at(base: String) -> Self {
        let loopback = url::Url::parse(&base).is_ok_and(|u| u.scheme() == "http" && matches!(u.host_str(), Some("127.0.0.1" | "localhost")));
        Self { base, loopback, cache: Mutex::new(HashMap::new()), wait_until: Mutex::new(None) }
    }

    /// The hosts CurseForge files may download from.
    pub fn download_hosts(&self) -> &'static [&'static str] {
        if self.loopback {
            CDN_AND_LOOPBACK
        } else {
            CDN
        }
    }

    pub fn url(&self, path: &str) -> String {
        format!("{}{}", self.base, with_slash(path))
    }

    fn waiting(&self) -> Option<u64> {
        let until = (*self.wait_until.lock().unwrap())?;
        let left = until.saturating_duration_since(Instant::now()).as_secs();
        (until > Instant::now()).then_some(left.max(1))
    }

    fn cached(&self, key: &str, ttl: Duration) -> Option<Value> {
        self.cache.lock().unwrap().get(key).filter(|(at, _)| at.elapsed() < ttl).map(|(_, v)| v.clone())
    }

    fn store(&self, key: String, value: &Value) {
        let mut cache = self.cache.lock().unwrap();
        if cache.len() >= CACHE_LIMIT {
            if let Some(oldest) = cache.iter().min_by_key(|(_, (at, _))| *at).map(|(k, _)| k.clone()) {
                cache.remove(&oldest);
            }
        }
        cache.insert(key, (Instant::now(), value.clone()));
    }

    async fn send(&self, http: &Client, path: &str, body: Option<&Value>, lookup: bool) -> Result<Value, Failure> {
        if let Some(left) = self.waiting() {
            return Err(Failure::Busy(left));
        }
        let url = self.url(path);
        let mut attempt = 0;
        loop {
            let request = match body {
                Some(body) => http.post(&url).json(body),
                None => http.get(&url),
            };
            let response = request
                .header(reqwest::header::USER_AGENT, crate::modrinth::user_agent())
                .header(reqwest::header::ACCEPT, "application/json")
                .timeout(Duration::from_secs(20))
                .send()
                .await
                .map_err(|_| Failure::Offline)?;
            let status = response.status().as_u16();
            if (200..300).contains(&status) {
                return response.json::<Value>().await.map_err(|_| Failure::Unavailable);
            }
            let retry = response.headers().get(reqwest::header::RETRY_AFTER).and_then(|v| v.to_str().ok()).map(str::to_owned);
            let failure = failure_for(status, retry.as_deref(), lookup);
            match failure {
                Failure::Busy(wait) if wait <= RETRY_INLINE && attempt == 0 => {
                    attempt += 1;
                    tokio::time::sleep(Duration::from_secs(wait)).await;
                }
                Failure::Busy(wait) => {
                    *self.wait_until.lock().unwrap() = Some(Instant::now() + Duration::from_secs(wait));
                    return Err(failure);
                }
                // CurseForge itself had a moment's trouble behind the proxy: one more try.
                Failure::Unavailable if status == 502 && attempt == 0 => {
                    attempt += 1;
                    tokio::time::sleep(Duration::from_millis(600)).await;
                }
                _ => {
                    if status == 400 {
                        eprintln!("[flint] the CurseForge proxy refused a request ({path})");
                    }
                    return Err(failure);
                }
            }
        }
    }

    /// GET with a short per-run cache; only good answers are kept.
    pub async fn get(&self, http: &Client, path: &str, ttl: Duration, lookup: bool) -> Result<Value, Failure> {
        if let Some(hit) = self.cached(path, ttl) {
            return Ok(hit);
        }
        let value = self.send(http, path, None, lookup).await?;
        self.store(path.to_owned(), &value);
        Ok(value)
    }

    /// POST with a short per-run cache keyed by its body (lookups by id only).
    pub async fn post(&self, http: &Client, path: &str, body: &Value, ttl: Duration) -> Result<Value, Failure> {
        let key = format!("POST {path} {body}");
        if let Some(hit) = self.cached(&key, ttl) {
            return Ok(hit);
        }
        let value = self.send(http, path, Some(body), false).await?;
        self.store(key, &value);
        Ok(value)
    }
}

// ---------- CurseForge's answers in Flint's shapes ----------

pub fn class_of(kind: &str) -> Option<u64> {
    match kind {
        "mod" => Some(CLASS_MOD),
        "modpack" => Some(CLASS_MODPACK),
        "resourcepack" => Some(CLASS_RESOURCEPACK),
        "shader" => Some(CLASS_SHADER),
        "datapack" => Some(CLASS_DATAPACK),
        _ => None,
    }
}

pub fn kind_of_class(class_id: u64) -> Option<&'static str> {
    match class_id {
        CLASS_MOD => Some("mod"),
        CLASS_MODPACK => Some("modpack"),
        CLASS_RESOURCEPACK => Some("resourcepack"),
        CLASS_SHADER => Some("shader"),
        CLASS_DATAPACK => Some("datapack"),
        _ => None,
    }
}

/// CurseForge's ModLoaderType numbers.
fn loader_of_type(n: u64) -> Option<&'static str> {
    match n {
        1 => Some("forge"),
        4 => Some("fabric"),
        5 => Some("quilt"),
        6 => Some("neoforge"),
        _ => None,
    }
}

const MOD_LOADERS: [&str; 4] = ["fabric", "forge", "neoforge", "quilt"];
/// What a file that names no mod loader is: a resource pack, shader pack or data pack, which need none.
const PACK_LOADERS: [&str; 3] = ["minecraft", "iris", "datapack"];

/// The loaders a file is built for, from its `gameVersions` (which mix Minecraft versions with "Fabric", "Forge"…).
/// A file naming none is loader-independent content.
pub fn file_loaders(file: &Value) -> Vec<String> {
    let mut found: Vec<String> = Vec::new();
    for tag in file["gameVersions"].as_array().into_iter().flatten().filter_map(Value::as_str) {
        let lower = tag.to_ascii_lowercase();
        if MOD_LOADERS.contains(&lower.as_str()) && !found.contains(&lower) {
            found.push(lower);
        }
    }
    if found.is_empty() {
        PACK_LOADERS.iter().map(|l| (*l).to_owned()).collect()
    } else {
        found
    }
}

/// The Minecraft versions among a file's `gameVersions`.
pub fn file_game_versions(file: &Value) -> Vec<String> {
    file["gameVersions"].as_array().into_iter().flatten().filter_map(Value::as_str).filter(|v| v.starts_with(|c: char| c.is_ascii_digit()) && valid_game_version(v)).map(str::to_owned).collect()
}

fn release_type(n: Option<u64>) -> &'static str {
    match n {
        Some(1) => "release",
        Some(2) => "beta",
        _ => "alpha",
    }
}

/// FileRelationType: 3 required, 2 optional, 5 incompatible; embedded libraries, tools and includes need nothing.
fn relation(n: Option<u64>) -> &'static str {
    match n {
        Some(3) => "required",
        Some(2) => "optional",
        Some(5) => "incompatible",
        _ => "embedded",
    }
}

/// A file's published hash by HashAlgo (1 SHA-1, 2 MD5).
pub fn file_hash(file: &Value, algo: u64) -> Option<String> {
    file["hashes"].as_array()?.iter().find(|h| h["algo"].as_u64() == Some(algo)).and_then(|h| h["value"].as_str()).map(|h| h.to_ascii_lowercase()).filter(|h| !h.is_empty())
}

/// A file's name without its extension: CurseForge has no version numbers, only display names.
fn version_label(file: &Value) -> String {
    let name = file["displayName"].as_str().filter(|n| !n.trim().is_empty()).or(file["fileName"].as_str()).unwrap_or_default().trim();
    name.strip_suffix(".jar").or_else(|| name.strip_suffix(".zip")).unwrap_or(name).to_owned()
}

/// A CurseForge File as a Modrinth-shaped version. `files[0].url` is null when its author allows downloads on
/// CurseForge only.
pub fn version_of(file: &Value) -> Value {
    let dependencies: Vec<Value> = file["dependencies"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|d| {
            let id = d["modId"].as_u64().filter(|&n| n > 0)?;
            Some(json!({ "project_id": reference(id), "version_id": Value::Null, "dependency_type": relation(d["relationType"].as_u64()) }))
        })
        .collect();
    let url = file["downloadUrl"].as_str().filter(|u| u.starts_with("https://") || u.starts_with("http://"));
    json!({
        "id": file["id"].as_u64().map(reference),
        "project_id": file["modId"].as_u64().map(reference),
        "name": file["displayName"],
        "version_number": version_label(file),
        "version_type": release_type(file["releaseType"].as_u64()),
        "game_versions": file_game_versions(file),
        "loaders": file_loaders(file),
        "date_published": file["fileDate"],
        "downloads": file["downloadCount"],
        "changelog": "",
        "dependencies": dependencies,
        "files": [{
            "filename": file["fileName"],
            "size": file["fileLength"],
            "primary": true,
            "url": url,
            "hashes": { "sha1": file_hash(file, 1), "md5": file_hash(file, 2) },
        }],
        "platform": "curseforge",
    })
}

/// Whether Flint may download a normalized version's file itself.
pub fn downloadable(version: &Value) -> bool {
    version["files"][0]["url"].is_string()
}

/// The loaders a mod's latest files are built for (its `latestFilesIndexes` and `latestFiles`).
fn mod_loaders(m: &Value, kind: &str) -> Vec<String> {
    if !matches!(kind, "mod" | "modpack") {
        return crate::modrinth::loaders_for(kind).iter().map(|l| (*l).to_owned()).collect();
    }
    let mut found: Vec<String> = Vec::new();
    for index in m["latestFilesIndexes"].as_array().into_iter().flatten() {
        if let Some(loader) = index["modLoader"].as_u64().and_then(loader_of_type) {
            if !found.iter().any(|f| f == loader) {
                found.push(loader.to_owned());
            }
        }
    }
    for file in m["latestFiles"].as_array().into_iter().flatten() {
        for loader in file_loaders(file).into_iter().filter(|l| MOD_LOADERS.contains(&l.as_str())) {
            if !found.contains(&loader) {
                found.push(loader);
            }
        }
    }
    // Nothing says which: the files decide (each is checked for Fabric before it's chosen).
    if found.is_empty() {
        found.push("fabric".into());
    }
    found
}

/// Orders Minecraft versions oldest first ("1.21.11" after "1.21.9", "26.1" after both).
fn version_order(a: &str, b: &str) -> std::cmp::Ordering {
    let parts = |v: &str| v.split(|c: char| !c.is_ascii_digit()).filter(|p| !p.is_empty()).map(|p| p.parse::<u64>().unwrap_or(0)).collect::<Vec<_>>();
    parts(a).cmp(&parts(b)).then_with(|| a.cmp(b))
}

fn mod_game_versions(m: &Value) -> Vec<String> {
    let mut versions: Vec<String> = m["latestFilesIndexes"].as_array().into_iter().flatten().filter_map(|i| i["gameVersion"].as_str()).filter(|v| valid_game_version(v)).map(str::to_owned).collect::<HashSet<_>>().into_iter().collect();
    versions.sort_by(|a, b| version_order(a, b));
    versions
}

fn icon_of(m: &Value) -> Value {
    m["logo"]["thumbnailUrl"].as_str().or(m["logo"]["url"].as_str()).filter(|u| u.starts_with("https://")).map(Value::from).unwrap_or(Value::Null)
}

fn author_of(m: &Value) -> String {
    m["authors"].as_array().and_then(|a| a.first()).and_then(|a| a["name"].as_str()).unwrap_or("CurseForge").to_owned()
}

fn https(v: &Value) -> Value {
    v.as_str().filter(|u| u.starts_with("https://")).map(Value::from).unwrap_or(Value::Null)
}

/// A CurseForge Mod as a Modrinth-shaped project, for planning and installing.
pub fn project_of(m: &Value) -> Value {
    let kind = m["classId"].as_u64().and_then(kind_of_class).unwrap_or("mod");
    json!({
        "id": m["id"].as_u64().map(reference),
        "slug": m["slug"],
        "title": m["name"],
        "description": m["summary"],
        "icon_url": icon_of(m),
        "project_type": kind,
        "loaders": mod_loaders(m, kind),
        "game_versions": mod_game_versions(m),
        "client_side": "unknown",
        "server_side": "unknown",
        "website_url": https(&m["links"]["websiteUrl"]),
        "allow_distribution": m["allowModDistribution"],
        "platform": "curseforge",
    })
}

/// A search result card.
pub fn hit_of(m: &Value, kind: &str) -> Value {
    let gallery = m["screenshots"].as_array().and_then(|s| s.first()).and_then(|s| s["thumbnailUrl"].as_str().or(s["url"].as_str())).filter(|u| u.starts_with("https://"));
    let mut versions = mod_game_versions(m);
    versions.reverse();
    versions.truncate(6);
    json!({
        "projectId": m["id"].as_u64().map(reference),
        "slug": m["slug"],
        "title": m["name"],
        "description": m["summary"],
        "iconUrl": icon_of(m),
        "author": author_of(m),
        "downloads": m["downloadCount"].as_f64().map(|d| d as u64).unwrap_or(0),
        "follows": Value::Null,
        "categories": m["categories"].as_array().map(|c| c.iter().filter_map(|x| x["name"].as_str()).take(6).collect::<Vec<_>>()),
        "versions": versions,
        "projectType": m["classId"].as_u64().and_then(kind_of_class).unwrap_or(kind),
        "dateModified": m["dateReleased"].as_str().or(m["dateModified"].as_str()),
        "gallery": gallery,
        "color": Value::Null,
        "websiteUrl": https(&m["links"]["websiteUrl"]),
        "source": "curseforge",
    })
}

/// CurseForge's sort for each of the Library's (ModsSearchSortField): relevance is CurseForge's own (Featured),
/// "follows" is popularity (CurseForge has no follows).
pub fn sort_field(index: Option<&str>) -> u32 {
    match index {
        Some("downloads") => 6,
        Some("follows") => 2,
        Some("updated") => 3,
        Some("newest") => 11,
        _ => 1,
    }
}

/// The proxy path for a Library search. Mods and modpacks are Fabric only; packs are filtered by game version alone.
pub fn search_path(params: &LibrarySearch) -> CmdResult<String> {
    let class = class_of(&params.kind).ok_or("Unknown content type")?;
    let limit = params.limit.unwrap_or(20).clamp(1, 50);
    // CurseForge pages stop at 10,000 results (index + pageSize).
    let index = params.offset.min(10_000 - limit);
    let query: String = params.query.trim().chars().take(120).collect();
    let mut path = format!("/v1/mods/search?gameId={GAME_ID}&classId={class}&sortField={}&sortOrder=desc&index={index}&pageSize={limit}", sort_field(params.index.as_deref()));
    if !query.is_empty() {
        path.push_str(&format!("&searchFilter={}", encode(&query)));
    }
    if matches!(params.kind.as_str(), "mod" | "modpack") {
        path.push_str(&format!("&modLoaderType={FABRIC}"));
    }
    if let Some(version) = params.game_version.as_deref().filter(|v| valid_game_version(v)) {
        path.push_str(&format!("&gameVersion={}", encode(version)));
    }
    let ids: Vec<u64> = params.categories.iter().filter_map(|c| c.parse::<u64>().ok()).filter(|&n| n > 0).take(10).collect();
    if !ids.is_empty() {
        path.push_str(&format!("&categoryIds={}", encode(&json!(ids).to_string())));
    }
    Ok(path)
}

/// A mod's files path, newest first: for one Minecraft version (and Fabric, for mods and modpacks) when given.
pub fn files_path(mod_id: u64, kind: &str, game_version: Option<&str>) -> String {
    let mut path = format!("/v1/mods/{mod_id}/files?pageSize=50&index=0");
    if let Some(v) = game_version.filter(|v| valid_game_version(v)) {
        path.push_str(&format!("&gameVersion={}", encode(v)));
    }
    if matches!(kind, "mod" | "modpack") {
        path.push_str(&format!("&modLoaderType={FABRIC}"));
    }
    path
}

/// Normalized versions, newest first.
pub fn versions_from(list: &Value) -> Vec<Value> {
    let mut versions: Vec<Value> = list["data"].as_array().into_iter().flatten().map(version_of).collect();
    versions.sort_by(|a, b| b["date_published"].as_str().cmp(&a["date_published"].as_str()));
    versions
}

// ---------- catalog (planning, installing, updating) ----------

fn lookup_error(context: &str) -> impl Fn(Failure) -> String + '_ {
    move |f| f.message(context)
}

pub async fn project(state: &AppState, mod_id: u64) -> CmdResult<Value> {
    let answer = state.curseforge.get(&state.http, &format!("/v1/mods/{mod_id}"), PROJECT_TTL, true).await.map_err(lookup_error("Couldn't load a project from CurseForge"))?;
    Ok(project_of(&answer["data"]))
}

/// One file by its id (the batch endpoint needs no mod id).
pub async fn version(state: &AppState, file_id: u64) -> CmdResult<Value> {
    let answer = state.curseforge.post(&state.http, "/v1/mods/files", &json!({ "fileIds": [file_id] }), FILES_TTL).await.map_err(lookup_error("Couldn't load a file from CurseForge"))?;
    let file = answer["data"].as_array().and_then(|l| l.iter().find(|f| f["id"].as_u64() == Some(file_id))).ok_or_else(|| Failure::NotFound.message("Couldn't load a file from CurseForge"))?;
    Ok(version_of(file))
}

pub async fn versions(state: &AppState, mod_id: u64, kind: &str, game_version: Option<&str>) -> CmdResult<Value> {
    let answer = state.curseforge.get(&state.http, &files_path(mod_id, kind, game_version), FILES_TTL, true).await.map_err(lookup_error("Couldn't check versions on CurseForge"))?;
    Ok(Value::Array(versions_from(&answer)))
}

/// Several mods at once (at most `MAX_IDS` a request), by id.
pub async fn mods(state: &AppState, ids: &[u64]) -> CmdResult<Vec<Value>> {
    let mut out = Vec::new();
    for chunk in ids.chunks(MAX_IDS) {
        let answer = state.curseforge.post(&state.http, "/v1/mods", &json!({ "modIds": chunk }), PROJECT_TTL).await.map_err(lookup_error("Couldn't load projects from CurseForge"))?;
        out.extend(answer["data"].as_array().cloned().unwrap_or_default());
    }
    Ok(out)
}

/// Several files at once (at most `MAX_IDS` a request), by id, as CurseForge gives them.
pub async fn files(state: &AppState, ids: &[u64]) -> CmdResult<Vec<Value>> {
    let mut out = Vec::new();
    for chunk in ids.chunks(MAX_IDS) {
        let answer = state.curseforge.post(&state.http, "/v1/mods/files", &json!({ "fileIds": chunk }), FILES_TTL).await.map_err(lookup_error("Couldn't load files from CurseForge"))?;
        out.extend(answer["data"].as_array().cloned().unwrap_or_default());
    }
    Ok(out)
}

/// A file's changelog (HTML, which the app sanitizes), trimmed.
pub async fn changelog(state: &AppState, mod_id: u64, file_id: u64) -> CmdResult<String> {
    let answer = state.curseforge.get(&state.http, &format!("/v1/mods/{mod_id}/files/{file_id}/changelog"), FILES_TTL, true).await.map_err(lookup_error("Couldn't load the changelog"))?;
    Ok(answer["data"].as_str().unwrap_or_default().chars().take(20_000).collect())
}

/// The newest of a mod's latest files for one Minecraft version (and Fabric, for mods), from `latestFilesIndexes`:
/// whether something other than `installed` may be newer. True when the mod says nothing for that version, so the
/// full list is asked for.
pub fn may_have_newer(m: &Value, installed_file: u64, kind: &str, game_version: &str) -> bool {
    let fabric = matches!(kind, "mod" | "modpack");
    let indexes: Vec<&Value> = m["latestFilesIndexes"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|i| i["gameVersion"].as_str() == Some(game_version))
        .filter(|i| !fabric || i["modLoader"].as_u64() == Some(FABRIC as u64))
        .collect();
    indexes.is_empty() || indexes.iter().any(|i| i["fileId"].as_u64().is_some_and(|f| f > installed_file))
}

// ---------- commands ----------

/// A Library search on CurseForge. When CurseForge can't be asked (no proxy yet, no key, offline, rate limited) the
/// answer says so in `unavailable` instead of failing, so the Library shows a calm state and Modrinth keeps working.
#[tauri::command]
pub async fn curseforge_search(state: State<'_, AppState>, params: LibrarySearch) -> CmdResult<Value> {
    search(&state, &params).await
}

/// Why CurseForge lists no mods for Minecraft 1.8.9 (the `unavailable` code `ornithe`, which the Library shows).
pub const NO_ORNITHE_MODS: &str = "CurseForge doesn't mark which 1.8.9 mods are Ornithe builds, and Flint's 1.8.9 can't load Legacy Fabric ones. Find 1.8.9 mods on Modrinth.";

/// CurseForge's answer for a mod search on 1.8.9, without asking it: nothing. Its loaders are Forge, Cauldron, LiteLoader,
/// Fabric, Quilt and NeoForge, and every 1.8.9 Fabric file is just "Fabric", Legacy Fabric's and Ornithe's alike, so its
/// results couldn't be told apart before download (see `ornithe.rs`). Packs, and every other version, are searched as before.
pub fn no_ornithe_mods(params: &LibrarySearch) -> Option<Value> {
    let ornithe = params.kind == "mod" && params.game_version.as_deref().is_some_and(crate::ornithe::on_ornithe) && params.loader.as_deref() != Some("vanilla");
    ornithe.then(|| {
        json!({
            "hits": [],
            "offset": params.offset,
            "limit": 0,
            "total": 0,
            "stale": false,
            "staging": false,
            "rateLimit": {},
            "unavailable": "ornithe",
            "message": NO_ORNITHE_MODS,
        })
    })
}

/// A Library search on CurseForge (the Library's page, and the in-game Library through the game link).
pub async fn search(state: &AppState, params: &LibrarySearch) -> CmdResult<Value> {
    if let Some(answer) = no_ornithe_mods(params) {
        return Ok(answer);
    }
    let path = search_path(params)?;
    match state.curseforge.get(&state.http, &path, SEARCH_TTL, false).await {
        Ok(answer) => {
            let hits: Vec<Value> = answer["data"].as_array().into_iter().flatten().map(|m| hit_of(m, &params.kind)).collect();
            let total = answer["pagination"]["totalCount"].as_u64().unwrap_or(hits.len() as u64).min(10_000);
            Ok(json!({
                "hits": hits,
                "offset": answer["pagination"]["index"].as_u64().unwrap_or(params.offset as u64),
                "limit": answer["pagination"]["pageSize"].as_u64().unwrap_or(hits.len() as u64),
                "total": total,
                "stale": false,
                "staging": false,
                "rateLimit": {},
                "unavailable": Value::Null,
            }))
        }
        Err(failure) => Ok(json!({
            "hits": [],
            "offset": params.offset,
            "limit": 0,
            "total": 0,
            "stale": false,
            "staging": false,
            "rateLimit": {},
            "unavailable": failure.code(),
            "retryIn": match failure { Failure::Busy(wait) => Some(wait), _ => None },
        })),
    }
}

/// CurseForge's categories for every content type the Library shows, by id (names are CurseForge's, in English).
#[tauri::command]
pub async fn curseforge_categories(state: State<'_, AppState>) -> CmdResult<Value> {
    let answer = state.curseforge.get(&state.http, &format!("/v1/categories?gameId={GAME_ID}"), CATEGORIES_TTL, false).await.map_err(|f| f.message("Couldn't load categories"))?;
    Ok(json!({ "categories": categories_from(&answer) }))
}

pub fn categories_from(answer: &Value) -> Vec<Value> {
    let mut list: Vec<(String, Value)> = answer["data"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|c| c["isClass"].as_bool() != Some(true))
        .filter_map(|c| {
            let kind = c["classId"].as_u64().and_then(kind_of_class)?;
            let id = c["id"].as_u64()?;
            let name = c["name"].as_str()?;
            Some((name.to_owned(), json!({ "name": id.to_string(), "label": name, "projectType": kind, "header": "categories" })))
        })
        .collect();
    list.sort_by_key(|(name, _)| name.to_lowercase());
    list.into_iter().map(|(_, v)| v).collect()
}

/// The project drawer: the mod and its description, asked for together.
pub async fn library_project(state: &AppState, mod_id: u64) -> CmdResult<Value> {
    let (mod_path, description_path) = (format!("/v1/mods/{mod_id}"), format!("/v1/mods/{mod_id}/description"));
    let (project, description) = futures_util::join!(
        state.curseforge.get(&state.http, &mod_path, PROJECT_TTL, true),
        state.curseforge.get(&state.http, &description_path, PROJECT_TTL, true),
    );
    let m = project.map_err(|f| f.message("Couldn't load this project"))?["data"].clone();
    let body: String = description.ok().and_then(|d| d["data"].as_str().map(str::to_owned)).unwrap_or_default().chars().take(60_000).collect();
    let kind = m["classId"].as_u64().and_then(kind_of_class).unwrap_or("mod");
    let authors: Vec<Value> = m["authors"].as_array().into_iter().flatten().take(12).enumerate().map(|(i, a)| json!({ "username": a["name"], "role": "Author", "avatarUrl": Value::Null, "owner": i == 0 })).collect();
    Ok(json!({
        "id": m["id"].as_u64().map(reference),
        "slug": m["slug"],
        "title": m["name"],
        "description": m["summary"],
        "body": body,
        "iconUrl": icon_of(&m),
        "projectType": kind,
        "categories": m["categories"].as_array().map(|c| c.iter().filter_map(|x| x["name"].as_str()).collect::<Vec<_>>()).unwrap_or_default(),
        "additionalCategories": [],
        "loaders": mod_loaders(&m, kind),
        "gameVersions": mod_game_versions(&m),
        "clientSide": "unknown",
        "serverSide": "unknown",
        "downloads": m["downloadCount"].as_f64().map(|d| d as u64).unwrap_or(0),
        "followers": m["thumbsUpCount"].as_u64().unwrap_or(0),
        "license": Value::Null,
        "published": m["dateCreated"],
        "updated": m["dateReleased"].as_str().or(m["dateModified"].as_str()),
        "gallery": m["screenshots"].as_array().map(|g| g.iter().take(12).filter(|i| i["url"].as_str().is_some_and(|u| u.starts_with("https://"))).map(|i| json!({ "url": i["url"], "title": i["title"], "featured": false })).collect::<Vec<_>>()),
        "sourceUrl": https(&m["links"]["sourceUrl"]),
        "issuesUrl": https(&m["links"]["issuesUrl"]),
        "wikiUrl": https(&m["links"]["wikiUrl"]),
        "websiteUrl": https(&m["links"]["websiteUrl"]),
        "color": Value::Null,
        "organization": Value::Null,
        "author": author_of(&m),
        "members": authors,
        "stale": false,
        "source": "curseforge",
    }))
}

/// The drawer's versions: each file Flint can install, or one to get on CurseForge (`downloadable` false).
pub async fn library_versions(state: &AppState, mod_id: u64, kind: &str, game_version: Option<&str>) -> CmdResult<Value> {
    let list = versions(state, mod_id, kind, game_version).await?;
    let versions: Vec<Value> = list
        .as_array()
        .into_iter()
        .flatten()
        .take(60)
        .map(|v| {
            let mut trimmed = crate::modrinth::trim_version(v);
            trimmed["downloadable"] = json!(downloadable(v));
            trimmed
        })
        .collect();
    Ok(json!({ "versions": versions, "stale": false }))
}

/// A file's changelog for the drawer and the update dialog, fetched when it's opened.
#[tauri::command]
pub async fn curseforge_changelog(state: State<'_, AppState>, project_id: String, version_id: String) -> CmdResult<String> {
    let (Some(mod_id), Some(file_id)) = (parse_ref(&project_id), parse_ref(&version_id)) else {
        return Err("Invalid project".into());
    };
    changelog(&state, mod_id, file_id).await
}

#[tauri::command]
pub fn curseforge_status(state: State<'_, AppState>) -> Value {
    json!({ "base": state.curseforge.base, "production": state.curseforge.base == PRODUCTION })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn search(kind: &str, query: &str, index: Option<&str>, offset: u32, game: Option<&str>, categories: &[&str]) -> LibrarySearch {
        serde_json::from_value(json!({
            "kind": kind, "query": query, "index": index, "offset": offset, "limit": 24, "gameVersion": game, "categories": categories,
        }))
        .unwrap()
    }

    #[test]
    fn ids_name_curseforge_numbers_only() {
        assert_eq!(parse_ref("cf:238222"), Some(238222));
        for bad in ["238222", "cf:", "cf:0", "cf:12a", "cf:-1", "cf:1234567890123", "CF:1", "AANobbMI", "cf: 1"] {
            assert_eq!(parse_ref(bad), None, "{bad:?}");
        }
        assert!(valid_ref("cf:1") && valid_ref("AANobbMI") && valid_ref("fabric-api") && !valid_ref("cf:x") && !valid_ref("a/b"));
        assert_eq!((platform_name("cf:9"), platform_name("P7dR8mSH")), ("CurseForge", "Modrinth"));
    }

    #[test]
    fn the_proxy_is_https_or_this_computer() {
        assert_eq!(proxy_base("https://store.flintclient.com/api/v1/curseforge/").as_deref(), Some(PRODUCTION));
        assert_eq!(proxy_base("http://127.0.0.1:4810").as_deref(), Some("http://127.0.0.1:4810"));
        for bad in ["http://example.com/cf", "https://user:pw@example.com", "https://example.com/?key=1", "ftp://127.0.0.1", "not a url"] {
            assert_eq!(proxy_base(bad), None, "{bad}");
        }
        assert_eq!(CurseForge::production().download_hosts(), CDN, "release builds download from the CDN only");
        assert_eq!(CurseForge::at("http://127.0.0.1:4810".into()).download_hosts(), CDN_AND_LOOPBACK);
    }

    #[test]
    fn every_path_ends_in_a_slash_before_its_query() {
        assert_eq!(with_slash("/v1/mods/search?gameId=432&x=1"), "/v1/mods/search/?gameId=432&x=1");
        assert_eq!(with_slash("/v1/mods"), "/v1/mods/");
        assert_eq!(with_slash("/v1/fingerprints/432/"), "/v1/fingerprints/432/");
        assert_eq!(CurseForge::production().url("/v1/mods/files"), "https://store.flintclient.com/api/v1/curseforge/v1/mods/files/");
    }

    #[test]
    fn failures_map_to_calm_states() {
        assert_eq!(failure_for(503, None, false), Failure::Unavailable);
        assert_eq!(failure_for(502, None, true), Failure::Unavailable);
        assert_eq!(failure_for(404, None, false), Failure::Unavailable, "a route that isn't deployed yet");
        assert_eq!(failure_for(404, None, true), Failure::NotFound);
        assert_eq!(failure_for(429, Some("60"), false), Failure::Busy(60));
        assert_eq!(failure_for(429, None, false), Failure::Busy(60));
        assert_eq!(failure_for(429, Some("5"), false).code(), "busy");
        assert_eq!(Failure::Unavailable.code(), "unavailable");
        assert!(Failure::Busy(42).message("Search").contains("42 seconds"));
    }

    #[test]
    fn no_mods_are_offered_for_1_8_9_and_everything_else_is_searched() {
        let answer = no_ornithe_mods(&search("mod", "sprint", None, 0, Some("1.8.9"), &[])).expect("1.8.9 mods");
        assert_eq!((answer["unavailable"].as_str(), answer["total"].as_u64()), (Some("ornithe"), Some(0)));
        assert!(answer["hits"].as_array().unwrap().is_empty() && answer["message"].as_str().unwrap().contains("Modrinth"));
        assert!(no_ornithe_mods(&search("resourcepack", "pvp", None, 0, Some("1.8.9"), &[])).is_none(), "1.8.9 resource packs are searched");
        for game in [Some("1.21.1"), Some("26.3"), None] {
            assert!(no_ornithe_mods(&search("mod", "sprint", None, 0, game, &[])).is_none(), "{game:?}");
        }
        // A 1.8.9 Fabric file on CurseForge never fits a 1.8.9 profile: it can't be told apart from a Legacy Fabric one.
        let v = version_of(&json!({ "id": 9, "modId": 3, "fileName": "m.jar", "releaseType": 1, "gameVersions": ["1.8.9", "Fabric"], "downloadUrl": "https://edge.forgecdn.net/9/m.jar", "hashes": [{ "value": "ab", "algo": 1 }] }));
        assert!(!crate::content::fits(&v, "mod", "1.8.9"));
    }

    #[test]
    fn searches_map_kinds_sorts_loaders_and_categories() {
        let path = search_path(&search("mod", "  mini map ", Some("downloads"), 24, Some("1.21.1"), &["423", "x", "425"])).unwrap();
        assert_eq!(path, "/v1/mods/search?gameId=432&classId=6&sortField=6&sortOrder=desc&index=24&pageSize=24&searchFilter=mini+map&modLoaderType=4&gameVersion=1.21.1&categoryIds=%5B423%2C425%5D");
        let packs = search_path(&search("resourcepack", "", None, 0, Some("1.21.1"), &[])).unwrap();
        assert!(packs.contains("classId=12") && packs.contains("sortField=1") && !packs.contains("modLoaderType"), "{packs}");
        let modpacks = search_path(&search("modpack", "", Some("updated"), 0, None, &[])).unwrap();
        assert!(modpacks.contains("classId=4471") && modpacks.contains("modLoaderType=4") && modpacks.contains("sortField=3"));
        assert!(search_path(&search("shader", "", Some("newest"), 0, None, &[])).unwrap().contains("classId=6552&sortField=11"));
        assert!(search_path(&search("datapack", "", Some("follows"), 0, None, &[])).unwrap().contains("classId=6945&sortField=2"));
        assert!(search_path(&search("world", "", None, 0, None, &[])).is_err());
        // The last page CurseForge serves.
        assert!(search_path(&search("mod", "", None, 50_000, None, &[])).unwrap().contains("index=9976&pageSize=24"));
    }

    fn file(id: u64, date: &str, release: u64, versions: &[&str], url: Option<&str>) -> Value {
        json!({
            "id": id, "modId": 100, "displayName": format!("Mod {id}.jar"), "fileName": format!("mod-{id}.jar"), "releaseType": release,
            "fileDate": date, "fileLength": 1234, "downloadCount": 5, "downloadUrl": url, "gameVersions": versions,
            "hashes": [{ "value": "ABCDEF", "algo": 1 }, { "value": "0123", "algo": 2 }],
            "dependencies": [{ "modId": 306612, "relationType": 3 }, { "modId": 7, "relationType": 2 }, { "modId": 8, "relationType": 1 }, { "modId": 9, "relationType": 5 }],
        })
    }

    #[test]
    fn files_become_versions_flint_can_plan_with() {
        let v = version_of(&file(5, "2026-10-01T00:00:00Z", 1, &["1.21.1", "Fabric", "Client", "Java 21"], Some("https://edge.forgecdn.net/files/5/5/mod-5.jar")));
        assert_eq!(v["id"], "cf:5");
        assert_eq!(v["project_id"], "cf:100");
        assert_eq!(v["version_number"], "Mod 5");
        assert_eq!(v["version_type"], "release");
        assert_eq!(v["game_versions"], json!(["1.21.1"]));
        assert_eq!(v["loaders"], json!(["fabric"]));
        assert_eq!(v["files"][0]["hashes"]["sha1"], "abcdef");
        assert_eq!(v["files"][0]["filename"], "mod-5.jar");
        let deps = v["dependencies"].as_array().unwrap();
        assert_eq!(deps[0], json!({ "project_id": "cf:306612", "version_id": null, "dependency_type": "required" }));
        assert_eq!((deps[1]["dependency_type"].as_str(), deps[2]["dependency_type"].as_str(), deps[3]["dependency_type"].as_str()), (Some("optional"), Some("embedded"), Some("incompatible")));
        assert!(downloadable(&v));
        // A pack names no loader; a file its author keeps on CurseForge has no address.
        let pack = version_of(&file(6, "2026-10-01T00:00:00Z", 2, &["1.21.1"], None));
        assert_eq!(pack["loaders"], json!(["minecraft", "iris", "datapack"]));
        assert_eq!(pack["version_type"], "beta");
        assert!(!downloadable(&pack));
        assert!(crate::content::fits(&pack, "resourcepack", "1.21.1") && !crate::content::fits(&pack, "mod", "1.21.1"));
        assert!(crate::content::fits(&v, "mod", "1.21.1") && !crate::content::fits(&v, "mod", "1.21.4"));
        let forge = version_of(&file(7, "2026-10-01T00:00:00Z", 1, &["1.21.1", "NeoForge"], Some("https://edge.forgecdn.net/x.jar")));
        assert!(!crate::content::fits(&forge, "mod", "1.21.1"), "a NeoForge build never goes into a Fabric profile");
    }

    #[test]
    fn the_newest_release_is_chosen_first() {
        let list = json!({ "data": [
            file(1, "2026-01-01T00:00:00Z", 1, &["1.21.1", "Fabric"], Some("https://edge.forgecdn.net/1.jar")),
            file(3, "2026-03-01T00:00:00Z", 2, &["1.21.1", "Fabric"], Some("https://edge.forgecdn.net/3.jar")),
            file(2, "2026-02-01T00:00:00Z", 1, &["1.21.1", "Fabric"], Some("https://edge.forgecdn.net/2.jar")),
        ]});
        let versions = versions_from(&list);
        assert_eq!(versions.iter().map(|v| v["id"].as_str().unwrap()).collect::<Vec<_>>(), ["cf:3", "cf:2", "cf:1"]);
        assert_eq!(crate::modrinth::pick_version(&versions).unwrap()["id"], "cf:2", "a release over a newer beta");
    }

    #[test]
    fn mods_become_projects_and_cards() {
        let m = json!({
            "id": 394468, "classId": 6, "slug": "sodium", "name": "Sodium", "summary": "Fast", "downloadCount": 1.5e8,
            "logo": { "thumbnailUrl": "https://media.forgecdn.net/avatars/thumbnails/1.png" }, "authors": [{ "name": "jellysquid3_" }],
            "links": { "websiteUrl": "https://www.curseforge.com/minecraft/mc-mods/sodium", "sourceUrl": "http://insecure.example" },
            "latestFilesIndexes": [{ "gameVersion": "1.21.1", "fileId": 9, "modLoader": 4 }, { "gameVersion": "1.20.1", "fileId": 8, "modLoader": 6 }, { "gameVersion": "1.21.11", "fileId": 10, "modLoader": 4 }],
            "categories": [{ "name": "Performance" }], "screenshots": [], "allowModDistribution": true,
        });
        let p = project_of(&m);
        assert_eq!((p["id"].as_str(), p["project_type"].as_str(), p["title"].as_str()), (Some("cf:394468"), Some("mod"), Some("Sodium")));
        assert_eq!(p["loaders"], json!(["fabric", "neoforge"]));
        assert_eq!(p["game_versions"], json!(["1.20.1", "1.21.1", "1.21.11"]));
        let hit = hit_of(&m, "mod");
        assert_eq!((hit["projectId"].as_str(), hit["downloads"].as_u64(), hit["source"].as_str()), (Some("cf:394468"), Some(150_000_000), Some("curseforge")));
        assert_eq!(hit["versions"], json!(["1.21.11", "1.21.1", "1.20.1"]));
        assert!(hit["follows"].is_null());
        let pack = project_of(&json!({ "id": 5, "classId": 12, "name": "Faithful" }));
        assert_eq!((pack["project_type"].as_str(), pack["loaders"].clone()), (Some("resourcepack"), json!(["minecraft"])));
        assert_eq!(project_of(&json!({ "id": 6, "classId": 6, "name": "Old" }))["loaders"], json!(["fabric"]), "no loader info: the files decide");
    }

    #[test]
    fn categories_keep_known_classes_only() {
        let answer = json!({ "data": [
            { "id": 6, "name": "Mods", "classId": null, "isClass": true },
            { "id": 423, "name": "Map and Information", "classId": 6, "isClass": false },
            { "id": 4471, "name": "Modpacks", "isClass": true },
            { "id": 4475, "name": "Adventure and RPG", "classId": 4471 },
            { "id": 17, "name": "Worlds", "classId": 17 },
        ]});
        let list = categories_from(&answer);
        assert_eq!(list.len(), 2);
        assert_eq!(list[0], json!({ "name": "4475", "label": "Adventure and RPG", "projectType": "modpack", "header": "categories" }));
        assert_eq!(list[1]["projectType"], "mod");
    }

/// A stand-in proxy on this computer: answers each connection with the next canned response and records the
    /// request lines it was sent.
    fn canned(responses: Vec<&'static str>) -> (String, std::sync::Arc<Mutex<Vec<String>>>) {
        use std::io::{BufRead, BufReader, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let seen = std::sync::Arc::new(Mutex::new(Vec::new()));
        let log = seen.clone();
        std::thread::spawn(move || {
            for response in responses {
                let Ok((mut stream, _)) = listener.accept() else { return };
                let mut reader = BufReader::new(stream.try_clone().unwrap());
                let mut line = String::new();
                reader.read_line(&mut line).unwrap();
                log.lock().unwrap().push(line.trim().to_owned());
                let mut length = 0;
                loop {
                    let mut header = String::new();
                    reader.read_line(&mut header).unwrap();
                    if header.trim().is_empty() {
                        break;
                    }
                    if let Some(v) = header.to_ascii_lowercase().strip_prefix("content-length:") {
                        length = v.trim().parse().unwrap_or(0);
                    }
                }
                let mut body = vec![0u8; length];
                std::io::Read::read_exact(&mut reader, &mut body).unwrap();
                stream.write_all(response.as_bytes()).unwrap();
            }
        });
        (base, seen)
    }

    #[tokio::test]
    async fn the_proxy_s_answers_become_calm_states() {
        let ok = "HTTP/1.1 200 OK
content-type: application/json
content-length: 11
connection: close

{\"data\":[]}";
        let (base, seen) = canned(vec![
            "HTTP/1.1 503 Service Unavailable
content-type: application/json
content-length: 34
connection: close

{\"error\":\"curseforge_unavailable\"}",
            "HTTP/1.1 404 Not Found
content-length: 0
connection: close

",
            "HTTP/1.1 404 Not Found
content-length: 0
connection: close

",
            "HTTP/1.1 502 Bad Gateway
content-length: 0
connection: close

",
            ok,
            ok,
            "HTTP/1.1 429 Too Many Requests
retry-after: 60
content-type: application/json
content-length: 22
connection: close

{\"error\":\"rate-limited\"}",
        ]);
        let cf = CurseForge::at(base.clone());
        let http = crate::core::test_client();
        let ttl = Duration::from_secs(60);
        assert_eq!(cf.get(&http, "/v1/mods/search?gameId=432", ttl, false).await, Err(Failure::Unavailable), "503: no key yet");
        assert_eq!(cf.get(&http, "/v1/categories?gameId=432", ttl, false).await, Err(Failure::Unavailable), "404: not deployed yet");
        assert_eq!(cf.get(&http, "/v1/mods/1", ttl, true).await, Err(Failure::NotFound));
        // 502 once, then a good answer: tried again.
        assert_eq!(cf.get(&http, "/v1/mods/2", ttl, true).await, Ok(json!({ "data": [] })));
        assert_eq!(cf.get(&http, "/v1/mods/2", ttl, true).await, Ok(json!({ "data": [] })), "the second ask is the cache's");
        assert_eq!(cf.post(&http, "/v1/mods/files", &json!({ "fileIds": [1] }), ttl).await, Ok(json!({ "data": [] })));
        assert_eq!(cf.get(&http, "/v1/mods/search?gameId=432&x=1", ttl, false).await, Err(Failure::Busy(60)));
        // While CurseForge asked to wait, nothing more is sent.
        assert!(matches!(cf.get(&http, "/v1/mods/search?gameId=432&x=2", ttl, false).await, Err(Failure::Busy(s)) if s > 50));
        let seen = seen.lock().unwrap().clone();
        assert_eq!(seen.len(), 7, "{seen:?}");
        assert_eq!(seen[0], "GET /v1/mods/search/?gameId=432 HTTP/1.1");
        assert_eq!(seen[1], "GET /v1/categories/?gameId=432 HTTP/1.1");
        assert_eq!(seen[5], "POST /v1/mods/files/ HTTP/1.1");
        // Offline: nothing listens there.
        let gone = CurseForge::at("http://127.0.0.1:9".into());
        assert_eq!(gone.get(&http, "/v1/mods/search?gameId=432", ttl, false).await, Err(Failure::Offline));
    }

    #[test]
    fn the_latest_files_index_tells_whether_to_look_further() {
        let m = json!({ "latestFilesIndexes": [{ "gameVersion": "1.21.1", "fileId": 50, "modLoader": 4 }, { "gameVersion": "1.21.1", "fileId": 70, "modLoader": 1 }] });
        assert!(!may_have_newer(&m, 50, "mod", "1.21.1"), "the installed file is the newest Fabric one");
        assert!(may_have_newer(&m, 40, "mod", "1.21.1"));
        assert!(may_have_newer(&m, 50, "resourcepack", "1.21.1"), "packs count every loader");
        assert!(may_have_newer(&m, 50, "mod", "1.21.4"), "nothing listed for that version: ask for the files");
    }
}
