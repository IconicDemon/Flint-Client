//! The Flint Store in the game: what the game link (`gamelink.rs`) gives a game's Cosmetics screen. The Store session
//! stays the launcher's; the game gets the account's items as the Store last listed them (no token, no texture address),
//! wears one by asking Flint (the same `PUT /me/equipped/` the app's Store page sends, after which every running game
//! hears of the change through its handoff file and refreshes its cape), and gets an item's picture as checked bytes
//! (the texture the Store hashed, from Flint's own cache).
//!
//! An account that isn't signed in to the Store answers [`SIGN_IN_IN_APP`]: signing in proves the account at Mojang and is
//! the app's to do.
use super::*;

/// What a game shows when the account has no Store session.
pub const SIGN_IN_IN_APP: &str = "Sign in to the Flint Store in the Flint app (its Store page) to wear your cosmetics here.";
/// The most items sent to a game.
pub const GAME_ITEMS: usize = 500;

/// One owned item as a game shows it.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameItem {
    /// The Store's cosmetic id (a UUID).
    pub id: String,
    pub slug: String,
    /// The slot it is worn in: cape, back, head or aura.
    pub kind: String,
    pub name: String,
    pub gift: bool,
    pub gift_from: Option<String>,
    pub test: bool,
    /// The version of its picture (ask `cosmeticArt` for it), or none when it has no usable one.
    pub art: Option<i64>,
}

/// A game's Cosmetics screen.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameCosmetics {
    /// The Store's address (`https://store.flintclient.com`), for "Get more".
    pub store_url: String,
    pub name: Option<String>,
    pub items: Vec<GameItem>,
    /// Slot → the cosmetic id worn there.
    pub equipped: BTreeMap<String, String>,
    /// The Store couldn't be reached: the last list, display only.
    pub stale: bool,
    pub problem: Option<String>,
    pub fetched_at: Option<u64>,
}

fn shown(view: StoreView) -> CmdResult<GameCosmetics> {
    if !view.signed_in {
        return Err(view.problem.unwrap_or_else(|| SIGN_IN_IN_APP.into()));
    }
    let inventory = view.inventory;
    let items = inventory
        .as_ref()
        .map(|i| {
            i.owned
                .iter()
                .take(GAME_ITEMS)
                .map(|o| GameItem {
                    id: o.cosmetic_id.clone(),
                    slug: o.slug.clone(),
                    kind: o.kind.clone(),
                    name: o.name.clone(),
                    gift: o.gift,
                    gift_from: o.gift_from.clone(),
                    test: o.test,
                    art: o.asset.as_ref().map(|a| a.version),
                })
                .collect()
        })
        .unwrap_or_default();
    Ok(GameCosmetics {
        store_url: view.store_url,
        name: view.name,
        items,
        equipped: inventory.map(|i| i.equipped).unwrap_or_default(),
        stale: view.stale,
        problem: view.problem,
        fetched_at: view.fetched_at,
    })
}

/// The account's items (fresh from the Store, or its last list marked stale).
pub async fn cosmetics(state: &AppState, uuid: &str) -> CmdResult<GameCosmetics> {
    shown(view(state, uuid, false).await)
}

/// Wears `id` in `slot` (or empties the slot): the Store decides, and every running game is told.
pub async fn wear(state: &AppState, uuid: &str, slot: &str, id: Option<&str>) -> CmdResult<GameCosmetics> {
    shown(equip(state, uuid, slot, id).await?)
}

/// The picture of an item the account owns: the PNG the Store hashed, as base64.
pub async fn art(state: &AppState, uuid: &str, id: &str) -> CmdResult<String> {
    let url = asset(state, uuid, id).await?;
    url.strip_prefix("data:image/png;base64,").map(str::to_owned).ok_or_else(|| "internal error".to_owned())
}

/// The Flint account a game plays as, if it may use the Store: a Microsoft account of Flint's.
pub fn game_account(state: &AppState, playing: &str) -> CmdResult<String> {
    let (uuid, account) = flint_account(state, playing).map_err(|_| "Flint doesn't have the account this game plays as.".to_owned())?;
    if account.qa {
        return Err("The test identity has no Minecraft account, so it can't use the Flint Store.".into());
    }
    Ok(uuid)
}
