use crate::auth::active_account;
use crate::core::*;
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_autostart::ManagerExt;
use tauri_plugin_opener::OpenerExt;

pub const SUPPORTED_VERSIONS: [&str; 9] =
    ["26.3", "26.2", "26.1.2", "26.1.1", "26.1", "1.21.11", "1.21.10", "1.21.1", "1.8.9"];
pub const DISCORD_INVITE: &str = "https://discord.gg/59rAHZMWb7";
/// Built-in address of Flint's AI gateway, the production Cloudflare Worker (Mojang-verified players
/// only; no key or secret in the app). FLINT_AI_GATEWAY_URL (at run or build time) can point a build
/// elsewhere, e.g. at staging for tests; it is no longer a setting, so an address saved by an older Flint
/// is ignored.
const DEFAULT_AI_GATEWAY: Option<&str> = None;
const MANIFEST_URL: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

// ---------- settings & onboarding ----------

fn checked_object(value: &Value, limit: usize) -> CmdResult<()> {
    if !value.is_object() {
        return Err("Expected an object".into());
    }
    if serde_json::to_vec(value).map(|b| b.len()).unwrap_or(usize::MAX) > limit {
        return Err("Data is too large".into());
    }
    Ok(())
}

pub fn load_settings(state: &AppState) -> Value {
    read_json_opt(&state.paths.settings()).unwrap_or_else(|| json!({}))
}

/// Settings → "Performance mods for new profiles" (on unless turned off).
pub fn performance_mods_enabled(state: &AppState) -> bool {
    load_settings(state)["performanceMods"].as_bool().unwrap_or(true)
}

#[tauri::command]
pub fn settings_get(state: State<'_, AppState>) -> Value {
    load_settings(&state)
}

#[tauri::command]
pub async fn settings_set(app: AppHandle, state: State<'_, AppState>, settings: Value) -> CmdResult<()> {
    checked_object(&settings, 64 * 1024)?;
    let _guard = state.file_lock.lock().await;
    write_json(&state.paths.settings(), &settings)?;
    // Rich Presence follows its switches at once (turning it off clears the activity mid-game).
    if let Some(presence) = tauri::Manager::try_state::<crate::discord::Presence>(&app) {
        presence.configure(&settings);
    }
    Ok(())
}

#[tauri::command]
pub async fn onboarding_get(state: State<'_, AppState>) -> CmdResult<Value> {
    startup_fault().await?;
    Ok(read_json_opt(&state.paths.onboarding()).unwrap_or_else(|| json!({ "completed": false })))
}

/// Debug builds with an isolated data folder only: `FLINT_FAULT=boot-stall` delays a start-up
/// request past the interface's timeout, and `FLINT_FAULT=boot-error-once` fails the first one,
/// so the start-up recovery screen can be tested. Release builds never read this.
async fn startup_fault() -> CmdResult<()> {
    if !cfg!(debug_assertions) || std::env::var_os("FLINT_DATA_DIR").is_none() {
        return Ok(());
    }
    static FAILED_ONCE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
    match std::env::var("FLINT_FAULT").as_deref() {
        Ok("boot-stall") => tokio::time::sleep(Duration::from_secs(40)).await,
        Ok("boot-error-once") if !FAILED_ONCE.swap(true, std::sync::atomic::Ordering::SeqCst) => {
            return Err("Simulated start-up failure (FLINT_FAULT=boot-error-once)".into());
        }
        _ => {}
    }
    Ok(())
}

#[tauri::command]
pub async fn onboarding_set(state: State<'_, AppState>, value: Value) -> CmdResult<()> {
    checked_object(&value, 64 * 1024)?;
    let _guard = state.file_lock.lock().await;
    write_json(&state.paths.onboarding(), &value)
}

// ---------- app info & system ----------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    version: String,
    /// Release build number (`YYYY.MMDD.HHMM`, also the bundle's CFBundleVersion); `None` for dev builds.
    build: Option<&'static str>,
    commit: Option<&'static str>,
    platform: &'static str,
    arch: &'static str,
    data_dir: String,
    qa: bool,
    force_onboarding: bool,
    gateway_configured: bool,
    discord_url: Option<&'static str>,
    /// QA only (`FLINT_QA_PAGE` with an isolated `FLINT_DATA_DIR`): the page to open first, as
    /// `page` or `page:param`, so packaged builds can be screenshotted page by page.
    qa_start_page: Option<String>,
    /// Measurement builds only (`FLINT_PERF_RUN`): the scenario suite to run after start-up.
    perf_run: Option<String>,
}

pub fn gateway_url() -> Option<String> {
    std::env::var("FLINT_AI_GATEWAY_URL")
        .ok()
        .or_else(|| option_env!("FLINT_AI_GATEWAY_URL").map(str::to_owned))
        .or_else(|| DEFAULT_AI_GATEWAY.map(str::to_owned))
        .filter(|u| valid_gateway(u))
}

/// HTTPS anywhere, plain HTTP only for a gateway running on this computer.
fn valid_gateway(address: &str) -> bool {
    let Ok(url) = url::Url::parse(address.trim()) else { return false };
    match (url.scheme(), url.host_str()) {
        ("https", Some(host)) => !host.is_empty() && url.username().is_empty() && url.password().is_none(),
        ("http", Some("127.0.0.1" | "localhost" | "[::1]")) => url.username().is_empty() && url.password().is_none(),
        _ => false,
    }
}

#[tauri::command]
pub fn app_info(app: AppHandle, state: State<'_, AppState>) -> AppInfo {
    AppInfo {
        version: app.package_info().version.to_string(),
        build: option_env!("FLINT_BUILD").filter(|b| !b.is_empty()),
        commit: option_env!("FLINT_COMMIT").filter(|c| !c.is_empty()),
        platform: std::env::consts::OS,
        arch: std::env::consts::ARCH,
        data_dir: state.paths.root.display().to_string(),
        qa: state.qa,
        force_onboarding: state.force_onboarding,
        gateway_configured: gateway_url().is_some(),
        discord_url: Some(option_env!("FLINT_DISCORD_URL").filter(|u| u.starts_with("https://")).unwrap_or(DISCORD_INVITE)),
        qa_start_page: std::env::var_os("FLINT_DATA_DIR")
            .and(std::env::var("FLINT_QA_PAGE").ok())
            .filter(|p| p.len() <= 64 && p.chars().all(|c| c.is_ascii_alphanumeric() || ":-_".contains(c))),
        perf_run: if cfg!(feature = "perf") && state.qa { std::env::var("FLINT_PERF_RUN").ok() } else { None },
    }
}

