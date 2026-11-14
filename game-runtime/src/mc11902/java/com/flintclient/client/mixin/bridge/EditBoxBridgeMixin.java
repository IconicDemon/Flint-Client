package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.EditBoxBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 1.19.2: an EditBox's hint, shown as its suggestion while it is empty ({@link EditBoxBridge}). */
@Mixin(EditBox.class)
abstract class EditBoxBridgeMixin implements EditBoxBridge {
	@Unique
	private @Nullable String flint$hint;

	@Shadow
	public abstract String getValue();

	@Shadow
	public abstract void setSuggestion(@Nullable String suggestion);

	@Override
	public void setHint(@Nullable Component hint) {
		flint$hint = hint == null ? null : hint.getString();
	}

	@Inject(method = "renderButton", at = @At("HEAD"))
	private void flint$hint(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		if (flint$hint != null) {
			setSuggestion(getValue().isEmpty() ? flint$hint : null);
		}
	}
}
