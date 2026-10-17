//! The Minecraft settings Flint shows and copies, and how each is stored in options.txt.
//!
//! Every entry was checked against Minecraft 26.3's `Options` (key, range, default, how it is written) and
//! exists, constructed the same way, in the 26.1 and 26.2 bytecode: availability in a family comes from the
//! generated tables (`tables.rs`), never from this file, and a key whose definition differs in a family is
//! treated as missing there. Settings that are machine-specific or that Flint can't change safely
//! (`resourcePacks`, `soundDevice`, `fullscreenResolution`, `lastServer`, window overrides, tutorial and
//! onboarding flags, `version`, ...) are not listed: they are always preserved, never shown and never synced.

use super::options_txt::{java_int, Codec, Val};
use super::tables::{FamilyData, Input, FAMILIES, KEY_LABELS, MAPPING_LABELS};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value as Json};

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[serde(rename_all = "camelCase")]
pub enum Category {
    Controls,
    Keybinds,
    Video,
    Audio,
    Accessibility,
    Chat,
    Skin,
    Language,
}

/// What a sync can copy: Minecraft's settings by category, and Flint's own files.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[serde(rename_all = "camelCase")]
pub enum SyncCategory {
    Controls,
    Keybinds,
    Video,
    Audio,
    Accessibility,
    Chat,
    Skin,
    Language,
    HudLayouts,
    ControlCenter,
    ServerShortcuts,
}

impl SyncCategory {
    /// The Minecraft category, or None for Flint's own files.
    pub fn minecraft(self) -> Option<Category> {
        Some(match self {
            SyncCategory::Controls => Category::Controls,
            SyncCategory::Keybinds => Category::Keybinds,
            SyncCategory::Video => Category::Video,
            SyncCategory::Audio => Category::Audio,
            SyncCategory::Accessibility => Category::Accessibility,
            SyncCategory::Chat => Category::Chat,
            SyncCategory::Skin => Category::Skin,
            SyncCategory::Language => Category::Language,
            SyncCategory::HudLayouts | SyncCategory::ControlCenter | SyncCategory::ServerShortcuts => return None,
        })
    }
}

impl From<Category> for SyncCategory {
    fn from(category: Category) -> Self {
        match category {
            Category::Controls => SyncCategory::Controls,
            Category::Keybinds => SyncCategory::Keybinds,
            Category::Video => SyncCategory::Video,
            Category::Audio => SyncCategory::Audio,
            Category::Accessibility => SyncCategory::Accessibility,
            Category::Chat => SyncCategory::Chat,
            Category::Skin => SyncCategory::Skin,
            Category::Language => SyncCategory::Language,
        }
    }
}

/// When a change made inside the running game takes effect there. From the launcher every change takes
/// effect the next time the profile is played; this documents the in-game behaviour. `ResourceReload`
/// covers Minecraft reloading textures or sounds for it (mipmaps and texture filtering when the Video
/// screen closes, directional audio by restarting the sound engine).
#[derive(Serialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum InGame {
    Live,
    ChunkRebuild,
    ResourceReload,
    Restart,
}

/// The launcher's control for a setting (the command contract's `SettingControl`).
/// The launcher form of a setting (controls, shown values, typed input), from the earlier Game settings
/// view. Auto Sync shows no settings, so only the tests use it now; it stays because it records Minecraft's
/// verified slider grids and labels.
#[allow(dead_code)]
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum Control {
    Toggle,
    Range {
        min: f64,
        max: f64,
        step: f64,
        #[serde(skip_serializing_if = "Option::is_none")]
        unit: Option<&'static str>,
        #[serde(skip_serializing_if = "Vec::is_empty")]
        special: Vec<Special>,
    },
    Choice {
        options: Vec<ChoiceOption>,
    },
    Keybind,
    Text,
}

#[allow(dead_code)]
#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct Special {
    pub value: f64,
    pub label: &'static str,
}

#[allow(dead_code)]
#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct ChoiceOption {
    pub value: Json,
    pub label: &'static str,
}

/// How a number is shown, as Minecraft's own labels show it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Shown {
    /// Whole units, cut off like Minecraft's `(int)` (percentages).
    Whole,
    /// Whole units, rounded down like `Mth.floor` (pixels).
    Floor,
    /// A fixed number of decimals.
    Decimals(usize),
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct IntRange {
    /// What Minecraft accepts when it loads the file.
    pub min: i64,
    pub max: i64,
    /// The launcher's slider: up to `ui_max`, in `step`s (a value off the step isn't offered).
    pub ui_max: i64,
    pub step: i64,
    pub unit: Option<&'static str>,
    pub special: &'static [(i64, &'static str)],
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct NumRange {
    /// What Minecraft accepts when it loads the file.
    pub min: f64,
    pub max: f64,
    /// Shown as `stored × scale + offset`.
    pub scale: f64,
    pub offset: f64,
    /// The slider's step, in shown units.
    pub step: f64,
    pub unit: Option<&'static str>,
    pub special: &'static [(f64, &'static str)],
    pub shown: Shown,
}

/// How a setting's value is stored in options.txt and shown in the launcher.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Repr {
    /// A JSON boolean, shown with these words for true and false.
    Bool(&'static str, &'static str),
    /// A plain boolean field (`modelPart_*`): only the exact text `true` is true.
    PlainBool,
    Int(IntRange),
    /// An enum Minecraft stores by number; which numbers a version accepts comes from that version's enum
    /// (`enum_name` in the generated tables), the labels from here.
    Id { enum_name: &'static str, labels: &'static [(i64, &'static str)] },
    /// An enum Minecraft stores as a JSON string; the accepted values come from the version's enum.
    Str { enum_name: &'static str, labels: &'static [(&'static str, &'static str)] },
    /// Clouds: a JSON string ("false", "fast", "true"), or a JSON boolean Minecraft reads as off / fancy.
    Clouds,
    Num(NumRange),
    /// Field of view: stored as (degrees − 70) / 40 and read back as whole degrees (Java's `(int)`).
    Fov,
    /// The language code, stored as plain text.
    Lang,
    /// A key name.
    Key,
}

impl Repr {
    pub fn codec(&self) -> Codec {
        match self {
            Repr::Bool(..) => Codec::Bool,
            Repr::PlainBool => Codec::PlainBool,
            Repr::Int(_) | Repr::Id { .. } => Codec::Int,
            Repr::Str { .. } => Codec::JsonString,
            Repr::Clouds => Codec::JsonStringOrBool,
            Repr::Num(_) | Repr::Fov => Codec::Double,
            Repr::Lang => Codec::Raw,
            Repr::Key => Codec::KeyName,
        }
    }
}

/// Origin of a key binding.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Bind {
    /// One of Minecraft's own key mappings.
    Vanilla,
    /// One of Flint's in-game menu keys.
    Flint,
    /// A key mapping another mod added (`key_` lines Flint doesn't know).
    Mod,
}

/// A setting as the launcher handles it.
#[derive(Clone, Debug, PartialEq)]
pub struct Setting {
    pub id: String,
    pub key: String,
    pub category: Category,
    pub label: String,
    pub description: Option<&'static str>,
    pub repr: Repr,
    /// The text Minecraft starts with when the key is missing, when known.
    pub default: Option<String>,
    pub in_game: InGame,
    pub note: Option<&'static str>,
    /// Why the launcher shows this setting without changing it, if it doesn't change it.
    pub read_only: Option<&'static str>,
    pub bind: Option<Bind>,
}

struct OptionDef {
    id: &'static str,
    key: &'static str,
    category: Category,
    label: &'static str,
    description: &'static str,
    repr: Repr,
    default: &'static str,
    in_game: InGame,
    note: &'static str,
    read_only: &'static str,
}

const ON_OFF: Repr = Repr::Bool("On", "Off");

const fn percent(default_special: &'static [(f64, &'static str)]) -> Repr {
    Repr::Num(NumRange { min: 0.0, max: 1.0, scale: 100.0, offset: 0.0, step: 1.0, unit: Some("%"), special: default_special, shown: Shown::Whole })
}

const fn chat_pixels(scale: f64, offset: f64) -> Repr {
    Repr::Num(NumRange { min: 0.0, max: 1.0, scale, offset, step: 1.0, unit: Some("px"), special: &[], shown: Shown::Floor })
}

