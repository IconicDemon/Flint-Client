//! Resource packs and shader packs: finding them in a profile, telling them apart by content (SHA-256), the
//! content-addressed library they are kept in (`<data>/sync/library/<folder>/<sha256>/<name>`), copying
//! them as copy-on-write clones, and whether a resource pack suits a Minecraft version.
//!
//! Copies use `std::fs::copy`, which on macOS first tries `fclonefileat` (an APFS clone: instant, sharing the
//! data until either copy changes) and falls back to `fcopyfile`; verified on this machine by the symbols the
//! standard library links (`_fclonefileat`, `_fcopyfile`) and by cloning a 300 MB file without the disk's
//! free space dropping. Should that copy fail outright (a file system that refuses both), Flint copies the
//! bytes itself. Packs are never linked (no symlinks, no hard links): every profile works on its own.

use super::store::CachedPack;
use serde::{Deserialize, Serialize};
use serde_json::Value as Json;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::io::Read;
use std::path::{Path, PathBuf};

/// A pack over this size isn't synced (a notice says so).
pub const MAX_PACK: u64 = 2 * 1024 * 1024 * 1024;
/// Above this the library is reported as large; nothing is ever deleted from it.
pub const LIBRARY_WARN: u64 = 10 * 1024 * 1024 * 1024;
/// Files in a folder pack Flint will look at.
const MAX_DIR_FILES: usize = 20_000;
const MAX_MCMETA: u64 = 1024 * 1024;
/// Resource pack formats up to this one predate `min_format` / `max_format` (`PackFormat.lastPreMinorVersion`).
const LAST_PRE_MINOR: u32 = 64;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Folder {
    #[serde(rename = "resourcepacks")]
    Resource,
    #[serde(rename = "shaderpacks")]
    Shader,
}

impl Folder {
    pub fn dir(self) -> &'static str {
        match self {
            Folder::Resource => "resourcepacks",
            Folder::Shader => "shaderpacks",
        }
    }
}

/// A pack found in a profile.
#[derive(Clone, Debug, PartialEq)]
pub struct Pack {
    pub name: String,
    pub dir: bool,
    pub size: u64,
    pub sha: String,
}

/// A name Flint may create or read in a pack folder: one plain path component, not hidden, not one of
/// Flint's temporary names.
pub fn safe_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 255
        && !name.starts_with('.')
        && !name.contains(['/', '\\', '\0'])
        && name != ".."
        && !name.ends_with(crate::core::TMP_SUFFIX)
        && !name.ends_with(crate::jobs::PART_SUFFIX)
}

fn mtime_nanos(meta: &std::fs::Metadata) -> i128 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_nanos() as i128)
        .unwrap_or(0)
}

/// SHA-256 of a file, read in pieces.
pub fn hash_file(path: &Path) -> std::io::Result<String> {
    let mut file = std::fs::File::open(path)?;
    let mut hash = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hash.update(&buf[..n]);
    }
    Ok(hex::encode(hash.finalize()))
}

/// The regular files of a folder pack (relative paths, sorted), its total size and newest modification
/// time. Links inside are skipped; too many files is an error.
/// A folder pack's files (relative path, full path), total size and newest modification time.
type Walked = (Vec<(String, PathBuf)>, u64, i128);

fn walk(dir: &Path) -> std::io::Result<Walked> {
    let mut files = Vec::new();
    let mut size = 0u64;
    let mut newest = std::fs::symlink_metadata(dir).map(|m| mtime_nanos(&m)).unwrap_or(0);
    let mut stack = vec![(dir.to_path_buf(), String::new())];
    while let Some((path, rel)) = stack.pop() {
        for entry in std::fs::read_dir(&path)? {
            let entry = entry?;
            let meta = entry.path().symlink_metadata()?;
            let Some(name) = entry.file_name().to_str().map(str::to_owned) else { continue };
            let child_rel = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
            newest = newest.max(mtime_nanos(&meta));
            if meta.file_type().is_symlink() {
                continue;
            } else if meta.is_dir() {
                stack.push((entry.path(), child_rel));
            } else if meta.is_file() {
                size += meta.len();
                files.push((child_rel, entry.path()));
                if files.len() > MAX_DIR_FILES {
                    return Err(std::io::Error::other("too many files"));
                }
            }
        }
    }
    files.sort();
    Ok((files, size, newest))
}

