//! Flint's in-game runtime (`game-runtime/`): a small Fabric mod whose first feature is Flint's own
//! main menu. The release build compiles it once per Minecraft line and this app embeds the jars, packed together; at
//! launch the jar for the profile's version is unpacked and copied into the profile's `mods` folder for supported
//! profiles (Fabric on a Minecraft version it was built and tested for) and removed everywhere else,
//! so Fabric never sees a runtime on a version it can't load on.
//!
//! The file is Flint's own: it never appears in the profile's content list, and Settings → "Flint main
//! menu in Minecraft" decides whether it is installed. Wherever it is installed, each launch also
//! writes its config (`config/flint-performance.json`, see perfcore.rs) beside it.

use crate::core::*;
use crate::data::Profile;
use crate::perfcore::LaunchTuning;
use std::path::Path;
use std::sync::OnceLock;

/// The jars built by `game-runtime/`, one per Minecraft line (build.rs's RUNTIME_LINES), each with its entries stored
/// uncompressed and packed by build.rs into one "large window" brotli stream: the lines share most of their classes, so
/// the seven jars take about a sixth of the size of Gradle's jars in the app.
static PACKED: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/flint-runtimes.br"));

/// What [PACKED] holds, in stream order: each line, its jar's size (0 when the app was compiled without it) and SHA-256.
const PACKED_LINES: &[(&str, usize, [u8; 32])] = include!(concat!(env!("OUT_DIR"), "/flint-runtimes.rs"));

/// Each line's jar, unpacked the first time a launch needs it and kept for the rest of this run (in practice the one or
/// two lines a player starts, about 10 MB each).
static UNPACKED: [OnceLock<&'static [u8]>; PACKED_LINES.len()] = [const { OnceLock::new() }; PACKED_LINES.len()];

/// The jar for a line; empty when the app was compiled without it.
fn embedded(line: &str) -> &'static [u8] {
    let Some(index) = PACKED_LINES.iter().position(|(l, _, _)| *l == line) else {
        return &[];
    };
    UNPACKED[index].get_or_init(|| {
        // Checked when the app was built (build.rs unpacks the stream) and by the tests; a copy of Flint damaged on disk
        // is the only way here, and that line then counts as missing (the launch stops with a message).
        unpack(index).unwrap_or_else(|error| {
            eprintln!("[flint] the in-game runtime for Minecraft {line} didn't unpack: {error}");
            &[]
        })
    })
}

/// Reads [PACKED] up to the end of the line's jar (the newest lines are first: a few hundredths of a second, all seven
/// lines about two tenths) and checks the jar against the SHA-256 build.rs recorded. The decoder takes the stream's large
/// window; its buffer grows with what it has read, to 32 MiB at most, and is freed once the jar is out.
fn unpack(index: usize) -> Result<&'static [u8], String> {
    use sha2::Digest;
    use std::io::Read;
    let (_, size, sha256) = PACKED_LINES[index];
    let before: usize = PACKED_LINES[..index].iter().map(|(_, size, _)| size).sum();
    let mut stream = brotli::Decompressor::new(PACKED, 64 * 1024);
    let skipped = std::io::copy(&mut (&mut stream).take(before as u64), &mut std::io::sink()).map_err(|e| e.to_string())?;
    if skipped != before as u64 {
        return Err("the packed jars end early".into());
    }
    let mut jar = vec![0; size];
    stream.read_exact(&mut jar).map_err(|e| e.to_string())?;
    if sha2::Sha256::digest(&jar)[..] != sha256[..] {
        return Err("the jar doesn't match the one the app was built with".into());
    }
    Ok(Box::leak(jar.into_boxed_slice()))
}

/// The runtime's file in a profile's `mods` folder (whichever line's jar it is).
pub const FILE_NAME: &str = "flint-runtime.jar";

/// Minecraft versions the runtime is built against and tested on, with the line whose jar they get
/// (that jar's fabric.mod.json requires ~line on 26.x, and exactly the versions listed here on 1.21.x). One
/// 26.1 jar serves every 26.1 release. A release newer than all of them gets the newest line (see [NEWEST_LINE]).
const SUPPORTED_VERSIONS: &[(&str, &str)] = &[
    ("26.3", "26.3"),
    ("26.2", "26.2"),
    ("26.1.2", "26.1"),
    ("26.1.1", "26.1"),
    ("26.1", "26.1"),
    ("1.21.11", "1.21.11"),
    ("1.21.10", "1.21.10"),
    ("1.21.1", "1.21.1"),
    // Its own code base (game-runtime/legacy189), on Fabric from Ornithe.
    ("1.8.9", "1.8.9"),
];

