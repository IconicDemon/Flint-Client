//! A slide's picture, fetched by the launcher so the window never loads an address from the feed itself: at most 2 MB,
//! it must hash to the SHA-256 its file name gives, be the PNG, JPEG or WebP its name says, and be the launcher's shape
//! (3:1, 1200×400 or more) by its own header. The window gets the checked bytes (and keeps them for the session).

use super::slide::{launcher_shape, Format, Slide};
use sha2::{Digest, Sha256};
use std::time::Duration;

/// Flint Admin and the showcase-media bucket allow 2 MB.
pub const MAX_IMAGE: usize = 2 * 1024 * 1024;
const TIMEOUT: Duration = Duration::from_secs(15);

#[derive(Debug, PartialEq, Eq)]
pub enum Rejected {
    /// Not reachable, not a success answer, or larger than 2 MB.
    Unavailable,
    /// The bytes don't hash to the slide's SHA-256.
    Checksum,
    /// Not the picture format its name says.
    Format,
    /// Its own size isn't the launcher's 3:1 shape (or can't be read).
    Shape,
}

/// The picture's format by its first bytes.
pub fn sniff(bytes: &[u8]) -> Option<Format> {
    match crate::partners::media::content_type(bytes)? {
        "image/png" => Some(Format::Png),
        "image/jpeg" => Some(Format::Jpeg),
        "image/webp" => Some(Format::Webp),
        _ => None,
    }
}

/// The width and height a PNG, JPEG or WebP gives in its header.
pub fn dimensions(bytes: &[u8]) -> Option<(u32, u32)> {
    match sniff(bytes)? {
        Format::Png => png_size(bytes),
        Format::Jpeg => jpeg_size(bytes),
        Format::Webp => webp_size(bytes),
    }
}

fn be16(b: &[u8], at: usize) -> Option<u32> {
    Some(u32::from(u16::from_be_bytes(b.get(at..at + 2)?.try_into().ok()?)))
}

fn le16(b: &[u8], at: usize) -> Option<u32> {
    Some(u32::from(u16::from_le_bytes(b.get(at..at + 2)?.try_into().ok()?)))
}

fn le24(b: &[u8], at: usize) -> Option<u32> {
    let s = b.get(at..at + 3)?;
    Some(u32::from(s[0]) | u32::from(s[1]) << 8 | u32::from(s[2]) << 16)
}

/// PNG: the IHDR chunk comes first, width and height big-endian.
fn png_size(b: &[u8]) -> Option<(u32, u32)> {
    if b.get(12..16)? != b"IHDR" {
        return None;
    }
    let w = u32::from_be_bytes(b.get(16..20)?.try_into().ok()?);
    let h = u32::from_be_bytes(b.get(20..24)?.try_into().ok()?);
    Some((w, h))
}

/// JPEG: the first start-of-frame marker (SOF0–SOF15, not DHT, JPG or DAC) gives height, then width.
fn jpeg_size(b: &[u8]) -> Option<(u32, u32)> {
    let mut i = 2;
    while i + 4 <= b.len() {
        if b[i] != 0xff {
            return None;
        }
        let marker = b[i + 1];
        if marker == 0xff {
            i += 1;
            continue;
        }
        if marker == 0x01 || (0xd0..=0xd8).contains(&marker) {
            i += 2;
            continue;
        }
        if marker == 0xd9 || marker == 0xda {
            return None;
        }
        let length = be16(b, i + 2)? as usize;
        if (0xc0..=0xcf).contains(&marker) && !matches!(marker, 0xc4 | 0xc8 | 0xcc) {
            return Some((be16(b, i + 7)?, be16(b, i + 5)?));
        }
        if length < 2 {
            return None;
        }
        i += 2 + length;
    }
    None
}

/// WebP: lossy (`VP8 `), lossless (`VP8L`) or extended (`VP8X`, the canvas size).
fn webp_size(b: &[u8]) -> Option<(u32, u32)> {
    match b.get(12..16)? {
        b"VP8 " => {
            if b.get(23..26)? != [0x9d, 0x01, 0x2a] {
                return None;
            }
            Some((le16(b, 26)? & 0x3fff, le16(b, 28)? & 0x3fff))
        }
        b"VP8L" => {
            if *b.get(20)? != 0x2f {
                return None;
            }
            let bits = u32::from_le_bytes(b.get(21..25)?.try_into().ok()?);
            Some(((bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1))
        }
        b"VP8X" => Some((le24(b, 24)? + 1, le24(b, 27)? + 1)),
        _ => None,
    }
}

/// Checks downloaded bytes against the slide: its checksum, its format, then its shape.
pub fn check(bytes: &[u8], slide: &Slide) -> Result<(), Rejected> {
    if bytes.len() > MAX_IMAGE {
        return Err(Rejected::Unavailable);
    }
    if hex::encode(Sha256::digest(bytes)) != slide.sha256 {
        return Err(Rejected::Checksum);
    }
    if sniff(bytes) != Some(slide.format) {
        return Err(Rejected::Format);
    }
    match dimensions(bytes) {
        Some((w, h)) if launcher_shape(w, h) => Ok(()),
        _ => Err(Rejected::Shape),
    }
}

/// Downloads at most 2 MB from `url` (already checked: slide::image_url).
pub async fn download(http: &reqwest::Client, url: &url::Url) -> Result<Vec<u8>, Rejected> {
    let response = http.get(url.clone()).timeout(TIMEOUT).send().await.map_err(|_| Rejected::Unavailable)?;
    if !response.status().is_success() {
        return Err(Rejected::Unavailable);
    }
    crate::store::read_capped(response, MAX_IMAGE).await.map_err(|_| Rejected::Unavailable)
}

/// The slide's picture, checked. The window keeps it for the session, so each picture is fetched about once a run.
pub async fn load(http: &reqwest::Client, slide: &Slide) -> Result<Vec<u8>, Rejected> {
    let bytes = download(http, &slide.image).await?;
    check(&bytes, slide).inspect_err(|why| eprintln!("[flint] a showcase picture was left out ({why:?})"))?;
    Ok(bytes)
}
