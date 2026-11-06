package com.flintclient.client.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.resource.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Draws what only the game can draw, for the HUD modules, on Minecraft 1.8.9: items (with the hotbar's durability bar and
 * count), the game's own sprites (the inventory's effect icons, the heart), the effect tile newer Minecraft draws behind an
 * effect's icon (1.8.9 has none: drawn here as newer Minecraft's, pixel for pixel), and text in Minecraft's font with its
 * shadow. Items are 1.8.9's item stacks (no stack is {@code null}); sprites are this class's {@link Sprite}s. Drawing goes
 * into a {@link Frame}, under its transform. Platform class (see {@link Clock}). Render thread.
 */
public final class ItemPainter {
	private static final Identifier INVENTORY = new Identifier("textures/gui/container/inventory.png");
	private static final Identifier ICONS = new Identifier("textures/gui/icons.png");
	private static final Sprite[] EFFECT_ICONS = new Sprite[64];
	private static final Sprite TILE = new Sprite(null, 0, 0, 0, 0, Sprite.TILE);
	private static final Sprite TILE_AMBIENT = new Sprite(null, 0, 0, 0, 0, Sprite.TILE_AMBIENT);
	private static final Sprite NOTHING = new Sprite(null, 0, 0, 0, 0, Sprite.NOTHING);
	private static final Sprite HEART = new Sprite(ICONS, 52 / 256F, 0, 61 / 256F, 9 / 256F, Sprite.TEXTURE);

	private ItemPainter() {
	}

	/** One of the game's sprites: a region of a texture, or the effect tile. */
	public static final class Sprite {
		static final int TEXTURE = 0;
		static final int TILE = 1;
		static final int TILE_AMBIENT = 2;
		static final int NOTHING = 3;
		final @Nullable Identifier texture;
		final float u0;
		final float v0;
		final float u1;
		final float v1;
		final int kind;

		Sprite(@Nullable Identifier texture, float u0, float v0, float u1, float v1, int kind) {
			this.texture = texture;
			this.u0 = u0;
			this.v0 = v0;
			this.u1 = u1;
			this.v1 = v1;
			this.kind = kind;
		}
	}

	// ---------- items ----------

	public static boolean empty(@Nullable Object stack) {
		return stack == null || ((ItemStack) stack).size <= 0;
	}

	/** 1.8.9 has no empty stack object: no item is {@code null}. */
	public static @Nullable Object none() {
		return null;
	}

	public static Object sample(String item, int count) {
		Item found = Item.byKey("minecraft:" + item);
		return new ItemStack(found != null ? found : Items.STONE_SWORD, count);
	}

	/** A sample splash potion for previews (1.8.9's splash potions are potion items with the splash bit in their data). */
	public static Object splashPotion(String potion) {
		int data;
		switch (potion) {
			case "strong_healing":
				data = 16421;
				break;
			case "long_strength":
				data = 16457;
				break;
			default:
				data = 16386;
				break;
		}
		return new ItemStack(Items.POTION, 1, data);
	}

