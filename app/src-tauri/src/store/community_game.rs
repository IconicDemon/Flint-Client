//! Discord and community rewards in the game: what the game link (`gamelink.rs`) gives the main menu's "Join our Discord"
//! card and the Friends settings' linked Discord, for the account the game plays as. Everything stays the launcher's
//! (community.rs): the public reward catalog, and the account's connection and reward standing read with its own Store
//! session. The game gets only what it shows: names, states, the invite to open, the listed rewards and their status. A
//! reward listed here is shown as a preview; whether it is the player's is the Store's `status`, never the game's guess.
//!
//! Linking starts here too ([`link`]): the Store's attempt is opened by the game itself, which is in front (as the account
//! card's Add account does), and the game reads this view again until the link shows.
use super::community::{self, Avail, Catalog, ConnectionsView, LinkStart, RewardsView, Standing};
use super::*;

/// The most rewards sent to a game.
pub const GAME_REWARDS: usize = 20;

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameDiscord {
    pub username: String,
    pub display_name: Option<String>,
    pub member: Option<bool>,
    pub booster: Option<bool>,
    /// `checking`, `verified`, `pending` or `unavailable`.
    pub guild: String,
    pub linked_at: Option<String>,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameReward {
    pub id: String,
    pub kind: String,
    pub name: String,
    /// `discord_member`, `discord_booster`, `first_launch`… (the game shows Discord ones on its card).
    pub requirement: String,
    /// The Store's words for what earns it.
    pub label: String,
    pub permanence: String,
    /// `owned`, `eligible`, `checking`, `unavailable` or `locked` (what the Store said; `locked` when it can't say).
    pub status: String,
    pub animated: bool,
    /// The version of its picture (`rewardArt`).
    pub art: i64,
}

/// The `community` answer.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameCommunity {
    /// `ok`, `off` (this Store has no community rewards: show nothing), `signedOut` (no Store session for the account: the
    /// rewards are listed, locked) or `unavailable` (the Store couldn't answer now: keep what was shown).
    pub state: String,
    pub enabled: bool,
    pub discord_enabled: bool,
    pub can_link: bool,
    /// The Discord server to join: always Flint's own, discord.gg/flintclient.
    pub invite: String,
    pub discord: Option<GameDiscord>,
    pub standing: Option<Standing>,
    pub rewards: Vec<GameReward>,
}

fn running(starts: &Option<String>, ends: &Option<String>, now_secs: i64) -> bool {
    let at = |t: &Option<String>| -> Option<Option<i64>> {
        match t {
            None => Some(None),
            Some(text) => rfc3339_secs(text).map(Some),
        }
    };
    match (at(starts), at(ends)) {
        // A schedule Flint can't read: not shown.
        (Some(s), Some(e)) => s.is_none_or(|s| s <= now_secs) && e.is_none_or(|e| now_secs < e),
        _ => false,
    }
}

/// What the game shows, from the catalog and the account's connection and rewards (pure: tested without a Store).
pub fn view(catalog: &Catalog, connections: &ConnectionsView, rewards: &RewardsView, now_secs: i64) -> GameCommunity {
    let account = |a: Avail| a == Avail::Ok;
    let state = match catalog.state {
        Avail::Off => "off",
        Avail::Unavailable | Avail::SignedOut => "unavailable",
        Avail::Ok if connections.state == Avail::SignedOut || rewards.state == Avail::SignedOut => "signedOut",
        Avail::Ok if connections.state == Avail::Unavailable || rewards.state == Avail::Unavailable => "unavailable",
        Avail::Ok => "ok",
    };
    let status_of = |id: &str| {
        account(rewards.state)
            .then(|| rewards.rewards.iter().find(|r| r.cosmetic_id == id).map(|r| r.status.clone()))
            .flatten()
            .unwrap_or_else(|| "locked".to_owned())
    };
    let mut listed: Vec<&community::CommunityReward> = if catalog.state == Avail::Ok {
        catalog.rewards.iter().filter(|r| running(&r.starts_at, &r.ends_at, now_secs)).collect()
    } else {
        Vec::new()
    };
    listed.sort_by_key(|r| r.sort);
    let shown = listed
        .into_iter()
        .take(GAME_REWARDS)
        .map(|r| GameReward {
            id: r.cosmetic_id.clone(),
            kind: r.kind.clone(),
            name: r.name.clone(),
            requirement: r.requirement.kind.clone(),
            label: r.requirement.label.clone(),
            permanence: r.permanence.clone(),
            status: status_of(&r.cosmetic_id),
            animated: r.animated,
            art: r.asset.version,
        })
        .collect();
    // Always Flint's own server, discord.gg/flintclient (owner, 1.0.4): whatever invite the Store has stored, the card opens
    // the one Flint ships. Membership is checked through the linked Discord account, not the invite code.
    let invite = crate::data::DISCORD_INVITE.to_owned();
    let discord = account(connections.state)
        .then_some(connections.discord.as_ref())
        .flatten()
        .map(|d| GameDiscord {
            username: d.username.clone(),
            display_name: d.display_name.clone(),
            member: d.guild.member,
            booster: d.guild.booster,
            guild: d.guild.state.clone(),
            linked_at: d.linked_at.clone(),
        });
    GameCommunity {
        state: state.to_owned(),
        enabled: catalog.state == Avail::Ok && catalog.enabled,
        discord_enabled: catalog.state == Avail::Ok && catalog.discord_enabled,
        can_link: account(connections.state) && connections.can_link,
        invite,
        discord,
        standing: account(rewards.state).then(|| rewards.discord.clone()).flatten(),
        rewards: shown,
    }
}

