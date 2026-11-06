//! The Store client against the fake Store and Mojang in `fake.rs` (always), and against a real local Store dev server with
//! the fake Mojang (`e2e_against_a_local_store`, opt-in: see docs/STORE-INTEGRATION.md).
use super::fake::{dashed, rfc3339, Fake};
use super::*;
use crate::core::{AppState, MemoryCredentials, RunningGame, TestDir};
use std::path::Path;
use std::sync::atomic::Ordering;
use std::sync::Arc;

pub(super) const STEVE: &str = "8667ba71b85a4004af54457a9734eed7";
pub(super) const ALEX: &str = "ec561538f3fd461daff5086b22154bce";
pub(super) const EMBER: &[u8] = b"\x89PNG\r\n\x1a\nember-cape-pixels";
pub(super) const FROST: &[u8] = b"\x89PNG\r\n\x1a\nfrost-cape-pixels";

pub(super) struct Rig {
    pub(super) dir: TestDir,
    pub(super) state: AppState,
    pub(super) keychain: Arc<MemoryCredentials>,
    pub(super) fake: Fake,
    pub(super) ember: String,
    pub(super) frost: String,
}

fn accounts(state: &AppState, list: &[(&str, &str)]) {
    let accounts: Vec<Value> = list.iter().map(|(uuid, name)| json!({ "uuid": uuid, "username": name, "addedAt": 1 })).collect();
    write_json(&state.paths.accounts(), &json!({ "active": list[0].0, "accounts": accounts })).unwrap();
}

pub(super) async fn rig() -> Rig {
    let fake = Fake::start().await;
    let dir = TestDir::new();
    let mut state = AppState::for_tests(dir.0.clone());
    let keychain = Arc::new(MemoryCredentials::default());
    state.credentials = Box::new(keychain.clone());
    state.store = StoreState::new(Endpoints { store: fake.url(), mojang: fake.url(), access: None });
    accounts(&state, &[(STEVE, "Steve"), (ALEX, "Alex")]);
    let (ember, frost) = fake.with(|w| {
        w.add_mojang_account("mc-token-steve", STEVE, "Steve");
        w.add_mojang_account("mc-token-alex", ALEX, "Alex");
        (w.add_cosmetic("ember-cape", "cape", EMBER), w.add_cosmetic("frost-cape", "cape", FROST))
    });
    Rig { dir, state, keychain, fake, ember, frost }
}

impl Rig {
    pub(super) async fn sign_in(&self, uuid: &str) -> Session {
        let (name, token) = if uuid == STEVE { ("Steve", "mc-token-steve") } else { ("Alex", "mc-token-alex") };
        sign_in_with_token(&self.state, uuid, name, token).await.unwrap()
    }
    pub(super) fn saved(&self, uuid: &str) -> Option<String> {
        self.keychain.secrets.lock().unwrap().get(&credential_name(&self.state, uuid)).cloned()
    }
    pub(super) async fn view(&self, uuid: &str) -> StoreView {
        view(&self.state, uuid, false).await
    }
    pub(super) fn grant(&self, uuid: &str, cosmetic: &str) {
        self.fake.with(|w| w.grant(uuid, cosmetic));
    }
    /// A new launcher run: what was in memory is gone; the keychain and the cache files stay.
    pub(super) fn restart(&mut self) {
        self.state.store = StoreState::new(self.state.store.endpoints.clone());
    }
}

pub(super) fn owned(view: &StoreView) -> Vec<String> {
    view.inventory.as_ref().map(|i| i.owned.iter().map(|o| o.slug.clone()).collect()).unwrap_or_default()
}

fn files_containing(dir: &Path, needle: &str) -> Vec<PathBuf> {
    let mut found = Vec::new();
    for entry in std::fs::read_dir(dir).into_iter().flatten().flatten() {
        let path = entry.path();
        if path.is_dir() {
            found.extend(files_containing(&path, needle));
        } else if std::fs::read(&path).is_ok_and(|b| String::from_utf8_lossy(&b).contains(needle)) {
            found.push(path);
        }
    }
    found
}

#[tokio::test]
async fn signing_in_proves_the_account_at_mojang_and_keeps_the_session_in_the_keychain_only() {
    let mut rig = rig().await;
    let session = rig.sign_in(STEVE).await;
    assert_eq!(session.name, "Steve");
    let saved: Session = serde_json::from_str(&rig.saved(STEVE).expect("kept in the credential store")).unwrap();
    assert_eq!(saved, session);
    assert!(rig.saved(ALEX).is_none());
    assert!(rig.fake.with(|w| w.count("POST /session/minecraft/join 204")) == 1, "Mojang saw the join");

    let view = rig.view(STEVE).await;
    assert!(view.signed_in && !view.stale && !view.locked && view.problem.is_none(), "{view:?}");
    assert_eq!(view.name.as_deref(), Some("Steve"));
    assert_eq!(view.inventory.as_ref().unwrap().owned, Vec::<Owned>::new());

    assert!(files_containing(&rig.dir.0, &session.token).is_empty(), "the Store session is in no file");
    assert!(files_containing(&rig.dir.0, "mc-token-steve").is_empty(), "nor the Minecraft token");
    assert!(!format!("{session:?}").contains(&session.token), "a session's Debug is redacted");

    // The next run reads the keychain once, however often the page asks.
    rig.restart();
    let reads = rig.keychain.reads.load(Ordering::SeqCst);
    assert!(rig.view(STEVE).await.signed_in);
    assert!(rig.view(STEVE).await.signed_in);
    assert_eq!(rig.keychain.reads.load(Ordering::SeqCst), reads + 1);
}

#[tokio::test]
async fn an_unchanged_inventory_costs_a_304_and_a_purchase_shows_after_the_change_feed() {
    let mut rig = rig().await;
    let session = rig.sign_in(STEVE).await;
    let (first, how) = fetch_inventory(&rig.state, STEVE, &session.token).await.unwrap();
    assert_eq!(how, Fetched::Fresh);
    let (again, how) = fetch_inventory(&rig.state, STEVE, &session.token).await.unwrap();
    assert_eq!((how, &again), (Fetched::NotModified, &first));
    assert_eq!(rig.fake.with(|w| w.count("GET /api/v1/me/inventory/ 304")), 1);

    assert_eq!(changes(&rig.state, STEVE, Some(first.version)).await, Changes::Ok { changed: false, version: first.version, poll_after_seconds: 25 });
    rig.grant(STEVE, &rig.ember); // a Store purchase confirmed elsewhere
    assert_eq!(changes(&rig.state, STEVE, Some(first.version)).await, Changes::Ok { changed: true, version: first.version + 1, poll_after_seconds: 25 });
    let view = rig.view(STEVE).await;
    assert_eq!(owned(&view), ["ember-cape"]);
    assert_eq!(view.inventory.as_ref().unwrap().version, first.version + 1);

    // The saved ETag still saves the download after a restart.
    rig.restart();
    let (_, how) = fetch_inventory(&rig.state, STEVE, &session.token).await.unwrap();
    assert_eq!(how, Fetched::NotModified);

    // The poll interval is the Store's, within bounds.
    rig.fake.with(|w| w.poll_after_seconds = 1);
    assert!(matches!(changes(&rig.state, STEVE, None).await, Changes::Ok { changed: true, poll_after_seconds: 10, .. }));
    rig.fake.with(|w| w.poll_after_seconds = 86_400);
    assert!(matches!(changes(&rig.state, STEVE, None).await, Changes::Ok { poll_after_seconds: 300, .. }));
}

