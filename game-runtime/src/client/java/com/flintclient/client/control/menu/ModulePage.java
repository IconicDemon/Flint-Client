package com.flintclient.client.control.menu;

import static com.flintclient.client.control.menu.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.client.control.menu.MenuStyle.Label;
import com.flintclient.client.control.menu.MenuStyle.Type;
import com.flintclient.client.control.menu.PageControls.ColorSwatch;
import com.flintclient.client.control.menu.PageControls.Segmented;
import com.flintclient.client.control.menu.PageControls.Slider;
import com.flintclient.client.control.menu.PageControls.Switch;
import com.flintclient.client.control.menu.PageControls.TextButton;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.ChoiceSetting;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.IdListSetting;
import com.flintclient.client.control.model.IdValuesSetting;
import com.flintclient.client.control.model.IdSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.TextSetting;
import com.flintclient.client.control.model.Labelled;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.SettingSection;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.Picture;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.ScrollView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * A module's settings page inside the Right Shift menu, in place of the grid: built once from the module's own
 * declaration (its {@link SettingSection sections} and their settings), so every module gets the same page and the same
 * behaviour; the module adds only what isn't a setting (FPS: its size and a way back to its default position).
 *
 * <p><b>Header.</b> Back to the grid, the module's one-line description, then Move (the HUD editor with this module
 * selected), Mod Preview (shows or hides the preview; the module itself is untouched) and Reset Settings (asks first;
 * puts only this module's settings back, not its position or on/off state, nor any other module).
 *
 * <p><b>Body.</b> The sections, each folding (the first open, the others closed), each setting a row: on/off is a
 * switch, a choice a segmented control, a number a slider, a colour a swatch that opens the colour picker. A setting
 * another one makes moot is dimmed and says why. Beside them, the preview: the owner's scene with the module drawn on it
 * by the HUD's own code at its real in-game size, with sample numbers (marked as such). When the window is too narrow for
 * both side by side, the preview goes above the settings.
 *
 * <p>Every change is made on the active layout's module at once (the HUD and the preview show it the same frame) and
 * saved shortly after by the menu ({@link MenuScreen}), never per frame.
 */
final class ModulePage extends Node {
	static final Identifier SCENE = Identifier.fromNamespaceAndPath("flintclient", "textures/gui/module_preview_scene.png");
	/** The scene behind every module preview; its textures live while the menu is open. */
	static final Picture PICTURE = new Picture(SCENE, "module_preview_scene");
	private static final float HEADER = 64;
	private static final float GAP = 36;

	private final MenuWindow window;
	private final MenuState state;
	private final String moduleId;
	private final TextButton back;
	private final Label sentence;
	private final MenuControls.IconButton move;
	private final TextButton preview;
	private final TextButton reset;
	private final Label askText = new Label(Type.SENTENCE, "");
	private final TextButton keep;
	private final TextButton confirm;
	private final ScrollView scroll;
	private final Column column;
	private final Preview previewBox;
	private final boolean hud;
	private boolean resettable;
	private boolean asking;
	private boolean stacked;
	/** One column whatever the width (a page with a wide panel of its own). */
	private boolean wide;

	ModulePage(MenuWindow window, MenuState state, String moduleId) {
		this.window = window;
		this.state = state;
		this.moduleId = moduleId;
		ModuleDescriptor descriptor = module().descriptor();
		back = add(new TextButton("Back", Icon.CHEVRON_LEFT, PageControls.Look.PLAIN, window::closePage));
		back.name("Back to mods");
		sentence = new Label(Type.SENTENCE, descriptor.description());
		move = add(new MenuControls.IconButton(Icon.MOVE, 30, 14, MenuStyle.CHIP, MenuStyle.CHIP_HOVER, 0, MenuStyle.TAB_TEXT_SELECTED,
			() -> window.openEditor(moduleId)));
		move.name("Move " + descriptor.name() + " on screen");
		move.tooltip("Move on screen (HUD editor)");
		preview = add(new TextButton("Mod Preview", null, PageControls.Look.PLAIN, () -> {
			state.previewOn = !state.previewOn;
			invalidate();
		}).active(() -> state.previewOn));
		reset = add(new TextButton("Reset Settings", Icon.RESET, PageControls.Look.PLAIN, () -> setAsking(true)));
		askText.set("Reset " + descriptor.name() + " settings?");
		keep = add(new TextButton("Keep", null, PageControls.Look.PLAIN, () -> setAsking(false)));
		confirm = add(new TextButton("Reset", null, PageControls.Look.DANGER, this::resetSettings));
		confirm.name("Reset " + descriptor.name() + " settings");
		column = new Column();
		hud = descriptor.hud();
		move.setVisible(hud);
		preview.setVisible(hud);
		// Nothing to put back on a module without settings (or one that isn't built).
		resettable = !descriptor.settings().isEmpty() && descriptor.status().usable();
		String about = about(descriptor);
		if (about != null) {
			column.add(new InfoCard(about, descriptor.status()));
		}
		List<SettingSection> sections = descriptor.sections();
		for (int i = 0; i < sections.size(); i++) {
			SettingSection section = sections.get(i);
			Fold fold = column.add(new Fold(section.title(), !section.advanced()));
			for (SettingDescriptor<?> setting : section.settings()) {
				Node control = control(setting);
				if (control != null) {
					fold.add(new Row(setting.label(), setting.description(), control, setting));
				}
			}
		}
		if (hud && descriptor.status().usable()) {
			Fold place = column.add(new Fold("On screen", sections.isEmpty()));
			extras(place);
		}
		// Pack Organizer lists the packs themselves, full width under its settings.
		wide = moduleId.equals(com.flintclient.client.control.model.mods.UtilityMods.PackOrganizer.ID);
		if (wide) {
			column.add(new PacksPanel());
		}
		scroll = add(new ScrollView(column));
		previewBox = add(new Preview());
		setAsking(false);
	}

	String moduleId() {
		return moduleId;
	}

	/** The module on the layout in use now (looked up each time: the layout can be switched). */
	ModuleState module() {
		return ControlState.layout().module(moduleId);
	}

	// ---------- rows ----------

	/** The control for {@code setting}, by its kind. */
	private @Nullable Node control(SettingDescriptor<?> setting) {
		return switch (setting) {
			case BoolSetting bool -> new Switch(() -> module().bool(bool), () -> set(bool, !module().bool(bool)));
			case IntSetting number -> new Slider(number.min(), number.max(), number.step(), number.unit(), () -> module().integer(number),
				v -> set(number, v));
			case FloatSetting number -> floatSlider(number);
			case RgbSetting rgb -> {
				ColorSwatch[] swatch = new ColorSwatch[1];
				swatch[0] = new ColorSwatch(() -> module().rgb(rgb), () -> window.openColorPicker(swatch[0], rgb.label(), rgb.defaultValue(),
					() -> module().rgb(rgb), v -> set(rgb, v)));
				yield swatch[0];
			}
			case ChoiceSetting<?> choice -> segmented(choice);
			case OptionSetting option -> {
				String[] labels = option.labels().toArray(new String[0]);
				yield fitsSegmented(labels) ? new Segmented(labels, () -> module().option(option), i -> set(option, i)) : optionField(option, labels);
			}
			case KeySetting key -> new PageControls.KeyBind(() -> module().text(key), v -> set(key, v));
			case TextSetting text -> textField(text);
			case IdSetting id -> idField(id);
			case IdValuesSetting values -> valuesField(values);
			case IdListSetting list -> listField(list);
		};
	}

	/** A choice with more options than a segmented control holds: a field that opens the searchable list. */
	private Node optionField(OptionSetting option, String[] labels) {
		List<ListPicker.Item> items = new ArrayList<>(labels.length);
		for (int i = 0; i < labels.length; i++) {
			items.add(new ListPicker.Item(Integer.toString(i), labels[i], ""));
		}
		PageControls.PickField[] field = new PageControls.PickField[1];
		field[0] = new PageControls.PickField(() -> labels[Math.max(0, Math.min(labels.length - 1, module().option(option)))],
			() -> window.openChooser(field[0], option.label(), items, false, () -> Integer.toString(module().option(option)),
				v -> set(option, Integer.parseInt(v)), null));
		return field[0];
	}

	/** One sound or item: its name; pressing opens the searchable list (sounds can be played from it). */
	private Node idField(IdSetting id) {
		PageControls.PickField[] field = new PageControls.PickField[1];
		boolean sound = id.kind() == com.flintclient.client.control.model.IdKind.SOUND;
		field[0] = new PageControls.PickField(() -> IdCatalog.name(id.kind(), module().text(id)), () -> window.openChooser(field[0], id.label(),
			IdCatalog.of(id.kind()), true, () -> module().text(id), v -> set(id, v), sound ? v -> IdCatalog.preview(v, previewVolume(id)) : null));
		return field[0];
	}

	/** The volume a sound is played at (its "…Volume" setting next to it, if it has one), for previews. */
	private float previewVolume(IdSetting sound) {
		SettingDescriptor<?> volume = module().descriptor().setting(sound.id() + "Volume");
		return volume instanceof IntSetting v ? module().integer(v) / 100F : 1F;
	}

	/** A value for each of many ids: how many are changed; pressing opens the list with − and + per row. */
	private Node valuesField(IdValuesSetting values) {
		PageControls.PickField[] field = new PageControls.PickField[1];
		boolean sound = values.kind() == com.flintclient.client.control.model.IdKind.SOUND;
		ListPicker.Values model = new ListPicker.Values() {
			@Override
			public int get(String id) {
				return values.of(module().values(values), id);
			}

			@Override
			public void set(String id, int value) {
				ModulePage.this.set(values, values.with(module().values(values), id, value));
			}

			@Override
			public int min() {
				return values.min();
			}

			@Override
			public int max() {
				return values.max();
			}

			@Override
			public int step() {
				return values.step();
			}

			@Override
			public int defaultValue() {
				return values.entryDefault();
			}

			@Override
			public String unit() {
				return values.unit();
			}
		};
		field[0] = new PageControls.PickField(() -> {
			int n = module().values(values).size();
			return n == 0 ? "None changed" : n == 1 ? "1 changed" : n + " changed";
		}, () -> window.openValues(field[0], values.label(), IdCatalog.of(values.kind()), model,
			sound ? v -> IdCatalog.preview(v, model.get(v) / 100F) : null));
		return field[0];
	}

	/** A set of ids: how many are in it; pressing opens the list with a tick per row. Slots: how many; pressing twice unlocks all. */
	private Node listField(IdListSetting list) {
		if (list.kind() == com.flintclient.client.control.model.IdKind.SLOT) {
			long[] askedAt = {0};
			return new PageControls.PickField(() -> {
				int n = module().ids(list).size();
				if (n > 0 && System.currentTimeMillis() - askedAt[0] < 3000) {
					return "Press again to unlock all";
				}
				return n == 0 ? "None locked" : n == 1 ? "1 locked · press to unlock" : n + " locked · press to unlock all";
			}, () -> {
				if (module().ids(list).isEmpty()) {
					return;
				}
				if (System.currentTimeMillis() - askedAt[0] < 3000) {
					askedAt[0] = 0;
					set(list, java.util.List.of());
				} else {
					askedAt[0] = System.currentTimeMillis();
				}
			});
		}
		PageControls.PickField[] field = new PageControls.PickField[1];
		ListPicker.Values model = new ListPicker.Values() {
			@Override
			public int get(String id) {
				return module().ids(list).contains(id) ? 1 : 0;
			}

			@Override
			public void set(String id, int value) {
				ModulePage.this.set(list, list.with(module().ids(list), id, value == 1));
			}

			@Override
			public int min() {
				return 0;
			}

			@Override
			public int max() {
				return 1;
			}

			@Override
			public int step() {
				return 1;
			}

			@Override
			public int defaultValue() {
				return 0;
			}

			@Override
			public String unit() {
				return "";
			}
		};
		field[0] = new PageControls.PickField(() -> {
			int n = module().ids(list).size();
			return n == 0 ? "None" : n == 1 ? "1 item" : n + " items";
		}, () -> window.openValues(field[0], list.label(), IdCatalog.of(list.kind()), model, null));
		return field[0];
	}

	/** Up to four short options fit a segmented control; more (or long ones) take a stepper. */
	private static boolean fitsSegmented(String[] labels) {
		int total = 0;
		for (String label : labels) {
			total += label.length() + 4;
		}
		return labels.length <= 4 && total <= 44;
	}

	private Node textField(TextSetting text) {
		com.flintclient.client.ui.kit.TextField field = new com.flintclient.client.ui.kit.TextField(text.label(), text.maxLength()) {
			private com.flintclient.client.render.FontFace face;

			@Override
			public float prefWidth() {
				return r(330);
			}

			@Override
			protected void layout() {
				com.flintclient.client.render.FontFace now = Type.VALUE.face();
				if (now != face) {
					face = now;
					look(new com.flintclient.client.ui.kit.TextField.Look(now, MenuStyle.FIELD, MenuStyle.FIELD_EDGE, MenuStyle.SEARCH_EDGE_HOVER,
						MenuStyle.SEARCH_FOCUS, r(12), r(18), 0, 0, MenuStyle.ROW_TEXT, MenuStyle.LABEL, MenuStyle.LABEL));
				}
				super.layout();
			}

			@Override
			protected void paint(Canvas c) {
				// Follows the model (a reset, another layout) while it isn't being edited.
				if (!focused() && !text().equals(module().text(text))) {
					setText(module().text(text));
				}
				super.paint(c);
			}
		};
		field.setText(module().text(text));
		field.onChange(value -> set(text, value));
		return field;
	}

	private <E extends Enum<E> & Labelled> Segmented segmented(ChoiceSetting<E> choice) {
		List<E> options = choice.options();
		String[] labels = new String[options.size()];
		for (int i = 0; i < labels.length; i++) {
			labels[i] = options.get(i).label();
		}
		return new Segmented(labels, () -> options.indexOf(module().choice(choice)), i -> set(choice, options.get(i)));
	}

	/** A decimal setting in whole steps of its step size (its value shown with the step's decimals). */
	private Slider floatSlider(FloatSetting number) {
		int steps = Math.round((number.max() - number.min()) / number.step());
		int decimals = MenuMath.decimals(number.step());
		return new Slider(0, steps, 1, "", () -> Math.round((module().decimal(number) - number.min()) / number.step()),
			v -> set(number, number.min() + v * number.step())).display(
				v -> String.format(java.util.Locale.ROOT, "%." + decimals + "f", number.min() + v * number.step()),
				text -> {
					Double typed = MenuMath.parseNumber(text);
					return typed == null ? null : (int) Math.round((typed - number.min()) / number.step());
				});
	}

	/** Rows every HUD module has that aren't settings: its size, and putting it back where it started. */
	private void extras(Fold fold) {
		String[] sizes = {"½", "¾", "1", "1¼", "1½", "2"};
		Segmented scale = new Segmented(sizes, () -> ScaleSteps.index(module().placement().scale()),
			i -> changed(module().setPlacement(module().placement().withScale(ScaleSteps.step(i)))));
		fold.add(new Row("Scale", "The whole module, times its normal size", scale, null));
		TextButton position = new TextButton("Reset position", Icon.MOVE, PageControls.Look.PLAIN, () -> changed(module().resetPlacement()));
		fold.add(new Row("Position", "Back to where it starts on screen", position, null));
	}

	/**
	 * What the page says above the settings: why a module isn't available, what a partial one lacks, where its data
	 * comes from; null when there's nothing to add. A module without settings says so.
	 */
	static @Nullable String about(ModuleDescriptor descriptor) {
		StringBuilder text = new StringBuilder();
		switch (descriptor.status()) {
			case NOT_BUILT -> text.append("Not built yet. ").append(descriptor.statusNote());
			case BLOCKED -> text.append("Unavailable. ").append(descriptor.statusNote());
			case PARTIAL -> text.append("Not everything is built yet: ").append(descriptor.statusNote()).append('.');
			default -> {
			}
		}
		if (descriptor.note() != null) {
			if (!text.isEmpty()) {
				text.append('\n');
			}
			text.append(descriptor.note());
		}
		if (descriptor.sections().isEmpty() && descriptor.status().usable() && !descriptor.hud()) {
			if (!text.isEmpty()) {
				text.append('\n');
			}
			text.append("This module has no settings: turn it on or off from its card.");
		}
		return text.isEmpty() ? null : text.toString();
	}

	private <T> void set(SettingDescriptor<T> setting, T value) {
		changed(module().set(setting, value));
	}

	private void changed(boolean changed) {
		if (changed) {
			ControlState.settings().recordChange(moduleId);
		}
	}

	// ---------- header ----------

	private void setAsking(boolean value) {
		asking = value;
		reset.setVisible(!value && resettable);
		keep.setVisible(value);
		confirm.setVisible(value);
		invalidate();
	}

	private void resetSettings() {
		changed(module().reset());
		setAsking(false);
	}

	/** Esc: a question first, then the page closes (the menu's job). */
	boolean escape() {
		if (asking) {
			setAsking(false);
			return true;
		}
		return false;
	}

	// ---------- layout ----------

	@Override
	protected void layout() {
		float bar = r(HEADER);
		back.setBounds(x, y, back.prefWidth(), bar);
		// Right to left: the reset (or its question), Mod Preview, Move.
		float at = x + w;
		if (asking) {
			at -= confirm.prefWidth();
			confirm.setBounds(at, y, confirm.prefWidth(), bar);
			at -= r(12) + keep.prefWidth();
			keep.setBounds(at, y, keep.prefWidth(), bar);
		} else if (resettable) {
			at -= reset.prefWidth();
			reset.setBounds(at, y, reset.prefWidth(), bar);
		} else {
			at += r(12);
		}
		at -= r(12) + preview.prefWidth();
		preview.setBounds(at, y, preview.prefWidth(), bar);
		at -= r(12) + bar;
		move.setBounds(at, y, bar, bar);
		boolean world = ControlState.inWorld();
		if (!hud) {
			move.setEnabled(false);
		} else if (!world) {
			move.setDisabled("Join a world to move it on screen");
		} else if (!module().enabled()) {
			move.setDisabled("Turn " + module().descriptor().name() + " on to move it");
		} else {
			move.setEnabled(true);
		}

		float top = y + bar + r(GAP);
		float bodyH = Math.max(0, y + h - top);
		scroll.flatBar(MenuStyle.SCROLL_THUMB, r(30), r(120));
		if (!state.previewOn || !hud) {
			stacked = false;
			previewBox.setVisible(false);
			column.columns = !wide && w >= r(1500) ? 2 : 1;
			scroll.setBounds(x, top, w, bodyH);
			return;
		}
		column.columns = 1;
		previewBox.setVisible(true);
		float settingsW = (float) Math.floor((w - r(32)) * 0.58F);
		float previewW = w - settingsW - r(32);
		stacked = previewW < r(520);
		if (stacked) {
			float ph = Math.min(r(520), bodyH * 0.42F);
			previewBox.setBounds(x, top, w, ph);
			scroll.setBounds(x, top + ph + r(24), w, Math.max(0, bodyH - ph - r(24)));
		} else {
			scroll.setBounds(x, top, settingsW, bodyH);
			previewBox.setBounds(x + settingsW + r(32), top, previewW, bodyH);
		}
	}

	@Override
	protected void paint(Canvas c) {
		// The description beside Back (or the reset question in its place), cut short before the buttons.
		float left = back.x() + back.width() + r(26);
		float limit = (asking ? keep.x() : hud ? move.x() : resettable ? reset.x() : x + w) - r(24) - left;
		float cy = y + r(HEADER) / 2;
		if (limit > r(80)) {
			if (asking) {
				MenuStyle.text(c, askText, left, cy, MenuStyle.DANGER_TEXT);
			} else {
				MenuStyle.icon(c, Icon.INFO, left + r(15), cy, r(30), MenuStyle.INFO);
				Label line = sentence;
				MenuStyle.fitted(c, line, left + r(44), cy, MenuStyle.SENTENCE_TEXT, limit - r(44));
			}
		}
	}

	// ---------- parts ----------

	/** The sections, one under another (two columns of sections when the page is wide and has no preview). */
	private static final class Column extends Node {
		int columns = 1;

		@Override
		public float prefHeight(float width) {
			if (columns == 2) {
				float colW = (width - r(32)) / 2;
				return Math.max(height(colW, 0), height(colW, 1)) + r(24);
			}
			return height(width, -1) + r(24);
		}

		private float height(float width, int only) {
			float total = 0;
			int k = 0;
			for (int i = 0; i < childCount(); i++) {
				Node fold = child(i);
				if (only >= 0 && i % 2 != only) {
					continue;
				}
				total += (k++ > 0 ? r(24) : 0) + fold.prefHeight(width);
			}
			return total;
		}

		@Override
		protected void layout() {
			if (columns == 2) {
				float colW = (w - r(32)) / 2;
				float[] at = {y, y};
				for (int i = 0; i < childCount(); i++) {
					Node fold = child(i);
					int col = i % 2;
					float fh = fold.prefHeight(colW);
					fold.setBounds(x + col * (colW + r(32)), at[col], colW, fh);
					at[col] += fh + r(24);
				}
				return;
			}
			float at = y;
			for (int i = 0; i < childCount(); i++) {
				Node fold = child(i);
				float fh = fold.prefHeight(w);
				fold.setBounds(x, at, w, fh);
				at += fh + r(24);
			}
		}
	}

	/** A titled section that folds: a header to click, then its rows on a card. */
	private final class Fold extends Node {
		private static final float HEAD = 60;
		private final Label title;
		private final Head head;
		private final List<Row> rows = new ArrayList<>();
		private boolean open;
		private float openness;

		/** Where the player's choice is kept: "moduleId/Title" when folded away, "+moduleId/Title" when opened. */
		private final String key;

		Fold(String title, boolean open) {
			this.title = new Label(Type.SECTION_TITLE, title);
			this.key = moduleId + "/" + title;
			ControlSettings settings = ControlState.settings();
			if (settings.sectionCollapsed(key)) {
				open = false;
			} else if (settings.sectionCollapsed("+" + key)) {
				open = true;
			}
			this.open = open;
			this.openness = open ? 1 : 0;
			head = add(new Head());
			head.name(title);
		}

		Row add(Row row) {
			rows.add(super.add(row));
			row.setVisible(open);
			return row;
		}

		private float rowsHeight(float width) {
			float total = 0;
			for (Row row : rows) {
				total += row.prefHeight(width);
			}
			return total;
		}

		@Override
		public float prefHeight(float width) {
			return r(HEAD) + openness * (rowsHeight(width) + r(12));
		}

		@Override
		protected void layout() {
			head.setBounds(x, y, w, r(HEAD));
			float at = y + r(HEAD) + r(6);
			for (Row row : rows) {
				float rh = row.prefHeight(w);
				row.setBounds(x, at, w, rh);
				at += rh;
			}
		}

		@Override
		protected void paint(Canvas c) {
			float target = open ? 1 : 0;
			if (openness != target) {
				openness = Kit.reduced() ? target : Kit.approach(openness, target, 0.05F);
				if (Math.abs(openness - target) < 0.01F) {
					openness = target;
				}
				for (Row row : rows) {
					row.setVisible(openness > 0.01F);
				}
				// The sections under it move with it.
				invalidate();
			}
			if (openness > 0.01F) {
				float cardY = y + r(HEAD);
				float cardH = h - r(HEAD);
				MenuStyle.framed(c, x, cardY, w, cardH, r(20), MenuStyle.SECTION_CARD, MenuStyle.SECTION_EDGE);
			}
		}

		@Override
		protected void paintChildren(Canvas c) {
			if (openness >= 0.999F || openness <= 0.01F) {
				super.paintChildren(c);
				return;
			}
			// While folding, the rows are cut to the card.
			int x0 = c.px(x);
			int y0 = c.px(y);
			c.pushClip(x0, y0, c.px(x + w), c.px(y + h));
			try {
				super.paintChildren(c);
			} finally {
				c.popClip();
			}
		}

		/** The section's header: a chevron and its title; it folds the section. */
		private final class Head extends com.flintclient.client.ui.kit.Pressable {
			Head() {
				super(null);
				setAction(() -> {
					open = !open;
					ControlSettings settings = ControlState.settings();
					settings.setSectionCollapsed(key, !open);
					settings.setSectionCollapsed("+" + key, open);
					for (Row row : rows) {
						row.setVisible(true);
					}
					Fold.this.invalidate();
				});
			}

			@Override
			public String narration() {
				return accessibleName() + ", section, " + (open ? "expanded" : "collapsed");
			}

			@Override
			protected void paint(Canvas c) {
				float cy = y + h / 2;
				float turn = openness;
				MenuStyle.icon(c, turn > 0.5F ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT, x + r(16), cy, r(26),
					Palette.lerp(MenuStyle.LABEL, MenuStyle.TAB_TEXT_SELECTED, hover));
				MenuStyle.text(c, title, x + r(40), cy, Palette.lerp(MenuStyle.ROW_TEXT, 0xFFFFFFFF, hover));
				MenuStyle.focusRing(c, x, y, w, h, r(12), focus);
			}
		}
	}

	/**
	 * One row: the label (and a note under it, wrapped to two lines when long) on the left, the control on the right;
	 * dimmed with its reason when moot.
	 */
	private final class Row extends Node {
		/** The reset button's side and its gap to the control, in R. */
		private static final float RESET = 40;
		private static final float RESET_GAP = 14;
		private final Label label;
		private final Label note;
		private final Label second = new Label(Type.ROW_NOTE, "");
		private final @Nullable String description;
		private final Node control;
		private final @Nullable SettingDescriptor<?> setting;
		private @Nullable String shownReason;
		private float dim = -1;
		private float wrappedFor = -1;
		private String wrappedText = "";
		/** Back to this setting's default; shown only while it holds another value (and isn't moot). */
		private final MenuControls.@Nullable IconButton reset;
		/** Other Flint settings and Minecraft controls on the same key (key settings only), or null. */
		private @Nullable String conflict;
		private String conflictFor = "\0";

		Row(String label, @Nullable String description, Node control, @Nullable SettingDescriptor<?> setting) {
			this.label = new Label(Type.ROW_LABEL, label);
			this.description = description;
			this.note = new Label(Type.ROW_NOTE, description != null ? description : "");
			this.control = add(control);
			this.setting = setting;
			control.name(label);
			if (setting != null) {
				MenuControls.IconButton button = add(new MenuControls.IconButton(Icon.RESET, 22, 10, 0, 0x14FFFFFF, 0, MenuStyle.LABEL,
					() -> changed(module().resetSetting(setting))));
				button.name("Reset " + label + " to its default");
				button.tooltip("Back to default");
				button.setVisible(false);
				reset = button;
			} else {
				reset = null;
			}
		}

		/** The reset button's room before the control (kept even while it's hidden, so labels don't jump). */
		private float resetRoom() {
			return reset != null ? r(RESET + RESET_GAP) : 0;
		}

		/** The room for the label and note beside the control, in layout units. */
		private float room(float width) {
			return width - r(28) - control.prefWidth() - resetRoom() - r(28) - r(24);
		}

		/** Splits the note into two lines when it doesn't fit {@code room}. Returns whether it takes two. */
		private boolean wrap(float room) {
			String text = shownReason != null ? shownReason : conflict != null ? conflict : description != null ? description : "";
			if (room == wrappedFor && text.equals(wrappedText)) {
				return !second.text().isEmpty();
			}
			wrappedFor = room;
			wrappedText = text;
			Canvas c = measure();
			note.set(text);
			second.set("");
			if (text.isEmpty() || note.width(c) <= room) {
				return false;
			}
			String[] words = text.split(" ");
			StringBuilder first = new StringBuilder();
			int i = 0;
			for (; i < words.length; i++) {
				String candidate = first.isEmpty() ? words[i] : first + " " + words[i];
				note.set(candidate);
				if (note.width(c) > room && !first.isEmpty()) {
					break;
				}
				first.setLength(0);
				first.append(candidate);
			}
			note.set(first.toString());
			second.set(String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)));
			return !second.text().isEmpty();
		}

		@Override
		public float prefHeight(float width) {
			if (note.text().isEmpty() && description == null) {
				return r(80);
			}
			return r(wrap(room(width)) ? 130 : 104);
		}

		@Override
		protected void layout() {
			float cw = control.prefWidth();
			float ch = control instanceof Switch ? r(Switch.H) : control instanceof Slider ? r(Slider.H)
				: control instanceof ColorSwatch ? r(ColorSwatch.H) : control instanceof com.flintclient.client.ui.kit.TextField ? r(56) : r(52);
			control.setBounds(x + w - r(28) - cw, y + (h - ch) / 2, cw, ch);
			if (reset != null) {
				reset.setBounds(control.x() - r(RESET_GAP) - r(RESET), y + (h - r(RESET)) / 2, r(RESET), r(RESET));
			}
		}

		/** The conflict note for a key setting, worked out again only when its key changes. */
		private void checkConflict() {
			if (!(setting instanceof KeySetting key)) {
				return;
			}
			String bound = module().text(key);
			if (bound.equals(conflictFor)) {
				return;
			}
			conflictFor = bound;
			List<String> uses = com.flintclient.client.control.model.KeyConflicts.flintUses(ControlState.layout(), bound, moduleId, key.id());
			for (String mapping : com.flintclient.client.control.platform.Mc263ControlPlatform.mappingsUsing(bound)) {
				uses.add("Minecraft: " + mapping);
			}
			conflict = com.flintclient.client.control.model.KeyConflicts.warning(uses);
			wrappedFor = -1;
			invalidate();
		}

		@Override
		protected void paint(Canvas c) {
			// Moot settings: disabled, with their reason as the note (only when the reason changes, so nothing is made per frame).
			String reason = setting != null ? ModuleSettings.inactiveReason(module(), setting) : null;
			if (reason != shownReason) {
				shownReason = reason;
				if (reason == null) {
					control.setEnabled(true);
				} else {
					control.setDisabled(reason);
				}
				wrappedFor = -1;
				invalidate();
			}
			checkConflict();
			if (reset != null) {
				boolean show = reason == null && !module().isDefault(setting);
				if (show != reset.visible()) {
					reset.setVisible(show);
				}
			}
			float labelLeft = x + r(28);
			float room = control.x() - resetRoom() - r(24) - labelLeft;
			boolean two = wrap(room);
			dim = MenuControls.ease(dim, reason != null);
			float opacity = c.opacity();
			boolean hasNote = !note.text().isEmpty();
			float cy = y + h / 2;
			float labelY = hasNote ? cy - r(two ? 27 : 14) : cy;
			c.setOpacity(opacity * (1 - 0.5F * dim));
			MenuStyle.fitted(c, label, labelLeft, labelY, MenuStyle.ROW_TEXT, room);
			if (hasNote) {
				int ink = reason != null ? MenuStyle.DANGER_TEXT : conflict != null ? MenuStyle.WARN_TEXT : MenuStyle.LABEL;
				MenuStyle.fitted(c, note, labelLeft, cy + r(two ? 5 : 18), ink, room);
				if (two) {
					MenuStyle.fitted(c, second, labelLeft, cy + r(31), ink, room);
				}
			}
			c.setOpacity(opacity);
			if (this != lastRow()) {
				c.fill(c.px(x + r(24)), c.px(y + h) - MenuStyle.edge(), c.px(x + w - r(24)), c.px(y + h), MenuStyle.ROW_RULE);
			}
		}

		@Override
		protected void paintChildren(Canvas c) {
			float opacity = c.opacity();
			c.setOpacity(opacity * (1 - 0.55F * Math.max(0, dim)));
			try {
				super.paintChildren(c);
			} finally {
				c.setOpacity(opacity);
			}
		}

		private Node lastRow() {
			Node parent = parent();
			return parent != null ? parent.child(parent.childCount() - 1) : this;
		}
	}

	/** A note above the settings: a module's status and where its data comes from, wrapped to the column. */
	private static final class InfoCard extends Node {
		private static final float PAD = 26;
		private final String text;
		private final ModuleDescriptor.Status status;
		private final List<Label> lines = new ArrayList<>();
		private float wrappedFor = -1;
		private float lineH;

		InfoCard(String text, ModuleDescriptor.Status status) {
			this.text = text;
			this.status = status;
		}

		/** Wraps the text to {@code width} (layout units) when the width or the scale changed. */
		private void wrap(float width) {
			Canvas c = measure();
			float key = width * 997 + MenuStyle.r(1000);
			if (key == wrappedFor) {
				return;
			}
			wrappedFor = key;
			lines.clear();
			Label probe = new Label(Type.ROW_NOTE, "");
			for (String block : text.split("\n")) {
				StringBuilder current = new StringBuilder();
				for (String word : block.split(" ")) {
					String candidate = current.isEmpty() ? word : current + " " + word;
					probe.set(candidate);
					if (probe.width(c) <= width || current.isEmpty()) {
						current.setLength(0);
						current.append(candidate);
					} else {
						lines.add(new Label(Type.ROW_NOTE, current.toString()));
						current.setLength(0);
						current.append(word);
					}
				}
				lines.add(new Label(Type.ROW_NOTE, current.toString()));
			}
			lineH = r(34);
		}

		@Override
		public float prefHeight(float width) {
			wrap(width - 2 * r(PAD) - r(44));
			return lines.size() * lineH + 2 * r(PAD) - r(6);
		}

		@Override
		protected void paint(Canvas c) {
			wrap(w - 2 * r(PAD) - r(44));
			boolean warn = status == ModuleDescriptor.Status.BLOCKED || status == ModuleDescriptor.Status.NOT_BUILT;
			MenuStyle.framed(c, x, y, w, h, r(20), MenuStyle.SECTION_CARD, warn ? Palette.withAlpha(MenuStyle.DANGER, 0.55F) : MenuStyle.SECTION_EDGE);
			MenuStyle.icon(c, warn ? Icon.WARNING : Icon.INFO, x + r(PAD) + r(15), y + r(PAD) + lineH / 2 - r(3), r(28),
				warn ? MenuStyle.DANGER_TEXT : MenuStyle.INFO);
			float top = y + r(PAD) + lineH / 2 - r(3);
			for (int i = 0; i < lines.size(); i++) {
				MenuStyle.fitted(c, lines.get(i), x + r(PAD) + r(44), top + i * lineH, MenuStyle.ROW_TEXT, w - 2 * r(PAD) - r(44));
			}
		}
	}

	/**
	 * The preview: the owner's scene, cropped to fill the box without stretching, with the module drawn on it by the HUD's
	 * own code at its in-game size and sample numbers, a "Sample" tag and a line saying the game shows the real value.
	 */
	private final class Preview extends Node {
		private final Label tag = new Label(Type.BADGE, "SAMPLE");
		private final Label caption = new Label(Type.CAPTION, "");

		@Override
		protected void paint(Canvas c) {
			float pad = r(16);
			float captionH = r(56);
			MenuStyle.framed(c, x, y, w, h, r(22), MenuStyle.PREVIEW_FRAME, MenuStyle.SECTION_EDGE);
			float ix = x + pad;
			float iy = y + pad;
			float iw = w - 2 * pad;
			float ih = h - 2 * pad - captionH;
			int x0 = c.px(ix);
			int y0 = c.px(iy);
			int pw = c.px(ix + iw) - x0;
			int ph = c.px(iy + ih) - y0;
			Picture.Level level = PICTURE.best(pw, ph);
			if (level != null) {
				c.picture(level, x0, y0, pw, ph, c.px(r(16)), MenuStyle.PREVIEW_FRAME);
			} else {
				c.roundRect(x0, y0, pw, ph, c.px(r(16)), 0xFF2A2724);
			}
			int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
			int used = HudRenderer.drawSample(c, ControlState.layout(), moduleId, x0, y0, pw, ph, guiScale);
			// True to size unless that doesn't fit, which it then says.
			caption.set(used > 0 && used < guiScale ? "Shown smaller to fit. In the game it shows your real values."
				: "Sample values. In the game it shows your real ones.");
			// The tag: this is a sample, not a measurement.
			float tw = tag.width(c) + 2 * r(14);
			MenuStyle.box(c, ix + r(16), iy + r(16), tw, r(36), r(10), 0xCC1A1815);
			MenuStyle.text(c, tag, ix + r(30), iy + r(34), MenuStyle.BADGE_TEXT);
			MenuStyle.fitted(c, caption, x + pad + r(6), y + h - pad - captionH / 2, MenuStyle.LABEL, w - 2 * pad - r(12));
		}
	}
}
