//! Each profile's folder (under `profiles/`) is named after it: "Daemon-26.2", or "PvP 1.8.9" when the name already
//! says the version, and "Minecraft 26.2" when nothing of the name can go in a folder name. Profiles from before 0.5.5
//! are in folders named by their id; start-up moves them, and a folder follows its profile when it is renamed.
//!
//! A folder only moves while nothing uses it: no game (Flint's, or one that outlived a restart), no install or job
//! for that profile, no Auto Sync pass. Otherwise it stays where it is and moves at a later start or after the game.
//! A move is written to a journal first, so a crash between the move and saving profiles.json is undone at start-up.

use crate::core::{write_json, AppState};
use crate::data::{load_profiles, save_profiles, Profile, ProfilesFile};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::path::PathBuf;

/// The move in progress (in Flint's data folder, next to profiles.json).
const JOURNAL: &str = "profile-folder-move.json";
/// Folders `jobs::clean_partials` deletes at start-up: a profile's name must never end so.
const IMPORTING: &str = ".flint-importing";

#[derive(Serialize, Deserialize)]
struct Move {
    id: String,
    from: String,
    to: String,
}

/// The folder a profile named `name` on Minecraft `version` asks for (before another profile's taking it adds " (2)").
pub fn wanted(name: &str, version: &str) -> String {
    let version = clean(version);
    let name = clean(name);
    let base = if name.is_empty() {
        format!("Minecraft {version}").trim().to_owned()
    } else if version.is_empty() || says_version(&name, &version) {
        name
    } else {
        format!("{name}-{version}")
    };
    // A name Windows reserves, or one ending like Flint's own temporary names, gets a word that makes it plainly a profile.
    let lower = base.to_ascii_lowercase();
    if reserved(&base) {
        format!("Profile {base}")
    } else if [IMPORTING, crate::jobs::PART_SUFFIX, crate::core::TMP_SUFFIX].iter().any(|s| lower.ends_with(s)) {
        format!("{base} profile")
    } else {
        base
    }
}

/// What of `text` a folder name can hold on every system Flint runs on, and on the Java 8 1.8.9 runs on (its command
/// line is read in the system's code page, so letters outside it would break `--gameDir`): ASCII letters, digits, spaces
/// and a few marks; accented Latin letters lose their accents; anything else is a space. No dots or spaces at either end.
fn clean(text: &str) -> String {
    let mut out = String::new();
    for c in text.chars() {
        let c = fold(c);
        if c.is_ascii_alphanumeric() || " -_.()+!,'&#@[]".contains(c) {
            out.push(c);
        } else {
            out.push(' ');
        }
    }
    let joined = out.split_whitespace().collect::<Vec<_>>().join(" ");
    let trimmed = joined.trim_matches(|c: char| c == '.' || c == ' ');
    trimmed.chars().take(48).collect::<String>().trim_end_matches(['.', ' ']).to_owned()
}

/// A Latin letter without its accent ("é" → "e"); other characters unchanged.
fn fold(c: char) -> char {
    const FROM: &str = "ÀÁÂÃÄÅàáâãäåÇçÈÉÊËèéêëÌÍÎÏìíîïÑñÒÓÔÕÖØòóôõöøÙÚÛÜùúûüÝýÿŠšŽžŒœ";
    const TO: &str = "AAAAAAaaaaaaCcEEEEeeeeIIIIiiiiNnOOOOOOooooooUUUUuuuuYyySsZzOo";
    FROM.chars().position(|f| f == c).and_then(|i| TO.chars().nth(i)).unwrap_or(c)
}

/// Whether `name` holds `version` as a whole ("PvP 1.8.9" does; "1.21.11" doesn't hold 1.21.1).
fn says_version(name: &str, version: &str) -> bool {
    let (name, version) = (name.to_ascii_lowercase(), version.to_ascii_lowercase());
    name.match_indices(&version).any(|(at, _)| {
        let part = |c: Option<char>| c.is_none_or(|c| !c.is_ascii_digit() && c != '.');
        part(name[..at].chars().next_back()) && part(name[at + version.len()..].chars().next())
    })
}

