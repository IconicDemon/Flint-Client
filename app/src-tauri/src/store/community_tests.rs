//! Discord connections and community rewards (community.rs) against the fake Store (fake.rs), and the checks on every
//! address Flint would open or show. The fake starts as today's production Store: none of these routes exist.
use super::community::*;
use super::fake::{dashed, FakeDiscord};
use super::tests::{owned, rig, Rig, ALEX, EMBER, STEVE};
use super::*;
use std::sync::atomic::Ordering;

const MEMBER_ART: &[u8] = b"\x89PNG\r\n\x1a\nmember-cape-pixels";
const BOOSTER_ART: &[u8] = b"\x89PNG\r\n\x1a\nbooster-cape-pixels";
const FIRST_ART: &[u8] = b"\x89PNG\r\n\x1a\nfirst-launch-pixels";

/// A Store with the feature on, a Discord member cape and a Discord booster cape listed.
async fn community_rig() -> (Rig, String, String) {
    let rig = rig().await;
    let (member, booster) = rig.fake.with(|w| {
        w.enable_community();
        (w.add_reward("discord-member", "discord_member", MEMBER_ART), w.add_reward("discord-booster", "discord_booster", BOOSTER_ART))
    });
    (rig, member, booster)
}

/// Fictional Discord accounts (ids in no real range Discord has used for people).
fn fan() -> FakeDiscord {
    FakeDiscord {
        global_name: Some("FlintFan".into()),
        avatar_url: Some("https://cdn.discordapp.com/avatars/1300000000000000101/0123abcd.png?size=128".into()),
        member: true,
        ..FakeDiscord::new("1300000000000000101", "flintfan")
    }
}

fn newface() -> FakeDiscord {
    FakeDiscord::new("1300000000000000103", "newface")
}

fn link(rig: &Rig, uuid: &str, who: FakeDiscord) {
    rig.fake.with(|w| {
        w.discord.insert(dashed(uuid), who);
        w.settle(uuid);
    });
}

fn change(rig: &Rig, uuid: &str, f: impl FnOnce(&mut FakeDiscord)) {
    rig.fake.with(|w| {
        f(w.discord.get_mut(&dashed(uuid)).unwrap());
        w.settle(uuid);
    });
}

fn status_of(view: &RewardsView, id: &str) -> (String, String, Option<String>) {
    let r = view.rewards.iter().find(|r| r.cosmetic_id == id).expect("listed");
    (r.status.clone(), r.action.kind.clone(), r.action.url.clone())
}

fn item<'a>(view: &'a StoreView, id: &str) -> Option<&'a Owned> {
    view.inventory.as_ref()?.owned.iter().find(|o| o.cosmetic_id == id)
}

fn requests(rig: &Rig) -> usize {
    rig.fake.with(|w| w.log.len())
}

// ---------- a Store without the feature ----------

#[tokio::test]
async fn todays_production_store_has_no_routes_so_the_feature_is_simply_off() {
    let rig = rig().await;
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);

    let c = connections(&rig.state, STEVE).await;
    assert_eq!((c.state, c.can_link, c.discord.is_none(), c.problem.is_none()), (Avail::Off, false, true, true), "{c:?}");
    let r = rewards(&rig.state, STEVE).await;
    assert_eq!((r.state, r.rewards.len(), r.problem.is_none()), (Avail::Off, 0, true));
    let catalog_now = catalog(&rig.state).await;
    assert_eq!((catalog_now.state, catalog_now.enabled, catalog_now.rewards.len()), (Avail::Off, false, 0));
    assert_eq!(start_link(&rig.state, STEVE).await.unwrap(), (LinkStart::Off, None));
    assert_eq!(recheck(&rig.state, STEVE).await.unwrap(), Recheck::Off);
    assert_eq!(unlink(&rig.state, STEVE).await.unwrap().state, Avail::Off);
    assert!(rig.fake.with(|w| w.count("GET /api/v1/me/connections/ 404")) >= 1);
    assert_eq!(rig.fake.with(|w| w.count("GET /api/v1/catalog/community/ 404")), 1);

    // The 404 is remembered for a minute like any catalog answer: the page asking again costs nothing.
    let before = requests(&rig);
    catalog(&rig.state).await;
    assert_eq!(requests(&rig), before);

    // The Store page and the session are untouched; the inventory without the new fields still reads.
    let view = rig.view(STEVE).await;
    assert!(view.signed_in && !view.stale && view.problem.is_none());
    let ember = item(&view, &rig.ember).unwrap();
    assert_eq!((ember.obtained_via.as_deref(), ember.permanence.as_deref(), ember.state.as_deref()), (None, None, None));
}

