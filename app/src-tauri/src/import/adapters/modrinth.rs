//! Modrinth App (open source: github.com/modrinth/code, packages/app-lib). Three storage eras, told apart by what is on
//! disk:
//! - A, app 0.22 and later (mid-2026): `app.db` tables `instances` (id, path, name, icon_path, install_stage,
//!   last_played in epoch seconds) and `instance_content_sets` (game_version, loader, loader_version), joined on
//!   `applied_content_set_id`; `instance_quarantines(instance_id)`. Migrations 20260611120000_instances-content-foundation
//!   and 20260619120000_finish-instance-profile-migration (which gives migrated profiles the id `legacy:<path>`).
//!   Verified on a real 0.22.1 database (read from a copy).
//! - B, app 0.8 to early 2026: `app.db` table `profiles` (path, name, icon_path, install_stage, game_version,
//!   mod_loader, mod_loader_version, last_played in epoch seconds): migrations/20240711194701_init.sql, state/profiles.rs.
//! - C, before July 2024: `com.modrinth.theseus/profiles/<dir>/profile.json` (state/legacy_converter.rs:
//!   metadata.name, game_version, loader, loader_version.id, last_played as RFC 3339), moved by `settings.json`
//!   `loaded_config_dir`.
//!
//! The data folder is `dirs::data_dir()/ModrinthApp` or `THESEUS_CONFIG_DIR` (state/dirs.rs); the database always
//! stays there, while `settings.custom_dir` moves `profiles/`. The database is only read from a private copy
//! (`DbCopy`), and only these tables and columns are read: never the sign-in tables.

use super::{component, epoch_seconds, iso_seconds, loader_named, read_json, sub_dirs, text, Adapter, DbCopy, Found};
use crate::import::{scan, Env, LauncherId, Source};
use std::collections::HashSet;
use std::path::{Path, PathBuf};

pub struct Modrinth;

impl Adapter for Modrinth {
    fn id(&self) -> LauncherId {
        LauncherId::Modrinth
    }

    fn name(&self) -> &'static str {
        "Modrinth App"
    }

    fn locations(&self, env: &Env) -> Vec<PathBuf> {
        [env.var("THESEUS_CONFIG_DIR"), super::under(&env.appdata, "ModrinthApp"), super::under(&env.config, "com.modrinth.theseus")].into_iter().flatten().collect()
    }

    fn read(&self, root: &Path, env: &Env) -> Found {
        if scan::real_file(&root.join("app.db")).is_some() {
            return read_db(root, env);
        }
        read_legacy(root)
    }
}

/// One profile from any era.
struct Row {
    id: String,
    path: String,
    name: Option<String>,
    icon: Option<String>,
    stage: Option<String>,
    last_played: Option<u64>,
    version: Option<String>,
    loader: Option<String>,
    loader_version: Option<String>,
    quarantined: bool,
}

fn source(row: Row, profiles: &Path) -> Source {
    let folder = component(&row.path);
    let game_dir = profiles.join(folder.unwrap_or("-"));
    let name = row.name.clone().filter(|n| !n.trim().is_empty()).unwrap_or_else(|| row.path.clone());
    let mut s = Source::new(LauncherId::Modrinth, &name, game_dir);
    s.id = Some(row.id);
    s.game_version = row.version.filter(|v| !v.is_empty());
    s.loader = row.loader.as_deref().map(loader_named).unwrap_or(crate::import::ImportLoader::Unknown);
    s.loader_version = row.loader_version.filter(|v| !v.is_empty());
    s.last_played = row.last_played;
    s.icon = row.icon.map(PathBuf::from).filter(|p| p.is_absolute());
    s.problem = if folder.is_none() {
        Some("Its folder name in the Modrinth App's records can't be read safely.".into())
    } else if row.stage.as_deref() != Some("installed") {
        Some("It isn't fully installed in the Modrinth App.".into())
    } else if row.quarantined {
        Some("The Modrinth App has set it aside for review (quarantined).".into())
    } else if !s.game_dir.is_dir() {
        Some("Its folder is missing.".into())
    } else {
        None
    };
    s
}

