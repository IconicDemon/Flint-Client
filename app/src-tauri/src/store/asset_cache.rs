//! Flint's one cache of verified cosmetic files (cape textures, animation strips, 3D cosmetic files), shared by the launcher
//! and every game it starts (`game-runtime` `control/store/AssetCache.java`: the same layout and rules):
//! `<data>/cache/assets/<first 2 hex>/<sha256>`, content-addressed, so a file is downloaded once per computer whatever
//! account, profile or session wants it. Games learn the folder from the Store handoff (`assetCacheDir`, [`super::handoff`]).
//!
//! - Only files that passed the caller's checks (the Store's SHA-256, and its size or PNG checks) go in, and every read hashes
//!   the file again: one that doesn't hash to its name is deleted and downloaded again.
//! - Writes are atomic (a uniquely named `.tmp` in the same folder, renamed into place), so a reader in this process or a game
//!   never sees half a file; two writers of one file write the same bytes.
//! - At most [`MAX_BYTES`] (and [`MAX_FILES`] files): [`prune`] deletes the least recently used first; a read marks a file used
//!   (its modification time, at most every [`TOUCH`]: access times aren't kept on Windows).
//! - Downloads go to Flint's CDN first when the Store names one (`cdn_url`, the same file) and to the Store's `url` when that
//!   fails in any way; both are checked the same. Each download is logged with this session's totals.
use super::*;
use std::path::Path;
use std::sync::atomic::Ordering;
use std::time::SystemTime;

pub const MAX_BYTES: u64 = 1024 * 1024 * 1024;
pub const MAX_FILES: usize = 16_384;
/// A read marks a file used when it was last marked longer ago than this.
const TOUCH: Duration = Duration::from_secs(10 * 60);
/// A `.tmp` older than this is a crashed writer's; younger ones may still be being written (by a game).
const STALE_TEMP: Duration = Duration::from_secs(60 * 60);
/// [`put`] prunes after this many bytes written since the last prune.
const PRUNE_EVERY: u64 = 64 * 1024 * 1024;

static DOWNLOADS: AtomicU64 = AtomicU64::new(0);
static DOWNLOADED_BYTES: AtomicU64 = AtomicU64::new(0);
static HITS: AtomicU64 = AtomicU64::new(0);
static HIT_BYTES: AtomicU64 = AtomicU64::new(0);
static CDN_FALLBACKS: AtomicU64 = AtomicU64::new(0);
static SINCE_PRUNE: AtomicU64 = AtomicU64::new(0);
/// The cache folders tidied this session (once each: the old per-account caches removed, the cache pruned).
static TIDIED: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());

/// The shared folder: `<data>/cache/assets`.
pub fn dir(state: &AppState) -> PathBuf {
    state.paths.cache().join("assets")
}

