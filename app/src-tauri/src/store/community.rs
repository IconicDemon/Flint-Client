//! Discord connected accounts and community rewards from the launcher: the Store's contract
//! `docs/connections-rewards-api-v1.md` (v1). Every call goes through the account's own Store session, here in Rust; the
//! interface never talks to the Store or to Discord itself.
//!
//! - **Nothing about Discord is ever sent from here.** Linking happens on Discord's own OAuth2 page in the system browser
//!   (never a webview, never a code, token or password typed into Flint); membership and boosting are the Store's bot's
//!   answer. Flint only reads what the Store says and asks it to check again.
//! - **Nothing is granted here.** What an account may wear is the Store's inventory; rewards are its status for each listed
//!   reward. Equipping is the existing `PUT /me/equipped`, which the Store refuses for a cape that doesn't count (any more).
//! - **A Store without these routes has the feature off.** Today's production Store has none of them: a 404 (405, 410,
//!   501), an answer that isn't the contract's shape, or `503 discord_unconfigured` is [`Avail::Off`], which the interface
//!   shows as nothing at all. Never an error.
//! - **Every address is checked before it is opened or shown:** Discord's authorize page ([`authorize_url`]), invites
//!   ([`invite_url`]) and avatars ([`avatar_url`]). Anything else is refused.
//! - **Accounts never mix:** every command names the account; link attempts and "Check again" times are kept per account and
//!   forgotten with its Store session. The community catalog is public (the same for everyone) and cached once.
use super::*;
use tauri_plugin_opener::OpenerExt;

/// A link attempt lasts 10 minutes at the Store; Flint stops polling and reopening it then.
pub const ATTEMPT_LIFETIME_MS: u64 = 10 * 60 * 1000;
/// The community catalog is asked for at most once a minute (it is `public, max-age=60`).
pub const CATALOG_EVERY_MS: u64 = 60 * 1000;
/// "Check again" runs at most once a minute per account (the Store's limit; Flint keeps it too).
pub const RECHECK_EVERY_MS: u64 = 60 * 1000;
const MAX_REWARDS: usize = 100;

const UNTRUSTED_AUTHORIZE: &str = "The Flint Store sent a Discord sign-in address Flint doesn't trust, so Flint didn't open it. Try again later.";
const TOO_MANY_STARTS: &str = "You've started connecting Discord several times in a few minutes. Wait a few minutes, then try again.";
const ATTEMPT_GONE: &str = "That Discord connection isn't open for this account any more. Start again.";

// ---------- what may be opened ----------

fn query_once(url: &Url) -> Option<HashMap<String, String>> {
    let mut seen = HashMap::new();
    for (key, value) in url.query_pairs() {
        // A parameter given twice (`scope=identify&scope=guilds`) is read differently by different readers: refused.
        if seen.insert(key.into_owned(), value.into_owned()).is_some() {
            return None;
        }
    }
    Some(seen)
}

