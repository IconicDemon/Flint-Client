package com.flintclient.client.compat;

import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jspecify.annotations.Nullable;

/**
 * Methods 26.x added to Minecraft's classes that the shared sources call, answered on Minecraft 1.21.11 the way 26.x does.
 * Loom adds each interface to its class at compile time ({@code src/mc12111/loom/fabric.mod.json}) and the matching mixin in
 * {@code mixin.bridge} at run time. (GuiGraphics and Gui have their own, GraphicsBridge and GuiBridge.)
 */
public final class Bridges {
	private Bridges() {
	}

	/** ChatComponent: 26.x's name for adding a message from the client itself. */
	public interface Chat {
		default void addClientSystemMessage(Component message) {
			((ChatComponent) (Object) this).addMessage(message);
		}
	}

	/** Minecraft: 26.1's system text input (IME) hooks, which 1.21.11 doesn't have (GLFW types characters on its own), and resizeGui. */
	public interface Game {
		default void onTextInputFocusChange(GuiEventListener field, boolean focused) {
		}

		default TextInput textInputManager() {
			return TextInput.NONE;
		}

		default void resizeGui() {
			((Minecraft) (Object) this).resizeDisplay();
		}
	}

	/** Where a text field wants the IME's candidate window: nowhere on 1.21.11. */
	public static final class TextInput {
		static final TextInput NONE = new TextInput();

		private TextInput() {
		}

		public void setTextInputArea(int x0, int y0, int x1, int y1) {
		}
	}

	/** ClientLevel: the overworld clock is the level's day time on 1.21.11. */
	public interface Level {
		default long getOverworldClockTime() {
			return ((ClientLevel) (Object) this).getDayTime();
		}
	}

	/** Camera: the frustum this frame's world was culled with (LevelRenderer keeps it only while it draws; mixin.bridge.CullFrustumMixin keeps it). */
	public interface Camera {
		default Frustum getCullFrustum() {
			Frustum frustum = cullFrustum;
			return frustum != null ? frustum : ALL;
		}
	}

	/** ItemContainerContents: 26.x's copying streams. */
	public interface ContainerContents {
		default Stream<ItemStack> nonEmptyItemCopyStream() {
			return ((ItemContainerContents) (Object) this).nonEmptyStream().map(ItemStack::copy);
		}

		default Stream<ItemStack> allItemsCopyStream() {
			return ((ItemContainerContents) (Object) this).stream().map(ItemStack::copy);
		}
	}

	/**
	 * GuiMessage.Line: the message a chat line belongs to (26.x keeps it on the line; 1.21.11's chat records it here as it
	 * makes the line, mixin.feature.ChatComponentMixin).
	 */
	public interface ChatLine {
		default net.minecraft.client.@Nullable GuiMessage parent() {
			return CHAT_LINES.get(this);
		}
	}

	private static final java.util.Map<Object, net.minecraft.client.GuiMessage> CHAT_LINES = new com.google.common.collect.MapMaker().weakKeys()
		.makeMap();

	public static void chatLine(net.minecraft.client.GuiMessage.Line line, net.minecraft.client.GuiMessage message) {
		CHAT_LINES.put(line, message);
	}

	/** The last frame's cull frustum, before the first frame a frustum that sees everything. */
	public static @Nullable Frustum cullFrustum;
	private static final Frustum ALL = new Frustum(new org.joml.Matrix4f(), new org.joml.Matrix4f()) {
		@Override
		public boolean isVisible(net.minecraft.world.phys.AABB box) {
			return true;
		}
	};
}
