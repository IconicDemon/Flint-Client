//! Reading a source profile for an import: what each part holds, file by file, within limits. Only reads: links
//! (and on Windows every reparse point: junctions, mount points, online-only files) are never followed below a
//! profile's folders, nothing outside the parts Flint copies is opened, and every path is checked before it can become
//! a destination.
//!
//! A profile's top-level entries (`mods`, `options.txt`, `saves`…) usually live in its game folder, but some launchers
//! keep some elsewhere (`Source::resolve`): Lunar's mods, Dawn's Auto Sync folders. Destination paths are always the
//! game-folder ones; `Plan::roots` says where each top-level entry is read from.

use super::{ImportPart, ImportPartPreview, ImportSkip, PartStatus, Source};
use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::path::{Path, PathBuf};

pub const GB: u64 = 1024 * 1024 * 1024;

/// How far an import goes (docs/PROFILE_IMPORT.md, Safety). Tests use small values.
#[derive(Clone, Copy, Debug)]
pub struct Limits {
    /// Directory levels below the game folder.
    pub max_depth: usize,
    /// Files per profile (what is copied; worlds counted only when chosen).
    pub max_files: usize,
    /// Bytes per profile.
    pub max_total: u64,
    /// Bytes per file: a larger file is skipped and listed.
    pub max_file: u64,
}

impl Default for Limits {
    fn default() -> Self {
        Self { max_depth: 64, max_files: 200_000, max_total: 256 * GB, max_file: 32 * GB }
    }
}

/// Game settings at the top of a game folder.
pub const OPTION_FILES: [&str; 3] = ["options.txt", "optionsof.txt", "optionsshaders.txt"];
/// Folders mods keep per game folder; they come over with the mods ("other"). Waypoints, maps and schematics.
pub const OTHER_FOLDERS: [&str; 5] = ["schematics", "XaeroWaypoints", "XaeroWorldMap", "journeymap", "voxelmap"];
/// Names never copied wherever they're met, whatever the part: sign-ins kept by mods (In-Game Account Switcher, the
/// Essential mod), typed commands (they can hold `/login <password>`), other players' names, and launchers' own copies
/// of the settings (Lunar's optionsLC.txt).
const DENIED: [&str; 6] = ["command_history.txt", "_ias_accounts_do_not_send_to_anyone", "essential", "usercache.json", "optionslc.txt", "realms_persistence.json"];
/// The longest destination path Flint creates (bytes of the `/`-separated relative path).
const MAX_PATH: usize = 1024;

pub const LINK: &str = "It's a link to another place (or an online-only file), which Flint doesn't follow.";
pub const SENSITIVE: &str = "Its name suggests sign-in data, which Flint never copies.";
pub const DENIED_REASON: &str = "It can hold private data (sign-ins, typed commands or other players' names), so Flint never copies it.";
const UNSAFE_NAME: &str = "Its name or path can't be created safely on this computer.";
const UNREADABLE: &str = "Flint couldn't read it.";
const SAME_NAME: &str = "Another file here has the same name in different letter case.";

/// Whether directory entry metadata (never followed) is a link: a symbolic link, or on Windows any reparse point
/// (junctions and mount points included, which Rust doesn't always call links).
pub fn is_link(meta: &std::fs::Metadata) -> bool {
    if meta.file_type().is_symlink() {
        return true;
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x400;
        if meta.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
            return true;
        }
    }
    false
}

/// `path` as a real folder, not a link to one.
pub fn real_dir(path: &Path) -> bool {
    std::fs::symlink_metadata(path).is_ok_and(|m| m.is_dir() && !is_link(&m))
}

/// `path` as a real file, not a link.
pub fn real_file(path: &Path) -> Option<std::fs::Metadata> {
    std::fs::symlink_metadata(path).ok().filter(|m| m.is_file() && !is_link(m))
}

/// A name that says it may hold sign-in data: account, auth, token, session, credential, keychain, or the word msa.
pub fn sensitive(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    ["account", "auth", "token", "session", "credential", "keychain"].iter().any(|w| lower.contains(w))
        || lower.split(|c: char| !c.is_ascii_alphanumeric()).any(|word| word == "msa")
}

/// A name on the deny list (`DENIED`), any letter case.
pub fn denied(name: &str) -> bool {
    DENIED.contains(&name.to_ascii_lowercase().as_str())
}

