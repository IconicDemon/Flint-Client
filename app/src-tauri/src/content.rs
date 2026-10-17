use crate::core::*;
use crate::data::{find_profile, Profile, SUPPORTED_VERSIONS};
use crate::jobs::{self, download_verified, Expected, Fetch};
use crate::modrinth::*;
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha512};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use tauri::{AppHandle, Manager, State};

pub const MODRINTH_CDN: &[&str] = &["cdn.modrinth.com"];

/// Flint's default performance set: established, open-source, client-side Fabric mods. Each is
/// installed only if Modrinth has a build for the profile's Minecraft version.
pub const PERFORMANCE_SET: [(&str, &str); 6] = [
    ("sodium", "Faster chunk rendering"),
    ("lithium", "Lighter game logic"),
    ("ferrite-core", "Lower memory use"),
    ("immediatelyfast", "Faster HUD and text rendering"),
    ("entityculling", "Skips drawing hidden entities"),
    ("dynamic-fps", "Throttles the game while it's in the background"),
];
/// The mod ids the performance set's projects declare in their fabric.mod.json, as read from the
/// real jars. A jar already in the mods folder with one of these ids is that project, whatever its
/// file is called. Fabric API (a dependency) isn't here: one the player added may be too old for
/// the set, and with a second copy Fabric simply loads the newer one.
const PERFORMANCE_MOD_IDS: [(&str, &[&str]); 6] = [
    ("sodium", &["sodium"]),
    ("lithium", &["lithium"]),
    ("ferrite-core", &["ferritecore"]),
    ("immediatelyfast", &["immediatelyfast"]),
    ("entityculling", &["entityculling"]),
    ("dynamic-fps", &["dynamic_fps"]),
];
const IRIS: &str = "YL57xq9U";
const DISABLED: &str = ".disabled";
/// Where an install moves a same-named jar that Flint didn't put there, inside `mods`. Fabric only
/// loads jars at the top of `mods` (and skips hidden names), so a jar kept here is inert.
const REPLACED: &str = ".flint-replaced";

fn default_kind() -> String {
    "mod".into()
}

/// One piece of content Flint installed into a profile. Stored per profile in `flint-content.json`.
#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct ContentEntry {
    #[serde(default = "default_kind")]
    pub kind: String,
    pub project_id: String,
    pub version_id: String,
    #[serde(default)]
    pub slug: String,
    pub title: String,
    #[serde(default)]
    pub author: Option<String>,
    #[serde(default)]
    pub version_number: String,
    pub file_name: String,
    #[serde(default)]
    pub sha512: String,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub source: String,
    #[serde(default)]
    pub reason: Option<String>,
    #[serde(default)]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub installed_at: u64,
    #[serde(default)]
    pub world: Option<String>,
    #[serde(default)]
    pub requires: Vec<String>,
    #[serde(default)]
    pub previous: Option<Box<ContentEntry>>,
}

fn manifest_path(state: &AppState, id: &str) -> PathBuf {
    state.paths.profile_dir(id).join("flint-content.json")
}

/// Reads the profile's content manifest, migrating the older mods-only manifest on first use.
pub fn load(state: &AppState, id: &str) -> Vec<ContentEntry> {
    let path = manifest_path(state, id);
    if path.exists() {
        return read_json(&path);
    }
    read_json(&state.paths.profile_dir(id).join("flint-mods.json"))
}

fn save(state: &AppState, id: &str, entries: &[ContentEntry]) -> CmdResult<()> {
    write_json(&manifest_path(state, id), &entries)
}

/// Slugs of the mods and resource packs Flint installed in a profile, for Flint AI's context.
pub fn installed_slugs(state: &AppState, profile_id: &str) -> Vec<String> {
    load(state, profile_id).into_iter().filter(|m| matches!(m.kind.as_str(), "mod" | "resourcepack")).map(|m| m.slug).take(40).collect()
}

pub fn target_profile(state: &AppState, profile_id: Option<&str>, game_version: Option<&str>) -> CmdResult<Profile> {
    match (profile_id, game_version) {
        (Some(id), _) => find_profile(state, id),
        (None, Some(version)) if SUPPORTED_VERSIONS.contains(&version) && version != "1.8.9" => Ok(Profile {
            game_version: version.to_owned(),
            loader: "fabric".into(),
            ..Profile::default()
        }),
        _ => Err("Choose a Fabric version first.".into()),
    }
}

fn ensure_not_running(state: &AppState, id: &str) -> CmdResult<()> {
    if state.running.lock().unwrap().contains_key(id) {
        return Err("Close Minecraft first. Flint doesn't change content while the game is running.".into());
    }
    Ok(())
}

fn safe_name(name: &str) -> CmdResult<&str> {
    let name = name.trim();
    if name.is_empty() || name.len() > 180 || name.contains(['/', '\\', ':']) || name.starts_with('.') || name == ".." {
        return Err("Unsafe file name".into());
    }
    Ok(name)
}

fn world_dir(state: &AppState, profile_id: &str, world: &str) -> CmdResult<PathBuf> {
    let world = safe_name(world)?;
    let dir = state.paths.profile_dir(profile_id).join("saves").join(world);
    if !dir.join("level.dat").is_file() {
        return Err("That world no longer exists in this profile.".into());
    }
    Ok(dir)
}

fn kind_dir(state: &AppState, profile_id: &str, kind: &str, world: Option<&str>) -> CmdResult<PathBuf> {
    if kind == "datapack" {
        let world = world.ok_or("Choose the world this data pack belongs to.")?;
        return Ok(world_dir(state, profile_id, world)?.join("datapacks"));
    }
    let sub = content_dir(kind).ok_or("Unknown content type")?;
    Ok(state.paths.profile_dir(profile_id).join(sub))
}

fn max_bytes(kind: &str) -> u64 {
    match kind {
        "mod" => 64 * 1024 * 1024,
        "resourcepack" => 512 * 1024 * 1024,
        _ => 128 * 1024 * 1024,
    }
}

fn file_fits(kind: &str, name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    match kind {
        "mod" => lower.ends_with(".jar"),
        _ => lower.ends_with(".zip"),
    }
}

fn project_kind(project: &Value) -> String {
    let loaders = project["loaders"].as_array().cloned().unwrap_or_default();
    match project["project_type"].as_str() {
        Some("mod") if !loaders.iter().any(|l| l == "fabric") && loaders.iter().any(|l| l == "datapack") => "datapack".into(),
        Some(t) => t.to_owned(),
        None => "mod".into(),
    }
}

// ---------- planning ----------

struct Pending {
    project: Value,
    kind: String,
    version_id: Option<String>,
    source: String,
    reason: Option<String>,
    required_by: Option<String>,
    required: bool,
}

async fn project(state: &AppState, id: &str) -> CmdResult<Value> {
    if !valid_id(id) {
        return Err("Invalid project".into());
    }
    state.modrinth.get_fresh(&state.http, &format!("/project/{id}"), PROJECT_TTL, "Couldn't load a project from Modrinth").await
}

async fn version(state: &AppState, id: &str) -> CmdResult<Value> {
    if !valid_id(id) {
        return Err("Invalid version".into());
    }
    state.modrinth.get_fresh(&state.http, &format!("/version/{id}"), VERSIONS_TTL, "Couldn't load a version from Modrinth").await
}

fn fits(version: &Value, kind: &str, game_version: &str) -> bool {
    let loaders = loaders_for(kind);
    let game = version["game_versions"].as_array().is_some_and(|v| v.iter().any(|x| x.as_str() == Some(game_version)));
    let loader = version["loaders"].as_array().is_some_and(|v| v.iter().any(|x| x.as_str().is_some_and(|l| loaders.contains(&l))));
    game && loader
}

async fn best_version(state: &AppState, project_id: &str, kind: &str, profile: &Profile) -> CmdResult<Option<Value>> {
    let path = format!("/project/{project_id}/version{}", version_query(loaders_for(kind), Some(&profile.game_version)));
    let list = state.modrinth.get_fresh(&state.http, &path, VERSIONS_TTL, "Couldn't check versions on Modrinth").await?;
    Ok(pick_version(list.as_array().map(Vec::as_slice).unwrap_or_default()))
}

fn plan_item(p: &Pending, version: Option<&Value>, status: &str, message: Option<String>) -> Value {
    let file = version.and_then(primary_file);
    json!({
        "projectId": p.project["id"],
        "slug": p.project["slug"],
        "title": p.project["title"],
        "author": Value::Null,
        "description": p.project["description"],
        "iconUrl": p.project["icon_url"],
        "clientSide": p.project["client_side"],
        "kind": p.kind,
        "versionId": version.map(|v| v["id"].clone()),
        "versionNumber": version.map(|v| v["version_number"].clone()),
        "versionType": version.map(|v| v["version_type"].clone()),
        "datePublished": version.map(|v| v["date_published"].clone()),
        "fileName": file.map(|f| f["filename"].clone()),
        "size": file.map(|f| f["size"].clone()),
        "source": p.source,
        "reason": p.reason,
        "requiredBy": p.required_by,
        "required": p.required,
        "status": status,
        "message": message,
    })
}

