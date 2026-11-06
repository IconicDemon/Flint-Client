use crate::auth::{active_account, MICROSOFT_CLIENT_ID};
use crate::core::*;
use crate::data::{find_profile, load_settings, update_profile, Profile};
use crate::perfcore::JvmPolicy;
use crate::timing::Steps;
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
/// Fabric for Minecraft 1.8.9: Ornithe's meta lists the official Fabric Loader with its own intermediary for it.
const ORNITHE_META: &str = "https://meta.ornithemc.net/v3";
/// Exact hostnames only. Older versions (1.8.9 and earlier) still reference launchermeta and
/// launcher for asset indexes, logging configs and Java 8 files; Ornithe's maven has 1.8.9's
/// intermediary mappings for Fabric.
pub const ALLOWED_HOSTS: [&str; 8] = [
    "piston-data.mojang.com",
    "piston-meta.mojang.com",
    "launchermeta.mojang.com",
    "launcher.mojang.com",
    "libraries.minecraft.net",
    "resources.download.minecraft.net",
    "maven.fabricmc.net",
    "maven.ornithemc.net",
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
    rule_matches_on(rule, os_version())
}

fn rule_matches_on(rule: &Value, os_version: Option<&str>) -> bool {
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
        // 26.x's `default-user-jvm` picks its garbage collector by Windows build (ZGC from 10.0.17134, Mojang's G1 set
        // before it). Both entries matching would hand Java two collectors, which it refuses to start with; an unknown
        // system version matches neither, so Java keeps its own default.
        if let Some(range) = os.get("versionRange") {
            let Some(current) = os_version else { return false };
            if range["min"].as_str().is_some_and(|min| version_cmp(current, min).is_lt()) {
                return false;
            }
            if range["max"].as_str().is_some_and(|max| version_cmp(current, max).is_ge()) {
                return false;
            }
        }
    }
    true
}

/// Dotted versions compared number by number ("10.0.9999" < "10.0.17134").
fn version_cmp(a: &str, b: &str) -> std::cmp::Ordering {
    let parts = |v: &str| v.split('.').map(|p| p.trim().parse::<u64>().unwrap_or(0)).collect::<Vec<_>>();
    let (a, b) = (parts(a), parts(b));
    for i in 0..a.len().max(b.len()) {
        let order = a.get(i).copied().unwrap_or(0).cmp(&b.get(i).copied().unwrap_or(0));
        if order.is_ne() {
            return order;
        }
    }
    std::cmp::Ordering::Equal
}

/// The system's version as Mojang's rules compare it ("10.0.26300" on Windows, "15.1" on macOS), read once per run.
fn os_version() -> Option<&'static str> {
    static VERSION: std::sync::OnceLock<Option<String>> = std::sync::OnceLock::new();
    VERSION
        .get_or_init(|| {
            #[cfg(windows)]
            {
                use std::os::windows::process::CommandExt;
                let out = std::process::Command::new("cmd").args(["/c", "ver"]).creation_flags(0x0800_0000).output().ok()?;
                let text = String::from_utf8_lossy(&out.stdout).into_owned();
                let number = text.split("Version ").nth(1)?.trim().trim_end_matches(']').to_owned();
                // "10.0.26300.7171": Mojang's ranges name major.minor.build.
                Some(number.split('.').take(3).collect::<Vec<_>>().join("."))
            }
            #[cfg(target_os = "macos")]
            {
                crate::data::sysctl(&["kern.osproductversion"]).map(|v| v.trim().to_owned())
            }
            #[cfg(not(any(windows, target_os = "macos")))]
            {
                None
            }
        })
        .as_deref()
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

pub(crate) fn maven_path(name: &str) -> Option<String> {
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

/// The Java a launch uses: Mojang's runtime component, the oldest major version a custom Java in Settings may be, and the
/// component to use where Mojang has no build of the first for this computer. Flint's choice for the line
/// (`runtime::java_override`) when it has one, otherwise the version file's.
fn java_for(version: &Value, flint: Option<crate::runtime::JavaChoice>) -> (&str, Option<u64>, Option<&str>) {
    match flint {
        Some(choice) => (choice.component, Some(choice.minimum), Some(choice.fallback)),
        None => (version["javaVersion"]["component"].as_str().unwrap_or("jre-legacy"), version["javaVersion"]["majorVersion"].as_u64(), None),
    }
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

/// Where a game version's Fabric comes from. Official Fabric's meta doesn't list Minecraft 1.8.9; Ornithe's does: the
/// official Fabric Loader with Ornithe's (Calamus, generation 2) intermediary. The two metas answer in the same format.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum LoaderSource {
    Fabric,
    Ornithe,
}

impl LoaderSource {
    fn for_game(game: &str) -> Self {
        if game == "1.8.9" {
            LoaderSource::Ornithe
        } else {
            LoaderSource::Fabric
        }
    }

    fn loaders_url(self, game: &str) -> String {
        match self {
            LoaderSource::Fabric => format!("{FABRIC_META}/versions/loader/{game}"),
            LoaderSource::Ornithe => format!("{ORNITHE_META}/versions/fabric-loader/{game}"),
        }
    }

    fn profile_url(self, game: &str, loader: &str) -> String {
        match self {
            LoaderSource::Fabric => format!("{FABRIC_META}/versions/loader/{game}/{loader}/profile/json"),
            LoaderSource::Ornithe => format!("{ORNITHE_META}/versions/fabric-loader/{game}/{loader}/profile/json"),
        }
    }

    /// The profile's folder under `versions/`: the profile's own id, so an Ornithe profile never shares an official one's.
    fn profile_dir(self, game: &str, loader: &str) -> String {
        match self {
            LoaderSource::Fabric => format!("fabric-loader-{loader}-{game}"),
            LoaderSource::Ornithe => format!("fabric-loader-{loader}-{game}-ornithe-gen2"),
        }
    }

    fn name(self) -> &'static str {
        match self {
            LoaderSource::Fabric => "Fabric",
            LoaderSource::Ornithe => "Ornithe (Fabric for 1.8.9)",
        }
    }

    /// The version a profile may build on. Ornithe names its own copy of vanilla 1.8.9 (`1.8.9-vanilla`, which swaps in
    /// Legacy Fabric's LWJGL builds); Flint runs it on Mojang's own 1.8.9, which Fabric Loader runs as is.
    fn check_inherits(self, profile: &Value, game: &str) -> CmdResult<()> {
        let parent = profile["inheritsFrom"].as_str().unwrap_or_default();
        let ok = parent == game || (self == LoaderSource::Ornithe && parent == format!("{game}-vanilla"));
        if ok {
            Ok(())
        } else {
            Err(format!("{} returned a profile for another Minecraft version", self.name()))
        }
    }
}

/// A maven's `.sha1` file: the 40 hex digits it starts with (some mavens add the file name after them).
fn parse_maven_sha1(text: &str) -> Option<String> {
    let first = text.split_whitespace().next()?.to_ascii_lowercase();
    (first.len() == 40 && first.chars().all(|c| c.is_ascii_hexdigit())).then_some(first)
}

/// The jar URLs of the profile's libraries that list no sha1 (index, URL). Fabric Loader and Ornithe's intermediary are
/// listed without one; their mavens publish `<jar>.sha1` beside each jar.
fn libraries_missing_sha1(profile: &Value, default_maven: &str) -> CmdResult<Vec<(usize, String)>> {
    let mut out = Vec::new();
    for (i, lib) in profile["libraries"].as_array().into_iter().flatten().enumerate() {
        if lib["sha1"].as_str().is_some_and(|s| parse_maven_sha1(s).is_some()) {
            continue;
        }
        let name = lib["name"].as_str().ok_or("Bad Fabric library")?;
        let rel = maven_path(name).ok_or("Bad Fabric library name")?;
        let base = lib["url"].as_str().unwrap_or(default_maven);
        out.push((i, format!("{}/{rel}", base.trim_end_matches('/'))));
    }
    Ok(out)
}

/// Gives every library of a freshly downloaded loader profile a sha1 before the profile is kept, so each jar is checked
/// on download and on every later check, like Mojang's files.
async fn fill_library_hashes(state: &AppState, profile: &mut Value) -> CmdResult<()> {
    for (i, url) in libraries_missing_sha1(profile, "https://maven.fabricmc.net/")? {
        let text = fetch_verified_text(state, &format!("{url}.sha1"), None, "Could not check a Fabric library").await?;
        let sha1 = parse_maven_sha1(&text).ok_or("A Fabric library's published checksum is malformed")?;
        profile["libraries"][i]["sha1"] = json!(sha1);
    }
    Ok(())
}