const fn ints(min: i64, max: i64, unit: Option<&'static str>, special: &'static [(i64, &'static str)]) -> Repr {
    Repr::Int(IntRange { min, max, ui_max: max, step: 1, unit, special })
}

/// An option: id, key, category, (label, description), how it's stored, Minecraft's default text and when
/// it applies in game.
const fn def(id: &'static str, key: &'static str, category: Category, text: (&'static str, &'static str), repr: Repr, default: &'static str, in_game: InGame) -> OptionDef {
    OptionDef { id, key, category, label: text.0, description: text.1, repr, default, in_game, note: "", read_only: "" }
}

const OFF: &[(f64, &str)] = &[(0.0, "Off")];

/// Options with their own keys. Labels and descriptions follow Minecraft's English texts.
static OPTIONS: &[OptionDef] = &[
    // Controls
    def("controls.mouseSensitivity", "mouseSensitivity", Category::Controls, ("Mouse sensitivity", "How far the camera turns as you move the mouse."),
        Repr::Num(NumRange { min: 0.0, max: 1.0, scale: 200.0, offset: 0.0, step: 1.0, unit: Some("%"), special: &[(0.0, "*yawn*"), (200.0, "HYPERSPEED!!!")], shown: Shown::Whole }),
        "0.5", InGame::Live),
    def("controls.invertYMouse", "invertYMouse", Category::Controls, ("Invert mouse Y", "Moving the mouse up looks down."), ON_OFF, "false", InGame::Live),
    def("controls.mouseWheelSensitivity", "mouseWheelSensitivity", Category::Controls, ("Scroll sensitivity", "How much each turn of the mouse wheel scrolls."),
        Repr::Num(NumRange { min: 0.01, max: 10.0, scale: 1.0, offset: 0.0, step: 0.01, unit: None, special: &[], shown: Shown::Decimals(2) }),
        "1.0", InGame::Live),
    def("controls.toggleCrouch", "toggleCrouch", Category::Controls, ("Sneak", "Toggle: press the sneak key once to start or stop sneaking. Hold: sneak while the key is held."),
        Repr::Bool("Toggle", "Hold"), "false", InGame::Live),
    def("controls.toggleSprint", "toggleSprint", Category::Controls, ("Sprint", "Toggle: press the sprint key once to start or stop sprinting. Hold: sprint while the key is held."),
        Repr::Bool("Toggle", "Hold"), "false", InGame::Live),
    def("controls.autoJump", "autoJump", Category::Controls, ("Auto-jump", "Jumps up single blocks automatically as you walk into them."), ON_OFF, "false", InGame::Live),
    def("controls.rawMouseInput", "rawMouseInput", Category::Controls, ("Raw input", "Reads mouse movement straight from the mouse, without the system's pointer acceleration."), ON_OFF, "true", InGame::Live),
    def("controls.sprintWindow", "sprintWindow", Category::Controls, ("Sprint window", "How many ticks you have to double-tap forward to start sprinting. Off turns double-tap sprinting off."),
        ints(0, 10, Some("ticks"), &[(0, "Off")]), "7", InGame::Live),
    // Video
    OptionDef {
        note: "Minecraft sets the quality settings to this preset's values each time it starts, unless it is Custom.",
        ..def("video.graphicsPreset", "graphicsPreset", Category::Video, ("Graphics preset", "Sets the quality and performance settings to Minecraft's values for the chosen quality."),
            Repr::Str { enum_name: "GraphicsPreset", labels: &[("fast", "Fast"), ("fancy", "Fancy"), ("fabulous", "Fabulous!"), ("custom", "Custom")] },
            "\"fancy\"", InGame::ChunkRebuild)
    },
    def("video.renderDistance", "renderDistance", Category::Video, ("Render distance", "How far away the world is drawn."), ints(2, 32, Some("chunks"), &[]), "12", InGame::Live),
    def("video.simulationDistance", "simulationDistance", Category::Video, ("Simulation distance", "How far away the world keeps running in single-player: mobs move, crops grow and redstone works."),
        ints(5, 32, Some("chunks"), &[]), "12", InGame::Live),
    def("video.maxFps", "maxFps", Category::Video, ("Max framerate", "The highest frame rate Minecraft draws at."),
        Repr::Int(IntRange { min: 10, max: 260, ui_max: 260, step: 10, unit: Some("fps"), special: &[(260, "Unlimited")] }), "120", InGame::Live),
    def("video.enableVsync", "enableVsync", Category::Video, ("VSync", "Matches the frame rate to the display, which avoids tearing."), ON_OFF, "true", InGame::Live),
    def("video.fov", "fov", Category::Video, ("Field of view", "How wide the camera sees."), Repr::Fov, "0.0", InGame::Live),
    OptionDef {
        note: "Minecraft lowers it if the window is too small for the chosen size.",
        ..def("video.guiScale", "guiScale", Category::Video, ("GUI scale", "The size of menus and the HUD."),
            Repr::Int(IntRange { min: 0, max: 2_147_483_646, ui_max: 8, step: 1, unit: None, special: &[(0, "Auto")] }), "0", InGame::Live)
    },
    def("video.gamma", "gamma", Category::Video, ("Brightness", "How bright dark places look."),
        Repr::Num(NumRange { min: 0.0, max: 1.0, scale: 100.0, offset: 0.0, step: 1.0, unit: Some("%"), special: &[(0.0, "Moody"), (50.0, "Default"), (100.0, "Bright")], shown: Shown::Whole }),
        "0.5", InGame::Live),
    def("video.renderClouds", "renderClouds", Category::Video, ("Clouds", "How clouds are drawn."), Repr::Clouds, "\"true\"", InGame::Live),
    def("video.cloudRange", "cloudRange", Category::Video, ("Cloud distance", "How far away clouds are drawn."), ints(2, 128, Some("chunks"), &[]), "128", InGame::ChunkRebuild),
    def("video.particles", "particles", Category::Video, ("Particles", "How many particles are shown."),
        Repr::Id { enum_name: "ParticleStatus", labels: &[(0, "All"), (1, "Decreased"), (2, "Minimal")] }, "0", InGame::Live),
    def("video.ao", "ao", Category::Video, ("Smooth lighting", "Softens light and shadow between blocks."), ON_OFF, "true", InGame::ChunkRebuild),
    def("video.entityShadows", "entityShadows", Category::Video, ("Entity shadows", "Draws shadows under mobs and other entities."), ON_OFF, "true", InGame::Live),
    def("video.biomeBlendRadius", "biomeBlendRadius", Category::Video, ("Biome blend", "How smoothly grass, foliage and water colors blend between biomes."),
        Repr::Int(IntRange { min: 0, max: 7, ui_max: 7, step: 1, unit: None, special: &[(0, "Off"), (1, "3×3"), (2, "5×5"), (3, "7×7"), (4, "9×9"), (5, "11×11"), (6, "13×13"), (7, "15×15")] }),
        "2", InGame::ChunkRebuild),
    def("video.entityDistanceScaling", "entityDistanceScaling", Category::Video, ("Entity distance", "How far away mobs and other entities are drawn."),
        Repr::Num(NumRange { min: 0.5, max: 5.0, scale: 100.0, offset: 0.0, step: 25.0, unit: Some("%"), special: &[], shown: Shown::Whole }),
        "1.0", InGame::Live),
    def("video.chunkSectionFadeInTime", "chunkSectionFadeInTime", Category::Video, ("Chunk fade time", "How long newly drawn chunks take to fade in."),
        Repr::Num(NumRange { min: 0.0, max: 2.0, scale: 1.0, offset: 0.0, step: 0.05, unit: Some("s"), special: OFF, shown: Shown::Decimals(2) }),
        "0.75", InGame::Live),
    def("video.mipmapLevels", "mipmapLevels", Category::Video, ("Mipmap levels", "Smooths textures seen from far away."), ints(0, 4, None, &[(0, "Off")]), "4", InGame::ResourceReload),
    def("video.textureFiltering", "textureFiltering", Category::Video, ("Texture filtering", "How textures are smoothed when seen at an angle."),
        Repr::Id { enum_name: "TextureFilteringMethod", labels: &[(0, "None"), (1, "RGSS"), (2, "Anisotropic")] }, "0", InGame::ResourceReload),
    OptionDef {
        note: "Used when Texture filtering is Anisotropic.",
        ..def("video.maxAnisotropyBit", "maxAnisotropyBit", Category::Video, ("Anisotropic filtering", "How sharp textures stay at an angle; higher levels use more video memory."),
            Repr::Int(IntRange { min: 1, max: 3, ui_max: 3, step: 1, unit: None, special: &[(1, "2×"), (2, "4×"), (3, "8×")] }), "2", InGame::ResourceReload)
    },
    def("video.prioritizeChunkUpdates", "prioritizeChunkUpdates", Category::Video, ("Chunk builder", "When changed chunks are rebuilt: in the background, or straight away for nearby or player-made changes."),
        Repr::Id { enum_name: "PrioritizeChunkUpdates", labels: &[(0, "Threaded"), (1, "Semi blocking"), (2, "Fully blocking")] }, "0", InGame::Live),
    def("video.cutoutLeaves", "cutoutLeaves", Category::Video, ("See-through leaves", "Lets you see through the gaps in leaves. Turning it off improves performance."), ON_OFF, "true", InGame::ChunkRebuild),
    def("video.improvedTransparency", "improvedTransparency", Category::Video, ("Improved transparency", "An experimental way of drawing layers of translucent blocks and effects. Costs performance."),
        ON_OFF, "false", InGame::ChunkRebuild),
    def("video.weatherRadius", "weatherRadius", Category::Video, ("Weather effect radius", "How far around you rain and snow are drawn."), ints(3, 10, Some("blocks"), &[]), "10", InGame::Live),
    OptionDef {
        note: "After a start that didn't finish cleanly, Minecraft switches Vulkan back to Default.",
        ..def("video.preferredGraphicsBackend", "preferredGraphicsBackend", Category::Video, ("Graphics API", "Which graphics API Minecraft draws with. Leave it on Default unless you need to change it."),
            Repr::Str { enum_name: "PreferredGraphicsApi", labels: &[("default", "Default"), ("opengl", "Prefer OpenGL"), ("vulkan", "Prefer Vulkan (experimental)")] },
            "\"default\"", InGame::Restart)
    },
    // Audio (the volumes come from SOUNDS below)
    def("audio.showSubtitles", "showSubtitles", Category::Audio, ("Closed captions", "Shows captions for sounds played in the game."), ON_OFF, "false", InGame::Live),
    def("audio.directionalAudio", "directionalAudio", Category::Audio, ("Directional audio", "3D sound based on HRTF. Needs suitable audio hardware and is best with headphones."), ON_OFF, "false", InGame::ResourceReload),
    // Accessibility
    def("accessibility.textBackgroundOpacity", "textBackgroundOpacity", Category::Accessibility, ("Text background opacity", "How dark the background behind chat and other text is."), percent(&[]), "0.5", InGame::Live),
    OptionDef {
        read_only: "Change this in Minecraft: it also turns the High Contrast resource pack on or off, which Flint doesn't manage.",
        ..def("accessibility.highContrast", "highContrast", Category::Accessibility, ("High contrast", "Enhances the contrast of interface elements."), ON_OFF, "false", InGame::ResourceReload)
    },
    def("accessibility.panoramaScrollSpeed", "panoramaScrollSpeed", Category::Accessibility, ("Panorama scroll speed", "How fast the title screen's background moves."), percent(&[]), "1.0", InGame::Live),
    def("accessibility.hideLightningFlashes", "hideLightningFlashes", Category::Accessibility, ("Hide sky flashes", "Stops lightning and similar effects from making the sky flash. The lightning itself still shows."),
        ON_OFF, "false", InGame::Live),
    def("accessibility.darkMojangStudiosBackground", "darkMojangStudiosBackground", Category::Accessibility, ("Monochrome logo", "Makes the Mojang Studios loading screen's background black."),
        ON_OFF, "false", InGame::Live),
    def("accessibility.screenEffectScale", "screenEffectScale", Category::Accessibility, ("Distortion effects", "Strength of the Nausea and Nether portal screen distortion."), percent(OFF), "1.0", InGame::Live),
    def("accessibility.fovEffectScale", "fovEffectScale", Category::Accessibility, ("FOV effects", "How much the field of view changes with gameplay effects such as sprinting."), percent(OFF), "1.0", InGame::Live),
    def("accessibility.damageTiltStrength", "damageTiltStrength", Category::Accessibility, ("Damage tilt", "How much the camera shakes when you're hurt."), percent(OFF), "1.0", InGame::Live),
    def("accessibility.glintSpeed", "glintSpeed", Category::Accessibility, ("Glint speed", "How fast the glint shimmers across enchanted items."), percent(OFF), "0.5", InGame::Live),
    def("accessibility.glintStrength", "glintStrength", Category::Accessibility, ("Glint strength", "How visible the glint on enchanted items is."), percent(OFF), "0.75", InGame::Live),
    def("accessibility.menuBackgroundBlurriness", "menuBackgroundBlurriness", Category::Accessibility, ("Menu background blur", "How blurry the world looks behind menus."),
        ints(0, 10, None, &[(0, "Off")]), "5", InGame::Live),
    def("accessibility.notificationDisplayTime", "notificationDisplayTime", Category::Accessibility, ("Notification time", "How long notifications stay on screen."),
        Repr::Num(NumRange { min: 0.5, max: 10.0, scale: 1.0, offset: 0.0, step: 0.1, unit: Some("×"), special: &[], shown: Shown::Decimals(1) }),
        "1.0", InGame::Live),
    def("accessibility.narrator", "narrator", Category::Accessibility, ("Narrator", "What the narrator reads aloud."),
        Repr::Id { enum_name: "NarratorStatus", labels: &[(0, "Off"), (1, "Narrates all"), (2, "Narrates chat"), (3, "Narrates system")] }, "0", InGame::Live),
    // Chat
    def("chat.chatVisibility", "chatVisibility", Category::Chat, ("Chat", "Which messages chat shows."),
        Repr::Id { enum_name: "ChatVisiblity", labels: &[(0, "Shown"), (1, "Commands only"), (2, "Hidden")] }, "0", InGame::Live),
    def("chat.chatColors", "chatColors", Category::Chat, ("Colors", "Shows colors in chat messages."), ON_OFF, "true", InGame::Live),
    def("chat.chatLinks", "chatLinks", Category::Chat, ("Web links", "Makes web addresses in chat clickable."), ON_OFF, "true", InGame::Live),
    def("chat.chatLinksPrompt", "chatLinksPrompt", Category::Chat, ("Prompt on links", "Asks before opening a link from chat."), ON_OFF, "true", InGame::Live),
    def("chat.chatOpacity", "chatOpacity", Category::Chat, ("Chat text opacity", "How opaque chat text is."),
        Repr::Num(NumRange { min: 0.0, max: 1.0, scale: 90.0, offset: 10.0, step: 1.0, unit: Some("%"), special: &[], shown: Shown::Whole }), "1.0", InGame::Live),
    def("chat.chatLineSpacing", "chatLineSpacing", Category::Chat, ("Line spacing", "Extra space between chat lines."), percent(&[]), "0.0", InGame::Live),
    def("chat.chatScale", "chatScale", Category::Chat, ("Chat text size", "The size of chat text."), percent(OFF), "1.0", InGame::Live),
    def("chat.chatWidth", "chatWidth", Category::Chat, ("Width", "How wide the chat box is."), chat_pixels(280.0, 40.0), "1.0", InGame::Live),
    def("chat.chatHeightFocused", "chatHeightFocused", Category::Chat, ("Focused height", "How tall chat is while it's open."), chat_pixels(160.0, 20.0), "1.0", InGame::Live),
    def("chat.chatHeightUnfocused", "chatHeightUnfocused", Category::Chat, ("Unfocused height", "How tall chat is while you play."), chat_pixels(160.0, 20.0), "0.4375", InGame::Live),
    def("chat.chatDelay", "chatDelay", Category::Chat, ("Chat delay", "Waits this long before showing each new message."),
        Repr::Num(NumRange { min: 0.0, max: 6.0, scale: 1.0, offset: 0.0, step: 0.1, unit: Some("s"), special: &[(0.0, "None")], shown: Shown::Decimals(1) }),
        "0.0", InGame::Live),
    // Skin & hand (the model parts come from PARTS below)
    def("skin.mainHand", "mainHand", Category::Skin, ("Main hand", "The hand you hold items in."),
        Repr::Str { enum_name: "HumanoidArm", labels: &[("left", "Left"), ("right", "Right")] }, "\"right\"", InGame::Live),
    // Language
    def("language.lang", "lang", Category::Language, ("Language", "The language code Minecraft uses, such as en_us."), Repr::Lang, "en_us", InGame::ResourceReload),
];

/// `soundCategory_<name>` volumes, in `SoundSource` order, with Minecraft's English names.
static SOUNDS: &[(&str, &str)] = &[
    ("master", "Master volume"),
    ("music", "Music"),
    ("record", "Jukebox/note blocks"),
    ("weather", "Weather"),
    ("block", "Blocks"),
    ("hostile", "Hostile mobs"),
    ("neutral", "Friendly mobs"),
    ("player", "Players"),
    ("ambient", "Ambient/environment"),
    ("voice", "Narrator/voice"),
    ("ui", "UI"),
];

/// `modelPart_<name>` switches, in `PlayerModelPart` order.
static PARTS: &[(&str, &str)] = &[
    ("cape", "Cape"),
    ("jacket", "Jacket"),
    ("left_sleeve", "Left sleeve"),
    ("right_sleeve", "Right sleeve"),
    ("left_pants_leg", "Left pant leg"),
    ("right_pants_leg", "Right pant leg"),
    ("hat", "Hat"),
];

/// Flint's own key mappings (game-runtime ControlKeys.java), with their defaults in 26.3.
pub static FLINT_BINDS: &[(&str, &str, &str)] = &[
    ("key.flintclient.control_center", "Open Flint Control Center", "key.keyboard.right.shift"),
    ("key.flintclient.hud_editor", "Open HUD editor", "key.keyboard.unknown"),
];

pub const KEY_PREFIX: &str = "key_";
pub const UNBOUND: &str = "key.keyboard.unknown";

impl Setting {
    fn option(def: &OptionDef) -> Setting {
        Setting {
            id: def.id.to_owned(),
            key: def.key.to_owned(),
            category: def.category,
            label: def.label.to_owned(),
            description: Some(def.description).filter(|d| !d.is_empty()),
            repr: def.repr,
            default: Some(def.default.to_owned()),
            in_game: def.in_game,
            note: Some(def.note).filter(|n| !n.is_empty()),
            read_only: Some(def.read_only).filter(|r| !r.is_empty()),
            bind: None,
        }
    }

    /// A key binding: Minecraft's, Flint's or a mod's, by its mapping name.
    pub fn bind(mapping: &str, bind: Bind, default: Option<String>) -> Setting {
        let label = match bind {
            Bind::Vanilla => lookup(MAPPING_LABELS, mapping).unwrap_or(mapping).to_owned(),
            Bind::Flint => FLINT_BINDS.iter().find(|(m, ..)| *m == mapping).map(|(_, l, _)| *l).unwrap_or(mapping).to_owned(),
            Bind::Mod => mapping.to_owned(),
        };
        Setting {
            id: bind_id(mapping),
            key: format!("{KEY_PREFIX}{mapping}"),
            category: Category::Keybinds,
            label,
            description: match bind {
                Bind::Vanilla => None,
                Bind::Flint => Some("A Flint in-game menu key."),
                Bind::Mod => Some("Added by a mod in this profile."),
            },
            repr: Repr::Key,
            default,
            in_game: InGame::Live,
            note: None,
            read_only: None,
            bind: Some(bind),
        }
    }

    /// The JSON control for the launcher (see `Control`).
    #[allow(dead_code)]
    pub fn control(&self) -> Control {
        match self.repr {
            Repr::Bool(..) | Repr::PlainBool => Control::Toggle,
            Repr::Int(r) if r.max - r.min <= 7 && r.special.len() as i64 == r.max - r.min + 1 => Control::Choice {
                options: r.special.iter().map(|(v, l)| ChoiceOption { value: json!(v), label: l }).collect(),
            },
            Repr::Int(r) => Control::Range {
                min: r.min as f64,
                max: r.ui_max as f64,
                step: r.step as f64,
                unit: r.unit,
                special: r.special.iter().map(|(v, l)| Special { value: *v as f64, label: l }).collect(),
            },
            Repr::Id { labels, .. } => Control::Choice { options: labels.iter().map(|(v, l)| ChoiceOption { value: json!(v), label: l }).collect() },
            Repr::Str { labels, .. } => Control::Choice { options: labels.iter().map(|(v, l)| ChoiceOption { value: json!(v), label: l }).collect() },
            Repr::Clouds => Control::Choice { options: CLOUDS.iter().map(|(v, l)| ChoiceOption { value: json!(v), label: l }).collect() },
            Repr::Num(r) => Control::Range {
                min: ui_number(r.min * r.scale + r.offset),
                max: ui_number(r.max * r.scale + r.offset),
                step: r.step,
                unit: r.unit,
                special: r.special.iter().map(|(v, l)| Special { value: *v, label: l }).collect(),
            },
            Repr::Fov => Control::Range { min: 30.0, max: 110.0, step: 1.0, unit: Some("°"), special: FOV_SPECIAL.iter().map(|(v, l)| Special { value: *v as f64, label: l }).collect() },
            Repr::Lang => Control::Text,
            Repr::Key => Control::Keybind,
        }
    }

    /// Reads the stored text, or None when Flint can't be sure what Minecraft makes of it.
    pub fn decode(&self, raw: &str) -> Option<Val> {
        let value = self.repr.codec().decode(raw)?;
        match (&self.repr, &value) {
            (Repr::Fov, Val::Num(n)) => Some(Val::Int(fov_degrees(*n))),
            _ => Some(value),
        }
    }

    /// The text Minecraft writes for `value`.
    pub fn encode(&self, value: &Val) -> String {
        match (&self.repr, value) {
            (Repr::Fov, Val::Int(degrees)) => self.repr.codec().encode(&Val::Num((*degrees as f64 - 70.0) / 40.0)),
            _ => self.repr.codec().encode(value),
        }
    }

    /// Whether `family`'s Minecraft keeps `value` as it is when it loads the file (in range, a value its enum
    /// has). Key names are checked separately, with `check_key`.
    pub fn accepts(&self, family: &FamilyData, value: &Val) -> bool {
        match (&self.repr, value) {
            (Repr::Bool(..) | Repr::PlainBool, Val::Bool(_)) => true,
            (Repr::Int(r), Val::Int(n)) => (r.min..=r.max).contains(n),
            (Repr::Id { enum_name, .. }, Val::Int(n)) => enum_values(family, enum_name).iter().any(|v| *v == n.to_string()),
            (Repr::Str { enum_name, .. }, Val::Text(s)) => enum_values(family, enum_name).iter().any(|v| v == s),
            (Repr::Clouds, Val::Text(s)) => enum_values(family, "CloudStatus").iter().any(|v| v == s),
            (Repr::Num(r), Val::Num(n)) => *n >= r.min && *n <= r.max,
            (Repr::Fov, Val::Int(d)) => (30..=110).contains(d),
            (Repr::Lang, Val::Text(s)) => valid_lang(s),
            (Repr::Key, Val::Text(_)) => true,
            _ => false,
        }
    }

    /// The value as the launcher's control shows it (degrees, percent, ...). See `Control`.
    #[allow(dead_code)]
    pub fn to_ui(&self, value: &Val) -> Json {
        match (&self.repr, value) {
            (_, Val::Bool(b)) => json!(b),
            (_, Val::Int(n)) => json!(n),
            (Repr::Num(r), Val::Num(n)) => json!(ui_number(n * r.scale + r.offset)),
            (_, Val::Num(n)) => json!(n),
            (_, Val::Text(s)) => json!(s),
        }
    }

    /// A value from the launcher's control, checked against what the control offers. See `Control`.
    #[allow(dead_code)]
    pub fn parse_ui(&self, value: &Json) -> Result<Val, String> {
        let wrong = || format!("{} can't be set to {value}.", self.label);
        let whole = |v: &Json| v.as_i64().or_else(|| v.as_f64().filter(|f| f.fract() == 0.0 && f.abs() < 1e15).map(|f| f as i64));
        match self.repr {
            Repr::Bool(..) | Repr::PlainBool => value.as_bool().map(Val::Bool).ok_or_else(wrong),
            Repr::Int(r) => {
                let n = whole(value).ok_or_else(wrong)?;
                if n < r.min || n > r.ui_max {
                    return Err(format!("Choose a value from {} to {} for {}.", r.min, r.ui_max, self.label));
                }
                if (n - r.min) % r.step != 0 {
                    return Err(format!("{} goes in steps of {}.", self.label, r.step));
                }
                Ok(Val::Int(n))
            }
            Repr::Id { labels, .. } => whole(value).filter(|n| labels.iter().any(|(v, _)| v == n)).map(Val::Int).ok_or_else(wrong),
            Repr::Str { labels, .. } => value.as_str().filter(|s| labels.iter().any(|(v, _)| v == s)).map(|s| Val::Text(s.to_owned())).ok_or_else(wrong),
            Repr::Clouds => value.as_str().filter(|s| CLOUDS.iter().any(|(v, _)| v == s)).map(|s| Val::Text(s.to_owned())).ok_or_else(wrong),
            Repr::Num(r) => {
                let shown = value.as_f64().filter(|n| n.is_finite()).ok_or_else(wrong)?;
                let (lo, hi) = (ui_number(r.min * r.scale + r.offset), ui_number(r.max * r.scale + r.offset));
                if shown < lo - 1e-9 || shown > hi + 1e-9 {
                    return Err(format!("Choose a value from {} to {} for {}.", trim(lo), trim(hi), self.label));
                }
                // The slider's steps, which for Minecraft's stepped sliders (entity distance, chat delay, ...) are
                // the values it keeps: it would cut 110% down to 100% on load. Stored as Minecraft computes a
                // step (`step / steps_per_unit`), so the text is what it would write itself.
                let steps_per_unit = r.scale / r.step;
                let steps = (shown - r.offset) / r.scale * steps_per_unit;
                if (steps - steps.round()).abs() > 1e-6 {
                    return Err(format!("{} goes in steps of {}.", self.label, with_unit(&trim(r.step), r.unit)));
                }
                Ok(Val::Num((steps.round() / steps_per_unit).clamp(r.min, r.max)))
            }
            Repr::Fov => {
                let degrees = whole(value).ok_or_else(wrong)?;
                if !(30..=110).contains(&degrees) {
                    return Err("Choose a field of view from 30° to 110°.".into());
                }
                Ok(Val::Int(degrees))
            }
            Repr::Lang => value.as_str().filter(|s| valid_lang(s)).map(|s| Val::Text(s.to_owned())).ok_or_else(|| "Enter a language code such as en_us.".into()),
            Repr::Key => value.as_str().map(|s| Val::Text(s.to_owned())).ok_or_else(wrong),
        }
    }

    /// How the value reads in the launcher ("90°", "Unlimited", "Right Shift").
    pub fn display(&self, value: &Val) -> String {
        match (&self.repr, value) {
            (Repr::Bool(on, off), Val::Bool(b)) => (if *b { on } else { off }).to_string(),
            (Repr::PlainBool, Val::Bool(b)) => (if *b { "On" } else { "Off" }).to_owned(),
            (Repr::Int(r), Val::Int(n)) => match r.special.iter().find(|(v, _)| v == n) {
                Some((_, label)) => (*label).to_owned(),
                None => with_unit(&n.to_string(), r.unit),
            },
            (Repr::Id { labels, .. }, Val::Int(n)) => labels.iter().find(|(v, _)| v == n).map(|(_, l)| (*l).to_owned()).unwrap_or_else(|| n.to_string()),
            (Repr::Str { labels, .. }, Val::Text(s)) => labels.iter().find(|(v, _)| v == s).map(|(_, l)| (*l).to_owned()).unwrap_or_else(|| s.clone()),
            (Repr::Clouds, Val::Text(s)) => CLOUDS.iter().find(|(v, _)| v == s).map(|(_, l)| (*l).to_owned()).unwrap_or_else(|| s.clone()),
            (Repr::Num(r), Val::Num(n)) => {
                let shown = n * r.scale + r.offset;
                if let Some((_, label)) = r.special.iter().find(|(v, _)| (v - ui_number(shown)).abs() < 1e-9) {
                    return (*label).to_owned();
                }
                let text = match r.shown {
                    Shown::Whole => format!("{}", (shown + 1e-9).trunc() as i64),
                    Shown::Floor => format!("{}", (shown + 1e-9).floor() as i64),
                    Shown::Decimals(d) => format!("{shown:.d$}"),
                };
                with_unit(&text, r.unit)
            }
            (Repr::Fov, Val::Int(d)) => FOV_SPECIAL.iter().find(|(v, _)| v == d).map(|(_, l)| (*l).to_owned()).unwrap_or_else(|| format!("{d}°")),
            (Repr::Key, Val::Text(name)) => key_label(name),
            (_, Val::Text(s)) => s.clone(),
            (_, other) => format!("{other:?}"),
        }
    }

    /// Whether this setting exists, defined the same way, in `family`'s Minecraft; Err gives the reason.
    pub fn available_in(&self, family: &FamilyData) -> Result<(), String> {
        let missing = || Err(format!("Not in Minecraft {}", family.id));
        if let Some(bind) = self.bind {
            let mapping = &self.key[KEY_PREFIX.len()..];
            return match bind {
                Bind::Vanilla if !family.key_mappings.iter().any(|(m, _)| *m == mapping) => missing(),
                _ => Ok(()),
            };
        }
        if let Some(source) = self.key.strip_prefix("soundCategory_") {
            return if enum_values(family, "SoundSource").contains(&source) { Ok(()) } else { missing() };
        }
        if let Some(part) = self.key.strip_prefix("modelPart_") {
            return if enum_values(family, "PlayerModelPart").contains(&part) { Ok(()) } else { missing() };
        }
        if !family.option_keys.contains(&self.key.as_str()) {
            return missing();
        }
        if family.changed_definitions.contains(&self.key.as_str()) {
            return Err(format!("Works differently in Minecraft {}, which Flint hasn't checked yet", family.id));
        }
        Ok(())
    }
}

const CLOUDS: &[(&str, &str)] = &[("false", "Off"), ("fast", "Fast"), ("true", "Fancy")];
const FOV_SPECIAL: &[(i64, &str)] = &[(70, "Normal"), (110, "Quake Pro")];

/// Java's `(int)(value * 40.0 + 70.0)`: truncation toward zero, saturating.
pub fn fov_degrees(normalized: f64) -> i64 {
    (normalized * 40.0 + 70.0) as i32 as i64
}

/// A shown number without floating-point noise (0.7 × 100 = 70.00000000000001).
fn ui_number(n: f64) -> f64 {
    (n * 1e6).round() / 1e6
}

#[allow(dead_code)]
fn trim(n: f64) -> String {
    let text = format!("{n}");
    text.strip_suffix(".0").map(str::to_owned).unwrap_or(text)
}

fn with_unit(number: &str, unit: Option<&str>) -> String {
    match unit {
        None => number.to_owned(),
        Some(u @ ("%" | "×" | "°")) => format!("{number}{u}"),
        Some(u) => format!("{number} {u}"),
    }
}

/// Minecraft's language codes: lower-case letters, digits and underscores (en_us, zh_cn, lol_us).
fn valid_lang(code: &str) -> bool {
    (2..=16).contains(&code.len()) && code.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
}

fn lookup<'a>(table: &'a [(&'a str, &'a str)], key: &str) -> Option<&'a str> {
    table.binary_search_by(|(k, _)| (*k).cmp(key)).ok().map(|i| table[i].1)
}

