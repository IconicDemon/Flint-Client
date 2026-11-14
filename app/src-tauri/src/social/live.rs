//! Friends live: the Flint Store's push socket, so a message, a friend request or an accepted request reaches the other
//! player as it happens, as in Discord, instead of at the next sync (up to 4 s later in an open chat, 15 s elsewhere).
//!
//! - **The socket** (a Cloudflare Durable Object on the Store Worker, one hub per player):
//!   `wss://<store>/api/v1/social/live/?uuid=<the account>`. Flint opens it once Friends has a session and has synced, sends
//!   `{"type":"auth","token":…}` as its first frame (the token never goes in the URL), and is live when the hub answers
//!   `{"type":"ready","uuid":…}` for that account. It sends exactly `{"type":"ping"}` every 25 s (Cloudflare answers
//!   `{"type":"pong"}` without waking the hub); nothing heard within 10 s of a ping ends the connection (a laptop that
//!   slept, a Wi-Fi change).
//! - **Coming back**: a connection that fails or drops is opened again after 1 s, then 2, 4… up to a minute (each ±20%);
//!   the hub's own answers say more ([`closed`], [`open`]): a session it refuses (close 4001) isn't offered again for 5
//!   minutes (the sync checks the session and signs in again if the server ended it, and a new session is tried at once);
//!   "too much" (close 4429, HTTP 429) waits at least a minute (or its Retry-After), its database being unreachable (close
//!   1013, HTTP 503) at least 15 s; what can only be a bug on this side (close 4400 or 1009, HTTP 400) and a Store without
//!   the socket (404) are asked again after 10 minutes, never in a loop. No auth in time (4008) is retried as a drop.
//! - **What it says** ([`LiveEvent`]): `message`, `read`, `request`, `request_gone`, `friend_added`, `friend_removed`,
//!   `presence`, `typing` and `resync`, applied to the state at once ([`Social::apply_live`]) and announced as the sync
//!   announces them (`social:event`). Messages are kept by id, so a push and the next sync never show one twice or lose
//!   one; anything the push can't place (a message from someone not on the list yet) asks for a sync instead.
//! - **Polling stays** as the safety net: while live, a sync every 10 s with a chat open (players still on 1.0.3 write
//!   straight to the RPCs, which push nothing) and every 60 s otherwise (it is also the presence heartbeat, which the server
//!   keeps for 75 s); while not live, today's 4 s and 15 s. A sync runs as soon as the socket is live again, for what
//!   happened while it was down.
//! - **Writes** ([`Social::write`]) go to the Store's `POST /api/v1/social/<action>/` with the RPC's own body, so the Store
//!   can tell the other player; its answer is the RPC's. A Store without those routes (404, or anything that isn't an RPC
//!   answer) or one that can't be reached gets the RPC directly, as before 1.0.4, and is skipped for a while.
//! - The socket is Flint's own TCP and TLS (rustls with ring and the webpki roots, as reqwest's) with tungstenite's
//!   framing; frames are at most 64 KiB. The token and message text never appear in logs.
use super::*;
use futures_util::SinkExt;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, watch};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_tungstenite::tungstenite::protocol::{CloseFrame, WebSocketConfig};
use tokio_tungstenite::tungstenite::{self, Message as Frame};
use tokio_tungstenite::WebSocketStream;

pub const LIVE_PATH: &str = "api/v1/social/live/";
/// While live: a chat is open (messages from players on 1.0.3 aren't pushed).
pub const LIVE_CHAT_POLL: Duration = Duration::from_secs(10);
/// While live, otherwise: also the presence heartbeat (the server shows a status for 75 s after the last sync).
pub const LIVE_IDLE_POLL: Duration = Duration::from_secs(60);
/// A sync the socket asks for (it is live again, or said `resync`) waits at most this after the previous one.
pub const URGENT_GAP: Duration = Duration::from_secs(1);
/// How long "typing…" shows after a friend's typing note.
pub const TYPING_SHOWN: Duration = Duration::from_secs(5);
/// At most one typing note per friend this often (the hub's limit).
pub const TYPING_EVERY: Duration = Duration::from_secs(3);
/// A game's open chat counts as open this long after it last asked (a game asks again within 20 s).
pub const GAME_CHAT_HOLD: Duration = Duration::from_secs(30);
/// The client's ping, exactly (Cloudflare answers this text itself).
pub const PING: &str = r#"{"type":"ping"}"#;
const MAX_FRAME: usize = 64 * 1024;
/// An outgoing request that went away is remembered this long, so a `friend_added` right after says "accepted".
const REMEMBER_GONE: Duration = Duration::from_secs(120);
/// The Store has no write routes (yet): the RPCs directly for this long.
const ROUTES_MISSING_FOR: Duration = Duration::from_secs(600);
/// The Store couldn't be reached or failed: the RPCs directly for this long.
const ROUTES_DOWN_FOR: Duration = Duration::from_secs(60);

