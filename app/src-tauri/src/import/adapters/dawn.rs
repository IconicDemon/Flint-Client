//! Dawn Client (0.2.0.x; checked against a 0.2.0.466 install).
//! Data folder: `DAWN_LAUNCHER_HOME`, else the place `launcher-home.local.json` (`{"schemaVersion":1,"launcherHome":…}`)
//! names, else `%APPDATA%\.dawn` (Windows) or `~/.dawn`. Profiles: `profiles/<id>/profile.json`
//! (`schemaVersion` 4; `profile.id`, `name`, `minecraftVersion`, `loader.kind` Vanilla|Fabric|Forge|NeoForge|Quilt and
//! `loader.version`, `settings.autoSyncMembership` {kind Member, groupId}); icon `assets/profile/icon.png`; last played
//! `profile-library-state.local.json` `lastPlayedAtEpochMillis[id]` (milliseconds).
//!
//! Where the game content really is (desktop/profiles/bq.kt, al.kt; WindowsAutoSyncMappingFileSystem.kt): an Auto Sync
//! member's saves, config and servers.dat are in `auto-sync/groups/<groupId>/`, its mods, resource and shader packs and
//! optionsshaders.txt in `profiles/<id>/private-game-content/`, and its real video and control options in the group's
//! `feather/mc_options_v2.txt` (vanilla options.txt format). Dawn links them into `profiles/<id>/.minecraft` only while
//! the game runs; at rest that folder holds stale copies, so it's only the fallback. Other profiles use
//! `profiles/<id>/.minecraft`. Jars Dawn injects (`dawn-managed-client-mods.json`) aren't the player's.

use super::{component, epoch_seconds, read_json, sub_dirs, text, Adapter, Found};
use crate::import::{scan, Env, LauncherId, Source};
use serde_json::Value;
use std::path::{Path, PathBuf};

pub struct Dawn;

const NOTE: &str = "Dawn's own client and its settings are Dawn's and stay behind.";

impl Adapter for Dawn {
    fn id(&self) -> LauncherId {
        LauncherId::Dawn
    }

    fn name(&self) -> &'static str {
        "Dawn Client"
    }

    fn locations(&self, env: &Env) -> Vec<PathBuf> {
        let default = if super::windows() { super::under(&env.appdata, ".dawn") } else { super::under(&env.home, ".dawn") };
        let moved = default.as_ref().and_then(|d| read_json(&d.join("launcher-home.local.json"))).and_then(Result::ok).and_then(|v| text(&v, "launcherHome")).map(PathBuf::from).filter(|p| p.is_absolute());
        [env.var("DAWN_LAUNCHER_HOME"), moved, default].into_iter().flatten().collect()
    }

    fn read(&self, root: &Path, _env: &Env) -> Found {
        let dirs: Vec<PathBuf> = sub_dirs(&root.join("profiles")).into_iter().filter(|d| scan::real_file(&d.join("profile.json")).is_some()).collect();
        let state = read_json(&root.join("profile-library-state.local.json")).and_then(Result::ok);
        // Other launchers keep a `profiles/<x>/profile.json` too (the old Modrinth App): the folder is Dawn's only with
        // its library state or a profile of Dawn's shape.
        let dawn_shaped = |d: &PathBuf| matches!(read_json(&d.join("profile.json")), Some(Ok(v)) if v["schemaVersion"].is_u64() && v["profile"]["id"].is_string());
        if state.is_none() && !dirs.iter().any(dawn_shaped) {
            return Found::NotHere;
        }
        if dirs.is_empty() {
            return Found::Profiles(Vec::new());
        }
        let state = state.unwrap_or(Value::Null);
        Found::Profiles(dirs.into_iter().map(|dir| profile(root, &dir, &state)).collect())
    }
}

