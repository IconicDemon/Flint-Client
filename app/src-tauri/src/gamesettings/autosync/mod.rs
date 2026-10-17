//! Auto Sync: one switch (Settings → General) that keeps Flint profiles' compatible game settings, resource
//! packs, shaders and mod settings consistent, automatically and safely.
//!
//! It is event-driven, with no polling, watchers or background service:
//! - `before_launch` (while the profile is held, before the game starts) first records what the player
//!   changed in that profile since Flint last looked, then brings in what changed elsewhere, if it fits that
//!   profile's Minecraft version and mods. It never stops a launch: anything that goes wrong is logged and
//!   the game starts with the profile's own files.
//! - `after_exit` records what the player changed in the game that just ended (after a crash, only files
//!   that read back whole). Other profiles pick the changes up at their own next launch, so a running game
//!   is never written to.
//! - `set_enabled` turns it on (taking every existing profile as it is) or off (nothing removed).
//!
//! Source of truth: the store keeps each entry's newest value with a revision number. A profile changed an
//! entry when its current value differs from its last-seen value (`store::ProfileSync`), never by file
//! times; changes become new revisions, and the last change of an entry wins. Writes into a profile are one
//! transaction (`apply::commit`): backup, stage, fingerprint check, move into place, read back, roll back.
//!
//! Turning Auto Sync on seeds the store from the most recently played eligible profile; every profile that
//! exists then is taken as it is at its first launch or exit (last-seen = its own values), so nothing is
//! overwritten until a genuine change happens. Profiles created later get the whole store at their first
//! launch. Turning it off stops both hooks and keeps everything; turning it back on takes every profile as
//! it is again.

pub(super) mod jsonedit;
pub(super) mod mods;
pub(super) mod packs;
pub(super) mod props;
pub(super) mod store;
#[cfg(test)]
mod tests;

use super::apply::{self, Content, FileWrite, Hooks, TxnError};
use super::flintfiles::{self, FlintDocs, JsonFile, JsonFileOrMissing, Schema};
use super::options_txt::OptionsDoc;
use super::plan::{self, OptionsFile, ProfileDocs};
use super::registry::{self, check_key, Bind, KeyCheck, Repr, Setting, SyncCategory, KEY_PREFIX};
use super::tables::{FamilyData, FAMILIES};
use super::versions::{self, SupportLevel, VersionSupport};
use crate::core::{now_secs, profile_running, started_elsewhere, AppState, CmdResult, ProcessScan};
use crate::data::{blocking, Profile, ProfilesFile};
use packs::{Fit, Folder, Library, Pack};
use props::PropsDoc;
use serde::Serialize;
use serde_json::{json, Value as Json};
use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use store::{Entry, Loaded, ProfileSync, State};
use tauri::AppHandle;

/// One Auto Sync operation at a time in this Flint (captures, applies, turning it on or off): revisions
/// are handed out in a fixed order and no two operations interleave.
static SYNC: Mutex<()> = Mutex::new(());

const MAX_OPTIONS: u64 = 4 * 1024 * 1024;
const OPTIONS: &str = "options/";
const FLINT: &str = "flint/";
const FLINT_LAYOUT: &str = "flint/layout/";
const FLINT_CC: &str = "flint/cc/";
const FLINT_SHORTCUTS: &str = "flint/shortcuts";
const MODS: &str = "mods/";
const SELECTION: &str = "selection";
const RESOURCE_PACKS_KEY: &str = "resourcePacks";
const INCOMPATIBLE_KEY: &str = "incompatibleResourcePacks";
/// Control Center sections synced whole, key by key.
const CC_SECTIONS: &[&str] = &["sounds", "motion", "accessibility", "hud"];
const ALL_MINECRAFT: [SyncCategory; 8] = [
    SyncCategory::Controls,
    SyncCategory::Keybinds,
    SyncCategory::Video,
    SyncCategory::Audio,
    SyncCategory::Accessibility,
    SyncCategory::Chat,
    SyncCategory::Skin,
    SyncCategory::Language,
];

/// Settings → General → "Auto Sync" (on unless turned off).
pub fn setting_enabled(state: &AppState) -> bool {
    crate::data::load_settings(state)["autoSync"].as_bool().unwrap_or(true)
}

fn log(message: &str) {
    eprintln!("[flint] auto sync: {message}");
}

/// `FLINT_DEV_SYNC=1` adds a line per entry (entry keys and reasons; never values or paths).
fn detail(message: impl FnOnce() -> String) {
    if std::env::var("FLINT_DEV_SYNC").is_ok_and(|v| v == "1") {
        log(&message());
    }
}

fn category(key: &str) -> &str {
    key.split('/').next().unwrap_or(key)
}

/// "options 3, packs 1".
fn counts<'a>(keys: impl IntoIterator<Item = &'a String>) -> String {
    let mut by: BTreeMap<&str, usize> = BTreeMap::new();
    for key in keys {
        *by.entry(category(key)).or_default() += 1;
    }
    by.iter().map(|(c, n)| format!("{c} {n}")).collect::<Vec<_>>().join(", ")
}

fn sha(bytes: &[u8]) -> String {
    super::backup::sha256(bytes)
}

/// What one operation did, for the log and for tests.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Report {
    /// Why nothing was done, when nothing was.
    pub skipped: Option<&'static str>,
    pub captured: Vec<String>,
    pub applied: Vec<String>,
    pub deferred: BTreeMap<String, String>,
    pub backup: Option<String>,
    pub notices: Vec<String>,
}

impl Report {
    fn skip(reason: &'static str) -> Self {
        Self { skipped: Some(reason), ..Default::default() }
    }
}

/// How a game ended.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Exit {
    Clean,
    /// A crash, a forced stop, or an exit Flint couldn't read: only files that read back whole count.
    Unclean,
}

// ---------- a profile as Auto Sync sees it ----------

struct View {
    id: String,
    name: String,
    dir: PathBuf,
    game_version: String,
    support: VersionSupport,
    family: Option<&'static FamilyData>,
    resource_format: Option<(u32, u32)>,
    mods: BTreeMap<String, String>,
    /// Flint's in-game runtime is there (or has run there), so Flint's own files and keys are used.
    runtime: bool,
}

impl View {
    fn load(state: &AppState, profile: &Profile) -> View {
        let dir = state.paths.profile_dir(&profile.id);
        let support = versions::resolve(&state.paths.game(), &profile.game_version);
        View {
            family: support.family(),
            resource_format: versions::resource_format(&state.paths.game(), &profile.game_version),
            mods: mods::installed(&dir),
            runtime: dir.join("mods").join(crate::runtime::FILE_NAME).is_file() || dir.join("config/flintclient").is_dir(),
            id: profile.id.clone(),
            name: profile.name.clone(),
            game_version: profile.game_version.clone(),
            support,
            dir,
        }
    }

    fn eligible(&self) -> bool {
        self.family.is_some()
    }

    fn has_iris(&self) -> bool {
        self.mods.contains_key("iris")
    }
}

/// One synced value as found in a profile.
#[derive(Clone, Debug)]
struct Item {
    value: String,
    fp: String,
    family: Option<String>,
    mod_version: Option<String>,
    content: Option<ItemContent>,
}

#[derive(Clone, Debug)]
enum ItemContent {
    Pack(Folder, Pack),
    Blob(Vec<u8>),
}

impl Item {
    fn plain(value: String, fp: String) -> Self {
        Self { value, fp, family: None, mod_version: None, content: None }
    }
}

/// Everything synced in one profile right now.
struct Snap {
    items: BTreeMap<String, Item>,
    options: OptionsFile,
    options_sha: Option<String>,
    /// options.txt exists but can't be trusted (damaged, no version line, cut short after a crash).
    options_problem: bool,
    packs: BTreeMap<Folder, Vec<Pack>>,
    too_large: Vec<Folder>,
    /// iris.properties when Iris is installed: its text and SHA-256.
    iris: Option<(PropsDoc, String)>,
}

fn read_capped(path: &Path, limit: u64) -> Option<Vec<u8>> {
    let meta = path.symlink_metadata().ok()?;
    if !meta.is_file() || meta.len() > limit {
        return None;
    }
    std::fs::read(path).ok()
}

/// The comparison form of an options.txt value: its meaning (so `0.5` and `0.50` are the same), or the
/// text itself for keys Flint doesn't know.
fn option_fp(setting: Option<&Setting>, raw: &str) -> String {
    match setting.and_then(|s| s.decode(raw)) {
        Some(value) => format!("{value:?}"),
        None => format!("raw:{raw}"),
    }
}

