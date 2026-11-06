package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.BoxStyle;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import java.util.List;

/**
 * HUD modules added with the Right Shift menu's second batch: what's under the crosshair (a block or a mob), your own
 * model, your resource packs, your mouse, how well you timed a jump reset, and the item you just picked up in hand.
 */
public final class MoreHudMods {
	private MoreHudMods() {
	}

	public static final class BlockIndicator {
		public static final String ID = "block_indicator";
		public static final IntSetting RANGE = new IntSetting("indicatorRange", "Range", "How far away a block is still shown", 12, 1, 32, 1,
			" blocks");
		public static final BoolSetting BREAKING = new BoolSetting("showBlockBreakingPercentage", "Breaking progress", "How far you've mined it",
			true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Block Indicator", "Show the block under your crosshair")
			.keywords("block", "look at", "waila", "jade", "hwyla", "mining").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("General", RANGE, BREAKING).hud(HudPlacement.of(Anchor.TOP, 0, 4)).needs(Requirement.WORLD).build();
	}

	public static final class DamageIndicator {
		public static final String ID = "damage_indicator";
		public static final IntSetting RANGE = new IntSetting("indicatorRange", "Range", "How far away a mob is still shown", 12, 1, 32, 1,
			" blocks");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Damage Indicator", "Show the health of the mob you look at")
			.keywords("health", "mob", "target", "hp", "damage").icon(IconId.SHIELD).category(Category.COMBAT)
			.section("General", RANGE).hud(HudPlacement.of(Anchor.TOP, 0, 34)).needs(Requirement.WORLD).build();
	}

	public static final class PlayerModel {
		public static final String ID = "player_model";
		public static final BoolSetting HEAD = new BoolSetting("headFollowsCamera", "Head follows camera", "The head looks up and down as you do",
			true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Player Model", "Show your own player on the HUD")
			.keywords("player", "model", "skin", "paper doll", "self").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", HEAD).hud(HudPlacement.of(Anchor.RIGHT, 4, 0)).needs(Requirement.WORLD).build();
	}

	/** Pack Display: Pack Displayer's box with the texture pack you use, its icon and name. */
	public static final class PackDisplay {
		public static final String ID = "pack_display";
		public static final IntSetting OPACITY = new IntSetting("backgroundOpacity", "Background opacity",
			"Set how transparent the overlay background is", 40, 0, 100, 1, "%");
		public static final BoolSetting ICON = new BoolSetting("showIcon", "Show pack icon", "Display the pack icon next to the pack name", true);
		public static final IntSetting SCALE = new IntSetting("scale", "Overlay scale", "Adjust the size of the pack icon", 80, 50, 200, 1, "%");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Pack Display", "Displays the texture pack you're using")
			.keywords("resource pack", "texture pack", "packs", "pack displayer").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Appearance", OPACITY, ICON, SCALE).drawnByMinecraft().hud(HudPlacement.of(Anchor.CENTER, 0, 0)).needs(Requirement.WORLD)
			.note("The top texture pack from your resource packs folder (Minecraft's built-in packs and servers' aren't counted). When you "
				+ "turn your last one off, it switches itself off and says so in chat. Move it in the HUD editor.").build();
	}

	public static final class Mousestrokes {
		public static final String ID = "mousestrokes";
		public static final FloatSetting SENSITIVITY = new FloatSetting("sensitivity", "Sensitivity", "How far the dot moves for a turn", 1,
			0.1F, 3, 0.1F, "×");
		public static final RgbSetting COLOR = new RgbSetting("mainColor", "Dot color", null, 0xFFFFFF);
		public static final BoolSetting TRAIL = new BoolSetting("trail", "Trail", "A fading line behind the dot", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Mousestrokes", "Show your mouse movement on the HUD")
			.keywords("mouse", "aim", "movement", "strokes", "sensitivity").icon(IconId.MOUSE).category(Category.COMBAT)
			.section("General", SENSITIVITY, COLOR, TRAIL).hud(HudPlacement.of(Anchor.BOTTOM, 122, 50)).needs(Requirement.WORLD).build();
	}

