//! The Flint Store (store.flintclient.com) from the launcher: signing in with proof of Minecraft ownership, the account's
//! Store inventory and Store Merit balance, equipping cosmetics, and approving a browser's Store sign-in. The contract is the
//! Store's versioned launcher API (its `docs/LAUNCHER-API.md`, v1); this is a port of its reference client
//! (`clients/launcher-sync.ts`). Nothing here invents another contract.
//!
//! - **Sign-in**: `POST /api/v1/auth/challenge/` → Flint joins the challenge's `serverId` at Mojang's session server with the
//!   account's own Minecraft access token (sent to Mojang only, as joining any server does) → `POST /api/v1/auth/verify/`.
//!   The Store asks Mojang `hasJoined` and answers a bearer session for the account Mojang names. Flint keeps it only when
//!   that account is the one it signed in, in the system credential store (keychain), one item per Store and account.
//! - **Ownership is the Store's**: the inventory Flint shows is the Store's last answer, cached per account as display data.
//!   Offline, the cached copy is shown marked stale and nothing can be changed. Nothing is granted, equipped or counted
//!   locally; equipping is a request the Store decides. A refund or chargeback disappears with the next answer.
//! - **Accounts never mix**: every command names the account; sessions, ETags, caches and textures are kept per Minecraft UUID
//!   (and per Store), an answer for another account is refused, and the launcher's other accounts are never touched.
//! - **Store Merit is not the daily-rewards Merits**: the balance here is the Store's wallet (`/me/inventory` `balance`,
//!   `/me/wallet`). The game's Daily Rewards balance is a separate system (Flint presence, Backend `merit_wallets`) that
//!   this module never reads or changes.
//! - Tokens are never logged or shown: errors carry fixed messages, and a session's `Debug` is redacted.

pub mod anim;
pub mod community;
pub mod game;
pub mod handoff;
#[cfg(test)]
mod community_tests;
#[cfg(test)]
mod fake;
#[cfg(test)]
mod tests;

use crate::auth::{load_accounts, StoredAccount};
use crate::core::*;
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use futures_util::StreamExt;
use reqwest::header::{ACCEPT, ETAG, IF_NONE_MATCH};
use reqwest::Method;
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::PathBuf;
use std::sync::atomic::AtomicU64;
use std::sync::Mutex;
use std::time::Duration;
use tauri::{AppHandle, State};
use url::Url;

/// The launcher API version this client speaks (`/api/v1/`).
pub const CONTRACT_VERSION: u32 = 1;
pub const PRODUCTION_STORE: &str = "https://store.flintclient.com";
pub const MOJANG_SESSION_SERVER: &str = "https://sessionserver.mojang.com";
/// The slots the Store knows (`player_set_equipped`). An item is worn in the slot named by its kind.
pub const SLOTS: [&str; 4] = ["cape", "back", "head", "aura"];
const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);
const LOGOUT_TIMEOUT: Duration = Duration::from_secs(5);
/// A real inventory is a few kilobytes; anything near this is not one.
const MAX_JSON: usize = 1024 * 1024;
/// A cosmetic texture is a small PNG (a 64×32 cape is under 2 KB); the Store allows up to 2 MiB (an HD cape, or an animated
/// cape's poster at 1024×512).
const MAX_ASSET: usize = 2 * 1024 * 1024;
const MAX_OWNED: usize = 2000;
/// The Store asks for 25 s; Flint accepts 10 s to 5 min.
const DEFAULT_POLL_SECONDS: u64 = 25;
const POLL_RANGE: (u64, u64) = (10, 300);

const SIGN_IN_AGAIN: &str = "Your Flint Store sign-in has ended. Sign in again to see your Store items.";
const OFFLINE: &str = "Flint can't reach the Flint Store right now. Check your connection and try again.";

// ---------- where the Store is ----------

/// The Store and the Mojang session server Flint talks to. Release builds always use the real ones; development and
/// isolated QA builds may point at another Store with `FLINT_STORE_URL` (an https origin, or http on this computer).
/// Mojang is never overridden in the app: the account's Minecraft token only ever goes to Mojang's session server.
#[derive(Clone, Debug)]
pub struct Endpoints {
    pub store: Url,
    pub mojang: Url,
    /// A Cloudflare Access service token for a non-production Store behind Access (staging), development and isolated
    /// QA builds only: sent to that Store and nowhere else. Release builds never have one.
    pub access: Option<AccessToken>,
}

/// A Cloudflare Access service token. Its secret never appears in debug output or logs.
#[derive(Clone)]
pub struct AccessToken {
    pub client_id: String,
    pub client_secret: String,
}

impl std::fmt::Debug for AccessToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AccessToken").field("client_id", &self.client_id).field("client_secret", &"<redacted>").finish()
    }
}

/// The Access token to send, if any: only in development or isolated QA builds, only when the Store was pointed away
/// from production, and only when both values are given (`FLINT_STORE_ACCESS_CLIENT_ID`, `FLINT_STORE_ACCESS_CLIENT_SECRET`).
pub fn access_token_for(development: bool, store: &Url, id: Option<String>, secret: Option<String>) -> Option<AccessToken> {
    let production = Url::parse(PRODUCTION_STORE).ok()?;
    if !development || store.origin() == production.origin() {
        return None;
    }
    let (client_id, client_secret) = (id?.trim().to_owned(), secret?.trim().to_owned());
    (!client_id.is_empty() && !client_secret.is_empty()).then_some(AccessToken { client_id, client_secret })
}

impl Endpoints {
    /// The Store this build talks to: store.flintclient.com, or a staging build's own staging Store (channel.rs).
    pub fn production() -> Self {
        let store = if crate::channel::STAGING { crate::channel::STAGING_STORE } else { PRODUCTION_STORE };
        Self { store: Url::parse(store).expect("valid"), mojang: Url::parse(MOJANG_SESSION_SERVER).expect("valid"), access: None }
    }

    pub fn from_env(development: bool) -> Self {
        let mut endpoints = Self::production();
        if development {
            if let Some(store) = std::env::var("FLINT_STORE_URL").ok().as_deref().and_then(store_origin) {
                endpoints.store = store;
            }
            endpoints.access = access_token_for(
                development,
                &endpoints.store,
                std::env::var("FLINT_STORE_ACCESS_CLIENT_ID").ok(),
                std::env::var("FLINT_STORE_ACCESS_CLIENT_SECRET").ok(),
            );
        }
        endpoints
    }

    pub(crate) fn api(&self, path: &str) -> Url {
        self.store.join(path).expect("API paths are valid")
    }