fn profile(root: &Path, dir: &Path, state: &Value) -> Source {
    let folder = dir.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let game_dir = dir.join(".minecraft");
    let json = match read_json(&dir.join("profile.json")) {
        Some(Ok(json)) if json["schemaVersion"].is_u64() && text(&json["profile"], "id").is_some() => json,
        other => {
            let mut s = Source::new(LauncherId::Dawn, &folder, game_dir);
            s.id = Some(folder.clone());
            s.problem = Some(match other {
                Some(Err(e)) => e,
                _ => "Its profile.json isn't one Flint can read.".into(),
            });
            return s;
        }
    };
    let p = &json["profile"];
    let id = text(p, "id").unwrap_or(folder);
    let mut s = Source::new(LauncherId::Dawn, &text(p, "name").unwrap_or_else(|| id.clone()), game_dir.clone());
    s.game_version = text(p, "minecraftVersion");
    s.loader = super::loader_named(p["loader"]["kind"].as_str().unwrap_or("vanilla"));
    s.loader_version = text(&p["loader"], "version");
    s.last_played = state["lastPlayedAtEpochMillis"][id.as_str()].as_i64().and_then(epoch_seconds);
    s.icon = Some(dir.join("assets").join("profile").join("icon.png"));
    let membership = &p["settings"]["autoSyncMembership"];
    let group = (membership["kind"].as_str() == Some("Member")).then(|| membership["groupId"].as_str().and_then(component)).flatten().map(|g| root.join("auto-sync").join("groups").join(g));
    let options_home = match &group {
        Some(group) => {
            s.prefer = vec![group.clone(), dir.join("private-game-content")];
            group.clone()
        }
        None => game_dir,
    };
    let options = options_home.join("feather").join("mc_options_v2.txt");
    if scan::real_file(&options).is_some() {
        s.overrides.push(("options.txt".into(), options));
    }
    s.launcher_mods = read_json(&dir.join("dawn-managed-client-mods.json"))
        .and_then(Result::ok)
        .and_then(|v| v["mods"].as_array().map(|m| m.iter().filter_map(|x| x["fileName"].as_str()).map(str::to_ascii_lowercase).collect()))
        .unwrap_or_default();
    s.notes.push(NOTE.into());
    s.id = Some(id);
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::tests::{fabric_jar, import_everything, write, World};
    use crate::import::{ImportLoader, ImportSupport};

    #[test]
    fn dawn_content_is_taken_from_where_auto_sync_keeps_it() {
        let world = World::new();
        let root = Dawn.locations(&world.env).last().unwrap().clone();
        let profile = root.join("profiles/dawn-performance");
        write(
            &profile,
            "profile.json",
            br#"{"schemaVersion":4,"profile":{"id":"dawn-performance","kind":"Custom","name":"Dawn","minecraftVersion":"26.3","loader":{"kind":"Fabric","version":"0.19.5"},"settings":{"memoryDefaults":{},"jvmOptions":{},"javaOverrides":{},"tags":[],"autoSyncMembership":{"kind":"Member","groupId":"default"}},"runtimePolicy":{"modAuthority":"UserManaged","requiredHooks":["DawnCoreMods"]}}}"#,
        );
        write(&profile, "dawn-managed-client-mods.json", br#"{"schemaVersion":1,"mods":[{"fileName":"dawn-client.jar","sha1":"0000000000000000000000000000000000000000"}]}"#);
        write(&profile, "assets/profile/icon.png", b"\x89PNG\r\n\x1a\nicon");
        // Private content: mods (with Dawn's own client), packs, a stub options.txt.
        fabric_jar(&profile, "private-game-content/mods/dawn-client.jar");
        fabric_jar(&profile, "private-game-content/mods/fabric-api-0.163.0+26.3.jar");
        write(&profile, "private-game-content/resourcepacks/Pack.zip", b"pack");
        write(&profile, "private-game-content/options.txt", b"fullscreen:false\noverrideWidth:1280\n");
        write(&profile, "private-game-content/optionsshaders.txt", b"shaderPack=x\n");
        // The group's shared content and the real options.
        let group = root.join("auto-sync/groups/default");
        write(&group, "saves/World/level.dat", b"current world");
        write(&group, "config/x.json", b"{}");
        write(&group, "servers.dat", b"servers");
        write(&group, "feather/mc_options_v2.txt", b"version:5023\nfov:0.5\nkey_key.jump:key.keyboard.space\n");
        write(&group, "feather/client_preferences.json", b"{}");
        write(&group, "usercache.json", b"[]");
        // Stale copies in the profile's own .minecraft.
        write(&profile, ".minecraft/options.txt", b"fullscreen:false\n");
        write(&profile, ".minecraft/saves/World/level.dat", b"stale world");
        write(&profile, ".minecraft/saves/Old/level.dat", b"stale");
        write(&root, "auto-sync/groups.json", br#"{"schemaVersion":1,"groups":[{"groupId":"default","members":["dawn-performance"]}]}"#);
        write(&root, "profile-library-state.local.json", br#"{"schemaVersion":6,"orderedProfileIds":["dawn-performance"],"lastPlayedAtEpochMillis":{"dawn-performance":1793578954431},"totalPlayedMillis":{"dawn-performance":6120000}}"#);
        // Sign-ins and launcher records, never copied.
        for secret in ["accounts.local.json", "backend-account-links.local.json", "dawn-launcher-instance.json", "last-java-session.properties"] {
            write(&root, secret, b"{\"token\":\"secret\"}");
        }
        write(&profile, ".dawn/auth.local.json", b"secret");

        let (listed, result) = import_everything(&world, &Dawn, true);
        let p = &listed.listing.profiles[0];
        assert_eq!((p.name.as_str(), p.game_version.as_deref(), p.loader, p.support), ("Dawn", Some("26.3"), ImportLoader::Fabric, ImportSupport::Full));
        assert_eq!((p.mods, p.worlds, p.last_played, p.icon.is_some()), (1, 1, Some(1793578954), true), "Dawn's own client isn't counted; the group's one world");
        let id = result.profiles[0].profile_id.clone().unwrap();
        let dir = world.state.paths.profile_dir(&id);
        assert_eq!(std::fs::read(dir.join("options.txt")).unwrap(), b"version:5023\nfov:0.5\nkey_key.jump:key.keyboard.space\n", "the real options");
        assert_eq!(std::fs::read(dir.join("saves/World/level.dat")).unwrap(), b"current world");
        assert!(!dir.join("saves/Old").exists(), "not the stale copies");
        assert!(dir.join("mods/fabric-api-0.163.0+26.3.jar").is_file() && !dir.join("mods/dawn-client.jar").exists());
        assert!(dir.join("resourcepacks/Pack.zip").is_file() && dir.join("optionsshaders.txt").is_file() && dir.join("servers.dat").is_file() && dir.join("config/x.json").is_file());
        assert!(!dir.join("feather").exists() && !dir.join("usercache.json").exists());
        let parts = &result.profiles[0].parts;
        assert_eq!(parts.iter().find(|x| x.id == crate::import::ImportPart::Keybinds).map(|x| x.count), Some(1));
    }
}
