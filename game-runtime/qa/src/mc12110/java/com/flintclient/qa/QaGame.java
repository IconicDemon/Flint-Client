package com.flintclient.qa;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.GameRules;
import org.jspecify.annotations.Nullable;

/** Where Minecraft keeps the screen, the overlay and the chat (1.21.10: on Minecraft itself, and Minecraft.gui is the HUD). */
final class QaGame {
	private QaGame() {
	}

	static @Nullable Screen screen(Minecraft mc) {
		return mc.screen;
	}

	static void setScreen(Minecraft mc, @Nullable Screen screen) {
		mc.setScreen(screen);
	}

	static @Nullable Overlay overlay(Minecraft mc) {
		return mc.getOverlay();
	}

	static ChatComponent chat(Minecraft mc) {
		return mc.gui.getChat();
	}

	static RenderTarget mainRenderTarget(Minecraft mc) {
		return mc.getMainRenderTarget();
	}

	static Camera mainCamera(Minecraft mc) {
		return mc.gameRenderer.getMainCamera();
	}

	/** A creative world with commands, as create-world makes it (normal difficulty, not hardcore, as 26.x's default). */
	static LevelSettings creativeLevel(String name) {
		WorldDataConfiguration data = WorldDataConfiguration.DEFAULT;
		return new LevelSettings(name, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(data.enabledFeatures()), data);
	}

	/** The GUI laid out again (a new GUI scale): 1.21.10's resizeDisplay. */
	static void resizeGui(Minecraft mc) {
		mc.resizeDisplay();
	}

	/** The chat window's lines as shown now, newest first. */
	@SuppressWarnings("unchecked")
	static List<FormattedCharSequence> chatLines(Minecraft mc) throws ReflectiveOperationException {
		List<GuiMessage.Line> lines = (List<GuiMessage.Line>) QaNames.field(ChatComponent.class, "trimmedMessages").get(chat(mc));
		return lines.stream().map(GuiMessage.Line::content).toList();
	}
}