/// The registry's setting for an options.txt key (any verified family's), or a mod's key binding.
fn setting_for(key: &str, catalogue: &[Setting]) -> Option<Setting> {
    if let Some(setting) = catalogue.iter().find(|s| s.key == key) {
        return Some(setting.clone());
    }
    let mapping = key.strip_prefix(KEY_PREFIX)?;
    Some(Setting::bind(mapping, Bind::Mod, None))
}

fn named_somewhere(name: &str) -> bool {
    FAMILIES.iter().any(|f| check_key(f, name) == KeyCheck::Named)
}

/// The portable options.txt values of a profile: every registry setting its version has (not the ones
/// Flint only shows), Flint's keys, and other mods' key bindings, each only when Minecraft would read it.
/// Machine- and session-specific keys aren't in the registry, so they are never taken.
fn option_items(doc: &OptionsDoc, family: Option<&'static FamilyData>, out: &mut BTreeMap<String, Item>) {
    let catalogue = registry::catalogue(family);
    let mut take = |setting: &Setting, raw: &str| {
        let Some(value) = setting.decode(raw) else { return };
        let fits = match (&setting.repr, &value, family) {
            (Repr::Key, registry_value, Some(f)) => matches!(registry_value, super::options_txt::Val::Text(name) if check_key(f, name) != KeyCheck::Invalid),
            // A version Flint hasn't verified: only key names some verified version knows by name.
            (Repr::Key, registry_value, None) => matches!(registry_value, super::options_txt::Val::Text(name) if named_somewhere(name)),
            (_, value, Some(f)) => setting.accepts(f, value),
            (_, value, None) => FAMILIES.iter().any(|f| setting.accepts(f, value)),
        };
        if fits {
            let mut item = Item::plain(raw.to_owned(), format!("{value:?}"));
            item.family = family.map(|f| f.id.to_owned());
            out.insert(format!("{OPTIONS}{}", setting.key), item);
        }
    };
    for setting in &catalogue {
        if setting.read_only.is_some() {
            continue;
        }
        if let Some(f) = family {
            if setting.available_in(f).is_err() {
                continue;
            }
        }
        if let Some(raw) = doc.get(&setting.key) {
            take(setting, raw);
        }
    }
    let mut seen = HashSet::new();
    for key in doc.keys() {
        let Some(mapping) = key.strip_prefix(KEY_PREFIX) else { continue };
        let vanilla = FAMILIES.iter().any(|f| f.key_mappings.iter().any(|(m, _)| *m == mapping));
        let flint = registry::FLINT_BINDS.iter().any(|(m, ..)| *m == mapping);
        if vanilla || flint || !seen.insert(key.to_owned()) {
            continue;
        }
        if let Some(raw) = doc.get(key) {
            take(&Setting::bind(mapping, Bind::Mod, None), raw);
        }
    }
}

/// A Flint JSON value's comparison form.
fn json_fp(value: &Json) -> String {
    sha(value.to_string().as_bytes())
}

fn flint_items(dir: &Path, out: &mut BTreeMap<String, Item>) {
    let (docs, _) = flintfiles::load(dir);
    for (id, file) in &docs.layouts {
        let JsonFile::Ok { root, bytes } = file else { continue };
        if !matches!(flintfiles::schema_of(root), Schema::Known(_)) {
            continue;
        }
        let Ok(text) = String::from_utf8(bytes.clone()) else { continue };
        out.insert(format!("{FLINT_LAYOUT}{id}"), Item::plain(text, json_fp(&Json::Object(root.clone()))));
    }
    if let JsonFile::Ok { root, .. } = &docs.control_center.0 {
        if let Schema::Known(schema) = flintfiles::schema_of(root) {
            let mut add = |name: &str, value: Option<&Json>| {
                if let Some(value) = value.filter(|v| !v.is_null()) {
                    out.insert(format!("{FLINT_CC}{name}"), Item::plain(json!({ "schema": schema, "value": value }).to_string(), json_fp(value)));
                }
            };
            for section in CC_SECTIONS {
                add(section, root.get(*section).filter(|v| v.is_object()));
            }
            add("library.favorites", root.get("library").and_then(|l| l.get("favorites")).filter(|v| v.is_array()));
            add("onboarding.completed", root.get("onboarding").and_then(|o| o.get("completed")).filter(|v| v.is_boolean()));
            add("activeLayout", root.get("activeLayout").filter(|v| v.as_str().is_some_and(flintfiles::valid_layout_id)));
        }
    }
    if let JsonFile::Ok { root, .. } = &docs.shortcuts.0 {
        if let Some(list) = root.get("shortcuts").filter(|l| l.is_array()) {
            out.insert(FLINT_SHORTCUTS.into(), Item::plain(json!({ "shortcuts": list }).to_string(), json_fp(list)));
        }
    }
}

/// The resource packs options.txt selects, as written there (`file/<name>` and Minecraft's own).
fn pack_list(doc: &OptionsDoc, key: &str) -> Option<Vec<String>> {
    match doc.get(key) {
        None => Some(Vec::new()),
        Some(raw) => serde_json::from_str::<Vec<String>>(raw).ok(),
    }
}

/// A selection entry's file name, if it names a file pack ("file/<name>", or a bare name of a pack there).
fn file_name<'a>(entry: &'a str, names: &BTreeMap<String, String>) -> Option<&'a str> {
    match entry.strip_prefix("file/") {
        Some(name) => Some(name),
        None => names.contains_key(entry).then_some(entry),
    }
}

fn snapshot(view: &View, cache: &mut BTreeMap<String, store::CachedPack>, strict: bool) -> Snap {
    let mut items = BTreeMap::new();
    let path = view.dir.join("options.txt");
    let bytes = read_capped(&path, MAX_OPTIONS);
    let exists = path.symlink_metadata().is_ok();
    let (options, options_problem) = match &bytes {
        None if !exists => (OptionsFile::Missing, false),
        None => (OptionsFile::Unreadable("options.txt can't be read.".into()), true),
        Some(bytes) => match OptionsDoc::parse(bytes) {
            Ok(doc) if doc.data_version().is_some() && (!strict || bytes.ends_with(b"\n")) => (OptionsFile::Ok(doc), false),
            Ok(_) => (OptionsFile::Damaged("options.txt has no version line or was cut short.".into()), true),
            Err(reason) => (OptionsFile::Damaged(reason), true),
        },
    };
    if let OptionsFile::Ok(doc) = &options {
        option_items(doc, view.family, &mut items);
    }
    let mut packs = BTreeMap::new();
    let mut too_large = Vec::new();
    for folder in [Folder::Resource, Folder::Shader] {
        let (found, large) = packs::scan(&view.dir, folder, cache);
        if !large.is_empty() {
            too_large.push(folder);
        }
        for pack in &found {
            let key = format!("packs/{}/{}", folder.dir(), pack.sha);
            if items.contains_key(&key) {
                continue;
            }
            let mut item = Item::plain(pack.name.clone(), pack.name.clone());
            item.content = Some(ItemContent::Pack(folder, pack.clone()));
            items.insert(key, item);
        }
        packs.insert(folder, found);
    }
    // The resource pack selection, by content.
    if let OptionsFile::Ok(doc) = &options {
        let names: BTreeMap<String, String> = packs[&Folder::Resource].iter().map(|p| (p.name.clone(), p.sha.clone())).collect();
        if let Some(list) = pack_list(doc, RESOURCE_PACKS_KEY) {
            let hashes: Vec<String> = list.iter().filter_map(|e| file_name(e, &names)).filter_map(|n| names.get(n).cloned()).collect();
            let value = serde_json::to_string(&hashes).unwrap_or_default();
            items.insert(format!("packs/resourcepacks/{SELECTION}"), Item::plain(value.clone(), value));
        }
    }
    // The shader selection, where Iris is installed.
    let mut iris = None;
    if view.has_iris() {
        let path = view.dir.join("config/iris.properties");
        if let Some(bytes) = read_capped(&path, mods::MAX_CONFIG).filter(|b| !strict || b.is_empty() || b.ends_with(b"\n")) {
            if let Ok(doc) = PropsDoc::parse(&bytes) {
                let names: BTreeMap<&str, &str> = packs[&Folder::Shader].iter().map(|p| (p.name.as_str(), p.sha.as_str())).collect();
                let name = doc.get("shaderPack").map(str::to_owned);
                let enabled = doc.get("enableShaders").map(str::to_owned);
                if name.is_some() || enabled.is_some() {
                    let pack = name.as_deref().and_then(|n| names.get(n)).map(|s| s.to_string());
                    let fp = json!({ "pack": pack, "enabled": enabled }).to_string();
                    let value = json!({ "pack": pack, "name": name, "enabled": enabled }).to_string();
                    items.insert(format!("packs/shaderpacks/{SELECTION}"), Item::plain(value, fp));
                }
                iris = Some((doc, sha(&bytes)));
            }
        }
    }
    flint_items(&view.dir, &mut items);
    for (rel, (mod_id, _class)) in mods::files(&view.dir, &view.mods) {
        let Some(bytes) = read_capped(&view.dir.join("config").join(&rel), mods::MAX_CONFIG) else { continue };
        let format = mods::format_of(&rel);
        if !mods::well_formed(format, &bytes) {
            continue;
        }
        // After a crash, only files that show they were written whole.
        if strict && (format == mods::Format::Other || (format == mods::Format::Properties && !bytes.is_empty() && !bytes.ends_with(b"\n"))) {
            continue;
        }
        let mut item = Item::plain(sha(&bytes), mods::fingerprint(format, &bytes));
        item.mod_version = view.mods.get(&mod_id).cloned();
        item.content = Some(ItemContent::Blob(bytes));
        items.insert(format!("{MODS}{mod_id}/{rel}"), item);
    }
    Snap { items, options_sha: bytes.as_deref().map(sha), options, options_problem, packs, too_large, iris }
}

