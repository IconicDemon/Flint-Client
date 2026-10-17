package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.HudEditorScreen;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.hud.VanillaAreas;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.platform.Mc263ControlPlatform;
import com.flintclient.client.control.platform.Mc263HudData;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.FlintFrame;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Tokens;
import java.lang.ref.WeakReference;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/**
 * Draws the active layout's HUD modules over the game, and the module previews the Control Center and first-time
 * setup show.
 *
 * <p><b>The HUD.</b> {@link #render} runs every frame the HUD is drawn (just before chat; not with F1 or under
 * the loading screen). It skips itself while the F3 overlay is open (unless "Show the HUD with the debug screen"
 * is on) and while the HUD editor is open (the editor draws the modules itself). The enabled modules are kept in
 * an index array rebuilt only when the layout's revision or the active layout changes; their styles are resolved
 * again then and when the GUI scale changes. Each frame each module reads its data, measures itself, is placed by
 * its {@link HudPlacement} (clamped on screen, plain arithmetic) and drawn in whole pixels, between
 * {@code FlintFrame.begin} and {@code FlintFrame.end} so the HUD and a Flint screen in the same frame share the
 * atlas frame. Nothing of Flint's own is allocated per frame in steady state; Minecraft's own render state
 * objects (one per fill, text run, item and sprite, as for its own HUD) are the only allocations.
 *
 * <p><b>Previews</b> use their own set of module views (their eased states and cached text are per surface)
 * and draw at the canvas's unit scale: live data where there is some, em dashes where there isn't.
 *
 * <p><b>For the Control Center</b>: {@link #summary} gives each module's one-line live summary for the module list
 * (refreshed at most four times a second, a new String only when the text changed), {@link #lastRect} where each
 * module was drawn in the last HUD frame, and {@link #setPreviewLayout} lets first-time setup show a layout on the
 * live HUD before it is saved.
 *
 * <p>If drawing the HUD ever throws, the facade turns the HUD path off for the session; this class notes it
 * too, so {@link #hideVanillaEffects} stops hiding Minecraft's effect icons (else effects would vanish from
 * both HUDs). Render thread only.
 */
public final class HudRenderer {
	private static final int MODULES = ModuleRegistry.size();
	private static final int EFFECTS = ModuleRegistry.indexOf(ModuleId.EFFECTS);
	private static final @Nullable ModuleView[] HUD_VIEWS = views(HudText.HUD);
	private static final @Nullable ModuleView[] PREVIEW_VIEWS = views(HudText.PREVIEW);
	private static final HudPen HUD_PEN = new HudPen(HudText.HUD);
	private static final HudPen PREVIEW_PEN = new HudPen(HudText.PREVIEW);
	private static final HudStyle[] HUD_STYLES = styles();
	private static final HudStyle PREVIEW_STYLE = new HudStyle();
	/** Registry indices of the enabled modules, in registry order (the order they are drawn in). */
	private static final int[] ENABLED = new int[MODULES];

	private static @Nullable Layout bound;
	private static long boundRevision = -1;
	private static int boundSwitches = -1;
	private static int boundScale = -1;
	private static int enabledCount;
	private static boolean effectsHideVanilla;
	/** The HUD editor resolved the styles for the layout it edits: the HUD resolves them again before drawing. */
	private static boolean stylesStale;
	/** Drawing the HUD threw: the facade has turned it off, so Minecraft's effect icons must stay. */
	private static boolean failed;
	/** A module view has read from a world since the last {@link #forgetWorld}. */
	private static boolean holdsWorld;

	/** Summaries refresh at most this often. */
	private static final long SUMMARY_NANOS = 250_000_000L;
	private static final char[] SUMMARY_CHARS = new char[64];
	private static final @Nullable String[] SUMMARIES = new String[MODULES];
	private static final long[] SUMMARY_AT = new long[MODULES];
	private static final boolean[] SUMMARY_MADE = new boolean[MODULES];

	/** Where each module was drawn (GUI units: x, y, w, h) and the HUD frame it was drawn in. */
	private static final float[] RECTS = new float[4 * MODULES];
	private static final long[] RECT_FRAME = new long[MODULES];
	/** HUD frames so far (every {@link #render} call, drawn or skipped), and when the last one was. */
	private static long hudFrame;
	private static long hudFrameAt;
	/** A HUD frame older than this is not "the last frame" any more (the HUD stopped: title screen, F1). */
	private static final long RECT_STALE_NANOS = 500_000_000L;

