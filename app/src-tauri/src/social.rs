//! Friends: Flint players adding each other, seeing who is online and messaging, through Flint's Supabase project
//! (PostgREST RPC functions, `POST {url}/rest/v1/rpc/<name>` with the project's publishable key).
//!
//! - **Proof of account** with a challenge Flint's server issues (Backend 20261104006500): `flint_signin_challenge` hands out
//!   a single-use server id, Flint joins it at Mojang's session server with the account's own Minecraft access token (sent to
//!   Mojang only), then `flint_social_signin_v2` asks Mojang `hasJoined` for that challenge and answers a Friends session
//!   token. (The first version let the launcher pick the server id, which any Minecraft server the player joins also
//!   learns; 20261104006400 turned it off.) Nothing about it is ever shown to the player.
//! - **The session is kept** for each account in the system credential store (Windows Credential Manager, the macOS
//!   Keychain, the Linux Secret Service), so starting Flint signs in nobody again: the saved session is used at once and
//!   only a session the server refuses, or one about to expire, is replaced. Switching accounts keeps each account's
//!   session; removing an account, or signing out of Flint, ends it. The friends list of the last sync is kept on disk
//!   (no messages, no token) and shown, marked as not live, until the first sync confirms it.
//! - **One background task** (`start`) keeps the state: `flint_social_state` is both the sync and the heartbeat, every 4 s
//!   while a chat is open and every 15 s otherwise, in the background too (a game running with Flint minimized). Each answer
//!   is merged into the last one and compared with it: the interface gets `social:update` (the whole snapshot) and
//!   `social:event` (a request, an accepted request, a message, a friend coming online), never for the first sync after
//!   Flint starts or an account signs in.
//! - **Nothing is fatal**: offline, the last state stays on screen marked offline and is retried; a server without the
//!   functions (PostgREST 404, PGRST202) is "not available right now" and asked again every 10 minutes; a build without
//!   the project's address says Friends isn't available in it.
//! - **In the game** (`game.rs`): a game Flint started gets this state, trimmed, for the account it plays as, and runs these
//!   same actions over the game link (`gamelink.rs`); the Friends session never leaves Flint.
//! - The project address and key come from the build (`FLINT_SUPABASE_URL`, `FLINT_SUPABASE_ANON_KEY`, as for the game
//!   runtime's services); a secret (service role) key is refused. Tokens never appear in logs, errors or `Debug`.

#[cfg(test)]
pub mod fake;
pub mod game;
#[cfg(test)]
mod tests;

use crate::core::*;
use crate::store::{canonical, rfc3339_secs};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::future::Future;
use std::pin::Pin;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::Notify;
use url::Url;

pub const MOJANG_SESSION_SERVER: &str = "https://sessionserver.mojang.com";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);
/// While a chat is open.
pub const CHAT_POLL: Duration = Duration::from_secs(4);
pub const IDLE_POLL: Duration = Duration::from_secs(15);
/// The server doesn't have the Friends functions yet.
pub const UNAVAILABLE_RETRY: Duration = Duration::from_secs(600);
/// No account signed in: only this computer's account list is looked at, nothing is sent.
const SIGNED_OUT_CHECK: Duration = Duration::from_secs(15);
const MAX_BACKOFF: Duration = Duration::from_secs(60);
/// A session with less than this left is renewed (while it still works).
const RENEW_BEFORE_MS: i64 = 24 * 3600 * 1000;
/// A renewal that failed is tried again after this.
const RENEW_RETRY: Duration = Duration::from_secs(3600);
/// A saved session with less than this left isn't used: Flint signs in afresh.
const SAVED_MIN_LEFT_MS: i64 = 5 * 60 * 1000;
/// When a sign-in's answer has no usable expiry: a day short of the server's default week.
const SESSION_FALLBACK_MS: i64 = 6 * 24 * 3600 * 1000;
/// The most recent messages kept per friend.
pub const CONVERSATION_CAP: usize = 200;
pub const MAX_BODY: usize = 1000;
const MAX_JSON: usize = 4 * 1024 * 1024;
pub const STATUSES: [&str; 4] = ["online", "busy", "away", "invisible"];

/// How often Friends may ask, whatever wakes it: syncs at least `min_gap` apart (an open chat's 4 s), and after "expired"
/// a new sign-in at most every `resign_gap`, so a session that keeps ending can never make a loop.
#[derive(Clone, Copy, Debug)]
pub struct Pacing {
    pub min_gap: Duration,
    pub resign_gap: Duration,
}

pub const PACING: Pacing = Pacing { min_gap: CHAT_POLL, resign_gap: Duration::from_secs(60) };

// ---------- where the server is ----------

/// The Supabase project Friends talks to, and Mojang's session server (overridden only in tests).
#[derive(Clone)]
pub struct Endpoints {
    pub project: Url,
    pub key: String,
    pub mojang: Url,
}

impl std::fmt::Debug for Endpoints {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Endpoints").field("project", &self.project.as_str()).field("key", &"<redacted>").field("mojang", &self.mojang.as_str()).finish()
    }
}

/// Whether `key` is a secret (service role) key: Supabase's `sb_secret_…`, or an older JWT-shaped key whose role says so.
pub fn is_secret_key(key: &str) -> bool {
    if key.starts_with("sb_secret_") {
        return true;
    }
    let parts: Vec<&str> = key.split('.').collect();
    if parts.len() != 3 {
        return false;
    }
    URL_SAFE_NO_PAD
        .decode(parts[1].trim_end_matches('='))
        .ok()
        .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok())
        .is_some_and(|claims| claims["role"] == "service_role")
}

impl Endpoints {
    /// The project from its address and publishable key: an https address (http only on this computer), with nothing
    /// after the host; never a secret key. None when either is missing or unusable.
    pub fn new(url: &str, key: &str) -> Option<Self> {
        let (url, key) = (url.trim(), key.trim());
        if url.is_empty() || key.is_empty() || is_secret_key(key) || !key.bytes().all(|b| b.is_ascii_graphic()) {
            return None;
        }
        let mut project = Url::parse(url).ok()?;
        let local = matches!(project.host_str(), Some("127.0.0.1" | "localhost"));
        if !(project.scheme() == "https" || (project.scheme() == "http" && local)) || !project.username().is_empty() || project.query().is_some() {
            return None;
        }
        if !project.path().trim_end_matches('/').is_empty() {
            return None;
        }
        project.set_path("/");
        Some(Self { project, key: key.to_owned(), mojang: Url::parse(MOJANG_SESSION_SERVER).expect("valid") })
    }

    /// Release builds: the address and key the build was made with. Development builds may also take them from the
    /// environment when run, to try Friends against another project.
    pub fn from_build(development: bool) -> Option<Self> {
        let built = Self::new(option_env!("FLINT_SUPABASE_URL").unwrap_or_default(), option_env!("FLINT_SUPABASE_ANON_KEY").unwrap_or_default());
        if built.is_some() || !development {
            return built;
        }
        Self::new(&std::env::var("FLINT_SUPABASE_URL").ok()?, &std::env::var("FLINT_SUPABASE_ANON_KEY").ok()?)
    }

    fn rpc(&self, name: &str) -> Url {
        self.project.join(&format!("rest/v1/rpc/{name}")).expect("RPC paths are valid")
    }
}

// ---------- what the server sends ----------