    /// The Store's origin as shown to the player and passed to the game: `https://store.flintclient.com`.
    pub fn store_base(&self) -> String {
        self.store.as_str().trim_end_matches('/').to_owned()
    }

    /// The Store's address as the player sees it: `store.flintclient.com`, `localhost:3214`.
    pub fn host(&self) -> String {
        match (self.store.host_str(), self.store.port()) {
            (Some(host), Some(port)) => format!("{host}:{port}"),
            (Some(host), None) => host.to_owned(),
            _ => self.store_base(),
        }
    }

    /// The Store's origin as a file and keychain name: `store.flintclient.com`, `localhost_3214`.
    pub fn origin_key(&self) -> String {
        let host = self.store.host_str().unwrap_or("store");
        let name = match self.store.port() {
            Some(port) => format!("{host}_{port}"),
            None => host.to_owned(),
        };
        name.chars().map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '-' { c } else { '_' }).collect()
    }
}

/// A Store origin: https anywhere, plain http only on this computer; no user, path, query or fragment.
pub fn store_origin(address: &str) -> Option<Url> {
    let url = Url::parse(address.trim()).ok()?;
    let local = matches!(url.host_str(), Some("127.0.0.1" | "localhost" | "[::1]"));
    let scheme_ok = url.scheme() == "https" || (url.scheme() == "http" && local);
    let bare = url.username().is_empty() && url.password().is_none() && url.query().is_none() && url.fragment().is_none() && url.path() == "/";
    (scheme_ok && bare && url.host_str().is_some_and(|h| !h.is_empty())).then_some(url)
}

// ---------- state ----------

/// A Store session: the bearer token, kept only in memory and in the credential store.
#[derive(Serialize, Deserialize, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub token: String,
    pub expires_at: String,
    pub name: String,
}

impl std::fmt::Debug for Session {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Session").field("token", &"<redacted>").field("expires_at", &self.expires_at).field("name", &self.name).finish()
    }
}

impl Session {
    /// The Store's expiry has passed (by this computer's clock). An unreadable expiry is left to the Store to judge.
    fn expired(&self) -> bool {
        rfc3339_secs(&self.expires_at).is_some_and(|at| at <= now_secs() as i64)
    }
}

pub struct StoreState {
    pub endpoints: Endpoints,
    /// Sessions already read from (or written to) the credential store this run, by credential name; `None` = none saved.
    sessions: Mutex<HashMap<String, Option<Session>>>,
    /// Credential names whose read the system refused this run; not asked again automatically.
    blocked: Mutex<HashSet<String>>,
    /// When the player last changed what they wear through this launcher (ms since the epoch; 0 = not this run).
    pub equip_revision: AtomicU64,
    /// The Store's partner listings (partners/mod.rs): this run's requests and pictures.
    pub partners: crate::partners::PartnersState,
    /// Discord connections and community rewards (community.rs): the public reward catalog, and each account's open
    /// Discord link attempt and last "Check again".
    pub community: community::CommunityState,
}

impl StoreState {
    pub fn new(endpoints: Endpoints) -> Self {
        Self { endpoints, sessions: Mutex::default(), blocked: Mutex::default(), equip_revision: AtomicU64::new(0), partners: Default::default(), community: Default::default() }
    }
}

/// A Minecraft UUID as Flint keeps it: 32 lowercase hex digits. None for anything else.
pub fn canonical(uuid: &str) -> Option<String> {
    let hex: String = uuid.chars().filter(|c| *c != '-').collect::<String>().to_ascii_lowercase();
    (hex.len() == 32 && hex.bytes().all(|b| b.is_ascii_hexdigit()) && uuid.len() <= 36).then_some(hex)
}

pub fn is_uuid(text: &str) -> bool {
    text.len() == 36
        && text.char_indices().all(|(i, c)| if [8, 13, 18, 23].contains(&i) { c == '-' } else { c.is_ascii_hexdigit() })
}

fn credential_name(state: &AppState, uuid: &str) -> String {
    format!("store-session:{}:{uuid}", state.store.endpoints.origin_key())
}

fn account_dir(state: &AppState, uuid: &str) -> PathBuf {
    state.paths.cache().join("store").join(state.store.endpoints.origin_key()).join(uuid)
}

fn cache_path(state: &AppState, uuid: &str) -> PathBuf {
    account_dir(state, uuid).join("inventory.json")
}

/// A Flint account by any spelling of its UUID, with the UUID in Flint's form.
fn flint_account(state: &AppState, uuid: &str) -> CmdResult<(String, StoredAccount)> {
    let id = canonical(uuid).ok_or("That isn't a Minecraft account id.")?;
    load_accounts(state)
        .accounts
        .into_iter()
        .find(|a| canonical(&a.uuid).as_deref() == Some(id.as_str()))
        .map(|a| (id, a))
        .ok_or_else(|| "That account isn't signed in to Flint any more.".into())
}

enum Lookup {
    Found(Session),
    Missing,
    /// The system refused to read the credential store (macOS can ask the player).
    Blocked,
}

/// The account's Store session: from memory after the first read this run. `interactive` (an action the player started)
/// may ask the system again after it refused; automatic reads (opening the page, polling) don't.
fn load_session(state: &AppState, uuid: &str, interactive: bool) -> Lookup {
    let name = credential_name(state, uuid);
    if let Some(slot) = state.store.sessions.lock().unwrap().get(&name) {
        return slot.clone().map_or(Lookup::Missing, Lookup::Found);
    }
    if !interactive && state.store.blocked.lock().unwrap().contains(&name) {
        return Lookup::Blocked;
    }
    match state.credentials.read(&state.keyring_service, &name) {
        Ok(found) => {
            let session = found.and_then(|raw| serde_json::from_str::<Session>(&raw).ok()).filter(|s| valid_token(&s.token));
            state.store.blocked.lock().unwrap().remove(&name);
            state.store.sessions.lock().unwrap().insert(name, session.clone());
            session.map_or(Lookup::Missing, Lookup::Found)
        }
        Err(_) => {
            state.store.blocked.lock().unwrap().insert(name);
            Lookup::Blocked
        }
    }
}

/// Keeps a new session: in memory first, so a credential store that refuses the write still leaves this run signed in.
fn save_session(state: &AppState, uuid: &str, session: &Session) {
    let name = credential_name(state, uuid);
    state.store.sessions.lock().unwrap().insert(name.clone(), Some(session.clone()));
    let secret = serde_json::to_string(session).expect("a session serializes");
    match state.credentials.write(&state.keyring_service, &name, &secret) {
        Ok(()) => {
            state.store.blocked.lock().unwrap().remove(&name);
        }
        Err(_) => eprintln!("[flint] the Flint Store sign-in is kept for this run only: the system credential store refused it"),
    }
}

