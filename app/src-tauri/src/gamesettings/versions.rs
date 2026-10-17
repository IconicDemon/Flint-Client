//! Whether Flint has checked a profile's Minecraft version against the game's own code, which decides
//! whether its settings may be changed.
//!
//! A version is VERIFIED only when its installed jar reports one of the data versions in the generated
//! tables (each read from that version's real jar), UNVERIFIED when Flint knows the version but hasn't
//! checked it (its settings are shown read-only), and UNKNOWN when there is nothing to go on (a version Flint
//! doesn't list, or a jar that isn't installed). Nothing is ever guessed from the version's name alone.

use super::tables::{FamilyData, FAMILIES};
use serde::Serialize;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, Mutex};
use std::time::SystemTime;

#[derive(Serialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum SupportLevel {
    Verified,
    Unverified,
    Unknown,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VersionSupport {
    pub level: SupportLevel,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub family: Option<&'static str>,
    pub game_version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data_version: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

impl VersionSupport {
    /// The verified family's tables; None for anything that isn't verified.
    pub fn family(&self) -> Option<&'static FamilyData> {
        if self.level != SupportLevel::Verified {
            return None;
        }
        FAMILIES.iter().find(|f| Some(f.id) == self.family)
    }

    fn unknown(game_version: &str, reason: String) -> Self {
        Self { level: SupportLevel::Unknown, family: None, game_version: game_version.to_owned(), data_version: None, reason: Some(reason) }
    }

    fn unverified(game_version: &str, data_version: Option<u32>, reason: String) -> Self {
        Self { level: SupportLevel::Unverified, family: None, game_version: game_version.to_owned(), data_version, reason: Some(reason) }
    }
}

/// Versions Flint offers whose settings format hasn't been checked against the game's code yet (no local
/// jars to check them with). Their settings are shown, never written.
const UNVERIFIED_VERSIONS: &[(&str, &str)] = &[
    ("1.21.11", "Not yet verified against Minecraft 1.21.x"),
    ("1.21.10", "Not yet verified against Minecraft 1.21.x"),
    ("1.21.1", "Not yet verified against Minecraft 1.21.x"),
    ("1.8.9", "Not yet verified against Minecraft 1.8.9"),
];

/// The family a version name belongs to and the data version its jar must report.
fn expected(game_version: &str) -> Option<(&'static FamilyData, u32)> {
    FAMILIES.iter().find_map(|family| family.versions.iter().find(|(name, _)| *name == game_version).map(|(_, data)| (family, *data)))
}

/// What Flint knows about `game_version`'s settings, reading its installed jar under `game_root`
/// (the shared `minecraft` folder).
pub fn resolve(game_root: &Path, game_version: &str) -> VersionSupport {
    if let Some((_, reason)) = UNVERIFIED_VERSIONS.iter().find(|(name, _)| *name == game_version) {
        return VersionSupport::unverified(game_version, None, (*reason).to_owned());
    }
    let Some((family, wanted)) = expected(game_version) else {
        return VersionSupport::unknown(game_version, format!("Flint doesn't know Minecraft {game_version}'s settings format."));
    };
    let jar = game_root.join("versions").join(game_version).join(format!("{game_version}.jar"));
    match jar_version(&jar) {
        None => VersionSupport::unknown(
            game_version,
            format!("Minecraft {game_version} isn't installed yet, so Flint can't confirm its settings format. Install or play this profile once first."),
        ),
        Some((id, data)) if id != game_version || data != wanted => VersionSupport::unverified(
            game_version,
            Some(data),
            format!("This copy of Minecraft {game_version} reports version {id} (data version {data}), which Flint hasn't verified."),
        ),
        Some((_, data)) => VersionSupport {
            level: SupportLevel::Verified,
            family: Some(family.id),
            game_version: game_version.to_owned(),
            data_version: Some(data),
            reason: None,
        },
    }
}

/// A jar's size and modification time: while both stay the same, its version.json is too.
type JarKey = (u64, Option<SystemTime>);
type JarMemo = HashMap<PathBuf, (JarKey, Option<JarInfo>)>;

/// What a client jar's `version.json` says: its `id`, `world_version` and resource pack format.
#[derive(Clone, Debug, PartialEq)]
struct JarInfo {
    id: String,
    data: u32,
    resource: Option<(u32, u32)>,
}

/// Data versions already read, by jar path.
static JAR_VERSIONS: LazyLock<Mutex<JarMemo>> = LazyLock::new(Default::default);

/// The `id` and `world_version` in a client jar's `version.json`, memoised per jar.
fn jar_version(jar: &Path) -> Option<(String, u32)> {
    jar_info(jar).map(|info| (info.id, info.data))
}

fn jar_info(jar: &Path) -> Option<JarInfo> {
    let meta = std::fs::metadata(jar).ok().filter(|m| m.is_file())?;
    let key = (meta.len(), meta.modified().ok());
    if let Some((seen, found)) = JAR_VERSIONS.lock().unwrap().get(jar) {
        if *seen == key {
            return found.clone();
        }
    }
    let found = read_version_json(jar);
    JAR_VERSIONS.lock().unwrap().insert(jar.to_path_buf(), (key, found.clone()));
    found
}

/// The resource pack format `game_version`'s installed jar reports, as (major, minor): 26.x jars have
/// `pack_version.resource_major` / `resource_minor` (26.3: 97.1, 26.2: 88.0, 26.1: 84.0, read from the real
/// jars); older ones `pack_version.resource` or a bare number. None when the jar isn't installed or says
/// nothing Flint can read.
pub fn resource_format(game_root: &Path, game_version: &str) -> Option<(u32, u32)> {
    if expected(game_version).is_none() && !UNVERIFIED_VERSIONS.iter().any(|(name, _)| *name == game_version) {
        return None;
    }
    jar_info(&game_root.join("versions").join(game_version).join(format!("{game_version}.jar")))?.resource
}

