package com.flintclient.client.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Methods later Minecraft versions added to its classes that the shared sources call, answered on Minecraft 1.19.2 (the 1.21.1
 * overlay's, with what 1.19.2 also lacks). Loom adds each interface to its class at compile time
 * ({@code src/mc11902/loom/fabric.mod.json}) and the matching mixin in {@code mixin.bridge} at run time. (Gui has its own,
 * GuiBridge; GuiGraphics is Flint's own class on 1.19.2.)
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

	/** Minecraft: 26.1's system text input (IME) hooks, which 1.21.1 doesn't have (GLFW types characters on its own), and resizeGui. */
	public interface Game {
		default void onTextInputFocusChange(GuiEventListener field, boolean focused) {
		}

		default TextInput textInputManager() {
			return TextInput.NONE;
		}

		default void resizeGui() {
			((Minecraft) (Object) this).resizeDisplay();
		}

		/** The last kind of input (1.20 tracks it; on 1.19.2 Flint does, {@link InputType}). */
		default InputType getLastInputType() {
			return InputType.last();
		}

		/** 1.21's frame timing (1.19.2 keeps a float partial tick: {@link DeltaTracker} reads it). */
		default DeltaTracker getTimer() {
			return DeltaTracker.GAME;
		}
	}

	/** Where a text field wants the IME's candidate window: nowhere on 1.21.1. */
	public static final class TextInput {
		static final TextInput NONE = new TextInput();

		private TextInput() {
		}

		public void setTextInputArea(int x0, int y0, int x1, int y1) {
		}
	}

	/** ClientLevel: the overworld clock is the level's day time on 1.21.1. */
	public interface Level {
		default long getOverworldClockTime() {
			return ((ClientLevel) (Object) this).getDayTime();
		}
	}

	/** Camera: the frustum this frame's world was culled with (kept by mixin.bridge.CullFrustumMixin as LevelRenderer sets it up). */
	public interface Camera {
		default Frustum getCullFrustum() {
			Frustum frustum = cullFrustum;
			return frustum != null ? frustum : ALL;
		}
	}

	/**
	 * GuiMessage.Line: the message a chat line belongs to (26.x keeps it on the line; 1.21.1's chat records it here as it
	 * makes the line, mixin.feature.ChatComponentMixin).
	 */
	public interface ChatLine {
		default net.minecraft.client.@Nullable GuiMessage parent() {
			return CHAT_LINES.get(this);
		}
	}

	/** Inventory: 1.21.5's getters for what 1.21.1 keeps in public fields. */
	public interface PlayerInventory {
		default int getSelectedSlot() {
			return ((net.minecraft.world.entity.player.Inventory) (Object) this).selected;
		}

		/** The 36 main slots (hotbar first), as 1.21.5 names them. */
		default net.minecraft.core.NonNullList<ItemStack> getNonEquipmentItems() {
			return ((net.minecraft.world.entity.player.Inventory) (Object) this).items;
		}
	}

	/** Window: the GUI scale as the whole number it always is (1.21.1 keeps it as a double; the rules call getGuiScale this). */
	public interface WindowScale {
		default int guiScaleInt() {
			return (int) ((com.mojang.blaze3d.platform.Window) (Object) this).getGuiScale();
		}
	}

	/** Style: a font given as a {@link FontDescription} (1.21.1 names fonts by id). */
	public interface StyleFont {
		default Style withFont(FontDescription font) {
			return ((Style) (Object) this).withFont(font.id());
		}
	}

	/**
	 * NativeImage: 1.21.2's pixel access in 0xAARRGGBB (1.19.2 reads and writes 0xAABBGGRR), and the address of its pixels
	 * ({@code mixin.bridge.NativeImageBridgeMixin} reads the field).
	 */
	public interface Image {
		private NativeImage self() {
			return (NativeImage) (Object) this;
		}

		default int getPixel(int x, int y) {
			return ARGB.fromABGR(self().getPixelRGBA(x, y));
		}

		default void setPixel(int x, int y, int argb) {
			self().setPixelRGBA(x, y, ARGB.toABGR(argb));
		}

		/** Every pixel, row by row, in 0xAARRGGBB. */
		default int[] getPixels() {
			// 1.19.2's makePixelArray answers 0xAARRGGBB already.
			return self().makePixelArray();
		}

		default long getPointer() {
			throw new IllegalStateException("NativeImageBridgeMixin isn't applied");
		}
	}

	private static final java.util.Map<Object, net.minecraft.client.GuiMessage> CHAT_LINES = new com.google.common.collect.MapMaker().weakKeys()
		.makeMap();

	public static void chatLine(net.minecraft.client.GuiMessage.Line line, net.minecraft.client.GuiMessage message) {
		CHAT_LINES.put(line, message);
	}

	/** The last frame's cull frustum, before the first frame a frustum that sees everything. */
	public static @Nullable Frustum cullFrustum;
	private static final Frustum ALL = new Frustum(new com.mojang.math.Matrix4f(), new com.mojang.math.Matrix4f()) {
		@Override
		public boolean isVisible(net.minecraft.world.phys.AABB box) {
			return true;
		}
	};
}
