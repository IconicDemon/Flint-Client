package com.flintclient.client.ui.screen;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.MaskAtlas;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.MenuScale;
import com.flintclient.client.ui.screen.MenuGeometry.Box;
import com.flintclient.client.ui.screen.MenuGeometry.Part;
import com.flintclient.client.ui.widget.AccountPill;
import com.flintclient.client.ui.widget.FlintButton;
import com.flintclient.client.ui.widget.IconButton;
import com.flintclient.client.ui.widget.MenuButton;
import com.flintclient.client.ui.widget.MenuToggle;
import com.flintclient.client.ui.widget.NewsCard;
import com.flintclient.client.ui.widget.RewardsCard;
import com.flintclient.client.ui.widget.ServerSlot;
import com.flintclient.client.ui.widget.WidgetVisibility;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.components.AbstractWidget;
import org.jspecify.annotations.Nullable;

/**
 * Places the main menu's parts for any window size and GUI scale, in GUI units (whole units, so every
 * visible edge lands on a pixel and hit areas match what's drawn).
 *
 * <p>The composition follows the reference design: the logo above a centred column of five buttons,
 * Quit Game set apart below with the version line, the account row top-right, the menu switch top-left (both close to the corners),
 * the Quick Play rail on the left edge (only when servers are pinned, one to five) and the "What's new" card bottom-right with
 * "Daily rewards" above it. Gaps shrink when the window is short; the rail and the cards give way (narrower, then hidden) rather
 * than ever overlapping the menu. Minecraft's own title screen places its switch and cards with the same rules
 * ({@link VanillaTitleExtras}), so they stay put when the player changes menus.
 *
 * <p>Each part is drawn at the size the player picked for it (Utilities › Menu sizes; {@link MenuGeometry} does the arithmetic):
 * its rectangle is that size, so the pointer finds it where it is drawn, and the control draws itself crisply at that size
 * ({@link FlintButton#drawScale}).
 */
final class MenuLayout {
	static final int ICON_BUTTON = MenuGeometry.ICON_BUTTON;

	final List<MenuButton> column = new ArrayList<>();
	MenuButton quit;
	AccountPill account;
	/** The menu choice (Flint or Vanilla), top-left. */
	MenuToggle menuSwitch;
	List<IconButton> topIcons = List.of();
	List<ServerSlot> slots = List.of();
	@Nullable NewsCard news;
	@Nullable RewardsCard rewards;
	/** The "Join our Discord" card (above Daily rewards), the showcase and other mods' buttons (bottom left); null or empty: none. */
	com.flintclient.client.ui.widget.@Nullable DiscordCard discord;
	com.flintclient.client.ui.widget.@Nullable ShowcaseCard showcase;
	List<com.flintclient.client.ui.widget.ExternalButton> externals = List.of();
	/** The logo's wordmark and the version line (the screen's own text), measured at the sizes they're drawn at. */
	@Nullable TextLine wordmark;
	@Nullable TextLine footer;

	/** Computed positions, read by the screen when drawing. */
	float markHeight = MenuGeometry.MARK_HEIGHT;
	/** The top bar's bottom edge (toasts start below it). */
	int topBarBottom;
	int logoY;
	/** The sizes the logo and the version line are drawn at (1: their design size). */
	float logoScale = 1.0F;
	float footerScale = 1.0F;
	int footerY;
	boolean railVisible;
	int railX;
	int railY;
	int railW;
	int railH;
	/** The whole result, for the parts' areas (Menu sizes shows which part a size changes). */
	MenuGeometry.@Nullable Result result;

	/** The size the player picked for {@code part} (1: its design size), from Utilities › Menu sizes. */
	static float asked(Part part) {
		try {
			return ControlState.settings().gameInt(GameOptions.MENU_SIZES.get(part.ordinal())) / 100.0F;
		} catch (RuntimeException | LinkageError e) {
			return 1.0F;
		}
	}

	/** The top bar's distance from the top and from the sides: close to the corners. */
	static int barTop(int height) {
		return MenuGeometry.barTop(height);
	}

	static int barSide(int width) {
		return MenuGeometry.barSide(width);
	}

	/** Where a top-left switch goes, how big it is and the size it is drawn at, in menu units (Minecraft's title screen). */
	static Box switchSpot(MenuToggle toggle, int width, int height) {
		float unit = MenuScale.pixels();
		float scale = Math.max(MenuGeometry.least(unit), asked(Part.SWITCH));
		return MenuGeometry.switchBox(s -> measured(toggle::preferredWidth, unit, s), width, height, scale);
	}

	/** Where a card goes, how big it is, the size it is drawn at, and whether it shows there. */
	interface Spot {
		void place(Box box);
	}