async fn resolve(state: &AppState, profile: &Profile, profile_id: Option<&str>, roots: Vec<Pending>) -> CmdResult<Value> {
    let installed = profile_id.map(|id| load(state, id)).unwrap_or_default();
    let installed_ids: HashSet<String> = installed.iter().map(|e| format!("{}:{}", e.kind, e.project_id)).collect();
    let installed_mods: HashMap<String, String> = installed.iter().filter(|e| e.kind == "mod").map(|e| (e.project_id.clone(), e.title.clone())).collect();
    let fabric = profile.loader == "fabric";

    let mut items: Vec<Value> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    // Every project in the plan that requires each project (`seen` keeps only the first one to ask for it).
    let mut required_by_all: HashMap<String, Vec<String>> = HashMap::new();
    let mut incompatible: HashMap<String, String> = HashMap::new();
    let mut queue = roots;

    if fabric && queue.iter().any(|p| p.kind == "shader") && !installed_mods.contains_key(IRIS) {
        if let Ok(iris) = project(state, IRIS).await {
            let by = queue.iter().find(|p| p.kind == "shader").and_then(|p| p.project["title"].as_str()).map(str::to_owned);
            queue.push(Pending { project: iris, kind: "mod".into(), version_id: None, source: "dependency".into(), reason: Some("Shader packs need the Iris shader loader".into()), required_by: by, required: true });
        }
    }

    let mut depth = 0;
    while !queue.is_empty() && depth < 6 {
        let mut next: Vec<Pending> = Vec::new();
        for p in queue.drain(..) {
            let pid = p.project["id"].as_str().unwrap_or_default().to_owned();
            if let (Some(by), "dependency", false) = (&p.required_by, p.source.as_str(), pid.is_empty()) {
                let parents = required_by_all.entry(format!("{}:{pid}", p.kind)).or_default();
                if !parents.contains(by) {
                    parents.push(by.clone());
                }
            }
            if pid.is_empty() || !seen.insert(format!("{}:{pid}", p.kind)) {
                continue;
            }
            let loaders: Vec<String> = p.project["loaders"].as_array().map(|l| l.iter().filter_map(|x| x.as_str().map(str::to_owned)).collect()).unwrap_or_default();
            let needed = loaders_for(&p.kind);

            if installed_ids.contains(&format!("{}:{pid}", p.kind)) {
                items.push(plan_item(&p, None, "installed", Some("Already in this profile".into())));
                continue;
            }
            if (p.kind == "mod" || p.kind == "shader") && !fabric {
                items.push(plan_item(&p, None, "unavailable", Some("This profile runs vanilla Minecraft, which can't load mods or shaders.".into())));
                continue;
            }
            if !loaders.iter().any(|l| needed.contains(&l.as_str())) {
                if p.source == "dependency" && p.kind == "mod" {
                    items.push(plan_item(&p, None, "not-needed", Some("Only used by the Forge or NeoForge build, so Fabric doesn't need it".into())));
                } else {
                    let what = match p.kind.as_str() {
                        "mod" => "a Fabric build",
                        "shader" => "an Iris-compatible build",
                        "datapack" => "a data pack build",
                        _ => "a compatible build",
                    };
                    items.push(plan_item(&p, None, "unavailable", Some(format!("Modrinth has no {what} of this project"))));
                }
                continue;
            }
            if p.kind == "mod" && p.project["client_side"] == "unsupported" {
                items.push(plan_item(&p, None, "unavailable", Some("Server-only mod; it does nothing in a client profile".into())));
                continue;
            }

            let chosen = match &p.version_id {
                Some(id) => match version(state, id).await {
                    Ok(v) if fits(&v, &p.kind, &profile.game_version) => Some(v),
                    Ok(_) => None,
                    Err(_) => None,
                },
                None => None,
            };
            let chosen = match chosen {
                Some(v) => Some(v),
                None => best_version(state, &pid, &p.kind, profile).await?,
            };
            let Some(v) = chosen else {
                items.push(plan_item(&p, None, "unavailable", Some(format!("Not available for Minecraft {}", profile.game_version))));
                continue;
            };
            if primary_file(&v).and_then(|f| f["filename"].as_str()).is_none_or(|n| !file_fits(&p.kind, n)) {
                items.push(plan_item(&p, None, "unavailable", Some("The published file isn't a type Flint can install here".into())));
                continue;
            }
            for dep in v["dependencies"].as_array().into_iter().flatten() {
                let dep_project = dep["project_id"].as_str();
                let dep_version = dep["version_id"].as_str();
                let dep_project = match (dep_project, dep_version) {
                    (Some(id), _) => Some(id.to_owned()),
                    (None, Some(vid)) => version(state, vid).await.ok().and_then(|x| x["project_id"].as_str().map(str::to_owned)),
                    _ => None,
                };
                let Some(dep_id) = dep_project else { continue };
                match dep["dependency_type"].as_str() {
                    Some(kind @ ("required" | "optional")) => {
                        if installed_mods.contains_key(&dep_id) {
                            continue;
                        }
                        if let Ok(dp) = project(state, &dep_id).await {
                            let dep_kind = project_kind(&dp);
                            next.push(Pending {
                                kind: if dep_kind == "datapack" { p.kind.clone() } else { dep_kind },
                                project: dp,
                                version_id: dep_version.map(str::to_owned),
                                source: if kind == "required" { "dependency".into() } else { "optional".into() },
                                reason: None,
                                required_by: p.project["title"].as_str().map(str::to_owned),
                                required: kind == "required" && p.required,
                            });
                        }
                    }
                    Some("incompatible") => {
                        incompatible.insert(dep_id, p.project["title"].as_str().unwrap_or_default().to_owned());
                    }
                    _ => {}
                }
            }
            items.push(plan_item(&p, Some(&v), "ok", None));
        }
        queue = next.into_iter().filter(|p| p.source != "optional" || depth < 1).collect();
        depth += 1;
    }

    for item in items.iter_mut() {
        let id = item["projectId"].as_str().unwrap_or_default().to_owned();
        if let Some(parents) = required_by_all.get(&format!("{}:{id}", item["kind"].as_str().unwrap_or_default())) {
            item["requiredByAll"] = json!(parents);
        }
        if let Some(by) = incompatible.get(&id) {
            item["status"] = json!("conflict");
            item["message"] = json!(format!("Doesn't work together with {by}"));
        }
    }
    for (id, by) in &incompatible {
        if let Some(title) = installed_mods.get(id) {
            if let Some(item) = items.iter_mut().find(|i| i["title"].as_str() == Some(by.as_str())) {
                item["status"] = json!("conflict");
                item["message"] = json!(format!("Doesn't work together with {title}, which is already installed"));
            }
        }
    }
    let blockers: Vec<String> = items
        .iter()
        .filter(|i| i["required"].as_bool() == Some(true) && matches!(i["status"].as_str(), Some("unavailable" | "conflict")))
        .map(|i| format!("{}: {}", i["title"].as_str().unwrap_or("A project"), i["message"].as_str().unwrap_or("unavailable")))
        .collect();
    Ok(json!({
        "items": items,
        "blockers": blockers,
        "gameVersion": profile.game_version,
        "loader": profile.loader,
    }))
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PlanRequest {
    kind: String,
    project: String,
    #[serde(default)]
    version_id: Option<String>,
    #[serde(default)]
    source: Option<String>,
    #[serde(default)]
    reason: Option<String>,
}

#[tauri::command]
pub async fn content_plan(state: State<'_, AppState>, profile_id: String, requests: Vec<PlanRequest>) -> CmdResult<Value> {
    let profile = find_profile(&state, &profile_id)?;
    let mut roots = Vec::new();
    for r in requests.into_iter().take(12) {
        if !matches!(r.kind.as_str(), "mod" | "resourcepack" | "shader" | "datapack") {
            return Err("That kind of content is installed differently.".into());
        }
        let p = project(&state, &r.project).await?;
        roots.push(Pending {
            project: p,
            kind: r.kind,
            version_id: r.version_id.filter(|v| valid_id(v)),
            source: match r.source.as_deref() {
                Some("assistant") => "assistant".into(),
                Some("optional") => "optional".into(),
                _ => "user".into(),
            },
            reason: r.reason.map(|x| x.chars().take(200).collect()),
            required_by: None,
            required: true,
        });
    }
    resolve(&state, &profile, Some(&profile_id), roots).await
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ResolveRequest {
    #[serde(default)]
    project: Option<String>,
    #[serde(default)]
    query: Option<String>,
    source: String,
    #[serde(default)]
    reason: Option<String>,
    /// "mod" (the default), "resourcepack" or "shader".
    #[serde(default)]
    kind: Option<String>,
}

/// The kind of content a resolve request asks for, or `None` for kinds it can't resolve.
fn resolve_kind(kind: Option<&str>) -> Option<&'static str> {
    match kind {
        None | Some("mod") => Some("mod"),
        Some("resourcepack") => Some("resourcepack"),
        Some("shader") => Some("shader"),
        _ => None,
    }
}

/// Modrinth search facets for a resolve request: client-side Fabric mods, resource packs (which
/// need no loader and work on vanilla profiles too), or shader packs Iris can load (as the Library
/// searches them).
fn search_facets(kind: &str, game_version: &str) -> Value {
    let version = format!("versions:{game_version}");
    match kind {
        "resourcepack" => json!([["project_type:resourcepack"], [version]]),
        "shader" => json!([["project_type:shader"], ["categories:iris"], [version]]),
        _ => json!([["project_type:mod"], ["categories:fabric"], [version], ["client_side:required", "client_side:optional"]]),
    }
}

/// Why a request was left out before searching: an unknown kind, or a mod or shader pack for a vanilla profile.
fn resolve_refusal(kind: Option<&str>, fabric: bool) -> Option<&'static str> {
    match kind {
        None => Some("That kind of content is installed differently."),
        Some("mod") if !fabric => Some("Mods need a Fabric profile"),
        Some("shader") if !fabric => Some("Shader packs need a Fabric profile with Iris"),
        _ => None,
    }
}

/// Words that say how much, how good or what type, not what a project does. A search made only of
/// these ("boosting", "useful", "lets") matches nothing, because any hit would be a coincidence.
const VAGUE_WORDS: &[&str] = &[
    "a", "an", "the", "and", "or", "for", "with", "of", "to", "in", "on", "at", "by", "my", "me", "it", "is", "that", "this", "your", "mod", "mods",
    "modpack", "pack", "packs", "resource", "resourcepack", "shader", "shaders", "shaderpack", "minecraft", "fabric", "java", "client", "best", "good",
    "great", "nice", "cool", "awesome", "top", "useful", "helpful", "handy", "better", "boost", "booster", "improve", "enhance", "let", "lets",
    "want", "need", "some", "more", "less", "very", "really", "simple", "easy", "new", "small", "smaller", "big", "bigger", "low", "lower", "high",
    "higher", "no", "not", "without", "make", "makes", "add", "adds", "get", "install", "just", "also", "plus", "any", "all", "stuff", "thing",
    "feature", "lightweight", "fast", "faster", "look", "looking", "game", "feel",
];

/// A light stemmer shared with the app's planner (`src/lib/assistPlan.ts`, same rules): plurals,
/// "-ing", "-ed", "-er" and a final "e", so "boosting", "lowers" and "smaller" meet "boost", "low" and "small".
fn stem(word: &str) -> String {
    let mut s = match word {
        "armour" => "armor",
        "colour" => "color",
        "grey" => "gray",
        other => other,
    }
    .to_owned();
    let n = s.len();
    if n > 5 && s.ends_with("ing") {
        s.truncate(n - 3);
    } else if n > 4 && s.ends_with("ies") {
        s.truncate(n - 3);
        s.push('y');
    } else if n > 4 && ["ses", "xes", "zes", "ches", "shes"].iter().any(|e| s.ends_with(e)) {
        s.truncate(n - 2);
    } else if n > 3 && s.ends_with('s') && !s.ends_with("ss") && !s.ends_with("us") && !s.ends_with("is") {
        s.truncate(n - 1);
    } else if n > 5 && s.ends_with("ed") {
        s.truncate(n - 2);
    }
    let n = s.len();
    if n > 4 && s.ends_with("er") {
        s.truncate(n - 2);
    } else if n > 4 && s.ends_with('e') {
        s.truncate(n - 1);
    }
    s
}

/// Lowercase words of two or more letters or digits.
fn tokens(text: &str) -> Vec<String> {
    text.split(|c: char| !c.is_alphanumeric()).filter(|w| w.chars().count() >= 2).map(str::to_lowercase).collect()
}

fn vague(word: &str) -> bool {
    let stemmed = stem(word);
    VAGUE_WORDS.iter().any(|v| *v == word || stem(v) == stemmed)
}

/// The words of a search that say what is wanted, stemmed, in order and without repeats.
fn query_terms(query: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for word in tokens(query).into_iter().filter(|w| !vague(w)) {
        let s = stem(&word);
        if !out.contains(&s) {
            out.push(s);
        }
    }
    out
}

/// Whether a search hit really is what the search asked for: its title, slug, summary or
/// categories hold every meaningful word of a one- or two-word search, or most words of a longer
/// one. A word may also start a word of the name ("zoom" in "Zoomify"). Modrinth returns *something*
/// for almost any word, so its top hit alone proves nothing ("boosting" found an elytra mod).
fn matches_query(query: &str, hit: &Value) -> bool {
    let terms = query_terms(query);
    if terms.is_empty() {
        return false;
    }
    let text = |key: &str| hit[key].as_str().unwrap_or_default().to_owned();
    let names: Vec<String> = tokens(&format!("{} {}", text("title"), text("slug")));
    let mut all = format!("{} {} {}", text("title"), text("slug"), text("description"));
    for key in ["categories", "display_categories"] {
        for c in hit[key].as_array().into_iter().flatten().filter_map(Value::as_str) {
            all.push(' ');
            all.push_str(c);
        }
    }
    let stems: HashSet<String> = tokens(&all).iter().map(|w| stem(w)).collect();
    let found = terms.iter().filter(|t| stems.contains(*t) || (t.len() >= 4 && names.iter().any(|n| n.starts_with(t.as_str())))).count();
    let needed = if terms.len() <= 2 { terms.len() } else { terms.len() / 2 + 1 };
    found >= needed
}

/// A vanilla profile can't use any of the requests when every one of them is a mod (or an unknown
/// kind); the whole call then fails as before instead of returning an empty plan.
fn vanilla_refuses_all(fabric: bool, kinds: &[Option<&str>]) -> bool {
    !fabric && !kinds.contains(&Some("resourcepack"))
}

/// Clear cheats that Flint never suggests: the name, slug or summary of a search result is checked
/// as whole words. Phrases may appear anywhere; the short terms only in the name or slug, where
/// they can't be ordinary words in a description ("reach the end", "esp. useful").
const CHEAT_PHRASES: [&str; 14] = [
    "xray", "x ray", "killaura", "kill aura", "autoclick", "autoclicker", "auto clicker", "aimbot", "aim assist", "wallhack",
    "hacked client", "ghost client", "anticheat bypass", "bypass anticheat",
];
const CHEAT_NAME_TERMS: [&str; 4] = ["reach", "esp", "bypass", "triggerbot"];
/// Display mods whose names pair a short term with what they only show ("Reach Display" shows the
/// distance of your last hit); these pairings are removed from the name before the short terms are checked.
const HARMLESS_NAME_PHRASES: [&str; 5] = ["reach display", "reach indicator", "reach hud", "reach counter", "reach meter"];
/// Projects not updated for this long (about 18 months) count as stale when ranking search results.
const STALE_SECS: u64 = 548 * 24 * 3600;

