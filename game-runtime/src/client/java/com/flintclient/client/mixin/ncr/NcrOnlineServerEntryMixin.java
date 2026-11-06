package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.flintclient.client.ui.screen.ChatSafetyScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Chat Reports' "Safe Server" icon in the server list, with its explanation on hover.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
abstract class NcrOnlineServerEntryMixin extends ServerSelectionList.Entry {
	@Unique
	private static final Identifier VERIFIED = Identifier.fromNamespaceAndPath("flintclient", "no_chat_reports/verified_server");
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	@Final
	private ServerData serverData;

	@Inject(method = "extractContent", at = @At("RETURN"))
	private void flint$verified(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a, CallbackInfo callback) {
		if (!Mc263ChatSafety.with(NoChatReports.VERIFIED_ICON) || serverData.ping < 0L
			|| !(serverData instanceof Mc263ChatSafety.ServerFlag flag && flag.flint$preventsReports())) {
			return;
		}
		int xOffset = 16 + Mc263ChatSafety.integer(NoChatReports.ICON_X);
		int yOffset = 12 + Mc263ChatSafety.integer(NoChatReports.ICON_Y);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, VERIFIED, getContentRight() - 35 + xOffset, getContentY() - 1 + yOffset, 14, 14);
		int t = mouseX - getContentX();
		int u = mouseY - getContentY();
		if (t >= getContentWidth() - 35 + xOffset && t <= getContentWidth() - 22 + xOffset && u >= yOffset && u <= 11 + yOffset) {
			graphics.setTooltipForNextFrame(ChatSafetyScreens.verifiedTooltip(minecraft.font), mouseX, mouseY);
		}
	}
}
