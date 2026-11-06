//! GDLauncher, both generations, under one entry. Not installed on the PC this was written on: written from their
//! sources and tested on layouts built from them.
//! - Legacy (Electron, archived; gorilla-devs/GDLauncher @7e5346be: public/electron.js, selectors.js, getInstances.js):
//!   `<appData>/gdlauncher_next` (`%APPDATA%`, `~/Library/Application Support`, Linux `~/.config`), moved by the path in
//!   its `override.data`. `instances/<folder>/config.json`: `loader` {loaderType vanilla|forge|fabric, mcVersion,
//!   loaderVersion} (the oldest files: `modloader` [type, mc, version, …]), `lastPlayed` (epoch ms), `background` (icon,
//!   relative). The name is the folder's. An empty config.json is replaced by `config.bak.json`; `installing.lock` marks
//!   an unfinished install; `mods/__JLF__.jar` is the launcher's own. The game folder is the instance folder.
//! - Carbon (current; gorilla-devs/GDLauncher-Carbon @35533d7c: managers/instance/schema/v1.rs, carbon_rt_path):
//!   `<appData>/gdlauncher_carbon` (Linux `~/.local/share`), whose runtime folder is `GDL_RUNTIME_PATH`, the path in
//!   `runtime_path_override`, or `data`. `instances/<folder>/instance.json` with `"_version":"1"`: `name`, `icon`
//!   (relative), `last_played` (RFC 3339), `game_configuration.version` {release, modloaders [{type
//!   Forge|Neoforge|Fabric|Quilt, version}]} (a bare string is a custom version Flint can't read). `.setup/` marks an
//!   unfinished install. The game folder is `<instance>/instance/`. Its `gdl_conf.db` holds sign-ins and is never opened.

use super::{epoch_seconds, iso_seconds, loader_named, read_json, read_text, relative, sub_dirs, text, Adapter, Found};
use crate::import::{scan, Env, ImportLoader, LauncherId, Source};
use serde_json::Value;
use std::path::{Path, PathBuf};

pub struct GdLauncher;

impl Adapter for GdLauncher {
    fn id(&self) -> LauncherId {
        LauncherId::Gdlauncher
    }

    fn name(&self) -> &'static str {
        "GDLauncher"
    }

    fn locations(&self, env: &Env) -> Vec<PathBuf> {
        let linux = !super::windows() && !super::macos();
        let next = if linux { super::under(&env.config, "gdlauncher_next") } else { super::under(&env.appdata, "gdlauncher_next") };
        let carbon = if linux { super::under(&env.local, "gdlauncher_carbon") } else { super::under(&env.appdata, "gdlauncher_carbon") };
        [env.var("GDL_RUNTIME_PATH"), carbon, next].into_iter().flatten().collect()
    }

    fn read(&self, root: &Path, _env: &Env) -> Found {
        let named = |n: &str| root.file_name().is_some_and(|f| f.to_string_lossy().eq_ignore_ascii_case(n));
        let moved = |file: &str| read_text(&root.join(file)).and_then(Result::ok).map(|t| PathBuf::from(t.trim())).filter(|p| p.is_absolute() && p.is_dir());
        let mut sources = Vec::new();
        let mut claimed = false;
        // Legacy: its data folder (or where override.data moved it).
        let data = moved("override.data").unwrap_or_else(|| root.to_path_buf());
        let legacy: Vec<PathBuf> = sub_dirs(&data.join("instances")).into_iter().filter(|d| legacy_config(d).is_some()).collect();
        if !legacy.is_empty() || (named("gdlauncher_next") && data.join("instances").is_dir()) {
            claimed = true;
            sources.extend(legacy.iter().map(|d| legacy_instance(d)));
        }
        // Carbon: its runtime folder.
        let runtime = moved("runtime_path_override").or_else(|| Some(root.join("data")).filter(|d| d.join("instances").is_dir())).unwrap_or_else(|| root.to_path_buf());
        let carbon: Vec<PathBuf> = sub_dirs(&runtime.join("instances")).into_iter().filter(|d| carbon_shaped(d)).collect();
        if !carbon.is_empty() || (named("gdlauncher_carbon") && runtime.join("instances").is_dir()) {
            claimed = true;
            sources.extend(carbon.iter().map(|d| carbon_instance(d)));
        }
        if claimed {
            Found::Profiles(sources)
        } else {
            Found::NotHere
        }
    }

    fn instance(&self, dir: &Path, _env: &Env) -> Option<Source> {
        if legacy_config(dir).is_some() {
            Some(legacy_instance(dir))
        } else if carbon_shaped(dir) {
            Some(carbon_instance(dir))
        } else {
            None
        }
    }
}