fn token_like(value: Option<&String>, length: std::ops::RangeInclusive<usize>) -> bool {
    value.is_some_and(|v| length.contains(&v.len()) && v.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_'))
}

fn loopback_host(url: &Url) -> bool {
    matches!(url.host_str(), Some("127.0.0.1" | "localhost"))
}

fn bare(url: &Url) -> bool {
    url.username().is_empty() && url.password().is_none() && url.fragment().is_none()
}

/// The Store's callback page Discord sends the browser back to: `https://<this Store, or a flintclient.com host>/connect/
/// discord/callback/` (in development, also http on this computer: the Store's local demo).
fn redirect_ok(value: Option<&String>, store: &Url, loopback: bool) -> bool {
    let Some(url) = value.and_then(|v| Url::parse(v).ok()) else { return false };
    let host = url.host_str().unwrap_or_default();
    let flint = host == "flintclient.com" || host.ends_with(".flintclient.com") || Some(host) == store.host_str();
    let place = (url.scheme() == "https" && flint && url.port().is_none()) || (loopback && url.scheme() == "http" && loopback_host(&url));
    place && bare(&url) && url.query().is_none() && url.path() == "/connect/discord/callback/"
}

/// Discord's OAuth2 authorize page as the Store must send it: https, host exactly `discord.com`, path `/oauth2/authorize`
/// (or `/api/oauth2/authorize`), scope exactly `identify`, `response_type=code`, a `state`, PKCE with `S256`, a client id
/// and the callback of `store` (the Store this launcher uses); no credentials, no fragment, no parameter twice. None for
/// anything else.
///
/// Development builds also accept the Store's local demo Discord (`http://127.0.0.1:54321/discord/oauth2/authorize`, http on
/// 127.0.0.1 or localhost only, with the same query checks); release builds never do ([`authorize_url_release`]).
pub fn authorize_url(raw: &str, store: &Url) -> Option<Url> {
    if cfg!(debug_assertions) {
        authorize_url_checked(raw, store, true)
    } else {
        authorize_url_release(raw, store)
    }
}

/// The release rule: discord.com only.
pub fn authorize_url_release(raw: &str, store: &Url) -> Option<Url> {
    authorize_url_checked(raw, store, false)
}

fn authorize_url_checked(raw: &str, store: &Url, loopback: bool) -> Option<Url> {
    if raw.len() > 4096 {
        return None;
    }
    let url = Url::parse(raw).ok()?;
    let host = url.host_str()?;
    let discord = url.scheme() == "https" && host == "discord.com" && url.port().is_none() && matches!(url.path(), "/oauth2/authorize" | "/api/oauth2/authorize");
    let demo = loopback && url.scheme() == "http" && loopback_host(&url) && url.path().ends_with("/oauth2/authorize");
    if !(discord || demo) || !bare(&url) {
        return None;
    }
    let q = query_once(&url)?;
    let is = |key: &str, want: &str| q.get(key).map(String::as_str) == Some(want);
    let ok = is("scope", "identify")
        && is("response_type", "code")
        && is("code_challenge_method", "S256")
        && token_like(q.get("state"), 16..=200)
        && token_like(q.get("code_challenge"), 43..=128)
        && token_like(q.get("client_id"), 1..=64)
        && redirect_ok(q.get("redirect_uri"), store, loopback);
    ok.then_some(url)
}

/// An invite to a Discord server: https `discord.gg/<code>`, or `discord.com/invite/<code>` (also `www.discord.com`). No
/// credentials, port, query or fragment. None for anything else.
pub fn invite_url(raw: &str) -> Option<Url> {
    if raw.len() > 300 {
        return None;
    }
    let url = Url::parse(raw).ok()?;
    if url.scheme() != "https" || !bare(&url) || url.port().is_some() || url.query().is_some() {
        return None;
    }
    let code = match url.host_str()? {
        "discord.gg" => url.path().strip_prefix('/'),
        "discord.com" | "www.discord.com" => url.path().strip_prefix("/invite/"),
        _ => None,
    }?;
    let code = code.strip_suffix('/').unwrap_or(code);
    ((2..=64).contains(&code.len()) && code.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')).then_some(url)
}

/// A Discord avatar: https on `cdn.discordapp.com` only (no credentials, port or fragment). None for anything else, and the
/// interface shows a default avatar.
pub fn avatar_url(raw: &str) -> Option<Url> {
    if raw.len() > 400 {
        return None;
    }
    let url = Url::parse(raw).ok()?;
    (url.scheme() == "https" && url.host_str() == Some("cdn.discordapp.com") && url.port().is_none() && bare(&url)).then_some(url)
}

// ---------- state ----------

#[derive(Clone)]
struct Attempt {
    id: String,
    url: Url,
    started: u64,
}

#[derive(Clone)]
struct CatalogCache {
    etag: Option<String>,
    /// When the Store was last asked (whatever it answered): the once-a-minute limit.
    asked_at: u64,
    catalog: Catalog,
}

/// This run's community state ([`StoreState::community`]).
#[derive(Default)]
pub struct CommunityState {
    catalog: Mutex<Option<CatalogCache>>,
    /// Each account's open Discord link attempt, by canonical UUID.
    attempts: Mutex<HashMap<String, Attempt>>,
    /// When each account may next ask "Check again" (ms since the epoch).
    recheck_after: Mutex<HashMap<String, u64>>,
}

/// Forgets what this run kept for the account (its open link attempt, its "Check again" time): it signed out of the Store
/// or left Flint.
pub fn forget(state: &AppState, uuid: &str) {
    state.store.community.attempts.lock().unwrap().remove(uuid);
    state.store.community.recheck_after.lock().unwrap().remove(uuid);
}

// ---------- reading the Store ----------

/// Whether the feature is there for this account.
/// - `ok`: the Store answered with the contract's shape.
/// - `off`: this Store doesn't have it (404 and the like, `discord_unconfigured`, an answer that isn't the contract's shape).
///   The interface shows nothing new.
/// - `signedOut`: the account has no Store session (or the Store ended it).
/// - `unavailable`: the Store couldn't answer now (offline, `unavailable`, rate limited); keep what was shown.
#[derive(Serialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum Avail {
    Ok,
    Off,
    SignedOut,
    Unavailable,
}

/// A route this Store doesn't have, or Discord not configured there: the feature is off.
fn missing(raw: &Raw) -> bool {
    matches!(raw.status, 404 | 405 | 410 | 501) || error_code(&raw.body) == "discord_unconfigured"
}

fn schema_one(body: &Value) -> bool {
    body.is_object() && body["schema"].as_i64() == Some(1)
}

enum Read {
    Body(Value),
    Off,
    SignedOut,
    Unavailable(String),
}

/// A private read for the account, without asking the system for its saved sign-in again (automatic).
async fn read(state: &AppState, uuid: &str, path: &str) -> Read {
    let session = match load_session(state, uuid, false) {
        Lookup::Found(session) if !session.expired() => session,
        _ => return Read::SignedOut,
    };
    match send(state, Method::GET, path, Some(&session.token), None, None).await {
        Err(failure) => Read::Unavailable(failure_text(&failure)),
        Ok(raw) if raw.status == 401 => {
            drop_session(state, uuid);
            clear_cache(state, uuid);
            Read::SignedOut
        }
        Ok(raw) if missing(&raw) => Read::Off,
        Ok(raw) if (200..300).contains(&raw.status) => {
            if schema_one(&raw.body) {
                Read::Body(raw.body)
            } else {
                Read::Off
            }
        }
        Ok(raw) => Read::Unavailable(failure_text(&Failure::Refused { status: raw.status, code: error_code(&raw.body) })),
    }
}

fn text(value: &Value, max: usize) -> Option<String> {
    value.as_str().map(|t| clip(t, max)).filter(|t| !t.trim().is_empty())
}

/// A short code the Store writes (`discord_member`, `join_discord`): lowercase letters and `_` only.
fn code(value: &Value) -> Option<String> {
    value.as_str().filter(|c| (1..=40).contains(&c.len()) && c.bytes().all(|b| b.is_ascii_lowercase() || b == b'_')).map(str::to_owned)
}

// ---------- the Discord connection ----------

/// How Flint's server sees the linked Discord account.
/// - `state`: `checking` (linked, not checked yet), `verified`, `pending` (membership screening not finished) or
///   `unavailable` (Discord couldn't be reached at the last check). Anything else is shown as `unavailable`.
/// - `member`, `booster`: none until the first successful check.
#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Guild {
    pub state: String,
    pub member: Option<bool>,
    pub booster: Option<bool>,
    pub checked_at: Option<String>,
    pub verified_at: Option<String>,
}

