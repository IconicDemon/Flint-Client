package com.flintclient.client.ui.screen;

import com.flintclient.client.render.Icon;
import com.flintclient.client.ui.widget.MenuButton;
import com.mojang.blaze3d.Blaze3D;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.network.chat.Component;

/** Quick access to useful game utilities. Every entry opens a real Minecraft screen or folder. */
public final class UtilitiesScreen extends FlintPageScreen {
	private static final int ROW_W = 132;
	private static final int ROW_H = 15;
	private static final int GAP = 4;

	public UtilitiesScreen(Screen parent) {
		super(parent, Component.translatable("flintclient.menu.utilities"));
	}

	@Override
	protected void initPage(int contentTop) {
		Path game = minecraft.gameDirectory.toPath();
		Row[] rows = {
			new Row("flintclient.utilities.language", Icon.GLOBE, () -> minecraft.gui.setScreen(new LanguageSelectScreen(this, minecraft.options, minecraft.getLanguageManager()))),
			new Row("flintclient.utilities.accessibility", Icon.ACCESSIBILITY, () -> minecraft.gui.setScreen(new AccessibilityOptionsScreen(this, minecraft.options))),
			new Row("flintclient.utilities.gameFolder", Icon.FOLDER, () -> open(game)),
			new Row("flintclient.utilities.modsFolder", Icon.FOLDER, () -> open(game.resolve("mods"))),
			new Row("flintclient.utilities.packsFolder", Icon.LAYERS, () -> open(minecraft.getResourcePackDirectory())),
			new Row("flintclient.utilities.credits", Icon.SPARK, () -> minecraft.gui.setScreen(new CreditsAndAttributionScreen(this))),
		};
		int total = rows.length * ROW_H + (rows.length - 1) * GAP;
		int y = Math.max(contentTop, (height - total) / 2);
		MenuButton first = null;
		for (Row row : rows) {
			MenuButton button = addRenderableWidget(new MenuButton(Component.translatable(row.key), row.icon, MenuButton.Style.NEUTRAL, row.action));
			button.setRectangle(ROW_W, ROW_H, (width - ROW_W) / 2, y);
			y += ROW_H + GAP;
			if (first == null) {
				first = button;
			}
		}
		setInitialFocus(first);
	}

	private static void open(Path folder) {
		try {
			Files.createDirectories(folder);
		} catch (Exception ignored) {
			// Opening still works if it already exists; if not, the system reports it.
		}
		Blaze3D.openPath(folder);
	}

	private record Row(String key, Icon icon, Runnable action) {
	}
}
