//! Minecraft 1.8.9's options.txt, translated to and from the form 1.21 and 26.x write, so Auto Sync can carry
//! the settings both kinds of game share.
//!
//! Read from 1.8.9's own `GameSettings` (`avh` in Mojang's jar; its `loadOptions` and `saveOptions`):
//! - There is no `version:` line and no data fixer: each `key:value` line is read on its own, and a line it
//!   can't read is skipped with the rest of the file still loaded.
//! - Key bindings are LWJGL 2 key codes (`key_key.forward:17`); mouse buttons are `-100 + button`
//!   (`key.attack:-100`), and 0 is unbound. Newer versions store key names (`key.keyboard.w`).
//! - Numbers are Java floats (`Float.toString`); `true` and `false` also read as 1.0 and 0.0. Field of view is
//!   stored as (degrees − 70) / 40 like the newer versions, but kept as a float.
//! - Smooth lighting (`ao`) is a level (0 off, 1 minimum, 2 maximum); clouds are `true`, `fast` or `false`
//!   without quotes; the language is `en_US` where newer versions write `en_us`.
//! - Option names Flint syncs are the same as in newer versions. Settings 1.8.9 doesn't have (auto-jump,
//!   the off-hand key, simulation distance, ...) and those newer versions don't have (fancy graphics,
//!   anaglyph, streaming) are never translated: they stay in each game's own file.
//!
//! Only the settings in `FLOATS`, `BOOLS`, `INTS`, the special cases and the vanilla key mappings in `KEYS`
//! are translated; every other line of a 1.8.9 file is kept exactly as it is.

use super::options_txt::{java_int, OptionsDoc, Val};
use super::registry::{self, Setting};
use super::tables::{FamilyData, Input, FAMILIES};

/// How Flint names the 1.8.9 "family" (it has no generated tables).
pub const FAMILY: &str = "1.8.9";

/// The SHA-1 of Mojang's 1.8.9 client jar, as its version manifest lists it.
const MOJANG_JAR_SHA1: &str = "3870888a6c3d349d3771a3e9d16c9bf5e076b908";

/// The test build's stand-in for Mojang's jar (see `known_jar`).
#[cfg(test)]
pub const TEST_JAR: &[u8] = b"Flint's test stand-in for the Minecraft 1.8.9 client jar";

/// Whether a jar with this SHA-1 is Mojang's 1.8.9 client (or, in tests, `TEST_JAR`).
pub fn known_jar(sha1: &str) -> bool {
    if sha1.eq_ignore_ascii_case(MOJANG_JAR_SHA1) {
        return true;
    }
    #[cfg(test)]
    {
        use sha1::{Digest, Sha1};
        if sha1 == hex::encode(Sha1::digest(TEST_JAR)) {
            return true;
        }
    }
    false
}

/// The family whose spelling translated values take: the newest one that reads key names with GLFW, as
/// LWJGL 2's keys are named in `KEYS` (every one of those names means the same key in SDL3 versions too).
pub fn reference_family() -> &'static FamilyData {
    FAMILIES.iter().find(|f| f.input == Input::Glfw).expect("a verified GLFW family")
}

/// Settings stored as a float, the same number in every version (sensitivity, brightness, chat sizes and
/// opacity, volumes).
const FLOATS: &[&str] = &[
    "mouseSensitivity",
    "gamma",
    "chatOpacity",
    "chatScale",
    "chatWidth",
    "chatHeightFocused",
    "chatHeightUnfocused",
    "soundCategory_master",
    "soundCategory_music",
    "soundCategory_record",
    "soundCategory_weather",
    "soundCategory_block",
    "soundCategory_hostile",
    "soundCategory_neutral",
    "soundCategory_player",
    "soundCategory_ambient",
];

/// Settings stored as `true` / `false`.
const BOOLS: &[&str] = &[
    "invertYMouse",
    "enableVsync",
    "entityShadows",
    "chatColors",
    "chatLinks",
    "chatLinksPrompt",
    "modelPart_cape",
    "modelPart_jacket",
    "modelPart_left_sleeve",
    "modelPart_right_sleeve",
    "modelPart_left_pants_leg",
    "modelPart_right_pants_leg",
    "modelPart_hat",
];

