//! Friends against the fake server in `fake.rs`: two players, each with their own `Social` (two launchers).
use super::fake::{Fake, KEY};
use super::*;
use crate::core::build_clients;
use std::collections::HashMap;

const STEVE: &str = "8667ba71b85a4004af54457a9734eed7";
const ALEX: &str = "ec561538f3fd461daff5086b22154bce";

/// A launcher's accounts: the active one and the Minecraft tokens it would get for each. `keeping` ones also keep what
/// the app keeps on the computer (saved sessions, the friends view), shared by every `Social` made with them, as by every
/// run of the app.
struct TestMinecraft {
    active: Mutex<Option<Identity>>,
    tokens: HashMap<String, String>,
    keeping: bool,
    sessions: Mutex<HashMap<String, SavedSession>>,
    views: Mutex<HashMap<String, CachedView>>,
}

impl TestMinecraft {
    fn new(uuid: &str, name: &str) -> Self {
        let tokens = HashMap::from([(STEVE.to_owned(), "mc-token-steve".to_owned()), (ALEX.to_owned(), "mc-token-alex".to_owned())]);
        Self { active: Mutex::new(Some(Identity { uuid: uuid.into(), name: name.into() })), tokens, keeping: false, sessions: Mutex::default(), views: Mutex::default() }
    }
    fn keeping(uuid: &str, name: &str) -> Self {
        Self { keeping: true, ..Self::new(uuid, name) }
    }
    fn switch(&self, who: Option<(&str, &str)>) {
        *self.active.lock().unwrap() = who.map(|(uuid, name)| Identity { uuid: uuid.into(), name: name.into() });
    }
}

impl Minecraft for TestMinecraft {
    fn account(&self) -> Option<Identity> {
        self.active.lock().unwrap().clone()
    }
    fn access_token<'a>(&'a self, uuid: &'a str) -> TokenFuture<'a> {
        let token = self.tokens.get(uuid).cloned();
        Box::pin(async move { token })
    }
    fn saved_session(&self, uuid: &str) -> Option<SavedSession> {
        self.sessions.lock().unwrap().get(uuid).cloned()
    }
    fn save_session(&self, uuid: &str, session: &SavedSession) {
        if self.keeping {
            self.sessions.lock().unwrap().insert(uuid.into(), session.clone());
        }
    }
    fn forget_session(&self, uuid: &str) {
        self.sessions.lock().unwrap().remove(uuid);
    }
    fn cached_view(&self, uuid: &str) -> Option<CachedView> {
        self.views.lock().unwrap().get(uuid).cloned()
    }
    fn save_view(&self, uuid: &str, view: &CachedView) {
        if self.keeping {
            self.views.lock().unwrap().insert(uuid.into(), view.clone());
        }
    }
}

/// A launcher talking to the fake, unpaced so a test can step as fast as it likes (`paced` keeps the app's pacing).
fn social(fake: &Fake) -> Social {
    let mut social = paced(fake);
    social.pacing = Pacing { min_gap: Duration::ZERO, resign_gap: Duration::ZERO };
    social
}

fn paced(fake: &Fake) -> Social {
    let mut endpoints = Endpoints::new(&fake.url(), KEY).expect("a local project is usable");
    endpoints.mojang = Url::parse(&fake.url()).unwrap();
    Social::new(build_clients().0, Some(endpoints))
}

struct Rig {
    fake: Fake,
    steve: Social,
    steve_mc: TestMinecraft,
    alex: Social,
    alex_mc: TestMinecraft,
}

async fn rig() -> Rig {
    let fake = Fake::start().await;
    fake.with(|w| {
        w.add_mojang_account("mc-token-steve", STEVE, "Steve");
        w.add_mojang_account("mc-token-alex", ALEX, "Alex");
    });
    Rig { steve: social(&fake), alex: social(&fake), steve_mc: TestMinecraft::new(STEVE, "Steve"), alex_mc: TestMinecraft::new(ALEX, "Alex"), fake }
}

impl Rig {
    async fn steve(&self) -> Step {
        self.steve.step(&self.steve_mc).await
    }
    async fn alex(&self) -> Step {
        self.alex.step(&self.alex_mc).await
    }
    /// Both signed in and synced once, then friends (Steve asked, Alex accepted), both synced again.
    async fn friends(&self) {
        self.steve().await;
        self.alex().await;
        assert_eq!(self.steve.request(&self.steve_mc, "alex").await, Ok("sent".into()));
        self.alex().await;
        let id = self.alex.snapshot().incoming[0].id.clone();
        self.alex.respond(&self.alex_mc, &id, true).await.unwrap();
        self.steve().await;
        self.alex().await;
    }
}

fn kinds(step: &Step) -> Vec<&'static str> {
    step.events
        .iter()
        .map(|e| match e {
            SocialEvent::Request { .. } => "request",
            SocialEvent::Accepted { .. } => "accepted",
            SocialEvent::Message { .. } => "message",
            SocialEvent::Online { .. } => "online",
        })
        .collect()
}

#[tokio::test]
async fn a_burst_of_503s_from_mojang_is_asked_again_and_signing_in_goes_through() {
    let rig = rig().await;
    rig.fake.with(|w| w.mojang_busy = 2);
    rig.steve().await;
    assert_eq!(rig.steve.snapshot().phase, Phase::Ready);
    rig.fake.with(|w| assert_eq!((w.count("join 503"), w.count("join 204")), (2, 1)));
}

#[tokio::test]
async fn mojang_busy_on_every_try_waits_like_a_rate_limit_without_hammering_it() {
    let rig = rig().await;
    rig.fake.with(|w| w.mojang_busy = 10);
    let step = rig.steve().await;
    rig.fake.with(|w| assert_eq!(w.count("join 503"), 3, "three tries, then the step waits"));
    assert!(step.wait >= IDLE_POLL);
    assert_eq!(rig.steve.snapshot().phase, Phase::Error);
}

