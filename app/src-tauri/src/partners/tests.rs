//! The partner feed against a small fake Store (ETag, max-age, 304, 429, 503, offline, oversized and wrong answers), the
//! picture cache, the Play target checks and favourites, with the fixtures the page's tests read too
//! (src/lib/partnerFixtures.json, written by scripts/partner-fixtures.mjs).
use super::*;
use crate::core::TestDir;
use crate::store::{Endpoints, StoreState};
use serde_json::json;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

const FIXTURES: &str = include_str!("../../../src/lib/partnerFixtures.json");

fn fixtures() -> Value {
    serde_json::from_str(FIXTURES).unwrap()
}

fn set(name: &str) -> Value {
    fixtures()["sets"][name].clone()
}

#[derive(Clone)]
struct Reply {
    status: u16,
    headers: Vec<(&'static str, String)>,
    body: Vec<u8>,
    delay: Duration,
}

impl Reply {
    fn json(status: u16, body: &Value) -> Self {
        Self { status, headers: vec![("Content-Type", "application/json".into())], body: serde_json::to_vec(body).unwrap(), delay: Duration::ZERO }
    }
    fn header(mut self, name: &'static str, value: &str) -> Self {
        self.headers.push((name, value.into()));
        self
    }
}

type Handler = Box<dyn FnMut(&str, &HashMap<String, String>) -> Reply + Send>;

/// A one-route-at-a-time HTTP server on this computer that records each request's path and headers.
struct FakeStore {
    url: Url,
    seen: Arc<Mutex<Vec<(String, HashMap<String, String>)>>>,
    handler: Arc<Mutex<Handler>>,
}

impl FakeStore {
    async fn start(handler: impl FnMut(&str, &HashMap<String, String>) -> Reply + Send + 'static) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = Url::parse(&format!("http://{}/", listener.local_addr().unwrap())).unwrap();
        let seen: Arc<Mutex<Vec<(String, HashMap<String, String>)>>> = Arc::default();
        let handler: Arc<Mutex<Handler>> = Arc::new(Mutex::new(Box::new(handler)));
        let (s, h) = (seen.clone(), handler.clone());
        tokio::spawn(async move {
            while let Ok((mut stream, _)) = listener.accept().await {
                let (seen, handler) = (s.clone(), h.clone());
                tokio::spawn(async move {
                    let mut buffer = Vec::new();
                    let mut chunk = [0u8; 4096];
                    while !buffer.windows(4).any(|w| w == b"\r\n\r\n") {
                        match stream.read(&mut chunk).await {
                            Ok(0) | Err(_) => return,
                            Ok(n) => buffer.extend_from_slice(&chunk[..n]),
                        }
                    }
                    let head = String::from_utf8_lossy(&buffer).to_string();
                    let mut lines = head.split("\r\n");
                    let path = lines.next().unwrap_or_default().split(' ').nth(1).unwrap_or_default().to_owned();
                    let headers: HashMap<String, String> = lines.filter_map(|l| l.split_once(':')).map(|(k, v)| (k.trim().to_ascii_lowercase(), v.trim().to_owned())).collect();
                    seen.lock().unwrap().push((path.clone(), headers.clone()));
                    let reply = (handler.lock().unwrap())(&path, &headers);
                    tokio::time::sleep(reply.delay).await;
                    let mut out = format!("HTTP/1.1 {} X\r\nContent-Length: {}\r\nConnection: close\r\n", reply.status, reply.body.len());
                    for (k, v) in &reply.headers {
                        out.push_str(&format!("{k}: {v}\r\n"));
                    }
                    out.push_str("\r\n");
                    let _ = stream.write_all(out.as_bytes()).await;
                    let _ = stream.write_all(&reply.body).await;
                    let _ = stream.shutdown().await;
                });
            }
        });
        Self { url, seen, handler }
    }

    fn requests(&self) -> usize {
        self.seen.lock().unwrap().len()
    }

    fn last_header(&self, name: &str) -> Option<String> {
        self.seen.lock().unwrap().last().and_then(|(_, h)| h.get(name).cloned())
    }