/// Settings stored as a whole number, with the values both kinds of game read the same way: render distance
/// in chunks, the frame limit (260 is unlimited in both), the GUI scale (1.8.9's button offers Auto and 1 to
/// 3; larger scales aren't carried into it), mipmaps, and the particle and chat visibility levels (the same
/// ids in both).
const INTS: &[(&str, i64, i64)] = &[
    ("renderDistance", 2, 32),
    ("maxFps", 10, 260),
    ("guiScale", 0, 3),
    ("mipmapLevels", 0, 4),
    ("particles", 0, 2),
    ("chatVisibility", 0, 2),
];

/// 1.8.9's vanilla key mappings that newer versions still have, by the same name (the streaming keys are
/// left out: nothing newer has them).
const MAPPINGS: &[&str] = &[
    "key.attack",
    "key.use",
    "key.forward",
    "key.left",
    "key.back",
    "key.right",
    "key.jump",
    "key.sneak",
    "key.sprint",
    "key.drop",
    "key.inventory",
    "key.chat",
    "key.playerlist",
    "key.pickItem",
    "key.command",
    "key.screenshot",
    "key.togglePerspective",
    "key.smoothCamera",
    "key.fullscreen",
    "key.spectatorOutlines",
    "key.hotbar.1",
    "key.hotbar.2",
    "key.hotbar.3",
    "key.hotbar.4",
    "key.hotbar.5",
    "key.hotbar.6",
    "key.hotbar.7",
    "key.hotbar.8",
    "key.hotbar.9",
];

