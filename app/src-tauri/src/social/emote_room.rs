//! Emotes seen by others: a per-server room on the Flint Store (a Durable Object per server, next to Friends live), so the
//! Flint players on the same Minecraft server see each other's emotes as they start and stop.
//!
//! - **The room**: `wss://<store>/api/v1/social/emotes/?uuid=<the account>&room=<sha256 of the server's address>`, the
//!   address normalised one way by every Flint ([`normalize`]): the host lowercased without a trailing dot, then `:` and the
//!   real port. The game reports the address it connected to after Minecraft's own SRV lookup (`_minecraft._tcp`), so
//!   `example.com` and the `mc.example.com:25565` it points at are one room; no port is 25565. Singleplayer and LAN games
//!   (and any loopback, private or link-local address, or `.local` / `.lan` name) never get a room.
//! - **Opening it**: when the game joins a server ([`Room::join`]), with the Friends session of the account it plays as;
//!   closed when it leaves (or plays as another account). It doesn't start Friends presence. The first frame is
//!   `{"type":"auth","token":…}` (the token never goes in the URL), answered by `{"type":"ready","uuid","server_time"}`
//!   within 5 s; pings as Friends live ([`live::PING`] every 25 s, something heard within 10 s).
//! - **What goes through it**: `{"type":"emote","cosmetic_id"}` and `{"type":"emote_stop"}` from this game; from the
//!   others `{"type":"emote","uuid","cosmetic_id","at"}` and `{"type":"emote_stop","uuid"}` (never one's own), and
//!   `{"type":"emote_refused","cosmetic_id","reason"}` for one of this game's (`not_owned`, `rate_limited`: 2 s between
//!   emotes). The game gets them through the game link ([`Room::events_after`], waited on), each emote with how long ago it
//!   started by the room's clock.
//! - **Closing codes** ([`closed`], [`retry_wait`]): 4001 (the session expired): signed in again first, then at once;
//!   4409 (replaced: another socket of this player's took over): never opened again for that server (only a new join opens
//!   it); 4400 and 1009 (only a bug here): not again for 10 minutes; 4503 (the room is full) and every other abnormal close
//!   (4429, 4008, 1013…), and an upgrade the Store refuses as busy or down: at least a minute plus 0–30 s, doubling on each
//!   one in a row up to 10 minutes. A connection that just broke (no close at all) comes back as Friends live's does.
//! - **Times**: `at` and `server_time` are RFC 3339 UTC with milliseconds (`2026-11-11T21:04:05.123Z`); an emote started
//!   `(now + offset) - at` ago, the offset being `server_time` minus this computer's clock when `ready` came.
//! - Flint's own TCP and TLS, as Friends live; frames are at most 1 KiB. The token never appears in logs.
use super::live::{self, LiveTiming};
use super::*;
use futures_util::SinkExt;
use sha2::{Digest, Sha256};
use std::collections::VecDeque;
use std::net::IpAddr;
use tokio::sync::{mpsc, watch};
use tokio_tungstenite::tungstenite::protocol::CloseFrame;
use tokio_tungstenite::tungstenite::Message as Frame;

pub const EMOTES_PATH: &str = "api/v1/social/emotes/";
/// Frames the room takes (and sends) are at most this (its close 1009 past it).
pub const MAX_FRAME: usize = 1024;
/// Events kept for games to read (the oldest go first).
pub const MAX_EVENTS: usize = 64;
/// An emote older than this when it arrives isn't played (it would be over).
pub const MAX_AGO_MS: u64 = 30_000;
/// Minecraft's port when the address names none (and has no SRV record).
pub const DEFAULT_PORT: u16 = 25565;