/// A pack's size and newest modification time, as `scan` records them.
pub fn stat(path: &Path, dir: bool) -> Option<(u64, i128)> {
    if dir {
        walk(path).ok().map(|(_, size, mtime)| (size, mtime))
    } else {
        let meta = path.symlink_metadata().ok().filter(|m| m.is_file())?;
        Some((meta.len(), mtime_nanos(&meta)))
    }
}

/// SHA-256 over a folder pack's files (each path, size and content hash, in order).
pub fn hash_dir(dir: &Path) -> std::io::Result<String> {
    let (files, ..) = walk(dir)?;
    let mut hash = Sha256::new();
    for (rel, path) in files {
        let size = std::fs::metadata(&path)?.len();
        hash.update(format!("{rel}\0{size}\0{}\n", hash_file(&path)?).as_bytes());
    }
    Ok(format!("d{}", hex::encode(hash.finalize())))
}

/// The packs in a profile folder, hashing only those whose size or time changed since `cache` (which is
/// updated). Packs over `MAX_PACK` are left out and named in the second list.
pub fn scan(profile_dir: &Path, folder: Folder, cache: &mut BTreeMap<String, CachedPack>) -> (Vec<Pack>, Vec<String>) {
    let mut packs = Vec::new();
    let mut too_large = Vec::new();
    let base = profile_dir.join(folder.dir());
    let prefix = format!("{}/", folder.dir());
    let Ok(entries) = std::fs::read_dir(&base) else {
        cache.retain(|k, _| !k.starts_with(&prefix));
        return (packs, too_large);
    };
    let mut seen_keys = Vec::new();
    let mut names: Vec<String> = entries.flatten().filter_map(|e| e.file_name().into_string().ok()).filter(|n| safe_name(n)).collect();
    names.sort();
    for name in names {
        let path = base.join(&name);
        let Ok(meta) = path.symlink_metadata() else { continue };
        let key = format!("{prefix}{name}");
        let (dir, size, mtime) = if meta.file_type().is_symlink() {
            continue;
        } else if meta.is_file() {
            // Minecraft lists .zip files; Iris also reads shader packs from zips.
            if !name.to_ascii_lowercase().ends_with(".zip") {
                continue;
            }
            (false, meta.len(), mtime_nanos(&meta))
        } else if meta.is_dir() {
            let marker = if folder == Folder::Resource { "pack.mcmeta" } else { "shaders" };
            if !path.join(marker).exists() {
                continue;
            }
            match walk(&path) {
                Ok((_, size, newest)) => (true, size, newest),
                Err(_) => continue,
            }
        } else {
            continue;
        };
        if size > MAX_PACK {
            too_large.push(name);
            continue;
        }
        let sha = match cache.get(&key) {
            Some(c) if c.size == size && c.mtime == mtime && c.dir == dir => c.sha.clone(),
            _ => match if dir { hash_dir(&path) } else { hash_file(&path) } {
                Ok(sha) => sha,
                Err(_) => continue,
            },
        };
        cache.insert(key.clone(), CachedPack { size, mtime, sha: sha.clone(), dir });
        seen_keys.push(key);
        packs.push(Pack { name, dir, size, sha });
    }
    cache.retain(|k, _| !k.starts_with(&prefix) || seen_keys.contains(k));
    (packs, too_large)
}

// ---------- copying ----------

/// How a file is copied: `std::fs::copy` (a clone where the file system allows), or a replacement in tests.
pub type Copier = fn(&Path, &Path) -> std::io::Result<u64>;

/// Copies `from` to `to` (which must not exist yet) with `copier`, falling back to copying the bytes when
/// that fails; the copy is flushed to disk.
pub fn copy_file(from: &Path, to: &Path, copier: Copier) -> std::io::Result<()> {
    if copier(from, to).is_err() {
        let _ = std::fs::remove_file(to);
        let mut src = std::fs::File::open(from)?;
        let mut dst = std::fs::File::create(to)?;
        std::io::copy(&mut src, &mut dst)?;
    }
    // Write access: Windows refuses to flush a read-only handle ("Access is denied").
    std::fs::OpenOptions::new().write(true).open(to)?.sync_all()
}

