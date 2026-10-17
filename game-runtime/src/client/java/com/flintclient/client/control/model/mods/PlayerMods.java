package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.BoxStyle;
import com.flintclient.client.control.model.Category;
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
		public static final BoxStyle STYLE = new BoxStyle(110, 200, 13, FpsSizing.DYNAMIC, 0xFFFFFF, true);
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

	public static final class Zoom {
		public static final String ID = "zoom";
		public static final KeySetting KEY = new KeySetting("key", "Zoom key", null, "key.keyboard.c");
		public static final OptionSetting MODE = holdToggle("mode", "Zoom mode");
		public static final IntSetting LEVEL = new IntSetting("level", "Start zoom", "How many times closer", 4, 2, 30, 1, "×");
		public static final BoolSetting SCROLL = new BoolSetting("scroll", "Scroll zoom", "The mouse wheel zooms while zooming (not the hotbar)",
			true);
		public static final BoolSetting SMOOTH = new BoolSetting("smooth", "Smooth zoom", "Eases in and out", true);
		public static final OptionSetting EASING = new OptionSetting("easing", "Smooth zoom easing", null, 3, "out_exponential", "Out Exponential",
			"in_out_quadratic", "In Out Quadratic", "in_out_circular", "In Out Circular", "out_quintic", "Out Quintic");
		public static final IntSetting DURATION = new IntSetting("durationMs", "Smooth duration", null, 160, 60, 400, 20, " ms");
		public static final BoolSetting LOWER_SENSITIVITY = new BoolSetting("dynamicSensitivity", "Dynamic sensitivity",
			"Lowers mouse sensitivity with the zoom, so aiming feels the same", true);
		public static final BoolSetting CINEMATIC = new BoolSetting("cinematic", "Smooth movement", "Minecraft's cinematic camera while zooming",
			false);
		public static final OptionSetting SPYGLASS = new OptionSetting("spyglass", "Needs a spyglass", "Where it must be to zoom", 0,
			"none", "None", "inventory", "Inventory", "hotbar", "Hotbar", "hand", "Hand");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Zoom", "Zoom in and out of objects")
			.keywords("zoom", "optifine", "spyglass", "fov").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("General", KEY, MODE, LEVEL, LOWER_SENSITIVITY).section("Scroll", SCROLL)
			.section("Smooth", SMOOTH, EASING, DURATION, CINEMATIC).section("Spyglass", SPYGLASS)
			.note("Changes only your field of view while the key is used; your FOV setting is never touched.").build();
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

	public static final class Crosshair {
		public static final String ID = "crosshair";
		public static final OptionSetting TYPE = new OptionSetting("type", "Type", null, 1, "default", "Default", "cross", "Cross", "corners",
			"Cross w/ corner", "circle", "Circle", "square", "Square", "dot", "Dot", "arrow", "Arrow");
		public static final BoolSetting IGNORE_SCALE = new BoolSetting("ignoreGuiScale", "Ignore GUI scale", "Same size in pixels at any GUI scale",
			false);
		public static final IntSetting SIZE = new IntSetting("size", "Size", null, 5, 1, 20, 1, "");
		public static final IntSetting GAP = new IntSetting("gap", "Gap", null, 2, 0, 10, 1, "");
		public static final IntSetting THICKNESS = new IntSetting("thickness", "Thickness", null, 1, 1, 5, 1, "");
		public static final RgbSetting COLOR = new RgbSetting("color", "Color", null, 0xFFFFFF);
		public static final IntSetting OPACITY = new IntSetting("opacity", "Opacity", null, 100, 10, 100, 5, "%");
		public static final BoolSetting INVERT = new BoolSetting("invertColor", "Invert color", "Contrasts with what is behind it, as vanilla's does",
			false);
		public static final BoolSetting TARGET_COLOR = new BoolSetting("targetColor", "Color on target",
			"Another color while you aim at an entity in reach", false);
		public static final RgbSetting TARGET_RGB = new RgbSetting("targetRgb", "Target color", null, 0xFF5555);
		public static final BoolSetting DOT = new BoolSetting("dot", "Dot", "A dot in the middle", false);
		public static final RgbSetting DOT_COLOR = new RgbSetting("dotColor", "Dot color", null, 0xFFFFFF);
		public static final BoolSetting OUTLINE = new BoolSetting("outline", "Outline", true);
		public static final RgbSetting OUTLINE_COLOR = new RgbSetting("outlineColor", "Outline color", null, 0x000000);
		public static final BoolSetting THIRD_PERSON = new BoolSetting("thirdPerson", "Show in third person", false);
		public static final BoolSetting HIDE_SPECTATOR = new BoolSetting("hideSpectator", "Disable when spectating", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Crosshair", "Customize your crosshair")
			.keywords("crosshair", "aim", "reticle", "dot").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Crosshair", TYPE, IGNORE_SCALE, SIZE, GAP, THICKNESS, COLOR, OPACITY, INVERT)
			.section("Reachable Crosshair", TARGET_COLOR, TARGET_RGB).section("Dot", DOT, DOT_COLOR).section("Outline", OUTLINE, OUTLINE_COLOR)
			.section("Dynamic Crosshair", THIRD_PERSON, HIDE_SPECTATOR)
			.note("“On target” uses what Minecraft's own crosshair already knows you aim at.").build();
	}

	public static final class AttackIndicator {
		public static final String ID = "attack_indicator";
		public static final OptionSetting SHAPE = new OptionSetting("shape", "Display mode", null, 1, "default", "Default", "ring", "Ring",
			"bar", "Bar");
		public static final IntSetting SCALE = new IntSetting("scale", "Scale", null, 100, 50, 300, 25, "%");
		public static final IntSetting X_OFFSET = new IntSetting("xOffset", "X offset", null, 0, -40, 40, 1, "");
		public static final IntSetting Y_OFFSET = new IntSetting("yOffset", "Y offset", null, 0, -40, 40, 1, "");
		public static final BoolSetting FULL_ONLY_TARGET = new BoolSetting("hideWhenReady", "Hide when charged", "Off: always shown",
			true);
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
		public static final BoolSetting SLEEP = new BoolSetting("sleep", "Sleep progress", "Until the night can be skipped", true);
		public static final BoolSetting BREAKING = new BoolSetting("blockBreaking", "Block breaking", "How far the block you mine is", true);
		public static final BoolSetting PEARL = new BoolSetting("enderPearl", "Ender pearl", "Its cooldown, while you hold one", true);
		public static final BoolSetting CHORUS = new BoolSetting("chorusFruit", "Chorus fruit", "Its cooldown, while you hold one", true);
		public static final BoolSetting BOWS = new BoolSetting("bows", "Bows", "How far you've drawn a bow or crossbow", true);
		public static final BoolSetting SHIELDS = new BoolSetting("shields", "Shields", "Until a raised shield blocks", true);
		public static final BoolSetting CONSUMABLES = new BoolSetting("consumables", "Consumables", "Eating and drinking", true);
		public static final BoolSetting WIND_CHARGE = new BoolSetting("windCharge", "Wind charge", "Its cooldown, while you hold one", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Attack Indicator", "Show your attack cooldown your way")
			.keywords("attack", "cooldown", "indicator", "pvp", "bow").icon(IconId.GAUGE).category(Category.COMBAT)
			.section("General", SHAPE, SCALE, X_OFFSET, Y_OFFSET, FULL_ONLY_TARGET, DYNAMIC, COLOR)
			.section("Attack Cooldowns", SWORDS, AXES, TRIDENTS, SPEARS, PICKAXES, SHOVELS, OTHER, SOUND, SOUND_VOLUME)
			.section("Custom Indicators", SLEEP, BREAKING, PEARL, CHORUS, BOWS, SHIELDS, CONSUMABLES, WIND_CHARGE)
			.note("Under the crosshair, from what Minecraft already knows: your attack strength, how long you've used an item, an item's "
				+ "cooldown, how far a block is mined. One shows at a time: something you're doing first, then your attack. Set Minecraft's "
				+ "own Attack Indicator to Crosshair or Off.").build();
	}

	public static final class HitColor {
		public static final String ID = "hit_color";
		public static final BoolSetting HIT_INDICATOR = new BoolSetting("hitIndicator", "Hit indicator", "An arc around the crosshair pointing "
			+ "where damage came from", true);
		public static final RgbSetting HIT_COLOR = new RgbSetting("hitColor", "Hit indicator color", null, 0xE85A5A);
		public static final IntSetting HIT_DURATION = new IntSetting("hitDuration", "Hit duration", null, 30, 10, 100, 5, " ticks");
		public static final IntSetting HIT_SIZE = new IntSetting("hitSize", "Hit size", null, 100, 50, 200, 10, "%");
		public static final BoolSetting MARKER = new BoolSetting("marker", "Hit marker", "An X on the crosshair when your hit lands", true);
		public static final IntSetting MARKER_DURATION = new IntSetting("markerDuration", "Marker duration", null, 10, 4, 40, 2, " ticks");
		public static final IntSetting MARKER_SIZE = new IntSetting("markerSize", "Marker size", null, 100, 50, 200, 10, "%");
		public static final RgbSetting MARKER_COLOR = new RgbSetting("markerColor", "Marker color", null, 0xFFFFFF);
		public static final RgbSetting KILL_COLOR = new RgbSetting("killColor", "Kill color", "When the target died", 0xE53935);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Hit Color", "Add indicators where you receive damage")
			.keywords("hit", "damage", "indicator", "marker").icon(IconId.GAUGE).category(Category.COMBAT)
			.section("Hit Indicator", HIT_INDICATOR, HIT_COLOR, HIT_DURATION, HIT_SIZE)
			.section("Marker Indicator", MARKER, MARKER_DURATION, MARKER_SIZE, MARKER_COLOR, KILL_COLOR).needs(Requirement.WORLD)
			.note("The direction is the one the server sends with your hurt animation. A marker shows when the entity you hit flashes hurt.")
			.build();
	}

	public static final class Hitbox {
		public static final String ID = "hitbox";
		public static final BoolSetting LIMIT = new BoolSetting("limitDistance", "Max showable distance", "Only entities within the distance below",
			false);
		public static final IntSetting DISTANCE = new IntSetting("distance", "Distance", null, 64, 4, 256, 4, " blocks");

		/** One kind of entity's hitbox settings. The "hittable" and "damaged" ones exist only for living kinds (else null). */
		public record Group(String key, String title, BoolSetting enabled, IntSetting width, RgbSetting color, BoolSetting look,
			@org.jspecify.annotations.Nullable BoolSetting hittable, @org.jspecify.annotations.Nullable RgbSetting hittableColor,
			@org.jspecify.annotations.Nullable BoolSetting onlyHittable, @org.jspecify.annotations.Nullable BoolSetting damaged,
			@org.jspecify.annotations.Nullable RgbSetting damagedColor) {
			SettingSection section() {
				java.util.List<SettingDescriptor<?>> all = new java.util.ArrayList<>(java.util.List.of(enabled, width, color, look));
				if (hittable != null) {
					all.addAll(java.util.List.of(hittable, hittableColor, onlyHittable, damaged, damagedColor));
				}
				return new SettingSection(title, all, false);
			}
		}

		static Group group(String key, String title, boolean living) {
			BoolSetting enabled = new BoolSetting(key + ".enabled", "Show hitboxes", true);
			IntSetting width = new IntSetting(key + ".width", "Line width", null, 2, 1, 8, 1, "");
			RgbSetting color = new RgbSetting(key + ".color", "Line color", null, 0xFFFFFF);
			BoolSetting look = new BoolSetting(key + ".look", "Show look vector", "A line from the eyes where it faces", false);
			if (!living) {
				return new Group(key, title, enabled, width, color, look, null, null, null, null, null);
			}
			return new Group(key, title, enabled, width, color, look,
				new BoolSetting(key + ".hittable", "Show hittable color", "Another color while it's within your reach", false),
				new RgbSetting(key + ".hittableColor", "Hittable color", null, 0x55FF55),
				new BoolSetting(key + ".onlyHittable", "Only show when hittable", "Hides the box until it's within your reach", false),
				new BoolSetting(key + ".damaged", "Show damaged color", "Another color while it flashes hurt", false),
				new RgbSetting(key + ".damagedColor", "Damaged color", null, 0xFF5555));
		}

		/** In the reference order; the version layer sorts each entity into one of these by its kind. */
		public static final Group PLAYERS = group("players", "Players", true);
		public static final Group ITEMS = group("items", "Items", false);
		public static final Group ORBS = group("orbs", "Exp Orbs", false);
		public static final Group FRAMES = group("itemFrames", "Item Frames", false);
		public static final Group FIREWORKS = group("fireworks", "Fireworks", false);
		public static final Group WITHER_SKULLS = group("witherSkulls", "Wither Skulls", false);
		public static final Group SNOWBALLS = group("snowballs", "Snowballs", false);
		public static final Group FIREBALLS = group("fireballs", "Fireballs", false);
		public static final Group ARROWS = group("arrows", "Arrows", false);
		public static final Group PROJECTILES = group("projectiles", "Projectiles", false);
		public static final Group MONSTERS = group("monsters", "Monsters", true);
		public static final Group PASSIVE = group("passive", "Passive", true);
		public static final Group OTHER = group("other", "Other Entities", false);
		public static final java.util.List<Group> GROUPS = java.util.List.of(PLAYERS, ITEMS, ORBS, FRAMES, FIREWORKS, WITHER_SKULLS, SNOWBALLS,
			FIREBALLS, ARROWS, PROJECTILES, MONSTERS, PASSIVE, OTHER);

		public static final ModuleDescriptor DESCRIPTOR = build();

		private static ModuleDescriptor build() {
			ModuleDescriptor.Builder b = ModuleDescriptor.builder(ID, "Hitbox", "Customize the hitboxes of entities around you")
				.keywords("hitbox", "f3+b", "bounding box", "entities").icon(IconId.GAUGE).category(Category.COMBAT)
				.section("Distance", LIMIT, DISTANCE);
			for (Group group : GROUPS) {
				b.section(group.section());
			}
			return b.needs(Requirement.WORLD)
				.note("Outlines the hitboxes Minecraft already uses, only for entities you could see (walls still hide them). It never changes a "
					+ "hitbox or your reach. “Within your reach” is your interaction range, measured from your eyes.").build();
		}
	}

	public static final class TierTagger {
		public static final String ID = "tier_tagger";
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Tier Tagger", "Displays tier from PvP tier lists")
			.keywords("tier", "pvp", "rank", "nametag").icon(IconId.SHIELD).category(Category.COMBAT)
			.blocked("Showing tiers means sending the UUID of every player you see to a tier list (mctiers.com, pvptiers.com, subtiers.net). "
				+ "None publishes terms for other clients, and their icons are licensed to one mod only, so Flint asks you to approve a "
				+ "list first. Until then it shows no tiers rather than guessed ones").build();
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

	public static final class HurtCam {
		public static final String ID = "hurt_cam";
		public static final IntSetting STRENGTH = new IntSetting("strength", "Shake strength", "0 removes the shake entirely", 0, 0, 100, 5, "%");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Hurt Cam", "Tone down the camera shake when you're hurt")
			.keywords("hurt", "camera", "shake", "tilt").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Hurt Cam", STRENGTH)
			.note("Times Minecraft's own Damage Tilt setting while on.").build();
	}
}
