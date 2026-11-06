//! Test only: a fake Flint Store (the launcher API v1 as the Store implements it in `src/lib/server/api.ts`) and a fake
//! Mojang session server (`join` and `hasJoined`) on one loopback port. The end-to-end test against a real local Store
//! uses only the Mojang half, as the Store's `MOJANG_SESSION_SERVER` in local development.
//!
//! Mojang is simulated the way the real one behaves for this: `join` accepts an access token only for the profile it
//! belongs to and remembers who joined which server id; `hasJoined` names that player only for the right name.
use crate::core::random_hex;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use url::Url;

pub struct FakeSession {
    pub uuid: String,
    pub expires: i64,
    pub revoked: bool,
}

#[derive(Default)]
pub struct Player {
    pub name: String,
    pub version: i64,
    pub balance: i64,
    pub owned: Vec<String>,
    pub equipped: BTreeMap<String, String>,
}

pub struct Cosmetic {
    pub slug: String,
    pub kind: String,
    pub name: String,
    pub asset_id: String,
    pub path: String,
    pub sha256: String,
    /// `asset.animation` as the Store sends it (with each strip's `url`), for an animated cape.
    pub animation: Option<Value>,
}

#[derive(Default)]
pub struct World {
    pub base: String,
    /// Connections are dropped unanswered, like a computer with no network.
    pub offline: bool,
    /// Minecraft access tokens Mojang knows: token → (UUID without dashes, name).
    pub mojang_tokens: HashMap<String, (String, String)>,
    /// Who joined which server id.
    pub joins: HashMap<String, (String, String)>,
    pub challenges: HashMap<String, String>,
    pub sessions: HashMap<String, FakeSession>,
    /// By dashed UUID.
    pub players: HashMap<String, Player>,
    pub cosmetics: BTreeMap<String, Cosmetic>,
    /// Files served as they are (textures), by path.
    pub files: HashMap<String, Vec<u8>>,
    /// Browser sign-in codes: code → the account that approved it.
    pub links: HashMap<String, Option<String>>,
    pub poll_after_seconds: u64,
    /// A Store that answers `verify` for this player whoever joined (to test that Flint checks the answer).
    pub verify_as: Option<(String, String)>,
    /// A Store that answers `inventory` for this player (dashed) whoever asks.
    pub inventory_as: Option<String>,
    /// Every request: "GET /api/v1/me/inventory/ 304".
    pub log: Vec<String>,

    // ---- Discord connections and community rewards (Store docs/connections-rewards-api-v1.md) ----
    /// The Store has the connections, rewards and community catalog routes and the inventory's new fields. Off by default,
    /// like today's production Store (every one of those routes is a 404). [`World::enable_community`] turns it all on.
    pub community: bool,
    /// The Store has a Discord application (otherwise `discord_unconfigured`).
    pub discord_configured: bool,
    /// Flint Admin's switches.
    pub link_enabled: bool,
    pub rewards_enabled: bool,
    pub discord_rewards_enabled: bool,
    pub invite_url: String,
    /// Discord can't be reached: the last answer still counts (grace) until `grace_over`.
    pub discord_down: bool,
    pub grace_over: bool,
    /// Linked Discord accounts, by dashed Minecraft UUID.
    pub discord: HashMap<String, FakeDiscord>,
    pub rewards: Vec<FakeReward>,
    pub attempts: HashMap<String, FakeAttempt>,
    /// What each new attempt answers, one state per poll (the last one stays).
    pub attempt_script: Vec<String>,
    /// The Discord account the player approves on Discord's page.
    pub link_as: Option<FakeDiscord>,
    /// A Store that sends this as `authorize_url` (to test that Flint checks it).
    pub authorize_override: Option<String>,
    /// Last "Check again" per account (seconds), and link starts (the Store's limits).
    pub rechecked_at: HashMap<String, i64>,
    pub starts: HashMap<String, Vec<i64>>,
    /// How a permanent item was got (`admin_gift`, `giveaway`…), by (dashed UUID, cosmetic id); `purchase` otherwise.
    pub granted_via: HashMap<(String, String), String>,
    /// Accounts that got the first-launch reward (once, ever).
    pub first_launch_claimed: HashSet<String>,
}

#[derive(Clone, Debug)]
pub struct FakeDiscord {
    pub id: String,
    pub username: String,
    pub global_name: Option<String>,
    pub avatar_url: Option<String>,
    pub member: bool,
    pub pending: bool,
    pub booster: bool,
}

