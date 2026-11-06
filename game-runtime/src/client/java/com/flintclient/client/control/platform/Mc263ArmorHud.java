/*
 * Adapted from uku's Armor HUD (https://github.com/uku3lig/armor-hud), a fork of BerdinskiyBear's Armor HUD.
 * Copyright (c) 2021 BerdinskiyBear. MIT License: see META-INF/licenses/MIT-ukus-armor-hud.txt.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.ArmorLayout;
import com.flintclient.client.control.feature.ArmorPieces;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings.Armor;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.Orientation;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

/**
 * Armor, as uku's Armor HUD: the armour you wear in a strip of hotbar slots (Minecraft's own hotbar sprite, cut to as many
 * slots as there are pieces, or its rounded offhand slot), placed against the hotbar (moving aside for the offhand slot and
 * the attack indicator while they show), the top centre, a top or bottom corner or the middle of a side, with the offsets
 * added; or, Free, wherever the HUD editor put it, at its size there. Each piece shows its durability as Minecraft's bar or
 * as text in the bar's colour (at the text's own size, bold if asked), and a warning sign that bobs on pieces about to break.
 * Slots after the armour can show a sword, pickaxe, axe, mace, trident, bow and shield from your hands and hotbar
 * ({@link ArmorPieces}) the same way; the held items' durability can show over their hotbar slots (unless a slot shows them).
 * Boss bars, Minecraft's effect icons and subtitles move out of its way where it covers theirs, and a sound plays as an
 * endangered piece wears down.
 *
 * <p>The geometry ({@link ArmorLayout}), the order of the drawing and the colours are the original's, so it looks exactly
 * like it. Drawn from the hotbar's hooks (after the hotbar, so F1 and spectator menus hide it as they hide the hotbar).
 * Fault-isolated.
 */
public final class Mc263ArmorHud {
	private static final HookGuard GUARD = new HookGuard("armor HUD",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int ARMOR = Mods.index(ModuleId.ARMOR);
	/** The slots' pitch, a slot's frame, the hotbar's half width plus its gap, the offhand slot and the attack indicator. */
	static final int STEP = ArmorLayout.STEP;
	static final int SIZE = ArmorLayout.SIZE;
	static final int HOTBAR_OFFSET = 98;
	static final int OFFHAND_OFFSET = 29;
	static final int ATTACK_INDICATOR_OFFSET = 23;
	/** Where the hotbar sprite's slots start over again for a strip longer than it ({@link #hotbarRun}): its eighth slot's end. */
	private static final int HOTBAR_REPEAT_END = 1 + 8 * STEP;
	private static final Identifier HOTBAR = Identifier.withDefaultNamespace("hud/hotbar");
	private static final Identifier OFFHAND_LEFT = Identifier.withDefaultNamespace("hud/hotbar_offhand_left");
	private static final Identifier WARNING = Identifier.fromNamespaceAndPath("flintclient", "textures/gui/armor_warning.png");
	/** The worn slots in the inventory's order (helmet first). */
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	/**
	 * Each slot's empty outline, by where it comes from: the four armour pieces, then the item kinds ({@link ArmorPieces}); null
	 * for a kind Minecraft draws no outline for.
	 */
	private static final @Nullable Identifier[] EMPTY = {Identifier.withDefaultNamespace("container/slot/helmet"),
		Identifier.withDefaultNamespace("container/slot/chestplate"), Identifier.withDefaultNamespace("container/slot/leggings"),
		Identifier.withDefaultNamespace("container/slot/boots"), Identifier.withDefaultNamespace("container/slot/sword"),
		Identifier.withDefaultNamespace("container/slot/pickaxe"), Identifier.withDefaultNamespace("container/slot/axe"), null, null, null,
		Identifier.withDefaultNamespace("container/slot/shield")};
	/** Every slot whose wear can sound the alarm: the armour, then the main hand and the offhand. */
	private static final EquipmentSlot[] ALL_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
		EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
	private static final SoundEvent BREAKING = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("flintclient", "armor_breaking"));
	private static final RandomSource RANDOM = RandomSource.create();

	// Anchors and options, by their index in the settings.
	private static final int TOP_CENTER = Armor.TOP_CENTER;
	private static final int TOP = Armor.TOP;
	private static final int VERT_CENTER = Armor.VERTICAL_CENTER;
	private static final int HOTBAR_ANCHOR = Armor.HOTBAR;
	private static final int BOTTOM = Armor.BOTTOM;
	private static final int LEFT = 0;
	private static final int RIGHT = 1;
	private static final int STYLE_HOTBAR = 0;
	private static final int STYLE_ROUNDED_CORNERS = 1;
	private static final int STYLE_ROUNDED = 2;
	private static final int ALWAYS = 0;
	private static final int IF_ANY_PRESENT = 1;
	private static final int NOT_EMPTY = 2;
	private static final int ALWAYS_IGNORE = 0;
	private static final int ADHERE = 1;
	private static final int BAR = ArmorPieces.BAR;
	private static final int TEXT_LEFT = 1;
	private static final int TEXT_RIGHT = 2;
	private static final int TEXT_ABOVE = 3;
	private static final int TEXT_BELOW = 4;
	private static final int TEXT_CUSTOM = 1;
	/** What the item slots come from on the strip: the armour pieces are 0 to 3. */
	private static final int FIRST_KIND = SLOTS.length;