/// Names Windows keeps for devices (CON, NUL, COM1…), which it refuses as folder names, with or without an extension.
fn reserved(name: &str) -> bool {
    let stem = name.split('.').next().unwrap_or("").trim_end().to_ascii_uppercase();
    matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || (stem.len() == 4 && (stem.starts_with("COM") || stem.starts_with("LPT")) && stem.as_bytes()[3].is_ascii_digit())
}

/// `base`, or "base (2)", "base (3)"… the first that isn't `taken`.
fn unique(base: &str, taken: impl Fn(&str) -> bool) -> String {
    if !taken(base) {
        return base.to_owned();
    }
    (2..).map(|n| format!("{base} ({n})")).find(|name| !taken(name)).expect("an unused number")
}

/// The folder a profile is in now: the one recorded, else its id (profiles from before 0.5.5).
pub fn current(profile: &Profile) -> &str {
    profile.folder.as_deref().unwrap_or(&profile.id)
}

fn profiles_dir(state: &AppState) -> PathBuf {
    state.paths.root.join("profiles")
}

/// The name profile `id` should have: what its name asks for, made unique against the other profiles' folders and
/// against anything else on disk (a removed profile's kept folder), its own folder `own` excepted.
fn free(state: &AppState, file: &ProfilesFile, id: &str, own: Option<&str>, base: &str) -> String {
    let others: HashSet<String> = file.profiles.iter().filter(|p| p.id != id).map(|p| current(p).to_lowercase()).collect();
    let dir = profiles_dir(state);
    unique(base, |name| {
        let lower = name.to_lowercase();
        others.contains(&lower) || (own.is_none_or(|own| own.to_lowercase() != lower) && dir.join(name).symlink_metadata().is_ok())
    })
}

/// Names a new profile's folder (call with the profiles lock held and `file` loaded, before the folder is made).
pub fn assign(state: &AppState, file: &ProfilesFile, profile: &mut Profile) {
    let folder = free(state, file, &profile.id, None, &wanted(&profile.name, &profile.game_version));
    state.paths.set_folder(&profile.id, &folder);
    profile.folder = Some(folder);
}

/// Why profile `id`'s folder can't move now, or None.
fn busy(state: &AppState, id: &str) -> Option<String> {
    let scan = crate::core::ProcessScan::take();
    if let Some(why) = crate::core::profile_running(state, id, &scan) {
        return Some(why);
    }
    if state.jobs.active_for(id) {
        return Some("a download for it is running".into());
    }
    crate::datadir::game_using(&state.paths.profile_dir(id))
}

/// At start-up, before anything uses a profile's folder: undoes or completes a move a crash interrupted, tells `Paths`
/// where every profile is, and moves the folders whose names don't match their profiles (all of them, the first time).
pub fn settle_all(state: &AppState) {
    let mut file = load_profiles(state);
    recover(state, &mut file);
    state.paths.set_folders(&file.profiles);
    let ids: Vec<String> = file.profiles.iter().map(|p| p.id.clone()).collect();
    for id in ids {
        settle(state, &mut file, &id);
    }
}

/// Moves profile `id`'s folder to the name it asks for, if it differs and nothing is using it (its profile was
/// renamed, or its game was running then). Returns the profile as saved.
pub async fn follow(state: &AppState, id: &str) -> Option<Profile> {
    let _guard = state.file_lock.lock().await;
    let mut file = load_profiles(state);
    settle(state, &mut file, id);
    file.profiles.into_iter().find(|p| p.id == id)
}

