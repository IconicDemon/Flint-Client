//! Content updates: finding newer builds of what a profile has (`content_updates`), keeping a version
//! (`content_pin`), and replacing files safely (`content_update`).
//!
//! An update is offered only for a build made for the profile's Minecraft version and loader, at least as
//! stable as the installed one, and newer. Updating is one transaction: everything is planned and checked
//! first, every new file is downloaded and verified into staging, and only then are files swapped. The file
//! being replaced goes to `.flint-backup` as the item's `previous` (what `content_rollback` restores), and an
//! item whose swap fails gets its old file back, so no item is ever left without a version that worked.

use super::*;

/// How many other versions an update offers to choose from besides the one it proposes.
const ALTERNATIVES: usize = 6;
/// The most of a changelog sent to the app for each version.
const CHANGELOG_CHARS: usize = 1500;

fn stability(v: &Value) -> u8 {
    match v["version_type"].as_str() {
        Some("release") => 2,
        Some("beta") => 1,
        _ => 0,
    }
}

/// Whether Flint can install a version's file as this kind of content: a plain file name of the right type,
/// with a published SHA-512 and an address.
fn installable(v: &Value, kind: &str) -> bool {
    primary_file(v).is_some_and(|f| {
        let name = f["filename"].as_str().unwrap_or_default();
        plain_name(name) && file_fits(kind, name) && f["hashes"]["sha512"].as_str().is_some_and(|h| !h.is_empty()) && f["url"].is_string()
    })
}

/// What an installed version can be updated to, from its project's versions: the newest build for this
/// profile that is newer and at least as stable as the installed one, then the other newer builds for this
/// profile (any channel, newest first) to pick instead. Each build's own Minecraft versions and loaders are
/// checked, not only the query that listed it. None when nothing newer qualifies.
fn update_options(installed: &Value, list: &[Value], kind: &str, game_version: &str) -> Option<(Value, Vec<Value>)> {
    let since = installed["date_published"].as_str().unwrap_or_default();
    let mut newer: Vec<&Value> = list
        .iter()
        .filter(|v| v["id"] != installed["id"] && v["date_published"].as_str().unwrap_or_default() > since)
        .filter(|v| fits(v, kind, game_version) && installable(v, kind))
        .collect();
    newer.sort_by(|a, b| b["date_published"].as_str().cmp(&a["date_published"].as_str()));
    let at = newer.iter().position(|v| stability(v) >= stability(installed))?;
    let proposed = newer.remove(at).clone();
    Some((proposed, newer.into_iter().take(ALTERNATIVES).cloned().collect()))
}

/// A changelog short enough to send with every version, ending at a line break where one is near.
fn changelog(v: &Value) -> String {
    let text = v["changelog"].as_str().unwrap_or_default().trim();
    if text.chars().count() <= CHANGELOG_CHARS {
        return text.to_owned();
    }
    let mut cut: String = text.chars().take(CHANGELOG_CHARS).collect();
    if let Some(at) = cut.rfind('\n').filter(|&at| at > cut.len() / 2) {
        cut.truncate(at);
    }
    cut.push_str("\n\n…");
    cut
}

/// "Minecraft 26.2 with Fabric": what a kind of content must be built for in a profile.
fn made_for(kind: &str, game_version: &str) -> String {
    match kind {
        "mod" => format!("Minecraft {game_version} with Fabric"),
        "shader" => format!("Minecraft {game_version} with Iris"),
        _ => format!("Minecraft {game_version}"),
    }
}

// ---------- what a version needs ----------

/// A required dependency as a version lists it: by project, by version, or both.
#[derive(Debug, PartialEq)]
struct Dependency {
    project: Option<String>,
    version: Option<String>,
}

/// The required projects a version lists that the profile doesn't have (`present`: project id → title), and the
/// titles of installed projects it says it can't run with.
fn dependency_needs(version: &Value, present: &HashMap<String, String>) -> (Vec<Dependency>, Vec<String>) {
    let mut missing = Vec::new();
    let mut clashes = Vec::new();
    for dep in version["dependencies"].as_array().into_iter().flatten() {
        let project = dep["project_id"].as_str().filter(|p| valid_id(p)).map(str::to_owned);
        let pinned = dep["version_id"].as_str().filter(|v| valid_id(v)).map(str::to_owned);
        match dep["dependency_type"].as_str() {
            Some("required") if !project.as_ref().is_some_and(|p| present.contains_key(p)) && (project.is_some() || pinned.is_some()) => {
                missing.push(Dependency { project, version: pinned });
            }
            Some("incompatible") => clashes.extend(project.as_ref().and_then(|p| present.get(p)).cloned()),
            _ => {}
        }
    }
    (missing, clashes)
}

/// The projects in a profile, by id, with their titles: Flint's records and the hand-added files Modrinth knows.
fn present_titles(installed: &[ContentEntry]) -> HashMap<String, String> {
    installed.iter().filter(|e| !e.project_id.is_empty()).map(|e| (e.project_id.clone(), e.title.clone())).collect()
}

/// Flint's records plus the hand-added files Modrinth identified, as the resolver counts what's installed.
fn present_entries(manifest: &[ContentEntry], identified: &HashMap<String, Identified>) -> Vec<ContentEntry> {
    let mut out = manifest.to_vec();
    for (key, found) in identified {
        let Some(info) = &found.info else { continue };
        let kind = key.split('/').next().unwrap_or_default();
        let project_id = info["projectId"].as_str().unwrap_or_default();
        if project_id.is_empty() || out.iter().any(|e| e.kind == kind && e.project_id == project_id) {
            continue;
        }
        out.push(ContentEntry {
            kind: kind.to_owned(),
            project_id: project_id.to_owned(),
            title: info["title"].as_str().unwrap_or(project_id).to_owned(),
            source: "manual".into(),
            ..Default::default()
        });
    }
    out
}

/// Dependency plans, each missing project planned once per check or update.
#[derive(Default)]
struct Needs(HashMap<String, Result<Vec<Value>, String>>);

impl Needs {
    /// What putting `version` (of `title`, a `kind`) into the profile adds: the resolver's plan items for every
    /// required project it lacks, their own needs included. Err says why it can't go in.
    async fn of(&mut self, catalog: &impl Catalog, profile: &Profile, installed: &[ContentEntry], version: &Value, title: &str, kind: &str) -> Result<Vec<Value>, String> {
        let present = present_titles(installed);
        let (missing, clashes) = dependency_needs(version, &present);
        if let Some(other) = clashes.first() {
            return Err(format!("Doesn't work with {other}, which is in this profile"));
        }
        let mut adds: Vec<Value> = Vec::new();
        for dep in missing {
            let project_id = match (dep.project, &dep.version) {
                (Some(id), _) => id,
                (None, Some(vid)) => match catalog.version(vid).await {
                    Ok(v) => v["project_id"].as_str().unwrap_or_default().to_owned(),
                    Err(_) => return Err("Flint couldn't check what this version needs. Try again later.".into()),
                },
                (None, None) => continue,
            };
            if project_id.is_empty() || present.contains_key(&project_id) {
                continue;
            }
            if !self.0.contains_key(&project_id) {
                let plan = Self::plan(catalog, profile, installed, &project_id, dep.version, title, kind).await;
                self.0.insert(project_id.clone(), plan);
            }
            match &self.0[&project_id] {
                Ok(items) => {
                    for item in items {
                        if !adds.iter().any(|a| a["projectId"] == item["projectId"]) {
                            adds.push(item.clone());
                        }
                    }
                }
                Err(reason) => return Err(reason.clone()),
            }
        }
        Ok(adds)
    }

    async fn plan(catalog: &impl Catalog, profile: &Profile, installed: &[ContentEntry], project_id: &str, version: Option<String>, title: &str, kind: &str) -> Result<Vec<Value>, String> {
        let unchecked = || "Flint couldn't check what this version needs. Try again later.".to_string();
        let project = catalog.project(project_id).await.map_err(|_| unchecked())?;
        let dep_kind = match project_kind(&project) {
            k if k == "datapack" => kind.to_owned(),
            k => k,
        };
        let root = Pending { project, kind: dep_kind, version_id: version, source: "dependency".into(), reason: None, required_by: Some(title.to_owned()), required: true };
        let plan = resolve_among(catalog, installed, profile, vec![root]).await.map_err(|_| unchecked())?;
        if let Some(blocker) = plan["blockers"].as_array().and_then(|b| b.first()).and_then(Value::as_str) {
            return Err(format!("Needs {blocker}"));
        }
        Ok(plan["items"].as_array().into_iter().flatten().filter(|i| i["status"] == "ok" && i["required"] == true).cloned().collect())
    }
}

// ---------- checking for updates ----------

/// What the update check asks Modrinth beyond projects and versions: the app's cached client, or fixed answers in tests.
trait UpdateSource: Catalog {
    /// The newest version, by file hash, among those for these loaders and this Minecraft version.
    async fn latest(&self, hashes: &[String], kind: &str, game_version: &str) -> CmdResult<Value>;
}

impl UpdateSource for AppState {
    async fn latest(&self, hashes: &[String], kind: &str, game_version: &str) -> CmdResult<Value> {
        let body = json!({ "hashes": hashes, "algorithm": "sha512", "loaders": loaders_for(kind), "game_versions": [game_version] });
        self.modrinth.post(&self.http, "/version_files/update", &body, "Couldn't check for updates").await
    }
}

/// One installed file the check can ask about: its project is known, by Flint's records or by its hash.
#[derive(Clone, Debug, Default)]
struct Installed {
    kind: String,
    world: Option<String>,
    file_name: String,
    path: PathBuf,
    title: String,
    icon_url: Option<String>,
    project_id: String,
    version_id: String,
    version_number: String,
    sha512: String,
    tracked: bool,
    pinned: bool,
}

