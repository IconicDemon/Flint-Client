//! The import engine, tested without Tauri over synthetic launchers in temporary folders. Each launcher's own format is
//! tested in its adapter's file; here a small fake launcher (`Fake`) lists profiles from a JSON file.

use super::adapters::{self, Adapter, Found};
use super::scan::{self, Limits};
use super::*;
use crate::core::TestDir;
use std::cell::Cell;
use std::io::Write;

// ---------- fixtures ----------

/// A launcher for engine tests: `<appdata>/<folder>/fake-launcher.json` lists `{ id?, name, version?, loader, dir, played? }`.
pub struct Fake {
    pub id: LauncherId,
    pub name: &'static str,
    pub folder: &'static str,
}

impl Adapter for Fake {
    fn id(&self) -> LauncherId {
        self.id
    }
    fn name(&self) -> &'static str {
        self.name
    }
    fn locations(&self, env: &Env) -> Vec<PathBuf> {
        env.appdata.iter().map(|a| a.join(self.folder)).collect()
    }
    fn read(&self, root: &Path, _env: &Env) -> Found {
        let value = match adapters::read_json(&root.join("fake-launcher.json")) {
            None => return Found::NotHere,
            Some(Err(why)) => return Found::Unreadable(why),
            Some(Ok(value)) => value,
        };
        let Some(list) = value["profiles"].as_array() else { return Found::Unreadable("fake-launcher.json lists no profiles.".into()) };
        Found::Profiles(
            list.iter()
                .map(|p| {
                    let dir = root.join(p["dir"].as_str().unwrap_or("missing"));
                    let mut source = Source::new(self.id, p["name"].as_str().unwrap_or("?"), dir);
                    source.id = p["id"].as_str().map(str::to_owned);
                    source.game_version = p["version"].as_str().map(str::to_owned);
                    source.loader = serde_json::from_value(p["loader"].clone()).unwrap_or(ImportLoader::Unknown);
                    source.last_played = p["played"].as_u64();
                    if p.get("damaged").is_some() {
                        source.problem = Some("Its instance file is damaged.".into());
                    }
                    source
                })
                .collect(),
        )
    }
}

const PRISMISH: Fake = Fake { id: LauncherId::Prism, name: "Prism Launcher", folder: "PrismLauncher" };
const CURSEISH: Fake = Fake { id: LauncherId::Curseforge, name: "CurseForge", folder: "curseforge" };
const LUNARISH: Fake = Fake { id: LauncherId::Lunar, name: "Lunar Client", folder: ".lunarclient" };

fn env(dir: &Path) -> Env {
    Env {
        home: Some(dir.join("home")),
        appdata: Some(dir.join("appdata")),
        local: Some(dir.join("local")),
        config: Some(dir.join("config")),
        documents: Some(dir.join("home").join("Documents")),
        temp: dir.join("tmp"),
        flint_root: Some(dir.join("flint")),
        vars: HashMap::new(),
        fixed_places: false,
    }
}

/// A test computer: launchers' folders and Flint's data, all in one temporary folder.
pub struct World {
    _dir: TestDir,
    pub root: PathBuf,
    pub env: Env,
    pub state: AppState,
}

impl World {
    pub fn new() -> Self {
        let dir = TestDir::new();
        let root = dir.0.clone();
        let env = env(&root);
        std::fs::create_dir_all(root.join("flint")).unwrap();
        let state = AppState::for_tests(root.join("flint"));
        Self { _dir: dir, root, env, state }
    }

    fn launcher(&self, fake: &Fake) -> PathBuf {
        self.root.join("appdata").join(fake.folder)
    }

    /// Writes a fake launcher's profile list and a fake sign-in next to it (never to be copied).
    fn profiles(&self, fake: &Fake, profiles: serde_json::Value) -> PathBuf {
        let root = self.launcher(fake);
        write(&root, "fake-launcher.json", serde_json::json!({ "profiles": profiles }).to_string().as_bytes());
        write(&root, "accounts.json", br#"{"accounts":[{"token":"secret-token"}]}"#);
        root
    }

    pub fn existing(&self) -> ProfilesFile {
        load_profiles(&self.state)
    }

    fn list(&self, adapters: &[&dyn Adapter], fake: &Fake) -> Listed {
        list(adapters, fake.id, None, &self.env, &self.existing()).unwrap()
    }
}

pub fn write(dir: &Path, rel: &str, bytes: &[u8]) {
    let path = scan::join(dir, rel);
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, bytes).unwrap();
}

pub fn jar(dir: &Path, rel: &str, entries: &[(&str, &str)]) {
    let path = scan::join(dir, rel);
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
    for (name, text) in entries {
        zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(text.as_bytes()).unwrap();
    }
    zip.finish().unwrap();
}

