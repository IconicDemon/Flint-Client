//! Backups Auto Sync makes before it changes anything in a profile, and putting files back from them.
//!
//! Each backup is a folder `profiles/<id>/.flint-backup/sync/<yyyyMMdd-HHmmss>-<rand>/` (UTC time) holding a
//! copy of every file about to change, under `files/`, and a `manifest.json` listing each file's path,
//! whether it existed, its size and SHA-256. The folder is assembled as `.<name>.flint-tmp`, flushed and
//! renamed into place, so a backup is complete or absent. Copies are copy-on-write clones where the file
//! system allows (`packs::copy_file`), so backing up a large pack costs no space on APFS. A file that didn't
//! exist is recorded as such, and undoing removes it again: the only file Flint ever deletes here, and only
//! one Flint created in that same operation.
//!
//! Auto Sync keeps the 10 newest of its own backups per profile (`prune`); it never deletes any other folder.

use super::apply::link_problem;
use super::autosync::{mods, packs};
use crate::core::{now_secs, random_hex, sync_dir, CmdResult, TMP_SUFFIX};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};

pub const DIR: &str = ".flint-backup/sync";
const SCHEMA: u32 = 1;
const MANIFEST: &str = "manifest.json";
const FILES: &str = "files";
/// Every Auto Sync backup's reason starts with this; only such backups are ever pruned.
pub const REASON_PREFIX: &str = "Auto Sync";
/// Auto Sync backups kept per profile.
pub const KEEP: usize = 10;

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
struct Manifest {
    schema: u32,
    created: u64,
    /// Nanoseconds since the Unix epoch, to order backups made within the same second.
    #[serde(default)]
    created_nanos: u64,
    reason: String,
    files: Vec<ManifestFile>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
struct ManifestFile {
    path: String,
    existed: bool,
    size: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    sha256: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct BackupFile {
    pub path: String,
    pub existed: bool,
    pub size: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct BackupInfo {
    pub id: String,
    /// Seconds since the Unix epoch.
    pub created_at: u64,
    pub reason: String,
    pub files: Vec<BackupFile>,
    pub valid: bool,
    pub problem: Option<String>,
    order: u64,
}

/// The only files Auto Sync may back up, write or put back, relative to the profile folder: options.txt,
/// files in `config/` (not Flint's per-launch performance config, nor anything sensitive by name outside
/// Flint's own folder), and packs directly in `resourcepacks/` or `shaderpacks/`.
pub fn allowed_path(rel: &str) -> bool {
    let parts: Vec<&str> = rel.split('/').collect();
    let plain = |p: &&str| !p.is_empty() && *p != "." && *p != ".." && !p.starts_with('.') && !p.contains(['\\', '\0', ':']);
    if !parts.iter().all(plain) {
        return false;
    }
    match parts.as_slice() {
        ["options.txt"] => true,
        ["resourcepacks" | "shaderpacks", name] => packs::safe_name(name),
        ["config", "flintclient.json"] => true,
        ["config", "flintclient", ..] => parts.len() > 2,
        ["config", rest @ ..] => !rest.is_empty() && rel != "config/flint-performance.json" && !mods::sensitive(rel),
        _ => false,
    }
}

fn valid_id(id: &str) -> bool {
    (1..=48).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

fn root(profile_dir: &Path) -> PathBuf {
    profile_dir.join(DIR)
}

pub fn sha256(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

/// `yyyyMMdd-HHmmss` in UTC.
pub fn stamp(secs: u64) -> String {
    let days = (secs / 86_400) as i64;
    let rem = secs % 86_400;
    // Howard Hinnant's civil-from-days.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!("{year:04}{month:02}{day:02}-{:02}{:02}{:02}", rem / 3_600, rem % 3_600 / 60, rem % 60)
}

/// Copies `rels` (paths relative to `profile_dir`) into a new backup; returns its id. A path Auto Sync
/// doesn't manage (`allowed_path`), a link, or a folder where a file should be is refused, so nothing is
/// ever written without its backup.
pub fn create(profile_dir: &Path, rels: &[String], reason: &str, copier: packs::Copier) -> CmdResult<String> {
    if let Some(rel) = rels.iter().find(|rel| !allowed_path(rel)) {
        return Err(format!("Flint doesn't back up or change {rel}, so nothing was changed."));
    }
    if let Some(problem) = rels.iter().find_map(|rel| link_problem(profile_dir, rel)) {
        return Err(problem);
    }
    let root = root(profile_dir);
    std::fs::create_dir_all(&root).map_err(|e| format!("Couldn't make a backup of this profile's settings: {e}"))?;
    let created = now_secs();
    let mut id = format!("{}-{}", stamp(created), random_hex(3));
    while root.join(&id).exists() {
        id = format!("{}-{}", stamp(created), random_hex(3));
    }
    let staging = root.join(format!(".{id}{TMP_SUFFIX}"));
    let made = (|| {
        let mut files = Vec::new();
        std::fs::create_dir_all(&staging)?;
        for rel in rels {
            if files.iter().any(|f: &ManifestFile| &f.path == rel) {
                continue;
            }
            let path = profile_dir.join(rel);
            match std::fs::symlink_metadata(&path) {
                Ok(meta) if meta.is_file() => {
                    let copy = staging.join(FILES).join(rel);
                    std::fs::create_dir_all(copy.parent().expect("a file path has a parent"))?;
                    packs::copy_file(&path, &copy, copier)?;
                    let sha = packs::hash_file(&copy)?;
                    let size = std::fs::metadata(&copy)?.len();
                    files.push(ManifestFile { path: rel.clone(), existed: true, size, sha256: Some(sha) });
                }
                Ok(_) => return Err(std::io::Error::other(format!("{rel} isn't a file"))),
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => files.push(ManifestFile { path: rel.clone(), existed: false, size: 0, sha256: None }),
                Err(e) => return Err(e),
            }
        }
        let created_nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0);
        let manifest = Manifest { schema: SCHEMA, created, created_nanos, reason: reason.to_owned(), files };
        write_synced(&staging.join(MANIFEST), &serde_json::to_vec_pretty(&manifest).map_err(std::io::Error::other)?)?;
        sync_tree(&staging);
        std::fs::rename(&staging, root.join(&id))?;
        sync_dir(&root);
        Ok::<_, std::io::Error>(())
    })();
    if let Err(e) = made {
        let _ = std::fs::remove_dir_all(&staging);
        return Err(format!("Couldn't make a backup of this profile's settings, so nothing was changed: {e}"));
    }
    Ok(id)
}

fn write_synced(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let mut file = std::fs::File::create(path)?;
    file.write_all(bytes)?;
    file.sync_all()
}

/// Flushes every folder under `dir` (the files themselves were flushed as they were written).
fn sync_tree(dir: &Path) {
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            if entry.file_type().is_ok_and(|t| t.is_dir()) {
                sync_tree(&entry.path());
            }
        }
    }
    sync_dir(dir);
}

fn read_manifest(dir: &Path) -> Result<Manifest, String> {
    let bytes = std::fs::read(dir.join(MANIFEST)).map_err(|_| "Its list of files is missing.".to_owned())?;
    let manifest: Manifest = serde_json::from_slice(&bytes).map_err(|_| "Its list of files can't be read.".to_owned())?;
    if manifest.schema > SCHEMA {
        return Err("It was made by a newer Flint.".into());
    }
    Ok(manifest)
}

/// Checks that every file the manifest lists is in the backup, unchanged.
fn check(dir: &Path, manifest: &Manifest) -> Result<(), String> {
    for file in &manifest.files {
        if !allowed_path(&file.path) {
            return Err(format!("It lists a file Flint doesn't restore ({}).", file.path));
        }
        if !file.existed {
            continue;
        }
        let copy = dir.join(FILES).join(&file.path);
        let size = std::fs::metadata(&copy).map_err(|_| format!("{} is missing from it.", file.path))?.len();
        let sha = packs::hash_file(&copy).map_err(|_| format!("{} is missing from it.", file.path))?;
        if size != file.size || file.sha256.as_deref() != Some(sha.as_str()) {
            return Err(format!("{} in it has changed since the backup was made.", file.path));
        }
    }
    Ok(())
}

fn info(dir: &Path, id: &str) -> BackupInfo {
    match read_manifest(dir) {
        Ok(manifest) => {
            let problem = check(dir, &manifest).err();
            BackupInfo {
                id: id.to_owned(),
                created_at: manifest.created,
                reason: manifest.reason,
                files: manifest.files.into_iter().map(|f| BackupFile { path: f.path, existed: f.existed, size: f.size }).collect(),
                valid: problem.is_none(),
                problem,
                order: manifest.created_nanos,
            }
        }
        Err(problem) => BackupInfo { id: id.to_owned(), created_at: 0, reason: "Unknown".into(), files: Vec::new(), valid: false, problem: Some(problem), order: 0 },
    }
}

/// Every Auto Sync backup of a profile, newest first, each checked.
pub fn list(profile_dir: &Path) -> Vec<BackupInfo> {
    let Ok(entries) = std::fs::read_dir(root(profile_dir)) else { return Vec::new() };
    let mut out: Vec<BackupInfo> = entries
        .flatten()
        .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
        .filter_map(|e| e.file_name().into_string().ok())
        .filter(|name| valid_id(name))
        .map(|id| info(&root(profile_dir).join(&id), &id))
        .collect();
    out.sort_by(|a, b| (b.created_at, b.order, &b.id).cmp(&(a.created_at, a.order, &a.id)));
    out
}

/// Puts one file back as the backup holds it (atomically), or removes it when it didn't exist before:
/// for undoing a write that just failed. The caller only passes files this operation wrote.
pub fn put_back(profile_dir: &Path, id: &str, rel: &str, copier: packs::Copier) -> CmdResult<()> {
    if !valid_id(id) || !allowed_path(rel) {
        return Err("That backup doesn't exist.".into());
    }
    let dir = root(profile_dir).join(id);
    let manifest = read_manifest(&dir).map_err(|p| format!("This backup is damaged: {p}"))?;
    let file = manifest.files.iter().find(|f| f.path == rel).ok_or_else(|| format!("{rel} isn't in this backup."))?;
    let target = profile_dir.join(rel);
    if !file.existed {
        return match std::fs::symlink_metadata(&target) {
            Ok(meta) if meta.is_dir() => std::fs::remove_dir_all(&target).map_err(|e| format!("Couldn't remove {rel}: {e}")),
            Ok(_) => std::fs::remove_file(&target).map_err(|e| format!("Couldn't remove {rel}: {e}")),
            Err(_) => Ok(()),
        };
    }
    let copy = dir.join(FILES).join(rel);
    let sha = packs::hash_file(&copy).map_err(|e| format!("Couldn't read {rel} from the backup: {e}"))?;
    if file.sha256.as_deref() != Some(sha.as_str()) {
        return Err(format!("{rel} in the backup has changed; stopped."));
    }
    let name = target.file_name().and_then(|n| n.to_str()).ok_or("Invalid file name")?;
    let tmp = target.with_file_name(format!(".{name}{TMP_SUFFIX}"));
    let _ = std::fs::remove_file(&tmp);
    let placed = packs::copy_file(&copy, &tmp, copier).and_then(|_| std::fs::rename(&tmp, &target));
    if let Err(e) = placed {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("Couldn't put {rel} back: {e}"));
    }
    sync_dir(target.parent().unwrap_or(profile_dir));
    Ok(())
}

/// Deletes the oldest Auto Sync backups beyond the newest `KEEP`. Only folders that are Auto Sync's own
/// (a backup name and a readable manifest whose reason is Auto Sync's) are ever deleted; anything else in
/// the folder, damaged backups included, is left alone.
pub fn prune(profile_dir: &Path) -> usize {
    let ours: Vec<BackupInfo> = list(profile_dir).into_iter().filter(|b| b.reason.starts_with(REASON_PREFIX) && b.created_at > 0).collect();
    let mut removed = 0;
    for old in ours.iter().skip(KEEP) {
        if std::fs::remove_dir_all(root(profile_dir).join(&old.id)).is_ok() {
            removed += 1;
        }
    }
    removed
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;

    const CLONE: packs::Copier = |a, b| std::fs::copy(a, b);

    fn files() -> Vec<String> {
        vec!["options.txt".into(), "config/flintclient/layouts/pvp.json".into()]
    }

    fn make(dir: &Path, reason: &str) -> String {
        create(dir, &["options.txt".into()], reason, CLONE).unwrap()
    }

    #[test]
    fn a_backup_holds_each_file_with_its_size_and_checksum() {
        let dir = TestDir::new();
        std::fs::write(dir.0.join("options.txt"), b"version:5023\nfov:0.0\n").unwrap();
        let id = create(&dir.0, &files(), "Auto Sync", CLONE).unwrap();
        let backup = dir.0.join(DIR).join(&id);
        assert_eq!(std::fs::read(backup.join("files/options.txt")).unwrap(), b"version:5023\nfov:0.0\n");
        let manifest: serde_json::Value = serde_json::from_slice(&std::fs::read(backup.join(MANIFEST)).unwrap()).unwrap();
        assert_eq!(manifest["schema"], 1);
        assert_eq!(manifest["reason"], "Auto Sync");
        assert_eq!(manifest["files"][0], serde_json::json!({ "path": "options.txt", "existed": true, "size": 21, "sha256": sha256(b"version:5023\nfov:0.0\n") }));
        assert_eq!(manifest["files"][1], serde_json::json!({ "path": "config/flintclient/layouts/pvp.json", "existed": false, "size": 0 }));
        let listed = list(&dir.0);
        assert_eq!(listed.len(), 1);
        assert!(listed[0].valid);
        assert!(std::fs::read_dir(dir.0.join(DIR)).unwrap().flatten().all(|e| !e.file_name().to_string_lossy().ends_with(TMP_SUFFIX)));
    }

    #[test]
    fn only_files_auto_sync_manages_are_ever_backed_up_or_put_back() {
        assert!(allowed_path("options.txt"));
        assert!(allowed_path("config/flintclient.json"));
        assert!(allowed_path("config/flintclient/control-center.json"));
        assert!(allowed_path("config/flintclient/layouts/author-pick.json"), "Flint's own files aren't judged by name");
        assert!(allowed_path("config/sodium-options.json"));
        assert!(allowed_path("config/xaero/minimap.txt"));
        assert!(allowed_path("resourcepacks/Faithful 32x.zip"));
        assert!(allowed_path("shaderpacks/Complementary.zip"));
        assert!(!allowed_path("resourcepacks/sub/x.zip"));
        assert!(!allowed_path("resourcepacks/.x.zip"));
        assert!(!allowed_path("config/flintclient/layouts/../../../saves/x.json"));
        assert!(!allowed_path("config/flintclient"));
        assert!(!allowed_path("../options.txt"));
        assert!(!allowed_path("/etc/passwd"));
        assert!(!allowed_path("config/flint-performance.json"));
        assert!(!allowed_path("config/someauth/token.json"));
        assert!(!allowed_path("saves/World/level.dat"));
        assert!(!allowed_path("servers.dat"));
        assert!(!allowed_path("mods/sodium.jar"));
    }

    #[test]
    fn a_backup_of_a_file_flint_doesn_t_manage_is_refused_whole() {
        let dir = TestDir::new();
        std::fs::write(dir.0.join("options.txt"), b"version:5023\n").unwrap();
        let error = create(&dir.0, &["options.txt".into(), "saves/World/level.dat".into()], "Auto Sync", CLONE).unwrap_err();
        assert!(error.contains("saves/World/level.dat") && error.contains("nothing was changed"), "{error}");
        assert!(list(&dir.0).is_empty(), "no partial backup");
    }

    #[test]
    fn a_tampered_or_incomplete_backup_is_reported_damaged_and_never_put_back() {
        let dir = TestDir::new();
        std::fs::write(dir.0.join("options.txt"), b"version:5023\n").unwrap();
        let changed = make(&dir.0, "Auto Sync");
        std::fs::write(dir.0.join(DIR).join(&changed).join("files/options.txt"), b"version:1\n").unwrap();
        let missing = make(&dir.0, "Auto Sync");
        std::fs::remove_file(dir.0.join(DIR).join(&missing).join("files/options.txt")).unwrap();
        std::fs::create_dir_all(dir.0.join(DIR).join("20260101-000000-abcdef")).unwrap();
        let listed = list(&dir.0);
        assert_eq!(listed.len(), 3);
        assert!(listed.iter().all(|b| !b.valid && b.problem.is_some()));
        std::fs::write(dir.0.join("options.txt"), b"version:5023\nfov:1.0\n").unwrap();
        assert!(put_back(&dir.0, &changed, "options.txt", CLONE).unwrap_err().contains("changed"));
        assert!(put_back(&dir.0, "20260101-000000-abcdef", "options.txt", CLONE).is_err());
        assert_eq!(std::fs::read(dir.0.join("options.txt")).unwrap(), b"version:5023\nfov:1.0\n", "nothing put back");
    }

    #[test]
    fn putting_back_restores_files_and_removes_ones_flint_created() {
        let dir = TestDir::new();
        std::fs::write(dir.0.join("options.txt"), b"before").unwrap();
        let id = create(&dir.0, &files(), "Auto Sync", CLONE).unwrap();
        std::fs::write(dir.0.join("options.txt"), b"after").unwrap();
        let layout = dir.0.join("config/flintclient/layouts/pvp.json");
        std::fs::create_dir_all(layout.parent().unwrap()).unwrap();
        std::fs::write(&layout, b"{}").unwrap();
        for rel in files() {
            put_back(&dir.0, &id, &rel, CLONE).unwrap();
        }
        assert_eq!(std::fs::read(dir.0.join("options.txt")).unwrap(), b"before");
        assert!(!layout.exists(), "the layout the sync created is gone again");
        assert!(put_back(&dir.0, &id, "config/flintclient.json", CLONE).unwrap_err().contains("isn't in this backup"));
    }

    #[test]
    fn only_the_ten_newest_auto_sync_backups_are_kept_and_nothing_else_is_touched() {
        let dir = TestDir::new();
        std::fs::write(dir.0.join("options.txt"), b"x").unwrap();
        let ids: Vec<String> = (0..13).map(|_| make(&dir.0, "Auto Sync")).collect();
        // Folders that aren't Auto Sync's own backups: a person's folder, another tool's backup, a damaged one.
        let foreign = dir.0.join(DIR).join("my-notes");
        std::fs::create_dir_all(&foreign).unwrap();
        std::fs::write(foreign.join("keep.txt"), b"mine").unwrap();
        let other = make(&dir.0, "Manual copy");
        let damaged = dir.0.join(DIR).join("20200101-000000-abcdef");
        std::fs::create_dir_all(&damaged).unwrap();
        let settings = dir.0.join(".flint-backup/settings/20200101-000000-abcdef");
        std::fs::create_dir_all(&settings).unwrap();
        assert_eq!(prune(&dir.0), 3);
        let left: Vec<String> = list(&dir.0).into_iter().filter(|b| b.reason.starts_with(REASON_PREFIX)).map(|b| b.id).collect();
        let newest: Vec<String> = ids.iter().rev().take(KEEP).cloned().collect();
        assert_eq!(left, newest);
        for kept in [foreign.join("keep.txt"), dir.0.join(DIR).join(&other), damaged, settings] {
            assert!(kept.exists(), "{} must stay", kept.display());
        }
        assert_eq!(prune(&dir.0), 0, "pruning again removes nothing");
    }

    #[test]
    fn stamps_are_utc_dates() {
        assert_eq!(stamp(0), "19700101-000000");
        assert_eq!(stamp(1_760_520_612), "20251015-093012");
        assert_eq!(stamp(951_782_400), "20000229-000000");
    }

    #[test]
    fn a_backup_that_fails_midway_leaves_nothing_behind() {
        let dir = TestDir::new();
        // A folder where options.txt should be: it can't be backed up as a file.
        std::fs::create_dir_all(dir.0.join("options.txt")).unwrap();
        assert!(create(&dir.0, &["options.txt".into()], "Auto Sync", CLONE).is_err());
        assert!(std::fs::read_dir(dir.0.join(DIR)).unwrap().next().is_none());
    }
}