fn enum_values(family: &FamilyData, name: &str) -> &'static [&'static str] {
    family.enums.iter().find(|(n, _)| *n == name).map(|(_, v)| *v).unwrap_or(&[])
}

pub fn bind_id(mapping: &str) -> String {
    format!("keybind.{mapping}")
}

/// A key name as people read it ("Left Shift", "W", "Button 4").
pub fn key_label(name: &str) -> String {
    if let Some(label) = lookup(KEY_LABELS, name) {
        return label.to_owned();
    }
    if let Some(rest) = name.strip_prefix("key.keyboard.") {
        if rest.chars().count() == 1 {
            return rest.to_uppercase();
        }
        if java_int(rest).is_some() {
            return format!("Key {rest}");
        }
    }
    if let Some(rest) = name.strip_prefix("key.mouse.") {
        return format!("Button {rest}");
    }
    if let Some(rest) = name.strip_prefix("scancode.") {
        return format!("Scancode {rest}");
    }
    name.to_owned()
}

/// What `InputConstants.getKey` makes of a key name in a family.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyCheck {
    /// A key the family knows by this name.
    Named,
    /// A numeric fallback (`key.keyboard.65`): valid, but the number means a different key with GLFW than
    /// with SDL3.
    Numeric,
    /// Neither: 26.3 unbinds the key, and 26.1 / 26.2 stop reading the rest of options.txt.
    Invalid,
}