/// The socket's timings (tests shorten them).
#[derive(Clone, Copy, Debug)]
pub struct LiveTiming {
    pub connect: Duration,
    /// For `ready` after the auth frame.
    pub ready: Duration,
    pub ping: Duration,
    /// Something must be heard this soon after a ping.
    pub pong: Duration,
    pub first_retry: Duration,
    pub max_retry: Duration,
    /// A Store without the socket is asked again after this.
    pub missing_retry: Duration,
    /// A session the hub refused isn't offered again before this.
    pub refused_retry: Duration,
    /// The least wait after the hub said "too much" (close 4429, or 429 without a Retry-After).
    pub busy_retry: Duration,
    /// The least wait after the hub couldn't reach Flint's database (close 1013, or 503).
    pub down_retry: Duration,
    /// Retries are spread ±20% (so every Flint doesn't come back in the same second after an outage).
    pub jitter: bool,
}

pub const TIMING: LiveTiming = LiveTiming {
    connect: Duration::from_secs(10),
    ready: Duration::from_secs(10),
    ping: Duration::from_secs(25),
    pong: Duration::from_secs(10),
    first_retry: Duration::from_secs(1),
    max_retry: Duration::from_secs(60),
    missing_retry: Duration::from_secs(600),
    refused_retry: Duration::from_secs(300),
    busy_retry: Duration::from_secs(60),
    down_retry: Duration::from_secs(15),
    jitter: true,
};

/// The wait before the `attempt`th try in a row (1: the first after a failure): 1 s, 2 s, 4 s… up to `max_retry`.
pub fn backoff(timing: &LiveTiming, attempt: u32) -> Duration {
    let factor = 2u32.saturating_pow(attempt.saturating_sub(1).min(20));
    timing.first_retry.saturating_mul(factor).min(timing.max_retry)
}

fn jittered(wait: Duration, on: bool) -> Duration {
    if !on || wait.is_zero() {
        return wait;
    }
    use rand::Rng;
    wait.mul_f64(rand::thread_rng().gen_range(0.8..1.2))
}

// ---------- what the hub says ----------

/// One frame from the hub, checked as the RPC answers are. Unknown types are [`LiveEvent::Other`] (a newer server).
#[derive(Debug, Clone, PartialEq)]
pub enum LiveEvent {
    Ready { uuid: String },
    Pong,
    /// A message to or from the account (from its other connections too).
    Message(Message),
    /// Messages up to `up_to` between the account and `with` were read: by the account (another of its connections) unless
    /// `by` names `with` (a read receipt).
    Read { with: String, up_to: i64, by: Option<String> },
    /// A friend request: to the account, or from it (another of its connections) when `outgoing`.
    Request { request: FriendRequest, outgoing: bool },
    RequestGone { id: Value },
    FriendAdded(Friend),
    FriendRemoved { uuid: String },
    /// `last_seen`: None when the hub didn't say; `Some(None)` when it says it isn't shown.
    Presence { uuid: String, status: String, in_game: Option<bool>, last_seen: Option<Option<i64>> },
    Typing { uuid: String },
    /// Sync now.
    Resync,
    Other,
}

fn number_of(value: &Value) -> Option<i64> {
    value.as_i64().or_else(|| value.as_str().and_then(|s| s.parse().ok()))
}

/// A frame's event; None for anything that isn't JSON with a `type`, or a known type with fields that don't check out.
pub fn parse_event(text: &str) -> Option<LiveEvent> {
    let value: Value = serde_json::from_str(text).ok()?;
    Some(match value["type"].as_str()? {
        "ready" => LiveEvent::Ready { uuid: uuid_of(&value["uuid"])? },
        "pong" => LiveEvent::Pong,
        "message" => LiveEvent::Message(parse_message(&value["message"])?),
        "read" => LiveEvent::Read { with: uuid_of(&value["with"])?, up_to: number_of(&value["up_to"])?, by: uuid_of(&value["by"]) },
        "request" => LiveEvent::Request {
            request: parse_request(&value["request"])?,
            outgoing: value["direction"] == "outgoing" || value["request"]["direction"] == "outgoing",
        },
        "request_gone" => LiveEvent::RequestGone { id: id_of(&value["id"])? },
        "friend_added" => LiveEvent::FriendAdded(parse_friend(&value["friend"])?),
        "friend_removed" => LiveEvent::FriendRemoved { uuid: uuid_of(&value["uuid"])? },
        "presence" => LiveEvent::Presence {
            uuid: uuid_of(&value["uuid"])?,
            status: status_of(&value["status"], &["online", "busy", "away", "offline"], "offline"),
            in_game: value["in_game"].as_bool(),
            last_seen: value.get("last_seen").map(millis),
        },
        "typing" => LiveEvent::Typing { uuid: uuid_of(&value["uuid"])? },
        "resync" => LiveEvent::Resync,
        _ => LiveEvent::Other,
    })
}

