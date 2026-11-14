use crate::core::*;
use reqwest::{Client, RequestBuilder, Response};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::State;

const PRODUCTION: &str = "https://api.modrinth.com/v2";
const STAGING: &str = "https://staging-api.modrinth.com/v2";
const CACHE_LIMIT: usize = 400;
const MAX_WAIT: Duration = Duration::from_secs(12);

pub const SEARCH_TTL: Duration = Duration::from_secs(120);
pub const PROJECT_TTL: Duration = Duration::from_secs(600);
pub const VERSIONS_TTL: Duration = Duration::from_secs(300);
pub const TAGS_TTL: Duration = Duration::from_secs(24 * 3600);

/// Identifies Flint to Modrinth with the app's own version (overridable at build time with
/// FLINT_RELEASE_VERSION so the header tracks published releases).
pub fn user_agent() -> String {
    let version = option_env!("FLINT_RELEASE_VERSION").unwrap_or(env!("CARGO_PKG_VERSION"));
    format!("IconicDemon/FlintClient/{version} (flintclient.com)")
}

#[derive(Serialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct RateLimit {
    pub limit: Option<u32>,
    pub remaining: Option<u32>,
    pub reset_in: Option<u32>,
    #[serde(skip)]
    pub observed: Option<Instant>,
}

pub struct Modrinth {
    pub base: &'static str,
    pub staging: bool,
    user_agent: String,
    cache: Mutex<HashMap<String, (Instant, Value)>>,
    rate: Mutex<RateLimit>,
}

impl Modrinth {
    /// Staging data is only reachable from development or QA runs, never from a normal install.
    pub fn new(allow_staging: bool) -> Self {
        let staging = allow_staging && std::env::var("FLINT_MODRINTH_API").is_ok_and(|v| v == "staging");
        Self {
            base: if staging { STAGING } else { PRODUCTION },
            staging,
            user_agent: user_agent(),
            cache: Mutex::new(HashMap::new()),
            rate: Mutex::new(RateLimit::default()),
        }
    }

    pub fn url(&self, path: &str) -> String {
        format!("{}{path}", self.base)
    }

    pub fn rate_limit(&self) -> RateLimit {
        let mut rate = self.rate.lock().unwrap().clone();
        if let (Some(reset), Some(seen)) = (rate.reset_in, rate.observed) {
            rate.reset_in = Some(reset.saturating_sub(seen.elapsed().as_secs() as u32));
        }
        rate
    }

    fn record(&self, response: &Response) {
        let header = |name: &str| {
            response
                .headers()
                .get(name)
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u32>().ok())
        };
        let mut rate = self.rate.lock().unwrap();
        rate.limit = header("x-ratelimit-limit").or(rate.limit);
        rate.remaining = header("x-ratelimit-remaining");
        rate.reset_in = header("x-ratelimit-reset");
        rate.observed = Some(Instant::now());
    }

    /// Waits out an exhausted rate-limit window when it is short, otherwise reports it.
    async fn respect_window(&self) -> CmdResult<()> {
        let rate = self.rate_limit();
        if rate.remaining == Some(0) {
            let wait = Duration::from_secs(rate.reset_in.unwrap_or(1).max(1) as u64);
            if wait > MAX_WAIT {
                return Err(format!("Modrinth asked Flint to slow down. Try again in {} seconds.", wait.as_secs()));
            }
            tokio::time::sleep(wait).await;
        }
        Ok(())
    }

    async fn send(&self, build: impl Fn() -> RequestBuilder, context: &str) -> CmdResult<Value> {
        let mut attempt = 0;
        loop {
            self.respect_window().await?;
            let response = build()
                .header(reqwest::header::USER_AGENT, &self.user_agent)
                .header(reqwest::header::ACCEPT, "application/json")
                .send()
                .await
                .map_err(|e| format!("{context}: {}", friendly_network(&e)))?;
            self.record(&response);
            let status = response.status().as_u16();
            if status == 429 && attempt < 2 {
                attempt += 1;
                let reset = self.rate_limit().reset_in.unwrap_or(2).clamp(1, MAX_WAIT.as_secs() as u32);
                tokio::time::sleep(Duration::from_secs(reset as u64)).await;
                continue;
            }
            if status == 429 {
                return Err(format!("{context}: Modrinth is rate limiting requests. Try again in a minute."));
            }
            if (500..600).contains(&status) && attempt < 1 {
                attempt += 1;
                tokio::time::sleep(Duration::from_millis(800)).await;
                continue;
            }
            if status == 404 {
                return Err(format!("{context}: not found on Modrinth"));
            }
            return response_json(response, context).await;
        }
    }

    fn cached(&self, key: &str, ttl: Duration) -> Option<Value> {
        let cache = self.cache.lock().unwrap();
        cache.get(key).filter(|(at, _)| at.elapsed() < ttl).map(|(_, v)| v.clone())
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

    /// GET with caching. When the network fails, a previously cached copy is returned and
    /// flagged as stale rather than pretending it is fresh.
    pub async fn get(&self, http: &Client, path: &str, ttl: Duration, context: &str) -> CmdResult<(Value, bool)> {
        let url = self.url(path);
        if let Some(hit) = self.cached(&url, ttl) {
            return Ok((hit, false));
        }
        match self.send(|| http.get(&url), context).await {
            Ok(value) => {
                self.store(url, &value);
                Ok((value, false))
            }
            Err(error) => match self.cache.lock().unwrap().get(&url) {
                Some((_, stale)) => Ok((stale.clone(), true)),
                None => Err(error),
            },
        }
    }

    pub async fn get_fresh(&self, http: &Client, path: &str, ttl: Duration, context: &str) -> CmdResult<Value> {
        self.get(http, path, ttl, context).await.map(|(v, _)| v)
    }

    pub async fn post(&self, http: &Client, path: &str, body: &Value, context: &str) -> CmdResult<Value> {
        let url = self.url(path);
        self.send(|| http.post(&url).json(body), context).await
    }
}