fn words(text: &str) -> String {
    let spaced: String = text.chars().map(|c| if c.is_alphanumeric() { c.to_ascii_lowercase() } else { ' ' }).collect();
    format!(" {} ", spaced.split_whitespace().collect::<Vec<_>>().join(" "))
}

fn looks_like_cheat(project: &Value) -> bool {
    let name = words(&format!("{} {}", project["title"].as_str().unwrap_or_default(), project["slug"].as_str().unwrap_or_default()));
    let all = format!("{name}{}", words(project["description"].as_str().unwrap_or_default()));
    let mut short = name;
    for phrase in HARMLESS_NAME_PHRASES.map(|p| format!(" {p} ")) {
        // Repeated because adjacent matches share a space ("reach display reach display").
        while short.contains(&phrase) {
            short = short.replace(&phrase, " ");
        }
    }
    CHEAT_PHRASES.iter().any(|p| all.contains(&format!(" {p} "))) || CHEAT_NAME_TERMS.iter().any(|t| short.contains(&format!(" {t} ")))
}

/// Seconds since 1970 for an RFC 3339 date such as Modrinth's `date_modified` (day precision).
fn date_secs(date: &str) -> Option<u64> {
    let mut parts = date.get(..10)?.split('-').map(|p| p.parse::<i64>().ok());
    let (y, m, d) = (parts.next()??, parts.next()??, parts.next()??);
    if !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    // Days from the civil date (Howard Hinnant's algorithm).
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    u64::try_from((era * 146_097 + doe - 719_468) * 86_400).ok()
}

/// Picks the best of Modrinth's top search results. Modrinth's relevance order leads; clear cheats
/// are skipped; a project not updated in about 18 months drops two places, so a maintained project
/// close behind it wins; download counts only break exact ties. Projects another request in the
/// same batch already chose are passed over while a different good match exists.
fn pick_search_hit(hits: &[Value], taken: &HashSet<String>, now: u64) -> Option<String> {
    let mut ranked: Vec<(usize, u64, usize, &str)> = hits
        .iter()
        .enumerate()
        .filter(|(_, h)| !looks_like_cheat(h))
        .filter_map(|(i, h)| {
            let id = h["project_id"].as_str().filter(|id| valid_id(id))?;
            let stale = h["date_modified"].as_str().and_then(date_secs).is_some_and(|t| now.saturating_sub(t) > STALE_SECS);
            Some((i + if stale { 2 } else { 0 }, u64::MAX - h["downloads"].as_u64().unwrap_or(0), i, id))
        })
        .collect();
    ranked.sort();
    ranked.iter().find(|r| !taken.contains(r.3)).or(ranked.first()).map(|r| r.3.to_owned())
}

/// Mod, resource pack and shader pack planning for onboarding and Flint AI, which can work from
/// free-text names before a profile exists. A search resolves only to a hit that matches it
/// (`matches_query`). On a vanilla profile only resource packs resolve; mod and shader requests
/// come back unmatched ("Mods need a Fabric profile"). `matches` lists, for each request in order, the project it resolved to (or null), so the
/// interface can show which goal each pick came from.
#[tauri::command]
pub async fn mods_resolve(
    state: State<'_, AppState>,
    profile_id: Option<String>,
    game_version: Option<String>,
    requests: Vec<ResolveRequest>,
) -> CmdResult<Value> {
    let profile = target_profile(&state, profile_id.as_deref(), game_version.as_deref())?;
    let fabric = profile.loader == "fabric";
    let requests: Vec<ResolveRequest> = requests.into_iter().take(16).collect();
    let kinds: Vec<Option<&'static str>> = requests.iter().map(|r| resolve_kind(r.kind.as_deref())).collect();
    if vanilla_refuses_all(fabric, &kinds) {
        return Err("This profile runs vanilla Minecraft. Mods need a Fabric profile.".into());
    }
    let mut roots = Vec::new();
    let mut unmatched = Vec::new();
    let mut matches: Vec<Value> = Vec::new();
    let mut taken: HashSet<String> = HashSet::new();
    for (request, kind) in requests.into_iter().zip(kinds) {
        let mut message: String = match kind {
            Some("resourcepack") => "No matching resource pack on Modrinth for this version",
            Some("shader") => "No matching shader pack on Modrinth for this version",
            _ => "No matching mod on Modrinth for this version",
        }
        .into();
        let found = if let Some(refusal) = resolve_refusal(kind, fabric) {
            message = refusal.into();
            None
        } else if let Some(id) = request.project.as_deref().filter(|p| valid_id(p)) {
            project(&state, id).await.ok()
        } else if let Some(query) = request.query.as_deref().map(str::trim).filter(|q| !q.is_empty()) {
            let kind = kind.unwrap_or("mod");
            let path = format!(
                "/search?query={}&limit=5&index=relevance&facets={}",
                encode(query),
                encode(&search_facets(kind, &profile.game_version).to_string())
            );
            let hits = state.modrinth.get_fresh(&state.http, &path, SEARCH_TTL, "Modrinth search failed").await?;
            let hits = hits["hits"].as_array().map(Vec::as_slice).unwrap_or_default();
            // Only hits that really match the search are candidates; when none does, the search is reported instead of guessed.
            let relevant: Vec<Value> = hits.iter().filter(|h| matches_query(query, h)).cloned().collect();
            if relevant.is_empty() && !hits.is_empty() {
                message = format!("No project clearly matching '{}'", query.chars().take(40).collect::<String>());
            }
            match pick_search_hit(&relevant, &taken, now_secs()) {
                Some(id) => project(&state, &id).await.ok(),
                None => None,
            }
        } else {
            None
        };
        // Assistant hints are only hints: a cheat named by the assistant is left out too.
        let found = match found {
            Some(p) if request.source == "assistant" && looks_like_cheat(&p) => {
                message = "Left out: Flint doesn't suggest cheat clients".into();
                None
            }
            other => other,
        };
        match (found, kind) {
            // A named project must be the kind that was asked for.
            (Some(p), Some(kind)) if p["project_type"] == kind => {
                let id = p["id"].as_str().unwrap_or_default().to_owned();
                matches.push(json!(id));
                taken.insert(id);
                roots.push(Pending {
                    project: p,
                    kind: kind.into(),
                    version_id: None,
                    source: if request.source == "assistant" { "assistant".into() } else { "user".into() },
                    reason: request.reason.map(|r| r.chars().take(200).collect()),
                    required_by: None,
                    required: true,
                });
            }
            _ => {
                matches.push(Value::Null);
                unmatched.push(json!({
                    "query": request.query.or(request.project),
                    "source": request.source,
                    "kind": kind.unwrap_or("mod"),
                    "message": message,
                }));
            }
        }
    }
    let mut plan = resolve(&state, &profile, profile_id.as_deref(), roots).await?;
    // Iris is a mod that loads shader packs; the interface labels it as the shader loader, never as a pack.
    for item in plan["items"].as_array_mut().into_iter().flatten() {
        if item["projectId"] == IRIS {
            item["role"] = json!("shaderLoader");
        }
    }
    attach_authors(&state, &mut plan).await;
    plan["unmatched"] = json!(unmatched);
    plan["matches"] = json!(matches);
    Ok(plan)
}

/// Project ids in groups small enough for one Modrinth search each.
const AUTHOR_BATCH: usize = 20;

/// The Modrinth search paths that look up the given projects, `AUTHOR_BATCH` at a time.
fn author_search_paths(ids: &[String]) -> Vec<String> {
    ids.chunks(AUTHOR_BATCH)
        .map(|chunk| {
            let facet: Vec<String> = chunk.iter().map(|id| format!("project_id:{id}")).collect();
            format!("/search?limit={}&facets={}", chunk.len(), encode(&json!([facet]).to_string()))
        })
        .collect()
}

/// Fills in each plan item's author (the Modrinth username search results carry) with one search
/// per `AUTHOR_BATCH` projects. Best effort: on any failure the authors stay null.
async fn attach_authors(state: &AppState, plan: &mut Value) {
    let Some(items) = plan["items"].as_array_mut() else { return };
    let mut ids: Vec<String> = Vec::new();
    for id in items.iter().filter_map(|i| i["projectId"].as_str()).filter(|id| valid_id(id)) {
        if !ids.iter().any(|x| x == id) {
            ids.push(id.to_owned());
        }
    }
    let mut authors: HashMap<String, String> = HashMap::new();
    for path in author_search_paths(&ids) {
        let Ok(found) = state.modrinth.get_fresh(&state.http, &path, SEARCH_TTL, "Modrinth search failed").await else { continue };
        for hit in found["hits"].as_array().into_iter().flatten() {
            if let (Some(id), Some(author)) = (hit["project_id"].as_str(), hit["author"].as_str().map(str::trim).filter(|a| !a.is_empty() && a.len() <= 64)) {
                authors.insert(id.to_owned(), author.to_owned());
            }
        }
    }
    for item in items.iter_mut() {
        if let Some(author) = item["projectId"].as_str().and_then(|id| authors.get(id)) {
            item["author"] = json!(author);
        }
    }
}

// ---------- installing ----------

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct InstallItem {
    pub version_id: String,
    #[serde(default)]
    pub kind: Option<String>,
    pub source: String,
    #[serde(default)]
    pub reason: Option<String>,
}

struct Staged {
    entry: ContentEntry,
    staged: PathBuf,
    dir: PathBuf,
}

fn backup_dir(state: &AppState, profile_id: &str, kind: &str) -> PathBuf {
    state.paths.profile_dir(profile_id).join(".flint-backup").join(kind)
}

