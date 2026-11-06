package com.flintclient.client.ui.widget;

import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;

/**
 * A 20 by 20 button that is only a picture, as No Chat Reports draws its buttons: the sprite {@code base} normally and
 * {@code base_hovered} when hovered, focused or inactive (its sprite rules). The picture may change with state (the chat
 * safety status), so it is asked for each frame.
 */
public final class SpriteButton extends Button {
	private final Supplier<String> sprite;

	public SpriteButton(int x, int y, Supplier<String> sprite, Consumer<SpriteButton> onPress) {
		super(x, y, 20, 20, CommonComponents.EMPTY, button -> onPress.accept((SpriteButton) button), DEFAULT_NARRATION);
		this.sprite = sprite;
	}

	@Override
	public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		String base = sprite.get();
		String path = isActive() && !isHoveredOrFocused() ? base : base + "_hovered";
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("flintclient", "no_chat_reports/" + path), getX(), getY(),
			getWidth(), getHeight());
	}
}
