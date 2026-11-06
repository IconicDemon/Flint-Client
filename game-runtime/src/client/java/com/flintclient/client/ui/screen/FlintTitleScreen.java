package com.flintclient.client.ui.screen;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlCenter;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.module.account.AccountSwitcher;
import com.flintclient.client.module.account.LauncherLink;
import com.flintclient.client.module.news.WhatsNew;
import com.flintclient.client.module.servers.QuickPlay;
import com.flintclient.client.module.servers.ServerShortcuts;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.MaskAtlas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.Paragraph;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.Backdrop;
import com.flintclient.client.ui.FlintFrame;
import com.flintclient.client.ui.FlintTooltip;
import com.flintclient.client.ui.MenuScale;
import com.flintclient.client.ui.Motion;
import com.flintclient.client.ui.UiFit;
import com.flintclient.client.ui.widget.AccountPill;
import com.flintclient.client.ui.widget.CardChip;
import com.flintclient.client.ui.widget.CardRow;
import com.flintclient.client.ui.widget.FlintButton;
import com.flintclient.client.ui.widget.IconButton;
import com.flintclient.client.ui.widget.MenuButton;
import com.flintclient.client.ui.widget.MenuToggle;
import com.flintclient.client.ui.widget.NewsCard;
import com.flintclient.client.ui.widget.ServerSlot;
import com.flintclient.client.ui.widget.WidgetVisibility;
import com.flintclient.client.util.FlintInfo;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.SkinCustomizationScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Flint's main menu, shown in place of Minecraft's title screen. It extends {@link TitleScreen} so
 * Minecraft still treats it as the title screen (menu music, Realms and other checks), but builds its
 * own controls and draws its own background (no panorama).
 *
 * <p>Every control does something real: Singleplayer and Multiplayer open Minecraft's own screens, the gear opens Minecraft's
 * options, Friends and Messages open Flint Friends (the Flint app's friends, requests and chats; the Friends icon counts what's
 * waiting), Cosmetics the player's Flint Store cosmetics, and Store a small dialog over the menu that opens the Flint Store.
 * This is Flint's menu: with Minecraft's own title screen chosen (the menu switch), its own Friends button opens Minecraft's
 * friends list, as always.
 *
 * <p>The left rail is Quick Play: the servers the player pinned in Multiplayer, one shortcut each (their
 * own icon, or a monogram), at most five. With nothing pinned there is no rail at all.
 *
 * <p>The top bar has the menu switch at the left (Flint and Vanilla side by side: Minecraft's own title screen then shows from
 * then on, with the same switch to come back, see {@link #titleFor}; the two menus blend into each other, {@link MenuBlend})
 * and the account at the right, whose pill opens the account card: Flint's Minecraft accounts, switched to right here
 * ({@link AccountSwitcher}), Add account (Flint's own sign-in) and Skin layers. Toasts start below the top bar.
 */
public final class FlintTitleScreen extends TitleScreen {
	private static final long ENTRANCE_MILLIS = 220;
	/** How long the dropdown and the account card take to open, and to close (closing is quicker: it gets out of the way). */
	private static final float OPEN_MILLIS = 180.0F;
	private static final float CLOSE_MILLIS = 120.0F;
	/** Each row of an opening card comes in over this long, a little after the row above it. */
	private static final float ROW_MILLIS = 160.0F;
	private static final float STAGGER_MILLIS = 22.0F;

	private final MenuLayout layout = new MenuLayout();
	private final List<ServerSlot> slots = new ArrayList<>();
	private final TextLine wordmark = new TextLine(FlintFonts.WORDMARK, "FLINT", 0.34F);
	private final TextLine footer = new TextLine(FlintFonts.SMALL, "");
	private AccountPill account;
	private IconButton friends;
	private NewsCard news;
	private com.flintclient.client.ui.widget.RewardsCard rewards;
	/** The pinned servers, read once per visit (with servers.dat), not on every resize. */
	private @Nullable List<QuickPlay.Entry> pins;
	/** Their icons, by address, made once per visit and freed when the menu goes. */
	private final Map<String, FaviconTexture> icons = new HashMap<>();
	private final Map<String, Identifier> iconIds = new HashMap<>();
	/** The Store dialog (Open Store, which asks Minecraft's link confirmation first, and Close), over the menu. */
	private boolean storeOpen;
	private float storeShown;
	private MenuButton storeClose;
	private MenuButton storeVisit;
	private final TextLine storeTitle = new TextLine(FlintFonts.STRONG, "");
	private Paragraph storeBodyText;
	private int cardX;
	private int cardY;
	private int cardW;
	private int cardH;
	private long openedAt;
	private int friendRequests;
	private int ticks;
	/** Where toasts start (GUI units), below the top bar. */
	private int toastTop;
	/** The menu switch (Flint, Vanilla), top-left. */
	private MenuToggle menuSwitch;
	/** Kept while this menu dissolves over Minecraft's title screen: its server icons are freed once that is over. */
	private boolean iconsKept;

	/** The account card under the account pill: its rows (accounts, Add account, Skin layers), rebuilt as they change. */
	private boolean accountOpen;
	private float accountShown;
	private float accountRowsMillis;
	private final List<CardRow> accountRows = new ArrayList<>();
	/** The small controls on and under the rows (remove, Remove / Keep, Copy link / Cancel), each placed by its row. */
	private final List<Chip> accountChips = new ArrayList<>();
	/** The rows and chips the card draws, rows first. */
	private final List<FlintButton> accountParts = new ArrayList<>();
	/** Where the sign-in's chips go (under the "Continue in your browser" row), or -1. */
	private int chipsLineY = -1;
	private int builtRevision = -1;
	private String builtUser = "";
	private final TextLine accountHeading = new TextLine(FlintFonts.SMALL, "", 0.12F);
	private @Nullable Paragraph accountNote;
	private boolean noteIsError;
	private int headingY;
	private int noteY;
	private int separatorY;
	private int popX;
	private int popY;
	private int popW;
	private int popH;

	/** Set once Flint's title screen failed on this Minecraft: Minecraft's own title screen is shown for the rest of the run. */
	private static volatile boolean failed;

	public FlintTitleScreen() {
		super(false);
	}

	/** Whether Flint's title screen runs on this Minecraft (false once it failed). */
	public static boolean fits() {
		return !failed && UiFit.fits(FlintTitleScreen.class, MenuButton.class, ServerSlot.class, NewsCard.class, com.flintclient.client.ui.widget.RewardsCard.class, AccountPill.class, IconButton.class,
			MenuToggle.class, CardRow.class, CardChip.class);
	}

	// ---------- Flint's main menu or Minecraft's ----------

	/** Whether the player chose Minecraft's own title screen (the menu choice, or General › Vanilla Main Menu). */
	public static boolean vanillaChosen() {
		try {
			return ControlState.settings().gameBool(GameOptions.VANILLA_MAIN_MENU);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/**
	 * The screen to show in place of {@code screen} (from {@code Gui.setScreen}, every way a screen is shown): a title screen
	 * becomes the one the player chose, Flint's or Minecraft's, so leaving a world, Back from a page and a stale title screen
	 * kept by a screen opened before the choice all land on the chosen one. Any other screen is shown as it is.
	 */
	public static @Nullable Screen titleFor(@Nullable Screen screen) {
		if (screen == null) {
			return null;
		}
		Class<?> type = screen.getClass();
		if (minecraftsTitle(type)) {
			return fits() && !vanillaChosen() ? new FlintTitleScreen() : screen;
		}
		if (type == FlintTitleScreen.class && (vanillaChosen() || !fits())) {
			return new TitleScreen();
		}
		return screen;
	}

	/**
	 * Whether a screen of {@code type} is Minecraft's title screen as the player sees it: the class itself, or a subclass
	 * another mod shows in its place that adds no code of its own (Force Close Loading Screen's {@code TitleBridgeScreen},
	 * which only starts it without the fade-in). Such a stand-in becomes Flint's menu too; a mod's own title screen, with
	 * code of its own, is left to that mod. Never Flint's menu.
	 */
	public static boolean minecraftsTitle(Class<?> type) {
		return type == TitleScreen.class || STAND_IN.get(type);
	}

	private static final ClassValue<Boolean> STAND_IN = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			if (!TitleScreen.class.isAssignableFrom(type) || FlintTitleScreen.class.isAssignableFrom(type)) {
				return false;
			}
			for (Class<?> c = type; c != TitleScreen.class; c = c.getSuperclass()) {
				for (java.lang.reflect.Method method : c.getDeclaredMethods()) {
					if (!method.isSynthetic()) {
						return false;
					}
				}
			}
			return true;
		}
	};

	/** Remembers the choice of title screen (Gui.setScreen then shows the chosen one, see titleFor). */
	static void remember(boolean vanilla) {
		try {
			if (ControlState.settings().setGame(GameOptions.VANILLA_MAIN_MENU, vanilla)) {
				ControlState.saveAll();
			}
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: couldn't save the main menu choice", e);
		}
	}

	/**
	 * How far down toasts start while Flint's main menu is shown, in GUI units: clear of its top bar (the account and the
	 * icons at the top right, where toasts slide in). 0 on any other screen.
	 */
	public static int toastInset() {
		try {
			Screen shown = net.minecraft.client.Minecraft.getInstance().gui.screen();
			return shown instanceof FlintTitleScreen menu ? menu.toastTop : 0;
		} catch (RuntimeException | LinkageError e) {
			return 0;
		}
	}

	/** A Minecraft version Flint wasn't built for changed something this screen uses: Minecraft's own title screen takes over. */
	private void fallBack(Throwable error) {
		if (!failed) {
			failed = true;
			FlintClient.LOGGER.error("Flint: the title screen doesn't fit this Minecraft; showing Minecraft's own", error);
		}
		minecraft.gui.setScreen(new TitleScreen());
	}

	@Override
	protected void init() {
		try {
			layOut();
		} catch (RuntimeException | LinkageError e) {
			fallBack(e);
		}
	}

	private void layOut() {
		// Laid out in menu units: never smaller than Minecraft's automatic GUI scale (see MenuScale).
		MenuScale.adopt(this);
		slots.clear();

		// The account card's rows come first, so while it is open a click on them reaches them before anything under it.
		buildAccountRows();

		addColumnButton(Component.translatable(minecraft.isDemo() ? "menu.playdemo" : "menu.singleplayer"), Icon.USER, MenuButton.Style.NEUTRAL, this::openSingleplayer);
		MenuButton multiplayer = addColumnButton(Component.translatable("menu.multiplayer"), Icon.USERS, MenuButton.Style.NEUTRAL, this::openMultiplayer);
		Component multiplayerBlocked = MultiplayerAccess.disabledReason(minecraft);
		if (multiplayerBlocked != null) {
			multiplayer.active = false;
			multiplayer.tooltip(multiplayerBlocked);
		}
		addColumnButton(Component.translatable("flintclient.menu.cosmetics"), Icon.GEM, MenuButton.Style.NEUTRAL,
			() -> minecraft.gui.setScreen(new com.flintclient.client.control.rshift.FlintCosmeticsScreen(this)));
		addColumnButton(Component.translatable("flintclient.menu.screenshots"), Icon.CAMERA, MenuButton.Style.NEUTRAL,
			() -> minecraft.gui.setScreen(new ScreenshotsScreen(this)));
		addColumnButton(Component.translatable("flintclient.menu.store"), Icon.BAG, MenuButton.Style.ACCENT, this::openStore);
		layout.quit = addRenderableWidget(new MenuButton(Component.translatable("flintclient.menu.quit"), null, MenuButton.Style.DANGER, minecraft::stop));

		account = addRenderableWidget(new AccountPill(Component.translatable("flintclient.account.open", minecraft.getUser().getName()),
			() -> accountOpen, this::toggleAccount));
		account.tooltip(Component.translatable("flintclient.account.tooltip"));
		menuSwitch = addRenderableWidget(new MenuToggle(Component.translatable("flintclient.menu.choice.open", Component.translatable("flintclient.menu.choice.flint")),
			Component.translatable("flintclient.menu.choice.flint"), Component.translatable("flintclient.menu.choice.vanilla"), false, vanilla -> {
				if (vanilla) {
					leaveForVanilla();
				}
			}));
		menuSwitch.tooltip(Component.translatable("flintclient.menu.choice.tooltip"));
		layout.menuSwitch = menuSwitch;
		// Flint Friends, with what's waiting counted (Minecraft's own friends list is on its own title screen, the Vanilla menu).
		friends = addRenderableWidget(new IconButton(Component.translatable("flintclient.friends.open"), Icon.USERS, () -> friendRequests,
			this::openFriends));
		friends.tooltip(Component.translatable("flintclient.friends.tooltip"));
		IconButton messages = addRenderableWidget(new IconButton(Component.translatable("flintclient.menu.messages"), Icon.MESSAGE,
			() -> minecraft.gui.setScreen(com.flintclient.client.control.rshift.FlintFriendsScreen.messages(this))));
		messages.tooltip(Component.translatable("flintclient.menu.messages"));
		IconButton settings = addRenderableWidget(new IconButton(Component.translatable("flintclient.menu.settings"), Icon.GEAR,
			() -> minecraft.gui.setScreen(com.flintclient.client.compat.McCompat.optionsScreen(this, minecraft.options))));
		settings.tooltip(Component.translatable("flintclient.menu.settings"));
		IconButton utilities = addRenderableWidget(new IconButton(Component.translatable("flintclient.menu.utilities"), Icon.GRID,
			() -> minecraft.gui.setScreen(new UtilitiesScreen(this))));
		utilities.tooltip(Component.translatable("flintclient.menu.utilities"));
		layout.topIcons = List.of(friends, messages, settings, utilities);

		// Quick Play: one shortcut per pinned server; none pinned, no rail.
		List<QuickPlay.Entry> pinned = pins();
		for (QuickPlay.Entry pin : pinned) {
			ServerSlot slot = addRenderableWidget(new ServerSlot(pin, iconIds.get(QuickPlay.key(pin.address())), this::joinShortcut));
			slot.active = multiplayerBlocked == null;
			if (multiplayerBlocked != null) {
				slot.tooltip(multiplayerBlocked);
			}
			slots.add(slot);
		}
		layout.slots = slots;

		WhatsNew.Note note = WhatsNew.current();
		news = note == null ? null : addRenderableWidget(new NewsCard(note, () -> minecraft.gui.setScreen(new WhatsNewScreen(this, note))));
		layout.news = news;
		rewards = addRenderableWidget(new com.flintclient.client.ui.widget.RewardsCard(
			() -> minecraft.gui.setScreen(new com.flintclient.client.control.rshift.DailyRewardsScreen(this))));
		layout.rewards = rewards;

		footer.set(Component.translatable("flintclient.menu.footer", FlintInfo.flintVersion(), FlintInfo.minecraftVersion(), FlintInfo.loader()).getString());
		layout.account = account;
		layout.apply(width, height);
		toastTop = (int) Math.ceil((layout.topBarBottom + 4) * MenuScale.pixels() / (double) Math.max(1, minecraft.getWindow().getGuiScale()));
		placeAccountCard();
		showAccountRows(accountOpen);
		if (openedAt == 0) {
			openedAt = Util.getMillis();
		}
		refreshFriendRequests();
		ServerShortcuts.refresh(pinned);

		storeTitle.set(Component.translatable("flintclient.store.dialog.title").getString());
		storeBodyText = new Paragraph(FlintFonts.LABEL, Component.translatable("flintclient.store.dialog.body").getString());
		storeClose = addRenderableWidget(new MenuButton(Component.translatable("flintclient.store.dialog.close"), null, MenuButton.Style.NEUTRAL,
			this::closeStore));
		storeVisit = addRenderableWidget(new MenuButton(Component.translatable("flintclient.store.dialog.open"), null, MenuButton.Style.ACCENT,
			this::visitStore));
		placeStoreDialog();
		WidgetVisibility.set(storeClose, storeOpen);
		WidgetVisibility.set(storeVisit, storeOpen);
	}

	/** The pinned servers (with servers.dat read once per visit for their current names and icons). */
	private List<QuickPlay.Entry> pins() {
		if (pins == null) {
			ServerList saved = ServerShortcuts.savedServers();
			pins = ServerShortcuts.pins(saved);
			loadIcons(saved, pins);
		}
		return pins;
	}

	/** Each pinned server's saved icon as a texture (a broken icon just means the monogram). */
	private void loadIcons(ServerList saved, List<QuickPlay.Entry> pinned) {
		freeIcons();
		for (QuickPlay.Entry pin : pinned) {
			ServerData data = saved.get(pin.address());
			byte[] bytes = data == null ? null : data.getIconBytes();
			if (bytes == null) {
				continue;
			}
			try {
				FaviconTexture texture = FaviconTexture.forServer(minecraft.getTextureManager(), pin.address());
				texture.upload(NativeImage.read(bytes));
				icons.put(QuickPlay.key(pin.address()), texture);
				iconIds.put(QuickPlay.key(pin.address()), texture.textureLocation());
			} catch (Throwable broken) {
				// Minecraft's server list drops a broken icon the same way.
			}
		}
	}

	private void freeIcons() {
		for (FaviconTexture texture : icons.values()) {
			texture.close();
		}
		icons.clear();
		iconIds.clear();
	}

	// ---------- the menu switch ----------

	private static final int ROW_GAP = 1;

	/** Vanilla from the switch: Minecraft's title screen is shown at once, this menu dissolving over it. */
	private void leaveForVanilla() {
		closeAccount();
		if (storeOpen) {
			closeStore();
		}
		MenuBlend.toVanilla(this);
	}

	// ---------- the account card ----------

	private static final int CARD_W = 150;
	private static final int CARD_PAD = 4;
	private static final int HEADING_H = 9;
	private static final int CARD_FILL = 0xF5111216;
	/** The card's corner radius (the dropdown's too): a step rounder than its rows' ({@link CardRow#RADIUS}). */
	private static final float CARD_RADIUS = 4.6F;
	private static final float NOTE_LINE = FlintFonts.SMALL.size * 1.5F;
	private static final int CHIP_GAP = 3;

	/** What a chip does on its row. */
	private enum ChipRole {
		/** An account's remove button (the bin), at the row's right. */
		TRASH,
		/** "Remove" in the row's "Remove from Flint?". */
		REMOVE,
		/** "Keep", beside it. */
		KEEP,
		/** Under the waiting sign-in's row: Copy link, then Cancel. */
		COPY,
		CANCEL
	}

	private record Chip(CardChip chip, CardRow row, ChipRole role) {
	}

	private void toggleAccount() {
		if (accountOpen) {
			closeAccount();
		} else {
			openAccount();
		}
	}

	private void openAccount() {
		accountOpen = true;
		accountRowsMillis = accountShown * OPEN_MILLIS;
		AccountSwitcher.clearError();
		AccountSwitcher.refresh();
		showAccountRows(true);
		if (!accountRows.isEmpty()) {
			setFocused(accountRows.get(0));
		}
	}

	private void closeAccount() {
		if (!accountOpen) {
			return;
		}
		accountOpen = false;
		AccountSwitcher.cardClosed();
		showAccountRows(false);
		setFocused(null);
	}

	/** Puts the card's rows within reach (or out of it), and of its chips the ones that belong to the card now. */
	private void showAccountRows(boolean shown) {
		for (CardRow row : accountRows) {
			WidgetVisibility.set(row, shown);
		}
		for (Chip chip : accountChips) {
			WidgetVisibility.set(chip.chip(), shown && chip.chip().shown());
		}
	}

	/**
	 * The card's rows for what {@link AccountSwitcher} knows now: the game's account (ticked), Flint's other accounts (click
	 * one to switch; each with a remove button when there is more than one), then Microsoft's sign-in button (or, while a
	 * sign-in waits, "Continue in your browser" with Copy link and Cancel) and Skin layers. Without Flint, the game's account
	 * and a note to open the app.
	 */
	private void buildAccountRows() {
		accountRows.clear();
		accountChips.clear();
		accountParts.clear();
		builtRevision = AccountSwitcher.revision();
		builtUser = minecraft.getUser().getName();
		AccountSwitcher.Reach reach = AccountSwitcher.reach();
		String current = AccountSwitcher.currentId();
		String switching = AccountSwitcher.switching();
		boolean busy = AccountSwitcher.busy();
		List<LauncherLink.Account> listed = reach == AccountSwitcher.Reach.READY ? AccountSwitcher.accounts() : List.of();
		if (listed.stream().noneMatch(a -> a.uuid().equals(current))) {
			CardRow.Trail trail = reach == AccountSwitcher.Reach.LOADING ? CardRow.Trail.SPINNER : CardRow.Trail.CHECK;
			accountRows.add(CardRow.account(current, builtUser, text("flintclient.account.playing"), () -> trail, this::closeAccount));
		}
		boolean removable = listed.size() > 1;
		for (LauncherLink.Account entry : listed) {
			boolean playing = entry.uuid().equals(current);
			boolean target = entry.uuid().equals(switching);
			String uuid = entry.uuid();
			String detail = text(playing ? "flintclient.account.playing" : target ? "flintclient.account.switching"
				: entry.offline() ? "flintclient.account.offline" : "flintclient.account.microsoft");
			String ask = text("flintclient.account.remove.ask");
			String removingText = text("flintclient.account.removing");
			CardRow.Trail fixed = playing ? CardRow.Trail.CHECK : target ? CardRow.Trail.SPINNER : CardRow.Trail.NONE;
			CardRow row = CardRow.account(uuid, entry.username(), detail,
				() -> uuid.equals(AccountSwitcher.removing()) ? CardRow.Trail.SPINNER : fixed,
				playing ? this::closeAccount : () -> {
					// While its row asks "Remove?", a click on the row keeps the account.
					if (uuid.equals(AccountSwitcher.confirming())) {
						AccountSwitcher.keep();
					} else {
						AccountSwitcher.switchTo(uuid);
					}
				});
			row.detailFrom(() -> uuid.equals(AccountSwitcher.removing()) ? removingText : uuid.equals(AccountSwitcher.confirming()) ? ask : detail,
				() -> uuid.equals(AccountSwitcher.confirming()) || uuid.equals(AccountSwitcher.removing()));
			row.active = playing || !busy;
			accountRows.add(row);
			if (removable) {
				addRemoveChips(row, uuid, entry.username(), playing);
			}
		}
		if (reach == AccountSwitcher.Reach.READY) {
			CardRow signIn;
			if (AccountSwitcher.adding()) {
				signIn = CardRow.microsoft(Component.translatable("flintclient.account.adding.narration"), text("flintclient.account.adding"),
					text("flintclient.account.adding.detail"), () -> CardRow.Trail.SPINNER, AccountSwitcher::openSignInPage);
				String copy = text("flintclient.account.copyLink");
				String copied = text("flintclient.account.copied");
				CardChip copyChip = CardChip.label(CardChip.Kind.NEUTRAL, Icon.COPY, () -> AccountSwitcher.linkCopied() ? copied : copy,
					AccountSwitcher::adding, AccountSwitcher::copySignInLink);
				copyChip.active = AccountSwitcher.signInUrl() != null;
				String cancel = text("flintclient.account.cancel");
				CardChip cancelChip = CardChip.label(CardChip.Kind.NEUTRAL, Icon.CLOSE, () -> cancel, AccountSwitcher::adding, AccountSwitcher::cancelAdd);
				accountChips.add(new Chip(copyChip, signIn, ChipRole.COPY));
				accountChips.add(new Chip(cancelChip, signIn, ChipRole.CANCEL));
			} else {
				// Microsoft's own sign-in button (its logo, its words, its dark style); with one account it says what it's for.
				boolean first = listed.size() <= 1;
				signIn = CardRow.microsoft(Component.translatable("flintclient.account.signIn.narration"), text("flintclient.account.signIn"),
					first ? text("flintclient.account.signIn.first") : null, () -> CardRow.Trail.NONE, AccountSwitcher::add);
				signIn.active = switching == null && AccountSwitcher.removing() == null;
			}
			accountRows.add(signIn);
		}
		accountRows.add(CardRow.action(Icon.USER, false, text("flintclient.account.skinLayers"), null, () -> CardRow.Trail.NONE,
			() -> {
				closeAccount();
				minecraft.gui.setScreen(new SkinCustomizationScreen(this, minecraft.options));
			}));
		// Chips first: a click on one reaches it before the row under it.
		for (Chip chip : accountChips) {
			addRenderableWidget(chip.chip());
		}
		for (CardRow row : accountRows) {
			addRenderableWidget(row);
			row.surface(CARD_FILL);
		}
		accountParts.addAll(accountRows);
		for (Chip chip : accountChips) {
			accountParts.add(chip.chip());
		}
		String error = AccountSwitcher.error();
		noteIsError = error != null;
		accountNote = error != null ? new Paragraph(FlintFonts.SMALL, error)
			: reach == AccountSwitcher.Reach.NO_APP ? new Paragraph(FlintFonts.SMALL, text("flintclient.account.noApp")) : null;
		accountHeading.set(text("flintclient.account.heading"));
	}

	/**
	 * An account row's remove button (the bin, coming in as the pointer reaches the row) and its "Remove from Flint?" answer
	 * (Remove, Keep). The game's own account has the bin too, unavailable, its tooltip saying why.
	 */
	private void addRemoveChips(CardRow row, String uuid, String name, boolean playing) {
		CardChip trash = CardChip.icon(Component.translatable("flintclient.account.remove.narration", name), Icon.TRASH, row,
			() -> !uuid.equals(AccountSwitcher.confirming()) && AccountSwitcher.removing() == null, () -> AccountSwitcher.confirmRemove(uuid));
		trash.active = !playing;
		trash.tooltip(Component.translatable(playing ? "flintclient.account.remove.playing" : "flintclient.account.remove.tooltip"));
		accountChips.add(new Chip(trash, row, ChipRole.TRASH));
		row.trailRoom(playing ? 9.0F + CardChip.ICON_SIZE : CardChip.ICON_SIZE + 1.0F);
		if (playing) {
			return;
		}
		String remove = text("flintclient.account.remove");
		String keep = text("flintclient.account.keep");
		CardChip removeChip = CardChip.label(CardChip.Kind.DANGER, null, () -> remove, () -> uuid.equals(AccountSwitcher.confirming()),
			() -> AccountSwitcher.remove(uuid));
		CardChip keepChip = CardChip.label(CardChip.Kind.NEUTRAL, null, () -> keep, () -> uuid.equals(AccountSwitcher.confirming()),
			AccountSwitcher::keep);
		accountChips.add(new Chip(removeChip, row, ChipRole.REMOVE));
		accountChips.add(new Chip(keepChip, row, ChipRole.KEEP));
	}

	private static String text(String key) {
		return Component.translatable(key).getString();
	}

	/**
	 * Under the account pill, kept inside the screen's right margin: heading, accounts, a note, a line, then the sign-in (and,
	 * while it waits, its chips) and Skin layers. Each chip is placed on (or under) its row.
	 */
	private void placeAccountCard() {
		popW = Math.min(CARD_W, width - 8);
		int right = Math.max(account.getX() + account.getWidth(), layout.topIcons.isEmpty() ? width - 8
			: layout.topIcons.get(layout.topIcons.size() - 1).getX() + MenuLayout.ICON_BUTTON);
		popX = Math.max(4, Math.min(account.getX(), right - popW));
		popY = account.getY() + account.getHeight() + 4;
		int inner = popW - 2 * CARD_PAD;
		int y = popY + CARD_PAD;
		headingY = y;
		y += HEADING_H;
		int actions = AccountSwitcher.reach() == AccountSwitcher.Reach.READY ? 2 : 1;
		int accounts = accountRows.size() - actions;
		for (int i = 0; i < accounts; i++) {
			accountRows.get(i).setRectangle(inner, CardRow.TALL, popX + CARD_PAD, y);
			y += CardRow.TALL + ROW_GAP;
		}
		Canvas m = Canvas.measuring(MenuScale.pixels());
		if (accountNote != null) {
			int lines = accountNote.lines(m, m.px(inner - 6)).size();
			noteY = y + 3;
			y = noteY + Math.round(lines * NOTE_LINE) + 2;
		}
		separatorY = y + 2;
		y += 5;
		chipsLineY = -1;
		for (int i = accounts; i < accountRows.size(); i++) {
			CardRow row = accountRows.get(i);
			boolean signIn = i == accounts && actions == 2;
			boolean tall = signIn && (AccountSwitcher.adding() || accounts <= 1);
			int h = tall ? CardRow.TALL : CardRow.SHORT;
			row.setRectangle(inner, h, popX + CARD_PAD, y);
			y += h + ROW_GAP;
			if (signIn) {
				// The sign-in button stands apart from Skin layers.
				y += 2;
				if (AccountSwitcher.adding()) {
					chipsLineY = y;
					y += CardChip.HEIGHT + 4;
				}
			}
		}
		popH = y - ROW_GAP + CARD_PAD - popY;
		placeChips(m);
	}

	private void placeChips(Canvas m) {
		int copyRight = popX + CARD_PAD;
		for (Chip chip : accountChips) {
			CardChip c = chip.chip();
			CardRow row = chip.row();
			int rowRight = row.getX() + row.getWidth() - 3;
			int middle = row.getY() + row.getHeight() / 2;
			switch (chip.role()) {
				case TRASH -> {
					// The game's own row keeps its tick at the right: its bin sits just before it.
					int x = rowRight - CardChip.ICON_SIZE - (c.active ? 0 : 9);
					c.setRectangle(CardChip.ICON_SIZE, CardChip.ICON_SIZE, x, middle - CardChip.ICON_SIZE / 2);
				}
				case REMOVE -> {
					int w = c.preferredWidth(m);
					c.setRectangle(w, CardChip.HEIGHT, rowRight - w, middle - CardChip.HEIGHT / 2);
				}
				case KEEP -> {
					Chip remove = accountChips.stream().filter(o -> o.row() == row && o.role() == ChipRole.REMOVE).findFirst().orElseThrow();
					int w = c.preferredWidth(m);
					c.setRectangle(w, CardChip.HEIGHT, remove.chip().getX() - CHIP_GAP - w, middle - CardChip.HEIGHT / 2);
				}
				case COPY, CANCEL -> {
					// Side by side under the waiting sign-in, sharing its width.
					int inner = popW - 2 * CARD_PAD;
					int w = (inner - CHIP_GAP) / 2;
					int x = chip.role() == ChipRole.COPY ? copyRight : copyRight + w + CHIP_GAP;
					c.setRectangle(chip.role() == ChipRole.COPY ? w : inner - w - CHIP_GAP, CardChip.HEIGHT, x, Math.max(0, chipsLineY));
				}
			}
		}
	}

	private boolean onAccountCard(double x, double y) {
		return x >= popX && x < popX + popW && y >= popY && y < popY + popH;
	}

	/** The card's state changed (a list from Flint, a switch begun or done, a sign-in): its rows are made again. */
	private void syncAccountCard() {
		if (AccountSwitcher.revision() != builtRevision || !minecraft.getUser().getName().equals(builtUser)) {
			boolean refocus = getFocused() instanceof CardRow || getFocused() instanceof CardChip;
			rebuildWidgets();
			if (refocus && accountOpen && !accountRows.isEmpty()) {
				setFocused(accountRows.get(0));
			}
		}
	}

	// ---------- the dropdown and the card: drawing ----------

	/** Moves {@code shown} towards open or closed at a constant rate (eased when drawn): {@link #OPEN_MILLIS} in, {@link #CLOSE_MILLIS} out. */
	private static float step(float shown, boolean open) {
		return Motion.toward(shown, open ? 1.0F : 0.0F, open ? OPEN_MILLIS : CLOSE_MILLIS);
	}

	/**
	 * A popover under a top-bar pill, on its own layer over the menu (which doesn't dim). Opening, it grows from 94 % out of
	 * the pill's corner ({@code anchorX}, {@code anchorY}), drops 3 units into place and fades in, eased out over
	 * {@link #OPEN_MILLIS}; its rows follow one after another ({@link #STAGGER_MILLIS} apart), each fading and settling 2 units.
	 * Closing, the whole card goes at once, quicker. {@code parts} (rows, then chips) are drawn inside it; a chip only while it
	 * is part of the card. {@code extra} draws the card's own text. Everything runs on frame time, the same at any frame rate.
	 */
	private void paintPopover(GuiGraphicsExtractor graphics, float shown, float rowsMillis, boolean open, int x0, int y0, int w0, int h0,
		float anchorX, float anchorY, List<? extends FlintButton> parts, int mouseX, int mouseY, float partialTick,
		java.util.function.@Nullable Consumer<Canvas> extra) {
		float t = Motion.easeOut(shown);
		graphics.nextStratum();
		Canvas c = Canvas.begin(Frame.of(graphics));
		try {
			int x = c.px(x0);
			int y = c.px(y0);
			int w = c.px(w0);
			int h = c.px(h0);
			c.pushOffset(0, -Math.round((1.0F - t) * c.px(3.0F)));
			c.pushZoom((open ? 0.94F : 0.97F) + (open ? 0.06F : 0.03F) * t, c.px(anchorX), c.px(anchorY));
			c.setOpacity(t);
			int border = Math.max(1, Math.round(c.scale() / 4.0F));
			int radius = c.px(CARD_RADIUS);
			// A soft shadow, then the card.
			for (int i = 3; i >= 1; i--) {
				int spread = c.px(1.2F * i);
				c.roundRect(x - spread, y - spread + c.px(1.5F), w + 2 * spread, h + 2 * spread, radius + spread, 0x14000000);
			}
			c.panel(x, y, w, h, radius, border, Palette.BORDER_HOVER, CARD_FILL);
			if (extra != null) {
				extra.accept(c);
			}
			// The parts draw inside this frame, so they fade and move with the card; they take the pointer only once it's open.
			int mx = open && shown >= 1.0F ? mouseX : -1;
			int my = open && shown >= 1.0F ? mouseY : -1;
			float rowHeight = CardRow.TALL + ROW_GAP;
			for (FlintButton part : parts) {
				if (part instanceof CardChip chip && !chip.shown()) {
					continue;
				}
				// Each row (and its chips) comes in a little after the one above it.
				float delay = Math.max(0, part.getY() - y0) / rowHeight * STAGGER_MILLIS;
				float rowT = open ? Motion.easeOut((rowsMillis - delay) / ROW_MILLIS) : 1.0F;
				if (rowT <= 0.0F) {
					continue;
				}
				c.setOpacity(t * rowT);
				int settle = Math.round((1.0F - rowT) * c.px(2.0F));
				c.pushOffset(0, settle);
				// While closing, the parts are already out of reach (hidden), but still drawn as the card fades away.
				boolean hidden = !WidgetVisibility.get(part);
				if (hidden) {
					WidgetVisibility.set(part, true);
				}
				try {
					part.extractRenderState(graphics, mx, my, partialTick);
				} finally {
					if (hidden) {
						WidgetVisibility.set(part, false);
					}
					c.popOffset();
				}
			}
			c.setOpacity(1.0F);
			c.popOffset();
			c.popOffset();
		} finally {
			c.end();
		}
	}

	private void paintAccountCard(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		accountShown = step(accountShown, accountOpen);
		accountRowsMillis = accountOpen ? accountRowsMillis + Motion.frameSeconds() * 1000.0F : Float.MAX_VALUE;
		if (accountShown <= 0.0F) {
			return;
		}
		if (accountOpen) {
			// A chip comes and goes with the card's state (Remove? asked, a sign-in waiting) without the card being made again.
			for (Chip chip : accountChips) {
				WidgetVisibility.set(chip.chip(), chip.chip().shown());
			}
		}
		// It grows out of the pill's lower-left corner (inside the card, which may sit further left near the screen's edge).
		float anchorX = Math.clamp(account.getX(), popX, popX + popW);
		paintPopover(graphics, accountShown, accountRowsMillis, accountOpen, popX, popY, popW, popH, anchorX, popY, accountParts,
			mouseX, mouseY, partialTick, c -> {
				int x = c.px(popX + CARD_PAD + 1);
				c.text(accountHeading, x, c.baselineFor(accountHeading, c.px(headingY + HEADING_H / 2.0F)), Palette.FAINT);
				if (accountNote != null) {
					int inner = c.px(popW - 2 * CARD_PAD - 6);
					int ink = noteIsError ? Palette.DANGER_TEXT : Palette.MUTED;
					List<TextLine> lines = accountNote.lines(c, inner);
					for (int i = 0; i < lines.size(); i++) {
						TextLine line = lines.get(i);
						c.text(line, c.px(popX + CARD_PAD + 3), c.baselineFor(line, c.px(noteY + NOTE_LINE * (i + 0.5F))), ink);
					}
				}
				int line = Math.max(1, Math.round(c.scale() / 4.0F));
				c.fill(c.px(popX + CARD_PAD), c.px(separatorY), c.px(popX + popW - CARD_PAD), c.px(separatorY) + line, 0x14FFFFFF);
			});
	}

	// ---------- the Store dialog ----------

	private void openStore() {
		storeOpen = true;
		WidgetVisibility.set(storeClose, true);
		WidgetVisibility.set(storeVisit, true);
		setFocused(storeVisit);
	}

	private void closeStore() {
		storeOpen = false;
		WidgetVisibility.set(storeClose, false);
		WidgetVisibility.set(storeVisit, false);
		setFocused(null);
	}

	/** The Flint Store (the launcher's, from its handoff), after Minecraft's link confirmation, which comes back here. */
	private void visitStore() {
		closeStore();
		String store = com.flintclient.client.control.store.StoreEndpoint.DEFAULT_BASE;
		try {
			com.flintclient.client.control.store.StoreHandoff handoff = new com.flintclient.client.control.store.StoreHandoff(minecraft.gameDirectory.toPath());
			handoff.poll();
			store = handoff.endpoint().origin();
		} catch (RuntimeException e) {
			// The built-in Store, as the game's capes use without a handoff.
		}
		net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, java.net.URI.create(store));
	}

	/** A small card in the middle of the screen, Close and Open Store side by side at its foot. */
	private void placeStoreDialog() {
		cardW = Math.min(170, width - 24);
		cardH = 78;
		cardX = (width - cardW) / 2;
		cardY = (height - cardH) / 2;
		int buttonW = Math.min(70, (cardW - 24) / 2);
		int left = (width - 2 * buttonW - 6) / 2;
		storeClose.setRectangle(buttonW, 15, left, cardY + cardH - 15 - 9);
		storeVisit.setRectangle(buttonW, 15, left + buttonW + 6, cardY + cardH - 15 - 9);
	}

	private MenuButton addColumnButton(Component label, Icon icon, MenuButton.Style style, Runnable action) {
		MenuButton button = addRenderableWidget(new MenuButton(label, icon, style, action));
		layout.column.add(button);
		return button;
	}

	@Override
	protected void rebuildWidgets() {
		layout.column.clear();
		super.rebuildWidgets();
	}

	@Override
	public void resize(int width, int height) {
		layout.column.clear();
		super.resize(width, height);
	}

	// ---------- actions ----------

	private void openSingleplayer() {
		if (minecraft.isDemo()) {
			DemoWorld.play(minecraft, this);
		} else {
			minecraft.gui.setScreen(new SelectWorldScreen(this));
		}
	}

	private void openMultiplayer() {
		minecraft.gui.setScreen(minecraft.options.skipMultiplayerWarning ? new JoinMultiplayerScreen(this) : new SafetyScreen(this));
	}

	private void openFriends() {
		minecraft.gui.setScreen(com.flintclient.client.control.rshift.FlintFriendsScreen.friends(this));
	}

	/** Joins a pinned server through Minecraft's own connection screen (its errors and timeouts included). */
	private void joinShortcut(QuickPlay.Entry slot) {
		if (!minecraft.options.skipMultiplayerWarning) {
			// The first multiplayer visit shows Minecraft's safety notice, as the Multiplayer button does.
			minecraft.gui.setScreen(new SafetyScreen(this));
			return;
		}
		ServerList saved = ServerShortcuts.savedServers();
		ServerData data = saved.get(slot.address());
		if (data == null) {
			data = new ServerData(slot.name(), slot.address(), ServerData.Type.OTHER);
		}
		ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(slot.address()), data, false, null);
	}

	/** Flint Friends' count (requests waiting, unread messages), asked of the Flint app every 15 s while the menu is up. */
	private void refreshFriendRequests() {
		com.flintclient.client.module.social.FriendsClient client = com.flintclient.client.module.social.FriendsClient.get(
			com.flintclient.client.control.rshift.FlintFriendsScreen::playing);
		client.want();
		client.tick();
		friendRequests = client.badge();
	}

	// ---------- input ----------

	/** The Control Center key opens it over the menu (when that feature is on); closing it comes back here. */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (storeOpen) {
			// The dialog takes the keyboard: Escape closes it, Enter or Space press Close.
			if (event.isEscape()) {
				closeStore();
				return true;
			}
			if (event.isCycleFocus() || event.isLeft() || event.isRight()) {
				setFocused(getFocused() == storeVisit ? storeClose : storeVisit);
				return true;
			}
			if (getFocused() == storeVisit) {
				storeVisit.keyPressed(event);
			} else {
				storeClose.keyPressed(event);
			}
			return true;
		}
		if (event.isEscape() && accountOpen) {
			closeAccount();
			setFocused(account);
			return true;
		}
		if (ControlCenter.isOpenKey(event)) {
			ControlCenter.requestOpen(this);
			return true;
		}
		return super.keyPressed(event);
	}

	/** The same when the Control Center key is bound to a mouse button. */
	@Override
	public boolean mouseClicked(MouseButtonEvent guiEvent, boolean doubleClick) {
		MouseButtonEvent event = MenuScale.toMenu(guiEvent);
		if (storeOpen) {
			// Only its buttons work while the dialog is up; a click outside the card closes it too.
			boolean onCard = event.x() >= cardX && event.x() < cardX + cardW && event.y() >= cardY && event.y() < cardY + cardH;
			if (storeClose.isMouseOver(event.x(), event.y())) {
				return storeClose.mouseClicked(event, doubleClick);
			}
			if (storeVisit.isMouseOver(event.x(), event.y())) {
				return storeVisit.mouseClicked(event, doubleClick);
			}
			if (!onCard) {
				closeStore();
			}
			return true;
		}
		if (accountOpen) {
			// The open card's rows work; a click anywhere else (its pill included) closes it and goes no further.
			if (onAccountCard(event.x(), event.y())) {
				return super.mouseClicked(event, doubleClick);
			}
			closeAccount();
			return true;
		}
		if (ControlCenter.isOpenKey(event)) {
			ControlCenter.requestOpen(this);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return super.mouseReleased(MenuScale.toMenu(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return super.mouseDragged(MenuScale.toMenu(event), MenuScale.toMenu(dx), MenuScale.toMenu(dy));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		return super.mouseScrolled(MenuScale.toMenu(x), MenuScale.toMenu(y), scrollX, scrollY);
	}

	@Override
	public void mouseMoved(double x, double y) {
		super.mouseMoved(MenuScale.toMenu(x), MenuScale.toMenu(y));
	}

	// ---------- lifecycle ----------

	@Override
	public void tick() {
		ServerShortcuts.tick();
		AccountSwitcher.tick();
		syncAccountCard();
		ticks++;
		refreshFriendRequests();
	}

	// Gui.setScreen calls removed() on the screen being replaced, whatever replaces it (a page, a
	// connecting or world-loading screen, an overlay), and added() before init() on the one shown.
	@Override
	public void removed() {
		ServerShortcuts.stop();
		// Dissolving over Minecraft's title screen, it still draws its pinned servers' icons: they go when that is over.
		iconsKept = MenuBlend.leaving(this);
		if (!iconsKept) {
			freeIcons();
		}
		pins = null;
		storeOpen = false;
		storeShown = 0;
		accountOpen = false;
		accountShown = 0;
	}

	/** The blend away from this menu is over (or was cut short). */
	void ghostDone() {
		if (iconsKept && minecraft.gui.screen() != this) {
			iconsKept = false;
			freeIcons();
		}
	}

	@Override
	public void added() {
		// Coming in through the blend, the blend is its entrance.
		openedAt = MenuBlend.flintShown() < 1.0F ? Util.getMillis() - ENTRANCE_MILLIS : 0;
		if (iconsKept) {
			iconsKept = false;
			freeIcons();
		}
		// Servers may have been pinned, edited or deleted while away.
		pins = null;
		// Flint's accounts, so the account card opens with them.
		AccountSwitcher.refresh();
	}

	// ---------- drawing ----------

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Coming back from Minecraft's title screen, it is drawn first, as it was, and this menu fades in over it.
		MenuBlend.paintVanillaUnder(graphics, partialTick);
		FlintFrame.begin(graphics);
		Canvas c = Canvas.beginAtGuiScale(Frame.of(graphics), MenuScale.pixels());
		try {
			c.setOpacity(MenuBlend.flintShown());
			Backdrop.paint(c, width, height, Backdrop.MENU);
		} finally {
			c.end();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int guiMouseX, int guiMouseY, float partialTick) {
		// Everything below draws in menu units, inside one frame at the menu's scale; nested canvases follow it.
		Canvas frame = Canvas.beginAtGuiScale(Frame.of(graphics), MenuScale.pixels());
		try {
			drawMenu(graphics, (int) MenuScale.toMenu(guiMouseX), (int) MenuScale.toMenu(guiMouseY), partialTick);
		} catch (RuntimeException | LinkageError e) {
			fallBack(e);
		} finally {
			frame.end();
		}
	}

	private void drawMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (openedAt == 0) {
			openedAt = Util.getMillis();
		}
		float entrance = Motion.reduced() ? 1.0F : Motion.easeOut((Util.getMillis() - openedAt) / (float) ENTRANCE_MILLIS);
		// Coming in over Minecraft's title screen, everything but what both menus share fades in, settling from a little nearer.
		float shown = MenuBlend.flintControlsShown();
		paintContent(graphics, mouseX, mouseY, partialTick, entrance, shown, 1.0F + MenuBlend.DRIFT * (1.0F - shown), true);

		boolean covered = storeOpen || accountOpen;
		if (!covered) {
			FlintTooltip.draw(graphics, width, height);
		}
		paintAccountCard(graphics, mouseX, mouseY, partialTick);
		if (accountOpen && !storeOpen) {
			// The card's own tooltips (why the game's account can't be removed), over the card.
			FlintTooltip.draw(graphics, width, height);
		}
		paintStoreDialog(graphics, mouseX, mouseY, partialTick);

		if (entrance < 1.0F) {
			// A short fade from the background colour; the controls are already live underneath.
			graphics.nextStratum();
			Canvas overlay = Canvas.begin(Frame.of(graphics));
			try {
				overlay.fill(0, 0, overlay.px(width), overlay.px(height), Palette.withAlpha(Palette.BACKGROUND_TOP, 1.0F - entrance));
			} finally {
				overlay.end();
			}
		}
		MaskAtlas.get().endFrame();
	}

	/** What Minecraft's title screen also has, in the same place: the menu switch and the cards. */
	private boolean shared(FlintButton button) {
		return button == menuSwitch || button == news || button == rewards;
	}

	/**
	 * The logo, the version line, the Quick Play rail and the controls, at {@code opacity} and scaled by {@code zoom} about the
	 * middle of the screen (a blend between the menus); with {@code sharedToo}, then what Minecraft's title screen has in the
	 * same place (the switch and the cards), sharp and in place. The popovers are drawn on their own.
	 */
	private void paintContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, float entrance, float opacity, float zoom,
		boolean sharedToo) {
		// TitleScreen's own drawing (panorama, logo, splash) is skipped; only Flint's controls are drawn.
		// With the Store dialog or the account card up, the menu underneath doesn't react to the pointer.
		boolean covered = storeOpen || accountOpen;
		int mx = covered ? -1 : mouseX;
		int my = covered ? -1 : mouseY;
		Canvas c = Canvas.begin(Frame.of(graphics));
		float before = c.opacity();
		boolean zoomed = Math.abs(zoom - 1.0F) > 0.0005F;
		try {
			if (zoomed) {
				c.pushZoom(zoom, c.px(width) / 2.0F, c.px(height) / 2.0F);
			}
			c.setOpacity(before * opacity);
			paintLogo(c, entrance);
			int footerY = c.px(layout.footerY);
			c.text(footer, (c.px(width) - c.width(footer)) / 2, footerY, Palette.MUTED);
			if (layout.railVisible) {
				int border = Math.max(1, Math.round(c.scale() / 4.0F));
				c.panel(c.px(layout.railX), c.px(layout.railY), c.px(layout.railW), c.px(layout.railH), c.px(3.6F), border, Palette.RAIL_BORDER, Palette.RAIL);
			}
			for (var child : children()) {
				if (child instanceof FlintButton button && button != storeClose && button != storeVisit && !(button instanceof CardRow) && !(button instanceof CardChip)
					&& !shared(button)) {
					// The account pill stays lit under the pointer while its card is open.
					boolean live = accountOpen && button == account;
					button.extractRenderState(graphics, live ? mouseX : mx, live ? mouseY : my, partialTick);
				}
			}
			if (zoomed) {
				c.popOffset();
				zoomed = false;
			}
			c.setOpacity(before);
			if (sharedToo) {
				for (var child : children()) {
					if (child instanceof FlintButton button && shared(button) && button.visible) {
						button.extractRenderState(graphics, mx, my, partialTick);
					}
				}
			}
		} finally {
			if (zoomed) {
				c.popOffset();
			}
			c.setOpacity(before);
			c.end();
		}
	}

	/**
	 * This menu as it dissolves over Minecraft's title screen (the blend to Vanilla): its artwork at {@code artwork} opacity,
	 * its controls at {@code controls} drifting towards you ({@code zoom}); the switch and the cards are that screen's own by now.
	 */
	void paintGhost(GuiGraphicsExtractor graphics, float artwork, float controls, float zoom) {
		FlintFrame.begin(graphics);
		Canvas c = Canvas.beginAtGuiScale(Frame.of(graphics), MenuScale.pixels());
		try {
			c.setOpacity(artwork);
			Backdrop.paint(c, width, height, Backdrop.MENU);
			c.setOpacity(1.0F);
			if (controls > 0.0F) {
				paintContent(graphics, -1, -1, 0.0F, 1.0F, controls, zoom, false);
			}
		} finally {
			c.end();
			FlintFrame.end();
		}
	}

	/** The Store dialog: a dim over the menu and a small card that fades and rises in over about 120 ms. */
	private void paintStoreDialog(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Frame time, as everything here: in over 140 ms, out over 100 ms, whatever the frame rate.
		storeShown = Motion.toward(storeShown, storeOpen ? 1.0F : 0.0F, storeOpen ? 140.0F : 100.0F);
		if (storeShown <= 0.0F) {
			return;
		}
		float t = Motion.easeOut(storeShown);
		graphics.nextStratum();
		Canvas c = Canvas.begin(Frame.of(graphics));
		try {
			c.fill(0, 0, c.px(width), c.px(height), Palette.withAlpha(0xFF000000, 0.55F * t));
			int rise = Math.round((1.0F - t) * c.px(4.0F));
			int x = c.px(cardX);
			int y = c.px(cardY) + rise;
			int w = c.px(cardW);
			int h = c.px(cardH);
			int border = Math.max(1, Math.round(c.scale() / 4.0F));
			c.setOpacity(t);
			c.panel(x, y, w, h, c.px(4.0F), border, Palette.BORDER, 0xF2111216);
			// A short ember line at the top: the only accent.
			c.roundRect(x + w / 2 - c.px(12), y + border, c.px(24), Math.max(1, c.px(0.8F)), c.px(0.4F), Palette.ACCENT);
			int badge = c.px(14);
			int bx = x + (w - badge) / 2;
			int by = y + c.px(8);
			c.roundRect(bx, by, badge, badge, badge / 2, 0xFF1D1610);
			int iconPx = c.px(6.5F);
			c.icon(Icon.BAG, bx + (badge - iconPx) / 2, by + (badge - iconPx) / 2, iconPx, Palette.ACCENT_TEXT);
			int titleBase = by + badge + c.px(10);
			c.text(storeTitle, x + (w - c.width(storeTitle)) / 2, titleBase, Palette.TEXT);
			int baseline = titleBase + c.px(9);
			for (TextLine line : storeBodyText.lines(c, w - c.px(20))) {
				c.text(line, x + (w - c.width(line)) / 2, baseline, Palette.MUTED);
				baseline += storeBodyText.lineHeightPx(c);
			}
			c.setOpacity(1.0F);
		} finally {
			c.end();
		}
		if (storeOpen) {
			storeClose.extractRenderState(graphics, mouseX, mouseY, partialTick);
			storeVisit.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
	}

	private void paintLogo(Canvas c, float entrance) {
		int markH = c.px(layout.markHeight);
		int markW = MaskAtlas.markWidth(markH);
		int gap = c.px(4.6F);
		int wordW = c.width(wordmark);
		int left = (c.px(width) - (markW + gap + wordW)) / 2;
		int rise = Math.round((1.0F - entrance) * c.px(2.0F));
		int top = c.px(layout.logoY) + rise;
		c.mark(left, top, markH, 1.0F);
		// The wordmark's capitals are centred on the mark.
		c.text(wordmark, left + markW + gap, c.baselineFor(wordmark, top + markH / 2), Palette.TEXT);
	}
}
