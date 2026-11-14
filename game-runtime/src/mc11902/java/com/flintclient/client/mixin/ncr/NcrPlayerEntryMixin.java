package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.flintclient.client.ui.widget.WidgetVisibility;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.social.PlayerEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Chat Reports in Social Interactions: the report button hidden (Always hide report button), or inactive with a note on a
 * safe server. Minecraft 1.19.2 makes the button active again (and its tooltip anew) when it learns the player has recent
 * messages, so both are set after that too; its tooltip is the entry's own text, shown after the entry's own short delay.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(PlayerEntry.class)
abstract class NcrPlayerEntryMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	private Button reportButton;
	@Shadow
	List<FormattedCharSequence> reportTooltip;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void flint$report(CallbackInfo callback) {
		flint$hideReport();
	}

	@Inject(method = "setHasRecentMessages", at = @At("RETURN"))
	private void flint$reportAgain(boolean recent, CallbackInfo callback) {
		flint$hideReport();
	}

	@Unique
	private void flint$hideReport() {
		if (reportButton == null || !Mc263ChatSafety.on()) {
			return;
		}
		if (Mc263ChatSafety.bool(NoChatReports.ALWAYS_HIDE_REPORT)) {
			reportButton.active = false;
			WidgetVisibility.set(reportButton, false);
		} else if (Mc263ChatSafety.current().secure()) {
			reportButton.active = false;
			reportTooltip = minecraft.font.split(Component.translatable("flintclient.ncr.gui.no_reporting"), 150);
		}
	}
}