/// The server's address as every Flint names it: `host:port`, the host lowercased without a trailing dot, the port the real
/// one (25565 when none is given). None for an address that isn't a server on the Internet: singleplayer has none, and LAN
/// games, loopback, private and link-local addresses, and `.local`, `.lan`, `.home`, `.internal` names never get a room.
pub fn normalize(address: &str) -> Option<String> {
    let a = address.trim();
    if a.is_empty() || a.len() > 300 || a.chars().any(|c| c.is_whitespace() || c == '/' || c == '@') {
        return None;
    }
    // [v6]:port, v6 alone, host:port, host.
    let (host, port) = if let Some(rest) = a.strip_prefix('[') {
        let end = rest.find(']')?;
        let host = &rest[..end];
        let after = &rest[end + 1..];
        let port = match after.strip_prefix(':') {
            Some(p) => Some(p),
            None if after.is_empty() => None,
            None => return None,
        };
        (host.to_owned(), port)
    } else if a.matches(':').count() > 1 {
        (a.to_owned(), None)
    } else {
        match a.rsplit_once(':') {
            Some((h, p)) => (h.to_owned(), Some(p)),
            None => (a.to_owned(), None),
        }
    };
    let port: u16 = match port {
        Some(p) => p.parse().ok().filter(|p| *p > 0)?,
        None => DEFAULT_PORT,
    };
    let host = host.to_lowercase();
    let host = host.trim_end_matches('.');
    if host.is_empty() || host.len() > 253 {
        return None;
    }
    if let Ok(ip) = host.parse::<IpAddr>() {
        if !public(ip) {
            return None;
        }
        return Some(match ip {
            IpAddr::V6(v6) => format!("[{v6}]:{port}"),
            IpAddr::V4(v4) => format!("{v4}:{port}"),
        });
    }
    let labels_ok = host.split('.').all(|l| !l.is_empty() && l.len() <= 63 && l.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_'));
    if !labels_ok || !host.contains('.') {
        // A bare name ("localhost", a LAN machine's name) is never a server on the Internet.
        return None;
    }
    let tld = host.rsplit('.').next().unwrap_or("");
    if matches!(tld, "local" | "lan" | "home" | "internal" | "localhost" | "localdomain" | "arpa") {
        return None;
    }
    Some(format!("{host}:{port}"))
}

/// Whether an address can be a public server (not loopback, private, link-local, shared or unspecified).
fn public(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            !(v4.is_loopback() || v4.is_private() || v4.is_link_local() || v4.is_unspecified() || v4.is_broadcast() || o[0] == 0
                || (o[0] == 100 && (64..128).contains(&o[1])))
        }
        IpAddr::V6(v6) => {
            let s = v6.segments();
            !(v6.is_loopback() || v6.is_unspecified() || (s[0] & 0xfe00) == 0xfc00 || (s[0] & 0xffc0) == 0xfe80)
                && v6.to_ipv4_mapped().is_none_or(|v4| public(IpAddr::V4(v4)))
        }
    }
}

/// The room's id: the SHA-256 (lowercase hex) of the normalised address.
pub fn room_id(normalized: &str) -> String {
    format!("{:x}", Sha256::digest(normalized.as_bytes()))
}

/// The room's address at `store` for `uuid` (dashed) in `room`: wss (ws only on this computer, as Friends live).
pub fn room_url(store: &crate::store::Endpoints, uuid: &str, room: &str) -> Option<Url> {
    let mut url = store.api(EMOTES_PATH);
    let scheme = if url.scheme() == "https" { "wss" } else { "ws" };
    url.set_scheme(scheme).ok()?;
    url.query_pairs_mut().append_pair("uuid", &live::dashed(uuid)).append_pair("room", room);
    Some(url)
}

