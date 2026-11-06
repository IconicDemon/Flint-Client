//! Friends in the game: what the game link (`gamelink.rs`) gives a game Flint started, so its Friends screen shows the same
//! friends, requests and chats as the app's Friends page. Everything stays the launcher's: the game asks, Flint answers from
//! its own state ([`Social`]) and runs the same actions the app's page runs. The game never sees a Friends session token.
//!
//! - **Only for the account the game plays as**, and only when that is Flint's active account ([`gate`]): a game playing as
//!   another account gets `account`; a launcher signed out, or on its test identity, answers "signed out".
//! - **Trimmed for the game** ([`view`]): no conversations except the one the game has open (its latest
//!   [`GAME_MESSAGES`]), lists capped, nothing the app keeps for itself (`focus`, `account`). A game polling with a chat
//!   open wakes the background sync (it still never syncs faster than an open chat's 4 s).
//! - **Actions** ([`GameAction`], [`act`]): the app page's own, through the same `Social` methods, which check every
//!   argument (names, ids, UUIDs, message length) before anything is sent. Failures answer `Fail::code()`, the codes the
//!   app's page translates; the game translates the same ones.
use super::*;

/// The most friends sent to a game (the server allows 200).
pub const GAME_FRIENDS: usize = 300;
/// The most requests sent each way.
pub const GAME_REQUESTS: usize = 100;
/// The latest messages of the open chat sent with each answer.
pub const GAME_MESSAGES: usize = 50;
/// A page of older messages.
pub const GAME_HISTORY: u32 = 30;

/// A friend as a game shows them.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameFriend {
    pub uuid: String,
    pub name: String,
    pub status: String,
    pub in_game: bool,
    pub last_seen: Option<i64>,
    pub pinned: bool,
    pub unread: u32,
}

/// The chat the game has open.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameChat {
    pub with: String,
    /// Oldest first.
    pub messages: Vec<Message>,
    /// Flint fetched the latest page of this chat's history (otherwise the game asks for it once).
    pub loaded: bool,
    /// Flint holds older messages than these (the game asks for them with `history`).
    pub more: bool,
}

/// What a game's Friends screen shows.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameFriends {
    pub configured: bool,
    pub phase: Phase,
    /// Signed out because Flint's active account is its test identity (no Minecraft account to prove).
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub test_identity: bool,
    pub me: Option<Me>,
    pub friends: Vec<GameFriend>,
    pub incoming: Vec<FriendRequest>,
    pub outgoing: Vec<FriendRequest>,
    pub offline: bool,
    pub stale: bool,
    pub error: Option<String>,
    pub synced_at: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub chat: Option<GameChat>,
}

impl GameFriends {
    fn empty(configured: bool, phase: Phase) -> Self {
        Self {
            configured,
            phase,
            test_identity: false,
            me: None,
            friends: Vec::new(),
            incoming: Vec::new(),
            outgoing: Vec::new(),
            offline: false,
            stale: false,
            error: None,
            synced_at: None,
            chat: None,
        }
    }

    /// Flint has no Microsoft account active (none, or its test identity).
    pub fn signed_out(configured: bool, test_identity: bool) -> Self {
        Self { test_identity, ..Self::empty(configured, if configured { Phase::SignedOut } else { Phase::Unavailable }) }
    }
}

/// Whether a game playing as `playing` (32 lowercase hex) may have Friends now, given Flint's active account (its UUID in
/// any spelling, and whether it is the test identity).
#[derive(Debug, PartialEq)]
pub enum Gate {
    /// The game plays as Flint's active Microsoft account.
    Ready,
    /// Flint has no account active, or its test identity: Friends is signed out.
    SignedOut { test_identity: bool },
    /// Flint's active account is another one.
    OtherAccount,
}

pub fn gate(active: Option<(&str, bool)>, playing: &str) -> Gate {
    match active {
        None => Gate::SignedOut { test_identity: false },
        Some((uuid, test)) if canonical(uuid).as_deref() == Some(playing) => {
            if test {
                Gate::SignedOut { test_identity: true }
            } else {
                Gate::Ready
            }
        }
        Some(_) => Gate::OtherAccount,
    }
}

