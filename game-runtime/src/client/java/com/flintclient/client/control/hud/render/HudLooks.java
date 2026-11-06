package com.flintclient.client.control.hud.render;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the game tells a few HUD views, in the same shape on every engine: the block and the mob under the crosshair, the item
 * just taken in hand, a resource pack and a row of the potion list. The engine's side ({@code platform.HudData}) fills them;
 * an item or a texture is the engine's own object, held as an {@link Object} and drawn by {@code platform.ItemPainter}.
 */
public final class HudLooks {
	private HudLooks() {
	}

	/** What Block Indicator shows. */
	public static final class Block {
		/** The block's item (the engine's item stack), or an empty one. */
		public @Nullable Object icon;
		public String name = "";
		/** How far it's mined, 0 to 100, or -1 when it isn't being mined. */
		public int percent = -1;
	}

	/** What Damage Indicator shows. */
	public static final class Target {
		/** The mob's entity id: a new one snaps the health bar instead of easing it. */
		public int id;
		public String name = "";
		public float health;
		public float maxHealth;
		public float absorption;
		public int armor;
	}

	/** The item just taken in hand, as Item Info lists it. */
	public static final class Held {
		public List<String> lines = new ArrayList<>();
		/** Per line: its colour (0xRRGGBB). */
		public List<Integer> colors = new ArrayList<>();
		/** The stack's count after the name ("  x16"), empty when not shown, and its own colour. */
		public String count = "";
		public int countColor;
		/** When it was taken in hand ({@link System#nanoTime()}). */
		public long since;
		/** Bumped whenever the lines change. */
		public int revision;
	}

	/** One resource pack: its id, its name, its description's first line and its icon (the engine's texture; null: none). */
	public record Pack(String id, String title, String description, @Nullable Object icon) {
	}

	/** One row of the potion list: the potion to draw (the engine's item stack), how many, and whether it's longer or stronger. */
	public record PotionRow(Object icon, int count, boolean longer, boolean stronger) {
	}
}