impl FakeDiscord {
    pub fn new(id: &str, username: &str) -> Self {
        Self { id: id.into(), username: username.into(), global_name: None, avatar_url: None, member: false, pending: false, booster: false }
    }
}

pub struct FakeReward {
    pub cosmetic_id: String,
    pub requirement: String,
}

pub struct FakeAttempt {
    pub uuid: String,
    pub at: usize,
    pub script: Vec<String>,
}

pub fn dashed(uuid: &str) -> String {
    let h = uuid.replace('-', "").to_ascii_lowercase();
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

fn uuid_v4() -> String {
    let mut hex = random_hex(16).into_bytes();
    hex[12] = b'4';
    hex[16] = b'8';
    dashed(&String::from_utf8(hex).unwrap())
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
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.000+00:00", rem / 3600, rem % 3600 / 60, rem % 60)
}

fn now() -> i64 {
    crate::core::now_secs() as i64
}

impl World {
    pub fn add_mojang_account(&mut self, token: &str, uuid: &str, name: &str) {
        self.mojang_tokens.insert(token.into(), (uuid.replace('-', ""), name.into()));
    }

    /// A cosmetic with a texture; returns its id.
    pub fn add_cosmetic(&mut self, slug: &str, kind: &str, png: &[u8]) -> String {
        let id = uuid_v4();
        let path = format!("/assets/{slug}.png");
        self.files.insert(path.clone(), png.to_vec());
        let sha256 = hex::encode(Sha256::digest(png));
        self.cosmetics.insert(id.clone(), Cosmetic { slug: slug.into(), kind: kind.into(), name: format!("TEST · {slug}"), asset_id: uuid_v4(), path, sha256, animation: None });
        id
    }

    /// An animated cape (Store docs/LAUNCHER-API.md "Animated capes"): `poster` as its texture, `strips` (PNG bytes, frames in
    /// each) served at /assets/animated/<sha256>.png, at `scale` px per unit with these frame durations; returns its id.
    pub fn add_animated_cape(&mut self, slug: &str, poster: &[u8], scale: u32, durations: &[u32], strips: &[(Vec<u8>, u32)]) -> String {
        let id = self.add_cosmetic(slug, "cape", poster);
        let (width, height) = (22 * scale, 17 * scale);
        let listed: Vec<Value> = strips
            .iter()
            .map(|(png, frames)| {
                let sha256 = hex::encode(Sha256::digest(png));
                let path = format!("/assets/animated/{sha256}.png");
                self.files.insert(path.clone(), png.clone());
                json!({ "path": format!("animated/{sha256}.png"), "url": format!("{}{}", self.base, path), "sha256": sha256, "bytes": png.len(), "frames": frames })
            })
            .collect();
        let animation = json!({
            "v": 1, "layout": "cape-atlas-22x17", "width": width, "height": height, "scale": scale,
            "frame_count": durations.len(), "durations_ms": durations, "duration_ms": durations.iter().sum::<u32>(),
            "frames_per_strip": (2048 / height).max(1), "strips": listed,
            "source": { "format": "avif", "sha256": "0".repeat(64), "bytes": 1 }
        });
        self.cosmetics.get_mut(&id).unwrap().animation = Some(animation);
        id
    }

    fn player(&mut self, uuid: &str) -> &mut Player {
        self.players.entry(dashed(uuid)).or_default()
    }

    /// A purchase confirmed elsewhere (the Store website, Tebex).
    pub fn grant(&mut self, uuid: &str, cosmetic: &str) {
        let player = self.player(uuid);
        player.owned.push(cosmetic.into());
        player.version += 1;
    }

    /// A refund or chargeback: the item goes, and with it what was worn.
    pub fn revoke(&mut self, uuid: &str, cosmetic: &str) {
        let player = self.player(uuid);
        player.owned.retain(|c| c != cosmetic);
        player.equipped.retain(|_, c| c != cosmetic);
        player.version += 1;
    }

    /// A permanent grant made some other way than a purchase (`admin_gift`, `giveaway`, `event`).
    pub fn grant_via(&mut self, uuid: &str, cosmetic: &str, via: &str) {
        self.granted_via.insert((dashed(uuid), cosmetic.into()), via.into());
        self.grant(uuid, cosmetic);
    }

    /// A Store with every part of the contract, switched on in Admin, with Discord configured.
    pub fn enable_community(&mut self) {
        self.community = true;
        self.discord_configured = true;
        self.link_enabled = true;
        self.rewards_enabled = true;
        self.discord_rewards_enabled = true;
        self.invite_url = "https://discord.gg/flint".into();
        self.attempt_script = vec!["waiting".into(), "waiting".into(), "linked".into()];
    }

    /// A listed reward cape (`discord_member`, `discord_booster`, `first_launch`, `event`); returns its id.
    pub fn add_reward(&mut self, slug: &str, requirement: &str, png: &[u8]) -> String {
        let id = self.add_cosmetic(slug, "cape", png);
        self.rewards.push(FakeReward { cosmetic_id: id.clone(), requirement: requirement.into() });
        id
    }

    /// The account's Discord standing: (linked, member, booster, pending, state).
    fn standing(&self, uuid: &str) -> (bool, bool, bool, bool, &'static str) {
        let Some(d) = self.discord.get(uuid) else { return (false, false, false, false, "disconnected") };
        let counts = !self.grace_over;
        let state = match (self.discord_down, self.grace_over) {
            (true, true) => "unavailable",
            (true, false) => "grace",
            _ => "verified",
        };
        (true, counts && d.member && !d.pending, counts && d.member && d.booster, d.member && d.pending, state)
    }

    /// Capes the account may use now through a Discord reward.
    fn conditional_ids(&self, uuid: &str) -> Vec<String> {
        if !(self.community && self.rewards_enabled && self.discord_rewards_enabled) {
            return Vec::new();
        }
        let (linked, member, booster, ..) = self.standing(uuid);
        self.rewards
            .iter()
            .filter(|r| linked && ((r.requirement == "discord_member" && member) || (r.requirement == "discord_booster" && booster)))
            .map(|r| r.cosmetic_id.clone())
            .collect()
    }

    fn owns(&self, uuid: &str, cosmetic: &str) -> bool {
        self.players.get(uuid).is_some_and(|p| p.owned.iter().any(|c| c == cosmetic)) || self.conditional_ids(uuid).iter().any(|c| c == cosmetic)
    }

    /// After a Discord change: a worn cape that no longer counts is taken off, and the account's version moves on.
    pub fn settle(&mut self, uuid: &str) {
        let uuid = dashed(uuid);
        let worn: Vec<(String, String)> = self.players.get(&uuid).map(|p| p.equipped.iter().map(|(s, c)| (s.clone(), c.clone())).collect()).unwrap_or_default();
        let gone: Vec<String> = worn.into_iter().filter(|(_, c)| !self.owns(&uuid, c)).map(|(s, _)| s).collect();
        let player = self.player(&uuid);
        for slot in gone {
            player.equipped.remove(&slot);
        }
        player.version += 1;
    }

    fn link_json(&self, uuid: &str) -> Value {
        match self.discord.get(uuid) {
            None => Value::Null,
            Some(d) => {
                let state = if self.discord_down { "unavailable" } else if d.member && d.pending { "pending" } else { "verified" };
                json!({
                    "user": { "id": d.id, "username": d.username, "global_name": d.global_name, "avatar_url": d.avatar_url },
                    "linked_at": rfc3339(now() - 60),
                    "guild": { "state": state, "member": d.member && !d.pending, "booster": d.booster, "checked_at": rfc3339(now()), "verified_at": rfc3339(now() - 30) }
                })
            }
        }
    }

    fn connections_json(&self, uuid: &str) -> Value {
        let mut body = json!({ "schema": 1, "enabled": self.link_enabled && self.discord_configured, "invite_url": self.invite_url, "discord": self.link_json(uuid) });
        if !self.discord_configured {
            body["reason"] = json!("discord_unconfigured");
        }
        body
    }

    fn rewards_json(&self, uuid: &str) -> Value {
        let (linked, member, booster, pending, state) = self.standing(uuid);
        let conditional = self.conditional_ids(uuid);
        let listed: Vec<Value> = self
            .listed_rewards()
            .map(|r| {
                let owned = self.players.get(uuid).is_some_and(|p| p.owned.contains(&r.cosmetic_id));
                let discord = r.requirement.starts_with("discord_");
                let status = if owned {
                    "owned"
                } else if conditional.contains(&r.cosmetic_id) {
                    "eligible"
                } else if discord && state == "unavailable" {
                    "unavailable"
                } else {
                    "locked"
                };
                let action = match () {
                    _ if status != "locked" => json!({ "kind": "none" }),
                    _ if discord && !linked => json!({ "kind": "connect_discord" }),
                    _ if discord && pending => json!({ "kind": "complete_screening", "url": self.invite_url }),
                    _ if r.requirement == "discord_member" || (r.requirement == "discord_booster" && !member) => json!({ "kind": "join_discord", "url": self.invite_url }),
                    _ if r.requirement == "discord_booster" => json!({ "kind": "boost_discord", "url": self.invite_url }),
                    _ if r.requirement == "first_launch" => json!({ "kind": "launch" }),
                    _ => json!({ "kind": "none" }),
                };
                json!({ "cosmetic_id": r.cosmetic_id, "requirement": r.requirement, "permanence": if discord { "conditional" } else { "permanent" }, "status": status, "action": action })
            })
            .collect();
        json!({ "schema": 1, "discord": { "linked": linked, "member": member, "booster": booster, "pending": pending, "state": state }, "rewards": listed })
    }

    fn listed_rewards(&self) -> impl Iterator<Item = &FakeReward> {
        self.rewards.iter().filter(|r| self.rewards_enabled && (!r.requirement.starts_with("discord_") || self.discord_rewards_enabled))
    }

    fn catalog_json(&self) -> Value {
        let rewards: Vec<Value> = self
            .listed_rewards()
            .enumerate()
            .filter_map(|(i, r)| {
                let c = self.cosmetics.get(&r.cosmetic_id)?;
                let label = match r.requirement.as_str() {
                    "discord_member" => "Member of the Flint Discord server",
                    "discord_booster" => "Boosting the Flint Discord server",
                    "first_launch" => "Sign in with the Flint launcher",
                    _ => "Community event reward",
                };
                Some(json!({
                    "cosmetic_id": r.cosmetic_id, "slug": c.slug, "kind": c.kind, "name": c.name, "description": null, "category": "community",
                    "requirement": { "type": r.requirement, "label": label, "description": null },
                    "permanence": if r.requirement.starts_with("discord_") { "conditional" } else { "permanent" },
                    "starts_at": null, "ends_at": null, "sort": 100 + i,
                    "asset": self.asset_json(&r.cosmetic_id), "animated": c.animation.is_some(), "hd": false,
                    "categories": ["static", "flint"], "origin": "flint", "publisher": { "name": "Flint" }
                }))
            })
            .collect();
        json!({
            "schema": 1, "enabled": self.rewards_enabled,
            "discord_enabled": self.rewards_enabled && self.discord_rewards_enabled && self.link_enabled && self.discord_configured,
            "invite_url": self.invite_url, "rewards": rewards
        })
    }

    /// Discord's authorize page as the Store builds it (identify, PKCE S256, this Store's callback).
    fn authorize_url(&self) -> String {
        self.authorize_override.clone().unwrap_or_else(|| {
            format!(
                "https://discord.com/oauth2/authorize?client_id=1300000000000000001&redirect_uri=https%3A%2F%2Fstore.flintclient.com%2Fconnect%2Fdiscord%2Fcallback%2F&response_type=code&scope=identify&state={}&code_challenge={}&code_challenge_method=S256&prompt=consent",
                random_hex(22),
                random_hex(22)
            )
        })
    }

    /// The Discord account `link_as` linked to `uuid`, unless another account holds it.
    fn finish_link(&mut self, uuid: &str) -> &'static str {
        let Some(link) = self.link_as.clone() else { return "error" };
        if self.discord.iter().any(|(other, d)| other != uuid && d.id == link.id) {
            return "already_linked_elsewhere";
        }
        self.discord.insert(uuid.to_owned(), link);
        self.settle(uuid);
        "linked"
    }

    pub fn expire_sessions(&mut self) {
        for session in self.sessions.values_mut() {
            session.expires = now() - 1;
        }
    }

    pub fn version(&self, uuid: &str) -> i64 {
        self.players.get(&dashed(uuid)).map(|p| p.version).unwrap_or(0)
    }

    pub fn count(&self, line: &str) -> usize {
        self.log.iter().filter(|l| l.as_str() == line).count()
    }

    fn resolve(&self, headers: &HashMap<String, String>) -> Option<String> {
        let token = headers.get("authorization")?.strip_prefix("Bearer ")?;
        let session = self.sessions.get(token)?;
        (!session.revoked && session.expires > now()).then(|| session.uuid.clone())
    }

    fn asset_json(&self, id: &str) -> Value {
        match self.cosmetics.get(id) {
            Some(c) => json!({ "id": c.asset_id, "version": 1, "path": c.path, "sha256": c.sha256, "width": 64, "height": 32, "url": format!("{}{}", self.base, c.path), "animation": c.animation }),
            None => Value::Null,
        }
    }

    fn inventory_json(&self, uuid: &str) -> Value {
        let player = self.players.get(uuid);
        let mut owned: Vec<Value> = player
            .map(|p| p.owned.iter().filter_map(|id| self.cosmetics.get(id).map(|c| (id, c))).collect::<Vec<_>>())
            .unwrap_or_default()
            .into_iter()
            .map(|(id, c)| {
                let mut item = json!({ "cosmetic_id": id, "slug": c.slug, "kind": c.kind, "name": c.name, "source": "test", "granted_at": rfc3339(now()), "test": true, "gift": false, "gift_from": null, "gift_message": null, "asset": self.asset_json(id) });
                if self.community {
                    let via = self.granted_via.get(&(uuid.to_owned(), id.clone())).cloned().unwrap_or_else(|| "purchase".into());
                    item["obtained_via"] = json!(via);
                    item["permanence"] = json!("permanent");
                    item["state"] = json!("owned");
                }
                item
            })
            .collect();
        // Capes earned through Discord right now, listed while they count (an owned one appears once, as owned).
        for id in self.conditional_ids(uuid) {
            let (Some(c), false) = (self.cosmetics.get(&id), player.is_some_and(|p| p.owned.contains(&id))) else { continue };
            let requirement = self.rewards.iter().find(|r| r.cosmetic_id == id).map(|r| r.requirement.clone()).unwrap_or_default();
            owned.push(json!({
                "cosmetic_id": id, "slug": c.slug, "kind": c.kind, "name": c.name, "source": "reward", "granted_at": null, "test": false,
                "gift": false, "gift_from": null, "gift_message": null, "obtained_via": requirement, "permanence": "conditional",
                "state": if self.discord_down { "unavailable" } else { "eligible" }, "asset": self.asset_json(&id)
            }));
        }
        let equipped: BTreeMap<&String, &String> = player.map(|p| p.equipped.iter().filter(|(_, c)| self.owns(uuid, c)).collect()).unwrap_or_default();
        json!({
            "version": player.map(|p| p.version).unwrap_or(0),
            "minecraft_uuid": uuid,
            "name": player.map(|p| p.name.clone()).unwrap_or_default(),
            "balance": player.map(|p| p.balance).unwrap_or(0),
            "owned": owned,
            "equipped": equipped,
        })
    }

    /// A launcher session's inventory read claims an active first-launch reward, once per account, ever.
    fn claim_first_launch(&mut self, uuid: &str) {
        if !(self.community && self.rewards_enabled) || self.first_launch_claimed.contains(uuid) {
            return;
        }
        let Some(id) = self.rewards.iter().find(|r| r.requirement == "first_launch").map(|r| r.cosmetic_id.clone()) else { return };
        self.first_launch_claimed.insert(uuid.to_owned());
        self.grant_via(uuid, &id, "first_launch");
    }

    fn handle(&mut self, request: &Request) -> Reply {
        let body: Value = serde_json::from_slice(&request.body).unwrap_or(Value::Null);
        let route = (request.method.as_str(), request.path.as_str());
        match route {
            ("POST", "/session/minecraft/join") => {
                let token = body["accessToken"].as_str().unwrap_or_default();
                let profile = body["selectedProfile"].as_str().unwrap_or_default();
                match self.mojang_tokens.get(token) {
                    Some((uuid, name)) if uuid == profile => {
                        let joined = (uuid.clone(), name.clone());
                        self.joins.insert(body["serverId"].as_str().unwrap_or_default().into(), joined);
                        Reply::empty(204)
                    }
                    _ => Reply::json(403, json!({ "error": "ForbiddenOperationException", "errorMessage": "Invalid token" })),
                }
            }
            ("GET", "/session/minecraft/hasJoined") => {
                let name = request.query.get("username").cloned().unwrap_or_default();
                match self.joins.get(request.query.get("serverId").map(String::as_str).unwrap_or_default()) {
                    Some((uuid, joined)) if *joined == name => Reply::json(200, json!({ "id": uuid, "name": joined })),
                    _ => Reply::empty(204),
                }
            }
            ("POST", "/api/v1/auth/challenge/") => {
                let id = uuid_v4();
                let server_id = random_hex(20);
                self.challenges.insert(id.clone(), server_id.clone());
                Reply::json(200, json!({ "challengeId": id, "serverId": server_id, "expiresAt": rfc3339(now() + 120) }))
            }
            ("POST", "/api/v1/auth/verify/") => {
                let Some(server_id) = self.challenges.remove(body["challengeId"].as_str().unwrap_or_default()) else {
                    return Reply::json(400, json!({ "error": "challenge_invalid" }));
                };
                let username = body["username"].as_str().unwrap_or_default();
                let joined = match self.joins.get(&server_id) {
                    Some((uuid, name)) if name == username => (dashed(uuid), name.clone()),
                    _ => return Reply::json(403, json!({ "error": "not_verified" })),
                };
                let (uuid, name) = self.verify_as.clone().map(|(u, n)| (dashed(&u), n)).unwrap_or(joined);
                self.player(&uuid).name = name.clone();
                let token = random_hex(24);
                let expires = now() + 30 * 86_400;
                self.sessions.insert(token.clone(), FakeSession { uuid: uuid.clone(), expires, revoked: false });
                Reply::json(200, json!({ "token": token, "expiresAt": rfc3339(expires), "player": { "uuid": uuid, "name": name } }))
            }
            ("POST", "/api/v1/auth/logout/") => {
                if let Some(token) = request.headers.get("authorization").and_then(|h| h.strip_prefix("Bearer ")) {
                    if let Some(session) = self.sessions.get_mut(token) {
                        session.revoked = true;
                    }
                }
                Reply::json(200, json!({ "ok": true }))
            }
            ("GET", "/api/v1/players/cosmetics/") => {
                let mut players = serde_json::Map::new();
                for uuid in request.query.get("uuids").map(|s| s.split(',').map(str::to_owned).collect::<Vec<_>>()).unwrap_or_default() {
                    if let Some(p) = self.players.get(&uuid) {
                        let slots: serde_json::Map<String, Value> = p
                            .equipped
                            .iter()
                            .map(|(slot, id)| (slot.clone(), json!({ "cosmetic_id": id, "kind": slot, "asset": self.asset_json(id) })))
                            .collect();
                        players.insert(uuid, Value::Object(slots));
                    }
                }
                Reply::json(200, json!({ "players": players }))
            }
            ("GET", "/api/v1/catalog/community/") if self.community => {
                let body = self.catalog_json();
                let etag = format!("\"c1-{}\"", &hex::encode(Sha256::digest(body.to_string().as_bytes()))[..32]);
                if request.headers.get("if-none-match") == Some(&etag) {
                    return Reply { status: 304, headers: vec![("ETag".into(), etag)], body: Vec::new() };
                }
                let mut reply = Reply::json(200, body);
                reply.headers.push(("ETag".into(), etag));
                reply
            }
            (method, path) if path.starts_with("/api/v1/me/") || path == "/api/v1/store-link/approve/" => {
                let Some(uuid) = self.resolve(&request.headers) else {
                    return Reply::json(401, json!({ "error": "unauthorized" }));
                };
                let uuid = self.inventory_as.clone().filter(|_| path == "/api/v1/me/inventory/").unwrap_or(uuid);
                self.me(method, path, &uuid, request, &body)
            }
            ("GET", path) if self.files.contains_key(path) => Reply { status: 200, headers: vec![("Content-Type".into(), "image/png".into())], body: self.files[path].clone() },
            _ => Reply::json(404, json!({ "error": "not_found" })),
        }
    }

    fn me(&mut self, method: &str, path: &str, uuid: &str, request: &Request, body: &Value) -> Reply {
        match (method, path) {
            ("GET", "/api/v1/me/inventory/") => {
                self.claim_first_launch(uuid);
                let etag = format!("W/\"inv-{}-{}\"", uuid.replace('-', ""), self.version(uuid));
                if request.headers.get("if-none-match") == Some(&etag) {
                    return Reply { status: 304, headers: vec![("ETag".into(), etag)], body: Vec::new() };
                }
                let mut reply = Reply::json(200, self.inventory_json(uuid));
                reply.headers.push(("ETag".into(), etag));
                reply
            }
            ("GET", "/api/v1/me/changes/") => {
                let version = self.version(uuid);
                let since = request.query.get("since").and_then(|s| s.parse::<i64>().ok());
                Reply::json(200, json!({ "changed": since != Some(version), "version": version, "pollAfterSeconds": self.poll_after_seconds }))
            }
            ("PUT", "/api/v1/me/equipped/") => {
                let slot = body["slot"].as_str().unwrap_or_default().to_owned();
                if !["cape", "back", "head", "aura"].contains(&slot.as_str()) {
                    return Reply::json(400, json!({ "error": "invalid_slot" }));
                }
                let target = body["cosmeticId"].as_str().map(str::to_owned);
                if let Some(id) = &target {
                    if !self.owns(uuid, id) {
                        return Reply::json(403, json!({ "error": "not_owned" }));
                    }
                    if self.cosmetics.get(id).map(|c| c.kind.as_str()) != Some(slot.as_str()) {
                        return Reply::json(400, json!({ "error": "wrong_slot" }));
                    }
                }
                let player = self.player(uuid);
                match target {
                    Some(id) => player.equipped.insert(slot, id),
                    None => player.equipped.remove(&slot),
                };
                player.version += 1;
                Reply::json(200, json!({ "ok": true, "version": player.version }))
            }
            ("GET", "/api/v1/me/wallet/") => {
                let balance = self.players.get(uuid).map(|p| p.balance).unwrap_or(0);
                Reply::json(200, json!({ "balance": balance, "ledger": [{ "delta": balance, "balance_after": balance, "reason": "test_grant", "order_id": null, "created_at": rfc3339(now()) }] }))
            }
            ("GET", "/api/v1/me/orders/") => Reply::json(
                200,
                json!({ "orders": [{ "id": uuid_v4(), "kind": "cosmetic", "status": "paid", "payment_method": "tebex", "test_mode": true, "item": "TEST · Ember", "coins": null, "amount_minor": "499", "currency": "USD", "created_at": rfc3339(now()), "gift": false, "reference": "FL-7Q2M-K4" }] }),
            ),
            ("POST", "/api/v1/store-link/approve/") => {
                let code = body["code"].as_str().unwrap_or_default().trim().to_ascii_uppercase();
                match self.links.get_mut(&code) {
                    Some(slot) if slot.is_none() => {
                        *slot = Some(uuid.to_owned());
                        Reply::json(200, json!({ "ok": true }))
                    }
                    _ => Reply::json(400, json!({ "error": "code_invalid" })),
                }
            }
            _ if self.community => self.community_route(method, path, uuid),
            _ => Reply::json(404, json!({ "error": "not_found" })),
        }
    }

    /// The private routes of docs/connections-rewards-api-v1.md.
    fn community_route(&mut self, method: &str, path: &str, uuid: &str) -> Reply {
        let attempt = path.strip_prefix("/api/v1/me/connections/discord/attempts/").and_then(|rest| rest.strip_suffix('/')).map(str::to_owned);
        match (method, path) {
            ("GET", "/api/v1/me/connections/") => Reply::json(200, self.connections_json(uuid)),
            ("POST", "/api/v1/me/connections/discord/start/") => {
                if !self.discord_configured {
                    return Reply::json(503, json!({ "error": "discord_unconfigured" }));
                }
                if !self.link_enabled {
                    return Reply::json(403, json!({ "error": "discord_disabled" }));
                }
                let starts = self.starts.entry(uuid.to_owned()).or_default();
                starts.retain(|at| *at > now() - 600);
                if starts.len() >= 5 {
                    return Reply::json(429, json!({ "error": "rate-limited", "next_allowed_at": rfc3339(now() + 600) })).header("Retry-After", "600");
                }
                starts.push(now());
                // Starting again cancels the previous open attempt.
                self.attempts.retain(|_, a| a.uuid != uuid);
                let id = uuid_v4();
                self.attempts.insert(id.clone(), FakeAttempt { uuid: uuid.to_owned(), at: 0, script: self.attempt_script.clone() });
                Reply::json(200, json!({ "authorize_url": self.authorize_url(), "attempt_id": id, "expires_at": rfc3339(now() + 600) }))
            }
            ("GET", _) if attempt.is_some() => {
                let id = attempt.unwrap();
                let Some(a) = self.attempts.get_mut(&id).filter(|a| a.uuid == uuid) else {
                    return Reply::json(404, json!({ "error": "not_found" }));
                };
                let said = a.script.get(a.at).cloned().unwrap_or_else(|| "error".into());
                if a.at + 1 < a.script.len() {
                    a.at += 1;
                }
                let state = if said == "linked" {
                    let done = self.finish_link(uuid);
                    // Settled once: later polls answer the same.
                    if let Some(a) = self.attempts.get_mut(&id) {
                        a.script = vec![done.into()];
                        a.at = 0;
                    }
                    done.to_owned()
                } else {
                    said
                };
                Reply::json(200, json!({ "state": state, "expires_at": rfc3339(now() + 300) }))
            }
            ("DELETE", "/api/v1/me/connections/discord/") => {
                let unlinked = self.discord.remove(uuid).is_some();
                if unlinked {
                    self.settle(uuid);
                }
                Reply::json(200, json!({ "ok": true, "unlinked": unlinked }))
            }
            ("GET", "/api/v1/me/rewards/") => Reply::json(200, self.rewards_json(uuid)),
            ("POST", "/api/v1/me/rewards/recheck/") => {
                if let Some(at) = self.rechecked_at.get(uuid).copied().filter(|at| *at > now() - 60) {
                    let next = at + 60;
                    return Reply::json(429, json!({ "error": "rate-limited", "next_allowed_at": rfc3339(next) })).header("Retry-After", &(next - now()).max(1).to_string());
                }
                self.rechecked_at.insert(uuid.to_owned(), now());
                self.settle(uuid);
                Reply::json(200, json!({ "connection": self.connections_json(uuid), "rewards": self.rewards_json(uuid) }))
            }
            _ => Reply::json(404, json!({ "error": "not_found" })),
        }
    }
}

