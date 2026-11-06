//! Whether Flint has checked a profile's Minecraft version against the game's own code, which decides
//! whether its settings may be changed.
//!
//! A version is VERIFIED only when its installed jar reports one of the data versions in the generated
//! tables (each read from that version's real jar), UNVERIFIED when Flint knows the version but hasn't
//! checked it (its settings are shown read-only), and UNKNOWN when there is nothing to go on (a version Flint
//! doesn't list, or a jar that isn't installed). Nothing is ever guessed from the version's name alone.
//!
//! Minecraft 1.8.9 has no `version.json` in its jar and no data version in its options.txt: it is verified
//! by the jar itself (Mojang's client jar, by its SHA-1), and its settings go through `legacy189`.

use super::legacy189;
use super::tables::{FamilyData, FAMILIES};
use sha1::{Digest, Sha1};
use serde::{Deserialize, Serialize};
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
    /// The verified family's tables; None for anything that isn't verified, and for 1.8.9 (whose options.txt
    /// is another format, see `legacy`).
    pub fn family(&self) -> Option<&'static FamilyData> {
        if self.level != SupportLevel::Verified {
            return None;
        }
        FAMILIES.iter().find(|f| Some(f.id) == self.family)
    }

    /// Minecraft 1.8.9, verified by its jar: its settings are read and written through `legacy189`.
    pub fn legacy(&self) -> bool {
        self.level == SupportLevel::Verified && self.family == Some(legacy189::FAMILY)
    }

    fn unknown(game_version: &str, reason: String) -> Self {
        Self { level: SupportLevel::Unknown, family: None, game_version: game_version.to_owned(), data_version: None, reason: Some(reason) }
    }

    fn unverified(game_version: &str, data_version: Option<u32>, reason: String) -> Self {
        Self { level: SupportLevel::Unverified, family: None, game_version: game_version.to_owned(), data_version, reason: Some(reason) }
    }
}