pub fn check_key(family: &FamilyData, name: &str) -> KeyCheck {
    if family.key_names.binary_search(&name).is_ok() {
        return KeyCheck::Named;
    }
    // getKey skips one character after the prefix and parses the rest as an int.
    for prefix in family.key_prefixes {
        if let Some(rest) = name.strip_prefix(prefix) {
            let mut chars = rest.chars();
            return match chars.next() {
                Some(_) if java_int(chars.as_str()).is_some() => KeyCheck::Numeric,
                _ => KeyCheck::Invalid,
            };
        }
    }
    KeyCheck::Invalid
}

/// Whether two families read numeric key names the same way.
pub fn same_input(a: &FamilyData, b: &FamilyData) -> bool {
    a.input == b.input
}

pub fn input_name(input: Input) -> &'static str {
    match input {
        Input::Glfw => "GLFW",
        Input::Sdl3 => "SDL3",
    }
}

/// Every setting Flint knows, as it applies to `family` (or to the newest verified family when the
/// profile's own isn't verified). Key bindings of mods aren't included; they come from the file.
pub fn catalogue(family: Option<&'static FamilyData>) -> Vec<Setting> {
    let family = family.unwrap_or(&FAMILIES[0]);
    let mut out: Vec<Setting> = OPTIONS.iter().map(Setting::option).collect();
    let after_video = out.iter().rposition(|s| s.category == Category::Video).map(|i| i + 1).unwrap_or(out.len());
    let sounds = SOUNDS.iter().map(|(name, label)| Setting {
        id: format!("audio.{name}"),
        key: format!("soundCategory_{name}"),
        category: Category::Audio,
        label: (*label).to_owned(),
        description: None,
        repr: percent(OFF),
        default: Some("1.0".into()),
        in_game: InGame::Live,
        note: None,
        read_only: None,
        bind: None,
    });
    out.splice(after_video..after_video, sounds);
    let before_hand = out.iter().position(|s| s.key == "mainHand").unwrap_or(out.len());
    let parts = PARTS.iter().map(|(name, label)| Setting {
        id: format!("skin.{name}"),
        key: format!("modelPart_{name}"),
        category: Category::Skin,
        label: (*label).to_owned(),
        description: None,
        repr: Repr::PlainBool,
        default: Some("true".into()),
        in_game: InGame::Live,
        note: None,
        read_only: None,
        bind: None,
    });
    out.splice(before_hand..before_hand, parts);
    // Key bindings: this family's, then any other verified family's that this one lacks.
    let mut mappings: Vec<(&str, &str)> = family.key_mappings.to_vec();
    for other in FAMILIES.iter() {
        for (mapping, default) in other.key_mappings {
            if !mappings.iter().any(|(m, _)| m == mapping) {
                mappings.push((mapping, default));
            }
        }
    }
    out.extend(mappings.into_iter().map(|(mapping, default)| Setting::bind(mapping, Bind::Vanilla, Some(default.to_owned()))));
    out.extend(FLINT_BINDS.iter().map(|(mapping, _, default)| Setting::bind(mapping, Bind::Flint, Some((*default).to_owned()))));
    out
}

