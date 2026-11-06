//! Settings → Troubleshooting: the history of what went wrong, this computer's details, and the diagnostics a player can
//! save and share with support.
//!
//! The error history is what Flint already reports when something fails: a job (an install, update, import, export), a
//! profile that couldn't be readied or started, and a game that stopped with an error. It is kept in
//! `<data>/error-history.json`, newest first, at most [KEEP] entries, and cleared by the player.
//!
//! Diagnostics are a `.zip` the player saves wherever they choose (nothing is uploaded): Flint's version and build, the
//! system, Java, settings, profiles (name, version, loader, how many mods), storage, recent errors and jobs, and the newest
//! launch logs and crash reports, each cut to its last part. Everything in it passes through `redact`: no token, e-mail
//! or IP address, player UUID, or path with the user's name.

use crate::core::*;
use crate::data::{blocking, load_profiles, load_settings};
use crate::redact::Redactor;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_opener::OpenerExt;

const HISTORY: &str = "error-history.json";
/// Entries kept, newest first.
const KEEP: usize = 50;
/// The longest message kept for one entry.
const MESSAGE_MAX: usize = 1200;
/// The same failure reported twice within this many seconds is one entry.
const REPEAT_WINDOW: u64 = 10;

/// Logs in a diagnostics file: this many of each, newest first, each cut to its last part.
const LAUNCH_LOGS: usize = 4;
const CRASH_REPORTS: usize = 4;
const LOG_TAIL: u64 = 384 * 1024;
/// Logs stop being added past this much in the file.
const LOGS_MAX: u64 = 4 * 1024 * 1024;

static WRITING: Mutex<()> = Mutex::new(());

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ErrorEntry {
    /// Seconds since 1970.
    pub at: u64,
    /// "job" (an install, update, import or export), "profile" (getting a profile ready or starting it), "game" (the game
    /// stopped with an error).
    pub kind: String,
    /// What failed: the job's title, or the profile's name.
    pub title: String,
    pub message: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub game_version: Option<String>,
}

pub fn history(root: &Path) -> Vec<ErrorEntry> {
    read_json(&root.join(HISTORY))
}

/// Adds a failure to the history (never fails: the history is a convenience).
pub fn record(root: &Path, kind: &str, title: &str, message: &str, game_version: Option<&str>) {
    record_at(root, now_secs(), kind, title, message, game_version);
}

fn record_at(root: &Path, at: u64, kind: &str, title: &str, message: &str, game_version: Option<&str>) {
    let message = message.trim();
    if message.is_empty() || message == "__cancelled__" {
        return;
    }
    let cut = message.chars().count() > MESSAGE_MAX;
    let mut message: String = message.chars().take(MESSAGE_MAX).collect();
    if cut {
        message.push('…');
    }
    let entry = ErrorEntry {
        at,
        kind: kind.to_owned(),
        title: title.chars().take(120).collect(),
        message,
        game_version: game_version.map(str::to_owned),
    };
    let _guard = WRITING.lock().unwrap_or_else(|e| e.into_inner());
    let mut entries = history(root);
    if entries.first().is_some_and(|last| last.kind == entry.kind && last.title == entry.title && last.message == entry.message && at.saturating_sub(last.at) <= REPEAT_WINDOW) {
        return;
    }
    entries.insert(0, entry);
    entries.truncate(KEEP);
    let _ = write_json(&root.join(HISTORY), &entries);
}

/// A profile that couldn't be readied or started, or whose game stopped with an error.
pub fn record_profile(state: &AppState, id: &str, kind: &str, message: &str) {
    let profile = crate::data::find_profile(state, id).ok();
    let title = profile.as_ref().map_or_else(|| "A profile".to_owned(), |p| p.name.clone());
    record(&state.paths.root, kind, &title, message, profile.as_ref().map(|p| p.game_version.as_str()));
}

#[tauri::command]
pub fn error_history(state: State<'_, AppState>) -> Vec<ErrorEntry> {
    history(&state.paths.root)
}

#[tauri::command]
pub fn error_history_clear(state: State<'_, AppState>) -> CmdResult<()> {
    let _guard = WRITING.lock().unwrap_or_else(|e| e.into_inner());
    match std::fs::remove_file(state.paths.root.join(HISTORY)) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(format!("Could not clear the history: {e}")),
        _ => Ok(()),
    }
}