/// Measurement builds only: saves a results file from the in-app recorder to `<data>/perf/`.
/// Compiled to a refusal everywhere else, so release builds can't write files through it.
#[tauri::command]
pub fn perf_write(state: State<'_, AppState>, name: String, json: String) -> CmdResult<()> {
    if !cfg!(feature = "perf") || !state.qa {
        return Err("Not available.".into());
    }
    if name.is_empty() || name.len() > 64 || !name.chars().all(|c| c.is_ascii_alphanumeric() || "-_".contains(c)) {
        return Err("Invalid name".into());
    }
    let dir = state.paths.root.join("perf");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    std::fs::write(dir.join(format!("{name}.json")), json).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn open_external(app: AppHandle, url: String) -> CmdResult<()> {
    let parsed = url::Url::parse(&url).map_err(|_| "Invalid link".to_string())?;
    if parsed.scheme() != "https" {
        return Err("Only secure links can be opened".into());
    }
    app.opener()
        .open_url(parsed.as_str(), None::<&str>)
        .map_err(err("Could not open the link"))
}

#[tauri::command]
pub fn reveal_folder(app: AppHandle, state: State<'_, AppState>, which: String, profile_id: Option<String>) -> CmdResult<()> {
    let path = match which.as_str() {
        "data" => state.paths.root.clone(),
        // Minecraft's shared files: versions, libraries and assets.
        "game" => state.paths.game(),
        "profiles" => state.paths.root.join("profiles"),
        "profile" | "mods" | "resourcepacks" | "shaderpacks" | "saves" | "screenshots" | "config" | "logs" => {
            let id = profile_id.ok_or("Missing profile")?;
            if !is_safe_id(&id) {
                return Err("Invalid profile".into());
            }
            find_profile(&state, &id)?;
            let dir = state.paths.profile_dir(&id);
            if which == "profile" { dir } else { dir.join(&which) }
        }
        // A profile's backups: earlier versions of updated content and Auto Sync's backups. Only opened, never made.
        "backups" => {
            let id = profile_id.ok_or("Missing profile")?;
            if !is_safe_id(&id) {
                return Err("Invalid profile".into());
            }
            find_profile(&state, &id)?;
            let dir = state.paths.profile_dir(&id).join(".flint-backup");
            if !dir.is_dir() {
                return Err("This profile has no backups.".into());
            }
            dir
        }
        _ => return Err("Unknown folder".into()),
    };
    std::fs::create_dir_all(&path).map_err(err("Could not open the folder"))?;
    app.opener()
        .open_path(path.display().to_string(), None::<&str>)
        .map_err(err("Could not open the folder"))
}

#[tauri::command]
pub fn launch_at_login_get(app: AppHandle) -> bool {
    app.autolaunch().is_enabled().unwrap_or(false)
}

#[tauri::command]
pub fn launch_at_login_set(app: AppHandle, enabled: bool) -> CmdResult<bool> {
    let manager = app.autolaunch();
    let result = if enabled { manager.enable() } else { manager.disable() };
    result.map_err(err("Could not change the login item"))?;
    Ok(manager.is_enabled().unwrap_or(enabled))
}

/// Runs blocking file-system work on a background thread, so a large walk can't freeze the window.
pub async fn blocking<T: Send + 'static>(app: &AppHandle, work: impl FnOnce(&AppState) -> T + Send + 'static) -> CmdResult<T> {
    let app = app.clone();
    tokio::task::spawn_blocking(move || work(&app.state::<AppState>()))
        .await
        .map_err(|e| format!("Background task failed: {e}"))
}

// ---------- profiles ----------

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    pub id: String,
    pub name: String,
    pub game_version: String,
    pub loader: String,
    #[serde(default)]
    pub loader_version: Option<String>,
    #[serde(default)]
    pub created_at: u64,
    #[serde(default)]
    pub last_played: Option<u64>,
    #[serde(default)]
    pub installed: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub modpack: Option<Value>,
    /// Flint's performance mods for this profile: "pending" for new Fabric profiles until they
    /// have been installed, then "applied". Absent for profiles created before this existed, which
    /// are never changed automatically.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub performance: Option<String>,
    /// Heap for this profile in MB. None means the global Settings → Memory value is used.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub memory_mb: Option<u64>,
    /// The profile's icon (profileicon.rs): a Minecraft item on a coloured tile, `{"kind":"item","name":"item/diamond",
    /// "color":"ember"}`, or the player's own picture, `{"kind":"image","file":"<id>-<n>.png"}`. None: the version's picture.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon: Option<Value>,
    /// When the profile was pinned to the sidebar (seconds since 1970); None when it isn't.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pinned_at: Option<u64>,
    /// Time played in this profile through Flint, in seconds (the sidebar's "most played").
    #[serde(default, skip_serializing_if = "is_zero")]
    pub play_seconds: u64,
    /// Its folder's name under `profiles/` ("Daemon-26.2", profilefolder.rs). None: a folder named by its id.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub folder: Option<String>,
    /// The other launcher's profile it was imported from (import/), so a later scan shows that one as already here.
    /// Never copied by Duplicate or carried in a `.flintpack`: a copy isn't the import. Read leniently: a value this
    /// Flint can't read is dropped instead of making profiles.json unreadable.
    #[serde(default, skip_serializing_if = "Option::is_none", deserialize_with = "lenient_origin")]
    pub origin: Option<crate::import::ImportOrigin>,
}

fn lenient_origin<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<Option<crate::import::ImportOrigin>, D::Error> {
    let value = Value::deserialize(deserializer)?;
    Ok(serde_json::from_value(value).ok())
}

fn is_zero(n: &u64) -> bool {
    *n == 0
}

/// The heap a launch gets: the profile's own value when it has one, else the global setting,
/// always kept inside what this computer can spare.
pub fn launch_memory_mb(profile: &Profile, global: Option<u64>, max: u64) -> u64 {
    profile.memory_mb.or(global).unwrap_or(4096).clamp(1024, max.max(1024))
}

/// A profile memory override must be a whole number of 256 MB steps between 1 GB and the computer's limit.
fn validate_profile_memory(memory_mb: Option<u64>, max: u64) -> CmdResult<Option<u64>> {
    match memory_mb {
        None => Ok(None),
        Some(mb) if !(1024..=max).contains(&mb) => Err(format!("Choose between 1.0 and {:.1} GB.", max as f64 / 1024.0)),
        Some(mb) if mb % 256 != 0 => Err("Memory must be a multiple of 256 MB.".into()),
        Some(mb) => Ok(Some(mb)),
    }
}

#[derive(Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProfilesFile {
    #[serde(default)]
    pub selected: Option<String>,
    #[serde(default)]
    pub profiles: Vec<Profile>,
}

pub fn load_profiles(state: &AppState) -> ProfilesFile {
    read_json(&state.paths.profiles())
}

pub fn save_profiles(state: &AppState, file: &ProfilesFile) -> CmdResult<()> {
    write_json(&state.paths.profiles(), file)
}

pub fn find_profile(state: &AppState, id: &str) -> CmdResult<Profile> {
    load_profiles(state)
        .profiles
        .into_iter()
        .find(|p| p.id == id)
        .ok_or_else(|| "That profile no longer exists.".into())
}

pub async fn update_profile(state: &AppState, id: &str, f: impl FnOnce(&mut Profile)) -> CmdResult<Profile> {
    let _guard = state.file_lock.lock().await;
    let mut file = load_profiles(state);
    let profile = file
        .profiles
        .iter_mut()
        .find(|p| p.id == id)
        .ok_or("That profile no longer exists.")?;
    f(profile);
    let updated = profile.clone();
    save_profiles(state, &file)?;
    Ok(updated)
}

pub fn clean_name(name: &str) -> CmdResult<String> {
    let name: String = name.trim().chars().filter(|c| !c.is_control()).take(40).collect();
    if name.is_empty() {
        return Err("Give the profile a name.".into());
    }
    Ok(name)
}

pub(crate) fn validate_version(version: &str, loader: &str) -> CmdResult<()> {
    if !SUPPORTED_VERSIONS.contains(&version) {
        return Err("That Minecraft version isn't offered in Flint yet.".into());
    }
    match loader {
        "vanilla" => Ok(()),
        "fabric" => Ok(()),
        _ => Err("Unknown loader".into()),
    }
}

#[tauri::command]
pub fn profiles_list(state: State<'_, AppState>) -> ProfilesFile {
    load_profiles(&state)
}

#[tauri::command]
pub async fn profiles_create(
    state: State<'_, AppState>,
    name: String,
    game_version: String,
    loader: String,
    select: bool,
) -> CmdResult<Profile> {
    let name = clean_name(&name)?;
    validate_version(&game_version, &loader)?;
    let _guard = state.file_lock.lock().await;
    let mut file = load_profiles(&state);
    let performance = (loader == "fabric" && performance_mods_enabled(&state)).then(|| "pending".to_owned());
    let mut profile = Profile {
        id: random_hex(6),
        name,
        game_version,
        loader,
        loader_version: None,
        created_at: now_secs(),
        last_played: None,
        installed: false,
        modpack: None,
        performance,
        memory_mb: None,
        ..Profile::default()
    };
    crate::profilefolder::assign(&state, &file, &mut profile);
    std::fs::create_dir_all(state.paths.profile_dir(&profile.id).join("mods"))
        .map_err(err("Could not create the profile folder"))?;
    file.profiles.push(profile.clone());
    if select || file.selected.is_none() {
        file.selected = Some(profile.id.clone());
    }
    save_profiles(&state, &file)?;
    Ok(profile)
}

