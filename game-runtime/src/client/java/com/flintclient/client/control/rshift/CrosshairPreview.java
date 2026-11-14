package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;
import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.CrosshairDrawing;
import com.flintclient.client.control.feature.CrosshairPicture;
import com.flintclient.client.control.feature.CrosshairPresets;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods.Crosshair;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.PageControls.TextButton;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Crosshair's live preview, kept in view at the top of its page while the settings scroll: the crosshair at the size the game
 * draws it (the game's GUI scale) over a plain dark backdrop (or a light one, or a Minecraft day, from the toggle in its corner),
 * redrawn the moment a setting changes. Beside it, the
 * presets ({@link CrosshairPresets}): a row of tiles, each showing its crosshair in your colours, one click to take its shape
 * (the chosen one ringed in the accent); and the way to the drawing pad.
 */
final class CrosshairPreview extends Node {
	private static final float HEIGHT = 128;
	/** A preset tile's side, and the room between tiles. */
	private static final float TILE = 52;
	private static final float TILE_GAP = 8;
	private final Box box = new Box("crosshair_preview_page");
	private final Label title = new Label(Type.SECTION_TITLE, "Presets");
	private final Label size = new Label(Type.ROW_NOTE, "");
	private final TextButton draw;
	private final List<PresetTile> tiles = new ArrayList<>();
	/** Behind the preview: Dark (the default), Light or a Minecraft day; remembered. */
	private final PageControls.Segmented backdrop;

	CrosshairPreview(CrosshairPad pad) {
		for (CrosshairPresets.Preset preset : CrosshairPresets.ALL) {
			tiles.add(add(new PresetTile(preset)));
		}
		draw = add(new TextButton("Draw your own", Glyph.PEN, PageControls.Look.PLAIN, pad::show));
		backdrop = add(new PageControls.Segmented(new String[] {"Dark", "Light", "Scene"}, Box::mode, Box::setMode));
		backdrop.name("Preview background");
		backdrop.tooltip("Preview background\nCheck your crosshair on a dark backdrop, a light one or a Minecraft day");
	}

	/** One line of Minecraft's font with room around it, in layout units (the font keeps its pixel size on a small window). */
	private float line() {
		return Math.max(d(22), 9F * MenuStyle.pixelScale() / measure().scale() * 1.35F);
	}

	private float boxWidth(float width) {
		return Math.min(width * 0.42F, d(HEIGHT) * 2.0F);
	}

	/** How many tiles fit across the column beside the picture. */
	private int columns(float width) {
		float room = width - boxWidth(width) - d(24) - d(16);
		return Math.max(1, Math.min(tiles.size(), (int) Math.floor((room + d(TILE_GAP)) / (d(TILE) + d(TILE_GAP)))));
	}

	private float blockHeight(float width) {
		float buttonH = r(PageControls.TextButton.H) * 0.62F;
		int rows = (tiles.size() + columns(width) - 1) / columns(width);
		float column = d(14) + line() + d(4) + rows * d(TILE) + (rows - 1) * d(TILE_GAP) + d(12) + buttonH + d(14);
		return Math.max(d(HEIGHT), column);
	}

	@Override
	public float prefHeight(float width) {
		return blockHeight(width) + d(16);
	}

	@Override
	protected void layout() {
		float bh = r(PageControls.TextButton.H) * 0.62F;
		float left = x + boxWidth(w) + d(24);
		int cols = columns(w);
		float top = y + d(14) + line() + d(4);
		for (int i = 0; i < tiles.size(); i++) {
			tiles.get(i).setBounds(left + (i % cols) * (d(TILE) + d(TILE_GAP)), top + (i / cols) * (d(TILE) + d(TILE_GAP)), d(TILE), d(TILE));
		}
		draw.setBounds(left, y + blockHeight(w) - bh - d(14), draw.prefWidth(), bh);
		// The backdrop choice beside the pad's button, the same height.
		backdrop.setBounds(draw.x() + draw.width() + d(12), draw.y(), backdrop.prefWidth(), bh);
	}

	@Override
	protected void paint(Canvas c) {
		float bh = blockHeight(w);
		MenuStyle.block(c, x, y, w, bh, MenuStyle.radius(), 0, 0);
		float inset = d(8);
		box.paint(c, x + inset, y + inset, boxWidth(w) - inset, bh - 2 * inset);
		float left = x + boxWidth(w) + d(24);
		float room = x + w - d(16) - left;
		MenuStyle.fitted(c, title, left, y + d(14) + line() / 2, MenuStyle.TEXT, room);
		size.set("At your GUI scale (" + Frame.windowGuiScale() + "×)");
		float after = backdrop.x() + backdrop.width() + d(16);
		MenuStyle.fitted(c, size, after, draw.y() + draw.height() / 2, MenuStyle.NEUTRAL, x + w - d(16) - after);
	}

	/**
	 * A preset's tile: its crosshair in your colours (your own scale and offset left out, so it sits in the middle), as large as
	 * the game draws it when that fits the tile, else at the largest whole scale that does; ringed in the accent while your
	 * crosshair has its shape. Worked out again only when a setting changes.
	 */
	private static final class PresetTile extends Pressable {
		private final CrosshairPresets.Preset preset;
		private final ModuleState sample = new ModuleState(com.flintclient.client.control.model.mods.PlayerMods.Crosshair.DESCRIPTOR);
		private @Nullable CrosshairPicture picture;
		private int[] background = new int[0];
		private long drawnFor = Long.MIN_VALUE;
		private final Runs runs = new Runs();
		private float on = -1;

		PresetTile(CrosshairPresets.Preset preset) {
			super(null);
			this.preset = preset;
			setAction(this::choose);
			name("Preset " + preset.name());
			tooltip(preset.name());
		}

		private void choose() {
			ModuleState s = ControlState.layout().module(Crosshair.ID);
			if (s != null && preset.apply(s)) {
				ControlState.settings().recordChange(Crosshair.ID);
			}
		}

		@Override
		protected void paint(Canvas c) {
			ModuleState s = ControlState.layout().module(Crosshair.ID);
			if (s == null) {
				return;
			}
			boolean chosen = preset.matches(s);
			on = MenuStyle.ease(on, chosen);
			float rad = MenuStyle.radius();
			int fill = Palette.lerp(MenuStyle.CHIP, MenuStyle.CHIP_HOVER, hover);
			MenuStyle.box(c, x, y, w, h, rad, fill);
			int x0 = c.px(x);
			int y0 = c.px(y);
			int pw = c.px(x + w) - x0;
			int ph = c.px(y + h) - y0;
			if (pw > 4 && ph > 4) {
				int gs = Frame.windowGuiScale();
				CrosshairDrawing drawing = CrosshairDrawing.shared();
				long key = (((long) System.identityHashCode(s) * 31 + s.revision()) * 31 + gs) * 31 + pw * 4099L + ph + fill;
				if (key != drawnFor || picture == null) {
					drawnFor = key;
					sample.copyFrom(s);
					sample.set(Crosshair.SCALE, 100);
					sample.set(Crosshair.OFFSET_X, 0);
					sample.set(Crosshair.OFFSET_Y, 0);
					preset.apply(sample);
					// The game's own size when it fits the tile, else the largest whole scale that does, never under 2 (at 1 a hairline
					// cross is under a pixel wide): sharp either way.
					float reach = sample.integer(Crosshair.ROTATION) % 90 == 0 ? CrosshairPicture.extent(sample, drawing) / 1.4143F
						: CrosshairPicture.extent(sample, drawing);
					int scale = Math.max(Math.min(2, gs), Math.min(gs, (int) Math.floor((Math.min(pw, ph) / 2.0F - 1) / reach)));
					if (picture == null || picture.width() != pw || picture.height() != ph) {
						picture = new CrosshairPicture(pw, ph);
						background = new int[pw * ph];
					}
					java.util.Arrays.fill(background, 0xFF000000 | fill);
					picture.draw(sample, scale, background, drawing, 0);
					runs.from(picture);
				}
				runs.paint(c, x0, y0);
			}
			MenuStyle.ring(c, x, y, w, h, rad, Math.max(1, c.px(d(1.5F))), Palette.withAlpha(MenuStyle.ACCENT, Math.max(0, on)));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** A crosshair picture as runs of one colour along each row, drawn as fills (no texture to upload when it changes). */
	static final class Runs {
		/** x0, x1, y, colour for each run. */
		private int[] runs = new int[256];
		private int count;

		void from(CrosshairPicture p) {
			count = 0;
			int width = p.width();
			int[] px = p.pixels();
			for (int y = 0; y < p.height(); y++) {
				int row = y * width;
				int x = 0;
				while (x < width) {
					int color = px[row + x];
					if (color == 0) {
						x++;
						continue;
					}
					int end = x + 1;
					while (end < width && px[row + end] == color) {
						end++;
					}
					if ((count + 1) * 4 > runs.length) {
						runs = java.util.Arrays.copyOf(runs, runs.length * 2);
					}
					int at = count * 4;
					runs[at] = x;
					runs[at + 1] = end;
					runs[at + 2] = y;
					runs[at + 3] = color;
					count++;
					x = end;
				}
			}
		}

		void paint(Canvas c, int x0, int y0) {
			for (int i = 0; i < count; i++) {
				int at = i * 4;
				c.fill(x0 + runs[at], y0 + runs[at + 2], x0 + runs[at + 1], y0 + runs[at + 2] + 1, runs[at + 3]);
			}
		}
	}

	/**
	 * The preview itself, for the page and the pad: the scene as a texture (made again only when its size or the GUI scale
	 * changes) and the crosshair as runs of pixels (worked out again only when a setting or the drawing changes; each frame with
	 * the rainbow).
	 */
	static final class Box {
		private final String path;
		private @Nullable Texture scene;
		private int sceneW;
		private int sceneH;
		private int sceneScale;
		private int[] background = new int[0];
		private @Nullable CrosshairPicture picture;
		private long drawnFor = Long.MIN_VALUE;
		/** The crosshair's runs: x0, x1, y, colour for each. */
		private int[] runs = new int[256];
		private int runCount;

		private int sceneMode = -1;

		Box(String path) {
			this.path = path;
		}

		/** The backdrop chosen: 0 dark (the default), 1 light, 2 a Minecraft day; kept with Flint's menu settings. */
		static int mode() {
			com.flintclient.client.control.store.ControlSettings s = ControlState.settings();
			return s.sectionCollapsed("crosshair-preview/light") ? 1 : s.sectionCollapsed("crosshair-preview/scene") ? 2 : 0;
		}

		static void setMode(int mode) {
			com.flintclient.client.control.store.ControlSettings s = ControlState.settings();
			s.setSectionCollapsed("crosshair-preview/light", mode == 1);
			s.setSectionCollapsed("crosshair-preview/scene", mode == 2);
		}

		void paint(Canvas c, float x, float y, float w, float h) {
			int x0 = c.px(x);
			int y0 = c.px(y);
			int pw = c.px(x + w) - x0;
			int ph = c.px(y + h) - y0;
			if (pw <= 4 || ph <= 4) {
				return;
			}
			int gs = Frame.windowGuiScale();
			ensureScene(pw, ph, gs);
			ModuleState s = ControlState.layout().module(Crosshair.ID);
			if (s == null || scene == null) {
				return;
			}
			c.pushClip(x0, y0, x0 + pw, y0 + ph);
			c.texture(scene, x0, y0, sceneW * gs, sceneH * gs, sceneW, sceneH, 0xFFFFFFFF);
			CrosshairDrawing drawing = CrosshairDrawing.shared();
			boolean rainbow = s.bool(Crosshair.RAINBOW);
			int ticks = rainbow ? (int) (Clock.millis() / 16 % 125000) : 0;
			long key = (((long) System.identityHashCode(s) * 31 + s.revision()) * 31 + drawing.revision()) * 31 + gs;
			key = (key * 31 + pw) * 31 + ph;
			key = key * 31 + ticks;
			if (key != drawnFor || picture == null) {
				drawnFor = key;
				if (picture == null || picture.width() != pw || picture.height() != ph) {
					picture = new CrosshairPicture(pw, ph);
				}
				picture.draw(s, gs, background, drawing, ticks);
				runs(picture);
			}
			for (int i = 0; i < runCount; i++) {
				int at = i * 4;
				c.fill(x0 + runs[at], y0 + runs[at + 2], x0 + runs[at + 1], y0 + runs[at + 2] + 1, runs[at + 3]);
			}
			c.popClip();
			// Soft corners and a hairline, the menu's own look around the picture.
			int radius = c.px(MenuStyle.radius() * 0.75F);
			c.roundCorners(x0, y0, pw, ph, radius, MenuStyle.BG2);
			c.ring(x0, y0, pw, ph, radius, Math.max(1, c.px(d(1))), MenuStyle.SECTION_EDGE);
		}

		/** The scene for a {@code pw × ph} pixel box at GUI scale {@code gs}, and each pixel's colour behind the crosshair. */
		private void ensureScene(int pw, int ph, int gs) {
			int tw = (pw + gs - 1) / gs;
			int th = (ph + gs - 1) / gs;
			int mode = mode();
			if (scene != null && tw == sceneW && th == sceneH && gs == sceneScale && background.length == pw * ph && mode == sceneMode) {
				return;
			}
			sceneMode = mode;
			int[] texels = mode == 2 ? CrosshairPicture.scene(tw, th) : CrosshairPicture.backdrop(tw, th, mode == 1);
			scene = Texture.upload(path, tw, th, texels, false);
			sceneW = tw;
			sceneH = th;
			sceneScale = gs;
			background = new int[pw * ph];
			for (int y = 0; y < ph; y++) {
				for (int x = 0; x < pw; x++) {
					background[y * pw + x] = texels[(y / gs) * tw + x / gs];
				}
			}
			drawnFor = Long.MIN_VALUE;
		}

		/** The picture's pixels as runs of one colour along each row. */
		private void runs(CrosshairPicture p) {
			runCount = 0;
			int width = p.width();
			int[] px = p.pixels();
			for (int y = 0; y < p.height(); y++) {
				int row = y * width;
				int x = 0;
				while (x < width) {
					int color = px[row + x];
					if (color == 0) {
						x++;
						continue;
					}
					int end = x + 1;
					while (end < width && px[row + end] == color) {
						end++;
					}
					if ((runCount + 1) * 4 > runs.length) {
						runs = java.util.Arrays.copyOf(runs, runs.length * 2);
					}
					int at = runCount * 4;
					runs[at] = x;
					runs[at + 1] = end;
					runs[at + 2] = y;
					runs[at + 3] = color;
					runCount++;
					x = end;
				}
			}
		}
	}
}