// ---------- this computer ----------

/// The processor's name, as the system reports it.
fn cpu_name() -> Option<String> {
    #[cfg(windows)]
    {
        std::env::var("PROCESSOR_IDENTIFIER").ok()
    }
    #[cfg(target_os = "macos")]
    {
        crate::data::sysctl(&["machdep.cpu.brand_string"]).map(|s| s.trim().to_owned()).filter(|s| !s.is_empty())
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        std::fs::read_to_string("/proc/cpuinfo").ok()?.lines().find_map(|l| l.strip_prefix("model name")).and_then(|l| l.split_once(':')).map(|(_, v)| v.trim().to_owned())
    }
}

/// The system's name and version ("Windows 10.0.26300", "macOS 15.1", "Ubuntu 24.04 LTS").
fn os_version() -> String {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let ver = std::process::Command::new("cmd").args(["/c", "ver"]).creation_flags(0x0800_0000).output().ok().map(|o| String::from_utf8_lossy(&o.stdout).into_owned());
        ver.and_then(|v| v.split("Version ").nth(1).map(|n| n.trim().trim_end_matches(']').to_owned())).map_or_else(|| "Windows".to_owned(), |n| format!("Windows {n}"))
    }
    #[cfg(target_os = "macos")]
    {
        crate::data::sysctl(&["kern.osproductversion"]).map(|v| format!("macOS {}", v.trim())).unwrap_or_else(|| "macOS".to_owned())
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        std::fs::read_to_string("/etc/os-release")
            .ok()
            .and_then(|t| t.lines().find_map(|l| l.strip_prefix("PRETTY_NAME=")).map(|v| v.trim_matches('"').to_owned()))
            .unwrap_or_else(|| "Linux".to_owned())
    }
}

/// Settings → Troubleshooting's system line: the system and its version, the processor and its threads, and memory.
#[tauri::command]
pub async fn environment_info() -> Value {
    tokio::task::spawn_blocking(|| {
        json!({
            "os": os_version(),
            "arch": std::env::consts::ARCH,
            "cpu": cpu_name(),
            "threads": std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1),
            "memoryMb": crate::data::memory().map(|m| m.installed_mb),
        })
    })
    .await
    .unwrap_or_default()
}

// ---------- diagnostics ----------

/// Settings kept out of diagnostics whatever they hold.
fn private_key(key: &str) -> bool {
    let key = key.to_ascii_lowercase();
    ["token", "secret", "password", "session", "cookie", "auth", "email", "account"].iter().any(|word| key.contains(word))
}

fn clean_settings(settings: &Value) -> Value {
    match settings {
        Value::Object(map) => Value::Object(map.iter().filter(|(k, _)| !private_key(k)).map(|(k, v)| (k.clone(), clean_settings(v))).collect()),
        other => other.clone(),
    }
}

/// How many of a folder's files have one of `extensions` (and how many more are switched off, `.disabled`).
fn count_files(dir: &Path, extensions: &[&str]) -> (usize, usize) {
    let mut on = 0;
    let mut off = 0;
    for entry in std::fs::read_dir(dir).into_iter().flatten().flatten() {
        let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
        if name.starts_with('.') {
            continue;
        }
        let (base, disabled) = name.strip_suffix(".disabled").map_or((name.as_str(), false), |b| (b, true));
        if extensions.is_empty() || extensions.iter().any(|e| base.ends_with(e)) {
            if disabled {
                off += 1;
            } else {
                on += 1;
            }
        }
    }
    (on, off)
}

fn profiles_report(state: &AppState) -> Value {
    let file = load_profiles(state);
    let list: Vec<Value> = file
        .profiles
        .iter()
        .map(|p| {
            let dir = state.paths.profile_dir(&p.id);
            let (mods, mods_off) = count_files(&dir.join("mods"), &[".jar"]);
            json!({
                "name": p.name,
                "gameVersion": p.game_version,
                "loader": p.loader,
                "loaderVersion": p.loader_version,
                "installed": p.installed,
                "selected": file.selected.as_deref() == Some(p.id.as_str()),
                "mods": mods,
                "modsSwitchedOff": mods_off,
                "resourcePacks": count_files(&dir.join("resourcepacks"), &[]).0,
                "shaderPacks": count_files(&dir.join("shaderpacks"), &[]).0,
                "worlds": count_files(&dir.join("saves"), &[]).0,
                "memoryMb": p.memory_mb,
                "lastPlayed": p.last_played,
                "flintRuntime": dir.join("mods").join(crate::runtime::FILE_NAME).is_file(),
            })
        })
        .collect();
    json!(list)
}

