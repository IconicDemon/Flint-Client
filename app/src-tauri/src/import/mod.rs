//! Importing profiles from other Minecraft launchers (docs/PROFILE_IMPORT.md; TypeScript types in app/src/lib/api.ts,
//! "importing profiles").
//!
//! Discover, validate, preview, stage, copy, verify, commit:
//! - `adapters/` find each launcher's data and list its profiles (one file per launcher, each citing its format);
//! - `listing` turns them into what the page shows, with a stable key per source profile and whether it's already
//!   in Flint (a Flint profile's `origin.key`);
//! - `scan::plan` reads a source game folder: what each part holds and what stays behind, within limits, never
//!   following a link;
//! - `run` copies each chosen profile into `profiles/<id>.flint-importing`, checks every copied file's size, and only
//!   then registers it (`commit`). A cancelled or failed profile's staging folder is removed; one left by a crash is
//!   removed at the next start (`jobs::clean_partials`).
//!
//! Other launchers' folders are only read. The engine is plain functions over paths with injected hooks (cancel,
//! free space, progress), so it's tested without Tauri; the commands at the end are thin wrappers.

mod adapters;
mod scan;
#[cfg(test)]
mod tests;

use crate::core::*;
use crate::data::{clean_name, load_profiles, save_profiles, Profile, ProfilesFile, SUPPORTED_VERSIONS};
use crate::flintpack::unique_name;
use adapters::{Adapter, Found};
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use scan::{How, Limits, Mods, Plan};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager, State};

/// Free space an import leaves on Flint's drive.
pub const SPARE: u64 = scan::GB;
/// A chosen folder without profiles.
const NO_PROFILES: &str = "Flint couldn't find a Minecraft profile in that folder.";
/// Profiles one import takes.
const MAX_KEYS: usize = 500;

// ---------- the contract (app/src/lib/api.ts) ----------

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[serde(rename_all = "lowercase")]
pub enum LauncherId {
    Modrinth,
    Curseforge,
    Prism,
    Multimc,
    Lunar,
    Feather,
    Dawn,
    Official,
    Gdlauncher,
    Atlauncher,
    Folder,
}

impl LauncherId {
    pub fn as_str(self) -> &'static str {
        match self {
            LauncherId::Modrinth => "modrinth",
            LauncherId::Curseforge => "curseforge",
            LauncherId::Prism => "prism",
            LauncherId::Multimc => "multimc",
            LauncherId::Lunar => "lunar",
            LauncherId::Feather => "feather",
            LauncherId::Dawn => "dawn",
            LauncherId::Official => "official",
            LauncherId::Gdlauncher => "gdlauncher",
            LauncherId::Atlauncher => "atlauncher",
            LauncherId::Folder => "folder",
        }
    }
}

/// Where a Flint profile came from (`Profile.origin`). `launcher` is kept as text, so a profile imported by a newer
/// Flint from a launcher this one doesn't know still loads.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ImportOrigin {
    pub launcher: String,
    pub key: String,
    pub imported_at: u64,
}

#[derive(Serialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum LauncherStatus {
    Found,
    Empty,
    Unreadable,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ImportLauncher {
    pub id: LauncherId,
    pub name: String,
    pub status: LauncherStatus,
    pub profiles: usize,
    pub importable: usize,
    pub message: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
pub enum ImportLoader {
    Vanilla,
    Fabric,
    Quilt,
    Forge,
    Neoforge,
    Unknown,
}

impl ImportLoader {
    pub fn label(self) -> &'static str {
        match self {
            ImportLoader::Vanilla => "Vanilla",
            ImportLoader::Fabric => "Fabric",
            ImportLoader::Quilt => "Quilt",
            ImportLoader::Forge => "Forge",
            ImportLoader::Neoforge => "NeoForge",
            ImportLoader::Unknown => "its mod loader",
        }
    }
}

#[derive(Serialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ImportSupport {
    Full,
    Settings,
    Unsupported,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ImportedAs {
    pub profile_id: String,
    pub name: String,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ImportSourceProfile {
    pub key: String,
    pub name: String,
    pub game_version: Option<String>,
    pub loader: ImportLoader,
    pub loader_version: Option<String>,
    pub last_played: Option<u64>,
    pub icon: Option<String>,
    pub mods: usize,
    pub worlds: usize,
    pub support: ImportSupport,
    pub support_note: Option<String>,
    pub imported: Option<ImportedAs>,
    pub shared_folder: bool,
}

#[derive(Serialize, Clone, Debug)]
pub struct ImportListing {
    pub launcher: ImportLauncher,
    pub profiles: Vec<ImportSourceProfile>,
}

#[derive(Serialize, Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
pub enum ImportPart {
    Mods,
    Config,
    Options,
    Keybinds,
    Resourcepacks,
    Shaderpacks,
    Servers,
    Worlds,
    Other,
}

impl ImportPart {
    pub fn label(self) -> &'static str {
        match self {
            ImportPart::Mods => "Mods",
            ImportPart::Config => "Mod settings",
            ImportPart::Options => "Game settings",
            ImportPart::Keybinds => "Key binds",
            ImportPart::Resourcepacks => "Resource packs",
            ImportPart::Shaderpacks => "Shader packs",
            ImportPart::Servers => "Servers",
            ImportPart::Worlds => "Worlds",
            ImportPart::Other => "Mod data",
        }
    }

    /// The order parts are copied in: small settings first.
    pub fn order(self) -> u8 {
        match self {
            ImportPart::Options | ImportPart::Keybinds => 0,
            ImportPart::Servers => 1,
            ImportPart::Config => 2,
            ImportPart::Mods => 3,
            ImportPart::Other => 4,
            ImportPart::Shaderpacks => 5,
            ImportPart::Resourcepacks => 6,
            ImportPart::Worlds => 7,
        }
    }
}