// ---------- the store's content (library and mod settings) ----------

fn blob_path(store_root: &Path, key: &str, value: &str) -> Option<PathBuf> {
    let rest = key.strip_prefix(MODS)?;
    let mod_id = rest.split('/').next()?;
    if mod_id.is_empty() || !mod_id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-' || b == b'.') || mod_id.starts_with('.') {
        return None;
    }
    if value.len() != 64 || !value.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    Some(store_root.join("mods").join(mod_id).join(value))
}

/// Keeps a captured item's content in the store (packs in the library, mod settings in `mods/`).
fn keep_content(store_root: &Path, library: &mut Library, key: &str, item: &Item, copier: packs::Copier, dir: &Path) -> Result<(), String> {
    match &item.content {
        Some(ItemContent::Pack(folder, pack)) => packs::add_to_library(store_root, library, *folder, pack, &dir.join(folder.dir()).join(&pack.name), copier),
        Some(ItemContent::Blob(bytes)) => {
            let path = blob_path(store_root, key, &item.value).ok_or("bad entry")?;
            if path.is_file() && packs::hash_file(&path).ok().as_deref() == Some(item.value.as_str()) {
                return Ok(());
            }
            crate::core::write_atomic(&path, bytes)
        }
        None => Ok(()),
    }
}

/// `mods/<modid>/meta.json`: for each stored file of the mod, its SHA-256, the mod version that wrote it,
/// its format and adapter, and its revision (the store's state stays the authority; this describes it).
fn record_mod_meta(store_root: &Path, key: &str, entry: &Entry) {
    let Some((mod_id, rel)) = key.strip_prefix(MODS).and_then(|r| r.split_once('/')) else { return };
    let Some(blob) = blob_path(store_root, key, &entry.value) else { return };
    let path = blob.with_file_name("meta.json");
    let mut meta: Json = std::fs::read(&path).ok().and_then(|b| serde_json::from_slice(&b).ok()).filter(Json::is_object).unwrap_or_else(|| json!({ "schema": 1, "files": {} }));
    let format = match mods::format_of(rel) {
        mods::Format::Json => "json",
        mods::Format::Properties => "properties",
        mods::Format::Other => "other",
    };
    meta["modId"] = json!(mod_id);
    meta["files"][rel] = json!({ "sha256": entry.value, "modVersion": entry.mod_version, "format": format, "adapter": mods::adapter_for(rel).map(|a| a.mod_id), "rev": entry.rev });
    if let Ok(bytes) = serde_json::to_vec_pretty(&meta) {
        let _ = crate::core::write_atomic(&path, &bytes);
    }
}

fn read_blob(store_root: &Path, key: &str, value: &str) -> Option<Vec<u8>> {
    let bytes = std::fs::read(blob_path(store_root, key, value)?).ok()?;
    (sha(&bytes) == value).then_some(bytes)
}

// ---------- capture ----------

/// The selection a profile changed to, keeping in place the packs of the store's selection this profile
/// can't hold (they don't suit its version, or it never had them), so another profile's choices aren't lost
/// just because this one couldn't show them.
fn merged_selection(stored: Option<&str>, mine: &[String], keep: impl Fn(&str) -> bool) -> Vec<String> {
    let stored: Vec<String> = stored.and_then(|s| serde_json::from_str(s).ok()).unwrap_or_default();
    let mut out = mine.to_vec();
    for (i, hash) in stored.iter().enumerate() {
        if out.contains(hash) || !keep(hash) {
            continue;
        }
        let at = stored[..i].iter().rev().find_map(|prev| out.iter().position(|h| h == prev)).map(|p| p + 1).unwrap_or(0);
        out.insert(at, hash.clone());
    }
    out
}

struct Ctx<'a> {
    state: &'a AppState,
    store_root: PathBuf,
    hooks: &'a Hooks<'a>,
}

/// Records what changed in a profile since Flint last looked: each changed entry becomes a new revision
/// with this profile as its origin. Returns the captured keys.
fn capture(ctx: &Ctx, st: &mut State, library: &mut Library, view: &View, snap: &Snap, sync: &ProfileSync) -> Vec<String> {
    let now = now_secs();
    let mut captured = Vec::new();
    for (key, item) in &snap.items {
        let mut item = item.clone();
        let introduced = match sync.seen.get(key) {
            Some(seen) if *seen == item.fp => continue,
            Some(_) => false,
            // New here: if the store already has a value for it, that one is brought in instead (unless the
            // store only says the pack was deleted elsewhere: then it was added back here).
            None if st.entries.get(key).is_some_and(|e| !(key.starts_with("packs/") && e.value.is_empty())) => continue,
            None => true,
        };
        if key == "packs/resourcepacks/selection" {
            let mine: Vec<String> = serde_json::from_str(&item.value).unwrap_or_default();
            let here: BTreeMap<&str, &Pack> = snap.packs[&Folder::Resource].iter().map(|p| (p.sha.as_str(), p)).collect();
            let merged = merged_selection(st.entries.get(key).map(|e| e.value.as_str()), &mine, |hash| match here.get(hash) {
                // Here but can't be selected in this version.
                Some(_) => packs::fit(library.folder(Folder::Resource).get(hash).and_then(|p| p.formats), view.resource_format) == Fit::Incompatible,
                // Never here (as opposed to deleted here).
                None => !sync.seen.contains_key(&format!("packs/resourcepacks/{hash}")),
            });
            item.value = serde_json::to_string(&merged).unwrap_or_default();
        }
        // Changed here, but to what the store already has (by meaning): nothing new.
        if st.entries.get(key).is_some_and(|entry| entry.fp == item.fp) {
            continue;
        }
        if let Err(e) = keep_content(&ctx.store_root, library, key, &item, ctx.hooks.copier, &view.dir) {
            detail(|| format!("{key}: not stored ({e})"));
            continue;
        }
        let rev = st.next_rev();
        st.entries.insert(
            key.clone(),
            Entry {
                value: item.value.clone(),
                rev,
                origin_profile: view.id.clone(),
                origin_family: item.family.clone(),
                updated_at: now,
                fp: item.fp.clone(),
                mod_version: item.mod_version.clone(),
                introduced,
            },
        );
        record_mod_meta(&ctx.store_root, key, &st.entries[key]);
        detail(|| format!("{key}: captured as revision {rev}"));
        captured.push(key.clone());
    }
    // A pack deleted here leaves the selection elsewhere; its files elsewhere are never deleted.
    for (key, _) in sync.seen_with("packs/") {
        if key.ends_with(SELECTION) || snap.items.contains_key(key) {
            continue;
        }
        if st.entries.get(key).is_some_and(|e| !e.value.is_empty()) {
            let rev = st.next_rev();
            st.entries.insert(key.clone(), Entry { value: String::new(), rev, origin_profile: view.id.clone(), origin_family: None, updated_at: now, fp: String::new(), mod_version: None, introduced: false });
            captured.push(key.clone());
        }
    }
    captured
}

// ---------- apply ----------

