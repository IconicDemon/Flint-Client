//! Mods for Flint's Minecraft 1.8.9, which runs the official Fabric Loader with Ornithe's (Calamus, generation 2)
//! intermediary (see `game.rs`, `LoaderSource::Ornithe`).
//!
//! Two different projects make Fabric mods for 1.8.9, and their jars can't stand in for each other: a mod's bytecode and
//! mixin refmaps name Minecraft's classes by its intermediary, and the two intermediaries differ.
//!
//! - Ornithe: classes are `net/minecraft/unmapped/C_<7 digits>` (methods `m_…`, fields `f_…`). Modrinth tags these builds
//!   with the `ornithe` loader; their library is Ornithe Standard Libraries (`osl`, modules `osl-*`).
//! - Legacy Fabric: classes are `net/minecraft/class_<n>` (methods `method_…`), the same scheme as Fabric's own
//!   intermediary, but with Legacy Fabric's own numbering. Modrinth tags these `legacy-fabric`, and very often `fabric` as
//!   well; their library is Legacy Fabric API (`legacy-fabric-api`, modules `legacy-fabric-*`).
//!
//! So on 1.8.9 Modrinth's `fabric` tag means Legacy Fabric, and Flint asks Modrinth for `ornithe` builds only. Legacy Fabric
//! API itself has had an Ornithe build since 1.14 (2026-03: tagged `ornithe`, depends on `osl`), so depending on
//! `legacy-fabric-api` alone doesn't make a mod a Legacy Fabric one. A jar's own names decide (see `inspect`).
//!
//! CurseForge has no loader for either: its ModLoaderType is Any, Forge, Cauldron, LiteLoader, Fabric, Quilt or NeoForge,
//! and a 1.8.9 file is tagged "Fabric" whichever it is built for. Its 1.8.9 mods can't be told apart before download, so
//! CurseForge offers no mods for 1.8.9 (`curseforge::search`), and every jar is checked once it's downloaded.

use serde_json::Value;
use std::io::{Read, Seek};
use std::path::Path;

/// Modrinth's loader tag for Ornithe builds.
pub const LOADER: &str = "ornithe";

/// Whether this Minecraft version's Fabric comes from Ornithe (its mods must be Ornithe builds).
pub fn on_ornithe(game_version: &str) -> bool {
    game_version == "1.8.9"
}

/// Libraries that Ornithe mods list as required on Modrinth but that are published as plain Fabric builds, for no
/// particular Minecraft version: they hold no Minecraft code, so any build runs on Ornithe's Fabric Loader. Fabric Language
/// Kotlin is required by Polyfrost's Ornithe mods (OneConfig, PolySprint, Chatting, VanillaHUD, EvergreenHUD…) and lists
/// no 1.8.9 at all. (Modrinth project id, slug.)
pub const SHARED_LIBRARIES: &[(&str, &str)] = &[("Ha28R6CL", "fabric-language-kotlin")];

/// Whether `project` (a Modrinth id or slug) is one of `SHARED_LIBRARIES`.
pub fn shared_library(project: &str) -> bool {
    SHARED_LIBRARIES.iter().any(|(id, slug)| *id == project || *slug == project)
}

// ---------- the jar check ----------

const ORNITHE_NAME: &[u8] = b"net/minecraft/unmapped/C_";
const LEGACY_FABRIC_NAME: &[u8] = b"net/minecraft/class_";
/// Limits: a jar is read until it shows which names it uses, never further than these.
const MAX_ENTRY: u64 = 16 * 1024 * 1024;
const MAX_TOTAL: u64 = 256 * 1024 * 1024;
const MAX_ENTRIES: usize = 40_000;
const MAX_NESTED_JAR: u64 = 64 * 1024 * 1024;
const MAX_DEPTH: usize = 3;

