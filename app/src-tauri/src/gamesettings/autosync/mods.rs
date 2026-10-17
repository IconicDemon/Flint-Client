//! Mod settings: which files in a profile's `config/` belong to which installed mod, how each is compared,
//! and how a stored copy is brought into another profile.
//!
//! Every file falls in one class (the words diagnostics use):
//! - `Portable`: a verified adapter knows the file and merges it key by key, so it works across mod
//!   versions. The adapters were checked against the real jars and the files the real game wrote
//!   (scratchpad/m14/bench/mods-stack and games/*/config, m17/game/*/config):
//!   - Sodium 0.9.2 `config/sodium-options.json` (JSON; `SodiumOptions.DEFAULT_FILE_NAME`, Gson; sections
//!     quality / performance / advanced / debug / notifications). Strings are enum names, so they only move
//!     between the same major.minor; `notifications` holds prompts already seen and never moves.
//!   - Lithium 0.26.2 `config/lithium.properties` (`LithiumConfig`: `mixin.*=true|false` overrides; the jar's
//!     `assets/lithium/lithium-mixin-config-default.properties` lists every valid key, and unknown keys are
//!     ignored by Lithium with a warning).
//!   - FerriteCore 9.0.0 `config/ferritecore.mixin.properties` (`ConfigFileHandler`, `key = value` booleans).
//!   - ImmediatelyFast 1.17.1 `config/immediatelyfast.json` (flat JSON; the `*_INFO` keys are labels).
//!   - EntityCulling 1.11.3 `config/entityculling.json` (JSON with `configVersion`, which must match).
//!   - Dynamic FPS 3.11.10 `config/dynamic_fps.json` (`Serialization`: JSON holding only values that differ
//!     from the defaults, so a merge only reaches keys both files have).
//!   - Iris `config/iris.properties`: no Iris jar was available to check, so only keys both files already
//!     have are merged, and `shaderPack` / `enableShaders` are left to the shader selection.
//! - `SameVersionVerbatim`: another mod's file (`config/<modid>.<json|json5|toml|properties|cfg|txt>` or
//!   anything under `config/<modid>/`), copied as it is between profiles with the same version of that mod.
//! - `StoredOnly`: kept in the store while it doesn't fit (another version, the mod missing) and tried again.
//! - `Never`: not attributable to an installed mod, or sensitive by name: never read, never written.

use super::jsonedit;
use super::props::PropsDoc;
use crate::perfcore::ModJar;
use serde_json::Value as Json;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::path::Path;

/// Settings files over this size aren't synced.
pub const MAX_CONFIG: u64 = 64 * 1024 * 1024;
const MAX_FILES_PER_MOD: usize = 200;
const MAX_DEPTH: usize = 4;
/// Words that keep a file out of sync whatever folder it is in.
const SENSITIVE: &[&str] = &["token", "auth", "account", "session", "password", "secret", "credential", "login"];
const GENERIC_EXTENSIONS: &[&str] = &["json", "json5", "toml", "properties", "cfg", "txt"];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Class {
    Portable,
    SameVersionVerbatim,
    StoredOnly,
    Never,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Format {
    Json,
    Properties,
    Other,
}

/// A verified adapter: the file, its mod and how it is merged.
#[derive(Clone, Copy, Debug)]
pub struct Adapter {
    pub mod_id: &'static str,
    /// Path under `config/`.
    pub file: &'static str,
    pub format: Format,
    /// Same mod version on both sides: copy the file as it is.
    pub verbatim_same_version: bool,
    /// A profile without the file gets the stored one when the mod's major.minor matches (otherwise it
    /// waits for the mod to write its own file once).
    pub create_same_minor: bool,
}

pub const ADAPTERS: &[Adapter] = &[
    Adapter { mod_id: "sodium", file: "sodium-options.json", format: Format::Json, verbatim_same_version: true, create_same_minor: true },
    Adapter { mod_id: "lithium", file: "lithium.properties", format: Format::Properties, verbatim_same_version: true, create_same_minor: false },
    Adapter { mod_id: "ferritecore", file: "ferritecore.mixin.properties", format: Format::Properties, verbatim_same_version: true, create_same_minor: false },
    Adapter { mod_id: "immediatelyfast", file: "immediatelyfast.json", format: Format::Json, verbatim_same_version: true, create_same_minor: false },
    Adapter { mod_id: "entityculling", file: "entityculling.json", format: Format::Json, verbatim_same_version: true, create_same_minor: false },
    Adapter { mod_id: "dynamic_fps", file: "dynamic_fps.json", format: Format::Json, verbatim_same_version: true, create_same_minor: false },
    Adapter { mod_id: "iris", file: "iris.properties", format: Format::Properties, verbatim_same_version: false, create_same_minor: false },
];

/// Iris keys that belong to the shader selection, not the settings merge.
pub const IRIS_SELECTION: &[&str] = &["shaderPack", "enableShaders"];
/// Flint's own runtime: its files are Flint settings, synced separately.
const FLINT_RUNTIME_ID: &str = "flintclient";

pub fn adapter_for(rel: &str) -> Option<&'static Adapter> {
    ADAPTERS.iter().find(|a| a.file == rel)
}