/// The community view for the account `uuid` (a Microsoft account of Flint's): the catalog (asked at most once a minute),
/// then the account's connection and rewards, read together.
pub async fn game(state: &AppState, uuid: &str) -> GameCommunity {
    let catalog = community::catalog(state).await;
    let (connections, rewards) = tokio::join!(community::connections(state, uuid), community::rewards(state, uuid));
    view(&catalog, &connections, &rewards, (now_millis() / 1000) as i64)
}

/// A listed reward's picture (the PNG the catalog hashed), as base64: public, the same for every account.
pub async fn art(state: &AppState, cosmetic_id: &str) -> CmdResult<String> {
    community::catalog(state).await;
    let url = community::reward_asset(state, cosmetic_id).await?;
    url.strip_prefix("data:image/png;base64,").map(str::to_owned).ok_or_else(|| "internal error".to_owned())
}

/// Starts linking Discord for the account: the Store's attempt, and Discord's page (checked) for the game to open.
pub async fn link(state: &AppState, uuid: &str) -> CmdResult<Value> {
    Ok(match community::start_link(state, uuid).await? {
        (LinkStart::Started { attempt_id, .. }, Some(url)) => json!({ "state": "started", "url": url.as_str(), "attemptId": attempt_id }),
        _ => json!({ "state": "off" }),
    })
}

#[cfg(test)]
mod tests {
    use super::community::{CommunityReward, DiscordLink, Guild, Requirement, RewardAction, RewardStatus};
    use super::*;

    const MEMBER: &str = "00000000-0000-4000-8000-0000000000a1";
    const BOOSTER: &str = "00000000-0000-4000-8000-0000000000a2";
    const FIRST: &str = "00000000-0000-4000-8000-0000000000a3";
    const ENDED: &str = "00000000-0000-4000-8000-0000000000a4";
    const NOW: i64 = 1_800_000_000;

    fn reward(id: &str, requirement: &str, sort: i64, ends_at: Option<&str>) -> CommunityReward {
        CommunityReward {
            cosmetic_id: id.into(),
            slug: id[30..].into(),
            kind: "cape".into(),
            name: format!("Cape {}", &id[34..]),
            description: None,
            requirement: Requirement { kind: requirement.into(), label: format!("Label for {requirement}"), description: None },
            permanence: if requirement.starts_with("discord_") { "conditional".into() } else { "permanent".into() },
            starts_at: None,
            ends_at: ends_at.map(str::to_owned),
            sort,
            asset: Asset { id: id.into(), version: sort, url: String::new(), sha256: "a".repeat(64), cdn_url: None, width: None, height: None, animation: None, model: None, has_texture: true },
            animated: requirement == "discord_booster",
            hd: false,
        }
    }

    fn catalog() -> Catalog {
        Catalog {
            state: Avail::Ok,
            enabled: true,
            discord_enabled: true,
            rewards: vec![
                reward(BOOSTER, "discord_booster", 2, None),
                reward(MEMBER, "discord_member", 1, None),
                reward(FIRST, "first_launch", 3, None),
                reward(ENDED, "discord_member", 0, Some("2020-01-01T00:00:00Z")),
            ],
            invite_url: None,
            fetched_at: Some(1),
        }
    }

    fn connections(state: Avail, member: Option<bool>) -> ConnectionsView {
        ConnectionsView {
            uuid: "u".into(),
            state,
            can_link: state == Avail::Ok,
            discord: member.map(|m| DiscordLink {
                username: "flintfan".into(),
                display_name: Some("FlintFan".into()),
                avatar_url: None,
                linked_at: Some("2026-11-01T00:00:00Z".into()),
                guild: Guild { state: "verified".into(), member: Some(m), booster: Some(false), checked_at: None, verified_at: None },
            }),
            problem: None,
        }
    }

    fn rewards(state: Avail, member: bool) -> RewardsView {
        let status = |id: &str, ok: bool, kind: &str| RewardStatus {
            cosmetic_id: id.into(),
            requirement: String::new(),
            permanence: "conditional".into(),
            status: if ok { "eligible".into() } else { "locked".into() },
            action: RewardAction { kind: if ok { "none".into() } else { kind.into() }, url: (!ok && kind == "join_discord").then(|| "https://discord.gg/flintfans".to_owned()) },
        };
        RewardsView {
            uuid: "u".into(),
            state,
            discord: Some(Standing { linked: true, member, booster: false, pending: false, state: "verified".into() }),
            rewards: vec![status(MEMBER, member, "join_discord"), status(BOOSTER, false, "boost_discord")],
            problem: None,
        }
    }