/// Installs a batch all-or-nothing: every file is downloaded and verified into a staging folder
/// first, and only when all of them succeed are they moved into the profile.
pub async fn install_batch(app: &AppHandle, state: &AppState, job: &str, profile_id: &str, items: Vec<InstallItem>, world: Option<String>) -> CmdResult<Value> {
    let profile = find_profile(state, profile_id)?;
    ensure_not_running(state, profile_id)?;
    let items: Vec<InstallItem> = items.into_iter().take(60).collect();
    let staging = state.paths.profile_dir(profile_id).join(format!(".flint-staging-{job}"));
    std::fs::create_dir_all(&staging).map_err(err("Could not prepare the download"))?;
    let total = items.len();

    let staged: CmdResult<Vec<Staged>> = async {
        let mut out = Vec::new();
        for (index, item) in items.iter().enumerate() {
            state.jobs.update(app, job, false, |j| {
                j.state = "running".into();
                j.stage = "Checking compatibility".into();
                j.items_done = index;
                j.items_total = total;
            });
            let kind = item.kind.clone().unwrap_or_else(|| "mod".into());
            if (kind == "mod" || kind == "shader") && profile.loader != "fabric" {
                return Err("This profile runs vanilla Minecraft, which can't load mods or shaders.".into());
            }
            let v = version(state, &item.version_id).await?;
            if !fits(&v, &kind, &profile.game_version) {
                return Err(format!("{} isn't built for Minecraft {} with this loader", v["name"].as_str().unwrap_or("This release"), profile.game_version));
            }
            let file = primary_file(&v).ok_or("This release has no downloadable file")?;
            let name = safe_name(file["filename"].as_str().unwrap_or_default())?.to_owned();
            if !file_fits(&kind, &name) {
                return Err(format!("{name} isn't a file type Flint installs for this content"));
            }
            let sha512 = file["hashes"]["sha512"].as_str().ok_or("Modrinth published no SHA-512 hash for this file")?.to_owned();
            let url = file["url"].as_str().ok_or("No download address")?.to_owned();
            let p = project(state, v["project_id"].as_str().unwrap_or_default()).await?;
            let dir = kind_dir(state, profile_id, &kind, world.as_deref())?;
            let target = staging.join(&name);
            let size = download_verified(app, state, job, Fetch { url: &url, expected: Expected::Sha512(&sha512), max_bytes: max_bytes(&kind), allowed_hosts: MODRINTH_CDN }, &target).await?;
            let requires: Vec<String> = v["dependencies"]
                .as_array()
                .map(|d| d.iter().filter(|x| x["dependency_type"] == "required").filter_map(|x| x["project_id"].as_str().map(str::to_owned)).collect())
                .unwrap_or_default();
            out.push(Staged {
                entry: ContentEntry {
                    kind: kind.clone(),
                    project_id: v["project_id"].as_str().unwrap_or_default().to_owned(),
                    version_id: item.version_id.clone(),
                    slug: p["slug"].as_str().unwrap_or_default().to_owned(),
                    title: p["title"].as_str().unwrap_or_default().to_owned(),
                    author: None,
                    version_number: v["version_number"].as_str().unwrap_or_default().to_owned(),
                    file_name: name,
                    sha512,
                    size,
                    source: match item.source.as_str() {
                        "assistant" | "dependency" | "optional" | "modpack" | "flint" => item.source.clone(),
                        _ => "user".into(),
                    },
                    reason: item.reason.clone().map(|r| r.chars().take(200).collect()),
                    icon_url: p["icon_url"].as_str().map(str::to_owned),
                    installed_at: now_secs(),
                    world: if kind == "datapack" { world.clone() } else { None },
                    requires,
                    previous: None,
                },
                staged: target,
                dir,
            });
        }
        Ok(out)
    }
    .await;

    let staged = match staged {
        Ok(s) => s,
        Err(e) => {
            let _ = std::fs::remove_dir_all(&staging);
            return Err(e);
        }
    };

    state.jobs.update(app, job, false, |j| {
        j.stage = "Installing".into();
        j.items_done = total;
    });
    let _guard = state.file_lock.lock().await;
    ensure_not_running(state, profile_id)?;
    let mut manifest = load(state, profile_id);
    let mut placed = Vec::new();
    for s in staged {
        std::fs::create_dir_all(&s.dir).map_err(err("Could not create the content folder"))?;
        let mut entry = s.entry;
        let mut keep_disabled = false;
        if let Some(old) = manifest.iter().find(|m| m.kind == entry.kind && m.project_id == entry.project_id && m.world == entry.world).cloned() {
            keep_disabled = s.dir.join(format!("{}{DISABLED}", old.file_name)).exists();
            let backups = backup_dir(state, profile_id, &entry.kind);
            let _ = std::fs::create_dir_all(&backups);
            for candidate in [old.file_name.clone(), format!("{}{DISABLED}", old.file_name)] {
                let path = s.dir.join(&candidate);
                if path.exists() && candidate != entry.file_name {
                    let _ = std::fs::rename(&path, backups.join(&old.file_name));
                }
            }
            let mut prev = old.clone();
            prev.previous = None;
            entry.previous = Some(Box::new(prev));
        }
        let target = if keep_disabled { s.dir.join(format!("{}{DISABLED}", entry.file_name)) } else { s.dir.join(&entry.file_name) };
        // A jar the player added under this name is kept, not overwritten. Only in `mods`: Iris
        // lists every folder in `shaderpacks` as a shader pack, so a kept-files folder would show.
        let tracked = manifest.iter().any(|m| m.kind == entry.kind && m.world == entry.world && m.file_name == entry.file_name);
        if entry.kind == "mod" && !tracked && target.exists() {
            set_aside(&target)?;
        }
        std::fs::rename(&s.staged, &target).map_err(err("Could not move the file into the profile"))?;
        manifest.retain(|m| !(m.kind == entry.kind && m.project_id == entry.project_id && m.world == entry.world));
        placed.push(entry.title.clone());
        manifest.push(entry);
    }
    let present: HashSet<String> = manifest.iter().map(|m| m.project_id.clone()).collect();
    for entry in manifest.iter_mut() {
        entry.requires.retain(|r| present.contains(r));
    }
    save(state, profile_id, &manifest)?;
    let _ = std::fs::remove_dir_all(&staging);
    Ok(json!({ "installed": placed, "failed": [] }))
}

/// Moves a file Flint didn't install (one the player added by hand) into `.flint-replaced` beside
/// it, so an install with the same file name doesn't silently overwrite it. Returns where it went.
fn set_aside(path: &Path) -> CmdResult<PathBuf> {
    let (Some(dir), Some(name)) = (path.parent(), path.file_name().and_then(|n| n.to_str())) else {
        return Err("Could not keep the file being replaced".into());
    };
    let folder = dir.join(REPLACED);
    std::fs::create_dir_all(&folder).map_err(err("Could not keep the file being replaced"))?;
    let (stem, ext) = name.rsplit_once('.').map(|(s, e)| (s, format!(".{e}"))).unwrap_or((name, String::new()));
    let kept = std::iter::once(name.to_owned())
        .chain((2u32..).map(|n| format!("{stem} ({n}){ext}")))
        .map(|candidate| folder.join(candidate))
        .find(|candidate| !candidate.exists())
        .expect("an unused name exists");
    std::fs::rename(path, &kept).map_err(err("Could not keep the file being replaced"))?;
    eprintln!("[flint] {name} wasn't installed by Flint; moved it to {REPLACED}/{} before installing a file with the same name", kept.file_name().unwrap_or_default().to_string_lossy());
    Ok(kept)
}

#[tauri::command]
pub async fn content_install(app: AppHandle, state: State<'_, AppState>, profile_id: String, title: String, items: Vec<InstallItem>, world: Option<String>) -> CmdResult<String> {
    let profile = find_profile(&state, &profile_id)?;
    ensure_not_running(&state, &profile_id)?;
    if items.is_empty() {
        return Err("Nothing to install.".into());
    }
    let (id, _) = state.jobs.create(&app, "install", Some(&profile.id), &title, items.len());
    jobs::spawn(&app, id.clone(), move |app, job| async move {
        let state = app.state::<AppState>();
        install_batch(&app, &state, &job, &profile_id, items, world).await
    });
    Ok(id)
}

/// Onboarding installs mods inline and waits for the result.
#[tauri::command]
pub async fn mods_install(app: AppHandle, state: State<'_, AppState>, profile_id: String, items: Vec<InstallItem>) -> CmdResult<Value> {
    find_profile(&state, &profile_id)?;
    let (job, _) = state.jobs.create(&app, "install", Some(&profile_id), "Mods for your new profile", items.len());
    let outcome = install_batch(&app, &state, &job, &profile_id, items, None).await;
    state.jobs.finish(&app, &job, &outcome);
    outcome
}

// ---------- listing ----------

fn file_info(path: &Path) -> (u64, u64) {
    std::fs::metadata(path)
        .map(|m| {
            let modified = m.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map(|d| d.as_secs()).unwrap_or(0);
            (if m.is_dir() { 0 } else { m.len() }, modified)
        })
        .unwrap_or((0, 0))
}

fn scan(dir: &Path, kind: &str) -> Vec<(String, bool, bool)> {
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    let mut out: Vec<(String, bool, bool)> = entries
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().into_string().ok()?;
            if name.starts_with('.') {
                return None;
            }
            let is_dir = e.path().is_dir();
            let lower = name.to_ascii_lowercase();
            let (base, enabled) = match lower.strip_suffix(DISABLED) {
                Some(_) => (name[..name.len() - DISABLED.len()].to_owned(), false),
                None => (name.clone(), true),
            };
            let base_lower = base.to_ascii_lowercase();
            let ok = match kind {
                // Flint's own runtime is managed by Flint (Settings), not listed as profile content.
                "mod" => !is_dir && base_lower.ends_with(".jar") && base != crate::runtime::FILE_NAME,
                _ => is_dir || base_lower.ends_with(".zip"),
            };
            ok.then_some((base, enabled, is_dir))
        })
        .collect();
    out.sort_by_key(|a| a.0.to_lowercase());
    out
}

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
struct Identified {
    size: u64,
    modified: u64,
    sha512: String,
    #[serde(default)]
    info: Option<Value>,
}

fn identified_path(state: &AppState, id: &str) -> PathBuf {
    state.paths.profile_dir(id).join(".flint-identified.json")
}

fn list_kind(state: &AppState, profile_id: &str, kind: &str, world: Option<&str>, manifest: &[ContentEntry], identified: &HashMap<String, Identified>) -> Vec<Value> {
    let Ok(dir) = kind_dir(state, profile_id, kind, world) else { return Vec::new() };
    let files = scan(&dir, kind);
    let tracked: Vec<&ContentEntry> = manifest.iter().filter(|m| m.kind == kind && m.world.as_deref() == world).collect();
    let backups = backup_dir(state, profile_id, kind);
    let mut out = Vec::new();
    for entry in &tracked {
        let found = files.iter().find(|f| f.0 == entry.file_name);
        let (size, modified) = file_info(&dir.join(&entry.file_name));
        out.push(json!({
            "kind": kind,
            "fileName": entry.file_name,
            "title": entry.title,
            "projectId": entry.project_id,
            "versionId": entry.version_id,
            "versionNumber": entry.version_number,
            "iconUrl": entry.icon_url,
            "slug": entry.slug,
            "source": entry.source,
            "reason": entry.reason,
            "size": if size > 0 { size } else { entry.size },
            "modified": modified,
            "enabled": found.is_none_or(|f| f.1),
            "missing": found.is_none(),
            "tracked": true,
            "world": entry.world,
            "requires": entry.requires,
            "installedAt": entry.installed_at,
            "canRollback": entry.previous.as_ref().is_some_and(|p| backups.join(&p.file_name).exists()),
            "previousVersion": entry.previous.as_ref().map(|p| p.version_number.clone()),
        }));
    }
    for (name, enabled, is_dir) in files {
        if tracked.iter().any(|t| t.file_name == name) {
            continue;
        }
        let real = if enabled { name.clone() } else { format!("{name}{DISABLED}") };
        let (size, modified) = file_info(&dir.join(&real));
        let info = identified.get(&format!("{kind}/{}/{name}", world.unwrap_or("-"))).filter(|i| i.size == size && i.modified == modified).and_then(|i| i.info.clone());
        out.push(json!({
            "kind": kind,
            "fileName": name,
            "title": info.as_ref().and_then(|i| i["title"].as_str()).map(str::to_owned).unwrap_or_else(|| name.trim_end_matches(".jar").trim_end_matches(".zip").to_owned()),
            "projectId": info.as_ref().map(|i| i["projectId"].clone()),
            "versionId": info.as_ref().map(|i| i["versionId"].clone()),
            "versionNumber": info.as_ref().map(|i| i["versionNumber"].clone()),
            "iconUrl": info.as_ref().map(|i| i["iconUrl"].clone()),
            "source": "manual",
            "size": size,
            "modified": modified,
            "enabled": enabled,
            "missing": false,
            "tracked": false,
            "folder": is_dir,
            "world": world,
            "requires": [],
            "identified": info.is_some(),
        }));
    }
    out
}

pub fn worlds(state: &AppState, profile_id: &str) -> Vec<Value> {
    let saves = state.paths.profile_dir(profile_id).join("saves");
    let Ok(entries) = std::fs::read_dir(&saves) else { return Vec::new() };
    let mut out: Vec<Value> = entries
        .flatten()
        .filter(|e| e.path().join("level.dat").is_file())
        .filter_map(|e| {
            let name = e.file_name().into_string().ok()?;
            let (_, modified) = file_info(&e.path().join("level.dat"));
            let icon = std::fs::read(e.path().join("icon.png"))
                .ok()
                .filter(|b| b.len() < 64 * 1024 && b.starts_with(b"\x89PNG"))
                .map(|b| format!("data:image/png;base64,{}", STANDARD.encode(b)));
            let datapacks = std::fs::read_dir(e.path().join("datapacks")).map(|d| d.flatten().filter(|x| !x.file_name().to_string_lossy().starts_with('.')).count()).unwrap_or(0);
            Some(json!({ "id": name, "name": name, "lastPlayed": modified, "icon": icon, "datapacks": datapacks }))
        })
        .collect();
    out.sort_by(|a, b| b["lastPlayed"].as_u64().cmp(&a["lastPlayed"].as_u64()));
    out
}

#[tauri::command]
pub async fn content_list(app: AppHandle, profile_id: String) -> CmdResult<Value> {
    crate::data::blocking(&app, move |state| list_content(state, &profile_id)).await?
}

