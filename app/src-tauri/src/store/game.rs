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
    /// The Store's kind ([`super::kinds`]: cape, wings, headwear, aura, pet, shield, gloves, emote). Kinds Flint
    /// doesn't know aren't listed to a game.
    pub kind: String,
    /// The slot it is worn in ([`super::SLOTS`]); none for an emote (it goes on the wheel).
    pub slot: Option<String>,
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
    /// A 3D cosmetic's model as the game draws it ([`super::model::Model`]: sizes, frames, anchor; no addresses or hashes):
    /// the game asks for its files with `modelFile`, which Flint checks. Absent for a cape.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<Value>,
}

/// A 3D cosmetic's model for a game (no addresses or hashes: those stay in Flint).
pub fn game_model(asset: Option<&Asset>) -> Option<Value> {
    asset.and_then(|a| a.model.as_ref()).and_then(|m| serde_json::to_value(m).ok())
}

/// The most bytes of animation manifests sent to a game in one list: with 500 items the list stays well inside the 512 KiB
/// a game (1.0.2 and earlier included) takes for it.
pub const GAME_ANIMATION_BYTES: usize = 192 * 1024;

/// One cosmetic the account doesn't own, shown locked in a game's wardrobe ([`shop`]).
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameShopItem {
    pub id: String,
    pub slug: String,
    pub kind: String,
    pub slot: Option<String>,
    pub name: String,
    /// The Merit price; none for a reward (earned) or one not sold for Merit.
    pub price_coins: Option<i64>,
    /// Its picture's version (ask `cosmeticArt` for it), or none when it has none.
    pub art: Option<i64>,
    pub animated: bool,
    /// A 3D cosmetic's model, as [`GameItem::model`].
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<Value>,
    /// `store` (for sale) or `reward` (a community reward).
    pub source: &'static str,
    /// How a reward unlocks (`discord_member`: in the Flint Discord; `discord_booster`: boosting it; `first_launch`…), so
    /// the game says that instead of "In the Store". None for an item for sale.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub unlock: Option<String>,
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
    /// The Store's inventory version of this list (it only grows): a game drops a list older than the one it shows.
    pub version: Option<i64>,
    /// The emote wheel: always [`super::kinds::EMOTE_SLOTS`] places, each an owned emote's id or null.
    pub emotes: Vec<Option<String>>,
    /// What the account could still get ([`shop`]): the Store's listed cosmetics and community rewards it doesn't own.
    pub shop: Vec<GameShopItem>,
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
                .filter(|o| kinds::is_known(&o.kind))
                .take(GAME_ITEMS)
                .map(|o| GameItem {
                    id: o.cosmetic_id.clone(),
                    slug: o.slug.clone(),
                    kind: o.kind.clone(),
                    slot: o.slot.clone(),
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
                    model: game_model(o.asset.as_ref()),
                })
                .collect()
        })
        .unwrap_or_default();
    let mut emotes = inventory.as_ref().and_then(|i| i.emotes.clone()).unwrap_or_default();
    emotes.resize(kinds::EMOTE_SLOTS, None);
    Ok(GameCosmetics {
        store_url: view.store_url,
        name: view.name,
        items,
        emotes,
        shop: Vec::new(),
        equipped: inventory.map(|i| i.equipped).unwrap_or_default(),
        stale: view.stale,
        problem: view.problem,
        fetched_at: view.fetched_at,
        version,
    })
}

/// The account's items (fresh from the Store, or its last list marked stale), and what it could still get.
pub async fn cosmetics(state: &AppState, uuid: &str) -> CmdResult<GameCosmetics> {
    let view = view(state, uuid, false).await;
    let owned: HashSet<String> = view.inventory.as_ref().map(|i| i.owned.iter().map(|o| o.cosmetic_id.clone()).collect()).unwrap_or_default();
    let mut shown = shown(view)?;
    shown.shop = shop_for_game(state, &owned).await;
    Ok(shown)
}