/// What a jar (with the jars bundled in it) shows about the Fabric it's built for.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct Evidence {
    /// It has a `fabric.mod.json` at its top.
    pub fabric_mod: bool,
    /// It has a `quilt.mod.json` at its top (and no `fabric.mod.json`).
    pub quilt_only: bool,
    /// Its `fabric.mod.json` id, if any.
    pub id: Option<String>,
    /// Its `fabric.mod.json` name (or id).
    pub name: Option<String>,
    /// Its code or refmaps name Minecraft by Ornithe's intermediary.
    pub ornithe_names: bool,
    /// Its code or refmaps name Minecraft by Legacy Fabric's (Fabric-style) intermediary.
    pub legacy_names: bool,
    /// It (or a jar bundled in it) depends on Legacy Fabric API or one of its modules, or is one.
    pub legacy_api: bool,
    /// It (or a jar bundled in it) depends on Ornithe Standard Libraries or one of its modules.
    pub osl: bool,
    /// It (or a jar bundled in it) requires (`depends`) Ornithe Standard Libraries or one of its modules (`osl-entrypoints`…).
    pub requires_osl: bool,
    /// It is Ornithe Standard Libraries, or bundles one of its modules.
    pub provides_osl: bool,
    budget: u64,
    entries: usize,
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack.windows(needle.len()).any(|w| w == needle)
}

fn read_limited(entry: &mut impl Read, max: u64) -> Option<Vec<u8>> {
    let mut bytes = Vec::new();
    entry.take(max + 1).read_to_end(&mut bytes).ok()?;
    (bytes.len() as u64 <= max).then_some(bytes)
}

fn legacy_api_id(id: &str) -> bool {
    id == "legacy-fabric-api" || id.starts_with("legacy-fabric-")
}

fn osl_id(id: &str) -> bool {
    id == "osl" || id.starts_with("osl-")
}

fn read_mod_json(json: &Value, top: bool, out: &mut Evidence) {
    let id = json["id"].as_str().unwrap_or_default();
    if top {
        out.id = Some(id.to_owned()).filter(|i| !i.is_empty());
        out.name = json["name"].as_str().map(str::trim).filter(|n| !n.is_empty()).map(str::to_owned).or_else(|| out.id.clone());
    }
    if legacy_api_id(id) {
        out.legacy_api = true;
    }
    if osl_id(id) {
        out.provides_osl = true;
    }
    for dep in json["depends"].as_object().into_iter().flat_map(|o| o.keys()) {
        out.requires_osl |= osl_id(dep);
    }
    for key in ["depends", "recommends"] {
        for dep in json[key].as_object().into_iter().flat_map(|o| o.keys()) {
            out.legacy_api |= legacy_api_id(dep);
            out.osl |= osl_id(dep);
        }
    }
}

/// Reads a jar's `fabric.mod.json`, then its classes and JSON files (mixin refmaps) for Minecraft's names, then the jars
/// bundled in it (their `fabric.mod.json` always; their code until Ornithe's names turn up).
fn inspect<R: Read + Seek>(archive: &mut zip::ZipArchive<R>, depth: usize, out: &mut Evidence) {
    // Some(None): there, but too large or not JSON (Fabric Loader would refuse it; the names still decide here).
    let mod_json: Option<Option<Value>> = archive
        .by_name("fabric.mod.json")
        .ok()
        .map(|mut entry| read_limited(&mut entry, 1024 * 1024).and_then(|b| serde_json::from_slice::<Value>(b.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(&b)).ok()));
    match mod_json {
        Some(json) => {
            if depth == 0 {
                out.fabric_mod = true;
            }
            if let Some(json) = json {
                read_mod_json(&json, depth == 0, out);
            }
        }
        None => {
            let quilt = depth == 0 && archive.by_name("quilt.mod.json").is_ok();
            out.quilt_only |= quilt;
        }
    }
    let mut nested = Vec::new();
    for index in 0..archive.len() {
        if out.entries >= MAX_ENTRIES || out.budget >= MAX_TOTAL {
            break;
        }
        out.entries += 1;
        let Ok(mut entry) = archive.by_index(index) else { continue };
        let name = entry.name().to_ascii_lowercase();
        if name.ends_with(".jar") {
            if depth < MAX_DEPTH && entry.size() <= MAX_NESTED_JAR {
                nested.push(index);
            }
            continue;
        }
        // Once Ornithe's names have turned up, only the bundled jars' `fabric.mod.json` are still read (for OSL).
        if out.ornithe_names {
            continue;
        }
        if !(name.ends_with(".class") || name.ends_with(".json")) || entry.size() > MAX_ENTRY {
            continue;
        }
        let Some(bytes) = read_limited(&mut entry, MAX_ENTRY) else { continue };
        out.budget += bytes.len() as u64;
        if contains(&bytes, ORNITHE_NAME) {
            out.ornithe_names = true;
        } else if !out.legacy_names && contains(&bytes, LEGACY_FABRIC_NAME) {
            out.legacy_names = true;
        }
    }
    for index in nested {
        if out.budget >= MAX_TOTAL {
            return;
        }
        let Ok(mut entry) = archive.by_index(index) else { continue };
        let Some(bytes) = read_limited(&mut entry, MAX_NESTED_JAR) else { continue };
        out.budget += bytes.len() as u64;
        if let Ok(mut inner) = zip::ZipArchive::new(std::io::Cursor::new(bytes)) {
            inspect(&mut inner, depth + 1, out);
        }
    }
}

