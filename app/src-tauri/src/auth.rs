use crate::core::*;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use keyring::Entry;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, State};
use tauri_plugin_opener::OpenerExt;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

pub const MICROSOFT_CLIENT_ID: &str = "e880af11-5316-40ad-a428-c15069a390f5";
const MICROSOFT_SCOPE: &str = "XboxLive.signin offline_access";
const AUTHORIZE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
const TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
const SIGN_IN_TIMEOUT: Duration = Duration::from_secs(300);

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct StoredAccount {
    pub uuid: String,
    pub username: String,
    #[serde(default)]
    pub skin_url: Option<String>,
    #[serde(default)]
    pub skin_model: Option<String>,
    #[serde(default)]
    pub added_at: u64,
    #[serde(default)]
    pub qa: bool,
}

#[derive(Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct AccountsFile {
    #[serde(default)]
    pub active: Option<String>,
    #[serde(default)]
    pub accounts: Vec<StoredAccount>,
}

struct MinecraftSession {
    account: StoredAccount,
    access_token: String,
    expires_in: u64,
}

fn emit_stage(app: &AppHandle, stage: &str) {
    let _ = app.emit("auth-status", json!({ "stage": stage }));
}

/// Where Microsoft refresh tokens are kept. The real store is the OS credential vault (the macOS
/// Keychain); tests use an in-memory store so they never touch the user's keychain.
pub trait CredentialStore: Send + Sync {
    fn read(&self, service: &str, account: &str) -> Result<Option<String>, String>;
    fn write(&self, service: &str, account: &str, secret: &str) -> Result<(), String>;
    fn delete(&self, service: &str, account: &str) -> Result<(), String>;
}

pub struct SystemKeychain;

