//! Test only: a fake Friends server (Supabase PostgREST RPC, the contract in `social.rs`) and a fake Mojang session server
//! (`join`) on one loopback port. Mojang is simulated as it behaves for this: `join` accepts an access token only for the
//! profile it belongs to and remembers who joined which server id; `flint_social_signin` accepts only that player.
//! The Flint Store's Friends routes are on the same port: `POST /api/v1/social/<action>/` (the same RPC, then what the
//! Store tells each player) and the live hub `GET /api/v1/social/live/?uuid=…` (a websocket, `live`).
use crate::core::random_hex;
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use url::Url;

pub const KEY: &str = "sb_publishable_test-key";

pub fn dashed(uuid: &str) -> String {
    let h = uuid.replace('-', "").to_ascii_lowercase();
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + i64::from(m <= 2), m, d)
}

/// An RFC 3339 time as Postgres writes it.
pub fn rfc3339(secs: i64) -> String {
    let (y, m, d) = civil_from_days(secs.div_euclid(86_400));
    let rem = secs.rem_euclid(86_400);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.123456+00:00", rem / 3600, rem % 3600 / 60, rem % 60)
}

fn now() -> i64 {
    crate::core::now_secs() as i64
}

pub struct FakePlayer {
    pub name: String,
    pub status: String,
    pub allow_requests: bool,
    /// Has a live session (what friends see as present).
    pub online: bool,
    pub in_game: bool,
    pub last_seen: i64,
    /// Who sees when they were last online, and who may send them gifts (a server with those settings: World::privacy).
    pub last_seen_to: String,
    pub gifts: String,
}

pub struct FakeMessage {
    pub id: i64,
    pub from: String,
    pub to: String,
    pub body: String,
    pub sensitive: bool,
    pub at: i64,
    pub read: bool,
    /// The sender's id for it (a retry with the same one answers this message).
    pub client_id: Option<String>,
}

#[derive(Default)]
pub struct World {
    /// The Friends functions aren't on the server yet: every RPC answers PostgREST's 404 (PGRST202).
    pub missing: bool,
    /// The server has the privacy settings (last seen, gifts): its `me` carries them and its settings function takes them.
    /// Without them, a settings change naming them is refused whole (PostgREST matches functions by their arguments).
    pub privacy: bool,
    /// The server refuses every privacy value (as it refuses one it doesn't know: `invalid`).
    pub refuse_privacy: bool,
    /// Connections are dropped unanswered, like a computer with no network.
    pub offline: bool,
    /// Minecraft access tokens Mojang knows: token → (UUID without dashes, name).
    pub mojang_tokens: HashMap<String, (String, String)>,
    /// The next this-many joins are answered 503, as Mojang's session server answers in a burst.
    pub mojang_busy: u32,
    pub joins: HashMap<String, (String, String)>,
    /// Sign-in challenges handed out and not used yet: challenge id → server id.
    pub challenges: HashMap<String, String>,
    /// The next sign-in finds its challenge gone (as one that ran out while Mojang was slow).
    pub lose_next_challenge: bool,
    /// Session token → (player, expired).
    pub sessions: HashMap<String, (String, bool)>,
    /// By UUID without dashes.
    pub players: BTreeMap<String, FakePlayer>,
    /// Friend pairs (smaller UUID first) → (since, who pinned the other).
    pub friendships: BTreeMap<(String, String), (i64, BTreeSet<String>)>,
    /// Request id → (from, to, at).
    pub requests: BTreeMap<i64, (String, String, i64)>,
    pub messages: Vec<FakeMessage>,
    pub next_id: i64,
    /// Friends a player may have (the server's 200).
    pub max_friends: usize,
    /// Functions answering `rate_limited` for now.
    pub limited: BTreeSet<String>,
    /// Session tokens in the order they were made (at most 10 per player; the oldest ends).
    pub session_order: Vec<String>,
    /// Every request: "flint_social_state ok", "join 204", "flint_friend_request not_on_flint", "store message_send ok",
    /// "store message_send 404"; and the live hub's: "live open", "live auth", "live ping", "live typing <to>".
    pub log: Vec<String>,
    /// The Store's write routes and live socket answer 404 (not deployed yet).
    pub store_missing: bool,
    /// The hub refuses every session (close 4001).
    pub live_refuse: bool,
    /// The hub stops answering pings (a connection that died without closing).
    pub live_silent: bool,
    /// Live connections: (id, player, where its frames go).
    pub live: Vec<(u64, String, tokio::sync::mpsc::UnboundedSender<HubOut>)>,
    pub next_conn: u64,
    /// Requests for the live socket, answered or not (when `offline` too).
    pub live_attempts: usize,
    /// Added before each HTTP answer and each pushed frame, to stand for the network (ms).
    pub http_delay_ms: u64,
    pub push_delay_ms: u64,
}