/// Mods a profile loads: id → version (Flint's runtime left out).
pub fn installed(profile_dir: &Path) -> BTreeMap<String, String> {
    crate::perfcore::scan_mods(&profile_dir.join("mods"))
        .into_iter()
        .filter(|m: &ModJar| m.id != FLINT_RUNTIME_ID && m.file != crate::runtime::FILE_NAME)
        .map(|m| (m.id, m.version))
        .collect()
}

/// "0.9" from "0.9.2+mc26.3".
pub fn major_minor(version: &str) -> Option<String> {
    let core = version.split(['+', '-']).next()?;
    let mut parts = core.split('.');
    let major = parts.next().filter(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))?;
    let minor = parts.next().filter(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit())).unwrap_or("0");
    Some(format!("{major}.{minor}"))
}

pub fn sensitive(rel: &str) -> bool {
    let lower = rel.to_ascii_lowercase();
    SENSITIVE.iter().any(|w| lower.contains(w))
}

/// The installed mod a file under `config/` belongs to, and its class there; None for `Never`.
pub fn attribute(rel: &str, mods: &BTreeMap<String, String>) -> Option<(String, Class)> {
    if sensitive(rel) || rel.split('/').any(|part| part.starts_with('.') || part.is_empty()) {
        return None;
    }
    if rel == "flint-performance.json" || rel == "flintclient.json" || rel.starts_with("flintclient/") {
        return None;
    }
    if let Some(adapter) = adapter_for(rel) {
        return mods.contains_key(adapter.mod_id).then(|| (adapter.mod_id.to_owned(), Class::Portable));
    }
    let lower = rel.to_ascii_lowercase();
    if lower.ends_with(".tmp") || lower.contains(".damaged-") || lower.ends_with(".bak") || lower.ends_with(".old") {
        return None;
    }
    match rel.split_once('/') {
        Some((dir, _)) => mods.contains_key(dir).then(|| (dir.to_owned(), Class::SameVersionVerbatim)),
        None => {
            let (stem, ext) = rel.rsplit_once('.')?;
            (GENERIC_EXTENSIONS.contains(&ext.to_ascii_lowercase().as_str()) && mods.contains_key(stem)).then(|| (stem.to_owned(), Class::SameVersionVerbatim))
        }
    }
}

