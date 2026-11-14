//! The in-game Library (game-runtime `control/rshift/LibraryPage`), through the game link (`gamelink.rs`): the game's own
//! profile searched, installed into and cleaned up by the launcher's own content code, never a copy of it in the game.
//!
//! - Only mods, resource packs and shader packs, and only what fits the profile: its Minecraft version, and Fabric for mods
//!   and shader packs (Modrinth's and CurseForge's own filters, as the Library page asks them). No data packs, no modpacks.
//! - Search hits come trimmed for the game (id, name, author, downloads, a short description, the icon's address) and say
//!   whether the profile has them already: by project, by slug (the same mod from the other site), by the mod ids its jars
//!   declare (a mod added by hand) and, for packs, by name.
//! - An install is the Library's own: planned with its dependencies (`resolve`), every file downloaded and checked
//!   (`install_batch_as`), recorded in the profile's manifest, shown as a job in the launcher. While the game runs, packs go
//!   in live and are NOT turned on (the player turns them on in Minecraft's or Iris' screen); a mod goes in as a new file.
//!   Fabric can't load a mod into a running game (its mixins, access wideners, resources and entrypoints are fixed when the
//!   game starts), so Instant Swap (`swap.rs`) has a game with the new mods take over when the Library closes.
//! - Removing a pack while the game runs is live (`remove_items`). A mod the running game has loaded is never touched
//!   (Windows keeps it locked, and the game uses it): it's marked in [`PENDING`], left out of the game Instant Swap starts,
//!   and deleted once the old game has ended (or before the next launch).
//! - Pictures: an icon by its address from a search, only from Modrinth's and CurseForge's picture hosts, at most 1 MB,
//!   without redirects; a WebP comes back as a small PNG (the game reads PNG and JPEG). Kept for the session, a few at most.

use super::*;
use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

/// What the in-game Library offers.
pub const KINDS: [&str; 3] = ["mod", "resourcepack", "shader"];
/// The mods to remove once the game that loaded them has ended (`[{"kind":"mod","fileName":…}]`).
pub const PENDING: &str = ".flint-pending-removal.json";
/// Where icons may come from: Modrinth's CDN and CurseForge's picture host.
pub const ART_HOSTS: [&str; 2] = ["cdn.modrinth.com", "media.forgecdn.net"];
const MAX_ART: usize = 1024 * 1024;
/// The size a WebP icon is made into (the game draws icons at most about this big).
const ART_SIZE: u32 = 96;
const ART_KEPT: usize = 160;
const PAGE: u32 = 24;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
struct PendingRemoval {
    kind: String,
    file_name: String,
}

/// A plain file name, as the content folders take it.
pub fn plain(name: &str) -> bool {
    plain_name(name)
}

/// An icon address the launcher may fetch for the game: https on one of [`ART_HOSTS`], no user part, no odd characters.
pub fn art_url(url: &str) -> bool {
    let Ok(parsed) = url::Url::parse(url) else { return false };
    url.len() <= 1024
        && parsed.scheme() == "https"
        && parsed.username().is_empty()
        && parsed.password().is_none()
        && parsed.port().is_none()
        && parsed.host_str().is_some_and(|h| ART_HOSTS.contains(&h))
}

fn needs_fabric(kind: &str) -> bool {
    matches!(kind, "mod" | "shader")
}

/// A name for matching: lowercase letters and digits only ("Ferrite-Core" and "ferritecore" are the same).
fn key(text: &str) -> String {
    text.chars().filter(char::is_ascii_alphanumeric).map(|c| c.to_ascii_lowercase()).collect()
}

/// One thing in the profile, as the game marks a search hit with it.
#[derive(Clone, Debug, PartialEq)]
struct Have {
    file_name: String,
    pending: bool,
    enabled: bool,
}

/// What the profile has of each kind, by every name a hit may be matched with.
#[derive(Default)]
struct Present {
    by: HashMap<String, Have>,
    counts: HashMap<&'static str, usize>,
    pending: usize,
}

