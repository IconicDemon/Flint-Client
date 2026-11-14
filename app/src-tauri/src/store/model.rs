//! A 3D cosmetic's files (cosmetics v2: wings, hats, auras, pets, shields, gloves and emotes), as the Store
//! lists them in `asset.model`: Bedrock geometry, its texture, an optional animation and a 512×512 thumbnail, each with
//! its SHA-256 and size. Read leniently: a field Flint can't use is left out, never refusing the inventory. The files are
//! downloaded only on demand ([`model_file`]; Flint's CDN first when the Store names it), checked against the Store's SHA-256
//! and byte count, and kept in Flint's shared cache of verified files ([`super::asset_cache`]).
//!
//! The limits are the Store's upload limits (the Store enforces them; Flint checks them again before using a file).
use super::*;

/// The Store's upload limits per file, in bytes.
pub const MAX_GEOMETRY: u64 = 256 * 1024;
pub const MAX_ANIMATION: u64 = 256 * 1024;
pub const MAX_TEXTURE: u64 = 2 * 1024 * 1024;
pub const MAX_THUMBNAIL: u64 = 512 * 1024;
/// Texture animation: at most this many frames, each shown at least this long.
pub const MAX_FRAMES: u32 = 12;
pub const MIN_FRAME_MS: u32 = 50;
/// The bones a cosmetic is attached to (the geometry's root bone).
pub const ANCHORS: [&str; 4] = ["anchor_body", "anchor_head", "anchor_right_arm", "anchor_left_arm"];

/// One of the model's files. `url` and `sha256` stay in Rust; the interface learns that the file exists and its size.
#[derive(Deserialize, Serialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all(serialize = "camelCase"))]
pub struct ModelFile {
    #[serde(default, skip_serializing)]
    pub url: String,
    #[serde(default, skip_serializing)]
    pub sha256: String,
    /// The same file on Flint's CDN (`cdn_url`), tried first; absent until the Store names one.
    #[serde(default, skip_serializing)]
    pub cdn_url: Option<String>,
    #[serde(default, deserialize_with = "lenient_u64")]
    pub bytes: Option<u64>,
    #[serde(default, deserialize_with = "lenient_u32")]
    pub width: Option<u32>,
    #[serde(default, deserialize_with = "lenient_u32")]
    pub height: Option<u32>,
}

#[derive(Deserialize, Serialize, Clone, Debug, PartialEq)]
pub struct Attach {
    pub anchor: String,
    #[serde(default)]
    pub side: Option<String>,
}

#[derive(Deserialize, Serialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all(serialize = "camelCase"))]
pub struct Model {
    #[serde(default, deserialize_with = "lenient_file")]
    pub geometry: Option<ModelFile>,
    #[serde(default, deserialize_with = "lenient_file")]
    pub texture: Option<ModelFile>,
    #[serde(default, deserialize_with = "lenient_file")]
    pub animation: Option<ModelFile>,
    #[serde(default, deserialize_with = "lenient_file")]
    pub thumbnail: Option<ModelFile>,
    #[serde(default, deserialize_with = "lenient_u32")]
    pub texture_frames: Option<u32>,
    /// One duration per frame (one value from the Store means every frame).
    #[serde(default, deserialize_with = "lenient_frame_ms")]
    pub frame_ms: Vec<u32>,
    #[serde(default, deserialize_with = "lenient_attach")]
    pub attach: Option<Attach>,
    #[serde(default, deserialize_with = "lenient_bool")]
    pub hides_cape: bool,
}

fn lenient_u64<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<u64>, D::Error> {
    Ok(Option::<Value>::deserialize(deserializer)?.and_then(|v| v.as_u64()))
}

fn lenient_u32<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<u32>, D::Error> {
    Ok(Option::<Value>::deserialize(deserializer)?.and_then(|v| v.as_u64()).and_then(|n| u32::try_from(n).ok()))
}

fn lenient_bool<'de, D: Deserializer<'de>>(deserializer: D) -> Result<bool, D::Error> {
    Ok(Option::<Value>::deserialize(deserializer)?.and_then(|v| v.as_bool()).unwrap_or(false))
}

