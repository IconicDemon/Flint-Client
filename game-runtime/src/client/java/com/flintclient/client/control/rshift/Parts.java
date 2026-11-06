package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Pressable;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/** The window's own controls, as Dawn's: its tabs, the account block, square icon buttons and the category buttons. Sizes in design pixels. */
final class Parts {
	private Parts() {
	}

	/**
	 * A window tab, 64 tall: a block with its icon; the open tab turns the accent and shows its name, in capitals, beside
	 * the icon (eased). A closed tab names itself in a tooltip.
	 */
	static final class Tab extends Pressable {
		static final float H = 64;
		private final Glyph glyph;
		private final Label label;
		private final BooleanSupplier selected;
		private float on = -1;

		Tab(String text, Glyph glyph, BooleanSupplier selected, Runnable action) {
			super(action);
			this.glyph = glyph;
			this.label = new Label(Type.TAB, text.toUpperCase(java.util.Locale.ROOT));
			this.selected = selected;
			name(text + " tab");
			tooltip(text);
		}

		/** Its width now: 24 either side of its 24 icon, and (while open) 12 and its name after the icon; eased between the two. */
		float width(Canvas c) {
			float closed = d(2 + 24 + 24 + 24 + 2);
			float open = closed + d(12) + label.width(c);
			return closed + (open - closed) * Math.max(0, on);
		}

		@Override
		public String narration() {
			return accessibleName() + (selected.getAsBoolean() ? ", selected" : "");
		}

		@Override
		protected void paint(Canvas c) {
			float before = on;
			on = MenuStyle.ease(on, selected.getAsBoolean());
			if (on != before && before >= 0 && parent() != null) {
				// Growing or shrinking: the row is laid out again next frame.
				parent().invalidate();
			}
			float rad = MenuStyle.radius();
			MenuStyle.block(c, x, y, w, h, rad, hover * (1 - on), on);
			MenuStyle.pressed(c, x, y, w, h, rad, press * (1 - on));
			float iconX = x + d(26) + d(12);
			glyph.draw(c, iconX, y + h / 2, d(24), MenuStyle.TEXT);
			if (on > 0.02F) {
				float labelX = iconX + d(12) + d(12);
				MenuStyle.fitted(c, label, labelX, y + h / 2, Palette.withAlpha(MenuStyle.TEXT, on), Math.max(0, x + w - d(26) - labelX));
			}
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}

		/** The open tab shows its name already. */
		@Override
		public com.flintclient.client.render.TextLine @Nullable [] tooltipLines() {
			return selected.getAsBoolean() ? null : super.tooltipLines();
		}
	}

	/** The player's face and name, as Dawn's account block (64 tall): opens the Profiles tab. */
	static final class ProfilePill extends Pressable {
		private final Label name = new Label(Type.PILL, "");

		ProfilePill(Runnable open) {
			super(open);
			name("Profiles");
			tooltip("Your profiles");
		}

		private void refresh() {
			name.set(com.flintclient.client.platform.Faces.ownName().toUpperCase(java.util.Locale.ROOT));
		}

		float width(Canvas c) {
			refresh();
			return d(2 + 12 + 40 + 16) + name.width(c) + d(24 + 2);
		}

		@Override
		public String narration() {
			return "Profiles, " + com.flintclient.client.control.ControlState.layout().name() + " in use";
		}

		@Override
		protected void paint(Canvas c) {
			refresh();
			float rad = MenuStyle.radius();
			MenuStyle.block(c, x, y, w, h, rad, hover, 0);
			MenuStyle.pressed(c, x, y, w, h, rad, press);
			// Skin pixels stay sharp only at whole multiples of the 8-pixel face.
			int avatar = Math.max(8, Math.round(c.px(d(40)) / 8.0F) * 8);
			int ax = c.px(x + d(14));
			int ay = c.px(y + h / 2) - avatar / 2;
			com.flintclient.client.platform.Faces.own(c.frame(), ax, ay, avatar);
			MenuStyle.text(c, name, (ax + avatar) / (float) c.scale() + d(16), y + h / 2, MenuStyle.TEXT);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/**
	 * A square button with one icon (40 × 40, its glyph 22): Dawn's secondary button, the text at 5% with a grey glyph that
	 * the pointer brightens; the accent while {@code active}. Close is Dawn's primary arrow: the accent at 10% inside a
	 * 1 px accent edge, its X in the accent.
	 */
	static final class Square extends Pressable {
		private final Glyph glyph;
		private @Nullable BooleanSupplier active;
		/** Close: an accent edge and mark. */
		private boolean closer;

		Square(Glyph glyph, String name, Runnable action) {
			super(action);
			this.glyph = glyph;
			name(name);
			tooltip(name);
		}

		Square active(BooleanSupplier value) {
			this.active = value;
			return this;
		}

		Square closer() {
			this.closer = true;
			return this;
		}

		@Override
		public String narration() {
			return withReason(active == null ? accessibleName() + ", button" : accessibleName() + (active.getAsBoolean() ? ", on" : ", off"));
		}

		@Override
		protected void paint(Canvas c) {
			float rad = Math.min(MenuStyle.radius(), h / 2);
			boolean lit = active != null && active.getAsBoolean();
			if (closer) {
				MenuStyle.box(c, x, y, w, h, rad, Palette.withAlpha(MenuStyle.ACCENT, 0.10F + 0.08F * hover));
				MenuStyle.ring(c, x, y, w, h, rad, Math.max(1, c.px(d(1))), MenuStyle.ACCENT);
				MenuStyle.halo(c, x, y, w, h, rad, hover, Palette.withAlpha(MenuStyle.ACCENT, 0.30F));
				glyph.draw(c, x + w / 2, y + h / 2, h * 0.5F, MenuStyle.ACCENT);
			} else {
				MenuStyle.button(c, x, y, w, h, false, hover, press);
				int mark = lit ? MenuStyle.ACCENT : Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover);
				glyph.draw(c, x + w / 2, y + h / 2, h * 0.55F, mark);
			}
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/**
	 * A category button ("All", "HUD"…): Dawn's default button, 40 tall and at least 108 wide, its label 14. The chosen one
	 * is primary (the accent, white text), the rest secondary (the text at 5%, grey text). Its count is read out, not shown.
	 */
	static final class Chip extends Pressable {
		static final float H = 40;
		private final Label label;
		private final BooleanSupplier selected;
		private float on = -1;

		Chip(String text, int count, BooleanSupplier selected, Runnable action) {
			super(action);
			this.label = new Label(Type.CHIP, text);
			this.selected = selected;
			name(text + ", " + count + " mods");
		}

		float width(Canvas c) {
			return Math.max(d(108), label.width(c) + d(15) * 2);
		}

		@Override
		public String narration() {
			return accessibleName() + (selected.getAsBoolean() ? ", selected" : "");
		}

		@Override
		protected void paint(Canvas c) {
			on = MenuStyle.ease(on, selected.getAsBoolean());
			float rad = MenuStyle.radius();
			int fill = Palette.lerp(MenuStyle.CHIP, MenuStyle.ACCENT, on);
			MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(fill, 0xFF000000, press * 0.15F));
			MenuStyle.halo(c, x, y, w, h, rad, hover, on >= 0.5F ? Palette.withAlpha(MenuStyle.ACCENT, 0.30F) : Palette.withAlpha(MenuStyle.TEXT, 0.03F));
			int ink = Palette.lerp(Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover * 0.6F), MenuStyle.TEXT, on);
			MenuStyle.centered(c, label, x + w / 2, y + h / 2 + d(0.75F), ink, w - d(20));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}
}
