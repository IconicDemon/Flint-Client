//! The game link: a small channel between Flint and the games it started, so the main menu's account card can list
//! Flint's Minecraft accounts, switch the running game to another one and add one, while every account and token stays
//! owned by Flint.
//!
//! - Loopback only: one listener on `127.0.0.1` at a port the system picks, started with the first game.
//! - Each launch gets its own random 256-bit secret, handed to that game alone in its environment
//!   ([`ENV_ADDRESS`], [`ENV_SECRET`]): readable by that process and the same OS user only, never on a command line, in a
//!   file or in a log. Flint keeps only its SHA-256 and forgets it when the game ends. A request without a live secret gets
//!   "unauthorized" and nothing else.
//! - The protocol: one connection per request, one JSON line each way (`{"v":1,"secret":…,"op":…}` → `{"ok":…}`),
//!   requests at most 16 KiB, read within 5 seconds. Nothing in it speaks HTTP, so a web page can't drive it either.
//! - Operations: `accounts` (the list: names and ids, no tokens), `session` (a fresh Minecraft session for one account,
//!   renewed as needed: the only answer with a token), `activate` (Flint's active account), `addAccount` / `cancelAdd`
//!   (Flint's own Microsoft sign-in, as the app's Add account does), `removeAccount` (signs an account out of Flint, as the
//!   app's account menu does; never the one the game plays as).
//! - Music Player (`media.rs`): `media` (what's playing on this computer as Windows' media controls show it: title, artist,
//!   app, position, length, which buttons work, the album art's version; `musicOnly` leaves browsers out), `mediaArt` (that
//!   art at the `size` the game draws it, 8 to 256 px square, as base64 RGBA), `mediaControl` (`action`: `toggle`, `next`,
//!   `previous` or `seek` to `positionMs`). Nothing in them is private to the player beyond what Windows itself shows.
//! - Friends (`social/game.rs`): `friends` (the Friends page's state for the account the game plays as, `playing`, only when
//!   that is Flint's active account, else `account`; trimmed for the game, with the chat it has open, `chat`) and
//!   `friendsAction` (`action`: the page's own actions, `{"kind": "send", …}`, run by the same code). The Friends session
//!   stays Flint's.
//! - Cosmetics (`store/game.rs`): `cosmetics` (the items the account the game plays as owns in the Flint Store and what it
//!   wears, from the Store session Flint has; none: an error saying to sign in to the Store in the app), `equip` (`slot`,
//!   `id` or none: what the app's Store page sends, after which every running game hears of it) and `cosmeticArt` (an owned
//!   item's picture, the PNG the Store hashed, as base64). The Store session stays Flint's.
//! - Add account: the game is in front, and on Windows a background process (Flint, minimised) can't bring a window
//!   forward, so a page Flint opened would stay behind the game (or only flash in the taskbar). A game that says it `opens`
//!   the page gets Microsoft's sign-in address back and opens it itself (as Minecraft opens a chat link); Flint still runs the
//!   loopback redirect and the token exchange. A game that doesn't say so has Flint open it, as before.
//! - Tokens are never logged here: requests and answers aren't printed, and errors carry messages, not payloads.
use crate::core::CmdResult;
use crate::media::{ArtReply, MediaAction, MediaReply};
use crate::social::game::GameAction;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};

/// The environment variables a game started by Flint finds the link in.
pub const ENV_ADDRESS: &str = "FLINT_LAUNCHER_LINK";
pub const ENV_SECRET: &str = "FLINT_LAUNCHER_LINK_SECRET";
/// The protocol version a request must name.
pub const PROTOCOL: u64 = 1;
const MAX_REQUEST: u64 = 16 * 1024;
const READ_TIMEOUT: Duration = Duration::from_secs(5);
/// A session can take a Microsoft refresh and the Xbox and Minecraft sign-in steps.
const WORK_TIMEOUT: Duration = Duration::from_secs(90);
/// What's playing and its buttons: Windows answers in milliseconds; the game asks again soon anyway.
const MEDIA_TIMEOUT: Duration = Duration::from_secs(4);
/// A Friends action may sign in first (Flint's server, Mojang, then the action itself).
const FRIENDS_TIMEOUT: Duration = Duration::from_secs(45);
/// The Store: an inventory, or an equip and the inventory after it, or a texture.
const STORE_TIMEOUT: Duration = Duration::from_secs(40);

/// One account as a game sees it: no token, no skin URL needed (the game asks Minecraft's own services for skins).
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LinkAccount {
    pub uuid: String,
    pub username: String,
    /// A test identity with no Microsoft sign-in (QA builds only): the game gets an offline session for it.
    pub offline: bool,
}

/// Where Flint's own sign-in started from a game stands.
#[derive(Serialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase", tag = "state")]
pub enum SignIn {
    #[default]
    Idle,
    /// Waiting for the browser: `url` is Microsoft's page for it (for the game to open again or copy).
    Running {
        #[serde(skip_serializing_if = "Option::is_none")]
        url: Option<String>,
    },
    Done { uuid: String, username: String },
    Failed { message: String },
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AccountsReply {
    pub active: Option<String>,
    pub accounts: Vec<LinkAccount>,
    pub sign_in: SignIn,
}

/// A Minecraft session for the game: what Minecraft's own `User` needs.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameSession {
    pub uuid: String,
    pub username: String,
    pub access_token: String,
    pub offline: bool,
}

/// The answer to `addAccount`: `started` false when a sign-in from a game was already waiting (its page is given again).
#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct AddReply {
    pub started: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
}

