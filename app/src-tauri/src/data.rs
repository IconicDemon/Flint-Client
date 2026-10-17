use crate::auth::active_account;
use crate::core::*;
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::time::Duration;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_autostart::ManagerExt;
use tauri_plugin_opener::OpenerExt;

pub const SUPPORTED_VERSIONS: [&str; 9] =
    ["26.3", "26.2", "26.1.2", "26.1.1", "26.1", "1.21.11", "1.21.10", "1.21.1", "1.8.9"];
pub const DISCORD_INVITE: &str = "https://discord.gg/59rAHZMWb7";
/// Built-in address of Flint's AI gateway, the production Cloudflare Worker (Mojang-verified players
/// only; no key or secret in the app). Settings ("aiGatewayUrl") or FLINT_AI_GATEWAY_URL can point a
/// build elsewhere, e.g. at staging for tests.
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

pub fn gateway_url(state: &AppState) -> Option<String> {
    let settings = load_settings(state);
    settings["aiGatewayUrl"]
        .as_str()
        .map(str::trim)
        .filter(|u| !u.is_empty())
        .map(str::to_owned)
        .or_else(|| std::env::var("FLINT_AI_GATEWAY_URL").ok())
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
        gateway_configured: gateway_url(&state).is_some(),
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

/// Total size of a file or folder. Symlinks are counted as links, never followed.
fn disk_size(path: &std::path::Path) -> u64 {
    let Ok(meta) = std::fs::symlink_metadata(path) else { return 0 };
    if meta.is_dir() {
        std::fs::read_dir(path)
            .map(|entries| entries.flatten().map(|e| disk_size(&e.path())).sum())
            .unwrap_or(0)
    } else {
        meta.len()
    }
}

/// Runs blocking file-system work on a background thread, so a large walk can't freeze the window.
pub async fn blocking<T: Send + 'static>(app: &AppHandle, work: impl FnOnce(&AppState) -> T + Send + 'static) -> CmdResult<T> {
    let app = app.clone();
    tokio::task::spawn_blocking(move || work(&app.state::<AppState>()))
        .await
        .map_err(|e| format!("Background task failed: {e}"))
}

#[tauri::command]
pub async fn storage_usage(app: AppHandle) -> CmdResult<Value> {
    blocking(&app, |state| {
        json!({
            "game": disk_size(&state.paths.game()),
            "runtimes": disk_size(&state.paths.runtimes()),
            "profiles": disk_size(&state.paths.root.join("profiles")),
            "cache": disk_size(&state.paths.cache()),
        })
    })
    .await
}

/// The only files Clear Cache may remove. All of them are re-downloaded on demand; worlds, mods,
/// settings, accounts and installed game files are never in this list.
const CACHE_ENTRIES: [&str; 3] = ["skins", "news.json", "catalog.json"];

/// Removes the cached entries under `cache`, continuing past failures, and reports exactly what
/// happened: bytes freed and anything that couldn't be removed.
fn clear_cache_dir(cache: &std::path::Path) -> Value {
    let mut removed = 0u64;
    let mut failed = Vec::new();
    for name in CACHE_ENTRIES {
        let path = cache.join(name);
        let Ok(meta) = std::fs::symlink_metadata(&path) else { continue };
        let size = disk_size(&path);
        let result = if meta.is_dir() { std::fs::remove_dir_all(&path) } else { std::fs::remove_file(&path) };
        match result {
            Ok(()) => removed += size,
            Err(error) => failed.push(json!({ "name": name, "error": error.to_string() })),
        }
    }
    json!({ "removedBytes": removed, "failed": failed })
}

