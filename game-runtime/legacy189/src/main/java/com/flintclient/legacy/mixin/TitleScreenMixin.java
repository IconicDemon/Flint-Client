package com.flintclient.legacy.mixin;

import com.flintclient.client.mc.GuiGraphicsExtractor;
import com.flintclient.client.mc.MouseButtonEvent;
import com.flintclient.client.ui.screen.FlintTitleScreen;
import com.flintclient.client.ui.screen.MenuBlend;
import com.flintclient.client.ui.screen.VanillaTitleExtras;
import com.flintclient.client.ui.widget.MenuScaled;
import java.util.List;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.TitleScreen;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.8.9's own title screen, when the player chose it over Flint's main menu (the menu switch, or General › Vanilla
 * Main Menu), as on every line: Flint's menu switch in its top-left corner, to come back, and the "What's new" and "Daily
 * rewards" cards at the bottom right ({@link VanillaTitleExtras}), each exactly as on Flint's menu; and while the player changes
 * menus, Flint's menu dissolving over it ({@link MenuBlend}). Only the exact vanilla class gets them, and only while Flint's menu
 * can run.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	@Unique
	private List<MenuScaled> flint$extras = List.of();

	@Inject(method = "init", at = @At("TAIL"))
	private void flint$addExtras(CallbackInfo ci) {
		flint$extras = List.of();
		if (((Object) this).getClass() != TitleScreen.class || !FlintTitleScreen.fits() || com.flintclient.client.mc.Screen.titleBroken()
			|| com.flintclient.client.ui.screen.ExternalButtons.probing()) {
			return;
		}
		int font = com.flintclient.client.mc.Screen.minecraftFont();
		try {
			flint$extras = VanillaTitleExtras.build(this, width);
		} catch (RuntimeException | LinkageError e) {
			LogManager.getLogger("Flint").warn("Flint: couldn't add Flint's menu switch and cards to Minecraft's title screen", e);
		} finally {
			com.flintclient.client.render.PixelFace.end(font);
		}
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void flint$paintExtras(int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
		if (flint$extras.isEmpty()) {
			return;
		}
		int font = com.flintclient.client.mc.Screen.minecraftFont();
		try {
			GuiGraphicsExtractor graphics = GuiGraphicsExtractor.get();
			MenuBlend.paintOverVanilla(graphics, flint$extras, mouseX, mouseY, tickDelta);
			if (!MenuBlend.drawingGhost()) {
				VanillaTitleExtras.paintTooltip(graphics);
			}
		} catch (RuntimeException | LinkageError e) {
			flint$extras = List.of();
			LogManager.getLogger("Flint").warn("Flint: the main menu blend stopped", e);
		} finally {
			com.flintclient.client.render.PixelFace.end(font);
		}
	}

	/** A click on the switch or a card is theirs (they lie clear of Minecraft's buttons). */
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void flint$clickExtras(int mouseX, int mouseY, int button, CallbackInfo ci) {
		try {
			for (MenuScaled extra : flint$extras) {
				if (extra.visible && extra.mouseClicked(new MouseButtonEvent(mouseX + 0.5, mouseY + 0.5, button), false)) {
					ci.cancel();
					return;
				}
			}
		} catch (RuntimeException | LinkageError e) {
			flint$extras = List.of();
			LogManager.getLogger("Flint").warn("Flint: the main menu switch stopped", e);
		}
	}
}
