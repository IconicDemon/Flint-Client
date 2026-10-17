package com.flintclient.client.control.editor;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlCenter;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.center.CenterPrefs;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.hud.Snapper;
import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.client.control.hud.render.HudScene;
import com.flintclient.client.control.menu.MenuScreen;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.platform.HookGuard;
import com.flintclient.client.control.platform.Mc263ControlPlatform;
import com.flintclient.client.control.store.LayoutInfo;
import com.flintclient.client.control.store.LayoutStore;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.ScaledFont;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Button;
import com.flintclient.client.ui.kit.Dialog;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Tokens2;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The HUD editor's behaviour, for {@code HudEditorScreen}: the layout being edited (in place, with undo and a
 * copy to cancel back to), the selection, dragging with snapping, scaling, nudging, and drawing the editing
 * surface under the toolbar, inspector, hub and widget palette.
 *
 * <p>The game stays visible under a light dim and a faint 16-unit dot grid; the modules are drawn live by
 * {@link HudRenderer#drawScene} exactly where the HUD draws them. A press on a module selects it and a drag
 * moves it (the pointer is held until release); edges and centres snap to the screen edges and centre, other
 * modules and Minecraft's own HUD within {@link Snapper#THRESHOLD} units, shown by ember guide lines, unless
 * Alt (Option) is held. Shift+click adds a module to the selection (or takes it out) and a right-button drag draws a box
 * that selects every module it touches; a selection of several moves, nudges and resets its size together. The corner
 * handle (one module selected), the wheel over a module and the {@code [} {@code ]} keys change the size step, and
 * Space resets it; arrow keys nudge by 1 unit (10 with Ctrl, half a unit with Shift), a burst of presses being one undo
 * step. Tab (Shift+Tab) selects the next (previous) module. After every change the module is
 * re-anchored to the third of the screen its centre is in and kept fully on screen
 * ({@code HudPlacement.fromAbsolute}), once the next frame has measured it.
 *
 * <p>The toolbar sits at the top centre, or at the bottom centre while a module sits under its place at the
 * top (and none under the bottom one), so no module is hidden behind it; when both are taken it stays at the
 * top and steps back while the selected module is under it.
 *
 * <p>In the middle sits Flint's hub ({@link HubPanel}: EDIT FLINT MODS, WIDGETS and shortcuts into the module menu),
 * the widget palette docks at the right ({@link WidgetPalette}) and the controls are listed under "?" ({@link KeyLegend}).
 * Going to the module menu saves first; coming back, the editor carries on from what was saved.
 *
 * <p>Done, Right Shift and Escape (once the palette is closed and nothing is selected) write the stores once and return
 * to the game (or say why they couldn't); Cancel puts the copy back. If drawing the editor ever throws, the error is logged once, the
 * editor closes at the next tick without its unconfirmed changes, and stays off for the session (as every
 * Control Center path does), so Minecraft keeps running. Render thread.
 */
public final class HudEditor {
	static final int NONE = -1;
	private static final HookGuard GUARD = new HookGuard("hud editor", HudEditor::turnedOff);
	/** A light dim over the game (14 % black): the world stays the stage. */
	private static final int DIM = Palette.withAlpha(0xFF000000, 0.08F);
	/** The dot grid: white at 4 %, every 16 units. */
	private static final int DOT = 0x08FFFFFF;
	private static final int OUTLINE = Palette.withAlpha(Tokens2.TEXT, 0.45F);
	private static final int REASON = Palette.withAlpha(Tokens2.TEXT, 0.75F);
	/** Snapping guides: ember at 75 %, one pixel. */
	private static final int GUIDE = Palette.withAlpha(Tokens2.EMBER, 0.75F);
	/** The scale handle's side and its (slightly larger) hit area, in GUI units. */
	static final float HANDLE = 8.0F;
	private static final float HANDLE_HIT = 11.0F;

	private enum Drag {
		NONE,
		MOVE,
		SCALE
	}

	private final KitScreen screen;
	private final @Nullable Screen parent;
	/** Remade when the profile in use changed while the module menu was open (it then edits that profile). */
	private EditorSession session;
	private final HudScene scene = new HudScene();
	private final Snapper snapper = new Snapper();
	private final VanillaGuides guides = new VanillaGuides();
	private final EditorPatterns patterns = new EditorPatterns();
	private final TextLine[] reasons = new TextLine[ModuleRegistry.size()];
	private EditorStage stage;
	private EditorToolbar toolbar;
	private EditorInspector inspector;
	private WidgetPalette palette;
	private HubPanel hub;
	private KeyLegend legend;

	private int selected = NONE;
	/** A module to select once the first frame has placed it ("Edit position" in the Control Center). */
	private int pendingSelect = NONE;
	/** The inspector is open (only from the mini-bar's gear or I; closed by default so arranging is unobstructed). */
	private boolean inspectorOpen;
	/** H: every tool tucked away but a small pill, so the HUD can be judged as it will look. */
	private boolean chromeHidden;
	/** Snapping to edges, centres, other modules and Minecraft's HUD (Alt turns it off for one drag). */
	private boolean snapping = true;
	private MiniBar miniBar;
	private int hovered = NONE;
	private boolean showAreas = CenterPrefs.editorAreas();
	private boolean toolbarBottom;
	private boolean leaving;
	/** Drawing failed: leave at the next tick. */
	private boolean failed;
	/** A module that changed: re-anchor and clamp it once the next frame has measured it (NONE: nothing to do). */
	private int settleIndex = NONE;
	private long seenRevision = -1;
	private float screenW = 1;
	private float screenH = 1;

	private Drag drag = Drag.NONE;
	private boolean moved;
	private float pressX;
	private float pressY;
	private float grabX;
	private float grabY;
	private float startLeft;
	private float startTop;
	private float startW;
	private float startH;
	private float startScale;
	private float startDistance;
	/** The selection beyond the primary {@link #selected}: every module in it moves, nudges and resets together. */
	private final boolean[] group = new boolean[ModuleRegistry.size()];
	private final float[] groupStartX = new float[ModuleRegistry.size()];
	private final float[] groupStartY = new float[ModuleRegistry.size()];
	/** Settle every module of the group after the gesture (not just {@link #settleIndex}). */
	private boolean settleGroup;
	/** A right-button drag on the surface: a box that selects every module it touches. */
	private boolean boxing;
	private float boxX0;
	private float boxY0;
	private float boxX1;
	private float boxY1;
	private boolean guideV;
	private boolean guideH;
	private float guideX;
	private float guideY;
	private float guideVFrom;
	private float guideVTo;
	private float guideHFrom;
	private float guideHTo;

	public HudEditor(KitScreen screen, @Nullable Screen parent, Layout layout) {
		this.screen = screen;
		this.parent = parent;
		this.session = new EditorSession(layout);
		ScaledFont small = ScaledFont.of(FlintFonts.LABEL, 5.0F);
		for (int i = 0; i < reasons.length; i++) {
			reasons[i] = new TextLine(small, "");
		}
	}

	/** Whether the editor still works this session (it turns itself off after a drawing failure). */
	public static boolean available() {
		return GUARD.on();
	}

	// ---------- set-up (from the screen) ----------

	public void build(Node root) {
		stage = root.add(new EditorStage(this));
		miniBar = root.add(new MiniBar(this));
		toolbar = root.add(new EditorToolbar(this));
		inspector = root.add(new EditorInspector(this));
		hub = root.add(new HubPanel(this));
		palette = root.add(new WidgetPalette(this));
		legend = root.add(new KeyLegend());
		miniBar.setVisible(false);
		inspector.setVisible(false);
		palette.setVisible(false);
	}

	/**
	 * The screen is shown, first or again (back from the module menu, whose changes were saved): what the editor holds
	 * now is where Cancel goes back to, and the history starts again.
	 */
	public void shown() {
		if (leaving) {
			leaving = false;
			cancelDrag();
			Layout now = ControlState.layout();
			if (now != session.layout()) {
				// Another profile was chosen in the menu: edit that one, from where it is.
				session = new EditorSession(now);
				if (stage != null) {
					select(NONE);
				}
			} else {
				session.accept();
			}
		}
	}

	/** Selects {@code moduleId} once the first frame has placed it. */
	public void selectWhenShown(String moduleId) {
		pendingSelect = ModuleRegistry.indexOf(moduleId);
	}

	boolean chromeHidden() {
		return chromeHidden;
	}

	/** H: hides every tool but a small pill, or brings them back. */
	void toggleChrome() {
		chromeHidden = !chromeHidden;
		if (chromeHidden) {
			closePalette();
		}
		syncPanels();
		stage.invalidate();
	}

	boolean inspectorOpen() {
		return inspectorOpen;
	}

	/** The gear or I: opens or closes the inspector for the selected module. */
	void toggleInspector() {
		setInspectorOpen(!inspectorOpen);
	}

	void setInspectorOpen(boolean open) {
		inspectorOpen = open;
		if (open && selected >= 0) {
			inspector.bind(selected);
		}
		syncPanels();
		stage.invalidate();
	}

	boolean snapping() {
		return snapping;
	}

	void setSnapping(boolean on) {
		snapping = on;
	}

	/** Which floating pieces show: the inspector (open, with a selection), the mini-bar (a selection). */
	private void syncPanels() {
		inspector.setVisible(inspectorOpen && selected != NONE && !chromeHidden);
		miniBar.setVisible(selected != NONE && !chromeHidden);
		hub.setVisible(!chromeHidden);
		legend.setVisible(!chromeHidden);
	}

	/** Places the toolbar, inspector, hub and palette for a screen of {@code width × height} UI units. */
	public void layout(float width, float height) {
		stage.setBounds(0, 0, width, height);
		toolbar.place(width, height, toolbarBottom);
		inspector.place(width, height, selected >= 0 && inspectorLeft(), toolbarBottom);
		palette.place(width, height, toolbarBottom);
		hub.place(width, height);
		legend.place(height);
	}

	KitScreen screen() {
		return screen;
	}

	EditorSession session() {
		return session;
	}

	Layout layout() {
		return session.layout();
	}

	HudScene scene() {
		return scene;
	}

	int selected() {
		return selected;
	}

	boolean dragging() {
		return drag != Drag.NONE && (drag == Drag.SCALE || moved);
	}

	boolean showAreas() {
		return showAreas;
	}

	void setShowAreas(boolean on) {
		showAreas = on;
		CenterPrefs.setEditorAreas(on);
	}

	/** The module under the pointer (not dragging), for its hover outline; {@link #NONE} when there is none. */
	int hovered() {
		return hovered;
	}

	ModuleState module(int index) {
		return session.layout().modules().get(index);
	}

	// ---------- coordinates ----------

	/** UI units (the kit's) to GUI units (the HUD's), the inverse of {@code KitScreen.toUiX}. */
	float toGuiX(float ui) {
		Window window = Minecraft.getInstance().getWindow();
		return ui * screen.uiScale() * window.getGuiScaledWidth() / Math.max(1, window.getWidth());
	}

	float toGuiY(float ui) {
		Window window = Minecraft.getInstance().getWindow();
		return ui * screen.uiScale() * window.getGuiScaledHeight() / Math.max(1, window.getHeight());
	}

	float toUiX(float gui) {
		Window window = Minecraft.getInstance().getWindow();
		return gui * window.getWidth() / Math.max(1, window.getGuiScaledWidth()) / screen.uiScale();
	}

	float toUiY(float gui) {
		Window window = Minecraft.getInstance().getWindow();
		return gui * window.getHeight() / Math.max(1, window.getGuiScaledHeight()) / screen.uiScale();
	}

	// ---------- selection ----------

	void select(int index) {
		if (index != NONE && !module(index).enabled()) {
			index = NONE;
		}
		boolean hadGroup = groupSize() > 1;
		java.util.Arrays.fill(group, false);
		if (index != NONE) {
			group[index] = true;
		}
		if (index == selected && !hadGroup) {
			return;
		}
		setPrimary(index);
	}

	/** Makes {@code index} the primary selection (the one the inspector and mini-bar follow), keeping the group. */
	private void setPrimary(int index) {
		selected = index;
		inspector.bind(index);
		miniBar.bind(index);
		syncPanels();
		stage.invalidate();
	}

	/** Whether module {@code index} is selected (the primary or another in the group). */
	boolean inGroup(int index) {
		return index >= 0 && index < group.length && group[index];
	}

	/** How many modules are selected. */
	int groupSize() {
		int n = 0;
		for (boolean in : group) {
			if (in) {
				n++;
			}
		}
		return n;
	}

	/** Shift+click: adds the module to the selection or takes it out. */
	void toggleInGroup(int index) {
		if (index < 0 || !module(index).enabled()) {
			return;
		}
		if (selected < 0) {
			select(index);
			return;
		}
		group[index] = !group[index];
		if (!group[index] && index == selected) {
			int next = NONE;
			for (int i = 0; i < group.length; i++) {
				if (group[i]) {
					next = i;
					break;
				}
			}
			setPrimary(next);
		} else if (group[index]) {
			setPrimary(index);
		}
		stage.invalidate();
	}

	/** The box being dragged out (GUI units: x0, y0, x1, y1), or false when there is none. */
	boolean selectionBox(float[] out) {
		if (!boxing) {
			return false;
		}
		out[0] = Math.min(boxX0, boxX1);
		out[1] = Math.min(boxY0, boxY1);
		out[2] = Math.max(boxX0, boxX1);
		out[3] = Math.max(boxY0, boxY1);
		return true;
	}

	/** A right press on the surface: starts a selection box. */
	boolean pressSecondary(float gx, float gy) {
		closePalette();
		cancelDrag();
		boxing = true;
		boxX0 = boxX1 = gx;
		boxY0 = boxY1 = gy;
		return true;
	}

	/** The box's end: selects every shown module it touches (the first one in order becomes the primary). */
	private void finishBox() {
		boxing = false;
		float x0 = Math.min(boxX0, boxX1);
		float y0 = Math.min(boxY0, boxY1);
		float w = Math.abs(boxX1 - boxX0);
		float h = Math.abs(boxY1 - boxY0);
		if (w < EditorMath.DRAG_START && h < EditorMath.DRAG_START) {
			select(NONE);
			return;
		}
		java.util.Arrays.fill(group, false);
		int first = NONE;
		for (int i = 0; i < scene.size(); i++) {
			if (scene.state(i) != HudScene.OFF && module(i).enabled()
				&& EditorMath.overlaps(scene.x(i), scene.y(i), scene.width(i), scene.height(i), x0, y0, w, h)) {
				group[i] = true;
				if (first == NONE) {
					first = i;
				}
			}
		}
		setPrimary(first);
		stage.invalidate();
	}

	/**
	 * Selects the next shown module in registry order ({@code direction} 1) or the previous one (-1), wrapping
	 * round, from the selection or from the start; returns whether there was one. The keyboard's way to reach
	 * every module.
	 */
	boolean selectNext(int direction) {
		int next = EditorMath.nextShown(scene, selected, direction);
		if (next < 0) {
			return false;
		}
		select(next);
		return selected == next;
	}

	private boolean inspectorLeft() {
		if (selected < 0 || scene.state(selected) == HudScene.OFF) {
			return false;
		}
		return EditorMath.inspectorOnLeft(scene.x(selected) + scene.width(selected) / 2.0F, screenW);
	}

	/** WIDGETS: opens or closes the palette, with focus in its search. */
	void togglePalette() {
		if (palette.visible()) {
			closePalette();
			return;
		}
		palette.setVisible(true);
		stage.invalidate();
		// Typing goes straight to the search (the editor's letter keys wait until it closes).
		if (screen.focusVisible()) {
			screen.focusFromKeyboard(palette.first());
		} else {
			screen.focus(palette.first());
		}
	}

	/** Closes the palette; keyboard focus inside it goes back to the toolbar's Widgets button. */
	void closePalette() {
		if (!palette.visible()) {
			return;
		}
		Node focused = screen.focused();
		boolean focusInside = focused != null && palette.contains(focused);
		boolean keyboard = screen.focusVisible();
		palette.setVisible(false);
		palette.clearSearch();
		if (focusInside) {
			if (keyboard) {
				screen.focusFromKeyboard(toolbar.widgetsButton());
			} else {
				screen.focus(null);
			}
		}
	}

	/** Puts keyboard focus on the editing surface (after choosing a widget from the palette by keyboard). */
	void focusStage() {
		screen.focusFromKeyboard(stage);
	}

	// ---------- pointer (GUI units) ----------

	/**
	 * A left press on the editing surface; returns true when it starts a drag. With Shift the module joins or leaves the
	 * selection; a press on a module already in a selection of several moves them all.
	 */
	boolean press(float gx, float gy, boolean shift) {
		closePalette();
		if (selected >= 0 && scene.state(selected) != HudScene.OFF && groupSize() <= 1 && onHandle(gx, gy)) {
			beginDrag(Drag.SCALE, gx, gy);
			startDistance = EditorMath.distance(startLeft, startTop, gx, gy);
			return true;
		}
		int hit = scene.hit(gx, gy);
		if (shift && hit != NONE) {
			toggleInGroup(hit);
			return false;
		}
		if (hit != NONE && inGroup(hit) && groupSize() > 1) {
			setPrimary(hit);
		} else {
			select(hit);
		}
		if (hit == NONE) {
			return false;
		}
		beginDrag(Drag.MOVE, gx, gy);
		for (int i = 0; i < group.length; i++) {
			if (group[i]) {
				groupStartX[i] = scene.x(i);
				groupStartY[i] = scene.y(i);
			}
		}
		return true;
	}

	private void beginDrag(Drag kind, float gx, float gy) {
		drag = kind;
		moved = false;
		pressX = gx;
		pressY = gy;
		startLeft = scene.x(selected);
		startTop = scene.y(selected);
		startW = scene.width(selected);
		startH = scene.height(selected);
		startScale = module(selected).placement().scale();
		grabX = gx - startLeft;
		grabY = gy - startTop;
	}

	private boolean onHandle(float gx, float gy) {
		return EditorMath.inHandle(gx, gy, scene.x(selected) + scene.width(selected), scene.y(selected) + scene.height(selected), HANDLE_HIT);
	}

	void dragTo(float gx, float gy) {
		if (boxing) {
			boxX1 = gx;
			boxY1 = gy;
			return;
		}
		if (drag == Drag.NONE || selected < 0) {
			return;
		}
		ModuleState state = module(selected);
		if (drag == Drag.SCALE) {
			float next = EditorMath.scaleForDrag(startScale, startDistance, EditorMath.distance(startLeft, startTop, gx, gy));
			if (next != state.placement().scale()) {
				session.begin();
				float ratio = next / startScale;
				state.setPlacement(HudPlacement.at(state.placement().anchor(), startLeft, startTop, screenW, screenH, startW * ratio,
					startH * ratio, next));
			}
			return;
		}
		if (!moved) {
			if (EditorMath.distance(pressX, pressY, gx, gy) < EditorMath.DRAG_START) {
				return;
			}
			moved = true;
			session.begin();
		}
		float w = scene.width(selected);
		float h = scene.height(selected);
		snapper.begin(screenW, screenH);
		for (int i = 0; i < scene.size(); i++) {
			if (i != selected && !group[i] && scene.state(i) != HudScene.OFF) {
				snapper.addModule(scene.x(i), scene.y(i), scene.width(i), scene.height(i));
			}
		}
		if (showAreas) {
			guides.addTo(snapper);
		}
		snapper.snap(gx - grabX, gy - grabY, w, h, altDown() || !snapping);
		float left = EditorMath.clampInto(snapper.left(), screenW - w);
		float top = EditorMath.clampInto(snapper.top(), screenH - h);
		// A guide shows only while the module really sits on it (clamping at an edge can pull it off).
		guideV = snapper.hasVerticalGuide() && left == snapper.left();
		guideH = snapper.hasHorizontalGuide() && top == snapper.top();
		guideX = snapper.verticalGuideX();
		guideVFrom = snapper.verticalGuideFrom();
		guideVTo = snapper.verticalGuideTo();
		guideY = snapper.horizontalGuideY();
		guideHFrom = snapper.horizontalGuideFrom();
		guideHTo = snapper.horizontalGuideTo();
		state.setPlacement(HudPlacement.at(state.placement().anchor(), left, top, screenW, screenH, w, h, state.placement().scale()));
		// The rest of the selection follows by the same amount, each kept on screen.
		float dx = left - groupStartX[selected];
		float dy = top - groupStartY[selected];
		for (int i = 0; i < group.length; i++) {
			if (!group[i] || i == selected || scene.state(i) == HudScene.OFF) {
				continue;
			}
			ModuleState other = module(i);
			float ow = scene.width(i);
			float oh = scene.height(i);
			float ox = EditorMath.clampInto(groupStartX[i] + dx, screenW - ow);
			float oy = EditorMath.clampInto(groupStartY[i] + dy, screenH - oh);
			other.setPlacement(HudPlacement.at(other.placement().anchor(), ox, oy, screenW, screenH, ow, oh, other.placement().scale()));
		}
	}

	void release() {
		if (boxing) {
			finishBox();
			return;
		}
		if (drag != Drag.NONE && session.editing()) {
			settleIndex = selected;
			settleGroup = groupSize() > 1;
		}
		drag = Drag.NONE;
		guideV = false;
		guideH = false;
	}

	/** Holding Alt (Option on a Mac) turns snapping off while dragging. */
	private static boolean altDown() {
		return Mc263ControlPlatform.keyDown(InputConstants.KEY_LALT) || Mc263ControlPlatform.keyDown(InputConstants.KEY_RALT);
	}

	/** The wheel over a module steps its size (the one under the pointer, selected or not); returns whether it did. */
	boolean scroll(float gx, float gy, double amount) {
		int hit = scene.hit(gx, gy);
		if (hit < 0 || amount == 0) {
			return false;
		}
		if (!inGroup(hit)) {
			select(hit);
		}
		stepScale(hit, amount > 0 ? 1 : -1);
		return true;
	}

	/**
	 * One size step up or down for the selection, keeping its top-left corner. Steps in quick succession (the
	 * wheel, {@code [} and {@code ]}) are one undo step.
	 */
	void stepScale(int direction) {
		stepScale(selected, direction);
	}

	private void stepScale(int index, int direction) {
		if (index < 0 || drag != Drag.NONE) {
			return;
		}
		ModuleState state = module(index);
		float current = state.placement().scale();
		float next = direction > 0 ? ScaleSteps.next(current) : ScaleSteps.previous(current);
		if (next == current) {
			return;
		}
		session.continueRun(System.nanoTime());
		float ratio = next / current;
		state.setPlacement(HudPlacement.at(state.placement().anchor(), scene.x(index), scene.y(index), screenW, screenH,
			scene.width(index) * ratio, scene.height(index) * ratio, next));
		settleIndex = index;
	}

	/** Space: every selected module back to its normal size (1x), keeping its top-left corner. */
	void resetSize() {
		if (selected < 0 || drag != Drag.NONE) {
			return;
		}
		session.begin();
		for (int i = 0; i < group.length; i++) {
			if (!group[i] || scene.state(i) == HudScene.OFF) {
				continue;
			}
			ModuleState state = module(i);
			float current = state.placement().scale();
			if (current == 1.0F) {
				continue;
			}
			float ratio = 1.0F / current;
			state.setPlacement(HudPlacement.at(state.placement().anchor(), scene.x(i), scene.y(i), screenW, screenH, scene.width(i) * ratio,
				scene.height(i) * ratio, 1.0F));
		}
		settleIndex = selected;
		settleGroup = groupSize() > 1;
	}

	/** Sets the selection's size step from the inspector. */
	void setScale(float step) {
		if (selected < 0) {
			return;
		}
		ModuleState state = module(selected);
		float current = state.placement().scale();
		if (step == current) {
			return;
		}
		session.begin();
		float ratio = step / current;
		state.setPlacement(HudPlacement.at(state.placement().anchor(), scene.x(selected), scene.y(selected), screenW, screenH,
			scene.width(selected) * ratio, scene.height(selected) * ratio, step));
		settleIndex = selected;
	}

	/** Moves the selection; a burst of presses (a held arrow key) is one undo step. */
	private void nudge(float dx, float dy) {
		if (selected < 0 || drag != Drag.NONE) {
			return;
		}
		session.continueRun(System.nanoTime());
		for (int i = 0; i < group.length; i++) {
			if (group[i]) {
				module(i).setPlacement(module(i).placement().moved(dx, dy));
			}
		}
		settleIndex = selected;
		settleGroup = groupSize() > 1;
	}

	/**
	 * Re-anchors the changed module by where it now sits and keeps it on screen (after the frame measured it),
	 * then closes the gesture, unless a run of key presses is still coming (it closes when they stop).
	 */
	private void settleSelection() {
		int index = settleIndex;
		settleIndex = NONE;
		boolean all = settleGroup;
		settleGroup = false;
		for (int i = 0; i < group.length; i++) {
			if ((i == index || all && group[i]) && scene.state(i) != HudScene.OFF) {
				ModuleState state = module(i);
				float w = scene.width(i);
				float h = scene.height(i);
				state.setPlacement(HudPlacement.fromAbsolute(scene.x(i), scene.y(i), w, h, screenW, screenH,
					state.placement().scale()).clampOnScreen(screenW, screenH, w, h));
			}
		}
		if (!session.inRun()) {
			session.end();
		}
		stage.invalidate();
	}

	// ---------- actions ----------

	void undo() {
		cancelDrag();
		if (session.undo()) {
			afterHistory();
		}
	}

	void redo() {
		cancelDrag();
		if (session.redo()) {
			afterHistory();
		}
	}

	private void afterHistory() {
		settleIndex = NONE;
		if (selected >= 0 && !module(selected).enabled()) {
			select(NONE);
		}
		inspector.bind(selected);
		stage.invalidate();
	}

	/**
	 * Stops any drag and closes the gesture under way, if any, as one undo step. A pending settle stays: the
	 * module is still re-anchored and kept on screen once the next frame has measured it.
	 */
	private void cancelDrag() {
		drag = Drag.NONE;
		boxing = false;
		guideV = false;
		guideH = false;
		if (session.editing()) {
			session.end();
		}
	}

	void askReset() {
		screen.showModal(new Dialog("Reset all positions to the layout's defaults?",
			"Every module goes back to its default place and size. Undo brings them back.", "Reset positions", Button.Variant.DANGER,
			() -> {
				cancelDrag();
				session.resetPositions();
				inspector.bind(selected);
			}));
	}

	/** Turns a module on or off (the palette); turning one on selects it. */
	void setEnabled(int index, boolean on) {
		cancelDrag();
		session.edit(() -> module(index).setEnabled(on));
		if (on) {
			select(index);
		} else if (index == selected) {
			select(NONE);
		}
	}

	void hideSelected() {
		if (selected >= 0) {
			setEnabled(selected, false);
		}
	}

	/** Saves the editor's changes, then opens the module menu on the selected module's settings; Escape there comes back. */
	void openFullSettings() {
		if (selected < 0) {
			return;
		}
		openSettings(selected);
	}

	/** Saves the editor's changes, then opens the module menu on {@code index}'s settings. */
	void openSettings(int index) {
		String id = module(index).id();
		toMenu();
		ControlCenter.openModuleSettings(screen, id);
	}

	/** EDIT FLINT MODS and the hub's shortcuts: saves, then opens the module menu on {@code start}; Escape there comes back. */
	void openMenu(MenuScreen.Start start) {
		toMenu();
		MenuScreen.open(screen, start);
	}

	private void toMenu() {
		closePalette();
		cancelDrag();
		session.end();
		ControlState.saveAll();
		leaving = true;
	}

	/** Right Shift: saves and returns to the game. */
	public void saveAndClose() {
		if (screen.hasModal()) {
			return;
		}
		save();
	}

	/** Writes the layout and returns to the game; when it can't be written, says why first. */
	void save() {
		cancelDrag();
		session.end();
		ControlState.saveAll();
		LayoutStore layouts = ControlState.layouts();
		if (layouts.dirty(layouts.activeId())) {
			showSaveProblem(layouts);
			return;
		}
		leave();
	}

	/** The layout couldn't be written (saved by a newer Flint, or the file couldn't be replaced). */
	private void showSaveProblem(LayoutStore layouts) {
		String notice = null;
		for (LayoutInfo info : layouts.list()) {
			if (info.active()) {
				notice = info.notice();
			}
		}
		String why = notice != null ? notice : "Flint couldn't write the layout file.";
		screen.showModal(new Dialog("Couldn't save this layout", why + " Your changes still apply until you quit Minecraft.", "Close editor",
			Button.Variant.PRIMARY, this::leave).cancelLabel("Keep editing"));
	}

	void cancel() {
		cancelDrag();
		session.discard();
		leave();
	}

	/**
	 * Escape: the palette closes first, then the selection is let go, then the editor saves and returns to the game
	 * (changes are kept, as with Right Shift; Cancel on the toolbar is the way to throw them away).
	 */
	public void requestLeave() {
		if (palette.visible()) {
			closePalette();
			return;
		}
		if (legend.open()) {
			legend.close();
			return;
		}
		if (drag != Drag.NONE || boxing) {
			cancelDrag();
			return;
		}
		if (selected != NONE) {
			select(NONE);
			return;
		}
		save();
	}

	private void leave() {
		leaving = true;
		Minecraft.getInstance().gui.setScreen(parent);
	}

	/**
	 * The screen was replaced. Without Save or Cancel (the world was left, the player died) nothing was
	 * confirmed, so the layout goes back to how it was. The editor's textures are freed either way.
	 */
	public void removed() {
		if (!leaving) {
			cancelDrag();
			session.discard();
		}
		patterns.release();
	}

	/** Each client tick: an editor whose drawing failed closes, without its unconfirmed changes. */
	public void tick() {
		if (failed && !leaving) {
			cancel();
		}
	}

	private static void turnedOff(String path, RuntimeException error) {
		FlintClient.LOGGER.warn("Flint Control Center: the {} failed while drawing; it's off for the rest of this session", path, error);
	}

	// ---------- keys ----------

	/** Editor keys, before the kit's own handling; returns true when used. */
	public boolean keyPressed(KeyEvent event) {
		if (screen.hasModal() || screen.typing() || screen.capturingKeys()) {
			return false;
		}
		if (event.hasControlDownWithQuirk() && (event.shortcutKey() == 'z' || event.shortcutKey() == 'Z')) {
			if (event.hasShiftDown()) {
				redo();
			} else {
				undo();
			}
			return true;
		}
		Node focused = screen.focused();
		boolean free = focused == null || focused == stage;
		if (free && !event.hasControlDownWithQuirk() && !event.hasAltDown()) {
			if (event.key() == InputConstants.KEY_H) {
				toggleChrome();
				return true;
			}
			if (event.key() == InputConstants.KEY_I && selected >= 0) {
				toggleInspector();
				return true;
			}
		}
		if (!free) {
			return false;
		}
		// Tab (Shift+Tab): the next (previous) module, once one is selected; else Tab moves focus as usual.
		if (event.key() == InputConstants.KEY_TAB && selected >= 0) {
			selectNext(event.hasShiftDown() ? -1 : 1);
			return true;
		}
		if (selected < 0) {
			return false;
		}
		if (event.key() == InputConstants.KEY_SPACE) {
			resetSize();
			return true;
		}
		float step = EditorMath.nudge(event.hasControlDownWithQuirk(), event.hasShiftDown());
		if (event.isLeft()) {
			nudge(-step, 0);
		} else if (event.isRight()) {
			nudge(step, 0);
		} else if (event.isUp()) {
			nudge(0, -step);
		} else if (event.isDown()) {
			nudge(0, step);
		} else if (event.shortcutKey() == '[' || event.key() == InputConstants.KEY_LBRACKET) {
			stepScale(-1);
		} else if (event.shortcutKey() == ']' || event.key() == InputConstants.KEY_RBRACKET) {
			stepScale(1);
		} else {
			return false;
		}
		return true;
	}

	// ---------- toolbar ----------

	/** Whether a shown module overlaps the toolbar's place at the top or at the bottom of the screen. */
	private boolean toolbarCovers(boolean bottom) {
		float uiTop = EditorToolbar.top(screen.uiHeight(), bottom);
		for (int i = 0; i < scene.size(); i++) {
			if (scene.state(i) != HudScene.OFF && underToolbar(i, uiTop)) {
				return true;
			}
		}
		return false;
	}

	private boolean underToolbar(int index, float uiTop) {
		return EditorMath.overlaps(scene.x(index), scene.y(index), scene.width(index), scene.height(index), toGuiX(toolbar.x()),
			toGuiY(uiTop), toGuiX(toolbar.width()), toGuiY(EditorToolbar.HEIGHT));
	}

	/**
	 * Whether the toolbar steps back (fades): while a module is dragged, and while the selected module is under
	 * it and the pointer isn't on it (both of the toolbar's places are taken).
	 */
	boolean toolbarFaded() {
		if (dragging()) {
			return true;
		}
		if (selected < 0 || scene.state(selected) == HudScene.OFF) {
			return false;
		}
		return !toolbar.containsPoint(screen.mouseX(), screen.mouseY()) && underToolbar(selected, toolbar.y());
	}

	/** Whether the palette of widgets is open. */
	boolean paletteOpen() {
		return palette.visible();
	}

	/**
	 * Whether the hub steps back (fades): while a module is dragged or a box drawn, and while a selected module is under
	 * it and the pointer isn't on it.
	 */
	boolean hubFaded() {
		if (dragging() || boxing) {
			return true;
		}
		if (hub.containsPoint(screen.mouseX(), screen.mouseY())) {
			return false;
		}
		float hx = toGuiX(hub.x());
		float hy = toGuiY(hub.y());
		float hw = toGuiX(hub.width());
		float hh = toGuiY(hub.height());
		for (int i = 0; i < group.length; i++) {
			if (group[i] && scene.state(i) != HudScene.OFF && EditorMath.overlaps(scene.x(i), scene.y(i), scene.width(i), scene.height(i), hx, hy, hw, hh)) {
				return true;
			}
		}
		return false;
	}

	/** The wheel over the toolbar's own background: as over the module under it. */
	boolean scrollAt(float uiX, float uiY, double amount) {
		return scroll(toGuiX(uiX), toGuiY(uiY), amount);
	}

	// ---------- drawing (GUI units) ----------

	/** Draws the editing surface on a canvas begun in GUI units, under the screen's controls. */
	public void paint(Canvas c, int guiWidth, int guiHeight) {
		if (GUARD.on()) {
			try {
				paintSurface(c, guiWidth, guiHeight);
				return;
			} catch (RuntimeException e) {
				GUARD.fail(e);
			}
		}
		// Off (now or earlier this session): only the dim, and the editor closes at the next tick.
		failed = true;
		int g = c.guiScale();
		c.fill(0, 0, c.px(guiWidth) + g, c.px(guiHeight) + g, DIM);
	}

	private void paintSurface(Canvas c, int guiWidth, int guiHeight) {
		long now = System.nanoTime();
		screenW = guiWidth;
		screenH = guiHeight;
		patterns.beginFrame();
		session.endIdleRun(now);
		int g = c.guiScale();
		int wPx = c.px(guiWidth) + g;
		int hPx = c.px(guiHeight) + g;
		c.fill(0, 0, wPx, hPx, DIM);
		int spacing = EditorMath.gridSpacingPx(g);
		patterns.dots(c, spacing, spacing, wPx, hPx, spacing, Math.max(1, g / 2), DOT);
		guides.update(screenW, screenH, now);
		if (showAreas) {
			guides.paint(c, patterns);
		}
		Layout layout = session.layout();
		HudRenderer.drawScene(c, layout, screenW, screenH, scene);
		if (settleIndex != NONE && drag == Drag.NONE) {
			settleSelection();
		}
		if (selected >= 0 && scene.state(selected) == HudScene.OFF) {
			select(NONE);
		}
		for (int i = 0; i < group.length; i++) {
			if (group[i] && scene.state(i) == HudScene.OFF) {
				group[i] = false;
			}
		}
		if (pendingSelect != NONE && scene.state(pendingSelect) != HudScene.OFF) {
			select(pendingSelect);
			pendingSelect = NONE;
		}
		if (selected >= 0 && miniBar.visible()) {
			miniBar.placeFor(toUiX(scene.x(selected)), toUiY(scene.y(selected)), toUiX(scene.x(selected) + scene.width(selected)),
				toUiY(scene.y(selected) + scene.height(selected)), screen.uiWidth(), screen.uiHeight());
		}
		long revision = layout.revision();
		// Not while a pointer gesture (a slider, a drag) is under way: the control being moved is the truth then.
		if (revision != seenRevision && (!session.editing() || session.inRun())) {
			seenRevision = revision;
			inspector.bind(selected);
		}
		if (drag == Drag.NONE) {
			boolean bottom = toolbarCovers(false) && !toolbarCovers(true);
			if (bottom != toolbarBottom) {
				toolbarBottom = bottom;
				stage.invalidate();
			}
		}
		hovered = drag == Drag.NONE && screen.hovered() == stage ? scene.hit(toGuiX(screen.mouseX()), toGuiY(screen.mouseY())) : NONE;
		int line = VanillaGuides.lineFor(g);
		for (int i = 0; i < scene.size(); i++) {
			int state = scene.state(i);
			if (state == HudScene.OFF) {
				continue;
			}
			int x0 = Math.round(scene.x(i) * g);
			int y0 = Math.round(scene.y(i) * g);
			int x1 = Math.round((scene.x(i) + scene.width(i)) * g);
			int y1 = Math.round((scene.y(i) + scene.height(i)) * g);
			if (state == HudScene.OUTLINE) {
				patterns.dashed(c, x0, y0, x1, y1, line, VanillaGuides.dashFor(g), VanillaGuides.gapFor(g), OUTLINE);
				paintReason(c, i, x0, y0, x1, y1, g);
			}
			boolean warn = guides.overlap(scene.x(i), scene.y(i), scene.width(i), scene.height(i)) >= 0;
			if (warn) {
				outline(c, x0 - line, y0 - line, x1 + line, y1 + line, line, Tokens2.WARN);
				int icon = Math.max(6, Math.round(6.0F * g));
				c.icon(Icon.WARNING, x1 - icon / 2, y0 - icon / 2, icon, Tokens2.WARN);
			}
		}
		// The hover outline and the selection's marks are drawn by the stage, in the kit's units, over this.
		paintGuides(c, g);
	}

	/** A hidden module's reason, just below its outline (above it near the bottom of the screen). */
	private void paintReason(Canvas c, int i, int x0, int y0, int x1, int y1, int g) {
		String reason = scene.reason(i);
		if (reason == null) {
			return;
		}
		TextLine line = reasons[i];
		line.set(reason);
		int cap = Math.round(line.capHeightPx(g));
		int gap = 2 * g;
		int baseline = y1 + gap + cap;
		if (baseline > c.px(screenH) - g) {
			baseline = y0 - gap;
		}
		// Kept on screen: a module at the right edge has its reason end where it ends instead of running off.
		int width = c.width(line);
		int left = x0;
		int screenRight = c.px(screenW) - g;
		if (left + width > screenRight) {
			left = Math.max(g, Math.min(x1, screenRight) - width);
		}
		c.text(line, left, baseline, REASON);
	}

	/**
	 * A square outline {@code line} px wide inside {@code (x0, y0)..(x1, y1)}, as four fills: rings from the mask
	 * atlas would add a mask for every module size the editor outlines.
	 */
	private static void outline(Canvas c, int x0, int y0, int x1, int y1, int line, int argb) {
		c.fill(x0, y0, x1, y0 + line, argb);
		c.fill(x0, y1 - line, x1, y1, argb);
		c.fill(x0, y0 + line, x0 + line, y1 - line, argb);
		c.fill(x1 - line, y0 + line, x1, y1 - line, argb);
	}

	/** The snapping guides: one-pixel ember lines, only while a drag sits on them. */
	private void paintGuides(Canvas c, int g) {
		if (!dragging()) {
			return;
		}
		if (guideV) {
			int x = Math.round(guideX * g);
			c.fill(x, Math.round(guideVFrom * g), x + 1, Math.round(guideVTo * g), GUIDE);
		}
		if (guideH) {
			int y = Math.round(guideY * g);
			c.fill(Math.round(guideHFrom * g), y, Math.round(guideHTo * g), y + 1, GUIDE);
		}
	}

	/** Which of Minecraft's bars the module at {@code index} overlaps (its tooltip), or -1. */
	int overlap(int index) {
		return guides.overlap(scene.x(index), scene.y(index), scene.width(index), scene.height(index));
	}
}