#[tokio::test]
async fn signing_in_proves_the_account_at_mojang_and_the_first_sync_is_ready_and_silent() {
    let rig = rig().await;
    assert_eq!(rig.steve.snapshot().phase, Phase::SignedOut);
    let step = rig.steve().await;
    assert!(step.events.is_empty());
    assert_eq!(step.wait, IDLE_POLL);
    let snapshot = rig.steve.snapshot();
    assert_eq!((snapshot.phase, snapshot.configured, snapshot.offline), (Phase::Ready, true, false));
    assert_eq!(snapshot.account.as_deref(), Some(STEVE));
    assert_eq!(snapshot.me.as_ref().map(|m| (m.uuid.as_str(), m.name.as_str(), m.status.as_str())), Some((STEVE, "Steve", "online")));
    rig.fake.with(|w| {
        assert_eq!(w.count("join 204"), 1, "Mojang saw one join");
        assert_eq!(w.count("flint_signin_challenge ok"), 1, "a challenge from Flint's server, never a server id Flint picked");
        assert_eq!(w.count("flint_social_signin_v2 ok"), 1);
        assert_eq!(w.calls("flint_social_signin"), 0, "the locked-down sign-in is never called");
        assert_eq!(w.count("flint_social_state ok"), 1);
    });
    // The session is reused.
    rig.steve().await;
    rig.fake.with(|w| assert_eq!((w.count("join 204"), w.count("flint_social_state ok")), (1, 2)));
    // An open chat polls faster.
    assert!(rig.steve.set_focus(Some(dashed_alex())));
    assert_eq!(rig.steve().await.wait, CHAT_POLL);
    assert_eq!(rig.steve.snapshot().focus.as_deref(), Some(ALEX));
}

fn dashed_alex() -> String {
    super::fake::dashed(ALEX)
}

#[tokio::test]
async fn a_player_mojang_doesnt_confirm_never_gets_a_session() {
    let rig = rig().await;
    rig.fake.with(|w| {
        w.mojang_tokens.clear();
    });
    rig.steve().await;
    let snapshot = rig.steve.snapshot();
    assert_eq!((snapshot.phase, snapshot.error.as_deref()), (Phase::Error, Some("mojang")));
    rig.fake.with(|w| assert_eq!(w.calls("flint_social_signin_v2"), 0, "nothing is asked of Friends without Mojang's join"));
}

#[tokio::test]
async fn a_request_accepted_makes_two_friends_with_events_on_both_sides() {
    let rig = rig().await;
    rig.steve().await;
    rig.alex().await;
    assert_eq!(rig.steve.request(&rig.steve_mc, "Nobody").await, Err(Fail::Refused("not_on_flint".into())));
    assert_eq!(rig.steve.request(&rig.steve_mc, "steve").await, Err(Fail::Refused("self".into())));
    assert_eq!(rig.steve.request(&rig.steve_mc, "not a name!").await, Err(Fail::Refused("not_on_flint".into())));

    let found = rig.steve.search(&rig.steve_mc, "al").await.unwrap();
    assert_eq!(found.iter().map(|p| (p.uuid.as_str(), p.relation.as_str(), p.accepts)).collect::<Vec<_>>(), [(ALEX, "none", true)]);
    assert_eq!(rig.steve.request(&rig.steve_mc, "alex").await, Ok("sent".into()));
    assert_eq!(rig.steve.request(&rig.steve_mc, "Alex").await, Err(Fail::Refused("already_sent".into())));
    assert!(kinds(&rig.steve().await).is_empty());
    assert_eq!(rig.steve.snapshot().outgoing.iter().map(|r| r.uuid.as_str()).collect::<Vec<_>>(), [ALEX]);

    let step = rig.alex().await;
    assert_eq!(kinds(&step), ["request"]);
    let SocialEvent::Request { id, uuid, name } = &step.events[0] else { unreachable!() };
    assert_eq!((uuid.as_str(), name.as_str()), (STEVE, "Steve"));
    assert_eq!(rig.alex.search(&rig.alex_mc, "ste").await.unwrap()[0].relation, "incoming");
    // Asked again, the same request is no news.
    assert!(rig.alex().await.events.is_empty());

    rig.alex.respond(&rig.alex_mc, id, true).await.unwrap();
    assert!(rig.alex.snapshot().incoming.is_empty(), "gone at once");
    let alex_step = rig.alex().await;
    assert!(alex_step.events.is_empty(), "accepting isn't news to the one who accepted");
    assert_eq!(rig.alex.snapshot().friends.iter().map(|f| f.uuid.as_str()).collect::<Vec<_>>(), [STEVE]);

    let step = rig.steve().await;
    assert_eq!(kinds(&step), ["accepted"]);
    let snapshot = rig.steve.snapshot();
    assert!(snapshot.outgoing.is_empty());
    let friend = &snapshot.friends[0];
    assert_eq!((friend.uuid.as_str(), friend.name.as_str(), friend.status.as_str()), (ALEX, "Alex", "online"));
    assert!(friend.since.is_some_and(|s| s > 1_700_000_000_000), "times arrive in milliseconds: {:?}", friend.since);
    assert_eq!(rig.steve.request(&rig.steve_mc, "alex").await, Err(Fail::Refused("already_friends".into())));
}

#[tokio::test]
async fn asking_someone_who_already_asked_you_makes_you_friends() {
    let rig = rig().await;
    rig.steve().await;
    rig.alex().await;
    assert_eq!(rig.steve.request(&rig.steve_mc, "alex").await, Ok("sent".into()));
    assert_eq!(rig.alex.request(&rig.alex_mc, "steve").await, Ok("accepted".into()));
    rig.alex().await;
    assert_eq!(rig.alex.snapshot().friends.len(), 1);

    // Declining and cancelling.
    rig.alex.remove(&rig.alex_mc, STEVE).await.unwrap();
    assert!(rig.alex.snapshot().friends.is_empty());
    rig.steve().await;
    assert!(rig.steve.snapshot().friends.is_empty(), "removed on both sides");
    rig.steve.request(&rig.steve_mc, "alex").await.unwrap();
    rig.steve().await;
    let id = rig.steve.snapshot().outgoing[0].id.clone();
    rig.steve.cancel(&rig.steve_mc, &id).await.unwrap();
    assert!(rig.steve.snapshot().outgoing.is_empty());
    rig.alex().await;
    assert!(rig.alex.snapshot().incoming.is_empty());
    rig.fake.with(|w| w.players.get_mut(ALEX).unwrap().allow_requests = false);
    assert_eq!(rig.steve.request(&rig.steve_mc, "alex").await, Err(Fail::Refused("not_accepting".into())));
}