/// Names never copied and not worth listing: hidden files, the system's folder files, Flint's own partial files.
fn ignored(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    name.starts_with('.') || lower == "desktop.ini" || lower == "thumbs.db" || lower.ends_with(crate::jobs::PART_SUFFIX) || lower.ends_with(crate::core::TMP_SUFFIX)
}

fn is_archive(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower.ends_with(".zip") || lower.ends_with(".jar")
}

/// A mod file in `mods/`: a jar, enabled or not (Fabric loads only the top of the folder).
pub fn is_mod_file(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    !ignored(name) && (lower.ends_with(".jar") || lower.ends_with(".jar.disabled")) && !crate::flintpack::is_runtime_jar(&lower)
}

/// A world: a real folder in `saves/` with a level.dat.
fn is_world(dir: &Path) -> bool {
    real_dir(dir) && real_file(&dir.join("level.dat")).is_some()
}

/// A folder of a profile as its launcher names it: a configured path may be a link (followed); one found in a game
/// folder may not.
fn dir_at(path: &Path, configured: bool) -> bool {
    if configured {
        path.is_dir()
    } else {
        real_dir(path)
    }
}

/// How many mod files a profile's mods folder holds (listing; nothing is opened). The launcher's own jars aren't counted.
pub fn count_mods(source: &Source) -> usize {
    let (mods, configured) = source.resolve("mods");
    if !dir_at(&mods, configured) {
        return 0;
    }
    std::fs::read_dir(mods)
        .map(|entries| {
            entries
                .flatten()
                .filter(|e| e.file_name().to_str().is_some_and(|n| is_mod_file(n) && !source.launcher_mod(n)))
                .filter(|e| e.metadata().is_ok_and(|m| m.is_file() && !is_link(&m)))
                .count()
        })
        .unwrap_or(0)
}

/// How many worlds a profile's `saves/` holds (listing).
pub fn count_worlds(source: &Source) -> usize {
    let (saves, configured) = source.resolve("saves");
    if !dir_at(&saves, configured) {
        return 0;
    }
    std::fs::read_dir(saves).map(|entries| entries.flatten().filter(|e| !e.file_name().to_string_lossy().starts_with('.') && is_world(&e.path())).count()).unwrap_or(0)
}

/// An options file's text (at most its first 4 MB), when it's a real file.
fn options_text(options: &Path) -> Option<String> {
    let meta = std::fs::metadata(options).ok().filter(|m| m.is_file())?;
    let mut text = String::new();
    std::fs::File::open(options).ok()?.take(meta.len().min(4 * 1024 * 1024)).read_to_string(&mut text).ok()?;
    Some(text)
}

/// The key bindings an options file holds (`key_` lines).
pub fn count_keybinds(options: &Path) -> usize {
    options_text(options).map_or(0, |text| text.lines().filter(|l| l.starts_with("key_")).count())
}

/// The `version:` an options file holds (the data version of the game that wrote it last), when it's a plain number.
pub fn options_data_version(options: &Path) -> Option<u32> {
    let text = options_text(options)?;
    text.lines().find_map(|l| l.strip_prefix("version:")).filter(|v| !v.is_empty() && v.bytes().all(|b| b.is_ascii_digit())).and_then(|v| v.parse().ok())
}

/// What kind of mod a jar is, from the descriptor files at its root. Only the zip's directory is read.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum JarKind {
    Fabric,
    Quilt,
    Forge,
    NeoForge,
    Other,
    Damaged,
}

pub fn jar_kind(path: &Path) -> JarKind {
    let Ok(file) = std::fs::File::open(path) else { return JarKind::Damaged };
    let Ok(archive) = zip::ZipArchive::new(std::io::BufReader::new(file)) else { return JarKind::Damaged };
    let has = |name: &str| archive.index_for_name(name).is_some();
    if has("fabric.mod.json") {
        JarKind::Fabric
    } else if has("quilt.mod.json") {
        JarKind::Quilt
    } else if has("META-INF/neoforge.mods.toml") {
        JarKind::NeoForge
    } else if has("META-INF/mods.toml") || has("mcmod.info") {
        JarKind::Forge
    } else {
        JarKind::Other
    }
}