#[tokio::test]
async fn equipping_is_the_stores_decision_and_a_running_game_hears_of_it() {
    let rig = rig().await;
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);
    rig.state.running.lock().unwrap().insert("p1".into(), RunningGame { kill: None });

    let view = equip(&rig.state, STEVE, "cape", Some(&rig.ember)).await.unwrap();
    assert_eq!(view.inventory.as_ref().unwrap().equipped.get("cape"), Some(&rig.ember));
    let handoff: Value = read_json(&rig.state.paths.profile_dir("p1").join(handoff::FILE));
    assert!(handoff["equipRevision"].as_u64().unwrap() > 0, "{handoff}");

    // What the Store refuses changes nothing.
    let refused = equip(&rig.state, STEVE, "cape", Some(&rig.frost)).await.unwrap_err();
    assert!(refused.contains("doesn't own"), "{refused}");
    let refused = equip(&rig.state, STEVE, "head", Some(&rig.ember)).await.unwrap_err();
    assert!(refused.contains("can't be worn there"), "{refused}");
    assert_eq!(rig.view(STEVE).await.inventory.unwrap().equipped.get("cape"), Some(&rig.ember));

    // Checked here before anything is sent.
    let before = rig.fake.with(|w| w.log.len());
    assert!(equip(&rig.state, STEVE, "boots", Some(&rig.ember)).await.is_err());
    assert!(equip(&rig.state, STEVE, "cape", Some("not-a-uuid")).await.is_err());
    assert_eq!(rig.fake.with(|w| w.log.len()), before);

    let view = equip(&rig.state, STEVE, "cape", None).await.unwrap();
    assert!(view.inventory.unwrap().equipped.is_empty());
}

#[tokio::test]
async fn a_texture_is_used_only_when_it_matches_the_stores_hash() {
    let rig = rig().await;
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);
    rig.grant(STEVE, &rig.frost);
    let view = rig.view(STEVE).await;
    let ember = view.inventory.as_ref().unwrap().owned.iter().find(|o| o.slug == "ember-cape").unwrap().clone();

    let url = asset(&rig.state, STEVE, &rig.ember).await.unwrap();
    assert_eq!(STANDARD.decode(url.strip_prefix("data:image/png;base64,").unwrap()).unwrap(), EMBER);
    // Cached by (asset id, version) for this account, and served from there.
    let file = account_dir(&rig.state, STEVE).join("assets").join(format!("{}-v1.png", ember.asset.as_ref().unwrap().id));
    assert_eq!(std::fs::read(&file).unwrap(), EMBER);
    asset(&rig.state, STEVE, &rig.ember).await.unwrap();
    assert_eq!(rig.fake.with(|w| w.count("GET /assets/ember-cape.png 200")), 1);

    // A cached copy that changed on disk is fetched again.
    std::fs::write(&file, b"\x89PNG\r\n\x1a\nedited").unwrap();
    assert_eq!(asset(&rig.state, STEVE, &rig.ember).await.unwrap(), url);
    assert_eq!(rig.fake.with(|w| w.count("GET /assets/ember-cape.png 200")), 2);

    // Bytes that aren't the ones the Store hashed are refused and never kept.
    rig.fake.with(|w| w.files.insert("/assets/frost-cape.png".into(), b"\x89PNG\r\n\x1a\nswapped".to_vec()));
    assert_eq!(asset(&rig.state, STEVE, &rig.frost).await.unwrap_err(), HASH_MISMATCH);
    let kept: Vec<_> = std::fs::read_dir(account_dir(&rig.state, STEVE).join("assets")).unwrap().flatten().collect();
    assert_eq!(kept.len(), 1, "only the verified ember texture");

    // Only items in this account's inventory.
    assert!(asset(&rig.state, STEVE, "00000000-0000-4000-8000-000000000000").await.is_err());
    assert!(asset(&rig.state, ALEX, &rig.ember).await.is_err());
}

#[tokio::test]
async fn switching_accounts_never_shows_or_uses_the_other_accounts_session_or_items() {
    let rig = rig().await;
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);
    assert_eq!(owned(&rig.view(STEVE).await), ["ember-cape"]);

    let alex = rig.view(ALEX).await;
    assert!(!alex.signed_in && alex.inventory.is_none(), "Alex isn't signed in to the Store: {alex:?}");
    assert!(equip(&rig.state, ALEX, "cape", Some(&rig.ember)).await.unwrap_err().contains("Sign in"));

    rig.sign_in(ALEX).await;
    assert_ne!(rig.saved(ALEX), rig.saved(STEVE));
    let alex = rig.view(ALEX).await;
    assert!(alex.signed_in && owned(&alex).is_empty());
    assert!(equip(&rig.state, ALEX, "cape", Some(&rig.ember)).await.unwrap_err().contains("doesn't own"));
    assert!(asset(&rig.state, ALEX, &rig.ember).await.is_err());

    // Signing one account out leaves the other alone.
    sign_out(&rig.state, ALEX).await;
    assert!(rig.saved(ALEX).is_none() && rig.saved(STEVE).is_some());
    assert_eq!(owned(&rig.view(STEVE).await), ["ember-cape"]);
    assert!(!account_dir(&rig.state, ALEX).exists() && account_dir(&rig.state, STEVE).exists());
}

#[tokio::test]
async fn an_expired_session_asks_to_sign_in_again() {
    let mut rig = rig().await;
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);
    rig.view(STEVE).await;

    // The Store ended it.
    rig.fake.with(|w| w.expire_sessions());
    let view = rig.view(STEVE).await;
    assert!(!view.signed_in && view.inventory.is_none(), "{view:?}");
    assert_eq!(view.problem.as_deref(), Some(SIGN_IN_AGAIN));
    assert!(rig.saved(STEVE).is_none() && !cache_path(&rig.state, STEVE).exists());
    assert_eq!(equip(&rig.state, STEVE, "cape", Some(&rig.ember)).await.unwrap_err(), "Sign in to the Flint Store first.");
    assert_eq!(changes(&rig.state, STEVE, None).await, Changes::SignedOut);

    // One past its expiry by this computer's clock isn't even sent.
    let mut session = rig.sign_in(STEVE).await;
    session.expires_at = rfc3339(now_secs() as i64 - 60);
    rig.keychain.secrets.lock().unwrap().insert(credential_name(&rig.state, STEVE), serde_json::to_string(&session).unwrap());
    rig.restart();
    let before = rig.fake.with(|w| w.log.len());
    assert!(!rig.view(STEVE).await.signed_in);
    assert_eq!(rig.fake.with(|w| w.log.len()), before, "nothing was sent");
    assert!(rig.saved(STEVE).is_none());
}

