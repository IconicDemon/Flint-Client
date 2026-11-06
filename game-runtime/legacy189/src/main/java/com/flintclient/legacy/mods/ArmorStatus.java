/*
 * Adapted from uku's Armor HUD (https://github.com/uku3lig/armor-hud), a fork of BerdinskiyBear's Armor HUD.
 * Copyright (c) 2021 BerdinskiyBear. MIT License: see META-INF/licenses/MIT-ukus-armor-hud.txt.
 */
package com.flintclient.legacy.mods;

import com.flintclient.client.control.feature.ArmorLayout;
import com.flintclient.client.control.feature.ArmorPieces;
import com.flintclient.client.control.model.ModuleSettings.Armor;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.Orientation;
import com.flintclient.legacy.module.HudBackend;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.SwordItem;
import net.minecraft.resource.Identifier;

/**
 * Armor, as uku's Armor HUD on every line: the armour you wear in a strip of hotbar slots (Minecraft's own hotbar, cut to as
 * many slots as there are pieces), placed against the hotbar, the top centre, a top or bottom corner or the middle of a side,
 * with the offsets added; or, Free, wherever the HUD editor put it, at its size there. Each piece shows its durability as
 * Minecraft's bar or as text (at the text's own size, bold if asked, in the bar's colour or the chosen one), and a warning
 * sign that bobs on pieces about to break; slots after the armour can show a sword, pickaxe, axe and bow from your hand and
 * hotbar ({@link ArmorPieces}); the held item's durability can show over its hotbar slot (unless a slot shows it). The
 * geometry ({@link ArmorLayout}), the order of the drawing and the colours are the 26.x module's ({@code Mc263ArmorHud}).
 * 1.8.9 has no offhand, shields, tridents or maces (their switches say so), no rounded slot sprites, no effect icons,
 * subtitles or armour breaking sound, so those settings wait out of sight. Drawn right after the hotbar (F1 hides it with the
 * hotbar). The 1.8.9 backend of Armor.
 */
public final class ArmorStatus extends HudBackend {
	private static final int STEP = ArmorLayout.STEP;
	private static final int SIZE = ArmorLayout.SIZE;
	private static final int HOTBAR_OFFSET = 98;
	private static final Identifier WIDGETS = new Identifier("textures/gui/widgets.png");
	/** The warning sign: Flint's own picture, which 1.8.9's resource manager can't see, so it is read from Flint's jar. */
	private static com.flintclient.client.platform.Texture warning;
	private static boolean warningRead;
	/** Each armour slot's empty outline (helmet first); the item slots have none on 1.8.9. */
	private static final Identifier[] EMPTY = {new Identifier("textures/items/empty_armor_slot_helmet.png"),
		new Identifier("textures/items/empty_armor_slot_chestplate.png"), new Identifier("textures/items/empty_armor_slot_leggings.png"),
		new Identifier("textures/items/empty_armor_slot_boots.png")};
	private static final int TOP_CENTER = Armor.TOP_CENTER;
	private static final int TOP = Armor.TOP;
	private static final int VERT_CENTER = Armor.VERTICAL_CENTER;
	private static final int HOTBAR_ANCHOR = Armor.HOTBAR;
	private static final int LEFT = 0;
	private static final int RIGHT = 1;
	private static final int ALWAYS = 0;
	private static final int IF_ANY_PRESENT = 1;
	private static final int NOT_EMPTY = 2;
	private static final int BAR = ArmorPieces.BAR;
	private static final int TEXT_LEFT = 1;
	private static final int TEXT_RIGHT = 2;
	private static final int TEXT_ABOVE = 3;
	private static final int TEXT_BELOW = 4;
	private static final int TEXT_CUSTOM = 1;
	/** What the item slots come from on the strip: the armour pieces are 0 to 3. */
	private static final int FIRST_KIND = 4;
	private static final Random RANDOM = new Random();

	private final ItemStack[] items = new ItemStack[ArmorPieces.MAX_SLOTS];
	private final int[] from = new int[ArmorPieces.MAX_SLOTS];
	/** The hotbar places the strip's item slots show (bits). */
	private int shownPlaces;
	private final int[] placeKinds = new int[ArmorPieces.PLACES];
	private final int[] picked = new int[ArmorPieces.KINDS];
	/** The strip's rectangle against its anchor (x, y, w, h). */
	private final int[] rect = new int[4];
	private final ArmorLayout layout = new ArmorLayout();
	private final ArmorPieces.Texts texts = new ArmorPieces.Texts();