	public static final class JumpReset {
		public static final String ID = "jump_reset";
		public static final BoxStyle STYLE = new BoxStyle(80, 13);
		public static final IntSetting MAX_TICKS = new IntSetting("maxDiffTicksToDisplay", "Max tick difference",
			"A jump further from the hit than this isn't shown", 10, 1, 20, 1, " ticks");
		public static final FloatSetting SHOW_FOR = new FloatSetting("displayTime", "Display time", null, 3, 0.5F, 10, 0.5F, " s");
		public static final BoolSetting TIMING_COLORS = new BoolSetting("timingColors", "Timing colors",
			"Green when perfect, amber one tick off, red further; off: the text color", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Jump Reset", "How well you timed a jump reset")
			.keywords("jump reset", "knockback", "kb", "pvp", "timing").icon(IconId.GAUGE).category(Category.COMBAT)
			.section("General", MAX_TICKS, SHOW_FOR, TIMING_COLORS).section(STYLE.section("Style")).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.CENTER, 0, 24)).needs(Requirement.WORLD)
			.note("Jump the same tick you're hit to take less knockback. Perfect is that tick; early and late count the ticks between.").build();
	}

	public static final class ItemInfo {
		public static final String ID = "item_info";
		public static final BoolSetting COUNT = new BoolSetting("count", "Count", "How many are in the stack", true);
		public static final IntSetting MIN_COUNT = new IntSetting("minToShow", "Min to show", "Smaller stacks don't show a count", 2, 1, 64, 1, "");
		public static final RgbSetting COUNT_COLOR = new RgbSetting("countColor", "Count color", null, 0xC0C0C0);
		public static final BoolSetting NO_PREFIX = new BoolSetting("removePrefix", "Remove prefix", "\"64\" instead of \"x64\"", false);
		public static final BoolSetting ENCHANTS = new BoolSetting("enchants", "Enchantments", true);
		public static final RgbSetting ENCHANTS_COLOR = new RgbSetting("enchantsColor", "Enchantments color", null, 0xB9A7FF);
		public static final BoolSetting DURABILITY = new BoolSetting("durability", "Durability", "Uses left out of the most it can have", false);
		public static final RgbSetting DURABILITY_COLOR = new RgbSetting("durabilityColor", "Durability color", null, 0xC0C0C0);
		public static final BoolSetting ALERT = new BoolSetting("durabilityAlert", "Low durability alert", "Turns the durability red when low",
			false);
		public static final IntSetting ALERT_AT = new IntSetting("durabilityPercent", "Alert below", null, 20, 1, 100, 1, "%");
		public static final RgbSetting ALERT_COLOR = new RgbSetting("durabilityAlertColor", "Alert color", null, 0xFF5555);
		public static final BoolSetting ALERT_SOUND = new BoolSetting("durabilityAlertSound", "Alert sound", "A ping when you take it in hand",
			false);
		public static final IntSetting ALERT_VOLUME = new IntSetting("durabilityAlertVolume", "Alert volume", null, 80, 0, 100, 5, "%");
		public static final BoolSetting CONTAINER = new BoolSetting("container", "Container contents", "How many items a shulker box or bundle holds",
			true);
		public static final RgbSetting CONTAINER_COLOR = new RgbSetting("containerColor", "Container color", null, 0xC0C0C0);
		public static final BoolSetting LORE = new BoolSetting("text", "Item text", "The item's own description lines", false);
		public static final RgbSetting LORE_COLOR = new RgbSetting("textColor", "Item text color", null, 0xA0A0A0);
		public static final BoolSetting FADE = new BoolSetting("fadeOut", "Fade out", "Off: it stays while you hold the item", true);
		public static final FloatSetting SHOW_FOR = new FloatSetting("fadeOutTime", "Fade after", null, 1.5F, 0.5F, 10, 0.5F, " s");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Item Info", "Details of the item you take in hand")
			.keywords("held item", "tooltip", "enchantments", "durability", "count").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Count", COUNT, MIN_COUNT, COUNT_COLOR, NO_PREFIX).section("Enchantments", ENCHANTS, ENCHANTS_COLOR)
			.section("Durability", DURABILITY, DURABILITY_COLOR, ALERT, ALERT_AT, ALERT_COLOR, ALERT_SOUND, ALERT_VOLUME)
			.section("Container", CONTAINER, CONTAINER_COLOR).section("Item Text", LORE, LORE_COLOR).section("Fade", FADE, SHOW_FOR)
			.hud(HudPlacement.of(Anchor.BOTTOM, 0, 50)).needs(Requirement.WORLD)
			.note("Takes the place of Minecraft's item name above the hotbar while it's on.").build();
	}

	/** The batch above, for the registry. */
	public static List<ModuleDescriptor> all() {
		return List.of(BlockIndicator.DESCRIPTOR, DamageIndicator.DESCRIPTOR, PlayerModel.DESCRIPTOR, PackDisplay.DESCRIPTOR, Mousestrokes.DESCRIPTOR,
			JumpReset.DESCRIPTOR, ItemInfo.DESCRIPTOR);
	}
}