#[tauri::command]
pub async fn profiles_rename(state: State<'_, AppState>, id: String, name: String) -> CmdResult<Profile> {
    let name = clean_name(&name)?;
    let renamed = update_profile(&state, &id, |p| p.name = name).await?;
    // Its folder takes the new name too, unless something is using it (then at a later start).
    Ok(crate::profilefolder::follow(&state, &id).await.unwrap_or(renamed))
}

/// Sets or clears (None) a profile's own memory. Applies from the next launch.
#[tauri::command]
pub async fn profiles_set_memory(state: State<'_, AppState>, id: String, memory_mb: Option<u64>) -> CmdResult<Profile> {
    let max = tokio::task::spawn_blocking(max_memory_mb).await.map_err(|e| e.to_string())?;
    let memory_mb = validate_profile_memory(memory_mb, max)?;
    update_profile(&state, &id, |p| p.memory_mb = memory_mb).await
}

#[tauri::command]
pub async fn profiles_select(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    let _guard = state.file_lock.lock().await;
    let mut file = load_profiles(&state);
    if !file.profiles.iter().any(|p| p.id == id) {
        return Err("That profile no longer exists.".into());
    }
    file.selected = Some(id);
    save_profiles(&state, &file)
}

#[tauri::command]
pub async fn profiles_inspect(app: AppHandle, id: String) -> CmdResult<Value> {
    blocking(&app, move |state| inspect_profile(state, &id)).await?
}

fn inspect_profile(state: &AppState, id: &str) -> CmdResult<Value> {
    let id = id.to_owned();
    let profile = find_profile(state, &id)?;
    let dir = state.paths.profile_dir(&id);
    let count = |sub: &str, ext: Option<&str>| {
        std::fs::read_dir(dir.join(sub))
            .map(|entries| {
                entries
                    .flatten()
                    .filter(|e| ext.is_none_or(|x| e.path().extension().is_some_and(|y| y == x)))
                    .count()
            })
            .unwrap_or(0)
    };
    Ok(json!({
        "profile": profile,
        "path": dir.display().to_string(),
        "mods": count("mods", Some("jar")),
        "worlds": count("saves", None),
        "screenshots": count("screenshots", None),
        "resourcePacks": count("resourcepacks", None),
    }))
}

fn copy_tree(from: &std::path::Path, to: &std::path::Path, depth: u32) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)?.flatten() {
        let name = entry.file_name();
        let text = name.to_string_lossy();
        if depth == 0 && (text == "logs" || text == "crash-reports" || text.starts_with(".flint-")) {
            continue;
        }
        let meta = entry.metadata()?;
        if meta.file_type().is_symlink() {
            continue;
        }
        if meta.is_dir() {
            copy_tree(&entry.path(), &to.join(&name), depth + 1)?;
        } else {
            std::fs::copy(entry.path(), to.join(&name))?;
        }
    }
    Ok(())
}

/// The record of a copy of `source`: its icon (`icon`, its own copy of a picture), version, loader and settings, but
/// unpinned, unplayed, in a folder of its own and not marked as imported (the import is the original, not the copy).
fn duplicate_of(source: Profile, id: String, name: String, icon: Option<Value>) -> Profile {
    Profile {
        id,
        name,
        created_at: now_secs(),
        last_played: None,
        icon,
        pinned_at: None,
        play_seconds: 0,
        folder: None,
        origin: None,
        ..source
    }
}

/// Copies a profile into a brand-new folder. The original profile is only read.
#[tauri::command]
pub async fn profiles_duplicate(state: State<'_, AppState>, id: String, name: String) -> CmdResult<Profile> {
    let source = find_profile(&state, &id)?;
    if state.running.lock().unwrap().contains_key(&id) {
        return Err("Close Minecraft before duplicating this profile.".into());
    }
    let name = clean_name(&name)?;
    let new_id = random_hex(6);
    let from = state.paths.profile_dir(&id);
    let staging = state.paths.root.join("profiles").join(format!("{new_id}.flint-importing"));
    let (from_c, staging_c) = (from.clone(), staging.clone());
    let copied = tokio::task::spawn_blocking(move || copy_tree(&from_c, &staging_c, 0)).await.map_err(|e| e.to_string())?;
    if let Err(e) = copied {
        let _ = std::fs::remove_dir_all(&staging);
        return Err(format!("Could not copy the profile: {e}"));
    }
    let icon = crate::profileicon::copy_for(&state, source.icon.as_ref(), &new_id);
    let mut profile = duplicate_of(source, new_id, name, icon);
    let _guard = state.file_lock.lock().await;
    let mut file = load_profiles(&state);
    crate::profilefolder::assign(&state, &file, &mut profile);
    if let Err(e) = std::fs::rename(&staging, state.paths.profile_dir(&profile.id)) {
        let _ = std::fs::remove_dir_all(&staging);
        return Err(format!("Could not finish the copy: {e}"));
    }
    file.profiles.push(profile.clone());
    save_profiles(&state, &file)?;
    Ok(profile)
}

/// Removes a profile from Flint. Its folder (worlds, mods, settings) is only deleted when
/// `delete_files` is set, which the interface asks for separately and explicitly.
#[tauri::command]
pub async fn profiles_delete(state: State<'_, AppState>, id: String, delete_files: bool) -> CmdResult<Value> {
    find_profile(&state, &id)?;
    if !is_safe_id(&id) {
        return Err("Invalid profile".into());
    }
    if state.running.lock().unwrap().contains_key(&id) {
        return Err("Close Minecraft before removing this profile.".into());
    }
    let _guard = state.file_lock.lock().await;
    let dir = state.paths.profile_dir(&id);
    let mut file = load_profiles(&state);
    // Its own picture goes with it (the folder may be kept; the picture is Flint's, not the game's).
    if let Some(gone) = file.profiles.iter().find(|p| p.id == id) {
        crate::profileicon::remove_picture(&state, gone.icon.as_ref());
    }
    file.profiles.retain(|p| p.id != id);
    if file.selected.as_deref() == Some(id.as_str()) {
        file.selected = file.profiles.first().map(|p| p.id.clone());
    }
    save_profiles(&state, &file)?;
    let kept = if delete_files {
        if dir.starts_with(state.paths.root.join("profiles")) && dir.exists() {
            std::fs::remove_dir_all(&dir).map_err(err("The profile was removed, but its folder couldn't be deleted"))?;
        }
        None
    } else {
        Some(dir.display().to_string())
    };
    Ok(json!({ "keptFolder": kept }))
}

/// This computer's memory in MB: what is installed (the "8 GB" on the box) and what the system can use
/// (a little less: firmware and integrated graphics keep some).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Memory {
    pub installed_mb: u64,
    pub usable_mb: u64,
}

/// What this computer's memory allows Minecraft, in MB: the size Settings shows, the largest heap Flint
/// hands the game (three quarters of the usable memory, so the system keeps room to breathe), the heap
/// suggested for modded play, and the point past which Settings warns.
#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MemoryLimits {
    pub total_mb: Option<u64>,
    pub max_mb: u64,
    pub recommended_mb: u64,
    pub warn_mb: Option<u64>,
}

/// The ceiling when the memory size can't be read: safe on the smallest computers Flint runs on.
const UNKNOWN_MAX_MB: u64 = 4096;

pub fn memory_limits(memory: Option<Memory>) -> MemoryLimits {
    let Some(Memory { installed_mb, usable_mb }) = memory else {
        return MemoryLimits { total_mb: None, max_mb: UNKNOWN_MAX_MB, recommended_mb: 3072, warn_mb: None };
    };
    let steps = |mb: u64| mb / 512 * 512;
    let max_mb = steps(usable_mb * 3 / 4).clamp(1024, 32768);
    let recommended_mb = if installed_mb <= 8192 {
        3072
    } else if installed_mb <= 16384 {
        4096
    } else {
        6144
    }
    .min(max_mb);
    MemoryLimits { total_mb: Some(installed_mb), max_mb, recommended_mb, warn_mb: Some(steps(usable_mb * 3 / 5).max(1024)) }
}

