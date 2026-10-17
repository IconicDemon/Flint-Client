package com.flintclient.client.control.menu;

import static com.flintclient.client.control.menu.MenuStyle.r;

import com.flintclient.client.control.menu.MenuStyle.Label;
import com.flintclient.client.control.menu.MenuStyle.Type;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Pressable;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The menu's small controls, each drawn to the reference: header tabs, the close and view buttons, the profile pill,
 * the sidebar buttons and the category chips. All show hover, press and keyboard focus. Some only take the selection
 * or give feedback in this phase (see the owner's brief); none opens anything the brief hasn't asked for.
 */
final class MenuControls {
	private MenuControls() {
	}

	/** Eases {@code current} towards {@code on} (the first frame snaps). */
	static float ease(float current, boolean on) {
		return current < 0 ? (on ? 1 : 0) : Kit.approach(current, on ? 1 : 0, 0.05F);
	}

	/** A header section: MODS, SETTINGS or WAYPOINTS. The selected one sits on a raised pill. */
	static final class NavTab extends Pressable {
		static final float H = 69;
		static final float PAD = 36;
		private final Label label;
		private final BooleanSupplier selected;
		private float on = -1;

		NavTab(String text, BooleanSupplier selected, Runnable action) {
			super(action);
			this.label = new Label(Type.TAB, text);
			this.selected = selected;
			name(text.charAt(0) + text.substring(1).toLowerCase(Locale.ROOT) + " tab");
		}

		float width(Canvas c) {
			return label.width(c) + 2 * r(PAD);
		}

		@Override
		public String narration() {
			return accessibleName() + (selected.getAsBoolean() ? ", selected" : "");
		}

		@Override
		protected void paint(Canvas c) {
			on = ease(on, selected.getAsBoolean());
			float rad = r(12);
			int fill = Palette.lerp(Palette.withAlpha(MenuStyle.TAB_HOVER, hover * 0.9F), MenuStyle.TAB_SELECTED, on);
			if ((fill >>> 24) > 0) {
				MenuStyle.box(c, x, y, w, h, rad, fill);
			}
			if (on > 0.01F) {
				MenuStyle.ring(c, x, y, w, h, rad, MenuStyle.edge(), Palette.withAlpha(MenuStyle.TAB_SELECTED_EDGE, on));
			}
			int ink = Palette.lerp(Palette.lerp(MenuStyle.TAB_TEXT, MenuStyle.TAB_TEXT_HOVER, hover), MenuStyle.TAB_TEXT_SELECTED, on);
			MenuStyle.text(c, label, x + r(PAD), y + h / 2, ink);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** A square button with one icon: the close button and the favourites filter. */
	static final class IconButton extends Pressable {
		private final Icon icon;
		private final float iconSize;
		private final float radius;
		private final int fill;
		private final int fillHover;
		private final int edge;
		private final int ink;
		private java.util.function.@Nullable BooleanSupplier active;

		/** Shown in ember while {@code value} is true (a filter that's on). */
		IconButton active(java.util.function.BooleanSupplier value) {
			this.active = value;
			return this;
		}

		@Override
		public String narration() {
			return active == null ? super.narration() : accessibleName() + (active.getAsBoolean() ? ", on" : ", off");
		}

		IconButton(Icon icon, float iconSize, float radius, int fill, int fillHover, int edge, int ink, @Nullable Runnable action) {
			super(action);
			this.icon = icon;
			this.iconSize = iconSize;
			this.radius = radius;
			this.fill = fill;
			this.fillHover = fillHover;
			this.edge = edge;
			this.ink = ink;
		}

		@Override
		protected void paint(Canvas c) {
			int body = Palette.lerp(Palette.lerp(fill, fillHover, hover), Palette.lerp(fill, 0xFF000000, 0.25F), press * 0.6F);
			float rad = r(radius);
			if ((edge >>> 24) != 0) {
				MenuStyle.framed(c, x, y, w, h, rad, body, edge);
			} else {
				MenuStyle.box(c, x, y, w, h, rad, body);
			}
			int mark = active != null && active.getAsBoolean() ? MenuStyle.ACCENT : Palette.lerp(ink, 0xFFFFFFFF, hover * 0.5F);
			MenuStyle.icon(c, icon, x + w / 2, y + h / 2, r(iconSize), mark);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** A category chip: its name and count. The selected chip is light with dark text. */
	static final class Chip extends Pressable {
		static final float H = 64;
		private final MenuCatalog.Category category;
		private final Label label;
		private final Label count;
		private final BooleanSupplier selected;
		private float on = -1;

		Chip(MenuCatalog.Category category, BooleanSupplier selected, Runnable action) {
			super(action);
			this.category = category;
			this.label = new Label(Type.CHIP, category.label);
			this.count = new Label(Type.COUNT, Integer.toString(MenuCatalog.count(category)));
			this.selected = selected;
			name(category.label.charAt(0) + category.label.substring(1).toLowerCase(Locale.ROOT));
		}

		MenuCatalog.Category category() {
			return category;
		}

		float width(Canvas c) {
			return r(24) + label.width(c) + r(12) + count.width(c) + r(24);
		}

		@Override
		public String narration() {
			return accessibleName() + ", " + count.text() + " mods" + (selected.getAsBoolean() ? ", selected" : "");
		}

		@Override
		protected void paint(Canvas c) {
			on = ease(on, selected.getAsBoolean());
			float rad = r(14);
			int idle = Palette.lerp(Palette.lerp(MenuStyle.CHIP, MenuStyle.CHIP_HOVER, hover), Palette.lerp(MenuStyle.CHIP, 0xFF000000, 0.25F), press * 0.6F);
			MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(idle, MenuStyle.CHIP_SELECTED, on));
			float cy = y + h / 2;
			int text = Palette.lerp(Palette.lerp(MenuStyle.CHIP_TEXT, MenuStyle.TAB_TEXT_SELECTED, hover * 0.6F), MenuStyle.CHIP_SELECTED_TEXT, on);
			int number = Palette.lerp(MenuStyle.CHIP_COUNT, MenuStyle.CHIP_SELECTED_COUNT, on);
			MenuStyle.text(c, label, x + r(24), cy, text);
			MenuStyle.text(c, count, x + r(24) + label.width(c) + r(12), cy, number);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}
}