#[derive(Serialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum PartStatus {
    Compatible,
    Attention,
    Unsupported,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ImportPartPreview {
    pub id: ImportPart,
    pub status: PartStatus,
    pub files: usize,
    pub bytes: u64,
    pub count: usize,
    pub note: Option<String>,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ImportTarget {
    pub name: String,
    pub game_version: String,
    pub loader: String,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ImportProfilePreview {
    pub key: String,
    pub name: String,
    pub target: Option<ImportTarget>,
    pub parts: Vec<ImportPartPreview>,
    pub bytes: u64,
    pub world_bytes: u64,
    pub warnings: Vec<String>,
    pub blocker: Option<String>,
    /// Files that stay behind, with reasons (an addition to the contract: `skipped?: ImportSkip[]`).
    pub skipped: Vec<ImportSkip>,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ImportPreview {
    pub profiles: Vec<ImportProfilePreview>,
    pub bytes: u64,
    pub free_bytes: Option<u64>,
    pub enough_space: bool,
}

#[derive(Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ImportRequest {
    pub launcher: LauncherId,
    #[serde(default)]
    pub folder: Option<String>,
    pub keys: Vec<String>,
    #[serde(default)]
    pub worlds: bool,
}

#[derive(Serialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ProgressState {
    Waiting,
    Copying,
    Verifying,
    Done,
    Failed,
    Skipped,
    Cancelled,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ImportProfileProgress {
    pub key: String,
    pub name: String,
    pub state: ProgressState,
    pub bytes_done: u64,
    pub bytes_total: u64,
    pub files_done: usize,
    pub files_total: usize,
    pub current: Option<String>,
    pub profile_id: Option<String>,
    pub error: Option<String>,
}

#[derive(Serialize, Clone, Debug)]
pub struct ImportProgress {
    pub job: String,
    pub profiles: Vec<ImportProfileProgress>,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct ImportSkip {
    pub path: String,
    pub reason: String,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ImportResultProfile {
    pub key: String,
    pub name: String,
    pub profile_id: Option<String>,
    pub state: ProgressState,
    pub error: Option<String>,
    pub warnings: Vec<String>,
    pub skipped: Vec<ImportSkip>,
    /// What really came over, part by part, after skips (an addition to the first contract: `parts?:`). Empty when
    /// nothing was imported.
    pub parts: Vec<ImportPartPreview>,
}

#[derive(Serialize, Clone, Debug)]
pub struct ImportResult {
    pub profiles: Vec<ImportResultProfile>,
}

// ---------- sources ----------

/// The system folders launchers keep their data in. Tests point every one of them into a temporary folder.
#[derive(Clone, Debug, Default)]
pub struct Env {
    pub home: Option<PathBuf>,
    /// Windows: %APPDATA% (Roaming). macOS: ~/Library/Application Support. Linux: ~/.local/share.
    pub appdata: Option<PathBuf>,
    /// Windows: %LOCALAPPDATA%. macOS: ~/Library/Application Support. Linux: ~/.local/share.
    pub local: Option<PathBuf>,
    /// Windows: %APPDATA%. macOS: ~/Library/Application Support. Linux: ~/.config.
    pub config: Option<PathBuf>,
    /// The user's Documents folder (CurseForge keeps instances there on macOS; Windows may redirect it to OneDrive).
    pub documents: Option<PathBuf>,
    /// Where copies of other launchers' databases are read (each in a fresh folder, removed after).
    pub temp: PathBuf,
    /// Flint's own data folder: never read as another launcher's.
    pub flint_root: Option<PathBuf>,
    /// The environment variables launchers use to move their data (`ENV_VARS`), as set for Flint.
    pub vars: HashMap<String, PathBuf>,
    /// Whether places fixed on this system (`C:\MultiMC`, `/Applications/…`) are probed; off in tests.
    pub fixed_places: bool,
}

/// Environment variables that move a launcher's data folder (each adapter cites its source).
const ENV_VARS: [&str; 4] = ["THESEUS_CONFIG_DIR", "PRISMLAUNCHER_DATA_DIR", "DAWN_LAUNCHER_HOME", "GDL_RUNTIME_PATH"];

impl Env {
    pub fn system(flint_root: &Path) -> Self {
        Self {
            home: dirs::home_dir(),
            appdata: dirs::data_dir(),
            local: dirs::data_local_dir(),
            config: dirs::config_dir(),
            documents: dirs::document_dir(),
            temp: std::env::temp_dir(),
            flint_root: Some(flint_root.to_path_buf()),
            vars: ENV_VARS.iter().filter_map(|k| std::env::var_os(k).filter(|v| !v.is_empty()).map(|v| (k.to_string(), PathBuf::from(v)))).collect(),
            fixed_places: true,
        }
    }

    pub fn var(&self, name: &str) -> Option<PathBuf> {
        self.vars.get(name).filter(|p| p.is_absolute()).cloned()
    }

    /// Whether `path` is Flint's own data folder or inside it (a folder holding it is fine: only a chosen folder's own
    /// entries are read, never what is below its other folders).
    pub fn is_flint(&self, path: &Path) -> bool {
        let Some(root) = &self.flint_root else { return false };
        let (path, root) = (canonical_text(path), canonical_text(root));
        path == root || path.starts_with(&format!("{root}{}", std::path::MAIN_SEPARATOR))
    }
}

/// One profile of another launcher, as its adapter read it.
#[derive(Clone, Debug)]
pub struct Source {
    /// The adapter that read it (its key is made with this, also when found in a chosen folder).
    pub launcher: LauncherId,
    /// The launcher's own unique id for it, when it has one; otherwise its game folder identifies it.
    pub id: Option<String>,
    pub name: String,
    pub game_version: Option<String>,
    pub loader: ImportLoader,
    pub loader_version: Option<String>,
    pub last_played: Option<u64>,
    /// An icon file, or (`icon_url`) a `data:` URL its launcher stores.
    pub icon: Option<PathBuf>,
    pub icon_url: Option<String>,
    /// Its game folder: where its top-level entries are, unless `overrides` or `prefer` say otherwise.
    pub game_dir: PathBuf,
    /// Top-level entries its launcher keeps elsewhere: (`"mods"`, Lunar's per-version mods folder), (`"options.txt"`,
    /// Dawn's `feather/mc_options_v2.txt`). A path its launcher configured; it may be a link.
    pub overrides: Vec<(String, PathBuf)>,
    /// Folders whose top-level entries win over the game folder's when they have them, in order (Dawn's Auto Sync
    /// group, then its private content).
    pub prefer: Vec<PathBuf>,
    /// Mod file names (lowercase) its launcher put there itself: never counted or copied.
    pub launcher_mods: Vec<String>,
    /// What of the launcher's own stays behind (its built-in mods, its own settings), shown in the preview.
    pub notes: Vec<String>,
    /// Why its metadata couldn't be read (it's listed as unsupported, with this as the note).
    pub problem: Option<String>,
}

impl Source {
    pub fn new(launcher: LauncherId, name: &str, game_dir: PathBuf) -> Self {
        Self {
            launcher,
            id: None,
            name: name.to_owned(),
            game_version: None,
            loader: ImportLoader::Unknown,
            loader_version: None,
            last_played: None,
            icon: None,
            icon_url: None,
            game_dir,
            overrides: Vec::new(),
            prefer: Vec::new(),
            launcher_mods: Vec::new(),
            notes: Vec::new(),
            problem: None,
        }
    }

    /// Where its top-level entry `top` is, and whether its launcher configured that place (so it may be a link).
    pub fn resolve(&self, top: &str) -> (PathBuf, bool) {
        if let Some((_, path)) = self.overrides.iter().find(|(name, _)| name == top) {
            return (path.clone(), true);
        }
        for dir in &self.prefer {
            let path = dir.join(top);
            if std::fs::symlink_metadata(&path).is_ok() {
                return (path, false);
            }
        }
        (self.game_dir.join(top), false)
    }

    /// Whether any of its folders is there.
    pub fn exists(&self) -> bool {
        self.game_dir.is_dir() || self.prefer.iter().any(|p| p.is_dir()) || self.overrides.iter().any(|(_, p)| p.exists())
    }

    /// Whether a mod file is its launcher's own.
    pub fn launcher_mod(&self, name: &str) -> bool {
        let lower = name.to_ascii_lowercase();
        let lower = lower.strip_suffix(".disabled").unwrap_or(&lower);
        self.launcher_mods.iter().any(|m| m == lower)
    }
}

/// A path as one stable text: canonical when it exists, without Windows' `\\?\`, lowercased where folders ignore case.
pub fn canonical_text(path: &Path) -> String {
    let real = std::fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
    let text = real.display().to_string();
    let text = text.strip_prefix(r"\\?\UNC\").map(|t| format!(r"\\{t}")).unwrap_or_else(|| text.strip_prefix(r"\\?\").unwrap_or(&text).to_owned());
    let text = text.trim_end_matches(['/', '\\']).to_owned();
    if cfg!(any(windows, target_os = "macos")) {
        text.to_lowercase()
    } else {
        text
    }
}

/// A source profile's key: a hash of the launcher and the launcher's own id for it (or its folder's canonical path),
/// so nothing about the player is in clear text.
pub fn origin_key(launcher: LauncherId, id: Option<&str>, game_dir_text: &str) -> String {
    let native = match id {
        Some(id) => format!("id:{id}"),
        None => format!("dir:{game_dir_text}"),
    };
    let digest = Sha256::digest(format!("flint-import-v1\n{}\n{native}", launcher.as_str()).as_bytes());
    hex::encode(&digest[..16])
}

fn display_text(text: &str, max: usize) -> String {
    let clean: String = text.chars().filter(|c| !c.is_control()).take(max).collect();
    clean.trim().to_owned()
}

const MAX_ICON: usize = 256 * 1024;

/// The picture type of an image's bytes (PNG, JPEG, GIF or WebP), from its own header.
fn image_mime(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some("image/png")
    } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some("image/jpeg")
    } else if bytes.starts_with(b"GIF8") {
        Some("image/gif")
    } else if bytes.len() > 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Some("image/webp")
    } else {
        None
    }
}

/// A small icon file as a data: URL (at most 256 KB), or None.
fn icon_data(path: &Path) -> Option<String> {
    let meta = scan::real_file(path)?;
    if meta.len() > MAX_ICON as u64 {
        return None;
    }
    let bytes = std::fs::read(path).ok()?;
    Some(format!("data:{};base64,{}", image_mime(&bytes)?, STANDARD.encode(bytes)))
}

/// A data: URL a launcher stored as an icon, re-encoded, when it really is a small image.
fn valid_icon_url(url: &str) -> Option<String> {
    let (_, encoded) = url.strip_prefix("data:image/")?.split_once(";base64,")?;
    if encoded.len() > MAX_ICON * 4 / 3 + 4 {
        return None;
    }
    let bytes = STANDARD.decode(encoded.trim()).ok()?;
    Some(format!("data:{};base64,{}", image_mime(&bytes)?, STANDARD.encode(bytes)))
}

/// Whether Flint can take the profile, and why not (or why only its settings).
fn support_of(source: &Source) -> (ImportSupport, Option<String>) {
    if let Some(problem) = &source.problem {
        return (ImportSupport::Unsupported, Some(problem.clone()));
    }
    let Some(version) = source.game_version.as_deref() else {
        return (ImportSupport::Unsupported, Some("Flint couldn't tell which Minecraft version it uses, so it can't import it.".into()));
    };
    if !SUPPORTED_VERSIONS.contains(&version) {
        return (ImportSupport::Unsupported, Some(format!("Flint doesn't offer Minecraft {}.", display_text(version, 40))));
    }
    match source.loader {
        ImportLoader::Vanilla => (ImportSupport::Full, None),
        ImportLoader::Fabric if version == "1.8.9" => {
            (ImportSupport::Full, Some("Its mods are made for Legacy Fabric, which Flint's 1.8.9 doesn't run, so they stay behind.".into()))
        }
        ImportLoader::Fabric => (ImportSupport::Full, None),
        loader => (
            ImportSupport::Settings,
            Some(format!("Flint doesn't run {}, so it comes over as a Vanilla profile: its settings, packs, servers and worlds, without its mods.", loader.label())),
        ),
    }
}

/// The Flint profile a source becomes: its loader, and what happens to its mods.
fn how_for(source: &Source, worlds: bool) -> (&'static str, How) {
    let version = source.game_version.as_deref().unwrap_or_default();
    let (loader, mods) = match source.loader {
        ImportLoader::Fabric if version == "1.8.9" => ("fabric", Mods::Legacy189),
        ImportLoader::Fabric => ("fabric", Mods::Copy),
        ImportLoader::Vanilla => ("vanilla", Mods::Vanilla),
        other => ("vanilla", Mods::NotRun(other.label())),
    };
    let shaders_run = loader == "fabric" && version != "1.8.9";
    (loader, How { mods, copy_worlds: worlds, list_worlds: true, shaders_run })
}

/// One launcher's profiles as the page shows them, with the sources they were read from (same order).
#[derive(Clone, Debug)]
pub struct Listed {
    pub listing: ImportListing,
    pub sources: Vec<Source>,
}

impl Listed {
    fn find(&self, key: &str) -> Option<(&Source, &ImportSourceProfile)> {
        self.listing.profiles.iter().position(|p| p.key == key).map(|i| (&self.sources[i], &self.listing.profiles[i]))
    }
}

/// Flint profiles by the key of the source they were imported from (the newest import of each).
fn imported_keys(existing: &ProfilesFile) -> HashMap<String, (u64, ImportedAs)> {
    let mut map: HashMap<String, (u64, ImportedAs)> = HashMap::new();
    for profile in &existing.profiles {
        let Some(origin) = &profile.origin else { continue };
        let entry = (origin.imported_at, ImportedAs { profile_id: profile.id.clone(), name: profile.name.clone() });
        if map.get(&origin.key).is_none_or(|(at, _)| *at <= origin.imported_at) {
            map.insert(origin.key.clone(), entry);
        }
    }
    map
}

/// Turns sources into a listing: keys, support, counts, already-imported, shared folders; newest played first.
fn listing(id: LauncherId, name: &str, sources: Vec<Source>, message: Option<String>, existing: &ProfilesFile) -> Listed {
    let imported = imported_keys(existing);
    let dirs: Vec<String> = sources.iter().map(|s| canonical_text(&s.game_dir)).collect();
    let mut per_dir: HashMap<&str, usize> = HashMap::new();
    for dir in &dirs {
        *per_dir.entry(dir.as_str()).or_default() += 1;
    }
    let mut seen: HashSet<String> = HashSet::new();
    let mut rows: Vec<(Source, ImportSourceProfile)> = Vec::new();
    for (source, dir) in sources.into_iter().zip(dirs.iter()) {
        let key = origin_key(source.launcher, source.id.as_deref(), dir);
        // The same profile found twice (two locations of one launcher) is listed once.
        if !seen.insert(key.clone()) {
            continue;
        }
        let (support, support_note) = support_of(&source);
        let readable = source.problem.is_none();
        let profile = ImportSourceProfile {
            imported: imported.get(&key).map(|(_, as_)| as_.clone()),
            key,
            name: display_text(&source.name, 100),
            game_version: source.game_version.as_deref().map(|v| display_text(v, 40)),
            loader: source.loader,
            loader_version: source.loader_version.as_deref().map(|v| display_text(v, 40)),
            last_played: source.last_played,
            icon: source.icon_url.as_deref().and_then(valid_icon_url).or_else(|| source.icon.as_deref().and_then(icon_data)),
            mods: if readable { scan::count_mods(&source) } else { 0 },
            worlds: if readable { scan::count_worlds(&source) } else { 0 },
            support,
            support_note,
            shared_folder: per_dir.get(dir.as_str()).is_some_and(|n| *n > 1),
        };
        rows.push((source, profile));
    }
    rows.sort_by(|(_, a), (_, b)| b.last_played.unwrap_or(0).cmp(&a.last_played.unwrap_or(0)).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
    let importable = rows.iter().filter(|(_, p)| p.support != ImportSupport::Unsupported).count();
    let status = if !rows.is_empty() {
        LauncherStatus::Found
    } else if message.is_some() {
        LauncherStatus::Unreadable
    } else {
        LauncherStatus::Empty
    };
    // Every message the page shows is a sentence a player can read.
    let message = message.or_else(|| (status == LauncherStatus::Empty).then(|| format!("No profiles found in {name}.")));
    let launcher = ImportLauncher { id, name: name.to_owned(), status, profiles: rows.len(), importable, message };
    let (sources, profiles) = rows.into_iter().unzip();
    Listed { listing: ImportListing { launcher, profiles }, sources }
}

/// A profile whose game folder is Flint's own (another launcher pointed at a Flint profile) is never imported back.
fn flag_flint_folders(env: &Env, sources: &mut [Source]) {
    for source in sources.iter_mut().filter(|s| s.problem.is_none() && env.is_flint(&s.game_dir)) {
        source.problem = Some("Its game folder is one of Flint's own profiles.".into());
    }
}

/// One launcher on this computer: None when it isn't installed (none of its data folders is there).
pub fn discover(adapter: &dyn Adapter, env: &Env, existing: &ProfilesFile) -> Option<Listed> {
    let mut sources = Vec::new();
    let mut problems = Vec::new();
    let mut installed = false;
    let mut read: HashSet<String> = HashSet::new();
    for root in adapter.locations(env) {
        // A data folder the launcher itself uses may be a link (moved to another drive); links inside it never are
        // followed. The same folder named twice (Roaming is both `appdata` and `config` on Windows) is read once.
        if !root.is_dir() || env.is_flint(&root) || !read.insert(canonical_text(&root)) {
            continue;
        }
        match adapter.read(&root, env) {
            Found::NotHere => {}
            Found::Profiles(found) => {
                installed = true;
                sources.extend(found);
            }
            Found::Unreadable(why) => {
                installed = true;
                problems.push(why);
            }
        }
    }
    if !installed {
        return None;
    }
    flag_flint_folders(env, &mut sources);
    let message = (!problems.is_empty()).then(|| problems.join(" "));
    Some(listing(adapter.id(), adapter.name(), sources, message, existing))
}

/// Every launcher found on this computer: most profiles first, then by name.
pub fn discover_all(adapters: &[&dyn Adapter], env: &Env, existing: &ProfilesFile) -> Vec<ImportLauncher> {
    let mut found: Vec<ImportLauncher> = adapters.iter().filter_map(|a| discover(*a, env, existing)).map(|l| l.listing.launcher).collect();
    found.sort_by(|a, b| b.profiles.cmp(&a.profiles).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
    found
}

/// A folder holds a game folder's parts (settings, mods, worlds or packs).
fn looks_like_game_folder(dir: &Path) -> bool {
    scan::real_file(&dir.join("options.txt")).is_some() || ["mods", "saves", "resourcepacks", "config", "shaderpacks"].iter().any(|d| scan::real_dir(&dir.join(d)))
}

/// A plain game folder as a source: its version only when options.txt names a version Flint verified, its loader from
/// its mods' own descriptors.
fn plain_folder(dir: &Path) -> Source {
    let name = dir
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .filter(|n| !n.trim_start_matches('.').eq_ignore_ascii_case("minecraft"))
        .or_else(|| dir.parent().and_then(|p| p.file_name()).map(|n| n.to_string_lossy().to_string()))
        .unwrap_or_else(|| "Minecraft folder".into());
    let mut source = Source::new(LauncherId::Folder, &name, dir.to_path_buf());
    source.game_version = scan::options_data_version(&dir.join("options.txt")).and_then(crate::gamesettings::release_of_data_version).map(str::to_owned);
    source.loader = folder_loader(dir);
    if source.game_version.is_none() {
        source.problem = Some("Flint couldn't tell which Minecraft version this folder was used with (its options.txt doesn't name one Flint knows), so it can't import it.".into());
    }
    source.last_played = scan::real_file(&dir.join("options.txt")).and_then(|m| m.modified().ok()).and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map(|d| d.as_secs());
    source
}

/// The loader a plain folder's mods are made for: Vanilla without mods; the one every readable mod names; otherwise
/// unknown.
fn folder_loader(dir: &Path) -> ImportLoader {
    let mods = dir.join("mods");
    if !scan::real_dir(&mods) {
        return ImportLoader::Vanilla;
    }
    let mut kinds = HashSet::new();
    let Ok(entries) = std::fs::read_dir(&mods) else { return ImportLoader::Unknown };
    for entry in entries.flatten().take(1000) {
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else { continue };
        if !scan::is_mod_file(&name) || !entry.metadata().is_ok_and(|m| m.is_file() && !scan::is_link(&m)) {
            continue;
        }
        kinds.insert(match scan::jar_kind(&entry.path()) {
            scan::JarKind::Fabric => ImportLoader::Fabric,
            scan::JarKind::Quilt => ImportLoader::Quilt,
            scan::JarKind::Forge => ImportLoader::Forge,
            scan::JarKind::NeoForge => ImportLoader::Neoforge,
            _ => continue,
        });
    }
    match kinds.len() {
        0 => ImportLoader::Vanilla,
        1 => kinds.into_iter().next().unwrap_or(ImportLoader::Unknown),
        _ => ImportLoader::Unknown,
    }
}

/// A folder the player chose: another launcher's data folder, one of its instances, or a plain game folder (itself,
/// or the `.minecraft`/`minecraft` inside it).
pub fn discover_folder(adapters: &[&dyn Adapter], folder: &Path, env: &Env, existing: &ProfilesFile) -> Listed {
    let mut sources = Vec::new();
    let mut problems = Vec::new();
    let mut names = Vec::new();
    for adapter in adapters {
        match adapter.read(folder, env) {
            Found::Profiles(found) if !found.is_empty() => sources.extend(found),
            Found::Unreadable(why) => problems.push(why),
            _ => continue,
        }
        names.push(adapter.name());
    }
    if sources.is_empty() && problems.is_empty() {
        for adapter in adapters {
            if let Some(source) = adapter.instance(folder, env) {
                names.push(adapter.name());
                sources.push(source);
                break;
            }
        }
    }
    if sources.is_empty() && problems.is_empty() {
        let inner = [folder.to_path_buf(), folder.join(".minecraft"), folder.join("minecraft")];
        if let Some(game) = inner.into_iter().find(|d| d.is_dir() && looks_like_game_folder(d)) {
            sources.push(plain_folder(&game));
        }
    }
    let name = match names.as_slice() {
        [one] => format!("{one} (chosen folder)"),
        _ => "Chosen folder".to_owned(),
    };
    let message = if !problems.is_empty() {
        Some(problems.join(" "))
    } else {
        sources.is_empty().then(|| NO_PROFILES.to_owned())
    };
    flag_flint_folders(env, &mut sources);
    let mut listed = listing(LauncherId::Folder, &name, sources, message, existing);
    if listed.listing.profiles.is_empty() && listed.listing.launcher.status == LauncherStatus::Unreadable && problems.is_empty() {
        listed.listing.launcher.status = LauncherStatus::Empty;
    }
    listed
}

/// What one launcher (or a chosen folder) holds now.
pub fn list(adapters: &[&dyn Adapter], launcher: LauncherId, folder: Option<&Path>, env: &Env, existing: &ProfilesFile) -> CmdResult<Listed> {
    if launcher == LauncherId::Folder {
        let folder = folder.ok_or("Choose a folder first.")?;
        let listed = discover_folder(adapters, folder, env, existing);
        // A folder with nothing Flint can list is an error the page shows as it is.
        if listed.listing.profiles.is_empty() {
            return Err(listed.listing.launcher.message.unwrap_or_else(|| NO_PROFILES.to_owned()));
        }
        return Ok(listed);
    }
    let adapter = adapters.iter().find(|a| a.id() == launcher).ok_or("Flint can't read that launcher.")?;
    Ok(discover(*adapter, env, existing).unwrap_or_else(|| {
        let mut empty = listing(launcher, adapter.name(), Vec::new(), None, existing);
        empty.listing.launcher.message = Some(format!("Flint didn't find {} on this computer.", adapter.name()));
        empty
    }))
}

// ---------- preview ----------

fn wanted_name(source: &Source) -> String {
    clean_name(&source.name).unwrap_or_else(|_| "Imported profile".into())
}

/// The keys of a request, without repeats, in its order.
fn request_keys(request: &ImportRequest) -> Vec<String> {
    let mut seen = HashSet::new();
    request.keys.iter().filter(|k| !k.is_empty() && k.len() <= 64).filter(|k| seen.insert(k.to_string())).take(MAX_KEYS).cloned().collect()
}

struct Planned<'a> {
    key: String,
    source: Option<&'a Source>,
    name: String,
    target: Option<ImportTarget>,
    plan: Plan,
    warnings: Vec<String>,
    blocker: Option<String>,
}

/// Plans every requested profile, giving each a unique name among Flint's profiles and the others imported with it.
fn plan_all<'a>(listed: &'a Listed, existing: &ProfilesFile, request: &ImportRequest, limits: &Limits, list_worlds: bool) -> Vec<Planned<'a>> {
    let mut taken: Vec<String> = existing.profiles.iter().map(|p| p.name.clone()).collect();
    let launcher = &listed.listing.launcher.name;
    request_keys(request)
        .into_iter()
        .map(|key| {
            let Some((source, shown)) = listed.find(&key) else {
                return Planned {
                    key,
                    source: None,
                    name: "Profile".into(),
                    target: None,
                    plan: Plan::default(),
                    warnings: Vec::new(),
                    blocker: Some(format!("It's no longer in {launcher}.")),
                };
            };
            let mut warnings = Vec::new();
            if shown.support == ImportSupport::Unsupported {
                return Planned { key, source: Some(source), name: shown.name.clone(), target: None, plan: Plan::default(), warnings, blocker: shown.support_note.clone() };
            }
            if let Some(note) = &shown.support_note {
                warnings.push(note.clone());
            }
            if shown.shared_folder {
                warnings.push(format!("It shares its game folder with other {launcher} profiles, so the settings, packs and worlds that come over are that folder's."));
            }
            let (loader, mut how) = how_for(source, request.worlds);
            how.list_worlds = list_worlds;
            let plan = scan::plan(source, how, limits);
            warnings.extend(source.notes.iter().cloned());
            warnings.extend(plan.warnings.iter().cloned());
            let blocker = plan.blocker.clone();
            let target = blocker.is_none().then(|| {
                let name = unique_name(&wanted_name(source), &taken);
                taken.push(name.clone());
                ImportTarget { name, game_version: source.game_version.clone().unwrap_or_default(), loader: loader.to_owned() }
            });
            Planned { key, source: Some(source), name: shown.name.clone(), target, plan, warnings, blocker }
        })
        .collect()
}

/// What an import with these choices would do. Reads only.
pub fn preview(listed: &Listed, existing: &ProfilesFile, request: &ImportRequest, limits: &Limits, free: Option<u64>) -> ImportPreview {
    let planned = plan_all(listed, existing, request, limits, true);
    let profiles: Vec<ImportProfilePreview> = planned
        .into_iter()
        .map(|p| {
            let mut warnings = p.warnings;
            if !p.plan.skipped.is_empty() {
                warnings.push(format!("{} stay behind (listed below).", if p.plan.skipped.len() == 1 { "1 file".to_owned() } else { format!("{} files", p.plan.skipped.len()) }));
            }
            ImportProfilePreview {
                key: p.key,
                name: p.name,
                bytes: if p.target.is_some() { p.plan.bytes } else { 0 },
                world_bytes: p.plan.world_bytes,
                target: p.target,
                parts: p.plan.parts,
                warnings,
                blocker: p.blocker,
                skipped: p.plan.skipped,
            }
        })
        .collect();
    let bytes: u64 = profiles.iter().map(|p| p.bytes).sum();
    ImportPreview { enough_space: free.is_none_or(|f| bytes.saturating_add(SPARE) <= f), profiles, bytes, free_bytes: free }
}

pub const RUNNING: &str = "Minecraft is running from this profile's folder. Close it before importing, so its worlds and settings are saved first.";

/// Warns first about profiles whose game folder a running Minecraft uses (`running`): its worlds and settings could
/// change while they're copied. A warning, not a blocker: the copy only reads, and a file that changes mid-copy is
/// left out and listed.
pub fn warn_running(shown: &mut ImportPreview, listed: &Listed, running: &dyn Fn(&Path) -> bool) {
    for p in shown.profiles.iter_mut().filter(|p| p.target.is_some()) {
        if listed.find(&p.key).is_some_and(|(source, _)| running(&source.game_dir)) {
            p.warnings.insert(0, RUNNING.to_owned());
        }
    }
}

/// Whether a game (or another program) runs from `dir` now. Anything Flint can't check counts as not running: this
/// only adds a warning.
fn game_running_in(dir: &Path, scan: &crate::core::ProcessScan) -> bool {
    #[cfg(windows)]
    {
        let _ = scan;
        crate::datadir::game_using(dir).is_some_and(|why| !why.starts_with("couldn't"))
    }
    #[cfg(not(windows))]
    {
        let text = dir.display().to_string();
        scan.lines().is_ok_and(|lines| lines.iter().any(|line| crate::core::game_dir_in_command(line, &text)))
    }
}

// ---------- the import ----------

/// What the engine asks of whoever runs it.
pub struct Hooks<'a> {
    /// Whether the player cancelled.
    pub cancelled: &'a dyn Fn() -> bool,
    /// Free bytes on the drive holding this folder, when known.
    pub free_space: &'a dyn Fn(&Path) -> Option<u64>,
    /// The import's progress; `important` for a change of state (never throttled).
    pub progress: &'a mut dyn FnMut(&ImportProgress, bool),
}