#[tokio::test]
async fn discord_unconfigured_or_switched_off_hides_connect_but_keeps_a_link_visible_to_unlink() {
    let (rig, _, _) = community_rig().await;
    rig.sign_in(STEVE).await;
    rig.fake.with(|w| w.discord_configured = false);
    let c = connections(&rig.state, STEVE).await;
    assert_eq!((c.state, c.can_link, c.discord.is_none()), (Avail::Ok, false, true));
    assert_eq!(start_link(&rig.state, STEVE).await.unwrap(), (LinkStart::Off, None), "503 discord_unconfigured is off");
    assert_eq!(rig.fake.with(|w| w.count("POST /api/v1/me/connections/discord/start/ 503")), 1);

    // A link made before stays visible so it can be removed.
    link(&rig, STEVE, fan());
    let c = connections(&rig.state, STEVE).await;
    assert!(!c.can_link && c.discord.as_ref().is_some_and(|d| d.username == "flintfan"));

    // Linking switched off in Admin: 403 discord_disabled is off too.
    rig.fake.with(|w| {
        w.discord_configured = true;
        w.link_enabled = false;
    });
    assert!(!connections(&rig.state, STEVE).await.can_link);
    assert_eq!(start_link(&rig.state, STEVE).await.unwrap(), (LinkStart::Off, None));
    let c = unlink(&rig.state, STEVE).await.unwrap();
    assert!(c.discord.is_none(), "unlinking still works with linking off");
}

// ---------- addresses ----------

const GOOD_AUTHORIZE: &str = "https://discord.com/oauth2/authorize?client_id=1300000000000000001&redirect_uri=https%3A%2F%2Fstore.flintclient.com%2Fconnect%2Fdiscord%2Fcallback%2F&response_type=code&scope=identify&state=Zq3vY0b8xQm1Lr7tWc5nHk2pJd9sFg4a_-Ae&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&prompt=consent";

/// The Store this launcher uses, for the authorize page's callback.
fn live() -> Url {
    Endpoints::production().store
}

#[test]
fn only_discords_own_authorize_page_with_identify_and_pkce_is_opened() {
    let store = live();
    let authorize_url = |raw: &str| authorize_url(raw, &store);
    let authorize_url_release = |raw: &str| authorize_url_release(raw, &store);
    assert!(authorize_url_release(GOOD_AUTHORIZE).is_some());
    assert!(authorize_url(GOOD_AUTHORIZE).is_some());
    assert!(authorize_url_release(&GOOD_AUTHORIZE.replace("/oauth2/", "/api/oauth2/")).is_some(), "Discord's API path too");
    assert!(authorize_url_release(&GOOD_AUTHORIZE.replace("https://discord.com", "https://DISCORD.com")).is_some(), "host case doesn't matter");
    // A staging or local Store's own callback is its own host.
    let staging = Url::parse("https://store-staging.example.workers.dev").unwrap();
    let staged = GOOD_AUTHORIZE.replace("store.flintclient.com", "store-staging.example.workers.dev");
    assert!(super::community::authorize_url_release(&staged, &staging).is_some());
    assert!(authorize_url_release(&staged).is_none(), "but not for a launcher using the live Store");

    let hostile: Vec<(&str, String)> = vec![
        ("plain http", GOOD_AUTHORIZE.replace("https://", "http://")),
        ("a look-alike host", GOOD_AUTHORIZE.replace("discord.com", "discord.com.evil.example")),
        ("another host", GOOD_AUTHORIZE.replace("discord.com", "evil.example")),
        ("discordapp.com", GOOD_AUTHORIZE.replace("discord.com", "discordapp.com")),
        ("a subdomain", GOOD_AUTHORIZE.replace("discord.com", "ptb.discord.com")),
        ("a port", GOOD_AUTHORIZE.replace("discord.com", "discord.com:8443")),
        ("credentials", GOOD_AUTHORIZE.replace("https://", "https://user:pw@")),
        ("discord.com as a user name", GOOD_AUTHORIZE.replace("https://discord.com", "https://discord.com@evil.example")),
        ("another path", GOOD_AUTHORIZE.replace("/oauth2/authorize", "/oauth2/token")),
        ("a longer path", GOOD_AUTHORIZE.replace("/oauth2/authorize", "/oauth2/authorize/x")),
        ("more scopes", GOOD_AUTHORIZE.replace("scope=identify", "scope=identify%20guilds")),
        ("more scopes with +", GOOD_AUTHORIZE.replace("scope=identify", "scope=identify+email")),
        ("another scope", GOOD_AUTHORIZE.replace("scope=identify", "scope=guilds")),
        ("the bot scope", GOOD_AUTHORIZE.replace("scope=identify", "scope=bot")),
        ("scope twice", format!("{GOOD_AUTHORIZE}&scope=guilds")),
        ("state twice", format!("{GOOD_AUTHORIZE}&state=Zq3vY0b8xQm1Lr7tWc5nHk2pJd9sFg4a")),
        ("no scope", GOOD_AUTHORIZE.replace("&scope=identify", "")),
        ("the token flow", GOOD_AUTHORIZE.replace("response_type=code", "response_type=token")),
        ("plain PKCE", GOOD_AUTHORIZE.replace("code_challenge_method=S256", "code_challenge_method=plain")),
        ("no PKCE method", GOOD_AUTHORIZE.replace("&code_challenge_method=S256", "")),
        ("no PKCE challenge", GOOD_AUTHORIZE.replace("&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", "")),
        ("no state", GOOD_AUTHORIZE.replace("&state=Zq3vY0b8xQm1Lr7tWc5nHk2pJd9sFg4a_-Ae", "")),
        ("a short state", GOOD_AUTHORIZE.replace("state=Zq3vY0b8xQm1Lr7tWc5nHk2pJd9sFg4a_-Ae", "state=abc")),
        ("no client", GOOD_AUTHORIZE.replace("client_id=1300000000000000001&", "")),
        ("a callback elsewhere", GOOD_AUTHORIZE.replace("store.flintclient.com%2Fconnect", "evil.example%2Fconnect")),
        ("a callback over http", GOOD_AUTHORIZE.replace("https%3A%2F%2Fstore", "http%3A%2F%2Fstore")),
        ("another callback path", GOOD_AUTHORIZE.replace("%2Fconnect%2Fdiscord%2Fcallback%2F", "%2Fsteal")),
        ("a callback on a look-alike", GOOD_AUTHORIZE.replace("store.flintclient.com%2F", "store.flintclient.com.evil.example%2F")),
        ("a callback with a port", GOOD_AUTHORIZE.replace("store.flintclient.com%2F", "store.flintclient.com%3A8443%2F")),
        ("no callback", GOOD_AUTHORIZE.replace("redirect_uri=https%3A%2F%2Fstore.flintclient.com%2Fconnect%2Fdiscord%2Fcallback%2F&", "")),
        ("a fragment", format!("{GOOD_AUTHORIZE}#x")),
        ("javascript", "javascript:alert(1)".into()),
        ("data", "data:text/html,<script>alert(1)</script>".into()),
        ("a file", "file:///C:/Windows/System32/calc.exe".into()),
        ("nothing", String::new()),
        ("too long", format!("{GOOD_AUTHORIZE}&prompt2={}", "a".repeat(5000))),
    ];
    for (why, url) in &hostile {
        assert!(authorize_url(url).is_none(), "accepted {why}: {url}");
        assert!(authorize_url_release(url).is_none(), "release accepted {why}: {url}");
    }

    // The Store's local demo Discord: development builds only, http on this computer only, the same query checks.
    let demo = GOOD_AUTHORIZE
        .replace("https://discord.com/oauth2/authorize", "http://127.0.0.1:54321/discord/oauth2/authorize")
        .replace("https%3A%2F%2Fstore.flintclient.com", "http%3A%2F%2Flocalhost%3A3214");
    assert!(authorize_url_release(&demo).is_none(), "release builds refuse a loopback authorize page");
    assert_eq!(authorize_url(&demo).is_some(), cfg!(debug_assertions), "development builds accept the local demo");
    for bad in [
        demo.replace("127.0.0.1", "192.168.1.20"),
        demo.replace("127.0.0.1", "localhost.evil.example"),
        demo.replace("http://127.0.0.1:54321", "https://127.0.0.1:54321"),
        demo.replace("scope=identify", "scope=identify%20guilds"),
        demo.replace("code_challenge_method=S256", "code_challenge_method=plain"),
        demo.replace("/discord/oauth2/authorize", "/discord/oauth2/token"),
    ] {
        assert!(authorize_url(&bad).is_none(), "{bad}");
    }
}