pub fn fabric_jar(dir: &Path, rel: &str) {
    jar(dir, rel, &[("fabric.mod.json", r#"{"schemaVersion":1,"id":"m","version":"1"}"#), ("m/Mod.class", "bytes")]);
}

/// A full game folder: settings, packs, servers, Fabric mods (and one Forge mod), mod settings, mod data, two worlds,
/// and the things never copied (logs, crash reports, a sign-in, launcher files, caches, a running world's lock).
fn game_folder(dir: &Path) {
    write(dir, "options.txt", b"version:5023\nfov:0.5\nkey_key.jump:key.keyboard.space\nkey_key.sneak:key.keyboard.left.shift\n");
    write(dir, "optionsof.txt", b"ofFogType:1\n");
    write(dir, "servers.dat", b"\x0a\x00\x00servers");
    fabric_jar(dir, "mods/sodium.jar");
    fabric_jar(dir, "mods/zoom.jar.disabled");
    jar(dir, "mods/forge-only.jar", &[("META-INF/mods.toml", "modLoader=\"javafml\"")]);
    write(dir, "mods/notes.txt", b"not a mod");
    write(dir, "mods/flint-runtime.jar", b"flint's own");
    write(dir, "config/sodium-options.json", b"{\"quality\":1}");
    write(dir, "config/deep/a/b/c.toml", b"x=1");
    write(dir, "config/authme.json", b"{\"token\":\"secret\"}");
    write(dir, "config/.hidden.json", b"{}");
    write(dir, "resourcepacks/Faithful.zip", &[7u8; 3000]);
    write(dir, "resourcepacks/Folder Pack/pack.mcmeta", b"{\"pack\":{}}");
    write(dir, "resourcepacks/Folder Pack/assets/minecraft/textures/token.png", b"png");
    write(dir, "shaderpacks/Complementary.zip", b"shader");
    write(dir, "XaeroWaypoints/Multiplayer_server/waypoints.txt", b"waypoint");
    write(dir, "saves/Survival/level.dat", b"level");
    write(dir, "saves/Survival/session.lock", b"lock");
    write(dir, "saves/Survival/region/r.0.0.mca", &[1u8; 70_000]);
    write(dir, "saves/Creative/level.dat", b"level2");
    write(dir, "saves/not-a-world/readme.txt", b"no level.dat");
    // Never copied.
    write(dir, "logs/latest.log", b"log");
    write(dir, "crash-reports/crash.txt", b"crash");
    write(dir, "screenshots/2026.png", b"png");
    write(dir, "launcher_accounts.json", br#"{"accessToken":"secret"}"#);
    write(dir, "launcher_msa_credentials.bin", b"secret");
    write(dir, "essential/microsoft_accounts.json", b"secret");
    write(dir, "versions/1.21.1/1.21.1.jar", b"jar");
    write(dir, "libraries/x.jar", b"jar");
    write(dir, "assets/indexes/x.json", b"{}");
    write(dir, ".fabric/cache.bin", b"cache");
    // Private data mods and the game keep in a game folder: an account switcher's sign-ins, typed commands.
    write(dir, "_IAS_ACCOUNTS_DO_NOT_SEND_TO_ANYONE/accounts.dat", b"secret account");
    write(dir, "command_history.txt", b"/login secret-password");
    write(dir, "config/_IAS_ACCOUNTS_DO_NOT_SEND_TO_ANYONE/accounts.dat", b"secret account");
    write(dir, "XaeroWaypoints/command_history.txt", b"/login secret-password");
    write(dir, "config/essential/microsoft_accounts.json", b"secret");
}

/// Every file under `dir` (relative path, size, SHA-256), links included as links: the whole tree's fingerprint.
fn tree(dir: &Path) -> Vec<(String, String)> {
    fn walk(dir: &Path, prefix: &str, out: &mut Vec<(String, String)>) {
        let Ok(entries) = std::fs::read_dir(dir) else { return };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            let rel = if prefix.is_empty() { name } else { format!("{prefix}/{name}") };
            let meta = std::fs::symlink_metadata(entry.path()).unwrap();
            if scan::is_link(&meta) {
                out.push((rel, "link".into()));
            } else if meta.is_dir() {
                out.push((rel.clone(), "dir".into()));
                walk(&entry.path(), &rel, out);
            } else {
                let bytes = std::fs::read(entry.path()).unwrap();
                out.push((rel, format!("{}:{}", bytes.len(), hex::encode(Sha256::digest(&bytes)))));
            }
        }
    }
    let mut out = Vec::new();
    walk(dir, "", &mut out);
    out.sort();
    out
}

fn files_in(dir: &Path) -> Vec<String> {
    tree(dir).into_iter().filter(|(_, kind)| kind != "dir").map(|(rel, _)| rel).collect()
}

fn request(launcher: LauncherId, keys: Vec<String>, worlds: bool) -> ImportRequest {
    ImportRequest { launcher, folder: None, keys, worlds }
}

/// Runs an import with `free` bytes free; `cancel` sees every progress report and returns true to cancel.
fn import(world: &World, listed: &Listed, request: &ImportRequest, limits: &Limits, free: Option<u64>, mut cancel: impl FnMut(&ImportProgress) -> bool) -> (Outcome, Vec<ImportProgress>) {
    let stop = Cell::new(false);
    let mut seen: Vec<ImportProgress> = Vec::new();
    let mut progress = |p: &ImportProgress, important: bool| {
        if cancel(p) {
            stop.set(true);
        }
        if important {
            seen.push(p.clone());
        }
    };
    let cancelled = || stop.get();
    let free_space = move |_: &Path| free;
    let mut hooks = Hooks { cancelled: &cancelled, free_space: &free_space, progress: &mut progress };
    let outcome = run(&world.state, listed, request, "jtest", limits, &mut hooks);
    (outcome, seen)
}

fn finished(outcome: Outcome) -> ImportResult {
    match outcome {
        Outcome::Finished(result) => result,
        other => panic!("expected a finished import, got {other:?}"),
    }
}

fn staging_left(world: &World) -> Vec<String> {
    std::fs::read_dir(world.state.paths.root.join("profiles"))
        .map(|d| d.flatten().map(|e| e.file_name().to_string_lossy().to_string()).filter(|n| n.ends_with(".flint-importing")).collect())
        .unwrap_or_default()
}

/// Everything on the test computer outside Flint's data and the temporary folder: the launchers' side.
fn launchers_side(world: &World) -> Vec<(String, String)> {
    tree(&world.root).into_iter().filter(|(rel, _)| !rel.starts_with("flint") && !rel.starts_with("tmp")).collect()
}

/// For adapter tests: finds `adapter`'s launcher on the test computer, previews and imports every profile Flint can
/// take, and checks what every import must hold: the launcher's files are byte for byte unchanged, no database copy is
/// left behind, nothing that looks like a sign-in reached Flint's data, nothing is half done, and every imported profile
/// now shows as imported.
pub fn import_everything(world: &World, adapter: &dyn Adapter, worlds: bool) -> (Listed, ImportResult) {
    let before = launchers_side(world);
    let listed = discover(adapter, &world.env, &world.existing()).expect("the launcher is found");
    let keys: Vec<String> = listed.listing.profiles.iter().filter(|p| p.support != ImportSupport::Unsupported).map(|p| p.key.clone()).collect();
    let request = ImportRequest { launcher: adapter.id(), folder: None, keys, worlds };
    let shown = preview(&listed, &world.existing(), &request, &Limits::default(), None);
    let result = finished(import(world, &listed, &request, &Limits::default(), None, |_| false).0);
    assert_eq!(launchers_side(world), before, "{}: the launcher's files are byte for byte what they were", adapter.name());
    assert_eq!(std::fs::read_dir(&world.env.temp).map(|d| d.count()).unwrap_or(0), 0, "no database copy is left behind");
    assert!(staging_left(world).is_empty());
    for (rel, kind) in tree(&world.state.paths.root) {
        if kind == "dir" || kind == "link" {
            continue;
        }
        let name = rel.rsplit('/').next().unwrap_or_default();
        let content = rel.contains("/resourcepacks/") || rel.contains("/shaderpacks/") || rel.contains("/saves/") || rel.contains("/mods/");
        assert!(content || !scan::sensitive(name), "{rel} looks like sign-in data");
        let text = String::from_utf8_lossy(&std::fs::read(scan::join(&world.state.paths.root, &rel)).unwrap()).to_string();
        assert!(!text.contains("secret"), "{rel} holds something that was secret");
    }
    // The preview's sizes are what was copied.
    for (p, r) in shown.profiles.iter().zip(&result.profiles) {
        if let Some(id) = &r.profile_id {
            let dir = world.state.paths.profile_dir(id);
            let copied: u64 = files_in(&dir).iter().map(|f| std::fs::metadata(scan::join(&dir, f)).unwrap().len()).sum();
            assert_eq!(copied, p.bytes, "{}: the preview was exact", p.name);
            assert_eq!(r.parts.iter().map(|x| x.bytes).sum::<u64>(), copied, "{}: the result's parts add up", p.name);
        }
    }
    let again = discover(adapter, &world.env, &world.existing()).unwrap();
    for r in result.profiles.iter().filter(|r| r.profile_id.is_some()) {
        let shown = again.listing.profiles.iter().find(|p| p.key == r.key).expect("still listed under the same key");
        assert_eq!(shown.imported.as_ref().map(|i| &i.profile_id), r.profile_id.as_ref());
    }
    (listed, result)
}

// ---------- discovery and listing ----------

#[test]
fn no_launchers_on_a_clean_computer() {
    let world = World::new();
    let all: Vec<&dyn Adapter> = vec![&PRISMISH, &CURSEISH, &LUNARISH];
    assert!(discover_all(&all, &world.env, &world.existing()).is_empty());
    // The real adapters find nothing in an empty home either.
    assert!(discover_all(&adapters::all(), &world.env, &world.existing()).is_empty());
    let listed = world.list(&all, &PRISMISH);
    assert_eq!(listed.listing.launcher.status, LauncherStatus::Empty);
    assert!(listed.listing.launcher.message.as_deref().is_some_and(|m| m.contains("didn't find")));
}

#[test]
fn launchers_are_listed_most_profiles_first() {
    let world = World::new();
    let prism = world.profiles(&PRISMISH, serde_json::json!([
        { "name": "One", "version": "26.3", "loader": "fabric", "dir": "a" },
        { "name": "Two", "version": "1.21.1", "loader": "forge", "dir": "b" },
        { "name": "Old", "version": "1.12.2", "loader": "forge", "dir": "c" },
    ]));
    for d in ["a", "b", "c"] {
        write(&prism, &format!("{d}/options.txt"), b"version:5023\n");
    }
    world.profiles(&CURSEISH, serde_json::json!([]));
    let lunar = world.profiles(&LUNARISH, serde_json::json!([{ "name": "PvP", "version": "1.8.9", "loader": "vanilla", "dir": "g" }]));
    write(&lunar, "g/options.txt", b"fov:0.5\n");
    let all: Vec<&dyn Adapter> = vec![&CURSEISH, &LUNARISH, &PRISMISH];
    let found = discover_all(&all, &world.env, &world.existing());
    let summary: Vec<(LauncherId, LauncherStatus, usize, usize)> = found.iter().map(|l| (l.id, l.status, l.profiles, l.importable)).collect();
    assert_eq!(
        summary,
        [
            (LauncherId::Prism, LauncherStatus::Found, 3, 2),
            (LauncherId::Lunar, LauncherStatus::Found, 1, 1),
            (LauncherId::Curseforge, LauncherStatus::Empty, 0, 0),
        ]
    );
    let json = serde_json::to_value(&found[0]).unwrap();
    assert_eq!(json, serde_json::json!({ "id": "prism", "name": "Prism Launcher", "status": "found", "profiles": 3, "importable": 2, "message": null }));
}

#[test]
fn listings_show_support_counts_and_newest_played_first() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([
        { "id": "a", "name": "Fabric PvP", "version": "26.3", "loader": "fabric", "dir": "a", "played": 100 },
        { "id": "b", "name": "Forge pack", "version": "1.21.1", "loader": "forge", "dir": "b", "played": 300 },
        { "id": "c", "name": "Snapshot", "version": "24w14a", "loader": "vanilla", "dir": "c", "played": 200 },
        { "id": "d", "name": "No version", "loader": "fabric", "dir": "d" },
        { "id": "e", "name": "Legacy", "version": "1.8.9", "loader": "fabric", "dir": "e" },
        { "id": "f", "name": "Broken", "version": "26.3", "loader": "fabric", "dir": "f", "damaged": true },
    ]));
    game_folder(&root.join("a"));
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let names: Vec<&str> = listed.listing.profiles.iter().map(|p| p.name.as_str()).collect();
    assert_eq!(names, ["Forge pack", "Snapshot", "Fabric PvP", "Broken", "Legacy", "No version"], "newest played first, then by name");
    let by = |name: &str| listed.listing.profiles.iter().find(|p| p.name == name).unwrap();
    let pvp = by("Fabric PvP");
    assert_eq!((pvp.support, pvp.mods, pvp.worlds), (ImportSupport::Full, 3, 2), "every jar, enabled or not (the Forge one too), but not Flint's runtime; two worlds");
    assert_eq!(by("Forge pack").support, ImportSupport::Settings);
    assert!(by("Forge pack").support_note.as_deref().unwrap().contains("Forge"));
    assert_eq!(by("Snapshot").support, ImportSupport::Unsupported);
    assert!(by("Snapshot").support_note.as_deref().unwrap().contains("24w14a"));
    assert_eq!(by("No version").support, ImportSupport::Unsupported);
    assert_eq!(by("Broken").support, ImportSupport::Unsupported);
    assert_eq!(by("Broken").support_note.as_deref(), Some("Its instance file is damaged."));
    assert_eq!(by("Legacy").support, ImportSupport::Full);
    assert!(by("Legacy").support_note.as_deref().unwrap().contains("Legacy Fabric"));
    assert_eq!(listed.listing.launcher.importable, 3);
    // Keys: stable, hex, nothing in clear text.
    let again = world.list(&[&PRISMISH], &PRISMISH);
    assert_eq!(listed.listing.profiles.iter().map(|p| &p.key).collect::<Vec<_>>(), again.listing.profiles.iter().map(|p| &p.key).collect::<Vec<_>>());
    assert!(listed.listing.profiles.iter().all(|p| p.key.len() == 32 && p.key.bytes().all(|b| b.is_ascii_hexdigit())));
    let json = serde_json::to_value(pvp).unwrap();
    for field in ["key", "name", "gameVersion", "loader", "loaderVersion", "lastPlayed", "icon", "mods", "worlds", "support", "supportNote", "imported", "sharedFolder"] {
        assert!(json.get(field).is_some(), "{field} is in the JSON");
    }
}

#[test]
fn a_damaged_launcher_is_unreadable_and_the_others_still_list() {
    let world = World::new();
    let broken = world.launcher(&PRISMISH);
    write(&broken, "fake-launcher.json", b"{ not json");
    let ok = world.profiles(&CURSEISH, serde_json::json!([{ "name": "Fine", "version": "26.3", "loader": "fabric", "dir": "x" }]));
    write(&ok, "x/options.txt", b"version:5023\n");
    let all: Vec<&dyn Adapter> = vec![&PRISMISH, &CURSEISH];
    let found = discover_all(&all, &world.env, &world.existing());
    let prism = found.iter().find(|l| l.id == LauncherId::Prism).unwrap();
    assert_eq!(prism.status, LauncherStatus::Unreadable);
    assert!(prism.message.as_deref().unwrap().contains("damaged"));
    let curse = found.iter().find(|l| l.id == LauncherId::Curseforge).unwrap();
    assert_eq!((curse.status, curse.profiles), (LauncherStatus::Found, 1));
}

#[test]
fn a_thousand_profiles_list_quickly() {
    let world = World::new();
    let root = world.launcher(&PRISMISH);
    let profiles: Vec<serde_json::Value> = (0..1000).map(|i| serde_json::json!({ "id": format!("p{i}"), "name": format!("Profile {i}"), "version": "1.21.11", "loader": "fabric", "dir": format!("i/{i}"), "played": i })).collect();
    for i in 0..1000 {
        write(&root, &format!("i/{i}/options.txt"), b"version:4671\n");
    }
    world.profiles(&PRISMISH, serde_json::Value::Array(profiles));
    let started = Instant::now();
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let took = started.elapsed();
    assert_eq!(listed.listing.profiles.len(), 1000);
    assert_eq!(listed.listing.profiles[0].name, "Profile 999");
    assert!(took < Duration::from_secs(10), "listing 1000 profiles took {took:?}");
    eprintln!("1000 profiles listed in {took:?}");
}

// ---------- preview ----------

#[test]
fn the_preview_says_exactly_what_comes_over() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([{ "id": "a", "name": "PvP", "version": "26.3", "loader": "fabric", "dir": "a" }]));
    game_folder(&root.join("a"));
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let key = listed.listing.profiles[0].key.clone();
    let without = preview(&listed, &world.existing(), &request(LauncherId::Prism, vec![key.clone()], false), &Limits::default(), Some(100 * scan::GB));
    let p = &without.profiles[0];
    assert_eq!(p.blocker, None);
    assert_eq!(p.target, Some(ImportTarget { name: "PvP".into(), game_version: "26.3".into(), loader: "fabric".into() }));
    let part = |id: ImportPart| p.parts.iter().find(|x| x.id == id).unwrap_or_else(|| panic!("{id:?} listed"));
    assert_eq!((part(ImportPart::Mods).status, part(ImportPart::Mods).count), (PartStatus::Attention, 2), "the Forge jar stays behind");
    assert_eq!(part(ImportPart::Keybinds).count, 2);
    assert!(p.parts.iter().all(|x| x.id != ImportPart::Worlds), "worlds aren't listed as coming over when they stay behind");
    assert_eq!(part(ImportPart::Options).files, 2);
    assert_eq!(part(ImportPart::Resourcepacks).count, 2);
    assert_eq!(part(ImportPart::Other).files, 1);
    assert!(p.skipped.iter().any(|s| s.path == "mods/forge-only.jar" && s.reason.contains("Forge")));
    assert!(p.skipped.iter().any(|s| s.path == "config/authme.json" && s.reason == scan::SENSITIVE));
    for denied in ["config/_IAS_ACCOUNTS_DO_NOT_SEND_TO_ANYONE", "config/essential", "XaeroWaypoints/command_history.txt"] {
        assert!(p.skipped.iter().any(|s| s.path == denied && s.reason == scan::DENIED_REASON), "{denied} is listed as never copied: {:?}", p.skipped);
    }
    for name in ["command_history.txt", "Command_History.TXT", "_IAS_ACCOUNTS_DO_NOT_SEND_TO_ANYONE", "usercache.json", "optionsLC.txt", "essential"] {
        assert!(scan::denied(name), "{name}");
    }
    assert!(!scan::denied("options.txt") && !scan::denied("servers.dat"));
    assert!(p.world_bytes >= 70_000);
    assert!(without.enough_space);
    let with = preview(&listed, &world.existing(), &request(LauncherId::Prism, vec![key.clone()], true), &Limits::default(), Some(100 * scan::GB));
    assert_eq!(with.profiles[0].bytes, p.bytes + p.world_bytes, "worlds add exactly their bytes");
    assert_eq!(with.bytes, with.profiles[0].bytes);
    let worlds = with.profiles[0].parts.iter().find(|x| x.id == ImportPart::Worlds).expect("worlds come over when chosen");
    assert_eq!((worlds.status, worlds.count), (PartStatus::Compatible, 2));
    // Not enough space: 1 GB must stay free.
    let tight = preview(&listed, &world.existing(), &request(LauncherId::Prism, vec![key.clone()], true), &Limits::default(), Some(with.bytes + SPARE - 1));
    assert!(!tight.enough_space);
    let json = serde_json::to_value(&with).unwrap();
    assert_eq!(json["freeBytes"], 100 * scan::GB);
    for field in ["key", "name", "target", "parts", "bytes", "worldBytes", "warnings", "blocker", "skipped"] {
        assert!(json["profiles"][0].get(field).is_some(), "{field}");
    }
    // The preview is exact: importing copies those bytes.
    let result = finished(import(&world, &listed, &request(LauncherId::Prism, vec![key], true), &Limits::default(), None, |_| false).0);
    let id = result.profiles[0].profile_id.clone().unwrap();
    let dir = world.state.paths.profile_dir(&id);
    let copied: u64 = files_in(&dir).iter().map(|f| std::fs::metadata(scan::join(&dir, f)).unwrap().len()).sum();
    assert_eq!(copied, with.bytes);
}

