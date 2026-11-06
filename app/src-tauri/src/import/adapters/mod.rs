//! One adapter per launcher. Each finds its launcher's data folders, proves there are real profiles, and lists them
//! (name, Minecraft version, loader, last played, icon, game folder). Adapters only read: databases are copied to a
//! temporary folder first and the copy is read. Each file's header cites where its format comes from; a launcher Flint
//! can't read reliably is reported as unreadable rather than guessed.

use super::{Env, LauncherId, Source};
use serde_json::Value;
use std::path::{Path, PathBuf};

/// What an adapter found in a folder.
#[derive(Debug)]
pub enum Found {
    /// The folder isn't this launcher's.
    NotHere,
    /// This launcher's folder, with its profiles (maybe none). A profile whose own metadata is damaged is listed with
    /// its `problem` set.
    Profiles(Vec<Source>),
    /// This launcher's folder, but its profile list can't be read (why, in one sentence).
    Unreadable(String),
}

pub trait Adapter: Sync {
    fn id(&self) -> LauncherId;
    fn name(&self) -> &'static str;
    /// Where it keeps its data on this computer, most likely first. Folders that don't exist are passed over.
    fn locations(&self, env: &Env) -> Vec<PathBuf>;
    /// Reads one of its data folders (or a folder the player chose, which may be anything).
    fn read(&self, root: &Path, env: &Env) -> Found;
    /// One of its instance folders, chosen by the player, when the instance's metadata is kept in it.
    fn instance(&self, _dir: &Path, _env: &Env) -> Option<Source> {
        None
    }
}

mod atlauncher;
mod curseforge;
mod dawn;
mod feather;
mod gdlauncher;
mod lunar;
mod modrinth;
mod official;
mod prism;

/// Every launcher Flint imports from. A folder the player chooses is tried against them in this order (instance
/// formats that share a file name, `instance.json`, are told apart by their fields).
pub fn all() -> Vec<&'static dyn Adapter> {
    vec![
        &modrinth::Modrinth,
        &curseforge::CurseForge,
        &prism::Prism,
        &prism::MultiMc,
        &lunar::Lunar,
        &feather::Feather,
        &dawn::Dawn,
        &gdlauncher::GdLauncher,
        &atlauncher::AtLauncher,
        &official::Official,
    ]
}

// ---------- platform ----------

pub fn windows() -> bool {
    cfg!(windows)
}

pub fn macos() -> bool {
    cfg!(target_os = "macos")
}

/// `base/<rel>` when `base` is known.
pub fn under(base: &Option<PathBuf>, rel: &str) -> Option<PathBuf> {
    base.as_ref().map(|b| super::scan::join(b, rel))
}

/// The official launcher's default game folder on this system (Lunar and Feather default to it too).
pub fn default_minecraft(env: &Env) -> Option<PathBuf> {
    if windows() {
        under(&env.appdata, ".minecraft")
    } else if macos() {
        under(&env.appdata, "minecraft")
    } else {
        under(&env.home, ".minecraft")
    }
}

// ---------- names, versions, loaders ----------

/// A single folder name from a launcher's records, safe to join to a folder (no separators, `..` or drive).
pub fn component(name: &str) -> Option<&str> {
    let name = name.trim();
    (!name.contains(['/', '\\']) && crate::flintpack::clean_path_within(name, 1, 255).is_some()).then_some(name)
}

/// A relative path from a launcher's records (`icon.png`, `assets/x.png`), safe to join below a folder.
pub fn relative(path: &str) -> Option<String> {
    let path = path.trim().replace('\\', "/");
    crate::flintpack::clean_path_within(&path, 16, 512).map(str::to_owned)
}

/// A launcher's name for a mod loader, any letter case.
pub fn loader_named(name: &str) -> super::ImportLoader {
    use super::ImportLoader as L;
    match name.trim().to_ascii_lowercase().as_str() {
        "vanilla" | "" => L::Vanilla,
        "fabric" => L::Fabric,
        "quilt" => L::Quilt,
        "forge" => L::Forge,
        "neoforge" => L::Neoforge,
        _ => L::Unknown,
    }
}

