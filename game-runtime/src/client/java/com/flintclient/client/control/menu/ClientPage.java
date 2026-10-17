package com.flintclient.client.control.menu;

import static com.flintclient.client.control.menu.MenuStyle.r;

import com.flintclient.client.control.ControlCenter;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.menu.MenuStyle.Label;
import com.flintclient.client.control.menu.MenuStyle.Type;
import com.flintclient.client.control.platform.ControlKeys;
import com.flintclient.client.control.platform.Mc263ControlPlatform;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.ScrollView;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import org.jspecify.annotations.Nullable;

/**
 * The SETTINGS tab: Flint's client-wide settings (the same in every profile and every Minecraft version): the menu's
 * sounds and motion, the HUD with the debug screen, and the keys that open Flint and the HUD editor. The menu's and the
 * HUD's look are on the Themes page. Keys are Minecraft
 * key bindings, so they also show (and can be changed) in Minecraft's Controls, which point out conflicts.
 */
final class ClientPage extends Node {
	private final ScrollView scroll;
	private final Column column = new Column();

	ClientPage() {
		ControlSettings s = ControlState.settings();
		Section menu = column.add(new Section("Menu"));
		menu.row("Interface sounds", "Soft sounds when Flint opens and closes", new PageControls.Switch(s::soundsEnabled,
			() -> s.setSoundsEnabled(!s.soundsEnabled())));
		menu.row("Volume", null, new PageControls.Slider(0, 100, 5, "%", s::soundVolumePercent, s::setSoundVolumePercent));
		menu.row("Animations", "Fades and slides in Flint's menus", new PageControls.Switch(s::animations, () -> s.setAnimations(!s.animations())));
		menu.row("Follow Minecraft's reduced motion", "When Minecraft's panorama speed is 0", new PageControls.Switch(
			s::followMinecraftReducedMotion, () -> s.setFollowMinecraftReducedMotion(!s.followMinecraftReducedMotion())));
		menu.row("Blur behind Flint", "Minecraft's menu blur behind the mods window", new PageControls.Switch(s::transitionBlur,
			() -> s.setTransitionBlur(!s.transitionBlur())));
		menu.row("High-contrast outlines", null, new PageControls.Switch(s::highContrast, () -> s.setHighContrast(!s.highContrast())));
		Section hud = column.add(new Section("HUD"));
		hud.row("Show the HUD with F3", "Off: Flint's HUD steps aside for the debug screen", new PageControls.Switch(s::showHudWithDebugScreen,
			() -> s.setShowHudWithDebugScreen(!s.showHudWithDebugScreen())));
		Section keys = column.add(new Section("Keys"));
		keys.row("Open Flint", "Right Shift unless you change it", key(true));
		keys.row("HUD editor", "Opens the HUD editor straight away", key(false));
		scroll = add(new ScrollView(column));
	}

	private static Node key(boolean open) {
		return new PageControls.KeyBind(() -> {
			KeyMapping mapping = mapping(open);
			return mapping == null ? "" : Mc263ControlPlatform.boundKey(mapping).getName();
		}, name -> {
			KeyMapping mapping = mapping(open);
			if (mapping != null) {
				Mc263ControlPlatform.rebind(mapping, name.isEmpty() ? InputConstants.UNKNOWN : InputConstants.getKey(name));
			}
		});
	}

	private static @Nullable KeyMapping mapping(boolean open) {
		ControlKeys keys = ControlCenter.keys();
		return keys == null ? null : open ? keys.controlCenter() : keys.hudEditor();
	}

	@Override
	protected void layout() {
		scroll.flatBar(MenuStyle.SCROLL_THUMB, r(30), r(120));
		scroll.setBounds(x, y, w, h);
	}

	/** Sections one under another (two columns when wide). */
	private static final class Column extends Node {
		@Override
		public float prefHeight(float width) {
			float total = 0;
			for (int i = 0; i < childCount(); i++) {
				total += child(i).prefHeight(width) + r(24);
			}
			return total;
		}

		@Override
		protected void layout() {
			float at = y;
			for (int i = 0; i < childCount(); i++) {
				Node section = child(i);
				float sh = section.prefHeight(w);
				section.setBounds(x, at, w, sh);
				at += sh + r(24);
			}
		}
	}

	/** A titled card of rows. */
	private static final class Section extends Node {
		private static final float HEAD = 60;
		private final Label title;
		private final List<Row> rows = new ArrayList<>();

		Section(String title) {
			this.title = new Label(Type.SECTION_TITLE, title);
		}

		void row(String label, @Nullable String note, Node control) {
			rows.add(add(new Row(label, note, control)));
		}

		@Override
		public float prefHeight(float width) {
			float total = r(HEAD) + r(12);
			for (Row row : rows) {
				total += row.prefHeight(width);
			}
			return total;
		}

		@Override
		protected void layout() {
			float at = y + r(HEAD) + r(6);
			for (Row row : rows) {
				float rh = row.prefHeight(w);
				row.setBounds(x, at, w, rh);
				at += rh;
			}
		}

		@Override
		protected void paint(Canvas c) {
			MenuStyle.text(c, title, x + r(16), y + r(HEAD) / 2, MenuStyle.ROW_TEXT);
			MenuStyle.framed(c, x, y + r(HEAD), w, h - r(HEAD), r(20), MenuStyle.SECTION_CARD, MenuStyle.SECTION_EDGE);
		}
	}

	/** A label (and note) on the left, the control on the right. */
	private static final class Row extends Node {
		private final Label label;
		private final Label note;
		private final Node control;

		Row(String label, @Nullable String note, Node control) {
			this.label = new Label(Type.ROW_LABEL, label);
			this.note = new Label(Type.ROW_NOTE, note == null ? "" : note);
			this.control = add(control);
			control.name(label);
		}

		@Override
		public float prefHeight(float width) {
			return r(note.text().isEmpty() ? 80 : 104);
		}

		@Override
		protected void layout() {
			float cw = control.prefWidth();
			float ch = control instanceof PageControls.Switch ? r(PageControls.Switch.H) : r(52);
			control.setBounds(x + w - r(28) - cw, y + (h - ch) / 2, cw, ch);
		}

		@Override
		protected void paint(Canvas c) {
			float left = x + r(28);
			float room = control.x() - r(24) - left;
			float cy = y + h / 2;
			boolean hasNote = !note.text().isEmpty();
			MenuStyle.fitted(c, label, left, hasNote ? cy - r(14) : cy, MenuStyle.ROW_TEXT, room);
			if (hasNote) {
				MenuStyle.fitted(c, note, left, cy + r(18), MenuStyle.LABEL, room);
			}
		}
	}
}
