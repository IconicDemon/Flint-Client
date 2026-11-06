//! Content changed while the profile's Minecraft runs: which kinds can go in live, how a file is placed so the running game
//! never sees half of it or loses one it has open, and the launcher → game handoff that tells the game (`.flint-packs.json`).
//!
//! Resource packs, shader packs and data packs can be added and removed while the game runs: the game picks them up without
//! a restart (resource packs and shader packs at once, data packs at the next `/reload` or world load). Mods, and anything
//! else, need a restart, so they stay blocked with a message that says so.
//!
//! The handoff is a small file in the profile folder (the game directory), written atomically, that the game looks at once a
//! second with one `stat` and reads only when it changed (game-runtime `control/store/PackHandoff`):
//!
//! ```json
//! { "schema": 1, "revision": 1762380000123,
//!   "changes": [{ "revision": 1762380000123, "kind": "resourcepack", "file": "Dirt Tint.zip", "title": "Dirt Tint",
//!                 "action": "added", "enable": true }] }
//! ```
//!
//! `revision` is the time of the newest change (ms since the epoch, always above the last one), so a game applies each change
//! newer than what it applied before and newer than its own start: a file left from an earlier session is never replayed.
//! The newest few changes stay in the file, so two installs a moment apart both reach a game that polls once a second. The
//! file holds names only (a plain file name in the kind's own folder, as the game checks again), nothing private. Like Flint's
//! other dot files at the top of a profile, it is never exported, synced or duplicated, and it is removed at every launch.

use crate::core::*;
use serde::{Deserialize, Serialize};
use std::path::Path;

pub const FILE: &str = ".flint-packs.json";
/// The layout of the file. A game that doesn't know it ignores the file.
pub const SCHEMA: u32 = 1;
/// The most changes the file keeps (newest last).
const KEEP: usize = 16;
/// The longest title the file carries (the game shows it in a toast).
const MAX_TITLE: usize = 120;
/// Ending of the temporary file a live install writes beside its target before renaming it into place. Neither Minecraft
/// nor Iris lists it: it isn't a `.zip` and its name starts with a dot.
const TMP_SUFFIX: &str = ".flint-tmp";

/// Why `kind` can't be changed while the profile's game runs, or None when it can go in live.
pub fn blocked_while_running(kind: &str) -> Option<&'static str> {
    match kind {
        "resourcepack" | "shader" | "datapack" => None,
        "mod" => Some("Mods need a restart: close Minecraft first."),
        _ => Some("Close Minecraft first. Flint doesn't change this while the game is running."),
    }
}

/// Whether the game Flint started for this profile is running.
pub fn running(state: &AppState, profile_id: &str) -> bool {
    state.running.lock().unwrap().contains_key(profile_id)
}

/// Whether the profile's game reads the handoff: Flint's in-game runtime is in its mods folder (put there at launch for a
/// Fabric profile on a supported version while Settings → "Flint main menu in Minecraft" is on). Without it the game still
/// lists a new pack when its pack screen opens, but nothing turns on by itself.
pub fn game_applies(state: &AppState, profile_id: &str) -> bool {
    state.paths.profile_dir(profile_id).join("mods").join(crate::runtime::FILE_NAME).is_file()
}

/// Whether content of these kinds can change in this profile now: `Ok(true)` while its game runs (the change goes in live),
/// `Ok(false)` while it doesn't (as always), and the reason when the game runs and one of the kinds needs a restart.
pub fn check_kinds<'a>(state: &AppState, profile_id: &str, kinds: impl IntoIterator<Item = &'a str>) -> CmdResult<bool> {
    if !running(state, profile_id) {
        return Ok(false);
    }
    for kind in kinds {
        if let Some(reason) = blocked_while_running(kind) {
            return Err(reason.into());
        }
    }
    Ok(true)
}

/// The handoff's name for a content kind (Flint's "shader" is a shader pack).
fn handoff_kind(kind: &str) -> Option<&'static str> {
    match kind {
        "resourcepack" => Some("resourcepack"),
        "shader" => Some("shaderpack"),
        "datapack" => Some("datapack"),
        _ => None,
    }
}