#[derive(Debug)]
pub enum Outcome {
    /// Every chosen profile was tried (some may have failed or been skipped).
    Finished(ImportResult),
    /// Stopped by the player: the profiles finished before it stay.
    Cancelled(ImportResult),
    /// Refused before anything was copied (not enough space).
    Refused(String),
}

enum Stop {
    Cancelled,
    Failed(String),
}

const CHUNK: usize = 1024 * 1024;

/// Why an I/O error on a source file means it's skipped.
fn read_failure(error: &std::io::Error) -> String {
    match error.kind() {
        std::io::ErrorKind::NotFound => "It was removed before Flint could copy it.".into(),
        std::io::ErrorKind::PermissionDenied => "Flint isn't allowed to read it.".into(),
        _ if matches!(error.raw_os_error(), Some(32) | Some(33)) && cfg!(windows) => "Another program is using it, so Flint couldn't read it.".into(),
        _ => "Flint couldn't read it.".into(),
    }
}

fn write_failure(error: &std::io::Error) -> String {
    if error.kind() == std::io::ErrorKind::StorageFull || (cfg!(windows) && matches!(error.raw_os_error(), Some(39) | Some(112))) {
        "Flint's drive is full.".into()
    } else {
        "Flint couldn't write the new profile's files.".into()
    }
}

