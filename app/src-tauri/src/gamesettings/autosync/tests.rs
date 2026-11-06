//! Auto Sync end to end: the same entry points `game.rs` calls (`before_launch`, `after_exit`) and the
//! Settings switch (`set_enabled`), on throwaway data folders holding real files: the options.txt Minecraft
//! 26.3 wrote on a test profile, the sodium-options.json the real Sodium wrote, stub version jars carrying
//! the real data and resource pack versions, and zip packs with pack.mcmeta. Every identifier is invented.

use super::*;
use crate::core::{RunningGame, TestDir};
use crate::gamesettings::testkit::{add_profile, FIXTURE_26_3};
use std::io::Write;

const SODIUM: &[u8] = include_bytes!("../testdata/sodium-options.json");

fn setup() -> (TestDir, AppState) {
    let dir = TestDir::new();
    let state = AppState::for_tests(dir.0.clone());
    (dir, state)
}

fn quiet() -> ProcessScan {
    ProcessScan::from(Ok(Vec::new()))
}

fn edited(base: &[u8], edits: &[(&str, &str)]) -> Vec<u8> {
    let mut doc = OptionsDoc::parse(base).unwrap();
    for (k, v) in edits {
        doc.set(k, v);
    }
    doc.to_string().into_bytes()
}

/// A file as Minecraft 26.2 writes it: the 26.3 test file without 26.3-only lines, with 26.2's data
/// version and the raw input option 26.3 dropped.
fn options_26_2() -> Vec<u8> {
    let text = String::from_utf8(FIXTURE_26_3.to_vec()).unwrap();
    let only_26_3 = ["macFullscreenMenuVisibility:", "debugGuiScale:", "quitShortcuts:", "ctrlClickEmulatesRightClick:", "key_key.debug.improvedTransparency:"];
    let mut lines: Vec<&str> = text.lines().filter(|l| !only_26_3.iter().any(|p| l.starts_with(p))).collect();
    lines[0] = "version:4903";
    lines.insert(40, "rawMouseInput:true");
    (lines.join("\n") + "\n").into_bytes()
}

/// A profile with its version installed (26.x, 1.21.x or 1.8.9) and options.txt; `played` sets its
/// last-played time.
fn profile(state: &AppState, id: &str, version: &str, options: Option<&[u8]>, played: Option<u64>) {
    let data = match version {
        "26.3" => Some(5023),
        "26.2" => Some(4903),
        "26.1" => Some(4786),
        "1.21.11" => Some(4671),
        "1.21.10" => Some(4556),
        "1.21.1" => Some(3955),
        _ => None,
    };
    add_profile(state, id, version, data, options);
    if version == "1.8.9" {
        crate::gamesettings::testkit::install_legacy_189(&state.paths.game());
    }
    if played.is_some() {
        let mut file: ProfilesFile = crate::core::read_json(&state.paths.profiles());
        file.profiles.iter_mut().find(|p| p.id == id).unwrap().last_played = played;
        crate::data::save_profiles(state, &file).unwrap();
    }
}

fn dir(state: &AppState, id: &str) -> PathBuf {
    state.paths.profile_dir(id)
}

fn options(state: &AppState, id: &str) -> OptionsDoc {
    OptionsDoc::parse(&std::fs::read(dir(state, id).join("options.txt")).unwrap()).unwrap()
}

fn find(state: &AppState, id: &str) -> Profile {
    crate::data::find_profile(state, id).unwrap()
}

fn launch(state: &AppState, id: &str) -> Report {
    before_launch_with(state, &find(state, id), &quiet(), &Hooks::default())
}

fn launch_with(state: &AppState, id: &str, hooks: &Hooks) -> Report {
    before_launch_with(state, &find(state, id), &quiet(), hooks)
}

fn exit(state: &AppState, id: &str) -> Report {
    after_exit_with(state, id, Exit::Clean, &quiet(), &Hooks::default())
}

/// What the game does while it runs: changes options.txt its own way.
fn play(state: &AppState, id: &str, edits: &[(&str, &str)]) {
    let path = dir(state, id).join("options.txt");
    std::fs::write(&path, edited(&std::fs::read(&path).unwrap(), edits)).unwrap();
}

/// Settings → Auto Sync: the frontend saves the setting and calls the command.
fn switch(state: &AppState, on: bool) -> Status {
    crate::core::write_json(&state.paths.settings(), &json!({ "autoSync": on })).unwrap();
    set_enabled(state, on, &quiet(), &Hooks::default()).unwrap()
}

fn stored(state: &AppState) -> State {
    match store::load(&state.paths.root) {
        Loaded::Ok(st) => st,
        _ => panic!("the store exists"),
    }
}

fn zip(path: &Path, files: &[(&str, &[u8])]) {
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
    for (name, bytes) in files {
        zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(bytes).unwrap();
    }
    zip.finish().unwrap();
}

/// A resource pack zip declaring `formats` (the pack.mcmeta `pack` object), with `tag` making its content
/// unique.
fn resource_pack(path: &Path, formats: Json, tag: &str) {
    let meta = json!({ "pack": { "description": tag } }).to_string();
    let mut meta: Json = serde_json::from_str(&meta).unwrap();
    for (k, v) in formats.as_object().unwrap() {
        meta["pack"][k] = v.clone();
    }
    zip(path, &[("pack.mcmeta", meta.to_string().as_bytes()), ("assets/minecraft/textures/tag.txt", tag.as_bytes())]);
}

fn mod_jar(state: &AppState, id: &str, mod_id: &str, version: &str) {
    crate::perfcore::test_jar(&dir(state, id).join("mods"), &format!("{mod_id}-{version}.jar"), &json!({ "id": mod_id, "version": version, "environment": "*" }).to_string());
}

fn pack_list(state: &AppState, id: &str) -> Vec<String> {
    serde_json::from_str(options(state, id).get("resourcePacks").unwrap_or("[]")).unwrap()
}

fn files_in(path: &Path) -> Vec<String> {
    let mut names: Vec<String> = std::fs::read_dir(path).map(|e| e.flatten().map(|e| e.file_name().into_string().unwrap()).collect()).unwrap_or_default();
    names.sort();
    names
}

/// Two played profiles, 26.3 ("new", played last) and 26.2 ("old"), with Auto Sync on and both met once.
fn pair(state: &AppState) {
    profile(state, "new", "26.3", Some(FIXTURE_26_3), Some(2_000));
    let mut old = options_26_2();
    // A line from a mod 26.2's profile has that Flint doesn't know: it must survive every write.
    old.extend_from_slice(b"someMod.renderMode:fancy-ish\n");
    profile(state, "old", "26.2", Some(&old), Some(1_000));
    switch(state, true);
    assert_eq!(launch(state, "new").skipped, None);
    exit(state, "new");
    assert_eq!(launch(state, "old").skipped, None);
    exit(state, "old");
}

// ---------- options ----------

#[test]
fn changes_made_in_26_3_reach_26_2_and_back_without_touching_anything_else() {
    let (_dir, state) = setup();
    pair(&state);
    let before_old = std::fs::read(dir(&state, "old").join("options.txt")).unwrap();
    // The player changes sensitivity, field of view and two key bindings in 26.3, one to a key only SDL3 has.
    play(&state, "new", &[("mouseSensitivity", "0.75"), ("fov", "0.5"), ("key_key.jump", "key.keyboard.v"), ("key_key.drop", "key.keyboard.media.play"), ("soundDevice", "\"Speakers\"")]);
    let report = exit(&state, "new");
    assert_eq!(report.captured, ["options/fov", "options/key_key.drop", "options/key_key.jump", "options/mouseSensitivity"], "the sound device is never synced");
    let report = launch(&state, "old");
    assert!(report.backup.is_some(), "a backup was kept before writing");
    let old = options(&state, "old");
    assert_eq!(old.get("mouseSensitivity"), Some("0.75"));
    assert_eq!(old.get("fov"), Some("0.5"));
    assert_eq!(old.get("key_key.jump"), Some("key.keyboard.v"));
    assert_eq!(old.get("key_key.drop"), Some("key.keyboard.q"), "26.2 has no media keys: never written there");
    assert!(report.deferred.contains_key("options/key_key.drop"));
    assert_eq!(old.get("version"), Some("4903"), "the version line is never changed");
    assert_eq!(old.get("someMod.renderMode"), Some("fancy-ish"), "unknown lines stay");
    assert_eq!(old.get("rawMouseInput"), Some("true"));
    assert_eq!(old.line_count(), OptionsDoc::parse(&before_old).unwrap().line_count());
    let changed: Vec<&str> = before_old.split(|b| *b == b'\n').zip(std::fs::read(dir(&state, "old").join("options.txt")).unwrap().split(|b| *b == b'\n').collect::<Vec<_>>()).filter(|(a, b)| a != b).map(|(a, _)| std::str::from_utf8(a).unwrap()).collect();
    assert_eq!(changed, ["fov:0.0", "mouseSensitivity:0.5", "key_key.jump:key.keyboard.space"], "only those three lines changed");
    assert!(crate::gamesettings::backup::list(&dir(&state, "old")).iter().any(|b| b.valid), "a backup was kept first");
    // The other way round: 26.2 changes field of view and brightness.
    play(&state, "old", &[("fov", "0.25"), ("gamma", "1.0")]);
    exit(&state, "old");
    launch(&state, "new");
    let new = options(&state, "new");
    assert_eq!((new.get("fov"), new.get("gamma")), (Some("0.25"), Some("1.0")));
    assert_eq!(new.get("key_key.drop"), Some("key.keyboard.media.play"), "its own SDL3 key stays");
    assert_eq!(new.get("version"), Some("5023"));
    // Nothing more to do on either side now.
    assert!(launch(&state, "new").applied.is_empty());
    assert!(launch(&state, "old").captured.is_empty());
}

/// options.txt as Minecraft 1.21.11 writes it (abridged).
const OPTIONS_1_21_11: &[u8] = b"version:4671\nfov:0.0\nrenderDistance:12\ngamma:0.5\nmouseSensitivity:0.5\nlang:en_us\nkey_key.jump:key.keyboard.space\nkey_key.swapOffhand:key.keyboard.f\nsoundCategory_master:1.0\nresourcePacks:[\"vanilla\"]\n";

/// options.txt as Minecraft 1.8.9 writes it (abridged, with its CRLF line endings and no version line).
const OPTIONS_1_8_9: &[u8] = b"invertYMouse:false\r\nmouseSensitivity:0.5\r\nfov:0.0\r\ngamma:0.5\r\nrenderDistance:12\r\nfancyGraphics:true\r\nlang:en_US\r\nkey_key.jump:57\r\nkey_key.drop:16\r\nkey_key.streamStartStop:64\r\nsoundCategory_master:1.0\r\nresourcePacks:[]\r\n";

