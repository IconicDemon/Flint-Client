//! Settings → Syncing: one switch per kind of thing Auto Sync shares, and the profiles left out of it.
//!
//! The choices live in `<data>/sync/choices.json`, beside the store but apart from it, so they outlast a
//! store that is set aside. Every store entry belongs to exactly one switch (`Switch::of`). What Auto Sync
//! shared before these switches existed stays on by default; the kinds added with them (servers, saved
//! hotbars, command history) start off.

use super::super::registry::{self, Category, KEY_PREFIX};
use crate::core::write_atomic;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

const FILE: &str = "choices.json";
const SCHEMA: u32 = 1;

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
#[serde(rename_all = "camelCase")]
pub enum Switch {
    /// Minecraft's options.txt: video, sound, accessibility, chat, skin and language.
    GameOptions,
    /// Key bindings (Minecraft's, mods' and Flint's) and the mouse settings of Minecraft's Controls screen.
    Keybinds,
    /// The multiplayer server list (servers.dat).
    Servers,
    /// Resource pack files and the selection.
    ResourcePacks,
    /// Shader pack files and Iris's shader selection.
    ShaderPacks,
    /// Saved creative hotbars (hotbar.nbt).
    Hotbars,
    /// Commands typed in chat (command_history.txt).
    CommandHistory,
    /// Supported mods' settings and Flint's own in-game files (HUD layouts, Control Center, shortcuts).
    ModSettings,
}

impl Switch {
    pub const ALL: [Switch; 8] = [
        Switch::GameOptions,
        Switch::Keybinds,
        Switch::Servers,
        Switch::ResourcePacks,
        Switch::ShaderPacks,
        Switch::Hotbars,
        Switch::CommandHistory,
        Switch::ModSettings,
    ];

    pub fn id(self) -> &'static str {
        match self {
            Switch::GameOptions => "gameOptions",
            Switch::Keybinds => "keybinds",
            Switch::Servers => "servers",
            Switch::ResourcePacks => "resourcePacks",
            Switch::ShaderPacks => "shaderPacks",
            Switch::Hotbars => "hotbars",
            Switch::CommandHistory => "commandHistory",
            Switch::ModSettings => "modSettings",
        }
    }

    pub fn from_id(id: &str) -> Option<Switch> {
        Switch::ALL.into_iter().find(|s| s.id() == id)
    }

    /// Off until turned on: the kinds Auto Sync didn't share before Settings → Syncing.
    pub fn default_on(self) -> bool {
        !matches!(self, Switch::Servers | Switch::Hotbars | Switch::CommandHistory)
    }

    /// The switch a store entry belongs to, by its key ("options/fov", "packs/shaderpacks/<sha>", ...).
    pub fn of(key: &str) -> Switch {
        let (kind, rest) = key.split_once('/').unwrap_or((key, ""));
        match kind {
            "options" if rest.starts_with(KEY_PREFIX) || registry::category_of_key(rest) == Some(Category::Controls) => Switch::Keybinds,
            "options" => Switch::GameOptions,
            "packs" if rest.starts_with("shaderpacks/") => Switch::ShaderPacks,
            "packs" => Switch::ResourcePacks,
            "servers" => Switch::Servers,
            "hotbars" => Switch::Hotbars,
            "commands" => Switch::CommandHistory,
            // "mods/" and Flint's own "flint/".
            _ => Switch::ModSettings,
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Choices {
    #[serde(default)]
    pub schema: u32,
    /// Switches set away from their default, by id.
    #[serde(default)]
    pub switches: BTreeMap<String, bool>,
    /// Profiles left out ("Don't sync this profile"): Auto Sync never reads or writes their files.
    #[serde(default)]
    pub excluded: BTreeSet<String>,
}

impl Choices {
    pub fn on(&self, switch: Switch) -> bool {
        self.switches.get(switch.id()).copied().unwrap_or_else(|| switch.default_on())
    }

    pub fn set(&mut self, switch: Switch, on: bool) {
        if on == switch.default_on() {
            self.switches.remove(switch.id());
        } else {
            self.switches.insert(switch.id().to_owned(), on);
        }
    }

    pub fn excludes(&self, profile_id: &str) -> bool {
        self.excluded.contains(profile_id)
    }
}

/// The choices as saved; the defaults when there are none or they can't be read (the file is then replaced
/// at the next change).
pub fn load(store_root: &Path) -> Choices {
    std::fs::read(store_root.join(FILE)).ok().and_then(|bytes| serde_json::from_slice::<Choices>(&bytes).ok()).filter(|c| c.schema <= SCHEMA).unwrap_or_default()
}

pub fn save(store_root: &Path, choices: &Choices) -> Result<(), String> {
    let bytes = serde_json::to_vec_pretty(&Choices { schema: SCHEMA, ..choices.clone() }).map_err(|e| e.to_string())?;
    write_atomic(&store_root.join(FILE), &bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;

    #[test]
    fn every_entry_has_one_switch() {
        let cases = [
            ("options/fov", Switch::GameOptions),
            ("options/soundCategory_master", Switch::GameOptions),
            ("options/lang", Switch::GameOptions),
            ("options/someMod.unknown", Switch::GameOptions),
            ("options/key_key.jump", Switch::Keybinds),
            ("options/key_key.flintclient.control_center", Switch::Keybinds),
            ("options/key_somemod.zoom", Switch::Keybinds),
            ("options/mouseSensitivity", Switch::Keybinds),
            ("options/toggleSprint", Switch::Keybinds),
            ("packs/resourcepacks/selection", Switch::ResourcePacks),
            ("packs/resourcepacks/0123", Switch::ResourcePacks),
            ("packs/shaderpacks/selection", Switch::ShaderPacks),
            ("servers/order", Switch::Servers),
            ("servers/0a1b2c3d4e5f6071", Switch::Servers),
            ("hotbars/hotbar.nbt", Switch::Hotbars),
            ("commands/command_history.txt", Switch::CommandHistory),
            ("mods/sodium/sodium-options.json", Switch::ModSettings),
            ("flint/layout/pvp", Switch::ModSettings),
            ("flint/shortcuts", Switch::ModSettings),
        ];
        for (key, switch) in cases {
            assert_eq!(Switch::of(key), switch, "{key}");
        }
        for switch in Switch::ALL {
            assert_eq!(Switch::from_id(switch.id()), Some(switch));
        }
    }

    #[test]
    fn what_auto_sync_shared_before_stays_on_and_the_new_kinds_start_off() {
        let dir = TestDir::new();
        let choices = load(&dir.0);
        let on: Vec<Switch> = Switch::ALL.into_iter().filter(|s| choices.on(*s)).collect();
        assert_eq!(on, [Switch::GameOptions, Switch::Keybinds, Switch::ResourcePacks, Switch::ShaderPacks, Switch::ModSettings]);
        let mut choices = choices;
        choices.set(Switch::Servers, true);
        choices.set(Switch::ResourcePacks, false);
        choices.set(Switch::GameOptions, true);
        choices.excluded.insert("p1".into());
        save(&dir.0, &choices).unwrap();
        let back = load(&dir.0);
        assert!(back.on(Switch::Servers) && !back.on(Switch::ResourcePacks) && back.on(Switch::GameOptions));
        assert!(back.excludes("p1") && !back.excludes("p2"));
        assert_eq!(back.switches.len(), 2, "only what differs from the defaults is kept");
        assert!(!back.on(Switch::of("packs/resourcepacks/selection")) && back.on(Switch::of("servers/order")));
        std::fs::write(dir.0.join(FILE), b"{ damaged").unwrap();
        assert_eq!(load(&dir.0), Choices::default());
    }
}
