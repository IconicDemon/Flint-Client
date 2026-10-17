package com.flintclient.client.control.editor;

import com.flintclient.client.control.center.SettingControls;
import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Button;
import com.flintclient.client.ui.kit.Column;
import com.flintclient.client.ui.kit.IconButton;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.ScrollView;
import com.flintclient.client.ui.kit.Segmented;
import com.flintclient.client.ui.kit.SettingRow;
import com.flintclient.client.ui.kit.Slider;
import com.flintclient.client.ui.kit.SliderModel;
import com.flintclient.client.ui.kit.Swatches;
import com.flintclient.client.ui.kit.Switch;
import com.flintclient.client.ui.kit.Tokens2;
import com.flintclient.client.ui.kit.Type;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The editor's inspector: a compact floating card, {@value #WIDTH} units wide, in the deck's colour, docked under the
 * toolbar on the side of the screen away from the selected module. Its header (the module's icon and name, a chevron
 * that folds the card into a 20-unit pill, and a close button that clears the selection) stays; under it the
 * most-used options, with the same rows and controls as the Control Center's inspector: style, size, text colour,
 * background and its opacity, padding and label, then "All settings", which opens the Control Center on that module.
 *
 * <p>Every change shows on the module at once and is one undo step (a slider drag is one step however far it goes).
 * The controls show the module's effective values (its own, else the layout's, else the style's). A setting the module
 * doesn't use in its style is disabled with the reason, never left as a control that changes nothing.
 */
final class EditorInspector extends Node {
	static final float WIDTH = 168;
	private static final float MARGIN = 8;
	private static final float HEADER = 20;
	private static final float PAD = 10;
	/** Clear of the toolbar: its edge distance and height, then the margin. */
	private static final float TOOLBAR_CLEARANCE = EditorToolbar.TOP + EditorToolbar.HEIGHT + MARGIN;
	private static final HudThemeId[] THEMES = HudThemeId.values();
	private static final HudColor[] COLORS = HudColor.values();
	private static final String[] STYLES = {"Layout", "Flint", "Classic", "Minimal"};
	private static final String[] SIZES = {"½", "¾", "1", "1¼", "1½", "2"};

	private final HudEditor editor;
	private final ScrollView scroll;
	private final Header header;
	private final Segmented style;
	private final Segmented size;
	private final Switch background;
	private final Slider opacity;
	private final Slider padding;
	private final Swatches colors;
	private final Switch label;
	private final SettingRow labelRow;
	/** The look rows a module with a look of its own (FPS) sets on its settings page instead. */
	private final SettingRow[] lookRows;
	private boolean collapsed;
	private float screenW;
	private float screenH;
	private boolean left;
	private boolean toolbarBottom;

	EditorInspector(HudEditor editor) {
		this.editor = editor;
		header = add(new Header(this, editor));
		Column column = new Column(0).padding(PAD, 2);
		style = new Segmented(STYLES, 0, i -> change(a -> a.withTheme(i == 0 ? null : THEMES[i - 1])));
		SettingRow styleRow = column.add(new SettingRow("Style", "Layout follows the layout's own style.", style));
		size = new Segmented(SIZES, ScaleSteps.index(ScaleSteps.DEFAULT), i -> editor.setScale(ScaleSteps.step(i)));
		column.add(new SettingRow("Size", "Times the module's normal size.", size));
		colors = SettingControls.swatches(COLORS, HudColor.DEFAULT, value -> change(a -> a.withTextColor(value.isDefault() ? null : value)));
		SettingRow colorRow = column.add(new SettingRow("Text color", "The text color. Default uses the style's own.", colors));
		background = new Switch(true, on -> change(a -> a.withBackground(on)));
		SettingRow backgroundRow = column.add(new SettingRow("Background", null, background));
		opacity = new Slider(new SliderModel(Appearance.BACKGROUND_OPACITY.min(), Appearance.BACKGROUND_OPACITY.max(),
			Appearance.BACKGROUND_OPACITY.step()), 70, 0, "%",
			v -> live(a -> a.withBackgroundOpacity(Math.round(v))), v -> editor.session().end());
		SettingRow opacityRow = column.add(new SettingRow("Opacity", null, opacity));
		padding = new Slider(new SliderModel(Appearance.PADDING.min(), Appearance.PADDING.max(), Appearance.PADDING.step()), 3, 0, null,
			v -> live(a -> a.withPadding(Math.round(v))), v -> editor.session().end());
		SettingRow paddingRow = column.add(new SettingRow("Padding", null, padding));
		label = new Switch(true, on -> change(a -> a.withShowLabel(on)));
		labelRow = column.add(new SettingRow("Label", "Shows the module's name beside its value.", label));
		lookRows = new SettingRow[] {styleRow, colorRow, backgroundRow, opacityRow, paddingRow};
		column.add(new Spacer(4));
		column.add(new Trailing(new Button("All settings", Button.Variant.ACCENT, Type.META, editor::openFullSettings)
			.trailingIcon(Icon.CHEVRON_RIGHT).iconSize(7)));
		column.add(new Spacer(6));
		scroll = add(new ScrollView(column));
	}