/// LWJGL 2 key codes (`org.lwjgl.input.Keyboard.KEY_*`, and `-100 + button` for the mouse) with the key name
/// newer versions store for the same key. Keys only one side has (LWJGL 2's Japanese-layout keys, the
/// newer versions' media keys, ...) aren't listed and aren't translated.
const KEYS: &[(i64, &str)] = &[
    (0, "key.keyboard.unknown"),
    (1, "key.keyboard.escape"),
    (2, "key.keyboard.1"),
    (3, "key.keyboard.2"),
    (4, "key.keyboard.3"),
    (5, "key.keyboard.4"),
    (6, "key.keyboard.5"),
    (7, "key.keyboard.6"),
    (8, "key.keyboard.7"),
    (9, "key.keyboard.8"),
    (10, "key.keyboard.9"),
    (11, "key.keyboard.0"),
    (12, "key.keyboard.minus"),
    (13, "key.keyboard.equal"),
    (14, "key.keyboard.backspace"),
    (15, "key.keyboard.tab"),
    (16, "key.keyboard.q"),
    (17, "key.keyboard.w"),
    (18, "key.keyboard.e"),
    (19, "key.keyboard.r"),
    (20, "key.keyboard.t"),
    (21, "key.keyboard.y"),
    (22, "key.keyboard.u"),
    (23, "key.keyboard.i"),
    (24, "key.keyboard.o"),
    (25, "key.keyboard.p"),
    (26, "key.keyboard.left.bracket"),
    (27, "key.keyboard.right.bracket"),
    (28, "key.keyboard.enter"),
    (29, "key.keyboard.left.control"),
    (30, "key.keyboard.a"),
    (31, "key.keyboard.s"),
    (32, "key.keyboard.d"),
    (33, "key.keyboard.f"),
    (34, "key.keyboard.g"),
    (35, "key.keyboard.h"),
    (36, "key.keyboard.j"),
    (37, "key.keyboard.k"),
    (38, "key.keyboard.l"),
    (39, "key.keyboard.semicolon"),
    (40, "key.keyboard.apostrophe"),
    (41, "key.keyboard.grave.accent"),
    (42, "key.keyboard.left.shift"),
    (43, "key.keyboard.backslash"),
    (44, "key.keyboard.z"),
    (45, "key.keyboard.x"),
    (46, "key.keyboard.c"),
    (47, "key.keyboard.v"),
    (48, "key.keyboard.b"),
    (49, "key.keyboard.n"),
    (50, "key.keyboard.m"),
    (51, "key.keyboard.comma"),
    (52, "key.keyboard.period"),
    (53, "key.keyboard.slash"),
    (54, "key.keyboard.right.shift"),
    (55, "key.keyboard.keypad.multiply"),
    (56, "key.keyboard.left.alt"),
    (57, "key.keyboard.space"),
    (58, "key.keyboard.caps.lock"),
    (59, "key.keyboard.f1"),
    (60, "key.keyboard.f2"),
    (61, "key.keyboard.f3"),
    (62, "key.keyboard.f4"),
    (63, "key.keyboard.f5"),
    (64, "key.keyboard.f6"),
    (65, "key.keyboard.f7"),
    (66, "key.keyboard.f8"),
    (67, "key.keyboard.f9"),
    (68, "key.keyboard.f10"),
    (69, "key.keyboard.num.lock"),
    (70, "key.keyboard.scroll.lock"),
    (71, "key.keyboard.keypad.7"),
    (72, "key.keyboard.keypad.8"),
    (73, "key.keyboard.keypad.9"),
    (74, "key.keyboard.keypad.subtract"),
    (75, "key.keyboard.keypad.4"),
    (76, "key.keyboard.keypad.5"),
    (77, "key.keyboard.keypad.6"),
    (78, "key.keyboard.keypad.add"),
    (79, "key.keyboard.keypad.1"),
    (80, "key.keyboard.keypad.2"),
    (81, "key.keyboard.keypad.3"),
    (82, "key.keyboard.keypad.0"),
    (83, "key.keyboard.keypad.decimal"),
    (87, "key.keyboard.f11"),
    (88, "key.keyboard.f12"),
    (100, "key.keyboard.f13"),
    (101, "key.keyboard.f14"),
    (102, "key.keyboard.f15"),
    (103, "key.keyboard.f16"),
    (104, "key.keyboard.f17"),
    (105, "key.keyboard.f18"),
    (113, "key.keyboard.f19"),
    (141, "key.keyboard.keypad.equal"),
    (156, "key.keyboard.keypad.enter"),
    (157, "key.keyboard.right.control"),
    (181, "key.keyboard.keypad.divide"),
    (183, "key.keyboard.print.screen"),
    (184, "key.keyboard.right.alt"),
    (197, "key.keyboard.pause"),
    (199, "key.keyboard.home"),
    (200, "key.keyboard.up"),
    (201, "key.keyboard.page.up"),
    (203, "key.keyboard.left"),
    (205, "key.keyboard.right"),
    (207, "key.keyboard.end"),
    (208, "key.keyboard.down"),
    (209, "key.keyboard.page.down"),
    (210, "key.keyboard.insert"),
    (211, "key.keyboard.delete"),
    (219, "key.keyboard.left.win"),
    (220, "key.keyboard.right.win"),
    (221, "key.keyboard.menu"),
    (-100, "key.mouse.left"),
    (-99, "key.mouse.right"),
    (-98, "key.mouse.middle"),
    (-97, "key.mouse.4"),
    (-96, "key.mouse.5"),
    (-95, "key.mouse.6"),
    (-94, "key.mouse.7"),
    (-93, "key.mouse.8"),
];

const KEY_PREFIX: &str = registry::KEY_PREFIX;
const CLOUDS: &[&str] = &["true", "fast", "false"];

/// Every newer-version option key the translation covers.
pub fn keys() -> impl Iterator<Item = String> {
    FLOATS
        .iter()
        .chain(BOOLS)
        .map(|k| (*k).to_owned())
        .chain(INTS.iter().map(|(k, ..)| (*k).to_owned()))
        .chain(["fov", "ao", "renderClouds", "lang"].map(str::to_owned))
        .chain(MAPPINGS.iter().map(|m| format!("{KEY_PREFIX}{m}")))
}

/// Whether the translation covers this newer-version option key.
pub fn covers(key: &str) -> bool {
    keys().any(|k| k == key)
}