/// The files of every kind the profile takes: the ones the check can ask about, and the ones it can't (`unmanaged`:
/// folders, and files Modrinth doesn't know by their hash). Nothing is guessed from a file name.
fn survey(state: &AppState, profile: &Profile) -> (Vec<Installed>, Vec<Value>) {
    let manifest = load(state, &profile.id);
    let identified: HashMap<String, Identified> = read_json(&identified_path(state, &profile.id));
    let mut known = Vec::new();
    let mut unmanaged = Vec::new();
    for (kind, world) in content_targets(state, &profile.id) {
        if (kind == "mod" || kind == "shader") && !takes_mods(profile) {
            continue;
        }
        let Ok(dir) = kind_dir(state, &profile.id, &kind, world.as_deref()) else { continue };
        for (name, enabled, is_dir) in scan(&dir, &kind) {
            let path = dir.join(if enabled { name.clone() } else { format!("{name}{DISABLED}") });
            let unchecked = |title: &str| json!({ "kind": kind, "fileName": name, "world": world, "title": title, "folder": is_dir });
            if let Some(entry) = manifest.iter().find(|m| m.kind == kind && m.file_name == name && m.world == world) {
                if is_dir || entry.project_id.is_empty() || entry.version_id.is_empty() {
                    unmanaged.push(unchecked(&entry.title));
                } else {
                    known.push(Installed {
                        kind: kind.clone(),
                        world: world.clone(),
                        file_name: name.clone(),
                        path,
                        title: entry.title.clone(),
                        icon_url: entry.icon_url.clone(),
                        project_id: entry.project_id.clone(),
                        version_id: entry.version_id.clone(),
                        version_number: entry.version_number.clone(),
                        sha512: entry.sha512.clone(),
                        tracked: true,
                        pinned: entry.pinned,
                    });
                }
                continue;
            }
            let (size, modified) = file_info(&path);
            let key = format!("{kind}/{}/{name}", world.as_deref().unwrap_or("-"));
            let found = identified.get(&key).filter(|i| !is_dir && i.size == size && i.modified == modified).and_then(|i| i.info.as_ref().map(|info| (i, info)));
            match found {
                Some((i, info)) if info["projectId"].as_str().is_some_and(|p| !p.is_empty()) => known.push(Installed {
                    kind: kind.clone(),
                    world: world.clone(),
                    file_name: name.clone(),
                    path,
                    title: info["title"].as_str().unwrap_or(&name).to_owned(),
                    icon_url: info["iconUrl"].as_str().map(str::to_owned),
                    project_id: info["projectId"].as_str().unwrap_or_default().to_owned(),
                    version_id: info["versionId"].as_str().unwrap_or_default().to_owned(),
                    version_number: info["versionNumber"].as_str().unwrap_or_default().to_owned(),
                    sha512: i.sha512.clone(),
                    tracked: false,
                    pinned: false,
                }),
                _ => unmanaged.push(unchecked(name.trim_end_matches(".jar").trim_end_matches(".zip"))),
            }
        }
    }
    (known, unmanaged)
}

/// One way to update: a version, what it adds to the profile, and why it can't go in (if it can't).
fn version_option(v: &Value, adds: &[Value], blocked: Option<String>) -> Value {
    json!({
        "versionId": v["id"],
        "versionNumber": v["version_number"],
        "versionType": v["version_type"],
        "datePublished": v["date_published"],
        "changelog": changelog(v),
        "size": primary_file(v).and_then(|f| f["size"].as_u64()),
        "adds": adds.iter().map(|a| json!({
            "projectId": a["projectId"],
            "title": a["title"],
            "iconUrl": a["iconUrl"],
            "kind": a["kind"],
            "versionId": a["versionId"],
            "versionNumber": a["versionNumber"],
            "size": a["size"],
        })).collect::<Vec<_>>(),
        "blocked": blocked,
    })
}

/// The updates for the files the check can ask about.
async fn find_updates(source: &impl UpdateSource, profile: &Profile, installed: &[ContentEntry], files: &[Installed]) -> CmdResult<Vec<Value>> {
    let game = profile.game_version.as_str();
    let mut needs = Needs::default();
    let mut updates = Vec::new();
    for kind in ["mod", "resourcepack", "shader", "datapack"] {
        let group: Vec<&Installed> = files.iter().filter(|f| f.kind == kind && !f.sha512.is_empty()).collect();
        if group.is_empty() {
            continue;
        }
        let hashes: Vec<String> = group.iter().map(|f| f.sha512.clone()).collect::<HashSet<_>>().into_iter().collect();
        let mut latest = serde_json::Map::new();
        for chunk in hashes.chunks(300) {
            latest.extend(source.latest(chunk, kind, game).await?.as_object().cloned().unwrap_or_default());
        }
        for file in group {
            // Modrinth's answer is the newest build of any channel; when that's the installed one, nothing is newer.
            let Some(newest) = latest.get(&file.sha512) else { continue };
            if newest["id"].as_str() == Some(file.version_id.as_str()) {
                continue;
            }
            let Ok(list) = source.versions(&file.project_id, kind, game).await else { continue };
            let list = list.as_array().cloned().unwrap_or_default();
            let current = match list.iter().find(|v| v["id"].as_str() == Some(file.version_id.as_str())) {
                Some(v) => v.clone(),
                None => match source.version(&file.version_id).await {
                    Ok(v) => v,
                    Err(_) => continue,
                },
            };
            let Some((proposed, alternatives)) = update_options(&current, &list, kind, game) else { continue };
            let mut options = Vec::new();
            for v in std::iter::once(&proposed).chain(&alternatives) {
                let option = match needs.of(source, profile, installed, v, &file.title, kind).await {
                    Ok(adds) => version_option(v, &adds, None),
                    Err(reason) => version_option(v, &[], Some(reason)),
                };
                options.push(option);
            }
            let mut update = options.remove(0);
            for (key, value) in [
                ("kind", json!(kind)),
                ("fileName", json!(file.file_name)),
                ("world", json!(file.world)),
                ("title", json!(file.title)),
                ("iconUrl", json!(file.icon_url)),
                ("projectId", json!(file.project_id)),
                ("tracked", json!(file.tracked)),
                ("pinned", json!(file.pinned)),
                ("currentVersion", json!(file.version_number)),
                ("currentVersionId", json!(file.version_id)),
                ("currentVersionType", current["version_type"].clone()),
                ("alternatives", json!(options)),
            ] {
                update[key] = value;
            }
            updates.push(update);
        }
    }
    Ok(updates)
}

/// Updates for everything in a profile that Flint can check, and the files it can't.
#[tauri::command]
pub async fn content_updates(state: State<'_, AppState>, profile_id: String) -> CmdResult<Value> {
    let profile = find_profile(&state, &profile_id)?;
    identify(&state, &profile_id).await?;
    check_profile(state.inner(), state.inner(), &profile).await
}

async fn check_profile(state: &AppState, source: &impl UpdateSource, profile: &Profile) -> CmdResult<Value> {
    let (mut files, mut unmanaged) = survey(state, profile);
    // Records from before Flint kept hashes: the file on disk is hashed instead.
    for file in files.iter_mut().filter(|f| f.sha512.is_empty()) {
        let path = file.path.clone();
        file.sha512 = tokio::task::spawn_blocking(move || sha512_file(&path)).await.ok().flatten().unwrap_or_default();
        if file.sha512.is_empty() {
            unmanaged.push(json!({ "kind": file.kind, "fileName": file.file_name, "world": file.world, "title": file.title, "folder": false }));
        }
    }
    let manifest = load(state, &profile.id);
    let identified: HashMap<String, Identified> = read_json(&identified_path(state, &profile.id));
    let installed = present_entries(&manifest, &identified);
    let updates = find_updates(source, profile, &installed, &files).await?;
    Ok(json!({
        "updates": updates,
        "unmanaged": unmanaged,
        "checkedAt": now_secs(),
        "gameVersion": profile.game_version,
        "loader": profile.loader,
    }))
}

// ---------- pinning ----------

/// A record for a file Flint didn't install, from what Modrinth said about it (if anything), so it can be pinned,
/// updated and rolled back like Flint's own.
fn adopted(identified: &HashMap<String, Identified>, kind: &str, world: Option<&str>, file_name: &str, dir: &Path) -> ContentEntry {
    let on = dir.join(file_name);
    let real = if on.exists() { on } else { dir.join(format!("{file_name}{DISABLED}")) };
    let (size, modified) = file_info(&real);
    let key = format!("{kind}/{}/{file_name}", world.unwrap_or("-"));
    let cached = identified.get(&key).filter(|i| i.size == size && i.modified == modified);
    let info = cached.and_then(|i| i.info.clone()).unwrap_or(Value::Null);
    let text = |k: &str| info[k].as_str().unwrap_or_default().to_owned();
    ContentEntry {
        kind: kind.to_owned(),
        project_id: text("projectId"),
        version_id: text("versionId"),
        version_number: text("versionNumber"),
        title: info["title"].as_str().map(str::to_owned).unwrap_or_else(|| file_name.trim_end_matches(".jar").trim_end_matches(".zip").to_owned()),
        file_name: file_name.to_owned(),
        sha512: cached.map(|i| i.sha512.clone()).unwrap_or_default(),
        size,
        source: "manual".into(),
        icon_url: info["iconUrl"].as_str().map(str::to_owned),
        installed_at: if modified > 0 { modified } else { now_secs() },
        world: world.map(str::to_owned),
        ..Default::default()
    }
}

/// Pins or unpins items. A pinned item stays on its version: Update all leaves it unticked and the player updates
/// it only by choosing to. Files added by hand are recorded so the pin is kept, even ones Modrinth doesn't know.
#[tauri::command]
pub async fn content_pin(state: State<'_, AppState>, profile_id: String, items: Vec<ContentRef>, pinned: bool) -> CmdResult<()> {
    find_profile(&state, &profile_id)?;
    let _guard = state.file_lock.lock().await;
    pin(&state, &profile_id, &items, pinned)
}