#[tokio::test]
async fn messages_arrive_as_events_count_as_unread_and_are_read() {
    let rig = rig().await;
    rig.friends().await;
    assert_eq!(rig.steve.send(&rig.steve_mc, ALEX, "   ", false, None).await, Err(Fail::Refused("empty".into())));
    assert_eq!(rig.steve.send(&rig.steve_mc, ALEX, &"a".repeat(1001), false, None).await, Err(Fail::Refused("too_long".into())));
    let first = rig.steve.send(&rig.steve_mc, ALEX, "want to play bedwars?", false, None).await.unwrap();
    assert_eq!((first.from.as_str(), first.to.as_str(), first.sensitive), (STEVE, ALEX, false));
    assert_eq!(rig.steve.snapshot().conversations[ALEX].messages, std::slice::from_ref(&first), "shown at once on the sender's side");
    let private = rig.steve.send(&rig.steve_mc, &dashed_alex(), "my password is hunter2", true, None).await.unwrap();
    assert!(private.sensitive);
    let email = rig.steve.send(&rig.steve_mc, ALEX, "steve@example.com", false, None).await.unwrap();
    assert!(email.sensitive, "the server's flag is kept");

    // Steve's own messages are no news to Steve.
    assert!(rig.steve().await.events.is_empty());
    assert_eq!(rig.steve.snapshot().conversations[ALEX].messages.len(), 3, "no repeats after the sync");

    let step = rig.alex().await;
    assert_eq!(kinds(&step), ["message", "message", "message"]);
    let SocialEvent::Message { name, message } = &step.events[1] else { unreachable!() };
    assert_eq!((name.as_str(), message.body.as_str(), message.sensitive), ("Steve", "my password is hunter2", true));
    let snapshot = rig.alex.snapshot();
    assert_eq!(snapshot.friends[0].unread, 3);
    assert_eq!(snapshot.conversations[STEVE].messages.iter().map(|m| m.id).collect::<Vec<_>>(), [first.id, private.id, email.id]);

    rig.alex.read(&rig.alex_mc, STEVE, private.id).await.unwrap();
    assert_eq!(rig.alex.snapshot().friends[0].unread, 1, "read up to the second");
    rig.alex().await;
    assert_eq!(rig.alex.snapshot().friends[0].unread, 1, "the server agrees");
    rig.alex.read(&rig.alex_mc, STEVE, email.id).await.unwrap();
    assert!(kinds(&rig.alex().await).is_empty());
    assert_eq!(rig.alex.snapshot().friends[0].unread, 0);

    // A reply comes back to Steve.
    rig.alex.send(&rig.alex_mc, STEVE, "sure, 5 min", false, None).await.unwrap();
    assert_eq!(kinds(&rig.steve().await), ["message"]);
    assert_eq!(rig.fake.with(|w| w.calls("flint_message_send")), 4, "empty and too long never left Flint");
}

#[tokio::test]
async fn a_restarted_launcher_says_nothing_about_old_messages_and_loads_the_history() {
    let rig = rig().await;
    rig.friends().await;
    for i in 0..230 {
        rig.steve.send(&rig.steve_mc, ALEX, &format!("message {i}"), false, None).await.unwrap();
    }
    assert_eq!(rig.steve.snapshot().conversations[ALEX].messages.len(), CONVERSATION_CAP, "the most recent 200 are kept");
    assert_eq!(rig.steve.snapshot().conversations[ALEX].messages.last().unwrap().body, "message 229");

    // Alex's launcher starts (a new process): the first sync is silent, whatever it brings.
    let alex = social(&rig.fake);
    let step = alex.step(&rig.alex_mc).await;
    assert!(step.events.is_empty());
    let snapshot = alex.snapshot();
    assert_eq!(snapshot.friends[0].unread, 230);
    assert_eq!(snapshot.conversations[STEVE].messages.len(), CONVERSATION_CAP);

    let latest = alex.history(&rig.alex_mc, STEVE, None, Some(50)).await.unwrap();
    assert_eq!((latest.len(), latest[49].body.as_str()), (50, "message 229"));
    assert!(alex.snapshot().conversations[STEVE].loaded);
    let older = alex.history(&rig.alex_mc, STEVE, Some(latest[0].id), Some(50)).await.unwrap();
    assert_eq!((older.first().map(|m| m.body.as_str()), older.last().map(|m| m.body.as_str())), (Some("message 130"), Some("message 179")));
    assert_eq!(alex.snapshot().conversations[STEVE].messages.len(), CONVERSATION_CAP, "older pages aren't kept");
}

#[tokio::test]
async fn coming_online_is_an_event_but_never_on_the_first_sync() {
    let rig = rig().await;
    rig.friends().await;
    rig.fake.with(|w| w.set_online(ALEX, false));
    assert!(rig.steve().await.events.is_empty());
    let alex = &rig.steve.snapshot().friends[0];
    assert_eq!(alex.status, "offline");
    assert!(alex.last_seen.is_some());

    rig.fake.with(|w| {
        w.set_online(ALEX, true);
        w.players.get_mut(ALEX).unwrap().status = "busy".into();
        w.players.get_mut(ALEX).unwrap().in_game = true;
    });
    let step = rig.steve().await;
    assert_eq!(step.events, [SocialEvent::Online { uuid: ALEX.into(), name: "Alex".into(), status: "busy".into() }]);
    assert!(rig.steve.snapshot().friends[0].in_game);
    assert!(rig.steve().await.events.is_empty(), "still online is no news");

    // Invisible is offline to friends: going invisible and back is "online" again.
    rig.fake.with(|w| w.players.get_mut(ALEX).unwrap().status = "invisible".into());
    rig.steve().await;
    assert_eq!(rig.steve.snapshot().friends[0].status, "offline");

    // A new launcher run with Alex already online: no event on its first sync.
    rig.fake.with(|w| w.players.get_mut(ALEX).unwrap().status = "online".into());
    let steve = social(&rig.fake);
    assert!(steve.step(&rig.steve_mc).await.events.is_empty());
    assert_eq!(steve.snapshot().friends[0].status, "online");
}

