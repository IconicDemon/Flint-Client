//! The game wardrobe's locked items (shop.rs, game.rs) against the fake Store (fake.rs).
use super::tests::{rig, STEVE};
use super::*;

/// A small WebP picture (a 3D cosmetic's thumbnail as the Store may keep it).
fn webp(w: u32, h: u32) -> Vec<u8> {
    let pixels: Vec<u8> = (0..w * h).flat_map(|i| [(i % 251) as u8, 40, 200, 255]).collect();
    let mut out = Vec::new();
    image_webp::WebPEncoder::new(&mut out).encode(&pixels, w, h, image_webp::ColorType::Rgba8).unwrap();
    out
}

#[tokio::test]
async fn a_store_without_the_catalog_lists_nothing_locked() {
    let rig = rig().await;
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);
    let shown = game::cosmetics(&rig.state, STEVE).await.unwrap();
    assert_eq!(shown.items.len(), 1);
    assert!(shown.shop.is_empty(), "the route is a 404 there: no locked items, nothing broken");
    assert_eq!(rig.fake.with(|w| w.count("GET /api/v1/catalog/cosmetics/ 404")), 1);
}

#[tokio::test]
async fn the_game_gets_what_the_account_doesnt_own_with_its_price_and_its_checked_picture() {
    let rig = rig().await;
    let wings = webp(32, 32);
    let wings_id = rig.fake.with(|w| {
        w.catalog = true;
        w.prices.insert(rig.frost.clone(), 400);
        w.add_model_cosmetic("glass-wings", "wings", &[("geometry", br#"{"format_version":"1.12.0"}"#.as_slice()), ("thumbnail", wings.as_slice())])
    });
    rig.sign_in(STEVE).await;
    rig.grant(STEVE, &rig.ember);
    let shown = game::cosmetics(&rig.state, STEVE).await.unwrap();
    let ids: Vec<&str> = shown.shop.iter().map(|s| s.id.as_str()).collect();
    assert!(!ids.contains(&rig.ember.as_str()), "what the account owns isn't locked");
    let frost = shown.shop.iter().find(|s| s.id == rig.frost).expect("the cape it doesn't own is listed");
    assert_eq!((frost.kind.as_str(), frost.slot.as_deref(), frost.price_coins, frost.source), ("cape", Some("cape"), Some(400), "store"));
    let listed_wings = shown.shop.iter().find(|s| s.id == wings_id).expect("a 3D cosmetic is listed too");
    assert_eq!((listed_wings.kind.as_str(), listed_wings.slot.as_deref()), ("wings", Some("back")));

    // Its picture: the cape's texture as the catalog hashed it, and the wings' WebP thumbnail as a PNG.
    let png = STANDARD.decode(game::art(&rig.state, STEVE, &rig.frost).await.unwrap()).unwrap();
    assert_eq!(png, super::tests::FROST);
    let thumb = STANDARD.decode(game::art(&rig.state, STEVE, &wings_id).await.unwrap()).unwrap();
    assert!(thumb.starts_with(PNG_SIGNATURE), "a WebP thumbnail reaches the game as a PNG");

    // A picture that isn't what the catalog hashed is refused.
    let path = rig.fake.with(|w| w.cosmetics[&rig.frost].path.clone());
    rig.fake.with(|w| w.files.insert(path, b"\x89PNG\r\n\x1a\nswapped".to_vec()));
    // The shared cache emptied, so it is downloaded again.
    let cached = asset_cache::dir(&rig.state);
    let _ = std::fs::remove_dir_all(&cached);
    assert!(game::art(&rig.state, STEVE, &rig.frost).await.is_err());
}

#[tokio::test]
async fn the_catalog_is_asked_again_with_its_etag_and_not_more_than_once_a_minute() {
    let rig = rig().await;
    rig.fake.with(|w| w.catalog = true);
    assert_eq!(shop::listed(&rig.state).await.len(), 2);
    assert_eq!(shop::listed(&rig.state).await.len(), 2);
    assert_eq!(rig.fake.with(|w| w.count("GET /api/v1/catalog/cosmetics/ 200")), 1, "a minute's cache");
    shop::age(&rig.state);
    assert_eq!(shop::listed(&rig.state).await.len(), 2, "an unchanged catalog keeps its items");
    assert_eq!(rig.fake.with(|w| w.count("GET /api/v1/catalog/cosmetics/ 304")), 1);
}

#[test]
fn listed_items_flint_cant_show_are_left_out() {
    let id = "00000000-0000-4000-8000-000000000001";
    let body = serde_json::json!({ "schema": 1, "items": [
        { "cosmetic_id": id, "kind": "back", "name": "Old wings name", "price_coins": 10 },
        { "cosmetic_id": id, "kind": "cape", "name": "Twice" },
        { "cosmetic_id": "not-a-uuid", "kind": "cape", "name": "Bad" },
        { "cosmetic_id": "00000000-0000-4000-8000-000000000002", "kind": "jetpack", "name": "Unknown kind" },
        { "cosmetic_id": "00000000-0000-4000-8000-000000000003", "kind": "pet", "name": " ", "price_coins": -5 },
    ]});
    let items = shop::parse(&body).unwrap();
    assert_eq!(items.len(), 1);
    assert_eq!((items[0].kind.as_str(), items[0].slot.as_deref(), items[0].price_coins), ("wings", Some("back"), Some(10)));
    assert!(shop::parse(&serde_json::json!({ "schema": 2, "items": [] })).is_none());
}

/// The website's local demo Store (http://localhost:3200), when it runs: its catalog parses, every listed item keeps its kind
/// and slot, and the ones with pictures carry a checked asset. Run by hand: `cargo test demo_catalog_live -- --ignored`.
#[tokio::test]
#[ignore]
async fn demo_catalog_live() {
    let body: Value = crate::core::test_client().get("http://localhost:3200/api/v1/catalog/cosmetics/").send().await.unwrap().json().await.unwrap();
    let items = shop::parse(&body).expect("the contract's shape");
    for i in &items {
        println!("{:9} slot {:7} price {:?} asset {} model {} | {}", i.kind, i.slot.as_deref().unwrap_or("-"), i.price_coins, i.asset.is_some(),
            i.asset.as_ref().is_some_and(|a| a.model.is_some()), i.name);
    }
    assert!(!items.is_empty());
    assert!(items.iter().all(|i| kinds::is_known(&i.kind)));
}