/// Forgets the account's Store session on this computer (memory and credential store). Returns it, if one was known.
fn drop_session(state: &AppState, uuid: &str) -> Option<Session> {
    community::forget(state, uuid);
    let name = credential_name(state, uuid);
    let previous = state.store.sessions.lock().unwrap().insert(name.clone(), None).flatten();
    if state.credentials.delete(&state.keyring_service, &name).is_err() {
        eprintln!("[flint] couldn't remove a saved Flint Store sign-in from the system credential store");
    }
    previous
}

fn valid_token(token: &str) -> bool {
    // The Store refuses tokens over 100 characters.
    (16..=100).contains(&token.len()) && token.bytes().all(|b| b.is_ascii_graphic())
}

// ---------- HTTP ----------

#[derive(Debug, PartialEq)]
pub(crate) enum Failure {
    /// The Store couldn't be reached (or didn't answer in time).
    Offline,
    /// 401: the session is unknown, expired or revoked.
    SignedOut,
    /// Any other refusal, with the Store's error code (`not_owned`, `rate-limited`…).
    Refused { status: u16, code: String },
    /// An answer that isn't what the contract says.
    Invalid,
}

struct Answer {
    status: u16,
    etag: Option<String>,
    body: Value,
}

/// Any answer the Store gave, refusals included, as it came: the status, its ETag and `Retry-After` (seconds), and the
/// body when it was JSON (`Null` otherwise). Only an unreachable Store or an oversized answer is a [`Failure`] here.
pub(crate) struct Raw {
    pub status: u16,
    pub etag: Option<String>,
    pub retry_after: Option<u64>,
    pub body: Value,
}

pub(crate) async fn read_capped(response: reqwest::Response, limit: usize) -> Result<Vec<u8>, Failure> {
    if response.content_length().is_some_and(|n| n as usize > limit) {
        return Err(Failure::Invalid);
    }
    let mut out = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|_| Failure::Offline)?;
        if out.len() + chunk.len() > limit {
            return Err(Failure::Invalid);
        }
        out.extend_from_slice(&chunk);
    }
    Ok(out)
}

/// Adds the Access service token's headers to a request for the Store itself (never to Mojang or textures).
pub(crate) fn with_access(request: reqwest::RequestBuilder, endpoints: &Endpoints) -> reqwest::RequestBuilder {
    match &endpoints.access {
        Some(a) => request.header("CF-Access-Client-Id", &a.client_id).header("CF-Access-Client-Secret", &a.client_secret),
        None => request,
    }
}

/// One request to the Store (any method: GET, POST, PUT, DELETE), its answer as it came. See [`Raw`].
pub(crate) async fn send(state: &AppState, method: Method, path: &str, token: Option<&str>, body: Option<Value>, etag: Option<&str>) -> Result<Raw, Failure> {
    let mut request = with_access(state.http.request(method, state.store.endpoints.api(path)), &state.store.endpoints).timeout(REQUEST_TIMEOUT).header(ACCEPT, "application/json");
    if let Some(token) = token {
        request = request.bearer_auth(token);
    }
    if let Some(etag) = etag {
        request = request.header(IF_NONE_MATCH, etag);
    }
    if let Some(body) = &body {
        request = request.json(body);
    }
    let response = request.send().await.map_err(|_| Failure::Offline)?;
    let status = response.status().as_u16();
    let header = |name: reqwest::header::HeaderName| response.headers().get(name).and_then(|v| v.to_str().ok()).filter(|v| v.len() <= 200).map(str::to_owned);
    let etag = header(ETAG);
    let retry_after = header(reqwest::header::RETRY_AFTER).and_then(|v| v.trim().parse::<u64>().ok());
    if status == 304 {
        return Ok(Raw { status, etag, retry_after, body: Value::Null });
    }
    let bytes = read_capped(response, MAX_JSON).await?;
    Ok(Raw { status, etag, retry_after, body: serde_json::from_slice(&bytes).unwrap_or(Value::Null) })
}

/// The Store's error code in an answer (`not_owned`, `rate-limited`…), printable ASCII only.
fn error_code(body: &Value) -> String {
    body["error"].as_str().unwrap_or_default().chars().filter(|c| c.is_ascii_graphic()).take(64).collect()
}

async fn call(state: &AppState, method: Method, path: &str, token: Option<&str>, body: Option<Value>, etag: Option<&str>) -> Result<Answer, Failure> {
    let Raw { status, etag, body, .. } = send(state, method, path, token, body, etag).await?;
    if status == 304 {
        return Ok(Answer { status, etag, body: Value::Null });
    }
    if status == 401 && token.is_some() {
        return Err(Failure::SignedOut);
    }
    if !(200..300).contains(&status) {
        return Err(Failure::Refused { status, code: error_code(&body) });
    }
    if !body.is_object() {
        return Err(Failure::Invalid);
    }
    Ok(Answer { status, etag, body })
}

/// What to tell the player about a failed request.
fn failure_text(failure: &Failure) -> String {
    match failure {
        Failure::Offline => OFFLINE.into(),
        Failure::SignedOut => SIGN_IN_AGAIN.into(),
        Failure::Invalid => "The Flint Store sent an answer Flint couldn't read. Try again later.".into(),
        Failure::Refused { status, code } => match code.as_str() {
            "not_verified" => "Mojang didn't confirm this Minecraft account to the Flint Store. Try again, or refresh the account in Settings → Accounts.".into(),
            "mojang_unavailable" => "The Flint Store couldn't check your Minecraft account with Mojang right now. Try again later.".into(),
            "challenge_invalid" => "The sign-in took too long or was already used. Try again.".into(),
            "account_locked" => "This Minecraft account can't use the Flint Store right now. Contact Flint support if you think that's wrong.".into(),
            "rate-limited" => "Too many attempts for now. Wait a few minutes and try again.".into(),
            "not_owned" => "This account doesn't own that item (any more). The Flint Store decides what you own.".into(),
            "wrong_slot" | "invalid_slot" => "That item can't be worn there.".into(),
            "unavailable" => "The Flint Store isn't available right now. Try again in a few minutes.".into(),
            _ => format!("The Flint Store refused that (HTTP {status}). Try again later."),
        },
    }
}

// ---------- the inventory ----------

