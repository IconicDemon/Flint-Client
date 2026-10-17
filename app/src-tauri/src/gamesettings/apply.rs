//! Writing a set of files into one profile as a single transaction: back up the files that will change,
//! stage every new file beside its target, check that nothing changed since the files were read (their
//! SHA-256), move each into place, read each back, and put everything back from the backup if anything goes
//! wrong. A file is never half-written, and a profile is never left with only some of its files changed
//! after a failure Flint sees.

use super::autosync::packs::{self, Copier};
use super::backup;
use super::options_txt::OptionsDoc;
use crate::core::{sync_dir, TMP_SUFFIX};
use std::path::{Path, PathBuf};

/// What a file becomes.
#[derive(Clone, Debug, PartialEq)]
pub enum Content {
    Bytes(Vec<u8>),
    /// A copy (a clone where possible) of a file, whose SHA-256 must be `sha`.
    File { from: PathBuf, sha: String },
    /// A copy of a folder pack, whose content hash must be `sha`; only ever placed where nothing is.
    Dir { from: PathBuf, sha: String },
}

/// One file of a transaction, by its path relative to the profile folder.
#[derive(Clone, Debug, PartialEq)]
pub struct FileWrite {
    pub rel: String,
    pub content: Content,
    /// The SHA-256 of the file as it was read when the change was worked out; None when it didn't exist.
    pub expect: Option<String>,
    /// For options.txt: each changed key and the exact text it must have once written.
    pub checks: Vec<(String, String)>,
}

impl FileWrite {
    pub fn bytes(rel: impl Into<String>, bytes: Vec<u8>, expect: Option<String>) -> Self {
        Self { rel: rel.into(), content: Content::Bytes(bytes), expect, checks: Vec::new() }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum TxnError {
    /// A file changed after it was read (or appeared, or went away): nothing was written.
    Conflict(String),
    /// Something can't be written at all (a link, a path Flint doesn't manage): nothing was written.
    Refused(String),
    /// Writing failed part-way; what was written was put back (the message says if that failed too).
    Failed(String),
}

/// Test hooks: the copier, a step after staging, and a rename that fails.
pub struct Hooks<'a> {
    pub copier: Copier,
    pub after_stage: Option<&'a dyn Fn()>,
    /// The index of the rename that fails (for tests).
    pub fail_rename: Option<usize>,
}

impl Default for Hooks<'_> {
    fn default() -> Self {
        Self { copier: |a, b| std::fs::copy(a, b), after_stage: None, fail_rename: None }
    }
}

/// The SHA-256 of what is at `path` now: a file's content hash, a folder pack's tree hash, None if absent.
pub fn current_sha(path: &Path) -> Option<String> {
    let meta = path.symlink_metadata().ok()?;
    if meta.file_type().is_symlink() {
        return Some("link".into());
    }
    if meta.is_dir() {
        return Some(packs::hash_dir(path).unwrap_or_else(|_| "unreadable".into()));
    }
    Some(packs::hash_file(path).unwrap_or_else(|_| "unreadable".into()))
}

fn staging_path(target: &Path) -> PathBuf {
    let name = target.file_name().and_then(|n| n.to_str()).unwrap_or("file");
    target.with_file_name(format!(".{name}{TMP_SUFFIX}"))
}

fn remove_any(path: &Path) {
    match path.symlink_metadata() {
        Ok(meta) if meta.is_dir() => {
            let _ = std::fs::remove_dir_all(path);
        }
        Ok(_) => {
            let _ = std::fs::remove_file(path);
        }
        Err(_) => {}
    }
}

