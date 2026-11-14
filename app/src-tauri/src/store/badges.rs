//! Badges: the role marks a Flint account owns (`GET /api/v1/me/` `badges`, always "default" first, then rank order) and
//! the one other players see (`badge`), chosen with `PUT /api/v1/me/badge/` `{"tag": …}`. Presence then hands the chosen
//! one to other players. A Store that doesn't send the fields yet answers [`Badges`] `known: false`: the launcher shows the
//! Flint mark only and the game keeps its own choice, as before.
use super::*;

/// Every mark the Store may name, in its rank order ("default" first). A name Flint doesn't know is left out.
pub const TAGS: [&str; 9] = ["default", "owner", "co_owner", "developer", "staff", "content_creator", "trial_creator", "vip", "special"];

pub const SLOW_DOWN: &str = "You're changing your badge too often. Wait a minute and try again.";

#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Badges {
    /// Whether the Store sent badges at all (false: today's Store; only the Flint mark is shown).
    pub known: bool,
    /// The marks owned, "default" first, in the Store's rank order.
    pub badges: Vec<String>,
    /// The mark others see now (always one of `badges`).
    pub badge: String,
}

impl Badges {
    pub fn unknown() -> Self {
        Self { known: false, badges: vec!["default".into()], badge: "default".into() }
    }
}

/// `badges`/`badge` of a `/me` (or `PUT /me/badge/`) answer, checked: only names Flint knows, "default" always first and
/// once, and a chosen badge that is one of them (else "default"). None when the answer has no `badges` list.
pub fn parse(body: &Value) -> Option<Badges> {
    let list = body.get("badges")?.as_array()?;
    let mut badges = vec!["default".to_owned()];
    for name in list.iter().filter_map(Value::as_str).take(32) {
        if TAGS.contains(&name) && !badges.iter().any(|b| b == name) {
            badges.push(name.to_owned());
        }
    }
    let badge = body.get("badge").and_then(Value::as_str).filter(|b| badges.iter().any(|o| o == b)).unwrap_or("default").to_owned();
    Some(Badges { known: true, badges, badge })
}

/// What to tell the player when a badge change fails.
fn badge_failure(state: &AppState, uuid: &str, failure: Failure) -> String {
    match &failure {
        Failure::Refused { status: 429, .. } => SLOW_DOWN.into(),
        Failure::Refused { code, .. } if code == "not_owned" => "That badge isn't yours (any more). Flint decides which badges you have.".into(),
        Failure::Refused { code, .. } if code == "invalid_tag" => "Flint doesn't know that badge. Update Flint and try again.".into(),
        _ => action_failure(state, uuid, failure),
    }
}

/// `GET /api/v1/me/`: the badges the account owns and the one others see. A Store without them answers [`Badges::unknown`].
pub async fn list(state: &AppState, uuid: &str) -> CmdResult<Badges> {
    let session = session_for_action(state, uuid)?;
    let answer = call(state, Method::GET, "/api/v1/me/", Some(&session.token), None, None).await.map_err(|f| action_failure(state, uuid, f))?;
    Ok(parse(&answer.body).unwrap_or_else(Badges::unknown))
}

/// `PUT /api/v1/me/badge/` `{"tag": …}`: asks the Store to show `tag` to other players. The Store decides; its answer is
/// what is shown afterwards.
pub async fn set(state: &AppState, uuid: &str, tag: &str) -> CmdResult<Badges> {
    if !TAGS.contains(&tag) {
        return Err("Flint doesn't know that badge. Update Flint and try again.".into());
    }
    let session = session_for_action(state, uuid)?;
    let answer = call(state, Method::PUT, "/api/v1/me/badge/", Some(&session.token), Some(json!({ "tag": tag })), None)
        .await
        .map_err(|f| badge_failure(state, uuid, f))?;
    // The answer names the new badge; one without the list still took the change.
    Ok(parse(&answer.body).unwrap_or_else(|| {
        let mut badges = vec!["default".to_owned()];
        if tag != "default" {
            badges.push(tag.to_owned());
        }
        Badges { known: true, badges, badge: tag.to_owned() }
    }))
}

#[tauri::command]
pub async fn badge_list(state: State<'_, AppState>, uuid: String) -> CmdResult<Badges> {
    list(&state, &uuid).await
}

#[tauri::command]
pub async fn badge_set(state: State<'_, AppState>, uuid: String, tag: String) -> CmdResult<Badges> {
    set(&state, &uuid, &tag).await
}

/// A game's view (game link `badges`/`badgeSet`): the list and the chosen one, or both null when the Store doesn't send
/// badges (the game then keeps its own choice, as before).
pub fn for_game(badges: &Badges) -> Value {
    if badges.known {
        json!({ "badges": badges.badges, "badge": badges.badge })
    } else {
        json!({ "badges": null, "badge": null })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_game_hears_null_from_today_s_store() {
        assert_eq!(for_game(&Badges::unknown()), json!({ "badges": null, "badge": null }));
        let known = Badges { known: true, badges: vec!["default".into(), "staff".into()], badge: "staff".into() };
        assert_eq!(for_game(&known), json!({ "badges": ["default", "staff"], "badge": "staff" }));
    }
}