/// A UUID as the hub writes them (8-4-4-4-12).
pub fn dashed(uuid: &str) -> String {
    let h = uuid.replace('-', "");
    if h.len() != 32 {
        return uuid.to_owned();
    }
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

pub fn auth_frame(token: &str) -> String {
    json!({ "type": "auth", "token": token }).to_string()
}

pub fn typing_frame(to: &str) -> String {
    json!({ "type": "typing", "to": dashed(to) }).to_string()
}

/// What a push changed.
#[derive(Debug, Default, PartialEq)]
pub struct Applied {
    pub events: Vec<SocialEvent>,
    /// What Friends shows changed (the snapshot goes out).
    pub changed: bool,
    /// The push couldn't be placed alone: sync now.
    pub resync: bool,
}

// ---------- what the socket shares with the rest of Friends ----------

/// Where news goes: the app's window (and, through the revision, its games); a test's recorder.
pub trait Out: Send + Sync {
    fn event(&self, event: &SocialEvent);
    fn update(&self, snapshot: &Snapshot);
}

pub struct Live {
    /// The Store (its socket and write routes); none: the RPCs only, and polling.
    pub(crate) store: Option<crate::store::Endpoints>,
    up: AtomicBool,
    /// The next sync may come sooner than the pacing (the socket is live again, or said `resync`).
    pub(crate) urgent: AtomicBool,
    /// Has the socket look at the session again (after each sync step).
    pub(crate) kick: Notify,
    /// Changes whenever what Friends shows changes: a game's `friends` waits on it.
    revision: watch::Sender<u64>,
    typing_out: mpsc::Sender<String>,
    typing_in: Mutex<Option<mpsc::Receiver<String>>>,
    typing_sent: Mutex<HashMap<String, Instant>>,
    /// Writes skip the Store until then.
    routes_off_until: Mutex<Option<Instant>>,
    /// The chat a game has open, and when it last asked.
    pub(crate) game_chat: Mutex<Option<(String, Instant)>>,
}

impl Live {
    pub fn new(store: Option<crate::store::Endpoints>) -> Self {
        let (typing_out, typing_in) = mpsc::channel(8);
        Self {
            store,
            up: AtomicBool::new(false),
            urgent: AtomicBool::new(false),
            kick: Notify::new(),
            revision: watch::Sender::new(1),
            typing_out,
            typing_in: Mutex::new(Some(typing_in)),
            typing_sent: Mutex::default(),
            routes_off_until: Mutex::new(None),
            game_chat: Mutex::new(None),
        }
    }
}

/// Who the socket is for: the account and its session token.
#[derive(Clone, PartialEq)]
pub struct Target {
    pub uuid: String,
    pub token: String,
}

impl std::fmt::Debug for Target {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Target").field("uuid", &self.uuid).field("token", &"<hidden>").finish()
    }
}

/// The Store's route for an RPC that writes, if it has one.
pub fn store_action(rpc: &str) -> Option<&'static str> {
    Some(match rpc {
        "flint_message_send" => "message_send",
        "flint_message_read" => "message_read",
        "flint_friend_request" => "friend_request",
        "flint_friend_respond" => "friend_respond",
        "flint_friend_cancel" => "friend_cancel",
        "flint_friend_remove" => "friend_remove",
        "flint_social_settings" => "settings",
        _ => return None,
    })
}

/// What a Store write route's answer means.
#[derive(Debug, PartialEq)]
pub enum StoreSaid {
    /// The RPC's own answer (read as the RPC's).
    Answer,
    /// No such route (404, a page that isn't an RPC answer): the RPC directly, and the Store skipped for 10 minutes.
    Missing,
    /// The Store failed: the RPC directly, and the Store skipped for a minute.
    Down,
}

/// The Store answers the RPC's own answer with 200; its own refusals are shaped like the RPC's: `rate_limited` (429),
/// `invalid` (400), `too_long` (413). Anything else is the RPC's to answer: 404 (no such action, or PostgREST's PGRST202
/// from a database without Friends live), 503 (`unavailable`: the Worker isn't set up, or the database didn't answer), and
/// any answer that isn't an RPC's (a page).
pub fn store_said(status: u16, body: &Value) -> StoreSaid {
    let rpc_shaped = body.is_object() && body["ok"].is_boolean();
    match status {
        429 => StoreSaid::Answer,
        500..=599 => StoreSaid::Down,
        200..=299 | 400 | 401 | 403 | 413 if rpc_shaped => StoreSaid::Answer,
        _ => StoreSaid::Missing,
    }
}

impl Social {
    /// The Store Friends pushes through (its socket and write routes).
    pub fn with_store(mut self, store: crate::store::Endpoints) -> Self {
        self.live.store = Some(store);
        self
    }

    pub fn live_up(&self) -> bool {
        self.live.up.load(Ordering::Relaxed)
    }

    /// What Friends shows changed: a game waiting on it hears now.
    pub fn changed(&self) {
        self.live.revision.send_modify(|r| *r = r.wrapping_add(1));
    }

    pub fn revision(&self) -> u64 {
        *self.live.revision.borrow()
    }