async fn fabric_profile(state: &AppState, game: &str, pinned: Option<&str>) -> CmdResult<(Value, String)> {
    let source = LoaderSource::for_game(game);
    let loader = match pinned {
        Some(v) => v.to_owned(),
        None => {
            let loaders = get_json(&state.http, &source.loaders_url(game), &format!("Could not reach {}", source.name())).await?;
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
        .join(source.profile_dir(game, &loader))
        .join("profile.json");
    if let Some(value) = read_json_opt::<Value>(&path) {
        return Ok((value, loader));
    }
    let mut value = get_json(&state.http, &source.profile_url(game, &loader), &format!("Could not load the {} profile", source.name())).await?;
    if value["mainClass"].as_str().is_none() {
        return Err(format!("{} returned an incomplete profile", source.name()));
    }
    source.check_inherits(&value, game)?;
    fill_library_hashes(state, &mut value).await?;
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
/// network request. An older one is used as it is too, and refreshed in the background for the
/// next launch, so Play never waits on Mojang for a list it already has (a runtime Mojang has
/// since replaced still runs the game; the next launch moves to the new one). Only a missing or
/// unreadable copy is fetched before going on. The index only points at manifests, which are
/// verified by SHA-1 before use.
async fn runtime_index(state: &AppState) -> CmdResult<Value> {
    let path = state.paths.runtimes().join("index.json");
    if let Some(index) = cached_json(&path, Some(RUNTIME_INDEX_MAX_AGE)) {
        return Ok(index);
    }
    if let Some(index) = cached_json(&path, None) {
        refresh_runtime_index(state.http.clone(), path);
        return Ok(index);
    }
    let index = get_json(&state.http, RUNTIME_INDEX, "Could not reach Mojang's Java runtime list").await?;
    let _ = write_json(&path, &index);
    Ok(index)
}

/// Fetches a newer runtime index into `path` in the background (one fetch at a time); a failure keeps the old copy.
fn refresh_runtime_index(http: reqwest::Client, path: PathBuf) {
    static REFRESHING: AtomicBool = AtomicBool::new(false);
    if REFRESHING.swap(true, Ordering::SeqCst) {
        return;
    }
    tauri::async_runtime::spawn(async move {
        if let Ok(index) = get_json(&http, RUNTIME_INDEX, "Could not reach Mojang's Java runtime list").await {
            if index.is_object() {
                let _ = write_json(&path, &index);
            }
        }
        REFRESHING.store(false, Ordering::SeqCst);
    });
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
    fallback: Option<&str>,
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
    // Where Mojang has no build of the Java Flint picked for this line, the line's own (runtime::JAVA_FOR_LINE).
    let component = match fallback {
        Some(other) if runtime_platform(&index, component).is_none() && runtime_platform(&index, other).is_some() => other,
        _ => component,
    };
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

/// The sizes of the files in the folders that hold many of a check's files, each folder read with one listing instead
/// of one query per file. On Windows a listing carries every file's size, so the ~4,000 assets of a version are checked
/// in a few milliseconds instead of a few hundred; elsewhere it costs about what the queries did. Symbolic links and
/// folders that can't be listed are left to the per-file check.
#[derive(Default)]
struct Listed {
    /// Each folder listed whole, with its files' sizes by name: a file not named there is missing.
    folders: HashMap<PathBuf, HashMap<String, u64>>,
    /// Outside Windows, each listed folder's names in lower case: a name there only in another case may be the same file
    /// (macOS usually ignores case, Linux doesn't), so the per-file check decides it.
    folded: HashMap<PathBuf, std::collections::HashSet<String>>,
}

impl Listed {
    /// Folders with fewer files to check than this are checked file by file.
    const MIN_FILES: usize = 8;

    fn read<'a>(paths: impl Iterator<Item = &'a Path>) -> Self {
        let mut per_folder: HashMap<&Path, usize> = HashMap::new();
        for path in paths {
            if let Some(parent) = path.parent() {
                *per_folder.entry(parent).or_default() += 1;
            }
        }
        let mut listed = Listed::default();
        for (folder, count) in per_folder {
            if count < Self::MIN_FILES {
                continue;
            }
            let entries = match std::fs::read_dir(folder) {
                Ok(entries) => entries,
                // Not there yet (a first install): every file in it is missing.
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                    listed.folders.insert(folder.to_owned(), HashMap::new());
                    continue;
                }
                Err(_) => continue,
            };
            let mut sizes = HashMap::new();
            let mut whole = true;
            for entry in entries {
                let Ok(entry) = entry else {
                    whole = false;
                    break;
                };
                match entry.file_type() {
                    Ok(kind) if kind.is_file() => match entry.metadata() {
                        Ok(meta) => {
                            sizes.insert(Self::key(&entry.file_name()), meta.len());
                        }
                        Err(_) => whole = false,
                    },
                    // A link (followed by the per-file check) or anything else unexpected: checked file by file.
                    Ok(kind) if kind.is_dir() => {}
                    _ => whole = false,
                }
            }
            if whole {
                if !cfg!(windows) {
                    listed.folded.insert(folder.to_owned(), sizes.keys().map(|name| name.to_lowercase()).collect());
                }
                listed.folders.insert(folder.to_owned(), sizes);
            }
        }
        listed
    }

    /// `Some(size)` for a file of a listed folder (`Some(None)`: missing); `None` when the per-file check must decide (its
    /// folder wasn't listed, or only another case of its name is there).
    fn size(&self, path: &Path) -> Option<Option<u64>> {
        let folder = path.parent()?;
        let sizes = self.folders.get(folder)?;
        let name = path.file_name()?;
        if let Some(size) = sizes.get(&Self::key(name)) {
            return Some(Some(*size));
        }
        if self.folded.get(folder).is_some_and(|names| names.contains(&name.to_string_lossy().to_lowercase())) {
            return None;
        }
        Some(None)
    }

    /// A file name as compared: on Windows, whatever its case.
    fn key(name: &std::ffi::OsStr) -> String {
        let text = name.to_string_lossy();
        if cfg!(windows) {
            text.to_lowercase()
        } else {
            text.into_owned()
        }
    }
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
    // Settings → Storage → "Files downloaded at once"; each file is still checked against its SHA-1.
    let parallel = crate::storage::download_limit(&load_settings(state));
    // The largest files first (Java's 66 MB module image, the client jar), so they download beside the thousands of small
    // assets instead of after them, where one connection would be left fetching the last big file alone.
    let mut items = items;
    items.sort_by_key(|item| std::cmp::Reverse(item.size.unwrap_or(0)));
    let result = futures_util::stream::iter(items.into_iter().map(Ok::<Download, String>))
        .try_for_each_concurrent(parallel, |item| {
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

/// What `extract_natives` put in a natives folder (`.flint-natives.json` there), by natives jar: the jar as it was (size,
/// modification time) and every file it gave, with theirs. While the jar and those files are unchanged, a launch doesn't
/// open the jar at all (1.8.9 unpacks about 10 MB of libraries from it).
#[derive(serde::Serialize, serde::Deserialize, Clone, PartialEq, Debug, Default)]
struct NativesRecord {
    jar: (u64, u128),
    files: Vec<(String, u64, u128)>,
}

const NATIVES_RECORD: &str = ".flint-natives.json";

fn file_stamp(path: &Path) -> Option<(u64, u128)> {
    let meta = std::fs::metadata(path).ok().filter(std::fs::Metadata::is_file)?;
    Some((meta.len(), modified_nanos(&meta)?))
}

/// Unpacks a natives jar into `dest`, skipping it when the record says it is already there (`trust_record`; a repair
/// doesn't trust it) and leaving alone each file already in place byte for byte.
fn extract_natives(jar: &Path, dest: &Path, exclude: &[String], trust_record: bool) -> CmdResult<()> {
    let record_path = dest.join(NATIVES_RECORD);
    let key = jar.to_string_lossy().into_owned();
    let jar_stamp = file_stamp(jar);
    if trust_record {
        let records: HashMap<String, NativesRecord> = read_json(&record_path);
        if let (Some(record), Some(stamp)) = (records.get(&key), jar_stamp) {
            if record.jar == stamp && record.files.iter().all(|(name, size, modified)| file_stamp(&dest.join(name)) == Some((*size, *modified))) {
                return Ok(());
            }
        }
    }
    let mut given = Vec::new();
    let file = std::fs::File::open(jar).map_err(err("Could not open natives"))?;
    let mut archive = zip::ZipArchive::new(std::io::BufReader::new(file)).map_err(err("Could not read natives"))?;
    std::fs::create_dir_all(dest).map_err(err("Could not create the natives folder"))?;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| e.to_string())?;
        let name = entry.name().to_owned();
        if entry.is_dir() || exclude.iter().any(|x| name.starts_with(x.as_str())) {
            continue;
        }
        let Some(relative) = entry.enclosed_name() else { continue };
        let out = dest.join(&relative);
        given.push(relative.to_string_lossy().into_owned());
        // A native already in place, byte for byte, is left alone: rewriting it at every launch cost a virus scan of
        // each library, and failed while another game of this version had it loaded.
        if std::fs::metadata(&out).is_ok_and(|meta| meta.is_file() && meta.len() == entry.size()) {
            let mut bytes = Vec::with_capacity(usize::try_from(entry.size()).unwrap_or(0));
            std::io::Read::read_to_end(&mut entry, &mut bytes).map_err(|e| e.to_string())?;
            if std::fs::read(&out).is_ok_and(|current| current == bytes) {
                continue;
            }
            std::fs::write(&out, &bytes).map_err(|e| e.to_string())?;
            continue;
        }
        if let Some(parent) = out.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let mut target = std::fs::File::create(&out).map_err(|e| e.to_string())?;
        std::io::copy(&mut entry, &mut target).map_err(|e| e.to_string())?;
    }
    if let Some(stamp) = jar_stamp {
        let files: Option<Vec<(String, u64, u128)>> = given.into_iter().map(|name| file_stamp(&dest.join(&name)).map(|(size, modified)| (name, size, modified))).collect();
        if let Some(files) = files {
            let mut records: HashMap<String, NativesRecord> = read_json(&record_path);
            let record = NativesRecord { jar: stamp, files };
            if records.get(&key) != Some(&record) {
                records.insert(key, record);
                let _ = write_json(&record_path, &records);
            }
        }
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

async fn install_inner(app: &AppHandle, state: &AppState, profile: &Profile, cancel: &Arc<AtomicBool>, deep: bool, steps: Option<&Steps>) -> CmdResult<Installed> {
    let plan = until_cancelled(cancel, make_plan(app, state, profile, cancel, deep, false, steps)).await?;
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
    step(steps, "download");

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
            extract_natives(&lib.path, &installed.natives_dir, exclude, !deep)?;
        }
    }
    std::fs::create_dir_all(state.paths.profile_dir(id).join("mods")).map_err(err("Could not create the profile folder"))?;
    if !installed.java.exists() {
        return Err("The Java runtime didn't install correctly.".into());
    }
    step(steps, "natives");
    Ok(installed)
}

fn step(steps: Option<&Steps>, name: &'static str) {
    if let Some(steps) = steps {
        steps.step(name);
    }
}

/// `quiet`: a background size probe that shows no "Checking" state (see `profile_check`).
async fn make_plan(app: &AppHandle, state: &AppState, profile: &Profile, cancel: &Arc<AtomicBool>, deep: bool, quiet: bool, steps: Option<&Steps>) -> CmdResult<Plan> {
    let id = profile.id.as_str();
    let game = state.paths.game();
    if !quiet {
        emit_stage(app, state, id, "checking", "validate", "Validating version");
    }
    let version = vanilla_version(state, &profile.game_version).await?;
    step(steps, "version file");
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
    step(steps, "loader profile");

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

    step(steps, "libraries");
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

    step(steps, "asset index");
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

    // The platform check stays on the version file's Java: 1.8.9's LWJGL 2 natives are x86_64 whichever Java runs them.
    check_platform(&profile.game_version, version["javaVersion"]["component"].as_str().unwrap_or("jre-legacy"))?;
    let (component, required_major, fallback) = java_for(&version, crate::runtime::java_override(crate::runtime::enabled(state), profile));
    let mut links = Vec::new();
    let java = java_runtime(state, &profile.game_version, component, fallback, required_major, &mut downloads, &mut links).await?;
    cancelled(cancel)?;
    step(steps, "java runtime list");

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
    step(steps, "check files");
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
    let unchanged = verified.clone();
    let total = downloads.len();
    let started = std::time::Instant::now();
    let mut last_report = started;
    let mut pending = Vec::new();
    let mut stopped = false;
    let is_jar = |item: &Download| item.path.extension().is_some_and(|e| e == "jar");
    // Files only compared by size (the thousands of assets) are looked up in their folder's listing.
    let listed = if deep { Listed::default() } else { Listed::read(downloads.iter().filter(|d| !is_jar(d)).map(|d| d.path.as_path())) };
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
        let deep_check = deep || is_jar(&item);
        if !deep_check {
            if let Some(found) = listed.size(&item.path) {
                // Missing, or not the size the version lists.
                if found.is_none() || item.size.is_some_and(|s| Some(s) != found) {
                    pending.push(item);
                }
                continue;
            }
        }
        // Repair (`deep`) re-hashes everything instead of trusting earlier results.
        if needs_download(&item, deep_check, &mut verified, !deep) {
            pending.push(item);
        }
    }
    // Results so far are kept even when paused, so the next check doesn't hash those files again. A check that
    // learned nothing new leaves the file alone.
    if verified != unchanged {
        let _ = write_json(cache, &verified);
    }
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
        let plan = until_cancelled(&cancel, make_plan(&app, &state, &profile, &cancel, false, quiet, None)).await?;
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
    // Checked under the lock a cleanup reads after it starts (storage.rs `busy_reason`), so one of the two always waits.
    if crate::storage::cleaning() {
        return Err("Flint is cleaning up its files (Settings → Storage). Try again in a moment.".into());
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
        crate::troubleshoot::record_profile(state, id, "profile", error);
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
    let steps = Steps::new();
    // A new profile's performance set is looked up on Modrinth while the game's files download.
    crate::content::prefetch_performance_set(&app, &id);
    let result = async {
        install_inner(&app, &state, &profile, &cancel, repair.unwrap_or(false), Some(&steps)).await?;
        emit_stage(&app, &state, &id, "installing", "finalize", "Finalizing");
        update_profile(&state, &id, |p| p.installed = true).await?;
        crate::content::apply_performance_set(&app, &state, &id).await;
        steps.step("performance set");
        Ok::<_, String>(())
    }
    .await;
    // Released before the final state is shown, so a click on Play the moment it appears works.
    drop(reservation);
    match result {
        Ok(()) => {
            // Where an install's time went, for the debug log (as Play's "Play to Java" line).
            eprintln!("[flint] Install of Minecraft {}: {}", profile.game_version, steps.summary());
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
    let runtime_on = crate::runtime::enabled(&state);
    let mut out = serde_json::Map::new();
    for profile in profiles.profiles {
        let value = live.get(&profile.id).cloned().unwrap_or_else(|| {
            let dir = state.paths.game().join("versions").join(&profile.game_version);
            let jar = dir.join(format!("{}.jar", profile.game_version));
            let version = read_json_opt::<Value>(&dir.join(format!("{}.json", profile.game_version))).unwrap_or_default();
            let (component, _, fallback) = java_for(&version, crate::runtime::java_override(runtime_on, &profile));
            let java_ready = custom_java || state.paths.runtimes().join(component).is_dir() || fallback.is_some_and(|f| state.paths.runtimes().join(f).is_dir());
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

/// Most extra Java arguments Settings → Java and memory takes, and the longest text.
const EXTRA_JVM_ARGS_MAX: usize = 64;
const EXTRA_JVM_TEXT_MAX: usize = 4096;

/// Settings → Java and memory → "Extra Java arguments", split like a command line (double quotes keep spaces
/// together). Only JVM options are taken, and none that would replace what Flint itself sets for every
/// launch (the heap is the Memory setting; the classpath and native libraries belong to the version) or
/// that make Java print something and exit instead of starting Minecraft.
pub fn parse_extra_jvm_args(text: &str) -> Result<Vec<String>, String> {
    if text.len() > EXTRA_JVM_TEXT_MAX {
        return Err("That's too long for Java arguments.".into());
    }
    let mut args = Vec::new();
    let mut current = String::new();
    let (mut quoted, mut started) = (false, false);
    for c in text.chars() {
        match c {
            '"' => {
                quoted = !quoted;
                started = true;
            }
            c if c.is_whitespace() && !quoted => {
                if started {
                    args.push(std::mem::take(&mut current));
                    started = false;
                }
            }
            c if c.is_control() => return Err("Java arguments can't contain control characters.".into()),
            c => {
                current.push(c);
                started = true;
            }
        }
    }
    if quoted {
        return Err("A quote isn't closed.".into());
    }
    if started {
        args.push(current);
    }
    if args.len() > EXTRA_JVM_ARGS_MAX {
        return Err(format!("Use at most {EXTRA_JVM_ARGS_MAX} Java arguments."));
    }
    for arg in &args {
        let lower = arg.to_ascii_lowercase();
        if !arg.starts_with('-') || arg.len() < 2 {
            return Err(format!("“{arg}” isn't a Java option. Each one starts with a dash, like -XX:+UseG1GC."));
        }
        if lower.starts_with("-xmx") || lower.starts_with("-xms") || lower.starts_with("-xx:maxheapsize") || lower.starts_with("-xx:initialheapsize") || lower.starts_with("-xx:maxrampercentage") {
            return Err(format!("{arg} sets Java's memory, which Flint takes from Memory in Settings → Java and memory. Change it there."));
        }
        if matches!(lower.as_str(), "-cp" | "-classpath" | "--class-path" | "-jar") || lower.starts_with("-djava.library.path") || lower.starts_with("--class-path=") {
            return Err(format!("{arg} would replace the files Minecraft runs from, so Flint doesn't take it."));
        }
        if matches!(lower.as_str(), "-version" | "--version" | "-help" | "--help" | "-h" | "-?" | "--dry-run" | "-x" | "--list-modules") {
            return Err(format!("{arg} makes Java exit instead of starting Minecraft."));
        }
    }
    Ok(args)
}

/// Checks extra Java arguments the way a launch does; the arguments as they will be used (Settings uses
/// `javaplan::java_args_review`, which also says what they change).
#[tauri::command]
pub fn java_args_check(text: String) -> CmdResult<Vec<String>> {
    parse_extra_jvm_args(&text)
}

/// Java's arguments up to the main class: the heap and tuning flags (Flint Performance Core),
/// Flint's brand, the player's extra arguments (after Flint's, so a flag set twice takes theirs), the
/// version's and the loader's own arguments (classpath included) and the logging config.
fn jvm_args(installed: &Installed, policy: JvmPolicy, memory_mb: u64, extra: &[String], vars: &HashMap<&str, String>) -> Vec<String> {
    let version = &installed.version;
    let mut args = crate::perfcore::heap_args(memory_mb);
    args.push("-Dminecraft.launcher.brand=flint".to_owned());
    match recommended_jvm(version, vars, memory_mb, extra) {
        Some(own) => args.extend(own),
        None => args.extend(crate::perfcore::tuning_args_with(policy, extra)),
    }
    args.extend(extra.iter().cloned());
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

/// The Java settings the official Minecraft Launcher gives a version that recommends its own (`arguments.default-user-jvm`):
/// on 26.x, ZGC with compact object headers, a pre-touched heap and string deduplication on Windows 10 1803+, macOS and
/// Linux, Mojang's G1 set on older Windows. Used since 2026-11-03 instead of Flint's G1 set with compact headers, after
/// garbage-collector crashes in G1 on Java 25 (Mojang's 25.0.1 has a G1 bug, JDK-8377811); the official launcher doesn't
/// run 26.x on G1. The heap's ceiling stays the profile's memory setting; Mojang's starting size is kept when it fits
/// under it. A collector the player chose in the extra arguments replaces Mojang's.
fn recommended_jvm(version: &Value, vars: &HashMap<&str, String>, memory_mb: u64, extra: &[String]) -> Option<Vec<String>> {
    let list = version["arguments"].get("default-user-jvm")?;
    let mut args = Vec::new();
    collect_args(Some(list), vars, &mut args);
    let collector_flag = |a: &str| a == "-XX:+UseZGC" || a == "-XX:+UseG1GC" || a.starts_with("-XX:G1") || a.starts_with("-XX:MaxGCPauseMillis");
    let player_collector = crate::perfcore::chosen_collector(extra).is_some() || extra.iter().any(|a| a == "-XX:+UseG1GC");
    args.retain(|a| {
        if a.starts_with("-Xmx") || (player_collector && collector_flag(a)) {
            return false;
        }
        match a.strip_prefix("-Xms") {
            Some(size) => heap_mb(size).is_some_and(|mb| mb <= memory_mb),
            None => true,
        }
    });
    Some(args)
}

/// A Java heap size ("2G", "512M", "1024m") in megabytes.
fn heap_mb(size: &str) -> Option<u64> {
    let (number, unit) = size.split_at(size.len().checked_sub(1)?);
    let number: u64 = number.parse().ok()?;
    match unit.to_ascii_lowercase().as_str() {
        "g" => Some(number * 1024),
        "m" => Some(number),
        "k" => Some(number / 1024),
        _ => size.parse::<u64>().ok().map(|bytes| bytes / (1024 * 1024)),
    }
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

/// The profile whose Play the player most likely presses first: the selected one, else the last played.
fn likely_first_play(file: &crate::data::ProfilesFile) -> Option<&Profile> {
    file.selected
        .as_deref()
        .and_then(|id| file.profiles.iter().find(|p| p.id == id))
        .or_else(|| file.profiles.iter().filter(|p| p.last_played.is_some()).max_by_key(|p| p.last_played))
}

/// Readies, in the background when Flint opens, what the session's first Play would otherwise wait for: that profile's mods
/// are read once (Play checks them before Java starts, and the first read of each jar in a session can take half a second
/// while antivirus software looks at it; later reads come from memory until a jar changes), Flint's in-game runtime for it
/// is unpacked and its copy in the profile compared once (`runtime::warm`), and the active account is signed in again
/// where that never asks the player anything (`auth::renew_ahead`).
pub fn prepare_first_play(app: &AppHandle) {
    let handle = app.clone();
    tauri::async_runtime::spawn(async move {
        let _ = crate::data::blocking(&handle, |state| {
            let file = crate::data::load_profiles(state);
            if let Some(profile) = likely_first_play(&file).filter(|p| p.loader == "fabric") {
                crate::perfcore::scan_mods(&state.paths.profile_dir(&profile.id).join("mods"));
                crate::runtime::warm(state, profile);
            }
        })
        .await;
    });
    crate::auth::renew_ahead(app);
}

#[tauri::command]
pub async fn profile_launch(app: AppHandle, state: State<'_, AppState>, id: String) -> CmdResult<()> {
    let result = launch(&app, &state, &id, None).await;
    result.map(|_| ()).map_err(|e| settle(&app, &state, &id, e))
}

/// Launches a profile and joins a partner server the player confirmed (partners/play.rs): the server goes into the
/// profile's list once the profile is ready, just before Java starts, and the game is told to join it.
pub async fn launch_joining(app: &AppHandle, state: &AppState, id: &str, join: &crate::partners::play::Join) -> CmdResult<crate::partners::play::Listing> {
    let result = launch(app, state, id, Some(join)).await;
    match result {
        Ok(listing) => Ok(listing.unwrap_or_default()),
        Err(e) => Err(settle(app, state, id, e)),
    }
}

async fn launch(app: &AppHandle, state: &AppState, id: &str, join: Option<&crate::partners::play::Join>) -> CmdResult<Option<crate::partners::play::Listing>> {
    let (app, state, id) = (app.clone(), state, id.to_owned());
    let steps = Steps::new();
    let profile = find_profile(state, &id)?;
    let account = active_account(state).ok_or("Connect a Minecraft account to play.")?;
    crate::content::launch_preflight(state, &id)?;
    let cancel = begin_job(state, &id)?;
    steps.step("preflight");
    // Held until the game process is registered as running, so repeated clicks can't launch twice.
    let reservation = JobReservation { app: &app, state, id: id.clone() };
    // Signing in (a token refresh is a few network round trips) runs beside the file check instead of after it.
    let sign_in = async {
        if account.qa {
            Ok(("0".to_owned(), true))
        } else {
            crate::auth::minecraft_token_explicit(&app, state, &account.uuid).await.map(|token| (token, false))
        }
    };
    // An installed profile whose performance set is settled has its own folder readied (Flint's runtime, Auto Sync)
    // while the game's shared files are checked: the two touch different files. A first launch does it after the
    // install and the performance set, which may add mods.
    let settled = profile.installed && profile.performance.as_deref() != Some("pending");
    if !settled {
        // Looked up on Modrinth while the game's files are checked or downloaded; installed after them.
        crate::content::prefetch_performance_set(&app, &id);
    }
    let ready_folder = async {
        if settled {
            prepare_folder(&app, state, &profile, &steps).await.map(Some)
        } else {
            Ok(None)
        }
    };
    let (installed, signed_in, folder) = tokio::join!(install_inner(&app, state, &profile, &cancel, false, Some(&steps)), sign_in, ready_folder);
    let installed = installed.map_err(|e| fail(&app, state, &id, &e))?;
    let tuning = folder.map_err(|e| fail(&app, state, &id, &e))?;
    let (profile, tuning) = if let Some(tuning) = tuning {
        (profile, tuning)
    } else {
        if !profile.installed {
            update_profile(state, &id, |p| p.installed = true).await?;
        }
        crate::content::apply_performance_set(&app, state, &id).await;
        // Read again: the copy loaded above predates the performance set.
        let profile = find_profile(state, &id).map_err(|e| fail(&app, state, &id, &e))?;
        steps.step("performance set");
        let tuning = prepare_folder(&app, state, &profile, &steps).await.map_err(|e| fail(&app, state, &id, &e))?;
        (profile, tuning)
    };
    // A mod copied in from another launcher may need a library that launcher had: added now, from Modrinth.
    crate::content::add_missing_libraries(&app, state, &id).await;
    // The performance set and libraries may have added mods; make sure they're all present and switched on, and that
    // nothing Fabric would refuse to start without is missing.
    crate::content::launch_preflight(state, &id).map_err(|e| fail(&app, state, &id, &e))?;
    crate::content::dependency_check(state, &id).map_err(|e| fail(&app, state, &id, &e))?;
    steps.step("mods check");

    emit(&app, state, &id, json!({ "state": "launching", "label": "Starting Minecraft" }));
    let (token, demo) = signed_in.map_err(|e| fail(&app, state, &id, &e))?;

    let game_dir = state.paths.profile_dir(&id);
    let extra = parse_extra_jvm_args(load_settings(state)["jvmArgs"].as_str().unwrap_or_default())
        .map_err(|e| fail(&app, state, &id, &format!("The extra Java arguments in Settings → Java and memory can't be used: {e}")))?;
    // Java's start-up cache (see jvmcache.rs): Fabric profiles on Java 25+ run from an unsigned copy of the client jar, which
    // the cache needs. If the copy can't be made, the launch goes ahead as before, without the cache.
    let java_major = crate::jvmcache::java_major(&installed.java);
    let start_cache = crate::jvmcache::applies(&profile, java_major, &extra, load_settings(state)["javaStartCache"].as_bool() == Some(true));
    let client_jar = if start_cache {
        let (root, original, java) = (state.paths.root.clone(), installed.client_jar.clone(), installed.java.clone());
        tauri::async_runtime::spawn_blocking(move || {
            if !crate::jvmcache::accepts_options(&java, java_major.unwrap_or_default()) {
                return Err("this Java doesn't take the cache's options".to_owned());
            }
            crate::jvmcache::unsigned_client_jar(&Paths::new(root), &original)
        })
            .await
            .map_err(|e| e.to_string())
            .and_then(|made| made)
            .map_err(|error| eprintln!("[flint] launching without Java's start-up cache: {error}"))
            .ok()
    } else {
        None
    };
    let separator = if cfg!(windows) { ";" } else { ":" };
    let mut seen = HashSet::new();
    let mut classpath: Vec<String> = installed
        .libraries
        .iter()
        .filter(|l| l.native_extract.is_none() && seen.insert(l.path.clone()))
        .map(|l| l.path.display().to_string())
        .collect();
    classpath.push(client_jar.as_ref().unwrap_or(&installed.client_jar).display().to_string());

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

    // The flags follow the Java actually started (Java 21 where Mojang has no Java 25, or a custom Java).
    let mut args = jvm_args(&installed, crate::perfcore::policy_for_java(tuning.policy, java_major.map(u64::from)), tuning.heap_max_mb, &extra, &vars);
    let java_flags = crate::perfcore::modern_java_args(java_major.map(u64::from), &args);
    args.extend(java_flags);
    // Decided on the final options, which the cache must match; the build after a recorded launch reuses them.
    let cache_plan = if client_jar.is_some() { crate::jvmcache::plan(&state.paths, &installed.java, &args) } else { crate::jvmcache::Plan::off() };
    let cache_build = (crate::jvmcache::dir(&state.paths), installed.java.clone(), args.clone());
    args.extend(cache_plan.jvm_args());
    steps.step("start cache");
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
    // A partner server the player chose: added to this profile's list now (after Auto Sync prepared the profile, before
    // the game can read the list), then joined as the game starts.
    let listing = join.map(|join| {
        let listing = crate::partners::play::list_server(state, &id, join);
        // Quick Play finds the server in the list by the entry's own spelling of its address.
        args.extend(crate::partners::rules::join_args(&profile.game_version, &listing.address, &join.host, join.port));
        listing
    });
    steps.step("arguments");

    // Last look before Java starts, after everything else that touches the profile's files: Flint's runtime
    // is in mods/, whole and for this version, exactly when the profile should have it.
    crate::runtime::verify(state, &profile).map_err(|e| fail(&app, state, &id, &e))?;
    steps.step("runtime verify");
    // Which Flint Store the game shows cosmetics from (docs/STORE-INTEGRATION.md): public data only, no token. A game
    // without it uses its built-in Store, so a failure here never stops the launch.
    if let Err(error) = crate::store::handoff::write(state, &id) {
        eprintln!("[flint] couldn't write the Store handoff: {error}");
    }
    // Packs added while an earlier game ran were for that game: this one starts with nothing to apply.
    crate::content::livepacks::clear(&state.paths.profile_dir(&id));

    let logs = game_dir.join("logs");
    std::fs::create_dir_all(&logs).map_err(err("Could not create the log folder"))?;
    let log_path = logs.join("flint-launch.log");
    let mut log = std::fs::File::create(&log_path).map_err(err("Could not create the launch log"))?;
    // Where the time between Play and here went, as the launch log's first line (the game's output follows it).
    {
        use std::io::Write;
        let _ = writeln!(log, "[Flint] Minecraft {} prepared in {}; Java start-up cache: {}", profile.game_version, steps.summary(), cache_plan.describe());
    }
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
    // The game link (see gamelink.rs): this game's own secret, in its environment only. Without the link the game's account
    // card still works, saying accounts are switched in Flint.
    let link_secret = match state.game_link.address(|| crate::gamelink::AppBackend { app: app.clone() }).await {
        Ok(address) => {
            let secret = state.game_link.registry.register(&id, &account.uuid);
            command.env(crate::gamelink::ENV_ADDRESS, address.to_string()).env(crate::gamelink::ENV_SECRET, &secret);
            Some(secret)
        }
        Err(error) => {
            eprintln!("[flint] {error}");
            None
        }
    };
    steps.step("game link");
    let spawned = command.spawn();
    if let (Err(_), Some(secret)) = (&spawned, &link_secret) {
        state.game_link.registry.unregister(secret);
    }
    let mut child = spawned.map_err(|e| fail(&app, state, &id, &format!("Could not start Java: {e}")))?;
    steps.step("start java");
    eprintln!("[flint] Play to Java for Minecraft {}: {}", profile.game_version, steps.summary());
    let launched_at = now_secs();
    let launched_wall = std::time::SystemTime::now();
    if let (Some(pid), Some(presence)) = (child.id(), tauri::Manager::try_state::<crate::discord::Presence>(&app)) {
        presence.started(crate::discord::Session {
            id: id.clone(),
            pid,
            version: profile.game_version.clone(),
            loader: profile.loader.clone(),
            started_at: launched_at,
            game_dir: Some(game_dir.clone()),
        });
    }
    let (kill_tx, mut kill_rx) = tokio::sync::oneshot::channel::<()>();
    state.running.lock().unwrap().insert(id.clone(), RunningGame { kill: Some(kill_tx) });
    drop(reservation);
    emit(&app, state, &id, json!({ "state": "running", "demo": demo }));
    // Flint steps aside and the game opens maximized once its window shows (see gamewindow.rs).
    let aside = std::sync::Arc::new(std::sync::Mutex::new(crate::gamewindow::SteppedAside::default()));
    {
        let (on_start, maximize) = crate::gamewindow::launch_choices(state, &game_dir);
        let aside = aside.clone();
        crate::gamewindow::on_game_started(&app, child.id(), on_start, maximize, move |done| *aside.lock().unwrap() = done);
    }

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
        if let Some(secret) = &link_secret {
            state.game_link.registry.unregister(secret);
        }
        if let Some(presence) = app_handle.try_state::<crate::discord::Presence>() {
            presence.ended(&id);
        }
        if !recorded && counts_as_played(status.as_ref().map(|s| s.success()), started.elapsed()) {
            record(&state).await;
        }
        // The session's length, for the sidebar's most played (before "ready", which has the interface read profiles).
        let played = started.elapsed().as_secs();
        if played > 0 {
            let _ = update_profile(&state, &id, |p| p.play_seconds = p.play_seconds.saturating_add(played)).await;
        }
        let clean_exit = status.as_ref().is_some_and(|s| s.success());
        let exit = if clean_exit { crate::gamesettings::autosync::Exit::Clean } else { crate::gamesettings::autosync::Exit::Unclean };
        match status {
            Some(s) if !s.success() => {
                let during_startup = started.elapsed() < STARTUP_WINDOW;
                let failure = failure_state(&game_dir, &log_path, launched_wall, s.code(), during_startup);
                crate::troubleshoot::record_profile(&state, &id, "game", failure["message"].as_str().unwrap_or_default());
                emit(&app_handle, &state, &id, failure);
                let stepped = *aside.lock().unwrap();
                if (stepped.minimized || stepped.hidden) && during_startup {
                    crate::gamewindow::bring_back(&app_handle);
                }
            }
            _ => emit(&app_handle, &state, &id, json!({ "state": "ready" })),
        }
        // A Flint hidden in the tray comes back when its last game ends (a minimized one stays in the taskbar).
        if crate::gamewindow::hidden_in_tray(&app_handle) && state.running.lock().unwrap().is_empty() {
            crate::gamewindow::bring_back(&app_handle);
        }
        // Auto Sync records what the player changed in this game (after a crash, only files that read back
        // whole). Nothing is written into any profile here.
        let synced = id.clone();
        let _ = crate::data::blocking(&app_handle, move |state| crate::gamesettings::autosync::after_exit(state, &synced, exit)).await;
        // Renamed while it was playing: its folder takes the new name now.
        crate::profilefolder::follow(&state, &id).await;
        // A launch that recorded its classes builds Java's start-up cache now, in the background at low priority.
        let (cache_dir, java, cache_args) = cache_build;
        crate::jvmcache::after_exit(cache_dir, cache_plan, java, cache_args, clean_exit).await;
    });
    Ok(listing)
}

/// Readies a profile's own folder for a launch: Flint's in-game menu and its config (installed for supported profiles,
/// removed from all others), then Auto Sync, which records this profile's own changes and brings in what changed in
/// other profiles where it fits (its own failures are logged and never stop the launch).
async fn prepare_folder(app: &AppHandle, state: &AppState, profile: &Profile, steps: &Steps) -> CmdResult<crate::perfcore::LaunchTuning> {
    let version = vanilla_version(state, &profile.game_version).await?;
    let automatic = crate::perfcore::automatic_optimization_enabled(state);
    // The Java the game will run on: Flint's own for the line (Java 25 before 26.x) unless a custom Java is set, whose
    // version isn't known until it is checked (the version file's is assumed, and the launch drops what it can't take).
    let custom_java = load_settings(state)["javaPath"].as_str().is_some_and(|p| !p.trim().is_empty());
    let flint_java = crate::runtime::java_override(crate::runtime::enabled(state), profile).filter(|_| !custom_java);
    let java_major = flint_java.map(|choice| choice.major).or_else(|| version["javaVersion"]["majorVersion"].as_u64());
    let tuning = crate::perfcore::LaunchTuning {
        automatic,
        policy: crate::perfcore::choose_policy_for_java(automatic, crate::perfcore::hardware(), java_major),
        heap_max_mb: crate::data::launch_memory_mb(profile, load_settings(state)["memoryMb"].as_u64(), crate::data::max_memory_mb()),
    };
    crate::runtime::sync(state, profile, &tuning)?;
    steps.step("flint runtime");
    let profile = profile.clone();
    let _ = crate::data::blocking(app, move |state| crate::gamesettings::autosync::before_launch(state, &profile)).await;
    steps.step("options sync");
    Ok(tuning)
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
    fn the_first_play_is_expected_on_the_selected_profile_else_the_last_played() {
        let profile = |id: &str, last_played: Option<u64>| Profile { id: id.into(), name: id.into(), game_version: "26.3".into(), loader: "fabric".into(), last_played, ..Default::default() };
        let mut file = crate::data::ProfilesFile { selected: Some("b".into()), profiles: vec![profile("a", Some(30)), profile("b", None), profile("c", Some(10))] };
        assert_eq!(likely_first_play(&file).map(|p| p.id.as_str()), Some("b"));
        file.selected = Some("gone".into());
        assert_eq!(likely_first_play(&file).map(|p| p.id.as_str()), Some("a"));
        file.selected = None;
        file.profiles.retain(|p| p.last_played.is_none());
        assert!(likely_first_play(&file).is_none(), "nothing selected and nothing played yet");
    }

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
        assert!(check_host("https://maven.ornithemc.net/releases/net/ornithemc/x.jar").is_ok());
        assert!(check_host("http://maven.ornithemc.net/releases/x.jar").is_err(), "plain HTTP");
        assert!(check_host("https://maven.ornithemc.net.evil.example/x").is_err(), "lookalike host");
        assert!(check_host("https://maven.ornithemc.net:8080/x").is_err(), "custom port");
        assert!(check_host("https://user@maven.ornithemc.net/x").is_err(), "credentials in URL");
        assert!(check_host("https://meta.ornithemc.net/x.jar").is_err(), "metadata isn't a download host");
        assert!(check_host("https://maven.legacyfabric.net/x.jar").is_err());
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
    fn extra_java_arguments_are_split_and_checked() {
        assert_eq!(parse_extra_jvm_args("").unwrap(), Vec::<String>::new());
        assert_eq!(parse_extra_jvm_args("  -XX:+UseZGC   -Dfoo=bar ").unwrap(), ["-XX:+UseZGC", "-Dfoo=bar"]);
        assert_eq!(parse_extra_jvm_args(r#"-Dlog.dir="C:\My Logs" -Da="""#).unwrap(), [r"-Dlog.dir=C:\My Logs", "-Da="]);
        assert!(parse_extra_jvm_args(r#"-Dx="open"#).unwrap_err().contains("quote"));
        assert!(parse_extra_jvm_args("UseG1GC").unwrap_err().contains("dash"));
        assert!(parse_extra_jvm_args("-Xmx8G").unwrap_err().contains("Memory"), "the heap belongs to Settings → Memory");
        assert!(parse_extra_jvm_args("-xms2g").is_err());
        assert!(parse_extra_jvm_args("-XX:MaxRAMPercentage=90").is_err());
        assert!(parse_extra_jvm_args("-cp evil.jar").is_err());
        assert!(parse_extra_jvm_args("-Djava.library.path=C:/x").is_err());
        assert!(parse_extra_jvm_args("-version").is_err());
        assert!(parse_extra_jvm_args("-Da=\u{7}").is_err());
        assert!(parse_extra_jvm_args(&"-Da ".repeat(65)).is_err());
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

    fn asset(path: PathBuf, size: u64) -> Download {
        Download { url: String::new(), path, sha1: None, size: Some(size), executable: false }
    }

    #[test]
    fn files_in_a_listed_folder_are_checked_by_size_like_one_by_one() {
        let dir = TestDir::new();
        let cache = dir.0.join("verified.json");
        let objects = dir.0.join("assets/objects/ab");
        std::fs::create_dir_all(&objects).unwrap();
        let mut items = Vec::new();
        for i in 0..12 {
            let name = format!("ab{i:038}");
            std::fs::write(objects.join(&name), vec![b'x'; i + 1]).unwrap();
            // Written the way the manifests name them: with a forward slash inside the joined path.
            items.push(asset(dir.0.join("assets/objects").join("ab").join(&name), i as u64 + 1));
        }
        items[3].size = Some(99);
        std::fs::remove_file(objects.join(format!("ab{:038}", 5))).unwrap();
        let mut odd = asset(dir.0.join("assets/objects/ab").join(format!("AB{:038}", 7)), 8);
        odd.path = PathBuf::from(odd.path.to_string_lossy().replace('\\', "/"));
        items.push(odd);
        // A folder that isn't there yet: every file in it is missing.
        for i in 0..9 {
            items.push(asset(dir.0.join("assets/objects/cd").join(format!("cd{i:038}")), 1));
        }
        let cancel = AtomicBool::new(false);
        let pending = pending_downloads(items.clone(), &cache, false, &cancel, None::<fn(usize, usize)>).unwrap();
        let expected: Vec<&Download> = items.iter().filter(|i| needs_download(i, false, &mut VerifiedFiles::new(), true)).collect();
        assert_eq!(pending.iter().map(|d| &d.path).collect::<Vec<_>>(), expected.iter().map(|d| &d.path).collect::<Vec<_>>());
        let names: Vec<String> = pending.iter().map(|d| d.path.file_name().unwrap().to_string_lossy().into_owned()).collect();
        assert!(names.contains(&format!("ab{:038}", 3)), "wrong size");
        assert!(names.contains(&format!("ab{:038}", 5)), "missing");
        // The other case of a name is the same file where the file system ignores case (Windows, macOS as usually set up),
        // another file where it doesn't (Linux): the listing agrees with the file system either way.
        let other_case_is_same_file = objects.join(format!("AB{:038}", 7)).exists();
        assert_eq!(pending.len(), 2 + 9 + usize::from(!other_case_is_same_file), "the other case of a name");
        assert!(!cache.exists(), "nothing was hashed, so the record isn't written");
    }

    #[test]
    fn natives_are_unpacked_once_and_kept_while_unchanged() {
        use std::io::Write;
        let dir = TestDir::new();
        let jar = dir.0.join("natives.jar");
        {
            let mut zip = zip::ZipWriter::new(std::fs::File::create(&jar).unwrap());
            for (name, bytes) in [("lwjgl.dll", &b"native one"[..]), ("META-INF/MANIFEST.MF", b"skip"), ("sub/openal.dll", b"native two")] {
                zip.start_file(name, zip::write::SimpleFileOptions::default()).unwrap();
                zip.write_all(bytes).unwrap();
            }
            zip.finish().unwrap();
        }
        let dest = dir.0.join("natives");
        let exclude = vec!["META-INF/".to_owned()];
        extract_natives(&jar, &dest, &exclude, true).unwrap();
        assert_eq!(std::fs::read(dest.join("lwjgl.dll")).unwrap(), b"native one");
        assert_eq!(std::fs::read(dest.join("sub/openal.dll")).unwrap(), b"native two");
        assert!(!dest.join("META-INF").exists());
        let stamp = std::fs::metadata(dest.join("lwjgl.dll")).unwrap().modified().unwrap();

        // Unchanged: nothing is written again.
        extract_natives(&jar, &dest, &exclude, true).unwrap();
        assert_eq!(std::fs::metadata(dest.join("lwjgl.dll")).unwrap().modified().unwrap(), stamp);

        // A damaged native (another size) is put back; one changed in place is caught by a repair.
        std::fs::write(dest.join("lwjgl.dll"), b"broken").unwrap();
        extract_natives(&jar, &dest, &exclude, true).unwrap();
        assert_eq!(std::fs::read(dest.join("lwjgl.dll")).unwrap(), b"native one");
        let before = std::fs::metadata(dest.join("sub/openal.dll")).unwrap().modified().unwrap();
        std::fs::write(dest.join("sub/openal.dll"), b"native TWO").unwrap();
        std::fs::File::options().write(true).open(dest.join("sub/openal.dll")).unwrap().set_modified(before).unwrap();
        extract_natives(&jar, &dest, &exclude, false).unwrap();
        assert_eq!(std::fs::read(dest.join("sub/openal.dll")).unwrap(), b"native two");
        std::fs::remove_file(dest.join("lwjgl.dll")).unwrap();
        extract_natives(&jar, &dest, &exclude, true).unwrap();
        assert_eq!(std::fs::read(dest.join("lwjgl.dll")).unwrap(), b"native one", "a missing native is unpacked again");
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

        // 26.3: the version JSON recommends its own JVM settings, which Flint uses as the official launcher does.
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
        // Mojang's set: its heap ceiling left to Flint's memory setting, ZGC where its rules allow it (G1 on Windows before
        // 10.0.17134), whether Automatic Optimization is on or off.
        let modern = !windows || os_version().is_some_and(|v| version_cmp(v, "10.0.17134").is_ge());
        let old_windows = windows && os_version().is_some_and(|v| version_cmp(v, "10.0.17134").is_lt());
        let mojang = |memory: u64, extra: &[&str]| -> Vec<String> {
            let mut out = vec![format!("-Xmx{memory}M"), "-Dminecraft.launcher.brand=flint".into()];
            if memory >= 2048 {
                out.push("-Xms2G".into());
            }
            out.extend(["-XX:+UseCompactObjectHeaders", "-XX:+AlwaysPreTouch", "-XX:+UseStringDeduplication"].map(str::to_owned));
            let player_collector = !extra.is_empty();
            out.extend(os_specific(&["-XX:+UseZGC"], modern && !player_collector));
            out.extend(os_specific(&["-XX:+UnlockExperimentalVMOptions"], old_windows));
            out.extend(os_specific(&["-XX:+UseG1GC"], old_windows && !player_collector));
            out.extend(extra.iter().map(|a| a.to_string()));
            os_expected(&mut out);
            out.extend(
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
            out
        };
        let installed = installed_with(v26_3, Some(fabric.clone()), Some(logging));
        for hardware in [unbounded.clone(), HardwareInfo::default()] {
            for automatic in [false, true] {
                let policy = crate::perfcore::choose_policy(automatic, &hardware, &installed.version);
                assert_eq!(jvm_args(&installed, policy, 4096, &[], &vars), mojang(4096, &[]), "26.3, automatic optimization {automatic}");
            }
        }
        let policy = crate::perfcore::choose_policy(true, &HardwareInfo::default(), &installed.version);
        assert_eq!(jvm_args(&installed, policy, 1536, &[], &vars), mojang(1536, &[]), "a 2 GB starting heap never exceeds a smaller ceiling");
        let shenandoah = ["-XX:+UseShenandoahGC".to_owned()];
        assert_eq!(jvm_args(&installed, policy, 4096, &shenandoah, &vars), mojang(4096, &["-XX:+UseShenandoahGC"]), "the player's collector replaces Mojang's");

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
            assert_eq!(jvm_args(&installed, policy, 6144, &[], &vars), expected, "1.21.11, automatic={automatic}");
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
            assert_eq!(jvm_args(&installed, policy, 2048, &[], &vars), expected, "1.8.9, automatic={automatic}");
        }

        // 1.8.9 with Fabric from Ornithe: the same, then the loader's own flags; its logging block is never used.
        let installed = installed_with(
            json!({ "javaVersion": { "component": "jre-legacy", "majorVersion": 8 }, "minecraftArguments": "--username ${auth_player_name}" }),
            Some(ornithe_189_profile()),
            None,
        );
        let mut expected = flint_0_4_0_head(2048);
        expected.extend(
            ["-Djava.library.path=/game/versions/v/natives", "-cp", "/lib/a.jar:/game/versions/v/v.jar", "-Dfabric.fixPackageAccess=true", "-Dfabric.gameVersion=1.8.9"]
                .map(str::to_owned),
        );
        for automatic in [true, false] {
            let policy = crate::perfcore::choose_policy(automatic, &unbounded, &installed.version);
            assert_eq!(policy, JvmPolicy::Legacy);
            assert_eq!(jvm_args(&installed, policy, 2048, &[], &vars), expected, "1.8.9 with Fabric, automatic={automatic}");
        }
    }

    /// Ornithe's Fabric profile for 1.8.9, as meta.ornithemc.net served it on 2026-10-26 (asm's hashes shortened to one library).
    fn ornithe_189_profile() -> Value {
        json!({
            "id": "fabric-loader-0.20.1-1.8.9-ornithe-gen2",
            "inheritsFrom": "1.8.9-vanilla",
            "type": "release",
            "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "arguments": { "jvm": ["-Dfabric.fixPackageAccess=true", "-Dfabric.gameVersion=1.8.9"], "game": [] },
            "libraries": [
                { "name": "org.ow2.asm:asm:9.10.1", "url": "https://maven.fabricmc.net/", "sha1": "ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236", "size": 126151 },
                { "name": "net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7", "url": "https://maven.fabricmc.net/", "sha1": "0cef8519a91129d0c21fa03bb9378f0e8ca810e5", "size": 1551593 },
                { "name": "net.ornithemc:calamus-intermediary-gen2:1.8.9", "url": "https://maven.ornithemc.net/releases/" },
                { "name": "net.fabricmc:fabric-loader:0.20.1", "url": "https://maven.fabricmc.net/" }
            ],
            "logging": { "client": { "argument": "-Dlog4j.configurationFile=${path}", "file": { "id": "client-1.12.xml" } } }
        })
    }

    #[test]
    fn version_ranges_pick_exactly_one_collector() {
        let zgc = json!({ "action": "allow", "os": { "name": mojang_os(), "versionRange": { "min": "10.0.17134" } } });
        let g1 = json!({ "action": "allow", "os": { "name": mojang_os(), "versionRange": { "max": "10.0.17134" } } });
        for (version, want_zgc) in [("10.0.26300", true), ("10.0.17134", true), ("10.0.17133", false), ("6.1.7601", false)] {
            assert_eq!(rule_matches_on(&zgc, Some(version)), want_zgc, "{version}");
            assert_eq!(rule_matches_on(&g1, Some(version)), !want_zgc, "{version}: never both, never neither");
        }
        assert!(!rule_matches_on(&zgc, None) && !rule_matches_on(&g1, None), "an unknown system gets neither: Java's own default");
        assert!(version_cmp("10.0.9999", "10.0.17134").is_lt() && version_cmp("11", "10.0.26300").is_gt());
        assert_eq!(heap_mb("2G"), Some(2048));
        assert_eq!(heap_mb("512m"), Some(512));
        assert_eq!(heap_mb("nonsense"), None);
    }

    #[test]
    fn the_launch_java_is_flints_need_or_the_version_files() {
        let v1_8_9 = json!({ "javaVersion": { "component": "jre-legacy", "majorVersion": 8 } });
        assert_eq!(java_for(&v1_8_9, None), ("jre-legacy", Some(8), None), "vanilla 1.8.9");
        let v26_3 = json!({ "javaVersion": { "component": "java-runtime-epsilon", "majorVersion": 25 } });
        assert_eq!(java_for(&v26_3, None), ("java-runtime-epsilon", Some(25), None));
        assert_eq!(java_for(&json!({}), None), ("jre-legacy", None, None), "no javaVersion: Java 8, as Mojang's launcher");
        // As the official launcher: 1.21.x with Flint keeps its own Java 21. 1.8.9 with Flint's runtime needs Java 21: Mojang's
        // Java 21, and a custom Java must be 21 or newer.
        let v1_21_11 = json!({ "javaVersion": { "component": "java-runtime-delta", "majorVersion": 21 } });
        let fabric = |version: &str| -> crate::data::Profile { serde_json::from_value(json!({ "id": "p", "name": "P", "gameVersion": version, "loader": "fabric" })).unwrap() };
        assert_eq!(java_for(&v1_21_11, crate::runtime::java_override(true, &fabric("1.21.11"))), ("java-runtime-delta", Some(21), None));
        assert_eq!(java_for(&v1_8_9, crate::runtime::java_override(true, &fabric("1.8.9"))), ("java-runtime-delta", Some(21), Some("java-runtime-delta")));
        let profile: crate::data::Profile =
            serde_json::from_value(json!({ "id": "p", "name": "P", "gameVersion": "1.8.9", "loader": "fabric" })).unwrap();
        assert_eq!(java_for(&v1_8_9, crate::runtime::java_override(false, &profile)), ("jre-legacy", Some(8), None));
    }

    #[test]
    fn fabric_for_1_8_9_comes_from_ornithe() {
        let ornithe = LoaderSource::for_game("1.8.9");
        assert_eq!(ornithe, LoaderSource::Ornithe);
        assert_eq!(ornithe.loaders_url("1.8.9"), "https://meta.ornithemc.net/v3/versions/fabric-loader/1.8.9");
        assert_eq!(ornithe.profile_url("1.8.9", "0.20.1"), "https://meta.ornithemc.net/v3/versions/fabric-loader/1.8.9/0.20.1/profile/json");
        assert_eq!(ornithe.profile_dir("1.8.9", "0.20.1"), "fabric-loader-0.20.1-1.8.9-ornithe-gen2", "the profile's own id");
        for game in ["26.3", "26.1.2", "1.21.11", "1.21.10", "1.21.1"] {
            let fabric = LoaderSource::for_game(game);
            assert_eq!(fabric, LoaderSource::Fabric, "{game}");
            assert_eq!(fabric.loaders_url(game), format!("https://meta.fabricmc.net/v2/versions/loader/{game}"));
            assert_eq!(fabric.profile_url(game, "0.19.5"), format!("https://meta.fabricmc.net/v2/versions/loader/{game}/0.19.5/profile/json"));
            assert_eq!(fabric.profile_dir(game, "0.19.5"), format!("fabric-loader-0.19.5-{game}"), "unchanged from earlier versions");
        }
        // Every library of Ornithe's profile downloads from an allowed host.
        for (_, url) in libraries_missing_sha1(&json!({ "libraries": ornithe_189_profile()["libraries"].as_array().unwrap().iter().map(|l| json!({ "name": l["name"], "url": l["url"] })).collect::<Vec<_>>() }), "https://maven.fabricmc.net/").unwrap() {
            assert!(check_host(&url).is_ok(), "{url}");
        }
    }

    #[test]
    fn a_loader_profile_must_build_on_the_version_asked_for() {
        let profile = ornithe_189_profile();
        assert!(LoaderSource::Ornithe.check_inherits(&profile, "1.8.9").is_ok(), "Ornithe's name for vanilla 1.8.9");
        assert!(LoaderSource::Ornithe.check_inherits(&json!({ "inheritsFrom": "1.8.9" }), "1.8.9").is_ok());
        assert!(LoaderSource::Ornithe.check_inherits(&json!({ "inheritsFrom": "1.8.8-vanilla" }), "1.8.9").is_err());
        assert!(LoaderSource::Ornithe.check_inherits(&json!({ "inheritsFrom": "1.8.9-gen1" }), "1.8.9").is_err());
        assert!(LoaderSource::Ornithe.check_inherits(&json!({}), "1.8.9").is_err());
        assert!(LoaderSource::Fabric.check_inherits(&json!({ "inheritsFrom": "26.3" }), "26.3").is_ok());
        assert!(LoaderSource::Fabric.check_inherits(&json!({ "inheritsFrom": "26.3-vanilla" }), "26.3").is_err(), "only Ornithe names vanilla that way");
        assert!(LoaderSource::Fabric.check_inherits(&json!({ "inheritsFrom": "26.2" }), "26.3").is_err());
    }

    #[test]
    fn libraries_without_a_hash_get_their_mavens_published_one() {
        let missing = libraries_missing_sha1(&ornithe_189_profile(), "https://maven.fabricmc.net/").unwrap();
        assert_eq!(
            missing,
            vec![
                (2, "https://maven.ornithemc.net/releases/net/ornithemc/calamus-intermediary-gen2/1.8.9/calamus-intermediary-gen2-1.8.9.jar".to_owned()),
                (3, "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar".to_owned()),
            ]
        );
        // A library listing a malformed hash is checked against the maven's instead.
        let odd = json!({ "libraries": [{ "name": "a:b:1", "url": "https://maven.fabricmc.net/", "sha1": "nope" }] });
        assert_eq!(libraries_missing_sha1(&odd, "https://maven.fabricmc.net/").unwrap().len(), 1);
        assert!(libraries_missing_sha1(&json!({ "libraries": [{ "name": "broken" }] }), "https://maven.fabricmc.net/").is_err());

        assert_eq!(parse_maven_sha1("0ba81322efe0c8515a4ab246dae65a5c59c20742").as_deref(), Some("0ba81322efe0c8515a4ab246dae65a5c59c20742"));
        assert_eq!(parse_maven_sha1("0BA81322EFE0C8515A4AB246DAE65A5C59C20742  x.jar\n").as_deref(), Some("0ba81322efe0c8515a4ab246dae65a5c59c20742"));
        assert!(parse_maven_sha1("").is_none());
        assert!(parse_maven_sha1("<html>Not found</html>").is_none());
        assert!(parse_maven_sha1("0ba81322efe0c8515a4ab246dae65a5c59c2074").is_none(), "39 digits");
        assert!(parse_maven_sha1("0ba81322efe0c8515a4ab246dae65a5c59c2074z").is_none());
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
        // A changed file no longer matches its stamp and is checked (and found wrong). Its time is set apart: a fast disk
        // can write both versions within one tick of the clock.
        let before = std::fs::metadata(&jar).unwrap().modified().unwrap();
        std::fs::write(&jar, b"other").unwrap();
        std::fs::File::options().write(true).open(&jar).unwrap().set_modified(before + std::time::Duration::from_secs(2)).unwrap();
        assert!(needs_download(&item, true, &mut verified, true));
    }
}