/// The newest line. Its jar also takes Minecraft releases Flint has no build for yet (a 26.4 added to the launcher
/// before Flint ships a 26.4 runtime): its fabric.mod.json requires >=line, and on a version it wasn't built for the
/// runtime switches off whatever no longer fits Minecraft instead of stopping the game.
const NEWEST_LINE: &str = "26.3";

pub fn supports(profile: &Profile) -> bool {
    profile.loader == "fabric" && line(&profile.game_version).is_some()
}

/// The Minecraft line whose jar a game version gets, if the runtime supports that version.
fn line(game_version: &str) -> Option<&'static str> {
    if let Some((_, line)) = SUPPORTED_VERSIONS.iter().find(|(version, _)| *version == game_version) {
        return Some(line);
    }
    match (release(game_version), release(NEWEST_LINE)) {
        (Some(version), Some(newest)) if version > newest => Some(NEWEST_LINE),
        _ => None,
    }
}

/// A release version's numbers ("26.4" -> [26, 4]); None for a snapshot, pre-release or anything else.
fn release(version: &str) -> Option<Vec<u32>> {
    version.split('.').map(|part| part.parse().ok()).collect()
}

/// The jar for this profile, when the app was built with one for its version.
#[cfg(test)]
fn jar_for(profile: &Profile) -> Option<&'static [u8]> {
    match wanted(true, profile, embedded) {
        Want::Runtime(jar) => Some(jar),
        _ => None,
    }
}

/// What a launch must find in the profile's mods folder.
#[derive(Debug, PartialEq)]
enum Want<'a> {
    /// This jar, written whole.
    Runtime(&'a [u8]),
    /// No runtime (the setting is off, or the profile isn't on a version the runtime supports).
    Nothing,
    /// The runtime belongs in this profile, but this copy of Flint was built without its line's jar.
    NotBuilt(&'static str),
}

/// `jar` gives a line's jar (empty when this copy of Flint has none); it is only asked for the line the profile needs.
fn wanted<'a>(enabled: bool, profile: &Profile, jar: impl FnOnce(&'static str) -> &'a [u8]) -> Want<'a> {
    let line = match line(&profile.game_version) {
        Some(line) if enabled && supports(profile) => line,
        _ => return Want::Nothing,
    };
    match jar(line) {
        [] => Want::NotBuilt(line),
        jar => Want::Runtime(jar),
    }
}

fn not_built(line: &str) -> String {
    format!(
        "This copy of Flint was built without its in-game menu for Minecraft {line}, so the game wasn't started without it. Turn off “Flint main menu in Minecraft” in Settings → Minecraft to play this profile without it."
    )
}

/// Settings → "Flint main menu in Minecraft" (on unless turned off).
pub fn enabled(state: &AppState) -> bool {
    crate::data::load_settings(state)["inGameMenu"].as_bool().unwrap_or(true)
}

/// Lines that run on a newer Java than their Minecraft version asks for, with Flint: (line, Mojang's Java runtime
/// component, its major version, the oldest Java the line can run on, which a custom Java in Settings must be, and the
/// component used where Mojang has no build of the first for the computer).
///
/// Every version runs on the Java the official Minecraft Launcher gives it (the owner's call on 2026-11-03, after crashes
/// on Java 25): 26.x on Mojang's Java 25, 1.21.x on Mojang's Java 21 (its own; no entry here). The one exception is 1.8.9
/// with Flint: Flint's 1.8.9 runtime shares Flint's Java 21 code (docs/LEGACY189_SHARED_MENU_PLAN.md), so it runs on
/// Mojang's Java 21 (`java-runtime-delta`) instead of its own Java 8; vanilla 1.8.9, and 1.8.9 with the in-game menu
/// turned off, keep Mojang's Java 8.
///
/// Until then 1.21.x and 1.8.9 ran on Java 25, which reached the title screen sooner (1.21.11: 10.4-10.6 s on Java 21,
/// 7.0-7.9 s on Java 25), but Java 25 is where the random crashes were.
const JAVA_FOR_LINE: &[(&str, &str, u64, u64, &str)] = &[("1.8.9", "java-runtime-delta", 21, 21, "java-runtime-delta")];