fn settle(state: &AppState, file: &mut ProfilesFile, id: &str) {
    let Some(profile) = file.profiles.iter().find(|p| p.id == id) else { return };
    let from = current(profile).to_owned();
    let to = free(state, file, id, Some(&from), &wanted(&profile.name, &profile.game_version));
    if to == from {
        return;
    }
    let source = profiles_dir(state).join(&from);
    if source.symlink_metadata().is_err() {
        // No folder yet (a game makes it on its first launch): only the name is recorded.
        let _ = record(state, file, id, &to);
        return;
    }
    // No Auto Sync pass reads or writes profiles while it moves.
    let _quiet = crate::gamesettings::autosync::hold();
    if let Some(why) = busy(state, id) {
        log(&format!("{from} stays for now: {why}"));
        return;
    }
    let dir = profiles_dir(state);
    let journal = state.paths.root.join(JOURNAL);
    if write_json(&journal, &Move { id: id.into(), from: from.clone(), to: to.clone() }).is_err() {
        return;
    }
    if let Err(e) = std::fs::rename(dir.join(&from), dir.join(&to)) {
        // Windows refuses while a program holds a file in it: it stays, and moves at a later start.
        log(&format!("{from} stays for now: {e}"));
    } else if record(state, file, id, &to).is_err() {
        let _ = std::fs::rename(dir.join(&to), dir.join(&from));
    } else {
        log(&format!("{from} → {to}"));
    }
    let _ = std::fs::remove_file(&journal);
}

/// Saves profile `id` as being in folder `to`; on failure, nothing changes.
fn record(state: &AppState, file: &mut ProfilesFile, id: &str, to: &str) -> Result<(), String> {
    let profile = file.profiles.iter_mut().find(|p| p.id == id).ok_or("gone")?;
    let before = profile.folder.replace(to.to_owned());
    if let Err(e) = save_profiles(state, file) {
        if let Some(p) = file.profiles.iter_mut().find(|p| p.id == id) {
            p.folder = before;
        }
        return Err(e);
    }
    state.paths.set_folder(id, to);
    Ok(())
}

/// A move that a crash interrupted: profiles.json keeps whichever of its two folders is there.
fn recover(state: &AppState, file: &mut ProfilesFile) {
    let journal = state.paths.root.join(JOURNAL);
    if !journal.exists() {
        return;
    }
    let moved: Option<Move> = std::fs::read(&journal).ok().and_then(|b| serde_json::from_slice(&b).ok());
    if let Some(m) = moved {
        let dir = profiles_dir(state);
        let at = if dir.join(&m.to).exists() && !dir.join(&m.from).exists() { m.to } else { m.from };
        if let Some(p) = file.profiles.iter_mut().find(|p| p.id == m.id) {
            if current(p) != at {
                p.folder = Some(at);
                if save_profiles(state, file).is_err() {
                    return;
                }
            }
        }
    }
    let _ = std::fs::remove_file(&journal);
}

fn log(line: &str) {
    eprintln!("[flint] profile folders: {line}");
}


