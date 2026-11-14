package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.compat.input.CharacterEvent;
import com.flintclient.client.compat.input.Events;
import com.flintclient.client.compat.input.KeyEvent;
import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.client.control.platform.Mc263ChatScreen;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chat's screen on Minecraft 1.19.2: its tabs and search box, the input bar sliding up into place, keys and clicks for them,
 * and messages Flint answers itself; the HUD's own buttons (Music Player's) under the pointer. 1.19.2 hands input over as raw
 * values, draws the screen in render (with the chat preview in place of the command suggestions while one shows), keeps a
 * widget's place in public fields, and answers whether a sent message closes the screen.
 */
@Mixin(ChatScreen.class)
abstract class ChatScreenMixin extends Screen {
	@Shadow
	protected EditBox input;
	@Unique
	private long flint$openedAt;
	@Unique
	private int flint$inputY;
	@Unique
	private int flint$slide;
	/** Whether Chat's search box has the keys (Flint took them from the input box: it is unfocused after Flint's key or click). */
	@Unique
	private boolean flint$searching;

	protected ChatScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void flint$opened(CallbackInfo callback) {
		if (flint$openedAt == 0) {
			flint$openedAt = net.minecraft.Util.getMillis();
		}
		flint$inputY = input.y;
		flint$searching = false;
		Mc263ChatScreen.opened(input, width, height);
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void flint$closed(CallbackInfo callback) {
		Mc263ChatScreen.closed();
	}

	@Inject(method = "onEdited", at = @At("TAIL"))
	private void flint$edited(String value, CallbackInfo callback) {
		Mc263ChatScreen.edited(input, value);
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void flint$key(int key, int scancode, int modifiers, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263ChatScreen.key(input, new KeyEvent(key, scancode, modifiers))) {
			flint$searching = !input.isFocused();
			callback.setReturnValue(true);
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void flint$click(double x, double y, int button, CallbackInfoReturnable<Boolean> callback) {
		// The HUD's own buttons first (Music Player's), then Chat's tabs, search and copying.
		if (HudRenderer.chatClick(x, y, button)) {
			callback.setReturnValue(true);
			return;
		}
		boolean used = Mc263ChatScreen.click(input, Events.mouse(x, y, button));
		flint$searching = !input.isFocused();
		if (used) {
			callback.setReturnValue(true);
		}
	}

	@Override
	public boolean charTyped(char c, int modifiers) {
		return Mc263ChatScreen.typed(new CharacterEvent(c, modifiers)) || super.charTyped(c, modifiers);
	}

	/** A message Flint answers itself is used up: true, so the screen closes as after any sent message. */
	@Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
	private void flint$submit(String message, boolean addToHistory, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263ChatScreen.submit(message, addToHistory)) {
			callback.setReturnValue(true);
		}
	}

	/** The input bar slides up into place as chat opens: the box itself... */
	@Inject(method = "render", at = @At("HEAD"))
	private void flint$slide(PoseStack pose, int mouseX, int mouseY, float delta, CallbackInfo callback) {
		HudRenderer.chatPointer(mouseX, mouseY);
		flint$slide = Mc263ChatScreen.inputOffset(flint$openedAt);
		input.y = flint$inputY + flint$slide;
	}

	/**
	 * 1.19.2 gives the input box the keys again every frame (a click elsewhere takes them from it): not while Chat's search box
	 * has them, so only one of the two shows a cursor.
	 */
	@WrapWithCondition(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;setFocus(Z)V"))
	private boolean flint$inputKeys(EditBox box, boolean focused) {
		return !flint$searching || box != input;
	}

	/** ...and its background. */
	@WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/screens/ChatScreen;fill(Lcom/mojang/blaze3d/vertex/PoseStack;IIIII)V"))
	private void flint$inputBackground(PoseStack pose, int x0, int y0, int x1, int y1, int color, Operation<Void> original) {
		original.call(pose, x0, y0 + flint$slide, x1, y1 + flint$slide, color);
	}

	/** The tabs and search box, under the command suggestions (which open upwards over them)... */
	@Inject(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/CommandSuggestions;render(Lcom/mojang/blaze3d/vertex/PoseStack;II)V"))
	private void flint$tabs(PoseStack pose, int mouseX, int mouseY, float delta, CallbackInfo callback) {
		Mc263ChatScreen.render(GuiGraphics.of(pose), width, height, mouseX, mouseY, delta);
	}

	/** ...or under the suggestions over a chat preview (1.19.2's preview of a signed message, when the server asks for one). */
	@Inject(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/CommandSuggestions;renderSuggestions(Lcom/mojang/blaze3d/vertex/PoseStack;II)Z"))
	private void flint$tabsOverPreview(PoseStack pose, int mouseX, int mouseY, float delta, CallbackInfo callback) {
		Mc263ChatScreen.render(GuiGraphics.of(pose), width, height, mouseX, mouseY, delta);
	}
}
