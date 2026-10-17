//! What copying settings from one profile into another would change, decided from the files alone (no
//! I/O here, so every rule is unit-tested).
//!
//! Rules, per destination:
//! - A destination that is running, isn't a verified Minecraft version (whatever is being copied, Flint's own
//!   files included) or whose options.txt can't be trusted is blocked as a whole, with the reason.
//! - A setting the destination's version doesn't have, a key name it doesn't know, a numeric key code that
//!   means another key there (GLFW ↔ SDL3), a value it doesn't accept or a value the source's Minecraft
//!   couldn't read is skipped with the reason. Nothing is ever translated or invented.
//! - A mod's key binding is copied only into a profile whose options.txt already has it (the mod has run
//!   there); Flint's own keys only where Flint's in-game menu is installed or already left its lines.
//! - A value the destination already has counts as unchanged.
//! - Writing an option a graphics preset manages also makes the preset "custom", or Minecraft would undo it
//!   at the next start. A copied preset other than "custom" says which options Minecraft sets from it, and
//!   isn't copied when the person left one of those out (Minecraft would reset it at every start).

use super::flintfiles::{self, FlintDocs};
use super::options_txt::{Codec, OptionsDoc, Val};
use super::registry::{self, check_key, Bind, Category, KeyCheck, Repr, Setting, SyncCategory, KEY_PREFIX, UNBOUND};
use super::tables::{FamilyData, FAMILIES};
use super::versions::VersionSupport;
use serde::Serialize;
use std::collections::HashSet;

#[derive(Serialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum ChangeKind {
    Add,
    Change,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlanChange {
    pub setting_id: String,
    pub label: String,
    pub category: SyncCategory,
    pub current: String,
    pub proposed: String,
    pub kind: ChangeKind,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
    /// For options.txt: the text on the line now (None when it isn't there) and the text Flint writes.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub current_raw: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub proposed_raw: Option<String>,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlanSkip {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub setting_id: Option<String>,
    pub label: String,
    pub category: SyncCategory,
    pub reason: String,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TargetPlan {
    pub profile_id: String,
    pub profile_name: String,
    pub game_version: String,
    pub support: VersionSupport,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub blocked: Option<String>,
    pub changes: Vec<PlanChange>,
    pub skipped: Vec<PlanSkip>,
    pub unchanged_count: usize,
    pub untouched: String,
}

/// A file a plan writes, by its path relative to the profile folder.
#[derive(Clone, Debug, PartialEq)]
pub struct FileWrite {
    pub rel: String,
    pub bytes: Vec<u8>,
    /// For options.txt: each changed key and the exact text it must have once written.
    pub checks: Vec<(String, String)>,
}

impl FileWrite {
    pub fn new(rel: String, bytes: Vec<u8>) -> Self {
        Self { rel, bytes, checks: Vec::new() }
    }
}

/// What could be read of a profile's options.txt.
#[derive(Clone, Debug, PartialEq)]
pub enum OptionsFile {
    Missing,
    Ok(OptionsDoc),
    /// Not UTF-8 text, so Flint couldn't write it back exactly as it is; the reason is a whole sentence.
    Damaged(String),
    Unreadable(String),
}

/// Everything a plan needs to know about one profile.
#[derive(Clone, Debug)]
pub struct ProfileDocs {
    pub id: String,
    pub name: String,
    pub game_version: String,
    pub support: VersionSupport,
    pub options: OptionsFile,
    pub flint: FlintDocs,
    /// Flint's in-game menu is installed for this profile, so Flint's key lines are used there.
    pub runtime: bool,
}

/// One destination's plan, with the files it would write.
#[derive(Clone, Debug)]
pub struct TargetWork {
    pub plan: TargetPlan,
    pub writes: Vec<FileWrite>,
}

pub const PRESET_NOTE: &str = "Otherwise Minecraft's graphics preset would undo this change at launch.";
/// The forced Custom preset in a new options.txt, where nothing else is set yet.
const PRESET_NEW_FILE_NOTE: &str = "Otherwise Minecraft's graphics preset would undo this change at launch. The other options the preset controls start at Minecraft's own defaults rather than a preset's.";
pub const PRESET_KEY: &str = "graphicsPreset";
const LEFT_OUT: &str = "You left this out.";
const CATEGORY_ORDER: [Category; 8] =
    [Category::Controls, Category::Keybinds, Category::Video, Category::Audio, Category::Accessibility, Category::Chat, Category::Skin, Category::Language];

/// The options.txt Flint may change in `profile`: its own, or a new one for a verified version whose jar is
/// installed. Err says why it can't be written.
pub fn writable_options(profile: &ProfileDocs) -> Result<OptionsDoc, String> {
    let Some(family) = profile.support.family() else {
        return Err(not_verified(&profile.support));
    };
    match &profile.options {
        OptionsFile::Missing => match profile.support.data_version {
            Some(data) => Ok(OptionsDoc::new_file(data)),
            None => Err("Install or play this profile once first.".into()),
        },
        OptionsFile::Damaged(reason) => Err(reason.clone()),
        OptionsFile::Unreadable(reason) => Err(format!("{reason} Flint won't change it.")),
        OptionsFile::Ok(doc) => match doc.data_version() {
            None => Err("This profile's options.txt has no version line, so Minecraft would reset it. Play the profile once, then try again.".into()),
            Some(version) if version < family.options_fix_floor as i64 => Err(format!(
                "This profile's options.txt was saved by an older Minecraft (data version {version}). Play the profile once so Minecraft updates it, then try again."
            )),
            // A newer Minecraft's file can hold key names this version doesn't know, and 26.1 / 26.2 stop
            // reading options.txt at the first one, so values after it wouldn't take effect.
            Some(version) if version > family.versions.iter().map(|(_, data)| *data as i64).max().unwrap_or(0) => Err(format!(
                "This profile's options.txt was saved by a newer Minecraft (data version {version}) than {}. Play the profile once so Minecraft {} saves it again, then try again.",
                profile.game_version, profile.game_version
            )),
            Some(_) => Ok(doc.clone()),
        },
    }
}

pub fn not_verified(support: &VersionSupport) -> String {
    let reason = support.reason.clone().unwrap_or_else(|| format!("Minecraft {} isn't verified.", support.game_version));
    let reason = if reason.ends_with('.') { reason } else { format!("{reason}.") };
    format!("{reason} Flint only changes settings for versions it has checked.")
}

/// The graphics preset the file leaves Minecraft with: "fancy" when missing or unreadable, as Minecraft does.
fn preset_of(doc: &OptionsDoc) -> String {
    match doc.get(PRESET_KEY).and_then(|raw| Codec::JsonString.decode(raw)) {
        Some(Val::Text(preset)) => preset,
        _ => "fancy".into(),
    }
}

/// Minecraft re-applies a graphics preset other than "custom" at every start (GraphicsPreset.apply), so
/// a change to an option it manages also needs the preset to be "custom". True when `doc`, with `changed`
/// keys already written, needs that.
pub fn needs_custom_preset(doc: &OptionsDoc, family: &FamilyData, changed: &[&str]) -> bool {
    changed.iter().any(|key| family.preset_managed.contains(key)) && preset_of(doc) != "custom"
}

pub fn custom_preset_raw() -> String {
    Codec::JsonString.encode(&Val::Text("custom".into()))
}

/// Names in a sentence: "A", "A and B", "A, B and C".
pub fn list(names: &[&str]) -> String {
    match names {
        [] => String::new(),
        [only] => (*only).to_owned(),
        [rest @ .., last] => format!("{} and {last}", rest.join(", ")),
    }
}

/// `work` with its destination blocked as a whole: nothing is listed and nothing is written.
pub fn block(mut work: TargetWork, reason: String) -> TargetWork {
    work.plan.blocked = Some(reason);
    work.plan.changes.clear();
    work.plan.skipped.clear();
    work.plan.unchanged_count = 0;
    work.plan.untouched = "Nothing in this profile changes.".into();
    work.writes.clear();
    work
}

/// Plans one destination.
pub fn plan_target(source: &ProfileDocs, target: &ProfileDocs, running: Option<&str>, categories: &[SyncCategory], excluded: &HashSet<String>) -> TargetWork {
    let mut plan = TargetPlan {
        profile_id: target.id.clone(),
        profile_name: target.name.clone(),
        game_version: target.game_version.clone(),
        support: target.support.clone(),
        blocked: None,
        changes: Vec::new(),
        skipped: Vec::new(),
        unchanged_count: 0,
        untouched: String::new(),
    };
    let blocked = |plan: TargetPlan, reason: String| block(TargetWork { plan, writes: Vec::new() }, reason);
    if target.id == source.id {
        return blocked(plan, "This is the profile the settings come from.".into());
    }
    if let Some(reason) = running {
        return blocked(plan, reason.to_owned());
    }
    // Only verified versions are written, whatever is copied: Flint's own files included.
    if target.support.family().is_none() {
        return blocked(plan, not_verified(&target.support));
    }
    let minecraft: Vec<Category> = CATEGORY_ORDER.into_iter().filter(|c| categories.contains(&SyncCategory::from(*c))).collect();
    let mut writes = Vec::new();
    let mut untouched = Vec::new();
    if minecraft.is_empty() {
        untouched.push("options.txt isn't touched.".to_owned());
    } else {
        let Some(source_family) = source.support.family() else {
            return blocked(plan, format!("Flint can't read {}'s Minecraft settings: {}", source.name, not_verified(&source.support)));
        };
        let original = match writable_options(target) {
            Ok(doc) => doc,
            Err(reason) => return blocked(plan, reason),
        };
        let family = target.support.family().expect("checked above");
        let mut doc = original.clone();
        match &source.options {
            OptionsFile::Ok(source_doc) => {
                let input = Minecraft { source, source_doc, source_family, target, family, categories: &minecraft, excluded };
                input.plan(&mut doc, &mut plan);
            }
            missing_or_bad => {
                let reason = match missing_or_bad {
                    OptionsFile::Missing => format!("{} has no Minecraft settings yet. Play it once first.", source.name),
                    _ => format!("{}'s options.txt can't be read.", source.name),
                };
                for category in &minecraft {
                    plan.skipped.push(PlanSkip { setting_id: None, label: "Minecraft settings".into(), category: (*category).into(), reason: reason.clone() });
                }
            }
        }
        let new_file = matches!(target.options, OptionsFile::Missing);
        let changed_lines = plan.changes.iter().filter(|c| c.category.minecraft().is_some() && c.kind == ChangeKind::Change).count();
        if plan.changes.iter().any(|c| c.category.minecraft().is_some()) {
            // Every key whose value the plan changed, with the text it must read back as.
            let mut checks: Vec<(String, String)> = Vec::new();
            for key in doc.keys() {
                if original.get(key) != doc.get(key) && !checks.iter().any(|(k, _)| k == key) {
                    checks.push((key.to_owned(), doc.get(key).unwrap_or_default().to_owned()));
                }
            }
            let mut write = FileWrite::new("options.txt".into(), doc.to_string().into_bytes());
            write.checks = checks;
            writes.push(write);
            untouched.push(if new_file {
                "A new options.txt starts with only these settings; Minecraft fills in the rest when it starts.".to_owned()
            } else {
                let others = original.line_count().saturating_sub(changed_lines);
                format!("{others} other {} in options.txt left exactly as {}.", if others == 1 { "line" } else { "lines" }, if others == 1 { "it is" } else { "they are" })
            });
        } else {
            untouched.push(if new_file { "No options.txt is created.".to_owned() } else { "options.txt is left exactly as it is.".to_owned() });
        }
    }
    let flint = flintfiles::plan(&source.flint, &source.name, &target.flint, categories, excluded);
    if flint.writes.iter().any(|w| w.rel == flintfiles::CONTROL_CENTER) {
        untouched.push("Other Control Center settings are kept (Flint saves that file with its settings in alphabetical order).".into());
    }
    if categories.contains(&SyncCategory::HudLayouts) && !target.flint.layouts.is_empty() {
        untouched.push("Layouts only this profile has are kept.".into());
    }
    if !flint.writes.is_empty() && !target.runtime {
        untouched.push("Flint's in-game menu isn't installed in this profile, so its files take effect once it is.".into());
    }
    plan.changes.extend(flint.changes);
    plan.skipped.extend(flint.skipped);
    plan.unchanged_count += flint.unchanged;
    writes.extend(flint.writes);
    plan.untouched = untouched.join(" ");
    TargetWork { plan, writes }
}

struct Minecraft<'a> {
    source: &'a ProfileDocs,
    source_doc: &'a OptionsDoc,
    source_family: &'static FamilyData,
    target: &'a ProfileDocs,
    family: &'static FamilyData,
    categories: &'a [Category],
    excluded: &'a HashSet<String>,
}