/// The settings files of installed mods under `config/`: rel path (under config/) → (mod id, class).
/// Links, hidden files and files over `MAX_CONFIG` are left out.
pub fn files(profile_dir: &Path, mods: &BTreeMap<String, String>) -> BTreeMap<String, (String, Class)> {
    let config = profile_dir.join("config");
    let mut out = BTreeMap::new();
    if config.symlink_metadata().map(|m| !m.is_dir()).unwrap_or(true) {
        return out;
    }
    let mut per_mod: BTreeMap<String, usize> = BTreeMap::new();
    let mut stack = vec![(config.clone(), String::new(), 0usize)];
    while let Some((dir, rel, depth)) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else { continue };
        let mut entries: Vec<_> = entries.flatten().collect();
        entries.sort_by_key(|e| e.file_name());
        for entry in entries {
            let Some(name) = entry.file_name().to_str().map(str::to_owned) else { continue };
            let child = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
            let Ok(meta) = entry.path().symlink_metadata() else { continue };
            if meta.file_type().is_symlink() || name.starts_with('.') {
                continue;
            }
            if meta.is_dir() {
                // Only folders named after an installed mod are looked into.
                if depth < MAX_DEPTH && (depth > 0 || mods.contains_key(&name)) && !sensitive(&child) {
                    stack.push((entry.path(), child, depth + 1));
                }
                continue;
            }
            if !meta.is_file() || meta.len() > MAX_CONFIG {
                continue;
            }
            if let Some((mod_id, class)) = attribute(&child, mods) {
                let count = per_mod.entry(mod_id.clone()).or_default();
                if *count < MAX_FILES_PER_MOD {
                    *count += 1;
                    out.insert(child, (mod_id, class));
                }
            }
        }
    }
    out
}

/// A stored settings file's class in a profile with `mods` installed (the vocabulary diagnostics use).
pub fn class_at(rel: &str, mod_id: &str, stored_version: &str, mods: &BTreeMap<String, String>) -> Class {
    if sensitive(rel) {
        return Class::Never;
    }
    let Some(version) = mods.get(mod_id) else { return Class::StoredOnly };
    match attribute(rel, mods) {
        None => Class::Never,
        Some((owner, _)) if owner != mod_id => Class::Never,
        Some((_, Class::Portable)) => Class::Portable,
        Some(_) if version == stored_version => Class::SameVersionVerbatim,
        Some(_) => Class::StoredOnly,
    }
}

pub fn format_of(rel: &str) -> Format {
    if let Some(adapter) = adapter_for(rel) {
        return adapter.format;
    }
    match rel.rsplit_once('.').map(|(_, e)| e.to_ascii_lowercase()) {
        Some(e) if e == "json" => Format::Json,
        Some(e) if e == "properties" => Format::Properties,
        _ => Format::Other,
    }
}

/// Whether bytes are a well-formed file of their format (anything goes for formats Flint doesn't parse).
pub fn well_formed(format: Format, bytes: &[u8]) -> bool {
    match format {
        Format::Json => serde_json::from_slice::<Json>(bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(bytes)).is_ok_and(|v| v.is_object()),
        Format::Properties => PropsDoc::parse(bytes).is_ok(),
        Format::Other => true,
    }
}

/// The comparison form of a settings file: its meaning rather than its spelling (key order, spacing), so a
/// mod rewriting the same values isn't taken for a change.
pub fn fingerprint(format: Format, bytes: &[u8]) -> String {
    let canonical = match format {
        Format::Json => serde_json::from_slice::<Json>(bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(bytes)).ok().map(|v| v.to_string()),
        Format::Properties => PropsDoc::parse(bytes).ok().map(|d| serde_json::to_string(&d.entries()).unwrap_or_default()),
        Format::Other => None,
    };
    match canonical {
        Some(text) => hex::encode(Sha256::digest(text.as_bytes())),
        None => hex::encode(Sha256::digest(bytes)),
    }
}

/// Keys Lithium accepts, from the jar's own default config (`assets/lithium/lithium-mixin-config-default.properties`).
pub fn lithium_keys(profile_dir: &Path) -> Option<Vec<String>> {
    use std::io::Read;
    let mods = profile_dir.join("mods");
    let jar = crate::perfcore::scan_mods(&mods).into_iter().find(|m| m.id == "lithium")?;
    let mut archive = zip::ZipArchive::new(std::fs::File::open(mods.join(&jar.file)).ok()?).ok()?;
    let entry = archive.by_name("assets/lithium/lithium-mixin-config-default.properties").ok()?;
    let mut bytes = Vec::new();
    entry.take(1024 * 1024).read_to_end(&mut bytes).ok()?;
    Some(PropsDoc::parse(&bytes).ok()?.entries().into_keys().collect())
}

