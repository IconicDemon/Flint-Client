package com.flintclient.legacy;

import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.model.MenuTheme;
import com.flintclient.client.control.platform.HookGuard;
import com.flintclient.client.control.rshift.FlintFriendsScreen;
import com.flintclient.client.module.account.LauncherLink;
import com.flintclient.client.module.social.FriendsClient;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.MaskAtlas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.PixelFace;
import com.flintclient.client.render.TextLine;
import com.flintclient.legacy.mixin.ToastGuiAccessor;
import com.flintclient.legacy.shared.Shared;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;

/**
 * Flint Friends' notices in Minecraft 1.8.9, which has no toasts: a friend's message, a friend request, a request accepted, a
 * friend coming online, each a small card at the top right as Flint's toasts are on the newer lines (Flint's mark and one line
 * in Minecraft's font, in the menu theme's colours). A card slides in, stays four seconds and slides out; at most three show
 * at once, the rest wait their turn. Who and what only, never a message's text. With Friends notifications on (the Friends
 * window's settings, {@link GameOptions#FRIEND_NOTICES}) the Flint app is asked every 15 s while playing
 * ({@link FriendsClient}); never without the game link, and none are shown while the Friends window is open (it shows them
 * already). Over everything the frame drew, in a world or a menu, below Minecraft's achievement pop-up while it shows; hidden
 * with the HUD (F1) in a world. Fault-isolated: a
 * failure turns the notices off for the session, never the game. Render thread.
 */
public final class FriendNotices189 {
	private static final long SHOW_MILLIS = 4000;
	private static final long SLIDE_MILLIS = 250;
	private static final int MAX_SHOWN = 2;
	private static final int MAX_WAITING = 8;
	/** Sizes in GUI units, as the newer lines' toast. */
	private static final float HEIGHT = com.flintclient.client.render.NoticeStyle.HEIGHT;
	private static final float MARK = 10;
	private static final float PAD = 7;
	private static final float GAP = 6;
	private static final float MARGIN = 4;
	private static final float SPACING = 3;
	private static final float MAX_TEXT = 200;
	/** Minecraft's achievement pop-up, at the top right too: the cards move down below it while it shows. */
	private static final float ACHIEVEMENT = 32;
	private static final long ACHIEVEMENT_MILLIS = 3000;
	private static final HookGuard GUARD = new HookGuard("Friends notices",
		(path, error) -> Flint.LOG.warn("Flint: the {} hook failed; it's off for this session", path, error));

	private static final ArrayDeque<String> WAITING = new ArrayDeque<>();
	private static final Card[] SHOWN = new Card[MAX_SHOWN];
	private static int count;
	/** How far down the cards are (GUI units), easing to below the achievement pop-up and back. */
	private static float below;
	private static long lastFrame;

	/** One card on screen: its line, and when it first showed. */
	private static final class Card {
		final TextLine text;
		final long since;

		final TextLine secondary;

		Card(String text, long since) {
			int dot = text.indexOf(" · ");
			this.text = new TextLine(FlintFonts.SMALL, dot > 0 ? text.substring(0, dot) : text);
			this.secondary = dot > 0 ? new TextLine(FlintFonts.SMALL, text.substring(dot + 1)) : null;
			this.since = since;
		}
	}

	private FriendNotices189() {
	}