#[test]
fn changes_made_in_26_3_reach_1_21_11_and_1_8_9_and_changes_made_there_come_back() {
    let (_dir, state) = setup();
    pair(&state);
    // Created after Auto Sync was turned on: their first launch brings in the whole store.
    profile(&state, "mid", "1.21.11", Some(OPTIONS_1_21_11), Some(500));
    profile(&state, "legacy", "1.8.9", Some(OPTIONS_1_8_9), Some(400));
    assert_eq!(launch(&state, "mid").skipped, None);
    assert_eq!(launch(&state, "legacy").skipped, None);
    let fixture = OptionsDoc::parse(FIXTURE_26_3).unwrap();
    assert_eq!(options(&state, "mid").get("fov"), fixture.get("fov"), "a new profile of another version gets the store");
    exit(&state, "mid");
    exit(&state, "legacy");

    // The player changes settings in 26.3, including an off-hand key 1.8.9 doesn't have.
    play(&state, "new", &[
        ("fov", "0.5"),
        ("gamma", "1.0"),
        ("mouseSensitivity", "0.75"),
        ("renderDistance", "8"),
        ("lang", "fr_fr"),
        ("key_key.jump", "key.keyboard.v"),
        ("key_key.swapOffhand", "key.keyboard.g"),
    ]);
    exit(&state, "new");

    let report = launch(&state, "mid");
    assert!(report.backup.is_some());
    let mid = options(&state, "mid");
    assert_eq!((mid.get("fov"), mid.get("gamma"), mid.get("mouseSensitivity"), mid.get("renderDistance")), (Some("0.5"), Some("1.0"), Some("0.75"), Some("8")));
    assert_eq!((mid.get("lang"), mid.get("key_key.jump"), mid.get("key_key.swapOffhand")), (Some("fr_fr"), Some("key.keyboard.v"), Some("key.keyboard.g")));
    assert_eq!(mid.get("graphicsPreset"), Some("\"custom\""), "1.21.11 has graphics presets: the copied render distance stays");
    assert_eq!(mid.get("version"), Some("4671"), "1.21.11 keeps its own version line");

    let report = launch(&state, "legacy");
    assert!(report.backup.is_some());
    assert!(report.deferred.contains_key("options/key_key.swapOffhand"), "1.8.9 has no off-hand key");
    assert!(stored(&state).entries.contains_key("options/key_key.swapOffhand"), "but the store keeps it for the others");
    let legacy = options(&state, "legacy");
    assert_eq!((legacy.get("fov"), legacy.get("gamma"), legacy.get("mouseSensitivity"), legacy.get("renderDistance")), (Some("0.5"), Some("1.0"), Some("0.75"), Some("8")));
    assert_eq!((legacy.get("lang"), legacy.get("key_key.jump")), (Some("fr_FR"), Some("47")), "1.8.9's spelling and LWJGL 2 key code for V");
    assert_eq!(legacy.get("key_key.swapOffhand"), None);
    assert_eq!((legacy.get("fancyGraphics"), legacy.get("key_key.streamStartStop"), legacy.get("resourcePacks")), (Some("true"), Some("64"), Some("[]")), "1.8.9's own lines and packs stay");
    assert_eq!(legacy.get("version"), None, "1.8.9 files never get a version line");
    let text = String::from_utf8(std::fs::read(dir(&state, "legacy").join("options.txt")).unwrap()).unwrap();
    assert!(text.lines().count() > 0 && !text.replace("\r\n", "").contains('\n'), "its CRLF line endings are kept");

    // Changes made in 1.8.9 (in its own spelling) reach 26.3 and 1.21.11.
    play(&state, "legacy", &[("fov", "0.25"), ("gamma", "0.3"), ("key_key.jump", "48")]);
    let report = exit(&state, "legacy");
    assert_eq!(report.captured, ["options/fov", "options/gamma", "options/key_key.jump"]);
    launch(&state, "new");
    let new = options(&state, "new");
    assert_eq!((new.get("fov"), new.get("gamma"), new.get("key_key.jump")), (Some("0.25"), Some("0.3"), Some("key.keyboard.b")));
    assert_eq!(new.get("version"), Some("5023"));
    launch(&state, "mid");
    let mid = options(&state, "mid");
    assert_eq!((mid.get("fov"), mid.get("gamma"), mid.get("key_key.jump")), (Some("0.25"), Some("0.3"), Some("key.keyboard.b")));
    // Nothing more to do anywhere: what was written reads back as the same values.
    assert!(launch(&state, "legacy").captured.is_empty());
    assert!(launch(&state, "legacy").applied.is_empty());
    assert!(launch(&state, "mid").captured.is_empty());
}

#[test]
fn a_1_21_x_profile_met_before_its_version_was_checked_gets_the_whole_store_once() {
    let (_dir, state) = setup();
    pair(&state);
    play(&state, "new", &[("fov", "0.5"), ("gamma", "1.0")]);
    exit(&state, "new");
    // Records as Flint saved them before 1.21.x was checked: no `metAs`, and nothing brought in yet.
    profile(&state, "mid", "1.21.11", Some(OPTIONS_1_21_11), Some(500));
    let old_record = |id: &str| {
        let generation = stored(&state).generation;
        let mut record = ProfileSync { store: stored(&state).tag(), generation, seen: BTreeMap::from([("options/fov".to_owned(), "Int(70)".to_owned())]), ..ProfileSync::new(stored(&state).head()) };
        store::save_profile(&dir(&state, id), &mut record, generation).unwrap();
        assert!(!std::fs::read_to_string(dir(&state, id).join(store::PROFILE_FILE)).unwrap().contains("metAs"));
    };
    old_record("mid");
    let report = launch(&state, "mid");
    let mid = options(&state, "mid");
    assert_eq!((mid.get("fov"), mid.get("gamma")), (Some("0.5"), Some("1.0")), "values changed before it was checked come in too");
    assert!(report.applied.contains(&"options/fov".to_owned()));
    assert_eq!(store::load_profile(&dir(&state, "mid")).unwrap().met_as.as_deref(), Some("1.21.11"));
    // An old record of a 26.x profile is a normal one: it always took values.
    let mut record = store::load_profile(&dir(&state, "old")).unwrap();
    record.met_as = None;
    assert_eq!(start(&stored(&state), "old", "26.2", &Some(record.clone())), Start::Normal);
    // A record saved while the version couldn't take values (not installed yet), then installed: the whole store.
    record.met_as = Some(String::new());
    assert_eq!(start(&stored(&state), "old", "26.2", &Some(record.clone())), Start::FirstContact);
    assert_eq!(start(&stored(&state), "old", "", &Some(record)), Start::Normal, "still can't: its own changes are taken as usual");
}

#[test]
fn the_graphics_preset_rule_keeps_copied_video_options_from_being_undone() {
    let (_dir, state) = setup();
    pair(&state);
    play(&state, "new", &[("renderDistance", "8"), ("graphicsPreset", "\"custom\"")]);
    exit(&state, "new");
    launch(&state, "old");
    let old = options(&state, "old");
    assert_eq!(old.get("renderDistance"), Some("8"));
    assert_eq!(old.get("graphicsPreset"), Some("\"custom\""), "otherwise Minecraft's preset would undo it at launch");
}

// ---------- packs ----------