enum FileCopy {
    Copied(u64),
    Skipped(String),
}

/// Copies one file in chunks, checking for a cancel between chunks. A source problem skips the file; a problem
/// writing stops the profile.
fn copy_file(from: &Path, to: &Path, max: u64, buf: &mut [u8], tick: &mut dyn FnMut(u64) -> bool) -> Result<FileCopy, Stop> {
    let mut source = match std::fs::File::open(from) {
        Ok(file) => file,
        Err(e) => return Ok(FileCopy::Skipped(read_failure(&e))),
    };
    let mut out = std::fs::OpenOptions::new().write(true).create_new(true).open(to).map_err(|e| Stop::Failed(write_failure(&e)))?;
    let mut done: u64 = 0;
    let result = loop {
        let n = match source.read(buf) {
            Ok(0) => break Ok(FileCopy::Copied(done)),
            Ok(n) => n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => break Ok(FileCopy::Skipped(read_failure(&e))),
        };
        done += n as u64;
        if done > max {
            break Ok(FileCopy::Skipped(format!("It grew past {} while Flint copied it.", scan::size_text(max))));
        }
        if let Err(e) = out.write_all(&buf[..n]) {
            break Err(Stop::Failed(write_failure(&e)));
        }
        if !tick(n as u64) {
            break Err(Stop::Cancelled);
        }
    };
    let flushed = out.flush();
    drop(out);
    match (result, flushed) {
        (Ok(FileCopy::Copied(n)), Ok(())) => Ok(FileCopy::Copied(n)),
        (Ok(FileCopy::Copied(_)), Err(e)) => {
            let _ = std::fs::remove_file(to);
            Err(Stop::Failed(write_failure(&e)))
        }
        (other, _) => {
            let _ = std::fs::remove_file(to);
            other
        }
    }
}