/// One file to copy: its path in the new profile (`/`-separated, checked with `flintpack::clean_path_within`).
#[derive(Clone, Debug)]
pub struct PlanFile {
    pub rel: String,
    pub size: u64,
    pub part: ImportPart,
}

/// `rel` (`/`-separated, already checked) inside `base`.
pub fn join(base: &Path, rel: &str) -> PathBuf {
    rel.split('/').fold(base.to_path_buf(), |p, part| p.join(part))
}

/// Where `rel` is read from: its top-level entry's place (`roots`), then the rest of its path.
fn source_of(roots: &HashMap<String, PathBuf>, rel: &str) -> PathBuf {
    let (top, rest) = rel.split_once('/').unwrap_or((rel, ""));
    let base = roots.get(top).cloned().unwrap_or_else(|| PathBuf::from(top));
    if rest.is_empty() {
        base
    } else {
        join(&base, rest)
    }
}

impl PlanFile {
    /// The file it's copied from.
    pub fn source(&self, plan: &Plan) -> PathBuf {
        source_of(&plan.roots, &self.rel)
    }

    /// What progress shows while it's copied ("Mods", "World: Survival").
    pub fn label(&self) -> String {
        match self.part {
            ImportPart::Worlds => format!("World: {}", self.rel.split('/').nth(1).unwrap_or_default()),
            part => part.label().to_owned(),
        }
    }
}

/// How names inside a folder are checked against `sensitive`.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Names {
    /// Every file and folder name (mod settings and mod data, where a mod might keep a sign-in).
    Strict,
    /// The top of a pack folder: loose files that aren't archives; inside packs, nothing.
    PackRoot,
    /// Nothing (inside worlds and packs: their own content, never a launcher's).
    Free,
}

/// A bounded walk that collects files to copy and the ones it skips, with reasons.
struct Walk<'a> {
    source: &'a Source,
    limits: &'a Limits,
    files: Vec<PlanFile>,
    skipped: Vec<ImportSkip>,
    bytes: u64,
    /// A file or byte limit was passed: the walk stopped there.
    over: bool,
    seen: HashSet<String>,
    /// Where each top-level entry is read from, and whether its launcher configured that place.
    roots: HashMap<String, (PathBuf, bool)>,
}

impl<'a> Walk<'a> {
    fn new(source: &'a Source, limits: &'a Limits) -> Self {
        Self { source, limits, files: Vec::new(), skipped: Vec::new(), bytes: 0, over: false, seen: HashSet::new(), roots: HashMap::new() }
    }

    fn root(&mut self, top: &str) -> (PathBuf, bool) {
        let source = self.source;
        self.roots.entry(top.to_owned()).or_insert_with(|| source.resolve(top)).clone()
    }

    fn abs(&mut self, rel: &str) -> PathBuf {
        let (top, rest) = rel.split_once('/').unwrap_or((rel, ""));
        let (base, _) = self.root(top);
        if rest.is_empty() {
            base
        } else {
            join(&base, rest)
        }
    }

    /// A top-level entry's metadata: a place its launcher configured is followed, one in a game folder is not.
    fn top_meta(&mut self, top: &str) -> Option<std::fs::Metadata> {
        let (path, configured) = self.root(top);
        if configured {
            std::fs::metadata(path).ok()
        } else {
            std::fs::symlink_metadata(path).ok()
        }
    }

    fn skip(&mut self, path: impl Into<String>, reason: &str) {
        // A damaged folder could hold millions of odd names; the list stays readable.
        if self.skipped.len() < 1000 {
            self.skipped.push(ImportSkip { path: path.into(), reason: reason.to_owned() });
        }
    }

    fn add(&mut self, rel: String, size: u64, part: ImportPart) {
        if size > self.limits.max_file {
            let reason = format!("It's larger than {}, the most Flint copies in one file.", size_text(self.limits.max_file));
            self.skip(rel, &reason);
            return;
        }
        if !self.seen.insert(rel.to_lowercase()) {
            self.skip(rel, SAME_NAME);
            return;
        }
        if self.files.len() >= self.limits.max_files || self.bytes + size > self.limits.max_total {
            self.over = true;
            return;
        }
        self.bytes += size;
        self.files.push(PlanFile { rel, size, part });
    }