#[test]
fn unsupported_versions_are_blocked_and_never_substituted() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([{ "id": "a", "name": "Old", "version": "1.12.2", "loader": "forge", "dir": "a" }]));
    game_folder(&root.join("a"));
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let key = listed.listing.profiles[0].key.clone();
    let shown = preview(&listed, &world.existing(), &request(LauncherId::Prism, vec![key.clone()], true), &Limits::default(), None);
    assert!(shown.profiles[0].blocker.as_deref().unwrap().contains("1.12.2"));
    assert_eq!(shown.profiles[0].target, None);
    assert_eq!(shown.bytes, 0);
    let result = finished(import(&world, &listed, &request(LauncherId::Prism, vec![key], true), &Limits::default(), None, |_| false).0);
    assert_eq!(result.profiles[0].state, ProgressState::Skipped);
    assert!(world.existing().profiles.is_empty());
    assert!(staging_left(&world).is_empty());
}

// ---------- importing ----------

#[test]
fn a_fabric_profile_comes_over_whole_and_secrets_never_do() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([{ "id": "a", "name": "PvP", "version": "26.3", "loader": "fabric", "dir": "a" }]));
    game_folder(&root.join("a"));
    let before = tree(&world.root.join("appdata"));
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let key = listed.listing.profiles[0].key.clone();
    preview(&listed, &world.existing(), &request(LauncherId::Prism, vec![key.clone()], true), &Limits::default(), None);
    let (outcome, seen) = import(&world, &listed, &request(LauncherId::Prism, vec![key.clone()], true), &Limits::default(), Some(500 * scan::GB), |_| false);
    let result = finished(outcome);
    assert_eq!(result.profiles[0].state, ProgressState::Done);
    let profile = world.existing().profiles.into_iter().next().unwrap();
    assert_eq!((profile.name.as_str(), profile.game_version.as_str(), profile.loader.as_str()), ("PvP", "26.3", "fabric"));
    assert_eq!((profile.loader_version.as_deref(), profile.performance.as_deref(), profile.installed), (None, None, false));
    let origin = profile.origin.clone().unwrap();
    assert_eq!((origin.launcher.as_str(), origin.key.as_str()), ("prism", key.as_str()));
    assert_eq!(profile.folder.as_deref(), Some("PvP-26.3"));
    let dir = world.state.paths.profile_dir(&profile.id);
    let mut got = files_in(&dir);
    got.sort();
    assert_eq!(
        got,
        [
            "XaeroWaypoints/Multiplayer_server/waypoints.txt",
            "config/deep/a/b/c.toml",
            "config/sodium-options.json",
            "mods/sodium.jar",
            "mods/zoom.jar.disabled",
            "options.txt",
            "optionsof.txt",
            "resourcepacks/Faithful.zip",
            "resourcepacks/Folder Pack/assets/minecraft/textures/token.png",
            "resourcepacks/Folder Pack/pack.mcmeta",
            "saves/Creative/level.dat",
            "saves/Survival/level.dat",
            "saves/Survival/region/r.0.0.mca",
            "servers.dat",
            "shaderpacks/Complementary.zip",
        ]
    );
    for (rel, _) in tree(&dir) {
        assert!(!scan::sensitive(rel.rsplit('/').next().unwrap()) || rel.starts_with("resourcepacks/Folder Pack/"), "{rel} must not be copied");
    }
    assert_eq!(std::fs::read(dir.join("saves/Survival/region/r.0.0.mca")).unwrap(), vec![1u8; 70_000]);
    // Nothing anywhere in Flint's data holds a sign-in from the source.
    for (rel, kind) in tree(&world.state.paths.root) {
        if kind != "dir" {
            let text = String::from_utf8_lossy(&std::fs::read(scan::join(&world.state.paths.root, &rel)).unwrap()).to_string();
            assert!(!text.contains("secret"), "{rel} holds a copied secret");
        }
    }
    // The source is byte for byte what it was: listing, preview and import only read.
    assert_eq!(tree(&world.root.join("appdata")), before);
    assert!(staging_left(&world).is_empty());
    // Progress went waiting → copying → verifying → done, with the bytes adding up.
    let states: Vec<ProgressState> = seen.iter().map(|p| p.profiles[0].state).collect();
    assert_eq!(states.first(), Some(&ProgressState::Waiting));
    assert!(states.contains(&ProgressState::Copying) && states.contains(&ProgressState::Verifying));
    let last = &seen.last().unwrap().profiles[0];
    assert_eq!((last.state, last.bytes_done, last.profile_id.as_deref()), (ProgressState::Done, last.bytes_total, Some(profile.id.as_str())));
    let json = serde_json::to_value(&result).unwrap();
    assert_eq!(json["profiles"][0]["state"], "done");
    for field in ["key", "name", "profileId", "state", "error", "warnings", "skipped"] {
        assert!(json["profiles"][0].get(field).is_some(), "{field}");
    }
    let progress = serde_json::to_value(seen.last().unwrap()).unwrap();
    for field in ["key", "name", "state", "bytesDone", "bytesTotal", "filesDone", "filesTotal", "current", "profileId", "error"] {
        assert!(progress["profiles"][0].get(field).is_some(), "{field}");
    }
}