/// Largest heap Flint will hand Minecraft on this computer (see [`memory_limits`]).
pub fn max_memory_mb() -> u64 {
    memory_limits(memory()).max_mb
}

#[tauri::command]
pub async fn system_info() -> Value {
    // macOS reads its memory size through a short-lived system process; keep it off the main thread.
    tokio::task::spawn_blocking(system_summary).await.unwrap_or_default()
}

fn system_summary() -> Value {
    let memory = memory();
    json!({
        "totalMemory": memory.map(|m| m.installed_mb * 1024 * 1024),
        "memory": memory_limits(memory),
        "cpus": std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1),
        "os": std::env::consts::OS,
        "arch": std::env::consts::ARCH,
    })
}

/// Settings → Troubleshooting → "Copy troubleshooting details": what support needs to know about this Flint and
/// computer, as plain text, redacted (no account name, token, path with the user's name in it, or profile name).
#[tauri::command]
pub async fn diagnostics_text(app: AppHandle, state: State<'_, AppState>) -> CmdResult<String> {
    Ok(crate::redact::redact(&diagnostics_summary(&app, &state).await))
}

/// The text of `diagnostics_text`, before redaction (the diagnostics file's `summary.txt` redacts it too).
pub async fn diagnostics_summary(app: &AppHandle, state: &AppState) -> String {
    let settings = load_settings(state);
    let mb = |v: u64| format!("{:.1} GB", v as f64 / 1024.0);
    let mut lines = vec![
        format!(
            "Flint Client {}{}{}",
            app.package_info().version,
            option_env!("FLINT_BUILD").filter(|b| !b.is_empty()).map(|b| format!(" build {b}")).unwrap_or_default(),
            option_env!("FLINT_COMMIT").filter(|c| !c.is_empty()).map(|c| format!(" ({c})")).unwrap_or_default()
        ),
        format!("System: {} {}, {} CPU threads", std::env::consts::OS, std::env::consts::ARCH, std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1)),
    ];
    let memory = tokio::task::spawn_blocking(memory).await.unwrap_or(None);
    let limits = memory_limits(memory);
    lines.push(match memory {
        Some(m) => format!("Memory: {} installed, {} usable, up to {} for Minecraft", mb(m.installed_mb), mb(m.usable_mb), mb(limits.max_mb)),
        None => format!("Memory: unknown, up to {} for Minecraft", mb(limits.max_mb)),
    });
    let setting_mb = settings["memoryMb"].as_u64().unwrap_or(4096);
    lines.push(format!("Memory setting: {} (launches with {})", mb(setting_mb), mb(setting_mb.clamp(1024, limits.max_mb))));
    lines.push(match settings["javaPath"].as_str().map(str::trim).filter(|p| !p.is_empty()) {
        Some(path) => match inspect_java(path).await {
            Ok(java) => format!("Java: custom, {}", java.summary),
            Err(e) => format!("Java: custom, can't be used ({e})"),
        },
        None => "Java: managed by Flint".to_owned(),
    });
    let runtimes: Vec<String> = std::fs::read_dir(state.paths.runtimes())
        .map(|entries| entries.flatten().filter(|e| e.path().is_dir()).filter_map(|e| e.file_name().into_string().ok()).collect())
        .unwrap_or_default();
    lines.push(format!("Managed Java installed: {}", if runtimes.is_empty() { "none".to_owned() } else { runtimes.join(", ") }));
    let extra = settings["jvmArgs"].as_str().map(str::trim).unwrap_or_default();
    lines.push(format!("Extra Java arguments: {}", if extra.is_empty() { "none" } else { extra }));
    let on = |key: &str, default: bool| if settings[key].as_bool().unwrap_or(default) { "on" } else { "off" };
    lines.push(format!(
        "Settings: in-game menu {}, Automatic Optimization {}, Auto Sync {}, performance mods {}, open the game maximized {}, when the game starts: {}",
        on("inGameMenu", true),
        on("automaticOptimization", true),
        on("autoSync", true),
        on("performanceMods", true),
        on("gameMaximized", true),
        settings["gameStartBehavior"].as_str().unwrap_or("minimize")
    ));
    lines.push(format!(
        "Downloads: {} files at once, {} installs at once",
        crate::storage::download_limit(&settings),
        crate::storage::install_slots(&settings)
    ));
    let profiles = load_profiles(state).profiles;
    let mut versions: Vec<String> = profiles.iter().map(|p| format!("{} {}{}", p.game_version, p.loader, if p.installed { "" } else { " (not installed)" })).collect();
    versions.sort();
    lines.push(format!("Profiles ({}): {}", profiles.len(), if versions.is_empty() { "none".to_owned() } else { versions.join(", ") }));
    lines.push(format!("Accounts signed in: {}", crate::auth::load_accounts(state).accounts.len()));
    lines.join("\n")
}

/// Output of macOS `sysctl -n <keys>`: one value per line, in the order asked. A key this Mac doesn't
/// have is left out without a gap, so optional keys belong at the end.
pub fn sysctl(keys: &[&str]) -> Option<String> {
    std::process::Command::new("/usr/sbin/sysctl")
        .arg("-n")
        .args(keys)
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
}

/// Usable physical memory in bytes.
pub fn total_memory() -> Option<u64> {
    memory().map(|m| m.usable_mb * 1024 * 1024)
}

/// This computer's memory, read from the system itself. (It was read through PowerShell's WMI query on
/// Windows, which can fail or be blocked; the fallback then offered a 16 GB slider on an 8 GB computer.)
#[cfg(windows)]
pub fn memory() -> Option<Memory> {
    use windows_sys::Win32::System::SystemInformation::{GetPhysicallyInstalledSystemMemory, GlobalMemoryStatusEx, MEMORYSTATUSEX};
    // SAFETY: both calls only write into the values handed to them, sized as the API requires.
    let mut status: MEMORYSTATUSEX = unsafe { std::mem::zeroed() };
    status.dwLength = std::mem::size_of::<MEMORYSTATUSEX>() as u32;
    if unsafe { GlobalMemoryStatusEx(&mut status) } == 0 || status.ullTotalPhys == 0 {
        return None;
    }
    let usable_mb = status.ullTotalPhys / (1024 * 1024);
    let mut installed_kb = 0u64;
    // Installed memory comes from the firmware's tables; some virtual machines don't have them.
    let installed_mb = if unsafe { GetPhysicallyInstalledSystemMemory(&mut installed_kb) } != 0 && installed_kb / 1024 >= usable_mb {
        installed_kb / 1024
    } else {
        usable_mb
    };
    Some(Memory { installed_mb, usable_mb })
}

#[cfg(target_os = "macos")]
pub fn memory() -> Option<Memory> {
    let mb = sysctl(&["hw.memsize"]).and_then(|s| s.trim().parse::<u64>().ok()).filter(|b| *b > 0)? / (1024 * 1024);
    Some(Memory { installed_mb: mb, usable_mb: mb })
}

#[cfg(not(any(windows, target_os = "macos")))]
pub fn memory() -> Option<Memory> {
    let kb = std::fs::read_to_string("/proc/meminfo")
        .ok()
        .and_then(|s| s.lines().find(|l| l.starts_with("MemTotal")).and_then(|l| l.split_whitespace().nth(1)).and_then(|kb| kb.parse::<u64>().ok()))
        .filter(|kb| *kb > 0)?;
    Some(Memory { installed_mb: kb / 1024, usable_mb: kb / 1024 })
}

/// Reads the version string and major version from `java -version` output
/// (`"1.8.0_402"` is Java 8, `"25.0.3"` is Java 25).
pub fn parse_java_version(text: &str) -> (String, Option<u32>) {
    let quoted = text.split('"').nth(1).unwrap_or_default().to_owned();
    let major = if let Some(rest) = quoted.strip_prefix("1.") {
        rest.split('.').next().and_then(|x| x.parse::<u32>().ok())
    } else {
        quoted.split(['.', '-', '+']).next().and_then(|x| x.parse::<u32>().ok())
    };
    (quoted, major)
}

pub struct JavaInfo {
    pub version: String,
    pub major: Option<u32>,
    pub summary: String,
}

