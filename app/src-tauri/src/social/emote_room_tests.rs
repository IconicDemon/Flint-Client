//! The emote room: the address every Flint names a server by, the room's frames, its closes and refusals, and a connection
//! against a fake room on this computer (auth, ready, emotes both ways, a refusal, a replaced socket).
use super::emote_room::*;
use super::live::{self, LiveTiming};
use super::*;
use futures_util::SinkExt;
use tokio::net::TcpListener;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_tungstenite::tungstenite::protocol::CloseFrame;
use tokio_tungstenite::tungstenite::Message as Frame;

const ME: &str = "8667ba71b85a4004af54457a9734eed7";
const ALEX: &str = "ec561538f3fd461daff5086b22154bce";
const WAVE: &str = "c6a771fe-05ee-4aa5-a587-592560c12aa6";

#[test]
fn every_flint_names_a_server_one_way() {
    assert_eq!(normalize("Play.Example.COM").as_deref(), Some("play.example.com:25565"));
    assert_eq!(normalize("play.example.com.").as_deref(), Some("play.example.com:25565"));
    assert_eq!(normalize(" play.example.com:25565 ").as_deref(), Some("play.example.com:25565"));
    assert_eq!(normalize("mc.Example.com.:25566").as_deref(), Some("mc.example.com:25566"));
    assert_eq!(normalize("203.0.113.9").as_deref(), Some("203.0.113.9:25565"));
    assert_eq!(normalize("[2001:db8::1]:25570").as_deref(), Some("[2001:db8::1]:25570"));
    assert_eq!(normalize("2001:db8::1").as_deref(), Some("[2001:db8::1]:25565"));
    // One room per address: the hash of the normalised text, lowercase hex.
    assert_eq!(room_id("play.example.com:25565"), room_id(&normalize("PLAY.example.com.").unwrap()));
    assert_eq!(room_id("a:1").len(), 64);
    assert_ne!(room_id("play.example.com:25565"), room_id("play.example.com:25566"));
}

#[test]
fn singleplayer_and_lan_never_get_a_room() {
    for local in [
        "", "localhost", "localhost:25565", "127.0.0.1", "127.0.0.1:25565", "[::1]:25565", "192.168.1.20:25565", "10.0.0.5",
        "172.16.3.4", "169.254.2.2", "100.64.1.1", "0.0.0.0", "[fe80::1]", "[fd00::5]", "myserver", "box.local", "nas.lan:25565",
        "pc.home", "x.internal", "[::ffff:192.168.1.1]",
    ] {
        assert_eq!(normalize(local), None, "{local}");
    }
    for bad in ["a b.com", "a.com:0", "a.com:99999", "a.com:x", "user@a.com", "a.com/x", "[2001:db8::1]x", "a..com"] {
        assert_eq!(normalize(bad), None, "{bad}");
    }
}

