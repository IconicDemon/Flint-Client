//! Flint's own settings in a profile's `config/` folder, which the in-game runtime reads once at start:
//! HUD layouts (`config/flintclient/layouts/<id>.json`, each self-contained), the Control Center's
//! preferences (`config/flintclient/control-center.json`) and the server shortcuts
//! (`config/flintclient.json`). `flint-performance.json` is derived per machine at every launch and is never
//! copied, and neither is anything named `.flint-*`.
//!
//! The rules follow the runtime's own store (JsonStore): a file that isn't a JSON object is damaged and is
//! never replaced; a file whose `schema` is newer than the copy being written, or isn't a whole number from
//! 1 up, is never written; keys Flint doesn't sync are kept.

use super::plan::{ChangeKind, FileWrite, PlanChange, PlanSkip};
use super::registry::SyncCategory;
use serde_json::{Map, Value as Json};
use std::collections::HashSet;
use std::path::Path;

pub const LAYOUTS_DIR: &str = "config/flintclient/layouts";
pub const CONTROL_CENTER: &str = "config/flintclient/control-center.json";
pub const SHORTCUTS: &str = "config/flintclient.json";
/// More layout files than anyone keeps; a folder past this isn't read.
const MAX_LAYOUTS: usize = 500;
const MAX_FILE: u64 = 4 * 1024 * 1024;

/// A Flint JSON file as found on disk.
#[derive(Clone, Debug, PartialEq)]
pub enum JsonFile {
    Missing,
    Ok { root: Map<String, Json>, bytes: Vec<u8> },
    /// Not UTF-8, or not a JSON object: the runtime keeps a copy aside and uses defaults.
    Damaged(String),
    /// Couldn't be read at all.
    Unreadable(String),
}

impl JsonFile {
    pub fn parse(bytes: Vec<u8>) -> JsonFile {
        let text = bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(&bytes);
        let Ok(text) = std::str::from_utf8(text) else { return JsonFile::Damaged("isn't readable text".into()) };
        match serde_json::from_str::<Json>(text) {
            Ok(Json::Object(root)) => JsonFile::Ok { root, bytes },
            _ => JsonFile::Damaged("isn't valid settings data".into()),
        }
    }