/// The writes that bring a profile in line with the store, and what fit or didn't.
#[derive(Default)]
struct Work {
    writes: Vec<FileWrite>,
    applied: BTreeSet<String>,
    deferred: BTreeMap<String, String>,
    /// Packs placed by this run: (folder, name, sha, dir).
    placed: Vec<(Folder, String, String, bool)>,
    backup: Option<String>,
}

fn support_for(family: &'static FamilyData) -> VersionSupport {
    let (version, data) = family.versions[0];
    VersionSupport { level: SupportLevel::Verified, family: Some(family.id), game_version: version.to_owned(), data_version: Some(data), reason: None }
}

/// Brings options.txt entries in with the verified plan rules (`plan::plan_target`): each entry only where
/// the destination's version has the key, knows the key name and accepts the value; the graphics preset
/// rule; mods' keys only where that mod has run. Entries are grouped by the version they came from, since
/// key codes mean different keys in GLFW and SDL3 versions.
fn apply_options(view: &View, options: &OptionsFile, entries: &[(&String, &Entry)], work: &mut Work) -> Option<OptionsDoc> {
    let family = view.family?;
    let mut groups: BTreeMap<&'static str, Vec<(&String, &Entry)>> = BTreeMap::new();
    for (key, entry) in entries {
        // An unverified origin's values are only taken by name (see `option_items`); check them against
        // this version on both sides.
        let origin = entry.origin_family.as_deref().and_then(|id| FAMILIES.iter().find(|f| f.id == id)).unwrap_or(family);
        groups.entry(origin.id).or_default().push((key, entry));
    }
    let mut current = options.clone();
    for (origin, list) in &groups {
        let origin = FAMILIES.iter().find(|f| f.id == *origin).expect("grouped by a known family");
        let mut doc = OptionsDoc::new_file(origin.versions[0].1);
        for (key, entry) in list {
            doc.set(&key[OPTIONS.len()..], &entry.value);
        }
        let source = ProfileDocs {
            id: "auto-sync".into(),
            name: "Auto Sync".into(),
            game_version: origin.versions[0].0.into(),
            support: support_for(origin),
            options: OptionsFile::Ok(doc),
            flint: FlintDocs::default(),
            runtime: true,
        };
        let target = ProfileDocs {
            id: view.id.clone(),
            name: view.name.clone(),
            game_version: view.game_version.clone(),
            support: view.support.clone(),
            options: current.clone(),
            flint: FlintDocs::default(),
            runtime: view.runtime,
        };
        let planned = plan::plan_target(&source, &target, None, &ALL_MINECRAFT, &HashSet::new());
        if let Some(reason) = planned.plan.blocked {
            for (key, _) in entries {
                work.deferred.insert((*key).clone(), reason.clone());
            }
            return None;
        }
        for skip in &planned.plan.skipped {
            detail(|| format!("options: {} waits ({})", skip.setting_id.as_deref().unwrap_or(&skip.label), skip.reason));
        }
        if let Some(write) = planned.writes.iter().find(|w| w.rel == "options.txt") {
            current = OptionsFile::Ok(OptionsDoc::parse(&write.bytes).ok()?);
        }
    }
    let catalogue = registry::catalogue(Some(family));
    let doc = match current {
        OptionsFile::Ok(doc) => doc,
        _ => return None,
    };
    for (key, entry) in entries {
        let option = &key[OPTIONS.len()..];
        let setting = setting_for(option, &catalogue);
        let now = doc.get(option).map(|raw| option_fp(setting.as_ref(), raw));
        if now.as_deref() == Some(entry.fp.as_str()) || option == plan::PRESET_KEY {
            // The graphics preset counts as resolved either way: when it wasn't copied, Minecraft's preset
            // rule made the profile Custom instead.
            work.applied.insert((*key).clone());
        } else {
            work.deferred.insert((*key).clone(), "doesn't fit this profile's version yet".into());
        }
    }
    Some(doc)
}

/// The library's name for a pack, made safe, or a plain name from its hash.
fn pack_target_name(entry_name: &str, sha: &str, dir: bool) -> String {
    if packs::safe_name(entry_name) {
        entry_name.to_owned()
    } else if dir {
        format!("Pack {}", &sha[..8.min(sha.len())])
    } else {
        format!("Pack {}.zip", &sha[..8.min(sha.len())])
    }
}

/// `<stem> (Flint <short hash>)<ext>`, for a pack whose name is taken by different content.
fn variant_name(name: &str, sha: &str, dir: bool) -> String {
    let short: String = sha.strip_prefix('d').filter(|_| dir).unwrap_or(sha).chars().take(8).collect();
    match name.rsplit_once('.') {
        Some((stem, ext)) if !dir && !stem.is_empty() => format!("{stem} (Flint {short}).{ext}"),
        _ => format!("{name} (Flint {short})"),
    }
}

/// Places the store's packs that this profile doesn't have (by content, under any name).
fn apply_pack_files(ctx: &Ctx, st: &State, library: &Library, snap: &Snap, sync: &ProfileSync, entries: &[(&String, &Entry)], work: &mut Work) {
    for folder in [Folder::Resource, Folder::Shader] {
        let prefix = format!("packs/{}/", folder.dir());
        let here = &snap.packs[&folder];
        // By name as the file system compares them: macOS folders usually ignore case, so "Pack.zip" and
        // "pack.zip" are one file there and must count as a collision.
        let fold = |name: &str| name.to_lowercase();
        let mut by_name: BTreeMap<String, String> = here.iter().map(|p| (fold(&p.name), p.sha.clone())).collect();
        // What this profile's packs were when Flint last saw them, by name.
        let seen_names: BTreeMap<String, &str> = sync.seen_with(&prefix).filter(|(k, _)| !k.ends_with(SELECTION)).map(|(k, name)| (fold(name), &k[prefix.len()..])).collect();
        for (key, entry) in entries.iter().filter(|(k, _)| k.starts_with(&prefix) && !k.ends_with(SELECTION)) {
            let hash = &key[prefix.len()..];
            if entry.value.is_empty() || here.iter().any(|p| p.sha == hash) {
                work.applied.insert((*key).clone());
                continue;
            }
            let Some((from, info)) = packs::library_copy(&ctx.store_root, library, folder, hash) else {
                work.deferred.insert((*key).clone(), "not in the library".into());
                continue;
            };
            let mut name = pack_target_name(&entry.value, hash, info.dir);
            let mut expect = None;
            if let Some(theirs) = by_name.get(&fold(&name)).cloned() {
                // The name holds other content. Replace it only when it is the version Auto Sync placed
                // before and its origin has since replaced it (the pack was updated there); otherwise keep
                // it and add this one beside it.
                let updated = !info.dir && seen_names.get(&fold(&name)) == Some(&theirs.as_str()) && st.entries.get(&format!("{prefix}{theirs}")).is_some_and(|e| e.value.is_empty());
                if updated {
                    // Written under the name the file already has here.
                    name = here.iter().find(|p| fold(&p.name) == fold(&name)).map(|p| p.name.clone()).unwrap_or(name);
                    expect = Some(theirs);
                } else {
                    name = variant_name(&name, hash, info.dir);
                    if let Some(existing) = by_name.get(&fold(&name)) {
                        if existing != hash {
                            work.deferred.insert((*key).clone(), "a different pack has that name".into());
                        }
                        continue;
                    }
                }
            }
            let content = if info.dir { Content::Dir { from, sha: hash.to_owned() } } else { Content::File { from, sha: hash.to_owned() } };
            work.writes.push(FileWrite { rel: format!("{}/{name}", folder.dir()), content, expect, checks: Vec::new() });
            work.placed.push((folder, name.clone(), hash.to_owned(), info.dir));
            by_name.insert(fold(&name), hash.to_owned());
            work.applied.insert((*key).clone());
        }
    }
}