/// The locked items a game shows: [`shop::for_game`] in the game's shape.
pub async fn shop_for_game(state: &AppState, owned: &HashSet<String>) -> Vec<GameShopItem> {
    shop::for_game(state, owned)
        .await
        .into_iter()
        .map(|s| GameShopItem {
            art: s.asset.as_ref().map(|a| a.version),
            model: game_model(s.asset.as_ref()),
            id: s.cosmetic_id,
            slug: s.slug,
            kind: s.kind,
            slot: s.slot,
            name: s.name,
            price_coins: s.price_coins,
            animated: s.animated,
            source: s.source,
            unlock: s.unlock,
        })
        .collect()
}

/// Wears `id` in `slot` (or empties the slot): the Store decides, and every running game is told. `heard` gets the Store's
/// answer as the app's Store page shows it (the app's window is told, so the launcher shows what the game put on).
pub async fn wear(state: &AppState, uuid: &str, slot: &str, id: Option<&str>, heard: impl FnOnce(&StoreView)) -> CmdResult<GameCosmetics> {
    let view = equip(state, uuid, slot, id).await?;
    heard(&view);
    shown(view)
}

/// Puts these emotes on the wheel (exactly 8 places, each an emote id or empty): the Store decides, and every running game
/// is told. `heard` gets the Store's answer as the app's Store page shows it.
pub async fn wheel(state: &AppState, uuid: &str, slots: &[Option<String>], heard: impl FnOnce(&StoreView)) -> CmdResult<GameCosmetics> {
    let view = set_emotes(state, uuid, slots).await?;
    heard(&view);
    shown(view)
}

/// The picture of an item the account owns, as base64 PNG: the texture the Store hashed, or for a 3D cosmetic without one
/// (wings, pets, emotes…) its 512×512 thumbnail when that is a PNG.
///
/// An item the account doesn't own is answered from the Store's listing ([`shop::art`]: checked against the catalog's hash),
/// so a locked card shows its real picture. A WebP thumbnail comes back as a PNG.
pub async fn art(state: &AppState, uuid: &str, id: &str) -> CmdResult<String> {
    let owned = load_cache(state, uuid).and_then(|c| c.inventory.owned.into_iter().find(|o| o.cosmetic_id == id));
    let Some(owned) = owned else {
        return Ok(STANDARD.encode(shop::art(state, id).await?));
    };
    let textured = owned.asset.is_none_or(|a| a.has_texture);
    let url = if textured { asset(state, uuid, id).await? } else { model::model_file(state, uuid, id, "thumbnail").await? };
    if let Some(png) = url.strip_prefix("data:image/png;base64,") {
        return Ok(png.to_owned());
    }
    let webp = url.strip_prefix("data:image/webp;base64,").and_then(|b| STANDARD.decode(b).ok()).ok_or("This item has no picture Flint can show.")?;
    let png = tokio::task::spawn_blocking(move || shop::as_png(webp)).await.unwrap_or_else(|_| Err("internal error".into()))?;
    Ok(STANDARD.encode(png))
}

/// The Flint account a game plays as, if it may use the Store: a Microsoft account of Flint's.
pub fn game_account(state: &AppState, playing: &str) -> CmdResult<String> {
    let (uuid, account) = flint_account(state, playing).map_err(|_| "Flint doesn't have the account this game plays as.".to_owned())?;
    if account.qa {
        return Err("The test identity has no Minecraft account, so it can't use the Flint Store.".into());
    }
    Ok(uuid)
}

/// An emote as a game plays it on another player (the emote room): `{"id", "kind": "emote", "name", "model"}`, from what
/// the account owns or the Store lists. None when Flint doesn't know it (the game then doesn't play it).
pub async fn emote_item(state: &AppState, uuid: &str, cosmetic_id: &str) -> Option<Value> {
    let owned = load_cache(state, uuid).and_then(|c| c.inventory.owned.iter().find(|o| o.cosmetic_id == cosmetic_id).map(|o| (o.kind.clone(), o.name.clone(), o.asset.clone())));
    let (kind, name, asset) = match owned {
        Some(found) => found,
        None => shop::listed(state).await.into_iter().find(|s| s.cosmetic_id == cosmetic_id).map(|s| (s.kind, s.name, s.asset))?,
    };
    if kind != kinds::EMOTE {
        return None;
    }
    let model = game_model(asset.as_ref())?;
    Some(serde_json::json!({ "id": cosmetic_id, "kind": kind, "name": name, "model": model }))
}
