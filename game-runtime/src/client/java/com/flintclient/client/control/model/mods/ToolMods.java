package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IdKind;
import com.flintclient.client.control.model.IdListSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.SettingSection;
import com.flintclient.client.control.model.TextSetting;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Right Shift menu's fourth batch: animation tweaks, a search for any keybind, items you can't drop by accident, and a
 * debug screen with only the lines you want.
 */
public final class ToolMods {
	private ToolMods() {
	}

	public static final class Animations {
		public static final String ID = "animations";
		public static final BoolSetting NO_HIT_DIRECTION = new BoolSetting("disableHitDirection", "Disable hit direction",
			"Your screen tilts the same way whichever side you're hit from", false);
		public static final BoolSetting CUSTOM_DAMAGE = new BoolSetting("customDamageColor", "Custom damage color",
			"The flash a player or mob gets when hit, in your color", false);
		public static final RgbSetting DAMAGE_COLOR = new RgbSetting("damageColor", "Damage color", null, 0xA00000);
		public static final IntSetting DAMAGE_STRENGTH = new IntSetting("damageColorStrength", "Damage color strength", null, 30, 5, 100, 5, "%");
		public static final BoolSetting SWING = new BoolSetting("modifySwingDuration", "Change swing duration",
			"How long your own arm swing takes on your screen", false);
		public static final FloatSetting SWING_MULTIPLIER = new FloatSetting("swingDurationMultiplier", "Swing duration", null, 1, 0.25F, 3, 0.05F,
			"×");
		public static final BoolSetting NO_POTION_PARTICLES = new BoolSetting("disableCameraPotionParticles", "Hide own potion particles",
			"Your own effect swirls (potions, golden apples) stay out of your view in first person; in third person you still see them", true);
		public static final KeySetting POTION_KEY = new KeySetting("potionParticlesKeybind", "Potion particles key", "Turns the hiding on and off",
			KeySetting.NONE);
		public static final BoolSetting ARMOR_HURT = new BoolSetting("armorHurtColor", "Armor hurt color", "Worn armor flashes red with whoever wears it",
			false);
		public static final KeySetting ARMOR_KEY = new KeySetting("armorHurtColorKeybind", "Armor hurt color key", "Turns it on and off",
			KeySetting.NONE);
		public static final BoolSetting NO_HEALTH_FLASH = new BoolSetting("disableHealthFlashing", "Disable health flashing",
			"Your hearts don't flash when you're hit or heal", false);
		// ---- Not Enough Animations (tr7zw): every option of its settings screen and its item lists, with its defaults. Flint's
		// own code (its licence lets the behaviour be followed, not the code reused).
		public static final BoolSetting MAP = new BoolSetting("enableInWorldMapRendering", "In-world map rendering",
			"A map held in third person shows its picture", true);
		public static final BoolSetting LADDER = new BoolSetting("enableLadderAnimation", "Ladder animation",
			"Arms and legs climb ladders (and trapdoors), not vines or scaffolding", true);
		public static final BoolSetting LADDER_ROTATE = new BoolSetting("enableRotateToLadder", "Snap body to ladder",
			"Your body turns to face the ladder you climb", true);
		public static final FloatSetting LADDER_AMPLIFIER = new FloatSetting("ladderAnimationAmplifier", "Ladder arm amplifier",
			"How far the arms move while climbing", 0.35F, 0.1F, 0.5F, 0.01F, "");
		public static final FloatSetting LADDER_HEIGHT = new FloatSetting("ladderAnimationArmHeight", "Ladder arm height",
			"How high the arms reach while climbing", 1.7F, 1, 3, 0.1F, "");
		public static final FloatSetting LADDER_SPEED = new FloatSetting("ladderAnimationArmSpeed", "Ladder arm speed",
			"How fast the arms move while climbing", 2, 1, 4, 0.1F, "");
		public static final BoolSetting CRAWLING = new BoolSetting("enableCrawlingAnimation", "Crawling animation",
			"Crawling out of water is a crawl, not a swim", true);
		public static final BoolSetting EAT_DRINK = new BoolSetting("enableEatDrinkAnimation", "Eat/drink animation",
			"The arm lifts food and drink to the mouth", true);
		public static final BoolSetting BOAT = new BoolSetting("enableRowBoatAnimation", "Boat rowing animation", "The arms hold and pull the oars",
			true);
		public static final BoolSetting HORSE = new BoolSetting("enableHorseAnimation", "Horse riding animation", "The arms hold the reins", true);
		public static final BoolSetting HORSE_LEGS = new BoolSetting("enableHorseLegAnimation", "Horse riding leg animation",
			"The legs grip the horse's sides", false);
		public static final BoolSetting ITEM_SWAP = new BoolSetting("itemSwapAnimation", "Item swap animation",
			"The arms move when items swap between hands", true);
		public static final BoolSetting PET = new BoolSetting("petAnimation", "Pet wolves/cats",
			"Crouch while looking at a wolf or cat to pet it", true);
		public static final BoolSetting FREEZING = new BoolSetting("freezingAnimation", "Freezing animation",
			"Arms wrap and shiver in powder snow", true);
		public static final OptionSetting BOW = new OptionSetting("bowAnimation", "Bow animation", "The pose while drawing a bow", 0, "vanilla",
			"Vanilla", "custom_v1", "Custom V1");
		public static final BoolSetting BOW_LOCK = new BoolSetting("customBowRotationLock", "Custom bow rotation lock",
			"The body turns sideways while using the custom bow animation", false);
		public static final BoolSetting CROSSBOW_CLAMP = new BoolSetting("clampCrossbowAnimations", "Clamp crossbow animations",
			"Arms holding a crossbow don't swing past straight ahead", false);
		public static final BoolSetting BURNING = new BoolSetting("burningAnimation", "Burning animation",
			"Arms flail while you burn (not with fire resistance)", true);
		public static final BoolSetting SMOOTHING = new BoolSetting("enableAnimationSmoothing", "Animation smoothing",
			"Arms and legs ease between poses instead of jumping", true);
		public static final BoolSetting NO_LEG_SMOOTHING = new BoolSetting("disableLegSmoothing", "Disable leg smoothing",
			"Only the arms are smoothed", false);
		public static final FloatSetting SMOOTHING_SPEED = new FloatSetting("animationSmoothingSpeed", "Smoothing amount",
			"How far each tick moves towards the new pose (lower is smoother)", 0.1F, 0.01F, 1, 0.01F, "");
		public static final OptionSetting ROTATION_LOCK = new OptionSetting("rotationLock", "Rotation lock",
			"How your body follows your head", 0, "none", "None", "fixed", "Fixed", "smooth", "Smooth");
		public static final BoolSetting LOCK_FIRST_PERSON = new BoolSetting("limitRotationLockToFP", "Limit rotation lock to FP",
			"Your own body is locked only in first person", true);
		public static final BoolSetting LOCK_EVERYONE = new BoolSetting("applyRotationLockToEveryone", "Rotation lock for everyone",
			"Other players' bodies are locked too", false);
		public static final BoolSetting ROTATION_LOCKING = new BoolSetting("enableRotationLocking", "Animation rotation locking",
			"While eating, drinking or blocking, the body turns with the head", true);
		public static final FloatSetting MAX_NORMAL_ANGLE = new FloatSetting("maxNormalAngle", "Normal angle",
			"How far the head turns before the body follows", 50, 0, 50, 0.1F, "°");
		public static final FloatSetting MAX_BLOCKING_ANGLE = new FloatSetting("maxBlockingAngle", "Blocking angle",
			"The same while blocking with a shield", 15, 0, 15, 0.1F, "°");
		public static final OptionSetting HOLD_UP_MODE = new OptionSetting("holdUpItemsMode", "Holding up mode",
			"Which held items are held up in front of you", 0, "config", "Only config items", "all", "All items", "none", "No items",
			"config_inverted", "Not config items");
		public static final BoolSetting LANTERNS = new BoolSetting("animateLanterns", "Animate lanterns", "Held lanterns swing as you move", true);
		public static final FloatSetting HOLD_UP_OFFSET = new FloatSetting("holdUpItemOffset", "Holding up offset",
			"How much higher the item is held", 0, -0.5F, 0.4F, 0.1F, "");
		public static final OptionSetting HOLD_UP_TARGET = new OptionSetting("holdUpTarget", "Hold up target", "Where a held-up item points", 1,
			"none", "None", "camera", "Camera");
		public static final FloatSetting HOLD_UP_CAMERA_OFFSET = new FloatSetting("holdUpCameraOffset", "Hold up camera offset",
			"How far the arm moves to the side when aimed at the camera", 0.1F, -0.3F, 0.6F, 0.1F, "");
		public static final BoolSetting HOLD_UP_SELF = new BoolSetting("holdUpOnlySelf", "Hold up only self", "Other players hold items normally",
			false);
		public static final IdListSetting HOLDING_ITEMS = new IdListSetting("holdingItems", "Hold up items", "The items held up in front of you",
			IdKind.ITEM, List.of("minecraft:clock", "minecraft:compass", "minecraft:torch", "minecraft:lantern", "minecraft:soul_torch",
				"minecraft:soul_lantern", "minecraft:recovery_compass", "quad-mstv-mtv:acacia_torch", "quad-mstv-mtv:bamboo_torch",
				"quad-mstv-mtv:birch_torch", "quad-mstv-mtv:cherry_torch", "quad-mstv-mtv:crimson_torch", "quad-mstv-mtv:dark_oak_torch",
				"quad-mstv-mtv:pale_oak_torch", "quad-mstv-mtv:jungle_torch", "quad-mstv-mtv:mangrove_torch", "quad-mstv-mtv:spruce_torch",
				"quad-mstv-mtv:warped_torch", "quad-mstv-mtv:acacia_soul_torch", "quad-mstv-mtv:bamboo_soul_torch", "quad-mstv-mtv:birch_soul_torch",
				"quad-mstv-mtv:cherry_soul_torch", "quad-mstv-mtv:crimson_soul_torch", "quad-mstv-mtv:dark_oak_soul_torch",
				"quad-mstv-mtv:pale_oak_soul_torch", "quad-mstv-mtv:jungle_soul_torch", "quad-mstv-mtv:mangrove_soul_torch",
				"quad-mstv-mtv:spruce_soul_torch", "quad-mstv-mtv:warped_soul_torch", "minecraft:copper_lantern", "minecraft:exposed_copper_lantern",
				"minecraft:waxed_copper_lantern", "minecraft:waxed_exposed_copper_lantern", "minecraft:waxed_oxidized_copper_lantern",
				"minecraft:weathered_copper_lantern", "minecraft:waxed_weathered_copper_lantern"));
		public static final IdListSetting LANTERN_ITEMS = new IdListSetting("lanternItems", "Lantern items", "The items that swing like lanterns",
			IdKind.ITEM, List.of("minecraft:waxed_copper_lantern", "minecraft:waxed_exposed_copper_lantern", "minecraft:soul_lantern",
				"minecraft:waxed_oxidized_copper_lantern", "minecraft:exposed_copper_lantern", "minecraft:lantern",
				"minecraft:waxed_weathered_copper_lantern", "minecraft:weathered_copper_lantern", "minecraft:oxidized_copper_lantern",
				"minecraft:copper_lantern"));
		public static final BoolSetting ELYTRA = new BoolSetting("tweakElytraAnimation", "Tweak elytra flying",
			"Arms and legs sway with your speed while gliding", true);
		public static final BoolSetting NO_SLEEP_ITEMS = new BoolSetting("dontHoldItemsInBed", "No sleep items", "Sleeping players hold nothing",
			true);
		public static final BoolSetting NO_SLEEP_MOVEMENT = new BoolSetting("freezeArmsInBed", "No sleep movement", "Arms lie still in bed", true);
		public static final BoolSetting OFFHAND_HIDING = new BoolSetting("enableOffhandHiding", "Bow animation fixes",
			"The other hand's item hides while you pull a bow or crossbow", true);
		public static final IdListSetting MAP_ITEMS = new IdListSetting("mapHolding", "Map items", "Items held like a map", IdKind.ITEM,
			List.of("minecraft:filled_map", "antiqueatlas:antique_atlas"));
		public static final IdListSetting BOW_ITEMS = new IdListSetting("hideItemsForTheseBows", "Also bows",
			"More items that hide the other hand's item, as bows do", IdKind.ITEM, List.of());
		public static final BoolSetting NARUTO = new BoolSetting("narutoRunning", "Naruto running", "Arms trail behind you while sprinting", false);
		public static final BoolSetting HUGGING = new BoolSetting("huggingAnimation", "Simple hugging animation",
			"Crouch facing a crouching player to hug them", false);
		public static final BoolSetting FALLING = new BoolSetting("fallingAnimation", "Falling animation", "Arms and legs flail in a long fall",
			false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Animations", "Tweak hurt, swing and item animations")
			.keywords("animation", "hurt", "swing", "damage color", "particles", "flash", "notenoughanimations", "nea", "ladder", "eat", "map",
				"boat", "horse", "crawl", "lantern", "rotation lock").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Hurt Animation", NO_HIT_DIRECTION).section("Damage Color", CUSTOM_DAMAGE, DAMAGE_COLOR, DAMAGE_STRENGTH)
			.section("Swing Duration", SWING, SWING_MULTIPLIER).section("General", NO_POTION_PARTICLES, POTION_KEY, ARMOR_HURT, ARMOR_KEY, NO_HEALTH_FLASH)
			.section("Animations", MAP, LADDER, LADDER_ROTATE, LADDER_AMPLIFIER, LADDER_HEIGHT, LADDER_SPEED, CRAWLING, EAT_DRINK, BOAT, HORSE,
				HORSE_LEGS, ITEM_SWAP, PET, FREEZING, BOW, BOW_LOCK, CROSSBOW_CLAMP, BURNING)
			.section("Smoothing", SMOOTHING, NO_LEG_SMOOTHING, SMOOTHING_SPEED)
			.section("Rotation", ROTATION_LOCK, LOCK_FIRST_PERSON, LOCK_EVERYONE, ROTATION_LOCKING)
			.section("Rotation Angle", MAX_NORMAL_ANGLE, MAX_BLOCKING_ANGLE)
			.section("Hold Up", HOLD_UP_MODE, LANTERNS, HOLD_UP_OFFSET, HOLD_UP_TARGET, HOLD_UP_CAMERA_OFFSET, HOLD_UP_SELF, HOLDING_ITEMS,
				LANTERN_ITEMS)
			.section("Fixes", ELYTRA, NO_SLEEP_ITEMS, NO_SLEEP_MOVEMENT, OFFHAND_HIDING, MAP_ITEMS, BOW_ITEMS)
			.section("Fun", NARUTO, HUGGING).section("Unfinished", FALLING)
			.needs(Requirement.WORLD).note("The hurt camera, the damage vignette and flat items are their own mods: Hurt Cam, Damage Tint and 2D Items. "
				+ "The player animations are Not Enough Animations': on every player you see, you in third person and in the inventory.")
			.build();
	}

