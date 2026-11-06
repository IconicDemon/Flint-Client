//! Lunar Client (launcher 3.7.x). Data folder `~/.lunarclient` on every system (`%USERPROFILE%\.lunarclient` on Windows,
//! not %APPDATA%; Lunar's support pages name `~/.lunarclient/settings/game`), Flatpak
//! `~/.var/app/com.lunarclient.LunarClient/.lunarclient`. Launcher profiles are the rows of `db/profiles.db` (SQLite,
//! read from a private copy; table `profiles`: id, name, path, type, game_version, loaders (JSON array, e.g.
//! `["ichor","fabric"]`), loader_version, game_directory, mods_directory, icon_path), verified on a real database
//! (schema migrations 0001 to 0004). Lunar-type profiles keep their jars in `<mods_directory>/fabric-<game_version>/`
//! (a `dependencies/` folder of libraries Lunar extracted sits below it, and folders of older versions beside it) and use
//! `game_directory`, the shared `.minecraft`; modrinth-type rows keep theirs in `profiles/<path>/mods` with the pack's
//! config and packs in `profiles/<path>/`, and leave `game_directory` empty (whether Lunar then uses that folder or
//! `settings/launcher.json` `settings.gameDirectory` is unconfirmed, so both are read, the profile's own first).
//! No last-played time is stored: `profiles/<path>/logs/latest.log`'s time stands in. `icon_path` is relative to
//! `profiles/<path>/`. Lunar's in-game settings profiles (`settings/game/<Name>/`) and `optionsLC.txt` are Lunar's own.

use super::{component, loader_named, modified, relative, Adapter, DbCopy, Found};
use crate::import::{scan, Env, ImportLoader, LauncherId, Source};
use serde::Deserialize;
use std::path::{Path, PathBuf};

pub struct Lunar;

const NOTE: &str = "Lunar's own features (its built-in mods, HUD and in-game settings, and optionsLC.txt) are Lunar's and stay behind.";

impl Adapter for Lunar {
    fn id(&self) -> LauncherId {
        LauncherId::Lunar
    }

    fn name(&self) -> &'static str {
        "Lunar Client"
    }

    fn locations(&self, env: &Env) -> Vec<PathBuf> {
        [super::under(&env.home, ".lunarclient"), super::under(&env.home, ".var/app/com.lunarclient.LunarClient/.lunarclient")].into_iter().flatten().collect()
    }

    fn read(&self, root: &Path, env: &Env) -> Found {
        let db = root.join("db").join("profiles.db");
        if scan::real_file(&db).is_none() {
            // Lunar is here (its settings), but it keeps no launcher profiles.
            return if scan::real_dir(&root.join("settings").join("game")) { Found::Profiles(Vec::new()) } else { Found::NotHere };
        }
        let copy = match DbCopy::open(&db, &env.temp) {
            Ok(copy) => copy,
            Err(e) => return Found::Unreadable(format!("Flint couldn't read Lunar Client's profile list. {e}")),
        };
        let rows = match rows(copy.conn()) {
            Ok(rows) => rows,
            Err(_) => return Found::Unreadable("Lunar Client's profile list is in a form Flint can't read, so its profiles can't be listed.".into()),
        };
        let default_game = default_game(root, env);
        Found::Profiles(rows.into_iter().take(super::MAX_INSTANCES).map(|row| source(root, row, default_game.as_deref())).collect())
    }
}

struct Row {
    id: String,
    name: String,
    path: String,
    game_version: Option<String>,
    loaders: Option<String>,
    loader_version: Option<String>,
    game_directory: Option<String>,
    mods_directory: Option<String>,
    icon_path: Option<String>,
}

fn rows(conn: &rusqlite::Connection) -> rusqlite::Result<Vec<Row>> {
    let mut statement = conn.prepare("SELECT id, name, path, game_version, loaders, loader_version, game_directory, mods_directory, icon_path FROM profiles")?;
    let rows = statement.query_map([], |r| {
        Ok(Row {
            id: r.get(0)?,
            name: r.get(1)?,
            path: r.get(2)?,
            game_version: r.get(3)?,
            loaders: r.get(4)?,
            loader_version: r.get(5)?,
            game_directory: r.get(6)?,
            mods_directory: r.get(7)?,
            icon_path: r.get(8)?,
        })
    })?;
    rows.collect()
}

/// The game folder Lunar uses when a profile names none: `settings/launcher.json` `settings.gameDirectory` (only that
/// key is read), else the usual `.minecraft`.
fn default_game(root: &Path, env: &Env) -> Option<PathBuf> {
    #[derive(Deserialize, Default)]
    struct Launcher {
        #[serde(default)]
        settings: Settings,
    }
    #[derive(Deserialize, Default)]
    struct Settings {
        #[serde(default, rename = "gameDirectory")]
        game_directory: Option<String>,
    }
    let configured = super::read_text(&root.join("settings").join("launcher.json"))
        .and_then(Result::ok)
        .and_then(|text| serde_json::from_str::<Launcher>(&text).ok())
        .and_then(|l| l.settings.game_directory)
        .map(PathBuf::from)
        .filter(|p| p.is_absolute() && p.is_dir());
    configured.or_else(|| super::default_minecraft(env).filter(|p| p.is_dir()))
}

