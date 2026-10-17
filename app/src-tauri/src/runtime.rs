//! Flint's in-game runtime (`game-runtime/`): a small Fabric mod whose first feature is Flint's own
//! main menu. The release build compiles it and this app embeds the jar; at launch it is copied into
//! the profile's `mods` folder for supported profiles (Fabric on a Minecraft version it was built and
//! tested for) and removed everywhere else, so Fabric never sees it on a version it can't load on.
//!
//! The file is Flint's own: it never appears in the profile's content list, and Settings → "Flint main
//! menu in Minecraft" decides whether it is installed. Wherever it is installed, each launch also
//! writes its config (`config/flint-performance.json`, see perfcore.rs) beside it.

use crate::core::*;
use crate::data::Profile;
use crate::perfcore::LaunchTuning;
use std::path::Path;

/// The jar as built by `game-runtime/` (empty when the app was compiled without it).
static JAR: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/flint-runtime.jar"));

/// The runtime's file in a profile's `mods` folder.
pub const FILE_NAME: &str = "flint-runtime.jar";

/// Minecraft versions the runtime is built against and tested on (its fabric.mod.json requires ~26.3).
const SUPPORTED_VERSIONS: &[&str] = &["26.3"];

pub fn bundled() -> bool {
    !JAR.is_empty()
}

pub fn supports(profile: &Profile) -> bool {
    profile.loader == "fabric" && SUPPORTED_VERSIONS.contains(&profile.game_version.as_str())
}

/// Settings → "Flint main menu in Minecraft" (on unless turned off).
pub fn enabled(state: &AppState) -> bool {
    crate::data::load_settings(state)["inGameMenu"].as_bool().unwrap_or(true)
}

/// Installs, updates or removes the runtime so the profile matches the setting and its version, and
/// with it this launch's config for the runtime.
pub fn sync(state: &AppState, profile: &Profile, tuning: &LaunchTuning) -> CmdResult<()> {
    let wanted = bundled() && enabled(state) && supports(profile);
    sync_profile(state, profile, if wanted { Some(JAR) } else { None }, tuning)
}

fn sync_profile(state: &AppState, profile: &Profile, jar: Option<&[u8]>, tuning: &LaunchTuning) -> CmdResult<()> {
    let game_dir = state.paths.profile_dir(&profile.id);
    sync_dir(&game_dir.join("mods"), jar)?;
    let config = jar.map(|_| crate::perfcore::runtime_config(profile, tuning, crate::perfcore::hardware(), crate::perfcore::frame_stats_log_requested()));
    crate::perfcore::sync_runtime_config(&game_dir, config.as_ref())
}

fn sync_dir(mods: &Path, jar: Option<&[u8]>) -> CmdResult<()> {
    let target = mods.join(FILE_NAME);
    let Some(jar) = jar else {
        for stale in [target.clone(), mods.join(format!("{FILE_NAME}.disabled"))] {
            if stale.exists() {
                std::fs::remove_file(&stale).map_err(|e| format!("Couldn't remove Flint's in-game menu from the profile: {e}"))?;
            }
        }
        return Ok(());
    };
    if std::fs::read(&target).is_ok_and(|current| current == jar) {
        return Ok(());
    }
    std::fs::create_dir_all(mods).map_err(|e| format!("Couldn't prepare the mods folder: {e}"))?;
    // Written beside the target and renamed into place, so the game never sees a half-written jar.
    let staging = mods.join(format!(".{FILE_NAME}.part"));
    std::fs::write(&staging, jar).map_err(|e| format!("Couldn't install Flint's in-game menu: {e}"))?;
    std::fs::rename(&staging, &target).map_err(|e| format!("Couldn't install Flint's in-game menu: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn profile(version: &str, loader: &str) -> Profile {
        serde_json::from_value(serde_json::json!({ "id": "p", "name": "P", "gameVersion": version, "loader": loader })).unwrap()
    }

    #[test]
    fn only_fabric_on_a_tested_version_gets_the_runtime() {
        assert!(supports(&profile("26.3", "fabric")));
        assert!(!supports(&profile("26.3", "vanilla")));
        assert!(!supports(&profile("26.2", "fabric")));
        assert!(!supports(&profile("1.21.11", "fabric")));
    }

    #[test]
    fn the_jar_is_installed_updated_and_removed() {
        let dir = std::env::temp_dir().join(format!("flint-runtime-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let target = dir.join(FILE_NAME);

        sync_dir(&dir, Some(b"one")).unwrap();
        assert_eq!(std::fs::read(&target).unwrap(), b"one");
        let first = std::fs::metadata(&target).unwrap().modified().unwrap();

        // Unchanged: not rewritten.
        sync_dir(&dir, Some(b"one")).unwrap();
        assert_eq!(std::fs::metadata(&target).unwrap().modified().unwrap(), first);

        sync_dir(&dir, Some(b"two")).unwrap();
        assert_eq!(std::fs::read(&target).unwrap(), b"two");
        assert!(!dir.join(format!(".{FILE_NAME}.part")).exists());

        // Turned off (or an unsupported version): removed, along with a disabled copy.
        std::fs::write(dir.join(format!("{FILE_NAME}.disabled")), b"x").unwrap();
        sync_dir(&dir, None).unwrap();
        assert!(!target.exists());
        assert!(!dir.join(format!("{FILE_NAME}.disabled")).exists());
        sync_dir(&dir, None).unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_config_follows_the_runtime() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let profile = profile("26.3", "fabric");
        let game_dir = state.paths.profile_dir(&profile.id);
        let config = game_dir.join("config").join(crate::perfcore::CONFIG_FILE);
        let tuning = LaunchTuning { automatic: false, policy: crate::perfcore::JvmPolicy::Legacy, heap_max_mb: 3072 };

        sync_profile(&state, &profile, Some(b"jar"), &tuning).unwrap();
        assert!(game_dir.join("mods").join(FILE_NAME).exists());
        let written: serde_json::Value = read_json_opt(&config).expect("written with the runtime");
        assert_eq!(written["schema"], 1);
        assert_eq!(written["automaticOptimization"], false);
        assert_eq!(written["profile"], serde_json::json!({ "gameVersion": "26.3", "loader": "fabric" }));
        assert_eq!(written["jvm"], serde_json::json!({ "policy": "legacy", "heapMaxMb": 3072 }));
        assert_eq!(written["hardware"]["os"], std::env::consts::OS);
        assert_eq!(written["modules"], serde_json::json!({}));

        sync_profile(&state, &profile, None, &tuning).unwrap();
        assert!(!game_dir.join("mods").join(FILE_NAME).exists());
        assert!(!config.exists(), "removed with the runtime");
    }
}
