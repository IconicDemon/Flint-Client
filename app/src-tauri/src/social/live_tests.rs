//! Friends live against the fake Store hub in `fake.rs`: the socket's frames, coming back after a drop, pushes applied
//! once and in order, polling as the fallback, writes through the Store, and the end-to-end latency (`measure_latency`,
//! run by hand; `measure_local_hub`, by hand against the website's local Store and Supabase).
use super::fake::Fake;
use super::fake::KEY;
use super::live::*;
use super::*;
use crate::core::build_clients;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

const STEVE: &str = "8667ba71b85a4004af54457a9734eed7";
const ALEX: &str = "ec561538f3fd461daff5086b22154bce";

/// A launcher's one account and the Minecraft token it gets for it.
struct Mc {
    who: Identity,
    token: String,
}

impl Minecraft for Mc {
    fn account(&self) -> Option<Identity> {
        Some(self.who.clone())
    }
    fn access_token<'a>(&'a self, _uuid: &'a str) -> TokenFuture<'a> {
        let token = self.token.clone();
        Box::pin(async move { Some(token) })
    }
}

/// What the app's window would have been told.
#[derive(Default)]
struct Recorder {
    events: Mutex<Vec<SocialEvent>>,
    updates: AtomicUsize,
}

impl Out for Recorder {
    fn event(&self, event: &SocialEvent) {
        self.events.lock().unwrap().push(event.clone());
    }
    fn update(&self, _snapshot: &Snapshot) {
        self.updates.fetch_add(1, Ordering::SeqCst);
    }
}

impl Recorder {
    fn messages(&self) -> Vec<i64> {
        self.events.lock().unwrap().iter().filter_map(|e| if let SocialEvent::Message { message, .. } = e { Some(message.id) } else { None }).collect()
    }
    fn count(&self, kind: &str) -> usize {
        self.events
            .lock()
            .unwrap()
            .iter()
            .filter(|e| match e {
                SocialEvent::Request { .. } => kind == "request",
                SocialEvent::Accepted { .. } => kind == "accepted",
                SocialEvent::Message { .. } => kind == "message",
                SocialEvent::Online { .. } => kind == "online",
            })
            .count()
    }
}

/// Short timings, so a test sees reconnects and pings in milliseconds.
const FAST: LiveTiming = LiveTiming {
    connect: Duration::from_secs(2),
    ready: Duration::from_secs(2),
    ping: Duration::from_millis(300),
    pong: Duration::from_millis(300),
    first_retry: Duration::from_millis(50),
    max_retry: Duration::from_millis(400),
    missing_retry: Duration::from_secs(5),
    refused_retry: Duration::from_secs(10),
    busy_retry: Duration::from_millis(600),
    down_retry: Duration::from_millis(400),
    jitter: false,
};

struct Player {
    social: Arc<Social>,
    mc: Arc<Mc>,
    out: Arc<Recorder>,
    tasks: Vec<tokio::task::JoinHandle<()>>,
}

impl Drop for Player {
    fn drop(&mut self) {
        for task in &self.tasks {
            task.abort();
        }
    }
}

fn launcher(fake: &Fake, store: bool, paced: bool) -> Social {
    let mut endpoints = Endpoints::new(&fake.url(), KEY).expect("a local project is usable");
    endpoints.mojang = Url::parse(&fake.url()).unwrap();
    let mut social = Social::new(build_clients().0, Some(endpoints));
    if !paced {
        social.pacing = Pacing { min_gap: Duration::ZERO, resign_gap: Duration::ZERO };
    }
    if store {
        social = social.with_store(fake.store());
    }
    social
}

impl Player {
    fn new(fake: &Fake, uuid: &str, name: &str, store: bool, paced: bool) -> Self {
        let mc = Mc { who: Identity { uuid: uuid.into(), name: name.into() }, token: format!("mc-{name}") };
        Self { social: Arc::new(launcher(fake, store, paced)), mc: Arc::new(mc), out: Arc::default(), tasks: Vec::new() }
    }

    async fn step(&self) -> Step {
        self.social.step(&*self.mc).await
    }

    /// The app's two tasks: the sync, and (with a Store) the socket.
    fn start(&mut self, timing: LiveTiming) {
        let (social, mc, out) = (self.social.clone(), self.mc.clone(), self.out.clone());
        self.tasks.push(tokio::spawn(async move { run_sync(&social, &*mc, &*out).await }));
        let (social, out) = (self.social.clone(), self.out.clone());
        self.tasks.push(tokio::spawn(async move { live::run(&social, timing, &*out).await }));
    }

    fn ids(&self, with: &str) -> Vec<i64> {
        self.social.snapshot().conversations.get(with).map(|c| c.messages.iter().map(|m| m.id).collect()).unwrap_or_default()
    }

    async fn send(&self, to: &str, body: &str) -> Message {
        self.social.send(&*self.mc, to, body, false, None).await.expect("sent")
    }
}

/// Waits for `check` (every 2 ms), failing after `limit`.
async fn until(what: &str, limit: Duration, mut check: impl FnMut() -> bool) -> Duration {
    let started = Instant::now();
    while !check() {
        assert!(started.elapsed() < limit, "timed out waiting for {what}");
        tokio::time::sleep(Duration::from_millis(2)).await;
    }
    started.elapsed()
}

fn accounts(fake: &Fake) {
    fake.with(|w| {
        w.add_mojang_account("mc-Steve", STEVE, "Steve");
        w.add_mojang_account("mc-Alex", ALEX, "Alex");
    });
}

/// Steve and Alex signed in and synced once, not friends.
async fn strangers(fake: &Fake, store: bool, paced: bool) -> (Player, Player) {
    accounts(fake);
    let (steve, alex) = (Player::new(fake, STEVE, "Steve", store, paced), Player::new(fake, ALEX, "Alex", store, paced));
    steve.step().await;
    alex.step().await;
    (steve, alex)
}

/// Steve and Alex friends (Steve asked, Alex accepted), both synced since.
async fn friends(fake: &Fake, store: bool, paced: bool) -> (Player, Player) {
    let (steve, alex) = strangers(fake, store, paced).await;
    steve.social.request(&*steve.mc, "alex").await.unwrap();
    alex.step().await;
    let id = alex.social.snapshot().incoming[0].id.clone();
    alex.social.respond(&*alex.mc, &id, true).await.unwrap();
    steve.step().await;
    alex.step().await;
    assert_eq!(steve.social.snapshot().friends.len(), 1);
    (steve, alex)
}

async fn live_both(steve: &mut Player, alex: &mut Player, timing: LiveTiming) {
    steve.start(timing);
    alex.start(timing);
    let (s, a) = (steve.social.clone(), alex.social.clone());
    until("both live", Duration::from_secs(5), || s.live_up() && a.live_up()).await;
}

fn dashed_of(uuid: &str) -> String {
    super::live::dashed(uuid)
}

// ---------- the protocol ----------