fn pack_format(pack: &serde_json::Value) -> Option<(u32, u32)> {
    let int = |v: &serde_json::Value| v.as_u64().and_then(|n| u32::try_from(n).ok());
    match pack {
        serde_json::Value::Object(o) if o.contains_key("resource_major") => Some((int(&o["resource_major"])?, o.get("resource_minor").and_then(int).unwrap_or(0))),
        serde_json::Value::Object(o) => Some((int(o.get("resource")?)?, 0)),
        other => Some((int(other)?, 0)),
    }
}

fn read_version_json(jar: &Path) -> Option<JarInfo> {
    use std::io::Read;
    const LIMIT: u64 = 64 * 1024;
    let mut archive = zip::ZipArchive::new(std::fs::File::open(jar).ok()?).ok()?;
    let entry = archive.by_name("version.json").ok()?;
    let mut bytes = Vec::new();
    // The declared size can lie; never read past the limit.
    entry.take(LIMIT + 1).read_to_end(&mut bytes).ok()?;
    if bytes.len() as u64 > LIMIT {
        return None;
    }
    let json: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    let id = json["id"].as_str()?.to_owned();
    let data = u32::try_from(json["world_version"].as_u64()?).ok()?;
    Some(JarInfo { id, data, resource: pack_format(&json["pack_version"]) })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;
    use crate::gamesettings::testkit::install_version;

    #[test]
    fn an_installed_verified_version_maps_to_its_family() {
        let dir = TestDir::new();
        for (version, data, family) in [("26.1", 4786, "26.1"), ("26.1.1", 4788, "26.1"), ("26.1.2", 4790, "26.1"), ("26.2", 4903, "26.2"), ("26.3", 5023, "26.3")] {
            install_version(&dir.0, version, data);
            let support = resolve(&dir.0, version);
            assert_eq!(support.level, SupportLevel::Verified, "{version}");
            assert_eq!(support.family, Some(family));
            assert_eq!(support.data_version, Some(data));
            assert_eq!(support.family().map(|f| f.id), Some(family));
        }
    }

    #[test]
    fn a_jar_reporting_another_data_version_is_not_verified() {
        let dir = TestDir::new();
        install_version(&dir.0, "26.3", 5024);
        let support = resolve(&dir.0, "26.3");
        assert_eq!(support.level, SupportLevel::Unverified);
        assert_eq!(support.data_version, Some(5024));
        assert!(support.family().is_none());
        assert!(support.reason.unwrap().contains("5024"));
    }

    #[test]
    fn a_missing_jar_is_unknown_and_asks_for_an_install() {
        let dir = TestDir::new();
        let support = resolve(&dir.0, "26.2");
        assert_eq!(support.level, SupportLevel::Unknown);
        assert!(support.reason.unwrap().contains("Install or play this profile once first"));
    }

    #[test]
    fn listed_older_versions_are_unverified_and_others_unknown() {
        let dir = TestDir::new();
        let support = resolve(&dir.0, "1.21.11");
        assert_eq!(support.level, SupportLevel::Unverified);
        assert_eq!(support.reason.as_deref(), Some("Not yet verified against Minecraft 1.21.x"));
        assert_eq!(resolve(&dir.0, "1.21.1").level, SupportLevel::Unverified);
        assert_eq!(resolve(&dir.0, "1.8.9").level, SupportLevel::Unverified);
        assert_eq!(resolve(&dir.0, "1.20.1").level, SupportLevel::Unknown);
        assert_eq!(resolve(&dir.0, "../26.3").level, SupportLevel::Unknown);
    }

    #[test]
    fn the_data_version_is_read_again_when_the_jar_changes() {
        let dir = TestDir::new();
        install_version(&dir.0, "26.2", 4903);
        assert_eq!(resolve(&dir.0, "26.2").level, SupportLevel::Verified);
        // A different jar (another size) is read again rather than served from the memo.
        install_version(&dir.0, "26.2", 49030);
        assert_eq!(resolve(&dir.0, "26.2").level, SupportLevel::Unverified);
    }

    #[test]
    fn the_resource_pack_format_comes_from_the_jar() {
        let dir = TestDir::new();
        for (version, data, format) in [("26.1", 4786, (84, 0)), ("26.2", 4903, (88, 0)), ("26.3", 5023, (97, 1))] {
            install_version(&dir.0, version, data);
            assert_eq!(resource_format(&dir.0, version), Some(format), "{version}");
        }
        assert_eq!(resource_format(&dir.0, "26.1.1"), None, "not installed");
        assert_eq!(pack_format(&serde_json::json!({ "resource": 46, "data": 57 })), Some((46, 0)), "1.21.x style");
        assert_eq!(pack_format(&serde_json::json!(15)), Some((15, 0)), "a bare number");
        assert_eq!(pack_format(&serde_json::Value::Null), None);
    }

    #[test]
    fn a_damaged_jar_counts_as_not_installed() {
        let dir = TestDir::new();
        let jar = dir.0.join("versions/26.3/26.3.jar");
        std::fs::create_dir_all(jar.parent().unwrap()).unwrap();
        std::fs::write(&jar, b"not a zip").unwrap();
        assert_eq!(resolve(&dir.0, "26.3").level, SupportLevel::Unknown);
    }
}