fn lenient_file<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<ModelFile>, D::Error> {
    Ok(Option::<Value>::deserialize(deserializer)?.and_then(|v| serde_json::from_value(v).ok()))
}

fn lenient_attach<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<Attach>, D::Error> {
    Ok(Option::<Value>::deserialize(deserializer)?.and_then(|v| serde_json::from_value(v).ok()))
}

fn lenient_frame_ms<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Vec<u32>, D::Error> {
    let ms = |v: &Value| v.as_u64().map(|n| n.min(60_000) as u32);
    Ok(match Option::<Value>::deserialize(deserializer)? {
        Some(Value::Array(list)) => list.iter().filter_map(ms).collect(),
        Some(one) => ms(&one).into_iter().collect(),
        None => Vec::new(),
    })
}

/// `asset.model` as it came, or none when it isn't an object Flint can read.
pub fn lenient<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<Model>, D::Error> {
    Ok(Option::<Value>::deserialize(deserializer)?.filter(Value::is_object).and_then(|v| serde_json::from_value(v).ok()))
}

fn is_sha256(text: &str) -> bool {
    text.len() == 64 && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// The part of a model the interface may ask for, and the most bytes it may have.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Part {
    Geometry,
    Texture,
    Animation,
    Thumbnail,
}

impl Part {
    pub fn parse(name: &str) -> Option<Part> {
        Some(match name {
            "geometry" => Part::Geometry,
            "texture" => Part::Texture,
            "animation" => Part::Animation,
            "thumbnail" => Part::Thumbnail,
            _ => return None,
        })
    }

    fn max(self) -> u64 {
        match self {
            Part::Geometry => MAX_GEOMETRY,
            Part::Animation => MAX_ANIMATION,
            Part::Texture => MAX_TEXTURE,
            Part::Thumbnail => MAX_THUMBNAIL,
        }
    }

    fn of(self, model: &Model) -> Option<&ModelFile> {
        match self {
            Part::Geometry => model.geometry.as_ref(),
            Part::Texture => model.texture.as_ref(),
            Part::Animation => model.animation.as_ref(),
            Part::Thumbnail => model.thumbnail.as_ref(),
        }
    }
}

impl Model {
    /// The model with only what Flint can use: files with a hash and a size within the Store's limits, an anchor Flint
    /// knows, frames and durations within budget. None when nothing usable is left. An emote keeps its animation and
    /// thumbnail only.
    pub fn checked(mut self, kind: &str) -> Option<Model> {
        let keep = |file: Option<ModelFile>, part: Part| file.filter(|f| !f.url.is_empty() && is_sha256(&f.sha256) && f.bytes.is_some_and(|b| b > 0 && b <= part.max()));
        self.geometry = keep(self.geometry.take(), Part::Geometry);
        self.texture = keep(self.texture.take(), Part::Texture).filter(|f| f.width.is_none_or(|w| w <= 1024) && f.height.is_none_or(|h| h <= 1024 * MAX_FRAMES));
        self.animation = keep(self.animation.take(), Part::Animation);
        self.thumbnail = keep(self.thumbnail.take(), Part::Thumbnail);
        if kind == kinds::EMOTE {
            self.geometry = None;
            self.texture = None;
        }
        self.texture_frames = self.texture_frames.filter(|n| (1..=MAX_FRAMES).contains(n));
        self.frame_ms.truncate(MAX_FRAMES as usize);
        for ms in &mut self.frame_ms {
            *ms = (*ms).max(MIN_FRAME_MS);
        }
        self.attach = self.attach.take().filter(|a| ANCHORS.contains(&a.anchor.as_str())).map(|mut a| {
            a.side = a.side.filter(|s| s == "left" || s == "right");
            a
        });
        if kind != "wings" {
            self.hides_cape = false;
        }
        let usable = self.geometry.is_some() || self.animation.is_some() || self.thumbnail.is_some();
        usable.then_some(self)
    }
}

const WEBP: (&[u8], &[u8]) = (b"RIFF", b"WEBP");

