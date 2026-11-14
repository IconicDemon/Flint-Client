package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.control.feature.CosmeticPreview;
import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.ModelCosmetics;
import com.flintclient.client.cosmetics.ModelItem;
import com.flintclient.client.cosmetics.ModelThumbnails;
import com.flintclient.client.module.social.SocialLink.Item;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.platform.Texture;
import com.google.gson.JsonObject;
import java.util.IdentityHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The wardrobe's 3D renderer on 26.1 (as on 26.3) ({@link CosmeticPreview.Renderer}): a tile's live mini-render is the item's turntable
 * ({@link ModelThumbnails}: made once off the game's thread, then a turn is another cell of one texture), and the wardrobe's
 * preview player wears what it says while it is drawn ({@link Mc263Cosmetics#extract} reads {@link CosmeticPreview#wearing}).
 */
final class Mc263WardrobeRenderer implements CosmeticPreview.Renderer {
	static final int CELL = 64;
	private static final int MAX_ITEMS = 256;
	/** Each wardrobe item read once as a model item (null: it has no model the game can draw). */
	private final Map<Item, ModelItem> items = new IdentityHashMap<>();
	private final Map<Item, Boolean> none = new IdentityHashMap<>();

	@Nullable ModelItem item(Item item, ModelCosmetics<Texture> s) {
		ModelItem m = items.get(item);
		if (m != null || none.containsKey(item)) {
			return m;
		}
		if (items.size() + none.size() > MAX_ITEMS) {
			items.clear();
			none.clear();
		}
		CosmeticKind kind = CosmeticKind.of(com.flintclient.client.control.feature.CosmeticKinds.normalize(item.kind()));
		JsonObject model = item.model();
		m = kind == null || model == null ? null : ModelItem.model(item.id(), kind, model, s.endpoint());
		if (m == null && kind != null && model != null) {
			// As the Flint app sends items: no addresses; the files come through it.
			m = ModelItem.linked(item.id(), kind, model);
		}
		if (m == null) {
			none.put(item, Boolean.TRUE);
		} else {
			items.put(item, m);
		}
		return m;
	}

	@Override
	public boolean thumbnail(Frame frame, Item item, int x, int y, int w, int h, float yaw) {
		try {
			ModelCosmetics<Texture> s = Mc263Cosmetics.service();
			ModelItem m = s == null || !Mc263Cosmetics.GUARD.on() ? null : item(item, s);
			if (m == null || m.geometry() == null) {
				return false;
			}
			ModelCosmetics.Thumb<Texture> t = s.thumbnail(m, CELL);
			Texture texture = t.texture;
			if (t.state() != ModelCosmetics.UPLOADED || texture == null) {
				return false;
			}
			int cell = ModelThumbnails.cell(yaw);
			int side = Math.min(w, h);
			int atlas = ModelThumbnails.atlasSize(CELL);
			frame.blit(texture, x + (w - side) / 2, y + (h - side) / 2, (cell % ModelThumbnails.COLUMNS) * CELL,
				(cell / ModelThumbnails.COLUMNS) * CELL, side, side, CELL, CELL, atlas, atlas, -1);
			return true;
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	@Override
	public boolean draws(String kind) {
		CosmeticKind k = CosmeticKind.of(com.flintclient.client.control.feature.CosmeticKinds.normalize(kind));
		return k != null && Mc263Cosmetics.GUARD.on();
	}

	@Override
	public boolean previewEmote(Item emote) {
		ModelCosmetics<Texture> s = Mc263Cosmetics.service();
		ModelItem m = s == null ? null : item(emote, s);
		return m != null && m.kind() == CosmeticKind.EMOTE && Mc263Emotes.play(m);
	}
}