/// Numbers the database may send as text (`numeric`).
fn lenient_i64<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<i64>, D::Error> {
    Ok(match Option::<Value>::deserialize(deserializer)? {
        Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)),
        Some(Value::String(s)) => s.trim().parse::<f64>().ok().map(|f| f as i64),
        _ => None,
    })
}

/// Text the Store may add to an item later: anything that isn't text reads as none, never refusing the inventory.
fn lenient_text<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<String>, D::Error> {
    Ok(match Option::<Value>::deserialize(deserializer)? {
        Some(Value::String(s)) => Some(s),
        _ => None,
    })
}

/// A texture the Store approved for a cosmetic. `url` and `sha256` stay in Rust: the interface gets verified bytes.
#[derive(Deserialize, Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all(serialize = "camelCase"))]
pub struct Asset {
    pub id: String,
    pub version: i64,
    #[serde(skip_serializing)]
    pub url: String,
    #[serde(skip_serializing)]
    pub sha256: String,
    #[serde(default)]
    pub width: Option<u32>,
    #[serde(default)]
    pub height: Option<u32>,
    /// An animated cape's frames and timing ([`anim`]); none for a static cape, or when the manifest isn't one Flint plays
    /// (the texture above, its poster, is then shown as a static cape).
    #[serde(default, deserialize_with = "anim::lenient")]
    pub animation: Option<anim::Animation>,
}

#[derive(Deserialize, Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all(serialize = "camelCase"))]
pub struct Owned {
    pub cosmetic_id: String,
    pub slug: String,
    pub kind: String,
    pub name: String,
    #[serde(default)]
    pub source: Option<String>,
    #[serde(default)]
    pub test: bool,
    #[serde(default)]
    pub gift: bool,
    #[serde(default)]
    pub gift_from: Option<String>,
    #[serde(default)]
    pub gift_message: Option<String>,
    #[serde(default)]
    pub granted_at: Option<String>,
    #[serde(default)]
    pub asset: Option<Asset>,
    /// How the account got it (contract v1 additions, docs/connections-rewards-api-v1.md): `purchase`, `admin_gift`,
    /// `giveaway`, `event`, `first_launch`, `creator`, `test`, `discord_member`, `discord_booster`. None from a Store
    /// that doesn't send it. Never the catalog's `origin` (who made the cape).
    #[serde(default, deserialize_with = "lenient_text")]
    pub obtained_via: Option<String>,
    /// `permanent` or `conditional` (counts only while its condition holds, a Discord reward). Read as conditional
    /// whenever the Store marks the item as a reward, so a conditional reward is never shown as owned for good.
    #[serde(default, deserialize_with = "lenient_text")]
    pub permanence: Option<String>,
    /// `owned`, `eligible` (conditional, earned now) or `unavailable` (conditional, Discord can't be reached; still
    /// usable during the grace period).
    #[serde(default, deserialize_with = "lenient_text")]
    pub state: Option<String>,
}

/// `GET /api/v1/me/inventory/`, checked. Shown to the interface in camelCase.
#[derive(Deserialize, Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all(serialize = "camelCase"))]
pub struct Inventory {
    pub version: i64,
    #[serde(skip_serializing)]
    pub minecraft_uuid: String,
    pub name: String,
    /// Store Merit (the Store's wallet), never the game's daily-rewards Merits.
    #[serde(default, deserialize_with = "lenient_i64")]
    pub balance: Option<i64>,
    #[serde(default)]
    pub owned: Vec<Owned>,
    #[serde(default)]
    pub equipped: BTreeMap<String, String>,
}

fn clip(text: &str, max: usize) -> String {
    text.chars().filter(|c| !c.is_control()).take(max).collect()
}

fn clip_opt(text: &Option<String>, max: usize) -> Option<String> {
    text.as_deref().map(|t| clip(t, max)).filter(|t| !t.is_empty())
}

/// The Store's inventory, with every field checked: an answer that isn't an inventory is refused, unusable entries are
/// left out, and text is trimmed to what the interface can show.
fn parse_inventory(body: &Value) -> Option<Inventory> {
    let mut inventory: Inventory = serde_json::from_value(body.clone()).ok()?;
    if inventory.owned.len() > MAX_OWNED || canonical(&inventory.minecraft_uuid).is_none() {
        return None;
    }
    inventory.name = clip(&inventory.name, 32);
    inventory.owned.retain(|o| is_uuid(&o.cosmetic_id) && SLOTS.contains(&o.kind.as_str()));
    for owned in &mut inventory.owned {
        owned.slug = clip(&owned.slug, 80);
        owned.name = clip(&owned.name, 120);
        owned.source = clip_opt(&owned.source, 32);
        owned.gift_from = clip_opt(&owned.gift_from, 32);
        owned.gift_message = clip_opt(&owned.gift_message, 500);
        owned.granted_at = clip_opt(&owned.granted_at, 40);
        owned.obtained_via = clip_opt(&owned.obtained_via, 32).filter(|v| v.bytes().all(|b| b.is_ascii_lowercase() || b == b'_'));
        let reward = owned.source.as_deref() == Some("reward") || owned.permanence.as_deref() == Some("conditional");
        owned.permanence = match owned.permanence.as_deref() {
            _ if reward => Some("conditional".into()),
            Some("permanent") => Some("permanent".into()),
            _ => None,
        };
        owned.state = owned.state.take().filter(|s| matches!(s.as_str(), "owned" | "eligible" | "unavailable"));
        let usable = owned.asset.as_ref().is_some_and(|a| {
            is_uuid(&a.id) && a.version >= 0 && a.sha256.len() == 64 && a.sha256.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
        });
        if !usable {
            owned.asset = None;
        }
    }
    inventory.equipped.retain(|slot, id| SLOTS.contains(&slot.as_str()) && is_uuid(id));
    Some(inventory)
}

/// What Flint last heard from the Store about one account: the answer as it came, its ETag and when.
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CacheFile {
    etag: Option<String>,
    fetched_at: u64,
    inventory: Value,
}

struct Cached {
    file: CacheFile,
    inventory: Inventory,
}

/// The cached copy for `uuid`, only if it is for `uuid`.
fn load_cache(state: &AppState, uuid: &str) -> Option<Cached> {
    let file: CacheFile = read_json_opt(&cache_path(state, uuid))?;
    let inventory = parse_inventory(&file.inventory)?;
    (canonical(&inventory.minecraft_uuid).as_deref() == Some(uuid)).then_some(Cached { file, inventory })
}

fn save_cache(state: &AppState, uuid: &str, etag: Option<String>, inventory: &Value) {
    let file = CacheFile { etag, fetched_at: now_millis(), inventory: inventory.clone() };
    if let Err(error) = write_json(&cache_path(state, uuid), &file) {
        eprintln!("[flint] couldn't keep a copy of the Store inventory: {error}");
    }
}