#[tokio::test]
async fn an_expired_session_signs_in_again_without_a_gap() {
    let rig = rig().await;
    rig.friends().await;
    let joins = rig.fake.with(|w| w.count("join 204"));
    rig.fake.with(|w| w.expire_sessions());
    let step = rig.steve().await;
    assert!(step.events.is_empty());
    assert_eq!(rig.steve.snapshot().phase, Phase::Ready);
    rig.fake.with(|w| {
        assert_eq!(w.count("join 204"), joins + 1, "a fresh proof at Mojang");
        assert_eq!(w.count("flint_social_state expired"), 1);
    });
    // Actions too.
    rig.fake.with(|w| w.expire_sessions());
    rig.steve.pin(&rig.steve_mc, ALEX, true).await.unwrap();
    assert!(rig.steve.snapshot().friends[0].pinned);
    rig.steve().await;
    assert!(rig.steve.snapshot().friends[0].pinned, "the server kept it");
}

#[tokio::test]
async fn a_server_without_friends_yet_is_almost_here_and_asked_again_in_10_minutes() {
    let rig = rig().await;
    rig.fake.with(|w| w.missing = true);
    let step = rig.steve().await;
    assert_eq!(step.wait, UNAVAILABLE_RETRY);
    let snapshot = rig.steve.snapshot();
    assert_eq!((snapshot.phase, snapshot.configured, snapshot.error.clone()), (Phase::Unavailable, true, None));
    let asked = rig.fake.with(|w| w.log.len());
    // Woken early (the page opened), it doesn't ask again before its time.
    let step = rig.steve().await;
    assert!(step.wait > UNAVAILABLE_RETRY - Duration::from_secs(5));
    assert_eq!(rig.fake.with(|w| w.log.len()), asked);
    // Retry, once the functions are there.
    rig.fake.with(|w| w.missing = false);
    rig.steve.retry_now();
    rig.steve().await;
    assert_eq!(rig.steve.snapshot().phase, Phase::Ready);
}

#[tokio::test]
async fn offline_keeps_the_last_state_and_retries() {
    let rig = rig().await;
    rig.friends().await;
    rig.fake.with(|w| w.offline = true);
    let step = rig.steve().await;
    assert_eq!(step.wait, IDLE_POLL);
    let snapshot = rig.steve.snapshot();
    assert_eq!((snapshot.phase, snapshot.offline, snapshot.error.as_deref()), (Phase::Ready, true, Some("offline")));
    assert_eq!(snapshot.friends.len(), 1, "the last state stays");
    assert_eq!(rig.steve().await.wait, IDLE_POLL * 2, "backs off");
    rig.fake.with(|w| w.offline = false);
    assert!(rig.steve().await.events.is_empty());
    let snapshot = rig.steve.snapshot();
    assert_eq!((snapshot.phase, snapshot.offline, snapshot.error), (Phase::Ready, false, None));

    // Offline from the start: an error to explain, retried.
    let fresh = social(&rig.fake);
    rig.fake.with(|w| w.offline = true);
    fresh.step(&rig.alex_mc).await;
    assert_eq!((fresh.snapshot().phase, fresh.snapshot().offline), (Phase::Error, true));
}

#[tokio::test]
async fn switching_accounts_starts_over_and_signing_out_of_flint_ends_the_session() {
    let rig = rig().await;
    rig.friends().await;
    assert_eq!(rig.steve.snapshot().friends.len(), 1);
    // The same launcher switches to Alex.
    rig.steve_mc.switch(Some((ALEX, "Alex")));
    let step = rig.steve().await;
    assert!(step.events.is_empty(), "a new account's first sync is silent too");
    let snapshot = rig.steve.snapshot();
    assert_eq!(snapshot.account.as_deref(), Some(ALEX));
    assert_eq!(snapshot.me.as_ref().map(|m| m.name.as_str()), Some("Alex"));
    assert_eq!(snapshot.friends.iter().map(|f| f.uuid.as_str()).collect::<Vec<_>>(), [STEVE]);
    rig.fake.with(|w| assert_eq!(w.count("flint_social_signout ok"), 0, "Steve's session stays for when he comes back"));

    // Signed out of Flint: Friends signs out (Alex's session, the one in use) and stops asking.
    rig.steve_mc.switch(None);
    let step = rig.steve().await;
    assert_eq!(step.wait, SIGNED_OUT_CHECK);
    let snapshot = rig.steve.snapshot();
    assert_eq!((snapshot.phase, snapshot.account, snapshot.friends.len()), (Phase::SignedOut, None, 0));
    let asked = rig.fake.with(|w| w.log.len());
    rig.steve().await;
    assert_eq!(rig.fake.with(|w| w.log.len()), asked, "nothing is sent while signed out");
    assert_eq!(rig.fake.with(|w| w.count("flint_social_signout ok")), 1);
}

#[tokio::test]
async fn status_and_requests_settings_go_to_the_server() {
    let rig = rig().await;
    rig.friends().await;
    let me = rig.steve.settings(&rig.steve_mc, Some("away"), Some(false)).await.unwrap();
    assert_eq!((me.status.as_str(), me.allow_requests), ("away", false));
    assert_eq!(rig.steve.snapshot().me.unwrap().status, "away");
    assert_eq!(rig.steve.settings(&rig.steve_mc, Some("sleeping"), None).await, Err(Fail::Refused("invalid_status".into())));
    rig.alex().await;
    assert_eq!(rig.alex.snapshot().friends[0].status, "away");
    rig.steve.settings(&rig.steve_mc, Some("invisible"), None).await.unwrap();
    rig.alex().await;
    assert_eq!(rig.alex.snapshot().friends[0].status, "offline");
}

#[tokio::test]
async fn a_build_without_the_project_says_so_and_sends_nothing() {
    let social = Social::new(build_clients().0, None);
    let mc = TestMinecraft::new(STEVE, "Steve");
    let step = social.step(&mc).await;
    assert!(step.wait >= Duration::from_secs(3600));
    let snapshot = social.snapshot();
    assert_eq!((snapshot.configured, snapshot.phase), (false, Phase::Unavailable));
    assert_eq!(social.search(&mc, "alex").await, Err(Fail::NotConfigured));
}