/// Writes `writes` into the profile folder `dir` as one transaction; returns the backup's id. The caller
/// holds the profile (its game can't start) and has made sure the game isn't running.
pub fn commit(dir: &Path, writes: &[FileWrite], reason: &str, hooks: &Hooks) -> Result<String, TxnError> {
    if let Some(problem) = writes.iter().find_map(|w| link_problem(dir, &w.rel)) {
        return Err(TxnError::Refused(problem));
    }
    if let Some(w) = writes.iter().find(|w| !backup::allowed_path(&w.rel)) {
        return Err(TxnError::Refused(format!("Flint doesn't change {}.", w.rel)));
    }
    if let Some(w) = writes.iter().find(|w| matches!(w.content, Content::Dir { .. }) && w.expect.is_some()) {
        return Err(TxnError::Refused(format!("{} is already there.", w.rel)));
    }
    let rels: Vec<String> = writes.iter().map(|w| w.rel.clone()).collect();
    let reason = if reason.starts_with(backup::REASON_PREFIX) { reason.to_owned() } else { format!("{}: {reason}", backup::REASON_PREFIX) };
    let reason = format!("{reason} ({} {})", writes.len(), if writes.len() == 1 { "file" } else { "files" });
    let backup_id = backup::create(dir, &rels, &reason, hooks.copier).map_err(TxnError::Refused)?;
    // Stage every file, and check each staged copy is exactly what is meant.
    let mut staged: Vec<(PathBuf, String)> = Vec::new();
    let cleanup = |staged: &[(PathBuf, String)]| staged.iter().for_each(|(tmp, _)| remove_any(tmp));
    for w in writes {
        let target = dir.join(&w.rel);
        let tmp = staging_path(&target);
        remove_any(&tmp);
        let made = (|| -> std::io::Result<String> {
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent)?;
            }
            match &w.content {
                Content::Bytes(bytes) => {
                    use std::io::Write;
                    let mut file = std::fs::File::create(&tmp)?;
                    file.write_all(bytes)?;
                    file.sync_all()?;
                    Ok(backup::sha256(bytes))
                }
                Content::File { from, sha } => {
                    packs::copy_file(from, &tmp, hooks.copier)?;
                    let got = packs::hash_file(&tmp)?;
                    if &got != sha {
                        return Err(std::io::Error::other("the copy doesn't match its source"));
                    }
                    Ok(got)
                }
                Content::Dir { from, sha } => {
                    packs::copy_dir(from, &tmp, hooks.copier)?;
                    let got = packs::hash_dir(&tmp)?;
                    if &got != sha {
                        return Err(std::io::Error::other("the copy doesn't match its source"));
                    }
                    Ok(got)
                }
            }
        })();
        match made {
            Ok(sha) => staged.push((tmp, sha)),
            Err(e) => {
                remove_any(&tmp);
                cleanup(&staged);
                return Err(TxnError::Failed(format!("Couldn't prepare {}: {e}", w.rel)));
            }
        }
    }
    if let Some(step) = hooks.after_stage {
        step();
    }
    // Nothing may have changed since the files were read.
    if let Some(w) = writes.iter().find(|w| current_sha(&dir.join(&w.rel)) != w.expect) {
        cleanup(&staged);
        return Err(TxnError::Conflict(w.rel.clone()));
    }
    let mut placed: Vec<&FileWrite> = Vec::new();
    let undo = |placed: &[&FileWrite], problem: String| -> TxnError {
        let mut failed = Vec::new();
        for w in placed.iter().rev() {
            if let Err(e) = backup::put_back(dir, &backup_id, &w.rel, hooks.copier) {
                failed.push(e);
            }
        }
        if failed.is_empty() {
            TxnError::Failed(format!("{problem} Flint put this profile's files back as they were."))
        } else {
            TxnError::Failed(format!("{problem} Putting the files back also failed ({}); backup {backup_id} holds them.", failed.join("; ")))
        }
    };
    for (i, (w, (tmp, _))) in writes.iter().zip(&staged).enumerate() {
        let target = dir.join(&w.rel);
        let moved = if hooks.fail_rename == Some(i) { Err(std::io::Error::other("simulated failure")) } else { std::fs::rename(tmp, &target) };
        if let Err(e) = moved {
            cleanup(&staged[i..]);
            return Err(undo(&placed, format!("Couldn't write {}: {e}.", w.rel)));
        }
        placed.push(w);
        sync_dir(target.parent().unwrap_or(dir));
    }
    // Read everything back.
    for (w, (_, sha)) in writes.iter().zip(&staged) {
        if let Err(problem) = verify(dir, w, sha) {
            return Err(undo(&placed, problem));
        }
    }
    backup::prune(dir);
    Ok(backup_id)
}