/// What bringing a stored settings file into a profile comes to.
#[derive(Clone, Debug, PartialEq)]
pub enum Outcome {
    /// Write these bytes.
    Write(Vec<u8>),
    /// The profile already has these settings.
    Same,
    /// Not now (the reason, for diagnostics); kept and tried again.
    Wait(String),
}

/// Everything a merge needs to know about both sides.
pub struct Merge<'a> {
    pub rel: &'a str,
    pub stored: &'a [u8],
    pub stored_version: &'a str,
    /// The destination's current file, if it has one.
    pub current: Option<&'a [u8]>,
    pub current_version: &'a str,
    /// For Lithium: the keys the destination's Lithium accepts.
    pub lithium_keys: Option<&'a [String]>,
}

/// Brings a stored settings file into a profile, by the file's class.
pub fn bring(m: &Merge) -> Outcome {
    let same_version = m.stored_version == m.current_version;
    let format = format_of(m.rel);
    let adapter = adapter_for(m.rel);
    if let Some(current) = m.current {
        if fingerprint(format, current) == fingerprint(format, m.stored) {
            return Outcome::Same;
        }
    }
    let Some(adapter) = adapter else {
        return if same_version { verbatim(format, m.stored) } else { Outcome::Wait("another version of the mod (stored only)".into()) };
    };
    let Some(current) = m.current else {
        let same_minor = major_minor(m.stored_version).is_some() && major_minor(m.stored_version) == major_minor(m.current_version);
        return if (adapter.verbatim_same_version && same_version) || (adapter.create_same_minor && same_minor) {
            verbatim(format, m.stored)
        } else {
            Outcome::Wait("the mod hasn't written its own file here yet".into())
        };
    };
    if adapter.verbatim_same_version && same_version {
        return verbatim(format, m.stored);
    }
    let merged = match adapter.format {
        Format::Json => merge_json(adapter, m, current),
        Format::Properties => merge_props(adapter, m, current),
        Format::Other => return Outcome::Wait("no merge for this format".into()),
    };
    match merged {
        Ok(None) => Outcome::Same,
        Ok(Some(bytes)) if well_formed(format, &bytes) => Outcome::Write(bytes),
        Ok(Some(_)) => Outcome::Wait("the merged file wouldn't be valid".into()),
        Err(reason) => Outcome::Wait(reason),
    }
}

fn verbatim(format: Format, stored: &[u8]) -> Outcome {
    if well_formed(format, stored) {
        Outcome::Write(stored.to_vec())
    } else {
        Outcome::Wait("the stored file isn't valid".into())
    }
}

fn kind(v: &Json) -> u8 {
    match v {
        Json::Null => 0,
        Json::Bool(_) => 1,
        Json::Number(_) => 2,
        Json::String(_) => 3,
        Json::Array(_) => 4,
        Json::Object(_) => 5,
    }
}

