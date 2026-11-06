package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.ChatBubbles;
import com.flintclient.client.control.feature.FlintPlayers;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.NametagRules;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.Nametags;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's side of Nametags (26.3 and 26.2; each version makes its own text parts from {@link Look}): your own nametag in
 * third person ({@link #own}, by Minecraft's own rules: {@link NametagRules}), hiding them by key, named mobs and pets always
 * named, ping after players' names, your own colour, how a nametag is drawn (size, shadow, opacity, background, rounded
 * corners, border, the hurt tint) and players' chat bubbles above it. Minecraft's visibility rules (teams, invisibility,
 * F1) are always applied first: Flint decorates the nametag Minecraft shows and never adds one beside it. Render thread;
 * fault-isolated (a failing path leaves Minecraft's nametag as it is).
 */
public final class Mc263Nametags {
	private static final int MODULE = Mods.index(Nametags.ID);
	private static final HookGuard GUARD = new HookGuard("nametags",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static boolean allHidden;
	private static boolean playersHidden;
	private static boolean allKeyWas;
	private static boolean playersKeyWas;
	/** What Minecraft's living-entity rules said last (a mob's renderer asks them first). */
	private static boolean lastLiving;
	private static final HookGuard BADGE = new HookGuard("Flint nametag icon",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final HookGuard OWN = new HookGuard("own nametag",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	/** How many pictures of an entity are being drawn now (the inventory's player, a HUD's): no own nametag on those. */
	private static int pictures;
	/** Each Flint mark as one glyph of Flint's badge font, in its own colours (white leaves them as they are), by tag. */
	private static final Component[] MARKS = new Component[FlintPlayers.Tag.values().length];
	/** A nametag background's corner radius and a chat bubble's, in text pixels. */
	private static final float TAG_RADIUS = 3;
	private static final float BUBBLE_RADIUS = 4;
	/** Whose nametag is being drawn now (null: a label that isn't an entity's). */
	private static @Nullable EntityRenderState drawing;
	/** How strongly the chat bubble being drawn now shows; NaN while a nametag is drawn. */
	private static float bubbleAlpha = Float.NaN;
	private static final ChatBubbles BUBBLES = new ChatBubbles();
	private static long prunedAt;

	static {
		Style badge = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "badge"))).withColor(0xFFFFFF);
		for (FlintPlayers.Tag tag : FlintPlayers.Tag.values()) {
			MARKS[tag.ordinal()] = Component.literal(String.valueOf(tag.glyph())).withStyle(badge);
		}
	}

	private Mc263Nametags() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(MODULE);
	}

	private static boolean bubblesOn() {
		return on() && Mods.state(MODULE).bool(Nametags.BUBBLES);
	}

	/** From the client tick: the toggle keys (only while playing, never while typing). */
	public static void tick() {
		if (!on()) {
			allKeyWas = false;
			playersKeyWas = false;
			return;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			Minecraft mc = Minecraft.getInstance();
			boolean play = mc.player != null && mc.gui.screen() == null;
			boolean all = play && Mc263Features.keyHeld(state.text(Nametags.TOGGLE_ALL));
			if (all && !allKeyWas) {
				allHidden = !allHidden;
				message(state, allHidden ? "Nametags hidden" : "Nametags shown");
			}
			allKeyWas = all;
			boolean players = play && Mc263Features.keyHeld(state.text(Nametags.TOGGLE_PLAYERS));
			if (players && !playersKeyWas) {
				playersHidden = !playersHidden;
				message(state, playersHidden ? "Player nametags hidden" : "Player nametags shown");
			}
			playersKeyWas = players;
			if (state.text(Nametags.TOGGLE_ALL).isEmpty()) {
				allHidden = false;
			}
			if (state.text(Nametags.TOGGLE_PLAYERS).isEmpty()) {
				playersHidden = false;
			}
			long now = System.currentTimeMillis();
			if (now - prunedAt > 5000 && !BUBBLES.isEmpty()) {
				// Players who left or aren't drawn keep nothing past the longest a bubble can stay.
				prunedAt = now;
				BUBBLES.prune(now, 25_000);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static void message(ModuleState state, String text) {
		if (state.bool(Nametags.TOGGLE_MESSAGE)) {
			com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal(text), false);
		}
	}

	/**
	 * Your own nametag over you in third person, "Nametag in perspective" on: shown when Minecraft's rules for another player's
	 * nametag would show yours ({@link NametagRules#ownShown}: your team's nametag visibility, invisibility, nothing riding you,
	 * F1). {@code vanilla} is Minecraft's answer (it never shows the camera's own entity), kept for every other entity;
	 * {@code tooFar} Minecraft's distance rule (sneaking past 32 blocks). Minecraft's answer on a failure.
	 */
	public static boolean own(LivingEntity entity, boolean tooFar, boolean vanilla) {
		if (vanilla || !OWN.on() || !on()) {
			return vanilla;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			net.minecraft.client.player.LocalPlayer you = mc.player;
			if (entity != you || !Mods.state(MODULE).bool(Nametags.OWN)) {
				return vanilla;
			}
			net.minecraft.world.scores.Team team = entity.getTeam();
			NametagRules.TeamRule rule = team == null ? null : NametagRules.TeamRule.of(team.getNameTagVisibility().name());
			return NametagRules.ownShown(true, !mc.options.getCameraType().isFirstPerson(), com.flintclient.client.compat.McCompat.hud().isHidden(),
				pictures == 0, tooFar, !entity.isInvisibleTo(you), entity.isVehicle(), rule, team != null && team.canSeeFriendlyInvisibles());
		} catch (RuntimeException | LinkageError e) {
			OWN.fail(e);
			return vanilla;
		}
	}

	/** Around a picture of an entity drawn outside the world (the inventory's player, a HUD's): {@code drawing} true before, false after. */
	public static void picture(boolean drawing) {
		pictures = Math.max(0, pictures + (drawing ? 1 : -1));
	}

	/** A living entity's nametag after Minecraft's rules ({@code vanilla}): hidden while toggled off. */
	public static boolean living(LivingEntity entity, boolean vanilla) {
		lastLiving = vanilla;
		if (!vanilla || !on()) {
			return vanilla;
		}
		return !(allHidden || playersHidden && entity instanceof Player);
	}

	/**
	 * A mob's nametag ({@code vanilla}: looked at, or always shown): named mobs, and your own named pets, always, when asked.
	 * Only where Minecraft's living rules would show it ({@link #lastLiving}: not on a hidden team, invisible or ridden), so a
	 * server's name above its own mob stays the only one; another player's pet (a server's, on a minigame) keeps Minecraft's rule.
	 */
	public static boolean mob(Entity entity, boolean vanilla) {
		if (vanilla || !on() || !lastLiving || allHidden || !entity.hasCustomName()) {
			return vanilla;
		}
		ModuleState state = Mods.state(MODULE);
		if (state.bool(Nametags.ALWAYS_NAMED)) {
			return true;
		}
		net.minecraft.client.player.LocalPlayer you = Minecraft.getInstance().player;
		return state.bool(Nametags.ALWAYS_TAMED) && entity instanceof TamableAnimal pet && pet.isTame() && you != null && pet.isOwnedBy(you);
	}

	/** A nametag's text: ping after a player's name, your own in your colour. */
	public static Component text(Entity entity, Component name) {
		if (!on()) {
			return name;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			Component out = name;
			Minecraft mc = Minecraft.getInstance();
			if (entity == mc.player && state.bool(Nametags.OWN_COLOR)) {
				out = out.copy().withColor(state.rgb(Nametags.OWN_RGB));
			}
			if (entity instanceof Player player && state.bool(Nametags.PING) && mc.getSingleplayerServer() == null) {
				ClientPacketListener connection = mc.getConnection();
				PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
				if (info != null) {
					out = out.copy().append(Component.literal(" " + info.getLatency() + "ms").withStyle(ChatFormatting.GRAY));
				}
			}
			return out;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return name;
		}
	}

	/**
	 * The Flint mark before the name of a player on Flint (you; others once Flint can know, see {@link FlintPlayers}): theirs,
	 * the default unless Flint gave them another, while Flint's "Flint icon on nametags" option is on. Independent of the
	 * Nametags mod.
	 */
	public static Component badge(Entity entity, Component name) {
		if (!(entity instanceof Player player)) {
			return name;
		}
		// Tier Tagger's tier goes right before the name, the Flint mark before both.
		Component tiered = Mc263Tiers.nametag(player, name);
		return BADGE.on() ? badge(player.getUUID(), tiered) : tiered;
	}

	/** {@link #badge(Entity, Component)} for a name in the player list (Tab). */
	public static Component tabBadge(net.minecraft.client.multiplayer.PlayerInfo info, Component name) {
		Component tiered = Mc263Tiers.playerList(info.getProfile().id(), name);
		return BADGE.on() ? badge(info.getProfile().id(), tiered) : tiered;
	}

	private static Component badge(java.util.UUID player, Component name) {
		try {
			Minecraft mc = Minecraft.getInstance();
			FlintPlayers.Tag tag = mc.player != null && player.equals(mc.player.getUUID()) ? FlintPlayers.own() : FlintPlayers.tag(player);
			if (tag == null || !com.flintclient.client.control.ControlState.settings().gameBool(com.flintclient.client.control.model.GameOptions.FLINT_BADGE)) {
				return name;
			}
			return Component.empty().append(MARKS[tag.ordinal()]).append(" ").append(name);
		} catch (RuntimeException | LinkageError e) {
			BADGE.fail(e);
			return name;
		}
	}

	/** The nametag's size factor (1: Minecraft's). */
	public static float size() {
		return on() ? Mods.state(MODULE).integer(Nametags.SIZE) / 100F : 1F;
	}

	/** Around Minecraft drawing an entity's name (and score line): whose it is, or null once done. */
	public static void drawing(@Nullable EntityRenderState state) {
		drawing = state;
	}

	/**
	 * How a nametag (or chat bubble) text part is drawn now, as {@link #look} works it out: the text colour, its background
	 * and shadow, and a shaped background (rounded corners, a border) to draw before it around the text. Reused: read it
	 * before the next part.
	 */
	public static final class Look {
		/** Nothing to draw (a bubble faded out). */
		public boolean skip;
		public int text;
		/** Minecraft's own plain background for the text (0: none). */
		public int background;
		public boolean shadow;
		/** A shaped background's fill and ring colours (alpha 0: none), corner radius and ring thickness, in text pixels. */
		public int fill;
		public int ring;
		public float radius;
		public float thickness;
		/** The shaped background's box: left and top from the text's corner, right past its width, bottom below its top. */
		public float left;
		public float top;
		public float right;
		public float bottom;

		/** Whether a shaped background goes in before the text. */
		public boolean shaped() {
			return ARGB.alpha(fill) > 0 || ARGB.alpha(ring) > 0;
		}
	}

	private static final Look LOOK = new Look();

	/**
	 * How the nametag text part Minecraft is making now ({@code color}, {@code background} and {@code mode} as Minecraft gave
	 * them) is restyled: shadow, text opacity, background, the hurt tint, rounded corners and border; a chat bubble's line as
	 * a bubble. Null: Minecraft's part as it is (Nametags off, or a failure).
	 */
	public static @Nullable Look look(int color, int background, Font.DisplayMode mode) {
		boolean bubble = !Float.isNaN(bubbleAlpha);
		if (!bubble && !on()) {
			return null;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			Look look = LOOK;
			look.skip = false;
			look.fill = 0;
			look.ring = 0;
			look.radius = 0;
			look.thickness = 0;
			if (bubble) {
				float a = bubbleAlpha * (mode == Font.DisplayMode.SEE_THROUGH ? state.integer(Nametags.BUBBLE_OBSCURED) / 100F : 1);
				look.skip = a * 255 < 4;
				look.shadow = false;
				look.background = 0;
				// Not fully opaque, so it's drawn after its background in the same group rather than with solid text.
				look.text = ARGB.color(Math.min(254, Math.round(255 * a)), state.rgb(Nametags.BUBBLE_TEXT));
				if (state.bool(Nametags.BUBBLE_BACKGROUND)) {
					look.fill = ARGB.color(Math.round(a * 255 * state.integer(Nametags.BUBBLE_BACKGROUND_OPACITY) / 100F), state.rgb(Nametags.BUBBLE_BACKGROUND_COLOR));
					look.thickness = state.bool(Nametags.BUBBLE_BORDER) ? state.decimal(Nametags.BUBBLE_BORDER_THICKNESS) : 0;
					look.ring = look.thickness > 0
						? ARGB.color(Math.round(a * 255 * state.integer(Nametags.BUBBLE_BORDER_OPACITY) / 100F), state.rgb(Nametags.BUBBLE_BORDER_COLOR)) : 0;
					look.radius = BUBBLE_RADIUS;
					box(look, -2, -2, 1, 10);
				}
				return look;
			}
			// Minecraft's see-through pass carries the background; the first, depth-tested pass of a visible tag has none.
			boolean carries = !(background == 0 && mode == Font.DisplayMode.NORMAL && color == -1);
			float textAlpha = state.integer(Nametags.OPACITY) / 100F;
			int text = ARGB.color(Math.round(ARGB.alpha(color) * textAlpha), color);
			int bg = carries && state.bool(Nametags.BACKGROUND)
				? ARGB.color(Math.round(255 * state.integer(Nametags.BACKGROUND_OPACITY) / 100F), state.rgb(Nametags.BACKGROUND_COLOR)) : 0;
			if (state.bool(Nametags.HURT) && drawing instanceof LivingEntityRenderState living && living.hasRedOverlay) {
				float k = state.integer(Nametags.HURT_STRENGTH) / 100F;
				int hurt = state.rgb(Nametags.HURT_COLOR);
				text = ARGB.color(ARGB.alpha(text), mix(text, hurt, k));
				if (bg != 0) {
					bg = ARGB.color(Math.max(ARGB.alpha(bg), Math.round(0x60 * k)), mix(bg, hurt, k * 0.6F));
				}
			}
			look.text = text;
			look.shadow = state.bool(Nametags.SHADOW) && mode != Font.DisplayMode.SEE_THROUGH;
			boolean rounded = state.bool(Nametags.ROUNDED);
			boolean border = border(state);
			if (carries && state.bool(Nametags.BACKGROUND) && (rounded || border)) {
				look.fill = bg;
				look.ring = border ? 0xFF000000 | (state.bool(Nametags.BORDER_CHROMA)
					? java.awt.Color.HSBtoRGB(System.currentTimeMillis() % 4000 / 4000F, 0.75F, 1F) : state.rgb(Nametags.BORDER_COLOR)) : 0;
				look.radius = rounded ? TAG_RADIUS : 0;
				look.thickness = border ? 1 : 0;
				box(look, -1, -1, 0, 9);
				bg = 0;
			}
			look.background = bg;
			return look;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return null;
		}
	}

	private static void box(Look look, float left, float top, float right, float bottom) {
		look.left = left;
		look.top = top;
		look.right = right;
		look.bottom = bottom;
	}

	/** Whether a nametag text part drawn in this mode gets a shadow now (a chat bubble's never does). */
	public static boolean shadow(Font.DisplayMode mode) {
		return Float.isNaN(bubbleAlpha) && on() && Mods.state(MODULE).bool(Nametags.SHADOW) && mode != Font.DisplayMode.SEE_THROUGH;
	}

	/** Whether the nametag drawn now gets a border (only with a background; players only, when asked). */
	private static boolean border(ModuleState state) {
		return state.bool(Nametags.BORDER) && state.bool(Nametags.BACKGROUND) && (!state.bool(Nametags.BORDER_PLAYERS) || drawing instanceof AvatarRenderState);
	}

	/** {@code a}'s colour moved {@code k} (0..1) of the way to {@code b}'s, as RGB. */
	private static int mix(int a, int b, float k) {
		int r = Math.round(ARGB.red(a) + (ARGB.red(b) - ARGB.red(a)) * k);
		int g = Math.round(ARGB.green(a) + (ARGB.green(b) - ARGB.green(a)) * k);
		int bl = Math.round(ARGB.blue(a) + (ARGB.blue(b) - ARGB.blue(a)) * k);
		return r << 16 | g << 8 | bl;
	}

	// ---------- chat bubbles ----------

	/** A player's render state is being filled: the chat bubbles it shows this frame, and where its nametag would sit. */
	public static void extract(LivingEntity entity, LivingEntityRenderState state, float partialTick) {
		NametagState tag = (NametagState) state;
		if (!(entity instanceof Player player) || !bubblesOn()) {
			if (!tag.flint$bubbles().isEmpty()) {
				tag.flint$bubbles(List.of(), null);
			}
			return;
		}
		try {
			ModuleState s = Mods.state(MODULE);
			List<ChatBubbles.Line> lines = BUBBLES.lines(player.getUUID(), System.currentTimeMillis(), s.integer(Nametags.BUBBLE_MAX),
				s.integer(Nametags.BUBBLE_FADE_IN), Math.round(s.decimal(Nametags.BUBBLE_DELAY) * 1000), s.integer(Nametags.BUBBLE_FADE_OUT));
			tag.flint$bubbles(lines, lines.isEmpty() ? null : entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot(partialTick)));
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			tag.flint$bubbles(List.of(), null);
		}
	}

	/** After an entity is submitted: its chat bubbles above its nametag (or where its nametag would be), newest lowest. */
	public static void bubbles(EntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (!(state instanceof LivingEntityRenderState living) || !(state instanceof NametagState tag) || tag.flint$bubbles().isEmpty()) {
			return;
		}
		Vec3 at = tag.flint$bubbleAt();
		if (at == null || !bubblesOn() || living.isInvisibleToPlayer || allHidden || playersHidden || com.flintclient.client.compat.McCompat.hud().isHidden()
			|| state.distanceToCameraSq > 64 * 64) {
			return;
		}
		try {
			ModuleState s = Mods.state(MODULE);
			float thickness = s.bool(Nametags.BUBBLE_BACKGROUND) && s.bool(Nametags.BUBBLE_BORDER) ? s.decimal(Nametags.BUBBLE_BORDER_THICKNESS) : 0;
			int pad = (int) Math.ceil(thickness);
			int step = 12 + 2 * pad + 2;
			int y = 0;
			if (state.nameTag != null || state.scoreText != null) {
				// Above the name: a bubble reaches 10 below its line, the name's background 1 above its own (2 with a border).
				int nameTop = -1 - (s.bool(Nametags.BORDER) && s.bool(Nametags.BACKGROUND) ? 1 : 0);
				y = nameTop - 2 - 10 - pad;
				if (state.nameTag != null && state.scoreText != null) {
					y -= 11;
				}
			}
			for (ChatBubbles.Line line : tag.flint$bubbles()) {
				bubbleAlpha = line.alpha();
				if (bubbleAlpha > 0.02F) {
					com.flintclient.client.compat.McCompat.submitNameTag(collector, pose, at, y, Component.literal(line.text()), !state.isDiscrete, state.lightCoords,
						state.distanceToCameraSq, camera);
				}
				y -= step;
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		} finally {
			bubbleAlpha = Float.NaN;
		}
	}

	/** A signed chat message from {@code sender} is being shown: a bubble, unless it's a private or team message. */
	public static void playerChat(UUID sender, Component content, ChatType.Bound bound) {
		if (!bubblesOn()) {
			return;
		}
		try {
			if (bound.chatType().is(ChatType.MSG_COMMAND_INCOMING) || bound.chatType().is(ChatType.MSG_COMMAND_OUTGOING)
				|| bound.chatType().is(ChatType.TEAM_MSG_COMMAND_INCOMING) || bound.chatType().is(ChatType.TEAM_MSG_COMMAND_OUTGOING)
				|| Minecraft.getInstance().getPlayerSocialManager().shouldHideMessageFrom(sender)) {
				return;
			}
			BUBBLES.add(sender, content.getString(), System.currentTimeMillis(), Mods.state(MODULE).integer(Nametags.BUBBLE_CHARS));
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** An unsigned chat message shown under {@code bound}'s name: a bubble when that name is a player's. */
	public static void disguisedChat(Component content, ChatType.Bound bound) {
		if (!bubblesOn()) {
			return;
		}
		try {
			ClientPacketListener connection = Minecraft.getInstance().getConnection();
			PlayerInfo info = connection == null ? null : connection.getPlayerInfo(bound.name().getString());
			if (info != null) {
				BUBBLES.add(info.getProfile().id(), content.getString(), System.currentTimeMillis(), Mods.state(MODULE).integer(Nametags.BUBBLE_CHARS));
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** A server message that isn't signed chat: a bubble when it reads like a player's chat line ("Steve: hi"). */
	public static void systemChat(Component message) {
		if (!bubblesOn()) {
			return;
		}
		try {
			ClientPacketListener connection = Minecraft.getInstance().getConnection();
			if (connection == null) {
				return;
			}
			String[] said = ChatBubbles.sender(message.getString(), name -> connection.getPlayerInfo(name) != null);
			if (said != null) {
				PlayerInfo info = connection.getPlayerInfo(said[0]);
				if (info != null) {
					BUBBLES.add(info.getProfile().id(), said[1], System.currentTimeMillis(), Mods.state(MODULE).integer(Nametags.BUBBLE_CHARS));
				}
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}
}