/// Sets options.txt's resource pack selection to the store's, by content: each pack under the name this
/// profile has it by, only if it suits this version (or this profile already had it switched on despite
/// that). Minecraft's own packs and any entries that aren't this profile's files keep their places.
fn apply_resource_selection(st: &State, library: &Library, view: &View, snap: &Snap, work: &mut Work, doc: &mut OptionsDoc, key: &str) {
    let Some(entry) = st.entries.get(key) else { return };
    let Ok(wanted) = serde_json::from_str::<Vec<String>>(&entry.value) else { return };
    let (Some(current), Some(forced)) = (pack_list(doc, RESOURCE_PACKS_KEY), pack_list(doc, INCOMPATIBLE_KEY)) else {
        work.deferred.insert(key.to_owned(), "this profile's pack list can't be read".into());
        return;
    };
    let mut names: BTreeMap<String, String> = snap.packs[&Folder::Resource].iter().map(|p| (p.name.clone(), p.sha.clone())).collect();
    for (folder, name, hash, _) in &work.placed {
        if *folder == Folder::Resource {
            names.insert(name.clone(), hash.clone());
        }
    }
    let by_hash = |hash: &str| -> Option<String> {
        // The name the current selection already uses for it, else the first name that has it.
        current.iter().filter_map(|e| file_name(e, &names)).find(|n| names.get(*n).map(String::as_str) == Some(hash)).map(str::to_owned).or_else(|| names.iter().find(|(_, h)| h.as_str() == hash).map(|(n, _)| n.clone()))
    };
    let mut files: Vec<String> = Vec::new();
    let mut skipped = 0;
    for hash in &wanted {
        let Some(name) = by_hash(hash) else {
            skipped += 1;
            continue;
        };
        let entry_text = format!("file/{name}");
        let fit = packs::fit(library.folder(Folder::Resource).get(hash).and_then(|p| p.formats), view.resource_format);
        let forced_here = current.contains(&entry_text) && forced.contains(&entry_text);
        if fit == Fit::Incompatible && !forced_here {
            skipped += 1;
            continue;
        }
        if !files.contains(&entry_text) {
            files.push(entry_text);
        }
    }
    let first_file = current.iter().position(|e| file_name(e, &names).is_some_and(|n| names.contains_key(n)));
    let mut next: Vec<String> = current.iter().filter(|e| !file_name(e, &names).is_some_and(|n| names.contains_key(n))).cloned().collect();
    let at = first_file.map(|i| current[..i].iter().filter(|e| !file_name(e, &names).is_some_and(|n| names.contains_key(n))).count()).unwrap_or(next.len());
    for (i, f) in files.into_iter().enumerate() {
        next.insert(at + i, f);
    }
    if next != current {
        doc.set(RESOURCE_PACKS_KEY, &serde_json::to_string(&next).unwrap_or_else(|_| "[]".into()));
    }
    if skipped > 0 {
        detail(|| format!("{key}: {skipped} pack(s) left unselected (missing or not for this version)"));
    }
    work.applied.insert(key.to_owned());
}

/// Sets Iris's shader selection, where Iris is installed and has written its settings once.
fn apply_shader_selection(st: &State, view: &View, snap: &Snap, work: &mut Work, pending: &mut BTreeMap<String, (Vec<u8>, Option<String>)>, key: &str) {
    let Some(entry) = st.entries.get(key) else { return };
    if !view.has_iris() {
        work.deferred.insert(key.to_owned(), "Iris isn't installed here".into());
        return;
    }
    let rel = "config/iris.properties".to_owned();
    let (bytes, expect) = match pending.get(&rel) {
        Some((bytes, expect)) => (bytes.clone(), expect.clone()),
        None => match &snap.iris {
            Some((doc, sha)) => (doc.to_string().into_bytes(), Some(sha.clone())),
            None => {
                work.deferred.insert(key.to_owned(), "Iris hasn't written its settings here yet".into());
                return;
            }
        },
    };
    let Ok(mut doc) = PropsDoc::parse(&bytes) else { return };
    let value: Json = serde_json::from_str(&entry.value).unwrap_or(Json::Null);
    let mut names: Vec<(String, String)> = snap.packs[&Folder::Shader].iter().map(|p| (p.name.clone(), p.sha.clone())).collect();
    names.extend(work.placed.iter().filter(|(f, ..)| *f == Folder::Shader).map(|(_, n, h, _)| (n.clone(), h.clone())));
    if let Some(hash) = value["pack"].as_str() {
        match names.iter().find(|(_, h)| h == hash) {
            Some((name, _)) => doc.set("shaderPack", name),
            None => {
                work.deferred.insert(key.to_owned(), "the shader pack isn't here yet".into());
                return;
            }
        }
    }
    if let Some(enabled) = value["enabled"].as_str().filter(|v| *v == "true" || *v == "false") {
        doc.set("enableShaders", enabled);
    }
    let out = doc.to_string().into_bytes();
    if out != bytes {
        pending.insert(rel, (out, expect));
    }
    work.applied.insert(key.to_owned());
}

/// Brings Flint's own settings in with `flintfiles`' rules (never over a newer or damaged file; Control
/// Center keys Flint doesn't sync are kept; the active layout only when that layout is there).
fn apply_flint(view: &View, entries: &[(&String, &Entry)], work: &mut Work) {
    if !view.runtime {
        for (key, _) in entries {
            work.deferred.insert((*key).clone(), "Flint's in-game menu isn't in this profile".into());
        }
        return;
    }
    let (target, seen) = flintfiles::load(&view.dir);
    let mut source = FlintDocs::default();
    let mut categories = Vec::new();
    let mut cc_schema: Option<i64> = match &target.control_center.0 {
        JsonFile::Ok { root, .. } => match flintfiles::schema_of(root) {
            Schema::Known(s) => Some(s),
            Schema::Unrecognised => None,
        },
        _ => None,
    };
    let mut cc = serde_json::Map::new();
    for (key, entry) in entries {
        if let Some(id) = key.strip_prefix(FLINT_LAYOUT) {
            source.layouts.push((id.to_owned(), JsonFile::parse(entry.value.clone().into_bytes())));
        } else if let Some(name) = key.strip_prefix(FLINT_CC) {
            let Ok(parsed) = serde_json::from_str::<Json>(&entry.value) else { continue };
            let schema = parsed["schema"].as_i64().unwrap_or(1);
            if *cc_schema.get_or_insert(schema) != schema {
                work.deferred.insert((*key).clone(), "Control Center settings from another Flint version".into());
                continue;
            }
            let value = parsed["value"].clone();
            match name.split_once('.') {
                Some((section, field)) => {
                    let slot = cc.entry(section.to_owned()).or_insert_with(|| json!({}));
                    if let Some(map) = slot.as_object_mut() {
                        map.insert(field.to_owned(), value);
                    }
                }
                None => {
                    cc.insert(name.to_owned(), value);
                }
            }
        } else if *key == FLINT_SHORTCUTS {
            source.shortcuts = JsonFileOrMissing(JsonFile::parse(entry.value.clone().into_bytes()));
            categories.push(SyncCategory::ServerShortcuts);
        }
    }
    if !source.layouts.is_empty() || cc.contains_key("activeLayout") {
        categories.push(SyncCategory::HudLayouts);
    }
    if cc.keys().any(|k| k != "activeLayout") {
        categories.push(SyncCategory::ControlCenter);
    }
    if !cc.is_empty() {
        cc.insert("schema".into(), json!(cc_schema.unwrap_or(1)));
        source.control_center = JsonFileOrMissing(JsonFile::parse(Json::Object(cc).to_string().into_bytes()));
    }
    let planned = flintfiles::plan(&source, "Auto Sync", &target, &categories, &HashSet::new());
    let expect_of = |rel: &str| seen.iter().find(|(r, _)| r == rel).and_then(|(_, b)| b.as_deref().map(sha));
    let after = |rel: &str| -> Option<Vec<u8>> {
        planned.writes.iter().find(|w| w.rel == rel).map(|w| w.bytes.clone()).or_else(|| seen.iter().find(|(r, _)| r == rel).and_then(|(_, b)| b.clone()))
    };
    for write in &planned.writes {
        work.writes.push(FileWrite::bytes(write.rel.clone(), write.bytes.clone(), expect_of(&write.rel)));
    }
    // Each entry counts as brought in when the profile's file (as it will be) holds its value.
    for (key, entry) in entries {
        if work.deferred.contains_key(*key) {
            continue;
        }
        let fits = if let Some(id) = key.strip_prefix(FLINT_LAYOUT) {
            after(&flintfiles::layout_path(id)).and_then(|b| serde_json::from_slice::<Json>(&b).ok()).map(|v| json_fp(&v)) == Some(entry.fp.clone())
        } else if let Some(name) = key.strip_prefix(FLINT_CC) {
            let root = after(flintfiles::CONTROL_CENTER).and_then(|b| serde_json::from_slice::<Json>(&b).ok());
            let value = root.as_ref().and_then(|r| match name.split_once('.') {
                Some((section, field)) => r.get(section).and_then(|s| s.get(field)),
                None => r.get(name),
            });
            let wanted: Json = serde_json::from_str::<Json>(&entry.value).map(|v| v["value"].clone()).unwrap_or(Json::Null);
            match (value, &wanted) {
                // A whole section counts when every key of the stored one is there with its value.
                (Some(Json::Object(have)), Json::Object(want)) => want.iter().all(|(k, v)| have.get(k) == Some(v)),
                (Some(v), want) => v == want,
                (None, _) => false,
            }
        } else {
            after(flintfiles::SHORTCUTS).and_then(|b| serde_json::from_slice::<Json>(&b).ok()).and_then(|r| r.get("shortcuts").map(json_fp)) == Some(entry.fp.clone())
        };
        if fits {
            work.applied.insert((*key).clone());
        } else {
            work.deferred.insert((*key).clone(), "this profile's Flint file is newer or damaged".into());
        }
    }
}

