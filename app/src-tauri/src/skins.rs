use crate::auth::{load_accounts, minecraft_token, minecraft_token_explicit, session_ready, update_skin_model};
use crate::core::*;
use crate::data::forget_skin;
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use serde_json::{json, Value};
use tauri::{AppHandle, State};

const PROFILE: &str = "https://api.minecraftservices.com/minecraft/profile";

fn check_account(state: &AppState, uuid: &str) -> CmdResult<()> {
    let accounts = load_accounts(state);
    let account = accounts.accounts.iter().find(|a| a.uuid == uuid).ok_or("That account isn't signed in.")?;
    if account.qa {
        return Err("The local QA identity has no Minecraft profile to change.".into());
    }
    Ok(())
}

/// For changes the player asked for (upload, reset, cape): may ask macOS for keychain access.
async fn token_for(app: &AppHandle, state: &AppState, uuid: &str) -> CmdResult<String> {
    check_account(state, uuid)?;
    minecraft_token_explicit(app, state, uuid).await
}

/// The owned capes and arm model, remembered so the Skins page can show them without signing in.
/// No secrets: cape ids, names and their public textures.
fn profile_cache(state: &AppState, uuid: &str) -> std::path::PathBuf {
    state.paths.cache().join("skins").join(format!("{uuid}-profile.json"))
}

fn remember_profile(state: &AppState, uuid: &str, described: &Value) {
    let path = profile_cache(state, uuid);
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let _ = std::fs::write(path, described.to_string());
}

async fn texture_data_url(state: &AppState, url: &str) -> Option<String> {
    let parsed = url::Url::parse(&url.replace("http://", "https://")).ok()?;
    if parsed.host_str() != Some("textures.minecraft.net") {
        return None;
    }
    let bytes = state.http.get(parsed).send().await.ok()?.error_for_status().ok()?.bytes().await.ok()?;
    (bytes.len() < 512 * 1024 && bytes.starts_with(b"\x89PNG")).then(|| format!("data:image/png;base64,{}", STANDARD.encode(&bytes)))
}

async fn describe(state: &AppState, profile: &Value) -> Value {
    let mut capes = Vec::new();
    for cape in profile["capes"].as_array().into_iter().flatten().take(24) {
        let url = cape["url"].as_str().unwrap_or_default();
        capes.push(json!({
            "id": cape["id"],
            "alias": cape["alias"],
            "active": cape["state"] == "ACTIVE",
            "dataUrl": texture_data_url(state, url).await,
        }));
    }
    let active = profile["skins"].as_array().and_then(|s| s.iter().find(|x| x["state"] == "ACTIVE").or_else(|| s.first())).cloned().unwrap_or(Value::Null);
    json!({
        "name": profile["name"],
        "variant": if active["variant"].as_str().is_some_and(|v| v.eq_ignore_ascii_case("slim")) { "slim" } else { "classic" },
        "capes": capes,
    })
}

/// The account's owned capes and arm model. Opening the Skins page calls this with
/// `interactive: false`: it uses the live session when this run already has one, otherwise the
/// remembered profile (`"saved": true`), otherwise it answers `{"locked": true}` without touching
/// the keychain. Only `interactive: true` (the player pressed a button) may ask macOS.
#[tauri::command]
pub async fn skin_profile(app: AppHandle, state: State<'_, AppState>, uuid: String, interactive: Option<bool>) -> CmdResult<Value> {
    check_account(&state, &uuid)?;
    let token = if session_ready(&state, &uuid) {
        minecraft_token(&app, &state, &uuid).await?
    } else if interactive.unwrap_or(false) {
        minecraft_token_explicit(&app, &state, &uuid).await?
    } else {
        let saved = std::fs::read(profile_cache(&state, &uuid)).ok().and_then(|b| serde_json::from_slice::<Value>(&b).ok());
        return Ok(match saved {
            Some(mut profile) if profile["capes"].is_array() => {
                profile["saved"] = json!(true);
                profile
            }
            _ => json!({ "locked": true }),
        });
    };
    let response = state.http.get(PROFILE).bearer_auth(&token).send().await.map_err(|e| friendly_network(&e))?;
    let profile = response_json(response, "Couldn't load your Minecraft profile").await?;
    let described = describe(&state, &profile).await;
    remember_profile(&state, &uuid, &described);
    Ok(described)
}

