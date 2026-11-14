package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.feature.CapeChoices;
import com.flintclient.client.control.feature.CapeMods;
import com.flintclient.client.control.feature.CapePng;
import com.flintclient.client.control.feature.CosmeticKinds;
import com.flintclient.client.control.feature.CosmeticKinds.Category;
import com.flintclient.client.control.feature.CosmeticPreview;
import com.flintclient.client.control.feature.FlintPlayers;
import com.flintclient.client.control.feature.Outfits;
import com.flintclient.client.control.feature.Outfits.Outfit;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.SocialParts.Button;
import com.flintclient.client.control.rshift.SocialParts.Lines;
import com.flintclient.client.control.rshift.WardrobeModel.Filter;
import com.flintclient.client.control.rshift.WardrobeModel.Sort;
import com.flintclient.client.control.store.StoreEndpoint;
import com.flintclient.client.module.social.FriendsClient;
import com.flintclient.client.module.social.SocialLink;
import com.flintclient.client.module.social.SocialLink.Cosmetics;
import com.flintclient.client.module.social.SocialLink.Item;
import com.flintclient.client.module.social.SocialLink.Skin;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

/**
 * The wardrobe: Flint's in-game Cosmetics window on 26.3 and newer ({@link FlintCosmeticsScreen} shows it there; the older lines
 * keep the window they had). Three columns in the Right Shift menu's look, calm and in Flint's black, greys and white, the
 * orange only for what is selected and the one main action:
 *
 * <ul>
 * <li><b>Categories</b> on the left: the Flint Store's eight kinds (Capes, Wings, Hats, Auras, Pets, Shields, Gloves, Emotes),
 * each with a small count and a green dot while one is worn; under a rule, Outfits, Skin Changer and Badges; then the Flint
 * Store.</li>
 * <li><b>The items</b> in the middle: a grid of cards (the picture, or the 3D renderer's mini-render where one draws it
 * ({@link CosmeticPreview}); the Store's others dimmed with a small lock), with a search and compact sort and filter menus.
 * Emotes have their wheel of 8 above them.</li>
 * <li><b>Your loadout</b> on the right: the player in 3D wearing the selection (drag to turn it), with Equip or Unequip.</li>
 * </ul>
 *
 * <p>A click selects (the preview shows it on you); Equip, a second click or Enter wears it. Wearing is the old window's: shown
 * at once (the cape on you in the same tick), asked of the Store in the background, the latest pick wins, a refusal puts back
 * what the Store last said. The emote wheel works the same way ({@code emotes} on the game link). Everything opens at once:
 * the list, pictures and animations are the session's ({@link CosmeticShelf}). Fits any window: the sidebar becomes icons,
 * the panels narrow, and the lists scroll.
 *
 * <p>Skin Changer: a skin is added by a Minecraft username or a skin picture's address and only appears in the grid; it goes
 * on the account when the player selects it, chooses Classic or Slim and presses Apply (the Flint app's skin list and
 * Minecraft's skin upload, through the game link: {@code skinsList}, {@code skinAdd}, {@code skinApply}).
 */
final class WardrobePage extends Node {
	private static final float PAD = 32;
	private static final float HEAD = 56;
	private static final float GAP = 16;
	private static final float SIDE_W = 232;
	private static final float SIDE_ICONS = 60;
	private static final float LOAD_W = 344;
	private static final float ROW_H = 44;
	private static final float CARD_GAP = 12;
	private static final float CARD_PAD = 8;
	/** A card's width in window pixels to aim for: compact, five or six to a row at 1080p at Large; {@link #cardPx} at the menu's size. */
	private static final int CARD_PX = 136;
	/** A click again on the selected card within this long wears it. */
	private static final long DOUBLE_MILLIS = 450;
	/** The preview turns once in this long while nobody drags it. */
	private static final float TURN_MILLIS = 18_000;

	/** The sidebar, top to bottom; null is the rule between the Store's kinds and the game's own pages. */
	private static final List<@Nullable Category> SIDEBAR = java.util.Arrays.asList(Category.CAPES, Category.WINGS, Category.HEADWEAR, Category.AURAS,
		Category.PETS, Category.SHIELDS, Category.GLOVES, Category.EMOTES, Category.BODYWEAR, null, Category.OUTFITS, Category.SKINS, Category.BADGES);

	/** Each category's icon (Font Awesome's solid set, the menu's one glyph font). */
	static int icon(Category c) {
		return switch (c) {
			case CAPES -> 0xF024;
			case WINGS -> 0xF56B;
			case HEADWEAR -> 0xF8C0;
			case AURAS -> 0xE2CA;
			case PETS -> 0xF1B0;
			case SHIELDS -> 0xF3ED;
			case GLOVES -> 0xF256;
			case EMOTES -> 0xF118;
			case BODYWEAR -> 0xF553;
			case MORE -> 0xF141;
			case OUTFITS -> 0xF5FD;
			case SKINS -> 0xF4FF;
			case BADGES -> 0xF559;
		};
	}

	private static final int LOCK = 0xF023;
	private static final int CHECK = 0xF00C;
	private static final int SORT = 0xF160;
	private static final int FILTER = 0xF0B0;

	static void glyph(Canvas c, int codepoint, float cx, float cy, float size, int argb) {
		int px = Math.max(1, c.px(size));
		c.glyph(codepoint, c.px(cx) - px / 2, c.px(cy) - px / 2, px, argb);
	}