/// Copies a plan into `staging`, then checks every copied file against its source (verify). Returns what stayed
/// behind. `tick` gets (bytes, files done, what's being copied) and returns false to stop.
fn copy_plan(staging: &Path, plan: &Plan, limits: &Limits, tick: &mut dyn FnMut(u64, usize, &str, bool) -> bool) -> Result<Vec<ImportSkip>, Stop> {
    let mut skipped = Vec::new();
    let mut copied: Vec<(usize, u64)> = Vec::with_capacity(plan.files.len());
    let mut made: HashSet<PathBuf> = HashSet::new();
    let mut buf = vec![0u8; CHUNK];
    let mut label = String::new();
    for (i, file) in plan.files.iter().enumerate() {
        let now = file.label();
        if now != label {
            label = now;
            if !tick(0, i, &label, false) {
                return Err(Stop::Cancelled);
            }
        }
        let to = scan::join(staging, &file.rel);
        if let Some(parent) = to.parent() {
            if !made.contains(parent) {
                std::fs::create_dir_all(parent).map_err(|e| Stop::Failed(write_failure(&e)))?;
                made.insert(parent.to_path_buf());
            }
        }
        let mut chunk_tick = |n: u64| tick(n, i, &label, false);
        match copy_file(&file.source(plan), &to, limits.max_file, &mut buf, &mut chunk_tick)? {
            FileCopy::Copied(n) => copied.push((i, n)),
            FileCopy::Skipped(reason) => skipped.push(ImportSkip { path: file.rel.clone(), reason }),
        }
    }
    // Verify: every copy is the size that was read, and its source didn't change while it was read.
    if !tick(0, plan.files.len(), "Checking the copied files", true) {
        return Err(Stop::Cancelled);
    }
    for (i, written) in copied {
        let file = &plan.files[i];
        let to = scan::join(staging, &file.rel);
        match std::fs::metadata(&to) {
            Ok(meta) if meta.len() == written => {}
            _ => return Err(Stop::Failed(format!("A copied file ({}) doesn't match what was read; Flint's drive may have a problem.", file.rel))),
        }
        if std::fs::symlink_metadata(file.source(plan)).is_ok_and(|m| m.len() != written) {
            let _ = std::fs::remove_file(&to);
            skipped.push(ImportSkip { path: file.rel.clone(), reason: "It changed while Flint copied it (is Minecraft running from that folder?).".into() });
        }
    }
    Ok(skipped)
}

