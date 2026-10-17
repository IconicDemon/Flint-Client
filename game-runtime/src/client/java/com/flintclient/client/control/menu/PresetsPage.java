package com.flintclient.client.control.menu;

import static com.flintclient.client.control.menu.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.menu.MenuStyle.Label;
import com.flintclient.client.control.menu.MenuStyle.Type;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.Preset;
import com.flintclient.client.control.store.LayoutResult;
import com.flintclient.client.control.store.LayoutStore;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.ScrollView;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The PRESETS tab: Flint's ready-made HUD layouts, each with the widgets it puts on screen. "New profile" makes a profile
 * from the preset and switches to it (nothing else changes). "Apply to <profile>" changes the profile in use: it puts the
 * preset's widgets at their places and turns every other module off (their settings are kept), so it asks first: the
 * button becomes "Confirm" for a few seconds. A line under the cards says what happened.
 */
final class PresetsPage extends Node {
	private static final long CONFIRM_MILLIS = 4000;
	private static final float INTRO = 96;

	private final ScrollView scroll;
	private final Label intro = new Label(Type.SENTENCE,
		"Ready-made HUD layouts. Applying one turns this profile's other modules off; their settings stay.");
	private final Label message = new Label(Type.ROW_NOTE, "");
	private boolean messageIsError;
	private @Nullable Card asking;
	private long askedAt;

	PresetsPage() {
		Cards cards = new Cards();
		for (Preset preset : Preset.values()) {
			cards.add(new Card(preset));
		}
		scroll = add(new ScrollView(cards));
	}

	private static LayoutStore store() {
		return ControlState.layouts();
	}

	private void say(String text, boolean error) {
		message.set(text);
		messageIsError = error;
	}

	/** Esc: a pending confirmation is dropped first; otherwise the menu's. */
	boolean escape() {
		if (asking != null) {
			asking.unask();
			asking = null;
			return true;
		}
		return false;
	}

	@Override
	protected void layout() {
		scroll.flatBar(MenuStyle.SCROLL_THUMB, r(30), r(120));
		scroll.setBounds(x, y + r(INTRO), w, Math.max(0, h - r(INTRO) - r(56)));
	}

	@Override
	protected void paint(Canvas c) {
		if (asking != null && Util.getMillis() - askedAt > CONFIRM_MILLIS) {
			asking.unask();
			asking = null;
		}
		MenuStyle.icon(c, Icon.INFO, x + r(15), y + r(INTRO) / 2 - r(8), r(30), MenuStyle.INFO);
		MenuStyle.fitted(c, intro, x + r(44), y + r(INTRO) / 2 - r(8), MenuStyle.SENTENCE_TEXT, w - r(44));
		if (!message.text().isEmpty()) {
			MenuStyle.fitted(c, message, x + r(6), y + h - r(28), messageIsError ? MenuStyle.DANGER_TEXT : MenuStyle.SENTENCE_TEXT, w - r(12));
		}
	}

	/** A widget's usual size in GUI units, for the sketch only. */
	private static float[] sketchSize(String moduleId) {
		return switch (moduleId) {
			case "keystrokes" -> new float[] {46, 48};
			case "armor" -> new float[] {20, 64};
			case "effects" -> new float[] {64, 34};
			case "coordinates" -> new float[] {54, 30};
			case "direction" -> new float[] {46, 13};
			default -> new float[] {42, 13};
		};
	}

	/** The cards, one under another. */
	private static final class Cards extends Node {
		@Override
		public float prefHeight(float width) {
			return childCount() * (r(Card.H) + r(20));
		}

		@Override
		protected void layout() {
			float at = y;
			for (int i = 0; i < childCount(); i++) {
				child(i).setBounds(x, at, w, r(Card.H));
				at += r(Card.H) + r(20);
			}
		}
	}

	/** One preset: its name, the widgets it shows, and its two buttons. */
	private final class Card extends Node {
		static final float H = 190;
		/** The sketch's screen, in GUI units (a common 16:9 window at GUI scale 4.5). */
		private static final float SCREEN_W = 427;
		private static final float SCREEN_H = 240;
		/** Where each of the preset's widgets sits on that screen (x, y, w, h), worked out once. */
		private final float[] boxes;
		private final Preset preset;
		private final Label name;
		private final Label summary;
		private final PageControls.TextButton make;
		private final PageControls.TextButton apply;

