package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.feature.CapeChoices;
import com.flintclient.client.control.feature.CapeFrames;
import com.flintclient.client.control.feature.CapeMods;
import com.flintclient.client.control.feature.CapePng;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.SocialParts.Button;
import com.flintclient.client.control.rshift.SocialParts.Lines;
import com.flintclient.client.control.feature.StoreCapes;
import com.flintclient.client.control.store.CapeAnimation;
import com.flintclient.client.control.store.StoreEndpoint;
import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.flintclient.client.module.social.FriendsClient;
import com.flintclient.client.module.social.SocialLink;
import com.flintclient.client.module.social.SocialLink.Cosmetics;
import com.flintclient.client.module.social.SocialLink.Item;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import com.flintclient.client.ui.kit.SearchField;
import com.flintclient.client.ui.kit.TextField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *
 * <p>Picking is instant: the tile is ticked and the cape is on you in the same tick ({@link CapeChoices}, from the picture this
 * window already has), while the Store is asked in the background. One request per slot at a time, and the latest pick wins:
 * picks made meanwhile are sent once the Store answers, only the last one. A refusal puts back what the Store last said
 * (on the tile and on you) and says why under the note. A change made in the launcher reads the list again.
 *
 * <p>Animated capes play in their tiles as they play on you ({@link Motion}): the poster first, then the frames, at the wall
 * clock's frame (the one the worn cape shows), written only while the tile is on screen; the strips come through the game's
 * own Store capes ({@link StoreCapes#strip}: its disk cache, the Store's hosts only, SHA-256 checked). Still capes stay still.
 * The frames go when the window does.
 *
 * <p>Built for big collections: a grid as dense as the window allows (about eight capes to a row at 1080p), the worn item
 * first in its slot, a search that filters by name (Escape clears it before it closes the window), and only the tiles on
 * screen ask for pictures or play. Past the window's frame budget, animations that scrolled away give their frames back.
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
		page.followLauncher();
		page.rebuild();
	}

	@Override
	protected void initialFocus(boolean keyboard) {
	}

	/** Escape clears the search first, then closes the window. */
	@Override
	protected void onEscape() {
		if (!page.clearSearch()) {
			super.onEscape();
		}
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
		/** The narrowest a tile gets: the grid fits as many as this allows, then shares the row out evenly. */
		private static final float TILE_MIN = 128;
		private static final float TILE_PAD = 8;
		private static final float GAP = 14;
		/** The room left of the first column and right of the last (the Equipped ring and the hover lift stay unclipped). */
		private static final float EDGE = 6;
		/** A slot's name row above its grid. */
		private static final float LABEL = 36;
		/** The search field's widest. */
		private static final float SEARCH_W = 300;

		final FriendsClient client = FriendsClient.get(FlintFriendsScreen::playing);
		private final @Nullable SocialLink link = SocialLink.get();
		private final Label title = new Label(Type.HEADER_TITLE, "COSMETICS");
		private final Lines note = new Lines(Type.ROW_NOTE, "Click one to wear it. Other Flint players see it on you in game.");
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
		/** Slot → what the player picked last (or "" for None) while the Store hasn't taken it: shown as worn. */
		private final Map<String, String> wearing = new HashMap<>();
		/** Slot → the pick sent to the Store and not answered yet: one request per slot at a time. */
		private final Map<String, String> sending = new HashMap<>();
		/** Pictures by item and version; asked for once each. */
		private final Map<String, Picture> pictures = new HashMap<>();
		/** Capes' whole textures by item id (what goes on you when picked), within {@link #ART_TEXELS}. */
		private final Map<String, CapeArt> capeArt = new HashMap<>();
		private long capeTexels;
		/** The pick last put on you ({@link CapeChoices}), and the item it was ("" for None). */
		private long capeSerial;
		private @Nullable String capePick;
		/** Why the last pick didn't go through: shown under the note until the next pick. */
		private final Lines error = new Lines(Type.ROW_NOTE, "");
		private boolean failedPick;
		/** The launcher's changes seen ({@link CapeChoices#launcherChanges}): another reads the list again. */
		private long launcherSeen = CapeChoices.launcherChanges();
		private final Set<String> asking = new HashSet<>();
		private String builtKey = "";
		private @Nullable Cosmetics builtFrom;
		/** Everything the account has, next to the title. */
		private final Label total = new Label(Type.CAPTION, "");
		private final SearchField search;
		private @Nullable FontFace searchFace;
		/** The search, lower case and trimmed ("" shows everything). */
		private String query = "";
		/** Tiles the search left (None not counted); with a search and none left, "No matches" shows. */
		private int matches;
		private final Label noMatch = new Label(Type.EMPTY, "No matches");
		private final Lines noMatchBody = new Lines(Type.EMPTY_NOTE, "");

		CosmeticsPage() {
			more = add(new Button("Flint Store", Glyph.GEM, PageControls.Look.PLAIN, this::openStore));
			more.tooltip("Get more cosmetics in the Flint Store");
			retry = add(new Button("Try again", Glyph.RESET, PageControls.Look.PLAIN, this::load));
			view = add(new ScrollView(column));
			view.flatBar(MenuStyle.SCROLL_THUMB, d(10), d(90));
			search = add(new SearchField("Search by name", 48));
			search.name("Search your cosmetics");
			search.onChange(text -> filter(text));
			search.onEscape(FlintCosmeticsScreen.this::onEscape);
			search.setVisible(false);
			load();
		}

		/** Shows the tiles whose name has the search in it (every slot, its count too) and goes back to the top. */
		private void filter(String text) {
			String q = text.toLowerCase(Locale.ROOT).strip();
			if (q.equals(query)) {
				return;
			}
			query = q;
			noMatchBody.set("Nothing you own is called “" + text.strip() + "”. Escape clears the search.");
			applyFilter();
			view.scrollToTop();
		}

		private void applyFilter() {
			matches = 0;
			for (int i = 0; i < column.childCount(); i++) {
				if (column.child(i) instanceof Slot s) {
					matches += s.filter(query);
				}
			}
			column.invalidate();
			view.invalidate();
		}

		/** Empties the search; false when it was empty already. */
		boolean clearSearch() {
			if (search.text().isEmpty() && query.isEmpty()) {
				return false;
			}
			search.setText("");
			filter("");
			return true;
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
				took(got);
				problem = null;
				// A pick on its way stays shown until the Store answers it.
				wearing.keySet().retainAll(sending.keySet());
			}, this::failed);
		}

		/** A Store answer, unless it is older than the one shown (a list read before an equip, arriving after it). */
		private void took(Cosmetics got) {
			Cosmetics now = shown;
			if (now == null || got.version() < 0 || now.version() < 0 || got.version() >= now.version()) {
				shown = got;
			}
		}

		/** The launcher said something was put on or taken off (there, on the website, or here): the list is read again. */
		void followLauncher() {
			long changes = CapeChoices.launcherChanges();
			if (changes != launcherSeen && sending.isEmpty() && !loading && link != null) {
				launcherSeen = changes;
				load();
			}
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

		/** A click on a tile: shown at once (ticked, and a cape on you), then asked of the Store; the latest pick wins. */
		private void wear(String slot, @Nullable String id) {
			String who = client.playing();
			Cosmetics now = shown;
			if (link == null || who == null || now == null || now.stale()) {
				return;
			}
			String want = id == null ? "" : id;
			String showing = wearing.containsKey(slot) ? wearing.get(slot) : java.util.Objects.requireNonNullElse(now.worn(slot), "");
			if (want.equals(showing)) {
				return;
			}
			if ("cape".equals(slot) && id != null && !capeMods.isEmpty() && !noticeShown) {
				noticeShown = true;
				FlintCosmeticsScreen.this.showModal(new CapeNotice(capeMods));
			}
			if (failedPick) {
				failedPick = false;
				invalidate();
			}
			wearing.put(slot, want);
			if ("cape".equals(slot)) {
				onYou(who, want);
			}
			if (!sending.containsKey(slot)) {
				send(who, slot);
			}
		}

		/** Asks the Store for the slot's latest pick; once it answers, the pick made meanwhile (if any) goes next. */
		private void send(String who, String slot) {
			SocialLink to = link;
			String want = wearing.get(slot);
			Cosmetics now = shown;
			if (to == null || want == null || now == null) {
				return;
			}
			if (want.equals(java.util.Objects.requireNonNullElse(now.worn(slot), ""))) {
				// Back to what the Store says is worn: nothing to ask.
				wearing.remove(slot);
				settleCape(who, slot, want, true);
				return;
			}
			sending.put(slot, want);
			client.run(() -> to.equip(who, slot, want.isEmpty() ? null : want), got -> {
				sending.remove(slot);
				took(got);
				if (want.equals(wearing.get(slot))) {
					wearing.remove(slot);
					settleCape(who, slot, want, true);
				} else {
					send(who, slot);
				}
			}, code -> {
				sending.remove(slot);
				if (!want.equals(wearing.get(slot))) {
					// A later pick goes anyway: it is the one that counts.
					send(who, slot);
					return;
				}
				wearing.remove(slot);
				settleCape(who, slot, want, false);
				failedPick = true;
				invalidate();
				error.set((want.isEmpty() ? "Couldn't take that off: " : "Couldn't wear that: ") + reason(code)
					+ " You're wearing what the Flint Store last said.");
			});
		}

		private String reason(String code) {
			return switch (code) {
				case "unreachable" -> "Flint isn't running any more.";
				case "bad request" -> "this copy of the Flint app can't do that yet.";
				case "timeout" -> "the Flint Store took too long to answer.";
				default -> code;
			};
		}

		/** The cape just picked goes on you now, from its picture here; one without a picture here waits for the Store. */
		private void onYou(String who, String want) {
			UUID player = uuid(who);
			if (player == null) {
				return;
			}
			CapeArt art = want.isEmpty() ? null : capeArt.get(want);
			if (!want.isEmpty() && art == null) {
				// Its picture isn't here yet: what the Store says stays on you until it answers.
				if (capePick != null) {
					CapeChoices.settled(player, capeSerial, false);
					capePick = null;
				}
				return;
			}
			capeSerial = art == null ? CapeChoices.choose(player, null, null) : CapeChoices.choose(player, art.sha256(), art.image());
			capePick = want;
		}

		/** The Store took the cape pick {@code want} (it stays on you until the game's lookup agrees) or refused it. */
		private void settleCape(String who, String slot, String want, boolean accepted) {
			UUID player = uuid(who);
			if (!"cape".equals(slot) || player == null || !want.equals(capePick)) {
				return;
			}
			capePick = null;
			CapeChoices.settled(player, capeSerial, accepted);
			Cosmetics now = shown;
			if (accepted || now == null) {
				return;
			}
			// What the Store last said goes back on you at once (it may be newer than the game's last lookup).
			String worn = now.worn("cape");
			CapeArt art = worn == null ? null : capeArt.get(worn);
			if (worn == null || art != null) {
				long serial = art == null ? CapeChoices.choose(player, null, null) : CapeChoices.choose(player, art.sha256(), art.image());
				CapeChoices.settled(player, serial, true);
			}
		}

		private static @Nullable UUID uuid(String hex) {
			if (hex.length() != 32) {
				return null;
			}
			try {
				return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
			} catch (NumberFormatException e) {
				return null;
			}
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
				if (decoded != null && decoded.art != null) {
					keepArt(item.id(), decoded.art);
				}
				if (decoded != null && asked == generation) {
					String path = "cosmetics/" + key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
					pictures.put(key, new Picture(Texture.upload(path, decoded.width, decoded.height, decoded.argb, false), decoded.width, decoded.height));
				}
			}, code -> {
			});
			return null;
		}

		/** A cape's whole texture, for putting it on you when picked: within {@link #ART_TEXELS} (more are left to the Store's answer). */
		private void keepArt(String id, CapeArt art) {
			long texels = (long) art.image().width() * art.image().height();
			CapeArt before = capeArt.get(id);
			if (before != null) {
				capeTexels -= (long) before.image().width() * before.image().height();
			}
			if (capeTexels + texels > ART_TEXELS) {
				capeArt.remove(id);
				return;
			}
			capeArt.put(id, art);
			capeTexels += texels;
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
			for (Motion motion : motions.values()) {
				motion.release();
			}
			motions.clear();
			motionBytes = 0;
		}

		/** Animated capes' frames by item id while the window shows them; {@link #motionBytes} of them together. */
		private final Map<String, Motion> motions = new HashMap<>();
		private long motionBytes;

		/**
		 * The item's animation, playing or on its way, or null for a still cape (and while the game's Store capes aren't up, or
		 * the window's frames are full: the poster then stays). Asked for once the poster is up.
		 */
		@Nullable Motion motion(Item item, int artPx) {
			Motion known = motions.get(item.id());
			long now = Clock.millis();
			if (known != null) {
				known.drawnAt = now;
				return known;
			}
			if (item.animation() == null || !"cape".equals(item.kind()) || still.contains(item.id())) {
				return null;
			}
			StoreCapes<?> capes = StoreCapes.shared();
			if (capes == null) {
				return null;
			}
			// The manifest is read once per item (not every frame while the frames are full): a bad one keeps the poster.
			CapeAnimation animation = animations.get(item.id());
			if (animation == null) {
				animation = CapeAnimation.parse(item.animation(), capes.client().endpoint());
				if (animation == null) {
					still.add(item.id());
					return null;
				}
				animations.put(item.id(), animation);
			}
			// Kept no bigger than the tile shows it (half the scale while that still fills the picture's width) nor MOTION_BYTES.
			int scale = Math.min(animation.scale(), CapeFrames.MAX_SCALE);
			while (scale > 1 && (Motion.bytes(animation.frameCount(), scale) > MOTION_BYTES || CapeFrames.FRONT_WIDTH * (scale / 2) >= artPx)) {
				scale /= 2;
			}
			long bytes = Motion.bytes(animation.frameCount(), scale);
			if (motionBytes + bytes > WINDOW_MOTION_BYTES && !makeRoom(bytes, now)) {
				return null;
			}
			motionBytes += bytes;
			Motion motion = new Motion(item.id(), animation, scale, bytes);
			motion.drawnAt = now;
			motions.put(item.id(), motion);
			motion.load(capes);
			return motion;
		}

		/** Manifests read, by item id, and the items whose manifest didn't read (they stay still). */
		private final Map<String, CapeAnimation> animations = new HashMap<>();
		private final Set<String> still = new HashSet<>();
		private long roomAt;

		/**
		 * The frames are full: the animations not drawn for a moment (scrolled away) give theirs back, at most every 250 ms;
		 * true when {@code bytes} more then fit.
		 */
		private boolean makeRoom(long bytes, long now) {
			if (now - roomAt < 250) {
				return false;
			}
			roomAt = now;
			var it = motions.values().iterator();
			while (it.hasNext()) {
				Motion m = it.next();
				if (now - m.drawnAt > 250) {
					m.release();
					motionBytes -= m.bytes;
					it.remove();
				}
			}
			return motionBytes + bytes <= WINDOW_MOTION_BYTES;
		}

		// ---------- layout ----------

		@Override
		protected void layout() {
			float pad = d(PAD);
			float right = x + w - pad;
			float bh = d(Button.H);
			float top = y + pad + (d(HEAD) - bh) / 2;
			float mw = more.prefWidth();
			more.setBounds(right - mw, top, mw, bh);
			float rw = Math.max(d(150), retry.prefWidth());
			retry.setBounds(x + (w - rw) / 2, y + h / 2 + d(70), rw, bh);
			// The search sits between the title (with its count) and the Store button, as wide as there is room for.
			float searchRight = right - mw - d(12);
			float searchX = Math.max(headEnd(measure()) + d(20), searchRight - d(SEARCH_W));
			float sw = Math.max(d(110), searchRight - searchX);
			search.setBounds(searchRight - sw, top, sw, bh);
			FontFace face = Type.SEARCH.face();
			if (face != searchFace) {
				searchFace = face;
				search.look(new TextField.Look(face, MenuStyle.SEARCH, MenuStyle.SEARCH_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS,
					MenuStyle.radius(), d(14), d(14), d(14), MenuStyle.SEARCH_TEXT, MenuStyle.SEARCH_HINT, MenuStyle.SEARCH_HINT));
			}
			float listY = y + pad + d(HEAD) + d(4) + note.height(measure(), w - 2 * pad) + hintHeight(w - 2 * pad) + errorHeight(w - 2 * pad) + d(10);
			view.setBounds(x + pad - d(EDGE), listY, w - 2 * pad + 2 * d(EDGE) + d(4), Math.max(0, y + h - pad - listY));
		}

		/** Where the title, its count and the offline caption end. */
		private float headEnd(Canvas c) {
			float end = x + d(PAD) + 1.5F * title.width(c);
			Cosmetics now = shown;
			if (now != null) {
				end += d(14) + total.width(c);
				if (now.stale()) {
					end += d(14) + stale.width(c);
				}
			}
			return end;
		}

		/** The failed pick's line with its gap above (none while the last pick went through). */
		private float errorHeight(float width) {
			return failedPick ? d(8) + error.height(measure(), width - d(22)) : 0;
		}

		/** The cape-mod line's height with its gap above (none without a cape mod). */
		private float hintHeight(float width) {
			return capeMods.isEmpty() ? 0 : d(8) + capeHint.height(measure(), width - d(22));
		}

		void rebuild() {
			Cosmetics c = shown;
			if (c == builtFrom && c != null) {
				return;
			}
			builtFrom = c;
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
			invalidate();
			if (c == null) {
				return;
			}
			int n = c.items().size();
			total.set(n == 1 ? "1 item" : n + " items");
			for (String slot : SocialLink.SLOTS) {
				// What is worn first, then the rest in the Store's order (fixed while the window is open: a pick doesn't move tiles).
				String worn = c.worn(slot);
				List<Item> items = new ArrayList<>();
				for (Item item : c.items()) {
					if (item.kind().equals(slot)) {
						if (item.id().equals(worn)) {
							items.add(0, item);
						} else {
							items.add(item);
						}
					}
				}
				if (!items.isEmpty()) {
					column.add(new Slot(slot, items));
				}
			}
			applyFilter();
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
			search.setVisible(now != null && !empty);
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
				if (failedPick) {
					float errorY = noteY + note.height(c, w - 2 * pad) + hintHeight(w - 2 * pad) + d(8);
					Glyph.INFO.draw(c, x + pad + d(7), errorY + Lines.pitch(c) / 2, d(13), MenuStyle.WARN_TEXT);
					error.paint(c, x + pad + d(22), errorY, w - 2 * pad - d(22), MenuStyle.WARN_TEXT);
				}
				float tx = x + pad + 1.5F * title.width(c) + d(14);
				MenuStyle.text(c, total, tx, cy, Palette.withAlpha(MenuStyle.NEUTRAL, 0.7F));
				if (now.stale()) {
					MenuStyle.text(c, stale, tx + total.width(c) + d(14), cy, MenuStyle.WARN_TEXT);
				}
				if (!query.isEmpty() && matches == 0) {
					float mx = view.x() + view.width() / 2;
					float my2 = view.y() + Math.min(d(90), view.height() / 3);
					MenuStyle.centered(c, noMatch, mx, my2, MenuStyle.EMPTY_TEXT, view.width() - d(40));
					noMatchBody.paintCentered(c, mx, my2 + d(18), Math.min(view.width() - d(60), d(560)), MenuStyle.EMPTY_NOTE);
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

		/**
		 * One slot's items: its name and count, then None and a tile per item in a grid as dense as the width allows (tiles at
		 * least {@link #TILE_MIN} wide, the row shared out evenly). A search hides the tiles it leaves out (None too).
		 */
		private final class Slot extends Node {
			private final Label name;
			private final Label count;
			private final int size;
			private int shownTiles;

			Slot(String slot, List<Item> items) {
				this.name = new Label(Type.SECTION_TITLE, switch (slot) {
					case "cape" -> "CAPES";
					case "back" -> "BACK";
					case "head" -> "HEAD";
					default -> "AURAS";
				});
				size = items.size();
				count = new Label(Type.CAPTION, "· " + size);
				add(new Tile(slot, null));
				for (Item item : items) {
					add(new Tile(slot, item));
				}
				shownTiles = childCount();
			}

			/** Shows the tiles whose name has {@code query} in it (all with none); how many items that left. */
			int filter(String query) {
				int found = 0;
				shownTiles = 0;
				for (int i = 0; i < childCount(); i++) {
					Tile tile = (Tile) child(i);
					boolean show = query.isEmpty() || (tile.item != null && tile.lower.contains(query));
					tile.setVisible(show);
					if (show) {
						shownTiles++;
						found += tile.item != null ? 1 : 0;
					}
				}
				count.set(query.isEmpty() ? "· " + size : "· " + found + " of " + size);
				setVisible(shownTiles > 0);
				invalidate();
				return found;
			}

			private int columns(float width) {
				return Math.max(1, (int) ((width - 2 * d(EDGE) + d(GAP)) / (d(TILE_MIN) + d(GAP))));
			}

			private float tileW(float width, int columns) {
				return Math.max(d(60), (width - 2 * d(EDGE) - (columns - 1) * d(GAP)) / columns);
			}

			@Override
			public float prefHeight(float width) {
				if (shownTiles == 0) {
					return 0;
				}
				int per = columns(width);
				int rows = (shownTiles + per - 1) / per;
				return d(LABEL) + rows * tileH(tileW(width, per)) + Math.max(0, rows - 1) * d(GAP) + d(EDGE);
			}

			@Override
			protected void layout() {
				int per = columns(w);
				float tw = tileW(w, per);
				float th = tileH(tw);
				int k = 0;
				for (int i = 0; i < childCount(); i++) {
					Node tile = child(i);
					if (tile.visible()) {
						tile.setBounds(x + d(EDGE) + (k % per) * (tw + d(GAP)), y + d(LABEL) + (k / per) * (th + d(GAP)), tw, th);
						k++;
					}
				}
			}

			@Override
			protected void paint(Canvas c) {
				float cy = y + d(LABEL) / 2 - d(2);
				MenuStyle.text(c, name, x + d(EDGE) + d(2), cy, MenuStyle.NEUTRAL);
				MenuStyle.text(c, count, x + d(EDGE) + d(2) + name.width(c) + d(8), cy, Palette.withAlpha(MenuStyle.NEUTRAL, 0.6F));
			}
		}

		/** A tile's picture box: a poster, a little taller than wide. */
		private float artH(float tileW) {
			return Math.round((tileW - 2 * d(TILE_PAD)) * 1.22F);
		}

		/** A tile: padding, the picture, a gap, two lines of name, padding. */
		private float tileH(float tileW) {
			return d(TILE_PAD) + artH(tileW) + d(8) + 2 * Lines.pitch(measure()) + d(TILE_PAD);
		}

		/**
		 * One item (or None): its picture (an animated cape playing), its whole name on up to two lines, and "Equipped" with an
		 * orange ring while worn.
		 */
		private final class Tile extends Pressable {
			private final String slot;
			final @Nullable Item item;
			/** The name in lower case, for the search. */
			final String lower;
			private final Lines label;
			private final Label rest = new Label(Type.ROW_NOTE, "");
			/** The first line {@link #rest} was cut after (the name is wrapped again only when the width or font changes). */
			private @Nullable TextLine restAfter;
			private final Label equipped = new Label(Type.CAPTION, "Equipped");
			private final Label hint;
			private final Label badge = new Label(Type.BADGE, "TEST");
			private float on = -1;

			Tile(String slot, @Nullable Item item) {
				super(null);
				this.slot = slot;
				this.item = item;
				this.lower = item == null ? "" : item.name().toLowerCase(Locale.ROOT);
				this.hint = new Label(Type.CAPTION, item == null ? "Take off" : "Wear");
				this.label = new Lines(Type.ROW_NOTE, item == null ? "None" : item.name());
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
				return withReason(accessibleName() + (worn() ? ", equipped" : ""));
			}

			/** On screen: inside the list's view (it scrolls). */
			private boolean onScreen() {
				return y + h > view.y() && y < view.bottom();
			}

			@Override
			protected void paint(Canvas c) {
				if (!onScreen()) {
					return;
				}
				Cosmetics now = shown;
				boolean disabled = now == null || now.stale();
				on = MenuStyle.ease(on, worn());
				float hot = disabled ? 0 : hover;
				// Under the pointer the tile lifts a little and brightens (no lift with reduced motion).
				int lift = Kit.reduced() ? 0 : Math.round(c.px(d(2)) * hot);
				c.pushOffset(0, -lift);
				try {
					paintTile(c, disabled, hot);
				} finally {
					c.popOffset();
				}
			}

			private void paintTile(Canvas c, boolean disabled, float hot) {
				float rad = MenuStyle.radius();
				MenuStyle.block(c, x, y, w, h, rad, 0.5F * hot, 0);
				MenuStyle.pressed(c, x, y, w, h, rad, press);
				MenuStyle.chosen(c, x, y, w, h, rad, on);
				float pad = d(TILE_PAD);
				float pitch = Lines.pitch(c);
				float ax = x + pad;
				float ay = y + pad;
				float artW = w - 2 * pad;
				float artH = artH(w);
				MenuStyle.box(c, ax, ay, artW, artH, rad * 0.75F, Palette.lerp(MenuStyle.PREVIEW_FRAME, 0xFF181818, 0.6F * hot));
				if (item == null) {
					Glyph.CLOSE.draw(c, ax + artW / 2, ay + artH / 2, d(22), Palette.withAlpha(MenuStyle.TEXT, 0.35F + 0.2F * hot));
				} else {
					Picture picture = picture(item);
					Motion motion = picture == null ? null : motion(item, c.px(artW));
					Picture frame = motion == null ? null : motion.frame();
					if (frame != null) {
						drawPicture(c, frame, ax, ay, artW, artH);
					} else if (picture != null) {
						drawPicture(c, picture, ax, ay, artW, artH);
					} else {
						Glyph.GEM.draw(c, ax + artW / 2, ay + artH / 2, d(20), Palette.withAlpha(MenuStyle.TEXT, item.art() < 0 ? 0.25F : 0.12F));
					}
					if (item.gift()) {
						Glyph.HEART.draw(c, ax + artW - d(11), ay + d(11), d(11), 0xFFFF6B8A);
					}
					if (item.test()) {
						float bw = badge.width(c) + d(10);
						MenuStyle.box(c, ax + d(5), ay + d(5), bw, d(16), d(4), MenuStyle.BADGE);
						MenuStyle.centered(c, badge, ax + d(5) + bw / 2, ay + d(13), MenuStyle.BADGE_TEXT, bw);
					}
				}
				if (wearing.containsKey(slot) && worn() && !Kit.reduced()) {
					float pulse = 0.5F + 0.5F * (float) Math.sin(Clock.millis() / 160.0);
					MenuStyle.ring(c, ax, ay, artW, artH, rad * 0.75F, Math.max(1, c.px(d(2))), Palette.withAlpha(MenuStyle.ACCENT, 0.3F + 0.5F * pulse));
				}
				// "Equipped" (or, under the pointer, what a click does) on a dark pill at the foot of the picture.
				Label pill = on > 0.5F ? equipped : hot > 0.01F ? hint : null;
				if (pill != null) {
					float a = pill == equipped ? on : hot;
					boolean check = pill == equipped;
					float ph = d(22);
					float pw = pill.width(c) + d(check ? 34 : 20);
					if (pw > artW - d(4)) {
						// A small window (Minecraft's font doesn't shrink): the check alone, or no hint.
						if (check) {
							float cx = ax + artW / 2;
							float cy = ay + artH - ph / 2 - d(6);
							MenuStyle.box(c, cx - ph / 2, cy - ph / 2, ph, ph, ph / 2, Palette.withAlpha(0xFF0A0A0A, 0.85F * a));
							Glyph.CHECK.draw(c, cx, cy, d(11), Palette.withAlpha(MenuStyle.ACCENT, a));
						}
						pill = null;
					}
				}
				if (pill != null) {
					float a = pill == equipped ? on : hot;
					boolean check = pill == equipped;
					float ph = d(22);
					float pw = pill.width(c) + d(check ? 34 : 20);
					float px = ax + (artW - pw) / 2;
					float py = ay + artH - ph - d(6);
					MenuStyle.box(c, px, py, pw, ph, ph / 2, Palette.withAlpha(0xFF0A0A0A, 0.85F * a));
					if (check) {
						Glyph.CHECK.draw(c, px + d(13), py + ph / 2, d(10), Palette.withAlpha(MenuStyle.ACCENT, a));
						MenuStyle.text(c, pill, px + d(23), py + ph / 2, Palette.withAlpha(MenuStyle.TEXT, a));
					} else {
						MenuStyle.text(c, pill, px + d(10), py + ph / 2, Palette.withAlpha(MenuStyle.TEXT, 0.85F * a));
					}
				}
				// The whole name on up to two lines (a longer one ends in an ellipsis on the second).
				float ny = ay + artH + d(8);
				float nw = w - 2 * pad;
				int ink = disabled ? MenuStyle.NEUTRAL : Palette.lerp(MenuStyle.SENTENCE_TEXT, MenuStyle.TEXT, Math.max(hot, on));
				List<TextLine> lines = label.lines(c, nw);
				if (lines.size() <= 2) {
					label.paint(c, ax, ny, nw, ink);
				} else {
					TextLine first = lines.get(0);
					c.text(first, c.px(ax), c.baselineFor(first, c.px(ny + pitch / 2)), ink);
					if (first != restAfter) {
						restAfter = first;
						String name = label.text();
						rest.set(name.substring(Math.min(name.length(), first.text().length())).strip());
					}
					MenuStyle.fitted(c, rest, ax, ny + pitch * 1.5F, ink, nw);
				}
				MenuStyle.focusRing(c, x, y, w, h, rad, focus);
			}

			/** As large as fits, in proportion, pixels kept whole where they can be. */
			private void drawPicture(Canvas c, Picture picture, float ax, float ay, float artW, float artH) {
				float scale = Math.min(artW * c.scale() / picture.width, artH * c.scale() / picture.height);
				if (scale >= 1) {
					scale = (float) Math.floor(scale);
				}
				int pw = Math.max(1, Math.round(picture.width * scale));
				int ph = Math.max(1, Math.round(picture.height * scale));
				int px = c.px(ax + artW / 2) - pw / 2;
				int py = c.px(ay + artH / 2) - ph / 2;
				c.texture(picture.texture, px, py, pw, ph, picture.width, picture.height, 0xFFFFFFFF);
			}
		}
	}

	/** One animated cape's frames in the window: at most this many bytes (more halve its scale). */
	static final long MOTION_BYTES = 8L * 1024 * 1024;
	/** Every animated tile's frames together: past this, a cape keeps its poster. */
	static final long WINDOW_MOTION_BYTES = 48L * 1024 * 1024;

	/** The window's loader: one low-priority thread, gone when idle. */
	private static @Nullable ExecutorService loader;

	private static synchronized ExecutorService loader() {
		ExecutorService l = loader;
		if (l == null) {
			ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
				Thread thread = new Thread(task, "Flint cosmetics frames");
				thread.setDaemon(true);
				thread.setPriority(Thread.MIN_PRIORITY);
				return thread;
			});
			pool.allowCoreThreadTimeOut(true);
			l = pool;
			loader = pool;
		}
		return l;
	}

	/**
	 * An animated cape's front, playing: its strips loaded on {@link #loader} through the game's Store capes, cut into fronts
	 * ({@link CapeFrames#front}) at {@code scale} pixels per unit in the layout {@link Texture#writeTopLeft} takes; then one
	 * texture, written with the wall clock's frame ({@link CapeAnimation#frameAt}, as the worn cape) when it changes and only
	 * while the tile is drawn. A strip that fails its checks leaves the poster.
	 */
	private static final class Motion {
		private final String id;
		private final CapeAnimation animation;
		private final int scale;
		/** What its frames take ({@link #bytes(int, int)}), counted in the window's budget. */
		final long bytes;
		/** When its tile last drew it (render thread): one off screen for a moment gives its frames back when room is needed. */
		long drawnAt;
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private volatile int @Nullable [][] frames;
		private @Nullable Picture texture;
		private int shown = -1;

		Motion(String id, CapeAnimation animation, int scale, long bytes) {
			this.id = id;
			this.animation = animation;
			this.scale = scale;
			this.bytes = bytes;
		}

		/** What {@code count} fronts take at {@code scale} pixels per unit. */
		static long bytes(int count, int scale) {
			return (long) count * CapeFrames.FRONT_WIDTH * scale * CapeFrames.FRONT_HEIGHT * scale * 4;
		}

		void load(StoreCapes<?> capes) {
			try {
				loader().execute(() -> cut(capes));
			} catch (RejectedExecutionException e) {
				// The poster stays.
			}
		}

		/** Loader thread: every strip read (or downloaded) and checked, decoded at the size the manifest gives, cut into fronts. */
		private void cut(StoreCapes<?> capes) {
			int[][] whole = new int[animation.frameCount()][];
			int[][] out = new int[animation.frameCount()][];
			try {
				for (int s = 0; s < animation.strips().size(); s++) {
					if (cancelled.get()) {
						return;
					}
					CapeAnimation.Strip strip = animation.strips().get(s);
					int height = strip.frames() * animation.frameHeight();
					CapePng.Image image = CapePng.decode(capes.strip(strip), animation.frameWidth(), height);
					if (image.width() != animation.frameWidth() || image.height() != height) {
						return;
					}
					int first = animation.firstFrame(s);
					CapeFrames.slice(image.argb(), animation.scale(), first, strip.frames(), scale, Texture.WRITE_LAYOUT, whole);
					for (int f = first; f < first + strip.frames(); f++) {
						out[f] = CapeFrames.front(whole[f], scale);
						whole[f] = null;
					}
				}
			} catch (Exception e) {
				// Offline, refused or not a PNG: the poster stays.
				return;
			}
			if (!cancelled.get()) {
				frames = out;
			}
		}

		/** The frame the wall clock is at, written into the texture when it changed; null until the frames are in. Render thread. */
		@Nullable Picture frame() {
			int[][] all = frames;
			if (all == null || cancelled.get()) {
				return null;
			}
			int fw = CapeFrames.FRONT_WIDTH * scale;
			int fh = CapeFrames.FRONT_HEIGHT * scale;
			Picture t = texture;
			if (t == null) {
				String path = "cosmetics/motion-" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "") + "-" + animation.key();
				t = new Picture(Texture.upload(path, fw, fh, new int[fw * fh], false), fw, fh);
				texture = t;
				shown = -1;
			}
			int f = Kit.reduced() ? 0 : animation.frameAt(System.currentTimeMillis());
			if (f != shown) {
				shown = f;
				t.texture.writeTopLeft(all[f], fw, fh);
			}
			return t;
		}

		void release() {
			cancelled.set(true);
			frames = null;
			Picture t = texture;
			texture = null;
			if (t != null) {
				t.texture.release();
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

	/** A decoded picture: a cape's front, or the whole image for anything else; for a cape, its whole texture too. */
	private record Decoded(int width, int height, int[] argb, @Nullable CapeArt art) {
	}

	/** A cape's whole texture as the Flint app checked it (the PNG the Store hashed), and that hash. */
	private record CapeArt(String sha256, CapePng.Image image) {
	}

	/** The most cape texels kept for putting capes on you at once: 32 MiB (16 HD capes, or thousands of 64 × 32 ones). */
	static final long ART_TEXELS = 8L * 1024 * 1024;

	private static @Nullable Decoded decode(byte[] png, boolean cape) throws LinkException {
		try {
			CapePng.Image image = CapePng.decode(png, 4096, 2048);
			if (!cape) {
				return image.width() > 1024 || image.height() > 1024 ? null : new Decoded(image.width(), image.height(), image.argb(), null);
			}
			// The texture goes to the game's cape cache (by its hash), so the Store's answer for it loads from disk.
			String sha256 = CapeChoices.seen(png);
			int[] size = new int[2];
			int[] front = capeFront(image, size);
			return size[0] > 0 && size[1] > 0 ? new Decoded(size[0], size[1], front, new CapeArt(sha256, image)) : null;
		} catch (CapePng.Refused e) {
			throw new LinkException("invalid", false);
		}
	}
}