/// Checks that a path is an absolute java executable and runs `java -version` on it.
pub async fn inspect_java(path: &str) -> CmdResult<JavaInfo> {
    let path = path.trim();
    let file = std::path::PathBuf::from(path);
    if path.is_empty() || !file.is_absolute() {
        return Err("Use the full path to a java executable.".into());
    }
    let name = file.file_name().and_then(|n| n.to_str()).unwrap_or_default().to_ascii_lowercase();
    if !matches!(name.as_str(), "java" | "java.exe" | "javaw.exe") {
        return Err("That file isn't a java executable.".into());
    }
    if !file.is_file() {
        return Err("Nothing exists at that path.".into());
    }
    let run = tokio::process::Command::new(&file).arg("-version").output();
    let output = tokio::time::timeout(Duration::from_secs(6), run)
        .await
        .map_err(|_| "Java didn't respond.".to_string())?
        .map_err(|e| format!("Couldn't run it: {e}"))?;
    let text = String::from_utf8_lossy(&output.stderr).to_string() + &String::from_utf8_lossy(&output.stdout);
    let (version, major) = parse_java_version(&text);
    Ok(JavaInfo { version, major, summary: text.lines().next().unwrap_or_default().to_owned() })
}

/// Checks a custom Java path by running `java -version` and reading the major version.
#[tauri::command]
pub async fn java_validate(path: String) -> CmdResult<Value> {
    let java = inspect_java(&path).await?;
    Ok(json!({ "version": java.version, "major": java.major, "summary": java.summary }))
}

// ---------- version catalog ----------

#[tauri::command]
pub async fn version_catalog(state: State<'_, AppState>) -> CmdResult<Value> {
    let cache_path = state.paths.cache().join("catalog.json");
    let fetched = async {
        let manifest = get_json(&state.http, MANIFEST_URL, "Mojang version list").await?;
        let fabric_games = get_json(&state.http, "https://meta.fabricmc.net/v2/versions/game", "Fabric version list").await?;
        let loaders = get_json(&state.http, "https://meta.fabricmc.net/v2/versions/loader", "Fabric loader list").await?;
        let stable_loader = loaders
            .as_array()
            .and_then(|l| l.iter().find(|x| x["stable"].as_bool() == Some(true)))
            .and_then(|x| x["version"].as_str())
            .map(str::to_owned);
        let versions: Vec<Value> = SUPPORTED_VERSIONS
            .iter()
            .map(|id| {
                let entry = manifest["versions"]
                    .as_array()
                    .and_then(|v| v.iter().find(|x| x["id"].as_str() == Some(id)));
                // Official Fabric's meta doesn't list 1.8.9; Flint gets its Fabric from Ornithe's (see game.rs).
                let fabric = *id == "1.8.9"
                    || fabric_games.as_array().is_some_and(|g| g.iter().any(|x| x["version"].as_str() == Some(id)));
                json!({
                    "id": id,
                    "available": entry.is_some(),
                    "releaseTime": entry.and_then(|e| e["releaseTime"].as_str()),
                    "fabric": fabric,
                })
            })
            .collect();
        Ok::<Value, String>(json!({
            "versions": versions,
            "latestRelease": manifest["latest"]["release"],
            "fabricLoader": stable_loader,
            "checkedAt": now_secs(),
            "offline": false,
        }))
    }
    .await;
    match fetched {
        Ok(value) => {
            let _ = write_json(&cache_path, &value);
            Ok(value)
        }
        Err(error) => match read_json_opt::<Value>(&cache_path) {
            Some(mut cached) => {
                cached["offline"] = json!(true);
                Ok(cached)
            }
            None => Err(error),
        },
    }
}

// ---------- skins ----------

/// How long a cached skin counts as current; an older one is still shown at once while a fresh one is fetched.
const SKIN_FRESH: Duration = Duration::from_secs(6 * 3600);
/** A skin lookup gets this long in all (Mojang's profile, then the texture): Mojang's session server answered in 6–8
 * seconds about one request in three when it was measured (2026-11-03), and the app's general limit is 45 seconds. */
const SKIN_LOOKUP_LIMIT: Duration = Duration::from_secs(12);

/// Skins being fetched in the background right now, so a refresh is never started twice for one player.
fn refreshing() -> &'static std::sync::Mutex<std::collections::HashSet<String>> {
    static SET: std::sync::OnceLock<std::sync::Mutex<std::collections::HashSet<String>>> = std::sync::OnceLock::new();
    SET.get_or_init(|| std::sync::Mutex::new(std::collections::HashSet::new()))
}

/// A player's skin as the web view shows it: the cached texture (and cape) as data URLs and the arm model.
fn skin_json(bytes: &[u8], model: &str, cape: Option<&[u8]>) -> Value {
    json!({
        "dataUrl": format!("data:image/png;base64,{}", STANDARD.encode(bytes)),
        "capeUrl": cape.map(|c| format!("data:image/png;base64,{}", STANDARD.encode(c))),
        "model": model,
    })
}

