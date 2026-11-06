package com.flintclient.client.ui.widget;

import com.flintclient.client.module.account.AccountSkin;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * One row of the main menu's account card: a picture on the left (a player's face, Microsoft's logo or an icon in a round
 * badge), a title with an optional line
 * under it, and on the right a tick for the current choice or a spinner while it works. Hover fades a soft surface in under
 * the row; a press dips it. The sign-in row is a button of its own, in Microsoft's dark sign-in style.
 */
public final class CardRow extends FlintButton {
	/** What the row shows on the left. */
	public enum Lead {
		FACE,
		ICON,
		/** Microsoft's logo: the sign-in. */
		MICROSOFT
	}

	/** What the row shows on the right. */
	public enum Trail {
		NONE,
		CHECK,
		SPINNER
	}

	/** Row heights in GUI units: with a second line, and without. */
	public static final int TALL = 19;
	public static final int SHORT = 14;
	/** Corner radius of a row's surface (the cards around rows are a step rounder). */
	public static final float RADIUS = 2.6F;
	private static final float PAD = 3.4F;
	/** Microsoft's logo colours (its sign-in branding): red, green, blue, yellow, in reading order. */
	public static final int MS_RED = 0xFFF25022;
	public static final int MS_GREEN = 0xFF7FBA00;
	public static final int MS_BLUE = 0xFF00A4EF;
	public static final int MS_YELLOW = 0xFFFFB900;
	/** Microsoft's dark sign-in button: its fill, and a step lighter under the pointer. */
	private static final int MS_FILL = 0xFF2F2F2F;
	private static final int MS_FILL_HOVER = 0xFF3B3B3B;

	private final Lead lead;
	private final @Nullable String account;
	private final @Nullable Icon icon;
	private final boolean accentIcon;
	private final TextLine title;
	private final @Nullable TextLine detail;
	private final Supplier<Trail> trail;
	/** A second line that changes with the card's state (an account's "Remove from Flint?"), or null for the fixed one. */
	private @Nullable Supplier<String> detailSource;
	/** Whether the second line is a warning (drawn in red). */
	private BooleanSupplier warn = () -> false;
	/** Room kept free at the right for chips laid over the row, in GUI units. */
	private float trailRoom;
	/** The card's fill under the row, which the picture's rounded corners blend into. */
	private int surface = 0xFF111216;

	private CardRow(Component message, Lead lead, @Nullable String account, @Nullable Icon icon, boolean accentIcon, String title,
		@Nullable String detail, Supplier<Trail> trail, Runnable action) {
		super(message, action);
		this.lead = lead;
		this.account = account;
		this.icon = icon;
		this.accentIcon = accentIcon;
		this.title = new TextLine(FlintFonts.STRONG, title);
		this.detail = detail == null ? null : new TextLine(FlintFonts.SMALL, detail);
		this.trail = trail;
	}

	/** An account: its face (from {@code uuid}), its name and a line under it. */
	public static CardRow account(String uuid, String name, String detail, Supplier<Trail> trail, Runnable action) {
		return new CardRow(Component.literal(name + ", " + detail), Lead.FACE, uuid, null, false, name, detail, trail, action);
	}

	/** An action with an icon in a round badge (Skin layers); {@code accent}: the badge in Flint orange. */
	public static CardRow action(Icon icon, boolean accent, String title, @Nullable String detail, Supplier<Trail> trail, Runnable action) {
		return new CardRow(Component.literal(detail == null ? title : title + ", " + detail), Lead.ICON, null, icon, accent, title, detail,
			trail, action);
	}

	/** Microsoft's sign-in: its logo and {@code title} on Microsoft's dark sign-in button. */
	public static CardRow microsoft(Component narration, String title, @Nullable String detail, Supplier<Trail> trail, Runnable action) {
		return new CardRow(narration, Lead.MICROSOFT, null, null, false, title, detail, trail, action);
	}

	public CardRow surface(int argb) {
		this.surface = argb;
		return this;
	}

	/** The second line follows {@code source} (and is red while {@code warn}). */
	public CardRow detailFrom(Supplier<String> source, BooleanSupplier warn) {
		this.detailSource = source;
		this.warn = warn;
		return this;
	}

	/** Keeps {@code units} free at the row's right for chips laid over it (the text ends before them). */
	public CardRow trailRoom(float units) {
		this.trailRoom = units;
		return this;
	}