fn read_db(root: &Path, env: &Env) -> Found {
    let copy = match DbCopy::open(&root.join("app.db"), &env.temp) {
        Ok(copy) => copy,
        Err(e) => return Found::Unreadable(format!("Flint couldn't read the Modrinth App's profile list. {e}")),
    };
    match rows(copy.conn(), root) {
        Ok((rows, profiles)) => Found::Profiles(rows.into_iter().take(super::MAX_INSTANCES).map(|r| source(r, &profiles)).collect()),
        Err(e) => Found::Unreadable(e),
    }
}

fn rows(conn: &rusqlite::Connection, root: &Path) -> Result<(Vec<Row>, PathBuf), String> {
    let damaged = |_: rusqlite::Error| "The Modrinth App's profile list is damaged, so Flint couldn't read it.".to_string();
    let tables: HashSet<String> = conn.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").and_then(|mut s| s.query_map([], |r| r.get(0)).map(|rows| rows.flatten().collect())).map_err(damaged)?;
    // `profiles/` may have been moved (Settings → Default instance folder); the database stays in the data folder.
    let custom: Option<String> = if tables.contains("settings") { conn.query_row("SELECT custom_dir FROM settings LIMIT 1", [], |r| r.get(0)).ok().flatten() } else { None };
    let base = custom.map(PathBuf::from).filter(|p| p.is_absolute() && p.is_dir()).unwrap_or_else(|| root.to_path_buf());
    let profiles = base.join("profiles");
    if tables.contains("instances") {
        let quarantined: HashSet<String> = if tables.contains("instance_quarantines") {
            conn.prepare("SELECT instance_id FROM instance_quarantines").and_then(|mut s| s.query_map([], |r| r.get(0)).map(|rows| rows.flatten().collect())).unwrap_or_default()
        } else {
            HashSet::new()
        };
        let mut statement = conn
            .prepare(
                "SELECT i.id, i.path, i.name, i.icon_path, i.install_stage, i.last_played, cs.game_version, cs.loader, cs.loader_version
                 FROM instances i LEFT JOIN instance_content_sets cs ON cs.id = i.applied_content_set_id",
            )
            .map_err(damaged)?;
        let rows = statement
            .query_map([], |r| {
                let id: String = r.get(0)?;
                Ok(Row {
                    quarantined: quarantined.contains(&id),
                    id,
                    path: r.get(1)?,
                    name: r.get(2)?,
                    icon: r.get(3)?,
                    stage: r.get(4)?,
                    last_played: r.get::<_, Option<i64>>(5)?.and_then(epoch_seconds),
                    version: r.get(6)?,
                    loader: r.get(7)?,
                    loader_version: r.get(8)?,
                })
            })
            .map_err(damaged)?
            .collect::<Result<Vec<Row>, _>>()
            .map_err(damaged)?;
        return Ok((rows, profiles));
    }
    if tables.contains("profiles") {
        let mut statement = conn.prepare("SELECT path, name, icon_path, install_stage, last_played, game_version, mod_loader, mod_loader_version FROM profiles").map_err(damaged)?;
        let rows = statement
            .query_map([], |r| {
                let path: String = r.get(0)?;
                Ok(Row {
                    // The id the later migration gives it, so an import before and after the upgrade is the same one.
                    id: format!("legacy:{path}"),
                    path,
                    name: r.get(1)?,
                    icon: r.get(2)?,
                    stage: r.get(3)?,
                    last_played: r.get::<_, Option<i64>>(4)?.and_then(epoch_seconds),
                    version: r.get(5)?,
                    loader: r.get(6)?,
                    loader_version: r.get(7)?,
                    quarantined: false,
                })
            })
            .map_err(damaged)?
            .collect::<Result<Vec<Row>, _>>()
            .map_err(damaged)?;
        return Ok((rows, profiles));
    }
    Err("This version of the Modrinth App keeps its profiles in a way Flint can't read yet.".into())
}