/// The skin (and cape) Mojang's session server gives for `uuid`, within [`SKIN_LOOKUP_LIMIT`].
async fn fetch_skin(http: &reqwest::Client, uuid: &str) -> CmdResult<(Vec<u8>, &'static str, Option<Vec<u8>>)> {
    let lookup = async {
        let profile = get_json(http, &format!("https://sessionserver.mojang.com/session/minecraft/profile/{uuid}"), "Skin lookup").await?;
        let encoded = profile["properties"]
            .as_array()
            .and_then(|p| p.iter().find(|x| x["name"] == "textures"))
            .and_then(|x| x["value"].as_str())
            .ok_or("No textures")?;
        let textures: Value = serde_json::from_slice(&STANDARD.decode(encoded).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
        let skin = &textures["textures"]["SKIN"];
        let url = skin["url"].as_str().ok_or("No skin")?.replace("http://", "https://");
        let parsed = url::Url::parse(&url).map_err(|e| e.to_string())?;
        if parsed.host_str() != Some("textures.minecraft.net") {
            return Err("Unexpected skin host".to_string());
        }
        let model = if skin["metadata"]["model"].as_str() == Some("slim") { "slim" } else { "classic" };
        let bytes = texture_png(http, parsed).await?;
        let cape = match textures["textures"]["CAPE"]["url"].as_str() {
            Some(cape_url) => match url::Url::parse(&cape_url.replace("http://", "https://")) {
                Ok(u) if u.host_str() == Some("textures.minecraft.net") => texture_png(http, u).await.ok(),
                _ => None,
            },
            None => None,
        };
        Ok((bytes, model, cape))
    };
    tokio::time::timeout(SKIN_LOOKUP_LIMIT, lookup).await.map_err(|_| "Skin lookup: Mojang didn't answer in time".to_string())?
}

/// Writes a fetched skin to the cache; true when it differs from what was cached (a new skin, model or cape).
fn store_skin(dir: &std::path::Path, uuid: &str, bytes: &[u8], model: &str, cape: Option<&[u8]>) -> bool {
    let file = dir.join(format!("{uuid}.png"));
    let meta_file = dir.join(format!("{uuid}.json"));
    let cape_file = dir.join(format!("{uuid}-cape.png"));
    let changed = std::fs::read(&file).ok().as_deref() != Some(bytes)
        || read_json_opt::<Value>(&meta_file).and_then(|m| m["model"].as_str().map(str::to_owned)).as_deref() != Some(model)
        || std::fs::read(&cape_file).ok().as_deref() != cape;
    let _ = std::fs::create_dir_all(dir);
    let _ = std::fs::write(&file, bytes);
    match cape {
        Some(c) => {
            let _ = std::fs::write(&cape_file, c);
        }
        None => {
            let _ = std::fs::remove_file(&cape_file);
        }
    }
    let _ = write_json(&meta_file, &json!({ "model": model }));
    changed
}

/// Asks Mojang's session server for `uuid`'s current skin and cape in the background (once at a time per player) and,
/// if anything changed, caches it and tells the web view ("skin-updated"), which asks for the skin again.
fn refresh_skin_in_background(app: AppHandle, http: reqwest::Client, dir: std::path::PathBuf, uuid: String) {
    if !refreshing().lock().unwrap().insert(uuid.clone()) {
        return;
    }
    tauri::async_runtime::spawn(async move {
        if let Ok((bytes, model, cape)) = fetch_skin(&http, &uuid).await {
            if store_skin(&dir, &uuid, &bytes, model, cape.as_deref()) {
                let _ = app.emit("skin-updated", json!({ "uuid": uuid }));
            }
        }
        refreshing().lock().unwrap().remove(&uuid);
    });
}

/// A player's skin for heads, the Home character and the Skins page. A cached skin is answered at once, whatever its
/// age, so heads never wait on Mojang; one older than [`SKIN_FRESH`] is fetched again in the background, and if it
/// changed the web view hears "skin-updated" and asks again. Only a player with no cached skin waits for the lookup,
/// at most [`SKIN_LOOKUP_LIMIT`]; if that fails the web view retries later.
#[tauri::command]
pub async fn skin_texture(app: AppHandle, state: State<'_, AppState>, uuid: String) -> CmdResult<Value> {
    if uuid.len() != 32 || !uuid.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("Invalid UUID".into());
    }
    let dir = state.paths.cache().join("skins");
    let file = dir.join(format!("{uuid}.png"));
    let meta_file = dir.join(format!("{uuid}.json"));
    let cape_file = dir.join(format!("{uuid}-cape.png"));

    let accounts = crate::auth::load_accounts(&state);
    let stored = accounts.accounts.iter().find(|a| a.uuid == uuid).cloned();
    if stored.as_ref().is_some_and(|a| a.qa) {
        return Ok(json!({ "dataUrl": null, "model": "classic" }));
    }

    if let Ok(bytes) = std::fs::read(&file) {
        let fresh = std::fs::metadata(&file)
            .and_then(|m| m.modified())
            .ok()
            .and_then(|t| t.elapsed().ok())
            .is_some_and(|age| age < SKIN_FRESH);
        if !fresh {
            refresh_skin_in_background(app, state.http.clone(), dir.clone(), uuid.clone());
        }
        let cached_meta: Value = read_json_opt(&meta_file).unwrap_or_else(|| json!({}));
        let model = cached_meta["model"].as_str().map(str::to_owned).or(stored.and_then(|a| a.skin_model)).unwrap_or_else(|| "classic".to_owned());
        let cape = std::fs::read(&cape_file).ok();
        return Ok(skin_json(&bytes, &model, cape.as_deref()));
    }

    // A signed-in account: its skin's link from sign-in goes straight to Mojang's texture server (a fraction of a
    // second), so its head never waits on the session server, whose answers take from one to over ten seconds. The
    // session server is still asked, in the background, for a changed skin and the cape.
    let account_skin = stored
        .as_ref()
        .and_then(|a| a.skin_url.as_deref())
        .and_then(|u| url::Url::parse(&u.replace("http://", "https://")).ok())
        .filter(|u| u.host_str() == Some("textures.minecraft.net"));
    if let Some(link) = account_skin {
        if let Ok(Ok(bytes)) = tokio::time::timeout(Duration::from_secs(8), texture_png(&state.http, link)).await {
            let model = stored.as_ref().and_then(|a| a.skin_model.clone()).filter(|m| m == "slim").unwrap_or_else(|| "classic".to_owned());
            store_skin(&dir, &uuid, &bytes, &model, None);
            refresh_skin_in_background(app, state.http.clone(), dir, uuid);
            return Ok(skin_json(&bytes, &model, None));
        }
    }

    match fetch_skin(&state.http, &uuid).await {
        Ok((bytes, model, cape)) => {
            store_skin(&dir, &uuid, &bytes, model, cape.as_deref());
            Ok(skin_json(&bytes, model, cape.as_deref()))
        }
        Err(_) => Ok(json!({ "dataUrl": null, "model": stored.and_then(|a| a.skin_model).unwrap_or_else(|| "classic".to_owned()) })),
    }
}

async fn texture_png(http: &reqwest::Client, url: url::Url) -> CmdResult<Vec<u8>> {
    let bytes = http
        .get(url)
        .send()
        .await
        .map_err(|e| e.to_string())?
        .error_for_status()
        .map_err(|e| e.to_string())?
        .bytes()
        .await
        .map_err(|e| e.to_string())?;
    if bytes.len() > 512 * 1024 || !bytes.starts_with(b"\x89PNG") {
        return Err("Invalid texture file".to_string());
    }
    Ok(bytes.to_vec())
}

pub fn forget_skin(state: &AppState, uuid: &str) {
    let dir = state.paths.cache().join("skins");
    for name in [format!("{uuid}.png"), format!("{uuid}.json"), format!("{uuid}-cape.png"), format!("{uuid}-profile.json")] {
        let _ = std::fs::remove_file(dir.join(name));
    }
}

// ---------- news ----------

/// Flint's public release feed (schema 1, newest first, plain text only): app/scripts/release-notes-feed.mjs writes it at
/// release time, promote-update.yml copies it to the fixed `site-data` release, and flintclient.com builds its changelog
/// from the same file. Home's news reads it so a new Flint release shows before this copy of Flint has updated; the notes
/// bundled with this build stay the fallback. Minecraft's own patch notes are not shown (owner, 1.0.1).
const RELEASE_FEED: &str = "https://github.com/IconicDemon/Flint-Client/releases/download/site-data/release-notes.json";

/// How many feed releases Home is given; it shows the newest three of these and its bundled notes together.
const FEED_RELEASES: usize = 6;

/// A plain release version (`1.0.1`): exactly three numbers of one to three digits each.
fn release_version(v: &str) -> Option<(u16, u16, u16)> {
    let parts: Vec<&str> = v.split('.').collect();
    if parts.len() != 3 || !parts.iter().all(|p| (1..=3).contains(&p.len()) && p.bytes().all(|b| b.is_ascii_digit())) {
        return None;
    }
    Some((parts[0].parse().ok()?, parts[1].parse().ok()?, parts[2].parse().ok()?))
}

/// A real calendar day written YYYY-MM-DD.
fn real_day(d: &str) -> bool {
    let b = d.as_bytes();
    if b.len() != 10 || b[4] != b'-' || b[7] != b'-' {
        return false;
    }
    let num = |from: usize, to: usize| d.get(from..to).filter(|s| s.bytes().all(|c| c.is_ascii_digit())).and_then(|s| s.parse::<u32>().ok());
    let (Some(year), Some(month), Some(day)) = (num(0, 4), num(5, 7), num(8, 10)) else {
        return false;
    };
    let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    let days = match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if leap => 29,
        2 => 28,
        _ => 0,
    };
    (2000..=2999).contains(&year) && (1..=days).contains(&day)
}

/// A feed text Home may show: at most `max` characters and no control characters (it is only ever shown as plain text).
fn feed_text(value: &Value, max: usize) -> Option<String> {
    let text = value.as_str()?.trim();
    (!text.is_empty() && text.chars().count() <= max && !text.chars().any(char::is_control)).then(|| text.to_string())
}

/// The releases Home may show from Flint's feed: schema 1 only; each entry checked field by field and left out, never
/// repaired, when any check fails; newest first, one per version, at most FEED_RELEASES. `page_url` is passed on only when
/// it is exactly that version's page on flintclient.com (the one address the site serves a version at).
pub fn feed_releases(feed: &Value) -> Vec<Value> {
    if feed["schema"].as_u64() != Some(1) {
        return Vec::new();
    }
    let mut releases: Vec<((u16, u16, u16), Value)> = feed["versions"]
        .as_array()
        .map(|list| {
            list.iter()
                .take(500)
                .filter_map(|entry| {
                    let version = entry["version"].as_str()?;
                    let order = release_version(version)?;
                    let date = entry["date"].as_str().filter(|d| real_day(d))?;
                    let title = feed_text(&entry["title"], 120)?;
                    let summary = match &entry["summary"] {
                        Value::Null => String::new(),
                        Value::String(s) if s.trim().is_empty() => String::new(),
                        other => feed_text(other, 400)?,
                    };
                    let page = format!("https://flintclient.com/releases/{version}/");
                    let page_url = (entry["page_url"].as_str() == Some(page.as_str())).then_some(page);
                    Some((order, json!({ "version": version, "date": date, "title": title, "summary": summary, "page_url": page_url })))
                })
                .collect()
        })
        .unwrap_or_default();
    releases.sort_by_key(|r| std::cmp::Reverse(r.0));
    releases.dedup_by(|a, b| a.0 == b.0);
    releases.into_iter().take(FEED_RELEASES).map(|(_, release)| release).collect()
}