    /// Returns once the revision is no longer `since`, or after `wait`.
    pub async fn changed_since(&self, since: u64, wait: Duration) {
        let mut rx = self.live.revision.subscribe();
        let _ = tokio::time::timeout(wait, rx.wait_for(|r| *r != since)).await;
    }

    /// A sync now (at most one a second): the socket is live again, or asked for it.
    pub fn sync_soon(&self) {
        self.live.urgent.store(true, Ordering::Relaxed);
        self.wake.notify_one();
    }

    /// A game asked with `chat` open (or none). True when that chat just opened there (the sync is woken for it).
    pub fn game_chat(&self, chat: Option<&str>) -> bool {
        let mut held = self.live.game_chat.lock().unwrap();
        match chat {
            Some(chat) => {
                let fresh = held.as_ref().is_none_or(|(c, at)| c != chat || at.elapsed() >= GAME_CHAT_HOLD);
                *held = Some((chat.to_owned(), Instant::now()));
                fresh
            }
            None => {
                *held = None;
                false
            }
        }
    }

    pub(crate) fn chat_open(&self) -> bool {
        self.focus.lock().unwrap().is_some() || self.live.game_chat.lock().unwrap().as_ref().is_some_and(|(_, at)| at.elapsed() < GAME_CHAT_HOLD)
    }

    /// The account and session the socket opens for: only once a sync went through for it.
    pub fn live_target(&self) -> Option<Target> {
        self.live.store.as_ref()?;
        let inner = self.inner.lock().unwrap();
        if !inner.synced || inner.phase != Phase::Ready {
            return None;
        }
        Some(Target { uuid: inner.account.as_ref()?.uuid.clone(), token: inner.token.clone()? })
    }

    fn set_live(&self, up: bool, out: &dyn Out) {
        if self.live.up.swap(up, Ordering::Relaxed) == up {
            return;
        }
        if up {
            // What happened while it was down.
            self.sync_soon();
        } else {
            self.inner.lock().unwrap().typing.clear();
            // Polling speeds up again.
            self.wake.notify_one();
        }
        self.publish_to(out);
    }

    pub(crate) fn publish_to(&self, out: &dyn Out) {
        out.update(&self.snapshot());
        self.changed();
    }

    /// Tells a friend the player is typing to them: only while live, to a friend, at most once every 3 s each.
    pub fn typing(&self, to: &str) -> bool {
        let Some(to) = canonical(to) else { return false };
        if !self.live_up() || !self.inner.lock().unwrap().friends.iter().any(|f| f.uuid == to) {
            return false;
        }
        let mut sent = self.live.typing_sent.lock().unwrap();
        if sent.get(&to).is_some_and(|at| at.elapsed() < TYPING_EVERY) {
            return false;
        }
        sent.retain(|_, at| at.elapsed() < TYPING_EVERY);
        sent.insert(to.clone(), Instant::now());
        self.live.typing_out.try_send(to).is_ok()
    }

    /// When the first "typing…" shown runs out.
    fn typing_ends(&self) -> Option<Instant> {
        self.inner.lock().unwrap().typing.values().min().copied()
    }

    fn expire_typing(&self) -> bool {
        let now = Instant::now();
        let mut inner = self.inner.lock().unwrap();
        let before = inner.typing.len();
        inner.typing.retain(|_, until| *until > now);
        inner.typing.len() != before
    }

    // ---------- writes through the Store ----------

    /// An RPC that writes, through the Store when it has the route (so it tells the other player at once), else directly.
    pub(crate) async fn write(&self, name: &str, args: Value) -> Result<Value, Fail> {
        if let Some(answer) = self.through_store(name, &args).await {
            return answer;
        }
        self.rpc(name, args).await
    }

    fn routes_off(&self, how_long: Duration) {
        *self.live.routes_off_until.lock().unwrap() = Some(Instant::now() + how_long);
    }

    /// None: the Store doesn't take this one now (the RPC is called instead).
    async fn through_store(&self, name: &str, args: &Value) -> Option<Result<Value, Fail>> {
        let action = store_action(name)?;
        let store = self.live.store.as_ref()?;
        if self.live.routes_off_until.lock().unwrap().is_some_and(|until| Instant::now() < until) {
            return None;
        }
        let request = crate::store::with_access(self.http.post(store.api(&format!("api/v1/social/{action}/"))), store)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .json(args);
        let response = match request.send().await {
            Ok(response) => response,
            // Never reached the Store: nothing was done there.
            Err(e) if e.is_connect() => {
                self.routes_off(ROUTES_DOWN_FOR);
                return None;
            }
            // Sent, and no answer in time: it may have been done, so it isn't sent again.
            Err(_) => return Some(Err(Fail::Offline)),
        };
        let status = response.status().as_u16();
        let body: Value = match read_capped(response).await {
            Ok(bytes) => serde_json::from_slice(&bytes).unwrap_or(Value::Null),
            Err(failure) => return Some(Err(failure)),
        };
        match store_said(status, &body) {
            StoreSaid::Answer => Some(answer_of(name, if status == 429 { 429 } else { 200 }, body)),
            StoreSaid::Missing => {
                self.routes_off(ROUTES_MISSING_FOR);
                None
            }
            StoreSaid::Down => {
                self.routes_off(ROUTES_DOWN_FOR);
                None
            }
        }
    }

