use crate::auth::{active_account, MICROSOFT_CLIENT_ID};
use crate::core::*;
use crate::data::{find_profile, load_settings, update_profile, Profile};
use crate::perfcore::JvmPolicy;
use futures_util::{StreamExt, TryStreamExt};
use serde_json::{json, Value};
use sha1::{Digest, Sha1};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tauri::{AppHandle, Emitter, State};
use tokio::io::AsyncWriteExt;

const MANIFEST_URL: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const RUNTIME_INDEX: &str = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
const FABRIC_META: &str = "https://meta.fabricmc.net/v2";
/// Exact hostnames only. Older versions (1.8.9 and earlier) still reference launchermeta and
/// launcher for asset indexes, logging configs and Java 8 files.
pub const ALLOWED_HOSTS: [&str; 7] = [
    "piston-data.mojang.com",
    "piston-meta.mojang.com",
    "launchermeta.mojang.com",
    "launcher.mojang.com",
    "libraries.minecraft.net",
    "resources.download.minecraft.net",
    "maven.fabricmc.net",
];

#[derive(Clone)]
struct Download {
    url: String,
    path: PathBuf,
    sha1: Option<String>,
    size: Option<u64>,
    #[cfg_attr(not(unix), allow(dead_code))] // only Unix has an executable bit
    executable: bool,
}

struct Library {
    key: String,
    path: PathBuf,
    native_extract: Option<Vec<String>>,
}

pub struct Installed {
    version: Value,
    fabric: Option<Value>,
    libraries: Vec<Library>,
    client_jar: PathBuf,
    natives_dir: PathBuf,
    java: PathBuf,
    logging_arg: Option<String>,
}

// ---------- state events ----------

/// Orders state updates: `profile_states` returns the same numbers, so the interface can tell
/// whether a state it fetched is older than the events it has already shown.
static STATE_SEQ: AtomicU64 = AtomicU64::new(1);

fn emit(app: &AppHandle, state: &AppState, id: &str, value: Value) {
    let mut value = value;
    value["profileId"] = json!(id);
    // Numbered and emitted under the lock, so events leave in the same order as their numbers.
    let mut states = state.profile_states.lock().unwrap();
    value["seq"] = json!(STATE_SEQ.fetch_add(1, Ordering::Relaxed));
    states.insert(id.to_owned(), value.clone());
    let _ = app.emit("profile-state", value);
}

fn emit_stage(app: &AppHandle, state: &AppState, id: &str, phase: &str, stage: &str, label: &str) {
    emit(app, state, id, json!({ "state": phase, "stage": stage, "label": label, "progress": null }));
}

/// Runs `work` until it finishes or `cancel` is set (checked every 200 ms), so Pause takes effect
/// even while a metadata request is still waiting on the network.
async fn until_cancelled<T>(cancel: &AtomicBool, work: impl std::future::Future<Output = CmdResult<T>>) -> CmdResult<T> {
    tokio::select! {
        result = work => result,
        _ = async {
            while !cancel.load(Ordering::Relaxed) {
                tokio::time::sleep(Duration::from_millis(200)).await;
            }
        } => Err("__cancelled__".into()),
    }
}

/// True while the profile shows a busy state (checking, downloading, installing, launching).
fn shows_busy(state: &AppState, id: &str) -> bool {
    state
        .profile_states
        .lock()
        .unwrap()
        .get(id)
        .and_then(|s| s["state"].as_str())
        .is_some_and(|s| matches!(s, "checking" | "downloading" | "installing" | "launching"))
}

/// Makes sure a failed command never leaves its profile looking busy: when no error state has been
/// shown for this failure yet, shows it (with the same message the command returns).
fn settle(app: &AppHandle, state: &AppState, id: &str, error: String) -> String {
    if shows_busy(state, id) {
        fail(app, state, id, &error)
    } else {
        error
    }
}

fn cancelled(flag: &AtomicBool) -> CmdResult<()> {
    if flag.load(Ordering::Relaxed) {
        Err("__cancelled__".into())
    } else {
        Ok(())
    }
}

// ---------- rules & paths ----------

fn rule_matches(rule: &Value) -> bool {
    if rule.get("features").is_some() {
        return false;
    }
    if let Some(os) = rule.get("os") {
        if let Some(name) = os["name"].as_str() {
            if name != mojang_os() {
                return false;
            }
        }
        if let Some(arch) = os["arch"].as_str() {
            let current = if std::env::consts::ARCH == "x86" { "x86" } else { std::env::consts::ARCH };
            if arch != current {
                return false;
            }
        }
    }
    true
}

fn rules_allow(rules: Option<&Value>) -> bool {
    let Some(rules) = rules.and_then(Value::as_array) else { return true };
    let mut allowed = false;
    for rule in rules {
        if rule_matches(rule) {
            allowed = rule["action"].as_str() == Some("allow");
        }
    }
    allowed
}

fn maven_path(name: &str) -> Option<String> {
    let (coords, ext) = name.split_once('@').unwrap_or((name, "jar"));
    let parts: Vec<&str> = coords.split(':').collect();
    if parts.len() < 3 {
        return None;
    }
    let (group, artifact, version) = (parts[0], parts[1], parts[2]);
    let classifier = parts.get(3).map(|c| format!("-{c}")).unwrap_or_default();
    Some(format!(
        "{}/{artifact}/{version}/{artifact}-{version}{classifier}.{ext}",
        group.replace('.', "/")
    ))
}

fn library_key(name: &str) -> String {
    let parts: Vec<&str> = name.split(':').collect();
    match parts.as_slice() {
        [group, artifact, _, classifier, ..] => format!("{group}:{artifact}:{classifier}"),
        [group, artifact, ..] => format!("{group}:{artifact}"),
        _ => name.to_owned(),
    }
}

fn safe_join(base: &Path, relative: &str) -> CmdResult<PathBuf> {
    let rel = Path::new(relative);
    // Only plain names: on Windows "/etc" and "C:x" aren't absolute but still escape `base` when joined.
    if rel.components().any(|c| !matches!(c, std::path::Component::Normal(_) | std::path::Component::CurDir)) {
        return Err("Refused an unsafe file path from version metadata".into());
    }
    Ok(base.join(rel))
}

fn check_host(url: &str) -> CmdResult<()> {
    let parsed = url::Url::parse(url).map_err(|_| "Invalid download URL".to_string())?;
    let trusted = parsed.scheme() == "https"
        && parsed.port().is_none()
        && parsed.username().is_empty()
        && parsed.host_str().is_some_and(|h| ALLOWED_HOSTS.contains(&h));
    if !trusted {
        return Err(format!("Refused a download from an unverified source ({})", parsed.host_str().unwrap_or("?")));
    }
    Ok(())
}

/// Java 8 versions need Mojang's Intel-only runtime and x86_64 LWJGL 2 natives, which don't run reliably on Apple silicon.
fn check_platform(game_version: &str, component: &str) -> CmdResult<()> {
    if cfg!(all(target_os = "macos", target_arch = "aarch64")) && component == "jre-legacy" {
        return Err(format!("Minecraft {game_version} isn't supported on Apple silicon Macs yet."));
    }
    Ok(())
}

fn sha1_file(path: &Path) -> Option<String> {
    use std::io::Read;
    let mut file = std::fs::File::open(path).ok()?;
    let mut hasher = Sha1::new();
    let mut buf = vec![0u8; 1 << 16];
    loop {
        let n = file.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Some(hex::encode(hasher.finalize()))
}

// ---------- metadata ----------

async fn fetch_verified_text(state: &AppState, url: &str, sha1: Option<&str>, context: &str) -> CmdResult<String> {
    check_host(url)?;
    let text = state
        .http
        .get(url)
        .send()
        .await
        .map_err(|e| format!("{context}: {}", friendly_network(&e)))?
        .error_for_status()
        .map_err(|e| format!("{context}: {e}"))?
        .text()
        .await
        .map_err(|e| format!("{context}: {e}"))?;
    if let Some(expected) = sha1 {
        if hex::encode(Sha1::digest(text.as_bytes())) != expected {
            return Err(format!("{context}: the file failed its integrity check"));
        }
    }
    Ok(text)
}

async fn vanilla_version(state: &AppState, id: &str) -> CmdResult<Value> {
    let path = state.paths.game().join("versions").join(id).join(format!("{id}.json"));
    if let Some(value) = read_json_opt::<Value>(&path) {
        if value["id"].as_str() == Some(id) {
            return Ok(value);
        }
    }
    let manifest = get_json(&state.http, MANIFEST_URL, "Could not reach Mojang").await?;
    let entry = manifest["versions"]
        .as_array()
        .and_then(|v| v.iter().find(|x| x["id"].as_str() == Some(id)))
        .ok_or_else(|| format!("Minecraft {id} isn't listed by Mojang"))?;
    let text = fetch_verified_text(
        state,
        entry["url"].as_str().ok_or("Bad manifest")?,
        entry["sha1"].as_str(),
        "Minecraft version metadata",
    )
    .await?;
    let value: Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    std::fs::create_dir_all(path.parent().unwrap()).map_err(err("Could not create the game folder"))?;
    std::fs::write(&path, text).map_err(err("Could not save version metadata"))?;
    Ok(value)
}

async fn fabric_profile(state: &AppState, game: &str, pinned: Option<&str>) -> CmdResult<(Value, String)> {
    let loader = match pinned {
        Some(v) => v.to_owned(),
        None => {
            let loaders = get_json(&state.http, &format!("{FABRIC_META}/versions/loader/{game}"), "Could not reach Fabric").await?;
            loaders
                .as_array()
                .and_then(|l| {
                    l.iter()
                        .find(|x| x["loader"]["stable"].as_bool() == Some(true))
                        .or_else(|| l.first())
                })
                .and_then(|x| x["loader"]["version"].as_str())
                .map(str::to_owned)
                .ok_or_else(|| format!("Fabric has no loader for Minecraft {game}"))?
        }
    };
    if !loader.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '+' || c == '-') {
        return Err("Unexpected Fabric loader version".into());
    }
    let path = state
        .paths
        .game()
        .join("versions")
        .join(format!("fabric-loader-{loader}-{game}"))
        .join("profile.json");
    if let Some(value) = read_json_opt::<Value>(&path) {
        return Ok((value, loader));
    }
    let value = get_json(
        &state.http,
        &format!("{FABRIC_META}/versions/loader/{game}/{loader}/profile/json"),
        "Could not load the Fabric profile",
    )
    .await?;
    if value["mainClass"].as_str().is_none() {
        return Err("Fabric returned an incomplete profile".into());
    }
    write_json(&path, &value)?;
    Ok((value, loader))
}

