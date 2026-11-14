package com.flintclient.client.ui.widget;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.module.social.MenuArt;
import com.flintclient.client.module.social.MenuLink.Community;
import com.flintclient.client.module.social.MenuLink.Reward;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.PixelFace;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.Motion;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The main menu's "Join our Discord" card, above Daily Rewards: the player's own skin as a 3D player wearing the cape Flint's
 * Discord server gives its members, turning slowly so the cape shows, with a FREE COSMETICS tag. It is a preview, and says so:
 * the cape is drawn on the player here only, nothing is worn or owned by looking at it (the Store decides that, after Discord
 * says the player joined). A click opens the server's invite after Minecraft's link confirmation. Once the player's linked
 * Discord account is in Flint's server, the card goes calm ("You're in!") and opens the Cosmetics window instead.
 *
 * <p>The rewards and the invite are the Flint app's (its Store session; {@code community} on the game link); the card shows only
 * while the Store lists a Discord reward. Where this Minecraft can't draw a player from a render state (1.21.1), or while the
 * cape's picture loads, the cape's front stands in. Text in Minecraft's font.
 */
public final class DiscordCard extends FlintButton {
	/** One turn of the player, in milliseconds. */
	private static final float TURN_MILLIS = 14_000.0F;
	/** Where the player rests with motion reduced: from behind and a little to the side, the cape in view. */
	private static final float RESTING_YAW = 150.0F;
	/** How far the player stands out above the card, in menu units (the menu keeps that room free: MenuGeometry.DISCORD_POP). */
	public static final float POP = 12.0F;

	private final Supplier<@Nullable Community> community;
	private final TextLine tag = new TextLine(PixelFace.of(FlintFonts.CAPS.size), "FREE COSMETICS");
	private final TextLine title = new TextLine(PixelFace.of(FlintFonts.LABEL.size), "");
	private final TextLine summary = new TextLine(PixelFace.of(FlintFonts.SMALL.size), "");
	private final TextLine summary2 = new TextLine(PixelFace.of(FlintFonts.SMALL.size), "");
	private int wrappedFor = -1;
	private String shownTitle = "";
	private String shownSummary = "";
	private boolean previewFailed;
	private final long shownAt = Clock.millis();

	public DiscordCard(Supplier<@Nullable Community> community, Runnable action) {
		super(Component.translatable("flintclient.discord.open"), action);
		this.community = community;
		tooltip(Component.translatable("flintclient.discord.tooltip"));
	}

	/** Whether the card has something to show: the Store lists a Discord reward for this account. */
	public static boolean shows(@Nullable Community c) {
		return c != null && c.discordEnabled() && !"off".equals(c.state()) && !c.discordRewards().isEmpty();
	}

	/** The linked Discord account is in Flint's server, and its rewards are the player's. */
	public static boolean joined(@Nullable Community c) {
		return c != null && c.member() && c.discordRewards().stream().anyMatch(Reward::unlocked);
	}

	/** The reward the card dresses the player in: the first Discord cape. */
	static @Nullable Reward cape(@Nullable Community c) {
		if (c == null) {
			return null;
		}
		for (Reward r : c.discordRewards()) {
			if ("cape".equals(r.kind())) {
				return r;
			}
		}
		return null;
	}

	private boolean broken;

	/** A fault while drawing takes the card off the menu (logged once); the menu itself carries on. */
	@Override
	protected void paint(Canvas c) {
		if (broken) {
			return;
		}
		try {
			paintCard(c);
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			active = false;
			visible = false;
			com.flintclient.client.FlintClient.LOGGER.warn("Flint: the main menu's " + getClass().getSimpleName() + " failed and is hidden", e);
		}
	}