	/**
	 * The slots drawn this frame (reused; cleared once drawn so no stack outlives its frame), where each came from (0 to 3: the
	 * armour; then {@link #FIRST_KIND} plus the item kind), and the hotbar and offhand places the strip shows (bits).
	 */
	private static final ItemStack[] ITEMS = new ItemStack[ArmorPieces.MAX_SLOTS];
	private static final int[] FROM = new int[ArmorPieces.MAX_SLOTS];
	private static int shownPlaces;
	/** The kinds of the items in the hotbar and offhand, and the place each wanted kind's slot shows. */
	private static final int[] PLACE_KINDS = new int[ArmorPieces.PLACES];
	private static final int[] PICKED = new int[ArmorPieces.KINDS];
	/** The last stack seen in each of {@link #ALL_SLOTS} and each item slot, for the breaking sound. */
	private static final ItemStack[] LAST = new ItemStack[ALL_SLOTS.length];
	private static final ItemStack[] LAST_PICKED = new ItemStack[ArmorPieces.KINDS];
	/** The strip's rectangle against its anchor (x, y, w, h), as uku's Armor HUD works it out. */
	private static final int[] RECT = new int[4];
	/** The strip and its text laid out (its box, and in Free placement where it goes). */
	private static final ArmorLayout LAYOUT = new ArmorLayout();
	private static final ArmorPieces.Texts TEXTS = new ArmorPieces.Texts();