/// Each managed Java: its component, platform folder and version (from its `release` file).
fn java_report(state: &AppState) -> Value {
    let mut out = Vec::new();
    for component in std::fs::read_dir(state.paths.runtimes()).into_iter().flatten().flatten().filter(|e| e.path().is_dir()) {
        for platform in std::fs::read_dir(component.path()).into_iter().flatten().flatten().filter(|e| e.path().is_dir()) {
            let home = platform.path();
            let release = ["release", "jre.bundle/Contents/Home/release"].iter().find_map(|r| std::fs::read_to_string(home.join(r)).ok());
            let version = release.and_then(|r| r.lines().find_map(|l| l.strip_prefix("JAVA_VERSION=")).map(|v| v.trim_matches('"').to_owned()));
            out.push(json!({
                "component": component.file_name().to_string_lossy(),
                "platform": platform.file_name().to_string_lossy(),
                "version": version,
            }));
        }
    }
    json!(out)
}

/// A log to include: its name in the file, where it is, and whether it is Minecraft's XML console output.
struct LogFile {
    name: String,
    path: PathBuf,
    modified: std::time::SystemTime,
}

/// The newest launch logs and crash reports across profiles, and Java's newest start-up cache log.
fn recent_logs(state: &AppState) -> Vec<LogFile> {
    let mut launches = Vec::new();
    let mut crashes = Vec::new();
    let modified = |path: &Path| std::fs::metadata(path).and_then(|m| m.modified()).ok();
    for profile in load_profiles(state).profiles {
        let dir = state.paths.profile_dir(&profile.id);
        let folder = file_part(crate::profilefolder::current(&profile));
        for name in ["flint-launch.log", "latest.log"] {
            let path = dir.join("logs").join(name);
            if let Some(time) = modified(&path) {
                launches.push(LogFile { name: format!("logs/{folder}/{name}"), path, modified: time });
            }
        }
        for entry in std::fs::read_dir(dir.join("crash-reports")).into_iter().flatten().flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.ends_with(".txt") {
                if let Some(time) = modified(&entry.path()) {
                    crashes.push(LogFile { name: format!("crash-reports/{folder}/{}", file_part(&name)), path: entry.path(), modified: time });
                }
            }
        }
    }
    let mut java = Vec::new();
    for entry in std::fs::read_dir(crate::jvmcache::dir(&state.paths)).into_iter().flatten().flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if name.ends_with(".log") {
            if let Some(time) = modified(&entry.path()) {
                java.push(LogFile { name: format!("logs/java-start-up-cache/{}", file_part(&name)), path: entry.path(), modified: time });
            }
        }
    }
    let newest = |mut list: Vec<LogFile>, keep: usize| {
        list.sort_by_key(|log| std::cmp::Reverse(log.modified));
        list.truncate(keep);
        list
    };
    let mut out = newest(launches, LAUNCH_LOGS);
    out.extend(newest(crashes, CRASH_REPORTS));
    out.extend(newest(java, 1));
    out
}

/// A name that is safe as a part of a path in the file (letters, digits, a few marks).
fn file_part(name: &str) -> String {
    let clean: String = name.chars().map(|c| if c.is_ascii_alphanumeric() || " -_.()".contains(c) { c } else { '_' }).collect();
    let clean = clean.trim_matches(['.', ' ']).to_owned();
    if clean.is_empty() {
        "profile".into()
    } else {
        clean
    }
}

