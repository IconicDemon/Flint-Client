package com.flintclient.client.ui.screen;

import com.flintclient.client.control.ControlCenter;
import com.flintclient.client.module.news.WhatsNew;
import com.flintclient.client.module.servers.QuickPlay;
import com.flintclient.client.module.servers.ServerShortcuts;
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
import com.flintclient.client.ui.widget.AccountPill;
import com.flintclient.client.ui.widget.FlintButton;
import com.flintclient.client.ui.widget.IconButton;
import com.flintclient.client.ui.widget.MenuButton;
import com.flintclient.client.ui.widget.NewsCard;
import com.flintclient.client.ui.widget.ServerSlot;
import com.flintclient.client.util.FlintInfo;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.friends.FriendsOverlayScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.OnlineOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
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
 * <p>Every control does something real: Singleplayer and Multiplayer open Minecraft's own screens,
 * Friends opens Minecraft's friends list, the gear opens Minecraft's options, and features Flint doesn't
 * offer yet say so plainly (Store: a small "coming soon" dialog over the menu).
 *
 * <p>The left rail is Quick Play: the servers the player pinned in Multiplayer, one shortcut each (their
 * own icon, or a monogram), at most five. With nothing pinned there is no rail at all.
 */
public final class FlintTitleScreen extends TitleScreen {
	private static final long ENTRANCE_MILLIS = 220;

	private final MenuLayout layout = new MenuLayout();
	private final List<ServerSlot> slots = new ArrayList<>();
	private final TextLine wordmark = new TextLine(FlintFonts.WORDMARK, "FLINT", 0.34F);
	private final TextLine footer = new TextLine(FlintFonts.SMALL, "");
	private AccountPill account;
	private IconButton friends;
	private NewsCard news;
	/** The pinned servers, read once per visit (with servers.dat), not on every resize. */
	private @Nullable List<QuickPlay.Entry> pins;
	/** Their icons, by address, made once per visit and freed when the menu goes. */
	private final Map<String, FaviconTexture> icons = new HashMap<>();
	private final Map<String, Identifier> iconIds = new HashMap<>();
	/** The Store's "coming soon" dialog, over the menu. */
	private boolean storeOpen;
	private float storeShown;
	private MenuButton storeClose;
	private final TextLine storeTitle = new TextLine(FlintFonts.STRONG, "");
	private Paragraph storeBodyText;
	private int cardX;
	private int cardY;
	private int cardW;
	private int cardH;
	private long openedAt;
	private int friendRequests;
	private int ticks;

	public FlintTitleScreen() {
		super(false);
	}