/// A key-wise JSON merge: each value the destination file already has is set to the stored one when the
/// stored file has it too, with the same type, and the adapter allows it. Nothing is added or removed.
fn merge_json(adapter: &Adapter, m: &Merge, current: &[u8]) -> Result<Option<Vec<u8>>, String> {
    let text = std::str::from_utf8(current.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(current)).map_err(|_| "this profile's file isn't UTF-8".to_owned())?;
    let stored: Json = serde_json::from_slice(m.stored.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(m.stored)).map_err(|_| "the stored file isn't valid".to_owned())?;
    let mine: Json = serde_json::from_str(text).map_err(|_| "this profile's file isn't valid JSON".to_owned())?;
    let same_minor = major_minor(m.stored_version).is_some() && major_minor(m.stored_version) == major_minor(m.current_version);
    if adapter.mod_id == "entityculling" && mine.get("configVersion") != stored.get("configVersion") {
        return Err("EntityCulling's config versions differ".into());
    }
    let mut changes = Vec::new();
    for leaf in jsonedit::leaves(text)? {
        let path = &leaf.path;
        let key = path.join(".");
        let skip = match adapter.mod_id {
            "sodium" => path.first().is_some_and(|p| p == "notifications"),
            "immediatelyfast" => key.ends_with("_INFO"),
            "entityculling" => key == "configVersion",
            _ => false,
        };
        if skip {
            continue;
        }
        let (Some(want), Some(have)) = (jsonedit::get(&stored, path), jsonedit::get(&mine, path)) else { continue };
        if want == have || kind(want) != kind(have) || want.is_null() {
            continue;
        }
        // Sodium's strings are enum constants; another minor version may not have them.
        if adapter.mod_id == "sodium" && want.is_string() && !same_minor {
            continue;
        }
        changes.push((path.clone(), want.clone()));
    }
    if changes.is_empty() {
        return Ok(None);
    }
    jsonedit::replace(text, &changes).map(|t| Some(t.into_bytes()))
}