	/** One change to the selection's look, as one undo step. */
	private void change(UnaryOperator<Appearance> edit) {
		ModuleState state = selection();
		if (state != null) {
			editor.session().edit(() -> state.setAppearance(edit.apply(state.appearance())));
		}
	}

	/** A change while a slider is dragged: part of one step, ended when the slider lets go. */
	private void live(UnaryOperator<Appearance> edit) {
		ModuleState state = selection();
		if (state != null) {
			editor.session().begin();
			state.setAppearance(edit.apply(state.appearance()));
		}
	}

	private @Nullable ModuleState selection() {
		int index = editor.selected();
		return index < 0 ? null : editor.module(index);
	}

	/** Shows module {@code index}'s values (none: nothing to show); the listeners aren't told. */
	void bind(int index) {
		if (index < 0) {
			return;
		}
		ModuleState state = editor.module(index);
		Appearance own = state.appearance();
		Appearance shown = editor.layout().effectiveAppearance(state);
		header.show(state);
		style.setSelected(own.theme() == null ? 0 : own.theme().ordinal() + 1);
		size.setSelected(ScaleSteps.index(state.placement().scale()));
		background.setOn(shown.background());
		opacity.setValue(shown.backgroundOpacity());
		if (shown.background()) {
			opacity.setEnabled(true);
		} else {
			opacity.setDisabled("Turn the background on to set its opacity");
		}
		padding.setValue(shown.padding());
		String noPadding = HudRenderer.noPaddingReason(state.id());
		if (noPadding == null) {
			padding.setEnabled(true);
		} else {
			padding.setDisabled(noPadding);
		}
		colors.setDefaultColor(shown.theme() == HudThemeId.CLASSIC ? 0xFFFFFFFF : Tokens2.TEXT);
		HudColor colour = own.textColor();
		colors.setSelected(colour == null ? 0 : colour.ordinal());
		label.setOn(shown.showLabel());
		String noLabel = HudRenderer.noLabelReason(state.id(), shown.theme());
		if (noLabel == null) {
			label.setEnabled(true);
		} else {
			label.setDisabled(noLabel);
		}
		// Modules with a label position of their own set it in the Control Center.
		labelRow.setVisible(ModuleSettings.labelSetting(state.descriptor()) == null);
		// A module with a look of its own (FPS) keeps only Size here; "All settings" opens the rest.
		boolean ownLook = HudRenderer.ownLook(state.id());
		for (SettingRow row : lookRows) {
			row.setVisible(!ownLook);
		}
	}

	boolean collapsed() {
		return collapsed;
	}

	void toggleCollapsed() {
		collapsed = !collapsed;
		scroll.setVisible(!collapsed);
		place(screenW, screenH, left, toolbarBottom);
	}

