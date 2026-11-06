package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Inventory;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
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
 * Inventory: the search box, highlights, lock and binding marks and slot decorations, the lock, bind and search keys, the
 * click guards and bound-slot swaps, and the open/close animations (closing waits for the animation; clicks follow the
 * moving container and are ignored while it leaves).
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

	@Inject(method = "extractSlots", at = @At("TAIL"))
	private void flint$slots(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo callback) {
		Mc263Inventory.decorateSlots(graphics, (AbstractContainerScreen<?>) (Object) this, hoveredSlot, imageWidth, imageHeight);
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void flint$key(KeyEvent event, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263Inventory.keyPressed(this, flint$search, hoveredSlot, event)) {
			callback.setReturnValue(true);
		}
	}

	@ModifyVariable(method = {"mouseClicked", "mouseReleased", "mouseDragged"}, at = @At("HEAD"), argsOnly = true)
	private MouseButtonEvent flint$movedPointer(MouseButtonEvent event) {
		double x = Mc263Inventory.pointer(this, event.x(), false);
		double y = Mc263Inventory.pointer(this, event.y(), true);
		return x == event.x() && y == event.y() ? event : new MouseButtonEvent(x, y, event.buttonInfo());
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void flint$click(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263Inventory.closing(this)) {
			callback.setReturnValue(true);
			return;
		}
		EditBox search = flint$search;
		if (search != null && search.isFocused() && !search.isMouseOver(event.x(), event.y())) {
			search.setFocused(false);
			setFocused(null);
		}
	}

	@Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V", at = @At("HEAD"),
		cancellable = true)
	private void flint$slotClicked(Slot slot, int slotId, int buttonNum, ContainerInput input, CallbackInfo callback) {
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
