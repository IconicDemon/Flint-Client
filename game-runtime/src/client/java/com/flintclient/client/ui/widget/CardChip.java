package com.flintclient.client.ui.widget;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A small control inside one of the main menu's cards, on or under a {@link CardRow}: an icon on its own (an account's
 * remove button, which shows as the pointer comes onto its row), or a short label with an optional icon (Remove, Keep,
 * Copy link, Cancel). It shows only while {@link #shown()}; the screen keeps it out of reach otherwise.
 */
public final class CardChip extends FlintButton {
	/** How the chip looks. */
	public enum Kind {
		/** An icon alone, faint until its row is under the pointer; red on hover (removing). */
		ICON,
		/** A quiet label on the card's surface. */
		NEUTRAL,
		/** A red label: the step that can't be undone. */
		DANGER
	}

	/** Height in GUI units of a labelled chip. */
	public static final int HEIGHT = 11;
	/** Width and height of an icon chip. */
	public static final int ICON_SIZE = 11;
	private static final float PAD = 3.4F;
	private static final float RADIUS = 2.0F;
	private static final float GLYPH = 4.6F;
	private static final float GLYPH_GAP = 2.0F;

	private final Kind kind;
	private final @Nullable Icon icon;
	private final @Nullable TextLine label;
	private final @Nullable Supplier<String> labelSource;
	private final BooleanSupplier shown;
	/** For an icon chip: its row, whose hover brings it in. */
	private final @Nullable FlintButton row;

	private CardChip(Component message, Kind kind, @Nullable Icon icon, @Nullable Supplier<String> label, @Nullable FlintButton row,
		BooleanSupplier shown, Runnable action) {
		super(message, action);
		this.kind = kind;
		this.icon = icon;
		this.labelSource = label;
		this.label = label == null ? null : new TextLine(FlintFonts.SMALL_BOLD, label.get());
		this.row = row;
		this.shown = shown;
	}

	/** An icon alone on {@code row}, coming in with the row's hover. */
	public static CardChip icon(Component name, Icon icon, FlintButton row, BooleanSupplier shown, Runnable action) {
		return new CardChip(name, Kind.ICON, icon, null, row, shown, action);
	}

	/** A labelled chip ({@code label} read every frame: "Copy link", then "Copied"). */
	public static CardChip label(Kind kind, @Nullable Icon icon, Supplier<String> label, BooleanSupplier shown, Runnable action) {
		return new CardChip(Component.literal(label.get()), kind, icon, label, null, shown, action);
	}

	/** Whether it is part of the card now (a removal asked about, a sign-in waiting...). */
	public boolean shown() {
		return shown.getAsBoolean();
	}

	/** The width in GUI units that fits the label (and icon). */
	public int preferredWidth(Canvas c) {
		if (label == null) {
			return ICON_SIZE;
		}
		float text = c.width(label) / c.unitScale();
		return Math.round(PAD * 2 + text + (icon == null ? 0 : GLYPH + GLYPH_GAP) + 0.5F);
	}

	@Override
	protected void paint(Canvas c) {
		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int h = c.px(getHeight());
		float reveal = Math.max(Math.max(hover, focus), row == null ? 1.0F : row.lit());
		if (reveal <= 0.01F) {
			return;
		}
		float opacity = c.opacity();
		c.setOpacity(opacity * reveal);
		try {
			int zoomed = 0;
			if (press > 0.01F) {
				// A press dips the chip a little and lets it back.
				c.pushZoom(1.0F - 0.06F * press, x + w / 2.0F, y + h / 2.0F);
				zoomed = 1;
			}
			float lift = Math.max(hover, focus);
			int radius = kind == Kind.ICON ? Math.min(w, h) / 2 : c.px(RADIUS);
			int fill = switch (kind) {
				case ICON -> Palette.withAlpha(active ? 0x33E07D70 : 0x14FFFFFF, lift);
				case NEUTRAL -> Palette.lerp(0x14FFFFFF, 0x24FFFFFF, lift);
				case DANGER -> Palette.lerp(0xFF8E2F26, 0xFFA8382D, lift);
			};
			if ((fill >>> 24) != 0) {
				c.roundRect(x, y, w, h, radius, fill);
			}
			if (focus > 0.5F) {
				c.ring(x, y, w, h, radius, Math.max(1, Math.round(c.scale() / 4.0F)), Palette.withAlpha(Palette.ACCENT, focus));
			}
			int ink = switch (kind) {
				case ICON -> !active ? Palette.withAlpha(Palette.MUTED, 0.6F) : Palette.lerp(Palette.MUTED, Palette.DANGER_TEXT_HOVER, lift);
				case NEUTRAL -> Palette.lerp(Palette.TEXT_DIM, Palette.TEXT, lift);
				case DANGER -> 0xFFFFF4F2;
			};
			if (label == null) {
				int size = c.px(GLYPH);
				c.icon(icon, x + (w - size) / 2, y + (h - size) / 2, size, ink);
			} else {
				label.set(labelSource.get());
				int glyph = icon == null ? 0 : c.px(GLYPH);
				int gap = icon == null ? 0 : c.px(GLYPH_GAP);
				int content = glyph + gap + c.width(label);
				int left = x + (w - content) / 2;
				if (icon != null) {
					c.icon(icon, left, y + (h - glyph) / 2, glyph, ink);
				}
				c.text(label, left + glyph + gap, c.baselineFor(label, y + h / 2), ink);
			}
			if (zoomed > 0) {
				c.popOffset();
			}
		} finally {
			c.setOpacity(opacity);
		}
	}
}