/// Registers a copied profile: its folder name, the staging folder renamed to it, profiles.json saved, all under the
/// profiles lock. On failure nothing is registered (the caller removes the staging folder).
fn commit(state: &AppState, staging: &Path, id: &str, source: &Source, key: &str, loader: &str) -> CmdResult<Profile> {
    let _guard = state.file_lock.blocking_lock();
    let mut file = load_profiles(state);
    let taken: Vec<String> = file.profiles.iter().map(|p| p.name.clone()).collect();
    let mut profile = Profile {
        id: id.to_owned(),
        name: unique_name(&wanted_name(source), &taken),
        game_version: source.game_version.clone().unwrap_or_default(),
        loader: loader.to_owned(),
        // Flint's own (newest stable) Fabric Loader: its runtime needs a recent one, and mods made for older loaders
        // run on it.
        loader_version: None,
        created_at: now_secs(),
        installed: false,
        // The player's own mods came over; Flint's performance set isn't added on top.
        performance: None,
        origin: Some(ImportOrigin { launcher: source.launcher.as_str().to_owned(), key: key.to_owned(), imported_at: now_secs() }),
        ..Profile::default()
    };
    crate::data::validate_version(&profile.game_version, &profile.loader)?;
    crate::profilefolder::assign(state, &file, &mut profile);
    let dir = state.paths.profile_dir(id);
    std::fs::rename(staging, &dir).map_err(|_| "Flint couldn't finish the new profile. Try importing it again.".to_string())?;
    file.profiles.push(profile.clone());
    if file.selected.is_none() {
        file.selected = Some(id.to_owned());
    }
    if let Err(e) = save_profiles(state, &file) {
        let _ = std::fs::remove_dir_all(&dir);
        return Err(e);
    }
    // Its settings came with it: Auto Sync takes them as they are rather than replacing them at its first Play.
    crate::gamesettings::autosync::take_as_is(state, id);
    Ok(profile)
}