/// Text that looks like a Minecraft version id ("1.21.11", "26.3", "24w14a", "26.4-snapshot-2").
pub fn version_like(text: &str) -> bool {
    text.len() <= 32 && text.starts_with(|c: char| c.is_ascii_digit()) && text.chars().all(|c| c.is_ascii_alphanumeric() || ".-_".contains(c))
}

// ---------- INI (Prism, MultiMC) ----------

/// An INI file's keys and values, sections ignored (`instance.cfg`, `prismlauncher.cfg`, `multimc.cfg`). Handles both
/// dialects in use: Qt's (`[General]`, `%`-encoded keys, `"quoted"` values) and MultiMC's plain `key=value` with
/// `\n \t \\ \#` escapes. Later keys win.
pub fn ini(text: &str) -> std::collections::HashMap<String, String> {
    let mut out = std::collections::HashMap::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with(['#', ';', '[']) {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else { continue };
        let value = value.trim();
        let value = match value.strip_prefix('"').and_then(|v| v.strip_suffix('"')) {
            Some(quoted) => unescape(quoted),
            None => unescape(value),
        };
        out.insert(percent_decode(key.trim()), value);
    }
    out
}

fn unescape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('n') => out.push('\n'),
            Some('t') => out.push('\t'),
            Some(c @ ('\\' | '#' | '"' | ';' | '=')) => out.push(c),
            Some(other) => {
                out.push('\\');
                out.push(other);
            }
            None => out.push('\\'),
        }
    }
    out
}

fn percent_decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Some(b) = std::str::from_utf8(&bytes[i + 1..i + 3]).ok().and_then(|h| u8::from_str_radix(h, 16).ok()) {
                out.push(b);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

// ---------- JSON ----------

/// `value[key]`, the key matched in any letter case (CurseForge wrote `Name` in older files, `name` in newer).
pub fn get_ci<'a>(value: &'a Value, key: &str) -> Option<&'a Value> {
    let object = value.as_object()?;
    object.get(key).or_else(|| object.iter().find(|(k, _)| k.eq_ignore_ascii_case(key)).map(|(_, v)| v)).filter(|v| !v.is_null())
}

/// `get_ci` as trimmed, non-empty text.
pub fn text_ci(value: &Value, key: &str) -> Option<String> {
    get_ci(value, key)?.as_str().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned)
}

// ---------- shared reading helpers ----------

/// The most a launcher's metadata file may be.
pub const MAX_METADATA: u64 = 32 * 1024 * 1024;
/// The most instances read from one launcher folder.
pub const MAX_INSTANCES: usize = 5000;

/// A metadata file's text: None when it isn't there (or is a link), Err when it can't be read or is too large.
pub fn read_text(path: &Path) -> Option<Result<String, String>> {
    let meta = super::scan::real_file(path)?;
    let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    if meta.len() > MAX_METADATA {
        return Some(Err(format!("{name} is unexpectedly large.")));
    }
    Some(std::fs::read(path).map_err(|_| format!("Flint couldn't read {name}.")).map(|bytes| {
        let text = String::from_utf8_lossy(&bytes).into_owned();
        text.strip_prefix('\u{feff}').map(str::to_owned).unwrap_or(text)
    }))
}

/// A metadata file as JSON: None when it isn't there, Err when it can't be read or isn't JSON.
pub fn read_json(path: &Path) -> Option<Result<Value, String>> {
    let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    read_text(path).map(|text| text.and_then(|t| serde_json::from_str(&t).map_err(|_| format!("{name} is damaged (it isn't valid JSON)."))))
}

/// The sub-folders of `dir` that are real folders (not links), sorted by name, at most `MAX_INSTANCES`.
pub fn sub_dirs(dir: &Path) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    let mut dirs: Vec<PathBuf> = entries
        .flatten()
        .filter(|e| !e.file_name().to_string_lossy().starts_with('.'))
        .filter(|e| e.metadata().is_ok_and(|m| m.is_dir() && !super::scan::is_link(&m)))
        .map(|e| e.path())
        .collect();
    dirs.sort();
    dirs.truncate(MAX_INSTANCES);
    dirs
}