#[test]
fn several_profiles_import_together_with_unique_names_and_are_known_by_key_afterwards() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([
        { "id": "a", "name": "PvP", "version": "26.3", "loader": "fabric", "dir": "a", "played": 3 },
        { "id": "b", "name": "PvP", "version": "1.21.11", "loader": "vanilla", "dir": "b", "played": 2 },
        { "id": "c", "name": "Empty", "version": "1.21.1", "loader": "vanilla", "dir": "c", "played": 1 },
    ]));
    game_folder(&root.join("a"));
    write(&root, "b/options.txt", b"version:4671\n");
    std::fs::create_dir_all(root.join("c")).unwrap();
    // Flint already has a "PvP".
    crate::data::save_profiles(&world.state, &ProfilesFile { selected: None, profiles: vec![Profile { id: "aaaaaaaaaaaa".into(), name: "PvP".into(), game_version: "26.3".into(), loader: "fabric".into(), ..Default::default() }] }).unwrap();
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let keys: Vec<String> = listed.listing.profiles.iter().map(|p| p.key.clone()).collect();
    let shown = preview(&listed, &world.existing(), &request(LauncherId::Prism, keys.clone(), false), &Limits::default(), None);
    let targets: Vec<String> = shown.profiles.iter().map(|p| p.target.as_ref().unwrap().name.clone()).collect();
    assert_eq!(targets, ["PvP (2)", "PvP (3)", "Empty"]);
    let result = finished(import(&world, &listed, &request(LauncherId::Prism, keys.clone(), false), &Limits::default(), None, |_| false).0);
    assert!(result.profiles.iter().all(|p| p.state == ProgressState::Done), "{result:?}");
    let profiles = world.existing().profiles;
    let names: Vec<&str> = profiles.iter().map(|p| p.name.as_str()).collect();
    assert_eq!(names, ["PvP", "PvP (2)", "PvP (3)", "Empty"]);
    assert_eq!(profiles[2].loader, "vanilla");
    assert!(world.state.paths.profile_dir(&profiles[3].id).is_dir(), "an empty profile still gets its folder");
    assert!(!world.state.paths.profile_dir(&profiles[1].id).join("saves").exists(), "worlds weren't chosen");

    // Listed again: all three show as imported, matched by key.
    let again = world.list(&[&PRISMISH], &PRISMISH);
    for (p, flint) in again.listing.profiles.iter().zip(&profiles[1..]) {
        assert_eq!(p.imported, Some(ImportedAs { profile_id: flint.id.clone(), name: flint.name.clone() }));
    }
    // Renaming in the other launcher doesn't change who it is; a new profile with an imported one's name isn't
    // imported (names never decide).
    write(&root, "fake-launcher.json", serde_json::json!({ "profiles": [
        { "id": "a", "name": "Renamed", "version": "26.3", "loader": "fabric", "dir": "a" },
        { "id": "z", "name": "Empty", "version": "1.21.1", "loader": "vanilla", "dir": "c" },
    ] }).to_string().as_bytes());
    let renamed = world.list(&[&PRISMISH], &PRISMISH);
    let by = |name: &str| renamed.listing.profiles.iter().find(|p| p.name == name).unwrap();
    assert_eq!(by("Renamed").imported.as_ref().map(|i| i.name.as_str()), Some("PvP (2)"));
    assert_eq!(by("Empty").imported, None);
    // Importing one again makes a second profile only because the player chose it.
    let twice = finished(import(&world, &renamed, &request(LauncherId::Prism, vec![by("Renamed").key.clone()], false), &Limits::default(), None, |_| false).0);
    assert_eq!(twice.profiles[0].name, "Renamed");
    assert_eq!(world.existing().profiles.len(), 5);
}