impl Minecraft<'_> {
    fn skip(&self, plan: &mut TargetPlan, setting: &Setting, reason: String) {
        plan.skipped.push(PlanSkip { setting_id: Some(setting.id.clone()), label: setting.label.clone(), category: setting.category.into(), reason });
    }

    fn plan(&self, doc: &mut OptionsDoc, plan: &mut TargetPlan) {
        let catalogue = registry::catalogue(Some(self.family));
        let original = doc.clone();
        let mut changed_keys: Vec<String> = Vec::new();
        for category in self.categories {
            let mut settings: Vec<Setting> = registry::in_category(&catalogue, *category).cloned().collect();
            if *category == Category::Keybinds {
                settings.extend(self.mod_binds());
            }
            for setting in &settings {
                if let Some(key) = self.copy(setting, doc, plan, &catalogue) {
                    changed_keys.push(key);
                }
            }
        }
        let changed: Vec<&str> = changed_keys.iter().map(String::as_str).collect();
        if needs_custom_preset(doc, self.family, &changed) {
            self.force_custom_preset(&original, doc, plan, &catalogue);
        } else {
            self.explain_preset(plan, &catalogue);
        }
        self.flag_shared_keys(doc, plan, &catalogue);
    }

    /// The options a graphics preset sets at every start, in the order the launcher lists them.
    fn preset_managed<'c>(&self, catalogue: &'c [Setting]) -> Vec<&'c Setting> {
        catalogue.iter().filter(|s| self.family.preset_managed.contains(&s.key.as_str())).collect()
    }

    /// Copying a graphics preset other than Custom hands the options it controls back to Minecraft, which
    /// sets them to the preset's values at every start. When the person left one of those out, that would
    /// undo their choice, so the preset isn't copied; the reason (Some) says which.
    fn preset_held_back(&self, setting: &Setting, value: &Val, doc: &OptionsDoc, catalogue: &[Setting]) -> Option<String> {
        if setting.key != PRESET_KEY || *value == Val::Text("custom".into()) {
            return None;
        }
        let held: Vec<&str> = self
            .preset_managed(catalogue)
            .into_iter()
            .filter(|s| self.excluded.contains(&s.id) && self.categories.contains(&s.category))
            .map(|s| s.label.as_str())
            .collect();
        if held.is_empty() {
            return None;
        }
        Some(format!(
            "The {} preset would make Minecraft reset {} at every start, and you left {} out. This profile keeps its {} preset.",
            setting.display(value),
            list(&held),
            if held.len() == 1 { "it" } else { "them" },
            setting.display(&Val::Text(preset_of(doc)))
        ))
    }

    /// A copied preset other than Custom: its row names the options Minecraft sets from it at every start.
    fn explain_preset(&self, plan: &mut TargetPlan, catalogue: &[Setting]) {
        let Some(preset) = catalogue.iter().find(|s| s.key == PRESET_KEY) else { return };
        let Some(change) = plan.changes.iter_mut().find(|c| c.setting_id == preset.id) else { return };
        if change.proposed_raw == Some(custom_preset_raw()) {
            return;
        }
        let managed: Vec<&str> = self.preset_managed(catalogue).into_iter().map(|s| s.label.as_str()).collect();
        change.note = Some(format!("Each time this profile starts, Minecraft sets {} to the {} preset's values.", list(&managed), change.proposed));
    }

    /// Key mappings in the source that are neither Minecraft's (in the source's version) nor Flint's.
    fn mod_binds(&self) -> Vec<Setting> {
        let mut seen = HashSet::new();
        self.source_doc
            .keys()
            .filter_map(|key| key.strip_prefix(KEY_PREFIX))
            // A mapping any verified Minecraft has is Minecraft's, even where this version lacks it.
            .filter(|mapping| !FAMILIES.iter().any(|f| f.key_mappings.iter().any(|(m, _)| m == mapping)))
            .filter(|mapping| !registry::FLINT_BINDS.iter().any(|(m, ..)| m == mapping))
            .filter(|mapping| seen.insert(mapping.to_string()))
            .map(|mapping| Setting::bind(mapping, Bind::Mod, None))
            .collect()
    }

    /// Plans one setting; returns its key when the document changed.
    fn copy(&self, setting: &Setting, doc: &mut OptionsDoc, plan: &mut TargetPlan, catalogue: &[Setting]) -> Option<String> {
        let target_version = &self.target.game_version;
        let source_name = &self.source.name;
        // Only what the source's Minecraft really uses: a key its version doesn't have means nothing there.
        if setting.bind != Some(Bind::Mod) && setting.available_in(self.source_family).is_err() {
            return None;
        }
        let raw = self.source_doc.get(&setting.key)?;
        if self.excluded.contains(&setting.id) {
            self.skip(plan, setting, LEFT_OUT.into());
            return None;
        }
        if setting.available_in(self.family).is_err() {
            self.skip(plan, setting, format!("Not in Minecraft {target_version}."));
            return None;
        }
        match setting.bind {
            Some(Bind::Mod) if !doc.contains(&setting.key) => {
                self.skip(plan, setting, format!("{} isn't used in this profile (the mod that adds it hasn't run here).", setting.label));
                return None;
            }
            Some(Bind::Flint) if !doc.contains(&setting.key) && !self.target.runtime => {
                self.skip(plan, setting, "Flint's in-game menu isn't installed in this profile.".into());
                return None;
            }
            _ => {}
        }
        let Some(value) = setting.decode(raw) else {
            self.skip(plan, setting, format!("The value in {source_name} ({raw}) isn't one Minecraft can read."));
            return None;
        };
        if setting.repr == Repr::Key {
            let Val::Text(name) = &value else { return None };
            if check_key(self.source_family, name) == KeyCheck::Invalid {
                self.skip(plan, setting, format!("{name} isn't a key Minecraft {} knows.", self.source.game_version));
                return None;
            }
            match check_key(self.family, name) {
                KeyCheck::Named => {}
                KeyCheck::Numeric if registry::same_input(self.source_family, self.family) => {}
                KeyCheck::Numeric => {
                    self.skip(plan, setting, format!(
                        "{name} is a numeric key code, and Minecraft {target_version} reads key codes differently ({} instead of {}), so it would be a different key there.",
                        registry::input_name(self.family.input),
                        registry::input_name(self.source_family.input)
                    ));
                    return None;
                }
                KeyCheck::Invalid => {
                    self.skip(plan, setting, format!("{} doesn't exist in Minecraft {target_version}.", registry::key_label(name)));
                    return None;
                }
            }
        } else {
            if !setting.accepts(self.source_family, &value) {
                self.skip(plan, setting, format!("The value in {source_name} ({raw}) isn't one Minecraft {} accepts.", self.source.game_version));
                return None;
            }
            if !setting.accepts(self.family, &value) {
                self.skip(plan, setting, format!("{} isn't a value Minecraft {target_version} accepts.", setting.display(&value)));
                return None;
            }
        }
        let current_raw = doc.get(&setting.key).map(str::to_owned);
        let current = current_raw.as_deref().and_then(|r| setting.decode(r));
        if current.as_ref() == Some(&value) {
            plan.unchanged_count += 1;
            return None;
        }
        if let Some(reason) = setting.read_only {
            self.skip(plan, setting, reason.into());
            return None;
        }
        if let Some(reason) = self.preset_held_back(setting, &value, doc, catalogue) {
            self.skip(plan, setting, reason);
            return None;
        }
        let proposed_raw = setting.encode(&value);
        doc.set(&setting.key, &proposed_raw);
        plan.changes.push(PlanChange {
            setting_id: setting.id.clone(),
            label: setting.label.clone(),
            category: setting.category.into(),
            current: match (&current, &current_raw) {
                (Some(v), _) => setting.display(v),
                (None, Some(raw)) => raw.clone(),
                (None, None) => "Not set".into(),
            },
            proposed: setting.display(&value),
            kind: if current_raw.is_some() { ChangeKind::Change } else { ChangeKind::Add },
            note: None,
            current_raw,
            proposed_raw: Some(proposed_raw),
        });
        Some(setting.key.clone())
    }

    fn force_custom_preset(&self, original: &OptionsDoc, doc: &mut OptionsDoc, plan: &mut TargetPlan, catalogue: &[Setting]) {
        let preset = catalogue.iter().find(|s| s.key == PRESET_KEY).expect("the registry lists the graphics preset");
        let copied = plan.changes.iter().position(|c| c.setting_id == preset.id).map(|at| plan.changes.remove(at));
        doc.set(&preset.key, &custom_preset_raw());
        let before_raw = original.get(&preset.key);
        let before = before_raw.and_then(|raw| preset.decode(raw));
        if before == Some(Val::Text("custom".into())) {
            // Already Custom, so there is no row; but when the source's own preset was about to be copied,
            // say why it isn't.
            if let Some(copied) = copied {
                plan.skipped.push(PlanSkip {
                    setting_id: Some(preset.id.clone()),
                    label: preset.label.clone(),
                    category: SyncCategory::Video,
                    reason: format!("{} isn't copied: this profile stays Custom, or Minecraft's graphics preset would undo the copied changes at launch.", copied.proposed),
                });
            }
            return;
        }
        // The forced row says what happens to the preset; a skip for it would contradict that.
        plan.skipped.retain(|s| s.setting_id.as_deref() != Some(preset.id.as_str()));
        let note = if matches!(self.target.options, OptionsFile::Missing) { PRESET_NEW_FILE_NOTE } else { PRESET_NOTE };
        plan.changes.push(PlanChange {
            setting_id: preset.id.clone(),
            label: preset.label.clone(),
            category: SyncCategory::Video,
            current: before.map(|v| preset.display(&v)).or_else(|| before_raw.map(str::to_owned)).unwrap_or_else(|| "Not set".into()),
            proposed: preset.display(&Val::Text("custom".into())),
            kind: if before_raw.is_some() { ChangeKind::Change } else { ChangeKind::Add },
            note: Some(note.into()),
            current_raw: before_raw.map(str::to_owned),
            proposed_raw: Some(custom_preset_raw()),
        });
    }

    /// Minecraft allows one key for several actions; a copied binding that shares its key says so.
    fn flag_shared_keys(&self, doc: &OptionsDoc, plan: &mut TargetPlan, catalogue: &[Setting]) {
        for change in plan.changes.iter_mut().filter(|c| c.category == SyncCategory::Keybinds) {
            let Some(mapping) = change.setting_id.strip_prefix("keybind.") else { continue };
            let key = format!("{KEY_PREFIX}{mapping}");
            let Some(bound) = doc.get(&key).filter(|k| *k != UNBOUND) else { continue };
            let mut others: Vec<String> = Vec::new();
            for other in doc.keys().filter(|k| k.starts_with(KEY_PREFIX) && **k != key) {
                if doc.get(other) == Some(bound) {
                    let label = catalogue.iter().find(|s| s.key == other).map(|s| s.label.clone()).unwrap_or_else(|| other[KEY_PREFIX.len()..].to_owned());
                    if !others.contains(&label) {
                        others.push(label);
                    }
                }
            }
            if !others.is_empty() {
                change.note = Some(format!("Also bound to {}.", others.join(", ")));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gamesettings::flintfiles::{JsonFile, JsonFileOrMissing};
    use crate::gamesettings::testkit::FIXTURE_26_3;
    use crate::gamesettings::versions::SupportLevel;

    fn support(version: &str) -> VersionSupport {
        let family = FAMILIES.iter().find(|f| f.versions.iter().any(|(v, _)| *v == version));
        match family {
            Some(f) => VersionSupport {
                level: SupportLevel::Verified,
                family: Some(f.id),
                game_version: version.into(),
                data_version: f.versions.iter().find(|(v, _)| *v == version).map(|(_, d)| *d),
                reason: None,
            },
            None => VersionSupport {
                level: SupportLevel::Unverified,
                family: None,
                game_version: version.into(),
                data_version: None,
                reason: Some("Not yet verified against Minecraft 1.21.x".into()),
            },
        }
    }

    fn profile(id: &str, version: &str, options: &str) -> ProfileDocs {
        ProfileDocs {
            id: id.into(),
            name: format!("Profile {id}"),
            game_version: version.into(),
            support: support(version),
            options: OptionsFile::Ok(OptionsDoc::parse(options.as_bytes()).unwrap()),
            flint: FlintDocs::default(),
            runtime: false,
        }
    }

    fn bench() -> String {
        String::from_utf8(FIXTURE_26_3.to_vec()).unwrap()
    }

    /// The bench file with some lines replaced (key → new value), as another profile might have it.
    fn edited(base: &str, edits: &[(&str, &str)]) -> String {
        let mut doc = OptionsDoc::parse(base.as_bytes()).unwrap();
        for (key, value) in edits {
            doc.set(key, value);
        }
        doc.to_string()
    }

    fn run(source: &ProfileDocs, target: &ProfileDocs, categories: &[SyncCategory]) -> TargetWork {
        plan_target(source, target, None, categories, &HashSet::new())
    }

    fn written(work: &TargetWork) -> OptionsDoc {
        let write = work.writes.iter().find(|w| w.rel == "options.txt").expect("options.txt is written");
        OptionsDoc::parse(&write.bytes).unwrap()
    }

    fn change<'a>(work: &'a TargetWork, id: &str) -> Option<&'a PlanChange> {
        work.plan.changes.iter().find(|c| c.setting_id == id)
    }

    fn skipped<'a>(work: &'a TargetWork, id: &str) -> Option<&'a PlanSkip> {
        work.plan.skipped.iter().find(|s| s.setting_id.as_deref() == Some(id))
    }

    /// A 26.1-style file: the bench file without 26.2/26.3-only lines, with raw input and version 4786.
    fn file_26_1() -> String {
        let text = bench();
        let mut out: Vec<&str> = text
            .lines()
            .filter(|l| !["preferredGraphicsBackend:", "macFullscreenMenuVisibility:", "debugGuiScale:", "quitShortcuts:", "ctrlClickEmulatesRightClick:",
                "inGameNotification:", "sharePresence:", "key_key.friends:", "key_key.debug.improvedTransparency:"].iter().any(|p| l.starts_with(p)))
            .collect();
        out[0] = "version:4786";
        out.push("rawMouseInput:true");
        out.join("\n") + "\n"
    }

    /// A 26.2-style file: the 26.1 one with 26.2's data version and its friends key.
    fn file_26_2() -> String {
        edited(&file_26_1(), &[("version", "4903"), ("key_key.friends", "key.keyboard.o")])
    }

    #[test]
    fn identical_profiles_need_no_changes() {
        let source = profile("a", "26.3", &bench());
        let target = profile("b", "26.3", &bench());
        let work = run(&source, &target, &[SyncCategory::Controls, SyncCategory::Keybinds, SyncCategory::Video, SyncCategory::Audio]);
        assert!(work.plan.blocked.is_none());
        assert!(work.plan.changes.is_empty(), "{:?}", work.plan.changes);
        assert!(work.writes.is_empty());
        assert!(work.plan.unchanged_count > 90);
        assert_eq!(work.plan.untouched, "options.txt is left exactly as it is.");
    }

    #[test]
    fn changed_values_are_listed_and_written_and_nothing_else_moves() {
        let source = profile("a", "26.3", &edited(&bench(), &[("mouseSensitivity", "0.75"), ("soundCategory_music", "0.25"), ("key_key.jump", "key.keyboard.v")]));
        let target = profile("b", "26.3", &bench());
        let work = run(&source, &target, &[SyncCategory::Controls, SyncCategory::Keybinds, SyncCategory::Audio]);
        let sensitivity = change(&work, "controls.mouseSensitivity").unwrap();
        assert_eq!((sensitivity.current.as_str(), sensitivity.proposed.as_str(), sensitivity.kind), ("100%", "150%", ChangeKind::Change));
        assert_eq!(change(&work, "audio.music").unwrap().proposed, "25%");
        assert_eq!(change(&work, "keybind.key.jump").unwrap().proposed, "V");
        assert_eq!(work.plan.changes.len(), 3);
        let doc = written(&work);
        assert_eq!(doc.get("mouseSensitivity"), Some("0.75"));
        assert_eq!(doc.get("key_key.jump"), Some("key.keyboard.v"));
        let expected = edited(&bench(), &[("mouseSensitivity", "0.75"), ("soundCategory_music", "0.25"), ("key_key.jump", "key.keyboard.v")]);
        assert_eq!(doc.to_string(), expected, "every other line is exactly as it was");
        assert_eq!(work.plan.untouched, "191 other lines in options.txt left exactly as they are.");
        let write = &work.writes[0];
        assert!(write.checks.contains(&("mouseSensitivity".into(), "0.75".into())));
    }

    #[test]
    fn categories_are_independent() {
        let source = profile("a", "26.3", &edited(&bench(), &[("graphicsPreset", "\"custom\""), ("renderDistance", "8"), ("key_key.jump", "key.keyboard.v"), ("soundCategory_master", "0.5")]));
        let mut target = profile("b", "26.3", &bench());
        target.flint.shortcuts = JsonFileOrMissing(JsonFile::Missing);
        let mut source_with_flint = source.clone();
        source_with_flint.flint.shortcuts = JsonFileOrMissing(JsonFile::parse(br#"{"shortcuts":[{"name":"Hub","address":"hub.example.net"}]}"#.to_vec()));
        let work = run(&source_with_flint, &target, &[SyncCategory::Video]);
        assert!(work.plan.changes.iter().all(|c| c.category == SyncCategory::Video), "{:?}", work.plan.changes);
        assert!(change(&work, "video.renderDistance").is_some());
        assert_eq!(work.writes.len(), 1, "only options.txt");
        let doc = written(&work);
        assert_eq!(doc.get("key_key.jump"), Some("key.keyboard.space"), "key binds untouched");
        assert_eq!(doc.get("soundCategory_master"), Some("1.0"), "audio untouched");
        // And Flint's files only with their own categories.
        let shortcuts = run(&source_with_flint, &target, &[SyncCategory::ServerShortcuts]);
        assert!(shortcuts.writes.iter().all(|w| w.rel == flintfiles::SHORTCUTS));
        assert_eq!(shortcuts.plan.untouched, "options.txt isn't touched. Flint's in-game menu isn't installed in this profile, so its files take effect once it is.");
        let mut with_runtime = target.clone();
        with_runtime.runtime = true;
        assert_eq!(run(&source_with_flint, &with_runtime, &[SyncCategory::ServerShortcuts]).plan.untouched, "options.txt isn't touched.");
    }

    #[test]
    fn a_preset_managed_change_makes_the_preset_custom_with_a_reason() {
        // The source chose a shorter render distance by hand; its preset is already custom.
        let source = profile("a", "26.3", &edited(&bench(), &[("graphicsPreset", "\"custom\""), ("renderDistance", "8")]));
        let target = profile("b", "26.3", &bench());
        let work = run(&source, &target, &[SyncCategory::Video]);
        let preset = change(&work, "video.graphicsPreset").unwrap();
        assert_eq!((preset.current.as_str(), preset.proposed.as_str()), ("Fancy", "Custom"));
        assert_eq!(written(&work).get("graphicsPreset"), Some("\"custom\""));
        // Only the accessibility blur (also managed by the preset) is copied: the preset still becomes custom.
        let blur = profile("c", "26.3", &edited(&bench(), &[("menuBackgroundBlurriness", "2")]));
        let work = run(&blur, &target, &[SyncCategory::Accessibility]);
        let preset = change(&work, "video.graphicsPreset").unwrap();
        assert_eq!(preset.note.as_deref(), Some(PRESET_NOTE));
        assert_eq!(preset.category, SyncCategory::Video);
        // Even when the source's own preset is being copied, a managed change wins: Fast would undo it.
        let fast = profile("d", "26.3", &edited(&bench(), &[("graphicsPreset", "\"fast\""), ("renderDistance", "8")]));
        let work = run(&fast, &target, &[SyncCategory::Video]);
        assert_eq!(written(&work).get("graphicsPreset"), Some("\"custom\""));
        assert_eq!(work.plan.changes.iter().filter(|c| c.setting_id == "video.graphicsPreset").count(), 1);
    }

    #[test]
    fn a_destination_already_on_custom_needs_no_preset_change() {
        let source = profile("a", "26.3", &edited(&bench(), &[("graphicsPreset", "\"custom\""), ("renderDistance", "8")]));
        let target = profile("b", "26.3", &edited(&bench(), &[("graphicsPreset", "\"custom\"")]));
        let work = run(&source, &target, &[SyncCategory::Video]);
        assert!(change(&work, "video.graphicsPreset").is_none());
        assert_eq!(work.plan.changes.len(), 1);
    }

    #[test]
    fn copying_only_the_preset_doesn_t_force_custom() {
        let source = profile("a", "26.3", &edited(&bench(), &[("graphicsPreset", "\"fast\"")]));
        let target = profile("b", "26.3", &bench());
        let work = run(&source, &target, &[SyncCategory::Video]);
        assert_eq!(change(&work, "video.graphicsPreset").unwrap().proposed, "Fast");
        assert_eq!(written(&work).get("graphicsPreset"), Some("\"fast\""));
    }

    #[test]
    fn settings_a_version_doesn_t_have_are_skipped_with_a_reason() {
        // 26.3 → 26.1: the graphics API option and two keys don't exist in 26.1.
        let source = profile("a", "26.3", &edited(&bench(), &[("preferredGraphicsBackend", "\"opengl\""), ("key_key.friends", "key.keyboard.k")]));
        let target = profile("b", "26.1", &file_26_1());
        let work = run(&source, &target, &[SyncCategory::Video, SyncCategory::Keybinds]);
        assert!(work.plan.blocked.is_none(), "{:?}", work.plan.blocked);
        assert_eq!(skipped(&work, "video.preferredGraphicsBackend").unwrap().reason, "Not in Minecraft 26.1.");
        assert_eq!(skipped(&work, "keybind.key.friends").unwrap().reason, "Not in Minecraft 26.1.");
        assert_eq!(skipped(&work, "keybind.key.debug.improvedTransparency").unwrap().reason, "Not in Minecraft 26.1.");
        assert!(work.writes.is_empty(), "nothing else differs");
        // Raw input exists only in 26.1/26.2, so a 26.3 source has nothing to say about it.
        assert!(skipped(&work, "controls.rawMouseInput").is_none());
    }

    #[test]
    fn a_26_1_destination_gets_26_3_values_it_understands() {
        let source = profile("a", "26.3", &edited(&bench(), &[("fov", "0.5"), ("toggleSprint", "true"), ("key_key.drop", "key.keyboard.z")]));
        let target = profile("b", "26.1", &file_26_1());
        let work = run(&source, &target, &[SyncCategory::Video, SyncCategory::Controls, SyncCategory::Keybinds]);
        let doc = written(&work);
        assert_eq!(doc.get("fov"), Some("0.5"));
        assert_eq!(change(&work, "video.fov").unwrap().proposed, "90°");
        assert_eq!(doc.get("toggleSprint"), Some("true"));
        assert_eq!(doc.get("key_key.drop"), Some("key.keyboard.z"));
        assert_eq!(doc.get("version"), Some("4786"), "the version line is never changed");
        assert_eq!(doc.get("rawMouseInput"), Some("true"), "26.1-only lines stay");
    }

    #[test]
    fn key_names_that_only_26_3_has_are_never_written_into_26_2() {
        let source = profile("a", "26.3", &edited(&bench(), &[("key_key.jump", "key.keyboard.media.play"), ("key_key.drop", "key.keyboard.keypad.period")]));
        let target = profile("b", "26.2", &file_26_2());
        let work = run(&source, &target, &[SyncCategory::Keybinds]);
        assert_eq!(skipped(&work, "keybind.key.jump").unwrap().reason, "Media Play doesn't exist in Minecraft 26.2.");
        assert_eq!(skipped(&work, "keybind.key.drop").unwrap().reason, "Keypad . doesn't exist in Minecraft 26.2.");
        assert!(work.writes.is_empty());
    }

    #[test]
    fn numeric_key_codes_aren_t_carried_across_glfw_and_sdl3() {
        let source = profile("a", "26.3", &edited(&bench(), &[("key_key.jump", "key.keyboard.300"), ("key_key.attack", "key.mouse.9")]));
        let target = profile("b", "26.2", &file_26_2());
        let work = run(&source, &target, &[SyncCategory::Keybinds]);
        let reason = &skipped(&work, "keybind.key.jump").unwrap().reason;
        assert!(reason.contains("numeric key code") && reason.contains("GLFW instead of SDL3"), "{reason}");
        assert!(skipped(&work, "keybind.key.attack").is_some());
        // Between two GLFW versions the same number is the same key, so it is copied.
        let glfw_source = profile("c", "26.1", &edited(&file_26_1(), &[("key_key.jump", "key.keyboard.300")]));
        let work = run(&glfw_source, &target, &[SyncCategory::Keybinds]);
        assert_eq!(written(&work).get("key_key.jump"), Some("key.keyboard.300"));
    }

    #[test]
    fn shared_keys_are_copied_as_they_are_and_flagged() {
        // Minecraft allows one key for two actions: copied as-is, with an informational note.
        let source = profile("a", "26.3", &edited(&bench(), &[("key_key.drop", "key.keyboard.space")]));
        let target = profile("b", "26.3", &bench());
        let work = run(&source, &target, &[SyncCategory::Keybinds]);
        let drop = change(&work, "keybind.key.drop").unwrap();
        assert_eq!(drop.proposed, "Space");
        assert_eq!(drop.note.as_deref(), Some("Also bound to Jump."));
        assert_eq!(written(&work).get("key_key.drop"), Some("key.keyboard.space"));
    }

    #[test]
    fn malformed_values_in_the_source_are_skipped() {
        let source = profile("a", "26.3", &edited(&bench(), &[("renderDistance", "far"), ("fov", "3.0"), ("key_key.jump", "key.keyboard.nope"), ("particles", "7")]));
        let target = profile("b", "26.3", &bench());
        let work = run(&source, &target, &[SyncCategory::Video, SyncCategory::Keybinds]);
        assert!(skipped(&work, "video.renderDistance").unwrap().reason.contains("isn't one Minecraft can read"));
        assert!(skipped(&work, "video.fov").unwrap().reason.contains("isn't one Minecraft 26.3 accepts"), "190° is out of range");
        assert!(skipped(&work, "keybind.key.jump").unwrap().reason.contains("isn't a key Minecraft 26.3 knows"));
        assert!(skipped(&work, "video.particles").is_some());
        assert!(work.writes.is_empty());
    }

    #[test]
    fn mod_key_binds_go_only_where_the_mod_has_run() {
        let source = profile("a", "26.3", &(bench() + "key_key.sodium.menu:key.keyboard.k\nkey_key.zoom:key.keyboard.c\n"));
        let target = profile("b", "26.3", &(bench() + "key_key.zoom:key.keyboard.unknown\n"));
        let work = run(&source, &target, &[SyncCategory::Keybinds]);
        let sodium = skipped(&work, "keybind.key.sodium.menu").unwrap();
        assert_eq!(sodium.label, "key.sodium.menu");
        assert!(sodium.reason.contains("isn't used in this profile"));
        let zoom = change(&work, "keybind.key.zoom").unwrap();
        assert_eq!((zoom.current.as_str(), zoom.proposed.as_str()), ("Not Bound", "C"));
        assert_eq!(written(&work).get("key_key.sodium.menu"), None);
    }

    #[test]
    fn a_newer_minecraft_key_left_in_an_older_file_isn_t_taken_for_a_mod_key() {
        // A 26.1 file with key.friends (from a newer game) is stale there: 26.1 ignores it.
        let source = profile("a", "26.1", &(file_26_1() + "key_key.friends:key.keyboard.k\n"));
        let target = profile("b", "26.3", &bench());
        let work = run(&source, &target, &[SyncCategory::Keybinds]);
        assert!(change(&work, "keybind.key.friends").is_none());
        assert!(skipped(&work, "keybind.key.friends").is_none());
    }

    #[test]
    fn flint_keys_go_where_flint_s_menu_is_installed() {
        let source = profile("a", "26.3", &(bench() + "key_key.flintclient.hud_editor:key.keyboard.h\n"));
        let without = profile("b", "26.3", &bench());
        let work = run(&source, &without, &[SyncCategory::Keybinds]);
        assert!(skipped(&work, "keybind.key.flintclient.hud_editor").unwrap().reason.contains("isn't installed"));
        let mut with = without.clone();
        with.runtime = true;
        let work = run(&source, &with, &[SyncCategory::Keybinds]);
        let editor = change(&work, "keybind.key.flintclient.hud_editor").unwrap();
        assert_eq!((editor.label.as_str(), editor.kind), ("Open HUD editor", ChangeKind::Add));
    }

    #[test]
    fn an_unverified_destination_is_blocked_whole() {
        let source = profile("a", "26.3", &bench());
        let target = profile("b", "1.21.11", "version:4671\nfov:0.0\n");
        let work = run(&source, &target, &[SyncCategory::Video, SyncCategory::ServerShortcuts]);
        let reason = work.plan.blocked.unwrap();
        assert!(reason.starts_with("Not yet verified against Minecraft 1.21.x."), "{reason}");
        assert!(work.writes.is_empty());
        assert!(work.plan.changes.is_empty());
    }

    #[test]
    fn a_flint_only_sync_never_writes_into_an_unverified_or_unknown_version() {
        let mut source = profile("a", "26.3", &bench());
        source.flint.shortcuts = JsonFileOrMissing(JsonFile::parse(br#"{"shortcuts":[{"name":"Hub","address":"hub.example.net"}]}"#.to_vec()));
        source.flint.layouts = vec![("pvp".into(), JsonFile::parse(br#"{"schema":1,"id":"pvp"}"#.to_vec()))];
        let flint = [SyncCategory::HudLayouts, SyncCategory::ControlCenter, SyncCategory::ServerShortcuts];
        let unverified = profile("b", "1.21.11", "version:4671\n");
        let mut unknown = profile("c", "26.2", "");
        unknown.options = OptionsFile::Missing;
        unknown.support = VersionSupport {
            level: SupportLevel::Unknown,
            family: None,
            game_version: "26.2".into(),
            data_version: None,
            reason: Some("Minecraft 26.2 isn't installed yet, so Flint can't confirm its settings format. Install or play this profile once first.".into()),
        };
        for target in [&unverified, &unknown] {
            let work = run(&source, target, &flint);
            let reason = work.plan.blocked.as_deref().unwrap_or_else(|| panic!("{} must be blocked", target.game_version));
            assert!(reason.ends_with("Flint only changes settings for versions it has checked."), "{reason}");
            assert!(work.writes.is_empty() && work.plan.changes.is_empty() && work.plan.skipped.is_empty());
        }
        // A verified destination gets them.
        let verified = profile("d", "26.3", &bench());
        assert_eq!(run(&source, &verified, &flint).writes.len(), 2, "the layout and the shortcuts");
    }

    #[test]
    fn an_unverified_source_can_t_be_read() {
        let source = profile("a", "1.21.11", "version:4671\nfov:0.5\n");
        let target = profile("b", "26.3", &bench());
        let work = run(&source, &target, &[SyncCategory::Video]);
        assert!(work.plan.blocked.unwrap().starts_with("Flint can't read Profile a's Minecraft settings"));
    }

    #[test]
    fn a_running_destination_is_blocked() {
        let source = profile("a", "26.3", &edited(&bench(), &[("fov", "0.5")]));
        let target = profile("b", "26.3", &bench());
        let work = plan_target(&source, &target, Some("Minecraft is running in this profile."), &[SyncCategory::Video], &HashSet::new());
        assert_eq!(work.plan.blocked.as_deref(), Some("Minecraft is running in this profile."));
        assert!(work.writes.is_empty());
    }

    #[test]
    fn the_source_profile_is_never_its_own_destination() {
        let source = profile("a", "26.3", &bench());
        assert!(run(&source, &source, &[SyncCategory::Video]).plan.blocked.is_some());
    }

    #[test]
    fn a_settings_file_from_before_the_last_data_fix_is_blocked() {
        let source = profile("a", "26.3", &bench());
        let old = profile("b", "26.3", &edited(&bench(), &[("version", "4800")]));
        assert!(run(&source, &old, &[SyncCategory::Video]).plan.blocked.unwrap().contains("older Minecraft"));
        let no_version = profile("c", "26.3", "fov:0.0\n");
        assert!(run(&source, &no_version, &[SyncCategory::Video]).plan.blocked.unwrap().contains("no version line"));
        // 26.1's last options fix is older, so its own files are fine.
        let target = profile("d", "26.1", &file_26_1());
        assert!(run(&source, &target, &[SyncCategory::Video]).plan.blocked.is_none());
    }

    #[test]
    fn a_settings_file_from_a_newer_minecraft_is_blocked() {
        // 26.2 stops reading options.txt at a 26.3-only key name, so values after it wouldn't take effect.
        let source = profile("a", "26.3", &edited(&bench(), &[("fov", "0.5")]));
        let newer = profile("b", "26.2", &edited(&file_26_2(), &[("version", "5023")]));
        let reason = run(&source, &newer, &[SyncCategory::Video]).plan.blocked.unwrap();
        assert!(reason.contains("newer Minecraft (data version 5023) than 26.2"), "{reason}");
        // The versions of one family share a format: a 26.1 profile can hold 26.1.2's file.
        let sibling = profile("c", "26.1", &edited(&file_26_1(), &[("version", "4790")]));
        assert!(run(&source, &sibling, &[SyncCategory::Video]).plan.blocked.is_none());
    }

    #[test]
    fn a_copied_preset_names_the_options_minecraft_sets_from_it() {
        // Every quality option already matches, so only the preset changes: Minecraft applies it at every start.
        let source = profile("a", "26.3", &edited(&bench(), &[("graphicsPreset", "\"fast\"")]));
        let target = profile("b", "26.3", &edited(&bench(), &[("graphicsPreset", "\"custom\"")]));
        let work = run(&source, &target, &[SyncCategory::Video]);
        let preset = change(&work, "video.graphicsPreset").unwrap();
        assert_eq!((preset.current.as_str(), preset.proposed.as_str()), ("Custom", "Fast"));
        let note = preset.note.as_deref().unwrap();
        assert!(note.starts_with("Each time this profile starts, Minecraft sets Render distance, Simulation distance,"), "{note}");
        assert!(note.ends_with(" and Menu background blur to the Fast preset's values."), "{note}");
        let family = FAMILIES.iter().find(|f| f.id == "26.3").unwrap();
        let catalogue = registry::catalogue(Some(family));
        for key in family.preset_managed {
            let label = &catalogue.iter().find(|s| s.key == *key).unwrap().label;
            assert!(note.contains(label.as_str()), "{label} is named");
        }
    }

    #[test]
    fn a_preset_that_would_reset_a_left_out_option_isn_t_copied() {
        let excluded: HashSet<String> = ["video.renderDistance".to_owned()].into();
        let source = profile("a", "26.3", &edited(&bench(), &[("graphicsPreset", "\"fast\"")]));
        let target = profile("b", "26.3", &edited(&bench(), &[("graphicsPreset", "\"custom\""), ("renderDistance", "20")]));
        let work = plan_target(&source, &target, None, &[SyncCategory::Video], &excluded);
        assert!(change(&work, "video.graphicsPreset").is_none());
        assert_eq!(
            skipped(&work, "video.graphicsPreset").unwrap().reason,
            "The Fast preset would make Minecraft reset Render distance at every start, and you left it out. This profile keeps its Custom preset."
        );
        assert!(work.writes.is_empty(), "render distance stays 20");
        // On a destination that isn't Custom, other copied quality options still make it Custom, which keeps
        // the left-out one as it is too; the row then says what happens to the preset.
        let source = profile("a", "26.3", &edited(&bench(), &[("graphicsPreset", "\"fast\""), ("renderDistance", "8"), ("particles", "1")]));
        let target = profile("c", "26.3", &bench());
        let work = plan_target(&source, &target, None, &[SyncCategory::Video], &excluded);
        let preset = change(&work, "video.graphicsPreset").unwrap();
        assert_eq!((preset.current.as_str(), preset.proposed.as_str()), ("Fancy", "Custom"));
        assert!(skipped(&work, "video.graphicsPreset").is_none());
        let doc = written(&work);
        assert_eq!((doc.get("renderDistance"), doc.get("particles"), doc.get("graphicsPreset")), (Some("16"), Some("1"), Some("\"custom\"")));
    }

    #[test]
    fn a_destination_kept_on_custom_says_why_the_source_s_preset_isn_t_copied() {
        let source = profile("a", "26.3", &edited(&bench(), &[("graphicsPreset", "\"fast\""), ("renderDistance", "8")]));
        let target = profile("b", "26.3", &edited(&bench(), &[("graphicsPreset", "\"custom\"")]));
        let work = run(&source, &target, &[SyncCategory::Video]);
        assert!(change(&work, "video.graphicsPreset").is_none());
        let reason = &skipped(&work, "video.graphicsPreset").unwrap().reason;
        assert!(reason.starts_with("Fast isn't copied: this profile stays Custom"), "{reason}");
        assert_eq!(written(&work).get("graphicsPreset"), Some("\"custom\""));
        assert_eq!(written(&work).get("renderDistance"), Some("8"));
    }

    #[test]
    fn nothing_a_destination_s_version_lacks_is_ever_written() {
        // A source with a value for every setting its version has and, round by round, every key name its
        // version knows (plus numeric ones), copied with every Minecraft category into each verified family:
        // every line written must be one the destination's version has, holding a value or key name it keeps.
        let categories: Vec<SyncCategory> = CATEGORY_ORDER.iter().map(|c| SyncCategory::from(*c)).collect();
        let mut skipped_binds: std::collections::HashMap<(&str, &str), usize> = Default::default();
        for source_family in FAMILIES.iter() {
            let catalogue = registry::catalogue(Some(source_family));
            let available: Vec<&Setting> = catalogue.iter().filter(|s| s.available_in(source_family).is_ok() && s.read_only.is_none()).collect();
            let binds = available.iter().filter(|s| s.repr == Repr::Key).count();
            let mut names: Vec<&str> = source_family.key_names.to_vec();
            names.extend(["key.keyboard.300", "key.mouse.9", "key.keyboard.65"]);
            let mut covered: HashSet<&str> = HashSet::new();
            for round in 0..names.len().div_ceil(binds) {
                let mut doc = OptionsDoc::new_file(source_family.versions[0].1);
                let mut bind = 0;
                for (i, setting) in available.iter().enumerate() {
                    let raw = match setting.repr {
                        Repr::Key => {
                            let name = names[(round * binds + bind) % names.len()];
                            bind += 1;
                            covered.insert(name);
                            name.to_owned()
                        }
                        Repr::Id { labels, .. } => setting.encode(&Val::Int(labels[(round + i) % labels.len()].0)),
                        Repr::Str { labels, .. } => setting.encode(&Val::Text(labels[(round + i) % labels.len()].0.into())),
                        Repr::Bool(..) | Repr::PlainBool => setting.encode(&Val::Bool((round + i) % 2 == 0)),
                        _ => setting.default.clone().unwrap(),
                    };
                    doc.set(&setting.key, &raw);
                }
                let source = ProfileDocs { options: OptionsFile::Ok(doc), ..profile("src", source_family.versions[0].0, "") };
                for target_family in FAMILIES.iter() {
                    let (version, data) = target_family.versions[0];
                    let target = ProfileDocs { options: OptionsFile::Ok(OptionsDoc::new_file(data)), runtime: true, ..profile("dst", version, "") };
                    let work = run(&source, &target, &categories);
                    assert!(work.plan.blocked.is_none(), "{:?}", work.plan.blocked);
                    *skipped_binds.entry((source_family.id, target_family.id)).or_default() += work.plan.skipped.iter().filter(|s| s.category == SyncCategory::Keybinds).count();
                    let doc = written(&work);
                    assert_eq!(doc.get("version"), Some(data.to_string().as_str()), "the version line is never changed");
                    let theirs = registry::catalogue(Some(target_family));
                    for key in doc.keys().filter(|k| *k != "version") {
                        let value = doc.get(key).unwrap();
                        let what = format!("{key}:{value} from {} into {}", source_family.id, target_family.id);
                        let setting = theirs.iter().find(|s| s.key == key).unwrap_or_else(|| panic!("{what}: not a listed setting"));
                        assert!(setting.available_in(target_family).is_ok(), "{what}: the destination doesn't have it");
                        if setting.repr == Repr::Key {
                            match check_key(target_family, value) {
                                KeyCheck::Named => {}
                                KeyCheck::Numeric => assert!(registry::same_input(source_family, target_family), "{what}: a numeric code across GLFW and SDL3"),
                                KeyCheck::Invalid => panic!("{what}: a key name the destination doesn't know"),
                            }
                        } else {
                            let decoded = setting.decode(value).unwrap_or_else(|| panic!("{what}: unreadable"));
                            assert!(setting.accepts(target_family, &decoded), "{what}: a value the destination doesn't keep");
                        }
                    }
                }
            }
            assert_eq!(covered.len(), names.len(), "every key name of {} was tried", source_family.id);
        }
        // Not vacuous: key names were held back between GLFW and SDL3 versions, in both directions.
        for pair in [("26.3", "26.2"), ("26.3", "26.1"), ("26.2", "26.3"), ("26.1", "26.3")] {
            assert!(skipped_binds[&pair] > 0, "{pair:?}");
        }
    }

    #[test]
    fn a_damaged_destination_file_is_blocked() {
        let source = profile("a", "26.3", &bench());
        let mut target = profile("b", "26.3", &bench());
        target.options = OptionsFile::Damaged("options.txt has text that isn't UTF-8, so Flint won't change it.".into());
        assert_eq!(run(&source, &target, &[SyncCategory::Video]).plan.blocked.as_deref(), Some("options.txt has text that isn't UTF-8, so Flint won't change it."));
    }

    #[test]
    fn a_missing_destination_file_is_created_only_for_a_verified_installed_version() {
        let source = profile("a", "26.3", &edited(&bench(), &[("fov", "0.5")]));
        let mut target = profile("b", "26.2", "");
        target.options = OptionsFile::Missing;
        let work = run(&source, &target, &[SyncCategory::Video]);
        let doc = written(&work);
        assert_eq!(doc.to_string().lines().next(), Some("version:4903"), "starts with the destination's data version");
        assert_eq!(doc.get("fov"), Some("0.5"));
        assert!(work.plan.changes.iter().all(|c| c.kind == ChangeKind::Add));
        assert!(work.plan.untouched.starts_with("A new options.txt"));
        // The copied quality options need Custom, and the rest of a new file starts at Minecraft's defaults.
        assert_eq!(change(&work, "video.graphicsPreset").unwrap().note.as_deref(), Some(PRESET_NEW_FILE_NOTE));
        // Without the jar the data version is unknown: blocked.
        target.support.data_version = None;
        assert_eq!(run(&source, &target, &[SyncCategory::Video]).plan.blocked.as_deref(), Some("Install or play this profile once first."));
    }

    #[test]
    fn a_source_without_options_has_nothing_to_copy_and_says_why() {
        let mut source = profile("a", "26.3", "");
        source.options = OptionsFile::Missing;
        let target = profile("b", "26.3", &bench());
        let work = run(&source, &target, &[SyncCategory::Video, SyncCategory::Audio]);
        assert!(work.writes.is_empty());
        assert_eq!(work.plan.skipped.len(), 2);
        assert!(work.plan.skipped[0].reason.contains("has no Minecraft settings yet"));
    }

    #[test]
    fn left_out_settings_are_listed_so_they_can_be_put_back() {
        let source = profile("a", "26.3", &edited(&bench(), &[("fov", "0.5"), ("gamma", "1.0")]));
        let target = profile("b", "26.3", &bench());
        let excluded: HashSet<String> = ["video.fov".to_owned()].into();
        let work = plan_target(&source, &target, None, &[SyncCategory::Video], &excluded);
        assert_eq!(skipped(&work, "video.fov").unwrap().reason, LEFT_OUT);
        assert!(change(&work, "video.gamma").is_some());
        assert_eq!(written(&work).get("fov"), Some("0.0"));
    }

    #[test]
    fn high_contrast_is_shown_but_never_copied() {
        let source = profile("a", "26.3", &edited(&bench(), &[("highContrast", "true")]));
        let target = profile("b", "26.3", &bench());
        let work = run(&source, &target, &[SyncCategory::Accessibility]);
        assert!(skipped(&work, "accessibility.highContrast").unwrap().reason.contains("High Contrast resource pack"));
        assert!(work.writes.is_empty());
    }

    #[test]
    fn the_plan_serialises_as_the_contract_describes() {
        let source = profile("a", "26.3", &edited(&bench(), &[("fov", "0.5")]));
        let target = profile("b", "26.3", &bench());
        let work = run(&source, &target, &[SyncCategory::Video]);
        let json = serde_json::to_value(&work.plan).unwrap();
        assert_eq!(json["profileId"], "b");
        assert_eq!(json["support"]["level"], "verified");
        assert_eq!(json["support"]["family"], "26.3");
        assert_eq!(
            json["changes"][0],
            serde_json::json!({ "settingId": "video.fov", "label": "Field of view", "category": "video", "current": "Normal", "proposed": "90°", "kind": "change", "currentRaw": "0.0", "proposedRaw": "0.5" })
        );
        assert_eq!(json["unchangedCount"], work.plan.unchanged_count);
        assert!(json.get("blocked").is_none());
    }
}
