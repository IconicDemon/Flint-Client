//! Auto Sync's own data: the store in `<data>/sync/` (never inside a profile) and each profile's last-seen
//! record in `profiles/<id>/.flint-sync.json`.
//!
//! - `state.json` holds every synced entry ("<category>/<key>") with the value as its origin profile had
//!   it, a revision number, the origin and when it changed, plus the notices for the Settings page. It is
//!   written last in every operation (and atomically), so an interrupted run leaves the old state or the new
//!   one; content it points at (pack files in `library/`, mod settings in `mods/`) is content-addressed and
//!   written before it.
//! - `.flint-sync.json` holds, per entry, what Flint last read in that profile or wrote there ("last-seen"),
//!   the revision the profile has caught up to, and the entries waiting until they fit there. A profile
//!   changed an entry when its current value differs from last-seen; timestamps are never trusted.
//! - `generation` ties the two together: a profile's record names the store generation it was written
//!   for, so a record from a run whose `state.json` never landed (a crash in between) is recognised and the
//!   profile is re-read as it is instead of trusted.

use crate::core::{now_secs, write_atomic};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

pub const STORE_DIR: &str = "sync";
const STATE_FILE: &str = "state.json";
pub const PROFILE_FILE: &str = ".flint-sync.json";
const SCHEMA: u32 = 1;
/// Notices kept for the Settings page at most (the newest first).
const MAX_NOTICES: usize = 5;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    /// The origin's value: options.txt text, a Flint JSON document, a pack's file name (empty once the
    /// origin deleted it), a selection list, or a mod settings file's SHA-256 (its bytes are in `mods/`).
    pub value: String,
    pub rev: u64,
    pub origin_profile: String,
    /// The origin's verified Minecraft family ("26.3"), when it has one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub origin_family: Option<String>,
    pub updated_at: u64,
    /// The value's comparison form, as a profile's last-seen records it.
    pub fp: String,
    /// For mod settings: the version of the mod that wrote them.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mod_version: Option<String>,
    /// The entry first appeared with this value (the store had none, and neither had its origin before):
    /// a profile that already has its own value for it keeps that until someone genuinely changes it.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub introduced: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Notice {
    pub id: String,
    pub text: String,
    pub at: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct State {
    pub schema: u32,
    /// Mirrors Settings → Auto Sync.
    pub enabled: bool,
    pub next_revision: u64,
    #[serde(default)]
    pub generation: u64,
    #[serde(default)]
    pub entries: BTreeMap<String, Entry>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seeded_from: Option<String>,
    #[serde(default)]
    pub notices: Vec<Notice>,
    /// Identifies this store; with `epoch` it tells a profile's record from an older store or an earlier
    /// time Auto Sync was on.
    #[serde(default)]
    pub store_id: String,
    /// Raised each time Auto Sync is turned back on: every profile is then taken as it is again.
    #[serde(default)]
    pub epoch: u64,
    /// The revision profiles start from when they are taken as they are (the seed's, or the newest when
    /// Auto Sync was turned back on).
    #[serde(default)]
    pub baseline_rev: u64,
    /// Profiles that existed when Auto Sync was turned on: met for the first time, they are taken as they
    /// are (nothing overwritten). Profiles created later get the whole store at their first launch.
    #[serde(default)]
    pub baseline_ids: BTreeSet<String>,
}

impl State {
    pub fn new() -> Self {
        Self {
            schema: SCHEMA,
            enabled: true,
            next_revision: 1,
            generation: 0,
            entries: BTreeMap::new(),
            seeded_from: None,
            notices: Vec::new(),
            store_id: crate::core::random_hex(6),
            epoch: 1,
            baseline_rev: 0,
            baseline_ids: BTreeSet::new(),
        }
    }

    /// The tag a profile's record carries while it is in step with this store and epoch.
    pub fn tag(&self) -> String {
        format!("{}:{}", self.store_id, self.epoch)
    }

    /// The newest revision in the store (0 when empty).
    pub fn head(&self) -> u64 {
        self.next_revision.saturating_sub(1)
    }

    /// A new revision number; numbers only ever grow, so the last change of an entry wins.
    pub fn next_rev(&mut self) -> u64 {
        let rev = self.next_revision;
        self.next_revision += 1;
        rev
    }

    /// Adds a notice unless the same one is already there; keeps the newest few.
    pub fn notice(&mut self, id: &str, text: &str) {
        if self.notices.iter().any(|n| n.id == id) {
            return;
        }
        self.notices.insert(0, Notice { id: id.to_owned(), text: text.to_owned(), at: now_secs() });
        self.notices.truncate(MAX_NOTICES);
    }

    pub fn dismiss(&mut self, id: &str) -> bool {
        let before = self.notices.len();
        self.notices.retain(|n| n.id != id);
        before != self.notices.len()
    }
}

/// What the store's state file holds right now.
pub enum Loaded {
    Missing,
    Ok(State),
    /// Unreadable, or written by a newer Flint: kept aside, never overwritten in place.
    Damaged,
}

pub fn root(data_root: &Path) -> PathBuf {
    data_root.join(STORE_DIR)
}

pub fn load(data_root: &Path) -> Loaded {
    let path = root(data_root).join(STATE_FILE);
    match std::fs::read(&path) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Loaded::Missing,
        Err(_) => Loaded::Damaged,
        Ok(bytes) => match serde_json::from_slice::<State>(&bytes) {
            Ok(state) if state.schema <= SCHEMA => Loaded::Ok(state),
            _ => Loaded::Damaged,
        },
    }
}

/// Moves a damaged state file aside (`state.damaged-<time>.json`) so a new store can start; nothing is
/// deleted.
pub fn set_aside(data_root: &Path) {
    let dir = root(data_root);
    let target = dir.join(format!("state.damaged-{}.json", now_secs()));
    let _ = std::fs::rename(dir.join(STATE_FILE), target);
}