/// An absolute folder from a row, when it's there.
fn absolute(path: &Option<String>) -> Option<PathBuf> {
    path.as_deref().map(str::trim).filter(|p| !p.is_empty()).map(PathBuf::from).filter(|p| p.is_absolute())
}

fn source(root: &Path, row: Row, default_game: Option<&Path>) -> Source {
    let folder = component(&row.path);
    let own = root.join("profiles").join(folder.unwrap_or("-"));
    let named = absolute(&row.game_directory);
    let game_dir = named.clone().or_else(|| default_game.map(Path::to_path_buf)).unwrap_or_else(|| own.clone());
    let mut s = Source::new(LauncherId::Lunar, &row.name, game_dir);
    s.id = Some(row.id);
    s.game_version = row.game_version.filter(|v| !v.is_empty());
    let loaders: Vec<String> = row.loaders.as_deref().and_then(|l| serde_json::from_str::<Vec<String>>(l).ok()).unwrap_or_default();
    // `ichor` is Lunar's own layer; a profile with no public loader is Lunar on Vanilla.
    s.loader = loaders.iter().map(|l| loader_named(l)).find(|l| *l != ImportLoader::Unknown && *l != ImportLoader::Vanilla).unwrap_or(ImportLoader::Vanilla);
    s.loader_version = row.loader_version.filter(|v| !v.is_empty());
    s.last_played = modified(&own.join("logs").join("latest.log"));
    s.icon = row.icon_path.as_deref().and_then(relative).map(|rel| scan::join(&own, &rel));
    if named.is_none() {
        // A modpack row: its own folder holds the pack's settings and packs.
        s.prefer.push(own.clone());
    }
    let mods = absolute(&row.mods_directory).unwrap_or_else(|| own.join("mods"));
    let per_version = s.game_version.as_deref().and_then(|v| component(&format!("fabric-{v}")).map(|f| mods.join(f)));
    let mods = per_version.filter(|p| p.is_dir()).unwrap_or(mods);
    s.overrides.push(("mods".into(), mods));
    s.notes.push(NOTE.into());
    s.problem = if folder.is_none() {
        Some("Its folder name in Lunar Client's records can't be read safely.".into())
    } else if named.as_ref().is_some_and(|d| !d.is_dir()) {
        Some("Its game folder no longer exists.".into())
    } else {
        None
    };
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::tests::{fabric_jar, import_everything, write, World};
    use crate::import::ImportSupport;

    #[test]
    fn lunar_profiles_come_from_its_database_with_their_own_mods() {
        let world = World::new();
        let root = Lunar.locations(&world.env)[0].clone();
        let minecraft = world.root.join("appdata/.minecraft");
        std::fs::create_dir_all(root.join("db")).unwrap();
        {
            let db = rusqlite::Connection::open(root.join("db/profiles.db")).unwrap();
            // The schema of a real profiles.db (its columns, in its order).
            db.execute_batch(
                "CREATE TABLE profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT, icon_path TEXT, featured_image_path TEXT,
                   path TEXT UNIQUE NOT NULL, type TEXT, major_game_version TEXT, game_version TEXT, loaders TEXT DEFAULT '[]',
                   use_lunar_features INT, is_badlion INT, provider TEXT, modrinth TEXT, curseforge TEXT, user_modpack TEXT,
                   allocated_memory INT, game_resolution TEXT, loader_version TEXT, game_directory TEXT, mods_directory TEXT,
                   jvm_arguments TEXT, pre_launch_command TEXT, wrapper_command TEXT, post_exit_command TEXT, environment_variables TEXT,
                   lunar_module TEXT, created_at INT, config_version INT DEFAULT 3, update_channel TEXT, garbage_collector TEXT, pack_state TEXT);
                 CREATE TABLE modpack_version (profile_id TEXT, version_id TEXT, version_label TEXT, game_version TEXT, is_selected INT, installed_at INT);",
            )
            .unwrap();
            let insert = "INSERT INTO profiles (id, name, path, type, major_game_version, game_version, loaders, loader_version, game_directory, mods_directory, lunar_module, created_at, icon_path) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, 'fabric', 1791098966, ?11)";
            let gd = Some(minecraft.display().to_string());
            let mods = |p: &str| Some(root.join("profiles").join(p).join("mods").display().to_string());
            db.execute(insert, rusqlite::params!["11111111-1111-1111-1111-111111111111", "Minecraft 26", "26-1", "lunar", "26", "26.3", r#"["ichor","fabric"]"#, "0.19.5", gd, mods("26-1"), None::<String>]).unwrap();
            db.execute(insert, rusqlite::params!["22222222-2222-2222-2222-222222222222", "Minecraft 1.21", "1.21", "lunar", "1.21", "1.21.11", r#"["ichor","fabric"]"#, None::<String>, gd, mods("1.21"), None::<String>]).unwrap();
            db.execute(insert, rusqlite::params!["33333333-3333-3333-3333-333333333333", "Skyblocker Modpack - Hypixel Skyblock", "skyblocker", "modrinth", None::<String>, "26.2", r#"["ichor","fabric"]"#, None::<String>, None::<String>, None::<String>, "icon.png"]).unwrap();
            db.execute(insert, rusqlite::params!["44444444-4444-4444-4444-444444444444", "PvP 1.8.9", "189", "lunar", "1.8", "1.8.9", r#"["ichor"]"#, None::<String>, gd, None::<String>, None::<String>]).unwrap();
        }
        // The shared game folder (also the official launcher's), with Lunar's own options copy.
        write(&minecraft, "options.txt", b"version:5023\nfov:0.5\n");
        write(&minecraft, "optionsLC.txt", br#"{"fov":"0.5","lastLaunchedVersion":"v26_3"}"#);
        write(&minecraft, "saves/Survival/level.dat", b"level");
        write(&minecraft, "resourcepacks/Faithful.zip", b"pack");
        // Lunar-type mods: the current version's folder, an old version's, and Lunar's extracted libraries.
        fabric_jar(&root, "profiles/26-1/mods/fabric-26.3/sodium.jar");
        fabric_jar(&root, "profiles/26-1/mods/fabric-26.3/zoom.jar.disabled");
        fabric_jar(&root, "profiles/26-1/mods/fabric-26.3/dependencies/lib-1a2b3c.jar");
        fabric_jar(&root, "profiles/26-1/mods/fabric-26.2/old.jar");
        write(&root, "profiles/26-1/logs/latest.log", b"log");
        fabric_jar(&root, "profiles/1.21/mods/fabric-1.21.11/iris.jar");
        // A modpack profile: mods straight in mods/, its own config and packs.
        fabric_jar(&root, "profiles/skyblocker/mods/skyblocker.jar");
        write(&root, "profiles/skyblocker/config/skyblocker.json", b"{}");
        write(&root, "profiles/skyblocker/icon.png", b"\x89PNG\r\n\x1a\nicon");
        write(&root, "profiles/skyblocker/versions/3.5.34/cached-install.mrpack", b"zip");
        // Sign-ins and Lunar's own settings, never copied.
        write(&root, "settings/game/accounts.json", br#"{"accounts":{"x":{"accessToken":"secret"}}}"#);
        write(&root, "settings/game-backup/accounts.json", b"secret");
        write(&root, "settings/game/Default/mods.json", b"{}");
        write(&root, "settings/launcher.json", serde_json::json!({ "settings": { "gameDirectory": minecraft.display().to_string(), "privacy": "x" } }).to_string().as_bytes());

        let (listed, result) = import_everything(&world, &Lunar, true);
        let by = |name: &str| listed.listing.profiles.iter().find(|p| p.name == name).unwrap_or_else(|| panic!("{name}"));
        let m26 = by("Minecraft 26");
        assert_eq!((m26.game_version.as_deref(), m26.loader, m26.mods, m26.worlds, m26.shared_folder), (Some("26.3"), ImportLoader::Fabric, 2, 1, true), "only fabric-26.3's own jars");
        assert!(m26.last_played.is_some(), "from latest.log");
        assert_eq!(by("Minecraft 1.21").mods, 1);
        let sky = by("Skyblocker Modpack - Hypixel Skyblock");
        assert_eq!((sky.mods, sky.icon.is_some()), (1, true));
        let pvp = by("PvP 1.8.9");
        assert_eq!((pvp.loader, pvp.support), (ImportLoader::Vanilla, ImportSupport::Full), "Lunar on Vanilla 1.8.9");
        assert!(result.profiles.iter().all(|p| p.profile_id.is_some()), "{result:?}");
        let profiles = world.existing().profiles;
        let dir = |name: &str| world.state.paths.profile_dir(&profiles.iter().find(|p| p.name == name).unwrap().id);
        let m26 = dir("Minecraft 26");
        assert!(m26.join("mods/sodium.jar").is_file() && m26.join("mods/zoom.jar.disabled").is_file());
        assert!(!m26.join("mods/old.jar").exists() && !m26.join("mods/dependencies").exists() && !m26.join("mods/lib-1a2b3c.jar").exists());
        assert!(m26.join("options.txt").is_file() && m26.join("saves/Survival/level.dat").is_file());
        assert!(!m26.join("optionsLC.txt").exists(), "Lunar's own options copy stays");
        let sky = dir("Skyblocker Modpack - Hypixel Skyblock");
        assert!(sky.join("mods/skyblocker.jar").is_file() && sky.join("config/skyblocker.json").is_file());
        assert!(sky.join("options.txt").is_file(), "the rest from the game folder Lunar uses");
        assert!(!sky.join("versions").exists());
        let preview = crate::import::preview(&listed, &world.existing(), &crate::import::ImportRequest { launcher: LauncherId::Lunar, folder: None, keys: vec![listed.listing.profiles[0].key.clone()], worlds: false }, &scan::Limits::default(), None);
        assert!(preview.profiles[0].warnings.iter().any(|w| w == NOTE));
    }
}
