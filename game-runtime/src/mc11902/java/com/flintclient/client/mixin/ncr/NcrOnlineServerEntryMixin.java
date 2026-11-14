package com.flintclient.client.mixin.ncr;

import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.flintclient.client.ui.screen.ChatSafetyScreens;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
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
 * No Chat Reports' "Safe Server" icon in the server list on Minecraft 1.19.2, with its explanation on hover. 1.19.2 hands the
 * entry its row (top, left, width) and a PoseStack; it has no GUI sprites (1.20.2), so the icon is drawn from its own 14 x 14
 * texture, and the tooltip waits until the screen has drawn (compat.DeferredTooltip), as on the later versions.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
abstract class NcrOnlineServerEntryMixin {
	@Unique
	private static final ResourceLocation VERIFIED = new ResourceLocation("flintclient", "textures/gui/sprites/no_chat_reports/verified_server.png");
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	@Final
	private ServerData serverData;

	@Inject(method = "render", at = @At("RETURN"))
	private void flint$verified(PoseStack pose, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered,
		float partialTick, CallbackInfo callback) {
		if (!Mc263ChatSafety.with(NoChatReports.VERIFIED_ICON) || serverData.ping < 0L
			|| !(serverData instanceof Mc263ChatSafety.ServerFlag flag && flag.flint$preventsReports())) {
			return;
		}
		int xOffset = 16 + Mc263ChatSafety.integer(NoChatReports.ICON_X);
		int yOffset = 12 + Mc263ChatSafety.integer(NoChatReports.ICON_Y);
		RenderSystem.setShaderTexture(0, VERIFIED);
		RenderSystem.enableBlend();
		GuiComponent.blit(pose, left + width - 35 + xOffset, top - 1 + yOffset, 0.0F, 0.0F, 14, 14, 14, 14);
		RenderSystem.disableBlend();
		int t = mouseX - left;
		int u = mouseY - top;
		if (t >= width - 35 + xOffset && t <= width - 22 + xOffset && u >= yOffset && u <= 11 + yOffset) {
			GuiGraphics.of(pose).setTooltipForNextFrame(minecraft.font, ChatSafetyScreens.verifiedTooltip(minecraft.font), mouseX, mouseY);
		}
	}
}
