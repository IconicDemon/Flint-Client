//! Feather Client. Data folder `%APPDATA%\.feather` (verified on a PC where only its data remains; the macOS folder is
//! unconfirmed, so `~/Library/Application Support/.feather` and `~/.feather` are both probed). Feather has no instance
//! folders: `settings.json` `mcPath` is the one game folder every version uses (often the official `.minecraft`, with
//! mixed `/` and `\`), and `mods/feather-mods.json` keys everything by version (`<mc>-fabric`, or `1.8.9`/`1.12.2` for
//! Feather's own older builds): `selectedVersion`, `addedMods` (Feather's built-in mod switches, not jars),
//! `customAddedMods` and `modrinthAddedMods` (what the player added), `profiles` (Feather's mod profiles). Listed: the
//! selected version and every version the player added mods to, each as "Feather <version>" on that shared folder.
//! The layout of `user-mods/` is unconfirmed: jars come over only from `user-mods/<version key>/`.

use super::{read_json, sub_dirs, text, version_like, Adapter, Found};
use crate::import::{scan, Env, ImportLoader, LauncherId, Source};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

pub struct Feather;

const NOTE: &str = "Feather's own built-in mods and client settings are Feather's and stay behind.";

impl Adapter for Feather {
    fn id(&self) -> LauncherId {
        LauncherId::Feather
    }

    fn name(&self) -> &'static str {
        "Feather Client"
    }

    fn locations(&self, env: &Env) -> Vec<PathBuf> {
        if super::windows() {
            super::under(&env.appdata, ".feather").into_iter().collect()
        } else {
            [super::under(&env.appdata, ".feather"), super::under(&env.config, ".feather"), super::under(&env.home, ".feather")].into_iter().flatten().collect()
        }
    }

    fn read(&self, root: &Path, env: &Env) -> Found {
        let settings = read_json(&root.join("settings.json"));
        let mods = read_json(&root.join("mods").join("feather-mods.json"));
        let mods = match (settings.is_some(), mods) {
            (false, None) => return Found::NotHere,
            (_, None) => return Found::Profiles(Vec::new()),
            (_, Some(Err(_))) => return Found::Unreadable("Feather's mod list (feather-mods.json) is damaged, so Flint couldn't read its versions.".into()),
            (_, Some(Ok(mods))) => mods,
        };
        let game = settings
            .and_then(Result::ok)
            .and_then(|s| text(&s, "mcPath"))
            .map(|p| PathBuf::from(p.replace(['/', '\\'], std::path::MAIN_SEPARATOR_STR)))
            .filter(|p| p.is_absolute() && p.is_dir())
            .or_else(|| super::default_minecraft(env));
        let Some(game) = game else { return Found::Profiles(Vec::new()) };
        let mut keys: BTreeSet<String> = BTreeSet::new();
        keys.extend(text(&mods, "selectedVersion"));
        for list in ["customAddedMods", "modrinthAddedMods"] {
            if let Some(map) = mods[list].as_object() {
                keys.extend(map.iter().filter(|(_, v)| v.as_array().is_some_and(|a| !a.is_empty())).map(|(k, _)| k.clone()));
            }
        }
        let user_mods: Vec<PathBuf> = sub_dirs(&root.join("user-mods"));
        Found::Profiles(keys.into_iter().filter_map(|key| version(root, &game, &key, &user_mods)).collect())
    }
}