/// The Discord account linked to this Minecraft account, as the interface shows it. Its Discord id stays here.
#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DiscordLink {
    pub username: String,
    pub display_name: Option<String>,
    /// Only a `cdn.discordapp.com` address ([`avatar_url`]); none otherwise (a default avatar is shown).
    pub avatar_url: Option<String>,
    pub linked_at: Option<String>,
    pub guild: Guild,
}

/// Settings → Connected Accounts for one account.
/// - `can_link`: the Store lets players link Discord (`enabled`, and Discord configured). If not, there's no Connect, but a
///   link that exists is still shown so it can be removed.
#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionsView {
    pub uuid: String,
    pub state: Avail,
    pub can_link: bool,
    pub discord: Option<DiscordLink>,
    pub problem: Option<String>,
}

impl ConnectionsView {
    fn empty(uuid: &str, state: Avail, problem: Option<String>) -> Self {
        Self { uuid: uuid.to_owned(), state, can_link: false, discord: None, problem }
    }
}

fn parse_link(value: &Value) -> Option<DiscordLink> {
    let user = value.get("user").filter(|u| u.is_object())?;
    let guild = &value["guild"];
    let state = guild["state"].as_str().filter(|s| matches!(*s, "checking" | "verified" | "pending" | "unavailable")).unwrap_or("unavailable");
    Some(DiscordLink {
        username: text(&user["username"], 40).unwrap_or_default(),
        display_name: text(&user["global_name"], 40),
        avatar_url: user["avatar_url"].as_str().and_then(avatar_url).map(String::from),
        linked_at: text(&value["linked_at"], 40),
        guild: Guild {
            state: state.to_owned(),
            member: guild["member"].as_bool(),
            booster: guild["booster"].as_bool(),
            checked_at: text(&guild["checked_at"], 40),
            verified_at: text(&guild["verified_at"], 40),
        },
    })
}

/// `GET /me/connections/`, checked. None when it isn't the contract's shape.
fn parse_connections(uuid: &str, body: &Value) -> Option<ConnectionsView> {
    if !schema_one(body) {
        return None;
    }
    let enabled = body["enabled"].as_bool()?;
    let unconfigured = body["reason"].as_str() == Some("discord_unconfigured");
    let discord = match &body["discord"] {
        Value::Null => None,
        link => Some(parse_link(link)?),
    };
    Some(ConnectionsView { uuid: uuid.to_owned(), state: Avail::Ok, can_link: enabled && !unconfigured, discord, problem: None })
}

/// The account's Discord connection (may make the Store run one due membership check, bounded to 3 s there).
pub async fn connections(state: &AppState, uuid: &str) -> ConnectionsView {
    match read(state, uuid, "/api/v1/me/connections/").await {
        Read::Body(body) => parse_connections(uuid, &body).unwrap_or_else(|| ConnectionsView::empty(uuid, Avail::Off, None)),
        Read::Off => ConnectionsView::empty(uuid, Avail::Off, None),
        Read::SignedOut => ConnectionsView::empty(uuid, Avail::SignedOut, None),
        Read::Unavailable(problem) => ConnectionsView::empty(uuid, Avail::Unavailable, Some(problem)),
    }
}

