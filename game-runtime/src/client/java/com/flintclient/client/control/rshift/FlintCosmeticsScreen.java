package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.feature.CapeMods;
import com.flintclient.client.control.feature.CapePng;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.SocialParts.Button;
import com.flintclient.client.control.rshift.SocialParts.Lines;
import com.flintclient.client.control.store.StoreEndpoint;
import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.flintclient.client.module.social.FriendsClient;
import com.flintclient.client.module.social.SocialLink;
import com.flintclient.client.module.social.SocialLink.Cosmetics;
import com.flintclient.client.module.social.SocialLink.Item;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Your Flint Store cosmetics in the game, a window of its own (as Daily Rewards is): what the account the game plays as owns
 * and wears, from the Flint app that started the game (the Store session stays the app's). Capes show their front, as the
 * app's Store page shows them; a click wears one (or None takes it off), which the Store decides, and your cape in game
 * changes with it. "Get more" opens the Flint Store after Minecraft's link confirmation. Same window on every Minecraft line.
 *
 * <p>While a mod that puts its own capes on players is loaded ({@link CapeMods}), it can hide a Flint cape in this game: a
 * quiet line under the note says so, and wearing a cape shows a small notice (once per window) naming the mod. The cape is
 * worn all the same, as it is the account's.
 */
public final class FlintCosmeticsScreen extends WindowScreen {
	private final CosmeticsPage page = new CosmeticsPage();

	public FlintCosmeticsScreen(@Nullable Object parent) {
		super("Cosmetics", parent, 1240, 680);
	}

	@Override
	protected Node content() {
		return page;
	}

	@Override
	protected void step() {
		page.client.tick();
		page.rebuild();
	}

	@Override
	protected void initialFocus(boolean keyboard) {
	}

	@Override
	protected void hidden() {
		super.hidden();
		page.release();
	}

	/** The front of a cape (the part a player sees from behind): 10 × 16 of its 64 × 32 layout, at the texture's own scale. */
	static int[] capeFront(CapePng.Image image, int[] size) {
		int unitX = Math.max(1, image.width() / 64);
		int unitY = Math.max(1, image.height() / 32);
		int x0 = unitX;
		int y0 = unitY;
		int w = Math.min(10 * unitX, image.width() - x0);
		int h = Math.min(16 * unitY, image.height() - y0);
		int[] out = new int[Math.max(0, w) * Math.max(0, h)];
		for (int y = 0; y < h; y++) {
			System.arraycopy(image.argb(), (y0 + y) * image.width() + x0, out, y * w, w);
		}
		size[0] = w;
		size[1] = h;
		return out;
	}

	/** The page: the header, then a row of tiles per slot the account has items for. */
	private final class CosmeticsPage extends Node {
		private static final float PAD = 28;
		private static final float HEAD = 56;
		private static final float TILE_W = 156;
		private static final float TILE_H = 214;

		final FriendsClient client = FriendsClient.get(FlintFriendsScreen::playing);
		private final @Nullable SocialLink link = SocialLink.get();
		private final Label title = new Label(Type.HEADER_TITLE, "COSMETICS");
		private final Lines note = new Lines(Type.ROW_NOTE, "Click one to wear it: other Flint players see it on you in game. The Flint Store decides what you own.");
		private final Label stale = new Label(Type.CAPTION, "OFFLINE · THE STORE'S LAST LIST");
		/** The cape mods loaded in this game: while there are any, they can hide a Flint cape here. */
		private final List<String> capeMods = CapeMods.inThisGame();
		private final Lines capeHint = new Lines(Type.ROW_NOTE, capeMods.isEmpty() ? "" : CapeMods.hint(capeMods));
		private boolean noticeShown;
		private final Label emptyTitle = new Label(Type.EMPTY, "");
		private final Lines emptyBody = new Lines(Type.EMPTY_NOTE, "");
		private final Button more;
		private final Button retry;
		private final FriendsPage.Stack column = new FriendsPage.Stack(d(18));
		private final ScrollView view;
		private @Nullable Cosmetics shown;
		private @Nullable String problem;
		private boolean loading;
		private boolean asked;
		private long askedAt;
		/** Slot → what is being put on (or "" for None) while the Store answers. */
		private final Map<String, String> wearing = new HashMap<>();
		/** Pictures by item and version; asked for once each. */
		private final Map<String, Picture> pictures = new HashMap<>();
		private final Set<String> asking = new HashSet<>();
		private String builtKey = "";

		CosmeticsPage() {
			more = add(new Button("Get more in the Flint Store", Glyph.GEM, PageControls.Look.ACCENT, this::openStore));
			retry = add(new Button("Try again", Glyph.RESET, PageControls.Look.PLAIN, this::load));
			view = add(new ScrollView(column));
			view.flatBar(MenuStyle.SCROLL_THUMB, d(10), d(70));
			load();
		}

		private void openStore() {
			Cosmetics c = shown;
			openLink((c != null ? c.storeUrl() : StoreEndpoint.DEFAULT_BASE) + "/cosmetics/");
		}

		private void load() {
			String who = client.playing();
			if (link == null || who == null) {
				problem = link == null ? "Open Flint to see your cosmetics here: they come from the Flint app's Store page. Start Minecraft from Flint."
					: "This game's account couldn't be read.";
				return;
			}
			loading = true;
			asked = true;
			askedAt = Clock.millis();
			client.run(() -> link.cosmetics(who), got -> {
				loading = false;
				shown = got;
				problem = null;
				wearing.clear();
			}, this::failed);
		}

		private void failed(String code) {
			loading = false;
			problem = switch (code) {
				case "unreachable" -> "Flint isn't running any more. Open the Flint app, then try again.";
				case "bad request" -> "This copy of the Flint app doesn't offer cosmetics in game yet. Update Flint, then start Minecraft from it again.";
				case "timeout" -> "The Flint Store took too long to answer. Try again.";
				default -> code;
			};
		}

		private void wear(String slot, @Nullable String id) {
			String who = client.playing();
			Cosmetics now = shown;
			if (link == null || who == null || now == null || now.stale() || wearing.containsKey(slot)) {
				return;
			}
			if (java.util.Objects.equals(now.worn(slot), id)) {
				return;
			}
			if ("cape".equals(slot) && id != null && !capeMods.isEmpty() && !noticeShown) {
				noticeShown = true;
				FlintCosmeticsScreen.this.showModal(new CapeNotice(capeMods));
			}
			wearing.put(slot, id == null ? "" : id);
			client.run(() -> link.equip(who, slot, id), got -> {
				shown = got;
				wearing.remove(slot);
			}, code -> {
				wearing.remove(slot);
				failed(code);
			});
		}

		/** The item's picture: asked for once, decoded on the worker, uploaded here. */
		@Nullable Picture picture(Item item) {
			if (item.art() < 0 || link == null) {
				return null;
			}
			String key = item.id() + "-v" + item.art();
			Picture known = pictures.get(key);
			String who = client.playing();
			if (known != null || asking.contains(key) || who == null) {
				return known;
			}
			asking.add(key);
			boolean cape = "cape".equals(item.kind());
			int asked = generation;
			client.run(() -> decode(link.art(who, item.id()), cape), decoded -> {
				if (decoded != null && asked == generation) {
					String path = "cosmetics/" + key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
					pictures.put(key, new Picture(Texture.upload(path, decoded.width, decoded.height, decoded.argb, false), decoded.width, decoded.height));
				}
			}, code -> {
			});
			return null;
		}

		/** Pictures asked for before the last {@link #release} are dropped when they arrive. */
		private int generation;

		/** Frees the pictures (the window went, or a screen covers it); they are asked for again when drawn. */
		void release() {
			generation++;
			asking.clear();
			for (Picture picture : pictures.values()) {
				picture.texture.release();
			}
			pictures.clear();
		}

		// ---------- layout ----------

		@Override
		protected void layout() {
			float pad = d(PAD);
			float right = x + w - pad;
			float bh = d(Button.H);
			float mw = Math.max(d(260), more.prefWidth());
			more.setBounds(right - mw, y + pad + (d(HEAD) - bh) / 2, mw, bh);
			float rw = Math.max(d(150), retry.prefWidth());
			retry.setBounds(x + (w - rw) / 2, y + h / 2 + d(70), rw, bh);
			float listY = y + pad + d(HEAD) + d(6) + note.height(measure(), w - 2 * pad) + hintHeight(w - 2 * pad) + d(14);
			view.setBounds(x + pad - d(6), listY, w - 2 * pad + d(12), Math.max(0, y + h - pad - listY));
		}

		/** The cape-mod line's height with its gap above (none without a cape mod). */
		private float hintHeight(float width) {
			return capeMods.isEmpty() ? 0 : d(8) + capeHint.height(measure(), width - d(22));
		}

		void rebuild() {
			Cosmetics c = shown;
			StringBuilder key = new StringBuilder();
			if (c != null) {
				key.append(c.items().size()).append(c.stale());
				for (Item item : c.items()) {
					key.append(item.id());
				}
			}
			if (key.toString().equals(builtKey)) {
				return;
			}
			builtKey = key.toString();
			column.clearChildren();
			if (c == null) {
				return;
			}
			for (String slot : SocialLink.SLOTS) {
				List<Item> items = new ArrayList<>();
				for (Item item : c.items()) {
					if (item.kind().equals(slot)) {
						items.add(item);
					}
				}
				if (!items.isEmpty()) {
					column.add(new Slot(slot, items));
				}
			}
			view.invalidate();
		}

		// ---------- drawing ----------

		@Override
		protected void paint(Canvas c) {
			if (!asked) {
				load();
			}
			Cosmetics now = shown;
			boolean empty = now != null && now.items().isEmpty();
			view.setVisible(now != null && !empty);
			retry.setVisible(now == null && problem != null && link != null && !loading);
			float pad = d(PAD);
			float cy = y + pad + d(HEAD) / 2;
			c.pushZoom(1.5F, c.px(x + pad), c.px(cy));
			try {
				MenuStyle.text(c, title, x + pad, cy, MenuStyle.WORDMARK);
			} finally {
				c.popOffset();
			}
			if (now != null && !empty) {
				float noteY = y + pad + d(HEAD) + d(6);
				note.paint(c, x + pad, noteY, w - 2 * pad, MenuStyle.NEUTRAL);
				if (!capeMods.isEmpty()) {
					float hintY = noteY + note.height(c, w - 2 * pad) + d(8);
					Glyph.INFO.draw(c, x + pad + d(7), hintY + Lines.pitch(c) / 2, d(13), MenuStyle.WARN_TEXT);
					capeHint.paint(c, x + pad + d(22), hintY, w - 2 * pad - d(22), MenuStyle.WARN_TEXT);
				}
				if (now.stale()) {
					MenuStyle.text(c, stale, x + pad + 1.5F * title.width(c) + d(24), cy, MenuStyle.WARN_TEXT);
				}
			}
			if (now != null && !empty) {
				return;
			}
			String heading;
			String body;
			if (empty) {
				heading = "Nothing here yet";
				body = "Cosmetics you buy or are given on the Flint Store appear here within a minute, and on you in game once you wear them.";
			} else if (problem != null && !loading) {
				heading = link == null ? "Open Flint to see your cosmetics" : "Your cosmetics didn't load";
				body = problem;
			} else {
				heading = "Loading your cosmetics…";
				body = "They come from your Flint Store account, through the Flint app.";
			}
			emptyTitle.set(heading);
			emptyBody.set(body);
			float cx = x + w / 2;
			float my = y + h / 2 - d(40);
			float disc = d(64);
			MenuStyle.box(c, cx - disc / 2, my - disc / 2 - d(30), disc, disc, disc / 2, Palette.withAlpha(MenuStyle.ACCENT, 0.14F));
			(loading ? Glyph.HOURGLASS : Glyph.GEM).draw(c, cx, my - d(30), d(26), MenuStyle.ACCENT);
			if (loading && !Kit.reduced()) {
				long t = Clock.millis() - askedAt;
				for (int i = 0; i < 3; i++) {
					float a = 0.25F + 0.75F * (float) Math.max(0, Math.sin((t / 260.0) - i * 0.8));
					SocialParts.dot(c, cx - d(14) + i * d(14), my + d(14), d(6), Palette.withAlpha(MenuStyle.TEXT, a));
				}
			}
			MenuStyle.centered(c, emptyTitle, cx, my + d(40), MenuStyle.EMPTY_TEXT, w - d(80));
			emptyBody.paintCentered(c, cx, my + d(58), Math.min(w - d(120), d(640)), MenuStyle.EMPTY_NOTE);
		}

		/** One slot's items: its name, then None and a tile per item, as many to a row as fit. */
		private final class Slot extends Node {
			private final String slot;
			private final Label name;

			Slot(String slot, List<Item> items) {
				this.slot = slot;
				this.name = new Label(Type.SECTION_TITLE, switch (slot) {
					case "cape" -> "CAPES";
					case "back" -> "BACK";
					case "head" -> "HEAD";
					default -> "AURAS";
				});
				add(new Tile(slot, null));
				for (Item item : items) {
					add(new Tile(slot, item));
				}
			}

			private int perRow(float width) {
				return Math.max(1, (int) ((width - d(12) + d(14)) / (d(TILE_W) + d(14))));
			}

			@Override
			public float prefHeight(float width) {
				int rows = (childCount() + perRow(width) - 1) / perRow(width);
				return d(40) + rows * d(TILE_H) + Math.max(0, rows - 1) * d(14);
			}

			@Override
			protected void layout() {
				int per = perRow(w);
				for (int i = 0; i < childCount(); i++) {
					child(i).setBounds(x + d(6) + (i % per) * (d(TILE_W) + d(14)), y + d(40) + (i / per) * (d(TILE_H) + d(14)), d(TILE_W), d(TILE_H));
				}
			}

			@Override
			protected void paint(Canvas c) {
				MenuStyle.text(c, name, x + d(8), y + d(18), MenuStyle.NEUTRAL);
			}
		}

		/** One item (or None): its picture, its name, ticked while worn. */
		private final class Tile extends Pressable {
			private final String slot;
			private final @Nullable Item item;
			private final Label label;
			private final Label badge = new Label(Type.BADGE, "");
			private float on = -1;

			Tile(String slot, @Nullable Item item) {
				super(null);
				this.slot = slot;
				this.item = item;
				this.label = new Label(Type.ROW_NOTE, item == null ? "None" : item.name());
				setAction(() -> wear(slot, item == null ? null : item.id()));
				String gift = item != null && item.gift() ? item.giftFrom() != null ? ", a gift from " + item.giftFrom() : ", a gift" : "";
				name((item == null ? "No " + slot : item.name()) + gift);
				if (item != null && item.gift()) {
					tooltip(item.name() + "\n" + (item.giftFrom() != null ? "Gift from " + item.giftFrom() : "Gift"));
				}
			}

			private boolean worn() {
				Cosmetics now = shown;
				String putting = wearing.get(slot);
				String id = item == null ? null : item.id();
				if (putting != null) {
					return putting.isEmpty() ? id == null : putting.equals(id);
				}
				return now != null && java.util.Objects.equals(now.worn(slot), id);
			}

			@Override
			public String narration() {
				return withReason(accessibleName() + (worn() ? ", worn" : ""));
			}

			@Override
			protected void paint(Canvas c) {
				Cosmetics now = shown;
				boolean disabled = now == null || now.stale();
				on = MenuStyle.ease(on, worn());
				float rad = MenuStyle.radius();
				MenuStyle.block(c, x, y, w, h, rad, disabled ? 0 : hover, 0);
				MenuStyle.pressed(c, x, y, w, h, rad, press);
				MenuStyle.chosen(c, x, y, w, h, rad, on);
				float artW = w - 2 * d(14);
				float artH = h - d(14) - d(44);
				float ax = x + d(14);
				float ay = y + d(14);
				MenuStyle.box(c, ax, ay, artW, artH, rad * 0.75F, MenuStyle.PREVIEW_FRAME);
				if (item == null) {
					Glyph.CLOSE.draw(c, ax + artW / 2, ay + artH / 2, d(24), Palette.withAlpha(MenuStyle.TEXT, 0.35F));
				} else {
					Picture picture = picture(item);
					if (picture != null) {
						// As large as fits, in proportion, pixels kept whole where they can be.
						float scale = Math.min(artW * c.scale() / picture.width, artH * c.scale() / picture.height);
						if (scale >= 1) {
							scale = (float) Math.floor(scale);
						}
						int pw = Math.max(1, Math.round(picture.width * scale));
						int ph = Math.max(1, Math.round(picture.height * scale));
						int px = c.px(ax + artW / 2) - pw / 2;
						int py = c.px(ay + artH / 2) - ph / 2;
						c.texture(picture.texture, px, py, pw, ph, picture.width, picture.height, 0xFFFFFFFF);
					} else {
						Glyph.GEM.draw(c, ax + artW / 2, ay + artH / 2, d(22), Palette.withAlpha(MenuStyle.TEXT, item.art() < 0 ? 0.25F : 0.12F));
					}
					if (item.gift()) {
						Glyph.HEART.draw(c, ax + artW - d(12), ay + d(12), d(12), 0xFFFF6B8A);
					}
					if (item.test()) {
						badge.set("TEST");
						float bw = badge.width(c) + d(10);
						MenuStyle.box(c, ax + d(6), ay + d(6), bw, d(16), d(4), MenuStyle.BADGE);
						MenuStyle.centered(c, badge, ax + d(6) + bw / 2, ay + d(14), MenuStyle.BADGE_TEXT, bw);
					}
				}
				if (wearing.containsKey(slot) && worn() && !Kit.reduced()) {
					float pulse = 0.5F + 0.5F * (float) Math.sin(Clock.millis() / 160.0);
					MenuStyle.ring(c, ax, ay, artW, artH, rad * 0.75F, Math.max(1, c.px(d(2))), Palette.withAlpha(MenuStyle.ACCENT, 0.3F + 0.5F * pulse));
				}
				float ly = y + h - d(30);
				if (on > 0.5F) {
					Glyph.CHECK.draw(c, x + d(22), ly, d(12), MenuStyle.ACCENT);
					MenuStyle.fitted(c, label, x + d(36), ly, MenuStyle.TEXT, w - d(48));
				} else {
					MenuStyle.fitted(c, label, x + d(14), ly, disabled ? MenuStyle.NEUTRAL : Palette.lerp(MenuStyle.SENTENCE_TEXT, MenuStyle.TEXT, hover), w - d(28));
				}
				MenuStyle.focusRing(c, x, y, w, h, rad, focus);
			}
		}
	}

	/**
	 * The small notice a cape worn here gets while a cape mod is loaded: what is on, that it can hide the Flint cape, what to
	 * do, and OK. In the window's look (a block, Minecraft's font), over the screen's dimmed backdrop; it rises in over
	 * 160 ms. OK, Escape or a click beside it closes it; the cape is worn either way.
	 */
	private static final class CapeNotice extends Node {
		private static final float W = 600;
		private static final float PAD = 32;
		private static final float ICON = 44;

		private final Label title;
		private final Lines body;
		private final Button ok;
		private final long openedAt = Clock.millis();
		private int rise;
		private float opacity = 1;
		private float cardX;
		private float cardY;
		private float cardW;
		private float cardH;

		CapeNotice(List<String> mods) {
			title = new Label(Type.EMPTY, CapeMods.noticeTitle(mods));
			body = new Lines(Type.EMPTY_NOTE, CapeMods.notice(mods));
			ok = add(new Button("OK", null, PageControls.Look.ACCENT, this::close));
			name(title.text());
		}

		private void close() {
			if (screen() != null) {
				screen().closeModal(this);
			}
		}

		@Override
		public boolean interactive() {
			return true;
		}

		@Override
		public String narration() {
			return title.text() + ". " + body.text();
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			if (mx < cardX || mx > cardX + cardW || my < cardY || my > cardY + cardH) {
				close();
			}
			return false;
		}

		private float textW() {
			return cardW - 2 * d(PAD) - d(ICON) - d(18);
		}

		@Override
		protected void layout() {
			cardW = Math.min(d(W), w - 2 * d(24));
			float textH = d(26) + d(10) + body.height(measure(), textW());
			cardH = d(PAD) + Math.max(d(ICON), textH) + d(28) + d(Button.H) + d(PAD);
			cardX = x + Math.round((w - cardW) / 2);
			cardY = y + Math.round((h - cardH) / 2);
			float bw = Math.max(d(120), ok.prefWidth());
			ok.setBounds(cardX + cardW - d(PAD) - bw, cardY + cardH - d(PAD) - d(Button.H), bw, d(Button.H));
		}

		/** Drawn from {@link #paint} (which moves and fades the card and its button) to {@link #paintOver} (which undoes it). */
		@Override
		protected void paint(Canvas c) {
			float t = Kit.reduced() ? 1 : Math.min(1, (Clock.millis() - openedAt) / 160.0F);
			float eased = 1 - (1 - t) * (1 - t) * (1 - t);
			rise = Math.round(c.px(d(10)) * (1 - eased));
			opacity = c.opacity();
			c.setOpacity(opacity * eased);
			c.pushOffset(0, rise);
			MenuStyle.block(c, cardX, cardY, cardW, cardH, MenuStyle.radius(), 0, 0);
			float ix = cardX + d(PAD);
			float iy = cardY + d(PAD);
			MenuStyle.box(c, ix, iy, d(ICON), d(ICON), d(ICON) / 2, Palette.withAlpha(MenuStyle.ACCENT, 0.14F));
			Glyph.INFO.draw(c, ix + d(ICON) / 2, iy + d(ICON) / 2, d(20), MenuStyle.ACCENT);
			float tx = ix + d(ICON) + d(18);
			MenuStyle.text(c, title, tx, iy + d(13), MenuStyle.EMPTY_TEXT);
			body.paint(c, tx, iy + d(26) + d(10), textW(), MenuStyle.SENTENCE_TEXT);
		}

		@Override
		protected void paintOver(Canvas c) {
			c.popOffset();
			c.setOpacity(opacity);
		}
	}

	/** An item's picture, uploaded. */
	private record Picture(Texture texture, int width, int height) {
	}

	/** A decoded picture: a cape's front, or the whole image for anything else. */
	private record Decoded(int width, int height, int[] argb) {
	}

	private static @Nullable Decoded decode(byte[] png, boolean cape) throws LinkException {
		try {
			CapePng.Image image = CapePng.decode(png, 4096, 2048);
			if (!cape) {
				return image.width() > 1024 || image.height() > 1024 ? null : new Decoded(image.width(), image.height(), image.argb());
			}
			int[] size = new int[2];
			int[] front = capeFront(image, size);
			return size[0] > 0 && size[1] > 0 ? new Decoded(size[0], size[1], front) : null;
		} catch (CapePng.Refused e) {
			throw new LinkException("invalid", false);
		}
	}
}