/// Everything installed in a profile. Reads folders and world icons, so commands run it off the main thread.
pub fn list_content(state: &AppState, profile_id: &str) -> CmdResult<Value> {
    let profile = find_profile(state, profile_id)?;
    let manifest = load(state, profile_id);
    let identified: HashMap<String, Identified> = read_json(&identified_path(state, profile_id));
    let worlds = worlds(state, profile_id);
    let datapacks: Vec<Value> = worlds
        .iter()
        .flat_map(|w| list_kind(state, profile_id, "datapack", w["id"].as_str(), &manifest, &identified))
        .collect();
    let mods = list_kind(state, profile_id, "mod", None, &manifest, &identified);
    let iris = mods.iter().any(|m| m["projectId"] == IRIS || m["fileName"].as_str().is_some_and(|n| n.to_ascii_lowercase().starts_with("iris")));
    Ok(json!({
        "profileId": profile.id,
        "mods": mods,
        "resourcepacks": list_kind(state, profile_id, "resourcepack", None, &manifest, &identified),
        "shaders": list_kind(state, profile_id, "shader", None, &manifest, &identified),
        "datapacks": datapacks,
        "worlds": worlds,
        "running": state.running.lock().unwrap().contains_key(profile_id),
        "irisInstalled": iris,
    }))
}

fn sha512_file(path: &Path) -> Option<String> {
    use std::io::Read;
    let mut file = std::fs::File::open(path).ok()?;
    let mut hasher = Sha512::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Some(hex::encode(hasher.finalize()))
}

/// Looks up files the user added by hand using their SHA-512 hash, so they show real names and icons.
#[tauri::command]
pub async fn content_identify(state: State<'_, AppState>, profile_id: String) -> CmdResult<Value> {
    find_profile(&state, &profile_id)?;
    let manifest = load(&state, &profile_id);
    let mut identified: HashMap<String, Identified> = read_json(&identified_path(&state, &profile_id));
    let mut pending: Vec<(String, String)> = Vec::new();
    let mut targets: Vec<(String, Option<String>)> = vec![("mod".into(), None), ("resourcepack".into(), None), ("shader".into(), None)];
    targets.extend(worlds(&state, &profile_id).into_iter().filter_map(|w| w["id"].as_str().map(|id| ("datapack".to_owned(), Some(id.to_owned())))));
    for (kind, world) in targets {
        let Ok(dir) = kind_dir(&state, &profile_id, &kind, world.as_deref()) else { continue };
        for (name, enabled, is_dir) in scan(&dir, &kind) {
            if is_dir || manifest.iter().any(|m| m.kind == kind && m.file_name == name && m.world == world) {
                continue;
            }
            let real = dir.join(if enabled { name.clone() } else { format!("{name}{DISABLED}") });
            let (size, modified) = file_info(&real);
            let key = format!("{kind}/{}/{name}", world.as_deref().unwrap_or("-"));
            if identified.get(&key).is_some_and(|i| i.size == size && i.modified == modified) {
                continue;
            }
            if size > 256 * 1024 * 1024 {
                continue;
            }
            let path = real.clone();
            let Some(hash) = tokio::task::spawn_blocking(move || sha512_file(&path)).await.ok().flatten() else { continue };
            identified.insert(key.clone(), Identified { size, modified, sha512: hash.clone(), info: None });
            pending.push((key, hash));
        }
    }
    if !pending.is_empty() {
        let hashes: Vec<&str> = pending.iter().map(|(_, h)| h.as_str()).take(200).collect();
        let found = state.modrinth.post(&state.http, "/version_files", &json!({ "hashes": hashes, "algorithm": "sha512" }), "Couldn't identify files").await?;
        let ids: Vec<String> = found.as_object().map(|o| o.values().filter_map(|v| v["project_id"].as_str().map(str::to_owned)).collect::<HashSet<_>>().into_iter().collect()).unwrap_or_default();
        let projects = if ids.is_empty() {
            json!([])
        } else {
            state
                .modrinth
                .get_fresh(&state.http, &format!("/projects?ids={}", encode(&json!(ids).to_string())), PROJECT_TTL, "Couldn't load projects")
                .await
                .unwrap_or_else(|_| json!([]))
        };
        for (key, hash) in &pending {
            let Some(v) = found.get(hash) else { continue };
            let project = projects.as_array().and_then(|l| l.iter().find(|p| p["id"] == v["project_id"]));
            if let Some(entry) = identified.get_mut(key) {
                entry.info = Some(json!({
                    "projectId": v["project_id"],
                    "versionId": v["id"],
                    "versionNumber": v["version_number"],
                    "title": project.map(|p| p["title"].clone()),
                    "iconUrl": project.map(|p| p["icon_url"].clone()),
                }));
            }
        }
    }
    write_json(&identified_path(&state, &profile_id), &identified)?;
    Ok(json!({ "identified": identified.values().filter(|i| i.info.is_some()).count() }))
}

// ---------- changing installed content ----------

