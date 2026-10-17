//! Minecraft settings, Flint's own in-game settings, resource and shader packs and mod settings, kept
//! consistent between Flint profiles by Auto Sync (`autosync`), the one switch in Settings → General.
//!
//! The pieces Auto Sync is built on, each tested on its own:
//! - `options_txt`: options.txt read and written without losing a byte Flint doesn't change;
//! - `registry` and `tables`: the real options of each verified Minecraft family, their key names and the
//!   graphics preset rule; `versions`: which family an installed version is, read from its jar;
//! - `plan`: whether a value fits another profile's version (key, key name, value, preset), with no I/O;
//! - `flintfiles`: Flint's HUD layouts, Control Center preferences and server shortcuts;
//! - `backup` and `apply`: backups, transactional writes with read-back and roll-back, and the start-up
//!   cleanup of interrupted writes.
//!
//! A profile's files are never written while its game runs (or might), always backed up first, written
//! atomically, and keep every line and key Flint doesn't own. Only Minecraft versions checked against their
//! real game code are ever written.

mod apply;
pub mod autosync;
mod backup;
mod flintfiles;
mod options_txt;
mod plan;
mod registry;
mod tables;
mod versions;

pub use apply::clean_interrupted_writes;

#[cfg(test)]
pub mod testkit {
    use crate::core::AppState;
    use crate::data::{Profile, ProfilesFile};
    use std::path::Path;

    /// options.txt as Minecraft 26.3 wrote it on a test profile (no personal data; `lastServer` is empty).
    pub const FIXTURE_26_3: &[u8] = include_bytes!("testdata/options-26.3.txt");

    /// A minimal client jar for `version` under `game_root/versions/`, whose version.json reports
    /// `data_version` and the resource pack format the way Minecraft's own jars do (the real values, read
    /// from the 26.1–26.3 jars: 26.1.x 84.0, 26.2 88.0, 26.3 97.1).
    pub fn install_version(game_root: &Path, version: &str, data_version: u32) {
        use std::io::Write;
        let dir = game_root.join("versions").join(version);
        std::fs::create_dir_all(&dir).unwrap();
        let mut zip = zip::ZipWriter::new(std::fs::File::create(dir.join(format!("{version}.jar"))).unwrap());
        zip.start_file("version.json", zip::write::SimpleFileOptions::default()).unwrap();
        let pack = match data_version {
            4786 | 4788 | 4790 => serde_json::json!({ "resource_major": 84, "resource_minor": 0, "data_major": 101, "data_minor": 1 }),
            4903 => serde_json::json!({ "resource_major": 88, "resource_minor": 0, "data_major": 107, "data_minor": 1 }),
            5023 => serde_json::json!({ "resource_major": 97, "resource_minor": 1, "data_major": 121, "data_minor": 0 }),
            _ => serde_json::Value::Null,
        };
        let json = serde_json::json!({ "id": version, "name": version, "world_version": data_version, "pack_version": pack, "java_version": 25, "stable": true });
        zip.write_all(json.to_string().as_bytes()).unwrap();
        zip.finish().unwrap();
    }

    /// Adds a profile to profiles.json with its folder (and its version installed when `data_version` is
    /// given); `options` becomes its options.txt.
    pub fn add_profile(state: &AppState, id: &str, version: &str, data_version: Option<u32>, options: Option<&[u8]>) {
        let mut file: ProfilesFile = crate::core::read_json(&state.paths.profiles());
        file.profiles.push(Profile { id: id.into(), name: format!("Profile {id}"), game_version: version.into(), loader: "fabric".into(), ..Default::default() });
        crate::data::save_profiles(state, &file).unwrap();
        let dir = state.paths.profile_dir(id);
        std::fs::create_dir_all(&dir).unwrap();
        if let Some(data) = data_version {
            install_version(&state.paths.game(), version, data);
        }
        if let Some(options) = options {
            std::fs::write(dir.join("options.txt"), options).unwrap();
        }
    }
}
