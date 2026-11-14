package com.flintclient.client.control.feature;

import com.flintclient.client.module.social.SocialLink.Item;
import com.flintclient.client.platform.Frame;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where the wardrobe (the in-game Cosmetics window, {@code control/rshift/WardrobeScreen}) meets Flint's 3D cosmetics
 * renderer (wings, hats, auras, pets, shields, gloves): the renderer registers itself here, and the
 * wardrobe asks it for live mini-renders and tells it what its preview player wears. Until a renderer is registered (or
 * where one can't draw a kind) the wardrobe shows the item's thumbnail, or a placeholder of its category.
 *
 * <p>While the wardrobe's preview player is drawn ({@link #wearing} is set around that one draw), a renderer that adds
 * cosmetics to players draws these instead of the account's worn ones, so the player shows the selection before it is
 * equipped. Render thread; platform-free.
 */
public final class CosmeticPreview {
	private CosmeticPreview() {
	}

	/** What a 3D cosmetics renderer offers the wardrobe. Every call is on the render thread and must never throw. */
	public interface Renderer {
		/**
		 * Draws {@code item} alone, turned {@code yaw} degrees, fitted into the pixel box ({@code x}, {@code y}, {@code w},
		 * {@code h}) of {@code frame}'s canvas: a tile's live mini-render. False when it can't (yet: its files are loading), and
		 * the tile shows the thumbnail instead.
		 */
		boolean thumbnail(Frame frame, Item item, int x, int y, int w, int h, float yaw);

		/** Whether it draws this kind ({@link CosmeticKinds}) on players at all. */
		boolean draws(String kind);

		/**
		 * Plays {@code emote} once on the wardrobe's preview player (the next draws, from now): false when it can't. Optional.
		 */
		default boolean previewEmote(Item emote) {
			return false;
		}
	}

	private static volatile @Nullable Renderer renderer;
	private static Map<String, Item> wearing = Map.of();
	private static boolean drawing;

	/** The 3D cosmetics renderer registers itself (null: unregisters). */
	public static void register(@Nullable Renderer r) {
		renderer = r;
	}

	public static @Nullable Renderer renderer() {
		return renderer;
	}

	/**
	 * True only while the wardrobe's preview player is being drawn; {@link #wearing} is then slot → the item it wears
	 * ("cape", "back", "head", "aura", "pet", "shield", "hands"), a slot left out wearing nothing.
	 */
	public static boolean drawingPreview() {
		return drawing;
	}

	public static Map<String, Item> wearing() {
		return wearing;
	}

	/** The wardrobe draws its preview player wearing {@code outfit} in {@code draw}. */
	public static void drawWearing(Map<String, Item> outfit, Runnable draw) {
		Map<String, Item> before = wearing;
		boolean was = drawing;
		wearing = Collections.unmodifiableMap(new LinkedHashMap<>(outfit));
		drawing = true;
		try {
			draw.run();
		} finally {
			wearing = before;
			drawing = was;
		}
	}
}
