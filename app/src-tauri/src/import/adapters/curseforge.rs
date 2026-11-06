//! CurseForge app. Not installed on the PC this was written on: written from CurseForge's documentation and published
//! instance files, and tested on layouts built from them.
//! - Instances folder (support.curseforge.com, "Getting started"): `%USERPROFILE%\curseforge\minecraft\Instances` on
//!   Windows, `~/Documents/curseforge/minecraft/Instances` on macOS; older `Documents\curseforge`, `Documents\Curse` and
//!   `Documents\Twitch` folders are probed too (the Modrinth App's importer falls back to the first). The player can
//!   move it in CurseForge's settings, which Flint can't read: then they choose the folder.
//! - Each instance folder holds `minecraftinstance.json` (the check every other importer uses). Keys are read in any
//!   letter case (2017 files wrote `Name`): `name`, `gameVersion`, `baseModLoader` (null for Vanilla; `name`
//!   `forge-47.2.0` / `neoforge-21.1.200` / `fabric-0.16.9-1.20.4` / `quilt-0.25.0-1.20.6`, `forgeVersion` (the loader's
//!   version, for every type in current files), `minecraftVersion`, `type` 1 Forge, 4 Fabric, 5 Quilt, 6 NeoForge per
//!   docs.curseforge.com ModLoaderType), `lastPlayed` (ISO 8601; year 1 = never), `profileImagePath`, `guid`.
//! - The game folder is the instance folder itself (`installPath` is ignored: instances get copied between computers).

use super::{get_ci, iso_seconds, loader_named, read_json, sub_dirs, text_ci, Adapter, Found};
use crate::import::{scan, Env, ImportLoader, LauncherId, Source};
use serde_json::Value;
use std::path::{Path, PathBuf};

pub struct CurseForge;

const FILE: &str = "minecraftinstance.json";

impl Adapter for CurseForge {
    fn id(&self) -> LauncherId {
        LauncherId::Curseforge
    }

    fn name(&self) -> &'static str {
        "CurseForge"
    }

    fn locations(&self, env: &Env) -> Vec<PathBuf> {
        let mut out = Vec::new();
        if super::windows() {
            out.extend(super::under(&env.home, "curseforge/minecraft/Instances"));
        }
        out.extend(super::under(&env.documents, "curseforge/minecraft/Instances"));
        if super::windows() {
            out.extend(super::under(&env.documents, "Curse/Minecraft/Instances"));
            out.extend(super::under(&env.documents, "Twitch/Minecraft/Instances"));
        }
        out
    }

    fn read(&self, root: &Path, _env: &Env) -> Found {
        // The Instances folder itself, or a folder above it.
        for dir in [root.join("Instances"), root.join("minecraft").join("Instances"), root.to_path_buf()] {
            let found: Vec<PathBuf> = sub_dirs(&dir).into_iter().filter(|d| scan::real_file(&d.join(FILE)).is_some()).collect();
            if !found.is_empty() {
                return Found::Profiles(found.iter().map(|d| instance(d)).collect());
            }
        }
        // An empty Instances folder where CurseForge keeps it: installed, no profiles.
        let named = |p: &Path, n: &str| p.file_name().is_some_and(|f| f.to_string_lossy().eq_ignore_ascii_case(n));
        if named(root, "Instances") && root.parent().is_some_and(|p| named(p, "minecraft")) {
            Found::Profiles(Vec::new())
        } else {
            Found::NotHere
        }
    }

    fn instance(&self, dir: &Path, _env: &Env) -> Option<Source> {
        scan::real_file(&dir.join(FILE))?;
        Some(instance(dir))
    }
}