fn pin(state: &AppState, profile_id: &str, items: &[ContentRef], pinned: bool) -> CmdResult<()> {
    let mut manifest = load(state, profile_id);
    let identified: HashMap<String, Identified> = read_json(&identified_path(state, profile_id));
    for item in items.iter().take(500) {
        let name = safe_name(&item.file_name)?.to_owned();
        if item.world.as_deref().is_some_and(|w| !plain_name(w)) {
            return Err("Unsafe file name".into());
        }
        if let Some(entry) = manifest.iter_mut().find(|m| m.kind == item.kind && m.file_name == name && m.world == item.world) {
            entry.pinned = pinned;
            continue;
        }
        if !pinned {
            continue;
        }
        let dir = kind_dir(state, profile_id, &item.kind, item.world.as_deref())?;
        if !dir.join(&name).is_file() && !dir.join(format!("{name}{DISABLED}")).is_file() {
            return Err(format!("{name} is no longer in this profile"));
        }
        manifest.push(ContentEntry { pinned: true, ..adopted(&identified, &item.kind, item.world.as_deref(), &name, &dir) });
    }
    // A record kept only to hold the pin of a file Modrinth doesn't know goes once the pin is lifted.
    manifest.retain(|m| m.pinned || !(m.source == "manual" && m.project_id.is_empty()));
    save(state, profile_id, &manifest)
}

// ---------- updating ----------

#[derive(Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct UpdateItem {
    kind: String,
    file_name: String,
    version_id: String,
    #[serde(default)]
    world: Option<String>,
}

/// A file the transaction puts into the profile: an update's new version, or a dependency it adds.
#[derive(Clone, Debug, Default)]
struct NewFile {
    kind: String,
    world: Option<String>,
    project_id: String,
    version_id: String,
    version_number: String,
    title: String,
    slug: String,
    icon_url: Option<String>,
    file_name: String,
    sha512: String,
    url: String,
    size: Option<u64>,
    requires: Vec<String>,
}

fn new_file(version: &Value, project: Option<&Value>, kind: &str, world: Option<&str>, title: &str) -> CmdResult<NewFile> {
    let file = primary_file(version).ok_or("This version has no downloadable file")?;
    let name = file["filename"].as_str().unwrap_or_default();
    if !plain_name(name) {
        return Err("This version's file name isn't safe to use".into());
    }
    if !file_fits(kind, name) {
        return Err(format!("{name} isn't a file type Flint installs for this content"));
    }
    let sha512 = file["hashes"]["sha512"].as_str().filter(|h| !h.is_empty()).ok_or("Modrinth published no SHA-512 hash for this file")?;
    let text = |v: &Value, key: &str| v[key].as_str().unwrap_or_default().to_owned();
    Ok(NewFile {
        kind: kind.to_owned(),
        world: world.map(str::to_owned),
        project_id: text(version, "project_id"),
        version_id: text(version, "id"),
        version_number: text(version, "version_number"),
        title: project.and_then(|p| p["title"].as_str()).unwrap_or(title).to_owned(),
        slug: project.map(|p| text(p, "slug")).unwrap_or_default(),
        icon_url: project.and_then(|p| p["icon_url"].as_str()).map(str::to_owned),
        file_name: name.to_owned(),
        sha512: sha512.to_owned(),
        url: file["url"].as_str().ok_or("No download address")?.to_owned(),
        size: file["size"].as_u64(),
        requires: version["dependencies"]
            .as_array()
            .map(|d| d.iter().filter(|x| x["dependency_type"] == "required").filter_map(|x| x["project_id"].as_str().map(str::to_owned)).collect())
            .unwrap_or_default(),
    })
}

/// One planned update: the profile's file now (its record, or one made for a hand-added file) and what replaces it.
struct Swap {
    item: UpdateItem,
    old: ContentEntry,
    version: Value,
    new: NewFile,
    /// Why it won't happen, once something rules it out.
    failed: Option<String>,
    staged: Option<PathBuf>,
}

/// A dependency the batch adds, and the updates (by index) that need it.
struct Added {
    file: NewFile,
    for_swaps: Vec<usize>,
    staged: Option<PathBuf>,
}

/// What an update transaction does outside the profile folder: download into staging and report progress.
trait Transfer {
    /// Downloads a file to `target`, verified against its SHA-512.
    async fn download(&self, file: &NewFile, target: &Path) -> CmdResult<u64>;
    fn stage(&self, stage: &str, done: usize, total: usize);
    fn cancelled(&self) -> bool;
    /// Moves a file within the profile; tests make it fail on purpose.
    fn place(&self, from: &Path, to: &Path) -> std::io::Result<()> {
        std::fs::rename(from, to)
    }
}

/// The app's side of a transaction: Modrinth through the cached client, downloads and progress through the job.
struct Live<'a> {
    app: &'a AppHandle,
    state: &'a AppState,
    job: &'a str,
}

impl Catalog for Live<'_> {
    async fn project(&self, id: &str) -> CmdResult<Value> {
        self.state.project(id).await
    }
    async fn version(&self, id: &str) -> CmdResult<Value> {
        self.state.version(id).await
    }
    async fn versions(&self, project_id: &str, kind: &str, game_version: &str) -> CmdResult<Value> {
        self.state.versions(project_id, kind, game_version).await
    }
}

impl Transfer for Live<'_> {
    async fn download(&self, file: &NewFile, target: &Path) -> CmdResult<u64> {
        let fetch = Fetch { url: &file.url, expected: Expected::Sha512(&file.sha512), max_bytes: jobs::listed_cap(file.size), allowed_hosts: MODRINTH_CDN, quiet: false };
        download_verified(self.app, self.state, self.job, fetch, target).await
    }
    fn stage(&self, stage: &str, done: usize, total: usize) {
        self.state.jobs.update(self.app, self.job, false, |j| {
            j.state = "running".into();
            j.stage = stage.into();
            j.items_done = done;
            j.items_total = total;
        });
    }
    fn cancelled(&self) -> bool {
        self.state.jobs.is_cancelled(self.job)
    }
}

/// A staging folder that is removed however the transaction ends.
struct Staging(PathBuf);

impl Drop for Staging {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// What happened to each item, for the job's result.
#[derive(Default)]
struct Report {
    updated: Vec<Value>,
    added: Vec<Value>,
    failed: Vec<Value>,
    skipped: Vec<Value>,
}

impl Report {
    fn note(list: &mut Vec<Value>, item: &UpdateItem, title: &str, reason: &str) {
        list.push(json!({ "kind": item.kind, "fileName": item.file_name, "world": item.world, "title": title, "reason": reason }));
    }