#[tokio::test]
async fn offline_shows_the_last_copy_marked_stale_and_changes_nothing() {
    let rig = rig().await;
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);
    let online = rig.view(STEVE).await;

    rig.fake.with(|w| w.offline = true);
    let view = rig.view(STEVE).await;
    assert!(view.signed_in && view.stale, "{view:?}");
    assert_eq!(view.inventory, online.inventory, "the Store's last answer, unchanged");
    assert_eq!(view.problem.as_deref(), Some(OFFLINE));
    assert!(view.fetched_at.is_some());
    assert_eq!(equip(&rig.state, STEVE, "cape", Some(&rig.ember)).await.unwrap_err(), OFFLINE);
    assert_eq!(approve_link(&rig.state, STEVE, "GXV9-MC1G").await.unwrap_err(), OFFLINE);
    assert_eq!(changes(&rig.state, STEVE, None).await, Changes::Offline);
    assert_eq!(rig.view(STEVE).await.inventory.unwrap().equipped, BTreeMap::new(), "nothing was changed locally");

    // Back online, a refund that happened meanwhile wins over the copy.
    rig.fake.with(|w| {
        w.offline = false;
        w.revoke(STEVE, &rig.ember);
    });
    let view = rig.view(STEVE).await;
    assert!(!view.stale && owned(&view).is_empty());
    assert!(asset(&rig.state, STEVE, &rig.ember).await.is_err(), "a revoked item's texture isn't served either");

    // Offline before any copy was made: nothing is shown, nothing invented.
    rig.sign_in(ALEX).await;
    rig.fake.with(|w| w.offline = true);
    let alex = rig.view(ALEX).await;
    assert!(alex.stale && alex.inventory.is_none());
}

#[tokio::test]
async fn a_launcher_cant_sign_in_as_an_account_it_doesnt_hold() {
    let rig = rig().await;
    // Alex's Minecraft token for Steve's profile: Mojang refuses the join.
    let refused = sign_in_with_token(&rig.state, STEVE, "Steve", "mc-token-alex").await.unwrap_err();
    assert!(refused.contains("Mojang didn't accept"), "{refused}");
    assert!(rig.saved(STEVE).is_none());

    // A modified launcher joins as Alex but claims to be Steve: the Store's own Mojang check refuses it.
    let (challenge_id, server_id) = challenge(&rig.state).await.unwrap();
    mojang_join(&rig.state, "mc-token-alex", ALEX, &server_id).await.unwrap();
    let verify = call(&rig.state, Method::POST, "/api/v1/auth/verify/", None, Some(json!({ "challengeId": challenge_id, "username": "Steve" })), None).await;
    assert!(matches!(verify, Err(Failure::Refused { status: 403, ref code }) if code == "not_verified"));

    // A Store answer for another account than the one signing in is never kept.
    rig.fake.with(|w| w.verify_as = Some((ALEX.into(), "Alex".into())));
    let refused = sign_in_with_token(&rig.state, STEVE, "Steve", "mc-token-steve").await.unwrap_err();
    assert!(refused.contains("different Minecraft account"), "{refused}");
    assert!(rig.saved(STEVE).is_none() && rig.saved(ALEX).is_none());
}

#[tokio::test]
async fn a_session_belonging_to_another_account_is_refused() {
    let rig = rig().await;
    let steve = rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);
    rig.grant(ALEX, &rig.frost);

    // Steve's session copied into Alex's credential: the Store answers for Steve, so Flint drops it for Alex.
    rig.keychain.secrets.lock().unwrap().insert(credential_name(&rig.state, ALEX), serde_json::to_string(&steve).unwrap());
    let alex = rig.view(ALEX).await;
    assert!(!alex.signed_in && alex.inventory.is_none(), "{alex:?}");
    assert!(alex.problem.unwrap().contains("different Minecraft account"));
    assert!(rig.saved(ALEX).is_none() && !cache_path(&rig.state, ALEX).exists());

    // Used directly, the stolen token only ever reaches Steve's things: the Store refuses Alex's cape.
    let stolen = call(&rig.state, Method::PUT, "/api/v1/me/equipped/", Some(&steve.token), Some(json!({ "slot": "cape", "cosmeticId": rig.frost })), None).await;
    assert!(matches!(stolen, Err(Failure::Refused { status: 403, ref code }) if code == "not_owned"));

    // A Store that answered with another account's inventory would be refused the same way.
    rig.fake.with(|w| w.inventory_as = Some(dashed(ALEX)));
    let view = rig.view(STEVE).await;
    assert!(!view.signed_in && view.problem.unwrap().contains("different Minecraft account"));
}

#[tokio::test]
async fn signing_out_revokes_the_session_and_forgets_the_copy() {
    let rig = rig().await;
    let session = rig.sign_in(STEVE).await;
    rig.view(STEVE).await;
    assert!(cache_path(&rig.state, STEVE).exists());

    sign_out(&rig.state, STEVE).await;
    assert!(rig.saved(STEVE).is_none() && !account_dir(&rig.state, STEVE).exists());
    let reused = call(&rig.state, Method::GET, "/api/v1/me/inventory/", Some(&session.token), None, None).await;
    assert_eq!(reused.err(), Some(Failure::SignedOut), "revoked at the Store");
    assert!(!rig.view(STEVE).await.signed_in);
}

#[tokio::test]
async fn removing_an_account_from_flint_forgets_its_store_session() {
    let rig = rig().await;
    rig.sign_in(STEVE).await;
    rig.view(STEVE).await;
    assert!(crate::auth::remove_account(&rig.state, STEVE).unwrap());
    assert!(rig.saved(STEVE).is_none() && !account_dir(&rig.state, STEVE).exists());
}

#[tokio::test]
async fn a_browser_sign_in_is_approved_only_for_a_code_waiting_for_it() {
    let rig = rig().await;
    rig.fake.with(|w| w.links.insert("GXV9-MC1G".into(), None));
    assert_eq!(approve_link(&rig.state, STEVE, "GXV9-MC1G").await.unwrap_err(), "Sign in to the Flint Store first.");

    rig.sign_in(STEVE).await;
    approve_link(&rig.state, STEVE, " gxv9 mc1g ").await.unwrap();
    assert_eq!(rig.fake.with(|w| w.links["GXV9-MC1G"].clone()), Some(dashed(STEVE)));
    let used = approve_link(&rig.state, STEVE, "GXV9-MC1G").await.unwrap_err();
    assert!(used.starts_with(&format!("{} has no sign-in waiting", rig.state.store.endpoints.host())), "used once: {used}");
    assert!(used.contains("10 minutes") && used.contains("staging"), "says why a code can be refused: {used}");
    assert!(rig.view(STEVE).await.signed_in, "a refused code keeps the session");

    let before = rig.fake.with(|w| w.log.len());
    assert!(approve_link(&rig.state, STEVE, "not-a-real-code").await.is_err());
    assert_eq!(rig.fake.with(|w| w.log.len()), before, "a malformed code is never sent");
}

#[tokio::test]
async fn a_code_from_another_store_is_refused_naming_the_store_this_flint_uses() {
    // The live Store and staging keep their codes in separate databases: a code the staging website shows waits only there,
    // so a launcher connected to the live Store can't approve it, and leaves it waiting.
    let live = rig().await;
    let staging = rig().await;
    staging.fake.with(|w| w.links.insert("GXV9-MC1G".into(), None));
    live.sign_in(STEVE).await;
    let refused = approve_link(&live.state, STEVE, "GXV9-MC1G").await.unwrap_err();
    assert!(refused.starts_with(&format!("{} has no sign-in waiting", live.state.store.endpoints.host())), "{refused}");
    assert_eq!(staging.fake.with(|w| w.links["GXV9-MC1G"].clone()), None, "still waiting at the Store that showed it");

    staging.sign_in(STEVE).await;
    approve_link(&staging.state, STEVE, "GXV9-MC1G").await.unwrap();
    assert_eq!(staging.fake.with(|w| w.links["GXV9-MC1G"].clone()), Some(dashed(STEVE)));
}