/// Removes everything Flint cached for the account from this Store (inventory and textures).
fn clear_cache(state: &AppState, uuid: &str) {
    let _ = std::fs::remove_dir_all(account_dir(state, uuid));
}

pub fn now_millis() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

#[derive(Debug, PartialEq)]
enum Fetched {
    /// A new answer (200).
    Fresh,
    /// The Store confirmed the cached copy (304).
    NotModified,
}

/// The account's inventory from the Store, sent with the cached copy's ETag so an unchanged inventory costs a 304.
async fn fetch_inventory(state: &AppState, uuid: &str, token: &str) -> Result<(Inventory, Fetched), Failure> {
    let cached = load_cache(state, uuid);
    let etag = cached.as_ref().and_then(|c| c.file.etag.clone());
    let answer = call(state, Method::GET, "/api/v1/me/inventory/", Some(token), None, etag.as_deref()).await?;
    if answer.status == 304 {
        // Only ever sent back for the ETag of the cached copy, which names this account and version.
        let cached = cached.ok_or(Failure::Invalid)?;
        save_cache(state, uuid, answer.etag.or(cached.file.etag), &cached.file.inventory);
        return Ok((cached.inventory, Fetched::NotModified));
    }
    let inventory = parse_inventory(&answer.body).ok_or(Failure::Invalid)?;
    if canonical(&inventory.minecraft_uuid).as_deref() != Some(uuid) {
        return Err(Failure::Refused { status: 409, code: "account_mismatch".into() });
    }
    save_cache(state, uuid, answer.etag, &answer.body);
    Ok((inventory, Fetched::Fresh))
}

/// What the Store page shows for one account.
#[derive(Serialize, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct StoreView {
    pub uuid: String,
    pub store_url: String,
    pub signed_in: bool,
    /// The system refused to read the saved Store sign-in; an explicit retry may ask again.
    pub locked: bool,
    pub name: Option<String>,
    pub session_expires_at: Option<String>,
    pub inventory: Option<Inventory>,
    /// The inventory shown is the last copy from the Store, which couldn't be reached now: display only.
    pub stale: bool,
    /// When the inventory shown came from (or was confirmed by) the Store, in ms.
    pub fetched_at: Option<u64>,
    pub problem: Option<String>,
}

fn empty_view(state: &AppState, uuid: &str) -> StoreView {
    StoreView { uuid: uuid.to_owned(), store_url: state.store.endpoints.store_base(), ..Default::default() }
}

/// Ends the account's Store session on this computer after the Store refused it (or answered for someone else).
fn signed_out_view(state: &AppState, uuid: &str, problem: &str) -> StoreView {
    drop_session(state, uuid);
    clear_cache(state, uuid);
    StoreView { problem: Some(problem.into()), ..empty_view(state, uuid) }
}

/// The account's Store page: signed out, or its inventory (fresh, confirmed, or the cached copy marked stale).
pub async fn view(state: &AppState, uuid: &str, interactive: bool) -> StoreView {
    let session = match load_session(state, uuid, interactive) {
        Lookup::Found(session) => session,
        Lookup::Missing => return empty_view(state, uuid),
        Lookup::Blocked => {
            return StoreView {
                locked: true,
                problem: Some("Flint couldn't read your saved Flint Store sign-in. Try again, and allow Flint if your system asks.".into()),
                ..empty_view(state, uuid)
            }
        }
    };
    if session.expired() {
        return signed_out_view(state, uuid, SIGN_IN_AGAIN);
    }
    let signed_in = StoreView {
        signed_in: true,
        name: Some(session.name.clone()),
        session_expires_at: Some(session.expires_at.clone()),
        ..empty_view(state, uuid)
    };
    match fetch_inventory(state, uuid, &session.token).await {
        Ok((inventory, _)) => StoreView { inventory: Some(inventory), fetched_at: Some(now_millis()), ..signed_in },
        Err(Failure::SignedOut) => signed_out_view(state, uuid, SIGN_IN_AGAIN),
        Err(Failure::Refused { code, .. }) if code == "account_mismatch" => signed_out_view(
            state,
            uuid,
            "The Flint Store answered for a different Minecraft account, so Flint signed this account out of the Store. Sign in again.",
        ),
        Err(failure) => {
            let cached = load_cache(state, uuid);
            StoreView {
                stale: true,
                fetched_at: cached.as_ref().map(|c| c.file.fetched_at),
                inventory: cached.map(|c| c.inventory),
                problem: Some(failure_text(&failure)),
                ..signed_in
            }
        }
    }
}

// ---------- signing in ----------

