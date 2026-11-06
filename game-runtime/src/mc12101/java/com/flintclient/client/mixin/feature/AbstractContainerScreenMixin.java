package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.input.KeyEvent;
import com.flintclient.client.control.platform.Mc263Inventory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Inventory on Minecraft 1.21.1: the search box, highlights, lock and binding marks and slot decorations, the lock, bind and
 * search keys, the click guards and bound-slot swaps, and the open/close animations. 1.21.1 draws the slots inside render
 * (the decorations follow them, before the labels, in the container's own coordinates) and hands input over as raw values.
 */
@Mixin(AbstractContainerScreen.class)
abstract class AbstractContainerScreenMixin extends Screen {
	@Shadow
	protected int leftPos;
	@Shadow
	protected int topPos;
	@Shadow
	protected int imageWidth;
	@Shadow
	protected int imageHeight;
	@Shadow
	protected @Nullable Slot hoveredSlot;
	@Unique
	private @Nullable EditBox flint$search;

	private AbstractContainerScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void flint$init(CallbackInfo callback) {
		Mc263Inventory.opened((AbstractContainerScreen<?>) (Object) this);
		flint$search = Mc263Inventory.searchBox((AbstractContainerScreen<?>) (Object) this, leftPos, topPos, imageWidth, imageHeight);
		if (flint$search != null) {
			addRenderableWidget(flint$search);
		}
	}

	@Inject(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderLabels(Lnet/minecraft/client/gui/GuiGraphics;II)V"))
	private void flint$slots(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		Mc263Inventory.decorateSlots(graphics, (AbstractContainerScreen<?>) (Object) this, hoveredSlot, imageWidth, imageHeight);
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void flint$key(int key, int scancode, int modifiers, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263Inventory.keyPressed(this, flint$search, hoveredSlot, new KeyEvent(key, scancode, modifiers))) {
			callback.setReturnValue(true);
		}
	}

	@ModifyVariable(method = {"mouseClicked", "mouseReleased", "mouseDragged"}, at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private double flint$movedPointerX(double x) {
		return Mc263Inventory.pointer(this, x, false);
	}

	@ModifyVariable(method = {"mouseClicked", "mouseReleased", "mouseDragged"}, at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private double flint$movedPointerY(double y) {
		return Mc263Inventory.pointer(this, y, true);
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void flint$click(double x, double y, int button, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263Inventory.closing(this)) {
			callback.setReturnValue(true);
			return;
		}
		EditBox search = flint$search;
		if (search != null && search.isFocused() && !search.isMouseOver(x, y)) {
			search.setFocused(false);
			setFocused(null);
		}
	}

	@Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ClickType;)V", at = @At("HEAD"),
		cancellable = true)
	private void flint$slotClicked(Slot slot, int slotId, int buttonNum, ClickType input, CallbackInfo callback) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		if (Mc263Inventory.blockClick(screen, slot, slotId, buttonNum, input) || Mc263Inventory.bindingClick(screen, slot, input)) {
			callback.cancel();
		}
	}

	@Inject(method = "onClose", at = @At("HEAD"), cancellable = true)
	private void flint$close(CallbackInfo callback) {
		if (Mc263Inventory.deferClose((AbstractContainerScreen<?>) (Object) this)) {
			callback.cancel();
		}
	}
}
