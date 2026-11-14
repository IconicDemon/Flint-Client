package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.control.platform.Mc263ChatHeads;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.suggestion.Suggestion;
import java.util.List;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.Rect2i;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat Heads: a player name in the command suggestions shows that player's head beside it. Minecraft 1.19.2 draws the list
 * with GuiComponent's fills and the font's shadowed text, on the PoseStack it is handed.
 */
@Mixin(targets = "net.minecraft.client.gui.components.CommandSuggestions$SuggestionsList")
abstract class ChatHeadsSuggestionsMixin {
	@Shadow
	@Final
	private Rect2i rect;

	@Shadow
	@Final
	private List<Suggestion> suggestionList;

	@Unique
	private @Nullable GuiGraphics flint$graphics;

	@Unique
	private @Nullable PlayerInfo flint$player;

	/** Room for the heads when the list starts at the screen's left edge. */
	@Inject(method = "<init>", at = @At("RETURN"))
	private void flint$room(CallbackInfo callback) {
		if (rect.getX() - 11 < 3) {
			for (Suggestion suggestion : suggestionList) {
				if (Mc263ChatHeads.suggestionPlayer(suggestion.getText()) != null) {
					rect.setPosition(14, rect.getY());
					break;
				}
			}
		}
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void flint$graphics(PoseStack pose, int mouseX, int mouseY, CallbackInfo callback) {
		flint$graphics = GuiGraphics.of(pose);
	}

	@ModifyVariable(method = "render", at = @At("STORE"), ordinal = 0)
	private Suggestion flint$suggestion(Suggestion suggestion) {
		flint$player = Mc263ChatHeads.suggestionPlayer(suggestion.getText());
		return suggestion;
	}

	/** Each row's background (the fifth fill: after the list's two edges and their two dotted scroll marks), widened for the head. */
	@ModifyArg(method = "render", index = 1,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiComponent;fill(Lcom/mojang/blaze3d/vertex/PoseStack;IIIII)V", ordinal = 4))
	private int flint$background(int x) {
		return flint$player != null ? x - 11 : x;
	}

	@ModifyArg(method = "render", index = 3,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/lang/String;FFI)I",
			ordinal = 0))
	private float flint$head(float y) {
		PlayerInfo player = flint$player;
		if (player != null && flint$graphics != null) {
			Mc263ChatHeads.drawHead(flint$graphics, rect.getX() - 9, (int) y, player);
		}
		return y;
	}
}