	private void paintCard(Canvas c) {
		Community now = community.get();
		boolean joined = joined(now);
		Reward cape = cape(now);
		refresh(now, joined, cape);

		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int h = c.px(getHeight());
		int borderPx = Math.max(1, Math.round(c.scale() / 4.0F));
		int radius = c.px(3.2F);
		int fill = Palette.lerp(Palette.SURFACE, Palette.SURFACE_HOVER, hover * 0.7F);
		int border = Palette.lerp(Palette.lerp(Palette.BORDER, Palette.BORDER_HOVER, hover), Palette.ACCENT, focus * 0.85F);
		c.panel(x, y, w, h, radius, borderPx + (focus > 0.5F ? borderPx : 0), border, fill);

		// The player, on the left: a quiet stage under it.
		int stageW = Math.round(h * 0.62F);
		int stageX = x + c.px(2.2F);
		int stageY = y + c.px(2.2F);
		int stageH = h - 2 * c.px(2.2F);
		c.roundRect(stageX, stageY, stageW, stageH, Math.max(1, radius - c.px(1.2F)), 0x2A000000);
		Texture capeTexture = null;
		MenuArt.Entry art = null;
		if (cape != null) {
			String id = cape.id();
			art = MenuArt.png("reward/" + id + "/" + cape.art(), link -> link.rewardArt(id));
			capeTexture = art.texture();
		}
		boolean drawn = false;
		if (capeTexture != null && !previewFailed) {
			// The player stands on the stage's floor and out above the card, head and shoulders over its edge.
			int pop = c.px(POP);
			drawn = player(c, capeTexture, stageX - c.px(2), stageY - pop, stageW + c.px(4), stageH + pop);
		}
		if (!drawn && capeTexture != null && art.width() >= 64) {
			capeFront(c, capeTexture, art.width(), art.height(), stageX, stageY, stageW, stageH);
		}

		// The words, on the right.
		int left = stageX + stageW + c.px(5);
		int right = x + w - c.px(NewsCard.PAD_X) + c.px(1.5F);
		if (joined) {
			int d = c.px(5.5F);
			c.roundRect(right - d, y + c.px(4.5F), d, d, d / 2, 0xFF3FBF4A);
			c.icon(Icon.CHECK, right - d + c.px(0.9F), y + c.px(4.5F) + c.px(0.9F), d - 2 * c.px(0.9F), 0xFF0B1A0C);
		} else {
			int tw = c.width(tag) + c.px(3.6F);
			int th = c.px(6.4F);
			int tx = right - tw;
			int ty = y + c.px(4.2F);
			c.roundRect(tx, ty, tw, th, th / 2, Palette.ACCENT);
			c.text(tag, tx + c.px(1.8F), c.baselineFor(tag, ty + th / 2), Palette.ACCENT_INK);
		}
		int inner = right - left;
		c.text(title, left, y + c.px(18.5F), Palette.TEXT, inner);
		// The summary on up to two lines, the second clear of the arrow.
		int key = inner * 31 + shownSummary.hashCode();
		if (key != wrappedFor) {
			wrappedFor = key;
			wrap(c, shownSummary, inner);
		}
		int ink = Palette.lerp(Palette.MUTED, Palette.TEXT_DIM, hover);
		c.text(summary, left, y + c.px(26.4F), ink, inner);
		c.text(summary2, left, y + c.px(32.6F), ink, inner - c.px(6.5F));
		int arrow = c.px(4.2F);
		float nudge = Motion.smooth(hover) * c.px(1.0F);
		c.icon(Icon.CHEVRON_RIGHT, right - arrow + Math.round(nudge), y + h - c.px(5.0F) - arrow, arrow,
			Palette.lerp(Palette.MUTED, Palette.TEXT, Math.max(hover, focus)));
	}

	/** {@code text} on two lines: as many words as fit {@code maxPx} on the first, the rest on the second (cut short there). */
	private void wrap(Canvas c, String text, int maxPx) {
		String[] words = text.split(" ");
		StringBuilder first = new StringBuilder();
		int i = 0;
		for (; i < words.length; i++) {
			String next = first.isEmpty() ? words[i] : first + " " + words[i];
			summary.set(next);
			if (c.width(summary) > maxPx && !first.isEmpty()) {
				break;
			}
			first.setLength(0);
			first.append(next);
		}
		summary.set(first.toString());
		summary2.set(i < words.length ? String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)) : "");
	}

	/** What the card says for the community view as it is now (remade only when it changes). */
	private void refresh(@Nullable Community now, boolean joined, @Nullable Reward cape) {
		String t;
		String s;
		if (joined) {
			t = "You're in!";
			s = "Your Discord rewards are unlocked";
		} else if (now != null && now.pending()) {
			t = "Almost in";
			s = "Finish joining Flint's Discord";
		} else {
			t = "Join our Discord";
			List<Reward> rewards = now == null ? List.of() : now.discordRewards();
			// The player wears it here as a preview only, and the card says so.
			s = cape != null ? "Preview: " + cape.name() : rewards.size() + " free cosmetics for members";
		}
		if (!t.equals(shownTitle)) {
			shownTitle = t;
			title.set(t);
		}
		if (!s.equals(shownSummary)) {
			shownSummary = s;
			wrappedFor = -1;
		}
	}

	/**
	 * The player wearing the cape, turning slowly (resting from behind with motion reduced): Minecraft's own player renderer at
	 * the GUI's scale. False (and the cape's front stands in from then on) where this Minecraft can't.
	 */
	private boolean player(Canvas c, Texture cape, int x, int y, int w, int h) {
		float yaw = Motion.reduced() ? RESTING_YAW : (Clock.millis() - shownAt) % (long) TURN_MILLIS / TURN_MILLIS * 360.0F + 120.0F;
		float lean = Motion.reduced() ? 6.0F : 6.0F + 5.0F * (float) Math.sin((Clock.millis() - shownAt) / 900.0);
		try {
			return McCompat.previewPlayer(c.frame(), cape, x, y, w, h, yaw, lean);
		} catch (RuntimeException | LinkageError e) {
			previewFailed = true;
			com.flintclient.client.FlintClient.LOGGER.warn("Flint: the Discord card's player preview failed; it shows the cape instead", e);
			return false;
		}
	}

	/** The cape's front (the part a player sees from behind), centred on the stage, as its own pixels. */
	private static void capeFront(Canvas c, Texture cape, int texW, int texH, int x, int y, int w, int h) {
		int unitX = Math.max(1, texW / 64);
		int unitY = Math.max(1, texH / 32);
		int fh = Math.round(h * 0.78F);
		int fw = Math.round(fh * 10.0F / 16.0F);
		c.textureRegion(cape, x + (w - fw) / 2, y + (h - fh) / 2, fw, fh, unitX, unitY, 10 * unitX, 16 * unitY, texW, texH, 0xFFFFFFFF);
	}

}