/// What the link asks of Flint. The app's implementation is [`AppBackend`]; tests use a fake.
pub trait Backend: Send + Sync + 'static {
    fn accounts(&self) -> AccountsReply;
    fn session(&self, uuid: String) -> impl std::future::Future<Output = CmdResult<GameSession>> + Send;
    fn activate(&self, uuid: &str) -> CmdResult<()>;
    /// Starts Flint's Microsoft sign-in (`opens`: the game opens the page itself, so Flint doesn't); not started when one from
    /// a game is already waiting.
    fn add_account(&self, opens: bool) -> impl std::future::Future<Output = CmdResult<AddReply>> + Send;
    fn cancel_add(&self);
    /// Signs `uuid` out of Flint; refused for `playing`, the account the asking game plays as.
    fn remove_account(&self, uuid: &str, playing: Option<&str>) -> CmdResult<()>;
    /// What's playing on this computer (`media.rs`); Err when Windows didn't answer this time.
    fn media(&self, music_only: bool) -> impl std::future::Future<Output = CmdResult<MediaReply>> + Send;
    /// The album art of what's playing, `size` px square.
    fn media_art(&self, size: u32) -> impl std::future::Future<Output = CmdResult<ArtReply>> + Send;
    /// Presses a button of what's playing.
    fn media_control(&self, action: MediaAction, position_ms: Option<u64>, music_only: bool)
        -> impl std::future::Future<Output = CmdResult<()>> + Send;
    /// Friends for a game playing as `playing` (32 lowercase hex), with its open chat; `account` when Flint's active account
    /// is another one.
    fn friends(&self, playing: &str, chat: Option<&str>) -> CmdResult<Value>;
    /// A Friends action for a game playing as `playing`.
    fn friends_action(&self, playing: String, action: GameAction) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// The Store items of the account `playing` and what it wears.
    fn cosmetics(&self, playing: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// Wears `id` in `slot` (none: empties it) for `playing`; answers the items after it.
    fn equip(&self, playing: String, slot: String, id: Option<String>) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// The picture of an item `playing` owns (`{"png": base64}`).
    fn cosmetic_art(&self, playing: String, id: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
}

#[derive(Deserialize, Debug, PartialEq)]
#[serde(tag = "op", rename_all = "camelCase")]
enum Op {
    Accounts,
    Session { uuid: String },
    Activate { uuid: String },
    AddAccount {
        /// The game opens Microsoft's page itself (it is in front); older games leave it to Flint.
        #[serde(default)]
        opens: bool,
    },
    CancelAdd,
    RemoveAccount {
        uuid: String,
        /// The account the game plays as, which can't be removed from it.
        #[serde(default)]
        playing: Option<String>,
    },
    Media {
        /// Music apps only (no browsers or video players).
        #[serde(default, rename = "musicOnly")]
        music_only: bool,
    },
    MediaArt {
        /// The size the game draws it at, px square (clamped to what's kept).
        #[serde(default = "art_size")]
        size: u32,
    },
    MediaControl {
        action: MediaAction,
        /// Where `seek` goes, in milliseconds.
        #[serde(default, rename = "positionMs")]
        position_ms: Option<u64>,
        #[serde(default, rename = "musicOnly")]
        music_only: bool,
    },
    Friends {
        /// The account the game plays as.
        playing: String,
        /// The friend whose chat the game has open.
        #[serde(default)]
        chat: Option<String>,
    },
    FriendsAction {
        playing: String,
        action: GameAction,
    },
    Cosmetics {
        playing: String,
    },
    Equip {
        playing: String,
        #[serde(default = "cape_slot")]
        slot: String,
        /// None (or null): the slot is emptied.
        #[serde(default)]
        id: Option<String>,
    },
    CosmeticArt {
        playing: String,
        id: String,
    },
}

fn art_size() -> u32 {
    64
}

fn cape_slot() -> String {
    "cape".into()
}

/// An account as a game names it, in Flint's form (32 lowercase hex); None for anything else.
fn account_id(text: &str) -> Option<String> {
    crate::store::canonical(text)
}

/// A call to Flint's Friends or Store with its time limit; running out answers `timeout`.
async fn limited(limit: Duration, work: impl std::future::Future<Output = CmdResult<Value>>) -> CmdResult<Value> {
    tokio::time::timeout(limit, work).await.unwrap_or_else(|_| Err("timeout".into()))
}

#[derive(Deserialize)]
struct Request {
    v: u64,
    secret: String,
    #[serde(flatten)]
    op: Op,
}

/// The secrets of the games running now, as SHA-256 digests with the profile each belongs to and the account the game is
/// signed in as: the one it was launched with, then any account its own switcher took a session for. Friends and Store
/// cosmetics answer a game only for that account, whichever account it names.
#[derive(Default)]
pub struct Registry {
    games: Mutex<Vec<Game>>,
}

struct Game {
    digest: [u8; 32],
    profile: String,
    /// 32 lowercase hex (Flint's form), or empty when the launch account had no usable id.
    account: String,
}

fn digest(secret: &str) -> [u8; 32] {
    Sha256::digest(secret.as_bytes()).into()
}

/// Equal in time that doesn't depend on where the first difference is.
fn same(a: &[u8; 32], b: &[u8; 32]) -> bool {
    a.iter().zip(b.iter()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

impl Registry {
    /// A new secret for a game of `profile` launched as `account` (32 random bytes, hex).
    pub fn register(&self, profile: &str, account: &str) -> String {
        let secret = crate::core::random_hex(32);
        let account = account_id(account).unwrap_or_default();
        self.games.lock().unwrap().push(Game { digest: digest(&secret), profile: profile.to_owned(), account });
        secret
    }

    pub fn unregister(&self, secret: &str) {
        let wanted = digest(secret);
        self.games.lock().unwrap().retain(|game| !same(&game.digest, &wanted));
    }

    /// Runs `f` on the live game a secret belongs to. Every entry is compared, whichever matches.
    fn with_game<T>(&self, secret: &str, f: impl FnOnce(&mut Game) -> T) -> Option<T> {
        if secret.len() != 64 {
            return None;
        }
        let wanted = digest(secret);
        let mut games = self.games.lock().unwrap();
        let mut found = None;
        for (i, game) in games.iter().enumerate() {
            if same(&game.digest, &wanted) {
                found = Some(i);
            }
        }
        found.map(|i| f(&mut games[i]))
    }

    /// The profile a live secret belongs to.
    pub fn check(&self, secret: &str) -> Option<String> {
        self.with_game(secret, |game| game.profile.clone())
    }

    /// The account the game with this secret is signed in as (32 lowercase hex), if known.
    fn account(&self, secret: &str) -> Option<String> {
        self.with_game(secret, |game| game.account.clone()).filter(|account| !account.is_empty())
    }

    /// The game's own switcher took a session for `account`: from now on it plays as that one.
    fn signed_in_as(&self, secret: &str, account: &str) {
        if let Some(account) = account_id(account) {
            self.with_game(secret, |game| game.account = account);
        }
    }
}

/// Flint's side of the link: the registry and, once the first game starts, the listener's address.
#[derive(Default)]
pub struct GameLink {
    pub registry: Arc<Registry>,
    address: tokio::sync::OnceCell<SocketAddr>,
    pub sign_in: Arc<Mutex<SignIn>>,
}

impl GameLink {
    /// The listener's address, starting it the first time (on `127.0.0.1`, a free port).
    pub async fn address<B: Backend>(&self, backend: impl FnOnce() -> B) -> CmdResult<SocketAddr> {
        let registry = self.registry.clone();
        self.address
            .get_or_try_init(|| async move {
                let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
                    .await
                    .map_err(|e| format!("Could not open the game link: {e}"))?;
                let address = listener.local_addr().map_err(|e| e.to_string())?;
                tauri::async_runtime::spawn(serve(listener, registry, Arc::new(backend())));
                Ok(address)
            })
            .await
            .copied()
    }
}

/// Answers connections until the app ends.
pub async fn serve<B: Backend>(listener: TcpListener, registry: Arc<Registry>, backend: Arc<B>) {
    loop {
        let Ok((stream, peer)) = listener.accept().await else { continue };
        if !peer.ip().is_loopback() {
            continue;
        }
        let (registry, backend) = (registry.clone(), backend.clone());
        tokio::spawn(async move {
            let _ = handle(stream, &registry, &*backend).await;
        });
    }
}

async fn handle<B: Backend>(stream: TcpStream, registry: &Registry, backend: &B) -> std::io::Result<()> {
    let (read, mut write) = stream.into_split();
    let mut line = String::new();
    let read = tokio::time::timeout(READ_TIMEOUT, BufReader::new(read.take(MAX_REQUEST)).read_line(&mut line)).await;
    let reply = match read {
        Ok(Ok(_)) if line.ends_with('\n') => answer(line.trim_end(), registry, backend).await,
        Ok(Ok(_)) => failure("too long"),
        _ => failure("timed out"),
    };
    let mut bytes = serde_json::to_vec(&reply).unwrap_or_default();
    bytes.push(b'\n');
    write.write_all(&bytes).await?;
    write.shutdown().await
}

fn failure(message: &str) -> Value {
    json!({ "ok": false, "error": message })
}

/// One request's answer: an unknown secret gets "unauthorized" (and a short pause) before anything else is looked at.
async fn answer<B: Backend>(line: &str, registry: &Registry, backend: &B) -> Value {
    let Ok(request) = serde_json::from_str::<Request>(line) else {
        return failure("bad request");
    };
    if registry.check(&request.secret).is_none() {
        tokio::time::sleep(Duration::from_millis(250)).await;
        return failure("unauthorized");
    }
    if request.v != PROTOCOL {
        return failure("unsupported version");
    }
    // Friends and Store cosmetics: only for the account this game is signed in as. Another account it names (one of Flint's
    // others, or anyone's) is answered `account` before Friends or the Store hear of it.
    let signed_in = registry.account(&request.secret);
    let own = |playing: &str| -> Option<String> { account_id(playing).filter(|p| signed_in.as_deref() == Some(p.as_str())) };
    let result: CmdResult<Value> = match request.op {
        Op::Accounts => Ok(serde_json::to_value(backend.accounts()).unwrap_or_default()),
        Op::Session { uuid } => match tokio::time::timeout(WORK_TIMEOUT, backend.session(uuid.clone())).await {
            Ok(Ok(session)) => {
                registry.signed_in_as(&request.secret, &uuid);
                Ok(serde_json::to_value(session).unwrap_or_default())
            }
            Ok(Err(error)) => Err(error),
            Err(_) => Err("Flint took too long to renew the sign-in. Try again.".into()),
        },
        Op::Activate { uuid } => backend.activate(&uuid).map(|_| json!({})),
        Op::AddAccount { opens } => backend.add_account(opens).await.map(|reply| serde_json::to_value(reply).unwrap_or_default()),
        Op::CancelAdd => {
            backend.cancel_add();
            Ok(json!({}))
        }
        Op::RemoveAccount { uuid, playing } => backend.remove_account(&uuid, playing.as_deref()).map(|_| json!({})),
        Op::Media { music_only } => match tokio::time::timeout(MEDIA_TIMEOUT, backend.media(music_only)).await {
            Ok(reply) => reply.map(|r| serde_json::to_value(r).unwrap_or_default()),
            Err(_) => Err("busy".into()),
        },
        Op::MediaArt { size } => match tokio::time::timeout(MEDIA_TIMEOUT, backend.media_art(size)).await {
            Ok(art) => art.map(|a| serde_json::to_value(a).unwrap_or_default()),
            Err(_) => Err("busy".into()),
        },
        Op::MediaControl { action, position_ms, music_only } => {
            if action == MediaAction::Seek && position_ms.is_none() {
                Err("bad request".into())
            } else {
                match tokio::time::timeout(MEDIA_TIMEOUT, backend.media_control(action, position_ms, music_only)).await {
                    Ok(done) => done.map(|_| json!({})),
                    Err(_) => Err("busy".into()),
                }
            }
        }
        Op::Friends { playing, chat } => match (account_id(&playing), chat.as_deref().map(account_id)) {
            (Some(_), None | Some(Some(_))) if own(&playing).is_none() => Err("account".into()),
            (Some(playing), None) => backend.friends(&playing, None),
            (Some(playing), Some(Some(chat))) => backend.friends(&playing, Some(&chat)),
            _ => Err("bad request".into()),
        },
        Op::FriendsAction { playing, action } => match (account_id(&playing), own(&playing)) {
            (Some(_), Some(playing)) => limited(FRIENDS_TIMEOUT, backend.friends_action(playing, action)).await,
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
        Op::Cosmetics { playing } => match (account_id(&playing), own(&playing)) {
            (Some(_), Some(playing)) => limited(STORE_TIMEOUT, backend.cosmetics(playing)).await,
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
        Op::Equip { playing, slot, id } => {
            let id_ok = id.as_deref().is_none_or(crate::store::is_uuid);
            match (account_id(&playing), own(&playing)) {
                (Some(_), _) if !id_ok || !crate::store::SLOTS.contains(&slot.as_str()) => Err("bad request".into()),
                (Some(_), Some(playing)) => limited(STORE_TIMEOUT, backend.equip(playing, slot, id)).await,
                (Some(_), None) => Err("account".into()),
                (None, _) => Err("bad request".into()),
            }
        }
        Op::CosmeticArt { playing, id } => match (account_id(&playing), own(&playing)) {
            (Some(_), _) if !crate::store::is_uuid(&id) => Err("bad request".into()),
            (Some(_), Some(playing)) => limited(STORE_TIMEOUT, backend.cosmetic_art(playing, id)).await,
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
    };
    match result {
        Ok(Value::Object(mut fields)) => {
            fields.insert("ok".into(), Value::Bool(true));
            Value::Object(fields)
        }
        Ok(_) => failure("internal error"),
        Err(message) => failure(&message),
    }
}

/// The app's backend: Flint's accounts file, its token store and its sign-in.
pub struct AppBackend {
    pub app: tauri::AppHandle,
}

impl AppBackend {
    fn state(&self) -> tauri::State<'_, crate::core::AppState> {
        tauri::Manager::state::<crate::core::AppState>(&self.app)
    }
}

/// Tells the app's window that the accounts changed (`added`: an account signed in from a game; `removed`: one a game signed
/// out).
pub fn accounts_changed(app: &tauri::AppHandle, added: Option<&str>, removed: Option<&str>) {
    let _ = tauri::Emitter::emit(app, "accounts-changed", json!({ "added": added, "removed": removed }));
}

/// The message for removing the account a game plays as.
pub const REMOVE_PLAYING: &str = "You're playing as this account. Switch to another one first, then remove it.";

impl Backend for AppBackend {
    fn accounts(&self) -> AccountsReply {
        let state = self.state();
        let file = crate::auth::load_accounts(&state);
        let sign_in = state.game_link.sign_in.lock().unwrap().clone();
        AccountsReply {
            active: file.active,
            accounts: file
                .accounts
                .into_iter()
                .map(|a| LinkAccount { uuid: a.uuid, username: a.username, offline: a.qa })
                .collect(),
            sign_in,
        }
    }

    fn session(&self, uuid: String) -> impl std::future::Future<Output = CmdResult<GameSession>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            crate::auth::game_session(&app, &state, &uuid).await
        }
    }

    fn activate(&self, uuid: &str) -> CmdResult<()> {
        crate::auth::set_active(&self.state(), uuid)?;
        accounts_changed(&self.app, None, None);
        Ok(())
    }

    fn add_account(&self, opens: bool) -> impl std::future::Future<Output = CmdResult<AddReply>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let status = state.game_link.sign_in.clone();
            {
                let mut now = status.lock().unwrap();
                if let SignIn::Running { url } = &*now {
                    return Ok(AddReply { started: false, url: url.clone() });
                }
                *now = SignIn::Running { url: None };
            }
            let pending = match crate::auth::begin_sign_in(&state).await {
                Ok(pending) => pending,
                Err(message) => {
                    *status.lock().unwrap() = SignIn::Failed { message: message.clone() };
                    return Err(message);
                }
            };
            let url = pending.url.clone();
            *status.lock().unwrap() = SignIn::Running { url: Some(url.clone()) };
            if !opens {
                use tauri_plugin_opener::OpenerExt;
                if let Err(e) = app.opener().open_url(url.as_str(), None::<&str>) {
                    crate::auth::cancel_sign_in(&state);
                    let message = format!("Could not open your browser: {e}");
                    *status.lock().unwrap() = SignIn::Failed { message: message.clone() };
                    return Err(message);
                }
            }
            let task = app.clone();
            tauri::async_runtime::spawn(async move {
                let state = tauri::Manager::state::<crate::core::AppState>(&task);
                // Flint's active account stays the game's: the game switches to the new account itself, then says so.
                let result = crate::auth::finish_sign_in(&task, &state, pending, false).await;
                *status.lock().unwrap() = match &result {
                    Ok(account) => SignIn::Done { uuid: account.uuid.clone(), username: account.username.clone() },
                    Err(message) => SignIn::Failed { message: message.clone() },
                };
                if let Ok(account) = &result {
                    accounts_changed(&task, Some(&account.username), None);
                }
            });
            Ok(AddReply { started: true, url: Some(url) })
        }
    }

    fn cancel_add(&self) {
        crate::auth::cancel_sign_in(&self.state());
    }

    fn remove_account(&self, uuid: &str, playing: Option<&str>) -> CmdResult<()> {
        if playing == Some(uuid) {
            return Err(REMOVE_PLAYING.into());
        }
        let state = self.state();
        let name = crate::auth::load_accounts(&state).accounts.into_iter().find(|a| a.uuid == uuid).map(|a| a.username);
        if crate::auth::remove_account(&state, uuid)? {
            accounts_changed(&self.app, None, name.as_deref());
        }
        Ok(())
    }

    // Not `async fn`: these futures must not borrow `self`, so they stay `Send` whatever the link holds.
    #[allow(clippy::manual_async_fn)]
    fn media(&self, music_only: bool) -> impl std::future::Future<Output = CmdResult<MediaReply>> + Send {
        async move { blocking(move || crate::media::read(music_only)).await }
    }

    #[allow(clippy::manual_async_fn)]
    fn media_art(&self, size: u32) -> impl std::future::Future<Output = CmdResult<ArtReply>> + Send {
        async move { blocking(move || crate::media::art(size)).await }
    }

    #[allow(clippy::manual_async_fn)]
    fn media_control(&self, action: MediaAction, position_ms: Option<u64>, music_only: bool)
        -> impl std::future::Future<Output = CmdResult<()>> + Send {
        async move { blocking(move || crate::media::control(action, position_ms, music_only)).await }
    }

    fn friends(&self, playing: &str, chat: Option<&str>) -> CmdResult<Value> {
        crate::social::game::app_view(&self.app, playing, chat)
    }

    #[allow(clippy::manual_async_fn)]
    fn friends_action(&self, playing: String, action: GameAction) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move { crate::social::game::app_act(&app, &playing, action).await }
    }

    #[allow(clippy::manual_async_fn)]
    fn cosmetics(&self, playing: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let uuid = crate::store::game::game_account(&state, &playing)?;
            to_value(crate::store::game::cosmetics(&state, &uuid).await?)
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn equip(&self, playing: String, slot: String, id: Option<String>) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let uuid = crate::store::game::game_account(&state, &playing)?;
            to_value(crate::store::game::wear(&state, &uuid, &slot, id.as_deref()).await?)
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn cosmetic_art(&self, playing: String, id: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let uuid = crate::store::game::game_account(&state, &playing)?;
            Ok(json!({ "png": crate::store::game::art(&state, &uuid, &id).await? }))
        }
    }
}