#[test]
fn only_discord_invites_and_discord_cdn_avatars_are_used() {
    for good in ["https://discord.gg/flint", "https://discord.com/invite/flint", "https://www.discord.com/invite/Flint-2", "https://discord.gg/flint/"] {
        assert!(invite_url(good).is_some(), "{good}");
    }
    for bad in [
        "http://discord.gg/flint",
        "https://discord.gg/",
        "https://discord.gg",
        "https://discord.gg/flint/extra",
        "https://discord.gg/flint?event=1",
        "https://discord.gg/flint#x",
        "https://discord.gg:8443/flint",
        "https://user@discord.gg/flint",
        "https://discord.com/flint",
        "https://discord.com/invite/",
        "https://discord.com/oauth2/authorize?scope=bot",
        "https://discord.com.evil.example/invite/flint",
        "https://evil.example/invite/flint",
        "https://discordapp.com/invite/flint",
        "https://ptb.discord.com/invite/flint",
        "https://discord.gg/fl%20int",
        "javascript:alert(1)",
        "",
    ] {
        assert!(invite_url(bad).is_none(), "{bad}");
    }
    assert!(avatar_url("https://cdn.discordapp.com/avatars/1300000000000000101/0123abcd.png?size=128").is_some());
    assert!(avatar_url("https://cdn.discordapp.com/embed/avatars/0.png").is_some());
    for bad in [
        "http://cdn.discordapp.com/avatars/1/a.png",
        "https://cdn.discordapp.com.evil.example/avatars/1/a.png",
        "https://media.discordapp.net/avatars/1/a.png",
        "https://evil.example/cdn.discordapp.com/a.png",
        "https://user@cdn.discordapp.com/a.png",
        "https://cdn.discordapp.com:444/a.png",
        "data:image/png;base64,iVBORw0KGgo=",
        "javascript:alert(1)",
    ] {
        assert!(avatar_url(bad).is_none(), "{bad}");
    }
}

#[tokio::test]
async fn a_store_sending_another_authorize_page_is_refused_and_nothing_is_kept() {
    let (rig, _, _) = community_rig().await;
    rig.sign_in(STEVE).await;
    rig.fake.with(|w| w.authorize_override = Some(GOOD_AUTHORIZE.replace("scope=identify", "scope=identify%20guilds%20email")));
    let refused = start_link(&rig.state, STEVE).await.unwrap_err();
    assert!(refused.contains("doesn't trust"), "{refused}");
    let attempt_id = rig.fake.with(|w| w.attempts.keys().next().cloned()).expect("the Store made one");
    assert!(attempt(&rig.state, STEVE, &attempt_id).await.is_err(), "not kept, never polled");
    assert!(reopen_url(&rig.state, STEVE, &attempt_id).is_err());

    // Hostile avatar and invite addresses are dropped, never shown.
    rig.fake.with(|w| {
        w.invite_url = "https://evil.example/invite/flint".into();
        w.discord.insert(dashed(STEVE), FakeDiscord { avatar_url: Some("https://evil.example/track.png".into()), ..newface() });
    });
    let c = connections(&rig.state, STEVE).await;
    assert_eq!(c.discord.as_ref().unwrap().avatar_url, None);
    let r = rewards(&rig.state, STEVE).await;
    assert!(r.rewards.iter().all(|x| x.action.kind == "join_discord" && x.action.url.is_none()), "{r:?}");
}

