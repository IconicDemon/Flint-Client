/*
 * Adapted from Chat Heads by dzwdz and Fourmisain (https://github.com/dzwdz/chat_heads), MPL-2.0: the licence text is in
 * META-INF/licenses/MPL-2.0-ChatHeads.txt. This file is covered by the MPL-2.0: who sent a message (the server's word, the
 * name in it, a /tell click, a nickname), where the head goes in it, and how heads are drawn in chat.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods.Chat;
import com.flintclient.client.control.ControlState;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.font.GlyphInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.contents.objects.PlayerSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.ResolvableProfile;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

/**
 * The Chat module's player heads, as Chat Heads draws them: Minecraft's own player-head glyph put into the message, just
 * before the sender's name (Before Name) or at the start of the line (Before Line), drawn with the module's padding,
 * 3Dness, shadow and rounding. Who sent a message is the server's word when it gives one, else a name (a player's profile
 * name, tab-list name or a nickname) found in the message. Render thread; fault-isolated (a failure leaves chat without
 * heads for the session).
 */
public final class Mc263ChatHeads {
	private static final int CHAT = Mods.index(Chat.ID);
	private static final HookGuard GUARD = new HookGuard("chat heads", (path, error) -> FlintClient.LOGGER.warn(
		"Flint: the {} hook failed; it's off for this session", path, error));
	private static final Pattern FORMAT = Pattern.compile("§.");
	/** The resource a server's pack carries to turn heads off (Chat Heads' own, so servers that ask it are obeyed). */
	public static final Identifier DISABLE_RESOURCE = Identifier.fromNamespaceAndPath("chat_heads", "disable");

	private static final int NONE = 0;
	private static final int PLAYER = 1;
	private static final int DISGUISED = 2;
	private static final int SYSTEM = 3;
	/** What kind of message ChatListener is adding now (NONE: the client's own lines, which never get a head). */
	private static int context = NONE;
	private static @Nullable PlayerInfo contextOwner;
	/** Who the message being added is from, once found (for Before Line and the chat tabs). */
	private static @Nullable PlayerInfo lastSender;

	private static volatile boolean serverSentUuid;
	private static volatile boolean serverDisabled;

	/** Heads in chat (and the GUI's text) are being laid out or drawn: padding, 3Dness, shadow and rounding apply. */
	private static boolean rendering;
	private static float extraAdvance;
	private static float threeDee;
	private static boolean noShadow;
	private static boolean round;
	private static boolean insideLog;