/// One Feather version key as a profile on the shared game folder.
fn version(root: &Path, game: &Path, key: &str, user_mods: &[PathBuf]) -> Option<Source> {
    let (mc, loader) = match key.strip_suffix("-fabric") {
        Some(mc) => (mc, ImportLoader::Fabric),
        // Feather's own builds for 1.8.9 and 1.12.2 aren't a loader Flint runs.
        None => (key, ImportLoader::Unknown),
    };
    if !version_like(mc) {
        return None;
    }
    let mut s = Source::new(LauncherId::Feather, &format!("Feather {mc}"), game.to_path_buf());
    s.id = Some(format!("version:{key}"));
    s.game_version = Some(mc.to_owned());
    s.loader = loader;
    // Only the player's own jars, in the folder named after this version (when there is one).
    let mods = user_mods.iter().find(|d| d.file_name().is_some_and(|n| n.to_string_lossy() == key)).cloned().unwrap_or_else(|| root.join("user-mods").join(key));
    s.overrides.push(("mods".into(), mods));
    // Feather's own copy of the options (vanilla format) only when the game folder has none.
    let copy = game.join("feather").join("mc_options_v2.txt");
    if scan::real_file(&game.join("options.txt")).is_none() && scan::real_file(&copy).is_some() {
        s.overrides.push(("options.txt".into(), copy));
    }
    s.notes.push(NOTE.into());
    Some(s)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::tests::{fabric_jar, import_everything, write, World};
    use crate::import::ImportSupport;

    #[test]
    fn feather_versions_are_listed_on_its_shared_game_folder() {
        let world = World::new();
        let root = Feather.locations(&world.env)[0].clone();
        let minecraft = world.root.join("Games/.minecraft");
        // mcPath with the mixed separators Feather writes.
        let mc_path = minecraft.display().to_string().replacen(std::path::MAIN_SEPARATOR, "/", 1);
        write(&root, "settings.json", serde_json::json!({ "mcPath": mc_path, "allocatedRamMb": 2048, "resolution": [null, null] }).to_string().as_bytes());
        write(
            &root,
            "mods/feather-mods.json",
            serde_json::json!({
                "selectedVersion": "26.2-fabric",
                "addedMods": { "26.2-fabric": ["optifine", "starlight"], "1.21.11-fabric": ["entityCulling"], "1.8.9": ["keystrokes"] },
                "customAddedMods": { "1.21.11-fabric": ["sodium.jar"] },
                "modrinthAddedMods": { "1.8.9": [] },
                "profiles": { "26.2-fabric": { "selectedProfile": "Default", "profileStateByName": { "Default": { "enabledMods": [], "customEnabledMods": [], "modrinthEnabledMods": [] } } } }
            })
            .to_string()
            .as_bytes(),
        );
        fabric_jar(&root, "user-mods/1.21.11-fabric/sodium.jar");
        write(&minecraft, "options.txt", b"version:4903\nfov:0.5\n");
        write(&minecraft, "saves/World/level.dat", b"level");
        fabric_jar(&minecraft, "mods/other-launchers-mod.jar");
        for secret in ["accounts.json", "account.txt", "client-id.json", "skins.json"] {
            write(&root, secret, b"{\"token\":\"secret\"}");
        }
        write(&root, "player-server/player-servers.json", br#"{"serverToken":"secret"}"#);

        let (listed, result) = import_everything(&world, &Feather, false);
        let names: Vec<&str> = listed.listing.profiles.iter().map(|p| p.name.as_str()).collect();
        assert_eq!(listed.listing.profiles.len(), 2, "the selected version and the one the player added a mod to: {names:?}");
        let by = |name: &str| listed.listing.profiles.iter().find(|p| p.name == name).unwrap_or_else(|| panic!("{name} in {names:?}"));
        let selected = by("Feather 26.2");
        assert_eq!((selected.game_version.as_deref(), selected.loader, selected.mods, selected.support, selected.shared_folder), (Some("26.2"), ImportLoader::Fabric, 0, ImportSupport::Full, true));
        assert_eq!(by("Feather 1.21.11").mods, 1, "the player's own jar, not the shared folder's");
        assert!(result.profiles.iter().all(|p| p.profile_id.is_some()));
        let profile = world.existing().profiles.into_iter().find(|p| p.name == "Feather 1.21.11").unwrap();
        let dir = world.state.paths.profile_dir(&profile.id);
        assert!(dir.join("mods/sodium.jar").is_file() && !dir.join("mods/other-launchers-mod.jar").exists());
        assert!(dir.join("options.txt").is_file());
        // A damaged mod list: Feather is listed as unreadable.
        write(&root, "mods/feather-mods.json", b"{ nope");
        let broken = crate::import::discover(&Feather, &world.env, &world.existing()).unwrap();
        assert_eq!(broken.listing.launcher.status, crate::import::LauncherStatus::Unreadable);
    }
}