fn runtime_platform(index: &Value, component: &str) -> Option<&'static str> {
    let has = |p: &str| index[p][component].as_array().is_some_and(|a| !a.is_empty());
    let candidates: &[&'static str] = match (std::env::consts::OS, std::env::consts::ARCH) {
        ("macos", "aarch64") => &["mac-os-arm64", "mac-os"],
        ("macos", _) => &["mac-os"],
        ("windows", "aarch64") => &["windows-arm64", "windows-x64"],
        ("windows", "x86") => &["windows-x86"],
        ("windows", _) => &["windows-x64"],
        ("linux", "x86") => &["linux-i386"],
        _ => &["linux"],
    };
    candidates.iter().copied().find(|p| has(p))
}

fn java_binary(dir: &Path) -> PathBuf {
    match std::env::consts::OS {
        "macos" => dir.join("jre.bundle/Contents/Home/bin/java"),
        "windows" => dir.join("bin/javaw.exe"),
        _ => dir.join("bin/java"),
    }
}

/// How long a downloaded copy of Mojang's Java runtime index is used without asking again.
const RUNTIME_INDEX_MAX_AGE: Duration = Duration::from_secs(6 * 3600);

/// A cached JSON object, optionally only if the file was written within `max_age`.
fn cached_json(path: &Path, max_age: Option<Duration>) -> Option<Value> {
    if let Some(max_age) = max_age {
        let age = std::fs::metadata(path).ok()?.modified().ok()?.elapsed().ok()?;
        if age > max_age {
            return None;
        }
    }
    read_json_opt::<Value>(path).filter(Value::is_object)
}

/// Mojang's Java runtime index, kept in `runtimes/index.json`. A recent copy is used without a
/// network request; an older one is refreshed, and still serves as a fallback when Mojang can't be
/// reached. The index only points at manifests, which are verified by SHA-1 before use.
async fn runtime_index(state: &AppState) -> CmdResult<Value> {
    let path = state.paths.runtimes().join("index.json");
    if let Some(index) = cached_json(&path, Some(RUNTIME_INDEX_MAX_AGE)) {
        return Ok(index);
    }
    match get_json(&state.http, RUNTIME_INDEX, "Could not reach Mojang's Java runtime list").await {
        Ok(index) => {
            let _ = write_json(&path, &index);
            Ok(index)
        }
        Err(error) => cached_json(&path, None).ok_or(error),
    }
}

/// A runtime manifest, reused from `cache` only while it still matches the SHA-1 the index lists.
async fn runtime_manifest(state: &AppState, url: &str, sha1: Option<&str>, cache: &Path) -> CmdResult<Value> {
    if let (Some(expected), Ok(bytes)) = (sha1, std::fs::read(cache)) {
        if hex::encode(Sha1::digest(&bytes)) == expected {
            if let Ok(value) = serde_json::from_slice::<Value>(&bytes) {
                return Ok(value);
            }
        }
    }
    let text = fetch_verified_text(state, url, sha1, "Java runtime manifest").await?;
    if sha1.is_some() {
        if let Some(parent) = cache.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::write(cache, &text);
    }
    serde_json::from_str(&text).map_err(|e| e.to_string())
}

async fn java_runtime(
    state: &AppState,
    game_version: &str,
    component: &str,
    required_major: Option<u64>,
    downloads: &mut Vec<Download>,
    links: &mut Vec<(PathBuf, String)>,
) -> CmdResult<PathBuf> {
    let settings = load_settings(state);
    if let Some(custom) = settings["javaPath"].as_str().filter(|p| !p.trim().is_empty()) {
        let java = crate::data::inspect_java(custom)
            .await
            .map_err(|e| format!("The custom Java in Settings can't be used: {e}"))?;
        if let (Some(need), Some(have)) = (required_major, java.major) {
            if u64::from(have) < need {
                return Err(format!(
                    "The custom Java in Settings is Java {have}, but Minecraft {game_version} needs Java {need} or newer. Choose a newer Java, or clear the custom path to use Flint's managed Java."
                ));
            }
        }
        return Ok(PathBuf::from(custom.trim()));
    }
    let index = runtime_index(state).await?;
    let platform = runtime_platform(&index, component)
        .ok_or_else(|| format!("Mojang has no Java runtime ({component}) for this computer"))?;
    let entry = &index[platform][component][0];
    let dir = state.paths.runtimes().join(component).join(platform);
    let manifest = runtime_manifest(
        state,
        entry["manifest"]["url"].as_str().ok_or("Bad runtime index")?,
        entry["manifest"]["sha1"].as_str(),
        &state.paths.runtimes().join(component).join(format!("{platform}.manifest.json")),
    )
    .await?;
    for (path, file) in manifest["files"].as_object().ok_or("Bad runtime manifest")? {
        let target = safe_join(&dir, path)?;
        match file["type"].as_str() {
            Some("directory") => {
                std::fs::create_dir_all(&target).map_err(err("Could not create the Java folder"))?;
            }
            Some("file") => {
                let raw = &file["downloads"]["raw"];
                downloads.push(Download {
                    url: raw["url"].as_str().ok_or("Bad runtime file")?.to_owned(),
                    path: target,
                    sha1: raw["sha1"].as_str().map(str::to_owned),
                    size: raw["size"].as_u64(),
                    executable: file["executable"].as_bool().unwrap_or(false),
                });
            }
            Some("link") => {
                if let Some(t) = file["target"].as_str() {
                    links.push((target, t.to_owned()));
                }
            }
            _ => {}
        }
    }
    Ok(java_binary(&dir))
}

// ---------- downloads ----------

/// A file whose SHA-1 was checked, with the size and modification time it had at the time.
#[derive(serde::Serialize, serde::Deserialize, Clone, PartialEq, Debug)]
struct VerifiedFile {
    size: u64,
    modified: u128,
    sha1: String,
}

/// Remembers which game files were already hash-checked (`minecraft/.flint-verified.json`), so a
/// launch only re-hashes files whose size or modification time changed since. Repair ignores it.
type VerifiedFiles = HashMap<String, VerifiedFile>;

fn modified_nanos(meta: &std::fs::Metadata) -> Option<u128> {
    meta.modified().ok()?.duration_since(std::time::UNIX_EPOCH).ok().map(|d| d.as_nanos())
}

/// Whether a file must be (re)downloaded. Sizes are always compared; with `deep`, the SHA-1 is
/// checked too, unless `verified` shows this exact file (same size and modification time) already
/// passed the same hash and `trust_verified` allows reusing that result.
fn needs_download(item: &Download, deep: bool, verified: &mut VerifiedFiles, trust_verified: bool) -> bool {
    let Ok(meta) = std::fs::metadata(&item.path) else { return true };
    if item.size.is_some_and(|s| s != meta.len()) {
        return true;
    }
    let (true, Some(expected)) = (deep, &item.sha1) else { return false };
    let key = item.path.to_string_lossy().into_owned();
    let stamp = modified_nanos(&meta).map(|modified| VerifiedFile { size: meta.len(), modified, sha1: expected.clone() });
    if trust_verified && stamp.is_some() && verified.get(&key) == stamp.as_ref() {
        return false;
    }
    let ok = sha1_file(&item.path).as_deref() == Some(expected.as_str());
    match (ok, stamp) {
        (true, Some(stamp)) => {
            verified.insert(key, stamp);
        }
        _ => {
            verified.remove(&key);
        }
    }
    !ok
}

/// Names the part of the game a file belongs to, for error messages.
fn component_name(state: &AppState, path: &Path) -> &'static str {
    let game = state.paths.game();
    if path.starts_with(state.paths.runtimes()) {
        "Java runtime file"
    } else if path.starts_with(game.join("assets/objects")) {
        "game asset"
    } else if path.starts_with(game.join("assets")) {
        "logging configuration"
    } else if path.starts_with(game.join("libraries")) {
        "game library"
    } else if path.starts_with(game.join("versions")) {
        "Minecraft client"
    } else {
        "game file"
    }
}

/// Explains a download that failed after its retries, naming the file and where it came from.
fn download_failure(state: &AppState, item: &Download, reason: &str) -> String {
    let what = component_name(state, &item.path);
    let name = item.path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let host = url::Url::parse(&item.url).ok().and_then(|u| u.host_str().map(str::to_owned)).unwrap_or_else(|| "an unknown address".into());
    let reason = match reason {
        "integrity check failed" => "the file didn't match its published checksum, so it was discarded",
        "size mismatch" => "the file arrived incomplete",
        other => other,
    };
    format!("Couldn't download the {what} {name} from {host}: {reason}. Files that finished downloading are kept, so Retry continues from here.")
}