#[derive(Serialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    #[default]
    SignedOut,
    Connecting,
    Unavailable,
    Ready,
    Error,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Me {
    pub uuid: String,
    pub name: String,
    pub status: String,
    pub allow_requests: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Friend {
    pub uuid: String,
    pub name: String,
    /// online, busy, away or offline (someone invisible is offline to their friends).
    pub status: String,
    pub in_game: bool,
    /// Milliseconds since the epoch.
    pub last_seen: Option<i64>,
    pub pinned: bool,
    pub since: Option<i64>,
    pub unread: u32,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct FriendRequest {
    /// As the server sent it (a number or a string), and sent back as it came.
    pub id: Value,
    pub uuid: String,
    pub name: String,
    pub at: Option<i64>,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    pub id: i64,
    pub from: String,
    pub to: String,
    pub body: String,
    /// The sender confirmed it after Flint warned it looked private, or the server found an email or card number in it:
    /// shown blurred until clicked.
    pub sensitive: bool,
    pub at: Option<i64>,
    pub read: bool,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Player {
    pub uuid: String,
    pub name: String,
    /// none, friend, incoming, outgoing or self.
    pub relation: String,
    /// They take friend requests.
    pub accepts: bool,
}

#[derive(Serialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Conversation {
    /// Oldest first, the most recent `CONVERSATION_CAP`.
    pub messages: Vec<Message>,
    /// The latest page of the history was fetched (so the interface doesn't ask again on opening the chat).
    pub loaded: bool,
}

/// Everything the Friends page shows.
#[derive(Serialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    /// This build knows where Friends lives.
    pub configured: bool,
    pub phase: Phase,
    /// The Flint account Friends is for.
    pub account: Option<String>,
    pub me: Option<Me>,
    pub friends: Vec<Friend>,
    pub incoming: Vec<FriendRequest>,
    pub outgoing: Vec<FriendRequest>,
    /// By friend.
    pub conversations: BTreeMap<String, Conversation>,
    /// The last sync failed for want of a network: what is shown is the last state, retried.
    pub offline: bool,
    /// A short code (`offline`, `mojang`, `unverified`, …) when the phase is `error`, or when an otherwise ready state
    /// couldn't be refreshed.
    pub error: Option<String>,
    /// When the state shown was last confirmed, in ms.
    pub synced_at: Option<u64>,
    /// The chat open on the Friends page.
    pub focus: Option<String>,
    /// What is shown is the last run's (kept on disk), not yet confirmed by a sync: no status is live.
    pub stale: bool,
}

impl Snapshot {
    /// The same data, whenever it was confirmed.
    pub fn same_as(&self, other: &Snapshot) -> bool {
        let mut a = self.clone();
        a.synced_at = other.synced_at;
        &a == other
    }
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum SocialEvent {
    Request { id: Value, uuid: String, name: String },
    Accepted { uuid: String, name: String },
    Message { name: String, message: Message },
    Online { uuid: String, name: String, status: String },
}

fn clip(text: &str, max: usize) -> String {
    text.chars().filter(|c| !c.is_control() || *c == '\n').take(max).collect()
}

fn uuid_of(value: &Value) -> Option<String> {
    value.as_str().and_then(canonical)
}

fn name_of(value: &Value) -> Option<String> {
    let name = value.as_str()?.trim();
    (!name.is_empty()).then(|| clip(name, 32))
}

/// A time as the server sends it (RFC 3339 text, or seconds or milliseconds since the epoch), in milliseconds.
pub fn millis(value: &Value) -> Option<i64> {
    match value {
        Value::String(text) => rfc3339_secs(text).map(|s| s * 1000),
        Value::Number(n) => n.as_f64().map(|v| if v < 1e11 { (v * 1000.0) as i64 } else { v as i64 }),
        _ => None,
    }
}

fn status_of(value: &Value, allowed: &[&str], otherwise: &str) -> String {
    value.as_str().filter(|s| allowed.contains(s)).unwrap_or(otherwise).to_owned()
}

fn id_of(value: &Value) -> Option<Value> {
    match value {
        Value::Number(n) if n.is_i64() || n.is_u64() => Some(value.clone()),
        Value::String(s) if (1..=64).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-') => Some(value.clone()),
        _ => None,
    }
}

fn id_key(value: &Value) -> String {
    match value {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

pub fn parse_me(value: &Value) -> Option<Me> {
    Some(Me {
        uuid: uuid_of(&value["uuid"])?,
        name: name_of(&value["name"])?,
        status: status_of(&value["status"], &STATUSES, "online"),
        allow_requests: value["allow_requests"].as_bool().unwrap_or(true),
    })
}

fn parse_friend(value: &Value) -> Option<Friend> {
    Some(Friend {
        uuid: uuid_of(&value["uuid"])?,
        name: name_of(&value["name"])?,
        status: status_of(&value["status"], &["online", "busy", "away", "offline"], "offline"),
        in_game: value["in_game"].as_bool().unwrap_or(false),
        last_seen: millis(&value["last_seen"]),
        pinned: value["pinned"].as_bool().unwrap_or(false),
        since: millis(&value["since"]),
        unread: value["unread"].as_u64().unwrap_or(0).min(u32::MAX as u64) as u32,
    })
}

fn parse_request(value: &Value) -> Option<FriendRequest> {
    Some(FriendRequest { id: id_of(&value["id"])?, uuid: uuid_of(&value["uuid"])?, name: name_of(&value["name"])?, at: millis(&value["at"]) })
}

pub fn parse_message(value: &Value) -> Option<Message> {
    Some(Message {
        id: value["id"].as_i64().or_else(|| value["id"].as_str().and_then(|s| s.parse().ok()))?,
        from: uuid_of(&value["from"])?,
        to: uuid_of(&value["to"])?,
        body: clip(value["body"].as_str()?, MAX_BODY),
        sensitive: value["sensitive"].as_bool().unwrap_or(false),
        at: millis(&value["at"]),
        read: value["read"].as_bool().unwrap_or(false),
    })
}

fn parse_player(value: &Value) -> Option<Player> {
    Some(Player {
        uuid: uuid_of(&value["uuid"])?,
        name: name_of(&value["name"])?,
        relation: status_of(&value["relation"], &["none", "friend", "incoming", "outgoing", "self"], "none"),
        accepts: value["accepts"].as_bool().unwrap_or(false),
    })
}

fn list<T>(value: &Value, parse: impl Fn(&Value) -> Option<T>, max: usize) -> Vec<T> {
    value.as_array().map(|items| items.iter().take(max).filter_map(parse).collect()).unwrap_or_default()
}

/// One `flint_social_state` answer, checked.
#[derive(Debug, Clone, PartialEq)]
pub struct StateAnswer {
    pub me: Me,
    pub friends: Vec<Friend>,
    pub incoming: Vec<FriendRequest>,
    pub outgoing: Vec<FriendRequest>,
    pub messages: Vec<Message>,
}

pub fn parse_state(body: &Value) -> Option<StateAnswer> {
    let mut messages = list(&body["messages"], parse_message, 5000);
    messages.sort_by_key(|m| m.id);
    Some(StateAnswer {
        me: parse_me(&body["me"])?,
        friends: list(&body["friends"], parse_friend, 2000),
        incoming: list(&body["incoming"], parse_request, 500),
        outgoing: list(&body["outgoing"], parse_request, 500),
        messages,
    })
}

/// A Minecraft name as Mojang allows it.
pub fn is_player_name(name: &str) -> bool {
    (1..=16).contains(&name.len()) && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

// ---------- failures ----------

#[derive(Debug, Clone, PartialEq)]
pub enum Fail {
    /// Flint has no Minecraft account signed in (or only the offline test identity).
    SignedOut,
    /// This build doesn't know where Friends lives.
    NotConfigured,
    /// The network or the server is down (or didn't answer in time).
    Offline,
    /// The server doesn't have that function yet (PostgREST 404, PGRST202).
    Unavailable,
    /// The Friends session has ended: sign in again.
    Expired,
    /// The server said no, with its code (`not_on_flint`, `rate_limited`…), or Flint refused the request itself.
    Refused(String),
    /// Mojang didn't take the account's session.
    Mojang,
    /// Flint couldn't get the account's Minecraft session (its sign-in needs renewing).
    Account,
    /// An answer that isn't what the contract says.
    Invalid,
}

impl Fail {
    /// The short code the interface translates.
    pub fn code(&self) -> String {
        match self {
            Fail::SignedOut => "signed_out".into(),
            Fail::NotConfigured => "not_configured".into(),
            Fail::Offline => "offline".into(),
            Fail::Unavailable => "unavailable".into(),
            Fail::Expired => "expired".into(),
            Fail::Refused(code) => code.clone(),
            Fail::Mojang => "mojang".into(),
            Fail::Account => "account".into(),
            Fail::Invalid => "invalid".into(),
        }
    }
}

async fn read_capped(response: reqwest::Response) -> Result<Vec<u8>, Fail> {
    if response.content_length().is_some_and(|n| n as usize > MAX_JSON) {
        return Err(Fail::Invalid);
    }
    let mut out = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|_| Fail::Offline)?;
        if out.len() + chunk.len() > MAX_JSON {
            return Err(Fail::Invalid);
        }
        out.extend_from_slice(&chunk);
    }
    Ok(out)
}

/// A Friends session token as the server issues them (64 hex), or at least something safe to send back.
fn valid_session_token(token: &str) -> bool {
    (16..=200).contains(&token.len()) && token.bytes().all(|b| b.is_ascii_graphic())
}

/// A code as the server wrote it, kept short and plain.
fn code_of(value: &Value) -> String {
    let code: String = value.as_str().unwrap_or_default().chars().filter(|c| c.is_ascii_alphanumeric() || *c == '_').take(40).collect();
    if code.is_empty() {
        "refused".into()
    } else {
        code
    }
}

// ---------- the account Friends is for ----------

#[derive(Clone, Debug, PartialEq)]
pub struct Identity {
    /// 32 lowercase hex digits.
    pub uuid: String,
    pub name: String,
}

pub type TokenFuture<'a> = Pin<Box<dyn Future<Output = Option<String>> + Send + 'a>>;

/// A Friends session kept on this computer between runs: its token and when it ends (ms since the epoch).
#[derive(Serialize, Deserialize, Clone, PartialEq)]
pub struct SavedSession {
    pub token: String,
    pub expires_at: i64,
}

impl std::fmt::Debug for SavedSession {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SavedSession").field("token", &"<hidden>").field("expires_at", &self.expires_at).finish()
    }
}

/// What the Friends page showed after the last sync, kept on disk per account so it opens at once: no messages, no token,
/// and statuses aren't shown from it (the interface marks it as not live).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
pub struct CachedView {
    pub me: Option<Me>,
    pub friends: Vec<Friend>,
    pub incoming: Vec<FriendRequest>,
    pub outgoing: Vec<FriendRequest>,
}

/// Flint's side of signing in: which account is active, its Minecraft access token, and what Friends keeps for it on this
/// computer. The keeping is optional (a test's accounts keep nothing unless they say so).
pub trait Minecraft: Send + Sync {
    fn account(&self) -> Option<Identity>;
    /// The account's Minecraft access token, or None when Flint can't have one now without asking the player.
    fn access_token<'a>(&'a self, uuid: &'a str) -> TokenFuture<'a>;
    /// The Friends session saved for this account, if Flint can read it now without asking the player.
    fn saved_session(&self, _uuid: &str) -> Option<SavedSession> {
        None
    }
    fn save_session(&self, _uuid: &str, _session: &SavedSession) {}
    fn forget_session(&self, _uuid: &str) {}
    fn cached_view(&self, _uuid: &str) -> Option<CachedView> {
        None
    }
    fn save_view(&self, _uuid: &str, _view: &CachedView) {}
    /// A line for Friends' own log (timings only: never a token, a message or a name).
    fn note(&self, _line: &str) {}
}

// ---------- the state ----------

#[derive(Default)]
struct Inner {
    phase: Phase,
    account: Option<Identity>,
    token: Option<String>,
    me: Option<Me>,
    friends: Vec<Friend>,
    incoming: Vec<FriendRequest>,
    outgoing: Vec<FriendRequest>,
    conversations: HashMap<String, Conversation>,
    /// The newest message id seen in a sync.
    last_message: i64,
    /// At least one sync for this account went through (events start after it).
    synced: bool,
    offline: bool,
    error: Option<String>,
    synced_at: Option<u64>,
    failures: u32,
    /// The server didn't have the Friends functions: asked again from then.
    retry_at: Option<Instant>,
    /// When the last sync went out.
    last_sync: Option<Instant>,
    /// When this account last signed in.
    signed_in_at: Option<Instant>,
    /// When `token` ends (ms since the epoch), as the server said.
    expires_at: Option<i64>,
    /// `token` came from the credential store (this run hasn't signed in).
    restored: bool,
    /// The last time a session about to expire was renewed (or tried).
    renewed_at: Option<Instant>,
    /// Showing the last run's view until the first sync.
    stale: bool,
    /// The view last written to disk (written again only when it changes).
    saved_view: Option<CachedView>,
}

impl Inner {
    fn for_account(account: Option<Identity>) -> Self {
        Self { account, ..Default::default() }
    }

    fn me_uuid(&self) -> Option<&str> {
        self.account.as_ref().map(|a| a.uuid.as_str())
    }

    /// What is kept on disk: who my friends and requests are, without anything live (statuses, in game, last seen), so a
    /// friend coming and going doesn't rewrite it and nothing stale can pass for live.
    fn view(&self) -> CachedView {
        let friends = self
            .friends
            .iter()
            .map(|f| Friend { status: "offline".into(), in_game: false, last_seen: None, ..f.clone() })
            .collect();
        CachedView { me: self.me.clone(), friends, incoming: self.incoming.clone(), outgoing: self.outgoing.clone() }
    }

    /// Adds messages to the conversation each belongs to (the other person's), without repeats, oldest first, keeping the
    /// most recent `CONVERSATION_CAP`.
    fn add_messages(&mut self, messages: &[Message]) {
        let Some(me) = self.me_uuid().map(str::to_owned) else { return };
        for message in messages {
            let other = if message.from == me { &message.to } else { &message.from };
            let conversation = self.conversations.entry(other.clone()).or_default();
            match conversation.messages.binary_search_by_key(&message.id, |m| m.id) {
                Ok(at) => conversation.messages[at] = message.clone(),
                Err(at) => conversation.messages.insert(at, message.clone()),
            }
            let extra = conversation.messages.len().saturating_sub(CONVERSATION_CAP);
            conversation.messages.drain(..extra);
        }
    }
}

pub struct Step {
    pub events: Vec<SocialEvent>,
    /// How long until the next step (sooner if woken).
    pub wait: Duration,
}

pub struct Social {
    http: reqwest::Client,
    endpoints: Option<Endpoints>,
    inner: Mutex<Inner>,
    focus: Mutex<Option<String>>,
    pub wake: Notify,
    sign_in: tokio::sync::Mutex<()>,
    pacing: Pacing,
}

impl Social {
    pub fn new(http: reqwest::Client, endpoints: Option<Endpoints>) -> Self {
        Self { http, endpoints, inner: Mutex::default(), focus: Mutex::default(), wake: Notify::new(), sign_in: tokio::sync::Mutex::new(()), pacing: PACING }
    }

    pub fn configured(&self) -> bool {
        self.endpoints.is_some()
    }

    /// The request that ends a Friends session at the server (whoever holds it sends it, in the background).
    pub fn signout_request(&self, token: &str) -> Option<reqwest::RequestBuilder> {
        let endpoints = self.endpoints.as_ref()?;
        Some(
            self.http
                .post(endpoints.rpc("flint_social_signout"))
                .timeout(REQUEST_TIMEOUT)
                .header("apikey", &endpoints.key)
                .bearer_auth(&endpoints.key)
                .header("Accept", "application/json")
                .json(&json!({ "p_token": token })),
        )
    }

    /// The project's host (`<ref>.supabase.co`), naming what Friends keeps per project on this computer; "none" unbuilt.
    pub fn project_key(&self) -> String {
        self.endpoints.as_ref().and_then(|e| e.project.host_str().map(str::to_owned)).unwrap_or_else(|| "none".into())
    }

    pub fn snapshot(&self) -> Snapshot {
        let inner = self.inner.lock().unwrap();
        Snapshot {
            configured: self.configured(),
            phase: if self.configured() { inner.phase } else { Phase::Unavailable },
            account: inner.account.as_ref().map(|a| a.uuid.clone()),
            me: inner.me.clone(),
            friends: inner.friends.clone(),
            incoming: inner.incoming.clone(),
            outgoing: inner.outgoing.clone(),
            conversations: inner.conversations.iter().map(|(k, v)| (k.clone(), v.clone())).collect(),
            offline: inner.offline,
            error: inner.error.clone(),
            synced_at: inner.synced_at,
            focus: self.focus.lock().unwrap().clone(),
            stale: inner.stale,
        }
    }

    /// The chat open on the Friends page (polled every 4 s while it is). Returns whether it changed.
    pub fn set_focus(&self, chat: Option<String>) -> bool {
        let chat = chat.as_deref().and_then(canonical);
        let mut focus = self.focus.lock().unwrap();
        let changed = *focus != chat;
        *focus = chat;
        changed
    }

    fn poll_interval(&self) -> Duration {
        if self.focus.lock().unwrap().is_some() {
            CHAT_POLL
        } else {
            IDLE_POLL
        }
    }

    /// The next step runs now, and a pending "almost here" or offline wait is cut short.
    pub fn retry_now(&self) {
        let mut inner = self.inner.lock().unwrap();
        inner.retry_at = None;
        inner.failures = 0;
        drop(inner);
        self.wake.notify_one();
    }

    async fn rpc(&self, name: &str, args: Value) -> Result<Value, Fail> {
        let endpoints = self.endpoints.as_ref().ok_or(Fail::NotConfigured)?;
        let response = self
            .http
            .post(endpoints.rpc(name))
            .timeout(REQUEST_TIMEOUT)
            .header("apikey", &endpoints.key)
            .bearer_auth(&endpoints.key)
            .header("Accept", "application/json")
            .json(&args)
            .send()
            .await
            .map_err(|_| Fail::Offline)?;
        let status = response.status().as_u16();
        let bytes = read_capped(response).await?;
        let body: Value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
        match status {
            200..=299 => {}
            // PGRST202: no such function (yet). A 404 without it is the same to the player.
            404 => return Err(Fail::Unavailable),
            408 | 500..=599 => return Err(Fail::Offline),
            429 => return Err(Fail::Refused("rate_limited".into())),
            _ => return Err(Fail::Invalid),
        }
        if !body.is_object() {
            return Err(Fail::Invalid);
        }
        if body["ok"] == false {
            let code = code_of(&body["error"]);
            if code == "invalid" {
                // Flint sent something the server couldn't take: a bug to find, shown as a calm "didn't work".
                eprintln!("[flint] Friends: {name} refused its arguments as invalid");
            }
            return Err(if code == "expired" { Fail::Expired } else { Fail::Refused(code) });
        }
        if body["ok"] != true {
            return Err(Fail::Invalid);
        }
        Ok(body)
    }

    /// Mojang's `session/minecraft/join`, as a Minecraft client does before joining a server. The access token goes to
    /// Mojang's session server only.
    async fn mojang_join(&self, access_token: &str, uuid: &str, server_id: &str) -> Result<(), Fail> {
        let endpoints = self.endpoints.as_ref().ok_or(Fail::NotConfigured)?;
        let url = endpoints.mojang.join("session/minecraft/join").map_err(|_| Fail::Invalid)?;
        // A 503 burst is asked again a few seconds apart (crate::mojangjoin); one that outlasts that waits like a 429.
        match crate::mojangjoin::join(&self.http, url, REQUEST_TIMEOUT, access_token, uuid, server_id).await {
            crate::mojangjoin::Joined::Yes => Ok(()),
            crate::mojangjoin::Joined::Busy(_) => Err(Fail::Refused("rate_limited".into())),
            crate::mojangjoin::Joined::Unreachable => Err(Fail::Offline),
            crate::mojangjoin::Joined::Refused(_) => Err(Fail::Mojang),
        }
    }

    /// A challenge from Flint's server (single use, for two minutes): its id, and the server id to join at Mojang.
    async fn challenge(&self) -> Result<(String, String), Fail> {
        let body = self.rpc("flint_signin_challenge", json!({ "p_purpose": "social" })).await?;
        let id = body["challenge"].as_str().filter(|c| c.len() == 36 && c.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')).ok_or(Fail::Invalid)?;
        let server_id = body["server_id"].as_str().filter(|s| s.len() == 40 && s.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))).ok_or(Fail::Invalid)?;
        Ok((id.to_owned(), server_id.to_owned()))
    }

    /// Fresh proof of the account at Mojang for a challenge Flint's server issued, then a Friends session for it, kept on
    /// this computer. A challenge that ran out on the way (a slow Mojang) is replaced once. Logs how long each part took.
    async fn sign_in_as(&self, mc: &dyn Minecraft, who: &Identity) -> Result<String, Fail> {
        let started = Instant::now();
        let access_token = mc.access_token(&who.uuid).await.ok_or(Fail::Account)?;
        let session_ms = started.elapsed().as_millis();
        let mut attempt = 0;
        let (body, challenge_ms, mojang_ms, signin_ms) = loop {
            let t0 = Instant::now();
            let (challenge, server_id) = self.challenge().await?;
            let t1 = Instant::now();
            self.mojang_join(&access_token, &who.uuid, &server_id).await?;
            let t2 = Instant::now();
            match self.rpc("flint_social_signin_v2", json!({ "p_uuid": who.uuid, "p_username": who.name, "p_challenge": challenge })).await {
                Err(Fail::Refused(code)) if code == "challenge" && attempt == 0 => attempt += 1,
                result => break (result?, (t1 - t0).as_millis(), (t2 - t1).as_millis(), t2.elapsed().as_millis()),
            }
        };
        drop(access_token);
        let token = body["token"].as_str().filter(|t| valid_session_token(t)).ok_or(Fail::Invalid)?;
        let me = parse_me(&body["me"]);
        if me.as_ref().is_some_and(|me| me.uuid != who.uuid) {
            // A session for someone else is never kept.
            return Err(Fail::Invalid);
        }
        let now = crate::store::now_millis() as i64;
        let expires_at = body["expires_at"].as_str().and_then(rfc3339_secs).map(|s| s * 1000).filter(|e| *e > now).unwrap_or(now + SESSION_FALLBACK_MS);
        let line = format!(
            "signed in in {} ms (Minecraft session {session_ms} ms, challenge {challenge_ms} ms, Mojang {mojang_ms} ms, sign-in {signin_ms} ms{})",
            started.elapsed().as_millis(),
            if attempt > 0 { ", a second challenge" } else { "" }
        );
        eprintln!("[flint] Friends: {line}");
        mc.note(&line);
        {
            let mut inner = self.inner.lock().unwrap();
            if inner.me_uuid() != Some(who.uuid.as_str()) {
                // The account changed while this was on its way: this session isn't the one shown.
                return Ok(token.to_owned());
            }
            inner.token = Some(token.to_owned());
            inner.expires_at = Some(expires_at);
            inner.restored = false;
            inner.signed_in_at = Some(Instant::now());
            if me.is_some() {
                inner.me = me;
            }
        }
        mc.save_session(&who.uuid, &SavedSession { token: token.to_owned(), expires_at });
        Ok(token.to_owned())
    }

    /// The session for the active account. A switch to another account starts the state over (from that account's
    /// kept view, if any) and keeps the previous account's saved session for when it comes back; no account at all ends
    /// the previous session. With no session in memory, the saved one is used as it is, without asking Mojang; one about
    /// to expire is renewed, at most once an hour, while it still works.
    async fn session(&self, mc: &dyn Minecraft) -> Result<(Identity, String), Fail> {
        if !self.configured() {
            return Err(Fail::NotConfigured);
        }
        let who = mc.account();
        let _guard = self.sign_in.lock().await;
        let ended = {
            let mut inner = self.inner.lock().unwrap();
            if inner.me_uuid() != who.as_ref().map(|w| w.uuid.as_str()) {
                let old = inner.account.as_ref().map(|a| a.uuid.clone()).zip(inner.token.take());
                *inner = Inner::for_account(who.clone());
                inner.phase = if who.is_some() { Phase::Connecting } else { Phase::SignedOut };
                if let Some(view) = who.as_ref().and_then(|w| mc.cached_view(&w.uuid)).filter(|v| v.me.as_ref().is_none_or(|me| Some(me.uuid.as_str()) == inner.me_uuid())) {
                    inner.me = view.me.clone();
                    inner.friends = view.friends.clone();
                    inner.incoming = view.incoming.clone();
                    inner.outgoing = view.outgoing.clone();
                    inner.stale = true;
                    inner.saved_view = Some(view);
                }
                // Only a sign-out of Flint ends the session; a switch keeps it saved for that account.
                old.filter(|_| who.is_none())
            } else {
                if let (Some(account), Some(who)) = (inner.account.as_mut(), who.as_ref()) {
                    account.name = who.name.clone();
                }
                None
            }
        };
        if let Some((uuid, old)) = ended {
            mc.forget_session(&uuid);
            let _ = self.rpc("flint_social_signout", json!({ "p_token": old })).await;
        }
        let who = who.ok_or(Fail::SignedOut)?;
        let (known, expires_at, renewed_at) = {
            let inner = self.inner.lock().unwrap();
            (inner.token.clone(), inner.expires_at, inner.renewed_at)
        };
        let now = crate::store::now_millis() as i64;
        let token = match known {
            Some(token) => {
                let ending = expires_at.is_some_and(|e| e - now < RENEW_BEFORE_MS);
                if ending && renewed_at.is_none_or(|at| at.elapsed() >= RENEW_RETRY) {
                    self.inner.lock().unwrap().renewed_at = Some(Instant::now());
                    // The session in hand still works: a renewal that fails changes nothing.
                    self.sign_in_as(mc, &who).await.unwrap_or(token)
                } else {
                    token
                }
            }
            None => match mc.saved_session(&who.uuid).filter(|s| valid_session_token(&s.token) && s.expires_at - now > SAVED_MIN_LEFT_MS) {
                Some(saved) => {
                    let mut inner = self.inner.lock().unwrap();
                    if inner.me_uuid() == Some(who.uuid.as_str()) {
                        inner.token = Some(saved.token.clone());
                        inner.expires_at = Some(saved.expires_at);
                        inner.restored = true;
                    }
                    saved.token
                }
                None => self.sign_in_as(mc, &who).await?,
            },
        };
        Ok((who, token))
    }

    /// An RPC with the account's session; a session the server no longer takes (expired, revoked) is forgotten here and
    /// on this computer, and Flint signs in again once.
    async fn authed(&self, mc: &dyn Minecraft, name: &str, args: impl Fn(&str) -> Value) -> Result<(Identity, Value), Fail> {
        let mut attempt = 0;
        loop {
            let (who, token) = self.session(mc).await?;
            match self.rpc(name, args(&token)).await {
                Err(Fail::Expired) if attempt == 0 => {
                    attempt += 1;
                    {
                        let mut inner = self.inner.lock().unwrap();
                        // Signed in a moment ago and already ended (more than 10 sessions elsewhere): not again yet.
                        if !inner.restored && inner.signed_in_at.is_some_and(|at| at.elapsed() < self.pacing.resign_gap) {
                            return Err(Fail::Expired);
                        }
                        if inner.token.as_deref() == Some(token.as_str()) {
                            inner.token = None;
                            inner.expires_at = None;
                            inner.restored = false;
                        }
                    }
                    mc.forget_session(&who.uuid);
                }
                Err(failure) => return Err(failure),
                Ok(body) => return Ok((who, body)),
            }
        }
    }

    /// The state from the server, merged into what was known; events for what changed since the previous sync.
    async fn sync(&self, mc: &dyn Minecraft) -> Result<Vec<SocialEvent>, Fail> {
        let after = self.inner.lock().unwrap().last_message;
        let (who, body) = self.authed(mc, "flint_social_state", |token| json!({ "p_token": token, "p_after_message": after })).await?;
        let answer = parse_state(&body).ok_or(Fail::Invalid)?;
        if answer.me.uuid != who.uuid {
            return Err(Fail::Invalid);
        }
        Ok(self.merge(&who, answer))
    }

    fn merge(&self, who: &Identity, answer: StateAnswer) -> Vec<SocialEvent> {
        let mut inner = self.inner.lock().unwrap();
        if inner.me_uuid() != Some(who.uuid.as_str()) {
            // The account changed while this was on its way.
            return Vec::new();
        }
        let mut events = Vec::new();
        if inner.synced {
            let known: HashSet<String> = inner.incoming.iter().map(|r| id_key(&r.id)).collect();
            for request in answer.incoming.iter().filter(|r| !known.contains(&id_key(&r.id))) {
                events.push(SocialEvent::Request { id: request.id.clone(), uuid: request.uuid.clone(), name: request.name.clone() });
            }
            let friends_before: HashMap<&str, &str> = inner.friends.iter().map(|f| (f.uuid.as_str(), f.status.as_str())).collect();
            let still_out: HashSet<String> = answer.outgoing.iter().map(|r| id_key(&r.id)).collect();
            for request in inner.outgoing.iter().filter(|r| !still_out.contains(&id_key(&r.id))) {
                if !friends_before.contains_key(request.uuid.as_str()) && answer.friends.iter().any(|f| f.uuid == request.uuid) {
                    events.push(SocialEvent::Accepted { uuid: request.uuid.clone(), name: request.name.clone() });
                }
            }
            for friend in &answer.friends {
                if friends_before.get(friend.uuid.as_str()) == Some(&"offline") && friend.status != "offline" {
                    events.push(SocialEvent::Online { uuid: friend.uuid.clone(), name: friend.name.clone(), status: friend.status.clone() });
                }
            }
            for message in answer.messages.iter().filter(|m| m.id > inner.last_message && m.from != who.uuid) {
                let name = answer.friends.iter().find(|f| f.uuid == message.from).map(|f| f.name.clone()).unwrap_or_default();
                events.push(SocialEvent::Message { name, message: message.clone() });
            }
        }
        inner.last_message = answer.messages.iter().map(|m| m.id).max().unwrap_or(0).max(inner.last_message);
        inner.add_messages(&answer.messages);
        // A friend removed (by either side) takes the conversation along.
        let friends: HashSet<&str> = answer.friends.iter().map(|f| f.uuid.as_str()).collect();
        inner.conversations.retain(|uuid, _| friends.contains(uuid.as_str()));
        inner.me = Some(answer.me);
        inner.friends = answer.friends;
        inner.incoming = answer.incoming;
        inner.outgoing = answer.outgoing;
        inner.synced = true;
        inner.synced_at = Some(crate::store::now_millis());
        events
    }

    /// One pass of the background task: follow the active account, sync, and say when to come back.
    pub async fn step(&self, mc: &dyn Minecraft) -> Step {
        let idle = Step { events: Vec::new(), wait: SIGNED_OUT_CHECK };
        if !self.configured() {
            return Step { wait: Duration::from_secs(3600), ..idle };
        }
        let Some(who) = mc.account() else {
            let signed_in = self.inner.lock().unwrap().account.is_some();
            if signed_in {
                // Ends the previous session and empties the state.
                let _ = self.session(mc).await;
            }
            return idle;
        };
        {
            let inner = self.inner.lock().unwrap();
            let same = inner.me_uuid() == Some(who.uuid.as_str());
            if let (true, Some(at)) = (same, inner.retry_at) {
                let now = Instant::now();
                if at > now {
                    return Step { wait: at - now, ..idle };
                }
            }
        }
        {
            let mut inner = self.inner.lock().unwrap();
            if inner.me_uuid() == Some(who.uuid.as_str()) {
                // However often it's woken (focus changes, actions), never faster than an open chat's poll.
                if let Some(since) = inner.last_sync.map(|at| at.elapsed()).filter(|since| *since < self.pacing.min_gap) {
                    return Step { wait: self.pacing.min_gap - since, ..idle };
                }
                inner.last_sync = Some(Instant::now());
                if !inner.synced && inner.phase != Phase::Error {
                    inner.phase = Phase::Connecting;
                }
            }
        }
        let started = Instant::now();
        let first = !self.inner.lock().unwrap().synced;
        let result = self.sync(mc).await;
        if first && result.is_ok() {
            // How long Friends took to be ready this run: with the kept session (no sign-in) or after signing in.
            let restored = self.inner.lock().unwrap().restored;
            mc.note(&format!("ready in {} ms ({})", started.elapsed().as_millis(), if restored { "kept session, no sign-in" } else { "including the sign-in" }));
        }
        let mut inner = self.inner.lock().unwrap();
        if inner.me_uuid() != Some(who.uuid.as_str()) {
            return Step { wait: Duration::ZERO, ..idle };
        }
        // Also for a first sync, whose sign-in started the state over.
        inner.last_sync = Some(started);
        match result {
            Ok(events) => {
                inner.phase = Phase::Ready;
                inner.offline = false;
                inner.error = None;
                inner.failures = 0;
                inner.retry_at = None;
                inner.stale = false;
                // The view kept on disk for the next start, written only when it changed.
                let view = inner.view();
                let changed = inner.saved_view.as_ref() != Some(&view);
                if changed {
                    inner.saved_view = Some(view.clone());
                }
                drop(inner);
                if changed {
                    mc.save_view(&who.uuid, &view);
                }
                Step { events, wait: self.poll_interval() }
            }
            Err(Fail::Unavailable) => {
                inner.phase = Phase::Unavailable;
                inner.offline = false;
                inner.error = None;
                inner.retry_at = Some(Instant::now() + UNAVAILABLE_RETRY);
                Step { wait: UNAVAILABLE_RETRY, ..idle }
            }
            Err(failure) => {
                inner.failures = inner.failures.saturating_add(1);
                inner.offline = failure == Fail::Offline;
                inner.error = Some(failure.code());
                // What was shown stays; a first sync that never went through is an error to explain.
                if !inner.synced {
                    inner.phase = Phase::Error;
                }
                let wait = if failure == Fail::Refused("rate_limited".into()) {
                    // Slowed down by the server: the poll interval doubles (up to a minute) until a sync goes through.
                    self.poll_interval() * 2u32.saturating_pow(inner.failures.min(5))
                } else {
                    IDLE_POLL * 2u32.saturating_pow(inner.failures.saturating_sub(1).min(4))
                };
                Step { wait: wait.min(MAX_BACKOFF), ..idle }
            }
        }
    }

    /// Signs in now if needed (the Friends page opened, or Retry): may use a Minecraft session the background task can't.
    pub async fn connect(&self, mc: &dyn Minecraft) -> Result<(), Fail> {
        self.session(mc).await.map(|_| ())
    }

    // ---------- actions ----------

    pub async fn settings(&self, mc: &dyn Minecraft, status: Option<&str>, allow_requests: Option<bool>) -> Result<Me, Fail> {
        if status.is_some_and(|s| !STATUSES.contains(&s)) {
            return Err(Fail::Refused("invalid_status".into()));
        }
        let (who, body) = self
            .authed(mc, "flint_social_settings", |token| {
                let mut args = json!({ "p_token": token });
                if let Some(status) = status {
                    args["p_status"] = json!(status);
                }
                if let Some(allow) = allow_requests {
                    args["p_allow_requests"] = json!(allow);
                }
                args
            })
            .await?;
        let me = parse_me(&body["me"]).filter(|me| me.uuid == who.uuid).ok_or(Fail::Invalid)?;
        let mut inner = self.inner.lock().unwrap();
        if inner.me_uuid() == Some(who.uuid.as_str()) {
            inner.me = Some(me.clone());
        }
        Ok(me)
    }

    pub async fn search(&self, mc: &dyn Minecraft, query: &str) -> Result<Vec<Player>, Fail> {
        let query = query.trim();
        if query.is_empty() || query.chars().count() > 16 {
            return Ok(Vec::new());
        }
        let (_, body) = self.authed(mc, "flint_social_search", |token| json!({ "p_token": token, "p_query": query })).await?;
        Ok(list(&body["players"], parse_player, 50))
    }

    /// A friend request by Minecraft name: "sent", or "accepted" when they had already asked.
    pub async fn request(&self, mc: &dyn Minecraft, name: &str) -> Result<String, Fail> {
        let name = name.trim();
        if !is_player_name(name) {
            return Err(Fail::Refused("not_on_flint".into()));
        }
        let (_, body) = self.authed(mc, "flint_friend_request", |token| json!({ "p_token": token, "p_name": name })).await?;
        let result = status_of(&body["result"], &["sent", "accepted"], "sent");
        self.wake.notify_one();
        Ok(result)
    }

    pub async fn respond(&self, mc: &dyn Minecraft, request_id: &Value, accept: bool) -> Result<(), Fail> {
        let id = id_of(request_id).ok_or(Fail::Refused("not_found".into()))?;
        self.authed(mc, "flint_friend_respond", |token| json!({ "p_token": token, "p_request_id": id, "p_accept": accept })).await?;
        self.inner.lock().unwrap().incoming.retain(|r| r.id != id);
        self.wake.notify_one();
        Ok(())
    }

    pub async fn cancel(&self, mc: &dyn Minecraft, request_id: &Value) -> Result<(), Fail> {
        let id = id_of(request_id).ok_or(Fail::Refused("not_found".into()))?;
        self.authed(mc, "flint_friend_cancel", |token| json!({ "p_token": token, "p_request_id": id })).await?;
        self.inner.lock().unwrap().outgoing.retain(|r| r.id != id);
        Ok(())
    }

    pub async fn remove(&self, mc: &dyn Minecraft, uuid: &str) -> Result<(), Fail> {
        let uuid = canonical(uuid).ok_or(Fail::Refused("not_friends".into()))?;
        self.authed(mc, "flint_friend_remove", |token| json!({ "p_token": token, "p_uuid": uuid })).await?;
        let mut inner = self.inner.lock().unwrap();
        inner.friends.retain(|f| f.uuid != uuid);
        inner.conversations.remove(&uuid);
        Ok(())
    }

    pub async fn pin(&self, mc: &dyn Minecraft, uuid: &str, pinned: bool) -> Result<(), Fail> {
        let uuid = canonical(uuid).ok_or(Fail::Refused("not_friends".into()))?;
        self.authed(mc, "flint_friend_pin", |token| json!({ "p_token": token, "p_uuid": uuid, "p_pinned": pinned })).await?;
        if let Some(friend) = self.inner.lock().unwrap().friends.iter_mut().find(|f| f.uuid == uuid) {
            friend.pinned = pinned;
        }
        Ok(())
    }

    /// Sends a message. `client_id` (a uuid the interface picks per message and keeps for its retries) makes a retry after
    /// a timeout answer the message already stored instead of storing a second copy.
    pub async fn send(&self, mc: &dyn Minecraft, to: &str, body: &str, sensitive: bool, client_id: Option<&str>) -> Result<Message, Fail> {
        let to = canonical(to).ok_or(Fail::Refused("not_friends".into()))?;
        let body = body.trim();
        if body.is_empty() {
            return Err(Fail::Refused("empty".into()));
        }
        if body.chars().count() > MAX_BODY {
            return Err(Fail::Refused("too_long".into()));
        }
        let client_id = client_id.filter(|id| id.len() == 36 && id.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')).map(str::to_ascii_lowercase);
        let (who, answer) = self
            .authed(mc, "flint_message_send", |token| {
                let mut args = json!({ "p_token": token, "p_to": to, "p_body": body, "p_sensitive": sensitive });
                if let Some(id) = &client_id {
                    args["p_client_id"] = json!(id);
                }
                args
            })
            .await?;
        let message = parse_message(&answer["message"]).filter(|m| m.from == who.uuid && m.to == to).ok_or(Fail::Invalid)?;
        let mut inner = self.inner.lock().unwrap();
        if inner.me_uuid() == Some(who.uuid.as_str()) {
            // `last_message` stays: the next sync still brings anything sent to this player before it.
            inner.add_messages(std::slice::from_ref(&message));
        }
        Ok(message)
    }

    /// Older messages with a friend (before `before`, oldest first). The latest page (no `before`) also fills the
    /// conversation, so opening a chat after a restart shows it.
    pub async fn history(&self, mc: &dyn Minecraft, with: &str, before: Option<i64>, limit: Option<u32>) -> Result<Vec<Message>, Fail> {
        let with = canonical(with).ok_or(Fail::Refused("not_friends".into()))?;
        let limit = limit.unwrap_or(50).clamp(1, 100);
        let (who, body) = self
            .authed(mc, "flint_message_history", |token| {
                let mut args = json!({ "p_token": token, "p_with": with, "p_limit": limit });
                if let Some(before) = before {
                    args["p_before"] = json!(before);
                }
                args
            })
            .await?;
        let mut messages: Vec<Message> = list(&body["messages"], parse_message, 200).into_iter().filter(|m| m.from == with || m.to == with).collect();
        messages.sort_by_key(|m| m.id);
        if before.is_none() {
            let mut inner = self.inner.lock().unwrap();
            if inner.me_uuid() == Some(who.uuid.as_str()) {
                inner.add_messages(&messages);
                inner.conversations.entry(with).or_default().loaded = true;
            }
        }
        Ok(messages)
    }

    /// Marks a friend's messages read up to `up_to`.
    pub async fn read(&self, mc: &dyn Minecraft, with: &str, up_to: i64) -> Result<(), Fail> {
        let with = canonical(with).ok_or(Fail::Refused("not_friends".into()))?;
        self.authed(mc, "flint_message_read", |token| json!({ "p_token": token, "p_with": with, "p_up_to": up_to })).await?;
        let mut inner = self.inner.lock().unwrap();
        let mut left = 0;
        if let Some(conversation) = inner.conversations.get_mut(&with) {
            for message in conversation.messages.iter_mut().filter(|m| m.from == with) {
                if message.id <= up_to {
                    message.read = true;
                } else if !message.read {
                    left += 1;
                }
            }
        }
        if let Some(friend) = inner.friends.iter_mut().find(|f| f.uuid == with) {
            friend.unread = left;
        }
        Ok(())
    }
}

// ---------- in the app ----------

/// The active Flint account, and its Minecraft session. The background task never asks the player anything: where
/// reading the saved sign-in could (the macOS Keychain), it uses only a session Flint already has this run.
struct AppMinecraft {
    app: AppHandle,
    interactive: bool,
}

/// Now as `YYYY-MM-DDTHH:MM:SSZ` (UTC), for Friends' log.
fn rfc3339_now() -> String {
    let secs = crate::core::now_secs() as i64;
    let (days, rem) = (secs.div_euclid(86_400), secs.rem_euclid(86_400));
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}

/// Where an account's Friends session is kept in the system credential store (per Supabase project, so a test build
/// pointed at staging never reads production's).
fn session_credential(state: &AppState, uuid: &str) -> String {
    format!("friends-session:{}:{uuid}", state.social.project_key())
}

fn view_file(state: &AppState, uuid: &str) -> Option<std::path::PathBuf> {
    canonical(uuid).map(|uuid| state.paths.root.join("friends").join(format!("{}-{uuid}.json", state.social.project_key())))
}

/// Forgets what Friends kept on this computer for an account (its saved session, ended at the server in the background,
/// and its view): the account's removal from Flint.
pub fn forget_account(state: &AppState, uuid: &str) {
    let Some(uuid) = canonical(uuid) else { return };
    let name = session_credential(state, &uuid);
    if let Some(saved) = state.credentials.read(&state.keyring_service, &name).ok().flatten().and_then(|raw| serde_json::from_str::<SavedSession>(&raw).ok()) {
        if let Some(request) = state.social.signout_request(&saved.token) {
            tauri::async_runtime::spawn(async move {
                let _ = request.send().await;
            });
        }
    }
    if state.credentials.delete(&state.keyring_service, &name).is_err() {
        eprintln!("[flint] Friends: couldn't remove a saved session from the system credential store");
    }
    if let Some(file) = view_file(state, &uuid) {
        let _ = std::fs::remove_file(file);
    }
}

impl Minecraft for AppMinecraft {
    fn account(&self) -> Option<Identity> {
        let state = self.app.state::<AppState>();
        let account = crate::auth::active_account(&state).filter(|a| !a.qa)?;
        Some(Identity { uuid: canonical(&account.uuid)?, name: account.username })
    }

    fn saved_session(&self, uuid: &str) -> Option<SavedSession> {
        // macOS may ask the player before the Keychain answers: the background task reads it only once the player has
        // opened Friends this run (an interactive call); elsewhere the store never asks.
        if cfg!(target_os = "macos") && !self.interactive {
            return None;
        }
        let state = self.app.state::<AppState>();
        let raw = state.credentials.read(&state.keyring_service, &session_credential(&state, uuid)).ok().flatten()?;
        serde_json::from_str::<SavedSession>(&raw).ok()
    }

    fn save_session(&self, uuid: &str, session: &SavedSession) {
        let state = self.app.state::<AppState>();
        let secret = serde_json::to_string(session).expect("a session serializes");
        if state.credentials.write(&state.keyring_service, &session_credential(&state, uuid), &secret).is_err() {
            eprintln!("[flint] Friends: the session is kept for this run only: the system credential store refused it");
        }
    }

    fn forget_session(&self, uuid: &str) {
        let state = self.app.state::<AppState>();
        let _ = state.credentials.delete(&state.keyring_service, &session_credential(&state, uuid));
    }

    fn cached_view(&self, uuid: &str) -> Option<CachedView> {
        let state = self.app.state::<AppState>();
        let text = std::fs::read_to_string(view_file(&state, uuid)?).ok()?;
        serde_json::from_str(&text).ok()
    }

    fn note(&self, line: &str) {
        use std::io::Write;
        let state = self.app.state::<AppState>();
        let dir = state.paths.root.join("logs");
        let file = dir.join("friends.log");
        let _ = std::fs::create_dir_all(&dir);
        // Kept small: past 256 KB the older half goes.
        if std::fs::metadata(&file).is_ok_and(|m| m.len() > 256 * 1024) {
            if let Ok(text) = std::fs::read_to_string(&file) {
                let keep = text.len() / 2;
                let cut = text[keep..].find('\n').map_or(keep, |n| keep + n + 1);
                let _ = std::fs::write(&file, &text[cut..]);
            }
        }
        if let Ok(mut out) = std::fs::OpenOptions::new().create(true).append(true).open(&file) {
            let _ = writeln!(out, "{} {line}", rfc3339_now());
        }
    }

    fn save_view(&self, uuid: &str, view: &CachedView) {
        let state = self.app.state::<AppState>();
        let Some(file) = view_file(&state, uuid) else { return };
        if let Some(dir) = file.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        if let Ok(bytes) = serde_json::to_vec(view) {
            let _ = crate::core::write_atomic(&file, &bytes);
        }
    }

    fn access_token<'a>(&'a self, uuid: &'a str) -> TokenFuture<'a> {
        Box::pin(async move {
            let state = self.app.state::<AppState>();
            let token = if self.interactive {
                crate::auth::minecraft_token(&self.app, &state, uuid).await
            } else {
                crate::auth::minecraft_token_quiet(&self.app, &state, uuid).await
            };
            token.ok()
        })
    }
}

fn publish(app: &AppHandle, social: &Social) {
    let _ = app.emit("social:update", social.snapshot());
}

/// Starts the background task (once, at start-up). Signed out it only looks at Flint's account list.
pub fn start(app: &AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let mc = AppMinecraft { app: app.clone(), interactive: false };
        let state = app.state::<AppState>();
        let social = &state.social;
        let mut last: Option<Snapshot> = None;
        loop {
            let step = social.step(&mc).await;
            for event in &step.events {
                let _ = app.emit("social:event", event);
            }
            let snapshot = social.snapshot();
            if last.as_ref().is_none_or(|l| !l.same_as(&snapshot)) {
                let _ = app.emit("social:update", &snapshot);
                last = Some(snapshot);
            }
            tokio::select! {
                _ = tokio::time::sleep(step.wait) => {}
                _ = social.wake.notified() => {}
            }
        }
    });
}