/// A legacy instance's config: `config.json`, or its backup when the file was left empty.
fn legacy_config(dir: &Path) -> Option<Result<Value, String>> {
    let main = dir.join("config.json");
    let file = match scan::real_file(&main) {
        Some(meta) if meta.len() > 0 => main,
        _ => dir.join("config.bak.json"),
    };
    match read_json(&file)? {
        Ok(v) if v.get("loader").is_some_and(Value::is_object) || v.get("modloader").is_some_and(Value::is_array) => Some(Ok(v)),
        Ok(_) => None,
        Err(e) => Some(Err(e)),
    }
}

fn legacy_instance(dir: &Path) -> Source {
    let folder = dir.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let mut s = Source::new(LauncherId::Gdlauncher, &folder, dir.to_path_buf());
    s.launcher_mods = vec!["__jlf__.jar".into()];
    let json = match legacy_config(dir) {
        Some(Ok(json)) => json,
        _ => {
            s.problem = Some("Its config.json is damaged.".into());
            return s;
        }
    };
    let (kind, version, loader_version) = match json.get("loader").filter(|l| l.is_object()) {
        Some(l) => (text(l, "loaderType"), text(l, "mcVersion"), text(l, "loaderVersion")),
        None => {
            let a = |i: usize| json["modloader"][i].as_str().map(str::to_owned);
            (a(0), a(1), a(2))
        }
    };
    s.loader = loader_named(kind.as_deref().unwrap_or("vanilla"));
    s.game_version = version;
    s.loader_version = loader_version;
    s.last_played = json["lastPlayed"].as_i64().and_then(epoch_seconds);
    s.icon = json["background"].as_str().and_then(relative).map(|r| scan::join(dir, &r));
    if scan::real_file(&dir.join("installing.lock")).is_some() {
        s.problem = Some("It isn't fully installed in GDLauncher.".into());
    }
    s
}

fn carbon_shaped(dir: &Path) -> bool {
    matches!(read_json(&dir.join("instance.json")), Some(Ok(v)) if v["_version"].is_string())
}