    /// One entry of a folder being walked: its name and path (or None when it was skipped) and its metadata.
    fn entry(&mut self, rel_dir: &str, entry: &std::fs::DirEntry) -> Option<(String, String, std::fs::Metadata)> {
        let name = match entry.file_name().into_string() {
            Ok(name) => name,
            Err(raw) => {
                self.skip(format!("{rel_dir}/{}", raw.to_string_lossy()), UNSAFE_NAME);
                return None;
            }
        };
        if ignored(&name) {
            return None;
        }
        let rel = format!("{rel_dir}/{name}");
        if denied(&name) {
            self.skip(rel, DENIED_REASON);
            return None;
        }
        let Ok(meta) = entry.metadata() else {
            self.skip(rel, UNREADABLE);
            return None;
        };
        if is_link(&meta) {
            self.skip(rel, LINK);
            return None;
        }
        if crate::flintpack::clean_path_within(&rel, self.limits.max_depth + 1, MAX_PATH).is_none() {
            self.skip(rel, UNSAFE_NAME);
            return None;
        }
        Some((name, rel, meta))
    }

    fn sorted(&mut self, rel_dir: &str) -> Option<Vec<std::fs::DirEntry>> {
        let dir = self.abs(rel_dir);
        match std::fs::read_dir(dir) {
            Ok(entries) => {
                let mut entries: Vec<_> = entries.flatten().collect();
                entries.sort_by_key(|e| e.file_name());
                Some(entries)
            }
            Err(_) => {
                self.skip(rel_dir, "Flint couldn't read this folder.");
                None
            }
        }
    }

    /// Everything below `rel_dir` (a folder already checked), for `part`.
    fn tree(&mut self, rel_dir: &str, part: ImportPart, names: Names) {
        if self.over {
            return;
        }
        let depth = rel_dir.split('/').count();
        if depth > self.limits.max_depth {
            let reason = format!("It's nested deeper than {} folders.", self.limits.max_depth);
            self.skip(rel_dir, &reason);
            return;
        }
        let Some(entries) = self.sorted(rel_dir) else { return };
        for entry in entries {
            if self.over {
                return;
            }
            let Some((name, rel, meta)) = self.entry(rel_dir, &entry) else { continue };
            let check = match names {
                Names::Strict => true,
                Names::PackRoot => meta.is_file() && !is_archive(&name),
                Names::Free => false,
            };
            if check && sensitive(&name) {
                self.skip(rel, SENSITIVE);
                continue;
            }
            if meta.is_dir() {
                let inner = if names == Names::Strict { Names::Strict } else { Names::Free };
                self.tree(&rel, part, inner);
            } else if meta.is_file() {
                self.add(rel, meta.len(), part);
            }
        }
    }

    /// A top-level folder of the profile, walked whole, when it's there.
    fn folder(&mut self, name: &str, part: ImportPart, names: Names) {
        match self.top_meta(name) {
            Some(meta) if is_link(&meta) => self.skip(name, LINK),
            Some(meta) if meta.is_dir() => self.tree(name, part, names),
            _ => {}
        }
    }

    /// A top-level file of the profile, when it's there.
    fn file(&mut self, name: &str, part: ImportPart) {
        match self.top_meta(name) {
            Some(meta) if is_link(&meta) => self.skip(name, LINK),
            Some(meta) if meta.is_file() => self.add(name.to_owned(), meta.len(), part),
            _ => {}
        }
    }

    /// The top-level entries as resolved, for copying.
    fn places(&self) -> HashMap<String, PathBuf> {
        self.roots.iter().map(|(top, (path, _))| (top.clone(), path.clone())).collect()
    }
}

/// What happens to a profile's mods.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mods {
    /// Fabric on a modern version: every Fabric jar comes over.
    Copy,
    /// Fabric on 1.8.9: Legacy Fabric mods don't run on Flint's 1.8.9 (Ornithe), and a jar doesn't say which it was
    /// made for, so none come over.
    Legacy189,
    /// A loader Flint doesn't run (its label): the mods stay behind.
    NotRun(&'static str),
    /// Vanilla: no mods are loaded.
    Vanilla,
}