		Card(Preset preset) {
			this.preset = preset;
			this.name = new Label(Type.SECTION_TITLE, preset.label());
			this.summary = new Label(Type.ROW_NOTE, preset.summary());
			boxes = new float[preset.entries().size() * 4];
			for (int i = 0; i < preset.entries().size(); i++) {
				Preset.Entry entry = preset.entries().get(i);
				float[] size = sketchSize(entry.moduleId());
				com.flintclient.client.control.hud.HudRect rect = entry.placement().onScreen(SCREEN_W, SCREEN_H, size[0], size[1]);
				boxes[i * 4] = rect.x();
				boxes[i * 4 + 1] = rect.y();
				boxes[i * 4 + 2] = rect.width();
				boxes[i * 4 + 3] = rect.height();
			}
			make = add(new PageControls.TextButton("New profile", Icon.PLUS, PageControls.Look.PLAIN, this::makeProfile));
			apply = add(new PageControls.TextButton("Apply to profile", null, PageControls.Look.ACCENT, this::pressApply));
		}

		private void makeProfile() {
			LayoutResult made = store().create(store().uniqueName(preset.label()), preset, null);
			if (!made.succeeded() || made.id() == null) {
				say(String.valueOf(made.error()), true);
				return;
			}
			LayoutResult switched = store().switchTo(made.id());
			say(switched.succeeded() ? "Made the profile \"" + ControlState.layout().name() + "\" from " + preset.label() + " and switched to it."
				: String.valueOf(switched.error()), !switched.succeeded());
		}

		private void pressApply() {
			if (asking != this) {
				if (asking != null) {
					asking.unask();
				}
				asking = this;
				askedAt = Util.getMillis();
				apply.setText("Confirm: turns other modules off");
				apply.setLook(PageControls.Look.DANGER);
				invalidate();
				return;
			}
			unask();
			asking = null;
			Layout layout = ControlState.layout();
			preset.applyTo(layout, preset.theme());
			say("Applied " + preset.label() + " to \"" + layout.name() + "\".", false);
		}

		void unask() {
			apply.setText("Apply to profile");
			apply.setLook(PageControls.Look.ACCENT);
			invalidate();
		}

		@Override
		protected void layout() {
			float bh = r(PageControls.TextButton.H);
			float by = y + h - r(28) - bh;
			float aw = apply.prefWidth();
			apply.setBounds(x + w - r(28) - aw, by, aw, bh);
			float mw = make.prefWidth();
			make.setBounds(apply.x() - r(16) - mw, by, mw, bh);
		}

		@Override
		protected void paint(Canvas c) {
			MenuStyle.framed(c, x, y, w, h, r(20), MenuStyle.SECTION_CARD, MenuStyle.SECTION_EDGE);
			MenuStyle.text(c, name, x + r(28), y + r(42), MenuStyle.ROW_TEXT);
			// The sketch: a small screen with the preset's widgets where they go, left of the buttons.
			float sh = h - r(48);
			float sw = sh * SCREEN_W / SCREEN_H;
			float sx = make.x() - r(36) - sw;
			float sy = y + r(24);
			MenuStyle.fitted(c, summary, x + r(28), y + r(80), MenuStyle.LABEL, Math.max(r(60), sx - r(24) - x - r(28)));
			if (sx < x + r(420)) {
				return;
			}
			MenuStyle.framed(c, sx, sy, sw, sh, r(10), MenuStyle.PREVIEW_FRAME, MenuStyle.SECTION_EDGE);
			float k = sw / SCREEN_W;
			for (int i = 0; i < boxes.length; i += 4) {
				MenuStyle.box(c, sx + boxes[i] * k, sy + boxes[i + 1] * k, Math.max(r(6), boxes[i + 2] * k), Math.max(r(4), boxes[i + 3] * k), r(3),
					Palette.withAlpha(MenuStyle.ACCENT, 0.75F));
			}
		}
	}
}