	/** Shown instead of the active layout while set (first-time setup's live preview). */
	private static @Nullable Layout previewLayout;
	/** Client ticks in a row without a Flint screen open while a preview is set, and when one was last seen. */
	private static int previewTicksAlone;
	private static long previewScreenSeenAt;
	/** Two ticks: "no Flint screen for more than one tick". */
	private static final long PREVIEW_ALONE_NANOS = 100_000_000L;

	/** The world and pause state last seen: joining a world or resuming restarts the 1% low's window. */
	private static @Nullable WeakReference<ClientLevel> lastLevel;
	private static boolean wasPausing;

	private HudRenderer() {
	}

	/** One view per HUD module; null for modules that aren't on the HUD (Zoom, Fog...). */
	private static @Nullable ModuleView[] views(int surface) {
		@Nullable ModuleView[] views = new ModuleView[MODULES];
		for (int i = 0; i < MODULES; i++) {
			ModuleDescriptor descriptor = ModuleRegistry.all().get(i);
			String id = descriptor.id();
			if (!descriptor.hud()) {
				continue;
			}
			views[i] = switch (id) {
				case ModuleId.FPS -> new FpsView(surface);
				case ModuleId.PING -> new PingView(surface);
				case ModuleId.CPS -> new CpsView(surface);
				case ModuleId.KEYSTROKES -> new KeystrokesView(surface);
				case ModuleId.COORDINATES -> new CoordinatesView(surface);
				case ModuleId.DIRECTION -> new DirectionView(surface);
				case ModuleId.ARMOR -> new ArmorView(surface);
				case ModuleId.EFFECTS -> new EffectsView(surface);
				case HudMods.MemoryUsage.ID -> new InfoViews.MemoryView(surface);
				case HudMods.Playtime.ID -> new InfoViews.PlaytimeView(surface);
				case HudMods.DayCounter.ID -> new InfoViews.DayView(surface);
				case HudMods.ViewAngles.ID -> new InfoViews.AnglesView(surface);
				case HudMods.HeightLimit.ID -> new InfoViews.HeightView(surface);
				case HudMods.Saturation.ID -> new InfoViews.SaturationView(surface);
				case HudMods.PotionCounter.ID -> new InfoViews.PotionCountView(surface);
				case HudMods.ReachDisplay.ID -> new InfoViews.ReachView(surface);
				case HudMods.ComboCounter.ID -> new InfoViews.ComboView(surface);
				case HudMods.HorseStats.ID -> new InfoViews.HorseView(surface);
				case HudMods.Cooldowns.ID -> new ItemViews.CooldownView(surface);
				case HudMods.ItemCounter.ID -> new ItemViews.CounterView(surface);
				case PlayerMods.ToggleSprint.ID -> new InfoViews.SprintView(surface);
				case com.flintclient.client.control.model.mods.VisualMods.Momentum.ID -> new InfoViews.MomentumView(surface);
				case com.flintclient.client.control.model.mods.UtilityMods.Inventory.ID -> new InventoryView(surface);
				default -> throw new IllegalStateException("No renderer for HUD module " + id);
			};
		}
		return views;
	}

	private static HudStyle[] styles() {
		HudStyle[] styles = new HudStyle[MODULES];
		for (int i = 0; i < MODULES; i++) {
			styles[i] = new HudStyle();
		}
		return styles;
	}

	// ---------- the HUD ----------