#[tokio::test]
async fn wallet_and_orders_are_the_stores() {
    let rig = rig().await;
    rig.sign_in(STEVE).await;
    rig.fake.with(|w| w.players.get_mut(&dashed(STEVE)).unwrap().balance = 500);
    let wallet = wallet(&rig.state, STEVE).await.unwrap();
    assert_eq!(wallet.balance, Some(500));
    assert_eq!(wallet.ledger[0].delta, Some(500));
    assert_eq!(rig.view(STEVE).await.inventory.unwrap().balance, Some(500));
    let orders = orders(&rig.state, STEVE).await.unwrap();
    assert_eq!((orders[0].amount_minor, orders[0].test_mode), (Some(499), true), "numeric text is read as a number");
    assert_eq!(orders[0].reference.as_deref(), Some("FL-7Q2M-K4"), "a cart's lines are grouped by their checkout");
}

#[test]
fn codes_are_read_the_way_the_store_writes_them() {
    assert_eq!(normalize_code("GXV9-MC1G").as_deref(), Some("GXV9-MC1G"));
    assert_eq!(normalize_code(" gxv9mc1g ").as_deref(), Some("GXV9-MC1G"));
    assert_eq!(normalize_code("gxv9 - mclg").as_deref(), Some("GXV9-MC1G"), "l is read as 1");
    assert_eq!(normalize_code("O0I1-ABCD").as_deref(), Some("0011-ABCD"));
    assert_eq!(normalize_code("GXV9-MC1U"), None, "U isn't in the Store's alphabet");
    assert_eq!(normalize_code("GXV9-MC1"), None);
    assert_eq!(normalize_code("GXV9-MC1GG"), None);
    assert_eq!(normalize_code("ГXV9-MC1G"), None);
}

#[test]
fn times_are_read_as_postgres_and_javascript_write_them() {
    assert_eq!(rfc3339_secs("1970-01-01T00:00:00Z"), Some(0));
    assert_eq!(rfc3339_secs("2026-10-29T12:00:00.123456+00:00"), Some(1_793_275_200));
    assert_eq!(rfc3339_secs("2026-10-29 14:00:00+02"), Some(1_793_275_200));
    assert_eq!(rfc3339_secs("2026-10-29T07:30:00-04:30"), Some(1_793_275_200));
    assert_eq!(rfc3339_secs("2000-02-29T00:00:00Z"), Some(951_782_400));
    for secs in [0, 951_782_400, 1_793_275_200, 4_102_444_799] {
        assert_eq!(rfc3339_secs(&rfc3339(secs)), Some(secs));
    }
    for bad in ["", "tomorrow", "2026-10-29", "2026-13-01T00:00:00Z", "2026-10-29T12:00:00", "2026-10-29T12:00:00+2"] {
        assert_eq!(rfc3339_secs(bad), None, "{bad}");
    }
}

#[test]
fn only_a_safe_store_address_is_accepted() {
    assert_eq!(store_origin("https://store.flintclient.com").unwrap().as_str(), "https://store.flintclient.com/");
    assert!(store_origin("http://localhost:3214").is_some());
    assert!(store_origin("http://127.0.0.1:3214/").is_some());
    for bad in ["http://store.flintclient.com", "https://store.flintclient.com/api", "https://user:pw@store.flintclient.com", "https://store.flintclient.com/?a=1", "file:///c:/x", "store.flintclient.com"] {
        assert!(store_origin(bad).is_none(), "{bad}");
    }
    let local = Endpoints { store: store_origin("http://localhost:3214").unwrap(), mojang: Endpoints::production().mojang, access: None };
    assert_eq!(local.origin_key(), "localhost_3214");
    assert_eq!((local.host(), Endpoints::production().host()), ("localhost:3214".into(), "store.flintclient.com".into()));
    assert_eq!(Endpoints::production().origin_key(), "store.flintclient.com");
    assert_eq!(Endpoints::production().store_base(), PRODUCTION_STORE);
    assert!(asset_url(&local, "http://localhost:3214/test-capes/ember-cape.png").is_some(), "the Store itself");
    assert!(asset_url(&local, "https://p.supabase.co/storage/v1/object/public/cosmetic-assets/a.png").is_some());
    assert!(asset_url(&local, "http://elsewhere.example/a.png").is_none());
    assert!(asset_url(&Endpoints::production(), "http://localhost:3214/a.png").is_none());
}

#[test]
fn uuids_in_either_spelling_are_one_account() {
    assert_eq!(canonical("8667BA71-B85A-4004-AF54-457A9734EED7").as_deref(), Some(STEVE));
    assert_eq!(canonical(STEVE).as_deref(), Some(STEVE));
    assert_eq!(canonical("8667ba71b85a4004af54457a9734eed"), None);
    assert_eq!(canonical("../../8667ba71b85a4004af54457a9734eed7"), None);
    assert!(is_uuid(&dashed(STEVE)) && !is_uuid(STEVE));
}

#[test]
fn an_inventory_is_checked_before_it_is_shown() {
    let body = json!({
        "version": 4, "minecraft_uuid": dashed(STEVE), "name": "Steve\u{0007}", "balance": "250",
        "owned": [
            { "cosmetic_id": "00000000-0000-4000-8000-000000000001", "slug": "ok", "kind": "cape", "name": "Cape",
              "asset": { "id": "00000000-0000-4000-8000-0000000000a1", "version": 2, "url": "https://x/a.png", "sha256": "a".repeat(64) } },
            { "cosmetic_id": "00000000-0000-4000-8000-000000000002", "slug": "bad-hash", "kind": "cape", "name": "Cape",
              "asset": { "id": "00000000-0000-4000-8000-0000000000a2", "version": 1, "url": "https://x/b.png", "sha256": "not-a-hash" } },
            { "cosmetic_id": "nope", "slug": "bad-id", "kind": "cape", "name": "Cape" },
            { "cosmetic_id": "00000000-0000-4000-8000-000000000003", "slug": "boots", "kind": "boots", "name": "Boots" }
        ],
        "equipped": { "cape": "00000000-0000-4000-8000-000000000001", "boots": "00000000-0000-4000-8000-000000000003", "head": "nope" }
    });
    let inventory = parse_inventory(&body).unwrap();
    assert_eq!(inventory.name, "Steve");
    assert_eq!(inventory.balance, Some(250));
    assert_eq!(inventory.owned.iter().map(|o| o.slug.as_str()).collect::<Vec<_>>(), ["ok", "bad-hash"]);
    assert!(inventory.owned[0].asset.is_some() && inventory.owned[1].asset.is_none(), "an unusable texture is left out");
    assert_eq!(inventory.equipped.keys().collect::<Vec<_>>(), ["cape"]);
    let shown = serde_json::to_value(&inventory).unwrap();
    assert!(shown.get("minecraftUuid").is_none() && shown["owned"][0]["asset"].get("url").is_none() && shown["owned"][0]["asset"].get("sha256").is_none());
    assert!(parse_inventory(&json!({ "version": 1, "minecraft_uuid": "x", "name": "Steve" })).is_none());
    assert!(parse_inventory(&json!([])).is_none());
}