/// What the hub sends one connection.
#[derive(Debug)]
pub enum HubOut {
    Text(String),
    Close(u16),
    /// The TCP connection just goes (no close frame).
    Cut,
}

fn pair(a: &str, b: &str) -> (String, String) {
    if a < b {
        (a.to_owned(), b.to_owned())
    } else {
        (b.to_owned(), a.to_owned())
    }
}

fn ok(mut value: Value) -> Value {
    value["ok"] = json!(true);
    value
}

fn no(code: &str) -> Value {
    json!({ "ok": false, "error": code })
}

impl World {
    pub fn add_mojang_account(&mut self, token: &str, uuid: &str, name: &str) {
        self.mojang_tokens.insert(token.into(), (uuid.replace('-', ""), name.into()));
    }

    pub fn expire_sessions(&mut self) {
        for session in self.sessions.values_mut() {
            session.1 = true;
        }
    }

    pub fn count(&self, line: &str) -> usize {
        self.log.iter().filter(|l| l.as_str() == line).count()
    }

    pub fn calls(&self, name: &str) -> usize {
        self.log.iter().filter(|l| l.split(' ').next() == Some(name)).count()
    }

    pub fn set_online(&mut self, uuid: &str, online: bool) {
        if let Some(player) = self.players.get_mut(uuid) {
            player.online = online;
            player.last_seen = now() - 120;
        }
    }

    fn id(&mut self) -> i64 {
        self.next_id += 1;
        self.next_id
    }

    fn friends_of(&self, me: &str) -> Vec<String> {
        self.friendships.keys().filter_map(|(a, b)| if a == me { Some(b.clone()) } else if b == me { Some(a.clone()) } else { None }).collect()
    }

    fn are_friends(&self, a: &str, b: &str) -> bool {
        self.friendships.contains_key(&pair(a, b))
    }

    fn me_json(&self, uuid: &str) -> Value {
        let p = &self.players[uuid];
        let mut me = json!({ "uuid": dashed(uuid), "name": p.name, "status": p.status, "allow_requests": p.allow_requests });
        if self.privacy {
            me["last_seen"] = json!(p.last_seen_to);
            me["gifts"] = json!(p.gifts);
        }
        me
    }

    fn message_json(m: &FakeMessage) -> Value {
        json!({ "id": m.id, "from": dashed(&m.from), "to": dashed(&m.to), "body": m.body, "sensitive": m.sensitive, "at": rfc3339(m.at), "read": m.read })
    }

    fn request_json(&self, id: i64, other: &str, at: i64) -> Value {
        json!({ "id": id, "uuid": dashed(other), "name": self.players.get(other).map(|p| p.name.clone()).unwrap_or_default(), "at": rfc3339(at) })
    }

    /// `f` as `me`'s friends list shows them.
    fn friend_json(&self, me: &str, f: &str) -> Value {
        let p = &self.players[f];
        let (since, pins) = &self.friendships[&pair(me, f)];
        let status = self.status_of(f);
        let unread = self.messages.iter().filter(|m| m.from == f && m.to == me && !m.read).count();
        json!({ "uuid": dashed(f), "name": p.name, "status": status, "in_game": p.in_game && status != "offline", "last_seen": rfc3339(p.last_seen), "pinned": pins.contains(me), "since": rfc3339(*since), "unread": unread })
    }

    fn status_of(&self, uuid: &str) -> &str {
        let p = &self.players[uuid];
        if p.online && p.status != "invisible" {
            p.status.as_str()
        } else {
            "offline"
        }
    }

    // ---------- the Store's live hub ----------

    /// Sends `event` to every live connection of `player`.
    pub fn push(&mut self, player: &str, event: Value) {
        let text = event.to_string();
        for (_, _, out) in self.live.iter().filter(|(_, p, _)| p == player) {
            let _ = out.send(HubOut::Text(text.clone()));
        }
    }

    /// Ends every live connection: with a close frame (`Some(code)`), or by cutting the connection.
    pub fn end_live(&mut self, code: Option<u16>) {
        for (_, _, out) in self.live.drain(..) {
            let _ = out.send(code.map(HubOut::Close).unwrap_or(HubOut::Cut));
        }
    }

    pub fn live_count(&self, player: &str) -> usize {
        self.live.iter().filter(|(_, p, _)| p == player).count()
    }

