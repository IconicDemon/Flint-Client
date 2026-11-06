//! Partner pictures, fetched by the launcher so the window never loads a listing's address itself. The page asks for
//! `flint-partner://localhost/<sha256>` (`http://flint-partner.localhost/<sha256>` on Windows); only a checksum listed in
//! the current feed is served. A picture is downloaded once per checksum from the address the feed gave it (Partners API
//! v1: `partner-media/partners/<listing id>/<sha256>.<png|jpg|webp>` in an allowed Supabase project), at most 2 MB, must
//! hash to its checksum and be a PNG, JPEG or WebP, and is then kept in a small disk cache (`cache/partners/media`).

use crate::core::{write_atomic, AppState};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::time::Duration;
use url::Url;

pub const SCHEME: &str = "flint-partner";
const MAX_IMAGE: usize = 2 * 1024 * 1024;
/// The disk cache stays under this; pictures the current feed doesn't use go first, oldest first.
const MAX_CACHE: u64 = 48 * 1024 * 1024;
/// A picture no listing has used for this long is removed.
const UNUSED_FOR: Duration = Duration::from_secs(7 * 86_400);
const TIMEOUT: Duration = Duration::from_secs(20);

/// The address the page loads pictures from (the checksum follows).
pub fn base() -> String {
    if cfg!(windows) || cfg!(target_os = "android") {
        format!("http://{SCHEME}.localhost/")
    } else {
        format!("{SCHEME}://localhost/")
    }
}

pub fn dir(state: &AppState) -> PathBuf {
    state.paths.cache().join("partners").join("media")
}

fn is_sha(text: &str) -> bool {
    text.len() == 64 && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// An image address exactly as Partners API v1 serves one for listing `id` with checksum `sha`, from an allowed project.
pub fn media_url(raw: &str, id: &str, sha: &str, projects: &[String]) -> Option<Url> {
    let rest = raw.strip_prefix("https://")?;
    let (host, path) = rest.split_once('/')?;
    let project = host.strip_suffix(".supabase.co")?;
    let project_ok = project.len() == 20 && project.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit()) && (projects.is_empty() || projects.iter().any(|p| p == project));
    let file = path.strip_prefix("storage/v1/object/public/partner-media/partners/")?.strip_prefix(id)?.strip_prefix('/')?;
    let (name, ext) = file.split_once('.')?;
    (project_ok && crate::store::is_uuid(id) && name == sha && is_sha(sha) && matches!(ext, "png" | "jpg" | "webp")).then(|| Url::parse(raw).ok()).flatten()
}

/// Every picture the feed lists, by checksum, with the address to fetch it from. Pictures whose address isn't one Flint
/// fetches are left out (the page shows its own art instead).
pub fn index(body: &Value, projects: &[String]) -> HashMap<String, Url> {
    let mut out = HashMap::new();
    for partner in body["partners"].as_array().into_iter().flatten() {
        let Some(id) = partner["id"].as_str() else { continue };
        for field in ["featured_image", "library_image", "logo"] {
            let image = &partner[field];
            if let (Some(url), Some(sha)) = (image["url"].as_str(), image["sha256"].as_str()) {
                if let Some(url) = media_url(url, id, sha, projects) {
                    out.insert(sha.to_owned(), url);
                }
            }
        }
    }
    out
}

/// What a picture is, by its first bytes.
pub fn content_type(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some("image/png")
    } else if bytes.starts_with(&[0xff, 0xd8, 0xff]) {
        Some("image/jpeg")
    } else if bytes.len() > 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Some("image/webp")
    } else {
        None
    }
}

fn sha_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

/// The picture with checksum `sha`: from the disk cache when it still hashes right, otherwise from `url` (checked,
/// then kept). None when it can't be had or isn't what the feed said.
pub async fn fetch(http: &reqwest::Client, cache: &Path, sha: &str, url: &Url) -> Option<(Vec<u8>, &'static str)> {
    let path = cache.join(sha);
    if let Ok(bytes) = std::fs::read(&path) {
        if sha_hex(&bytes) == sha {
            if let Some(kind) = content_type(&bytes) {
                touch(&path);
                return Some((bytes, kind));
            }
        }
        let _ = std::fs::remove_file(&path);
    }
    let response = http.get(url.clone()).timeout(TIMEOUT).send().await.ok()?;
    if !response.status().is_success() {
        return None;
    }
    let bytes = crate::store::read_capped(response, MAX_IMAGE).await.ok()?;
    if sha_hex(&bytes) != sha {
        eprintln!("[flint] a partner picture didn't match its checksum, so Flint didn't show it");
        return None;
    }
    let kind = content_type(&bytes)?;
    if let Err(error) = write_atomic(&path, &bytes) {
        eprintln!("[flint] couldn't keep a partner picture: {error}");
    }
    Some((bytes, kind))
}

/// Marks a cached picture as used now (its modification time), so pruning keeps the ones still shown.
fn touch(path: &Path) {
    if let Ok(file) = std::fs::File::options().append(true).open(path) {
        let _ = file.set_modified(std::time::SystemTime::now());
    }
}

/// Serves `/<sha256>` for the page: only checksums the current feed lists.
pub async fn serve(state: &AppState, path: &str) -> Option<(Vec<u8>, &'static str)> {
    let sha = path.trim_start_matches('/');
    if !is_sha(sha) {
        return None;
    }
    let url = super::current_index(state).get(sha).cloned()?;
    fetch(&state.http, &dir(state), sha, &url).await
}

/// Keeps the disk cache small: pictures the feed no longer uses go after a week, and when it is over its size the
/// oldest unused ones go first. Pictures in use are never removed.
pub fn prune(cache: &Path, keep: &HashSet<String>) {
    let Ok(entries) = std::fs::read_dir(cache) else { return };
    let mut unused: Vec<(std::time::SystemTime, u64, PathBuf)> = Vec::new();
    let mut total = 0;
    for entry in entries.flatten() {
        let Ok(meta) = entry.metadata() else { continue };
        if !meta.is_file() {
            continue;
        }
        total += meta.len();
        let name = entry.file_name().to_string_lossy().into_owned();
        if !keep.contains(&name) {
            unused.push((meta.modified().unwrap_or(std::time::UNIX_EPOCH), meta.len(), entry.path()));
        }
    }
    unused.sort();
    for (modified, size, path) in unused {
        let old = modified.elapsed().is_ok_and(|age| age > UNUSED_FOR);
        if (old || total > MAX_CACHE) && std::fs::remove_file(&path).is_ok() {
            total = total.saturating_sub(size);
        }
    }
}