fn carbon_instance(dir: &Path) -> Source {
    let folder = dir.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let game_dir = dir.join("instance");
    let Some(Ok(json)) = read_json(&dir.join("instance.json")) else {
        let mut s = Source::new(LauncherId::Gdlauncher, &folder, game_dir);
        s.problem = Some("Its instance.json is damaged.".into());
        return s;
    };
    let mut s = Source::new(LauncherId::Gdlauncher, &text(&json, "name").unwrap_or(folder), game_dir);
    s.last_played = text(&json, "last_played").as_deref().and_then(iso_seconds);
    s.icon = json["icon"].as_str().and_then(relative).map(|r| scan::join(dir, &r));
    match &json["game_configuration"]["version"] {
        Value::Object(version) => {
            s.game_version = version.get("release").and_then(Value::as_str).map(str::to_owned);
            let first = version.get("modloaders").and_then(Value::as_array).and_then(|m| m.first().cloned());
            match first {
                None => s.loader = ImportLoader::Vanilla,
                Some(m) => {
                    s.loader = loader_named(m["type"].as_str().unwrap_or_default());
                    s.loader_version = text(&m, "version");
                }
            }
        }
        _ => s.problem = Some("It runs a custom Minecraft version Flint can't read.".into()),
    }
    if json["_version"].as_str() != Some("1") {
        s.problem = Some("It was made by a GDLauncher version Flint can't read yet.".into());
    } else if dir.join(".setup").exists() {
        s.problem = Some("It isn't fully installed in GDLauncher.".into());
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::tests::{fabric_jar, import_everything, write, World};
    use crate::import::ImportSupport;

    #[test]
    fn gdlauncher_legacy_and_carbon_instances_are_read() {
        let world = World::new();
        let locations = GdLauncher.locations(&world.env);
        let carbon_root = locations.iter().find(|p| p.ends_with("gdlauncher_carbon")).unwrap().clone();
        let next_root = locations.iter().find(|p| p.ends_with("gdlauncher_next")).unwrap().clone();
        // Legacy, moved by override.data.
        let moved = world.root.join("D/GDL");
        write(&next_root, "override.data", format!("{}\n", moved.display()).as_bytes());
        let vault = moved.join("instances/Vault Hunters");
        write(&vault, "config.json", br#"{"loader":{"loaderType":"forge","mcVersion":"1.18.2","loaderVersion":"1.18.2-40.1.93","fileID":1,"projectID":2,"source":"curseforge","sourceName":"Vault Hunters 3rd Edition"},"timePlayed":0,"background":"background.png","lastPlayed":1650057794197,"mods":[]}"#);
        let fab = moved.join("instances/Fabric PvP");
        write(&fab, "config.json", b"");
        write(&fab, "config.bak.json", br#"{"loader":{"loaderType":"fabric","mcVersion":"1.21.1","loaderVersion":"0.16.9"},"lastPlayed":1761926400000,"background":"background.png"}"#);
        write(&fab, "background.png", b"\x89PNG\r\n\x1a\nbg");
        fabric_jar(&fab, "mods/sodium.jar");
        fabric_jar(&fab, "mods/__JLF__.jar");
        write(&fab, "options.txt", b"version:3955\n");
        write(&moved, "datastore/accounts.json", b"{\"token\":\"secret\"}");
        // Carbon, in its default runtime folder.
        let runtime = carbon_root.join("data");
        let pack = runtime.join("instances/My Pack");
        write(&pack, "instance.json", br#"{"_version":"1","name":"My Pack","icon":"icon.png","created_at":"2025-03-01T09:00:00Z","updated_at":"2025-03-01T09:00:00Z","last_played":"2025-03-02T09:58:12.789Z","seconds_played":7260,"game_configuration":{"version":{"release":"1.21.1","modloaders":[{"type":"Fabric","version":"0.16.9"}]},"memory":{}},"notes":""}"#);
        fabric_jar(&pack, "instance/mods/sodium.jar");
        write(&pack, "instance/options.txt", b"version:3955\n");
        let half = runtime.join("instances/Half");
        write(&half, "instance.json", br#"{"_version":"1","name":"Half","game_configuration":{"version":{"release":"1.21.1","modloaders":[]}}}"#);
        std::fs::create_dir_all(half.join(".setup")).unwrap();
        write(&runtime, "gdl_conf.db", b"secret refresh token");

        let (listed, result) = import_everything(&world, &GdLauncher, false);
        let by = |name: &str| listed.listing.profiles.iter().find(|p| p.name == name).unwrap_or_else(|| panic!("{name}"));
        assert_eq!(listed.listing.profiles.len(), 4);
        let vault = by("Vault Hunters");
        assert_eq!((vault.game_version.as_deref(), vault.loader, vault.last_played, vault.support), (Some("1.18.2"), ImportLoader::Forge, Some(1650057794), ImportSupport::Unsupported));
        let fab = by("Fabric PvP");
        assert_eq!((fab.loader, fab.mods, fab.icon.is_some()), (ImportLoader::Fabric, 1, true), "the backup config; GDLauncher's own jar isn't counted");
        let pack = by("My Pack");
        assert_eq!((pack.game_version.as_deref(), pack.loader, pack.last_played), (Some("1.21.1"), ImportLoader::Fabric, Some(1740909492)));
        assert!(by("Half").support_note.as_deref().unwrap().contains("fully installed"));
        assert_eq!(result.profiles.iter().filter(|p| p.profile_id.is_some()).count(), 2);
        let profile = world.existing().profiles.into_iter().find(|p| p.name == "Fabric PvP").unwrap();
        let dir = world.state.paths.profile_dir(&profile.id);
        assert!(dir.join("mods/sodium.jar").is_file() && !dir.join("mods/__JLF__.jar").exists() && !dir.join("config.json").exists());
    }
}