#[test]
fn other_loaders_bring_their_settings_without_mods() {
    for (loader, label) in [("forge", "Forge"), ("neoforge", "NeoForge"), ("quilt", "Quilt")] {
        let world = World::new();
        let root = world.profiles(&CURSEISH, serde_json::json!([{ "name": "Pack", "version": "1.21.1", "loader": loader, "dir": "p" }]));
        game_folder(&root.join("p"));
        let listed = world.list(&[&CURSEISH], &CURSEISH);
        let key = listed.listing.profiles[0].key.clone();
        let shown = preview(&listed, &world.existing(), &request(LauncherId::Curseforge, vec![key.clone()], true), &Limits::default(), None);
        let p = &shown.profiles[0];
        assert_eq!(p.target.as_ref().unwrap().loader, "vanilla", "{loader}");
        let mods = p.parts.iter().find(|x| x.id == ImportPart::Mods).unwrap();
        assert_eq!((mods.status, mods.count, mods.files), (PartStatus::Unsupported, 0, 0), "{loader}");
        assert!(mods.note.as_deref().unwrap().contains(label));
        assert_eq!(p.parts.iter().find(|x| x.id == ImportPart::Config).unwrap().status, PartStatus::Unsupported);
        assert_eq!(p.parts.iter().find(|x| x.id == ImportPart::Shaderpacks).unwrap().status, PartStatus::Attention);
        let result = finished(import(&world, &listed, &request(LauncherId::Curseforge, vec![key], true), &Limits::default(), None, |_| false).0);
        let id = result.profiles[0].profile_id.clone().unwrap();
        let dir = world.state.paths.profile_dir(&id);
        assert!(!dir.join("mods").exists() && !dir.join("config").exists() && !dir.join("XaeroWaypoints").exists(), "{loader}: no mods or mod files");
        assert!(dir.join("options.txt").is_file() && dir.join("servers.dat").is_file() && dir.join("saves/Survival/level.dat").is_file());
        let profile = crate::data::find_profile(&world.state, &id).unwrap();
        assert_eq!((profile.loader.as_str(), profile.game_version.as_str()), ("vanilla", "1.21.1"));
    }
}

#[test]
fn legacy_fabric_mods_for_1_8_9_stay_behind() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([{ "id": "l", "name": "Legacy PvP", "version": "1.8.9", "loader": "fabric", "dir": "l" }]));
    game_folder(&root.join("l"));
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let key = listed.listing.profiles[0].key.clone();
    let shown = preview(&listed, &world.existing(), &request(LauncherId::Prism, vec![key.clone()], false), &Limits::default(), None);
    let mods = shown.profiles[0].parts.iter().find(|x| x.id == ImportPart::Mods).unwrap();
    assert_eq!((mods.status, mods.count), (PartStatus::Attention, 0));
    assert!(mods.note.as_deref().unwrap().contains("Legacy Fabric"));
    assert_eq!(shown.profiles[0].target.as_ref().unwrap().loader, "fabric");
    let result = finished(import(&world, &listed, &request(LauncherId::Prism, vec![key], false), &Limits::default(), None, |_| false).0);
    let dir = world.state.paths.profile_dir(result.profiles[0].profile_id.as_ref().unwrap());
    assert!(!dir.join("mods").exists() && dir.join("options.txt").is_file());
}

#[test]
fn a_file_over_the_limit_is_skipped_and_listed() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([{ "id": "a", "name": "Big", "version": "26.3", "loader": "vanilla", "dir": "a" }]));
    write(&root, "a/options.txt", b"version:5023\n");
    write(&root, "a/resourcepacks/Huge.zip", &[0u8; 5000]);
    write(&root, "a/resourcepacks/Small.zip", &[0u8; 10]);
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let key = listed.listing.profiles[0].key.clone();
    let limits = Limits { max_file: 4096, ..Limits::default() };
    let shown = preview(&listed, &world.existing(), &request(LauncherId::Prism, vec![key.clone()], false), &limits, None);
    assert!(shown.profiles[0].skipped.iter().any(|s| s.path == "resourcepacks/Huge.zip" && s.reason.contains("larger than")));
    let result = finished(import(&world, &listed, &request(LauncherId::Prism, vec![key], false), &limits, None, |_| false).0);
    assert!(result.profiles[0].skipped.iter().any(|s| s.path == "resourcepacks/Huge.zip"));
    let dir = world.state.paths.profile_dir(result.profiles[0].profile_id.as_ref().unwrap());
    assert!(!dir.join("resourcepacks/Huge.zip").exists() && dir.join("resourcepacks/Small.zip").is_file());
    // Too many files for one profile: blocked, not cut short.
    let few = Limits { max_files: 1, ..Limits::default() };
    let blocked = preview(&listed, &world.existing(), &request(LauncherId::Prism, vec![result.profiles[0].key.clone()], false), &few, None);
    assert!(blocked.profiles[0].blocker.as_deref().unwrap().contains("more than 1 files"));
}