pub fn is_sha256(text: &str) -> bool {
    text.len() == 64 && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// Where the file of hash `sha256` is kept: `<dir>/<first 2 hex>/<sha256>`.
pub fn file(dir: &Path, sha256: &str) -> PathBuf {
    dir.join(&sha256[..2]).join(sha256)
}

/// The file of hash `sha256` while it still hashes to it and is at most `max` bytes; one that changed on disk is deleted.
pub fn read(dir: &Path, sha256: &str, max: u64) -> Option<Vec<u8>> {
    if !is_sha256(sha256) {
        return None;
    }
    let path = file(dir, sha256);
    let meta = std::fs::metadata(&path).ok().filter(|m| m.is_file())?;
    if meta.len() > max {
        return None;
    }
    let bytes = std::fs::read(&path).ok()?;
    if sha256_hex(&bytes) != sha256 {
        // Changed on disk (or cut short outside Flint): never used; downloaded again.
        let _ = std::fs::remove_file(&path);
        return None;
    }
    let stale = meta.modified().ok().and_then(|m| SystemTime::now().duration_since(m).ok()).is_none_or(|age| age > TOUCH);
    if stale {
        if let Ok(f) = std::fs::OpenOptions::new().write(true).open(&path) {
            let _ = f.set_modified(SystemTime::now());
        }
    }
    HITS.fetch_add(1, Ordering::Relaxed);
    HIT_BYTES.fetch_add(bytes.len() as u64, Ordering::Relaxed);
    Some(bytes)
}

/// Removes a cached file (one that failed the caller's own checks).
pub fn remove(dir: &Path, sha256: &str) {
    if is_sha256(sha256) {
        let _ = std::fs::remove_file(file(dir, sha256));
    }
}

/// Keeps `bytes` as the file of hash `sha256`, atomically, unless it is there. Bytes that don't hash to it are never kept.
pub fn put(dir: &Path, sha256: &str, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    if !is_sha256(sha256) || sha256_hex(bytes) != sha256 {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "not that file"));
    }
    let path = file(dir, sha256);
    if std::fs::metadata(&path).is_ok_and(|m| m.is_file() && m.len() == bytes.len() as u64) {
        return Ok(());
    }
    let folder = path.parent().expect("a file in a folder");
    std::fs::create_dir_all(folder)?;
    let tmp = folder.join(format!("{}.{}.tmp", &sha256[..16], random_hex(8)));
    let written = (|| {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        drop(f);
        std::fs::rename(&tmp, &path)
    })();
    if written.is_err() {
        let _ = std::fs::remove_file(&tmp);
        // Another writer's rename got there first (or a game holds it open): the same bytes are in place.
        if std::fs::metadata(&path).is_ok_and(|m| m.len() == bytes.len() as u64) {
            return Ok(());
        }
        written?;
    }
    if SINCE_PRUNE.fetch_add(bytes.len() as u64, Ordering::Relaxed) + bytes.len() as u64 >= PRUNE_EVERY {
        SINCE_PRUNE.store(0, Ordering::Relaxed);
        prune(dir, MAX_BYTES, MAX_FILES);
    }
    Ok(())
}

/// Keeps the cache within `max_bytes` and `max_files` (the least recently used first) and deletes crashed writers' `.tmp`s.
pub fn prune(dir: &Path, max_bytes: u64, max_files: usize) {
    let Ok(top) = std::fs::read_dir(dir) else { return };
    let now = SystemTime::now();
    let mut files: Vec<(PathBuf, SystemTime, u64)> = Vec::new();
    for folder in top.flatten() {
        let name = folder.file_name().to_string_lossy().into_owned();
        if name.len() != 2 || !name.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')) || !folder.path().is_dir() {
            continue;
        }
        let Ok(inner) = std::fs::read_dir(folder.path()) else { continue };
        for entry in inner.flatten() {
            let n = entry.file_name().to_string_lossy().into_owned();
            let Ok(meta) = entry.metadata() else { continue };
            let modified = meta.modified().unwrap_or(SystemTime::UNIX_EPOCH);
            if n.ends_with(".tmp") {
                if now.duration_since(modified).is_ok_and(|age| age > STALE_TEMP) {
                    let _ = std::fs::remove_file(entry.path());
                }
            } else if meta.is_file() && is_sha256(&n) && n.starts_with(&name) {
                files.push((entry.path(), modified, meta.len()));
            }
        }
    }
    files.sort_by_key(|(_, modified, _)| *modified);
    let mut total: u64 = files.iter().map(|(_, _, len)| len).sum();
    let mut left = files.len();
    for (path, _, len) in files {
        if left <= max_files && total <= max_bytes {
            break;
        }
        let _ = std::fs::remove_file(path);
        left -= 1;
        total -= len;
    }
}

/// The per-account and community caches of earlier launchers (`cache/store/<origin>/<uuid>/assets`,
/// `cache/store/<origin>/community/assets`): everything in them is in the shared cache now, or downloaded there once.
pub fn remove_old(cache_root: &Path) {
    let Ok(origins) = std::fs::read_dir(cache_root.join("store")) else { return };
    for origin in origins.flatten() {
        let Ok(children) = std::fs::read_dir(origin.path()) else { continue };
        for child in children.flatten() {
            let assets = child.path().join("assets");
            if assets.is_dir() && std::fs::remove_dir_all(&assets).is_ok() {
                eprintln!("[flint] asset cache: removed the old cache {}", assets.display());
            }
        }
    }
}

