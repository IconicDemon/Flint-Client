//! Flint's own profile file, `.flintpack`: one profile (its Minecraft version and loader, and whichever
//! of its mods, packs, settings and worlds the player picked) in a single file that another Flint can
//! import, the way Modrinth's `.mrpack` carries a modpack.
//!
//! The file is a zip:
//! - `flintpack.json`, the manifest (`Manifest`): format and version, the profile's name, Minecraft
//!   version and loader, the sections it carries, and every file with its size and SHA-1;
//! - `overrides/<path>`, each file that is carried inside the pack, at its path in the profile folder.
//!
//! Mods, resource packs and shader packs that Modrinth hosts aren't copied in: their manifest entry
//! has a `download` (Modrinth CDN address and SHA-512) and the importer downloads and verifies them.
//! Anything Modrinth doesn't know is carried as a file. Flint's own runtime jar, logs, crash reports,
//! Flint's per-machine files (`flint-performance.json`, `.flint-*`) and anything outside the sections
//! below are never written, and an import never extracts a path that isn't listed, safe and inside
//! its section.

use crate::content::{ContentEntry, MODRINTH_CDN};
use crate::core::*;
use crate::data::{clean_name, find_profile, load_profiles, save_profiles, validate_version, Profile};
use crate::jobs::{self, download_verified, part_path, Expected, Fetch, PART_SUFFIX};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha1::Sha1;
use sha2::{Digest, Sha512};
use std::collections::{HashMap, HashSet};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_opener::OpenerExt;

pub const EXTENSION: &str = "flintpack";
pub const MANIFEST_NAME: &str = "flintpack.json";
pub const FORMAT: &str = "flintpack";
pub const FORMAT_VERSION: u32 = 1;
const OVERRIDES: &str = "overrides/";

const MAX_MANIFEST: u64 = 16 * 1024 * 1024;
const MAX_FILES: usize = 100_000;
const MAX_FILE: u64 = 4 * 1024 * 1024 * 1024;
/// Everything a pack may unpack to, worlds included.
const MAX_TOTAL: u64 = 32 * 1024 * 1024 * 1024;
const MAX_DEPTH: usize = 32;
/// Hashes per Modrinth lookup request.
const LOOKUP_BATCH: usize = 400;

/// The options files Minecraft and its usual mods keep at the top of the game folder.
const OPTION_FILES: [&str; 4] = ["options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat"];

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[serde(rename_all = "lowercase")]
pub enum Section {
    Mods,
    Resourcepacks,
    Shaderpacks,
    Config,
    Options,
    Flint,
    Worlds,
}

impl Section {
    pub const ALL: [Section; 7] = [Section::Mods, Section::Resourcepacks, Section::Shaderpacks, Section::Config, Section::Options, Section::Flint, Section::Worlds];

    /// Sections whose files Modrinth may host, so the pack can point to them instead of carrying them.
    fn downloadable(self) -> bool {
        matches!(self, Section::Mods | Section::Resourcepacks | Section::Shaderpacks)
    }