    /// A Store write route: the same RPC, then what the Store tells each player.
    fn store_write(&mut self, action: &str, args: &Value) -> Value {
        let rpc = match action {
            "message_send" => "flint_message_send",
            "message_read" => "flint_message_read",
            "friend_request" => "flint_friend_request",
            "friend_respond" => "flint_friend_respond",
            "friend_cancel" => "flint_friend_cancel",
            "friend_remove" => "flint_friend_remove",
            "settings" => "flint_social_settings",
            _ => return json!({ "code": "PGRST202" }),
        };
        let me = self.sessions.get(args["p_token"].as_str().unwrap_or_default()).map(|s| s.0.clone()).unwrap_or_default();
        let other = |key: &str| args[key].as_str().unwrap_or_default().replace('-', "").to_ascii_lowercase();
        // What the change is about, looked at before it is made.
        let request = args["p_request_id"].as_i64().and_then(|id| self.requests.get(&id).cloned().map(|r| (id, r)));
        let before: std::collections::BTreeSet<i64> = self.requests.keys().copied().collect();
        let answer = self.rpc(rpc, args);
        if answer["ok"] != true {
            return answer;
        }
        match action {
            "message_send" => {
                let message = &answer["message"];
                let to = message["to"].as_str().unwrap_or_default().replace('-', "");
                let event = json!({ "type": "message", "message": message });
                self.push(&to, event.clone());
                self.push(&me, event);
            }
            "message_read" => {
                let (with, up_to) = (other("p_with"), args["p_up_to"].clone());
                self.push(&me, json!({ "type": "read", "with": dashed(&with), "up_to": up_to }));
                self.push(&with, json!({ "type": "read", "with": dashed(&me), "up_to": up_to, "by": dashed(&me) }));
            }
            "friend_request" if answer["result"] == "sent" => {
                if let Some((id, (from, to, at))) = self.requests.iter().find(|(id, _)| !before.contains(id)).map(|(id, r)| (*id, r.clone())) {
                    self.push(&to, json!({ "type": "request", "request": self.request_json(id, &from, at) }));
                    self.push(&from, json!({ "type": "request", "direction": "outgoing", "request": self.request_json(id, &to, at) }));
                }
            }
            "friend_request" => {
                // They had asked already: friends now.
                let gone: Vec<i64> = before.iter().copied().filter(|id| !self.requests.contains_key(id)).collect();
                for id in &gone {
                    self.push(&me, json!({ "type": "request_gone", "id": id }));
                }
                if let Some(target) = self.friends_of(&me).into_iter().find(|f| self.players[f].name.eq_ignore_ascii_case(args["p_name"].as_str().unwrap_or_default())) {
                    for id in &gone {
                        self.push(&target, json!({ "type": "request_gone", "id": id }));
                    }
                    let (mine, theirs) = (self.friend_json(&me, &target), self.friend_json(&target, &me));
                    self.push(&me, json!({ "type": "friend_added", "friend": mine }));
                    self.push(&target, json!({ "type": "friend_added", "friend": theirs }));
                }
            }
            "friend_respond" | "friend_cancel" => {
                if let Some((id, (from, to, _))) = request {
                    self.push(&from, json!({ "type": "request_gone", "id": id }));
                    self.push(&to, json!({ "type": "request_gone", "id": id }));
                    if self.are_friends(&from, &to) {
                        let (a, b) = (self.friend_json(&from, &to), self.friend_json(&to, &from));
                        self.push(&from, json!({ "type": "friend_added", "friend": a }));
                        self.push(&to, json!({ "type": "friend_added", "friend": b }));
                    }
                }
            }
            "friend_remove" => {
                let them = other("p_uuid");
                self.push(&me, json!({ "type": "friend_removed", "uuid": dashed(&them) }));
                self.push(&them, json!({ "type": "friend_removed", "uuid": dashed(&me) }));
            }
            "settings" => {
                let status = self.status_of(&me).to_owned();
                let in_game = self.players[&me].in_game;
                for friend in self.friends_of(&me) {
                    self.push(&friend, json!({ "type": "presence", "uuid": dashed(&me), "status": status, "in_game": in_game }));
                }
            }
            _ => {}
        }
        answer
    }

    fn befriend(&mut self, a: &str, b: &str) {
        self.friendships.insert(pair(a, b), (now(), BTreeSet::new()));
        self.requests.retain(|_, (from, to, _)| !((from == a && to == b) || (from == b && to == a)));
    }