fn to_value(value: impl Serialize) -> CmdResult<Value> {
    serde_json::to_value(value).map_err(|_| "internal error".to_owned())
}

/// Runs a blocking call to Windows on the runtime's blocking threads.
async fn blocking<T: Send + 'static>(work: impl FnOnce() -> CmdResult<T> + Send + 'static) -> CmdResult<T> {
    tokio::task::spawn_blocking(work).await.unwrap_or_else(|_| Err("internal error".into()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[derive(Default)]
    struct Fake {
        active: Mutex<Option<String>>,
        sessions: AtomicUsize,
        adds: AtomicUsize,
        opened_by_game: AtomicUsize,
        removed: Mutex<Vec<String>>,
        /// The buttons pressed, and whether the last media read was for music apps only.
        pressed: Mutex<Vec<(MediaAction, Option<u64>)>>,
        music_only: std::sync::atomic::AtomicBool,
        /// What Friends and the Store were asked: (playing, what).
        social: Mutex<Vec<(String, String)>>,
        actions: Mutex<Vec<GameAction>>,
        store: Mutex<Vec<(String, String)>>,
    }

    /// The account the fake's games play as (Flint's active one), and another of Flint's.
    const PLAYING: &str = "8667ba71b85a4004af54457a9734eed7";
    const PLAYING_DASHED: &str = "8667ba71-b85a-4004-af54-457a9734eed7";
    const FRIEND: &str = "ec561538f3fd461daff5086b22154bce";
    const CAPE: &str = "00000000-0000-4000-8000-000000000001";

    impl Backend for Fake {
        fn accounts(&self) -> AccountsReply {
            AccountsReply {
                active: self.active.lock().unwrap().clone(),
                accounts: vec![
                    LinkAccount { uuid: "a".into(), username: "Alpha".into(), offline: false },
                    LinkAccount { uuid: "b".into(), username: "Beta".into(), offline: true },
                ],
                sign_in: SignIn::Idle,
            }
        }
        fn session(&self, uuid: String) -> impl std::future::Future<Output = CmdResult<GameSession>> + Send {
            self.sessions.fetch_add(1, Ordering::SeqCst);
            async move {
                match uuid.as_str() {
                    "a" => Ok(GameSession { uuid, username: "Alpha".into(), access_token: "token-a".into(), offline: false }),
                    FRIEND => Ok(GameSession { uuid, username: "Alex".into(), access_token: "token-alex".into(), offline: false }),
                    _ => Err("This account's sign-in expired. Please sign in again.".into()),
                }
            }
        }
        fn activate(&self, uuid: &str) -> CmdResult<()> {
            if uuid != "a" && uuid != "b" {
                return Err("That account isn't signed in.".into());
            }
            *self.active.lock().unwrap() = Some(uuid.to_owned());
            Ok(())
        }
        fn add_account(&self, opens: bool) -> impl std::future::Future<Output = CmdResult<AddReply>> + Send {
            if opens {
                self.opened_by_game.fetch_add(1, Ordering::SeqCst);
            }
            let started = self.adds.fetch_add(1, Ordering::SeqCst) == 0;
            async move { Ok(AddReply { started, url: Some("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?x".into()) }) }
        }
        fn cancel_add(&self) {}
        fn remove_account(&self, uuid: &str, playing: Option<&str>) -> CmdResult<()> {
            if playing == Some(uuid) {
                return Err(REMOVE_PLAYING.into());
            }
            self.removed.lock().unwrap().push(uuid.to_owned());
            Ok(())
        }
        fn media(&self, music_only: bool) -> impl std::future::Future<Output = CmdResult<MediaReply>> + Send {
            self.music_only.store(music_only, Ordering::SeqCst);
            async move {
                Ok(MediaReply {
                    supported: true,
                    active: true,
                    playing: true,
                    title: "Midnight City".into(),
                    artist: "M83".into(),
                    album: "Hurry Up, We're Dreaming".into(),
                    app: "Spotify".into(),
                    position_ms: 96_000,
                    duration_ms: 243_000,
                    art_version: 7,
                    can_previous: true,
                    can_next: true,
                    can_toggle: true,
                    can_seek: true,
                    ..Default::default()
                })
            }
        }
        async fn media_art(&self, size: u32) -> CmdResult<ArtReply> {
            let mut cache = crate::media::ArtCache::new();
            cache.poll("track");
            cache.read(Some(vec![200; (crate::media::ART_SOURCE * crate::media::ART_SOURCE * 4) as usize]));
            cache.art(size).ok_or_else(|| "No album art".to_owned())
        }
        fn media_control(&self, action: MediaAction, position_ms: Option<u64>, _music_only: bool)
            -> impl std::future::Future<Output = CmdResult<()>> + Send {
            self.pressed.lock().unwrap().push((action, position_ms));
            async move { if action == MediaAction::Previous { Err("The app didn't take it".into()) } else { Ok(()) } }
        }
        fn friends(&self, playing: &str, chat: Option<&str>) -> CmdResult<Value> {
            self.social.lock().unwrap().push((playing.to_owned(), chat.unwrap_or("-").to_owned()));
            if playing != PLAYING {
                return Err("account".into());
            }
            Ok(json!({ "phase": "ready", "friends": [{ "uuid": FRIEND, "name": "Alex", "status": "online", "unread": 2 }] }))
        }
        fn friends_action(&self, playing: String, action: GameAction) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
            self.actions.lock().unwrap().push(action.clone());
            async move {
                if playing != PLAYING {
                    return Err("account".into());
                }
                match action {
                    GameAction::Send { body, .. } => Ok(json!({ "message": { "id": 7, "body": body } })),
                    GameAction::Request { name } if name == "nobody" => Err("not_on_flint".into()),
                    _ => Ok(json!({})),
                }
            }
        }
        async fn cosmetics(&self, playing: String) -> CmdResult<Value> {
            self.store.lock().unwrap().push((playing, "cosmetics".into()));
            Ok(json!({ "items": [{ "id": CAPE, "kind": "cape", "name": "Ember" }], "equipped": {} }))
        }
        async fn equip(&self, playing: String, slot: String, id: Option<String>) -> CmdResult<Value> {
            self.store.lock().unwrap().push((playing, format!("equip {slot} {}", id.as_deref().unwrap_or("none"))));
            Ok(json!({ "items": [], "equipped": {} }))
        }
        async fn cosmetic_art(&self, playing: String, id: String) -> CmdResult<Value> {
            self.store.lock().unwrap().push((playing, format!("art {id}")));
            Ok(json!({ "png": "iVBORw0KGgo=" }))
        }
    }

    async fn link() -> (SocketAddr, Arc<Registry>, Arc<Fake>) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let address = listener.local_addr().unwrap();
        let registry = Arc::new(Registry::default());
        let fake = Arc::new(Fake::default());
        tokio::spawn(serve(listener, registry.clone(), fake.clone()));
        (address, registry, fake)
    }

    async fn send(address: SocketAddr, raw: &str) -> Value {
        let mut stream = TcpStream::connect(address).await.unwrap();
        stream.write_all(raw.as_bytes()).await.unwrap();
        let mut reply = String::new();
        stream.read_to_string(&mut reply).await.unwrap();
        serde_json::from_str(reply.trim_end()).unwrap()
    }

    async fn ask(address: SocketAddr, secret: &str, op: Value) -> Value {
        let mut request = json!({ "v": PROTOCOL, "secret": secret });
        request.as_object_mut().unwrap().extend(op.as_object().unwrap().clone());
        send(address, &format!("{request}\n")).await
    }

    #[tokio::test]
    async fn only_a_live_secret_gets_an_answer() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("profile-1", PLAYING);
        assert_eq!(secret.len(), 64);
        let wrong = "0".repeat(64);
        assert_eq!(ask(address, &wrong, json!({ "op": "accounts" })).await, json!({ "ok": false, "error": "unauthorized" }));
        assert_eq!(ask(address, "short", json!({ "op": "session", "uuid": "a" })).await["error"], "unauthorized");
        assert_eq!(fake.sessions.load(Ordering::SeqCst), 0, "nothing runs for an unknown secret");
        let listed = ask(address, &secret, json!({ "op": "accounts" })).await;
        assert_eq!(listed["ok"], true);
        assert_eq!(listed["accounts"][1]["username"], "Beta");
        // A game that ended loses its link.
        registry.unregister(&secret);
        assert_eq!(ask(address, &secret, json!({ "op": "accounts" })).await["error"], "unauthorized");
    }

    #[tokio::test]
    async fn the_account_list_never_carries_a_token() {
        let (address, registry, _fake) = link().await;
        let secret = registry.register("p", PLAYING);
        let listed = ask(address, &secret, json!({ "op": "accounts" })).await.to_string();
        assert!(!listed.to_lowercase().contains("token"), "{listed}");
        assert!(listed.contains("\"signIn\":{\"state\":\"idle\"}"), "{listed}");
    }

    #[tokio::test]
    async fn sessions_and_their_failures_come_back_as_they_are() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        let session = ask(address, &secret, json!({ "op": "session", "uuid": "a" })).await;
        assert_eq!(session, json!({ "ok": true, "uuid": "a", "username": "Alpha", "accessToken": "token-a", "offline": false }));
        let expired = ask(address, &secret, json!({ "op": "session", "uuid": "b" })).await;
        assert_eq!(expired["ok"], false);
        assert!(expired["error"].as_str().unwrap().contains("expired"));
        assert_eq!(fake.sessions.load(Ordering::SeqCst), 2);
    }

    #[tokio::test]
    async fn activating_and_adding_reach_the_backend() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        assert_eq!(ask(address, &secret, json!({ "op": "activate", "uuid": "b" })).await, json!({ "ok": true }));
        assert_eq!(fake.active.lock().unwrap().as_deref(), Some("b"));
        assert_eq!(ask(address, &secret, json!({ "op": "activate", "uuid": "zz" })).await["ok"], false);
        let page = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?x";
        assert_eq!(ask(address, &secret, json!({ "op": "addAccount" })).await, json!({ "ok": true, "started": true, "url": page }));
        assert_eq!(fake.opened_by_game.load(Ordering::SeqCst), 0, "an older game leaves the page to Flint");
        assert_eq!(
            ask(address, &secret, json!({ "op": "addAccount", "opens": true })).await,
            json!({ "ok": true, "started": false, "url": page }),
            "a sign-in already waiting: its page again"
        );
        assert_eq!(fake.opened_by_game.load(Ordering::SeqCst), 1);
        assert_eq!(ask(address, &secret, json!({ "op": "cancelAdd" })).await, json!({ "ok": true }));
    }

    #[tokio::test]
    async fn a_game_removes_accounts_but_never_the_one_it_plays_as() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        assert_eq!(ask(address, &secret, json!({ "op": "removeAccount", "uuid": "b", "playing": "a" })).await, json!({ "ok": true }));
        let refused = ask(address, &secret, json!({ "op": "removeAccount", "uuid": "a", "playing": "a" })).await;
        assert_eq!(refused["ok"], false);
        assert_eq!(refused["error"], REMOVE_PLAYING);
        assert_eq!(*fake.removed.lock().unwrap(), vec!["b".to_owned()]);
        assert_eq!(ask(address, &secret, json!({ "op": "removeAccount" })).await["error"], "bad request", "an account is named");
    }

    #[tokio::test]
    async fn the_music_player_reads_what_is_playing_and_presses_its_buttons() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        // Nothing runs for a stranger.
        assert_eq!(ask(address, &"0".repeat(64), json!({ "op": "media" })).await["error"], "unauthorized");
        let playing = ask(address, &secret, json!({ "op": "media" })).await;
        assert_eq!(playing["ok"], true);
        assert_eq!(playing["title"], "Midnight City");
        assert_eq!(playing["app"], "Spotify");
        assert_eq!(playing["positionMs"], 96_000);
        assert_eq!(playing["durationMs"], 243_000);
        assert_eq!(playing["artVersion"], 7);
        assert_eq!(playing["canSeek"], true);
        assert!(!fake.music_only.load(Ordering::SeqCst));
        ask(address, &secret, json!({ "op": "media", "musicOnly": true })).await;
        assert!(fake.music_only.load(Ordering::SeqCst), "music apps only reaches the backend");

        let art = ask(address, &secret, json!({ "op": "mediaArt", "size": 32 })).await;
        assert_eq!((art["ok"].clone(), art["width"].clone(), art["height"].clone()), (json!(true), json!(32), json!(32)));
        use base64::Engine;
        let rgba = base64::engine::general_purpose::STANDARD.decode(art["rgba"].as_str().unwrap()).unwrap();
        assert_eq!(rgba.len(), 32 * 32 * 4);
        assert_eq!(ask(address, &secret, json!({ "op": "mediaArt", "size": 4096 })).await["width"], crate::media::ART_SOURCE, "never above what's kept");
        assert_eq!(ask(address, &secret, json!({ "op": "mediaArt" })).await["width"], 64, "a game that names no size gets 64");

        assert_eq!(ask(address, &secret, json!({ "op": "mediaControl", "action": "toggle" })).await, json!({ "ok": true }));
        assert_eq!(ask(address, &secret, json!({ "op": "mediaControl", "action": "next" })).await, json!({ "ok": true }));
        assert_eq!(ask(address, &secret, json!({ "op": "mediaControl", "action": "seek", "positionMs": 30_000 })).await, json!({ "ok": true }));
        let refused = ask(address, &secret, json!({ "op": "mediaControl", "action": "previous" })).await;
        assert_eq!(refused, json!({ "ok": false, "error": "The app didn't take it" }));
        assert_eq!(ask(address, &secret, json!({ "op": "mediaControl", "action": "seek" })).await["error"], "bad request", "a seek names where");
        assert_eq!(ask(address, &secret, json!({ "op": "mediaControl", "action": "eject" })).await["error"], "bad request");
        assert_eq!(
            *fake.pressed.lock().unwrap(),
            vec![(MediaAction::Toggle, None), (MediaAction::Next, None), (MediaAction::Seek, Some(30_000)), (MediaAction::Previous, None)]
        );
    }

    #[tokio::test]
    async fn friends_answer_for_the_account_the_game_plays_as() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        assert_eq!(ask(address, &"0".repeat(64), json!({ "op": "friends", "playing": PLAYING })).await["error"], "unauthorized");
        let shown = ask(address, &secret, json!({ "op": "friends", "playing": PLAYING_DASHED, "chat": FRIEND })).await;
        assert_eq!(shown["ok"], true);
        assert_eq!(shown["friends"][0]["name"], "Alex");
        // Another account than the one this game is signed in as is told so, before Friends hears of it.
        assert_eq!(ask(address, &secret, json!({ "op": "friends", "playing": FRIEND })).await, json!({ "ok": false, "error": "account" }));
        // Ids are checked here, before Friends hears of them.
        for bad in [json!({ "op": "friends" }), json!({ "op": "friends", "playing": "Steve" }), json!({ "op": "friends", "playing": PLAYING, "chat": "../x" })] {
            assert_eq!(ask(address, &secret, bad).await["error"], "bad request");
        }
        assert_eq!(*fake.social.lock().unwrap(), vec![(PLAYING.to_owned(), FRIEND.to_owned())]);
    }

    #[tokio::test]
    async fn a_game_reaches_friends_and_cosmetics_only_as_the_account_it_is_signed_in_as() {
        let (address, registry, fake) = link().await;
        let game = registry.register("p", PLAYING);
        let other_game = registry.register("q", FRIEND);
        // Naming another of Flint's accounts (or anyone's) gets nothing from Friends or the Store.
        for op in [
            json!({ "op": "friends", "playing": FRIEND }),
            json!({ "op": "friendsAction", "playing": FRIEND, "action": { "kind": "settings", "status": "busy" } }),
            json!({ "op": "cosmetics", "playing": FRIEND }),
            json!({ "op": "equip", "playing": FRIEND, "id": CAPE }),
            json!({ "op": "cosmeticArt", "playing": FRIEND, "id": CAPE }),
        ] {
            assert_eq!(ask(address, &game, op).await, json!({ "ok": false, "error": "account" }));
        }
        assert!(fake.social.lock().unwrap().is_empty() && fake.actions.lock().unwrap().is_empty() && fake.store.lock().unwrap().is_empty());
        // Each game has its own account: the other game reaches the Store as FRIEND, not as PLAYING.
        assert_eq!(ask(address, &other_game, json!({ "op": "cosmetics", "playing": PLAYING })).await["error"], "account");
        assert_eq!(ask(address, &other_game, json!({ "op": "cosmetics", "playing": FRIEND })).await["ok"], true);
        // A failed switch changes nothing; the game's own switcher taking FRIEND's session makes it play as FRIEND.
        assert!(ask(address, &game, json!({ "op": "session", "uuid": "b" })).await["error"].is_string());
        assert_eq!(ask(address, &game, json!({ "op": "cosmetics", "playing": PLAYING })).await["ok"], true);
        assert_eq!(ask(address, &game, json!({ "op": "session", "uuid": FRIEND })).await["ok"], true);
        assert_eq!(ask(address, &game, json!({ "op": "cosmetics", "playing": PLAYING })).await["error"], "account");
        assert_eq!(ask(address, &game, json!({ "op": "equip", "playing": FRIEND, "id": CAPE })).await["ok"], true);
        assert_eq!(
            *fake.store.lock().unwrap(),
            vec![
                (FRIEND.to_owned(), "cosmetics".to_owned()),
                (PLAYING.to_owned(), "cosmetics".to_owned()),
                (FRIEND.to_owned(), format!("equip cape {CAPE}")),
            ]
        );
    }

    #[tokio::test]
    async fn friends_actions_are_the_pages_own_and_checked_on_the_way_in() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        let sent = ask(
            address,
            &secret,
            json!({ "op": "friendsAction", "playing": PLAYING, "action": { "kind": "send", "to": FRIEND, "body": "gg", "sensitive": false, "clientId": "0c8f8c2e-6b0e-4d0e-9f43-0f6f3d8f6a11" } }),
        )
        .await;
        assert_eq!(sent, json!({ "ok": true, "message": { "id": 7, "body": "gg" } }));
        let refused = ask(address, &secret, json!({ "op": "friendsAction", "playing": PLAYING, "action": { "kind": "request", "name": "nobody" } })).await;
        assert_eq!(refused, json!({ "ok": false, "error": "not_on_flint" }));
        assert_eq!(
            ask(address, &secret, json!({ "op": "friendsAction", "playing": PLAYING, "action": { "kind": "respond", "id": 12, "accept": true } })).await,
            json!({ "ok": true })
        );
        assert_eq!(
            ask(address, &secret, json!({ "op": "friendsAction", "playing": PLAYING, "action": { "kind": "settings", "status": "busy" } })).await["ok"],
            true
        );
        for bad in [
            json!({ "op": "friendsAction", "playing": PLAYING, "action": { "kind": "deleteEverything" } }),
            json!({ "op": "friendsAction", "playing": PLAYING, "action": { "kind": "send", "to": FRIEND } }),
            json!({ "op": "friendsAction", "playing": PLAYING, "action": "send" }),
            json!({ "op": "friendsAction", "action": { "kind": "connect" } }),
            json!({ "op": "friendsAction", "playing": "x", "action": { "kind": "connect" } }),
        ] {
            assert_eq!(ask(address, &secret, bad).await["error"], "bad request");
        }
        let actions = fake.actions.lock().unwrap();
        assert_eq!(actions.len(), 4, "only well-formed actions reach Friends");
        assert_eq!(
            actions[0],
            GameAction::Send { to: FRIEND.into(), body: "gg".into(), sensitive: false, client_id: Some("0c8f8c2e-6b0e-4d0e-9f43-0f6f3d8f6a11".into()) }
        );
        assert_eq!(actions[3], GameAction::Settings { status: Some("busy".into()), allow_requests: None });
    }

    #[tokio::test]
    async fn cosmetics_are_listed_worn_and_pictured_for_the_game_s_account() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        let listed = ask(address, &secret, json!({ "op": "cosmetics", "playing": PLAYING_DASHED })).await;
        assert_eq!(listed["items"][0]["name"], "Ember");
        assert_eq!(ask(address, &secret, json!({ "op": "equip", "playing": PLAYING, "id": CAPE })).await["ok"], true);
        assert_eq!(ask(address, &secret, json!({ "op": "equip", "playing": PLAYING, "slot": "cape", "id": null })).await["ok"], true);
        assert_eq!(ask(address, &secret, json!({ "op": "cosmeticArt", "playing": PLAYING, "id": CAPE })).await["png"], "iVBORw0KGgo=");
        for bad in [
            json!({ "op": "equip", "playing": PLAYING, "slot": "boots", "id": CAPE }),
            json!({ "op": "equip", "playing": PLAYING, "id": "../../etc" }),
            json!({ "op": "cosmeticArt", "playing": PLAYING, "id": "x" }),
            json!({ "op": "cosmetics", "playing": "" }),
        ] {
            assert_eq!(ask(address, &secret, bad).await["error"], "bad request");
        }
        assert_eq!(
            *fake.store.lock().unwrap(),
            vec![
                (PLAYING.to_owned(), "cosmetics".to_owned()),
                (PLAYING.to_owned(), format!("equip cape {CAPE}")),
                (PLAYING.to_owned(), "equip cape none".to_owned()),
                (PLAYING.to_owned(), format!("art {CAPE}")),
            ]
        );
    }

    #[tokio::test]
    async fn malformed_requests_are_refused() {
        let (address, registry, _fake) = link().await;
        let secret = registry.register("p", PLAYING);
        // A browser's request isn't a request here.
        assert_eq!(send(address, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").await["error"], "bad request");
        assert_eq!(ask(address, &secret, json!({ "op": "deleteEverything" })).await["error"], "bad request");
        let old = format!("{}\n", json!({ "v": 99, "secret": secret, "op": "accounts" }));
        assert_eq!(send(address, &old).await["error"], "unsupported version");
        // 16 KiB without a line end.
        let mut stream = TcpStream::connect(address).await.unwrap();
        stream.write_all(&vec![b'x'; MAX_REQUEST as usize]).await.unwrap();
        let mut reply = String::new();
        stream.read_to_string(&mut reply).await.unwrap();
        assert!(reply.contains("too long"), "{reply}");
    }

    #[tokio::test]
    async fn a_silent_connection_times_out() {
        let (address, _registry, _fake) = link().await;
        let mut stream = TcpStream::connect(address).await.unwrap();
        let started = std::time::Instant::now();
        let mut reply = String::new();
        stream.read_to_string(&mut reply).await.unwrap();
        assert!(reply.contains("timed out"));
        assert!(started.elapsed() >= READ_TIMEOUT - Duration::from_millis(100));
    }

    #[test]
    fn secrets_are_compared_whole() {
        let registry = Registry::default();
        let one = registry.register("one", PLAYING);
        let two = registry.register("two", PLAYING);
        assert_ne!(one, two);
        assert_eq!(registry.check(&one).as_deref(), Some("one"));
        assert_eq!(registry.check(&two).as_deref(), Some("two"));
        let mut near = one.clone();
        near.replace_range(63..64, if one.ends_with('0') { "1" } else { "0" });
        assert_eq!(registry.check(&near), None);
        assert_eq!(registry.check(&one[..63]), None);
    }

    #[test]
    fn sign_in_states_serialise_for_the_game() {
        assert_eq!(serde_json::to_value(SignIn::Running { url: None }).unwrap(), json!({ "state": "running" }));
        assert_eq!(serde_json::to_value(SignIn::Running { url: Some("u".into()) }).unwrap(), json!({ "state": "running", "url": "u" }));
        assert_eq!(
            serde_json::to_value(SignIn::Done { uuid: "u".into(), username: "N".into() }).unwrap(),
            json!({ "state": "done", "uuid": "u", "username": "N" })
        );
        assert_eq!(serde_json::to_value(SignIn::Failed { message: "m".into() }).unwrap(), json!({ "state": "failed", "message": "m" }));
    }
}