#[tauri::command]
pub async fn content_set_enabled(state: State<'_, AppState>, profile_id: String, file_names: Vec<String>, enabled: bool) -> CmdResult<()> {
    find_profile(&state, &profile_id)?;
    ensure_not_running(&state, &profile_id)?;
    let _guard = state.file_lock.lock().await;
    let dir = state.paths.profile_dir(&profile_id).join("mods");
    for name in file_names.iter().take(500) {
        let name = safe_name(name)?;
        if !name.to_ascii_lowercase().ends_with(".jar") {
            return Err("Only Fabric mods can be switched on and off. Other content is chosen inside Minecraft.".into());
        }
        let on = dir.join(name);
        let off = dir.join(format!("{name}{DISABLED}"));
        let (from, to) = if enabled { (&off, &on) } else { (&on, &off) };
        if from.exists() {
            std::fs::rename(from, to).map_err(err("Could not change the mod"))?;
        } else if !to.exists() {
            return Err(format!("{name} is missing from this profile"));
        }
    }
    Ok(())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContentRef {
    kind: String,
    file_name: String,
    #[serde(default)]
    world: Option<String>,
}

#[tauri::command]
pub async fn content_remove(state: State<'_, AppState>, profile_id: String, items: Vec<ContentRef>) -> CmdResult<()> {
    find_profile(&state, &profile_id)?;
    ensure_not_running(&state, &profile_id)?;
    let _guard = state.file_lock.lock().await;
    let mut manifest = load(&state, &profile_id);
    for item in items.iter().take(500) {
        let name = safe_name(&item.file_name)?.to_owned();
        let dir = kind_dir(&state, &profile_id, &item.kind, item.world.as_deref())?;
        for candidate in [name.clone(), format!("{name}{DISABLED}")] {
            let path = dir.join(&candidate);
            if path.parent() != Some(dir.as_path()) {
                return Err("Unsafe path".into());
            }
            if path.is_dir() {
                std::fs::remove_dir_all(&path).map_err(err("Could not remove it"))?;
            } else if path.exists() {
                std::fs::remove_file(&path).map_err(err("Could not remove it"))?;
            }
        }
        if let Some(entry) = manifest.iter().find(|m| m.kind == item.kind && m.file_name == name && m.world == item.world) {
            if let Some(prev) = &entry.previous {
                let _ = std::fs::remove_file(backup_dir(&state, &profile_id, &item.kind).join(&prev.file_name));
            }
        }
        manifest.retain(|m| !(m.kind == item.kind && m.file_name == name && m.world == item.world));
    }
    save(&state, &profile_id, &manifest)
}

#[tauri::command]
pub async fn content_rollback(state: State<'_, AppState>, profile_id: String, kind: String, project_id: String, world: Option<String>) -> CmdResult<()> {
    find_profile(&state, &profile_id)?;
    ensure_not_running(&state, &profile_id)?;
    let _guard = state.file_lock.lock().await;
    let mut manifest = load(&state, &profile_id);
    let index = manifest.iter().position(|m| m.kind == kind && m.project_id == project_id && m.world == world).ok_or("Nothing to roll back")?;
    let current = manifest[index].clone();
    let previous = current.previous.clone().ok_or("There's no earlier version saved")?;
    let dir = kind_dir(&state, &profile_id, &kind, world.as_deref())?;
    let backup = backup_dir(&state, &profile_id, &kind).join(&previous.file_name);
    if !backup.exists() {
        return Err("The earlier file was removed, so it can't be restored.".into());
    }
    let disabled = dir.join(format!("{}{DISABLED}", current.file_name)).exists();
    for candidate in [current.file_name.clone(), format!("{}{DISABLED}", current.file_name)] {
        let _ = std::fs::remove_file(dir.join(candidate));
    }
    let restored = if disabled { format!("{}{DISABLED}", previous.file_name) } else { previous.file_name.clone() };
    std::fs::rename(&backup, dir.join(restored)).map_err(err("Could not restore the earlier version"))?;
    manifest[index] = *previous;
    save(&state, &profile_id, &manifest)
}

#[tauri::command]
pub async fn content_updates(state: State<'_, AppState>, profile_id: String) -> CmdResult<Value> {
    let profile = find_profile(&state, &profile_id)?;
    let manifest = load(&state, &profile_id);
    let identified: HashMap<String, Identified> = read_json(&identified_path(&state, &profile_id));
    let mut updates = Vec::new();
    for kind in ["mod", "resourcepack", "shader", "datapack"] {
        if (kind == "mod" || kind == "shader") && profile.loader != "fabric" {
            continue;
        }
        let mut known: Vec<(String, Value)> = manifest
            .iter()
            .filter(|m| m.kind == kind && !m.sha512.is_empty())
            .map(|m| (m.sha512.clone(), json!({ "fileName": m.file_name, "projectId": m.project_id, "versionId": m.version_id, "versionNumber": m.version_number, "title": m.title, "world": m.world, "tracked": true })))
            .collect();
        for (key, i) in identified.iter().filter(|(k, i)| k.starts_with(&format!("{kind}/")) && i.info.is_some()) {
            let mut parts = key.splitn(3, '/');
            let (_, world, name) = (parts.next(), parts.next().unwrap_or("-"), parts.next().unwrap_or_default());
            let info = i.info.clone().unwrap_or_default();
            known.push((i.sha512.clone(), json!({ "fileName": name, "projectId": info["projectId"], "versionId": info["versionId"], "versionNumber": info["versionNumber"], "title": info["title"], "world": if world == "-" { Value::Null } else { json!(world) }, "tracked": false })));
        }
        if known.is_empty() {
            continue;
        }
        let hashes: Vec<&str> = known.iter().map(|(h, _)| h.as_str()).take(300).collect();
        let body = json!({ "hashes": hashes, "algorithm": "sha512", "loaders": loaders_for(kind), "game_versions": [profile.game_version] });
        let latest = state.modrinth.post(&state.http, "/version_files/update", &body, "Couldn't check for updates").await?;
        for (hash, current) in &known {
            let Some(newest) = latest.get(hash) else { continue };
            if newest["id"] == current["versionId"] {
                continue;
            }
            let v = if newest["version_type"] == "release" {
                newest.clone()
            } else {
                match stable_update(&state, current, kind, &profile.game_version).await {
                    Some(v) => v,
                    None => continue,
                }
            };
            if v["id"] == current["versionId"] || !fits(&v, kind, &profile.game_version) {
                continue;
            }
            let changelog: String = v["changelog"].as_str().unwrap_or_default().chars().take(1500).collect();
            updates.push(json!({
                "kind": kind,
                "fileName": current["fileName"],
                "title": current["title"],
                "projectId": current["projectId"],
                "world": current["world"],
                "tracked": current["tracked"],
                "currentVersion": current["versionNumber"],
                "versionId": v["id"],
                "versionNumber": v["version_number"],
                "versionType": v["version_type"],
                "datePublished": v["date_published"],
                "changelog": changelog,
            }));
        }
    }
    Ok(json!({ "updates": updates, "checkedAt": now_secs() }))
}

fn stability(v: &Value) -> u8 {
    match v["version_type"].as_str() {
        Some("release") => 2,
        Some("beta") => 1,
        _ => 0,
    }
}

/// Modrinth's update lookup returns the newest version of any channel. A stable install should
/// only be offered newer versions that are at least as stable as the one it runs.
async fn stable_update(state: &AppState, current: &Value, kind: &str, game_version: &str) -> Option<Value> {
    let project = current["projectId"].as_str().filter(|p| valid_id(p))?;
    let path = format!("/project/{project}/version{}", version_query(loaders_for(kind), Some(game_version)));
    let (list, _) = state.modrinth.get(&state.http, &path, VERSIONS_TTL, "Couldn't check for updates").await.ok()?;
    let list = list.as_array()?;
    let installed = list.iter().find(|v| v["id"] == current["versionId"])?;
    let floor = stability(installed);
    let since = installed["date_published"].as_str().unwrap_or_default();
    list.iter()
        .filter(|v| stability(v) >= floor && v["date_published"].as_str().unwrap_or_default() > since)
        .max_by(|a, b| a["date_published"].as_str().cmp(&b["date_published"].as_str()))
        .cloned()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateItem {
    kind: String,
    file_name: String,
    version_id: String,
    #[serde(default)]
    world: Option<String>,
}

/// Updates replace one file per project; files the user added by hand are adopted into Flint's
/// records first so the old version can be rolled back like any other.
#[tauri::command]
pub async fn content_update(app: AppHandle, state: State<'_, AppState>, profile_id: String, items: Vec<UpdateItem>) -> CmdResult<String> {
    find_profile(&state, &profile_id)?;
    ensure_not_running(&state, &profile_id)?;
    {
        let _guard = state.file_lock.lock().await;
        let mut manifest = load(&state, &profile_id);
        let identified: HashMap<String, Identified> = read_json(&identified_path(&state, &profile_id));
        for item in &items {
            if manifest.iter().any(|m| m.kind == item.kind && m.file_name == item.file_name && m.world == item.world) {
                continue;
            }
            let key = format!("{}/{}/{}", item.kind, item.world.as_deref().unwrap_or("-"), item.file_name);
            if let Some(info) = identified.get(&key).and_then(|i| i.info.clone().map(|info| (i.sha512.clone(), i.size, info))) {
                manifest.push(ContentEntry {
                    kind: item.kind.clone(),
                    project_id: info.2["projectId"].as_str().unwrap_or_default().to_owned(),
                    version_id: info.2["versionId"].as_str().unwrap_or_default().to_owned(),
                    title: info.2["title"].as_str().unwrap_or(&item.file_name).to_owned(),
                    version_number: info.2["versionNumber"].as_str().unwrap_or_default().to_owned(),
                    file_name: item.file_name.clone(),
                    sha512: info.0,
                    size: info.1,
                    source: "manual".into(),
                    icon_url: info.2["iconUrl"].as_str().map(str::to_owned),
                    installed_at: now_secs(),
                    world: item.world.clone(),
                    ..ContentEntry::default()
                });
            }
        }
        save(&state, &profile_id, &manifest)?;
    }
    let mut groups: Vec<(Option<String>, Vec<&UpdateItem>)> = Vec::new();
    for item in &items {
        match groups.iter_mut().find(|(w, _)| *w == item.world) {
            Some((_, list)) => list.push(item),
            None => groups.push((item.world.clone(), vec![item])),
        }
    }
    let mut ids = Vec::new();
    for (world, group) in groups {
        let install: Vec<InstallItem> = group
            .iter()
            .map(|i| InstallItem { version_id: i.version_id.clone(), kind: Some(i.kind.clone()), source: "user".into(), reason: None })
            .collect();
        let title = if group.len() == 1 { format!("Updating {}", group[0].file_name) } else { format!("Updating {} items", group.len()) };
        let (id, _) = state.jobs.create(&app, "update", Some(&profile_id), &title, install.len());
        let profile_id = profile_id.clone();
        jobs::spawn(&app, id.clone(), move |app, job| async move {
            let state = app.state::<AppState>();
            install_batch(&app, &state, &job, &profile_id, install, world).await
        });
        ids.push(id);
    }
    ids.first().cloned().ok_or_else(|| "Nothing to update.".into())
}

/// Installs Flint's performance set into a profile marked "pending", as a visible download job.
/// Mods without a build for this Minecraft version are skipped. The profile is marked "applied"
/// once Modrinth answered (even if nothing fitted); a failure (e.g. offline) leaves it pending so
/// the next install or launch tries again. Never blocks or fails a launch.
/// What the performance set installs: its own mods plus the dependencies something kept actually
/// requires (following chains). Optional extras the resolver lists (Mod Menu, for example) and
/// libraries only those extras need are left out; the player never chose them.
/// Projects already in the mods folder under any file name (`present` holds the mod ids its jars
/// declare) are left out too, so a Sodium the player added by hand never gets a second copy.
fn performance_install_items(items: &[Value], present: &HashSet<String>) -> Vec<InstallItem> {
    let ok: Vec<&Value> = items
        .iter()
        .filter(|i| i["status"] == "ok" && i["versionId"].is_string() && !already_present(i["slug"].as_str().unwrap_or_default(), present))
        .collect();
    let title = |i: &Value| i["title"].as_str().unwrap_or_default().to_owned();
    let mut kept: HashSet<String> = ok.iter().filter(|i| i["source"] == "flint").map(|i| title(i)).collect();
    loop {
        let before = kept.len();
        for item in ok.iter().filter(|i| i["source"] == "dependency") {
            let parents: Vec<&str> = item["requiredByAll"].as_array().map(|a| a.iter().filter_map(Value::as_str).collect()).unwrap_or_else(|| item["requiredBy"].as_str().into_iter().collect());
            if parents.iter().any(|p| kept.contains(*p)) {
                kept.insert(title(item));
            }
        }
        if kept.len() == before {
            break;
        }
    }
    ok.into_iter()
        .filter(|i| i["source"] != "optional" && kept.contains(&title(i)))
        .filter_map(|i| {
            Some(InstallItem {
                version_id: i["versionId"].as_str()?.to_owned(),
                kind: Some("mod".into()),
                source: if i["source"] == "flint" { "flint".into() } else { "dependency".into() },
                reason: i["reason"].as_str().map(str::to_owned),
            })
        })
        .collect()
}

/// Whether a jar declaring one of the project's known mod ids is already there. Projects whose ids
/// Flint doesn't know count as absent.
fn already_present(slug: &str, present: &HashSet<String>) -> bool {
    PERFORMANCE_MOD_IDS.iter().find(|(s, _)| *s == slug).is_some_and(|(_, ids)| ids.iter().any(|id| present.contains(*id)))
}

pub async fn apply_performance_set(app: &AppHandle, state: &AppState, profile_id: &str) {
    let Ok(profile) = find_profile(state, profile_id) else { return };
    if profile.performance.as_deref() != Some("pending") || profile.loader != "fabric" || !crate::data::performance_mods_enabled(state) {
        return;
    }
    let mut roots = Vec::new();
    for (slug, reason) in PERFORMANCE_SET {
        if let Ok(p) = project(state, slug).await {
            roots.push(Pending { project: p, kind: "mod".into(), version_id: None, source: "flint".into(), reason: Some(reason.into()), required: false, required_by: None });
        }
    }
    if roots.is_empty() {
        return;
    }
    let Ok(plan) = resolve(state, &profile, Some(profile_id), roots).await else { return };
    // The resolver only knows what Flint installed; jars added by hand are found by their mod ids.
    // Only a jar built for this Minecraft version counts: a copy for another version would stop
    // Fabric from starting if Flint left its own out.
    let present = crate::perfcore::mod_ids_for(&crate::perfcore::scan_mods(&state.paths.profile_dir(profile_id).join("mods")), &profile.game_version);
    let items = performance_install_items(plan["items"].as_array().map(Vec::as_slice).unwrap_or_default(), &present);
    let outcome = if items.is_empty() {
        Ok(json!({ "installed": [], "failed": [] }))
    } else {
        let (job, _) = state.jobs.create(app, "install", Some(profile_id), "Flint performance mods", items.len());
        let outcome = install_batch(app, state, &job, profile_id, items, None).await;
        state.jobs.finish(app, &job, &outcome);
        outcome
    };
    if outcome.is_ok() {
        let _ = crate::data::update_profile(state, profile_id, |p| p.performance = Some("applied".into())).await;
    }
}

/// Blocks a launch that would crash because a required mod is missing or switched off, or whose
/// mods folder holds mods that can't run together (every jar Fabric would load counts, including
/// ones added by hand).
pub fn launch_preflight(state: &AppState, profile_id: &str) -> CmdResult<()> {
    let manifest = load(state, profile_id);
    let dir = state.paths.profile_dir(profile_id).join("mods");
    let enabled = |name: &str| dir.join(name).is_file();
    let mut problems = Vec::new();
    for entry in manifest.iter().filter(|m| m.kind == "mod") {
        let on = enabled(&entry.file_name);
        let off = dir.join(format!("{}{DISABLED}", entry.file_name)).is_file();
        if !on && !off {
            problems.push(format!("{} is missing from the mods folder. Open the profile and reinstall or remove it.", entry.title));
            continue;
        }
        if !on {
            continue;
        }
        for req in &entry.requires {
            if let Some(dep) = manifest.iter().find(|m| &m.project_id == req) {
                if !enabled(&dep.file_name) {
                    problems.push(format!("{} needs {}, which is switched off.", entry.title, dep.title));
                }
            }
        }
    }
    // Vanilla doesn't load the mods folder.
    if find_profile(state, profile_id).is_ok_and(|p| p.loader == "fabric") {
        let jars = crate::perfcore::scan_mods(&dir);
        // Two copies of a mod start fine (Fabric picks one), so they're only noted.
        for duplicate in crate::perfcore::duplicate_mods(&jars) {
            eprintln!("[flint] {duplicate}");
        }
        // Fabric's dependency overrides can lift a mod's `breaks`, and then the pair starts. Fabric
        // applies them and explains anything that still clashes, so Flint doesn't second-guess it.
        let overrides = state.paths.profile_dir(profile_id).join("config").join("fabric_loader_dependencies.json");
        if !overrides.exists() {
            problems.extend(crate::perfcore::mod_conflicts(&jars));
        }
    }
    if problems.is_empty() {
        Ok(())
    } else {
        Err(problems.into_iter().take(3).collect::<Vec<_>>().join("\n"))
    }
}

// ---------- profile files, worlds, logs ----------

#[tauri::command]
pub async fn profile_worlds(app: AppHandle, profile_id: String) -> CmdResult<Value> {
    crate::data::blocking(&app, move |state| {
        find_profile(state, &profile_id)?;
        Ok(json!(worlds(state, &profile_id)))
    })
    .await?
}

#[tauri::command]
pub async fn profile_files(state: State<'_, AppState>, profile_id: String) -> CmdResult<Value> {
    find_profile(&state, &profile_id)?;
    let dir = state.paths.profile_dir(&profile_id);
    tokio::task::spawn_blocking(move || {
        fn size(path: &Path, depth: u32) -> u64 {
            let Ok(meta) = std::fs::symlink_metadata(path) else { return 0 };
            if meta.is_dir() && depth < 12 {
                std::fs::read_dir(path).map(|e| e.flatten().map(|x| size(&x.path(), depth + 1)).sum()).unwrap_or(0)
            } else {
                meta.len()
            }
        }
        let folders: Vec<Value> = ["mods", "resourcepacks", "shaderpacks", "saves", "screenshots", "config", "logs"]
            .iter()
            .map(|name| {
                let path = dir.join(name);
                let count = std::fs::read_dir(&path).map(|e| e.flatten().filter(|x| !x.file_name().to_string_lossy().starts_with('.')).count()).unwrap_or(0);
                json!({ "id": name, "count": count, "size": size(&path, 0), "exists": path.exists() })
            })
            .collect();
        json!({ "path": dir.display().to_string(), "folders": folders, "total": size(&dir, 0) })
    })
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn profile_logs(app: AppHandle, profile_id: String) -> CmdResult<Value> {
    crate::data::blocking(&app, move |state| list_logs(state, &profile_id)).await?
}

fn list_logs(state: &AppState, profile_id: &str) -> CmdResult<Value> {
    find_profile(state, profile_id)?;
    let dir = state.paths.profile_dir(profile_id);
    let mut out = Vec::new();
    for (folder, kind) in [("logs", "log"), ("crash-reports", "crash")] {
        if let Ok(entries) = std::fs::read_dir(dir.join(folder)) {
            for e in entries.flatten() {
                let name = e.file_name().to_string_lossy().to_string();
                if !(name.ends_with(".log") || name.ends_with(".txt")) {
                    continue;
                }
                let (size, modified) = file_info(&e.path());
                out.push(json!({ "name": name, "folder": folder, "kind": kind, "size": size, "modified": modified }));
            }
        }
    }
    out.sort_by(|a, b| b["modified"].as_u64().cmp(&a["modified"].as_u64()));
    Ok(json!(out))
}

pub fn redact(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for line in text.lines() {
        let mut words: Vec<String> = Vec::new();
        let mut hide_next = false;
        for word in line.split(' ') {
            if hide_next {
                words.push("[hidden]".into());
                hide_next = false;
                continue;
            }
            let lower = word.to_ascii_lowercase();
            if lower == "--accesstoken" || lower == "--session" {
                hide_next = true;
                words.push(word.to_owned());
            } else if word.matches('.').count() == 2 && word.starts_with("eyJ") && word.len() > 40 {
                words.push("[hidden token]".into());
            } else {
                words.push(word.to_owned());
            }
        }
        out.push_str(&words.join(" "));
        out.push('\n');
    }
    out
}

#[tauri::command]
pub async fn profile_log_read(app: AppHandle, profile_id: String, folder: String, name: String) -> CmdResult<String> {
    crate::data::blocking(&app, move |state| read_log(state, &profile_id, &folder, &name)).await?
}

/// The last 256 KB of a log, with tokens redacted. Flint's launch log holds Minecraft's log4j XML
/// console output, which is shown as ordinary log lines.
fn read_log(state: &AppState, profile_id: &str, folder: &str, name: &str) -> CmdResult<String> {
    find_profile(state, profile_id)?;
    if folder != "logs" && folder != "crash-reports" {
        return Err("Unknown log folder".into());
    }
    let name = safe_name(name)?;
    let path = state.paths.profile_dir(profile_id).join(folder).join(name);
    let text = crate::diagnostics::read_tail(&path, 256 * 1024).map_err(err("Could not read the log"))?;
    let text = if text.contains("<log4j:Event") { crate::diagnostics::render_log4j(&text) } else { text };
    Ok(redact(&text))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 2026-10-13, the fixtures' "today".
    const NOW: u64 = 1_791_849_600;

    fn hits() -> Vec<Value> {
        serde_json::from_str(r#"[
            {"project_id":"AAAA1111","slug":"pvp-reach-plus","title":"Reach+","description":"Longer reach in PvP","downloads":900000,"date_modified":"2026-09-01T00:00:00Z"},
            {"project_id":"BBBB2222","slug":"old-sprint","title":"Old Sprint","description":"Toggle sprint","downloads":5000000,"date_modified":"2023-01-10T00:00:00Z"},
            {"project_id":"CCCC3333","slug":"toggle-sprint-display","title":"Toggle Sprint Display","description":"Shows your sprint state","downloads":20000,"date_modified":"2026-05-01T00:00:00Z"},
            {"project_id":"DDDD4444","slug":"sprint-tweaks","title":"Sprint Tweaks","description":"Sprint options","downloads":80000,"date_modified":"2026-08-01T00:00:00Z"},
            {"project_id":"EEEE5555","slug":"ghostly","title":"Ghostly","description":"A ghost client with autoclicker","downloads":1,"date_modified":"2026-10-01T00:00:00Z"}
        ]"#).unwrap()
    }

    #[test]
    fn dates_parse_to_seconds() {
        assert_eq!(date_secs("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(date_secs("2026-10-13T08:00:00.123Z"), Some(NOW));
        assert_eq!(date_secs("2024-02-29"), Some(1_709_164_800));
        assert_eq!(date_secs("soon"), None);
        assert_eq!(date_secs("2026-13-01"), None);
    }

    #[test]
    fn clear_cheats_are_recognised_without_false_alarms() {
        let hit = |title: &str, slug: &str, description: &str| json!({ "title": title, "slug": slug, "description": description });
        for cheat in [
            hit("Reach+", "pvp-reach-plus", ""),
            hit("Reach Display", "longer-reach", "Shows the distance of your hits"),
            hit("Simple X-Ray", "simple-x-ray", ""),
            hit("Combat Helper", "combat-helper", "Kill Aura and aimbot for anarchy servers"),
            hit("ESP Boxes", "esp-boxes", ""),
            hit("Tools", "tools", "An auto clicker"),
        ] {
            assert!(looks_like_cheat(&cheat), "{cheat}");
        }
        for fine in [
            hit("Freecam", "freecam", "Fly around your world, client-side"),
            hit("Sodium", "sodium", "Reach higher frame rates, esp. on older GPUs"),
            hit("Entity Culling", "entityculling", "Skips rendering hidden entities"),
            hit("Xaero's Minimap", "xaeros-minimap", "Displays the map of the area around you"),
            hit("Reach Display", "reach-display", "Shows the distance of your last hit"),
            hit("Combat Reach Indicator", "combat-reach-indicator", ""),
        ] {
            assert!(!looks_like_cheat(&fine), "{fine}");
        }
    }

    #[test]
    fn search_picks_skip_cheats_and_prefer_maintained_projects() {
        let hits = hits();
        // #1 is a reach cheat (skipped). #2 hasn't been updated since 2023, so the maintained #3
        // right behind it wins.
        assert_eq!(pick_search_hit(&hits, &HashSet::new(), NOW).as_deref(), Some("CCCC3333"));
        // A pick another request already took is passed over. Stale #2 and maintained #4 now rank
        // equally, and downloads break that tie.
        let taken: HashSet<String> = ["CCCC3333".to_owned()].into();
        assert_eq!(pick_search_hit(&hits, &taken, NOW).as_deref(), Some("BBBB2222"));
        // Relevance still leads: a maintained top hit wins over more popular ones further down.
        assert_eq!(pick_search_hit(&hits[1..], &HashSet::new(), date_secs("2023-06-01").unwrap()).as_deref(), Some("BBBB2222"));
        // Only cheats: nothing is picked.
        assert_eq!(pick_search_hit(&[hits[0].clone(), hits[4].clone()], &HashSet::new(), NOW), None);
        assert_eq!(pick_search_hit(&[], &HashSet::new(), NOW), None);
    }

    #[test]
    fn a_pick_already_taken_is_reused_when_nothing_else_fits() {
        let hits = hits();
        let taken: HashSet<String> = ["BBBB2222".to_owned()].into();
        assert_eq!(pick_search_hit(&hits[1..2], &taken, NOW).as_deref(), Some("BBBB2222"));
    }

    #[test]
    fn file_names_cannot_traverse_folders() {
        assert!(safe_name("sodium-0.9.2.jar").is_ok());
        for bad in ["", "..", ".hidden", "../x.jar", "a/b.jar", "a\\b.jar", "C:x.jar"] {
            assert!(safe_name(bad).is_err(), "{bad:?} must be refused");
        }
        assert!(safe_name(&"a".repeat(181)).is_err());
    }

    #[test]
    fn redaction_hides_tokens_and_their_arguments() {
        let jwt = format!("eyJ{}.{}.{}", "a".repeat(20), "b".repeat(20), "c".repeat(20));
        let out = redact(&format!("--username Steve --accessToken secret123 --session other456\nbearer {jwt} end"));
        assert!(!out.contains("secret123") && !out.contains("other456") && !out.contains(&jwt), "{out}");
        assert!(out.contains("--username Steve"), "harmless text stays readable: {out}");
        assert!(out.contains("[hidden token]"));
    }

    #[test]
    fn resolve_requests_default_to_mods_and_accept_resource_packs() {
        let parse = |text: &str| serde_json::from_str::<ResolveRequest>(text).unwrap();
        let plain = parse(r#"{"query":"sodium","source":"assistant"}"#);
        assert_eq!(resolve_kind(plain.kind.as_deref()), Some("mod"));
        let pack = parse(r#"{"project":"faithful-32x","source":"user","kind":"resourcepack"}"#);
        assert_eq!(resolve_kind(pack.kind.as_deref()), Some("resourcepack"));
        assert_eq!(resolve_kind(Some("mod")), Some("mod"));
        assert_eq!(resolve_kind(Some("shader")), Some("shader"));
        for other in ["datapack", "modpack", "RESOURCEPACK", ""] {
            assert_eq!(resolve_kind(Some(other)), None, "{other}");
        }
    }

    #[test]
    fn shader_searches_find_iris_shader_packs_and_need_fabric() {
        // The same facets the Library uses for shader packs on a Fabric profile.
        assert_eq!(search_facets("shader", "1.21.11"), json!([["project_type:shader"], ["categories:iris"], ["versions:1.21.11"]]));
        assert_eq!(loaders_for("shader"), ["iris"]);
        assert_eq!(resolve_refusal(Some("shader"), true), None);
        assert_eq!(resolve_refusal(Some("shader"), false), Some("Shader packs need a Fabric profile with Iris"));
        assert!(vanilla_refuses_all(false, &[Some("shader")]));
    }

    #[test]
    fn search_hits_must_match_the_search() {
        // Shared with the app's planner (src/lib/assistPlan.ts), so both decide the same way.
        let fixture: Value = serde_json::from_str(include_str!("../../src/lib/relevanceCases.json")).unwrap();
        let cases = fixture["cases"].as_array().unwrap();
        assert!(cases.len() >= 20);
        for case in cases {
            let query = case["query"].as_str().unwrap();
            assert_eq!(matches_query(query, &case["hit"]), case["match"].as_bool().unwrap(), "{query} vs {}", case["hit"]["title"]);
        }
    }

    #[test]
    fn stemming_and_vague_words() {
        for (word, stemmed) in [("boosting", "boost"), ("lowers", "low"), ("smaller", "small"), ("shields", "shield"), ("keystrokes", "keystrok"), ("keystroke", "keystrok"), ("status", "status"), ("iris", "iris"), ("inventories", "inventory"), ("armour", "armor"), ("boxes", "box")] {
            assert_eq!(stem(word), stemmed, "{word}");
        }
        assert!(query_terms("useful").is_empty() && query_terms("lets").is_empty() && query_terms("the best boosting").is_empty());
        assert_eq!(query_terms("good PvP armour HUDs"), ["pvp", "armor", "hud"]);
    }

    #[test]
    fn a_search_whose_hits_dont_match_picks_the_next_hit_or_nothing() {
        let fixture: Value = serde_json::from_str(include_str!("../../src/lib/relevanceCases.json")).unwrap();
        let hit = |title: &str| fixture["cases"].as_array().unwrap().iter().map(|c| c["hit"].clone()).find(|h| h["title"] == title).unwrap();
        // "armor hud": the elytra mod ranks first in this list but doesn't match; the next hit does.
        let hits = [hit("Elytra Swapper"), hit("Armor Hud")];
        let relevant: Vec<Value> = hits.iter().filter(|h| matches_query("armor hud", h)).cloned().collect();
        assert_eq!(pick_search_hit(&relevant, &HashSet::new(), NOW).as_deref(), Some("AghHBZC5"));
        // "boosting": nothing matches, so nothing is picked (reported as unmatched).
        let hits = [hit("Disable Elytra Outside The End"), hit("Bow Boostin' (BETA)")];
        assert!(hits.iter().all(|h| !matches_query("boosting", h)));
    }

    #[test]
    fn resource_pack_searches_use_resource_pack_facets() {
        assert_eq!(search_facets("resourcepack", "26.3"), json!([["project_type:resourcepack"], ["versions:26.3"]]));
        let mods = search_facets("mod", "26.3");
        assert_eq!(mods[0], json!(["project_type:mod"]));
        assert_eq!(mods[1], json!(["categories:fabric"]));
        assert_eq!(mods[3], json!(["client_side:required", "client_side:optional"]));
        assert_eq!(loaders_for("resourcepack"), ["minecraft"], "resource pack versions are matched on the minecraft loader");
    }

    #[test]
    fn vanilla_profiles_resolve_resource_packs_and_report_mods() {
        // Fabric: everything known is searched.
        assert_eq!(resolve_refusal(Some("mod"), true), None);
        assert_eq!(resolve_refusal(Some("resourcepack"), true), None);
        // Vanilla: packs are searched, mods are reported unmatched.
        assert_eq!(resolve_refusal(Some("resourcepack"), false), None);
        assert_eq!(resolve_refusal(Some("mod"), false), Some("Mods need a Fabric profile"));
        assert!(resolve_refusal(None, true).is_some());
        // The whole call fails on vanilla only when nothing could resolve.
        assert!(vanilla_refuses_all(false, &[Some("mod"), Some("mod")]));
        assert!(vanilla_refuses_all(false, &[]));
        assert!(!vanilla_refuses_all(false, &[Some("mod"), Some("resourcepack")]));
        assert!(!vanilla_refuses_all(true, &[Some("mod")]));
    }

    #[test]
    fn author_lookups_are_batched() {
        let ids: Vec<String> = (0..45).map(|n| format!("id{n:06}")).collect();
        let paths = author_search_paths(&ids);
        assert_eq!(paths.len(), 3);
        assert!(paths[0].starts_with("/search?limit=20&facets="));
        assert!(paths[2].starts_with("/search?limit=5&facets="));
        let facets = paths[2].split("facets=").nth(1).unwrap();
        let decoded: String = url::form_urlencoded::parse(format!("f={facets}").as_bytes()).next().unwrap().1.into_owned();
        assert_eq!(serde_json::from_str::<Value>(&decoded).unwrap(), json!([["project_id:id000040", "project_id:id000041", "project_id:id000042", "project_id:id000043", "project_id:id000044"]]));
        assert!(author_search_paths(&[]).is_empty());
    }

    #[test]
    fn resource_packs_install_into_the_resourcepacks_folder() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let profile = state.paths.profile_dir("p1");
        assert_eq!(kind_dir(&state, "p1", "resourcepack", None).unwrap(), profile.join("resourcepacks"));
        assert_eq!(kind_dir(&state, "p1", "mod", None).unwrap(), profile.join("mods"));
        assert_eq!(kind_dir(&state, "p1", "shader", None).unwrap(), profile.join("shaderpacks"));
        assert!(kind_dir(&state, "p1", "modpack", None).is_err());
        // A resource pack is a .zip; a .jar is never accepted as one.
        assert!(file_fits("resourcepack", "Faithful 32x.zip"));
        assert!(!file_fits("resourcepack", "sodium.jar"));
        assert!(max_bytes("resourcepack") > max_bytes("mod"));
    }

    #[test]
    fn only_expected_file_types_are_installed() {
        assert!(file_fits("mod", "Sodium.JAR"));
        assert!(!file_fits("mod", "pack.zip"));
        assert!(file_fits("resourcepack", "pack.zip"));
        assert!(!file_fits("shader", "evil.jar"));
    }

    #[test]
    fn the_performance_set_leaves_optional_extras_and_their_libraries_out() {
        let plan = vec![
            json!({ "title": "Sodium", "source": "flint", "status": "ok", "versionId": "a" }),
            json!({ "title": "Zoomify", "source": "flint", "status": "ok", "versionId": "b" }),
            json!({ "title": "Fabric API", "source": "dependency", "status": "ok", "versionId": "c", "requiredByAll": ["Zoomify"] }),
            json!({ "title": "YACL", "source": "dependency", "status": "ok", "versionId": "d", "requiredBy": "Zoomify" }),
            json!({ "title": "Kotlin", "source": "dependency", "status": "ok", "versionId": "e", "requiredByAll": ["YACL"] }),
            json!({ "title": "Mod Menu", "source": "optional", "status": "ok", "versionId": "f", "requiredBy": "Zoomify" }),
            json!({ "title": "Text Placeholder API", "source": "dependency", "status": "ok", "versionId": "g", "requiredByAll": ["Mod Menu"] }),
            json!({ "title": "Broken", "source": "flint", "status": "unavailable" }),
        ];
        let ids: Vec<String> = performance_install_items(&plan, &HashSet::new()).into_iter().map(|i| i.version_id).collect();
        assert_eq!(ids, ["a", "b", "c", "d", "e"], "chains are followed; the extra and its library are not installed");
    }

    #[test]
    fn performance_mods_added_by_hand_are_not_installed_again() {
        let plan = vec![
            json!({ "title": "Sodium", "slug": "sodium", "source": "flint", "status": "ok", "versionId": "sodium" }),
            json!({ "title": "Lithium", "slug": "lithium", "source": "flint", "status": "ok", "versionId": "lithium" }),
            json!({ "title": "Entity Culling", "slug": "entityculling", "source": "flint", "status": "ok", "versionId": "culling" }),
            json!({ "title": "Dynamic FPS", "slug": "dynamic-fps", "source": "flint", "status": "ok", "versionId": "dynamic" }),
            json!({ "title": "Fabric API", "slug": "fabric-api", "source": "dependency", "status": "ok", "versionId": "fapi", "requiredByAll": ["Entity Culling", "Dynamic FPS"] }),
        ];
        let dir = TestDir::new();
        // The player's own Sodium and Fabric API, under file names Flint wouldn't use. Fabric API is
        // still installed for the mods that need it (theirs may be too old; Fabric loads the newer).
        crate::perfcore::test_jar(&dir.0, "my-sodium.jar", r#"{"id":"sodium","version":"0.9.1","depends":{"minecraft":"~26.3"}}"#);
        crate::perfcore::test_jar(&dir.0, "fapi.jar", r#"{"id":"fabric-api","version":"0.160.0","depends":{"minecraft":"~26.3"}}"#);
        let present = crate::perfcore::mod_ids_for(&crate::perfcore::scan_mods(&dir.0), "26.3");
        let ids: Vec<String> = performance_install_items(&plan, &present).into_iter().map(|i| i.version_id).collect();
        assert_eq!(ids, ["lithium", "culling", "dynamic", "fapi"]);

        // A copy built for another Minecraft version doesn't count: Flint installs its own as 0.4.0
        // did, so Fabric still has one that runs.
        let present = crate::perfcore::mod_ids_for(&crate::perfcore::scan_mods(&dir.0), "26.2");
        assert!(!already_present("sodium", &present));

        // With both mods that need Fabric API already there, Fabric API isn't pulled in for them.
        let present: HashSet<String> = ["entityculling", "dynamic_fps"].map(str::to_owned).into();
        let ids: Vec<String> = performance_install_items(&plan, &present).into_iter().map(|i| i.version_id).collect();
        assert_eq!(ids, ["sodium", "lithium"]);

        // A disabled copy doesn't count: the set installs its own.
        std::fs::rename(dir.0.join("my-sodium.jar"), dir.0.join("my-sodium.jar.disabled")).unwrap();
        assert!(!already_present("sodium", &crate::perfcore::mod_ids_for(&crate::perfcore::scan_mods(&dir.0), "26.3")));
        assert!(!already_present("some-other-mod", &["some-other-mod".to_owned()].into()), "unknown projects are never skipped");
    }

    #[test]
    fn every_performance_set_mod_has_known_mod_ids() {
        for (slug, _) in PERFORMANCE_SET {
            assert!(PERFORMANCE_MOD_IDS.iter().any(|(s, ids)| *s == slug && !ids.is_empty()), "{slug}");
        }
    }

    #[test]
    fn a_file_flint_did_not_install_is_kept_when_an_install_takes_its_name() {
        let dir = TestDir::new();
        let mods = dir.0.join("mods");
        std::fs::create_dir_all(&mods).unwrap();
        std::fs::write(mods.join("sodium.jar"), b"the player's").unwrap();
        let kept = set_aside(&mods.join("sodium.jar")).unwrap();
        assert_eq!(kept, mods.join(REPLACED).join("sodium.jar"));
        assert_eq!(std::fs::read(&kept).unwrap(), b"the player's");
        assert!(!mods.join("sodium.jar").exists());

        // A second file of that name doesn't overwrite the first one kept.
        std::fs::write(mods.join("sodium.jar"), b"again").unwrap();
        assert_eq!(set_aside(&mods.join("sodium.jar")).unwrap(), mods.join(REPLACED).join("sodium (2).jar"));
        std::fs::write(mods.join("sodium.jar"), b"third").unwrap();
        assert_eq!(set_aside(&mods.join("sodium.jar")).unwrap(), mods.join(REPLACED).join("sodium (3).jar"));
        assert_eq!(std::fs::read(mods.join(REPLACED).join("sodium.jar")).unwrap(), b"the player's");

        // Kept jars are out of Fabric's reach and out of the content list.
        crate::perfcore::test_jar(&mods.join(REPLACED), "kept.jar", r#"{"id":"kept","version":"1"}"#);
        assert!(crate::perfcore::scan_mods(&mods).is_empty());
        assert!(scan(&mods, "mod").is_empty());
    }

    #[test]
    fn launch_preflight_stops_incompatible_mods_but_not_duplicates() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let fabric = Profile { id: "aaaaaaaaaaaa".into(), name: "Fabric".into(), game_version: "26.3".into(), loader: "fabric".into(), ..Default::default() };
        let vanilla = Profile { id: "bbbbbbbbbbbb".into(), name: "Vanilla".into(), loader: "vanilla".into(), ..fabric.clone() };
        crate::data::save_profiles(&state, &crate::data::ProfilesFile { selected: None, profiles: vec![fabric.clone(), vanilla.clone()] }).unwrap();
        let mods = state.paths.profile_dir(&fabric.id).join("mods");

        assert!(launch_preflight(&state, &fabric.id).is_ok(), "no mods folder yet");
        crate::perfcore::test_jar(&mods, "sodium-fabric-0.9.2+mc26.3.jar", r#"{"id":"sodium","version":"0.9.2+mc26.3","provides":["indium"]}"#);
        crate::perfcore::test_jar(&mods, "lithium.jar", r#"{"id":"lithium","version":"0.26.2"}"#);
        assert!(launch_preflight(&state, &fabric.id).is_ok());

        // A second Sodium the player dropped in, or a mod claiming an id another jar provides:
        // Fabric loads one of them and starts, as it did before Flint checked.
        crate::perfcore::test_jar(&mods, "sodium-old.jar", r#"{"id":"sodium","version":"0.9.1"}"#);
        crate::perfcore::test_jar(&mods, "indium.jar", r#"{"id":"indium","version":"1.0"}"#);
        assert!(launch_preflight(&state, &fabric.id).is_ok());
        std::fs::remove_file(mods.join("sodium-old.jar")).unwrap();
        std::fs::remove_file(mods.join("indium.jar")).unwrap();

        // A mod that breaks every version of another one present.
        crate::perfcore::test_jar(&mods, "fork.jar", r#"{"id":"fork","version":"1.0","breaks":{"lithium":"*"}}"#);
        let error = launch_preflight(&state, &fabric.id).unwrap_err();
        assert!(error.contains("fork.jar") && error.contains("lithium.jar"), "{error}");
        // The player lifted it with Fabric's dependency overrides: Fabric starts, so Flint doesn't block.
        let overrides = state.paths.profile_dir(&fabric.id).join("config").join("fabric_loader_dependencies.json");
        std::fs::create_dir_all(overrides.parent().unwrap()).unwrap();
        std::fs::write(&overrides, r#"{"version":1,"overrides":{"fork":{"-breaks":{"lithium":"*"}}}}"#).unwrap();
        assert!(launch_preflight(&state, &fabric.id).is_ok());
        std::fs::remove_file(&overrides).unwrap();
        // Switched off, it no longer counts.
        std::fs::rename(mods.join("fork.jar"), mods.join("fork.jar.disabled")).unwrap();
        assert!(launch_preflight(&state, &fabric.id).is_ok());
        crate::perfcore::test_jar(&mods, "fork.jar", r#"{"id":"fork","version":"1.0","breaks":{"lithium":"<0.20"}}"#);
        assert!(launch_preflight(&state, &fabric.id).is_ok(), "version ranges are left to Fabric");

        // Vanilla doesn't load mods, so the same folder doesn't block it.
        let vanilla_mods = state.paths.profile_dir(&vanilla.id).join("mods");
        crate::perfcore::test_jar(&vanilla_mods, "a.jar", r#"{"id":"a","version":"1","breaks":{"b":"*"}}"#);
        crate::perfcore::test_jar(&vanilla_mods, "b.jar", r#"{"id":"b","version":"1"}"#);
        assert!(launch_preflight(&state, &vanilla.id).is_ok());
    }
}