	private Mc263ChatHeads() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(CHAT) && Mods.state(CHAT).bool(Chat.AVATARS);
	}

	// ---- Where a message comes from (ChatListener).

	/** A player's chat message is shown: {@code sender} is who the server says sent it. */
	public static void playerMessage(GameProfile sender) {
		context = PLAYER;
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		contextOwner = connection == null ? null : connection.getPlayerInfo(sender.id());
	}

	/** A disguised chat message (a server's /say and the like): no sender given. */
	public static void disguisedMessage() {
		context = DISGUISED;
		contextOwner = null;
	}

	/** A system message from the server (not the client's own feedback). */
	public static void systemMessage(boolean fromServer) {
		context = fromServer ? SYSTEM : NONE;
		contextOwner = null;
	}

	public static void messageDone() {
		context = NONE;
		contextOwner = null;
	}

	/** A new connection: what the last server said or asked is forgotten. */
	public static void connecting() {
		serverSentUuid = false;
		serverDisabled = false;
	}

	/** The server's resource packs carry {@link #DISABLE_RESOURCE}: no heads on this server. */
	public static void disabledByServer() {
		serverDisabled = true;
		FlintClient.LOGGER.info("Flint: chat heads disabled by the server's request");
	}

	// ---- Putting the head into a message.

	/**
	 * A message on its way into chat (before Flint's own decorations): with its head before the sender's name (Before
	 * Name), or unchanged with the sender remembered for {@link #beforeLine} (Before Line).
	 */
	public static Component incoming(Component message) {
		lastSender = null;
		if (context == NONE || !on()) {
			return message;
		}
		try {
			ModuleState chat = Mods.state(CHAT);
			if (context == SYSTEM && !chat.bool(Chat.SYSTEM_MESSAGES)) {
				return message;
			}
			if (chat.bool(Chat.DETECT_ALIASES)) {
				autoDetectAlias(chat, message);
			}
			if (serverDisabled) {
				return message;
			}
			PlayerInfo owner = context == PLAYER ? contextOwner : null;
			int detection = chat.option(Chat.SENDER_DETECTION);
			boolean forceHeuristic = message.getContents() instanceof TranslatableContents t
				&& Objects.equals(t.getKey(), "showcaseitem.misc.shared_item");
			if (detection == 2 || forceHeuristic) {
				owner = null;
			} else if (owner != null) {
				serverSentUuid = true;
			} else if (detection == 0 || serverSentUuid && chat.bool(Chat.SMART_HEURISTICS)) {
				return message;
			}
			Found found = detect(message, owner, chat);
			if (found == null) {
				return message;
			}
			lastSender = found.owner;
			return beforeLineMode(chat) ? message : found.message;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			lastSender = null;
			return message;
		}
	}

	/** Who the message just decorated is from (its profile id), or null. */
	public static @Nullable UUID lastSenderId() {
		return lastSender == null ? null : lastSender.getProfile().id();
	}

	/** Before Line: the finished line (timestamp and all) with the sender's head first. */
	public static Component beforeLine(Component body) {
		PlayerInfo sender = lastSender;
		lastSender = null;
		if (sender == null || !on()) {
			return body;
		}
		try {
			return beforeLineMode(Mods.state(CHAT)) ? Component.empty().append(head(sender, body)).append(body) : body;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return body;
		}
	}

	private static boolean beforeLineMode(ModuleState chat) {
		return chat.option(Chat.HEAD_POSITION) == 0;
	}

	/** "Nick is Name" (EssentialsX's /realname answer): Nick becomes a nickname for Name. */
	private static void autoDetectAlias(ModuleState chat, Component message) {
		String text = message.getString();
		int space = text.indexOf(' ');
		if (space <= 0 || !text.startsWith(" is ", space)) {
			return;
		}
		String nickname = text.substring(0, space);
		String profileName = text.substring(space + " is ".length());
		if (profileName.isEmpty() || profileName.contains(" ")) {
			return;
		}
		Map<String, String> aliases = aliases(chat.text(Chat.NAME_ALIASES));
		if (profileName.equals(aliases.get(nickname))) {
			return;
		}
		aliases.put(nickname, profileName);
		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, String> entry : aliases.entrySet()) {
			if (!out.isEmpty()) {
				out.append(", ");
			}
			out.append(entry.getKey()).append(" -> ").append(entry.getValue());
		}
		if (out.length() <= Chat.NAME_ALIASES.maxLength() && chat.set(Chat.NAME_ALIASES, out.toString())) {
			ControlState.settings().recordChange(chat.id());
			ControlState.saveAll();
		}
	}

	private static String aliasesFor = "";
	private static Map<String, String> aliasesParsed = Map.of();

	/** The Name aliases setting ("nick -> name, nick2 -> name2") as nickname to profile name, in order. */
	static Map<String, String> aliases(String setting) {
		if (!setting.equals(aliasesFor)) {
			Map<String, String> map = new LinkedHashMap<>();
			for (String pair : setting.split(",")) {
				int arrow = pair.indexOf("->");
				if (arrow < 0) {
					continue;
				}
				String nick = pair.substring(0, arrow).strip();
				String name = pair.substring(arrow + 2).strip();
				if (!nick.isEmpty() && !name.isEmpty()) {
					map.put(nick, name);
				}
			}
			aliasesFor = setting;
			aliasesParsed = map;
		}
		return new LinkedHashMap<>(aliasesParsed);
	}

	private record Found(Component message, PlayerInfo owner) {
	}

	private static @Nullable Found detect(Component message, @Nullable PlayerInfo given, ModuleState chat) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) {
			return null;
		}
		Names names = new Names(connection, chat);
		if (given != null) {
			names.addProfileName(given);
		} else {
			names.collectProfileNames();
		}
		List<Component> split = split(message);
		if (containsPlayerSprite(split)) {
			return null;
		}
		PlayerInfo found = headForTell(split, names);
		if (found != null) {
			return new Found(join(split), found);
		}
		if (given != null) {
			names.add(given);
		} else {
			names.collectAllNames();
		}
		found = headForName(split, names);
		if (found != null) {
			return new Found(join(split), found);
		}
		return given == null ? null : new Found(Component.empty().append(head(given, message)).append(message), given);
	}

	/** The names a message is searched for, and whose they are. */
	private static final class Names {
		private final ClientPacketListener connection;
		private final ModuleState chat;
		private final Map<String, PlayerInfo> infos = new HashMap<>();
		private boolean profiles;
		private boolean everything;

		Names(ClientPacketListener connection, ModuleState chat) {
			this.connection = connection;
			this.chat = chat;
		}

		private Iterable<PlayerInfo> online() {
			List<PlayerInfo> out = new ArrayList<>();
			for (PlayerInfo info : connection.getOnlinePlayers()) {
				// Tab-list filler entries some servers add.
				if (!info.getProfile().name().startsWith("|slot_")) {
					out.add(info);
				}
			}
			return out;
		}

		void collectProfileNames() {
			if (!profiles) {
				profiles = true;
				online().forEach(this::addProfileName);
			}
		}

		void addProfileName(PlayerInfo info) {
			String name = FORMAT.matcher(info.getProfile().name()).replaceAll("");
			if (!name.isEmpty()) {
				infos.put(name, info);
			}
		}

		void collectAllNames() {
			if (!everything) {
				everything = true;
				collectProfileNames();
				online().forEach(this::addDisplayName);
				addAliases();
			}
		}

		private void addAliases() {
			for (Map.Entry<String, String> entry : aliases(chat.text(Chat.NAME_ALIASES)).entrySet()) {
				PlayerInfo info = infos.get(entry.getValue());
				if (info != null) {
					infos.putIfAbsent(entry.getKey(), info);
				}
			}
		}

		private void addDisplayName(PlayerInfo info) {
			Component display = info.getTabListDisplayName();
			if (display != null) {
				String name = FORMAT.matcher(display.getString()).replaceAll("");
				if (!name.isEmpty() && !name.equals(" ")) {
					infos.putIfAbsent(name, info);
				}
			}
		}

		void add(PlayerInfo info) {
			addProfileName(info);
			addDisplayName(info);
			addAliases();
		}

		@Nullable PlayerInfo get(String name) {
			return infos.get(name);
		}

		Map<Integer, List<String>> byFirstCharacter() {
			Map<Integer, List<String>> out = new HashMap<>();
			for (String name : infos.keySet()) {
				out.computeIfAbsent(name.codePointAt(0), k -> new ArrayList<>()).add(name);
			}
			return out;
		}
	}

	/** The message as a flat list of single-style pieces (its literal text split at each style change). */
	private static List<Component> split(Component component) {
		List<Component> out = new ArrayList<>();
		walk(component, Style.EMPTY, out);
		return out;
	}

	private static void walk(Component component, Style parent, List<Component> out) {
		Style style = component.getStyle().applyTo(parent);
		if (component.getContents() instanceof PlainTextContents literal) {
			Style[] previous = {style};
			StringBuilder text = new StringBuilder();
			StringDecomposer.iterateFormatted(literal.text(), style, (index, s, codePoint) -> {
				if (!s.equals(previous[0])) {
					if (!text.isEmpty()) {
						out.add(Component.literal(text.toString()).setStyle(previous[0]));
					}
					text.setLength(0);
					previous[0] = s;
				}
				text.appendCodePoint(codePoint);
				return true;
			});
			if (!text.isEmpty()) {
				out.add(Component.literal(text.toString()).setStyle(previous[0]));
			}
		} else {
			out.add(component.plainCopy().setStyle(style));
		}
		for (Component sibling : component.getSiblings()) {
			walk(sibling, style, out);
		}
	}

	private static Component join(List<Component> parts) {
		if (parts.size() == 1) {
			return parts.getFirst();
		}
		MutableComponent out = Component.empty();
		for (Component part : parts) {
			out.getSiblings().add(part);
		}
		return out;
	}

	private static boolean containsPlayerSprite(List<Component> parts) {
		for (Component part : parts) {
			if (part.getContents() instanceof ObjectContents object && object.contents() instanceof PlayerSprite) {
				return true;
			}
		}
		return false;
	}

	/** A name with a "/tell Name" suggestion on click (many servers' chat formats): its player's head before it. */
	private static @Nullable PlayerInfo headForTell(List<Component> parts, Names names) {
		for (int i = 0; i < parts.size(); i++) {
			Component part = parts.get(i);
			String receiver = tellReceiver(part);
			if (receiver != null) {
				PlayerInfo info = names.get(receiver);
				if (info != null) {
					parts.set(i, Component.empty().append(head(info, part)).append(part));
					return info;
				}
			}
			if (part.getContents() instanceof TranslatableContents translatable) {
				int index = i;
				PlayerInfo info = translatableArgs(part, translatable, arg -> headForTell(arg, names), done -> parts.set(index, done));
				if (info != null) {
					return info;
				}
			}
		}
		return null;
	}

	private static @Nullable String tellReceiver(Component part) {
		if (part.getStyle().getClickEvent() instanceof ClickEvent.SuggestCommand suggest) {
			String command = suggest.command();
			if (command != null && command.startsWith("/tell ")) {
				return command.substring("/tell ".length()).trim();
			}
		}
		return null;
	}

	/** Runs {@code search} over a translatable's arguments (just the name for a "<Name> message" line); the first hit is kept. */
	private static <T> @Nullable T translatableArgs(Component translatable, TranslatableContents contents, Function<List<Component>, T> search,
		java.util.function.Consumer<Component> done) {
		Object[] args = contents.getArgs();
		int count = Objects.equals(contents.getKey(), "chat.type.text") ? Math.min(1, args.length) : args.length;
		for (int i = 0; i < count; i++) {
			if (args[i] instanceof String text) {
				args[i] = Component.literal(text);
			}
			if (args[i] instanceof Component arg) {
				List<Component> parts = split(arg);
				T result = search.apply(parts);
				if (result != null) {
					Object[] changed = Arrays.copyOf(args, args.length);
					changed[i] = join(parts);
					MutableComponent out = Component.translatableWithFallback(contents.getKey(), contents.getFallback(), changed);
					out.setStyle(translatable.getStyle());
					translatable.getSiblings().forEach(out::append);
					done.accept(out);
					return result;
				}
			}
		}
		return null;
	}

	/** The first player name (or nickname) in the message's text: its player's head goes right before it. */
	private static @Nullable PlayerInfo headForName(List<Component> parts, Names names) {
		StringBuilder text = new StringBuilder();
		int start = -1;
		for (int i = 0; i < parts.size(); i++) {
			ComponentContents contents = parts.get(i).getContents();
			if (contents instanceof PlainTextContents literal) {
				if (start < 0) {
					start = i;
				}
				text.append(literal.text());
				continue;
			}
			if (!text.isEmpty()) {
				PlayerInfo found = headInRun(parts, start, i - 1, text.toString(), names);
				if (found != null) {
					return found;
				}
			}
			text.setLength(0);
			start = -1;
			if (contents instanceof TranslatableContents translatable) {
				int index = i;
				PlayerInfo found = translatableArgs(parts.get(i), translatable, arg -> headForName(arg, names), done -> parts.set(index, done));
				if (found != null) {
					return found;
				}
			}
		}
		return text.isEmpty() ? null : headInRun(parts, start, parts.size() - 1, text.toString(), names);
	}

	private static @Nullable PlayerInfo headInRun(List<Component> parts, int first, int last, String text, Names names) {
		int at = -1;
		PlayerInfo info = null;
		Map<Integer, List<String>> byFirst = names.byFirstCharacter();
		boolean insideWord = false;
		int[] seq = text.codePoints().toArray();
		search:
		for (int i = 0; i < seq.length; i++) {
			int c = seq[i];
			if (insideWord && isWordCharacter(c)) {
				continue;
			}
			for (String name : byFirst.getOrDefault(c, List.of())) {
				int[] nameSeq = name.codePoints().toArray();
				if (i + nameSeq.length - 1 >= seq.length) {
					continue;
				}
				boolean endsAsWord = isWordCharacter(nameSeq[nameSeq.length - 1]);
				boolean followedByWord = i + nameSeq.length < seq.length && isWordCharacter(seq[i + nameSeq.length]);
				if ((!endsAsWord || !followedByWord) && Arrays.equals(seq, i, i + nameSeq.length, nameSeq, 0, nameSeq.length)) {
					at = i;
					info = names.get(name);
					break search;
				}
			}
			insideWord = isWordCharacter(c);
		}
		if (info == null) {
			return null;
		}
		for (int i = first; i <= last; i++) {
			Component literal = parts.get(i);
			String partText = ((PlainTextContents) literal.getContents()).text();
			int count = (int) partText.codePoints().count();
			if (at < count) {
				MutableComponent head = head(info, literal);
				Component decorated;
				if (at == 0) {
					decorated = Component.empty().append(head).append(literal);
				} else {
					int split = partText.offsetByCodePoints(0, at);
					MutableComponent left = Component.literal(partText.substring(0, split)).setStyle(literal.getStyle());
					MutableComponent right = Component.literal(partText.substring(split)).setStyle(literal.getStyle());
					literal.getSiblings().forEach(right::append);
					decorated = Component.empty().append(left).append(head).append(right);
				}
				parts.set(i, decorated);
				return info;
			}
			at -= count;
		}
		return null;
	}

	private static boolean isWordCharacter(int codePoint) {
		return Character.isLetterOrDigit(codePoint) || codePoint == '_' || Character.getNumericValue(codePoint) != -1;
	}

	/** {@code info}'s head as a glyph (struck through with a struck-through message), left out of the chat log. */
	private static MutableComponent head(PlayerInfo info, Component message) {
		PlayerSprite sprite = new PlayerSprite(ResolvableProfile.createResolved(info.getProfile()), info.showHat());
		((HiddenInLog) (Object) sprite).flint$hideInLog();
		MutableComponent head = Component.object(sprite).withStyle(ChatFormatting.WHITE);
		return message.getStyle().isStrikethrough() ? head.withStyle(ChatFormatting.STRIKETHROUGH) : head;
	}

	/** A head glyph Flint made: the chat log writes nothing for it. */
	public interface HiddenInLog {
		void flint$hideInLog();

		boolean flint$hiddenInLog();
	}

	/** The chat log is being written (heads leave no "[Name head]" in it). */
	public static void insideLog(boolean inside) {
		insideLog = inside;
	}

	public static boolean hideInLog(Object sprite) {
		return insideLog && sprite instanceof HiddenInLog hidden && hidden.flint$hiddenInLog();
	}

	// ---- Lines without a head line up with those that have one (Before Line).

	/** How far a line's text sits right: a head's width for lines not starting with one, when Offset non-player text is on. */
	public static int lineOffset(GuiMessage.Line line) {
		if (!offsetEnabled()) {
			return 0;
		}
		try {
			return startsWithHead(line) ? 0 : headWidth(Mods.state(CHAT));
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return 0;
		}
	}

	/** How much wider a line's background is (the offset lines reach that much further right). */
	public static int widthDifference() {
		return offsetEnabled() ? headWidth(Mods.state(CHAT)) : 0;
	}

	private static boolean offsetEnabled() {
		if (!on() || serverDisabled) {
			return false;
		}
		ModuleState chat = Mods.state(CHAT);
		return beforeLineMode(chat) && chat.bool(Chat.ALIGN);
	}

	private static boolean startsWithHead(GuiMessage.Line line) {
		boolean[] head = new boolean[1];
		line.content().accept((position, style, codePoint) -> {
			head[0] = style.getFont() instanceof FontDescription.PlayerSprite;
			return false;
		});
		return head[0];
	}

	private static int headWidth(ModuleState chat) {
		return 8 + chat.integer(Chat.HEAD_PADDING) + Math.round(2 * chat.integer(Chat.THREE_D) / 100F);
	}

	// ---- Drawing: Minecraft's head glyph with the module's padding, 3Dness, shadow and rounding.

	/** Chat (or the GUI's text) starts ({@code true}) or stops being laid out and drawn. */
	public static void rendering(boolean now) {
		if (!now) {
			rendering = false;
			return;
		}
		boolean active = on() && !serverDisabled;
		if (!active) {
			rendering = false;
			return;
		}
		ModuleState chat = Mods.state(CHAT);
		threeDee = chat.integer(Chat.THREE_D) / 100F;
		extraAdvance = chat.integer(Chat.HEAD_PADDING) + Math.round(2 * threeDee);
		noShadow = !chat.bool(Chat.HEAD_SHADOW);
		round = chat.bool(Chat.ROUND_AVATARS);
		rendering = true;
	}

	/** The head glyph's width: Minecraft's 8 plus the padding (and the 3D hat's spread) while chat is drawn. */
	public static final class PaddedGlyph implements GlyphInfo {
		private final GlyphInfo glyph;

		public PaddedGlyph(GlyphInfo glyph) {
			this.glyph = glyph;
		}

		@Override
		public float getAdvance() {
			return glyph.getAdvance() + (rendering ? extraAdvance : 0);
		}
	}

	/** A glyph's shadow colour: none when Render shadow is off. */
	public static int shadowColor(int vanilla) {
		return rendering && noShadow ? 0 : vanilla;
	}

	/** Without its shadow, the head sits where the shadowed one's shadow would: one pixel lower (as Chat Heads draws it). */
	public static float shadowlessY(float y, int shadowColor) {
		return rendering && noShadow && shadowColor != 0 ? y + 1 : y;
	}

	/** One of the head glyph's quads (the face, u 8, or the hat, u 40); draws it through {@code quad}. */
	public interface Quad {
		void draw(float x0, float x1, float y0, float y1, float u, float v, int uw, int vh);
	}

	/** Each row's inset at either side: an 8-pixel face with its corners rounded off. */
	private static final int[] ROUND = {2, 1, 0, 0, 0, 0, 1, 2};

	/** The face, or the hat (bigger by 3Dness, as Chat Heads draws it), whole or round. */
	public static void headQuad(float x0, float x1, float y0, float y1, float u, float v, int uw, int vh, Quad quad) {
		if (rendering) {
			float t = threeDee;
			if (u < 32) {
				x0 += t;
				x1 += t;
			} else {
				x1 += 2 * t;
				y0 -= t;
				y1 += t;
			}
			if (round && uw == 8 && vh == 8) {
				float column = (x1 - x0) / 8;
				float row = (y1 - y0) / 8;
				for (int r = 0; r < 8; r++) {
					int in = ROUND[r];
					quad.draw(x0 + in * column, x1 - in * column, y0 + r * row, y0 + (r + 1) * row, u + in, v + r, 8 - 2 * in, 1);
				}
				return;
			}
		}
		quad.draw(x0, x1, y0, y1, u, v, uw, vh);
	}

	// ---- Heads in the command suggestions (a player name's suggestion shows its head).

	public static @Nullable PlayerInfo suggestionPlayer(String text) {
		if (!on()) {
			return null;
		}
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		return connection == null ? null : connection.getPlayerInfo(text);
	}

	/** Chat Heads' head (as in its suggestions list): the face and hat, at {@code x}, {@code y}, without a shadow. */
	public static void drawHead(GuiGraphicsExtractor g, int x, int y, PlayerInfo owner) {
		try {
			Identifier skin = owner.getSkin().body().texturePath();
			int color = ARGB.white(1F);
			ClientLevel level = Minecraft.getInstance().level;
			Player player = level == null ? null : level.getPlayerByUUID(owner.getProfile().id());
			boolean upsideDown = player != null && AvatarRenderer.isPlayerUpsideDown(player);
			int yOffset = upsideDown ? 8 : 0;
			int direction = upsideDown ? -1 : 1;
			float t = Mods.state(CHAT).integer(Chat.THREE_D) / 100F;
			g.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 8, 8 + yOffset, 8, 8, 8, direction * 8, 64, 64, color);
			if (owner.showHat()) {
				Matrix3x2fStack pose = g.pose();
				if (t != 0) {
					pose.pushMatrix();
					pose.scaleAround(1 + t * 0.25F, x + 4F, y + 4F);
				}
				g.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 40, 8 + yOffset, 8, 8, 8, direction * 8, 64, 64, color);
				if (t != 0) {
					pose.popMatrix();
				}
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}
}
