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

pub mod livepacks;
pub mod updates;

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
#[derive(Serialize, Deserialize, Clone, Default, Debug, PartialEq)]
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
    /// The player keeps this version: Update all leaves it unticked, and it changes only when they pick it.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub pinned: bool,
}

fn manifest_path(state: &AppState, id: &str) -> PathBuf {
    state.paths.profile_dir(id).join("flint-content.json")
}

/// Reads the profile's content manifest, migrating the older mods-only manifest on first use.
pub fn load(state: &AppState, id: &str) -> Vec<ContentEntry> {
    let path = manifest_path(state, id);
    let entries: Vec<ContentEntry> = if path.exists() { read_json(&path) } else { read_json(&state.paths.profile_dir(id).join("flint-mods.json")) };
    trusted_entries(entries)
}

/// A plain file name, exactly as `safe_name` would accept and keep it.
fn plain_name(name: &str) -> bool {
    safe_name(name).is_ok_and(|kept| kept == name)
}

/// The manifest's file and world names are joined to the profile's folders when content is removed, rolled
/// back or updated, so an entry naming anything but a plain file there (an edited or planted manifest) is
/// dropped, and an earlier version that does is forgotten.
fn trusted_entries(entries: Vec<ContentEntry>) -> Vec<ContentEntry> {
    entries
        .into_iter()
        .filter(|e| plain_name(&e.file_name) && e.world.as_deref().is_none_or(plain_name))
        .map(|mut e| {
            if e.previous.as_ref().is_some_and(|p| !plain_name(&p.file_name)) {
                e.previous = None;
            }
            e
        })
        .collect()
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
        // A new profile for Modrinth content: 1.8.9 takes none of Modrinth's mods (see `takes_mods`).
        (None, Some(version)) if SUPPORTED_VERSIONS.contains(&version) && version != "1.8.9" => Ok(Profile {
            game_version: version.to_owned(),
            loader: "fabric".into(),
            ..Profile::default()
        }),
        _ => Err("Choose a Fabric version first.".into()),
    }
}

/// Whether Modrinth's Fabric mods and shader packs can go in this profile. Modrinth's Fabric builds for 1.8.9 are made
/// for Legacy Fabric, whose intermediary names aren't the Ornithe ones Flint's 1.8.9 Fabric runs on, so none of them would
/// load: a 1.8.9 Fabric profile takes resource packs (and Flint's own runtime) only.
pub fn takes_mods(profile: &Profile) -> bool {
    profile.loader == "fabric" && profile.game_version != "1.8.9"
}