/// Imports the requested profiles of `listed`, one after another. A cancel or failure of one profile removes its
/// staging folder and registers nothing for it; profiles already finished stay.
pub fn run(state: &AppState, listed: &Listed, request: &ImportRequest, job: &str, limits: &Limits, hooks: &mut Hooks) -> Outcome {
    let existing = load_profiles(state);
    let mut planned = plan_all(listed, &existing, request, limits, false);
    let mut progress = ImportProgress {
        job: job.to_owned(),
        profiles: planned
            .iter()
            .map(|p| ImportProfileProgress {
                key: p.key.clone(),
                name: p.target.as_ref().map(|t| t.name.clone()).unwrap_or_else(|| p.name.clone()),
                state: if p.target.is_some() { ProgressState::Waiting } else { ProgressState::Skipped },
                bytes_done: 0,
                bytes_total: p.plan.bytes,
                files_done: 0,
                files_total: p.plan.files.len(),
                current: None,
                profile_id: None,
                error: p.blocker.clone(),
            })
            .collect(),
    };
    let mut results: Vec<ImportResultProfile> = planned
        .iter()
        .map(|p| ImportResultProfile {
            key: p.key.clone(),
            name: p.target.as_ref().map(|t| t.name.clone()).unwrap_or_else(|| p.name.clone()),
            profile_id: None,
            state: if p.target.is_some() { ProgressState::Waiting } else { ProgressState::Skipped },
            error: p.blocker.clone(),
            warnings: p.warnings.clone(),
            skipped: p.plan.skipped.clone(),
            parts: Vec::new(),
        })
        .collect();
    (hooks.progress)(&progress, true);

    // Space for everything before anything is copied.
    let needed: u64 = planned.iter().filter(|p| p.target.is_some()).map(|p| p.plan.bytes).sum();
    let profiles_dir = state.paths.root.join("profiles");
    if let Some(free) = (hooks.free_space)(&state.paths.root) {
        if needed.saturating_add(SPARE) > free {
            return Outcome::Refused(format!(
                "There isn't enough space on Flint's drive: the import needs {} (plus 1 GB to spare) and {} is free. Free up space, or import fewer profiles or no worlds.",
                scan::size_text(needed),
                scan::size_text(free)
            ));
        }
    }

    let mut cancelled = false;
    for i in 0..planned.len() {
        if planned[i].target.is_none() {
            continue;
        }
        if cancelled || (hooks.cancelled)() {
            cancelled = true;
            progress.profiles[i].state = ProgressState::Cancelled;
            results[i].state = ProgressState::Cancelled;
            continue;
        }
        let Some(source) = planned[i].source else { continue };
        let (loader, _) = how_for(source, request.worlds);
        let plan = std::mem::take(&mut planned[i].plan);
        let id = random_hex(6);
        let staging = profiles_dir.join(format!("{id}.flint-importing"));
        progress.profiles[i].state = ProgressState::Copying;
        (hooks.progress)(&progress, true);

        let copied = std::fs::create_dir_all(&staging).map_err(|e| Stop::Failed(write_failure(&e))).and_then(|_| {
            let cancelled = hooks.cancelled;
            let progress_hook = &mut *hooks.progress;
            let entry_progress = &mut progress;
            copy_plan(&staging, &plan, limits, &mut |bytes, files, label, verifying| {
                let p = &mut entry_progress.profiles[i];
                p.bytes_done += bytes;
                p.files_done = files;
                let changed = p.current.as_deref() != Some(label);
                if changed {
                    p.current = Some(label.to_owned());
                }
                if verifying {
                    p.state = ProgressState::Verifying;
                }
                progress_hook(entry_progress, verifying);
                !cancelled()
            })
        });
        let outcome = copied.and_then(|skipped| {
            if (hooks.cancelled)() {
                return Err(Stop::Cancelled);
            }
            commit(state, &staging, &id, source, &planned[i].key, loader).map(|profile| (profile, skipped)).map_err(Stop::Failed)
        });
        let entry = &mut progress.profiles[i];
        entry.current = None;
        match outcome {
            Ok((profile, skipped)) => {
                entry.state = ProgressState::Done;
                entry.files_done = entry.files_total;
                entry.bytes_done = entry.bytes_total;
                entry.name = profile.name.clone();
                entry.profile_id = Some(profile.id.clone());
                results[i].state = ProgressState::Done;
                results[i].name = profile.name;
                results[i].profile_id = Some(profile.id);
                results[i].parts = scan::actual_parts(&plan, &skipped);
                results[i].skipped.extend(skipped);
            }
            Err(stop) => {
                let _ = std::fs::remove_dir_all(&staging);
                match stop {
                    Stop::Cancelled => {
                        cancelled = true;
                        entry.state = ProgressState::Cancelled;
                        results[i].state = ProgressState::Cancelled;
                    }
                    Stop::Failed(error) => {
                        entry.state = ProgressState::Failed;
                        entry.error = Some(error.clone());
                        results[i].state = ProgressState::Failed;
                        results[i].error = Some(error);
                    }
                }
            }
        }
        (hooks.progress)(&progress, true);
    }
    let result = ImportResult { profiles: results };
    if cancelled {
        Outcome::Cancelled(result)
    } else {
        Outcome::Finished(result)
    }
}

// ---------- free space ----------

/// Free bytes on the drive holding `path` (the nearest folder that exists), or None when the system won't say.
pub fn free_space(path: &Path) -> Option<u64> {
    let mut dir = path.to_path_buf();
    while !dir.exists() {
        dir = dir.parent()?.to_path_buf();
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;
        let wide: Vec<u16> = dir.as_os_str().encode_wide().chain(std::iter::once(0)).collect();
        let mut available: u64 = 0;
        // SAFETY: `wide` is a NUL-terminated path; the out pointer is a valid u64 and the others may be null.
        let ok = unsafe { GetDiskFreeSpaceExW(wide.as_ptr(), &mut available, std::ptr::null_mut(), std::ptr::null_mut()) };
        (ok != 0).then_some(available)
    }
    #[cfg(unix)]
    {
        use std::os::unix::ffi::OsStrExt;
        let text = std::ffi::CString::new(dir.as_os_str().as_bytes()).ok()?;
        // SAFETY: `text` is a NUL-terminated path and `stat` a zeroed statvfs the call fills in.
        let mut stat: libc::statvfs = unsafe { std::mem::zeroed() };
        let ok = unsafe { libc::statvfs(text.as_ptr(), &mut stat) };
        #[allow(clippy::unnecessary_cast)]
        (ok == 0).then(|| stat.f_bavail as u64 * stat.f_frsize as u64)
    }
    #[cfg(not(any(windows, unix)))]
    {
        None
    }
}

// ---------- commands ----------

/// The latest progress of recent imports, for a page that opens mid-import.
static PROGRESS: Mutex<Vec<ImportProgress>> = Mutex::new(Vec::new());
/// Profile-import jobs (their ids): one runs at a time.
static JOBS: Mutex<Vec<String>> = Mutex::new(Vec::new());

fn keep_progress(progress: &ImportProgress) {
    let mut list = PROGRESS.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(slot) = list.iter_mut().find(|p| p.job == progress.job) {
        *slot = progress.clone();
    } else {
        list.push(progress.clone());
        if list.len() > 4 {
            list.remove(0);
        }
    }
}