/// Brings stored mod settings in, by each file's class (see `mods`).
fn apply_mods(ctx: &Ctx, view: &View, entries: &[(&String, &Entry)], work: &mut Work, pending: &mut BTreeMap<String, (Vec<u8>, Option<String>)>) {
    let lithium_keys = view.mods.contains_key("lithium").then(|| mods::lithium_keys(&view.dir)).flatten();
    for (key, entry) in entries {
        let rest = &key[MODS.len()..];
        let Some((mod_id, rel)) = rest.split_once('/') else { continue };
        let class = mods::class_at(rel, mod_id, entry.mod_version.as_deref().unwrap_or_default(), &view.mods);
        let Some(version) = view.mods.get(mod_id) else {
            work.deferred.insert((*key).clone(), format!("{class:?}: the mod isn't installed here"));
            continue;
        };
        if class == mods::Class::Never || mods::attribute(rel, &view.mods).map(|(m, _)| m) != Some(mod_id.to_owned()) {
            work.deferred.insert((*key).clone(), format!("{class:?}: not this mod's file here"));
            continue;
        }
        let Some(stored) = read_blob(&ctx.store_root, key, &entry.value) else {
            work.deferred.insert((*key).clone(), "the stored copy is missing".into());
            continue;
        };
        let path = format!("config/{rel}");
        if apply::link_problem(&view.dir, &path).is_some() {
            work.deferred.insert((*key).clone(), "a link".into());
            continue;
        }
        let current = read_capped(&view.dir.join(&path), mods::MAX_CONFIG);
        let outcome = mods::bring(&mods::Merge {
            rel,
            stored: &stored,
            stored_version: entry.mod_version.as_deref().unwrap_or_default(),
            current: current.as_deref(),
            current_version: version,
            lithium_keys: lithium_keys.as_deref(),
        });
        match outcome {
            mods::Outcome::Same => {
                work.applied.insert((*key).clone());
            }
            mods::Outcome::Write(bytes) => {
                pending.insert(path, (bytes, current.as_deref().map(sha)));
                work.applied.insert((*key).clone());
            }
            mods::Outcome::Wait(reason) => {
                work.deferred.insert((*key).clone(), format!("{class:?}: {reason}"));
            }
        }
    }
}

/// Which store entries this profile should look at now.
fn candidates<'a>(st: &'a State, sync: &ProfileSync, snap: &Snap, captured: &BTreeSet<String>, first_contact: bool) -> Vec<(&'a String, &'a Entry)> {
    st.entries
        .iter()
        .filter(|(key, _)| !captured.contains(*key))
        // A value that first appeared elsewhere doesn't replace this profile's own value for it.
        .filter(|(key, entry)| first_contact || !(entry.introduced && sync.seen.contains_key(*key)))
        .filter(|(key, entry)| {
            first_contact
                || entry.rev > sync.applied_rev
                || sync.deferred.contains(*key)
                // Appeared here since Flint last looked (a mod installed, a key line added): the store's
                // value comes in.
                || (!sync.seen.contains_key(*key) && snap.items.contains_key(*key))
        })
        .collect()
}

/// Works out and writes everything the store has for this profile. Returns the report of what fit.
/// The profile being brought in line: what it is, what it holds now and its record.
struct Here<'a> {
    view: &'a View,
    snap: &'a Snap,
    sync: &'a ProfileSync,
}

fn apply(ctx: &Ctx, st: &State, library: &Library, here: &Here, captured: &BTreeSet<String>, first_contact: bool) -> Result<Work, TxnError> {
    let Here { view, snap, sync } = *here;
    let list = candidates(st, sync, snap, captured, first_contact);
    let mut work = Work::default();
    if list.is_empty() {
        return Ok(work);
    }
    let of = |prefix: &str| -> Vec<(&String, &Entry)> { list.iter().filter(|(k, _)| k.starts_with(prefix)).cloned().collect() };
    let mut pending: BTreeMap<String, (Vec<u8>, Option<String>)> = BTreeMap::new();
    // Packs first: the selections name them as this profile will have them.
    apply_pack_files(ctx, st, library, snap, sync, &of("packs/"), &mut work);
    // Minecraft's options.
    let options = of(OPTIONS);
    let selection_key = format!("packs/resourcepacks/{SELECTION}");
    let wants_selection = list.iter().any(|(k, _)| **k == selection_key);
    let mut doc = if options.is_empty() {
        match &snap.options {
            OptionsFile::Ok(doc) => Some(doc.clone()),
            _ => None,
        }
    } else {
        apply_options(view, &snap.options, &options, &mut work)
    };
    if wants_selection {
        match doc.as_mut() {
            Some(doc) => apply_resource_selection(st, library, view, snap, &mut work, doc, &selection_key),
            None => {
                work.deferred.insert(selection_key.clone(), "options.txt can't be changed here".into());
            }
        }
    }
    if let (Some(doc), OptionsFile::Ok(original)) = (&doc, &snap.options) {
        if doc != original {
            let checks: Vec<(String, String)> = doc.keys().filter(|k| original.get(k) != doc.get(k)).map(|k| (k.to_owned(), doc.get(k).unwrap_or_default().to_owned())).collect();
            work.writes.push(FileWrite { rel: "options.txt".into(), content: Content::Bytes(doc.to_string().into_bytes()), expect: snap.options_sha.clone(), checks });
        }
    } else if let (Some(doc), OptionsFile::Missing) = (&doc, &snap.options) {
        // A profile that hasn't been played yet: a new options.txt with its version line and these values.
        if doc.line_count() > 1 {
            work.writes.push(FileWrite::bytes("options.txt", doc.to_string().into_bytes(), None));
        }
    }
    apply_mods(ctx, view, &of(MODS), &mut work, &mut pending);
    let shader_key = format!("packs/shaderpacks/{SELECTION}");
    if list.iter().any(|(k, _)| **k == shader_key) {
        apply_shader_selection(st, view, snap, &mut work, &mut pending, &shader_key);
    }
    apply_flint(view, &of(FLINT), &mut work);
    for (rel, (bytes, expect)) in pending {
        work.writes.push(FileWrite::bytes(rel, bytes, expect));
    }
    // Remaining candidates (a pack entry that was resolved above, an empty entry) count as seen.
    for (key, _) in &list {
        if !work.deferred.contains_key(*key) {
            work.applied.insert((*key).clone());
        }
    }
    if work.writes.is_empty() {
        return Ok(work);
    }
    let backup = apply::commit(&view.dir, &work.writes, "Auto Sync", ctx.hooks)?;
    detail(|| format!("wrote {} file(s); backup {backup}", work.writes.len()));
    work.backup = Some(backup);
    Ok(work)
}

// ---------- the store's life cycle ----------

fn profiles(state: &AppState) -> Vec<Profile> {
    crate::core::read_json::<ProfilesFile>(&state.paths.profiles()).profiles
}

