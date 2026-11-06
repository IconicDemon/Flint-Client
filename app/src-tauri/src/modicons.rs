//! A mod's own name and logo, read from its jar: every Fabric (and Quilt) mod names an icon inside the jar in its
//! `fabric.mod.json`. Mods Flint installed from Modrinth carry Modrinth's icon; a jar added by hand, imported from
//! another launcher or unknown to Modrinth (a CurseForge-only mod, say) shows its own logo and name this way, and so
//! does every mod while Flint is offline.
//!
//! Icons reach the page through the `flint-modicon` scheme (`url`), one request per visible row, read off the main
//! thread. Only jars in that profile's own mods folder are opened, only a PNG named by the jar's metadata is returned,
//! and nothing in the jar is run.

use crate::core::AppState;
use serde_json::Value;
use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::SystemTime;

pub const SCHEME: &str = "flint-modicon";
/// The largest metadata file or icon read out of a jar.
const MAX_JSON: u64 = 1024 * 1024;
const MAX_ICON: u64 = 1024 * 1024;
/// The icon size a row shows best (a 36 px tile at up to 3× density); the closest declared size wins.
const WANT: u32 = 128;

/// What a jar says about itself, for a row that Modrinth hasn't named.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct JarInfo {
    pub name: Option<String>,
    pub version: Option<String>,
    pub icon: Option<String>,
}

/// A jar's size and modification time: what it was read at.
type Stamp = (u64, Option<SystemTime>);
type Cache<T> = std::sync::LazyLock<Mutex<HashMap<PathBuf, (Stamp, T)>>>;
static INFO: Cache<JarInfo> = std::sync::LazyLock::new(Default::default);
static ICONS: Cache<Option<Vec<u8>>> = std::sync::LazyLock::new(Default::default);

fn stamp(path: &Path) -> Option<Stamp> {
    let meta = std::fs::metadata(path).ok().filter(std::fs::Metadata::is_file)?;
    Some((meta.len(), meta.modified().ok()))
}

fn open(jar: &Path) -> Option<zip::ZipArchive<std::io::BufReader<std::fs::File>>> {
    zip::ZipArchive::new(std::io::BufReader::new(std::fs::File::open(jar).ok()?)).ok()
}

fn read_entry(archive: &mut zip::ZipArchive<std::io::BufReader<std::fs::File>>, name: &str, max: u64) -> Option<Vec<u8>> {
    let entry = archive.by_name(name.trim_start_matches('/')).ok()?;
    if entry.size() > max {
        return None;
    }
    let mut bytes = Vec::with_capacity(entry.size() as usize);
    entry.take(max).read_to_end(&mut bytes).ok()?;
    Some(bytes)
}

/// The icon path a mod declares: `"icon": "path"`, or `"icon": { "16": "a.png", "128": "b.png" }` (the size closest to
/// `WANT`, preferring larger ones on a tie). Quilt keeps it in `quilt_loader.metadata.icon`.
fn declared_icon(icon: &Value) -> Option<String> {
    match icon {
        Value::String(path) => Some(path.clone()),
        Value::Object(sizes) => sizes
            .iter()
            .filter_map(|(size, path)| Some((size.parse::<u32>().ok()?, path.as_str()?.to_owned())))
            .min_by_key(|(size, _)| (size.abs_diff(WANT), u32::MAX - size))
            .map(|(_, path)| path),
        _ => None,
    }
}

/// Parses `fabric.mod.json` or `quilt.mod.json` (a leading byte-order mark is allowed, as Fabric allows it).
pub fn parse_info(bytes: &[u8], quilt: bool) -> Option<JarInfo> {
    let json: Value = serde_json::from_slice(bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(bytes)).ok()?;
    let (meta, version) = if quilt {
        (&json["quilt_loader"]["metadata"], &json["quilt_loader"]["version"])
    } else {
        (&json, &json["version"])
    };
    let text = |v: &Value| v.as_str().map(str::trim).filter(|s| !s.is_empty() && s.len() <= 120).map(str::to_owned);
    Some(JarInfo { name: text(&meta["name"]), version: text(version).filter(|v| !v.contains("${")), icon: declared_icon(&meta["icon"]) })
}