pub fn encode(value: &str) -> String {
    url::form_urlencoded::byte_serialize(value.as_bytes()).collect()
}

pub fn valid_id(value: &str) -> bool {
    !value.is_empty() && value.len() <= 64 && value.chars().all(|c| c.is_ascii_alphanumeric() || "-_.".contains(c))
}

/// A Minecraft version id such as `26.3` or `25w14a`. It becomes a folder and file name under
/// `minecraft/versions`, so it must start with a letter or digit (never `.` or `..`).
pub fn valid_game_version(value: &str) -> bool {
    value.len() <= 24
        && value.starts_with(|c: char| c.is_ascii_alphanumeric())
        && value.chars().all(|c| c.is_ascii_alphanumeric() || ".-_".contains(c))
}

/// The loader tags Modrinth uses on version files for each kind of content Flint installs.
pub fn loaders_for(kind: &str) -> &'static [&'static str] {
    match kind {
        "mod" | "modpack" => &["fabric"],
        "resourcepack" => &["minecraft"],
        "shader" => &["iris"],
        "datapack" => &["datapack"],
        _ => &[],
    }
}

/// The loader tags a version must carry for `kind` content on `game_version`. Mods for 1.8.9 must be Ornithe builds:
/// Modrinth's `fabric` builds for 1.8.9 are Legacy Fabric ones, which Flint's 1.8.9 can't load (see `ornithe.rs`).
pub fn loaders_on(kind: &str, game_version: &str) -> &'static [&'static str] {
    if kind == "mod" && crate::ornithe::on_ornithe(game_version) {
        &[crate::ornithe::LOADER]
    } else {
        loaders_for(kind)
    }
}

/// Which of a project's versions fit `kind` content on `game_version`: the loader tags (one of them) and the Minecraft
/// version they must list. On 1.8.9, the libraries Ornithe mods require that are published as plain Fabric builds for no
/// Minecraft version in particular (`ornithe::SHARED_LIBRARIES`) fit by their Fabric builds, for any version.
pub fn version_filter<'a>(project_id: &str, kind: &str, game_version: &'a str) -> (&'static [&'static str], Option<&'a str>) {
    if kind == "mod" && crate::ornithe::on_ornithe(game_version) && crate::ornithe::shared_library(project_id) {
        (&["fabric"], None)
    } else {
        (loaders_on(kind, game_version), Some(game_version))
    }
}

pub fn content_dir(kind: &str) -> Option<&'static str> {
    match kind {
        "mod" => Some("mods"),
        "resourcepack" => Some("resourcepacks"),
        "shader" => Some("shaderpacks"),
        _ => None,
    }
}

pub fn version_query(loaders: &[&str], game_version: Option<&str>) -> String {
    let mut parts = Vec::new();
    if !loaders.is_empty() {
        parts.push(format!("loaders={}", encode(&json!(loaders).to_string())));
    }
    if let Some(v) = game_version {
        parts.push(format!("game_versions={}", encode(&json!([v]).to_string())));
    }
    if parts.is_empty() {
        String::new()
    } else {
        format!("?{}", parts.join("&"))
    }
}

pub fn primary_file(version: &Value) -> Option<&Value> {
    let files = version["files"].as_array()?;
    files.iter().find(|f| f["primary"].as_bool() == Some(true)).or_else(|| files.first())
}

