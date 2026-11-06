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
	private static final int MAX_SHOWN = 3;
	private static final int MAX_WAITING = 8;
	/** Sizes in GUI units, as the newer lines' toast. */
	private static final float HEIGHT = 20;
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

		Card(String text, long since) {
			this.text = new TextLine(FlintFonts.SMALL, text);
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
	static void show(String text) {
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

	/** After the frame is drawn ({@code mixin/GameRendererMixin}): the cards, over everything. */
	public static void draw(Minecraft mc) {
		if (!GUARD.on() || WAITING.isEmpty() && SHOWN[0] == null && SHOWN[1] == null && SHOWN[2] == null) {
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
		MenuTheme.Look look = Shared.settings().menuTheme().look();
		int border = 0xFF000000 | look.surface4;
		int fill = 0xF2000000 | look.surface1;
		int ink = Palette.lerp(0xFF000000 | look.surface1, 0xFF000000 | look.ink, 0.85F);
		Canvas c = Canvas.begin(Frame.get());
		// Minecraft's font at the game's own interface scale, as Minecraft's own text.
		int fontMode = PixelFace.begin(c.guiScale());
		try {
			int h = c.px(HEIGHT);
			int markH = c.px(MARK);
			int markW = MaskAtlas.markWidth(markH);
			int pad = c.px(PAD);
			int maxText = c.px(MAX_TEXT);
			int borderPx = Math.max(1, c.px(0.4F));
			for (int i = 0; i < MAX_SHOWN; i++) {
				Card card = SHOWN[i];
				if (card == null) {
					continue;
				}
				int textW = c.width(card.text, maxText);
				int w = pad + markW + c.px(GAP) + textW + pad;
				int y = c.px(MARGIN + below) + i * (h + c.px(SPACING));
				// Slides in from the right edge, and back out at its end.
				long age = now - card.since;
				float in = ease(Math.min(1.0F, age / (float) SLIDE_MILLIS)) * ease(Math.min(1.0F, (SHOW_MILLIS - age) / (float) SLIDE_MILLIS));
				int x = mc.width - c.px(MARGIN) - w + Math.round((1 - in) * (w + c.px(MARGIN)));
				c.panel(x, y, w, h, c.px(5), borderPx, border, fill);
				c.mark(x + pad, y + (h - markH) / 2, markH, 1.0F);
				c.text(card.text, x + pad + markW + c.px(GAP), c.baselineFor(card.text, y + h / 2), ink, maxText);
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