/// One change, as the game reads it.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Change {
    pub revision: u64,
    /// "resourcepack", "shaderpack" or "datapack".
    pub kind: String,
    /// A plain file name in the kind's folder (for a data pack, in the world's `datapacks`).
    pub file: String,
    /// The name the toast shows.
    pub title: String,
    /// "added" or "removed".
    pub action: String,
    /// For an added resource or shader pack: turn it on in the game now.
    #[serde(default)]
    pub enable: bool,
    /// A data pack's world (its folder in `saves`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub world: Option<String>,
}

#[derive(Serialize, Deserialize, Default, Debug)]
#[serde(rename_all = "camelCase")]
struct Handoff {
    schema: u32,
    revision: u64,
    #[serde(default)]
    changes: Vec<Change>,
}

/// Something to tell the game about.
#[derive(Clone, Debug, PartialEq)]
pub struct Note {
    pub kind: String,
    pub file: String,
    pub title: String,
    pub removed: bool,
    pub enable: bool,
    pub world: Option<String>,
}

impl Note {
    /// A file put into the profile; `enable`: turn it on in the game (resource and shader packs only).
    pub fn added(kind: &str, file: &str, title: &str, world: Option<&str>, enable: bool) -> Self {
        Self { kind: kind.into(), file: file.into(), title: title.into(), removed: false, enable: enable && kind != "datapack", world: world.map(str::to_owned) }
    }

    /// A file taken out of the profile.
    pub fn removed(kind: &str, file: &str, title: &str, world: Option<&str>) -> Self {
        Self { kind: kind.into(), file: file.into(), title: title.into(), removed: true, enable: false, world: world.map(str::to_owned) }
    }
}

/// A title as the game may show it: one line of printable text, at most `MAX_TITLE` characters, without Minecraft's
/// formatting sign. Falls back to the file name without its ending.
fn clean_title(title: &str, file: &str) -> String {
    let text: String = title.chars().filter(|c| !c.is_control() && *c != '\u{a7}').take(MAX_TITLE).collect();
    let text = text.trim();
    if text.is_empty() {
        file.trim_end_matches(".zip").to_owned()
    } else {
        text.to_owned()
    }
}

/// Tells the game in `game_dir` (the profile folder) about `notes`: one new revision for them all, after the changes the file
/// already holds (the newest `KEEP` stay). Written atomically. Returns the revision. Notes of kinds the game doesn't take
/// live, or without a plain file name, are left out.
pub fn tell_game(game_dir: &Path, notes: &[Note]) -> CmdResult<u64> {
    tell_game_at(game_dir, notes, crate::store::now_millis())
}

fn tell_game_at(game_dir: &Path, notes: &[Note], now: u64) -> CmdResult<u64> {
    let path = game_dir.join(FILE);
    // A file this launcher can't read (another layout, damaged, too large) starts over.
    let mut handoff: Handoff = std::fs::metadata(&path)
        .ok()
        .filter(|m| m.is_file() && m.len() <= 64 * 1024)
        .and_then(|_| read_json_opt::<Handoff>(&path))
        .filter(|h| h.schema == SCHEMA)
        .unwrap_or_default();
    let revision = now.max(handoff.revision.saturating_add(1));
    let changes: Vec<Change> = notes
        .iter()
        .filter_map(|n| {
            let kind = handoff_kind(&n.kind)?;
            if !plain(&n.file) || n.world.as_deref().is_some_and(|w| !plain(w)) || (kind == "datapack") != n.world.is_some() {
                return None;
            }
            Some(Change {
                revision,
                kind: kind.into(),
                file: n.file.clone(),
                title: clean_title(&n.title, &n.file),
                action: if n.removed { "removed" } else { "added" }.into(),
                enable: n.enable && !n.removed && kind != "datapack",
                world: n.world.clone(),
            })
        })
        .collect();
    if changes.is_empty() {
        return Ok(handoff.revision);
    }
    handoff.schema = SCHEMA;
    handoff.revision = revision;
    handoff.changes.extend(changes);
    let excess = handoff.changes.len().saturating_sub(KEEP);
    handoff.changes.drain(..excess);
    let bytes = serde_json::to_vec_pretty(&handoff).map_err(|e| e.to_string())?;
    write_atomic(&path, &bytes)?;
    Ok(revision)
}

/// Removes the handoff before a launch: a new game starts with nothing to apply.
pub fn clear(game_dir: &Path) {
    let _ = std::fs::remove_file(game_dir.join(FILE));
}

