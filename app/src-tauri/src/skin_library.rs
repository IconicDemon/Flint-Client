//! The skin changer's list: skins the player added, by a Minecraft username or by an image's address, kept on this computer
//! per account. Adding never changes anything on the account: a skin only goes on when the player picks it, chooses Classic
//! or Slim and presses Apply, which uploads it with Minecraft's official skin endpoint (`skins::upload`), the same call the
//! Skins page's Apply makes. Shared by the launcher's wardrobe (the `skin_library_*` commands) and the game's Cosmetics
//! window (the game link's `skinsList`, `skinAdd`, `skinArt`, `skinApply`, `skinRemove`).
//!
//! - Stored in `<data>/skins/library/<account>/`: `library.json` (the list, newest first) and `<id>.png` per skin, where the
//!   id is the PNG's SHA-256. The same picture added twice is one entry (moved to the top).
//! - A username resolves through Mojang's profile lookup and session server to the player's current skin on
//!   `textures.minecraft.net` (its arm model kept as the suggestion). An address must be `https` and answer a PNG of 64×64 or
//!   64×32 within 64 KiB (Mojang's limits for an upload).
//! - Development and isolated runs (`FLINT_DATA_DIR`) may point the Mojang endpoints elsewhere (`FLINT_MOJANG_API_URL`,
//!   `FLINT_MOJANG_SESSION_URL`), for tests that must never touch a real account.
use crate::core::*;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// Mojang's upload limit for a skin file, and so the most an added skin may be.
pub const MAX_SKIN_BYTES: usize = 64 * 1024;
/// Skins kept per account at most (the oldest go first).
pub const MAX_SKINS: usize = 60;
const LOOKUP_TIMEOUT: Duration = Duration::from_secs(12);

/// Where usernames and textures are looked up.
#[derive(Clone, Debug, PartialEq)]
pub struct Mojang {
    /// `https://api.mojang.com`: username → id.
    pub api: String,
    /// `https://sessionserver.mojang.com`: id → textures.
    pub session: String,
    /// Textures may come from loopback too (tests and isolated QA runs only).
    pub loopback_textures: bool,
}

impl Mojang {
    pub fn official() -> Self {
        Self { api: "https://api.mojang.com".into(), session: "https://sessionserver.mojang.com".into(), loopback_textures: false }
    }

    /// The official endpoints; development and isolated runs may name others (`FLINT_MOJANG_API_URL`, `FLINT_MOJANG_SESSION_URL`).
    pub fn from_env() -> Self {
        let custom = cfg!(debug_assertions) || std::env::var_os("FLINT_DATA_DIR").is_some();
        let mut m = Self::official();
        if custom {
            if let Ok(api) = std::env::var("FLINT_MOJANG_API_URL") {
                m.api = api.trim_end_matches('/').to_owned();
                m.loopback_textures = true;
            }
            if let Ok(session) = std::env::var("FLINT_MOJANG_SESSION_URL") {
                m.session = session.trim_end_matches('/').to_owned();
                m.loopback_textures = true;
            }
        }
        m
    }
}

/// One skin in the list.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SavedSkin {
    /// The PNG's SHA-256 (64 lowercase hex): its file name too.
    pub id: String,
    /// What the list calls it: the player's name, or the file's name from the address.
    pub name: String,
    /// `username` or `url`.
    pub source: String,
    /// The username or the address it came from.
    pub origin: String,
    /// The arm model it suggests (`classic` or `slim`): Mojang's for a username, else guessed from the picture.
    pub variant: String,
    /// A 64×32 (pre-1.8) skin.
    pub legacy: bool,
    /// When it was added (ms since 1970).
    pub added_at: u64,
}

/// What a skin is added from.
#[derive(Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", tag = "from", content = "value")]
pub enum Source {
    Username(String),
    Url(String),
}

fn library_dir(root: &Path, account: &str) -> PathBuf {
    root.join("skins").join("library").join(account)
}