#[test]
fn only_a_publishable_key_and_a_project_address_are_used() {
    assert!(Endpoints::new("https://abcd.supabase.co", "sb_publishable_x").is_some());
    assert_eq!(Endpoints::new("https://abcd.supabase.co/", "k").unwrap().rpc("flint_social_state").as_str(), "https://abcd.supabase.co/rest/v1/rpc/flint_social_state");
    assert!(Endpoints::new("http://abcd.supabase.co", "k").is_none(), "plain http only on this computer");
    assert!(Endpoints::new("http://127.0.0.1:54321", "k").is_some());
    assert!(Endpoints::new("https://abcd.supabase.co/rest/v1", "k").is_none());
    assert!(Endpoints::new("https://abcd.supabase.co", "").is_none());
    assert!(Endpoints::new("", "k").is_none());
    assert!(Endpoints::new("https://abcd.supabase.co", "sb_secret_abc").is_none());
    let claims = URL_SAFE_NO_PAD.encode(br#"{"role":"service_role","iss":"supabase"}"#);
    assert!(Endpoints::new("https://abcd.supabase.co", &format!("eyJhbGciOiJIUzI1NiJ9.{claims}.sig")).is_none());
    let anon = URL_SAFE_NO_PAD.encode(br#"{"role":"anon","iss":"supabase"}"#);
    assert!(Endpoints::new("https://abcd.supabase.co", &format!("eyJhbGciOiJIUzI1NiJ9.{anon}.sig")).is_some());
    let shown = format!("{:?}", Endpoints::new("https://abcd.supabase.co", "sb_publishable_secretish").unwrap());
    assert!(!shown.contains("secretish"), "{shown}");
}

#[test]
fn answers_are_checked_and_normalized() {
    let state = json!({
        "ok": true,
        "me": { "uuid": "8667BA71-B85A-4004-AF54-457A9734EED7", "name": "Steve", "status": "dancing", "allow_requests": true },
        "friends": [
            { "uuid": "ec561538-f3fd-461d-aff5-086b22154bce", "name": "Alex", "status": "online", "in_game": true, "last_seen": null, "pinned": true, "since": "2026-11-01 10:00:00+00", "unread": 2 },
            { "uuid": "not-a-uuid", "name": "Bad" },
        ],
        "incoming": [{ "id": 7, "uuid": "ec561538f3fd461daff5086b22154bce", "name": "Alex", "at": 1_762_000_000 }],
        "outgoing": [{ "id": "a-b-c", "uuid": "ec561538f3fd461daff5086b22154bce", "name": "Alex", "at": null }],
        "messages": [
            { "id": 9, "from": "ec561538f3fd461daff5086b22154bce", "to": STEVE, "body": "b\u{0007}ye", "sensitive": false, "at": "2026-11-03T10:00:00Z", "read": false },
            { "id": "8", "from": STEVE, "to": "ec561538f3fd461daff5086b22154bce", "body": "hi\nthere", "sensitive": true, "at": "2026-11-03T09:59:00Z", "read": true },
        ],
    });
    let answer = parse_state(&state).unwrap();
    assert_eq!((answer.me.uuid.as_str(), answer.me.status.as_str()), (STEVE, "online"));
    assert_eq!(answer.friends.len(), 1);
    assert_eq!(answer.friends[0].since, Some(rfc3339_secs("2026-11-01T10:00:00Z").unwrap() * 1000));
    assert_eq!(answer.incoming[0].at, Some(1_762_000_000_000));
    assert_eq!(answer.outgoing[0].id, json!("a-b-c"));
    assert_eq!(answer.messages.iter().map(|m| (m.id, m.body.as_str())).collect::<Vec<_>>(), [(8, "hi\nthere"), (9, "bye")], "oldest first, no control characters");
    assert!(parse_state(&json!({ "ok": true })).is_none());
    assert!(is_player_name("Steve_01") && !is_player_name("") && !is_player_name("seventeen_letters") && !is_player_name("bad name"));
    let event = serde_json::to_value(SocialEvent::Online { uuid: ALEX.into(), name: "Alex".into(), status: "away".into() }).unwrap();
    assert_eq!(event, json!({ "kind": "online", "uuid": ALEX, "name": "Alex", "status": "away" }));
    let snapshot = serde_json::to_value(Snapshot { phase: Phase::SignedOut, ..Default::default() }).unwrap();
    assert_eq!((snapshot["phase"].as_str(), snapshot.get("syncedAt").is_some()), (Some("signed_out"), true));
}

#[tokio::test]
async fn syncs_are_never_closer_than_an_open_chats_poll_and_signing_in_again_waits_a_minute() {
    let rig = rig().await;
    let mut steve = paced(&rig.fake);
    steve.step(&rig.steve_mc).await;
    // Woken again at once (a focus change, an action): it waits instead of asking.
    let step = steve.step(&rig.steve_mc).await;
    assert!(step.wait > Duration::ZERO && step.wait <= CHAT_POLL, "{:?}", step.wait);
    assert_eq!(rig.fake.with(|w| w.calls("flint_social_state")), 1);

    // A session that ends right after signing in isn't replaced before a minute has passed.
    steve.pacing.min_gap = Duration::ZERO;
    rig.fake.with(|w| w.expire_sessions());
    steve.step(&rig.steve_mc).await;
    let snapshot = steve.snapshot();
    assert_eq!((snapshot.phase, snapshot.error.as_deref()), (Phase::Ready, Some("expired")), "the last state stays");
    assert_eq!(rig.fake.with(|w| w.count("join 204")), 1, "no second sign-in yet");
    steve.pacing.resign_gap = Duration::ZERO;
    steve.step(&rig.steve_mc).await;
    assert_eq!(rig.fake.with(|w| w.count("join 204")), 2);
    assert_eq!(steve.snapshot().error, None);
}

#[tokio::test]
async fn rate_limited_syncs_slow_down_then_recover() {
    let rig = rig().await;
    rig.steve().await;
    rig.fake.with(|w| w.limited.insert("flint_social_state".into()));
    let waits = [rig.steve().await.wait, rig.steve().await.wait, rig.steve().await.wait];
    assert_eq!(waits, [IDLE_POLL * 2, IDLE_POLL * 4, MAX_BACKOFF], "doubles up to a minute");
    assert_eq!(rig.steve.snapshot().error.as_deref(), Some("rate_limited"));
    assert_eq!(rig.steve.snapshot().phase, Phase::Ready);
    rig.steve.set_focus(Some(ALEX.into()));
    assert_eq!(rig.steve().await.wait, MAX_BACKOFF);
    rig.fake.with(|w| w.limited.clear());
    assert_eq!(rig.steve().await.wait, CHAT_POLL, "back to normal");
    assert_eq!(rig.steve.snapshot().error, None);
    rig.fake.with(|w| w.limited.insert("flint_friend_request".into()));
    assert_eq!(rig.steve.request(&rig.steve_mc, "alex").await, Err(Fail::Refused("rate_limited".into())));
}

#[tokio::test]
async fn accepting_when_either_side_is_full_keeps_the_request() {
    let rig = rig().await;
    rig.steve().await;
    rig.alex().await;
    rig.steve.request(&rig.steve_mc, "alex").await.unwrap();
    rig.alex().await;
    let id = rig.alex.snapshot().incoming[0].id.clone();
    rig.fake.with(|w| w.max_friends = 0);
    assert_eq!(rig.alex.respond(&rig.alex_mc, &id, true).await, Err(Fail::Refused("too_many".into())));
    assert_eq!(rig.alex.snapshot().incoming.len(), 1, "the request stays");
    rig.alex().await;
    assert_eq!(rig.alex.snapshot().incoming.len(), 1);
    rig.fake.with(|w| w.max_friends = 200);
    rig.alex.respond(&rig.alex_mc, &id, true).await.unwrap();
    rig.alex().await;
    assert_eq!(rig.alex.snapshot().friends.len(), 1);
}

#[tokio::test]
async fn a_full_player_takes_no_requests() {
    let rig = rig().await;
    rig.steve().await;
    rig.alex().await;
    rig.fake.with(|w| w.max_friends = 0);
    assert_eq!(rig.steve.request(&rig.steve_mc, "alex").await, Err(Fail::Refused("not_accepting".into())));
}

#[tokio::test]
async fn search_puts_the_exact_name_first_and_shows_yourself() {
    let rig = rig().await;
    const AL: &str = "0000000000000000000000000000a11a";
    rig.fake.with(|w| w.add_mojang_account("mc-token-al", AL, "Al"));
    let al = social(&rig.fake);
    let al_mc = TestMinecraft { active: Mutex::new(Some(Identity { uuid: AL.into(), name: "Al".into() })), tokens: HashMap::from([(AL.to_owned(), "mc-token-al".to_owned())]), ..TestMinecraft::new(AL, "Al") };
    rig.steve().await;
    rig.alex().await;
    al.step(&al_mc).await;
    let names = |players: Vec<Player>| players.into_iter().map(|p| (p.name, p.relation)).collect::<Vec<_>>();
    assert_eq!(names(rig.alex.search(&rig.alex_mc, "al").await.unwrap()), [("Al".to_owned(), "none".to_owned()), ("Alex".to_owned(), "self".to_owned())]);
    assert_eq!(names(al.search(&al_mc, "al").await.unwrap()), [("Al".to_owned(), "self".to_owned()), ("Alex".to_owned(), "none".to_owned())]);
}

#[tokio::test]
async fn a_malformed_call_is_a_calm_failure() {
    let rig = rig().await;
    rig.friends().await;
    // Flint never sends this; the server's answer is still only a code, never a crash.
    let answer = rig.steve.authed(&rig.steve_mc, "flint_message_send", |token| json!({ "p_token": token, "p_to": ALEX, "p_body": 7 })).await.map(|_| ());
    assert_eq!(answer, Err(Fail::Refused("invalid".into())));
    let sent = rig.steve.send(&rig.steve_mc, ALEX, "hi\u{0007} there", false, None).await.unwrap();
    assert_eq!(sent.body, "hi there", "control characters are dropped");
}

// ---------- the challenge sign-in, kept sessions and the kept view (Backend 20261104006500) ----------

/// A keeping launcher for Steve, already friends with Alex and with one message from Alex; the fake keeps running.
async fn steve_keeping() -> (Fake, TestMinecraft, TestMinecraft) {
    let fake = Fake::start().await;
    fake.with(|w| {
        w.add_mojang_account("mc-token-steve", STEVE, "Steve");
        w.add_mojang_account("mc-token-alex", ALEX, "Alex");
    });
    let steve_mc = TestMinecraft::keeping(STEVE, "Steve");
    let alex_mc = TestMinecraft::new(ALEX, "Alex");
    let steve = social(&fake);
    let alex = social(&fake);
    steve.step(&steve_mc).await;
    alex.step(&alex_mc).await;
    assert_eq!(steve.request(&steve_mc, "alex").await, Ok("sent".into()));
    alex.step(&alex_mc).await;
    let id = alex.snapshot().incoming[0].id.clone();
    alex.respond(&alex_mc, &id, true).await.unwrap();
    alex.send(&alex_mc, STEVE, "gg", false, None).await.unwrap();
    steve.step(&steve_mc).await;
    (fake, steve_mc, alex_mc)
}

#[tokio::test]
async fn the_next_start_uses_the_kept_session_without_asking_mojang_and_shows_the_kept_list_first() {
    let (fake, steve_mc, _) = steve_keeping().await;
    assert!(steve_mc.saved_session(STEVE).is_some(), "the session is kept for the next start");
    let kept = steve_mc.cached_view(STEVE).expect("the view is kept");
    assert_eq!(kept.friends.iter().map(|f| (f.name.as_str(), f.status.as_str(), f.last_seen)).collect::<Vec<_>>(), vec![("Alex", "offline", None)], "nothing live is kept");
    let joins = fake.with(|w| w.count("join 204"));

    // The next start: a new Social, as a new run of the app.
    let next = social(&fake);
    next.connect(&steve_mc).await.unwrap();
    let before = next.snapshot();
    assert!(before.stale, "the kept list shows, marked not live, until the first sync");
    assert_eq!(before.friends.len(), 1);
    next.step(&steve_mc).await;
    let after = next.snapshot();
    assert_eq!((after.phase, after.stale), (Phase::Ready, false));
    assert_eq!(after.friends[0].status, "online");
    fake.with(|w| {
        assert_eq!(w.count("join 204"), joins, "no Mojang join, no new sign-in");
        assert_eq!(w.calls("flint_signin_challenge"), 2, "Steve's and Alex's first sign-ins, none since");
    });
}

#[tokio::test]
async fn a_kept_session_the_server_revoked_is_forgotten_and_replaced_without_the_player_noticing() {
    let (fake, steve_mc, _) = steve_keeping().await;
    let old = steve_mc.saved_session(STEVE).unwrap();
    fake.with(|w| w.expire_sessions());
    let next = social(&fake);
    next.step(&steve_mc).await;
    assert_eq!(next.snapshot().phase, Phase::Ready);
    let new = steve_mc.saved_session(STEVE).unwrap();
    assert_ne!(new.token, old.token, "the revoked session was replaced and the new one kept");
    fake.with(|w| assert_eq!(w.count("flint_social_signin_v2 ok"), 3, "Steve and Alex at first, then Steve again"));
}

#[tokio::test]
async fn a_challenge_that_ran_out_on_the_way_is_replaced_once() {
    let (fake, steve_mc, _) = steve_keeping().await;
    steve_mc.forget_session(STEVE);
    // Every challenge vanishes once (as one that expired while Mojang was slow): the second one goes through.
    let next = social(&fake);
    fake.with(|w| w.lose_next_challenge = true);
    next.step(&steve_mc).await;
    assert_eq!(next.snapshot().phase, Phase::Ready);
    fake.with(|w| assert_eq!(w.count("flint_social_signin_v2 challenge"), 1));
}

#[tokio::test]
async fn switching_accounts_keeps_each_accounts_session_and_signing_out_of_flint_ends_it() {
    let (fake, steve_mc, _) = steve_keeping().await;
    let steve = social(&fake);
    steve.step(&steve_mc).await;
    let kept = steve_mc.saved_session(STEVE).unwrap();
    steve_mc.switch(Some((ALEX, "Alex")));
    steve.step(&steve_mc).await;
    assert_eq!(steve.snapshot().account.as_deref(), Some(ALEX));
    assert_eq!(steve_mc.saved_session(STEVE), Some(kept.clone()), "Steve's session stays for when he comes back");
    fake.with(|w| assert_eq!(w.calls("flint_social_signout"), 0));
    steve_mc.switch(None);
    steve.step(&steve_mc).await;
    assert_eq!(steve.snapshot().phase, Phase::SignedOut);
    fake.with(|w| assert_eq!(w.calls("flint_social_signout"), 1, "signing out of Flint ends the session in use"));
}

#[tokio::test]
async fn a_send_retried_with_the_same_client_id_is_stored_once() {
    let rig = rig().await;
    rig.friends().await;
    let id = "6f1c2e0a-8f4b-4d55-9a7e-2b3c4d5e6f70";
    let first = rig.steve.send(&rig.steve_mc, ALEX, "on my way", false, Some(id)).await.unwrap();
    let again = rig.steve.send(&rig.steve_mc, ALEX, "on my way", false, Some(id)).await.unwrap();
    assert_eq!(first.id, again.id);
    rig.fake.with(|w| assert_eq!(w.messages.iter().filter(|m| m.body == "on my way").count(), 1));
    // A malformed id is not sent at all (the message still goes, without one).
    rig.steve.send(&rig.steve_mc, ALEX, "no id", false, Some("x")).await.unwrap();
}

#[tokio::test]
async fn a_long_history_never_replays_as_new_messages_after_a_start() {
    let rig = rig().await;
    rig.friends().await;
    for i in 0..230 {
        rig.alex.send(&rig.alex_mc, STEVE, &format!("old {i}"), false, None).await.unwrap();
    }
    // A new start for Steve: the first sync is silent, and the ones after it bring nothing old back as new.
    let next = social(&rig.fake);
    let first = next.step(&rig.steve_mc).await;
    assert!(first.events.is_empty());
    let shown: usize = next.snapshot().conversations.values().map(|c| c.messages.len()).sum();
    assert!(shown <= 200);
    assert!(next.snapshot().conversations.values().flat_map(|c| &c.messages).any(|m| m.body == "old 229"), "the newest are the ones shown");
    for _ in 0..3 {
        assert!(kinds(&next.step(&rig.steve_mc).await).is_empty(), "no old message comes back as new");
    }
}

// ---------- Friends in the game (game.rs) ----------

#[test]
fn a_game_has_friends_only_as_flints_active_microsoft_account() {
    use super::game::{gate, Gate};
    assert_eq!(gate(Some((&fake::dashed(STEVE), false)), STEVE), Gate::Ready, "either spelling of the active account");
    assert_eq!(gate(Some((ALEX, false)), STEVE), Gate::OtherAccount);
    assert_eq!(gate(Some((STEVE, true)), STEVE), Gate::SignedOut { test_identity: true });
    assert_eq!(gate(None, STEVE), Gate::SignedOut { test_identity: false });
}

#[tokio::test]
async fn the_game_sees_the_pages_state_trimmed_with_only_its_open_chat() {
    use super::game::{view, GAME_MESSAGES};
    let rig = rig().await;
    rig.friends().await;
    for i in 0..60 {
        rig.alex.send(&rig.alex_mc, STEVE, &format!("hi {i}"), false, None).await.unwrap();
    }
    rig.steve().await;
    let snapshot = rig.steve.snapshot();

    let list = view(&snapshot, STEVE, None);
    assert_eq!(list.phase, Phase::Ready);
    assert_eq!(list.friends.len(), 1);
    assert_eq!((list.friends[0].name.as_str(), list.friends[0].unread), ("Alex", 60));
    assert!(list.chat.is_none(), "no conversation unless one is open");
    let json = serde_json::to_value(&list).unwrap();
    assert!(json.get("conversations").is_none() && json.get("focus").is_none() && json.get("account").is_none(), "{json}");
    assert_eq!(json["friends"][0]["inGame"], false);
    assert!(!json.to_string().contains("hi 59"), "no message outside an open chat");

    let open = view(&snapshot, STEVE, Some(&dashed_alex()));
    assert_eq!(view(&rig.steve.game_snapshot(Some(&dashed_alex())), STEVE, Some(&dashed_alex())), open, "the game's lighter snapshot shows the same");
    assert!(rig.steve.game_snapshot(None).conversations.is_empty(), "no conversation copied for a list");
    let chat = open.chat.expect("the open chat");
    assert_eq!(chat.with, ALEX);
    assert_eq!(chat.messages.len(), GAME_MESSAGES);
    assert!(chat.more, "older ones are Flint's, asked for page by page");
    assert_eq!(chat.messages.last().unwrap().body, "hi 59", "the newest, oldest first");
    // Only a friend's chat.
    assert!(view(&snapshot, STEVE, Some("0123456789abcdef0123456789abcdef")).chat.is_none());

    // A snapshot still for another account (Flint switched a moment ago): connecting, never that account's friends.
    let other = view(&snapshot, ALEX, None);
    assert_eq!(other.phase, Phase::Connecting);
    assert!(other.friends.is_empty() && other.me.is_none());
}

#[tokio::test]
async fn a_games_actions_run_as_the_pages_do() {
    use super::game::{act, GameAction};
    let rig = rig().await;
    rig.steve().await;
    rig.alex().await;
    let ask = |action: GameAction| act(&rig.steve, &rig.steve_mc, action);
    assert_eq!(ask(GameAction::Request { name: "alex".into() }).await.unwrap(), json!({ "result": "sent" }));
    assert_eq!(ask(GameAction::Request { name: "nobody_here".into() }).await, Err(Fail::Refused("not_on_flint".into())));
    assert_eq!(ask(GameAction::Request { name: "../../x".into() }).await, Err(Fail::Refused("not_on_flint".into())));
    rig.steve().await;
    let request = rig.steve.snapshot().outgoing[0].id.clone();
    assert_eq!(ask(GameAction::Cancel { id: request }).await.unwrap(), json!({}));
    assert!(rig.steve.snapshot().outgoing.is_empty());

    // Alex asks; Steve accepts from the game.
    rig.alex.request(&rig.alex_mc, "steve").await.unwrap();
    rig.steve().await;
    let incoming = rig.steve.snapshot().incoming[0].id.clone();
    assert_eq!(ask(GameAction::Respond { id: incoming, accept: true }).await.unwrap(), json!({}));
    rig.steve().await;
    assert_eq!(rig.steve.snapshot().friends.len(), 1);

    // A message with the game's id, retried after a lost answer: stored once.
    let client_id = "4b1d2f9c-1e2a-4c3b-8d4e-5f6a7b8c9d0e".to_owned();
    let send = || GameAction::Send { to: dashed_alex(), body: "gg".into(), sensitive: false, client_id: Some(client_id.clone()) };
    let first = ask(send()).await.unwrap();
    let again = ask(send()).await.unwrap();
    assert_eq!(first["message"]["id"], again["message"]["id"]);
    assert_eq!(first["message"]["body"], "gg");
    rig.fake.with(|w| assert_eq!(w.messages.iter().filter(|m| m.body == "gg").count(), 1));
    assert_eq!(ask(GameAction::Send { to: ALEX.into(), body: "x".repeat(MAX_BODY + 1), sensitive: false, client_id: None }).await, Err(Fail::Refused("too_long".into())));
    assert_eq!(ask(GameAction::Send { to: ALEX.into(), body: "  ".into(), sensitive: false, client_id: None }).await, Err(Fail::Refused("empty".into())));

    // History pages, read, pin, settings, search.
    let page = ask(GameAction::History { with: ALEX.into(), before: None }).await.unwrap();
    assert_eq!(page["complete"], true);
    assert_eq!(page["messages"][0]["body"], "gg");
    let alex_said = rig.alex.send(&rig.alex_mc, STEVE, "nice", false, None).await.unwrap();
    rig.steve().await;
    assert_eq!(rig.steve.snapshot().friends[0].unread, 1);
    ask(GameAction::Read { with: ALEX.into(), up_to: alex_said.id }).await.unwrap();
    assert_eq!(rig.steve.snapshot().friends[0].unread, 0);
    ask(GameAction::Pin { uuid: ALEX.into(), pinned: true }).await.unwrap();
    assert!(rig.steve.snapshot().friends[0].pinned);
    let me = ask(GameAction::Settings { status: Some("busy".into()), allow_requests: Some(false) }).await.unwrap();
    assert_eq!((me["me"]["status"].clone(), me["me"]["allowRequests"].clone()), (json!("busy"), json!(false)));
    assert_eq!(ask(GameAction::Settings { status: Some("sleeping".into()), allow_requests: None }).await, Err(Fail::Refused("invalid_status".into())));
    let found = ask(GameAction::Search { query: "ale".into() }).await.unwrap();
    assert_eq!(found["players"][0]["name"], "Alex");
    assert_eq!(found["players"][0]["relation"], "friend");
    assert_eq!(ask(GameAction::Connect).await.unwrap(), json!({}));
    ask(GameAction::Remove { uuid: dashed_alex() }).await.unwrap();
    assert!(rig.steve.snapshot().friends.is_empty());
}

#[test]
fn only_the_pages_actions_are_read_from_a_game() {
    use super::game::GameAction;
    let read = |v: Value| serde_json::from_value::<GameAction>(v);
    assert_eq!(read(json!({ "kind": "connect" })).unwrap(), GameAction::Connect);
    assert_eq!(read(json!({ "kind": "read", "with": ALEX, "upTo": 4 })).unwrap(), GameAction::Read { with: ALEX.into(), up_to: 4 });
    assert_eq!(read(json!({ "kind": "history", "with": ALEX })).unwrap(), GameAction::History { with: ALEX.into(), before: None });
    assert!(read(json!({ "kind": "signIn" })).is_err());
    assert!(read(json!({ "kind": "pin", "uuid": ALEX })).is_err(), "pinned is required");
    assert!(!GameAction::Search { query: "a".into() }.changes_view());
    assert!(GameAction::History { with: ALEX.into(), before: None }.changes_view(), "the latest page fills the page's chat too");
}
