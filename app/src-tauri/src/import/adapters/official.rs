//! The official Minecraft Launcher (Java Edition). Its data folder is `%APPDATA%\.minecraft` (Windows; the Microsoft
//! Store launcher writes there too, and a virtualised `…\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\
//! Roaming\.minecraft` is probed), `~/Library/Application Support/minecraft` (macOS) or `~/.minecraft` (Linux)
//! (minecraft.wiki/w/.minecraft). Profiles are in `launcher_profiles.json` and/or `launcher_profiles_microsoft_store.json`
//! (fabric-installer `LauncherType`; MinecraftForge/Installer `ClientInstall.java`), keyed by id: `name`, `type`
//! (`custom`, `latest-release`, `latest-snapshot`), `lastUsed` (ISO 8601, `1970-01-01…` = never), `icon` (a block name
//! or a `data:` URL), `lastVersionId`, optional `gameDir` (minecraft.wiki/w/Launcher_profiles.json; verified on a real
//! file). Only the `profiles` object is deserialized: never the old sign-in keys some files still carry.
//!
//! The version and loader come from `versions/<id>/<id>.json`: `inheritsFrom` and the loader's library
//! (`net.fabricmc:fabric-loader`, `org.quiltmc:quilt-loader`, `net.minecraftforge:fmlloader`/`forge`,
//! `net.neoforged.fancymodloader:loader`/`net.neoforged:neoforge`) or `--fml.*Version` arguments (Fabric and Quilt meta
//! profile JSON, real Forge/NeoForge version files); without that file, from the id's own pattern
//! (`fabric-loader-<loader>-<mc>`, `<mc>-forge-<v>`). `latest-release`/`latest-snapshot` resolve through the launcher's
//! cached `versions/version_manifest_v2.json`. Built-in profiles are listed when they were used (or, for the latest
//! release, when the folder holds a player's content).

use super::{component, iso_seconds, read_json, read_text, version_like, Adapter, Found};
use crate::import::{scan, Env, ImportLoader, LauncherId, Source};
use serde::Deserialize;
use serde_json::Value;
use std::collections::HashSet;
use std::path::{Path, PathBuf};

pub struct Official;

const FILES: [&str; 2] = ["launcher_profiles.json", "launcher_profiles_microsoft_store.json"];

impl Adapter for Official {
    fn id(&self) -> LauncherId {
        LauncherId::Official
    }

    fn name(&self) -> &'static str {
        "Minecraft Launcher"
    }

    fn locations(&self, env: &Env) -> Vec<PathBuf> {
        let mut out: Vec<PathBuf> = super::default_minecraft(env).into_iter().collect();
        if super::windows() {
            out.extend(super::under(&env.local, "Packages/Microsoft.4297127D64EC6_8wekyb3d8bbwe/LocalCache/Roaming/.minecraft"));
        }
        out
    }

    fn read(&self, root: &Path, _env: &Env) -> Found {
        #[derive(Deserialize)]
        struct File {
            #[serde(default)]
            profiles: serde_json::Map<String, Value>,
        }
        let mut present = false;
        let mut problems = Vec::new();
        let mut seen = HashSet::new();
        let mut entries: Vec<(String, Value)> = Vec::new();
        for name in FILES {
            match read_text(&root.join(name)) {
                None => continue,
                Some(Err(e)) => problems.push(e),
                Some(Ok(text)) => match serde_json::from_str::<File>(&text) {
                    Ok(file) => {
                        for (key, value) in file.profiles {
                            if seen.insert(key.clone()) {
                                entries.push((key, value));
                            }
                        }
                    }
                    Err(_) => problems.push(format!("{name} is damaged, so Flint couldn't read its profiles.")),
                },
            }
            present = true;
        }
        if !present {
            return Found::NotHere;
        }
        if entries.is_empty() && !problems.is_empty() {
            return Found::Unreadable(problems.join(" "));
        }
        let latest = latest(root);
        Found::Profiles(entries.into_iter().take(super::MAX_INSTANCES).filter_map(|(key, value)| profile(root, &key, &value, &latest)).collect())
    }
}