/// Java's `Float.toString` for the numbers options.txt holds: the shortest digits that read back as the same
/// float, with a decimal point, in `1.0E-4` form below 10^-3 or from 10^7.
fn java_float(n: f32) -> String {
    if n == 0.0 {
        return if n.is_sign_negative() { "-0.0" } else { "0.0" }.to_owned();
    }
    let abs = n.abs();
    if (1e-3..1e7).contains(&abs) {
        let text = format!("{n}");
        return if text.contains('.') { text } else { format!("{text}.0") };
    }
    let text = format!("{n:e}");
    let (mantissa, exponent) = text.split_once('e').unwrap_or((&text, "0"));
    let mantissa = if mantissa.contains('.') { mantissa.to_owned() } else { format!("{mantissa}.0") };
    format!("{mantissa}E{exponent}")
}

/// 1.8.9's `GameSettings.parseFloat`: `true` is 1, `false` 0, anything else a float (None when Java would
/// throw and skip the line).
fn read_float(raw: &str) -> Option<f32> {
    match raw {
        "true" => Some(1.0),
        "false" => Some(0.0),
        _ => raw.trim().parse::<f32>().ok().filter(|n| n.is_finite()),
    }
}

/// A float as the double newer versions hold: its shortest float spelling read as a double, so 1.8.9's
/// `0.3` (a float) is the newer versions' `0.3`, not 0.30000001192092896.
fn float_to_double(n: f32) -> f64 {
    format!("{n}").parse::<f64>().unwrap_or(n as f64)
}

fn read_bool(raw: &str) -> Option<bool> {
    match raw {
        "true" => Some(true),
        "false" => Some(false),
        _ => None,
    }
}

/// The value a 1.8.9 line means, in the newer versions' terms, for an option key `covers` lists.
fn legacy_value(key: &str, raw: &str) -> Option<Val> {
    if FLOATS.contains(&key) {
        return read_float(raw).map(|n| Val::Num(float_to_double(n)));
    }
    if BOOLS.contains(&key) {
        return read_bool(raw).map(Val::Bool);
    }
    if let Some((_, min, max)) = INTS.iter().find(|(k, ..)| *k == key) {
        return java_int(raw).filter(|n| (*min..=*max).contains(n)).map(Val::Int);
    }
    if let Some(mapping) = key.strip_prefix(KEY_PREFIX) {
        if !MAPPINGS.contains(&mapping) {
            return None;
        }
        let code = java_int(raw)?;
        return KEYS.iter().find(|(c, _)| *c == code).map(|(_, name)| Val::Text((*name).to_owned()));
    }
    match key {
        // Degrees as 1.8.9 computes them (in floats), to the nearest whole degree as its slider sets them.
        "fov" => read_float(raw).map(|n| Val::Int((n * 40.0 + 70.0).round() as i64)),
        "ao" => match raw {
            "true" => Some(Val::Bool(true)),
            "false" => Some(Val::Bool(false)),
            _ => java_int(raw).map(|level| Val::Bool(level != 0)),
        },
        "renderClouds" => CLOUDS.contains(&raw).then(|| Val::Text(raw.to_owned())),
        "lang" => {
            let lower = raw.to_ascii_lowercase();
            (raw.is_ascii() && !raw.is_empty()).then_some(Val::Text(lower))
        }
        _ => None,
    }
}

/// The settings a 1.8.9 file holds that newer versions share, as a newer-version options.txt (in the
/// reference family's spelling) holding only those keys. Lines 1.8.9 couldn't read are left out.
pub fn to_modern(legacy: &OptionsDoc) -> OptionsDoc {
    let catalogue = registry::catalogue(Some(reference_family()));
    let mut out = OptionsDoc::new_file(reference_family().versions[0].1);
    for key in keys() {
        let Some(raw) = legacy.get(&key) else { continue };
        let Some(value) = legacy_value(&key, raw) else { continue };
        let Some(setting) = setting(&catalogue, &key) else { continue };
        out.set(&key, &setting.encode(&value));
    }
    out
}