    /// The content-manifest kind (`flint-content.json`) whose files live in this section.
    fn holds_kind(self, kind: &str) -> bool {
        matches!((self, kind), (Section::Mods, "mod") | (Section::Resourcepacks, "resourcepack") | (Section::Shaderpacks, "shader") | (Section::Worlds, "datapack"))
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub format: String,
    pub format_version: u32,
    #[serde(default)]
    pub generator: String,
    #[serde(default)]
    pub created_at: u64,
    pub profile: PackProfile,
    pub sections: Vec<Section>,
    pub files: Vec<PackFile>,
    /// Flint's records of the included content (titles, Modrinth ids, icons), so the imported profile
    /// lists it the same way and can update it.
    #[serde(default)]
    pub content: Vec<ContentEntry>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PackProfile {
    pub name: String,
    pub game_version: String,
    pub loader: String,
    #[serde(default)]
    pub loader_version: Option<String>,
    /// The Modrinth modpack the profile came from (its title and icon), when it came from one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub modpack: Option<Value>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PackFile {
    /// Path inside the profile folder, `/`-separated.
    pub path: String,
    pub size: u64,
    pub sha1: String,
    /// Set when the file isn't in the pack and is downloaded from Modrinth instead.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub download: Option<Download>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Download {
    pub url: String,
    pub sha512: String,
}

// ---------- paths ----------

const RESERVED: [&str; 22] = [
    "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
];

/// A pack path that is safe to create inside a profile folder on every system, or None: relative,
/// `/`-separated, no empty, `.` or `..` parts, nothing Windows would refuse or silently change
/// (reserved device names, `<>:"|?*\`, control characters, a trailing dot or space).
pub fn clean_path(path: &str) -> Option<&str> {
    clean_path_within(path, MAX_DEPTH, 512)
}

/// `clean_path` with other limits: at most `max_depth` parts and `max_len` bytes. Profile imports (import/) copy game
/// folders as other launchers made them, whose worlds and mod data may nest deeper than a pack's paths.
pub fn clean_path_within(path: &str, max_depth: usize, max_len: usize) -> Option<&str> {
    if path.is_empty() || path.len() > max_len || path.starts_with('/') {
        return None;
    }
    let parts: Vec<&str> = path.split('/').collect();
    if parts.len() > max_depth {
        return None;
    }
    let ok = parts.iter().all(|part| {
        let stem = part.split('.').next().unwrap_or_default().to_ascii_lowercase();
        !part.is_empty()
            && part.len() <= 255
            && *part != "."
            && *part != ".."
            && !part.ends_with(['.', ' '])
            && !part.chars().any(|c| c.is_control() || "<>:\"|?*\\".contains(c))
            && !RESERVED.contains(&stem.as_str())
    });
    ok.then_some(path)
}

pub(crate) fn is_runtime_jar(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower == crate::runtime::FILE_NAME || lower.starts_with("flint-runtime") || lower.starts_with("flint-game-runtime")
}

/// Which section a profile file belongs to, or None when it is never put in a pack: Flint's runtime,
/// logs and crash reports, per-machine and hidden files (`.flint-*` staging, backups and partial
/// downloads), damaged-settings copies, a world's lock file, and anything outside the sections.
pub fn section_of(path: &str) -> Option<Section> {
    let parts: Vec<&str> = path.split('/').collect();
    let name = *parts.last()?;
    if parts.iter().any(|p| p.is_empty() || p.starts_with('.')) || name.ends_with(PART_SUFFIX) || name.ends_with(TMP_SUFFIX) {
        return None;
    }
    match parts.as_slice() {
        [file] if OPTION_FILES.contains(file) => Some(Section::Options),
        ["mods", file] if is_runtime_jar(file) => None,
        ["mods", _, ..] => Some(Section::Mods),
        ["resourcepacks", _, ..] => Some(Section::Resourcepacks),
        ["shaderpacks", _, ..] => Some(Section::Shaderpacks),
        ["saves", _, _, ..] if name != "session.lock" => Some(Section::Worlds),
        ["config", "flintclient.json"] => Some(Section::Flint),
        ["config", "flintclient", _, ..] if !name.contains(".damaged-") => Some(Section::Flint),
        ["config", "flintclient", ..] | ["config", "flint-performance.json"] => None,
        ["config", _, ..] => Some(Section::Config),
        _ => None,
    }
}

// ---------- reading a profile ----------

#[derive(Clone, Debug)]
pub struct LocalFile {
    pub path: String,
    pub abs: PathBuf,
    pub size: u64,
}

/// Every packable file in a profile folder, in a stable order. Links are skipped, so a pack never
/// reaches outside the profile.
pub fn collect(dir: &Path) -> Vec<LocalFile> {
    fn walk(dir: &Path, prefix: &str, depth: usize, out: &mut Vec<LocalFile>) {
        let Ok(entries) = std::fs::read_dir(dir) else { return };
        let mut entries: Vec<_> = entries.flatten().collect();
        entries.sort_by_key(|e| e.file_name());
        for entry in entries {
            let Ok(name) = entry.file_name().into_string() else { continue };
            let Ok(meta) = std::fs::symlink_metadata(entry.path()) else { continue };
            let path = if prefix.is_empty() { name } else { format!("{prefix}/{name}") };
            if meta.file_type().is_symlink() || clean_path(&path).is_none() {
                continue;
            }
            if meta.is_dir() {
                if depth < MAX_DEPTH && !path.starts_with('.') && !path.contains("/.") {
                    walk(&entry.path(), &path, depth + 1, out);
                }
            } else if section_of(&path).is_some() {
                out.push(LocalFile { path, abs: entry.path(), size: meta.len() });
            }
        }
    }
    let mut out = Vec::new();
    for root in ["mods", "resourcepacks", "shaderpacks", "config", "saves"] {
        walk(&dir.join(root), root, 1, &mut out);
    }
    for file in OPTION_FILES {
        if let Ok(meta) = std::fs::symlink_metadata(dir.join(file)) {
            if meta.is_file() {
                out.push(LocalFile { path: file.to_owned(), abs: dir.join(file), size: meta.len() });
            }
        }
    }
    out
}

/// What each section of a profile holds, for the export dialog. Only sections with files are listed.
pub fn summarize(files: &[LocalFile]) -> Vec<Value> {
    Section::ALL
        .iter()
        .filter_map(|&section| {
            let (count, size) = files.iter().filter(|f| section_of(&f.path) == Some(section)).fold((0u64, 0u64), |(c, s), f| (c + 1, s + f.size));
            let worlds = (section == Section::Worlds).then(|| files.iter().filter(|f| section_of(&f.path) == Some(section)).filter_map(|f| f.path.split('/').nth(1)).collect::<HashSet<_>>().len());
            (count > 0).then(|| json!({ "id": section, "files": count, "size": size, "worlds": worlds }))
        })
        .collect()
}

fn hash_file(path: &Path) -> std::io::Result<(String, String)> {
    let mut file = std::fs::File::open(path)?;
    let mut sha1 = Sha1::new();
    let mut sha512 = Sha512::new();
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        sha1.update(&buf[..n]);
        sha512.update(&buf[..n]);
    }
    Ok((hex::encode(sha1.finalize()), hex::encode(sha512.finalize())))
}

// ---------- writing a pack ----------

/// One file going into a pack: carried inside it, or (with `download`) referenced. A referenced file
/// was hashed beforehand, so its SHA-1 is known; a carried one is hashed while it is written.
pub struct ExportItem {
    pub file: LocalFile,
    pub sha1: Option<String>,
    pub download: Option<Download>,
}

/// Copies `reader` into `out`, hashing it and reporting progress; `tick` gets the bytes copied so far
/// in this call and returns false to stop (a cancelled job).
fn copy_hashed(reader: &mut dyn Read, out: &mut dyn Write, limit: u64, tick: &mut dyn FnMut(u64) -> bool) -> CmdResult<(u64, String)> {
    let mut sha1 = Sha1::new();
    let mut buf = vec![0u8; 256 * 1024];
    let mut done: u64 = 0;
    loop {
        let n = reader.read(&mut buf).map_err(err("Could not read a file"))?;
        if n == 0 {
            break;
        }
        done += n as u64;
        if done > limit {
            return Err("A file in the pack is larger than its listed size.".into());
        }
        sha1.update(&buf[..n]);
        out.write_all(&buf[..n]).map_err(err("Could not write the file"))?;
        if !tick(n as u64) {
            return Err("Cancelled".into());
        }
    }
    Ok((done, hex::encode(sha1.finalize())))
}

/// Compressing these again gains nothing, so they are stored as they are (much faster on big packs).
fn already_compressed(path: &str) -> bool {
    let lower = path.to_ascii_lowercase();
    [".jar", ".zip", ".png", ".jpg", ".jpeg", ".ogg", ".mca", ".gz", ".disabled"].iter().any(|ext| lower.ends_with(ext))
}

/// Writes the pack to `out`, through a partial file that only replaces `out` once it is complete.
/// Returns the manifest that was written.
pub fn write_pack(out: &Path, profile: PackProfile, sections: Vec<Section>, content: Vec<ContentEntry>, items: Vec<ExportItem>, tick: &mut dyn FnMut(u64) -> bool) -> CmdResult<Manifest> {
    let part = part_path(out);
    let result = (|| {
        let file = std::fs::File::create(&part).map_err(err("Could not create the file"))?;
        let mut zip = zip::ZipWriter::new(std::io::BufWriter::new(file));
        let mut files = Vec::with_capacity(items.len());
        for item in items {
            if let Some(download) = item.download {
                files.push(PackFile { path: item.file.path, size: item.file.size, sha1: item.sha1.unwrap_or_default(), download: Some(download) });
                if !tick(0) {
                    return Err("Cancelled".to_string());
                }
                continue;
            }
            let method = if already_compressed(&item.file.path) { zip::CompressionMethod::Stored } else { zip::CompressionMethod::Deflated };
            let options = zip::write::SimpleFileOptions::default().compression_method(method).large_file(item.file.size >= u32::MAX as u64 / 2);
            zip.start_file(format!("{OVERRIDES}{}", item.file.path), options).map_err(err("Could not write the pack"))?;
            let mut source = std::fs::File::open(&item.file.abs).map_err(|e| format!("Could not read {}: {e}", item.file.path))?;
            // A file that grows while it is read (a world being saved) is cut at a sane limit, never unbounded.
            let (size, sha1) = copy_hashed(&mut source, &mut zip, MAX_FILE, tick)?;
            files.push(PackFile { path: item.file.path, size, sha1, download: None });
        }
        let manifest = Manifest {
            format: FORMAT.into(),
            format_version: FORMAT_VERSION,
            generator: format!("Flint Client {}", env!("CARGO_PKG_VERSION")),
            created_at: now_secs(),
            profile,
            sections,
            files,
            content,
        };
        zip.start_file(MANIFEST_NAME, zip::write::SimpleFileOptions::default()).map_err(err("Could not write the pack"))?;
        zip.write_all(&serde_json::to_vec_pretty(&manifest).map_err(|e| e.to_string())?).map_err(err("Could not write the pack"))?;
        let mut writer = zip.finish().map_err(err("Could not finish the pack"))?;
        writer.flush().map_err(err("Could not finish the pack"))?;
        drop(writer);
        std::fs::rename(&part, out).map_err(err("Could not save the pack"))?;
        Ok(manifest)
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&part);
    }
    result
}

// ---------- reading a pack ----------

fn is_hex(text: &str, len: usize) -> bool {
    text.len() == len && text.bytes().all(|b| b.is_ascii_hexdigit())
}

fn valid_loader_version(version: &str) -> bool {
    !version.is_empty() && version.len() <= 64 && version.chars().all(|c| c.is_ascii_alphanumeric() || ".+-_".contains(c))
}

/// Checks everything an import relies on. Unsupported Minecraft versions aren't an error here: the
/// preview explains them (see `blocker`).
pub fn validate(manifest: &Manifest) -> CmdResult<()> {
    if manifest.format != FORMAT {
        return Err("This isn't a Flint profile file.".into());
    }
    if manifest.format_version == 0 || manifest.format_version > FORMAT_VERSION {
        return Err("This profile was exported by a newer Flint. Update Flint to import it.".into());
    }
    let p = &manifest.profile;
    if !matches!(p.loader.as_str(), "fabric" | "vanilla") {
        return Err("This profile uses a mod loader Flint doesn't support.".into());
    }
    if p.game_version.is_empty() || p.game_version.len() > 32 || !p.game_version.chars().all(|c| c.is_ascii_alphanumeric() || ".-_ ".contains(c)) {
        return Err("The file names an invalid Minecraft version.".into());
    }
    if p.loader_version.as_deref().is_some_and(|v| !valid_loader_version(v)) {
        return Err("The file names an invalid Fabric loader version.".into());
    }
    if manifest.files.len() > MAX_FILES {
        return Err("The file lists more files than Flint imports.".into());
    }
    let sections: HashSet<Section> = manifest.sections.iter().copied().collect();
    let mut seen: HashSet<String> = HashSet::new();
    let mut total: u64 = 0;
    for file in &manifest.files {
        let unsafe_path = || format!("The file lists an unsafe path ({}), so it wasn't imported.", file.path.chars().take(80).collect::<String>());
        if clean_path(&file.path).is_none() {
            return Err(unsafe_path());
        }
        let section = section_of(&file.path).filter(|s| sections.contains(s)).ok_or_else(unsafe_path)?;
        if !is_hex(&file.sha1, 40) {
            return Err("A file in the pack has no valid checksum.".into());
        }
        if file.size > MAX_FILE {
            return Err("The pack lists a file that is unexpectedly large.".into());
        }
        if let Some(d) = &file.download {
            if !section.downloadable() || !is_hex(&d.sha512, 128) || jobs::host_allowed(&d.url, MODRINTH_CDN).is_err() {
                return Err("The pack points to a download Flint doesn't trust.".into());
            }
        }
        // Windows and macOS folders ignore case, so two paths differing only in case would collide.
        if !seen.insert(file.path.to_lowercase()) {
            return Err("The pack lists the same file twice.".into());
        }
        total += file.size;
    }
    // A file can't also be a folder another file lives in.
    for path in &seen {
        let mut prefix = path.as_str();
        while let Some((parent, _)) = prefix.rsplit_once('/') {
            if seen.contains(parent) {
                return Err("The pack lists a file and a folder with the same name.".into());
            }
            prefix = parent;
        }
    }
    if total > MAX_TOTAL {
        return Err("The pack is larger than Flint imports.".into());
    }
    Ok(())
}

/// Opens a pack: reads and validates its manifest, and checks every entry in the zip. An entry whose
/// name is unsafe (absolute, `..`, a drive) rejects the whole file; files inside `overrides/` must
/// be exactly the listed ones, with their listed sizes.
pub fn read_pack(path: &Path) -> CmdResult<Manifest> {
    let file = std::fs::File::open(path).map_err(err("Could not open the file"))?;
    let mut archive = zip::ZipArchive::new(std::io::BufReader::new(file)).map_err(|_| "This file is damaged or isn't a Flint profile.".to_string())?;
    let manifest: Manifest = {
        let entry = archive.by_name(MANIFEST_NAME).map_err(|_| "This isn't a Flint profile file.".to_string())?;
        if entry.size() > MAX_MANIFEST {
            return Err("This file's manifest is unexpectedly large.".into());
        }
        let mut bytes = Vec::new();
        entry.take(MAX_MANIFEST).read_to_end(&mut bytes).map_err(|_| "This file is damaged.".to_string())?;
        serde_json::from_slice(&bytes).map_err(|_| "This file's manifest is damaged.".to_string())?
    };
    validate(&manifest)?;
    let carried: HashMap<&str, u64> = manifest.files.iter().filter(|f| f.download.is_none()).map(|f| (f.path.as_str(), f.size)).collect();
    let mut found = 0usize;
    for i in 0..archive.len() {
        let entry = archive.by_index_raw(i).map_err(|_| "This file is damaged.".to_string())?;
        let name = entry.name().to_owned();
        if name == MANIFEST_NAME {
            continue;
        }
        let bare = name.strip_suffix('/').unwrap_or(&name);
        if clean_path(bare).is_none() {
            return Err("This file contains a path outside its profile folder, so it wasn't imported.".into());
        }
        let Some(rel) = name.strip_prefix(OVERRIDES) else { continue };
        if entry.is_dir() {
            continue;
        }
        match carried.get(rel) {
            Some(&size) if size == entry.size() => found += 1,
            _ => return Err("This file contains files its manifest doesn't list.".into()),
        }
    }
    if found != carried.len() {
        return Err("This file is incomplete: some of its files are missing.".into());
    }
    Ok(manifest)
}

/// Unpacks the carried files into `dest`, checking each one's size and SHA-1.
pub fn extract(path: &Path, manifest: &Manifest, dest: &Path, tick: &mut dyn FnMut(u64) -> bool) -> CmdResult<()> {
    let file = std::fs::File::open(path).map_err(err("Could not open the file"))?;
    let mut archive = zip::ZipArchive::new(std::io::BufReader::new(file)).map_err(|_| "This file is damaged.".to_string())?;
    for item in manifest.files.iter().filter(|f| f.download.is_none()) {
        let rel = clean_path(&item.path).ok_or("The file lists an unsafe path.")?;
        let target = rel.split('/').fold(dest.to_path_buf(), |p, part| p.join(part));
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent).map_err(err("Could not create a folder"))?;
        }
        let mut entry = archive.by_name(&format!("{OVERRIDES}{rel}")).map_err(|_| "This file is incomplete: some of its files are missing.".to_string())?;
        let mut out = std::fs::File::create(&target).map_err(err("Could not write a file"))?;
        // A read error is the archive's fault (bad data, a failed CRC); a write error is this computer's.
        let (size, sha1) = copy_hashed(&mut entry, &mut out, item.size, tick)
            .map_err(|e| if e == "Cancelled" || e.starts_with("Could not write") { e } else { format!("{} in this file is damaged, so the profile wasn't imported.", item.path) })?;
        if size != item.size || !sha1.eq_ignore_ascii_case(&item.sha1) {
            return Err(format!("{} in this file is damaged (its checksum doesn't match), so the profile wasn't imported.", item.path));
        }
    }
    Ok(())
}