/// The snapshot as a game playing as `playing` sees it, with the chat `chat` if it has one open. A snapshot still for
/// another account (Flint switched a moment ago and hasn't synced yet) shows as connecting, never that account's friends.
pub fn view(snapshot: &Snapshot, playing: &str, chat: Option<&str>) -> GameFriends {
    if snapshot.account.as_deref() != Some(playing) {
        let phase = if !snapshot.configured { Phase::Unavailable } else { Phase::Connecting };
        return GameFriends::empty(snapshot.configured, phase);
    }
    let friends = snapshot
        .friends
        .iter()
        .take(GAME_FRIENDS)
        .map(|f| GameFriend {
            uuid: f.uuid.clone(),
            name: f.name.clone(),
            status: f.status.clone(),
            in_game: f.in_game,
            last_seen: f.last_seen,
            pinned: f.pinned,
            unread: f.unread,
        })
        .collect();
    let chat = chat.and_then(canonical).filter(|with| snapshot.friends.iter().any(|f| &f.uuid == with)).map(|with| {
        let conversation = snapshot.conversations.get(&with);
        let all = conversation.map(|c| c.messages.as_slice()).unwrap_or_default();
        let skip = all.len().saturating_sub(GAME_MESSAGES);
        GameChat { messages: all[skip..].to_vec(), loaded: conversation.is_some_and(|c| c.loaded), more: skip > 0, with }
    });
    GameFriends {
        configured: snapshot.configured,
        phase: snapshot.phase,
        test_identity: false,
        me: snapshot.me.clone(),
        friends,
        incoming: snapshot.incoming.iter().take(GAME_REQUESTS).cloned().collect(),
        outgoing: snapshot.outgoing.iter().take(GAME_REQUESTS).cloned().collect(),
        offline: snapshot.offline,
        stale: snapshot.stale,
        error: snapshot.error.clone(),
        synced_at: snapshot.synced_at,
        chat,
    }
}

/// What a game asks Friends to do: the app page's own actions (`{"kind": "send", "to": …}`).
#[derive(Deserialize, Debug, Clone, PartialEq)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum GameAction {
    /// Retry: signs in now if needed, then syncs.
    Connect,
    Request {
        name: String,
    },
    Respond {
        id: Value,
        accept: bool,
    },
    Cancel {
        id: Value,
    },
    Remove {
        uuid: String,
    },
    Pin {
        uuid: String,
        pinned: bool,
    },
    Send {
        to: String,
        body: String,
        #[serde(default)]
        sensitive: bool,
        /// The game's id for this message, the same on every retry.
        #[serde(default, rename = "clientId")]
        client_id: Option<String>,
    },
    History {
        with: String,
        /// Older than this message id; none: the latest page.
        #[serde(default)]
        before: Option<i64>,
    },
    Read {
        with: String,
        #[serde(rename = "upTo")]
        up_to: i64,
    },
    Settings {
        #[serde(default)]
        status: Option<String>,
        #[serde(default, rename = "allowRequests")]
        allow_requests: Option<bool>,
    },
    Search {
        query: String,
    },
}

impl GameAction {
    /// Whether it changes what the app's Friends page shows (the page is told at once).
    pub fn changes_view(&self) -> bool {
        !matches!(self, GameAction::Search { .. } | GameAction::History { before: Some(_), .. })
    }
}