/// Starting a link: an attempt to poll, or the feature is off at this Store.
#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum LinkStart {
    #[serde(rename_all = "camelCase")]
    Started { attempt_id: String, expires_at: Option<String>, opened: bool },
    Off,
}

/// `POST /me/connections/discord/start/`: a new attempt for the account (the Store cancels any earlier one), with Discord's
/// authorize page checked by [`authorize_url`]. The page is the caller's to open; it is kept for "Open Discord again".
pub async fn start_link(state: &AppState, uuid: &str) -> CmdResult<(LinkStart, Option<Url>)> {
    let session = session_for_action(state, uuid)?;
    let raw = send(state, Method::POST, "/api/v1/me/connections/discord/start/", Some(&session.token), None, None).await.map_err(|f| failure_text(&f))?;
    let code = error_code(&raw.body);
    match raw.status {
        401 => return Err(action_failure(state, uuid, Failure::SignedOut)),
        429 => return Err(TOO_MANY_STARTS.into()),
        403 if code == "discord_disabled" => return Ok((LinkStart::Off, None)),
        _ if missing(&raw) => return Ok((LinkStart::Off, None)),
        200..=299 => {}
        status => return Err(failure_text(&Failure::Refused { status, code })),
    }
    let body = &raw.body;
    let url = body["authorize_url"].as_str().and_then(|u| authorize_url(u, &state.store.endpoints.store)).ok_or(UNTRUSTED_AUTHORIZE)?;
    let id = body["attempt_id"].as_str().filter(|id| is_uuid(id)).ok_or_else(|| failure_text(&Failure::Invalid))?.to_owned();
    let expires_at = text(&body["expires_at"], 40);
    state.store.community.attempts.lock().unwrap().insert(uuid.to_owned(), Attempt { id: id.clone(), url: url.clone(), started: now_millis() });
    Ok((LinkStart::Started { attempt_id: id, expires_at, opened: false }, Some(url)))
}

/// The account's open attempt `attempt_id`, still within its 10 minutes.
fn open_attempt(state: &AppState, uuid: &str, attempt_id: &str) -> Option<Attempt> {
    let attempts = state.store.community.attempts.lock().unwrap();
    attempts.get(uuid).filter(|a| a.id == attempt_id && now_millis().saturating_sub(a.started) < ATTEMPT_LIFETIME_MS).cloned()
}

/// Discord's page for the account's open attempt again ("Open Discord again"), checked again before it is opened.
pub fn reopen_url(state: &AppState, uuid: &str, attempt_id: &str) -> CmdResult<Url> {
    let attempt = open_attempt(state, uuid, attempt_id).ok_or(ATTEMPT_GONE)?;
    authorize_url(attempt.url.as_str(), &state.store.endpoints.store).ok_or_else(|| UNTRUSTED_AUTHORIZE.to_owned())
}

/// Where a link attempt is: `waiting`, `linked`, `cancelled`, `denied`, `expired`, `already_linked_elsewhere` or `error`.
#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AttemptView {
    pub state: String,
    pub expires_at: Option<String>,
}

const ATTEMPT_STATES: [&str; 7] = ["waiting", "linked", "cancelled", "denied", "expired", "already_linked_elsewhere", "error"];

/// `GET /me/connections/discord/attempts/{id}/` for the attempt this account started in this run (no other attempt is
/// ever asked about). Past its 10 minutes it is `expired` without asking. Once it isn't `waiting`, it is forgotten.
pub async fn attempt(state: &AppState, uuid: &str, attempt_id: &str) -> CmdResult<AttemptView> {
    let known = state.store.community.attempts.lock().unwrap().get(uuid).filter(|a| a.id == attempt_id).cloned();
    let Some(known) = known else { return Err(ATTEMPT_GONE.into()) };
    let finished = |view: AttemptView| {
        if view.state != "waiting" {
            let mut attempts = state.store.community.attempts.lock().unwrap();
            if attempts.get(uuid).is_some_and(|a| a.id == attempt_id) {
                attempts.remove(uuid);
            }
        }
        view
    };
    if now_millis().saturating_sub(known.started) >= ATTEMPT_LIFETIME_MS {
        return Ok(finished(AttemptView { state: "expired".into(), expires_at: None }));
    }
    let session = match load_session(state, uuid, false) {
        Lookup::Found(session) if !session.expired() => session,
        _ => return Err(SIGN_IN_AGAIN.into()),
    };
    let path = format!("/api/v1/me/connections/discord/attempts/{attempt_id}/");
    let raw = send(state, Method::GET, &path, Some(&session.token), None, None).await.map_err(|f| failure_text(&f))?;
    match raw.status {
        401 => Err(action_failure(state, uuid, Failure::SignedOut)),
        // Only the account that started it can read it; for that account a 404 means it's gone.
        404 => Ok(finished(AttemptView { state: "expired".into(), expires_at: None })),
        200..=299 => {
            let said = raw.body["state"].as_str().unwrap_or_default();
            let state_name = if ATTEMPT_STATES.contains(&said) { said } else { "error" };
            Ok(finished(AttemptView { state: state_name.into(), expires_at: text(&raw.body["expires_at"], 40) }))
        }
        status => Err(failure_text(&Failure::Refused { status, code: error_code(&raw.body) })),
    }
}

