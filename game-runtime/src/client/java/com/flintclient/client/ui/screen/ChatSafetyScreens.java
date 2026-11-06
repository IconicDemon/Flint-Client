package com.flintclient.client.ui.screen;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.flintclient.client.control.platform.Mc263ChatSafety.Mode;
import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.ui.widget.SpriteButton;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.WarningScreen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * No Chat Reports' screens and buttons: the "signing requested" and Realms warnings (Minecraft's warning screen, as the mod
 * has them), the chat screen's safety button, and the multiplayer menu's reload and on/off buttons.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt): {@code UnsafeServerScreen},
 * {@code RealmsWarningScreen}, {@code FontHelper} and the buttons of its chat and multiplayer screen mixins.
 */
public final class ChatSafetyScreens {
	private static boolean realmsSeen;

	private ChatSafetyScreens() {
	}

	/** Prompt mode: the server wants signed chat; ask. Returns to the chat (a fresh one when none is open). */
	public static void promptSigning() {
		Minecraft mc = Minecraft.getInstance();
		Screen back = mc.gui.screen() instanceof ChatScreen chat ? chat : new ChatScreen("", false);
		mc.gui.setScreen(new UnsafeServer(back));
	}

	/** Realms is about to open: the warning first, unless it was seen this session or switched off. */
	public static @Nullable Screen beforeRealms(Screen realms) {
		if (realmsSeen || !Mc263ChatSafety.on() || Mc263ChatSafety.bool(NoChatReports.SKIP_REALMS_WARNING)) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		return new RealmsWarning(mc.gui.screen() != null ? mc.gui.screen() : new net.minecraft.client.gui.screens.TitleScreen(), realms);
	}

	/** The server asks for signed chat: allow it for this session, or dismiss (optionally for the rest of the session). */
	static final class UnsafeServer extends WarningScreen {
		private static final Component TITLE = Component.translatable("flintclient.ncr.gui.signing_required.header").withStyle(ChatFormatting.BOLD);
		private static final Component CONTENT = Component.translatable("flintclient.ncr.gui.signing_required.contents");
		private static final Component CHECK = Component.translatable("flintclient.ncr.gui.signing_required.check");
		private final Screen previous;

		UnsafeServer(Screen previous) {
			super(TITLE, CONTENT, CHECK, TITLE.copy().append("\n").append(CONTENT));
			this.previous = previous;
		}

		@Override
		protected Layout addFooterButtons() {
			LinearLayout row = LinearLayout.horizontal().spacing(8);
			row.addChild(Button.builder(Component.translatable("flintclient.ncr.gui.signing_required.allow_signing"), button -> {
				Mc263ChatSafety.setAllowSigning(true);
				this.minecraft.gui.setScreen(previous);
			}).build());
			row.addChild(Button.builder(Component.translatable("flintclient.ncr.gui.signing_required.cancel"), button -> {
				if (this.stopShowing.selected()) {
					Mc263ChatSafety.setHideThisSession(true);
				}
				this.minecraft.gui.setScreen(previous);
			}).build());
			return row;
		}
	}

	/** Realms isn't private: a warning before it opens, with a link to No Chat Reports' explanation. */
	static final class RealmsWarning extends WarningScreen {
		private static final Component TITLE = Component.translatable("flintclient.ncr.gui.realms_warning.header").withStyle(ChatFormatting.BOLD);
		private static final Component CONTENT = Component.translatable("flintclient.ncr.gui.realms_warning.contents");
		private static final Component CHECK = Component.translatable("flintclient.ncr.gui.realms_warning.check");
		private static final URI WIKI = URI.create("https://github.com/Aizistral-Studios/No-Chat-Reports/wiki/The-Realms-Question");
		private final Screen previous;
		private final Screen realms;

		RealmsWarning(Screen previous, Screen realms) {
			super(TITLE, CONTENT, CHECK, TITLE.copy().append("\n").append(CONTENT));
			this.previous = previous;
			this.realms = realms;
		}

		@Override
		protected Layout addFooterButtons() {
			LinearLayout row = LinearLayout.horizontal().spacing(8);
			row.addChild(Button.builder(CommonComponents.GUI_PROCEED, button -> {
				realmsSeen = true;
				if (this.stopShowing.selected()) {
					Mc263ChatSafety.setSkipRealmsWarning();
				}
				this.minecraft.gui.setScreen(realms);
			}).build());
			row.addChild(Button.builder(Component.translatable("flintclient.ncr.gui.realms_warning.learn_more"), ConfirmLinkScreen.confirmLink(this, WIKI, true))
				.build());
			row.addChild(Button.builder(CommonComponents.GUI_BACK, button -> this.minecraft.gui.setScreen(previous)).build());
			return row;
		}
	}

	// ---------- buttons ----------

