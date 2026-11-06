package com.flintclient.client.compat;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 1.21.11's {@code GuiGraphics} is 26.x's {@code GuiGraphicsExtractor} under its older method names (drawString
 * for text, renderItem for item…). The shared sources, written against 26.x, call the new names: Loom adds this interface
 * to GuiGraphics at compile time ({@code src/mc12111/loom/fabric.mod.json}) and {@code mixin.GraphicsBridgeMixin} at run
 * time, and each default here calls the 1.21.11 method it was renamed from. (The type name itself is renamed by the
 * line's shared-sources.rules.)
 */
public interface GraphicsBridge {
	private GuiGraphics self() {
		return (GuiGraphics) (Object) this;
	}

	default void horizontalLine(int x0, int x1, int y, int color) {
		self().hLine(x0, x1, y, color);
	}

	default void verticalLine(int x, int y0, int y1, int color) {
		self().vLine(x, y0, y1, color);
	}

	default void outline(int x, int y, int width, int height, int color) {
		self().renderOutline(x, y, width, height, color);
	}

	default void text(Font font, @Nullable String text, int x, int y, int color) {
		self().drawString(font, text, x, y, color);
	}

	default void text(Font font, @Nullable String text, int x, int y, int color, boolean shadow) {
		self().drawString(font, text, x, y, color, shadow);
	}

	default void text(Font font, FormattedCharSequence text, int x, int y, int color) {
		self().drawString(font, text, x, y, color);
	}

	default void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
		self().drawString(font, text, x, y, color, shadow);
	}

	default void text(Font font, Component text, int x, int y, int color) {
		self().drawString(font, text, x, y, color);
	}

	default void text(Font font, Component text, int x, int y, int color, boolean shadow) {
		self().drawString(font, text, x, y, color, shadow);
	}

	default void centeredText(Font font, String text, int x, int y, int color) {
		self().drawCenteredString(font, text, x, y, color);
	}

	default void centeredText(Font font, Component text, int x, int y, int color) {
		self().drawCenteredString(font, text, x, y, color);
	}

	default void centeredText(Font font, FormattedCharSequence text, int x, int y, int color) {
		self().drawCenteredString(font, text, x, y, color);
	}

	default void textWithWordWrap(Font font, FormattedText text, int x, int y, int width, int color) {
		self().drawWordWrap(font, text, x, y, width, color);
	}

	default void textWithWordWrap(Font font, FormattedText text, int x, int y, int width, int color, boolean shadow) {
		self().drawWordWrap(font, text, x, y, width, color, shadow);
	}

	default void textWithBackdrop(Font font, Component text, int x, int y, int width, int color) {
		self().drawStringWithBackdrop(font, text, x, y, width, color);
	}

	default void item(ItemStack stack, int x, int y) {
		self().renderItem(stack, x, y);
	}

	default void item(ItemStack stack, int x, int y, int seed) {
		self().renderItem(stack, x, y, seed);
	}

	default void item(LivingEntity entity, ItemStack stack, int x, int y, int seed) {
		self().renderItem(entity, stack, x, y, seed);
	}

	default void fakeItem(ItemStack stack, int x, int y) {
		self().renderFakeItem(stack, x, y);
	}

	default void fakeItem(ItemStack stack, int x, int y, int seed) {
		self().renderFakeItem(stack, x, y, seed);
	}

	default void itemDecorations(Font font, ItemStack stack, int x, int y) {
		self().renderItemDecorations(font, stack, x, y);
	}

	default void itemDecorations(Font font, ItemStack stack, int x, int y, @Nullable String count) {
		self().renderItemDecorations(font, stack, x, y, count);
	}

	default void tooltip(Font font, List<ClientTooltipComponent> lines, int x, int y, ClientTooltipPositioner positioner,
		@Nullable Identifier style) {
		self().renderTooltip(font, lines, x, y, positioner, style);
	}
}