// ---------- linking ----------

#[tokio::test]
async fn linking_through_discords_page_unlocks_the_member_cape_and_it_can_be_worn() {
    let (rig, member, booster) = community_rig().await;
    rig.sign_in(STEVE).await;
    rig.fake.with(|w| w.link_as = Some(fan()));
    let before = rewards(&rig.state, STEVE).await;
    assert_eq!(status_of(&before, &member), ("locked".into(), "connect_discord".into(), None));
    assert!(equip(&rig.state, STEVE, "cape", Some(&member)).await.is_err(), "not earned: the Store refuses it");

    let (start, url) = start_link(&rig.state, STEVE).await.unwrap();
    let url = url.unwrap();
    assert_eq!((url.scheme(), url.host_str()), ("https", Some("discord.com")));
    let LinkStart::Started { attempt_id, .. } = start else { panic!("{start:?}") };
    assert_eq!(reopen_url(&rig.state, STEVE, &attempt_id).unwrap(), url, "Open Discord again opens the same page");
    let mut seen = Vec::new();
    for _ in 0..3 {
        seen.push(attempt(&rig.state, STEVE, &attempt_id).await.unwrap().state);
    }
    assert_eq!(seen, ["waiting", "waiting", "linked"]);
    assert!(attempt(&rig.state, STEVE, &attempt_id).await.is_err(), "a finished attempt is forgotten: polling stops");

    let c = connections(&rig.state, STEVE).await;
    let d = c.discord.as_ref().expect("linked");
    assert_eq!((d.username.as_str(), d.display_name.as_deref(), d.guild.state.as_str(), d.guild.member), ("flintfan", Some("FlintFan"), "verified", Some(true)));
    assert!(d.avatar_url.as_deref().is_some_and(|a| a.starts_with("https://cdn.discordapp.com/")));
    let json = serde_json::to_value(&c).unwrap();
    assert!(json["discord"].get("id").is_none() && json["discord"].get("user").is_none(), "the Discord id isn't handed over (only inside Discord's own avatar address): {json}");

    let after = rewards(&rig.state, STEVE).await;
    assert_eq!(status_of(&after, &member), ("eligible".into(), "none".into(), None));
    assert_eq!(status_of(&after, &booster), ("locked".into(), "boost_discord".into(), Some("https://discord.gg/flint".into())));
    let view = rig.view(STEVE).await;
    let cape = item(&view, &member).expect("in the inventory while it counts");
    assert_eq!((cape.obtained_via.as_deref(), cape.permanence.as_deref(), cape.state.as_deref()), (Some("discord_member"), Some("conditional"), Some("eligible")));
    let worn = equip(&rig.state, STEVE, "cape", Some(&member)).await.unwrap();
    assert_eq!(worn.inventory.unwrap().equipped.get("cape"), Some(&member));
}

#[tokio::test]
async fn every_way_an_attempt_ends_is_reported_and_polling_stops() {
    let (rig, _, _) = community_rig().await;
    rig.sign_in(STEVE).await;
    for (said, shown) in [("denied", "denied"), ("cancelled", "cancelled"), ("expired", "expired"), ("error", "error"), ("something-new", "error")] {
        rig.fake.with(|w| {
            w.attempt_script = vec!["waiting".into(), said.into()];
            w.starts.clear();
        });
        let LinkStart::Started { attempt_id, .. } = start_link(&rig.state, STEVE).await.unwrap().0 else { panic!() };
        assert_eq!(attempt(&rig.state, STEVE, &attempt_id).await.unwrap().state, "waiting");
        assert_eq!(attempt(&rig.state, STEVE, &attempt_id).await.unwrap().state, shown, "{said}");
        let before = requests(&rig);
        assert!(attempt(&rig.state, STEVE, &attempt_id).await.is_err());
        assert_eq!(requests(&rig), before, "not asked again");
    }

    // An attempt the Store no longer has (purged) has expired.
    rig.fake.with(|w| w.attempt_script = vec!["waiting".into()]);
    let LinkStart::Started { attempt_id, .. } = start_link(&rig.state, STEVE).await.unwrap().0 else { panic!() };
    rig.fake.with(|w| w.attempts.clear());
    assert_eq!(attempt(&rig.state, STEVE, &attempt_id).await.unwrap().state, "expired");
}