fn png_size(bytes: &[u8]) -> Option<(u32, u32)> {
    if bytes.len() < 24 || !bytes.starts_with(b"\x89PNG\r\n\x1a\n") || &bytes[12..16] != b"IHDR" {
        return None;
    }
    let w = u32::from_be_bytes(bytes[16..20].try_into().ok()?);
    let h = u32::from_be_bytes(bytes[20..24].try_into().ok()?);
    Some((w, h))
}

/// Uploads a new skin with Mojang's documented skin endpoint. Only called after the user
/// previews the file and confirms.
#[tauri::command]
pub async fn skin_upload(app: AppHandle, state: State<'_, AppState>, uuid: String, png_base64: String, variant: String) -> CmdResult<Value> {
    let bytes = STANDARD.decode(png_base64.trim()).map_err(|_| "That file couldn't be read.".to_string())?;
    if bytes.len() > 64 * 1024 {
        return Err("Skin files must be smaller than 64 KB.".into());
    }
    match png_size(&bytes) {
        Some((64, 64)) | Some((64, 32)) => {}
        _ => return Err("Minecraft skins must be 64×64 (or legacy 64×32) PNG images.".into()),
    }
    let variant = if variant == "slim" { "slim" } else { "classic" };
    let token = token_for(&app, &state, &uuid).await?;
    let part = reqwest::multipart::Part::bytes(bytes).file_name("skin.png").mime_str("image/png").map_err(|e| e.to_string())?;
    let form = reqwest::multipart::Form::new().text("variant", variant.to_owned()).part("file", part);
    let response = state
        .http
        .post(format!("{PROFILE}/skins"))
        .bearer_auth(&token)
        .multipart(form)
        .send()
        .await
        .map_err(|e| friendly_network(&e))?;
    let profile = response_json(response, "Minecraft didn't accept the skin").await?;
    forget_skin(&state, &uuid);
    update_skin_model(&state, &uuid, variant)?;
    let described = describe(&state, &profile).await;
    remember_profile(&state, &uuid, &described);
    Ok(described)
}

#[tauri::command]
pub async fn skin_reset(app: AppHandle, state: State<'_, AppState>, uuid: String) -> CmdResult<Value> {
    let token = token_for(&app, &state, &uuid).await?;
    let response = state.http.delete(format!("{PROFILE}/skins/active")).bearer_auth(&token).send().await.map_err(|e| friendly_network(&e))?;
    let profile = response_json(response, "Couldn't reset the skin").await?;
    forget_skin(&state, &uuid);
    update_skin_model(&state, &uuid, "classic")?;
    let described = describe(&state, &profile).await;
    remember_profile(&state, &uuid, &described);
    Ok(described)
}

#[tauri::command]
pub async fn cape_set(app: AppHandle, state: State<'_, AppState>, uuid: String, cape_id: Option<String>) -> CmdResult<Value> {
    let token = token_for(&app, &state, &uuid).await?;
    let request = match cape_id.as_deref() {
        Some(id) if id.len() <= 64 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') => {
            state.http.put(format!("{PROFILE}/capes/active")).bearer_auth(&token).json(&json!({ "capeId": id }))
        }
        Some(_) => return Err("Invalid cape".into()),
        None => state.http.delete(format!("{PROFILE}/capes/active")).bearer_auth(&token),
    };
    let response = request.send().await.map_err(|e| friendly_network(&e))?;
    let profile = response_json(response, "Couldn't change the cape").await?;
    forget_skin(&state, &uuid);
    let described = describe(&state, &profile).await;
    remember_profile(&state, &uuid, &described);
    Ok(described)
}