    // ---------- applying a push ----------

    /// Applies one pushed event to the state: what changed, the events to announce, and whether a sync should follow.
    pub fn apply_live(&self, event: LiveEvent) -> Applied {
        let mut inner = self.inner.lock().unwrap();
        if inner.synced {
            // A sync on its way may answer with the state from before this: applied again over its answer.
            if let Some(journal) = inner.journal.as_mut() {
                journal.push(event.clone());
            }
        }
        inner.apply(event)
    }
}

impl Inner {
    /// [`Social::apply_live`] on the state itself (also how a sync's answer gets the pushes it may predate).
    pub(crate) fn apply(&mut self, event: LiveEvent) -> Applied {
        let mut out = Applied::default();
        let inner = self;
        let Some(me) = inner.me_uuid().map(str::to_owned) else { return out };
        if !inner.synced {
            out.resync = true;
            return out;
        }
        let now = Instant::now();
        inner.gone_outgoing.retain(|_, (_, at)| now.duration_since(*at) < REMEMBER_GONE);
        match event {
            LiveEvent::Message(message) => {
                if message.from != me && message.to != me {
                    return out;
                }
                let other = if message.from == me { message.to.clone() } else { message.from.clone() };
                let Some(at) = inner.friends.iter().position(|f| f.uuid == other) else {
                    // Not on the list (yet): the sync brings both.
                    out.resync = true;
                    return out;
                };
                let known = inner.conversations.get(&other).is_some_and(|c| c.messages.binary_search_by_key(&message.id, |m| m.id).is_ok());
                if known {
                    return out;
                }
                // Older than what the syncs went through (and not kept): placed, but not news.
                let news = message.id > inner.last_message && message.from == other;
                if message.from == other {
                    inner.typing.remove(&other);
                }
                if news {
                    if !message.read {
                        inner.friends[at].unread = inner.friends[at].unread.saturating_add(1);
                    }
                    out.events.push(SocialEvent::Message { name: inner.friends[at].name.clone(), message: message.clone() });
                }
                inner.add_messages(std::slice::from_ref(&message));
                out.changed = true;
            }
            LiveEvent::Read { with, up_to, by } => {
                let receipt = by.as_deref() == Some(with.as_str());
                let mut left = 0;
                if let Some(conversation) = inner.conversations.get_mut(&with) {
                    for message in conversation.messages.iter_mut() {
                        let theirs = message.from == with;
                        if theirs == receipt {
                            continue;
                        }
                        if message.id <= up_to {
                            if !message.read {
                                message.read = true;
                                out.changed = true;
                            }
                        } else if theirs && !message.read {
                            left += 1;
                        }
                    }
                }
                if !receipt {
                    if let Some(friend) = inner.friends.iter_mut().find(|f| f.uuid == with && f.unread != left) {
                        friend.unread = left;
                        out.changed = true;
                    }
                }
            }
            LiveEvent::Request { request, outgoing } => {
                if request.uuid == me || inner.friends.iter().any(|f| f.uuid == request.uuid) {
                    out.resync = true;
                    return out;
                }
                let key = id_key(&request.id);
                let list = if outgoing { &mut inner.outgoing } else { &mut inner.incoming };
                if list.iter().any(|r| id_key(&r.id) == key) {
                    return out;
                }
                // Newest first, as the server lists them.
                list.insert(0, request.clone());
                out.changed = true;
                if !outgoing {
                    out.events.push(SocialEvent::Request { id: request.id, uuid: request.uuid, name: request.name });
                }
            }
            LiveEvent::RequestGone { id } => {
                let key = id_key(&id);
                let before = inner.incoming.len();
                inner.incoming.retain(|r| id_key(&r.id) != key);
                out.changed = inner.incoming.len() != before;
                if let Some(at) = inner.outgoing.iter().position(|r| id_key(&r.id) == key) {
                    let gone = inner.outgoing.remove(at);
                    inner.gone_outgoing.insert(gone.uuid, (gone.name, now));
                    out.changed = true;
                }
            }
            LiveEvent::FriendAdded(friend) => {
                if friend.uuid == me {
                    return out;
                }
                let asked = inner.outgoing.iter().any(|r| r.uuid == friend.uuid) | inner.gone_outgoing.remove(&friend.uuid).is_some();
                inner.incoming.retain(|r| r.uuid != friend.uuid);
                inner.outgoing.retain(|r| r.uuid != friend.uuid);
                match inner.friends.iter().position(|f| f.uuid == friend.uuid) {
                    Some(at) => inner.friends[at] = friend,
                    None => {
                        if asked {
                            out.events.push(SocialEvent::Accepted { uuid: friend.uuid.clone(), name: friend.name.clone() });
                        }
                        inner.friends.push(friend);
                    }
                }
                out.changed = true;
            }
            LiveEvent::FriendRemoved { uuid } => {
                let before = inner.friends.len();
                inner.friends.retain(|f| f.uuid != uuid);
                inner.conversations.remove(&uuid);
                inner.typing.remove(&uuid);
                out.changed = inner.friends.len() != before;
            }
            LiveEvent::Presence { uuid, status, in_game, last_seen } => {
                let Some(friend) = inner.friends.iter_mut().find(|f| f.uuid == uuid) else { return out };
                let was_offline = friend.status == "offline";
                let in_game = in_game.unwrap_or(friend.in_game) && status != "offline";
                let last_seen = last_seen.unwrap_or(friend.last_seen);
                if friend.status != status || friend.in_game != in_game || friend.last_seen != last_seen {
                    out.changed = true;
                }
                friend.status = status;
                friend.in_game = in_game;
                friend.last_seen = last_seen;
                if was_offline && friend.status != "offline" {
                    out.events.push(SocialEvent::Online { uuid: friend.uuid.clone(), name: friend.name.clone(), status: friend.status.clone() });
                }
            }
            LiveEvent::Typing { uuid } => {
                if uuid != me && inner.friends.iter().any(|f| f.uuid == uuid) {
                    inner.typing.insert(uuid, now + TYPING_SHOWN);
                    out.changed = true;
                }
            }
            LiveEvent::Resync => out.resync = true,
            LiveEvent::Ready { .. } | LiveEvent::Pong | LiveEvent::Other => {}
        }
        out
    }
}

