package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.flintclient.client.ui.widget.WidgetVisibility;
import java.time.Duration;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.social.PlayerEntry;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Chat Reports in Social Interactions: the report button hidden (Always hide report button), or inactive with a note on a
 * server that's safe. Flint changes the button itself rather than putting another in its place, so a click can't reach the
 * report screen through the old one.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(PlayerEntry.class)
abstract class NcrPlayerEntryMixin {
	@Shadow
	private Button reportButton;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void flint$report(CallbackInfo callback) {
		if (reportButton == null || !Mc263ChatSafety.on()) {
			return;
		}
		if (Mc263ChatSafety.bool(NoChatReports.ALWAYS_HIDE_REPORT)) {
			reportButton.active = false;
			WidgetVisibility.set(reportButton, false);
		} else if (Mc263ChatSafety.current().secure()) {
			reportButton.active = false;
			reportButton.setTooltip(Tooltip.create(Component.translatable("flintclient.ncr.gui.no_reporting")));
			reportButton.setTooltipDelay(Duration.ofMillis(500L));
		}
	}
}