/// The imported profile's content records: only for files that really arrived, with safe names, and
/// without rollback history (whose backups weren't carried).
pub fn imported_content(manifest: &Manifest, dest: &Path) -> Vec<ContentEntry> {
    let sections: HashSet<Section> = manifest.sections.iter().copied().collect();
    manifest
        .content
        .iter()
        .filter(|e| sections.iter().any(|s| s.holds_kind(&e.kind)))
        .filter(|e| clean_path(&e.file_name).is_some_and(|n| !n.contains('/') && !n.starts_with('.')))
        .filter(|e| e.world.as_deref().is_none_or(|w| clean_path(w).is_some_and(|w| !w.contains('/'))))
        .filter(|e| {
            let dir = match (e.kind.as_str(), e.world.as_deref()) {
                ("datapack", Some(world)) => dest.join("saves").join(world).join("datapacks"),
                ("datapack", None) => return false,
                (kind, _) => match crate::modrinth::content_dir(kind) {
                    Some(sub) => dest.join(sub),
                    None => return false,
                },
            };
            dir.join(&e.file_name).is_file() || dir.join(format!("{}.disabled", e.file_name)).is_file()
        })
        .map(|e| ContentEntry { previous: None, ..e.clone() })
        .collect()
}

/// The Modrinth modpack record kept on an imported profile: known fields only, an https icon.
fn clean_modpack(value: Option<&Value>) -> Option<Value> {
    let v = value?.as_object()?;
    let text = |key: &str| v.get(key).and_then(Value::as_str).map(|s| s.chars().take(200).collect::<String>());
    let title = text("title")?;
    Some(json!({
        "projectId": text("projectId").filter(|id| crate::modrinth::valid_id(id)),
        "versionId": text("versionId").filter(|id| crate::modrinth::valid_id(id)),
        "title": title,
        "versionNumber": text("versionNumber"),
        "iconUrl": text("iconUrl").filter(|u| u.starts_with("https://")),
    }))
}

