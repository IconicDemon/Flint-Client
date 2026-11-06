//! Settings → Storage: what Flint keeps on disk, and the cleanups that free space.
//!
//! The overview walks Flint's data folder once, off the interface's thread, and puts every byte in exactly one place:
//! Minecraft's game files and Java (shared by every profile), each profile's own folder, the earlier versions of updated
//! mods and packs kept for undo (rollback copies), Auto Sync's store and backups, the cache, unfinished downloads and saves
//! (temporary files), and the rest (Flint's settings, the window's web data, profile pictures). Links are never followed,
//! and a file with several hard links (macOS, Linux) is counted once; Flint makes no hard links. Flint doesn't share mods
//! between profiles: two profiles with the same mod each have their own copy, and both are counted.
//!
//! Each cleanup is planned first (exactly which files, how much), shown to the player, and carried out only as planned
//! (`storage_clean` refuses when the plan changed since it was shown). Nothing is removed while a game runs, while Flint
//! downloads or installs anything, or while Java's start-up cache is being built; worlds, mods, packs, configs, profiles and
//! accounts are never in a plan.

use crate::core::*;
use crate::data::{blocking, load_settings, Profile, ProfilesFile};
use serde::Serialize;
use serde_json::{json, Value};
use sha1::{Digest, Sha1};
use std::collections::{BTreeMap, HashSet};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime};
use tauri::{AppHandle, Manager};

/// The only things Clear Cache removes, with what the player sees them as. All of them are fetched or rebuilt on demand
/// (Java's start-up cache after the next game of each version); worlds, mods, settings, accounts and installed game files
/// are never in this list.
const CACHE_ENTRIES: [(&str, &str); 4] = [
    ("java-start", "Java's start-up cache"),
    ("skins", "Skins and capes"),
    ("catalog.json", "Minecraft version list"),
    ("news.json", "Flint news"),
];

/// The kinds of content whose earlier versions Flint keeps in `<profile>/.flint-backup/<kind>/` (content.rs `backup_dir`).
const ROLLBACK_KINDS: [&str; 4] = ["mod", "resourcepack", "shader", "datapack"];

/// A temporary file or folder modified this recently may belong to something running right now, so it is left alone.
const IN_USE_WINDOW: Duration = Duration::from_secs(10 * 60);

/// How long an overview is reused (Settings opened twice, Storage then Troubleshooting).
const OVERVIEW_TTL: Duration = Duration::from_secs(20);

/// Set while a cleanup runs; installs and launches wait for it (`game::begin_job`).
static CLEANING: AtomicBool = AtomicBool::new(false);

pub fn cleaning() -> bool {
    CLEANING.load(Ordering::SeqCst)
}

// ---------- download settings ----------

/// Game files an install downloads at a time unless changed in Settings → Storage. A version is over 5,000 files, most
/// a few kilobytes, so an install waits on Mojang's answers more than on the connection: a fresh 26.3 (741 MB) took 26.4 s
/// with 16 at once and 25.5 s with 32 on a 300 Mbit/s line (2026-11-04, largest files first); 16 until Flint 1.0.
pub const DOWNLOADS_DEFAULT: u64 = 32;
pub const DOWNLOADS_MAX: u64 = 32;
/// Files from Modrinth (a modpack's) are never fetched more than this many at a time, whatever the setting.
pub const MODRINTH_MAX: usize = 8;
/// Installs (mods, packs, profile imports and exports) that run at the same time; the rest wait their turn.
pub const INSTALLS_DEFAULT: u64 = 2;
pub const INSTALLS_MAX: u64 = 4;

fn setting_in(settings: &Value, key: &str, default: u64, max: u64) -> u64 {
    match &settings[key] {
        Value::Number(n) => n.as_f64().filter(|v| v.is_finite()).map_or(default, |v| (v.round().max(1.0) as u64).min(max)),
        _ => default,
    }
}

/// Settings → Storage → "Files downloaded at once" (1 to 32): read at the start of each install, so it applies to the next
/// download. Every file is still checked against its published hash.
pub fn download_limit(settings: &Value) -> usize {
    setting_in(settings, "maxDownloads", DOWNLOADS_DEFAULT, DOWNLOADS_MAX) as usize
}

/// The same for Modrinth's files, which never go above [MODRINTH_MAX] at a time.
pub fn modrinth_download_limit(settings: &Value) -> usize {
    download_limit(settings).min(MODRINTH_MAX)
}

/// Settings → Storage → "Installs at once" (1 to 4), read when Flint starts.
pub fn install_slots(settings: &Value) -> usize {
    setting_in(settings, "maxInstalls", INSTALLS_DEFAULT, INSTALLS_MAX) as usize
}

// ---------- measuring ----------

#[derive(Clone, Copy, Default, Debug, PartialEq, Serialize)]
pub struct Tally {
    pub bytes: u64,
    pub files: u64,
}

impl Tally {
    fn add(&mut self, other: Tally) {
        self.bytes += other.bytes;
        self.files += other.files;
    }
}

/// A file's identity when it has other hard links, so it is counted once. Windows: Flint makes no hard links and the
/// standard library can't read a file's link count there, so every name counts.
#[cfg(unix)]
fn shared_identity(meta: &std::fs::Metadata) -> Option<(u64, u64)> {
    use std::os::unix::fs::MetadataExt;
    (meta.nlink() > 1).then(|| (meta.dev(), meta.ino()))
}

#[cfg(not(unix))]
fn shared_identity(_: &std::fs::Metadata) -> Option<(u64, u64)> {
    None
}

/// Why something is temporary.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
enum Leftover {
    /// `.<name>.flint-part`: a download that never finished.
    Download,
    /// `.flint-staging-<job>` in a profile, `<id>.flint-importing` in `profiles/`: an install or import that never finished.
    Install,
    /// `.<name>.flint-tmp`, `<name>.json.tmp`: a save that never finished.
    Save,
    /// `<key>.aot.part`, `client/<jar>.part`: a Java start-up cache that never finished building.
    JavaCache,
}

impl Leftover {
    fn label(self) -> &'static str {
        match self {
            Leftover::Download => "Unfinished downloads",
            Leftover::Install => "Unfinished installs and imports",
            Leftover::Save => "Unfinished saves",
            Leftover::JavaCache => "Unfinished Java start-up caches",
        }
    }

    /// What a name is, if it is something Flint left unfinished. Names only Flint uses count anywhere; `<name>.json.tmp`
    /// (`core::write_json`) only where Flint keeps its own files, or named as Flint's; `.part` only in the cache.
    fn of(name: &str, is_dir: bool, zone: Zone) -> Option<Leftover> {
        let flint_json = zone != Zone::Others || name.starts_with("flint-") || name.starts_with(".flint-");
        // Flint's partial downloads and unfinished writes are always hidden (`.<name>.flint-part`, `.<name>.flint-tmp`):
        // a visible name ending that way is a player's, never Flint's leftover.
        let hidden = name.starts_with('.');
        if hidden && name.ends_with(crate::jobs::PART_SUFFIX) {
            Some(Leftover::Download)
        } else if (hidden && name.ends_with(TMP_SUFFIX)) || (!is_dir && flint_json && name.ends_with(".json.tmp")) {
            Some(Leftover::Save)
        } else if is_dir && (name.starts_with(".flint-staging-") || crate::jobs::is_staging_name(name)) {
            Some(Leftover::Install)
        } else if zone == Zone::Cache && !is_dir && (name.ends_with(".aot.part") || name.ends_with(".jar.part")) {
            Some(Leftover::JavaCache)
        } else {
            None
        }
    }
}

/// Whose files a folder holds, which decides what in it may be Flint's unfinished work.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Zone {
    /// Flint's own: its settings, Minecraft's game files and Java as Flint installs them, Auto Sync's store.
    Flint,
    /// The cache, where Java's start-up cache builds also write.
    Cache,
    /// The game's, the mods' and the window's own files, in which only names nothing but Flint uses count.
    Others,
}

/// One file or folder a cleanup removes, with its size when planned.
#[derive(Clone, Debug)]
struct Target {
    path: PathBuf,
    size: Tally,
}

#[derive(Clone, Debug)]
struct Temp {
    kind: Leftover,
    target: Target,
    /// The newest modification time in it.
    newest: SystemTime,
}

/// One profile folder's share.
#[derive(Clone, Debug, Default)]
struct FolderUse {
    folder: String,
    own: Tally,
    rollback: Vec<Target>,
    sync_backup: Tally,
    sync_backups: u64,
}