    fn rpc(&mut self, name: &str, args: &Value) -> Value {
        // The client-picked server id sign-in, as Backend 20261104006400 left it.
        if name == "flint_social_signin" {
            return no("update_required");
        }
        if name == "flint_signin_challenge" {
            if args["p_purpose"].as_str() != Some("social") && args["p_purpose"].as_str() != Some("presence") {
                return no("invalid");
            }
            let hex = random_hex(16);
            let id = format!("{}-{}-{}-{}-{}", &hex[..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..32]);
            let server_id = random_hex(20);
            self.challenges.insert(id.clone(), server_id.clone());
            return ok(json!({ "challenge": id, "server_id": server_id, "expires_at": rfc3339(now() + 120) }));
        }
        if name == "flint_social_signin_v2" {
            let uuid = args["p_uuid"].as_str().unwrap_or_default().replace('-', "");
            let username = args["p_username"].as_str().unwrap_or_default();
            let taken = self.challenges.remove(args["p_challenge"].as_str().unwrap_or_default());
            if std::mem::take(&mut self.lose_next_challenge) {
                return no("challenge");
            }
            let Some(server_id) = taken else {
                return no("challenge");
            };
            return match self.joins.remove(&server_id) {
                Some((joined, joined_name)) if joined == uuid && joined_name == username => {
                    let player = self.players.entry(uuid.clone()).or_insert(FakePlayer {
                        name: username.into(),
                        status: "online".into(),
                        allow_requests: true,
                        online: true,
                        in_game: false,
                        last_seen: now(),
                        last_seen_to: "friends".into(),
                        gifts: "everyone".into(),
                    });
                    player.online = true;
                    player.name = username.into();
                    let token = random_hex(32);
                    self.sessions.insert(token.clone(), (uuid.clone(), false));
                    self.session_order.push(token.clone());
                    let mine: Vec<String> = self.session_order.iter().filter(|t| self.sessions.get(*t).is_some_and(|s| s.0 == uuid)).cloned().collect();
                    for old in mine.iter().take(mine.len().saturating_sub(10)) {
                        self.sessions.remove(old);
                    }
                    // A week, as the server's social_session_days.
                    ok(json!({ "token": token, "expires_at": rfc3339(now() + 7 * 86_400), "me": self.me_json(&uuid) }))
                }
                _ => no("unverified"),
            };
        }
        let token = args["p_token"].as_str().unwrap_or_default();
        let me = match self.sessions.get(token) {
            Some((_, true)) => return no("expired"),
            Some((uuid, false)) => uuid.clone(),
            None => return no("expired"),
        };
        if self.limited.contains(name) {
            return no("rate_limited");
        }
        let other = |key: &str| args[key].as_str().unwrap_or_default().replace('-', "").to_ascii_lowercase();
        match name {
            "flint_social_signout" => {
                self.sessions.remove(token);
                if let Some(p) = self.players.get_mut(&me) {
                    p.online = false;
                }
                ok(json!({}))
            }
            "flint_social_state" => {
                let after = args["p_after_message"].as_i64().unwrap_or(0);
                // Pinned first, then by name.
                let mut order = self.friends_of(&me);
                order.sort_by_key(|f| (!self.friendships[&pair(&me, f)].1.contains(&me), self.players[f].name.to_ascii_lowercase()));
                let friends: Vec<Value> = order.iter().map(|f| self.friend_json(&me, f)).collect();
                // Newest first, 200 at most.
                let incoming: Vec<Value> = self.requests.iter().rev().filter(|(_, r)| r.1 == me).take(200).map(|(id, r)| self.request_json(*id, &r.0, r.2)).collect();
                let outgoing: Vec<Value> = self.requests.iter().rev().filter(|(_, r)| r.0 == me).take(200).map(|(id, r)| self.request_json(*id, &r.1, r.2)).collect();
                // After the cursor: the next 200, oldest first. A first sync (cursor 0): the newest 200 (Backend 6500).
                let mine: Vec<&FakeMessage> = self.messages.iter().filter(|m| m.id > after && (m.from == me || m.to == me)).collect();
                let page: &[&FakeMessage] = if after == 0 { &mine[mine.len().saturating_sub(200)..] } else { &mine[..mine.len().min(200)] };
                let messages: Vec<Value> = page.iter().map(|m| Self::message_json(m)).collect();
                ok(json!({ "now": rfc3339(now()), "me": self.me_json(&me), "friends": friends, "incoming": incoming, "outgoing": outgoing, "messages": messages }))
            }
            "flint_social_settings" => {
                let named = !args["p_last_seen"].is_null() || !args["p_gifts"].is_null();
                if named && !self.privacy {
                    return json!({ "code": "PGRST202" });
                }
                let seen_ok = args["p_last_seen"].is_null() || args["p_last_seen"].as_str().is_some_and(|v| ["friends", "nobody"].contains(&v) && !self.refuse_privacy);
                let gifts_ok = args["p_gifts"].is_null() || args["p_gifts"].as_str().is_some_and(|v| ["everyone", "friends", "nobody"].contains(&v) && !self.refuse_privacy);
                if !seen_ok || !gifts_ok {
                    return no("invalid");
                }
                let player = self.players.get_mut(&me).unwrap();
                if let Some(seen) = args["p_last_seen"].as_str() {
                    player.last_seen_to = seen.into();
                }
                if let Some(gifts) = args["p_gifts"].as_str() {
                    player.gifts = gifts.into();
                }
                if let Some(status) = args["p_status"].as_str() {
                    player.status = status.into();
                }
                if let Some(allow) = args["p_allow_requests"].as_bool() {
                    player.allow_requests = allow;
                }
                ok(json!({ "me": self.me_json(&me) }))
            }
            "flint_social_search" => {
                let query = args["p_query"].as_str().unwrap_or_default().to_ascii_lowercase();
                // The exact name first, then shorter names, then A to Z.
                let mut found: Vec<(&String, &FakePlayer)> = self.players.iter().filter(|(_, p)| p.name.to_ascii_lowercase().starts_with(&query)).collect();
                found.sort_by_key(|(_, p)| (p.name.to_ascii_lowercase() != query, p.name.len(), p.name.to_ascii_lowercase()));
                let players: Vec<Value> = found
                    .into_iter()
                    .map(|(uuid, p)| {
                        let relation = if *uuid == me {
                            "self"
                        } else if self.are_friends(&me, uuid) {
                            "friend"
                        } else if self.requests.values().any(|r| r.0 == *uuid && r.1 == me) {
                            "incoming"
                        } else if self.requests.values().any(|r| r.0 == me && r.1 == *uuid) {
                            "outgoing"
                        } else {
                            "none"
                        };
                        json!({ "uuid": dashed(uuid), "name": p.name, "relation": relation, "accepts": p.allow_requests })
                    })
                    .collect();
                ok(json!({ "players": players }))
            }
            "flint_friend_request" => {
                let wanted = args["p_name"].as_str().unwrap_or_default().to_ascii_lowercase();
                let Some(target) = self.players.iter().find(|(_, p)| p.name.to_ascii_lowercase() == wanted).map(|(u, _)| u.clone()) else {
                    return no("not_on_flint");
                };
                if target == me {
                    return no("self");
                }
                if self.are_friends(&me, &target) {
                    return no("already_friends");
                }
                if self.requests.values().any(|r| r.0 == me && r.1 == target) {
                    return no("already_sent");
                }
                if self.requests.values().any(|r| r.0 == target && r.1 == me) {
                    self.befriend(&me, &target);
                    return ok(json!({ "result": "accepted" }));
                }
                let full = self.friends_of(&target).len() >= self.max_friends || self.requests.values().filter(|r| r.1 == target).count() >= 200;
                if !self.players[&target].allow_requests || full {
                    return no("not_accepting");
                }
                let id = self.id();
                self.requests.insert(id, (me, target, now()));
                ok(json!({ "result": "sent" }))
            }
            "flint_friend_respond" => {
                let (Some(id), Some(accept)) = (args["p_request_id"].as_i64(), args["p_accept"].as_bool()) else { return no("invalid") };
                match self.requests.get(&id).cloned() {
                    Some((from, to, _)) if to == me => {
                        // Accepting keeps the request when either side is full.
                        if accept && self.friends_of(&me).len() >= self.max_friends {
                            return no("too_many");
                        }
                        if accept && self.friends_of(&from).len() >= self.max_friends {
                            return no("not_accepting");
                        }
                        self.requests.remove(&id);
                        if accept {
                            self.befriend(&from, &to);
                        }
                        ok(json!({}))
                    }
                    _ => no("not_found"),
                }
            }
            "flint_friend_cancel" => {
                let id = args["p_request_id"].as_i64().unwrap_or(-1);
                match self.requests.get(&id) {
                    Some((from, _, _)) if *from == me => {
                        self.requests.remove(&id);
                        ok(json!({}))
                    }
                    _ => no("not_found"),
                }
            }
            "flint_friend_remove" => {
                self.friendships.remove(&pair(&me, &other("p_uuid")));
                ok(json!({}))
            }
            "flint_friend_pin" => {
                let friend = other("p_uuid");
                let Some((_, pins)) = self.friendships.get_mut(&pair(&me, &friend)) else { return no("not_friends") };
                if args["p_pinned"].as_bool() == Some(true) {
                    pins.insert(me);
                } else {
                    pins.remove(&me);
                }
                ok(json!({}))
            }
            "flint_message_send" => {
                let to = other("p_to");
                if !self.are_friends(&me, &to) {
                    return no("not_friends");
                }
                let Some(body) = args["p_body"].as_str() else { return no("invalid") };
                // Control characters are dropped, then the length is checked.
                let body = body.chars().filter(|c| !c.is_control() || *c == '\n').collect::<String>().trim().to_owned();
                if body.is_empty() {
                    return no("empty");
                }
                if body.chars().count() > 1000 {
                    return no("too_long");
                }
                // The server forces the flag for what looks like an email address.
                let sensitive = args["p_sensitive"].as_bool() == Some(true) || (body.contains('@') && body.contains('.'));
                let client_id = args["p_client_id"].as_str().map(str::to_owned);
                if let Some(stored) = client_id.as_ref().and_then(|cid| self.messages.iter().find(|m| m.from == me && m.client_id.as_ref() == Some(cid))) {
                    let mut value = Self::message_json(stored);
                    value["client_id"] = json!(stored.client_id);
                    return ok(json!({ "message": value }));
                }
                let id = self.id();
                let message = FakeMessage { id, from: me, to, body, sensitive, at: now(), read: false, client_id };
                let mut value = Self::message_json(&message);
                value["client_id"] = json!(message.client_id);
                self.messages.push(message);
                ok(json!({ "message": value }))
            }
            "flint_message_history" => {
                let with = other("p_with");
                let before = args["p_before"].as_i64().unwrap_or(i64::MAX);
                let limit = args["p_limit"].as_u64().unwrap_or(50) as usize;
                let between: Vec<&FakeMessage> = self.messages.iter().filter(|m| m.id < before && ((m.from == me && m.to == with) || (m.from == with && m.to == me))).collect();
                let page: Vec<Value> = between[between.len().saturating_sub(limit)..].iter().map(|m| Self::message_json(m)).collect();
                ok(json!({ "messages": page }))
            }
            "flint_message_read" => {
                let with = other("p_with");
                let up_to = args["p_up_to"].as_i64().unwrap_or(0);
                for m in self.messages.iter_mut().filter(|m| m.from == with && m.to == me && m.id <= up_to) {
                    m.read = true;
                }
                ok(json!({}))
            }
            _ => json!({ "code": "PGRST202" }),
        }
    }