fn interactive(app: &AppHandle) -> AppMinecraft {
    AppMinecraft { app: app.clone(), interactive: true }
}

#[tauri::command]
pub fn social_snapshot(state: State<'_, AppState>) -> Snapshot {
    state.social.snapshot()
}

/// The chat open on the Friends page, or none: polling is every 4 s while one is.
#[tauri::command]
pub fn social_focus(state: State<'_, AppState>, chat: Option<String>) {
    let opened = chat.is_some();
    if state.social.set_focus(chat) && opened {
        state.social.wake.notify_one();
    }
}

/// The Friends page opened (`force`: Retry): signs in now if needed, then syncs.
#[tauri::command]
pub async fn social_refresh(app: AppHandle, state: State<'_, AppState>, force: Option<bool>) -> CmdResult<()> {
    let social = &state.social;
    let waiting = social.snapshot().phase == Phase::Unavailable;
    if force.unwrap_or(false) {
        social.retry_now();
    } else if waiting {
        return Ok(());
    }
    let result = social.connect(&interactive(&app)).await;
    social.wake.notify_one();
    match result {
        Ok(()) | Err(Fail::SignedOut | Fail::NotConfigured | Fail::Unavailable | Fail::Offline) => Ok(()),
        Err(failure) => Err(failure.code()),
    }
}