#[test]
fn files_removed_mid_import_are_skipped_and_the_import_still_succeeds() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([{ "id": "a", "name": "PvP", "version": "26.3", "loader": "fabric", "dir": "a" }]));
    game_folder(&root.join("a"));
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let key = listed.listing.profiles[0].key.clone();
    let doomed = root.join("a/resourcepacks/Faithful.zip");
    let (outcome, _) = import(&world, &listed, &request(LauncherId::Prism, vec![key], true), &Limits::default(), None, |p| {
        // Removed once copying has started (after the plan was made).
        if p.profiles[0].state == ProgressState::Copying && doomed.exists() {
            std::fs::remove_file(&doomed).unwrap();
        }
        false
    });
    let result = finished(outcome);
    assert_eq!(result.profiles[0].state, ProgressState::Done);
    let skip = result.profiles[0].skipped.iter().find(|s| s.path == "resourcepacks/Faithful.zip").expect("listed as skipped");
    assert!(skip.reason.contains("removed"), "{}", skip.reason);
    let dir = world.state.paths.profile_dir(result.profiles[0].profile_id.as_ref().unwrap());
    assert!(dir.join("saves/Survival/level.dat").is_file());
}

#[test]
fn not_enough_space_refuses_before_copying() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([{ "id": "a", "name": "PvP", "version": "26.3", "loader": "fabric", "dir": "a" }]));
    game_folder(&root.join("a"));
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let key = listed.listing.profiles[0].key.clone();
    let needed = preview(&listed, &world.existing(), &request(LauncherId::Prism, vec![key.clone()], true), &Limits::default(), None).bytes;
    let (outcome, seen) = import(&world, &listed, &request(LauncherId::Prism, vec![key.clone()], true), &Limits::default(), Some(needed + SPARE - 1), |_| false);
    match outcome {
        Outcome::Refused(message) => assert!(message.contains("enough space"), "{message}"),
        other => panic!("expected a refusal, got {other:?}"),
    }
    assert!(seen.iter().all(|p| p.profiles[0].state == ProgressState::Waiting), "nothing started copying");
    assert!(world.existing().profiles.is_empty());
    assert!(!world.state.paths.root.join("profiles").exists(), "nothing was written");
    // Exactly enough is fine.
    let result = finished(import(&world, &listed, &request(LauncherId::Prism, vec![key], true), &Limits::default(), Some(needed + SPARE), |_| false).0);
    assert_eq!(result.profiles[0].state, ProgressState::Done);
}

#[test]
fn cancelling_mid_copy_keeps_finished_profiles_and_nothing_partial() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([
        { "id": "a", "name": "First", "version": "26.3", "loader": "fabric", "dir": "a", "played": 3 },
        { "id": "b", "name": "Second", "version": "26.3", "loader": "fabric", "dir": "b", "played": 2 },
        { "id": "c", "name": "Third", "version": "26.3", "loader": "fabric", "dir": "c", "played": 1 },
    ]));
    for d in ["a", "b", "c"] {
        game_folder(&root.join(d));
    }
    // The second profile has a big world, so the cancel lands mid-file (chunks of 1 MB).
    write(&root, "b/saves/Survival/region/r.1.1.mca", &vec![3u8; 3 * 1024 * 1024]);
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let keys: Vec<String> = listed.listing.profiles.iter().map(|p| p.key.clone()).collect();
    let staging_seen = std::cell::RefCell::new(Vec::new());
    let (outcome, _) = import(&world, &listed, &request(LauncherId::Prism, keys, true), &Limits::default(), None, |p| {
        let second = &p.profiles[1];
        if second.state == ProgressState::Copying && second.current.as_deref() == Some("World: Survival") && second.bytes_done > 1024 * 1024 {
            staging_seen.borrow_mut().extend(staging_left(&world));
            return true;
        }
        false
    });
    let result = match outcome {
        Outcome::Cancelled(result) => result,
        other => panic!("expected a cancelled import, got {other:?}"),
    };
    assert_eq!(staging_seen.borrow().len(), 1, "the second profile was being staged when cancelled");
    let states: Vec<ProgressState> = result.profiles.iter().map(|p| p.state).collect();
    assert_eq!(states, [ProgressState::Done, ProgressState::Cancelled, ProgressState::Cancelled]);
    let profiles = world.existing().profiles;
    assert_eq!(profiles.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(), ["First"]);
    assert!(staging_left(&world).is_empty(), "the cancelled profile's staging folder is gone");
    let folders: Vec<String> = std::fs::read_dir(world.state.paths.root.join("profiles")).unwrap().flatten().map(|e| e.file_name().to_string_lossy().to_string()).collect();
    assert_eq!(folders, ["First-26.3"]);
}

#[test]
fn an_interrupted_import_leaves_nothing_after_a_restart() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([{ "id": "a", "name": "PvP", "version": "26.3", "loader": "fabric", "dir": "a" }]));
    game_folder(&root.join("a"));
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let key = listed.listing.profiles[0].key.clone();
    // The process dies mid-copy: simulated by a panic in the progress hook (nothing after it runs).
    let crashed = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        import(&world, &listed, &request(LauncherId::Prism, vec![key.clone()], true), &Limits::default(), None, |p| {
            if p.profiles[0].state == ProgressState::Copying && p.profiles[0].files_done > 3 {
                panic!("power cut");
            }
            false
        })
    }));
    assert!(crashed.is_err());
    assert_eq!(staging_left(&world).len(), 1, "a staging folder was left behind");
    assert!(world.existing().profiles.is_empty(), "nothing was registered");
    // The next start removes it (lib.rs runs this before anything else).
    crate::jobs::clean_partials(&world.state.paths.root);
    assert!(staging_left(&world).is_empty());
    let restarted = AppState::for_tests(world.state.paths.root.clone());
    crate::profilefolder::settle_all(&restarted);
    assert!(load_profiles(&restarted).profiles.is_empty());
    // And the profile still shows as not imported.
    let again = list(&[&PRISMISH], LauncherId::Prism, None, &world.env, &load_profiles(&restarted)).unwrap();
    assert_eq!(again.listing.profiles[0].imported, None);
}

#[test]
fn after_a_restart_an_imported_profile_loads_with_its_origin() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([{ "id": "a", "name": "PvP", "version": "1.21.11", "loader": "fabric", "dir": "a" }]));
    game_folder(&root.join("a"));
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let key = listed.listing.profiles[0].key.clone();
    let result = finished(import(&world, &listed, &request(LauncherId::Prism, vec![key.clone()], false), &Limits::default(), None, |_| false).0);
    let id = result.profiles[0].profile_id.clone().unwrap();
    let restarted = AppState::for_tests(world.state.paths.root.clone());
    crate::jobs::clean_partials(&restarted.paths.root);
    crate::profilefolder::settle_all(&restarted);
    let file = load_profiles(&restarted);
    let profile = file.profiles.iter().find(|p| p.id == id).unwrap();
    assert_eq!(profile.origin.as_ref().map(|o| o.key.as_str()), Some(key.as_str()));
    assert_eq!(file.selected.as_deref(), Some(id.as_str()), "the first profile is selected");
    assert!(restarted.paths.profile_dir(&id).join("mods/sodium.jar").is_file());
    let again = list(&[&PRISMISH], LauncherId::Prism, None, &world.env, &file).unwrap();
    assert_eq!(again.listing.profiles[0].imported.as_ref().map(|i| i.profile_id.as_str()), Some(id.as_str()));
    // A .flintpack of it doesn't carry the origin: its importer builds a fresh profile.
    let pack = crate::flintpack::PackProfile { name: profile.name.clone(), game_version: profile.game_version.clone(), loader: profile.loader.clone(), loader_version: None, modpack: None };
    assert!(!serde_json::to_string(&pack).unwrap().contains("origin"));
}