/// A chosen folder: an existing directory, not Flint's own data.
fn checked_folder(env: &Env, folder: Option<&str>) -> CmdResult<PathBuf> {
    let folder = folder.map(str::trim).filter(|f| !f.is_empty()).ok_or("Choose a folder first.")?;
    let path = PathBuf::from(folder);
    if !path.is_absolute() || !path.is_dir() {
        return Err("That folder doesn't exist anymore.".into());
    }
    if env.is_flint(&path) {
        return Err("That's Flint's own folder. Choose another launcher's folder or a Minecraft folder.".into());
    }
    Ok(path)
}

fn system_listing(state: &AppState, launcher: LauncherId, folder: Option<&str>) -> CmdResult<(Env, Listed)> {
    let env = Env::system(&state.paths.root);
    let folder = if launcher == LauncherId::Folder { Some(checked_folder(&env, folder)?) } else { None };
    let existing = load_profiles(state);
    let listed = list(&adapters::all(), launcher, folder.as_deref(), &env, &existing)?;
    Ok((env, listed))
}

/// Every launcher found on this computer. Reads only.
#[tauri::command]
pub async fn import_launchers(app: AppHandle) -> CmdResult<Vec<ImportLauncher>> {
    crate::data::blocking(&app, |state| {
        let env = Env::system(&state.paths.root);
        discover_all(&adapters::all(), &env, &load_profiles(state))
    })
    .await
}

/// One launcher's (or a chosen folder's) profiles, newest played first. Reads only.
#[tauri::command]
pub async fn import_profiles(app: AppHandle, launcher: LauncherId, folder: Option<String>) -> CmdResult<ImportListing> {
    crate::data::blocking(&app, move |state| system_listing(state, launcher, folder.as_deref()).map(|(_, listed)| listed.listing)).await?
}

/// The system's folder dialog. None when cancelled.
#[tauri::command]
pub async fn import_pick_folder(window: tauri::Window) -> CmdResult<Option<String>> {
    let picked = tokio::task::spawn_blocking(move || rfd::FileDialog::new().set_title("Choose a launcher folder or a Minecraft folder").set_parent(&window).pick_folder())
        .await
        .map_err(|e| e.to_string())?;
    Ok(picked.map(|p| p.display().to_string()))
}

/// What an import would bring, per profile, and whether it fits. Reads only.
#[tauri::command]
pub async fn import_preview(app: AppHandle, request: ImportRequest) -> CmdResult<ImportPreview> {
    crate::data::blocking(&app, move |state| {
        let (_, listed) = system_listing(state, request.launcher, request.folder.as_deref())?;
        let existing = load_profiles(state);
        let mut shown = preview(&listed, &existing, &request, &Limits::default(), free_space(&state.paths.root));
        let scan = crate::core::ProcessScan::take();
        warn_running(&mut shown, &listed, &|dir| game_running_in(dir, &scan));
        Ok(shown)
    })
    .await?
}

/// Starts an import job; the page follows it with "import-progress" and the job's events.
#[tauri::command]
pub async fn import_start(app: AppHandle, state: State<'_, AppState>, request: ImportRequest) -> CmdResult<String> {
    if request_keys(&request).is_empty() {
        return Err("Choose at least one profile to import.".into());
    }
    if request.launcher == LauncherId::Folder {
        checked_folder(&Env::system(&state.paths.root), request.folder.as_deref())?;
    }
    {
        let ours = JOBS.lock().unwrap_or_else(|e| e.into_inner());
        if state.jobs.list().iter().any(|j| ours.contains(&j.id) && (j.state == "queued" || j.state == "running")) {
            return Err("An import is already running. Wait for it to finish, or cancel it.".into());
        }
    }
    let count = request_keys(&request).len();
    let title = if count == 1 { "Importing a profile".to_owned() } else { format!("Importing {count} profiles") };
    let (id, _) = state.jobs.create(&app, "import", None, &title, count);
    {
        let mut ours = JOBS.lock().unwrap_or_else(|e| e.into_inner());
        ours.push(id.clone());
        if ours.len() > 16 {
            ours.remove(0);
        }
    }
    crate::jobs::spawn(&app, id.clone(), move |app, job| async move {
        let worker = app.clone();
        tokio::task::spawn_blocking(move || run_job(&worker, &job, request)).await.map_err(|e| format!("Background task failed: {e}"))?
    });
    Ok(id)
}

/// The import job itself, on a blocking thread.
fn run_job(app: &AppHandle, job: &str, request: ImportRequest) -> CmdResult<serde_json::Value> {
    let state = app.state::<AppState>();
    state.jobs.update(app, job, false, |j| {
        j.state = "running".into();
        j.stage = "Reading the profiles".into();
    });
    let (_, listed) = system_listing(&state, request.launcher, request.folder.as_deref())?;
    let mut last = Instant::now() - Duration::from_secs(1);
    let mut emit = |progress: &ImportProgress, important: bool| {
        if !important && last.elapsed() < Duration::from_millis(125) {
            return;
        }
        last = Instant::now();
        keep_progress(progress);
        let _ = app.emit("import-progress", progress);
        let total = progress.profiles.len();
        let finished = progress.profiles.iter().filter(|p| !matches!(p.state, ProgressState::Waiting | ProgressState::Copying | ProgressState::Verifying)).count();
        let active = progress.profiles.iter().position(|p| matches!(p.state, ProgressState::Copying | ProgressState::Verifying));
        state.jobs.update(app, job, !important, |j| {
            j.bytes_done = progress.profiles.iter().map(|p| p.bytes_done).sum();
            j.bytes_total = Some(progress.profiles.iter().filter(|p| p.state != ProgressState::Skipped).map(|p| p.bytes_total).sum());
            j.items_done = finished;
            j.items_total = total;
            j.file = active.and_then(|i| progress.profiles[i].current.clone());
            j.stage = match active {
                Some(i) if total > 1 => format!("Importing {} ({} of {total})", progress.profiles[i].name, i + 1),
                Some(i) => format!("Importing {}", progress.profiles[i].name),
                None => "Importing".into(),
            };
        });
    };
    let cancelled = || state.jobs.is_cancelled(job);
    let mut hooks = Hooks { cancelled: &cancelled, free_space: &free_space, progress: &mut emit };
    let outcome = run(&state, &listed, &request, job, &Limits::default(), &mut hooks);
    let result = match outcome {
        Outcome::Refused(message) => return Err(message),
        Outcome::Cancelled(result) => {
            state.jobs.update(app, job, false, |j| j.result = serde_json::to_value(&result).ok());
            return Err("Cancelled".into());
        }
        Outcome::Finished(result) => result,
    };
    let value = serde_json::to_value(&result).map_err(|e| e.to_string())?;
    let done = result.profiles.iter().filter(|p| p.state == ProgressState::Done).count();
    if done == 0 {
        state.jobs.update(app, job, false, |j| j.result = Some(value.clone()));
        let first = result.profiles.iter().find_map(|p| p.error.clone()).unwrap_or_else(|| "Nothing could be imported.".into());
        return Err(if result.profiles.len() == 1 { first } else { format!("None of the profiles could be imported. {first}") });
    }
    Ok(value)
}

/// The latest progress of an import job, or None.
#[tauri::command]
pub fn import_progress(job: String) -> Option<ImportProgress> {
    PROGRESS.lock().unwrap_or_else(|e| e.into_inner()).iter().find(|p| p.job == job).cloned()
}