/// The Java a launch runs on instead of its version file's, if any: only when the profile gets Flint's runtime.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct JavaChoice {
    /// Mojang's runtime component.
    pub component: &'static str,
    /// Its major version.
    pub major: u64,
    /// The oldest Java the line runs on (a custom Java in Settings must be at least this).
    pub minimum: u64,
    /// The component used where Mojang has no build of `component` for this computer.
    pub fallback: &'static str,
}

pub fn java_override(enabled: bool, profile: &Profile) -> Option<JavaChoice> {
    let line = line(&profile.game_version).filter(|_| enabled && supports(profile))?;
    JAVA_FOR_LINE
        .iter()
        .find(|(l, _, _, _, _)| *l == line)
        .map(|(_, component, major, minimum, fallback)| JavaChoice { component, major: *major, minimum: *minimum, fallback })
}

/// Installs, updates or removes the runtime so the profile matches the setting and its version, and
/// with it this launch's config for the runtime. A profile that should have the runtime never starts without it: a build that lacks the line's jar is an
/// error rather than a silent launch to Minecraft's own menu.
pub fn sync(state: &AppState, profile: &Profile, tuning: &LaunchTuning) -> CmdResult<()> {
    match wanted(enabled(state), profile, embedded) {
        Want::Runtime(jar) => sync_profile(state, profile, Some(jar), tuning),
        Want::Nothing => sync_profile(state, profile, None, tuning),
        Want::NotBuilt(line) => Err(not_built(line)),
    }
}

/// Does ahead, when Flint opens, what the session's first Play of this profile would otherwise wait for: its line's jar
/// unpacked from the app (a tenth to a quarter of a second) and the copy in the profile's mods folder read and compared
/// with it once (`in_place`). Only reads; Play still checks and writes as before.
pub fn warm(state: &AppState, profile: &Profile) {
    if let Want::Runtime(jar) = wanted(enabled(state), profile, embedded) {
        let _ = in_place(&state.paths.profile_dir(&profile.id).join("mods").join(FILE_NAME), jar);
    }
}

/// The last check before the game starts (after everything else that touches the profile's files): the
/// runtime is in the mods folder, whole and the right line's, exactly when it should be.
pub fn verify(state: &AppState, profile: &Profile) -> CmdResult<()> {
    verify_dir(&state.paths.profile_dir(&profile.id).join("mods"), wanted(enabled(state), profile, embedded))
}

fn verify_dir(mods: &Path, want: Want<'_>) -> CmdResult<()> {
    let target = mods.join(FILE_NAME);
    match want {
        Want::Runtime(jar) if in_place(&target, jar) => Ok(()),
        Want::Runtime(_) => Err("Flint's in-game menu couldn't be put in place in this profile's mods folder, so the game wasn't started. Try again.".into()),
        Want::Nothing if target.exists() => Err("An old copy of Flint's in-game menu is still in this profile's mods folder and couldn't be removed. Close anything using the profile's files and try again.".into()),
        Want::Nothing => Ok(()),
        Want::NotBuilt(line) => Err(not_built(line)),
    }
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
    if in_place(&target, jar) {
        return Ok(());
    }
    std::fs::create_dir_all(mods).map_err(|e| format!("Couldn't prepare the mods folder: {e}"))?;
    // Written beside the target and renamed into place, so the game never sees a half-written jar.
    let staging = mods.join(format!(".{FILE_NAME}.part"));
    std::fs::write(&staging, jar).map_err(|e| format!("Couldn't install Flint's in-game menu: {e}"))?;
    std::fs::rename(&staging, &target).map_err(|e| format!("Couldn't install Flint's in-game menu: {e}"))?;
    // Read back: the launch only goes on with the whole jar in place.
    verify_dir(mods, Want::Runtime(jar))
}

/// Whether `target` holds exactly `jar`. Read and compared byte for byte, except when this run of Flint already did that
/// for this jar and the file as it is now (same size and modification time): a launch looks at the jar twice, and every
/// later launch again, so its megabytes are read once per change instead of at each look.
fn in_place(target: &Path, jar: &[u8]) -> bool {
    let Some(stamp) = std::fs::metadata(target).ok().filter(std::fs::Metadata::is_file).and_then(|m| Some((m.len(), m.modified().ok()?))) else {
        return false;
    };
    if stamp.0 != jar.len() as u64 {
        return false;
    }
    // Each line's jar is unpacked once per run, so its address names it.
    let known = (stamp, jar.as_ptr() as usize);
    let mut checked = CHECKED.lock().unwrap_or_else(|e| e.into_inner());
    if checked.get(target) == Some(&known) {
        return true;
    }
    let whole = std::fs::read(target).is_ok_and(|current| current == jar);
    if whole {
        checked.insert(target.to_owned(), known);
    } else {
        checked.remove(target);
    }
    whole
}

