package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.keys.KeyBindsSearchScreen;
import com.flintclient.client.control.model.mods.ToolMods;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keybind Search's button on Minecraft 1.19.2's controls screen (input as raw values there). 1.19.2 lays the screen out in
 * init alone (again on a resize) and draws the key list before its widgets, so the button added there draws above the list;
 * its buttons take their tooltip as a callback drawn while they are hovered.
 */
@Mixin(KeyBindsScreen.class)
abstract class KeyBindsScreenSearchMixin extends Screen {
	@Unique
	private @Nullable Button flint$searchButton;

	protected KeyBindsScreenSearchMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void flint$search(CallbackInfo callback) {
		flint$searchButton = null;
		if (!Mods.on(Mods.index(ToolMods.KeybindSearch.ID))) {
			return;
		}
		Screen self = this;
		Component tip = Component.literal("Open a searchable keybind list (supports mod:filter)");
		flint$searchButton = addRenderableWidget(new Button(width - 140 - 12, 32, 140, 20, Component.literal("Search Keybinds"),
			button -> minecraft.setScreen(new KeyBindsSearchScreen(self)),
			(button, pose, mouseX, mouseY) -> self.renderTooltip(pose, tip, mouseX, mouseY)));
	}

	/** The button sits over the key list's top corner: it takes its clicks first (the list would otherwise swallow them). */
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void flint$clickSearch(double x, double y, int buttonNumber, CallbackInfoReturnable<Boolean> callback) {
		Button button = flint$searchButton;
		if (button != null && button.visible && button.isMouseOver(x, y) && button.mouseClicked(x, y, buttonNumber)) {
			callback.setReturnValue(true);
		}
	}
}