impl Present {
    fn find(&self, kind: &str, project_id: &str, slug: &str, title: &str) -> Option<&Have> {
        let mut names = vec![format!("{kind}|p|{project_id}"), format!("{kind}|k|{}", key(slug))];
        if kind != "mod" {
            names.push(format!("{kind}|t|{}", key(title)));
        }
        names.iter().filter(|n| !n.ends_with('|')).find_map(|n| self.by.get(n))
    }
}

fn read_pending(state: &AppState, profile_id: &str) -> Vec<PendingRemoval> {
    read_json(&state.paths.profile_dir(profile_id).join(PENDING))
}

fn write_pending(state: &AppState, profile_id: &str, list: &[PendingRemoval]) -> CmdResult<()> {
    let path = state.paths.profile_dir(profile_id).join(PENDING);
    if list.is_empty() {
        let _ = std::fs::remove_file(&path);
        return Ok(());
    }
    write_json(&path, &list)
}

/// The mods waiting to be removed (file names), for the launcher's own list.
pub fn pending_names(state: &AppState, profile_id: &str) -> HashSet<String> {
    read_pending(state, profile_id).into_iter().filter(|p| p.kind == "mod").map(|p| p.file_name).collect()
}

fn present(state: &AppState, profile_id: &str) -> CmdResult<Present> {
    let listed = list_content(state, profile_id)?;
    let pending = pending_names(state, profile_id);
    let jars: HashMap<String, crate::perfcore::ModJar> = crate::perfcore::scan_mods(&state.paths.profile_dir(profile_id).join("mods")).into_iter().map(|j| (j.file.clone(), j)).collect();
    let manifest = load(state, profile_id);
    let mut out = Present::default();
    for (field, kind) in [("mods", "mod"), ("resourcepacks", "resourcepack"), ("shaders", "shader")] {
        let items = listed[field].as_array().cloned().unwrap_or_default();
        out.counts.insert(kind, items.len());
        for item in items {
            let file = item["fileName"].as_str().unwrap_or_default().to_owned();
            let have = Have { pending: kind == "mod" && pending.contains(&file), enabled: item["enabled"].as_bool() != Some(false), file_name: file.clone() };
            if have.pending {
                out.pending += 1;
            }
            let mut names: Vec<String> = Vec::new();
            if let Some(id) = item["projectId"].as_str().filter(|s| !s.is_empty()) {
                names.push(format!("{kind}|p|{id}"));
            }
            if let Some(slug) = manifest.iter().find(|m| m.kind == kind && m.file_name == file).map(|m| m.slug.clone()).filter(|s| !s.is_empty()) {
                names.push(format!("{kind}|k|{}", key(&slug_key(&slug))));
            }
            if kind == "mod" {
                if let Some(jar) = jars.get(&file) {
                    names.push(format!("mod|k|{}", key(&jar.id)));
                    names.extend(jar.provides.iter().map(|p| format!("mod|k|{}", key(p))));
                }
            } else if let Some(title) = item["title"].as_str() {
                names.push(format!("{kind}|t|{}", key(title)));
            }
            for name in names {
                out.by.entry(name).or_insert_with(|| have.clone());
            }
        }
    }
    Ok(out)
}