/// `DELETE /me/connections/discord/`: removes the link. Discord rewards end at once at the Store (a worn one is taken off
/// there, so running games are told to look again); bought and given capes stay. Then the connection as it is now.
pub async fn unlink(state: &AppState, uuid: &str) -> CmdResult<ConnectionsView> {
    let session = session_for_action(state, uuid)?;
    let raw = send(state, Method::DELETE, "/api/v1/me/connections/discord/", Some(&session.token), None, None).await.map_err(|f| failure_text(&f))?;
    match raw.status {
        401 => return Err(action_failure(state, uuid, Failure::SignedOut)),
        _ if missing(&raw) => return Ok(ConnectionsView::empty(uuid, Avail::Off, None)),
        200..=299 if raw.body["ok"].as_bool() == Some(true) => {}
        200..=299 => return Err(failure_text(&Failure::Invalid)),
        status => return Err(failure_text(&Failure::Refused { status, code: error_code(&raw.body) })),
    }
    forget(state, uuid);
    handoff::equip_changed(state);
    Ok(connections(state, uuid).await)
}

// ---------- rewards ----------

/// The account's Discord standing for rewards: `state` is `disconnected`, `checking`, `verified`, `grace` (Discord can't be
/// reached; the last answer still counts) or `unavailable`.
#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Standing {
    pub linked: bool,
    pub member: bool,
    pub booster: bool,
    pub pending: bool,
    pub state: String,
}

/// What would unlock a locked reward. `url` only for `join_discord`, `complete_screening` and `boost_discord`, and only an
/// invite [`invite_url`] accepts.
#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RewardAction {
    pub kind: String,
    pub url: Option<String>,
}

/// The account's standing on one listed reward. `status`: `owned` (permanently), `eligible` (conditional, earned now),
/// `checking`, `unavailable` (Discord can't confirm; not usable) or `locked`. Anything else the Store says is `locked`.
#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RewardStatus {
    pub cosmetic_id: String,
    pub requirement: String,
    /// `permanent` or `conditional`; Discord rewards are always conditional.
    pub permanence: String,
    pub status: String,
    pub action: RewardAction,
}

#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RewardsView {
    pub uuid: String,
    pub state: Avail,
    pub discord: Option<Standing>,
    pub rewards: Vec<RewardStatus>,
    pub problem: Option<String>,
}

impl RewardsView {
    fn empty(uuid: &str, state: Avail, problem: Option<String>) -> Self {
        Self { uuid: uuid.to_owned(), state, discord: None, rewards: Vec::new(), problem }
    }
}

const STATUSES: [&str; 5] = ["owned", "eligible", "checking", "unavailable", "locked"];
const ACTIONS: [&str; 6] = ["connect_discord", "join_discord", "complete_screening", "boost_discord", "launch", "none"];
const INVITE_ACTIONS: [&str; 3] = ["join_discord", "complete_screening", "boost_discord"];

/// Discord rewards are conditional whatever else is said; others are permanent only when the Store says so.
fn permanence_of(requirement: &str, said: &Value) -> String {
    let permanent = !requirement.starts_with("discord_") && said.as_str() == Some("permanent");
    (if permanent { "permanent" } else { "conditional" }).into()
}

fn parse_standing(value: &Value) -> Option<Standing> {
    value.is_object().then(|| Standing {
        linked: value["linked"].as_bool().unwrap_or(false),
        member: value["member"].as_bool().unwrap_or(false),
        booster: value["booster"].as_bool().unwrap_or(false),
        pending: value["pending"].as_bool().unwrap_or(false),
        state: value["state"].as_str().filter(|s| matches!(*s, "disconnected" | "checking" | "verified" | "grace" | "unavailable")).unwrap_or("unavailable").into(),
    })
}

fn parse_reward_status(value: &Value) -> Option<RewardStatus> {
    let cosmetic_id = value["cosmetic_id"].as_str().filter(|id| is_uuid(id))?.to_owned();
    let requirement = code(&value["requirement"]).unwrap_or_default();
    let status = value["status"].as_str().filter(|s| STATUSES.contains(s)).unwrap_or("locked").to_owned();
    let said = value["action"]["kind"].as_str().filter(|k| ACTIONS.contains(k)).unwrap_or("none");
    // Only a locked reward has something to do; the Store says so too.
    let kind = if status == "locked" { said } else { "none" };
    let url = INVITE_ACTIONS.contains(&kind).then(|| value["action"]["url"].as_str().and_then(invite_url)).flatten().map(String::from);
    Some(RewardStatus { permanence: permanence_of(&requirement, &value["permanence"]), cosmetic_id, requirement, status, action: RewardAction { kind: kind.into(), url } })
}