#[test]
fn the_game_handoff_names_the_store_and_carries_nothing_private() {
    let dir = TestDir::new();
    let state = AppState::for_tests(dir.0.clone());
    handoff::write(&state, "p1").unwrap();
    let written: Value = read_json(&state.paths.profile_dir("p1").join(".flint-store.json"));
    assert_eq!(
        written,
        json!({ "schema": 1, "contractVersion": 1, "storeBaseUrl": "https://store.flintclient.com", "publicCosmeticsUrl": "https://store.flintclient.com/api/v1/players/cosmetics/", "equipRevision": 0 })
    );
    handoff::equip_changed(&state);
    assert!(state.store.equip_revision.load(Ordering::SeqCst) > 0);
    // Not running: its file isn't rewritten.
    assert_eq!(read_json::<Value>(&state.paths.profile_dir("p1").join(".flint-store.json"))["equipRevision"], 0);
}

// ---------- against a real local Store ----------

fn e2e_uuid() -> String {
    let mut hex = random_hex(16).into_bytes();
    hex[12] = b'4';
    hex[16] = b'8';
    String::from_utf8(hex).unwrap()
}

/// The browser side of a test (the Store website): same-origin requests with the Store's own `Origin`.
async fn browser(state: &AppState, path: &str, token: Option<&str>, cookie: Option<&str>, body: Value) -> (u16, Value, Vec<String>) {
    let store = &state.store.endpoints.store;
    let mut request = state.http.post(store.join(path).unwrap()).header("Origin", state.store.endpoints.store_base()).json(&body);
    if let Some(token) = token {
        request = request.bearer_auth(token);
    }
    if let Some(cookie) = cookie {
        request = request.header("Cookie", cookie);
    }
    let response = request.send().await.expect("the local Store answers");
    let status = response.status().as_u16();
    let cookies = response.headers().get_all("set-cookie").iter().filter_map(|v| v.to_str().ok()).map(|c| c.split(';').next().unwrap_or("").to_owned()).collect();
    (status, response.json().await.unwrap_or(Value::Null), cookies)
}

/// A cosmetic's id from its Store page (the page hands it to the buy button).
fn cosmetic_id_in(html: &str, slug: &str) -> Option<String> {
    (0..html.len().saturating_sub(36)).find_map(|i| {
        let candidate = html.get(i..i + 36).filter(|c| is_uuid(c))?;
        let after = html.get(i + 36..(i + 100).min(html.len()))?;
        (after.contains("slug") && after.contains(slug)).then(|| candidate.to_owned())
    })
}

async fn simulate(state: &AppState, token: &str, order: &str, outcome: &str) {
    let (status, body, _) = browser(state, "/api/dev/simulate-payment/", Some(token), None, json!({ "orderId": order, "outcome": outcome })).await;
    assert!((200..300).contains(&status), "simulated {outcome}: {status} {body}");
}

/// Waits for the change feed to report a change since `since`, as the launcher's poll would.
async fn changed_since(state: &AppState, uuid: &str, since: i64) -> i64 {
    for _ in 0..40 {
        if let Changes::Ok { changed: true, version, .. } = changes(state, uuid, Some(since)).await {
            return version;
        }
        tokio::time::sleep(Duration::from_millis(250)).await;
    }
    panic!("no change reported since version {since}");
}

/// The launcher against a real local Store (`next dev`, PGlite test database, payment simulator on) with this test's fake
/// Mojang as its `MOJANG_SESSION_SERVER`. Opt-in; see docs/STORE-INTEGRATION.md for the exact setup:
/// `FLINT_E2E_STORE=http://localhost:3214 FLINT_E2E_MOJANG_PORT=3299 cargo test e2e_against_a_local_store -- --ignored`
#[tokio::test]
#[ignore = "needs a local Store dev server: docs/STORE-INTEGRATION.md"]
async fn e2e_against_a_local_store() {
    let store = std::env::var("FLINT_E2E_STORE").ok().and_then(|s| store_origin(&s)).expect("FLINT_E2E_STORE: the local Store's address");
    assert!(store.host_str().is_some_and(|h| h == "localhost" || h == "127.0.0.1"), "a local Store only");
    let port: u16 = std::env::var("FLINT_E2E_MOJANG_PORT").ok().and_then(|p| p.parse().ok()).expect("FLINT_E2E_MOJANG_PORT");
    let mojang = Fake::start_on(port).await;
    let dir = TestDir::new();
    let mut state = AppState::for_tests(dir.0.clone());
    let keychain = Arc::new(MemoryCredentials::default());
    state.credentials = Box::new(keychain.clone());
    state.store = StoreState::new(Endpoints { store: store.clone(), mojang: mojang.url(), access: None });
    let (steve, alex) = (e2e_uuid(), e2e_uuid());
    let (steve_name, alex_name) = (format!("E2eSteve{}", &steve[..4]), format!("E2eAlex{}", &alex[..4]));
    accounts(&state, &[(&steve, &steve_name), (&alex, &alex_name)]);
    mojang.with(|w| {
        w.add_mojang_account("e2e-mc-steve", &steve, &steve_name);
        w.add_mojang_account("e2e-mc-alex", &alex, &alex_name);
    });

    // Sign-in: challenge → Mojang join → verify, answered for Steve.
    let session = sign_in_with_token(&state, &steve, &steve_name, "e2e-mc-steve").await.expect("signed in to the local Store");
    assert_eq!(session.name, steve_name);
    let first = view(&state, &steve, false).await;
    assert!(first.signed_in && !first.stale, "{first:?}");
    let v0 = first.inventory.as_ref().unwrap().version;
    assert!(owned(&first).is_empty());

    // ETag: unchanged costs a 304.
    let (_, how) = fetch_inventory(&state, &steve, &session.token).await.unwrap();
    assert_eq!(how, Fetched::NotModified);
    assert!(matches!(changes(&state, &steve, Some(v0)).await, Changes::Ok { changed: false, poll_after_seconds: 25, .. }));

    // A purchase made on the Store website (simulated card payment) shows up through the change feed.
    let page = state.http.get(store.join("/cosmetics/test-frost-cape/").unwrap()).send().await.unwrap().text().await.unwrap();
    let frost = cosmetic_id_in(&page, "test-frost-cape").expect("the TEST Frost Cape's id on its Store page");
    let (status, checkout, _) = browser(&state, "/api/store/checkout/", Some(&session.token), None, json!({ "kind": "cosmetic", "itemId": frost, "idempotencyKey": format!("e2e-{}", random_hex(8)),
        // The website buyer confirms age and terms before a paid checkout (Store CHECKOUT_TERMS_VERSION; Backend migration 4000).
        "buyerAttestation": { "termsVersion": "terms-v2+refunds-v2", "age16": true } })).await;
    assert_eq!(status, 200, "checkout: {checkout}");
    let order = checkout["orderId"].as_str().unwrap().to_owned();
    simulate(&state, &session.token, &order, "completed").await;
    let v1 = changed_since(&state, &steve, v0).await;
    let bought = view(&state, &steve, false).await;
    assert_eq!(owned(&bought), ["test-frost-cape"], "{bought:?}");
    assert_eq!(bought.inventory.as_ref().unwrap().version, v1);

    // Its texture, checked against the Store's SHA-256 and cached for Steve.
    let texture = asset(&state, &steve, &frost).await.expect("the TEST Frost Cape texture verifies");
    assert!(texture.starts_with("data:image/png;base64,"));

    // Equip: the Store decides, and the public lookup the game uses agrees.
    let worn = equip(&state, &steve, "cape", Some(&frost)).await.unwrap();
    assert_eq!(worn.inventory.as_ref().unwrap().equipped.get("cape"), Some(&frost));
    assert!(state.store.equip_revision.load(Ordering::SeqCst) > 0);
    let public: Value = state.http.get(store.join(&format!("/api/v1/players/cosmetics/?uuids={}", dashed(&steve))).unwrap()).send().await.unwrap().json().await.unwrap();
    assert_eq!(public["players"][dashed(&steve)]["cape"]["cosmetic_id"], json!(frost), "{public}");

    // Alex on the same launcher: his own session and an empty inventory; Steve's cape can't be worn.
    sign_in_with_token(&state, &alex, &alex_name, "e2e-mc-alex").await.unwrap();
    let alex_view = view(&state, &alex, false).await;
    assert!(alex_view.signed_in && owned(&alex_view).is_empty());
    assert!(equip(&state, &alex, "cape", Some(&frost)).await.unwrap_err().contains("doesn't own"));

    // Forged identities: Alex's token for Steve's profile, and a launcher that joins as Alex but claims Steve.
    assert!(sign_in_with_token(&state, &steve, &steve_name, "e2e-mc-alex").await.unwrap_err().contains("Mojang didn't accept"));
    let (challenge_id, server_id) = challenge(&state).await.unwrap();
    mojang_join(&state, "e2e-mc-alex", &alex, &server_id).await.unwrap();
    let liar = call(&state, Method::POST, "/api/v1/auth/verify/", None, Some(json!({ "challengeId": challenge_id, "username": steve_name })), None).await;
    assert!(matches!(liar, Err(Failure::Refused { status: 403, ref code }) if code == "not_verified"), "{:?}", liar.err());

    // Steve's session in Alex's slot: the Store answers for Steve, and Flint refuses it for Alex.
    keychain.secrets.lock().unwrap().insert(credential_name(&state, &alex), serde_json::to_string(&session).unwrap());
    state.store = StoreState::new(state.store.endpoints.clone());
    let stolen = view(&state, &alex, false).await;
    assert!(!stolen.signed_in && stolen.problem.unwrap().contains("different Minecraft account"));
    assert!(!cache_path(&state, &alex).exists());

    // A browser sign-in approved from the launcher.
    let (status, link, cookies) = browser(&state, "/api/store/link/start/", None, None, json!({})).await;
    assert_eq!(status, 200, "{link}");
    let code = link["userCode"].as_str().unwrap().to_owned();
    approve_link(&state, &steve, &code.to_ascii_lowercase().replace('-', " ")).await.expect("approved");
    let link_cookie = cookies.iter().find(|c| c.starts_with("flint_link=")).expect("the browser's link cookie").clone();
    let (_, poll, _) = browser(&state, "/api/store/link/poll/", None, Some(&link_cookie), json!({})).await;
    assert_eq!(poll["status"], "approved", "{poll}");

    // Store Merit and orders come from the Store.
    assert!(wallet(&state, &steve).await.unwrap().balance.is_some());
    assert!(orders(&state, &steve).await.unwrap().iter().any(|o| o.id == order));

    // A refund removes the item at the next sync.
    simulate(&state, &session.token, &order, "refunded").await;
    changed_since(&state, &steve, worn.inventory.as_ref().unwrap().version).await;
    let refunded = view(&state, &steve, false).await;
    assert!(owned(&refunded).is_empty(), "{refunded:?}");
    assert!(asset(&state, &steve, &frost).await.is_err());

    // Signing out revokes the session at the Store.
    sign_out(&state, &steve).await;
    let reused = call(&state, Method::GET, "/api/v1/me/inventory/", Some(&session.token), None, None).await;
    assert_eq!(reused.err(), Some(Failure::SignedOut));
    assert!(files_containing(&dir.0, &session.token).is_empty());
}

