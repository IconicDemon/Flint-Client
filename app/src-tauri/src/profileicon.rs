//! Profile icons and the sidebar's pins.
//!
//! An icon is one of:
//! - a Minecraft item or block on a coloured tile, `{"kind":"item","name":"item/diamond","color":"ember"}`. The picture is
//!   read from the player's own installed Minecraft (its client jars), never shipped with Flint: [`ICON_GROUPS`] lists
//!   the textures offered; the tile is one of [`COLORS`] or any colour picked, `#rrggbb`;
//! - the player's own picture, `{"kind":"image","file":"<id>-<n>.png"}`, copied into `profile-icons/` in Flint's data
//!   folder (PNG, JPEG, WebP or GIF, at most [`MAX_PICTURE`] bytes).
//!
//! Without one, the interface shows the profile's version picture.

use crate::core::*;
use crate::data::{find_profile, update_profile, Profile};
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tauri::State;

/// The Minecraft textures offered as icons, in the picker's groups, as `textures/<path>.png` in the game's jar (as 1.21 and
/// 26.x name them; 1.8.9's names are looked up too, `legacy_path`). The picker shows those the installed versions have.
pub const ICON_GROUPS: &[(&str, &[&str])] = &[
    (
        "Treasure",
        &[
            "item/diamond",
            "item/emerald",
            "item/netherite_ingot",
            "item/gold_ingot",
            "item/iron_ingot",
            "item/amethyst_shard",
            "item/nether_star",
            "item/heart_of_the_sea",
            "item/echo_shard",
            "item/totem_of_undying",
            "item/end_crystal",
            "item/trial_key",
            "item/ominous_trial_key",
            "item/nautilus_shell",
        ],
    ),
    (
        "Combat",
        &[
            "item/diamond_sword",
            "item/netherite_sword",
            "item/golden_sword",
            "item/iron_sword",
            "item/mace",
            "item/trident",
            "item/bow",
            "item/crossbow_standby",
            "item/netherite_axe",
            "item/arrow",
            "item/fire_charge",
            "item/wind_charge",
            "item/diamond_helmet",
            "item/netherite_chestplate",
            "item/turtle_helmet",
            "item/elytra",
        ],
    ),
    (
        "Tools",
        &[
            "item/diamond_pickaxe",
            "item/netherite_pickaxe",
            "item/flint_and_steel",
            "item/fishing_rod",
            "item/shears",
            "item/spyglass",
            "item/brush",
            "item/lead",
            "item/name_tag",
            "item/saddle",
            "item/lava_bucket",
            "item/water_bucket",
            "item/axolotl_bucket",
            "item/compass_00",
            "item/clock_00",
            "item/map",
        ],
    ),
    (
        "Magic",
        &[
            "item/ender_pearl",
            "item/ender_eye",
            "item/blaze_rod",
            "item/blaze_powder",
            "item/breeze_rod",
            "item/experience_bottle",
            "item/enchanted_book",
            "item/dragon_breath",
            "item/ghast_tear",
            "item/magma_cream",
            "item/slime_ball",
            "item/phantom_membrane",
            "item/shulker_shell",
            "item/redstone",
            "item/glowstone_dust",
            "item/music_disc_pigstep",
            "item/goat_horn",
            "item/firework_rocket",
        ],
    ),
    (
        "Food",
        &[
            "item/golden_apple",
            "item/apple",
            "item/golden_carrot",
            "item/cake",
            "item/cookie",
            "item/bread",
            "item/sweet_berries",
            "item/glow_berries",
            "item/honey_bottle",
            "item/melon_slice",
            "item/pumpkin_pie",
            "item/rabbit_foot",
            "item/cooked_beef",
            "item/carrot",
        ],
    ),
    (
        "Blocks",
        &[
            "block/tnt_side",
            "block/beacon",
            "block/dragon_egg",
            "block/crying_obsidian",
            "block/obsidian",
            "block/ancient_debris_side",
            "block/diamond_ore",
            "block/emerald_ore",
            "block/coal_ore",
            "block/netherite_block",
            "block/diamond_block",
            "block/gold_block",
            "block/emerald_block",
            "block/amethyst_block",
            "block/glowstone",
            "block/redstone_torch",
            "block/crafting_table_front",
            "block/bookshelf",
            "block/jack_o_lantern",
            "block/slime_block",
            "block/spawner",
            "block/note_block",
            "block/target_side",
            "block/shroomlight",
            "block/end_stone",
            "block/purpur_block",
            "block/blue_ice",
            "block/mossy_cobblestone",
            "block/oak_log",
            "block/honeycomb_block",
        ],
    ),
];