#[tauri::command]
pub async fn social_settings(app: AppHandle, state: State<'_, AppState>, status: Option<String>, allow_requests: Option<bool>) -> CmdResult<Me> {
    let me = state.social.settings(&interactive(&app), status.as_deref(), allow_requests).await.map_err(|f| f.code())?;
    publish(&app, &state.social);
    Ok(me)
}

#[tauri::command]
pub async fn social_search(app: AppHandle, state: State<'_, AppState>, query: String) -> CmdResult<Vec<Player>> {
    state.social.search(&interactive(&app), &query).await.map_err(|f| f.code())
}

#[tauri::command]
pub async fn social_request(app: AppHandle, state: State<'_, AppState>, name: String) -> CmdResult<String> {
    state.social.request(&interactive(&app), &name).await.map_err(|f| f.code())
}

#[tauri::command]
pub async fn social_respond(app: AppHandle, state: State<'_, AppState>, request_id: Value, accept: bool) -> CmdResult<()> {
    state.social.respond(&interactive(&app), &request_id, accept).await.map_err(|f| f.code())?;
    publish(&app, &state.social);
    Ok(())
}

#[tauri::command]
pub async fn social_cancel(app: AppHandle, state: State<'_, AppState>, request_id: Value) -> CmdResult<()> {
    state.social.cancel(&interactive(&app), &request_id).await.map_err(|f| f.code())?;
    publish(&app, &state.social);
    Ok(())
}