/// A written file must hash to what was staged, and each changed options.txt key must hold its new value.
fn verify(dir: &Path, write: &FileWrite, sha: &str) -> Result<(), String> {
    if current_sha(&dir.join(&write.rel)).as_deref() != Some(sha) {
        return Err(format!("{} didn't read back as written.", write.rel));
    }
    if !write.checks.is_empty() {
        let doc = OptionsDoc::parse(&std::fs::read(dir.join(&write.rel)).map_err(|e| e.to_string())?)?;
        if let Some((key, _)) = write.checks.iter().find(|(key, raw)| doc.get(key) != Some(raw.as_str())) {
            return Err(format!("{key} in {} didn't read back as written.", write.rel));
        }
    }
    Ok(())
}

/// Why Flint won't write `rel` in the profile folder `dir`, if it won't: the file, or a folder on its way,
/// is a link. Writing through it would change another profile's file, or one several profiles share;
/// replacing it would quietly break the link.
pub fn link_problem(dir: &Path, rel: &str) -> Option<String> {
    let target = dir.join(rel);
    let mut path = dir.to_path_buf();
    for part in Path::new(rel).components() {
        path.push(part);
        if std::fs::symlink_metadata(&path).is_ok_and(|m| m.file_type().is_symlink()) {
            return Some(if path == target {
                format!("{rel} in this profile is a link to another file, so Flint won't replace it.")
            } else {
                format!("{} in this profile is a link to another folder, so Flint won't change {rel}.", path.strip_prefix(dir).unwrap_or(&path).display())
            });
        }
    }
    None
}

/// Folders (relative to a profile) where a write leaves a `.<name>.flint-tmp` if Flint stops mid-write;
/// `config` is looked into a few levels deep (mod settings live in sub-folders).
const WRITE_DIRS: &[&str] = &["", "resourcepacks", "shaderpacks"];
const CONFIG_DEPTH: usize = 5;

fn leftover(name: &str) -> bool {
    name.len() > 1 + TMP_SUFFIX.len() && name.starts_with('.') && name.ends_with(TMP_SUFFIX)
}

/// Removes exactly-named leftovers (`.<name>.flint-tmp`, files or folders) directly in `dir`, and in its
/// sub-folders down to `depth` levels (links are never followed).
fn sweep(dir: &Path, depth: usize) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let Ok(kind) = entry.file_type() else { continue };
        if kind.is_symlink() {
            continue;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        if leftover(&name) {
            remove_any(&entry.path());
        } else if kind.is_dir() && depth > 0 {
            sweep(&entry.path(), depth - 1);
        }
    }
}

