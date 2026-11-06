//! Prism Launcher (and PolyMC, its predecessor) and MultiMC, which share one instance format. Not installed on the PC
//! this was written on: written from their sources and tested on layouts built from them.
//! - Data folder. Prism (PrismLauncher 11.1.1 launcher/Application.cpp; prismlauncher.org/wiki data-location):
//!   `PRISMLAUNCHER_DATA_DIR`, else `%APPDATA%\PrismLauncher`, `~/Library/Application Support/PrismLauncher`,
//!   `~/.local/share/PrismLauncher` (Flatpak `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher`, Scoop
//!   `~/scoop/persist/prismlauncher`); PolyMC `%APPDATA%\PolyMC`. MultiMC (MultiMC/Launcher Application.cpp) is portable:
//!   the folder it runs from (a few usual places are probed; otherwise the player chooses it), `MultiMC.app/Data` on
//!   macOS, `~/.local/share/multimc` from Linux packages.
//! - `prismlauncher.cfg` (or `polymc.cfg`) / `multimc.cfg`, INI: `InstanceDir` (default `instances`, relative to the data
//!   folder or absolute) and `IconsDir` (default `icons`). Nothing else is read from it (it holds proxy passwords).
//! - An instance is a direct sub-folder of `InstanceDir` with an `instance.cfg` (InstanceList.cpp): `name`, `iconKey`,
//!   `lastLaunchTime` (epoch milliseconds, 0 = never), `InstanceType` (`OneSix` or empty; MultiMC's `Legacy` and
//!   `Nostalgia` aren't readable). Prism writes it with Qt's QSettings, MultiMC with its own INIFile.
//! - `mmc-pack.json` (`formatVersion` 1; minecraft/PackProfile.cpp): the `net.minecraft` component's `version` (or
//!   `cachedVersion`), and the loader is the first component not `disabled` among `net.neoforged`,
//!   `net.minecraftforge`, `net.fabricmc.fabric-loader`, `org.quiltmc.quilt-loader`, `com.mumfrey.liteloader`.
//! - Game folder: Prism takes `minecraft/` unless only `.minecraft/` exists (MinecraftInstance.cpp); MultiMC takes
//!   `.minecraft/` first. Icons: a file in `IconsDir` whose base name is the `iconKey` (IconList.cpp).

use super::{epoch_seconds, ini, read_json, read_text, sub_dirs, Adapter, Found};
use crate::import::{scan, Env, ImportLoader, LauncherId, Source};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

pub struct Prism;
pub struct MultiMc;

#[derive(Clone, Copy, PartialEq, Eq)]
enum Flavor {
    Prism,
    MultiMc,
}

impl Adapter for Prism {
    fn id(&self) -> LauncherId {
        LauncherId::Prism
    }

    fn name(&self) -> &'static str {
        "Prism Launcher"
    }

    fn locations(&self, env: &Env) -> Vec<PathBuf> {
        let mut out: Vec<PathBuf> = env.var("PRISMLAUNCHER_DATA_DIR").into_iter().collect();
        if super::windows() || super::macos() {
            out.extend(super::under(&env.appdata, "PrismLauncher"));
            out.extend(super::under(&env.appdata, "PolyMC"));
        } else {
            out.extend(super::under(&env.local, "PrismLauncher"));
            out.extend(super::under(&env.home, ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"));
            out.extend(super::under(&env.local, "PolyMC"));
        }
        if super::windows() {
            out.extend(super::under(&env.home, "scoop/persist/prismlauncher"));
        }
        out
    }

    fn read(&self, root: &Path, _env: &Env) -> Found {
        read_root(root, Flavor::Prism)
    }

    /// An instance folder the player chose (Prism's rules; MultiMC's differ only in which game folder wins when both
    /// exist).
    fn instance(&self, dir: &Path, _env: &Env) -> Option<Source> {
        scan::real_file(&dir.join("instance.cfg"))?;
        Some(instance(dir, Flavor::Prism, &HashMap::new()))
    }
}

impl Adapter for MultiMc {
    fn id(&self) -> LauncherId {
        LauncherId::Multimc
    }

    fn name(&self) -> &'static str {
        "MultiMC"
    }

    fn locations(&self, env: &Env) -> Vec<PathBuf> {
        let mut out: Vec<PathBuf> = ["MultiMC", "Desktop/MultiMC", "Downloads/MultiMC"].iter().filter_map(|rel| super::under(&env.home, rel)).collect();
        if super::windows() {
            out.extend(env.fixed_places.then(|| PathBuf::from(r"C:\MultiMC")));
        } else if super::macos() {
            out.extend(env.fixed_places.then(|| PathBuf::from("/Applications/MultiMC.app/Data")));
        } else {
            out.extend(super::under(&env.local, "multimc"));
        }
        out
    }

    fn read(&self, root: &Path, _env: &Env) -> Found {
        read_root(root, Flavor::MultiMc)
    }
}

