//! The Flint Store's other cosmetics, for the game's wardrobe: what a player could get, shown locked with its real picture.
//!
//! - `GET /api/v1/catalog/cosmetics/` (public, nothing per player): every listed cosmetic, with its kind, Merit price and the
//!   same `asset` object the inventory has. Asked for at most once a minute, with its ETag. A Store without the route (a 404)
//!   lists nothing, so the game simply shows no locked items.
//! - The community rewards (`community.rs`, `/catalog/community/`) join them: a Discord or event cape the player hasn't earned
//!   is shown locked too.
//! - A listed item's picture comes through the same checks as an owned one: the texture, or a 3D cosmetic's thumbnail,
//!   downloaded without any session from an address the Store allowlist accepts, checked against the catalog's SHA-256 (and
//!   size), and kept in Flint's shared cache of verified files (`asset_cache`, by hash; Flint's CDN first when the Store names
//!   it). A WebP thumbnail is turned into a PNG for the game.
use super::*;
use std::sync::OnceLock;

/// Listed cosmetics sent to a game at most.
pub const MAX_SHOP: usize = 300;
const EVERY_MS: u64 = 60_000;

/// One listed cosmetic.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ShopItem {
    pub cosmetic_id: String,
    pub slug: String,
    /// The Store's kind ([`kinds`]), never one Flint doesn't know.
    pub kind: String,
    pub slot: Option<String>,
    pub name: String,
    /// The Merit price, when it can be bought with Merit.
    pub price_coins: Option<i64>,
    pub animated: bool,
    /// `reward`: a community reward (earned, not bought); `store`: for sale.
    pub source: &'static str,
    /// A reward's requirement (the community catalog's `requirement.type`: `discord_member`, `discord_booster`,
    /// `first_launch`…): how it unlocks, never "buy it". None for an item for sale.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub unlock: Option<String>,
    #[serde(skip)]
    pub asset: Option<Asset>,
}

struct Cached {
    asked_at: u64,
    etag: Option<String>,
    items: Vec<ShopItem>,
}

/// By the Store's origin (tests run several Stores in one process).
fn cache() -> &'static Mutex<HashMap<String, Cached>> {
    static CACHE: OnceLock<Mutex<HashMap<String, Cached>>> = OnceLock::new();
    CACHE.get_or_init(Default::default)
}

/// The asset as the inventory checks it: a texture with a well-formed hash, and/or a 3D model with usable files.
fn checked_asset(value: &Value, kind: &str) -> Option<Asset> {
    let mut a: Asset = serde_json::from_value(value.clone()).ok()?;
    if !is_uuid(&a.id) || a.version < 0 {
        return None;
    }
    a.has_texture = !a.url.is_empty() && a.sha256.len() == 64 && a.sha256.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'));
    if !a.has_texture {
        a.animation = None;
    }
    a.model = a.model.take().and_then(|m| m.checked(kind));
    (a.has_texture || a.model.is_some()).then_some(a)
}

/// The catalog as the Store sent it, checked; items Flint can't show are left out. None when it isn't the contract's shape.
pub(crate) fn parse(body: &Value) -> Option<Vec<ShopItem>> {
    if body["schema"].as_i64() != Some(1) {
        return None;
    }
    let mut seen = HashSet::new();
    let items = body["items"]
        .as_array()?
        .iter()
        .filter_map(|v| {
            let cosmetic_id = v["cosmetic_id"].as_str().filter(|id| is_uuid(id))?.to_owned();
            let kind = v["kind"].as_str().and_then(kinds::normalize).filter(|k| kinds::is_known(k))?;
            let name = v["name"].as_str().map(|n| clip(n, 120)).filter(|n| !n.trim().is_empty())?;
            seen.insert(cosmetic_id.clone()).then_some(())?;
            Some(ShopItem {
                slot: kinds::slot_of(&kind).map(str::to_owned),
                slug: v["slug"].as_str().map(|s| clip(s, 80)).unwrap_or_default(),
                price_coins: v["price_coins"].as_i64().filter(|p| *p >= 0),
                animated: v["animated"].as_bool().unwrap_or(false),
                asset: checked_asset(&v["asset"], &kind),
                source: "store",
                unlock: None,
                cosmetic_id,
                kind,
                name,
            })
        })
        .take(MAX_SHOP)
        .collect();
    Some(items)
}