/// Whether `name` is one of the offered textures.
fn is_icon(name: &str) -> bool {
    ICON_GROUPS.iter().any(|(_, icons)| icons.contains(&name))
}

/// Every offered texture, in the picker's order.
fn all_icons() -> impl Iterator<Item = &'static str> {
    ICON_GROUPS.iter().flat_map(|(_, icons)| icons.iter().copied())
}

/// The named tiles an item sits on (the interface draws each as a gradient); a tile can also be any colour, `#rrggbb`.
pub const COLORS: &[&str] = &["ember", "amber", "crimson", "rose", "violet", "ocean", "teal", "emerald", "slate", "night"];

/// A named tile or a colour the player picked (`#rrggbb`).
fn color_ok(color: &str) -> bool {
    COLORS.contains(&color) || (color.len() == 7 && color.starts_with('#') && color[1..].chars().all(|c| c.is_ascii_hexdigit()))
}

/// The largest picture a player can use (a profile icon is shown at most a few dozen pixels across).
pub const MAX_PICTURE: u64 = 2 * 1024 * 1024;

fn folder(state: &AppState) -> PathBuf {
    state.paths.root.join("profile-icons")
}

/// Whether `icon` is one Flint knows how to show.
pub fn valid(icon: &Value) -> bool {
    match icon["kind"].as_str() {
        Some("item") => {
            icon["name"].as_str().is_some_and(is_icon) && icon["color"].as_str().is_some_and(color_ok)
        }
        Some("image") => icon["file"].as_str().is_some_and(picture_name_ok),
        _ => false,
    }
}

/// `<profile id>-<number>.<png|jpg|webp|gif>`: a name Flint made, never a path.
fn picture_name_ok(name: &str) -> bool {
    let Some((stem, ext)) = name.rsplit_once('.') else { return false };
    matches!(ext, "png" | "jpg" | "webp" | "gif")
        && stem.len() <= 64
        && stem.split_once('-').is_some_and(|(id, n)| is_safe_id(id) && !n.is_empty() && n.chars().all(|c| c.is_ascii_digit()))
}

fn picture_path(state: &AppState, icon: Option<&Value>) -> Option<PathBuf> {
    let icon = icon?;
    if icon["kind"] != "image" {
        return None;
    }
    let file = icon["file"].as_str().filter(|f| picture_name_ok(f))?;
    Some(folder(state).join(file))
}

/// Removes a profile's own picture (an item icon or none: nothing to remove).
pub fn remove_picture(state: &AppState, icon: Option<&Value>) {
    if let Some(path) = picture_path(state, icon) {
        let _ = std::fs::remove_file(path);
    }
}

/// The icon a duplicate of a profile gets: the same item, or its own copy of the picture (none when it can't be copied).
pub fn copy_for(state: &AppState, icon: Option<&Value>, new_id: &str) -> Option<Value> {
    let icon = icon?;
    let Some(from) = picture_path(state, Some(icon)) else {
        return valid(icon).then(|| icon.clone());
    };
    let ext = from.extension()?.to_str()?.to_owned();
    let file = format!("{new_id}-{}.{ext}", now_millis());
    std::fs::copy(&from, folder(state).join(&file)).ok()?;
    Some(json!({ "kind": "image", "file": file }))
}

fn now_millis() -> u128 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0)
}

