package com.flintclient.client.platform;

import com.flintclient.client.control.platform.Mc263HudExtras;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

/**
 * Draws what only the game can draw, for the HUD modules: items (with the hotbar's durability bar and count), the game's GUI
 * sprites (effect icons and tiles, hearts), textures it holds (a pack's icon), your own model, and text in Minecraft's own font
 * with its shadow, as the original mods draw it. Items, sprites and textures are the engine's own objects, held as
 * {@link Object}. Drawing goes into a {@link Frame}, under its current transform. Platform class (see {@link Clock}). Render
 * thread.
 */
public final class ItemPainter {
	private ItemPainter() {
	}

	// ---------- items ----------

	/** Whether {@code stack} is no item (null, or the engine's empty stack). */
	public static boolean empty(@Nullable Object stack) {
		return stack == null || ((ItemStack) stack).isEmpty();
	}

	/** The engine's empty stack. */
	public static Object none() {
		return ItemStack.EMPTY;
	}

	/** A sample stack for previews, by the item's name; made once items exist (not before the game has loaded). */
	public static Object sample(String item, int count) {
		return new ItemStack(switch (item) {
			case "cobblestone" -> Items.COBBLESTONE;
			case "torch" -> Items.TORCH;
			case "iron_pickaxe" -> Items.IRON_PICKAXE;
			case "bread" -> Items.BREAD;
			case "ender_pearl" -> Items.ENDER_PEARL;
			case "shield" -> Items.SHIELD;
			case "oak_log" -> Items.OAK_LOG;
			case "redstone" -> Items.REDSTONE;
			case "glowstone_dust" -> Items.GLOWSTONE_DUST;
			default -> Items.STONE;
		}, count);
	}

	/** A sample splash potion for previews: {@code strong_healing}, {@code long_strength} or {@code swiftness}. */
	public static Object splashPotion(String potion) {
		return PotionContents.createItemStack(Items.SPLASH_POTION, switch (potion) {
			case "strong_healing" -> Potions.STRONG_HEALING;
			case "long_strength" -> Potions.LONG_STRENGTH;
			default -> Potions.SWIFTNESS;
		});
	}

	/**
	 * An item drawn {@code size} px square at ({@code x}, {@code y}) with, when {@code decorations}, the hotbar's durability bar
	 * and count ({@code count}: the count text, null for the game's own, shown above 1).
	 */
	public static void item(Frame frame, Object stack, int x, int y, int size, @Nullable String count, boolean decorations) {
		GuiGraphicsExtractor graphics = frame.graphics();
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(size / 16.0F, size / 16.0F);
		graphics.item((ItemStack) stack, 0, 0);
		if (decorations) {
			graphics.itemDecorations(Minecraft.getInstance().font, (ItemStack) stack, 0, 0, count);
		}
		pose.popMatrix();
	}

	/** An item at its own 16-unit size at ({@code x}, {@code y}), without decorations, as the game's HUD draws one. */
	public static void item(Frame frame, Object stack, int x, int y) {
		frame.graphics().item((ItemStack) stack, x, y);
	}

	// ---------- sprites and textures ----------

	/** One of the game's GUI sprites by its name ({@code hud/heart/full}). */
	public static Object sprite(String name) {
		return Identifier.withDefaultNamespace(name);
	}

	/** A GUI sprite (an effect icon, a heart) {@code size} square, tinted by {@code argb} (alpha fades it). */
	public static void sprite(Frame frame, Object sprite, int x, int y, int size, int argb) {
		frame.graphics().blitSprite(RenderPipelines.GUI_TEXTURED, (Identifier) sprite, x, y, size, size, argb);
	}

	/** A whole texture the game holds (a pack's icon) drawn into the {@code w × h} box. */
	public static void texture(Frame frame, Object texture, int x, int y, int w, int h) {
		frame.graphics().blit(RenderPipelines.GUI_TEXTURED, (Identifier) texture, x, y, 0.0F, 0.0F, w, h, w, h);
	}

	/** You, as the inventory draws you, in the {@code w × h} box; false with no player. */
	public static boolean player(Frame frame, int x, int y, int w, int h, boolean head) {
		return Mc263HudExtras.drawPlayer(frame.graphics(), x, y, w, h, head);
	}

	// ---------- Minecraft's font, as the original mods draw ----------

	/** {@code text}'s width in Minecraft's font, in GUI units. */
	public static int width(String text) {
		return Minecraft.getInstance().font.width(text);
	}

	/** The start of {@code text} that fits in {@code width} units. */
	public static String cut(String text, int width) {
		return Minecraft.getInstance().font.plainSubstrByWidth(text, width);
	}

	/** {@code text} in Minecraft's font with its top left at ({@code x}, {@code y}), with Minecraft's shadow when {@code shadow}. */
	public static void text(Frame frame, String text, int x, int y, int argb, boolean shadow) {
		frame.graphics().text(Minecraft.getInstance().font, text, x, y, argb, shadow);
	}
}