	public static final class KeybindSearch {
		public static final String ID = "keybind_search";
		public static final KeySetting KEY = new KeySetting("keybind", "Open key", "Opens the search while you play", "key.keyboard.k");
		public static final IntSetting ROWS = new IntSetting("entryCount", "Results shown", null, 5, 3, 12, 1, "");
		public static final BoolSetting HIDE_BOUND = new BoolSetting("hideBound", "Only unbound keybinds", "Leaves out keybinds that have a key", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Keybind Search", "A searchable keybind list, by name, key or mod")
			.keywords("keybind", "search", "controls", "keys", "find", "rebind").icon(IconId.KEYS).category(Category.UTILITY)
			.section("General", KEY, ROWS, HIDE_BOUND)
			.note("Options › Controls › Key Binds gets a Search Keybinds button: every keybind grouped by mod, searched by name, key or mod, "
				+ "each with a Rebind button. In a world the open key also finds a keybind and uses it (Enter or a click), even one "
				+ "without a key.").build();
	}

	public static final class DropPrevention {
		public static final String ID = "drop_prevention";
		public static final IdListSetting ITEMS = new IdListSetting("protectedItems", "Protected items", null, IdKind.ITEM,
			List.of("minecraft:diamond_sword", "minecraft:netherite_sword", "minecraft:totem_of_undying"));
		public static final KeySetting TOGGLE_KEY = new KeySetting("keybindToggleHeldItem", "Protect held item key",
			"Adds the item in your hand to the list, or takes it off", KeySetting.NONE);
		public static final KeySetting LIST_KEY = new KeySetting("keybind", "Open list key", "Opens this page to see and change the list",
			KeySetting.NONE);
		public static final RgbSetting HIGHLIGHT = new RgbSetting("slotHighlightColor", "Slot highlight", "Marks protected items on your hotbar",
			0xFFFFFF);
		public static final IntSetting HIGHLIGHT_STRENGTH = new IntSetting("slotHighlightStrength", "Highlight strength", "0: no highlight", 40, 0,
			100, 5, "%");
		public static final BoolSetting IN_INVENTORY = new BoolSetting("preventInventoryDrop", "In inventories too",
			"Also stops throwing them out of an open inventory", true);
		public static final FloatSetting SCROLL = new FloatSetting("scrollNotches", "Scroll resistance",
			"Mouse wheel notches it takes to scroll off a protected item (1: as usual, no delay)", 1, 1, 5, 0.5F, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Drop Prevention", "Keep chosen items from being dropped")
			.keywords("drop", "protect", "throw", "q", "keep").icon(IconId.SHIELD).category(Category.UTILITY)
			.section("General", ITEMS, TOGGLE_KEY, LIST_KEY, HIGHLIGHT, HIGHLIGHT_STRENGTH, IN_INVENTORY, SCROLL).needs(Requirement.WORLD).build();
	}

