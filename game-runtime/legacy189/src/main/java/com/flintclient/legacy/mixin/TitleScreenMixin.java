package com.flintclient.legacy.mixin;

import com.flintclient.client.control.rshift.DailyRewardsScreen;
import com.flintclient.client.control.rshift.FlintCosmeticsScreen;
import com.flintclient.client.control.rshift.FlintFriendsScreen;
import com.flintclient.client.control.store.StoreEndpoint;
import com.flintclient.client.control.store.StoreHandoff;
import com.flintclient.client.module.social.FriendsClient;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.legacy.ui.TitleIconButton;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flint on Minecraft 1.8.9's title screen (it has no Flint main menu), in Minecraft's own buttons:
 * <ul>
 * <li>the Minecraft Realms row is split in two, as the Options and Quit row is, with Daily Rewards on the right;</li>
 * <li>a row under it holds Cosmetics (the player's Flint Store cosmetics, to wear) and Flint Store (the Store, after
 * Minecraft's link confirmation);</li>
 * <li>Friends and Messages are icon buttons to the right of Quit Game, as the language button is to the left of Options:
 * Flint Friends (the Flint app's friends, requests and chats; the icon counts the requests waiting and unread messages, asked
 * of the Flint app every 15 s while the screen is up) and the same window opened on the chats.</li>
 * </ul>
 * The Options row moves down a row to make room (less far when the screen is short, so it stays clear of the copyright line).
 * Each opens the same window as on every line, which comes back here. In the demo the screen is left as it is.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	private static final int OPTIONS = 0;
	private static final int QUIT = 4;
	private static final int LANGUAGE = 5;
	private static final int REALMS = 14;
	private static final int DAILY_REWARDS = 7_401;
	private static final int COSMETICS = 7_402;
	private static final int STORE = 7_403;
	private static final int FRIENDS = 7_404;
	private static final int MESSAGES = 7_405;
	/** Minecraft draws its version and copyright lines this far above the foot of the screen. */
	private static final int FOOTER = 10;

	@Unique
	private int flint$waiting;

	@Inject(method = "init", at = @At("TAIL"))
	private void flint$flintButtons(CallbackInfo ci) {
		ButtonWidget realms = null;
		for (ButtonWidget button : buttons) {
			if (button.id == REALMS && button.visible && ((ButtonWidgetAccessor) button).flint$width() == 200) {
				realms = button;
				break;
			}
		}
		if (realms == null) {
			return;
		}
		((ButtonWidgetAccessor) realms).flint$setWidth(98);
		buttons.add(new ButtonWidget(DAILY_REWARDS, realms.x + 102, realms.y, 98, 20, "Daily Rewards"));
		int row = realms.y + 24;
		buttons.add(new ButtonWidget(COSMETICS, realms.x, row, 98, 20, "Cosmetics"));
		buttons.add(new ButtonWidget(STORE, realms.x + 102, row, 98, 20, "Flint Store"));
		// Minecraft puts Options, Quit Game and the language button 12 below the Realms row.
		int was = realms.y + 36;
		int now = Math.max(row + 24, Math.min(row + 36, height - FOOTER - 2 - 20));
		for (ButtonWidget button : buttons) {
			if ((button.id == OPTIONS || button.id == QUIT || button.id == LANGUAGE) && button.y == was) {
				button.y = now;
			}
		}
		buttons.add(new TitleIconButton(FRIENDS, width / 2 + 104, now, "Friends", "Flint Friends", TitleIconButton.USERS, () -> flint$waiting));
		buttons.add(new TitleIconButton(MESSAGES, width / 2 + 128, now, "Messages", "Messages", TitleIconButton.MESSAGE, () -> 0));
	}

	@Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
	private void flint$open(ButtonWidget button, CallbackInfo ci) {
		switch (button.id) {
			case DAILY_REWARDS -> MenuHost.show(new DailyRewardsScreen(this));
			case FRIENDS -> MenuHost.show(FlintFriendsScreen.friends(this));
			case MESSAGES -> MenuHost.show(FlintFriendsScreen.messages(this));
			case COSMETICS -> MenuHost.show(new FlintCosmeticsScreen(this));
			case STORE -> MenuHost.openLink(this, flint$store());
			default -> {
				return;
			}
		}
		ci.cancel();
	}

	/** Flint Friends' count (requests waiting, unread messages), asked of the Flint app every 15 s while the screen is up. */
	@Inject(method = "tick", at = @At("TAIL"))
	private void flint$friendsCount(CallbackInfo ci) {
		try {
			FriendsClient client = FriendsClient.get(FlintFriendsScreen::playing);
			client.want();
			client.tick();
			flint$waiting = client.badge();
		} catch (RuntimeException | LinkageError e) {
			flint$waiting = 0;
		}
	}

	/** An icon button's name under the pointer, as Minecraft names an item. */
	@Inject(method = "render", at = @At("TAIL"))
	private void flint$tooltips(int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
		for (ButtonWidget button : buttons) {
			if (button instanceof TitleIconButton icon && icon.visible && icon.isHovered()) {
				renderTooltip(icon.tooltip(), mouseX, mouseY);
				// Minecraft's tooltip leaves item lighting on (an item screen's state): the title screen's is without it.
				net.minecraft.client.render.platform.GlStateManager.disableRescaleNormal();
				net.minecraft.client.render.platform.Lighting.turnOff();
				net.minecraft.client.render.platform.GlStateManager.disableLighting();
				return;
			}
		}
	}

	/** The Flint Store the launcher named in its handoff, else Flint's own. */
	@Unique
	private static String flint$store() {
		try {
			StoreHandoff handoff = new StoreHandoff(FabricLoader.getInstance().getGameDir());
			handoff.poll();
			return handoff.endpoint().origin();
		} catch (RuntimeException e) {
			return StoreEndpoint.DEFAULT_BASE;
		}
	}
}
