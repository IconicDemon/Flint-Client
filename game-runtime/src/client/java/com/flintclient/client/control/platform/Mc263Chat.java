package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.ChatStack;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

/**
 * Minecraft 26.3's side of the Chat module: decorates each message on its way into your chat window (timestamp, repeat
 * count, mention colour) and sets how many messages it keeps. Runs once per chat message, never per frame. A failure
 * turns the module's hooks off for the session and chat carries on as vanilla.
 */
public final class Mc263Chat {
	private static final int CHAT = Mods.index(UtilityMods.Chat.ID);
	private static final HookGuard GUARD = new HookGuard("chat", (path, error) -> FlintClient.LOGGER.warn(
		"Flint: the {} hook failed; it's off for this session", path, error));
	private static final DateTimeFormatter H24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
	private static final DateTimeFormatter H12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ROOT);
	private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
	private static final ChatStack STACK = new ChatStack();
	private static boolean removed;

	private Mc263Chat() {
	}

	/** Whether the last {@link #incoming} removed an earlier copy (the chat then re-wraps its lines). */
	public static boolean removedPrevious() {
		return removed;
	}

	/** A message on its way into the chat window: what to show instead (often itself). */
	public static Component incoming(Component contents, List<GuiMessage> shown) {
		removed = false;
		// Brackets first: they need the message's own structure, which hiding names flattens.
		if (GUARD.on() && Mods.on(CHAT) && Mods.state(CHAT).bool(UtilityMods.Chat.NO_BRACKETS)) {
			contents = withoutBrackets(contents);
		}
		contents = Mc263Overlay.chatLine(contents);
		if (!GUARD.on() || !Mods.on(CHAT)) {
			return contents;
		}
		try {
			ModuleState chat = Mods.state(CHAT);
			String plain = contents.getString();
			int count = 1;
			if (chat.bool(UtilityMods.Chat.STACK) && !plain.isBlank()) {
				Object previous = STACK.accept(plain, chat.bool(UtilityMods.Chat.CONSECUTIVE));
				count = STACK.count();
				if (previous != null) {
					for (int i = 0; i < shown.size(); i++) {
						if (shown.get(i).content() == previous) {
							shown.remove(i);
							removed = true;
							break;
						}
					}
				}
			}
			Component body = contents;
			if (count > 1) {
				body = contents.copy().append(Component.literal(" (" + count + ")").withColor(0x8A8A8A));
			}
			if (chat.bool(UtilityMods.Chat.MENTIONS) && mentionsMe(plain)) {
				int rgb = chat.rgb(UtilityMods.Chat.MENTION_COLOR);
				body = body.copy().withStyle(style -> style.withColor(TextColor.fromRgb(rgb)));
			}
			if (chat.bool(UtilityMods.Chat.TIMESTAMPS)) {
				DateTimeFormatter format = switch (chat.option(UtilityMods.Chat.TIME_FORMAT)) {
					case 1 -> H12;
					case 2 -> SECONDS;
					default -> H24;
				};
				MutableComponent line = Component.literal("[" + LocalTime.now().format(format) + "] ").withColor(chat.rgb(UtilityMods.Chat.TIME_COLOR));
				body = line.append(body);
			}
			if (chat.bool(UtilityMods.Chat.STACK) && !plain.isBlank()) {
				STACK.shown(plain, body);
			}
			return body;
		} catch (RuntimeException e) {
			GUARD.fail(e);
			return contents;
		}
	}

	/** A standard player chat line ("<Name> message", the chat.type.text form) as "Name: message"; anything else as it is. */
	static Component withoutBrackets(Component line) {
		if (line.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable
			&& "chat.type.text".equals(translatable.getKey()) && translatable.getArgs().length == 2) {
			Object name = translatable.getArgs()[0];
			Object message = translatable.getArgs()[1];
			MutableComponent out = Component.empty().withStyle(line.getStyle());
			out.append(name instanceof Component c ? c : Component.literal(String.valueOf(name)));
			out.append(": ");
			out.append(message instanceof Component c ? c : Component.literal(String.valueOf(message)));
			for (Component sibling : line.getSiblings()) {
				out.append(sibling);
			}
			return out;
		}
		return line;
	}

	private static boolean hidden;
	private static boolean toggleWas;

	/** From the client tick: the Toggle chat key (only while playing). */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (!GUARD.on() || !Mods.on(CHAT)) {
			hidden = false;
			toggleWas = false;
			return;
		}
		String key = Mods.state(CHAT).text(UtilityMods.Chat.TOGGLE_KEY);
		boolean held = !key.isEmpty() && mc.gui.screen() == null && Mc263Features.keyHeld(key);
		if (held && !toggleWas) {
			hidden = !hidden;
		}
		toggleWas = held;
		if (key.isEmpty()) {
			hidden = false;
		}
	}

	/** Whether the chat on the HUD is hidden by the Toggle chat key (an open chat screen always shows it). */
	public static boolean hideOnHud() {
		return hidden && !(Minecraft.getInstance().gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen);
	}

	/** The chat background's colour at Minecraft's opacity. */
	public static int background(int vanilla) {
		if (!GUARD.on() || !Mods.on(CHAT)) {
			return vanilla;
		}
		return (vanilla & 0xFF000000) | Mods.state(CHAT).rgb(UtilityMods.Chat.BACKGROUND);
	}

	/** Whether {@code text} contains your name as a whole word (any case). */
	static boolean mentionsMe(String text) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		String name = player.getGameProfile().name();
		if (name.isEmpty()) {
			return false;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		String me = name.toLowerCase(Locale.ROOT);
		int at = lower.indexOf(me);
		while (at >= 0) {
			boolean startOk = at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1)) && lower.charAt(at - 1) != '_';
			int end = at + me.length();
			boolean endOk = end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end)) && lower.charAt(end) != '_';
			if (startOk && endOk) {
				return true;
			}
			at = lower.indexOf(me, at + 1);
		}
		return false;
	}

	/** How many messages (and lines) chat keeps: vanilla's 100, or the module's scrollback. */
	public static int historyLimit(int vanilla) {
		if (!GUARD.on() || !Mods.on(CHAT)) {
			return vanilla;
		}
		return switch (Mods.state(CHAT).option(UtilityMods.Chat.HISTORY)) {
			case 1 -> 500;
			case 2 -> 1000;
			case 3 -> 5000;
			default -> vanilla;
		};
	}

	/** Whether leaving a world keeps the chat. */
	public static boolean keepHistory() {
		return GUARD.on() && Mods.on(CHAT) && Mods.state(CHAT).bool(UtilityMods.Chat.KEEP);
	}
}