#[test]
fn the_rooms_frames_are_read_and_checked() {
    assert_eq!(emote_frame(WAVE), format!(r#"{{"type":"emote","cosmetic_id":"{WAVE}"}}"#));
    assert_eq!(STOP, r#"{"type":"emote_stop"}"#);
    assert_eq!(
        parse(r#"{"type":"ready","uuid":"8667ba71-b85a-4004-af54-457a9734eed7","server_time":"2026-11-12T02:20:00.123Z"}"#),
        Some(RoomEvent::Ready { uuid: ME.into(), server_ms: Some(1_794_450_000_123) })
    );
    assert_eq!(
        parse(&format!(r#"{{"type":"emote","uuid":"{ALEX}","cosmetic_id":"{}","at":"2026-11-12T02:00:00Z"}}"#, WAVE.to_uppercase())),
        Some(RoomEvent::Emote { uuid: ALEX.into(), cosmetic_id: WAVE.into(), at_ms: Some(1_794_448_800_000) })
    );
    assert_eq!(parse(&format!(r#"{{"type":"emote_stop","uuid":"{ALEX}"}}"#)), Some(RoomEvent::Stop { uuid: ALEX.into() }));
    assert_eq!(
        parse(&format!(r#"{{"type":"emote_refused","cosmetic_id":"{WAVE}","reason":"rate_limited"}}"#)),
        Some(RoomEvent::Refused { cosmetic_id: WAVE.into(), reason: "rate_limited".into() })
    );
    assert_eq!(parse(r#"{"type":"emote_refused","reason":"whatever"}"#), Some(RoomEvent::Refused { cosmetic_id: String::new(), reason: "refused".into() }));
    assert_eq!(parse(r#"{"type":"pong"}"#), Some(RoomEvent::Pong));
    assert_eq!(parse(r#"{"type":"news"}"#), Some(RoomEvent::Other));
    // Malformed: no player, a cosmetic that isn't an id, too long, not JSON.
    assert_eq!(parse(r#"{"type":"emote","cosmetic_id":"x"}"#), None);
    assert_eq!(parse(&format!(r#"{{"type":"emote","uuid":"{ALEX}","cosmetic_id":"../x"}}"#)), None);
    assert_eq!(parse(&format!(r#"{{"type":"other","pad":"{}"}}"#, "x".repeat(MAX_FRAME))), None);
    assert_eq!(parse("not json"), None);
}

#[test]
fn the_rooms_times_are_javascripts_iso_strings_to_the_millisecond() {
    assert_eq!(emote_room::millis(&json!("2026-11-11T21:04:05.123Z")), Some(1_794_431_045_123));
    assert_eq!(emote_room::millis(&json!("2026-11-11T21:04:05.1Z")), Some(1_794_431_045_100));
    assert_eq!(emote_room::millis(&json!("2026-11-11T21:04:05Z")), Some(1_794_431_045_000));
    // Only that form: no numbers since 1970, nothing that isn't a time.
    assert_eq!(emote_room::millis(&json!(1_794_431_045_123_i64)), None);
    assert_eq!(emote_room::millis(&json!("yesterday")), None);
}

fn close(code: u16) -> CloseFrame {
    CloseFrame { code: CloseCode::from(code), reason: "".into() }
}

#[test]
fn the_rooms_closes_wait_as_they_ask() {
    assert_eq!(closed(Some(&close(4001)), true), RoomEnded::Refused);
    assert_eq!(closed(Some(&close(4409)), true), RoomEnded::Replaced);
    assert_eq!(closed(Some(&close(4400)), true), RoomEnded::Missing);
    assert_eq!(closed(Some(&close(1009)), true), RoomEnded::Missing);
    for abnormal in [4503, 4429, 4008, 1013, 1011] {
        assert_eq!(closed(Some(&close(abnormal)), true), RoomEnded::Abnormal { code: Some(abnormal) }, "{abnormal}");
    }
    assert_eq!(closed(Some(&close(1001)), true), RoomEnded::Dropped { was_live: true });
    assert_eq!(closed(None, true), RoomEnded::Dropped { was_live: true });
    let t = live::TIMING;
    let (mut failures, mut abnormal) = (0, 0);
    let no = Duration::ZERO;
    assert_eq!(retry_wait(&t, &RoomEnded::Missing, &mut failures, &mut abnormal, no), Some(t.missing_retry));
    assert_eq!(retry_wait(&t, &RoomEnded::Switched, &mut failures, &mut abnormal, no), Some(Duration::ZERO));
    // An expired session: signed in again first, then at once.
    assert_eq!(retry_wait(&t, &RoomEnded::Refused, &mut failures, &mut abnormal, no), Some(t.first_retry));
    // A drop comes back as Friends live's: 1 s, 2 s…
    assert_eq!(retry_wait(&t, &RoomEnded::Dropped { was_live: false }, &mut failures, &mut abnormal, no), Some(Duration::from_secs(1)));
    assert_eq!(retry_wait(&t, &RoomEnded::Dropped { was_live: false }, &mut failures, &mut abnormal, no), Some(Duration::from_secs(2)));
}

#[test]
fn a_full_room_or_any_abnormal_close_waits_a_minute_doubling_to_ten_with_jitter() {
    let t = live::TIMING;
    let (mut failures, mut abnormal) = (0, 0);
    let full = RoomEnded::Abnormal { code: Some(4503) };
    // At least a minute plus 0–30 s, doubling on each one in a row, up to 10 minutes.
    let mut expected = 60;
    for _ in 0..8 {
        let least = retry_wait(&t, &full, &mut failures, &mut abnormal, Duration::ZERO).unwrap();
        assert_eq!(least, Duration::from_secs(expected));
        abnormal -= 1;
        let most = retry_wait(&t, &full, &mut failures, &mut abnormal, Duration::from_secs(30)).unwrap();
        assert_eq!(most, Duration::from_secs(expected + 30));
        expected = (expected * 2).min(600);
    }
    // The jitter never goes past 30 s, however large it is asked to be, and the real spread stays within it.
    assert_eq!(abnormal_wait(1, Duration::from_secs(90)), Duration::from_secs(90));
    for repeat in 1..=6 {
        let w = abnormal_wait(repeat, Duration::from_millis(12_345));
        let base = Duration::from_secs((60u64 << (repeat - 1)).min(600));
        assert!(w >= base && w <= base + ABNORMAL_JITTER, "{repeat}: {w:?}");
    }
    // Anything else in between starts it over.
    retry_wait(&t, &RoomEnded::Dropped { was_live: true }, &mut failures, &mut abnormal, Duration::ZERO);
    assert_eq!(retry_wait(&t, &full, &mut failures, &mut abnormal, Duration::ZERO), Some(Duration::from_secs(60)));
}

#[test]
fn a_replaced_socket_is_never_opened_again_for_that_server() {
    let t = live::TIMING;
    let (mut failures, mut abnormal) = (0, 0);
    assert_eq!(retry_wait(&t, &RoomEnded::Replaced, &mut failures, &mut abnormal, Duration::ZERO), None);
}

#[tokio::test]
async fn a_game_hears_others_and_never_itself_and_old_emotes_are_skipped() {
    let room = Room::default();
    room.join(Some(Want { uuid: ME.into(), address: "play.example.com:25565".into(), room: room_id("play.example.com:25565") }));
    assert!(!room.send(emote_frame(WAVE)), "nothing goes out before the room is live");
    room.apply(RoomEvent::Emote { uuid: ALEX.into(), cosmetic_id: WAVE.into(), at_ms: None }, ME);
    room.apply(RoomEvent::Emote { uuid: ME.into(), cosmetic_id: WAVE.into(), at_ms: None }, ME);
    room.apply(RoomEvent::Emote { uuid: ALEX.into(), cosmetic_id: WAVE.into(), at_ms: Some(1) }, ME);
    room.apply(RoomEvent::Stop { uuid: ALEX.into() }, ME);
    room.apply(RoomEvent::Refused { cosmetic_id: WAVE.into(), reason: "not_owned".into() }, ME);
    let (events, seq, live) = room.events_after(0);
    assert!(!live);
    assert_eq!(events.iter().map(|e| e.kind).collect::<Vec<_>>(), ["emote", "stop", "refused"]);
    assert_eq!(events[0].uuid.as_deref(), Some(ALEX));
    assert_eq!(events[2].reason.as_deref(), Some("not_owned"));
    assert_eq!(room.events_after(seq).0, vec![]);
    // A game waiting on the room hears the next one at once.
    let waited = Instant::now();
    room.wait_after(seq, Duration::from_millis(100)).await;
    assert!(waited.elapsed() >= Duration::from_millis(90));
    // Leaving clears what it wanted.
    room.join(None);
    assert_eq!(room.wanted(), None);
}

/// A fake room on this computer, as the Store's: checks the address, the auth frame, then plays `script` (frames to send,
/// and what it expects to receive), and closes with `close` at the end.
#[allow(clippy::result_large_err)]
async fn fake_room(listener: TcpListener, room: String, script: Vec<(Option<String>, Option<&'static str>)>, close_code: Option<u16>) -> Vec<String> {
    let (tcp, _) = listener.accept().await.unwrap();
    let mut path = String::new();
    let mut socket = tokio_tungstenite::accept_hdr_async(tcp, |request: &tokio_tungstenite::tungstenite::handshake::server::Request, response| {
        path = request.uri().to_string();
        Ok(response)
    })
    .await
    .unwrap();
    assert!(path.starts_with("/api/v1/social/emotes/?uuid=8667ba71-b85a-4004-af54-457a9734eed7&room="), "{path}");
    assert!(path.ends_with(&room), "{path}");
    let mut heard = Vec::new();
    match socket.next().await {
        Some(Ok(Frame::Text(t))) => {
            let auth: Value = serde_json::from_str(t.as_str()).unwrap();
            assert_eq!(auth, json!({ "type": "auth", "token": "room-token" }));
        }
        other => panic!("no auth: {other:?}"),
    }
    socket.send(Frame::text(format!(r#"{{"type":"ready","uuid":"{ME}","server_time":"{}"}}"#, iso(now())))).await.unwrap();
    for (send, expect) in script {
        if let Some(frame) = send {
            socket.send(Frame::text(frame)).await.unwrap();
        }
        if let Some(prefix) = expect {
            loop {
                match tokio::time::timeout(Duration::from_secs(5), socket.next()).await {
                    Ok(Some(Ok(Frame::Text(t)))) if t.as_str() == live::PING => continue,
                    Ok(Some(Ok(Frame::Text(t)))) => {
                        assert!(t.as_str().starts_with(prefix), "{} isn't {prefix}", t.as_str());
                        heard.push(t.as_str().to_owned());
                        break;
                    }
                    other => panic!("expected {prefix}: {other:?}"),
                }
            }
        }
    }
    if let Some(code) = close_code {
        let _ = socket.close(Some(close(code))).await;
    }
    heard
}

fn now() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as i64
}

/// `ms` since 1970 as JavaScript's `toISOString()` writes it.
fn iso(ms: i64) -> String {
    let (days, rest) = (ms.div_euclid(86_400_000), ms.rem_euclid(86_400_000));
    // Civil from days (Howard Hinnant's algorithm).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.{:03}Z", rest / 3_600_000, rest / 60_000 % 60, rest / 1000 % 60, rest % 1000)
}

#[test]
fn the_tests_own_clock_writes_what_the_room_reads() {
    let t = 1_794_431_045_123;
    assert_eq!(iso(t), "2026-11-11T21:04:05.123Z");
    assert_eq!(emote_room::millis(&json!(iso(t))), Some(t));
}

const FAST: LiveTiming = LiveTiming {
    connect: Duration::from_secs(2),
    ready: Duration::from_secs(2),
    ping: Duration::from_secs(30),
    pong: Duration::from_secs(10),
    first_retry: Duration::from_millis(50),
    max_retry: Duration::from_millis(400),
    missing_retry: Duration::from_secs(5),
    refused_retry: Duration::from_secs(10),
    busy_retry: Duration::from_millis(600),
    down_retry: Duration::from_millis(400),
    jitter: false,
};

#[tokio::test]
async fn emotes_go_both_ways_through_the_room_and_a_replaced_socket_isnt_reopened() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let store = crate::store::Endpoints {
        store: Url::parse(&format!("http://127.0.0.1:{port}/")).unwrap(),
        mojang: Url::parse(&format!("http://127.0.0.1:{port}")).unwrap(),
        access: None,
    };
    let address = normalize("Play.Example.com.").unwrap();
    let want = Want { uuid: ME.into(), room: room_id(&address), address };
    let room = std::sync::Arc::new(Room::default());
    room.join(Some(want.clone()));
    let script = vec![
        // Alex's emote 2 s ago, by the room's clock.
        (Some(format!(r#"{{"type":"emote","uuid":"{ALEX}","cosmetic_id":"{WAVE}","at":"{}"}}"#, iso(now() - 2000))), None),
        // Ours goes out once the room is live...
        (None, Some(r#"{"type":"emote","cosmetic_id":"#)),
        // ...is refused (rate limited), then our stop goes out.
        (Some(format!(r#"{{"type":"emote_refused","cosmetic_id":"{WAVE}","reason":"rate_limited"}}"#)), Some(r#"{"type":"emote_stop"}"#)),
        (Some(format!(r#"{{"type":"emote_stop","uuid":"{ALEX}"}}"#)), None),
    ];
    let server = tokio::spawn(fake_room(listener, want.room.clone(), script, Some(4409)));
    let target = live::Target { uuid: ME.into(), token: "room-token".into() };
    let mut out = room.take_out().unwrap();
    let client = {
        let room = room.clone();
        let want = want.clone();
        tokio::spawn(async move { room_connection(&room, &store, &target, &want, &FAST, &mut out).await })
    };
    // Live: the game's frames go out.
    let started = Instant::now();
    while !room.is_live() {
        assert!(started.elapsed() < Duration::from_secs(5), "never live");
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    while room.events_after(0).0.is_empty() {
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    assert!(room.send(emote_frame(WAVE)));
    while room.events_after(0).0.len() < 2 {
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    assert!(room.send(STOP.to_owned()));
    let heard = server.await.unwrap();
    assert_eq!(heard, [emote_frame(WAVE), STOP.to_owned()]);
    let ended = client.await.unwrap();
    assert_eq!(ended, RoomEnded::Replaced, "replaced: another socket took over");
    let (mut failures, mut abnormal) = (0, 0);
    assert_eq!(retry_wait(&FAST, &ended, &mut failures, &mut abnormal, Duration::ZERO), None, "never reconnected for this server");
    let (events, _, _) = room.events_after(0);
    assert_eq!(events.iter().map(|e| e.kind).collect::<Vec<_>>(), ["emote", "refused", "stop"]);
    let ago = events[0].ago_ms.unwrap();
    assert!((1500..=4000).contains(&ago), "2 s ago by the room's clock: {ago}");
    assert_eq!(events[1].reason.as_deref(), Some("rate_limited"));
}

#[tokio::test]
async fn an_expired_session_is_told_apart_from_a_drop() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let store = crate::store::Endpoints {
        store: Url::parse(&format!("http://127.0.0.1:{port}/")).unwrap(),
        mojang: Url::parse(&format!("http://127.0.0.1:{port}")).unwrap(),
        access: None,
    };
    let address = normalize("play.example.com").unwrap();
    let want = Want { uuid: ME.into(), room: room_id(&address), address };
    let room = Room::default();
    room.join(Some(want.clone()));
    let server = tokio::spawn(fake_room(listener, want.room.clone(), vec![], Some(4001)));
    let target = live::Target { uuid: ME.into(), token: "room-token".into() };
    let mut out = room.take_out().unwrap();
    assert_eq!(room_connection(&room, &store, &target, &want, &FAST, &mut out).await, RoomEnded::Refused);
    server.await.unwrap();
}

/// By hand, against the website's local demo Store (`FLINT_EMOTE_STORE`, default <http://localhost:3200/>) with its two demo
/// accounts (`FLINT_EMOTE_ACCOUNTS`: a JSON list of {username, uuid, friends_token}): CollectorKai and Marlow in one room;
/// Kai's wave reaches Marlow (with how long ago it started), never Kai; a second wave within 2 s is refused as rate limited;
/// Kai's stop reaches Marlow. The tokens are never printed.
#[tokio::test]
#[ignore]
async fn local_demo_room() {
    let file = std::env::var("FLINT_EMOTE_ACCOUNTS").expect("FLINT_EMOTE_ACCOUNTS");
    let accounts: Vec<Value> = serde_json::from_str(&std::fs::read_to_string(file).unwrap()).unwrap();
    let account = |name: &str| {
        let a = accounts.iter().find(|a| a["username"] == name).expect(name);
        live::Target { uuid: a["uuid"].as_str().unwrap().replace('-', "").to_lowercase(), token: a["friends_token"].as_str().unwrap().to_owned() }
    };
    let (kai, marlow) = (account("CollectorKai"), account("Marlow"));
    let base = std::env::var("FLINT_EMOTE_STORE").unwrap_or_else(|_| "http://localhost:3200/".into());
    let store = crate::store::Endpoints { store: Url::parse(&base).unwrap(), mojang: Url::parse(&base).unwrap(), access: None };
    let address = normalize(&format!("flint-emote-e2e-{}.example.com", now())).unwrap();
    let rooms: Vec<std::sync::Arc<Room>> = (0..2).map(|_| std::sync::Arc::new(Room::default())).collect();
    let mut tasks = Vec::new();
    for (room, who) in rooms.iter().zip([kai.clone(), marlow.clone()]) {
        let want = Want { uuid: who.uuid.clone(), room: room_id(&address), address: address.clone() };
        room.join(Some(want.clone()));
        let (room, store) = (room.clone(), store.clone());
        let mut out = room.take_out().unwrap();
        tasks.push(tokio::spawn(async move { room_connection(&room, &store, &who, &want, &live::TIMING, &mut out).await }));
    }
    let (kai_room, marlow_room) = (&rooms[0], &rooms[1]);
    let started = Instant::now();
    while !(kai_room.is_live() && marlow_room.is_live()) {
        assert!(started.elapsed() < Duration::from_secs(10), "the room never got ready");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    println!("both live in {:?}", started.elapsed());
    let sent = Instant::now();
    assert!(kai_room.send(emote_frame(WAVE)));
    marlow_room.wait_after(0, Duration::from_secs(5)).await;
    let heard = marlow_room.events_after(0).0;
    println!("Marlow heard {:?} after {:?}", heard, sent.elapsed());
    assert_eq!(heard.first().map(|e| (e.kind, e.uuid.clone(), e.cosmetic_id.clone())), Some(("emote", Some(kai.uuid.clone()), Some(WAVE.to_owned()))));
    assert!(heard[0].ago_ms.unwrap() < 2000, "{:?}", heard[0].ago_ms);
    // A second wave at once: refused (2 s between emotes).
    assert!(kai_room.send(emote_frame(WAVE)));
    kai_room.wait_after(0, Duration::from_secs(5)).await;
    let refused = kai_room.events_after(0).0;
    println!("Kai heard {refused:?}");
    assert_eq!(refused.first().map(|e| (e.kind, e.reason.clone())), Some(("refused", Some("rate_limited".to_owned()))));
    let seq = marlow_room.events_after(0).1;
    assert!(kai_room.send(STOP.to_owned()));
    marlow_room.wait_after(seq, Duration::from_secs(5)).await;
    let stop = marlow_room.events_after(seq).0;
    println!("Marlow heard {stop:?}");
    assert_eq!(stop.first().map(|e| (e.kind, e.uuid.clone())), Some(("stop", Some(kai.uuid.clone()))));
    assert_eq!(kai_room.echoes(), 0, "the room echoed Kai's own emote");
    assert!(kai_room.events_after(0).0.iter().all(|e| e.kind != "emote"), "Kai heard an emote (only Kai emoted)");
    for room in &rooms {
        room.join(None);
    }
    for task in tasks {
        let ended = tokio::time::timeout(Duration::from_secs(5), task).await.unwrap().unwrap();
        assert_eq!(ended, RoomEnded::Switched);
    }
}