/// A new store, seeded from the most recently played eligible profile with an options.txt that isn't
/// running; every existing profile is taken as it is at its first contact.
fn seed(ctx: &Ctx, scan: &ProcessScan) -> State {
    let mut st = State::new();
    let all = profiles(ctx.state);
    let mut library = packs::load_library(&ctx.store_root);
    let mut played: Vec<&Profile> = all.iter().filter(|p| p.last_played.is_some()).collect();
    played.sort_by_key(|p| std::cmp::Reverse(p.last_played));
    for profile in played {
        // A game running in it (here or elsewhere) may be saving its files; a profile Flint is preparing to
        // launch is held and safe to read.
        if ctx.state.running.lock().unwrap().contains_key(&profile.id) || started_elsewhere(ctx.state, &profile.id, scan).is_some() {
            continue;
        }
        let view = View::load(ctx.state, profile);
        if !view.eligible() {
            continue;
        }
        let snap = snapshot(&view, &mut BTreeMap::new(), false);
        if !matches!(snap.options, OptionsFile::Ok(_)) {
            continue;
        }
        let rev = st.next_rev();
        let now = now_secs();
        for (key, item) in &snap.items {
            if keep_content(&ctx.store_root, &mut library, key, item, ctx.hooks.copier, &view.dir).is_err() {
                continue;
            }
            st.entries.insert(
                key.clone(),
                Entry {
                    value: item.value.clone(),
                    rev,
                    origin_profile: view.id.clone(),
                    origin_family: item.family.clone(),
                    updated_at: now,
                    fp: item.fp.clone(),
                    mod_version: item.mod_version.clone(),
                    introduced: false,
                },
            );
            record_mod_meta(&ctx.store_root, key, &st.entries[key]);
        }
        st.seeded_from = Some(view.id.clone());
        log(&format!("turned on: seeded {} entries ({}) from the most recently played profile", st.entries.len(), counts(st.entries.keys())));
        break;
    }
    if st.seeded_from.is_none() {
        log("turned on: no profile has been played yet, so the store starts empty");
    }
    st.baseline_rev = st.head();
    st.baseline_ids = all.iter().map(|p| p.id.clone()).collect();
    if let Err(e) = packs::save_library(&ctx.store_root, &library) {
        log(&format!("couldn't save the pack library ({e})"));
    }
    st
}

/// Turning Auto Sync back on: every existing profile is taken as it is again.
fn rebaseline(ctx: &Ctx, st: &mut State) {
    st.enabled = true;
    st.epoch += 1;
    st.baseline_rev = st.head();
    st.baseline_ids = profiles(ctx.state).into_iter().map(|p| p.id).collect();
    log("turned back on: every profile is taken as it is now");
}

fn save_state(ctx: &Ctx, st: &mut State, generation: u64) -> Result<(), String> {
    store::save(&ctx.state.paths.root, st, generation)
}

/// The store, made or re-baselined as the setting asks (holding `SYNC`).
fn ensure(ctx: &Ctx, scan: &ProcessScan) -> Result<State, String> {
    let mut st = match store::load(&ctx.state.paths.root) {
        Loaded::Ok(st) if st.enabled => return Ok(st),
        Loaded::Ok(mut st) => {
            rebaseline(ctx, &mut st);
            st
        }
        Loaded::Missing => seed(ctx, scan),
        Loaded::Damaged => {
            log("the store couldn't be read; it was set aside and a new one started");
            store::set_aside(&ctx.state.paths.root);
            seed(ctx, scan)
        }
    };
    let generation = st.generation + 1;
    save_state(ctx, &mut st, generation)?;
    Ok(st)
}