/// Runtime jars found whole this run: (size, modification time) and which jar, by path.
type Checked = std::collections::HashMap<std::path::PathBuf, ((u64, std::time::SystemTime), usize)>;
static CHECKED: std::sync::LazyLock<std::sync::Mutex<Checked>> = std::sync::LazyLock::new(Default::default);

#[cfg(test)]
mod tests {
    use super::*;

    fn profile(version: &str, loader: &str) -> Profile {
        serde_json::from_value(serde_json::json!({ "id": "p", "name": "P", "gameVersion": version, "loader": loader })).unwrap()
    }

    /// What a 1.21.x line's jar requires: the one release it serves, or the span of them
    /// (game-runtime/legacy's -Pminecraft_range).
    fn minecraft_range(line: &str) -> String {
        let versions: Vec<&str> = SUPPORTED_VERSIONS.iter().filter(|(_, l)| *l == line).map(|(v, _)| *v).collect();
        match versions.as_slice() {
            [one] => one.to_string(),
            many => format!(">={} <={}", many.last().unwrap(), many.first().unwrap()),
        }
    }

    #[test]
    fn only_fabric_on_a_tested_version_gets_the_runtime() {
        assert!(supports(&profile("26.3", "fabric")));
        assert!(supports(&profile("26.2", "fabric")));
        assert!(!supports(&profile("26.3", "vanilla")));
        assert!(!supports(&profile("26.2", "quilt")));
        // Newer releases than any line get the newest line's jar; snapshots don't.
        assert!(supports(&profile("26.3.1", "fabric")));
        assert!(supports(&profile("26.4", "fabric")));
        assert!(!supports(&profile("26.4", "vanilla")));
        assert!(!supports(&profile("26.4-snapshot-6", "fabric")));
        for version in ["26.1.2", "26.1.1", "26.1"] {
            assert!(supports(&profile(version, "fabric")), "{version}");
            assert!(!supports(&profile(version, "vanilla")), "{version}");
        }
        assert!(!supports(&profile("26.1.3", "fabric")));
        assert!(supports(&profile("1.21.11", "fabric")));
        assert!(supports(&profile("1.21.10", "fabric")));
        assert!(!supports(&profile("1.21.11", "vanilla")));
        assert!(!supports(&profile("1.21.10", "vanilla")));
        assert!(supports(&profile("1.21.1", "fabric")));
        assert!(!supports(&profile("1.21.1", "vanilla")));
        assert!(!supports(&profile("1.21", "fabric")));
        assert!(!supports(&profile("1.21.2", "fabric")));
        assert!(supports(&profile("1.8.9", "fabric")));
        assert!(!supports(&profile("1.8.9", "vanilla")));
        assert!(!supports(&profile("1.8.8", "fabric")));
    }

    #[test]
    fn each_version_gets_its_own_line() {
        assert_eq!(line("26.3"), Some("26.3"));
        assert_eq!(line("26.2"), Some("26.2"));
        assert_eq!(line("26.1.2"), Some("26.1"));
        assert_eq!(line("26.1.1"), Some("26.1"));
        assert_eq!(line("26.1"), Some("26.1"));
        assert_eq!(line("26.0"), None);
        assert_eq!(line("26.1.3"), None);
        assert_eq!(line("26.3.1"), Some("26.3"));
        assert_eq!(line("26.4"), Some("26.3"));
        assert_eq!(line("27.1"), Some("26.3"));
        assert_eq!(line("26.4-rc1"), None);
        assert_eq!(line(""), None);
        assert_eq!(line("1.21.11"), Some("1.21.11"));
        assert_eq!(line("1.21.10"), Some("1.21.10"));
        assert_eq!(line("1.21.1"), Some("1.21.1"));
        assert_eq!(line("1.21"), None);
        assert_eq!(line("1.8.9"), Some("1.8.9"));
        // Every supported version's line has an embedded slot (build.rs fills it, or leaves it empty).
        for (version, line) in SUPPORTED_VERSIONS {
            assert!(PACKED_LINES.iter().any(|(l, _, _)| l == line), "no jar slot for {version} ({line})");
        }
    }