    fn into_json(self) -> Value {
        let installed: Vec<&Value> = self.updated.iter().chain(&self.added).map(|x| &x["title"]).collect();
        json!({ "updated": self.updated, "added": self.added, "failed": self.failed, "skipped": self.skipped, "installed": installed })
    }
}

/// Updates content as one job. Several projects at once share one download and one swap.
#[tauri::command]
pub async fn content_update(app: AppHandle, state: State<'_, AppState>, profile_id: String, items: Vec<UpdateItem>) -> CmdResult<String> {
    find_profile(&state, &profile_id)?;
    ensure_not_running(&state, &profile_id)?;
    let mut unique: Vec<UpdateItem> = Vec::new();
    for item in items.into_iter().take(300) {
        if !plain_name(&item.file_name) || item.world.as_deref().is_some_and(|w| !plain_name(w)) || !valid_id(&item.version_id) {
            return Err("Unsafe file name".into());
        }
        if !unique.iter().any(|u| u.kind == item.kind && u.file_name == item.file_name && u.world == item.world) {
            unique.push(item);
        }
    }
    let title = match unique.as_slice() {
        [] => return Err("Nothing to update.".into()),
        [one] => format!("Updating {}", item_title(&state, &profile_id, one)),
        many => format!("Updating {} projects", many.len()),
    };
    let (id, _) = state.jobs.create(&app, "update", Some(&profile_id), &title, unique.len());
    jobs::spawn(&app, id.clone(), move |app, job| async move {
        let state = app.state::<AppState>();
        let live = Live { app: &app, state: state.inner(), job: &job };
        apply(state.inner(), &live, &profile_id, unique).await
    });
    Ok(id)
}

/// The name an item shows: its record's title, what Modrinth called the file, or the file's own name.
fn item_title(state: &AppState, profile_id: &str, item: &UpdateItem) -> String {
    let manifest = load(state, profile_id);
    if let Some(entry) = manifest.iter().find(|m| m.kind == item.kind && m.file_name == item.file_name && m.world == item.world) {
        return entry.title.clone();
    }
    let identified: HashMap<String, Identified> = read_json(&identified_path(state, profile_id));
    let key = format!("{}/{}/{}", item.kind, item.world.as_deref().unwrap_or("-"), item.file_name);
    identified
        .get(&key)
        .and_then(|i| i.info.as_ref())
        .and_then(|info| info["title"].as_str())
        .map(str::to_owned)
        .unwrap_or_else(|| item.file_name.trim_end_matches(".jar").trim_end_matches(".zip").to_owned())
}

/// Works out one update: the file must still be there, Flint must know its project, and the version must be
/// that project's and built for this profile.
async fn plan_swap(state: &AppState, catalog: &impl Catalog, profile: &Profile, manifest: &[ContentEntry], identified: &HashMap<String, Identified>, item: &UpdateItem) -> CmdResult<Option<Swap>> {
    if (item.kind == "mod" || item.kind == "shader") && !takes_mods(profile) {
        return Err(no_mods_reason(profile).into());
    }
    let dir = kind_dir(state, &profile.id, &item.kind, item.world.as_deref())?;
    if !dir.join(&item.file_name).is_file() && !dir.join(format!("{}{DISABLED}", item.file_name)).is_file() {
        return Err("The file is no longer in this profile".into());
    }
    let old = match manifest.iter().find(|m| m.kind == item.kind && m.file_name == item.file_name && m.world == item.world) {
        Some(entry) => entry.clone(),
        None => adopted(identified, &item.kind, item.world.as_deref(), &item.file_name, &dir),
    };
    if old.project_id.is_empty() {
        return Err("Flint couldn't match this file on Modrinth, so it can't update it".into());
    }
    let version = catalog.version(&item.version_id).await?;
    if version["project_id"].as_str() != Some(old.project_id.as_str()) {
        return Err("That version belongs to a different project".into());
    }
    if item.version_id == old.version_id {
        return Ok(None);
    }
    if !fits(&version, &item.kind, &profile.game_version) {
        let number = version["version_number"].as_str().unwrap_or("This version");
        return Err(format!("{number} isn't made for {}", made_for(&item.kind, &profile.game_version)));
    }
    let project = catalog.project(&old.project_id).await.ok();
    let new = new_file(&version, project.as_ref(), &item.kind, item.world.as_deref(), &old.title)?;
    Ok(Some(Swap { item: item.clone(), old, version, new, failed: None, staged: None }))
}

/// The update transaction: plan, download into staging, then swap under the file lock while the game is closed.
/// Items that can't be updated are reported and left exactly as they were; the rest go ahead.
async fn apply(state: &AppState, io: &(impl Catalog + Transfer), profile_id: &str, items: Vec<UpdateItem>) -> CmdResult<Value> {
    let profile = find_profile(state, profile_id)?;
    ensure_not_running(state, profile_id)?;
    let mut report = Report::default();
    let total = items.len();

    // 1. Plan every update against this profile.
    let manifest = load(state, profile_id);
    let identified: HashMap<String, Identified> = read_json(&identified_path(state, profile_id));
    let mut swaps: Vec<Swap> = Vec::new();
    for (index, item) in items.iter().enumerate() {
        io.stage("Checking compatibility", index, total);
        match plan_swap(state, io, &profile, &manifest, &identified, item).await {
            Ok(Some(swap)) => swaps.push(swap),
            Ok(None) => Report::note(&mut report.skipped, item, &item_title(state, profile_id, item), "Already on this version"),
            Err(reason) => Report::note(&mut report.failed, item, &item_title(state, profile_id, item), &reason),
        }
    }

    // 2. What the new versions need that the profile doesn't have. An update whose needs can't be met is left out.
    let installed = present_entries(&manifest, &identified);
    let mut needs = Needs::default();
    let mut adds: Vec<Added> = Vec::new();
    for (index, swap) in swaps.iter_mut().enumerate() {
        let wanted = match needs.of(io, &profile, &installed, &swap.version, &swap.new.title, &swap.item.kind).await {
            Ok(items) => items,
            Err(reason) => {
                swap.failed = Some(reason);
                continue;
            }
        };
        for item in wanted {
            let project_id = item["projectId"].as_str().unwrap_or_default().to_owned();
            if let Some(added) = adds.iter_mut().find(|a| a.file.project_id == project_id) {
                added.for_swaps.push(index);
            } else {
                match dependency_file(io, &item, swap.item.world.as_deref()).await {
                    Ok(file) => adds.push(Added { file, for_swaps: vec![index], staged: None }),
                    Err(e) => {
                        let title = item["title"].as_str().unwrap_or("a dependency");
                        swap.failed = Some(format!("Couldn't prepare {title}, which it needs: {e}"));
                        break;
                    }
                }
            }
        }
    }

    // 3. Download every new file into staging, each checked against its published hash.
    let staging = Staging(state.paths.profile_dir(profile_id).join(format!(".flint-staging-update-{}", random_hex(4))));
    std::fs::create_dir_all(&staging.0).map_err(err("Could not prepare the download"))?;
    let wanted = |swaps: &[Swap], added: &Added| added.for_swaps.iter().any(|&i| swaps[i].failed.is_none());
    let count = swaps.iter().filter(|s| s.failed.is_none()).count() + adds.iter().filter(|a| wanted(&swaps, a)).count();
    let mut done = 0;
    for (index, swap) in swaps.iter_mut().enumerate().filter(|(_, s)| s.failed.is_none()) {
        io.stage("Downloading", done, count);
        let target = staging.0.join(format!("u{index}")).join(&swap.new.file_name);
        match io.download(&swap.new, &target).await {
            Ok(bytes) => {
                swap.new.size = Some(bytes);
                swap.staged = Some(target);
            }
            Err(e) if io.cancelled() => return Err(e),
            Err(e) => swap.failed = Some(e),
        }
        done += 1;
    }
    for (index, add) in adds.iter_mut().enumerate() {
        if !wanted(&swaps, add) {
            continue;
        }
        io.stage("Downloading", done, count);
        let target = staging.0.join(format!("d{index}")).join(&add.file.file_name);
        match io.download(&add.file, &target).await {
            Ok(bytes) => {
                add.file.size = Some(bytes);
                add.staged = Some(target);
            }
            Err(e) if io.cancelled() => return Err(e),
            Err(e) => {
                for &i in &add.for_swaps {
                    swaps[i].failed.get_or_insert_with(|| format!("Couldn't download {}, which it needs: {e}", add.file.title));
                }
            }
        }
        done += 1;
    }

    // 4. Swap, under the file lock, and only while the game is still closed.
    io.stage("Installing", total, total);
    let _guard = state.file_lock.lock().await;
    ensure_not_running(state, profile_id)?;
    commit(state, io, profile_id, &mut swaps, &adds, &mut report);
    Ok(report.into_json())
}

/// A dependency's file, from the resolver's plan item.
async fn dependency_file(catalog: &impl Catalog, item: &Value, world: Option<&str>) -> CmdResult<NewFile> {
    let version = catalog.version(item["versionId"].as_str().unwrap_or_default()).await?;
    let project = catalog.project(item["projectId"].as_str().unwrap_or_default()).await.ok();
    let kind = item["kind"].as_str().unwrap_or("mod");
    new_file(&version, project.as_ref(), kind, if kind == "datapack" { world } else { None }, item["title"].as_str().unwrap_or_default())
}

/// Something `commit` put into the profile, so it can be taken back out if Flint's records can't be saved.
enum Placed {
    /// An update: the new file at `target`, the old one kept at `backup` (from `original`).
    Swap { target: PathBuf, backup: PathBuf, original: PathBuf, aside: Option<PathBuf> },
    Added { path: PathBuf, aside: Option<PathBuf> },
}

/// Takes a file `commit` put in back out, and returns the hand-added file it moved aside for it (if any) to its place.
fn take_out(path: &Path, aside: Option<&Path>) {
    let _ = std::fs::remove_file(path);
    if let Some(aside) = aside.filter(|_| !path.exists()) {
        let _ = std::fs::rename(aside, path);
    }
}

/// Puts one update in place: the current file goes to the backup folder (as the item's earlier version) and the new
/// one takes its place, switched off if the current one was. When the new file can't go in, the current one is
/// moved back, so the item keeps the version it had. `kept` holds the backups this commit already made: the record of
/// them is only saved at the end, so two updates in one go must not keep their earlier versions under one name.
fn swap_in(io: &impl Transfer, dir: &Path, backups: &Path, manifest: &[ContentEntry], kept: &mut HashSet<PathBuf>, swap: &Swap, staged: &Path) -> Result<Placed, String> {
    let (old, new) = (&swap.old, &swap.new);
    let on = dir.join(&old.file_name);
    let off = dir.join(format!("{}{DISABLED}", old.file_name));
    let (original, disabled) = if on.is_file() {
        (on, false)
    } else if off.is_file() {
        (off, true)
    } else {
        return Err("The file is no longer in this profile".into());
    };
    let same = |m: &ContentEntry| m.kind == old.kind && m.world == old.world && m.file_name == old.file_name;
    std::fs::create_dir_all(backups).map_err(err("Couldn't keep the current file"))?;
    let backup = backups.join(&old.file_name);
    if kept.contains(&backup) {
        return Err("Another update in this batch keeps its earlier version under the same file name. Update this one on its own.".into());
    }
    if backup.exists() {
        // An earlier version kept under this name: this item's own (which this update replaces) may go, another item's may not.
        if manifest.iter().any(|m| !same(m) && m.kind == old.kind && m.previous.as_ref().is_some_and(|p| p.file_name == old.file_name)) {
            return Err("Flint keeps an earlier version of another item under this file name. Roll that one back or remove it first.".into());
        }
        std::fs::remove_file(&backup).map_err(err("Couldn't keep the current file"))?;
    }
    io.place(&original, &backup).map_err(err("Couldn't move the current file aside"))?;
    let restore = |reason: String| match io.place(&backup, &original) {
        Ok(()) => reason,
        Err(e) => format!("{reason}. The current file couldn't be put back ({e}); it's kept in the profile's .flint-backup folder."),
    };
    let target = dir.join(if disabled { format!("{}{DISABLED}", new.file_name) } else { new.file_name.clone() });
    let mut aside = None;
    if target.exists() {
        // A file Flint tracks for another item, or a pack added by hand, is never overwritten. A hand-added jar is kept aside.
        let other = manifest.iter().any(|m| !same(m) && m.kind == new.kind && m.world == new.world && m.file_name == new.file_name);
        if other || new.kind != "mod" {
            return Err(restore(format!("A file named {} is already in this profile", new.file_name)));
        }
        match set_aside(&target) {
            Ok(path) => aside = Some(path),
            Err(e) => return Err(restore(e)),
        }
    }
    if let Err(e) = io.place(staged, &target) {
        take_out(&target, aside.as_deref());
        return Err(restore(format!("Couldn't put the new file in place: {e}")));
    }
    kept.insert(backup.clone());
    Ok(Placed::Swap { target, backup, original, aside })
}

/// Puts an added dependency in place. Ok(None): the profile already has it under that name.
fn add_in(io: &impl Transfer, dir: &Path, manifest: &[ContentEntry], file: &NewFile, staged: &Path) -> Result<Option<Placed>, String> {
    if manifest.iter().any(|m| m.kind == file.kind && m.world == file.world && m.project_id == file.project_id) {
        return Ok(None);
    }
    std::fs::create_dir_all(dir).map_err(err("Could not create the content folder"))?;
    let target = dir.join(&file.file_name);
    let mut aside = None;
    if target.exists() {
        if manifest.iter().any(|m| m.kind == file.kind && m.world == file.world && m.file_name == file.file_name) || file.kind != "mod" {
            return Err(format!("A file named {} is already in this profile", file.file_name));
        }
        aside = Some(set_aside(&target)?);
    }
    if let Err(e) = io.place(staged, &target) {
        take_out(&target, aside.as_deref());
        return Err(format!("Couldn't put the file in place: {e}"));
    }
    Ok(Some(Placed::Added { path: target, aside }))
}

/// Swaps every update that's still going ahead and records the result. Dependencies go in first, so an update
/// only goes in with everything it needs; one that's left with no update needing it is taken out again.
fn commit(state: &AppState, io: &impl Transfer, profile_id: &str, swaps: &mut [Swap], adds: &[Added], report: &mut Report) {
    let mut manifest = load(state, profile_id);
    let mut placed: Vec<Placed> = Vec::new();
    let mut added: Vec<(usize, Option<Placed>)> = Vec::new();
    let mut backups_kept: HashSet<PathBuf> = HashSet::new();
    for (index, add) in adds.iter().enumerate() {
        let Some(staged) = add.staged.as_deref().filter(|_| add.for_swaps.iter().any(|&i| swaps[i].failed.is_none())) else { continue };
        let placement = kind_dir(state, profile_id, &add.file.kind, add.file.world.as_deref()).and_then(|dir| add_in(io, &dir, &manifest, &add.file, staged));
        match placement {
            Ok(path) => added.push((index, path)),
            Err(e) => {
                for &i in &add.for_swaps {
                    swaps[i].failed.get_or_insert_with(|| format!("Couldn't add {}, which it needs: {e}", add.file.title));
                }
            }
        }
    }
    let mut updated: Vec<usize> = Vec::new();
    for (index, swap) in swaps.iter_mut().enumerate() {
        let Some(staged) = swap.staged.clone().filter(|_| swap.failed.is_none()) else { continue };
        let backups = backup_dir(state, profile_id, &swap.item.kind);
        match kind_dir(state, profile_id, &swap.item.kind, swap.item.world.as_deref()).and_then(|dir| swap_in(io, &dir, &backups, &manifest, &mut backups_kept, swap, &staged)) {
            Ok(done) => {
                placed.push(done);
                updated.push(index);
            }
            Err(e) => swap.failed = Some(e),
        }
    }
    // Dependencies no update that went in needs are taken back out.
    let mut kept: Vec<usize> = Vec::new();
    for (index, done) in added {
        let Some(done) = done else { continue };
        if adds[index].for_swaps.iter().any(|i| updated.contains(i)) {
            placed.push(done);
            kept.push(index);
        } else if let Placed::Added { path, aside } = done {
            take_out(&path, aside.as_deref());
        }
    }

    let mut stale: Vec<(String, String)> = Vec::new();
    for &index in &updated {
        let (old, new) = (&swaps[index].old, &swaps[index].new);
        if let Some(earlier) = old.previous.as_ref().filter(|p| p.file_name != old.file_name) {
            stale.push((old.kind.clone(), earlier.file_name.clone()));
        }
        let entry = ContentEntry {
            kind: old.kind.clone(),
            project_id: new.project_id.clone(),
            version_id: new.version_id.clone(),
            slug: if new.slug.is_empty() { old.slug.clone() } else { new.slug.clone() },
            title: new.title.clone(),
            author: old.author.clone(),
            version_number: new.version_number.clone(),
            file_name: new.file_name.clone(),
            sha512: new.sha512.clone(),
            size: new.size.unwrap_or(0),
            source: if old.source.is_empty() { "user".into() } else { old.source.clone() },
            reason: old.reason.clone(),
            icon_url: new.icon_url.clone().or_else(|| old.icon_url.clone()),
            installed_at: if old.installed_at > 0 { old.installed_at } else { now_secs() },
            world: old.world.clone(),
            requires: new.requires.clone(),
            previous: Some(Box::new(ContentEntry { previous: None, pinned: false, ..old.clone() })),
            pinned: old.pinned,
        };
        match manifest.iter().position(|m| m.kind == old.kind && m.world == old.world && m.file_name == old.file_name) {
            Some(at) => manifest[at] = entry,
            None => manifest.push(entry),
        }
    }
    for &index in &kept {
        let file = &adds[index].file;
        manifest.push(ContentEntry {
            kind: file.kind.clone(),
            project_id: file.project_id.clone(),
            version_id: file.version_id.clone(),
            slug: file.slug.clone(),
            title: file.title.clone(),
            version_number: file.version_number.clone(),
            file_name: file.file_name.clone(),
            sha512: file.sha512.clone(),
            size: file.size.unwrap_or(0),
            source: "dependency".into(),
            icon_url: file.icon_url.clone(),
            installed_at: now_secs(),
            world: file.world.clone(),
            requires: file.requires.clone(),
            ..Default::default()
        });
    }
    let present: HashSet<String> = manifest.iter().map(|m| m.project_id.clone()).collect();
    for entry in manifest.iter_mut() {
        entry.requires.retain(|r| present.contains(r));
    }

    if let Err(e) = save(state, profile_id, &manifest) {
        // Without the record, nothing that was swapped could be rolled back: every swap is undone instead.
        for done in placed.into_iter().rev() {
            match done {
                Placed::Swap { target, backup, original, aside } => {
                    take_out(&target, aside.as_deref());
                    let _ = io.place(&backup, &original);
                }
                Placed::Added { path, aside } => take_out(&path, aside.as_deref()),
            }
        }
        for &index in &updated {
            swaps[index].failed = Some(format!("Couldn't save Flint's records, so nothing was changed: {e}"));
        }
        updated.clear();
        kept.clear();
    } else {
        // The versions this update replaced as "earlier version" aren't anyone's backup any more.
        for (kind, name) in stale {
            if !manifest.iter().any(|m| m.kind == kind && m.previous.as_ref().is_some_and(|p| p.file_name == name)) {
                let _ = std::fs::remove_file(backup_dir(state, profile_id, &kind).join(name));
            }
        }
    }

    for swap in swaps.iter() {
        if let Some(reason) = &swap.failed {
            Report::note(&mut report.failed, &swap.item, &swap.old.title, reason);
        }
    }
    for &index in &updated {
        let (item, old, new) = (&swaps[index].item, &swaps[index].old, &swaps[index].new);
        report.updated.push(json!({
            "kind": item.kind,
            "fileName": item.file_name,
            "world": item.world,
            "newFileName": new.file_name,
            "title": new.title,
            "projectId": new.project_id,
            "from": old.version_number,
            "to": new.version_number,
        }));
    }
    for &index in &kept {
        let file = &adds[index].file;
        report.added.push(json!({ "title": file.title, "versionNumber": file.version_number, "projectId": file.project_id, "kind": file.kind }));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const GAME: &str = "26.2";
    const PROFILE: &str = "aaaaaaaaaaaa";

    fn sha(bytes: &[u8]) -> String {
        hex::encode(Sha512::digest(bytes))
    }

    /// A Modrinth version whose one file holds its own id as its contents.
    #[allow(clippy::too_many_arguments)]
    fn version(project: &str, id: &str, number: &str, loader: &str, game: &str, channel: &str, date: &str, deps: Value) -> Value {
        let ext = if loader == "fabric" { "jar" } else { "zip" };
        json!({
            "id": id,
            "project_id": project,
            "version_number": number,
            "version_type": channel,
            "date_published": format!("2026-{date}T10:00:00.000000Z"),
            "game_versions": [game],
            "loaders": [loader],
            "changelog": format!("## {number}\n- Fixes"),
            "dependencies": deps,
            "files": [{ "filename": format!("{project}-{number}.{ext}"), "url": format!("https://cdn.modrinth.com/data/{project}/{id}"), "hashes": { "sha512": sha(id.as_bytes()) }, "size": id.len(), "primary": true }],
        })
    }

    fn mod_project(id: &str, title: &str) -> Value {
        json!({ "id": id, "slug": title.to_lowercase().replace(' ', "-"), "title": title, "project_type": "mod", "loaders": ["fabric"], "client_side": "required", "icon_url": null })
    }

    fn requires(project: &str) -> Value {
        json!([{ "project_id": project, "version_id": null, "dependency_type": "required" }])
    }

    /// Modrinth, its CDN and the disk, answering from fixed data.
    #[derive(Default)]
    struct Fake<'a> {
        projects: Vec<Value>,
        versions: Vec<Value>,
        /// Versions whose download fails.
        broken: Vec<&'static str>,
        /// A file name that can't be moved into place.
        stuck: Option<String>,
        /// The game starts in this profile while files download.
        starts_game: Option<&'a AppState>,
    }

    impl Catalog for Fake<'_> {
        async fn project(&self, id: &str) -> CmdResult<Value> {
            self.projects.iter().find(|p| p["id"] == id).cloned().ok_or_else(|| "Couldn't load a project from Modrinth: not found on Modrinth".into())
        }
        async fn version(&self, id: &str) -> CmdResult<Value> {
            self.versions.iter().find(|v| v["id"] == id).cloned().ok_or_else(|| "Couldn't load a version from Modrinth: not found on Modrinth".into())
        }
        async fn versions(&self, project_id: &str, kind: &str, game_version: &str) -> CmdResult<Value> {
            let mut list: Vec<Value> = self.versions.iter().filter(|v| v["project_id"] == project_id && fits(v, kind, game_version)).cloned().collect();
            list.sort_by(|a, b| b["date_published"].as_str().cmp(&a["date_published"].as_str()));
            Ok(json!(list))
        }
    }