/// A plain file name, as the game accepts one: no folders, no drive, not hidden, not `..`, no control characters.
fn plain(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 180
        && name == name.trim()
        && !name.starts_with('.')
        && !name.contains(['/', '\\', ':'])
        && !name.chars().any(char::is_control)
}

/// The name a live install gives `wanted` in `dir`: `wanted` itself when nothing of that name (on, or switched off with
/// `.disabled`) is there, else "Name (2).zip", "Name (3).zip"…, so a pack the running game has open or turned on is never
/// replaced.
pub fn free_name(dir: &Path, wanted: &str) -> String {
    let taken = |name: &str| dir.join(name).symlink_metadata().is_ok() || dir.join(format!("{name}.disabled")).symlink_metadata().is_ok();
    if !taken(wanted) {
        return wanted.to_owned();
    }
    let (stem, ext) = match wanted.rsplit_once('.') {
        Some((s, e)) if !s.is_empty() => (s, format!(".{e}")),
        _ => (wanted, String::new()),
    };
    (2u32..)
        .map(|n| format!("{stem} ({n}){ext}"))
        .find(|candidate| !taken(candidate))
        .expect("an unused name exists")
}

/// Moves a verified download into `dir` as `name` (a name `free_name` chose) so the running game never sees part of it:
/// first beside its target as a hidden temporary file (copied and flushed when the download sits on another disk), then
/// renamed to its name in one step. Never replaces a file that is there.
pub fn place(staged: &Path, dir: &Path, name: &str) -> CmdResult<()> {
    std::fs::create_dir_all(dir).map_err(err("Could not create the content folder"))?;
    let target = dir.join(name);
    if target.symlink_metadata().is_ok() {
        return Err(format!("{name} is already in this profile"));
    }
    let tmp = dir.join(format!(".{name}{TMP_SUFFIX}"));
    let _ = std::fs::remove_file(&tmp);
    let moved = std::fs::rename(staged, &tmp).or_else(|_| {
        // Another disk (the folder may be a link to one): copied, then flushed so the rename below publishes a whole file.
        std::fs::copy(staged, &tmp)?;
        std::fs::File::open(&tmp)?.sync_all()?;
        let _ = std::fs::remove_file(staged);
        Ok::<_, std::io::Error>(())
    });
    if let Err(e) = moved {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("Could not move the file into the profile: {e}"));
    }
    if target.symlink_metadata().is_ok() {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("{name} is already in this profile"));
    }
    std::fs::rename(&tmp, &target).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        format!("Could not move the file into the profile: {e}")
    })
}