	/** Skin work (Mojang lookups, the upload) runs here, never on the Friends worker, so a slow lookup holds nothing else up. */
	private static final ExecutorService SKIN_WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Flint skin changer");
		thread.setDaemon(true);
		return thread;
	});

	/** The skin the player applied this session (until Minecraft's own lookup catches up): the preview wears it as theirs. */
	private static @Nullable Texture appliedSkin;
	private static boolean appliedSlim;
	/** Skins' textures this session, by id (at most {@link #MAX_SKIN_TEXTURES}). */
	private static final Map<String, Texture> SKIN_TEXTURES = new LinkedHashMap<>(16, 0.75F, true);
	private static final int MAX_SKIN_TEXTURES = 24;
	/** Capes' whole textures for the preview player, by SHA-256. */
	private static final Map<String, Texture> CAPE_TEXTURES = new LinkedHashMap<>(16, 0.75F, true);
	private static final int MAX_CAPE_TEXTURES = 12;
	private static final Map<FlintPlayers.Tag, Texture> BADGE_TEXTURES = new HashMap<>();
	/** The category last open (the window opens on it again this session). */
	private static Category lastCategory = Category.CAPES;

	private final FlintCosmeticsScreen screen;
	private final Consumer<String> openLink;
	final FriendsClient client = FriendsClient.get(FlintFriendsScreen::playing);
	private final @Nullable SocialLink link = SocialLink.get();
	private final CosmeticShelf shelf = CosmeticShelf.get();
	private final List<String> capeMods = CapeMods.inThisGame();
	private boolean noticeShown;

	// ---------- what is shown ----------

	private @Nullable Cosmetics shown;
	private @Nullable String problem;
	private boolean loading;
	private boolean asked;
	private long launcherSeen = CapeChoices.launcherChanges();
	/** Slot → the last pick ("" for none) the Store hasn't taken yet: shown as worn. */
	private final Map<String, String> wearing = new HashMap<>();
	/** Slot → the pick sent and not answered yet. */
	private final Map<String, String> sending = new HashMap<>();
	private long capeSerial;
	private @Nullable String capePick;

	private Category category = SIDEBAR.contains(lastCategory) ? lastCategory : Category.CAPES;
	private String query = "";
	private Sort sort = Sort.NEWEST;
	private Filter filter = Filter.ALL;
	/** The selected card's key ({@link Entry#key}), or null. */
	private @Nullable String selectedKey;
	/** Each category's selection, kept while the window is open (switching back finds it selected again). */
	private final Map<Category, String> selectedIn = new HashMap<>();
	/** Slot → the item last tried on there (selected) in any category: the preview player wears them all together. */
	private final Map<String, Item> tryOn = new LinkedHashMap<>();
	private @Nullable Entry selected;
	private long selectedAt;
	/** The emote wheel's place picked (0–7), or -1. */
	private int wheelPlace = -1;
	/** The wheel as the player set it and the Store hasn't taken yet (null: as the Store says), and the one on its way. */
	private @Nullable List<String> wheelWant;
	private @Nullable List<String> wheelSending;

	// ---------- the skin changer ----------

	private List<Skin> skins = List.of();
	private boolean skinsAsked;
	private boolean skinsLoading;
	private @Nullable String skinsProblem;
	private boolean adding;
	private boolean applying;
	private boolean byUrl;
	/** The arm model chosen for the selected skin (null: its suggestion). */
	private @Nullable String variant;
	private final java.util.Set<String> skinArtAsked = new java.util.HashSet<>();
	private final ConcurrentLinkedQueue<Runnable> skinAnswers = new ConcurrentLinkedQueue<>();

	// ---------- nodes ----------

	private final Label title = new Label(Type.HEADER_TITLE, "COSMETICS");
	private final Label total = new Label(Type.CAPTION, "");
	private final Label stale = new Label(Type.CAPTION, "OFFLINE · THE STORE'S LAST LIST");
	private final List<CategoryRow> rows = new ArrayList<>();
	private final FriendsPage.Stack rowColumn = new FriendsPage.Stack(d(2));
	private final ScrollView rowView;
	private final Button shop;
	private final SocialParts.Icon shopSmall;
	private final SocialParts.Field search;
	private final MenuButton sortButton;
	private final MenuButton filterButton;
	private final Button saveOutfit;
	private final SocialParts.Field skinField;
	private final Choice sourceChoice;
	private final Button addSkin;
	private final Lines notice = new Lines(Type.ROW_NOTE, "");
	private int noticeDot = MenuStyle.NEUTRAL;
	private final FriendsPage.Stack column = new FriendsPage.Stack(d(16));
	private final ScrollView view;
	private final Wheel wheel = new Wheel();
	private final Grid grid = new Grid();
	private final Loadout loadout;
	private final Button retry;
	private final Label emptyLine = new Label(Type.EMPTY_NOTE, "");
	private final Button emptyAction;
	private String builtKey = "";

	// ---------- layout results ----------

	private float sideX;
	private float sideW;
	private float centreX;
	private float centreW;
	private float loadX;
	private float loadW;
	private float bodyY;
	private float bodyH;
	private boolean icons;
	private final long madeAt = System.nanoTime();
	private boolean readyLogged;
	private int tilesDrawn;
	private int tilesReady;
	private @Nullable String who;
	private long epochMillis;

	WardrobePage(FlintCosmeticsScreen screen, Consumer<String> openLink) {
		this.screen = screen;
		this.openLink = openLink;
		for (Category c : SIDEBAR) {
			if (c == null) {
				rowColumn.add(new Rule());
			} else {
				CategoryRow row = new CategoryRow(c);
				rows.add(row);
				rowColumn.add(row);
			}
		}
		rowView = add(new ScrollView(rowColumn));
		rowView.flatBar(MenuStyle.SCROLL_THUMB, d(6), d(60));
		shop = add(new Button("Flint Store", Glyph.GEM, PageControls.Look.PLAIN, this::openStore));
		shop.tooltip("Get more cosmetics in the Flint Store");
		shopSmall = add(new SocialParts.Icon(Glyph.GEM, "Flint Store", false, this::openStore));
		search = add(new SocialParts.Field("Search", 48, true));
		search.name("Search this category");
		search.onChange(this::filterBy);
		search.onEscape(screen::escapeFromSearch);
		sortButton = add(new MenuButton(SORT, "Sort", List.of("Newest", "Name", "Equipped first"), () -> sort.ordinal(), i -> {
			sort = Sort.values()[i];
			rebuild(true);
		}));
		filterButton = add(new MenuButton(FILTER, "Show", List.of("All", "Owned", "Locked", "Animated"), () -> filter.ordinal(), i -> {
			filter = Filter.values()[i];
			rebuild(true);
		}));
		saveOutfit = add(new Button("Save current", Glyph.PLUS, PageControls.Look.PLAIN, this::saveOutfit));
		saveOutfit.tooltip("Saves what you're wearing as an outfit");
		skinField = add(new SocialParts.Field("Minecraft username", 300, false));
		skinField.name("Skin to add");
		skinField.onSubmit(text -> addSkin());
		skinField.onEscape(screen::escapeFromSearch);
		sourceChoice = add(new Choice(List.of("Username", "Image URL"), i -> {
			byUrl = i == 1;
			skinField.setPlaceholder(byUrl ? "https://…/skin.png" : "Minecraft username");
			skinField.setText("");
		}));
		sourceChoice.name("Add a skin by");
		addSkin = add(new Button("Add", Glyph.PLUS, PageControls.Look.PLAIN, this::addSkin));
		addSkin.tooltip("Adds it to your skins below. Nothing changes until you press Apply.");
		column.add(wheel);
		column.add(grid);
		view = add(new ScrollView(column));
		view.flatBar(MenuStyle.SCROLL_THUMB, d(8), d(90));
		loadout = add(new Loadout());
		retry = add(new Button("Try again", Glyph.RESET, PageControls.Look.PLAIN, this::load));
		emptyAction = add(new Button("Open the Flint Store", Glyph.GEM, PageControls.Look.PLAIN, this::openStore));
		shown = shelf.list(client.playing());
		shelf.opened();
		if (category == Category.SKINS) {
			loadSkins();
		}
		rebuild(true);
		load();
	}

	// ---------- the screen's hooks ----------

	void step() {
		client.tick();
		for (Runnable r; (r = skinAnswers.poll()) != null;) {
			r.run();
		}
		long changes = CapeChoices.launcherChanges();
		if (changes != launcherSeen && sending.isEmpty() && wheelSending == null && !loading && link != null) {
			launcherSeen = changes;
			load();
		}
		rebuild(false);
	}

	/** Escape clears the search (or the skin field) first; false when there was nothing to clear. */
	boolean clearSearch() {
		if (!search.text().isEmpty() || !query.isEmpty()) {
			search.setText("");
			filterBy("");
			return true;
		}
		if (!skinField.text().isEmpty()) {
			skinField.setText("");
			return true;
		}
		return false;
	}

	// ---------- loading and wearing ----------

	private void load() {
		String player = client.playing();
		if (link == null || player == null) {
			problem = link == null ? "Start Minecraft from the Flint app to see your cosmetics here." : "This game's account couldn't be read.";
			return;
		}
		loading = true;
		asked = true;
		client.run(() -> link.cosmetics(player), got -> {
			loading = false;
			took(got);
			shelf.remember(player, got);
			shelf.preload(player, got);
			problem = null;
			wearing.keySet().retainAll(sending.keySet());
		}, code -> {
			loading = false;
			problem = switch (code) {
				case "unreachable" -> "Flint isn't running any more. Open the Flint app, then try again.";
				case "bad request" -> "This copy of the Flint app can't show cosmetics in game yet. Update Flint.";
				case "timeout" -> "The Flint Store took too long to answer. Try again.";
				default -> code;
			};
		});
		// The badges the Flint app has for the account (the website's /me): none, or an older app, keeps the marks worked
		// out here. Asked on its own so a Store without badges never holds up the cosmetics.
		if (!badgeSending) {
			client.run(() -> link.badges(player), got -> {
				if (!badgeSending && player.equals(client.playing())) {
					FlintPlayers.fromLauncher(got.badges(), got.badge());
					invalidate();
				}
			}, code -> {
			});
		}
	}

	/** A badge pick on its way to the Store (through the Flint app). */
	private boolean badgeSending;

	/**
	 * Wears {@code tag}: shown at once on your own nametag; with the Flint app's list, saved by the Store so other players see
	 * it (a refusal puts the previous pick back and says why). Without that list, it stays in this game.
	 */
	private void wearBadge(FlintPlayers.Tag tag) {
		String player = client.playing();
		if (link == null || player == null || !FlintPlayers.fromLauncher()) {
			FlintPlayers.wear(tag);
			invalidate();
			return;
		}
		if (tag == FlintPlayers.worn() || badgeSending) {
			return;
		}
		FlintPlayers.Tag before = FlintPlayers.pick();
		FlintPlayers.wear(tag);
		invalidate();
		badgeSending = true;
		client.run(() -> link.badgeSet(player, tag.key()), got -> {
			badgeSending = false;
			if (player.equals(client.playing())) {
				FlintPlayers.fromLauncher(got.badges(), got.badge());
				invalidate();
			}
		}, code -> {
			badgeSending = false;
			if (player.equals(client.playing())) {
				FlintPlayers.restorePick(before);
				invalidate();
				say(switch (code) {
					case "unreachable" -> "Flint isn't running any more. Open the Flint app, then try again.";
					case "bad request" -> "This copy of the Flint app can't save your badge yet. Update Flint.";
					case "timeout" -> "The Flint Store took too long to answer. Try again.";
					default -> code;
				}, MenuStyle.WARN_TEXT);
			}
		});
	}

	private void took(Cosmetics got) {
		Cosmetics now = shown;
		if (now == null || got.version() < 0 || now.version() < 0 || got.version() >= now.version()) {
			shown = got;
		}
	}

	/** Whether {@code item} is worn now (an emote: on the wheel), the picks on their way included. */
	private boolean worn(Item item) {
		if ("emote".equals(item.kind())) {
			List<String> now = wheelNow();
			return now != null && now.contains(item.id());
		}
		String slot = item.slot();
		if (slot == null) {
			return false;
		}
		String putting = wearing.get(slot);
		if (putting != null) {
			return putting.equals(item.id());
		}
		Cosmetics now = shown;
		return now != null && item.id().equals(now.worn(slot));
	}

	/** What each slot wears now (the picks on their way included); a slot wearing nothing is left out. */
	private Map<String, String> wornNow() {
		Map<String, String> out = new LinkedHashMap<>();
		Cosmetics now = shown;
		for (String slot : CosmeticKinds.SLOTS) {
			String id = wearing.containsKey(slot) ? wearing.get(slot) : now == null ? null : now.worn(slot);
			if (id != null && !id.isEmpty()) {
				out.put(slot, id);
			}
		}
		return out;
	}

	/** Wears {@code id} in {@code slot} ({@code null}: takes it off): shown at once, asked of the Store; the latest pick wins. */
	private void wear(String slot, @Nullable String id) {
		String player = client.playing();
		Cosmetics now = shown;
		if (link == null || player == null || now == null || now.stale()) {
			if (now != null && now.stale()) {
				say("You're offline: what you wear can't change right now.", MenuStyle.WARN_TEXT);
			}
			return;
		}
		String want = id == null ? "" : id;
		String showing = wearing.containsKey(slot) ? wearing.get(slot) : Objects.requireNonNullElse(now.worn(slot), "");
		if (want.equals(showing)) {
			return;
		}
		if ("cape".equals(slot) && id != null && !capeMods.isEmpty() && !noticeShown) {
			noticeShown = true;
			screen.showModal(new FlintCosmeticsScreen.CapeNotice(capeMods));
		}
		wearing.put(slot, want);
		if ("cape".equals(slot)) {
			onYou(player, want);
		}
		if (!sending.containsKey(slot)) {
			send(player, slot);
		}
	}

	private void send(String player, String slot) {
		SocialLink to = link;
		String want = wearing.get(slot);
		Cosmetics now = shown;
		if (to == null || want == null || now == null) {
			return;
		}
		if (want.equals(Objects.requireNonNullElse(now.worn(slot), ""))) {
			wearing.remove(slot);
			settleCape(player, slot, want, true);
			return;
		}
		sending.put(slot, want);
		client.run(() -> to.equip(player, slot, want.isEmpty() ? null : want), got -> {
			sending.remove(slot);
			took(got);
			if (want.equals(wearing.get(slot))) {
				wearing.remove(slot);
				settleCape(player, slot, want, true);
			} else {
				send(player, slot);
			}
		}, code -> {
			sending.remove(slot);
			if (!want.equals(wearing.get(slot))) {
				send(player, slot);
				return;
			}
			wearing.remove(slot);
			settleCape(player, slot, want, false);
			say((want.isEmpty() ? "Couldn't take that off: " : "Couldn't wear that: ") + reason(code), MenuStyle.WARN_TEXT);
		});
	}

	private static String reason(String code) {
		return switch (code) {
			case "unreachable" -> "Flint isn't running any more.";
			case "bad request" -> "this copy of the Flint app can't do that yet. Update Flint.";
			case "timeout" -> "the Flint Store took too long to answer.";
			case "account" -> "Flint is using another account than this game.";
			default -> code;
		};
	}

	private void onYou(String player, String want) {
		UUID id = uuid(player);
		if (id == null) {
			return;
		}
		CosmeticShelf.CapeArt art = want.isEmpty() ? null : capeArt(want);
		if (!want.isEmpty() && art == null) {
			if (capePick != null) {
				CapeChoices.settled(id, capeSerial, false);
				capePick = null;
			}
			return;
		}
		capeSerial = art == null ? CapeChoices.choose(id, null, null) : CapeChoices.choose(id, art.sha256(), art.image());
		capePick = want;
	}

	private void settleCape(String player, String slot, String want, boolean accepted) {
		UUID id = uuid(player);
		if (!"cape".equals(slot) || id == null || !want.equals(capePick)) {
			return;
		}
		capePick = null;
		CapeChoices.settled(id, capeSerial, accepted);
		Cosmetics now = shown;
		if (accepted || now == null) {
			return;
		}
		String worn = now.worn("cape");
		CosmeticShelf.CapeArt art = worn == null ? null : capeArt(worn);
		if (worn == null || art != null) {
			long serial = art == null ? CapeChoices.choose(id, null, null) : CapeChoices.choose(id, art.sha256(), art.image());
			CapeChoices.settled(id, serial, true);
		}
	}

	private CosmeticShelf.@Nullable CapeArt capeArt(String id) {
		Item item = WardrobeModel.owned(shown, id);
		return item != null && "cape".equals(item.kind()) ? shelf.capeArt(item) : null;
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

	private void openStore() {
		openStore(null);
	}

	/** The Store's page for {@code item} when its slug is known (so a shared link previews the item), else the catalog. */
	private void openStore(@Nullable Item item) {
		Cosmetics c = shown;
		String base = (c != null ? c.storeUrl() : StoreEndpoint.DEFAULT_BASE) + "/cosmetics/";
		openLink.accept(item != null && item.slug() != null ? base + item.slug() + "/" : base);
	}

	/** A short line under the tool row, with a small dot of its kind (green done, amber a problem, grey working). */
	private void say(String text, int dot) {
		notice.set(text);
		noticeDot = dot;
		invalidate();
	}

	// ---------- the emote wheel ----------

	private @Nullable List<String> wheelNow() {
		if (wheelWant != null) {
			return wheelWant;
		}
		Cosmetics c = shown;
		return c == null ? null : c.emotes();
	}

	private void setWheel(int place, String id) {
		List<String> now = wheelNow();
		Cosmetics c = shown;
		if (now == null || c == null) {
			say("This copy of the Flint app can't set your emote wheel yet. Update Flint.", MenuStyle.WARN_TEXT);
			return;
		}
		if (c.stale()) {
			say("You're offline: your emote wheel can't change right now.", MenuStyle.WARN_TEXT);
			return;
		}
		List<String> next = new ArrayList<>(now);
		next.set(place, id);
		wheelWant = List.copyOf(next);
		if (wheelSending == null) {
			sendWheel();
		}
	}

	private void sendWheel() {
		SocialLink to = link;
		String player = client.playing();
		List<String> want = wheelWant;
		if (to == null || player == null || want == null) {
			return;
		}
		wheelSending = want;
		client.run(() -> to.emotes(player, want), got -> {
			wheelSending = null;
			took(got);
			if (want.equals(wheelWant)) {
				wheelWant = null;
			} else {
				sendWheel();
			}
		}, code -> {
			wheelSending = null;
			if (!want.equals(wheelWant)) {
				sendWheel();
				return;
			}
			wheelWant = null;
			say("Couldn't change your emote wheel: " + reason(code), MenuStyle.WARN_TEXT);
		});
	}

	/** A click on the wheel's place {@code i}: puts the selected emote there, else picks the place (its emote selected). */
	private void wheelClick(int i) {
		List<String> now = wheelNow();
		Entry sel = selected;
		if (sel != null && sel.item != null && "emote".equals(sel.item.kind()) && sel.item.owned() && wheelPlace != i) {
			setWheel(i, sel.item.id());
			wheelPlace = i;
			return;
		}
		wheelPlace = wheelPlace == i ? -1 : i;
		Item item = WardrobeModel.owned(shown, now == null ? "" : now.get(i));
		if (item != null) {
			select(Entry.of(item));
		}
		invalidate();
	}

	// ---------- outfits ----------

	private void saveOutfit() {
		String player = client.playing();
		if (player == null) {
			return;
		}
		Map<String, String> worn = wornNow();
		if (worn.isEmpty()) {
			say("Wear something first, then save it as an outfit.", MenuStyle.WARN_TEXT);
			return;
		}
		Outfits outfits = Outfits.get();
		String name = outfits.nextName(player);
		if (!outfits.save(player, name, worn, System.currentTimeMillis())) {
			say("You have " + Outfits.MAX + " outfits, the most Flint keeps. Delete one first.", MenuStyle.WARN_TEXT);
			return;
		}
		say("Saved as " + name + ".", MenuStyle.SUCCESS_TEXT);
		rebuild(true);
		select(Entry.of(outfits.of(player).get(0)));
	}

	private void wearOutfit(Outfit outfit) {
		int skipped = 0;
		for (Map.Entry<String, String> e : outfit.slots().entrySet()) {
			String id = e.getValue();
			if (!id.isEmpty() && WardrobeModel.owned(shown, id) == null) {
				skipped++;
				continue;
			}
			wear(e.getKey(), id.isEmpty() ? null : id);
		}
		say("Wearing " + outfit.name() + (skipped == 0 ? "." : " (" + skipped + (skipped == 1 ? " item" : " items") + " you no longer own left out)."),
			MenuStyle.SUCCESS_TEXT);
	}

	private void deleteOutfit(Outfit outfit) {
		String player = client.playing();
		if (player != null) {
			Outfits.get().remove(player, outfit);
			selected = null;
			selectedKey = null;
			rebuild(true);
		}
	}

	// ---------- the skin changer ----------

	private void loadSkins() {
		SocialLink to = link;
		String player = client.playing();
		skinsAsked = true;
		if (to == null || player == null) {
			skinsProblem = "Start Minecraft from the Flint app to change your skin here.";
			return;
		}
		skinsLoading = true;
		skinWork(() -> to.skins(player), got -> {
			skinsLoading = false;
			skinsProblem = null;
			skins = got;
			rebuild(true);
		}, code -> {
			skinsLoading = false;
			skinsProblem = skinReason(code);
		});
	}

	private <T> void skinWork(FriendsClient.Work<T> work, Consumer<T> done, Consumer<String> failed) {
		try {
			SKIN_WORKER.execute(() -> {
				T result = null;
				String error = null;
				try {
					result = work.run();
				} catch (com.flintclient.client.module.account.LauncherLink.LinkException e) {
					error = e.unreachable() ? "unreachable" : e.getMessage() == null ? "invalid" : e.getMessage();
				} catch (RuntimeException e) {
					error = "invalid";
				}
				T got = result;
				String code = error;
				skinAnswers.add(() -> {
					if (code == null) {
						done.accept(got);
					} else {
						failed.accept(code);
					}
				});
			});
		} catch (RuntimeException e) {
			failed.accept("invalid");
		}
	}

	private static String skinReason(String code) {
		return switch (code) {
			case "unreachable" -> "Flint isn't running any more. Open the Flint app, then try again.";
			case "bad request" -> "This copy of the Flint app can't change skins from the game yet. Update Flint.";
			case "timeout" -> "Flint took too long to answer. Try again.";
			case "account" -> SocialParts.errorText("account");
			case "invalid" -> "That didn't work. Try again.";
			default -> code;
		};
	}

	private void addSkin() {
		SocialLink to = link;
		String player = client.playing();
		String text = skinField.text().strip();
		if (adding || to == null || player == null) {
			return;
		}
		if (text.isEmpty()) {
			say(byUrl ? "Paste the address of a skin picture (a 64×64 PNG)." : "Type a Minecraft username.", MenuStyle.WARN_TEXT);
			return;
		}
		if (!byUrl && !text.matches("[A-Za-z0-9_]{1,25}")) {
			say("A Minecraft username has only letters, digits and _.", MenuStyle.WARN_TEXT);
			return;
		}
		if (byUrl && !text.regionMatches(true, 0, "https://", 0, 8) && !text.regionMatches(true, 0, "http://", 0, 7)) {
			say("That isn't a web address. Paste the picture's full https:// address.", MenuStyle.WARN_TEXT);
			return;
		}
		adding = true;
		addSkin.setText("Adding…");
		say(byUrl ? "Checking the picture…" : "Looking up " + text + "…", MenuStyle.NEUTRAL);
		boolean url = byUrl;
		java.util.Set<String> before = new java.util.HashSet<>();
		for (Skin s : skins) {
			before.add(s.id());
		}
		skinWork(() -> to.addSkin(player, url ? null : text, url ? text : null), got -> {
			adding = false;
			addSkin.setText("Add");
			skins = got;
			skinField.setText("");
			Skin added = got.isEmpty() ? null : got.get(0);
			rebuild(true);
			if (added != null) {
				select(Entry.of(added));
				variant = null;
				say((before.contains(added.id()) ? added.name() + " is in your skins already." : "Added " + added.name() + ".")
					+ " Choose Classic or Slim, then Apply.", MenuStyle.SUCCESS_TEXT);
			}
		}, code -> {
			adding = false;
			addSkin.setText("Add");
			say(skinReason(code), MenuStyle.WARN_TEXT);
		});
	}

	private void applySkin(Skin skin) {
		SocialLink to = link;
		String player = client.playing();
		if (applying || to == null || player == null) {
			return;
		}
		String model = variant != null ? variant : skin.variant();
		applying = true;
		say("Applying " + skin.name() + "…", MenuStyle.NEUTRAL);
		skinWork(() -> {
			to.applySkin(player, skin.id(), model);
			return Boolean.TRUE;
		}, ok -> {
			applying = false;
			Texture texture = SKIN_TEXTURES.get(skin.id());
			boolean onNow = false;
			if (texture != null) {
				appliedSkin = texture;
				appliedSlim = "slim".equals(model);
				try {
					onNow = MenuHost.wearOwnSkin(texture, appliedSlim);
				} catch (RuntimeException | LinkageError e) {
					com.flintclient.client.FlintLog.LOGGER.warn("Flint wardrobe: couldn't show the new skin on you before the next join", e);
				}
			}
			String which = skin.name() + " (" + ("slim".equals(model) ? "Slim" : "Classic") + ")";
			say(onNow ? "Your skin is now " + which + ". Others see it when you next join a server."
				: "Your skin is now " + which + ". It shows in game when you next join a world or server.", MenuStyle.SUCCESS_TEXT);
		}, code -> {
			applying = false;
			say("Minecraft didn't take that skin: " + skinReason(code), MenuStyle.WARN_TEXT);
		});
	}

	private void removeSkin(Skin skin) {
		SocialLink to = link;
		String player = client.playing();
		if (to == null || player == null) {
			return;
		}
		skinWork(() -> to.removeSkin(player, skin.id()), got -> {
			skins = got;
			if (selected != null && selected.skin != null && selected.skin.id().equals(skin.id())) {
				selected = null;
				selectedKey = null;
			}
			rebuild(true);
			say("Removed " + skin.name() + ".", MenuStyle.NEUTRAL);
		}, code -> say(skinReason(code), MenuStyle.WARN_TEXT));
	}

	/** A skin's texture (64×64, a 64×32 one made so), asked for once; null until it is here. */
	private @Nullable Texture skinTexture(Skin skin) {
		Texture t = SKIN_TEXTURES.get(skin.id());
		if (t != null || skinArtAsked.contains(skin.id())) {
			return t;
		}
		SocialLink to = link;
		String player = client.playing();
		if (to == null || player == null) {
			return null;
		}
		skinArtAsked.add(skin.id());
		String id = skin.id();
		skinWork(() -> {
			byte[] png = to.skinArt(player, id);
			try {
				return CapePng.decode(png, 64, 64);
			} catch (CapePng.Refused e) {
				return null;
			}
		}, image -> {
			if (image == null || image.width() != 64 || image.height() != 64 && image.height() != 32) {
				return;
			}
			int[] px = WardrobeModel.modernSkin(image.argb(), image.width(), image.height());
			Texture texture = Texture.upload("wardrobe/skin/" + id.substring(0, 16), 64, 64, px, false);
			SKIN_TEXTURES.put(id, texture);
			while (SKIN_TEXTURES.size() > MAX_SKIN_TEXTURES) {
				Map.Entry<String, Texture> eldest = SKIN_TEXTURES.entrySet().iterator().next();
				if (eldest.getValue() == appliedSkin) {
					break;
				}
				eldest.getValue().release();
				SKIN_TEXTURES.remove(eldest.getKey());
			}
		}, code -> {
		});
		return null;
	}

	/** A cape's whole texture for the preview player, uploaded once per cape this session; null while its picture loads. */
	private @Nullable Texture capeTexture(Item cape) {
		CosmeticShelf.CapeArt art = shelf.capeArt(cape);
		if (art == null) {
			return null;
		}
		Texture t = CAPE_TEXTURES.get(art.sha256());
		if (t == null) {
			CapePng.Image image = art.image();
			t = Texture.upload("wardrobe/cape/" + art.sha256().substring(0, 16), image.width(), image.height(), image.argb(), false);
			CAPE_TEXTURES.put(art.sha256(), t);
			while (CAPE_TEXTURES.size() > MAX_CAPE_TEXTURES) {
				Map.Entry<String, Texture> eldest = CAPE_TEXTURES.entrySet().iterator().next();
				eldest.getValue().release();
				CAPE_TEXTURES.remove(eldest.getKey());
			}
		}
		return t;
	}

	// ---------- categories, search, sort, selection ----------

	void show(Category c) {
		if (c == category) {
			return;
		}
		if (selectedKey != null) {
			selectedIn.put(category, selectedKey);
		} else {
			selectedIn.remove(category);
		}
		category = c;
		lastCategory = c;
		selected = null;
		selectedKey = selectedIn.get(c);
		wheelPlace = -1;
		notice.set("");
		search.setText("");
		query = "";
		if (c == Category.SKINS && !skinsAsked) {
			loadSkins();
		}
		rebuild(true);
		view.scrollToTop();
		invalidate();
	}

	private void filterBy(String text) {
		String q = text.toLowerCase(Locale.ROOT).strip();
		if (!q.equals(query)) {
			query = q;
			rebuild(true);
			view.scrollToTop();
		}
	}

	private void select(Entry e) {
		select(e, true);
	}

	/** {@code click}: a click or Enter (a second one soon after on the same card wears it); else focus arriving by the arrows. */
	private void select(Entry e, boolean click) {
		boolean again = click && e.key.equals(selectedKey) && Clock.millis() - selectedAt < DOUBLE_MILLIS;
		selected = e;
		selectedKey = e.key;
		selectedAt = click ? Clock.millis() : 0;
		if (e.item != null && e.item.slot() != null) {
			tryOn.put(e.item.slot(), e.item);
		}
		if (e.skin == null) {
			variant = null;
		}
		if (again) {
			primary();
		}
		invalidate();
	}

	/** The loadout's main button: wear, take off, put on the wheel, wear an outfit, apply a skin, or get it in the Store. */
	private void primary() {
		Entry e = selected;
		if (e == null) {
			return;
		}
		if (e.item != null) {
			Item item = e.item;
			if (!item.owned()) {
				WardrobeModel.Unlock unlock = WardrobeModel.unlock(item);
				if (unlock == null) {
					openStore(item);
				} else if (unlock.button() != null) {
					openLink.accept(com.flintclient.client.module.social.MenuLink.DEFAULT_INVITE);
				}
			} else if ("emote".equals(item.kind())) {
				List<String> now = wheelNow();
				int at = now == null ? -1 : now.indexOf(item.id());
				if (at >= 0) {
					setWheel(at, "");
				} else {
					int place = wheelPlace >= 0 ? wheelPlace : now == null ? 0 : Math.max(0, now.indexOf(""));
					setWheel(place, item.id());
				}
			} else if (item.slot() != null) {
				wear(item.slot(), worn(item) ? null : item.id());
			}
		} else if (e.outfit != null) {
			wearOutfit(e.outfit);
		} else if (e.skin != null) {
			applySkin(e.skin);
		} else if (e.badge != null) {
			wearBadge(e.badge);
		}
	}

	/** The cards: made again when the list, the category, the search, the sort, the filter or the skins change. */
	private void rebuild(boolean force) {
		Cosmetics c = shown;
		String player = client.playing();
		StringBuilder key = new StringBuilder().append(category).append('|').append(query).append('|').append(sort).append('|').append(filter);
		if (c != null) {
			key.append(c.items().size()).append(c.shop().size()).append(c.stale()).append(c.version());
		}
		key.append('|').append(skins.size()).append(skins.isEmpty() ? "" : skins.get(0).id());
		key.append('|').append(FlintPlayers.owned());
		if (player != null) {
			key.append('|').append(Outfits.get().of(player).size());
		}
		String k = key.toString();
		if (!force && k.equals(builtKey)) {
			return;
		}
		builtKey = k;
		grid.clearChildren();
		List<Entry> entries = new ArrayList<>();
		if (category.main()) {
			for (Item item : WardrobeModel.items(c, category, query, sort, filter, this::worn)) {
				entries.add(Entry.of(item));
			}
		} else if (category == Category.OUTFITS && player != null) {
			for (Outfit o : Outfits.get().of(player)) {
				if (query.isEmpty() || o.name().toLowerCase(Locale.ROOT).contains(query)) {
					entries.add(Entry.of(o));
				}
			}
		} else if (category == Category.SKINS) {
			for (Skin s : skins) {
				entries.add(Entry.of(s));
			}
		} else if (category == Category.BADGES) {
			// Only the marks that are yours: the one Flint gives you (if any) and the Flint mark.
			for (FlintPlayers.Tag t : FlintPlayers.owned()) {
				if (query.isEmpty() || badgeName(t).toLowerCase(Locale.ROOT).contains(query)) {
					entries.add(Entry.of(t));
				}
			}
		}
		for (Entry e : entries) {
			grid.add(new Card(e));
		}
		if (selectedKey != null) {
			for (Entry e : entries) {
				if (e.key.equals(selectedKey)) {
					selected = e;
				}
			}
		}
		total.set(headerCount(c, player));
		column.invalidate();
		view.invalidate();
		invalidate();
	}

	/**
	 * The header's count for the open category: every card it lists and how many are the player's ("6 capes · 4 owned");
	 * just the number for the pages that are all theirs ("2 skins", "1 badge"), nothing for Bodywear.
	 */
	private String headerCount(@Nullable Cosmetics c, @Nullable String player) {
		int all;
		int owned;
		switch (category) {
			case OUTFITS -> all = owned = player == null ? 0 : Outfits.get().of(player).size();
			case SKINS -> all = owned = skins.size();
			case BADGES -> all = owned = FlintPlayers.owned().size();
			case BODYWEAR -> {
				return "";
			}
			default -> {
				owned = WardrobeModel.count(c, category);
				all = owned;
				if (c != null) {
					for (Item item : c.shop()) {
						all += CosmeticKinds.categoryOf(item.kind()) == category && !WardrobeModel.owns(c, item.id()) ? 1 : 0;
					}
				}
			}
		}
		String noun = (all == 1 ? category.one : category == Category.SKINS ? "Skins" : category.title).toLowerCase(Locale.ROOT);
		if (!category.main()) {
			return all + " " + noun;
		}
		return all + " " + noun + " · " + owned + " owned";
	}

	static String badgeName(FlintPlayers.Tag t) {
		return switch (t) {
			case DEFAULT -> "Flint";
			case CONTENT_CREATOR -> "Content Creator";
			case STAFF -> "Staff";
			case DEVELOPER -> "Developer";
			case CO_OWNER -> "Co-Owner";
			case OWNER -> "Owner";
			case TRIAL_CREATOR -> "Trial Creator";
			case VIP -> "VIP";
			case SPECIAL -> "Special";
		};
	}

	/** One card's subject: an item (owned or the Store's), an outfit, a skin or a badge. */
	static final class Entry {
		final String key;
		final String name;
		final @Nullable Item item;
		final @Nullable Outfit outfit;
		final @Nullable Skin skin;
		final FlintPlayers.@Nullable Tag badge;

		private Entry(String key, String name, @Nullable Item item, @Nullable Outfit outfit, @Nullable Skin skin, FlintPlayers.@Nullable Tag badge) {
			this.key = key;
			this.name = name;
			this.item = item;
			this.outfit = outfit;
			this.skin = skin;
			this.badge = badge;
		}

		static Entry of(Item i) {
			return new Entry("item:" + i.id(), i.name(), i, null, null, null);
		}

		static Entry of(Outfit o) {
			return new Entry("outfit:" + o.name() + ":" + o.savedAt(), o.name(), null, o, null, null);
		}

		static Entry of(Skin s) {
			return new Entry("skin:" + s.id(), s.name(), null, null, s, null);
		}

		static Entry of(FlintPlayers.Tag t) {
			return new Entry("badge:" + t.name(), badgeName(t), null, null, null, t);
		}
	}

	// ---------- layout ----------

	private float pad() {
		return columns(w).pad();
	}

	/** The three columns' widths for a page {@code width} wide (layout units), and whether the sidebar is icons only. */
	record Columns(float pad, float side, float centre, float load, boolean icons) {
	}

	/**
	 * The columns for a page {@code width} wide: the sidebar's names give way to icons, then the loadout narrows, so the cards
	 * keep at least 300 design pixels; padding shrinks on a small window. Pure (at the menu's current scale).
	 */
	static Columns columns(float width) {
		float pad = Math.min(d(PAD), Math.max(d(14), width * 0.025F));
		float inner = width - 2 * pad;
		float gap = d(GAP);
		float side = d(SIDE_W);
		float load = Math.max(d(240), Math.min(d(LOAD_W), inner * 0.28F));
		float centre = inner - side - load - 2 * gap;
		boolean icons = false;
		if (centre < d(380)) {
			icons = true;
			side = d(SIDE_ICONS);
			centre = inner - side - load - 2 * gap;
		}
		if (centre < d(300)) {
			load = Math.max(d(180), inner - side - 2 * gap - d(300));
			centre = inner - side - load - 2 * gap;
		}
		return new Columns(pad, side, centre, load, icons);
	}

	@Override
	protected void layout() {
		Columns cols = columns(w);
		float pad = cols.pad();
		bodyY = y + pad + d(HEAD);
		bodyH = Math.max(0, y + h - pad - bodyY);
		float gap = d(GAP);
		sideW = cols.side();
		loadW = cols.load();
		centreW = cols.centre();
		icons = cols.icons();
		sideX = x + pad;
		centreX = sideX + sideW + gap;
		loadX = centreX + centreW + gap;

		// The sidebar: the categories (scrolling when they don't fit), then the Flint Store.
		float bh = d(Button.H);
		float shopY = bodyY + bodyH - bh;
		rowView.setBounds(sideX, bodyY, sideW + d(6), Math.max(0, shopY - d(12) - bodyY));
		shop.setVisible(!icons);
		shopSmall.setVisible(icons);
		shop.setBounds(sideX, shopY, sideW, bh);
		shopSmall.setBounds(sideX + (sideW - bh) / 2, shopY, bh, bh);

		// The centre: one quiet tool row for the category, a note under it when there is one, then the cards.
		float ty = bodyY;
		float tr = centreX + centreW;
		boolean storeKinds = category.main();
		boolean skinsPage = category == Category.SKINS;
		search.setVisible(!skinsPage && category != Category.BODYWEAR);
		sortButton.setVisible(storeKinds);
		filterButton.setVisible(storeKinds);
		saveOutfit.setVisible(category == Category.OUTFITS);
		sourceChoice.setVisible(skinsPage);
		skinField.setVisible(skinsPage);
		addSkin.setVisible(skinsPage);
		wheel.setVisible(category == Category.EMOTES);
		float right = tr;
		if (storeKinds) {
			filterButton.setBounds(right - bh, ty, bh, bh);
			sortButton.setBounds(right - 2 * bh - d(8), ty, bh, bh);
			right -= 2 * bh + d(16);
		}
		if (saveOutfit.visible()) {
			float ow = Math.min(saveOutfit.prefWidth(), centreW * 0.5F);
			saveOutfit.setBounds(right - ow, ty, ow, bh);
			right -= ow + d(8);
		}
		search.setBounds(centreX, ty, Math.max(d(80), right - centreX), bh);
		if (skinsPage) {
			float cw = Math.min(sourceChoice.prefWidth(), centreW * 0.42F);
			sourceChoice.setBounds(centreX, ty, cw, bh);
			float aw = Math.max(d(84), Math.min(addSkin.prefWidth(), centreW * 0.25F));
			addSkin.setBounds(tr - aw, ty, aw, bh);
			skinField.setBounds(centreX + cw + d(8), ty, Math.max(d(60), tr - aw - d(8) - (centreX + cw + d(8))), bh);
		}
		float listY = ty + bh + d(16) + noticeHeight();
		view.setBounds(centreX - d(4), listY, centreW + d(4) + d(8), Math.max(0, bodyY + bodyH - listY));
		column.invalidate();

		loadout.setBounds(loadX, bodyY, loadW, bodyH);

		float rw = Math.max(d(150), retry.prefWidth());
		float ey = listY + Math.min(d(120), Math.max(0, bodyY + bodyH - listY) / 3);
		retry.setBounds(centreX + (centreW - rw) / 2, ey + d(28), rw, bh);
		float ew = Math.min(centreW - d(16), Math.max(d(180), emptyAction.prefWidth()));
		emptyAction.setBounds(centreX + (centreW - ew) / 2, ey + d(28), ew, bh);
	}

	private float noticeHeight() {
		return notice.text().isEmpty() ? 0 : notice.height(measure(), centreW - d(16)) + d(12);
	}

	// ---------- drawing ----------

	@Override
	protected void paint(Canvas c) {
		shelf.pump();
		who = client.playing();
		epochMillis = System.currentTimeMillis();
		tilesDrawn = 0;
		tilesReady = 0;
		if (!asked) {
			load();
		}
		Cosmetics now = shown;
		float pad = pad();
		float cy = y + pad + d(HEAD) / 2 - d(8);
		c.pushZoom(1.5F, c.px(x + pad), c.px(cy));
		try {
			MenuStyle.text(c, title, x + pad, cy, MenuStyle.WORDMARK);
		} finally {
			c.popOffset();
		}
		float tx = x + pad + 1.5F * title.width(c) + d(14);
		if (now != null) {
			MenuStyle.text(c, total, tx, cy, Palette.withAlpha(MenuStyle.NEUTRAL, 0.8F));
			if (now.stale()) {
				MenuStyle.text(c, stale, tx + total.width(c) + d(14), cy, MenuStyle.WARN_TEXT);
			}
		}
		// A hairline between the sidebar and the cards.
		int line = Math.max(1, c.px(d(1)));
		int lx = c.px(sideX + sideW + d(GAP) / 2);
		c.fill(lx, c.px(bodyY), lx + line, c.px(bodyY + bodyH), MenuStyle.SECTION_EDGE);
		if (!notice.text().isEmpty()) {
			float ny = bodyY + d(Button.H) + d(10);
			SocialParts.dot(c, centreX + d(4), ny + Lines.pitch(c) / 2, d(6), noticeDot);
			notice.paint(c, centreX + d(16), ny, centreW - d(16), MenuStyle.SENTENCE_TEXT);
		}
		retry.setVisible(false);
		emptyAction.setVisible(false);
		if (category == Category.BODYWEAR) {
			view.setVisible(false);
			emptyLine.set("Coming soon");
			MenuStyle.centered(c, emptyLine, centreX + centreW / 2, bodyY + bodyH / 2, MenuStyle.NEUTRAL, centreW - d(24));
			return;
		}
		boolean storeLike = category.main() || category == Category.OUTFITS;
		if (storeLike && now == null) {
			view.setVisible(false);
			boolean failed = problem != null && !loading;
			retry.setVisible(failed && link != null);
			emptyText(c, failed ? Objects.requireNonNull(problem) : "Loading your cosmetics…");
			return;
		}
		if (category == Category.SKINS && skins.isEmpty()) {
			view.setVisible(false);
			emptyText(c, skinsLoading ? "Loading your skins…" : skinsProblem != null ? skinsProblem
				: byUrl ? "Paste a skin picture's address above, then Add." : "Type a Minecraft username above, then Add.");
			return;
		}
		boolean empty = grid.childCount() == 0;
		view.setVisible(!empty || category == Category.EMOTES);
		if (empty) {
			String many = category.title.toLowerCase(Locale.ROOT);
			if (!query.isEmpty()) {
				emptyText(c, "Nothing here is called “" + query + "”.");
			} else if (category == Category.OUTFITS) {
				emptyText(c, "No outfits yet. Save what you're wearing to switch back in one click.");
			} else if (category == Category.EMOTES) {
				// Under the wheel.
				emptyLine.set(filter == Filter.LOCKED ? "You own all the emotes the Flint Store has." : "No emotes yet.");
				MenuStyle.centered(c, emptyLine, centreX + centreW / 2, grid.y() + d(24), MenuStyle.NEUTRAL, centreW - d(24));
			} else if (filter == Filter.LOCKED) {
				emptyText(c, "You own all the " + many + " the Flint Store has.");
			} else if (filter == Filter.ANIMATED) {
				emptyText(c, "No animated " + many + " here.");
			} else {
				emptyText(c, filter == Filter.OWNED ? "You don't own any " + many + " yet." : "No " + many + " yet.");
				emptyAction.setVisible(true);
			}
		}
	}

	/** An empty list's one quiet line. */
	private void emptyText(Canvas c, String text) {
		emptyLine.set(text);
		float ey = view.y() + Math.min(d(120), view.height() / 3);
		MenuStyle.centered(c, emptyLine, centreX + centreW / 2, ey, MenuStyle.NEUTRAL, centreW - d(24));
	}

	@Override
	protected void paintOver(Canvas c) {
		if (!readyLogged && tilesDrawn > 0 && tilesReady == tilesDrawn) {
			readyLogged = true;
			com.flintclient.client.FlintLog.LOGGER.info("Flint wardrobe: {} cards on screen all showing {} ms after opening ({})", tilesDrawn,
				(System.nanoTime() - madeAt) / 1_000_000, shelf.describe());
		}
	}

	// ---------- the sidebar ----------

	/** The thin rule between the Store's kinds and the game's own pages. */
	private static final class Rule extends Node {
		@Override
		public float prefHeight(float width) {
			return d(17);
		}

		@Override
		protected void paint(Canvas c) {
			int line = Math.max(1, c.px(d(1)));
			c.fill(c.px(x + d(12)), c.px(y + h / 2), c.px(x + w - d(12)), c.px(y + h / 2) + line, MenuStyle.SECTION_EDGE);
		}
	}

	/** A category: its icon and name, a small count, and a green dot while one of it is worn; the open one marked. */
	private final class CategoryRow extends Pressable {
		final Category category;
		private final Label name;
		private final Label count = new Label(Type.COUNT, "");
		private float on = -1;

		CategoryRow(Category category) {
			super(null);
			this.category = category;
			this.name = new Label(Type.ROW_LABEL, category.title);
			setAction(() -> show(category));
			name(category.title);
			tooltip(category.title);
		}

		@Override
		public float prefHeight(float width) {
			return d(ROW_H);
		}

		private int n() {
			return switch (category) {
				case OUTFITS -> {
					String p = client.playing();
					yield p == null ? 0 : Outfits.get().of(p).size();
				}
				case SKINS -> skins.size();
				case BADGES -> FlintPlayers.owned().size();
				case BODYWEAR -> -1;
				default -> WardrobeModel.count(shown, category);
			};
		}

		private boolean dot() {
			if (category == Category.EMOTES) {
				List<String> wheelNow = wheelNow();
				return wheelNow != null && wheelNow.stream().anyMatch(id -> !id.isEmpty());
			}
			return category.main() && WardrobeModel.wearsAny(shown, category, wearing);
		}

		@Override
		public String narration() {
			return accessibleName() + (n() < 0 ? ", coming soon" : ", " + n()) + (dot() ? ", one worn" : "") + (WardrobePage.this.category == category ? ", open" : "");
		}

		@Override
		protected void paint(Canvas c) {
			boolean open = WardrobePage.this.category == category;
			on = MenuStyle.ease(on, open);
			float rad = MenuStyle.radius();
			float fill = Math.max(0.6F * hover, on);
			if (fill > 0.01F) {
				MenuStyle.box(c, x, y, w, h, rad, Palette.withAlpha(MenuStyle.TEXT, 0.05F * fill));
			}
			if (on > 0.01F) {
				MenuStyle.box(c, x, y + d(12), d(2), h - d(24), d(1), Palette.withAlpha(MenuStyle.ACCENT, on));
			}
			int ink = Palette.lerp(Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.SENTENCE_TEXT, hover), MenuStyle.TEXT, on);
			boolean dot = dot();
			if (icons) {
				glyph(c, icon(category), x + w / 2, y + h / 2, d(16), ink);
				if (dot) {
					SocialParts.dot(c, x + w - d(10), y + d(10), d(5), MenuStyle.SUCCESS_TEXT);
				}
			} else {
				glyph(c, icon(category), x + d(20), y + h / 2, d(15), ink);
				int n = n();
				count.set(n < 0 ? "" : String.valueOf(n));
				float cw = count.width(c);
				float room = w - d(40) - d(12);
				// The name always shows whole: where it and the count don't both fit (a small window), the count gives way.
				boolean withCount = name.width(c) <= room - cw - d(18);
				if (withCount) {
					MenuStyle.text(c, count, x + w - d(12) - cw, y + h / 2, Palette.withAlpha(MenuStyle.NEUTRAL, 0.7F));
				}
				if (dot) {
					SocialParts.dot(c, withCount ? x + w - d(12) - cw - d(10) : x + w - d(12), y + h / 2, d(5), MenuStyle.SUCCESS_TEXT);
				}
				MenuStyle.fitted(c, name, x + d(40), y + h / 2, ink, withCount ? room - cw - d(18) : room - d(12));
			}
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	// ---------- the cards ----------

	/** A card's width in window pixels to aim for at General › Menu size: smaller with the menu, so a row keeps its count. */
	static int cardPx() {
		return Math.round(CARD_PX * MenuStyle.menuZoom());
	}

	/** The cards: about {@link #CARD_PX} window pixels wide each (compact: five or six to a row at 1080p), the row shared out. */
	private final class Grid extends Node {
		private int columns(float width) {
			Canvas m = measure();
			int px = m.px(width);
			int gap = m.px(d(CARD_GAP));
			return Math.max(2, Math.min(8, (px + gap) / (cardPx() + gap)));
		}

		private float cardW(float width, int columns) {
			return Math.max(d(60), (width - (columns - 1) * d(CARD_GAP)) / columns);
		}

		private float cardH(float cw) {
			return d(CARD_PAD) + artH(cw) + d(10) + 3 * Lines.pitch(measure()) + d(CARD_PAD);
		}

		@Override
		public float prefHeight(float width) {
			int n = childCount();
			if (n == 0) {
				return category == Category.EMOTES ? d(48) : 0;
			}
			float inner = width - 2 * d(4);
			int per = columns(inner);
			int rowsN = (n + per - 1) / per;
			return rowsN * cardH(cardW(inner, per)) + Math.max(0, rowsN - 1) * d(CARD_GAP) + 2 * d(4);
		}

		@Override
		protected void layout() {
			float inner = w - 2 * d(4);
			int per = columns(inner);
			float cw = cardW(inner, per);
			float ch = cardH(cw);
			for (int i = 0; i < childCount(); i++) {
				child(i).setBounds(x + d(4) + (i % per) * (cw + d(CARD_GAP)), y + d(4) + (i / per) * (ch + d(CARD_GAP)), cw, ch);
			}
		}
	}

	private float artH(float cardW) {
		float art = cardW - 2 * d(CARD_PAD);
		return Math.round(category == Category.SKINS ? art * 1.2F : category == Category.CAPES ? art * 1.0F : art * 0.86F);
	}

	/** One card: its picture, its name (up to two lines), and one quiet line: equipped, owned, or its price. */
	private final class Card extends Pressable {
		final Entry entry;
		private final String shelfKey;
		private final Lines name;
		private final Label nameRest = new Label(Type.ROW_NOTE, "");
		private @Nullable TextLine restAfter;
		private final Label state = new Label(Type.CAPTION, "");
		private float pick = -1;

		Card(Entry entry) {
			super(null);
			this.entry = entry;
			this.shelfKey = entry.item == null ? "" : CosmeticShelf.key(entry.item);
			this.name = new Lines(Type.ROW_NOTE, entry.name);
			setAction(() -> select(entry));
			name(entry.name);
		}

		private boolean worn() {
			if (entry.item != null) {
				return WardrobePage.this.worn(entry.item);
			}
			if (entry.badge != null) {
				return entry.badge == FlintPlayers.worn();
			}
			return entry.skin != null && appliedSkin != null && appliedSkin == SKIN_TEXTURES.get(entry.skin.id());
		}

		private boolean locked() {
			return entry.item != null && !entry.item.owned();
		}

		@Override
		public String narration() {
			return withReason(accessibleName() + (worn() ? ", equipped" : locked() ? ", locked" : "") + (entry.key.equals(selectedKey) ? ", selected" : ""));
		}

		private boolean onScreen() {
			return y + h > view.y() && y < view.bottom();
		}

		@Override
		protected void focusChanged(boolean focused) {
			if (focused && keyboardFocused() && !entry.key.equals(selectedKey)) {
				select(entry, false);
			}
		}

		@Override
		public boolean keyDown(com.flintclient.client.platform.KeyInput event) {
			if (event.isSelection() && entry.key.equals(selectedKey)) {
				press = 1.0F;
				primary();
				return true;
			}
			return super.keyDown(event);
		}

		@Override
		protected void paint(Canvas c) {
			if (!onScreen()) {
				return;
			}
			boolean worn = worn();
			boolean locked = locked();
			pick = MenuStyle.ease(pick, entry.key.equals(selectedKey));
			float rad = MenuStyle.radius();
			MenuStyle.framed(c, x, y, w, h, rad, Palette.lerp(MenuStyle.CARD, MenuStyle.CARD_HOVER, hover),
				Palette.lerp(MenuStyle.CARD_EDGE, MenuStyle.CARD_EDGE_HOVER, hover));
			MenuStyle.pressed(c, x, y, w, h, rad, press);
			MenuStyle.chosen(c, x, y, w, h, rad, pick);
			float pad = d(CARD_PAD);
			float ax = x + pad;
			float ay = y + pad;
			float artW = w - 2 * pad;
			float artH = artH(w);
			MenuStyle.box(c, ax, ay, artW, artH, rad, MenuStyle.PREVIEW_FRAME);
			float before = c.opacity();
			if (locked) {
				c.setOpacity(before * 0.45F);
			}
			try {
				picture(c, ax, ay, artW, artH);
			} finally {
				c.setOpacity(before);
			}
			// A small mark in the picture's corner: locked, or worn.
			if (locked || worn) {
				float s = d(20);
				float mx = ax + artW - s - d(6);
				float my = ay + d(6);
				MenuStyle.box(c, mx, my, s, s, d(4), Palette.withAlpha(MenuStyle.CARD, 0.88F));
				glyph(c, locked ? LOCK : CHECK, mx + s / 2, my + s / 2, d(10), Palette.withAlpha(MenuStyle.TEXT, 0.9F));
			}
			float pitch = Lines.pitch(c);
			float ny = ay + artH + d(10);
			int ink = Palette.lerp(MenuStyle.SENTENCE_TEXT, MenuStyle.TEXT, Math.max(hover, pick));
			List<TextLine> lines = name.lines(c, artW);
			if (lines.size() <= 2) {
				name.paint(c, ax, ny, artW, ink);
			} else {
				TextLine first = lines.get(0);
				c.text(first, c.px(ax), c.baselineFor(first, c.px(ny + pitch / 2)), ink);
				if (first != restAfter) {
					restAfter = first;
					nameRest.set(entry.name.substring(Math.min(entry.name.length(), first.text().length())).strip());
				}
				MenuStyle.fitted(c, nameRest, ax, ny + pitch * 1.5F, ink, artW);
			}
			float sy = ny + pitch * (Math.min(2, lines.size()) + 0.5F);
			String line;
			float lx = ax;
			if (worn) {
				line = entry.skin != null ? "Applied"
					: entry.item != null && "emote".equals(entry.item.kind()) ? "On the wheel" : "Equipped";
				SocialParts.dot(c, ax + d(3), sy, d(5), MenuStyle.SUCCESS_TEXT);
				lx += d(12);
			} else if (locked) {
				WardrobeModel.Unlock unlock = entry.item == null ? null : WardrobeModel.unlock(entry.item);
				line = unlock != null ? unlock.line() : entry.item != null && entry.item.price() >= 0 ? entry.item.price() + " Merit" : "In the Store";
			} else if (entry.outfit != null) {
				long n = entry.outfit.slots().values().stream().filter(v -> !v.isEmpty()).count();
				line = n == 1 ? "1 item" : n + " items";
			} else if (entry.skin != null) {
				line = "slim".equals(entry.skin.variant()) ? "Slim" : "Classic";
			} else {
				line = "Owned";
			}
			state.set(line);
			MenuStyle.fitted(c, state, lx, sy, Palette.withAlpha(MenuStyle.NEUTRAL, 0.9F), artW - (lx - ax));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}

		private void picture(Canvas c, float ax, float ay, float artW, float artH) {
			if (entry.item != null) {
				itemPicture(c, entry.item, shelfKey, ax, ay, artW, artH, true);
			} else if (entry.skin != null) {
				Texture t = skinTexture(entry.skin);
				tilesDrawn++;
				if (t != null) {
					tilesReady++;
					skinFront(c, t, "slim".equals(entry.skin.variant()), ax, ay, artW, artH);
				}
			} else if (entry.badge != null) {
				badgePicture(c, entry.badge, ax, ay, artW, artH);
			} else if (entry.outfit != null) {
				outfitPicture(c, entry.outfit, ax, ay, artW, artH);
			}
		}
	}

	/**
	 * An item's picture in a box: an animated cape playing, else the 3D renderer's mini-render, else its thumbnail (the Store's
	 * others too), else its category's mark, quiet. {@code count}: a card (counted for the "all showing" log line).
	 */
	private void itemPicture(Canvas c, Item item, String shelfKey, float ax, float ay, float artW, float artH, boolean count) {
		boolean cape = "cape".equals(item.kind());
		CosmeticShelf.Reel reel = cape && item.owned() ? shelf.reel(shelfKey, item, true) : null;
		boolean ready = false;
		if (reel != null && reel.up()) {
			int f = Kit.reduced() ? 0 : reel.animation.frameAt(epochMillis);
			drawPicture(c, reel.texture, reel.u(f), reel.v(f), reel.frameW, reel.frameH, reel.width, reel.height, ax, ay, artW, artH);
			ready = true;
		} else {
			CosmeticPreview.Renderer r = cape ? null : CosmeticPreview.renderer();
			boolean live = false;
			if (r != null) {
				try {
					float yaw = Kit.reduced() ? 30 : (Clock.millis() % 9000) / 9000.0F * 360.0F;
					live = r.thumbnail(c.frame(), item, c.px(ax), c.px(ay), c.px(ax + artW) - c.px(ax), c.px(ay + artH) - c.px(ay), yaw);
				} catch (RuntimeException | LinkageError e) {
					CosmeticPreview.register(null);
					com.flintclient.client.FlintLog.LOGGER.warn("Flint wardrobe: the 3D cosmetics preview failed; thumbnails show instead", e);
				}
			}
			if (live) {
				ready = true;
			} else {
				CosmeticShelf.Poster picture = item.art() >= 0 ? shelf.poster(shelfKey, who, item, true) : null;
				if (picture != null) {
					drawPicture(c, picture.texture, 0, 0, picture.width, picture.height, picture.width, picture.height, ax, ay, artW, artH);
					ready = true;
				} else {
					glyph(c, icon(CosmeticKinds.categoryOf(item.kind())), ax + artW / 2, ay + artH / 2, Math.min(artW, artH) * 0.22F,
						Palette.withAlpha(MenuStyle.TEXT, 0.14F));
					ready = item.art() < 0;
				}
			}
		}
		if (count) {
			tilesDrawn++;
			tilesReady += ready ? 1 : 0;
		}
	}

	/** A picture as large as fits its box (with a little margin), in proportion, pixels kept whole where they can be. */
	static void drawPicture(Canvas c, @Nullable Texture texture, int u, int v, int width, int height, int texW, int texH, float ax, float ay,
		float artW, float artH) {
		if (texture == null) {
			return;
		}
		float scale = Math.min(artW * c.scale() / width, artH * c.scale() / height) * 0.86F;
		if (scale >= 1) {
			scale = (float) Math.floor(scale);
		}
		int pw = Math.max(1, Math.round(width * scale));
		int ph = Math.max(1, Math.round(height * scale));
		int px = c.px(ax + artW / 2) - pw / 2;
		int py = c.px(ay + artH / 2) - ph / 2;
		c.textureRegion(texture, px, py, pw, ph, u, v, width, height, texW, texH, 0xFFFFFFFF);
	}

	/** A skin seen from the front, flat (head, body, arms, legs and their outer layers): 16 × 32 of its pixels. */
	static void skinFront(Canvas c, Texture t, boolean slim, float ax, float ay, float artW, float artH) {
		float unit = Math.min(artW * c.scale() / 16.0F, artH * c.scale() / 32.0F) * 0.82F;
		if (unit >= 1) {
			unit = (float) Math.floor(unit);
		}
		int u = Math.max(1, Math.round(unit));
		int ox = c.px(ax + artW / 2) - 8 * u;
		int oy = c.px(ay + artH / 2) - 16 * u;
		int arm = slim ? 3 : 4;
		// {u, v, w, h, x, y} in skin pixels: each front face, then its outer layer.
		int[][] parts = {
			{8, 8, 8, 8, 4, 0}, {20, 20, 8, 12, 4, 8}, {44, 20, arm, 12, 4 - arm, 8}, {36, 52, arm, 12, 12, 8}, {4, 20, 4, 12, 4, 20}, {20, 52, 4, 12, 8, 20},
			{40, 8, 8, 8, 4, 0}, {20, 36, 8, 12, 4, 8}, {44, 36, arm, 12, 4 - arm, 8}, {52, 52, arm, 12, 12, 8}, {4, 36, 4, 12, 4, 20}, {4, 52, 4, 12, 8, 20}};
		for (int[] p : parts) {
			c.textureRegion(t, ox + p[4] * u, oy + p[5] * u, p[2] * u, p[3] * u, p[0], p[1], p[2], p[3], 64, 64, 0xFFFFFFFF);
		}
	}

	private void badgePicture(Canvas c, FlintPlayers.Tag t, float ax, float ay, float artW, float artH) {
		Texture tex = BADGE_TEXTURES.computeIfAbsent(t, k -> Texture.bundled("textures/font/tag_" + k.name().toLowerCase(Locale.ROOT) + ".png"));
		if (tex != null) {
			// The marks are 10 × 16 pictures, drawn whole and sharp.
			drawPicture(c, tex, 0, 0, 10, 16, 10, 16, ax + artW * 0.25F, ay + artH * 0.2F, artW * 0.5F, artH * 0.6F);
		}
	}

	private void outfitPicture(Canvas c, Outfit o, float ax, float ay, float artW, float artH) {
		// Its cape's picture when it has one, with the marks of what else it wears in a row under it.
		Item cape = WardrobeModel.owned(shown, o.slots().getOrDefault("cape", ""));
		float rowH = d(24);
		if (cape != null) {
			itemPicture(c, cape, CosmeticShelf.key(cape), ax, ay + d(4), artW, artH - rowH - d(8), false);
		} else {
			glyph(c, icon(Category.OUTFITS), ax + artW / 2, ay + (artH - rowH) / 2, d(24), Palette.withAlpha(MenuStyle.TEXT, 0.2F));
		}
		List<Category> worn = new ArrayList<>();
		for (Map.Entry<String, String> e : o.slots().entrySet()) {
			CosmeticKinds.Kind k = CosmeticKinds.ofSlot(e.getKey());
			if (!e.getValue().isEmpty() && k != null && k.category() != Category.CAPES) {
				worn.add(k.category());
			}
		}
		float gx = ax + artW / 2 - (worn.size() * d(18)) / 2 + d(9);
		for (Category cat : worn) {
			glyph(c, icon(cat), gx, ay + artH - rowH / 2 - d(2), d(12), Palette.withAlpha(MenuStyle.TEXT, 0.5F));
			gx += d(18);
		}
	}

	// ---------- the emote wheel ----------

	/** The emote wheel: 8 places round a centre, each an emote of yours or empty; a click picks a place or fills it. */
	private final class Wheel extends Pressable {
		private final Lines hint = new Lines(Type.CAPTION, "");
		private final Label number = new Label(Type.CAPTION, "");
		/** The place a click is on. */
		private int pending = -1;

		Wheel() {
			super(null);
			setAction(() -> {
				if (pending >= 0) {
					wheelClick(pending);
				}
			});
			name("Emote wheel");
		}

		@Override
		public float prefHeight(float width) {
			return visible() ? Math.min(d(320), Math.max(d(220), width * 0.42F)) : 0;
		}

		@Override
		public boolean focusable() {
			return false;
		}

		private float radius() {
			return Math.min(w, h) / 2 - place() / 2 - d(4);
		}

		private float place() {
			return Math.min(d(60), Math.min(w, h) * 0.2F);
		}

		private float[] centre(int i) {
			double a = Math.toRadians(i * 45 - 90);
			float r = radius();
			return new float[] {x + w / 2 + (float) Math.cos(a) * r, y + h / 2 + (float) Math.sin(a) * r};
		}

		private int placeAt(float mx, float my) {
			for (int i = 0; i < CosmeticKinds.EMOTE_SLOTS; i++) {
				float[] p = centre(i);
				if (Math.hypot(mx - p[0], my - p[1]) <= place() / 2 + d(2)) {
					return i;
				}
			}
			return -1;
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			return button == Kit.LEFT_BUTTON && placeAt(mx, my) >= 0;
		}

		@Override
		public void mouseUp(float mx, float my, int button) {
			pending = placeAt(mx, my);
			if (pending >= 0) {
				super.mouseUp(mx, my, button);
			}
			pending = -1;
		}

		@Override
		protected void paint(Canvas c) {
			float cx = x + w / 2;
			float cy = y + h / 2;
			float r = radius();
			float s = place();
			List<String> now = wheelNow();
			MenuStyle.ring(c, cx - r, cy - r, 2 * r, 2 * r, r, Math.max(1, c.px(d(1))), MenuStyle.SECTION_EDGE);
			int hot = hover > 0.01F && screen() != null ? placeAt(screen().mouseX(), screen().mouseY()) : -1;
			for (int i = 0; i < CosmeticKinds.EMOTE_SLOTS; i++) {
				float[] p = centre(i);
				boolean picked = wheelPlace == i;
				Item item = WardrobeModel.owned(shown, now == null ? "" : now.get(i));
				MenuStyle.framed(c, p[0] - s / 2, p[1] - s / 2, s, s, MenuStyle.radius(), hot == i ? MenuStyle.CARD_HOVER : MenuStyle.CARD,
					hot == i ? MenuStyle.CARD_EDGE_HOVER : MenuStyle.CARD_EDGE);
				if (picked) {
					MenuStyle.chosen(c, p[0] - s / 2, p[1] - s / 2, s, s, MenuStyle.radius(), 1);
				}
				if (item != null) {
					itemPicture(c, item, CosmeticShelf.key(item), p[0] - s * 0.4F, p[1] - s * 0.4F, s * 0.8F, s * 0.8F, false);
				} else {
					number.set(String.valueOf(i + 1));
					MenuStyle.centered(c, number, p[0], p[1], Palette.withAlpha(MenuStyle.NEUTRAL, 0.7F), s);
				}
			}
			String centre = now == null ? "Update Flint to set your wheel here" : wheelSending != null || wheelWant != null ? "Saving…"
				: selected != null && selected.item != null && "emote".equals(selected.item.kind()) && selected.item.owned() ? "Now pick a place"
				: "Pick an emote, then a place";
			hint.set(centre);
			float hw = r * 1.1F;
			hint.paintCentered(c, cx, cy - hint.height(c, hw) / 2, hw, now == null ? MenuStyle.WARN_TEXT : MenuStyle.NEUTRAL);
		}
	}

	// ---------- the loadout ----------

	/** "Your loadout": the player in 3D wearing the selection (drag to turn it), what it is, and its buttons. */
	private final class Loadout extends Node {
		private static final float IN = 20;
		private final Label caption = new Label(Type.CAPTION, "YOUR LOADOUT");
		private final Lines itemName = new Lines(Type.SECTION_TITLE, "");
		private final Label kind = new Label(Type.CAPTION, "");
		private final Lines note = new Lines(Type.ROW_NOTE, "");
		private final Button main;
		private final Button second;
		private final Choice model;
		private final Stage stage = new Stage();
		private float stageH;

		Loadout() {
			add(stage);
			main = add(new Button("Equip", null, PageControls.Look.ACCENT, WardrobePage.this::primary));
			second = add(new Button("Remove", null, PageControls.Look.PLAIN, this::secondary));
			model = add(new Choice(List.of("Classic", "Slim"), i -> variant = i == 1 ? "slim" : "classic"));
			model.name("Arm model");
		}

		private void secondary() {
			Entry e = selected;
			if (e == null) {
				return;
			}
			if (e.outfit != null) {
				deleteOutfit(e.outfit);
			} else if (e.skin != null) {
				removeSkin(e.skin);
			}
		}

		private float in() {
			return Math.min(d(IN), w * 0.06F);
		}

		@Override
		protected void layout() {
			float in = in();
			float bh = d(Button.H);
			Entry sel = selected;
			boolean skin = sel != null && sel.skin != null;
			boolean two = sel != null && (sel.outfit != null || sel.skin != null);
			float pitch = Lines.pitch(measure());
			// The stage takes what the words and buttons leave: a kind line, two lines of name and three of note.
			float below = d(16) + pitch + d(6) + 2 * pitch + d(8) + 3 * pitch + d(16) + bh + (skin ? bh + d(8) : 0) + in;
			stageH = Math.max(Math.min(d(140), h * 0.4F), Math.min(h * 0.6F, h - below - d(40)));
			stage.setBounds(x + in, y + d(40), w - 2 * in, stageH);
			float by = y + h - in - bh;
			model.setVisible(skin);
			second.setVisible(two);
			if (two) {
				float half = (w - 2 * in - d(8)) / 2;
				main.setBounds(x + in, by, half, bh);
				second.setBounds(x + in + half + d(8), by, half, bh);
			} else {
				main.setBounds(x + in, by, w - 2 * in, bh);
			}
			if (skin) {
				model.setBounds(x + in, by - bh - d(8), w - 2 * in, bh);
			}
		}

		private void refresh() {
			Entry e = selected;
			// A badge: Equip only when there is another of yours to switch to.
			main.setVisible(e != null && (e.badge == null || FlintPlayers.owned().size() > 1));
			String disabled = e == null ? null : refresh(e);
			if (disabled != null) {
				main.setDisabled(disabled);
			} else if (!main.enabled()) {
				main.setEnabled(true);
			}
		}

		/** The buttons' words and looks for {@code e}; why the main one is off, or null. */
		private @Nullable String refresh(Entry e) {
			String disabled = null;
			if (e.item != null) {
				Item item = e.item;
				WardrobeModel.Unlock unlock = WardrobeModel.unlock(item);
				if (unlock != null) {
					// A community reward: how it unlocks (the Flint Discord), never the Store.
					String button = unlock.button();
					main.setVisible(button != null);
					main.setText(button != null ? button : "");
					main.setLook(PageControls.Look.ACCENT);
				} else if (!item.owned()) {
					main.setText("Get in the Flint Store");
					main.setLook(PageControls.Look.ACCENT);
				} else if ("emote".equals(item.kind())) {
					List<String> now = wheelNow();
					boolean on = now != null && now.contains(item.id());
					main.setText(on ? "Take off the wheel" : wheelPlace >= 0 ? "Put in place " + (wheelPlace + 1) : "Put on the wheel");
					main.setLook(on ? PageControls.Look.PLAIN : PageControls.Look.ACCENT);
					if (now == null) {
						disabled = "Update Flint to set your emote wheel from the game";
					}
				} else if (item.slot() == null) {
					main.setVisible(false);
				} else {
					boolean on = worn(item);
					main.setText(on ? "Unequip" : "Equip");
					main.setLook(on ? PageControls.Look.PLAIN : PageControls.Look.ACCENT);
				}
				if (shown != null && shown.stale() && item.owned()) {
					disabled = "You're offline: what you wear can't change right now";
				}
			} else if (e.outfit != null) {
				main.setText("Wear");
				main.setLook(PageControls.Look.ACCENT);
				second.setText("Delete");
				second.setLook(PageControls.Look.PLAIN);
			} else if (e.badge != null) {
				boolean on = e.badge == FlintPlayers.worn();
				main.setText(on ? "Equipped" : "Equip");
				main.setLook(on ? PageControls.Look.PLAIN : PageControls.Look.ACCENT);
				if (on) {
					disabled = "You're wearing this mark";
				}
			} else if (e.skin != null) {
				main.setText(applying ? "Applying…" : "Apply");
				main.setLook(PageControls.Look.ACCENT);
				second.setText("Remove");
				second.setLook(PageControls.Look.PLAIN);
				model.select("slim".equals(variant != null ? variant : e.skin.variant()) ? 1 : 0);
				if (applying) {
					disabled = "Applying…";
				}
			}
			return disabled;
		}

		@Override
		protected void paint(Canvas c) {
			refresh();
			float in = in();
			MenuStyle.framed(c, x, y, w, h, MenuStyle.radius(), MenuStyle.SECTION_CARD, MenuStyle.SECTION_EDGE);
			MenuStyle.text(c, caption, x + in, y + d(20), Palette.withAlpha(MenuStyle.NEUTRAL, 0.9F));
			Entry e = selected;
			float tw = w - 2 * in;
			String heading;
			String sub;
			String body;
			if (e == null) {
				heading = switch (category) {
					case BADGES -> "Your badges";
					case OUTFITS -> "Your outfits";
					case SKINS -> "Your skins";
					case EMOTES -> "Your emote wheel";
					default -> "Your look";
				};
				sub = category.title.toUpperCase(Locale.ROOT);
				body = switch (category) {
					case SKINS -> "Select a skin to try it on, choose Classic or Slim, then Apply.";
					case BADGES -> FlintPlayers.owned().size() > 1 ? "Pick the mark to show before your name on nametags, then Equip."
						: "Your Flint mark shows before your name on nametags.";
					case OUTFITS -> "Select an outfit to try it on.";
					case EMOTES -> "Pick an emote, then a place on the wheel. Play them in game from the wheel.";
					default -> "Select an item to try it on. Equip, or a second click, wears it.";
				};
			} else if (e.item != null) {
				Item item = e.item;
				heading = item.name();
				WardrobeModel.Unlock unlock = WardrobeModel.unlock(item);
				sub = CosmeticKinds.categoryOf(item.kind()).one.toUpperCase(Locale.ROOT)
					+ (item.owned() ? "" : unlock != null ? " · " + unlock.tag() : item.price() >= 0 ? " · " + item.price() + " MERIT" : " · IN THE STORE");
				String gift = item.gift() ? (item.giftFrom() != null ? "A gift from " + item.giftFrom() + ". " : "A gift. ") : "";
				body = gift + (unlock != null ? unlock.body() : !item.owned() ? "Get it in the Flint Store to " + ("emote".equals(item.kind()) ? "play it." : "wear it.")
					: "emote".equals(item.kind()) ? "Plays from your emote wheel in game."
					: CosmeticPreview.renderer() == null && !"cape".equals(item.kind()) ? "It shows on you once Flint draws " + category.title.toLowerCase(Locale.ROOT) + "."
					: worn(item) ? "You're wearing it. Other Flint players see it on you." : "Equip it to wear it.");
			} else if (e.outfit != null) {
				heading = e.outfit.name();
				sub = "OUTFIT";
				body = "Wears everything in it at once.";
			} else if (e.skin != null) {
				heading = e.skin.name();
				sub = "SKIN";
				body = "username".equals(e.skin.source()) ? "From the player " + e.skin.origin() + "." : "From an image address.";
			} else {
				FlintPlayers.Tag t = Objects.requireNonNull(e.badge);
				heading = badgeName(t);
				sub = "BADGE";
				body = t == FlintPlayers.worn() ? FlintPlayers.fromLauncher() ? "You're wearing it. Other Flint players see it before your name."
					: "You're wearing it: it shows before your name on nametags."
					: t == FlintPlayers.Tag.DEFAULT ? "Every Flint player's mark. Equip it to wear it instead." : "Flint gave you this mark. Equip it to wear it.";
			}
			itemName.set(heading);
			kind.set(sub);
			note.set(body);
			float pitch = Lines.pitch(c);
			float ty = y + d(40) + stageH + d(16);
			float bottom = (model.visible() ? model.y() : main.visible() ? main.y() : y + h - in) - d(12);
			MenuStyle.fitted(c, kind, x + in, ty + pitch / 2, Palette.withAlpha(MenuStyle.NEUTRAL, 0.9F), tw);
			ty += pitch + d(6);
			List<TextLine> nameLines = itemName.lines(c, tw);
			int nameN = (int) Math.max(0, Math.min(Math.min(2, nameLines.size()), (bottom - ty) / pitch));
			for (int i = 0; i < nameN; i++) {
				TextLine line = nameLines.get(i);
				c.text(line, c.px(x + in), c.baselineFor(line, c.px(ty + pitch * i + pitch / 2)), MenuStyle.TEXT, c.px(tw));
			}
			ty += nameN * pitch + d(8);
			List<TextLine> lines = note.lines(c, tw);
			int fit = (int) Math.max(0, Math.min(lines.size(), (bottom - ty) / pitch));
			for (int i = 0; i < fit; i++) {
				TextLine line = lines.get(i);
				c.text(line, c.px(x + in), c.baselineFor(line, c.px(ty + pitch * i + pitch / 2)), MenuStyle.SENTENCE_TEXT);
			}
		}

		/** The player on a quiet stage, turning slowly; dragging turns it. */
		private final class Stage extends Node {
			private float yaw = 160;
			private float dragFrom;
			private float yawFrom;
			private boolean dragging;
			private long lastFrame = Clock.millis();
			private boolean previewFailed;

			Stage() {
				name("Loadout preview");
			}

			@Override
			public boolean interactive() {
				return true;
			}

			@Override
			public boolean mouseDown(float mx, float my, int button) {
				if (button != Kit.LEFT_BUTTON) {
					return false;
				}
				dragging = true;
				dragFrom = mx;
				yawFrom = yaw;
				return true;
			}

			@Override
			public void mouseDrag(float mx, float my) {
				if (dragging) {
					yaw = yawFrom - (mx - dragFrom) / Math.max(1, w) * 360.0F;
				}
			}

			@Override
			public void mouseUp(float mx, float my, int button) {
				dragging = false;
			}

			@Override
			public String narration() {
				return "Preview of your loadout. Drag to turn it.";
			}

			@Override
			protected void paint(Canvas c) {
				long now = Clock.millis();
				float dt = Math.min(100, now - lastFrame);
				lastFrame = now;
				if (!dragging && !Kit.reduced()) {
					yaw += dt / TURN_MILLIS * 360.0F;
				}
				float rad = MenuStyle.radius();
				MenuStyle.framed(c, x, y, w, h, rad, MenuStyle.PREVIEW_FRAME,
					Palette.lerp(MenuStyle.CARD_EDGE, MenuStyle.CARD_EDGE_HOVER, Math.max(hover, dragging ? 1 : 0)));
				Map<String, Item> outfit = previewOutfit();
				Item cape = outfit.get("cape");
				Texture capeTex = cape == null ? null : capeTexture(cape);
				Texture skin = null;
				boolean slim = false;
				Entry e = selected;
				if (e != null && e.skin != null) {
					skin = skinTexture(e.skin);
					slim = "slim".equals(variant != null ? variant : e.skin.variant());
				} else if (appliedSkin != null) {
					skin = appliedSkin;
					slim = appliedSlim;
				}
				boolean drawn = false;
				if (!previewFailed) {
					Texture s = skin;
					boolean sl = slim;
					boolean[] ok = new boolean[1];
					// The player with room around it.
					int px0 = c.px(x + w * 0.1F);
					int py0 = c.px(y + h * 0.08F);
					int pw = c.px(x + w * 0.9F) - px0;
					int ph = c.px(y + h * 0.92F) - py0;
					try {
						CosmeticPreview.drawWearing(outfit, () -> ok[0] = MenuHost.previewPlayer(c.frame(), s, sl, capeTex, px0, py0, pw, ph, yaw, 6.0F));
					} catch (RuntimeException | LinkageError err) {
						previewFailed = true;
						com.flintclient.client.FlintLog.LOGGER.warn("Flint wardrobe: the player preview failed; it shows the picture instead", err);
					}
					drawn = ok[0];
				}
				if (!drawn) {
					// Where this Minecraft can't draw the player: the selection's picture.
					if (e != null && e.item != null) {
						itemPicture(c, e.item, CosmeticShelf.key(e.item), x + w * 0.15F, y + h * 0.1F, w * 0.7F, h * 0.8F, false);
					} else if (e != null && e.skin != null && skin != null) {
						skinFront(c, skin, slim, x, y + d(10), w, h - d(20));
					} else if (cape != null) {
						itemPicture(c, cape, CosmeticShelf.key(cape), x + w * 0.2F, y + h * 0.1F, w * 0.6F, h * 0.8F, false);
					}
				}
			}
		}
	}

	/** What the preview player wears: what is worn now, with the selection (owned or not) in its slot. */
	private Map<String, Item> previewOutfit() {
		Map<String, Item> out = new LinkedHashMap<>();
		Cosmetics c = shown;
		Entry sel = selected;
		if (sel != null && sel.outfit != null) {
			for (Map.Entry<String, String> e : sel.outfit.slots().entrySet()) {
				Item item = WardrobeModel.owned(c, e.getValue());
				if (item != null) {
					out.put(e.getKey(), item);
				}
			}
			return out;
		}
		for (Map.Entry<String, String> e : wornNow().entrySet()) {
			Item item = WardrobeModel.owned(c, e.getValue());
			if (item != null) {
				out.put(e.getKey(), item);
			}
		}
		out.putAll(tryOn);
		if (sel != null && sel.item != null && sel.item.slot() != null) {
			out.put(sel.item.slot(), sel.item);
		}
		return out;
	}

	// ---------- small controls ----------

	/**
	 * A compact square button with one icon that opens a short menu of choices (sort, filter); its tooltip and narration say
	 * the current choice, and a small dot marks it while that isn't the first.
	 */
	private final class MenuButton extends Pressable {
		private final int codepoint;
		private final String what;
		private final List<String> options;
		private final java.util.function.IntSupplier current;
		private final IntConsumer onPick;
		private int tipFor = -1;

		MenuButton(int codepoint, String what, List<String> options, java.util.function.IntSupplier current, IntConsumer onPick) {
			super(null);
			this.codepoint = codepoint;
			this.what = what;
			this.options = options;
			this.current = current;
			this.onPick = onPick;
			setAction(this::open);
			name(what);
		}

		private void open() {
			screen.showModal(new Choices(this, options, current.getAsInt(), onPick));
		}

		@Override
		public String narration() {
			return what + ": " + options.get(current.getAsInt()) + ", button";
		}

		@Override
		protected void paint(Canvas c) {
			int now = current.getAsInt();
			if (now != tipFor) {
				tipFor = now;
				tooltip(what + ": " + options.get(now));
			}
			float rad = MenuStyle.radius();
			boolean set = now != 0;
			MenuStyle.framed(c, x, y, w, h, rad, Palette.lerp(MenuStyle.SEARCH, MenuStyle.CARD_HOVER, hover),
				Palette.lerp(MenuStyle.SEARCH_EDGE, MenuStyle.SEARCH_EDGE_HOVER, hover));
			glyph(c, codepoint, x + w / 2, y + h / 2, h * 0.36F, set ? MenuStyle.TEXT : Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover));
			if (set) {
				SocialParts.dot(c, x + w - d(8), y + d(8), d(5), MenuStyle.ACCENT);
			}
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** The short menu a {@link MenuButton} opens under itself: its choices, the current one ticked; a click outside closes it. */
	private static final class Choices extends Node {
		private final Node anchor;
		private final List<Label> labels = new ArrayList<>();
		private final int chosen;
		private final IntConsumer onPick;
		private float mx;
		private float my;
		private float mw;
		private float rowH;

		Choices(Node anchor, List<String> options, int chosen, IntConsumer onPick) {
			this.anchor = anchor;
			for (String o : options) {
				labels.add(new Label(Type.SEGMENT, o));
			}
			this.chosen = chosen;
			this.onPick = onPick;
			name("Choices");
		}

		@Override
		public boolean interactive() {
			return true;
		}

		@Override
		protected void layout() {
			Canvas c = measure();
			float widest = 0;
			for (Label l : labels) {
				widest = Math.max(widest, l.width(c));
			}
			rowH = d(36);
			mw = widest + d(48);
			mx = Math.max(x + d(8), Math.min(anchor.right() - mw, x + w - mw - d(8)));
			my = anchor.bottom() + d(6);
			if (my + labels.size() * rowH + d(8) > y + h) {
				my = anchor.y() - d(6) - labels.size() * rowH - d(8);
			}
		}

		private int at(float px, float py) {
			if (px < mx || px > mx + mw || py < my + d(4)) {
				return -1;
			}
			int i = (int) ((py - my - d(4)) / rowH);
			return i < labels.size() ? i : -1;
		}

		@Override
		public boolean mouseDown(float px, float py, int button) {
			int i = at(px, py);
			if (screen() != null) {
				screen().closeModal(this);
			}
			if (i >= 0 && button == Kit.LEFT_BUTTON) {
				onPick.accept(i);
			}
			return true;
		}

		@Override
		protected void paint(Canvas c) {
			float h0 = labels.size() * rowH + d(8);
			MenuStyle.framed(c, mx, my, mw, h0, MenuStyle.radius(), MenuStyle.CARD, MenuStyle.CARD_EDGE_HOVER);
			int hot = screen() == null ? -1 : at(screen().mouseX(), screen().mouseY());
			for (int i = 0; i < labels.size(); i++) {
				float ry = my + d(4) + i * rowH;
				if (i == hot) {
					MenuStyle.box(c, mx + d(4), ry, mw - d(8), rowH, MenuStyle.radius(), Palette.withAlpha(MenuStyle.TEXT, 0.06F));
				}
				if (i == chosen) {
					glyph(c, CHECK, mx + d(18), ry + rowH / 2, d(10), MenuStyle.TEXT);
				}
				MenuStyle.text(c, labels.get(i), mx + d(32), ry + rowH / 2, i == chosen || i == hot ? MenuStyle.TEXT : MenuStyle.SENTENCE_TEXT);
			}
		}
	}

	/** A row of two options, one chosen, in Flint's chip look (the chosen one white): Username | Image URL, Classic | Slim. */
	static final class Choice extends Pressable {
		private final List<Label> labels = new ArrayList<>();
		private final IntConsumer onPick;
		private int chosen;
		private int hot = -1;
		private float slide = -1;
		/** The option a click (or Enter: the next one) picks. */
		private int pending = -1;

		Choice(List<String> options, IntConsumer onPick) {
			super(null);
			for (String o : options) {
				labels.add(new Label(Type.SEGMENT, o));
			}
			this.onPick = onPick;
			setAction(() -> pick(pending >= 0 ? pending : (chosen + 1) % labels.size()));
		}

		void select(int i) {
			chosen = Math.max(0, Math.min(labels.size() - 1, i));
		}

		@Override
		public float prefWidth() {
			Canvas c = measure();
			// Every option as wide as the widest, so the chosen one's highlight fits each.
			float widest = 0;
			for (Label l : labels) {
				widest = Math.max(widest, l.width(c));
			}
			return labels.size() * (widest + 2 * d(12)) + 2 * d(3);
		}

		private int at(float mx) {
			float segment = (w - 2 * d(3)) / labels.size();
			return Math.max(0, Math.min(labels.size() - 1, (int) ((mx - x - d(3)) / segment)));
		}

		@Override
		public void mouseUp(float mx, float my, int button) {
			pending = at(mx);
			super.mouseUp(mx, my, button);
			pending = -1;
		}

		private void pick(int i) {
			if (i != chosen) {
				chosen = i;
				onPick.accept(i);
			}
		}

		@Override
		public String narration() {
			return accessibleName() + ": " + labels.get(chosen).text() + ", " + (chosen + 1) + " of " + labels.size();
		}

		@Override
		protected void paint(Canvas c) {
			float rad = MenuStyle.radius();
			MenuStyle.framed(c, x, y, w, h, rad, MenuStyle.SEARCH, MenuStyle.SEARCH_EDGE);
			float segment = (w - 2 * d(3)) / labels.size();
			slide = slide < 0 || Kit.reduced() ? chosen : Kit.approach(slide, chosen, 0.25F);
			hot = hover > 0.01F && screen() != null ? at(screen().mouseX()) : -1;
			float sx = x + d(3) + slide * segment;
			MenuStyle.box(c, sx, y + d(3), segment, h - 2 * d(3), Math.max(0, rad - d(2)), MenuStyle.CHIP_SELECTED);
			for (int i = 0; i < labels.size(); i++) {
				int ink = i == chosen ? MenuStyle.CHIP_SELECTED_TEXT : Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hot == i ? 0.8F : 0.2F);
				MenuStyle.centered(c, labels.get(i), x + d(3) + segment * i + segment / 2, y + h / 2, ink, segment - d(6));
			}
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}
}