	/** Every frame the HUD is drawn (not with F1 or under the loading screen); allocates nothing of Flint's own. */
	public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		try {
			draw(graphics);
		} catch (RuntimeException e) {
			// The facade logs it and turns the HUD off; from now on Minecraft's effect icons are left alone.
			failed = true;
			throw e;
		}
	}

	private static void draw(GuiGraphicsExtractor graphics) {
		long now = System.nanoTime();
		hudFrame++;
		hudFrameAt = now;
		FpsMeter.maintain(now);
		observeSession(now);
		guardPreview(now);
		if (Mc263ControlPlatform.currentScreen() instanceof HudEditorScreen) {
			return;
		}
		if (Mc263HudData.debugOverlayOpen() && !ControlState.settings().showHudWithDebugScreen()) {
			return;
		}
		Layout layout = shownLayout();
		int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
		bind(layout);
		if (boundScale != guiScale || stylesStale) {
			boundScale = guiScale;
			resolveStyles(layout, guiScale);
		}
		if (enabledCount == 0) {
			return;
		}
		holdsWorld = true;
		FlintFrame.begin(graphics);
		Canvas c = Canvas.begin(graphics);
		try {
			float screenW = graphics.guiWidth();
			float screenH = graphics.guiHeight();
			for (int k = 0; k < enabledCount; k++) {
				int i = ENABLED[k];
				ModuleState state = layout.modules().get(i);
				ModuleView view = HUD_VIEWS[i];
				view.update(state, ModuleView.HUD, now);
				drawPlaced(c, HUD_PEN.on(c, HUD_STYLES[i]), view, state, screenW, screenH, guiScale, null, i, true);
			}
		} finally {
			c.end();
			FlintFrame.end();
		}
	}

	/**
	 * Whether Minecraft's own effect icons are left out: the Effects module is on with "Hide Minecraft's effect
	 * icons", and Flint's HUD is showing (with the F3 overlay hiding Flint's HUD, Minecraft's icons stay).
	 */
	public static boolean hideVanillaEffects() {
		if (failed) {
			return false;
		}
		bind(shownLayout());
		if (!effectsHideVanilla) {
			return false;
		}
		return !Mc263HudData.debugOverlayOpen() || ControlState.settings().showHudWithDebugScreen();
	}

	/**
	 * From the client tick (once that hook calls it): stops frame-time recording once no FPS module has been
	 * drawn for a while, even when no HUD frame runs (the world was left, the HUD hidden), and once there is no
	 * world, drops whatever the module views read from the last one. Allocation-free.
	 */
	public static void tick() {
		long now = System.nanoTime();
		FpsMeter.maintain(now);
		observeSession(now);
		if (previewLayout != null) {
			if (Mc263ControlPlatform.currentScreen() instanceof KitScreen) {
				previewTicksAlone = 0;
				previewScreenSeenAt = now;
			} else if (++previewTicksAlone > 1) {
				// No Flint screen for more than a tick: whoever set the preview didn't clear it.
				previewLayout = null;
			}
		}
		if (holdsWorld && Mc263HudData.player() == null) {
			forgetWorld();
		}
	}

	/** The layout the HUD shows: setup's preview while one is set, else the active layout. */
	private static Layout shownLayout() {
		Layout preview = previewLayout;
		return preview != null ? preview : ControlState.layout();
	}

	/** Per HUD frame: a preview outlives no Flint screen for more than two ticks' time, even without the tick hook. */
	private static void guardPreview(long now) {
		if (previewLayout == null) {
			return;
		}
		if (Mc263ControlPlatform.currentScreen() instanceof KitScreen) {
			previewScreenSeenAt = now;
		} else if (now - previewScreenSeenAt > PREVIEW_ALONE_NANOS) {
			previewLayout = null;
		}
	}

	/**
	 * Joining a world and resuming from a pause restart the 1% low's window (frames from the loading screen or the
	 * pause menu aren't gameplay). Field reads; allocates only when the world changes.
	 */
	private static void observeSession(long now) {
		ClientLevel level = Mc263HudData.level();
		ClientLevel last = lastLevel == null ? null : lastLevel.get();
		if (level != last) {
			lastLevel = level == null ? null : new WeakReference<>(level);
			if (level != null) {
				FpsMeter.restartWindow(now);
			}
		}
		boolean pausing = level != null && Mc263HudData.pausing();
		if (wasPausing && !pausing) {
			FpsMeter.restartWindow(now);
		}
		wasPausing = pausing;
	}

	/** Every module view drops what it read from the world (the views are static and outlive it). */
	private static void forgetWorld() {
		holdsWorld = false;
		for (int i = 0; i < MODULES; i++) {
			if (HUD_VIEWS[i] != null) {
				HUD_VIEWS[i].forget();
				PREVIEW_VIEWS[i].forget();
			}
		}
	}

	/**
	 * Why "Show label" does nothing for {@code moduleId} in {@code theme} (it has no label there), or null when
	 * the setting applies. Settings panels disable the switch with this reason instead of offering a control
	 * that changes nothing. Allocation-free.
	 */
	public static @Nullable String noLabelReason(String moduleId, HudThemeId theme) {
		int index = ModuleRegistry.indexOf(moduleId);
		return index < 0 || PREVIEW_VIEWS[index] == null ? null : PREVIEW_VIEWS[index].noLabelReason(theme);
	}

	/** Why the padding setting does nothing for {@code moduleId} (it has no panel around it), or null. */
	public static @Nullable String noPaddingReason(String moduleId) {
		int index = ModuleRegistry.indexOf(moduleId);
		return index < 0 || PREVIEW_VIEWS[index] == null ? null : PREVIEW_VIEWS[index].noPaddingReason();
	}

	/** Why the accent colour does nothing for {@code moduleId} (it has no labels or highlights), or null. Allocation-free. */
	public static @Nullable String noAccentReason(String moduleId) {
		int index = ModuleRegistry.indexOf(moduleId);
		return index < 0 || PREVIEW_VIEWS[index] == null ? null : PREVIEW_VIEWS[index].noAccentReason();
	}

	/** Re-reads the enabled modules and the effects flag when the layout or its revision changed. Allocation-free. */
	private static void bind(Layout layout) {
		long revision = layout.revision();
		int switches = ControlState.layouts().switchCount();
		if (layout == bound && revision == boundRevision && switches == boundSwitches) {
			return;
		}
		bound = layout;
		boundRevision = revision;
		boundSwitches = switches;
		enabledCount = 0;
		for (int i = 0; i < MODULES; i++) {
			if (HUD_VIEWS[i] != null && layout.modules().get(i).enabled() && layout.modules().get(i).descriptor().status().usable()) {
				ENABLED[enabledCount++] = i;
			}
		}
		ModuleState effects = layout.modules().get(EFFECTS);
		effectsHideVanilla = effects.enabled() && effects.bool(ModuleSettings.Effects.HIDE_VANILLA_EFFECTS);
		if (boundScale > 0) {
			resolveStyles(layout, boundScale);
		}
	}

	private static void resolveStyles(Layout layout, int guiScale) {
		stylesStale = false;
		for (int i = 0; i < MODULES; i++) {
			ModuleState state = layout.modules().get(i);
			HUD_STYLES[i].resolve(layout.appearance(), state.appearance(), null, state.placement().scale(), guiScale);
		}
	}

	/**
	 * Measures {@code view} (already updated), places it by its placement on a {@code screenW × screenH} GUI-unit
	 * screen, clamped on screen, and draws it with its panel. Returns false when it has nothing to show. When
	 * {@code scene} is given, records the rectangle (GUI units) for module {@code index}.
	 */
	private static boolean drawPlaced(Canvas c, HudPen pen, ModuleView view, ModuleState state, float screenW, float screenH, int guiScale,
		@Nullable HudScene scene, int index, boolean hud) {
		view.measure(pen, state);
		if (view.width <= 0 || view.height <= 0) {
			return false;
		}
		HudStyle s = pen.s;
		int padX = view.panelled() ? s.padX : 0;
		int padY = view.panelled() ? s.padY : 0;
		int w = view.width + 2 * padX;
		int h = view.height + 2 * padY;
		float drawnW = (float) w / guiScale;
		float drawnH = (float) h / guiScale;
		HudPlacement placement = state.placement();
		float left = placement.clampedLeft(screenW, drawnW);
		float top = placement.clampedTop(screenH, drawnH);
		int x = Math.round(left * guiScale);
		int y = Math.round(top * guiScale);
		if (scene != null) {
			scene.set(index, HudScene.SHOWN, (float) x / guiScale, (float) y / guiScale, drawnW, drawnH, null);
		}
		if (hud) {
			RECTS[4 * index] = (float) x / guiScale;
			RECTS[4 * index + 1] = (float) y / guiScale;
			RECTS[4 * index + 2] = drawnW;
			RECTS[4 * index + 3] = drawnH;
			RECT_FRAME[index] = hudFrame;
		}
		if (view.panelled()) {
			pen.panel(x, y, w, h);
		}
		view.draw(pen, state, x + padX, y + padY);
		return true;
	}

	// ---------- the HUD editor ----------

	/**
	 * Draws every enabled module of {@code layout} for the HUD editor, exactly where and as the HUD would (live
	 * data), on a canvas begun in GUI units, and records each module's rectangle in {@code scene}. A module with
	 * nothing to show here is not drawn: it gets its placeholder size (as if it showed dashes) and the reason, for
	 * the editor's outline. Called once per editor frame; allocation-free.
	 */
	public static void drawScene(Canvas c, Layout layout, float screenW, float screenH, HudScene scene) {
		long now = System.nanoTime();
		FpsMeter.maintain(now);
		holdsWorld = true;
		// The HUD isn't drawn while the editor is open; it resolves its styles again before its next frame.
		stylesStale = true;
		int guiScale = c.guiScale();
		for (int i = 0; i < MODULES; i++) {
			ModuleState state = layout.modules().get(i);
			if (!state.enabled() || HUD_VIEWS[i] == null) {
				scene.off(i);
				continue;
			}
			ModuleView view = HUD_VIEWS[i];
			HudStyle style = HUD_STYLES[i];
			style.resolve(layout.appearance(), state.appearance(), null, state.placement().scale(), guiScale);
			HudPen pen = HUD_PEN.on(c, style);
			view.update(state, ModuleView.HUD, now);
			if (drawPlaced(c, pen, view, state, screenW, screenH, guiScale, scene, i, false)) {
				continue;
			}
			view.update(state, ModuleView.PLACEHOLDER, now);
			view.measure(pen, state);
			int padX = view.panelled() ? style.padX : 0;
			int padY = view.panelled() ? style.padY : 0;
			float w = (float) Math.max(view.width + 2 * padX, guiScale * 24) / guiScale;
			float h = (float) Math.max(view.height + 2 * padY, guiScale * 11) / guiScale;
			float left = Math.round(state.placement().clampedLeft(screenW, w) * guiScale) / (float) guiScale;
			float top = Math.round(state.placement().clampedTop(screenH, h) * guiScale) / (float) guiScale;
			scene.set(i, HudScene.OUTLINE, left, top, w, h, reason(view));
		}
	}

	/** Why a module shows nothing: its availability (needs a world, servers only...) or its own reason. */
	private static String reason(ModuleView view) {
		ModuleDescriptor descriptor = view.descriptor;
		String unavailable = descriptor.availability(ControlState.inWorld(), ControlState.multiplayer(), ControlState.latencyKnown(),
			ControlState.networkCoreRunning()).reason();
		return unavailable != null ? unavailable : view.emptyReason();
	}

	// ---------- previews ----------

	// ---------- for the Control Center ----------

	/**
	 * A short live summary of the module for the module list ("144 FPS", "32 ms", "8 · 3 CPS", "WASD · mouse ·
	 * jump", "−4 · 72 · 3", "South", "4 pieces · lowest 87%", "3 effects"), from the active layout's settings, or
	 * null when there is no live data (no world for the world modules; Ping off a server or before the server's
	 * first report; FPS before its first measurement). Refreshed at most four times a second per module; a new
	 * String is made only when the text changed. Callable every frame.
	 */
	public static @Nullable String summary(String moduleId) {
		int index = ModuleRegistry.indexOf(moduleId);
		if (index < 0 || PREVIEW_VIEWS[index] == null) {
			return null;
		}
		long now = System.nanoTime();
		if (SUMMARY_MADE[index] && now - SUMMARY_AT[index] < SUMMARY_NANOS) {
			return SUMMARIES[index];
		}
		SUMMARY_MADE[index] = true;
		SUMMARY_AT[index] = now;
		FpsMeter.maintain(now);
		int length = PREVIEW_VIEWS[index].summary(ControlState.layout().modules().get(index), SUMMARY_CHARS, now);
		String last = SUMMARIES[index];
		if (length < 0) {
			SUMMARIES[index] = null;
		} else if (last == null || !HudFormat.same(last, SUMMARY_CHARS, length)) {
			SUMMARIES[index] = new String(SUMMARY_CHARS, 0, length);
		}
		return SUMMARIES[index];
	}

	/**
	 * The module's rectangle on screen in GUI units ({@code x, y, width, height}) from the last HUD frame, into
	 * {@code out4}; false when it wasn't drawn then (off, nothing to show, the HUD hidden or skipped, or no HUD frame
	 * for half a second). Allocation-free.
	 */
	public static boolean lastRect(String moduleId, float[] out4) {
		int index = ModuleRegistry.indexOf(moduleId);
		if (index < 0 || hudFrame == 0 || RECT_FRAME[index] != hudFrame || System.nanoTime() - hudFrameAt > RECT_STALE_NANOS) {
			return false;
		}
		System.arraycopy(RECTS, 4 * index, out4, 0, 4);
		return true;
	}

	/**
	 * While set, the HUD draws {@code layout} instead of the active layout (first-time setup's live preview); null
	 * clears it. The caller clears it when its screen closes; it is also cleared once no Flint screen has been open
	 * for more than a tick. Allocation-free.
	 */
	public static void setPreviewLayout(@Nullable Layout layout) {
		previewLayout = layout;
		previewTicksAlone = 0;
		previewScreenSeenAt = System.nanoTime();
	}

	/** Whether {@code moduleId} is drawn on the HUD (has a view here). */
	public static boolean drawn(String moduleId) {
		int index = ModuleRegistry.indexOf(moduleId);
		return index >= 0 && HUD_VIEWS[index] != null;
	}

	/** Whether {@code moduleId} sets its whole look itself (FPS), so the HUD editor's style rows don't apply to it. */
	public static boolean ownLook(String moduleId) {
		int index = ModuleRegistry.indexOf(moduleId);
		return index >= 0 && HUD_VIEWS[index] != null && HUD_VIEWS[index].ownLook();
	}

	/**
	 * A module's settings preview: draws it with {@code layout}'s settings and fixed sample numbers ({@link ModuleView#SAMPLE})
	 * centred in the pixel box, at {@code unitPx} pixels per GUI unit (the GUI scale: the size it has in the game) and its
	 * own size step, so it looks exactly as on the HUD. When that doesn't fit inside the box less a margin, the unit is
	 * lowered until it does. Returns the unit used (0 when nothing was drawn). Allocation-free.
	 */
	public static int drawSample(Canvas canvas, Layout layout, String moduleId, int xPx, int yPx, int wPx, int hPx, int unitPx) {
		int index = ModuleRegistry.indexOf(moduleId);
		if (index < 0 || wPx <= 0 || hPx <= 0 || PREVIEW_VIEWS[index] == null) {
			return 0;
		}
		long now = System.nanoTime();
		ModuleState state = layout.modules().get(index);
		ModuleView view = PREVIEW_VIEWS[index];
		view.update(state, ModuleView.SAMPLE, now);
		HudPen pen = PREVIEW_PEN.on(canvas, PREVIEW_STYLE);
		float step = state.placement().scale();
		int unit = Math.max(1, unitPx);
		int w = 0;
		int h = 0;
		for (; unit >= 1; unit--) {
			PREVIEW_STYLE.resolve(layout.appearance(), state.appearance(), null, step, unit);
			view.measure(pen, state);
			w = view.width + (view.panelled() ? 2 * PREVIEW_STYLE.padX : 0);
			h = view.height + (view.panelled() ? 2 * PREVIEW_STYLE.padY : 0);
			int margin = 6 * unit;
			if (unit == 1 || w <= wPx - 2 * margin && h <= hPx - 2 * margin) {
				break;
			}
		}
		if (w <= 0 || h <= 0) {
			return 0;
		}
		int x = xPx + (wPx - w) / 2;
		int y = yPx + (hPx - h) / 2;
		boolean clip = w > wPx || h > hPx;
		if (clip) {
			canvas.pushClip(xPx, yPx, xPx + wPx, yPx + hPx);
		}
		try {
			if (view.panelled()) {
				pen.panel(x, y, w, h);
				view.draw(pen, state, x + PREVIEW_STYLE.padX, y + PREVIEW_STYLE.padY);
			} else {
				view.draw(pen, state, x, y);
			}
		} finally {
			if (clip) {
				canvas.popClip();
			}
		}
		return Math.max(1, unit);
	}

	/**
	 * Draws one module with {@code layout}'s settings, centred and fitted inside the pixel box on a canvas that
	 * is already begun (any unit scale). Live data in a world; dashes where there is none (never made-up
	 * numbers). {@code theme} overrides the module's style when not null (setup previews). The module is drawn at
	 * its own size step, or the largest smaller step that fits; if even the smallest doesn't, it is clipped to
	 * the box.
	 */
	public static void drawPreview(Canvas canvas, Layout layout, String moduleId, int xPx, int yPx, int wPx, int hPx,
		@Nullable HudThemeId theme) {
		int index = ModuleRegistry.indexOf(moduleId);
		if (index < 0 || wPx <= 0 || hPx <= 0 || PREVIEW_VIEWS[index] == null) {
			return;
		}
		long now = System.nanoTime();
		FpsMeter.maintain(now);
		holdsWorld = true;
		ModuleState state = layout.modules().get(index);
		ModuleView view = PREVIEW_VIEWS[index];
		view.update(state, ModuleView.PREVIEW, now);
		int unitPx = Math.max(1, canvas.scale());
		int margin = 4 * unitPx;
		HudPen pen = PREVIEW_PEN.on(canvas, PREVIEW_STYLE);
		int w = 0;
		int h = 0;
		for (int step = ScaleSteps.index(state.placement().scale()); step >= 0; step--) {
			PREVIEW_STYLE.resolve(layout.appearance(), state.appearance(), theme, ScaleSteps.step(step), unitPx);
			view.measure(pen, state);
			w = view.width + (view.panelled() ? 2 * PREVIEW_STYLE.padX : 0);
			h = view.height + (view.panelled() ? 2 * PREVIEW_STYLE.padY : 0);
			if (w <= wPx - 2 * margin && h <= hPx - 2 * margin) {
				break;
			}
		}
		if (w <= 0 || h <= 0) {
			return;
		}
		int x = xPx + (wPx - w) / 2;
		int y = yPx + (hPx - h) / 2;
		boolean clip = w > wPx || h > hPx;
		if (clip) {
			canvas.pushClip(xPx, yPx, xPx + wPx, yPx + hPx);
		}
		try {
			if (view.panelled()) {
				pen.panel(x, y, w, h);
				view.draw(pen, state, x + PREVIEW_STYLE.padX, y + PREVIEW_STYLE.padY);
			} else {
				view.draw(pen, state, x, y);
			}
		} finally {
			if (clip) {
				canvas.popClip();
			}
		}
	}

	/**
	 * Draws a miniature 16:9 screen showing every enabled module of {@code layout} where it sits (setup
	 * previews), on a canvas already begun. The screen is as tall in GUI units as the player's own window, so
	 * modules keep their real size and anchors relative to it, and the hotbar is sketched for orientation.
	 *
	 * <p>At that scale a module's text would be two to five pixels tall, too small to read or to rasterise
	 * crisply, so modules are drawn as their real shapes instead: panels in their style and opacity, items and
	 * icons as squares, and each piece of text as a rounded bar exactly as wide as the text would be. Positions,
	 * sizes, padding and styles are the real ones; only the glyphs are abstracted.
	 */
	public static void drawLayoutPreview(Canvas canvas, Layout layout, int xPx, int yPx, int wPx, int hPx, @Nullable HudThemeId theme) {
		if (wPx <= 2 || hPx <= 2) {
			return;
		}
		long now = System.nanoTime();
		FpsMeter.maintain(now);
		holdsWorld = true;
		int fw = wPx;
		int fh = Math.round(fw * 9 / 16.0F);
		if (fh > hPx) {
			fh = hPx;
			fw = Math.round(fh * 16 / 9.0F);
		}
		int fx = xPx + (wPx - fw) / 2;
		int fy = yPx + (hPx - fh) / 2;
		int unitPx = Math.max(1, canvas.scale());
		int line = Kit.hairline(canvas);
		canvas.panelCorners(fx, fy, fw, fh, 3 * unitPx, line, Tokens.outline(), Tokens.WELL);

		int screenH = Math.max(1, Minecraft.getInstance().getWindow().getGuiScaledHeight());
		float screenW = screenH * 16 / 9.0F;
		float k = fh / (float) screenH;
		// The hotbar, for orientation.
		float hotbarLeft = (float) Math.floor(screenW / 2) - VanillaAreas.HOTBAR_WIDTH / 2.0F;
		int hx = fx + Math.round(hotbarLeft * k);
		int hy = fy + Math.round((screenH - VanillaAreas.HOTBAR_HEIGHT) * k);
		canvas.fill(hx, hy, hx + Math.max(1, Math.round(VanillaAreas.HOTBAR_WIDTH * k)), fy + fh - Math.max(1, line), 0x14FFFFFF);

		canvas.pushClip(fx, fy, fx + fw, fy + fh);
		try {
			for (int i = 0; i < MODULES; i++) {
				ModuleState state = layout.modules().get(i);
				if (state.enabled() && PREVIEW_VIEWS[i] != null) {
					miniature(canvas, layout, state, i, theme, unitPx, now, fx, fy, screenW, screenH, k);
				}
			}
		} finally {
			canvas.popClip();
		}
	}

	private static void miniature(Canvas canvas, Layout layout, ModuleState state, int index, @Nullable HudThemeId theme, int unitPx,
		long now, int fx, int fy, float screenW, float screenH, float k) {
		ModuleView view = PREVIEW_VIEWS[index];
		view.update(state, ModuleView.PREVIEW, now);
		HudPen pen = PREVIEW_PEN.on(canvas, PREVIEW_STYLE);
		PREVIEW_STYLE.resolve(layout.appearance(), state.appearance(), theme, state.placement().scale(), unitPx);
		view.measure(pen, state);
		if (view.width <= 0 || view.height <= 0) {
			return;
		}
		int padX = view.panelled() ? PREVIEW_STYLE.padX : 0;
		int padY = view.panelled() ? PREVIEW_STYLE.padY : 0;
		int w = view.width + 2 * padX;
		int h = view.height + 2 * padY;
		// Measured in canvas pixels at unitPx per GUI unit: back to GUI units, placed, then scaled down.
		float drawnW = (float) w / unitPx;
		float drawnH = (float) h / unitPx;
		float left = state.placement().clampedLeft(screenW, drawnW);
		float top = state.placement().clampedTop(screenH, drawnH);
		float ox = fx + left * k;
		float oy = fy + top * k;
		float scale = k / unitPx;
		pen.startRecording();
		try {
			if (view.panelled()) {
				pen.panel(0, 0, w, h);
			}
			view.draw(pen, state, padX, padY);
		} finally {
			pen.stopRecording();
		}
		for (int r = 0; r < pen.recordCount(); r++) {
			sketch(canvas, pen, r, ox, oy, scale);
		}
	}

	/** Redraws one recorded shape scaled down: text as a bar of its width, the rest as its plain shape. */
	private static void sketch(Canvas canvas, HudPen pen, int r, float ox, float oy, float scale) {
		int x0 = Math.round(ox + pen.record(r, 0) * scale);
		int y0 = Math.round(oy + pen.record(r, 1) * scale);
		int x1 = Math.max(x0 + 1, Math.round(ox + (pen.record(r, 0) + pen.record(r, 2)) * scale));
		int y1 = Math.max(y0 + 1, Math.round(oy + (pen.record(r, 1) + pen.record(r, 3)) * scale));
		int color = pen.record(r, 5);
		int radius = Math.round(pen.record(r, 6) * scale);
		switch (pen.record(r, 4)) {
			case HudPen.TEXT -> {
				int h = y1 - y0;
				canvas.roundRectCorners(x0, y0, x1 - x0, h, h / 2, Palette.withAlpha(color, (color >>> 24) / 255.0F * 0.8F));
			}
			case HudPen.PANEL -> canvas.roundRectCorners(x0, y0, x1 - x0, y1 - y0, radius, color);
			case HudPen.RING -> {
				canvas.fill(x0, y0, x1, y0 + 1, color);
				canvas.fill(x0, y1 - 1, x1, y1, color);
				canvas.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
				canvas.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
			}
			case HudPen.ITEM -> canvas.roundRectCorners(x0, y0, x1 - x0, y1 - y0, Math.min(2, (x1 - x0) / 4), 0x998E8A83);
			case HudPen.SPRITE, HudPen.ICON -> canvas.roundRectCorners(x0, y0, x1 - x0, y1 - y0, Math.min(2, (x1 - x0) / 4),
				Palette.withAlpha(color, (color >>> 24) / 255.0F * 0.6F));
			default -> canvas.fill(x0, y0, x1, y1, color);
		}
	}
}