	/**
	 * Custom F3: BetterF3's debug screen, with its options, defaults and ranges. Modules of named lines down two columns, each
	 * module with its own name and value colours (some with more), every line switchable; the columns' order, the background,
	 * the shadow, the font scale and the slide-in animation; what it hides while open and what opens with it.
	 */
	public static final class CustomF3 {
		public static final String ID = "custom_f3";
		// ---------- General ----------
		public static final BoolSetting AUTO_START = new BoolSetting("autoStart", "Auto start",
			"Automatically starts the debug menu when the game starts", false);
		public static final BoolSetting SPACE_MODULES = new BoolSetting("spaceModules", "Space modules", "Adds a space after each module", false);
		public static final BoolSetting SHADOW = new BoolSetting("shadowText", "Shadow text", "Makes all characters have a shadow effect", true);
		public static final BoolSetting ANIMATIONS = new BoolSetting("animations", "Enable animations",
			"Add an animation when closing and opening the F3 HUD", true);
		public static final FloatSetting ANIMATION_SPEED = new FloatSetting("animationSpeed", "Animation speed", "Set the speed of the animation", 1,
			1, 3, 0.1F, "×");
		public static final FloatSetting FONT_SCALE = new FloatSetting("fontScale", "Font scale", "Set the scale of the font", 1, 0.1F, 2, 0.05F,
			"×");
		public static final RgbSetting BACKGROUND = new RgbSetting("backgroundColor", "Background color", "Change the background color of every line",
			0x505050);
		public static final IntSetting BACKGROUND_OPACITY = new IntSetting("backgroundOpacity", "Background opacity", null, 44, 0, 100, 1, "%");
		public static final BoolSetting HIDE_CROSSHAIR = new BoolSetting("hideDebugCrosshair", "Hide the debug crosshair",
			"Hide the debug crosshair when the F3 HUD is open", false);
		public static final BoolSetting HIDE_SIDEBAR = new BoolSetting("hideSidebar", "Hide sidebar", "Hide the sidebar when the F3 menu is active",
			true);
		public static final BoolSetting HIDE_BOSSBAR = new BoolSetting("hideBossbar", "Hide bossbar", "Hide the bossbar when the F3 menu is active",
			true);
		public static final BoolSetting ALWAYS_PROFILER = new BoolSetting("alwaysEnableProfiler", "Always enable the profiler",
			"Always enable the profiler when using F3", false);
		public static final BoolSetting ALWAYS_TPS = new BoolSetting("alwaysEnableTps", "Always enable the TPS graph",
			"Always enable the TPS graph when using F3. Cannot be used with the ping graph", false);
		public static final BoolSetting ALWAYS_PING = new BoolSetting("alwaysEnablePing", "Always enable the ping graph",
			"Always enable the ping graph when using F3. Cannot be used with the TPS graph", false);
		public static final BoolSetting PERFORMANCE = new BoolSetting("performanceOptimizations", "Performance optimizations",
			"Cache module lines and only refresh when values change", true);
		// ---------- the columns ----------
		/** The module ids a column may list. */
		public static final List<String> MODULE_IDS = List.of("minecraft", "fps", "graphics", "server", "coords", "chunks", "location", "entity",
			"sound", "help", "miscleft", "miscright", "system", "target", "empty");
		public static final TextSetting LEFT = new TextSetting("leftModules", "Left modules",
			"The modules down the left, in order, separated by commas; empty is a separator (empty:3 for three lines)",
			"minecraft, fps, graphics, server, coords, chunks, location, entity, sound, help, empty, miscleft", 400);
		public static final TextSetting RIGHT = new TextSetting("rightModules", "Right modules", "The modules down the right, in order",
			"system, miscright, empty, target", 400);
		public static final IntSetting EMPTY_LINES = new IntSetting("emptyLines", "Empty lines count",
			"The number of empty lines to display for a separator", 1, 0, 20, 1, "");
		// ---------- keys (Flint's) ----------
		public static final KeySetting ALT_F3 = new KeySetting("alternateF3Keybind", "Debug screen key", "Opens and closes it like F3",
			KeySetting.NONE);
		public static final KeySetting ALT_PIE = new KeySetting("alternatePieKeybind", "Profiler chart key", "Shows and hides the profiler chart",
			KeySetting.NONE);
		public static final KeySetting ALT_GRAPH = new KeySetting("alternateGraphKeybind", "FPS chart key", "Shows and hides the FPS and TPS charts",
			KeySetting.NONE);