/// What a jar shows about the Fabric it's built for; None when it isn't a readable jar.
pub fn evidence<R: Read + Seek>(reader: R) -> Option<Evidence> {
    let mut archive = zip::ZipArchive::new(reader).ok()?;
    let mut out = Evidence::default();
    inspect(&mut archive, 0, &mut out);
    Some(out)
}

/// Whether a mod jar can load on Flint's 1.8.9 (Fabric Loader with Ornithe's intermediary); the reason when it can't.
///
/// - Not a Fabric mod (a Forge jar, or a Quilt-only one): Fabric Loader never loads it.
/// - Ornithe's names in its code: an Ornithe build (even when it also uses Legacy Fabric API's Ornithe build).
/// - Legacy Fabric's names and no Ornithe ones: a Legacy Fabric build.
/// - No Minecraft names at all (a library, or code that reaches Minecraft some other way): a Legacy Fabric build only when it
///   needs Legacy Fabric API (or is it) without Ornithe's libraries. Legacy Fabric API's own Ornithe build depends on `osl`.
pub fn verdict(evidence: &Evidence, title: &str) -> Result<(), String> {
    let title = if title.trim().is_empty() { evidence.name.as_deref().unwrap_or("This mod") } else { title };
    if !evidence.fabric_mod {
        return Err(if evidence.quilt_only {
            format!("{title} is a Quilt-only build. Flint's Minecraft 1.8.9 runs Fabric (from Ornithe), which can't load it.")
        } else {
            format!("{title} isn't a Fabric mod, so Flint's Minecraft 1.8.9 (Fabric from Ornithe) can't load it.")
        });
    }
    let legacy = evidence.legacy_names || (evidence.legacy_api && !evidence.osl);
    if legacy && !evidence.ornithe_names {
        return Err(format!(
            "{title} is built for Legacy Fabric. Flint's Minecraft 1.8.9 runs Fabric from Ornithe, which can't load Legacy Fabric mods (they name Minecraft's code differently), so it would break the game. Look for the mod's Ornithe build instead."
        ));
    }
    Ok(())
}

/// Checks a downloaded mod jar for Flint's 1.8.9 (see `verdict`).
pub fn check_jar(path: &Path, title: &str) -> Result<(), String> {
    inspect_jar(path, title).map(|_| ())
}

/// `check_jar`, with what the jar showed when it passes.
pub fn inspect_jar(path: &Path, title: &str) -> Result<Evidence, String> {
    let file = std::fs::File::open(path).map_err(|e| format!("Could not read the downloaded mod: {e}"))?;
    let found = evidence(std::io::BufReader::new(file)).ok_or_else(|| format!("{} isn't a readable mod jar.", if title.is_empty() { "The download" } else { title }))?;
    verdict(&found, title)?;
    Ok(found)
}

/// The same check off the async runtime (a jar can be tens of MB to read through), with what the jar showed.
pub async fn check_jar_async(path: std::path::PathBuf, title: String) -> Result<Evidence, String> {
    tokio::task::spawn_blocking(move || inspect_jar(&path, &title)).await.map_err(|_| "Could not check the downloaded mod".to_owned())?
}

// ---------- Ornithe Standard Libraries ----------

/// Ornithe Standard Libraries on Modrinth (slug `osl`).
pub const OSL_PROJECT: &str = "EHGtwpJv";

