//! Mods that put capes of their own on players, and the profiles where one is on. A Flint Store cape is the cape in the
//! player's skin (game-runtime `PlayerInfoMixin`, 1.8.9 `ClientPlayerEntityMixin`); these mods set the skin's cape again
//! after Flint (or draw their own over it) whenever their service has a cape for that player, so while one is on it can
//! hide the Flint cape in that profile. Wearing a cape on the Store page asks for them (`cape_mod_conflicts`) and names the mods and profiles in a small notice; the equip
//! itself goes ahead, as the cape is the account's. The game's own copy of the list, for its Cosmetics window, is
//! game-runtime `control/feature/CapeMods.java`; `the_game_has_the_same_list` keeps the two equal.

use crate::core::*;
use crate::data::{load_profiles, Profile, ProfilesFile};
use crate::perfcore::ModJar;
use serde::Serialize;
use std::path::PathBuf;
use tauri::AppHandle;

/// Fabric mods that set a player's cape texture themselves, by the name players know them by and the mod ids their jars
/// declare (`fabric.mod.json` `id`, or a jar bundled in them). Read on 2026-11-06 from each mod's own jar on Modrinth
/// (26.3 builds; MinecraftCapes also its Ornithe 1.8.9 build, Client Capes and Team Capes their 1.21 ones) and its mixins:
/// each replaces the cape in the player's skin (`PlayerInfo.getSkin`, `AbstractClientPlayer.getSkin` or the render state)
/// or draws its own in the cape layer, whenever its service has a cape for that player. OptiFabric's id is from its
/// source (github.com/modmuss50/OptiFabric); OptiFine shows OptiFine capes the same way. Cloaks+ has no mod of its own:
/// the Capes mod shows its capes.
///
/// Not listed, as the Flint cape still shows with them: mods that only animate or restyle the skin's own cape (Wavey Capes,
/// Legacy WaveyCapes, Elytra Physics, Cloth Cloaks, Cape Tweaks, the transparent-cape and jitter fixes), and mods that
/// change the textures Minecraft downloads, which Flint's cape goes on top of (skin overrides, CustomSkinLoader,
/// StraySkins, Fabric Tailor, Cape Command).
pub const CAPE_MODS: &[(&str, &[&str])] = &[
    ("Essential", &["essential", "essential-container"]),
    ("Capes", &["capes"]),
    ("Cape Provider", &["cape-provider"]),
    ("Better Capes", &["bettercapes"]),
    ("Cosmetica", &["cosmetica", "cosmetica-core"]),
    ("MinecraftCapes", &["minecraftcapes"]),
    ("MoreCosmetics", &["morecosmetics"]),
    ("OptiFabric", &["optifabric"]),
    ("Custom Capes", &["ep-custom-capes"]),
    ("OptiFine Capes Reborn", &["optifine_capes_reborn"]),
    ("SkinMC", &["skinmc_mod"]),
    ("Client Side Cosmetics", &["clientsidecosmetics"]),
    ("CapeMod", &["capemod"]),
    ("Team Capes", &["team_capes"]),
    ("Cape Provider X", &["cape-provider-x"]),
    ("Custom Capes", &["capesmod"]),
    ("Client Capes", &["iicapemod"]),
];

/// A profile where a cape mod is on: its name and version, and the mods' names (in `CAPE_MODS` order, each once).
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CapeConflict {
    pub profile_id: String,
    pub profile_name: String,
    pub game_version: String,
    pub mods: Vec<String>,
}

/// The cape mods among the jars Fabric would load (`perfcore::scan_mods`: switched-off `.disabled` jars aren't there),
/// found by id, by `provides` or by a jar bundled in them.
pub fn cape_mods_in(jars: &[ModJar]) -> Vec<String> {
    let mut found: Vec<String> = Vec::new();
    for (name, ids) in CAPE_MODS {
        let on = jars.iter().any(|jar| std::iter::once(&jar.id).chain(&jar.provides).chain(&jar.nested).any(|id| ids.contains(&id.as_str())));
        if on && !found.iter().any(|n| n == name) {
            found.push((*name).to_owned());
        }
    }
    found
}