/// Why a profile takes no mods or shader packs.
fn no_mods_reason(profile: &Profile) -> &'static str {
    if profile.loader == "fabric" {
        "Modrinth's mods for Minecraft 1.8.9 are built for Legacy Fabric, which this profile's Fabric can't load."
    } else {
        "This profile runs vanilla Minecraft, which can't load mods or shaders."
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

/// Where planning reads Modrinth's projects and versions: the app's cached client (`AppState`), or fixed
/// answers in tests.
pub(crate) trait Catalog {
    async fn project(&self, id: &str) -> CmdResult<Value>;
    async fn version(&self, id: &str) -> CmdResult<Value>;
    /// A project's versions for one kind of content on one Minecraft version, newest first.
    async fn versions(&self, project_id: &str, kind: &str, game_version: &str) -> CmdResult<Value>;
}

impl Catalog for AppState {
    async fn project(&self, id: &str) -> CmdResult<Value> {
        project(self, id).await
    }
    async fn version(&self, id: &str) -> CmdResult<Value> {
        version(self, id).await
    }
    async fn versions(&self, project_id: &str, kind: &str, game_version: &str) -> CmdResult<Value> {
        if !valid_id(project_id) {
            return Err("Invalid project".into());
        }
        let path = format!("/project/{project_id}/version{}", version_query(loaders_for(kind), Some(game_version)));
        self.modrinth.get_fresh(&self.http, &path, VERSIONS_TTL, "Couldn't check versions on Modrinth").await
    }
}

fn fits(version: &Value, kind: &str, game_version: &str) -> bool {
    let loaders = loaders_for(kind);
    let game = version["game_versions"].as_array().is_some_and(|v| v.iter().any(|x| x.as_str() == Some(game_version)));
    let loader = version["loaders"].as_array().is_some_and(|v| v.iter().any(|x| x.as_str().is_some_and(|l| loaders.contains(&l))));
    game && loader
}

async fn best_version(catalog: &impl Catalog, project_id: &str, kind: &str, profile: &Profile) -> CmdResult<Option<Value>> {
    let list = catalog.versions(project_id, kind, &profile.game_version).await?;
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
    resolve_among(state, &installed, profile, roots).await
}

/// Plans `roots` and everything they need for a profile holding `installed`.
async fn resolve_among(catalog: &impl Catalog, installed: &[ContentEntry], profile: &Profile, roots: Vec<Pending>) -> CmdResult<Value> {
    let installed_ids: HashSet<String> = installed.iter().map(|e| format!("{}:{}", e.kind, e.project_id)).collect();
    let installed_mods: HashMap<String, String> = installed.iter().filter(|e| e.kind == "mod").map(|e| (e.project_id.clone(), e.title.clone())).collect();
    let fabric = takes_mods(profile);

    let mut items: Vec<Value> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    // Every project in the plan that requires each project (`seen` keeps only the first one to ask for it).
    let mut required_by_all: HashMap<String, Vec<String>> = HashMap::new();
    let mut incompatible: HashMap<String, String> = HashMap::new();
    let mut queue = roots;

    if fabric && queue.iter().any(|p| p.kind == "shader") && !installed_mods.contains_key(IRIS) {
        if let Ok(iris) = catalog.project(IRIS).await {
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
                items.push(plan_item(&p, None, "unavailable", Some(no_mods_reason(profile).into())));
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
                Some(id) => match catalog.version(id).await {
                    Ok(v) if fits(&v, &p.kind, &profile.game_version) => Some(v),
                    Ok(_) => None,
                    Err(_) => None,
                },
                None => None,
            };
            let chosen = match chosen {
                Some(v) => Some(v),
                None => best_version(catalog, &pid, &p.kind, profile).await?,
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
                    (None, Some(vid)) => catalog.version(vid).await.ok().and_then(|x| x["project_id"].as_str().map(str::to_owned)),
                    _ => None,
                };
                let Some(dep_id) = dep_project else { continue };
                match dep["dependency_type"].as_str() {
                    Some(kind @ ("required" | "optional")) => {
                        if installed_mods.contains_key(&dep_id) {
                            continue;
                        }
                        if let Ok(dp) = catalog.project(&dep_id).await {
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
    let mut plan = resolve(&state, &profile, Some(&profile_id), roots).await?;
    // Whether the game, while it runs, turns new packs on by itself (Flint's runtime is in it): the install dialog says so.
    plan["gameApplies"] = json!(livepacks::game_applies(&state, &profile_id));
    Ok(plan)
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
    let fabric = takes_mods(&profile);
    let requests: Vec<ResolveRequest> = requests.into_iter().take(16).collect();
    let kinds: Vec<Option<&'static str>> = requests.iter().map(|r| resolve_kind(r.kind.as_deref())).collect();
    if vanilla_refuses_all(fabric, &kinds) {
        return Err(if profile.loader == "fabric" { no_mods_reason(&profile).into() } else { "This profile runs vanilla Minecraft. Mods need a Fabric profile.".into() });
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
///
/// While the profile's game runs, a batch of resource packs, shader packs and data packs goes in live (`livepacks`): each
/// file takes a name nothing in its folder has, so a pack the game has open is never replaced, and the game is told
/// (`enable_live`: turn the packs on in the game now). Mods need a restart and are refused then.
pub async fn install_batch(app: &AppHandle, state: &AppState, job: &str, profile_id: &str, items: Vec<InstallItem>, world: Option<String>, enable_live: bool) -> CmdResult<Value> {
    let profile = find_profile(state, profile_id)?;
    let items: Vec<InstallItem> = items.into_iter().take(60).collect();
    livepacks::check_kinds(state, profile_id, items.iter().map(|i| i.kind.as_deref().unwrap_or("mod")))?;
    let staging = state.paths.profile_dir(profile_id).join(format!(".flint-staging-{job}"));
    std::fs::create_dir_all(&staging).map_err(err("Could not prepare the download"))?;
    let total = items.len();

    // Several files are fetched at once (Settings → Storage's limit, at most MODRINTH_MAX): each takes a request or two
    // to Modrinth and a download, and a batch such as a new profile's performance set (seven mods) spent about 4 s on them
    // one after another. Each file is still verified, the batch is still all-or-nothing, and `staged` keeps its order.
    // With several at once the job counts finished files instead of one file's bytes.
    let parallel = crate::storage::modrinth_download_limit(&crate::data::load_settings(state));
    let quiet = total > 1 && parallel > 1;
    let finished = std::sync::atomic::AtomicUsize::new(0);
    if quiet {
        state.jobs.update(app, job, false, |j| {
            j.state = "running".into();
            j.stage = "Downloading".into();
            j.items_done = 0;
            j.items_total = total;
        });
    }
    let staged: CmdResult<Vec<Staged>> = {
        use futures_util::{StreamExt, TryStreamExt};
        let (profile, staging, world, finished) = (&profile, &staging, &world, &finished);
        // Made in a loop rather than by a stream's closure, whose borrowing futures Rust can't prove Send for every
        // lifetime; none starts before `buffered` polls it.
        let mut each = Vec::with_capacity(total);
        for (index, item) in items.iter().enumerate() {
            each.push(async move {
                if !quiet {
                    state.jobs.update(app, job, false, |j| {
                        j.state = "running".into();
                        j.stage = "Checking compatibility".into();
                        j.items_done = index;
                        j.items_total = total;
                    });
                }
                let kind = item.kind.clone().unwrap_or_else(|| "mod".into());
                if (kind == "mod" || kind == "shader") && !takes_mods(profile) {
                    return Err(no_mods_reason(profile).into());
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
                let size = download_verified(app, state, job, Fetch { url: &url, expected: Expected::Sha512(&sha512), max_bytes: jobs::listed_cap(file["size"].as_u64()), allowed_hosts: MODRINTH_CDN, quiet }, &target).await?;
                if quiet {
                    let done = finished.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
                    state.jobs.update(app, job, false, |j| j.items_done = done);
                }
                let requires: Vec<String> = v["dependencies"]
                    .as_array()
                    .map(|d| d.iter().filter(|x| x["dependency_type"] == "required").filter_map(|x| x["project_id"].as_str().map(str::to_owned)).collect())
                    .unwrap_or_default();
                Ok::<_, String>(Staged {
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
                        pinned: false,
                    },
                    staged: target,
                    dir,
                })
            });
        }
        futures_util::stream::iter(each).buffered(parallel).try_collect().await
    };

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
    let outcome = commit_staged(state, profile_id, staged, enable_live);
    let _ = std::fs::remove_dir_all(&staging);
    outcome
}

/// Moves a batch's verified downloads into the profile and records them (under the file lock). Whether the profile's game
/// runs is looked at again here, as it may have started or closed during the download: while it runs, only packs go in, live.
fn commit_staged(state: &AppState, profile_id: &str, staged: Vec<Staged>, enable_live: bool) -> CmdResult<Value> {
    let live = livepacks::check_kinds(state, profile_id, staged.iter().map(|s| s.entry.kind.as_str()))?;
    let mut manifest = load(state, profile_id);
    let mut placed = Vec::new();
    let mut notes: Vec<livepacks::Note> = Vec::new();
    for s in staged {
        std::fs::create_dir_all(&s.dir).map_err(err("Could not create the content folder"))?;
        let mut entry = s.entry;
        if live {
            // The running game may have the old file of this project (or one of the same name) open or turned on: it stays
            // where it is, and the new file takes a name nothing in the folder has.
            let name = livepacks::free_name(&s.dir, &entry.file_name);
            livepacks::place(&s.staged, &s.dir, &name)?;
            entry.file_name = name;
            if let Some(old) = manifest.iter().find(|m| m.kind == entry.kind && m.project_id == entry.project_id && m.world == entry.world) {
                entry.pinned = old.pinned;
            }
            notes.push(livepacks::Note::added(&entry.kind, &entry.file_name, &entry.title, entry.world.as_deref(), enable_live));
            manifest.retain(|m| !(m.kind == entry.kind && m.project_id == entry.project_id && m.world == entry.world));
            placed.push(entry.title.clone());
            manifest.push(entry);
            continue;
        }
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
            entry.pinned = old.pinned;
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
    if !live {
        return Ok(json!({ "installed": placed, "failed": [] }));
    }
    // The files are in: a game that misses the message still lists them when its pack screen opens.
    if let Err(error) = livepacks::tell_game(&state.paths.profile_dir(profile_id), &notes) {
        eprintln!("[flint] couldn't tell the running game about new packs: {error}");
    }
    let kinds: HashSet<&str> = notes.iter().map(|n| n.kind.as_str()).collect();
    Ok(json!({
        "installed": placed,
        "failed": [],
        "live": {
            "enable": enable_live && kinds.iter().any(|k| *k != "datapack"),
            "datapack": kinds.contains("datapack"),
            "applies": livepacks::game_applies(state, profile_id),
        },
    }))
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

/// `enable_live`: while the profile's game runs, the packs installed are turned on in it at once (the default).
#[tauri::command]
pub async fn content_install(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    title: String,
    items: Vec<InstallItem>,
    world: Option<String>,
    enable_live: Option<bool>,
) -> CmdResult<String> {
    let profile = find_profile(&state, &profile_id)?;
    livepacks::check_kinds(&state, &profile_id, items.iter().map(|i| i.kind.as_deref().unwrap_or("mod")))?;
    if items.is_empty() {
        return Err("Nothing to install.".into());
    }
    let (id, _) = state.jobs.create(&app, "install", Some(&profile.id), &title, items.len());
    jobs::spawn(&app, id.clone(), move |app, job| async move {
        let state = app.state::<AppState>();
        install_batch(&app, &state, &job, &profile_id, items, world, enable_live.unwrap_or(true)).await
    });
    Ok(id)
}

/// Onboarding installs mods inline and waits for the result.
#[tauri::command]
pub async fn mods_install(app: AppHandle, state: State<'_, AppState>, profile_id: String, items: Vec<InstallItem>) -> CmdResult<Value> {
    find_profile(&state, &profile_id)?;
    let (job, _) = state.jobs.create(&app, "install", Some(&profile_id), "Mods for your new profile", items.len());
    let outcome = install_batch(&app, &state, &job, &profile_id, items, None, false).await;
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
            "pinned": entry.pinned,
            // The jar's own logo: shown when there's no Modrinth icon, or it can't load (offline).
            "localIcon": (kind == "mod").then(|| crate::modicons::url(profile_id, &entry.file_name)),
        }));
    }
    for (name, enabled, is_dir) in files {
        if tracked.iter().any(|t| t.file_name == name) {
            continue;
        }
        let real = if enabled { name.clone() } else { format!("{name}{DISABLED}") };
        let (size, modified) = file_info(&dir.join(&real));
        let info = identified.get(&format!("{kind}/{}/{name}", world.unwrap_or("-"))).filter(|i| i.size == size && i.modified == modified).and_then(|i| i.info.clone());
        // A jar Modrinth hasn't named (added by hand, imported, CurseForge-only) still shows its own name and version.
        let jar = if kind == "mod" && !is_dir { crate::modicons::jar_info(&dir.join(&real)) } else { Default::default() };
        out.push(json!({
            "kind": kind,
            "fileName": name,
            "title": info
                .as_ref()
                .and_then(|i| i["title"].as_str())
                .map(str::to_owned)
                .or(jar.name)
                .unwrap_or_else(|| name.trim_end_matches(".jar").trim_end_matches(".zip").to_owned()),
            "projectId": info.as_ref().map(|i| i["projectId"].clone()),
            "versionId": info.as_ref().map(|i| i["versionId"].clone()),
            "versionNumber": info.as_ref().map(|i| i["versionNumber"].clone()).filter(|v| !v.is_null()).or(jar.version.map(Value::from)),
            "iconUrl": info.as_ref().map(|i| i["iconUrl"].clone()),
            "localIcon": (kind == "mod" && !is_dir).then(|| crate::modicons::url(profile_id, &name)),
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
            "pinned": false,
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
    let identified = identify(&state, &profile_id).await?;
    Ok(json!({ "identified": identified }))
}

/// Every content folder of a profile: mods, resource packs, shader packs and each world's data packs.
fn content_targets(state: &AppState, profile_id: &str) -> Vec<(String, Option<String>)> {
    let mut targets: Vec<(String, Option<String>)> = vec![("mod".into(), None), ("resourcepack".into(), None), ("shader".into(), None)];
    targets.extend(worlds(state, profile_id).into_iter().filter_map(|w| w["id"].as_str().map(|id| ("datapack".to_owned(), Some(id.to_owned())))));
    targets
}

/// Hashes the files Flint didn't install (once per version of each file) and asks Modrinth which project each one
/// is. Files in Flint's records without a project (pinned before Modrinth knew them) are asked about again, and
/// filled in when Modrinth knows them now. Returns how many hand-added files Modrinth knows.
async fn identify(state: &AppState, profile_id: &str) -> CmdResult<usize> {
    let manifest = load(state, profile_id);
    let mut identified: HashMap<String, Identified> = read_json(&identified_path(state, profile_id));
    let mut pending: Vec<(String, String)> = Vec::new();
    for (kind, world) in content_targets(state, profile_id) {
        let Ok(dir) = kind_dir(state, profile_id, &kind, world.as_deref()) else { continue };
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
            if size > jobs::UNLISTED_MAX {
                continue;
            }
            let path = real.clone();
            let Some(hash) = tokio::task::spawn_blocking(move || sha512_file(&path)).await.ok().flatten() else { continue };
            identified.insert(key.clone(), Identified { size, modified, sha512: hash.clone(), info: None });
            pending.push((key, hash));
        }
    }
    let unknown: Vec<String> = manifest.iter().filter(|m| m.project_id.is_empty() && !m.sha512.is_empty()).map(|m| m.sha512.clone()).collect();
    if !pending.is_empty() || !unknown.is_empty() {
        let hashes: Vec<&str> = pending.iter().map(|(_, h)| h.as_str()).chain(unknown.iter().map(String::as_str)).collect();
        let mut found = serde_json::Map::new();
        for chunk in hashes.chunks(200) {
            let answer = state.modrinth.post(&state.http, "/version_files", &json!({ "hashes": chunk, "algorithm": "sha512" }), "Couldn't identify files").await?;
            found.extend(answer.as_object().cloned().unwrap_or_default());
        }
        let ids: Vec<String> = found.values().filter_map(|v| v["project_id"].as_str().map(str::to_owned)).collect::<HashSet<_>>().into_iter().collect();
        let projects = if ids.is_empty() {
            json!([])
        } else {
            state
                .modrinth
                .get_fresh(&state.http, &format!("/projects?ids={}", encode(&json!(ids).to_string())), PROJECT_TTL, "Couldn't load projects")
                .await
                .unwrap_or_else(|_| json!([]))
        };
        let project_of = |v: &Value| projects.as_array().and_then(|l| l.iter().find(|p| p["id"] == v["project_id"])).cloned();
        for (key, hash) in &pending {
            let Some(v) = found.get(hash) else { continue };
            let project = project_of(v);
            if let Some(entry) = identified.get_mut(key) {
                entry.info = Some(json!({
                    "projectId": v["project_id"],
                    "versionId": v["id"],
                    "versionNumber": v["version_number"],
                    "title": project.as_ref().map(|p| p["title"].clone()),
                    "iconUrl": project.as_ref().map(|p| p["icon_url"].clone()),
                }));
            }
        }
        if unknown.iter().any(|h| found.contains_key(h)) {
            let _guard = state.file_lock.lock().await;
            let mut manifest = load(state, profile_id);
            for entry in manifest.iter_mut().filter(|m| m.project_id.is_empty()) {
                let Some(v) = found.get(&entry.sha512) else { continue };
                let project = project_of(v);
                entry.project_id = v["project_id"].as_str().unwrap_or_default().to_owned();
                entry.version_id = v["id"].as_str().unwrap_or_default().to_owned();
                entry.version_number = v["version_number"].as_str().unwrap_or_default().to_owned();
                if let Some(p) = project {
                    entry.title = p["title"].as_str().unwrap_or(&entry.title).to_owned();
                    entry.slug = p["slug"].as_str().unwrap_or_default().to_owned();
                    entry.icon_url = p["icon_url"].as_str().map(str::to_owned);
                }
            }
            save(state, profile_id, &manifest)?;
        }
    }
    write_json(&identified_path(state, profile_id), &identified)?;
    Ok(identified.values().filter(|i| i.info.is_some()).count())
}

// ---------- changing installed content ----------

#[tauri::command]
pub async fn content_set_enabled(state: State<'_, AppState>, profile_id: String, file_names: Vec<String>, enabled: bool) -> CmdResult<()> {
    find_profile(&state, &profile_id)?;
    // Only mods are switched here, and they need a restart.
    livepacks::check_kinds(&state, &profile_id, ["mod"])?;
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

/// Removes content. While the profile's game runs, packs can still be removed (mods need a restart): the game is told, so
/// they leave its lists. A pack the game holds open (one turned on, on Windows) can't be deleted; that says what to do.
#[tauri::command]
pub async fn content_remove(state: State<'_, AppState>, profile_id: String, items: Vec<ContentRef>) -> CmdResult<()> {
    find_profile(&state, &profile_id)?;
    livepacks::check_kinds(&state, &profile_id, items.iter().map(|i| i.kind.as_str()))?;
    let _guard = state.file_lock.lock().await;
    remove_items(&state, &profile_id, &items)
}

fn remove_items(state: &AppState, profile_id: &str, items: &[ContentRef]) -> CmdResult<()> {
    let live = livepacks::check_kinds(state, profile_id, items.iter().map(|i| i.kind.as_str()))?;
    let mut manifest = load(state, profile_id);
    let mut notes: Vec<livepacks::Note> = Vec::new();
    let mut outcome = Ok(());
    for item in items.iter().take(500) {
        let name = safe_name(&item.file_name)?.to_owned();
        let dir = kind_dir(state, profile_id, &item.kind, item.world.as_deref())?;
        let gone = (|| {
            for candidate in [name.clone(), format!("{name}{DISABLED}")] {
                let path = dir.join(&candidate);
                if path.parent() != Some(dir.as_path()) {
                    return Err("Unsafe path".to_owned());
                }
                let removed = if path.is_dir() {
                    std::fs::remove_dir_all(&path)
                } else if path.exists() {
                    std::fs::remove_file(&path)
                } else {
                    Ok(())
                };
                removed.map_err(|e| if live { livepacks::removal_error(&item.kind, &e) } else { format!("Could not remove it: {e}") })?;
            }
            Ok(())
        })();
        if let Err(e) = gone {
            // What was removed before it stays removed, and is recorded (and told to the game) below.
            outcome = Err(e);
            break;
        }
        let entry = manifest.iter().find(|m| m.kind == item.kind && m.file_name == name && m.world == item.world);
        let title = entry.map(|e| e.title.clone()).unwrap_or_default();
        if let Some(prev) = entry.and_then(|e| e.previous.as_ref()) {
            let _ = std::fs::remove_file(backup_dir(state, profile_id, &item.kind).join(&prev.file_name));
        }
        manifest.retain(|m| !(m.kind == item.kind && m.file_name == name && m.world == item.world));
        if live {
            notes.push(livepacks::Note::removed(&item.kind, &name, &title, item.world.as_deref()));
        }
    }
    save(state, profile_id, &manifest)?;
    if !notes.is_empty() {
        if let Err(error) = livepacks::tell_game(&state.paths.profile_dir(profile_id), &notes) {
            eprintln!("[flint] couldn't tell the running game about removed packs: {error}");
        }
    }
    outcome
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
    // A pin belongs to the item, not to one of its versions.
    manifest[index] = ContentEntry { pinned: current.pinned, ..*previous };
    save(&state, &profile_id, &manifest)
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

/// The performance set's plan for a profile that is still waiting for it, from Modrinth (None: the profile doesn't get the
/// set, or Modrinth couldn't be reached). Only reads: Modrinth's answers land in its cache.
async fn plan_performance_set(state: &AppState, profile_id: &str) -> Option<(Profile, Value)> {
    let profile = find_profile(state, profile_id).ok()?;
    if profile.performance.as_deref() != Some("pending") || !takes_mods(&profile) || !crate::data::performance_mods_enabled(state) {
        return None;
    }
    // The set's projects and their versions for this Minecraft version are asked for all at once (twelve requests, well
    // inside Modrinth's limits), so the resolver below, which goes one project at a time, finds them in the Modrinth cache
    // instead of waiting for each in turn.
    let projects = futures_util::future::join_all(PERFORMANCE_SET.iter().map(|(slug, _)| project(state, slug))).await;
    let mut roots = Vec::new();
    for ((_, reason), found) in PERFORMANCE_SET.iter().zip(projects) {
        if let Ok(p) = found {
            roots.push(Pending { project: p, kind: "mod".into(), version_id: None, source: "flint".into(), reason: Some(reason.to_string()), required: false, required_by: None });
        }
    }
    if roots.is_empty() {
        return None;
    }
    let ids: Vec<String> = roots.iter().filter_map(|p| p.project["id"].as_str().map(str::to_owned)).collect();
    futures_util::future::join_all(ids.iter().map(|id| Catalog::versions(state, id, "mod", &profile.game_version))).await;
    let plan = resolve(state, &profile, Some(profile_id), roots).await.ok()?;
    Some((profile, plan))
}

/// Starts working out a new profile's performance set in the background while its game files download: Modrinth's
/// answers (a few seconds of requests one after another) are then in the cache when `apply_performance_set` runs after
/// the download, which only installs the mods. It writes nothing, so an install that fails or is paused meanwhile leaves
/// nothing behind.
pub fn prefetch_performance_set(app: &AppHandle, profile_id: &str) {
    let (app, profile_id) = (app.clone(), profile_id.to_owned());
    tauri::async_runtime::spawn(async move {
        let state = app.state::<AppState>();
        let _ = plan_performance_set(&state, &profile_id).await;
    });
}

pub async fn apply_performance_set(app: &AppHandle, state: &AppState, profile_id: &str) {
    let Some((profile, plan)) = plan_performance_set(state, profile_id).await else { return };
    // The resolver only knows what Flint installed; jars added by hand are found by their mod ids.
    // Only a jar built for this Minecraft version counts: a copy for another version would stop
    // Fabric from starting if Flint left its own out.
    let present = crate::perfcore::mod_ids_for(&crate::perfcore::scan_mods(&state.paths.profile_dir(profile_id).join("mods")), &profile.game_version);
    let items = performance_install_items(plan["items"].as_array().map(Vec::as_slice).unwrap_or_default(), &present);
    let outcome = if items.is_empty() {
        Ok(json!({ "installed": [], "failed": [] }))
    } else {
        let (job, _) = state.jobs.create(app, "install", Some(profile_id), "Flint performance mods", items.len());
        let outcome = install_batch(app, state, &job, profile_id, items, None, false).await;
        state.jobs.finish(app, &job, &outcome);
        outcome
    };
    if outcome.is_ok() {
        let _ = crate::data::update_profile(state, profile_id, |p| p.performance = Some("applied".into())).await;
    }
}

/// Libraries mods commonly need: the mod id they declare, their Modrinth project and their name. A mod copied in from
/// another launcher often comes without the library its old launcher had installed; when one of these is missing, Play
/// adds it (`add_missing_libraries`) instead of letting Minecraft refuse to start.
const LIBRARIES: [(&str, &str, &str); 18] = [
    ("fabric-api", "fabric-api", "Fabric API"),
    ("fabric", "fabric-api", "Fabric API"),
    ("cloth-config", "cloth-config", "Cloth Config API"),
    ("cloth-config2", "cloth-config", "Cloth Config API"),
    ("yet_another_config_lib_v3", "yacl", "YetAnotherConfigLib"),
    ("architectury", "architectury-api", "Architectury API"),
    ("fabric-language-kotlin", "fabric-language-kotlin", "Fabric Language Kotlin"),
    ("owo", "owo-lib", "oωo"),
    ("geckolib", "geckolib", "GeckoLib"),
    ("modmenu", "modmenu", "Mod Menu"),
    ("xaerolib", "xaerolib", "XaeroLib"),
    ("forgeconfigapiport", "forge-config-api-port", "Forge Config API Port"),
    ("puzzleslib", "puzzles-lib", "Puzzles Lib"),
    ("midnightlib", "midnightlib", "MidnightLib"),
    ("resourcefullib", "resourceful-lib", "Resourceful Lib"),
    ("collective", "collective", "Collective"),
    ("libipn", "libipn", "libIPN"),
    ("walksylib", "walksylib", "Walksy Lib"),
];

/// "AppleSkin", "AppleSkin and Axiom", "AppleSkin, Axiom and 19 more".
fn mod_names(names: &[String]) -> String {
    match names {
        [] => "A mod".into(),
        [one] => one.clone(),
        [a, b] => format!("{a} and {b}"),
        [a, b, rest @ ..] => format!("{a}, {b} and {} more", rest.len()),
    }
}

/// Adds the libraries (`LIBRARIES`) that mods in a Fabric profile require and that aren't there, from Modrinth, built for
/// this profile's version: a visible download before the game starts. Anything else that's missing is left to
/// `dependency_check`, which names it. Offline or unanswered, nothing is added and the check explains.
pub async fn add_missing_libraries(app: &AppHandle, state: &AppState, profile_id: &str) {
    let Ok(profile) = find_profile(state, profile_id) else { return };
    if !takes_mods(&profile) {
        return;
    }
    let missing = crate::perfcore::missing_dependencies(&crate::perfcore::scan_mods(&state.paths.profile_dir(profile_id).join("mods")));
    let mut wanted: Vec<(&str, String)> = Vec::new();
    for m in &missing {
        if let Some((_, slug, _)) = LIBRARIES.iter().find(|(id, _, _)| *id == m.id) {
            if !wanted.iter().any(|(s, _)| s == slug) {
                wanted.push((slug, mod_names(&m.needed_by)));
            }
        }
    }
    let mut roots = Vec::new();
    for (slug, needed_by) in wanted {
        if let Ok(p) = project(state, slug).await {
            roots.push(Pending { project: p, kind: "mod".into(), version_id: None, source: "dependency".into(), reason: Some(format!("Needed by {needed_by}")), required: true, required_by: None });
        }
    }
    if roots.is_empty() {
        return;
    }
    let Ok(plan) = resolve(state, &profile, Some(profile_id), roots).await else { return };
    let items: Vec<InstallItem> = plan["items"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|i| i["status"] == "ok" && i["source"] != "optional")
        .filter_map(|i| Some(InstallItem { version_id: i["versionId"].as_str()?.to_owned(), kind: Some("mod".into()), source: "dependency".into(), reason: i["reason"].as_str().map(str::to_owned) }))
        .collect();
    if items.is_empty() {
        return;
    }
    let (job, _) = state.jobs.create(app, "install", Some(profile_id), "Libraries your mods need", items.len());
    let outcome = install_batch(app, state, &job, profile_id, items, None, false).await;
    state.jobs.finish(app, &job, &outcome);
}

/// The player-facing lines for mods that require others the profile doesn't have (at most three).
fn missing_lines(missing: &[crate::perfcore::Missing]) -> Vec<String> {
    missing
        .iter()
        .take(3)
        .map(|m| {
            let what = LIBRARIES.iter().find(|(id, _, _)| *id == m.id).map(|(_, _, name)| (*name).to_owned()).unwrap_or_else(|| format!("the mod “{}”", m.id));
            let one = m.needed_by.len() == 1;
            format!(
                "{} {} {what}, which isn't in this profile. Add it from the Library (or switch it back on), or remove {}.",
                mod_names(&m.needed_by),
                if one { "needs" } else { "need" },
                if one { "that mod" } else { "those mods" }
            )
        })
        .collect()
}

/// Stops a launch Fabric would refuse because a mod requires another that isn't in the profile (or is switched off),
/// naming them, instead of leaving the player with Fabric's error window. Fabric's dependency overrides
/// (`config/fabric_loader_dependencies.json`) can lift a requirement, so a profile with them is left to Fabric.
pub fn dependency_check(state: &AppState, profile_id: &str) -> CmdResult<()> {
    let Ok(profile) = find_profile(state, profile_id) else { return Ok(()) };
    let dir = state.paths.profile_dir(profile_id);
    if profile.loader != "fabric" || dir.join("config").join("fabric_loader_dependencies.json").exists() {
        return Ok(());
    }
    let lines = missing_lines(&crate::perfcore::missing_dependencies(&crate::perfcore::scan_mods(&dir.join("mods"))));
    if lines.is_empty() {
        Ok(())
    } else {
        Err(lines.join("\n"))
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
        // A hand-added jar Flint only keeps a pin for isn't Flint's to miss.
        if !on && !off && !entry.project_id.is_empty() {
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
        // Mods dropped in as .zip, .rar or .7z (and packs dropped in by mistake) first become what Fabric loads.
        for line in crate::modarchives::tidy(&state.paths.profile_dir(profile_id)).lines {
            eprintln!("[flint] mods folder: {line}");
        }
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

/// Tokens, addresses, player UUIDs and the user's home folder out of log text (redact.rs).
pub use crate::redact::redact;

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

    #[test]
    fn missing_requirements_are_explained_in_plain_words() {
        let missing = |id: &str, by: &[&str]| crate::perfcore::Missing { id: id.into(), needed_by: by.iter().map(|s| s.to_string()).collect() };
        let lines = missing_lines(&[
            missing("fabric-api", &["AppleSkin", "Axiom", "Capes", "Voxy"]),
            missing("cloth-config2", &["Minimap"]),
            missing("odd_lib", &["A", "B"]),
            missing("never_shown", &["C"]),
        ]);
        assert_eq!(lines.len(), 3, "at most three lines");
        assert_eq!(lines[0], "AppleSkin, Axiom and 2 more need Fabric API, which isn't in this profile. Add it from the Library (or switch it back on), or remove those mods.");
        assert_eq!(lines[1], "Minimap needs Cloth Config API, which isn't in this profile. Add it from the Library (or switch it back on), or remove that mod.");
        assert!(lines[2].starts_with("A and B need the mod “odd_lib”"), "{}", lines[2]);
        // Every library Play adds has a real Modrinth project and a name.
        for (id, slug, name) in LIBRARIES {
            assert!(!id.is_empty() && crate::modrinth::valid_id(slug) && !name.is_empty(), "{id}");
        }
    }


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
    fn manifest_entries_naming_files_outside_their_folder_are_dropped() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let entry = |file: &str| ContentEntry { kind: "mod".into(), project_id: file.into(), version_id: "v".into(), title: file.into(), file_name: file.into(), ..Default::default() };
        let mut kept_without_backup = entry("lithium.jar");
        kept_without_backup.previous = Some(Box::new(entry("../../../outside.jar")));
        let mut world_pack = entry("pack.zip");
        world_pack.world = Some("../elsewhere".into());
        let mut with_backup = entry("sodium-0.9.2.jar");
        with_backup.previous = Some(Box::new(entry("sodium-0.9.1.jar")));
        let planted = vec![with_backup, entry("../../outside.txt"), entry("..\\outside.txt"), entry("C:\\Windows\\x.jar"), entry(".hidden.jar"), kept_without_backup, world_pack];
        write_json(&manifest_path(&state, "p1"), &planted).unwrap();

        let loaded = load(&state, "p1");
        let names: Vec<&str> = loaded.iter().map(|e| e.file_name.as_str()).collect();
        assert_eq!(names, ["sodium-0.9.2.jar", "lithium.jar"]);
        assert_eq!(loaded[0].previous.as_ref().map(|p| p.file_name.as_str()), Some("sodium-0.9.1.jar"), "a plain earlier version is kept");
        assert!(loaded[1].previous.is_none(), "an earlier version outside the folder is forgotten");
        // The older mods-only manifest is read the same way.
        std::fs::remove_file(manifest_path(&state, "p1")).unwrap();
        write_json(&state.paths.profile_dir("p1").join("flint-mods.json"), &vec![entry("/etc/passwd"), entry("ok.jar")]).unwrap();
        assert_eq!(load(&state, "p1").iter().map(|e| e.file_name.as_str()).collect::<Vec<_>>(), ["ok.jar"]);
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
    fn a_1_8_9_fabric_profile_takes_no_modrinth_mods() {
        let profile = |version: &str, loader: &str| Profile { game_version: version.into(), loader: loader.into(), ..Profile::default() };
        assert!(takes_mods(&profile("26.3", "fabric")));
        assert!(takes_mods(&profile("1.21.11", "fabric")));
        assert!(!takes_mods(&profile("1.8.9", "fabric")), "Modrinth's 1.8.9 Fabric mods are Legacy Fabric builds");
        assert!(!takes_mods(&profile("26.3", "vanilla")));
        assert!(no_mods_reason(&profile("1.8.9", "fabric")).contains("Legacy Fabric"));
        assert!(no_mods_reason(&profile("26.3", "vanilla")).contains("vanilla"));
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

    /// A verified download waiting in the profile's staging folder, as `install_batch` leaves it.
    fn staged_file(state: &AppState, kind: &str, file: &str, world: Option<&str>, bytes: &[u8]) -> Staged {
        let staging = state.paths.profile_dir("p1").join(".flint-staging-test");
        std::fs::create_dir_all(&staging).unwrap();
        std::fs::write(staging.join(file), bytes).unwrap();
        Staged {
            entry: ContentEntry {
                kind: kind.into(),
                project_id: format!("proj-{file}"),
                version_id: "v1".into(),
                title: file.trim_end_matches(".zip").into(),
                file_name: file.into(),
                world: world.map(str::to_owned),
                ..Default::default()
            },
            staged: staging.join(file),
            dir: kind_dir(state, "p1", kind, world).unwrap(),
        }
    }

    fn handoff(state: &AppState) -> Value {
        serde_json::from_slice(&std::fs::read(state.paths.profile_dir("p1").join(livepacks::FILE)).unwrap()).unwrap()
    }

    #[test]
    fn a_pack_installed_while_the_game_runs_goes_in_beside_the_one_in_use_and_the_game_is_told() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let packs = state.paths.profile_dir("p1").join("resourcepacks");
        std::fs::create_dir_all(&packs).unwrap();
        // The player's own pack of the same name, turned on in the running game (and open, on Windows).
        std::fs::write(packs.join("Dirt Tint.zip"), b"in use").unwrap();
        // Flint's runtime is in the game: it turns packs on by itself.
        let mods = state.paths.profile_dir("p1").join("mods");
        std::fs::create_dir_all(&mods).unwrap();
        std::fs::write(mods.join(crate::runtime::FILE_NAME), b"runtime").unwrap();
        state.running.lock().unwrap().insert("p1".into(), RunningGame { kill: None });

        let shaders = state.paths.profile_dir("p1").join("shaderpacks");
        let batch = vec![staged_file(&state, "resourcepack", "Dirt Tint.zip", None, b"new"), staged_file(&state, "shader", "Tint.zip", None, b"shader")];
        let result = commit_staged(&state, "p1", batch, true).unwrap();
        assert_eq!(result["installed"], json!(["Dirt Tint", "Tint"]));
        assert_eq!(result["live"], json!({ "enable": true, "datapack": false, "applies": true }));
        assert_eq!(std::fs::read(packs.join("Dirt Tint.zip")).unwrap(), b"in use", "never replaced");
        assert_eq!(std::fs::read(packs.join("Dirt Tint (2).zip")).unwrap(), b"new");
        assert_eq!(std::fs::read(shaders.join("Tint.zip")).unwrap(), b"shader");
        let manifest = load(&state, "p1");
        assert_eq!(manifest.iter().map(|m| m.file_name.as_str()).collect::<Vec<_>>(), ["Dirt Tint (2).zip", "Tint.zip"]);

        let file = handoff(&state);
        let changes = file["changes"].as_array().unwrap();
        assert_eq!(changes.len(), 2);
        assert_eq!((changes[0]["kind"].as_str(), changes[0]["file"].as_str(), changes[0]["enable"].as_bool()), (Some("resourcepack"), Some("Dirt Tint (2).zip"), Some(true)));
        assert_eq!((changes[1]["kind"].as_str(), changes[1]["file"].as_str(), changes[1]["action"].as_str()), (Some("shaderpack"), Some("Tint.zip"), Some("added")));
        assert_eq!(changes[0]["revision"], file["revision"]);

        // "Turn it on in the game now" off: the game only lists it.
        let result = commit_staged(&state, "p1", vec![staged_file(&state, "resourcepack", "Quiet.zip", None, b"q")], false).unwrap();
        assert_eq!(result["live"], json!({ "enable": false, "datapack": false, "applies": true }));
        let file = handoff(&state);
        let last = file["changes"].as_array().unwrap().last().unwrap().clone();
        assert_eq!((last["file"].as_str(), last["enable"].as_bool()), (Some("Quiet.zip"), Some(false)));
        assert_eq!(file["changes"].as_array().unwrap().len(), 3, "the earlier batch is still there for a game that hasn't looked yet");
    }

    #[test]
    fn a_data_pack_goes_in_live_to_its_world_and_mods_wait_for_a_restart() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let world = state.paths.profile_dir("p1").join("saves").join("New World");
        std::fs::create_dir_all(&world).unwrap();
        std::fs::write(world.join("level.dat"), b"x").unwrap();
        state.running.lock().unwrap().insert("p1".into(), RunningGame { kill: None });

        let result = commit_staged(&state, "p1", vec![staged_file(&state, "datapack", "Trees.zip", Some("New World"), b"dp")], true).unwrap();
        assert_eq!(result["live"], json!({ "enable": false, "datapack": true, "applies": false }), "no runtime in this game");
        assert!(world.join("datapacks").join("Trees.zip").is_file());
        let change = handoff(&state)["changes"][0].clone();
        assert_eq!(change["world"], "New World");
        assert_eq!(change["enable"], false, "a data pack loads at /reload or the next world load");

        // A mod in the batch: nothing is moved, and the reason is the restart.
        let mods = state.paths.profile_dir("p1").join("mods");
        let batch = vec![staged_file(&state, "resourcepack", "Fine.zip", None, b"f"), staged_file(&state, "mod", "iris.jar", None, b"jar")];
        assert_eq!(commit_staged(&state, "p1", batch, true).unwrap_err(), "Mods need a restart: close Minecraft first.");
        assert!(!mods.join("iris.jar").exists());
        assert!(!state.paths.profile_dir("p1").join("resourcepacks").join("Fine.zip").exists());
    }

    #[test]
    fn with_the_game_closed_an_install_is_as_before_and_tells_no_game() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let batch = vec![staged_file(&state, "resourcepack", "Dirt Tint.zip", None, b"new"), staged_file(&state, "mod", "lithium.jar", None, b"jar")];
        let result = commit_staged(&state, "p1", batch, true).unwrap();
        assert_eq!(result, json!({ "installed": ["Dirt Tint", "lithium.jar"], "failed": [] }));
        assert!(state.paths.profile_dir("p1").join("resourcepacks").join("Dirt Tint.zip").is_file());
        assert!(state.paths.profile_dir("p1").join("mods").join("lithium.jar").is_file());
        assert!(!state.paths.profile_dir("p1").join(livepacks::FILE).exists());
    }

    #[test]
    fn packs_are_removed_live_and_mods_are_not() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let batch = vec![staged_file(&state, "resourcepack", "Old.zip", None, b"old"), staged_file(&state, "mod", "sodium.jar", None, b"jar")];
        commit_staged(&state, "p1", batch, false).unwrap();
        state.running.lock().unwrap().insert("p1".into(), RunningGame { kill: None });

        let pack = ContentRef { kind: "resourcepack".into(), file_name: "Old.zip".into(), world: None };
        remove_items(&state, "p1", &[pack]).unwrap();
        assert!(!state.paths.profile_dir("p1").join("resourcepacks").join("Old.zip").exists());
        let change = handoff(&state)["changes"][0].clone();
        assert_eq!((change["action"].as_str(), change["file"].as_str(), change["title"].as_str()), (Some("removed"), Some("Old.zip"), Some("Old")));

        let jar = ContentRef { kind: "mod".into(), file_name: "sodium.jar".into(), world: None };
        assert_eq!(remove_items(&state, "p1", &[jar]), Err("Mods need a restart: close Minecraft first.".into()));
        assert!(state.paths.profile_dir("p1").join("mods").join("sodium.jar").is_file());
        assert_eq!(load(&state, "p1").iter().map(|m| m.file_name.as_str()).collect::<Vec<_>>(), ["sodium.jar"]);
    }

    /// Dev-only: the launcher's own live install and removal (`commit_staged`, `remove_items`) against a real game folder, for the
    /// QA tours that run a game and check what it shows (game-runtime/qa/tools/live-packs.mjs). Only the Modrinth download is
    /// left out: the pack comes from a file. FLINT_QA_ROOT is a folder laid out as Flint's data folder (`profiles/<id>` is the game
    /// folder), FLINT_QA_PROFILE the profile's id, FLINT_QA_ACTION `install` or `remove`, FLINT_QA_KIND, FLINT_QA_FILE (the pack
    /// to install, or the file name to remove), FLINT_QA_TITLE, FLINT_QA_ENABLE (`false` for "Turn it on in the game now" off),
    /// FLINT_QA_WORLD (a data pack's world). The profile's game counts as running, as when Flint started it. Prints one
    /// `QA-DRIVER` line with the outcome.
    #[test]
    #[ignore = "QA driver: run by game-runtime/qa/tools/live-packs.mjs against a running game"]
    fn qa_live_pack_change() {
        let env = |key: &str| std::env::var(key).unwrap_or_default();
        let state = AppState::for_tests(PathBuf::from(env("FLINT_QA_ROOT")));
        let id = env("FLINT_QA_PROFILE");
        assert!(crate::core::is_safe_id(&id), "FLINT_QA_PROFILE");
        state.running.lock().unwrap().insert(id.clone(), RunningGame { kill: None });
        let kind = env("FLINT_QA_KIND");
        let world = Some(env("FLINT_QA_WORLD")).filter(|w| !w.is_empty());
        let outcome: CmdResult<Value> = match env("FLINT_QA_ACTION").as_str() {
            "install" => {
                let source = PathBuf::from(env("FLINT_QA_FILE"));
                let name = source.file_name().unwrap().to_string_lossy().into_owned();
                let staging = state.paths.profile_dir(&id).join(".flint-staging-qa");
                std::fs::create_dir_all(&staging).unwrap();
                std::fs::copy(&source, staging.join(&name)).unwrap();
                let entry = ContentEntry {
                    kind: kind.clone(),
                    project_id: format!("qa-{}", name.to_ascii_lowercase().replace(|c: char| !c.is_ascii_alphanumeric(), "")),
                    version_id: "qa".into(),
                    title: env("FLINT_QA_TITLE"),
                    file_name: name.clone(),
                    world: world.clone(),
                    source: "user".into(),
                    installed_at: now_secs(),
                    ..Default::default()
                };
                let dir = kind_dir(&state, &id, &kind, world.as_deref());
                let result = dir.and_then(|dir| commit_staged(&state, &id, vec![Staged { entry, staged: staging.join(&name), dir }], env("FLINT_QA_ENABLE") != "false"));
                let _ = std::fs::remove_dir_all(&staging);
                result
            }
            "remove" => remove_items(&state, &id, &[ContentRef { kind: kind.clone(), file_name: env("FLINT_QA_FILE"), world }]).map(|()| json!("removed")),
            other => Err(format!("unknown FLINT_QA_ACTION {other:?}")),
        };
        println!("QA-DRIVER {}", json!({ "ok": outcome.is_ok(), "result": outcome.as_ref().ok(), "error": outcome.as_ref().err() }));
    }
}
