package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263ChatHeads;
import com.mojang.brigadier.suggestion.Suggestion;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

/** Chat Heads: a player name in the command suggestions shows that player's head beside it. */
@Mixin(targets = "net.minecraft.client.gui.components.CommandSuggestions$SuggestionsList")
abstract class ChatHeadsSuggestionsMixin {
	@Shadow
	@Final
	private Rect2i rect;

	@Shadow
	@Final
	private List<Suggestion> suggestionList;

	@Unique
	private @Nullable GuiGraphicsExtractor flint$graphics;

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

	@Inject(method = McCompat.SUGGESTIONS_DRAW, at = @At("HEAD"))
	private void flint$graphics(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo callback) {
		flint$graphics = graphics;
	}

	@ModifyVariable(method = McCompat.SUGGESTIONS_DRAW, at = @At("STORE"), ordinal = 0)
	private Suggestion flint$suggestion(Suggestion suggestion) {
		flint$player = Mc263ChatHeads.suggestionPlayer(suggestion.getText());
		return suggestion;
	}

	@ModifyArg(method = McCompat.SUGGESTIONS_DRAW, index = 0,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V", ordinal = 4))
	private int flint$background(int x) {
		return flint$player != null ? x - 11 : x;
	}

	@ModifyArg(method = McCompat.SUGGESTIONS_DRAW, index = 3,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V", ordinal = 0))
	private int flint$head(int y) {
		PlayerInfo player = flint$player;
		if (player != null && flint$graphics != null) {
			Mc263ChatHeads.drawHead(flint$graphics, rect.getX() - 9, y, player);
		}
		return y;
	}
}