#[tokio::test]
async fn a_discord_account_linked_to_another_minecraft_account_is_never_moved() {
    let (rig, member, _) = community_rig().await;
    rig.sign_in(STEVE).await;
    rig.sign_in(ALEX).await;
    link(&rig, STEVE, fan());
    // Alex approves Flint on Discord with Steve's Discord account.
    rig.fake.with(|w| w.link_as = Some(fan()));
    let LinkStart::Started { attempt_id, .. } = start_link(&rig.state, ALEX).await.unwrap().0 else { panic!() };
    let mut last = String::new();
    for _ in 0..3 {
        last = attempt(&rig.state, ALEX, &attempt_id).await.unwrap().state;
    }
    assert_eq!(last, "already_linked_elsewhere");
    assert!(connections(&rig.state, ALEX).await.discord.is_none());
    assert_eq!(connections(&rig.state, STEVE).await.discord.unwrap().username, "flintfan", "Steve keeps it");
    assert!(item(&rig.view(ALEX).await, &member).is_none());

    // Recovering: Alex links his own Discord account instead.
    rig.fake.with(|w| w.link_as = Some(FakeDiscord { member: true, ..FakeDiscord::new("1300000000000000104", "alexplays") }));
    let LinkStart::Started { attempt_id, .. } = start_link(&rig.state, ALEX).await.unwrap().0 else { panic!() };
    for _ in 0..3 {
        last = attempt(&rig.state, ALEX, &attempt_id).await.unwrap().state;
    }
    assert_eq!(last, "linked");
    assert_eq!(connections(&rig.state, ALEX).await.discord.unwrap().username, "alexplays");
    assert!(item(&rig.view(ALEX).await, &member).is_some());
}

#[tokio::test]
async fn accounts_never_see_or_use_each_others_discord_link_or_attempts() {
    let (rig, member, _) = community_rig().await;
    rig.sign_in(STEVE).await;
    rig.fake.with(|w| w.link_as = Some(fan()));
    let LinkStart::Started { attempt_id, .. } = start_link(&rig.state, STEVE).await.unwrap().0 else { panic!() };

    // Alex isn't signed in to the Store: nothing of Steve's, nothing asked.
    let alex = connections(&rig.state, ALEX).await;
    assert_eq!((alex.state, alex.discord.is_none()), (Avail::SignedOut, true));
    assert_eq!(rewards(&rig.state, ALEX).await.state, Avail::SignedOut);
    let before = requests(&rig);
    assert!(attempt(&rig.state, ALEX, &attempt_id).await.is_err(), "Steve's attempt isn't Alex's");
    assert!(reopen_url(&rig.state, ALEX, &attempt_id).is_err());
    assert_eq!(requests(&rig), before, "nothing was sent for another account's attempt");

    rig.sign_in(ALEX).await;
    assert!(attempt(&rig.state, ALEX, &attempt_id).await.is_err());
    for _ in 0..3 {
        attempt(&rig.state, STEVE, &attempt_id).await.unwrap();
    }
    assert!(connections(&rig.state, STEVE).await.discord.is_some());
    let alex = connections(&rig.state, ALEX).await;
    assert_eq!((alex.state, alex.discord.is_none(), alex.uuid.as_str()), (Avail::Ok, true, ALEX));
    assert_eq!(status_of(&rewards(&rig.state, ALEX).await, &member).1, "connect_discord");
    assert!(equip(&rig.state, ALEX, "cape", Some(&member)).await.is_err());

    // Signing Steve out of the Store forgets his attempt and his "Check again" time with his session.
    let LinkStart::Started { attempt_id, .. } = start_link(&rig.state, STEVE).await.unwrap().0 else { panic!() };
    recheck(&rig.state, STEVE).await.unwrap();
    sign_out(&rig.state, STEVE).await;
    assert!(attempt(&rig.state, STEVE, &attempt_id).await.is_err());
    assert_eq!(connections(&rig.state, STEVE).await.state, Avail::SignedOut);
    rig.sign_in(STEVE).await;
    assert!(matches!(recheck(&rig.state, STEVE).await, Ok(Recheck::Limited { .. })), "the Store still limits it");
}

#[tokio::test]
async fn an_ended_store_session_reads_as_signed_out_and_is_forgotten() {
    let (rig, _, _) = community_rig().await;
    rig.sign_in(STEVE).await;
    rig.fake.with(|w| w.expire_sessions());
    assert_eq!(connections(&rig.state, STEVE).await.state, Avail::SignedOut);
    assert!(rig.saved(STEVE).is_none());

    rig.sign_in(STEVE).await;
    rig.fake.with(|w| w.offline = true);
    let c = connections(&rig.state, STEVE).await;
    assert_eq!(c.state, Avail::Unavailable);
    assert_eq!(c.problem.as_deref(), Some(OFFLINE));
    assert_eq!(rewards(&rig.state, STEVE).await.state, Avail::Unavailable);
    assert!(start_link(&rig.state, STEVE).await.is_err());
}

// ---------- rewards ----------