/// The profile as the in-game Library's header shows it: how many mods, resource packs and shader packs it has, which can
/// be added, and how many mods leave at the next start.
pub async fn view(state: &AppState, profile_id: &str) -> CmdResult<Value> {
    let profile = find_profile(state, profile_id)?;
    // Files added by hand are named by Modrinth once (hashes are kept), so they count as installed in searches.
    let _ = tokio::time::timeout(Duration::from_secs(6), identify(state, profile_id)).await;
    let have = present(state, profile_id)?;
    let listed = list_content(state, profile_id)?;
    let iris = listed["irisInstalled"].as_bool() == Some(true);
    // What "Your mods" lists: every mod, resource pack and shader pack, with its name, version and icon.
    let mut items = Vec::new();
    for (field, kind) in [("mods", "mod"), ("resourcepacks", "resourcepack"), ("shaders", "shader")] {
        for item in listed[field].as_array().into_iter().flatten() {
            if item["pendingRemoval"] == true || items.len() >= 600 {
                continue;
            }
            items.push(json!({
                "kind": kind,
                "fileName": item["fileName"],
                "title": text(&item["title"], 60),
                "version": text(&item["versionNumber"], 40),
                "enabled": item["enabled"].as_bool() != Some(false),
                "icon": item["iconUrl"].as_str().filter(|u| art_url(u)),
            }));
        }
    }
    Ok(json!({
        "items": items,
        "gameVersion": profile.game_version,
        "fabric": takes_mods(&profile),
        "shaders": takes_shaders(&profile),
        "mods": have.counts.get("mod").copied().unwrap_or(0),
        "resourcepacks": have.counts.get("resourcepack").copied().unwrap_or(0),
        "shaders": have.counts.get("shader").copied().unwrap_or(0),
        "pendingRemoval": have.pending,
        "iris": iris,
    }))
}

/// One page of a search on `source` for `kind` content that fits the profile, each hit marked with what the profile has.
pub async fn search(state: &AppState, profile_id: &str, source: &str, kind: &str, query: &str, offset: u32, sort: Option<&str>) -> CmdResult<Value> {
    let profile = find_profile(state, profile_id)?;
    if !KINDS.contains(&kind) {
        return Err("bad request".into());
    }
    if !takes(&profile, kind) {
        return Ok(json!({ "hits": [], "total": 0, "unavailable": "no_mods", "message": refusal(&profile, kind) }));
    }
    let params = crate::modrinth::LibrarySearch {
        kind: kind.to_owned(),
        query: query.chars().take(120).collect(),
        index: sort.map(str::to_owned),
        offset: offset.min(9_000),
        limit: Some(PAGE),
        game_version: Some(profile.game_version.clone()),
        loader: needs_fabric(kind).then(|| "fabric".to_owned()),
        categories: Vec::new(),
        client_only: kind == "mod",
    };
    let answer = if source == "curseforge" { curseforge::search(state, &params).await? } else { crate::modrinth::search(state, &params).await? };
    let have = present(state, profile_id).unwrap_or_default();
    let mut hits: Vec<Value> = answer["hits"].as_array().into_iter().flatten().filter_map(|h| trim_hit(h, kind, &have)).collect();
    if params.offset == 0 {
        rank(&mut hits, query);
    }
    Ok(json!({
        "hits": hits,
        "total": answer["total"].as_u64().unwrap_or(0).min(10_000),
        "offset": params.offset,
        "unavailable": answer["unavailable"],
        "message": answer["message"],
    }))
}

/// The project named exactly as searched first, then names that start with it; otherwise the site's own order. CurseForge
/// ranks by popularity, so "AppleSkin" could come after mods that only mention it.
fn rank(hits: &mut [Value], query: &str) {
    let wanted = key(query);
    if wanted.is_empty() {
        return;
    }
    let place = |hit: &Value| {
        let (title, slug) = (key(hit["title"].as_str().unwrap_or_default()), key(hit["slug"].as_str().unwrap_or_default()));
        if title == wanted || slug == wanted {
            0
        } else if title.starts_with(&wanted) || slug.starts_with(&wanted) {
            1
        } else {
            2
        }
    };
    hits.sort_by_key(place);
}

fn text(value: &Value, max: usize) -> String {
    let raw = value.as_str().unwrap_or_default();
    // Control characters become spaces; Minecraft's formatting codes (the section sign and the letter after it) go.
    let mut plain = String::with_capacity(raw.len());
    let mut chars = raw.chars();
    while let Some(c) = chars.next() {
        if c == '\u{a7}' {
            chars.next();
        } else {
            plain.push(if c.is_control() { ' ' } else { c });
        }
    }
    let mut out: String = plain.split_whitespace().collect::<Vec<_>>().join(" ");
    if out.chars().count() > max {
        out = out.chars().take(max.saturating_sub(1)).collect::<String>().trim_end().to_owned() + "…";
    }
    out
}