/// The setting is off: the store mirrors it (nothing else changes).
fn mirror_off(ctx: &Ctx) {
    if let Loaded::Ok(mut st) = store::load(&ctx.state.paths.root) {
        if st.enabled {
            st.enabled = false;
            let generation = st.generation + 1;
            if save_state(ctx, &mut st, generation).is_ok() {
                log("turned off: nothing is synced until it is turned back on");
            }
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Start {
    /// A profile Auto Sync hasn't met that was created after it was turned on: it gets the whole store.
    FirstContact,
    /// Taken as it is (turned on while it existed, or its record can't be trusted).
    Baseline,
    Normal,
}

fn start(st: &State, id: &str, sync: &Option<ProfileSync>) -> Start {
    match sync {
        None if st.baseline_ids.contains(id) => Start::Baseline,
        None => Start::FirstContact,
        Some(s) if s.pending_baseline || s.store != st.tag() || s.generation > st.generation => Start::Baseline,
        Some(_) => Start::Normal,
    }
}

fn seen_of(snap: &Snap) -> BTreeMap<String, String> {
    snap.items.iter().map(|(k, item)| (k.clone(), item.fp.clone())).collect()
}

/// Saves a profile's record and then the store (always last), as one generation.
fn finish(ctx: &Ctx, st: &mut State, library: &Library, profile_dir: &Path, sync: Option<&mut ProfileSync>) {
    let generation = st.generation + 1;
    if let Err(e) = packs::save_library(&ctx.store_root, library) {
        log(&format!("couldn't save the pack library ({e})"));
    }
    if let Some(sync) = sync {
        sync.store = st.tag();
        if let Err(e) = store::save_profile(profile_dir, sync, generation) {
            log(&format!("couldn't save a profile's sync record ({e})"));
        }
    }
    if let Err(e) = save_state(ctx, st, generation) {
        log(&format!("couldn't save the store ({e})"));
    }
}

fn notice_for(st: &mut State, report: &mut Report, id: String, text: String) {
    st.notice(&id, &text);
    report.notices.push(text);
}

fn too_large(st: &mut State, report: &mut Report, snap: &Snap) {
    for folder in &snap.too_large {
        let (id, text) = match folder {
            Folder::Resource => ("pack-too-large", "A resource pack is too large to sync."),
            Folder::Shader => ("shader-too-large", "A shader pack is too large to sync."),
        };
        notice_for(st, report, id.into(), text.into());
    }
}

// ---------- the hooks ----------

/// Before a launch (the profile is held; its game isn't running): capture this profile's own changes,
/// then bring in what changed elsewhere. Never fails the launch.
pub fn before_launch(state: &AppState, profile: &Profile) -> Report {
    before_launch_with(state, profile, &ProcessScan::take(), &Hooks::default())
}

pub(crate) fn before_launch_with(state: &AppState, profile: &Profile, scan: &ProcessScan, hooks: &Hooks) -> Report {
    let _one = SYNC.lock().unwrap_or_else(|e| e.into_inner());
    let ctx = Ctx { state, store_root: store::root(&state.paths.root), hooks };
    if !setting_enabled(state) {
        mirror_off(&ctx);
        return Report::skip("off");
    }
    let mut st = match ensure(&ctx, scan) {
        Ok(st) => st,
        Err(e) => {
            log(&format!("before launch: the store couldn't be saved ({e}); nothing synced"));
            return Report::skip("store");
        }
    };
    // Another copy of this profile's game (started before Flint opened, or by another launcher) would
    // overwrite whatever is written now.
    if started_elsewhere(state, &profile.id, scan).is_some() {
        log("before launch: this profile's game is already running, so nothing was synced");
        return Report::skip("running");
    }
    let view = View::load(state, profile);
    let mut library = packs::load_library(&ctx.store_root);
    let previous = store::load_profile(&view.dir);
    let begin = start(&st, &view.id, &previous);
    let mut sync = previous.unwrap_or_else(|| ProfileSync::new(0));
    let mut report = Report::default();
    let snap = snapshot(&view, &mut sync.packs, false);
    too_large(&mut st, &mut report, &snap);
    let mut captured = BTreeSet::new();
    match begin {
        Start::Baseline => {
            sync = ProfileSync { packs: std::mem::take(&mut sync.packs), seen: seen_of(&snap), ..ProfileSync::new(st.baseline_rev) };
        }
        Start::FirstContact if !view.eligible() => {
            sync = ProfileSync { packs: std::mem::take(&mut sync.packs), seen: seen_of(&snap), ..ProfileSync::new(st.head()) };
        }
        Start::FirstContact => {}
        Start::Normal => {
            captured = capture(&ctx, &mut st, &mut library, &view, &snap, &sync).into_iter().collect();
            report.captured = captured.iter().cloned().collect();
            // What was just captured is what this profile has now.
            for key in &captured {
                match snap.items.get(key) {
                    Some(item) => sync.seen.insert(key.clone(), item.fp.clone()),
                    None => sync.seen.remove(key),
                };
            }
        }
    }
    if !view.eligible() {
        report.skipped = Some("not verified");
        log(&format!("before launch: captured {} ({}); this version isn't verified, so nothing is brought in", captured.len(), counts(&captured)));
        finish(&ctx, &mut st, &library, &view.dir, Some(&mut sync));
        return report;
    }
    if snap.options_problem {
        notice_for(
            &mut st,
            &mut report,
            format!("unreadable:{}", view.id),
            format!("A settings file in ‘{}’ couldn't be read; Auto Sync is leaving that profile's settings alone.", view.name),
        );
        log("before launch: options.txt couldn't be read, so this profile was left alone");
        // A profile met for the first time is taken as it is only once its options can be read; until then
        // it has no record, so nothing in it is ever mistaken for new.
        finish(&ctx, &mut st, &library, &view.dir, (begin == Start::Normal).then_some(&mut sync));
        report.skipped = Some("unreadable");
        return report;
    }
    let result = apply(&ctx, &st, &library, &Here { view: &view, snap: &snap, sync: &sync }, &captured, begin == Start::FirstContact);
    match result {
        Ok(work) => {
            // Re-read the profile as it is now: that is its new last-seen.
            for (folder, name, hash, dir) in &work.placed {
                if let Some((size, mtime)) = packs::stat(&view.dir.join(folder.dir()).join(name), *dir) {
                    sync.packs.insert(format!("{}/{name}", folder.dir()), store::CachedPack { size, mtime, sha: hash.clone(), dir: *dir });
                }
            }
            let after = if work.writes.is_empty() { snap } else { snapshot(&view, &mut sync.packs, false) };
            sync.seen = seen_of(&after);
            sync.applied_rev = st.head();
            sync.pending_baseline = false;
            let newly: Vec<&String> = work.deferred.keys().filter(|k| !sync.deferred.contains(*k)).collect();
            for key in &newly {
                detail(|| format!("{key}: waits ({})", work.deferred[*key]));
            }
            sync.deferred = work.deferred.keys().cloned().collect();
            report.applied = work.applied.iter().cloned().collect();
            report.backup = work.backup.clone();
            report.deferred = work.deferred.clone();
            log(&format!(
                "before launch: captured {} ({}), brought in {} ({}) with {} file(s) written, {} waiting",
                captured.len(),
                counts(&captured),
                work.applied.len(),
                counts(&work.applied),
                work.writes.len(),
                work.deferred.len()
            ));
        }
        Err(TxnError::Conflict(rel)) => {
            detail(|| format!("{rel} changed while it was being updated"));
            notice_for(
                &mut st,
                &mut report,
                format!("changed:{}", view.id),
                format!("Settings in ‘{}’ changed while Auto Sync was updating them, so it left them as they were this time.", view.name),
            );
            log("before launch: a file changed while it was being updated; nothing was written");
            report.skipped = Some("conflict");
        }
        Err(TxnError::Refused(reason)) | Err(TxnError::Failed(reason)) => {
            detail(|| reason.clone());
            notice_for(&mut st, &mut report, format!("failed:{}", view.id), format!("Auto Sync couldn't update ‘{}’, so its files were left as they were.", view.name));
            log("before launch: writing failed, so this profile's files were put back as they were");
            report.skipped = Some("failed");
        }
    }
    if library.total_size() > packs::LIBRARY_WARN {
        notice_for(&mut st, &mut report, "library-large".into(), "Auto Sync's pack library is over 10 GB.".into());
    }
    finish(&ctx, &mut st, &library, &view.dir, Some(&mut sync));
    report
}

/// After a game ended: capture what the player changed. Nothing is written into any profile.
pub fn after_exit(state: &AppState, id: &str, exit: Exit) -> Report {
    after_exit_with(state, id, exit, &ProcessScan::take(), &Hooks::default())
}

pub(crate) fn after_exit_with(state: &AppState, id: &str, exit: Exit, scan: &ProcessScan, hooks: &Hooks) -> Report {
    let _one = SYNC.lock().unwrap_or_else(|e| e.into_inner());
    let ctx = Ctx { state, store_root: store::root(&state.paths.root), hooks };
    if !setting_enabled(state) {
        mirror_off(&ctx);
        return Report::skip("off");
    }
    // Launched again already (that launch captures first), or another copy of its game is still running.
    if profile_running(state, id, scan).is_some() {
        return Report::skip("busy");
    }
    let Ok(profile) = crate::data::find_profile(state, id) else { return Report::skip("gone") };
    let mut st = match ensure(&ctx, scan) {
        Ok(st) => st,
        Err(_) => return Report::skip("store"),
    };
    let view = View::load(state, &profile);
    let mut library = packs::load_library(&ctx.store_root);
    let previous = store::load_profile(&view.dir);
    let begin = start(&st, &view.id, &previous);
    if begin == Start::FirstContact && view.eligible() {
        // Its first launch brings the store in; until then its values aren't taken.
        return Report::skip("first contact");
    }
    let mut sync = previous.unwrap_or_else(|| ProfileSync::new(st.head()));
    let mut report = Report::default();
    let snap = snapshot(&view, &mut sync.packs, exit == Exit::Unclean);
    too_large(&mut st, &mut report, &snap);
    if begin != Start::Normal {
        // After a crash the files may be cut short, and an unreadable options.txt can't be taken as it is:
        // the next launch takes the profile as it is instead.
        if exit == Exit::Unclean || snap.options_problem {
            return Report::skip("baseline later");
        }
        sync = ProfileSync { packs: std::mem::take(&mut sync.packs), seen: seen_of(&snap), ..ProfileSync::new(if begin == Start::Baseline { st.baseline_rev } else { st.head() }) };
        finish(&ctx, &mut st, &library, &view.dir, Some(&mut sync));
        report.skipped = Some("baseline");
        return report;
    }
    let captured = capture(&ctx, &mut st, &mut library, &view, &snap, &sync);
    for (key, item) in &snap.items {
        // Files that didn't read back whole after a crash keep their last-seen value (no item for them);
        // values new here that the store already has stay unseen, so the store's comes in at next launch.
        if sync.seen.contains_key(key) || captured.contains(key) {
            sync.seen.insert(key.clone(), item.fp.clone());
        }
    }
    for key in &captured {
        if !snap.items.contains_key(key) {
            sync.seen.remove(key);
        }
    }
    log(&format!("after exit{}: captured {} ({})", if exit == Exit::Unclean { " (unclean)" } else { "" }, captured.len(), counts(&captured)));
    report.captured = captured;
    finish(&ctx, &mut st, &library, &view.dir, Some(&mut sync));
    report
}

// ---------- turning it on and off ----------

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct NoticeView {
    pub id: String,
    pub text: String,
}

/// What the Settings row shows under the switch: "Waiting for your first game" or one notice, nothing else.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub enabled: bool,
    pub waiting: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub notice: Option<NoticeView>,
}

fn status_of(enabled: bool, st: Option<&State>) -> Status {
    Status {
        enabled,
        waiting: enabled && st.is_none_or(|s| s.entries.is_empty()),
        notice: if enabled { st.and_then(|s| s.notices.first()).map(|n| NoticeView { id: n.id.clone(), text: n.text.clone() }) } else { None },
    }
}

pub(crate) fn set_enabled(state: &AppState, enabled: bool, scan: &ProcessScan, hooks: &Hooks) -> CmdResult<Status> {
    let _one = SYNC.lock().unwrap_or_else(|e| e.into_inner());
    let ctx = Ctx { state, store_root: store::root(&state.paths.root), hooks };
    if !enabled {
        mirror_off(&ctx);
        let st = match store::load(&state.paths.root) {
            Loaded::Ok(st) => Some(st),
            _ => None,
        };
        return Ok(status_of(false, st.as_ref()));
    }
    let st = ensure(&ctx, scan).map_err(|e| format!("Auto Sync couldn't save its data: {e}"))?;
    Ok(status_of(true, Some(&st)))
}

pub(crate) fn status(state: &AppState) -> Status {
    let st = match store::load(&state.paths.root) {
        Loaded::Ok(st) => Some(st),
        _ => None,
    };
    status_of(setting_enabled(state), st.as_ref())
}

pub(crate) fn dismiss(state: &AppState, id: &str) -> Status {
    let _one = SYNC.lock().unwrap_or_else(|e| e.into_inner());
    if let Loaded::Ok(mut st) = store::load(&state.paths.root) {
        if st.dismiss(id) {
            let generation = st.generation + 1;
            let _ = store::save(&state.paths.root, &mut st, generation);
        }
    }
    status(state)
}

#[tauri::command]
pub async fn auto_sync_set_enabled(app: AppHandle, enabled: bool) -> CmdResult<Status> {
    blocking(&app, move |state| set_enabled(state, enabled, &ProcessScan::take(), &Hooks::default())).await?
}

#[tauri::command]
pub async fn auto_sync_status(app: AppHandle) -> CmdResult<Status> {
    blocking(&app, status).await
}

#[tauri::command]
pub async fn auto_sync_dismiss(app: AppHandle, id: String) -> CmdResult<Status> {
    blocking(&app, move |state| dismiss(state, &id)).await
}