/// `GET /me/rewards/`, checked. None when it isn't the contract's shape.
fn parse_rewards(uuid: &str, body: &Value) -> Option<RewardsView> {
    if !schema_one(body) {
        return None;
    }
    let listed = body["rewards"].as_array()?;
    let rewards = listed.iter().take(MAX_REWARDS).filter_map(parse_reward_status).collect();
    Some(RewardsView { uuid: uuid.to_owned(), state: Avail::Ok, discord: parse_standing(&body["discord"]), rewards, problem: None })
}

/// The account's standing on each listed reward.
pub async fn rewards(state: &AppState, uuid: &str) -> RewardsView {
    match read(state, uuid, "/api/v1/me/rewards/").await {
        Read::Body(body) => parse_rewards(uuid, &body).unwrap_or_else(|| RewardsView::empty(uuid, Avail::Off, None)),
        Read::Off => RewardsView::empty(uuid, Avail::Off, None),
        Read::SignedOut => RewardsView::empty(uuid, Avail::SignedOut, None),
        Read::Unavailable(problem) => RewardsView::empty(uuid, Avail::Unavailable, Some(problem)),
    }
}

/// "Check again": the Store's answers after checking Discord now, or when it may be asked next. The answers are boxed so
/// the enum stays small (they serialize exactly as before).
#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum Recheck {
    Ok { connections: Box<ConnectionsView>, rewards: Box<RewardsView> },
    /// Not before this time (ms since the epoch); nothing was sent when Flint already knew.
    #[serde(rename_all = "camelCase")]
    Limited { next_allowed_at: u64 },
    Off,
}

/// When the Store's 429 says to come back: `next_allowed_at`, else `Retry-After`, else a minute; within 1 s to 10 minutes.
fn next_allowed(raw: &Raw, now: u64) -> u64 {
    let said = raw.body["next_allowed_at"].as_str().and_then(rfc3339_secs).map(|s| (s.max(0) as u64) * 1000);
    let after = raw.retry_after.map(|s| now + s * 1000);
    said.or(after).unwrap_or(now + RECHECK_EVERY_MS).clamp(now + 1000, now + 10 * 60 * 1000)
}

/// `POST /me/rewards/recheck/`, at most once a minute per account (the Store's limit, kept here too so nothing can loop).
pub async fn recheck(state: &AppState, uuid: &str) -> CmdResult<Recheck> {
    let now = now_millis();
    let waiting = state.store.community.recheck_after.lock().unwrap().get(uuid).copied().filter(|at| *at > now);
    if let Some(at) = waiting {
        return Ok(Recheck::Limited { next_allowed_at: at });
    }
    let session = session_for_action(state, uuid)?;
    let raw = send(state, Method::POST, "/api/v1/me/rewards/recheck/", Some(&session.token), None, None).await.map_err(|f| failure_text(&f))?;
    let remember = |at: u64| state.store.community.recheck_after.lock().unwrap().insert(uuid.to_owned(), at);
    match raw.status {
        401 => Err(action_failure(state, uuid, Failure::SignedOut)),
        429 => {
            let at = next_allowed(&raw, now);
            remember(at);
            Ok(Recheck::Limited { next_allowed_at: at })
        }
        _ if missing(&raw) => Ok(Recheck::Off),
        200..=299 => {
            remember(now + RECHECK_EVERY_MS);
            let connections = parse_connections(uuid, &raw.body["connection"]);
            let rewards = parse_rewards(uuid, &raw.body["rewards"]);
            match (connections, rewards) {
                (Some(connections), Some(rewards)) => Ok(Recheck::Ok { connections: Box::new(connections), rewards: Box::new(rewards) }),
                _ => Ok(Recheck::Off),
            }
        }
        status => Err(failure_text(&Failure::Refused { status, code: error_code(&raw.body) })),
    }
}

// ---------- the community catalog ----------

#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Requirement {
    /// `discord_member`, `discord_booster`, `first_launch`, `event`, or one Flint doesn't know yet.
    #[serde(rename = "type")]
    pub kind: String,
    /// The Store's words for it ("Member of the Flint Discord server"), shown as they are.
    pub label: String,
    pub description: Option<String>,
}

/// A listed reward cape: what it is, what earns it, and its approved art (address and hash stay here).
#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CommunityReward {
    pub cosmetic_id: String,
    pub slug: String,
    pub kind: String,
    pub name: String,
    pub description: Option<String>,
    pub requirement: Requirement,
    pub permanence: String,
    pub starts_at: Option<String>,
    pub ends_at: Option<String>,
    pub sort: i64,
    pub asset: Asset,
    pub animated: bool,
    pub hd: bool,
}

/// `GET /catalog/community/`, checked. `enabled: false` lists nothing; `discord_enabled: false` lists no Discord reward.
#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Catalog {
    pub state: Avail,
    pub enabled: bool,
    pub discord_enabled: bool,
    pub rewards: Vec<CommunityReward>,
    /// When the Store last sent or confirmed it (ms).
    pub fetched_at: Option<u64>,
}