/// A key-wise properties merge: keys both files have (for Lithium, keys its jar lists, which may be added),
/// booleans only for the mixin options; Iris's shader selection keys are left alone.
fn merge_props(adapter: &Adapter, m: &Merge, current: &[u8]) -> Result<Option<Vec<u8>>, String> {
    let stored = PropsDoc::parse(m.stored).map_err(|_| "the stored file isn't valid".to_owned())?;
    let mut doc = PropsDoc::parse(current).map_err(|_| "this profile's file isn't UTF-8".to_owned())?;
    let have = doc.entries();
    let mut changed = false;
    for (key, value) in stored.entries() {
        let boolean = value == "true" || value == "false";
        let allowed = match adapter.mod_id {
            "lithium" => boolean && (have.contains_key(&key) || m.lithium_keys.is_some_and(|keys| keys.contains(&key))),
            "ferritecore" => boolean && have.contains_key(&key),
            "iris" => !IRIS_SELECTION.contains(&key.as_str()) && have.contains_key(&key),
            _ => have.contains_key(&key),
        };
        if allowed && have.get(&key) != Some(&value) {
            doc.set(&key, &value);
            changed = true;
        }
    }
    Ok(changed.then(|| doc.to_string().into_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;

    const SODIUM: &[u8] = include_bytes!("../testdata/sodium-options.json");

    fn mods(list: &[(&str, &str)]) -> BTreeMap<String, String> {
        list.iter().map(|(a, b)| (a.to_string(), b.to_string())).collect()
    }

    fn merge<'a>(rel: &'a str, stored: &'a [u8], sv: &'a str, current: Option<&'a [u8]>, cv: &'a str) -> Outcome {
        bring(&Merge { rel, stored, stored_version: sv, current, current_version: cv, lithium_keys: None })
    }

    #[test]
    fn files_are_attributed_only_to_installed_mods_and_never_when_sensitive() {
        let installed = mods(&[("sodium", "0.9.2+mc26.3"), ("xaero", "1.0"), ("authme", "2.0"), ("lithium", "0.26.2")]);
        assert_eq!(attribute("sodium-options.json", &installed), Some(("sodium".into(), Class::Portable)));
        assert_eq!(attribute("lithium.properties", &installed), Some(("lithium".into(), Class::Portable)));
        assert_eq!(attribute("xaero.toml", &installed), Some(("xaero".into(), Class::SameVersionVerbatim)));
        assert_eq!(attribute("xaero/minimap.txt", &installed), Some(("xaero".into(), Class::SameVersionVerbatim)));
        assert_eq!(attribute("authme.json", &installed), None, "sensitive by name");
        assert_eq!(attribute("xaero/session-cache.json", &installed), None);
        assert_eq!(attribute("xaero/Login.txt", &installed), None);
        assert_eq!(attribute("sodium-fingerprint.json", &installed), None, "not attributable");
        assert_eq!(attribute("sodium-mixins.properties", &installed), None);
        assert_eq!(attribute("transition.json", &installed), None, "a bundled library, not an installed mod");
        assert_eq!(attribute("iris.properties", &installed), None, "Iris isn't installed");
        assert_eq!(attribute("flint-performance.json", &installed), None);
        assert_eq!(attribute("flintclient/control-center.json", &installed), None);
        assert_eq!(attribute("xaero.json.tmp", &installed), None);
        assert_eq!(attribute("xaero/.hidden.json", &installed), None);
        assert_eq!(attribute("unknownmod.json", &installed), None);
        assert!(sensitive("my_Secret_Settings.json"));
    }

    #[test]
    fn each_file_has_one_class_where_it_would_go() {
        let here = mods(&[("sodium", "0.9.1"), ("xaero", "1.0")]);
        assert_eq!(class_at("sodium-options.json", "sodium", "0.9.2", &here), Class::Portable);
        assert_eq!(class_at("xaero.toml", "xaero", "1.0", &here), Class::SameVersionVerbatim);
        assert_eq!(class_at("xaero.toml", "xaero", "0.9", &here), Class::StoredOnly, "another version");
        assert_eq!(class_at("journeymap.toml", "journeymap", "5.0", &here), Class::StoredOnly, "not installed here");
        assert_eq!(class_at("xaero/session.json", "xaero", "1.0", &here), Class::Never);
        assert_eq!(class_at("sodium.toml", "xaero", "1.0", &here), Class::Never, "not that mod's file");
    }

    #[test]
    fn versions_compare_by_major_and_minor() {
        assert_eq!(major_minor("0.9.2+mc26.3"), Some("0.9".into()));
        assert_eq!(major_minor("0.9.1+mc26.2"), Some("0.9".into()));
        assert_eq!(major_minor("3.11.10"), Some("3.11".into()));
        assert_eq!(major_minor("9"), Some("9.0".into()));
        assert_eq!(major_minor("beta"), None);
    }

    #[test]
    fn sodium_merges_key_by_key_and_keeps_everything_else() {
        let stored = String::from_utf8(SODIUM.to_vec()).unwrap().replace("\"chunk_builder_threads\": 0", "\"chunk_builder_threads\": 3").replace("\"NEAREST\"", "\"LINEAR\"").replace("\"has_seen_donation_prompt\": false", "\"has_seen_donation_prompt\": true");
        // The destination's Sodium has an extra key of its own.
        let current = String::from_utf8(SODIUM.to_vec()).unwrap().replace("\"use_fog_occlusion\": true,", "\"use_fog_occlusion\": true,\n    \"future_option\": 5,");
        let Outcome::Write(out) = merge("sodium-options.json", stored.as_bytes(), "0.9.2+mc26.3", Some(current.as_bytes()), "0.9.1+mc26.2") else { panic!("merged") };
        let out = String::from_utf8(out).unwrap();
        assert!(out.contains("\"chunk_builder_threads\": 3"));
        assert!(out.contains("\"LINEAR\""), "same major.minor: enum strings move");
        assert!(out.contains("\"future_option\": 5"), "unknown keys stay");
        assert!(out.contains("\"has_seen_donation_prompt\": false"), "notifications never move");
        // Another minor version: numbers and booleans move, enum names don't.
        let Outcome::Write(out) = merge("sodium-options.json", stored.as_bytes(), "0.9.2+mc26.3", Some(current.as_bytes()), "0.8.0+mc26.1") else { panic!("merged") };
        let out = String::from_utf8(out).unwrap();
        assert!(out.contains("\"chunk_builder_threads\": 3") && out.contains("\"NEAREST\""));
        // The same file again is nothing to do; the same version is copied whole.
        assert_eq!(merge("sodium-options.json", SODIUM, "0.9.2", Some(SODIUM), "0.9.1"), Outcome::Same);
        assert_eq!(merge("sodium-options.json", stored.as_bytes(), "0.9.2+mc26.3", Some(current.as_bytes()), "0.9.2+mc26.3"), Outcome::Write(stored.clone().into_bytes()));
    }

    #[test]
    fn a_missing_file_is_created_only_where_the_adapter_allows() {
        assert_eq!(merge("sodium-options.json", SODIUM, "0.9.2+mc26.3", None, "0.9.1+mc26.2"), Outcome::Write(SODIUM.to_vec()));
        assert!(matches!(merge("sodium-options.json", SODIUM, "0.9.2+mc26.3", None, "0.8.0+mc26.1"), Outcome::Wait(_)));
        assert!(matches!(merge("immediatelyfast.json", b"{}", "1.17.1", None, "1.17.0"), Outcome::Wait(_)));
        assert_eq!(merge("immediatelyfast.json", b"{}", "1.17.1", None, "1.17.1"), Outcome::Write(b"{}".to_vec()));
    }

    #[test]
    fn other_mods_copy_only_between_the_same_version() {
        assert_eq!(merge("xaero/minimap.txt", b"zoom=2", "1.0", Some(b"zoom=1"), "1.0"), Outcome::Write(b"zoom=2".to_vec()));
        assert!(matches!(merge("xaero/minimap.txt", b"zoom=2", "1.0", Some(b"zoom=1"), "1.1"), Outcome::Wait(_)));
        assert!(matches!(merge("xaero.json", b"{ broken", "1.0", Some(b"{}"), "1.0"), Outcome::Wait(_)), "an invalid file is refused");
    }

    #[test]
    fn properties_merge_by_key_with_each_mod_s_rules() {
        let ferrite_current = b"# c\nreplaceNeighborLookup = true\ncompactFastMap = false\n";
        let ferrite_stored = b"replaceNeighborLookup = false\ncompactFastMap = false\nunknownKey = true\n";
        let Outcome::Write(out) = merge("ferritecore.mixin.properties", ferrite_stored, "9.0.0", Some(ferrite_current), "8.0.0") else { panic!() };
        assert_eq!(String::from_utf8(out).unwrap(), "# c\nreplaceNeighborLookup=false\ncompactFastMap = false\n");
        let keys = vec!["mixin.ai".to_owned(), "mixin.gen".to_owned()];
        let m = Merge { rel: "lithium.properties", stored: b"mixin.ai=false\nmixin.gone=false\nmixin.gen=maybe\n", stored_version: "0.26.2", current: Some(b"# Lithium\n"), current_version: "0.25.0", lithium_keys: Some(&keys) };
        let Outcome::Write(out) = bring(&m) else { panic!() };
        assert_eq!(String::from_utf8(out).unwrap(), "# Lithium\nmixin.ai=false\n", "only valid keys, only booleans");
        let Outcome::Write(out) = merge("iris.properties", b"shaderPack=A.zip\nenableShaders=true\nmaxShadowRenderDistance=16\n", "1.9", Some(b"shaderPack=B.zip\nenableShaders=false\nmaxShadowRenderDistance=32\n"), "1.8") else { panic!() };
        assert_eq!(String::from_utf8(out).unwrap(), "shaderPack=B.zip\nenableShaders=false\nmaxShadowRenderDistance=16\n");
    }

    #[test]
    fn fingerprints_ignore_spelling_but_not_meaning() {
        assert_eq!(fingerprint(Format::Json, b"{\"a\":1,\"b\":true}"), fingerprint(Format::Json, b"{\n  \"b\": true,\n  \"a\": 1\n}\n"));
        assert_ne!(fingerprint(Format::Json, b"{\"a\":1}"), fingerprint(Format::Json, b"{\"a\":2}"));
        assert_eq!(fingerprint(Format::Properties, b"# x\na = 1\n"), fingerprint(Format::Properties, b"a=1"));
        assert_ne!(fingerprint(Format::Other, b"a"), fingerprint(Format::Other, b"a "));
    }
}