/// The latest release and snapshot the launcher last knew of.
fn latest(root: &Path) -> (Option<String>, Option<String>) {
    let Some(Ok(manifest)) = read_json(&root.join("versions").join("version_manifest_v2.json")) else { return (None, None) };
    let get = |k: &str| manifest["latest"][k].as_str().filter(|v| version_like(v)).map(str::to_owned);
    (get("release"), get("snapshot"))
}

/// Whether a game folder holds a player's own things.
fn has_content(dir: &Path) -> bool {
    scan::real_file(&dir.join("options.txt")).is_some()
        || ["saves", "resourcepacks", "mods", "shaderpacks"].iter().any(|d| std::fs::read_dir(dir.join(d)).is_ok_and(|mut e| e.next().is_some()))
}

fn profile(root: &Path, key: &str, value: &Value, latest: &(Option<String>, Option<String>)) -> Option<Source> {
    let kind = value["type"].as_str().unwrap_or("custom");
    // 1970-01-01 and -02 mark built-ins that were never used.
    let last_used = value["lastUsed"].as_str().and_then(iso_seconds).filter(|t| *t > 2 * 86_400);
    let game_dir = match value["gameDir"].as_str().map(str::trim).filter(|d| !d.is_empty()) {
        Some(dir) if Path::new(dir).is_absolute() => PathBuf::from(dir),
        Some(dir) => root.join(dir),
        None => root.to_path_buf(),
    };
    let version_id = value["lastVersionId"].as_str().unwrap_or_default();
    let resolved = match (kind, version_id) {
        ("latest-release", _) | (_, "latest-release") => {
            if kind == "latest-release" && last_used.is_none() && !has_content(&game_dir) {
                return None;
            }
            latest.0.clone()
        }
        ("latest-snapshot", _) | (_, "latest-snapshot") => {
            if kind == "latest-snapshot" && last_used.is_none() {
                return None;
            }
            latest.1.clone()
        }
        _ => Some(version_id.to_owned()).filter(|v| !v.is_empty()),
    };
    let name = value["name"].as_str().map(str::trim).filter(|n| !n.is_empty()).map(str::to_owned).unwrap_or_else(|| match kind {
        "latest-release" => "Latest release".into(),
        "latest-snapshot" => "Latest snapshot".into(),
        _ => resolved.clone().unwrap_or_else(|| key.to_owned()),
    });
    let mut source = Source::new(LauncherId::Official, &name, game_dir);
    source.id = Some(key.to_owned());
    source.last_played = last_used;
    source.icon_url = value["icon"].as_str().filter(|i| i.starts_with("data:image/")).map(str::to_owned);
    match resolved {
        Some(id) => {
            let detected = detect(root, &id);
            source.game_version = detected.version;
            source.loader = detected.loader;
            source.loader_version = detected.loader_version;
            source.notes.extend(detected.note.map(str::to_owned));
        }
        None => source.problem = Some("Flint couldn't tell which Minecraft version it uses, so it can't import it.".into()),
    }
    if source.problem.is_none() && !source.game_dir.is_dir() {
        source.problem = Some("Its game folder no longer exists.".into());
    }
    Some(source)
}

/// What a version id runs.
#[derive(Debug, PartialEq)]
pub struct Detected {
    pub version: Option<String>,
    pub loader: ImportLoader,
    pub loader_version: Option<String>,
    pub note: Option<&'static str>,
}

/// The Minecraft version and loader of `versions/<id>`: from its JSON when it's there, else from the id's pattern.
pub fn detect(root: &Path, id: &str) -> Detected {
    if let Some(folder) = component(id) {
        if let Some(Ok(json)) = read_json(&root.join("versions").join(folder).join(format!("{folder}.json"))) {
            return from_json(&json, id);
        }
    }
    from_id(id)
}