/// Copies a folder pack's regular files (links are skipped) into `to`, which must not exist yet.
pub fn copy_dir(from: &Path, to: &Path, copier: Copier) -> std::io::Result<()> {
    let (files, ..) = walk(from)?;
    std::fs::create_dir_all(to)?;
    for (rel, path) in files {
        let target = to.join(&rel);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        copy_file(&path, &target, copier)?;
    }
    Ok(())
}

// ---------- the library ----------

/// What the library knows about one pack.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LibraryPack {
    pub name: String,
    pub size: u64,
    #[serde(default)]
    pub dir: bool,
    /// The resource pack formats its pack.mcmeta declares, as Minecraft reads them; None when Minecraft
    /// wouldn't accept its pack.mcmeta (or it has none).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub formats: Option<((u32, u32), (u32, u32))>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
pub struct Library {
    #[serde(default)]
    pub resourcepacks: BTreeMap<String, LibraryPack>,
    #[serde(default)]
    pub shaderpacks: BTreeMap<String, LibraryPack>,
}

impl Library {
    pub fn folder(&self, folder: Folder) -> &BTreeMap<String, LibraryPack> {
        match folder {
            Folder::Resource => &self.resourcepacks,
            Folder::Shader => &self.shaderpacks,
        }
    }

    fn folder_mut(&mut self, folder: Folder) -> &mut BTreeMap<String, LibraryPack> {
        match folder {
            Folder::Resource => &mut self.resourcepacks,
            Folder::Shader => &mut self.shaderpacks,
        }
    }

    pub fn total_size(&self) -> u64 {
        self.resourcepacks.values().chain(self.shaderpacks.values()).map(|p| p.size).sum()
    }
}

pub fn library_dir(store: &Path) -> PathBuf {
    store.join("library")
}

pub fn load_library(store: &Path) -> Library {
    std::fs::read(library_dir(store).join("library.json")).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}

pub fn save_library(store: &Path, library: &Library) -> Result<(), String> {
    let bytes = serde_json::to_vec_pretty(library).map_err(|e| e.to_string())?;
    crate::core::write_atomic(&library_dir(store).join("library.json"), &bytes)
}

/// Where the library keeps a pack.
pub fn library_path(store: &Path, folder: Folder, sha: &str, name: &str) -> PathBuf {
    library_dir(store).join(folder.dir()).join(sha).join(name)
}

/// The library's copy of a pack, if it has one whose content still matches.
pub fn library_copy(store: &Path, library: &Library, folder: Folder, sha: &str) -> Option<(PathBuf, LibraryPack)> {
    let pack = library.folder(folder).get(sha)?;
    let path = library_path(store, folder, sha, &pack.name);
    let meta = path.symlink_metadata().ok()?;
    if meta.file_type().is_symlink() || meta.is_dir() != pack.dir {
        return None;
    }
    Some((path, pack.clone()))
}

/// Adds a profile's pack to the library unless the library already has that content. The copy is
/// assembled under a temporary name and checked against the hash before it is moved into place.
pub fn add_to_library(store: &Path, library: &mut Library, folder: Folder, pack: &Pack, from: &Path, copier: Copier) -> Result<(), String> {
    if library_copy(store, library, folder, &pack.sha).is_some() {
        return Ok(());
    }
    let dir = library_dir(store).join(folder.dir()).join(&pack.sha);
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let target = dir.join(&pack.name);
    let staging = dir.join(format!(".{}{}", pack.name, crate::core::TMP_SUFFIX));
    let _ = std::fs::remove_dir_all(&staging);
    let _ = std::fs::remove_file(&staging);
    let copied = if pack.dir { copy_dir(from, &staging, copier) } else { copy_file(from, &staging, copier) };
    let sha = copied.and_then(|_| if pack.dir { hash_dir(&staging) } else { hash_file(&staging) });
    match sha {
        Ok(sha) if sha == pack.sha => {}
        _ => {
            let _ = std::fs::remove_dir_all(&staging);
            let _ = std::fs::remove_file(&staging);
            return Err("the pack changed while it was being copied".into());
        }
    }
    if target.symlink_metadata().is_ok() {
        let _ = if target.is_dir() { std::fs::remove_dir_all(&target) } else { std::fs::remove_file(&target) };
    }
    std::fs::rename(&staging, &target).map_err(|e| e.to_string())?;
    crate::core::sync_dir(&dir);
    let formats = if folder == Folder::Resource { read_mcmeta(&target, pack.dir).and_then(|m| declared(&m)) } else { None };
    library.folder_mut(folder).insert(pack.sha.clone(), LibraryPack { name: pack.name.clone(), size: pack.size, dir: pack.dir, formats });
    Ok(())
}