/// The library folder of `account` (32 lowercase hex) in the app's data.
pub fn dir_for(state: &AppState, account: &str) -> CmdResult<PathBuf> {
    let account = crate::store::canonical(account).ok_or("That isn't a Minecraft account.")?;
    Ok(library_dir(&state.paths.root, &account))
}

fn is_id(text: &str) -> bool {
    text.len() == 64 && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// A Minecraft username as Mojang allows one: 3–16 letters, digits and underscores (older names may be 1–2 or 17–25).
pub fn is_username(text: &str) -> bool {
    (1..=25).contains(&text.len()) && text.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

pub fn png_size(bytes: &[u8]) -> Option<(u32, u32)> {
    if bytes.len() < 24 || !bytes.starts_with(b"\x89PNG\r\n\x1a\n") || &bytes[12..16] != b"IHDR" {
        return None;
    }
    let w = u32::from_be_bytes(bytes[16..20].try_into().ok()?);
    let h = u32::from_be_bytes(bytes[20..24].try_into().ok()?);
    Some((w, h))
}

/// A skin file Minecraft takes: a PNG of 64×64 or 64×32 within [`MAX_SKIN_BYTES`]. True for 64×32.
pub fn check_skin_png(bytes: &[u8]) -> CmdResult<bool> {
    if bytes.len() > MAX_SKIN_BYTES {
        return Err("That skin is larger than 64 KB, Minecraft's limit for a skin file.".into());
    }
    match png_size(bytes) {
        Some((64, 64)) => Ok(false),
        Some((64, 32)) => Ok(true),
        Some((w, h)) => Err(format!("That picture is {w}×{h}. A Minecraft skin is a 64×64 (or old 64×32) PNG.")),
        None => Err("That address isn't a PNG picture. A Minecraft skin is a 64×64 (or old 64×32) PNG.".into()),
    }
}

/// The list of `account`, newest first (an unreadable list is empty; entries whose picture is gone are left out).
pub fn list_in(dir: &Path) -> Vec<SavedSkin> {
    let mut skins: Vec<SavedSkin> = read_json_opt(&dir.join("library.json")).unwrap_or_default();
    skins.retain(|s| is_id(&s.id) && dir.join(format!("{}.png", s.id)).is_file());
    skins
}

fn save_list(dir: &Path, skins: &[SavedSkin]) -> CmdResult<()> {
    std::fs::create_dir_all(dir).map_err(|e| format!("Couldn't save the skin list: {e}"))?;
    write_atomic(&dir.join("library.json"), &serde_json::to_vec_pretty(skins).map_err(|e| e.to_string())?)
}

/// The PNG of a listed skin.
pub fn png_in(dir: &Path, id: &str) -> CmdResult<Vec<u8>> {
    if !is_id(id) {
        return Err("That skin isn't in your list.".into());
    }
    let bytes = std::fs::read(dir.join(format!("{id}.png"))).map_err(|_| "That skin isn't in your list any more.".to_string())?;
    check_skin_png(&bytes)?;
    Ok(bytes)
}

/// Takes a listed skin out (its picture too). Not an error when it was already gone.
pub fn remove_in(dir: &Path, id: &str) -> CmdResult<Vec<SavedSkin>> {
    if !is_id(id) {
        return Err("That skin isn't in your list.".into());
    }
    let mut skins = list_in(dir);
    skins.retain(|s| s.id != id);
    save_list(dir, &skins)?;
    let _ = std::fs::remove_file(dir.join(format!("{id}.png")));
    Ok(skins)
}

/// Slim skins leave the outer column of each arm's front empty (as the Skins page guesses it).
pub fn guess_variant(bytes: &[u8]) -> &'static str {
    let Ok(image) = image_rgba(bytes) else { return "classic" };
    let (w, h, px) = image;
    if w != 64 || h != 64 {
        return "classic";
    }
    for y in 20..32 {
        for x in 54..56 {
            if px[(y * w + x) * 4 + 3] != 0 {
                return "classic";
            }
        }
    }
    "slim"
}

fn image_rgba(bytes: &[u8]) -> Result<(usize, usize, Vec<u8>), ()> {
    let decoder = png::Decoder::new(std::io::Cursor::new(bytes));
    let mut reader = decoder.read_info().map_err(|_| ())?;
    let mut buf = vec![0; reader.output_buffer_size().ok_or(())?];
    let info = reader.next_frame(&mut buf).map_err(|_| ())?;
    let (w, h) = (info.width as usize, info.height as usize);
    let rgba = match info.color_type {
        png::ColorType::Rgba if info.bit_depth == png::BitDepth::Eight => buf,
        png::ColorType::Rgb if info.bit_depth == png::BitDepth::Eight => buf.chunks(3).flat_map(|c| [c[0], c[1], c[2], 255]).collect(),
        _ => return Err(()),
    };
    Ok((w, h, rgba))
}

/// Adds a skin (already checked) to the list, newest first; the same picture again moves to the top.
pub fn keep_in(dir: &Path, bytes: &[u8], mut skin: SavedSkin) -> CmdResult<SavedSkin> {
    let legacy = check_skin_png(bytes)?;
    skin.id = hex::encode(Sha256::digest(bytes));
    skin.legacy = legacy;
    std::fs::create_dir_all(dir).map_err(|e| format!("Couldn't save the skin: {e}"))?;
    write_atomic(&dir.join(format!("{}.png", skin.id)), bytes)?;
    let mut skins = list_in(dir);
    skins.retain(|s| s.id != skin.id);
    skins.insert(0, skin.clone());
    for old in skins.drain(MAX_SKINS.min(skins.len())..) {
        let _ = std::fs::remove_file(dir.join(format!("{}.png", old.id)));
    }
    save_list(dir, &skins)?;
    Ok(skin)
}

async fn download_png(http: &reqwest::Client, url: reqwest::Url) -> CmdResult<Vec<u8>> {
    let mut response = http
        .get(url)
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .timeout(LOOKUP_TIMEOUT)
        .send()
        .await
        .map_err(|e| format!("Couldn't download that skin: {}", friendly_network(&e)))?;
    if !response.status().is_success() {
        return Err(format!("Couldn't download that skin: the address answered HTTP {}.", response.status().as_u16()));
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|e| format!("Couldn't download that skin: {}", friendly_network(&e)))? {
        bytes.extend_from_slice(&chunk);
        if bytes.len() > MAX_SKIN_BYTES {
            return Err("That skin is larger than 64 KB, Minecraft's limit for a skin file.".into());
        }
    }
    Ok(bytes)
}

fn loopback(url: &reqwest::Url) -> bool {
    url.scheme() == "http" && matches!(url.host_str(), Some("127.0.0.1" | "localhost"))
}

/// An image address a skin may be added from: https (with no user name or password), never a local or private network.
pub fn skin_url(text: &str, allow_loopback: bool) -> CmdResult<reqwest::Url> {
    let bad = || "That isn't a web address. Paste the skin picture's full address (https://…).".to_string();
    let url = reqwest::Url::parse(text.trim()).map_err(|_| bad())?;
    if allow_loopback && loopback(&url) {
        return Ok(url);
    }
    if url.scheme() != "https" {
        return Err("Use the picture's https:// address.".into());
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err(bad());
    }
    match url.host() {
        Some(url::Host::Domain(d)) if d.contains('.') && !d.eq_ignore_ascii_case("localhost") && !d.ends_with(".local") => Ok(url),
        Some(url::Host::Ipv4(ip)) if !(ip.is_private() || ip.is_loopback() || ip.is_link_local() || ip.is_unspecified()) => Ok(url),
        _ => Err("That address points at a local network. Use a public https:// address.".into()),
    }
}

/// The current skin (PNG, arm model) of the Minecraft player called `username`.
pub async fn skin_of(http: &reqwest::Client, mojang: &Mojang, username: &str) -> CmdResult<(String, Vec<u8>, &'static str)> {
    let name = username.trim();
    if !is_username(name) {
        return Err("A Minecraft username has only letters, digits and _ (up to 16 characters).".into());
    }
    let lookup = async {
        let response = http
            .get(format!("{}/users/profiles/minecraft/{name}", mojang.api))
            .header(reqwest::header::USER_AGENT, USER_AGENT)
            .send()
            .await
            .map_err(|e| format!("Couldn't reach Minecraft to look up {name}: {}", friendly_network(&e)))?;
        if matches!(response.status().as_u16(), 204 | 404) {
            return Err(format!("No Minecraft player is called {name}."));
        }
        if response.status().as_u16() == 429 {
            return Err("Minecraft is busy (too many lookups). Wait a minute and try again.".into());
        }
        let profile = response_json(response, "Couldn't look up that player").await?;
        let id = profile["id"].as_str().and_then(crate::store::canonical).ok_or_else(|| format!("No Minecraft player is called {name}."))?;
        let exact = profile["name"].as_str().filter(|n| is_username(n)).unwrap_or(name).to_owned();
        let session = get_json(http, &format!("{}/session/minecraft/profile/{id}", mojang.session), "Couldn't read that player's skin").await?;
        let (url, model) = texture_of(&session).ok_or_else(|| format!("{exact} uses Minecraft's default skin, which can't be added."))?;
        let url = reqwest::Url::parse(&url.replace("http://textures.minecraft.net", "https://textures.minecraft.net"))
            .map_err(|_| "Minecraft answered a skin address Flint can't read.".to_string())?;
        let allowed = url.host_str() == Some("textures.minecraft.net") && url.scheme() == "https" || mojang.loopback_textures && loopback(&url);
        if !allowed {
            return Err("Minecraft answered a skin address Flint can't read.".into());
        }
        let bytes = download_png(http, url).await?;
        check_skin_png(&bytes)?;
        Ok((exact, bytes, model))
    };
    tokio::time::timeout(LOOKUP_TIMEOUT * 2, lookup).await.map_err(|_| "Minecraft didn't answer in time. Try again.".to_string())?
}

/// The SKIN texture's address and arm model in a session-server profile.
pub fn texture_of(profile: &Value) -> Option<(String, &'static str)> {
    use base64::Engine;
    let encoded = profile["properties"].as_array()?.iter().find(|p| p["name"] == "textures")?["value"].as_str()?;
    let textures: Value = serde_json::from_slice(&base64::engine::general_purpose::STANDARD.decode(encoded).ok()?).ok()?;
    let skin = &textures["textures"]["SKIN"];
    let url = skin["url"].as_str()?.to_owned();
    Some((url, if skin["metadata"]["model"].as_str() == Some("slim") { "slim" } else { "classic" }))
}

/// The name a skin from `url` gets in the list: the file's name without `.png`, else the host.
fn name_from_url(url: &reqwest::Url) -> String {
    let file = url.path_segments().and_then(|mut s| s.next_back()).unwrap_or_default();
    let file = file.strip_suffix(".png").or_else(|| file.strip_suffix(".PNG")).unwrap_or(file);
    let clean: String = file.chars().filter(|c| c.is_alphanumeric() || matches!(c, '_' | '-' | ' ' | '.')).take(32).collect();
    if clean.is_empty() || clean.len() == 64 && clean.bytes().all(|b| b.is_ascii_hexdigit()) {
        url.host_str().unwrap_or("Skin").chars().take(32).collect()
    } else {
        clean
    }
}

/// Adds a skin to the list in `dir`: never applied. Errors are the player's to read.
pub async fn add_in(http: &reqwest::Client, mojang: &Mojang, dir: &Path, source: Source) -> CmdResult<SavedSkin> {
    let added_at = now_ms();
    match source {
        Source::Username(name) => {
            let (exact, bytes, model) = skin_of(http, mojang, &name).await?;
            keep_in(dir, &bytes, SavedSkin {
                id: String::new(),
                name: exact.clone(),
                source: "username".into(),
                origin: exact,
                variant: model.into(),
                legacy: false,
                added_at,
            })
        }
        Source::Url(text) => {
            let url = skin_url(&text, mojang.loopback_textures)?;
            let bytes = download_png(http, url.clone()).await?;
            check_skin_png(&bytes)?;
            let variant = guess_variant(&bytes);
            keep_in(dir, &bytes, SavedSkin {
                id: String::new(),
                name: name_from_url(&url),
                source: "url".into(),
                origin: url.to_string().chars().take(500).collect(),
                variant: variant.into(),
                legacy: false,
                added_at,
            })
        }
    }
}

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

// ---------- the app's side: the account's folder, the signed-in account for Apply ----------

/// Accounts the list may be kept for: Flint's signed-in Minecraft accounts (not the QA identity).
fn check_account(state: &AppState, account: &str) -> CmdResult<String> {
    let uuid = crate::store::canonical(account).ok_or("That isn't a Minecraft account.")?;
    let accounts = crate::auth::load_accounts(state);
    let found = accounts.accounts.iter().find(|a| crate::store::canonical(&a.uuid).as_deref() == Some(uuid.as_str()));
    match found {
        None => Err("That account isn't signed in to Flint.".into()),
        Some(a) => Ok(a.uuid.clone()),
    }
}

pub fn list(state: &AppState, account: &str) -> CmdResult<Vec<SavedSkin>> {
    check_account(state, account)?;
    Ok(list_in(&dir_for(state, account)?))
}

pub async fn add(state: &AppState, account: &str, source: Source) -> CmdResult<SavedSkin> {
    check_account(state, account)?;
    let dir = dir_for(state, account)?;
    add_in(&state.http, &Mojang::from_env(), &dir, source).await
}

pub fn remove(state: &AppState, account: &str, id: &str) -> CmdResult<Vec<SavedSkin>> {
    check_account(state, account)?;
    remove_in(&dir_for(state, account)?, id)
}

pub fn png(state: &AppState, account: &str, id: &str) -> CmdResult<Vec<u8>> {
    check_account(state, account)?;
    png_in(&dir_for(state, account)?, id)
}

/// Puts a listed skin on the account with Minecraft's skin endpoint (the Skins page's Apply): `variant` classic or slim.
/// Answers the profile as `skin_profile` describes it.
pub async fn apply(app: &tauri::AppHandle, state: &AppState, account: &str, id: &str, variant: &str) -> CmdResult<Value> {
    let uuid = check_account(state, account)?;
    let bytes = png_in(&dir_for(state, account)?, id)?;
    let variant = match variant {
        "slim" => "slim",
        "classic" => "classic",
        _ => return Err("Choose Classic or Slim.".into()),
    };
    crate::skins::upload(app, state, &uuid, bytes, variant).await
}

// ---------- the launcher's commands ----------

#[tauri::command]
pub fn skin_library_list(state: tauri::State<'_, AppState>, uuid: String) -> CmdResult<Vec<SavedSkin>> {
    list(&state, &uuid)
}

/// `source`: `{"from": "username", "value": "Notch"}` or `{"from": "url", "value": "https://…/skin.png"}`.
#[tauri::command]
pub async fn skin_library_add(state: tauri::State<'_, AppState>, uuid: String, source: Source) -> CmdResult<SavedSkin> {
    add(&state, &uuid, source).await
}

#[tauri::command]
pub fn skin_library_remove(state: tauri::State<'_, AppState>, uuid: String, id: String) -> CmdResult<Vec<SavedSkin>> {
    remove(&state, &uuid, &id)
}

/// A listed skin as a `data:image/png;base64,…` URL (for the wardrobe's preview).
#[tauri::command]
pub fn skin_library_png(state: tauri::State<'_, AppState>, uuid: String, id: String) -> CmdResult<String> {
    use base64::Engine;
    Ok(format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(png(&state, &uuid, &id)?)))
}

#[tauri::command]
pub async fn skin_library_apply(app: tauri::AppHandle, state: tauri::State<'_, AppState>, uuid: String, id: String, variant: String) -> CmdResult<Value> {
    apply(&app, &state, &uuid, &id, &variant).await
}

#[cfg(test)]
mod tests;