fn from_json(json: &Value, id: &str) -> Detected {
    let libraries: Vec<&str> = json["libraries"].as_array().map(|l| l.iter().filter_map(|x| x["name"].as_str()).collect()).unwrap_or_default();
    let mut args: Vec<String> = json["arguments"]["game"].as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_owned)).collect()).unwrap_or_default();
    if let Some(legacy) = json["minecraftArguments"].as_str() {
        args.extend(legacy.split_whitespace().map(str::to_owned));
    }
    let arg = |flag: &str| args.windows(2).find(|w| w[0] == flag).map(|w| w[1].clone());
    let library = |prefix: &str| libraries.iter().find_map(|l| l.strip_prefix(prefix)).map(str::to_owned);
    let (loader, loader_version, note) = if let Some(v) = arg("--fml.neoForgeVersion").or_else(|| library("net.neoforged:neoforge:")) {
        (ImportLoader::Neoforge, Some(v), None)
    } else if libraries.iter().any(|l| l.starts_with("net.neoforged.fancymodloader:loader:")) {
        (ImportLoader::Neoforge, None, None)
    } else if let Some(v) = arg("--fml.forgeVersion") {
        (ImportLoader::Forge, Some(v), None)
    } else if let Some(v) = library("net.minecraftforge:fmlloader:").or_else(|| library("net.minecraftforge:forge:")) {
        (ImportLoader::Forge, Some(v.split_once('-').map_or(v.clone(), |(_, rest)| rest.to_owned())), None)
    } else if let Some(v) = library("net.fabricmc:fabric-loader:") {
        (ImportLoader::Fabric, Some(v), None)
    } else if let Some(v) = library("org.quiltmc:quilt-loader:") {
        (ImportLoader::Quilt, Some(v), None)
    } else if libraries.iter().any(|l| l.starts_with("com.mumfrey:liteloader")) {
        (ImportLoader::Unknown, None, None)
    } else if libraries.iter().any(|l| l.starts_with("optifine:")) {
        (ImportLoader::Vanilla, None, Some("OptiFine itself doesn't come over: Flint profiles use Sodium and Iris instead."))
    } else {
        (ImportLoader::Vanilla, None, None)
    };
    let own_id = json["id"].as_str().unwrap_or(id);
    let version = arg("--fml.mcVersion")
        .or_else(|| json["inheritsFrom"].as_str().map(str::to_owned))
        .or_else(|| (loader == ImportLoader::Vanilla && json["inheritsFrom"].is_null() && version_like(own_id) && note.is_none()).then(|| own_id.to_owned()))
        .or_else(|| own_id.split('-').rev().find(|t| version_like(t)).map(str::to_owned))
        .filter(|v| version_like(v));
    Detected { version, loader, loader_version, note }
}