/// What a picture's first bytes say it is: its extension and type, or None for anything else.
fn picture_kind(head: &[u8]) -> Option<(&'static str, &'static str)> {
    if head.starts_with(&[0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1A, b'\n']) {
        Some(("png", "image/png"))
    } else if head.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some(("jpg", "image/jpeg"))
    } else if head.len() >= 12 && &head[0..4] == b"RIFF" && &head[8..12] == b"WEBP" {
        Some(("webp", "image/webp"))
    } else if head.starts_with(b"GIF87a") || head.starts_with(b"GIF89a") {
        Some(("gif", "image/gif"))
    } else {
        None
    }
}

fn mime_of(file: &str) -> &'static str {
    match file.rsplit('.').next() {
        Some("png") => "image/png",
        Some("jpg") => "image/jpeg",
        Some("webp") => "image/webp",
        _ => "image/gif",
    }
}

/// Sets a profile's icon (an item on a tile), or clears it (None: the version's picture again). A picture it had is
/// removed.
#[tauri::command]
pub async fn profiles_set_icon(state: State<'_, AppState>, id: String, icon: Option<Value>) -> CmdResult<Profile> {
    if let Some(icon) = &icon {
        if icon["kind"] != "item" || !valid(icon) {
            return Err("That icon isn't one Flint offers.".into());
        }
    }
    let before = find_profile(&state, &id)?;
    let updated = update_profile(&state, &id, |p| p.icon = icon).await?;
    remove_picture(&state, before.icon.as_ref());
    Ok(updated)
}

/// Uses a picture from this computer as a profile's icon: the system's open dialog, then a copy into Flint's data
/// folder. None when the dialog was cancelled.
#[tauri::command]
pub async fn profiles_choose_icon_picture(state: State<'_, AppState>, window: tauri::Window, id: String) -> CmdResult<Option<Profile>> {
    let before = find_profile(&state, &id)?;
    let picked = tokio::task::spawn_blocking(move || {
        rfd::FileDialog::new()
            .set_title("Choose a picture for this profile")
            .add_filter("Pictures", &["png", "jpg", "jpeg", "webp", "gif"])
            .set_parent(&window)
            .pick_file()
    })
    .await
    .map_err(|e| e.to_string())?;
    let Some(source) = picked else { return Ok(None) };
    let file = copy_picture(&state, &id, &source)?;
    let updated = update_profile(&state, &id, |p| p.icon = Some(json!({ "kind": "image", "file": file }))).await?;
    remove_picture(&state, before.icon.as_ref());
    Ok(Some(updated))
}

/// Checks a picture (size, and that it really is one) and copies it in under a name Flint makes.
fn copy_picture(state: &AppState, id: &str, source: &Path) -> CmdResult<String> {
    let size = std::fs::metadata(source).map_err(|e| format!("Couldn't read that picture: {e}"))?.len();
    if size > MAX_PICTURE {
        return Err("That picture is too large. Choose one under 2 MB.".into());
    }
    let mut bytes = Vec::with_capacity(size as usize);
    std::fs::File::open(source).and_then(|f| f.take(MAX_PICTURE + 1).read_to_end(&mut bytes)).map_err(|e| format!("Couldn't read that picture: {e}"))?;
    let Some((ext, _)) = picture_kind(&bytes) else {
        return Err("That file isn't a PNG, JPEG, WebP or GIF picture.".into());
    };
    let dir = folder(state);
    std::fs::create_dir_all(&dir).map_err(|e| format!("Couldn't save the picture: {e}"))?;
    let file = format!("{id}-{}.{ext}", now_millis());
    write_atomic(&dir.join(&file), &bytes)?;
    Ok(file)
}

/// A profile's own picture as a data URL (None when its icon isn't a picture, or the file is gone).
#[tauri::command]
pub fn profiles_icon_picture(state: State<'_, AppState>, id: String) -> CmdResult<Option<String>> {
    let profile = find_profile(&state, &id)?;
    let Some(path) = picture_path(&state, profile.icon.as_ref()) else { return Ok(None) };
    let Ok(bytes) = std::fs::read(&path) else { return Ok(None) };
    if bytes.len() as u64 > MAX_PICTURE || picture_kind(&bytes).is_none() {
        return Ok(None);
    }
    let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
    Ok(Some(format!("data:{};base64,{}", mime_of(name), STANDARD.encode(bytes))))
}