#[tokio::test]
async fn check_again_runs_at_most_once_a_minute_and_reports_when_it_may_run_again() {
    let (rig, member, _) = community_rig().await;
    rig.sign_in(STEVE).await;
    rig.sign_in(ALEX).await;
    link(&rig, STEVE, newface());
    assert_eq!(status_of(&rewards(&rig.state, STEVE).await, &member), ("locked".into(), "join_discord".into(), Some("https://discord.gg/flint".into())));

    // NewFace joins the server, then presses Check again.
    change(&rig, STEVE, |d| d.member = true);
    let Recheck::Ok { connections, rewards } = recheck(&rig.state, STEVE).await.unwrap() else { panic!() };
    assert_eq!(connections.discord.unwrap().guild.member, Some(true));
    assert_eq!(status_of(&rewards, &member).0, "eligible");

    // Pressed again at once: Flint answers itself, nothing is sent.
    let before = requests(&rig);
    let Recheck::Limited { next_allowed_at } = recheck(&rig.state, STEVE).await.unwrap() else { panic!() };
    assert_eq!(requests(&rig), before);
    let now = now_millis();
    assert!(next_allowed_at > now + 50_000 && next_allowed_at <= now + 61_000, "{next_allowed_at} vs {now}");

    // The Store's own 429 (a recheck from another device): its next_allowed_at is kept, and not asked again before it.
    rig.fake.with(|w| w.rechecked_at.insert(dashed(ALEX), fake_now() - 20));
    let Recheck::Limited { next_allowed_at } = recheck(&rig.state, ALEX).await.unwrap() else { panic!() };
    assert_eq!(rig.fake.with(|w| w.count("POST /api/v1/me/rewards/recheck/ 429")), 1);
    let expected = (fake_now() + 40) as u64 * 1000;
    assert!(next_allowed_at.abs_diff(expected) <= 2000, "{next_allowed_at} vs {expected}");
    assert!(matches!(recheck(&rig.state, ALEX).await.unwrap(), Recheck::Limited { .. }));
    assert_eq!(rig.fake.with(|w| w.count("POST /api/v1/me/rewards/recheck/ 429")), 1);
}

fn fake_now() -> i64 {
    crate::core::now_secs() as i64
}

#[tokio::test]
async fn joining_unlocks_leaving_takes_it_away_and_boosting_comes_and_goes() {
    let (rig, member, booster) = community_rig().await;
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);
    link(&rig, STEVE, newface());
    let v0 = rig.view(STEVE).await.inventory.unwrap().version;
    assert!(item(&rig.view(STEVE).await, &member).is_none());

    // Join: unlocked at the next check, then worn.
    change(&rig, STEVE, |d| d.member = true);
    assert!(matches!(changes(&rig.state, STEVE, Some(v0)).await, Changes::Ok { changed: true, .. }));
    assert!(item(&rig.view(STEVE).await, &member).is_some());
    equip(&rig.state, STEVE, "cape", Some(&member)).await.unwrap();

    // Boost on: the booster cape too; boost off: it goes, the member cape stays.
    change(&rig, STEVE, |d| d.booster = true);
    assert_eq!(status_of(&rewards(&rig.state, STEVE).await, &booster).0, "eligible");
    change(&rig, STEVE, |d| d.booster = false);
    let r = rewards(&rig.state, STEVE).await;
    assert_eq!(status_of(&r, &booster), ("locked".into(), "boost_discord".into(), Some("https://discord.gg/flint".into())));
    assert_eq!(status_of(&r, &member).0, "eligible");

    // Leave: the member cape is gone and taken off; the bought cape is untouched.
    change(&rig, STEVE, |d| d.member = false);
    let view = rig.view(STEVE).await;
    assert!(item(&view, &member).is_none());
    assert_eq!(view.inventory.as_ref().unwrap().equipped.get("cape"), None, "the Store took it off; Flint shows that");
    assert_eq!(owned(&view), ["ember-cape"]);
    assert!(equip(&rig.state, STEVE, "cape", Some(&member)).await.is_err());
}

#[tokio::test]
async fn a_discord_outage_keeps_rewards_through_the_grace_period_and_never_unlocks_anything() {
    let (rig, member, booster) = community_rig().await;
    rig.sign_in(STEVE).await;
    link(&rig, STEVE, fan());
    equip(&rig.state, STEVE, "cape", Some(&member)).await.unwrap();

    rig.fake.with(|w| {
        w.discord_down = true;
        w.settle(STEVE);
    });
    let r = rewards(&rig.state, STEVE).await;
    assert_eq!(r.discord.as_ref().unwrap().state, "grace");
    assert_eq!(status_of(&r, &member).0, "eligible", "the last answer still counts");
    assert_eq!(status_of(&r, &booster).0, "locked", "and nothing new is unlocked");
    let view = rig.view(STEVE).await;
    assert_eq!(item(&view, &member).unwrap().state.as_deref(), Some("unavailable"));
    assert_eq!(view.inventory.unwrap().equipped.get("cape"), Some(&member));
    assert_eq!(connections(&rig.state, STEVE).await.discord.unwrap().guild.state, "unavailable");

    // Past the grace period: not usable until a check succeeds.
    rig.fake.with(|w| {
        w.grace_over = true;
        w.settle(STEVE);
    });
    let r = rewards(&rig.state, STEVE).await;
    assert_eq!((r.discord.as_ref().unwrap().state.as_str(), status_of(&r, &member).0.as_str()), ("unavailable", "unavailable"));
    let view = rig.view(STEVE).await;
    assert!(item(&view, &member).is_none() && view.inventory.unwrap().equipped.get("cape").is_none());
}