    #[test]
    fn a_profile_gets_the_jar_of_its_version_or_none() {
        for (version, line) in SUPPORTED_VERSIONS {
            let built = embedded(line);
            let got = jar_for(&profile(version, "fabric"));
            if built.is_empty() {
                assert!(got.is_none(), "{version}: built without its jar, so nothing is installed");
            } else {
                assert!(std::ptr::eq(got.unwrap(), built), "{version} gets the {line} jar");
            }
        }
        assert!(jar_for(&profile("26.2", "vanilla")).is_none());
        assert!(jar_for(&profile("1.21.11", "vanilla")).is_none());
        assert!(jar_for(&profile("1.21.1", "vanilla")).is_none());
        assert!(jar_for(&profile("1.21.2", "fabric")).is_none());
        assert!(jar_for(&profile("1.8.9", "vanilla")).is_none());
    }

    #[test]
    fn the_embedded_jars_target_their_own_line() {
        // A jar built for one line must never be installed on another: each embedded jar's
        // fabric.mod.json requires its own line (26.x: ~line, the newest >=line; 1.21.x: exactly the releases it serves).
        for (line, _, _) in PACKED_LINES {
            let jar = embedded(line);
            if jar.is_empty() {
                continue;
            }
            let mut archive = zip::ZipArchive::new(std::io::Cursor::new(jar)).expect("the runtime is a jar");
            let mut text = String::new();
            std::io::Read::read_to_string(&mut archive.by_name("fabric.mod.json").expect("fabric.mod.json"), &mut text).unwrap();
            let meta: serde_json::Value = serde_json::from_str(&text).unwrap();
            let expected = if line.starts_with("1.") {
                minecraft_range(line)
            } else if *line == NEWEST_LINE {
                format!(">={line}")
            } else {
                format!("~{line}")
            };
            assert_eq!(meta["depends"]["minecraft"], expected, "the {line} jar");
        }
    }

    #[test]
    fn every_packed_jar_unpacks_whole_and_once() {
        // Each line on its own, as a launch asks for it: exactly the bytes build.rs packed (unpack checks the SHA-256).
        for (index, (line, size, _)) in PACKED_LINES.iter().enumerate() {
            let started = std::time::Instant::now();
            let jar = unpack(index).unwrap_or_else(|e| panic!("{line}: {e}"));
            eprintln!("{line}: {size} bytes unpacked in {:?}", started.elapsed());
            assert_eq!(jar.len(), *size, "{line}");
            if *size > 0 {
                assert!(jar.starts_with(b"PK\x03\x04"), "{line} is a jar");
            }
            // Kept: a launch looks at the jar twice, and its address tells in_place it was already compared.
            assert!(std::ptr::eq(embedded(line), embedded(line)), "{line}");
        }
        assert!(embedded("1.7.10").is_empty(), "not a runtime line");
        // The jars' entries are stored uncompressed and several lines share most of their files, so together they pack to
        // well under a fifth (one jar alone, as CI builds, to about a third).
        let total: usize = PACKED_LINES.iter().map(|(_, size, _)| size).sum();
        let built = PACKED_LINES.iter().filter(|(_, size, _)| *size > 0).count();
        assert!(built < 3 || PACKED.len() * 5 < total, "the jars are packed together");
        assert!(built == 0 || PACKED.len() * 2 < total, "the jars are stored, so the stream compresses them");
    }