async fn fetch_one(state: &AppState, item: &Download, done: &AtomicU64, cancel: &AtomicBool) -> CmdResult<()> {
    if check_host(&item.url).is_err() {
        let host = url::Url::parse(&item.url).ok().and_then(|u| u.host_str().map(str::to_owned)).unwrap_or_else(|| "an unknown address".into());
        return Err(format!(
            "Flint refused to download the {} {} because its address ({host}) isn't an official Minecraft or Fabric download server.",
            component_name(state, &item.path),
            item.path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
        ));
    }
    if let Some(parent) = item.path.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(err("Could not create a game folder"))?;
    }
    // Each download needs its own partial file. Deriving it from the stem alone made files such as
    // java.policy and java.security (or java.exe and java.dll) share one while downloading in
    // parallel, so a verified stream could be renamed over another file's bytes.
    let part = crate::jobs::part_path(&item.path);
    // Removes the partial file however this download ends, including when it is dropped because
    // another download failed or the install was paused (it is renamed away on success).
    let _cleanup = RemoveOnDrop(&part);
    let mut last_error = String::new();
    for attempt in 0..3 {
        if attempt > 0 {
            tokio::time::sleep(Duration::from_millis(600 * attempt)).await;
        }
        cancelled(cancel)?;
        let mut written = 0u64;
        let result: CmdResult<()> = async {
            let response = state
                .stream_http
                .get(&item.url)
                .send()
                .await
                .map_err(|e| friendly_network(&e))?
                .error_for_status()
                .map_err(|e| e.to_string())?;
            let mut file = tokio::fs::File::create(&part).await.map_err(|e| e.to_string())?;
            let mut hasher = Sha1::new();
            let mut stream = response.bytes_stream();
            while let Some(chunk) = stream.next().await {
                cancelled(cancel)?;
                let chunk = chunk.map_err(|e| friendly_network(&e))?;
                hasher.update(&chunk);
                file.write_all(&chunk).await.map_err(|e| e.to_string())?;
                written += chunk.len() as u64;
                done.fetch_add(chunk.len() as u64, Ordering::Relaxed);
            }
            file.flush().await.map_err(|e| e.to_string())?;
            drop(file);
            if let Some(expected) = &item.sha1 {
                if hex::encode(hasher.finalize()) != *expected {
                    return Err("integrity check failed".into());
                }
            }
            if let Some(size) = item.size {
                if size != written {
                    return Err("size mismatch".into());
                }
            }
            #[cfg(unix)]
            if item.executable {
                use std::os::unix::fs::PermissionsExt;
                let _ = std::fs::set_permissions(&part, std::fs::Permissions::from_mode(0o755));
            }
            tokio::fs::rename(&part, &item.path).await.map_err(|e| e.to_string())?;
            Ok(())
        }
        .await;
        if result.is_err() {
            // A failed attempt (dropped connection, bad checksum) keeps nothing, so its bytes no
            // longer count as downloaded; the retry counts them again.
            done.fetch_sub(written, Ordering::Relaxed);
        }
        match result {
            Ok(()) => return Ok(()),
            Err(e) if e == "__cancelled__" => return Err(e),
            Err(e) => last_error = e,
        }
    }
    Err(download_failure(state, item, &last_error))
}

struct RemoveOnDrop<'a>(&'a Path);

impl Drop for RemoveOnDrop<'_> {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(self.0);
    }
}

/// Everything a profile needs, including files already on disk.
#[derive(Clone, Copy, Default)]
struct Totals {
    bytes: u64,
    files: usize,
}