    fn handle(&mut self, request: &Request) -> (u16, Value) {
        let body: Value = serde_json::from_slice(&request.body).unwrap_or(Value::Null);
        if request.method == "POST" && request.path == "/session/minecraft/join" {
            if self.mojang_busy > 0 {
                self.mojang_busy -= 1;
                self.log.push("join 503".into());
                return (503, json!({ "path": "/session/minecraft/join" }));
            }
            let token = body["accessToken"].as_str().unwrap_or_default();
            let profile = body["selectedProfile"].as_str().unwrap_or_default();
            return match self.mojang_tokens.get(token) {
                Some((uuid, name)) if uuid == profile => {
                    let joined = (uuid.clone(), name.clone());
                    self.joins.insert(body["serverId"].as_str().unwrap_or_default().into(), joined);
                    self.log.push("join 204".into());
                    (204, Value::Null)
                }
                _ => {
                    self.log.push("join 403".into());
                    (403, json!({ "error": "ForbiddenOperationException" }))
                }
            };
        }
        if let Some(action) = request.path.strip_prefix("/api/v1/social/").and_then(|p| p.strip_suffix('/')) {
            if request.method != "POST" || self.store_missing {
                self.log.push(format!("store {action} 404"));
                return (404, json!({ "error": "not_found" }));
            }
            let answer = self.store_write(action, &body);
            if answer["code"] == "PGRST202" {
                self.log.push(format!("store {action} 404"));
                return (404, answer);
            }
            let outcome = if answer["ok"] == true { "ok".to_owned() } else { answer["error"].as_str().unwrap_or("?").to_owned() };
            self.log.push(format!("store {action} {outcome}"));
            return (200, answer);
        }
        let Some(name) = request.path.strip_prefix("/rest/v1/rpc/") else { return (404, json!({})) };
        let keyed = request.headers.get("apikey").map(String::as_str) == Some(KEY) && request.headers.get("authorization").map(String::as_str) == Some(&format!("Bearer {KEY}"));
        if !keyed {
            self.log.push(format!("{name} 401"));
            return (401, json!({ "message": "Invalid API key" }));
        }
        if self.missing {
            self.log.push(format!("{name} 404"));
            return (404, json!({ "code": "PGRST202", "message": format!("Could not find the function public.{name}") }));
        }
        let answer = self.rpc(name, &body);
        if answer["code"] == "PGRST202" {
            self.log.push(format!("{name} 404"));
            return (404, answer);
        }
        let outcome = if answer["ok"] == true { "ok".to_owned() } else { answer["error"].as_str().unwrap_or("?").to_owned() };
        self.log.push(format!("{name} {outcome}"));
        (200, answer)
    }
}