/// What the diagnostics file holds, as (name, text) pairs, every text redacted.
fn report(state: &AppState, summary: &str, redactor: &Redactor) -> Vec<(String, String)> {
    let json_text = |value: &Value| redactor.apply(&serde_json::to_string_pretty(value).unwrap_or_default());
    let settings = load_settings(state);
    let jobs: Vec<Value> = state
        .jobs
        .list()
        .into_iter()
        .map(|j| json!({ "kind": j.kind, "title": j.title, "state": j.state, "stage": j.stage, "message": j.message, "createdAt": j.created_at }))
        .collect();
    let storage = crate::storage::overview(state, false);
    let mut files = vec![
        (
            "README.txt".to_owned(),
            format!(
                "Flint Client diagnostics, saved {} (seconds since 1970: {}).\n\nNothing was uploaded: this file is only where you saved it. Sign-in tokens, e-mail addresses, IP addresses, your Minecraft account's UUID and your user folder's name were taken out (shown as [hidden], [email], [ip], [player uuid] and ~). Look through it before you share it.\n\nsummary.txt     Flint, this computer, memory and Java\nprofiles.json   your profiles: name, version, loader, how many mods, packs and worlds\nsettings.json   Flint's settings\njava.json       the Java runtimes Flint installed\nstorage.json    what Flint's folder holds\nerrors.json     recent failures (Settings → Troubleshooting → Error history)\njobs.json       this session's installs, updates, imports and exports\nlogs/           the newest launch logs, and Java's start-up cache log\ncrash-reports/  the newest crash reports\n",
                date_text(now_secs()),
                now_secs()
            ),
        ),
        ("summary.txt".to_owned(), redactor.apply(summary)),
        ("profiles.json".to_owned(), json_text(&profiles_report(state))),
        ("settings.json".to_owned(), json_text(&clean_settings(&settings))),
        ("java.json".to_owned(), json_text(&java_report(state))),
        ("storage.json".to_owned(), json_text(&storage)),
        ("errors.json".to_owned(), json_text(&json!(history(&state.paths.root)))),
        ("jobs.json".to_owned(), json_text(&json!(jobs))),
    ];
    let mut logs_size = 0u64;
    for log in recent_logs(state) {
        let Ok(text) = crate::diagnostics::read_tail(&log.path, LOG_TAIL) else { continue };
        let text = if text.contains("<log4j:Event") { crate::diagnostics::render_log4j(&text) } else { text };
        logs_size += text.len() as u64;
        if logs_size > LOGS_MAX {
            break;
        }
        files.push((log.name, redactor.apply(&text)));
    }
    files
}

/// A date as `2026-11-02 19:27 UTC`.
pub fn date_text(secs: u64) -> String {
    let days = (secs / 86_400) as i64;
    let (hour, minute) = ((secs % 86_400) / 3600, (secs % 3600) / 60);
    // Civil date from days since 1970 (Howard Hinnant's algorithm).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!("{year:04}-{month:02}-{day:02} {hour:02}:{minute:02} UTC")
}

/// Writes the files into a zip at `path` (through a temporary file beside it, so a failure leaves nothing half-written).
fn write_zip(path: &Path, files: &[(String, String)]) -> CmdResult<u64> {
    let name = path.file_name().and_then(|n| n.to_str()).ok_or("Invalid file name")?;
    let part = path.with_file_name(format!(".{name}{}", crate::jobs::PART_SUFFIX));
    let written = (|| {
        let mut zip = zip::ZipWriter::new(std::io::BufWriter::new(std::fs::File::create(&part)?));
        let options = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        for (name, text) in files {
            zip.start_file(name.as_str(), options).map_err(std::io::Error::other)?;
            zip.write_all(text.as_bytes())?;
        }
        zip.finish().map_err(std::io::Error::other)?.flush()?;
        std::fs::rename(&part, path)?;
        std::fs::metadata(path).map(|m| m.len())
    })();
    if written.is_err() {
        let _ = std::fs::remove_file(&part);
    }
    written.map_err(|e| format!("Could not save the diagnostics: {e}"))
}

