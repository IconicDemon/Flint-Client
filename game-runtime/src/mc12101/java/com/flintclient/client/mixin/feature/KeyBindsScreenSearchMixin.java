package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.keys.KeyBindsSearchScreen;
import com.flintclient.client.control.model.mods.ToolMods;
import com.flintclient.client.control.feature.Mods;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keybind Search's button on Minecraft 1.21.1's controls screen (input as raw values there). */
@Mixin(KeyBindsScreen.class)
abstract class KeyBindsScreenSearchMixin extends Screen {
	@Unique
	private @Nullable Button flint$searchButton;

	protected KeyBindsScreenSearchMixin(Component title) {
		super(title);
	}

	@Inject(method = "addContents", at = @At("TAIL"))
	private void flint$search(CallbackInfo callback) {
		flint$searchButton = null;
		if (!Mods.on(Mods.index(ToolMods.KeybindSearch.ID))) {
			return;
		}
		Screen self = this;
		flint$searchButton = Button.builder(Component.literal("Search Keybinds"), button -> minecraft.setScreen(new KeyBindsSearchScreen(self)))
			.bounds(width - 140 - 12, 32, 140, 20)
			.tooltip(Tooltip.create(Component.literal("Open a searchable keybind list (supports mod:filter)"))).build();
	}

	/** The button sits over the key list's top corner: it takes its clicks first (the list would otherwise swallow them). */
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void flint$clickSearch(double x, double y, int buttonNumber, CallbackInfoReturnable<Boolean> callback) {
		Button button = flint$searchButton;
		if (button != null && button.visible && button.isMouseOver(x, y) && button.mouseClicked(x, y, buttonNumber)) {
			callback.setReturnValue(true);
		}
	}

	/** Init ends here, after the key list joined the screen: adding the button now draws it above the list. */
	@Inject(method = "repositionElements", at = @At("TAIL"))
	private void flint$placeSearch(CallbackInfo callback) {
		if (flint$searchButton != null) {
			flint$searchButton.setX(width - 140 - 12);
			if (!children().contains(flint$searchButton)) {
				addRenderableWidget(flint$searchButton);
			}
		}
	}
}