    fn answer(&self, handler: impl FnMut(&str, &HashMap<String, String>) -> Reply + Send + 'static) {
        *self.handler.lock().unwrap() = Box::new(handler);
    }
}

fn state_for(dir: &TestDir, store: &Url) -> AppState {
    let mut state = AppState::for_tests(dir.0.clone());
    state.store = StoreState::new(Endpoints { store: store.clone(), mojang: store.clone(), access: None });
    state
}

/// A Store that answers the feed `body` with ETag `tag`, and 304 when asked with that tag.
fn etag_store(body: Value, tag: &'static str) -> impl FnMut(&str, &HashMap<String, String>) -> Reply + Send + 'static {
    move |path, headers| {
        assert_eq!(path, FEED_PATH);
        if headers.get("if-none-match").map(String::as_str) == Some(tag) {
            return Reply { status: 304, headers: vec![("ETag", tag.into()), ("Cache-Control", "public, max-age=120".into())], body: Vec::new(), delay: Duration::ZERO };
        }
        Reply::json(200, &body).header("ETag", tag).header("Cache-Control", "public, max-age=120")
    }
}

#[tokio::test]
async fn the_feed_is_asked_with_its_etag_and_its_max_age_is_respected() {
    let fake = FakeStore::start(etag_store(set("featured"), "\"p1-aaa\"")).await;
    let dir = TestDir::new();
    let state = state_for(&dir, &fake.url);

    let first = feed(&state, Mode::Auto).await;
    assert_eq!((first.source, first.problem), (Source::Network, None));
    assert_eq!(first.body, Some(set("featured")));
    assert_eq!(fake.last_header("if-none-match"), None, "nothing cached yet");
    assert_eq!(fake.last_header("authorization"), None, "no sign-in is ever sent");
    assert_eq!(first.media_projects, media_projects());
    assert!(first.media_base.contains(media::SCHEME));

    // Within max-age: no request at all.
    let again = feed(&state, Mode::Auto).await;
    assert_eq!((again.source, fake.requests()), (Source::Cache, 1));
    assert_eq!(again.body, first.body);

    // Asked again (the section opened): a conditional request, answered 304, and the cached copy confirmed.
    let confirmed = feed(&state, Mode::Force).await;
    assert_eq!(fake.last_header("if-none-match").as_deref(), Some("\"p1-aaa\""));
    assert_eq!((confirmed.source, confirmed.problem), (Source::Confirmed, None));
    assert_eq!(confirmed.body, first.body);
    assert!(confirmed.fetched_at >= first.fetched_at);

    // A changed feed: a new answer and a new tag.
    fake.answer(etag_store(set("many"), "\"p1-bbb\""));
    let changed = feed(&state, Mode::Force).await;
    assert_eq!((changed.source, changed.body), (Source::Network, Some(set("many"))));
    feed(&state, Mode::Force).await;
    assert_eq!(fake.last_header("if-none-match").as_deref(), Some("\"p1-bbb\""));

    // A new run reads the cached copy for its first paint, without a request.
    let restarted = state_for(&dir, &fake.url);
    let before = fake.requests();
    let cached = feed(&restarted, Mode::Cache).await;
    assert_eq!((cached.source, cached.body, fake.requests()), (Source::Cache, Some(set("many")), before));
}

#[tokio::test]
async fn errors_keep_the_last_good_copy_for_a_short_while() {
    let fake = FakeStore::start(etag_store(set("featured"), "\"p1-aaa\"")).await;
    let dir = TestDir::new();
    let state = state_for(&dir, &fake.url);
    feed(&state, Mode::Force).await;

    fake.answer(|_, _| Reply::json(503, &json!({ "schema": 1, "error": "unavailable" })));
    let down = feed(&state, Mode::Force).await;
    assert_eq!((down.source, down.problem), (Source::Cache, Some(Problem::Unavailable)));
    assert_eq!(down.body, Some(set("featured")), "keeps showing what it had");

    fake.answer(|_, _| Reply::json(429, &json!({ "schema": 1, "error": "rate-limited" })).header("Retry-After", "60"));
    let limited = feed(&state, Mode::Force).await;
    assert_eq!(limited.problem, Some(Problem::RateLimited));
    let before = fake.requests();
    assert_eq!(feed(&state, Mode::Auto).await.source, Source::Cache);
    assert_eq!(fake.requests(), before, "waits for Retry-After");

    for (reply, what) in [
        (Reply::json(200, &json!({ "schema": 2, "partners": [] })), "another schema"),
        (Reply::json(200, &json!({ "schema": 1, "error": "unavailable" })), "an error body"),
        (Reply { status: 200, headers: Vec::new(), body: b"<html>".to_vec(), delay: Duration::ZERO }, "not JSON"),
        (Reply { status: 200, headers: Vec::new(), body: vec![b' '; MAX_FEED + 1], delay: Duration::ZERO }, "over 256 KB"),
    ] {
        fake.answer(move |_, _| reply.clone());
        let odd = feed(&state, Mode::Force).await;
        assert_eq!((odd.problem, odd.body), (Some(Problem::Invalid), Some(set("featured"))), "{what}");
    }

    // The Store unreachable, with a copy older than six hours: nothing is shown.
    let offline_dir = TestDir::new();
    let closed = Url::parse(&format!("http://{}/", std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap())).unwrap();
    let offline = state_for(&offline_dir, &closed);
    let old = CacheFile { etag: None, fetched_at: crate::store::now_millis() - OFFLINE_KEEP_MS - 1, body: set("featured") };
    save_cache(&offline, &old);
    let answer = feed(&offline, Mode::Force).await;
    assert_eq!((answer.source, answer.problem, answer.body), (Source::None, Some(Problem::Offline), None));
    save_cache(&offline, &CacheFile { fetched_at: crate::store::now_millis() - 60_000, ..old });
    let recent = feed(&offline, Mode::Force).await;
    assert_eq!((recent.source, recent.problem, recent.body), (Source::Cache, Some(Problem::Offline), Some(set("featured"))));
}

#[tokio::test]
async fn one_request_at_a_time_even_when_the_store_is_slow() {
    let body = set("featured");
    let fake = FakeStore::start(move |_, _| {
        let mut reply = Reply::json(200, &body).header("ETag", "\"p1-slow\"");
        reply.delay = Duration::from_millis(400);
        reply
    })
    .await;
    let dir = TestDir::new();
    let state = state_for(&dir, &fake.url);
    let (a, b) = tokio::join!(feed(&state, Mode::Auto), feed(&state, Mode::Auto));
    assert_eq!(fake.requests(), 1);
    assert_eq!(a.source, Source::Network);
    assert_eq!((b.source, b.body), (Source::Cache, a.body), "the second waited for the first one's answer");
}

#[test]
fn every_fixture_is_a_feed_and_only_partner_media_pictures_are_indexed() {
    let projects = fixtures()["mediaProjects"].as_array().unwrap().iter().map(|p| p.as_str().unwrap().to_owned()).collect::<Vec<_>>();
    for (name, body) in fixtures()["sets"].as_object().unwrap() {
        assert!(is_feed(body), "{name}");
    }
    let featured = media::index(&set("featured"), &projects);
    assert_eq!(featured.len(), 12, "three pictures for each of four listings");
    assert!(featured.values().all(|u| u.as_str().contains(".supabase.co/storage/v1/object/public/partner-media/partners/")));
    assert_eq!(media::index(&set("example"), &projects).len(), 4, "the Store's example: two for Ember SMP, one each for the others");
    assert_eq!(media::index(&set("featured"), &["abcdefghijklmnopqrst".into()]).len(), 0, "another project's pictures");

    let invalid = media::index(&set("invalid"), &projects);
    let partners = set("invalid")["partners"].as_array().unwrap().clone();
    let shas_of = |slug: &str| -> Vec<String> {
        let p = partners.iter().find(|p| p["slug"] == slug).unwrap();
        ["featured_image", "library_image", "logo"].iter().filter_map(|f| p[f]["sha256"].as_str().map(str::to_owned)).collect()
    };
    for slug in ["wronghost", "querybanner", "otherlisting", "wronghash"] {
        assert!(!invalid.contains_key(&shas_of(slug)[0]), "{slug}'s featured picture");
    }
    let extras = shas_of("badextras");
    assert!(invalid.contains_key(&extras[0]), "its featured picture is fine");
    assert!(!invalid.contains_key(&extras[1]) && !invalid.contains_key(&extras[2]), "another bucket, a .gif");

    let id = "7a1e0001-5c2d-4b8e-9f10-000000000001";
    let sha = "a".repeat(64);
    let good = format!("https://vkqzlqjbpvxrskwpvijt.supabase.co/storage/v1/object/public/partner-media/partners/{id}/{sha}.webp");
    assert!(media::media_url(&good, id, &sha, &projects).is_some());
    for bad in [
        good.replace("https", "http"),
        format!("{good}?x=1"),
        good.replace(".webp", ".svg"),
        good.replace("/partners/", "/partners/../"),
        good.replace(".supabase.co", ".supabase.co.evil.example"),
        good.replace("vkqzlqjbpvxrskwpvijt", "VKQZLQJBPVXRSKWPVIJT"),
    ] {
        assert!(media::media_url(&bad, id, &sha, &projects).is_none(), "{bad}");
    }
    assert!(media::media_url(&good, "7a1e0002-5c2d-4b8e-9f10-000000000002", &sha, &projects).is_none(), "another listing's folder");
    assert!(media::media_url(&good, id, &"b".repeat(64), &projects).is_none(), "a checksum that isn't the file's name");
}

const PNG: &[u8] = b"\x89PNG\r\n\x1a\nfeatured-pixels";

fn sha(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

#[tokio::test]
async fn pictures_are_checked_against_their_checksum_and_kept_once() {
    let fake = FakeStore::start(|path, _| match path {
        "/ok.png" => Reply { status: 200, headers: Vec::new(), body: PNG.to_vec(), delay: Duration::ZERO },
        "/text" => Reply { status: 200, headers: Vec::new(), body: b"not a picture".to_vec(), delay: Duration::ZERO },
        "/huge" => Reply { status: 200, headers: Vec::new(), body: [PNG, &vec![0u8; 2 * 1024 * 1024]].concat(), delay: Duration::ZERO },
        _ => Reply { status: 404, headers: Vec::new(), body: Vec::new(), delay: Duration::ZERO },
    })
    .await;
    let dir = TestDir::new();
    let state = state_for(&dir, &fake.url);
    let cache = media::dir(&state);
    let at = |path: &str| fake.url.join(path).unwrap();
    let good = sha(PNG);

    assert_eq!(media::fetch(&state.http, &cache, &good, &at("/ok.png")).await, Some((PNG.to_vec(), "image/png")));
    assert_eq!(std::fs::read(cache.join(&good)).unwrap(), PNG, "kept by its checksum");
    let asked = fake.requests();
    assert!(media::fetch(&state.http, &cache, &good, &at("/ok.png")).await.is_some());
    assert_eq!(fake.requests(), asked, "downloaded once per checksum");

    // A cached file that changed on disk is fetched again.
    std::fs::write(cache.join(&good), b"tampered").unwrap();
    assert!(media::fetch(&state.http, &cache, &good, &at("/ok.png")).await.is_some());
    assert_eq!(fake.requests(), asked + 1);

    assert_eq!(media::fetch(&state.http, &cache, &"0".repeat(64), &at("/ok.png")).await, None, "the wrong checksum");
    assert!(!cache.join("0".repeat(64)).exists(), "and nothing kept");
    assert_eq!(media::fetch(&state.http, &cache, &sha(b"not a picture"), &at("/text")).await, None, "not a PNG, JPEG or WebP");
    let huge = [PNG, &vec![0u8; 2 * 1024 * 1024]].concat();
    assert_eq!(media::fetch(&state.http, &cache, &sha(&huge), &at("/huge")).await, None, "over 2 MB");
    assert_eq!(media::fetch(&state.http, &cache, &good.replace('a', "b"), &at("/missing")).await, None);

    // The page only gets pictures the current feed lists.
    assert_eq!(media::serve(&state, &format!("/{good}")).await, None);
    *state.store.partners.index.lock().unwrap() = Some(HashMap::from([(good.clone(), at("/ok.png"))]));
    assert_eq!(media::serve(&state, &format!("/{good}")).await.map(|(_, k)| k), Some("image/png"));
    assert_eq!(media::serve(&state, "/../../settings.json").await, None);

    assert_eq!(media::content_type(&[0xff, 0xd8, 0xff, 0xe0]), Some("image/jpeg"));
    assert_eq!(media::content_type(b"RIFF\0\0\0\0WEBPVP8 "), Some("image/webp"));
    assert_eq!(media::content_type(b"<svg"), None);

    // Pruning keeps what the feed uses.
    std::fs::write(cache.join("f".repeat(64)), b"old").unwrap();
    media::prune(&cache, &HashSet::from([good.clone()]));
    assert!(cache.join(&good).exists());
    assert!(cache.join("f".repeat(64)).exists(), "unused, but recent and the cache is small");
}

#[test]
fn play_joins_only_a_running_java_server_listing_at_a_public_address() {
    let fx = fixtures();
    let now = crate::store::rfc3339_secs(fx["now"].as_str().unwrap()).unwrap();
    let many = set("many");
    let id_of = |body: &Value, slug: &str| body["partners"].as_array().unwrap().iter().find(|p| p["slug"] == slug).unwrap()["id"].as_str().unwrap().to_owned();

    let ember = play::target_in(&many, &id_of(&many, "emberfall"), now, false).unwrap();
    assert_eq!(ember.join, play::Join { name: "Emberfall Network".into(), host: "play.emberfall.example".into(), port: 25565, address: "play.emberfall.example".into() });
    assert!(rules::version_fits("1.8.9", ember.versions.as_ref()) && rules::version_fits("26.3", ember.versions.as_ref()));
    let sky = play::target_in(&many, &id_of(&many, "skyhollow"), now, false).unwrap();
    assert_eq!(sky.join.address, "mc.skyhollow.example:25570");
    assert!(!rules::version_fits("1.8.9", sky.versions.as_ref()));
    let pvp = play::target_in(&many, &id_of(&many, "northwind"), now, false).unwrap();
    assert_eq!((pvp.join.port, pvp.versions.unwrap().list), (25565, Some(vec!["1.8.9".to_owned()])), "no port: Minecraft's default");

    assert!(play::target_in(&many, &id_of(&many, "forgelight"), now, false).unwrap_err().contains("isn't a Minecraft server"));
    assert!(play::target_in(&many, &id_of(&many, "cinderbloc"), now, false).is_err());
    let frostfire = id_of(&many, "frostfire");
    assert!(play::target_in(&many, &frostfire, now, false).is_ok(), "featured-only, running now");
    let ends = crate::store::rfc3339_secs(many["partners"][0]["ends_at"].as_str().unwrap()).unwrap();
    assert!(play::target_in(&many, &frostfire, ends, false).unwrap_err().contains("isn't running"));

    let expired = set("expired");
    assert!(play::target_in(&expired, &id_of(&expired, "lastseason"), now, false).unwrap_err().contains("isn't running"));
    assert!(play::target_in(&expired, &id_of(&expired, "nextmonth"), now, false).unwrap_err().contains("isn't running"));

    let invalid = set("invalid");
    assert!(play::target_in(&invalid, &id_of(&invalid, "bedrockonly"), now, false).unwrap_err().contains("another edition"));
    assert_eq!(play::target_in(&invalid, &id_of(&invalid, "privateip"), now, false).unwrap_err(), rules::PRIVATE);
    assert_eq!(play::target_in(&invalid, &id_of(&invalid, "linklocal"), now, false).unwrap_err(), rules::PRIVATE);
    assert!(play::target_in(&invalid, &id_of(&invalid, "loopback"), now, false).is_err());
    assert!(play::target_in(&invalid, &id_of(&invalid, "loopback"), now, true).is_ok(), "development builds may name this computer");
    assert!(play::target_in(&invalid, &id_of(&invalid, "badport"), now, false).is_err());
    assert!(play::target_in(&invalid, &id_of(&invalid, "badschedule"), now, false).is_err(), "a schedule it can't read");
    assert!(play::target_in(&invalid, "7a1e9999-5c2d-4b8e-9f10-000000009999", now, false).is_err(), "not listed");
    assert!(play::target_in(&invalid, "not-a-uuid", now, false).is_err());
}

#[test]
fn a_confirmed_server_goes_into_the_profile_list_once() {
    let dir = TestDir::new();
    let state = AppState::for_tests(dir.0.clone());
    let profile = state.paths.profile_dir("p1");
    std::fs::create_dir_all(&profile).unwrap();
    let join = play::Join { name: "Skyhollow SMP".into(), host: "mc.skyhollow.example".into(), port: 25570, address: "mc.skyhollow.example:25570".into() };
    let first = play::list_server(&state, "p1", &join);
    assert_eq!(first, play::Listing { address: "mc.skyhollow.example:25570".into(), added: true, already_listed: false, problem: None });
    let second = play::list_server(&state, "p1", &join);
    assert_eq!(second, play::Listing { address: "mc.skyhollow.example:25570".into(), added: false, already_listed: true, problem: None });
    assert_eq!(crate::gamesettings::serverlist::listed(&profile, "MC.Skyhollow.example:25570").unwrap().as_deref(), Some("mc.skyhollow.example:25570"));
}

#[test]
fn favourites_are_kept_for_this_launcher() {
    let dir = TestDir::new();
    let state = AppState::for_tests(dir.0.clone());
    assert!(favorites(&state).is_empty());
    let (a, b) = ("7a1e0001-5c2d-4b8e-9f10-000000000001", "7a1e0002-5c2d-4b8e-9f10-000000000002");
    assert_eq!(set_favorite(&state, a, true).unwrap(), [a]);
    assert_eq!(set_favorite(&state, b, true).unwrap(), [b, a], "newest first");
    assert_eq!(set_favorite(&state, a, true).unwrap(), [a, b], "never twice");
    assert_eq!(set_favorite(&state, b, false).unwrap(), [a]);
    assert!(set_favorite(&state, "../../x", true).is_err());
    assert_eq!(favorites(&AppState::for_tests(dir.0.clone())), [a], "kept on disk");
}