/// The Store's challenge: a single-use server id for Mojang's join.
async fn challenge(state: &AppState) -> CmdResult<(String, String)> {
    let answer = call(state, Method::POST, "/api/v1/auth/challenge/", None, Some(json!({})), None).await.map_err(|f| failure_text(&f))?;
    let id = answer.body["challengeId"].as_str().filter(|id| is_uuid(id)).ok_or("The Flint Store sent an unusable sign-in challenge.")?;
    let server_id = answer.body["serverId"]
        .as_str()
        .filter(|s| (1..=64).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-'))
        .ok_or("The Flint Store sent an unusable sign-in challenge.")?;
    Ok((id.to_owned(), server_id.to_owned()))
}

/// Mojang's `session/minecraft/join`, as a Minecraft client does before joining a server. The access token goes to
/// Mojang's session server only and is never logged.
async fn mojang_join(state: &AppState, access_token: &str, uuid: &str, server_id: &str) -> CmdResult<()> {
    use crate::mojangjoin::Joined;
    let url = state.store.endpoints.mojang.join("session/minecraft/join").map_err(|_| "Invalid Mojang address")?;
    // A 503 burst is asked again a few seconds apart before the player sees anything (crate::mojangjoin).
    match crate::mojangjoin::join(&state.http, url, REQUEST_TIMEOUT, access_token, uuid, server_id).await {
        Joined::Yes => Ok(()),
        Joined::Unreachable => Err("Flint couldn't reach Mojang to confirm your Minecraft account. Check your connection and try again.".into()),
        Joined::Refused(401 | 403) => Err("Mojang didn't accept this account's Minecraft session. Refresh the account in Settings → Accounts, then try again.".into()),
        Joined::Busy(429) => Err("Mojang is limiting sign-ins right now. Try again in a minute.".into()),
        Joined::Busy(_) => Err("Mojang's session server is busy right now. Try again in a minute.".into()),
        Joined::Refused(status) => Err(format!("Mojang's session server answered HTTP {status}. Try again shortly.")),
    }
}

/// Challenge → Mojang join → verify, for the Flint account `uuid` (Flint's form) named `username`, with its Minecraft
/// access token. The session is kept only if the Store's answer is for this same account.
pub async fn sign_in_with_token(state: &AppState, uuid: &str, username: &str, access_token: &str) -> CmdResult<Session> {
    let (challenge_id, server_id) = challenge(state).await?;
    mojang_join(state, access_token, uuid, &server_id).await?;
    let answer = call(state, Method::POST, "/api/v1/auth/verify/", None, Some(json!({ "challengeId": challenge_id, "username": username })), None)
        .await
        .map_err(|f| failure_text(&f))?;
    let token = answer.body["token"].as_str().filter(|t| valid_token(t)).ok_or("The Flint Store sent an unusable sign-in.")?;
    let player = &answer.body["player"];
    if player["uuid"].as_str().and_then(canonical).as_deref() != Some(uuid) {
        // The Store says this sign-in belongs to another Minecraft account: never keep it for this one.
        return Err("The Flint Store signed in a different Minecraft account than this one, so Flint didn't keep that sign-in.".into());
    }
    let session = Session {
        token: token.to_owned(),
        expires_at: answer.body["expiresAt"].as_str().map(|e| clip(e, 40)).unwrap_or_default(),
        name: player["name"].as_str().map(|n| clip(n, 32)).unwrap_or_else(|| username.to_owned()),
    };
    save_session(state, uuid, &session);
    Ok(session)
}

/// Revokes the account's Store session at the Store (best effort) and forgets it here, with its cached inventory.
pub async fn sign_out(state: &AppState, uuid: &str) {
    let known = match load_session(state, uuid, true) {
        Lookup::Found(session) => Some(session),
        _ => None,
    };
    if let Some(session) = known {
        let request = with_access(state.http.post(state.store.endpoints.api("/api/v1/auth/logout/")), &state.store.endpoints).timeout(LOGOUT_TIMEOUT).bearer_auth(&session.token).json(&json!({}));
        let _ = request.send().await;
    }
    drop_session(state, uuid);
    clear_cache(state, uuid);
}

/// An account removed from Flint: its Store session is revoked in the background and forgotten here, with its cache.
pub fn forget_account(state: &AppState, uuid: &str) {
    let Some(uuid) = canonical(uuid) else { return };
    if let Lookup::Found(session) = load_session(state, &uuid, true) {
        let request = with_access(state.http.post(state.store.endpoints.api("/api/v1/auth/logout/")), &state.store.endpoints).timeout(LOGOUT_TIMEOUT).bearer_auth(session.token).json(&json!({}));
        tauri::async_runtime::spawn(async move {
            let _ = request.send().await;
        });
    }
    drop_session(state, &uuid);
    clear_cache(state, &uuid);
}

/// A signed-in session for an action: none (or an expired one) is an error asking to sign in.
fn session_for_action(state: &AppState, uuid: &str) -> CmdResult<Session> {
    match load_session(state, uuid, true) {
        Lookup::Found(session) if !session.expired() => Ok(session),
        Lookup::Found(_) => {
            drop_session(state, uuid);
            clear_cache(state, uuid);
            Err(SIGN_IN_AGAIN.into())
        }
        Lookup::Missing => Err("Sign in to the Flint Store first.".into()),
        Lookup::Blocked => Err("Flint couldn't read your saved Flint Store sign-in. Allow Flint if your system asks, then try again.".into()),
    }
}

/// An action's failure: a refused session signs the account out of the Store here before the error is shown.
fn action_failure(state: &AppState, uuid: &str, failure: Failure) -> String {
    if failure == Failure::SignedOut {
        drop_session(state, uuid);
        clear_cache(state, uuid);
    }
    failure_text(&failure)
}

// ---------- the change feed, equipping, approving, textures ----------

#[derive(Serialize, Debug, PartialEq)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum Changes {
    #[serde(rename_all = "camelCase")]
    Ok { changed: bool, version: i64, poll_after_seconds: u64 },
    Offline,
    SignedOut,
}

/// `GET /api/v1/me/changes/?since=`: whether the inventory changed since `since`, and when to ask again.
pub async fn changes(state: &AppState, uuid: &str, since: Option<i64>) -> Changes {
    let session = match load_session(state, uuid, false) {
        Lookup::Found(session) if !session.expired() => session,
        _ => return Changes::SignedOut,
    };
    let path = format!("/api/v1/me/changes/?since={}", since.map(|v| v.to_string()).unwrap_or_default());
    match call(state, Method::GET, &path, Some(&session.token), None, None).await {
        Ok(answer) => {
            let poll = answer.body["pollAfterSeconds"].as_u64().unwrap_or(DEFAULT_POLL_SECONDS).clamp(POLL_RANGE.0, POLL_RANGE.1);
            match (answer.body["changed"].as_bool(), answer.body["version"].as_i64()) {
                (Some(changed), Some(version)) => Changes::Ok { changed, version, poll_after_seconds: poll },
                _ => Changes::Offline,
            }
        }
        Err(Failure::SignedOut) => {
            drop_session(state, uuid);
            clear_cache(state, uuid);
            Changes::SignedOut
        }
        Err(_) => Changes::Offline,
    }
}

/// `PUT /api/v1/me/equipped/`: asks the Store to put `cosmetic_id` in `slot` (or empty it). The Store decides; the
/// inventory shown afterwards is its answer.
pub async fn equip(state: &AppState, uuid: &str, slot: &str, cosmetic_id: Option<&str>) -> CmdResult<StoreView> {
    if !SLOTS.contains(&slot) {
        return Err("That item can't be worn there.".into());
    }
    if cosmetic_id.is_some_and(|id| !is_uuid(id)) {
        return Err("That isn't a Flint Store item.".into());
    }
    let session = session_for_action(state, uuid)?;
    call(state, Method::PUT, "/api/v1/me/equipped/", Some(&session.token), Some(json!({ "slot": slot, "cosmeticId": cosmetic_id })), None)
        .await
        .map_err(|f| action_failure(state, uuid, f))?;
    handoff::equip_changed(state);
    Ok(view(state, uuid, false).await)
}