	/** Docked under the toolbar (over it when the toolbar is at the bottom), at the left or right of the screen. */
	void place(float width, float height, boolean left, boolean toolbarBottom) {
		this.screenW = width;
		this.screenH = height;
		this.left = left;
		this.toolbarBottom = toolbarBottom;
		float top = toolbarBottom ? MARGIN : TOOLBAR_CLEARANCE;
		float bottom = toolbarBottom ? height - TOOLBAR_CLEARANCE : height - MARGIN;
		float available = Math.max(HEADER, bottom - top);
		float h;
		float w;
		if (collapsed) {
			w = Math.min(WIDTH, header.collapsedWidth());
			h = HEADER;
		} else {
			w = WIDTH;
			h = Math.min(HEADER + 4 + scroll.content().prefHeight(WIDTH), available);
		}
		setBounds(left ? MARGIN : width - w - MARGIN, top, w, h);
	}

	@Override
	protected void layout() {
		header.setBounds(x, y, w, HEADER);
		scroll.setBounds(x, y + HEADER + 2, w, Math.max(0, h - HEADER - 2));
	}

	@Override
	public boolean interactive() {
		// The card's own background takes presses, so they never reach the modules under it.
		return true;
	}

	@Override
	protected void paint(Canvas c) {
		EditorToolbar.paintCard(c, x, y, w, h, collapsed ? HEADER / 2 : Tokens2.RADIUS_DECK);
		if (!collapsed) {
			int line = c.px(y + HEADER);
			c.fill(c.px(x + PAD), line, c.px(x + w - PAD), line + 1, Tokens2.hairline());
		}
	}

	/** The selected module's icon and name, a fold chevron and a close button. */
	private static final class Header extends Node {
		private static final float ICON = 9;
		private final EditorInspector inspector;
		private final TextLine name = new TextLine(Type.NAME, "");
		private final IconButton fold;
		private final IconButton close;
		private Icon icon = Icon.GRID;

		Header(EditorInspector inspector, HudEditor editor) {
			this.inspector = inspector;
			this.fold = add(new IconButton(Icon.CHEVRON_UP, "Fold", IconButton.TINY, inspector::toggleCollapsed).iconSize(7).bare());
			this.close = add(new IconButton(Icon.CLOSE, "Close (I)", IconButton.TINY, () -> editor.setInspectorOpen(false)).iconSize(7).bare());
		}

		void show(ModuleState state) {
			name.set(state.descriptor().name());
			icon = com.flintclient.client.control.menu.MenuCatalog.icon(state.id());
			invalidate();
		}

		/** The pill's width when folded: icon, name and the two buttons. */
		float collapsedWidth() {
			Canvas c = measure();
			return PAD + ICON + 6 + (float) c.width(name) / c.scale() + 8 + 2 * IconButton.TINY + 4;
		}

		@Override
		protected void layout() {
			float by = y + (h - IconButton.TINY) / 2;
			close.setBounds(x + w - 4 - IconButton.TINY, by, IconButton.TINY, IconButton.TINY);
			fold.setBounds(close.x() - IconButton.TINY, by, IconButton.TINY, IconButton.TINY);
		}

		@Override
		protected void paint(Canvas c) {
			fold.setIcon(inspector.collapsed() ? Icon.CHEVRON_DOWN : Icon.CHEVRON_UP);
			fold.setName(inspector.collapsed() ? "Unfold" : "Fold");
			int size = c.px(ICON);
			int x0 = c.px(x + PAD);
			int center = c.px(y + h / 2);
			c.icon(icon, x0, center - size / 2, size, Tokens2.EMBER_TEXT);
			int textLeft = x0 + size + c.px(6);
			int textRight = c.px(fold.x() - 4);
			c.text(name, textLeft, c.baselineFor(name, center), Tokens2.TEXT, Math.max(0, textRight - textLeft));
		}
	}

	/** Holds one control at its own width, at the right. */
	private static final class Trailing extends Node {
		private final Node control;

		Trailing(Node control) {
			this.control = add(control);
		}

		@Override
		public float prefHeight(float width) {
			return control.prefHeight(width);
		}

		@Override
		protected void layout() {
			float cw = Math.min(w, control.prefWidth());
			control.setBounds(x + w - cw, y, cw, control.prefHeight(cw));
		}
	}

	/** Empty space in a column. */
	private static final class Spacer extends Node {
		private final float size;

		Spacer(float size) {
			this.size = size;
		}

		@Override
		public float prefHeight(float width) {
			return size;
		}
	}
}