/// How many profiles can be pinned: the sidebar shows five.
pub const MAX_PINNED: usize = 5;

/// Whether pinning `id` would make more than `MAX_PINNED` pinned profiles (pinning one already pinned is fine).
fn pin_refused(profiles: &[Profile], id: &str) -> bool {
    let already = profiles.iter().any(|p| p.id == id && p.pinned_at.is_some());
    let others = profiles.iter().filter(|p| p.id != id && p.pinned_at.is_some()).count();
    !already && others >= MAX_PINNED
}

/// Pins a profile to the sidebar (or unpins it). Pinned profiles come first there, in the order they were pinned; at most
/// five.
#[tauri::command]
pub async fn profiles_set_pinned(state: State<'_, AppState>, id: String, pinned: bool) -> CmdResult<Profile> {
    if pinned && pin_refused(&crate::data::load_profiles(&state).profiles, &id) {
        return Err(format!("You can pin up to {MAX_PINNED} profiles. Unpin one to pin this one."));
    }
    update_profile(&state, &id, |p| {
        if !pinned {
            p.pinned_at = None;
        } else if p.pinned_at.is_none() {
            p.pinned_at = Some(now_secs());
        }
    })
    .await
}

/// The icon textures, read once from the newest installed Minecraft that has them, as data URLs by name.
static TEXTURES: Mutex<Option<BTreeMap<String, String>>> = Mutex::new(None);

/// The item and block pictures the icon picker offers, from the player's own installed Minecraft. Empty until a version
/// with them is installed.
#[tauri::command]
pub async fn profile_icon_textures(state: State<'_, AppState>) -> CmdResult<Value> {
    if let Some(found) = TEXTURES.lock().unwrap().clone() {
        return Ok(catalog(found));
    }
    let versions = state.paths.game().join("versions");
    let found = tokio::task::spawn_blocking(move || read_textures(&versions)).await.map_err(|e| e.to_string())?;
    if !found.is_empty() {
        *TEXTURES.lock().unwrap() = Some(found.clone());
    }
    Ok(catalog(found))
}

/// The picker's groups (each with only the icons a texture was found for), the named tiles, and the textures.
fn catalog(textures: BTreeMap<String, String>) -> Value {
    let groups: Vec<Value> = ICON_GROUPS
        .iter()
        .map(|(name, icons)| json!({ "name": name, "icons": icons.iter().filter(|i| textures.contains_key(**i)).collect::<Vec<_>>() }))
        .filter(|g| g["icons"].as_array().is_some_and(|a| !a.is_empty()))
        .collect();
    let icons: Vec<&str> = all_icons().filter(|i| textures.contains_key(*i)).collect();
    json!({ "groups": groups, "icons": icons, "colors": COLORS, "textures": textures })
}

/// The icons' textures from the installed client jars, newest first, each from the newest that has it (1.8.9 alone still
/// gives the icons it has).
fn read_textures(versions: &Path) -> BTreeMap<String, String> {
    let mut jars: Vec<(std::time::SystemTime, PathBuf)> = std::fs::read_dir(versions)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|entry| {
            let name = entry.file_name().to_string_lossy().into_owned();
            let jar = entry.path().join(format!("{name}.jar"));
            let modified = std::fs::metadata(&jar).ok()?.modified().ok()?;
            Some((modified, jar))
        })
        .collect();
    jars.sort_by_key(|a| std::cmp::Reverse(a.0));
    let total = all_icons().count();
    let mut found = BTreeMap::new();
    for (_, jar) in jars {
        textures_in(&jar, &mut found);
        if found.len() == total {
            break;
        }
    }
    found
}