    #[test]
    fn a_member_sees_their_rewards_as_the_store_says() {
        let shown = view(&catalog(), &connections(Avail::Ok, Some(true)), &rewards(Avail::Ok, true), NOW);
        assert_eq!(shown.state, "ok");
        assert!(shown.enabled && shown.discord_enabled && shown.can_link);
        assert_eq!(shown.rewards.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(), [MEMBER, BOOSTER, FIRST], "by sort; ended ones left out");
        assert_eq!(shown.rewards[0].status, "eligible");
        assert_eq!(shown.rewards[1].status, "locked");
        assert_eq!(shown.rewards[2].status, "locked", "not in the account's list: locked, never owned");
        assert!(shown.rewards[1].animated);
        assert_eq!(shown.discord.as_ref().map(|d| (d.username.as_str(), d.member, d.guild.as_str())), Some(("flintfan", Some(true), "verified")));
        assert!(shown.standing.as_ref().is_some_and(|s| s.member));
        assert_eq!(shown.invite, crate::data::DISCORD_INVITE, "nothing to join: Flint's own invite");
    }

    #[test]
    fn a_player_who_hasnt_joined_gets_flints_invite() {
        let shown = view(&catalog(), &connections(Avail::Ok, Some(false)), &rewards(Avail::Ok, false), NOW);
        assert_eq!(shown.invite, crate::data::DISCORD_INVITE, "Flint's own invite, not the one the Store stored");
        assert_eq!(shown.rewards[0].status, "locked");
        let json = serde_json::to_value(&shown).unwrap();
        assert_eq!(json["discordEnabled"], true);
        assert_eq!(json["canLink"], true);
        assert_eq!(json["discord"]["displayName"], "FlintFan");
        assert_eq!(json["discord"]["linkedAt"], "2026-11-01T00:00:00Z");
        assert_eq!(json["rewards"][0]["requirement"], "discord_member");
        assert_eq!(json["rewards"][0]["art"], 1);
        assert_eq!(json["standing"]["state"], "verified");
    }

    #[test]
    fn the_invite_is_always_flints_own() {
        let mut named = catalog();
        named.invite_url = Some("https://discord.gg/qRku77NA7R".into());
        let signed_out = RewardsView { uuid: "u".into(), state: Avail::SignedOut, discord: None, rewards: Vec::new(), problem: None };
        // Neither the catalog's nor the account's stored invite: discord.gg/flintclient.
        assert_eq!(view(&named, &connections(Avail::SignedOut, None), &signed_out, NOW).invite, crate::data::DISCORD_INVITE);
        assert_eq!(view(&named, &connections(Avail::Ok, Some(false)), &rewards(Avail::Ok, false), NOW).invite, crate::data::DISCORD_INVITE);
        assert_eq!(crate::data::DISCORD_INVITE, "https://discord.gg/flintclient");
    }

    #[test]
    fn without_a_store_session_the_rewards_are_listed_locked() {
        let shown = view(&catalog(), &connections(Avail::SignedOut, None), &RewardsView { uuid: "u".into(), state: Avail::SignedOut, discord: None, rewards: Vec::new(), problem: None }, NOW);
        assert_eq!(shown.state, "signedOut");
        assert!(!shown.can_link && shown.discord.is_none() && shown.standing.is_none());
        assert!(shown.rewards.iter().all(|r| r.status == "locked"));
        assert_eq!(shown.invite, crate::data::DISCORD_INVITE);
    }

    #[test]
    fn a_store_without_the_feature_or_out_of_reach_shows_nothing_new() {
        let off = Catalog { state: Avail::Off, enabled: false, discord_enabled: false, rewards: Vec::new(), invite_url: None, fetched_at: None };
        let shown = view(&off, &connections(Avail::Off, None), &RewardsView { uuid: "u".into(), state: Avail::Off, discord: None, rewards: Vec::new(), problem: None }, NOW);
        assert_eq!((shown.state.as_str(), shown.enabled, shown.rewards.len()), ("off", false, 0));
        let mut down = catalog();
        down.state = Avail::Unavailable;
        let shown = view(&down, &connections(Avail::Ok, Some(true)), &rewards(Avail::Ok, true), NOW);
        assert_eq!(shown.state, "unavailable");
        assert!(shown.rewards.is_empty() && !shown.discord_enabled);
        assert!(shown.discord.is_some(), "the linked account still shows in Friends settings");
        let shown = view(&catalog(), &connections(Avail::Unavailable, None), &rewards(Avail::Ok, true), NOW);
        assert_eq!(shown.state, "unavailable");
    }

    #[test]
    fn schedules_flint_cant_read_hide_the_reward() {
        let mut odd = catalog();
        odd.rewards[0].starts_at = Some("next week".into());
        odd.rewards[1].starts_at = Some("2099-01-01T00:00:00Z".into());
        let shown = view(&odd, &connections(Avail::Ok, Some(true)), &rewards(Avail::Ok, true), NOW);
        assert_eq!(shown.rewards.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(), [FIRST]);
    }
}