/// A browser sign-in code as the Store writes it (`GXV9-MC1G`): case, spaces and dashes don't matter, and the letters the
/// Store never uses (O, I, L) are read as the digits they look like. None when it can't be such a code.
pub fn normalize_code(input: &str) -> Option<String> {
    const ALPHABET: &str = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    let mut code: String = input
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .map(|c| match c.to_ascii_uppercase() {
            'O' => '0',
            'I' | 'L' => '1',
            c => c,
        })
        .collect();
    if code.len() != 8 || !code.chars().all(|c| ALPHABET.contains(c)) {
        return None;
    }
    code.insert(4, '-');
    Some(code)
}

/// `POST /api/v1/store-link/approve/`: signs the browser showing `code` in to the Store as this account. Only after the
/// player confirmed the code in Flint's prompt.
pub async fn approve_link(state: &AppState, uuid: &str, code: &str) -> CmdResult<()> {
    let code = normalize_code(code).ok_or("That doesn't look like a Flint Store code. It has eight letters and digits, like GXV9-MC1G.")?;
    let session = session_for_action(state, uuid)?;
    call(state, Method::POST, "/api/v1/store-link/approve/", Some(&session.token), Some(json!({ "code": code })), None)
        .await
        .map(|_| ())
        .map_err(|failure| match failure {
            Failure::Refused { code: ref reason, .. } if reason == "code_invalid" => code_not_waiting(&state.store.endpoints),
            failure => action_failure(state, uuid, failure),
        })
}

/// The Store has no sign-in waiting for a code (`code_invalid`). The Store gives one answer for every reason: a code typed
/// wrong, older than its 10 minutes or already used, or one shown by another Flint Store. Each Store (the live one, staging, a
/// local one) keeps its codes in its own database, so only a launcher connected to that Store can approve them.
fn code_not_waiting(endpoints: &Endpoints) -> String {
    let host = endpoints.host();
    format!(
        "{host} has no sign-in waiting for that code. A code works once, for 10 minutes, and only on the Flint Store that showed it: \
         this Flint approves codes from {host}, so a code from a staging or test Store can't be approved here. Check the code, or \
         start the sign-in again on {host}."
    )
}

/// Where a texture may come from: the Store itself, or any https address (its storage). Never with credentials.
fn asset_url(endpoints: &Endpoints, raw: &str) -> Option<Url> {
    let url = Url::parse(raw).ok()?;
    if !url.username().is_empty() || url.password().is_some() {
        return None;
    }
    let same_origin = url.origin() == endpoints.store.origin();
    (url.scheme() == "https" || same_origin).then_some(url)
}

const PNG_SIGNATURE: &[u8] = b"\x89PNG\r\n\x1a\n";

fn sha256_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn data_url(bytes: &[u8]) -> String {
    format!("data:image/png;base64,{}", STANDARD.encode(bytes))
}

pub const HASH_MISMATCH: &str = "This item's texture didn't match the Flint Store's checksum, so Flint didn't use it.";

/// The texture of a cosmetic this account owns, as the Store last listed it: from the account's cache by (asset id,
/// version) when its SHA-256 still matches, otherwise downloaded (without the Store session), checked against the Store's
/// SHA-256 and only then kept. Returned as a data URL.
pub async fn asset(state: &AppState, uuid: &str, cosmetic_id: &str) -> CmdResult<String> {
    let cached = load_cache(state, uuid).ok_or("Flint has no Store inventory for this account yet.")?;
    let asset = cached
        .inventory
        .owned
        .iter()
        .find(|o| o.cosmetic_id == cosmetic_id)
        .and_then(|o| o.asset.clone())
        .ok_or("This item has no texture.")?;
    let path = account_dir(state, uuid).join("assets").join(format!("{}-v{}.png", asset.id, asset.version));
    verified_texture(state, &asset, &path).await.map(|bytes| data_url(&bytes))
}

/// A texture the Store approved (`asset`, its id already checked): from `path` when its SHA-256 still matches, otherwise
/// downloaded (without the Store session), checked against the Store's SHA-256 and only then kept at `path`.
pub(crate) async fn verified_texture(state: &AppState, asset: &Asset, path: &std::path::Path) -> CmdResult<Vec<u8>> {
    if let Ok(bytes) = std::fs::read(path) {
        if sha256_hex(&bytes) == asset.sha256 {
            return Ok(bytes);
        }
        // Changed on disk since it was checked: fetch it again.
        let _ = std::fs::remove_file(path);
    }
    let url = asset_url(&state.store.endpoints, &asset.url).ok_or("The Flint Store listed this texture at an address Flint doesn't download from.")?;
    let response = state.http.get(url).timeout(REQUEST_TIMEOUT).send().await.map_err(|_| OFFLINE.to_owned())?;
    if !response.status().is_success() {
        return Err(format!("The texture couldn't be downloaded (HTTP {}).", response.status().as_u16()));
    }
    let bytes = read_capped(response, MAX_ASSET).await.map_err(|_| "The texture couldn't be downloaded completely.".to_owned())?;
    if sha256_hex(&bytes) != asset.sha256 {
        return Err(HASH_MISMATCH.into());
    }
    if !bytes.starts_with(PNG_SIGNATURE) {
        return Err("This item's texture isn't a PNG image.".into());
    }
    if let Err(error) = write_atomic(path, &bytes) {
        eprintln!("[flint] couldn't keep a Store texture: {error}");
    }
    Ok(bytes)
}

#[derive(Deserialize, Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all(serialize = "camelCase"))]
pub struct LedgerEntry {
    #[serde(default, deserialize_with = "lenient_i64")]
    pub delta: Option<i64>,
    #[serde(default, deserialize_with = "lenient_i64")]
    pub balance_after: Option<i64>,
    #[serde(default)]
    pub reason: Option<String>,
    #[serde(default)]
    pub created_at: Option<String>,
}

/// `GET /api/v1/me/wallet/`: the Store Merit balance and its latest movements.
#[derive(Deserialize, Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all(serialize = "camelCase"))]
pub struct Wallet {
    #[serde(default, deserialize_with = "lenient_i64")]
    pub balance: Option<i64>,
    #[serde(default)]
    pub ledger: Vec<LedgerEntry>,
}

#[derive(Deserialize, Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all(serialize = "camelCase"))]
pub struct Order {
    pub id: String,
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub payment_method: Option<String>,
    #[serde(default)]
    pub test_mode: bool,
    #[serde(default)]
    pub item: Option<String>,
    #[serde(default, deserialize_with = "lenient_i64")]
    pub coins: Option<i64>,
    #[serde(default, deserialize_with = "lenient_i64")]
    pub amount_minor: Option<i64>,
    #[serde(default)]
    pub currency: Option<String>,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub gift: bool,
    #[serde(default)]
    pub recipient_name: Option<String>,
    /// The checkout the order belongs to: every line of one cart has the same.
    #[serde(default)]
    pub reference: Option<String>,
}