/// The settings in a sync category.
pub fn in_category(settings: &[Setting], category: Category) -> impl Iterator<Item = &Setting> {
    settings.iter().filter(move |s| s.category == category)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn family(id: &str) -> &'static FamilyData {
        FAMILIES.iter().find(|f| f.id == id).unwrap()
    }

    fn setting(id: &str) -> Setting {
        catalogue(Some(family("26.3"))).into_iter().find(|s| s.id == id).unwrap()
    }

    #[test]
    fn every_option_exists_in_the_newest_family_or_is_documented_as_older_only() {
        let newest = family("26.3");
        for s in catalogue(Some(newest)) {
            if s.bind.is_some() && s.bind != Some(Bind::Vanilla) {
                continue;
            }
            let somewhere = FAMILIES.iter().any(|f| s.available_in(f).is_ok());
            assert!(somewhere, "{} isn't in any verified family", s.key);
            if s.available_in(newest).is_err() {
                assert_eq!(s.key, "rawMouseInput", "only raw input is missing from 26.3");
            }
        }
    }

    #[test]
    fn availability_follows_each_family_s_own_keys() {
        let raw = setting("controls.rawMouseInput");
        assert!(raw.available_in(family("26.1")).is_ok());
        assert!(raw.available_in(family("26.2")).is_ok());
        assert_eq!(raw.available_in(family("26.3")).unwrap_err(), "Not in Minecraft 26.3");
        let api = setting("video.preferredGraphicsBackend");
        assert!(api.available_in(family("26.1")).is_err());
        assert!(api.available_in(family("26.2")).is_ok());
        let friends = setting("keybind.key.friends");
        assert!(friends.available_in(family("26.1")).is_err());
        assert!(friends.available_in(family("26.2")).is_ok());
        let transparency_key = setting("keybind.key.debug.improvedTransparency");
        assert!(transparency_key.available_in(family("26.2")).is_err());
        assert!(transparency_key.available_in(family("26.3")).is_ok());
        assert!(setting("audio.master").available_in(family("26.1")).is_ok());
        assert!(setting("skin.hat").available_in(family("26.1")).is_ok());
    }

    #[test]
    fn choice_values_are_exactly_what_each_family_s_enum_has() {
        for f in FAMILIES.iter() {
            for s in catalogue(Some(f)).iter().filter(|s| s.available_in(f).is_ok()) {
                let (name, values): (&str, Vec<String>) = match s.repr {
                    Repr::Id { enum_name, labels } => (enum_name, labels.iter().map(|(v, _)| v.to_string()).collect()),
                    Repr::Str { enum_name, labels } => (enum_name, labels.iter().map(|(v, _)| v.to_string()).collect()),
                    Repr::Clouds => ("CloudStatus", CLOUDS.iter().map(|(v, _)| v.to_string()).collect()),
                    _ => continue,
                };
                let real: Vec<String> = enum_values(f, name).iter().map(|v| v.to_string()).collect();
                assert_eq!(values, real, "{} in {}", s.key, f.id);
            }
            let sounds: Vec<&str> = SOUNDS.iter().map(|(n, _)| *n).collect();
            assert_eq!(sounds, enum_values(f, "SoundSource"), "sound categories in {}", f.id);
            let parts: Vec<&str> = PARTS.iter().map(|(n, _)| *n).collect();
            assert_eq!(parts, enum_values(f, "PlayerModelPart"), "model parts in {}", f.id);
        }
    }

    #[test]
    fn every_preset_managed_option_is_in_the_registry() {
        let keys: Vec<String> = catalogue(None).into_iter().map(|s| s.key).collect();
        for f in FAMILIES.iter() {
            assert_eq!(f.preset_managed.len(), 17, "GraphicsPreset.apply sets 17 options in {}", f.id);
            assert_eq!(f.preset_managed, FAMILIES[0].preset_managed, "the same options in {} as in {}", f.id, FAMILIES[0].id);
            for key in f.preset_managed {
                assert!(keys.iter().any(|k| k == key), "{key} is preset-managed in {} but not in the registry", f.id);
            }
        }
    }

    #[test]
    fn excluded_machine_specific_keys_are_never_listed() {
        let keys: Vec<String> = catalogue(None).into_iter().map(|s| s.key).collect();
        for excluded in ["resourcePacks", "incompatibleResourcePacks", "soundDevice", "fullscreenResolution", "lastServer", "overrideWidth", "overrideHeight", "tutorialStep",
            "joinedFirstServer", "onboardAccessibility", "skipMultiplayerWarning", "startedCleanly", "version", "glDebugVerbosity", "fullscreen"]
        {
            assert!(!keys.iter().any(|k| k == excluded), "{excluded} must not be managed");
        }
    }

    #[test]
    fn no_listed_option_is_defined_differently_in_any_verified_family() {
        // The generator compares how each option is constructed (range, default, codec) in every family; a
        // listed option that changed would need its own per-family handling before it could be synced.
        for f in FAMILIES.iter() {
            for s in catalogue(Some(f)) {
                assert!(!f.changed_definitions.contains(&s.key.as_str()), "{} is defined differently in {}", s.key, f.id);
            }
        }
    }

    #[test]
    fn ids_are_unique() {
        let all = catalogue(None);
        let mut ids: Vec<&str> = all.iter().map(|s| s.id.as_str()).collect();
        ids.sort();
        ids.dedup();
        assert_eq!(ids.len(), all.len());
    }

    #[test]
    fn field_of_view_is_stored_normalised_and_shown_in_degrees() {
        let fov = setting("video.fov");
        assert_eq!(fov.decode("0.0"), Some(Val::Int(70)));
        assert_eq!(fov.decode("1.0"), Some(Val::Int(110)));
        assert_eq!(fov.decode("-1.0"), Some(Val::Int(30)));
        assert_eq!(fov.decode("0.5"), Some(Val::Int(90)));
        // Java's (int) truncates toward zero.
        assert_eq!(fov.decode("0.4999"), Some(Val::Int(89)));
        assert_eq!(fov.encode(&Val::Int(90)), "0.5");
        assert_eq!(fov.encode(&Val::Int(70)), "0.0");
        assert_eq!(fov.encode(&Val::Int(30)), "-1.0");
        assert_eq!(fov.display(&Val::Int(70)), "Normal");
        assert_eq!(fov.display(&Val::Int(110)), "Quake Pro");
        assert_eq!(fov.display(&Val::Int(95)), "95°");
        assert_eq!(fov.parse_ui(&json!(95)), Ok(Val::Int(95)));
        assert!(fov.parse_ui(&json!(120)).is_err());
        assert!(fov.parse_ui(&json!(29)).is_err());
        assert!(!fov.accepts(family("26.3"), &Val::Int(111)));
        for degrees in 30..=110 {
            assert_eq!(fov.decode(&fov.encode(&Val::Int(degrees))), Some(Val::Int(degrees)), "{degrees}° survives a round trip");
        }
    }

    #[test]
    fn mouse_sensitivity_is_shown_times_two_hundred_percent() {
        let s = setting("controls.mouseSensitivity");
        let half = s.decode("0.5").unwrap();
        assert_eq!(s.to_ui(&half), json!(100.0));
        assert_eq!(s.display(&half), "100%");
        assert_eq!(s.display(&Val::Num(0.0)), "*yawn*");
        assert_eq!(s.display(&Val::Num(1.0)), "HYPERSPEED!!!");
        assert_eq!(s.parse_ui(&json!(150)), Ok(Val::Num(0.75)));
        assert_eq!(s.encode(&Val::Num(0.75)), "0.75");
        assert!(s.parse_ui(&json!(201)).is_err());
        assert!(s.parse_ui(&json!(-1)).is_err());
        assert_eq!(s.control(), Control::Range { min: 0.0, max: 200.0, step: 1.0, unit: Some("%"), special: vec![Special { value: 0.0, label: "*yawn*" }, Special { value: 200.0, label: "HYPERSPEED!!!" }] });
    }

    #[test]
    fn max_framerate_260_is_unlimited_and_goes_in_tens() {
        let s = setting("video.maxFps");
        assert_eq!(s.display(&Val::Int(260)), "Unlimited");
        assert_eq!(s.display(&Val::Int(120)), "120 fps");
        assert_eq!(s.parse_ui(&json!(260)), Ok(Val::Int(260)));
        assert!(s.parse_ui(&json!(125)).unwrap_err().contains("steps of 10"));
        assert!(s.parse_ui(&json!(270)).is_err());
        assert!(s.parse_ui(&json!(0)).is_err());
        assert!(s.accepts(family("26.3"), &Val::Int(10)));
        assert!(!s.accepts(family("26.3"), &Val::Int(261)));
    }

    #[test]
    fn gui_scale_zero_is_auto_and_any_size_minecraft_keeps_is_copied() {
        let s = setting("video.guiScale");
        assert_eq!(s.display(&Val::Int(0)), "Auto");
        assert_eq!(s.display(&Val::Int(3)), "3");
        assert!(s.accepts(family("26.3"), &Val::Int(12)), "Minecraft clamps a large scale to the window instead of resetting it");
        assert!(s.parse_ui(&json!(12)).is_err(), "but the launcher's slider stops at 8");
        assert!(!s.accepts(family("26.3"), &Val::Int(-1)));
    }

    #[test]
    fn enum_settings_use_minecraft_s_stored_ids_and_names() {
        let particles = setting("video.particles");
        assert_eq!(particles.decode("2"), Some(Val::Int(2)));
        assert_eq!(particles.display(&Val::Int(2)), "Minimal");
        assert!(particles.accepts(family("26.1"), &Val::Int(2)));
        assert!(!particles.accepts(family("26.1"), &Val::Int(3)));
        assert!(particles.parse_ui(&json!(3)).is_err());
        let preset = setting("video.graphicsPreset");
        assert_eq!(preset.decode("\"fabulous\""), Some(Val::Text("fabulous".into())));
        assert_eq!(preset.decode("fabulous"), None, "Minecraft writes it quoted");
        assert_eq!(preset.encode(&Val::Text("custom".into())), "\"custom\"");
        assert!(!preset.accepts(family("26.3"), &Val::Text("ultra".into())));
        let clouds = setting("video.renderClouds");
        assert_eq!(clouds.decode("\"true\""), Some(Val::Text("true".into())));
        assert_eq!(clouds.decode("false"), Some(Val::Text("false".into())), "a JSON boolean is accepted too");
        assert_eq!(clouds.display(&Val::Text("true".into())), "Fancy");
        assert_eq!(clouds.encode(&Val::Text("fast".into())), "\"fast\"");
        let api = setting("video.preferredGraphicsBackend");
        assert!(api.accepts(family("26.2"), &Val::Text("vulkan".into())));
        assert!(!api.accepts(family("26.1"), &Val::Text("vulkan".into())), "26.1 has no graphics API enum");
    }

    #[test]
    fn out_of_range_and_unreadable_values_are_rejected() {
        let s = setting("video.renderDistance");
        assert!(!s.accepts(family("26.3"), &Val::Int(33)));
        assert!(!s.accepts(family("26.3"), &Val::Int(1)));
        assert_eq!(s.decode("twelve"), None);
        assert!(s.parse_ui(&json!("12")).is_err());
        assert!(s.parse_ui(&json!(12.5)).is_err());
        let volume = setting("audio.master");
        assert!(!volume.accepts(family("26.3"), &Val::Num(1.5)));
        assert_eq!(volume.parse_ui(&json!(40)), Ok(Val::Num(0.4)));
        assert_eq!(volume.encode(&Val::Num(0.4)), "0.4");
        let lang = setting("language.lang");
        assert!(lang.parse_ui(&json!("en_us")).is_ok());
        assert!(lang.parse_ui(&json!("EN US")).is_err());
        assert!(lang.parse_ui(&json!("")).is_err());
    }

    #[test]
    fn stepped_sliders_only_take_the_values_minecraft_keeps() {
        // OptionInstance's stepped ranges cut a value off the step on load ((int)(value × 4) for entity
        // distance), so 110% would become 100%: refused, with the step.
        let entities = setting("video.entityDistanceScaling");
        assert_eq!(entities.parse_ui(&json!(110)).unwrap_err(), "Entity distance goes in steps of 25%.");
        assert_eq!(entities.parse_ui(&json!(125)), Ok(Val::Num(1.25)));
        assert_eq!(entities.parse_ui(&json!(50)), Ok(Val::Num(0.5)));
        let delay = setting("chat.chatDelay");
        assert_eq!(delay.parse_ui(&json!(0.7)), Ok(Val::Num(0.7)));
        assert!(delay.parse_ui(&json!(0.75)).unwrap_err().contains("steps of 0.1 s"));
        let fade = setting("video.chunkSectionFadeInTime");
        for i in 0..=40 {
            // Typed as the launcher shows it, stored exactly as Minecraft computes that step (i / 20.0).
            let shown: f64 = format!("{:.2}", i as f64 * 0.05).parse().unwrap();
            assert_eq!(fade.parse_ui(&json!(shown)), Ok(Val::Num(i as f64 / 20.0)), "{shown} s");
        }
        assert_eq!(setting("accessibility.notificationDisplayTime").parse_ui(&json!(2.3)), Ok(Val::Num(23.0 / 10.0)));
        assert!(setting("audio.master").parse_ui(&json!(40.5)).is_err(), "volumes go in whole percent");
    }

    #[test]
    fn chat_sizes_are_shown_in_pixels_like_minecraft() {
        let width = setting("chat.chatWidth");
        assert_eq!(width.display(&Val::Num(1.0)), "320 px");
        assert_eq!(width.display(&Val::Num(0.0)), "40 px");
        let unfocused = setting("chat.chatHeightUnfocused");
        assert_eq!(unfocused.display(&Val::Num(0.4375)), "90 px");
        assert_eq!(unfocused.parse_ui(&json!(90)), Ok(Val::Num(0.4375)));
        let opacity = setting("chat.chatOpacity");
        assert_eq!(opacity.display(&Val::Num(0.0)), "10%");
        assert_eq!(opacity.display(&Val::Num(1.0)), "100%");
        assert_eq!(opacity.parse_ui(&json!(55)), Ok(Val::Num(0.5)));
    }

    #[test]
    fn toggles_read_like_minecraft_s_buttons() {
        assert_eq!(setting("controls.toggleSprint").display(&Val::Bool(true)), "Toggle");
        assert_eq!(setting("controls.toggleSprint").display(&Val::Bool(false)), "Hold");
        assert_eq!(setting("video.enableVsync").display(&Val::Bool(true)), "On");
        let hat = setting("skin.hat");
        assert_eq!(hat.decode("true"), Some(Val::Bool(true)));
        assert_eq!(hat.decode("yes"), None);
        assert_eq!(hat.control(), Control::Toggle);
    }

    #[test]
    fn small_whole_number_ranges_with_names_become_choices() {
        let blend = setting("video.biomeBlendRadius");
        assert!(matches!(blend.control(), Control::Choice { ref options } if options.len() == 8));
        assert_eq!(blend.display(&Val::Int(2)), "5×5");
        let anisotropy = setting("video.maxAnisotropyBit");
        assert!(matches!(anisotropy.control(), Control::Choice { ref options } if options.len() == 3 && options[0].label == "2×"));
        assert!(matches!(setting("video.renderDistance").control(), Control::Range { .. }));
    }

    #[test]
    fn key_names_are_checked_per_family() {
        let (old, new) = (family("26.2"), family("26.3"));
        assert_eq!(check_key(new, "key.keyboard.media.play"), KeyCheck::Named);
        assert_eq!(check_key(old, "key.keyboard.media.play"), KeyCheck::Invalid, "SDL3-only names don't exist in 26.2");
        assert_eq!(check_key(old, "key.keyboard.f25"), KeyCheck::Named);
        assert_eq!(check_key(new, "key.keyboard.f25"), KeyCheck::Invalid, "26.3 dropped F25");
        assert_eq!(check_key(old, "key.keyboard.65"), KeyCheck::Numeric);
        assert_eq!(check_key(new, "key.mouse.9"), KeyCheck::Numeric);
        assert_eq!(check_key(old, "scancode.30"), KeyCheck::Numeric);
        assert_eq!(check_key(new, "scancode.30"), KeyCheck::Invalid, "SDL3 has no scancode prefix");
        assert_eq!(check_key(new, "key.keyboard.foo"), KeyCheck::Invalid);
        assert_eq!(check_key(new, "nonsense"), KeyCheck::Invalid);
        assert_eq!(check_key(new, UNBOUND), KeyCheck::Named);
        assert!(!same_input(old, new));
        assert!(same_input(family("26.1"), old));
    }

    #[test]
    fn key_names_read_as_people_name_keys() {
        assert_eq!(key_label("key.keyboard.right.shift"), "Right Shift");
        assert_eq!(key_label("key.keyboard.w"), "W");
        assert_eq!(key_label("key.keyboard.1"), "1");
        assert_eq!(key_label("key.mouse.left"), "Left Button");
        assert_eq!(key_label("key.mouse.4"), "Button 4");
        assert_eq!(key_label(UNBOUND), "Not Bound");
        assert_eq!(key_label("key.keyboard.300"), "Key 300");
    }

    #[test]
    fn vanilla_binds_have_their_family_s_default_key() {
        let attack = catalogue(Some(family("26.1"))).into_iter().find(|s| s.id == "keybind.key.attack").unwrap();
        assert_eq!(attack.default.as_deref(), Some("key.mouse.left"));
        assert_eq!(attack.label, "Attack/Destroy");
        let forward = catalogue(Some(family("26.3"))).into_iter().find(|s| s.id == "keybind.key.forward").unwrap();
        assert_eq!(forward.default.as_deref(), Some("key.keyboard.w"));
        assert_eq!(forward.key, "key_key.forward");
        let flint = catalogue(None).into_iter().find(|s| s.id == "keybind.key.flintclient.control_center").unwrap();
        assert_eq!(flint.bind, Some(Bind::Flint));
        assert_eq!(flint.default.as_deref(), Some("key.keyboard.right.shift"));
    }

    #[test]
    fn defaults_decode_and_are_accepted_where_available() {
        for f in FAMILIES.iter() {
            for s in catalogue(Some(f)).iter().filter(|s| s.available_in(f).is_ok() && s.bind.is_none()) {
                let default = s.default.as_deref().unwrap();
                let value = s.decode(default).unwrap_or_else(|| panic!("{}'s default {default} doesn't decode", s.key));
                assert!(s.accepts(f, &value), "{}'s default isn't accepted in {}", s.key, f.id);
                assert_eq!(s.encode(&value), default, "{}'s default is spelled as Minecraft writes it", s.key);
            }
        }
    }

    #[test]
    fn the_bench_file_decodes_every_listed_setting() {
        let doc = super::super::options_txt::OptionsDoc::parse(crate::gamesettings::testkit::FIXTURE_26_3).unwrap();
        let f = family("26.3");
        for s in catalogue(Some(f)).iter().filter(|s| s.available_in(f).is_ok() && s.bind != Some(Bind::Flint)) {
            let raw = doc.get(&s.key).unwrap_or_else(|| panic!("{} is missing from a file 26.3 wrote", s.key));
            let value = s.decode(raw).unwrap_or_else(|| panic!("{} = {raw} doesn't decode", s.key));
            if s.repr == Repr::Key {
                assert_eq!(check_key(f, raw), KeyCheck::Named);
            } else {
                assert!(s.accepts(f, &value), "{} = {raw}", s.key);
            }
        }
    }

    #[test]
    fn controls_serialise_as_the_contract_describes() {
        assert_eq!(serde_json::to_value(Control::Toggle).unwrap(), json!({ "kind": "toggle" }));
        assert_eq!(serde_json::to_value(setting("video.fov").control()).unwrap()["special"][0], json!({ "value": 70.0, "label": "Normal" }));
        let choice = serde_json::to_value(setting("video.particles").control()).unwrap();
        assert_eq!(choice, json!({ "kind": "choice", "options": [{ "value": 0, "label": "All" }, { "value": 1, "label": "Decreased" }, { "value": 2, "label": "Minimal" }] }));
        assert_eq!(serde_json::to_value(setting("keybind.key.jump").control()).unwrap(), json!({ "kind": "keybind" }));
        assert_eq!(serde_json::to_value(SyncCategory::HudLayouts).unwrap(), json!("hudLayouts"));
        assert_eq!(serde_json::to_value(Category::Keybinds).unwrap(), json!("keybinds"));
    }
}