#[test]
fn links_are_never_followed_and_paths_never_leave_the_profile() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([{ "id": "a", "name": "Linked", "version": "26.3", "loader": "vanilla", "dir": "a" }]));
    write(&root, "a/options.txt", b"version:5023\n");
    write(&root, "a/resourcepacks/Real.zip", b"real");
    // Somewhere outside the game folder, reachable only through links.
    let outside = world.root.join("outside");
    write(&outside, "secret-world/level.dat", b"outside");
    write(&outside, "pack.zip", b"outside");
    let game = root.join("a");
    assert!(link_dir(&outside, &game.join("saves")), "a link to a folder outside (a junction on Windows)");
    assert!(link_dir(&outside, &game.join("resourcepacks").join("Linked")));
    // A loop: a folder linking to its own game folder.
    assert!(link_dir(&game, &game.join("resourcepacks").join("Loop")));
    // A mods folder shared with another instance through a link.
    assert!(link_dir(&outside, &game.join("mods")));
    let before = tree(&world.root.join("appdata"));
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let key = listed.listing.profiles[0].key.clone();
    assert_eq!((listed.listing.profiles[0].worlds, listed.listing.profiles[0].mods), (0, 0), "nothing behind a link is counted");
    let shown = preview(&listed, &world.existing(), &request(LauncherId::Prism, vec![key.clone()], true), &Limits::default(), None);
    for linked in ["saves", "mods", "resourcepacks/Linked", "resourcepacks/Loop"] {
        assert!(shown.profiles[0].skipped.iter().any(|s| s.path == linked && s.reason == scan::LINK), "{linked} is listed as a link: {:?}", shown.profiles[0].skipped);
    }
    assert!(shown.profiles[0].warnings.iter().any(|w| w.contains("links")));
    let result = finished(import(&world, &listed, &request(LauncherId::Prism, vec![key], true), &Limits::default(), None, |_| false).0);
    let dir = world.state.paths.profile_dir(result.profiles[0].profile_id.as_ref().unwrap());
    assert_eq!(files_in(&dir), ["options.txt", "resourcepacks/Real.zip"]);
    assert!(tree(&dir).iter().all(|(_, kind)| kind != "link"), "no link is created");
    assert_eq!(tree(&world.root.join("appdata")), before, "the links and what they point to are untouched");
    assert!(outside.join("secret-world/level.dat").is_file());
    // Destination paths: relative, no `..`, nothing Windows refuses.
    for bad in ["../x", "saves/../../x", "/abs", "C:/x", "a\\b", "saves/w/CON", "saves/w/x.", ""] {
        assert_eq!(crate::flintpack::clean_path_within(bad, 65, 1024), None, "{bad:?}");
    }
    assert!(crate::flintpack::clean_path_within("d/".repeat(64).trim_end_matches('/'), 65, 1024).is_some(), "64 levels are fine");
}

/// A folder link (a symbolic link, or a junction where Windows refuses those without Developer Mode; junctions need no
/// rights). False when the system can't make one.
fn link_dir(target: &Path, link: &Path) -> bool {
    std::fs::create_dir_all(link.parent().unwrap()).unwrap();
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(target, link).is_ok()
    }
    #[cfg(windows)]
    {
        if std::os::windows::fs::symlink_dir(target, link).is_ok() {
            return true;
        }
        // mklink reads `/` as the start of a switch.
        let native = |p: &Path| p.display().to_string().replace('/', "\\");
        std::process::Command::new("cmd")
            .args(["/C", "mklink", "/J"])
            .arg(native(link))
            .arg(native(target))
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .is_ok_and(|s| s.success())
            && link.exists()
    }
}

#[test]
fn a_chosen_plain_folder_is_imported_only_with_a_version_flint_can_tell() {
    let world = World::new();
    let folder = world.root.join("Games/My Minecraft");
    game_folder(&folder.join(".minecraft"));
    let listed = list(&adapters::all(), LauncherId::Folder, Some(&folder), &world.env, &world.existing()).unwrap();
    assert_eq!(listed.listing.launcher.id, LauncherId::Folder);
    assert_eq!(listed.listing.profiles.len(), 1);
    let p = &listed.listing.profiles[0];
    assert_eq!((p.name.as_str(), p.game_version.as_deref(), p.loader, p.support), ("My Minecraft", Some("26.3"), ImportLoader::Unknown, ImportSupport::Settings), "Fabric and Forge jars: unknown");
    // Only Fabric mods: Fabric.
    std::fs::remove_file(folder.join(".minecraft/mods/forge-only.jar")).unwrap();
    let listed = list(&adapters::all(), LauncherId::Folder, Some(&folder), &world.env, &world.existing()).unwrap();
    assert_eq!((listed.listing.profiles[0].loader, listed.listing.profiles[0].support), (ImportLoader::Fabric, ImportSupport::Full));
    let mut req = request(LauncherId::Folder, vec![listed.listing.profiles[0].key.clone()], false);
    req.folder = Some(folder.display().to_string());
    let result = finished(import(&world, &listed, &req, &Limits::default(), None, |_| false).0);
    let profile = crate::data::find_profile(&world.state, result.profiles[0].profile_id.as_ref().unwrap()).unwrap();
    assert_eq!((profile.game_version.as_str(), profile.loader.as_str()), ("26.3", "fabric"));
    assert_eq!(profile.origin.unwrap().launcher, "folder");
    // A version Flint can't tell (none, or one it doesn't know): unsupported, with a note.
    for options in [&b"fov:0.5\n"[..], b"version:1343\n", b"version:99999\n"] {
        write(&folder.join(".minecraft"), "options.txt", options);
        let listed = list(&adapters::all(), LauncherId::Folder, Some(&folder), &world.env, &world.existing()).unwrap();
        assert_eq!(listed.listing.profiles[0].support, ImportSupport::Unsupported);
        assert!(listed.listing.profiles[0].support_note.as_deref().unwrap().contains("couldn't tell"));
    }
    // A folder with nothing Minecraft in it.
    let empty = world.root.join("Documents");
    std::fs::create_dir_all(&empty).unwrap();
    let nothing = list(&adapters::all(), LauncherId::Folder, Some(&empty), &world.env, &world.existing());
    assert_eq!(nothing.err().as_deref(), Some("Flint couldn't find a Minecraft profile in that folder."), "a plain sentence the page shows");
    // Flint's own folder is never a source.
    assert!(checked_folder(&world.env, Some(&world.state.paths.root.display().to_string())).is_err());
    assert!(checked_folder(&world.env, Some(&world.state.paths.root.join("profiles").display().to_string())).is_err() || !world.state.paths.root.join("profiles").exists());
    assert!(checked_folder(&world.env, Some(&folder.display().to_string())).is_ok());
    assert!(checked_folder(&world.env, Some("relative/path")).is_err());
}

#[test]
fn verified_data_versions_name_their_release() {
    assert_eq!(crate::gamesettings::release_of_data_version(5023), Some("26.3"));
    assert_eq!(crate::gamesettings::release_of_data_version(4790), Some("26.1.2"));
    assert_eq!(crate::gamesettings::release_of_data_version(3955), Some("1.21.1"));
    assert_eq!(crate::gamesettings::release_of_data_version(3953), None, "1.21 isn't offered");
    for (_, data) in [("26.3", 5023), ("26.2", 4903), ("26.1", 4786), ("1.21.11", 4671), ("1.21.10", 4556), ("1.21.1", 3955)] {
        assert!(SUPPORTED_VERSIONS.contains(&crate::gamesettings::release_of_data_version(data).unwrap()));
    }
}

#[test]
fn names_that_suggest_sign_ins_are_recognised() {
    for name in ["accounts.json", "launcher_accounts.json", "auth.json", "oauth-cache", "tokens.dat", "session.json", "credentials.bin", "keychain", "msa.json", "msa-cache", "launcher_msa_credentials.bin"] {
        assert!(scan::sensitive(name), "{name}");
    }
    for name in ["options.txt", "sodium-options.json", "msaa.json", "servers.dat", "level.dat", "Faithful.zip"] {
        assert!(!scan::sensitive(name), "{name}");
    }
}

#[test]
fn free_space_is_read_from_the_system() {
    let dir = TestDir::new();
    let free = free_space(&dir.0.join("not/yet/there"));
    assert!(free.is_some_and(|f| f > 0), "{free:?}");
}