/// `value[key]` as trimmed, non-empty text.
pub fn text(value: &Value, key: &str) -> Option<String> {
    value.get(key)?.as_str().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned)
}

/// Seconds since 1970 from a number that may be seconds or milliseconds.
pub fn epoch_seconds(n: i64) -> Option<u64> {
    if n <= 0 {
        return None;
    }
    let n = n as u64;
    Some(if n > 100_000_000_000 { n / 1000 } else { n })
}

/// Seconds since 1970 from an RFC 3339 / ISO 8601 time ("2024-05-01T12:34:56.789Z", "+02:00" offsets, or a date and
/// time with a space). None when it can't be read exactly.
pub fn iso_seconds(text: &str) -> Option<u64> {
    let text = text.trim();
    let (date, rest) = text.split_at_checked(10)?;
    let mut d = date.split('-');
    let (y, m, day): (i64, i64, i64) = (d.next()?.parse().ok()?, d.next()?.parse().ok()?, d.next()?.parse().ok()?);
    if d.next().is_some() || !(1..=12).contains(&m) || !(1..=31).contains(&day) || y < 1970 {
        return None;
    }
    let rest = rest.strip_prefix(['T', 't', ' ']).unwrap_or(rest);
    let (clock, zone) = match rest.find(['Z', 'z', '+', '-']) {
        Some(at) => rest.split_at(at),
        None => (rest, ""),
    };
    let mut c = clock.split(':');
    let h: i64 = c.next().filter(|s| !s.is_empty()).map_or(Some(0), |s| s.parse().ok())?;
    let min: i64 = c.next().map_or(Some(0), |s| s.parse().ok())?;
    let sec: f64 = c.next().map_or(Some(0.0), |s| s.parse().ok())?;
    let offset = match zone.chars().next() {
        None | Some('Z') | Some('z') => 0,
        Some(sign) => {
            let digits: String = zone[1..].chars().filter(|c| c.is_ascii_digit()).collect();
            if digits.len() != 4 {
                return None;
            }
            let minutes = digits[..2].parse::<i64>().ok()? * 60 + digits[2..].parse::<i64>().ok()?;
            if sign == '-' {
                -minutes
            } else {
                minutes
            }
        }
    };
    // Days since 1970 (civil from days, Howard Hinnant's algorithm).
    let (yy, mm) = if m <= 2 { (y - 1, m + 9) } else { (y, m - 3) };
    let era = yy.div_euclid(400);
    let yoe = yy - era * 400;
    let doy = (153 * mm + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    let secs = days * 86_400 + h * 3600 + min * 60 + sec as i64 - offset * 60;
    (secs > 0).then_some(secs as u64)
}

/// A private, read-only copy of another launcher's SQLite database: the database and its `-wal` and `-shm` files are
/// copied into a fresh folder under `Env::temp` (opened with the sharing the launcher allows, so nothing is locked), and
/// the copy is opened read-only. The original is never opened by SQLite. The folder is removed when this is dropped.
pub struct DbCopy {
    conn: Option<rusqlite::Connection>,
    dir: PathBuf,
}

impl DbCopy {
    /// Copies and opens `db`. A database that changes while it's copied (its launcher is writing) is copied again, a
    /// few times; a database that never holds still is refused rather than read half-written.
    pub fn open(db: &Path, temp: &Path) -> Result<DbCopy, String> {
        let name = db.file_name().ok_or("No database file")?.to_owned();
        let companions = |suffix: &str| {
            let mut file = name.clone();
            file.push(suffix);
            db.with_file_name(file)
        };
        let sources = [db.to_path_buf(), companions("-wal"), companions("-shm")];
        let stamp = || sources.iter().map(|p| std::fs::metadata(p).ok().map(|m| (m.len(), m.modified().ok()))).collect::<Vec<_>>();
        for _ in 0..4 {
            let before = stamp();
            let dir = temp.join(format!("flint-import-{}", crate::core::random_hex(6)));
            // Removes its folder when dropped, whichever way this attempt ends.
            let mut copy = DbCopy { conn: None, dir };
            std::fs::create_dir_all(&copy.dir).map_err(|_| "Flint couldn't prepare a temporary folder to read it.".to_string())?;
            for source in &sources {
                if super::scan::real_file(source).is_none() {
                    continue;
                }
                let target = copy.dir.join(source.file_name().unwrap_or_default());
                let copied = std::fs::File::open(source).and_then(|mut from| std::fs::File::create(&target).and_then(|mut to| std::io::copy(&mut from, &mut to)));
                if let Err(e) = copied {
                    let _ = e;
                    return Err(format!("Flint couldn't read {}.", source.file_name().unwrap_or_default().to_string_lossy()));
                }
            }
            if stamp() != before {
                continue;
            }
            let flags = rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX;
            let conn = rusqlite::Connection::open_with_flags(copy.dir.join(&name), flags).map_err(|_| "Its database couldn't be opened.".to_string())?;
            copy.conn = Some(conn);
            return Ok(copy);
        }
        Err("Its database kept changing while Flint read it. Close the launcher and try again.".into())
    }

    pub fn conn(&self) -> &rusqlite::Connection {
        self.conn.as_ref().expect("open while the copy exists")
    }
}

impl Drop for DbCopy {
    fn drop(&mut self) {
        // Closed before its folder goes (Windows won't remove an open file).
        drop(self.conn.take());
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// The modification time of a file, in seconds since 1970.
pub fn modified(path: &Path) -> Option<u64> {
    std::fs::symlink_metadata(path).ok()?.modified().ok()?.duration_since(std::time::UNIX_EPOCH).ok().map(|d| d.as_secs())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;

    /// A database a launcher has open in WAL mode, with writes not yet checkpointed: the copy sees them, the original
    /// and its companions are untouched, and the temporary copy is gone afterwards.
    #[test]
    fn a_database_in_use_is_read_from_a_private_copy() {
        let dir = TestDir::new();
        let launcher = dir.0.join("launcher");
        std::fs::create_dir_all(&launcher).unwrap();
        let db = launcher.join("app.db");
        let writer = rusqlite::Connection::open(&db).unwrap();
        let mode: String = writer.query_row("PRAGMA journal_mode=WAL", [], |r| r.get(0)).unwrap();
        assert_eq!(mode, "wal");
        writer.execute_batch("PRAGMA wal_autocheckpoint=0; CREATE TABLE t (name TEXT); INSERT INTO t VALUES ('first'), ('second');").unwrap();
        assert!(launcher.join("app.db-wal").is_file(), "the rows are only in the WAL");
        let fingerprint = || {
            let mut files: Vec<(String, Vec<u8>)> = std::fs::read_dir(&launcher).unwrap().flatten().map(|e| (e.file_name().to_string_lossy().to_string(), std::fs::read(e.path()).unwrap())).collect();
            files.sort();
            files
        };
        let before = fingerprint();
        let temp = dir.0.join("temp");
        {
            let copy = DbCopy::open(&db, &temp).unwrap();
            let names: Vec<String> = copy.conn().prepare("SELECT name FROM t ORDER BY name").unwrap().query_map([], |r| r.get(0)).unwrap().flatten().collect();
            assert_eq!(names, ["first", "second"]);
            assert!(copy.conn().execute("INSERT INTO t VALUES ('x')", []).is_err(), "the copy is read-only");
            assert_eq!(std::fs::read_dir(&temp).unwrap().count(), 1);
        }
        assert_eq!(std::fs::read_dir(&temp).unwrap().count(), 0, "the copy is removed");
        assert_eq!(fingerprint(), before, "the launcher's files are untouched");
        drop(writer);
        // Not a database at all.
        std::fs::write(launcher.join("broken.db"), b"not sqlite").unwrap();
        let broken = DbCopy::open(&launcher.join("broken.db"), &temp).and_then(|c| c.conn().query_row("SELECT count(*) FROM sqlite_master", [], |r| r.get::<_, i64>(0)).map_err(|e| e.to_string()));
        assert!(broken.is_err());
        assert_eq!(std::fs::read_dir(&temp).unwrap().count(), 0);
    }
}