/// What an import of one profile takes.
#[derive(Clone, Copy, Debug)]
pub struct How {
    pub mods: Mods,
    /// Copy the worlds.
    pub copy_worlds: bool,
    /// Walk the worlds even when they aren't copied (the preview shows their size).
    pub list_worlds: bool,
    /// The new profile runs modern Fabric (shader packs need a shader mod, which only those can have).
    pub shaders_run: bool,
}

/// Everything an import of one profile would copy, and what it leaves behind.
#[derive(Debug, Default)]
pub struct Plan {
    /// Files to copy, in order: settings and packs, then mods, then the chosen worlds.
    pub files: Vec<PlanFile>,
    /// Where each top-level entry is read from.
    pub roots: HashMap<String, PathBuf>,
    pub parts: Vec<ImportPartPreview>,
    pub skipped: Vec<ImportSkip>,
    pub warnings: Vec<String>,
    /// Bytes copied (worlds only when chosen).
    pub bytes: u64,
    /// All worlds' bytes, chosen or not.
    pub world_bytes: u64,
    pub blocker: Option<String>,
}

pub fn size_text(bytes: u64) -> String {
    let gb = bytes as f64 / GB as f64;
    if gb >= 1.0 {
        format!("{} GB", (gb * 10.0).round() / 10.0)
    } else {
        format!("{} MB", (bytes as f64 / (1024.0 * 1024.0)).ceil())
    }
}

fn part(id: ImportPart, status: PartStatus, files: &[PlanFile], count: usize, note: Option<String>) -> ImportPartPreview {
    let mine = files.iter().filter(|f| f.part == id);
    let (n, bytes) = mine.fold((0usize, 0u64), |(n, b), f| (n + 1, b + f.size));
    ImportPartPreview { id, status, files: n, bytes, count, note }
}

fn plural(n: usize, one: &str, many: &str) -> String {
    format!("{n} {}", if n == 1 { one } else { many })
}