/// Runs one action as the app's page would, and answers what the game needs from it.
pub async fn act(social: &Social, mc: &dyn Minecraft, action: GameAction) -> Result<Value, Fail> {
    Ok(match action {
        GameAction::Connect => {
            social.retry_now();
            let result = social.connect(mc).await;
            social.wake.notify_one();
            match result {
                Ok(()) | Err(Fail::SignedOut | Fail::NotConfigured | Fail::Unavailable | Fail::Offline) => json!({}),
                Err(failure) => return Err(failure),
            }
        }
        GameAction::Request { name } => json!({ "result": social.request(mc, &name).await? }),
        GameAction::Respond { id, accept } => {
            social.respond(mc, &id, accept).await?;
            json!({})
        }
        GameAction::Cancel { id } => {
            social.cancel(mc, &id).await?;
            json!({})
        }
        GameAction::Remove { uuid } => {
            social.remove(mc, &uuid).await?;
            json!({})
        }
        GameAction::Pin { uuid, pinned } => {
            social.pin(mc, &uuid, pinned).await?;
            json!({})
        }
        GameAction::Send { to, body, sensitive, client_id } => json!({ "message": social.send(mc, &to, &body, sensitive, client_id.as_deref()).await? }),
        GameAction::History { with, before } => {
            let messages = social.history(mc, &with, before, Some(GAME_HISTORY)).await?;
            json!({ "complete": messages.len() < GAME_HISTORY as usize, "messages": messages })
        }
        GameAction::Read { with, up_to } => {
            social.read(mc, &with, up_to).await?;
            json!({})
        }
        GameAction::Settings { status, allow_requests } => json!({ "me": social.settings(mc, status.as_deref(), allow_requests).await? }),
        GameAction::Search { query } => json!({ "players": social.search(mc, &query).await? }),
    })
}

impl Social {
    /// [`Social::snapshot`] with only `chat`'s conversation (a game polls every 4 s; the others aren't copied for it).
    pub fn game_snapshot(&self, chat: Option<&str>) -> Snapshot {
        let chat = chat.and_then(canonical);
        let inner = self.inner.lock().unwrap();
        Snapshot {
            configured: self.configured(),
            phase: if self.configured() { inner.phase } else { Phase::Unavailable },
            account: inner.account.as_ref().map(|a| a.uuid.clone()),
            me: inner.me.clone(),
            friends: inner.friends.clone(),
            incoming: inner.incoming.clone(),
            outgoing: inner.outgoing.clone(),
            conversations: chat
                .and_then(|with| inner.conversations.get(&with).map(|c| (with, c.clone())))
                .into_iter()
                .collect(),
            offline: inner.offline,
            error: inner.error.clone(),
            synced_at: inner.synced_at,
            focus: None,
            stale: inner.stale,
        }
    }
}

// ---------- in the app ----------

fn app_gate(state: &AppState, playing: &str) -> Gate {
    let active = crate::auth::active_account(state);
    gate(active.as_ref().map(|a| (a.uuid.as_str(), a.qa)), playing)
}

/// The `friends` answer for a game playing as `playing` (32 lowercase hex), with its open chat.
pub fn app_view(app: &AppHandle, playing: &str, chat: Option<&str>) -> CmdResult<Value> {
    let state = app.state::<AppState>();
    let social = &state.social;
    let view = match app_gate(&state, playing) {
        Gate::OtherAccount => return Err("account".into()),
        Gate::SignedOut { test_identity } => GameFriends::signed_out(social.configured(), test_identity),
        Gate::Ready => {
            let snapshot = social.game_snapshot(chat);
            let shown = view(&snapshot, playing, chat);
            if chat.is_some() || snapshot.account.as_deref() != Some(playing) {
                // An open chat in the game polls as fast as one in the app; a switch is followed at once.
                social.wake.notify_one();
            }
            shown
        }
    };
    serde_json::to_value(view).map_err(|_| "internal error".to_owned())
}

/// Runs a game's action for `playing`, as the app's page runs it; the page is told what changed.
pub async fn app_act(app: &AppHandle, playing: &str, action: GameAction) -> CmdResult<Value> {
    let state = app.state::<AppState>();
    match app_gate(&state, playing) {
        Gate::OtherAccount => return Err("account".into()),
        Gate::SignedOut { .. } => return Err(Fail::SignedOut.code()),
        Gate::Ready => {}
    }
    let changes = action.changes_view();
    let answer = act(&state.social, &interactive(app), action).await.map_err(|f| f.code())?;
    if changes {
        publish(app, &state.social);
    }
    Ok(answer)
}