fn read_root(root: &Path, flavor: Flavor) -> Found {
    // A folder Prism has run in has its own config (Prism reads multimc.cfg only to migrate from it), so each launcher
    // claims only its own.
    let own = |name: &str| scan::real_file(&root.join(name)).is_some();
    let config = match flavor {
        Flavor::Prism => ["prismlauncher.cfg", "polymc.cfg"].into_iter().find(|n| own(n)),
        Flavor::MultiMc => (own("multimc.cfg") && !own("prismlauncher.cfg") && !own("polymc.cfg")).then_some("multimc.cfg"),
    };
    let Some(config) = config else { return Found::NotHere };
    let settings = match read_text(&root.join(config)) {
        Some(Ok(text)) => ini(&text),
        _ => return Found::Unreadable(format!("Flint couldn't read {config}, so it can't find the instances.")),
    };
    let folder = |key: &str, default: &str| {
        let value = settings.get(key).map(|v| v.trim()).filter(|v| !v.is_empty()).unwrap_or(default);
        let path = PathBuf::from(value);
        if path.is_absolute() {
            path
        } else {
            root.join(path)
        }
    };
    let instances = folder("InstanceDir", "instances");
    let icons = icons(&folder("IconsDir", "icons"));
    Found::Profiles(sub_dirs(&instances).into_iter().filter(|d| scan::real_file(&d.join("instance.cfg")).is_some()).map(|d| instance(&d, flavor, &icons)).collect())
}

/// Icon files by base name (`iconKey`), at most three folders deep.
fn icons(dir: &Path) -> HashMap<String, PathBuf> {
    fn walk(dir: &Path, depth: usize, out: &mut HashMap<String, PathBuf>) {
        if depth > 3 || out.len() > 2000 {
            return;
        }
        for entry in sub_dirs(dir) {
            walk(&entry, depth + 1, out);
        }
        let Ok(entries) = std::fs::read_dir(dir) else { return };
        for entry in entries.flatten() {
            let path = entry.path();
            let image = path.extension().and_then(|e| e.to_str()).is_some_and(|e| ["png", "gif", "jpg", "jpeg", "webp"].contains(&e.to_ascii_lowercase().as_str()));
            if image && entry.metadata().is_ok_and(|m| m.is_file() && !scan::is_link(&m)) {
                if let Some(stem) = path.file_stem().map(|s| s.to_string_lossy().to_string()) {
                    out.entry(stem).or_insert(path);
                }
            }
        }
    }
    let mut out = HashMap::new();
    walk(dir, 0, &mut out);
    out
}