/// A jar's name, version and icon path, read once per change of the file.
pub fn jar_info(jar: &Path) -> JarInfo {
    let Some(stamp) = stamp(jar) else { return JarInfo::default() };
    if let Some((seen, info)) = INFO.lock().unwrap_or_else(|e| e.into_inner()).get(jar) {
        if *seen == stamp {
            return info.clone();
        }
    }
    let info = open(jar)
        .and_then(|mut archive| {
            read_entry(&mut archive, "fabric.mod.json", MAX_JSON)
                .and_then(|b| parse_info(&b, false))
                .or_else(|| read_entry(&mut archive, "quilt.mod.json", MAX_JSON).and_then(|b| parse_info(&b, true)))
        })
        .unwrap_or_default();
    INFO.lock().unwrap_or_else(|e| e.into_inner()).insert(jar.to_owned(), (stamp, info.clone()));
    info
}

/// The PNG a jar declares as its icon, or None (no icon, not a PNG, too large, or the jar can't be read).
pub fn jar_icon(jar: &Path) -> Option<Vec<u8>> {
    let stamp = stamp(jar)?;
    if let Some((seen, icon)) = ICONS.lock().unwrap_or_else(|e| e.into_inner()).get(jar) {
        if *seen == stamp {
            return icon.clone();
        }
    }
    let icon = jar_info(jar).icon.and_then(|path| {
        let bytes = read_entry(&mut open(jar)?, &path, MAX_ICON)?;
        bytes.starts_with(b"\x89PNG\r\n\x1a\n").then_some(bytes)
    });
    let mut cache = ICONS.lock().unwrap_or_else(|e| e.into_inner());
    // Rows come and go as profiles change; a few hundred icons are plenty to keep.
    if cache.len() > 600 {
        cache.clear();
    }
    cache.insert(jar.to_owned(), (stamp, icon.clone()));
    icon
}

/// The address the page loads a mod's icon from (the platform's form for custom schemes, as startup.rs's pages).
pub fn url(profile_id: &str, file_name: &str) -> String {
    let base = if cfg!(windows) || cfg!(target_os = "android") { format!("http://{SCHEME}.localhost") } else { format!("{SCHEME}://localhost") };
    format!("{base}/{}/{}", crate::modrinth::encode(profile_id), crate::modrinth::encode(file_name))
}