		/** One of BetterF3's modules: whether it shows, its name and value colours, its own extra settings and its lines. */
		public static final class F3Module {
			public final String id;
			public final String label;
			public final BoolSetting enabled;
			public final RgbSetting nameColor;
			public final RgbSetting valueColor;
			/** Its lines' switches, by the line's id, in order. */
			public final Map<String, BoolSetting> lines = new LinkedHashMap<>();
			private final List<SettingDescriptor<?>> extras = new ArrayList<>();

			F3Module(String id, String label, int name, int value, String... lineIdsAndLabels) {
				this.id = id;
				this.label = label;
				enabled = new BoolSetting(id + "Enabled", "Enable module", "Enables this module", true);
				nameColor = new RgbSetting(id + "NameColor", "Name color", "Change the color of the name of every line in this module", name);
				valueColor = new RgbSetting(id + "ValueColor", "Value color", "Change the color of the value of every line in this module", value);
				for (int i = 0; i < lineIdsAndLabels.length; i += 2) {
					lines.put(lineIdsAndLabels[i], new BoolSetting(id + "Line_" + lineIdsAndLabels[i], lineIdsAndLabels[i + 1], "Enable line", true));
				}
			}

			F3Module extra(SettingDescriptor<?>... settings) {
				extras.addAll(List.of(settings));
				return this;
			}