/// `wanted`, or `wanted (2)`, `wanted (3)`… when another profile already has the name (any case),
/// always within the 40 characters a profile name may have.
pub fn unique_name(wanted: &str, taken: &[String]) -> String {
    let taken: HashSet<String> = taken.iter().map(|n| n.to_lowercase()).collect();
    if !taken.contains(&wanted.to_lowercase()) {
        return wanted.to_owned();
    }
    (2u32..)
        .map(|n| {
            let suffix = format!(" ({n})");
            let base: String = wanted.chars().take(40 - suffix.chars().count()).collect();
            format!("{}{suffix}", base.trim_end())
        })
        .find(|candidate| !taken.contains(&candidate.to_lowercase()))
        .expect("an unused name exists")
}

/// The preview the import dialog shows, or the reason the file can't be imported.
fn preview(state: &AppState, path: &Path, manifest: &Manifest) -> Value {
    let sections: Vec<Value> = manifest
        .sections
        .iter()
        .map(|&section| {
            let files: Vec<&PackFile> = manifest.files.iter().filter(|f| section_of(&f.path) == Some(section)).collect();
            let worlds = (section == Section::Worlds).then(|| files.iter().filter_map(|f| f.path.split('/').nth(1)).collect::<HashSet<_>>().len());
            json!({
                "id": section,
                "files": files.len(),
                "size": files.iter().map(|f| f.size).sum::<u64>(),
                "downloads": files.iter().filter(|f| f.download.is_some()).count(),
                "worlds": worlds,
            })
        })
        .collect();
    let taken: Vec<String> = load_profiles(state).profiles.into_iter().map(|p| p.name).collect();
    let name = clean_name(&manifest.profile.name).unwrap_or_else(|_| "Imported profile".into());
    let blocker = validate_version(&manifest.profile.game_version, &manifest.profile.loader)
        .err()
        .map(|_| format!("Minecraft {} with {} isn't offered in this version of Flint.", manifest.profile.game_version, if manifest.profile.loader == "fabric" { "Fabric" } else { "Vanilla" }));
    json!({
        "path": path.display().to_string(),
        "fileName": path.file_name().map(|n| n.to_string_lossy().to_string()),
        "packName": manifest.profile.name,
        "name": unique_name(&name, &taken),
        "gameVersion": manifest.profile.game_version,
        "loader": manifest.profile.loader,
        "loaderVersion": manifest.profile.loader_version,
        "modpack": clean_modpack(manifest.profile.modpack.as_ref()),
        "sections": sections,
        "totalSize": manifest.files.iter().map(|f| f.size).sum::<u64>(),
        "downloads": manifest.files.iter().filter(|f| f.download.is_some()).count(),
        "createdAt": manifest.created_at,
        "generator": manifest.generator,
        "blocker": blocker,
    })
}

// ---------- Modrinth ----------

/// Download addresses for files Modrinth hosts, by SHA-512. Best effort: a file Modrinth doesn't
/// know (or a lookup that fails) is simply carried inside the pack.
async fn modrinth_downloads(state: &AppState, hashes: &[String]) -> HashMap<String, String> {
    let mut found = HashMap::new();
    for batch in hashes.chunks(LOOKUP_BATCH) {
        let Ok(result) = state.modrinth.post(&state.http, "/version_files", &json!({ "hashes": batch, "algorithm": "sha512" }), "Couldn't look up files").await else {
            break;
        };
        for hash in batch {
            let file = result.get(hash).and_then(|v| v["files"].as_array()).and_then(|files| files.iter().find(|f| f["hashes"]["sha512"].as_str().is_some_and(|h| h.eq_ignore_ascii_case(hash))));
            if let Some(url) = file.and_then(|f| f["url"].as_str()).filter(|u| jobs::host_allowed(u, MODRINTH_CDN).is_ok()) {
                found.insert(hash.clone(), url.to_owned());
            }
        }
    }
    found
}

// ---------- jobs ----------

/// Runs file work on a blocking thread with the job's progress reporting.
async fn on_thread<T: Send + 'static>(app: &AppHandle, work: impl FnOnce(&AppHandle, &AppState) -> CmdResult<T> + Send + 'static) -> CmdResult<T> {
    let app = app.clone();
    tokio::task::spawn_blocking(move || work(&app, &app.state::<AppState>())).await.map_err(|e| format!("Background task failed: {e}"))?
}

/// A progress callback for `copy_hashed`: adds to the job's byte count (throttled) and stops on cancel.
fn byte_ticker<'a>(app: &'a AppHandle, state: &'a AppState, job: &'a str, start: u64) -> impl FnMut(u64) -> bool + 'a {
    let mut done = start;
    move |n| {
        done += n;
        state.jobs.update(app, job, true, |j| j.bytes_done = done);
        !state.jobs.is_cancelled(job)
    }
}

fn ensure_not_running(state: &AppState, id: &str) -> CmdResult<()> {
    if state.running.lock().unwrap().contains_key(id) {
        return Err("Close Minecraft before exporting this profile, so its worlds and settings are saved.".into());
    }
    Ok(())
}