fn trim_hit(hit: &Value, kind: &str, have: &Present) -> Option<Value> {
    let id = hit["projectId"].as_str().filter(|id| curseforge::valid_ref(id))?.to_owned();
    let title = text(&hit["title"], 60);
    if title.is_empty() {
        return None;
    }
    let slug = hit["slug"].as_str().unwrap_or_default();
    let installed = have.find(kind, &id, &slug_key(slug), &title);
    Some(json!({
        "id": id,
        "slug": text(&hit["slug"], 64),
        "title": title,
        "author": text(&hit["author"], 40),
        "description": text(&hit["description"], 200),
        "downloads": hit["downloads"].as_u64().unwrap_or(0),
        "icon": hit["iconUrl"].as_str().filter(|u| art_url(u)),
        "installed": installed.map(|h| json!({ "fileName": h.file_name, "pending": h.pending, "enabled": h.enabled })),
    }))
}

/// What installing `id` would put in: its own file and the libraries it needs, or why it can't go in.
async fn planned(state: &AppState, profile_id: &str, kind: &str, id: &str) -> CmdResult<(Profile, Vec<InstallItem>, Vec<Value>, String)> {
    let profile = find_profile(state, profile_id)?;
    if !KINDS.contains(&kind) || !curseforge::valid_ref(id) {
        return Err("bad request".into());
    }
    if !takes(&profile, kind) {
        return Err(refusal(&profile, kind).into());
    }
    let p = project(state, id).await?;
    let found = project_kind(&p);
    if found != kind {
        return Err("This project isn't that kind of content.".into());
    }
    let title = p["title"].as_str().unwrap_or("This project").to_owned();
    let root_id = p["id"].as_str().unwrap_or(id).to_owned();
    let roots = vec![Pending { project: p, kind: kind.to_owned(), version_id: None, source: "user".into(), reason: None, required_by: None, required: true }];
    let plan = resolve(state, &profile, Some(profile_id), roots).await?;
    if let Some(first) = plan["blockers"].as_array().and_then(|b| b.first()).and_then(Value::as_str) {
        return Err(first.to_owned());
    }
    let have = present(state, profile_id)?;
    let mut items = Vec::new();
    let mut parts = Vec::new();
    for item in plan["items"].as_array().into_iter().flatten() {
        let item_kind = item["kind"].as_str().unwrap_or_default();
        let pid = item["projectId"].as_str().unwrap_or_default();
        let root = pid == root_id && item_kind == kind;
        if item["status"] != "ok" || !(root || item["required"].as_bool() == Some(true) && item["source"] == "dependency") || !KINDS.contains(&item_kind) {
            continue;
        }
        // Already in the mods folder under another name (added by hand, or from the other site): not a second copy.
        if have.find(item_kind, pid, &slug_key(item["slug"].as_str().unwrap_or_default()), item["title"].as_str().unwrap_or_default()).is_some() {
            if root {
                return Err(format!("{title} is already in this profile."));
            }
            continue;
        }
        let Some(version) = item["versionId"].as_str() else { continue };
        parts.push(json!({ "title": text(&item["title"], 60), "kind": item_kind, "version": text(&item["versionNumber"], 40), "size": item["size"], "root": root }));
        items.push(InstallItem { version_id: version.to_owned(), kind: Some(item_kind.to_owned()), source: if root { "user".into() } else { "dependency".into() }, reason: None });
    }
    if !parts.iter().any(|p| p["root"] == true) {
        return Err(format!("{title} isn't available for Minecraft {}.", profile.game_version));
    }
    Ok((profile, items, parts, title))
}

/// What an install would add (the project's file and the libraries it needs), for the card's details.
pub async fn plan(state: &AppState, profile_id: &str, kind: &str, id: &str) -> CmdResult<Value> {
    let (_, _, parts, _) = planned(state, profile_id, kind, id).await?;
    Ok(json!({ "parts": parts }))
}