impl FolderUse {
    fn rollback_size(&self) -> Tally {
        let mut total = Tally::default();
        for target in &self.rollback {
            total.add(target.size);
        }
        total
    }
}

/// Everything in the data folder, each byte in one place.
#[derive(Default)]
struct Walk {
    seen: HashSet<(u64, u64)>,
    /// Bytes not counted again because they are another hard link to a file already counted.
    linked: u64,
    game: Tally,
    runtimes: Tally,
    cache: Tally,
    sync: Tally,
    other: Tally,
    temps: Vec<Temp>,
    folders: Vec<FolderUse>,
}

impl Walk {
    /// A file's size, or 0 when it is another link to a file already counted.
    fn file(&mut self, meta: &std::fs::Metadata) -> Tally {
        if let Some(identity) = shared_identity(meta) {
            if !self.seen.insert(identity) {
                self.linked += meta.len();
                return Tally::default();
            }
        }
        Tally { bytes: meta.len(), files: 1 }
    }

    /// A whole file or folder, links not followed, with the newest modification time of a file in it (an empty folder's
    /// own).
    fn measure(&mut self, path: &Path) -> (Tally, SystemTime) {
        let Ok(meta) = std::fs::symlink_metadata(path) else { return (Tally::default(), SystemTime::UNIX_EPOCH) };
        let own = meta.modified().unwrap_or(SystemTime::UNIX_EPOCH);
        if meta.file_type().is_symlink() {
            return (Tally::default(), own);
        }
        if !meta.is_dir() {
            return (self.file(&meta), own);
        }
        let mut total = Tally::default();
        let mut newest = None;
        for entry in std::fs::read_dir(path).into_iter().flatten().flatten() {
            let (size, time) = self.measure(&entry.path());
            total.add(size);
            newest = newest.max(Some(time));
        }
        (total, newest.unwrap_or(own))
    }

    /// Adds a folder's files to `into`, except temporary entries, which are listed on their own.
    fn tree(&mut self, dir: &Path, into: &mut Tally, zone: Zone) {
        for entry in std::fs::read_dir(dir).into_iter().flatten().flatten() {
            let Ok(meta) = entry.metadata() else { continue };
            if meta.file_type().is_symlink() {
                continue;
            }
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().into_owned();
            if let Some(kind) = Leftover::of(&name, meta.is_dir(), zone) {
                self.temp(kind, path);
            } else if meta.is_dir() {
                self.tree(&path, into, zone);
            } else {
                into.add(self.file(&meta));
            }
        }
    }

    fn temp(&mut self, kind: Leftover, path: PathBuf) {
        let (size, newest) = self.measure(&path);
        self.temps.push(Temp { kind, target: Target { path, size }, newest });
    }

    fn of(root: &Path) -> Walk {
        let mut walk = Walk::default();
        for entry in std::fs::read_dir(root).into_iter().flatten().flatten() {
            let Ok(meta) = entry.metadata() else { continue };
            if meta.file_type().is_symlink() {
                continue;
            }
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().into_owned();
            let mut into = Tally::default();
            match name.as_str() {
                "minecraft" if meta.is_dir() => {
                    walk.tree(&path, &mut into, Zone::Flint);
                    walk.game.add(into);
                }
                "runtimes" if meta.is_dir() => {
                    walk.tree(&path, &mut into, Zone::Flint);
                    walk.runtimes.add(into);
                }
                "cache" if meta.is_dir() => {
                    walk.tree(&path, &mut into, Zone::Cache);
                    walk.cache.add(into);
                }
                // Auto Sync's store (gamesettings/autosync/store.rs `STORE_DIR`).
                "sync" if meta.is_dir() => {
                    walk.tree(&path, &mut into, Zone::Flint);
                    walk.sync.add(into);
                }
                "profiles" if meta.is_dir() => walk.profiles(&path),
                _ => match Leftover::of(&name, meta.is_dir(), if meta.is_dir() { Zone::Others } else { Zone::Flint }) {
                    Some(kind) => walk.temp(kind, path),
                    None if meta.is_dir() => {
                        walk.tree(&path, &mut into, Zone::Others);
                        walk.other.add(into);
                    }
                    None => {
                        let size = walk.file(&meta);
                        walk.other.add(size);
                    }
                },
            }
        }
        walk
    }

    fn profiles(&mut self, dir: &Path) {
        for entry in std::fs::read_dir(dir).into_iter().flatten().flatten() {
            let Ok(meta) = entry.metadata() else { continue };
            if meta.file_type().is_symlink() {
                continue;
            }
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().into_owned();
            if let Some(kind) = Leftover::of(&name, meta.is_dir(), Zone::Others) {
                self.temp(kind, path);
            } else if meta.is_dir() {
                let folder = self.profile(&path, name);
                self.folders.push(folder);
            } else {
                let size = self.file(&meta);
                self.other.add(size);
            }
        }
    }

    fn profile(&mut self, dir: &Path, folder: String) -> FolderUse {
        let mut use_ = FolderUse { folder, ..Default::default() };
        for entry in std::fs::read_dir(dir).into_iter().flatten().flatten() {
            let Ok(meta) = entry.metadata() else { continue };
            if meta.file_type().is_symlink() {
                continue;
            }
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().into_owned();
            if name == ".flint-backup" && meta.is_dir() {
                self.backups(&path, &mut use_);
            } else if let Some(kind) = Leftover::of(&name, meta.is_dir(), Zone::Others) {
                self.temp(kind, path);
            } else if meta.is_dir() {
                self.tree(&path, &mut use_.own, Zone::Others);
            } else {
                let size = self.file(&meta);
                use_.own.add(size);
            }
        }
        use_
    }

    /// `.flint-backup/`: Auto Sync's backups (`sync/`), the earlier versions of updated content (one folder per kind), and
    /// anything else, which stays the profile's own.
    fn backups(&mut self, dir: &Path, use_: &mut FolderUse) {
        for entry in std::fs::read_dir(dir).into_iter().flatten().flatten() {
            let Ok(meta) = entry.metadata() else { continue };
            if meta.file_type().is_symlink() {
                continue;
            }
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().into_owned();
            if name == "sync" && meta.is_dir() {
                let mut size = Tally::default();
                self.tree(&path, &mut size, Zone::Others);
                use_.sync_backup.add(size);
                use_.sync_backups += std::fs::read_dir(&path).map(|e| e.flatten().filter(|b| !b.file_name().to_string_lossy().starts_with('.')).count() as u64).unwrap_or(0);
            } else if ROLLBACK_KINDS.contains(&name.as_str()) && meta.is_dir() {
                for file in std::fs::read_dir(&path).into_iter().flatten().flatten() {
                    let Ok(file_meta) = file.metadata() else { continue };
                    if file_meta.file_type().is_symlink() {
                        continue;
                    }
                    let (size, _) = self.measure(&file.path());
                    use_.rollback.push(Target { path: file.path(), size });
                }
            } else if meta.is_dir() {
                self.tree(&path, &mut use_.own, Zone::Others);
            } else {
                let size = self.file(&meta);
                use_.own.add(size);
            }
        }
    }
}

// ---------- cleanups ----------

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Kind {
    Cache,
    Temporary,
    UnusedGame,
    Rollback,
}

impl Kind {
    pub fn parse(text: &str) -> Option<Kind> {
        match text {
            "cache" => Some(Kind::Cache),
            "temporary" => Some(Kind::Temporary),
            "unusedGame" => Some(Kind::UnusedGame),
            "rollback" => Some(Kind::Rollback),
            _ => None,
        }
    }

    fn id(self) -> &'static str {
        match self {
            Kind::Cache => "cache",
            Kind::Temporary => "temporary",
            Kind::UnusedGame => "unusedGame",
            Kind::Rollback => "rollback",
        }
    }
}

/// A labelled part of a cleanup ("Minecraft 1.21.1", "Unfinished downloads").
#[derive(Clone, Debug)]
struct Group {
    label: String,
    targets: Vec<Target>,
}

impl Group {
    fn size(&self) -> Tally {
        let mut total = Tally::default();
        for target in &self.targets {
            total.add(target.size);
        }
        total
    }
}

/// Exactly what a cleanup would remove.
#[derive(Clone, Debug)]
pub struct Plan {
    kind: Kind,
    groups: Vec<Group>,
    /// Something the player should know about what is kept (things in use, parts Flint can't be sure about).
    note: Option<String>,
}