impl CredentialStore for SystemKeychain {
    fn read(&self, service: &str, account: &str) -> Result<Option<String>, String> {
        match Entry::new(service, account).map_err(|e| e.to_string())?.get_password() {
            Ok(secret) => Ok(Some(secret)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(error) => Err(error.to_string()),
        }
    }
    fn write(&self, service: &str, account: &str, secret: &str) -> Result<(), String> {
        Entry::new(service, account).and_then(|e| e.set_password(secret)).map_err(|e| e.to_string())
    }
    fn delete(&self, service: &str, account: &str) -> Result<(), String> {
        match Entry::new(service, account).and_then(|e| e.delete_credential()) {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(error) => Err(error.to_string()),
        }
    }
}

fn credential_account(uuid: &str) -> String {
    format!("msa-refresh:{uuid}")
}

const KEYCHAIN_BLOCKED: &str = "macOS didn't let Flint read your saved Microsoft sign-in. Press Play, or use Settings → Accounts → Refresh, and choose Allow when macOS asks.";

fn store_refresh_token(state: &AppState, uuid: &str, token: &str) -> CmdResult<()> {
    // Memory first: if the keychain write fails, this run still uses the newest token.
    state.refresh_tokens.lock().unwrap().insert(uuid.to_owned(), token.to_owned());
    state
        .credentials
        .write(&state.keyring_service, &credential_account(uuid), token)
        .map_err(|e| format!("Could not save the sign-in to the system credential store: {e}"))?;
    state.keychain_blocked.lock().unwrap().remove(uuid);
    Ok(())
}

/// True when a Minecraft token can be had without touching the keychain: a live access token, or a
/// refresh token already read this run. Pages that open by themselves (Skins) use this so that
/// merely opening them never causes a macOS keychain prompt.
pub fn session_ready(state: &AppState, uuid: &str) -> bool {
    cached_token(state, uuid).is_some() || state.refresh_tokens.lock().unwrap().contains_key(uuid)
}

/// Reads an account's refresh token. After the first read in a session it is served from memory,
/// so the keychain (and any macOS permission prompt) is involved at most once per run. If macOS
/// refuses access, automatic requests (such as opening the Skins page) don't ask again; only an
/// explicit action (`explicit`, e.g. Play or Refresh) retries.
fn load_refresh_token(state: &AppState, uuid: &str, explicit: bool) -> CmdResult<Option<String>> {
    if let Some(token) = state.refresh_tokens.lock().unwrap().get(uuid) {
        return Ok(Some(token.clone()));
    }
    if !explicit && state.keychain_blocked.lock().unwrap().contains(uuid) {
        return Err(KEYCHAIN_BLOCKED.into());
    }
    match state.credentials.read(&state.keyring_service, &credential_account(uuid)) {
        Ok(Some(token)) => {
            state.keychain_blocked.lock().unwrap().remove(uuid);
            state.refresh_tokens.lock().unwrap().insert(uuid.to_owned(), token.clone());
            Ok(Some(token))
        }
        Ok(None) => Ok(None),
        Err(_) => {
            state.keychain_blocked.lock().unwrap().insert(uuid.to_owned());
            Err(KEYCHAIN_BLOCKED.into())
        }
    }
}

pub fn load_accounts(state: &AppState) -> AccountsFile {
    let mut file: AccountsFile = read_json(&state.paths.accounts());
    if !state.qa {
        file.accounts.retain(|a| !a.qa);
        if file.active.as_ref().is_some_and(|id| !file.accounts.iter().any(|a| &a.uuid == id)) {
            file.active = file.accounts.first().map(|a| a.uuid.clone());
        }
    }
    file
}

fn save_accounts(state: &AppState, file: &AccountsFile) -> CmdResult<()> {
    write_json(&state.paths.accounts(), file)
}

fn pkce_pair() -> (String, String) {
    use rand::RngCore;
    let mut bytes = [0u8; 64];
    rand::thread_rng().fill_bytes(&mut bytes);
    let verifier = URL_SAFE_NO_PAD.encode(bytes);
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    (verifier, challenge)
}

const CALLBACK_PAGE: &str = r##"<!doctype html><html><head><meta charset="utf-8"><title>Flint Client</title>
<style>body{margin:0;height:100vh;display:grid;place-items:center;background:#0c0b0a;color:#fef3e2;font:16px/1.5 -apple-system,Segoe UI,sans-serif}
main{max-width:420px;text-align:center;padding:32px}h1{font-size:20px;font-weight:600;margin:16px 0 8px}p{margin:0;color:rgba(254,243,226,.64)}</style></head>
<body><main><svg width="40" height="40" viewBox="150 50 212 370"><polygon points="256,60 351,228 256,312 161,228" fill="#B45309"/><polygon points="256,60 351,228 256,186" fill="#D97706"/><polygon points="239,172 318,284 239,410 194,284" fill="#FEF3E2"/></svg>
<h1>{{TITLE}}</h1><p>{{BODY}}</p></main></body></html>"##;

async fn respond(stream: &mut TcpStream, status: &str, title: &str, body: &str) {
    let html = CALLBACK_PAGE
        .replace("{{TITLE}}", title)
        .replace("{{BODY}}", body);
    let response = format!(
        "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n{html}",
        html.len()
    );
    let _ = stream.write_all(response.as_bytes()).await;
    let _ = stream.shutdown().await;
}

enum Callback {
    Code(String),
    Denied(String),
}

async fn handle_connection(mut stream: TcpStream, expected_state: &str) -> Option<Callback> {
    let mut buf = vec![0u8; 8192];
    let mut read = 0usize;
    loop {
        let n = tokio::time::timeout(Duration::from_secs(5), stream.read(&mut buf[read..]))
            .await
            .ok()?
            .ok()?;
        if n == 0 {
            break;
        }
        read += n;
        if buf[..read].windows(4).any(|w| w == b"\r\n\r\n") || read == buf.len() {
            break;
        }
    }
    let request = String::from_utf8_lossy(&buf[..read]);
    let target = request.lines().next()?.split_whitespace().nth(1)?.to_owned();
    let url = url::Url::parse(&format!("http://localhost{target}")).ok()?;
    if url.path() != "/" {
        respond(&mut stream, "404 Not Found", "Not found", "").await;
        return None;
    }
    let mut code = None;
    let mut state = None;
    let mut error = None;
    let mut description = None;
    for (key, value) in url.query_pairs() {
        match key.as_ref() {
            "code" => code = Some(value.into_owned()),
            "state" => state = Some(value.into_owned()),
            "error" => error = Some(value.into_owned()),
            "error_description" => description = Some(value.into_owned()),
            _ => {}
        }
    }
    if state.as_deref() != Some(expected_state) {
        respond(
            &mut stream,
            "400 Bad Request",
            "This sign-in link is no longer valid",
            "Return to Flint and start the sign-in again.",
        )
        .await;
        return None;
    }
    if let Some(error) = error {
        respond(
            &mut stream,
            "200 OK",
            "Sign-in was not completed",
            "You can close this tab and return to Flint.",
        )
        .await;
        let message = if error == "access_denied" {
            "Microsoft sign-in was cancelled.".to_owned()
        } else {
            description
                .map(|d| d.lines().next().unwrap_or_default().to_owned())
                .unwrap_or(error)
        };
        return Some(Callback::Denied(message));
    }
    let code = code?;
    respond(
        &mut stream,
        "200 OK",
        "You're signed in",
        "You can close this tab and return to Flint.",
    )
    .await;
    Some(Callback::Code(code))
}

async fn wait_for_callback(
    v4: TcpListener,
    v6: Option<TcpListener>,
    expected_state: String,
    mut cancel: tokio::sync::oneshot::Receiver<()>,
) -> CmdResult<String> {
    let deadline = tokio::time::sleep(SIGN_IN_TIMEOUT);
    tokio::pin!(deadline);
    loop {
        let accepted = tokio::select! {
            _ = &mut deadline => return Err("Sign-in timed out. Please try again.".into()),
            _ = &mut cancel => return Err("Sign-in was cancelled.".into()),
            result = v4.accept() => result,
            result = async {
                match &v6 {
                    Some(listener) => listener.accept().await,
                    None => std::future::pending().await,
                }
            } => result,
        };
        let Ok((stream, _)) = accepted else { continue };
        match handle_connection(stream, &expected_state).await {
            Some(Callback::Code(code)) => return Ok(code),
            Some(Callback::Denied(message)) => return Err(message),
            None => continue,
        }
    }
}

async fn token_request(state: &AppState, form: &[(&str, &str)]) -> CmdResult<Value> {
    let response = state
        .http
        .post(TOKEN_URL)
        .form(form)
        .send()
        .await
        .map_err(|e| format!("Microsoft sign-in failed: {}", friendly_network(&e)))?;
    response_json(response, "Microsoft sign-in failed").await
}

fn xsts_message(value: &Value) -> Option<&'static str> {
    match value["XErr"].as_u64()? {
        2148916233 => Some("This Microsoft account has no Xbox profile yet. Sign in once at xbox.com, then try again."),
        2148916235 => Some("Xbox Live isn't available in this account's country or region."),
        2148916236 | 2148916237 => Some("This account needs adult verification on xbox.com before it can play."),
        2148916238 => Some("This is a child account. An adult must add it to a Microsoft family before it can sign in."),
        _ => None,
    }
}

async fn minecraft_session(
    app: &AppHandle,
    state: &AppState,
    microsoft_access_token: &str,
) -> CmdResult<MinecraftSession> {
    emit_stage(app, "xbox");
    let xbox = response_json(
        state
            .http
            .post("https://user.auth.xboxlive.com/user/authenticate")
            .header("Accept", "application/json")
            .json(&json!({
                "Properties": {
                    "AuthMethod": "RPS",
                    "SiteName": "user.auth.xboxlive.com",
                    "RpsTicket": format!("d={microsoft_access_token}")
                },
                "RelyingParty": "http://auth.xboxlive.com",
                "TokenType": "JWT"
            }))
            .send()
            .await
            .map_err(|e| format!("Xbox Live sign-in failed: {}", friendly_network(&e)))?,
        "Xbox Live sign-in failed",
    )
    .await?;
    let xbox_token = xbox["Token"].as_str().ok_or("Xbox Live returned no token")?;
    let user_hash = xbox["DisplayClaims"]["xui"][0]["uhs"]
        .as_str()
        .ok_or("Xbox Live returned no user hash")?;

    let xsts_response = state
        .http
        .post("https://xsts.auth.xboxlive.com/xsts/authorize")
        .header("Accept", "application/json")
        .json(&json!({
            "Properties": { "SandboxId": "RETAIL", "UserTokens": [xbox_token] },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT"
        }))
        .send()
        .await
        .map_err(|e| format!("Xbox authorization failed: {}", friendly_network(&e)))?;
    if xsts_response.status().as_u16() == 401 {
        let body: Value = xsts_response.json().await.unwrap_or_default();
        return Err(xsts_message(&body)
            .unwrap_or("Xbox authorization was refused for this account.")
            .into());
    }
    let xsts = response_json(xsts_response, "Xbox authorization failed").await?;
    let xsts_token = xsts["Token"].as_str().ok_or("Xbox authorization returned no token")?;

    emit_stage(app, "minecraft");
    let login_response = state
        .http
        .post("https://api.minecraftservices.com/authentication/login_with_xbox")
        .json(&json!({ "identityToken": format!("XBL3.0 x={user_hash};{xsts_token}") }))
        .send()
        .await
        .map_err(|e| format!("Minecraft sign-in failed: {}", friendly_network(&e)))?;
    if login_response.status().as_u16() == 403 {
        let body = login_response.text().await.unwrap_or_default();
        if body.contains("Invalid app registration") || body.contains("AppRegInfo") {
            return Err("Minecraft services haven't approved Flint's Microsoft app registration yet (Mojang review pending).".into());
        }
        return Err("Minecraft services refused the sign-in.".into());
    }
    let login = response_json(login_response, "Minecraft sign-in failed").await?;
    let access_token = login["access_token"]
        .as_str()
        .ok_or("Minecraft services returned no access token")?
        .to_owned();
    let expires_in = login["expires_in"].as_u64().unwrap_or(86_400);

    emit_stage(app, "ownership");
    let entitlements = response_json(
        state
            .http
            .get("https://api.minecraftservices.com/entitlements/mcstore")
            .bearer_auth(&access_token)
            .send()
            .await
            .map_err(|e| format!("Could not verify Minecraft ownership: {}", friendly_network(&e)))?,
        "Could not verify Minecraft ownership",
    )
    .await?;
    let owns_java = entitlements["items"].as_array().is_some_and(|items| {
        items.iter().any(|item| {
            matches!(item["name"].as_str(), Some("game_minecraft" | "product_minecraft"))
        })
    });
    emit_stage(app, "profile");
    let profile_response = state
        .http
        .get("https://api.minecraftservices.com/minecraft/profile")
        .bearer_auth(&access_token)
        .send()
        .await
        .map_err(|e| format!("Could not load the Minecraft profile: {}", friendly_network(&e)))?;
    if profile_response.status().as_u16() == 404 {
        if !owns_java {
            return Err("This Microsoft account doesn't own Minecraft: Java Edition.".into());
        }
        return Err("This account owns Minecraft but hasn't created a Java profile yet. Open the official launcher once to choose a name.".into());
    }
    let profile = response_json(profile_response, "Could not load the Minecraft profile").await?;
    let active_skin = profile["skins"]
        .as_array()
        .and_then(|skins| {
            skins
                .iter()
                .find(|s| s["state"].as_str() == Some("ACTIVE"))
                .or_else(|| skins.first())
        })
        .cloned()
        .unwrap_or(Value::Null);

    Ok(MinecraftSession {
        account: StoredAccount {
            uuid: profile["id"].as_str().ok_or("Profile has no UUID")?.to_owned(),
            username: profile["name"].as_str().ok_or("Profile has no name")?.to_owned(),
            skin_url: active_skin["url"].as_str().map(|u| u.replace("http://", "https://")),
            skin_model: active_skin["variant"]
                .as_str()
                .map(|v| if v.eq_ignore_ascii_case("slim") { "slim" } else { "classic" }.to_owned()),
            added_at: now_secs(),
            qa: false,
        },
        access_token,
        expires_in,
    })
}

fn remember(state: &AppState, session: &MinecraftSession) -> CmdResult<StoredAccount> {
    state.minecraft_tokens.lock().unwrap().insert(
        session.account.uuid.clone(),
        CachedToken {
            token: session.access_token.clone(),
            expires: Instant::now() + Duration::from_secs(session.expires_in.saturating_sub(300)),
        },
    );
    let mut file = load_accounts(state);
    let mut account = session.account.clone();
    let mut changed = file.active.as_deref() != Some(account.uuid.as_str());
    if let Some(existing) = file.accounts.iter_mut().find(|a| a.uuid == account.uuid) {
        account.added_at = existing.added_at;
        changed |= serde_json::to_value(&*existing).ok() != serde_json::to_value(&account).ok();
        *existing = account.clone();
    } else {
        file.accounts.push(account.clone());
        changed = true;
    }
    file.active = Some(account.uuid.clone());
    // A routine token refresh usually changes nothing on disk; skip the rewrite then.
    if changed {
        save_accounts(state, &file)?;
    }
    Ok(account)
}

#[tauri::command]
pub async fn auth_sign_in(app: AppHandle, state: State<'_, AppState>) -> CmdResult<StoredAccount> {
    let v4 = TcpListener::bind("127.0.0.1:0")
        .await
        .map_err(err("Could not start the sign-in listener"))?;
    let port = v4.local_addr().map_err(|e| e.to_string())?.port();
    let v6 = TcpListener::bind(("::1", port)).await.ok();
    let redirect_uri = format!("http://localhost:{port}");
    let (verifier, challenge) = pkce_pair();
    let expected_state = random_hex(16);

    let mut authorize = url::Url::parse(AUTHORIZE_URL).map_err(|e| e.to_string())?;
    authorize
        .query_pairs_mut()
        .append_pair("client_id", MICROSOFT_CLIENT_ID)
        .append_pair("response_type", "code")
        .append_pair("redirect_uri", &redirect_uri)
        .append_pair("response_mode", "query")
        .append_pair("scope", MICROSOFT_SCOPE)
        .append_pair("code_challenge", &challenge)
        .append_pair("code_challenge_method", "S256")
        .append_pair("state", &expected_state)
        .append_pair("prompt", "select_account");

    let (cancel_tx, cancel_rx) = tokio::sync::oneshot::channel();
    if let Some(previous) = state.sign_in_cancel.lock().unwrap().replace(cancel_tx) {
        let _ = previous.send(());
    }

    emit_stage(&app, "browser");
    app.opener()
        .open_url(authorize.as_str(), None::<&str>)
        .map_err(err("Could not open your browser"))?;

    let code = wait_for_callback(v4, v6, expected_state, cancel_rx).await;
    state.sign_in_cancel.lock().unwrap().take();
    let code = code?;

    emit_stage(&app, "exchange");
    let token = token_request(
        &state,
        &[
            ("client_id", MICROSOFT_CLIENT_ID),
            ("grant_type", "authorization_code"),
            ("code", &code),
            ("redirect_uri", &redirect_uri),
            ("code_verifier", &verifier),
            ("scope", MICROSOFT_SCOPE),
        ],
    )
    .await?;
    let microsoft_access = token["access_token"]
        .as_str()
        .ok_or("Microsoft returned no access token")?;
    let session = minecraft_session(&app, &state, microsoft_access).await?;
    if let Some(refresh) = token["refresh_token"].as_str() {
        store_refresh_token(&state, &session.account.uuid, refresh)?;
    }
    let account = remember(&state, &session)?;
    emit_stage(&app, "done");
    Ok(account)
}

#[tauri::command]
pub fn auth_cancel(state: State<'_, AppState>) {
    if let Some(cancel) = state.sign_in_cancel.lock().unwrap().take() {
        let _ = cancel.send(());
    }
}

async fn refresh_session(
    app: &AppHandle,
    state: &AppState,
    uuid: &str,
    explicit: bool,
) -> CmdResult<MinecraftSession> {
    let token = renew_refresh_token(state, uuid, explicit, |refresh| async move {
        let response = state
            .http
            .post(TOKEN_URL)
            .form(&[
                ("client_id", MICROSOFT_CLIENT_ID),
                ("grant_type", "refresh_token"),
                ("refresh_token", refresh.as_str()),
                ("scope", MICROSOFT_SCOPE),
            ])
            .send()
            .await
            .map_err(|e| format!("Couldn't reach Microsoft to renew your sign-in: {}", friendly_network(&e)))?;
        let status = response.status().as_u16();
        response_json(response, "Microsoft sign-in failed")
            .await
            .map_err(|_| refresh_failure_message(status))
    })
    .await?;
    let access = token["access_token"]
        .as_str()
        .ok_or("Microsoft returned no access token")?;
    let session = minecraft_session(app, state, access).await?;
    if session.account.uuid != uuid {
        return Err("The saved sign-in belongs to a different profile.".into());
    }
    Ok(session)
}

/// The keychain side of renewing a sign-in: the refresh token comes from memory after the first
/// read this run, `exchange` trades it with Microsoft, and the keychain is written only when
/// Microsoft rotated it. A failed exchange keeps the token in memory, so a retry never reads again.
async fn renew_refresh_token<Fut>(state: &AppState, uuid: &str, explicit: bool, exchange: impl FnOnce(String) -> Fut) -> CmdResult<Value>
where
    Fut: std::future::Future<Output = CmdResult<Value>>,
{
    let refresh = load_refresh_token(state, uuid, explicit)?
        .ok_or("This account needs to sign in again.")?;
    let token = exchange(refresh.clone()).await?;
    // A failed save doesn't fail the refresh: the new token is kept in memory for this run, and the
    // saved one stays usable for the next run (or the next rotation saves again). Failing here
    // instead would discard the new token and ask macOS again on every retry.
    if let Some(next) = token["refresh_token"].as_str().filter(|next| *next != refresh) {
        if let Err(error) = store_refresh_token(state, uuid, next) {
            eprintln!("[flint] keeping the renewed sign-in in memory only: {error}");
        }
    }
    Ok(token)
}

/// What to tell the player when Microsoft rejects or fails a refresh. Only a rejected grant
/// (HTTP 400/401) means the saved sign-in is no longer valid.
fn refresh_failure_message(status: u16) -> String {
    match status {
        400 | 401 => "This account's sign-in expired. Please sign in again.".into(),
        429 => "Microsoft is limiting sign-in requests right now. Try again in a minute.".into(),
        _ => format!("Microsoft's sign-in service returned an error (HTTP {status}). Try again shortly."),
    }
}

fn cached_token(state: &AppState, uuid: &str) -> Option<String> {
    state
        .minecraft_tokens
        .lock()
        .unwrap()
        .get(uuid)
        .filter(|cached| cached.expires > Instant::now())
        .map(|cached| cached.token.clone())
}

/// Returns a valid Minecraft access token, refreshing it if needed. Concurrent callers for the
/// same account share one refresh, so the keychain is read (and written) once rather than once
/// per caller. Background uses (skins, suggestions) never re-ask after macOS refused access.
pub async fn minecraft_token(app: &AppHandle, state: &AppState, uuid: &str) -> CmdResult<String> {
    token_for_account(app, state, uuid, false).await
}

/// Like `minecraft_token`, for actions the player started on purpose (such as Play): may ask macOS
/// for keychain access again after an earlier refusal.
pub async fn minecraft_token_explicit(app: &AppHandle, state: &AppState, uuid: &str) -> CmdResult<String> {
    token_for_account(app, state, uuid, true).await
}

async fn token_for_account(app: &AppHandle, state: &AppState, uuid: &str, explicit: bool) -> CmdResult<String> {
    state
        .token_refresh
        .run(uuid, || cached_token(state, uuid), || async {
            let session = refresh_session(app, state, uuid, explicit).await?;
            remember(state, &session)?;
            Ok(session.access_token)
        })
        .await
}

pub fn update_skin_model(state: &AppState, uuid: &str, model: &str) -> CmdResult<()> {
    let mut file = load_accounts(state);
    if let Some(account) = file.accounts.iter_mut().find(|a| a.uuid == uuid) {
        account.skin_model = Some(model.to_owned());
    }
    save_accounts(state, &file)
}

pub fn active_account(state: &AppState) -> Option<StoredAccount> {
    let file = load_accounts(state);
    let active = file.active.clone()?;
    file.accounts.into_iter().find(|a| a.uuid == active)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountsView {
    active: Option<String>,
    accounts: Vec<StoredAccount>,
}

#[tauri::command]
pub fn accounts_list(state: State<'_, AppState>) -> AccountsView {
    let file = load_accounts(&state);
    AccountsView {
        active: file.active,
        accounts: file.accounts,
    }
}

#[tauri::command]
pub fn accounts_set_active(state: State<'_, AppState>, uuid: String) -> CmdResult<()> {
    let mut file = load_accounts(&state);
    if !file.accounts.iter().any(|a| a.uuid == uuid) {
        return Err("That account isn't signed in.".into());
    }
    file.active = Some(uuid);
    save_accounts(&state, &file)
}

#[tauri::command]
pub async fn accounts_refresh(
    app: AppHandle,
    state: State<'_, AppState>,
    uuid: String,
) -> CmdResult<StoredAccount> {
    let file = load_accounts(&state);
    let existing = file
        .accounts
        .iter()
        .find(|a| a.uuid == uuid)
        .cloned()
        .ok_or("That account isn't signed in.")?;
    if existing.qa {
        return Ok(existing);
    }
    // An explicit refresh always renews, but still queues behind any refresh already in flight.
    let account = state
        .token_refresh
        .run(&uuid, || None, || async {
            let session = refresh_session(&app, &state, &uuid, true).await?;
            remember(&state, &session)
        })
        .await?;
    let active = file.active.clone();
    if active.is_some() && active.as_deref() != Some(uuid.as_str()) {
        let mut file = load_accounts(&state);
        file.active = active;
        save_accounts(&state, &file)?;
    }
    Ok(account)
}

#[tauri::command]
pub fn accounts_remove(state: State<'_, AppState>, uuid: String) -> CmdResult<()> {
    let mut file = load_accounts(&state);
    let Some(account) = file.accounts.iter().find(|a| a.uuid == uuid).cloned() else {
        return Ok(());
    };
    if !account.qa {
        state
            .credentials
            .delete(&state.keyring_service, &credential_account(&uuid))
            .map_err(|e| format!("Could not remove the saved sign-in: {e}"))?;
    }
    state.minecraft_tokens.lock().unwrap().remove(&uuid);
    state.refresh_tokens.lock().unwrap().remove(&uuid);
    state.keychain_blocked.lock().unwrap().remove(&uuid);
    let _ = std::fs::remove_file(state.paths.cache().join("skins").join(format!("{uuid}-profile.json")));
    file.accounts.retain(|a| a.uuid != uuid);
    if file.active.as_deref() == Some(uuid.as_str()) {
        file.active = file.accounts.first().map(|a| a.uuid.clone());
    }
    save_accounts(&state, &file)
}

/// QA builds only: adds a local test identity so the onboarding flow can be exercised end to end
/// without a Microsoft account. It holds no token and can only launch the game in demo mode.
#[tauri::command]
pub fn auth_qa_account(state: State<'_, AppState>) -> CmdResult<StoredAccount> {
    if !state.qa {
        return Err("Not available.".into());
    }
    let account = StoredAccount {
        uuid: "00000000000040008000000000f11a7e".into(),
        username: "FlintQA".into(),
        skin_url: None,
        skin_model: Some("classic".into()),
        added_at: now_secs(),
        qa: true,
    };
    let mut file = load_accounts(&state);
    file.accounts.retain(|a| a.uuid != account.uuid);
    file.accounts.push(account.clone());
    file.active = Some(account.uuid.clone());
    save_accounts(&state, &file)?;
    Ok(account)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_rejected_grant_reports_an_expired_sign_in() {
        assert!(refresh_failure_message(400).contains("expired"));
        assert!(refresh_failure_message(401).contains("expired"));
        assert!(!refresh_failure_message(503).contains("expired"));
        assert!(refresh_failure_message(503).contains("503"));
        assert!(refresh_failure_message(429).contains("limiting"));
    }

    #[test]
    fn cached_tokens_are_used_until_they_expire() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        state.minecraft_tokens.lock().unwrap().insert("live".into(), CachedToken { token: "t1".into(), expires: Instant::now() + Duration::from_secs(60) });
        state.minecraft_tokens.lock().unwrap().insert("old".into(), CachedToken { token: "t0".into(), expires: Instant::now() - Duration::from_secs(1) });
        assert_eq!(cached_token(&state, "live").as_deref(), Some("t1"));
        assert_eq!(cached_token(&state, "old"), None);
        assert_eq!(cached_token(&state, "missing"), None);
    }

    fn state_with_store() -> (TestDir, AppState, std::sync::Arc<MemoryCredentials>) {
        let dir = TestDir::new();
        let mut state = AppState::for_tests(dir.0.clone());
        let store = std::sync::Arc::new(MemoryCredentials::default());
        state.credentials = Box::new(store.clone());
        (dir, state, store)
    }

    fn reads(store: &MemoryCredentials) -> usize {
        store.reads.load(std::sync::atomic::Ordering::SeqCst)
    }

    #[test]
    fn the_keychain_is_read_once_per_session() {
        let (_dir, state, store) = state_with_store();
        store.secrets.lock().unwrap().insert(credential_account("u1"), "refresh-1".into());
        assert_eq!(load_refresh_token(&state, "u1", false).unwrap().as_deref(), Some("refresh-1"));
        assert_eq!(load_refresh_token(&state, "u1", false).unwrap().as_deref(), Some("refresh-1"));
        assert_eq!(load_refresh_token(&state, "u1", true).unwrap().as_deref(), Some("refresh-1"));
        assert_eq!(reads(&store), 1, "later requests in the same run are served from memory");
    }

    #[test]
    fn a_refused_keychain_is_not_asked_again_automatically() {
        let (_dir, state, store) = state_with_store();
        store.secrets.lock().unwrap().insert(credential_account("u1"), "refresh-1".into());
        store.refuse.store(true, std::sync::atomic::Ordering::SeqCst);
        assert_eq!(load_refresh_token(&state, "u1", false).unwrap_err(), KEYCHAIN_BLOCKED);
        // Opening the Skins page again (automatic) must not trigger another macOS prompt.
        assert_eq!(load_refresh_token(&state, "u1", false).unwrap_err(), KEYCHAIN_BLOCKED);
        assert_eq!(reads(&store), 1);
        // Pressing Play or Refresh (explicit) asks again, and succeeds once access is allowed.
        store.refuse.store(false, std::sync::atomic::Ordering::SeqCst);
        assert_eq!(load_refresh_token(&state, "u1", true).unwrap().as_deref(), Some("refresh-1"));
        assert_eq!(reads(&store), 2);
        assert_eq!(load_refresh_token(&state, "u1", false).unwrap().as_deref(), Some("refresh-1"));
        assert_eq!(reads(&store), 2);
    }

    #[test]
    fn a_missing_sign_in_is_not_treated_as_refused() {
        let (_dir, state, store) = state_with_store();
        assert_eq!(load_refresh_token(&state, "nobody", false).unwrap(), None);
        assert!(!state.keychain_blocked.lock().unwrap().contains("nobody"));
        assert_eq!(reads(&store), 1);
    }

    #[test]
    fn written_tokens_are_reused_without_reading_back() {
        let (_dir, state, store) = state_with_store();
        store_refresh_token(&state, "u1", "refresh-2").unwrap();
        assert_eq!(load_refresh_token(&state, "u1", false).unwrap().as_deref(), Some("refresh-2"));
        assert_eq!(reads(&store), 0);
        assert_eq!(store.writes.load(std::sync::atomic::Ordering::SeqCst), 1);
    }

    #[test]
    fn a_failed_save_keeps_the_renewed_token_for_this_run() {
        let (_dir, state, store) = state_with_store();
        store.secrets.lock().unwrap().insert(credential_account("u1"), "refresh-1".into());
        store.refuse_writes.store(true, std::sync::atomic::Ordering::SeqCst);
        assert!(store_refresh_token(&state, "u1", "refresh-2").is_err());
        // The rotated token is used for the rest of the run, without asking macOS to read again.
        assert_eq!(load_refresh_token(&state, "u1", false).unwrap().as_deref(), Some("refresh-2"));
        assert_eq!(reads(&store), 0);
    }

    fn writes(store: &MemoryCredentials) -> usize {
        store.writes.load(std::sync::atomic::Ordering::SeqCst)
    }

    /// Stands in for Microsoft's token endpoint: counts calls and answers with `next` as the
    /// (possibly rotated) refresh token, or fails like an offline network.
    async fn fake_exchange(calls: &std::sync::atomic::AtomicUsize, next: Option<&str>) -> CmdResult<Value> {
        calls.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        tokio::time::sleep(Duration::from_millis(30)).await;
        next.map(|next| json!({ "access_token": "access", "refresh_token": next })).ok_or_else(|| "offline".to_string())
    }

    /// The same shape as `token_for_account` (single flight, cached access token, renew, remember),
    /// with the network replaced.
    async fn token(state: &AppState, explicit: bool, calls: &std::sync::atomic::AtomicUsize, next: Option<&str>) -> CmdResult<String> {
        state
            .token_refresh
            .run("u1", || cached_token(state, "u1"), || async {
                renew_refresh_token(state, "u1", explicit, |_| fake_exchange(calls, next)).await?;
                state.minecraft_tokens.lock().unwrap().insert("u1".into(), CachedToken { token: "access".into(), expires: Instant::now() + Duration::from_secs(600) });
                Ok("access".to_string())
            })
            .await
    }

    #[tokio::test]
    async fn simultaneous_first_requests_read_the_keychain_once() {
        let (_dir, state, store) = state_with_store();
        store.secrets.lock().unwrap().insert(credential_account("u1"), "refresh-1".into());
        let calls = std::sync::atomic::AtomicUsize::new(0);
        // Flint AI's quota, the Skins page and Play all asking at once in a fresh run.
        let (ai, skins, play) = tokio::join!(token(&state, false, &calls, Some("refresh-2")), token(&state, false, &calls, Some("refresh-2")), token(&state, true, &calls, Some("refresh-2")));
        assert_eq!((ai.unwrap(), skins.unwrap(), play.unwrap()), ("access".into(), "access".into(), "access".into()));
        assert_eq!(reads(&store), 1, "one keychain read for all three");
        assert_eq!(writes(&store), 1, "the rotated token is saved once");
        assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst), 1, "one Microsoft refresh");
        // Later requests in the run use the live access token: no keychain, no network.
        token(&state, true, &calls, Some("refresh-3")).await.unwrap();
        assert_eq!((reads(&store), writes(&store), calls.load(std::sync::atomic::Ordering::SeqCst)), (1, 1, 1));
    }

    #[tokio::test]
    async fn a_failed_refresh_is_retried_without_reading_the_keychain_again() {
        let (_dir, state, store) = state_with_store();
        store.secrets.lock().unwrap().insert(credential_account("u1"), "refresh-1".into());
        let calls = std::sync::atomic::AtomicUsize::new(0);
        assert_eq!(token(&state, false, &calls, None).await.unwrap_err(), "offline");
        assert_eq!(token(&state, true, &calls, None).await.unwrap_err(), "offline");
        assert_eq!(token(&state, true, &calls, Some("refresh-2")).await.unwrap(), "access");
        assert_eq!(reads(&store), 1, "network failures keep the token in memory");
        assert_eq!(writes(&store), 1);
        assert_eq!(store.secrets.lock().unwrap().get(&credential_account("u1")).map(String::as_str), Some("refresh-2"));
    }

    #[tokio::test]
    async fn the_keychain_is_written_only_when_microsoft_rotates_the_token() {
        let (_dir, state, store) = state_with_store();
        store.secrets.lock().unwrap().insert(credential_account("u1"), "refresh-1".into());
        let calls = std::sync::atomic::AtomicUsize::new(0);
        renew_refresh_token(&state, "u1", true, |_| fake_exchange(&calls, Some("refresh-1"))).await.unwrap();
        assert_eq!(writes(&store), 0, "an unchanged token is not rewritten");
        renew_refresh_token(&state, "u1", true, |_| fake_exchange(&calls, Some("refresh-2"))).await.unwrap();
        renew_refresh_token(&state, "u1", true, |_| fake_exchange(&calls, Some("refresh-2"))).await.unwrap();
        assert_eq!(writes(&store), 1);
        assert_eq!(reads(&store), 1);
        // A refused save after a rotation doesn't fail the refresh or cause another read.
        store.refuse_writes.store(true, std::sync::atomic::Ordering::SeqCst);
        renew_refresh_token(&state, "u1", false, |_| fake_exchange(&calls, Some("refresh-3"))).await.unwrap();
        assert_eq!(load_refresh_token(&state, "u1", false).unwrap().as_deref(), Some("refresh-3"));
        assert_eq!(reads(&store), 1);
    }

    /// A keychain whose read blocks for a while, like one waiting on a macOS permission prompt.
    struct PromptingKeychain(std::sync::Arc<MemoryCredentials>);

    impl CredentialStore for PromptingKeychain {
        fn read(&self, service: &str, account: &str) -> Result<Option<String>, String> {
            std::thread::sleep(Duration::from_millis(150));
            CredentialStore::read(&self.0, service, account)
        }
        fn write(&self, service: &str, account: &str, secret: &str) -> Result<(), String> {
            CredentialStore::write(&self.0, service, account, secret)
        }
        fn delete(&self, service: &str, account: &str) -> Result<(), String> {
            CredentialStore::delete(&self.0, service, account)
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn a_refusal_during_simultaneous_requests_asks_only_once() {
        let (_dir, mut state, store) = state_with_store();
        state.credentials = Box::new(PromptingKeychain(store.clone()));
        let state = std::sync::Arc::new(state);
        store.secrets.lock().unwrap().insert(credential_account("u1"), "refresh-1".into());
        store.refuse.store(true, std::sync::atomic::Ordering::SeqCst);
        let calls = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let ask = |explicit: bool| {
            let (state, calls) = (state.clone(), calls.clone());
            tokio::spawn(async move { token(&state, explicit, &calls, Some("refresh-2")).await })
        };
        // Flint AI asks first; the player presses Play while the macOS prompt is still showing,
        // then chooses Deny.
        let ai = ask(false);
        tokio::time::sleep(Duration::from_millis(30)).await;
        let play = ask(true);
        assert_eq!(ai.await.unwrap().unwrap_err(), KEYCHAIN_BLOCKED);
        assert_eq!(play.await.unwrap().unwrap_err(), KEYCHAIN_BLOCKED);
        assert_eq!(reads(&store), 1, "one prompt, its answer shared");
        // Automatic requests afterwards don't ask again; pressing Play again does.
        assert!(ask(false).await.unwrap().is_err());
        assert_eq!(reads(&store), 1);
        store.refuse.store(false, std::sync::atomic::Ordering::SeqCst);
        assert_eq!(ask(true).await.unwrap().unwrap(), "access");
        assert_eq!(reads(&store), 2);
    }

    #[test]
    fn checking_for_a_session_never_reads_the_keychain() {
        let (_dir, state, store) = state_with_store();
        store.secrets.lock().unwrap().insert(credential_account("u1"), "refresh-1".into());
        // Opening Skins in a fresh run: no session yet, and the keychain is left alone.
        assert!(!session_ready(&state, "u1"));
        assert_eq!(reads(&store), 0);
        // After Play (or any explicit action) read it once, pages can use the session freely.
        load_refresh_token(&state, "u1", true).unwrap();
        assert!(session_ready(&state, "u1"));
        state.minecraft_tokens.lock().unwrap().insert("u2".into(), CachedToken { token: "t".into(), expires: Instant::now() + Duration::from_secs(60) });
        assert!(session_ready(&state, "u2"));
        assert_eq!(reads(&store), 1);
    }

    #[test]
    fn an_unchanged_account_is_not_rewritten() {
        let (dir, state, _store) = state_with_store();
        let session = MinecraftSession {
            account: StoredAccount { uuid: "0123456789abcdef0123456789abcdef".into(), username: "Steve".into(), skin_model: Some("classic".into()), added_at: 1, ..Default::default() },
            access_token: "access".into(),
            expires_in: 86_400,
        };
        remember(&state, &session).unwrap();
        let path = dir.0.join("accounts.json");
        let old = std::time::SystemTime::now() - Duration::from_secs(3600);
        std::fs::File::options().write(true).open(&path).unwrap().set_modified(old).unwrap();
        remember(&state, &session).unwrap();
        assert_eq!(std::fs::metadata(&path).unwrap().modified().unwrap(), old, "no rewrite when nothing changed");
        assert_eq!(cached_token(&state, &session.account.uuid).as_deref(), Some("access"));
        let renamed = MinecraftSession { account: StoredAccount { username: "Alex".into(), ..session.account.clone() }, ..session };
        remember(&state, &renamed).unwrap();
        assert_ne!(std::fs::metadata(&path).unwrap().modified().unwrap(), old, "a real change is saved");
        assert_eq!(load_accounts(&state).accounts[0].username, "Alex");
    }

    #[test]
    fn pkce_challenge_matches_its_verifier() {
        let (verifier, challenge) = pkce_pair();
        assert!(verifier.len() >= 43 && verifier.len() <= 128);
        assert_eq!(challenge, URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes())));
        assert_ne!(pkce_pair().0, verifier);
    }
}
