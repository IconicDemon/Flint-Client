package com.flintclient.client.ui.screen;

import com.flintclient.client.control.rshift.DailyRewardsScreen;
import com.flintclient.client.module.news.WhatsNew;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.ui.MenuScale;
import com.flintclient.client.ui.widget.MenuScaled;
import com.flintclient.client.ui.widget.MenuToggle;
import com.flintclient.client.ui.widget.NewsCard;
import com.flintclient.client.ui.widget.RewardsCard;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * What Flint adds to Minecraft's own title screen when the player picked it over Flint's menu: the menu switch in the top-left
 * corner (to come back), and the "What's new" and "Daily rewards" cards at the bottom right, above Minecraft's copyright line.
 * Each is drawn in menu units, exactly as on Flint's menu and in the same place ({@link MenuLayout}), so they stay put while
 * the menus blend into each other ({@link MenuBlend}).
 */
public final class VanillaTitleExtras {
	/** Room kept between Minecraft's button column and the cards, in menu units. */
	private static final int CLEAR = 8;
	/** Half the width of Minecraft's title screen buttons, in GUI units. */
	private static final int COLUMN_HALF = 100;

	private VanillaTitleExtras() {
	}

	/** The parts for {@code screen} (Minecraft's title screen, laid out at {@code guiWidth} GUI units wide), placed. */
	public static List<MenuScaled> build(Screen screen, int guiWidth) {
		Minecraft minecraft = Minecraft.getInstance();
		BooleanSupplier drawn = () -> !MenuBlend.coveringVanilla() && !MenuBlend.drawingGhost();
		List<MenuScaled> parts = new ArrayList<>();
		float px = MenuScale.pixels();
		int width = Math.max(1, (int) (minecraft.getWindow().getWidth() / px));
		int height = Math.max(1, (int) (minecraft.getWindow().getHeight() / px));

		MenuToggle toggle = new MenuToggle(Component.translatable("flintclient.menu.choice.open", Component.translatable("flintclient.menu.choice.vanilla")),
			Component.translatable("flintclient.menu.choice.flint"), Component.translatable("flintclient.menu.choice.vanilla"), true, vanilla -> {
				if (!vanilla) {
					MenuBlend.toFlint(screen);
				}
			});
		toggle.tooltip(Component.translatable("flintclient.menu.choice.tooltip"));
		MenuScaled switcher = new MenuScaled(toggle, drawn);
		int[] spot = MenuLayout.switchSpot(toggle, width, height);
		switcher.place(spot[0], spot[1], spot[2], spot[3]);
		parts.add(switcher);

		WhatsNew.Note note = WhatsNew.current();
		MenuScaled news = note == null ? null
			: new MenuScaled(new NewsCard(note, () -> minecraft.gui.setScreen(new WhatsNewScreen(screen, note))), drawn);
		MenuScaled rewards = new MenuScaled(new RewardsCard(() -> minecraft.gui.setScreen(new DailyRewardsScreen(screen))), drawn);
		// Clear of Minecraft's buttons: their column's right edge, in menu units.
		int columnRight = (int) Math.ceil((guiWidth / 2 + COLUMN_HALF) * Frame.windowGuiScale() / px) + CLEAR;
		int topBarBottom = spot[1] + spot[3];
		MenuLayout.placeCards(news == null ? null : spot(news), spot(rewards), width, height, columnRight, topBarBottom);
		if (news != null) {
			parts.add(news);
		}
		parts.add(rewards);
		return parts;
	}

	/**
	 * At the end of the screen's drawing: the tooltip one of the parts asked for (the switch's), drawn in menu units as Flint's
	 * menu draws it. Without this it would wait for the next Flint screen and flash there.
	 */
	public static void paintTooltip(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
		float px = MenuScale.pixels();
		Minecraft minecraft = Minecraft.getInstance();
		com.flintclient.client.ui.FlintFrame.begin(graphics);
		com.flintclient.client.render.Canvas frame = com.flintclient.client.render.Canvas.beginAtGuiScale(Frame.of(graphics), px);
		try {
			com.flintclient.client.ui.FlintTooltip.draw(graphics, (int) (minecraft.getWindow().getWidth() / px), (int) (minecraft.getWindow().getHeight() / px));
		} finally {
			frame.end();
			com.flintclient.client.ui.FlintFrame.end();
		}
	}

	private static MenuLayout.Spot spot(MenuScaled part) {
		return (x, y, w, h, shown) -> {
			part.place(x, y, w, h);
			part.visible = shown;
			part.active = shown;
		};
	}
}