			/** Line {@code lineId}'s switch. */
			public BoolSetting line(String lineId) {
				return lines.get(lineId);
			}

			SettingSection section() {
				List<SettingDescriptor<?>> all = new ArrayList<>();
				all.add(enabled);
				all.add(nameColor);
				all.add(valueColor);
				all.addAll(extras);
				all.addAll(lines.values());
				return SettingSection.of(label, all.toArray(new SettingDescriptor<?>[0]));
			}
		}

		public static final F3Module MINECRAFT = new F3Module("minecraft", "Minecraft", 0xA0522D, 0x00AA00, "minecraft", "Minecraft");
		public static final RgbSetting FPS_HIGH = new RgbSetting("fpsColorHigh", "FPS high color", "Change the color of the text when the FPS is high",
			0x55FF55);
		public static final RgbSetting FPS_MEDIUM = new RgbSetting("fpsColorMedium", "FPS medium color",
			"Change the color of the text when the FPS is medium", 0xFFFF55);
		public static final RgbSetting FPS_LOW = new RgbSetting("fpsColorLow", "FPS low color", "Change the color of the text when the FPS is low",
			0xFF5555);
		public static final F3Module FPS = new F3Module("fps", "FPS", 0xFFFFFF, 0xFFFFFF, "fps", "FPS").extra(FPS_HIGH, FPS_MEDIUM, FPS_LOW);
		public static final F3Module GRAPHICS = new F3Module("graphics", "Graphics", 0xFFAA00, 0x55FFFF, "render_distance", "Render distance",
			"graphics", "Graphics", "clouds", "Clouds", "biome_blend_radius", "Biome blend radius", "shader", "Shader");
		public static final F3Module SERVER = new F3Module("server", "Server", 0xAAAAAA, 0xFFFF55, "server_tick", "Server", "packets_sent",
			"Packets sent", "packets_received", "Packets received", "tick_manager_status", "Tick manager status");
		public static final RgbSetting COORDS_X = new RgbSetting("coordsColorX", "X color",
			"Change the color of the X coordinate of every line in this module", 0xFF5555);
		public static final RgbSetting COORDS_Y = new RgbSetting("coordsColorY", "Y color",
			"Change the color of the Y coordinate of every line in this module", 0x55FF55);
		public static final RgbSetting COORDS_Z = new RgbSetting("coordsColorZ", "Z color",
			"Change the color of the Z coordinate of every line in this module", 0x55FFFF);
		public static final F3Module COORDS = new F3Module("coords", "Coordinates", 0xFF5555, 0xFFFFFF, "player_coords", "Coordinates",
			"block_coords", "Block", "chunk_relative_coords", "Chunk relative", "chunk_coords", "Chunk coordinates", "velocity", "Velocity",
			"absolute_velocity", "Absolute velocity", "horizontal_velocity", "Horizontal velocity").extra(COORDS_X, COORDS_Y, COORDS_Z);
		public static final RgbSetting CHUNKS_ENABLED = new RgbSetting("chunksEnabledColor", "Enabled color",
			"Change the color of the text when chunk options are enabled", 0x55FF55);
		public static final RgbSetting CHUNKS_DISABLED = new RgbSetting("chunksDisabledColor", "Disabled color",
			"Change the color of the text when chunk options are disabled", 0xFF5555);
		public static final RgbSetting CHUNKS_TOTAL = new RgbSetting("chunksTotalColor", "Total color",
			"Change the color of the text for the total number of chunks", 0xFFAA00);
		public static final F3Module CHUNKS = new F3Module("chunks", "Chunks", 0x00AAFF, 0xFFFF55, "chunk_sections", "Chunk sections",
			"chunk_culling", "Chunk culling", "pending_chunks", "Pending chunks", "pending_uploads", "Pending uploads to GPU", "available_buffers",
			"Available buffers", "client_chunk_cache", "Client chunk cache", "loaded_chunks", "Loaded chunks", "loaded_chunks_server",
			"Loaded chunks (server)", "forceloaded_chunks", "Forceloaded chunks", "spawn_chunks", "Spawn chunks", "chunk_file", "Region file")
			.extra(CHUNKS_ENABLED, CHUNKS_DISABLED, CHUNKS_TOTAL);
		public static final F3Module LOCATION = new F3Module("location", "Location", 0x00AA00, 0x55FFFF, "dimension", "Dimension", "facing",
			"Facing", "rotation", "Rotation", "light", "Light", "light_server", "Light (server)", "highest_block", "Highest block",
			"highest_block_server", "Highest block (server)", "biome", "Biome", "local_difficulty", "Local difficulty", "day_ticks",
			"Current day ticks", "days_played", "Days played", "slime_chunk", "Slime chunk");
		public static final RgbSetting ENTITY_TOTAL = new RgbSetting("entityTotalColor", "Total color",
			"Change the color of the text for the total number of entities", 0xFFAA00);
		public static final F3Module ENTITY = new F3Module("entity", "Entities", 0xFF5555, 0xFFFF55, "particles", "Particles", "entities",
			"Entities", "monster", "Monsters", "creature", "Creatures", "ambient", "Ambient", "axolotls", "Axolotls", "underground_water_creature",
			"Underground water creatures", "water_creature", "Water creatures", "water_ambient", "Water ambient", "misc", "Misc").extra(ENTITY_TOTAL);
		public static final RgbSetting SOUND_MAXIMUM = new RgbSetting("soundMaximumColor", "Maximum color",
			"Change the color of the text for the maximum number of sounds", 0x00AAAA);
		public static final F3Module SOUND = new F3Module("sound", "Sounds", 0xFFAA00, 0x55FFFF, "sounds", "Sounds", "ambient_sounds",
			"Ambient sounds", "mood", "Mood").extra(SOUND_MAXIMUM);
		public static final RgbSetting HELP_ENABLED = new RgbSetting("helpEnabledColor", "Enabled color",
			"Change the color of the text when help options are enabled", 0x55FF55);
		public static final RgbSetting HELP_DISABLED = new RgbSetting("helpDisabledColor", "Disabled color",
			"Change the color of the text when help options are disabled", 0xFF5555);
		public static final F3Module HELP = new F3Module("help", "Help", 0xFDFE96, 0x55FFFF, "pie_graph_new", "Pie graph (F3+1)", "fps_tps_new",
			"FPS / TPS (F3+2)", "ping", "Ping / bandwidth (F3+3)", "help", "For help").extra(HELP_ENABLED, HELP_DISABLED);
		public static final F3Module MISC_LEFT = new F3Module("miscleft", "Misc left", 0xFDFE96, 0x55FFFF, "misc_left", "Misc left");
		public static final F3Module MISC_RIGHT = new F3Module("miscright", "Misc right", 0xFDFE96, 0x55FFFF, "misc_right", "Misc right");
		public static final BoolSetting MEMORY_COLOR = new BoolSetting("memoryColorToggle", "Memory color toggle",
			"Enables the color change of the memory value based on the percentage of memory used", true);
		public static final TextSetting TIME_FORMAT = new TextSetting("timeFormat", "Time format", "Set the format of the system time", "HH:mm:ss", 64);
		public static final F3Module SYSTEM = new F3Module("system", "System", 0xFFAA00, 0x55FFFF, "time", "Current time", "java_version",
			"Java version", "memory_usage", "Memory usage", "allocation_rate", "Allocation rate", "allocated_memory", "Allocated memory", "cpu",
			"CPU", "display", "Display", "gpu", "GPU", "gpu_utilization", "GPU utilization", "graphics_api", "Graphics API", "gpu_driver",
			"GPU driver").extra(MEMORY_COLOR, TIME_FORMAT);
		public static final F3Module TARGET = new F3Module("target", "Targeted", 0x00AAFF, 0xFFFF55, "targeted_block", "Targeted block", "id_block",
			"Block ID", "block_states", "Block states", "block_tags", "Block tags", "nothing", "Blank line", "targeted_fluid", "Targeted fluid",
			"id_fluid", "Fluid ID", "fluid_states", "Fluid states", "fluid_tags", "Fluid tags", "nothing2", "Another blank line",
			"targeted_entity", "Targeted entity");
		public static final List<F3Module> MODULES = List.of(MINECRAFT, FPS, GRAPHICS, SERVER, COORDS, CHUNKS, LOCATION, ENTITY, SOUND, HELP,
			MISC_LEFT, MISC_RIGHT, SYSTEM, TARGET);