fn image_type(bytes: &[u8], part: Part) -> Option<&'static str> {
    if bytes.starts_with(PNG_SIGNATURE) {
        return Some("image/png");
    }
    let webp = bytes.len() > 12 && bytes.starts_with(WEBP.0) && &bytes[8..12] == WEBP.1;
    (part == Part::Thumbnail && webp).then_some("image/webp")
}

/// A file of a 3D cosmetic for a game (`modelFile`): one the account owns, or one the Store lists in its catalog (another
/// player's emote, a locked item's preview), as the bytes Flint checked (the Store's SHA-256 and size, that a picture is one
/// and that a model file parses). From the shared cache when it is there.
pub async fn model_bytes(state: &AppState, uuid: &str, cosmetic_id: &str, part: &str) -> CmdResult<Vec<u8>> {
    let part = Part::parse(part).ok_or("That isn't part of a cosmetic.")?;
    let owned = load_cache(state, uuid).and_then(|c| c.inventory.owned.iter().find(|o| o.cosmetic_id == cosmetic_id).and_then(|o| o.asset.clone()));
    let asset = match owned {
        Some(a) => Some(a),
        None => shop::listed(state).await.into_iter().find(|s| s.cosmetic_id == cosmetic_id).and_then(|s| s.asset),
    };
    let asset = asset.ok_or("The Flint Store doesn't list that item.")?;
    let file = asset.model.as_ref().and_then(|m| part.of(m)).ok_or("This item doesn't have that file.")?;
    let expected = file.bytes.unwrap_or(0) as usize;
    verified_download(state, &file.url, file.cdn_url.as_deref(), &file.sha256, part.max() as usize, |bytes| {
        if bytes.len() != expected {
            return Err("This item's file isn't the size the Flint Store listed, so Flint didn't use it.".into());
        }
        match part {
            Part::Texture | Part::Thumbnail => image_type(bytes, part).map(|_| ()).ok_or_else(|| "This item's picture isn't a PNG or WebP image.".to_owned()),
            Part::Geometry | Part::Animation => serde_json::from_slice::<Value>(bytes).map(|_| ()).map_err(|_| "This item's model file isn't one Flint can read.".to_owned()),
        }
    })
    .await
}

/// A file of a 3D cosmetic this account owns, as the Store last listed it: from the shared cache when its SHA-256
/// still matches, otherwise downloaded (without the Store session) and checked against the Store's SHA-256 and size.
/// Pictures (texture, thumbnail) come back as data URLs; geometry and animation as their JSON text, once it parses.
pub async fn model_file(state: &AppState, uuid: &str, cosmetic_id: &str, part: &str) -> CmdResult<String> {
    let part = Part::parse(part).ok_or("That isn't part of a cosmetic.")?;
    let cached = load_cache(state, uuid).ok_or("Flint has no Store inventory for this account yet.")?;
    let owned = cached.inventory.owned.iter().find(|o| o.cosmetic_id == cosmetic_id).ok_or("This account doesn't own that item.")?;
    let asset = owned.asset.as_ref().ok_or("This item has no files.")?;
    let file = asset.model.as_ref().and_then(|m| part.of(m)).ok_or("This item doesn't have that file.")?;
    let expected = file.bytes.unwrap_or(0) as usize;
    let bytes = verified_download(state, &file.url, file.cdn_url.as_deref(), &file.sha256, part.max() as usize, |bytes| {
        if bytes.len() != expected {
            return Err("This item's file isn't the size the Flint Store listed, so Flint didn't use it.".into());
        }
        match part {
            Part::Texture | Part::Thumbnail => image_type(bytes, part).map(|_| ()).ok_or_else(|| "This item's picture isn't a PNG or WebP image.".to_owned()),
            Part::Geometry | Part::Animation => serde_json::from_slice::<Value>(bytes).map(|_| ()).map_err(|_| "This item's model file isn't one Flint can read.".to_owned()),
        }
    })
    .await?;
    Ok(match part {
        Part::Texture | Part::Thumbnail => format!("data:{};base64,{}", image_type(&bytes, part).unwrap_or("image/png"), STANDARD.encode(&bytes)),
        Part::Geometry | Part::Animation => String::from_utf8(bytes).map_err(|_| "This item's model file isn't one Flint can read.".to_owned())?,
    })
}