/// Asks where to save the diagnostics (the system's save dialog), then writes them there. None when the player cancels.
#[tauri::command]
pub async fn diagnostics_export(app: AppHandle, window: tauri::Window) -> CmdResult<Option<Value>> {
    let folder = app.path().download_dir().ok();
    let stamp = date_text(now_secs()).replace([':', ' '], "-").trim_end_matches("-UTC").to_owned();
    let picked = tokio::task::spawn_blocking(move || {
        let mut dialog = rfd::FileDialog::new()
            .set_title("Save diagnostics")
            .set_file_name(format!("Flint diagnostics {stamp}.zip"))
            .add_filter("Zip archive", &["zip"])
            .set_parent(&window);
        if let Some(folder) = folder {
            dialog = dialog.set_directory(folder);
        }
        dialog.save_file()
    })
    .await
    .map_err(|e| e.to_string())?;
    let Some(mut path) = picked else { return Ok(None) };
    if !path.extension().is_some_and(|e| e.eq_ignore_ascii_case("zip")) {
        path.as_mut_os_string().push(".zip");
    }
    let state = app.state::<AppState>();
    let summary = crate::data::diagnostics_summary(&app, &state).await;
    let redactor = Redactor::local().with_players(crate::auth::load_accounts(&state).accounts.into_iter().map(|a| a.uuid));
    let target = path.clone();
    let (size, count) = blocking(&app, move |state| {
        let summary = format!("{summary}\nOS version: {}\nProcessor: {}", os_version(), cpu_name().unwrap_or_else(|| "unknown".into()));
        let files = report(state, &summary, &redactor);
        write_zip(&target, &files).map(|size| (size, files.len()))
    })
    .await??;
    Ok(Some(json!({ "path": path.display().to_string(), "size": size, "files": count })))
}