	/** Every client tick: asks the Flint app while playing, and takes what changed as cards. */
	static void tick(Minecraft mc) {
		if (!GUARD.on() || LauncherLink.get() == null) {
			return;
		}
		try {
			boolean on = Shared.settings().gameBool(GameOptions.FRIEND_NOTICES);
			FriendsClient client = FriendsClient.existing();
			if (client == null) {
				if (!on || mc.world == null) {
					return;
				}
				client = FriendsClient.get(FlintFriendsScreen::playing);
			}
			if (on && mc.world != null) {
				client.want();
			}
			client.tick();
			var notices = client.takeNotices();
			if (!on || mc.screen instanceof FlintFriendsScreen) {
				return;
			}
			for (FriendsClient.Notice notice : notices) {
				show(notice.text());
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** Queues a card saying {@code text} (one line, no formatting codes). Render thread. */
	/** Every Flint notice on 1.8.9 (Friends, files, a mod turned off after an error) goes through here: one style, two at most. */
	public static void show(String text) {
		// Minecraft's font reads a section sign as a formatting code.
		String plain = text.replace(String.valueOf((char) 0xA7), "").strip();
		if (plain.isEmpty()) {
			return;
		}
		if (WAITING.size() >= MAX_WAITING) {
			WAITING.removeFirst();
		}
		WAITING.addLast(plain);
	}

	/** Cards shown this session (QA reads it). */
	public static int count() {
		return count;
	}

	/** Whether no card is on screen; goes by {@link #MAX_SHOWN}, so it can't index past the slots. */
	private static boolean noneShown() {
		for (Card card : SHOWN) {
			if (card != null) {
				return false;
			}
		}
		return true;
	}

	/** After the frame is drawn ({@code mixin/GameRendererMixin}): the cards, over everything. */
	public static void draw(Minecraft mc) {
		if (!GUARD.on() || WAITING.isEmpty() && noneShown()) {
			return;
		}
		try {
			long now = System.currentTimeMillis();
			for (int i = 0; i < MAX_SHOWN; i++) {
				if (SHOWN[i] != null && now - SHOWN[i].since >= SHOW_MILLIS) {
					SHOWN[i] = null;
				}
				if (SHOWN[i] == null && !WAITING.isEmpty()) {
					SHOWN[i] = new Card(WAITING.removeFirst(), now);
					count++;
				}
			}
			float target = achievementShown(mc) ? ACHIEVEMENT : 0;
			float step = Math.min(1.0F, Math.max(0, now - lastFrame) / 120.0F);
			below = lastFrame == 0 ? target : below + (target - below) * step;
			lastFrame = now;
			if (mc.screen == null && mc.world != null && mc.options.hideGui) {
				return;
			}
			paint(mc, now);
		} catch (RuntimeException | LinkageError e) {
			WAITING.clear();
			java.util.Arrays.fill(SHOWN, null);
			GUARD.fail(e);
		}
	}

	private static void paint(Minecraft mc, long now) {
		Canvas c = Canvas.begin(Frame.get());
		// Minecraft's font at the game's own interface scale, as Minecraft's own text.
		int fontMode = PixelFace.begin(c.guiScale());
		try {
			int h = c.px(HEIGHT);
			int margin = c.px(MARGIN);
			int chrome = com.flintclient.client.render.NoticeStyle.chromeWidth(c, h);
			int gap = c.px(3);
			for (int i = 0; i < MAX_SHOWN; i++) {
				Card card = SHOWN[i];
				if (card == null) {
					continue;
				}
				// Flint's one notification style (NoticeStyle): a slim translucent pill that eases in, fades out and never leaves
				// the screen (at most its width less a margin either side; a long name is cut short).
				int wanted = chrome + c.width(card.text) + (card.secondary != null ? gap + c.width(card.secondary) : 0);
				int w = com.flintclient.client.render.NoticeStyle.fitWidth(wanted, mc.width, margin);
				int y = c.px(MARGIN + below) + i * (h + c.px(SPACING));
				long age = now - card.since;
				float alpha = com.flintclient.client.render.NoticeStyle.alpha(age, SHOW_MILLIS);
				float in = ease(Math.min(1.0F, age / (float) SLIDE_MILLIS));
				int x = mc.width - margin - w + Math.round((1 - in) * c.px(12));
				int tx = com.flintclient.client.render.NoticeStyle.pill(c, x, y, w, h, alpha);
				int room = x + w - c.px(com.flintclient.client.render.NoticeStyle.PAD) - tx;
				int cy = y + h / 2;
				int mainW = Math.min(c.width(card.text), room);
				c.text(card.text, tx, c.baselineFor(card.text, cy), com.flintclient.client.render.NoticeStyle.fade(com.flintclient.client.render.NoticeStyle.TEXT, alpha), room);
				if (card.secondary != null && room - mainW - gap > c.px(8)) {
					c.text(card.secondary, tx + mainW + gap, c.baselineFor(card.secondary, cy),
						com.flintclient.client.render.NoticeStyle.fade(com.flintclient.client.render.NoticeStyle.SECONDARY, alpha), room - mainW - gap);
				}
			}
		} finally {
			PixelFace.end(fontMode);
			c.end();
		}
	}

	/** Minecraft's achievement pop-up is up (an achievement for three seconds; a tutorial hint until it is done). */
	private static boolean achievementShown(Minecraft mc) {
		if (mc.toast == null || mc.player == null) {
			return false;
		}
		ToastGuiAccessor toast = (ToastGuiAccessor) mc.toast;
		long since = toast.flint$startTime();
		if (toast.flint$achievement() == null || since == 0) {
			return false;
		}
		long age = Minecraft.getTime() - since;
		return toast.flint$tutorial() || age >= 0 && age <= ACHIEVEMENT_MILLIS;
	}

	/** Ease-out (cubic). */
	private static float ease(float t) {
		float u = 1 - Math.max(0, t);
		return 1 - u * u * u;
	}
}
