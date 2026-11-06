//! Animated Flint Store capes in the launcher's previews: the Store's `asset.animation` manifest (its `docs/LAUNCHER-API.md`
//! "Animated capes"), read as strictly as the game reads it (`game-runtime` `control/store/CapeAnimation.java`), and its PNG
//! strips, each downloaded only from an address Flint downloads textures from, checked against the Store's SHA-256 and its
//! PNG size before the interface gets it.
//!
//! - The manifest's timing and layout go to the interface (frame count, each frame's duration, frames per strip, scale); the
//!   strips' addresses and hashes stay here, as the poster's do.
//! - Anything that isn't exactly a version-1 manifest within the Store's limits is dropped (`None`): the cape then shows its
//!   poster, the asset's own texture, as a static cape, exactly as a launcher that doesn't read animations would.
//! - Strips are cached per account by SHA-256 next to its textures (cleared with them when the account leaves the Store) and
//!   rehashed on every read.
use super::*;

pub const LAYOUT: &str = "cape-atlas-22x17";
pub const ATLAS_WIDTH: u32 = 22;
pub const ATLAS_HEIGHT: u32 = 17;
/// Pixels per atlas unit the Store makes animations at: 88×68, 176×136, 352×272 (standard) and 704×544 (HD) frames.
pub const SCALES: [u32; 4] = [4, 8, 16, 32];
pub const MIN_FRAMES: u32 = 2;
pub const MAX_FRAMES: u32 = 120;
pub const MIN_FRAME_MS: u32 = 10;
pub const MAX_FRAME_MS: u32 = 10_000;
pub const MAX_LOOP_MS: u32 = 60_000;
pub const MAX_STRIP_HEIGHT: u32 = 2048;
pub const MAX_STRIP_BYTES: u64 = 8 * 1024 * 1024;
pub const MAX_TOTAL_STRIP_BYTES: u64 = 32 * 1024 * 1024;

/// One PNG of frames stacked top to bottom. Its address and hash stay in Rust: the interface asks for it by index.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Strip {
    #[serde(skip_serializing)]
    pub url: String,
    #[serde(skip_serializing)]
    pub sha256: String,
    #[serde(skip_serializing)]
    pub bytes: u64,
    pub frames: u32,
}

/// An animated cape's manifest, checked: the 22×17 cape atlas at `scale` pixels per unit, `durations_ms[f]` for frame `f`,
/// `frames_per_strip` frames to a strip. Shown to the interface in camelCase, without addresses or hashes.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Animation {
    pub scale: u32,
    /// One frame: 22 × scale by 17 × scale pixels.
    pub width: u32,
    pub height: u32,
    pub frame_count: u32,
    pub durations_ms: Vec<u32>,
    /// One loop: the durations' sum.
    pub duration_ms: u32,
    pub frames_per_strip: u32,
    pub strips: Vec<Strip>,
}

/// How many frames one strip holds for frames `height` pixels tall (the Store's `frames_per_strip`).
pub fn frames_per_strip(height: u32) -> u32 {
    (MAX_STRIP_HEIGHT / height.max(1)).max(1)
}

/// A whole number within ±2^53, as JavaScript writes them (`3.0` is 3; `3.5`, text and booleans are not numbers here).
fn whole(value: &Value) -> Option<i64> {
    match value {
        Value::Number(n) => n.as_i64().or_else(|| n.as_f64().filter(|f| f.is_finite() && f.fract() == 0.0 && f.abs() <= 9.007_199_254_740_992e15).map(|f| f as i64)),
        _ => None,
    }
}

fn whole_in(value: &Value, key: &str, range: std::ops::RangeInclusive<i64>) -> Option<u32> {
    whole(value.get(key)?).filter(|n| range.contains(n)).map(|n| n as u32)
}