/// Installs `id` and what it needs into the profile, as the Library does (a job in the launcher). Packs aren't turned on.
pub async fn install(app: &AppHandle, state: &AppState, profile_id: &str, kind: &str, id: &str) -> CmdResult<Value> {
    let (_, items, _, title) = planned(state, profile_id, kind, id).await?;
    // A mod waiting to be removed that is asked for again simply stays.
    let (job, _) = state.jobs.create(app, "install", Some(profile_id), &format!("{title} (from Minecraft)"), items.len());
    let outcome = install_batch_as(app, state, &job, profile_id, items, None, false, true).await;
    state.jobs.finish(app, &job, &outcome);
    let outcome = outcome?;
    let files: Vec<Value> = outcome["files"].as_array().cloned().unwrap_or_default();
    // A mod added while the game runs: Instant Swap prepares a game with it at once (now, not when the Library closes), which
    // takes over when the Library closes. A resource pack or shader pack never swaps: its file is in the folder, ready in
    // Minecraft's Resource Packs screen or Iris's Shader Packs screen (a mod it needs, such as Iris, comes in at the next start).
    let swap = swaps_for(kind) && livepacks::running(state, profile_id) && files.iter().any(|f| f["kind"] == "mod");
    if swap {
        crate::swap::changed(app, profile_id, &title);
    }
    let file = files.iter().find(|f| f["kind"] == kind && f["projectId"].as_str().is_some_and(|p| p == id || !curseforge::is_ref(id))).or(files.iter().find(|f| f["kind"] == kind));
    Ok(json!({
        "fileName": file.map(|f| f["fileName"].clone()),
        "added": files.iter().map(|f| f["title"].clone()).collect::<Vec<_>>(),
        "swap": swap,
        // The mods put in, the libraries first: the game tries to load them live (HotLoader) before it swaps.
        "modFiles": mod_files(&files, file.and_then(|f| f["fileName"].as_str())),
    }))
}

/// The mod files an install put in, the project's own last (a mod loads after the libraries it needs).
fn mod_files(files: &[Value], own: Option<&str>) -> Vec<String> {
    let mut names: Vec<String> = files.iter().filter(|f| f["kind"] == "mod").filter_map(|f| f["fileName"].as_str()).filter(|n| plain_name(n)).map(str::to_owned).collect();
    names.sort_by_key(|n| Some(n.as_str()) == own);
    names
}

/// Whether installing this kind in the in-game Library may start an Instant Swap: only a mod does.
pub fn swaps_for(kind: &str) -> bool {
    kind == "mod"
}