    pub fn read(path: &Path) -> (JsonFile, Option<Vec<u8>>) {
        match std::fs::metadata(path) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return (JsonFile::Missing, None),
            Ok(meta) if meta.len() > MAX_FILE => return (JsonFile::Unreadable("is too large".into()), None),
            _ => {}
        }
        match std::fs::read(path) {
            Ok(bytes) => (JsonFile::parse(bytes.clone()), Some(bytes)),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => (JsonFile::Missing, None),
            Err(e) => (JsonFile::Unreadable(format!("couldn't be read ({e})")), None),
        }
    }

    fn root(&self) -> Option<&Map<String, Json>> {
        match self {
            JsonFile::Ok { root, .. } => Some(root),
            _ => None,
        }
    }

    fn schema(&self) -> Option<Schema> {
        self.root().map(schema_of)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Schema {
    Known(i64),
    /// Present but not a whole number from 1 up: not ours to rewrite.
    Unrecognised,
}

/// JsonStore.schemaOf: a missing schema is 1; a number (or a number written as text) must be a whole
/// number from 1 up; anything else is unrecognised.
pub fn schema_of(root: &Map<String, Json>) -> Schema {
    let number = match root.get("schema") {
        None | Some(Json::Null) => return Schema::Known(1),
        Some(Json::Number(n)) => n.as_f64(),
        Some(Json::String(s)) => s.trim().parse::<f64>().ok(),
        Some(_) => None,
    };
    match number {
        Some(n) if n >= 1.0 && n == n.trunc() => Schema::Known(if n >= i32::MAX as f64 { i32::MAX as i64 } else { n as i64 }),
        _ => Schema::Unrecognised,
    }
}

/// The runtime's layout ids (LayoutNames.ID): kept copies like `x.damaged-….json` and temporary files never match.
pub fn valid_layout_id(id: &str) -> bool {
    (1..=40).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

/// Flint's files in one profile.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct FlintDocs {
    /// Layouts by id, sorted.
    pub layouts: Vec<(String, JsonFile)>,
    pub control_center: JsonFileOrMissing,
    pub shortcuts: JsonFileOrMissing,
}

/// `JsonFile` with a default, so `FlintDocs` can derive one.
#[derive(Clone, Debug, PartialEq)]
pub struct JsonFileOrMissing(pub JsonFile);

impl Default for JsonFileOrMissing {
    fn default() -> Self {
        Self(JsonFile::Missing)
    }
}

/// Files read for a plan, by path relative to the profile folder, with their bytes (None when missing).
pub type Seen = Vec<(String, Option<Vec<u8>>)>;

/// Reads Flint's files under `profile_dir`; also returns what was read, for the plan's fingerprint.
pub fn load(profile_dir: &Path) -> (FlintDocs, Seen) {
    let mut seen = Vec::new();
    let mut docs = FlintDocs::default();
    if let Ok(entries) = std::fs::read_dir(profile_dir.join(LAYOUTS_DIR)) {
        let mut ids: Vec<String> = entries
            .flatten()
            .filter(|e| e.file_type().is_ok_and(|t| t.is_file()))
            .filter_map(|e| e.file_name().into_string().ok())
            .filter_map(|name| name.strip_suffix(".json").map(str::to_owned))
            .filter(|id| valid_layout_id(id))
            .collect();
        ids.sort();
        ids.truncate(MAX_LAYOUTS);
        for id in ids {
            let rel = layout_path(&id);
            let (file, bytes) = JsonFile::read(&profile_dir.join(&rel));
            seen.push((rel, bytes));
            docs.layouts.push((id, file));
        }
    }
    for (rel, slot) in [(CONTROL_CENTER, &mut docs.control_center), (SHORTCUTS, &mut docs.shortcuts)] {
        let (file, bytes) = JsonFile::read(&profile_dir.join(rel));
        seen.push((rel.to_owned(), bytes));
        slot.0 = file;
    }
    (docs, seen)
}

pub fn layout_path(id: &str) -> String {
    format!("{LAYOUTS_DIR}/{id}.json")
}

/// What copying Flint's files would change in one destination.
#[derive(Default)]
pub struct FlintPlan {
    pub changes: Vec<PlanChange>,
    pub skipped: Vec<PlanSkip>,
    pub unchanged: usize,
    pub writes: Vec<FileWrite>,
}

/// Plans the chosen Flint categories from `source` into `target`. `excluded` holds setting ids the person
/// left out.
pub fn plan(source: &FlintDocs, source_name: &str, target: &FlintDocs, categories: &[SyncCategory], excluded: &HashSet<String>) -> FlintPlan {
    let mut out = FlintPlan::default();
    let layouts = categories.contains(&SyncCategory::HudLayouts);
    let prefs = categories.contains(&SyncCategory::ControlCenter);
    if layouts {
        plan_layouts(source, source_name, target, excluded, &mut out);
    }
    if layouts || prefs {
        plan_control_center(source, source_name, target, layouts, prefs, excluded, &mut out);
    }
    if categories.contains(&SyncCategory::ServerShortcuts) {
        plan_shortcuts(source, source_name, target, excluded, &mut out);
    }
    out
}

fn skip(out: &mut FlintPlan, id: Option<String>, label: impl Into<String>, category: SyncCategory, reason: impl Into<String>) {
    out.skipped.push(PlanSkip { setting_id: id, label: label.into(), category, reason: reason.into() });
}

fn left_out(excluded: &HashSet<String>, id: &str, out: &mut FlintPlan, label: &str, category: SyncCategory) -> bool {
    if excluded.contains(id) {
        skip(out, Some(id.to_owned()), label, category, "You left this out.");
        return true;
    }
    false
}

fn layout_name(id: &str, file: &JsonFile) -> String {
    let name = file.root().and_then(|r| r.get("name")).and_then(Json::as_str).map(str::trim).filter(|n| !n.is_empty());
    format!("Layout “{}”", name.unwrap_or(id))
}

fn plan_layouts(source: &FlintDocs, source_name: &str, target: &FlintDocs, excluded: &HashSet<String>, out: &mut FlintPlan) {
    let category = SyncCategory::HudLayouts;
    if source.layouts.is_empty() {
        skip(out, None, "HUD layouts", category, format!("{source_name} has no HUD layouts yet."));
        return;
    }
    for (id, file) in &source.layouts {
        let setting_id = format!("hudLayouts.{id}");
        let label = layout_name(id, file);
        let JsonFile::Ok { bytes, .. } = file else {
            skip(out, Some(setting_id), label, category, format!("The copy in {source_name} is damaged."));
            continue;
        };
        let Some(Schema::Known(schema)) = file.schema() else {
            skip(out, Some(setting_id), label, category, format!("The copy in {source_name} has a version number Flint doesn't recognise."));
            continue;
        };
        if left_out(excluded, &setting_id, out, &label, category) {
            continue;
        }
        let existing = target.layouts.iter().find(|(i, _)| i == id).map(|(_, f)| f);
        let kind = match existing {
            None | Some(JsonFile::Missing) => ChangeKind::Add,
            Some(JsonFile::Damaged(_) | JsonFile::Unreadable(_)) => {
                skip(out, Some(setting_id), label, category, "The copy in this profile is damaged; Flint won't replace it.");
                continue;
            }
            Some(other @ JsonFile::Ok { bytes: current, .. }) => {
                if current == bytes {
                    out.unchanged += 1;
                    continue;
                }
                match other.schema() {
                    Some(Schema::Known(theirs)) if theirs <= schema => ChangeKind::Change,
                    _ => {
                        skip(out, Some(setting_id), label, category, "The copy in this profile was saved by a newer Flint; Flint won't replace it.");
                        continue;
                    }
                }
            }
        };
        out.changes.push(PlanChange {
            setting_id,
            label,
            category,
            current: if kind == ChangeKind::Add { "Not in this profile".into() } else { "This profile's version".into() },
            proposed: format!("{source_name}'s version"),
            kind,
            note: None,
            current_raw: None,
            proposed_raw: None,
        });
        out.writes.push(FileWrite::new(layout_path(id), bytes.clone()));
    }
}

/// The Control Center preferences that are the player's choices rather than this device's history:
/// (path, label). `library.recents` and `onboarding.hintShown` stay per profile.
const PREFS: &[(&str, &str, &str)] = &[
    ("sounds", "enabled", "Interface sounds"),
    ("sounds", "volume", "Interface volume"),
    ("motion", "animations", "Animations"),
    ("motion", "followMinecraftReducedMotion", "Follow Minecraft's reduced motion"),
    ("accessibility", "interfaceSize", "Interface size"),
    ("accessibility", "highContrast", "High-contrast outlines"),
    ("hud", "showWithDebugScreen", "Show the HUD with the debug screen (F3)"),
    ("library", "favorites", "Favourite modules"),
    ("onboarding", "completed", "First-time setup finished"),
];
/// Sections copied whole, key by key (a key the source has that isn't in `PREFS` is copied too).
const WHOLE_SECTIONS: &[&str] = &["sounds", "motion", "accessibility", "hud"];

fn show(value: Option<&Json>, section: &str, key: &str) -> String {
    match value {
        None | Some(Json::Null) => "Not set".into(),
        Some(Json::Bool(b)) => (if *b { "On" } else { "Off" }).into(),
        Some(Json::Number(n)) if section == "sounds" && key == "volume" => format!("{}%", (n.as_f64().unwrap_or(0.0) * 100.0).round() as i64),
        Some(Json::String(s)) => s.clone(),
        Some(Json::Array(items)) if items.is_empty() => "None".into(),
        Some(Json::Array(items)) => items.iter().map(|i| i.as_str().map(str::to_owned).unwrap_or_else(|| i.to_string())).collect::<Vec<_>>().join(", "),
        Some(other) => other.to_string(),
    }
}

fn plan_control_center(source: &FlintDocs, source_name: &str, target: &FlintDocs, layouts: bool, prefs: bool, excluded: &HashSet<String>, out: &mut FlintPlan) {
    let category = SyncCategory::ControlCenter;
    let src = &source.control_center.0;
    let dst = &target.control_center.0;
    let refuse = |out: &mut FlintPlan, reason: String| {
        if prefs {
            skip(out, None, "Control Center preferences", category, reason.clone());
        }
        if layouts {
            skip(out, Some("hudLayouts.activeLayout".into()), "Active layout", SyncCategory::HudLayouts, reason);
        }
    };
    let Some(src_root) = src.root() else {
        match src {
            // Without the file there is no active layout to copy either, so only the preferences say so.
            JsonFile::Missing if prefs => skip(out, None, "Control Center preferences", category, format!("{source_name} has no Control Center preferences yet.")),
            JsonFile::Missing => {}
            _ => refuse(out, format!("{source_name}'s Control Center preferences can't be read.")),
        }
        return;
    };
    let Some(Schema::Known(src_schema)) = src.schema() else {
        refuse(out, format!("{source_name}'s Control Center preferences have a version number Flint doesn't recognise."));
        return;
    };
    let mut root = match dst {
        JsonFile::Missing => {
            let mut fresh = Map::new();
            fresh.insert("schema".into(), Json::from(src_schema));
            fresh
        }
        JsonFile::Ok { root, .. } => match dst.schema() {
            Some(Schema::Known(theirs)) if theirs == src_schema => root.clone(),
            Some(Schema::Known(theirs)) if theirs < src_schema => {
                refuse(out, format!("{source_name}'s Control Center preferences were saved by a newer Flint than this profile's."));
                return;
            }
            _ => {
                refuse(out, "This profile's Control Center preferences were saved by a newer Flint; Flint won't change them.".into());
                return;
            }
        },
        JsonFile::Damaged(_) | JsonFile::Unreadable(_) => {
            refuse(out, "This profile's Control Center preferences are damaged; Flint won't replace them.".into());
            return;
        }
    };
    let before = root.clone();
    let mut changed = false;
    if prefs {
        let mut paths: Vec<(String, String, String)> = PREFS.iter().map(|(s, k, l)| (s.to_string(), k.to_string(), l.to_string())).collect();
        for section in WHOLE_SECTIONS {
            if let Some(Json::Object(fields)) = src_root.get(*section) {
                for key in fields.keys() {
                    if !paths.iter().any(|(s, k, _)| s == section && k == key) {
                        paths.push((section.to_string(), key.clone(), format!("{section}.{key}")));
                    }
                }
            }
        }
        for (section, key, label) in paths {
            let Some(value) = src_root.get(&section).and_then(|s| s.get(&key)) else { continue };
            let id = format!("controlCenter.{section}.{key}");
            let current = before.get(&section).and_then(|s| s.get(&key));
            if current == Some(value) {
                out.unchanged += 1;
                continue;
            }
            if left_out(excluded, &id, out, &label, category) {
                continue;
            }
            let entry = root.entry(section.clone()).or_insert_with(|| Json::Object(Map::new()));
            if !entry.is_object() {
                *entry = Json::Object(Map::new());
            }
            entry.as_object_mut().expect("just made an object").insert(key.clone(), value.clone());
            out.changes.push(PlanChange {
                setting_id: id,
                label,
                category,
                current: show(current, &section, &key),
                proposed: show(Some(value), &section, &key),
                kind: if current.is_some() { ChangeKind::Change } else { ChangeKind::Add },
                note: None,
                current_raw: None,
                proposed_raw: None,
            });
            changed = true;
        }
    }
    if layouts {
        // The active layout follows the layouts, and only when that layout exists here after the copy.
        if let Some(active) = src_root.get("activeLayout").and_then(Json::as_str).filter(|id| valid_layout_id(id)) {
            let id = "hudLayouts.activeLayout";
            let exists = target.layouts.iter().any(|(i, f)| i == active && matches!(f, JsonFile::Ok { .. })) || out.writes.iter().any(|w| w.rel == layout_path(active));
            let current = before.get("activeLayout");
            if current.and_then(Json::as_str) == Some(active) {
                out.unchanged += 1;
            } else if !exists {
                skip(out, Some(id.into()), "Active layout", SyncCategory::HudLayouts, format!("The layout “{active}” isn't in this profile."));
            } else if !left_out(excluded, id, out, "Active layout", SyncCategory::HudLayouts) {
                root.insert("activeLayout".into(), Json::String(active.to_owned()));
                out.changes.push(PlanChange {
                    setting_id: id.into(),
                    label: "Active layout".into(),
                    category: SyncCategory::HudLayouts,
                    current: current.map(|c| show(Some(c), "", "")).unwrap_or_else(|| "Not set".into()),
                    proposed: active.to_owned(),
                    kind: if current.is_some() { ChangeKind::Change } else { ChangeKind::Add },
                    note: None,
                    current_raw: None,
                    proposed_raw: None,
                });
                changed = true;
            }
        }
    }
    if changed {
        out.writes.push(FileWrite::new(CONTROL_CENTER.into(), pretty(&root)));
    }
}

/// Stable formatting for files Flint rewrites: two-space JSON with a final newline, as the runtime writes.
pub fn pretty(root: &Map<String, Json>) -> Vec<u8> {
    let mut bytes = serde_json::to_vec_pretty(root).expect("a JSON object always serialises");
    bytes.push(b'\n');
    bytes
}

/// The shortcut slots' names (None for an empty slot), or None when the file has no shortcut list.
fn shortcut_names(file: &JsonFile) -> Option<Vec<Option<String>>> {
    let list = file.root()?.get("shortcuts")?.as_array()?;
    Some(
        list.iter()
            .map(|slot| {
                let slot = slot.as_object()?;
                let address = slot.get("address")?.as_str()?;
                Some(slot.get("name").and_then(Json::as_str).unwrap_or(address).to_owned())
            })
            .collect(),
    )
}

fn show_shortcuts(names: &[Option<String>]) -> String {
    let named: Vec<&str> = names.iter().flatten().map(String::as_str).collect();
    if named.is_empty() { "None".into() } else { named.join(", ") }
}

fn plan_shortcuts(source: &FlintDocs, source_name: &str, target: &FlintDocs, excluded: &HashSet<String>, out: &mut FlintPlan) {
    let category = SyncCategory::ServerShortcuts;
    let id = "serverShortcuts";
    let label = "Server shortcuts";
    let src = &source.shortcuts.0;
    let Some(list) = src.root().and_then(|r| r.get("shortcuts")).filter(|l| l.is_array()) else {
        let reason = match src {
            JsonFile::Damaged(_) | JsonFile::Unreadable(_) => format!("{source_name}'s server shortcuts can't be read."),
            _ => format!("{source_name} has no server shortcuts yet."),
        };
        skip(out, Some(id.into()), label, category, reason);
        return;
    };
    let mut root = match &target.shortcuts.0 {
        JsonFile::Missing => Map::new(),
        JsonFile::Ok { root, .. } => root.clone(),
        JsonFile::Damaged(_) | JsonFile::Unreadable(_) => {
            skip(out, Some(id.into()), label, category, "This profile's server shortcuts file is damaged; Flint won't replace it.");
            return;
        }
    };
    let current = root.get("shortcuts").cloned();
    if current.as_ref() == Some(list) {
        out.unchanged += 1;
        return;
    }
    if left_out(excluded, id, out, label, category) {
        return;
    }
    // FlintConfig keeps only `shortcuts`, but anything else already in the file stays as found.
    root.insert("shortcuts".into(), list.clone());
    let names = |file: &JsonFile| shortcut_names(file).map(|n| show_shortcuts(&n)).unwrap_or_else(|| "None".into());
    out.changes.push(PlanChange {
        setting_id: id.into(),
        label: label.into(),
        category,
        current: names(&target.shortcuts.0),
        proposed: names(src),
        kind: if current.is_some() { ChangeKind::Change } else { ChangeKind::Add },
        note: None,
        current_raw: None,
        proposed_raw: None,
    });
    out.writes.push(FileWrite::new(SHORTCUTS.into(), pretty(&root)));
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn file(value: Json) -> JsonFile {
        JsonFile::parse(serde_json::to_vec_pretty(&value).unwrap())
    }

    fn layout(id: &str, value: Json) -> (String, JsonFile) {
        (id.to_owned(), file(value))
    }

    fn docs(layouts: Vec<(String, JsonFile)>, cc: JsonFile, shortcuts: JsonFile) -> FlintDocs {
        FlintDocs { layouts, control_center: JsonFileOrMissing(cc), shortcuts: JsonFileOrMissing(shortcuts) }
    }

    fn none() -> HashSet<String> {
        HashSet::new()
    }

    #[test]
    fn layouts_are_copied_byte_for_byte() {
        let raw = b"{\n  \"schema\": 1,\n  \"id\": \"pvp\",\n  \"name\": \"PvP\",\n  \"modules\": {}\n}\n".to_vec();
        let source = docs(vec![("pvp".into(), JsonFile::parse(raw.clone()))], JsonFile::Missing, JsonFile::Missing);
        let target = docs(vec![], JsonFile::Missing, JsonFile::Missing);
        let plan = plan(&source, "Main", &target, &[SyncCategory::HudLayouts], &none());
        assert_eq!(plan.writes.len(), 1);
        assert_eq!(plan.writes[0].rel, "config/flintclient/layouts/pvp.json");
        assert_eq!(plan.writes[0].bytes, raw);
        assert_eq!(plan.changes[0].label, "Layout “PvP”");
        assert_eq!(plan.changes[0].kind, ChangeKind::Add);
    }

    #[test]
    fn an_identical_layout_is_unchanged() {
        let l = layout("a", json!({ "schema": 1, "id": "a" }));
        let source = docs(vec![l.clone()], JsonFile::Missing, JsonFile::Missing);
        let target = docs(vec![l], JsonFile::Missing, JsonFile::Missing);
        let plan = plan(&source, "Main", &target, &[SyncCategory::HudLayouts], &none());
        assert!(plan.writes.is_empty());
        assert_eq!(plan.unchanged, 1);
    }

    #[test]
    fn a_newer_or_damaged_layout_in_the_destination_is_never_replaced() {
        let source = docs(vec![layout("a", json!({ "schema": 1, "id": "a" })), layout("b", json!({ "schema": 1, "id": "b" }))], JsonFile::Missing, JsonFile::Missing);
        let target = docs(vec![layout("a", json!({ "schema": 2, "id": "a" })), ("b".into(), JsonFile::parse(b"{ not json".to_vec()))], JsonFile::Missing, JsonFile::Missing);
        let plan = plan(&source, "Main", &target, &[SyncCategory::HudLayouts], &none());
        assert!(plan.writes.is_empty());
        let reasons: Vec<&str> = plan.skipped.iter().map(|s| s.reason.as_str()).collect();
        assert!(reasons[0].contains("newer Flint"));
        assert!(reasons[1].contains("damaged"));
    }

    #[test]
    fn a_damaged_or_unrecognised_layout_in_the_source_is_skipped() {
        let source = docs(vec![("a".into(), JsonFile::parse(b"[]".to_vec())), layout("b", json!({ "schema": "two" }))], JsonFile::Missing, JsonFile::Missing);
        let target = docs(vec![], JsonFile::Missing, JsonFile::Missing);
        let plan = plan(&source, "Main", &target, &[SyncCategory::HudLayouts], &none());
        assert!(plan.writes.is_empty());
        assert_eq!(plan.skipped.len(), 2);
    }

    #[test]
    fn schema_is_read_like_the_runtime() {
        let schema = |v: Json| schema_of(v.as_object().unwrap());
        assert_eq!(schema(json!({})), Schema::Known(1));
        assert_eq!(schema(json!({ "schema": null })), Schema::Known(1));
        assert_eq!(schema(json!({ "schema": 2 })), Schema::Known(2));
        assert_eq!(schema(json!({ "schema": " 3 " })), Schema::Known(3));
        assert_eq!(schema(json!({ "schema": 1.5 })), Schema::Unrecognised);
        assert_eq!(schema(json!({ "schema": 0 })), Schema::Unrecognised);
        assert_eq!(schema(json!({ "schema": [1] })), Schema::Unrecognised);
    }

    #[test]
    fn only_id_shaped_layout_names_count() {
        assert!(valid_layout_id("pvp-2"));
        assert!(!valid_layout_id("pvp.damaged-20261015-101010"));
        assert!(!valid_layout_id("PvP"));
        assert!(!valid_layout_id(""));
        assert!(!valid_layout_id(&"a".repeat(41)));
    }

    #[test]
    fn control_center_merge_keeps_every_other_key() {
        let source = docs(vec![], file(json!({
            "schema": 1,
            "sounds": { "enabled": false, "volume": 0.3 },
            "motion": { "animations": false },
            "library": { "favorites": ["fps", "coords"], "recents": ["fps"] },
            "onboarding": { "completed": true, "hintShown": true },
            "activeLayout": "pvp"
        })), JsonFile::Missing);
        let target = docs(vec![], file(json!({
            "schema": 1,
            "sounds": { "enabled": true, "volume": 0.6, "future": 1 },
            "library": { "favorites": [], "recents": ["clock"] },
            "onboarding": { "completed": false, "hintShown": false },
            "somethingNewer": { "kept": true }
        })), JsonFile::Missing);
        let plan = plan(&source, "Main", &target, &[SyncCategory::ControlCenter], &none());
        assert_eq!(plan.writes.len(), 1);
        let written: Json = serde_json::from_slice(&plan.writes[0].bytes).unwrap();
        assert_eq!(written["sounds"], json!({ "enabled": false, "volume": 0.3, "future": 1 }));
        assert_eq!(written["motion"], json!({ "animations": false }));
        assert_eq!(written["library"], json!({ "favorites": ["fps", "coords"], "recents": ["clock"] }), "recents stay per profile");
        assert_eq!(written["onboarding"], json!({ "completed": true, "hintShown": false }));
        assert_eq!(written["somethingNewer"], json!({ "kept": true }));
        assert!(written.get("activeLayout").is_none(), "the active layout belongs to the layouts category");
        let labels: Vec<&str> = plan.changes.iter().map(|c| c.label.as_str()).collect();
        assert!(labels.contains(&"Interface volume"));
        let volume = plan.changes.iter().find(|c| c.label == "Interface volume").unwrap();
        assert_eq!((volume.current.as_str(), volume.proposed.as_str()), ("60%", "30%"));
        assert!(plan.writes[0].bytes.ends_with(b"}\n"));
    }

    #[test]
    fn control_center_from_a_newer_or_damaged_file_is_refused() {
        let source = docs(vec![], file(json!({ "schema": 1, "sounds": { "enabled": false } })), JsonFile::Missing);
        let newer = docs(vec![], file(json!({ "schema": 2 })), JsonFile::Missing);
        let unrecognised = docs(vec![], file(json!({ "schema": "v2" })), JsonFile::Missing);
        let damaged = docs(vec![], JsonFile::parse(b"{".to_vec()), JsonFile::Missing);
        for target in [newer, unrecognised, damaged] {
            let plan = plan(&source, "Main", &target, &[SyncCategory::ControlCenter], &none());
            assert!(plan.writes.is_empty());
            assert_eq!(plan.skipped.len(), 1);
        }
    }

    #[test]
    fn the_active_layout_is_copied_only_with_layouts_and_only_if_it_exists() {
        let cc = file(json!({ "schema": 1, "activeLayout": "pvp" }));
        let source = docs(vec![layout("pvp", json!({ "schema": 1, "id": "pvp" }))], cc.clone(), JsonFile::Missing);
        let target = docs(vec![], file(json!({ "schema": 1, "activeLayout": "default" })), JsonFile::Missing);
        // Layouts chosen: the layout is copied, so the active layout can follow it.
        let with = plan(&source, "Main", &target, &[SyncCategory::HudLayouts], &none());
        assert_eq!(with.writes.len(), 2);
        let written: Json = serde_json::from_slice(&with.writes[1].bytes).unwrap();
        assert_eq!(written["activeLayout"], "pvp");
        // Only Control Center preferences chosen: the active layout isn't touched.
        let without = plan(&source, "Main", &target, &[SyncCategory::ControlCenter], &none());
        assert!(without.writes.is_empty());
        // The layout left out: it won't exist, so the active layout isn't switched to it.
        let excluded: HashSet<String> = ["hudLayouts.pvp".to_owned()].into();
        let left_out = plan(&source, "Main", &target, &[SyncCategory::HudLayouts], &excluded);
        assert!(left_out.writes.is_empty());
        assert!(left_out.skipped.iter().any(|s| s.setting_id.as_deref() == Some("hudLayouts.activeLayout")));
    }

    #[test]
    fn shortcuts_are_copied_whole_and_other_keys_kept() {
        let source = docs(vec![], JsonFile::Missing, file(json!({ "shortcuts": [{ "name": "Hub", "address": "hub.example.net" }, null] })));
        let target = docs(vec![], JsonFile::Missing, file(json!({ "shortcuts": [], "extra": 1 })));
        let plan = plan(&source, "Main", &target, &[SyncCategory::ServerShortcuts], &none());
        let written: Json = serde_json::from_slice(&plan.writes[0].bytes).unwrap();
        assert_eq!(written, json!({ "shortcuts": [{ "name": "Hub", "address": "hub.example.net" }, null], "extra": 1 }));
        assert_eq!((plan.changes[0].current.as_str(), plan.changes[0].proposed.as_str()), ("None", "Hub"));
        // Into a profile without the file: just the list, as FlintConfig writes it.
        let empty = docs(vec![], JsonFile::Missing, JsonFile::Missing);
        let fresh = super::plan(&source, "Main", &empty, &[SyncCategory::ServerShortcuts], &none());
        let written: Json = serde_json::from_slice(&fresh.writes[0].bytes).unwrap();
        assert_eq!(written.as_object().unwrap().len(), 1);
    }

    #[test]
    fn nothing_is_copied_from_a_profile_without_the_files() {
        let empty = docs(vec![], JsonFile::Missing, JsonFile::Missing);
        let plan = plan(&empty, "Main", &empty, &[SyncCategory::HudLayouts, SyncCategory::ControlCenter, SyncCategory::ServerShortcuts], &none());
        assert!(plan.writes.is_empty() && plan.changes.is_empty());
        assert_eq!(plan.skipped.len(), 3, "layouts, preferences and shortcuts each explained");
    }

    #[test]
    fn loading_skips_kept_copies_and_temporary_files() {
        let dir = crate::core::TestDir::new();
        let layouts = dir.0.join(LAYOUTS_DIR);
        std::fs::create_dir_all(&layouts).unwrap();
        std::fs::write(layouts.join("pvp.json"), b"{\"schema\":1}").unwrap();
        std::fs::write(layouts.join("pvp.damaged-20261015-101010.json"), b"{").unwrap();
        std::fs::write(layouts.join("pvp.json.tmp"), b"{}").unwrap();
        std::fs::write(layouts.join(".pvp.json.flint-tmp"), b"{}").unwrap();
        let (docs, seen) = load(&dir.0);
        assert_eq!(docs.layouts.iter().map(|(id, _)| id.as_str()).collect::<Vec<_>>(), vec!["pvp"]);
        assert_eq!(seen.len(), 3, "one layout, control-center.json and flintclient.json");
    }
}