	/** An item drawn {@code size} px square with, when {@code decorations}, the hotbar's durability bar and count. */
	public static void item(Frame frame, @Nullable Object stack, int x, int y, int size, @Nullable String count, boolean decorations) {
		if (empty(stack)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		frame.pushGame();
		try {
			GlStateManager.translatef(x, y, 0);
			GlStateManager.scalef(size / 16.0F, size / 16.0F, 1);
			GlStateManager.enableRescaleNormal();
			GlStateManager.enableDepthTest();
			Lighting.turnOnGui();
			mc.getItemRenderer().renderGuiItem((ItemStack) stack, 0, 0);
			if (decorations) {
				mc.getItemRenderer().renderGuiItemDecorations(mc.textRenderer, (ItemStack) stack, 0, 0, count);
			}
			Lighting.turnOff();
			GlStateManager.disableRescaleNormal();
		} finally {
			frame.popGame();
		}
	}

	public static void item(Frame frame, @Nullable Object stack, int x, int y) {
		item(frame, stack, x, y, 16, null, false);
	}

	// ---------- sprites and textures ----------

	/** One of the game's sprites by newer Minecraft's name (the heart; others draw nothing on 1.8.9). */
	public static Object sprite(String name) {
		return name.equals("hud/heart/full") ? HEART : NOTHING;
	}

	/** An effect's icon from the inventory's sheet, by its index (-1: none). */
	static Object effectIcon(int index) {
		if (index < 0 || index >= EFFECT_ICONS.length) {
			return NOTHING;
		}
		Sprite s = EFFECT_ICONS[index];
		if (s == null) {
			int u = index % 8 * 18;
			int v = 198 + index / 8 * 18;
			s = new Sprite(INVENTORY, u / 256F, v / 256F, (u + 18) / 256F, (v + 18) / 256F, Sprite.TEXTURE);
			EFFECT_ICONS[index] = s;
		}
		return s;
	}

	static Object tile(boolean ambient) {
		return ambient ? TILE_AMBIENT : TILE;
	}

	public static void sprite(Frame frame, Object sprite, int x, int y, int size, int argb) {
		Sprite s = (Sprite) sprite;
		if ((argb >>> 24) == 0 || size <= 0) {
			return;
		}
		switch (s.kind) {
			case Sprite.TEXTURE -> frame.blitGame(s.texture, x, y, x + size, y + size, s.u0, s.v0, s.u1, s.v1, argb);
			case Sprite.TILE, Sprite.TILE_AMBIENT -> tileShape(frame, x, y, size, s.kind == Sprite.TILE_AMBIENT, argb);
			default -> {
			}
		}
	}

	/**
	 * Newer Minecraft's effect tile, pixel for pixel at 24 units: a black (beacon: dark teal) rounded edge, a grey (teal) ring
	 * and a dark inside, scaled to {@code size} and faded by {@code argb}'s alpha.
	 */
	private static void tileShape(Frame frame, int x, int y, int size, boolean beacon, int argb) {
		int alpha = argb >>> 24;
		int edge = fade(beacon ? 0xFF005454 : 0xFF000000, alpha);
		int ring = fade(beacon ? 0xFF00A8A8 : 0xFF555555, alpha);
		int inside = fade(0xFF212121, alpha);
		float k = size / 24.0F;
		rect(frame, x, y, k, 2, 0, 22, 24, edge);
		rect(frame, x, y, k, 0, 2, 2, 22, edge);
		rect(frame, x, y, k, 22, 2, 24, 22, edge);
		rect(frame, x, y, k, 1, 1, 2, 2, edge);
		rect(frame, x, y, k, 22, 1, 23, 2, edge);
		rect(frame, x, y, k, 1, 22, 2, 23, edge);
		rect(frame, x, y, k, 22, 22, 23, 23, edge);
		rect(frame, x, y, k, 2, 1, 22, 23, ring);
		rect(frame, x, y, k, 1, 2, 23, 22, ring);
		rect(frame, x, y, k, 3, 2, 21, 22, inside);
		rect(frame, x, y, k, 2, 3, 22, 21, inside);
	}

	private static void rect(Frame frame, int x, int y, float k, int x0, int y0, int x1, int y1, int argb) {
		frame.fill(x + Math.round(x0 * k), y + Math.round(y0 * k), x + Math.round(x1 * k), y + Math.round(y1 * k), argb);
	}

	private static int fade(int argb, int alpha) {
		return (((argb >>> 24) * alpha / 255) << 24) | (argb & 0xFFFFFF);
	}

	/** A whole texture the game holds, drawn into the {@code w × h} box. */
	public static void texture(Frame frame, Object texture, int x, int y, int w, int h) {
		frame.blitGame((Identifier) texture, x, y, x + w, y + h, 0, 0, 1, 1, 0xFFFFFFFF);
	}

	/** Your own model (Player Model isn't on 1.8.9). */
	public static boolean player(Frame frame, int x, int y, int w, int h, boolean head) {
		return false;
	}

	// ---------- Minecraft's font, as the original mods draw ----------

	private static TextRenderer font() {
		return Minecraft.getInstance().textRenderer;
	}

	public static int width(String text) {
		return font().getWidth(text);
	}

	public static String cut(String text, int width) {
		return font().trim(text, width);
	}

	public static void text(Frame frame, String text, int x, int y, int argb, boolean shadow) {
		frame.gameText(text, x, y, argb, shadow);
	}
}