/// Takes `file_name` out of the profile. A mod while its game runs is marked to go at the next start instead; `keep` takes
/// that mark back.
pub async fn remove(app: &AppHandle, state: &AppState, profile_id: &str, kind: &str, file_name: &str, keep: bool, also: bool) -> CmdResult<Value> {
    find_profile(state, profile_id)?;
    if !KINDS.contains(&kind) || !plain_name(file_name) {
        return Err("bad request".into());
    }
    let _guard = state.file_lock.lock().await;
    let mut pending = read_pending(state, profile_id);
    // A mod others need never goes alone (Iris without Sodium wouldn't start: the new game would refuse the set): the
    // player is asked first, and takes them out together or not at all.
    let mut files = vec![file_name.to_owned()];
    if kind == "mod" && !keep {
        let dir = kind_dir(state, profile_id, kind, None)?;
        let going: std::collections::HashSet<String> = pending.iter().filter(|p| p.kind == "mod").map(|p| p.file_name.clone()).collect();
        let mods: Vec<crate::perfcore::ModJar> = crate::perfcore::scan_mods(&dir).into_iter().filter(|m| !going.contains(&m.file)).collect();
        let needing = dependents(&mods, file_name);
        if !needing.is_empty() && !also {
            let title = mods.iter().find(|m| m.file == file_name).map_or_else(|| file_name.trim_end_matches(".jar").to_owned(), |m| m.name.clone());
            return Ok(json!({
                "removed": false,
                "title": title,
                "needs": needing.iter().map(|(name, file)| json!({ "title": name, "fileName": file })).collect::<Vec<_>>(),
            }));
        }
        files.extend(needing.into_iter().map(|(_, file)| file));
    }
    if kind == "mod" && (keep || livepacks::running(state, profile_id)) {
        for file in &files {
            pending.retain(|p| !(p.kind == kind && &p.file_name == file));
        }
        if !keep {
            let dir = kind_dir(state, profile_id, kind, None)?;
            if !dir.join(file_name).exists() && !dir.join(format!("{file_name}{DISABLED}")).exists() {
                return Err("That mod isn't in this profile any more.".into());
            }
            for file in &files {
                pending.push(PendingRemoval { kind: kind.into(), file_name: file.clone() });
            }
        }
        write_pending(state, profile_id, &pending)?;
        drop(_guard);
        // Instant Swap prepares a game without them, which takes over when the Library closes.
        crate::swap::changed(app, profile_id, file_name.trim_end_matches(".jar"));
        return Ok(json!({ "removed": true, "swap": true, "files": files }));
    }
    if keep {
        return Err("bad request".into());
    }
    let refs: Vec<ContentRef> = files.iter().map(|file| ContentRef { kind: kind.into(), file_name: file.clone(), world: None }).collect();
    remove_items(state, profile_id, &refs)?;
    Ok(json!({ "removed": true, "swap": false, "files": files }))
}

/// The mods in `mods` that would be left without something they require once `file` goes, directly or through
/// another that goes with it: (display name, file), in the order found. Fabric's own ids (minecraft, java, fabricloader)
/// aren't in any jar's `depends` here (ModJar leaves them out).
pub fn dependents(mods: &[crate::perfcore::ModJar], file: &str) -> Vec<(String, String)> {
    let mut gone: Vec<&str> = vec![file];
    let mut found = Vec::new();
    loop {
        let present: std::collections::HashSet<&str> = mods
            .iter()
            .filter(|m| !gone.contains(&m.file.as_str()))
            .flat_map(|m| std::iter::once(m.id.as_str()).chain(m.provides.iter().map(String::as_str)).chain(m.nested.iter().map(String::as_str)))
            .collect();
        let broken: Vec<&crate::perfcore::ModJar> = mods
            .iter()
            .filter(|m| !gone.contains(&m.file.as_str()) && m.depends.iter().any(|d| !present.contains(d.as_str())))
            .collect();
        if broken.is_empty() {
            return found;
        }
        for m in broken {
            gone.push(&m.file);
            found.push((m.name.clone(), m.file.clone()));
        }
    }
}

/// Removes the mods marked while their game ran, once it no longer runs (at its end, and before the next launch).
pub async fn settle_pending(state: &AppState, profile_id: &str) {
    if livepacks::running(state, profile_id) {
        return;
    }
    let pending = read_pending(state, profile_id);
    if pending.is_empty() {
        return;
    }
    let _guard = state.file_lock.lock().await;
    let mut left = Vec::new();
    for p in pending {
        if !plain_name(&p.file_name) || p.kind != "mod" {
            continue;
        }
        if let Err(error) = remove_items(state, profile_id, &[ContentRef { kind: p.kind.clone(), file_name: p.file_name.clone(), world: None }]) {
            eprintln!("[flint] couldn't remove {} as the game asked: {error}", p.file_name);
            left.push(p);
        }
    }
    if let Err(error) = write_pending(state, profile_id, &left) {
        eprintln!("[flint] couldn't update the mods waiting to be removed: {error}");
    }
}

// ---------- icons ----------

type Kept = Mutex<VecDeque<(String, &'static str, Vec<u8>)>>;

fn kept() -> &'static Kept {
    static KEPT: OnceLock<Kept> = OnceLock::new();
    KEPT.get_or_init(|| Mutex::new(VecDeque::new()))
}