/// Versions Flint offers whose settings format hasn't been checked against the game's code yet (no local
/// jars to check them with). Their settings are shown, never written. Every version Flint offers today is
/// checked: 1.21.x and 26.x against the generated tables, 1.8.9 by its jar (`legacy189`).
const UNVERIFIED_VERSIONS: &[(&str, &str)] = &[];

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
    if game_version == legacy189::FAMILY {
        return resolve_legacy(game_root, game_version);
    }
    let Some((family, wanted)) = expected(game_version) else {
        return VersionSupport::unknown(game_version, format!("Flint doesn't know Minecraft {game_version}'s settings format."));
    };
    let jar = game_root.join("versions").join(game_version).join(format!("{game_version}.jar"));
    match jar_version(game_root, &jar) {
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

/// 1.8.9: verified when its installed jar is Mojang's (the SHA-1 its version manifest names, which is also
/// what the launcher checked the download against).
fn resolve_legacy(game_root: &Path, game_version: &str) -> VersionSupport {
    let jar = game_root.join("versions").join(game_version).join(format!("{game_version}.jar"));
    match legacy_jar_sha1(game_root, &jar) {
        None => VersionSupport::unknown(
            game_version,
            format!("Minecraft {game_version} isn't installed yet, so Flint can't confirm its settings format. Install or play this profile once first."),
        ),
        Some(sha) if !legacy189::known_jar(&sha) => {
            VersionSupport::unverified(game_version, None, format!("This copy of Minecraft {game_version} isn't Mojang's client jar, which Flint hasn't verified."))
        }
        Some(_) => VersionSupport { level: SupportLevel::Verified, family: Some(legacy189::FAMILY), game_version: game_version.to_owned(), data_version: None, reason: None },
    }
}

/// SHA-1s of 1.8.9 jars already read, by jar path (an 8 MB jar is hashed once until it changes).
static LEGACY_JARS: LazyLock<Mutex<HashMap<PathBuf, (JarKey, String)>>> = LazyLock::new(Default::default);

fn legacy_jar_sha1(game_root: &Path, jar: &Path) -> Option<String> {
    let meta = std::fs::metadata(jar).ok().filter(|m| m.is_file())?;
    let key = (meta.len(), meta.modified().ok());
    if let Some((seen, sha)) = LEGACY_JARS.lock().unwrap().get(jar) {
        if *seen == key {
            return Some(sha.clone());
        }
    }
    let sha = match Remembered::get(game_root, jar, key).and_then(|r| r.sha1) {
        Some(sha) => sha,
        None => {
            let mut file = std::fs::File::open(jar).ok()?;
            let mut hasher = Sha1::new();
            std::io::copy(&mut file, &mut hasher).ok()?;
            let sha = hex::encode(hasher.finalize());
            Remembered::put(game_root, jar, key, |r| r.sha1 = Some(sha.clone()));
            sha
        }
    };
    LEGACY_JARS.lock().unwrap().insert(jar.to_path_buf(), (key, sha.clone()));
    Some(sha)
}

/// The memos' copy on disk (`<game>/.flint-jar-info.json`): what each client jar's `version.json` says, or a 1.8.9
/// jar's SHA-1, with the size and modification time the jar had. A new run of Flint then doesn't open a 40 MB jar
/// (34,000 entries: a fifth of a second) to learn its version at the first Play.
#[derive(Serialize, Deserialize, Default, Clone)]
struct Remembered {
    size: u64,
    modified: u128,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    data: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    resource: Option<(u32, u32)>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    sha1: Option<String>,
}

/// One reader or writer of the file at a time.
static REMEMBERED: Mutex<()> = Mutex::new(());

impl Remembered {
    const FILE: &'static str = ".flint-jar-info.json";

    fn stamp(key: JarKey) -> Option<(u64, u128)> {
        Some((key.0, key.1?.duration_since(SystemTime::UNIX_EPOCH).ok()?.as_nanos()))
    }

    /// What was remembered for `jar` as it is now (same size and modification time).
    fn get(game_root: &Path, jar: &Path, key: JarKey) -> Option<Remembered> {
        let (size, modified) = Self::stamp(key)?;
        let _one = REMEMBERED.lock().unwrap_or_else(|e| e.into_inner());
        let all: HashMap<String, Remembered> = crate::core::read_json(&game_root.join(Self::FILE));
        all.get(jar.to_string_lossy().as_ref()).filter(|r| r.size == size && r.modified == modified).cloned()
    }

    /// Remembers something more about `jar` as it is now; what was remembered about an older copy is dropped.
    fn put(game_root: &Path, jar: &Path, key: JarKey, update: impl FnOnce(&mut Remembered)) {
        let Some((size, modified)) = Self::stamp(key) else { return };
        let _one = REMEMBERED.lock().unwrap_or_else(|e| e.into_inner());
        let path = game_root.join(Self::FILE);
        let mut all: HashMap<String, Remembered> = crate::core::read_json(&path);
        let entry = all.entry(jar.to_string_lossy().into_owned()).or_default();
        if entry.size != size || entry.modified != modified {
            *entry = Remembered { size, modified, ..Default::default() };
        }
        update(entry);
        let _ = crate::core::write_json(&path, &all);
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
fn jar_version(game_root: &Path, jar: &Path) -> Option<(String, u32)> {
    jar_info(game_root, jar).map(|info| (info.id, info.data))
}

fn jar_info(game_root: &Path, jar: &Path) -> Option<JarInfo> {
    let meta = std::fs::metadata(jar).ok().filter(|m| m.is_file())?;
    let key = (meta.len(), meta.modified().ok());
    if let Some((seen, found)) = JAR_VERSIONS.lock().unwrap().get(jar) {
        if *seen == key {
            return found.clone();
        }
    }
    let remembered = Remembered::get(game_root, jar, key).and_then(|r| Some(JarInfo { id: r.id?, data: r.data?, resource: r.resource }));
    let found = match remembered {
        Some(info) => Some(info),
        None => {
            let found = read_version_json(jar);
            if let Some(info) = &found {
                Remembered::put(game_root, jar, key, |r| {
                    r.id = Some(info.id.clone());
                    r.data = Some(info.data);
                    r.resource = info.resource;
                });
            }
            found
        }
    };
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
    jar_info(game_root, &game_root.join("versions").join(game_version).join(format!("{game_version}.jar")))?.resource
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
    // Buffered: reading a jar's index unbuffered is one system call per entry, and a client jar has thousands.
    let mut archive = zip::ZipArchive::new(std::io::BufReader::new(std::fs::File::open(jar).ok()?)).ok()?;
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
    use crate::gamesettings::testkit::{install_legacy_189, install_version};

    #[test]
    fn what_a_jar_says_is_remembered_on_disk_until_the_jar_changes() {
        let dir = TestDir::new();
        install_version(&dir.0, "26.3", 5023);
        install_legacy_189(&dir.0);
        assert_eq!(resolve(&dir.0, "26.3").data_version, Some(5023));
        assert_eq!(resolve(&dir.0, "1.8.9").level, SupportLevel::Verified);
        let jar = dir.0.join("versions").join("26.3").join("26.3.jar");
        let legacy = dir.0.join("versions").join("1.8.9").join("1.8.9.jar");
        let on_disk: HashMap<String, Remembered> = crate::core::read_json(&dir.0.join(Remembered::FILE));
        let entry = &on_disk[jar.to_string_lossy().as_ref()];
        assert_eq!((entry.id.as_deref(), entry.data, entry.resource), (Some("26.3"), Some(5023), Some((97, 1))));
        assert!(on_disk[legacy.to_string_lossy().as_ref()].sha1.is_some());

        // A new run of Flint (nothing in memory) takes it from disk while the jar keeps its size and time: proved by
        // a jar whose contents changed in place under the same stamp.
        let stamp = std::fs::metadata(&jar).unwrap().modified().unwrap();
        let len = std::fs::metadata(&jar).unwrap().len();
        std::fs::write(&jar, vec![0u8; len as usize]).unwrap();
        std::fs::File::options().write(true).open(&jar).unwrap().set_modified(stamp).unwrap();
        JAR_VERSIONS.lock().unwrap().remove(&jar);
        LEGACY_JARS.lock().unwrap().remove(&legacy);
        assert_eq!(resolve(&dir.0, "26.3").data_version, Some(5023));
        assert_eq!(resolve(&dir.0, "1.8.9").level, SupportLevel::Verified);

        // Another jar (a new stamp): read again.
        install_version(&dir.0, "26.3", 4903);
        assert_eq!(resolve(&dir.0, "26.3").data_version, Some(4903));
        std::fs::write(&legacy, b"not Mojang's jar").unwrap();
        assert_eq!(resolve(&dir.0, "1.8.9").level, SupportLevel::Unverified);
    }

    #[test]
    fn an_installed_verified_version_maps_to_its_family() {
        let dir = TestDir::new();
        for (version, data, family) in [
            ("1.21.1", 3955, "1.21.1"),
            ("1.21.10", 4556, "1.21.10"),
            ("1.21.11", 4671, "1.21.11"),
            ("26.1", 4786, "26.1"),
            ("26.1.1", 4788, "26.1"),
            ("26.1.2", 4790, "26.1"),
            ("26.2", 4903, "26.2"),
            ("26.3", 5023, "26.3"),
        ] {
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
    fn versions_flint_doesn_t_list_are_unknown() {
        let dir = TestDir::new();
        assert_eq!(resolve(&dir.0, "1.20.1").level, SupportLevel::Unknown);
        assert_eq!(resolve(&dir.0, "../26.3").level, SupportLevel::Unknown);
        assert_eq!(resolve(&dir.0, "1.21.11").level, SupportLevel::Unknown, "listed, but not installed");
    }

    #[test]
    fn minecraft_1_8_9_is_verified_by_its_jar() {
        let dir = TestDir::new();
        let missing = resolve(&dir.0, "1.8.9");
        assert_eq!(missing.level, SupportLevel::Unknown);
        assert!(!missing.legacy());
        // Mojang's jar (here the test build's stand-in for it): verified, with no tables and no data version.
        install_legacy_189(&dir.0);
        let support = resolve(&dir.0, "1.8.9");
        assert_eq!((support.level, support.family, support.data_version), (SupportLevel::Verified, Some("1.8.9"), None));
        assert!(support.legacy());
        assert!(support.family().is_none(), "1.8.9's options aren't read with the generated tables");
        assert_eq!(resource_format(&dir.0, "1.8.9"), None);
        // Any other jar under that name isn't.
        let jar = dir.0.join("versions/1.8.9/1.8.9.jar");
        std::fs::write(&jar, b"a different 1.8.9 jar, longer than the other").unwrap();
        let other = resolve(&dir.0, "1.8.9");
        assert_eq!(other.level, SupportLevel::Unverified);
        assert!(!other.legacy());
    }

    #[test]
    fn mojang_s_1_8_9_jar_is_known_by_its_sha1() {
        assert!(legacy189::known_jar("3870888a6c3d349d3771a3e9d16c9bf5e076b908"));
        assert!(!legacy189::known_jar("0000000000000000000000000000000000000000"));
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
        for (version, data, format) in [("1.21.1", 3955, (34, 0)), ("1.21.10", 4556, (69, 0)), ("1.21.11", 4671, (75, 0)), ("26.1", 4786, (84, 0)), ("26.2", 4903, (88, 0)), ("26.3", 5023, (97, 1))] {
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
        let jar = dir.0.join("versions").join("26.3").join("26.3.jar");
        std::fs::create_dir_all(jar.parent().unwrap()).unwrap();
        std::fs::write(&jar, b"not a zip").unwrap();
        assert_eq!(resolve(&dir.0, "26.3").level, SupportLevel::Unknown);
    }
}