impl Social {
    /// Announces what a push changed, and asks for the sync it needs.
    pub(crate) fn deliver(&self, applied: Applied, out: &dyn Out) {
        for event in &applied.events {
            out.event(event);
        }
        if applied.changed {
            self.publish_to(out);
        }
        if applied.resync {
            self.sync_soon();
        }
    }
}

// ---------- the connection ----------

pub(crate) trait Io: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Io for T {}
pub(crate) type Socket = WebSocketStream<Box<dyn Io>>;

/// How a connection ended.
#[derive(Debug, PartialEq)]
pub enum Ended {
    /// The hub refused this session (close 4001, or a `ready` for another player).
    Refused,
    /// No socket at this Store (404 on the upgrade), or one that can only be a bug here (HTTP 400, close 4400 or 1009):
    /// asked again in 10 minutes.
    Missing,
    /// It failed or dropped (network, the hub restarting, no pong, no auth in time); `was_live`: it had been live.
    Dropped { was_live: bool },
    /// "Too much" (close 4429, HTTP 429): at least `retry_after` (or the timing's `busy_retry`) before the next try.
    Busy { retry_after: Option<Duration>, was_live: bool },
    /// The hub couldn't reach Flint's database (close 1013, HTTP 503): at least the timing's `down_retry`.
    Down { was_live: bool },
    /// The account or its session changed: opened again at once.
    Switched,
    /// Friends has no session any more.
    Gone,
}

/// The TLS the HTTP clients use (core.rs `tls_config`: rustls with ring and the webpki roots, HTTP/1.1, a websocket
/// upgrade).
fn tls() -> Arc<rustls::ClientConfig> {
    static CONFIG: OnceLock<Arc<rustls::ClientConfig>> = OnceLock::new();
    CONFIG.get_or_init(|| Arc::new(crate::core::tls_config())).clone()
}

/// The socket's address for `uuid` at `store`: wss (ws only on this computer, as the Store's own address allows).
pub fn live_url(store: &crate::store::Endpoints, uuid: &str) -> Option<Url> {
    let mut url = store.api(LIVE_PATH);
    let scheme = if url.scheme() == "https" { "wss" } else { "ws" };
    url.set_scheme(scheme).ok()?;
    url.query_pairs_mut().append_pair("uuid", &dashed(uuid));
    Some(url)
}