fn art_client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .tls_backend_preconfigured(crate::core::tls_config())
            .connect_timeout(Duration::from_secs(8))
            .user_agent(crate::core::USER_AGENT)
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .expect("HTTP client configuration is valid")
    })
}

/// The picture at `url` (a search hit's icon) for the game: `{"type": "png"|"jpeg", "data": base64}`.
pub async fn art(url: &str) -> CmdResult<Value> {
    use base64::Engine;
    if !art_url(url) {
        return Err("bad request".into());
    }
    let hit = kept().lock().unwrap().iter().find(|(u, _, _)| u == url).map(|(_, k, b)| (*k, b.clone()));
    let (kind, bytes) = match hit {
        Some(found) => found,
        None => {
            let response = art_client().get(url).timeout(Duration::from_secs(15)).send().await.map_err(|_| "Flint couldn't get this picture.".to_owned())?;
            if !response.status().is_success() {
                return Err("Flint couldn't get this picture.".into());
            }
            let bytes = crate::store::read_capped(response, MAX_ART).await.map_err(|_| "Flint couldn't get this picture.".to_owned())?;
            let (kind, bytes) = tokio::task::spawn_blocking(move || for_game(bytes)).await.ok().flatten().ok_or("unsupported picture")?;
            let mut list = kept().lock().unwrap();
            list.push_back((url.to_owned(), kind, bytes.clone()));
            while list.len() > ART_KEPT {
                list.pop_front();
            }
            (kind, bytes)
        }
    };
    Ok(json!({ "type": kind, "data": base64::engine::general_purpose::STANDARD.encode(bytes) }))
}