/// Measurement builds only: replays the event traffic of one game download plus one content job, at
/// the real rates (profile progress every 220 ms, job progress throttled to 140 ms), for `seconds`.
/// The interface can't tell it from a real download, so its cost can be measured without the network.
#[tauri::command]
pub async fn perf_emit_load(app: AppHandle, state: State<'_, AppState>, profile_id: String, seconds: u64) -> CmdResult<()> {
    if !cfg!(feature = "perf") || !state.qa {
        return Err("Not available.".into());
    }
    let totals = Totals { bytes: 650_000_000, files: 5_000 };
    let (job, _) = state.jobs.create(&app, "install", Some(&profile_id), "Performance load", 40);
    let start = std::time::Instant::now();
    let mut last_profile = start - Duration::from_secs(1);
    while start.elapsed() < Duration::from_secs(seconds.min(120)) {
        let t = start.elapsed().as_secs_f64() / seconds.max(1) as f64;
        if last_profile.elapsed() >= Duration::from_millis(220) {
            last_profile = std::time::Instant::now();
            let bytes = (totals.bytes as f64 * t) as u64;
            emit(&app, &state, &profile_id, download_progress("Downloading game files", bytes, (totals.files as f64 * t) as usize, totals));
        }
        state.jobs.update(&app, &job, true, |j| {
            j.state = "running".into();
            j.bytes_done = (80_000_000.0 * t) as u64;
            j.bytes_total = Some(80_000_000);
            j.items_done = (40.0 * t) as usize;
        });
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    state.jobs.finish(&app, &job, &Ok(json!({})));
    emit(&app, &state, &profile_id, json!({ "state": "ready", "progress": null }));
    Ok(())
}

fn download_progress(label: &str, bytes: u64, files: usize, totals: Totals) -> Value {
    json!({
        "state": "downloading",
        "stage": "download",
        "label": label,
        "progress": if totals.bytes > 0 { (bytes as f64 / totals.bytes as f64).min(1.0) } else { 0.0 },
        "bytesDone": bytes,
        "bytesTotal": totals.bytes,
        "filesDone": files,
        "filesTotal": totals.files,
    })
}

/// Downloads `items`, showing progress against `all` (the whole profile). Files already on disk
/// count as done, so a resumed install continues from where it was paused instead of restarting
/// at 0% with a smaller total, and the bytes shown never go backwards.
async fn download_all(
    app: &AppHandle,
    state: &AppState,
    id: &str,
    items: Vec<Download>,
    all: Totals,
    label: &str,
    cancel: &Arc<AtomicBool>,
) -> CmdResult<()> {
    if items.is_empty() {
        return Ok(());
    }
    let pending_bytes: u64 = items.iter().filter_map(|d| d.size).sum();
    let totals = Totals { bytes: all.bytes.max(pending_bytes), files: all.files.max(items.len()) };
    let (base_bytes, base_files) = (totals.bytes - pending_bytes, totals.files - items.len());
    let done = Arc::new(AtomicU64::new(0));
    let files = Arc::new(AtomicUsize::new(0));
    let finished = Arc::new(AtomicBool::new(false));
    let ticker = {
        let (app, id, label) = (app.clone(), id.to_owned(), label.to_owned());
        let (done, files, finished) = (done.clone(), files.clone(), finished.clone());
        tauri::async_runtime::spawn(async move {
            use tauri::Manager;
            let state = app.state::<AppState>();
            let mut shown = 0;
            while !finished.load(Ordering::Relaxed) {
                // A retried file briefly lowers the count; the display holds its highest value.
                shown = (base_bytes + done.load(Ordering::Relaxed)).min(totals.bytes).max(shown);
                emit(&app, &state, &id, download_progress(&label, shown, base_files + files.load(Ordering::Relaxed), totals));
                tokio::time::sleep(Duration::from_millis(220)).await;
            }
        })
    };
    let result = futures_util::stream::iter(items.into_iter().map(Ok::<Download, String>))
        .try_for_each_concurrent(16, |item| {
            let (done, files, cancel) = (done.clone(), files.clone(), cancel.clone());
            async move {
                fetch_one(state, &item, &done, &cancel).await?;
                files.fetch_add(1, Ordering::Relaxed);
                Ok(())
            }
        })
        .await;
    finished.store(true, Ordering::Relaxed);
    let _ = ticker.await;
    match &result {
        // Shows the finished download before the next stage, instead of ending at the last tick.
        Ok(()) => emit(app, state, id, download_progress(label, totals.bytes, totals.files, totals)),
        Err(_) => cancel.store(true, Ordering::Relaxed),
    }
    result
}

fn extract_natives(jar: &Path, dest: &Path, exclude: &[String]) -> CmdResult<()> {
    let file = std::fs::File::open(jar).map_err(err("Could not open natives"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(err("Could not read natives"))?;
    std::fs::create_dir_all(dest).map_err(err("Could not create the natives folder"))?;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| e.to_string())?;
        let name = entry.name().to_owned();
        if entry.is_dir() || exclude.iter().any(|x| name.starts_with(x.as_str())) {
            continue;
        }
        let Some(relative) = entry.enclosed_name() else { continue };
        let out = dest.join(relative);
        if let Some(parent) = out.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let mut target = std::fs::File::create(&out).map_err(|e| e.to_string())?;
        std::io::copy(&mut entry, &mut target).map_err(|e| e.to_string())?;
    }
    Ok(())
}

// ---------- install ----------

struct Plan {
    pending: Vec<Download>,
    totals: Totals,
    #[cfg_attr(not(unix), allow(dead_code))] // symlinks are only made on Unix
    links: Vec<(PathBuf, String)>,
    installed: Installed,
}

async fn install_inner(app: &AppHandle, state: &AppState, profile: &Profile, cancel: &Arc<AtomicBool>, deep: bool) -> CmdResult<Installed> {
    let plan = until_cancelled(cancel, make_plan(app, state, profile, cancel, deep, false)).await?;
    let id = profile.id.as_str();
    // Downloads are SHA-1 checked before they're moved into place, so the jars fetched now are
    // remembered as verified: the first launch after an install doesn't hash them all again.
    let fetched: Vec<(PathBuf, String)> = plan
        .pending
        .iter()
        .filter(|d| d.path.extension().is_some_and(|e| e == "jar"))
        .filter_map(|d| Some((d.path.clone(), d.sha1.clone()?)))
        .collect();
    download_all(app, state, id, plan.pending, plan.totals, "Downloading game files", cancel).await?;
    remember_verified(&state.paths.game().join(".flint-verified.json"), &fetched);
    cancelled(cancel)?;

    emit_stage(app, state, id, "installing", "install", "Installing");
    #[cfg(unix)]
    for (link, target) in &plan.links {
        if std::fs::symlink_metadata(link).is_err() {
            if let Some(parent) = link.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            let _ = std::os::unix::fs::symlink(target, link);
        }
    }
    let installed = plan.installed;
    std::fs::create_dir_all(&installed.natives_dir).map_err(err("Could not create the natives folder"))?;
    for lib in &installed.libraries {
        if let Some(exclude) = &lib.native_extract {
            extract_natives(&lib.path, &installed.natives_dir, exclude)?;
        }
    }
    std::fs::create_dir_all(state.paths.profile_dir(id).join("mods")).map_err(err("Could not create the profile folder"))?;
    if !installed.java.exists() {
        return Err("The Java runtime didn't install correctly.".into());
    }
    Ok(installed)
}

/// `quiet`: a background size probe that shows no "Checking" state (see `profile_check`).
async fn make_plan(app: &AppHandle, state: &AppState, profile: &Profile, cancel: &Arc<AtomicBool>, deep: bool, quiet: bool) -> CmdResult<Plan> {
    let id = profile.id.as_str();
    let game = state.paths.game();
    if !quiet {
        emit_stage(app, state, id, "checking", "validate", "Validating version");
    }
    let version = vanilla_version(state, &profile.game_version).await?;
    let fabric = if profile.loader == "fabric" {
        let (value, loader) = fabric_profile(state, &profile.game_version, profile.loader_version.as_deref()).await?;
        if profile.loader_version.as_deref() != Some(loader.as_str()) {
            update_profile(state, id, |p| p.loader_version = Some(loader.clone())).await?;
        }
        Some(value)
    } else {
        None
    };
    cancelled(cancel)?;

    if !quiet {
        emit_stage(app, state, id, "checking", "check", "Checking files");
    }
    let mut downloads: Vec<Download> = Vec::new();
    let mut libraries: Vec<Library> = Vec::new();
    let lib_root = game.join("libraries");
    let version_id = profile.game_version.as_str();

    let client = &version["downloads"]["client"];
    let client_jar = game.join("versions").join(version_id).join(format!("{version_id}.jar"));
    downloads.push(Download {
        url: client["url"].as_str().ok_or("Version has no client download")?.to_owned(),
        path: client_jar.clone(),
        sha1: client["sha1"].as_str().map(str::to_owned),
        size: client["size"].as_u64(),
        executable: false,
    });

    if let Some(fabric) = &fabric {
        for lib in fabric["libraries"].as_array().into_iter().flatten() {
            let name = lib["name"].as_str().ok_or("Bad Fabric library")?;
            let rel = maven_path(name).ok_or("Bad Fabric library name")?;
            let base = lib["url"].as_str().unwrap_or("https://maven.fabricmc.net/");
            let path = safe_join(&lib_root, &rel)?;
            downloads.push(Download {
                url: format!("{}/{rel}", base.trim_end_matches('/')),
                path: path.clone(),
                sha1: lib["sha1"].as_str().map(str::to_owned),
                size: lib["size"].as_u64(),
                executable: false,
            });
            libraries.push(Library { key: library_key(name), path, native_extract: None });
        }
    }
    let fabric_keys: HashSet<String> = libraries.iter().map(|l| l.key.clone()).collect();
    for lib in version["libraries"].as_array().into_iter().flatten() {
        if !rules_allow(lib.get("rules")) {
            continue;
        }
        let name = lib["name"].as_str().unwrap_or_default();
        let artifact = &lib["downloads"]["artifact"];
        if let (Some(path), Some(url)) = (artifact["path"].as_str(), artifact["url"].as_str()) {
            let key = library_key(name);
            if !fabric_keys.contains(&key) && !url.is_empty() {
                let path = safe_join(&lib_root, path)?;
                downloads.push(Download {
                    url: url.to_owned(),
                    path: path.clone(),
                    sha1: artifact["sha1"].as_str().map(str::to_owned),
                    size: artifact["size"].as_u64(),
                    executable: false,
                });
                libraries.push(Library { key, path, native_extract: None });
            }
        }
        if let Some(classifier) = lib["natives"][mojang_os()].as_str() {
            let classifier = classifier.replace("${arch}", if cfg!(target_pointer_width = "64") { "64" } else { "32" });
            let native = &lib["downloads"]["classifiers"][classifier.as_str()];
            if let (Some(path), Some(url)) = (native["path"].as_str(), native["url"].as_str()) {
                let path = safe_join(&lib_root, path)?;
                downloads.push(Download {
                    url: url.to_owned(),
                    path: path.clone(),
                    sha1: native["sha1"].as_str().map(str::to_owned),
                    size: native["size"].as_u64(),
                    executable: false,
                });
                let exclude = lib["extract"]["exclude"]
                    .as_array()
                    .map(|x| x.iter().filter_map(|v| v.as_str().map(str::to_owned)).collect())
                    .unwrap_or_default();
                libraries.push(Library { key: format!("{name}:natives"), path, native_extract: Some(exclude) });
            }
        }
    }

    let asset_index = &version["assetIndex"];
    let index_id = asset_index["id"].as_str().ok_or("Version has no asset index")?;
    let index_path = game.join("assets/indexes").join(format!("{index_id}.json"));
    let index: Value = match read_json_opt::<Value>(&index_path)
        .filter(|_| sha1_file(&index_path).as_deref() == asset_index["sha1"].as_str())
    {
        Some(value) => value,
        None => {
            let text = fetch_verified_text(state, asset_index["url"].as_str().ok_or("Bad asset index")?, asset_index["sha1"].as_str(), "Asset index").await?;
            std::fs::create_dir_all(index_path.parent().unwrap()).map_err(|e| e.to_string())?;
            std::fs::write(&index_path, &text).map_err(|e| e.to_string())?;
            serde_json::from_str(&text).map_err(|e| e.to_string())?
        }
    };
    let mut seen = HashSet::new();
    for object in index["objects"].as_object().into_iter().flat_map(|o| o.values()) {
        let Some(hash) = object["hash"].as_str() else { continue };
        if hash.len() != 40 || !seen.insert(hash.to_owned()) {
            continue;
        }
        downloads.push(Download {
            url: format!("https://resources.download.minecraft.net/{}/{hash}", &hash[..2]),
            path: game.join("assets/objects").join(&hash[..2]).join(hash),
            sha1: Some(hash.to_owned()),
            size: object["size"].as_u64(),
            executable: false,
        });
    }

    let mut logging_arg = None;
    let logging = &version["logging"]["client"];
    if let (Some(file_id), Some(url), Some(argument)) = (logging["file"]["id"].as_str(), logging["file"]["url"].as_str(), logging["argument"].as_str()) {
        let path = safe_join(&game.join("assets/log_configs"), file_id)?;
        downloads.push(Download {
            url: url.to_owned(),
            path: path.clone(),
            sha1: logging["file"]["sha1"].as_str().map(str::to_owned),
            size: logging["file"]["size"].as_u64(),
            executable: false,
        });
        logging_arg = Some(argument.replace("${path}", &path.display().to_string()));
    }

    let component = version["javaVersion"]["component"].as_str().unwrap_or("jre-legacy");
    check_platform(&profile.game_version, component)?;
    let mut links = Vec::new();
    let required_major = version["javaVersion"]["majorVersion"].as_u64();
    let java = java_runtime(state, &profile.game_version, component, required_major, &mut downloads, &mut links).await?;
    cancelled(cancel)?;

    let totals = Totals { bytes: downloads.iter().filter_map(|d| d.size).sum(), files: downloads.len() };
    let report = (!quiet).then(|| {
        let (app, id, cancel) = (app.clone(), id.to_owned(), cancel.clone());
        move |done: usize, total: usize| {
            use tauri::Manager;
            // Never shows "Checking" again once the check was paused and its final state shown.
            if !cancel.load(Ordering::Relaxed) {
                let progress = json!({ "state": "checking", "stage": "check", "label": "Checking files", "progress": null, "filesDone": done, "filesTotal": total });
                emit(&app, &app.state::<AppState>(), &id, progress);
            }
        }
    });
    let (cache, check_cancel) = (game.join(".flint-verified.json"), cancel.clone());
    // Hashing can take a while (a repair re-hashes every file), so it runs off the async runtime,
    // where it can't hold up other commands and stops as soon as the check is paused.
    let pending = tauri::async_runtime::spawn_blocking(move || pending_downloads(downloads, &cache, deep, &check_cancel, report))
        .await
        .map_err(|e| e.to_string())??;
    let natives_dir = game.join("versions").join(version_id).join("natives");
    Ok(Plan {
        pending,
        totals,
        links,
        installed: Installed { version, fabric, libraries, client_jar, natives_dir, java, logging_arg },
    })
}

/// Records freshly downloaded (already hash-checked) files in the verified cache.
fn remember_verified(cache: &Path, files: &[(PathBuf, String)]) {
    if files.is_empty() {
        return;
    }
    let mut verified: VerifiedFiles = read_json(cache);
    for (path, sha1) in files {
        if let Some(stamp) = std::fs::metadata(path).ok().and_then(|meta| Some(VerifiedFile { size: meta.len(), modified: modified_nanos(&meta)?, sha1: sha1.clone() })) {
            verified.insert(path.to_string_lossy().into_owned(), stamp);
        }
    }
    let _ = write_json(cache, &verified);
}

/// The downloads whose file is missing or wrong. Jars (and, for a repair, every file) are hashed;
/// other files are compared by size. `report` gets (files checked, total) every 250 ms once the
/// check has taken longer than half a second.
fn pending_downloads(
    downloads: Vec<Download>,
    cache: &Path,
    deep: bool,
    cancel: &AtomicBool,
    report: Option<impl Fn(usize, usize)>,
) -> CmdResult<Vec<Download>> {
    let mut verified: VerifiedFiles = read_json(cache);
    let total = downloads.len();
    let started = std::time::Instant::now();
    let mut last_report = started;
    let mut pending = Vec::new();
    let mut stopped = false;
    for (index, item) in downloads.into_iter().enumerate() {
        if cancel.load(Ordering::Relaxed) {
            stopped = true;
            break;
        }
        if let Some(report) = &report {
            if started.elapsed() > Duration::from_millis(500) && last_report.elapsed() > Duration::from_millis(250) {
                last_report = std::time::Instant::now();
                report(index, total);
            }
        }
        let deep_check = deep || item.path.extension().is_some_and(|e| e == "jar");
        // Repair (`deep`) re-hashes everything instead of trusting earlier results.
        if needs_download(&item, deep_check, &mut verified, !deep) {
            pending.push(item);
        }
    }
    // Results so far are kept even when paused, so the next check doesn't hash those files again.
    let _ = write_json(cache, &verified);
    if stopped {
        return Err("__cancelled__".into());
    }
    Ok(pending)
}

/// Checks what a profile still needs. `quiet` is Home's background size probe: it shows no
/// "Checking" state, never holds the profile (Install and Play stay available), leaves the shown
/// state alone if it fails, and doesn't report if an install or launch started meanwhile.
#[tauri::command]
pub async fn profile_check(app: AppHandle, state: State<'_, AppState>, id: String, quiet: Option<bool>) -> CmdResult<Value> {
    let quiet = quiet.unwrap_or(false);
    let profile = find_profile(&state, &id)?;
    let busy = || state.install_cancel.lock().unwrap().contains_key(&id) || state.running.lock().unwrap().contains_key(&id);
    if quiet && busy() {
        return Err("This profile is being prepared.".into());
    }
    let (cancel, reservation) = if quiet {
        (Arc::new(AtomicBool::new(false)), None)
    } else {
        let cancel = begin_job(&state, &id)?;
        (cancel, Some(JobReservation { app: &app, state: &state, id: id.clone() }))
    };
    let result = async {
        let plan = until_cancelled(&cancel, make_plan(&app, &state, &profile, &cancel, false, quiet)).await?;
        let bytes: u64 = plan.pending.iter().filter_map(|d| d.size).sum();
        let ready = plan.pending.is_empty() && plan.installed.java.exists();
        if ready && !profile.installed {
            update_profile(&state, &id, |p| p.installed = true).await?;
        }
        Ok::<_, String>((ready, bytes, plan.pending.len()))
    }
    .await;
    drop(reservation);
    let (ready, bytes, files) = match result {
        Ok(found) => found,
        Err(error) if quiet => return Err(error),
        Err(error) => return Err(fail(&app, &state, &id, &error)),
    };
    if !quiet {
        emit(&app, &state, &id, json!({ "state": if ready { "ready" } else { "uninstalled" }, "missingBytes": bytes, "missingFiles": files }));
    } else if ready && !busy() {
        // A quiet probe only changes what is shown when it finds the profile complete; otherwise
        // it would replace a "paused" or error message with a plain "Not installed".
        emit(&app, &state, &id, json!({ "state": "ready" }));
    }
    Ok(json!({ "ready": ready, "missingBytes": bytes, "missingFiles": files }))
}

fn begin_job(state: &AppState, id: &str) -> CmdResult<Arc<AtomicBool>> {
    if state.running.lock().unwrap().contains_key(id) {
        return Err("This profile is running.".into());
    }
    let mut jobs = state.install_cancel.lock().unwrap();
    if jobs.contains_key(id) {
        return Err("This profile is already being prepared.".into());
    }
    let flag = Arc::new(AtomicBool::new(false));
    jobs.insert(id.to_owned(), flag.clone());
    Ok(flag)
}

fn fail(app: &AppHandle, state: &AppState, id: &str, error: &str) -> String {
    if error == "__cancelled__" {
        // Stopping a check or a launch of an installed profile leaves it ready to play (Play
        // downloads anything missing); only an unfinished install shows as paused.
        if find_profile(state, id).is_ok_and(|p| p.installed) {
            emit(app, state, id, json!({ "state": "ready" }));
        } else {
            emit(app, state, id, json!({ "state": "uninstalled", "message": "Installation paused. Resume any time." }));
        }
        "Installation paused.".into()
    } else {
        emit(app, state, id, json!({ "state": "error", "message": error }));
        error.to_owned()
    }
}

#[tauri::command]
pub async fn profile_install(app: AppHandle, state: State<'_, AppState>, id: String, repair: Option<bool>) -> CmdResult<()> {
    let profile = find_profile(&state, &id)?;
    let cancel = begin_job(&state, &id)?;
    // Held through "Finalizing" too: releasing the profile earlier let a background check report
    // "Not installed" or "Ready" in the middle of it.
    let reservation = JobReservation { app: &app, state: &state, id: id.clone() };
    let result = async {
        install_inner(&app, &state, &profile, &cancel, repair.unwrap_or(false)).await?;
        emit_stage(&app, &state, &id, "installing", "finalize", "Finalizing");
        update_profile(&state, &id, |p| p.installed = true).await?;
        crate::content::apply_performance_set(&app, &state, &id).await;
        Ok::<_, String>(())
    }
    .await;
    // Released before the final state is shown, so a click on Play the moment it appears works.
    drop(reservation);
    match result {
        Ok(()) => {
            emit(&app, &state, &id, json!({ "state": "ready" }));
            Ok(())
        }
        Err(error) => Err(fail(&app, &state, &id, &error)),
    }
}

#[tauri::command]
pub fn profile_cancel(state: State<'_, AppState>, id: String) {
    if let Some(flag) = state.install_cancel.lock().unwrap().get(&id) {
        flag.store(true, Ordering::Relaxed);
    }
}

#[tauri::command]
pub fn profile_states(state: State<'_, AppState>) -> Value {
    let profiles = crate::data::load_profiles(&state);
    let live = state.profile_states.lock().unwrap().clone();
    let custom_java = load_settings(&state)["javaPath"].as_str().is_some_and(|p| !p.trim().is_empty());
    let mut out = serde_json::Map::new();
    for profile in profiles.profiles {
        let value = live.get(&profile.id).cloned().unwrap_or_else(|| {
            let dir = state.paths.game().join("versions").join(&profile.game_version);
            let jar = dir.join(format!("{}.jar", profile.game_version));
            let component = read_json_opt::<Value>(&dir.join(format!("{}.json", profile.game_version)))
                .and_then(|v| v["javaVersion"]["component"].as_str().map(str::to_owned))
                .unwrap_or_else(|| "jre-legacy".into());
            let java_ready = custom_java || state.paths.runtimes().join(component).is_dir();
            let ready = profile.installed && jar.exists() && java_ready;
            json!({ "profileId": profile.id, "state": if ready { "ready" } else { "uninstalled" } })
        });
        out.insert(profile.id.clone(), value);
    }
    Value::Object(out)
}

// ---------- launch ----------

fn substitute(arg: &str, vars: &HashMap<&str, String>) -> String {
    let mut out = arg.to_owned();
    for (key, value) in vars {
        out = out.replace(&format!("${{{key}}}"), value);
    }
    out
}

fn collect_args(list: Option<&Value>, vars: &HashMap<&str, String>, out: &mut Vec<String>) {
    for item in list.and_then(Value::as_array).into_iter().flatten() {
        match item {
            Value::String(s) => out.push(substitute(s, vars)),
            Value::Object(_) if rules_allow(item.get("rules")) => match &item["value"] {
                Value::String(s) => out.push(substitute(s, vars)),
                Value::Array(values) => out.extend(values.iter().filter_map(Value::as_str).map(|s| substitute(s, vars))),
                _ => {}
            },
            _ => {}
        }
    }
}

/// Java's arguments up to the main class: the heap and tuning flags (Flint Performance Core),
/// Flint's brand, the version's and the loader's own arguments (classpath included) and the
/// logging config.
fn jvm_args(installed: &Installed, policy: JvmPolicy, memory_mb: u64, vars: &HashMap<&str, String>) -> Vec<String> {
    let version = &installed.version;
    let mut args = crate::perfcore::heap_args(memory_mb);
    args.push("-Dminecraft.launcher.brand=flint".to_owned());
    args.extend(crate::perfcore::tuning_args(policy));
    if version.get("arguments").is_some() {
        collect_args(version["arguments"].get("jvm"), vars, &mut args);
    } else {
        // Legacy versions use LWJGL 2, which deadlocks on macOS when started with -XstartOnFirstThread.
        args.push(format!("-Djava.library.path={}", installed.natives_dir.display()));
        args.push("-cp".into());
        args.push(vars["classpath"].clone());
    }
    if let Some(fabric) = &installed.fabric {
        collect_args(fabric["arguments"].get("jvm"), vars, &mut args);
    }
    if let Some(logging) = &installed.logging_arg {
        args.push(logging.clone());
    }
    args
}

/// A game that exits with an error within this window failed to start, rather than crashing later.
const STARTUP_WINDOW: Duration = Duration::from_secs(20);
/// A session counts as played once the game has stayed up this long, or when it exits cleanly.
const PLAYED_AFTER: Duration = Duration::from_secs(20);

fn counts_as_played(clean_exit: Option<bool>, elapsed: Duration) -> bool {
    elapsed >= PLAYED_AFTER || clean_exit == Some(true)
}

/// Keeps a profile reserved (see `begin_job`) until dropped, so a second Play click can't start
/// another install or launch while this one is still preparing.
struct JobReservation<'a> {
    app: &'a AppHandle,
    state: &'a AppState,
    id: String,
}