    #[test]
    fn every_version_keeps_the_official_launchers_java_but_1_8_9_with_flint_which_needs_21() {
        let java21 = Some(JavaChoice { component: "java-runtime-delta", major: 21, minimum: 21, fallback: "java-runtime-delta" });
        assert_eq!(java_override(true, &profile("1.8.9", "fabric")), java21, "1.8.9 with Flint's runtime: Mojang's Java 21");
        for version in ["1.8.9", "1.21.1", "1.21.10", "1.21.11"] {
            if version != "1.8.9" {
                assert_eq!(java_override(true, &profile(version, "fabric")), None, "{version}: its own Java 21, as the official launcher");
            }
            assert_eq!(java_override(false, &profile(version, "fabric")), None, "{version}: the in-game menu is off, the version's own Java");
            assert_eq!(java_override(true, &profile(version, "vanilla")), None, "vanilla {version}: the version's own Java");
        }
        assert_eq!(java_override(true, &profile("1.8.8", "fabric")), None);
        for (version, _) in SUPPORTED_VERSIONS.iter().filter(|(v, _)| v.starts_with("26.")) {
            assert_eq!(java_override(true, &profile(version, "fabric")), None, "{version} already runs on Java 25");
        }
        assert_eq!(java_override(true, &profile("26.4", "fabric")), None);
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
    fn a_profile_that_should_have_the_runtime_never_starts_without_it() {
        // A build with the 26.3 jar but none for 1.21.10 (the owner's 1.21.10 profile once started to
        // Minecraft's own menu that way, its runtime only arriving with the next build).
        let jars = |line: &str| -> &'static [u8] {
            match line {
                "26.3" => b"jar",
                _ => b"",
            }
        };
        assert_eq!(wanted(true, &profile("26.3", "fabric"), jars), Want::Runtime(b"jar"));
        assert_eq!(wanted(true, &profile("1.21.10", "fabric"), jars), Want::NotBuilt("1.21.10"));
        assert_eq!(wanted(false, &profile("1.21.10", "fabric"), jars), Want::Nothing, "the setting is off");
        assert_eq!(wanted(true, &profile("1.21.10", "vanilla"), jars), Want::Nothing);
        assert_eq!(wanted(true, &profile("1.21.2", "fabric"), jars), Want::Nothing, "not a runtime version");

        let dir = TestDir::new();
        let mods = dir.0.join("mods");
        assert!(verify_dir(&mods, Want::Runtime(b"jar")).is_err(), "not written yet");
        sync_dir(&mods, Some(b"jar")).unwrap();
        assert!(verify_dir(&mods, Want::Runtime(b"jar")).is_ok());
        assert!(verify_dir(&mods, Want::Runtime(b"other line")).is_err(), "another line's jar");
        std::fs::write(mods.join(FILE_NAME), b"ja").unwrap();
        assert!(verify_dir(&mods, Want::Runtime(b"jar")).is_err(), "cut short");
        assert!(verify_dir(&mods, Want::Nothing).is_err(), "a runtime where none belongs");
        assert!(verify_dir(&mods, Want::NotBuilt("1.21.10")).unwrap_err().contains("1.21.10"));
        sync_dir(&mods, None).unwrap();
        assert!(verify_dir(&mods, Want::Nothing).is_ok());
    }

    #[test]
    fn a_jar_found_whole_is_read_again_only_once_it_changed() {
        static JAR: &[u8] = b"the runtime jar";
        let dir = TestDir::new();
        let mods = dir.0.join("mods");
        sync_dir(&mods, Some(JAR)).unwrap();
        let target = mods.join(FILE_NAME);
        assert!(verify_dir(&mods, Want::Runtime(JAR)).is_ok());

        // Damaged in place under the same size and time: the check that found it whole isn't repeated (that is the
        // saving), so it still passes...
        let stamp = std::fs::metadata(&target).unwrap().modified().unwrap();
        std::fs::write(&target, b"THE RUNTIME JAR").unwrap();
        std::fs::File::options().write(true).open(&target).unwrap().set_modified(stamp).unwrap();
        assert!(verify_dir(&mods, Want::Runtime(JAR)).is_ok());
        // ...but any write that moves the time is caught, and the next launch puts the jar back.
        std::fs::File::options().write(true).open(&target).unwrap().set_modified(stamp + std::time::Duration::from_secs(5)).unwrap();
        assert!(verify_dir(&mods, Want::Runtime(JAR)).is_err());
        sync_dir(&mods, Some(JAR)).unwrap();
        assert_eq!(std::fs::read(&target).unwrap(), JAR);
        assert!(verify_dir(&mods, Want::Runtime(JAR)).is_ok());
    }

    #[test]
    fn warming_a_profile_only_reads() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let profile = profile("26.3", "fabric");
        warm(&state, &profile);
        assert!(!state.paths.profile_dir(&profile.id).join("mods").exists(), "nothing is written: Play puts the jar in place");
        sync_dir(&state.paths.profile_dir(&profile.id).join("mods"), Some(embedded("26.3"))).unwrap();
        warm(&state, &profile);
        assert!(verify(&state, &profile).is_ok() || embedded("26.3").is_empty());
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