impl Plan {
    fn size(&self) -> Tally {
        let mut total = Tally::default();
        for group in &self.groups {
            total.add(group.size());
        }
        total
    }

    /// Names this exact plan: the same files to remove give the same id.
    fn id(&self) -> String {
        let mut paths: Vec<String> = self.groups.iter().flat_map(|g| g.targets.iter().map(|t| t.path.display().to_string())).collect();
        paths.sort();
        let mut hash = Sha1::new();
        hash.update(self.kind.id());
        for path in paths {
            hash.update([0]);
            hash.update(path.as_bytes());
        }
        hex::encode(&hash.finalize()[..8])
    }

    fn view(&self) -> Value {
        let size = self.size();
        json!({
            "kind": self.kind.id(),
            "id": self.id(),
            "bytes": size.bytes,
            "files": size.files,
            "groups": self.groups.iter().map(|g| {
                let size = g.size();
                json!({ "label": g.label, "bytes": size.bytes, "files": size.files })
            }).collect::<Vec<_>>(),
            "note": self.note,
        })
    }
}

fn cache_plan(root: &Path) -> Plan {
    let cache = root.join("cache");
    let mut groups = Vec::new();
    let mut walk = Walk::default();
    for (name, label) in CACHE_ENTRIES {
        let path = cache.join(name);
        if std::fs::symlink_metadata(&path).is_ok_and(|m| !m.file_type().is_symlink()) {
            let (size, _) = walk.measure(&path);
            groups.push(Group { label: label.into(), targets: vec![Target { path, size }] });
        }
    }
    Plan { kind: Kind::Cache, groups, note: None }
}

fn temporary_plan(temps: &[Temp], now: SystemTime) -> Plan {
    let mut by_kind: BTreeMap<Leftover, Vec<Target>> = BTreeMap::new();
    let mut recent = 0;
    for temp in temps {
        if now.duration_since(temp.newest).unwrap_or_default() < IN_USE_WINDOW {
            recent += 1;
        } else {
            by_kind.entry(temp.kind).or_default().push(temp.target.clone());
        }
    }
    let groups = by_kind.into_iter().map(|(kind, targets)| Group { label: kind.label().into(), targets }).collect();
    let note = (recent > 0).then(|| {
        format!(
            "{recent} {} changed in the last few minutes, so {} kept in case {} still in use.",
            if recent == 1 { "file" } else { "files" },
            if recent == 1 { "it is" } else { "they are" },
            if recent == 1 { "it is" } else { "they are" }
        )
    });
    Plan { kind: Kind::Temporary, groups, note }
}

fn rollback_plan(folders: &[FolderUse], names: &dyn Fn(&str) -> String) -> Plan {
    let mut groups = Vec::new();
    for folder in folders.iter().filter(|f| !f.rollback.is_empty()) {
        let count = folder.rollback.len();
        groups.push(Group {
            label: format!("{}: {count} earlier {}", names(&folder.folder), if count == 1 { "version" } else { "versions" }),
            targets: folder.rollback.clone(),
        });
    }
    Plan { kind: Kind::Rollback, groups, note: None }
}

/// A Fabric version folder's (loader, game version): `fabric-loader-<loader>-<game>` and Ornithe's
/// `fabric-loader-<loader>-<game>-ornithe-gen2` (game.rs `LoaderSource::profile_dir`).
fn fabric_folder(name: &str) -> Option<(&str, &str)> {
    let rest = name.strip_prefix("fabric-loader-")?;
    let rest = rest.strip_suffix("-ornithe-gen2").unwrap_or(rest);
    // Loader versions have no hyphen; game versions can (26.4-pre1, 1.21.11-rc1).
    let (loader, game) = rest.split_once('-')?;
    (!loader.is_empty() && !game.is_empty()).then_some((loader, game))
}

/// Whether the custom Java in Settings is inside `dir`, however its path was typed (other slashes, `..`, or on
/// Windows other letter case).
fn java_inside(java: &str, dir: &Path) -> bool {
    let java = Path::new(java.trim());
    let (java, dir) = match (std::fs::canonicalize(java), std::fs::canonicalize(dir)) {
        (Ok(java), Ok(dir)) => (java, dir),
        _ => (java.to_path_buf(), dir.to_path_buf()),
    };
    if cfg!(windows) {
        let fold = |p: &Path| PathBuf::from(p.to_string_lossy().replace('/', "\\").to_lowercase());
        fold(&java).starts_with(fold(&dir))
    } else {
        java.starts_with(&dir)
    }
}

/// A relative path from version metadata, if it stays inside its folder.
fn plain(relative: &str) -> Option<PathBuf> {
    let path = Path::new(relative);
    (!relative.is_empty() && path.components().all(|c| matches!(c, Component::Normal(_)))).then(|| path.to_path_buf())
}

/// The library files a version's metadata names (Mojang's `downloads`, a loader profile's maven names), relative to
/// `libraries/`.
fn libraries_of(version: &Value) -> Vec<PathBuf> {
    let mut out = Vec::new();
    for lib in version["libraries"].as_array().into_iter().flatten() {
        let downloads = &lib["downloads"];
        if downloads.is_object() {
            out.extend(downloads["artifact"]["path"].as_str().and_then(plain));
            for classifier in downloads["classifiers"].as_object().into_iter().flat_map(|c| c.values()) {
                out.extend(classifier["path"].as_str().and_then(plain));
            }
        } else if let Some(path) = lib["name"].as_str().and_then(crate::game::maven_path) {
            out.extend(plain(&path));
        }
    }
    out
}

fn asset_objects(index: &Value) -> HashSet<String> {
    index["objects"]
        .as_object()
        .into_iter()
        .flat_map(|o| o.values())
        .filter_map(|o| o["hash"].as_str())
        .filter(|h| h.len() == 40 && h.bytes().all(|b| b.is_ascii_hexdigit()))
        .map(str::to_ascii_lowercase)
        .collect()
}

/// What players know Mojang's Java runtimes as.
fn java_label(component: &str) -> String {
    let major = match component {
        "jre-legacy" => Some(8),
        "java-runtime-alpha" => Some(16),
        "java-runtime-beta" | "java-runtime-gamma" | "java-runtime-gamma-snapshot" => Some(17),
        "java-runtime-delta" => Some(21),
        "java-runtime-epsilon" => Some(25),
        _ => None,
    };
    match major {
        Some(major) => format!("Java {major} ({component})"),
        None => format!("Java ({component})"),
    }
}