impl Evidence {
    /// The jar needs OSL from another jar: it requires OSL or one of its modules and doesn't carry them itself. Ornithe mods
    /// such as MinecraftCapes and Titanium require `osl-entrypoints` in their `fabric.mod.json` but don't list OSL as a
    /// dependency on Modrinth, so installed alone they leave a game that can't start.
    pub fn needs_osl(&self) -> bool {
        self.requires_osl && !self.provides_osl
    }
}

/// Whether a mods folder (as `perfcore::scan_mods` read it) already has OSL: a jar that is OSL or bundles its modules.
pub fn has_osl(mods: &[crate::perfcore::ModJar]) -> bool {
    mods.iter().any(|m| osl_id(&m.id) || m.provides.iter().any(|p| osl_id(p)) || m.nested.iter().any(|n| osl_id(n)))
}

/// The titles of the mods in a batch that need OSL brought in with them (`Evidence::needs_osl`): none when nothing needs
/// it, the profile has it (`present`), or the batch brings it already (Modrinth's OSL project, or a jar that provides it).
/// Each batch item is (project id, title, what its jar showed).
pub fn osl_needed_by<'a>(batch: impl IntoIterator<Item = (&'a str, &'a str, Option<&'a Evidence>)>, present: bool) -> Vec<String> {
    let mut needed_by = Vec::new();
    let mut brought = present;
    for (project_id, title, found) in batch {
        brought |= project_id == OSL_PROJECT || found.is_some_and(|f| f.provides_osl);
        if found.is_some_and(Evidence::needs_osl) {
            needed_by.push(title.to_owned());
        }
    }
    if brought {
        needed_by.clear();
    }
    needed_by
}

/// Checks every mod jar in a folder (a modpack's `mods` before it becomes a 1.8.9 profile); the first refusal.
pub fn check_mods_dir(dir: &Path) -> Result<(), String> {
    let Ok(entries) = std::fs::read_dir(dir) else { return Ok(()) };
    let mut names: Vec<String> = entries.flatten().filter_map(|e| e.file_name().into_string().ok()).filter(|n| n.to_ascii_lowercase().ends_with(".jar")).collect();
    names.sort();
    for name in names {
        let title = name.trim_end_matches(".jar").to_owned();
        check_jar(&dir.join(&name), &title).map_err(|why| format!("This modpack can't run on Flint's Minecraft 1.8.9: {why}"))?;
    }
    Ok(())
}

