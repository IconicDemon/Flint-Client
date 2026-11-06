package com.flintclient.client.control.editor;

import com.flintclient.client.FlintLog;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.hud.Snapper;
import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.client.control.hud.render.HudScene;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.platform.HookGuard;
import com.flintclient.client.control.store.LayoutInfo;
import com.flintclient.client.platform.HudData;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.platform.Keys;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.platform.Services;
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
import org.jspecify.annotations.Nullable;

/**
 * The HUD editor's behaviour, for the Right Shift HUD page ({@code rshift.HudPageScreen}): the layout being edited (in place,
 * with undo and a copy to cancel back to), the selection, dragging with snapping, scaling, nudging, and drawing the editing
 * surface under the page's own controls.
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
 * <p>Right Shift and Escape (once nothing is selected) write the stores once and return to the game (or say why they
 * couldn't); leaving for the window or another screen saves first. If drawing the editor ever throws, the error is logged once, the
 * editor closes at the next tick without its unconfirmed changes, and stays off for the session (as every
 * Control Center path does), so Minecraft keeps running. Render thread.
 */
public final class HudEditor {
	static final int NONE = -1;
	private static final HookGuard GUARD = new HookGuard("hud editor", HudEditor::turnedOff);
	/** A light dim over the game (14 % black): the world stays the stage. */
	private static final int DIM = Palette.withAlpha(0xFF000000, 0.30F);
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
	private final @Nullable Object parent;
	/** Remade when the profile in use changed while the module menu was open (it then edits that profile). */
	private EditorSession session;
	private final HudScene scene = new HudScene();
	private final Snapper snapper = new Snapper();
	private final VanillaGuides guides = new VanillaGuides();
	private final EditorPatterns patterns = new EditorPatterns();
	private final TextLine[] reasons = new TextLine[ModuleRegistry.size()];
	private EditorStage stage;

	private int selected = NONE;
	/** A module to select once the first frame has placed it ("Edit position" in the Control Center). */
	private int pendingSelect = NONE;
	/** Snapping to edges, centres, other modules and Minecraft's HUD (Alt turns it off for one drag). */
	private boolean snapping = true;
	private int hovered = NONE;
	private boolean leaving;
	/** Drawing failed: leave at the next tick. */
	private boolean failed;
	/** A module that changed: re-anchor and clamp it once the next frame has measured it (NONE: nothing to do). */
	private int settleIndex = NONE;
	private float screenW = 1;
	private float screenH = 1;

	private Drag drag = Drag.NONE;
	/** How visible the snapping guides are, 0..1 (eased). */
	private float guidesShown;
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