/// Minecraft versions, libraries, sounds and textures, and Java runtimes that no profile uses. Very conservative: a
/// version is unused only when no profile is on it (any loader version, for a Fabric profile that hasn't picked one); a
/// library or asset only when an unused version names it and no used version does; a Java only when no profile's version
/// needs it, with or without Flint's in-game menu, and it isn't the custom Java in Settings. When Flint can't read what a
/// used version needs (a profile that was never installed), libraries, assets and Java are all kept.
fn unused_game_plan(root: &Path, profiles: &[Profile], custom_java: Option<&str>) -> Plan {
    let game = root.join("minecraft");
    let versions = game.join("versions");
    let read = |path: &Path| read_json_opt::<Value>(path).filter(Value::is_object);
    let mut walk = Walk::default();
    let mut groups = Vec::new();
    let mut used_meta: Vec<Value> = Vec::new();
    let mut unused_meta: Vec<Value> = Vec::new();
    // Each profile's version must be readable for anything shared to be judged.
    let profile_meta: Vec<Option<Value>> = profiles.iter().map(|p| read(&versions.join(&p.game_version).join(format!("{}.json", p.game_version)))).collect();
    let mut known = profile_meta.iter().all(Option::is_some);
    let mut folders: Vec<_> = std::fs::read_dir(&versions).into_iter().flatten().flatten().collect();
    folders.sort_by_key(|e| e.file_name());
    for entry in folders {
        if !entry.metadata().is_ok_and(|m| m.is_dir() && !m.file_type().is_symlink()) {
            continue;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        let path = entry.path();
        let (used, label, meta_file) = if let Some((loader, version)) = fabric_folder(&name) {
            let used = profiles
                .iter()
                .any(|p| p.loader == "fabric" && p.game_version == version && p.loader_version.as_deref().is_none_or(|l| l == loader));
            (used, format!("Fabric Loader {loader} for Minecraft {version}"), path.join("profile.json"))
        } else if path.join(format!("{name}.json")).is_file() || path.join(format!("{name}.jar")).is_file() {
            (profiles.iter().any(|p| p.game_version == name), format!("Minecraft {name}"), path.join(format!("{name}.json")))
        } else {
            // A folder Flint didn't make: kept.
            continue;
        };
        if used {
            match read(&meta_file) {
                Some(meta) => used_meta.push(meta),
                None => known = false,
            }
        } else {
            unused_meta.extend(read(&meta_file));
            let (size, _) = walk.measure(&path);
            groups.push(Group { label, targets: vec![Target { path, size }] });
        }
    }
    let mut note = None;
    if known && !unused_meta.is_empty() {
        let used_libraries: HashSet<PathBuf> = used_meta.iter().flat_map(libraries_of).collect();
        let mut seen = HashSet::new();
        let mut targets = Vec::new();
        for relative in unused_meta.iter().flat_map(libraries_of) {
            if used_libraries.contains(&relative) || !seen.insert(relative.clone()) {
                continue;
            }
            let path = game.join("libraries").join(&relative);
            if let Ok(meta) = std::fs::symlink_metadata(&path) {
                if meta.is_file() {
                    targets.push(Target { path, size: Tally { bytes: meta.len(), files: 1 } });
                }
            }
        }
        if !targets.is_empty() {
            groups.push(Group { label: "Libraries only those versions use".into(), targets });
        }
        // Sounds and textures: the objects only unused versions' asset indexes list.
        let index_of = |meta: &Value| meta["assetIndex"]["id"].as_str().filter(|id| plain(id).is_some()).map(str::to_owned);
        let used_ids: HashSet<String> = used_meta.iter().filter_map(index_of).collect();
        let unused_ids: HashSet<String> = unused_meta.iter().filter_map(index_of).filter(|id| !used_ids.contains(id)).collect();
        let indexes = game.join("assets/indexes");
        let mut used_objects = HashSet::new();
        let mut assets_known = true;
        for id in &used_ids {
            match read(&indexes.join(format!("{id}.json"))) {
                Some(index) => used_objects.extend(asset_objects(&index)),
                None => assets_known = false,
            }
        }
        if assets_known && !unused_ids.is_empty() {
            let mut targets = Vec::new();
            let mut ids: Vec<&String> = unused_ids.iter().collect();
            ids.sort();
            for id in ids {
                let index_path = indexes.join(format!("{id}.json"));
                let Some(index) = read(&index_path) else { continue };
                for hash in asset_objects(&index) {
                    // Listed by a used version, or already taken from another unused one.
                    if !used_objects.insert(hash.clone()) {
                        continue;
                    }
                    let path = game.join("assets/objects").join(&hash[..2]).join(&hash);
                    if let Ok(meta) = std::fs::symlink_metadata(&path) {
                        if meta.is_file() {
                            targets.push(Target { path, size: Tally { bytes: meta.len(), files: 1 } });
                        }
                    }
                }
                let (size, _) = walk.measure(&index_path);
                targets.push(Target { path: index_path, size });
            }
            if !targets.is_empty() {
                groups.push(Group { label: "Sounds and textures only those versions use".into(), targets });
            }
        }
    }
    if known {
        let mut needed: HashSet<String> = HashSet::new();
        for (profile, meta) in profiles.iter().zip(profile_meta.iter().flatten()) {
            needed.insert(meta["javaVersion"]["component"].as_str().unwrap_or("jre-legacy").to_owned());
            if let Some(choice) = crate::runtime::java_override(true, profile) {
                needed.insert(choice.component.to_owned());
                needed.insert(choice.fallback.to_owned());
            }
        }
        let runtimes = root.join("runtimes");
        let mut entries: Vec<_> = std::fs::read_dir(&runtimes).into_iter().flatten().flatten().collect();
        entries.sort_by_key(|e| e.file_name());
        for entry in entries {
            let name = entry.file_name().to_string_lossy().into_owned();
            let path = entry.path();
            let custom = custom_java.is_some_and(|java| java_inside(java, &path));
            if needed.contains(&name) || custom || !entry.metadata().is_ok_and(|m| m.is_dir() && !m.file_type().is_symlink()) {
                continue;
            }
            let (size, _) = walk.measure(&path);
            groups.push(Group { label: java_label(&name), targets: vec![Target { path, size }] });
        }
    } else {
        note = Some("Shared libraries, sounds and Java are kept until every profile has been installed, so Flint knows what each one needs.".into());
    }
    Plan { kind: Kind::UnusedGame, groups, note }
}

/// The profile list; none when profiles.json is there but can't be read (an empty list when there is none yet).
fn readable_profiles(path: &Path) -> Option<Vec<Profile>> {
    match read_json_opt::<ProfilesFile>(path) {
        Some(file) => Some(file.profiles),
        None if std::fs::symlink_metadata(path).is_err_and(|e| e.kind() == std::io::ErrorKind::NotFound) => Some(Vec::new()),
        None => None,
    }
}

/// The data folder measured, with every cleanup planned.
struct Survey {
    root: PathBuf,
    walk: Walk,
    profiles: Vec<Profile>,
    plans: Vec<Plan>,
}

impl Survey {
    fn take(root: &Path, profiles: Vec<Profile>, custom_java: Option<&str>, now: SystemTime) -> Survey {
        let walk = Walk::of(root);
        let names = |folder: &str| profiles.iter().find(|p| crate::profilefolder::current(p) == folder).map_or_else(|| folder.to_owned(), |p| p.name.clone());
        let plans = vec![
            cache_plan(root),
            temporary_plan(&walk.temps, now),
            unused_game_plan(root, &profiles, custom_java),
            rollback_plan(&walk.folders, &names),
        ];
        Survey { root: root.to_owned(), walk, profiles, plans }
    }

    fn of(state: &AppState, now: SystemTime) -> Survey {
        let settings = load_settings(state);
        let profiles = readable_profiles(&state.paths.profiles());
        let unreadable = profiles.is_none();
        let mut survey = Survey::take(&state.paths.root, profiles.unwrap_or_default(), settings["javaPath"].as_str(), now);
        if unreadable {
            // Judged against an empty list, every installed version would look unused.
            let note = "Flint couldn't read your profile list, so it can't tell which game files are unused. They are all kept.";
            for plan in survey.plans.iter_mut().filter(|p| p.kind == Kind::UnusedGame) {
                *plan = Plan { kind: Kind::UnusedGame, groups: Vec::new(), note: Some(note.into()) };
            }
        }
        survey
    }

    fn plan(&self, kind: Kind) -> &Plan {
        self.plans.iter().find(|p| p.kind == kind).expect("every kind is planned")
    }

    fn view(&self) -> Value {
        let walk = &self.walk;
        let mut profiles = Tally::default();
        let mut rollback = Tally::default();
        let mut sync = walk.sync;
        let mut list = Vec::new();
        for folder in &walk.folders {
            profiles.add(folder.own);
            rollback.add(folder.rollback_size());
            sync.add(folder.sync_backup);
            let profile = self.profiles.iter().find(|p| crate::profilefolder::current(p) == folder.folder);
            list.push(json!({
                "id": profile.map(|p| p.id.clone()),
                "name": profile.map_or_else(|| folder.folder.clone(), |p| p.name.clone()),
                "folder": folder.folder,
                "gameVersion": profile.map(|p| p.game_version.clone()),
                "loader": profile.map(|p| p.loader.clone()),
                "bytes": folder.own.bytes,
                "rollbackBytes": folder.rollback_size().bytes,
                "rollbackFiles": folder.rollback.len(),
                "syncBackupBytes": folder.sync_backup.bytes,
                "syncBackups": folder.sync_backups,
            }));
        }
        list.sort_by(|a, b| b["bytes"].as_u64().cmp(&a["bytes"].as_u64()));
        let mut temporary = Tally::default();
        for temp in &walk.temps {
            temporary.add(temp.target.size);
        }
        let categories = [
            ("game", walk.game),
            ("profiles", profiles),
            ("runtimes", walk.runtimes),
            ("cache", walk.cache),
            ("rollback", rollback),
            ("sync", sync),
            ("temporary", temporary),
            ("other", walk.other),
        ];
        let total: u64 = categories.iter().map(|(_, t)| t.bytes).sum();
        json!({
            "root": self.root.display().to_string(),
            "total": total,
            "categories": categories.iter().map(|(id, t)| json!({ "id": id, "bytes": t.bytes, "files": t.files })).collect::<Vec<_>>(),
            "profiles": list,
            "linkedBytes": walk.linked,
            "reclaimable": {
                "cache": self.plan(Kind::Cache).size().bytes,
                "temporary": self.plan(Kind::Temporary).size().bytes,
                "unusedGame": self.plan(Kind::UnusedGame).size().bytes,
                "rollback": self.plan(Kind::Rollback).size().bytes,
            },
            "scannedAt": now_secs(),
        })
    }
}

/// Removes a planned file or folder (never a link, never anything outside the data folder).
fn remove(root: &Path, path: &Path) -> std::io::Result<bool> {
    if !path.starts_with(root) || path == root {
        return Err(std::io::Error::other("outside Flint's folder"));
    }
    let meta = match std::fs::symlink_metadata(path) {
        Ok(meta) => meta,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(false),
        Err(e) => return Err(e),
    };
    if meta.file_type().is_symlink() {
        return Err(std::io::Error::other("it is a link, which Flint leaves alone"));
    }
    if meta.is_dir() {
        std::fs::remove_dir_all(path)?;
    } else {
        std::fs::remove_file(path)?;
    }
    Ok(true)
}

/// Removes empty folders from `dir`'s parent upwards, stopping at `stop`.
fn prune_empty(mut dir: Option<&Path>, stop: &Path) {
    while let Some(current) = dir {
        if current == stop || !current.starts_with(stop) || std::fs::remove_dir(current).is_err() {
            break;
        }
        dir = current.parent();
    }
}

/// Where emptied folders stop being removed after `path` went: a rollback copy's `.flint-backup`, a library's
/// `libraries`, a sound's `assets/objects`. Whole folders (versions, Java, the cache) leave nothing to tidy.
fn prune_stop(root: &Path, kind: Kind, path: &Path) -> Option<PathBuf> {
    match kind {
        Kind::Rollback => path.parent()?.parent().map(Path::to_path_buf),
        Kind::UnusedGame => [root.join("minecraft/libraries"), root.join("minecraft/assets/objects")].into_iter().find(|stop| path.starts_with(stop)),
        Kind::Cache | Kind::Temporary => None,
    }
}

/// Carries out a plan: everything in it, continuing past failures, reporting what was freed and what couldn't be removed.
fn execute(root: &Path, plan: &Plan) -> Value {
    let mut removed = Tally::default();
    let mut failed = Vec::new();
    for group in &plan.groups {
        for target in &group.targets {
            match remove(root, &target.path) {
                Ok(true) => {
                    removed.add(target.size);
                    // Libraries, sounds and rollback copies leave their folders behind; empty ones go too.
                    if let Some(stop) = prune_stop(root, plan.kind, &target.path) {
                        prune_empty(target.path.parent(), &stop);
                    }
                }
                Ok(false) => {}
                Err(error) => {
                    let name = target.path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                    failed.push(json!({ "name": format!("{} ({name})", group.label), "error": error.to_string() }));
                }
            }
        }
    }
    json!({ "removedBytes": removed.bytes, "removedFiles": removed.files, "failed": failed })
}

// ---------- what is running ----------

/// Why nothing may be removed now, if so: a game runs (this Flint's, one that outlived a restart, or Java building its
/// start-up cache), or Flint is downloading or installing. Anything Flint can't rule out counts as running.
pub fn busy_reason(state: &AppState) -> Option<String> {
    if !state.running.lock().unwrap().is_empty() {
        return Some("Minecraft is running. Close it first: Flint never removes files a running game may use.".into());
    }
    if !state.install_cancel.lock().unwrap().is_empty() || state.jobs.any_active() {
        return Some("Flint is downloading or installing something. Try again when it's done.".into());
    }
    outside_game(&state.paths.root)
}

/// A Java started from Flint's folder that this Flint didn't start (a game from before Flint restarted, another
/// launcher's, or Java building its start-up cache after a game), found among the running processes.
fn outside_game(root: &Path) -> Option<String> {
    let found = {
        #[cfg(windows)]
        {
            crate::datadir::game_using(root)
        }
        #[cfg(not(windows))]
        {
            let folder = root.display().to_string();
            match ProcessScan::take().lines() {
                Ok(lines) => lines.iter().find(|l| l.contains(&folder) && l.to_ascii_lowercase().contains("java")).map(|_| "a game".to_owned()),
                Err(error) => Some(format!("Flint couldn't check what is running ({error})")),
            }
        }
    };
    found.map(|_| "A game started from Flint's folder is still running. Close it first: Flint never removes files a running game may use.".to_owned())
}

// ---------- commands ----------

/// The last overview: when, of which folder, and its plans.
type Remembered = (Instant, PathBuf, Value, Vec<Plan>);

static OVERVIEW: Mutex<Option<Remembered>> = Mutex::new(None);

fn forget_overview() {
    *OVERVIEW.lock().unwrap_or_else(|e| e.into_inner()) = None;
}

/// The overview and plans, reused for a few seconds.
fn surveyed(state: &AppState, refresh: bool) -> (Value, Vec<Plan>) {
    let mut cached = OVERVIEW.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((at, root, view, plans)) = cached.as_ref() {
        if !refresh && at.elapsed() < OVERVIEW_TTL && *root == state.paths.root {
            return (view.clone(), plans.clone());
        }
    }
    let survey = Survey::of(state, SystemTime::now());
    let view = survey.view();
    let plans = survey.plans.clone();
    *cached = Some((Instant::now(), state.paths.root.clone(), view.clone(), plans.clone()));
    (view, plans)
}

/// The overview, as Settings → Storage shows it (and diagnostics export it).
pub fn overview(state: &AppState, refresh: bool) -> Value {
    let (mut view, _) = surveyed(state, refresh);
    view["busy"] = json!(busy_reason(state));
    view
}

#[tauri::command]
pub async fn storage_overview(app: AppHandle, refresh: Option<bool>) -> CmdResult<Value> {
    blocking(&app, move |state| overview(state, refresh.unwrap_or(false))).await
}

/// Exactly what a cleanup would remove, for its confirmation.
#[tauri::command]
pub async fn storage_plan(app: AppHandle, kind: String) -> CmdResult<Value> {
    let kind = Kind::parse(&kind).ok_or("Unknown cleanup")?;
    blocking(&app, move |state| {
        let (_, plans) = surveyed(state, false);
        let mut view = plans.iter().find(|p| p.kind == kind).map(Plan::view).unwrap_or_default();
        view["busy"] = json!(busy_reason(state));
        view
    })
    .await
}

/// Carries out a cleanup, only as it was shown (`plan_id`): when Flint's files changed since, nothing is removed and the
/// player is asked to look again.
#[tauri::command]
pub async fn storage_clean(app: AppHandle, kind: String, plan_id: String) -> CmdResult<Value> {
    let kind = Kind::parse(&kind).ok_or("Unknown cleanup")?;
    let state = app.state::<AppState>();
    // Profile files (content manifests, Auto Sync) are written under this lock; none is written during a cleanup.
    let _guard = state.file_lock.lock().await;
    blocking(&app, move |state| clean(state, kind, &plan_id)).await?
}

fn clean(state: &AppState, kind: Kind, plan_id: &str) -> CmdResult<Value> {
    if CLEANING.swap(true, Ordering::SeqCst) {
        return Err("Flint is already cleaning up. Try again when it's done.".into());
    }
    let result = (|| {
        if let Some(why) = busy_reason(state) {
            return Err(why);
        }
        let survey = Survey::of(state, SystemTime::now());
        let plan = survey.plan(kind);
        if plan.id() != plan_id {
            return Err("Flint's files changed since you looked. Review the cleanup again.".into());
        }
        Ok(execute(&state.paths.root, plan))
    })();
    forget_overview();
    CLEANING.store(false, Ordering::SeqCst);
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;

    fn write(path: &Path, bytes: usize) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, vec![7u8; bytes]).unwrap();
    }

    fn age(path: &Path, secs: u64) {
        let file = std::fs::File::options().write(true).open(path).unwrap();
        file.set_modified(SystemTime::now() - Duration::from_secs(secs)).unwrap();
    }

    fn profile(id: &str, version: &str, loader: &str, loader_version: Option<&str>) -> Profile {
        Profile {
            id: id.into(),
            name: format!("Profile {id}"),
            game_version: version.into(),
            loader: loader.into(),
            loader_version: loader_version.map(str::to_owned),
            folder: Some(format!("{id}-{version}")),
            ..Profile::default()
        }
    }

    fn version_json(game: &Path, id: &str, libraries: &[&str], index: &str, java: &str) {
        let libs: Vec<Value> = libraries.iter().map(|l| json!({ "name": "x:y:1", "downloads": { "artifact": { "path": l } } })).collect();
        let meta = json!({ "id": id, "libraries": libs, "assetIndex": { "id": index }, "javaVersion": { "component": java } });
        write_json(&game.join("versions").join(id).join(format!("{id}.json")), &meta).unwrap();
        write(&game.join("versions").join(id).join(format!("{id}.jar")), 1000);
    }

    fn index(game: &Path, id: &str, hashes: &[&str]) {
        let objects: serde_json::Map<String, Value> = hashes.iter().enumerate().map(|(i, h)| (format!("o{i}"), json!({ "hash": h, "size": 10 }))).collect();
        write_json(&game.join("assets/indexes").join(format!("{id}.json")), &json!({ "objects": objects })).unwrap();
        for hash in hashes {
            write(&game.join("assets/objects").join(&hash[..2]).join(hash), 10);
        }
    }

    fn hash(c: char) -> String {
        std::iter::repeat_n(c, 40).collect()
    }

    /// A data folder with two profiles on 26.3 (Fabric) and 26.2, and 1.21.1 left over from a removed profile.
    fn data_folder(root: &Path) -> Vec<Profile> {
        let game = root.join("minecraft");
        version_json(&game, "26.3", &["shared/a.jar", "new/b.jar"], "29", "java-runtime-epsilon");
        version_json(&game, "26.2", &["shared/a.jar"], "28", "java-runtime-epsilon");
        version_json(&game, "1.21.1", &["shared/a.jar", "old/c.jar"], "17", "java-runtime-delta");
        write_json(&game.join("versions/fabric-loader-0.20.1-26.3/profile.json"), &json!({ "libraries": [{ "name": "net.fabricmc:fabric-loader:0.20.1" }] })).unwrap();
        write_json(&game.join("versions/fabric-loader-0.20.1-1.21.1/profile.json"), &json!({ "libraries": [{ "name": "net.fabricmc:intermediary:1.21.1" }] })).unwrap();
        for lib in ["shared/a.jar", "new/b.jar", "old/c.jar", "net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar", "net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.jar"] {
            write(&game.join("libraries").join(lib), 500);
        }
        index(&game, "29", &[&hash('a'), &hash('b')]);
        index(&game, "28", &[&hash('a')]);
        index(&game, "17", &[&hash('a'), &hash('c')]);
        write(&root.join("runtimes/java-runtime-epsilon/windows-x64/bin/javaw.exe"), 4000);
        write(&root.join("runtimes/java-runtime-delta/windows-x64/bin/javaw.exe"), 3000);
        write(&root.join("runtimes/java-runtime-gamma/windows-x64/bin/javaw.exe"), 2000);
        write(&root.join("runtimes/index.json"), 20);
        let profiles = vec![profile("p1", "26.3", "fabric", Some("0.20.1")), profile("p2", "26.2", "vanilla", None)];
        for p in &profiles {
            let dir = root.join("profiles").join(p.folder.as_ref().unwrap());
            write(&dir.join("mods/sodium.jar"), 300);
            write(&dir.join("saves/World/level.dat"), 200);
            write(&dir.join("config/sodium.json"), 50);
            write(&dir.join("options.txt"), 50);
        }
        let p1 = root.join("profiles/p1-26.3");
        write(&p1.join(".flint-backup/mod/sodium-0.6.jar"), 280);
        write(&p1.join(".flint-backup/sync/20261101-101010-abcdef/files/options.txt"), 50);
        write(&p1.join("mods/.lithium.jar.flint-part"), 70);
        write(&p1.join(".flint-staging-jabc/iris.jar"), 90);
        write(&root.join("profiles/0123456789ab.flint-importing/mods/x.jar"), 40);
        write(&root.join("cache/skins/u.png"), 30);
        write(&root.join("cache/news.json"), 20);
        write(&root.join("cache/java-start/k.aot"), 600);
        write(&root.join("cache/java-start/k2.aot.part"), 60);
        write(&root.join("cache/store/origin/u/inventory.json"), 15);
        write(&root.join("sync/state.json"), 25);
        write(&root.join("settings.json"), 10);
        write(&root.join("accounts.json"), 10);
        write(&root.join("EBWebView/Default/x"), 100);
        // Old enough to count as left over.
        for path in [p1.join("mods/.lithium.jar.flint-part"), p1.join(".flint-staging-jabc/iris.jar"), root.join("profiles/0123456789ab.flint-importing/mods/x.jar"), root.join("cache/java-start/k2.aot.part")] {
            age(&path, 3600);
        }
        profiles
    }

    fn category(view: &Value, id: &str) -> u64 {
        view["categories"].as_array().unwrap().iter().find(|c| c["id"] == id).unwrap()["bytes"].as_u64().unwrap()
    }

    fn total_on_disk(path: &Path) -> u64 {
        let meta = std::fs::symlink_metadata(path).unwrap();
        if meta.is_dir() {
            std::fs::read_dir(path).unwrap().flatten().map(|e| total_on_disk(&e.path())).sum()
        } else {
            meta.len()
        }
    }

    #[test]
    fn only_flints_own_unfinished_saves_are_temporary() {
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        let p1 = dir.0.join("profiles/p1-26.3");
        // Flint's own (core::write_json, core::write_atomic) are; a mod's or the window's file of the same shape is not.
        for (path, flints) in [
            (dir.0.join("settings.json.tmp"), true),
            (dir.0.join("cache/catalog.json.tmp"), true),
            (p1.join("flint-content.json.tmp"), true),
            (p1.join("config/.flintclient.json.flint-tmp"), true),
            (p1.join("config/xaero/minimap.json.tmp"), false),
            (p1.join("usercache.json.tmp"), false),
            (dir.0.join("EBWebView/Default/Preferences.json.tmp"), false),
            (p1.join("mods/sodium.jar.part"), false),
        ] {
            write(&path, 11);
            age(&path, 3600);
            let survey = Survey::take(&dir.0, profiles.clone(), None, SystemTime::now());
            let listed = survey.plan(Kind::Temporary).groups.iter().flat_map(|g| &g.targets).any(|t| t.path == path);
            assert_eq!(listed, flints, "{}", path.display());
            std::fs::remove_file(&path).unwrap();
        }
    }

    #[test]
    fn every_byte_is_counted_once_in_one_category() {
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        let survey = Survey::take(&dir.0, profiles, None, SystemTime::now());
        let view = survey.view();
        assert_eq!(view["total"].as_u64().unwrap(), total_on_disk(&dir.0), "the categories add up to the folder");
        assert_eq!(category(&view, "profiles"), 2 * (300 + 200 + 50 + 50), "profiles without their backups or leftovers");
        assert_eq!(category(&view, "rollback"), 280);
        assert_eq!(category(&view, "sync"), 50 + 25, "Auto Sync's store and its backups");
        assert_eq!(category(&view, "temporary"), 70 + 90 + 40 + 60);
        assert_eq!(category(&view, "cache"), 30 + 20 + 600 + 15, "the cache without the unfinished build");
        assert_eq!(category(&view, "runtimes"), 4000 + 3000 + 2000 + 20);
        assert_eq!(category(&view, "other"), 10 + 10 + 100);
        let list = view["profiles"].as_array().unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0]["name"], "Profile p1");
        assert_eq!(list[0]["rollbackFiles"], 1);
        assert_eq!(list[0]["syncBackups"], 1);
    }

    #[test]
    fn profiles_are_listed_biggest_first_and_kept_folders_by_name() {
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        write(&dir.0.join("profiles/p2-26.2/saves/Big/region/r.0.0.mca"), 5000);
        write(&dir.0.join("profiles/Removed Profile/saves/W/level.dat"), 10);
        let view = Survey::take(&dir.0, profiles, None, SystemTime::now()).view();
        let names: Vec<&str> = view["profiles"].as_array().unwrap().iter().map(|p| p["name"].as_str().unwrap()).collect();
        assert_eq!(names, ["Profile p2", "Profile p1", "Removed Profile"]);
        assert_eq!(view["profiles"][2]["id"], Value::Null, "a folder Flint no longer lists");
    }

    #[cfg(unix)]
    #[test]
    fn links_are_not_followed_and_hard_links_count_once() {
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        let before = Survey::take(&dir.0, profiles.clone(), None, SystemTime::now()).view();
        // A profile folder linking to the game files, and a mod hard-linked into the second profile.
        std::os::unix::fs::symlink(dir.0.join("minecraft"), dir.0.join("profiles/p1-26.3/linked-game")).unwrap();
        std::fs::hard_link(dir.0.join("profiles/p1-26.3/mods/sodium.jar"), dir.0.join("profiles/p2-26.2/mods/sodium-copy.jar")).unwrap();
        let after = Survey::take(&dir.0, profiles, None, SystemTime::now()).view();
        assert_eq!(after["total"], before["total"], "neither the link nor the second name adds anything");
        assert_eq!(after["linkedBytes"], 300);
    }

    #[cfg(windows)]
    #[test]
    fn a_junction_into_the_game_files_is_not_followed() {
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        let before = Survey::take(&dir.0, profiles.clone(), None, SystemTime::now()).view();
        // mklink reads a `/` in a path as an option: each part joined on its own.
        let link = dir.0.join("profiles").join("p1-26.3").join("linked-game");
        let made = std::process::Command::new("cmd").args(["/c", "mklink", "/J"]).arg(&link).arg(dir.0.join("minecraft")).output().unwrap();
        assert!(made.status.success(), "{}{}", String::from_utf8_lossy(&made.stdout), String::from_utf8_lossy(&made.stderr));
        assert!(link.join("versions").is_dir(), "the junction leads to the game files");
        let after = Survey::take(&dir.0, profiles, None, SystemTime::now()).view();
        assert_eq!(after["total"], before["total"]);
        assert_eq!(category(&after, "game"), category(&before, "game"));
    }

    #[test]
    fn clear_cache_removes_only_cache_entries() {
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        let survey = Survey::take(&dir.0, profiles, None, SystemTime::now());
        let plan = survey.plan(Kind::Cache);
        assert_eq!(plan.size().bytes, 600 + 60 + 30 + 20, "Java's start-up cache (with its unfinished build), skins, patch notes");
        let result = execute(&dir.0, plan);
        assert_eq!(result["removedBytes"], 710);
        assert_eq!(result["failed"], json!([]));
        let cache = dir.0.join("cache");
        assert!(!cache.join("skins").exists() && !cache.join("news.json").exists() && !cache.join("java-start").exists());
        assert!(cache.join("store/origin/u/inventory.json").exists(), "not in the list, so kept");
        for kept in ["profiles/p1-26.3/saves/World/level.dat", "profiles/p1-26.3/mods/sodium.jar", "accounts.json", "settings.json", "minecraft/versions/26.3/26.3.jar"] {
            assert!(dir.0.join(kept).exists(), "{kept}");
        }
        // Running again on a clean cache is a no-op.
        let again = Survey::take(&dir.0, load(&dir.0), None, SystemTime::now());
        assert_eq!(again.plan(Kind::Cache).size().bytes, 0);
    }

    #[cfg(unix)]
    #[test]
    fn a_cleanup_reports_what_it_could_not_remove_and_frees_the_rest() {
        use std::os::unix::fs::PermissionsExt;
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        let plan = Survey::take(&dir.0, profiles, None, SystemTime::now()).plan(Kind::Cache).clone();
        std::fs::set_permissions(dir.0.join("cache/skins"), std::fs::Permissions::from_mode(0o500)).unwrap();
        let result = execute(&dir.0, &plan);
        std::fs::set_permissions(dir.0.join("cache/skins"), std::fs::Permissions::from_mode(0o700)).unwrap();
        assert!(result["failed"][0]["name"].as_str().unwrap().starts_with("Skins and capes"), "{result}");
        assert_eq!(result["removedBytes"], 600 + 60 + 20);
    }

    fn load(_root: &Path) -> Vec<Profile> {
        vec![profile("p1", "26.3", "fabric", Some("0.20.1")), profile("p2", "26.2", "vanilla", None)]
    }

    #[test]
    fn a_profile_named_like_a_temporary_file_is_never_cleaned_up() {
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        // Players can name profiles anything; these folders are theirs, worlds and all.
        for folder in ["26.3 Hardcore.flint-part", "Ideas.flint-tmp", "x.flint-importing", "abc.flint-importing"] {
            let world = dir.0.join("profiles").join(folder).join("saves/World/level.dat");
            write(&world, 10);
            age(&world, 3600);
        }
        let survey = Survey::take(&dir.0, profiles, None, SystemTime::now());
        execute(&dir.0, survey.plan(Kind::Temporary));
        crate::jobs::clean_partials(&dir.0);
        for folder in ["26.3 Hardcore.flint-part", "Ideas.flint-tmp", "x.flint-importing", "abc.flint-importing"] {
            assert!(dir.0.join("profiles").join(folder).join("saves/World/level.dat").exists(), "{folder} kept");
        }
        assert!(!crate::jobs::is_staging_name("x.flint-importing") && crate::jobs::is_staging_name("0123456789ab.flint-importing"));
    }

    #[test]
    fn temporary_cleanup_takes_leftovers_but_never_one_still_changing() {
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        // An install running right now: its staging folder and partial download were written a moment ago.
        write(&dir.0.join("profiles/p2-26.2/.flint-staging-jnow/fresh.jar"), 33);
        write(&dir.0.join("minecraft/libraries/new/.b.jar.flint-part"), 44);
        let survey = Survey::take(&dir.0, profiles, None, SystemTime::now());
        let plan = survey.plan(Kind::Temporary);
        assert_eq!(plan.size().bytes, 70 + 90 + 40 + 60);
        assert!(plan.note.as_deref().unwrap().starts_with("2 files changed"), "{:?}", plan.note);
        let labels: Vec<&str> = plan.groups.iter().map(|g| g.label.as_str()).collect();
        assert_eq!(labels, ["Unfinished downloads", "Unfinished installs and imports", "Unfinished Java start-up caches"]);
        execute(&dir.0, plan);
        assert!(!dir.0.join("profiles/p1-26.3/mods/.lithium.jar.flint-part").exists());
        assert!(!dir.0.join("profiles/p1-26.3/.flint-staging-jabc").exists());
        assert!(!dir.0.join("profiles/0123456789ab.flint-importing").exists());
        assert!(!dir.0.join("cache/java-start/k2.aot.part").exists());
        assert!(dir.0.join("profiles/p2-26.2/.flint-staging-jnow/fresh.jar").exists(), "in use: kept");
        assert!(dir.0.join("minecraft/libraries/new/.b.jar.flint-part").exists(), "in use: kept");
        assert!(dir.0.join("cache/java-start/k.aot").exists(), "a finished cache isn't temporary");
        assert!(dir.0.join("profiles/p1-26.3/mods/sodium.jar").exists());
    }

    #[test]
    fn unused_game_files_are_only_what_no_profile_needs() {
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        let plan = unused_game_plan(&dir.0, &profiles, None);
        let labels: Vec<&str> = plan.groups.iter().map(|g| g.label.as_str()).collect();
        assert_eq!(
            labels,
            [
                "Minecraft 1.21.1",
                "Fabric Loader 0.20.1 for Minecraft 1.21.1",
                "Libraries only those versions use",
                "Sounds and textures only those versions use",
                "Java 21 (java-runtime-delta)",
                "Java 17 (java-runtime-gamma)",
            ]
        );
        assert_eq!(plan.note, None);
        execute(&dir.0, &plan);
        let game = dir.0.join("minecraft");
        assert!(!game.join("versions/1.21.1").exists() && !game.join("versions/fabric-loader-0.20.1-1.21.1").exists());
        assert!(!game.join("libraries/old/c.jar").exists() && !game.join("libraries/old").exists(), "with its empty folder");
        assert!(!game.join("libraries/net/fabricmc/intermediary").exists());
        assert!(game.join("libraries/shared/a.jar").exists(), "used by 26.3 and 26.2 too");
        assert!(game.join("libraries/new/b.jar").exists() && game.join("libraries/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar").exists());
        assert!(!game.join(format!("assets/objects/cc/{}", hash('c'))).exists() && !game.join("assets/indexes/17.json").exists());
        assert!(game.join(format!("assets/objects/aa/{}", hash('a'))).exists(), "shared with used versions");
        assert!(game.join("versions/26.3/26.3.jar").exists() && game.join("versions/26.2/26.2.jar").exists() && game.join("versions/fabric-loader-0.20.1-26.3").exists());
        assert!(dir.0.join("runtimes/java-runtime-epsilon").exists() && dir.0.join("runtimes/index.json").exists());
        assert!(!dir.0.join("runtimes/java-runtime-delta").exists() && !dir.0.join("runtimes/java-runtime-gamma").exists());
        for kept in ["profiles/p1-26.3/saves/World/level.dat", "profiles/p1-26.3/mods/sodium.jar", "profiles/p1-26.3/.flint-backup/mod/sodium-0.6.jar"] {
            assert!(dir.0.join(kept).exists(), "{kept}");
        }
    }

    #[test]
    fn a_profile_on_an_older_version_keeps_its_java_and_files() {
        let dir = TestDir::new();
        let mut profiles = data_folder(&dir.0);
        // A Fabric 1.21.1 profile that hasn't picked its loader yet keeps every Fabric folder of its version, and Java 21
        // (its own) as well as Java 25 (Flint's for 1.21.x).
        profiles.push(profile("p3", "1.21.1", "fabric", None));
        let plan = unused_game_plan(&dir.0, &profiles, None);
        let labels: Vec<&str> = plan.groups.iter().map(|g| g.label.as_str()).collect();
        assert_eq!(labels, ["Java 17 (java-runtime-gamma)"]);
        // The custom Java in Settings is never removed.
        let custom = dir.0.join("runtimes/java-runtime-gamma/windows-x64/bin/javaw.exe").display().to_string();
        assert!(unused_game_plan(&dir.0, &profiles, Some(&custom)).groups.is_empty());
    }

    #[test]
    fn nothing_shared_is_judged_while_a_profile_was_never_installed() {
        let dir = TestDir::new();
        let mut profiles = data_folder(&dir.0);
        profiles.push(profile("p4", "1.8.9", "vanilla", None));
        let plan = unused_game_plan(&dir.0, &profiles, None);
        let labels: Vec<&str> = plan.groups.iter().map(|g| g.label.as_str()).collect();
        assert_eq!(labels, ["Minecraft 1.21.1", "Fabric Loader 0.20.1 for Minecraft 1.21.1"], "only whole versions no profile is on");
        assert!(plan.note.is_some());
    }

    #[test]
    fn rollback_copies_go_only_when_asked_and_nothing_else_does() {
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        let survey = Survey::take(&dir.0, profiles, None, SystemTime::now());
        let plan = survey.plan(Kind::Rollback);
        assert_eq!(plan.groups[0].label, "Profile p1: 1 earlier version");
        execute(&dir.0, plan);
        let p1 = dir.0.join("profiles/p1-26.3");
        assert!(!p1.join(".flint-backup/mod").exists());
        assert!(p1.join(".flint-backup/sync/20261101-101010-abcdef/files/options.txt").exists(), "Auto Sync's backups stay");
        assert!(p1.join("mods/sodium.jar").exists() && p1.join("saves/World/level.dat").exists() && p1.join("config/sodium.json").exists());
        // No other cleanup ever names a rollback copy, a world, a mod, a config or an account.
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        let survey = Survey::take(&dir.0, profiles, None, SystemTime::now());
        for kind in [Kind::Cache, Kind::Temporary, Kind::UnusedGame] {
            for target in survey.plan(kind).groups.iter().flat_map(|g| &g.targets) {
                let path = target.path.display().to_string().replace('\\', "/");
                for never in [".flint-backup", "/saves", "/mods/sodium.jar", "/config", "accounts.json", "settings.json", "sync/state.json"] {
                    assert!(!path.contains(never), "{kind:?} would remove {path}");
                }
            }
        }
    }

    #[test]
    fn a_plan_runs_only_as_it_was_shown_and_never_while_busy() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        data_folder(&dir.0);
        let shown = Survey::of(&state, SystemTime::now()).plan(Kind::Cache).id();
        // A game is running: refused, nothing removed.
        state.running.lock().unwrap().insert("p1".into(), RunningGame { kill: None });
        assert!(clean(&state, Kind::Cache, &shown).unwrap_err().contains("Minecraft is running"));
        state.running.lock().unwrap().clear();
        state.install_cancel.lock().unwrap().insert("p2".into(), Default::default());
        assert!(clean(&state, Kind::Cache, &shown).unwrap_err().contains("downloading or installing"));
        state.install_cancel.lock().unwrap().clear();
        assert!(dir.0.join("cache/java-start/k.aot").exists());
        // Something new arrived since the plan was shown: refused.
        write(&dir.0.join("cache/catalog.json"), 5);
        assert!(clean(&state, Kind::Cache, &shown).unwrap_err().contains("changed"));
        assert!(dir.0.join("cache/skins/u.png").exists());
        let fresh = Survey::of(&state, SystemTime::now()).plan(Kind::Cache).id();
        let result = clean(&state, Kind::Cache, &fresh).unwrap();
        assert_eq!(result["removedBytes"], 715);
        assert!(!cleaning());
    }

    #[test]
    fn an_unreadable_profile_list_keeps_every_game_file() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let profiles = data_folder(&dir.0);
        crate::data::save_profiles(&state, &ProfilesFile { profiles, ..Default::default() }).unwrap();
        let unused = |state: &AppState| Survey::of(state, SystemTime::now()).plan(Kind::UnusedGame).groups.len();
        assert_eq!(unused(&state), 6, "read: only what no profile needs");
        std::fs::write(state.paths.profiles(), b"{\"profiles\": [").unwrap();
        let survey = Survey::of(&state, SystemTime::now());
        let plan = survey.plan(Kind::UnusedGame);
        assert!(plan.groups.is_empty(), "a half-written list must not make every version look unused");
        assert!(plan.note.as_deref().is_some_and(|n| n.contains("couldn't read your profile list")));
        assert!(dir.0.join("minecraft/versions/26.3/26.3.json").exists() && dir.0.join("runtimes/java-runtime-epsilon").exists());
        std::fs::remove_file(state.paths.profiles()).unwrap();
        assert_eq!(readable_profiles(&state.paths.profiles()).map(|p| p.len()), Some(0), "no list yet is an empty one");
    }

    #[test]
    fn download_settings_are_validated() {
        assert_eq!(download_limit(&json!({})), 32, "32 at once by default");
        assert_eq!(download_limit(&json!({ "maxDownloads": 4 })), 4);
        assert_eq!(download_limit(&json!({ "maxDownloads": 500 })), 32);
        assert_eq!(download_limit(&json!({ "maxDownloads": 0 })), 1);
        assert_eq!(download_limit(&json!({ "maxDownloads": "lots" })), 32);
        assert_eq!(modrinth_download_limit(&json!({})), 8, "Modrinth's files, as before");
        assert_eq!(modrinth_download_limit(&json!({ "maxDownloads": 3 })), 3);
        assert_eq!(install_slots(&json!({})), 2);
        assert_eq!(install_slots(&json!({ "maxInstalls": 9 })), 4);
        assert_eq!(install_slots(&json!({ "maxInstalls": 1 })), 1);
    }

    #[test]
    fn fabric_folders_are_recognised() {
        assert_eq!(fabric_folder("fabric-loader-0.20.1-26.3"), Some(("0.20.1", "26.3")));
        assert_eq!(fabric_folder("fabric-loader-0.17.2-1.8.9-ornithe-gen2"), Some(("0.17.2", "1.8.9")));
        assert_eq!(fabric_folder("fabric-loader-0.20.1-26.4-pre1"), Some(("0.20.1", "26.4-pre1")));
        assert_eq!(fabric_folder("26.3"), None);
        assert_eq!(fabric_folder("fabric-loader-"), None);
    }

    #[test]
    fn the_custom_java_is_kept_however_its_path_is_written() {
        let dir = TestDir::new();
        let profiles = data_folder(&dir.0);
        let javaw = dir.0.join("runtimes/java-runtime-gamma/windows-x64/bin/javaw.exe");
        let mut written = vec![javaw.display().to_string(), dir.0.join("runtimes/java-runtime-delta/../java-runtime-gamma/windows-x64/bin/javaw.exe").display().to_string()];
        if cfg!(windows) {
            written.push(javaw.display().to_string().to_uppercase().replace('\\', "/"));
        }
        for java in written {
            let plan = unused_game_plan(&dir.0, &profiles, Some(&java));
            let labels: Vec<&str> = plan.groups.iter().map(|g| g.label.as_str()).collect();
            assert!(!labels.contains(&"Java 17 (java-runtime-gamma)") && labels.contains(&"Java 21 (java-runtime-delta)"), "{java}: {labels:?}");
        }
    }
}