// ---------- compatibility ----------

/// A pack's `pack.mcmeta`, from a zip or a folder.
pub fn read_mcmeta(path: &Path, dir: bool) -> Option<Json> {
    let bytes = if dir {
        let file = path.join("pack.mcmeta");
        if std::fs::metadata(&file).ok()?.len() > MAX_MCMETA {
            return None;
        }
        std::fs::read(file).ok()?
    } else {
        let mut archive = zip::ZipArchive::new(std::fs::File::open(path).ok()?).ok()?;
        let entry = archive.by_name("pack.mcmeta").ok()?;
        let mut bytes = Vec::new();
        entry.take(MAX_MCMETA + 1).read_to_end(&mut bytes).ok()?;
        if bytes.len() as u64 > MAX_MCMETA {
            return None;
        }
        bytes
    };
    serde_json::from_slice(bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(&bytes)).ok()
}

fn int(value: &Json) -> Option<u32> {
    value.as_u64().and_then(|n| u32::try_from(n).ok()).filter(|n| i32::try_from(*n).is_ok())
}

/// `PackFormat`'s codec: a number, or a list whose first two numbers are major and minor.
fn format(value: &Json, default_minor: u32) -> Option<(u32, u32)> {
    match value {
        Json::Array(list) if (1..=256).contains(&list.len()) => {
            let numbers: Option<Vec<u32>> = list.iter().map(int).collect();
            let numbers = numbers?;
            Some((numbers[0], if numbers.len() > 1 { numbers[1] } else { default_minor }))
        }
        other => Some((int(other)?, default_minor)),
    }
}

/// `InclusiveRange<Integer>`'s codec: a number, `[min, max]` or `{min_inclusive, max_inclusive}`.
fn int_range(value: &Json) -> Option<(u32, u32)> {
    let (min, max) = match value {
        Json::Array(list) if list.len() == 2 => (int(&list[0])?, int(&list[1])?),
        Json::Object(o) => (int(o.get("min_inclusive")?)?, int(o.get("max_inclusive")?)?),
        other => {
            let n = int(other)?;
            (n, n)
        }
    };
    (min <= max).then_some((min, max))
}