pub struct Request {
    pub method: String,
    pub path: String,
    pub query: HashMap<String, String>,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

async fn read_request(stream: &mut TcpStream) -> Option<Request> {
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 4096];
    let head_end = loop {
        let n = stream.read(&mut chunk).await.ok()?;
        if n == 0 {
            return None;
        }
        buffer.extend_from_slice(&chunk[..n]);
        if let Some(at) = buffer.windows(4).position(|w| w == b"\r\n\r\n") {
            break at;
        }
        if buffer.len() > 64 * 1024 {
            return None;
        }
    };
    let head = String::from_utf8_lossy(&buffer[..head_end]).to_string();
    let mut lines = head.split("\r\n");
    let mut first = lines.next()?.split(' ');
    let method = first.next()?.to_owned();
    let target = first.next()?.to_owned();
    let headers: HashMap<String, String> = lines.filter_map(|l| l.split_once(':')).map(|(k, v)| (k.trim().to_ascii_lowercase(), v.trim().to_owned())).collect();
    let length: usize = headers.get("content-length").and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut body = buffer[head_end + 4..].to_vec();
    while body.len() < length {
        let n = stream.read(&mut chunk).await.ok()?;
        if n == 0 {
            break;
        }
        body.extend_from_slice(&chunk[..n]);
    }
    let url = Url::parse(&format!("http://fake{target}")).ok()?;
    let query = url.query_pairs().map(|(k, v)| (k.into_owned(), v.into_owned())).collect();
    Some(Request { method, path: url.path().to_owned(), query, headers, body })
}