/// This game's emote (`cosmetic_id`: a checked id, so nothing in it needs escaping).
pub fn emote_frame(cosmetic_id: &str) -> String {
    format!(r#"{{"type":"emote","cosmetic_id":"{cosmetic_id}"}}"#)
}

pub const STOP: &str = r#"{"type":"emote_stop"}"#;

// ---------- what the room says ----------

#[derive(Debug, Clone, PartialEq)]
pub enum RoomEvent {
    Ready { uuid: String, server_ms: Option<i64> },
    Emote { uuid: String, cosmetic_id: String, at_ms: Option<i64> },
    Stop { uuid: String },
    Refused { cosmetic_id: String, reason: String },
    Pong,
    Other,
}

/// A time as the room writes it (JavaScript's `toISOString()`: RFC 3339 UTC with milliseconds), in milliseconds since 1970.
pub fn millis(value: &Value) -> Option<i64> {
    let text = value.as_str()?;
    let seconds = rfc3339_secs(text)?;
    // The fraction after the seconds (".123"), to the millisecond.
    let fraction = text.get(19..).filter(|rest| rest.starts_with('.')).map(|rest| &rest[1..]).unwrap_or("");
    let digits: String = fraction.chars().take_while(char::is_ascii_digit).take(3).collect();
    let ms = if digits.is_empty() { 0 } else { format!("{digits:0<3}").parse::<i64>().ok()? };
    Some(seconds * 1000 + ms)
}

fn uuid_of(value: &Value) -> Option<String> {
    let text = value.as_str()?.to_ascii_lowercase().replace('-', "");
    (text.len() == 32 && text.bytes().all(|b| b.is_ascii_hexdigit())).then_some(text)
}

fn cosmetic_of(value: &Value) -> Option<String> {
    let text = value.as_str()?.to_ascii_lowercase();
    crate::store::is_uuid(&text).then_some(text)
}

/// One frame from the room, checked. Unknown types are [`RoomEvent::Other`]; malformed ones none.
pub fn parse(text: &str) -> Option<RoomEvent> {
    if text.len() > MAX_FRAME {
        return None;
    }
    let v: Value = serde_json::from_str(text).ok()?;
    Some(match v["type"].as_str()? {
        "ready" => RoomEvent::Ready { uuid: uuid_of(&v["uuid"])?, server_ms: millis(&v["server_time"]) },
        "emote" => RoomEvent::Emote { uuid: uuid_of(&v["uuid"])?, cosmetic_id: cosmetic_of(&v["cosmetic_id"])?, at_ms: millis(&v["at"]) },
        "emote_stop" => RoomEvent::Stop { uuid: uuid_of(&v["uuid"])? },
        "emote_refused" => RoomEvent::Refused {
            cosmetic_id: cosmetic_of(&v["cosmetic_id"]).unwrap_or_default(),
            reason: match v["reason"].as_str() {
                Some(r @ ("not_owned" | "rate_limited")) => r.to_owned(),
                _ => "refused".to_owned(),
            },
        },
        "pong" => RoomEvent::Pong,
        _ => RoomEvent::Other,
    })
}

/// How a room connection ended.
#[derive(Debug, PartialEq)]
pub enum RoomEnded {
    /// The session expired (4001): asked again with the next one.
    Refused,
    /// No room at this Store, or only a bug here (4400, 1009, HTTP 400/404): not again for a while.
    Missing,
    Dropped { was_live: bool },
    /// A full room (4503), too much (4429), no auth in time (4008), the Store's database (1013), any other abnormal close, or
    /// an upgrade refused as busy or down: at least a minute plus jitter, doubling on each one in a row up to 10 minutes.
    Abnormal { code: Option<u16> },
    /// Another socket of this player's took over (4409): this server's room isn't opened again until the game joins anew.
    Replaced,
    /// The game left, joined another server, or plays as another account: opened again at once (or not at all).
    Switched,
}

impl From<live::Ended> for RoomEnded {
    fn from(e: live::Ended) -> Self {
        match e {
            live::Ended::Refused => RoomEnded::Refused,
            live::Ended::Missing => RoomEnded::Missing,
            live::Ended::Dropped { was_live } => RoomEnded::Dropped { was_live },
            live::Ended::Busy { .. } | live::Ended::Down { .. } => RoomEnded::Abnormal { code: None },
            live::Ended::Switched | live::Ended::Gone => RoomEnded::Switched,
        }
    }
}

/// What the room's close means.
pub fn closed(frame: Option<&CloseFrame>, was_live: bool) -> RoomEnded {
    match frame.map(|f| u16::from(f.code)) {
        Some(4001) => RoomEnded::Refused,
        Some(4409) => RoomEnded::Replaced,
        Some(code @ (4400 | 1009)) => {
            eprintln!("[flint] Emote room: closed as invalid ({code}); asked again in 10 minutes");
            RoomEnded::Missing
        }
        // A normal close, or the room going away (restarting): back as after a drop.
        Some(1000 | 1001) | None => RoomEnded::Dropped { was_live },
        // 4503 (full), 4429 (too much), 4008 (no auth in 5 s), 1013 (the database), anything else.
        Some(code) => RoomEnded::Abnormal { code: Some(code) },
    }
}

/// After an abnormal close: the first wait, the most it doubles to, and the random spread added to each.
pub const ABNORMAL_FIRST: Duration = Duration::from_secs(60);
pub const ABNORMAL_MAX: Duration = Duration::from_secs(600);
pub const ABNORMAL_JITTER: Duration = Duration::from_secs(30);

/// The wait after the `repeat`th abnormal close in a row (1: the first): a minute, doubling up to 10 minutes, plus `jitter`.
pub fn abnormal_wait(repeat: u32, jitter: Duration) -> Duration {
    let factor = 2u32.saturating_pow(repeat.saturating_sub(1).min(16));
    ABNORMAL_FIRST.saturating_mul(factor).min(ABNORMAL_MAX) + jitter.min(ABNORMAL_JITTER)
}

/// A random spread for [`abnormal_wait`]: 0 to 30 s.
fn spread() -> Duration {
    use rand::Rng;
    Duration::from_millis(rand::thread_rng().gen_range(0..=ABNORMAL_JITTER.as_millis() as u64))
}

/// The wait after a connection ended, or none when it must not be opened again for this server (replaced). `failures`
/// counts the drops in a row, `abnormal` the abnormal closes in a row; `jitter` is added to an abnormal close's wait.
pub fn retry_wait(timing: &LiveTiming, ended: &RoomEnded, failures: &mut u32, abnormal: &mut u32, jitter: Duration) -> Option<Duration> {
    if !matches!(ended, RoomEnded::Abnormal { .. }) {
        *abnormal = 0;
    }
    match *ended {
        RoomEnded::Replaced => {
            *failures = 0;
            None
        }
        RoomEnded::Switched => {
            *failures = 0;
            Some(Duration::ZERO)
        }
        // Signed in again first (the run loop waits for a new session), then at once.
        RoomEnded::Refused => {
            *failures = 0;
            Some(timing.first_retry)
        }
        RoomEnded::Missing => {
            *failures = 0;
            Some(timing.missing_retry)
        }
        RoomEnded::Abnormal { .. } => {
            *failures = 0;
            *abnormal = abnormal.saturating_add(1);
            Some(abnormal_wait(*abnormal, jitter))
        }
        RoomEnded::Dropped { was_live } => {
            if was_live {
                *failures = 0;
            }
            *failures = failures.saturating_add(1);
            Some(live::backoff(timing, *failures))
        }
    }
}

// ---------- the state a game reads ----------

/// The room a game is in: the account it plays as, the server's normalised address and the room's id.
#[derive(Clone, Debug, PartialEq)]
pub struct Want {
    pub uuid: String,
    pub address: String,
    pub room: String,
}

/// One thing a game hears: another player's emote or stop, or one of its own emotes refused.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameEmote {
    pub seq: u64,
    /// `emote`, `stop` or `refused`.
    pub kind: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uuid: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cosmetic_id: Option<String>,
    /// How long ago the emote started, by the room's clock.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ago_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

#[derive(Default)]
struct Inner {
    want: Option<Want>,
    live: bool,
    events: VecDeque<GameEmote>,
    seq: u64,
    /// The room's clock minus this computer's, in ms (from `ready`).
    offset_ms: i64,
    /// Joins so far (a server joined anew counts, even the same one).
    joins: u64,
}

pub struct Room {
    inner: Mutex<Inner>,
    kick: Notify,
    changed: watch::Sender<u64>,
    /// Own emotes the room sent back (it never should): counted, never played.
    echoes: std::sync::atomic::AtomicU64,
    out: mpsc::Sender<String>,
    out_in: Mutex<Option<mpsc::Receiver<String>>>,
}

impl Default for Room {
    fn default() -> Self {
        let (out, out_in) = mpsc::channel(8);
        Self { inner: Mutex::default(), kick: Notify::new(), changed: watch::Sender::new(0), echoes: Default::default(), out, out_in: Mutex::new(Some(out_in)) }
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

impl Room {
    /// The game joined a server (`want`), or left one (none): the room follows at once.
    pub fn join(&self, want: Option<Want>) {
        let mut inner = self.inner.lock().unwrap();
        if inner.want != want {
            if want.is_some() {
                inner.joins += 1;
            }
            inner.want = want;
            inner.live = false;
            drop(inner);
            self.kick.notify_one();
        }
    }

    /// The queue of this game's frames (once: the connection loop takes it).
    pub(crate) fn take_out(&self) -> Option<mpsc::Receiver<String>> {
        self.out_in.lock().unwrap().take()
    }

    /// How many times a game joined a server (a new join after a replaced socket opens the room again).
    pub fn joins(&self) -> u64 {
        self.inner.lock().unwrap().joins
    }

    pub fn wanted(&self) -> Option<Want> {
        self.inner.lock().unwrap().want.clone()
    }

    pub fn is_live(&self) -> bool {
        self.inner.lock().unwrap().live
    }

    fn set_live(&self, live: bool, offset_ms: Option<i64>) {
        let mut inner = self.inner.lock().unwrap();
        inner.live = live;
        if let Some(o) = offset_ms {
            inner.offset_ms = o;
        }
    }

    /// Sends one of this game's frames while the room is live; false when it isn't (nothing is queued for later: an emote
    /// sent late would play out of time).
    pub fn send(&self, frame: String) -> bool {
        self.is_live() && frame.len() <= MAX_FRAME && self.out.try_send(frame).is_ok()
    }

    fn push(&self, kind: &'static str, uuid: Option<String>, cosmetic_id: Option<String>, ago_ms: Option<u64>, reason: Option<String>) {
        let mut inner = self.inner.lock().unwrap();
        inner.seq += 1;
        let seq = inner.seq;
        inner.events.push_back(GameEmote { seq, kind, uuid, cosmetic_id, ago_ms, reason });
        while inner.events.len() > MAX_EVENTS {
            inner.events.pop_front();
        }
        drop(inner);
        self.changed.send_replace(seq);
    }

    /// Applies one frame of the room's (a live connection's).
    pub(crate) fn apply(&self, event: RoomEvent, me: &str) {
        match event {
            RoomEvent::Emote { uuid, cosmetic_id, at_ms } => {
                if uuid == me {
                    self.echoes.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    return;
                }
                let offset = self.inner.lock().unwrap().offset_ms;
                let ago = at_ms.map(|at| (now_ms() + offset - at).max(0) as u64).unwrap_or(0);
                if ago <= MAX_AGO_MS {
                    self.push("emote", Some(uuid), Some(cosmetic_id), Some(ago), None);
                }
            }
            RoomEvent::Stop { uuid } if uuid != me => self.push("stop", Some(uuid), None, None, None),
            RoomEvent::Refused { cosmetic_id, reason } => self.push("refused", None, Some(cosmetic_id).filter(|c| !c.is_empty()), None, Some(reason)),
            _ => {}
        }
    }

    /// Own emotes the room sent back (the room never should; the live demo test checks it).
    #[cfg(test)]
    pub fn echoes(&self) -> u64 {
        self.echoes.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// What happened after `since` (the game's last `seq`), the newest `seq`, and whether the room is live.
    pub fn events_after(&self, since: u64) -> (Vec<GameEmote>, u64, bool) {
        let inner = self.inner.lock().unwrap();
        (inner.events.iter().filter(|e| e.seq > since).cloned().collect(), inner.seq, inner.live)
    }

    /// Waits up to `wait` for something after `since`.
    pub async fn wait_after(&self, since: u64, wait: Duration) {
        let mut rx = self.changed.subscribe();
        let _ = tokio::time::timeout(wait, rx.wait_for(|seq| *seq > since)).await;
    }
}

// ---------- the connection ----------

/// One connection to the room `want` as `target`, until it ends.
pub(crate) async fn room_connection(room: &Room, store: &crate::store::Endpoints, target: &live::Target, want: &Want, timing: &LiveTiming,
    out: &mut mpsc::Receiver<String>) -> RoomEnded {
    let Some(url) = room_url(store, &target.uuid, &want.room) else { return RoomEnded::Missing };
    let mut socket = match live::open(store, &url, timing, MAX_FRAME).await {
        Ok(socket) => socket,
        Err(ended) => return ended.into(),
    };
    if socket.send(Frame::text(live::auth_frame(&target.token))).await.is_err() {
        return RoomEnded::Dropped { was_live: false };
    }
    let ready = tokio::time::timeout(timing.ready, async {
        loop {
            match socket.next().await {
                Some(Ok(Frame::Text(text))) => {
                    if let Some(RoomEvent::Ready { uuid, server_ms }) = parse(text.as_str()) {
                        return Ok((uuid, server_ms));
                    }
                }
                Some(Ok(Frame::Close(frame))) => return Err(closed(frame.as_ref(), false)),
                Some(Ok(_)) => {}
                Some(Err(_)) | None => return Err(RoomEnded::Dropped { was_live: false }),
            }
        }
    })
    .await;
    let server_ms = match ready {
        Ok(Ok((uuid, server_ms))) if uuid == target.uuid => server_ms,
        Ok(Ok(_)) => {
            live::close(&mut socket).await;
            return RoomEnded::Refused;
        }
        Ok(Err(ended)) => return ended,
        Err(_) => return RoomEnded::Dropped { was_live: false },
    };
    // Frames this game queued before the room was live are dropped: an emote sent late plays out of time.
    while out.try_recv().is_ok() {}
    room.set_live(true, server_ms.map(|s| s - now_ms()));
    let mut ping_at = Instant::now() + timing.ping;
    let mut pong_by: Option<Instant> = None;
    loop {
        tokio::select! {
            frame = socket.next() => match frame {
                Some(Ok(Frame::Text(text))) => {
                    pong_by = None;
                    if let Some(event) = parse(text.as_str()) {
                        room.apply(event, &target.uuid);
                    }
                }
                Some(Ok(Frame::Close(frame))) => return closed(frame.as_ref(), true),
                Some(Ok(_)) => pong_by = None,
                Some(Err(_)) | None => return RoomEnded::Dropped { was_live: true },
            },
            _ = tokio::time::sleep_until(live::at(Some(ping_at))) => {
                if socket.send(Frame::text(live::PING)).await.is_err() {
                    return RoomEnded::Dropped { was_live: true };
                }
                ping_at = Instant::now() + timing.ping;
                pong_by.get_or_insert(Instant::now() + timing.pong);
            }
            _ = tokio::time::sleep_until(live::at(pong_by)), if pong_by.is_some() => return RoomEnded::Dropped { was_live: true },
            Some(frame) = out.recv() => {
                if socket.send(Frame::text(frame)).await.is_err() {
                    return RoomEnded::Dropped { was_live: true };
                }
            }
            _ = room.kick.notified() => {
                if room.wanted().as_ref() != Some(want) {
                    live::close(&mut socket).await;
                    return RoomEnded::Switched;
                }
            }
        }
    }
}

/// Waits up to `wait`, or until the game's room changes from `want`.
async fn wait_for_change(room: &Room, wait: Duration, want: Option<&Want>) {
    let deadline = tokio::time::Instant::now() + wait;
    loop {
        tokio::select! {
            _ = tokio::time::sleep_until(deadline) => return,
            _ = room.kick.notified() => {
                if room.wanted().as_ref() != want {
                    return;
                }
            }
        }
    }
}

/// Keeps the room a game is in open, for as long as the app runs (once, at start-up). A build without the Store returns at
/// once. The room opens only with the Friends session of the account the game plays as.
pub async fn run(social: &Social, timing: LiveTiming) {
    let Some(store) = social.live.store.clone() else { return };
    let room = &social.emotes;
    let Some(mut out) = room.take_out() else { return };
    let mut failures = 0u32;
    let mut abnormal = 0u32;
    let mut refused_token: Option<String> = None;
    loop {
        let Some(want) = room.wanted() else {
            wait_for_change(room, Duration::from_secs(3600), None).await;
            continue;
        };
        let target = social.live_target().filter(|t| t.uuid == want.uuid);
        let Some(target) = target.filter(|t| refused_token.as_deref() != Some(t.token.as_str())) else {
            // Friends isn't signed in as this account (or its session expired and the next isn't there yet): look again soon.
            wait_for_change(room, Duration::from_secs(15), Some(&want)).await;
            continue;
        };
        let ended = room_connection(room, &store, &target, &want, &timing, &mut out).await;
        room.set_live(false, None);
        if ended == RoomEnded::Refused {
            refused_token = Some(target.token.clone());
            social.sync_soon();
        } else {
            refused_token = None;
        }
        match retry_wait(&timing, &ended, &mut failures, &mut abnormal, spread()) {
            // Replaced: another socket of this player's has this room; only a new join (or leaving) opens one again.
            None => {
                let joins = room.joins();
                while room.wanted().as_ref() == Some(&want) && room.joins() == joins {
                    wait_for_change(room, Duration::from_secs(3600), Some(&want)).await;
                }
            }
            Some(wait) if !wait.is_zero() => wait_for_change(room, wait, Some(&want)).await,
            Some(_) => {}
        }
    }
}