/// Where 1.8.9 keeps `name`'s texture: `items/` and `blocks/`, and the names it used before 1.13.
fn legacy_path(name: &str) -> String {
    let renamed = match name {
        "item/redstone" => "items/redstone_dust",
        "item/golden_apple" => "items/apple_golden",
        "item/golden_carrot" => "items/carrot_golden",
        "item/melon_slice" => "items/melon",
        "item/golden_sword" => "items/gold_sword",
        "item/bow" => "items/bow_standby",
        "item/fire_charge" => "items/fireball",
        "item/fishing_rod" => "items/fishing_rod_uncast",
        "item/lava_bucket" => "items/bucket_lava",
        "item/water_bucket" => "items/bucket_water",
        "item/enchanted_book" => "items/book_enchanted",
        "item/firework_rocket" => "items/fireworks",
        "item/compass_00" => "items/compass",
        "item/clock_00" => "items/clock",
        "item/map" => "items/map_empty",
        "item/cooked_beef" => "items/beef_cooked",
        "item/slime_ball" => "items/slimeball",
        "block/redstone_torch" => "blocks/redstone_torch_on",
        "block/jack_o_lantern" => "blocks/pumpkin_face_on",
        "block/slime_block" => "blocks/slime",
        "block/spawner" => "blocks/mob_spawner",
        "block/note_block" => "blocks/noteblock",
        "block/mossy_cobblestone" => "blocks/cobblestone_mossy",
        "block/oak_log" => "blocks/log_oak",
        _ => "",
    };
    if !renamed.is_empty() {
        return renamed.to_owned();
    }
    match name.split_once('/') {
        Some(("item", rest)) => format!("items/{rest}"),
        Some(("block", rest)) => format!("blocks/{rest}"),
        _ => name.to_owned(),
    }
}

/// A PNG that is one square picture (an animated texture is a strip of frames, taller than wide: not offered).
fn square_png(bytes: &[u8]) -> bool {
    picture_kind(bytes).is_some_and(|(ext, _)| ext == "png")
        && bytes.len() >= 24
        && bytes[16..20] == bytes[20..24]
        && u32::from_be_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]) > 0
}