/// Picks the newest release build, falling back to the newest beta or alpha only when no release exists.
pub fn pick_version(list: &[Value]) -> Option<Value> {
    list.iter()
        .find(|v| v["version_type"] == "release")
        .or_else(|| list.iter().find(|v| v["version_type"] == "beta"))
        .or_else(|| list.first())
        .cloned()
}

pub(crate) fn trim_version(v: &Value) -> Value {
    let changelog: String = v["changelog"].as_str().unwrap_or_default().chars().take(4000).collect();
    json!({
        "id": v["id"],
        "projectId": v["project_id"],
        "name": v["name"],
        "versionNumber": v["version_number"],
        "versionType": v["version_type"],
        "gameVersions": v["game_versions"],
        "loaders": v["loaders"],
        "datePublished": v["date_published"],
        "downloads": v["downloads"],
        "changelog": changelog,
        "dependencies": v["dependencies"],
        "files": v["files"].as_array().map(|files| files.iter().map(|f| json!({
            "filename": f["filename"],
            "size": f["size"],
            "primary": f["primary"],
        })).collect::<Vec<_>>()),
    })
}

// ---------- commands ----------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LibrarySearch {
    pub(crate) kind: String,
    #[serde(default)]
    pub(crate) query: String,
    #[serde(default)]
    pub(crate) index: Option<String>,
    #[serde(default)]
    pub(crate) offset: u32,
    #[serde(default)]
    pub(crate) limit: Option<u32>,
    #[serde(default)]
    pub(crate) game_version: Option<String>,
    #[serde(default)]
    pub(crate) loader: Option<String>,
    #[serde(default)]
    pub(crate) categories: Vec<String>,
    #[serde(default)]
    pub(crate) client_only: bool,
}

pub fn search_path(params: &LibrarySearch) -> CmdResult<String> {
    let mut facets: Vec<Value> = Vec::new();
    match params.kind.as_str() {
        "mod" => facets.push(json!(["project_type:mod"])),
        "modpack" => facets.push(json!(["project_type:modpack"])),
        "resourcepack" => facets.push(json!(["project_type:resourcepack"])),
        "shader" => facets.push(json!(["project_type:shader"])),
        "datapack" => facets.push(json!(["all_project_types:datapack"])),
        _ => return Err("Unknown content type".into()),
    }
    let game = params.game_version.as_deref().filter(|v| valid_game_version(v));
    match (params.kind.as_str(), params.loader.as_deref()) {
        // A 1.8.9 Fabric profile runs Fabric from Ornithe: Ornithe builds only (Modrinth's 1.8.9 `fabric` ones are Legacy
        // Fabric's). A project tagged `ornithe` for another version only is still listed; its versions decide at install.
        ("mod", Some("fabric")) if game.is_some_and(crate::ornithe::on_ornithe) => facets.push(json!([format!("categories:{}", crate::ornithe::LOADER)])),
        ("mod" | "modpack", Some("fabric")) => facets.push(json!(["categories:fabric"])),
        ("shader", Some("fabric")) => facets.push(json!(["categories:iris"])),
        _ => {}
    }
    if let Some(version) = game {
        facets.push(json!([format!("versions:{version}")]));
    }
    if params.client_only && params.kind == "mod" {
        facets.push(json!(["client_side:required", "client_side:optional"]));
    }
    for category in params.categories.iter().filter(|c| valid_id(c)).take(6) {
        facets.push(json!([format!("categories:{category}")]));
    }
    let index = params.index.as_deref().filter(|i| ["relevance", "downloads", "follows", "newest", "updated"].contains(i)).unwrap_or("relevance");
    let query: String = params.query.trim().chars().take(120).collect();
    let limit = params.limit.unwrap_or(20).clamp(1, 50);
    Ok(format!(
        "/search?query={}&limit={limit}&offset={}&index={index}&facets={}",
        encode(&query),
        params.offset.min(10_000),
        encode(&Value::Array(facets).to_string())
    ))
}

#[tauri::command]
pub async fn library_search(state: State<'_, AppState>, params: LibrarySearch) -> CmdResult<Value> {
    search(&state, &params).await
}