async fn reply(stream: &mut TcpStream, status: u16, body: &Value) {
    let bytes = if body.is_null() { Vec::new() } else { serde_json::to_vec(body).unwrap() };
    let head = format!("HTTP/1.1 {status} Fake\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", bytes.len());
    let _ = stream.write_all(head.as_bytes()).await;
    let _ = stream.write_all(&bytes).await;
    let _ = stream.shutdown().await;
}

/// The Store's live hub for one connection, as the contract says: `?uuid=` routes it (400 without), the first frame must be
/// `{"type":"auth","token":…}` for a session of exactly that player (else close 4001), then `ready`; `{"type":"ping"}` is
/// answered `{"type":"pong"}` (unless the hub is "silent"), `typing` goes to the friend named, and the world's pushes go out.
async fn live(mut stream: TcpStream, request: Request, world: Arc<Mutex<World>>) {
    use futures_util::{SinkExt, StreamExt};
    use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
    use tokio_tungstenite::tungstenite::protocol::{CloseFrame, Role};
    use tokio_tungstenite::tungstenite::Message as Frame;
    let (missing, push_delay) = {
        let mut w = world.lock().unwrap();
        w.log.push("live open".into());
        (w.store_missing, Duration::from_millis(w.push_delay_ms))
    };
    if missing {
        return reply(&mut stream, 404, &json!({ "error": "not_found" })).await;
    }
    let Some(uuid) = request.query.get("uuid").and_then(|u| crate::store::canonical(u)) else {
        return reply(&mut stream, 400, &json!({ "error": "uuid" })).await;
    };
    let Some(key) = request.headers.get("sec-websocket-key") else {
        return reply(&mut stream, 426, &json!({ "error": "upgrade" })).await;
    };
    let accept = tokio_tungstenite::tungstenite::handshake::derive_accept_key(key.as_bytes());
    let head = format!("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n");
    if stream.write_all(head.as_bytes()).await.is_err() {
        return;
    }
    let mut ws = tokio_tungstenite::WebSocketStream::from_raw_socket(stream, Role::Server, None).await;
    let refuse = |code: u16| Frame::Close(Some(CloseFrame { code: CloseCode::from(code), reason: "expired".into() }));
    let token = match tokio::time::timeout(Duration::from_secs(5), ws.next()).await {
        Ok(Some(Ok(Frame::Text(text)))) => serde_json::from_str::<Value>(text.as_str()).ok().filter(|v| v["type"] == "auth").and_then(|v| v["token"].as_str().map(str::to_owned)),
        Err(_) => {
            let _ = ws.send(refuse(4008)).await;
            return;
        }
        _ => None,
    };
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
    let conn = {
        let mut w = world.lock().unwrap();
        w.log.push("live auth".into());
        let good = token.as_deref().and_then(|t| w.sessions.get(t)).is_some_and(|(player, expired)| *player == uuid && !expired);
        if !good || w.live_refuse {
            None
        } else {
            w.next_conn += 1;
            let id = w.next_conn;
            w.live.push((id, uuid.clone(), tx));
            Some(id)
        }
    };
    let Some(conn) = conn else {
        let _ = ws.send(refuse(4001)).await;
        return;
    };
    let ready = json!({ "type": "ready", "uuid": dashed(&uuid), "server_time": rfc3339(now()) }).to_string();
    if ws.send(Frame::text(ready)).await.is_ok() {
        loop {
            tokio::select! {
                frame = ws.next() => match frame {
                    Some(Ok(Frame::Text(text))) => {
                        let pong = {
                            let mut w = world.lock().unwrap();
                            let typing = serde_json::from_str::<Value>(text.as_str()).ok().filter(|v| v["type"] == "typing");
                            if text.as_str() == r#"{"type":"ping"}"# {
                                w.log.push("live ping".into());
                                !w.live_silent
                            } else if let Some(to) = typing.and_then(|v| v["to"].as_str().and_then(crate::store::canonical)) {
                                w.log.push(format!("live typing {to}"));
                                if w.are_friends(&uuid, &to) {
                                    w.push(&to, json!({ "type": "typing", "uuid": dashed(&uuid) }));
                                }
                                false
                            } else {
                                w.log.push(format!("live other {}", text.as_str()));
                                false
                            }
                        };
                        if pong && ws.send(Frame::text(r#"{"type":"pong"}"#)).await.is_err() {
                            break;
                        }
                    }
                    Some(Ok(Frame::Close(_))) | None | Some(Err(_)) => break,
                    Some(Ok(_)) => {}
                },
                out = rx.recv() => match out {
                    Some(HubOut::Text(text)) => {
                        if !push_delay.is_zero() {
                            tokio::time::sleep(push_delay).await;
                        }
                        if ws.send(Frame::text(text)).await.is_err() {
                            break;
                        }
                    }
                    Some(HubOut::Close(code)) => {
                        let _ = ws.send(refuse(code)).await;
                        break;
                    }
                    Some(HubOut::Cut) | None => break,
                },
            }
        }
    }
    world.lock().unwrap().live.retain(|(id, _, _)| *id != conn);
}

