package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.flintclient.client.ui.screen.ChatSafetyScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Chat Reports' "Safe Server" icon in the server list on Minecraft 1.21.1, with its explanation on hover. 1.21.1 hands
 * the entry its row (top, left, width) and shows tooltips through the screen.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
abstract class NcrOnlineServerEntryMixin {
	@Unique
	private static final ResourceLocation VERIFIED = ResourceLocation.fromNamespaceAndPath("flintclient", "no_chat_reports/verified_server");
	@Shadow
	@Final
	private JoinMultiplayerScreen screen;
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	@Final
	private ServerData serverData;

	@Inject(method = "render", at = @At("RETURN"))
	private void flint$verified(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered,
		float partialTick, CallbackInfo callback) {
		if (!Mc263ChatSafety.with(NoChatReports.VERIFIED_ICON) || serverData.ping < 0L
			|| !(serverData instanceof Mc263ChatSafety.ServerFlag flag && flag.flint$preventsReports())) {
			return;
		}
		int xOffset = 16 + Mc263ChatSafety.integer(NoChatReports.ICON_X);
		int yOffset = 12 + Mc263ChatSafety.integer(NoChatReports.ICON_Y);
		graphics.blitSprite(VERIFIED, left + width - 35 + xOffset, top - 1 + yOffset, 14, 14);
		int t = mouseX - left;
		int u = mouseY - top;
		if (t >= width - 35 + xOffset && t <= width - 22 + xOffset && u >= yOffset && u <= 11 + yOffset) {
			screen.setTooltipForNextRenderPass(ChatSafetyScreens.verifiedTooltip(minecraft.font));
		}
	}
}