async fn run_export(app: &AppHandle, state: &AppState, job: &str, profile: Profile, sections: Vec<Section>, dest: PathBuf) -> CmdResult<Value> {
    let dir = state.paths.profile_dir(&profile.id);
    let wanted: HashSet<Section> = sections.iter().copied().collect();
    state.jobs.update(app, job, false, |j| {
        j.state = "running".into();
        j.stage = "Reading the profile".into();
    });
    let files: Vec<LocalFile> = on_thread(app, move |_, _| Ok(collect(&dir))).await?.into_iter().filter(|f| section_of(&f.path).is_some_and(|s| wanted.contains(&s))).collect();

    // Hash what Modrinth might host, then ask it which of those it has.
    let candidates: Vec<LocalFile> = files.iter().filter(|f| section_of(&f.path).is_some_and(Section::downloadable) ).cloned().collect();
    let total = candidates.len();
    let (job_id, hashed) = (job.to_owned(), candidates.clone());
    let hashes: HashMap<String, (String, String)> = on_thread(app, move |app, state| {
        let mut out = HashMap::new();
        for (i, file) in hashed.iter().enumerate() {
            if state.jobs.is_cancelled(&job_id) {
                return Err("Cancelled".into());
            }
            state.jobs.update(app, &job_id, true, |j| {
                j.stage = "Checking mods and packs".into();
                j.items_done = i;
                j.items_total = total;
            });
            if let Ok(pair) = hash_file(&file.abs) {
                out.insert(file.path.clone(), pair);
            }
        }
        Ok(out)
    })
    .await?;
    state.jobs.update(app, job, false, |j| j.stage = "Looking them up on Modrinth".into());
    let sha512s: Vec<String> = hashes.values().map(|(_, h)| h.clone()).collect::<HashSet<_>>().into_iter().collect();
    let urls = modrinth_downloads(state, &sha512s).await;

    let items: Vec<ExportItem> = files
        .into_iter()
        .map(|file| {
            let hash = hashes.get(&file.path);
            let download = hash.and_then(|(_, sha512)| urls.get(sha512).map(|url| Download { url: url.clone(), sha512: sha512.clone() }));
            ExportItem { sha1: hash.map(|(sha1, _)| sha1.clone()), download, file }
        })
        .collect();
    let paths: HashSet<String> = items.iter().map(|i| i.file.path.clone()).collect();
    let content: Vec<ContentEntry> = crate::content::load(state, &profile.id)
        .into_iter()
        .filter(|e| wanted.iter().any(|s| s.holds_kind(&e.kind)))
        .filter(|e| {
            let base = match (e.kind.as_str(), e.world.as_deref()) {
                ("datapack", Some(world)) => format!("saves/{world}/datapacks"),
                (kind, _) => crate::modrinth::content_dir(kind).unwrap_or_default().to_owned(),
            };
            paths.contains(&format!("{base}/{}", e.file_name)) || paths.contains(&format!("{base}/{}.disabled", e.file_name))
        })
        .map(|e| ContentEntry { previous: None, ..e })
        .collect();
    let carried_bytes: u64 = items.iter().filter(|i| i.download.is_none()).map(|i| i.file.size).sum();
    let downloads = items.iter().filter(|i| i.download.is_some()).count();
    let count = items.len();
    state.jobs.update(app, job, false, |j| {
        j.stage = "Packing files".into();
        j.items_done = 0;
        j.items_total = 0;
        j.bytes_done = 0;
        j.bytes_total = Some(carried_bytes);
    });
    let pack_profile = PackProfile {
        name: profile.name.clone(),
        game_version: profile.game_version.clone(),
        loader: profile.loader.clone(),
        loader_version: profile.loader_version.clone(),
        modpack: profile.modpack.clone(),
    };
    let (job_id, out) = (job.to_owned(), dest.clone());
    on_thread(app, move |app, state| {
        let mut tick = byte_ticker(app, state, &job_id, 0);
        write_pack(&out, pack_profile, sections, content, items, &mut tick)
    })
    .await?;
    let size = std::fs::metadata(&dest).map(|m| m.len()).unwrap_or(0);
    Ok(json!({ "path": dest.display().to_string(), "size": size, "files": count, "downloads": downloads }))
}

async fn run_import(app: &AppHandle, state: &AppState, job: &str, path: PathBuf, name: Option<String>) -> CmdResult<Value> {
    state.jobs.update(app, job, false, |j| {
        j.state = "running".into();
        j.stage = "Checking the file".into();
    });
    let source = path.clone();
    let manifest = on_thread(app, move |_, _| read_pack(&source)).await?;
    validate_version(&manifest.profile.game_version, &manifest.profile.loader)?;

    let id = random_hex(6);
    let staging = state.paths.root.join("profiles").join(format!("{id}.flint-importing"));
    std::fs::create_dir_all(staging.join("mods")).map_err(err("Could not prepare the new profile"))?;
    let result = async {
        let carried: u64 = manifest.files.iter().filter(|f| f.download.is_none()).map(|f| f.size).sum();
        state.jobs.update(app, job, false, |j| {
            j.stage = "Unpacking files".into();
            j.bytes_done = 0;
            j.bytes_total = Some(carried);
        });
        let (job_id, source, dest, listed) = (job.to_owned(), path.clone(), staging.clone(), manifest.clone());
        on_thread(app, move |app, state| {
            let mut tick = byte_ticker(app, state, &job_id, 0);
            extract(&source, &listed, &dest, &mut tick)
        })
        .await?;

        let downloads: Vec<&PackFile> = manifest.files.iter().filter(|f| f.download.is_some()).collect();
        let total = downloads.len();
        for (i, file) in downloads.into_iter().enumerate() {
            let Some(d) = &file.download else { continue };
            state.jobs.update(app, job, false, |j| {
                j.stage = format!("Downloading mods and packs ({} of {total})", i + 1);
                j.items_done = i;
                j.items_total = total;
            });
            let target = file.path.split('/').fold(staging.clone(), |p, part| p.join(part));
            download_verified(app, state, job, Fetch { url: &d.url, expected: Expected::Sha512(&d.sha512), max_bytes: jobs::listed_cap(Some(file.size)), allowed_hosts: MODRINTH_CDN, quiet: false }, &target).await?;
        }
        state.jobs.update(app, job, false, |j| {
            j.stage = "Creating the profile".into();
            j.items_done = total;
        });
        let content = imported_content(&manifest, &staging);
        if !content.is_empty() {
            write_json(&staging.join("flint-content.json"), &content)?;
        }
        Ok::<(), String>(())
    }
    .await;
    if let Err(e) = result {
        let _ = std::fs::remove_dir_all(&staging);
        return Err(e);
    }

    let wanted = name.as_deref().and_then(|n| clean_name(n).ok()).or_else(|| clean_name(&manifest.profile.name).ok()).unwrap_or_else(|| "Imported profile".into());
    let _guard = state.file_lock.lock().await;
    let mut file = load_profiles(state);
    let taken: Vec<String> = file.profiles.iter().map(|p| p.name.clone()).collect();
    let mut profile = Profile {
        id: id.clone(),
        name: unique_name(&wanted, &taken),
        game_version: manifest.profile.game_version.clone(),
        loader: manifest.profile.loader.clone(),
        loader_version: manifest.profile.loader_version.clone().filter(|_| manifest.profile.loader == "fabric"),
        created_at: now_secs(),
        last_played: None,
        installed: false,
        // The pack already holds the mods its author chose; Flint's performance set isn't added on top.
        performance: None,
        memory_mb: None,
        modpack: clean_modpack(manifest.profile.modpack.as_ref()),
        ..Profile::default()
    };
    crate::profilefolder::assign(state, &file, &mut profile);
    if let Err(e) = std::fs::rename(&staging, state.paths.profile_dir(&id)) {
        let _ = std::fs::remove_dir_all(&staging);
        return Err(format!("Could not finish the new profile: {e}"));
    }
    file.profiles.push(profile.clone());
    if file.selected.is_none() {
        file.selected = Some(id.clone());
    }
    save_profiles(state, &file)?;
    state.jobs.update(app, job, false, |j| j.profile_id = Some(id.clone()));
    Ok(json!({ "profileId": id, "name": profile.name }))
}

// ---------- commands ----------

/// What a profile could export, section by section.
#[tauri::command]
pub async fn flintpack_scan(app: AppHandle, profile_id: String) -> CmdResult<Value> {
    on_thread(&app, move |_, state| {
        find_profile(state, &profile_id)?;
        let files = collect(&state.paths.profile_dir(&profile_id));
        Ok(json!({ "sections": summarize(&files) }))
    })
    .await
}

fn file_stem_for(name: &str) -> String {
    let stem: String = name.chars().map(|c| if c.is_control() || "<>:\"/\\|?*".contains(c) { '_' } else { c }).collect();
    let stem = stem.trim().trim_end_matches('.').to_owned();
    if stem.is_empty() || clean_path(&stem).is_none() { "Flint profile".into() } else { stem }
}

fn with_extension(path: PathBuf) -> PathBuf {
    if path.extension().is_some_and(|e| e.eq_ignore_ascii_case(EXTENSION)) {
        path
    } else {
        let mut name = path.file_name().unwrap_or_default().to_os_string();
        name.push(format!(".{EXTENSION}"));
        path.with_file_name(name)
    }
}