async fn serve(mut stream: TcpStream, world: Arc<Mutex<World>>) {
    let Some(request) = read_request(&mut stream).await else { return };
    let socket = request.path == "/api/v1/social/live/" && request.method == "GET";
    let (offline, delay) = {
        let mut w = world.lock().unwrap();
        w.live_attempts += usize::from(socket);
        (w.offline, Duration::from_millis(w.http_delay_ms))
    };
    if offline {
        return; // dropped unanswered
    }
    if socket {
        return live(stream, request, world).await;
    }
    if !delay.is_zero() {
        tokio::time::sleep(delay).await;
    }
    let (status, body) = world.lock().unwrap().handle(&request);
    reply(&mut stream, status, &body).await;
}

pub struct Fake {
    pub addr: SocketAddr,
    pub world: Arc<Mutex<World>>,
}

impl Fake {
    pub async fn start() -> Self {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("the fake server can listen");
        let addr = listener.local_addr().unwrap();
        let world = Arc::new(Mutex::new(World { next_id: 100, max_friends: 200, ..Default::default() }));
        let shared = world.clone();
        tokio::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                tokio::spawn(serve(stream, shared.clone()));
            }
        });
        Self { addr, world }
    }

    pub fn url(&self) -> String {
        format!("http://127.0.0.1:{}", self.addr.port())
    }

    /// The fake as the Flint Store.
    pub fn store(&self) -> crate::store::Endpoints {
        crate::store::Endpoints { store: Url::parse(&format!("{}/", self.url())).unwrap(), mojang: Url::parse(&self.url()).unwrap(), access: None }
    }

    pub fn with<R>(&self, f: impl FnOnce(&mut World) -> R) -> R {
        f(&mut self.world.lock().unwrap())
    }
}