/// The id's own pattern, when its version file is gone.
fn from_id(id: &str) -> Detected {
    let none = Detected { version: None, loader: ImportLoader::Unknown, loader_version: None, note: None };
    for (prefix, loader) in [("fabric-loader-", ImportLoader::Fabric), ("quilt-loader-", ImportLoader::Quilt)] {
        if let Some(rest) = id.strip_prefix(prefix) {
            let Some((loader_version, version)) = rest.split_once('-') else { return none };
            return Detected { version: version_like(version).then(|| version.to_owned()), loader, loader_version: Some(loader_version.to_owned()), note: None };
        }
    }
    if let Some((version, rest)) = id.split_once("-forge") {
        let loader_version = rest.trim_start_matches('-').rsplit('-').next().filter(|v| !v.is_empty()).map(str::to_owned);
        return Detected { version: version_like(version).then(|| version.to_owned()), loader: ImportLoader::Forge, loader_version, note: None };
    }
    if let Some(v) = id.strip_prefix("neoforge-") {
        return Detected { version: None, loader: ImportLoader::Neoforge, loader_version: Some(v.to_owned()), note: None };
    }
    if version_like(id) {
        return Detected { version: Some(id.to_owned()), loader: ImportLoader::Vanilla, loader_version: None, note: None };
    }
    none
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::tests::{fabric_jar, import_everything, write, World};
    use crate::import::ImportSupport;

    #[test]
    fn official_launcher_profiles_and_their_versions_are_read() {
        let world = World::new();
        let root = Official.locations(&world.env)[0].clone();
        let custom = world.root.join("Games/Fabric121");
        let icon = format!("data:image/png;base64,{}", base64::Engine::encode(&base64::engine::general_purpose::STANDARD, b"\x89PNG\r\n\x1a\nrest"));
        // The shape the launcher and the Fabric installer write (minecraft.wiki; fabric-installer), with the old sign-in
        // keys some files still carry.
        let profiles = serde_json::json!({
            "profiles": {
                "0123456789abcdef0123456789abcdef": { "name": "", "type": "latest-release", "created": "1970-01-02T00:00:00.000Z", "lastUsed": "1970-01-02T00:00:00.000Z", "icon": "Grass", "lastVersionId": "latest-release" },
                "fedcba9876543210fedcba9876543210": { "name": "", "type": "latest-snapshot", "created": "1970-01-01T00:00:00.000Z", "lastUsed": "1970-01-01T00:00:00.000Z", "icon": "Dirt", "lastVersionId": "latest-snapshot" },
                "fabric-loader-1.21.1": { "name": "fabric-loader-1.21.1", "type": "custom", "created": "2026-10-01T12:00:00+0000", "lastUsed": "2026-10-20T18:00:00.000Z", "icon": icon, "lastVersionId": "fabric-loader-0.16.9-1.21.1", "gameDir": custom.display().to_string(), "javaArgs": "-Xmx4G" },
                "forge": { "name": "forge", "type": "custom", "lastUsed": "2026-10-19T18:00:00.000Z", "icon": "Furnace", "lastVersionId": "1.20.1-forge-47.4.10" },
                "neo": { "name": "NeoForge", "type": "custom", "lastUsed": "2026-10-18T18:00:00.000Z", "lastVersionId": "neoforge-21.1.7" },
                "gone": { "name": "Moved", "type": "custom", "lastUsed": "2026-10-17T18:00:00.000Z", "lastVersionId": "1.21.11", "gameDir": world.root.join("nowhere").display().to_string() }
            },
            "settings": { "enableSnapshots": true },
            "version": 3,
            "authenticationDatabase": { "x": { "accessToken": "secret-token" } },
            "clientToken": "secret"
        });
        write(&root, "launcher_profiles.json", profiles.to_string().as_bytes());
        write(&root, "launcher_profiles_microsoft_store.json", br#"{"profiles":{"store":{"name":"Store PvP","type":"custom","lastUsed":"2026-10-21T10:00:00.000Z","lastVersionId":"26.3"}}}"#);
        write(&root, "versions/version_manifest_v2.json", br#"{"latest":{"release":"26.3","snapshot":"26.4-snapshot-2"},"versions":[]}"#);
        write(&root, "versions/fabric-loader-0.16.9-1.21.1/fabric-loader-0.16.9-1.21.1.json", br#"{"id":"fabric-loader-0.16.9-1.21.1","inheritsFrom":"1.21.1","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient","libraries":[{"name":"net.fabricmc:intermediary:1.21.1"},{"name":"net.fabricmc:fabric-loader:0.16.9"}]}"#);
        write(&root, "versions/1.20.1-forge-47.4.10/1.20.1-forge-47.4.10.json", br#"{"id":"1.20.1-forge-47.4.10","inheritsFrom":"1.20.1","libraries":[{"name":"net.minecraftforge:fmlloader:1.20.1-47.4.10"}],"arguments":{"game":["--fml.forgeVersion","47.4.10","--fml.mcVersion","1.20.1"]}}"#);
        write(&root, "versions/neoforge-21.1.7/neoforge-21.1.7.json", br#"{"id":"neoforge-21.1.7","inheritsFrom":"1.21.1","libraries":[{"name":"net.neoforged.fancymodloader:loader:4.0.24"}],"arguments":{"game":["--fml.neoForgeVersion","21.1.7","--fml.mcVersion","1.21.1"]}}"#);
        write(&root, "versions/26.3/26.3.json", br#"{"id":"26.3","type":"release","mainClass":"net.minecraft.client.main.Main"}"#);
        // The root's own content (what "Latest release" and the store profile share), and launcher files never copied.
        write(&root, "options.txt", b"version:5023\nfov:0.5\n");
        write(&root, "saves/Survival/level.dat", b"level");
        write(&root, "resourcepacks/Faithful.zip", b"pack");
        for secret in ["launcher_accounts_microsoft_store.json", "launcher_msa_credentials_microsoft_store.bin", "launcher_accounts.json", "launcher_settings.json", "launcher_ui_state_microsoft_store.json", "clientId_v2.txt"] {
            write(&root, secret, b"{\"token\":\"secret\"}");
        }
        write(&root, "essential/microsoft_accounts.json", b"secret");
        write(&custom, "options.txt", b"version:3955\n");
        fabric_jar(&custom, "mods/sodium.jar");

        let (listed, result) = import_everything(&world, &Official, true);
        let names: Vec<&str> = listed.listing.profiles.iter().map(|p| p.name.as_str()).collect();
        assert!(!names.contains(&"Latest snapshot"), "an unused snapshot profile isn't listed: {names:?}");
        let by = |name: &str| listed.listing.profiles.iter().find(|p| p.name == name).unwrap_or_else(|| panic!("{name} in {names:?}"));
        let fabric = by("fabric-loader-1.21.1");
        assert_eq!((fabric.game_version.as_deref(), fabric.loader, fabric.loader_version.as_deref(), fabric.support), (Some("1.21.1"), ImportLoader::Fabric, Some("0.16.9"), ImportSupport::Full));
        assert!(fabric.icon.as_deref().is_some_and(|i| i.starts_with("data:image/png;base64,")));
        assert_eq!((fabric.mods, fabric.shared_folder), (1, false));
        let latest = by("Latest release");
        assert_eq!((latest.game_version.as_deref(), latest.loader, latest.shared_folder), (Some("26.3"), ImportLoader::Vanilla, true), "resolved through the manifest; shares the root");
        assert_eq!(by("Store PvP").game_version.as_deref(), Some("26.3"));
        let forge = by("forge");
        assert_eq!((forge.game_version.as_deref(), forge.loader, forge.loader_version.as_deref(), forge.support), (Some("1.20.1"), ImportLoader::Forge, Some("47.4.10"), ImportSupport::Unsupported));
        let neo = by("NeoForge");
        assert_eq!((neo.game_version.as_deref(), neo.loader, neo.support), (Some("1.21.1"), ImportLoader::Neoforge, ImportSupport::Settings));
        assert_eq!(by("Moved").support_note.as_deref(), Some("Its game folder no longer exists."));
        assert!(result.profiles.iter().filter(|p| p.profile_id.is_some()).count() >= 4);
        let imported = world.existing().profiles;
        let fabric_profile = imported.iter().find(|p| p.name == "fabric-loader-1.21.1").unwrap();
        assert!(world.state.paths.profile_dir(&fabric_profile.id).join("mods/sodium.jar").is_file());
        let latest_profile = imported.iter().find(|p| p.name == "Latest release").unwrap();
        let dir = world.state.paths.profile_dir(&latest_profile.id);
        assert!(dir.join("saves/Survival/level.dat").is_file() && dir.join("resourcepacks/Faithful.zip").is_file());
        assert!(!dir.join("essential").exists() && !dir.join("launcher_profiles.json").exists());
    }

    #[test]
    fn version_ids_name_their_loader_without_their_files() {
        let d = |id: &str| from_id(id);
        assert_eq!(d("fabric-loader-0.16.9-1.21.1"), Detected { version: Some("1.21.1".into()), loader: ImportLoader::Fabric, loader_version: Some("0.16.9".into()), note: None });
        assert_eq!(d("quilt-loader-0.26.0-1.20.1").loader, ImportLoader::Quilt);
        assert_eq!(d("1.20.1-forge-47.4.10").version.as_deref(), Some("1.20.1"));
        assert_eq!(d("1.12.2-forge1.12.2-14.23.5.2860").version.as_deref(), Some("1.12.2"));
        assert_eq!(d("neoforge-21.1.7").version, None, "NeoForge's own number isn't read as a Minecraft version");
        assert_eq!(d("26.3").loader, ImportLoader::Vanilla);
        assert_eq!(d("My Modpack").version, None);
    }
}