#[tauri::command]
pub async fn news_feed(state: State<'_, AppState>) -> CmdResult<Value> {
    // Earlier builds kept Minecraft's patch notes in this file: a cache that isn't Flint's own feed is never shown.
    let cache_path = state.paths.cache().join("news.json");
    let cached = || read_json_opt::<Value>(&cache_path).filter(|c| c["source"] == "flint");
    let offline = |error: String| match cached() {
        Some(mut cache) => {
            cache["offline"] = json!(true);
            Ok(cache)
        }
        None => Err(error),
    };
    match get_json(&state.http, RELEASE_FEED, "Flint news").await {
        Ok(feed) => {
            let entries = feed_releases(&feed);
            if entries.is_empty() {
                // A feed with nothing usable never replaces a good saved one.
                return offline("Flint news: the release feed had no usable releases".to_string());
            }
            let out = json!({ "source": "flint", "entries": entries, "offline": false });
            let _ = write_json(&cache_path, &out);
            Ok(out)
        }
        Err(error) => offline(error),
    }
}

#[tauri::command]
pub fn session_summary(state: State<'_, AppState>) -> Value {
    json!({
        "account": active_account(&state),
        "profiles": load_profiles(&state),
    })
}

pub fn app_data_root(app: &AppHandle) -> std::path::PathBuf {
    if let Ok(dir) = std::env::var("FLINT_DATA_DIR") {
        let path = std::path::PathBuf::from(dir);
        if path.is_absolute() {
            return path;
        }
    }
    app.path()
        .app_data_dir()
        .expect("an application data directory is available")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Live (network): Flint's own skin lookup against Mojang, timed. `cargo test -- --ignored skin_lookup_live`;
    /// FLINT_SKIN_TEST_UUID picks the player (default: Notch).
    #[tokio::test]
    #[ignore]
    async fn skin_lookup_live() {
        let uuid = std::env::var("FLINT_SKIN_TEST_UUID").unwrap_or_else(|_| "069a79f444e94726a5befca90e38aaf5".to_owned());
        let (http, _) = crate::core::build_clients();
        let started = std::time::Instant::now();
        let result = fetch_skin(&http, &uuid).await;
        eprintln!("skin lookup: {:?} in {} ms", result.as_ref().map(|(b, m, c)| (b.len(), *m, c.as_ref().map(Vec::len))), started.elapsed().as_millis());
        assert!(result.is_ok(), "{result:?}");
    }

    #[test]
    fn a_refreshed_skin_tells_the_window_only_when_something_changed() {
        let dir = std::env::temp_dir().join(format!("flint-skin-test-{}", crate::core::random_hex(6)));
        let uuid = "069a79f444e94726a5befca90e38aaf5";
        let skin = b"\x89PNG skin one";
        assert!(store_skin(&dir, uuid, skin, "classic", None), "the first skin is new");
        assert!(!store_skin(&dir, uuid, skin, "classic", None), "the same skin again changes nothing");
        assert!(store_skin(&dir, uuid, skin, "slim", None), "a new arm model is a change");
        assert!(store_skin(&dir, uuid, skin, "slim", Some(b"\x89PNG cape")), "a new cape is a change");
        assert!(!store_skin(&dir, uuid, skin, "slim", Some(b"\x89PNG cape")));
        assert!(store_skin(&dir, uuid, skin, "slim", None), "a removed cape is a change");
        assert!(store_skin(&dir, uuid, b"\x89PNG skin two", "slim", None), "a new skin is a change");
        assert_eq!(std::fs::read(dir.join(format!("{uuid}.png"))).unwrap(), b"\x89PNG skin two");
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn feed(versions: Value) -> Value {
        json!({ "schema": 1, "generated_at": "2026-11-08T12:00:00Z", "versions": versions })
    }

    fn release(version: &str, date: &str) -> Value {
        json!({ "version": version, "date": date, "title": "Title", "summary": "Summary.", "points": ["A point"], "release_url": "https://github.com/IconicDemon/Flint-Client/releases/tag/v1.0.1", "page_url": format!("https://flintclient.com/releases/{version}/") })
    }

    #[test]
    fn news_reads_flints_own_release_feed_newest_first() {
        let shown = feed_releases(&feed(json!([release("0.9.1", "2026-11-04"), release("1.0.1", "2026-11-08"), release("0.10.0", "2026-11-06"), release("1.0.1", "2026-11-08")])));
        let versions: Vec<&str> = shown.iter().map(|r| r["version"].as_str().unwrap()).collect();
        assert_eq!(versions, ["1.0.1", "0.10.0", "0.9.1"], "by version number, not text, one per version");
        assert_eq!(shown[0], json!({ "version": "1.0.1", "date": "2026-11-08", "title": "Title", "summary": "Summary.", "page_url": "https://flintclient.com/releases/1.0.1/" }), "only what Home shows");
        let many: Vec<Value> = (0..20).map(|n| release(&format!("1.0.{n}"), "2026-11-08")).collect();
        assert_eq!(feed_releases(&feed(json!(many))).len(), FEED_RELEASES);
    }

    #[test]
    fn news_leaves_out_any_release_that_fails_a_check() {
        assert!(feed_releases(&json!({ "schema": 2, "versions": [release("1.0.1", "2026-11-08")] })).is_empty(), "another schema");
        assert!(feed_releases(&json!({ "schema": 1 })).is_empty());
        assert!(feed_releases(&json!([release("1.0.1", "2026-11-08")])).is_empty());
        for version in ["1.0", "1.0.1.2", "1.0.1-beta", "../1.0.1", "1.0.1/", "1000.0.1", "", " 1.0.1"] {
            assert!(feed_releases(&feed(json!([release(version, "2026-11-08")]))).is_empty(), "version {version:?}");
        }
        for date in ["2026-02-30", "2026-13-01", "2026-11-8", "08-11-2026", "2026-11-08T00:00:00Z", "1999-12-31"] {
            assert!(feed_releases(&feed(json!([release("1.0.1", date)]))).is_empty(), "date {date:?}");
        }
        assert_eq!(feed_releases(&feed(json!([release("1.0.1", "2028-02-29")]))).len(), 1, "a leap day is real");
        let mut long = release("1.0.1", "2026-11-08");
        long["title"] = json!("x".repeat(121));
        let mut control = release("1.0.1", "2026-11-08");
        control["summary"] = json!("A line\u{0007}with a bell");
        let mut untitled = release("1.0.1", "2026-11-08");
        untitled["title"] = json!("  ");
        let mut not_text = release("1.0.1", "2026-11-08");
        not_text["summary"] = json!({ "html": "<b>x</b>" });
        for bad in [long, control, untitled, not_text] {
            assert!(feed_releases(&feed(json!([bad]))).is_empty(), "{bad}");
        }
        let mut no_summary = release("1.0.1", "2026-11-08");
        no_summary["summary"] = Value::Null;
        assert_eq!(feed_releases(&feed(json!([no_summary])))[0]["summary"], "", "a missing summary is just empty");
    }

    #[test]
    fn news_opens_only_the_versions_own_page() {
        for (page, kept) in [
            (json!("https://flintclient.com/releases/1.0.1/"), true),
            (json!("https://flintclient.com/releases/0.9.1/"), false),
            (json!("https://flintclient.com/releases/1.0.1"), false),
            (json!("http://flintclient.com/releases/1.0.1/"), false),
            (json!("https://flintclient.com.evil.example/releases/1.0.1/"), false),
            (Value::Null, false),
        ] {
            let mut entry = release("1.0.1", "2026-11-08");
            entry["page_url"] = page.clone();
            let shown = feed_releases(&feed(json!([entry])));
            assert_eq!(shown.len(), 1, "a release without its page is still news");
            assert_eq!(shown[0]["page_url"].is_string(), kept, "{page}");
        }
    }

    #[test]
    fn ai_gateway_must_be_https_or_this_computer() {
        assert!(valid_gateway("https://flint-ai-gateway.example.workers.dev"));
        assert!(valid_gateway("http://127.0.0.1:8787"));
        assert!(valid_gateway("http://localhost:8787/"));
        assert!(!valid_gateway("http://flint-ai-gateway.example.workers.dev"));
        assert!(!valid_gateway("http://localhost.example.com"), "a look-alike host is not this computer");
        assert!(!valid_gateway("https://user:pass@example.com"));
        assert!(!valid_gateway("ftp://example.com"));
        assert!(!valid_gateway("not a url"));
    }

    #[test]
    fn parses_java_version_output() {
        assert_eq!(parse_java_version("openjdk version \"25.0.3\" 2026-04-21 LTS"), ("25.0.3".into(), Some(25)));
        assert_eq!(parse_java_version("java version \"1.8.0_402\"\nJava(TM) SE Runtime"), ("1.8.0_402".into(), Some(8)));
        assert_eq!(parse_java_version("openjdk version \"21-ea\""), ("21-ea".into(), Some(21)));
        assert_eq!(parse_java_version("garbage").1, None);
    }

    #[test]
    fn profile_names_and_versions_are_validated() {
        assert_eq!(clean_name("  My\u{7}World  ").unwrap(), "MyWorld");
        assert!(clean_name("   ").is_err());
        assert_eq!(clean_name(&"x".repeat(80)).unwrap().len(), 40);
        assert!(validate_version("26.3", "fabric").is_ok());
        assert!(validate_version("1.8.9", "vanilla").is_ok());
        assert!(validate_version("1.8.9", "fabric").is_ok(), "Fabric from Ornithe");
        assert!(validate_version("1.12.2", "vanilla").is_err());
        assert!(validate_version("26.3", "forge").is_err());
    }

    #[test]
    fn profile_memory_defaults_to_none_and_round_trips() {
        let old: Profile = serde_json::from_value(json!({ "id": "a", "name": "A", "gameVersion": "26.3", "loader": "fabric" })).unwrap();
        assert_eq!(old.memory_mb, None, "profiles saved before the override existed use the default");
        assert!(serde_json::to_value(&old).unwrap().get("memoryMb").is_none(), "no key is written when unset");
        let custom = Profile { memory_mb: Some(6144), ..old };
        let value = serde_json::to_value(&custom).unwrap();
        assert_eq!(value["memoryMb"], 6144);
        assert_eq!(serde_json::from_value::<Profile>(value).unwrap().memory_mb, Some(6144));
    }

    #[test]
    fn profile_memory_validation() {
        assert_eq!(validate_profile_memory(None, 8192).unwrap(), None);
        assert_eq!(validate_profile_memory(Some(1024), 8192).unwrap(), Some(1024));
        assert_eq!(validate_profile_memory(Some(6400), 8192).unwrap(), Some(6400));
        assert_eq!(validate_profile_memory(Some(8192), 8192).unwrap(), Some(8192));
        assert!(validate_profile_memory(Some(768), 8192).is_err(), "below 1 GB");
        assert!(validate_profile_memory(Some(8448), 8192).is_err(), "above what the computer can spare");
        assert!(validate_profile_memory(Some(4100), 8192).is_err(), "not a 256 MB step");
    }

    #[test]
    fn launch_prefers_the_profile_memory_over_the_global_setting() {
        let mut profile = Profile::default();
        assert_eq!(launch_memory_mb(&profile, Some(3072), 8192), 3072, "no override uses Settings");
        assert_eq!(launch_memory_mb(&profile, None, 8192), 4096, "missing setting falls back to 4 GB");
        profile.memory_mb = Some(6144);
        assert_eq!(launch_memory_mb(&profile, Some(3072), 8192), 6144);
        assert_eq!(launch_memory_mb(&profile, Some(3072), 4096), 4096, "never above the computer's limit");
        profile.memory_mb = Some(512);
        assert_eq!(launch_memory_mb(&profile, Some(3072), 8192), 1024);
    }

    #[test]
    fn memory_limits_follow_the_computer_and_clamp_what_was_saved() {
        // An 8 GB PC (Windows can use about 7.8 GB of it): it says 8 GB and offers at most 5.5 GB.
        let eight = memory_limits(Some(Memory { installed_mb: 8192, usable_mb: 7990 }));
        assert_eq!(eight, MemoryLimits { total_mb: Some(8192), max_mb: 5632, recommended_mb: 3072, warn_mb: Some(4608) });
        // A 16 GB allocation saved earlier (or on another computer) launches with what this one can spare.
        let mut profile = Profile::default();
        assert_eq!(launch_memory_mb(&profile, Some(16384), eight.max_mb), 5632);
        profile.memory_mb = Some(12288);
        assert_eq!(launch_memory_mb(&profile, Some(3072), eight.max_mb), 5632);

        let sixteen = memory_limits(Some(Memory { installed_mb: 16384, usable_mb: 16200 }));
        assert_eq!((sixteen.total_mb, sixteen.max_mb, sixteen.recommended_mb), (Some(16384), 11776, 4096));
        assert_eq!(memory_limits(Some(Memory { installed_mb: 65536, usable_mb: 65400 })).max_mb, 32768, "never above 32 GB");
        let four = memory_limits(Some(Memory { installed_mb: 4096, usable_mb: 3900 }));
        assert_eq!((four.max_mb, four.recommended_mb), (2560, 2560), "the suggestion never exceeds the limit");
        assert_eq!(memory_limits(Some(Memory { installed_mb: 1024, usable_mb: 900 })).max_mb, 1024, "the slider's floor");
        // Unknown memory: a ceiling safe on small computers rather than a guess of 16 GB.
        let unknown = memory_limits(None);
        assert_eq!((unknown.total_mb, unknown.max_mb, unknown.warn_mb), (None, 4096, None));
        // The size Settings shows is read from this computer.
        if let Some(memory) = memory() {
            assert!(memory.installed_mb >= memory.usable_mb && memory.usable_mb > 0);
        }
    }

    #[test]
    fn an_imported_profile_keeps_its_origin_but_a_copy_does_not() {
        let origin = crate::import::ImportOrigin { launcher: "prism".into(), key: "a1b2c3".into(), imported_at: 1_700_000_000 };
        let imported = Profile { id: "aaaaaaaaaaaa".into(), name: "PvP".into(), game_version: "26.3".into(), loader: "fabric".into(), origin: Some(origin.clone()), ..Default::default() };
        let value = serde_json::to_value(&imported).unwrap();
        assert_eq!(value["origin"], json!({ "launcher": "prism", "key": "a1b2c3", "importedAt": 1_700_000_000u64 }));
        let read: Profile = serde_json::from_value(value).unwrap();
        assert_eq!(read.origin, Some(origin));
        assert!(serde_json::to_value(Profile::default()).unwrap().get("origin").is_none(), "absent when not imported");

        let copy = duplicate_of(imported, "bbbbbbbbbbbb".into(), "PvP copy".into(), None);
        assert_eq!(copy.origin, None, "a copy isn't the import");
        assert_eq!((copy.game_version.as_str(), copy.loader.as_str(), copy.name.as_str()), ("26.3", "fabric", "PvP copy"));

        // A damaged or future origin never makes profiles.json unreadable: the profile loads without it.
        for odd in [json!("text"), json!({ "launcher": 7 }), json!(null), json!({ "key": "k" })] {
            let file: ProfilesFile = serde_json::from_value(json!({ "profiles": [{ "id": "p", "name": "P", "gameVersion": "26.3", "loader": "fabric", "origin": odd }] })).unwrap();
            assert_eq!(file.profiles.len(), 1);
            assert_eq!(file.profiles[0].origin, None, "{odd}");
        }
    }
}
