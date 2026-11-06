package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.ChatCalculator;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's chat screen with the Chat module on: the tabs and search box in a row above the input, the
 * calculator (a line starting with "=" is worked out on your screen, never sent), copying a message by clicking it, and
 * the input bar sliding up as chat opens. Render thread. A failure turns these off for the session; chat works as vanilla.
 */
public final class Mc263ChatScreen {
	private static final int CHAT = Mods.index(UtilityMods.Chat.ID);
	private static final HookGuard GUARD = new HookGuard("chat screen", (path, error) -> FlintClient.LOGGER.warn(
		"Flint: the {} hook failed; it's off for this session", path, error));
	private static final String[] TABS = {"All", "Chat", "Server", "Private", ""};
	/** Flint's orange, for the tab showing and the search box in use. */
	private static final int ACCENT = 0xFFFF8139;
	private static final int ROW_HEIGHT = 11;
	private static final int SEARCH_WIDTH = 110;
	/** SDL scancodes: Tab, Page Up, Page Down. */
	private static final int TAB_KEY = 43;
	private static final int PAGE_UP = 75;
	private static final int PAGE_DOWN = 78;

	private static @Nullable EditBox search;
	private static boolean searchFocused;
	/** The tab row as last drawn: each tab's left edge and width (the fifth is your own), and the row's top. */
	private static final int[] TAB_X = new int[5];
	private static final int[] TAB_W = new int[5];
	private static int tabsDrawn;
	private static int rowY;
	/** The search box's right edge as last drawn (0: not drawn). */
	private static int searchRight;
	/** The function or constant name the calculator would finish with Tab, or "". */
	private static String completion = "";