/// A code shown by one Store, approved through a launcher connected to another, against two real local Stores (two
/// `next dev` servers, each with its own PGlite database, as the live Store and staging each have their own database). Opt-in:
/// `FLINT_E2E_STORE=http://localhost:3214 FLINT_E2E_OTHER_STORE=http://localhost:3215 FLINT_E2E_MOJANG_PORT=3299 cargo test --lib e2e_a_code_from_another_store -- --ignored`
#[tokio::test]
#[ignore = "needs two local Store dev servers: docs/STORE-INTEGRATION.md"]
async fn e2e_a_code_from_another_store_is_not_approved_there() {
    let local = |var: &str| {
        let store = std::env::var(var).ok().and_then(|s| store_origin(&s)).unwrap_or_else(|| panic!("{var}: a local Store's address"));
        assert!(store.host_str().is_some_and(|h| h == "localhost" || h == "127.0.0.1"), "a local Store only");
        store
    };
    let (first, second) = (local("FLINT_E2E_STORE"), local("FLINT_E2E_OTHER_STORE"));
    assert_ne!(first, second, "two different Stores");
    let port: u16 = std::env::var("FLINT_E2E_MOJANG_PORT").ok().and_then(|p| p.parse().ok()).expect("FLINT_E2E_MOJANG_PORT");
    let mojang = Fake::start_on(port).await;
    let steve = e2e_uuid();
    let name = format!("E2eSteve{}", &steve[..4]);
    mojang.with(|w| w.add_mojang_account("e2e-mc-steve", &steve, &name));
    let launcher = |store: Url| {
        let dir = TestDir::new();
        let mut state = AppState::for_tests(dir.0.clone());
        state.credentials = Box::new(Arc::new(MemoryCredentials::default()));
        state.store = StoreState::new(Endpoints { store, mojang: mojang.url(), access: None });
        accounts(&state, &[(&steve, &name)]);
        (dir, state)
    };
    let (_first_dir, live) = launcher(first);
    let (_second_dir, other) = launcher(second);
    sign_in_with_token(&live, &steve, &name, "e2e-mc-steve").await.expect("signed in to the first Store");

    // The other Store's website shows a code; the launcher connected to the first Store is asked to approve it.
    let (status, link, cookies) = browser(&other, "/api/store/link/start/", None, None, json!({})).await;
    assert_eq!(status, 200, "{link}");
    let code = link["userCode"].as_str().unwrap().to_owned();
    let refused = approve_link(&live, &steve, &code).await.unwrap_err();
    assert!(refused.starts_with(&format!("{} has no sign-in waiting", live.store.endpoints.host())), "{refused}");
    assert!(view(&live, &steve, false).await.signed_in, "a refused code keeps the session");
    let link_cookie = cookies.iter().find(|c| c.starts_with("flint_link=")).expect("the browser's link cookie").clone();
    let (_, poll, _) = browser(&other, "/api/store/link/poll/", None, Some(&link_cookie), json!({})).await;
    assert_eq!(poll["status"], "pending", "still waiting at the Store that showed it: {poll}");

    // A launcher connected to that Store approves it, and the browser is signed in; once used, the code is refused there too.
    sign_in_with_token(&other, &steve, &name, "e2e-mc-steve").await.expect("signed in to the other Store");
    approve_link(&other, &steve, &code).await.expect("approved where it was shown");
    let (_, poll, _) = browser(&other, "/api/store/link/poll/", None, Some(&link_cookie), json!({})).await;
    assert_eq!(poll["status"], "approved", "{poll}");
    assert!(approve_link(&other, &steve, &code).await.unwrap_err().contains("has no sign-in waiting"), "used once");
}