/// Removes what an interrupted write left behind (a crash or forced quit between staging a file and
/// moving it into place): files or folders named exactly `.<name>.flint-tmp` in each profile's folder,
/// its pack folders and its `config` folder, unfinished backup folders, and the same in Auto Sync's store.
/// Run once at start-up, before anything else.
///
/// Every write Flint makes into a profile creates its backup in `.flint-backup/sync` first, so a profile
/// without that folder has never been written by Auto Sync and is skipped without being looked into.
pub fn clean_interrupted_writes(root: &Path) {
    sweep(&root.join(super::autosync::store::STORE_DIR), 6);
    let Ok(profiles) = std::fs::read_dir(root.join("profiles")) else { return };
    for profile in profiles.flatten() {
        if !profile.file_type().is_ok_and(|t| t.is_dir()) {
            continue;
        }
        let dir = profile.path();
        if !dir.join(backup::DIR).is_dir() {
            continue;
        }
        for sub in WRITE_DIRS {
            sweep(&dir.join(sub), 0);
        }
        sweep(&dir.join("config"), CONFIG_DEPTH);
        sweep(&dir.join(backup::DIR), 0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;

    fn options_write(dir: &Path, text: &str) -> FileWrite {
        let mut write = FileWrite::bytes("options.txt", text.as_bytes().to_vec(), current_sha(&dir.join("options.txt")));
        write.checks = vec![("fov".into(), "0.5".into())];
        write
    }

    fn layout(dir: &Path) -> FileWrite {
        FileWrite::bytes("config/flintclient/layouts/pvp.json", b"{}".to_vec(), current_sha(&dir.join("config/flintclient/layouts/pvp.json")))
    }

    #[test]
    fn a_transaction_is_backed_up_written_and_verified() {
        let dir = TestDir::new();
        std::fs::write(dir.0.join("options.txt"), b"version:5023\nfov:0.0\n").unwrap();
        let id = commit(&dir.0, &[options_write(&dir.0, "version:5023\nfov:0.5\n"), layout(&dir.0)], "Auto Sync", &Hooks::default()).unwrap();
        assert_eq!(std::fs::read(dir.0.join("options.txt")).unwrap(), b"version:5023\nfov:0.5\n");
        assert_eq!(std::fs::read(dir.0.join("config/flintclient/layouts/pvp.json")).unwrap(), b"{}");
        let backups = backup::list(&dir.0);
        assert_eq!(backups[0].id, id);
        assert!(backups[0].reason.starts_with("Auto Sync"));
        assert_eq!(std::fs::read(dir.0.join(backup::DIR).join(&id).join("files/options.txt")).unwrap(), b"version:5023\nfov:0.0\n");
    }

    #[test]
    fn a_failed_second_rename_puts_the_first_file_back_and_leaves_no_partial_file() {
        let dir = TestDir::new();
        std::fs::write(dir.0.join("options.txt"), b"version:5023\nfov:0.0\n").unwrap();
        let writes = [options_write(&dir.0, "version:5023\nfov:0.5\n"), layout(&dir.0)];
        let hooks = Hooks { fail_rename: Some(1), ..Hooks::default() };
        let error = commit(&dir.0, &writes, "Auto Sync", &hooks).unwrap_err();
        assert!(matches!(&error, TxnError::Failed(m) if m.contains("simulated failure") && m.contains("put this profile's files back")), "{error:?}");
        assert_eq!(std::fs::read(dir.0.join("options.txt")).unwrap(), b"version:5023\nfov:0.0\n");
        assert!(!dir.0.join("config/flintclient/layouts/pvp.json").exists());
        assert!(!dir.0.join(".options.txt.flint-tmp").exists() && !dir.0.join("config/flintclient/layouts/.pvp.json.flint-tmp").exists(), "no staged copy is left");
        assert_eq!(backup::list(&dir.0).len(), 1, "the backup stays available");
    }

    #[test]
    fn a_file_changed_after_it_was_read_aborts_everything() {
        let dir = TestDir::new();
        std::fs::write(dir.0.join("options.txt"), b"version:5023\nfov:0.0\n").unwrap();
        let writes = [layout(&dir.0), options_write(&dir.0, "version:5023\nfov:0.5\n")];
        let path = dir.0.join("options.txt");
        let game_saved = || std::fs::write(&path, b"version:5023\nfov:1.0\n").unwrap();
        let hooks = Hooks { after_stage: Some(&game_saved), ..Hooks::default() };
        assert_eq!(commit(&dir.0, &writes, "Auto Sync", &hooks), Err(TxnError::Conflict("options.txt".into())));
        assert_eq!(std::fs::read(&path).unwrap(), b"version:5023\nfov:1.0\n", "what the game saved stays");
        assert!(!dir.0.join("config/flintclient/layouts/pvp.json").exists(), "nothing else was written either");
        assert!(!dir.0.join("config/flintclient/layouts/.pvp.json.flint-tmp").exists());
    }

    #[test]
    fn a_file_that_reads_back_differently_is_put_back() {
        let dir = TestDir::new();
        std::fs::write(dir.0.join("options.txt"), b"version:5023\nfov:0.0\n").unwrap();
        // The new text has a different fov than the check expects, as if the disk lost the change.
        let mut write = options_write(&dir.0, "version:5023\nfov:0.25\n");
        write.checks = vec![("fov".into(), "0.5".into())];
        let error = commit(&dir.0, &[write], "Auto Sync", &Hooks::default()).unwrap_err();
        assert!(matches!(&error, TxnError::Failed(m) if m.contains("didn't read back as written")), "{error:?}");
        assert_eq!(std::fs::read(dir.0.join("options.txt")).unwrap(), b"version:5023\nfov:0.0\n");
    }

    #[test]
    fn packs_are_cloned_into_place_and_checked() {
        let dir = TestDir::new();
        let source = dir.0.join("library/pack.zip");
        std::fs::create_dir_all(source.parent().unwrap()).unwrap();
        std::fs::write(&source, b"zip bytes").unwrap();
        let sha = packs::hash_file(&source).unwrap();
        let profile = dir.0.join("p");
        std::fs::create_dir_all(&profile).unwrap();
        let place = FileWrite { rel: "resourcepacks/Pack.zip".into(), content: Content::File { from: source.clone(), sha: sha.clone() }, expect: None, checks: Vec::new() };
        commit(&profile, std::slice::from_ref(&place), "Auto Sync", &Hooks::default()).unwrap();
        assert_eq!(std::fs::read(profile.join("resourcepacks/Pack.zip")).unwrap(), b"zip bytes");
        // A source that no longer matches its hash is refused before anything moves.
        std::fs::write(&source, b"changed").unwrap();
        let again = FileWrite { rel: "resourcepacks/Other.zip".into(), ..place };
        assert!(matches!(commit(&profile, &[again], "Auto Sync", &Hooks::default()), Err(TxnError::Failed(_))));
        assert!(!profile.join("resourcepacks/Other.zip").exists());
    }

    #[test]
    fn repeated_writes_keep_ten_backups_and_never_touch_other_folders() {
        let dir = TestDir::new();
        let mine = dir.0.join(backup::DIR).join("kept by hand");
        std::fs::create_dir_all(&mine).unwrap();
        std::fs::write(mine.join("note.txt"), b"mine").unwrap();
        let settings = dir.0.join(".flint-backup/settings/20200101-000000-abcdef/files");
        std::fs::create_dir_all(&settings).unwrap();
        for i in 0..14 {
            let write = FileWrite::bytes("options.txt", format!("version:5023\nfov:0.{i}\n").into_bytes(), current_sha(&dir.0.join("options.txt")));
            commit(&dir.0, &[write], "Auto Sync", &Hooks::default()).unwrap();
        }
        let ours: Vec<_> = backup::list(&dir.0).into_iter().filter(|b| b.reason.starts_with(backup::REASON_PREFIX)).collect();
        assert_eq!(ours.len(), backup::KEEP);
        assert_eq!(std::fs::read(dir.0.join(backup::DIR).join(&ours[0].id).join("files/options.txt")).unwrap(), b"version:5023\nfov:0.12\n", "the newest is kept");
        assert_eq!(std::fs::read(mine.join("note.txt")).unwrap(), b"mine");
        assert!(settings.exists());
    }

    #[test]
    fn start_up_cleanup_removes_only_interrupted_write_leftovers() {
        let dir = TestDir::new();
        let profile = dir.0.join("profiles/abc");
        let make = |rel: &str| {
            let path = profile.join(rel);
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(&path, b"x").unwrap();
            path
        };
        let gone = [
            make(".options.txt.flint-tmp"),
            make("config/.flintclient.json.flint-tmp"),
            make("config/flintclient/.control-center.json.flint-tmp"),
            make("config/flintclient/layouts/.pvp.json.flint-tmp"),
            make("config/xaero/sub/.minimap.txt.flint-tmp"),
            make("resourcepacks/.Pack.zip.flint-tmp"),
            make("shaderpacks/.Folder.flint-tmp/shaders/a.fsh"),
        ];
        let staging = profile.join(backup::DIR).join(".20261015-101010-abcdef.flint-tmp");
        std::fs::create_dir_all(staging.join("files")).unwrap();
        let store_tmp = dir.0.join("sync/library/resourcepacks/abc/.Pack.zip.flint-tmp");
        std::fs::create_dir_all(store_tmp.parent().unwrap()).unwrap();
        std::fs::write(&store_tmp, b"x").unwrap();
        let kept = [
            make("options.txt"),
            make("notes.flint-tmp"),
            make(".flint-tmp"),
            make(".options.txt.flint-part"),
            make("saves/World/.level.dat.flint-tmp"),
            make("config/flintclient/layouts/pvp.json"),
            make("resourcepacks/Pack.zip"),
            make(".flint-backup/sync/20261015-101010-abcdef/files/options.txt"),
        ];
        // A profile Auto Sync never wrote (no backups) isn't looked into, whatever it holds.
        let untouched = dir.0.join("profiles/never-written");
        std::fs::create_dir_all(untouched.join("config")).unwrap();
        std::fs::write(untouched.join(".options.txt.flint-tmp"), b"x").unwrap();
        std::fs::write(untouched.join("config/.flintclient.json.flint-tmp"), b"x").unwrap();
        clean_interrupted_writes(&dir.0);
        for path in gone {
            assert!(!path.exists(), "{} should be removed", path.display());
        }
        assert!(!profile.join("shaderpacks/.Folder.flint-tmp").exists());
        assert!(!staging.exists());
        assert!(!store_tmp.exists());
        for path in kept {
            assert!(path.exists(), "{} must stay", path.display());
        }
        assert!(untouched.join(".options.txt.flint-tmp").exists());
        assert!(untouched.join("config/.flintclient.json.flint-tmp").exists());
    }

    #[cfg(unix)]
    #[test]
    fn a_linked_file_or_folder_is_never_written_through_or_replaced() {
        let dir = TestDir::new();
        let profile = dir.0.join("p");
        let shared = dir.0.join("shared");
        std::fs::create_dir_all(&profile).unwrap();
        std::fs::create_dir_all(shared.join("flintclient")).unwrap();
        std::fs::write(shared.join("options.txt"), b"version:5023\nfov:0.0\n").unwrap();
        std::os::unix::fs::symlink(shared.join("options.txt"), profile.join("options.txt")).unwrap();
        std::os::unix::fs::symlink(&shared, profile.join("config")).unwrap();
        assert!(link_problem(&profile, "options.txt").unwrap().contains("link to another file"));
        assert!(link_problem(&profile, "config/flintclient.json").unwrap().contains("config in this profile is a link to another folder"));
        assert_eq!(link_problem(&profile, "config/flintclient/layouts/pvp.json").map(|p| p.starts_with("config ")), Some(true));
        assert_eq!(link_problem(&dir.0.join("shared"), "options.txt"), None, "a plain file is fine");
        let write = FileWrite::bytes("options.txt", b"version:5023\nfov:0.5\n".to_vec(), current_sha(&profile.join("options.txt")));
        assert!(matches!(commit(&profile, &[write], "Auto Sync", &Hooks::default()), Err(TxnError::Refused(_))));
        assert_eq!(std::fs::read(shared.join("options.txt")).unwrap(), b"version:5023\nfov:0.0\n", "the shared file is untouched");
        assert!(std::fs::symlink_metadata(profile.join("options.txt")).unwrap().file_type().is_symlink(), "the link is still a link");
        assert!(backup::list(&profile).is_empty(), "refused before any backup");
    }

    #[test]
    fn a_file_outside_what_auto_sync_manages_is_never_written() {
        let dir = TestDir::new();
        std::fs::write(dir.0.join("options.txt"), b"version:5023\nfov:0.0\n").unwrap();
        for rel in ["saves/World/level.dat", "servers.dat", "../escape.txt", "resourcepacks/../../x.zip", "config/flint-performance.json"] {
            let stray = FileWrite::bytes(rel, b"x".to_vec(), None);
            let result = commit(&dir.0, &[options_write(&dir.0, "version:5023\nfov:0.5\n"), stray], "Auto Sync", &Hooks::default());
            assert!(matches!(&result, Err(TxnError::Refused(m)) if m.contains(rel)), "{rel}: {result:?}");
        }
        assert_eq!(std::fs::read(dir.0.join("options.txt")).unwrap(), b"version:5023\nfov:0.0\n");
        assert!(!dir.0.join("saves").exists() && !dir.0.join("servers.dat").exists());
        assert!(backup::list(&dir.0).is_empty());
    }
}