/// The system's save dialog, starting in Downloads with `<profile>.flintpack`. None when cancelled.
#[tauri::command]
pub async fn flintpack_pick_save(app: AppHandle, window: tauri::Window, profile_id: String) -> CmdResult<Option<String>> {
    let profile = find_profile(&app.state::<AppState>(), &profile_id)?;
    let folder = app.path().download_dir().ok();
    let picked = tokio::task::spawn_blocking(move || {
        let mut dialog = rfd::FileDialog::new()
            .set_title("Export profile")
            .set_file_name(format!("{}.{EXTENSION}", file_stem_for(&profile.name)))
            .add_filter("Flint Client profile", &[EXTENSION])
            .set_parent(&window);
        if let Some(folder) = folder {
            dialog = dialog.set_directory(folder);
        }
        dialog.save_file()
    })
    .await
    .map_err(|e| e.to_string())?;
    Ok(picked.map(|p| with_extension(p).display().to_string()))
}

/// The system's open dialog for a `.flintpack`. None when cancelled.
#[tauri::command]
pub async fn flintpack_pick_open(app: AppHandle, window: tauri::Window) -> CmdResult<Option<String>> {
    let folder = app.path().download_dir().ok();
    let picked = tokio::task::spawn_blocking(move || {
        let mut dialog = rfd::FileDialog::new().set_title("Import profile").add_filter("Flint Client profile", &[EXTENSION]).set_parent(&window);
        if let Some(folder) = folder {
            dialog = dialog.set_directory(folder);
        }
        dialog.pick_file()
    })
    .await
    .map_err(|e| e.to_string())?;
    Ok(picked.map(|p| p.display().to_string()))
}

#[tauri::command]
pub async fn flintpack_export(app: AppHandle, state: State<'_, AppState>, profile_id: String, sections: Vec<Section>, path: String) -> CmdResult<String> {
    let profile = find_profile(&state, &profile_id)?;
    ensure_not_running(&state, &profile_id)?;
    let mut sections: Vec<Section> = sections.into_iter().collect::<HashSet<_>>().into_iter().collect();
    sections.sort();
    let dest = with_extension(PathBuf::from(path));
    if !dest.is_absolute() || !dest.parent().is_some_and(Path::is_dir) {
        return Err("Choose a folder that exists for the exported file.".into());
    }
    if dest.starts_with(state.paths.profile_dir(&profile_id)) {
        return Err("Save the export outside the profile's own folder.".into());
    }
    let (id, _) = state.jobs.create(&app, "export", Some(&profile.id), &format!("Exporting {}", profile.name), 0);
    jobs::spawn(&app, id.clone(), move |app, job| async move {
        let state = app.state::<AppState>();
        ensure_not_running(&state, &profile.id)?;
        run_export(&app, &state, &job, profile, sections, dest).await
    });
    Ok(id)
}

/// Reads and checks a `.flintpack` for the import preview. Nothing is written.
#[tauri::command]
pub async fn flintpack_inspect(app: AppHandle, path: String) -> CmdResult<Value> {
    on_thread(&app, move |_, state| {
        let path = PathBuf::from(path);
        if !path.is_file() {
            return Err("That file no longer exists.".into());
        }
        let manifest = read_pack(&path)?;
        Ok(preview(state, &path, &manifest))
    })
    .await
}

/// Imports a `.flintpack` as a new profile, then installs its Minecraft version and loader.
#[tauri::command]
pub async fn flintpack_import(app: AppHandle, state: State<'_, AppState>, path: String, name: Option<String>) -> CmdResult<String> {
    let path = PathBuf::from(path);
    if !path.is_file() {
        return Err("That file no longer exists.".into());
    }
    let title = name.as_deref().map(str::trim).filter(|n| !n.is_empty()).unwrap_or("profile").to_owned();
    let (id, _) = state.jobs.create(&app, "import", None, &format!("Importing {title}"), 0);
    jobs::spawn(&app, id.clone(), move |app, job| async move {
        let state = app.state::<AppState>();
        let result = run_import(&app, &state, &job, path, name).await?;
        if let Some(profile_id) = result["profileId"].as_str().map(str::to_owned) {
            let installer = app.clone();
            // The usual install, the same as pressing Install on Play: its progress shows on the profile.
            tauri::async_runtime::spawn(async move {
                let state = installer.state::<AppState>();
                let _ = crate::game::profile_install(installer.clone(), state, profile_id, None).await;
            });
        }
        Ok(result)
    });
    Ok(id)
}

/// Shows an exported file in the system's file manager.
#[tauri::command]
pub fn flintpack_reveal(app: AppHandle, path: String) -> CmdResult<()> {
    let path = PathBuf::from(path);
    if !path.is_file() || !path.extension().is_some_and(|e| e.eq_ignore_ascii_case(EXTENSION)) {
        return Err("That file no longer exists.".into());
    }
    app.opener().reveal_item_in_dir(&path).map_err(err("Could not show the file"))
}

// ---------- opening a file with Flint ----------

/// A `.flintpack` Flint was asked to open (double-click, or "Open with"), waiting for the window.
#[derive(Default)]
pub struct Opened(Mutex<Option<String>>);

/// The first `.flintpack` among a command line's arguments (the program itself is skipped).
pub fn pack_in_args<I: IntoIterator<Item = String>>(args: I) -> Option<String> {
    args.into_iter().skip(1).find(|a| Path::new(a).extension().is_some_and(|e| e.eq_ignore_ascii_case(EXTENSION)) && Path::new(a).is_file())
}

/// Queues a file to open and tells the window, which opens the import preview for it.
pub fn open(app: &AppHandle, path: String) {
    if let Some(opened) = app.try_state::<Opened>() {
        *opened.0.lock().unwrap() = Some(path);
    }
    let _ = app.emit("flintpack-open", ());
}