/// Once a session for each data folder: the old caches go, and the shared one is pruned.
fn tidy(state: &AppState) {
    let dir = dir(state);
    {
        let mut done = TIDIED.lock().unwrap_or_else(|e| e.into_inner());
        if done.contains(&dir) {
            return;
        }
        done.push(dir.clone());
    }
    remove_old(&state.paths.cache());
    prune(&dir, MAX_BYTES, MAX_FILES);
}

/// One download from `raw` (an address Flint downloads from), at most `max` bytes. `noun`: "texture", "animation"… for
/// the messages.
async fn download(state: &AppState, raw: &str, max: usize, noun: &str) -> Result<Vec<u8>, String> {
    let url = asset_url(&state.store.endpoints, raw).ok_or_else(|| format!("The Flint Store listed this {noun} at an address Flint doesn't download from."))?;
    let response = state.http.get(url).timeout(REQUEST_TIMEOUT).send().await.map_err(|_| OFFLINE.to_owned())?;
    if !response.status().is_success() {
        return Err(format!("The {noun} couldn't be downloaded (HTTP {}).", response.status().as_u16()));
    }
    read_capped(response, max).await.map_err(|_| format!("The {noun} couldn't be downloaded completely."))
}

/// The file of hash `sha256` that passes `check` (which must compare the SHA-256, and may check more): from the shared cache,
/// else downloaded from `cdn` (Flint's CDN, when the Store names it) and, when that fails in any way, from `url`; kept in the
/// cache once checked. A failure is the `url`'s (or the check's) message. For any cosmetic file.
pub(crate) async fn fetch(
    state: &AppState,
    sha256: &str,
    cdn: Option<&str>,
    url: &str,
    max: usize,
    noun: &str,
    check: impl Fn(&[u8]) -> Result<(), String>,
) -> CmdResult<Vec<u8>> {
    let dir = dir(state);
    tidy(state);
    if let Some(bytes) = read(&dir, sha256, max as u64) {
        if check(&bytes).is_ok() {
            return Ok(bytes);
        }
        remove(&dir, sha256);
    }
    let cdn = cdn.filter(|c| *c != url && asset_url(&state.store.endpoints, c).is_some());
    if let Some(cdn) = cdn {
        match download(state, cdn, max, noun).await.and_then(|b| check(&b).map(|_| b)) {
            Ok(bytes) => {
                keep(&dir, sha256, &bytes, noun, "CDN");
                return Ok(bytes);
            }
            Err(error) => {
                CDN_FALLBACKS.fetch_add(1, Ordering::Relaxed);
                eprintln!("[flint] asset cache: the CDN didn't give {} ({error}); using the Store's address", &sha256[..sha256.len().min(12)]);
            }
        }
    }
    let bytes = download(state, url, max, noun).await?;
    check(&bytes)?;
    keep(&dir, sha256, &bytes, noun, "Store");
    Ok(bytes)
}

fn keep(dir: &Path, sha256: &str, bytes: &[u8], noun: &str, source: &str) {
    let n = DOWNLOADS.fetch_add(1, Ordering::Relaxed) + 1;
    let total = DOWNLOADED_BYTES.fetch_add(bytes.len() as u64, Ordering::Relaxed) + bytes.len() as u64;
    eprintln!(
        "[flint] asset cache: downloaded {noun} {} ({} KiB, {source}); this session {n} downloads ({} KiB), {} read from the cache ({} KiB), {} CDN fallbacks",
        &sha256[..sha256.len().min(12)],
        bytes.len() / 1024,
        total / 1024,
        HITS.load(Ordering::Relaxed),
        HIT_BYTES.load(Ordering::Relaxed) / 1024,
        CDN_FALLBACKS.load(Ordering::Relaxed)
    );
    if let Err(error) = put(dir, sha256, bytes) {
        eprintln!("[flint] couldn't keep a Store {noun}: {error}");
    }
}