/// Reads a profile and plans its import. Reads only.
pub fn plan(source: &Source, how: How, limits: &Limits) -> Plan {
    let mut out = Plan::default();
    // The game folder itself may be a link its launcher set up (moved to another drive); nothing inside it that is a
    // link is followed.
    if !source.exists() {
        out.blocker = Some("Its game folder is missing.".into());
        return out;
    }
    let mut walk = Walk::new(source, limits);
    // Settings and packs.
    for name in OPTION_FILES {
        walk.file(name, ImportPart::Options);
    }
    walk.folder("resourcepacks", ImportPart::Resourcepacks, Names::PackRoot);
    walk.folder("shaderpacks", ImportPart::Shaderpacks, Names::PackRoot);
    walk.file("servers.dat", ImportPart::Servers);

    // Mods, with their settings and data. The launcher's own jars are neither counted nor copied.
    let mut mod_files = 0usize;
    match walk.top_meta("mods") {
        Some(meta) if is_link(&meta) => walk.skip("mods", LINK),
        Some(meta) if meta.is_dir() => {
            if let Some(entries) = walk.sorted("mods") {
                for entry in entries {
                    let Some((name, rel, meta)) = walk.entry("mods", &entry) else { continue };
                    if !meta.is_file() || !is_mod_file(&name) || source.launcher_mod(&name) {
                        continue;
                    }
                    mod_files += 1;
                    if how.mods != Mods::Copy {
                        continue;
                    }
                    let why = match jar_kind(&entry.path()) {
                        JarKind::Fabric => None,
                        JarKind::Quilt => Some("It's a Quilt mod; Flint runs Fabric."),
                        JarKind::Forge => Some("It's a Forge mod; Flint runs Fabric."),
                        JarKind::NeoForge => Some("It's a NeoForge mod; Flint runs Fabric."),
                        JarKind::Other => Some("It isn't a Fabric mod (it has no fabric.mod.json)."),
                        JarKind::Damaged => Some("It's damaged or isn't a mod file (Flint couldn't open it as a jar)."),
                    };
                    match why {
                        Some(reason) => walk.skip(rel, reason),
                        None => walk.add(rel, meta.len(), ImportPart::Mods),
                    }
                }
            }
        }
        _ => {}
    }
    let with_mods = how.mods == Mods::Copy;
    if with_mods {
        walk.folder("config", ImportPart::Config, Names::Strict);
        for name in OTHER_FOLDERS {
            walk.folder(name, ImportPart::Other, Names::Strict);
        }
    }
    let config_there = !with_mods && {
        let (config, configured) = walk.root("config");
        dir_at(&config, configured) && std::fs::read_dir(config).is_ok_and(|mut d| d.next().is_some())
    };

    // Worlds, in a walk of their own: their size is shown even when they aren't copied.
    let mut world_names: Vec<String> = Vec::new();
    let mut worlds = Walk::new(source, limits);
    match worlds.top_meta("saves") {
        Some(meta) if is_link(&meta) => worlds.skip("saves", LINK),
        Some(meta) if meta.is_dir() && (how.copy_worlds || how.list_worlds) => {
            if let Some(entries) = worlds.sorted("saves") {
                for entry in entries {
                    let Some((name, rel, meta)) = worlds.entry("saves", &entry) else { continue };
                    if !meta.is_dir() || !is_world(&entry.path()) {
                        continue;
                    }
                    world_names.push(name);
                    worlds.tree(&rel, ImportPart::Worlds, Names::Free);
                }
            }
        }
        _ => {}
    }
    // A world's lock file belongs to a running game; it's never copied, and not worth listing.
    worlds.files.retain(|f| !f.rel.ends_with("/session.lock"));
    out.world_bytes = worlds.files.iter().map(|f| f.size).sum();

    // Limits.
    if walk.over {
        out.blocker = Some(format!("It holds more than {} files or {}, more than Flint imports at once.", limits.max_files, size_text(limits.max_total)));
    } else if how.copy_worlds && (worlds.over || walk.files.len() + worlds.files.len() > limits.max_files || walk.bytes + out.world_bytes > limits.max_total) {
        out.blocker = Some(format!(
            "With its worlds it holds more than {} files or {}, more than Flint imports at once. Import it without worlds.",
            limits.max_files,
            size_text(limits.max_total)
        ));
    } else if !how.copy_worlds && worlds.over {
        out.warnings.push("Its worlds are larger than Flint imports at once, so they can't come over.".into());
    }

    // The parts, as the preview lists them.
    let files = &walk.files;
    let mods_copied = files.iter().filter(|f| f.part == ImportPart::Mods).count();
    let mods_left = mod_files.saturating_sub(mods_copied);
    if mod_files > 0 {
        out.parts.push(match how.mods {
            Mods::Copy if mods_left == 0 => part(ImportPart::Mods, PartStatus::Compatible, files, mods_copied, None),
            Mods::Copy => part(ImportPart::Mods, PartStatus::Attention, files, mods_copied, Some(format!("{} stay behind (see the skipped files).", plural(mods_left, "file", "files")))),
            Mods::Legacy189 => part(
                ImportPart::Mods,
                PartStatus::Attention,
                files,
                0,
                Some(format!("{} stay behind: 1.8.9 mods are made for Legacy Fabric, which Flint's 1.8.9 doesn't run.", plural(mod_files, "mod", "mods"))),
            ),
            Mods::NotRun(loader) => part(ImportPart::Mods, PartStatus::Unsupported, files, 0, Some(format!("{} stay behind: Flint doesn't run {loader}.", plural(mod_files, "mod", "mods")))),
            Mods::Vanilla => part(ImportPart::Mods, PartStatus::Unsupported, files, 0, Some(format!("{} stay behind: a Vanilla profile doesn't load mods.", plural(mod_files, "mod", "mods")))),
        });
    }
    if with_mods {
        if files.iter().any(|f| f.part == ImportPart::Config) {
            let n = files.iter().filter(|f| f.part == ImportPart::Config).count();
            out.parts.push(part(ImportPart::Config, PartStatus::Compatible, files, n, None));
        }
    } else if config_there && how.mods != Mods::Vanilla {
        let status = if how.mods == Mods::Legacy189 { PartStatus::Attention } else { PartStatus::Unsupported };
        out.parts.push(part(ImportPart::Config, status, files, 0, Some("Mod settings stay behind with the mods.".into())));
    }
    let options = files.iter().filter(|f| f.part == ImportPart::Options).count();
    if options > 0 {
        out.parts.push(part(ImportPart::Options, PartStatus::Compatible, files, options, None));
        let (options_file, _) = walk.root("options.txt");
        let keys = count_keybinds(&options_file);
        if keys > 0 {
            out.parts.push(ImportPartPreview { id: ImportPart::Keybinds, status: PartStatus::Compatible, files: 0, bytes: 0, count: keys, note: Some("Kept in options.txt.".into()) });
        }
    }
    let files = &walk.files;
    for id in [ImportPart::Resourcepacks, ImportPart::Shaderpacks] {
        let top: HashSet<&str> = files.iter().filter(|f| f.part == id).filter_map(|f| f.rel.split('/').nth(1)).collect();
        if top.is_empty() {
            continue;
        }
        let (status, note) = if id == ImportPart::Shaderpacks && !how.shaders_run {
            (PartStatus::Attention, Some("Shader packs need a shader mod (Iris), which this profile won't have.".to_owned()))
        } else {
            (PartStatus::Compatible, None)
        };
        out.parts.push(part(id, status, files, top.len(), note));
    }
    if files.iter().any(|f| f.part == ImportPart::Servers) {
        out.parts.push(part(ImportPart::Servers, PartStatus::Compatible, files, 1, None));
    }
    // Worlds are listed as coming over only when they will; otherwise `world_bytes` (and the source's world count) let the
    // page say they stay behind, and what it would take to bring them.
    if how.copy_worlds && !world_names.is_empty() {
        out.parts.push(part(ImportPart::Worlds, PartStatus::Compatible, &worlds.files, world_names.len(), None));
    }
    if files.iter().any(|f| f.part == ImportPart::Other) {
        let n = files.iter().filter(|f| f.part == ImportPart::Other).count();
        out.parts.push(part(ImportPart::Other, PartStatus::Compatible, files, n, Some("Waypoints, maps and schematics that mods keep.".into())));
    }

    // What is copied, in order: small settings first (a cancelled import stops early either way).
    let mut roots = walk.places();
    roots.extend(worlds.places());
    let mut copy = walk.files;
    copy.sort_by_key(|f| f.part.order());
    out.skipped = walk.skipped;
    if how.copy_worlds {
        copy.extend(worlds.files);
        out.skipped.extend(worlds.skipped);
    }
    out.bytes = copy.iter().map(|f| f.size).sum();
    out.files = copy;
    out.roots = roots;
    if out.skipped.iter().any(|s| s.reason == LINK) {
        out.warnings.push("Some folders are links to other places; Flint doesn't follow links, so they stay behind.".into());
    }
    out
}