#[test]
fn resource_packs_are_placed_by_content_and_selected_only_where_they_fit() {
    let (_dir, state) = setup();
    pair(&state);
    let packs_new = dir(&state, "new").join("resourcepacks");
    resource_pack(&packs_new.join("Modern.zip"), json!({ "min_format": 88, "max_format": 97 }), "modern");
    resource_pack(&packs_new.join("New Only.zip"), json!({ "min_format": [97, 1], "max_format": 97 }), "new-only");
    std::fs::create_dir_all(packs_new.join("Folder Pack/assets")).unwrap();
    std::fs::write(packs_new.join("Folder Pack/pack.mcmeta"), json!({ "pack": { "description": "f", "min_format": 84, "max_format": 99 } }).to_string()).unwrap();
    std::fs::write(packs_new.join("Folder Pack/assets/a.txt"), b"folder").unwrap();
    // 26.2 already has the Modern pack's exact content under its own name, and its own pack named Collide.
    let packs_old = dir(&state, "old").join("resourcepacks");
    std::fs::create_dir_all(&packs_old).unwrap();
    std::fs::copy(packs_new.join("Modern.zip"), packs_old.join("My Modern.zip")).unwrap();
    resource_pack(&packs_old.join("Collide.zip"), json!({ "pack_format": 34 }), "old-own");
    resource_pack(&packs_new.join("Collide.zip"), json!({ "min_format": 88, "max_format": 97 }), "new-collide");
    play(&state, "new", &[("resourcePacks", r#"["vanilla","file/Modern.zip","file/New Only.zip","file/Folder Pack","file/Collide.zip"]"#)]);
    let report = exit(&state, "new");
    assert!(report.captured.contains(&"packs/resourcepacks/selection".to_owned()));
    assert_eq!(report.captured.iter().filter(|k| k.starts_with("packs/resourcepacks/") && !k.ends_with("selection")).count(), 4);
    let library = packs::load_library(&store::root(&state.paths.root));
    assert_eq!(library.resourcepacks.len(), 4);
    play(&state, "old", &[("resourcePacks", r#"["vanilla","fabric","file/My Modern.zip"]"#)]);
    exit(&state, "old");
    launch(&state, "old");
    assert_eq!(files_in(&packs_old), ["Collide (Flint ".to_owned() + &library.resourcepacks.iter().find(|(_, p)| p.name == "Collide.zip" && p.formats.is_some_and(|f| f.0 .0 == 88)).unwrap().0[..8] + ").zip", "Collide.zip".into(), "Folder Pack".into(), "My Modern.zip".into(), "New Only.zip".into()]);
    assert_eq!(std::fs::read(packs_old.join("Folder Pack/assets/a.txt")).unwrap(), b"folder");
    // No duplicate of Modern; the incompatible pack is placed but not selected; Minecraft's own entries stay.
    let list = pack_list(&state, "old");
    let collide_copy = files_in(&packs_old).into_iter().find(|n| n.starts_with("Collide (Flint")).unwrap();
    assert_eq!(list, ["vanilla".to_owned(), "fabric".into(), "file/My Modern.zip".into(), "file/Folder Pack".into(), format!("file/{collide_copy}")]);
    // The 26.2 profile's own Collide.zip is untouched.
    assert!(packs::read_mcmeta(&packs_old.join("Collide.zip"), false).unwrap()["pack"]["description"] == "old-own");
}

#[test]
fn renamed_updated_and_deleted_packs_follow_their_content_and_nothing_is_deleted_elsewhere() {
    let (_dir, state) = setup();
    pair(&state);
    let packs_new = dir(&state, "new").join("resourcepacks");
    let packs_old = dir(&state, "old").join("resourcepacks");
    resource_pack(&packs_new.join("Faithful.zip"), json!({ "min_format": 84, "max_format": 99 }), "v1");
    resource_pack(&packs_new.join("Rename Me.zip"), json!({ "min_format": 84, "max_format": 99 }), "rename");
    play(&state, "new", &[("resourcePacks", r#"["vanilla","file/Faithful.zip","file/Rename Me.zip"]"#)]);
    exit(&state, "new");
    launch(&state, "old");
    assert_eq!(files_in(&packs_old), ["Faithful.zip", "Rename Me.zip"]);
    exit(&state, "old");
    // At the origin: one pack renamed, one updated in place.
    std::fs::rename(packs_new.join("Rename Me.zip"), packs_new.join("Renamed.zip")).unwrap();
    resource_pack(&packs_new.join("Faithful.zip"), json!({ "min_format": 84, "max_format": 99 }), "v2");
    play(&state, "new", &[("resourcePacks", r#"["vanilla","file/Faithful.zip","file/Renamed.zip"]"#)]);
    exit(&state, "new");
    let report = launch(&state, "old");
    assert_eq!(files_in(&packs_old), ["Faithful.zip", "Rename Me.zip"], "the renamed pack isn't duplicated");
    assert_eq!(packs::read_mcmeta(&packs_old.join("Faithful.zip"), false).unwrap()["pack"]["description"], "v2", "the update replaced the synced copy");
    assert_eq!(pack_list(&state, "old"), ["file/Faithful.zip", "file/Rename Me.zip"], "the selection uses this profile's own name");
    let backup = crate::gamesettings::backup::list(&dir(&state, "old")).into_iter().next().unwrap();
    assert!(backup.files.iter().any(|f| f.path == "resourcepacks/Faithful.zip" && f.existed), "{report:?}");
    exit(&state, "old");
    // Deleted at the origin: it leaves the selection elsewhere, but the file stays.
    std::fs::remove_file(packs_new.join("Renamed.zip")).unwrap();
    play(&state, "new", &[("resourcePacks", r#"["vanilla","file/Faithful.zip"]"#)]);
    exit(&state, "new");
    launch(&state, "old");
    assert!(packs_old.join("Rename Me.zip").exists(), "files are never deleted from other profiles");
    assert_eq!(pack_list(&state, "old"), ["file/Faithful.zip"], "Minecraft's own packs aren't synced; it adds them itself");
}

#[test]
fn a_pack_name_differing_only_in_case_counts_as_taken() {
    let (_dir, state) = setup();
    pair(&state);
    resource_pack(&dir(&state, "new").join("resourcepacks/Faithful.zip"), json!({ "min_format": 84, "max_format": 99 }), "theirs");
    resource_pack(&dir(&state, "old").join("resourcepacks/faithful.zip"), json!({ "min_format": 84, "max_format": 99 }), "mine");
    exit(&state, "new");
    let report = launch(&state, "old");
    assert_eq!(report.skipped, None, "{report:?}");
    let names = files_in(&dir(&state, "old").join("resourcepacks"));
    assert_eq!(names.len(), 2, "{names:?}");
    assert!(names.iter().any(|n| n.starts_with("Faithful (Flint ")));
    assert_eq!(packs::read_mcmeta(&dir(&state, "old").join("resourcepacks/faithful.zip"), false).unwrap()["pack"]["description"], "mine");
}

#[test]
fn a_profile_that_can_t_show_a_pack_keeps_it_in_the_shared_selection() {
    let (_dir, state) = setup();
    pair(&state);
    let packs_new = dir(&state, "new").join("resourcepacks");
    resource_pack(&packs_new.join("Only New.zip"), json!({ "min_format": 97, "max_format": 97 }), "only-new");
    resource_pack(&packs_new.join("Both.zip"), json!({ "min_format": 84, "max_format": 99 }), "both");
    play(&state, "new", &[("resourcePacks", r#"["vanilla","file/Only New.zip","file/Both.zip"]"#)]);
    exit(&state, "new");
    launch(&state, "old");
    assert_eq!(pack_list(&state, "old"), ["file/Both.zip"]);
    // In 26.2 the player adds a pack of their own and plays.
    resource_pack(&dir(&state, "old").join("resourcepacks/Extra.zip"), json!({ "min_format": 84, "max_format": 99 }), "extra");
    play(&state, "old", &[("resourcePacks", r#"["vanilla","file/Both.zip","file/Extra.zip"]"#)]);
    exit(&state, "old");
    launch(&state, "new");
    assert_eq!(pack_list(&state, "new"), ["vanilla", "file/Only New.zip", "file/Both.zip", "file/Extra.zip"], "26.3's pack that 26.2 couldn't show is still selected");
}

#[test]
fn shader_selection_goes_only_where_iris_is_installed() {
    let (_dir, state) = setup();
    profile(&state, "new", "26.3", Some(FIXTURE_26_3), Some(2_000));
    profile(&state, "old", "26.2", Some(&options_26_2()), Some(1_000));
    profile(&state, "iris2", "26.3", Some(FIXTURE_26_3), Some(500));
    for id in ["new", "iris2"] {
        mod_jar(&state, id, "iris", "1.9.0+mc26.3");
        std::fs::create_dir_all(dir(&state, id).join("config")).unwrap();
    }
    std::fs::write(dir(&state, "new").join("config/iris.properties"), b"shaderPack=\nenableShaders=false\n").unwrap();
    std::fs::write(dir(&state, "iris2").join("config/iris.properties"), b"#This file stores configuration options for Iris\nshaderPack=\nenableShaders=false\nmaxShadowRenderDistance=32\n").unwrap();
    switch(&state, true);
    for id in ["new", "old", "iris2"] {
        launch(&state, id);
        exit(&state, id);
    }
    // The player picks a shader pack in 26.3.
    zip(&dir(&state, "new").join("shaderpacks/Shiny.zip"), &[("shaders/final.fsh", b"void main() {}")]);
    std::fs::write(dir(&state, "new").join("config/iris.properties"), b"shaderPack=Shiny.zip\nenableShaders=true\n").unwrap();
    let report = exit(&state, "new");
    assert!(report.captured.contains(&"packs/shaderpacks/selection".to_owned()), "{report:?}");
    // 26.2 without Iris: the pack file arrives, the selection waits.
    let report = launch(&state, "old");
    assert!(dir(&state, "old").join("shaderpacks/Shiny.zip").exists());
    assert!(!dir(&state, "old").join("config/iris.properties").exists());
    assert!(report.deferred.contains_key("packs/shaderpacks/selection"));
    exit(&state, "old");
    // With Iris: selected, and Iris's other settings stay.
    launch(&state, "iris2");
    let iris = PropsDoc::parse(&std::fs::read(dir(&state, "iris2").join("config/iris.properties")).unwrap()).unwrap();
    assert_eq!((iris.get("shaderPack"), iris.get("enableShaders"), iris.get("maxShadowRenderDistance")), (Some("Shiny.zip"), Some("true"), Some("32")));
    // Iris installed in 26.2 later (it writes its file at first start): the waiting selection comes in.
    mod_jar(&state, "old", "iris", "1.9.0+mc26.2");
    std::fs::create_dir_all(dir(&state, "old").join("config")).unwrap();
    std::fs::write(dir(&state, "old").join("config/iris.properties"), b"shaderPack=\nenableShaders=false\n").unwrap();
    let report = launch(&state, "old");
    assert!(report.applied.contains(&"packs/shaderpacks/selection".to_owned()), "{report:?}");
    let iris = PropsDoc::parse(&std::fs::read(dir(&state, "old").join("config/iris.properties")).unwrap()).unwrap();
    assert_eq!((iris.get("shaderPack"), iris.get("enableShaders")), (Some("Shiny.zip"), Some("true")));
}

// ---------- mod settings ----------

#[test]
fn sodium_settings_wait_for_sodium_then_merge_across_versions() {
    let (_dir, state) = setup();
    pair(&state);
    profile(&state, "older", "26.1", Some(&edited(&options_26_2(), &[("version", "4786")])), Some(100));
    launch(&state, "older");
    exit(&state, "older");
    mod_jar(&state, "new", "sodium", "0.9.2+mc26.3");
    let config = dir(&state, "new").join("config");
    std::fs::create_dir_all(&config).unwrap();
    std::fs::write(config.join("sodium-options.json"), SODIUM).unwrap();
    launch(&state, "new");
    // The player changes Sodium's settings in 26.3.
    let changed = String::from_utf8(SODIUM.to_vec()).unwrap().replace("\"chunk_builder_threads\": 0", "\"chunk_builder_threads\": 3").replace("\"NEAREST\"", "\"LINEAR\"");
    std::fs::write(config.join("sodium-options.json"), &changed).unwrap();
    assert!(exit(&state, "new").captured.contains(&"mods/sodium/sodium-options.json".to_owned()));
    let meta: Json = serde_json::from_slice(&std::fs::read(store::root(&state.paths.root).join("mods/sodium/meta.json")).unwrap()).unwrap();
    assert_eq!(meta["files"]["sodium-options.json"]["modVersion"], "0.9.2+mc26.3");
    assert_eq!((meta["files"]["sodium-options.json"]["format"].clone(), meta["files"]["sodium-options.json"]["adapter"].clone()), (json!("json"), json!("sodium")));
    assert_eq!(meta["files"]["sodium-options.json"]["sha256"], stored(&state).entries["mods/sodium/sodium-options.json"].value);
    // 26.2 has no Sodium: nothing is created, the settings wait in the store.
    let report = launch(&state, "old");
    assert!(report.deferred["mods/sodium/sodium-options.json"].starts_with("StoredOnly"), "{report:?}");
    assert!(!dir(&state, "old").join("config/sodium-options.json").exists());
    exit(&state, "old");
    // Sodium installed there (same major.minor, no file yet): the stored file is used as it is.
    mod_jar(&state, "old", "sodium", "0.9.1+mc26.2");
    let report = launch(&state, "old");
    assert!(report.applied.contains(&"mods/sodium/sodium-options.json".to_owned()), "{report:?}");
    assert_eq!(std::fs::read_to_string(dir(&state, "old").join("config/sodium-options.json")).unwrap(), changed);
    // An older Sodium waits for its own file, then gets a key-by-key merge (numbers yes, enum names no).
    mod_jar(&state, "older", "sodium", "0.8.0+mc26.1");
    let report = launch(&state, "older");
    assert!(report.deferred["mods/sodium/sodium-options.json"].contains("hasn't written its own file"), "{report:?}");
    exit(&state, "older");
    let own = String::from_utf8(SODIUM.to_vec()).unwrap().replace("\"use_fog_occlusion\": true,", "\"use_fog_occlusion\": true,\n    \"old_only_option\": 1,");
    std::fs::create_dir_all(dir(&state, "older").join("config")).unwrap();
    std::fs::write(dir(&state, "older").join("config/sodium-options.json"), &own).unwrap();
    let report = launch(&state, "older");
    assert!(report.applied.contains(&"mods/sodium/sodium-options.json".to_owned()), "{report:?}");
    let merged = std::fs::read_to_string(dir(&state, "older").join("config/sodium-options.json")).unwrap();
    assert_eq!(merged, own.replace("\"chunk_builder_threads\": 0", "\"chunk_builder_threads\": 3"), "only the shared number moved; its own key and its enum stay");
}

#[test]
fn other_mods_settings_copy_only_between_the_same_version_and_private_files_never_move() {
    let (_dir, state) = setup();
    pair(&state);
    profile(&state, "third", "26.3", Some(FIXTURE_26_3), Some(10));
    for (id, version) in [("new", "1.0"), ("old", "1.0"), ("third", "1.1")] {
        mod_jar(&state, id, "xaero", version);
        let config = dir(&state, id).join("config/xaero");
        std::fs::create_dir_all(&config).unwrap();
        std::fs::write(config.join("minimap.txt"), b"zoom=1\n").unwrap();
        std::fs::write(config.join("session-token.json"), format!("{{\"token\":\"{id}\"}}")).unwrap();
        std::fs::write(dir(&state, id).join("config/unknownmod.json"), format!("{{\"who\":\"{id}\"}}")).unwrap();
        std::fs::write(dir(&state, id).join("config/sodium-fingerprint.json"), format!("{{\"v\":1,\"s\":\"{id}\"}}")).unwrap();
    }
    launch(&state, "third");
    exit(&state, "third");
    launch(&state, "new");
    std::fs::write(dir(&state, "new").join("config/xaero/minimap.txt"), b"zoom=2\n").unwrap();
    std::fs::write(dir(&state, "new").join("config/xaero/session-token.json"), b"{\"token\":\"changed\"}").unwrap();
    std::fs::write(dir(&state, "new").join("config/unknownmod.json"), b"{\"who\":\"changed\"}").unwrap();
    let report = exit(&state, "new");
    assert_eq!(report.captured, ["mods/xaero/xaero/minimap.txt"]);
    assert!(stored(&state).entries.keys().all(|k| !k.contains("token") && !k.contains("unknownmod") && !k.contains("fingerprint")));
    launch(&state, "old");
    assert_eq!(std::fs::read(dir(&state, "old").join("config/xaero/minimap.txt")).unwrap(), b"zoom=2\n", "same version: copied as it is");
    let report = launch(&state, "third");
    assert_eq!(std::fs::read(dir(&state, "third").join("config/xaero/minimap.txt")).unwrap(), b"zoom=1\n", "another version: stored only");
    assert!(report.deferred["mods/xaero/xaero/minimap.txt"].starts_with("StoredOnly"));
    for id in ["old", "third"] {
        assert_eq!(std::fs::read_to_string(dir(&state, id).join("config/xaero/session-token.json")).unwrap(), format!("{{\"token\":\"{id}\"}}"));
        assert_eq!(std::fs::read_to_string(dir(&state, id).join("config/unknownmod.json")).unwrap(), format!("{{\"who\":\"{id}\"}}"));
    }
}

#[test]
fn a_value_that_first_appears_elsewhere_doesn_t_replace_one_a_profile_already_has() {
    let (_dir, state) = setup();
    profile(&state, "new", "26.3", Some(FIXTURE_26_3), Some(2_000));
    profile(&state, "old", "26.3", Some(FIXTURE_26_3), Some(1_000));
    mod_jar(&state, "old", "xaero", "1.0");
    std::fs::create_dir_all(dir(&state, "old").join("config/xaero")).unwrap();
    std::fs::write(dir(&state, "old").join("config/xaero/minimap.txt"), b"zoom=5\n").unwrap();
    switch(&state, true);
    for id in ["new", "old"] {
        launch(&state, id);
        exit(&state, id);
    }
    // The mod is installed in the other profile later and writes its defaults there.
    mod_jar(&state, "new", "xaero", "1.0");
    std::fs::create_dir_all(dir(&state, "new").join("config/xaero")).unwrap();
    std::fs::write(dir(&state, "new").join("config/xaero/minimap.txt"), b"zoom=1\n").unwrap();
    assert_eq!(exit(&state, "new").captured, ["mods/xaero/xaero/minimap.txt"]);
    assert!(stored(&state).entries["mods/xaero/xaero/minimap.txt"].introduced);
    launch(&state, "old");
    assert_eq!(std::fs::read(dir(&state, "old").join("config/xaero/minimap.txt")).unwrap(), b"zoom=5\n", "its own settings stay");
    // A real change made afterwards does come in.
    std::fs::write(dir(&state, "new").join("config/xaero/minimap.txt"), b"zoom=3\n").unwrap();
    exit(&state, "new");
    launch(&state, "old");
    assert_eq!(std::fs::read(dir(&state, "old").join("config/xaero/minimap.txt")).unwrap(), b"zoom=3\n");
}

// ---------- turning it on and off ----------

#[test]
fn turning_on_seeds_from_the_latest_played_profile_and_leaves_the_others_as_they_are() {
    let (_dir, state) = setup();
    profile(&state, "a", "26.3", Some(&edited(FIXTURE_26_3, &[("fov", "0.25")])), Some(100));
    profile(&state, "b", "26.3", Some(&edited(FIXTURE_26_3, &[("fov", "0.5"), ("gamma", "1.0")])), Some(300));
    profile(&state, "c", "26.2", Some(&options_26_2()), None);
    let a_before = std::fs::read(dir(&state, "a").join("options.txt")).unwrap();
    let c_before = std::fs::read(dir(&state, "c").join("options.txt")).unwrap();
    let status = switch(&state, true);
    assert!(!status.waiting);
    let st = stored(&state);
    assert_eq!(st.seeded_from.as_deref(), Some("b"));
    assert_eq!(st.entries["options/fov"].value, "0.5");
    assert!(st.entries.values().all(|e| e.rev == 1));
    for id in ["a", "c"] {
        let report = launch(&state, id);
        assert!(report.applied.is_empty() && report.captured.is_empty(), "{id}: {report:?}");
    }
    assert_eq!(std::fs::read(dir(&state, "a").join("options.txt")).unwrap(), a_before, "existing profiles aren't overwritten");
    assert_eq!(std::fs::read(dir(&state, "c").join("options.txt")).unwrap(), c_before);
    assert!(crate::gamesettings::backup::list(&dir(&state, "a")).is_empty(), "nothing written, so no backup");
    // A profile created afterwards gets everything at its first launch, in a new options.txt.
    profile(&state, "fresh", "26.2", None, None);
    let report = launch(&state, "fresh");
    assert!(report.applied.contains(&"options/fov".to_owned()), "{report:?}");
    let fresh = options(&state, "fresh");
    assert_eq!(fresh.to_string().lines().next(), Some("version:4903"));
    assert_eq!((fresh.get("fov"), fresh.get("gamma")), (Some("0.5"), Some("1.0")));
}

#[test]
fn with_nothing_played_yet_the_store_starts_empty_and_the_first_change_fills_it() {
    let (_dir, state) = setup();
    profile(&state, "a", "26.3", Some(FIXTURE_26_3), None);
    let status = switch(&state, true);
    assert!(status.waiting, "Waiting for your first game");
    assert!(stored(&state).entries.is_empty());
    launch(&state, "a");
    play(&state, "a", &[("fov", "0.5")]);
    assert_eq!(exit(&state, "a").captured, ["options/fov"]);
    assert!(!status_of(true, Some(&stored(&state))).waiting);
}

#[test]
fn turning_off_then_on_again_takes_every_profile_as_it_is_and_later_changes_still_flow() {
    let (_dir, state) = setup();
    pair(&state);
    let status = switch(&state, false);
    assert!(!status.enabled && !status.waiting);
    assert!(!stored(&state).enabled);
    play(&state, "new", &[("fov", "0.5")]);
    assert_eq!(exit(&state, "new").skipped, Some("off"));
    play(&state, "old", &[("fov", "-0.5")]);
    assert_eq!(launch(&state, "old").skipped, Some("off"));
    assert_eq!(options(&state, "old").get("fov"), Some("-0.5"));
    switch(&state, true);
    for id in ["new", "old"] {
        launch(&state, id);
        exit(&state, id);
    }
    assert_eq!(options(&state, "new").get("fov"), Some("0.5"), "nothing overwritten on re-enable");
    assert_eq!(options(&state, "old").get("fov"), Some("-0.5"));
    play(&state, "new", &[("gamma", "0.8")]);
    exit(&state, "new");
    launch(&state, "old");
    assert_eq!((options(&state, "old").get("gamma"), options(&state, "old").get("fov")), (Some("0.8"), Some("-0.5")), "only the new change comes in");
}

#[test]
fn the_hooks_follow_the_setting_even_without_the_command() {
    let (_dir, state) = setup();
    profile(&state, "a", "26.3", Some(FIXTURE_26_3), Some(5));
    // No settings at all: Auto Sync is on by default and its first hook seeds the store.
    launch(&state, "a");
    assert!(stored(&state).enabled);
    crate::core::write_json(&state.paths.settings(), &json!({ "autoSync": false })).unwrap();
    assert_eq!(exit(&state, "a").skipped, Some("off"));
    assert!(!stored(&state).enabled, "the store mirrors the setting");
}

// ---------- versions and running games ----------

#[test]
fn an_unverified_version_gets_nothing_but_its_own_changes_are_still_taken() {
    let (_dir, state) = setup();
    pair(&state);
    profile(&state, "legacy", "1.20.1", Some(b"version:3465\nfov:0.0\ngamma:0.5\nkey_key.jump:key.keyboard.space\nkey_key.drop:key.keyboard.81\n"), Some(50));
    let legacy_before = std::fs::read(dir(&state, "legacy").join("options.txt")).unwrap();
    play(&state, "new", &[("fov", "0.5")]);
    exit(&state, "new");
    let report = launch(&state, "legacy");
    assert_eq!(report.skipped, Some("not verified"));
    assert_eq!(std::fs::read(dir(&state, "legacy").join("options.txt")).unwrap(), legacy_before, "nothing applied");
    assert!(stored(&state).entries.contains_key("options/fov"), "the store is kept");
    play(&state, "legacy", &[("gamma", "0.9"), ("key_key.jump", "key.keyboard.b"), ("key_key.drop", "key.keyboard.80")]);
    let report = exit(&state, "legacy");
    assert_eq!(report.captured, ["options/gamma", "options/key_key.jump"], "numeric key codes from an unverified version aren't taken");
    assert_eq!(stored(&state).entries["options/gamma"].origin_family, None);
    launch(&state, "new");
    assert_eq!((options(&state, "new").get("gamma"), options(&state, "new").get("key_key.jump")), (Some("0.9"), Some("key.keyboard.b")));
}

#[test]
fn a_running_game_s_profile_is_never_written() {
    let (_dir, state) = setup();
    pair(&state);
    play(&state, "new", &[("fov", "0.5")]);
    exit(&state, "new");
    let before = std::fs::read(dir(&state, "old").join("options.txt")).unwrap();
    // The same profile is already running (started before Flint opened, or by another launcher).
    let elsewhere = ProcessScan::from(Ok(vec![format!("9 /usr/bin/java Main --gameDir {}", dir(&state, "old").display())]));
    let report = before_launch_with(&state, &find(&state, "old"), &elsewhere, &Hooks::default());
    assert_eq!(report.skipped, Some("running"));
    assert_eq!(std::fs::read(dir(&state, "old").join("options.txt")).unwrap(), before);
    // A game Flint started is running in it: an exit report for it is ignored too.
    state.running.lock().unwrap().insert("old".into(), RunningGame { kill: None });
    assert_eq!(exit(&state, "old").skipped, Some("busy"));
    state.running.lock().unwrap().clear();
    launch(&state, "old");
    assert_eq!(options(&state, "old").get("fov"), Some("0.5"), "applied at its next launch");
}

// ---------- failures ----------

#[test]
fn a_file_changed_during_the_update_aborts_it_and_nothing_is_written() {
    let (_dir, state) = setup();
    pair(&state);
    play(&state, "new", &[("fov", "0.5"), ("gamma", "0.9")]);
    exit(&state, "new");
    let path = dir(&state, "old").join("options.txt");
    let saved_by_game = edited(&std::fs::read(&path).unwrap(), &[("fov", "-0.25")]);
    let game = || std::fs::write(&path, &saved_by_game).unwrap();
    let report = launch_with(&state, "old", &Hooks { after_stage: Some(&game), ..Hooks::default() });
    assert_eq!(report.skipped, Some("conflict"));
    assert_eq!(std::fs::read(&path).unwrap(), saved_by_game, "nothing written over what the game saved");
    assert_eq!(status(&state).notice.unwrap().text, "Settings in ‘Profile old’ changed while Auto Sync was updating them, so it left them as they were this time.");
    // The next launch takes the player's change first, then brings in the rest.
    let report = launch(&state, "old");
    assert_eq!(report.captured, ["options/fov"]);
    assert_eq!((options(&state, "old").get("fov"), options(&state, "old").get("gamma")), (Some("-0.25"), Some("0.9")));
}

#[test]
fn an_interrupted_write_is_rolled_back_with_no_partial_file() {
    let (_dir, state) = setup();
    pair(&state);
    resource_pack(&dir(&state, "new").join("resourcepacks/Pack.zip"), json!({ "min_format": 84, "max_format": 99 }), "p");
    play(&state, "new", &[("fov", "0.5"), ("resourcePacks", r#"["vanilla","file/Pack.zip"]"#)]);
    exit(&state, "new");
    let before = std::fs::read(dir(&state, "old").join("options.txt")).unwrap();
    let report = launch_with(&state, "old", &Hooks { fail_rename: Some(1), ..Hooks::default() });
    assert_eq!(report.skipped, Some("failed"));
    assert_eq!(std::fs::read(dir(&state, "old").join("options.txt")).unwrap(), before, "put back");
    assert_eq!(files_in(&dir(&state, "old").join("resourcepacks")), Vec::<String>::new(), "no partial or staged file");
    assert!(!files_in(&dir(&state, "old")).iter().any(|n| n.ends_with(".flint-tmp")));
    assert_eq!(status(&state).notice.unwrap().text, "Auto Sync couldn't update ‘Profile old’, so its files were left as they were.");
    launch(&state, "old");
    assert_eq!(options(&state, "old").get("fov"), Some("0.5"), "the next launch succeeds");
    assert!(dir(&state, "old").join("resourcepacks/Pack.zip").exists());
}

#[test]
fn an_unreadable_options_file_is_left_alone_with_a_notice() {
    let (_dir, state) = setup();
    pair(&state);
    play(&state, "new", &[("fov", "0.5")]);
    exit(&state, "new");
    let path = dir(&state, "old").join("options.txt");
    let damaged = [b"version:4903\nfov:0.0\nlang:".as_slice(), &[0xff, 0xfe], b"\n"].concat();
    std::fs::write(&path, &damaged).unwrap();
    let report = launch(&state, "old");
    assert_eq!(report.skipped, Some("unreadable"));
    assert_eq!(std::fs::read(&path).unwrap(), damaged);
    let notice = status(&state).notice.unwrap();
    assert_eq!(notice.text, "A settings file in ‘Profile old’ couldn't be read; Auto Sync is leaving that profile's settings alone.");
    // Shown once, and gone when dismissed.
    launch(&state, "old");
    assert_eq!(stored(&state).notices.len(), 1);
    assert_eq!(dismiss(&state, &notice.id).notice, None);
}

#[test]
fn a_profile_unreadable_when_first_met_is_taken_as_it_is_once_it_can_be_read() {
    let (_dir, state) = setup();
    profile(&state, "a", "26.3", Some(&edited(FIXTURE_26_3, &[("fov", "0.5")])), Some(200));
    profile(&state, "b", "26.3", Some(&[b"version:5023\nfov:-0.5\nlang:".as_slice(), &[0xff], b"\n"].concat()), Some(100));
    switch(&state, true);
    assert_eq!(launch(&state, "b").skipped, Some("unreadable"));
    assert!(store::load_profile(&dir(&state, "b")).is_none(), "no record yet");
    // Minecraft rewrote the file at its next start; the profile's own values must stay.
    std::fs::write(dir(&state, "b").join("options.txt"), edited(FIXTURE_26_3, &[("fov", "-0.5")])).unwrap();
    let report = launch(&state, "b");
    assert!(report.applied.is_empty(), "{report:?}");
    assert_eq!(options(&state, "b").get("fov"), Some("-0.5"));
}

#[test]
fn after_a_crash_only_files_that_read_back_whole_are_taken() {
    let (_dir, state) = setup();
    pair(&state);
    launch(&state, "new");
    let path = dir(&state, "new").join("options.txt");
    // The game crashed while saving: the file stops mid-line.
    let mut cut = edited(&std::fs::read(&path).unwrap(), &[("fov", "0.5")]);
    cut.truncate(cut.len() - 10);
    std::fs::write(&path, &cut).unwrap();
    let report = after_exit_with(&state, "new", Exit::Unclean, &quiet(), &Hooks::default());
    assert!(report.captured.iter().all(|k| !k.starts_with(OPTIONS)), "{report:?}");
    assert!(!stored(&state).entries.get("options/fov").is_some_and(|e| e.value == "0.5"));
}

// ---------- concurrency, library, safety ----------

#[test]
fn concurrent_captures_get_distinct_ordered_revisions_and_the_last_one_wins() {
    let (_dir, state) = setup();
    pair(&state);
    for round in 0..8 {
        // Field of view is kept in whole degrees, so each value is a different angle.
        play(&state, "new", &[("fov", &format!("0.{}", round + 1)), ("gamma", &format!("0.{}", round + 1))]);
        play(&state, "old", &[("fov", &format!("-0.{}", round + 1)), ("gamma", &format!("0.{round}5"))]);
        let head = stored(&state).head();
        std::thread::scope(|s| {
            s.spawn(|| exit(&state, "new"));
            s.spawn(|| exit(&state, "old"));
        });
        let st = stored(&state);
        assert_eq!(st.head(), head + 4, "two keys from each profile, each its own revision");
        let (fov, gamma) = (&st.entries["options/fov"], &st.entries["options/gamma"]);
        // Whichever capture ran last holds both keys, with the two newest revisions.
        assert_eq!(fov.origin_profile, gamma.origin_profile);
        assert_eq!([fov.rev.max(gamma.rev), fov.rev.min(gamma.rev)], [head + 4, head + 3]);
        let expected = if fov.origin_profile == "new" { format!("0.{}", round + 1) } else { format!("-0.{}", round + 1) };
        assert_eq!(fov.value, expected);
    }
}

#[test]
fn the_library_keeps_one_copy_per_content_and_copies_still_work_when_cloning_fails() {
    let (_dir, state) = setup();
    pair(&state);
    profile(&state, "third", "26.3", Some(FIXTURE_26_3), Some(10));
    launch(&state, "third");
    exit(&state, "third");
    resource_pack(&dir(&state, "new").join("resourcepacks/Same.zip"), json!({ "min_format": 84, "max_format": 99 }), "same");
    std::fs::create_dir_all(dir(&state, "third").join("resourcepacks")).unwrap();
    std::fs::copy(dir(&state, "new").join("resourcepacks/Same.zip"), dir(&state, "third").join("resourcepacks/Same Copy.zip")).unwrap();
    exit(&state, "new");
    launch(&state, "third");
    exit(&state, "third");
    let library = packs::load_library(&store::root(&state.paths.root));
    assert_eq!(library.resourcepacks.len(), 1);
    assert_eq!(files_in(&packs::library_dir(&store::root(&state.paths.root)).join("resourcepacks")).len(), 1);
    // A file system that refuses clones: the pack is still placed, byte for byte.
    let refuse: packs::Copier = |_, _| Err(std::io::Error::other("cloning not supported"));
    launch_with(&state, "old", &Hooks { copier: refuse, ..Hooks::default() });
    assert_eq!(std::fs::read(dir(&state, "old").join("resourcepacks/Same.zip")).unwrap(), std::fs::read(dir(&state, "new").join("resourcepacks/Same.zip")).unwrap());
}

#[test]
fn flint_s_own_settings_follow_where_its_menu_is_and_a_newer_layout_is_never_replaced() {
    let (_dir, state) = setup();
    profile(&state, "new", "26.3", Some(FIXTURE_26_3), Some(2_000));
    profile(&state, "old", "26.2", Some(&options_26_2()), Some(1_000));
    profile(&state, "flint2", "26.3", Some(FIXTURE_26_3), Some(10));
    for id in ["new", "flint2"] {
        std::fs::create_dir_all(dir(&state, id).join("mods")).unwrap();
        std::fs::write(dir(&state, id).join("mods").join(crate::runtime::FILE_NAME), b"jar").unwrap();
        std::fs::create_dir_all(dir(&state, id).join("config/flintclient/layouts")).unwrap();
    }
    let config = |id: &str| dir(&state, id).join("config");
    let newer = b"{\"schema\":2,\"id\":\"mine\",\"name\":\"Mine\"}".to_vec();
    std::fs::write(config("flint2").join("flintclient/layouts/mine.json"), &newer).unwrap();
    std::fs::write(config("flint2").join("flintclient/control-center.json"), b"{\"schema\":1,\"library\":{\"recents\":[\"x\"]},\"sounds\":{\"volume\":0.2}}").unwrap();
    std::fs::write(config("new").join("flintclient/layouts/mine.json"), b"{\"schema\":1,\"id\":\"mine\",\"name\":\"Older\"}").unwrap();
    std::fs::write(config("new").join("flintclient/control-center.json"), b"{\"schema\":1,\"sounds\":{\"enabled\":true},\"library\":{\"favorites\":[],\"recents\":[\"y\"]}}").unwrap();
    std::fs::write(config("new").join("flintclient.json"), b"{\"shortcuts\":[]}").unwrap();
    switch(&state, true);
    for id in ["new", "old", "flint2"] {
        launch(&state, id);
        exit(&state, id);
    }
    // In 26.3 the player makes a layout, edits another, and changes Control Center preferences and shortcuts.
    std::fs::write(config("new").join("flintclient/layouts/pvp.json"), b"{\"schema\":1,\"id\":\"pvp\",\"name\":\"PvP\"}").unwrap();
    std::fs::write(config("new").join("flintclient/layouts/mine.json"), b"{\"schema\":1,\"id\":\"mine\",\"name\":\"Older 2\"}").unwrap();
    std::fs::write(config("new").join("flintclient/control-center.json"), b"{\"schema\":1,\"activeLayout\":\"pvp\",\"sounds\":{\"enabled\":false},\"library\":{\"favorites\":[\"fps\"],\"recents\":[\"y\"]}}").unwrap();
    std::fs::write(config("new").join("flintclient.json"), b"{\"shortcuts\":[{\"name\":\"Hub\",\"address\":\"hub.example.invalid\"}]}").unwrap();
    exit(&state, "new");
    let report = launch(&state, "flint2");
    let dst = config("flint2");
    assert_eq!(std::fs::read(dst.join("flintclient/layouts/pvp.json")).unwrap(), b"{\"schema\":1,\"id\":\"pvp\",\"name\":\"PvP\"}");
    assert_eq!(std::fs::read(dst.join("flintclient/layouts/mine.json")).unwrap(), newer, "a newer layout stays");
    assert!(report.deferred.contains_key("flint/layout/mine"), "{report:?}");
    let cc: Json = serde_json::from_slice(&std::fs::read(dst.join("flintclient/control-center.json")).unwrap()).unwrap();
    assert_eq!((cc["activeLayout"].clone(), cc["sounds"]["enabled"].clone(), cc["sounds"]["volume"].clone()), (json!("pvp"), json!(false), json!(0.2)));
    assert_eq!((cc["library"]["favorites"].clone(), cc["library"]["recents"].clone()), (json!(["fps"]), json!(["x"])), "recents are this profile's own");
    let shortcuts: Json = serde_json::from_slice(&std::fs::read(dst.join("flintclient.json")).unwrap()).unwrap();
    assert_eq!(shortcuts["shortcuts"][0]["name"], "Hub");
    // Without Flint's menu (26.2 here) the files wait instead of cluttering the profile.
    let report = launch(&state, "old");
    assert!(!dir(&state, "old").join("config/flintclient").exists());
    assert!(report.deferred.contains_key("flint/layout/pvp"));
}

#[test]
fn names_and_paths_from_the_store_can_t_reach_outside_the_profile() {
    let (_dir, state) = setup();
    pair(&state);
    resource_pack(&dir(&state, "new").join("resourcepacks/Fine.zip"), json!({ "min_format": 84, "max_format": 99 }), "fine");
    mod_jar(&state, "new", "xaero", "1.0");
    mod_jar(&state, "old", "xaero", "1.0");
    std::fs::create_dir_all(dir(&state, "new").join("config/xaero")).unwrap();
    std::fs::write(dir(&state, "new").join("config/xaero/x.txt"), b"a").unwrap();
    exit(&state, "new");
    // A damaged or tampered store: a pack name and a mod file pointing elsewhere.
    let mut st = stored(&state);
    let pack_key = st.entries.keys().find(|k| k.starts_with("packs/resourcepacks/") && !k.ends_with("selection")).unwrap().clone();
    st.entries.get_mut(&pack_key).unwrap().value = "../../escape.zip".into();
    let mod_entry = st.entries["mods/xaero/xaero/x.txt"].clone();
    st.entries.insert("mods/xaero/../../../../escape.txt".into(), Entry { rev: 999, ..mod_entry.clone() });
    st.entries.insert("mods/../../escape/x.txt".into(), Entry { rev: 999, ..mod_entry });
    st.next_revision = 1000;
    let generation = st.generation;
    store::save(&state.paths.root, &mut st, generation).unwrap();
    launch(&state, "old");
    let root = state.paths.root.clone();
    for outside in [root.join("escape.zip"), root.join("profiles/escape.zip"), root.join("escape.txt"), root.join("profiles/escape.txt"), root.join("escape")] {
        assert!(!outside.exists(), "{}", outside.display());
    }
    let placed = files_in(&dir(&state, "old").join("resourcepacks"));
    assert_eq!(placed.len(), 1);
    assert!(placed[0].starts_with("Pack "), "an unsafe name is replaced: {placed:?}");
}

#[cfg(unix)]
#[test]
fn links_are_never_captured_or_written_through() {
    let (dir_root, state) = setup();
    pair(&state);
    let outside = dir_root.0.join("shared-packs");
    std::fs::create_dir_all(&outside).unwrap();
    resource_pack(&outside.join("Shared.zip"), json!({ "min_format": 84, "max_format": 99 }), "shared");
    // In the origin, a linked pack: not taken.
    std::fs::create_dir_all(dir(&state, "new").join("resourcepacks")).unwrap();
    std::os::unix::fs::symlink(outside.join("Shared.zip"), dir(&state, "new").join("resourcepacks/Linked.zip")).unwrap();
    resource_pack(&dir(&state, "new").join("resourcepacks/Real.zip"), json!({ "min_format": 84, "max_format": 99 }), "real");
    let report = exit(&state, "new");
    assert_eq!(report.captured.iter().filter(|k| k.starts_with("packs/resourcepacks/") && !k.ends_with("selection")).count(), 1);
    // In the destination, the pack folder is a link to a shared folder: nothing is written through it.
    std::os::unix::fs::symlink(&outside, dir(&state, "old").join("resourcepacks")).unwrap();
    launch(&state, "old");
    assert_eq!(files_in(&outside), ["Shared.zip"], "the shared folder is untouched");
    assert!(std::fs::symlink_metadata(dir(&state, "old").join("resourcepacks")).unwrap().file_type().is_symlink());
}

// ---------- the three-way rules ----------

fn entry(value: &str, rev: u64, fp: &str) -> Entry {
    Entry { value: value.into(), rev, origin_profile: "x".into(), origin_family: Some("26.3".into()), updated_at: 0, fp: fp.into(), mod_version: None, introduced: false }
}

fn snap_with(items: &[(&str, &str)]) -> Snap {
    Snap {
        items: items.iter().map(|(k, fp)| (k.to_string(), Item::plain(fp.to_string(), fp.to_string()))).collect(),
        options: OptionsFile::Missing,
        options_sha: None,
        options_problem: false,
        packs: [(Folder::Resource, Vec::new()), (Folder::Shader, Vec::new())].into_iter().collect(),
        too_large: Vec::new(),
        iris: None,
        lists: Lists::default(),
    }
}

#[test]
fn a_profile_s_first_contact_decides_whether_it_is_taken_as_it_is() {
    let mut st = State::new();
    st.baseline_ids.insert("old".into());
    assert_eq!(start(&st, "old", "26.3", &None), Start::Baseline, "existed when Auto Sync was turned on");
    assert_eq!(start(&st, "later", "26.3", &None), Start::FirstContact, "created afterwards");
    let mut record = ProfileSync { store: st.tag(), generation: 3, ..ProfileSync::new(1) };
    st.generation = 3;
    assert_eq!(start(&st, "x", "26.3", &Some(record.clone())), Start::Normal);
    st.epoch += 1;
    assert_eq!(start(&st, "x", "26.3", &Some(record.clone())), Start::Baseline, "turned off and on again");
    record.store = st.tag();
    record.generation = 4;
    assert_eq!(start(&st, "x", "26.3", &Some(record.clone())), Start::Baseline, "a record from a run whose store never landed");
    record.generation = 3;
    record.pending_baseline = true;
    assert_eq!(start(&st, "x", "26.3", &Some(record)), Start::Baseline);
}

#[test]
fn only_entries_newer_than_the_profile_waiting_or_new_here_are_looked_at() {
    let mut st = State::new();
    st.entries.insert("options/fov".into(), entry("0.5", 5, "Int(90)"));
    st.entries.insert("options/gamma".into(), entry("1.0", 2, "Num(1.0)"));
    st.entries.insert("options/lang".into(), entry("de_de", 2, "Text(\"de_de\")"));
    st.entries.insert("mods/sodium/sodium-options.json".into(), entry("abc", 1, "f"));
    let mut sync = ProfileSync::new(3);
    sync.seen.insert("options/gamma".into(), "Num(0.5)".into());
    sync.seen.insert("options/fov".into(), "Int(70)".into());
    sync.deferred.insert("options/lang".into());
    let snap = snap_with(&[("options/gamma", "Num(0.5)"), ("options/fov", "Int(70)"), ("mods/sodium/sodium-options.json", "g")]);
    let keys = |captured: &[&str], first: bool| -> Vec<String> {
        let captured: BTreeSet<String> = captured.iter().map(|s| s.to_string()).collect();
        candidates(&st, &sync, &snap, &captured, first).into_iter().map(|(k, _)| k.clone()).collect()
    };
    // fov is newer; lang waits; Sodium's file appeared here since; gamma is old news.
    assert_eq!(keys(&[], false), ["mods/sodium/sodium-options.json", "options/fov", "options/lang"]);
    assert_eq!(keys(&["options/fov"], false), ["mods/sodium/sodium-options.json", "options/lang"], "what this profile just changed isn't brought back");
    assert_eq!(keys(&[], true).len(), 4, "a new profile gets everything");
}

#[test]
fn capture_takes_only_genuine_changes() {
    let (_dir, state) = setup();
    let hooks = Hooks::default();
    let ctx = Ctx { state: &state, store_root: store::root(&state.paths.root), hooks: &hooks };
    let view = View::load(&state, &Profile { id: "p".into(), name: "P".into(), game_version: "26.3".into(), loader: "fabric".into(), ..Default::default() });
    let mut st = State::new();
    st.entries.insert("options/gamma".into(), entry("1.0", 1, "Num(1.0)"));
    st.entries.insert("options/lang".into(), entry("de_de", 1, "Text(\"de_de\")"));
    let mut sync = ProfileSync::new(1);
    sync.seen.insert("options/fov".into(), "Int(70)".into());
    sync.seen.insert("options/gamma".into(), "Num(0.5)".into());
    sync.seen.insert("options/chatScale".into(), "Num(1.0)".into());
    let snap = snap_with(&[("options/fov", "Int(90)"), ("options/gamma", "Num(1.0)"), ("options/chatScale", "Num(1.0)"), ("options/lang", "Text(\"en_us\")"), ("options/biomeBlendRadius", "Int(3)")]);
    let captured = capture(&ctx, &mut st, &mut Library::default(), &view, &snap, &sync);
    // fov changed; gamma changed but to what the store already has; chatScale is unchanged; lang is new
    // here but the store has a value (that one comes in); biome blend is new everywhere.
    assert_eq!(captured, ["options/biomeBlendRadius", "options/fov"]);
    assert_eq!((st.entries["options/biomeBlendRadius"].rev, st.entries["options/fov"].rev), (1, 2), "in key order, from the store's counter");
    assert_eq!(st.entries["options/fov"].origin_profile, "p");
}

#[test]
fn a_merged_selection_keeps_what_this_profile_couldn_t_show() {
    let stored = r#"["a","only-new","b","never-here"]"#;
    let keep = |h: &str| h == "only-new" || h == "never-here";
    assert_eq!(merged_selection(Some(stored), &["b".into(), "c".into()], keep), ["only-new", "b", "never-here", "c"]);
    assert_eq!(merged_selection(Some(stored), &["c".into()], |_| false), ["c"], "dropped packs leave");
    assert_eq!(merged_selection(None, &["c".into()], keep), ["c"]);
}

// ---------- Settings → Syncing: switches, the game's lists, profiles left out ----------

use super::gamefiles::testdata::{hotbar, server, servers_dat};
use super::nbt::{Compound, Tag};

/// Settings → Syncing: one switch, starting from the most recently played profile.
fn turn(state: &AppState, switch: Switch, on: bool) {
    set_switch(state, switch, on, None, &quiet(), &Hooks::default()).unwrap();
}

fn put_servers(state: &AppState, id: &str, servers: &[Compound]) {
    std::fs::write(dir(state, id).join("servers.dat"), servers_dat(servers)).unwrap();
}

fn servers_in(state: &AppState, id: &str) -> Vec<Compound> {
    gamefiles::read_servers(&std::fs::read(dir(state, id).join("servers.dat")).unwrap()).unwrap().servers.into_iter().map(|s| s.tag).collect()
}

fn server_names(state: &AppState, id: &str) -> Vec<String> {
    servers_in(state, id).iter().map(|t| String::from_utf8_lossy(t.text("name").unwrap()).into_owned()).collect()
}

fn server_named(state: &AppState, id: &str, name: &str) -> Compound {
    servers_in(state, id).into_iter().find(|t| t.text("name") == Some(name.as_bytes())).unwrap()
}

fn touches_servers(keys: &[String]) -> bool {
    keys.iter().any(|k| k.starts_with(SERVERS))
}

#[test]
fn servers_wait_for_their_switch_then_flow_between_every_version_from_then_on() {
    let (_dir, state) = setup();
    pair(&state);
    let hub = server("Hub", "hub.example.invalid", &[("hidden", Tag::Byte(0))]);
    put_servers(&state, "new", std::slice::from_ref(&hub));
    // Off until turned on: nothing about servers is taken or written.
    assert!(!touches_servers(&exit(&state, "new").captured));
    assert!(!touches_servers(&launch(&state, "old").applied));
    assert!(!dir(&state, "old").join("servers.dat").exists());
    put_servers(&state, "old", &[server("Mine", "mine.invalid", &[])]);
    exit(&state, "old");

    // Turned on, it starts from the most recently played profile, and every profile keeps its own list.
    turn(&state, Switch::Servers, true);
    let st = stored(&state);
    assert_eq!(st.switches["servers"].source.as_deref(), Some("new"));
    assert!(st.entries.contains_key(SERVER_ORDER));
    for id in ["old", "new"] {
        let report = launch(&state, id);
        assert!(!touches_servers(&report.applied) && !touches_servers(&report.captured), "{id}: {report:?}");
        exit(&state, id);
    }
    assert_eq!(server_names(&state, "old"), ["Mine"], "nothing overwritten when it was turned on");

    // From then on, changes flow: 26.3 adds a server (26.x writes the code of conduct it accepted) and joins one
    // by address, which Minecraft keeps hidden.
    let lobby = server("Lobby", "lobby.invalid", &[("hidden", Tag::Byte(0)), ("acceptedCodeOfConduct", Tag::Int(2))]);
    let direct = server("Direct", "direct.invalid", &[("hidden", Tag::Byte(1))]);
    put_servers(&state, "new", &[hub.clone(), lobby.clone(), direct.clone()]);
    let report = exit(&state, "new");
    assert_eq!(report.captured.iter().filter(|k| k.starts_with(SERVERS)).count(), 3, "two servers and the order: {report:?}");
    let report = launch(&state, "old");
    assert!(report.backup.is_some());
    assert_eq!(server_names(&state, "old"), ["Mine", "Lobby", "Direct"], "its own server keeps its place");
    assert!(gamefiles::hidden(&server_named(&state, "old", "Direct")));
    exit(&state, "old");

    // A 1.8.9 profile made now gets the whole list, but never a hidden server, which it would show.
    profile(&state, "legacy", "1.8.9", Some(OPTIONS_1_8_9), Some(400));
    launch(&state, "legacy");
    assert_eq!(server_names(&state, "legacy"), ["Hub", "Lobby"]);
    exit(&state, "legacy");
    // 1.8.9 saves the list its own way (without the tags it doesn't know): not a change. Then the player renames
    // a server there.
    put_servers(&state, "legacy", &[server("Hub", "hub.example.invalid", &[]), server("Lobby", "lobby.invalid", &[])]);
    assert!(!touches_servers(&exit(&state, "legacy").captured));
    put_servers(&state, "legacy", &[server("Hub", "hub.example.invalid", &[]), server("Lobby EU", "lobby.invalid", &[])]);
    assert_eq!(exit(&state, "legacy").captured.len(), 1);
    launch(&state, "new");
    assert_eq!(server_names(&state, "new"), ["Hub", "Lobby EU", "Direct"]);
    let renamed = server_named(&state, "new", "Lobby EU");
    assert_eq!((renamed.get("acceptedCodeOfConduct"), renamed.get("hidden")), (Some(&Tag::Int(2)), Some(&Tag::Byte(0))), "what only 26.x writes stays");
    exit(&state, "new");

    // A server removed in 26.3 leaves the other lists.
    put_servers(&state, "new", &[renamed, direct]);
    exit(&state, "new");
    launch(&state, "legacy");
    assert_eq!(server_names(&state, "legacy"), ["Lobby EU"]);
    launch(&state, "old");
    assert_eq!(server_names(&state, "old"), ["Mine", "Lobby EU", "Direct"]);
    // Nothing more to do anywhere.
    assert!(launch(&state, "legacy").applied.is_empty() && launch(&state, "old").applied.is_empty());
}

#[test]
fn two_profiles_adding_different_servers_both_keep_both() {
    let (_dir, state) = setup();
    turn(&state, Switch::Servers, true);
    pair(&state);
    put_servers(&state, "new", &[server("A", "a.invalid", &[])]);
    exit(&state, "new");
    put_servers(&state, "old", &[server("B", "b.invalid", &[])]);
    exit(&state, "old");
    launch(&state, "new");
    launch(&state, "old");
    assert_eq!(server_names(&state, "new"), ["A", "B"]);
    assert_eq!(server_names(&state, "old"), ["A", "B"]);
}

#[test]
fn a_server_list_that_can_t_be_read_is_never_written_over() {
    let (_dir, state) = setup();
    turn(&state, Switch::Servers, true);
    pair(&state);
    put_servers(&state, "new", &[server("A", "a.invalid", &[])]);
    exit(&state, "new");
    let damaged = servers_dat(&[server("Mine", "mine.invalid", &[])])[..20].to_vec();
    std::fs::write(dir(&state, "old").join("servers.dat"), &damaged).unwrap();
    let report = launch(&state, "old");
    assert!(report.deferred.keys().any(|k| k.starts_with(SERVERS)), "{report:?}");
    assert_eq!(std::fs::read(dir(&state, "old").join("servers.dat")).unwrap(), damaged);
}

#[test]
fn hotbars_go_to_the_same_or_a_newer_version_and_never_to_1_8_9() {
    let (_dir, state) = setup();
    profile(&state, "a", "26.3", Some(FIXTURE_26_3), Some(300));
    profile(&state, "b", "26.3", Some(FIXTURE_26_3), Some(200));
    profile(&state, "c", "1.21.1", Some(b"version:3955\nfov:0.0\n"), Some(100));
    profile(&state, "d", "1.8.9", Some(OPTIONS_1_8_9), Some(50));
    std::fs::write(dir(&state, "a").join("hotbar.nbt"), hotbar(5023, "minecraft:diamond")).unwrap();
    std::fs::write(dir(&state, "c").join("hotbar.nbt"), hotbar(3955, "minecraft:dirt")).unwrap();
    turn(&state, Switch::Hotbars, true);
    switch(&state, true);
    assert!(stored(&state).entries.contains_key(HOTBARS_KEY), "started from the latest played profile");
    for id in ["a", "b", "c", "d"] {
        launch(&state, id);
        exit(&state, id);
    }
    assert!(!dir(&state, "b").join("hotbar.nbt").exists(), "nothing overwritten or added when it was turned on");
    // Saved again in 26.3: another 26.3 profile takes it, 1.21.1 can't read 26.3's items, 1.8.9 has no hotbars.
    let saved = hotbar(5023, "minecraft:emerald");
    std::fs::write(dir(&state, "a").join("hotbar.nbt"), &saved).unwrap();
    assert_eq!(exit(&state, "a").captured, [HOTBARS_KEY]);
    launch(&state, "b");
    assert_eq!(std::fs::read(dir(&state, "b").join("hotbar.nbt")).unwrap(), saved);
    let report = launch(&state, "c");
    assert!(report.deferred[HOTBARS_KEY].contains("newer"), "{report:?}");
    assert_eq!(std::fs::read(dir(&state, "c").join("hotbar.nbt")).unwrap(), hotbar(3955, "minecraft:dirt"));
    let report = launch(&state, "d");
    assert!(!report.deferred.contains_key(HOTBARS_KEY) && !dir(&state, "d").join("hotbar.nbt").exists());
    exit(&state, "c");
    // Saved in 1.21.1: Minecraft 26.3 upgrades older hotbars as it loads them, so they come in there.
    let older = hotbar(3955, "minecraft:stone");
    std::fs::write(dir(&state, "c").join("hotbar.nbt"), &older).unwrap();
    exit(&state, "c");
    launch(&state, "a");
    assert_eq!(std::fs::read(dir(&state, "a").join("hotbar.nbt")).unwrap(), older);
}

#[test]
fn command_history_is_shared_from_1_21_on_and_a_cut_short_file_isn_t_taken() {
    let (_dir, state) = setup();
    profile(&state, "new", "26.3", Some(FIXTURE_26_3), Some(300));
    profile(&state, "mid", "1.21.11", Some(OPTIONS_1_21_11), Some(200));
    profile(&state, "legacy", "1.8.9", Some(OPTIONS_1_8_9), Some(100));
    turn(&state, Switch::CommandHistory, true);
    switch(&state, true);
    for id in ["new", "mid", "legacy"] {
        launch(&state, id);
        exit(&state, id);
    }
    // A server's login command carries a password: it never leaves the profile it was typed in.
    let history = b"/gamemode creative\r\n/login hunter2\r\n/tp 0 64 0\r\n".to_vec();
    std::fs::write(dir(&state, "new").join("command_history.txt"), &history).unwrap();
    std::fs::write(dir(&state, "mid").join("command_history.txt"), b"/login mine\r\n").unwrap();
    assert_eq!(exit(&state, "new").captured, [COMMANDS_KEY]);
    let stored_copy = std::fs::read(store::root(&state.paths.root).join("files/commands").join(&stored(&state).entries[COMMANDS_KEY].value)).unwrap();
    assert_eq!(stored_copy, b"/gamemode creative\r\n/tp 0 64 0\r\n");
    launch(&state, "mid");
    assert_eq!(std::fs::read(dir(&state, "mid").join("command_history.txt")).unwrap(), b"/gamemode creative\r\n/tp 0 64 0\r\n/login mine\r\n", "its own login command stays");
    launch(&state, "new");
    assert_eq!(std::fs::read(dir(&state, "new").join("command_history.txt")).unwrap(), history, "the origin isn't rewritten");
    // Typing only a login command isn't a change to share.
    std::fs::write(dir(&state, "new").join("command_history.txt"), [history.as_slice(), b"/l hunter2\r\n"].concat()).unwrap();
    assert!(exit(&state, "new").captured.is_empty());
    launch(&state, "legacy");
    assert!(!dir(&state, "legacy").join("command_history.txt").exists(), "1.8.9 keeps no command history");
    // The game crashed while writing it: the cut-short file isn't taken.
    std::fs::write(dir(&state, "new").join("command_history.txt"), b"/gamemode creative\r\n/tp 0 64 0\r\n/time set da").unwrap();
    let report = after_exit_with(&state, "new", Exit::Unclean, &quiet(), &Hooks::default());
    assert!(!report.captured.contains(&COMMANDS_KEY.to_owned()), "{report:?}");
    // A history that isn't UTF-8 text can't be read, so the shared one never replaces it.
    let foreign = b"/msg caf\xE9\r\n".to_vec();
    std::fs::write(dir(&state, "mid").join("command_history.txt"), &foreign).unwrap();
    std::fs::write(dir(&state, "new").join("command_history.txt"), b"/gamemode creative\r\n/tp 0 64 0\r\n/weather clear\r\n").unwrap();
    assert_eq!(exit(&state, "new").captured, [COMMANDS_KEY]);
    launch(&state, "mid");
    assert_eq!(std::fs::read(dir(&state, "mid").join("command_history.txt")).unwrap(), foreign);
}

#[test]
fn a_switch_turned_off_leaves_its_kind_alone_while_the_others_still_flow() {
    let (_dir, state) = setup();
    pair(&state);
    turn(&state, Switch::GameOptions, false);
    turn(&state, Switch::ResourcePacks, false);
    resource_pack(&dir(&state, "new").join("resourcepacks/Pack.zip"), json!({ "min_format": 84, "max_format": 99 }), "p");
    play(&state, "new", &[("fov", "0.5"), ("key_key.jump", "key.keyboard.v"), ("resourcePacks", r#"["vanilla","file/Pack.zip"]"#)]);
    assert_eq!(exit(&state, "new").captured, ["options/key_key.jump"], "key bindings have their own switch");
    let fov_before = options(&state, "old").get("fov").map(str::to_owned);
    launch(&state, "old");
    assert_eq!(options(&state, "old").get("key_key.jump"), Some("key.keyboard.v"));
    assert_eq!(options(&state, "old").get("fov").map(str::to_owned), fov_before);
    assert!(!dir(&state, "old").join("resourcepacks/Pack.zip").exists());
    exit(&state, "old");
    // Turned back on: every profile is taken as it is for it, then later changes flow again.
    turn(&state, Switch::GameOptions, true);
    for id in ["old", "new"] {
        assert!(launch(&state, id).applied.iter().all(|k| Switch::of(k) != Switch::GameOptions), "{id}");
        exit(&state, id);
    }
    assert_eq!(options(&state, "old").get("fov").map(str::to_owned), fov_before, "nothing overwritten when it came back");
    play(&state, "new", &[("gamma", "0.9")]);
    exit(&state, "new");
    launch(&state, "old");
    assert_eq!(options(&state, "old").get("gamma"), Some("0.9"));
    assert_eq!(options(&state, "old").get("fov").map(str::to_owned), fov_before);
}

#[test]
fn a_profile_left_out_is_never_read_or_written_and_comes_back_as_it_is() {
    let (_dir, state) = setup();
    turn(&state, Switch::Servers, true);
    pair(&state);
    set_excluded(&state, "old", true).unwrap();
    let old_options = std::fs::read(dir(&state, "old").join("options.txt")).unwrap();
    play(&state, "new", &[("fov", "0.5")]);
    put_servers(&state, "new", &[server("A", "a.invalid", &[])]);
    exit(&state, "new");
    assert_eq!(launch(&state, "old").skipped, Some("left out"));
    assert_eq!(std::fs::read(dir(&state, "old").join("options.txt")).unwrap(), old_options);
    assert!(!dir(&state, "old").join("servers.dat").exists());
    assert!(crate::gamesettings::backup::list(&dir(&state, "old")).is_empty());
    play(&state, "old", &[("gamma", "0.9")]);
    assert_eq!(exit(&state, "old").skipped, Some("left out"));
    launch(&state, "new");
    assert_ne!(options(&state, "new").get("gamma"), Some("0.9"), "its changes aren't taken");
    assert!(!overview(&state).profiles.iter().find(|p| p.id == "old").unwrap().can_start);
    // Put back: taken as it is, so neither side's changes made meanwhile cross over.
    set_excluded(&state, "old", false).unwrap();
    let report = launch(&state, "old");
    assert!(report.applied.is_empty() && report.captured.is_empty(), "{report:?}");
    assert_eq!(std::fs::read(dir(&state, "old").join("options.txt")).unwrap(), edited(&old_options, &[("gamma", "0.9")]));
    exit(&state, "old");
    launch(&state, "new");
    assert_ne!(options(&state, "new").get("gamma"), Some("0.9"), "what changed while it was out stays there");
    // Changes made from then on flow both ways again.
    play(&state, "new", &[("mouseSensitivity", "0.75")]);
    exit(&state, "new");
    launch(&state, "old");
    assert_eq!(options(&state, "old").get("mouseSensitivity"), Some("0.75"));
    assert_ne!(options(&state, "old").get("fov"), Some("0.5"));
}

#[test]
fn a_running_game_s_server_list_is_never_written() {
    let (_dir, state) = setup();
    turn(&state, Switch::Servers, true);
    pair(&state);
    put_servers(&state, "new", &[server("A", "a.invalid", &[])]);
    exit(&state, "new");
    let elsewhere = ProcessScan::from(Ok(vec![format!("9 /usr/bin/java Main --gameDir {}", dir(&state, "old").display())]));
    assert_eq!(before_launch_with(&state, &find(&state, "old"), &elsewhere, &Hooks::default()).skipped, Some("running"));
    assert!(!dir(&state, "old").join("servers.dat").exists());
    // A game Flint started is running in it: its exit report is ignored too.
    state.running.lock().unwrap().insert("old".into(), RunningGame { kill: None });
    assert_eq!(exit(&state, "old").skipped, Some("busy"));
    state.running.lock().unwrap().clear();
    launch(&state, "old");
    assert_eq!(server_names(&state, "old"), ["A"], "brought in at its next launch");
}

#[test]
fn a_failed_write_puts_the_server_list_back_and_leaves_no_hotbar_behind() {
    let (_dir, state) = setup();
    turn(&state, Switch::Servers, true);
    turn(&state, Switch::Hotbars, true);
    profile(&state, "new", "26.3", Some(FIXTURE_26_3), Some(2_000));
    profile(&state, "other", "26.3", Some(FIXTURE_26_3), Some(1_000));
    switch(&state, true);
    for id in ["new", "other"] {
        launch(&state, id);
        exit(&state, id);
    }
    let own = servers_dat(&[server("Mine", "mine.invalid", &[])]);
    std::fs::write(dir(&state, "other").join("servers.dat"), &own).unwrap();
    exit(&state, "other");
    put_servers(&state, "new", &[server("A", "a.invalid", &[])]);
    std::fs::write(dir(&state, "new").join("hotbar.nbt"), hotbar(5023, "minecraft:diamond")).unwrap();
    exit(&state, "new");
    let report = launch_with(&state, "other", &Hooks { fail_rename: Some(1), ..Hooks::default() });
    assert_eq!(report.skipped, Some("failed"));
    assert_eq!(std::fs::read(dir(&state, "other").join("servers.dat")).unwrap(), own, "put back");
    assert!(!dir(&state, "other").join("hotbar.nbt").exists() && !files_in(&dir(&state, "other")).iter().any(|n| n.ends_with(".flint-tmp")));
    let backup = crate::gamesettings::backup::list(&dir(&state, "other")).into_iter().next().unwrap();
    assert!(backup.files.iter().any(|f| f.path == "servers.dat" && f.existed) && backup.files.iter().any(|f| f.path == "hotbar.nbt" && !f.existed));
    // The next launch succeeds.
    launch(&state, "other");
    assert_eq!(server_names(&state, "other"), ["Mine", "A"]);
    assert_eq!(std::fs::read(dir(&state, "other").join("hotbar.nbt")).unwrap(), hotbar(5023, "minecraft:diamond"));
}

#[test]
fn turning_on_starts_from_the_profile_picked_and_refuses_one_it_can_t_read() {
    let (_dir, state) = setup();
    profile(&state, "a", "26.3", Some(&edited(FIXTURE_26_3, &[("fov", "0.25")])), Some(100));
    profile(&state, "b", "26.3", Some(&edited(FIXTURE_26_3, &[("fov", "0.5")])), Some(300));
    profile(&state, "fresh", "26.2", None, None);
    crate::core::write_json(&state.paths.settings(), &json!({ "autoSync": true })).unwrap();
    let error = set_enabled_from(&state, true, Some("fresh"), &quiet(), &Hooks::default()).unwrap_err();
    assert!(error.contains("‘Profile fresh’") && error.contains("hasn't been played yet"), "{error}");
    assert!(matches!(store::load(&state.paths.root), Loaded::Missing), "nothing started");
    set_enabled_from(&state, true, Some("a"), &quiet(), &Hooks::default()).unwrap();
    assert_eq!(stored(&state).seeded_from.as_deref(), Some("a"));
    assert_eq!(stored(&state).entries["options/fov"].value, "0.25");
    assert_eq!(overview(&state).started_from.as_deref(), Some("a"));
    // A switch too: one whose game is running can't be read safely.
    put_servers(&state, "a", &[server("A", "a.invalid", &[])]);
    state.running.lock().unwrap().insert("a".into(), RunningGame { kill: None });
    let error = set_switch(&state, Switch::Servers, true, Some("a"), &quiet(), &Hooks::default()).unwrap_err();
    assert!(error.contains("its game is running"), "{error}");
    assert!(!switches::load(&store::root(&state.paths.root)).on(Switch::Servers), "it stays off");
    state.running.lock().unwrap().clear();
    set_switch(&state, Switch::Servers, true, Some("a"), &quiet(), &Hooks::default()).unwrap();
    let view = overview(&state);
    let servers = view.switches.iter().find(|s| s.id == "servers").unwrap();
    assert!(servers.on && servers.started_from.as_deref() == Some("a"));
    assert_eq!(view.default_source.as_deref(), Some("b"), "the most recently played readable profile");
    assert!(!view.profiles.iter().find(|p| p.id == "fresh").unwrap().can_start);
    // A profile made afterwards gets what the switch started from.
    profile(&state, "later", "26.3", Some(FIXTURE_26_3), None);
    launch(&state, "later");
    assert_eq!(server_names(&state, "later"), ["A"]);
    let later = overview(&state);
    let row = later.profiles.iter().find(|p| p.id == "later").unwrap();
    assert!(row.backups >= 1 && row.last_backup.is_some());
    assert_eq!(later.last_sync.map(|l| l.profile).as_deref(), Some("later"));
}

#[test]
fn an_imported_profile_keeps_its_own_settings_then_syncs_like_the_others() {
    let (_dir, state) = setup();
    pair(&state);
    play(&state, "new", &[("fov", "0.5")]);
    exit(&state, "new");
    // A profile imported from another launcher arrives with options of its own.
    let own = edited(FIXTURE_26_3, &[("fov", "0.9"), ("key_key.jump", "key.keyboard.v")]);
    profile(&state, "imported", "26.3", Some(&own), None);
    take_as_is(&state, "imported");
    let report = launch(&state, "imported");
    assert!(report.applied.is_empty(), "nothing replaces what it came with: {report:?}");
    assert_eq!(options(&state, "imported").get("fov"), Some("0.9"));
    assert_eq!(options(&state, "imported").get("key_key.jump"), Some("key.keyboard.v"));
    exit(&state, "imported");
    // From then on, changes flow both ways as for any profile.
    play(&state, "new", &[("gamma", "0.9")]);
    exit(&state, "new");
    launch(&state, "imported");
    assert_eq!(options(&state, "imported").get("gamma"), Some("0.9"));
    assert_eq!(options(&state, "imported").get("fov"), Some("0.9"));
}