	public ArmorStatus() {
		super("armor", "armorstatus", "displayStyle", "offhandSlotBehavior", "pushBossbars", "pushStatusEffectIcons", "pushSubtitles",
			"playBreakSound");
	}

	/** Fills {@link #items} with the slots to show, in order, and returns how many; {@link #shownPlaces} says which hotbar items they include. */
	private int armorItems(ModuleState s, PlayerEntity player) {
		int shown = s.option(Armor.WIDGET_SHOWN);
		int n = 0;
		boolean any = false;
		shownPlaces = 0;
		for (int i = 0; i < 4; i++) {
			// 1.8.9 keeps the boots in slot 0 and the helmet in slot 3.
			ItemStack stack = player.inventory.getArmor(3 - i);
			any |= stack != null;
			if (keep(s, shown, stack)) {
				items[n] = stack;
				from[n] = i;
				n++;
			}
		}
		int wanted = ArmorPieces.wanted(s, ArmorPieces.ALL_1_8_9);
		if (wanted != 0) {
			pick(player, wanted);
			for (int kind = 0; kind < ArmorPieces.KINDS; kind++) {
				if ((wanted & 1 << kind) == 0) {
					continue;
				}
				int place = picked[kind];
				ItemStack stack = place == ArmorPieces.NONE ? null : player.inventory.getItem(place);
				any |= stack != null;
				if (keep(s, shown, stack)) {
					items[n] = stack;
					from[n] = FIRST_KIND + kind;
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
				ItemStack stack = items[a];
				items[a] = items[b];
				items[b] = stack;
				int f = from[a];
				from[a] = from[b];
				from[b] = f;
			}
		}
		return n;
	}

	private static boolean keep(ModuleState s, int shown, ItemStack stack) {
		switch (shown) {
			case ALWAYS:
			case IF_ANY_PRESENT:
				return true;
			case NOT_EMPTY:
				return stack != null;
			default:
				return warning(s, stack);
		}
	}

	/** Sorts the hotbar's items into kinds and picks each wanted kind's place ({@link #picked}); 1.8.9 has no offhand. */
	private void pick(PlayerEntity player, int wanted) {
		for (int slot = 0; slot < ArmorPieces.HOTBAR; slot++) {
			placeKinds[slot] = kinds(player.inventory.getItem(slot));
		}
		placeKinds[ArmorPieces.OFFHAND] = 0;
		ArmorPieces.pick(placeKinds, player.inventory.selectedSlot, wanted, picked);
	}

	/** {@code stack}'s kinds, as bits ({@link ArmorPieces}). */
	private static int kinds(ItemStack stack) {
		if (stack == null) {
			return 0;
		}
		Item item = stack.getItem();
		if (item instanceof SwordItem) {
			return 1 << ArmorPieces.SWORD;
		}
		if (item instanceof PickaxeItem) {
			return 1 << ArmorPieces.PICKAXE;
		}
		if (item instanceof AxeItem) {
			return 1 << ArmorPieces.AXE;
		}
		if (item instanceof BowItem) {
			return 1 << ArmorPieces.BOW;
		}
		return 0;
	}

	private static boolean damageable(ItemStack stack) {
		return stack != null && stack.isDamageable() && stack.getMaxDamage() > 0;
	}

	private static boolean warning(ModuleState s, ItemStack stack) {
		if (!damageable(stack)) {
			return false;
		}
		return ArmorPieces.endangered(stack.getDamage(), stack.getMaxDamage(), s.integer(Armor.MIN_PERCENT) / 100.0, s.integer(Armor.MIN_VALUE));
	}

	/** Minecraft's durability bar colour for {@code stack}, as 0xRRGGBB (green when new, red when nearly broken). */
	private static int barColor(ItemStack stack) {
		int j = (int) Math.round(255.0 - stack.getDamage() * 255.0 / stack.getMaxDamage());
		j = Math.max(0, Math.min(255, j));
		return (255 - j) << 16 | j << 8;
	}

	/** The durability text's colour for {@code stack}: its bar's (green to red) or the chosen one. */
	private static int textColor(ModuleState s, ItemStack stack) {
		return 0xFF000000 | (s.option(Armor.TEXT_COLOR_MODE) == TEXT_CUSTOM ? s.rgb(Armor.TEXT_COLOR) : barColor(stack));
	}

	private static boolean top(ModuleState s) {
		int anchor = s.option(Armor.ANCHOR);
		return anchor == TOP || anchor == TOP_CENTER;
	}

	/** A horizontal strip's durability text and warning signs: under the strip (Below, or Auto at the top) or over it. */
	private static boolean extrasBelow(ModuleState s) {
		int position = s.option(Armor.TEXT_POSITION);
		return position == TEXT_ABOVE ? false : position == TEXT_BELOW || top(s);
	}

	/** A vertical strip's side for them: as chosen, or (Auto) away from the hotbar, or away from the screen's edge. */
	private static int extrasSide(ModuleState s) {
		int position = s.option(Armor.TEXT_POSITION);
		if (position == TEXT_LEFT) {
			return LEFT;
		}
		if (position == TEXT_RIGHT) {
			return RIGHT;
		}
		int side = s.option(Armor.SIDE);
		return s.option(Armor.ANCHOR) == HOTBAR_ANCHOR ? side : side == LEFT ? RIGHT : LEFT;
	}

	/** The strip's rectangle on a {@code guiWidth × guiHeight} screen for {@code count} pieces, into {@link #rect}. */
	private boolean widgetRect(ModuleState s, int count, int guiWidth, int guiHeight) {
		if (count == 0) {
			return false;
		}
		int anchor = s.option(Armor.ANCHOR);
		int side = s.option(Armor.SIDE);
		boolean flip = anchor == HOTBAR_ANCHOR && side == LEFT || anchor != HOTBAR_ANCHOR && side == RIGHT;
		int sideMultiplier = flip ? -1 : 1;
		int sideOffsetMultiplier = flip ? -1 : 0;
		int textureWidth = SIZE + (count - 1) * STEP;
		boolean vertical = s.choice(Armor.LAYOUT) == Orientation.VERTICAL;
		int width = vertical ? SIZE : textureWidth;
		int height = vertical ? textureWidth : SIZE;
		int x = s.integer(Armor.OFFSET_X) * sideMultiplier;
		if (anchor == TOP_CENTER) {
			x += (guiWidth - width) / 2;
		} else if (anchor == HOTBAR_ANCHOR) {
			x += guiWidth / 2 + HOTBAR_OFFSET * sideMultiplier + width * sideOffsetMultiplier;
		} else {
			x += (width - guiWidth) * sideOffsetMultiplier;
		}
		int offsetY = s.integer(Armor.OFFSET_Y);
		int y;
		if (anchor == TOP_CENTER || anchor == TOP) {
			y = offsetY;
		} else if (anchor == VERT_CENTER) {
			y = (guiHeight - height) / 2 + offsetY;
		} else {
			y = guiHeight - height - offsetY;
		}
		rect[0] = x;
		rect[1] = y;
		rect[2] = width;
		rect[3] = height;
		return true;
	}

	/**
	 * Lays out the {@code count} slots in {@link #items} ({@link #layout}: the box with their text and warning signs) and places
	 * them: against the anchor ({@link #rect} is the strip) or, Free, by the HUD placement at its size. False when there is
	 * nothing to show.
	 */
	private boolean layout(ModuleState s, TextRenderer font, int count, int guiWidth, int guiHeight) {
		if (count == 0) {
			return false;
		}
		int display = s.option(Armor.DURABILITY);
		boolean bold = s.bool(Armor.TEXT_BOLD);
		int widest = 0;
		if (display != BAR) {
			for (int i = 0; i < count; i++) {
				if (damageable(items[i])) {
					widest = Math.max(widest, font.getWidth(ArmorPieces.widest(display, items[i].getMaxDamage(), bold)));
				}
			}
		}
		boolean vertical = s.choice(Armor.LAYOUT) == Orientation.VERTICAL;
		layout.measure(count, vertical, display != BAR, widest, s.integer(Armor.TEXT_SCALE), s.bool(Armor.WARNING), s.integer(Armor.BOB));
		if (Armor.anchored(s)) {
			widgetRect(s, count, guiWidth, guiHeight);
			layout.side(vertical ? extrasSide(s) == RIGHT : extrasBelow(s));
			layout.scale = 1;
			layout.left = rect[0] - layout.stripX;
			layout.top = rect[1] - layout.stripY;
			layout.drawnWidth = layout.boxWidth;
			layout.drawnHeight = layout.boxHeight;
			return true;
		}
		layout.place(state().placement(), guiWidth, guiHeight, com.flintclient.client.platform.Frame.windowGuiScale());
		int position = s.option(Armor.TEXT_POSITION);
		boolean after;
		if (vertical) {
			after = position == TEXT_LEFT ? false : position == TEXT_RIGHT || layout.autoAfter(guiWidth, guiHeight);
		} else {
			after = position == TEXT_ABOVE ? false : position == TEXT_BELOW || layout.autoAfter(guiWidth, guiHeight);
		}
		layout.side(after);
		return true;
	}

	/**
	 * Armor in the HUD editor: its box (strip, text and warning signs) into {@code out} as {left, top, width, height, anchored}:
	 * where its own anchor puts it (GUI units, anchored = 1) or, Free, its size at full size (anchored = 0: its HUD placement
	 * places it). False when it shows nothing; with {@code placeholder}, the box four armour slots would take instead.
	 */
	public boolean box(float[] out, boolean placeholder) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return false;
		}
		ModuleState s = state();
		try {
			int count = armorItems(s, mc.player);
			if (count == 0) {
				if (!placeholder) {
					return false;
				}
				count = 4;
			}
			if (!layout(s, mc.textRenderer, count, com.flintclient.client.platform.HudData.guiWidth(), com.flintclient.client.platform.HudData.guiHeight())) {
				return false;
			}
			out[0] = layout.left;
			out[1] = layout.top;
			out[2] = layout.boxWidth;
			out[3] = layout.boxHeight;
			out[4] = Armor.anchored(s) ? 1 : 0;
			return true;
		} finally {
			java.util.Arrays.fill(items, null);
		}
	}

	/** For QA tours and logs: how Armor is placed now, its box on screen (GUI units) and what its slots show. */
	public static String describe() {
		ArmorStatus armor = com.flintclient.legacy.Flint.modules().armorStatus;
		ModuleState s = armor.state();
		float[] at = new float[5];
		boolean shown = armor.box(at, false);
		StringBuilder out = new StringBuilder(Armor.anchored(s) ? "anchor=" + Armor.ANCHOR.keys().get(s.option(Armor.ANCHOR)) : "free " + s.placement());
		out.append(shown ? String.format(java.util.Locale.ROOT, " box=%.1f,%.1f %.0fx%.0f", at[0], at[1], at[2], at[3]) : " box=none");
		PlayerEntity player = Minecraft.getInstance().player;
		if (player != null) {
			int count = armor.armorItems(s, player);
			out.append(" slots=");
			for (int i = 0; i < count; i++) {
				ItemStack stack = armor.items[i];
				out.append(i == 0 ? "" : ",").append(stack == null ? "empty" : stack.getItem().getClass().getSimpleName())
					.append(damageable(stack) ? "(" + (stack.getMaxDamage() - stack.getDamage()) + ")" : "");
			}
			out.append(" strip-places=").append(Integer.toBinaryString(armor.shownPlaces));
			java.util.Arrays.fill(armor.items, null);
		}
		return out.toString();
	}

	/** After the hotbar (GUI units, Minecraft's GUI projection): the strip, its items, their durability and warning signs. */
	public void draw(int guiWidth, int guiHeight) {
		Minecraft mc = Minecraft.getInstance();
		if (!enabled() || mc.player == null) {
			return;
		}
		ModuleState s = state();
		try {
			drawArmor(mc, s, mc.player, guiWidth, guiHeight);
			drawHand(mc, s, mc.player, guiWidth, guiHeight);
		} finally {
			java.util.Arrays.fill(items, null);
			GlStateManager.color4f(1, 1, 1, 1);
			GlStateManager.enableBlend();
		}
	}

	private void drawArmor(Minecraft mc, ModuleState s, PlayerEntity player, int guiWidth, int guiHeight) {
		int count = armorItems(s, player);
		if (!layout(s, mc.textRenderer, count, guiWidth, guiHeight)) {
			return;
		}
		if (Armor.anchored(s)) {
			drawStrip(mc, s, count, rect[0], rect[1]);
			return;
		}
		// Free: the box where the HUD editor put it, at its size there.
		GlStateManager.pushMatrix();
		try {
			GlStateManager.translatef(layout.left, layout.top, 0);
			GlStateManager.scalef(layout.scale, layout.scale, 1);
			drawStrip(mc, s, count, layout.stripX, layout.stripY);
		} finally {
			GlStateManager.popMatrix();
		}
	}

	/** The strip of {@code count} slots with its top-left corner at {@code (rectX, rectY)}, laid out by {@link #layout}. */
	private void drawStrip(Minecraft mc, ModuleState s, int count, int rectX, int rectY) {
		boolean vertical = layout.vertical;
		int textureWidth = SIZE + (count - 1) * STEP;
		GlStateManager.enableBlend();
		GlStateManager.color4f(1, 1, 1, 1);
		mc.getTextureManager().bind(WIDGETS);
		GlStateManager.pushMatrix();
		GlStateManager.translatef(rectX, rectY, 0);
		if (vertical) {
			GlStateManager.rotatef(90, 0, 0, 1);
			GlStateManager.translatef(0, -22, 0);
		}
		// Minecraft's hotbar, cut to the pieces: its left part (its slots again past its ninth: the armour and the item slots can
		// make eleven), then its right cap.
		int runX = 0;
		int runU = 0;
		for (int left = textureWidth - 3; left > 0; ) {
			int w = Math.min(left, 1 + 8 * STEP - runU);
			GuiElement.drawTexture(runX, 0, runU, 0, w, 22, 256, 256);
			runX += w;
			left -= w;
			runU = 1;
		}
		GuiElement.drawTexture(textureWidth - 3, 0, 179, 0, 3, 22, 256, 256);
		GlStateManager.popMatrix();

		TextRenderer font = mc.textRenderer;
		boolean after = layout.after;
		boolean shadow = s.bool(Armor.TEXT_SHADOW);
		boolean bold = s.bool(Armor.TEXT_BOLD);
		boolean icons = s.bool(Armor.ICONS);
		int shown = s.option(Armor.WIDGET_SHOWN);
		boolean emptySlots = shown == ALWAYS || shown == IF_ANY_PRESENT;
		int display = s.option(Armor.DURABILITY);
		boolean warningShown = s.bool(Armor.WARNING);
		int bob = s.integer(Armor.BOB);
		int lineHeight = layout.lineHeight;
		for (int i = 0; i < count; i++) {
			ItemStack stack = items[i];
			int x = rectX;
			int y = rectY;
			if (vertical) {
				y += STEP * i;
			} else {
				x += STEP * i;
			}
			int slotY = y;
			if (icons && emptySlots && stack == null && from[i] < EMPTY.length) {
				mc.getTextureManager().bind(EMPTY[from[i]]);
				GlStateManager.color4f(1, 1, 1, 1);
				GuiElement.drawTexture(x + 3, y + 3, 0, 0, 16, 16, 16, 16);
			}
			slot(mc, x + 3, y + 3, stack);
			if (after && vertical) {
				x += 22;
			}
			if (display != BAR && damageable(stack)) {
				String text = texts.get(i, display, stack.getDamage(), stack.getMaxDamage(), bold);
				int width = layout.scaled(font.getWidth(text));
				int ink = textColor(s, stack);
				if (!vertical) {
					// Over or under the slot, centred on it: every slot's text on the same line.
					int textY = after ? slotY + SIZE : slotY - lineHeight;
					text(font, text, ArmorLayout.centred(x, width), textY, ink, shadow, layout.textScale);
				} else {
					int textWidth = width + 2;
					if (!after) {
						x -= textWidth;
					}
					text(font, text, x + 1, layout.besideY(slotY), ink, shadow, layout.textScale);
					if (after) {
						x += textWidth;
					}
				}
			}
			if (warningShown && warning(s, stack)) {
				if (bob != 0) {
					y += (int) (RANDOM.nextInt(bob) - Math.ceil(bob / 2.0F));
				}
				if (!bindWarning()) {
					continue;
				}
				GlStateManager.enableBlend();
				GlStateManager.color4f(1, 1, 1, 1);
				if (!vertical) {
					// Past the line of text, as uku's Armor HUD puts it.
					int band = layout.textBand();
					int signY = y + (after ? SIZE + band : -band - 10);
					GuiElement.drawTexture(x + 7, signY + 1, 0, 0, 8, 8, 8, 8);
				} else {
					if (!after) {
						x -= 10;
					}
					GuiElement.drawTexture(x + 1, y + 7, 0, 0, 8, 8, 8, 8);
				}
			}
		}
	}

	/** Durability text at {@code (x, y)}, at {@code scale} times Minecraft's size (its top-left corner stays put). */
	private static void text(TextRenderer font, String text, int x, int y, int ink, boolean shadow, float scale) {
		if (scale == 1.0F) {
			font.draw(text, x, y, ink, shadow);
		} else {
			GlStateManager.pushMatrix();
			GlStateManager.translatef(x, y, 0);
			GlStateManager.scalef(scale, scale, 1);
			font.draw(text, 0, 0, ink, shadow);
			GlStateManager.popMatrix();
		}
		GlStateManager.color4f(1, 1, 1, 1);
	}

	private static boolean bindWarning() {
		if (!warningRead) {
			warningRead = true;
			try {
				com.flintclient.client.render.Pixels p = com.flintclient.client.platform.Images.read("textures/gui/armor_warning.png");
				if (p != null) {
					warning = com.flintclient.client.platform.Texture.upload("armor_warning", p.width(), p.height(), p.argb(), false);
				}
			} catch (java.io.IOException e) {
				com.flintclient.legacy.Flint.LOG.warn("Flint: no armor warning sign", e);
			}
		}
		if (warning == null) {
			return false;
		}
		GlStateManager.bindTexture(warning.glId());
		return true;
	}

	/** One item as the hotbar draws it: the item and its decorations (bar and count). */
	private static void slot(Minecraft mc, int x, int y, ItemStack stack) {
		if (stack == null) {
			return;
		}
		GlStateManager.enableRescaleNormal();
		GlStateManager.enableBlend();
		Lighting.turnOnGui();
		mc.getItemRenderer().renderGuiItem(stack, x, y);
		mc.getItemRenderer().renderGuiItemDecorations(mc.textRenderer, stack, x, y, null);
		Lighting.turnOff();
		GlStateManager.disableRescaleNormal();
		GlStateManager.disableLighting();
		GlStateManager.enableBlend();
	}

	/** The held item's durability over its hotbar slot (Show main hand durability, as text), unless the strip shows it. */
	private void drawHand(Minecraft mc, ModuleState s, PlayerEntity player, int guiWidth, int guiHeight) {
		ItemStack main = player.inventory.getSelectedItem();
		int display = s.option(Armor.DURABILITY);
		if (!damageable(main) || !s.bool(Armor.MAINHAND_DURABILITY) || display == BAR) {
			return;
		}
		// What the strip's item slots show already isn't shown again over the hotbar.
		armorItems(s, player);
		java.util.Arrays.fill(items, null);
		int selected = player.inventory.selectedSlot;
		if ((shownPlaces & 1 << selected) != 0) {
			return;
		}
		TextRenderer font = mc.textRenderer;
		float scale = Math.max(50, Math.min(200, s.integer(Armor.TEXT_SCALE))) / 100.0F;
		int x = guiWidth / 2 - 91 + selected * 20 + 11;
		int y = guiHeight - 22 - 1;
		String text = texts.get(ArmorPieces.MAIN_HAND_TEXT, display, main.getDamage(), main.getMaxDamage(), s.bool(Armor.TEXT_BOLD));
		int textX = x - Math.round(font.getWidth(text) * scale) / 2;
		GlStateManager.pushMatrix();
		GlStateManager.translatef(textX, y, 0);
		GlStateManager.scalef(scale, scale, 1);
		font.draw(text, 1, 0, 0xFF000000);
		font.draw(text, -1, 0, 0xFF000000);
		font.draw(text, 0, 1, 0xFF000000);
		font.draw(text, 0, -1, 0xFF000000);
		font.draw(text, 0, 0, textColor(s, main));
		GlStateManager.popMatrix();
	}
}