/// A Library search on Modrinth (the Library's page, and the in-game Library through the game link).
pub async fn search(state: &AppState, params: &LibrarySearch) -> CmdResult<Value> {
    let path = search_path(params)?;
    let (value, stale) = state.modrinth.get(&state.http, &path, SEARCH_TTL, "Modrinth search failed").await?;
    let hits: Vec<Value> = value["hits"]
        .as_array()
        .map(|hits| {
            hits.iter()
                .map(|h| {
                    json!({
                        "projectId": h["project_id"],
                        "slug": h["slug"],
                        "title": h["title"],
                        "description": h["description"],
                        "iconUrl": h["icon_url"],
                        "author": h["author"],
                        "downloads": h["downloads"],
                        "follows": h["follows"],
                        "categories": h["display_categories"].as_array().or(h["categories"].as_array()),
                        "versions": h["versions"].as_array().map(|v| v.iter().rev().take(6).cloned().collect::<Vec<_>>()),
                        "latestVersion": h["latest_version"],
                        "projectType": h["project_type"],
                        "clientSide": h["client_side"],
                        "serverSide": h["server_side"],
                        "dateModified": h["date_modified"],
                        "gallery": h["featured_gallery"].as_str().or(h["gallery"].as_array().and_then(|g| g.first()).and_then(Value::as_str)),
                        "color": h["color"],
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    Ok(json!({
        "hits": hits,
        "offset": value["offset"],
        "limit": value["limit"],
        "total": value["total_hits"],
        "stale": stale,
        "staging": state.modrinth.staging,
        "rateLimit": state.modrinth.rate_limit(),
    }))
}

#[tauri::command]
pub async fn library_project(state: State<'_, AppState>, id: String) -> CmdResult<Value> {
    // A CurseForge project (`cf:<modId>`) opens in the same drawer.
    if let Some(mod_id) = crate::curseforge::parse_ref(&id) {
        return crate::curseforge::library_project(&state, mod_id).await;
    }
    if !valid_id(&id) {
        return Err("Invalid project".into());
    }
    let (project, stale) = state
        .modrinth
        .get(&state.http, &format!("/project/{id}"), PROJECT_TTL, "Couldn't load this project")
        .await?;
    let members = state
        .modrinth
        .get_fresh(&state.http, &format!("/project/{id}/members"), PROJECT_TTL, "Couldn't load the project team")
        .await
        .unwrap_or_else(|_| json!([]));
    let members: Vec<Value> = members
        .as_array()
        .map(|list| {
            list.iter()
                .take(12)
                .map(|m| json!({ "username": m["user"]["username"], "role": m["role"], "avatarUrl": m["user"]["avatar_url"], "owner": m["is_owner"] }))
                .collect()
        })
        .unwrap_or_default();
    let owner = members
        .iter()
        .find(|m| m["owner"].as_bool() == Some(true))
        .or_else(|| members.first())
        .and_then(|m| m["username"].as_str())
        .map(str::to_owned);
    let body: String = project["body"].as_str().unwrap_or_default().chars().take(60_000).collect();
    Ok(json!({
        "id": project["id"],
        "slug": project["slug"],
        "title": project["title"],
        "description": project["description"],
        "body": body,
        "iconUrl": project["icon_url"],
        "projectType": project["project_type"],
        "categories": project["categories"],
        "additionalCategories": project["additional_categories"],
        "loaders": project["loaders"],
        "gameVersions": project["game_versions"],
        "clientSide": project["client_side"],
        "serverSide": project["server_side"],
        "downloads": project["downloads"],
        "followers": project["followers"],
        "license": project["license"],
        "published": project["published"],
        "updated": project["updated"],
        "gallery": project["gallery"].as_array().map(|g| g.iter().take(12).map(|i| json!({ "url": i["url"], "title": i["title"], "featured": i["featured"] })).collect::<Vec<_>>()),
        "sourceUrl": project["source_url"],
        "issuesUrl": project["issues_url"],
        "wikiUrl": project["wiki_url"],
        "color": project["color"],
        "organization": project["organization"],
        "author": owner,
        "members": members,
        "stale": stale,
    }))
}

#[tauri::command]
pub async fn library_versions(
    state: State<'_, AppState>,
    id: String,
    kind: String,
    game_version: Option<String>,
) -> CmdResult<Value> {
    let game_version = game_version.filter(|v| valid_game_version(v));
    if let Some(mod_id) = crate::curseforge::parse_ref(&id) {
        return crate::curseforge::library_versions(&state, mod_id, &kind, game_version.as_deref()).await;
    }
    if !valid_id(&id) {
        return Err("Invalid project".into());
    }
    let (loaders, game) = match game_version.as_deref() {
        Some(game) => version_filter(&id, &kind, game),
        None => (loaders_for(&kind), None),
    };
    let path = format!("/project/{id}/version{}", version_query(loaders, game));
    let (list, stale) = state.modrinth.get(&state.http, &path, VERSIONS_TTL, "Couldn't load versions").await?;
    let versions: Vec<Value> = list.as_array().map(|l| l.iter().take(60).map(trim_version).collect()).unwrap_or_default();
    Ok(json!({ "versions": versions, "stale": stale }))
}

#[tauri::command]
pub async fn library_tags(state: State<'_, AppState>) -> CmdResult<Value> {
    let categories = state
        .modrinth
        .get_fresh(&state.http, "/tag/category", TAGS_TTL, "Couldn't load categories")
        .await?;
    let list: Vec<Value> = categories
        .as_array()
        .map(|l| {
            l.iter()
                .filter(|c| c["header"] != "resolutions" || c["project_type"] == "resourcepack")
                .map(|c| json!({ "name": c["name"], "projectType": c["project_type"], "header": c["header"] }))
                .collect()
        })
        .unwrap_or_default();
    Ok(json!({ "categories": list }))
}

#[tauri::command]
pub fn library_status(state: State<'_, AppState>) -> Value {
    json!({
        "base": state.modrinth.base,
        "staging": state.modrinth.staging,
        "userAgent": user_agent(),
        "rateLimit": state.modrinth.rate_limit(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn search(kind: &str, game: Option<&str>, loader: Option<&str>) -> String {
        let params = LibrarySearch {
            kind: kind.into(),
            query: "sprint".into(),
            index: None,
            offset: 0,
            limit: None,
            game_version: game.map(str::to_owned),
            loader: loader.map(str::to_owned),
            categories: Vec::new(),
            client_only: kind == "mod",
        };
        let path = search_path(&params).unwrap();
        let facets = path.split("facets=").nth(1).unwrap();
        url::form_urlencoded::parse(format!("f={facets}").as_bytes()).next().unwrap().1.into_owned()
    }

    #[test]
    fn a_1_8_9_fabric_profile_searches_ornithe_builds_only() {
        let facets = search("mod", Some("1.8.9"), Some("fabric"));
        assert!(facets.contains(r#"["categories:ornithe"]"#) && facets.contains(r#"["versions:1.8.9"]"#), "{facets}");
        assert!(!facets.contains("categories:fabric") && !facets.contains("legacy-fabric"), "{facets}");
        // Every other version, and what isn't a mod, is as it was.
        for game in ["26.3", "1.21.11", "1.21.1", "1.19.2"] {
            let facets = search("mod", Some(game), Some("fabric"));
            assert!(facets.contains(r#"["categories:fabric"]"#) && !facets.contains("ornithe"), "{game}: {facets}");
        }
        assert!(search("resourcepack", Some("1.8.9"), Some("fabric")).contains(r#"["project_type:resourcepack"]"#));
        assert!(!search("resourcepack", Some("1.8.9"), Some("fabric")).contains("ornithe"));
        assert!(!search("mod", Some("1.8.9"), Some("vanilla")).contains("categories:"), "a vanilla profile names no loader");
    }

    #[test]
    fn versions_for_1_8_9_mods_are_ornithe_ones() {
        assert_eq!(loaders_on("mod", "1.8.9"), ["ornithe"]);
        assert_eq!(loaders_on("mod", "26.3"), ["fabric"]);
        assert_eq!(loaders_on("resourcepack", "1.8.9"), ["minecraft"]);
        assert_eq!(loaders_on("shader", "1.8.9"), ["iris"]);
        assert_eq!(version_filter("AANobbMI", "mod", "1.8.9"), (&["ornithe"][..], Some("1.8.9")));
        // Fabric Language Kotlin lists no Minecraft version for 1.8.9; its Fabric builds hold no Minecraft code.
        assert_eq!(version_filter("Ha28R6CL", "mod", "1.8.9"), (&["fabric"][..], None));
        assert_eq!(version_filter("Ha28R6CL", "mod", "1.21.11"), (&["fabric"][..], Some("1.21.11")), "elsewhere as before");
        assert_eq!(version_query(loaders_on("mod", "1.8.9"), Some("1.8.9")), "?loaders=%5B%22ornithe%22%5D&game_versions=%5B%221.8.9%22%5D");
    }

    #[test]
    fn game_versions_are_plain_names() {
        for good in ["26.3", "1.21.11", "25w14a", "1.21.11-pre1", "26.1_rc"] {
            assert!(valid_game_version(good), "{good}");
        }
        // A modpack's version becomes `minecraft/versions/<id>/<id>.json`, so it can't be a dot path.
        for bad in ["", ".", "..", "...", ".26", "-x", "26/3", "26\\3", "26:3", &"9".repeat(25)] {
            assert!(!valid_game_version(bad), "{bad:?}");
        }
    }
}
