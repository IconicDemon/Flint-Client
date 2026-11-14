package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.SocialParts.Button;
import com.flintclient.client.control.rshift.SocialParts.Lines;
import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.flintclient.client.module.social.LibraryLink;
import com.flintclient.client.module.social.LibraryLink.Counts;
import com.flintclient.client.module.social.LibraryLink.Hit;
import com.flintclient.client.module.social.LibraryLink.Item;
import com.flintclient.client.module.social.MenuArt;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The in-game Library ({@link FlintLibraryScreen}): Mods, Resource Packs and Shaders from Modrinth or CurseForge that fit this
 * game's profile, and Your mods, what the profile has. Everything goes through the Flint app (the launcher's own search,
 * installs and removals, {@link LibraryLink}); the page only draws and asks, off the render thread. Each result has its
 * picture, name, author, downloads and short description, and Install, or Installed with Uninstall. A resource pack or
 * shader pack is installed without being turned on (its button opens Minecraft's or Iris' screen to turn it on); a mod works
 * once the Library closes ({@code InstantSwap}). Where Flint has the same thing built in, the result also offers it, on at
 * once. Pictures come small through the app and are kept a little while ({@link MenuArt}); only the cards on screen draw.
 * Sizes in design pixels ({@link MenuStyle#d}).
 */
final class LibraryPage extends Node {
	private static final float PAD = 28;
	private static final float HEAD = 56;
	private static final float BAR = 44;
	private static final float CARD_H = 124;
	private static final float GAP = 10;
	private static final float ICON = 76;
	private static final long TYPE_DELAY = 280;
	private static final long NOTE_MILLIS = 5000;
	private static final int MAX_SHOWN = 96;

	private enum Tab {
		MODS("Mods", "mod"),
		PACKS("Resource Packs", "resourcepack"),
		SHADERS("Shaders", "shader"),
		YOURS("Your mods", null);

		final String text;
		final @Nullable String kind;

		Tab(String text, @Nullable String kind) {
			this.text = text;
			this.kind = kind;
		}
	}

	/**
	 * The same thing built into Flint, by the project's slug (letters and digits only): offered on the result as Flint's own
	 * mod, on at once.
	 */
	private static final Map<String, String> BUILT_IN = new HashMap<>();

	static {
		String[][] pairs = {
			{"appleskin", "saturation"}, {"zoomify", "zoom"}, {"okzoomer", "zoom"}, {"logicalzoom", "zoom"}, {"wizoom", "zoom"},
			{"freelook", "freelook"}, {"perspectivemodredux", "freelook"}, {"3dskinlayers", "3d_skins"}, {"skinlayers3d", "3d_skins"},
			{"betterf3", "custom_f3"}, {"shulkerboxtooltip", "shulker_preview"}, {"controlling", "keybind_search"},
			{"xaerosminimap", "minimap"}, {"xaerosworldmap", "world_map"}, {"togglesprint", "toggle_sneak_sprint"},
			{"soundphysicsremastered", "sound_physics"}, {"clumps", "clumps"}, {"nochatreports", "no_chat_reports"},
			{"fallingleaves", "falling_leaves"}, {"itemphysic", "item_physics"}, {"itemphysiclite", "item_physics"},
			{"autoreconnect", "reconnect"}, {"waveycapes", "wavy_capes"}, {"chunkborders", "chunk_borders"},
		};
		for (String[] p : pairs) {
			BUILT_IN.put(p[0], p[1]);
		}
	}

	private static final ExecutorService WORKER = worker();

	private final Object screen;
	private final Consumer<Object> openScreen;
	private final @Nullable LibraryLink link = LibraryLink.get();
	private final Queue<Runnable> answers = new ConcurrentLinkedQueue<>();
	private final Label title = new Label(Type.HEADER_TITLE, "LIBRARY");
	private final Label countsLabel = new Label(Type.CHIP, "");
	private final List<TabChip> tabs = new ArrayList<>();
	private final SourcePick source;
	/** Takes the clicks while the source list is open (last child, so it is hit first). */
	private final @Nullable SourceMenu sourceMenu;
	private final SocialParts.Field search = new SocialParts.Field("Search mods", 120, true);
	private final Cards cards = new Cards();
	private final ScrollView scroll;
	private final StatePanel state = new StatePanel();
	private final Lines note = new Lines(Type.ROW_NOTE, "");
	private Tab tab = Tab.MODS;
	private Counts counts = Counts.NONE;
	private List<Hit> hits = List.of();
	private long total;
	private boolean searching;
	private boolean loadingMore;
	private @Nullable String problem;
	private int searchSeq;
	private long typedAt = Long.MIN_VALUE;
	private String asked = "";
	private boolean noteBad;
	private long noteAt = Long.MIN_VALUE;
	/** Installs and removals going on, by project id or file name. */
	private final Map<String, String> busy = new HashMap<>();
	/** A mod was added that couldn't load live, or one was taken out: Instant Swap takes over once the Library closes. */
	private boolean needsSwap;
	/** Installs whose mods are still being loaded live. */
	private int liveLoading;

	LibraryPage(Object screen, Consumer<Object> openScreen) {
		this.screen = screen;
		this.openScreen = openScreen;
		for (Tab t : Tab.values()) {
			// A Minecraft no shader loader runs on (1.8.9) has no Shaders tab.
			if (t != Tab.SHADERS || MenuHost.shadersOffered()) {
				tabs.add(add(new TabChip(t)));
			}
		}
		source = add(new SourcePick());
		add(search);
		search.onChange(text -> {
			typedAt = Clock.millis();
		});
		search.onSubmit(text -> typedAt = Long.MIN_VALUE + 1);
		scroll = add(new ScrollView(cards));
		scroll.flatBar(MenuStyle.SCROLL_THUMB, d(10), d(70));
		add(state);
		sourceMenu = add(new SourceMenu());
		refreshCounts();
		runSearch(false);
	}

	private static ExecutorService worker() {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
			Thread thread = new Thread(task, "Flint library");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}

	/** The search field (focused when the Library opens, so typing searches at once). */
	Node searchField() {
		return search;
	}

	/** Whether a mod changed while the Library was open (Instant Swap then takes over when it closes). */
	boolean modsChanged() {
		return needsSwap || liveLoading > 0;
	}

	// ---------- asking the app ----------

	private interface Call<T> {
		T run(LibraryLink link) throws LinkException;
	}

	private <T> void ask(Call<T> call, Consumer<T> done, Consumer<String> failed) {
		LibraryLink l = link;
		if (l == null) {
			failed.accept("unreachable");
			return;
		}
		try {
			WORKER.execute(() -> {
				T result = null;
				String error = null;
				try {
					result = call.run(l);
				} catch (LinkException e) {
					error = e.unreachable() ? "unreachable" : e.getMessage() == null ? "Flint couldn't do that" : e.getMessage();
				} catch (RuntimeException e) {
					error = "Flint sent an answer this game can't read";
				}
				T got = result;
				String why = error;
				answers.add(() -> {
					if (why == null) {
						done.accept(got);
					} else {
						failed.accept(why);
					}
				});
			});
		} catch (RuntimeException e) {
			failed.accept("busy");
		}
	}

	private static String words(String why) {
		return switch (why) {
			case "unreachable" -> "Flint isn't running. Open the Flint app, then try again.";
			case "bad request" -> "This Flint app doesn't have the in-game Library yet. Update Flint.";
			case "timeout" -> "That took too long. Try again.";
			default -> why;
		};
	}

	private void refreshCounts() {
		ask(LibraryLink::counts, c -> {
			counts = c;
			updateCountsLabel();
			if (tab == Tab.YOURS) {
				cards.setItems(c.items());
			}
			invalidate();
		}, why -> problem = words(why));
	}

	private void updateCountsLabel() {
		if (counts.mods() < 0) {
			countsLabel.set("");
			return;
		}
		countsLabel.set(plural(counts.mods(), "mod", "mods") + "  ·  " + plural(counts.resourcepacks(), "resource pack", "resource packs")
			+ (MenuHost.shadersOffered() ? "  ·  " + plural(counts.shaders(), "shader", "shaders") : ""));
	}

	private static String plural(int n, String one, String many) {
		return n + " " + (n == 1 ? one : many);
	}

	private void runSearch(boolean more) {
		if (tab == Tab.YOURS) {
			searching = false;
			cards.setItems(counts.items());
			return;
		}
		String query = search.text().strip();
		asked = query;
		int seq = more ? searchSeq : ++searchSeq;
		int offset = more ? hits.size() : 0;
		if (more) {
			loadingMore = true;
		} else {
			searching = true;
			problem = null;
		}
		String kind = tab.kind;
		String src = source.value();
		ask(l -> l.search(src, kind, query, offset, query.isEmpty() ? "downloads" : null), page -> {
			if (seq != searchSeq) {
				return;
			}
			searching = false;
			loadingMore = false;
			problem = page.unavailable() == null ? null : switch (page.unavailable()) {
				case "no_mods" -> "This profile can't take mods or shader packs.";
				case "ornithe" -> "CurseForge doesn't mark Ornithe builds for 1.8.9. Find 1.8.9 mods on Modrinth.";
				case "not_configured", "unavailable", "offline" -> "CurseForge isn't available right now. Modrinth still works.";
				default -> page.hits().isEmpty() ? "CurseForge isn't available right now (" + page.unavailable() + ")." : null;
			};
			List<Hit> next = new ArrayList<>(more ? hits : List.of());
			for (Hit h : page.hits()) {
				if (next.stream().noneMatch(x -> x.id().equals(h.id())) && next.size() < MAX_SHOWN) {
					next.add(h);
				}
			}
			hits = List.copyOf(next);
			total = page.total();
			cards.setHits(hits, kind);
			if (!more) {
				scroll.scrollToTop();
			}
		}, why -> {
			if (seq != searchSeq) {
				return;
			}
			searching = false;
			loadingMore = false;
			problem = words(why);
			if (!more) {
				hits = List.of();
				cards.setHits(hits, kind);
			}
		});
	}

	/** Takes answers, runs a search once typing pauses, and fetches more as the list nears its end. Render thread. */
	void step() {
		for (Runnable a; (a = answers.poll()) != null;) {
			a.run();
		}
		// The pictures (MenuArt) come back through the menu feeds' answers.
		com.flintclient.client.module.social.MenuFeeds.get().tick();
		if (typedAt != Long.MIN_VALUE && Clock.millis() - typedAt >= TYPE_DELAY) {
			typedAt = Long.MIN_VALUE;
			if (!search.text().strip().equals(asked)) {
				runSearch(false);
			}
		}
		if (tab != Tab.YOURS && !searching && !loadingMore && problem == null && hits.size() < Math.min(total, MAX_SHOWN) && cards.nearEnd()) {
			runSearch(true);
		}
		boolean empty = tab == Tab.YOURS ? counts.items().isEmpty() : hits.isEmpty();
		state.setVisible(empty || searching && hits.isEmpty());
		scroll.setVisible(!state.visible());
	}

	void say(String text, boolean bad) {
		note.set(text);
		noteBad = bad;
		noteAt = Clock.millis();
	}

	// ---------- actions ----------

	private void install(Hit hit, String kind) {
		if (busy.containsKey(hit.id())) {
			return;
		}
		busy.put(hit.id(), "Installing…");
		ask(l -> l.install(kind, hit.id()), done -> {
			busy.remove(hit.id());
			String file = done.fileName();
			hits = replace(hits, hit.id(), h -> h.withInstalled(new LibraryLink.Have(file == null ? "" : file, true)));
			cards.setHits(hits, tab.kind);
			if (done.swap()) {
				// The new mods load into this game live where they can (HotLoader): then nothing swaps for them. Until that is
				// known (a moment), the change counts as one that swaps, so closing the Library meanwhile still brings it in.
				liveLoading++;
				com.flintclient.client.module.social.InstantSwap.loadLive(done.modFiles(), live -> {
					liveLoading--;
					if (!live) {
						needsSwap = true;
					} else {
						say(hit.title() + " is on: loaded into this game", false);
						if (!needsSwap && liveLoading == 0) {
							com.flintclient.client.module.social.InstantSwap.dropStandby();
						}
					}
				});
			}
			int extra = done.added().size() - 1;
			say(extra > 0 ? hit.title() + " installed, with " + plural(extra, "library", "libraries") + " it needs" : hit.title() + " installed", false);
			refreshCounts();
		}, why -> {
			busy.remove(hit.id());
			say(hit.title() + " couldn't be installed · " + words(why), true);
		});
	}

	private void uninstall(String key, String kind, String fileName, String name, @Nullable String hitId) {
		uninstall(key, kind, fileName, name, hitId, false);
	}

	private void uninstall(String key, String kind, String fileName, String name, @Nullable String hitId, boolean also) {
		if (fileName.isEmpty() || busy.containsKey(key)) {
			return;
		}
		busy.put(key, "Removing…");
		ask(l -> l.remove(kind, fileName, also), done -> {
			busy.remove(key);
			if (!done.removed() && !done.needs().isEmpty()) {
				askAboutDependents(key, kind, fileName, name, hitId, done);
				return;
			}
			boolean swap = done.swap();
			if (swap) {
				// Taking a mod out of a running game always takes the swap.
				needsSwap = true;
			}
			if (hitId != null) {
				hits = replace(hits, hitId, h -> h.withInstalled(null));
				cards.setHits(hits, tab.kind);
			}
			say(also ? name + " and what needed it uninstalled" : name + " uninstalled", false);
			refreshCounts();
		}, why -> {
			busy.remove(key);
			say(name + " couldn't be uninstalled · " + words(why), true);
		});
	}

	/** "Iris needs Sodium. Remove Iris too?": both go, or nothing does (never a mod set that can't start). */
	private void askAboutDependents(String key, String kind, String fileName, String name, @Nullable String hitId, LibraryLink.Removed done) {
		String what = done.title().isEmpty() ? name : done.title();
		List<String> names = new ArrayList<>();
		for (LibraryLink.Need n : done.needs()) {
			names.add(n.title());
		}
		String them = names.size() == 1 ? names.get(0) : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
		String title = them + (names.size() == 1 ? " needs " : " need ") + what;
		String body = "Remove " + them + " too? " + what + " can't go alone: Minecraft wouldn't start without it.";
		if (screen instanceof com.flintclient.client.ui.kit.KitScreen kit) {
			kit.showModal(new LibraryConfirm(title, body, names.size() == 1 ? "Remove both" : "Remove all",
				() -> uninstall(key, kind, fileName, name, hitId, true)));
		} else {
			say(title + ": nothing was removed", true);
		}
	}

	private static List<Hit> replace(List<Hit> list, String id, java.util.function.UnaryOperator<Hit> change) {
		List<Hit> out = new ArrayList<>(list.size());
		for (Hit h : list) {
			out.add(h.id().equals(id) ? change.apply(h) : h);
		}
		return List.copyOf(out);
	}

	/** Minecraft's resource pack screen, or Iris' shader pack screen, to turn an installed pack on. */
	private void turnOn(String kind) {
		Object next = "shader".equals(kind) ? MenuHost.shaderPacksScreen(screen) : MenuHost.resourcePacksScreen(screen);
		if (next == null) {
			say("shader".equals(kind) ? "Shaders need Iris: install it from Mods, then turn the pack on in Iris' screen." : "Open Options → Resource Packs to turn it on.", true);
			return;
		}
		openScreen.accept(next);
	}

	private static @Nullable ModuleDescriptor builtIn(String slug, String title) {
		String id = BUILT_IN.get(key(slug));
		if (id == null) {
			id = BUILT_IN.get(key(title));
		}
		return id == null ? null : ModuleRegistry.find(id);
	}

	static String key(String text) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char ch = Character.toLowerCase(text.charAt(i));
			if (ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9') {
				out.append(ch);
			}
		}
		return out.toString();
	}

	private void useBuiltIn(ModuleDescriptor module) {
		Rows.Enabled access = new Rows.Enabled(module);
		if (!Boolean.TRUE.equals(access.get())) {
			access.set(true);
		}
		say("Flint's " + module.name() + " is on · change it in the Right Shift menu", false);
	}

	private static boolean on(ModuleDescriptor module) {
		return Boolean.TRUE.equals(new Rows.Enabled(module).get());
	}

	// ---------- layout ----------

	@Override
	protected void layout() {
		float left = x + d(PAD);
		float inner = w - 2 * d(PAD);
		float headY = y + d(PAD);
		float sw = Math.max(d(150), source.prefWidth());
		source.setBounds(x + w - d(PAD) - sw, headY + d(HEAD) / 2 - d(18), sw, d(36));
		if (sourceMenu != null) {
			sourceMenu.setBounds(x, y, w, h);
		}
		float barY = headY + d(HEAD) + d(8);
		float tx = left;
		float[] prefs = new float[tabs.size()];
		for (int i = 0; i < prefs.length; i++) {
			prefs[i] = tabs.get(i).prefWidth();
		}
		float[] widths = tabWidths(prefs, inner, d(8));
		for (int i = 0; i < widths.length; i++) {
			tabs.get(i).setBounds(tx, barY, widths[i], d(BAR));
			tx += widths[i] + d(8);
		}
		float fieldX = Math.max(tx + d(16), x + w - d(PAD) - Math.min(d(460), inner * 0.42F));
		float top;
		if (!searchOwnRow(x + w - d(PAD) - fieldX, d(240)) || !search.visible()) {
			search.setBounds(fieldX, barY + d(2), x + w - d(PAD) - fieldX, d(BAR) - d(4));
			top = barY + d(BAR) + d(18);
		} else {
			// Not enough room beside the tabs (a narrow window): the search gets its own row.
			search.setBounds(left, barY + d(BAR) + d(10), inner, d(BAR) - d(4));
			top = barY + 2 * d(BAR) + d(24);
		}
		float bottom = y + h - d(PAD) - d(26);
		scroll.setBounds(left - d(6), top, inner + d(12), Math.max(d(60), bottom - top));
		state.setBounds(x, top, w, Math.max(d(60), bottom - top));
	}

	/** The tabs' widths: as they want while they fit {@code inner}, else all narrowed by the same share. */
	static float[] tabWidths(float[] pref, float inner, float gap) {
		float total = 0;
		for (float p : pref) {
			total += p + gap;
		}
		float squeeze = total <= 0 ? 1 : Math.min(1, (inner + gap) / total);
		float[] out = new float[pref.length];
		for (int i = 0; i < pref.length; i++) {
			out[i] = Math.max(0, (pref[i] + gap) * squeeze - gap);
		}
		return out;
	}

	/** Whether the search gets a row of its own (less than {@code least} beside the tabs). */
	static boolean searchOwnRow(float beside, float least) {
		return beside < least;
	}

	/** How many of a card's buttons (widths, the main one first) fit beside its text's least room; the main one always shows. */
	static int buttonsShown(float cardW, float pad, float icon, float textLeast, float gap, float[] buttons) {
		float room = cardW - 2 * pad - icon - pad - textLeast;
		float used = 0;
		int shown = 0;
		for (int i = 0; i < buttons.length; i++) {
			if (i > 0 && used + buttons[i] > room) {
				break;
			}
			used += buttons[i] + gap;
			shown++;
		}
		return shown;
	}

	@Override
	protected void paint(Canvas c) {
		float left = x + d(PAD);
		float headY = y + d(PAD) + d(HEAD) / 2;
		Glyph.BOOK_OPEN.draw(c, left + d(14), headY, d(26), MenuStyle.ACCENT);
		MenuStyle.text(c, title, left + d(40), headY, MenuStyle.TEXT);
		if (!countsLabel.text().isEmpty()) {
			float tx = left + d(40) + title.width(c) + d(16);
			float maxW = source.x() - d(16) - tx;
			float cw = Math.min(countsLabel.width(c) + d(24), maxW);
			if (cw > d(60)) {
				MenuStyle.box(c, tx, headY - d(14), cw, d(28), d(14), MenuStyle.PILL);
				MenuStyle.fitted(c, countsLabel, tx + d(12), headY + d(0.75F), MenuStyle.BADGE_TEXT, cw - d(24));
			}
		}
		long since = Clock.millis() - noteAt;
		if (since < NOTE_MILLIS) {
			float k = Math.min(1, (NOTE_MILLIS - since) / 400.0F);
			note.paint(c, left, y + h - d(PAD) - d(12), w - 2 * d(PAD), Palette.withAlpha(noteBad ? MenuStyle.DANGER_TEXT : MenuStyle.TEXT, k));
		}
	}

	@Override
	protected void paintChildren(Canvas c) {
		super.paintChildren(c);
		// The source list opens over the page.
		source.paintMenu(c);
	}

	// ---------- the cards ----------

	/** The results (or Your mods), one card a row; only the cards on screen draw. */
	private final class Cards extends Node {
		private final List<Card> list = new ArrayList<>();

		void setHits(List<Hit> found, @Nullable String kind) {
			List<Card> next = new ArrayList<>();
			for (Hit hit : found) {
				Card card = list.stream().filter(k -> k.hit != null && k.hit.id().equals(hit.id())).findFirst().orElse(null);
				if (card == null) {
					card = new Card();
				}
				card.show(hit, kind == null ? "mod" : kind);
				next.add(card);
			}
			swap(next);
		}

		void setItems(List<Item> items) {
			List<Card> next = new ArrayList<>();
			for (Item item : items) {
				Card card = new Card();
				card.show(item);
				next.add(card);
			}
			swap(next);
		}

		private void swap(List<Card> next) {
			clearChildren();
			list.clear();
			for (Card card : next) {
				list.add(add(card));
			}
			invalidate();
		}

		/** The last card is on screen (or close): time to fetch more. */
		boolean nearEnd() {
			Node view = parent();
			return !list.isEmpty() && view != null && list.get(list.size() - 1).y() < view.bottom() + d(CARD_H) * 2;
		}

		@Override
		public float prefHeight(float width) {
			return list.isEmpty() ? 0 : list.size() * (d(CARD_H) + d(GAP)) - d(GAP) + 2 * d(6) + (loadingMore ? d(40) : 0);
		}

		@Override
		protected void layout() {
			for (int i = 0; i < list.size(); i++) {
				list.get(i).setBounds(x + d(6), y + d(6) + i * (d(CARD_H) + d(GAP)), w - 2 * d(6), d(CARD_H));
			}
		}

		@Override
		protected void paintChildren(Canvas c) {
			Node view = parent();
			float top = view == null ? Float.NEGATIVE_INFINITY : view.y();
			float bottom = view == null ? Float.POSITIVE_INFINITY : view.bottom();
			for (Card card : list) {
				if (card.bottom() >= top && card.y() <= bottom) {
					paintChild(c, card);
				}
			}
		}
	}

	/** One result or one installed item: picture, name, by whom, downloads, short description; its buttons on the right. */
	private final class Card extends Node {
		private @Nullable Hit hit;
		private @Nullable Item item;
		private String kind = "mod";
		private final Label name = new Label(Type.NAME, "");
		private final Label meta = new Label(Type.CAPTION, "");
		private final Label badge = new Label(Type.BADGE, "");
		private final Label monogram = new Label(Type.PAGE_TITLE, "");
		private final Lines summary = new Lines(Type.ROW_NOTE, "");
		private final Button main;
		private final Button second;
		private final Button extra;
		private @Nullable ModuleDescriptor builtIn;

		Card() {
			main = add(new Button("Install", Glyph.DOWNLOAD, PageControls.Look.ACCENT, this::mainPressed));
			second = add(new Button("Turn on", Glyph.LAYERS, PageControls.Look.PLAIN, this::secondPressed));
			extra = add(new Button("Use Flint's", Glyph.GRID, PageControls.Look.PLAIN, this::extraPressed));
		}

		void show(Hit h, String k) {
			hit = h;
			item = null;
			kind = k;
			name.set(h.title());
			String by = h.author().isEmpty() ? "" : "by " + h.author();
			meta.set(by + (by.isEmpty() ? "" : "   ·   ") + downloads(h.downloads()) + " downloads");
			summary.set(h.description());
			monogram.set(h.title().substring(0, 1).toUpperCase(Locale.ROOT));
			builtIn = "mod".equals(k) ? builtIn(h.slug(), h.title()) : null;
			refresh();
		}

		void show(Item it) {
			item = it;
			hit = null;
			kind = it.kind();
			name.set(it.title());
			String what = switch (it.kind()) {
				case "mod" -> "Mod";
				case "resourcepack" -> "Resource pack";
				default -> "Shader pack";
			};
			meta.set(what + (it.version().isEmpty() ? "" : "   ·   " + it.version()) + (it.enabled() ? "" : "   ·   off"));
			summary.set(it.fileName());
			monogram.set(it.title().isEmpty() ? "?" : it.title().substring(0, 1).toUpperCase(Locale.ROOT));
			builtIn = null;
			refresh();
		}

		private String key() {
			return hit != null ? hit.id() : item != null ? item.fileName() : "";
		}

		private boolean installed() {
			return item != null || hit != null && hit.installed() != null;
		}

		/** The buttons for what the card is now. */
		void refresh() {
			String doing = busy.get(key());
			boolean pack = !"mod".equals(kind);
			if (doing != null) {
				main.setText(doing);
				main.setLook(PageControls.Look.PLAIN);
				main.setDisabled(doing);
			} else if (installed()) {
				main.setText("Uninstall");
				main.setGlyph(Glyph.TRASH);
				main.setLook(PageControls.Look.PLAIN);
				main.setEnabled(true);
			} else {
				main.setText("Install");
				main.setGlyph(Glyph.DOWNLOAD);
				main.setLook(PageControls.Look.ACCENT);
				main.setEnabled(true);
			}
			second.setVisible(installed() && pack && doing == null);
			second.setText("shader".equals(kind) ? "Turn on in Shaders" : "Turn on in Resource Packs");
			badge.set(installed() ? "INSTALLED" : "");
			boolean offer = builtIn != null && doing == null;
			extra.setVisible(offer);
			if (offer) {
				extra.setText(on(builtIn) ? "Flint's " + builtIn.name() + " is on" : "Use Flint's " + builtIn.name());
				if (on(builtIn)) {
					extra.setDisabled("Already on in the Right Shift menu");
				} else {
					extra.setEnabled(true);
				}
			}
			invalidate();
		}

		private void mainPressed() {
			if (installed()) {
				String file = item != null ? item.fileName() : hit.installed().fileName();
				uninstall(key(), kind, file, name.text(), hit == null ? null : hit.id());
			} else if (hit != null) {
				install(hit, kind);
			}
			refresh();
		}

		private void secondPressed() {
			turnOn(kind);
		}

		private void extraPressed() {
			if (builtIn != null) {
				useBuiltIn(builtIn);
				refresh();
			}
		}

		@Override
		protected void layout() {
			float pad = d(18);
			// The text keeps at least this much room beside the picture.
			float room = w - 2 * pad - d(ICON) - d(18) - d(260);
			float bx = x + w - pad;
			float by = y + (h - d(Button.H)) / 2;
			float used = 0;
			for (Button b : new Button[] {main, second, extra}) {
				if (!b.visible()) {
					continue;
				}
				float bw = Math.max(d(130), b.prefWidth());
				if (b != main && used + bw > room) {
					b.setBounds(x, y, 0, 0);
					continue;
				}
				used += bw + d(10);
				bx -= bw;
				b.setBounds(bx, by, bw, d(Button.H));
				bx -= d(10);
			}
		}

		@Override
		protected void paint(Canvas c) {
			// Cheap: buttons follow installs and removals going on elsewhere on the page.
			if (busy.containsKey(key()) != (main.disabledReason() != null)) {
				refresh();
			}
			float rad = MenuStyle.radius();
			int fill = Palette.lerp(MenuStyle.TILE, MenuStyle.TILE_HOVER, hover);
			MenuStyle.framed(c, x, y, w, h, rad, fill, Palette.lerp(MenuStyle.TILE_EDGE, MenuStyle.CARD_EDGE_HOVER, hover));
			float pad = d(18);
			float iy = y + (h - d(ICON)) / 2;
			paintIcon(c, x + pad, iy, d(ICON), fill);
			float tx = x + pad + d(ICON) + d(18);
			float buttonsLeft = x + w - pad;
			for (Button b : new Button[] {main, second, extra}) {
				if (b.visible() && b.width() > 0) {
					buttonsLeft = Math.min(buttonsLeft, b.x());
				}
			}
			float tw = Math.max(d(80), buttonsLeft - d(20) - tx);
			float nameW = Math.min(name.width(c), tw);
			MenuStyle.fitted(c, name, tx, y + d(24), MenuStyle.TEXT, tw);
			if (!badge.text().isEmpty() && nameW + d(110) < tw) {
				float bxx = tx + nameW + d(12);
				float bw = badge.width(c) + d(16);
				MenuStyle.framed(c, bxx, y + d(13), bw, d(22), d(11), Palette.withAlpha(MenuStyle.TEXT, 0.06F), Palette.withAlpha(MenuStyle.TEXT, 0.22F));
				MenuStyle.text(c, badge, bxx + d(8), y + d(24.5F), MenuStyle.ROW_TEXT);
			}
			MenuStyle.fitted(c, meta, tx, y + d(46), MenuStyle.NEUTRAL, tw);
			List<TextLine> lines = summary.lines(c, tw);
			float pitch = Lines.pitch(c);
			float sy = y + d(60);
			for (int i = 0; i < Math.min(2, lines.size()); i++) {
				TextLine line = lines.get(i);
				if (i == 1 && lines.size() > 2) {
					line = new TextLine(line.face(), line.text() + "…");
				}
				c.text(line, c.px(tx), c.baselineFor(line, c.px(sy + pitch * i + pitch / 2)), MenuStyle.SENTENCE_TEXT, c.px(tw));
			}
		}

		private void paintIcon(Canvas c, float ix, float iy, float size, int fill) {
			String url = hit != null ? hit.icon() : item != null ? item.icon() : null;
			Texture texture = null;
			MenuArt.Entry entry = null;
			LibraryLink l = link;
			if (url != null && l != null) {
				String u = url;
				entry = MenuArt.art("library/" + u, 128, 128, ignored -> l.art(u));
				texture = entry.texture();
			}
			int px = c.px(ix);
			int py = c.px(iy);
			int ps = c.px(size);
			if (texture != null && entry.width() > 0) {
				c.texture(texture, px, py, ps, ps, entry.width(), entry.height(), 0xFFFFFFFF);
				c.roundCorners(px, py, ps, ps, c.px(MenuStyle.radius()), fill);
			} else {
				MenuStyle.framed(c, ix, iy, size, size, MenuStyle.radius(), MenuStyle.CHIP, MenuStyle.TILE_EDGE);
				MenuStyle.centered(c, monogram, ix + size / 2, iy + size / 2 + d(1), MenuStyle.NEUTRAL, size);
			}
		}
	}

	static String downloads(long n) {
		if (n >= 1_000_000) {
			return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0).replace(".0M", "M");
		}
		if (n >= 1_000) {
			return String.format(Locale.ROOT, "%.1fK", n / 1_000.0).replace(".0K", "K");
		}
		return Long.toString(n);
	}

	// ---------- tabs and the source ----------

	private final class TabChip extends Pressable {
		private final Tab of;
		private final Label label;
		private float on = -1;

		TabChip(Tab of) {
			super(null);
			this.of = of;
			this.label = new Label(Type.CHIP, of.text);
			setAction(() -> pick(of));
			name(of.text);
		}

		@Override
		public float prefWidth() {
			return label.width(measure()) + d(36);
		}

		@Override
		public String narration() {
			return accessibleName() + (tab == of ? ", selected" : "");
		}

		@Override
		protected void paint(Canvas c) {
			on = MenuStyle.ease(on, tab == of);
			float rad = MenuStyle.radius();
			MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(Palette.lerp(MenuStyle.CHIP, MenuStyle.ACCENT, on), MenuStyle.ACCENT_PRESS, press * on));
			int ink = Palette.lerp(Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover * 0.6F), MenuStyle.ON_ACCENT, on);
			MenuStyle.centered(c, label, x + w / 2, y + h / 2 + d(0.75F), ink, w);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	private void pick(Tab next) {
		if (tab == next) {
			return;
		}
		tab = next;
		search.setPlaceholder(switch (next) {
			case MODS -> "Search mods";
			case PACKS -> "Search resource packs";
			case SHADERS -> "Search shaders";
			case YOURS -> "Search mods";
		});
		// Each kind starts from its most downloaded, not the last kind's words.
		search.setText("");
		search.setVisible(next != Tab.YOURS);
		source.setOpen(false);
		source.setVisible(next != Tab.YOURS);
		hits = List.of();
		if (next == Tab.YOURS) {
			refreshCounts();
		}
		runSearch(false);
	}

	/**
	 * Modrinth or CurseForge: a small list that opens under it. While it is open, {@link SourceMenu} (the page's last child, so
	 * hit first) takes the clicks: one on an option picks it and searches again, one anywhere else closes the list. The cards
	 * under the list never get them. Keyboard: Enter or Down opens it, Up/Down move, Enter picks, Escape closes.
	 */
	private final class SourcePick extends Pressable {
		private static final String[] VALUES = {"modrinth", "curseforge"};
		private static final String[] NAMES = {"Modrinth", "CurseForge"};
		/** An option's height, and the list's inset (reference units through {@code d}). */
		private static final float ROW = 36;
		private static final float INSET = 4;
		private int chosen;
		boolean open;
		/** The option the keyboard is on while the list is open. */
		private int lit;
		private final Label label = new Label(Type.CHIP, NAMES[0]);
		private final Label[] options = {new Label(Type.CHIP, NAMES[0]), new Label(Type.CHIP, NAMES[1])};

		SourcePick() {
			super(null);
			// Closed, a click or Enter opens the list; open, Enter picks the option the keyboard is on (clicks then go to SourceMenu).
			setAction(() -> {
				if (open) {
					choose(lit);
				} else {
					setOpen(true);
				}
			});
			name("Source: Modrinth or CurseForge");
		}

		String value() {
			return VALUES[chosen];
		}

		void setOpen(boolean value) {
			open = value && visible();
			lit = chosen;
			if (sourceMenu != null) {
				sourceMenu.setVisible(open);
			}
		}

		/** Picks option {@code i}: the list closes, the search box takes the typing, and a new source searches again. */
		void choose(int i) {
			setOpen(false);
			// Typing goes on in the search at once (from the keyboard, its ring shows as keyboard focus does).
			KitScreen s = screen();
			if (s != null && search.visible()) {
				if (s.focusVisible()) {
					s.focusFromKeyboard(search);
				} else {
					s.focus(search);
				}
			}
			if (i >= 0 && i < VALUES.length && i != chosen) {
				chosen = i;
				label.set(NAMES[i]);
				hits = List.of();
				cards.setHits(hits, tab.kind);
				runSearch(false);
			}
		}

		private float menuTop() {
			return y + h + d(6);
		}

		private float menuHeight() {
			return 2 * d(INSET) + NAMES.length * d(ROW);
		}

		/** The option at a point of the open list, or -1. */
		int optionAt(float mx, float my) {
			float top = menuTop() + d(INSET);
			if (mx < x || mx > x + w || my < top || my >= top + NAMES.length * d(ROW)) {
				return -1;
			}
			return Math.clamp((int) ((my - top) / d(ROW)), 0, NAMES.length - 1);
		}

		@Override
		public float prefWidth() {
			return Math.max(options[0].width(measure()), options[1].width(measure())) + d(56);
		}

		@Override
		public String narration() {
			return "Source, " + NAMES[open ? lit : chosen] + (open ? ", list open, " + (lit + 1) + " of " + NAMES.length : ", list");
		}

		@Override
		public boolean handlesEscape() {
			return open;
		}

		@Override
		public boolean keyDown(KeyInput event) {
			if (open) {
				if (event.isEscape()) {
					setOpen(false);
					return true;
				}
				if (event.isUp() || event.isDown()) {
					lit = Math.clamp(lit + (event.isDown() ? 1 : -1), 0, NAMES.length - 1);
					return true;
				}
			} else if (event.isDown()) {
				setOpen(true);
				return true;
			}
			return super.keyDown(event);
		}

		@Override
		protected void paint(Canvas c) {
			if (open && !visible()) {
				setOpen(false);
			}
			float rad = MenuStyle.radius();
			MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(MenuStyle.CHIP, MenuStyle.CHIP_HOVER, open ? 1 : hover));
			MenuStyle.text(c, label, x + d(14), y + h / 2 + d(0.75F), MenuStyle.TEXT);
			(open ? Glyph.CHEVRON_UP : Glyph.CHEVRON_DOWN).draw(c, x + w - d(18), y + h / 2, d(11), MenuStyle.NEUTRAL);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}

		void paintMenu(Canvas c) {
			if (!open || !visible()) {
				return;
			}
			KitScreen s = screen();
			int under = s == null ? -1 : optionAt(s.mouseX(), s.mouseY());
			float my = menuTop();
			MenuStyle.framed(c, x, my, w, menuHeight(), MenuStyle.radius(), MenuStyle.WINDOW, MenuStyle.WINDOW_EDGE);
			for (int i = 0; i < NAMES.length; i++) {
				float oy = my + d(INSET) + i * d(ROW);
				float bx = x + d(INSET);
				float bw = w - 2 * d(INSET);
				if (i == chosen) {
					MenuStyle.box(c, bx, oy, bw, d(ROW), MenuStyle.radius(), Palette.withAlpha(MenuStyle.ACCENT, 0.25F));
				} else if (i == under || under < 0 && i == lit && keyboardFocused()) {
					MenuStyle.box(c, bx, oy, bw, d(ROW), MenuStyle.radius(), MenuStyle.CHIP_HOVER);
				}
				MenuStyle.text(c, options[i], x + d(14), oy + d(ROW) / 2 + d(0.75F), i == chosen || i == under ? MenuStyle.TEXT : MenuStyle.ROW_TEXT);
			}
		}
	}

	/**
	 * The open source list's catcher: the whole page, above everything on it, visible only while the list is open. A click on
	 * an option picks it; a click anywhere else (the source button included) closes the list without reaching what is under it.
	 */
	private final class SourceMenu extends Pressable {
		/** The option the press went down on, picked when it is released on the same one. */
		private int pressed = -1;

		SourceMenu() {
			super(null);
			setAction(() -> source.choose(pressed));
			setVisible(false);
		}

		@Override
		public boolean focusable() {
			return false;
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			pressed = button == Kit.LEFT_BUTTON ? source.optionAt(mx, my) : -1;
			if (pressed < 0) {
				source.setOpen(false);
				return false;
			}
			return super.mouseDown(mx, my, button);
		}

		@Override
		public void mouseUp(float mx, float my, int button) {
			if (button == Kit.LEFT_BUTTON && source.optionAt(mx, my) == pressed) {
				super.mouseUp(mx, my, button);
			}
			pressed = -1;
		}

		@Override
		public boolean scroll(float mx, float my, double amount) {
			source.setOpen(false);
			return true;
		}
	}

	// ---------- nothing to show ----------

	private final class StatePanel extends Node {
		private final Label heading = new Label(Type.EMPTY, "");
		private final Lines body = new Lines(Type.EMPTY_NOTE, "");

		@Override
		protected void paint(Canvas c) {
			boolean waiting = searching;
			String[] words;
			if (link == null) {
				words = new String[] {"Open Flint to use the Library", "The Library comes from the Flint app. Start Minecraft from Flint to add mods, resource packs and shaders."};
			} else if (searching) {
				words = new String[] {"Searching…", tab == Tab.YOURS ? "" : "Only what fits Minecraft " + (counts.gameVersion().isEmpty() ? "this version" : counts.gameVersion()) + " shows here."};
			} else if (problem != null) {
				words = new String[] {"The Library isn't available right now", problem};
			} else if (tab == Tab.YOURS) {
				words = new String[] {"Nothing installed yet", "Mods, resource packs and shaders you add show here."};
			} else {
				words = new String[] {"Nothing found", "Try other words, or the other source."};
			}
			heading.set(words[0]);
			body.set(words[1]);
			float cx = x + w / 2;
			float cy = y + h / 2 - d(40);
			float disc = d(64);
			MenuStyle.box(c, cx - disc / 2, cy - disc / 2 - d(30), disc, disc, disc / 2, Palette.withAlpha(MenuStyle.ACCENT, 0.14F));
			(waiting ? Glyph.HOURGLASS : Glyph.BOOK_OPEN).draw(c, cx, cy - d(30), d(26), MenuStyle.ACCENT);
			if (waiting && !Kit.reduced()) {
				long t = Clock.millis();
				for (int i = 0; i < 3; i++) {
					float a = 0.25F + 0.75F * (float) Math.max(0, Math.sin((t / 260.0) - i * 0.8));
					SocialParts.dot(c, cx - d(14) + i * d(14), cy + d(14), d(6), Palette.withAlpha(MenuStyle.TEXT, a));
				}
			}
			MenuStyle.centered(c, heading, cx, cy + d(40), MenuStyle.EMPTY_TEXT, w - d(80));
			body.paintCentered(c, cx, cy + d(58), Math.min(w - d(120), d(640)), MenuStyle.EMPTY_NOTE);
		}
	}
}