impl Catalog {
    fn empty(state: Avail) -> Self {
        Self { state, enabled: false, discord_enabled: false, rewards: Vec::new(), fetched_at: None }
    }
}

fn usable_asset(value: &Value) -> Option<Asset> {
    let asset: Asset = serde_json::from_value(value.clone()).ok()?;
    let ok = is_uuid(&asset.id) && asset.version >= 0 && asset.sha256.len() == 64 && asset.sha256.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'));
    ok.then_some(asset)
}

fn parse_community_reward(value: &Value, discord_enabled: bool) -> Option<CommunityReward> {
    let cosmetic_id = value["cosmetic_id"].as_str().filter(|id| is_uuid(id))?.to_owned();
    let kind = value["kind"].as_str().filter(|k| SLOTS.contains(k))?.to_owned();
    let requirement = &value["requirement"];
    let kind_of = code(&requirement["type"])?;
    if kind_of.starts_with("discord_") && !discord_enabled {
        return None;
    }
    Some(CommunityReward {
        cosmetic_id,
        slug: text(&value["slug"], 80).unwrap_or_default(),
        kind,
        name: text(&value["name"], 120)?,
        description: text(&value["description"], 500),
        permanence: permanence_of(&kind_of, &value["permanence"]),
        requirement: Requirement { label: text(&requirement["label"], 120).unwrap_or_default(), description: text(&requirement["description"], 300), kind: kind_of },
        starts_at: text(&value["starts_at"], 40),
        ends_at: text(&value["ends_at"], 40),
        sort: value["sort"].as_i64().unwrap_or(0),
        asset: usable_asset(&value["asset"])?,
        animated: value["animated"].as_bool().unwrap_or(false),
        hd: value["hd"].as_bool().unwrap_or(false),
    })
}

/// The catalog as the Store sent it, checked. None when it isn't the contract's shape.
fn parse_catalog(body: &Value) -> Option<Catalog> {
    if !schema_one(body) {
        return None;
    }
    let enabled = body["enabled"].as_bool()?;
    let discord_enabled = enabled && body["discord_enabled"].as_bool().unwrap_or(false);
    let rewards = if enabled {
        body["rewards"].as_array()?.iter().filter_map(|r| parse_community_reward(r, discord_enabled)).take(MAX_REWARDS).collect()
    } else {
        Vec::new()
    };
    Some(Catalog { state: Avail::Ok, enabled, discord_enabled, rewards, fetched_at: Some(now_millis()) })
}

/// The public community catalog: asked for at most once a minute (sent with its ETag, so an unchanged one is a 304). While
/// the Store can't be reached, its last answer.
pub async fn catalog(state: &AppState) -> Catalog {
    let now = now_millis();
    let cached = state.store.community.catalog.lock().unwrap().clone();
    if let Some(cached) = cached.as_ref().filter(|c| now.saturating_sub(c.asked_at) < CATALOG_EVERY_MS) {
        return cached.catalog.clone();
    }
    let etag = cached.as_ref().filter(|c| c.catalog.state == Avail::Ok).and_then(|c| c.etag.clone());
    let answer = send(state, Method::GET, "/api/v1/catalog/community/", None, None, etag.as_deref()).await;
    let keep = |etag: Option<String>, catalog: Catalog| {
        *state.store.community.catalog.lock().unwrap() = Some(CatalogCache { etag, asked_at: now, catalog: catalog.clone() });
        catalog
    };
    let previous = || cached.as_ref().map(|c| c.catalog.clone()).filter(|c| c.state == Avail::Ok);
    match answer {
        Ok(raw) if raw.status == 304 && etag.is_some() => match previous() {
            Some(mut catalog) => {
                catalog.fetched_at = Some(now);
                keep(raw.etag.or(etag), catalog)
            }
            None => keep(None, Catalog::empty(Avail::Unavailable)),
        },
        Ok(raw) if (200..300).contains(&raw.status) => match parse_catalog(&raw.body) {
            Some(catalog) => keep(raw.etag, catalog),
            None => keep(None, Catalog::empty(Avail::Off)),
        },
        Ok(raw) if missing(&raw) => keep(None, Catalog::empty(Avail::Off)),
        // Offline, rate limited, unavailable: the last answer, asked again in a minute.
        _ => {
            let shown = previous().unwrap_or_else(|| Catalog::empty(Avail::Unavailable));
            keep(cached.and_then(|c| c.etag), shown)
        }
    }
}

/// Tests only: the next [`catalog`] asks the Store (as a minute later would).
#[cfg(test)]
pub(crate) fn age_catalog(state: &AppState) {
    if let Some(cached) = state.store.community.catalog.lock().unwrap().as_mut() {
        cached.asked_at = 0;
    }
}