    impl UpdateSource for Fake<'_> {
        async fn latest(&self, hashes: &[String], kind: &str, game_version: &str) -> CmdResult<Value> {
            let mut out = serde_json::Map::new();
            for hash in hashes {
                let Some(installed) = self.versions.iter().find(|v| v["files"][0]["hashes"]["sha512"] == hash.as_str()) else { continue };
                let list = self.versions(installed["project_id"].as_str().unwrap(), kind, game_version).await?;
                if let Some(newest) = list.as_array().and_then(|l| l.first()) {
                    out.insert(hash.clone(), newest.clone());
                }
            }
            Ok(Value::Object(out))
        }
    }

    impl Transfer for Fake<'_> {
        async fn download(&self, file: &NewFile, target: &Path) -> CmdResult<u64> {
            if let Some(state) = self.starts_game {
                state.running.lock().unwrap().insert(PROFILE.into(), RunningGame { kill: None });
            }
            if self.broken.contains(&file.version_id.as_str()) {
                return Err("Download failed: could not connect (check your internet connection)".into());
            }
            assert_eq!(sha(file.version_id.as_bytes()), file.sha512, "the published hash is what's checked");
            std::fs::create_dir_all(target.parent().unwrap()).unwrap();
            std::fs::write(target, file.version_id.as_bytes()).unwrap();
            Ok(file.version_id.len() as u64)
        }
        fn stage(&self, _: &str, _: usize, _: usize) {}
        fn cancelled(&self) -> bool {
            false
        }
        fn place(&self, from: &Path, to: &Path) -> std::io::Result<()> {
            if self.stuck.as_deref().is_some_and(|name| to.file_name().is_some_and(|n| n == name)) {
                return Err(std::io::Error::other("the disk refused"));
            }
            std::fs::rename(from, to)
        }
    }

    /// Sodium (two newer releases, a newer beta, one for another Minecraft), Lithium, Zoomify (whose update needs
    /// Fabric API), Cloth (whose update needs a library with no build for this Minecraft) and Fabric API.
    fn catalog<'a>() -> Fake<'a> {
        Fake {
            projects: vec![
                mod_project("SODIUM01", "Sodium"),
                mod_project("LITHIUM1", "Lithium"),
                mod_project("ZOOMIFY1", "Zoomify"),
                mod_project("CLOTH001", "Cloth"),
                mod_project("FABRICAP", "Fabric API"),
                mod_project("OLDLIB01", "Old Library"),
            ],
            versions: vec![
                version("SODIUM01", "S1", "0.6.0", "fabric", GAME, "release", "01-01", json!([])),
                version("SODIUM01", "S2", "0.6.1", "fabric", GAME, "release", "02-01", json!([])),
                version("SODIUM01", "S3", "0.6.2", "fabric", GAME, "release", "03-01", json!([])),
                version("SODIUM01", "S4", "0.7.0-beta.1", "fabric", GAME, "beta", "04-01", json!([])),
                version("SODIUM01", "S5", "0.7.0", "fabric", "26.3", "release", "05-01", json!([])),
                version("LITHIUM1", "L1", "0.1", "fabric", GAME, "release", "01-01", json!([])),
                version("LITHIUM1", "L2", "0.2", "fabric", GAME, "release", "02-01", json!([])),
                version("ZOOMIFY1", "Z1", "2.0", "fabric", GAME, "release", "01-01", json!([])),
                version("ZOOMIFY1", "Z2", "2.1", "fabric", GAME, "release", "02-01", requires("FABRICAP")),
                version("CLOTH001", "C1", "1.0", "fabric", GAME, "release", "01-01", json!([])),
                version("CLOTH001", "C2", "1.1", "fabric", GAME, "release", "02-01", requires("OLDLIB01")),
                version("FABRICAP", "F1", "0.140.0", "fabric", GAME, "release", "01-15", json!([])),
                version("OLDLIB01", "O1", "1.0", "fabric", "26.1", "release", "01-01", json!([])),
            ],
            ..Default::default()
        }
    }

    fn file_of(fake: &Fake, id: &str) -> String {
        fake.versions.iter().find(|v| v["id"] == id).unwrap()["files"][0]["filename"].as_str().unwrap().to_owned()
    }

    /// A Fabric profile for 26.2 holding Sodium S1 and Cloth C1 (Flint's), Lithium L1 (Flint's, switched off and
    /// pinned), Zoomify Z1 (added by hand, known by its hash) and a jar Modrinth doesn't know.
    fn profile_with_mods(dir: &TestDir, fake: &Fake) -> (AppState, Profile) {
        let state = AppState::for_tests(dir.0.clone());
        let profile = Profile { id: PROFILE.into(), name: "Test".into(), game_version: GAME.into(), loader: "fabric".into(), ..Default::default() };
        crate::data::save_profiles(&state, &crate::data::ProfilesFile { selected: None, profiles: vec![profile.clone()] }).unwrap();
        let mods = state.paths.profile_dir(PROFILE).join("mods");
        std::fs::create_dir_all(&mods).unwrap();
        let record = |id: &str, title: &str, project: &str| ContentEntry {
            kind: "mod".into(),
            project_id: project.into(),
            version_id: id.into(),
            title: title.into(),
            version_number: fake.versions.iter().find(|v| v["id"] == id).unwrap()["version_number"].as_str().unwrap().into(),
            file_name: file_of(fake, id),
            sha512: sha(id.as_bytes()),
            source: "user".into(),
            installed_at: 100,
            ..Default::default()
        };
        let lithium = ContentEntry { pinned: true, ..record("L1", "Lithium", "LITHIUM1") };
        let manifest = vec![record("S1", "Sodium", "SODIUM01"), lithium, record("C1", "Cloth", "CLOTH001")];
        for entry in &manifest {
            let name = if entry.project_id == "LITHIUM1" { format!("{}{DISABLED}", entry.file_name) } else { entry.file_name.clone() };
            std::fs::write(mods.join(name), entry.version_id.as_bytes()).unwrap();
        }
        save(&state, PROFILE, &manifest).unwrap();
        std::fs::write(mods.join(file_of(fake, "Z1")), b"Z1").unwrap();
        std::fs::write(mods.join("mystery.jar"), b"who knows").unwrap();
        let identified = |name: &str, sha512: String, info: Option<Value>| {
            let (size, modified) = file_info(&mods.join(name));
            (format!("mod/-/{name}"), Identified { size, modified, sha512, info })
        };
        let cache: HashMap<String, Identified> = [
            identified(&file_of(fake, "Z1"), sha(b"Z1"), Some(json!({ "projectId": "ZOOMIFY1", "versionId": "Z1", "versionNumber": "2.0", "title": "Zoomify", "iconUrl": null }))),
            identified("mystery.jar", sha(b"who knows"), None),
        ]
        .into();
        write_json(&identified_path(&state, PROFILE), &cache).unwrap();
        (state, profile)
    }

    fn item(fake: &Fake, current: &str, to: &str) -> UpdateItem {
        UpdateItem { kind: "mod".into(), file_name: file_of(fake, current), version_id: to.into(), world: None }
    }

    fn no_staging_left(state: &AppState) -> bool {
        std::fs::read_dir(state.paths.profile_dir(PROFILE)).unwrap().flatten().all(|e| !e.file_name().to_string_lossy().starts_with(".flint-staging"))
    }

    #[test]
    fn updates_are_newer_builds_for_this_profile_at_least_as_stable() {
        let fake = catalog();
        let list: Vec<Value> = fake.versions.iter().filter(|v| v["project_id"] == "SODIUM01").cloned().collect();
        let forge = version("SODIUM01", "SF", "0.8.0-forge", "forge", GAME, "release", "06-01", json!([]));
        let mut unsafe_name = version("SODIUM01", "SX", "0.9.0", "fabric", GAME, "release", "07-01", json!([]));
        unsafe_name["files"][0]["filename"] = json!("../sodium.jar");
        let mut unhashed = version("SODIUM01", "SH", "0.9.1", "fabric", GAME, "release", "08-01", json!([]));
        unhashed["files"][0]["hashes"] = json!({});
        let mut all = list.clone();
        all.extend([forge, unsafe_name, unhashed]);
        let ids = |vs: &[Value]| vs.iter().map(|v| v["id"].as_str().unwrap().to_owned()).collect::<Vec<_>>();

        // A release install: the newest release for 26.2 (not the 26.3 one, the Forge one or files Flint can't
        // install); the beta and the older release are there to pick instead, newest first.
        let (proposed, alternatives) = update_options(&list[0], &all, "mod", GAME).unwrap();
        assert_eq!(proposed["id"], "S3");
        assert_eq!(ids(&alternatives), ["S4", "S2"]);
        // A beta install may move to the newest beta or release.
        let mut beta = list[1].clone();
        beta["version_type"] = json!("beta");
        assert_eq!(update_options(&beta, &all, "mod", GAME).unwrap().0["id"], "S4");
        // The newest release is installed: only a less stable beta is newer, so nothing is offered.
        assert!(update_options(&list[2], &all, "mod", GAME).is_none());
        // Nothing newer at all.
        assert!(update_options(&list[3], &all, "mod", GAME).is_none());
    }

    #[test]
    fn alternatives_are_limited_and_newest_first() {
        let mut list = vec![version("P", "V0", "1.0", "fabric", GAME, "release", "01-01", json!([]))];
        for n in 1..=9 {
            list.push(version("P", &format!("V{n}"), &format!("1.{n}"), "fabric", GAME, if n % 2 == 0 { "alpha" } else { "release" }, &format!("{:02}-01", n + 1), json!([])));
        }
        let installed = list[0].clone();
        list.reverse();
        let (proposed, alternatives) = update_options(&installed, &list, "mod", GAME).unwrap();
        assert_eq!(proposed["id"], "V9");
        let dates: Vec<&str> = alternatives.iter().map(|v| v["date_published"].as_str().unwrap()).collect();
        assert_eq!(alternatives.len(), ALTERNATIVES);
        assert!(dates.windows(2).all(|w| w[0] > w[1]), "{dates:?}");
        assert_eq!(alternatives[0]["version_type"], "alpha", "less stable builds can be picked by hand");
    }

    #[test]
    fn long_changelogs_are_cut_at_a_line() {
        let v = json!({ "changelog": format!("{}\n{}", "a".repeat(1000), "b".repeat(1000)) });
        let text = changelog(&v);
        assert!(text.ends_with('…') && !text.contains('b'), "{}", text.len());
        assert_eq!(changelog(&json!({ "changelog": "  - Fixes\n" })), "- Fixes");
    }

    #[test]
    fn dependencies_already_present_are_not_added_and_clashes_are_named() {
        let present: HashMap<String, String> = [("FABRICAP".to_owned(), "Fabric API".to_owned()), ("OPTIFAB1".to_owned(), "OptiFabric".to_owned())].into();
        let v = json!({ "dependencies": [
            { "project_id": "FABRICAP", "dependency_type": "required" },
            { "project_id": "CLOTH001", "dependency_type": "required" },
            { "project_id": null, "version_id": "PINNED1", "dependency_type": "required" },
            { "project_id": "MODMENU1", "dependency_type": "optional" },
            { "project_id": "OPTIFAB1", "dependency_type": "incompatible" },
            { "project_id": "NOTHERE1", "dependency_type": "incompatible" },
        ]});
        let (missing, clashes) = dependency_needs(&v, &present);
        assert_eq!(missing, [Dependency { project: Some("CLOTH001".into()), version: None }, Dependency { project: None, version: Some("PINNED1".into()) }]);
        assert_eq!(clashes, ["OptiFabric"]);
    }

    #[tokio::test]
    async fn the_check_offers_compatible_updates_with_what_they_add_and_lists_what_it_cant_check() {
        let dir = TestDir::new();
        let fake = catalog();
        let (state, profile) = profile_with_mods(&dir, &fake);
        std::fs::create_dir_all(state.paths.profile_dir(PROFILE).join("resourcepacks").join("My Pack")).unwrap();
        let result = check_profile(&state, &fake, &profile).await.unwrap();
        let updates = result["updates"].as_array().unwrap();
        let find = |project: &str| updates.iter().find(|u| u["projectId"] == project).unwrap_or_else(|| panic!("{project}: {updates:?}"));

        let sodium = find("SODIUM01");
        assert_eq!((sodium["currentVersion"].as_str(), sodium["versionNumber"].as_str()), (Some("0.6.0"), Some("0.6.2")));
        let alternatives: Vec<&str> = sodium["alternatives"].as_array().unwrap().iter().map(|a| a["versionId"].as_str().unwrap()).collect();
        assert_eq!(alternatives, ["S4", "S2"], "the 26.3 build is never offered");
        assert_eq!(sodium["alternatives"][0]["versionType"], "beta");
        assert!(sodium["changelog"].as_str().unwrap().contains("0.6.2"));
        assert_eq!(sodium["pinned"], false);

        assert_eq!(find("LITHIUM1")["pinned"], true, "a pinned item is listed, marked as pinned");

        let zoomify = find("ZOOMIFY1");
        assert_eq!(zoomify["tracked"], false, "a hand-added file Modrinth knows is checked too");
        assert_eq!(zoomify["adds"].as_array().unwrap().iter().map(|a| a["title"].as_str().unwrap()).collect::<Vec<_>>(), ["Fabric API"]);
        assert!(zoomify["blocked"].is_null());

        let cloth = find("CLOTH001");
        assert!(cloth["blocked"].as_str().unwrap().starts_with("Needs Old Library"), "{}", cloth["blocked"]);
        assert!(cloth["adds"].as_array().unwrap().is_empty());

        let unmanaged: Vec<&str> = result["unmanaged"].as_array().unwrap().iter().map(|u| u["fileName"].as_str().unwrap()).collect();
        assert_eq!(unmanaged, ["mystery.jar", "My Pack"], "files nobody can match are listed, never guessed");
        assert_eq!(updates.len(), 4);
    }

    #[tokio::test]
    async fn an_update_swaps_files_keeps_the_old_ones_for_rollback_and_adds_what_it_needs() {
        let dir = TestDir::new();
        let fake = catalog();
        let (state, _) = profile_with_mods(&dir, &fake);
        let mods = state.paths.profile_dir(PROFILE).join("mods");
        let backups = backup_dir(&state, PROFILE, "mod");

        let items = vec![item(&fake, "S1", "S3"), item(&fake, "L1", "L2"), item(&fake, "Z1", "Z2")];
        let result = apply(&state, &fake, PROFILE, items).await.unwrap();
        assert_eq!(result["updated"].as_array().unwrap().len(), 3, "{result}");
        assert!(result["failed"].as_array().unwrap().is_empty(), "{result}");
        assert_eq!(result["added"][0]["title"], "Fabric API");

        // New files in place, old ones kept as the earlier version.
        assert_eq!(std::fs::read(mods.join(file_of(&fake, "S3"))).unwrap(), b"S3");
        assert!(!mods.join(file_of(&fake, "S1")).exists());
        assert_eq!(std::fs::read(backups.join(file_of(&fake, "S1"))).unwrap(), b"S1");
        // A switched-off mod stays switched off.
        assert!(mods.join(format!("{}{DISABLED}", file_of(&fake, "L2"))).is_file());
        assert!(!mods.join(file_of(&fake, "L2")).exists());
        assert_eq!(std::fs::read(backups.join(file_of(&fake, "L1"))).unwrap(), b"L1");
        // The dependency went in with it.
        assert!(mods.join(file_of(&fake, "F1")).is_file());
        // The mod Flint couldn't match is untouched.
        assert_eq!(std::fs::read(mods.join("mystery.jar")).unwrap(), b"who knows");

        let manifest = load(&state, PROFILE);
        let entry = |project: &str| manifest.iter().find(|m| m.project_id == project).unwrap().clone();
        let sodium = entry("SODIUM01");
        assert_eq!((sodium.version_id.as_str(), sodium.previous.as_ref().unwrap().version_id.as_str()), ("S3", "S1"));
        assert_eq!(sodium.installed_at, 100, "an update isn't a new addition");
        assert!(entry("LITHIUM1").pinned, "a pin stays with the item");
        let zoomify = entry("ZOOMIFY1");
        assert_eq!((zoomify.source.as_str(), zoomify.previous.as_ref().unwrap().version_id.as_str()), ("manual", "Z1"));
        assert_eq!(zoomify.requires, ["FABRICAP"]);
        assert_eq!(entry("FABRICAP").source, "dependency");

        // The profile's list offers the rollback, with the switched-off state as it was.
        let listed = list_content(&state, PROFILE).unwrap();
        let row = |project: &str| listed["mods"].as_array().unwrap().iter().find(|m| m["projectId"] == project).unwrap().clone();
        assert_eq!((row("SODIUM01")["canRollback"].as_bool(), row("SODIUM01")["previousVersion"].as_str()), (Some(true), Some("0.6.0")));
        assert_eq!(row("LITHIUM1")["enabled"], false);
        assert!(no_staging_left(&state), "staging is cleared");
    }

    #[tokio::test]
    async fn a_failed_item_keeps_its_old_file_while_the_rest_update() {
        let dir = TestDir::new();
        let mut fake = catalog();
        let (state, _) = profile_with_mods(&dir, &fake);
        let mods = state.paths.profile_dir(PROFILE).join("mods");
        // Lithium's download fails; Sodium's new file can't be moved in, after its old one was moved aside.
        fake.broken = vec!["L2"];
        fake.stuck = Some(file_of(&fake, "S3"));

        let items = vec![item(&fake, "S1", "S3"), item(&fake, "L1", "L2"), item(&fake, "Z1", "Z2")];
        let result = apply(&state, &fake, PROFILE, items).await.unwrap();
        let failed: Vec<(&str, &str)> = result["failed"].as_array().unwrap().iter().map(|f| (f["title"].as_str().unwrap(), f["reason"].as_str().unwrap())).collect();
        assert_eq!(failed.len(), 2, "{failed:?}");
        assert!(failed.iter().any(|(t, r)| *t == "Sodium" && r.starts_with("Couldn't put the new file in place")), "{failed:?}");
        assert!(failed.iter().any(|(t, r)| *t == "Lithium" && r.starts_with("Download failed")), "{failed:?}");
        assert_eq!(result["updated"].as_array().unwrap().len(), 1);

        // Sodium's old file is back where it was; nothing of the new version is left behind.
        assert_eq!(std::fs::read(mods.join(file_of(&fake, "S1"))).unwrap(), b"S1");
        assert!(!mods.join(file_of(&fake, "S3")).exists());
        assert!(!backup_dir(&state, PROFILE, "mod").join(file_of(&fake, "S1")).exists());
        // Lithium wasn't touched, and stays switched off.
        assert!(mods.join(format!("{}{DISABLED}", file_of(&fake, "L1"))).is_file());
        // Zoomify went ahead.
        assert!(mods.join(file_of(&fake, "Z2")).is_file());

        let manifest = load(&state, PROFILE);
        let version_of = |project: &str| manifest.iter().find(|m| m.project_id == project).map(|m| m.version_id.clone());
        assert_eq!(version_of("SODIUM01").as_deref(), Some("S1"));
        assert_eq!(version_of("LITHIUM1").as_deref(), Some("L1"));
        assert_eq!(version_of("ZOOMIFY1").as_deref(), Some("Z2"));
        assert!(no_staging_left(&state));
    }

    #[tokio::test]
    async fn an_update_whose_needs_cant_be_met_is_left_as_it_is() {
        let dir = TestDir::new();
        let mut fake = catalog();
        let (state, _) = profile_with_mods(&dir, &fake);
        let mods = state.paths.profile_dir(PROFILE).join("mods");
        // Cloth needs a library with no build for 26.2; Fabric API (which Zoomify needs) can't be downloaded.
        fake.broken = vec!["F1"];
        let result = apply(&state, &fake, PROFILE, vec![item(&fake, "C1", "C2"), item(&fake, "Z1", "Z2")]).await.unwrap();
        let reason = |title: &str| result["failed"].as_array().unwrap().iter().find(|f| f["title"] == title).unwrap()["reason"].as_str().unwrap().to_owned();
        assert!(reason("Cloth").starts_with("Needs Old Library"), "{result}");
        assert!(reason("Zoomify").starts_with("Couldn't download Fabric API, which it needs"), "{result}");
        assert!(result["updated"].as_array().unwrap().is_empty() && result["added"].as_array().unwrap().is_empty());
        assert_eq!(std::fs::read(mods.join(file_of(&fake, "C1"))).unwrap(), b"C1");
        assert_eq!(std::fs::read(mods.join(file_of(&fake, "Z1"))).unwrap(), b"Z1");
        assert!(!mods.join(file_of(&fake, "F1")).exists() && !mods.join(file_of(&fake, "C2")).exists());
        assert!(!load(&state, PROFILE).iter().any(|m| m.project_id == "ZOOMIFY1"), "a hand-added file is only recorded once it's updated");
    }

    #[tokio::test]
    async fn nothing_changes_while_the_game_runs() {
        let dir = TestDir::new();
        let fake = catalog();
        let (state, _) = profile_with_mods(&dir, &fake);
        let mods = state.paths.profile_dir(PROFILE).join("mods");
        let before = load(&state, PROFILE);

        // Started before the update: refused at once.
        state.running.lock().unwrap().insert(PROFILE.into(), RunningGame { kill: None });
        assert!(apply(&state, &fake, PROFILE, vec![item(&fake, "S1", "S3")]).await.unwrap_err().contains("Close Minecraft"));
        state.running.lock().unwrap().clear();

        // Started while files downloaded: checked again before anything is swapped.
        let fake = Fake { starts_game: Some(&state), ..catalog() };
        assert!(apply(&state, &fake, PROFILE, vec![item(&fake, "S1", "S3"), item(&fake, "Z1", "Z2")]).await.unwrap_err().contains("Close Minecraft"));
        assert_eq!(std::fs::read(mods.join(file_of(&fake, "S1"))).unwrap(), b"S1");
        assert!(!mods.join(file_of(&fake, "S3")).exists() && !mods.join(file_of(&fake, "F1")).exists());
        assert_eq!(load(&state, PROFILE), before);
        assert!(no_staging_left(&state));
    }

    #[tokio::test]
    async fn a_pack_that_keeps_its_file_name_still_keeps_its_earlier_version() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let profile = Profile { id: PROFILE.into(), name: "Packs".into(), game_version: GAME.into(), loader: "vanilla".into(), ..Default::default() };
        crate::data::save_profiles(&state, &crate::data::ProfilesFile { selected: None, profiles: vec![profile] }).unwrap();
        let pack = |id: &str, number: &str, date: &str| {
            let mut v = version("FAITHFUL", id, number, "minecraft", GAME, "release", date, json!([]));
            v["files"][0]["filename"] = json!("Faithful.zip");
            v
        };
        let fake = Fake {
            projects: vec![json!({ "id": "FAITHFUL", "slug": "faithful", "title": "Faithful", "project_type": "resourcepack", "loaders": ["minecraft"] })],
            versions: vec![pack("P1", "1", "01-01"), pack("P2", "2", "02-01"), pack("P3", "3", "03-01")],
            ..Default::default()
        };
        let packs = state.paths.profile_dir(PROFILE).join("resourcepacks");
        std::fs::create_dir_all(&packs).unwrap();
        std::fs::write(packs.join("Faithful.zip"), b"P1").unwrap();
        let record = ContentEntry {
            kind: "resourcepack".into(),
            project_id: "FAITHFUL".into(),
            version_id: "P1".into(),
            title: "Faithful".into(),
            version_number: "1".into(),
            file_name: "Faithful.zip".into(),
            sha512: sha(b"P1"),
            source: "user".into(),
            ..Default::default()
        };
        save(&state, PROFILE, &[record]).unwrap();
        let update = |to: &str| UpdateItem { kind: "resourcepack".into(), file_name: "Faithful.zip".into(), version_id: to.into(), world: None };

        apply(&state, &fake, PROFILE, vec![update("P2")]).await.unwrap();
        apply(&state, &fake, PROFILE, vec![update("P3")]).await.unwrap();
        assert_eq!(std::fs::read(packs.join("Faithful.zip")).unwrap(), b"P3");
        assert_eq!(std::fs::read(backup_dir(&state, PROFILE, "resourcepack").join("Faithful.zip")).unwrap(), b"P2", "the earlier version replaces the one before it");
        assert_eq!(load(&state, PROFILE)[0].previous.as_ref().unwrap().version_id, "P2");
    }

    #[test]
    fn two_updates_in_one_go_never_keep_their_earlier_versions_under_one_name() {
        // Data packs of two worlds share one backup folder; their records are only saved after both are swapped.
        let dir = TestDir::new();
        let fake = catalog();
        let backups = dir.0.join(".flint-backup/datapack");
        let mut kept = HashSet::new();
        let mut swap_in_world = |world: &str, old: &[u8]| {
            let packs = dir.0.join("saves").join(world).join("datapacks");
            std::fs::create_dir_all(&packs).unwrap();
            std::fs::write(packs.join("Terrain.zip"), old).unwrap();
            let staged = dir.0.join(format!("staged-{world}.zip"));
            std::fs::write(&staged, b"new").unwrap();
            let old = ContentEntry { kind: "datapack".into(), world: Some(world.into()), file_name: "Terrain.zip".into(), ..Default::default() };
            let new = NewFile { kind: "datapack".into(), world: Some(world.into()), file_name: "Terrain-2.zip".into(), ..Default::default() };
            let item = UpdateItem { kind: "datapack".into(), file_name: "Terrain.zip".into(), version_id: "T2".into(), world: Some(world.into()) };
            let swap = Swap { item, old, version: Value::Null, new, failed: None, staged: Some(staged.clone()) };
            (swap_in(&fake, &packs, &backups, &[], &mut kept, &swap, &staged).map(|_| ()), packs)
        };
        let (first, _) = swap_in_world("A", b"A1");
        assert!(first.is_ok());
        let (second, packs) = swap_in_world("B", b"B1");
        assert!(second.unwrap_err().contains("same file name"));
        assert_eq!(std::fs::read(backups.join("Terrain.zip")).unwrap(), b"A1", "the first world's earlier version is kept");
        assert_eq!(std::fs::read(packs.join("Terrain.zip")).unwrap(), b"B1", "the second world keeps its pack as it was");
    }

    #[tokio::test]
    async fn a_hand_added_jar_set_aside_for_a_dependency_comes_back_when_its_update_fails() {
        let dir = TestDir::new();
        let mut fake = catalog();
        let (state, _) = profile_with_mods(&dir, &fake);
        let mods = state.paths.profile_dir(PROFILE).join("mods");
        // A jar dropped in by hand under Fabric API's file name; Zoomify's new file then can't go in.
        std::fs::write(mods.join(file_of(&fake, "F1")), b"mine").unwrap();
        fake.stuck = Some(file_of(&fake, "Z2"));
        let result = apply(&state, &fake, PROFILE, vec![item(&fake, "Z1", "Z2")]).await.unwrap();
        assert!(result["updated"].as_array().unwrap().is_empty(), "{result}");
        assert_eq!(std::fs::read(mods.join(file_of(&fake, "F1"))).unwrap(), b"mine", "the hand-added jar is back in place");
        assert_eq!(std::fs::read(mods.join(file_of(&fake, "Z1"))).unwrap(), b"Z1");
        assert!(!mods.join(".flint-replaced").join(file_of(&fake, "F1")).exists());
    }

    #[test]
    fn pins_are_kept_for_flints_files_and_hand_added_ones() {
        let dir = TestDir::new();
        let fake = catalog();
        let (state, _) = profile_with_mods(&dir, &fake);
        let target = |name: String| ContentRef { kind: "mod".into(), file_name: name, world: None };
        pin(&state, PROFILE, &[target(file_of(&fake, "S1")), target(file_of(&fake, "Z1")), target("mystery.jar".into())], true).unwrap();
        let manifest = load(&state, PROFILE);
        let find = |name: &str| manifest.iter().find(|m| m.file_name == name).cloned();
        assert!(find(&file_of(&fake, "S1")).unwrap().pinned);
        let zoomify = find(&file_of(&fake, "Z1")).unwrap();
        assert_eq!((zoomify.pinned, zoomify.project_id.as_str(), zoomify.source.as_str()), (true, "ZOOMIFY1", "manual"));
        let mystery = find("mystery.jar").unwrap();
        assert_eq!((mystery.pinned, mystery.project_id.as_str(), mystery.title.as_str()), (true, "", "mystery"));
        // A pin is written only where there is one, so the record reads as before everywhere else.
        let text = std::fs::read_to_string(manifest_path(&state, PROFILE)).unwrap();
        assert_eq!(text.matches("\"pinned\"").count(), 4, "Sodium, Lithium, Zoomify and the unknown jar");

        pin(&state, PROFILE, &[target(file_of(&fake, "Z1")), target("mystery.jar".into())], false).unwrap();
        let manifest = load(&state, PROFILE);
        assert!(!manifest.iter().any(|m| m.file_name == "mystery.jar"), "a record kept only for its pin goes with it");
        assert!(manifest.iter().any(|m| m.project_id == "ZOOMIFY1" && !m.pinned));
        assert!(pin(&state, PROFILE, &[target("gone.jar".into())], true).unwrap_err().contains("no longer"));
        assert!(pin(&state, PROFILE, &[target("../escape.jar".into())], true).is_err());

        // An unknown file Flint only keeps a pin for may go missing without stopping a launch.
        pin(&state, PROFILE, &[target("mystery.jar".into())], true).unwrap();
        std::fs::remove_file(state.paths.profile_dir(PROFILE).join("mods").join("mystery.jar")).unwrap();
        assert!(launch_preflight(&state, PROFILE).is_ok());
    }

    #[test]
    fn records_from_before_pins_load_unpinned() {
        let old = r#"[{"kind":"mod","projectId":"AANobbMI","versionId":"abc","title":"Sodium","fileName":"sodium.jar"}]"#;
        let entries: Vec<ContentEntry> = serde_json::from_str(old).unwrap();
        assert!(!entries[0].pinned);
        assert!(!serde_json::to_string(&entries[0]).unwrap().contains("pinned"));
    }
}