/// The Fabric profiles with a cape mod on, the selected one first, then the most recently played. Vanilla profiles don't
/// load mods, so they never count.
pub fn conflicts_in(file: &ProfilesFile, mods_dir: impl Fn(&Profile) -> PathBuf) -> Vec<CapeConflict> {
    let mut profiles: Vec<&Profile> = file.profiles.iter().filter(|p| p.loader == "fabric").collect();
    let selected = file.selected.as_deref();
    profiles.sort_by(|a, b| {
        (Some(b.id.as_str()) == selected)
            .cmp(&(Some(a.id.as_str()) == selected))
            .then(b.last_played.unwrap_or(0).cmp(&a.last_played.unwrap_or(0)))
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    profiles
        .into_iter()
        .filter_map(|p| {
            let mods = cape_mods_in(&crate::perfcore::scan_mods(&mods_dir(p)));
            (!mods.is_empty()).then(|| CapeConflict { profile_id: p.id.clone(), profile_name: p.name.clone(), game_version: p.game_version.clone(), mods })
        })
        .collect()
}

pub fn conflicts(state: &AppState) -> Vec<CapeConflict> {
    conflicts_in(&load_profiles(state), |p| state.paths.profile_dir(&p.id).join("mods"))
}

/// The profiles where a mod that shows its own capes is on (none: the Flint cape shows everywhere Flint runs).
#[tauri::command]
pub async fn cape_mod_conflicts(app: AppHandle) -> CmdResult<Vec<CapeConflict>> {
    crate::data::blocking(&app, conflicts).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::perfcore::test_jar;

    fn profile(id: &str, name: &str, loader: &str, last_played: Option<u64>) -> Profile {
        Profile { id: id.into(), name: name.into(), game_version: "1.21.11".into(), loader: loader.into(), last_played, ..Default::default() }
    }

    #[test]
    fn a_cape_mod_counts_only_while_it_is_on() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let survival = profile("survival0001", "Survival 1.21.11", "fabric", Some(100));
        crate::data::save_profiles(&state, &ProfilesFile { selected: None, profiles: vec![survival.clone()] }).unwrap();
        let mods = state.paths.profile_dir(&survival.id).join("mods");
        test_jar(&mods, "sodium.jar", r#"{"id":"sodium","version":"0.9.2"}"#);
        assert!(conflicts(&state).is_empty(), "no cape mod");

        test_jar(&mods, "capes-1.5.10+1.21.11.jar", r#"{"schemaVersion":1,"id":"capes","name":"Capes","environment":"client"}"#);
        let found = conflicts(&state);
        assert_eq!(
            found,
            [CapeConflict { profile_id: "survival0001".into(), profile_name: "Survival 1.21.11".into(), game_version: "1.21.11".into(), mods: vec!["Capes".into()] }]
        );

        // Switched off in the profile's Mods tab: Fabric doesn't load it, so the Flint cape shows.
        std::fs::rename(mods.join("capes-1.5.10+1.21.11.jar"), mods.join("capes-1.5.10+1.21.11.jar.disabled")).unwrap();
        assert!(conflicts(&state).is_empty(), "a disabled mod doesn't count");
    }

    #[test]
    fn mods_that_only_animate_the_cape_never_count() {
        let dir = TestDir::new();
        let mods = dir.0.join("mods");
        // As the real jars declare them (Wavey Capes 1.11.3, Legacy WaveyCapes 1.0.1, Elytra Physics, skin overrides 2.7.1).
        test_jar(&mods, "waveycapes.jar", r#"{"schemaVersion":1,"id":"waveycapes","name":"WaveyCapes","jars":[]}"#);
        test_jar(&mods, "legacy-waveycapes.jar", r#"{"schemaVersion":1,"id":"legacy-waveycapes"}"#);
        test_jar(&mods, "elytra-physics.jar", r#"{"schemaVersion":1,"id":"elytra_physics"}"#);
        test_jar(&mods, "skin-overrides.jar", r#"{"schemaVersion":1,"id":"skin_overrides"}"#);
        test_jar(&mods, "customskinloader.jar", r#"{"schemaVersion":1,"id":"customskinloader-bootstrap"}"#);
        assert!(cape_mods_in(&crate::perfcore::scan_mods(&mods)).is_empty());
    }

    #[test]
    fn a_cape_mod_is_found_by_its_id_what_it_provides_or_a_jar_bundled_in_it() {
        let jar = |id: &str| ModJar { id: id.into(), ..Default::default() };
        assert_eq!(cape_mods_in(&[jar("essential-container")]), ["Essential"], "Essential's mods-folder jar");
        assert_eq!(cape_mods_in(&[ModJar { provides: vec!["capes".into()], ..jar("capes-fork") }]), ["Capes"]);
        assert_eq!(cape_mods_in(&[ModJar { nested: vec!["cosmetica-core".into()], ..jar("some-pack") }]), ["Cosmetica"]);
        // Several, in the list's order, each once (two jars of the same mod, or two mods of one name).
        let several = [jar("minecraftcapes"), jar("capes"), jar("cosmetica"), jar("cosmetica-core"), jar("capesmod"), jar("ep-custom-capes")];
        assert_eq!(cape_mods_in(&several), ["Capes", "Cosmetica", "MinecraftCapes", "Custom Capes"]);
        assert!(cape_mods_in(&[jar("capes-addon"), jar("essentials"), jar("sodium")]).is_empty(), "ids match whole");
    }

    #[test]
    fn every_fabric_profile_is_checked_the_selected_one_first_then_the_latest_played() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let old = profile("old000000001", "Old SMP", "fabric", Some(10));
        let recent = profile("recent000001", "PvP", "fabric", Some(500));
        let chosen = profile("chosen000001", "Survival", "fabric", Some(50));
        let clean = profile("clean0000001", "Clean", "fabric", Some(900));
        let vanilla = profile("vanilla00001", "Vanilla", "vanilla", Some(1000));
        let file = ProfilesFile { selected: Some(chosen.id.clone()), profiles: vec![old.clone(), recent.clone(), chosen.clone(), clean.clone(), vanilla.clone()] };
        crate::data::save_profiles(&state, &file).unwrap();
        let mods = |p: &Profile| state.paths.profile_dir(&p.id).join("mods");
        test_jar(&mods(&old), "essential.jar", r#"{"id":"essential-container","jars":[{"file":"essential-loader.jar"}]}"#);
        test_jar(&mods(&recent), "capes.jar", r#"{"id":"capes"}"#);
        test_jar(&mods(&recent), "mcc.jar", r#"{"id":"minecraftcapes"}"#);
        test_jar(&mods(&chosen), "capes.jar", r#"{"id":"capes"}"#);
        test_jar(&mods(&clean), "waveycapes.jar", r#"{"id":"waveycapes"}"#);
        // A vanilla profile's mods folder isn't loaded.
        test_jar(&mods(&vanilla), "capes.jar", r#"{"id":"capes"}"#);

        let found = conflicts(&state);
        let rows: Vec<(&str, Vec<&str>)> = found.iter().map(|c| (c.profile_name.as_str(), c.mods.iter().map(String::as_str).collect())).collect();
        assert_eq!(rows, [("Survival", vec!["Capes"]), ("PvP", vec!["Capes", "MinecraftCapes"]), ("Old SMP", vec!["Essential"])]);
    }

    #[test]
    fn the_list_is_well_formed() {
        let mut seen = std::collections::HashSet::new();
        for (name, ids) in CAPE_MODS {
            assert!(!name.trim().is_empty() && !ids.is_empty(), "{name}");
            for id in *ids {
                assert!(id.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-' || c == '_'), "{id}: Fabric mod ids are lower case");
                assert!(seen.insert(*id), "{id} is listed twice");
            }
        }
    }

    /// The game's copy (game-runtime `CapeMods.KNOWN`, one `new Known("Name", "id", …)` per line) names the same mods
    /// with the same ids, in the same order.
    #[test]
    fn the_game_has_the_same_list() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../game-runtime/src/client/java/com/flintclient/client/control/feature/CapeMods.java");
        let java = std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
        let block = java.split("KNOWN = List.of(").nth(1).and_then(|rest| rest.split(");").next()).expect("CapeMods.KNOWN = List.of(…);");
        let game: Vec<Vec<String>> = block
            .lines()
            .map(str::trim)
            .filter(|line| line.starts_with("new Known("))
            .map(|line| line.split('"').skip(1).step_by(2).map(str::to_owned).collect())
            .collect();
        let launcher: Vec<Vec<String>> = CAPE_MODS.iter().map(|(name, ids)| std::iter::once(*name).chain(ids.iter().copied()).map(str::to_owned).collect()).collect();
        assert_eq!(game, launcher, "game-runtime CapeMods.KNOWN and CAPE_MODS differ: change both");
    }
}