/// Shows a saved diagnostics file in its folder.
#[tauri::command]
pub fn diagnostics_reveal(app: AppHandle, path: String) -> CmdResult<()> {
    let path = PathBuf::from(path);
    if !path.is_file() || !path.extension().is_some_and(|e| e.eq_ignore_ascii_case("zip")) {
        return Err("That file no longer exists.".into());
    }
    app.opener().reveal_item_in_dir(&path).map_err(err("Could not show the file"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;
    use std::io::Read;

    #[test]
    fn the_history_is_newest_first_capped_and_skips_repeats_and_cancels() {
        let dir = TestDir::new();
        record_at(&dir.0, 100, "job", "Installing Sodium", "Couldn't reach Modrinth", None);
        record_at(&dir.0, 105, "job", "Installing Sodium", "Couldn't reach Modrinth", None);
        record_at(&dir.0, 200, "profile", "PvP", "__cancelled__", Some("26.2"));
        record_at(&dir.0, 300, "game", "PvP", "Minecraft crashed: Initializing game (exit code 1).", Some("26.2"));
        let entries = history(&dir.0);
        assert_eq!(entries.iter().map(|e| e.at).collect::<Vec<_>>(), [300, 100], "newest first, a repeat and a cancel left out");
        assert_eq!(entries[0].game_version.as_deref(), Some("26.2"));
        for n in 0..(KEEP as u64 + 10) {
            record_at(&dir.0, 1000 + n * 100, "job", &format!("Job {n}"), "failed", None);
        }
        let entries = history(&dir.0);
        assert_eq!(entries.len(), KEEP);
        assert_eq!(entries[0].title, format!("Job {}", KEEP + 9));
        let long = "x".repeat(5000);
        record_at(&dir.0, 99_999, "job", "Long", &long, None);
        assert_eq!(history(&dir.0)[0].message.chars().count(), MESSAGE_MAX + 1, "cut, with an ellipsis");
    }

    #[test]
    fn settings_lose_private_keys_and_paths_their_user_name() {
        let settings = json!({ "theme": "ember", "storeSessionToken": "abc", "javaPath": r"C:\Users\jordan\jdk\bin\java.exe", "nested": { "authCode": "x", "ok": 1 } });
        let clean = clean_settings(&settings);
        assert_eq!(clean, json!({ "theme": "ember", "javaPath": r"C:\Users\jordan\jdk\bin\java.exe", "nested": { "ok": 1 } }));
        let text = Redactor::new(Some(r"C:\Users\jordan"), Some("jordan")).apply(&serde_json::to_string_pretty(&clean).unwrap());
        assert!(!text.contains("jordan") && text.contains(r"~\\jdk\\bin\\java.exe"), "{text}");
    }

    #[test]
    fn a_diagnostics_file_holds_redacted_reports_and_recent_logs() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let mut file = crate::data::ProfilesFile::default();
        let mut profile = crate::data::Profile { id: "p1".into(), name: "PvP".into(), game_version: "26.2".into(), loader: "fabric".into(), ..Default::default() };
        profile.folder = Some("PvP-26.2".into());
        state.paths.set_folder("p1", "PvP-26.2");
        file.profiles.push(profile);
        crate::data::save_profiles(&state, &file).unwrap();
        let game = dir.0.join("profiles/PvP-26.2");
        std::fs::create_dir_all(game.join("mods")).unwrap();
        std::fs::create_dir_all(game.join("logs")).unwrap();
        std::fs::create_dir_all(game.join("crash-reports")).unwrap();
        std::fs::write(game.join("mods/sodium.jar"), b"jar").unwrap();
        std::fs::write(game.join("mods/old.jar.disabled"), b"jar").unwrap();
        let jwt = "eyJhbGciOiJIUzI1NiJ9.eyJ4dWlkIjoiMjUzNTQxMjM0NTY3ODkwMSJ9.c2lnbmF0dXJlLWhlcmU";
        std::fs::write(game.join("logs/flint-launch.log"), format!("[Flint] Minecraft 26.2 prepared\njava --accessToken {jwt} --gameDir C:\\Users\\jordan\\x\n")).unwrap();
        std::fs::write(game.join("crash-reports/crash-2026-11-02_10.00.00-client.txt"), "Description: Rendering overlay\nplayer@example.com\n").unwrap();
        std::fs::write(dir.0.join("settings.json"), r#"{"theme":"moss","maxDownloads":8}"#).unwrap();
        record(&dir.0, "game", "PvP", "Minecraft crashed: Rendering overlay (exit code 1).", Some("26.2"));

        let redactor = Redactor::new(Some(r"C:\Users\jordan"), Some("jordan"));
        let files = report(&state, "Flint Client 0.5.5\nSystem: windows x86_64", &redactor);
        let path = dir.0.join("out/Flint diagnostics.zip");
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        write_zip(&path, &files).unwrap();
        assert!(!dir.0.join("out/.Flint diagnostics.zip.flint-part").exists());

        let mut zip = zip::ZipArchive::new(std::fs::File::open(&path).unwrap()).unwrap();
        let mut read = |name: &str| {
            let mut text = String::new();
            zip.by_name(name).unwrap_or_else(|_| panic!("{name} is in the file")).read_to_string(&mut text).unwrap();
            text
        };
        let profiles: Value = serde_json::from_str(&read("profiles.json")).unwrap();
        assert_eq!(profiles[0]["name"], "PvP");
        assert_eq!((profiles[0]["mods"].as_u64(), profiles[0]["modsSwitchedOff"].as_u64()), (Some(1), Some(1)));
        assert!(profiles[0].get("path").is_none() && profiles[0].get("id").is_none());
        let launch = read("logs/PvP-26.2/flint-launch.log");
        assert!(!launch.contains(jwt) && !launch.contains("jordan") && launch.contains("--gameDir ~\\x"), "{launch}");
        let crash = read("crash-reports/PvP-26.2/crash-2026-11-02_10.00.00-client.txt");
        assert!(crash.contains("Rendering overlay") && crash.contains("[email]") && !crash.contains("example.com"));
        assert!(read("errors.json").contains("Rendering overlay"));
        assert!(read("settings.json").contains("\"maxDownloads\": 8"));
        assert!(read("storage.json").contains("\"categories\""));
        assert!(read("README.txt").contains("Nothing was uploaded"));
        assert_eq!(read("summary.txt"), "Flint Client 0.5.5\nSystem: windows x86_64\n");
    }

    #[test]
    fn dates_are_written_in_utc() {
        assert_eq!(date_text(0), "1970-01-01 00:00 UTC");
        assert_eq!(date_text(1_791_849_600 + 3600 + 120), "2026-10-13 01:02 UTC");
        assert_eq!(date_text(951_782_400), "2000-02-29 00:00 UTC");
    }

    #[test]
    fn names_in_the_file_are_plain() {
        assert_eq!(file_part("PvP 1.8.9"), "PvP 1.8.9");
        assert_eq!(file_part("../../evil"), "_.._evil");
        assert_eq!(file_part("Füße/x"), "F__e_x");
        assert_eq!(file_part(".."), "profile");
    }
}