		public static final ModuleDescriptor DESCRIPTOR = descriptor();

		/** The module called {@code id} ("coords"), or null (an unknown id, or "empty"). */
		public static F3Module module(String id) {
			for (F3Module module : MODULES) {
				if (module.id.equals(id)) {
					return module;
				}
			}
			return null;
		}

		private static ModuleDescriptor descriptor() {
			ModuleDescriptor.Builder b = ModuleDescriptor.builder(ID, "Custom F3", "A more readable, customizable debug screen")
				.keywords("f3", "debug", "coordinates", "betterf3", "lines").icon(IconId.GAUGE).category(Category.UTILITY)
				.section("General", AUTO_START, SPACE_MODULES, SHADOW, ANIMATIONS, ANIMATION_SPEED, FONT_SCALE, BACKGROUND, BACKGROUND_OPACITY,
					HIDE_CROSSHAIR, HIDE_SIDEBAR, HIDE_BOSSBAR, ALWAYS_PROFILER, ALWAYS_TPS, ALWAYS_PING, PERFORMANCE)
				.section("Modules", LEFT, RIGHT, EMPTY_LINES);
			for (F3Module module : MODULES) {
				b.section(module.section());
			}
			return b.section("Keys", ALT_F3, ALT_PIE, ALT_GRAPH)
				.note("Modules: minecraft, fps, graphics, server, coords, chunks, location, entity, sound, help, miscleft, miscright, system, "
					+ "target, and empty for a separator. F3 + F cycles the render distance (with Ctrl, the simulation distance; with Shift, "
					+ "backwards).")
				.build();
		}
	}

