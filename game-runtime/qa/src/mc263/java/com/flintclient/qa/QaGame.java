package com.flintclient.qa;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import org.jspecify.annotations.Nullable;

/** Where Minecraft keeps the screen, the overlay and the chat (26.2 and later: Minecraft.gui and its hud). */
final class QaGame {
	private QaGame() {
	}

	static @Nullable Screen screen(Minecraft mc) {
		return mc.gui.screen();
	}

	static void setScreen(Minecraft mc, @Nullable Screen screen) {
		mc.gui.setScreen(screen);
	}

	static @Nullable Overlay overlay(Minecraft mc) {
		return mc.gui.overlay();
	}

	static ChatComponent chat(Minecraft mc) {
		return mc.gui.hud.getChat();
	}

	static RenderTarget mainRenderTarget(Minecraft mc) {
		return mc.gameRenderer.mainRenderTarget();
	}

	static Camera mainCamera(Minecraft mc) {
		return mc.gameRenderer.mainCamera();
	}

	/** A creative world with commands, as create-world makes it. */
	static LevelSettings creativeLevel(String name) {
		return new LevelSettings(name, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
	}

	/** The GUI laid out again (a new GUI scale). */
	static void resizeGui(Minecraft mc) {
		mc.resizeGui();
	}

	/** The chat window's lines as shown now, newest first. */
	@SuppressWarnings("unchecked")
	static List<FormattedCharSequence> chatLines(Minecraft mc) throws ReflectiveOperationException {
		List<GuiMessage.Line> lines = (List<GuiMessage.Line>) QaNames.field(ChatComponent.class, "trimmedMessages").get(chat(mc));
		return lines.stream().map(GuiMessage.Line::content).toList();
	}
}
