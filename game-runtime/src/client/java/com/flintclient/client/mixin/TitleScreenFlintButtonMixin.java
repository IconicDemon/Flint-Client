package com.flintclient.client.mixin;

import com.flintclient.client.FlintClient;
import com.flintclient.client.ui.screen.FlintTitleScreen;
import com.flintclient.client.ui.screen.MenuBlend;
import com.flintclient.client.ui.screen.VanillaTitleExtras;
import com.flintclient.client.ui.widget.MenuScaled;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's own title screen, when the player chose it over Flint's main menu (the menu switch, or General › Vanilla Main
 * Menu): Flint's menu switch in its top-left corner, to come back, and the "What's new" and "Daily rewards" cards at the
 * bottom right ({@link VanillaTitleExtras}), each exactly as on Flint's menu; and while the player changes menus, Flint's
 * menu dissolving over it ({@link MenuBlend}). Only the exact vanilla class gets them (Flint's own main menu extends it but
 * lays itself out), and only while Flint's menu can run on this Minecraft.
 */
@Mixin(TitleScreen.class)
abstract class TitleScreenFlintButtonMixin extends Screen {
	@Unique
	private List<MenuScaled> flint$extras = List.of();

	protected TitleScreenFlintButtonMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void flint$addExtras(CallbackInfo callback) {
		flint$extras = List.of();
		// Minecraft's title screen, or a plain stand-in another mod shows instead (FlintTitleScreen.minecraftsTitle).
		if (!FlintTitleScreen.minecraftsTitle(((Object) this).getClass()) || !FlintTitleScreen.fits()) {
			return;
		}
		try {
			List<MenuScaled> extras = VanillaTitleExtras.build(this, width);
			for (MenuScaled extra : extras) {
				addRenderableWidget(extra);
			}
			flint$extras = extras;
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: couldn't add Flint's menu switch and cards to Minecraft's title screen", e);
		}
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void flint$blend(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		if (flint$extras.isEmpty()) {
			return;
		}
		try {
			MenuBlend.paintOverVanilla(graphics, flint$extras, mouseX, mouseY, partialTick);
			if (!MenuBlend.drawingGhost()) {
				VanillaTitleExtras.paintTooltip(graphics);
			}
		} catch (RuntimeException | LinkageError e) {
			flint$extras = List.of();
			FlintClient.LOGGER.warn("Flint: the main menu blend stopped", e);
		}
	}
}