/// Hands the queued file (if any) to the window, once.
#[tauri::command]
pub fn flintpack_take_opened(opened: State<'_, Opened>) -> Option<String> {
    opened.0.lock().unwrap().take()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(dir: &Path, rel: &str, bytes: &[u8]) {
        let path = dir.join(rel);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, bytes).unwrap();
    }

    fn pack_profile() -> PackProfile {
        PackProfile { name: "PvP".into(), game_version: "26.3".into(), loader: "fabric".into(), loader_version: Some("0.19.5".into()), modpack: None }
    }

    fn sample_profile(dir: &Path) {
        write(dir, "mods/sodium.jar", b"sodium jar");
        write(dir, "mods/own-mod.jar", b"a mod Modrinth doesn't have");
        write(dir, "mods/zoom.jar.disabled", b"disabled mod");
        write(dir, "mods/flint-runtime.jar", b"flint runtime");
        write(dir, "mods/.flint-replaced/old.jar", b"set aside");
        write(dir, "mods/.sodium.jar.flint-part", b"partial");
        write(dir, "resourcepacks/Faithful.zip", b"pack");
        write(dir, "shaderpacks/Complementary.zip", b"shader");
        write(dir, "config/sodium-options.json", b"{\"quality\":1}");
        write(dir, "config/flint-performance.json", b"{}");
        write(dir, "config/flintclient.json", b"{\"shortcuts\":[]}");
        write(dir, "config/flintclient/control-center.json", b"{\"schema\":1}");
        write(dir, "config/flintclient/layouts/pvp.json", b"{\"schema\":1}");
        write(dir, "config/flintclient/control-center.damaged-20261015-101500.json", b"x");
        write(dir, "options.txt", b"version:5023\nfov:0.5\n");
        write(dir, "servers.dat", b"\x0a\x00\x00");
        write(dir, "saves/World/level.dat", b"level");
        write(dir, "saves/World/session.lock", b"lock");
        write(dir, "saves/World/region/r.0.0.mca", &vec![7u8; 70_000]);
        write(dir, "logs/latest.log", b"log with a token");
        write(dir, "crash-reports/crash.txt", b"crash");
        write(dir, "launcher_accounts.json", b"{\"token\":\"secret\"}");
        write(dir, ".flint-backup/mod/old.jar", b"backup");
    }

    #[test]
    fn pack_paths_must_be_safe_everywhere() {
        for good in ["mods/sodium.jar", "saves/My World/level.dat", "config/a b/c.json", "options.txt", "resourcepacks/§6Pack.zip"] {
            assert_eq!(clean_path(good), Some(good), "{good:?} is safe");
        }
        for bad in [
            "", "/etc/passwd", "../outside", "mods/../../x", "mods/./x.jar", "C:/x", "c:\\x", "mods\\x.jar", "mods//x.jar", "mods/", "mods/x.jar.", "mods/x ", "mods/CON", "mods/nul.txt",
            "mods/a|b", "mods/a\u{0}b", "saves/w/a?b",
        ] {
            assert_eq!(clean_path(bad), None, "{bad:?} must be refused");
        }
        assert_eq!(clean_path(&"a/".repeat(40)), None, "too deep");
    }

    #[test]
    fn sections_never_include_flint_internals_logs_or_secrets() {
        assert_eq!(section_of("mods/sodium.jar"), Some(Section::Mods));
        assert_eq!(section_of("mods/zoom.jar.disabled"), Some(Section::Mods));
        assert_eq!(section_of("options.txt"), Some(Section::Options));
        assert_eq!(section_of("config/flintclient.json"), Some(Section::Flint));
        assert_eq!(section_of("config/flintclient/layouts/pvp.json"), Some(Section::Flint));
        assert_eq!(section_of("config/sodium-options.json"), Some(Section::Config));
        assert_eq!(section_of("saves/World/level.dat"), Some(Section::Worlds));
        for never in [
            "mods/flint-runtime.jar",
            "mods/Flint-Runtime.jar",
            "mods/flint-runtime.jar.disabled",
            "mods/.flint-replaced/old.jar",
            "mods/.sodium.jar.flint-part",
            "config/flint-performance.json",
            "config/flintclient/control-center.damaged-20261015-101500.json",
            "config/.flintclient.json.flint-tmp",
            "saves/World/session.lock",
            "saves/level.dat",
            "logs/latest.log",
            "crash-reports/crash.txt",
            "launcher_accounts.json",
            ".flint-backup/mod/old.jar",
            "flint-content.json",
            "mods",
        ] {
            assert_eq!(section_of(never), None, "{never:?} must never be packed");
        }
    }

    #[test]
    fn export_then_import_round_trips_files_and_manifest() {
        let profile = TestDir::new();
        sample_profile(&profile.0);
        let files = collect(&profile.0);
        let paths: Vec<&str> = files.iter().map(|f| f.path.as_str()).collect();
        assert!(paths.contains(&"mods/sodium.jar") && paths.contains(&"saves/World/region/r.0.0.mca") && paths.contains(&"servers.dat"));
        for never in ["mods/flint-runtime.jar", "logs/latest.log", "crash-reports/crash.txt", "launcher_accounts.json", "config/flint-performance.json", "saves/World/session.lock"] {
            assert!(!paths.contains(&never), "{never} must not be collected");
        }
        let summary = summarize(&files);
        assert_eq!(summary.len(), 7, "every section has files: {summary:?}");
        assert_eq!(summary.iter().find(|s| s["id"] == "worlds").unwrap()["worlds"], 1);

        // Sodium is "on Modrinth": referenced, not carried.
        let sodium_hash = hash_file(&profile.0.join("mods/sodium.jar")).unwrap();
        let items: Vec<ExportItem> = files
            .iter()
            .map(|f| {
                let download = (f.path == "mods/sodium.jar").then(|| Download { url: "https://cdn.modrinth.com/data/AANobbMI/versions/abc/sodium.jar".into(), sha512: sodium_hash.1.clone() });
                ExportItem { sha1: download.as_ref().map(|_| sodium_hash.0.clone()), download, file: f.clone() }
            })
            .collect();
        let entry = ContentEntry { kind: "mod".into(), project_id: "AANobbMI".into(), version_id: "abc".into(), title: "Sodium".into(), file_name: "sodium.jar".into(), previous: None, ..Default::default() };
        let gone = ContentEntry { file_name: "missing.jar".into(), ..entry.clone() };
        let out = TestDir::new();
        let pack = out.0.join("PvP.flintpack");
        let mut ticks = 0u64;
        let written = write_pack(&pack, pack_profile(), Section::ALL.to_vec(), vec![entry.clone(), gone], items, &mut |n| {
            ticks += n;
            true
        })
        .unwrap();
        assert!(!part_path(&pack).exists(), "no partial file is left");
        assert_eq!(ticks, files.iter().filter(|f| f.path != "mods/sodium.jar").map(|f| f.size).sum::<u64>(), "progress counts every carried byte");

        let read = read_pack(&pack).unwrap();
        assert_eq!(read, written, "the manifest reads back exactly as written");
        let json = serde_json::to_value(&read).unwrap();
        assert_eq!(json["format"], "flintpack");
        assert_eq!(json["formatVersion"], 1);
        assert_eq!(json["profile"]["gameVersion"], "26.3");
        assert_eq!(serde_json::from_value::<Manifest>(json).unwrap(), written, "the JSON form round-trips");
        let sodium = read.files.iter().find(|f| f.path == "mods/sodium.jar").unwrap();
        assert_eq!(sodium.sha1, sodium_hash.0);
        assert!(sodium.download.is_some());

        let dest = TestDir::new();
        extract(&pack, &read, &dest.0, &mut |_| true).unwrap();
        for f in files.iter().filter(|f| f.path != "mods/sodium.jar") {
            assert_eq!(std::fs::read(dest.0.join(&f.path)).unwrap(), std::fs::read(&f.abs).unwrap(), "{} arrives intact", f.path);
        }
        assert!(!dest.0.join("mods/sodium.jar").exists(), "referenced files are downloaded, not carried");
        assert!(!dest.0.join("mods/flint-runtime.jar").exists() && !dest.0.join("logs").exists() && !dest.0.join("launcher_accounts.json").exists());

        std::fs::write(dest.0.join("mods/sodium.jar"), b"sodium jar").unwrap();
        let content = imported_content(&read, &dest.0);
        assert_eq!(content.len(), 1, "only records for files that arrived");
        assert_eq!(content[0].title, "Sodium");
    }

    #[test]
    fn only_chosen_sections_are_packed_and_listed() {
        let profile = TestDir::new();
        sample_profile(&profile.0);
        let files: Vec<ExportItem> = collect(&profile.0)
            .into_iter()
            .filter(|f| section_of(&f.path) == Some(Section::Options))
            .map(|file| ExportItem { file, sha1: None, download: None })
            .collect();
        let out = TestDir::new();
        let pack = out.0.join("opts.flintpack");
        write_pack(&pack, pack_profile(), vec![Section::Options], vec![], files, &mut |_| true).unwrap();
        let read = read_pack(&pack).unwrap();
        let mut paths: Vec<&str> = read.files.iter().map(|f| f.path.as_str()).collect();
        paths.sort();
        assert_eq!(paths, ["options.txt", "servers.dat"]);
    }

    /// A pack written by hand, for the checks a real export would never fail.
    fn raw_pack(dir: &Path, manifest: &Value, entries: &[(&str, &[u8])]) -> PathBuf {
        let path = dir.join(format!("{}.flintpack", random_hex(4)));
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
        zip.start_file(MANIFEST_NAME, zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(manifest.to_string().as_bytes()).unwrap();
        for (name, bytes) in entries {
            zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(bytes).unwrap();
        }
        zip.finish().unwrap();
        path
    }

    fn manifest_with(files: Value) -> Value {
        json!({
            "format": "flintpack",
            "formatVersion": 1,
            "profile": { "name": "Test", "gameVersion": "26.3", "loader": "fabric" },
            "sections": ["mods", "config", "options"],
            "files": files,
        })
    }

    fn sha1_of(bytes: &[u8]) -> String {
        hex::encode(Sha1::digest(bytes))
    }

    #[test]
    fn unsafe_or_tampered_packs_are_refused() {
        let dir = TestDir::new();
        let ok = |path: &str, bytes: &[u8]| json!({ "path": path, "size": bytes.len(), "sha1": sha1_of(bytes) });

        let good = raw_pack(&dir.0, &manifest_with(json!([ok("mods/a.jar", b"jar")])), &[("overrides/mods/a.jar", b"jar")]);
        assert!(read_pack(&good).is_ok());

        // Paths that would land outside the profile, or outside their section.
        for path in ["../evil.jar", "/abs/evil.jar", "C:/evil.jar", "mods/../../evil.jar", "mods\\evil.jar", "logs/latest.log", "mods/flint-runtime.jar", "saves/World/level.dat"] {
            let pack = raw_pack(&dir.0, &manifest_with(json!([ok(path, b"x")])), &[]);
            assert!(read_pack(&pack).is_err(), "{path:?} must be refused");
        }
        // A zip entry with an unsafe name is refused even when the manifest doesn't list it.
        for name in ["../evil.txt", "overrides/../../evil.txt", "/abs.txt", "C:\\evil.txt"] {
            let pack = raw_pack(&dir.0, &manifest_with(json!([])), &[(name, b"x")]);
            assert!(read_pack(&pack).is_err(), "entry {name:?} must be refused");
        }
        // Carried files the manifest doesn't list, or listed files that are missing.
        let extra = raw_pack(&dir.0, &manifest_with(json!([])), &[("overrides/mods/sneaky.jar", b"x")]);
        assert!(read_pack(&extra).is_err());
        let missing = raw_pack(&dir.0, &manifest_with(json!([ok("mods/a.jar", b"jar")])), &[]);
        assert!(read_pack(&missing).is_err());
        // Same file twice (case differs), a file that is also a folder.
        let twice = raw_pack(&dir.0, &manifest_with(json!([ok("mods/A.jar", b"1"), ok("mods/a.jar", b"2")])), &[("overrides/mods/A.jar", b"1"), ("overrides/mods/a.jar", b"2")]);
        assert!(read_pack(&twice).is_err());
        let folder = raw_pack(&dir.0, &manifest_with(json!([ok("config/a", b"1"), ok("config/a/b.json", b"2")])), &[("overrides/config/a", b"1"), ("overrides/config/a/b.json", b"2")]);
        assert!(read_pack(&folder).is_err());
        // Downloads only from Modrinth's CDN, with a full SHA-512.
        let mut bad_host = ok("mods/b.jar", b"b");
        bad_host["download"] = json!({ "url": "https://evil.example/b.jar", "sha512": "0".repeat(128) });
        assert!(read_pack(&raw_pack(&dir.0, &manifest_with(json!([bad_host])), &[])).is_err());
        let mut short_hash = ok("mods/b.jar", b"b");
        short_hash["download"] = json!({ "url": "https://cdn.modrinth.com/data/x/versions/y/b.jar", "sha512": "abc" });
        assert!(read_pack(&raw_pack(&dir.0, &manifest_with(json!([short_hash])), &[])).is_err());
        let mut config_download = ok("config/c.json", b"c");
        config_download["download"] = json!({ "url": "https://cdn.modrinth.com/data/x/versions/y/c.json", "sha512": "0".repeat(128) });
        assert!(read_pack(&raw_pack(&dir.0, &manifest_with(json!([config_download])), &[])).is_err(), "only mods and packs are downloadable");
        // Size limits.
        let huge = json!([{ "path": "mods/big.jar", "size": MAX_FILE + 1, "sha1": "0".repeat(40) }]);
        assert!(read_pack(&raw_pack(&dir.0, &manifest_with(huge), &[])).is_err());
        // A newer format, or not a Flint file at all.
        let mut newer = manifest_with(json!([]));
        newer["formatVersion"] = json!(FORMAT_VERSION + 1);
        assert!(read_pack(&raw_pack(&dir.0, &newer, &[])).unwrap_err().contains("newer Flint"));
        let mut other = manifest_with(json!([]));
        other["format"] = json!("mrpack");
        assert!(read_pack(&raw_pack(&dir.0, &other, &[])).is_err());

        // A carried file whose bytes don't match its checksum is caught while unpacking, and the
        // listed size caps what is read.
        let tampered = raw_pack(&dir.0, &manifest_with(json!([{ "path": "mods/a.jar", "size": 3, "sha1": sha1_of(b"abc") }])), &[("overrides/mods/a.jar", b"xyz")]);
        let manifest = read_pack(&tampered).unwrap();
        let dest = TestDir::new();
        assert!(extract(&tampered, &manifest, &dest.0, &mut |_| true).unwrap_err().contains("damaged"));
    }

    #[test]
    fn imported_names_never_clash() {
        let taken = vec!["PvP".to_owned(), "pvp (2)".to_owned(), "Survival".to_owned()];
        assert_eq!(unique_name("Creative", &taken), "Creative");
        assert_eq!(unique_name("PvP", &taken), "PvP (3)");
        assert_eq!(unique_name("survival", &taken), "survival (2)");
        let long = "x".repeat(40);
        let renamed = unique_name(&long, std::slice::from_ref(&long));
        assert_eq!(renamed.chars().count(), 40);
        assert!(renamed.ends_with(" (2)"));
    }

    #[test]
    fn only_a_real_flintpack_argument_is_opened() {
        let dir = TestDir::new();
        let pack = dir.0.join("Shared.FlintPack");
        std::fs::write(&pack, b"zip").unwrap();
        let exe = "flint-app.exe".to_owned();
        assert_eq!(pack_in_args([exe.clone(), "--minimized".into(), pack.display().to_string()]), Some(pack.display().to_string()));
        assert_eq!(pack_in_args([pack.display().to_string()]), None, "the program path itself is skipped");
        assert_eq!(pack_in_args([exe, dir.0.join("missing.flintpack").display().to_string()]), None);
    }

    #[test]
    fn export_file_names_are_safe() {
        assert_eq!(file_stem_for("PvP: 1.8 / Ranked?"), "PvP_ 1.8 _ Ranked_");
        assert_eq!(file_stem_for("  ..  "), "Flint profile");
        assert_eq!(file_stem_for("CON"), "Flint profile");
        assert_eq!(with_extension(PathBuf::from("/tmp/a")), PathBuf::from("/tmp/a.flintpack"));
        assert_eq!(with_extension(PathBuf::from("/tmp/a.FLINTPACK")), PathBuf::from("/tmp/a.FLINTPACK"));
    }
}