	private Mc263ArmorHud() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(ARMOR);
	}

	private static @Nullable Player cameraPlayer() {
		return Minecraft.getInstance().getCameraEntity() instanceof Player player ? player : null;
	}

	// ---------- which pieces ----------

	/** Fills {@link #ITEMS} with the slots to show, in order, and returns how many; {@link #shownPlaces} says which hand items they include. */
	private static int armorItems(ModuleState s, Player player) {
		int shown = s.option(Armor.WIDGET_SHOWN);
		int n = 0;
		boolean any = false;
		shownPlaces = 0;
		for (int i = 0; i < SLOTS.length; i++) {
			ItemStack stack = player.getItemBySlot(SLOTS[i]);
			any |= !stack.isEmpty();
			if (keep(s, shown, stack)) {
				ITEMS[n] = stack;
				FROM[n] = i;
				n++;
			}
		}
		int wanted = ArmorPieces.wanted(s, ArmorPieces.ALL);
		if (wanted != 0) {
			pick(player, wanted);
			for (int kind = 0; kind < ArmorPieces.KINDS; kind++) {
				if ((wanted & 1 << kind) == 0) {
					continue;
				}
				int place = PICKED[kind];
				ItemStack stack = place == ArmorPieces.NONE ? ItemStack.EMPTY : at(player, place);
				any |= !stack.isEmpty();
				if (keep(s, shown, stack)) {
					ITEMS[n] = stack;
					FROM[n] = FIRST_KIND + kind;
					n++;
					if (place != ArmorPieces.NONE) {
						shownPlaces |= 1 << place;
					}
				}
			}
		}
		if (shown == IF_ANY_PRESENT && !any) {
			n = 0;
			shownPlaces = 0;
		}
		if (s.bool(Armor.REVERSED)) {
			for (int a = 0, b = n - 1; a < b; a++, b--) {
				ItemStack stack = ITEMS[a];
				ITEMS[a] = ITEMS[b];
				ITEMS[b] = stack;
				int from = FROM[a];
				FROM[a] = FROM[b];
				FROM[b] = from;
			}
		}
		return n;
	}

	/** Whether a slot holding {@code stack} shows with Show widget {@code shown}. */
	private static boolean keep(ModuleState s, int shown, ItemStack stack) {
		return switch (shown) {
			case ALWAYS, IF_ANY_PRESENT -> true;
			case NOT_EMPTY -> !stack.isEmpty();
			default -> warning(s, stack);
		};
	}

	/** Sorts the hotbar's and the offhand's items into kinds and picks each wanted kind's place ({@link #PICKED}). */
	private static void pick(Player player, int wanted) {
		net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
		for (int slot = 0; slot < ArmorPieces.HOTBAR; slot++) {
			PLACE_KINDS[slot] = kinds(inventory.getItem(slot));
		}
		PLACE_KINDS[ArmorPieces.OFFHAND] = kinds(player.getOffhandItem());
		ArmorPieces.pick(PLACE_KINDS, inventory.getSelectedSlot(), wanted, PICKED);
	}

	/** {@code stack}'s kinds, as bits ({@link ArmorPieces}). */
	private static int kinds(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}
		int kinds = 0;
		if (stack.is(ItemTags.SWORDS)) {
			kinds |= 1 << ArmorPieces.SWORD;
		}
		if (stack.is(ItemTags.PICKAXES)) {
			kinds |= 1 << ArmorPieces.PICKAXE;
		}
		if (stack.is(ItemTags.AXES)) {
			kinds |= 1 << ArmorPieces.AXE;
		}
		if (stack.is(Items.MACE)) {
			kinds |= 1 << ArmorPieces.MACE;
		}
		if (stack.is(Items.TRIDENT)) {
			kinds |= 1 << ArmorPieces.TRIDENT;
		}
		if (stack.is(Items.BOW) || stack.is(Items.CROSSBOW)) {
			kinds |= 1 << ArmorPieces.BOW;
		}
		if (stack.is(Items.SHIELD)) {
			kinds |= 1 << ArmorPieces.SHIELD;
		}
		return kinds;
	}

	/** The item at a hotbar slot or the offhand. */
	private static ItemStack at(Player player, int place) {
		return place == ArmorPieces.OFFHAND ? player.getOffhandItem() : player.getInventory().getItem(place);
	}

	private static void forget() {
		java.util.Arrays.fill(ITEMS, null);
	}

	/** Whether {@code stack} is about to break: at or below the minimum percentage, or the minimum uses left. */
	static boolean warning(ModuleState s, ItemStack stack) {
		if (stack.isEmpty() || !stack.isDamageableItem()) {
			return false;
		}
		return ArmorPieces.endangered(stack.getDamageValue(), stack.getMaxDamage(), s.integer(Armor.MIN_PERCENT) / 100.0, s.integer(Armor.MIN_VALUE));
	}

	// ---------- where ----------

	/**
	 * The strip's rectangle against its anchor on a {@code guiWidth × guiHeight} screen for {@code count} pieces, into
	 * {@link #RECT}, as uku's Armor HUD works it out; false when there is nothing to show.
	 */
	private static boolean widgetRect(ModuleState s, Player player, int count, int guiWidth, int guiHeight) {
		if (count == 0) {
			return false;
		}
		int anchor = s.option(Armor.ANCHOR);
		int side = s.option(Armor.SIDE);
		boolean flip = anchor == HOTBAR_ANCHOR && side == LEFT || anchor != HOTBAR_ANCHOR && side == RIGHT;
		int sideMultiplier = flip ? -1 : 1;
		int sideOffsetMultiplier = flip ? -1 : 0;
		HumanoidArm sideArm = side == LEFT ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
		int added = switch (s.option(Armor.OFFHAND_SLOT)) {
			case ALWAYS_IGNORE -> 0;
			case ADHERE -> {
				if (player.getMainArm() == sideArm) {
					if (Minecraft.getInstance().options.attackIndicator().get() == AttackIndicatorStatus.HOTBAR && player.getAttackStrengthScale(0.0F) < 1.0F) {
						yield ATTACK_INDICATOR_OFFSET;
					}
				} else if (!player.getOffhandItem().isEmpty()) {
					yield OFFHAND_OFFSET;
				}
				yield 0;
			}
			default -> player.getMainArm() == sideArm ? ATTACK_INDICATOR_OFFSET : OFFHAND_OFFSET;
		};
		int textureWidth = SIZE + (count - 1) * STEP;
		boolean vertical = s.choice(Armor.LAYOUT) == Orientation.VERTICAL;
		int width = vertical ? SIZE : textureWidth;
		int height = vertical ? textureWidth : SIZE;
		int offsetX = s.integer(Armor.OFFSET_X);
		int offsetY = s.integer(Armor.OFFSET_Y);
		int x = offsetX * sideMultiplier + switch (anchor) {
			case TOP_CENTER -> (guiWidth - width) / 2;
			case HOTBAR_ANCHOR -> guiWidth / 2 + (HOTBAR_OFFSET + added) * sideMultiplier + width * sideOffsetMultiplier;
			default -> (width - guiWidth) * sideOffsetMultiplier;
		};
		int y = switch (anchor) {
			case TOP_CENTER, TOP -> offsetY;
			case VERT_CENTER -> (guiHeight - height) / 2 + offsetY;
			default -> guiHeight - height - offsetY;
		};
		RECT[0] = x;
		RECT[1] = y;
		RECT[2] = width;
		RECT[3] = height;
		return true;
	}

	/**
	 * Lays out the {@code count} slots in {@link #ITEMS} ({@link #LAYOUT}: the box with their text and warning signs) and places
	 * them: against the anchor ({@link #RECT} is the strip, the box's corner {@code LAYOUT.left, top}) or, Free, by the HUD
	 * placement at its size. False when there is nothing to show.
	 */
	private static boolean layout(ModuleState s, Player player, Font font, int count, int guiWidth, int guiHeight) {
		if (count == 0) {
			return false;
		}
		int display = s.option(Armor.DURABILITY);
		boolean bold = s.bool(Armor.TEXT_BOLD);
		int widest = 0;
		if (display != BAR) {
			for (int i = 0; i < count; i++) {
				ItemStack stack = ITEMS[i];
				if (stack != null && !stack.isEmpty() && stack.isDamageableItem()) {
					widest = Math.max(widest, font.width(ArmorPieces.widest(display, stack.getMaxDamage(), bold)));
				}
			}
		}
		boolean vertical = s.choice(Armor.LAYOUT) == Orientation.VERTICAL;
		LAYOUT.measure(count, vertical, display != BAR, widest, s.integer(Armor.TEXT_SCALE), s.bool(Armor.WARNING), s.integer(Armor.BOB));
		if (Armor.anchored(s)) {
			widgetRect(s, player, count, guiWidth, guiHeight);
			LAYOUT.side(vertical ? extrasSide(s) == RIGHT : extrasBelow(s));
			LAYOUT.scale = 1;
			LAYOUT.left = RECT[0] - LAYOUT.stripX;
			LAYOUT.top = RECT[1] - LAYOUT.stripY;
			LAYOUT.drawnWidth = LAYOUT.boxWidth;
			LAYOUT.drawnHeight = LAYOUT.boxHeight;
			return true;
		}
		LAYOUT.place(s.placement(), guiWidth, guiHeight, com.flintclient.client.platform.Frame.windowGuiScale());
		int position = s.option(Armor.TEXT_POSITION);
		boolean after;
		if (vertical) {
			after = position == TEXT_LEFT ? false : position == TEXT_RIGHT || LAYOUT.autoAfter(guiWidth, guiHeight);
		} else {
			after = position == TEXT_ABOVE ? false : position == TEXT_BELOW || LAYOUT.autoAfter(guiWidth, guiHeight);
		}
		LAYOUT.side(after);
		return true;
	}

	/** {@link #layout} for the pushes: the box against its anchor (GUI units), false when there is nothing to show. */
	private static boolean effectiveRect(ModuleState s, Player player, int guiWidth, int guiHeight) {
		try {
			return layout(s, player, Minecraft.getInstance().font, armorItems(s, player), guiWidth, guiHeight);
		} finally {
			forget();
		}
	}

	private static boolean top(ModuleState s) {
		int anchor = s.option(Armor.ANCHOR);
		return anchor == TOP || anchor == TOP_CENTER;
	}

	/** A horizontal strip's durability text and warning signs: under the strip (Below, or Auto at the top) or over it. */
	private static boolean extrasBelow(ModuleState s) {
		return switch (s.option(Armor.TEXT_POSITION)) {
			case TEXT_ABOVE -> false;
			case TEXT_BELOW -> true;
			default -> top(s);
		};
	}

	/** A vertical strip's side for them: as chosen, or (Auto) away from the hotbar, or away from the screen's edge. */
	private static int extrasSide(ModuleState s) {
		return switch (s.option(Armor.TEXT_POSITION)) {
			case TEXT_LEFT -> LEFT;
			case TEXT_RIGHT -> RIGHT;
			default -> {
				int side = s.option(Armor.SIDE);
				yield s.option(Armor.ANCHOR) == HOTBAR_ANCHOR ? side : side == LEFT ? RIGHT : LEFT;
			}
		};
	}

	/** The durability text's color for {@code stack}: its bar's (green to red) or the chosen one. */
	private static int textColor(ModuleState s, ItemStack stack) {
		return 0xFF000000 | (s.option(Armor.TEXT_COLOR_MODE) == TEXT_CUSTOM ? s.rgb(Armor.TEXT_COLOR) : stack.getBarColor());
	}

	// ---------- the HUD editor ----------

	/**
	 * Armor in the HUD editor: its box (the strip with its text and warning signs) into {@code out} as {left, top, width,
	 * height, anchored}: where its own anchor puts it (GUI units, anchored = 1) or, Free, its size at full size (anchored = 0:
	 * its HUD placement places it). False when it shows nothing; with {@code placeholder}, the box four armour slots would
	 * take instead.
	 */
	public static boolean box(float[] out, boolean placeholder) {
		if (!GUARD.on()) {
			return false;
		}
		try {
			Player player = cameraPlayer();
			Minecraft mc = Minecraft.getInstance();
			if (player == null) {
				return false;
			}
			ModuleState s = Mods.state(ARMOR);
			int count = armorItems(s, player);
			if (count == 0) {
				if (!placeholder) {
					return false;
				}
				count = SLOTS.length;
				for (int i = 0; i < count; i++) {
					ITEMS[i] = ItemStack.EMPTY;
				}
			}
			if (!layout(s, player, mc.font, count, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight())) {
				return false;
			}
			out[0] = LAYOUT.left;
			out[1] = LAYOUT.top;
			out[2] = LAYOUT.boxWidth;
			out[3] = LAYOUT.boxHeight;
			out[4] = Armor.anchored(s) ? 1 : 0;
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		} finally {
			forget();
		}
	}

	/** For QA tours and logs: how Armor is placed now, its box on screen (GUI units) and what its slots show. */
	public static String describe() {
		ModuleState s = Mods.state(ARMOR);
		float[] at = new float[5];
		boolean shown = box(at, false);
		StringBuilder out = new StringBuilder(Armor.anchored(s) ? "anchor=" + Armor.ANCHOR.keys().get(s.option(Armor.ANCHOR)) : "free " + s.placement());
		out.append(shown ? String.format(java.util.Locale.ROOT, " box=%.1f,%.1f %.0fx%.0f", at[0], at[1], at[2], at[3]) : " box=none");
		Player player = cameraPlayer();
		if (player != null) {
			int count = armorItems(s, player);
			out.append(" slots=");
			for (int i = 0; i < count; i++) {
				ItemStack stack = ITEMS[i];
				out.append(i == 0 ? "" : ",").append(stack.isEmpty() ? "empty" : stack.getItem().toString())
					.append(stack.isDamageableItem() ? "(" + (stack.getMaxDamage() - stack.getDamageValue()) + ")" : "");
			}
			out.append(" strip-places=").append(Integer.toBinaryString(shownPlaces));
			forget();
		}
		return out.toString();
	}

	// ---------- the pushes ----------

	/** How far boss bars move down (Top Center anchor, Push bossbars), in GUI units; 0 for not at all. */
	public static int bossBarPush(GuiGraphicsExtractor graphics) {
		ModuleState s = pushing(Armor.PUSH_BOSSBARS);
		if (s == null || s.option(Armor.ANCHOR) != TOP_CENTER) {
			return 0;
		}
		return pushBelow(s, graphics);
	}

	/** How far Minecraft's effect icons move down (Top anchor on the right, Push effect icons). */
	public static int effectsPush(GuiGraphicsExtractor graphics) {
		ModuleState s = pushing(Armor.PUSH_EFFECTS);
		if (s == null || s.option(Armor.ANCHOR) != TOP || s.option(Armor.SIDE) != RIGHT) {
			return 0;
		}
		return pushBelow(s, graphics);
	}

	/** How far subtitles move up (Bottom anchor on the right, Push subtitles). */
	public static int subtitlesPush(GuiGraphicsExtractor graphics) {
		return subtitlesPush(graphics.guiWidth(), graphics.guiHeight());
	}

	/** {@link #subtitlesPush(GuiGraphicsExtractor)} on a {@code guiWidth × guiHeight} screen (Nearby Sound Activity's list too). */
	public static int subtitlesPush(int guiWidth, int guiHeight) {
		ModuleState s = pushing(Armor.PUSH_SUBTITLES);
		if (s == null || s.option(Armor.ANCHOR) != BOTTOM || s.option(Armor.SIDE) != RIGHT) {
			return 0;
		}
		try {
			Player player = cameraPlayer();
			if (player == null || !effectiveRect(s, player, guiWidth, guiHeight)) {
				return 0;
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return 0;
		}
		return Math.max(LAYOUT.boxHeight - 25, 0);
	}

	private static @Nullable ModuleState pushing(com.flintclient.client.control.model.BoolSetting push) {
		if (!on()) {
			return null;
		}
		ModuleState s = Mods.state(ARMOR);
		return s.bool(push) ? s : null;
	}

	private static int pushBelow(ModuleState s, GuiGraphicsExtractor graphics) {
		try {
			Player player = cameraPlayer();
			if (player == null || !effectiveRect(s, player, graphics.guiWidth(), graphics.guiHeight())) {
				return 0;
			}
			return Math.round(LAYOUT.top) + LAYOUT.boxHeight;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return 0;
		}
	}

	// ---------- drawing ----------

	/**
	 * After the hotbar (inside its draw, so it moves and hides with it, as uku's does): the strip against its anchor, its items,
	 * their durability and warning signs. Free, it is drawn after the hotbar and its bars instead ({@link #drawHands}).
	 */
	public static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!on()) {
			return;
		}
		try {
			Player player = cameraPlayer();
			ModuleState s = Mods.state(ARMOR);
			if (player != null && Armor.anchored(s)) {
				drawArmor(graphics, delta.getGameTimeDeltaPartialTick(false), s, player);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		} finally {
			forget();
		}
	}

	private static void drawArmor(GuiGraphicsExtractor graphics, float partialTick, ModuleState s, Player player) {
		int count = armorItems(s, player);
		Font font = Minecraft.getInstance().font;
		if (!layout(s, player, font, count, graphics.guiWidth(), graphics.guiHeight())) {
			return;
		}
		if (Armor.anchored(s)) {
			drawStrip(graphics, partialTick, s, player, font, count, RECT[0], RECT[1]);
			return;
		}
		// Free: the box where the HUD editor put it, at its size there.
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		try {
			pose.translate(LAYOUT.left, LAYOUT.top);
			pose.scale(LAYOUT.scale, LAYOUT.scale);
			drawStrip(graphics, partialTick, s, player, font, count, LAYOUT.stripX, LAYOUT.stripY);
		} finally {
			pose.popMatrix();
		}
	}

	/** The strip of {@code count} slots with its top-left corner at {@code (rectX, rectY)}, laid out by {@link #LAYOUT}. */
	private static void drawStrip(GuiGraphicsExtractor graphics, float partialTick, ModuleState s, Player player, Font font, int count, int rectX,
		int rectY) {
		boolean vertical = LAYOUT.vertical;
		int textureWidth = SIZE + (count - 1) * STEP;
		int color = 0xFFFFFFFF;
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(rectX, rectY);
		if (vertical) {
			pose.rotate((float) (Math.PI / 2));
			pose.translate(0.0F, -22.0F);
		}
		switch (s.option(Armor.STYLE)) {
			case STYLE_HOTBAR -> {
				hotbarRun(graphics, 0, 0, textureWidth - 3, color);
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, 179, 0, textureWidth - 3, 0, 3, 22, color);
			}
			case STYLE_ROUNDED_CORNERS -> {
				if (count > 1) {
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND_LEFT, 29, 24, 0, 1, 0, 0, 3, 22, color);
					hotbarRun(graphics, 3, 3, textureWidth - 6, color);
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND_LEFT, 29, 24, 19, 1, textureWidth - 3, 0, 3, 22, color);
				} else {
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND_LEFT, 29, 24, 0, 1, 0, 0, 22, 22, color);
				}
			}
			case STYLE_ROUNDED -> {
				if (count > 1) {
					int border = 1;
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND_LEFT, 29, 24, 0, 1, 0, 0, 22 - border, 22, color);
					for (int i = 1; i < count - 1; i++) {
						graphics.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND_LEFT, 29, 24, border, 1, border + i * STEP, 0, STEP, 22, color);
					}
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND_LEFT, 29, 24, 1, 1, textureWidth - STEP - border, 0, 22 - border, 22,
						color);
				} else {
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND_LEFT, 29, 24, 0, 1, 0, 0, 22, 22, color);
				}
			}
			default -> {
			}
		}
		pose.popMatrix();

		boolean after = LAYOUT.after;
		boolean shadow = s.bool(Armor.TEXT_SHADOW);
		boolean bold = s.bool(Armor.TEXT_BOLD);
		boolean icons = s.bool(Armor.ICONS) && com.flintclient.client.compat.McCompat.SLOT_OUTLINES;
		int shown = s.option(Armor.WIDGET_SHOWN);
		boolean emptySlots = shown == ALWAYS || shown == IF_ANY_PRESENT;
		int display = s.option(Armor.DURABILITY);
		boolean warningShown = s.bool(Armor.WARNING);
		int bob = s.integer(Armor.BOB);
		int lineHeight = LAYOUT.lineHeight;
		for (int i = 0; i < count; i++) {
			ItemStack stack = ITEMS[i];
			int x = rectX;
			int y = rectY;
			if (vertical) {
				y += STEP * i;
			} else {
				x += STEP * i;
			}
			int slotY = y;
			Identifier outline = EMPTY[FROM[i]];
			if (icons && emptySlots && stack.isEmpty() && outline != null) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, outline, x + 3, y + 3, 16, 16);
			}
			slot(graphics, font, x + 3, y + 3, partialTick, player, stack, i + 1);
			if (after && vertical) {
				x += 22;
			}
			if (display != BAR && !stack.isEmpty() && stack.isDamageableItem()) {
				String text = TEXTS.get(i, display, stack.getDamageValue(), stack.getMaxDamage(), bold);
				int width = LAYOUT.scaled(font.width(text));
				int ink = textColor(s, stack);
				if (!vertical) {
					// Over or under the slot, centred on it: every slot's text on the same line.
					int textY = after ? slotY + SIZE : slotY - lineHeight;
					text(graphics, font, text, ArmorLayout.centred(x, width), textY, ink, shadow, LAYOUT.textScale);
				} else {
					int textWidth = width + 2;
					if (!after) {
						x -= textWidth;
					}
					text(graphics, font, text, x + 1, LAYOUT.besideY(slotY), ink, shadow, LAYOUT.textScale);
					if (after) {
						x += textWidth;
					}
				}
			}
			if (warningShown && warning(s, stack)) {
				if (bob != 0) {
					y += (int) (RANDOM.nextInt(bob) - Math.ceil(bob / 2.0F));
				}
				if (!vertical) {
					// Past the line of text, as uku's Armor HUD puts it.
					int band = LAYOUT.textBand();
					int signY = y + (after ? SIZE + band : -band - 10);
					graphics.blit(RenderPipelines.GUI_TEXTURED, WARNING, x + 7, signY + 1, 0.0F, 0.0F, 8, 8, 8, 8);
				} else {
					if (!after) {
						x -= 10;
					}
					graphics.blit(RenderPipelines.GUI_TEXTURED, WARNING, x + 1, y + 7, 0.0F, 0.0F, 8, 8, 8, 8);
				}
			}
		}
	}

	/**
	 * {@code width} units of Minecraft's hotbar from {@code u} on, drawn at {@code x}: past the sprite's nine slots (the armour and
	 * the item slots can make eleven) it carries on with its slots again, so a long strip is the hotbar repeated, never the
	 * sprites beside it in the atlas.
	 */
	private static void hotbarRun(GuiGraphicsExtractor graphics, int x, int u, int width, int color) {
		while (width > 0) {
			// Up to the start of the hotbar's ninth slot's edge (1 + 8 slots of 20): the same point of a slot as u = 1.
			int w = Math.min(width, HOTBAR_REPEAT_END - u);
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, 182, 22, u, 0, x, 0, w, 22, color);
			x += w;
			width -= w;
			u = 1;
		}
	}

	/** Durability text at {@code (x, y)}, at {@code scale} times Minecraft's size (its top-left corner stays put). */
	private static void text(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int ink, boolean shadow, float scale) {
		if (scale == 1.0F) {
			graphics.text(font, text, x, y, ink, shadow);
			return;
		}
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		graphics.text(font, text, 0, 0, ink, shadow);
		pose.popMatrix();
	}

	/** One item as the hotbar draws it: the pick-up pop, the item and its decorations (bar and count). */
	private static void slot(GuiGraphicsExtractor graphics, Font font, int x, int y, float partialTick, Player player, ItemStack stack, int seed) {
		if (stack.isEmpty()) {
			return;
		}
		float pop = stack.getPopTime() - partialTick;
		Matrix3x2fStack pose = graphics.pose();
		if (pop > 0.0F) {
			float squeeze = 1.0F + pop / 5.0F;
			pose.pushMatrix();
			pose.translate(x + 8, y + 12);
			pose.scale(1.0F / squeeze, (squeeze + 1.0F) / 2.0F);
			pose.translate(-(x + 8), -(y + 12));
		}
		graphics.item(player, stack, x, y, seed);
		if (pop > 0.0F) {
			pose.popMatrix();
		}
		graphics.itemDecorations(font, stack, x, y);
	}

	/**
	 * After the hotbar and its bars: a Free strip, where the HUD editor put it (outside the Hotbar mod's and Autohide HUD's moves
	 * of the hotbar, like every HUD module; not for a spectator, who has no hotbar), then the held items' durability (and the
	 * offhand's warning) over their slots.
	 */
	public static void drawHands(GuiGraphicsExtractor graphics) {
		if (!on()) {
			return;
		}
		try {
			Player player = cameraPlayer();
			ModuleState state = Mods.state(ARMOR);
			if (player != null && !Armor.anchored(state) && !player.isSpectator()) {
				drawArmor(graphics, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false), state, player);
				forget();
			}
			if (player != null) {
				// Over the hotbar's slots at the Hotbar mod's size, as the hotbar itself is drawn.
				Matrix3x2fStack pose = graphics.pose();
				boolean sized = Mc263Hotbar.place(Mc263Hotbar.HOTBAR, graphics.guiWidth(), graphics.guiHeight(), 0, 0);
				if (sized) {
					pose.pushMatrix();
					pose.translate(Mc263Hotbar.anchorX, Mc263Hotbar.anchorY);
					pose.scale(Mc263Hotbar.scale, Mc263Hotbar.scale);
					pose.translate(-Mc263Hotbar.anchorX, -Mc263Hotbar.anchorY);
				}
				try {
					drawHands(graphics, Mods.state(ARMOR), player);
				} finally {
					if (sized) {
						pose.popMatrix();
					}
				}
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		} finally {
			forget();
		}
	}

	private static void drawHands(GuiGraphicsExtractor graphics, ModuleState s, Player player) {
		Font font = Minecraft.getInstance().font;
		int display = s.option(Armor.DURABILITY);
		boolean shadow = s.bool(Armor.TEXT_SHADOW);
		boolean bold = s.bool(Armor.TEXT_BOLD);
		float scale = Math.clamp(s.integer(Armor.TEXT_SCALE), 50, 200) / 100.0F;
		int lineHeight = Math.round(ArmorLayout.LINE * scale);
		// What the strip's item slots show already isn't shown again over the hotbar.
		armorItems(s, player);
		int strip = shownPlaces;
		ItemStack offhand = player.getOffhandItem();
		if (offhand.isDamageableItem() && s.bool(Armor.OFFHAND_DURABILITY) && (strip & 1 << ArmorPieces.OFFHAND) == 0) {
			int x = player.getMainArm() == HumanoidArm.RIGHT ? graphics.guiWidth() / 2 - 91 - 29 : graphics.guiWidth() / 2 + 91 + 7;
			int y = graphics.guiHeight() - 22;
			if (display != BAR) {
				String text = TEXTS.get(ArmorPieces.OFFHAND_TEXT, display, offhand.getDamageValue(), offhand.getMaxDamage(), bold);
				y -= lineHeight;
				text(graphics, font, text, ArmorLayout.centred(x, Math.round(font.width(text) * scale)), y, textColor(s, offhand), shadow, scale);
			}
			if (s.bool(Armor.WARNING) && warning(s, offhand)) {
				int bob = s.integer(Armor.BOB);
				if (bob != 0) {
					y += (int) (RANDOM.nextInt(bob) - Math.ceil(bob / 2.0F));
				}
				y -= 10;
				graphics.blit(RenderPipelines.GUI_TEXTURED, WARNING, x + 7, y + 1, 0.0F, 0.0F, 8, 8, 8, 8);
			}
		}
		ItemStack main = player.getMainHandItem();
		int selected = player.getInventory().getSelectedSlot();
		if (main.isDamageableItem() && s.bool(Armor.MAINHAND_DURABILITY) && display != BAR && (strip & 1 << selected) == 0) {
			int x = graphics.guiWidth() / 2 - 91 + selected * 20 + 11;
			int y = graphics.guiHeight() - 22 - 1;
			String text = TEXTS.get(ArmorPieces.MAIN_HAND_TEXT, display, main.getDamageValue(), main.getMaxDamage(), bold);
			int textX = x - Math.round(font.width(text) * scale) / 2;
			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(textX, y);
			pose.scale(scale, scale);
			graphics.text(font, text, 1, 0, 0xFF000000, false);
			graphics.text(font, text, -1, 0, 0xFF000000, false);
			graphics.text(font, text, 0, 1, 0xFF000000, false);
			graphics.text(font, text, 0, -1, 0xFF000000, false);
			graphics.text(font, text, 0, 0, textColor(s, main), false);
			pose.popMatrix();
		}
	}

	// ---------- the breaking sound ----------

	/** Each client tick: the breaking sound when an endangered piece (or a held item whose durability shows) wears down. */
	public static void tick() {
		Player player = cameraPlayer();
		if (!on() || player == null) {
			java.util.Arrays.fill(LAST, null);
			java.util.Arrays.fill(LAST_PICKED, null);
			return;
		}
		try {
			ModuleState s = Mods.state(ARMOR);
			if (s.bool(Armor.BREAK_SOUND) && breaking(s, player)) {
				player.makeSound(BREAKING);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/**
	 * As the original: slot by slot, remembering each one's stack, until one sounds (the rest are compared next tick); the item
	 * slots first, each remembering its own item every tick (one the hand slots already watch is left to them).
	 */
	private static boolean breaking(ModuleState s, Player player) {
		boolean sound = false;
		int wanted = ArmorPieces.wanted(s, ArmorPieces.ALL);
		if (wanted != 0) {
			pick(player, wanted);
		}
		int selected = player.getInventory().getSelectedSlot();
		for (int kind = 0; kind < ArmorPieces.KINDS; kind++) {
			int place = (wanted & 1 << kind) == 0 ? ArmorPieces.NONE : PICKED[kind];
			ItemStack current = place == ArmorPieces.NONE ? ItemStack.EMPTY : at(player, place);
			ItemStack last = LAST_PICKED[kind] == null ? ItemStack.EMPTY : LAST_PICKED[kind];
			LAST_PICKED[kind] = place == ArmorPieces.NONE ? null : current;
			boolean watched = place == selected && s.bool(Armor.MAINHAND_DURABILITY) || place == ArmorPieces.OFFHAND && s.bool(Armor.OFFHAND_DURABILITY);
			if (!watched && !current.isEmpty() && last.is(current.getItem()) && last.getDamageValue() != current.getDamageValue() && warning(s, current)) {
				sound = true;
			}
		}
		if (sound) {
			return true;
		}
		for (int i = 0; i < ALL_SLOTS.length; i++) {
			EquipmentSlot slot = ALL_SLOTS[i];
			ItemStack current = player.getItemBySlot(slot);
			ItemStack last = LAST[i] == null ? ItemStack.EMPTY : LAST[i];
			LAST[i] = current;
			boolean worn = last.getDamageValue() != current.getDamageValue() && warning(s, current);
			boolean swapped = slot == EquipmentSlot.MAINHAND && !last.is(current.getItem());
			boolean armor = slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND;
			boolean mainHand = slot == EquipmentSlot.MAINHAND && s.bool(Armor.MAINHAND_DURABILITY);
			boolean offHand = slot == EquipmentSlot.OFFHAND && s.bool(Armor.OFFHAND_DURABILITY);
			if (worn && !swapped && (armor || mainHand || offHand)) {
				return true;
			}
		}
		return false;
	}
}