fn is_sha256(text: &str) -> bool {
    text.len() == 64 && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// `asset.animation` as the Store sent it: `None` for a static cape (`null` or absent) and for anything that isn't exactly a
/// version-1 manifest within the Store's limits (the cape then shows its poster).
pub fn parse(value: &Value) -> Option<Animation> {
    let a = value.as_object()?;
    let field = |key: &str| a.get(key).unwrap_or(&Value::Null);
    if whole(field("v")) != Some(1) || field("layout").as_str() != Some(LAYOUT) {
        return None;
    }
    let scale = whole_in(value, "scale", 1..=64).filter(|s| SCALES.contains(s))?;
    let (width, height) = (ATLAS_WIDTH * scale, ATLAS_HEIGHT * scale);
    if whole(field("width")) != Some(width as i64) || whole(field("height")) != Some(height as i64) {
        return None;
    }
    let frame_count = whole_in(value, "frame_count", MIN_FRAMES as i64..=MAX_FRAMES as i64)?;
    let per = whole_in(value, "frames_per_strip", 1..=MAX_STRIP_HEIGHT as i64).filter(|&p| p == frames_per_strip(height))?;
    let durations = field("durations_ms").as_array().filter(|d| d.len() == frame_count as usize)?;
    let durations_ms = durations
        .iter()
        .map(|d| whole(d).filter(|d| (MIN_FRAME_MS as i64..=MAX_FRAME_MS as i64).contains(d)).map(|d| d as u32))
        .collect::<Option<Vec<u32>>>()?;
    let loop_ms: u64 = durations_ms.iter().map(|&d| d as u64).sum();
    if whole(field("duration_ms")) != Some(loop_ms as i64) || loop_ms > MAX_LOOP_MS as u64 {
        return None;
    }
    let count = frame_count.div_ceil(per);
    let listed = field("strips").as_array().filter(|s| s.len() == count as usize)?;
    let mut strips = Vec::with_capacity(listed.len());
    let mut total = 0u64;
    for (i, s) in listed.iter().enumerate() {
        let sha256 = s.get("sha256")?.as_str()?.to_ascii_lowercase();
        let url = s.get("url")?.as_str().filter(|u| !u.is_empty() && u.len() <= 2048)?.to_owned();
        let bytes = whole_in(s, "bytes", 1..=MAX_STRIP_BYTES as i64)? as u64;
        let frames = whole_in(s, "frames", 1..=MAX_FRAMES as i64)?;
        if !is_sha256(&sha256) || frames != per.min(frame_count - i as u32 * per) {
            return None;
        }
        total += bytes;
        strips.push(Strip { url, sha256, bytes, frames });
    }
    if total > MAX_TOTAL_STRIP_BYTES {
        return None;
    }
    Some(Animation { scale, width, height, frame_count, durations_ms, duration_ms: loop_ms as u32, frames_per_strip: per, strips })
}

/// For `#[serde(deserialize_with)]`: a manifest that isn't one never refuses the whole inventory, it only drops the animation.
pub fn lenient<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<Animation>, D::Error> {
    Ok(Option::<Value>::deserialize(deserializer)?.as_ref().and_then(parse))
}

/// A PNG's size from its IHDR chunk (right after the signature), without decoding it.
pub fn png_size(bytes: &[u8]) -> Option<(u32, u32)> {
    if bytes.len() < 24 || !bytes.starts_with(PNG_SIGNATURE) || &bytes[12..16] != b"IHDR" {
        return None;
    }
    let read = |at: usize| u32::from_be_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]]);
    Some((read(16), read(20)))
}

/// The strip's bytes when they are the PNG the manifest describes: its hash and its size (`width` × `frames × height`).
fn checked(animation: &Animation, strip: &Strip, bytes: &[u8]) -> Result<(), &'static str> {
    if sha256_hex(bytes) != strip.sha256 {
        return Err(HASH_MISMATCH);
    }
    if png_size(bytes) != Some((animation.width, strip.frames * animation.height)) {
        return Err("This cape's animation isn't the size the Flint Store listed, so Flint shows it without moving.");
    }
    Ok(())
}

/// Strip `index` of the animated cape `cosmetic_id` this account owns, as the Store last listed it: from the account's cache
/// by SHA-256 when it still matches, otherwise downloaded (without the Store session), checked and only then kept.
pub async fn strip(state: &AppState, uuid: &str, cosmetic_id: &str, index: usize) -> CmdResult<Vec<u8>> {
    let cached = load_cache(state, uuid).ok_or("Flint has no Store inventory for this account yet.")?;
    let animation = cached
        .inventory
        .owned
        .iter()
        .find(|o| o.cosmetic_id == cosmetic_id)
        .and_then(|o| o.asset.as_ref())
        .and_then(|a| a.animation.clone())
        .ok_or("This item isn't animated.")?;
    verified_strip(state, &animation, index, &account_dir(state, uuid).join("assets").join("strips")).await
}

/// Strip `index` of `animation`: from `dir` (by SHA-256) when it still matches, otherwise downloaded (without the Store
/// session), checked against the manifest's hash and size and only then kept in `dir`.
pub(crate) async fn verified_strip(state: &AppState, animation: &Animation, index: usize, dir: &std::path::Path) -> CmdResult<Vec<u8>> {
    let strip = animation.strips.get(index).ok_or("This animation has no such part.")?;
    let path = dir.join(format!("{}.png", strip.sha256));
    if let Ok(bytes) = std::fs::read(&path) {
        if checked(animation, strip, &bytes).is_ok() {
            return Ok(bytes);
        }
        // Changed on disk since it was checked: fetch it again.
        let _ = std::fs::remove_file(&path);
    }
    let url = asset_url(&state.store.endpoints, &strip.url).ok_or("The Flint Store listed this animation at an address Flint doesn't download from.")?;
    let response = state.http.get(url).timeout(REQUEST_TIMEOUT).send().await.map_err(|_| OFFLINE.to_owned())?;
    if !response.status().is_success() {
        return Err(format!("The animation couldn't be downloaded (HTTP {}).", response.status().as_u16()));
    }
    let bytes = read_capped(response, strip.bytes as usize).await.map_err(|_| "The animation couldn't be downloaded completely.".to_owned())?;
    checked(animation, strip, &bytes)?;
    if let Err(error) = write_atomic(&path, &bytes) {
        eprintln!("[flint] couldn't keep a Store animation: {error}");
    }
    Ok(bytes)
}

/// Strip `index` of an animated cape the account owns, as raw PNG bytes (an `ArrayBuffer` in the interface).
#[tauri::command]
pub async fn store_cape_strip(state: State<'_, AppState>, uuid: String, cosmetic_id: String, index: usize) -> CmdResult<tauri::ipc::Response> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    strip(&state, &uuid, &cosmetic_id, index).await.map(tauri::ipc::Response::new)
}