/// The formats a resource pack declares, following Minecraft 26.3's `PackFormat.IntermediaryFormat`
/// (`validate`, `validateNewFormat`, `validateOldFormat`); None where Minecraft rejects the metadata (it
/// then treats the pack as of unknown, incompatible format).
pub fn declared(mcmeta: &Json) -> Option<((u32, u32), (u32, u32))> {
    let pack = mcmeta.get("pack")?.as_object()?;
    let min = pack.get("min_format").map(|v| format(v, 0));
    let max = pack.get("max_format").map(|v| format(v, u32::MAX));
    let main = pack.get("pack_format").map(|v| v.as_i64());
    let supported = pack.get("supported_formats").map(int_range);
    // A field that is present but malformed fails the whole codec.
    if matches!(min, Some(None)) || matches!(max, Some(None)) || matches!(supported, Some(None)) || matches!(main, Some(None)) {
        return None;
    }
    let (min, max, supported) = (min.flatten(), max.flatten(), supported.flatten());
    let main = main.flatten();
    let fits = |lo: u32, hi: u32| match main {
        Some(f) => f >= lo as i64 && f <= hi as i64 && f >= 15,
        None => true,
    };
    match (min, max) {
        (Some(_), None) | (None, Some(_)) => None,
        (Some(min), Some(max)) => {
            if min > max {
                return None;
            }
            if min.0 > LAST_PRE_MINOR {
                if supported.is_some() || !fits(min.0, max.0) {
                    return None;
                }
            } else {
                let (lo, hi) = supported?;
                if lo != min.0 || (hi != max.0 && hi != LAST_PRE_MINOR) || main.is_none() || !fits(min.0, max.0) {
                    return None;
                }
            }
            Some((min, max))
        }
        (None, None) => match (supported, main) {
            (Some((lo, hi)), Some(_)) if hi <= LAST_PRE_MINOR && fits(lo, hi) => Some(((lo, 0), (hi, 0))),
            (Some(_), _) => None,
            (None, Some(f)) if (0..=LAST_PRE_MINOR as i64).contains(&f) => Some(((f as u32, 0), (f as u32, 0))),
            _ => None,
        },
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Fit {
    Compatible,
    /// Minecraft would show it as too old, too new or of unknown format, and drop it from the selection.
    Incompatible,
    /// The destination's own format isn't known.
    Unknown,
}

/// `PackCompatibility.forVersion`.
pub fn fit(formats: Option<((u32, u32), (u32, u32))>, game: Option<(u32, u32)>) -> Fit {
    let Some(game) = game else { return Fit::Unknown };
    match formats {
        Some((min, max)) if max >= game && game >= min => Fit::Compatible,
        _ => Fit::Incompatible,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;
    use serde_json::json;

    #[test]
    fn declared_formats_follow_minecraft_s_rules() {
        let d = |v: Json| declared(&json!({ "pack": v }));
        // 26.x style.
        assert_eq!(d(json!({ "description": "x", "min_format": 97, "max_format": 97 })), Some(((97, 0), (97, u32::MAX))));
        assert_eq!(d(json!({ "min_format": [88, 0], "max_format": [97, 1] })), Some(((88, 0), (97, 1))));
        assert_eq!(d(json!({ "min_format": 90 })), None, "min without max");
        assert_eq!(d(json!({ "min_format": 97, "max_format": 90 })), None);
        assert_eq!(d(json!({ "min_format": 90, "max_format": 97, "supported_formats": [90, 97] })), None, "deprecated field above 64");
        // Old style.
        assert_eq!(d(json!({ "pack_format": 34 })), Some(((34, 0), (34, 0))));
        assert_eq!(d(json!({ "pack_format": 34, "supported_formats": [18, 34] })), Some(((18, 0), (34, 0))));
        assert_eq!(d(json!({ "pack_format": 84 })), None, "a new format without min/max");
        assert_eq!(d(json!({ "pack_format": 34, "supported_formats": [18, 90] })), None);
        // Bridging both.
        assert_eq!(d(json!({ "pack_format": 46, "supported_formats": [46, 64], "min_format": 46, "max_format": 97 })), Some(((46, 0), (97, u32::MAX))));
        assert_eq!(d(json!({ "supported_formats": [46, 64], "min_format": 46, "max_format": 97 })), None, "pack_format is required below 65");
        assert_eq!(d(json!({ "description": "no format" })), None);
        assert_eq!(declared(&json!({ "nope": 1 })), None);
    }

    #[test]
    fn compatibility_is_minecraft_s() {
        let r = |a: (u32, u32), b: (u32, u32)| Some((a, b));
        assert_eq!(fit(r((97, 0), (97, u32::MAX)), Some((97, 1))), Fit::Compatible);
        assert_eq!(fit(r((84, 0), (88, u32::MAX)), Some((97, 1))), Fit::Incompatible, "too old");
        assert_eq!(fit(r((97, 0), (97, u32::MAX)), Some((88, 0))), Fit::Incompatible, "too new");
        assert_eq!(fit(r((97, 2), (98, 0)), Some((97, 1))), Fit::Incompatible, "minor counts");
        assert_eq!(fit(None, Some((97, 1))), Fit::Incompatible, "unreadable metadata");
        assert_eq!(fit(r((1, 0), (2, 0)), None), Fit::Unknown);
    }

    #[test]
    fn unsafe_names_are_refused() {
        for bad in ["", ".hidden.zip", "../x.zip", "a/b.zip", "a\\b.zip", ".x.zip.flint-tmp", "x.zip.flint-part", "x.zip.flint-tmp"] {
            assert!(!safe_name(bad), "{bad}");
        }
        assert!(safe_name("Faithful 32x (1.21).zip"));
    }

    #[test]
    fn a_copy_falls_back_to_plain_bytes_when_cloning_fails() {
        let dir = TestDir::new();
        let from = dir.0.join("pack.zip");
        std::fs::write(&from, vec![7u8; 300_000]).unwrap();
        let failing: Copier = |_, to| {
            std::fs::write(to, b"half")?;
            Err(std::io::Error::other("clone refused"))
        };
        copy_file(&from, &dir.0.join("copy.zip"), failing).unwrap();
        assert_eq!(std::fs::read(dir.0.join("copy.zip")).unwrap(), std::fs::read(&from).unwrap());
        copy_file(&from, &dir.0.join("clone.zip"), |a, b| std::fs::copy(a, b)).unwrap();
        assert_eq!(hash_file(&dir.0.join("clone.zip")).unwrap(), hash_file(&from).unwrap());
    }

    #[test]
    fn packs_are_hashed_once_until_they_change() {
        let dir = TestDir::new();
        let packs = dir.0.join("resourcepacks");
        std::fs::create_dir_all(packs.join("Folder Pack")).unwrap();
        std::fs::write(packs.join("Folder Pack/pack.mcmeta"), b"{}").unwrap();
        std::fs::write(packs.join("A.zip"), b"zip-a").unwrap();
        std::fs::write(packs.join("notes.txt"), b"not a pack").unwrap();
        std::fs::create_dir_all(packs.join("no-meta")).unwrap();
        let mut cache = BTreeMap::new();
        let (found, large) = scan(&dir.0, Folder::Resource, &mut cache);
        assert!(large.is_empty());
        assert_eq!(found.iter().map(|p| (p.name.as_str(), p.dir)).collect::<Vec<_>>(), [("A.zip", false), ("Folder Pack", true)]);
        assert_eq!(found[0].sha, hex::encode(Sha256::digest(b"zip-a")));
        // A cached hash is trusted while size and time match (proved by planting a different one).
        cache.get_mut("resourcepacks/A.zip").unwrap().sha = "cached".into();
        assert_eq!(scan(&dir.0, Folder::Resource, &mut cache).0[0].sha, "cached");
        std::fs::write(packs.join("A.zip"), b"zip-a-v2").unwrap();
        assert_eq!(scan(&dir.0, Folder::Resource, &mut cache).0[0].sha, hex::encode(Sha256::digest(b"zip-a-v2")));
        std::fs::remove_file(packs.join("A.zip")).unwrap();
        scan(&dir.0, Folder::Resource, &mut cache);
        assert!(!cache.contains_key("resourcepacks/A.zip"));
    }

    #[cfg(unix)]
    #[test]
    fn links_are_never_followed_or_copied() {
        let dir = TestDir::new();
        let outside = dir.0.join("outside");
        std::fs::create_dir_all(&outside).unwrap();
        std::fs::write(outside.join("secret.txt"), b"private").unwrap();
        let packs = dir.0.join("p/resourcepacks");
        std::fs::create_dir_all(packs.join("Pack")).unwrap();
        std::fs::write(packs.join("Pack/pack.mcmeta"), b"{}").unwrap();
        std::os::unix::fs::symlink(&outside, packs.join("Pack/escape")).unwrap();
        std::os::unix::fs::symlink(outside.join("secret.txt"), packs.join("Linked.zip")).unwrap();
        let (found, _) = scan(&dir.0.join("p"), Folder::Resource, &mut BTreeMap::new());
        assert_eq!(found.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(), ["Pack"], "the linked zip is skipped");
        let copy = dir.0.join("copy");
        copy_dir(&packs.join("Pack"), &copy, |a, b| std::fs::copy(a, b)).unwrap();
        assert!(copy.join("pack.mcmeta").exists());
        assert!(!copy.join("escape").exists(), "nothing behind the link is copied");
    }
}