/// Undoes `modrinth::encode` (form encoding: a space is `+`).
fn decode(part: &str) -> Option<String> {
    let bytes = part.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'+' {
            out.push(b' ');
            i += 1;
        } else if bytes[i] == b'%' {
            let hex = std::str::from_utf8(bytes.get(i + 1..i + 3)?).ok()?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

/// The jar a request path (`/<profile id>/<file name>`) names: a plain `.jar` file name in that profile's mods folder,
/// enabled or switched off. Anything else (another folder, `..`, a profile that doesn't exist) is None.
pub fn requested_jar(state: &AppState, path: &str) -> Option<PathBuf> {
    let mut parts = path.trim_start_matches('/').splitn(2, '/');
    let (profile, file) = (decode(parts.next()?)?, decode(parts.next()?)?);
    let plain = |s: &str| !s.is_empty() && s.len() <= 255 && !s.contains(['/', '\\', ':']) && s != "." && s != "..";
    if !plain(&profile) || !plain(&file) || !file.to_ascii_lowercase().ends_with(".jar") {
        return None;
    }
    crate::data::load_profiles(state).profiles.iter().find(|p| p.id == profile)?;
    let mods = state.paths.profile_dir(&profile).join("mods");
    [mods.join(&file), mods.join(format!("{file}.disabled"))].into_iter().find(|p| p.is_file())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_mods_name_version_and_icon_come_from_its_metadata() {
        let info = parse_info(br#"{"id":"sodium","name":"Sodium","version":"0.9.2+mc26.3","icon":"assets/sodium/icon.png"}"#, false).unwrap();
        assert_eq!(info, JarInfo { name: Some("Sodium".into()), version: Some("0.9.2+mc26.3".into()), icon: Some("assets/sodium/icon.png".into()) });
        // Sizes: the one nearest 128 px.
        let sized = parse_info(br#"{"id":"x","icon":{"16":"a.png","64":"b.png","512":"c.png"}}"#, false).unwrap();
        assert_eq!(sized.icon.as_deref(), Some("b.png"));
        let tie = parse_info(br#"{"id":"x","icon":{"64":"small.png","192":"big.png"}}"#, false).unwrap();
        assert_eq!(tie.icon.as_deref(), Some("big.png"));
        // Quilt keeps metadata elsewhere; a build placeholder isn't a version.
        let quilt = parse_info(br#"{"quilt_loader":{"version":"${version}","metadata":{"name":"Q","icon":"q.png"}}}"#, true).unwrap();
        assert_eq!((quilt.name.as_deref(), quilt.version, quilt.icon.as_deref()), (Some("Q"), None, Some("q.png")));
        // A byte-order mark is fine; broken JSON isn't.
        assert!(parse_info(b"\xEF\xBB\xBF{\"id\":\"x\"}", false).is_some());
        assert!(parse_info(b"{not json", false).is_none());
    }

    fn jar(dir: &Path, name: &str, files: &[(&str, &[u8])]) -> PathBuf {
        use std::io::Write;
        let path = dir.join(name);
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
        for (entry, bytes) in files {
            zip.start_file(*entry, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(bytes).unwrap();
        }
        zip.finish().unwrap();
        path
    }

    #[test]
    fn only_a_real_png_named_by_the_metadata_is_returned() {
        let dir = std::env::temp_dir().join(format!("flint-modicons-{}", crate::core::random_hex(6)));
        std::fs::create_dir_all(&dir).unwrap();
        let png: &[u8] = b"\x89PNG\r\n\x1a\nrest-of-image";
        let good = jar(&dir, "good.jar", &[("fabric.mod.json", br#"{"id":"good","name":"Good Mod","icon":"assets/good/icon.png"}"#), ("assets/good/icon.png", png)]);
        assert_eq!(jar_icon(&good).as_deref(), Some(png));
        assert_eq!(jar_info(&good).name.as_deref(), Some("Good Mod"));
        let fake = jar(&dir, "fake.jar", &[("fabric.mod.json", br#"{"id":"fake","icon":"icon.png"}"#), ("icon.png", b"<html>not an image</html>")]);
        assert_eq!(jar_icon(&fake), None, "only PNG files are served");
        let none = jar(&dir, "none.jar", &[("fabric.mod.json", br#"{"id":"none"}"#)]);
        assert_eq!(jar_icon(&none), None);
        let forge = jar(&dir, "forge.jar", &[("META-INF/mods.toml", b"modId=\"x\"")]);
        assert_eq!(jar_info(&forge), JarInfo::default());
        std::fs::write(dir.join("broken.jar"), b"not a zip").unwrap();
        assert_eq!(jar_icon(&dir.join("broken.jar")), None);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn icon_addresses_round_trip_and_never_leave_the_mods_folder() {
        let address = url("a1b2c3", "Xaero's Minimap 25.2 [Fabric].jar");
        let path = address.split_once(".localhost").or_else(|| address.split_once("://localhost")).unwrap().1;
        let mut parts = path.trim_start_matches('/').splitn(2, '/');
        assert_eq!(decode(parts.next().unwrap()).as_deref(), Some("a1b2c3"));
        assert_eq!(decode(parts.next().unwrap()).as_deref(), Some("Xaero's Minimap 25.2 [Fabric].jar"));
        assert_eq!(decode("%2"), None);
        let state = crate::core::AppState::for_tests(std::env::temp_dir().join(format!("flint-modicons-state-{}", crate::core::random_hex(6))));
        for bad in ["/../x.jar", "/p/..%2Fsecret.jar", "/p/a%5Cb.jar", "/p/options.txt", "/nobody/mod.jar", "/p/"] {
            assert_eq!(requested_jar(&state, bad), None, "{bad}");
        }
    }
}
