package com.flintclient.client.control.platform;

import com.flintclient.client.mixin.control.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * Minecraft 26.3's side of the Control Center platform: reads of state Minecraft already keeps, Flint's
 * key bindings and sounds, plus helpers that need Minecraft's own types. The state reads are field reads
 * and allocate nothing, so per-frame code can use them; the key names build text and belong in caches.
 * Render thread only.
 */
public final class Mc263ControlPlatform implements ControlPlatform {
	private static @Nullable ByteBuffer keyboardState;

	private final Supplier<@Nullable ControlKeys> keys;

	/**
	 * {@code keys} answers Flint's key bindings once they exist: null before options load, and for the whole
	 * session if they couldn't be created. Asking {@link ControlKeys} itself after its static initialiser has
	 * failed would throw an {@code Error} that no hook guard catches.
	 */
	public Mc263ControlPlatform(Supplier<@Nullable ControlKeys> keys) {
		this.keys = Objects.requireNonNull(keys, "keys");
	}

	@Override
	public boolean gameplayHasInput() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level != null && minecraft.gui.screen() == null && minecraft.gui.overlay() == null;
	}

	@Override
	public boolean inWorld() {
		return Minecraft.getInstance().level != null;
	}

	@Override
	public boolean hudHidden() {
		return Minecraft.getInstance().gui.hud.isHidden();
	}

	@Override
	public boolean debugScreenShown() {
		return Minecraft.getInstance().getDebugOverlay().showDebugScreen();
	}

	@Override
	public String controlCenterKeyName() {
		ControlKeys mappings = keys.get();
		return mappings == null ? notBound() : keyName(mappings.controlCenter()).getString();
	}

	@Override
	public String hudEditorKeyName() {
		ControlKeys mappings = keys.get();
		return mappings == null ? notBound() : keyName(mappings.hudEditor()).getString();
	}

	@Override
	public void playOpenSound(float volume) {
		UiSounds.playOpen(volume);
	}

	@Override
	public void playCloseSound(float volume) {
		UiSounds.playClose(volume);
	}

	@Override
	public boolean narratorOn() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.options.narrator().get().shouldNarrateSystem() && minecraft.getNarrator().isActive();
	}

	@Override
	public void narrate(String text) {
		Minecraft.getInstance().getNarrator().saySystemNow(text);
	}

	// Without Flint's key bindings no key opens anything, so Minecraft's own word for that is the honest name.
	private static String notBound() {
		return InputConstants.UNKNOWN.getDisplayName().getString();
	}

	/** The screen showing now, or null in game. */
	public static @Nullable Screen currentScreen() {
		return Minecraft.getInstance().gui.screen();
	}

	/** The key a mapping is bound to now (its default until the player rebinds it). */
	public static InputConstants.Key boundKey(KeyMapping mapping) {
		return ((KeyMappingAccessor) mapping).flint$boundKey();
	}

	/**
	 * The names (as Controls shows them) of Minecraft's key mappings, and those added by mods, bound to {@code keyName}
	 * ({@code key.keyboard.c}); for a key setting's conflict warning. Runs when a key changes, not per frame.
	 */
	public static java.util.List<String> mappingsUsing(String keyName) {
		java.util.List<String> out = new java.util.ArrayList<>();
		if (keyName.isEmpty()) {
			return out;
		}
		for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
			if (boundKey(mapping).getName().equals(keyName)) {
				out.add(Component.translatable(mapping.getName()).getString());
			}
		}
		return out;
	}

	/** A mapping's key as Minecraft names it in Controls: a single character ("W") or a name ("Right Shift"). */
	public static Component keyName(KeyMapping mapping) {
		return mapping.getTranslatedKeyMessage();
	}

	/**
	 * Whether the key a mapping is bound to is physically held. This differs from {@link KeyMapping#isDown()}
	 * for toggle mappings (toggle sneak, sprint, attack and use report the toggle there) and while a screen is
	 * open (Minecraft releases every mapping then). Mouse buttons other than left, middle and right have no
	 * separate state and fall back to the mapping's. Allocates nothing.
	 */
	public static boolean physicallyDown(KeyMapping mapping) {
		InputConstants.Key key = boundKey(mapping);
		if (key.getType() == InputConstants.Type.KEYBOARD) {
			return key.getValue() != InputConstants.UNKNOWN.getValue() && keyDown(key.getValue());
		}
		MouseHandler mouse = Minecraft.getInstance().mouseHandler;
		return switch (key.getValue()) {
			case InputConstants.MOUSE_BUTTON_LEFT -> mouse.isLeftPressed();
			case InputConstants.MOUSE_BUTTON_RIGHT -> mouse.isRightPressed();
			case InputConstants.MOUSE_BUTTON_MIDDLE -> mouse.isMiddlePressed();
			default -> mapping.isDown();
		};
	}

	/**
	 * Whether the keyboard key with this SDL scancode is held, as {@code InputConstants.isKeyDown} answers.
	 * That method asks SDL for its key state array on every call, and LWJGL wraps the answer in new buffer
	 * objects each time; SDL's array is a fixed internal one that lives as long as the program, so this asks
	 * once and keeps the view, and per-frame reads allocate nothing.
	 */
	public static boolean keyDown(int scancode) {
		ByteBuffer state = keyboardState;
		if (state == null) {
			state = SDLKeyboard.SDL_GetKeyboardState();
			if (state == null) {
				return false;
			}
			keyboardState = state;
		}
		return scancode >= 0 && scancode < state.capacity() && state.get(scancode) != 0;
	}

	/**
	 * Binds {@code mapping} to {@code key} the way Minecraft's Controls screen does: set the key, rebuild
	 * Minecraft's key lookup, and save options.txt (vanilla saves when the Controls screen closes).
	 */
	public static void rebind(KeyMapping mapping, InputConstants.Key key) {
		mapping.setKey(key);
		KeyMapping.resetMapping();
		Minecraft.getInstance().options.save();
	}

	/** {@link #rebind} back to the mapping's default key. */
	public static void resetBinding(KeyMapping mapping) {
		rebind(mapping, mapping.getDefaultKey());
	}

	/**
	 * Tells Minecraft a text field gained or lost focus, which starts or stops the system's text input (for
	 * typed characters and input methods), exactly as vanilla's own text field does.
	 */
	public static void textInputFocus(GuiEventListener field, boolean focused) {
		Minecraft.getInstance().onTextInputFocusChange(field, focused);
	}
}
