//! ATLauncher. Not installed on the PC this was written on: written from its source (ATLauncher/ATLauncher master:
//! data/Instance.java, InstanceLauncher.java, LoaderVersion.java, DisableableMod.java, FileSystem.java; Windows
//! installer packaging/windows-setup/installer.iss) and tested on layouts built from it.
//! - Data folder: where it runs from. The Windows installer puts it in `%APPDATA%\ATLauncher`; on macOS it is inside
//!   `ATLauncher.app/Contents/Java` (the Modrinth App's importer probes `~/Library/Application Support/ATLauncher`, so
//!   both are); Linux packages use `~/.local/share/atlauncher` or the Flatpak folder. Anywhere else: choose the folder.
//! - A data folder has `configs/`, and `instances/<folder>/instance.json`: a Mojang version JSON (`id` is the Minecraft
//!   version) with a `launcher` object: `name`, `pack`, `loaderVersion` {type Fabric|Forge|Quilt|NeoForge|LegacyFabric…,
//!   version}, `lastPlayed` (epoch milliseconds). `launcher.account` and the configs' `accounts.json` are never read.
//! - The game folder is the instance folder. Disabled mods are moved to `disabledmods/` and stay behind (said so).
//!   Icon: `instance.png`, else `configs/images/<pack name, lowercase letters and digits>.png`.

use super::{epoch_seconds, iso_seconds, read_json, sub_dirs, text, Adapter, Found};
use crate::import::{scan, Env, ImportLoader, LauncherId, Source};
use serde_json::Value;
use std::path::{Path, PathBuf};

pub struct AtLauncher;

impl Adapter for AtLauncher {
    fn id(&self) -> LauncherId {
        LauncherId::Atlauncher
    }

    fn name(&self) -> &'static str {
        "ATLauncher"
    }

    fn locations(&self, env: &Env) -> Vec<PathBuf> {
        if super::windows() {
            super::under(&env.appdata, "ATLauncher").into_iter().collect()
        } else if super::macos() {
            [super::under(&env.appdata, "ATLauncher"), env.fixed_places.then(|| PathBuf::from("/Applications/ATLauncher.app/Contents/Java"))].into_iter().flatten().collect()
        } else {
            [super::under(&env.local, "atlauncher"), super::under(&env.home, ".var/app/com.atlauncher.ATLauncher/data")].into_iter().flatten().collect()
        }
    }

    fn read(&self, root: &Path, _env: &Env) -> Found {
        if !scan::real_dir(&root.join("configs")) {
            return Found::NotHere;
        }
        Found::Profiles(sub_dirs(&root.join("instances")).into_iter().filter(|d| scan::real_file(&d.join("instance.json")).is_some()).filter_map(|d| instance(&d, Some(root))).collect())
    }

    fn instance(&self, dir: &Path, _env: &Env) -> Option<Source> {
        instance(dir, None).filter(|s| s.problem.is_none())
    }
}