/// What a failed removal says while the game runs: on Windows a pack Minecraft (or Iris) has open can't be deleted.
pub fn removal_error(kind: &str, error: &std::io::Error) -> String {
    // Windows: ERROR_SHARING_VIOLATION (32) or ERROR_LOCK_VIOLATION (33) while the game has the file open.
    let in_use = error.kind() == std::io::ErrorKind::PermissionDenied || (cfg!(windows) && matches!(error.raw_os_error(), Some(32 | 33)));
    if !in_use {
        return format!("Could not remove it: {error}");
    }
    match kind {
        "shader" => "Minecraft is using this shader pack. Turn shaders off or pick another pack in the game first, then remove it.".into(),
        "datapack" => "Minecraft is using this data pack. Leave the world first, then remove it.".into(),
        _ => "Minecraft is using this pack. Turn it off in the game (Options → Resource Packs) first, then remove it.".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn read(dir: &Path) -> Value {
        serde_json::from_slice(&std::fs::read(dir.join(FILE)).unwrap()).unwrap()
    }

    #[test]
    fn packs_go_in_live_and_mods_need_a_restart() {
        for kind in ["resourcepack", "shader", "datapack"] {
            assert_eq!(blocked_while_running(kind), None, "{kind}");
        }
        assert_eq!(blocked_while_running("mod"), Some("Mods need a restart: close Minecraft first."));
        assert!(blocked_while_running("modpack").is_some());
        assert!(blocked_while_running("").is_some());

        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        // Not running: everything goes ahead as before (the change isn't live).
        assert_eq!(check_kinds(&state, "p1", ["mod", "resourcepack"]), Ok(false));
        state.running.lock().unwrap().insert("p1".into(), RunningGame { kill: None });
        assert_eq!(check_kinds(&state, "p1", ["resourcepack", "shader"]), Ok(true));
        assert_eq!(check_kinds(&state, "p1", ["datapack"]), Ok(true));
        assert_eq!(check_kinds(&state, "p1", ["shader", "mod"]), Err("Mods need a restart: close Minecraft first.".into()));
        assert!(check_kinds(&state, "p1", ["modpack"]).is_err());
        // Only the profile whose own game runs is live; another one is as before.
        assert_eq!(check_kinds(&state, "p2", ["mod"]), Ok(false));

        // The game turns packs on by itself only with Flint's runtime in it (vanilla, or the in-game menu switched off: no).
        assert!(!game_applies(&state, "p1"));
        let mods = state.paths.profile_dir("p1").join("mods");
        std::fs::create_dir_all(&mods).unwrap();
        std::fs::write(mods.join(crate::runtime::FILE_NAME), b"jar").unwrap();
        assert!(game_applies(&state, "p1"));
        assert!(!game_applies(&state, "p2"));
    }

    #[test]
    fn the_handoff_is_written_atomically_with_a_new_revision_for_each_batch() {
        let dir = TestDir::new();
        let notes = [Note::added("resourcepack", "Dirt Tint.zip", "Dirt Tint", None, true), Note::added("shader", "Tint.zip", "Tint", None, false)];
        let first = tell_game_at(&dir.0, &notes, 1_000).unwrap();
        assert_eq!(first, 1_000);
        let file = read(&dir.0);
        assert_eq!(file["schema"], 1);
        assert_eq!(file["revision"], 1_000);
        assert_eq!(file["changes"][0], serde_json::json!({ "revision": 1000, "kind": "resourcepack", "file": "Dirt Tint.zip", "title": "Dirt Tint", "action": "added", "enable": true }));
        assert_eq!(file["changes"][1]["kind"], "shaderpack", "Flint's shader kind is a shader pack to the game");
        assert_eq!(file["changes"][1]["enable"], false);
        // No temporary file is left beside it.
        let names: Vec<String> = std::fs::read_dir(&dir.0).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
        assert_eq!(names, [FILE]);

        // A second batch in the same millisecond (or with the clock behind) still gets a newer revision; earlier changes stay.
        let second = tell_game_at(&dir.0, &[Note::removed("resourcepack", "Old.zip", "Old", None)], 900).unwrap();
        assert_eq!(second, 1_001);
        let file = read(&dir.0);
        assert_eq!(file["revision"], 1_001);
        assert_eq!(file["changes"].as_array().unwrap().len(), 3);
        assert_eq!(file["changes"][2], serde_json::json!({ "revision": 1001, "kind": "resourcepack", "file": "Old.zip", "title": "Old", "action": "removed", "enable": false }));
    }

    #[test]
    fn the_handoff_keeps_only_the_newest_changes_and_starts_over_from_a_damaged_file() {
        let dir = TestDir::new();
        for i in 0..20u64 {
            tell_game_at(&dir.0, &[Note::added("resourcepack", &format!("p{i}.zip"), "", None, false)], 10 + i).unwrap();
        }
        let file = read(&dir.0);
        let changes = file["changes"].as_array().unwrap();
        assert_eq!(changes.len(), KEEP);
        assert_eq!(changes[0]["file"], "p4.zip");
        assert_eq!(changes[KEEP - 1]["file"], "p19.zip");
        assert_eq!(changes[KEEP - 1]["title"], "p19", "no title: the file's name without .zip");

        std::fs::write(dir.0.join(FILE), b"{ not json").unwrap();
        tell_game_at(&dir.0, &[Note::added("resourcepack", "a.zip", "A", None, true)], 5_000).unwrap();
        let file = read(&dir.0);
        assert_eq!(file["changes"].as_array().unwrap().len(), 1);
        assert_eq!(file["revision"], 5_000);
    }

    #[test]
    fn the_handoff_carries_only_plain_names_and_clean_titles() {
        let dir = TestDir::new();
        let notes = [
            Note::added("resourcepack", "../outside.zip", "x", None, true),
            Note::added("resourcepack", "a/b.zip", "x", None, true),
            Note::added("resourcepack", "C:evil.zip", "x", None, true),
            Note::added("resourcepack", ".hidden.zip", "x", None, true),
            Note::added("mod", "sodium.jar", "Sodium", None, true),
            Note::added("datapack", "dp.zip", "No world", None, false),
            Note::added("datapack", "dp.zip", "Bad world", Some("../w"), false),
            Note::added("resourcepack", "ok.zip", "\u{a7}cRed\nTitle", None, true),
            Note::added("datapack", "dp.zip", "Trees", Some("New World"), true),
        ];
        assert_eq!(tell_game_at(&dir.0, &notes, 7).unwrap(), 7);
        let file = read(&dir.0);
        let changes = file["changes"].as_array().unwrap();
        assert_eq!(changes.len(), 2, "{changes:?}");
        assert_eq!(changes[0]["file"], "ok.zip");
        assert_eq!(changes[0]["title"], "cRedTitle");
        assert_eq!(changes[1], serde_json::json!({ "revision": 7, "kind": "datapack", "file": "dp.zip", "title": "Trees", "action": "added", "enable": false, "world": "New World" }));

        // Nothing usable: the file isn't touched.
        let other = TestDir::new();
        tell_game_at(&other.0, &[Note::added("mod", "a.jar", "A", None, true)], 1).unwrap();
        assert!(!other.0.join(FILE).exists());
        clear(&dir.0);
        assert!(!dir.0.join(FILE).exists());
    }

    #[test]
    fn a_live_install_never_replaces_a_file_that_is_there() {
        let dir = TestDir::new();
        let packs = dir.0.join("resourcepacks");
        std::fs::create_dir_all(&packs).unwrap();
        assert_eq!(free_name(&packs, "Dirt Tint.zip"), "Dirt Tint.zip");
        // The game has this one turned on (and, on Windows, open).
        std::fs::write(packs.join("Dirt Tint.zip"), b"in use").unwrap();
        assert_eq!(free_name(&packs, "Dirt Tint.zip"), "Dirt Tint (2).zip");
        std::fs::write(packs.join("Dirt Tint (2).zip.disabled"), b"off").unwrap();
        assert_eq!(free_name(&packs, "Dirt Tint.zip"), "Dirt Tint (3).zip");
        std::fs::create_dir_all(packs.join("Folder Pack")).unwrap();
        assert_eq!(free_name(&packs, "Folder Pack"), "Folder Pack (2)");

        let staging = dir.0.join(".flint-staging-x");
        std::fs::create_dir_all(&staging).unwrap();
        std::fs::write(staging.join("Dirt Tint.zip"), b"new").unwrap();
        let name = free_name(&packs, "Dirt Tint.zip");
        place(&staging.join("Dirt Tint.zip"), &packs, &name).unwrap();
        assert_eq!(std::fs::read(packs.join("Dirt Tint.zip")).unwrap(), b"in use", "the pack in use is untouched");
        assert_eq!(std::fs::read(packs.join("Dirt Tint (3).zip")).unwrap(), b"new");
        assert!(!staging.join("Dirt Tint.zip").exists());
        let leftovers: Vec<String> = std::fs::read_dir(&packs).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).filter(|n| n.ends_with(TMP_SUFFIX)).collect();
        assert!(leftovers.is_empty(), "{leftovers:?}");

        // Placing under a name that is taken fails instead of replacing it.
        std::fs::write(staging.join("again.zip"), b"again").unwrap();
        assert!(place(&staging.join("again.zip"), &packs, "Dirt Tint.zip").is_err());
        assert_eq!(std::fs::read(packs.join("Dirt Tint.zip")).unwrap(), b"in use");
        assert!(!packs.join(".Dirt Tint.zip.flint-tmp").exists());
    }

    #[test]
    fn a_pack_the_game_holds_open_gets_a_plain_answer() {
        let denied = std::io::Error::from(std::io::ErrorKind::PermissionDenied);
        assert!(removal_error("resourcepack", &denied).contains("Turn it off in the game"));
        assert!(removal_error("shader", &denied).contains("shader pack"));
        assert!(removal_error("datapack", &denied).contains("Leave the world"));
        if cfg!(windows) {
            assert!(removal_error("resourcepack", &std::io::Error::from_raw_os_error(32)).contains("Turn it off in the game"), "a sharing violation");
        }
        assert!(removal_error("resourcepack", &std::io::Error::from(std::io::ErrorKind::NotFound)).starts_with("Could not remove it"));
    }
}
