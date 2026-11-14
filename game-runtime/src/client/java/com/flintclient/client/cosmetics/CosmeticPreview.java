package com.flintclient.client.cosmetics;

import com.google.gson.JsonObject;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The small API the Cosmetics window (its 3D "loadout" preview) uses to show 3D cosmetics on the player before they are worn:
 * on a Minecraft line with the 3D renderer (26.3 for now) the items show on you wherever you are drawn, the window's player
 * doll included ({@code InventoryScreen.extractEntityInInventoryFollowsMouse} or any other way of drawing your player), over what
 * you wear, until {@link #clear}. On other lines {@link #available} is false and every call does nothing.
 *
 * <p>An item is a Store item as the Store and the Flint app describe it: {@code {"kind": "wings", "id" | "cosmetic_id": "…",
 * "asset": {"model": {…}}}} (or {@code "model"} at the top), see {@link ModelItem}. Its files come from the Store's hosts only,
 * checked against their SHA-256; loading takes a moment the first time ({@link #status}). Game thread.
 *
 * <pre>
 * if (CosmeticPreview.available()) {
 *     CosmeticPreview.show(List.of(selectedItem));     // then draw the player doll as usual
 *     String s = CosmeticPreview.status(selectedItem); // "loading", "ready", "failed: …", "unsupported"
 * }
 * CosmeticPreview.emote(emoteItem);                    // plays it on you (the doll shows it too)
 * CosmeticPreview.wheel(equippedEmotes);              // the emote wheel's 8 slots (Store emote items or null)
 * CosmeticPreview.clear();                             // when the window closes
 * </pre>
 */
public final class CosmeticPreview {
	/** What the line's renderer provides. */
	public interface Backend {
		String show(List<JsonObject> items);

		void clear();

		String status(JsonObject item);

		boolean emote(JsonObject emote);

		void stopEmote();

		void wheel(List<@Nullable JsonObject> slots);
	}

	private static volatile @Nullable Backend backend;

	private CosmeticPreview() {
	}

	/** Called by the line's renderer once it is ready. */
	public static void install(Backend b) {
		backend = b;
	}

	/** Whether this game draws 3D cosmetics (and so can preview them). */
	public static boolean available() {
		return backend != null;
	}

	/** Shows {@code items} on you (one per slot; an empty list shows nothing extra). Answers a line for the log. */
	public static String show(List<JsonObject> items) {
		Backend b = backend;
		return b == null ? "unavailable" : b.show(items);
	}

	/** Takes the preview off: you show what you wear. */
	public static void clear() {
		Backend b = backend;
		if (b != null) {
			b.clear();
		}
	}

	/** {@code "loading"}, {@code "ready"}, {@code "failed: reason"}, {@code "unsupported"} or {@code "unavailable"}. */
	public static String status(JsonObject item) {
		Backend b = backend;
		return b == null ? "unavailable" : b.status(item);
	}

	/** Plays an emote item on you (as from the emote wheel); false when it can't be played. */
	public static boolean emote(JsonObject emote) {
		Backend b = backend;
		return b != null && b.emote(emote);
	}

	public static void stopEmote() {
		Backend b = backend;
		if (b != null) {
			b.stopEmote();
		}
	}

	/**
	 * The emote wheel's 8 slots (the inventory's {@code emotes}, each a Store emote item or null), as the Cosmetics window reads
	 * them from the Flint app. Until it is called the wheel offers the dev emotes only.
	 */
	public static void wheel(List<@Nullable JsonObject> slots) {
		Backend b = backend;
		if (b != null) {
			b.wheel(slots);
		}
	}
}