#[tokio::test]
async fn unlinking_while_wearing_a_discord_cape_ends_it_and_keeps_what_was_bought() {
    let (rig, member, _) = community_rig().await;
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);
    link(&rig, STEVE, fan());
    equip(&rig.state, STEVE, "cape", Some(&member)).await.unwrap();
    let revision = rig.state.store.equip_revision.load(Ordering::SeqCst);

    let after = unlink(&rig.state, STEVE).await.unwrap();
    assert_eq!(rig.fake.with(|w| w.count("DELETE /api/v1/me/connections/discord/ 200")), 1, "sent as DELETE");
    assert!(after.discord.is_none() && after.can_link);
    let view = rig.view(STEVE).await;
    assert_eq!(owned(&view), ["ember-cape"]);
    assert_eq!(view.inventory.unwrap().equipped.get("cape"), None);
    assert!(rig.state.store.equip_revision.load(Ordering::SeqCst) >= revision, "running games are told to look again");
    assert_eq!(status_of(&rewards(&rig.state, STEVE).await, &member).1, "connect_discord");
}

#[tokio::test]
async fn gifts_giveaways_and_purchases_arrive_through_the_change_feed_with_where_they_came_from() {
    let (rig, member, _) = community_rig().await;
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);
    let v0 = rig.view(STEVE).await.inventory.unwrap().version;
    rig.fake.with(|w| w.grant_via(STEVE, &rig.frost, "admin_gift"));
    assert!(matches!(changes(&rig.state, STEVE, Some(v0)).await, Changes::Ok { changed: true, .. }));
    rig.fake.with(|w| w.grant_via(STEVE, &member, "giveaway"));
    let view = rig.view(STEVE).await;
    let via = |id: &str| item(&view, id).map(|o| (o.obtained_via.clone().unwrap_or_default(), o.permanence.clone().unwrap_or_default()));
    assert_eq!(via(&rig.ember), Some(("purchase".into(), "permanent".into())));
    assert_eq!(via(&rig.frost), Some(("admin_gift".into(), "permanent".into())));
    assert_eq!(via(&member), Some(("giveaway".into(), "permanent".into())));

    // A cape won in a giveaway that is also a Discord reward is listed once, as owned; leaving Discord never takes it.
    link(&rig, STEVE, fan());
    let view = rig.view(STEVE).await;
    assert_eq!(view.inventory.as_ref().unwrap().owned.iter().filter(|o| o.cosmetic_id == member).count(), 1);
    assert_eq!(status_of(&rewards(&rig.state, STEVE).await, &member).0, "owned");
    unlink(&rig.state, STEVE).await.unwrap();
    assert!(item(&rig.view(STEVE).await, &member).is_some());
}

#[tokio::test]
async fn the_first_launch_reward_is_the_stores_once_per_account_never_the_launchers() {
    let (mut rig, _, _) = community_rig().await;
    let first = rig.fake.with(|w| w.add_reward("first-launch", "first_launch", FIRST_ART));
    rig.sign_in(STEVE).await;
    let view = rig.view(STEVE).await;
    assert_eq!(item(&view, &first).unwrap().obtained_via.as_deref(), Some("first_launch"));
    // A reinstall: nothing in memory, no cached copy. The Store never gives it twice; Flint never asks for it.
    rig.restart();
    std::fs::remove_dir_all(rig.state.paths.cache().join("store")).ok();
    let view = rig.view(STEVE).await;
    assert_eq!(view.inventory.as_ref().unwrap().owned.iter().filter(|o| o.cosmetic_id == first).count(), 1);
    assert_eq!(status_of(&rewards(&rig.state, STEVE).await, &first), ("owned".into(), "none".into(), None));
    assert!(rig.fake.with(|w| w.log.iter().all(|l| !l.starts_with("POST /api/v1/me/"))), "Flint only reads");
}

// ---------- the community catalog ----------

#[tokio::test]
async fn the_community_catalog_is_asked_once_a_minute_with_its_etag_and_follows_the_stores_switches() {
    let (rig, member, booster) = community_rig().await;
    let first = rig.fake.with(|w| w.add_reward("first-launch", "first_launch", FIRST_ART));
    let c = catalog(&rig.state).await;
    assert_eq!((c.state, c.enabled, c.discord_enabled), (Avail::Ok, true, true));
    assert_eq!(c.rewards.iter().map(|r| r.cosmetic_id.clone()).collect::<Vec<_>>(), [member.clone(), booster.clone(), first.clone()]);
    let m = &c.rewards[0];
    assert_eq!((m.requirement.kind.as_str(), m.requirement.label.as_str(), m.permanence.as_str()), ("discord_member", "Member of the Flint Discord server", "conditional"));
    assert_eq!(c.rewards[2].permanence, "permanent");
    let shown = serde_json::to_value(&c).unwrap();
    assert!(shown["rewards"][0]["asset"].get("url").is_none() && shown["rewards"][0]["asset"].get("sha256").is_none());
    assert_eq!(shown["rewards"][0]["requirement"]["type"], "discord_member");

    // Within the minute nothing is sent; after it, an unchanged catalog is a 304.
    catalog(&rig.state).await;
    assert_eq!(rig.fake.with(|w| w.count("GET /api/v1/catalog/community/ 200")), 1);
    age_catalog(&rig.state);
    assert_eq!(catalog(&rig.state).await.rewards.len(), 3);
    assert_eq!(rig.fake.with(|w| w.count("GET /api/v1/catalog/community/ 304")), 1);

    // Discord rewards switched off: they aren't listed at all. Community rewards off: nothing is.
    rig.fake.with(|w| w.discord_rewards_enabled = false);
    age_catalog(&rig.state);
    let c = catalog(&rig.state).await;
    assert_eq!((c.discord_enabled, c.rewards.len()), (false, 1));
    rig.fake.with(|w| w.rewards_enabled = false);
    age_catalog(&rig.state);
    let c = catalog(&rig.state).await;
    assert_eq!((c.state, c.enabled, c.rewards.len()), (Avail::Ok, false, 0));

    // Offline: the last answer stays.
    rig.fake.with(|w| {
        w.rewards_enabled = true;
        w.discord_rewards_enabled = true;
    });
    age_catalog(&rig.state);
    assert_eq!(catalog(&rig.state).await.rewards.len(), 3);
    rig.fake.with(|w| w.offline = true);
    age_catalog(&rig.state);
    assert_eq!(catalog(&rig.state).await.rewards.len(), 3);
}