/// What came over after an import: the plan's parts recounted from the files really copied (those in `left` didn't
/// come). A part that was planned whole but lost files is marked `attention`; parts that stay behind entirely keep their
/// note; a worlds part with nothing copied is left out.
pub fn actual_parts(plan: &Plan, left: &[ImportSkip]) -> Vec<ImportPartPreview> {
    let gone: HashSet<&str> = left.iter().map(|s| s.path.as_str()).collect();
    let copied: Vec<&PlanFile> = plan.files.iter().filter(|f| !gone.contains(f.rel.as_str())).collect();
    let options_came = copied.iter().any(|f| f.rel == "options.txt");
    plan.parts
        .iter()
        .filter_map(|p| {
            let mine: Vec<&&PlanFile> = copied.iter().filter(|f| f.part == p.id).collect();
            let planned = plan.files.iter().filter(|f| f.part == p.id).count();
            let (files, bytes) = (mine.len(), mine.iter().map(|f| f.size).sum::<u64>());
            let distinct = || mine.iter().filter_map(|f| f.rel.split('/').nth(1)).collect::<HashSet<_>>().len();
            let count = match p.id {
                ImportPart::Keybinds => {
                    if options_came {
                        p.count
                    } else {
                        0
                    }
                }
                ImportPart::Resourcepacks | ImportPart::Shaderpacks | ImportPart::Worlds => distinct(),
                ImportPart::Servers => files.min(1),
                _ => files,
            };
            if p.id == ImportPart::Worlds && files == 0 {
                return None;
            }
            let (status, note) = if files < planned && p.status == PartStatus::Compatible {
                (PartStatus::Attention, Some(format!("{} couldn't be copied (see the skipped files).", plural(planned - files, "file", "files"))))
            } else {
                (p.status, p.note.clone())
            };
            Some(ImportPartPreview { id: p.id, status, files, bytes, count, note })
        })
        .collect()
}
