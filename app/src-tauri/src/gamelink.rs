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
//!   stays Flint's. Every `friends` answer has the state's `revision`; a game that sends it back as `since`, with `waitMs`
//!   (at most 25 s), is answered as soon as the state differs, so a message pushed to Flint reaches the game at once. At
//!   most [`MAX_WAITS`] wait at a time (more are answered at once), and one whose game hung up is let go.
//! - Cosmetics (`store/game.rs`): `cosmetics` (the items the account the game plays as owns in the Flint Store and what it
//!   wears, from the Store session Flint has; none: an error saying to sign in to the Store in the app), `equip` (`slot`,
//!   `id` or none: what the app's Store page sends, after which every running game hears of it) and `cosmeticArt` (an owned
//!   item's picture, the PNG the Store hashed, as base64). The Store session stays Flint's.
//! - 3D cosmetics' files: `modelFile` (`id`, `part`: geometry, texture, animation or thumbnail), the file of an item the
//!   account owns or one the Store lists (another player's emote), as the bytes Flint checked against the Store's SHA-256 and
//!   size (base64). The game never learns an address or a hash.
//! - Emote room (`social/emote_room.rs`): `emoteRoom` (`server`: the address the game joined after Minecraft's SRV lookup,
//!   or none when it left; singleplayer and LAN are never sent a room), `emoteSend` (`kind` `emote` with `cosmeticId`, or
//!   `stop`) and `emoteEvents` (what happened after `since`, waiting up to `waitMs`, at most 25 s): other players' emotes and
//!   stops with how long ago they started, and this game's refused emotes. Only for the account the game plays as, with its
//!   Friends session.
//! - Partners (`partners/game.rs`): `partners` (the Library's partner directory as the in-game Discover page shows it,
//!   checked as the launcher's page checks it; `version`: the game's Minecraft version, for which servers it can join),
//!   `partnerArt` (a listed picture by checksum, from the launcher's checked cache; a WebP as a PNG) and `partnerJoin` (a
//!   listing's server checked as the launcher's Play checks it, after DNS; the game joins it itself). The feed is public: no
//!   account is named.
//! - Community (`store/community_game.rs`): `community` (the Flint Discord's rewards, the account's linked Discord and its
//!   standing, and the invite to open, for the account the game plays as), `rewardArt` (a listed reward's picture, public)
//!   and `discordLink` (starts linking Discord with the account's Store session and answers Discord's page, checked, for the
//!   game to open: it is in front). The Store session stays Flint's.
//! - Showcase (`showcase/game.rs`): `showcase` (the slides the Store's admin panel runs on the in-game main menu, open now,
//!   checked; none when the Store has no showcase) and `showcaseArt` (a listed slide's picture by its id, downloaded without
//!   redirects; a WebP as a PNG). Public: no account is named.
//! - Library (`content/ingame.rs`): `libraryState` (the game's own profile: how many mods, resource packs and shader packs it
//!   has), `librarySearch` (`source` modrinth|curseforge, `kind` mod|resourcepack|shader, `query`, `offset`, `sort`: only
//!   content that fits the profile, each hit marked with what the profile has), `libraryPlan` and `libraryInstall` (`kind`,
//!   `id`: what the Library page would plan and install, with its libraries; packs aren't turned on, a mod loads at the next
//!   start), `libraryRemove` (`kind`, `file`; a loaded mod goes at the next start, `keep` takes that back) and `libraryArt`
//!   (a hit's icon by its address, from Modrinth's or CurseForge's picture host only). Always the profile the game was
//!   launched for: a game can't name another.
//! - Theme: `theme` (the app's theme, Settings › Appearance: `ember`, `icecream`, `moss`, `bloodstone`, `dusk` or `aurora`), which a game
//!   asks every few seconds while one of Flint's menus is open so a change in the app shows there at once, and `setTheme`
//!   (`theme`: one of those), a theme the player picked on the in-game Theme page, saved as the app's and shown in its window
//!   at once (`theme-changed`). One theme everywhere.
//! - Skin changer (`skin_library.rs`): `skinsList` (the skins added for the account the game plays as), `skinAdd`
//!   (`username` or `url`: added to that list, never applied), `skinArt` (a listed skin's PNG, base64), `skinApply` (`id`,
//!   `variant` classic or slim: uploads it with Minecraft's skin endpoint, as the launcher's Apply does) and `skinRemove`.
//!   Only for the account the game plays as; the sign-in stays Flint's.
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
use std::sync::atomic::{AtomicUsize, Ordering};
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
/// `friends` requests waiting for a change at once, across games (a game has one).
pub const MAX_WAITS: usize = 16;
static WAITING: AtomicUsize = AtomicUsize::new(0);

/// One of the [`MAX_WAITS`] waits, given back when dropped.
struct Waiting;

impl Waiting {
    fn take() -> Option<Self> {
        if WAITING.fetch_add(1, Ordering::SeqCst) >= MAX_WAITS {
            WAITING.fetch_sub(1, Ordering::SeqCst);
            return None;
        }
        Some(Waiting)
    }
}