	/**
	 * "What's new" at the bottom right and "Daily rewards" above it (or alone there), in menu units: kept {@code clearRight}
	 * units right of anything in the middle, and above Minecraft's copyright line (which its own title screen draws along the
	 * bottom), narrowing, then smaller, then hiding rather than coming closer.
	 */
	static void placeCards(@Nullable Spot news, @Nullable Spot rewards, int width, int height, int clearRight, int topBarBottom) {
		float unit = MenuScale.pixels();
		Box[] cards = MenuGeometry.cards(news != null, rewards != null, asked(Part.NEWS), asked(Part.REWARDS), unit, width, height, clearRight,
			topBarBottom, copyright(unit));
		if (news != null) {
			news.place(cards[0]);
		}
		if (rewards != null) {
			rewards.place(cards[1]);
		}
	}

	/** Minecraft's copyright line along the bottom of its title screen, in menu units, with a little room. */
	private static int copyright(float unit) {
		return (int) Math.ceil(11.0F * com.flintclient.client.platform.Frame.windowGuiScale() / unit) + 3;
	}

	/**
	 * A control's width in menu units at {@code scale} (its {@code preferredWidth}, measured at the size it is drawn at): at 100 %
	 * exactly what it always was.
	 */
	static int measured(ToIntFunction<Canvas> preferredWidth, float unit, float scale) {
		if (scale == 1.0F) {
			return preferredWidth.applyAsInt(Canvas.measuring(unit));
		}
		return Math.round(preferredWidth.applyAsInt(Canvas.measuring(unit * scale)) * scale);
	}

	void apply(int width, int height) {
		float unit = MenuScale.pixels();
		MenuGeometry.Input in = new MenuGeometry.Input();
		in.width = width;
		in.height = height;
		in.unit = unit;
		for (Part part : Part.values()) {
			in.asked[part.ordinal()] = asked(part);
		}
		in.pill = s -> measured(account::preferredWidth, unit, s);
		in.toggle = s -> measured(menuSwitch::preferredWidth, unit, s);
		in.logo = s -> logoWidth(unit, s);
		in.footer = s -> textWidth(footer, unit, s);
		if (footer != null) {
			in.footerCap = footer.capHeightPx(unit) / unit;
		}
		in.footerDescent = FlintFonts.SMALL.size * 0.3F;
		in.buttons = column.size();
		in.icons = topIcons.size();
		in.slots = slots.size();
		in.news = news != null;
		in.rewards = rewards != null;
		in.discord = discord != null;
		in.showcase = showcase != null;
		in.externals = externals.size();
		in.copyright = copyright(unit);
		MenuGeometry.Result r = MenuGeometry.compute(in);
		result = r;

		topBarBottom = r.topBarBottom;
		logoY = r.logo.y();
		logoScale = r.scale(Part.LOGO);
		footerScale = r.scale(Part.VERSION);
		footerY = r.footerY;
		for (int i = 0; i < column.size(); i++) {
			place(column.get(i), r.column[i]);
		}
		place(quit, r.quit);
		for (int i = 0; i < topIcons.size(); i++) {
			place(topIcons.get(i), r.icons[i]);
		}
		place(account, r.pill);
		place(menuSwitch, r.toggle);

		railVisible = r.railVisible;
		railX = r.railX;
		railY = r.railY;
		railW = r.railW;
		railH = r.railH;
		for (int i = 0; i < slots.size(); i++) {
			ServerSlot slot = slots.get(i);
			WidgetVisibility.set(slot, railVisible);
			slot.setRectangle(MenuGeometry.SLOT, MenuGeometry.SLOT, railX + MenuGeometry.RAIL_PAD,
				railY + MenuGeometry.RAIL_PAD + i * (MenuGeometry.SLOT + MenuGeometry.RAIL_GAP));
		}

		if (news != null) {
			spot(news).place(r.news);
		}
		if (rewards != null) {
			spot(rewards).place(r.rewards);
		}
		if (discord != null) {
			spot(discord).place(r.discord);
		}
		if (showcase != null) {
			spot(showcase).place(r.showcase);
		}
		for (int i = 0; i < externals.size(); i++) {
			com.flintclient.client.ui.widget.ExternalButton button = externals.get(i);
			boolean shown = i < r.externals.length;
			WidgetVisibility.set(button, shown);
			if (shown) {
				place(button, r.externals[i]);
			}
		}
	}

	/** The logo (mark, gap, wordmark) at {@code scale}, in menu units, as {@code FlintTitleScreen} draws it. */
	private int logoWidth(float unit, float scale) {
		if (wordmark == null) {
			return 0;
		}
		Canvas m = Canvas.measuring(unit * scale);
		int markH = m.px(markHeight);
		int px = MaskAtlas.markWidth(markH) + m.px(4.6F) + m.width(wordmark);
		return (int) Math.ceil(px / unit);
	}

	private static int textWidth(@Nullable TextLine line, float unit, float scale) {
		if (line == null) {
			return 0;
		}
		return (int) Math.ceil(Canvas.measuring(unit * scale).width(line) / unit);
	}

	private static Spot spot(AbstractWidget widget) {
		return box -> {
			WidgetVisibility.set(widget, box.shown());
			place(widget, box);
		};
	}

	private static void place(AbstractWidget widget, Box box) {
		widget.setRectangle(box.w(), box.h(), box.x(), box.y());
		if (widget instanceof FlintButton button) {
			button.drawScale(box.scale());
		}
	}
}