impl Drop for JobReservation<'_> {
    fn drop(&mut self) {
        self.state.install_cancel.lock().unwrap().remove(&self.id);
        // A panic mid-job must not leave the profile showing a busy state forever.
        if std::thread::panicking() && shows_busy(self.state, &self.id) {
            fail(self.app, self.state, &self.id, "Something went wrong while preparing this profile. Try again.");
        }
    }
}

/// Minimizes Flint if the player asked for that in Settings. Returns whether it did.
fn minimize_for_game(app: &AppHandle, state: &AppState) -> bool {
    use tauri::Manager;
    if load_settings(state)["minimizeOnLaunch"].as_bool() != Some(true) {
        return false;
    }
    app.get_webview_window("main").is_some_and(|w| w.minimize().is_ok())
}

fn bring_back(app: &AppHandle) {
    use tauri::Manager;
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// Explains a game that exited with an error, from its launch log and any new crash report.
fn failure_state(game_dir: &Path, log_path: &Path, since: std::time::SystemTime, exit: Option<i32>, during_startup: bool) -> Value {
    let log = crate::diagnostics::read_tail(log_path, 512 * 1024).map(|t| crate::diagnostics::render_log4j(&t)).unwrap_or_default();
    let crash = crate::diagnostics::new_crash_report(game_dir, since);
    let message = crate::diagnostics::summarize(&log, crash.as_ref().map(|c| c.1.as_str()), exit, during_startup);
    let detail = crate::diagnostics::details(&log, crash.as_ref().map(|(n, t)| (n.as_str(), t.as_str())));
    json!({
        "state": "error",
        "message": message,
        "detail": detail,
        "exitCode": exit,
        "logFile": crash.as_ref().map(|c| json!({ "folder": "crash-reports", "name": c.0 })).unwrap_or_else(|| json!({ "folder": "logs", "name": "flint-launch.log" })),
    })
}

#[tauri::command]
pub async fn profile_launch(app: AppHandle, state: State<'_, AppState>, id: String) -> CmdResult<()> {
    let result = launch(&app, &state, &id).await;
    result.map_err(|e| settle(&app, &state, &id, e))
}

async fn launch(app: &AppHandle, state: &AppState, id: &str) -> CmdResult<()> {
    let (app, state, id) = (app.clone(), state, id.to_owned());
    let profile = find_profile(state, &id)?;
    let account = active_account(state).ok_or("Connect a Minecraft account to play.")?;
    crate::content::launch_preflight(state, &id)?;
    let cancel = begin_job(state, &id)?;
    // Held until the game process is registered as running, so repeated clicks can't launch twice.
    let reservation = JobReservation { app: &app, state, id: id.clone() };
    let installed = install_inner(&app, state, &profile, &cancel, false).await.map_err(|e| fail(&app, state, &id, &e))?;
    if !profile.installed {
        update_profile(state, &id, |p| p.installed = true).await?;
    }
    crate::content::apply_performance_set(&app, state, &id).await;
    // Read again: the copy loaded above predates the performance set.
    let profile = find_profile(state, &id).map_err(|e| fail(&app, state, &id, &e))?;
    let automatic = crate::perfcore::automatic_optimization_enabled(state);
    let tuning = crate::perfcore::LaunchTuning {
        automatic,
        policy: crate::perfcore::choose_policy(automatic, crate::perfcore::hardware(), &installed.version),
        heap_max_mb: crate::data::launch_memory_mb(&profile, load_settings(state)["memoryMb"].as_u64(), crate::data::max_memory_mb()),
    };
    // Flint's in-game menu and its config: installed for supported profiles, removed from all others.
    crate::runtime::sync(state, &profile, &tuning).map_err(|e| fail(&app, state, &id, &e))?;
    // The performance set may have added mods; make sure they're all present and switched on.
    crate::content::launch_preflight(state, &id).map_err(|e| fail(&app, state, &id, &e))?;
    // Auto Sync: records this profile's own changes, then brings in what changed in other profiles where it
    // fits. Its own failures are logged and never stop the launch.
    {
        let profile = profile.clone();
        let _ = crate::data::blocking(&app, move |state| crate::gamesettings::autosync::before_launch(state, &profile)).await;
    }

    emit(&app, state, &id, json!({ "state": "launching", "label": "Starting Minecraft" }));
    let (token, demo) = if account.qa {
        ("0".to_owned(), true)
    } else {
        match crate::auth::minecraft_token_explicit(&app, state, &account.uuid).await {
            Ok(token) => (token, false),
            Err(e) => return Err(fail(&app, state, &id, &e)),
        }
    };

    let game_dir = state.paths.profile_dir(&id);
    let separator = if cfg!(windows) { ";" } else { ":" };
    let mut seen = HashSet::new();
    let mut classpath: Vec<String> = installed
        .libraries
        .iter()
        .filter(|l| l.native_extract.is_none() && seen.insert(l.path.clone()))
        .map(|l| l.path.display().to_string())
        .collect();
    classpath.push(installed.client_jar.display().to_string());

    let version = &installed.version;
    let modern = version.get("arguments").is_some();
    let version_name = installed
        .fabric
        .as_ref()
        .and_then(|f| f["id"].as_str())
        .unwrap_or(&profile.game_version)
        .to_owned();
    let vars: HashMap<&str, String> = HashMap::from([
        ("auth_player_name", account.username.clone()),
        ("version_name", version_name),
        ("game_directory", game_dir.display().to_string()),
        ("assets_root", state.paths.game().join("assets").display().to_string()),
        ("game_assets", state.paths.game().join("assets").display().to_string()),
        ("assets_index_name", version["assetIndex"]["id"].as_str().unwrap_or_default().to_owned()),
        ("auth_uuid", account.uuid.clone()),
        ("auth_access_token", token.clone()),
        ("auth_session", format!("token:{token}:{}", account.uuid)),
        ("clientid", MICROSOFT_CLIENT_ID.to_owned()),
        ("auth_xuid", "0".to_owned()),
        ("user_type", if modern { "msa" } else { "mojang" }.to_owned()),
        ("user_properties", "{}".to_owned()),
        ("version_type", "release".to_owned()),
        ("natives_directory", installed.natives_dir.display().to_string()),
        ("launcher_name", "flint".to_owned()),
        ("launcher_version", env!("CARGO_PKG_VERSION").to_owned()),
        ("classpath", classpath.join(separator)),
        ("classpath_separator", separator.to_owned()),
        ("library_directory", state.paths.game().join("libraries").display().to_string()),
    ]);

    let mut args = jvm_args(&installed, tuning.policy, tuning.heap_max_mb, &vars);
    let main_class = installed
        .fabric
        .as_ref()
        .and_then(|f| f["mainClass"].as_str())
        .or_else(|| version["mainClass"].as_str())
        .ok_or("Version has no main class")?;
    args.push(main_class.to_owned());
    if modern {
        collect_args(version["arguments"].get("game"), &vars, &mut args);
    } else {
        args.extend(version["minecraftArguments"].as_str().unwrap_or_default().split_whitespace().map(|a| substitute(a, &vars)));
    }
    if let Some(fabric) = &installed.fabric {
        collect_args(fabric["arguments"].get("game"), &vars, &mut args);
    }
    if demo {
        args.push("--demo".into());
    }

    let logs = game_dir.join("logs");
    std::fs::create_dir_all(&logs).map_err(err("Could not create the log folder"))?;
    let log_path = logs.join("flint-launch.log");
    let log = std::fs::File::create(&log_path).map_err(err("Could not create the launch log"))?;
    let log_err = log.try_clone().map_err(|e| e.to_string())?;

    let mut command = tokio::process::Command::new(&installed.java);
    command
        .args(&args)
        .current_dir(&game_dir)
        .stdin(std::process::Stdio::null())
        .stdout(log)
        .stderr(log_err)
        .kill_on_drop(false);
    #[cfg(windows)]
    {
        command.creation_flags(0x0800_0000);
    }
    let mut child = command.spawn().map_err(|e| fail(&app, state, &id, &format!("Could not start Java: {e}")))?;
    let launched_at = now_secs();
    let launched_wall = std::time::SystemTime::now();
    if let (Some(pid), Some(presence)) = (child.id(), tauri::Manager::try_state::<crate::discord::Presence>(&app)) {
        presence.started(crate::discord::Session { id: id.clone(), pid, version: profile.game_version.clone(), loader: profile.loader.clone(), started_at: launched_at });
    }
    let (kill_tx, mut kill_rx) = tokio::sync::oneshot::channel::<()>();
    state.running.lock().unwrap().insert(id.clone(), RunningGame { kill: Some(kill_tx) });
    drop(reservation);
    emit(&app, state, &id, json!({ "state": "running", "demo": demo }));
    let minimized = minimize_for_game(&app, state);

    let app_handle = app.clone();
    tauri::async_runtime::spawn(async move {
        use tauri::Manager;
        let state = app_handle.state::<AppState>();
        let started = std::time::Instant::now();
        let mut recorded = false;
        let record = async |state: &AppState| {
            let _ = update_profile(state, &id, |p| p.last_played = Some(launched_at)).await;
        };
        let played = tokio::time::sleep(PLAYED_AFTER);
        tokio::pin!(played);
        let status = loop {
            tokio::select! {
                status = child.wait() => break status.ok(),
                _ = &mut kill_rx => {
                    let _ = child.kill().await;
                    break None;
                }
                _ = &mut played, if !recorded => {
                    recorded = true;
                    record(&state).await;
                    // Lets the interface pick up the new last-played time while the game runs.
                    emit(&app_handle, &state, &id, json!({ "state": "running", "demo": demo }));
                }
            }
        };
        state.running.lock().unwrap().remove(&id);
        if let Some(presence) = app_handle.try_state::<crate::discord::Presence>() {
            presence.ended(&id);
        }
        if !recorded && counts_as_played(status.as_ref().map(|s| s.success()), started.elapsed()) {
            record(&state).await;
        }
        let exit = if status.as_ref().is_some_and(|s| s.success()) { crate::gamesettings::autosync::Exit::Clean } else { crate::gamesettings::autosync::Exit::Unclean };
        match status {
            Some(s) if !s.success() => {
                let during_startup = started.elapsed() < STARTUP_WINDOW;
                emit(&app_handle, &state, &id, failure_state(&game_dir, &log_path, launched_wall, s.code(), during_startup));
                if minimized && during_startup {
                    bring_back(&app_handle);
                }
            }
            _ => emit(&app_handle, &state, &id, json!({ "state": "ready" })),
        }
        // Auto Sync records what the player changed in this game (after a crash, only files that read back
        // whole). Nothing is written into any profile here.
        let _ = crate::data::blocking(&app_handle, move |state| crate::gamesettings::autosync::after_exit(state, &id, exit)).await;
    });
    Ok(())
}

#[tauri::command]
pub fn profile_stop(state: State<'_, AppState>, id: String) {
    if let Some(game) = state.running.lock().unwrap().get_mut(&id) {
        if let Some(kill) = game.kill.take() {
            let _ = kill.send(());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;
    use crate::perfcore::HardwareInfo;

    #[test]
    fn rules_follow_the_last_matching_rule() {
        let os = mojang_os();
        let other = if os == "osx" { "windows" } else { "osx" };
        assert!(rules_allow(None), "no rules means allowed");
        assert!(rules_allow(Some(&json!([{ "action": "allow" }]))));
        assert!(rules_allow(Some(&json!([{ "action": "allow", "os": { "name": os } }]))));
        assert!(!rules_allow(Some(&json!([{ "action": "allow", "os": { "name": other } }]))));
        assert!(!rules_allow(Some(&json!([{ "action": "allow" }, { "action": "disallow", "os": { "name": os } }]))));
        assert!(rules_allow(Some(&json!([{ "action": "allow" }, { "action": "disallow", "os": { "name": other } }]))));
        // Feature-gated arguments (demo user, custom resolution, quick play) are never enabled implicitly.
        assert!(!rules_allow(Some(&json!([{ "action": "allow", "features": { "is_demo_user": true } }]))));
    }

    #[test]
    fn maven_coordinates_become_repository_paths() {
        assert_eq!(maven_path("net.fabricmc:fabric-loader:0.19.5").unwrap(), "net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar");
        assert_eq!(maven_path("org.lwjgl:lwjgl:3.3.3:natives-macos-arm64").unwrap(), "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-macos-arm64.jar");
        assert_eq!(maven_path("a.b:c:1@zip").unwrap(), "a/b/c/1/c-1.zip");
        assert!(maven_path("broken").is_none());
        assert_eq!(library_key("org.ow2.asm:asm:9.8"), "org.ow2.asm:asm");
        assert_eq!(library_key("org.lwjgl:lwjgl:3.3.3:natives-macos"), "org.lwjgl:lwjgl:natives-macos");
    }

    #[test]
    fn metadata_paths_cannot_escape_their_folder() {
        let base = Path::new("/data/minecraft/libraries");
        assert_eq!(safe_join(base, "a/b.jar").unwrap(), base.join("a/b.jar"));
        assert!(safe_join(base, "../../etc/passwd").is_err());
        assert!(safe_join(base, "a/../../b").is_err());
        assert!(safe_join(base, "/etc/passwd").is_err());
    }

    #[test]
    fn downloads_only_come_from_official_https_hosts() {
        assert!(check_host("https://piston-data.mojang.com/v1/objects/abc/client.jar").is_ok());
        assert!(check_host("https://maven.fabricmc.net/net/fabricmc/x.jar").is_ok());
        assert!(check_host("http://piston-data.mojang.com/x").is_err(), "plain HTTP");
        assert!(check_host("https://piston-data.mojang.com.evil.example/x").is_err(), "lookalike host");
        assert!(check_host("https://piston-data.mojang.com:8443/x").is_err(), "custom port");
        assert!(check_host("https://user@piston-data.mojang.com/x").is_err(), "credentials in URL");
        assert!(check_host("https://example.com/x").is_err());
    }

    #[test]
    fn files_sharing_a_stem_get_separate_partial_files() {
        let dir = Path::new("/rt/conf/security");
        let a = crate::jobs::part_path(&dir.join("java.policy"));
        let b = crate::jobs::part_path(&dir.join("java.security"));
        assert_ne!(a, b);
        assert_ne!(crate::jobs::part_path(Path::new("/rt/bin/java.exe")), crate::jobs::part_path(Path::new("/rt/bin/java.dll")));
        assert_eq!(a.parent(), Some(dir), "partial files stay beside their target so the rename is atomic");
    }

    #[test]
    fn played_sessions_need_time_or_a_clean_exit() {
        assert!(!counts_as_played(Some(false), Duration::from_secs(5)), "a crash during startup isn't a session");
        assert!(!counts_as_played(None, Duration::from_secs(5)), "stopped right away isn't a session");
        assert!(counts_as_played(Some(true), Duration::from_secs(5)), "a clean exit is");
        assert!(counts_as_played(Some(false), PLAYED_AFTER), "so is a crash after playing for a while");
    }

    #[test]
    fn cached_json_respects_its_age_limit() {
        let dir = TestDir::new();
        let path = dir.0.join("index.json");
        assert!(cached_json(&path, None).is_none());
        write_json(&path, &json!({ "mac-os-arm64": {} })).unwrap();
        assert!(cached_json(&path, Some(Duration::from_secs(60))).is_some());
        let old = std::time::SystemTime::now() - Duration::from_secs(7 * 3600);
        std::fs::File::options().write(true).open(&path).unwrap().set_modified(old).unwrap();
        assert!(cached_json(&path, Some(RUNTIME_INDEX_MAX_AGE)).is_none(), "stale copies need a refresh");
        assert!(cached_json(&path, None).is_some(), "but remain usable as an offline fallback");
        std::fs::write(&path, "[1,2]").unwrap();
        assert!(cached_json(&path, None).is_none(), "only JSON objects are accepted");
    }

    #[tokio::test]
    async fn runtime_manifest_is_reused_only_while_its_hash_matches() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let cache = dir.0.join("component/mac-os-arm64.manifest.json");
        std::fs::create_dir_all(cache.parent().unwrap()).unwrap();
        let text = r#"{"files":{}}"#;
        std::fs::write(&cache, text).unwrap();
        let sha = hex::encode(Sha1::digest(text.as_bytes()));
        // The URL is deliberately unusable: a matching cache must be served without any request.
        let manifest = runtime_manifest(&state, "https://example.invalid/m.json", Some(&sha), &cache).await.unwrap();
        assert!(manifest["files"].is_object());
        // A cached copy that no longer matches the index is ignored, so Flint goes back to the
        // (here refused) source instead of trusting it.
        std::fs::write(&cache, r#"{"files":{"tampered":{}}}"#).unwrap();
        assert!(runtime_manifest(&state, "https://example.invalid/m.json", Some(&sha), &cache).await.is_err());
    }

    #[test]
    fn download_errors_name_the_component_and_host() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let item = Download {
            url: "https://piston-data.mojang.com/v1/objects/abc/java.policy".into(),
            path: state.paths.runtimes().join("java-runtime-delta/mac-os-arm64/jre.bundle/Contents/Home/conf/security/java.policy"),
            sha1: None,
            size: None,
            executable: false,
        };
        let message = download_failure(&state, &item, "integrity check failed");
        assert!(message.contains("Java runtime file java.policy"), "{message}");
        assert!(message.contains("piston-data.mojang.com"), "{message}");
        assert!(message.contains("checksum"), "{message}");
        let asset = Download { path: state.paths.game().join("assets/objects/ab/abcdef"), ..item };
        assert_eq!(component_name(&state, &asset.path), "game asset");
    }

    fn jar(dir: &Path, bytes: &[u8]) -> Download {
        let path = dir.join("lib.jar");
        std::fs::write(&path, bytes).unwrap();
        Download { url: String::new(), path, sha1: Some(hex::encode(Sha1::digest(bytes))), size: Some(bytes.len() as u64), executable: false }
    }

    fn set_mtime(path: &Path, secs_ago: u64) {
        let t = std::time::SystemTime::now() - Duration::from_secs(secs_ago);
        std::fs::File::options().write(true).open(path).unwrap().set_modified(t).unwrap();
    }

    #[test]
    fn verified_jars_are_not_rehashed_until_they_change() {
        let dir = TestDir::new();
        let item = jar(&dir.0, b"original jar bytes");
        let mut verified = VerifiedFiles::new();
        assert!(!needs_download(&item, true, &mut verified, true), "a correct file passes");
        assert_eq!(verified.len(), 1, "and its result is remembered");

        // Same size and timestamp: trusted without hashing. (Corrupt it in place to prove no hash ran.)
        let before = std::fs::metadata(&item.path).unwrap().modified().unwrap();
        std::fs::write(&item.path, b"original jar BYTES").unwrap();
        std::fs::File::options().write(true).open(&item.path).unwrap().set_modified(before).unwrap();
        assert!(!needs_download(&item, true, &mut verified, true), "unchanged stamp: cached result reused");

        // Repair ignores the cache and catches it.
        assert!(needs_download(&item, true, &mut verified, false), "repair re-hashes and finds the corruption");
        assert!(verified.is_empty(), "a failed file is forgotten");
    }

    #[test]
    fn a_changed_timestamp_forces_a_new_hash() {
        let dir = TestDir::new();
        let item = jar(&dir.0, b"jar");
        let mut verified = VerifiedFiles::new();
        assert!(!needs_download(&item, true, &mut verified, true));
        std::fs::write(&item.path, b"JAR").unwrap();
        set_mtime(&item.path, 30);
        assert!(needs_download(&item, true, &mut verified, true), "modified file is hashed again and fails");
    }

    #[test]
    fn a_paused_check_stops_and_keeps_what_it_hashed() {
        let dir = TestDir::new();
        let cache = dir.0.join("verified.json");
        let item = jar(&dir.0, b"jar");
        let missing = Download { path: dir.0.join("missing.jar"), ..item.clone() };
        let cancel = AtomicBool::new(false);
        let pending = pending_downloads(vec![item.clone(), missing], &cache, false, &cancel, None::<fn(usize, usize)>).unwrap();
        assert_eq!(pending.len(), 1, "only the missing file is needed");
        assert_eq!(read_json::<VerifiedFiles>(&cache).len(), 1, "the hashed jar is remembered");
        cancel.store(true, Ordering::Relaxed);
        let paused = pending_downloads(vec![item], &cache, false, &cancel, None::<fn(usize, usize)>);
        assert_eq!(paused.err().as_deref(), Some("__cancelled__"));
    }

    #[test]
    fn size_mismatches_and_missing_files_always_need_a_download() {
        let dir = TestDir::new();
        let mut verified = VerifiedFiles::new();
        let mut item = jar(&dir.0, b"jar");
        item.size = Some(99);
        assert!(needs_download(&item, false, &mut verified, true));
        item.path = dir.0.join("missing.jar");
        assert!(needs_download(&item, false, &mut verified, true));
    }

    fn installed_with(version: Value, fabric: Option<Value>, logging_arg: Option<&str>) -> Installed {
        Installed {
            version,
            fabric,
            libraries: Vec::new(),
            client_jar: PathBuf::from("/game/versions/v/v.jar"),
            natives_dir: PathBuf::from("/game/versions/v/natives"),
            java: PathBuf::from("/runtimes/java"),
            logging_arg: logging_arg.map(str::to_owned),
        }
    }

    /// With the legacy policy (Automatic Optimization off, or a version that runs on Java older than
    /// 25), a launch passes Java exactly what Flint 0.4.0 passed (its argument assembly is spelled
    /// out here, independent of perfcore). Compact headers add one flag after 0.4.0's G1 set and
    /// change nothing else.
    #[test]
    fn jvm_arguments_are_byte_identical_to_flint_0_4_0() {
        let vars: HashMap<&str, String> = HashMap::from([
            ("natives_directory", "/game/versions/v/natives".to_owned()),
            ("launcher_name", "flint".to_owned()),
            ("launcher_version", "0.4.0".to_owned()),
            ("classpath", "/lib/a.jar:/game/versions/v/v.jar".to_owned()),
        ]);
        let mac = mojang_os() == "osx";
        let windows = mojang_os() == "windows";
        let x86 = std::env::consts::ARCH == "x86";
        let os_specific = |args: &[&str], include: bool| -> Vec<String> { if include { args.iter().map(|a| a.to_string()).collect() } else { Vec::new() } };
        let flint_0_4_0_head = |memory: u64| -> Vec<String> {
            let mut head = vec![format!("-Xmx{memory}M"), "-Dminecraft.launcher.brand=flint".into()];
            head.extend(
                ["-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:G1NewSizePercent=20", "-XX:G1ReservePercent=20", "-XX:MaxGCPauseMillis=50", "-XX:G1HeapRegionSize=32M"]
                    .map(str::to_owned),
            );
            head
        };
        let os_rules = json!([
            { "rules": [{ "action": "allow", "os": { "name": "osx" } }], "value": ["-XstartOnFirstThread"] },
            { "rules": [{ "action": "allow", "os": { "name": "windows" } }], "value": "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump" },
            { "rules": [{ "action": "allow", "os": { "arch": "x86" } }], "value": "-Xss1M" }
        ]);
        let os_expected = |out: &mut Vec<String>| {
            out.extend(os_specific(&["-XstartOnFirstThread"], mac));
            out.extend(os_specific(&["-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"], windows));
            out.extend(os_specific(&["-Xss1M"], x86));
        };
        let fabric = json!({ "id": "fabric-loader-0.19.5-v", "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient", "arguments": { "jvm": ["-DFabricMcEmu= net.minecraft.client.main.Main "], "game": [] } });
        let logging = "-Dlog4j.configurationFile=/game/assets/log_configs/client-1.21.2.xml";
        let unbounded = HardwareInfo { memory_mb: Some(65536), logical_cpus: Some(16), performance_cores: Some(12), efficiency_cores: Some(4), arch: "aarch64".into(), os: "macos".into() };

        // 26.3: the version JSON also recommends its own JVM settings, which Flint doesn't use.
        let mut jvm_26_3 = os_rules.as_array().unwrap().clone();
        jvm_26_3.extend(
            [
                "-XX:StackShadowPages=32",
                "--enable-native-access=ALL-UNNAMED",
                "--add-exports",
                "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Djava.library.path=${natives_directory}/java",
                "-Djna.tmpdir=${natives_directory}/jna",
                "-Dorg.lwjgl.system.SharedLibraryExtractPath=${natives_directory}/lwjgl",
                "-Dio.netty.native.workdir=${natives_directory}/netty",
                "-Dminecraft.launcher.brand=${launcher_name}",
                "-Dminecraft.launcher.version=${launcher_version}",
                "-cp",
                "${classpath}",
            ]
            .map(|a| json!(a)),
        );
        let v26_3 = json!({ "javaVersion": { "component": "java-runtime-epsilon", "majorVersion": 25 }, "arguments": {
            "default-user-jvm": [
                { "value": ["-Xms2G", "-Xmx4G", "-XX:+UseCompactObjectHeaders", "-XX:+AlwaysPreTouch", "-XX:+UseStringDeduplication"] },
                { "rules": [{ "action": "allow", "os": { "name": "osx" } }, { "action": "allow", "os": { "name": "linux" } }, { "action": "allow", "os": { "name": "windows", "versionRange": { "min": "10.0.17134" } } }], "value": ["-XX:+UseZGC"] },
                { "rules": [{ "action": "allow", "os": { "name": "windows", "versionRange": { "max": "10.0.17134" } } }], "value": ["-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC"] }
            ],
            "jvm": jvm_26_3,
            "game": []
        } });
        let mut expected = flint_0_4_0_head(4096);
        os_expected(&mut expected);
        expected.extend(
            [
                "-XX:StackShadowPages=32",
                "--enable-native-access=ALL-UNNAMED",
                "--add-exports",
                "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Djava.library.path=/game/versions/v/natives/java",
                "-Djna.tmpdir=/game/versions/v/natives/jna",
                "-Dorg.lwjgl.system.SharedLibraryExtractPath=/game/versions/v/natives/lwjgl",
                "-Dio.netty.native.workdir=/game/versions/v/natives/netty",
                "-Dminecraft.launcher.brand=flint",
                "-Dminecraft.launcher.version=0.4.0",
                "-cp",
                "/lib/a.jar:/game/versions/v/v.jar",
                "-DFabricMcEmu= net.minecraft.client.main.Main ",
                logging,
            ]
            .map(str::to_owned),
        );
        let installed = installed_with(v26_3, Some(fabric.clone()), Some(logging));
        let mut compact = expected.clone();
        let after_gc = compact.iter().position(|a| a == "-XX:G1HeapRegionSize=32M").unwrap() + 1;
        compact.insert(after_gc, "-XX:+UseCompactObjectHeaders".to_owned());
        for hardware in [unbounded.clone(), HardwareInfo::default()] {
            let policy = crate::perfcore::choose_policy(false, &hardware, &installed.version);
            assert_eq!(policy, JvmPolicy::Legacy);
            assert_eq!(jvm_args(&installed, policy, 4096, &vars), expected, "26.3, automatic optimization off");
            let policy = crate::perfcore::choose_policy(true, &hardware, &installed.version);
            assert_eq!(policy, JvmPolicy::CompactHeaders);
            assert_eq!(jvm_args(&installed, policy, 4096, &vars), compact, "26.3, automatic optimization on");
        }

        // 1.21.11: no recommendations in the version JSON; an old Windows-only pattern rule.
        let mut jvm_1_21 = os_rules.as_array().unwrap().clone();
        jvm_1_21.push(json!({ "rules": [{ "action": "allow", "os": { "name": "windows", "version": "^10\\." } }], "value": ["-Dos.name=Windows 10", "-Dos.version=10.0"] }));
        jvm_1_21.extend(
            [
                "-Djava.library.path=${natives_directory}",
                "-Djna.tmpdir=${natives_directory}",
                "-Dorg.lwjgl.system.SharedLibraryExtractPath=${natives_directory}",
                "-Dio.netty.native.workdir=${natives_directory}",
                "-Dminecraft.launcher.brand=${launcher_name}",
                "-Dminecraft.launcher.version=${launcher_version}",
                "-cp",
                "${classpath}",
            ]
            .map(|a| json!(a)),
        );
        let v1_21 = json!({ "javaVersion": { "component": "java-runtime-delta", "majorVersion": 21 }, "arguments": { "jvm": jvm_1_21, "game": [] } });
        let mut expected = flint_0_4_0_head(6144);
        os_expected(&mut expected);
        expected.extend(os_specific(&["-Dos.name=Windows 10", "-Dos.version=10.0"], windows));
        expected.extend(
            [
                "-Djava.library.path=/game/versions/v/natives",
                "-Djna.tmpdir=/game/versions/v/natives",
                "-Dorg.lwjgl.system.SharedLibraryExtractPath=/game/versions/v/natives",
                "-Dio.netty.native.workdir=/game/versions/v/natives",
                "-Dminecraft.launcher.brand=flint",
                "-Dminecraft.launcher.version=0.4.0",
                "-cp",
                "/lib/a.jar:/game/versions/v/v.jar",
                "-DFabricMcEmu= net.minecraft.client.main.Main ",
                logging,
            ]
            .map(str::to_owned),
        );
        let installed = installed_with(v1_21, Some(fabric), Some(logging));
        for automatic in [true, false] {
            let policy = crate::perfcore::choose_policy(automatic, &unbounded, &installed.version);
            assert_eq!(policy, JvmPolicy::Legacy, "Java 21 doesn't know the compact-headers flag");
            assert_eq!(jvm_args(&installed, policy, 6144, &vars), expected, "1.21.11, automatic={automatic}");
        }

        // 1.8.9 (vanilla, no `arguments`): natives and classpath are passed directly.
        let installed = installed_with(
            json!({ "javaVersion": { "component": "jre-legacy", "majorVersion": 8 }, "minecraftArguments": "--username ${auth_player_name}" }),
            None,
            None,
        );
        let mut expected = flint_0_4_0_head(2048);
        expected.extend(["-Djava.library.path=/game/versions/v/natives", "-cp", "/lib/a.jar:/game/versions/v/v.jar"].map(str::to_owned));
        for automatic in [true, false] {
            let policy = crate::perfcore::choose_policy(automatic, &unbounded, &installed.version);
            assert_eq!(policy, JvmPolicy::Legacy);
            assert_eq!(jvm_args(&installed, policy, 2048, &vars), expected, "1.8.9, automatic={automatic}");
        }
    }

    #[tokio::test]
    async fn recording_a_session_updates_only_that_profile() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let one = crate::data::Profile { id: "aaaaaaaaaaaa".into(), name: "One".into(), game_version: "26.3".into(), loader: "fabric".into(), ..Default::default() };
        let two = crate::data::Profile { id: "bbbbbbbbbbbb".into(), name: "Two".into(), ..one.clone() };
        crate::data::save_profiles(&state, &crate::data::ProfilesFile { selected: Some(one.id.clone()), profiles: vec![one.clone(), two.clone()] }).unwrap();
        update_profile(&state, &one.id, |p| p.last_played = Some(1_791_917_449)).await.unwrap();
        let saved = crate::data::load_profiles(&state);
        assert_eq!(saved.profiles[0].last_played, Some(1_791_917_449));
        assert_eq!(saved.profiles[1].last_played, None, "other profiles keep their own history");
    }

    #[test]
    fn downloaded_jars_are_remembered_as_verified() {
        let dir = TestDir::new();
        let jar = dir.0.join("lib.jar");
        std::fs::write(&jar, b"flint").unwrap();
        let sha1 = sha1_file(&jar).unwrap();
        let cache = dir.0.join("verified.json");
        remember_verified(&cache, &[(jar.clone(), sha1.clone())]);
        let mut verified: VerifiedFiles = read_json(&cache);
        let item = Download { url: String::new(), path: jar.clone(), sha1: Some(sha1), size: Some(5), executable: false };
        // Trusted: the remembered stamp matches, so the file isn't hashed again.
        assert!(!needs_download(&item, true, &mut verified, true));
        assert_eq!(verified.len(), 1);
        // A changed file no longer matches its stamp and is checked (and found wrong).
        std::fs::write(&jar, b"other").unwrap();
        assert!(needs_download(&item, true, &mut verified, true));
    }
}