fn setting(catalogue: &[Setting], key: &str) -> Option<Setting> {
    catalogue.iter().find(|s| s.key == key).cloned()
}

/// The 1.8.9 text for a newer-version value of `key` (the same key name in 1.8.9), or None when 1.8.9 has no
/// such setting or no such value. `current` is the profile's 1.8.9 file: a setting whose current value
/// already means the same (smooth lighting at minimum is "on") keeps its text.
pub fn from_modern(key: &str, value: &Val, current: &OptionsDoc) -> Option<String> {
    let kept = |raw: &str| -> Option<String> {
        let now = current.get(key)?;
        (legacy_value(key, now).as_ref() == Some(&legacy_value(key, raw)?)).then(|| now.to_owned())
    };
    let raw = if FLOATS.contains(&key) {
        let Val::Num(n) = value else { return None };
        java_float(*n as f32)
    } else if BOOLS.contains(&key) {
        let Val::Bool(b) = value else { return None };
        b.to_string()
    } else if let Some((_, min, max)) = INTS.iter().find(|(k, ..)| *k == key) {
        let Val::Int(n) = value else { return None };
        if !(*min..=*max).contains(n) {
            return None;
        }
        n.to_string()
    } else if let Some(mapping) = key.strip_prefix(KEY_PREFIX) {
        let Val::Text(name) = value else { return None };
        if !MAPPINGS.contains(&mapping) {
            return None;
        }
        KEYS.iter().find(|(_, n)| n == name)?.0.to_string()
    } else {
        match (key, value) {
            ("fov", Val::Int(degrees)) if (30..=110).contains(degrees) => java_float((*degrees - 70) as f32 / 40.0),
            ("ao", Val::Bool(on)) => (if *on { "2" } else { "0" }).to_owned(),
            ("renderClouds", Val::Text(clouds)) if CLOUDS.contains(&clouds.as_str()) => clouds.clone(),
            ("lang", Val::Text(code)) => legacy_lang(code)?,
            _ => return None,
        }
    };
    Some(kept(&raw).unwrap_or(raw))
}