/// The Store's listed cosmetics (its last answer while it can't be reached; none from a Store without the route).
pub async fn listed(state: &AppState) -> Vec<ShopItem> {
    let key = state.store.endpoints.origin_key();
    let now = now_millis();
    let etag = {
        let cached = cache().lock().unwrap();
        match cached.get(&key) {
            Some(c) if now.saturating_sub(c.asked_at) < EVERY_MS => return c.items.clone(),
            Some(c) => c.etag.clone(),
            None => None,
        }
    };
    let answer = send(state, Method::GET, "/api/v1/catalog/cosmetics/", None, None, etag.as_deref()).await;
    let mut cached = cache().lock().unwrap();
    let previous = cached.get(&key).map(|c| c.items.clone()).unwrap_or_default();
    let (items, etag) = match answer {
        Ok(raw) if raw.status == 304 && etag.is_some() => (previous, raw.etag.or(etag)),
        Ok(raw) if (200..300).contains(&raw.status) => match parse(&raw.body) {
            Some(items) => (items, raw.etag),
            None => (Vec::new(), None),
        },
        Ok(raw) if matches!(raw.status, 404 | 405 | 410 | 501) => (Vec::new(), None),
        // Offline, rate limited, unavailable: the last answer, asked again in a minute.
        _ => (previous, etag),
    };
    cached.insert(key, Cached { asked_at: now, etag, items: items.clone() });
    items
}

/// Tests only: the next [`listed`] asks the Store again.
#[cfg(test)]
pub(crate) fn age(state: &AppState) {
    if let Some(c) = cache().lock().unwrap().get_mut(&state.store.endpoints.origin_key()) {
        c.asked_at = 0;
    }
}

/// What a game shows locked: the Store's listed cosmetics and the community rewards, minus what the account owns (`owned`),
/// rewards first among capes as the Store lists them.
pub async fn for_game(state: &AppState, owned: &HashSet<String>) -> Vec<ShopItem> {
    let mut out: Vec<ShopItem> = Vec::new();
    for r in community::catalog(state).await.rewards {
        if owned.contains(&r.cosmetic_id) || out.iter().any(|s| s.cosmetic_id == r.cosmetic_id) {
            continue;
        }
        out.push(ShopItem {
            slot: kinds::slot_of(&r.kind).map(str::to_owned),
            cosmetic_id: r.cosmetic_id,
            slug: r.slug,
            kind: r.kind,
            name: r.name,
            price_coins: None,
            animated: r.animated,
            source: "reward",
            unlock: Some(r.requirement.kind),
            asset: Some(r.asset),
        });
    }
    for item in listed(state).await {
        if !owned.contains(&item.cosmetic_id) && !out.iter().any(|s| s.cosmetic_id == item.cosmetic_id) {
            out.push(item);
        }
    }
    out.truncate(MAX_SHOP);
    out
}

/// A WebP picture as a PNG the game reads; a PNG as it is.
pub(crate) fn as_png(bytes: Vec<u8>) -> CmdResult<Vec<u8>> {
    if bytes.starts_with(PNG_SIGNATURE) {
        return Ok(bytes);
    }
    crate::partners::game::webp_to_png(&bytes, 512, 512).ok_or_else(|| "This item's picture isn't one Flint can show.".to_owned())
}

/// The PNG picture of a listed item (`id`, which the account doesn't own): its texture, else its thumbnail; checked against
/// the catalog's SHA-256 and kept in Flint's shared cache.
pub async fn art(state: &AppState, id: &str) -> CmdResult<Vec<u8>> {
    let listed: Vec<ShopItem> = {
        let cached = cache().lock().unwrap();
        cached.get(&state.store.endpoints.origin_key()).map(|c| c.items.clone()).unwrap_or_default()
    };
    if let Some(item) = listed.into_iter().find(|i| i.cosmetic_id == id) {
        let asset = item.asset.ok_or("This item has no picture.")?;
        if asset.has_texture {
            return verified_texture(state, &asset).await;
        }
        let thumb = asset.model.as_ref().and_then(|m| m.thumbnail.as_ref()).ok_or("This item has no picture.")?;
        let expected = thumb.bytes.unwrap_or(0) as usize;
        let bytes = verified_download(state, &thumb.url, thumb.cdn_url.as_deref(), &thumb.sha256, model::MAX_THUMBNAIL as usize, |bytes| {
            if bytes.len() != expected {
                return Err("This item's picture isn't the size the Flint Store listed, so Flint didn't use it.".into());
            }
            let webp = bytes.len() > 12 && bytes.starts_with(b"RIFF") && &bytes[8..12] == b"WEBP";
            (bytes.starts_with(PNG_SIGNATURE) || webp).then_some(()).ok_or_else(|| "This item's picture isn't a PNG or WebP image.".to_owned())
        })
        .await?;
        return tokio::task::spawn_blocking(move || as_png(bytes)).await.unwrap_or_else(|_| Err("internal error".into()));
    }
    // A community reward: its texture, as the launcher's Rewards page shows it.
    let url = community::reward_asset(state, id).await?;
    url.strip_prefix("data:image/png;base64,")
        .and_then(|b| STANDARD.decode(b).ok())
        .ok_or_else(|| "This item has no PNG picture.".to_owned())
}