	/** The chat screen's safety status (bottom right): its picture is how safe the server is; clicking picks its signing mode. */
	public static SpriteButton safetyButton(int width, int height) {
		Mode[] choices = Mc263ChatSafety.safetyChoices();
		Mode[] value = {Mc263ChatSafety.modeUnresolved(Mc263ChatSafety.lastServer())};
		if (Arrays.asList(choices).indexOf(value[0]) < 0) {
			value[0] = choices[0];
		}
		SpriteButton button = new SpriteButton(width - 23, height - 37, () -> "safety_state/" + Mc263ChatSafety.current().sprite(), self -> {
			int at = Arrays.asList(choices).indexOf(value[0]);
			boolean back = com.flintclient.client.control.platform.Mc263ControlPlatform.keyDown(com.mojang.blaze3d.platform.InputConstants.KEY_LSHIFT)
				|| com.flintclient.client.control.platform.Mc263ControlPlatform.keyDown(com.mojang.blaze3d.platform.InputConstants.KEY_RSHIFT);
			value[0] = choices[Math.floorMod(at + (back ? -1 : 1), choices.length)];
			Mc263ChatSafety.safetyPicked(value[0]);
			self.setTooltip(Tooltip.create(Mc263ChatSafety.safetyTooltip(value[0])));
		});
		button.setTooltip(Tooltip.create(Mc263ChatSafety.safetyTooltip(value[0])));
		return button;
	}

	/** The multiplayer menu's buttons, top to bottom: reload the signing modes, and No Chat Reports on or off. */
	public static List<SpriteButton> multiplayerButtons() {
		List<SpriteButton> out = new ArrayList<>(2);
		if (Mc263ChatSafety.bool(NoChatReports.SHOW_RELOAD_BUTTON)) {
			SpriteButton reload = new SpriteButton(0, 0, () -> "config_reload_button", self -> Mc263ChatSafety.reloadModes());
			reload.setTooltip(Tooltip.create(Component.translatable("flintclient.ncr.gui.reload_config_tooltip")));
			out.add(reload);
		}
		if (Mc263ChatSafety.bool(NoChatReports.SHOW_NCR_BUTTON)) {
			Consumer<SpriteButton> tooltip = self -> self.setTooltip(Tooltip.create(Component.translatable("flintclient.ncr.gui.ncr_toggle_tooltip",
				Language.getInstance().getOrDefault("flintclient.ncr.gui.ncr_state_" + (Mc263ChatSafety.bool(NoChatReports.ENABLE_MOD) ? "on" : "off")))));
			SpriteButton toggle = new SpriteButton(0, 0, () -> Mc263ChatSafety.bool(NoChatReports.ENABLE_MOD) ? "ncr_active_button" : "ncr_inactive_button",
				self -> {
					Mc263ChatSafety.setEnableMod(!Mc263ChatSafety.bool(NoChatReports.ENABLE_MOD));
					tooltip.accept(self);
				});
			tooltip.accept(toggle);
			out.add(toggle);
		}
		return out;
	}

	/** The "Safe Server" tooltip: No Chat Reports' text, wrapped at 250 pixels keeping its formatting codes. */
	public static List<FormattedCharSequence> verifiedTooltip(Font font) {
		List<FormattedCharSequence> lines = new ArrayList<>();
		for (String line : wrap(font, Language.getInstance().getOrDefault("flintclient.ncr.gui.verified_server"), 250)) {
			lines.add(Component.literal(line).getVisualOrderText());
		}
		return lines;
	}

	// No Chat Reports' FontHelper: wraps text with section-sign formatting codes, carrying the codes onto the next line.

	static List<String> wrap(Font font, String text, int width) {
		return Arrays.asList(wrapToWidth(font, text, width).split("\n"));
	}

	private static String wrapToWidth(Font font, String text, int width) {
		int j = fitWidth(font, text, width);
		if (text.length() <= j) {
			return text;
		}
		String head = text.substring(0, j);
		char c = text.charAt(j);
		boolean space = c == ' ' || c == '\n';
		String tail = formatOf(head) + text.substring(j + (space ? 1 : 0));
		return head + "\n" + wrapToWidth(font, tail, width);
	}

	private static int fitWidth(Font font, String text, int width) {
		int length = text.length();
		int k = 0;
		int l = 0;
		int lastSpace = -1;
		for (boolean bold = false; l < length; l++) {
			char c = text.charAt(l);
			switch (c) {
				case '\n':
					l--;
					break;
				case ' ':
					lastSpace = l;
					// fall through: a space has width
				default:
					k += font.width(String.valueOf(c));
					if (bold) {
						k++;
					}
					break;
				case '§':
					if (l < length - 1) {
						char code = text.charAt(++l);
						if (code == 'l' || code == 'L') {
							bold = true;
						} else if (code == 'r' || code == 'R' || colour(code)) {
							bold = false;
						}
					}
			}
			if (c == '\n') {
				lastSpace = ++l;
				break;
			}
			if (k > width) {
				break;
			}
		}
		return l != length && lastSpace != -1 && lastSpace < l ? lastSpace : l;
	}

	private static boolean colour(char c) {
		return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
	}

	private static boolean special(char c) {
		return c >= 'k' && c <= 'o' || c >= 'K' && c <= 'O' || c == 'r' || c == 'R';
	}

	private static String formatOf(String text) {
		String out = "";
		int i = -1;
		int length = text.length();
		while ((i = text.indexOf('§', i + 1)) != -1) {
			if (i < length - 1) {
				char c = text.charAt(i + 1);
				if (colour(c)) {
					out = "§" + c;
				} else if (special(c)) {
					out = out + "§" + c;
				}
			}
		}
		return out;
	}
}