#[tauri::command]
pub async fn cache_clear(app: AppHandle) -> CmdResult<Value> {
    blocking(&app, |state| clear_cache_dir(&state.paths.cache())).await
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

fn validate_version(version: &str, loader: &str) -> CmdResult<()> {
    if !SUPPORTED_VERSIONS.contains(&version) {
        return Err("That Minecraft version isn't offered in Flint yet.".into());
    }
    match loader {
        "vanilla" => Ok(()),
        "fabric" if version == "1.8.9" => Err("Official Fabric doesn't support Minecraft 1.8.9.".into()),
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
    let profile = Profile {
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
    };
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
    update_profile(&state, &id, |p| p.name = name).await
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
    let to = state.paths.profile_dir(&new_id);
    let staging = to.with_file_name(format!("{new_id}.flint-importing"));
    let (from_c, staging_c) = (from.clone(), staging.clone());
    let copied = tokio::task::spawn_blocking(move || copy_tree(&from_c, &staging_c, 0)).await.map_err(|e| e.to_string())?;
    if let Err(e) = copied {
        let _ = std::fs::remove_dir_all(&staging);
        return Err(format!("Could not copy the profile: {e}"));
    }
    std::fs::rename(&staging, &to).map_err(err("Could not finish the copy"))?;
    let profile = Profile {
        id: new_id,
        name,
        created_at: now_secs(),
        last_played: None,
        ..source
    };
    let _guard = state.file_lock.lock().await;
    let mut file = load_profiles(&state);
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
    let mut file = load_profiles(&state);
    file.profiles.retain(|p| p.id != id);
    if file.selected.as_deref() == Some(id.as_str()) {
        file.selected = file.profiles.first().map(|p| p.id.clone());
    }
    save_profiles(&state, &file)?;
    let dir = state.paths.profile_dir(&id);
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

/// Largest heap Flint will hand Minecraft: three quarters of physical RAM, so the system keeps room to breathe.
pub fn max_memory_mb() -> u64 {
    total_memory().map(|b| (b / (1024 * 1024) * 3 / 4) / 512 * 512).unwrap_or(16384).clamp(2048, 32768)
}

#[tauri::command]
pub async fn system_info() -> Value {
    // Reading total memory starts a short-lived system process; keep it off the main thread.
    tokio::task::spawn_blocking(system_summary).await.unwrap_or_default()
}

fn system_summary() -> Value {
    json!({
        "totalMemory": total_memory(),
        "cpus": std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1),
        "os": std::env::consts::OS,
        "arch": std::env::consts::ARCH,
    })
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

/// Physical memory in bytes.
pub fn total_memory() -> Option<u64> {
    if cfg!(target_os = "macos") {
        sysctl(&["hw.memsize"]).and_then(|s| s.trim().parse::<u64>().ok())
    } else if cfg!(target_os = "windows") {
        let mut command = std::process::Command::new("powershell");
        command.args(["-NoProfile", "-Command", "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory"]);
        // No console window flashing up while the launcher reads the memory size.
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x0800_0000);
        }
        command
            .output()
            .ok()
            .and_then(|o| String::from_utf8(o.stdout).ok())
            .and_then(|s| s.trim().parse::<u64>().ok())
    } else {
        std::fs::read_to_string("/proc/meminfo")
            .ok()
            .and_then(|s| s.lines().find(|l| l.starts_with("MemTotal")).and_then(|l| l.split_whitespace().nth(1)).and_then(|kb| kb.parse::<u64>().ok()))
            .map(|kb| kb * 1024)
    }
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
                let fabric = fabric_games
                    .as_array()
                    .is_some_and(|g| g.iter().any(|x| x["version"].as_str() == Some(id)));
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

#[tauri::command]
pub async fn skin_texture(state: State<'_, AppState>, uuid: String) -> CmdResult<Value> {
    if uuid.len() != 32 || !uuid.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("Invalid UUID".into());
    }
    let dir = state.paths.cache().join("skins");
    let file = dir.join(format!("{uuid}.png"));
    let meta_file = dir.join(format!("{uuid}.json"));
    let cape_file = dir.join(format!("{uuid}-cape.png"));
    let fresh = std::fs::metadata(&file)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.elapsed().ok())
        .is_some_and(|age| age < Duration::from_secs(6 * 3600));
    let cached_meta: Value = read_json_opt(&meta_file).unwrap_or_else(|| json!({}));

    let accounts = crate::auth::load_accounts(&state);
    let stored = accounts.accounts.iter().find(|a| a.uuid == uuid).cloned();
    if stored.as_ref().is_some_and(|a| a.qa) {
        return Ok(json!({ "dataUrl": null, "model": "classic" }));
    }

    if !fresh {
        let lookup = async {
            let profile = get_json(
                &state.http,
                &format!("https://sessionserver.mojang.com/session/minecraft/profile/{uuid}"),
                "Skin lookup",
            )
            .await?;
            let encoded = profile["properties"]
                .as_array()
                .and_then(|p| p.iter().find(|x| x["name"] == "textures"))
                .and_then(|x| x["value"].as_str())
                .ok_or("No textures")?;
            let textures: Value = serde_json::from_slice(&STANDARD.decode(encoded).map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
            let skin = &textures["textures"]["SKIN"];
            let url = skin["url"].as_str().ok_or("No skin")?.replace("http://", "https://");
            let parsed = url::Url::parse(&url).map_err(|e| e.to_string())?;
            if parsed.host_str() != Some("textures.minecraft.net") {
                return Err("Unexpected skin host".to_string());
            }
            let model = if skin["metadata"]["model"].as_str() == Some("slim") { "slim" } else { "classic" };
            let bytes = texture_png(&state, parsed).await?;
            let cape = match textures["textures"]["CAPE"]["url"].as_str() {
                Some(cape_url) => match url::Url::parse(&cape_url.replace("http://", "https://")) {
                    Ok(u) if u.host_str() == Some("textures.minecraft.net") => texture_png(&state, u).await.ok(),
                    _ => None,
                },
                None => None,
            };
            Ok::<(Vec<u8>, &str, Option<Vec<u8>>), String>((bytes, model, cape))
        }
        .await;
        if let Ok((bytes, model, cape)) = lookup {
            let _ = std::fs::create_dir_all(&dir);
            let _ = std::fs::write(&file, &bytes);
            match &cape {
                Some(c) => {
                    let _ = std::fs::write(&cape_file, c);
                }
                None => {
                    let _ = std::fs::remove_file(&cape_file);
                }
            }
            let _ = write_json(&meta_file, &json!({ "model": model }));
            return Ok(json!({
                "dataUrl": format!("data:image/png;base64,{}", STANDARD.encode(&bytes)),
                "capeUrl": cape.map(|c| format!("data:image/png;base64,{}", STANDARD.encode(c))),
                "model": model,
            }));
        }
    }
    let cape = std::fs::read(&cape_file).ok().map(|c| format!("data:image/png;base64,{}", STANDARD.encode(c)));
    match std::fs::read(&file) {
        Ok(bytes) => Ok(json!({
            "dataUrl": format!("data:image/png;base64,{}", STANDARD.encode(&bytes)),
            "capeUrl": cape,
            "model": cached_meta["model"].as_str().or(stored.and_then(|a| a.skin_model).as_deref()).unwrap_or("classic"),
        })),
        Err(_) => Ok(json!({ "dataUrl": null, "model": "classic" })),
    }
}

async fn texture_png(state: &AppState, url: url::Url) -> CmdResult<Vec<u8>> {
    let bytes = state
        .http
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

#[tauri::command]
pub async fn news_feed(state: State<'_, AppState>) -> CmdResult<Value> {
    let cache_path = state.paths.cache().join("news.json");
    let fetched = get_json(&state.http, "https://launchercontent.mojang.com/v2/javaPatchNotes.json", "Patch notes").await;
    match fetched {
        Ok(value) => {
            let entries: Vec<Value> = value["entries"]
                .as_array()
                .map(|entries| {
                    entries
                        .iter()
                        .filter(|e| e["type"].as_str() == Some("release"))
                        .take(4)
                        .map(|e| {
                            json!({
                                "id": e["id"],
                                "title": e["title"],
                                "version": e["version"],
                                "date": e["date"],
                                "summary": e["shortText"],
                                "image": e["image"]["url"].as_str().map(|u| format!("https://launchercontent.mojang.com{u}")),
                            })
                        })
                        .collect()
                })
                .unwrap_or_default();
            let out = json!({ "entries": entries, "offline": false });
            let _ = write_json(&cache_path, &out);
            Ok(out)
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
    use crate::core::TestDir;

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
    fn clear_cache_removes_only_cache_entries_and_reports_them() {
        let dir = TestDir::new();
        let cache = dir.0.join("cache");
        std::fs::create_dir_all(cache.join("skins")).unwrap();
        std::fs::write(cache.join("skins/a.png"), vec![0u8; 100]).unwrap();
        std::fs::write(cache.join("news.json"), "{}").unwrap();
        std::fs::write(cache.join("keep-me.json"), "{}").unwrap();
        std::fs::create_dir_all(dir.0.join("profiles/p/saves/World")).unwrap();
        std::fs::write(dir.0.join("profiles/p/saves/World/level.dat"), "w").unwrap();

        let result = clear_cache_dir(&cache);
        assert_eq!(result["removedBytes"], 102);
        assert_eq!(result["failed"], json!([]));
        assert!(!cache.join("skins").exists() && !cache.join("news.json").exists());
        assert!(cache.join("keep-me.json").exists(), "unknown files are left alone");
        assert!(dir.0.join("profiles/p/saves/World/level.dat").exists(), "worlds are never touched");
        // Running again on an already clean cache is a no-op, not an error.
        assert_eq!(clear_cache_dir(&cache)["removedBytes"], 0);
    }

    #[cfg(unix)]
    #[test]
    fn clear_cache_reports_entries_it_could_not_remove() {
        use std::os::unix::fs::PermissionsExt;
        let dir = TestDir::new();
        let cache = dir.0.join("cache");
        std::fs::create_dir_all(cache.join("skins")).unwrap();
        std::fs::write(cache.join("skins/a.png"), "x").unwrap();
        std::fs::set_permissions(cache.join("skins"), std::fs::Permissions::from_mode(0o500)).unwrap();
        let result = clear_cache_dir(&cache);
        std::fs::set_permissions(cache.join("skins"), std::fs::Permissions::from_mode(0o700)).unwrap();
        assert_eq!(result["failed"][0]["name"], "skins", "{result}");
    }

    #[test]
    fn profile_names_and_versions_are_validated() {
        assert_eq!(clean_name("  My\u{7}World  ").unwrap(), "MyWorld");
        assert!(clean_name("   ").is_err());
        assert_eq!(clean_name(&"x".repeat(80)).unwrap().len(), 40);
        assert!(validate_version("26.3", "fabric").is_ok());
        assert!(validate_version("1.8.9", "vanilla").is_ok());
        assert!(validate_version("1.8.9", "fabric").is_err());
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
}