pub struct Request {
    pub method: String,
    pub path: String,
    pub query: HashMap<String, String>,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

pub struct Reply {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Reply {
    fn json(status: u16, value: Value) -> Self {
        Self { status, headers: vec![("Content-Type".into(), "application/json".into())], body: serde_json::to_vec(&value).unwrap() }
    }
    fn empty(status: u16) -> Self {
        Self { status, headers: Vec::new(), body: Vec::new() }
    }
    fn header(mut self, name: &str, value: &str) -> Self {
        self.headers.push((name.into(), value.into()));
        self
    }
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
    let headers: HashMap<String, String> =
        lines.filter_map(|l| l.split_once(':')).map(|(k, v)| (k.trim().to_ascii_lowercase(), v.trim().to_owned())).collect();
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

async fn serve(mut stream: TcpStream, world: Arc<Mutex<World>>) {
    let Some(request) = read_request(&mut stream).await else { return };
    let reply = {
        let mut world = world.lock().unwrap();
        if world.offline {
            return; // dropped unanswered
        }
        let reply = world.handle(&request);
        let line = format!("{} {} {}", request.method, request.path, reply.status);
        world.log.push(line);
        reply
    };
    let reason = match reply.status {
        200 => "OK",
        204 => "No Content",
        304 => "Not Modified",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        _ => "Other",
    };
    let mut head = format!("HTTP/1.1 {} {reason}\r\nContent-Length: {}\r\nConnection: close\r\n", reply.status, reply.body.len());
    for (name, value) in &reply.headers {
        head.push_str(&format!("{name}: {value}\r\n"));
    }
    head.push_str("\r\n");
    let _ = stream.write_all(head.as_bytes()).await;
    let _ = stream.write_all(&reply.body).await;
    let _ = stream.shutdown().await;
}

pub struct Fake {
    pub addr: SocketAddr,
    pub world: Arc<Mutex<World>>,
}

impl Fake {
    pub async fn start() -> Self {
        Self::start_on(0).await
    }

    pub async fn start_on(port: u16) -> Self {
        let listener = TcpListener::bind(("127.0.0.1", port)).await.expect("the fake server can listen");
        let addr = listener.local_addr().unwrap();
        let world = Arc::new(Mutex::new(World { base: format!("http://{addr}"), poll_after_seconds: 25, ..Default::default() }));
        let shared = world.clone();
        tokio::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                tokio::spawn(serve(stream, shared.clone()));
            }
        });
        Self { addr, world }
    }

    pub fn url(&self) -> Url {
        Url::parse(&format!("http://{}/", self.addr)).unwrap()
    }

    pub fn with<R>(&self, f: impl FnOnce(&mut World) -> R) -> R {
        f(&mut self.world.lock().unwrap())
    }
}