fn instance(dir: &Path) -> Source {
    let folder = dir.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let json = match read_json(&dir.join(FILE)) {
        Some(Ok(json)) => json,
        _ => {
            let mut s = Source::new(LauncherId::Curseforge, &folder, dir.to_path_buf());
            s.problem = Some("Its minecraftinstance.json is damaged.".into());
            return s;
        }
    };
    let mut s = Source::new(LauncherId::Curseforge, &text_ci(&json, "name").unwrap_or(folder), dir.to_path_buf());
    s.id = text_ci(&json, "guid");
    let base = get_ci(&json, "baseModLoader").filter(|b| b.is_object());
    s.game_version = text_ci(&json, "gameVersion").or_else(|| base.and_then(|b| text_ci(b, "minecraftVersion")));
    let (loader, loader_version) = match base {
        None => (ImportLoader::Vanilla, None),
        Some(b) => loader_of(b, s.game_version.as_deref()),
    };
    s.loader = loader;
    s.loader_version = loader_version;
    s.last_played = text_ci(&json, "lastPlayed").as_deref().and_then(iso_seconds);
    s.icon = text_ci(&json, "profileImagePath").map(PathBuf::from).filter(|p| p.is_absolute());
    s
}

/// The loader and its version from `baseModLoader`: its name's prefix, else its `type` number.
fn loader_of(base: &Value, game: Option<&str>) -> (ImportLoader, Option<String>) {
    let name = text_ci(base, "name").unwrap_or_default();
    let lower = name.to_ascii_lowercase();
    let (loader, rest) = if let Some(rest) = lower.strip_prefix("neoforge-") {
        (ImportLoader::Neoforge, rest)
    } else if let Some(rest) = lower.strip_prefix("forge-") {
        (ImportLoader::Forge, rest)
    } else if let Some(rest) = lower.strip_prefix("fabric-") {
        (ImportLoader::Fabric, rest)
    } else if let Some(rest) = lower.strip_prefix("quilt-") {
        (ImportLoader::Quilt, rest)
    } else {
        let by_type = match get_ci(base, "type").and_then(Value::as_i64) {
            Some(1) => ImportLoader::Forge,
            Some(4) => ImportLoader::Fabric,
            Some(5) => ImportLoader::Quilt,
            Some(6) => ImportLoader::Neoforge,
            _ => loader_named(&lower),
        };
        (by_type, "")
    };
    // Fabric and Quilt names end with the Minecraft version; take it off rather than splitting blindly.
    let from_name = game.and_then(|g| rest.strip_suffix(&format!("-{}", g.to_ascii_lowercase()))).unwrap_or(rest);
    let version = text_ci(base, "forgeVersion").or_else(|| (!from_name.is_empty()).then(|| from_name.to_owned()));
    (loader, version)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::tests::{fabric_jar, import_everything, write, World};
    use crate::import::ImportSupport;

    #[test]
    fn curseforge_instances_are_read_from_their_own_folders() {
        let world = World::new();
        let root = CurseForge.locations(&world.env)[0].clone();
        // A current file (the shape of published ones), with the huge embedded strings left short.
        let c2m = root.join("C2M");
        write(
            &c2m,
            FILE,
            "\u{feff}{\"name\":\"C2M\",\"gameVersion\":\"1.21.1\",\"lastPlayed\":\"2025-10-27T18:12:38.57Z\",\"playedCount\":7,\"timePlayed\":11632092,\
             \"baseModLoader\":{\"name\":\"neoforge-21.1.200\",\"forgeVersion\":\"21.1.200\",\"minecraftVersion\":\"1.21.1\",\"type\":6,\"filename\":\"neoforge-21.1.200.jar\",\"versionJson\":\"{}\"},\
             \"installPath\":\"C:\\\\Users\\\\someone\\\\curseforge\\\\minecraft\\\\Instances\\\\C2M\\\\\",\"guid\":\"0f6c2a3e-1111-2222-3333-444455556666\",\"isEnabled\":true,\
             \"installedModpack\":null,\"projectID\":0,\"fileID\":0,\"installedAddons\":[]}"
                .as_bytes(),
        );
        write(&c2m, "options.txt", b"version:3955\n");
        write(&c2m, "mods/c2me.jar", b"forge jar");
        let fabric = root.join("life-in-the-moods");
        write(&fabric, FILE, br#"{"name":"Life in the Moods","gameVersion":"1.21.1","lastPlayed":"0001-01-01T00:00:00","baseModLoader":{"name":"fabric-0.16.9-1.21.1","forgeVersion":"0.16.9","minecraftVersion":"1.21.1","type":4},"guid":"aaaaaaaa-0000-0000-0000-000000000000"}"#);
        fabric_jar(&fabric, "mods/sodium.jar");
        fabric_jar(&fabric, "mods/BetterFps.jar.disabled");
        write(&fabric, "options.txt", b"version:3955\n");
        write(&fabric, "manifest.json", b"{}");
        write(&fabric, "modelist.html", b"<ul>");
        // A 2017 file: capitalised keys, Vanilla.
        let old = root.join("Revolution");
        write(&old, FILE, br#"{"Name":"Revolution","GameVersion":"1.21.11","BaseModLoader":null,"LastPlayed":"2017-05-01T10:00:00Z"}"#);
        write(&old, "options.txt", b"version:4671\n");
        write(&root.join("Damaged"), FILE, b"{ nope");

        let (listed, result) = import_everything(&world, &CurseForge, false);
        assert_eq!(listed.listing.profiles.len(), 4);
        let by = |name: &str| listed.listing.profiles.iter().find(|p| p.name == name).unwrap_or_else(|| panic!("{name}"));
        let c2m = by("C2M");
        assert_eq!((c2m.game_version.as_deref(), c2m.loader, c2m.loader_version.as_deref(), c2m.support, c2m.last_played), (Some("1.21.1"), ImportLoader::Neoforge, Some("21.1.200"), ImportSupport::Settings, Some(1761588758)));
        let moods = by("Life in the Moods");
        assert_eq!((moods.loader, moods.loader_version.as_deref(), moods.mods, moods.last_played), (ImportLoader::Fabric, Some("0.16.9"), 2, None), "year 1 means never");
        let old = by("Revolution");
        assert_eq!((old.game_version.as_deref(), old.loader), (Some("1.21.11"), ImportLoader::Vanilla));
        assert_eq!(by("Damaged").support, ImportSupport::Unsupported);
        assert_eq!(result.profiles.iter().filter(|p| p.profile_id.is_some()).count(), 3);
        let profile = world.existing().profiles.into_iter().find(|p| p.name == "Life in the Moods").unwrap();
        let dir = world.state.paths.profile_dir(&profile.id);
        assert!(dir.join("mods/sodium.jar").is_file() && dir.join("mods/BetterFps.jar.disabled").is_file());
        assert!(!dir.join(FILE).exists() && !dir.join("manifest.json").exists() && !dir.join("modelist.html").exists());
        // The guid keys it: renaming the folder keeps it the same profile.
        assert_eq!(listed.sources.iter().find(|s| s.name == "C2M").unwrap().id.as_deref(), Some("0f6c2a3e-1111-2222-3333-444455556666"));
    }

    #[test]
    fn loader_names_are_split_with_the_game_version() {
        let base = |name: &str, t: i64| serde_json::json!({ "name": name, "type": t });
        assert_eq!(loader_of(&base("fabric-0.16.9-1.20.4", 4), Some("1.20.4")), (ImportLoader::Fabric, Some("0.16.9".into())));
        assert_eq!(loader_of(&base("quilt-0.25.0-1.20.6", 5), Some("1.20.6")), (ImportLoader::Quilt, Some("0.25.0".into())));
        assert_eq!(loader_of(&base("forge-47.2.0", 1), Some("1.20.1")), (ImportLoader::Forge, Some("47.2.0".into())));
        assert_eq!(loader_of(&base("something", 6), None).0, ImportLoader::Neoforge, "by type when the name says nothing");
    }
}