/// An instance, or None when its instance.json isn't ATLauncher's (no `launcher` object).
fn instance(dir: &Path, root: Option<&Path>) -> Option<Source> {
    let folder = dir.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let json = match read_json(&dir.join("instance.json"))? {
        Ok(json) if json["launcher"].is_object() => json,
        Ok(_) => return None,
        Err(e) => {
            let mut s = Source::new(LauncherId::Atlauncher, &folder, dir.to_path_buf());
            s.problem = Some(e);
            return Some(s);
        }
    };
    let launcher = &json["launcher"];
    let mut s = Source::new(LauncherId::Atlauncher, &text(launcher, "name").unwrap_or(folder), dir.to_path_buf());
    s.game_version = text(&json, "id");
    let loader = &launcher["loaderVersion"];
    s.loader = match loader["type"].as_str().map(str::to_ascii_lowercase).as_deref() {
        None => ImportLoader::Vanilla,
        Some("fabric") | Some("legacyfabric") => ImportLoader::Fabric,
        Some("forge") => ImportLoader::Forge,
        Some("neoforge") => ImportLoader::Neoforge,
        Some("quilt") => ImportLoader::Quilt,
        Some(_) => ImportLoader::Unknown,
    };
    s.loader_version = text(loader, "version");
    s.last_played = match &launcher["lastPlayed"] {
        Value::Number(n) => n.as_i64().and_then(epoch_seconds),
        Value::String(t) => iso_seconds(t),
        _ => None,
    };
    let pack_icon = text(launcher, "pack").map(|p| p.to_ascii_lowercase().chars().filter(char::is_ascii_alphanumeric).collect::<String>()).filter(|p| !p.is_empty());
    s.icon = Some(dir.join("instance.png")).filter(|p| scan::real_file(p).is_some()).or_else(|| Some(root?.join("configs").join("images").join(format!("{}.png", pack_icon?))));
    if std::fs::read_dir(dir.join("disabledmods")).is_ok_and(|mut d| d.next().is_some()) {
        s.notes.push("Its disabled mods (kept in disabledmods) stay behind.".into());
    }
    Some(s)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::tests::{fabric_jar, import_everything, write, World};
    use crate::import::ImportSupport;

    #[test]
    fn atlauncher_instances_are_read_from_their_version_files() {
        let world = World::new();
        let root = AtLauncher.locations(&world.env)[0].clone();
        write(&root, "configs/ATLauncher.json", b"{\"proxy\":\"secret\"}");
        write(&root, "configs/accounts.json", b"[{\"accessToken\":\"secret\"}]");
        write(&root, "configs/images/allthemods9.png", b"\x89PNG\r\n\x1a\npack");
        let atm = root.join("instances/AllTheMods9");
        write(&atm, "instance.json", br#"{"id":"1.20.1","launcher":{"name":"All The Mods 9","pack":"All The Mods 9","version":"0.3.0","loaderVersion":{"type":"Forge","version":"47.2.0","rawVersion":"1.20.1-47.2.0"},"lastPlayed":1761926400000,"numPlays":12,"mods":[],"account":"someone"},"libraries":[]}"#);
        let pvp = root.join("instances/FabricPvP");
        write(&pvp, "instance.json", br#"{"id":"1.21.1","launcher":{"name":"Fabric PvP","pack":"Fabric PvP","loaderVersion":{"type":"Fabric","version":"0.16.9"},"lastPlayed":1761000000000,"mods":[{"file":"zoom.jar","type":"mods","disabled":true}]}}"#);
        write(&pvp, "instance.png", b"\x89PNG\r\n\x1a\nicon");
        fabric_jar(&pvp, "mods/sodium.jar");
        fabric_jar(&pvp, "disabledmods/zoom.jar");
        write(&pvp, "options.txt", b"version:3955\n");
        let vanilla = root.join("instances/Vanilla");
        write(&vanilla, "instance.json", br#"{"id":"1.21.11","launcher":{"name":"Vanilla","loaderVersion":null}}"#);
        write(&vanilla, "saves/World/level.dat", b"level");

        let (listed, result) = import_everything(&world, &AtLauncher, true);
        let by = |name: &str| listed.listing.profiles.iter().find(|p| p.name == name).unwrap_or_else(|| panic!("{name}"));
        let atm = by("All The Mods 9");
        assert_eq!((atm.game_version.as_deref(), atm.loader, atm.loader_version.as_deref(), atm.last_played, atm.support), (Some("1.20.1"), ImportLoader::Forge, Some("47.2.0"), Some(1761926400), ImportSupport::Unsupported));
        let pvp = by("Fabric PvP");
        assert_eq!((pvp.loader, pvp.mods, pvp.icon.is_some()), (ImportLoader::Fabric, 1, true));
        let vanilla = by("Vanilla");
        assert_eq!((vanilla.loader, vanilla.worlds), (ImportLoader::Vanilla, 1));
        assert_eq!(result.profiles.iter().filter(|p| p.profile_id.is_some()).count(), 2);
        let pvp_result = result.profiles.iter().find(|p| p.name == "Fabric PvP").unwrap();
        assert!(pvp_result.warnings.iter().any(|w| w.contains("disabledmods")));
        let profile = world.existing().profiles.into_iter().find(|p| p.name == "Fabric PvP").unwrap();
        let dir = world.state.paths.profile_dir(&profile.id);
        assert!(dir.join("mods/sodium.jar").is_file() && !dir.join("instance.json").exists() && !dir.join("disabledmods").exists());
    }
}