/// Opens the socket at `url` (the TCP, TLS and websocket upgrade), frames at most `max_frame` bytes; also the emote room's.
pub(crate) async fn open(store: &crate::store::Endpoints, url: &Url, timing: &LiveTiming, max_frame: usize) -> Result<Socket, Ended> {
    let failed = Ended::Dropped { was_live: false };
    let host = url.host_str().ok_or(Ended::Missing)?.trim_start_matches('[').trim_end_matches(']').to_owned();
    let port = url.port_or_known_default().ok_or(Ended::Missing)?;
    let tcp = match tokio::time::timeout(timing.connect, TcpStream::connect((host.as_str(), port))).await {
        Ok(Ok(tcp)) => tcp,
        _ => return Err(failed),
    };
    // Frames are small and should leave at once.
    let _ = tcp.set_nodelay(true);
    let io: Box<dyn Io> = if url.scheme() == "wss" {
        let name = rustls::pki_types::ServerName::try_from(host.clone()).map_err(|_| Ended::Missing)?;
        match tokio::time::timeout(timing.connect, tokio_rustls::TlsConnector::from(tls()).connect(name, tcp)).await {
            Ok(Ok(tls)) => Box::new(tls),
            _ => return Err(failed),
        }
    } else {
        Box::new(tcp)
    };
    let mut request = url.as_str().into_client_request().map_err(|_| Ended::Missing)?;
    let headers = request.headers_mut();
    if let Ok(agent) = crate::core::USER_AGENT.parse() {
        headers.insert("User-Agent", agent);
    }
    if let Some(access) = &store.access {
        if let (Ok(id), Ok(secret)) = (access.client_id.parse(), access.client_secret.parse()) {
            headers.insert("CF-Access-Client-Id", id);
            headers.insert("CF-Access-Client-Secret", secret);
        }
    }
    let config = WebSocketConfig::default().max_message_size(Some(max_frame)).max_frame_size(Some(max_frame));
    match tokio::time::timeout(timing.connect, tokio_tungstenite::client_async_with_config(request, io, Some(config))).await {
        Ok(Ok((socket, _))) => Ok(socket),
        Ok(Err(tungstenite::Error::Http(response))) => Err(refused_upgrade(response.status().as_u16(), response.headers().get("retry-after").and_then(|v| v.to_str().ok()))),
        _ => Err(failed),
    }
}

/// What the Store's answer to the upgrade, when it isn't one, means.
pub fn refused_upgrade(status: u16, retry_after: Option<&str>) -> Ended {
    match status {
        // Too many connections for this player, or upgrades from this network: as long as it says (or a minute).
        429 => Ended::Busy { retry_after: retry_after.and_then(|s| s.trim().parse::<u64>().ok()).map(|s| Duration::from_secs(s.clamp(1, 600))), was_live: false },
        // The Worker isn't set up, or its database didn't answer.
        502..=504 => Ended::Down { was_live: false },
        // Not deployed (yet), or a request it can't take: a bug to fix, not to retry every second.
        400 | 404 | 405 | 426 | 501 => {
            if status == 400 {
                eprintln!("[flint] Friends live: the Store refused the socket's address (HTTP 400); asked again in 10 minutes");
            }
            Ended::Missing
        }
        _ => Ended::Dropped { was_live: false },
    }
}

/// What the hub's close means (its codes: Store `src/lib/server/social-live.ts`).
pub fn closed(frame: Option<&CloseFrame>, was_live: bool) -> Ended {
    match frame.map(|f| u16::from(f.code)) {
        // The session isn't (any more) one of this player's.
        Some(4001) => Ended::Refused,
        // Flooding: more than 60 frames in 10 s.
        Some(4429) => Ended::Busy { retry_after: None, was_live },
        // Its database is unreachable.
        Some(1013) => Ended::Down { was_live },
        // A first frame that wasn't the auth, or a frame over 4 KiB: only a bug here sends those; not again in a loop.
        Some(code @ (4400 | 1009)) => {
            eprintln!("[flint] Friends live: the hub closed the socket as invalid ({code}); asked again in 10 minutes");
            Ended::Missing
        }
        // No auth in 5 s (4008), the hub restarting (1001), anything else: a drop.
        _ => Ended::Dropped { was_live },
    }
}

pub(crate) async fn close(socket: &mut Socket) {
    let frame = CloseFrame { code: CloseCode::Normal, reason: "".into() };
    let _ = tokio::time::timeout(Duration::from_secs(2), socket.close(Some(frame))).await;
}

pub(crate) fn at(when: Option<Instant>) -> tokio::time::Instant {
    when.map(tokio::time::Instant::from_std).unwrap_or_else(|| tokio::time::Instant::now() + Duration::from_secs(86_400))
}