	@Override
	protected void init() {
		// Laid out in menu units: never smaller than Minecraft's automatic GUI scale (see MenuScale).
		MenuScale.adopt(this);
		slots.clear();

		addColumnButton(Component.translatable(minecraft.isDemo() ? "menu.playdemo" : "menu.singleplayer"), Icon.USER, MenuButton.Style.NEUTRAL, this::openSingleplayer);
		MenuButton multiplayer = addColumnButton(Component.translatable("menu.multiplayer"), Icon.USERS, MenuButton.Style.NEUTRAL, this::openMultiplayer);
		Component multiplayerBlocked = MultiplayerAccess.disabledReason(minecraft);
		if (multiplayerBlocked != null) {
			multiplayer.active = false;
			multiplayer.tooltip(multiplayerBlocked);
		}
		addColumnButton(Component.translatable("flintclient.menu.cosmetics"), Icon.GEM, MenuButton.Style.NEUTRAL,
			() -> minecraft.gui.setScreen(ComingSoonScreen.cosmetics(this)));
		addColumnButton(Component.translatable("flintclient.menu.screenshots"), Icon.CAMERA, MenuButton.Style.NEUTRAL,
			() -> minecraft.gui.setScreen(new ScreenshotsScreen(this)));
		addColumnButton(Component.translatable("flintclient.menu.store"), Icon.BAG, MenuButton.Style.ACCENT, this::openStore);
		layout.quit = addRenderableWidget(new MenuButton(Component.translatable("flintclient.menu.quit"), null, MenuButton.Style.DANGER, minecraft::stop));

		account = addRenderableWidget(new AccountPill(Component.translatable("flintclient.account.open", minecraft.getUser().getName()),
			() -> minecraft.gui.setScreen(new SkinCustomizationScreen(this, minecraft.options))));
		account.tooltip(Component.translatable("flintclient.account.tooltip"));
		boolean friendsAvailable = !minecraft.isDemo() && !minecraft.isOfflineDeveloperMode();
		friends = addRenderableWidget(new IconButton(Component.translatable("gui.friends.open"), Icon.USERS, () -> friendRequests, this::openFriends));
		friends.active = friendsAvailable;
		friends.tooltip(friendsAvailable ? Component.translatable("gui.friends.open.tooltip") : Component.translatable("flintclient.friends.unavailable"));
		IconButton messages = addRenderableWidget(new IconButton(Component.translatable("flintclient.menu.messages"), Icon.MESSAGE,
			() -> minecraft.gui.setScreen(ComingSoonScreen.messages(this))));
		messages.tooltip(Component.translatable("flintclient.menu.messages"));
		IconButton settings = addRenderableWidget(new IconButton(Component.translatable("flintclient.menu.settings"), Icon.GEAR,
			() -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options))));
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

		footer.set(Component.translatable("flintclient.menu.footer", FlintInfo.flintVersion(), FlintInfo.minecraftVersion(), FlintInfo.loader()).getString());
		layout.account = account;
		layout.apply(width, height);
		if (openedAt == 0) {
			openedAt = Util.getMillis();
		}
		refreshFriendRequests();
		ServerShortcuts.refresh(pinned);

		storeTitle.set(Component.translatable("flintclient.store.dialog.title").getString());
		storeBodyText = new Paragraph(FlintFonts.LABEL, Component.translatable("flintclient.store.dialog.body").getString());
		storeClose = addRenderableWidget(new MenuButton(Component.translatable("flintclient.store.dialog.close"), null, MenuButton.Style.NEUTRAL,
			this::closeStore));
		placeStoreDialog();
		storeClose.visible = storeOpen;
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

	// ---------- the Store dialog ----------

	private void openStore() {
		storeOpen = true;
		storeClose.visible = true;
		setFocused(storeClose);
	}

	private void closeStore() {
		storeOpen = false;
		storeClose.visible = false;
		setFocused(null);
	}

	/** A small card in the middle of the screen, the Close button at its foot. */
	private void placeStoreDialog() {
		cardW = Math.min(170, width - 24);
		cardH = 78;
		cardX = (width - cardW) / 2;
		cardY = (height - cardH) / 2;
		storeClose.setRectangle(70, 15, (width - 70) / 2, cardY + cardH - 15 - 9);
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
		OnlineOptionsScreen.confirmFriendsListEnabled(minecraft, () -> minecraft.gui.setScreen(new FriendsOverlayScreen(this)), this);
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

	private void refreshFriendRequests() {
		var social = minecraft.getPlayerSocialManager();
		friendRequests = social.isFriendListEnabled() ? social.getIncomingRequests().size() : 0;
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
			storeClose.keyPressed(event);
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
			// Only Close works while the dialog is up; a click outside the card closes it too.
			boolean onCard = event.x() >= cardX && event.x() < cardX + cardW && event.y() >= cardY && event.y() < cardY + cardH;
			if (storeClose.isMouseOver(event.x(), event.y())) {
				return storeClose.mouseClicked(event, doubleClick);
			}
			if (!onCard) {
				closeStore();
			}
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
		if (++ticks % 40 == 0) {
			refreshFriendRequests();
		}
	}

	// Gui.setScreen calls removed() on the screen being replaced, whatever replaces it (a page, a
	// connecting or world-loading screen, an overlay), and added() before init() on the one shown.
	@Override
	public void removed() {
		ServerShortcuts.stop();
		freeIcons();
		pins = null;
		storeOpen = false;
		storeShown = 0;
	}

	@Override
	public void added() {
		openedAt = 0;
		// Servers may have been pinned, edited or deleted while away.
		pins = null;
	}

	// ---------- drawing ----------

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		FlintFrame.begin(graphics);
		Canvas c = Canvas.beginAtGuiScale(graphics, MenuScale.pixels());
		try {
			Backdrop.paint(c, width, height, Backdrop.MENU);
			if (layout.railVisible) {
				int border = Math.max(1, Math.round(c.scale() / 4.0F));
				c.panel(c.px(layout.railX), c.px(layout.railY), c.px(layout.railW), c.px(layout.railH), c.px(3.6F), border, Palette.RAIL_BORDER, Palette.RAIL);
			}
		} finally {
			c.end();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int guiMouseX, int guiMouseY, float partialTick) {
		// Everything below draws in menu units, inside one frame at the menu's scale; nested canvases follow it.
		Canvas frame = Canvas.beginAtGuiScale(graphics, MenuScale.pixels());
		try {
			drawMenu(graphics, (int) MenuScale.toMenu(guiMouseX), (int) MenuScale.toMenu(guiMouseY), partialTick);
		} finally {
			frame.end();
		}
	}

	private void drawMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (openedAt == 0) {
			openedAt = Util.getMillis();
		}
		float entrance = Motion.reduced() ? 1.0F : Motion.easeOut((Util.getMillis() - openedAt) / (float) ENTRANCE_MILLIS);

		Canvas c = Canvas.begin(graphics);
		try {
			paintLogo(c, entrance);
			int footerY = c.px(layout.footerY);
			c.text(footer, (c.px(width) - c.width(footer)) / 2, footerY, Palette.MUTED);
		} finally {
			c.end();
		}

		// TitleScreen's own drawing (panorama, logo, splash) is skipped; only Flint's controls are drawn.
		// With the Store dialog up, the menu underneath doesn't react to the pointer.
		int mx = storeOpen ? -1 : mouseX;
		int my = storeOpen ? -1 : mouseY;
		for (var child : children()) {
			if (child instanceof FlintButton button && button != storeClose) {
				button.extractRenderState(graphics, mx, my, partialTick);
			}
		}

		if (!storeOpen) {
			FlintTooltip.draw(graphics, width, height);
		}
		paintStoreDialog(graphics, mouseX, mouseY, partialTick);

		if (entrance < 1.0F) {
			// A short fade from the background colour; the controls are already live underneath.
			graphics.nextStratum();
			Canvas overlay = Canvas.begin(graphics);
			try {
				overlay.fill(0, 0, overlay.px(width), overlay.px(height), Palette.withAlpha(Palette.BACKGROUND_TOP, 1.0F - entrance));
			} finally {
				overlay.end();
			}
		}
		MaskAtlas.get().endFrame();
	}

	/** The Store dialog: a dim over the menu and a small card that fades and rises in over about 120 ms. */
	private void paintStoreDialog(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		float target = storeOpen ? 1.0F : 0.0F;
		if (storeShown != target) {
			storeShown = Motion.reduced() ? target : Math.clamp(storeShown + (storeOpen ? 0.14F : -0.2F), 0.0F, 1.0F);
		}
		if (storeShown <= 0.0F) {
			return;
		}
		float t = Motion.easeOut(storeShown);
		graphics.nextStratum();
		Canvas c = Canvas.begin(graphics);
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
