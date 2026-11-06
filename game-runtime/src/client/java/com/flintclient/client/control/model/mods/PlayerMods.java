package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.BoxStyle;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.FpsSizing;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IdKind;
import com.flintclient.client.control.model.IdSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.SettingSection;
import com.flintclient.client.control.model.TextSetting;

/** Movement, camera and combat feedback. Every one is client-side only: none changes what the server sees or allows. */
public final class PlayerMods {
	private PlayerMods() {
	}

	static OptionSetting holdToggle(String id, String label) {
		return new OptionSetting(id, label, null, 0, "hold", "Hold", "toggle", "Toggle");
	}

	public static final class ToggleSprint {
		public static final String ID = "toggle_sneak_sprint";
		public static final BoxStyle STYLE = new BoxStyle(110, 200, 13, FpsSizing.DYNAMIC, 0xFFFFFF, false);
		public static final BoolSetting SPRINT = new BoolSetting("toggleSprint", "Toggle sprint", "Tap the sprint key to keep sprinting", true);
		public static final BoolSetting SNEAK = new BoolSetting("toggleSneak", "Toggle sneak", "Tap the sneak key to keep sneaking", false);
		public static final BoolSetting HIDE_TEXT = new BoolSetting("hideText", "Hide text", "Keeps the toggles without the HUD line", false);
		public static final TextSetting SNEAK_HELD = new TextSetting("sneakHeld", "Sneaking held", null, "Sneaking (Key Held)", 40);
		public static final TextSetting SNEAK_TOGGLED = new TextSetting("sneakToggled", "Sneaking toggled", null, "Sneaking (Toggled)", 40);
		public static final TextSetting SPRINT_HELD = new TextSetting("sprintHeld", "Sprinting held", null, "Sprinting (Key Held)", 40);
		public static final TextSetting SPRINT_TOGGLED = new TextSetting("sprintToggled", "Sprinting toggled", null, "Sprinting (Toggled)", 40);
		public static final TextSetting SPRINT_VANILLA = new TextSetting("sprintVanilla", "Sprinting vanilla",
			"Sprinting by double-tapping forward", "Sprinting (Vanilla)", 40);
		public static final TextSetting FLYING = new TextSetting("flying", "Flying text", null, "Flying", 40);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Toggle Sneak/Sprint", "Toggle your sprint and sneak key")
			.keywords("sprint", "sneak", "toggle", "crouch").icon(IconId.KEYS).category(Category.COMBAT)
			.section("Toggle Sprint", SPRINT, SNEAK).section(STYLE.section("Style"))
			.section("Text Settings", HIDE_TEXT, SNEAK_HELD, SNEAK_TOGGLED, SPRINT_HELD, SPRINT_TOGGLED, SPRINT_VANILLA, FLYING)
			.section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.BOTTOM_LEFT, 4, 4))
			.note("Works on your movement keys only: the same as holding the key, nothing faster.").build();
	}

	/** Zoom: Zoomify (isXander, LGPL-3.0), every option with its name, range and default, and its secondary zoom and presets. */
	public static final class Zoom {
		public static final String ID = "zoom";
		private static final String[] TRANSITIONS = {"instant", "Instant", "linear", "Linear", "ease_in_sine", "Ease In Sine", "ease_out_sine",
			"Ease Out Sine", "ease_in_out_sine", "Ease In Out Sine", "ease_in_quad", "Ease In Quad", "ease_out_quad", "Ease Out Quad",
			"ease_in_out_quad", "Ease In Out Quad", "ease_in_cubic", "Ease In Cubic", "ease_out_cubic", "Ease Out Cubic", "ease_in_out_cubic",
			"Ease In Out Cubic", "ease_in_exp", "Ease In Exponential", "ease_out_exp", "Ease Out Exponential", "ease_in_out_exp",
			"Ease In Out Exponential"};
		public static final KeySetting KEY = new KeySetting("key", "Zoom key", null, "key.keyboard.c");
		public static final OptionSetting MODE = new OptionSetting("mode", "Zoom key behavior", "The behavior of the zoom key", 0, "hold", "Hold",
			"toggle", "Toggle");
		public static final IntSetting LEVEL = new IntSetting("level", "Initial zoom", "The zoom level when you first press the key", 4, 1, 10, 1,
			"×");
		public static final FloatSetting IN_TIME = new FloatSetting("zoomInTime", "Zoom in time", "How many seconds to complete zooming in", 1, 0.1F,
			5, 0.1F, " s");
		public static final FloatSetting OUT_TIME = new FloatSetting("zoomOutTime", "Zoom out time", "How many seconds to complete zooming out",
			0.5F, 0.1F, 5, 0.1F, " s");
		public static final OptionSetting IN_TRANSITION = new OptionSetting("zoomInTransition", "Zoom in transition",
			"The transition type used when zooming in", 12, TRANSITIONS);
		public static final OptionSetting OUT_TRANSITION = new OptionSetting("zoomOutTransition", "Zoom out transition",
			"The transition type used when zooming out", 12, TRANSITIONS);
		public static final BoolSetting AFFECT_HAND = new BoolSetting("affectHandFov", "Affect hand FOV",
			"Your hand and held item zoom with the world", true);
		public static final BoolSetting SCROLL = new BoolSetting("scroll", "Enable scroll zoom",
			"Zoom further in and out with the scroll wheel (not the hotbar)", true);
		public static final IntSetting SCROLL_STEPS = new IntSetting("scrollStepCount", "Scroll step count",
			"How many scroll steps in each direction; you can scroll to zoom in or out from the initial zoom", 10, 3, 20, 1, "");
		public static final FloatSetting PER_STEP = new FloatSetting("zoomPerStep", "Zoom per step",
			"The zoom multiplier applied per scroll step; 1.5× means each step zooms in 50% more", 1.5F, 1.1F, 2, 0.1F, "×");
		public static final IntSetting SCROLL_SMOOTHNESS = new IntSetting("scrollZoomSmoothness", "Scroll zoom smoothness",
			"How smooth, and how long, a scroll zoom takes; 0% is instant", 70, 0, 100, 1, "%");
		public static final BoolSetting RETAIN = new BoolSetting("retainZoomSteps", "Remember zoom steps",
			"Keep the scroll zoom amount between zooming in and out", false);
		public static final OptionSetting SPYGLASS = new OptionSetting("spyglassBehaviour", "Spyglass zoom behavior",
			"How the zoom and the spyglass's own zoom work together", 0, "combine", "Combine", "override", "Override",
			"only_zoom_while_holding", "Allow While Holding", "only_zoom_while_carrying", "Allow While Carrying");
		public static final OptionSetting SPYGLASS_OVERLAY = new OptionSetting("spyglassOverlayVisibility", "Spyglass overlay visibility",
			"When to show the spyglass overlay", 1, "never", "Never", "holding", "Holding", "carrying", "Carrying", "always", "Always");
		public static final OptionSetting SPYGLASS_SOUND = new OptionSetting("spyglassSoundBehaviour", "Spyglass sound behavior",
			"When to play the spyglass's zoom in and out sounds", 3, "never", "Never", "always", "Always", "only_spyglass",
			"Only Holding Spyglass", "with_overlay", "Match Overlay");
		public static final IntSetting RELATIVE_SENSITIVITY = new IntSetting("relativeSensitivity", "Relative sensitivity",
			"Lowers your mouse sensitivity with the zoom level; 0% is off", 100, 0, 150, 10, "%");
		public static final BoolSetting RELATIVE_BOBBING = new BoolSetting("relativeViewBobbing", "Relative view bobbing",
			"Make view bobbing less jarring when zoomed in", true);
		public static final IntSetting CINEMATIC = new IntSetting("cinematicCamera", "Cinematic camera",
			"How much the camera glides once moved while zoomed; 0% is off", 0, 0, 250, 10, "%");
		public static final KeySetting SECONDARY_KEY = new KeySetting("secondaryKey", "Secondary zoom key",
			"A separate toggle-only zoom with its own settings", "key.keyboard.f6");
		public static final IntSetting SECONDARY_AMOUNT = new IntSetting("secondaryZoomAmount", "Secondary zoom amount",
			"How many times to divide the field of view once the transition completes", 4, 2, 10, 1, "×");
		public static final FloatSetting SECONDARY_IN = new FloatSetting("secondaryZoomInTime", "Secondary zoom in time",
			"How many seconds to complete zooming in", 10, 6, 30, 2, " s");
		public static final FloatSetting SECONDARY_OUT = new FloatSetting("secondaryZoomOutTime", "Secondary zoom out time",
			"How many seconds to complete zooming out; 0 is instant", 1, 0, 5, 0.25F, " s");
		public static final BoolSetting SECONDARY_HIDE_HUD = new BoolSetting("secondaryHideHUDOnZoom", "Hide HUD on secondary zoom",
			"Stops drawing the HUD while zooming in, much like pressing F1", true);
		public static final OptionSetting PRESET = new OptionSetting("preset", "Apply preset",
			"Makes Zoom behave like Zoomify's default, OptiFine's or OK Zoomer's zoom (applied at once)", 0, "none", "Choose…", "default",
			"Default", "optifine", "OptiFine", "ok_zoomer", "OK Zoomer");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Zoom", "Zoom in and out of objects")
			.keywords("zoom", "optifine", "spyglass", "fov", "zoomify", "secondary").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("General", KEY, MODE, LEVEL, IN_TIME, OUT_TIME, IN_TRANSITION, OUT_TRANSITION, AFFECT_HAND)
			.section("Scroll", SCROLL, SCROLL_STEPS, PER_STEP, SCROLL_SMOOTHNESS, RETAIN)
			.section("Spyglass", SPYGLASS, SPYGLASS_OVERLAY, SPYGLASS_SOUND)
			.section("Controls", RELATIVE_SENSITIVITY, RELATIVE_BOBBING, CINEMATIC)
			.section("Secondary Zoom", SECONDARY_KEY, SECONDARY_AMOUNT, SECONDARY_IN, SECONDARY_OUT, SECONDARY_HIDE_HUD)
			.section("Presets", PRESET)
			.note("Zoomify's zoom: only your field of view changes while a key is used; your FOV setting is never touched. The secondary "
				+ "zoom is completely separate: toggle-only, slow, and it always uses the cinematic camera.").build();
	}

	public static final class Freelook {
		public static final String ID = "freelook";
		public static final KeySetting KEY = new KeySetting("key", "Perspective key", null, "key.keyboard.left.alt");
		public static final OptionSetting MODE = holdToggle("mode", "Mode");
		public static final BoolSetting INVERT_YAW = new BoolSetting("invertYaw", "Invert yaw (right and left)", false);
		public static final BoolSetting INVERT_PITCH = new BoolSetting("invertPitch", "Invert pitch (up and down)", false);
		public static final OptionSetting VIEW = new OptionSetting("startView", "Starting view mode", null, 0, "third", "Third", "front",
			"Forward");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Freelook", "Look around without turning")
			.keywords("perspective", "freelook", "camera", "look around").icon(IconId.COMPASS).category(Category.UTILITY)
			.section("Freelook", KEY, MODE, INVERT_YAW, INVERT_PITCH, VIEW)
			.note("Only the camera turns; you keep walking where you were facing. Servers may disallow it: respect their rules.").build();
	}

	/**
	 * Crosshair: Custom Crosshair Mod (CC0) with every option, its styles, its drawn crosshair and its editor key, plus
	 * Crosshair Indicator's (CC0) brackets while you aim at a player.
	 */
	public static final class Crosshair {
		public static final String ID = "crosshair";
		public static final OptionSetting TYPE = new OptionSetting("type", "Crosshair style", null, 1, "vanilla", "Vanilla", "cross", "Cross",
			"circle", "Circle", "square", "Square", "triangle", "Triangle", "arrow", "Arrow", "debug", "Debug", "drawn", "Drawn");
		public static final BoolSetting KEEP_DEBUG = new BoolSetting("keepDebug", "Vanilla crosshair in debug HUD",
			"Shows Minecraft's 3D crosshair while F3 is open", false);
		public static final OptionSetting DRAW = new OptionSetting("drawCrosshair", "Draw crosshair",
			"Opens the editor for the Drawn style: click or drag on the grid to set or clear pixels", 0, "closed", "Draw…", "open", "Opening");
		public static final KeySetting EDIT_KEY = new KeySetting("editKey", "Edit crosshair", "Opens these settings while you play",
			"key.keyboard.grave.accent");
		public static final RgbSetting COLOR = new RgbSetting("color", "Crosshair color", null, 0xFFFFFF);
		public static final IntSetting ALPHA = alpha("colorAlpha", "Crosshair opacity", 255);
		public static final BoolSetting INVERT = new BoolSetting("invertColor", "Enable adaptive color",
			"Contrasts with what is behind it, as Minecraft's crosshair does", true);
		public static final IntSetting WIDTH = new IntSetting("width", "Width", null, 3, 0, 50, 1, "");
		public static final IntSetting HEIGHT = new IntSetting("height", "Height", null, 3, 0, 50, 1, "");
		public static final IntSetting GAP = new IntSetting("gap", "Gap", null, 2, 0, 50, 1, "");
		public static final IntSetting THICKNESS = new IntSetting("thickness", "Thickness", null, 1, 1, 10, 1, "");
		public static final IntSetting ROTATION = new IntSetting("rotation", "Rotation", null, 0, 0, 360, 1, "°");
		public static final IntSetting SCALE = new IntSetting("scale", "Scale", null, 100, 25, 500, 1, "%");
		public static final IntSetting OFFSET_X = new IntSetting("offsetX", "Offset (X)", null, 0, -500, 500, 1, "");
		public static final IntSetting OFFSET_Y = new IntSetting("offsetY", "Offset (Y)", null, 0, -500, 500, 1, "");
		public static final BoolSetting VISIBLE_DEFAULT = new BoolSetting("visibleDefault", "Visible by default", true);
		public static final BoolSetting VISIBLE_HIDDEN_GUI = new BoolSetting("visibleHiddenGui", "Visible when GUI hidden (F1)", true);
		public static final BoolSetting VISIBLE_DEBUG = new BoolSetting("visibleDebug", "Visible in debug HUD", true);
		public static final BoolSetting THIRD_PERSON = new BoolSetting("thirdPerson", "Visible in third person mode", false);
		public static final BoolSetting VISIBLE_SPECTATOR = new BoolSetting("visibleSpectator", "Visible in spectator mode", true);
		public static final BoolSetting VISIBLE_RANGED = new BoolSetting("visibleRangedWeapon", "Visible holding ranged weapon",
			"A bow, crossbow or trident", true);
		public static final BoolSetting VISIBLE_THROWABLE = new BoolSetting("visibleThrowableItem", "Visible holding throwable item",
			"An ender pearl or an eye of ender", true);
		public static final BoolSetting VISIBLE_SPYGLASS = new BoolSetting("visibleSpyglass", "Visible when using spyglass", false);
		public static final BoolSetting OUTLINE = new BoolSetting("outline", "Enable outline", "A thin line around the Cross and Square styles",
			false);
		public static final RgbSetting OUTLINE_COLOR = new RgbSetting("outlineColor", "Outline color", null, 0x202020);
		public static final IntSetting OUTLINE_ALPHA = alpha("outlineAlpha", "Outline opacity", 255);
		public static final BoolSetting DOT = new BoolSetting("dot", "Enable dot", "A small ring in the middle", false);
		public static final RgbSetting DOT_COLOR = new RgbSetting("dotColor", "Dot color", null, 0xFFFFFF);
		public static final IntSetting DOT_ALPHA = alpha("dotAlpha", "Dot opacity", 255);
		public static final BoolSetting DYNAMIC_ATTACK = new BoolSetting("dynamicAttack", "Enable dynamic when attacking",
			"The crosshair opens up while your attack recharges", true);
		public static final BoolSetting DYNAMIC_BOW = new BoolSetting("dynamicBow", "Enable dynamic pull progress",
			"The crosshair closes in as you draw a bow, crossbow or trident", true);
		public static final BoolSetting HOSTILE = new BoolSetting("highlightHostile", "Hostile mob highlighting", true);
		public static final RgbSetting HOSTILE_COLOR = new RgbSetting("hostileColor", "Hostile mob color", null, 0xDC2828);
		public static final IntSetting HOSTILE_ALPHA = alpha("hostileAlpha", "Hostile mob opacity", 255);
		public static final BoolSetting PASSIVE = new BoolSetting("highlightPassive", "Passive mob highlighting", true);
		public static final RgbSetting PASSIVE_COLOR = new RgbSetting("passiveColor", "Passive mob color", null, 0x28E628);
		public static final IntSetting PASSIVE_ALPHA = alpha("passiveAlpha", "Passive mob opacity", 255);
		public static final BoolSetting PLAYERS = new BoolSetting("highlightPlayers", "Player highlighting", true);
		public static final RgbSetting PLAYERS_COLOR = new RgbSetting("playersColor", "Player color", null, 0x3C3CF0);
		public static final IntSetting PLAYERS_ALPHA = alpha("playersAlpha", "Player opacity", 255);
		public static final BoolSetting ITEM_COOLDOWN = new BoolSetting("itemCooldown", "Enable item cooldown indicator",
			"A ring around the crosshair while ender pearls or chorus fruit cool down", true);
		public static final RgbSetting COOLDOWN_COLOR = new RgbSetting("cooldownColor", "Indicator color", null, 0xFFFFFF);
		public static final IntSetting COOLDOWN_ALPHA = alpha("cooldownAlpha", "Indicator opacity", 80);
		public static final BoolSetting RAINBOW = new BoolSetting("rainbow", "Enable rainbow", false);
		public static final IntSetting RAINBOW_SPEED = new IntSetting("rainbowSpeed", "Rainbow speed", null, 500, 0, 1000, 1, "");
		public static final BoolSetting TOOL_DAMAGE = new BoolSetting("toolDamage", "Enable tool damage indicator",
			"The uses left beside the crosshair once your tool has 10 or fewer", true);
		public static final BoolSetting PROJECTILES = new BoolSetting("projectiles", "Enable projectiles indicator",
			"The ammunition your bow or crossbow would use, and how much", true);
		public static final BoolSetting PLAYER_INDICATOR = new BoolSetting("playerIndicator", "Crosshair indicator",
			"Brackets around the crosshair while you can swing at a player's hitbox (Crosshair Indicator)", true);

		static IntSetting alpha(String id, String label, int value) {
			return new IntSetting(id, label, null, value, 0, 255, 1, "");
		}

		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Crosshair", "Customize your crosshair")
			.keywords("crosshair", "aim", "reticle", "dot", "custom crosshair", "indicator").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General Settings", TYPE, KEEP_DEBUG, DRAW, EDIT_KEY)
			.section("Shape Settings", COLOR, ALPHA, INVERT, WIDTH, HEIGHT, GAP, THICKNESS, ROTATION, SCALE, OFFSET_X, OFFSET_Y)
			.section("Visibility Settings", VISIBLE_DEFAULT, VISIBLE_HIDDEN_GUI, VISIBLE_DEBUG, THIRD_PERSON, VISIBLE_SPECTATOR, VISIBLE_RANGED,
				VISIBLE_THROWABLE, VISIBLE_SPYGLASS)
			.section("Outline Settings", OUTLINE, OUTLINE_COLOR, OUTLINE_ALPHA).section("Dot Settings", DOT, DOT_COLOR, DOT_ALPHA)
			.section("Dynamic Crosshair Settings", DYNAMIC_ATTACK, DYNAMIC_BOW)
			.section("Highlight Settings", HOSTILE, HOSTILE_COLOR, HOSTILE_ALPHA, PASSIVE, PASSIVE_COLOR, PASSIVE_ALPHA, PLAYERS, PLAYERS_COLOR,
				PLAYERS_ALPHA)
			.section("Item Cooldown Settings", ITEM_COOLDOWN, COOLDOWN_COLOR, COOLDOWN_ALPHA).section("Rainbow Settings", RAINBOW, RAINBOW_SPEED)
			.section("Indicator Settings", TOOL_DAMAGE, PROJECTILES, PLAYER_INDICATOR)
			.note("Custom Crosshair Mod's crosshair, drawn over the whole HUD while no screen is open, in place of Minecraft's (its attack "
				+ "indicator is drawn with it). Highlighting colors it by what you aim at: players, then hostile and neutral mobs, then "
				+ "animals. The drawn crosshair is kept in config/flintclient/crosshair-drawn.png.").build();
	}

	public static final class AttackIndicator {
		public static final String ID = "attack_indicator";
		public static final BoolSetting ACCURATE = new BoolSetting("accurateCooldown", "Accurate cooldown",
			"Your cooldown restarts on every swing the server counts (placing, using and dropping items, mining), as Accurate Attack "
				+ "Indicator does", true);
		public static final OptionSetting SHAPE = new OptionSetting("shape", "Display mode",
			"Default is Minecraft's own indicator, where its Attack Indicator option is Crosshair", 0, "default", "Default", "ring", "Ring", "bar",
			"Bar");
		public static final IntSetting SCALE = new IntSetting("scale", "Scale", null, 100, 50, 300, 25, "%");
		public static final IntSetting X_OFFSET = new IntSetting("xOffset", "X offset", null, 0, -40, 40, 1, "");
		public static final IntSetting Y_OFFSET = new IntSetting("yOffset", "Y offset", null, 0, -40, 40, 1, "");
		public static final BoolSetting FULL_ONLY_TARGET = new BoolSetting("hideWhenReady", "Hide when charged",
			"On: as Minecraft does, shown full only while you aim at something you can hit. Off: always shown", true);
		public static final BoolSetting DYNAMIC = new BoolSetting("dynamicColor", "Dynamic color", "Red when empty to green when full", false);
		public static final RgbSetting COLOR = new RgbSetting("color", "Color", null, 0xFFFFFF);
		public static final BoolSetting SWORDS = new BoolSetting("swords", "Swords", true);
		public static final BoolSetting AXES = new BoolSetting("axes", "Axes", true);
		public static final BoolSetting TRIDENTS = new BoolSetting("tridents", "Tridents", true);
		public static final BoolSetting SPEARS = new BoolSetting("spears", "Spears", true);
		public static final BoolSetting PICKAXES = new BoolSetting("pickaxes", "Pickaxes", true);
		public static final BoolSetting SHOVELS = new BoolSetting("shovels", "Shovels", true);
		public static final BoolSetting OTHER = new BoolSetting("nonWeapons", "Non-weapon cooldowns", "Empty hand, hoes and anything else", true);
		public static final IdSetting SOUND = new IdSetting("cooldownSound", "Cooldown sound", "Plays when your attack is fully charged again",
			IdKind.SOUND, IdSetting.NONE);
		public static final IntSetting SOUND_VOLUME = new IntSetting("cooldownSoundVolume", "Volume", null, 60, 5, 100, 5, "%");
		public static final BoolSetting SLEEP = new BoolSetting("sleep", "Sleep progress", "Until the night can be skipped", false);
		public static final BoolSetting BREAKING = new BoolSetting("blockBreaking", "Block breaking", "How far the block you mine is", false);
		public static final BoolSetting PEARL = new BoolSetting("enderPearl", "Ender pearl", "Its cooldown, while you hold one", false);
		public static final BoolSetting CHORUS = new BoolSetting("chorusFruit", "Chorus fruit", "Its cooldown, while you hold one", false);
		public static final BoolSetting BOWS = new BoolSetting("bows", "Bows", "How far you've drawn a bow or crossbow", false);
		public static final BoolSetting SHIELDS = new BoolSetting("shields", "Shields", "Until a raised shield blocks", false);
		public static final BoolSetting CONSUMABLES = new BoolSetting("consumables", "Consumables", "Eating and drinking", false);
		public static final BoolSetting WIND_CHARGE = new BoolSetting("windCharge", "Wind charge", "Its cooldown, while you hold one", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Attack Indicator", "Show your attack cooldown your way")
			.keywords("attack", "cooldown", "indicator", "pvp", "bow").icon(IconId.GAUGE).category(Category.COMBAT)
			.section("General", ACCURATE, SHAPE, SCALE, X_OFFSET, Y_OFFSET, FULL_ONLY_TARGET, DYNAMIC, COLOR)
			.section("Attack Cooldowns", SWORDS, AXES, TRIDENTS, SPEARS, PICKAXES, SHOVELS, OTHER, SOUND, SOUND_VOLUME)
			.section("Custom Indicators", SLEEP, BREAKING, PEARL, CHORUS, BOWS, SHIELDS, CONSUMABLES, WIND_CHARGE)
			.note("Under the crosshair, from what Minecraft already knows: your attack strength, how long you've used an item, an item's "
				+ "cooldown, how far a block is mined. One shows at a time: something you're doing first, then your attack. Accurate "
				+ "cooldown is Accurate Attack Indicator's fix for MC-118740: the server restarts your attack cooldown on every swing, "
				+ "Minecraft's client only when you attack, so with it your indicator (on the crosshair or the hotbar) follows the server.")
			.build();
	}

	public static final class HitColor {
		public static final String ID = "hit_color";
		public static final BoolSetting HIT_INDICATOR = new BoolSetting("hitIndicator", "Hit indicator", "A crescent around the crosshair pointing "
			+ "where damage came from", true);
		public static final RgbSetting HIT_COLOR = new RgbSetting("hitColor", "Hit indicator color", null, 0xFF0000);
		public static final IntSetting HIT_DURATION = new IntSetting("hitDuration", "Hit duration", null, 60, 10, 120, 5, " ticks");
		public static final IntSetting HIT_SIZE = new IntSetting("hitSize", "Hit size", null, 100, 50, 200, 10, "%");
		public static final BoolSetting MARKER = new BoolSetting("marker", "Hit marker", "An X on the crosshair when your hit lands", true);
		public static final IntSetting MARKER_DURATION = new IntSetting("markerDuration", "Marker duration", null, 10, 4, 40, 2, " ticks");
		public static final IntSetting MARKER_SIZE = new IntSetting("markerSize", "Marker size", null, 100, 50, 200, 10, "%");
		public static final RgbSetting MARKER_COLOR = new RgbSetting("markerColor", "Marker color", null, 0xFFFFFF);
		public static final RgbSetting KILL_COLOR = new RgbSetting("killColor", "Kill color", "When the target died", 0xFF0000);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Hit Color", "Add indicators where you receive damage")
			.keywords("hit", "damage", "indicator", "marker").icon(IconId.GAUGE).category(Category.COMBAT)
			.section("Hit Indicator", HIT_INDICATOR, HIT_COLOR, HIT_DURATION, HIT_SIZE)
			.section("Marker Indicator", MARKER, MARKER_DURATION, MARKER_SIZE, MARKER_COLOR, KILL_COLOR).needs(Requirement.WORLD)
			.note("The direction is the one the server sends with your hurt animation. A marker shows when the entity you hit flashes hurt.")
			.build();
	}

	/**
	 * Hitbox: combat-hitbox (Apache-2.0) on Minecraft's own F3+B hitboxes, every option with its name and default, plus
	 * Flint's kinds of entity, reach colors and distance limit.
	 */
	public static final class Hitbox {
		public static final String ID = "hitbox";
		public static final RgbSetting MAIN_COLOR = new RgbSetting("mainColor", "Base color", "The hitbox's color, for every kind whose own color "
			+ "is left white", 0xFFFFFF);
		public static final IntSetting MAIN_OPACITY = opacity("mainOpacity", "Base opacity");
		public static final BoolSetting EYE_LEVEL = new BoolSetting("showEyeLevel", "Render eye height",
			"The red line at the entity's eye height", true);
		public static final RgbSetting EYE_COLOR = new RgbSetting("eyeLevelColor", "Eye color", "The hitbox eye height color", 0xFF0000);
		public static final IntSetting EYE_OPACITY = opacity("eyeOpacity", "Eye opacity");
		public static final BoolSetting LOOK = new BoolSetting("look", "Render look direction",
			"The blue line showing the direction the entity is facing", true);
		public static final BoolSetting LOOK_LINE = new BoolSetting("lineLookDir", "Line look direction",
			"The look direction as a line instead of Minecraft's arrow", true);
		public static final RgbSetting LOOK_COLOR = new RgbSetting("lookColor", "Look direction color", "The hitbox's look direction color", 0x0000FF);
		public static final IntSetting LOOK_OPACITY = opacity("lookOpacity", "Look direction opacity");
		public static final BoolSetting TARGET = new BoolSetting("targetColorEnabled", "Target hitbox color",
			"Another color while your crosshair is on the entity", true);
		public static final RgbSetting TARGET_COLOR = new RgbSetting("targetColor", "Target color", "The hitbox color when the entity is targeted",
			0xFF0000);
		public static final IntSetting TARGET_OPACITY = opacity("targetOpacity", "Target opacity");
		public static final BoolSetting DAMAGED = new BoolSetting("damaged", "Hitbox hurt", "Another color while it flashes hurt", false);
		public static final RgbSetting DAMAGED_COLOR = new RgbSetting("damagedColor", "Hurt color", "The hitbox color when the entity is on its "
			+ "hurt tick", 0xFF00FF);
		public static final IntSetting DAMAGED_OPACITY = opacity("damagedOpacity", "Hurt opacity");
		public static final BoolSetting HIDE_ARROWS = new BoolSetting("hideArrows", "Hide stuck arrows",
			"Removes arrows and bee stingers stuck in players, visually", false);
		public static final BoolSetting SKIP_FIREWORKS = new BoolSetting("skipFireworks", "Skip fireworks", "No hitboxes for firework rockets",
			false);
		public static final FloatSetting WIDTH = new FloatSetting("width", "Line width 1", "The width of the hitbox lines", 2.5F, 0, 25, 0.1F, "");
		public static final FloatSetting DISTANCE_2 = new FloatSetting("distanceForWidth2", "Distance for width 2",
			"Beyond this distance from you, Line width 2 is used", 32, 0, 256, 1, " blocks");
		public static final FloatSetting WIDTH_2 = new FloatSetting("width2", "Line width 2", "The width of the hitbox lines beyond that distance",
			2.5F, 0, 25, 0.1F, "");
		public static final BoolSetting OUTLINE = new BoolSetting("outline", "Outline", "A wider line in another color around each hitbox", false);
		public static final RgbSetting OUTLINE_COLOR = new RgbSetting("outlineColor", "Outline color", "The hitbox's outline color", 0x000000);
		public static final IntSetting OUTLINE_OPACITY = opacity("outlineOpacity", "Outline opacity");
		public static final FloatSetting OUTLINE_SIZE = new FloatSetting("outlineMultiplier", "Outline size multiplier",
			"How much the hitbox's line width is multiplied by for the outline", 2, 1.1F, 10, 0.1F, "×");
		public static final KeySetting TOGGLE_KEY = new KeySetting("toggleKey", "Toggle hitboxes", "Shows or hides every hitbox, as F3+B does",
			KeySetting.NONE);
		public static final BoolSetting HITTABLE = new BoolSetting("hittable", "Color in reach", "Another color while it is within your reach",
			false);
		public static final RgbSetting HITTABLE_COLOR = new RgbSetting("hittableColor", "In reach color", null, 0x55FF55);
		public static final BoolSetting ONLY_HITTABLE = new BoolSetting("onlyHittable", "Only in reach", "Hides a box until it is within your reach",
			false);
		public static final BoolSetting LIMIT = new BoolSetting("limitDistance", "Max showable distance", "Only entities within the distance below",
			false);
		public static final IntSetting DISTANCE = new IntSetting("distance", "Distance", null, 64, 4, 256, 4, " blocks");

		static IntSetting opacity(String id, String label) {
			return new IntSetting(id, label, null, 100, 0, 100, 5, "%");
		}

		/** One kind of entity: shown or not, and its color (left white, the base color is used). */
		public record Group(String key, String title, BoolSetting enabled, RgbSetting color) {
		}

		static Group group(String key, String title, String label, boolean on) {
			return new Group(key, title, new BoolSetting(key + ".enabled", label, on), new RgbSetting(key + ".color", "Color", null, 0xFFFFFF));
		}

		/** In the reference order; the version layer sorts each entity into one of these by its kind. */
		public static final Group PLAYERS = group("players", "Player Hitboxes", "Player hitboxes", true);
		public static final Group MONSTERS = group("monsters", "Monster Hitboxes", "Monster hitboxes", true);
		public static final Group PASSIVE = group("passive", "Animal Hitboxes", "Animal hitboxes", true);
		public static final Group PROJECTILES = group("projectiles", "Projectile Hitboxes", "Projectile hitboxes", true);
		public static final Group ITEMS = group("items", "Item Drop Hitboxes", "Item drop hitboxes", true);
		public static final Group OTHER = group("other", "Other Hitboxes", "Other hitboxes", true);
		public static final java.util.List<Group> GROUPS = java.util.List.of(PLAYERS, MONSTERS, PASSIVE, PROJECTILES, ITEMS, OTHER);

		public static final ModuleDescriptor DESCRIPTOR = build();

		private static ModuleDescriptor build() {
			ModuleDescriptor.Builder b = ModuleDescriptor.builder(ID, "Hitbox", "Customize the hitboxes of entities around you")
				.keywords("hitbox", "f3+b", "bounding box", "entities", "combat hitbox").icon(IconId.GAUGE).category(Category.COMBAT)
				.section("General", MAIN_COLOR, MAIN_OPACITY, EYE_LEVEL, EYE_COLOR, EYE_OPACITY, TOGGLE_KEY)
				.section("Behavior", LOOK, LOOK_LINE, LOOK_COLOR, LOOK_OPACITY, TARGET, TARGET_COLOR, TARGET_OPACITY, DAMAGED, DAMAGED_COLOR,
					DAMAGED_OPACITY, HIDE_ARROWS, SKIP_FIREWORKS)
				.section("Line Width", WIDTH, DISTANCE_2, WIDTH_2).section("Outline", OUTLINE, OUTLINE_COLOR, OUTLINE_OPACITY, OUTLINE_SIZE);
			for (Group group : GROUPS) {
				b.section(group.title(), group.enabled(), group.color());
			}
			return b.advanced("Advanced", HITTABLE, HITTABLE_COLOR, ONLY_HITTABLE, LIMIT, DISTANCE)
				.needs(Requirement.WORLD)
				.note("combat-hitbox's look on Minecraft's own hitboxes: they show while F3+B (or the toggle key) has them on, for the "
					+ "entities Minecraft would outline, with its eye line, seat and position marks. Colors: hurt, then target, then in "
					+ "reach, then the entity's kind. It never changes a hitbox or your reach. Projectiles are arrows, tridents, "
					+ "snowballs, fireballs and the like; other kinds are experience orbs, item frames, boats and minecarts. Hide stuck "
					+ "arrows works while the module is on, hitboxes shown or not.").build();
		}
	}

	/**
	 * Tier Tagger, Flint's port of the TierTagger mod: a player's PvP tier (from MCTiers or SubTiers) before their name on
	 * nametags, in the player list and on servers' text-display nametags; their highest tier when asked; a key to cycle the
	 * gamemode; a player search. Looked up only while it's on.
	 */
	public static final class TierTagger {
		public static final String ID = "tier_tagger";
		public static final OptionSetting LIST = new OptionSetting("tierList", "Tier list", "Whose tiers are shown", 0, "mctiers", "MCTiers",
			"subtiers", "SubTiers");
		public static final OptionSetting MODE = new OptionSetting("gameMode", "Gamemode", "The MCTiers gamemode whose tier shows", 0,
			"vanilla", "Crystal", "uhc", "UHC", "pot", "Pot", "nethop", "NethOP", "smp", "SMP", "sword", "Sword", "axe", "Axe", "mace", "Mace");
		public static final OptionSetting SUB_MODE = new OptionSetting("subtierGameMode", "Gamemode", "The SubTiers gamemode whose tier shows",
			0, "minecart", "Minecart", "dia_crystal", "Diamond Vanilla", "debuff", "DeBuff", "elytra", "Elytra", "speed", "Speed", "creeper",
			"Creeper", "manhunt", "Manhunt", "dia_smp", "Diamond SMP", "bow", "Bow", "bed", "Bed", "og_vanilla", "OG Vanilla", "trident", "Trident");
		public static final OptionSetting HIGHEST = new OptionSetting("showHighestTier", "Show highest tier",
			"Never; when someone isn't ranked in this gamemode; or always", 1, "never", "Never", "not_found", "When not ranked", "always",
			"Always");
		public static final KeySetting CYCLE = new KeySetting("cycleGamemode", "Cycle gamemode", "The next gamemode of the tier list", KeySetting.NONE);
		public static final OptionSetting DISPLAY = new OptionSetting("showType", "Display type", null, 0, "icon_text", "Icon and tier", "icons",
			"Icon", "text", "Tier");
		public static final BoolSetting NAMETAG = new BoolSetting("renderInNametag", "Show on nametags", true);
		public static final BoolSetting PLAYER_LIST = new BoolSetting("showInTablist", "Show in player list", true);
		public static final BoolSetting RETIRED = new BoolSetting("showRetired", "Show retired tiers", "A retired player's peak, as RHT2", true);
		/** Tier colours, TierTagger's own; HT1 is the highest. */
		public static final RgbSetting[] COLORS = {
			new RgbSetting("colorHT1", "HT1", null, 0xE8BA3A), new RgbSetting("colorLT1", "LT1", null, 0xD5B355),
			new RgbSetting("colorHT2", "HT2", null, 0xC4D3E7), new RgbSetting("colorLT2", "LT2", null, 0xA0A7B2),
			new RgbSetting("colorHT3", "HT3", null, 0xF89F5A), new RgbSetting("colorLT3", "LT3", null, 0xC67B42),
			new RgbSetting("colorHT4", "HT4", null, 0x81749A), new RgbSetting("colorLT4", "LT4", null, 0x655B79),
			new RgbSetting("colorHT5", "HT5", null, 0x8F82A8), new RgbSetting("colorLT5", "LT5", null, 0x655B79)};
		public static final RgbSetting RETIRED_COLOR = new RgbSetting("retiredColor", "Retired", null, 0xA2D6FF);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Tier Tagger", "Shows players' PvP tiers by their names")
			.keywords("tier", "tiers", "pvp", "rank", "mctiers", "subtiers", "nametag").icon(IconId.SHIELD).category(Category.COMBAT)
			.section("General", LIST, MODE, SUB_MODE, HIGHEST, CYCLE, DISPLAY, NAMETAG, PLAYER_LIST, RETIRED)
			.section("Color", COLORS[0], COLORS[1], COLORS[2], COLORS[3], COLORS[4], COLORS[5], COLORS[6], COLORS[7], COLORS[8], COLORS[9],
				RETIRED_COLOR)
			.note("While it's on, the players you see are looked up on the tier list you chose, by their UUID (online-mode players "
				+ "only), once each. Based on TierTagger by MCTiers (MPL-2.0).").build();
	}

	public static final class DamageTint {
		public static final String ID = "damage_tint";
		public static final RgbSetting COLOR = new RgbSetting("color", "Tint color", null, 0xC62828);
		public static final IntSetting INTENSITY = new IntSetting("intensity", "Intensity", null, 50, 10, 100, 5, "%");
		public static final IntSetting BELOW = new IntSetting("below", "Show below", "Health, in hearts, under which the edges tint", 5, 1, 10, 1,
			" ♥");
		public static final BoolSetting ON_HURT = new BoolSetting("onHurt", "Flash when hurt", "A short tint each time you take damage", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Damage Tint", "Tint the screen edges when your health is low")
			.keywords("health", "low", "vignette", "damage").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Tint", COLOR, INTENSITY, BELOW, ON_HURT).needs(Requirement.WORLD).build();
	}

	public static final class KillSounds {
		public static final String ID = "kill_sounds";
		static final String ORB = "minecraft:entity.experience_orb.pickup";
		public static final IdSetting PLAYER = new IdSetting("playerSound", "Player kill", "When the one you killed is a player",
			IdKind.SOUND, "minecraft:entity.player.levelup");
		public static final IntSetting PLAYER_VOLUME = volume("playerSoundVolume");
		public static final IdSetting MELEE = new IdSetting("meleeSound", "Melee kill", "Your last hit was with your hand or a weapon",
			IdKind.SOUND, ORB);
		public static final IntSetting MELEE_VOLUME = volume("meleeSoundVolume");
		public static final IdSetting BOW = new IdSetting("bowSound", "Bow kill", "Your last hit was an arrow (bow or crossbow)", IdKind.SOUND,
			ORB);
		public static final IntSetting BOW_VOLUME = volume("bowSoundVolume");
		public static final IdSetting ROD = new IdSetting("rodSound", "Rod kill", "Your last hit was your fishing rod's hook", IdKind.SOUND,
			ORB);
		public static final IntSetting ROD_VOLUME = volume("rodSoundVolume");
		public static final IdSetting THROWABLE = new IdSetting("throwableSound", "Throwable kill",
			"Your last hit was something thrown (a snowball, an egg, a trident...)", IdKind.SOUND, ORB);
		public static final IntSetting THROWABLE_VOLUME = volume("throwableSoundVolume");
		public static final BoolSetting PLAYERS_ONLY = new BoolSetting("playersOnly", "Players only", "No sound for mobs", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Kill Sounds", "Play a sound when you get a kill")
			.keywords("kill", "sound", "pvp", "ding").icon(IconId.GAUGE).category(Category.COMBAT)
			.section("Player Kill", PLAYER, PLAYER_VOLUME).section("Melee Kill", MELEE, MELEE_VOLUME).section("Bow Kill", BOW, BOW_VOLUME)
			.section("Rod Kill", ROD, ROD_VOLUME).section("Throwable Kill", THROWABLE, THROWABLE_VOLUME).section("General", PLAYERS_ONLY)
			.needs(Requirement.WORLD)
			.note("Plays when something you hurt in the last 3 seconds dies, as the server reports it; the kind of kill is what dealt your "
				+ "last hit. A player kill uses its own sound (or, set to None, the one for how you got it). Only you hear it. A Bed Wars win "
				+ "sound isn't offered: no game tells the client reliably that you won.").build();

		static IntSetting volume(String id) {
			return new IntSetting(id, "Volume", null, 80, 5, 100, 5, "%");
		}
	}

	/** BetterHurtCam (uku, MIT): its four options and three keys, with its names and defaults. */
	public static final class HurtCam {
		public static final String ID = "hurt_cam";
		public static final BoolSetting ENABLED = new BoolSetting("hurtcamEnabled", "Hurtcam enabled",
			"Off: no hurt tilt at all, and no death roll", true);
		public static final FloatSetting MULTIPLIER = new FloatSetting("multiplier", "Hurtcam multiplier",
			"Times every turn of the hurt tilt and the death roll; 1.00 is Minecraft's", 0.3F, 0, 3, 0.01F, "");
		public static final BoolSetting HEART_BLINK = new BoolSetting("heartBlink", "Health bar blinking",
			"Your hearts flash when you're hurt or healed", true);
		public static final OptionSetting TYPE = new OptionSetting("type", "Hurtcam type",
			"Old: 1.19.3 and lower, no direction change. Yaw-based: the tilt direction reflects the damage source", 1, "old", "Old", "yaw_based",
			"Yaw-based");
		public static final KeySetting TOGGLE = new KeySetting("toggleKey", "Toggle HurtCam", "Turns Hurtcam enabled on and off", "key.keyboard.f8");
		public static final KeySetting PLUS = new KeySetting("plusKey", "Increase HurtCam strength", "Adds 0.1 to the multiplier", "key.keyboard.f7");
		public static final KeySetting MINUS = new KeySetting("minusKey", "Decrease HurtCam strength", "Takes 0.1 off the multiplier",
			"key.keyboard.f6");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Hurt Cam", "Tone down the camera shake when you're hurt")
			.keywords("hurt", "camera", "shake", "tilt", "betterhurtcam").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", ENABLED, MULTIPLIER, HEART_BLINK, TYPE).section("Keybinds", TOGGLE, PLUS, MINUS)
			.note("BetterHurtCam's behaviour: the multiplier scales all four turns of Minecraft's hurt camera (the death roll, the tilt and "
				+ "its direction); Minecraft's own Damage Tilt slider still applies on top. The keys work while you play.").build();
	}

	/** Health Indicators: PlayerHealthIndicators' hearts above other players, with its stacking, offset and keys. */
	public static final class HealthIndicators {
		public static final String ID = "health_indicators";
		public static final BoolSetting STACKING = new BoolSetting("heartStackingEnabled", "Heart stacking",
			"Ten hearts to a row, the rows stacked; off: one long row", true);
		public static final IntSetting OFFSET = new IntSetting("heartOffset", "Heart offset", "How far above the name the hearts sit", 2, -100, 100, 1,
			"");
		public static final KeySetting STACKING_KEY = new KeySetting("heartStackingKey", "Toggle heart stacking", null, KeySetting.NONE);
		public static final KeySetting INCREASE_KEY = new KeySetting("increaseHeartOffsetKey", "Increase heart offset", null, KeySetting.NONE);
		public static final KeySetting DECREASE_KEY = new KeySetting("decreaseHeartOffsetKey", "Decrease heart offset", null, KeySetting.NONE);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Health Indicators", "Displays a health bar above players")
			.keywords("health", "hearts", "players", "hp", "indicator").icon(IconId.SHIELD).category(Category.COMBAT)
			.section("General", STACKING, OFFSET).section("Keys", STACKING_KEY, INCREASE_KEY, DECREASE_KEY).needs(Requirement.WORLD)
			.note("Other players' health and absorption as the server sends them, as Minecraft's own hearts, up to 64 blocks away. Use the "
				+ "Toggle mod key to switch them on and off from in game.").build();
	}
}