#[test]
fn access_token_only_for_development_builds_pointed_away_from_production() {
    let staging = Url::parse("https://flint-store-website-preview.example.workers.dev").unwrap();
    let production = Url::parse(PRODUCTION_STORE).unwrap();
    let both = || (Some("id.access".to_owned()), Some("s3cret".to_owned()));
    let (id, secret) = both();
    let token = access_token_for(true, &staging, id, secret).expect("a development build pointed at staging sends it");
    assert_eq!(token.client_id, "id.access");
    assert!(!format!("{token:?}").contains("s3cret"), "the secret never appears in debug output");
    let (id, secret) = both();
    assert!(access_token_for(false, &staging, id, secret).is_none(), "release builds never send one");
    let (id, secret) = both();
    assert!(access_token_for(true, &production, id, secret).is_none(), "never to the production Store");
    assert!(access_token_for(true, &staging, Some("id.access".to_owned()), None).is_none(), "both values are needed");
    assert!(access_token_for(true, &staging, Some(" ".to_owned()), Some("s3cret".to_owned())).is_none());
    assert!(Endpoints::production().access.is_none());
}

// ---------- the Store in the game (game.rs) ----------

#[tokio::test]
async fn a_game_lists_wears_and_pictures_the_accounts_items_through_flint() {
    let rig = rig().await;
    assert_eq!(game::cosmetics(&rig.state, STEVE).await.unwrap_err(), game::SIGN_IN_IN_APP, "no Store session: sign in in the app");
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);
    rig.state.running.lock().unwrap().insert("p1".into(), RunningGame { kill: None });

    let shown = game::cosmetics(&rig.state, STEVE).await.unwrap();
    assert_eq!(shown.items.len(), 1);
    assert_eq!((shown.items[0].id.as_str(), shown.items[0].kind.as_str(), shown.items[0].art), (rig.ember.as_str(), "cape", Some(1)));
    assert!(shown.equipped.is_empty() && !shown.stale);
    let json = serde_json::to_value(&shown).unwrap().to_string();
    assert!(!json.contains("sha256") && !json.contains("/assets/") && !json.contains("token"), "no texture address, hash or token: {json}");

    let worn = game::wear(&rig.state, STEVE, "cape", Some(&rig.ember)).await.unwrap();
    assert_eq!(worn.equipped.get("cape"), Some(&rig.ember));
    let handoff: Value = read_json(&rig.state.paths.profile_dir("p1").join(handoff::FILE));
    assert!(handoff["equipRevision"].as_u64().unwrap() > 0, "running games hear of it: {handoff}");
    assert!(game::wear(&rig.state, STEVE, "cape", Some(&rig.frost)).await.unwrap_err().contains("doesn't own"));
    assert!(game::wear(&rig.state, STEVE, "cape", None).await.unwrap().equipped.is_empty());

    let png = game::art(&rig.state, STEVE, &rig.ember).await.unwrap();
    assert_eq!(STANDARD.decode(png).unwrap(), EMBER);
    assert!(game::art(&rig.state, STEVE, &rig.frost).await.is_err(), "only what the account owns");

    assert_eq!(game::game_account(&rig.state, &dashed(STEVE)).unwrap(), STEVE);
    assert!(game::game_account(&rig.state, "0123456789abcdef0123456789abcdef").is_err());
}

// ---------- animated capes ----------

/// A PNG's signature and IHDR (what Flint reads of a strip before the interface decodes it), then `tag` so strips differ.
pub(super) fn png(width: u32, height: u32, tag: &str) -> Vec<u8> {
    let mut bytes = PNG_SIGNATURE.to_vec();
    bytes.extend_from_slice(&13u32.to_be_bytes());
    bytes.extend_from_slice(b"IHDR");
    bytes.extend_from_slice(&width.to_be_bytes());
    bytes.extend_from_slice(&height.to_be_bytes());
    bytes.extend_from_slice(&[8, 6, 0, 0, 0, 0, 0, 0, 0]);
    bytes.extend_from_slice(tag.as_bytes());
    bytes
}

/// The Store's manifest for a cape of `frames` frames at 16 px per unit (352×272 frames, 7 to a strip).
pub(super) fn manifest(frames: usize) -> Value {
    let durations: Vec<u32> = (0..frames).map(|f| 40 + 10 * f as u32).collect();
    let strips: Vec<Value> = (0..frames.div_ceil(7))
        .map(|s| json!({ "path": "animated/x.png", "url": format!("https://cdn.example/{s}.png"), "sha256": format!("{s:0>64}"), "bytes": 1000, "frames": 7.min(frames - s * 7) }))
        .collect();
    json!({ "v": 1, "layout": "cape-atlas-22x17", "width": 352, "height": 272, "scale": 16, "frame_count": frames,
            "durations_ms": durations, "duration_ms": durations.iter().sum::<u32>(), "frames_per_strip": 7, "strips": strips })
}

