//! The Flint Store in the game: what the game link (`gamelink.rs`) gives a game's Cosmetics screen. The Store session
//! stays the launcher's; the game gets the account's items as the Store last listed them (no token, no texture address;
//! an animated cape's manifest, the strips' public addresses and hashes the game's own Store lookup also gets, so its
//! Cosmetics window plays the cape as the game plays it on players),
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
    /// An animated cape's manifest exactly as the Store writes it (`asset.animation`, read by the game's `CapeAnimation`):
    /// absent for a still cape, and past [`GAME_ANIMATION_BYTES`] (its poster then shows still). Older games ignore it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub animation: Option<Value>,
}

/// The most bytes of animation manifests sent to a game in one list: with 500 items the list stays well inside the 512 KiB
/// a game (1.0.2 and earlier included) takes for it.
pub const GAME_ANIMATION_BYTES: usize = 192 * 1024;

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
    /// The Store's inventory version of this list (it only grows): a game drops a list older than the one it shows.
    pub version: Option<i64>,
}

fn shown(view: StoreView) -> CmdResult<GameCosmetics> {
    if !view.signed_in {
        return Err(view.problem.unwrap_or_else(|| SIGN_IN_IN_APP.into()));
    }
    let inventory = view.inventory;
    let version = inventory.as_ref().map(|i| i.version);
    let mut budget = GAME_ANIMATION_BYTES;
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
                    animation: o.asset.as_ref().and_then(|a| a.animation.as_ref()).and_then(anim::manifest).filter(|m| {
                        let bytes = m.to_string().len();
                        let fits = bytes <= budget;
                        if fits {
                            budget -= bytes;
                        }
                        fits
                    }),
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
        version,
    })
}

/// The account's items (fresh from the Store, or its last list marked stale).
pub async fn cosmetics(state: &AppState, uuid: &str) -> CmdResult<GameCosmetics> {
    shown(view(state, uuid, false).await)
}

/// Wears `id` in `slot` (or empties the slot): the Store decides, and every running game is told. `heard` gets the Store's
/// answer as the app's Store page shows it (the app's window is told, so the launcher shows what the game put on).
pub async fn wear(state: &AppState, uuid: &str, slot: &str, id: Option<&str>, heard: impl FnOnce(&StoreView)) -> CmdResult<GameCosmetics> {
    let view = equip(state, uuid, slot, id).await?;
    heard(&view);
    shown(view)
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