#[tauri::command]
pub async fn social_remove(app: AppHandle, state: State<'_, AppState>, uuid: String) -> CmdResult<()> {
    state.social.remove(&interactive(&app), &uuid).await.map_err(|f| f.code())?;
    publish(&app, &state.social);
    Ok(())
}

#[tauri::command]
pub async fn social_pin(app: AppHandle, state: State<'_, AppState>, uuid: String, pinned: bool) -> CmdResult<()> {
    state.social.pin(&interactive(&app), &uuid, pinned).await.map_err(|f| f.code())?;
    publish(&app, &state.social);
    Ok(())
}

#[tauri::command]
pub async fn social_send(app: AppHandle, state: State<'_, AppState>, to: String, body: String, sensitive: bool, client_id: Option<String>) -> CmdResult<Message> {
    let message = state.social.send(&interactive(&app), &to, &body, sensitive, client_id.as_deref()).await.map_err(|f| f.code())?;
    publish(&app, &state.social);
    Ok(message)
}

#[tauri::command]
pub async fn social_history(app: AppHandle, state: State<'_, AppState>, with: String, before: Option<i64>, limit: Option<u32>) -> CmdResult<Vec<Message>> {
    let messages = state.social.history(&interactive(&app), &with, before, limit).await.map_err(|f| f.code())?;
    if before.is_none() {
        publish(&app, &state.social);
    }
    Ok(messages)
}

#[tauri::command]
pub async fn social_read(app: AppHandle, state: State<'_, AppState>, with: String, up_to: i64) -> CmdResult<()> {
    state.social.read(&interactive(&app), &with, up_to).await.map_err(|f| f.code())?;
    publish(&app, &state.social);
    Ok(())
}