#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::{RunningGame, TestDir};

    fn profile(id: &str, name: &str, version: &str) -> Profile {
        Profile { id: id.into(), name: name.into(), game_version: version.into(), loader: "fabric".into(), ..Default::default() }
    }

    /// A data folder holding `profiles`, each with a world in a folder named by its id (as before 0.5.5).
    fn data(profiles: Vec<Profile>) -> (TestDir, AppState) {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        for p in &profiles {
            let world = state.paths.root.join("profiles").join(&p.id).join("saves").join("World");
            std::fs::create_dir_all(&world).unwrap();
            std::fs::write(world.join("level.dat"), &p.id).unwrap();
        }
        save_profiles(&state, &ProfilesFile { selected: None, profiles }).unwrap();
        (dir, state)
    }

    fn folders(state: &AppState) -> Vec<String> {
        let mut names: Vec<String> = std::fs::read_dir(state.paths.root.join("profiles")).unwrap().flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
        names.sort();
        names
    }

    fn world(state: &AppState, id: &str) -> String {
        std::fs::read_to_string(state.paths.profile_dir(id).join("saves/World/level.dat")).unwrap()
    }

    #[test]
    fn folders_are_named_after_the_profile_and_its_version() {
        assert_eq!(wanted("Daemon", "26.2"), "Daemon-26.2");
        assert_eq!(wanted("  My  PvP ", "1.21.11"), "My PvP-1.21.11");
        assert_eq!(wanted("PvP 1.8.9", "1.8.9"), "PvP 1.8.9", "the name says the version already");
        assert_eq!(wanted("26.3", "26.3"), "26.3");
        assert_eq!(wanted("1.21.11 build", "1.21.1"), "1.21.11 build-1.21.1", "1.21.11 isn't 1.21.1");
        assert_eq!(wanted("Café Ünïcode", "26.3"), "Cafe Unicode-26.3");
        assert_eq!(wanted("a/b:c*?\"<>|d", "26.3"), "a b c d-26.3");
        assert_eq!(wanted("✨🔥", "26.3"), "Minecraft 26.3", "nothing usable: the version");
        assert_eq!(wanted("...", "1.8.9"), "Minecraft 1.8.9");
        assert_eq!(wanted(".hidden.", "26.3"), "hidden-26.3", "no dot at either end");
        assert_eq!(wanted("nul.1.8.9", "1.8.9"), "Profile nul.1.8.9-1.8.9", "Windows keeps NUL for a device");
        assert_eq!(wanted("COM1", "26.3"), "COM1-26.3");
        assert_eq!(wanted("1.8.9 x.flint-importing", "1.8.9"), "1.8.9 x.flint-importing profile", "never named like a staging folder");
        assert_eq!(wanted("26.3 Hardcore.flint-part", "26.3"), "26.3 Hardcore.flint-part profile");
        assert_eq!(wanted("x.FLINT-TMP", "26.3"), "x.FLINT-TMP-26.3", "the version already changes the ending");
        assert!(wanted(&"x".repeat(40), "1.21.11").len() <= 48 + 8);
    }

    #[test]
    fn start_up_moves_old_folders_and_keeps_everything_in_them() {
        let (_dir, state) = data(vec![profile("aaaaaaaaaaaa", "Daemon", "26.2"), profile("bbbbbbbbbbbb", "Daemon", "26.2"), profile("cccccccccccc", "PvP 1.8.9", "1.8.9")]);
        settle_all(&state);
        assert_eq!(folders(&state), ["Daemon-26.2", "Daemon-26.2 (2)", "PvP 1.8.9"]);
        assert_eq!(world(&state, "aaaaaaaaaaaa"), "aaaaaaaaaaaa");
        assert_eq!(world(&state, "bbbbbbbbbbbb"), "bbbbbbbbbbbb");
        assert_eq!(world(&state, "cccccccccccc"), "cccccccccccc");
        // Saved: a restart finds them where they are and moves nothing.
        let fresh = AppState::for_tests(state.paths.root.clone());
        settle_all(&fresh);
        assert_eq!(world(&fresh, "bbbbbbbbbbbb"), "bbbbbbbbbbbb");
        assert_eq!(load_profiles(&fresh).profiles[1].folder.as_deref(), Some("Daemon-26.2 (2)"));
        assert_eq!(folders(&fresh).len(), 3);
    }

    #[test]
    fn a_kept_folder_of_a_removed_profile_is_never_taken() {
        let (_dir, state) = data(vec![profile("aaaaaaaaaaaa", "Daemon", "26.2")]);
        std::fs::create_dir_all(state.paths.root.join("profiles/daemon-26.2/saves")).unwrap();
        settle_all(&state);
        assert_eq!(state.paths.profile_dir("aaaaaaaaaaaa"), state.paths.root.join("profiles/Daemon-26.2 (2)"));
        assert_eq!(world(&state, "aaaaaaaaaaaa"), "aaaaaaaaaaaa");
    }

    #[test]
    fn a_folder_in_use_stays_until_it_is_free() {
        let (_dir, state) = data(vec![profile("aaaaaaaaaaaa", "Daemon", "26.2")]);
        state.running.lock().unwrap().insert("aaaaaaaaaaaa".into(), RunningGame { kill: None });
        settle_all(&state);
        assert_eq!(folders(&state), ["aaaaaaaaaaaa"], "a game is running in it");
        assert_eq!(world(&state, "aaaaaaaaaaaa"), "aaaaaaaaaaaa");
        state.running.lock().unwrap().clear();
        let rt = tokio::runtime::Builder::new_current_thread().build().unwrap();
        let moved = rt.block_on(follow(&state, "aaaaaaaaaaaa")).unwrap();
        assert_eq!(moved.folder.as_deref(), Some("Daemon-26.2"));
        assert_eq!(folders(&state), ["Daemon-26.2"]);
    }

    #[test]
    fn renaming_a_profile_renames_its_folder() {
        let (_dir, state) = data(vec![profile("aaaaaaaaaaaa", "Daemon", "26.2")]);
        settle_all(&state);
        let rt = tokio::runtime::Builder::new_current_thread().build().unwrap();
        rt.block_on(crate::data::update_profile(&state, "aaaaaaaaaaaa", |p| p.name = "Bedwars".into())).unwrap();
        rt.block_on(follow(&state, "aaaaaaaaaaaa"));
        assert_eq!(folders(&state), ["Bedwars-26.2"]);
        assert_eq!(world(&state, "aaaaaaaaaaaa"), "aaaaaaaaaaaa");
        // Only its letters' case changes.
        rt.block_on(crate::data::update_profile(&state, "aaaaaaaaaaaa", |p| p.name = "BEDWARS".into())).unwrap();
        rt.block_on(follow(&state, "aaaaaaaaaaaa"));
        assert_eq!(folders(&state), ["BEDWARS-26.2"]);
    }

    #[test]
    fn a_new_profile_gets_a_free_name() {
        let (_dir, state) = data(vec![profile("aaaaaaaaaaaa", "Daemon", "26.2")]);
        settle_all(&state);
        let file = load_profiles(&state);
        let mut new = profile("bbbbbbbbbbbb", "daemon", "26.2");
        assign(&state, &file, &mut new);
        assert_eq!(new.folder.as_deref(), Some("daemon-26.2 (2)"));
        assert_eq!(state.paths.profile_dir("bbbbbbbbbbbb"), state.paths.root.join("profiles/daemon-26.2 (2)"));
    }

    #[test]
    fn a_move_a_crash_interrupted_is_finished() {
        let (_dir, state) = data(vec![profile("aaaaaaaaaaaa", "Daemon", "26.2")]);
        // The folder moved, profiles.json wasn't saved yet.
        let profiles = state.paths.root.join("profiles");
        std::fs::rename(profiles.join("aaaaaaaaaaaa"), profiles.join("Daemon-26.2")).unwrap();
        write_json(&state.paths.root.join(JOURNAL), &Move { id: "aaaaaaaaaaaa".into(), from: "aaaaaaaaaaaa".into(), to: "Daemon-26.2".into() }).unwrap();
        settle_all(&state);
        assert_eq!(world(&state, "aaaaaaaaaaaa"), "aaaaaaaaaaaa");
        assert_eq!(folders(&state), ["Daemon-26.2"]);
        assert!(!state.paths.root.join(JOURNAL).exists());
    }

    #[test]
    fn profiles_without_a_folder_yet_only_get_a_name() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        save_profiles(&state, &ProfilesFile { selected: None, profiles: vec![profile("aaaaaaaaaaaa", "Daemon", "26.2")] }).unwrap();
        settle_all(&state);
        assert_eq!(load_profiles(&state).profiles[0].folder.as_deref(), Some("Daemon-26.2"));
        assert!(!state.paths.root.join("profiles").exists());
    }
}