/// Era C: one `profile.json` per profile.
fn read_legacy(root: &Path) -> Found {
    // Only `loaded_config_dir` is read from its settings.
    let moved = read_json(&root.join("settings.json")).and_then(Result::ok).and_then(|v| text(&v, "loaded_config_dir")).map(PathBuf::from).filter(|p| p.is_absolute() && p.is_dir());
    let profiles = moved.unwrap_or_else(|| root.to_path_buf()).join("profiles");
    let files: Vec<(PathBuf, Option<Result<serde_json::Value, String>>)> =
        sub_dirs(&profiles).into_iter().filter(|d| scan::real_file(&d.join("profile.json")).is_some()).map(|d| (d.join("profile.json"), d)).map(|(f, d)| (d, read_json(&f))).collect();
    // Other launchers keep a `profiles/<x>/profile.json` too (Dawn): the folder is the Modrinth App's only when one of
    // them has this era's shape.
    if !files.iter().any(|(_, json)| matches!(json, Some(Ok(v)) if v["metadata"].is_object() && v.get("install_stage").is_some())) {
        return Found::NotHere;
    }
    Found::Profiles(
        files
            .into_iter()
            .map(|(dir, json)| {
                let folder = dir.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
                match json {
                    Some(Ok(json)) => {
                        let meta = &json["metadata"];
                        let row = Row {
                            id: format!("legacy:{}", text(&json, "path").unwrap_or_else(|| folder.clone())),
                            path: folder.clone(),
                            name: text(meta, "name"),
                            icon: text(meta, "icon"),
                            stage: text(&json, "install_stage"),
                            last_played: text(meta, "last_played").as_deref().and_then(iso_seconds),
                            version: text(meta, "game_version"),
                            loader: text(meta, "loader"),
                            loader_version: text(&meta["loader_version"], "id"),
                            quarantined: false,
                        };
                        source(row, &profiles)
                    }
                    other => {
                        let mut s = Source::new(LauncherId::Modrinth, &folder, dir.clone());
                        s.id = Some(format!("legacy:{folder}"));
                        s.problem = Some(match other {
                            Some(Err(e)) => e,
                            _ => "Its profile.json is missing.".into(),
                        });
                        s
                    }
                }
            })
            .collect(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::tests::{import_everything, write, fabric_jar, World};
    use crate::import::{ImportLoader, ImportSupport};

    /// A profile folder as the Modrinth App 0.22 leaves it, with a mod's account folder and the shared typed-command
    /// history that must never come over.
    fn profile_folder(dir: &Path) {
        write(dir, "options.txt", b"version:4671\nfov:0.5\n");
        write(dir, "servers.dat", b"servers");
        fabric_jar(dir, "mods/sodium.jar");
        fabric_jar(dir, "mods/zoom.jar.disabled");
        write(dir, "resourcepacks/Pack.zip", b"pack");
        write(dir, "config/sodium-options.json", b"{}");
        write(dir, "saves/World/level.dat", b"level");
        write(dir, "_IAS_ACCOUNTS_DO_NOT_SEND_TO_ANYONE/accounts.dat", b"secret account");
        write(dir, "command_history.txt", b"/login secret-password");
        write(dir, "usercache.json", b"[]");
        write(dir, "logs/latest.log", b"log");
        write(dir, ".fabric/x", b"cache");
    }

    #[test]
    fn modrinth_app_instances_are_read_from_a_copy_of_its_database() {
        let world = World::new();
        let root = Modrinth.locations(&world.env)[0].clone();
        std::fs::create_dir_all(&root).unwrap();
        {
            let db = rusqlite::Connection::open(root.join("app.db")).unwrap();
            let _: String = db.query_row("PRAGMA journal_mode=WAL", [], |r| r.get(0)).unwrap();
            // The tables as the real 0.22.1 database has them (only the columns Flint reads, plus the sign-in tables
            // Flint must never read).
            db.execute_batch(
                "PRAGMA wal_autocheckpoint=0;
                 CREATE TABLE settings (id INTEGER NOT NULL CHECK (id = 0), custom_dir TEXT NULL);
                 INSERT INTO settings VALUES (0, NULL);
                 CREATE TABLE instances (id TEXT NOT NULL, path TEXT NOT NULL, applied_content_set_id TEXT NULL, install_stage TEXT NOT NULL,
                   launcher_feature_version TEXT NOT NULL, update_channel TEXT NOT NULL DEFAULT 'release', name TEXT NOT NULL, icon_path TEXT NULL,
                   created INTEGER NOT NULL, modified INTEGER NOT NULL, last_played INTEGER NULL, submitted_time_played INTEGER NOT NULL DEFAULT 0,
                   recent_time_played INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (id));
                 CREATE TABLE instance_content_sets (id TEXT NOT NULL, instance_id TEXT NOT NULL, name TEXT NOT NULL, source_kind TEXT NOT NULL,
                   status TEXT NOT NULL, game_version TEXT NOT NULL, protocol_version INTEGER NULL, loader TEXT NOT NULL, loader_version TEXT NULL,
                   created INTEGER NOT NULL, modified INTEGER NOT NULL, PRIMARY KEY (id));
                 CREATE TABLE instance_quarantines (instance_id TEXT PRIMARY KEY NOT NULL);
                 CREATE TABLE minecraft_users (uuid TEXT, access_token TEXT);
                 INSERT INTO minecraft_users VALUES ('u', 'secret-token');",
            )
            .unwrap();
            let rows = [
                ("local:aaaa", "PVPify", "installed", "PVP", "1.21.11", "fabric", Some("0.19.3"), Some(1792690354i64)),
                ("local:bbbb", "REC", "installed", "REC", "26.1.2", "fabric", Some("0.19.5"), Some(1792778083)),
                ("local:cccc", "Forge Pack", "installed", "Forge Pack", "1.21.1", "neoforge", Some("21.1.7"), None),
                ("local:dddd", "Half", "pack_installing", "Half", "26.3", "fabric", None, None),
                ("local:eeee", "Flagged", "installed", "Flagged", "26.3", "vanilla", None, None),
            ];
            for (id, path, stage, name, version, loader, loader_version, played) in rows {
                let set = format!("content-set:{id}");
                db.execute(
                    "INSERT INTO instances (id, path, applied_content_set_id, install_stage, launcher_feature_version, name, created, modified, last_played) VALUES (?1, ?2, ?3, ?4, 'migrated_launch_hooks', ?5, 1, 1, ?6)",
                    rusqlite::params![id, path, set, stage, name, played],
                )
                .unwrap();
                db.execute("INSERT INTO instance_content_sets VALUES (?1, ?2, 'Default', 'local', 'available', ?3, NULL, ?4, ?5, 1, 1)", rusqlite::params![set, id, version, loader, loader_version]).unwrap();
            }
            db.execute("INSERT INTO instance_quarantines VALUES ('local:eeee')", []).unwrap();
            for path in ["PVPify", "REC", "Forge Pack", "Half", "Flagged"] {
                profile_folder(&root.join("profiles").join(path));
            }
            write(&root, "caches/metadata/auth.json", b"secret");
            // The app is still running: the rows are only in the WAL when Flint reads.
            let (listed, result) = import_everything(&world, &Modrinth, false);
            let by = |name: &str| listed.listing.profiles.iter().find(|p| p.name == name).unwrap_or_else(|| panic!("{name} listed"));
            assert_eq!(listed.listing.profiles.len(), 5);
            let pvp = by("PVP");
            assert_eq!((pvp.game_version.as_deref(), pvp.loader, pvp.loader_version.as_deref(), pvp.last_played), (Some("1.21.11"), ImportLoader::Fabric, Some("0.19.3"), Some(1792690354)));
            assert_eq!((pvp.mods, pvp.worlds, pvp.support), (2, 1, ImportSupport::Full));
            assert_eq!(listed.listing.profiles[0].name, "REC", "newest played first");
            assert_eq!(by("Forge Pack").support, ImportSupport::Settings);
            assert!(by("Half").support_note.as_deref().unwrap().contains("fully installed"));
            assert!(by("Flagged").support_note.as_deref().unwrap().contains("quarantined"));
            let done: Vec<&str> = result.profiles.iter().filter(|p| p.profile_id.is_some()).map(|p| p.name.as_str()).collect();
            assert_eq!(done.len(), 3, "PVP, REC and the Forge pack (settings only): {done:?}");
            let pvp_profile = world.existing().profiles.into_iter().find(|p| p.name == "PVP").unwrap();
            let dir = world.state.paths.profile_dir(&pvp_profile.id);
            assert!(dir.join("mods/sodium.jar").is_file() && dir.join("mods/zoom.jar.disabled").is_file() && dir.join("servers.dat").is_file());
            for never in ["_IAS_ACCOUNTS_DO_NOT_SEND_TO_ANYONE", "command_history.txt", "usercache.json", "logs", ".fabric"] {
                assert!(!dir.join(never).exists(), "{never} must not come over");
            }
            drop(db);
        }
        // A damaged database: the launcher is listed as unreadable.
        let broken = World::new();
        let root = Modrinth.locations(&broken.env)[0].clone();
        write(&root, "app.db", b"this is not a database");
        let listed = crate::import::discover(&Modrinth, &broken.env, &broken.existing()).unwrap();
        assert_eq!(listed.listing.launcher.status, crate::import::LauncherStatus::Unreadable);
        assert!(std::fs::read_dir(&broken.env.temp).map(|d| d.count()).unwrap_or(0) == 0, "no copy is left behind");
    }

    #[test]
    fn older_modrinth_app_profiles_are_read_too() {
        // Era B: the `profiles` table.
        let world = World::new();
        let root = Modrinth.locations(&world.env)[0].clone();
        std::fs::create_dir_all(&root).unwrap();
        {
            let db = rusqlite::Connection::open(root.join("app.db")).unwrap();
            db.execute_batch(
                "CREATE TABLE profiles (path TEXT NOT NULL PRIMARY KEY, install_stage TEXT NOT NULL, name TEXT NOT NULL, icon_path TEXT,
                   game_version TEXT NOT NULL, mod_loader TEXT NOT NULL, mod_loader_version TEXT, groups JSONB NOT NULL DEFAULT '[]',
                   linked_project_id TEXT, linked_version_id TEXT, locked INTEGER, created INTEGER NOT NULL, modified INTEGER NOT NULL,
                   last_played INTEGER, submitted_time_played INTEGER NOT NULL DEFAULT 0, recent_time_played INTEGER NOT NULL DEFAULT 0);
                 INSERT INTO profiles (path, install_stage, name, game_version, mod_loader, mod_loader_version, created, modified, last_played)
                   VALUES ('Fabulously Optimized', 'installed', 'FO', '1.21.1', 'fabric', '0.16.10', 1, 1, 1761926400);",
            )
            .unwrap();
        }
        profile_folder(&root.join("profiles/Fabulously Optimized"));
        let (listed, result) = import_everything(&world, &Modrinth, false);
        assert_eq!(listed.listing.profiles.len(), 1);
        let fo = &listed.listing.profiles[0];
        assert_eq!((fo.name.as_str(), fo.game_version.as_deref(), fo.last_played), ("FO", Some("1.21.1"), Some(1761926400)));
        assert!(result.profiles[0].profile_id.is_some());
        // The key is the one the app's later migration gives the profile (`legacy:<path>`).
        let source = &listed.sources[0];
        assert_eq!(source.id.as_deref(), Some("legacy:Fabulously Optimized"));

        // Era C: one profile.json per profile, under com.modrinth.theseus.
        let world = World::new();
        let root = Modrinth.locations(&world.env).into_iter().find(|p| p.ends_with("com.modrinth.theseus")).unwrap();
        write(
            &root,
            "profiles/Old Pack/profile.json",
            br#"{"install_stage":"installed","path":"Old Pack","metadata":{"name":"Old Pack","icon":null,"groups":[],"game_version":"1.21.1","loader":"fabric","loader_version":{"id":"0.15.11"},"date_created":"2024-05-01T12:00:00Z","date_modified":"2024-05-01T12:00:00Z","last_played":"2024-06-01T10:00:00Z"}}"#,
        );
        profile_folder(&root.join("profiles/Old Pack"));
        write(&root, "profiles/Broken/profile.json", b"{ not json");
        write(&root, "caches/metadata/minecraft_auth.json", b"secret");
        let (listed, _) = import_everything(&world, &Modrinth, false);
        let old = listed.listing.profiles.iter().find(|p| p.name == "Old Pack").unwrap();
        assert_eq!((old.game_version.as_deref(), old.loader, old.loader_version.as_deref(), old.last_played), (Some("1.21.1"), ImportLoader::Fabric, Some("0.15.11"), Some(1717236000)));
        let broken = listed.listing.profiles.iter().find(|p| p.name == "Broken").unwrap();
        assert_eq!(broken.support, ImportSupport::Unsupported);
    }
}