/// A modpack's mods (its staging `mods` folder, overrides included) before it becomes a profile: on 1.8.9 every jar must
/// load on Ornithe's Fabric (a Legacy Fabric pack is refused whole), elsewhere nothing is checked.
pub async fn check_pack(game_version: &str, mods: std::path::PathBuf) -> Result<(), String> {
    if !on_ornithe(game_version) {
        return Ok(());
    }
    tokio::task::spawn_blocking(move || check_mods_dir(&mods)).await.map_err(|_| "Could not check the modpack's mods".to_owned())?
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Cursor, Write};

    fn jar(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut out = zip::ZipWriter::new(Cursor::new(Vec::new()));
        for (name, bytes) in entries {
            out.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            out.write_all(bytes).unwrap();
        }
        out.finish().unwrap().into_inner()
    }

    fn check(entries: &[(&str, &[u8])]) -> Result<(), String> {
        verdict(&evidence(Cursor::new(jar(entries))).unwrap(), "Test Mod")
    }

    // Shapes of real jars, read on 2026-11-12 from Modrinth (e4mc Retro 1.0.2's ornithe and fabric builds, Legacy Fabric
    // API 1.13.5 and 1.22.0, Fabric Language Kotlin).
    const ORNITHE_REFMAP: &[u8] = br#"{"mappings":{"link/e4mc/mixin/NetworkSystemMixin":{"addLanEndpoint":"Lnet/minecraft/unmapped/C_8345387;m_8606616(Ljava/net/InetAddress;I)V"}}}"#;
    const LEGACY_REFMAP: &[u8] = br#"{"mappings":{"link/e4mc/mixin/NetworkSystemMixin":{"addLanEndpoint":"Lnet/minecraft/class_2103;method_8143(Ljava/net/InetAddress;I)V"}}}"#;
    const CLASS_WITH_ORNITHE: &[u8] = b"\xCA\xFE\xBA\xBE\x00\x00\x00\x34\x01\x00\x20net/minecraft/unmapped/C_6842557";
    const CLASS_WITH_LEGACY: &[u8] = b"\xCA\xFE\xBA\xBE\x00\x00\x00\x34\x01\x00\x16net/minecraft/class_748";

    #[test]
    fn ornithe_builds_go_in() {
        let mod_json = br#"{"id":"e4mc_retro_minecraft","depends":{"fabricloader":">=0.14.19","minecraft":["1.8.9"]}}"#;
        assert_eq!(check(&[("fabric.mod.json", mod_json), ("e4mc.refmap.json", ORNITHE_REFMAP)]), Ok(()));
        assert_eq!(check(&[("fabric.mod.json", mod_json), ("a/B.class", CLASS_WITH_ORNITHE)]), Ok(()));
    }

    #[test]
    fn legacy_fabric_builds_are_refused_with_a_clear_reason() {
        let mod_json = br#"{"id":"e4mc_retro_minecraft","name":"e4mc Retro","depends":{"fabricloader":">=0.14.19","legacy-fabric-api":"*"}}"#;
        let why = check(&[("fabric.mod.json", mod_json), ("e4mc.refmap.json", LEGACY_REFMAP)]).unwrap_err();
        assert!(why.contains("Test Mod is built for Legacy Fabric") && why.contains("Ornithe build"), "{why}");
        // Legacy Fabric's names in code alone, with no Legacy Fabric API dependency (Legacy Mod Menu, Realms Button Remover).
        assert!(check(&[("fabric.mod.json", br#"{"id":"x"}"#), ("a/B.class", CLASS_WITH_LEGACY)]).is_err());
        // No Minecraft names, but it needs Legacy Fabric API and none of Ornithe's libraries.
        assert!(check(&[("fabric.mod.json", br#"{"id":"x","depends":{"legacy-fabric-api":"*"}}"#)]).is_err());
        assert!(check(&[("fabric.mod.json", br#"{"id":"x","depends":{"legacy-fabric-networking-api-v1":"*"}}"#)]).is_err());
    }

    #[test]
    fn legacy_fabric_api_s_own_builds_are_told_apart() {
        // 1.13.5: the Legacy Fabric build; its modules are bundled jars that need nothing of Ornithe's.
        let module = jar(&[("fabric.mod.json", br#"{"id":"legacy-fabric-api-base","depends":{"fabricloader":">=0.15.11","minecraft":">=1.8<=1.13.2"}}"#), ("net/legacyfabric/A.class", CLASS_WITH_LEGACY)]);
        let old = jar(&[("fabric.mod.json", br#"{"id":"legacy-fabric-api","jars":[{"file":"META-INF/jars/base.jar"}],"depends":{"fabricloader":">=0.15.11"}}"#), ("META-INF/jars/base.jar", &module)]);
        assert!(verdict(&evidence(Cursor::new(old)).unwrap(), "Legacy Fabric API").is_err());
        // 1.22.0: the Ornithe build (Modrinth's `ornithe` loader), which depends on OSL.
        let module = jar(&[("fabric.mod.json", br#"{"id":"legacy-fabric-biome-api-v1","depends":{"osl-registries":">=0.1.0-"}}"#), ("net/legacyfabric/A.class", CLASS_WITH_ORNITHE)]);
        let new = jar(&[("fabric.mod.json", br#"{"id":"legacy-fabric-api","depends":{"fabricloader":">=0.18.4","osl":">=0.23.0-alpha.11"}}"#), ("META-INF/jars/biome.jar", &module)]);
        assert_eq!(verdict(&evidence(Cursor::new(new)).unwrap(), "Legacy Fabric API"), Ok(()));
        // A mod on Legacy Fabric API's Ornithe build: Ornithe's names win over the dependency.
        assert_eq!(check(&[("fabric.mod.json", br#"{"id":"x","depends":{"legacy-fabric-api":">=1.14"}}"#), ("a/B.class", CLASS_WITH_ORNITHE)]), Ok(()));
    }

    #[test]
    fn libraries_without_minecraft_code_go_in() {
        // Fabric Language Kotlin, OSL itself, Ksyxis: no Minecraft names and nothing of Legacy Fabric's.
        assert_eq!(check(&[("fabric.mod.json", br#"{"id":"fabric-language-kotlin","depends":{"fabricloader":">=0.16.0","java":">=8"}}"#), ("kotlin/Unit.class", b"\xCA\xFE\xBA\xBE")]), Ok(()));
        assert_eq!(check(&[("fabric.mod.json", br#"{"id":"osl","depends":{"fabricloader":">=0.18.0","minecraft":"*"}}"#)]), Ok(()));
    }

    #[test]
    fn jars_fabric_loader_never_loads_are_refused() {
        let forge = check(&[("mcmod.info", b"[]"), ("a/B.class", CLASS_WITH_LEGACY)]).unwrap_err();
        assert!(forge.contains("isn't a Fabric mod"), "{forge}");
        let quilt = check(&[("quilt.mod.json", b"{}"), ("a/B.class", CLASS_WITH_ORNITHE)]).unwrap_err();
        assert!(quilt.contains("Quilt-only"), "{quilt}");
        assert!(evidence(Cursor::new(b"not a zip".to_vec())).is_none());
    }

    #[test]
    fn the_jar_s_own_name_stands_in_for_a_missing_title() {
        let found = evidence(Cursor::new(jar(&[("fabric.mod.json", br#"{"id":"oldium","name":"Oldium"}"#), ("a/B.class", CLASS_WITH_LEGACY)]))).unwrap();
        assert!(verdict(&found, "").unwrap_err().starts_with("Oldium is built for Legacy Fabric"));
    }

    #[test]
    fn a_modpack_s_mods_folder_is_checked_jar_by_jar() {
        let dir = std::env::temp_dir().join(format!("flint-ornithe-{}-{}", std::process::id(), crate::core::now_secs()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a-ornithe.jar"), jar(&[("fabric.mod.json", br#"{"id":"a"}"#), ("a.refmap.json", ORNITHE_REFMAP)])).unwrap();
        std::fs::write(dir.join("notes.txt"), b"not a mod").unwrap();
        assert_eq!(check_mods_dir(&dir), Ok(()));
        std::fs::write(dir.join("b-legacy.jar"), jar(&[("fabric.mod.json", br#"{"id":"b"}"#), ("b.refmap.json", LEGACY_REFMAP)])).unwrap();
        let why = check_mods_dir(&dir).unwrap_err();
        assert!(why.contains("b-legacy is built for Legacy Fabric"), "{why}");
        let _ = std::fs::remove_dir_all(&dir);
        assert_eq!(check_mods_dir(&dir), Ok(()), "no folder, nothing to refuse");
    }

    fn found(entries: &[(&str, &[u8])]) -> Evidence {
        evidence(Cursor::new(jar(entries))).unwrap()
    }

    /// Shapes of real 1.8.9 jars (2026-11-12): Titanium V0.1 and MinecraftCapes require `osl-entrypoints` in their
    /// `fabric.mod.json`, and Modrinth lists no OSL dependency for them.
    #[test]
    fn mods_that_require_osl_modules_are_told_from_those_that_carry_them() {
        let titanium = found(&[("fabric.mod.json", br#"{"id":"titanium","depends":{"fabricloader":">=0.17.3","minecraft":"1.8.9","osl-entrypoints":">=0.4.0"}}"#), ("a.refmap.json", ORNITHE_REFMAP)]);
        assert!(titanium.needs_osl());
        assert_eq!(verdict(&titanium, "Titanium"), Ok(()), "needing OSL is no reason to refuse it");
        assert!(found(&[("fabric.mod.json", br#"{"id":"x","depends":{"osl":">=0.20.3"}}"#)]).needs_osl(), "OSL itself, by its own id");
        // OSL: its modules are bundled jars that require each other.
        let module = jar(&[("fabric.mod.json", br#"{"id":"osl-entrypoints","depends":{"osl-core":"*"}}"#)]);
        let osl = found(&[("fabric.mod.json", br#"{"id":"osl","depends":{"fabricloader":">=0.18.0","minecraft":"*"}}"#), ("META-INF/jars/entrypoints.jar", &module)]);
        assert!(osl.provides_osl && !osl.needs_osl());
        // A mod that bundles the OSL modules it needs, one that only recommends OSL, and one that needs nothing of it.
        let bundling = found(&[("fabric.mod.json", br#"{"id":"y","depends":{"osl-entrypoints":"*"}}"#), ("META-INF/jars/entrypoints.jar", &module)]);
        assert!(!bundling.needs_osl());
        let bundling = found(&[("fabric.mod.json", br#"{"id":"y","depends":{"osl-entrypoints":"*"}}"#), ("a/B.class", CLASS_WITH_ORNITHE), ("META-INF/jars/entrypoints.jar", &module)]);
        assert!(bundling.ornithe_names && !bundling.needs_osl(), "bundled modules are read after Ornithe's names turn up");
        assert!(!found(&[("fabric.mod.json", br#"{"id":"z","recommends":{"osl":"*"}}"#)]).needs_osl());
        assert!(!found(&[("fabric.mod.json", br#"{"id":"fabric-language-kotlin","depends":{"fabricloader":">=0.16.0"}}"#)]).needs_osl());
    }

    #[test]
    fn osl_comes_in_once_and_only_when_the_profile_and_the_batch_lack_it() {
        let needs = &Evidence { fabric_mod: true, requires_osl: true, ..Default::default() };
        let carries = &Evidence { fabric_mod: true, provides_osl: true, ..Default::default() };
        let plain = &Evidence { fabric_mod: true, ..Default::default() };
        assert_eq!(osl_needed_by(vec![("cap", "MinecraftCapes", Some(needs)), ("ti", "Titanium", Some(needs)), ("k", "Kotlin", Some(plain))], false), ["MinecraftCapes", "Titanium"]);
        assert!(osl_needed_by(vec![("ti", "Titanium", Some(needs))], true).is_empty(), "the profile has OSL");
        assert!(osl_needed_by(vec![("ti", "Titanium", Some(needs)), (OSL_PROJECT, "Ornithe Standard Libraries", Some(carries))], false).is_empty(), "the batch brings it (Modrinth listed it)");
        assert!(osl_needed_by(vec![("ti", "Titanium", Some(needs)), ("other", "Bundled OSL", Some(carries))], false).is_empty(), "a jar in the batch carries it");
        assert!(osl_needed_by(vec![("k", "Kotlin", Some(plain)), ("pack", "A pack", None)], false).is_empty(), "nothing needs it");

        // The profile's mods folder, as `perfcore::scan_mods` reads it.
        let jar = |id: &str, nested: &[&str]| crate::perfcore::ModJar { id: id.into(), nested: nested.iter().map(|n| (*n).to_owned()).collect(), ..Default::default() };
        assert!(has_osl(&[jar("titanium", &[]), jar("osl", &["osl-core", "osl-entrypoints"])]));
        assert!(has_osl(&[jar("axolotlclient", &["osl-entrypoints"])]), "bundled modules count");
        assert!(!has_osl(&[jar("titanium", &[]), jar("legacy-lwjgl3", &[])]));
        assert!(!has_osl(&[]));
    }

    #[test]
    fn only_1_8_9_is_on_ornithe_and_kotlin_is_shared() {
        assert!(on_ornithe("1.8.9"));
        for other in ["26.3", "26.1.2", "1.21.11", "1.21.1", "1.19.2", "1.8.8"] {
            assert!(!on_ornithe(other), "{other}");
        }
        assert!(shared_library("Ha28R6CL") && shared_library("fabric-language-kotlin"));
        assert!(!shared_library("fabric-api") && !shared_library("P7dR8mSH"), "Fabric API has no build for 1.8.9");
    }

    /// Runs the check on real jars: `FLINT_ORNITHE_JARS=<folder> cargo test real_jars -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn real_jars() {
        let dir = std::env::var("FLINT_ORNITHE_JARS").expect("FLINT_ORNITHE_JARS");
        let mut names: Vec<_> = std::fs::read_dir(&dir).unwrap().flatten().map(|e| e.path()).filter(|p| p.extension().is_some_and(|e| e == "jar")).collect();
        names.sort();
        for path in names {
            let found = evidence(std::io::BufReader::new(std::fs::File::open(&path).unwrap()));
            let verdict = found.as_ref().map(|f| verdict(f, ""));
            println!("{} | {:?} | {:?}", path.file_name().unwrap().to_string_lossy(), verdict, found);
        }
    }
}