fn listed_reward(state: &AppState, cosmetic_id: &str) -> CmdResult<CommunityReward> {
    let cached = state.store.community.catalog.lock().unwrap().clone();
    cached
        .and_then(|c| c.catalog.rewards.into_iter().find(|r| r.cosmetic_id == cosmetic_id))
        .ok_or_else(|| "This reward isn't in the Flint Store's community rewards.".to_owned())
}

/// Public art, the same for every account: kept apart from any account's Store copy.
fn community_dir(state: &AppState) -> PathBuf {
    state.paths.cache().join("store").join(state.store.endpoints.origin_key()).join("community").join("assets")
}

/// A listed reward's texture (a data URL), checked against the catalog's SHA-256: a locked cape can be looked at without
/// owning it.
pub async fn reward_asset(state: &AppState, cosmetic_id: &str) -> CmdResult<String> {
    let reward = listed_reward(state, cosmetic_id)?;
    let path = community_dir(state).join(format!("{}-v{}.png", reward.asset.id, reward.asset.version));
    verified_texture(state, &reward.asset, &path).await.map(|bytes| data_url(&bytes))
}

/// Strip `index` of a listed animated reward cape (PNG bytes), checked against the catalog's hash and size.
pub async fn reward_strip(state: &AppState, cosmetic_id: &str, index: usize) -> CmdResult<Vec<u8>> {
    let reward = listed_reward(state, cosmetic_id)?;
    let animation = reward.asset.animation.as_ref().ok_or("This item isn't animated.")?;
    anim::verified_strip(state, animation, index, &community_dir(state).join("strips")).await
}

// ---------- commands ----------

fn open_in_browser(app: &AppHandle, url: &Url) -> bool {
    app.opener().open_url(url.as_str(), None::<&str>).is_ok()
}

#[tauri::command]
pub async fn store_connections(state: State<'_, AppState>, uuid: String) -> CmdResult<ConnectionsView> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    Ok(connections(&state, &uuid).await)
}

/// Starts linking Discord and opens Discord's own page in the system browser (never in Flint).
#[tauri::command]
pub async fn store_discord_link_start(app: AppHandle, state: State<'_, AppState>, uuid: String) -> CmdResult<LinkStart> {
    let (uuid, account) = flint_account(&state, &uuid)?;
    if account.qa {
        return Err("The test identity has no Minecraft account, so it can't connect Discord.".into());
    }
    match start_link(&state, &uuid).await? {
        (LinkStart::Started { attempt_id, expires_at, .. }, Some(url)) => Ok(LinkStart::Started { attempt_id, expires_at, opened: open_in_browser(&app, &url) }),
        (start, _) => Ok(start),
    }
}

/// Opens Discord's page for the account's open attempt again.
#[tauri::command]
pub async fn store_discord_link_reopen(app: AppHandle, state: State<'_, AppState>, uuid: String, attempt_id: String) -> CmdResult<()> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    let url = reopen_url(&state, &uuid, &attempt_id)?;
    open_in_browser(&app, &url).then_some(()).ok_or_else(|| "Flint couldn't open your browser.".to_owned())
}

#[tauri::command]
pub async fn store_discord_link_attempt(state: State<'_, AppState>, uuid: String, attempt_id: String) -> CmdResult<AttemptView> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    attempt(&state, &uuid, &attempt_id).await
}

#[tauri::command]
pub async fn store_discord_unlink(state: State<'_, AppState>, uuid: String) -> CmdResult<ConnectionsView> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    unlink(&state, &uuid).await
}

#[tauri::command]
pub async fn store_rewards(state: State<'_, AppState>, uuid: String) -> CmdResult<RewardsView> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    Ok(rewards(&state, &uuid).await)
}

#[tauri::command]
pub async fn store_rewards_recheck(state: State<'_, AppState>, uuid: String) -> CmdResult<Recheck> {
    let (uuid, _) = flint_account(&state, &uuid)?;
    recheck(&state, &uuid).await
}

#[tauri::command]
pub async fn store_community_catalog(state: State<'_, AppState>) -> CmdResult<Catalog> {
    Ok(catalog(&state).await)
}

#[tauri::command]
pub async fn store_reward_asset(state: State<'_, AppState>, cosmetic_id: String) -> CmdResult<String> {
    reward_asset(&state, &cosmetic_id).await
}

#[tauri::command]
pub async fn store_reward_strip(state: State<'_, AppState>, cosmetic_id: String, index: usize) -> CmdResult<tauri::ipc::Response> {
    reward_strip(&state, &cosmetic_id, index).await.map(tauri::ipc::Response::new)
}

/// Opens a Discord invite (a reward's "Join the server", "Boost", "Finish joining") in the system browser, after
/// [`invite_url`] accepted it. Anything else is refused.
#[tauri::command]
pub fn store_open_discord_invite(app: AppHandle, url: String) -> CmdResult<()> {
    let url = invite_url(&url).ok_or("That isn't a Discord invite, so Flint didn't open it.")?;
    open_in_browser(&app, &url).then_some(()).ok_or_else(|| "Flint couldn't open your browser.".to_owned())
}