pub async fn wallet(state: &AppState, uuid: &str) -> CmdResult<Wallet> {
    let session = session_for_action(state, uuid)?;
    let answer = call(state, Method::GET, "/api/v1/me/wallet/", Some(&session.token), None, None).await.map_err(|f| action_failure(state, uuid, f))?;
    let mut wallet: Wallet = serde_json::from_value(answer.body).map_err(|_| failure_text(&Failure::Invalid))?;
    wallet.ledger.truncate(200);
    for entry in &mut wallet.ledger {
        entry.reason = clip_opt(&entry.reason, 80);
        entry.created_at = clip_opt(&entry.created_at, 40);
    }
    Ok(wallet)
}

pub async fn orders(state: &AppState, uuid: &str) -> CmdResult<Vec<Order>> {
    let session = session_for_action(state, uuid)?;
    let answer = call(state, Method::GET, "/api/v1/me/orders/", Some(&session.token), None, None).await.map_err(|f| action_failure(state, uuid, f))?;
    let mut orders: Vec<Order> = serde_json::from_value(answer.body["orders"].clone()).map_err(|_| failure_text(&Failure::Invalid))?;
    orders.truncate(200);
    for order in &mut orders {
        order.item = clip_opt(&order.item, 120);
        order.recipient_name = clip_opt(&order.recipient_name, 32);
        order.reference = clip_opt(&order.reference, 40);
    }
    Ok(orders)
}

// ---------- time ----------

fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = (month + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// Seconds since the Unix epoch for an RFC 3339 time as Postgres and JavaScript write it (`2026-11-28T09:15:00.123+00:00`,
/// `…Z`, `2026-11-28 09:15:00+00`). None when it isn't one.
pub fn rfc3339_secs(text: &str) -> Option<i64> {
    let t = text.trim();
    let b = t.as_bytes();
    if b.len() < 20 || b[4] != b'-' || b[7] != b'-' || !matches!(b[10], b'T' | b't' | b' ') || b[13] != b':' || b[16] != b':' {
        return None;
    }
    let num = |from: usize, to: usize| -> Option<i64> { t.get(from..to).filter(|s| s.bytes().all(|c| c.is_ascii_digit())).and_then(|s| s.parse().ok()) };
    let (year, month, day, hour, minute, second) = (num(0, 4)?, num(5, 7)?, num(8, 10)?, num(11, 13)?, num(14, 16)?, num(17, 19)?);
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) || hour > 23 || minute > 59 || second > 60 {
        return None;
    }
    let mut rest = &t[19..];
    if let Some(fraction) = rest.strip_prefix('.') {
        let digits = fraction.bytes().take_while(u8::is_ascii_digit).count();
        if digits == 0 {
            return None;
        }
        rest = &fraction[digits..];
    }
    let offset = match rest {
        "Z" | "z" => 0,
        _ => {
            let sign = match rest.as_bytes().first()? {
                b'+' => 1,
                b'-' => -1,
                _ => return None,
            };
            let zone: String = rest[1..].chars().filter(|c| *c != ':').collect();
            if !(zone.len() == 2 || zone.len() == 4) || !zone.bytes().all(|c| c.is_ascii_digit()) {
                return None;
            }
            let hours: i64 = zone[..2].parse().ok()?;
            let minutes: i64 = if zone.len() == 4 { zone[2..].parse().ok()? } else { 0 };
            sign * (hours * 3600 + minutes * 60)
        }
    };
    Some(days_from_civil(year, month, day) * 86_400 + hour * 3600 + minute * 60 + second - offset)
}

// ---------- commands ----------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StoreInfo {
    store_url: String,
    contract_version: u32,
    production: bool,
}

#[tauri::command]
pub fn store_info(state: State<'_, AppState>) -> StoreInfo {
    let store_url = state.store.endpoints.store_base();
    StoreInfo { production: store_url == PRODUCTION_STORE, store_url, contract_version: CONTRACT_VERSION }
}

/// The account's Store page. `interactive`: the player asked (may ask the system for the saved sign-in again).
#[tauri::command]
pub async fn store_inventory(state: State<'_, AppState>, uuid: String, interactive: Option<bool>) -> CmdResult<StoreView> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    Ok(view(&state, &uuid, interactive.unwrap_or(false)).await)
}

/// Signs the account in to the Store: Flint's own Minecraft session for it (renewed if needed), joined at Mojang.
#[tauri::command]
pub async fn store_sign_in(app: AppHandle, state: State<'_, AppState>, uuid: String) -> CmdResult<StoreView> {
    let (uuid, account) = flint_account(&state, &uuid)?;
    if account.qa {
        return Err("The test identity has no Minecraft account, so it can't sign in to the Flint Store.".into());
    }
    let access_token = crate::auth::minecraft_token_explicit(&app, &state, &account.uuid).await?;
    // A renewal may have brought a new name; Mojang's check is by the current one.
    let username = flint_account(&state, &uuid).map(|(_, a)| a.username).unwrap_or(account.username);
    sign_in_with_token(&state, &uuid, &username, &access_token).await?;
    Ok(view(&state, &uuid, true).await)
}

#[tauri::command]
pub async fn store_sign_out(state: State<'_, AppState>, uuid: String) -> CmdResult<()> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    sign_out(&state, &uuid).await;
    Ok(())
}

#[tauri::command]
pub async fn store_changes(state: State<'_, AppState>, uuid: String, since: Option<i64>) -> CmdResult<Changes> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    Ok(changes(&state, &uuid, since).await)
}

#[tauri::command]
pub async fn store_equip(state: State<'_, AppState>, uuid: String, slot: String, cosmetic_id: Option<String>) -> CmdResult<StoreView> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    equip(&state, &uuid, &slot, cosmetic_id.as_deref()).await
}

#[tauri::command]
pub async fn store_asset(state: State<'_, AppState>, uuid: String, cosmetic_id: String) -> CmdResult<String> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    asset(&state, &uuid, &cosmetic_id).await
}

#[tauri::command]
pub async fn store_wallet(state: State<'_, AppState>, uuid: String) -> CmdResult<Wallet> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    wallet(&state, &uuid).await
}

#[tauri::command]
pub async fn store_orders(state: State<'_, AppState>, uuid: String) -> CmdResult<Vec<Order>> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    orders(&state, &uuid).await
}

#[tauri::command]
pub async fn store_approve_link(state: State<'_, AppState>, uuid: String, code: String) -> CmdResult<()> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    approve_link(&state, &uuid, &code).await
}