fn instance(dir: &Path, flavor: Flavor, icons: &HashMap<String, PathBuf>) -> Source {
    let folder = dir.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let (mc, dot) = (dir.join("minecraft"), dir.join(".minecraft"));
    let game_dir = match flavor {
        Flavor::Prism if dot.is_dir() && !mc.is_dir() => dot,
        Flavor::Prism => mc,
        Flavor::MultiMc if !dot.is_dir() && mc.is_dir() => mc,
        Flavor::MultiMc => dot,
    };
    let launcher = if flavor == Flavor::Prism { LauncherId::Prism } else { LauncherId::Multimc };
    let settings = match read_text(&dir.join("instance.cfg")) {
        Some(Ok(text)) => ini(&text),
        _ => {
            let mut s = Source::new(launcher, &folder, game_dir);
            s.problem = Some("Its instance.cfg can't be read.".into());
            return s;
        }
    };
    let name = settings.get("name").map(|n| n.trim()).filter(|n| !n.is_empty()).unwrap_or(&folder).to_owned();
    let mut s = Source::new(launcher, &name, game_dir);
    s.last_played = settings.get("lastLaunchTime").and_then(|t| t.trim().parse::<i64>().ok()).and_then(epoch_seconds);
    s.icon = settings.get("iconKey").and_then(|k| icons.get(k.trim())).cloned();
    if let Some(kind) = settings.get("InstanceType").map(|t| t.trim()).filter(|t| !t.is_empty() && *t != "OneSix") {
        s.problem = Some(format!("It's an old MultiMC instance type ({kind}) that Flint can't read."));
        return s;
    }
    let pack = match read_json(&dir.join("mmc-pack.json")) {
        Some(Ok(pack)) if pack["formatVersion"].as_u64() == Some(1) => pack,
        Some(Ok(_)) => {
            s.problem = Some("Its mmc-pack.json is in a format Flint doesn't know.".into());
            return s;
        }
        Some(Err(_)) => {
            s.problem = Some("Its mmc-pack.json is damaged.".into());
            return s;
        }
        None => {
            s.problem = Some("Its mmc-pack.json is missing, so Flint can't tell its version.".into());
            return s;
        }
    };
    let components = pack["components"].as_array().cloned().unwrap_or_default();
    let version_of = |c: &serde_json::Value| c["version"].as_str().or_else(|| c["cachedVersion"].as_str()).map(str::to_owned);
    s.game_version = components.iter().find(|c| c["uid"] == "net.minecraft").and_then(version_of);
    s.loader = ImportLoader::Vanilla;
    for c in components.iter().filter(|c| c["disabled"].as_bool() != Some(true)) {
        let loader = match c["uid"].as_str().unwrap_or_default() {
            "net.neoforged" => ImportLoader::Neoforge,
            "net.minecraftforge" => ImportLoader::Forge,
            "net.fabricmc.fabric-loader" => ImportLoader::Fabric,
            "org.quiltmc.quilt-loader" => ImportLoader::Quilt,
            "com.mumfrey.liteloader" => ImportLoader::Unknown,
            _ => continue,
        };
        s.loader = loader;
        s.loader_version = version_of(c);
        break;
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::tests::{fabric_jar, import_everything, write, World};
    use crate::import::ImportSupport;

    const FABRIC_PACK: &[u8] = br#"{"formatVersion":1,"components":[
        {"uid":"org.lwjgl3","version":"3.3.3","dependencyOnly":true},
        {"uid":"net.minecraft","version":"1.21.1","important":true},
        {"uid":"net.fabricmc.intermediary","version":"1.21.1","dependencyOnly":true},
        {"uid":"net.fabricmc.fabric-loader","version":"0.16.10"}]}"#;

    #[test]
    fn prism_instances_are_read_from_its_instance_folder() {
        let world = World::new();
        let root = Prism.locations(&world.env)[0].clone();
        write(&root, "prismlauncher.cfg", b"[General]\nConfigVersion=1.2\nInstanceDir=instances\nIconsDir=icons\nProxyPass=secret\n");
        write(&root, "accounts.json", br#"{"accounts":[{"msa":{"token":"secret"}}]}"#);
        write(&root, "icons/myicon.png", b"\x89PNG\r\n\x1a\nicon");
        let fo = root.join("instances/FO 1.21.1");
        // Qt's dialect, as Prism writes it.
        write(&fo, "instance.cfg", b"[General]\nConfigVersion=1.3\nInstanceType=OneSix\nManagedPack=true\nManagedPackType=modrinth\niconKey=myicon\nlastLaunchTime=1761926400000\nname=\"FO \\\"Fabulously\\\" 1.21.1\"\ntotalTimePlayed=128734\n");
        write(&fo, "mmc-pack.json", FABRIC_PACK);
        // Both game folders: Prism takes `minecraft/`.
        fabric_jar(&fo, "minecraft/mods/sodium.jar");
        write(&fo, "minecraft/options.txt", b"version:3955\n");
        write(&fo, ".minecraft/options.txt", b"old");
        write(&fo, "minecraft/mods/.index/sodium.pw.toml", b"index");
        let forge = root.join("instances/Forge Pack");
        write(&forge, "instance.cfg", b"[General]\nname=Forge Pack\nlastLaunchTime=0\n");
        write(&forge, "mmc-pack.json", br#"{"formatVersion":1,"components":[{"uid":"net.minecraft","version":"1.21.1"},{"uid":"net.fabricmc.fabric-loader","version":"0.16.10","disabled":true},{"uid":"net.minecraftforge","version":"52.0.1"}]}"#);
        write(&forge, ".minecraft/options.txt", b"version:3955\n");
        let broken = root.join("instances/Broken");
        write(&broken, "instance.cfg", b"name=Broken\n");
        write(&broken, "mmc-pack.json", b"{ nope");
        let legacy = root.join("instances/Ancient");
        write(&legacy, "instance.cfg", b"name=Ancient\nInstanceType=Legacy\n");
        write(&root, "instances/not-an-instance/readme.txt", b"x");

        let (listed, result) = import_everything(&world, &Prism, false);
        assert_eq!(listed.listing.profiles.len(), 4);
        let by = |name: &str| listed.listing.profiles.iter().find(|p| p.name == name).unwrap_or_else(|| panic!("{name}"));
        let fo = by("FO \"Fabulously\" 1.21.1");
        assert_eq!((fo.game_version.as_deref(), fo.loader, fo.loader_version.as_deref(), fo.last_played, fo.mods, fo.icon.is_some()), (Some("1.21.1"), ImportLoader::Fabric, Some("0.16.10"), Some(1761926400), 1, true));
        let forge = by("Forge Pack");
        assert_eq!((forge.loader, forge.last_played, forge.support), (ImportLoader::Forge, None, ImportSupport::Settings), "the disabled Fabric component doesn't count");
        assert_eq!(by("Broken").support, ImportSupport::Unsupported);
        assert!(by("Ancient").support_note.as_deref().unwrap().contains("Legacy"));
        assert_eq!(result.profiles.iter().filter(|p| p.profile_id.is_some()).count(), 2);
        let profile = world.existing().profiles.into_iter().find(|p| p.name.starts_with("FO")).unwrap();
        let dir = world.state.paths.profile_dir(&profile.id);
        assert_eq!(std::fs::read(dir.join("options.txt")).unwrap(), b"version:3955\n", "minecraft/ wins");
        assert!(dir.join("mods/sodium.jar").is_file() && !dir.join("mods/.index").exists());

        // A chosen instance folder on its own.
        let chosen = crate::import::list(&super::super::all(), LauncherId::Folder, Some(&root.join("instances/Forge Pack")), &world.env, &world.existing()).unwrap();
        assert_eq!(chosen.listing.profiles.len(), 1);
        assert_eq!(chosen.listing.launcher.name, "Prism Launcher (chosen folder)");
        assert_eq!(chosen.listing.profiles[0].key, by("Forge Pack").key, "the same profile, found either way");
    }

    #[test]
    fn multimc_takes_dot_minecraft_and_a_moved_instance_folder() {
        let world = World::new();
        let root = MultiMc.locations(&world.env)[0].clone();
        let elsewhere = world.root.join("D/Instances");
        // MultiMC's plain dialect, with an escaped absolute path.
        write(&root, "multimc.cfg", format!("InstanceDir={}\n", elsewhere.display().to_string().replace('\\', "\\\\")).as_bytes());
        write(&root, "accounts.json", b"{\"token\":\"secret\"}");
        let pvp = elsewhere.join("PvP");
        write(&pvp, "instance.cfg", b"InstanceType=OneSix\nname=PvP\\tPack\nlastLaunchTime=1761926400000\n");
        write(&pvp, "mmc-pack.json", FABRIC_PACK);
        write(&pvp, ".minecraft/options.txt", b"version:3955\nfov:1\n");
        write(&pvp, "minecraft/options.txt", b"other");
        let (listed, result) = import_everything(&world, &MultiMc, false);
        assert_eq!(listed.listing.launcher.id, LauncherId::Multimc);
        assert_eq!(listed.listing.profiles[0].name, "PvP\tPack".replace('\t', ""), "control characters are dropped from names");
        let dir = world.state.paths.profile_dir(result.profiles[0].profile_id.as_ref().unwrap());
        assert_eq!(std::fs::read(dir.join("options.txt")).unwrap(), b"version:3955\nfov:1\n", ".minecraft/ wins in MultiMC");
        // Prism doesn't claim a MultiMC folder.
        assert!(matches!(Prism.read(&root, &world.env), Found::NotHere));
    }

    #[test]
    fn both_ini_dialects_are_read() {
        let qt = ini("[General]\nname=\"A \\\"quoted\\\" name\"\nnotes=line\\nbreak\nInstanceDir=C:\\\\Games\\\\instances\n%41key=1\n");
        assert_eq!(qt["name"], "A \"quoted\" name");
        assert_eq!(qt["notes"], "line\nbreak");
        assert_eq!(qt["InstanceDir"], "C:\\Games\\instances");
        assert_eq!(qt["Akey"], "1");
        let plain = ini("# comment\nname=Pack \\# 2\niconKey=flame\n");
        assert_eq!((plain["name"].as_str(), plain["iconKey"].as_str()), ("Pack # 2", "flame"));
    }
}
