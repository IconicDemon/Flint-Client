package com.flintclient.client.ui.screen;

import com.flintclient.client.control.rshift.DailyRewardsScreen;
import com.flintclient.client.mc.Component;
import com.flintclient.client.mc.GuiGraphicsExtractor;
import com.flintclient.client.mc.Minecraft;
import com.flintclient.client.mc.TitleScreen;
import com.flintclient.client.module.news.WhatsNew;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.ui.FlintFrame;
import com.flintclient.client.ui.FlintTooltip;
import com.flintclient.client.ui.MenuScale;
import com.flintclient.client.ui.widget.MenuScaled;
import com.flintclient.client.ui.widget.MenuToggle;
import com.flintclient.client.ui.widget.NewsCard;
import com.flintclient.client.ui.widget.RewardsCard;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * What Flint adds to Minecraft 1.8.9's own title screen when the player picked it over Flint's menu, as on every line: the menu
 * switch in the top-left corner (to come back), and the "What's new" and "Daily rewards" cards at the bottom right, above
 * Minecraft's copyright line, each drawn in menu units exactly as on Flint's menu and in the same place ({@link MenuLayout}).
 * The 1.8.9 {@code TitleScreenMixin} places, draws and clicks them. Their pages come back to Minecraft's title screen.
 */
public final class VanillaTitleExtras {
	/** Room kept between Minecraft's button column and the cards, in menu units. */
	private static final int CLEAR = 8;
	/** Half the width of Minecraft's title screen buttons, in GUI units. */
	private static final int COLUMN_HALF = 100;

	private VanillaTitleExtras() {
	}

	/** The parts for {@code screen} (Minecraft 1.8.9's title screen, {@code guiWidth} GUI units wide), placed. */
	public static List<MenuScaled> build(net.minecraft.client.gui.screen.Screen screen, int guiWidth) {
		Minecraft minecraft = Minecraft.getInstance();
		BooleanSupplier drawn = () -> true;
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
		MenuGeometry.Box spot = MenuLayout.switchSpot(toggle, width, height);
		switcher.place(spot.x(), spot.y(), spot.w(), spot.h());
		toggle.drawScale(spot.scale());
		parts.add(switcher);

		// Their pages come back to Minecraft's title screen (newer Minecraft's, which is 1.8.9's here).
		WhatsNew.Note note = WhatsNew.current();
		MenuScaled news = note == null ? null
			: new MenuScaled(new NewsCard(note, () -> minecraft.gui.setScreen(new WhatsNewScreen(new TitleScreen(), note))), drawn);
		MenuScaled rewards = new MenuScaled(new RewardsCard(() -> minecraft.gui.setScreen(new DailyRewardsScreen(screen))), drawn);
		int columnRight = (int) Math.ceil((guiWidth / 2 + COLUMN_HALF) * Frame.windowGuiScale() / px) + CLEAR;
		int topBarBottom = spot.bottom();
		MenuLayout.placeCards(news == null ? null : spot(news), spot(rewards), width, height, columnRight, topBarBottom);
		if (news != null) {
			parts.add(news);
		}
		parts.add(rewards);
		return parts;
	}

	/** At the end of the screen's drawing: the tooltip one of the parts asked for, in menu units as Flint's menu draws it. */
	public static void paintTooltip(GuiGraphicsExtractor graphics) {
		float px = MenuScale.pixels();
		Minecraft minecraft = Minecraft.getInstance();
		FlintFrame.begin(graphics);
		Canvas frame = Canvas.beginAtGuiScale(Frame.of(graphics), px);
		try {
			FlintTooltip.draw(graphics, (int) (minecraft.getWindow().getWidth() / px), (int) (minecraft.getWindow().getHeight() / px));
		} finally {
			frame.end();
			FlintFrame.end();
		}
	}

	private static MenuLayout.Spot spot(MenuScaled part) {
		return box -> {
			part.place(box.x(), box.y(), box.w(), box.h());
			part.inner().drawScale(box.scale());
			part.visible = box.shown();
			part.active = box.shown();
		};
	}
}