	public static final class AdvancementsScreen {
		public static final String ID = "advancements_screen";
		public static final IntSetting MARGIN_X = new IntSetting("marginX", "Side margin", "Room left and right of the screen", 30, 0, 200, 5, "");
		public static final IntSetting MARGIN_Y = new IntSetting("marginY", "Top and bottom margin", "Room above and below the screen", 30, 0, 200, 5,
			"");
		public static final RgbSetting DONE = new RgbSetting("hasColor", "Done color", null, 0x55FF55);
		public static final RgbSetting MISSING = new RgbSetting("doesntHaveColor", "Missing color", null, 0xFF5555);
		public static final IntSetting INFO_WIDTH = new IntSetting("infoWidth", "Info width", "The details panel on the right", 100, 60, 240, 5, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Custom Advancements Screen",
			"An advancements screen that lists what is still missing").keywords("advancements", "achievements", "progress", "missing", "criteria")
			.icon(IconId.GAUGE).category(Category.UTILITY).section("General", MARGIN_X, MARGIN_Y, DONE, MISSING, INFO_WIDTH).needs(Requirement.WORLD)
			.note("Opens in place of Minecraft's advancements screen (L, or from the pause menu).").build();
	}

	public static final class SoundFilters {
		public static final String ID = "sound_filters";
		// What counts as a hard wall (echoes) and a soft one (absorbs).
		public static final FloatSetting DENSE_HARDNESS = new FloatSetting("denseBlockHardness", "Hard block hardness",
			"Blocks at least this hard echo, as stone does", 1.25F, 0, 5, 0.05F, "");
		public static final FloatSetting DENSE_BLAST = new FloatSetting("denseBlockBlastResis", "Hard block blast resistance",
			"Or at least this blast resistance", 4.2F, 0, 20, 0.1F, "");
		public static final FloatSetting SOFT_HARDNESS = new FloatSetting("softBlockHardness", "Soft block hardness",
			"Blocks this soft or softer absorb sound, as wool does", 0.4F, 0, 5, 0.05F, "");
		public static final FloatSetting SOFT_BLAST = new FloatSetting("softBlockBlastResis", "Soft block blast resistance", null, 0.4F, 0, 20, 0.1F,
			"");
		// Reverb from the room you're in.
		public static final BoolSetting REVERB = new BoolSetting("reverbFilterEnabled", "Reverb", "Rooms and caves echo by their size and walls", true);
		public static final IntSetting MAX_BLOCKS = new IntSetting("reverbFilterMaxBlocks", "Room size limit", "How much space around you is measured",
			1024, 64, 4096, 64, " blocks");
		public static final BoolSetting CHECK_SKY = new BoolSetting("reverbFilterCheckSky", "Open sky", "Places under the open sky echo less", true);
		public static final FloatSetting BASE_REVERB = new FloatSetting("reverbFilterBaseReverb", "Base reverb", "Echo everywhere, even outdoors", 0, 0,
			1, 0.05F, "");
		public static final FloatSetting REVERB_PERCENT = new FloatSetting("reverbFilterReverbPercent", "Reverb amount", null, 1, 0, 2, 0.05F, "×");
		public static final FloatSetting MIN_DECAY = new FloatSetting("reverbFilterMinDecayTime", "Min decay time", null, 0.1F, 0.1F, 2, 0.05F, " s");
		public static final FloatSetting REFLECTIONS_BASE = new FloatSetting("reverbFilterReflectionsGainBase", "Reflections gain", null, 0.05F, 0, 1,
			0.01F, "");
		public static final FloatSetting REFLECTIONS_MULTIPLIER = new FloatSetting("reverbFilterReflectionsGainMultiplier", "Reflections gain per echo",
			null, 0.05F, 0, 1, 0.01F, "");
		public static final FloatSetting REFLECTIONS_DELAY = new FloatSetting("reverbFilterReflectionsDelayMultiplier", "Reflections delay", null,
			0.025F, 0, 0.3F, 0.005F, "");
		public static final FloatSetting LATE_BASE = new FloatSetting("reverbFilterLateReverbGainBase", "Late reverb gain", null, 1.26F, 0, 5, 0.01F, "");
		public static final FloatSetting LATE_MULTIPLIER = new FloatSetting("reverbFilterLateReverbGainMultiplier", "Late reverb gain per echo", null,
			0.1F, 0, 2, 0.01F, "");
		public static final FloatSetting LATE_DELAY = new FloatSetting("reverbFilterLateReverbDelayMultiplier", "Late reverb delay", null, 0.01F, 0,
			0.1F, 0.005F, "");
		public static final FloatSetting DENSITY = new FloatSetting("reverbFilterDensity", "Density", null, 0.2F, 0, 1, 0.05F, "");
		public static final FloatSetting DIFFUSION = new FloatSetting("reverbFilterDiffusion", "Diffusion", null, 0.6F, 0, 1, 0.05F, "");
		public static final FloatSetting GAIN = new FloatSetting("reverbFilterGain", "Reverb volume", null, 0.15F, 0, 1, 0.01F, "");
		public static final FloatSetting GAIN_HF = new FloatSetting("reverbFilterGainHF", "Reverb brightness", null, 0.8F, 0, 1, 0.01F, "");
		public static final FloatSetting DECAY_HF = new FloatSetting("reverbFilterDecayHFRatio", "Decay brightness", null, 0.7F, 0.1F, 2, 0.05F, "");
		public static final FloatSetting AIR_ABSORPTION = new FloatSetting("reverbFilterAirAbsorptionGainHF", "Air absorption", null, 0.99F, 0.89F, 1,
			0.01F, "");
		// Walls between you and a sound.
		public static final BoolSetting OBSTRUCTION = new BoolSetting("obstructionFilterEnabled", "Muffled through walls",
			"Sounds behind blocks sound duller", true);
		public static final FloatSetting OBSTRUCTION_STEP = new FloatSetting("obstructionFilterObstructionStep", "Muffling per block", null, 0.1F, 0.01F,
			0.5F, 0.01F, "");
		public static final FloatSetting OBSTRUCTION_MAX = new FloatSetting("obstructionFilterObstructionMax", "Most muffling", null, 0.98F, 0, 1, 0.01F,
			"");
		// Under water or lava.
		public static final BoolSetting LIQUID = new BoolSetting("liquidFilter", "Under water and lava", "Everything sounds muffled while you're under",
			true);
		public static final FloatSetting WATER_GAIN = new FloatSetting("waterGain", "Water volume", null, 0.8F, 0, 1, 0.05F, "");
		public static final FloatSetting WATER_GAIN_HF = new FloatSetting("waterGainHF", "Water brightness", null, 0.3F, 0, 1, 0.05F, "");
		public static final FloatSetting LAVA_GAIN = new FloatSetting("lavaGain", "Lava volume", null, 0.5F, 0, 1, 0.05F, "");
		public static final FloatSetting LAVA_GAIN_HF = new FloatSetting("lavaGainHF", "Lava brightness", null, 0.1F, 0, 1, 0.05F, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Sound Filters", "Reverb, muffling and underwater effects for game sounds")
			.keywords("sound", "reverb", "echo", "muffle", "underwater", "audio").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Blocks", DENSE_HARDNESS, DENSE_BLAST, SOFT_HARDNESS, SOFT_BLAST)
			.section("Reverb", REVERB, MAX_BLOCKS, CHECK_SKY, BASE_REVERB, REVERB_PERCENT, MIN_DECAY, REFLECTIONS_BASE, REFLECTIONS_MULTIPLIER,
				REFLECTIONS_DELAY, LATE_BASE, LATE_MULTIPLIER, LATE_DELAY, DENSITY, DIFFUSION, GAIN, GAIN_HF, DECAY_HF, AIR_ABSORPTION)
			.section("Obstruction", OBSTRUCTION, OBSTRUCTION_STEP, OBSTRUCTION_MAX).section("Liquid", LIQUID, WATER_GAIN, WATER_GAIN_HF, LAVA_GAIN,
				LAVA_GAIN_HF)
			.needs(Requirement.WORLD).note("Needs a sound device with OpenAL's effects extension (nearly every one has it).").build();
	}

	/** The batch above, for the registry. */
	public static List<ModuleDescriptor> all() {
		return List.of(Animations.DESCRIPTOR, KeybindSearch.DESCRIPTOR, DropPrevention.DESCRIPTOR, CustomF3.DESCRIPTOR, AdvancementsScreen.DESCRIPTOR, SoundFilters.DESCRIPTOR);
	}
}
