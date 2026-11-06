package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.ChatStack;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of the Chat module: decorates each message on its way into your chat window (timestamp, repeat
 * count, mention colour), sets how many messages it keeps, and what the window shows of them: the tab and search
 * filter, players' heads, new messages sliding in, and where the chat sits. Decorating and sorting a message (who sent
 * it, whether it is private) run once per message; the per-frame parts only look those up, read settings and draw. A
 * failure turns the module's hooks off for the session and chat carries on as vanilla.
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
	/** The earlier copy the last {@link #incoming} removed (null: none), so the chat can drop just its lines. */
	private static @org.jspecify.annotations.Nullable GuiMessage removedMessage;

	private Mc263Chat() {
	}

	/** Whether the last {@link #incoming} removed an earlier copy (the chat then re-wraps its lines). */
	public static boolean removedPrevious() {
		return removed;
	}

	/** The earlier copy the last {@link #incoming} removed, or null. */
	public static @org.jspecify.annotations.Nullable GuiMessage removedMessage() {
		return removedMessage;
	}

	/** A message on its way into the chat window: what to show instead (often itself). */
	public static Component incoming(Component contents, List<GuiMessage> shown) {
		removed = false;
		removedMessage = null;
		// Chat Heads first: the head goes into the message as the server sent it (Before Name).
		contents = Mc263ChatHeads.incoming(contents);
		UUID headOwner = Mc263ChatHeads.lastSenderId();
		Component body = decorated(contents, shown);
		body = Mc263ChatHeads.beforeLine(body);
		if (headOwner != null) {
			if (OWNERS.size() > 200) {
				OWNERS.clear();
			}
			OWNERS.put(body, headOwner);
		}
		return body;
	}

	/** Who sent a message, as Chat Heads found it, until the message is sorted ({@link #kind}). */
	private static final Map<Component, UUID> OWNERS = new IdentityHashMap<>();

	private static Component decorated(Component contents, List<GuiMessage> shown) {
		// Brackets first: they need the message's own structure, which hiding names flattens.
		if (GUARD.on() && Mods.on(CHAT) && Mods.state(CHAT).bool(UtilityMods.Chat.NO_BRACKETS)) {
			contents = withoutBrackets(contents);
		}
		contents = Mc263Overlay.chatLine(contents);
		contents = Mc263WaypointShare.incoming(contents);
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
							removedMessage = shown.remove(i);
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
		} catch (RuntimeException | LinkageError e) {
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

	/** From the client tick: the Toggle chat key (only while playing), and re-wrapping when what shows changed. */
	public static void tick() {
		tickFilters();
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

	/** The chat background's colour at Minecraft's opacity (none at all when it is Transparent). */
	public static int background(int vanilla) {
		if (!GUARD.on() || !Mods.on(CHAT)) {
			return vanilla;
		}
		int rgb = Mods.state(CHAT).rgb(UtilityMods.Chat.BACKGROUND);
		return com.flintclient.client.control.model.RgbSetting.isClear(rgb) ? 0 : (vanilla & 0xFF000000) | rgb;
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

	private static boolean on() {
		return GUARD.on() && Mods.on(CHAT);
	}

	// ---- Tabs and search: which of the kept messages show. Chat keeps every message; a tab or a search only hides lines.

	public static final int TAB_ALL = 0;
	public static final int TAB_CHAT = 1;
	public static final int TAB_SERVER = 2;
	public static final int TAB_PRIVATE = 3;
	public static final int TAB_YOURS = 4;
	private static int tab = TAB_ALL;
	private static String query = "";

	/** What a kept message is: who sent it (null: nobody we know) and whether it is a private message. Worked out once. */
	private record Kind(@Nullable UUID sender, boolean whisper) {
	}

	private static final Kind NOBODY = new Kind(null, false);
	/** By message identity (a message's equality would compare whole components): a few thousand entries at most. */
	private static final Map<GuiMessage, Kind> KINDS = new IdentityHashMap<>();
	private static String wordsFor = "";
	private static String[] words = new String[0];

	/** The tab showing (All when tabs are off, or your tab has no name). */
	public static int tab() {
		if (!on()) {
			return TAB_ALL;
		}
		ModuleState chat = Mods.state(CHAT);
		if (!chat.bool(UtilityMods.Chat.TABS) || tab == TAB_YOURS && chat.text(UtilityMods.Chat.TAB_NAME).isBlank()) {
			return TAB_ALL;
		}
		return tab;
	}

	/** How many tabs there are: your own follows the four when it has a name. */
	public static int tabCount() {
		return on() && !Mods.state(CHAT).text(UtilityMods.Chat.TAB_NAME).isBlank() ? 5 : 4;
	}

	/** Shows {@code index}'s messages (wrapping round past the last tab). */
	public static void selectTab(int index) {
		int count = tabCount();
		tab = Math.floorMod(index, count);
		refresh();
	}

	/** Shows only messages containing {@code text} (any case); "" shows all. */
	public static void search(String text) {
		String lower = text.strip().toLowerCase(Locale.ROOT);
		if (!lower.equals(query)) {
			query = lower;
			refresh();
		}
	}

	public static String query() {
		return query;
	}

	/** Re-wraps the chat's lines from its kept messages (what shows, and how wide, changed). */
	static void refresh() {
		refreshKey = refreshKey();
		com.flintclient.client.compat.McCompat.hud().getChat().rescaleChat();
	}

	/** Whether {@code message} shows in the chat window now (it is kept either way). */
	public static boolean shown(GuiMessage message) {
		if (!on()) {
			return true;
		}
		int showing = tab();
		if (showing == TAB_ALL && query.isEmpty()) {
			return true;
		}
		try {
			if (!query.isEmpty() && !message.content().getString().toLowerCase(Locale.ROOT).contains(query)) {
				return false;
			}
			if (showing == TAB_ALL) {
				return true;
			}
			ModuleState chat = Mods.state(CHAT);
			Kind kind = kind(message);
			LocalPlayer me = Minecraft.getInstance().player;
			if (chat.bool(UtilityMods.Chat.TAB_OWN) && kind.sender != null && me != null && kind.sender.equals(me.getUUID())) {
				return true;
			}
			return switch (showing) {
				case TAB_CHAT -> !kind.whisper && (message.source() == GuiMessageSource.PLAYER || kind.sender != null);
				case TAB_SERVER -> !kind.whisper && message.source() != GuiMessageSource.PLAYER && kind.sender == null;
				case TAB_PRIVATE -> kind.whisper;
				default -> hasWord(message.content().getString(), chat.text(UtilityMods.Chat.TAB_WORDS));
			};
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return true;
		}
	}

	/** Whether {@code plain} contains one of the comma-separated {@code list} (any case). */
	private static boolean hasWord(String plain, String list) {
		if (!list.equals(wordsFor)) {
			wordsFor = list;
			words = java.util.Arrays.stream(list.split(",")).map(w -> w.strip().toLowerCase(Locale.ROOT)).filter(w -> !w.isEmpty())
				.toArray(String[]::new);
		}
		String lower = plain.toLowerCase(Locale.ROOT);
		for (String word : words) {
			if (lower.contains(word)) {
				return true;
			}
		}
		return false;
	}

	private static Kind kind(GuiMessage message) {
		Kind kind = KINDS.get(message);
		if (kind == null) {
			if (KINDS.size() > 6000) {
				// Older than any scrollback keeps: worked out again if ever needed.
				KINDS.clear();
			}
			String plain = message.content().getString();
			UUID sender = OWNERS.remove(message.content());
			if (sender == null) {
				sender = sender(message.content(), plain);
			}
			boolean whisper = isWhisper(plain, sender != null);
			kind = sender == null && !whisper ? NOBODY : new Kind(sender, whisper);
			KINDS.put(message, kind);
		}
		return kind;
	}

	/** Who sent {@code content}: the player its name's hover shows, else an online player named just before ":" or ">". */
	static @Nullable UUID sender(Component content, String plain) {
		Optional<UUID> hovered = content.visit((style, text) -> style.getHoverEvent() instanceof HoverEvent.ShowEntity show
			&& show.entity().type == com.flintclient.client.compat.McCompat.playerType() ? Optional.of(show.entity().uuid) : Optional.empty(), Style.EMPTY);
		if (hovered.isPresent()) {
			return hovered.get();
		}
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) {
			return null;
		}
		// A server's own chat format ("[VIP] Steve: hi", "<Steve> hi", "Steve » hi"); a timestamp's colon is passed over.
		int limit = Math.min(plain.length(), 64);
		for (int i = 1; i < limit; i++) {
			char c = plain.charAt(i);
			if (c != ':' && c != '>' && c != '»') {
				continue;
			}
			int end = i;
			while (end > 0 && plain.charAt(end - 1) == ' ') {
				end--;
			}
			int start = end;
			while (start > 0 && isNameChar(plain.charAt(start - 1))) {
				start--;
			}
			if (end - start >= 3 && end - start <= 16) {
				PlayerInfo info = connection.getPlayerInfo(plain.substring(start, end));
				if (info != null) {
					return info.getProfile().id();
				}
			}
		}
		return null;
	}

	private static boolean isNameChar(char c) {
		return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
	}

	/**
	 * A private message: Minecraft's /msg lines both ways, and the usual server forms ("[Steve -> me]", and "From Steve: hi"
	 * or "To Steve: hi" when {@code named}: Steve is someone online).
	 */
	static boolean isWhisper(String plain, boolean named) {
		String lower = plain.toLowerCase(Locale.ROOT);
		if (lower.contains(" whispers to you: ") || lower.contains("you whisper to ") || lower.contains(" -> me]") || lower.contains("[me -> ")) {
			return true;
		}
		// After a timestamp, if there is one.
		int stamp = lower.startsWith("[") ? lower.indexOf("] ") : -1;
		int at = stamp > 0 && stamp < 12 ? stamp + 2 : 0;
		return named && (lower.startsWith("from ", at) || lower.startsWith("to ", at));
	}

	// ---- Player heads (Mc263ChatHeads puts them into the messages; lines without one can line up with those that have one).

	/** The chat being drawn (not just measured for clicks). */
	private static @Nullable GuiGraphicsExtractor drawing;
	private static int shift;
	/** Moves a line's text right by its offset, and back (render thread only). */
	public static final java.util.function.Consumer<Matrix3x2f> SHIFT = pose -> pose.translate(shift, 0);
	public static final java.util.function.Consumer<Matrix3x2f> UNSHIFT = pose -> pose.translate(-shift, 0);

	/** How far {@code line}'s text sits right of the chat's edge (Offset non-player text), and remembers it for {@link #SHIFT}. */
	public static int lineOffset(GuiMessage.Line line) {
		shift = Mc263ChatHeads.lineOffset(line);
		return shift;
	}

	/** The public chat draw starts ({@code g} is where heads go) or ends (null). */
	public static void drawing(@Nullable GuiGraphicsExtractor g) {
		if (g == null && clipped && drawing != null) {
			drawing.disableScissor();
			clipped = false;
		}
		drawing = g;
		if (g != null && on() && slide() > 0) {
			// New lines slide in from below the chat's bottom edge, not over the hotbar.
			g.enableScissor(0, 0, g.guiWidth(), bottomEdge(g.guiHeight()));
			clipped = true;
		}
	}

	/** The chat window's bottom edge on screen (GUI pixels), where the module put it. */
	public static int bottomEdge(int guiHeight) {
		double scale = Minecraft.getInstance().options.chatScale().get();
		return (int) Math.round(Mth.floor((guiHeight - 40) / scale) * scale) - movedUp();
	}

	/** The chat window's right edge on screen (GUI pixels): its background's, where the module put it. */
	public static int rightEdge() {
		Minecraft mc = Minecraft.getInstance();
		double scale = mc.options.chatScale().get();
		int width = Mth.ceil(ChatComponent.getWidth(mc.options.chatWidth().get()) / scale);
		return movedRight() + (int) Math.round((width + 12) * scale);
	}

	private static boolean clipped;

	// ---- New messages slide up and fade in; the chat can be moved.

	private static long slideStart;
	private static float slideFrom;
	private static @Nullable GuiMessage sliding;

	/** {@code message} was just added to the chat window ({@code lines}: its lines, newest first): it slides in. */
	public static void added(GuiMessage message, List<GuiMessage.Line> lines, int scrolled) {
		if (!on() || scrolled > 0 || com.flintclient.client.ui.kit.Kit.reduced() || !Mods.state(CHAT).bool(UtilityMods.Chat.SMOOTH)) {
			return;
		}
		int count = 0;
		while (count < lines.size() && lines.get(count).parent() == message) {
			count++;
		}
		if (count == 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		float line = (int) (9 * (mc.options.chatLineSpacing().get() + 1)) * mc.options.chatScale().get().floatValue();
		slideFrom = Math.min(slide() + count * line, 10 * line);
		slideStart = net.minecraft.util.Util.getMillis();
		sliding = message;
	}

	private static float progress() {
		if (sliding == null) {
			return 1;
		}
		int length = Mods.state(CHAT).integer(UtilityMods.Chat.SMOOTH_TIME);
		float t = (net.minecraft.util.Util.getMillis() - slideStart) / (float) length;
		if (t >= 1) {
			sliding = null;
			return 1;
		}
		return Math.max(0, t);
	}

	/** How far below its place the chat is drawn now, in GUI pixels (0 once settled). */
	static float slide() {
		float p = progress();
		return p >= 1 ? 0 : slideFrom * (1 - easeOut(p));
	}

	/** A line's opacity factor: the newest message fades in as it slides. */
	public static float fade(GuiMessage.Line line) {
		if (!on() || sliding == null || line.parent() != sliding) {
			return 1;
		}
		return easeOut(progress());
	}

	static float easeOut(float t) {
		float r = 1 - t;
		return 1 - r * r * r;
	}

	/** Moves the chat by the module's offsets and the sliding of new messages, then lays it out as Minecraft does. */
	private static final class Placer implements java.util.function.Consumer<Matrix3x2f> {
		float x;
		float y;
		java.util.function.Consumer<Matrix3x2f> vanilla;

		@Override
		public void accept(Matrix3x2f pose) {
			pose.translate(x, y);
			vanilla.accept(pose);
		}
	}

	private static final Placer PLACER = new Placer();

	/** Minecraft's chat layout ({@code vanilla}: its scale and margin), moved where the module puts it. */
	public static java.util.function.Consumer<Matrix3x2f> placed(java.util.function.Consumer<Matrix3x2f> vanilla) {
		if (!on()) {
			return vanilla;
		}
		try {
			ModuleState chat = Mods.state(CHAT);
			float x = chat.integer(UtilityMods.Chat.MOVE_X);
			float y = slide() - chat.integer(UtilityMods.Chat.MOVE_Y);
			if (x == 0 && y == 0) {
				return vanilla;
			}
			PLACER.x = x;
			PLACER.y = y;
			PLACER.vanilla = vanilla;
			return PLACER;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return vanilla;
		}
	}

	/** Where the chat's left edge and bottom are moved to, in GUI pixels (x right, y up). */
	public static int movedRight() {
		return on() ? Mods.state(CHAT).integer(UtilityMods.Chat.MOVE_X) : 0;
	}

	public static int movedUp() {
		return on() ? Mods.state(CHAT).integer(UtilityMods.Chat.MOVE_Y) : 0;
	}

	/** The chat's kept lines and scroll position (Minecraft keeps them private). */
	public interface Lines {
		List<GuiMessage.Line> flint$lines();

		int flint$scroll();
	}

	/** The message whose line is at GUI point ({@code mouseX}, {@code mouseY}) in the open chat, or null. */
	public static @Nullable GuiMessage messageAt(double mouseX, double mouseY) {
		Minecraft mc = Minecraft.getInstance();
		ChatComponent chat = com.flintclient.client.compat.McCompat.hud().getChat();
		if (!(chat instanceof Lines lines)) {
			return null;
		}
		double scale = mc.options.chatScale().get();
		int chatBottom = Mth.floor((mc.getWindow().getGuiScaledHeight() - 40) / scale);
		int entry = (int) (9 * (mc.options.chatLineSpacing().get() + 1));
		double x = (mouseX - movedRight()) / scale - 4;
		double y = (mouseY + movedUp() - slide()) / scale;
		int width = Mth.ceil(ChatComponent.getWidth(mc.options.chatWidth().get()) / scale);
		if (x < -4 || x > width + 8) {
			return null;
		}
		int row = Mth.floor((chatBottom - y) / entry);
		List<GuiMessage.Line> all = lines.flint$lines();
		int scroll = lines.flint$scroll();
		int visible = Math.min(all.size() - scroll, chat.getLinesPerPage());
		return row < 0 || row >= visible ? null : all.get(row + scroll).parent();
	}

	/** {@code message}'s text as it was sent: without the timestamp or repeat count Flint added. */
	public static String plainText(GuiMessage message) {
		String plain = message.content().getString();
		if (on()) {
			ModuleState chat = Mods.state(CHAT);
			if (chat.bool(UtilityMods.Chat.TIMESTAMPS)) {
				plain = plain.replaceFirst("^\\[[0-9: APM]{4,11}\\] ", "");
			}
			if (chat.bool(UtilityMods.Chat.STACK)) {
				plain = plain.replaceFirst(" \\(\\d+\\)$", "");
			}
		}
		return plain;
	}

	private static int refreshKey;

	/** What decides which lines show and how they wrap, as one number: when it changes, chat is re-wrapped. */
	private static int refreshKey() {
		if (!on()) {
			return 0;
		}
		ModuleState chat = Mods.state(CHAT);
		return java.util.Objects.hash(tab(), query, chat.bool(UtilityMods.Chat.AVATARS), chat.bool(UtilityMods.Chat.ALIGN),
			chat.bool(UtilityMods.Chat.TAB_OWN), chat.text(UtilityMods.Chat.TAB_WORDS));
	}

	/** From the client tick: re-wraps the chat when a setting changed what shows (or the module was switched). */
	static void tickFilters() {
		if (!GUARD.on()) {
			return;
		}
		try {
			int key = refreshKey();
			if (key != refreshKey && Minecraft.getInstance().gui != null) {
				refresh();
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}
}