type Mistake = (&'static str, Box<dyn Fn(&mut Value)>);

#[test]
fn an_animated_capes_manifest_is_read_as_strictly_as_the_game_reads_it() {
    let good = anim::parse(&manifest(10)).expect("the Store's own shape");
    assert_eq!((good.scale, good.width, good.height, good.frame_count, good.frames_per_strip), (16, 352, 272, 10, 7));
    assert_eq!(good.duration_ms, good.durations_ms.iter().sum::<u32>());
    assert_eq!(good.strips.iter().map(|s| s.frames).collect::<Vec<_>>(), [7, 3]);
    // Whole numbers written as JavaScript may write them still count; HD's 704×544 frames go 3 to a strip.
    let mut whole = manifest(10);
    whole["scale"] = json!(16.0);
    assert!(anim::parse(&whole).is_some());
    assert_eq!(anim::frames_per_strip(544), 3);
    assert_eq!(anim::frames_per_strip(68), 30);

    // Each kind of mistake drops the animation (the cape keeps its poster).
    let broken: Vec<Mistake> = vec![
        ("another version", Box::new(|m| m["v"] = json!(2))),
        ("another layout", Box::new(|m| m["layout"] = json!("cape-64x32"))),
        ("a scale the Store doesn't make", Box::new(|m| m["scale"] = json!(12))),
        ("a frame size that isn't the scale's", Box::new(|m| m["width"] = json!(351))),
        ("one frame", Box::new(|m| m["frame_count"] = json!(1))),
        ("more frames than durations", Box::new(|m| m["frame_count"] = json!(11))),
        ("a frame under 10 ms", Box::new(|m| m["durations_ms"][0] = json!(5))),
        ("a fractional duration", Box::new(|m| m["durations_ms"][0] = json!(40.5))),
        ("a loop that isn't the sum", Box::new(|m| m["duration_ms"] = json!(1))),
        ("the wrong frames per strip", Box::new(|m| m["frames_per_strip"] = json!(6))),
        ("a strip missing", Box::new(|m| m["strips"].as_array_mut().unwrap().truncate(1))),
        ("a strip with the wrong frame count", Box::new(|m| m["strips"][1]["frames"] = json!(4))),
        ("a strip without a hash", Box::new(|m| m["strips"][0]["sha256"] = json!("nope"))),
        ("a strip without an address", Box::new(|m| m["strips"][0]["url"] = Value::Null)),
        ("a strip over 8 MiB", Box::new(|m| m["strips"][0]["bytes"] = json!(9 * 1024 * 1024))),
        ("text for a number", Box::new(|m| m["scale"] = json!("16"))),
    ];
    for (why, change) in broken {
        let mut m = manifest(10);
        change(&mut m);
        assert!(anim::parse(&m).is_none(), "{why}");
    }
    let mut long = manifest(120);
    long["durations_ms"] = json!(vec![600; 120]);
    long["duration_ms"] = json!(72_000);
    assert!(anim::parse(&long).is_none(), "a loop over 60 s");
    let mut heavy = manifest(120);
    for s in heavy["strips"].as_array_mut().unwrap() {
        s["bytes"] = json!(2 * 1024 * 1024);
    }
    assert!(anim::parse(&heavy).is_none(), "strips over 32 MiB together");
    assert!(anim::parse(&Value::Null).is_none() && anim::parse(&json!([])).is_none());

    // In an inventory: a bad manifest never refuses the inventory or the poster; the interface gets the timing, never an
    // address or a hash.
    let mut bad = manifest(10);
    bad["v"] = json!(9);
    let body = json!({
        "version": 1, "minecraft_uuid": dashed(STEVE), "name": "Steve",
        "owned": [
            { "cosmetic_id": "00000000-0000-4000-8000-000000000001", "slug": "moving", "kind": "cape", "name": "Cape",
              "asset": { "id": "00000000-0000-4000-8000-0000000000a1", "version": 2, "url": "https://x/a.png", "sha256": "a".repeat(64), "animation": manifest(10) } },
            { "cosmetic_id": "00000000-0000-4000-8000-000000000002", "slug": "bad-manifest", "kind": "cape", "name": "Cape",
              "asset": { "id": "00000000-0000-4000-8000-0000000000a2", "version": 1, "url": "https://x/b.png", "sha256": "b".repeat(64), "animation": bad } },
            { "cosmetic_id": "00000000-0000-4000-8000-000000000003", "slug": "static", "kind": "cape", "name": "Cape",
              "asset": { "id": "00000000-0000-4000-8000-0000000000a3", "version": 1, "url": "https://x/c.png", "sha256": "c".repeat(64), "animation": null } }
        ],
        "equipped": {}
    });
    let inventory = parse_inventory(&body).unwrap();
    let assets: Vec<_> = inventory.owned.iter().map(|o| o.asset.as_ref().unwrap()).collect();
    assert!(assets[0].animation.is_some() && assets[1].animation.is_none() && assets[2].animation.is_none());
    let shown = serde_json::to_value(&inventory).unwrap();
    let animation = &shown["owned"][0]["asset"]["animation"];
    assert_eq!(animation["frameCount"], 10);
    assert_eq!(animation["framesPerStrip"], 7);
    assert_eq!(animation["durationsMs"].as_array().unwrap().len(), 10);
    assert_eq!(animation["strips"], json!([{ "frames": 7 }, { "frames": 3 }]));
    let text = shown.to_string();
    assert!(!text.contains("cdn.example") && !text.contains(&"0".repeat(64)), "no strip address or hash: {text}");
    assert!(shown["owned"][1]["asset"]["animation"].is_null() && shown["owned"][2]["asset"]["animation"].is_null());
}

#[tokio::test]
async fn an_animated_capes_strips_are_served_only_when_they_match_the_stores_hash_and_size() {
    let rig = rig().await;
    // 8 frames at 32 px per unit (HD, 704×544): 3, 3 and 2 frames to a strip.
    let strips = vec![(png(704, 3 * 544, "s0"), 3), (png(704, 3 * 544, "s1"), 3), (png(704, 2 * 544, "s2"), 2)];
    let durations = [60, 120, 90, 150, 45, 260, 80, 100];
    let (cape, wrong_size) = rig.fake.with(|w| {
        let cape = w.add_animated_cape("hd-cape", EMBER, 32, &durations, &strips);
        // Listed as 2 frames tall, served 3 frames tall: the hash matches, the size doesn't.
        let wrong_size = w.add_animated_cape("odd-cape", FROST, 32, &durations[..2], &[(png(704, 3 * 544, "odd"), 2)]);
        (cape, wrong_size)
    });
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &cape);
    rig.grant(STEVE, &wrong_size);
    let view = rig.view(STEVE).await;
    let item = view.inventory.as_ref().unwrap().owned.iter().find(|o| o.cosmetic_id == cape).unwrap();
    let animation = item.asset.as_ref().unwrap().animation.as_ref().expect("the manifest is kept");
    assert_eq!((animation.scale, animation.frame_count, animation.frames_per_strip), (32, 8, 3));

    for (i, (bytes, _)) in strips.iter().enumerate() {
        assert_eq!(&anim::strip(&rig.state, STEVE, &cape, i).await.unwrap(), bytes);
    }
    // Cached by SHA-256 for this account, and served from there.
    let strips_dir = account_dir(&rig.state, STEVE).join("assets").join("strips");
    let sha = hex::encode(Sha256::digest(&strips[0].0));
    let file = strips_dir.join(format!("{sha}.png"));
    assert_eq!(std::fs::read(&file).unwrap(), strips[0].0);
    anim::strip(&rig.state, STEVE, &cape, 0).await.unwrap();
    assert_eq!(rig.fake.with(|w| w.count(&format!("GET /assets/animated/{sha}.png 200"))), 1);
    // A cached copy that changed on disk is fetched again.
    std::fs::write(&file, png(704, 3 * 544, "edited")).unwrap();
    assert_eq!(anim::strip(&rig.state, STEVE, &cape, 0).await.unwrap(), strips[0].0);
    assert_eq!(rig.fake.with(|w| w.count(&format!("GET /assets/animated/{sha}.png 200"))), 2);

    // Bytes that aren't the ones the Store hashed are refused and never kept.
    let sha2 = hex::encode(Sha256::digest(&strips[2].0));
    std::fs::remove_file(strips_dir.join(format!("{sha2}.png"))).unwrap();
    rig.fake.with(|w| w.files.insert(format!("/assets/animated/{sha2}.png"), png(704, 2 * 544, "zz")));
    assert_eq!(anim::strip(&rig.state, STEVE, &cape, 2).await.unwrap_err(), HASH_MISMATCH);
    // Nor is more than the Store listed read at all.
    rig.fake.with(|w| w.files.insert(format!("/assets/animated/{sha2}.png"), png(704, 2 * 544, "longer")));
    assert!(anim::strip(&rig.state, STEVE, &cape, 2).await.unwrap_err().contains("completely"));
    assert!(!strips_dir.join(format!("{sha2}.png")).exists());
    // A strip that isn't the size the manifest lists is refused too.
    assert!(anim::strip(&rig.state, STEVE, &wrong_size, 0).await.unwrap_err().contains("size"));

    // Only this account's items, only strips the manifest lists, only animated items.
    assert!(anim::strip(&rig.state, STEVE, &cape, 3).await.is_err());
    assert!(anim::strip(&rig.state, ALEX, &cape, 0).await.is_err());
    rig.grant(STEVE, &rig.ember);
    rig.view(STEVE).await;
    assert!(anim::strip(&rig.state, STEVE, &rig.ember, 0).await.is_err());

    // Signing out forgets the strips with the rest of the account's Store copy.
    sign_out(&rig.state, STEVE).await;
    assert!(!file.exists());
    assert!(anim::strip(&rig.state, STEVE, &cape, 0).await.is_err());
}