/// A PNG or JPEG as it is; a WebP as a small PNG; anything else (GIF, SVG) isn't for the game.
fn for_game(bytes: Vec<u8>) -> Option<(&'static str, Vec<u8>)> {
    match crate::partners::media::content_type(&bytes)? {
        "image/png" => Some(("png", bytes)),
        "image/jpeg" => Some(("jpeg", bytes)),
        "image/webp" => crate::partners::game::webp_to_png(&bytes, ART_SIZE, ART_SIZE).map(|png| ("png", png)),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_mod_others_need_names_them_before_it_goes() {
        let jar = |file: &str, id: &str, depends: &[&str]| crate::perfcore::ModJar {
            file: file.into(),
            id: id.into(),
            name: id.to_uppercase(),
            depends: depends.iter().map(|d| (*d).to_owned()).collect(),
            ..Default::default()
        };
        let mods = vec![
            jar("sodium.jar", "sodium", &[]),
            jar("iris.jar", "iris", &["sodium"]),
            jar("shaderaddon.jar", "addon", &["iris"]),
            jar("appleskin.jar", "appleskin", &["fabric-api"]),
            jar("fabric-api.jar", "fabric-api", &[]),
        ];
        assert_eq!(dependents(&mods, "sodium.jar"), [("IRIS".to_owned(), "iris.jar".to_owned()), ("ADDON".to_owned(), "shaderaddon.jar".to_owned())], "Iris needs Sodium, and the add-on needs Iris");
        assert!(dependents(&mods, "appleskin.jar").is_empty(), "nothing needs AppleSkin: it goes alone");
        assert_eq!(dependents(&mods, "fabric-api.jar"), [("APPLESKIN".to_owned(), "appleskin.jar".to_owned())]);
    }

    #[test]
    fn the_libraries_come_before_the_mod_that_needs_them() {
        let files = vec![
            json!({ "kind": "mod", "fileName": "armour.jar" }),
            json!({ "kind": "mod", "fileName": "cloth.jar" }),
            json!({ "kind": "resourcepack", "fileName": "pack.zip" }),
            json!({ "kind": "mod", "fileName": "../evil.jar" }),
        ];
        assert_eq!(mod_files(&files, Some("armour.jar")), ["cloth.jar", "armour.jar"]);
    }

    #[test]
    fn only_a_mod_install_swaps_never_a_pack_or_a_shader() {
        assert!(swaps_for("mod"));
        for kind in ["resourcepack", "shader", "shaderpack", "datapack"] {
            assert!(!swaps_for(kind), "{kind}");
        }
    }

    #[test]
    fn icons_come_only_from_the_two_sites_picture_hosts() {
        assert!(art_url("https://cdn.modrinth.com/data/EsAfCjCV/icon.png"));
        assert!(art_url("https://media.forgecdn.net/avatars/thumbnails/1/2/64/64/icon.png"));
        assert!(!art_url("http://cdn.modrinth.com/data/x/icon.png"), "https only");
        assert!(!art_url("https://evil.example/cdn.modrinth.com/icon.png"));
        assert!(!art_url("https://cdn.modrinth.com.evil.example/icon.png"));
        assert!(!art_url("https://user@cdn.modrinth.com/icon.png"));
        assert!(!art_url("https://cdn.modrinth.com:8443/icon.png"));
        assert!(!art_url("https://127.0.0.1/icon.png"));
        assert!(!art_url("file:///C:/Windows/win.ini"));
    }

    #[test]
    fn names_match_however_they_are_spelt() {
        assert_eq!(key("Ferrite-Core"), "ferritecore");
        assert_eq!(key("dynamic_fps"), key("dynamic-fps"));
        let mut have = Present::default();
        have.by.insert("mod|k|appleskin".into(), Have { file_name: "appleskin.jar".into(), pending: false, enabled: true });
        have.by.insert("resourcepack|t|faithful32x".into(), Have { file_name: "Faithful 32x.zip".into(), pending: false, enabled: true });
        assert!(have.find("mod", "cf:248787", "appleskin", "AppleSkin").is_some(), "the CurseForge AppleSkin is the jar added by hand");
        assert!(have.find("mod", "abc", "zoomify", "AppleSkin").is_none(), "a mod is never matched by its title");
        assert!(have.find("resourcepack", "x", "faithful-32x", "Faithful 32x").is_some());
        assert!(have.find("shader", "x", "", "").is_none(), "an empty name matches nothing");
    }

    #[test]
    fn the_exact_name_comes_first() {
        let mut hits = vec![
            json!({ "title": "AppleSkin Addon", "slug": "appleskin-addon" }),
            json!({ "title": "Better Food", "slug": "betterfood" }),
            json!({ "title": "AppleSkin", "slug": "appleskin" }),
            json!({ "title": "Apple Skin Extras", "slug": "x" }),
        ];
        rank(&mut hits, "apple skin");
        let titles: Vec<&str> = hits.iter().map(|h| h["title"].as_str().unwrap()).collect();
        assert_eq!(titles, ["AppleSkin", "AppleSkin Addon", "Apple Skin Extras", "Better Food"]);
        let before = hits.clone();
        rank(&mut hits, "  ");
        assert_eq!(hits, before, "no query: the site's order");
    }

    #[test]
    fn hits_are_trimmed_for_the_game() {
        let have = Present::default();
        let hit = json!({ "projectId": "EsAfCjCV", "slug": "appleskin", "title": "AppleSkin\u{a7}c", "author": "squeek502", "downloads": 5,
            "description": "x".repeat(400), "iconUrl": "https://elsewhere.example/icon.png" });
        let out = trim_hit(&hit, "mod", &have).unwrap();
        assert_eq!(out["title"], "AppleSkin");
        assert_eq!(out["description"].as_str().unwrap().chars().count(), 200);
        assert!(out["icon"].is_null(), "an icon from another host isn't offered");
        assert!(out["installed"].is_null());
        assert!(trim_hit(&json!({ "projectId": "../x", "title": "A" }), "mod", &have).is_none());
    }

    #[test]
    fn file_names() {
        assert!(plain("Sodium 0.9.jar"));
        assert!(!plain("../flint.jar"));
        assert!(!plain(".flint-content.json"));
    }
}