/// One connection, from opening to its end: authenticates, then applies what the hub says until it ends.
async fn connection(social: &Social, target: &Target, timing: &LiveTiming, out: &dyn Out, typing: &mut mpsc::Receiver<String>) -> Ended {
    let Some(store) = social.live.store.as_ref() else { return Ended::Gone };
    let Some(url) = live_url(store, &target.uuid) else { return Ended::Missing };
    let mut socket = match open(store, &url, timing, MAX_FRAME).await {
        Ok(socket) => socket,
        Err(ended) => return ended,
    };
    if socket.send(Frame::text(auth_frame(&target.token))).await.is_err() {
        return Ended::Dropped { was_live: false };
    }
    let ready = tokio::time::timeout(timing.ready, async {
        loop {
            match socket.next().await {
                Some(Ok(Frame::Text(text))) => {
                    if let Some(LiveEvent::Ready { uuid }) = parse_event(text.as_str()) {
                        return Ok(uuid);
                    }
                }
                Some(Ok(Frame::Close(frame))) => return Err(closed(frame.as_ref(), false)),
                Some(Ok(_)) => {}
                Some(Err(_)) | None => return Err(Ended::Dropped { was_live: false }),
            }
        }
    })
    .await;
    match ready {
        Ok(Ok(uuid)) if uuid == target.uuid => {}
        Ok(Ok(_)) => {
            close(&mut socket).await;
            return Ended::Refused;
        }
        Ok(Err(ended)) => return ended,
        Err(_) => return Ended::Dropped { was_live: false },
    }
    social.set_live(true, out);
    let mut ping_at = Instant::now() + timing.ping;
    let mut pong_by: Option<Instant> = None;
    loop {
        let typing_ends = social.typing_ends();
        tokio::select! {
            frame = socket.next() => match frame {
                Some(Ok(Frame::Text(text))) => {
                    // Anything heard proves the connection.
                    pong_by = None;
                    match parse_event(text.as_str()) {
                        Some(LiveEvent::Pong | LiveEvent::Ready { .. } | LiveEvent::Other) | None => {}
                        Some(event) => {
                            let applied = social.apply_live(event);
                            social.deliver(applied, out);
                        }
                    }
                }
                Some(Ok(Frame::Close(frame))) => return closed(frame.as_ref(), true),
                Some(Ok(_)) => pong_by = None,
                Some(Err(_)) | None => return Ended::Dropped { was_live: true },
            },
            _ = tokio::time::sleep_until(at(Some(ping_at))) => {
                if socket.send(Frame::text(PING)).await.is_err() {
                    return Ended::Dropped { was_live: true };
                }
                ping_at = Instant::now() + timing.ping;
                pong_by.get_or_insert(Instant::now() + timing.pong);
            }
            // Nothing since the ping: a connection that died without closing.
            _ = tokio::time::sleep_until(at(pong_by)), if pong_by.is_some() => return Ended::Dropped { was_live: true },
            Some(to) = typing.recv() => {
                if socket.send(Frame::text(typing_frame(&to))).await.is_err() {
                    return Ended::Dropped { was_live: true };
                }
            }
            _ = tokio::time::sleep_until(at(typing_ends)), if typing_ends.is_some() => {
                if social.expire_typing() {
                    social.publish_to(out);
                }
            }
            _ = social.live.kick.notified() => match social.live_target() {
                Some(now) if now == *target => {}
                Some(_) => {
                    close(&mut socket).await;
                    return Ended::Switched;
                }
                None => {
                    close(&mut socket).await;
                    return Ended::Gone;
                }
            },
        }
    }
}

/// Waits up to `wait`, or until the socket should open for another account or session than `target` (None: any).
async fn wait_for_change(social: &Social, wait: Duration, target: Option<&Target>) {
    let deadline = tokio::time::Instant::now() + wait;
    loop {
        tokio::select! {
            _ = tokio::time::sleep_until(deadline) => return,
            _ = social.live.kick.notified() => {
                if social.live_target().as_ref() != target {
                    return;
                }
            }
        }
    }
}

/// Keeps the socket open while Friends has a session, for as long as the app runs (once, at start-up). A build without the
/// Store returns at once: Friends polls.
pub async fn run(social: &Social, timing: LiveTiming, out: &dyn Out) {
    if social.live.store.is_none() {
        return;
    }
    let Some(mut typing) = social.live.typing_in.lock().unwrap().take() else { return };
    let mut failures = 0u32;
    let mut refused: Option<(String, Instant)> = None;
    loop {
        let Some(target) = social.live_target() else {
            // Signed out, or not synced yet: the next sync step says.
            wait_for_change(social, Duration::from_secs(30), None).await;
            continue;
        };
        if let Some((token, when)) = &refused {
            let left = timing.refused_retry.saturating_sub(when.elapsed());
            if *token == target.token && !left.is_zero() {
                wait_for_change(social, left, Some(&target)).await;
                continue;
            }
        }
        let ended = connection(social, &target, &timing, out, &mut typing).await;
        social.set_live(false, out);
        let wait = retry_wait(&timing, &ended, &mut failures);
        if ended == Ended::Refused {
            // The sync checks the session (and signs in again if it ended); a new session is tried at once.
            refused = Some((target.token.clone(), Instant::now()));
            social.sync_soon();
        }
        if !wait.is_zero() {
            wait_for_change(social, wait, Some(&target)).await;
        }
    }
}

/// How long to wait after a connection ended as `ended`; `failures` counts the tries that failed in a row (a connection
/// that had been live starts it over).
pub fn retry_wait(timing: &LiveTiming, ended: &Ended, failures: &mut u32) -> Duration {
    let (was_live, at_least) = match *ended {
        Ended::Switched | Ended::Gone | Ended::Refused => {
            *failures = 0;
            return Duration::ZERO;
        }
        Ended::Missing => {
            *failures = 0;
            return timing.missing_retry;
        }
        Ended::Dropped { was_live } => (was_live, Duration::ZERO),
        Ended::Busy { retry_after, was_live } => (was_live, retry_after.unwrap_or(timing.busy_retry)),
        Ended::Down { was_live } => (was_live, timing.down_retry),
    };
    if was_live {
        *failures = 0;
    }
    *failures = failures.saturating_add(1);
    jittered(backoff(timing, *failures), timing.jitter).max(at_least)
}