/// Adds the textures `jar` has of the icons not found yet to `out`.
fn textures_in(jar: &Path, out: &mut BTreeMap<String, String>) {
    let Ok(file) = std::fs::File::open(jar) else { return };
    let Ok(mut archive) = zip::ZipArchive::new(file) else { return };
    for name in all_icons() {
        if out.contains_key(name) {
            continue;
        }
        for at in [name.to_owned(), legacy_path(name)] {
            let Ok(mut entry) = archive.by_name(&format!("assets/minecraft/textures/{at}.png")) else { continue };
            if entry.size() > 64 * 1024 {
                break;
            }
            let mut bytes = Vec::new();
            if entry.read_to_end(&mut bytes).is_ok() && square_png(&bytes) {
                out.insert(name.to_owned(), format!("data:image/png;base64,{}", STANDARD.encode(&bytes)));
            }
            break;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn at_most_five_profiles_are_pinned() {
        let profile = |id: &str, pinned: bool| Profile {
            id: id.into(),
            pinned_at: pinned.then_some(1),
            ..Profile::default()
        };
        let mut profiles: Vec<Profile> = (0..5).map(|i| profile(&format!("p{i}"), true)).collect();
        profiles.push(profile("new", false));
        assert!(pin_refused(&profiles, "new"), "a sixth is refused");
        assert!(!pin_refused(&profiles, "p0"), "one already pinned can be pinned again");
        profiles[0].pinned_at = None;
        assert!(!pin_refused(&profiles, "new"), "with four pinned, a fifth is fine");
    }

    #[test]
    fn only_the_offered_items_and_tiles_are_icons() {
        assert!(valid(&json!({ "kind": "item", "name": "item/diamond", "color": "ember" })));
        assert!(valid(&json!({ "kind": "item", "name": "block/tnt_side", "color": "night" })));
        assert!(!valid(&json!({ "kind": "item", "name": "item/../../secret", "color": "ember" })), "not an offered texture");
        assert!(valid(&json!({ "kind": "item", "name": "item/mace", "color": "#ff7a2F" })), "any colour the player picks");
        assert!(!valid(&json!({ "kind": "item", "name": "item/diamond", "color": "#ff00" })), "not a colour");
        assert!(!valid(&json!({ "kind": "item", "name": "item/diamond", "color": "red" })), "not a tile");
        assert!(!valid(&json!({ "kind": "item", "name": "item/diamond" })));
        assert!(!valid(&json!({ "kind": "other" })));
        assert_eq!(all_icons().count(), 108);
        let mut seen = std::collections::HashSet::new();
        assert!(all_icons().all(|i| seen.insert(i)), "each icon once");
    }

    #[test]
    fn older_versions_are_read_by_their_own_names() {
        assert_eq!(legacy_path("item/diamond"), "items/diamond");
        assert_eq!(legacy_path("block/tnt_side"), "blocks/tnt_side");
        assert_eq!(legacy_path("item/golden_apple"), "items/apple_golden");
        assert_eq!(legacy_path("block/spawner"), "blocks/mob_spawner");
        let png = |w: u8, h: u8| {
            let mut b = vec![0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1A, b'\n', 0, 0, 0, 13, b'I', b'H', b'D', b'R', 0, 0, 0, w, 0, 0, 0, h];
            b.extend([8, 6, 0, 0, 0]);
            b
        };
        assert!(square_png(&png(16, 16)));
        assert!(!square_png(&png(16, 80)), "an animated strip");
    }

    #[test]
    fn a_picture_file_is_a_name_flint_made() {
        assert!(picture_name_ok("abcdef123456-1793567026123.png"));
        assert!(picture_name_ok("abcdef123456-1.webp"));
        assert!(!picture_name_ok("../abcdef123456-1.png"));
        assert!(!picture_name_ok("abcdef123456-1.exe"));
        assert!(!picture_name_ok("abcdef123456-x1.png"));
        assert!(!picture_name_ok("C:\\x\\abcdef123456-1.png"));
        assert!(valid(&json!({ "kind": "image", "file": "abcdef123456-5.jpg" })));
        assert!(!valid(&json!({ "kind": "image", "file": "/etc/passwd" })));
    }

    #[test]
    fn pictures_are_known_by_their_first_bytes() {
        assert_eq!(picture_kind(&[0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1A, b'\n', 0]).map(|k| k.0), Some("png"));
        assert_eq!(picture_kind(&[0xFF, 0xD8, 0xFF, 0xE0]).map(|k| k.0), Some("jpg"));
        assert_eq!(picture_kind(b"RIFF\0\0\0\0WEBPVP8 ").map(|k| k.0), Some("webp"));
        assert_eq!(picture_kind(b"GIF89a....").map(|k| k.0), Some("gif"));
        assert_eq!(picture_kind(b"MZ\x90\0"), None, "a program is not a picture");
        assert_eq!(picture_kind(b"<svg"), None, "no SVG: it can carry scripts");
    }

    #[test]
    fn a_picture_is_copied_in_checked_and_removed_with_its_profile() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let source = dir.0.join("mine.png");
        std::fs::write(&source, [0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1A, b'\n', 1, 2, 3]).unwrap();
        let file = copy_picture(&state, "abcdef123456", &source).unwrap();
        assert!(picture_name_ok(&file) && file.starts_with("abcdef123456-") && file.ends_with(".png"));
        let icon = json!({ "kind": "image", "file": file });
        let copy = copy_for(&state, Some(&icon), "bbbbbbbbbbbb").expect("a duplicate gets its own copy");
        assert_ne!(copy["file"], icon["file"]);
        remove_picture(&state, Some(&icon));
        assert!(!folder(&state).join(icon["file"].as_str().unwrap()).exists());
        assert!(folder(&state).join(copy["file"].as_str().unwrap()).exists(), "the duplicate's copy stays");
        let fake = dir.0.join("fake.png");
        std::fs::write(&fake, b"MZ not a picture").unwrap();
        assert!(copy_picture(&state, "abcdef123456", &fake).is_err());
    }
}