#[test]
fn the_frames_are_the_contracts_own() {
    assert_eq!(PING, r#"{"type":"ping"}"#, "exactly this text: Cloudflare answers it without waking the hub");
    assert_eq!(serde_json::from_str::<Value>(&auth_frame("t0k3n")).unwrap(), json!({ "type": "auth", "token": "t0k3n" }));
    assert_eq!(serde_json::from_str::<Value>(&typing_frame(ALEX)).unwrap(), json!({ "type": "typing", "to": "ec561538-f3fd-461d-aff5-086b22154bce" }));
    // The account in the address (it routes to the player's hub), never the token.
    let production = crate::store::Endpoints::production();
    assert_eq!(live_url(&production, STEVE).unwrap().as_str(), "wss://store.flintclient.com/api/v1/social/live/?uuid=8667ba71-b85a-4004-af54-457a9734eed7");
    let local = crate::store::Endpoints { store: Url::parse("http://localhost:3214/").unwrap(), ..crate::store::Endpoints::production() };
    assert_eq!(live_url(&local, STEVE).unwrap().as_str(), "ws://localhost:3214/api/v1/social/live/?uuid=8667ba71-b85a-4004-af54-457a9734eed7");
}

#[test]
fn every_push_is_read_and_checked() {
    let parse = |v: Value| parse_event(&v.to_string());
    assert_eq!(parse(json!({ "type": "ready", "uuid": dashed_of(STEVE), "server_time": "2026-11-10T00:00:00Z" })), Some(LiveEvent::Ready { uuid: STEVE.into() }));
    assert_eq!(parse(json!({ "type": "pong" })), Some(LiveEvent::Pong));
    let message = json!({ "id": 41, "from": dashed_of(STEVE), "to": dashed_of(ALEX), "body": "gg", "sensitive": false, "at": "2026-11-10T00:00:00Z", "read": false });
    let Some(LiveEvent::Message(m)) = parse(json!({ "type": "message", "message": message })) else { panic!("a message") };
    assert_eq!((m.id, m.from.as_str(), m.to.as_str(), m.body.as_str()), (41, STEVE, ALEX, "gg"));
    assert_eq!(parse(json!({ "type": "read", "with": dashed_of(ALEX), "up_to": 41 })), Some(LiveEvent::Read { with: ALEX.into(), up_to: 41, by: None }));
    assert_eq!(
        parse(json!({ "type": "read", "with": dashed_of(ALEX), "up_to": "41", "by": dashed_of(ALEX) })),
        Some(LiveEvent::Read { with: ALEX.into(), up_to: 41, by: Some(ALEX.into()) })
    );
    let request = json!({ "id": 7, "uuid": dashed_of(ALEX), "name": "Alex", "at": "2026-11-10T00:00:00Z" });
    assert!(matches!(parse(json!({ "type": "request", "request": request })), Some(LiveEvent::Request { outgoing: false, .. })));
    assert!(matches!(parse(json!({ "type": "request", "direction": "outgoing", "request": request })), Some(LiveEvent::Request { outgoing: true, .. })));
    assert_eq!(parse(json!({ "type": "request_gone", "id": 7 })), Some(LiveEvent::RequestGone { id: json!(7) }));
    let friend = json!({ "uuid": dashed_of(ALEX), "name": "Alex", "status": "online", "in_game": true, "pinned": false, "unread": 0 });
    assert!(matches!(parse(json!({ "type": "friend_added", "friend": friend })), Some(LiveEvent::FriendAdded(f)) if f.uuid == ALEX && f.in_game));
    assert_eq!(parse(json!({ "type": "friend_removed", "uuid": dashed_of(ALEX) })), Some(LiveEvent::FriendRemoved { uuid: ALEX.into() }));
    assert_eq!(
        parse(json!({ "type": "presence", "uuid": dashed_of(ALEX), "status": "away", "in_game": false })),
        Some(LiveEvent::Presence { uuid: ALEX.into(), status: "away".into(), in_game: Some(false), last_seen: None })
    );
    assert!(matches!(parse(json!({ "type": "presence", "uuid": dashed_of(ALEX), "status": "away", "last_seen": null })), Some(LiveEvent::Presence { last_seen: Some(None), .. })));
    assert!(matches!(parse(json!({ "type": "presence", "uuid": dashed_of(ALEX), "status": "away", "last_seen": "2026-11-10T00:00:00Z" })), Some(LiveEvent::Presence { last_seen: Some(Some(_)), .. })));
    // A status nobody knows shows as offline, as in the sync.
    assert!(matches!(parse(json!({ "type": "presence", "uuid": dashed_of(ALEX), "status": "dancing" })), Some(LiveEvent::Presence { status, .. }) if status == "offline"));
    assert_eq!(parse(json!({ "type": "typing", "uuid": dashed_of(ALEX) })), Some(LiveEvent::Typing { uuid: ALEX.into() }));
    assert_eq!(parse(json!({ "type": "resync" })), Some(LiveEvent::Resync));
    // A newer server's news is passed over; what doesn't check out is dropped.
    assert_eq!(parse(json!({ "type": "something_later", "x": 1 })), Some(LiveEvent::Other));
    for bad in [
        json!({ "type": "message", "message": { "id": "x", "from": STEVE, "to": ALEX, "body": "a" } }),
        json!({ "type": "message" }),
        json!({ "type": "request", "request": { "id": "../x", "uuid": ALEX, "name": "Alex" } }),
        json!({ "type": "friend_removed", "uuid": "Steve" }),
        json!({ "type": "ready" }),
        json!({ "kind": "message" }),
        json!([1, 2]),
    ] {
        assert_eq!(parse(bad.clone()), None, "{bad}");
    }
    assert_eq!(parse_event("not json"), None);
}

#[test]
fn the_hubs_closes_and_refusals_wait_as_they_ask() {
    use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
    use tokio_tungstenite::tungstenite::protocol::CloseFrame;
    let close = |code: u16| closed(Some(&CloseFrame { code: CloseCode::from(code), reason: "x".into() }), true);
    assert_eq!(close(4001), Ended::Refused);
    assert_eq!(close(4429), Ended::Busy { retry_after: None, was_live: true });
    assert_eq!(close(1013), Ended::Down { was_live: true });
    assert_eq!(close(4400), Ended::Missing, "a first frame that was not the auth: a bug, not a loop");
    assert_eq!(close(1009), Ended::Missing, "a frame over 4 KiB: a bug, not a loop");
    assert_eq!(close(4008), Ended::Dropped { was_live: true }, "no auth in time: tried again");
    assert_eq!(close(1001), Ended::Dropped { was_live: true });
    assert_eq!(closed(None, false), Ended::Dropped { was_live: false });
    assert_eq!(refused_upgrade(429, Some("30")), Ended::Busy { retry_after: Some(Duration::from_secs(30)), was_live: false });
    assert_eq!(refused_upgrade(429, Some("soon")), Ended::Busy { retry_after: None, was_live: false });
    assert_eq!(refused_upgrade(503, None), Ended::Down { was_live: false });
    for status in [400, 404, 405, 426, 501] {
        assert_eq!(refused_upgrade(status, None), Ended::Missing, "{status}");
    }
    assert_eq!(refused_upgrade(500, None), Ended::Dropped { was_live: false });
    // The waits (the app's timing, without its spread).
    let timing = LiveTiming { jitter: false, ..TIMING };
    let mut failures = 0;
    assert_eq!(retry_wait(&timing, &Ended::Dropped { was_live: false }, &mut failures), Duration::from_secs(1));
    assert_eq!(retry_wait(&timing, &Ended::Dropped { was_live: false }, &mut failures), Duration::from_secs(2));
    assert_eq!(retry_wait(&timing, &Ended::Busy { retry_after: None, was_live: false }, &mut failures), Duration::from_secs(60));
    assert_eq!(retry_wait(&timing, &Ended::Busy { retry_after: Some(Duration::from_secs(30)), was_live: false }, &mut failures), Duration::from_secs(30));
    assert_eq!(retry_wait(&timing, &Ended::Down { was_live: false }, &mut failures), Duration::from_secs(16), "the backoff (the fifth try in a row), past 15 s by now");
    assert_eq!(retry_wait(&timing, &Ended::Down { was_live: true }, &mut failures), Duration::from_secs(15), "live a moment ago: 15 s");
    assert_eq!(retry_wait(&timing, &Ended::Missing, &mut failures), Duration::from_secs(600));
    assert_eq!(failures, 0);
    assert_eq!(retry_wait(&timing, &Ended::Refused, &mut failures), Duration::ZERO);
    assert_eq!(retry_wait(&timing, &Ended::Dropped { was_live: true }, &mut failures), Duration::from_secs(1));
}

#[test]
fn retries_double_from_a_second_to_a_minute() {
    let waits: Vec<u64> = (1..=9).map(|n| backoff(&TIMING, n).as_secs()).collect();
    assert_eq!(waits, [1, 2, 4, 8, 16, 32, 60, 60, 60]);
    assert_eq!(backoff(&TIMING, 0), Duration::from_secs(1));
    assert_eq!(backoff(&TIMING, u32::MAX), Duration::from_secs(60));
}

#[test]
fn the_stores_answers_are_told_apart() {
    assert_eq!(store_said(200, &json!({ "ok": true, "message": {} })), StoreSaid::Answer);
    assert_eq!(store_said(200, &json!({ "ok": false, "error": "not_friends" })), StoreSaid::Answer);
    // The Store's own refusal, in the RPC's words, whatever its status.
    assert_eq!(store_said(401, &json!({ "ok": false, "error": "expired" })), StoreSaid::Answer);
    assert_eq!(store_said(429, &Value::Null), StoreSaid::Answer);
    // Not deployed yet, or a page instead of an answer: the RPC directly.
    assert_eq!(store_said(404, &json!({ "error": "not_found" })), StoreSaid::Missing);
    // The Store's own refusals (the website's contract): read as the RPC's.
    assert_eq!(store_said(400, &json!({ "ok": false, "error": "invalid" })), StoreSaid::Answer);
    assert_eq!(store_said(413, &json!({ "ok": false, "error": "too_long" })), StoreSaid::Answer);
    assert_eq!(store_said(429, &json!({ "ok": false, "error": "rate_limited" })), StoreSaid::Answer);
    // No such action, or a database without Friends live (PostgREST's PGRST202): the RPC itself.
    assert_eq!(store_said(404, &json!({ "ok": false, "error": "not_found" })), StoreSaid::Missing);
    assert_eq!(store_said(404, &json!({ "code": "PGRST202", "details": null, "hint": null, "message": "Could not find the function" })), StoreSaid::Missing);
    // A Worker that isn't set up, or whose database didn't answer.
    assert_eq!(store_said(503, &json!({ "error": "unavailable" })), StoreSaid::Down);
    assert_eq!(store_said(503, &json!({ "ok": false, "error": "unavailable" })), StoreSaid::Down);
    assert_eq!(store_said(405, &Value::Null), StoreSaid::Missing);
    assert_eq!(store_said(200, &Value::Null), StoreSaid::Missing);
    assert_eq!(store_said(403, &Value::Null), StoreSaid::Missing);
    assert_eq!(store_said(502, &json!({ "ok": false })), StoreSaid::Down);
    assert_eq!(store_action("flint_message_send"), Some("message_send"));
    assert_eq!(store_action("flint_social_settings"), Some("settings"));
    // Reads stay on the RPCs.
    for read in ["flint_social_state", "flint_message_history", "flint_social_search", "flint_friend_pin", "flint_social_signin_v2"] {
        assert_eq!(store_action(read), None, "{read}");
    }
}

// ---------- pushes applied to the state ----------

#[tokio::test]
async fn a_pushed_message_is_shown_and_announced_once_whatever_comes_after() {
    let fake = Fake::start().await;
    let (steve, alex) = friends(&fake, false, false).await;
    let first = steve.send(ALEX, "first").await;
    let applied = alex.social.apply_live(LiveEvent::Message(first.clone()));
    assert!(applied.changed && !applied.resync);
    assert!(matches!(&applied.events[..], [SocialEvent::Message { name, message }] if name == "Steve" && message.id == first.id));
    assert_eq!(alex.social.snapshot().friends[0].unread, 1);
    // The same push again, then the sync that brings it too: nothing new, never twice.
    assert_eq!(alex.social.apply_live(LiveEvent::Message(first.clone())), Applied::default());
    let step = alex.step().await;
    assert!(step.events.is_empty(), "announced by the push already: {:?}", step.events);
    assert_eq!(alex.ids(STEVE), vec![first.id]);
    assert_eq!(alex.social.snapshot().friends[0].unread, 1, "the server's count agrees");
    // Pushes out of order are kept in order; each announced once.
    let (second, third) = (steve.send(ALEX, "second").await, steve.send(ALEX, "third").await);
    assert_eq!(alex.social.apply_live(LiveEvent::Message(third.clone())).events.len(), 1);
    assert_eq!(alex.social.apply_live(LiveEvent::Message(second.clone())).events.len(), 1);
    assert_eq!(alex.ids(STEVE), vec![first.id, second.id, third.id]);
    // A message the push missed (a drop) comes with the next sync, announced then: none lost.
    let missed = steve.send(ALEX, "missed").await;
    let step = alex.step().await;
    assert!(matches!(&step.events[..], [SocialEvent::Message { message, .. }] if message.id == missed.id), "{:?}", step.events);
    assert_eq!(alex.ids(STEVE), vec![first.id, second.id, third.id, missed.id]);
    // The sender's own message pushed back to it (its other connections get it too) is placed once and isn't news.
    let mine = alex.send(STEVE, "back").await;
    let applied = alex.social.apply_live(LiveEvent::Message(mine.clone()));
    assert!(applied.events.is_empty() && !applied.changed);
    let echo = steve.social.apply_live(LiveEvent::Message(mine.clone()));
    assert_eq!(echo.events.len(), 1);
    assert_eq!(steve.ids(ALEX).iter().filter(|id| **id == mine.id).count(), 1);
}

#[tokio::test]
async fn what_a_push_cant_place_asks_for_a_sync() {
    let fake = Fake::start().await;
    let (steve, alex) = strangers(&fake, false, false).await;
    // From someone not on the list (yet): nothing placed, a sync asked for.
    let stranger = Message { id: 999, from: STEVE.into(), to: ALEX.into(), body: "hi".into(), sensitive: false, at: None, read: false, client_id: None };
    let applied = alex.social.apply_live(LiveEvent::Message(stranger));
    assert!(applied.resync && !applied.changed && applied.events.is_empty());
    assert!(alex.social.apply_live(LiveEvent::Resync).resync);
    // Not to or from this player: passed over.
    let elsewhere = Message { id: 1000, from: "0".repeat(32), to: "1".repeat(32), body: "x".into(), sensitive: false, at: None, read: false, client_id: None };
    assert_eq!(alex.social.apply_live(LiveEvent::Message(elsewhere)), Applied::default());
    // Before any sync for the account: a sync.
    let fresh = launcher(&fake, false, false);
    assert_eq!(fresh.apply_live(LiveEvent::Resync), Applied::default(), "no account yet");
    drop(steve);
}

#[tokio::test]
async fn presence_typing_reads_and_removals_apply_at_once() {
    let fake = Fake::start().await;
    let (steve, alex) = friends(&fake, false, false).await;
    let sent = steve.send(ALEX, "hello").await;
    alex.social.apply_live(LiveEvent::Message(sent.clone()));
    // Steve goes offline, then comes back busy and in game: one "online".
    let offline = LiveEvent::Presence { uuid: STEVE.into(), status: "offline".into(), in_game: Some(false), last_seen: Some(Some(1)) };
    assert!(alex.social.apply_live(offline.clone()).changed);
    assert!(!alex.social.apply_live(offline).changed, "nothing new");
    let back = alex.social.apply_live(LiveEvent::Presence { uuid: STEVE.into(), status: "busy".into(), in_game: Some(true), last_seen: None });
    assert!(matches!(&back.events[..], [SocialEvent::Online { status, .. }] if status == "busy"));
    let friend = alex.social.snapshot().friends[0].clone();
    assert_eq!((friend.status.as_str(), friend.in_game, friend.last_seen), ("busy", true, Some(1)), "a presence without last_seen keeps it");
    alex.social.apply_live(LiveEvent::Presence { uuid: STEVE.into(), status: "busy".into(), in_game: None, last_seen: Some(None) });
    assert_eq!(alex.social.snapshot().friends[0].last_seen, None, "one that says null hides it");
    // Typing shows, and a message from them ends it.
    assert!(alex.social.apply_live(LiveEvent::Typing { uuid: STEVE.into() }).changed);
    assert_eq!(alex.social.snapshot().typing, vec![STEVE.to_owned()]);
    let next = steve.send(ALEX, "typed").await;
    alex.social.apply_live(LiveEvent::Message(next.clone()));
    assert!(alex.social.snapshot().typing.is_empty());
    // A stranger's typing isn't shown.
    assert!(!alex.social.apply_live(LiveEvent::Typing { uuid: "2".repeat(32) }).changed);
    // Read on another of Alex's connections: Alex's count goes down with it.
    assert_eq!(alex.social.snapshot().friends[0].unread, 2);
    alex.social.apply_live(LiveEvent::Read { with: STEVE.into(), up_to: sent.id, by: None });
    let shown = alex.social.snapshot();
    assert_eq!(shown.friends[0].unread, 1);
    assert!(shown.conversations[STEVE].messages.iter().find(|m| m.id == sent.id).unwrap().read);
    // A read receipt (Alex read Steve's): Steve's own message is read; Steve's count isn't touched.
    steve.social.apply_live(LiveEvent::Message(sent.clone()));
    steve.social.apply_live(LiveEvent::Read { with: ALEX.into(), up_to: sent.id, by: Some(ALEX.into()) });
    let shown = steve.social.snapshot();
    assert!(shown.conversations[ALEX].messages.iter().find(|m| m.id == sent.id).unwrap().read);
    assert_eq!(shown.friends[0].unread, 0);
    // Removed: gone with the chat.
    assert!(alex.social.apply_live(LiveEvent::FriendRemoved { uuid: STEVE.into() }).changed);
    let shown = alex.social.snapshot();
    assert!(shown.friends.is_empty() && shown.conversations.is_empty());
}

#[tokio::test]
async fn requests_and_accepts_apply_with_their_events() {
    let fake = Fake::start().await;
    let (steve, alex) = strangers(&fake, false, false).await;
    let request = FriendRequest { id: json!(55), uuid: STEVE.into(), name: "Steve".into(), at: None };
    let applied = alex.social.apply_live(LiveEvent::Request { request: request.clone(), outgoing: false });
    assert!(matches!(&applied.events[..], [SocialEvent::Request { name, .. }] if name == "Steve"));
    assert_eq!(alex.social.apply_live(LiveEvent::Request { request, outgoing: false }), Applied::default(), "once");
    // On Steve's side (another of his connections): outgoing, no event.
    let outgoing = FriendRequest { id: json!(55), uuid: ALEX.into(), name: "Alex".into(), at: None };
    let applied = steve.social.apply_live(LiveEvent::Request { request: outgoing, outgoing: true });
    assert!(applied.changed && applied.events.is_empty());
    assert_eq!(steve.social.snapshot().outgoing.len(), 1);
    // Accepted: the request goes, then the friend comes: "accepted" for Steve (who asked), not for Alex.
    assert!(steve.social.apply_live(LiveEvent::RequestGone { id: json!(55) }).changed);
    assert!(alex.social.apply_live(LiveEvent::RequestGone { id: json!("55") }).changed, "ids compare as the server writes them");
    let alex_friend = Friend { uuid: ALEX.into(), name: "Alex".into(), status: "online".into(), in_game: false, last_seen: None, pinned: false, since: None, unread: 0 };
    let applied = steve.social.apply_live(LiveEvent::FriendAdded(alex_friend.clone()));
    assert!(matches!(&applied.events[..], [SocialEvent::Accepted { name, .. }] if name == "Alex"));
    let steve_friend = Friend { uuid: STEVE.into(), name: "Steve".into(), ..alex_friend.clone() };
    assert!(alex.social.apply_live(LiveEvent::FriendAdded(steve_friend)).events.is_empty());
    // Again: an update, not news.
    assert!(steve.social.apply_live(LiveEvent::FriendAdded(alex_friend)).events.is_empty());
    assert_eq!(steve.social.snapshot().friends.len(), 1);
    assert!(steve.social.snapshot().outgoing.is_empty() && alex.social.snapshot().incoming.is_empty());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_push_during_a_sync_isnt_lost_to_its_older_answer() {
    let fake = Fake::start().await;
    let (_steve, alex) = friends(&fake, false, false).await;
    // A slow sync: its answer is made after the pushes below, without them (the server's state from before).
    fake.with(|w| w.http_delay_ms = 300);
    let syncing = {
        let alex = alex.clone_handle();
        tokio::spawn(async move { alex.step().await })
    };
    tokio::time::sleep(Duration::from_millis(100)).await;
    let notch = "4".repeat(32);
    let request = FriendRequest { id: json!(777), uuid: notch.clone(), name: "Notch".into(), at: None };
    assert_eq!(alex.social.apply_live(LiveEvent::Request { request, outgoing: false }).events.len(), 1);
    let message = Message { id: 5000, from: STEVE.into(), to: ALEX.into(), body: "during".into(), sensitive: false, at: None, read: false, client_id: None };
    assert_eq!(alex.social.apply_live(LiveEvent::Message(message)).events.len(), 1);
    alex.social.apply_live(LiveEvent::Presence { uuid: STEVE.into(), status: "busy".into(), in_game: None, last_seen: None });
    let step = syncing.await.unwrap();
    assert!(step.events.is_empty(), "announced by the pushes: {:?}", step.events);
    let shown = alex.social.snapshot();
    assert_eq!(shown.incoming.iter().map(|r| r.uuid.as_str()).collect::<Vec<_>>(), vec![notch.as_str()]);
    assert!(alex.ids(STEVE).contains(&5000));
    assert_eq!((shown.friends[0].status.as_str(), shown.friends[0].unread), ("busy", 1), "the answer's count didn't have it yet");
    // The next sync is the server's word again.
    fake.with(|w| w.http_delay_ms = 0);
    alex.step().await;
    assert!(alex.social.snapshot().incoming.is_empty());
}

// ---------- the socket, end to end ----------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn two_players_hear_each_other_at_once_through_the_hub() {
    let fake = Fake::start().await;
    let (mut steve, mut alex) = friends(&fake, true, false).await;
    live_both(&mut steve, &mut alex, FAST).await;
    // Live: the sync slows to the safety net (10 s with a chat open, 60 s otherwise).
    assert_eq!(alex.social.poll_interval(), LIVE_IDLE_POLL);
    alex.social.set_focus(Some(STEVE.into()));
    assert_eq!(alex.social.poll_interval(), LIVE_CHAT_POLL);
    fake.with(|w| assert!(w.count("live auth") >= 2));
    for round in 0..5 {
        let sent = steve.send(ALEX, &format!("ping {round}")).await;
        let a = alex.clone_handle();
        until("Alex has it", Duration::from_secs(2), || a.ids(STEVE).contains(&sent.id)).await;
        let reply = alex.send(STEVE, &format!("pong {round}")).await;
        let s = steve.clone_handle();
        until("Steve has it", Duration::from_secs(2), || s.ids(ALEX).contains(&reply.id)).await;
    }
    // Written through the Store; each message shown and announced exactly once, on both sides, after syncs too.
    fake.with(|w| assert_eq!((w.count("store message_send ok"), w.calls("flint_message_send")), (10, 0)));
    alex.social.sync_soon();
    steve.social.sync_soon();
    tokio::time::sleep(Duration::from_millis(200)).await;
    let (on_alex, on_steve) = (alex.ids(STEVE), steve.ids(ALEX));
    assert_eq!((on_alex.len(), on_steve.len()), (10, 10));
    assert!(on_alex.windows(2).all(|w| w[0] < w[1]) && on_alex == on_steve, "in order, the same on both");
    assert_eq!(alex.out.messages().len(), 5);
    assert_eq!(steve.out.messages().len(), 5);
}

impl Player {
    /// A second handle on the same launcher (for closures).
    fn clone_handle(&self) -> Player {
        Player { social: self.social.clone(), mc: self.mc.clone(), out: self.out.clone(), tasks: Vec::new() }
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn requests_accepts_and_removals_arrive_at_once() {
    let fake = Fake::start().await;
    let (mut steve, mut alex) = strangers(&fake, true, false).await;
    live_both(&mut steve, &mut alex, FAST).await;
    assert_eq!(steve.social.request(&*steve.mc, "alex").await, Ok("sent".into()));
    let a = alex.clone_handle();
    until("Alex sees the request", Duration::from_secs(2), || a.social.snapshot().incoming.len() == 1).await;
    assert_eq!(alex.out.count("request"), 1);
    let s = steve.clone_handle();
    until("Steve sees it as sent", Duration::from_secs(2), || s.social.snapshot().outgoing.len() == 1).await;
    let id = alex.social.snapshot().incoming[0].id.clone();
    alex.social.respond(&*alex.mc, &id, true).await.unwrap();
    until("Steve hears it was accepted", Duration::from_secs(2), || s.out.count("accepted") == 1).await;
    until("friends on both sides", Duration::from_secs(2), || s.social.snapshot().friends.len() == 1 && a.social.snapshot().friends.len() == 1).await;
    assert!(steve.social.snapshot().outgoing.is_empty());
    assert_eq!(alex.out.count("accepted"), 0, "the one who accepted isn't told");
    // Removed by Alex: gone for Steve at once.
    alex.social.remove(&*alex.mc, STEVE).await.unwrap();
    until("Steve's list empties", Duration::from_secs(2), || s.social.snapshot().friends.is_empty()).await;
    // Asked again and cancelled: gone from Alex's requests at once.
    steve.social.request(&*steve.mc, "alex").await.unwrap();
    until("asked again", Duration::from_secs(2), || a.social.snapshot().incoming.len() == 1).await;
    let id = steve.social.snapshot().outgoing.first().map(|r| r.id.clone()).unwrap_or_else(|| a.social.snapshot().incoming[0].id.clone());
    steve.social.cancel(&*steve.mc, &id).await.unwrap();
    until("Alex's request goes", Duration::from_secs(2), || a.social.snapshot().incoming.is_empty()).await;
    // A status change reaches friends at once (here, none left: nothing is sent).
    fake.with(|w| assert!(w.count("store friend_cancel ok") == 1 && w.count("store friend_respond ok") == 1));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_dropped_socket_comes_back_and_what_was_missed_arrives_once() {
    let fake = Fake::start().await;
    let (mut steve, mut alex) = friends(&fake, true, false).await;
    live_both(&mut steve, &mut alex, FAST).await;
    let syncs = fake.with(|w| w.calls("flint_social_state"));
    // Both connections cut without a close frame; Steve writes while Alex's is down.
    fake.with(|w| w.end_live(None));
    let a = alex.clone_handle();
    until("Alex notices", Duration::from_secs(2), || !a.social.live_up()).await;
    let sent = steve.send(ALEX, "while you were away").await;
    until("Alex is live again", Duration::from_secs(3), || a.social.live_up()).await;
    // Live again: a sync for what happened meanwhile.
    until("the message arrives", Duration::from_secs(3), || a.ids(STEVE).contains(&sent.id)).await;
    assert!(fake.with(|w| w.calls("flint_social_state")) > syncs, "a sync on coming back");
    assert_eq!(alex.ids(STEVE), vec![sent.id]);
    assert_eq!(alex.out.messages(), vec![sent.id], "announced once");
    // A close from the hub (it restarted): back again too.
    fake.with(|w| w.end_live(Some(1001)));
    let s = steve.clone_handle();
    until("Steve is live again", Duration::from_secs(3), || s.social.live_up() && fake.with(|w| w.live_count(STEVE)) == 1).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_store_that_cant_be_reached_is_retried_with_backoff() {
    let fake = Fake::start().await;
    let (mut steve, _alex) = friends(&fake, true, false).await;
    // Every connection to the fake is dropped unanswered (the sync's too, which then waits 15 s and more).
    fake.with(|w| w.offline = true);
    steve.start(FAST);
    tokio::time::sleep(Duration::from_millis(1200)).await;
    // 50, 100, 200, 400, 400 ms…: a handful of tries in 1.2 s, not hundreds.
    let tries = fake.with(|w| w.live_attempts);
    assert!((3..=9).contains(&tries), "{tries} tries");
    assert!(!steve.social.live_up());
    // Reachable again: live within the next wait.
    fake.with(|w| w.offline = false);
    let s = steve.clone_handle();
    until("live", Duration::from_secs(2), || s.social.live_up()).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_hub_saying_too_much_or_invalid_is_never_a_reconnect_loop() {
    let fake = Fake::start().await;
    let (mut steve, _alex) = friends(&fake, true, false).await;
    steve.start(FAST);
    let s = steve.clone_handle();
    until("live", Duration::from_secs(3), || s.social.live_up()).await;
    // Flooding (4429): at least busy_retry (600 ms here, a minute in the app), then back.
    let before = fake.with(|w| w.live_attempts);
    fake.with(|w| w.end_live(Some(4429)));
    until("closed", Duration::from_secs(2), || !s.social.live_up()).await;
    tokio::time::sleep(Duration::from_millis(400)).await;
    assert_eq!(fake.with(|w| w.live_attempts), before, "not before the wait");
    until("back after the wait", Duration::from_secs(3), || s.social.live_up()).await;
    // Its database unreachable (1013): the same, with down_retry.
    fake.with(|w| w.end_live(Some(1013)));
    until("closed", Duration::from_secs(2), || !s.social.live_up()).await;
    until("back", Duration::from_secs(3), || s.social.live_up()).await;
    // Invalid (4400): only a bug here earns it; not tried again for missing_retry (5 s here, 10 minutes in the app).
    let before = fake.with(|w| w.live_attempts);
    fake.with(|w| w.end_live(Some(4400)));
    until("closed", Duration::from_secs(2), || !s.social.live_up()).await;
    tokio::time::sleep(Duration::from_millis(1500)).await;
    assert_eq!(fake.with(|w| w.live_attempts), before);
    // Not live: the polling of 1.0.3 meanwhile.
    assert_eq!(steve.social.poll_interval(), IDLE_POLL);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_connection_that_died_silently_is_noticed_by_its_ping() {
    let fake = Fake::start().await;
    let (mut steve, mut alex) = friends(&fake, true, false).await;
    live_both(&mut steve, &mut alex, FAST).await;
    let auths = fake.with(|w| w.count("live auth"));
    // The hub stops answering: within a ping and a pong (0.6 s here, 35 s at most in the app) the socket opens again.
    fake.with(|w| w.live_silent = true);
    until("a new connection", Duration::from_secs(3), || fake.with(|w| w.count("live auth")) > auths).await;
    // Each ping was exactly the contract's text (the hub logs only that).
    fake.with(|w| assert!(w.count("live ping") >= 1 && !w.log.iter().any(|l| l.starts_with("live other"))));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_refused_session_isnt_offered_again_but_a_new_one_is() {
    let fake = Fake::start().await;
    let (mut steve, _alex) = friends(&fake, true, false).await;
    fake.with(|w| w.live_refuse = true);
    steve.start(FAST);
    until("refused", Duration::from_secs(2), || fake.with(|w| w.count("live auth")) == 1).await;
    tokio::time::sleep(Duration::from_millis(800)).await;
    // Not again with the same session (here for 10 s; 5 minutes in the app), and never live.
    assert_eq!(fake.with(|w| w.count("live auth")), 1);
    assert!(!steve.social.live_up());
    // The server ended the session: the sync signs in again, and the new session is offered at once.
    fake.with(|w| {
        w.live_refuse = false;
        w.expire_sessions();
    });
    steve.social.sync_soon();
    let s = steve.clone_handle();
    until("live with the new session", Duration::from_secs(3), || s.social.live_up()).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_store_without_friends_live_keeps_todays_polling_and_the_rpcs() {
    let fake = Fake::start().await;
    let (mut steve, alex) = friends(&fake, true, false).await;
    fake.with(|w| w.store_missing = true);
    steve.start(FAST);
    tokio::time::sleep(Duration::from_millis(500)).await;
    assert!(!steve.social.live_up());
    // Asked once, then left for `missing_retry` (10 minutes in the app).
    assert_eq!(fake.with(|w| w.count("live open")), 1);
    assert_eq!(steve.social.poll_interval(), IDLE_POLL);
    steve.social.set_focus(Some(ALEX.into()));
    assert_eq!(steve.social.poll_interval(), CHAT_POLL);
    // Writes: the Store's 404, then the RPC; after that straight to the RPC.
    let first = steve.send(ALEX, "one").await;
    let second = steve.send(ALEX, "two").await;
    fake.with(|w| assert_eq!((w.count("store message_send 404"), w.count("flint_message_send ok")), (1, 2)));
    alex.step().await;
    assert_eq!(alex.ids(STEVE), vec![first.id, second.id]);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn typing_goes_out_at_most_every_three_seconds_and_shows_for_a_moment() {
    let fake = Fake::start().await;
    let (mut steve, mut alex) = friends(&fake, true, false).await;
    // Not live: nothing is sent.
    assert!(!steve.social.typing(ALEX));
    live_both(&mut steve, &mut alex, FAST).await;
    assert!(steve.social.typing(ALEX));
    assert!(!steve.social.typing(ALEX), "once every 3 s");
    assert!(!steve.social.typing(&"3".repeat(32)), "only to a friend");
    let a = alex.clone_handle();
    until("Alex sees Steve typing", Duration::from_secs(2), || a.social.snapshot().typing == vec![STEVE.to_owned()]).await;
    fake.with(|w| assert_eq!(w.log.iter().filter(|l| l.starts_with("live typing")).count(), 1));
    // It runs out on its own (5 s; shortened here), and the window is told.
    alex.social.inner.lock().unwrap().typing.insert(STEVE.into(), Instant::now() + Duration::from_millis(50));
    until("it runs out", Duration::from_secs(2), || a.social.snapshot().typing.is_empty()).await;
}

#[tokio::test]
async fn a_game_waiting_on_the_state_hears_of_a_push_at_once() {
    let fake = Fake::start().await;
    let (steve, alex) = friends(&fake, false, false).await;
    let (_, revision) = alex.social.game_answer(ALEX, Some(STEVE));
    let waiter = {
        let social = alex.social.clone();
        tokio::spawn(async move {
            let started = Instant::now();
            social.changed_since(revision, Duration::from_secs(20)).await;
            (started.elapsed(), social.game_answer(ALEX, Some(STEVE)))
        })
    };
    tokio::time::sleep(Duration::from_millis(30)).await;
    let sent = steve.send(ALEX, "for the game").await;
    let applied = alex.social.apply_live(LiveEvent::Message(sent.clone()));
    alex.social.deliver(applied, &*alex.out);
    let (waited, (view, after)) = waiter.await.unwrap();
    assert!(waited < Duration::from_secs(1), "{waited:?}");
    assert!(after != revision);
    assert_eq!(view.chat.unwrap().messages.last().map(|m| m.id), Some(sent.id));
}

// ---------- latency, by hand ----------

fn summary(label: &str, mut samples: Vec<Duration>) -> String {
    samples.sort();
    let ms = |d: Duration| d.as_secs_f64() * 1000.0;
    let pick = |q: f64| samples[((samples.len() as f64 - 1.0) * q).round() as usize];
    let mean = samples.iter().map(|d| ms(*d)).sum::<f64>() / samples.len() as f64;
    format!("{label:<58} n={:<3} mean {:>8.1} ms  p50 {:>8.1}  p95 {:>8.1}  max {:>8.1}", samples.len(), mean, ms(pick(0.5)), ms(pick(0.95)), ms(*samples.last().unwrap()))
}

/// The app's own pacing from here (set up unpaced, so the setup's syncs go through at once).
fn paced(player: &mut Player) {
    Arc::get_mut(&mut player.social).expect("not started yet").pacing = PACING;
}

/// One message from Steve to Alex: from the send to Alex's state having it.
async fn one_message(steve: &Player, alex: &Player, body: &str) -> Duration {
    let started = Instant::now();
    let sent = steve.send(ALEX, body).await;
    let a = alex.clone_handle();
    until("delivery", Duration::from_secs(40), || a.ids(STEVE).contains(&sent.id)).await;
    started.elapsed()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "measures end-to-end latency, ~2 minutes: cargo test measure_latency -- --ignored --nocapture"]
async fn measure_latency() {
    use rand::Rng;
    let mut lines = Vec::new();
    // Before: polling only (1.0.3's way), the app's own pacing; Alex with Steve's chat open (4 s), then with none (15 s).
    {
        let fake = Fake::start().await;
        let (mut steve, mut alex) = friends(&fake, false, false).await;
        paced(&mut steve);
        paced(&mut alex);
        steve.start(TIMING);
        alex.start(TIMING);
        alex.social.set_focus(Some(STEVE.into()));
        let mut open = Vec::new();
        for n in 0..8 {
            tokio::time::sleep(Duration::from_millis(rand::thread_rng().gen_range(0..4000))).await;
            open.push(one_message(&steve, &alex, &format!("open {n}")).await);
        }
        lines.push(summary("before: polling, chat open (4 s)", open));
        alex.social.set_focus(None);
        tokio::time::sleep(Duration::from_secs(5)).await;
        let mut idle = Vec::new();
        for n in 0..4 {
            tokio::time::sleep(Duration::from_millis(rand::thread_rng().gen_range(0..15000))).await;
            idle.push(one_message(&steve, &alex, &format!("idle {n}")).await);
        }
        lines.push(summary("before: polling, no chat open (15 s)", idle));
    }
    // After: the Store's push, on loopback (the software's own share), then with network-like delays.
    for (label, http_ms, push_ms) in [("after: push, loopback", 0, 0), ("after: push, +60 ms per HTTP call, +25 ms per push", 60, 25)] {
        let fake = Fake::start().await;
        let (mut steve, mut alex) = friends(&fake, true, false).await;
        paced(&mut steve);
        paced(&mut alex);
        steve.start(TIMING);
        alex.start(TIMING);
        let (s, a) = (steve.social.clone(), alex.social.clone());
        until("live", Duration::from_secs(5), || s.live_up() && a.live_up()).await;
        fake.with(|w| {
            w.http_delay_ms = http_ms;
            w.push_delay_ms = push_ms;
        });
        let mut samples = Vec::new();
        for n in 0..40 {
            tokio::time::sleep(Duration::from_millis(rand::thread_rng().gen_range(0..50))).await;
            samples.push(one_message(&steve, &alex, &format!("live {n}")).await);
        }
        lines.push(summary(&format!("{label}: message"), samples));
        // Requests and accepts, the same way.
        let (mut asked, mut accepted) = (Vec::new(), Vec::new());
        for _ in 0..5 {
            alex.social.remove(&*alex.mc, STEVE).await.unwrap();
            until("removed", Duration::from_secs(5), || s.snapshot().friends.is_empty()).await;
            let started = Instant::now();
            steve.social.request(&*steve.mc, "alex").await.unwrap();
            until("request", Duration::from_secs(5), || a.snapshot().incoming.len() == 1).await;
            asked.push(started.elapsed());
            let id = a.snapshot().incoming[0].id.clone();
            let started = Instant::now();
            alex.social.respond(&*alex.mc, &id, true).await.unwrap();
            until("accepted", Duration::from_secs(5), || s.snapshot().friends.len() == 1).await;
            accepted.push(started.elapsed());
        }
        lines.push(summary(&format!("{label}: friend request"), asked));
        lines.push(summary(&format!("{label}: accept"), accepted));
    }
    // The game's share: the launcher's state changes, a game waiting on it is answered.
    {
        let fake = Fake::start().await;
        let (_steve, alex) = friends(&fake, false, false).await;
        let mut samples = Vec::new();
        for _ in 0..40 {
            let revision = alex.social.revision();
            let social = alex.social.clone();
            let waiter = tokio::spawn(async move {
                social.changed_since(revision, Duration::from_secs(5)).await;
                Instant::now()
            });
            tokio::time::sleep(Duration::from_millis(5)).await;
            let started = Instant::now();
            alex.social.changed();
            samples.push(waiter.await.unwrap() - started);
        }
        lines.push(summary("game link: launcher change -> waiting game request (in process)", samples));
    }
    println!("\n{}\n", lines.join("\n"));
}

// ---------- latency against the real hub, by hand ----------

/// A launcher whose account already has a Friends session (issued by the local demo backend): no Mojang involved.
struct Kept {
    who: Identity,
    session: SavedSession,
}

impl Minecraft for Kept {
    fn account(&self) -> Option<Identity> {
        Some(self.who.clone())
    }
    fn access_token<'a>(&'a self, _uuid: &'a str) -> TokenFuture<'a> {
        Box::pin(async { None })
    }
    fn saved_session(&self, _uuid: &str) -> Option<SavedSession> {
        Some(self.session.clone())
    }
}

struct HubPlayer {
    social: Arc<Social>,
    mc: Arc<Kept>,
    out: Arc<Recorder>,
    tasks: Vec<tokio::task::JoinHandle<()>>,
}

impl Drop for HubPlayer {
    fn drop(&mut self) {
        for task in &self.tasks {
            task.abort();
        }
    }
}

impl HubPlayer {
    /// `spec`: "uuid,name,token".
    fn new(spec: &str, supabase: &str, key: &str, store: &str) -> Self {
        let parts: Vec<&str> = spec.split(',').collect();
        let uuid = canonical(parts[0]).expect("a uuid");
        let session = SavedSession { token: parts[2].trim().to_owned(), expires_at: crate::store::now_millis() as i64 + 6 * 24 * 3600 * 1000 };
        let mc = Kept { who: Identity { uuid, name: parts[1].trim().to_owned() }, session };
        let store = crate::store::Endpoints { store: crate::store::store_origin(store).expect("a Store origin"), ..crate::store::Endpoints::production() };
        let social = Social::new(build_clients().0, Some(Endpoints::new(supabase, key).expect("a local project"))).with_store(store);
        Self { social: Arc::new(social), mc: Arc::new(mc), out: Arc::default(), tasks: Vec::new() }
    }
    fn uuid(&self) -> String {
        self.mc.who.uuid.clone()
    }
    fn start(&mut self) {
        let (social, mc, out) = (self.social.clone(), self.mc.clone(), self.out.clone());
        self.tasks.push(tokio::spawn(async move { run_sync(&social, &*mc, &*out).await }));
        let (social, out) = (self.social.clone(), self.out.clone());
        self.tasks.push(tokio::spawn(async move { live::run(&social, TIMING, &*out).await }));
    }
    fn friends_with(&self, other: &str) -> bool {
        self.social.snapshot().friends.iter().any(|f| f.uuid == other)
    }
}

/// From `from`'s send to `to`'s state having it; a send the server slowed down is tried again a moment later.
async fn hub_message(from: &HubPlayer, to: &HubPlayer, body: &str) -> Duration {
    loop {
        let started = Instant::now();
        match from.social.send(&*from.mc, &to.uuid(), body, false, None).await {
            Ok(sent) => {
                let (other, me) = (to.social.clone(), from.uuid());
                until("delivery", Duration::from_secs(30), || other.snapshot().conversations.get(&me).is_some_and(|c| c.messages.iter().any(|m| m.id == sent.id))).await;
                return started.elapsed();
            }
            Err(Fail::Refused(code)) if code == "rate_limited" => {
                println!("(rate limited; again in 2 s)");
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
            Err(failure) => panic!("send: {failure:?}"),
        }
    }
}

/// Measures this client against the website's local Store hub and Supabase (the demo backend with Backend 7800 and a
/// Store preview). `FLINT_HUB_PHASE=up` measures the push; `down` (the Store stopped) measures the fallback.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "needs the website's local demo backend and Store: FLINT_HUB_SUPABASE, FLINT_HUB_KEY, FLINT_HUB_STORE, FLINT_HUB_A, FLINT_HUB_B, FLINT_HUB_PHASE"]
async fn measure_local_hub() {
    let var = |name: &str| std::env::var(name).unwrap_or_else(|_| panic!("{name} is needed"));
    let (supabase, key, store) = (var("FLINT_HUB_SUPABASE"), var("FLINT_HUB_KEY"), var("FLINT_HUB_STORE"));
    let up = var("FLINT_HUB_PHASE") == "up";
    let n: usize = std::env::var("FLINT_HUB_N").ok().and_then(|v| v.parse().ok()).unwrap_or(30);
    let mut steve = HubPlayer::new(&var("FLINT_HUB_A"), &supabase, &key, &store);
    let mut alex = HubPlayer::new(&var("FLINT_HUB_B"), &supabase, &key, &store);
    let (s, a) = (steve.social.clone(), alex.social.clone());
    steve.start();
    alex.start();
    let ready = until("both synced", Duration::from_secs(20), || s.snapshot().phase == Phase::Ready && a.snapshot().phase == Phase::Ready).await;
    println!("synced in {} ms (with the kept sessions)", ready.as_millis());
    let mut lines = Vec::new();
    if up {
        let live = until("both live", Duration::from_secs(20), || s.live_up() && a.live_up()).await;
        println!("live in {} ms more", live.as_millis());
        assert_eq!((s.poll_interval(), a.poll_interval()), (LIVE_IDLE_POLL, LIVE_IDLE_POLL));
        // Friends first (through the Store, so this is pushed too).
        if !steve.friends_with(&alex.uuid()) {
            let name = alex.mc.who.name.clone();
            steve.social.request(&*steve.mc, &name).await.expect("request");
            until("the request", Duration::from_secs(10), || !a.snapshot().incoming.is_empty()).await;
            let id = a.snapshot().incoming[0].id.clone();
            alex.social.respond(&*alex.mc, &id, true).await.expect("accept");
            let (steve_uuid, alex_uuid) = (steve.uuid(), alex.uuid());
            until("friends", Duration::from_secs(10), || s.snapshot().friends.iter().any(|f| f.uuid == alex_uuid) && a.snapshot().friends.iter().any(|f| f.uuid == steve_uuid)).await;
        }
        let mut there = Vec::new();
        let mut back = Vec::new();
        for i in 0..n {
            there.push(hub_message(&steve, &alex, &format!("live {i}")).await);
            back.push(hub_message(&alex, &steve, &format!("back {i}")).await);
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        lines.push(summary("hub up: message Steve -> Alex", there));
        lines.push(summary("hub up: message Alex -> Steve", back));
        // Each message once on both sides, in order, after a sync too.
        s.sync_soon();
        a.sync_soon();
        tokio::time::sleep(Duration::from_secs(2)).await;
        let ids = |p: &Social, with: &str| p.snapshot().conversations.get(with).map(|c| c.messages.iter().map(|m| m.id).collect::<Vec<_>>()).unwrap_or_default();
        let (on_steve, on_alex) = (ids(&s, &alex.uuid()), ids(&a, &steve.uuid()));
        assert_eq!(on_steve, on_alex, "the same on both");
        assert!(on_steve.windows(2).all(|w| w[0] < w[1]), "in order, no repeats");
        let announced = alex.out.messages();
        let unique: HashSet<i64> = announced.iter().copied().collect();
        assert_eq!(announced.len(), unique.len(), "never announced twice");
        // Typing, at most one every 3 s.
        let mut typing = Vec::new();
        for _ in 0..5 {
            a.inner.lock().unwrap().typing.clear();
            tokio::time::sleep(Duration::from_millis(3100)).await;
            let started = Instant::now();
            assert!(s.typing(&alex.uuid()));
            let me = steve.uuid();
            until("typing", Duration::from_secs(5), || a.snapshot().typing.contains(&me)).await;
            typing.push(started.elapsed());
        }
        lines.push(summary("hub up: typing Steve -> Alex", typing));
        // Requests and accepts: Alex removes Steve, Steve asks again, Alex accepts.
        let (mut asked, mut accepted, mut removed) = (Vec::new(), Vec::new(), Vec::new());
        for _ in 0..5 {
            let started = Instant::now();
            alex.social.remove(&*alex.mc, &steve.uuid()).await.expect("remove");
            let alex_uuid = alex.uuid();
            until("removed", Duration::from_secs(10), || !s.snapshot().friends.iter().any(|f| f.uuid == alex_uuid)).await;
            removed.push(started.elapsed());
            let started = Instant::now();
            steve.social.request(&*steve.mc, &alex.mc.who.name).await.expect("request");
            until("the request", Duration::from_secs(10), || !a.snapshot().incoming.is_empty()).await;
            asked.push(started.elapsed());
            let id = a.snapshot().incoming[0].id.clone();
            let accepts_before = steve.out.count("accepted");
            let started = Instant::now();
            alex.social.respond(&*alex.mc, &id, true).await.expect("accept");
            let out = steve.out.clone();
            until("accepted", Duration::from_secs(10), || out.count("accepted") > accepts_before).await;
            accepted.push(started.elapsed());
        }
        lines.push(summary("hub up: friend removed (Alex -> Steve)", removed));
        lines.push(summary("hub up: friend request (Steve -> Alex)", asked));
        lines.push(summary("hub up: accept (Alex -> Steve, Accepted event)", accepted));
    } else {
        // The Store is down: no socket, writes straight to the RPCs, and polling at 4 s with the chat open.
        tokio::time::sleep(Duration::from_secs(2)).await;
        assert!(!s.live_up() && !a.live_up());
        // As the app's social_focus does when a chat opens: the sync follows at once.
        a.set_focus(Some(steve.uuid()));
        a.wake.notify_one();
        assert_eq!(a.poll_interval(), CHAT_POLL);
        let mut samples = Vec::new();
        for i in 0..n.min(8) {
            use rand::Rng;
            tokio::time::sleep(Duration::from_millis(rand::thread_rng().gen_range(0..4000))).await;
            samples.push(hub_message(&steve, &alex, &format!("down {i}")).await);
        }
        lines.push(summary("hub down: message Steve -> Alex (RPC, polled, chat open)", samples));
        a.set_focus(None);
    }
    println!("\n{}\n", lines.join("\n"));
}