	@Override
	protected void paint(Canvas c) {
		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int h = c.px(getHeight());
		Trail state = trail.get();
		float lift = Math.max(hover, focus);
		boolean button = lead == Lead.MICROSOFT;
		int radius = c.px(RADIUS);
		boolean zoomed = press > 0.01F;
		if (zoomed) {
			// A press dips the row by a hair and lets it back: felt more than seen.
			c.pushZoom(1.0F - 0.018F * press, x + w / 2.0F, y + h / 2.0F);
		}
		if (button) {
			int fill = Palette.lerp(Palette.lerp(MS_FILL, MS_FILL_HOVER, lift), 0xFF262626, press);
			c.roundRect(x, y, w, h, radius, fill);
			c.ring(x, y, w, h, radius, Math.max(1, Math.round(c.scale() / 4.0F)), Palette.lerp(0x1AFFFFFF, 0x30FFFFFF, lift));
		} else if (lift > 0.005F || press > 0.005F) {
			c.roundRect(x, y, w, h, radius, Palette.withAlpha(0x12FFFFFF, Math.min(1.0F, lift + press * 0.5F)));
		}
		if (focus > 0.5F) {
			c.ring(x, y, w, h, radius, Math.max(1, Math.round(c.scale() / 4.0F)), Palette.withAlpha(Palette.ACCENT, focus));
		}
		float dim = active ? 1.0F : 0.5F;
		int pad = c.px(PAD);
		int center = y + h / 2;
		int pictureX = x + pad;
		int picture = paintLead(c, pictureX, center, h, dim);

		int textX = pictureX + picture + c.px(button ? 3.6F : 4.0F);
		int trailW = state == Trail.NONE ? 0 : c.px(9.0F);
		int textMax = x + w - pad - Math.max(trailW, c.px(trailRoom)) - textX;
		int titleInk = button ? Palette.withAlpha(0xFFFFFFFF, dim)
			: Palette.withAlpha(Palette.lerp(Palette.TEXT_DIM, Palette.TEXT, Math.max(lift, state == Trail.CHECK ? 1.0F : 0.0F)), dim);
		if (detail != null && detailSource != null) {
			detail.set(detailSource.get());
		}
		if (detail == null) {
			c.text(title, textX, c.baselineFor(title, center), titleInk, textMax);
		} else {
			c.text(title, textX, c.baselineFor(title, center - c.px(3.0F)), titleInk, textMax);
			int detailInk = warn.getAsBoolean() ? Palette.DANGER_TEXT : button ? 0xFFB8B8B8 : Palette.MUTED;
			c.text(detail, textX, c.baselineFor(detail, center + c.px(3.6F)), Palette.withAlpha(detailInk, dim), textMax);
		}

		int trailSize = c.px(5.0F);
		int trailX = x + w - pad - trailSize;
		if (state == Trail.CHECK) {
			c.icon(Icon.CHECK, trailX, center - trailSize / 2, trailSize, Palette.ACCENT_TEXT);
		} else if (state == Trail.SPINNER) {
			spinner(c, trailX + trailSize / 2, center, trailSize / 2);
		}
		if (zoomed) {
			c.popOffset();
		}
	}

	/** The picture; returns its width in pixels. */
	private int paintLead(Canvas c, int x, int center, int h, float dim) {
		int size = c.px(detail == null ? 8.0F : 11.0F);
		switch (lead) {
			case FACE -> {
				// Skin pixels stay sharp only at whole multiples of the 8-pixel face.
				size = Math.max(8, Math.round(size / 8.0F) * 8);
				int y = center - size / 2;
				PlayerFaceExtractor.extractRenderState(c.frame().graphics(), AccountSkin.of(account), x, y, size);
				float opacity = c.opacity();
				c.setOpacity(1.0F);
				// Minecraft draws the face at full strength: while the card fades, the card's colour over it fades it too.
				if (opacity < 1.0F || dim < 1.0F) {
					c.fill(x, y, x + size, y + size, Palette.withAlpha(surface, 1.0F - opacity * dim));
				}
				c.roundCorners(x, y, size, size, c.px(1.6F), surfaceUnder());
				c.setOpacity(opacity);
			}
			case ICON -> {
				int y = center - size / 2;
				int badge = accentIcon ? Palette.withAlpha(Palette.ACCENT, 0.9F * dim) : Palette.withAlpha(0x1CFFFFFF, dim);
				c.roundRect(x, y, size, size, size / 2, badge);
				int iconPx = Math.round(size * 0.52F);
				int ink = accentIcon ? Palette.ACCENT_INK : Palette.withAlpha(Palette.TEXT_DIM, dim);
				if (icon != null) {
					c.icon(icon, x + (size - iconPx) / 2, y + (size - iconPx) / 2, iconPx, ink);
				}
			}
			case MICROSOFT -> {
				size = microsoftLogo(c, x, center, c.px(detail == null ? 6.2F : 7.2F), dim);
			}
		}
		return size;
	}

	/**
	 * Microsoft's logo as its sign-in branding draws it: four squares (red, green, blue, yellow) with a gap of a tenth of a
	 * square between them, each square a whole number of pixels so every edge is crisp. Returns its width.
	 */
	static int microsoftLogo(Canvas c, int x, int center, int sizePx, float dim) {
		int gap = Math.max(1, Math.round(sizePx / 21.0F));
		int square = Math.max(2, (sizePx - gap) / 2);
		int total = square * 2 + gap;
		int y = center - total / 2;
		c.fill(x, y, x + square, y + square, Palette.withAlpha(MS_RED, dim));
		c.fill(x + square + gap, y, x + total, y + square, Palette.withAlpha(MS_GREEN, dim));
		c.fill(x, y + square + gap, x + square, y + total, Palette.withAlpha(MS_BLUE, dim));
		c.fill(x + square + gap, y + square + gap, x + total, y + total, Palette.withAlpha(MS_YELLOW, dim));
		return total;
	}

	/** The colour around the picture: the card with the row's hover on it. */
	private int surfaceUnder() {
		return Palette.over(Palette.withAlpha(0x12FFFFFF, Math.min(1.0F, Math.max(hover, focus) + press * 0.5F)), surface);
	}

	/** Eight dots round a circle, the brightest one turning (one turn a second). */
	private static void spinner(Canvas c, int cx, int cy, int radius) {
		int dot = Math.max(2, Math.round(radius * 0.42F));
		float turn = (Util.getMillis() % 960L) / 960.0F;
		for (int i = 0; i < 8; i++) {
			double angle = Math.PI * 2 * i / 8.0;
			int dx = (int) Math.round(Math.cos(angle) * (radius - dot / 2.0));
			int dy = (int) Math.round(Math.sin(angle) * (radius - dot / 2.0));
			float behind = ((turn * 8 - i) % 8 + 8) % 8 / 8.0F;
			c.roundRect(cx + dx - dot / 2, cy + dy - dot / 2, dot, dot, dot / 2, Palette.withAlpha(Palette.ACCENT_TEXT, 1.0F - behind * 0.8F));
		}
	}
}