#[test]
fn iso_times_are_read_exactly() {
    assert_eq!(adapters::iso_seconds("1970-01-02T00:00:00Z"), Some(86_400));
    assert_eq!(adapters::iso_seconds("2024-05-01T12:34:56.789Z"), Some(1_714_566_896));
    assert_eq!(adapters::iso_seconds("2024-05-01T14:34:56+02:00"), Some(1_714_566_896));
    assert_eq!(adapters::iso_seconds("2024-05-01 12:34:56"), Some(1_714_566_896));
    assert_eq!(adapters::iso_seconds("2024-13-01T00:00:00Z"), None);
    assert_eq!(adapters::iso_seconds("yesterday"), None);
    assert_eq!(adapters::epoch_seconds(1_714_566_896_000), Some(1_714_566_896));
    assert_eq!(adapters::epoch_seconds(1_714_566_896), Some(1_714_566_896));
}

/// Lists (never copies) the launchers installed on this computer and prints what Flint would see: per launcher, its
/// profiles' versions, loaders, mod and world counts, and a preview's parts. Run by hand:
/// `cargo test --no-default-features --lib -- --ignored import_real_scan --nocapture`. Prints no paths or accounts.
#[test]
#[ignore = "reads the launchers installed on this computer; run by hand"]
fn import_real_scan() {
    let temp = TestDir::new();
    let flint = dirs::data_dir().map(|d| d.join("Flint Client")).unwrap_or_else(|| temp.0.join("flint"));
    let env = Env { temp: temp.0.clone(), ..Env::system(&flint) };
    let none = ProfilesFile::default();
    let started = Instant::now();
    let found = discover_all(&adapters::all(), &env, &none);
    println!("\n{} launcher(s) found in {:?}", found.len(), started.elapsed());
    for launcher in &found {
        let started = Instant::now();
        let Some(adapter) = adapters::all().into_iter().find(|a| a.id() == launcher.id) else { continue };
        let Some(listed) = discover(adapter, &env, &none) else { continue };
        let took = started.elapsed();
        let l = &listed.listing.launcher;
        println!("\n== {} [{:?}]: {} profile(s), {} importable, listed in {took:?}{}", l.name, l.status, l.profiles, l.importable, l.message.as_deref().map(|m| format!(" ({m})")).unwrap_or_default());
        for p in &listed.listing.profiles {
            println!(
                "  - {:<34} {:<10} {:<9} {:<10} mods {:>3}  worlds {:>2}  {:?}{}{}{}",
                p.name.chars().take(34).collect::<String>(),
                p.game_version.as_deref().unwrap_or("?"),
                format!("{:?}", p.loader).to_lowercase(),
                p.loader_version.as_deref().unwrap_or("-"),
                p.mods,
                p.worlds,
                p.support,
                if p.shared_folder { "  shared folder" } else { "" },
                if p.icon.is_some() { "  icon" } else { "" },
                p.support_note.as_deref().map(|n| format!("\n      note: {n}")).unwrap_or_default(),
            );
        }
        let keys: Vec<String> = listed.listing.profiles.iter().filter(|p| p.support != ImportSupport::Unsupported).map(|p| p.key.clone()).collect();
        if keys.is_empty() {
            continue;
        }
        let started = Instant::now();
        let shown = preview(&listed, &none, &request(launcher.id, keys, false), &Limits::default(), free_space(&flint));
        println!("  preview of the importable ones in {:?}: {} without worlds", started.elapsed(), scan::size_text(shown.bytes));
        for p in &shown.profiles {
            let parts: Vec<String> = p.parts.iter().map(|x| format!("{:?} {}{}", x.id, x.count, if x.status == PartStatus::Compatible { "" } else if x.status == PartStatus::Attention { "!" } else { "x" }).to_lowercase()).collect();
            println!(
                "    {:<34} {:>9} (+{} worlds)  skipped {:>3}  [{}]{}",
                p.name.chars().take(34).collect::<String>(),
                scan::size_text(p.bytes),
                scan::size_text(p.world_bytes),
                p.skipped.len(),
                parts.join(", "),
                p.blocker.as_deref().map(|b| format!("  BLOCKED: {b}")).unwrap_or_default()
            );
        }
    }
}

#[test]
fn the_preview_warns_when_minecraft_runs_from_a_source_folder() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([
        { "id": "a", "name": "Running", "version": "26.3", "loader": "fabric", "dir": "a" },
        { "id": "b", "name": "Idle", "version": "26.3", "loader": "fabric", "dir": "b" },
    ]));
    game_folder(&root.join("a"));
    game_folder(&root.join("b"));
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let keys: Vec<String> = listed.listing.profiles.iter().map(|p| p.key.clone()).collect();
    let mut shown = preview(&listed, &world.existing(), &request(LauncherId::Prism, keys, false), &Limits::default(), None);
    let busy = root.join("a");
    warn_running(&mut shown, &listed, &|dir| dir == busy);
    let by = |name: &str| shown.profiles.iter().find(|p| p.name == name).unwrap();
    assert_eq!(by("Running").warnings.first().map(String::as_str), Some(RUNNING));
    assert!(!by("Idle").warnings.iter().any(|w| w == RUNNING));
    assert!(by("Running").blocker.is_none(), "a warning, not a blocker");
}

#[test]
fn profiles_import_in_the_order_they_were_asked_for() {
    let world = World::new();
    let root = world.profiles(&PRISMISH, serde_json::json!([
        { "id": "a", "name": "Newest", "version": "26.3", "loader": "vanilla", "dir": "a", "played": 30 },
        { "id": "b", "name": "Middle", "version": "26.3", "loader": "vanilla", "dir": "b", "played": 20 },
        { "id": "c", "name": "Oldest", "version": "26.3", "loader": "vanilla", "dir": "c", "played": 10 },
    ]));
    for d in ["a", "b", "c"] {
        write(&root, &format!("{d}/options.txt"), b"version:5023\n");
    }
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    let key = |name: &str| listed.listing.profiles.iter().find(|p| p.name == name).unwrap().key.clone();
    let keys = vec![key("Middle"), key("Oldest"), key("Newest"), key("Middle")];
    let (outcome, seen) = import(&world, &listed, &request(LauncherId::Prism, keys, false), &Limits::default(), None, |_| false);
    let result = finished(outcome);
    let order: Vec<&str> = result.profiles.iter().map(|p| p.name.as_str()).collect();
    assert_eq!(order, ["Middle", "Oldest", "Newest"], "the request's order, each once");
    assert_eq!(seen[0].profiles.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(), order);
    let saved: Vec<String> = world.existing().profiles.into_iter().map(|p| p.name).collect();
    assert_eq!(saved, ["Middle", "Oldest", "Newest"], "registered in that order too");
    let options = result.profiles[0].parts.iter().find(|p| p.id == ImportPart::Options).unwrap();
    assert_eq!((options.files, options.count, options.status), (1, 1, PartStatus::Compatible), "what really came over");
}

#[test]
fn flint_own_profiles_are_never_imported_back() {
    let world = World::new();
    // Another launcher pointed at one of Flint's profile folders.
    std::fs::create_dir_all(world.state.paths.root.join("profiles/Mine-26.3/saves/World")).unwrap();
    write(&world.state.paths.root, "profiles/Mine-26.3/saves/World/level.dat", b"level");
    world.profiles(&PRISMISH, serde_json::json!([{ "id": "x", "name": "Mine", "version": "26.3", "loader": "vanilla", "dir": "../../flint/profiles/Mine-26.3" }]));
    let listed = world.list(&[&PRISMISH], &PRISMISH);
    assert_eq!(listed.listing.profiles[0].support, ImportSupport::Unsupported);
    assert_eq!(listed.listing.profiles[0].support_note.as_deref(), Some("Its game folder is one of Flint's own profiles."));
    // Flint's folder and anything in it can't be chosen; a folder that merely holds it can (only its own entries are read).
    assert!(checked_folder(&world.env, Some(&world.state.paths.root.join("profiles").display().to_string())).is_err());
    assert!(checked_folder(&world.env, Some(&world.root.display().to_string())).is_ok());
}