	private Mc263ChatScreen() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(CHAT);
	}

	/** The chat screen was laid out ({@code input}: its text box): the search box goes in the row above it. */
	public static void opened(EditBox input, int width, int height) {
		searchFocused = false;
		search = null;
		tabsDrawn = 0;
		hinting = false;
		if (!on()) {
			return;
		}
		try {
			Font font = Minecraft.getInstance().font;
			EditBox box = new EditBox(font, width - SEARCH_WIDTH - 2, height - 14 - ROW_HEIGHT - 1, SEARCH_WIDTH - 4, ROW_HEIGHT,
				Component.literal("Search chat"));
			box.setBordered(false);
			box.setMaxLength(64);
			box.setHint(Component.literal("Search chat…").withStyle(ChatFormatting.DARK_GRAY));
			box.setValue(Mc263Chat.query());
			box.setResponder(Mc263Chat::search);
			box.setFocused(false);
			search = box;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** The chat screen closed: a search ends with it (the tab stays). */
	public static void closed() {
		search = null;
		searchFocused = false;
		completion = "";
		if (GUARD.on() && !Mc263Chat.query().isEmpty()) {
			try {
				Mc263Chat.search("");
			} catch (RuntimeException | LinkageError e) {
				GUARD.fail(e);
			}
		}
	}

	private static boolean searchShown() {
		return search != null && Mods.state(CHAT).bool(UtilityMods.Chat.SEARCH);
	}

	private static boolean tabsShown() {
		return Mods.state(CHAT).bool(UtilityMods.Chat.TABS);
	}

	/** How far below its place the input bar is drawn, {@code openedAt} ms after chat opened (0 once settled). */
	public static int inputOffset(long openedAt) {
		if (!on() || com.flintclient.client.ui.kit.Kit.reduced()) {
			return 0;
		}
		try {
			ModuleState chat = Mods.state(CHAT);
			if (!chat.bool(UtilityMods.Chat.SMOOTH_INPUT)) {
				return 0;
			}
			float t = (Util.getMillis() - openedAt) / (float) chat.integer(UtilityMods.Chat.INPUT_TIME);
			if (t >= 1) {
				return 0;
			}
			float eased = chat.option(UtilityMods.Chat.INPUT_EASING) == 0 ? backOut(Math.max(0, t)) : Mc263Chat.easeOut(Math.max(0, t));
			return Math.round(chat.integer(UtilityMods.Chat.INPUT_OFFSET) * (1 - eased));
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return 0;
		}
	}

	/** Eases out past the end and back: a small bounce. */
	private static float backOut(float t) {
		float r = 1 - t;
		return 1 - (2.70158F * r * r * r - 1.70158F * r * r);
	}

	/** After the chat screen drew itself: the tabs and the search box. */
	public static void render(GuiGraphicsExtractor g, int width, int height, int mouseX, int mouseY, float delta) {
		tabsDrawn = 0;
		if (!on()) {
			return;
		}
		try {
			Font font = Minecraft.getInstance().font;
			// Just under the chat window, as its bottom edge: clear of the hotbar and the input bar.
			rowY = Math.min(Mc263Chat.bottomEdge(height) + 2, height - 14 - ROW_HEIGHT - 2);
			int x = Mc263Chat.movedRight();
			if (tabsShown()) {
				int count = Mc263Chat.tabCount();
				int showing = Mc263Chat.tab();
				String own = Mods.state(CHAT).text(UtilityMods.Chat.TAB_NAME).strip();
				for (int i = 0; i < count; i++) {
					String label = i == Mc263Chat.TAB_YOURS ? own : TABS[i];
					int w = font.width(label) + 8;
					boolean hover = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
					g.fill(x, rowY, x + w, rowY + ROW_HEIGHT, hover ? 0xC0141414 : 0xA0000000);
					if (i == showing) {
						g.fill(x, rowY + ROW_HEIGHT - 1, x + w, rowY + ROW_HEIGHT, ACCENT);
					}
					g.text(font, label, x + 4, rowY + 2, i == showing ? 0xFFFFFFFF : hover ? 0xFFD9D9D9 : 0xFF8A8A8A, false);
					TAB_X[i] = x;
					TAB_W[i] = w;
					x += w + 1;
				}
				tabsDrawn = count;
			}
			EditBox box = search;
			searchRight = 0;
			if (box != null && searchShown()) {
				// At the chat's right edge, after the tabs.
				int right = Math.min(width - 2, Mc263Chat.rightEdge());
				int left = Math.max(x + 4, right - SEARCH_WIDTH);
				if (right - left >= 40) {
					box.setX(left + 4);
					box.setY(rowY + 2);
					box.setWidth(right - left - 6);
					g.fill(left, rowY, right, rowY + ROW_HEIGHT, searchFocused ? 0xD0000000 : 0xA0000000);
					if (searchFocused || !box.getValue().isEmpty()) {
						g.fill(left, rowY + ROW_HEIGHT - 1, right, rowY + ROW_HEIGHT, searchFocused ? ACCENT : 0xFF5A5A5A);
					}
					box.extractRenderState(g, mouseX, mouseY, delta);
					searchRight = right;
				}
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static boolean inSearch(double x, double y) {
		return search != null && searchRight > 0 && searchShown() && x >= search.getX() - 4 && x < searchRight && y >= rowY && y < rowY + ROW_HEIGHT;
	}

	private static void focusSearch(EditBox input) {
		EditBox box = search;
		if (box == null) {
			return;
		}
		searchFocused = true;
		input.setCanLoseFocus(true);
		input.setFocused(false);
		box.setFocused(true);
	}

	private static void leaveSearch(EditBox input) {
		searchFocused = false;
		if (search != null) {
			search.setFocused(false);
		}
		input.setFocused(true);
		input.setCanLoseFocus(false);
	}

	/** A click in the chat screen: a tab, the search box, or copying a message. True when it was used. */
	public static boolean click(EditBox input, MouseButtonEvent event) {
		if (!on()) {
			return false;
		}
		try {
			double x = event.x();
			double y = event.y();
			if (inSearch(x, y)) {
				focusSearch(input);
				search.mouseClicked(event, false);
				return true;
			}
			if (searchFocused) {
				leaveSearch(input);
			}
			if (y >= rowY && y < rowY + ROW_HEIGHT) {
				for (int i = 0; i < tabsDrawn; i++) {
					if (x >= TAB_X[i] && x < TAB_X[i] + TAB_W[i]) {
						Mc263Chat.selectTab(i);
						return true;
					}
				}
			}
			return copy(event);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** Copy on click: the message under the pointer to the clipboard. */
	private static boolean copy(MouseButtonEvent event) {
		int mode = Mods.state(CHAT).option(UtilityMods.Chat.COPY);
		// SDL numbers mouse buttons from 1: 3 is the right button.
		if (mode == 0 || event.button() != 3 || mode == 1 && !event.hasControlDown()) {
			return false;
		}
		GuiMessage message = Mc263Chat.messageAt(event.x(), event.y());
		if (message == null) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		mc.keyboardHandler.setClipboard(Mc263Chat.plainText(message));
		com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal("Message copied"), false);
		return true;
	}

	/** A key in the chat screen: typing in the search box, its key, the next-tab key, or finishing a calculator name. */
	public static boolean key(EditBox input, KeyEvent event) {
		if (!on()) {
			return false;
		}
		try {
			ModuleState chat = Mods.state(CHAT);
			EditBox box = search;
			if (searchFocused && box != null) {
				if (event.isEscape() || event.isConfirmation()) {
					leaveSearch(input);
					return true;
				}
				if (event.key() == PAGE_UP || event.key() == PAGE_DOWN) {
					return false;
				}
				box.keyPressed(event);
				return true;
			}
			if (searchShown() && event.hasControlDown() && is(chat.text(UtilityMods.Chat.SEARCH_KEY), event)) {
				focusSearch(input);
				return true;
			}
			if (tabsShown() && is(chat.text(UtilityMods.Chat.TAB_KEY), event)) {
				Mc263Chat.selectTab(Mc263Chat.tab() + 1);
				return true;
			}
			if (event.key() == TAB_KEY && !completion.isEmpty() && input.getCursorPosition() == input.getValue().length()) {
				input.insertText(completion);
				return true;
			}
			return false;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** Whether {@code event} is the key saved as {@code name} ("key.keyboard.f"). */
	private static boolean is(String name, KeyEvent event) {
		if (name.isEmpty()) {
			return false;
		}
		InputConstants.Key key = InputConstants.getKey(name);
		return key.getType() == com.flintclient.client.compat.McCompat.KEYBOARD && key.getValue() == event.key();
	}

	/** A typed character: into the search box while it's in use. */
	public static boolean typed(CharacterEvent event) {
		if (!searchFocused || search == null || !on()) {
			return false;
		}
		search.charTyped(event);
		return true;
	}

	/** The input changed: the calculator's answer (or the name it would finish) shows faintly after it. */
	public static void edited(EditBox input, String value) {
		completion = "";
		String hint = "";
		try {
			if (on() && Mods.state(CHAT).bool(UtilityMods.Chat.CALC) && value.startsWith("=")) {
				completion = ChatCalculator.completion(value);
				hint = completion;
				if (hint.isEmpty() && ChatCalculator.isSum(value)) {
					List<ChatCalculator.Result> results = ChatCalculator.solve(value, position());
					if (results.size() == 1 && results.getFirst().error() == null) {
						hint = " = " + ChatCalculator.format(results.getFirst().value(), Mods.state(CHAT).integer(UtilityMods.Chat.CALC_PLACES));
					}
				}
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
		if (!hint.isEmpty()) {
			input.setSuggestion(hint);
			hinting = true;
		} else if (hinting) {
			// Only clears what the calculator put there: a command's suggestion is Minecraft's.
			input.setSuggestion(null);
			hinting = false;
		}
	}

	private static boolean hinting;

	/** A line sent from chat: a sum is worked out and shown in your chat instead of being sent. True when it was. */
	public static boolean submit(String line, boolean toHistory) {
		String text = line.strip();
		if (!on() || !Mods.state(CHAT).bool(UtilityMods.Chat.CALC) || !ChatCalculator.isSum(text)) {
			return false;
		}
		try {
			java.util.List<ChatCalculator.Result> results = ChatCalculator.solve(text, position());
			// "=)" or "=D" is a message, not a sum: it goes to the server as typed.
			if (ChatCalculator.chatAfterAll(text, results)) {
				return false;
			}
			ModuleState chat = Mods.state(CHAT);
			var history = com.flintclient.client.compat.McCompat.hud().getChat();
			if (toHistory) {
				history.addRecentChat(text);
			}
			ChatFormatting input = color(chat.option(UtilityMods.Chat.CALC_INPUT));
			ChatFormatting output = color(chat.option(UtilityMods.Chat.CALC_OUTPUT));
			ChatFormatting error = color(chat.option(UtilityMods.Chat.CALC_ERROR));
			int places = chat.integer(UtilityMods.Chat.CALC_PLACES);
			for (ChatCalculator.Result result : results) {
				Component shown = result.error() == null
					? Component.literal(result.sum()).withStyle(input).append(Component.literal(" = " + ChatCalculator.format(result.value(), places))
						.withStyle(output))
					: Component.literal(result.sum()).withStyle(input).append(Component.literal("  " + result.error()).withStyle(error));
				history.addClientSystemMessage(shown);
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** Minecraft's chat colour by its number (black 0 … white 15), as the calculator's colour settings store it. */
	private static ChatFormatting color(int index) {
		// The enum lists the 16 colours first, in that order.
		return index >= 0 && index < 16 ? ChatFormatting.values()[index] : ChatFormatting.WHITE;
	}

	private static ChatCalculator.Position position() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player == null ? ChatCalculator.Position.ORIGIN : new ChatCalculator.Position(player.getX(), player.getY(), player.getZ());
	}
}