impl Drop for Waiting {
    fn drop(&mut self) {
        WAITING.fetch_sub(1, Ordering::SeqCst);
    }
}
/// The partner feed (the launcher allows the Store 15 s), a picture (20 s), or a server's DNS check (5 s).
const PARTNERS_TIMEOUT: Duration = Duration::from_secs(25);
/// A skin added (Mojang's lookups and a download) or applied (a token, then Minecraft's upload).
const SKIN_TIMEOUT: Duration = Duration::from_secs(40);
/// The showcase (the launcher allows the Store 10 s).
const SHOWCASE_TIMEOUT: Duration = Duration::from_secs(15);
/// The in-game Library: a search, a plan or the profile's counts (Modrinth or CurseForge may be asked first).
const LIBRARY_TIMEOUT: Duration = Duration::from_secs(30);
/// An install: its files are downloaded and checked first.
const INSTALL_TIMEOUT: Duration = Duration::from_secs(600);

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
    /// is another one. With `wait` (the revision the game has, how long it may wait): once the state differs from it.
    fn friends(&self, playing: String, chat: Option<String>, wait: Option<(u64, Duration)>) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// A Friends action for a game playing as `playing`.
    fn friends_action(&self, playing: String, action: GameAction) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// The Store items of the account `playing` and what it wears.
    fn cosmetics(&self, playing: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// Wears `id` in `slot` (none: empties it) for `playing`; answers the items after it.
    fn equip(&self, playing: String, slot: String, id: Option<String>) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// Puts these emotes (8 places, id or none) on the wheel of `playing`; answers the items after it.
    fn emotes(&self, playing: String, slots: Vec<Option<String>>) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// The picture of an item `playing` owns (`{"png": base64}`).
    fn cosmetic_art(&self, playing: String, id: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// The partner directory for a game on Minecraft `version` (`partners/game.rs` `reply`).
    fn partners(&self, version: Option<String>) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// A listed partner picture by checksum (`{"type": "png"|"jpeg", "data": base64}`).
    fn partner_art(&self, sha: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// Listing `id`'s server, checked for a game on `version` (`{"name", "address"}`).
    fn partner_join(&self, id: String, version: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// The community rewards and Discord standing of `playing` (`store/community_game.rs`).
    fn community(&self, playing: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// A listed community reward's picture (`{"png": base64}`).
    fn reward_art(&self, id: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// Starts linking Discord for `playing` (`{"state": "started", "url", "attemptId"}` or `{"state": "off"}`).
    fn discord_link(&self, playing: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// The skin changer's list for `playing` (`{"skins": [...]}`).
    fn skins(&self, playing: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// Adds a skin to `playing`'s list (`{"skin", "skins"}`); never applies it.
    fn skin_add(&self, playing: String, source: crate::skin_library::Source) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// A listed skin's picture (`{"png": base64}`).
    fn skin_art(&self, playing: String, id: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// Puts a listed skin on `playing`'s Minecraft account with the `variant` arm model (`{"applied": id, "variant"}`).
    fn skin_apply(&self, playing: String, id: String, variant: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// Takes a skin out of `playing`'s list (`{"skins": [...]}`).
    fn skin_remove(&self, playing: String, id: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// The main menu's showcase slides open now (`{"rotateMs", "slides"}`).
    fn showcase(&self) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// A listed slide's picture (`{"type": "png"|"jpeg", "data": base64}`).
    fn showcase_art(&self, id: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// The in-game Library, for the game's own `profile` (checked requests).
    fn library(&self, profile: String, request: LibraryRequest) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
    /// The app's theme (`{"theme": …}`, one of [crate::perfcore::THEMES]).
    fn theme(&self) -> CmdResult<Value>;
    /// The badges `playing` owns and the one others see (`{"badges": [...] | null, "badge": ... | null}`; null: the Store
    /// doesn't send badges yet, the game keeps its own choice).
    fn badges(&self, _playing: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        async { Err("bad request".into()) }
    }
    /// Shows `tag` (one of `playing`'s badges) to other players (`PUT /me/badge/`): the Store's answer, as [`Backend::badges`].
    fn badge_set(&self, _playing: String, _tag: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        async { Err("bad request".into()) }
    }
    /// A file of a 3D cosmetic `playing` owns or the Store lists (`{"base64": …}`), checked.
    fn model_file(&self, _playing: String, _id: String, _part: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        async { Err("unavailable".into()) }
    }
    /// The game playing as `playing` joined `server` (normalised; none: it left).
    fn emote_room(&self, _playing: String, _server: Option<String>) -> CmdResult<Value> {
        Err("unavailable".into())
    }
    /// One of the game's emote frames (`emote` with an id, or `stop`).
    fn emote_send(&self, _playing: String, _frame: String) -> CmdResult<Value> {
        Err("unavailable".into())
    }
    /// The room's events after `since`, waiting up to `wait` for one.
    fn emote_events(&self, _playing: String, _since: u64, _wait: Duration) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        async { Err("unavailable".into()) }
    }
    /// Makes `theme` (one of [crate::perfcore::THEMES]) the app's theme, as its Settings do.
    fn set_theme(&self, theme: &'static str) -> impl std::future::Future<Output = CmdResult<Value>> + Send;
}

/// An in-game Library request, checked (`kind` one of the three, ids and names plain, an icon from the two picture hosts).
#[derive(Debug, Clone, PartialEq)]
pub enum LibraryRequest {
    State,
    Search { source: String, kind: String, query: String, offset: u32, sort: Option<String> },
    Plan { kind: String, id: String },
    Install { kind: String, id: String },
    Remove { kind: String, file: String, keep: bool, also: bool },
    Art { url: String },
    /// Instant Swap (swap.rs), from the running game: how the new game is coming along.
    SwapStatus,
    /// From the running game, once it has left its world or server: where it was and its window.
    SwapGo { handover: Value },
    /// From the standby game: it is at its main menu.
    SwapReady { secret: String },
    /// From the standby game: wait for the handover.
    SwapWait { secret: String, wait: Duration },
    /// From the standby game: it is in the world and in front.
    SwapJoined { secret: String, frames: u32 },
    /// From the running game: the mods it added were loaded into it live (HotLoader), so the standby game isn't needed.
    SwapDrop,
}

impl LibraryRequest {
    /// The request as checked, or None for one the Library doesn't make.
    fn checked(op: Op) -> Option<Self> {
        let kind_ok = |k: &str| crate::content::ingame::KINDS.contains(&k);
        Some(match op {
            Op::LibraryState => Self::State,
            Op::LibrarySearch { source, kind, query, offset, sort } => {
                if !kind_ok(&kind) || !matches!(source.as_str(), "modrinth" | "curseforge") || query.chars().count() > 120 || offset > 9_000 {
                    return None;
                }
                let sort = sort.filter(|s| matches!(s.as_str(), "relevance" | "downloads" | "updated" | "newest"));
                Self::Search { source, kind, query: query.trim().to_owned(), offset, sort }
            }
            Op::LibraryPlan { kind, id } if kind_ok(&kind) && crate::curseforge::valid_ref(&id) => Self::Plan { kind, id },
            Op::LibraryInstall { kind, id } if kind_ok(&kind) && crate::curseforge::valid_ref(&id) => Self::Install { kind, id },
            Op::LibraryRemove { kind, file, keep, also } if kind_ok(&kind) && crate::content::ingame::plain(&file) => Self::Remove { kind, file, keep, also },
            Op::LibraryArt { url } if crate::content::ingame::art_url(&url) => Self::Art { url },
            Op::SwapStatus => Self::SwapStatus,
            Op::SwapDrop => Self::SwapDrop,
            Op::SwapGo { world, server, window } => Self::SwapGo { handover: handover(world, server, window)? },
            _ => return None,
        })
    }

    fn limit(&self) -> Duration {
        match self {
            Self::Install { .. } => INSTALL_TIMEOUT,
            _ => LIBRARY_TIMEOUT,
        }
    }
}

/// The old game's handover, checked: a world folder name or a server address (or neither: the new game stays at its main
/// menu), and its window (position, size, maximized, fullscreen) within sane bounds.
fn handover(world: Option<String>, server: Option<String>, window: Option<Value>) -> Option<Value> {
    if world.is_some() && server.is_some() {
        return None;
    }
    if world.as_deref().is_some_and(|w| !crate::content::ingame::plain(w)) {
        return None;
    }
    if server.as_deref().is_some_and(|a| a.is_empty() || a.len() > 262 || !a.bytes().all(|b| b.is_ascii_alphanumeric() || b".-:[]_".contains(&b))) {
        return None;
    }
    let number = |w: &Value, key: &str, min: i64, max: i64| w[key].as_i64().filter(|n| (min..=max).contains(n));
    let window = match window {
        Some(w) => Some(json!({
            "x": number(&w, "x", -32_000, 32_000)?,
            "y": number(&w, "y", -32_000, 32_000)?,
            "width": number(&w, "width", 320, 16_384)?,
            "height": number(&w, "height", 240, 16_384)?,
            "maximized": w["maximized"].as_bool().unwrap_or(false),
            "fullscreen": w["fullscreen"].as_bool().unwrap_or(false),
            // The old game wrote its last frame (`.flint-swap-frame.png`) for the launcher's still window.
            "frame": w["frame"].as_bool().unwrap_or(false),
            // The old game's process: the still goes exactly over its window.
            // The old game's GUI scale (the new one sets it again once its window has the real size).
            "guiScale": number(&w, "guiScale", 0, 64),
            "pid": w["pid"].as_u64().filter(|p| *p > 0 && *p <= u64::from(u32::MAX)),
        })),
        None => None,
    };
    Some(json!({ "world": world, "server": server, "window": window }))
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
        /// The `revision` of the game's last answer: this one waits until the state differs from it.
        #[serde(default)]
        since: Option<u64>,
        /// How long it may wait, in ms (at most 25 s).
        #[serde(default, rename = "waitMs")]
        wait_ms: Option<u64>,
    },
    FriendsAction {
        playing: String,
        action: GameAction,
    },
    Badges {
        playing: String,
    },
    BadgeSet {
        playing: String,
        tag: String,
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
    /// The emote wheel: exactly 8 places, each an owned emote's id or null.
    Emotes {
        playing: String,
        slots: Vec<Option<String>>,
    },
    CosmeticArt {
        playing: String,
        id: String,
    },
    Partners {
        /// The game's Minecraft version (which servers it can join); none: any.
        #[serde(default)]
        version: Option<String>,
    },
    PartnerArt {
        sha: String,
    },
    PartnerJoin {
        id: String,
        version: String,
    },
    Community {
        playing: String,
    },
    RewardArt {
        id: String,
    },
    DiscordLink {
        playing: String,
    },
    Showcase,
    ShowcaseArt {
        id: String,
    },
    ModelFile {
        playing: String,
        id: String,
        part: String,
    },
    EmoteRoom {
        playing: String,
        /// The server's address after Minecraft's SRV lookup; none: the game left it.
        #[serde(default)]
        server: Option<String>,
    },
    EmoteSend {
        playing: String,
        kind: String,
        #[serde(default, rename = "cosmeticId")]
        cosmetic_id: Option<String>,
    },
    EmoteEvents {
        playing: String,
        #[serde(default)]
        since: u64,
        #[serde(default, rename = "waitMs")]
        wait_ms: Option<u64>,
    },
    LibraryState,
    LibrarySearch {
        source: String,
        kind: String,
        #[serde(default)]
        query: String,
        #[serde(default)]
        offset: u32,
        #[serde(default)]
        sort: Option<String>,
    },
    LibraryPlan {
        kind: String,
        id: String,
    },
    LibraryInstall {
        kind: String,
        id: String,
    },
    LibraryRemove {
        kind: String,
        file: String,
        #[serde(default)]
        keep: bool,
        /// The player agreed to take out the mods that need this one too.
        #[serde(default)]
        also: bool,
    },
    LibraryArt {
        url: String,
    },
    SwapStatus,
    SwapGo {
        #[serde(default)]
        world: Option<String>,
        #[serde(default)]
        server: Option<String>,
        #[serde(default)]
        window: Option<Value>,
    },
    SwapReady,
    SwapJoined {
        /// Frames the new game has presented on screen, in front, showing the world.
        #[serde(default)]
        frames: u32,
    },
    SwapDrop,
    SwapWait {
        #[serde(default, rename = "waitMs")]
        wait_ms: Option<u64>,
    },
    Theme,
    SetTheme {
        theme: String,
    },
    SkinsList {
        playing: String,
    },
    SkinAdd {
        playing: String,
        #[serde(default)]
        username: Option<String>,
        #[serde(default)]
        url: Option<String>,
    },
    SkinArt {
        playing: String,
        id: String,
    },
    SkinApply {
        playing: String,
        id: String,
        variant: String,
    },
    SkinRemove {
        playing: String,
        id: String,
    },
}

/// A showcase slide's id as the Store writes it: 1 to 64 letters, digits, `-` and `_` (a UUID too).
fn is_slide_id(text: &str) -> bool {
    (1..=64).contains(&text.len()) && text.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// A picture's checksum as the partner feed names it: 64 lowercase hex digits.
fn is_sha(text: &str) -> bool {
    text.len() == 64 && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
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
    /// Instant Swap's standby games (swap.rs).
    pub swaps: Arc<crate::swap::Swaps>,
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
    let mut reader = BufReader::new(read.take(MAX_REQUEST));
    let read = tokio::time::timeout(READ_TIMEOUT, reader.read_line(&mut line)).await;
    let reply = match read {
        Ok(Ok(_)) if line.ends_with('\n') => {
            // The game hung up (it asked again with another chat, or quit): a wait for it ends there.
            let gone = async move {
                let mut byte = [0u8; 1];
                while let Ok(1..) = reader.read(&mut byte).await {}
            };
            answer(line.trim_end(), registry, backend, gone).await
        }
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

/// One request's answer: an unknown secret gets "unauthorized" (and a short pause) before anything else is looked at. `gone`
/// ends when the game hangs up (a `friends` wait stops there).
async fn answer<B: Backend>(line: &str, registry: &Registry, backend: &B, gone: impl std::future::Future<Output = ()>) -> Value {
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
        Op::Friends { playing, chat, since, wait_ms } => match (account_id(&playing), chat.as_deref().map(account_id)) {
            (Some(_), None | Some(Some(_))) if own(&playing).is_none() => Err("account".into()),
            (Some(playing), chat @ (None | Some(Some(_)))) => {
                let chat = chat.flatten();
                let wait = since.zip(wait_ms).map(|(since, ms)| (since, Duration::from_millis(ms.min(25_000)))).filter(|(_, wait)| !wait.is_zero());
                match wait.and_then(|wait| Waiting::take().map(|slot| (wait, slot))) {
                    Some((wait, _slot)) => {
                        tokio::select! {
                            answer = backend.friends(playing, chat, Some(wait)) => answer,
                            _ = gone => Err("gone".into()),
                        }
                    }
                    None => backend.friends(playing, chat, None).await,
                }
            }
            _ => Err("bad request".into()),
        },
        Op::FriendsAction { playing, action } => match (account_id(&playing), own(&playing)) {
            (Some(_), Some(playing)) => limited(FRIENDS_TIMEOUT, backend.friends_action(playing, action)).await,
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
        Op::Badges { playing } => match (account_id(&playing), own(&playing)) {
            (Some(_), Some(playing)) => limited(STORE_TIMEOUT, backend.badges(playing)).await,
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
        Op::BadgeSet { playing, tag } => match (account_id(&playing), own(&playing)) {
            (Some(_), _) if !crate::store::badges::TAGS.contains(&tag.as_str()) => Err("bad request".into()),
            (Some(_), Some(playing)) => limited(STORE_TIMEOUT, backend.badge_set(playing, tag)).await,
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
        Op::Emotes { playing, slots } => {
            let ok = slots.len() == crate::store::kinds::EMOTE_SLOTS && slots.iter().flatten().all(|id| crate::store::is_uuid(id));
            match (account_id(&playing), own(&playing)) {
                (Some(_), _) if !ok => Err("bad request".into()),
                (Some(_), Some(playing)) => limited(STORE_TIMEOUT, backend.emotes(playing, slots)).await,
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
        Op::Partners { version } => {
            if version.as_deref().is_some_and(|v| !crate::partners::rules::is_version(v)) {
                Err("bad request".into())
            } else {
                limited(PARTNERS_TIMEOUT, backend.partners(version)).await
            }
        }
        Op::PartnerArt { sha } if is_sha(&sha) => limited(PARTNERS_TIMEOUT, backend.partner_art(sha)).await,
        Op::PartnerJoin { id, version } if crate::store::is_uuid(&id) && crate::partners::rules::is_version(&version) => {
            limited(PARTNERS_TIMEOUT, backend.partner_join(id, version)).await
        }
        Op::PartnerArt { .. } | Op::PartnerJoin { .. } => Err("bad request".into()),
        Op::Community { playing } => match (account_id(&playing), own(&playing)) {
            (Some(_), Some(playing)) => limited(STORE_TIMEOUT, backend.community(playing)).await,
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
        Op::RewardArt { id } if crate::store::is_uuid(&id) => limited(STORE_TIMEOUT, backend.reward_art(id)).await,
        Op::RewardArt { .. } => Err("bad request".into()),
        Op::DiscordLink { playing } => match (account_id(&playing), own(&playing)) {
            (Some(_), Some(playing)) => limited(STORE_TIMEOUT, backend.discord_link(playing)).await,
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
        Op::Showcase => limited(SHOWCASE_TIMEOUT, backend.showcase()).await,
        Op::ShowcaseArt { id } if is_slide_id(&id) => limited(PARTNERS_TIMEOUT, backend.showcase_art(id)).await,
        Op::ShowcaseArt { .. } => Err("bad request".into()),
        Op::ModelFile { playing, id, part } => match (account_id(&playing), own(&playing)) {
            (Some(_), _) if !crate::store::is_uuid(&id) || crate::store::model::Part::parse(&part).is_none() => Err("bad request".into()),
            (Some(_), Some(playing)) => limited(STORE_TIMEOUT, backend.model_file(playing, id, part)).await,
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
        Op::EmoteRoom { playing, server } => match (account_id(&playing), own(&playing)) {
            (Some(_), Some(playing)) => backend.emote_room(playing, server.as_deref().and_then(crate::social::emote_room::normalize)),
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
        Op::EmoteSend { playing, kind, cosmetic_id } => {
            let frame = match (kind.as_str(), cosmetic_id.map(|id| id.to_ascii_lowercase())) {
                ("emote", Some(id)) if crate::store::is_uuid(&id) => Some(crate::social::emote_room::emote_frame(&id)),
                ("stop", _) => Some(crate::social::emote_room::STOP.to_owned()),
                _ => None,
            };
            match (account_id(&playing), own(&playing), frame) {
                (Some(_), Some(playing), Some(frame)) => backend.emote_send(playing, frame),
                (Some(_), None, Some(_)) => Err("account".into()),
                _ => Err("bad request".into()),
            }
        }
        Op::EmoteEvents { playing, since, wait_ms } => match (account_id(&playing), own(&playing)) {
            (Some(_), Some(playing)) => {
                let wait = Duration::from_millis(wait_ms.unwrap_or(0).min(25_000));
                tokio::select! {
                    reply = backend.emote_events(playing, since, wait) => reply,
                    _ = gone => Err("gone".into()),
                }
            }
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
        // The standby game's own two: only its secret, for its profile, is a standby game's.
        Op::SwapReady => match registry.check(&request.secret) {
            Some(profile) => limited(LIBRARY_TIMEOUT, backend.library(profile, LibraryRequest::SwapReady { secret: request.secret.clone() })).await,
            None => Err("bad request".into()),
        },
        Op::SwapJoined { frames } => match registry.check(&request.secret) {
            Some(profile) => limited(LIBRARY_TIMEOUT, backend.library(profile, LibraryRequest::SwapJoined { secret: request.secret.clone(), frames })).await,
            None => Err("bad request".into()),
        },
        Op::SwapWait { wait_ms } => match (Waiting::take(), registry.check(&request.secret)) {
            (Some(_slot), Some(profile)) => {
                let wait = Duration::from_millis(wait_ms.unwrap_or(20_000).min(25_000));
                tokio::select! {
                    answer = backend.library(profile, LibraryRequest::SwapWait { secret: request.secret.clone(), wait }) => answer,
                    _ = gone => Err("gone".into()),
                }
            }
            (None, Some(_)) => Ok(json!({ "go": null })),
            _ => Err("bad request".into()),
        },
        op @ (Op::LibraryState | Op::LibrarySearch { .. } | Op::LibraryPlan { .. } | Op::LibraryInstall { .. } | Op::LibraryRemove { .. } | Op::LibraryArt { .. } | Op::SwapStatus | Op::SwapDrop | Op::SwapGo { .. }) => {
            match (LibraryRequest::checked(op), registry.check(&request.secret)) {
                (Some(library), Some(profile)) => limited(library.limit(), backend.library(profile, library)).await,
                _ => Err("bad request".into()),
            }
        }
        Op::Theme => backend.theme(),
        Op::SetTheme { theme } => match crate::perfcore::THEMES.into_iter().find(|t| *t == theme) {
            Some(theme) => backend.set_theme(theme).await,
            None => Err("bad request".into()),
        },
        Op::SkinsList { playing } => match (account_id(&playing), own(&playing)) {
            (Some(_), Some(playing)) => backend.skins(playing).await,
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
        Op::SkinAdd { playing, username, url } => {
            let source = match (username, url) {
                (Some(name), None) if name.len() <= 64 => Some(crate::skin_library::Source::Username(name)),
                (None, Some(url)) if url.len() <= 2048 => Some(crate::skin_library::Source::Url(url)),
                _ => None,
            };
            match (account_id(&playing), own(&playing), source) {
                (Some(_), Some(playing), Some(source)) => limited(SKIN_TIMEOUT, backend.skin_add(playing, source)).await,
                (Some(_), None, Some(_)) => Err("account".into()),
                _ => Err("bad request".into()),
            }
        }
        Op::SkinArt { id, .. } | Op::SkinRemove { id, .. } if !is_sha(&id) => Err("bad request".into()),
        Op::SkinArt { playing, id } => match (account_id(&playing), own(&playing)) {
            (Some(_), Some(playing)) => backend.skin_art(playing, id).await,
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
        Op::SkinRemove { playing, id } => match (account_id(&playing), own(&playing)) {
            (Some(_), Some(playing)) => backend.skin_remove(playing, id).await,
            (Some(_), None) => Err("account".into()),
            (None, _) => Err("bad request".into()),
        },
        Op::SkinApply { playing, id, variant } => match (account_id(&playing), own(&playing)) {
            _ if !is_sha(&id) || !matches!(variant.as_str(), "classic" | "slim") => Err("bad request".into()),
            (Some(_), Some(playing)) => limited(SKIN_TIMEOUT, backend.skin_apply(playing, id, variant)).await,
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

/// The emote room's events for a game: each emote with the item it is (its model, from what the account owns or the Store
/// lists), so the game can play it on that player.
async fn room_events(state: &crate::core::AppState, uuid: &str, since: u64) -> Value {
    let (events, seq, live) = state.social.emotes.events_after(since);
    let mut items = serde_json::Map::new();
    for id in events.iter().filter(|e| e.kind == "emote").filter_map(|e| e.cosmetic_id.clone()) {
        if items.contains_key(&id) {
            continue;
        }
        if let Some(item) = crate::store::game::emote_item(state, uuid, &id).await {
            items.insert(id, item);
        }
    }
    json!({ "events": events, "seq": seq, "live": live, "items": items })
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

    #[allow(clippy::manual_async_fn)]
    fn friends(&self, playing: String, chat: Option<String>, wait: Option<(u64, Duration)>) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move { crate::social::game::app_view(&app, &playing, chat.as_deref(), wait).await }
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
    fn badges(&self, playing: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let uuid = crate::store::game::game_account(&state, &playing)?;
            Ok(crate::store::badges::for_game(&crate::store::badges::list(&state, &uuid).await?))
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn badge_set(&self, playing: String, tag: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let uuid = crate::store::game::game_account(&state, &playing)?;
            let badges = crate::store::badges::set(&state, &uuid, &tag).await?;
            // The app's Badges page shows what the game chose.
            let _ = tauri::Emitter::emit(&app, "badges-changed", &badges);
            Ok(crate::store::badges::for_game(&badges))
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn equip(&self, playing: String, slot: String, id: Option<String>) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let uuid = crate::store::game::game_account(&state, &playing)?;
            // The app's window hears of it too: the launcher's Store page and previews show what the game put on.
            to_value(crate::store::game::wear(&state, &uuid, &slot, id.as_deref(), |view| crate::store::announce(&app, view)).await?)
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn emotes(&self, playing: String, slots: Vec<Option<String>>) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let uuid = crate::store::game::game_account(&state, &playing)?;
            to_value(crate::store::game::wheel(&state, &uuid, &slots, |view| crate::store::announce(&app, view)).await?)
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn model_file(&self, playing: String, id: String, part: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            use base64::Engine;
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let uuid = crate::store::game::game_account(&state, &playing)?;
            let bytes = crate::store::model::model_bytes(&state, &uuid, &id, &part).await?;
            Ok(json!({ "base64": base64::engine::general_purpose::STANDARD.encode(bytes) }))
        }
    }

    fn emote_room(&self, playing: String, server: Option<String>) -> CmdResult<Value> {
        let state = self.state();
        let want = server.map(|address| crate::social::emote_room::Want { uuid: playing.clone(), room: crate::social::emote_room::room_id(&address), address });
        let joined = want.is_some();
        state.social.emotes.join(want);
        Ok(json!({ "joined": joined, "live": state.social.emotes.is_live() }))
    }

    fn emote_send(&self, playing: String, frame: String) -> CmdResult<Value> {
        let state = self.state();
        let here = state.social.emotes.wanted().is_some_and(|w| w.uuid == playing);
        Ok(json!({ "sent": here && state.social.emotes.send(frame) }))
    }

    #[allow(clippy::manual_async_fn)]
    fn emote_events(&self, playing: String, since: u64, wait: Duration) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            if !wait.is_zero() && state.social.emotes.events_after(since).0.is_empty() {
                state.social.emotes.wait_after(since, wait).await;
            }
            Ok(room_events(&state, &playing, since).await)
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

    #[allow(clippy::manual_async_fn)]
    fn partners(&self, version: Option<String>) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let answer = crate::partners::feed(&state, crate::partners::Mode::Auto).await;
            Ok(crate::partners::game::reply(&answer, crate::store::now_millis() as i64, version.as_deref()))
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn partner_art(&self, sha: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            crate::partners::game::art(&state, &sha).await
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn partner_join(&self, id: String, version: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            crate::partners::game::join(&state, &id, &version).await
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn community(&self, playing: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let uuid = crate::store::game::game_account(&state, &playing)?;
            to_value(crate::store::community_game::game(&state, &uuid).await)
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn reward_art(&self, id: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            Ok(json!({ "png": crate::store::community_game::art(&state, &id).await? }))
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn discord_link(&self, playing: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let uuid = crate::store::game::game_account(&state, &playing)?;
            crate::store::community_game::link(&state, &uuid).await
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn showcase(&self) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let showcase = crate::showcase::game::current(&state).await;
            Ok(crate::showcase::game::reply(&showcase, crate::store::now_millis() as i64))
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn showcase_art(&self, id: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            crate::showcase::game::art(&state, &id).await
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn library(&self, profile: String, request: LibraryRequest) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            use crate::content::ingame;
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            match request {
                LibraryRequest::State => ingame::view(&state, &profile).await,
                LibraryRequest::Search { source, kind, query, offset, sort } => ingame::search(&state, &profile, &source, &kind, &query, offset, sort.as_deref()).await,
                LibraryRequest::Plan { kind, id } => ingame::plan(&state, &profile, &kind, &id).await,
                LibraryRequest::Install { kind, id } => ingame::install(&app, &state, &profile, &kind, &id).await,
                LibraryRequest::Remove { kind, file, keep, also } => ingame::remove(&app, &state, &profile, &kind, &file, keep, also).await,
                LibraryRequest::Art { url } => ingame::art(&url).await,
                LibraryRequest::SwapStatus => Ok(crate::swap::status(&app, &profile)),
                LibraryRequest::SwapDrop => Ok(crate::swap::drop_unneeded(&app, &profile)),
                LibraryRequest::SwapGo { handover } => crate::swap::go(&app, &profile, handover).await,
                LibraryRequest::SwapReady { secret } => Ok(crate::swap::ready(&app, &profile, &secret)),
                LibraryRequest::SwapJoined { secret, frames } => Ok(crate::swap::joined(&app, &profile, &secret, frames)),
                LibraryRequest::SwapWait { secret, wait } => Ok(crate::swap::wait(&app, &profile, &secret, wait).await),
            }
        }
    }

    fn theme(&self) -> CmdResult<Value> {
        Ok(json!({ "theme": crate::perfcore::ui_theme(&crate::data::load_settings(&self.state())) }))
    }

    #[allow(clippy::manual_async_fn)]
    fn set_theme(&self, theme: &'static str) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            {
                let _guard = state.file_lock.lock().await;
                let mut settings = crate::data::load_settings(&state);
                if !settings.is_object() {
                    settings = json!({});
                }
                if settings["theme"].as_str() != Some(theme) {
                    settings["theme"] = json!(theme);
                    crate::core::write_json(&state.paths.settings(), &settings)?;
                }
            }
            // The window takes it at once (lib/settings.tsx), as if chosen in its Settings.
            let _ = tauri::Emitter::emit(&app, "theme-changed", json!({ "theme": theme }));
            Ok(json!({}))
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn skins(&self, playing: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            Ok(json!({ "skins": crate::skin_library::list(&state, &playing)? }))
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn skin_add(&self, playing: String, source: crate::skin_library::Source) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let skin = crate::skin_library::add(&state, &playing, source).await?;
            let _ = tauri::Emitter::emit(&app, "skin-library-changed", json!({ "account": playing }));
            Ok(json!({ "skin": skin, "skins": crate::skin_library::list(&state, &playing)? }))
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn skin_art(&self, playing: String, id: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            use base64::Engine;
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            Ok(json!({ "png": base64::engine::general_purpose::STANDARD.encode(crate::skin_library::png(&state, &playing, &id)?) }))
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn skin_apply(&self, playing: String, id: String, variant: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            crate::skin_library::apply(&app, &state, &playing, &id, &variant).await?;
            // The launcher's window shows the new skin too (its Skins page and account card read it again).
            let _ = tauri::Emitter::emit(&app, "skin-library-changed", json!({ "account": playing, "applied": id }));
            Ok(json!({ "applied": id, "variant": variant }))
        }
    }

    #[allow(clippy::manual_async_fn)]
    fn skin_remove(&self, playing: String, id: String) -> impl std::future::Future<Output = CmdResult<Value>> + Send {
        let app = self.app.clone();
        async move {
            let state = tauri::Manager::state::<crate::core::AppState>(&app);
            let skins = crate::skin_library::remove(&state, &playing, &id)?;
            let _ = tauri::Emitter::emit(&app, "skin-library-changed", json!({ "account": playing }));
            Ok(json!({ "skins": skins }))
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

    #[derive(Default)]
    struct Fake {
        /// Friends' revision, and its change.
        revision: Mutex<u64>,
        changed: tokio::sync::Notify,
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
        /// What the partner and community ops were asked, in order.
        asked: Mutex<Vec<String>>,
        /// The app's theme (none: Ember).
        theme: Mutex<Option<String>>,
    }

    const PARTNER: &str = "7a1e0001-5c2d-4b8e-9f10-000000000001";
    const ART_SHA: &str = "9b4b11fb5dd783d98f9c752ca00cd0b923f1e495bf3e6ae03c5b9b8bf6cf6f33";
    const REWARD: &str = "00000000-0000-4000-8000-0000000000a1";

    /// The account the fake's games play as (Flint's active one), and another of Flint's.
    const PLAYING: &str = "8667ba71b85a4004af54457a9734eed7";
    const PLAYING_DASHED: &str = "8667ba71-b85a-4004-af54-457a9734eed7";
    const FRIEND: &str = "ec561538f3fd461daff5086b22154bce";
    const CAPE: &str = "00000000-0000-4000-8000-000000000001";
    const SKIN: &str = "5f1c2a5b0d7a4c6e8f9b1a2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60";

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
        async fn friends(&self, playing: String, chat: Option<String>, wait: Option<(u64, Duration)>) -> CmdResult<Value> {
            self.social.lock().unwrap().push((playing.clone(), chat.unwrap_or_else(|| "-".into())));
            if playing != PLAYING {
                return Err("account".into());
            }
            if let Some((since, wait)) = wait {
                let deadline = tokio::time::Instant::now() + wait;
                loop {
                    let changed = self.changed.notified();
                    let now = *self.revision.lock().unwrap();
                    if now != since || tokio::time::timeout_at(deadline, changed).await.is_err() {
                        break;
                    }
                }
            }
            let revision = *self.revision.lock().unwrap();
            Ok(json!({ "phase": "ready", "friends": [{ "uuid": FRIEND, "name": "Alex", "status": "online", "unread": 2 }], "revision": revision }))
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
        async fn emotes(&self, playing: String, slots: Vec<Option<String>>) -> CmdResult<Value> {
            self.store.lock().unwrap().push((playing, format!("emotes {}", slots.iter().flatten().count())));
            Ok(json!({ "items": [], "equipped": {}, "emotes": slots }))
        }
        async fn cosmetic_art(&self, playing: String, id: String) -> CmdResult<Value> {
            self.store.lock().unwrap().push((playing, format!("art {id}")));
            Ok(json!({ "png": "iVBORw0KGgo=" }))
        }
        async fn partners(&self, version: Option<String>) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("partners {}", version.as_deref().unwrap_or("any")));
            Ok(json!({ "source": "network", "problem": null, "servers": 1, "partners": [{ "id": PARTNER, "name": "Emberfall Network", "kind": "minecraft_server", "joinable": true }] }))
        }
        async fn partner_art(&self, sha: String) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("partnerArt {}", &sha[..8]));
            if sha == ART_SHA { Ok(json!({ "type": "png", "data": "iVBORw0KGgo=" })) } else { Err("not found".into()) }
        }
        async fn partner_join(&self, id: String, version: String) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("partnerJoin {} {version}", &id[..8]));
            if version == "1.8.9" {
                Err("Emberfall Network doesn't take Minecraft 1.8.9. Pick a profile on a version it lists.".into())
            } else {
                Ok(json!({ "name": "Emberfall Network", "address": "play.emberfall.example" }))
            }
        }
        async fn community(&self, playing: String) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("community {}", &playing[..8]));
            Ok(json!({ "state": "ok", "invite": "https://discord.gg/flintclient", "rewards": [{ "id": REWARD, "status": "locked" }] }))
        }
        async fn reward_art(&self, id: String) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("rewardArt {}", &id[..8]));
            Ok(json!({ "png": "iVBORw0KGgo=" }))
        }
        async fn discord_link(&self, playing: String) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("discordLink {}", &playing[..8]));
            Ok(json!({ "state": "started", "url": "https://discord.com/oauth2/authorize?state=x", "attemptId": REWARD }))
        }
        async fn showcase(&self) -> CmdResult<Value> {
            self.asked.lock().unwrap().push("showcase".into());
            Ok(json!({ "rotateMs": 6000, "slides": [{ "id": "winter", "title": "Season 4", "alt": "", "link": null, "width": 1920, "height": 1080, "weight": 0, "endsAt": null }] }))
        }
        async fn showcase_art(&self, id: String) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("showcaseArt {id}"));
            if id == "winter" { Ok(json!({ "type": "jpeg", "data": "/9j/4A==" })) } else { Err("not found".into()) }
        }
        async fn library(&self, profile: String, request: LibraryRequest) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("library {profile} {request:?}"));
            Ok(json!({ "profile": profile }))
        }
        fn theme(&self) -> CmdResult<Value> {
            Ok(json!({ "theme": self.theme.lock().unwrap().clone().unwrap_or_else(|| "ember".into()) }))
        }
        async fn set_theme(&self, theme: &'static str) -> CmdResult<Value> {
            *self.theme.lock().unwrap() = Some(theme.into());
            Ok(json!({}))
        }
        async fn skins(&self, playing: String) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("skins {}", &playing[..8]));
            Ok(json!({ "skins": [] }))
        }
        async fn skin_add(&self, playing: String, source: crate::skin_library::Source) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("skinAdd {} {source:?}", &playing[..8]));
            match source {
                crate::skin_library::Source::Username(name) if name == "nobody_here" => Err("No Minecraft player is called nobody_here.".into()),
                _ => Ok(json!({ "skin": { "id": SKIN }, "skins": [{ "id": SKIN }] })),
            }
        }
        async fn skin_art(&self, playing: String, id: String) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("skinArt {} {}", &playing[..8], &id[..8]));
            Ok(json!({ "png": "iVBORw0KGgo=" }))
        }
        async fn skin_apply(&self, playing: String, id: String, variant: String) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("skinApply {} {} {variant}", &playing[..8], &id[..8]));
            Ok(json!({ "applied": id, "variant": variant }))
        }
        async fn skin_remove(&self, playing: String, id: String) -> CmdResult<Value> {
            self.asked.lock().unwrap().push(format!("skinRemove {} {}", &playing[..8], &id[..8]));
            Ok(json!({ "skins": [] }))
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
        assert_eq!(actions[3], GameAction::Settings { status: Some("busy".into()), allow_requests: None, last_seen: None, gifts: None });
    }

    impl Fake {
        fn bump(&self) {
            *self.revision.lock().unwrap() += 1;
            self.changed.notify_waiters();
        }
    }

    #[tokio::test]
    async fn a_game_waiting_for_friends_hears_of_a_change_at_once() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        // Without `since`: at once, with the revision to wait on.
        let first = ask(address, &secret, json!({ "op": "friends", "playing": PLAYING })).await;
        let revision = first["revision"].as_u64().unwrap();
        // Nothing changes: answered after the wait, with the same revision.
        let started = std::time::Instant::now();
        let quiet = ask(address, &secret, json!({ "op": "friends", "playing": PLAYING, "since": revision, "waitMs": 150 })).await;
        assert!(started.elapsed() >= Duration::from_millis(140), "it waited");
        assert_eq!(quiet["revision"], revision);
        // A change while it waits: answered then, long before the wait is over.
        let waiting = tokio::spawn({
            let secret = secret.clone();
            async move { ask(address, &secret, json!({ "op": "friends", "playing": PLAYING, "since": revision, "waitMs": 20_000 })).await }
        });
        tokio::time::sleep(Duration::from_millis(50)).await;
        let changed_at = std::time::Instant::now();
        fake.bump();
        let answer = tokio::time::timeout(Duration::from_secs(2), waiting).await.expect("answered on the change").unwrap();
        assert!(changed_at.elapsed() < Duration::from_millis(500), "{:?}", changed_at.elapsed());
        assert_eq!((answer["ok"].clone(), answer["revision"].as_u64()), (json!(true), Some(revision + 1)));
        // A revision the game doesn't have yet (it changed already): at once.
        let started = std::time::Instant::now();
        ask(address, &secret, json!({ "op": "friends", "playing": PLAYING, "since": revision, "waitMs": 20_000 })).await;
        assert!(started.elapsed() < Duration::from_secs(1));
        // Another account than the game's is still refused before any wait.
        let other = ask(address, &secret, json!({ "op": "friends", "playing": FRIEND, "since": 1, "waitMs": 20_000 })).await;
        assert_eq!(other["error"], "account");
    }

    #[tokio::test]
    async fn a_game_that_hangs_up_lets_its_wait_go() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        let revision = *fake.revision.lock().unwrap();
        for _ in 0..(MAX_WAITS + 4) {
            let mut stream = TcpStream::connect(address).await.unwrap();
            let line = json!({ "v": PROTOCOL, "secret": secret, "op": "friends", "playing": PLAYING, "since": revision, "waitMs": 20_000 });
            stream.write_all(format!("{line}\n").as_bytes()).await.unwrap();
            drop(stream);
        }
        // Every wait was let go when its game hung up (more than MAX_WAITS of them): the next one waits again.
        tokio::time::sleep(Duration::from_millis(200)).await;
        let started = std::time::Instant::now();
        ask(address, &secret, json!({ "op": "friends", "playing": PLAYING, "since": revision, "waitMs": 150 })).await;
        assert!(started.elapsed() >= Duration::from_millis(140));
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
    async fn partners_are_public_and_named_versions_are_checked() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        assert_eq!(ask(address, &"0".repeat(64), json!({ "op": "partners" })).await["error"], "unauthorized");
        let listed = ask(address, &secret, json!({ "op": "partners", "version": "26.3" })).await;
        assert_eq!(listed["ok"], true);
        assert_eq!(listed["servers"], 1);
        assert_eq!(listed["partners"][0]["name"], "Emberfall Network");
        assert_eq!(ask(address, &secret, json!({ "op": "partners" })).await["ok"], true, "no version: any");
        for bad in [json!({ "op": "partners", "version": "latest" }), json!({ "op": "partners", "version": "26.3-pre1" }), json!({ "op": "partners", "version": 26 })] {
            assert_eq!(ask(address, &secret, bad).await["error"], "bad request");
        }
        assert_eq!(*fake.asked.lock().unwrap(), ["partners 26.3", "partners any"]);
    }

    #[tokio::test]
    async fn partner_pictures_and_servers_are_asked_for_by_well_formed_names() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        assert_eq!(ask(address, &secret, json!({ "op": "partnerArt", "sha": ART_SHA })).await, json!({ "ok": true, "type": "png", "data": "iVBORw0KGgo=" }));
        assert_eq!(ask(address, &secret, json!({ "op": "partnerArt", "sha": "a".repeat(64) })).await, json!({ "ok": false, "error": "not found" }));
        let joined = ask(address, &secret, json!({ "op": "partnerJoin", "id": PARTNER, "version": "26.3" })).await;
        assert_eq!(joined, json!({ "ok": true, "name": "Emberfall Network", "address": "play.emberfall.example" }));
        let refused = ask(address, &secret, json!({ "op": "partnerJoin", "id": PARTNER, "version": "1.8.9" })).await;
        assert_eq!(refused["ok"], false);
        assert!(refused["error"].as_str().unwrap().contains("doesn't take Minecraft 1.8.9"), "the launcher's own words");
        for bad in [
            json!({ "op": "partnerArt", "sha": ART_SHA.to_uppercase() }),
            json!({ "op": "partnerArt", "sha": "../../etc/passwd" }),
            json!({ "op": "partnerArt" }),
            json!({ "op": "partnerJoin", "id": "emberfall", "version": "26.3" }),
            json!({ "op": "partnerJoin", "id": PARTNER, "version": "newest" }),
            json!({ "op": "partnerJoin", "id": PARTNER }),
        ] {
            assert_eq!(ask(address, &secret, bad.clone()).await["error"], "bad request", "{bad}");
        }
        assert_eq!(*fake.asked.lock().unwrap(), ["partnerArt 9b4b11fb", "partnerArt aaaaaaaa", "partnerJoin 7a1e0001 26.3", "partnerJoin 7a1e0001 1.8.9"]);
    }

    #[tokio::test]
    async fn community_and_discord_linking_answer_only_for_the_account_the_game_plays_as() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        let shown = ask(address, &secret, json!({ "op": "community", "playing": PLAYING_DASHED })).await;
        assert_eq!(shown["state"], "ok");
        assert_eq!(shown["rewards"][0]["status"], "locked");
        let started = ask(address, &secret, json!({ "op": "discordLink", "playing": PLAYING })).await;
        assert_eq!((started["state"].clone(), started["attemptId"].clone()), (json!("started"), json!(REWARD)));
        // Another account than the one this game plays as: nothing reaches the Store.
        for op in [json!({ "op": "community", "playing": FRIEND }), json!({ "op": "discordLink", "playing": FRIEND })] {
            assert_eq!(ask(address, &secret, op).await, json!({ "ok": false, "error": "account" }));
        }
        // A reward's picture is public: no account is named.
        assert_eq!(ask(address, &secret, json!({ "op": "rewardArt", "id": REWARD })).await, json!({ "ok": true, "png": "iVBORw0KGgo=" }));
        for bad in [
            json!({ "op": "community" }),
            json!({ "op": "community", "playing": "Steve" }),
            json!({ "op": "discordLink", "playing": "" }),
            json!({ "op": "rewardArt", "id": "x" }),
            json!({ "op": "rewardArt" }),
        ] {
            assert_eq!(ask(address, &secret, bad.clone()).await["error"], "bad request", "{bad}");
        }
        assert_eq!(*fake.asked.lock().unwrap(), ["community 8667ba71", "discordLink 8667ba71", "rewardArt 00000000"]);
    }

    #[tokio::test]
    async fn the_showcase_and_its_pictures_are_public_and_asked_for_by_well_formed_ids() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        assert_eq!(ask(address, &"0".repeat(64), json!({ "op": "showcase" })).await["error"], "unauthorized");
        let shown = ask(address, &secret, json!({ "op": "showcase" })).await;
        assert_eq!((shown["ok"].clone(), shown["rotateMs"].clone(), shown["slides"][0]["id"].clone()), (json!(true), json!(6000), json!("winter")));
        assert_eq!(ask(address, &secret, json!({ "op": "showcaseArt", "id": "winter" })).await, json!({ "ok": true, "type": "jpeg", "data": "/9j/4A==" }));
        assert_eq!(ask(address, &secret, json!({ "op": "showcaseArt", "id": "7a1e0001-5c2d-4b8e-9f10-000000000001" })).await["error"], "not found");
        for bad in [json!({ "op": "showcaseArt" }), json!({ "op": "showcaseArt", "id": "" }), json!({ "op": "showcaseArt", "id": "../x" }), json!({ "op": "showcaseArt", "id": "x".repeat(65) })] {
            assert_eq!(ask(address, &secret, bad.clone()).await["error"], "bad request", "{bad}");
        }
        assert_eq!(*fake.asked.lock().unwrap(), ["showcase", "showcaseArt winter", "showcaseArt 7a1e0001-5c2d-4b8e-9f10-000000000001"]);
    }

    #[tokio::test]
    async fn the_theme_goes_both_ways() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        assert_eq!(ask(address, &secret, json!({ "op": "theme" })).await, json!({ "ok": true, "theme": "ember" }));
        assert_eq!(ask(address, &secret, json!({ "op": "setTheme", "theme": "dusk" })).await, json!({ "ok": true }));
        assert_eq!(fake.theme.lock().unwrap().as_deref(), Some("dusk"));
        assert_eq!(ask(address, &secret, json!({ "op": "theme" })).await["theme"], "dusk");
        // Only the app's own themes: anything else is refused before the settings are touched.
        assert_eq!(ask(address, &secret, json!({ "op": "setTheme", "theme": "../../x" })).await["error"], "bad request");
        assert_eq!(ask(address, &secret, json!({ "op": "setTheme" })).await["error"], "bad request");
        assert_eq!(fake.theme.lock().unwrap().as_deref(), Some("dusk"));
        assert_eq!(ask(address, "0", json!({ "op": "theme" })).await["error"], "unauthorized");
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

    #[tokio::test]
    async fn the_library_answers_for_the_games_own_profile_only() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("survival-263", PLAYING);
        // A game can't name another profile: whatever it sends, the library works on the one it was launched for.
        let answer = ask(address, &secret, json!({ "op": "libraryState", "profile": "someone-else" })).await;
        assert_eq!(answer["ok"], true);
        assert_eq!(answer["profile"], "survival-263");
        let search = ask(address, &secret, json!({ "op": "librarySearch", "source": "curseforge", "kind": "mod", "query": "  apple skin ", "sort": "odd" })).await;
        assert_eq!(search["ok"], true);
        let asked = fake.asked.lock().unwrap().clone();
        assert!(asked.iter().any(|a| a.contains("Search") && a.contains("query: \"apple skin\"") && a.contains("sort: None")), "{asked:?}");
        for good in [
            json!({ "op": "libraryInstall", "kind": "resourcepack", "id": "cf:248787" }),
            json!({ "op": "libraryPlan", "kind": "shader", "id": "BVzZfTc1" }),
            json!({ "op": "libraryRemove", "kind": "mod", "file": "appleskin-3.0.10.jar" }),
            json!({ "op": "libraryArt", "url": "https://cdn.modrinth.com/data/EsAfCjCV/icon.png" }),
        ] {
            assert_eq!(ask(address, &secret, good.clone()).await["ok"], true, "{good}");
        }
    }

    #[tokio::test]
    async fn the_library_refuses_what_it_doesnt_offer() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        for bad in [
            json!({ "op": "librarySearch", "source": "modrinth", "kind": "datapack" }),
            json!({ "op": "librarySearch", "source": "modrinth", "kind": "modpack" }),
            json!({ "op": "librarySearch", "source": "planetminecraft", "kind": "mod" }),
            json!({ "op": "librarySearch", "source": "modrinth", "kind": "mod", "query": "x".repeat(121) }),
            json!({ "op": "librarySearch", "source": "modrinth", "kind": "mod", "offset": 50_000 }),
            json!({ "op": "libraryInstall", "kind": "datapack", "id": "abc" }),
            json!({ "op": "libraryInstall", "kind": "mod", "id": "../../x" }),
            json!({ "op": "libraryRemove", "kind": "mod", "file": "../flint-content.json" }),
            json!({ "op": "libraryRemove", "kind": "mod", "file": ".flint-pending-removal.json" }),
            json!({ "op": "libraryRemove", "kind": "resourcepack", "file": "saves\\World" }),
            json!({ "op": "libraryArt", "url": "https://127.0.0.1:4810/icon.png" }),
            json!({ "op": "libraryArt", "url": "http://cdn.modrinth.com/icon.png" }),
            json!({ "op": "libraryArt", "url": "file:///C:/Windows/win.ini" }),
        ] {
            assert_eq!(ask(address, &secret, bad.clone()).await, json!({ "ok": false, "error": "bad request" }), "{bad}");
        }
        assert!(fake.asked.lock().unwrap().iter().all(|a| !a.starts_with("library")), "nothing refused reached the launcher's content code");
        // Without a live secret, nothing at all.
        assert_eq!(ask(address, &"0".repeat(64), json!({ "op": "libraryState" })).await["error"], "unauthorized");
    }

    #[tokio::test]
    async fn the_swap_ops_carry_the_games_own_secret_and_a_checked_handover() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("survival-263", PLAYING);
        for op in ["swapStatus", "swapReady", "swapJoined", "swapDrop"] {
            assert_eq!(ask(address, &secret, json!({ "op": op })).await["ok"], true, "{op}");
        }
        let waited = ask(address, &secret, json!({ "op": "swapWait", "waitMs": 10 })).await;
        assert_eq!(waited["ok"], true);
        let go = json!({ "op": "swapGo", "world": "New World", "window": { "x": 10, "y": 20, "width": 1280, "height": 720, "maximized": false, "fullscreen": true, "guiScale": 3, "pid": 4242 } });
        assert_eq!(ask(address, &secret, go).await["ok"], true);
        let asked = fake.asked.lock().unwrap().clone();
        assert!(asked.iter().any(|a| a.contains("SwapReady") && a.contains(&secret)), "the standby game is known by its own secret: {asked:?}");
        assert!(asked.iter().any(|a| a.contains("SwapGo") && a.contains("New World") && a.contains("fullscreen")), "{asked:?}");
        assert!(asked.iter().any(|a| a.contains("SwapGo") && a.contains("guiScale") && a.contains("4242")), "the GUI scale and the process carry over: {asked:?}");
        for bad in [
            json!({ "op": "swapGo", "world": "../other", "window": null }),
            json!({ "op": "swapGo", "world": "A", "server": "b.example" }),
            json!({ "op": "swapGo", "server": "evil host" }),
            json!({ "op": "swapGo", "server": "a.example", "window": { "x": 0, "y": 0, "width": 10, "height": 720 } }),
        ] {
            assert_eq!(ask(address, &secret, bad.clone()).await, json!({ "ok": false, "error": "bad request" }), "{bad}");
        }
        assert_eq!(ask(address, &"0".repeat(64), json!({ "op": "swapReady" })).await["error"], "unauthorized");
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

    #[tokio::test]
    async fn the_skin_changer_is_reached_only_as_the_game_s_account_and_never_applies_on_add() {
        let (address, registry, fake) = link().await;
        let secret = registry.register("p", PLAYING);
        let other = registry.register("q", FRIEND);
        assert_eq!(ask(address, &secret, json!({ "op": "skinsList", "playing": PLAYING_DASHED })).await["ok"], true);
        let added = ask(address, &secret, json!({ "op": "skinAdd", "playing": PLAYING, "username": "Notch" })).await;
        assert_eq!(added["skin"]["id"], SKIN);
        let missing = ask(address, &secret, json!({ "op": "skinAdd", "playing": PLAYING, "username": "nobody_here" })).await;
        assert_eq!(missing["error"], "No Minecraft player is called nobody_here.");
        assert_eq!(ask(address, &secret, json!({ "op": "skinAdd", "playing": PLAYING, "url": "https://example.com/s.png" })).await["ok"], true);
        assert_eq!(ask(address, &secret, json!({ "op": "skinArt", "playing": PLAYING, "id": SKIN })).await["png"], "iVBORw0KGgo=");
        let applied = ask(address, &secret, json!({ "op": "skinApply", "playing": PLAYING, "id": SKIN, "variant": "slim" })).await;
        assert_eq!(applied["variant"], "slim");
        assert_eq!(ask(address, &secret, json!({ "op": "skinRemove", "playing": PLAYING, "id": SKIN })).await["ok"], true);
        // Malformed asks never reach Flint, nor another account's.
        for bad in [
            json!({ "op": "skinAdd", "playing": PLAYING }),
            json!({ "op": "skinAdd", "playing": PLAYING, "username": "a", "url": "https://example.com/s.png" }),
            json!({ "op": "skinArt", "playing": PLAYING, "id": "x" }),
            json!({ "op": "skinRemove", "playing": PLAYING, "id": "../../x" }),
            json!({ "op": "skinApply", "playing": PLAYING, "id": SKIN, "variant": "wide" }),
        ] {
            assert_eq!(ask(address, &secret, bad.clone()).await["error"], "bad request", "{bad}");
        }
        assert_eq!(ask(address, &other, json!({ "op": "skinApply", "playing": PLAYING, "id": SKIN, "variant": "classic" })).await["error"], "account");
        assert_eq!(ask(address, &other, json!({ "op": "skinsList", "playing": PLAYING })).await["error"], "account");
        let asked = fake.asked.lock().unwrap().clone();
        assert_eq!(asked.iter().filter(|a| a.starts_with("skinApply")).count(), 1, "{asked:?}");
        assert!(asked.iter().all(|a| !a.contains(&FRIEND[..8])), "{asked:?}");
    }
}