	public HudEditor(KitScreen screen, @Nullable Object parent, Layout layout) {
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

	boolean snapping() {
		return snapping;
	}

	void setSnapping(boolean on) {
		snapping = on;
	}

	/** Places the editing surface over a screen of {@code width × height} UI units. */
	public void layout(float width, float height) {
		stage.setBounds(0, 0, width, height);
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
		return ui * screen.uiScale() * HudData.guiWidth() / Math.max(1, HudData.windowWidth());
	}

	float toGuiY(float ui) {
		return ui * screen.uiScale() * HudData.guiHeight() / Math.max(1, HudData.windowHeight());
	}

	float toUiX(float gui) {
		return gui * HudData.windowWidth() / Math.max(1, HudData.guiWidth()) / screen.uiScale();
	}

	float toUiY(float gui) {
		return gui * HudData.windowHeight() / Math.max(1, HudData.guiHeight()) / screen.uiScale();
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

	/** Makes {@code index} the primary selection (the one with the corner handle), keeping the group. */
	private void setPrimary(int index) {
		selected = index;
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

	/** Puts keyboard focus on the editing surface (after choosing a widget from the HUDs palette by keyboard). */
	public void focusStage() {
		screen.focusFromKeyboard(stage);
	}

	// ---------- pointer (GUI units) ----------

	/**
	 * A left press on the editing surface; returns true when it starts a drag. With Shift the module joins or leaves the
	 * selection; a press on a module already in a selection of several moves them all.
	 */
	boolean press(float gx, float gy, boolean shift) {
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
		startScale = drawnScale(selected);
		grabX = gx - startLeft;
		grabY = gy - startTop;
	}

	/** The size step a module is drawn at: its placement's, or 1 while its own settings place it (Armor against the hotbar). */
	private float drawnScale(int index) {
		return scene.pinned(index) ? 1.0F : module(index).placement().scale();
	}

	/**
	 * Before the editor moves or sizes a module its own settings place (Armor against the hotbar): it becomes free, its HUD
	 * placement put exactly where it is drawn at size 1, so the change starts from there and the HUD keeps it where it is put.
	 * Inside the gesture's undo step.
	 */
	private void free(int index) {
		if (index < 0 || !scene.pinned(index) || scene.state(index) == HudScene.OFF) {
			return;
		}
		ModuleState state = module(index);
		if (com.flintclient.client.control.model.ModuleSettings.free(state)) {
			state.setPlacement(HudPlacement.at(state.placement().anchor(), scene.x(index), scene.y(index), screenW, screenH, scene.width(index),
				scene.height(index), 1.0F));
		}
		scene.unpin(index);
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
			if (next != drawnScale(selected)) {
				session.begin();
				free(selected);
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
			for (int i = 0; i < group.length; i++) {
				if (group[i] || i == selected) {
					free(i);
				}
			}
		}
		float w = scene.width(selected);
		float h = scene.height(selected);
		snapper.begin(screenW, screenH);
		for (int i = 0; i < scene.size(); i++) {
			if (i != selected && !group[i] && scene.state(i) != HudScene.OFF) {
				snapper.addModule(scene.x(i), scene.y(i), scene.width(i), scene.height(i));
			}
		}
		guides.addTo(snapper);
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
		return Services.keyDown(Keys.LEFT_ALT) || Services.keyDown(Keys.RIGHT_ALT);
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
		float current = drawnScale(index);
		float by = com.flintclient.client.control.ControlState.settings().game(com.flintclient.client.control.model.GameOptions.HUD_SCALE_STEP);
		float next = direction > 0 ? ScaleSteps.next(current, by) : ScaleSteps.previous(current, by);
		if (next == current) {
			return;
		}
		session.continueRun(System.nanoTime());
		free(index);
		float ratio = next / current;
		state.setPlacement(HudPlacement.at(state.placement().anchor(), scene.x(index), scene.y(index), screenW, screenH,
			scene.width(index) * ratio, scene.height(index) * ratio, next));
		settleIndex = index;
	}

	// ---------- typed values (the HUD page's inspector) ----------

	/** A size as a box shows it: "1", "1.25". */
	public static String scaleText(float scale) {
		String n = String.format(java.util.Locale.ROOT, "%.2f", ScaleSteps.snap(scale));
		return n.replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	/** The one module selected, for typed values, or -1 when none (or several) are. */
	public int single() {
		return selected >= 0 && groupSize() <= 1 && drag == Drag.NONE && scene.state(selected) != HudScene.OFF ? selected : NONE;
	}

	public String moduleName(int index) {
		return module(index).descriptor().name();
	}

	/** Its left and top edges on screen, in GUI units, as it is drawn. */
	public float left(int index) {
		return scene.x(index);
	}

	public float top(int index) {
		return scene.y(index);
	}

	public float scale(int index) {
		return drawnScale(index);
	}

	/** Moves it so its top-left corner is at ({@code left}, {@code top}) in GUI units (kept on screen); one undo step. */
	public void placeAt(int index, float left, float top) {
		if (index < 0 || drag != Drag.NONE) {
			return;
		}
		if (scene.pinned(index) && Math.abs(left - scene.x(index)) < 0.5F && Math.abs(top - scene.y(index)) < 0.5F) {
			// Where its own anchor already has it: it stays theirs.
			return;
		}
		session.begin();
		free(index);
		ModuleState state = module(index);
		state.setPlacement(HudPlacement.at(state.placement().anchor(), left, top, screenW, screenH, scene.width(index), scene.height(index),
			state.placement().scale()));
		settleIndex = index;
		settleGroup = false;
	}

	/** Resizes it to {@code scale} (the nearest size step), keeping its top-left corner; one undo step. */
	public void sizeTo(int index, float scale) {
		if (index < 0 || drag != Drag.NONE) {
			return;
		}
		ModuleState state = module(index);
		float current = drawnScale(index);
		float next = ScaleSteps.snap(scale);
		if (next == current) {
			return;
		}
		session.begin();
		free(index);
		float ratio = next / current;
		state.setPlacement(HudPlacement.at(state.placement().anchor(), scene.x(index), scene.y(index), screenW, screenH,
			scene.width(index) * ratio, scene.height(index) * ratio, next));
		settleIndex = index;
		settleGroup = false;
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
			float current = drawnScale(i);
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

	/** Moves the selection; a burst of presses (a held arrow key) is one undo step. */
	private void nudge(float dx, float dy) {
		if (selected < 0 || drag != Drag.NONE) {
			return;
		}
		session.continueRun(System.nanoTime());
		for (int i = 0; i < group.length; i++) {
			if (group[i]) {
				free(i);
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

	/** Turns a module on or off (the HUDs palette); turning one on selects it. */
	public void setEnabled(String moduleId, boolean on) {
		setEnabled(ModuleRegistry.indexOf(moduleId), on);
	}

	void setEnabled(int index, boolean on) {
		cancelDrag();
		session.edit(() -> module(index).setEnabled(on));
		if (on) {
			select(index);
		} else if (index == selected) {
			select(NONE);
		}
	}

	/**
	 * Before the page leaves for another screen (the window, Minecraft's settings): ends the gesture and saves; coming
	 * back, the editor carries on from what was saved ({@link #shown}).
	 */
	public void prepareToLeave() {
		cancelDrag();
		session.end();
		ControlState.saveAll();
		leaving = true;
	}

	/** Whether a drag or a selection box is under way (the page's controls step back meanwhile). */
	public boolean busy() {
		return dragging() || boxing;
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
	 * Escape (after the page's own layers): a drag is cancelled, then the selection is let go, then the editor saves and
	 * returns to the game (changes are kept, as with Right Shift; Ctrl+Z undoes them).
	 */
	public void requestLeave() {
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
		exit.accept(() -> MenuHost.show(parent));
	}

	/** How leaving happens: at once, or (the HUD page) after its closing fade; it is handed the step that leaves. */
	private java.util.function.Consumer<Runnable> exit = Runnable::run;

	public void exitWith(java.util.function.Consumer<Runnable> how) {
		exit = how;
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

	private static void turnedOff(String path, Throwable error) {
		FlintLog.LOGGER.warn("Flint Control Center: the {} failed while drawing; it's off for the rest of this session", path, error);
	}

	// ---------- keys ----------

	/** Editor keys, before the kit's own handling; returns true when used. */
	public boolean keyPressed(KeyInput event) {
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
		if (!free) {
			return false;
		}
		// Tab (Shift+Tab): the next (previous) module, once one is selected; else Tab moves focus as usual.
		if (event.key() == Keys.TAB && selected >= 0) {
			selectNext(event.hasShiftDown() ? -1 : 1);
			return true;
		}
		if (selected < 0) {
			return false;
		}
		if (event.key() == Keys.SPACE) {
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
		} else if (event.shortcutKey() == '[' || event.key() == Keys.LEFT_BRACKET) {
			stepScale(-1);
		} else if (event.shortcutKey() == ']' || event.key() == Keys.RIGHT_BRACKET) {
			stepScale(1);
		} else {
			return false;
		}
		return true;
	}

	// ---------- drawing (GUI units) ----------

	/** Draws the editing surface on a canvas begun in GUI units, under the screen's controls. */
	public void paint(Canvas c, int guiWidth, int guiHeight) {
		if (GUARD.on()) {
			try {
				paintSurface(c, guiWidth, guiHeight);
				return;
			} catch (RuntimeException | LinkageError e) {
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
		// The dot grid and Minecraft's HUD areas are snapping guides: they fade in only while something is dragged.
		guidesShown = com.flintclient.client.ui.kit.Kit.approach(guidesShown, drag != Drag.NONE ? 1 : 0, 0.05F);
		guides.update(screenW, screenH, now);
		if (guidesShown > 0.01F) {
			int spacing = EditorMath.gridSpacingPx(g);
			patterns.dots(c, spacing, spacing, wPx, hPx, spacing, Math.max(1, g / 2), Palette.withAlpha(DOT, 8 / 255F * guidesShown));
			guides.paint(c, patterns, guidesShown);
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
			// A module with nothing to show stays out of sight (the page stays clean) until it is pointed at or chosen: then
			// its outline and why it is empty.
			if (state == HudScene.OUTLINE && (i == hovered || i == selected || group[i])) {
				patterns.dashed(c, x0, y0, x1, y1, line, VanillaGuides.dashFor(g), VanillaGuides.gapFor(g), OUTLINE);
				int font = com.flintclient.client.render.PixelFace.begin(com.flintclient.client.render.PixelFace.BY_SIZE);
				try {
					paintReason(c, i, x0, y0, x1, y1, g);
				} finally {
					com.flintclient.client.render.PixelFace.end(font);
				}
			}
			boolean warn = guides.overlap(scene.x(i), scene.y(i), scene.width(i), scene.height(i)) >= 0;
			if (warn) {
				outline(c, x0 - line, y0 - line, x1 + line, y1 + line, line, Tokens2.WARN);
				int icon = Math.max(6, Math.round(6.0F * g));
				c.icon(Icon.WARNING, x1 - icon / 2, y0 - icon / 2, icon, Tokens2.WARN);
			}
		}
		// The hover outline and the selection's marks are drawn by the stage, in the kit's units, over this; the editor's
		// words are in Minecraft's font, as the rest of the Right Shift menu.
		int font = com.flintclient.client.render.PixelFace.begin(com.flintclient.client.render.PixelFace.BY_SIZE);
		try {
			paintGuides(c, g);
		} finally {
			com.flintclient.client.render.PixelFace.end(font);
		}
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