/// `en_us` as 1.8.9 names it: `en_US` (its language list is keyed by the region in capitals).
fn legacy_lang(code: &str) -> Option<String> {
    if code.is_empty() || !code.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_') {
        return None;
    }
    Some(match code.split_once('_') {
        Some((language, region)) => format!("{language}_{}", region.to_ascii_uppercase()),
        None => code.to_owned(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gamesettings::registry::{check_key, KeyCheck};

    /// A 1.8.9 options.txt as its `saveOptions` writes it (abridged, with its own order and CRLF endings).
    const FILE_189: &str = "invertYMouse:false\r\nmouseSensitivity:0.6338028\r\nfov:0.375\r\ngamma:1.0\r\nsaturation:0.0\r\nrenderDistance:8\r\nguiScale:2\r\nparticles:1\r\nbobView:true\r\nanaglyph3d:false\r\nmaxFps:260\r\nfboEnable:true\r\ndifficulty:2\r\nfancyGraphics:false\r\nao:1\r\nrenderClouds:fast\r\nresourcePacks:[\"Faithful.zip\"]\r\nincompatibleResourcePacks:[]\r\nlastServer:mc.example.net:25565\r\nlang:en_US\r\nchatVisibility:0\r\nchatColors:true\r\nchatLinks:true\r\nchatLinksPrompt:true\r\nchatOpacity:1.0\r\nenableVsync:false\r\nchatHeightFocused:1.0\r\nchatHeightUnfocused:0.44366196\r\nchatScale:1.0\r\nchatWidth:1.0\r\nmipmapLevels:4\r\nentityShadows:true\r\nkey_key.attack:-100\r\nkey_key.use:-99\r\nkey_key.forward:17\r\nkey_key.sprint:29\r\nkey_key.drop:16\r\nkey_key.streamStartStop:64\r\nkey_key.togglePerspective:63\r\nkey_key.hotbar.1:2\r\nkey_key.pickItem:-98\r\nkey_key.smoothCamera:0\r\nkey_key.chat:112\r\nsoundCategory_master:0.5\r\nsoundCategory_music:0.0\r\nmodelPart_cape:true\r\nmodelPart_hat:false\r\n";

    fn legacy() -> OptionsDoc {
        OptionsDoc::parse(FILE_189.as_bytes()).unwrap()
    }

    #[test]
    fn a_1_8_9_file_reads_as_the_newer_versions_would_write_it() {
        let modern = to_modern(&legacy());
        for (key, raw) in [
            ("invertYMouse", "false"),
            ("mouseSensitivity", "0.6338028"),
            ("fov", "0.375"),
            ("gamma", "1.0"),
            ("renderDistance", "8"),
            ("guiScale", "2"),
            ("particles", "1"),
            ("maxFps", "260"),
            ("ao", "true"),
            ("renderClouds", "\"fast\""),
            ("lang", "en_us"),
            ("chatVisibility", "0"),
            ("chatHeightUnfocused", "0.44366196"),
            ("enableVsync", "false"),
            ("mipmapLevels", "4"),
            ("key_key.attack", "key.mouse.left"),
            ("key_key.use", "key.mouse.right"),
            ("key_key.pickItem", "key.mouse.middle"),
            ("key_key.forward", "key.keyboard.w"),
            ("key_key.sprint", "key.keyboard.left.control"),
            ("key_key.drop", "key.keyboard.q"),
            ("key_key.togglePerspective", "key.keyboard.f5"),
            ("key_key.hotbar.1", "key.keyboard.1"),
            ("key_key.smoothCamera", "key.keyboard.unknown"),
            ("soundCategory_master", "0.5"),
            ("soundCategory_music", "0.0"),
            ("modelPart_cape", "true"),
            ("modelPart_hat", "false"),
        ] {
            assert_eq!(modern.get(key), Some(raw), "{key}");
        }
        // 1.8.9-only settings, machine-specific ones and keys it can't express aren't carried.
        for key in ["saturation", "bobView", "anaglyph3d", "fboEnable", "fancyGraphics", "difficulty", "resourcePacks", "lastServer", "key_key.streamStartStop"] {
            assert!(!modern.contains(key), "{key} isn't translated");
        }
        // LWJGL 2's Japanese-layout code 112 (KANA) has no newer counterpart.
        assert!(!modern.contains("key_key.chat"));
        assert_eq!(modern.data_version(), Some(reference_family().versions[0].1 as i64));
    }

    #[test]
    fn newer_values_are_written_the_way_1_8_9_writes_them() {
        let current = legacy();
        for (key, value, raw) in [
            ("fov", Val::Int(90), "0.5"),
            ("fov", Val::Int(85), "0.375"),
            ("fov", Val::Int(30), "-1.0"),
            ("maxFps", Val::Int(260), "260"),
            ("maxFps", Val::Int(120), "120"),
            ("renderDistance", Val::Int(16), "16"),
            ("guiScale", Val::Int(0), "0"),
            ("gamma", Val::Num(0.3), "0.3"),
            ("mouseSensitivity", Val::Num(0.123456789), "0.12345679"),
            ("soundCategory_master", Val::Num(1.0), "1.0"),
            ("lang", Val::Text("en_us".into()), "en_US"),
            ("lang", Val::Text("pt_br".into()), "pt_BR"),
            ("renderClouds", Val::Text("false".into()), "false"),
            ("ao", Val::Bool(false), "0"),
            ("chatColors", Val::Bool(false), "false"),
            ("key_key.jump", Val::Text("key.keyboard.space".into()), "57"),
            ("key_key.attack", Val::Text("key.mouse.left".into()), "-100"),
            ("key_key.use", Val::Text("key.mouse.5".into()), "-96"),
            ("key_key.sneak", Val::Text("key.keyboard.unknown".into()), "0"),
            ("key_key.inventory", Val::Text("key.keyboard.right.shift".into()), "54"),
        ] {
            assert_eq!(from_modern(key, &value, &current).as_deref(), Some(raw), "{key} {value:?}");
        }
        // Smooth lighting that is already on (at 1.8.9's minimum level) stays at its level.
        assert_eq!(from_modern("ao", &Val::Bool(true), &current).as_deref(), Some("1"));
        assert_eq!(from_modern("ao", &Val::Bool(true), &OptionsDoc::parse(b"ao:0\n").unwrap()).as_deref(), Some("2"));
    }

    #[test]
    fn what_1_8_9_can_t_hold_isn_t_written() {
        let current = legacy();
        for (key, value) in [
            ("autoJump", Val::Bool(true)),
            ("simulationDistance", Val::Int(8)),
            ("guiScale", Val::Int(4)),
            ("renderDistance", Val::Int(33)),
            ("key_key.swapOffhand", Val::Text("key.keyboard.f".into())),
            ("key_key.jump", Val::Text("key.keyboard.65".into())),
            ("key_key.jump", Val::Text("key.keyboard.world.1".into())),
            ("key_key.flintclient.control_center", Val::Text("key.keyboard.right.shift".into())),
            ("soundCategory_voice", Val::Num(0.5)),
            ("renderClouds", Val::Text("sometimes".into())),
            ("lang", Val::Text("EN-us".into())),
            ("fov", Val::Int(120)),
            ("fov", Val::Num(0.5)),
        ] {
            assert_eq!(from_modern(key, &value, &current), None, "{key} {value:?}");
        }
    }

    #[test]
    fn values_survive_the_round_trip() {
        // What Flint writes into 1.8.9 reads back as the same value, so it never comes back as a change.
        let current = legacy();
        let catalogue = registry::catalogue(Some(reference_family()));
        let mut doc = current.clone();
        for key in keys() {
            let Some(modern) = to_modern(&current).get(&key).map(str::to_owned) else { continue };
            let setting = setting(&catalogue, &key).unwrap();
            let value = setting.decode(&modern).unwrap();
            doc.set(&key, &from_modern(&key, &value, &current).unwrap());
        }
        assert_eq!(doc, current, "translating there and back changes nothing");
        for degrees in 30..=110 {
            let raw = from_modern("fov", &Val::Int(degrees), &current).unwrap();
            assert_eq!(legacy_value("fov", &raw), Some(Val::Int(degrees)));
        }
    }

    #[test]
    fn other_lines_and_the_file_s_form_are_kept() {
        let mut doc = legacy();
        let raw = from_modern("gamma", &Val::Num(0.25), &doc).unwrap();
        doc.set("gamma", &raw);
        let text = doc.to_string();
        assert_eq!(text, FILE_189.replace("gamma:1.0\r\n", "gamma:0.25\r\n"));
        assert!(!text.contains("version:"), "1.8.9 files never get a version line");
    }

    #[test]
    fn every_translated_key_name_means_the_same_key_in_every_newer_version() {
        for (_, name) in KEYS {
            for family in FAMILIES.iter() {
                assert_eq!(check_key(family, name), KeyCheck::Named, "{name} in {}", family.id);
            }
        }
        let mut codes: Vec<i64> = KEYS.iter().map(|(c, _)| *c).collect();
        codes.sort();
        codes.dedup();
        assert_eq!(codes.len(), KEYS.len(), "one name per code");
    }

    #[test]
    fn every_translated_setting_is_in_the_registry_for_every_newer_version() {
        for family in FAMILIES.iter() {
            let catalogue = registry::catalogue(Some(family));
            for key in keys() {
                let setting = setting(&catalogue, &key).unwrap_or_else(|| panic!("{key} isn't in the registry"));
                assert!(setting.available_in(family).is_ok(), "{key} in {}", family.id);
            }
        }
        for mapping in MAPPINGS {
            assert!(FAMILIES.iter().all(|f| f.key_mappings.iter().any(|(m, _)| m == mapping)), "{mapping}");
        }
    }

    #[test]
    fn only_mojang_s_jar_is_known() {
        assert!(known_jar(MOJANG_JAR_SHA1));
        assert!(known_jar(&MOJANG_JAR_SHA1.to_uppercase()));
        assert!(!known_jar("da39a3ee5e6b4b0d3255bfef95601890afd80709"));
    }
}