/// Writes the state atomically with a new generation number.
pub fn save(data_root: &Path, state: &mut State, generation: u64) -> Result<(), String> {
    state.generation = generation;
    let bytes = serde_json::to_vec_pretty(state).map_err(|e| e.to_string())?;
    write_atomic(&root(data_root).join(STATE_FILE), &bytes)
}

/// A pack's size and modification time when it was last hashed, so unchanged packs aren't read again.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CachedPack {
    pub size: u64,
    pub mtime: i128,
    pub sha: String,
    #[serde(default)]
    pub dir: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProfileSync {
    pub schema: u32,
    /// The newest store revision this profile has caught up with.
    pub applied_rev: u64,
    /// The store generation this record was written for (see the module notes).
    #[serde(default)]
    pub generation: u64,
    /// `State::tag` of the store and epoch this record belongs to.
    #[serde(default)]
    pub store: String,
    /// Set when the profile couldn't be read safely (its game was running) when Auto Sync was turned on:
    /// its last-seen values are taken as they are at its next launch or exit, and nothing is captured.
    #[serde(default)]
    pub pending_baseline: bool,
    /// Last-seen comparison form of every synced entry, by entry key.
    #[serde(default)]
    pub seen: BTreeMap<String, String>,
    /// Entries that didn't fit this profile yet (a key its version lacks, a mod it doesn't have…); tried
    /// again at each launch.
    #[serde(default)]
    pub deferred: BTreeSet<String>,
    /// Hash cache for packs, by "<folder>/<name>".
    #[serde(default)]
    pub packs: BTreeMap<String, CachedPack>,
}

impl ProfileSync {
    pub fn new(applied_rev: u64) -> Self {
        Self { schema: SCHEMA, applied_rev, ..Default::default() }
    }

    /// Last-seen values of `prefix` entries.
    pub fn seen_with<'a>(&'a self, prefix: &'a str) -> impl Iterator<Item = (&'a String, &'a String)> + 'a {
        self.seen.range(prefix.to_owned()..).take_while(move |(k, _)| k.starts_with(prefix))
    }
}

/// A profile's record: None when it has none yet (a profile Auto Sync hasn't met); a damaged one reads as
/// needing a fresh look.
pub fn load_profile(profile_dir: &Path) -> Option<ProfileSync> {
    let bytes = match std::fs::read(profile_dir.join(PROFILE_FILE)) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return None,
        Err(_) => return Some(ProfileSync { pending_baseline: true, ..ProfileSync::new(0) }),
        Ok(bytes) => bytes,
    };
    match serde_json::from_slice::<ProfileSync>(&bytes) {
        Ok(sync) if sync.schema <= SCHEMA => Some(sync),
        _ => Some(ProfileSync { pending_baseline: true, ..ProfileSync::new(0) }),
    }
}

pub fn save_profile(profile_dir: &Path, sync: &mut ProfileSync, generation: u64) -> Result<(), String> {
    sync.schema = SCHEMA;
    sync.generation = generation;
    let bytes = serde_json::to_vec_pretty(sync).map_err(|e| e.to_string())?;
    write_atomic(&profile_dir.join(PROFILE_FILE), &bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;

    #[test]
    fn the_state_round_trips_and_notices_are_deduplicated() {
        let dir = TestDir::new();
        assert!(matches!(load(&dir.0), Loaded::Missing));
        let mut state = State::new();
        assert_eq!((state.next_rev(), state.next_rev(), state.head()), (1, 2, 2));
        state.notice("a", "First");
        state.notice("a", "First again");
        state.notice("b", "Second");
        assert_eq!(state.notices.iter().map(|n| n.text.as_str()).collect::<Vec<_>>(), ["Second", "First"]);
        save(&dir.0, &mut state, 7).unwrap();
        let Loaded::Ok(back) = load(&dir.0) else { panic!("saved state reads back") };
        assert_eq!(back, state);
        assert_eq!(back.generation, 7);
        assert!(state.dismiss("a") && !state.dismiss("a"));
    }

    #[test]
    fn a_damaged_or_newer_state_is_set_aside_not_overwritten() {
        let dir = TestDir::new();
        std::fs::create_dir_all(root(&dir.0)).unwrap();
        std::fs::write(root(&dir.0).join(STATE_FILE), b"{ not json").unwrap();
        assert!(matches!(load(&dir.0), Loaded::Damaged));
        set_aside(&dir.0);
        assert!(matches!(load(&dir.0), Loaded::Missing));
        let kept: Vec<_> = std::fs::read_dir(root(&dir.0)).unwrap().flatten().map(|e| e.file_name().into_string().unwrap()).collect();
        assert!(kept.iter().any(|n| n.starts_with("state.damaged-")), "{kept:?}");
        std::fs::write(root(&dir.0).join(STATE_FILE), br#"{"schema":2,"enabled":true,"nextRevision":1}"#).unwrap();
        assert!(matches!(load(&dir.0), Loaded::Damaged));
    }

    #[test]
    fn a_profile_record_round_trips_and_a_damaged_one_asks_for_a_fresh_look() {
        let dir = TestDir::new();
        assert_eq!(load_profile(&dir.0), None);
        let mut sync = ProfileSync::new(4);
        sync.seen.insert("options/fov".into(), "Int(90)".into());
        save_profile(&dir.0, &mut sync, 3).unwrap();
        assert_eq!(load_profile(&dir.0), Some(sync));
        std::fs::write(dir.0.join(PROFILE_FILE), b"garbage").unwrap();
        assert!(load_profile(&dir.0).unwrap().pending_baseline);
    }
}