#[tokio::test]
async fn a_locked_rewards_art_can_be_looked_at_without_owning_it_and_only_if_it_matches_its_hash() {
    let (rig, member, booster) = community_rig().await;
    // A listed reward whose art doesn't match its hash is left out of the catalog.
    rig.fake.with(|w| w.cosmetics.get_mut(&booster).unwrap().sha256 = "not-a-hash".into());
    let c = catalog(&rig.state).await;
    assert_eq!(c.rewards.iter().map(|r| r.cosmetic_id.as_str()).collect::<Vec<_>>(), [member.as_str()]);

    let url = reward_asset(&rig.state, &member).await.unwrap();
    assert_eq!(STANDARD.decode(url.strip_prefix("data:image/png;base64,").unwrap()).unwrap(), MEMBER_ART);
    reward_asset(&rig.state, &member).await.unwrap();
    assert_eq!(rig.fake.with(|w| w.count("GET /assets/discord-member.png 200")), 1, "kept after it was checked");
    assert!(reward_asset(&rig.state, &booster).await.is_err(), "not listed");
    assert!(reward_asset(&rig.state, &rig.ember).await.is_err(), "not a reward");

    // Bytes that aren't the ones the Store hashed are refused.
    std::fs::remove_dir_all(rig.state.paths.cache().join("store")).ok();
    rig.fake.with(|w| w.files.insert("/assets/discord-member.png".into(), b"\x89PNG\r\n\x1a\nswapped".to_vec()));
    assert_eq!(reward_asset(&rig.state, &member).await.unwrap_err(), HASH_MISMATCH);
}

#[tokio::test]
async fn an_animated_reward_capes_strips_are_checked_like_the_stores() {
    use super::tests::png;
    let rig = rig().await;
    let strips = vec![(png(352, 7 * 272, "r0"), 7), (png(352, 3 * 272, "r1"), 3)];
    let durations: Vec<u32> = (0..10).map(|f| 60 + f * 10).collect();
    let id = rig.fake.with(|w| {
        w.enable_community();
        let id = w.add_animated_cape("event-glow", EMBER, 16, &durations, &strips);
        w.rewards.push(super::fake::FakeReward { cosmetic_id: id.clone(), requirement: "event".into() });
        id
    });
    let c = catalog(&rig.state).await;
    let animation = c.rewards[0].asset.animation.as_ref().expect("the manifest is kept");
    assert_eq!((animation.frame_count, animation.frames_per_strip), (10, 7));
    assert_eq!(reward_strip(&rig.state, &id, 1).await.unwrap(), strips[1].0);
    assert!(reward_strip(&rig.state, &id, 2).await.is_err());
}

// ---------- the inventory's new fields ----------

#[test]
fn an_inventory_reads_with_and_without_the_new_fields_and_a_reward_is_never_read_as_owned_for_good() {
    let item = |id: u32, extra: Value| {
        let mut o = json!({ "cosmetic_id": format!("00000000-0000-4000-8000-{id:012}"), "slug": format!("c{id}"), "kind": "cape", "name": "Cape" });
        o.as_object_mut().unwrap().extend(extra.as_object().unwrap().clone());
        o
    };
    let body = json!({
        "version": 2, "minecraft_uuid": dashed(STEVE), "name": "Steve",
        "owned": [
            item(1, json!({})),
            item(2, json!({ "obtained_via": "admin_gift", "permanence": "permanent", "state": "owned", "something_new": { "x": 1 } })),
            item(3, json!({ "source": "reward", "obtained_via": "discord_member", "permanence": "conditional", "state": "eligible", "granted_at": null })),
            item(4, json!({ "source": "reward", "obtained_via": "discord_booster", "permanence": "permanent", "state": "owned" })),
            item(5, json!({ "obtained_via": "Not A Code!", "permanence": "forever", "state": "sort-of" })),
            item(6, json!({ "obtained_via": 7, "permanence": null, "state": ["owned"] })),
        ],
        "equipped": {}
    });
    let inventory = parse_inventory(&body).expect("unknown and odd fields never refuse an inventory");
    let read: Vec<_> = inventory.owned.iter().map(|o| (o.obtained_via.as_deref(), o.permanence.as_deref(), o.state.as_deref())).collect();
    assert_eq!(
        read,
        [
            (None, None, None),
            (Some("admin_gift"), Some("permanent"), Some("owned")),
            (Some("discord_member"), Some("conditional"), Some("eligible")),
            (Some("discord_booster"), Some("conditional"), Some("owned")),
            (None, None, None),
            (None, None, None),
        ],
        "values Flint doesn't know, or of the wrong type, read as none"
    );
    assert_eq!(inventory.owned.len(), 6, "never refusing the item or the inventory");
}
